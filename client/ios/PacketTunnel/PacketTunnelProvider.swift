import Foundation
import Libbox
import Network
import NetworkExtension

class PacketTunnelProvider: NEPacketTunnelProvider,
    LibboxPlatformInterfaceProtocol,
    LibboxCommandServerHandlerProtocol,
    LibboxCommandClientHandlerProtocol {

    private let appGroupId = "group.com.shieldbox.vpn"
    private let configDefaultsKey = "vpn_config_json"

    private var boxService: LibboxBoxService?
    private var commandServer: LibboxCommandServer?
    private var commandClient: LibboxCommandClient?
    private var pathMonitor: NWPathMonitor?
    private var interfaceListener: (any LibboxInterfaceUpdateListenerProtocol)?

    private var lastUplinkSpeed: Int64 = 0
    private var lastDownlinkSpeed: Int64 = 0
    private var lastUplinkTotal: Int64 = 0
    private var lastDownlinkTotal: Int64 = 0

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        guard let containerUrl = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId),
              let sharedDefaults = UserDefaults(suiteName: appGroupId),
              let configJson = sharedDefaults.string(forKey: configDefaultsKey),
              !configJson.isEmpty else {
            completionHandler(NSError(
                domain: "com.shieldbox.vpn",
                code: 1001,
                userInfo: [NSLocalizedDescriptionKey: "Missing VPN configuration in App Group UserDefaults"]
            ))
            return
        }

        // Enforce strict Go memory limit inside NetworkExtension (50 MB OS cap)
        LibboxSetMemoryLimit(true)

        let setupOptions = LibboxSetupOptions()
        setupOptions.basePath = containerUrl.path
        setupOptions.workingPath = containerUrl.appendingPathComponent("sing-box").path
        setupOptions.tempPath = containerUrl.appendingPathComponent("tmp").path

        var setupError: NSError?
        LibboxSetup(setupOptions, &setupError)
        if let setupError = setupError {
            completionHandler(setupError)
            return
        }

        var serverError: NSError?
        let server = LibboxNewCommandServer(self, 100, &serverError)
        if let serverError = serverError {
            completionHandler(serverError)
            return
        }
        do {
            try server?.start()
            commandServer = server
        } catch {
            completionHandler(error)
            return
        }

        var serviceError: NSError?
        guard let service = LibboxNewService(configJson, self, &serviceError) else {
            completionHandler(serviceError ?? NSError(domain: "com.shieldbox.vpn", code: 1002, userInfo: nil))
            return
        }

        do {
            try service.start()
            boxService = service
            try commandServer?.setService(service)
        } catch {
            completionHandler(error)
            return
        }

        let clientOptions = LibboxCommandClientOptions()
        clientOptions.command = LibboxCommandStatus
        clientOptions.statusInterval = 1_000_000_000 // 1s
        let client = LibboxNewCommandClient(self, clientOptions)
        try? client?.connect()
        commandClient = client

        completionHandler(nil)
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        try? commandClient?.disconnect()
        commandClient = nil

        try? boxService?.close()
        boxService = nil

        try? commandServer?.close()
        commandServer = nil

        pathMonitor?.cancel()
        pathMonitor = nil

        completionHandler()
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        let message = String(data: messageData, encoding: .utf8)
        if message == "stats" {
            let payload: [String: Int64] = [
                "uplinkSpeed": lastUplinkSpeed,
                "downlinkSpeed": lastDownlinkSpeed,
                "uplinkTotal": lastUplinkTotal,
                "downlinkTotal": lastDownlinkTotal
            ]
            let response = try? JSONSerialization.data(withJSONObject: payload)
            completionHandler?(response)
            return
        }
        completionHandler?(nil)
    }

    override func sleep(completionHandler: @escaping () -> Void) {
        boxService?.pause()
        completionHandler()
    }

    override func wake() {
        boxService?.wake()
    }

    // MARK: - LibboxPlatformInterfaceProtocol

    func underNetworkExtension() -> Bool {
        return true
    }

    func includeAllNetworks() -> Bool {
        return false
    }

    func usePlatformAutoDetectInterfaceControl() -> Bool {
        return false
    }

    func autoDetectInterfaceControl(_ fd: Int32) throws {}

    func openTun(_ options: (any LibboxTunOptionsProtocol)?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        guard let options = options else {
            throw NSError(domain: "com.shieldbox.vpn", code: 1003, userInfo: [NSLocalizedDescriptionKey: "Nil TunOptions"])
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        // Enforce MTU 1400 for stability across cellular/QUIC networks and low memory overhead
        settings.mtu = NSNumber(value: min(Int(options.getMTU()), 1400))

        // IPv4 addresses & routes
        var ipv4Addresses: [String] = []
        var ipv4Masks: [String] = []
        if let inet4Iter = options.getInet4Address() {
            while inet4Iter.hasNext() {
                if let prefix = inet4Iter.next() {
                    ipv4Addresses.append(prefix.address())
                    ipv4Masks.append(prefix.mask())
                }
            }
        }
        if !ipv4Addresses.isEmpty {
            let ipv4Settings = NEIPv4Settings(addresses: ipv4Addresses, subnetMasks: ipv4Masks)
            if options.getAutoRoute() {
                var included: [NEIPv4Route] = []
                if let routeIter = options.getInet4RouteAddress(), routeIter.hasNext() {
                    while routeIter.hasNext() {
                        if let p = routeIter.next() {
                            included.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask()))
                        }
                    }
                } else {
                    included.append(NEIPv4Route.default())
                }
                ipv4Settings.includedRoutes = included

                var excluded: [NEIPv4Route] = []
                if let exIter = options.getInet4RouteExcludeAddress() {
                    while exIter.hasNext() {
                        if let p = exIter.next() {
                            excluded.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask()))
                        }
                    }
                }
                ipv4Settings.excludedRoutes = excluded
            }
            settings.ipv4Settings = ipv4Settings
        }

        // IPv6 addresses & routes
        var ipv6Addresses: [String] = []
        var ipv6Prefixes: [NSNumber] = []
        if let inet6Iter = options.getInet6Address() {
            while inet6Iter.hasNext() {
                if let prefix = inet6Iter.next() {
                    ipv6Addresses.append(prefix.address())
                    ipv6Prefixes.append(NSNumber(value: prefix.prefix()))
                }
            }
        }
        if !ipv6Addresses.isEmpty {
            let ipv6Settings = NEIPv6Settings(addresses: ipv6Addresses, networkPrefixLengths: ipv6Prefixes)
            if options.getAutoRoute() {
                ipv6Settings.includedRoutes = [NEIPv6Route.default()]
            }
            settings.ipv6Settings = ipv6Settings
        }

        // DNS settings
        if options.getAutoRoute(), let dnsServer = (try? options.getDNSServerAddress())?.value, !dnsServer.isEmpty {
            let dnsSettings = NEDNSSettings(servers: [dnsServer])
            dnsSettings.matchDomains = [""]
            settings.dnsSettings = dnsSettings
        }

        let semaphore = DispatchSemaphore(value: 0)
        var applyError: Error?
        setTunnelNetworkSettings(settings) { err in
            applyError = err
            semaphore.signal()
        }
        semaphore.wait()

        if let applyError = applyError {
            throw applyError
        }

        let tunFd = resolveUtunFileDescriptor()
        if tunFd < 0 {
            throw NSError(domain: "com.shieldbox.vpn", code: 1004, userInfo: [NSLocalizedDescriptionKey: "Unable to acquire utun file descriptor"])
        }
        ret0_?.pointee = tunFd
    }

    private func resolveUtunFileDescriptor() -> Int32 {
        // Primary lookup via NEPacketTunnelFlow KVC
        if let fd = packetFlow.value(forKeyPath: "socket.fileDescriptor") as? Int32, fd >= 0 {
            return fd
        }
        // Fallback scan for utun control socket
        var buffer = [CChar](repeating: 0, count: Int(IFNAMSIZ))
        for fd in Int32(3)..<Int32(1024) {
            var len = socklen_t(buffer.count)
            if getsockopt(fd, 2 /* SYSPROTO_CONTROL */, 2 /* UTUN_OPT_IFNAME */, &buffer, &len) == 0 {
                let name = String(cString: buffer)
                if name.hasPrefix("utun") {
                    return fd
                }
            }
        }
        return -1
    }

    func useProcFS() -> Bool { return false }

    func findConnectionOwner(_ ipProtocol: Int32, sourceAddress: String?, sourcePort: Int32, destinationAddress: String?, destinationPort: Int32, ret0_: UnsafeMutablePointer<Int32>?) throws {
        ret0_?.pointee = -1
    }

    func packageName(byUid uid: Int32, error: NSErrorPointer) -> String { return "" }

    func uid(byPackageName packageName: String?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        ret0_?.pointee = -1
    }

    func startDefaultInterfaceMonitor(_ listener: (any LibboxInterfaceUpdateListenerProtocol)?) throws {
        self.interfaceListener = listener
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let iface = path.availableInterfaces.first else {
                self?.interfaceListener?.updateDefaultInterface("", interfaceIndex: -1, isExpensive: false, isConstrained: false)
                return
            }
            self?.interfaceListener?.updateDefaultInterface(
                iface.name,
                interfaceIndex: Int32(iface.index),
                isExpensive: path.isExpensive,
                isConstrained: path.isConstrained
            )
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        pathMonitor = monitor
    }

    func closeDefaultInterfaceMonitor(_ listener: (any LibboxInterfaceUpdateListenerProtocol)?) throws {
        pathMonitor?.cancel()
        pathMonitor = nil
        interfaceListener = nil
    }

    func getInterfaces() throws -> any LibboxNetworkInterfaceIteratorProtocol {
        return EmptyInterfaceIterator()
    }

    func clearDNSCache() {}

    func readWIFIState() -> LibboxWIFIState? { return nil }

    func localDNSTransport() -> (any LibboxLocalDNSTransportProtocol)? { return nil }

    func send(_ notification: LibboxNotification?) throws {}

    // MARK: - LibboxCommandServerHandlerProtocol & LibboxCommandClientHandlerProtocol

    func serviceReload() throws {}

    func postServiceClose() {
        cancelTunnelWithError(nil)
    }

    func getSystemProxyStatus() -> LibboxSystemProxyStatus? { return nil }

    func setSystemProxyEnabled(_ isEnabled: Bool) throws {}

    func connected() {}

    func disconnected(_ message: String?) {}

    func clearLogs() {}

    func writeLogs(_ messageList: (any LibboxStringIteratorProtocol)?) {}

    func writeStatus(_ message: LibboxStatusMessage?) {
        guard let message = message else { return }
        lastUplinkSpeed = message.uplink
        lastDownlinkSpeed = message.downlink
        lastUplinkTotal = message.uplinkTotal
        lastDownlinkTotal = message.downlinkTotal
    }

    func writeGroups(_ message: (any LibboxOutboundGroupIteratorProtocol)?) {}

    func initializeClashMode(_ modeList: (any LibboxStringIteratorProtocol)?, currentMode: String?) {}

    func updateClashMode(_ newMode: String?) {}

    func writeConnections(_ message: LibboxConnections?) {}
}

private class EmptyInterfaceIterator: NSObject, LibboxNetworkInterfaceIteratorProtocol {
    func hasNext() -> Bool { return false }
    func next() -> LibboxNetworkInterface? { return nil }
}
