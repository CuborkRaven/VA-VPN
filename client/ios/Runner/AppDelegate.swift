import Flutter
import NetworkExtension
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterStreamHandler {

    private let methodChannelName = "com.shieldbox.vpn/control"
    private let eventChannelName = "com.shieldbox.vpn/events"
    private let appGroupId = "group.com.shieldbox.vpn"
    private let tunnelBundleId = "com.shieldbox.vpn.PacketTunnel"
    private let configDefaultsKey = "vpn_config_json"

    private var eventSink: FlutterEventSink?
    private var tunnelManager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?
    private var statsTimer: Timer?

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        GeneratedPluginRegistrant.register(with: self)

        if let controller = window?.rootViewController as? FlutterViewController {
            let methodChannel = FlutterMethodChannel(
                name: methodChannelName,
                binaryMessenger: controller.binaryMessenger
            )
            methodChannel.setMethodCallHandler { [weak self] call, result in
                self?.handleMethodCall(call, result: result)
            }

            let eventChannel = FlutterEventChannel(
                name: eventChannelName,
                binaryMessenger: controller.binaryMessenger
            )
            eventChannel.setStreamHandler(self)
        }

        loadOrCreateManager { _ in }
        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    deinit {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        statsTimer?.invalidate()
    }

    // MARK: - FlutterStreamHandler

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        emitCurrentStatus()
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        statsTimer?.invalidate()
        statsTimer = nil
        return nil
    }

    // MARK: - MethodChannel

    private func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startVpn":
            guard let args = call.arguments as? [String: Any],
                  let configJson = args["config"] as? String,
                  !configJson.isEmpty else {
                result(FlutterError(code: "INVALID_CONFIG", message: "VPN config JSON is empty", details: nil))
                return
            }
            startVpn(configJson: configJson, result: result)

        case "stopVpn":
            stopVpn(result: result)

        case "getStatus":
            let statusStr = mapVpnStatus(tunnelManager?.connection.status ?? .disconnected)
            result([
                "status": statusStr,
                "uplinkSpeed": 0,
                "downlinkSpeed": 0,
                "uplinkTotal": 0,
                "downlinkTotal": 0,
                "error": NSNull()
            ])

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - NETunnelProviderManager Control

    private func loadOrCreateManager(completion: @escaping (NETunnelProviderManager?) -> Void) {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            guard let self = self else { return }
            if let error = error {
                NSLog("Failed to load VPN preferences: \(error.localizedDescription)")
                completion(nil)
                return
            }

            let manager = managers?.first(where: {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.tunnelBundleId
            }) ?? NETunnelProviderManager()

            self.tunnelManager = manager
            self.observeStatusChanges(for: manager)
            completion(manager)
        }
    }

    private func startVpn(configJson: String, result: @escaping FlutterResult) {
        guard let sharedDefaults = UserDefaults(suiteName: appGroupId) else {
            result(FlutterError(code: "APP_GROUP_ERROR", message: "App Group container unavailable", details: nil))
            return
        }
        sharedDefaults.set(configJson, forKey: configDefaultsKey)
        sharedDefaults.synchronize()

        loadOrCreateManager { [weak self] manager in
            guard let self = self, let manager = manager else {
                result(FlutterError(code: "VPN_INIT_ERROR", message: "Unable to initialize NETunnelProviderManager", details: nil))
                return
            }

            let proto = (manager.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = self.tunnelBundleId
            proto.serverAddress = "ShieldBox"
            proto.includeAllNetworks = false

            manager.protocolConfiguration = proto
            manager.localizedDescription = "ShieldBox VPN"
            manager.isEnabled = true

            manager.saveToPreferences { saveError in
                if let saveError = saveError {
                    result(FlutterError(code: "VPN_SAVE_ERROR", message: saveError.localizedDescription, details: nil))
                    return
                }

                manager.loadFromPreferences { reloadError in
                    if let reloadError = reloadError {
                        result(FlutterError(code: "VPN_RELOAD_ERROR", message: reloadError.localizedDescription, details: nil))
                        return
                    }

                    do {
                        try manager.connection.startVPNTunnel()
                        result(true)
                    } catch {
                        result(FlutterError(code: "VPN_START_ERROR", message: error.localizedDescription, details: nil))
                    }
                }
            }
        }
    }

    private func stopVpn(result: @escaping FlutterResult) {
        tunnelManager?.connection.stopVPNTunnel()
        result(true)
    }

    private func observeStatusChanges(for manager: NETunnelProviderManager) {
        if let oldObserver = statusObserver {
            NotificationCenter.default.removeObserver(oldObserver)
        }

        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: manager.connection,
            queue: .main
        ) { [weak self] _ in
            self?.emitCurrentStatus()
        }
    }

    private func emitCurrentStatus() {
        let status = tunnelManager?.connection.status ?? .disconnected
        let statusStr = mapVpnStatus(status)

        if status == .connected {
            startStatsPolling()
        } else {
            statsTimer?.invalidate()
            statsTimer = nil
            eventSink?([
                "status": statusStr,
                "uplinkSpeed": 0,
                "downlinkSpeed": 0,
                "uplinkTotal": 0,
                "downlinkTotal": 0,
                "error": NSNull()
            ])
        }
    }

    private func startStatsPolling() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self,
                  let session = self.tunnelManager?.connection as? NETunnelProviderSession,
                  session.status == .connected else {
                return
            }

            let message = Data("stats".utf8)
            try? session.sendProviderMessage(message) { [weak self] responseData in
                guard let self = self,
                      let data = responseData,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    return
                }
                DispatchQueue.main.async {
                    self.eventSink?([
                        "status": "CONNECTED",
                        "uplinkSpeed": json["uplinkSpeed"] as? Int64 ?? 0,
                        "downlinkSpeed": json["downlinkSpeed"] as? Int64 ?? 0,
                        "uplinkTotal": json["uplinkTotal"] as? Int64 ?? 0,
                        "downlinkTotal": json["downlinkTotal"] as? Int64 ?? 0,
                        "error": NSNull()
                    ])
                }
            }
        }
    }

    private func mapVpnStatus(_ status: NEVPNStatus) -> String {
        switch status {
        case .connected:
            return "CONNECTED"
        case .connecting, .reasserting:
            return "CONNECTING"
        case .disconnecting, .disconnected, .invalid:
            return "DISCONNECTED"
        @unknown default:
            return "DISCONNECTED"
        }
    }
}
