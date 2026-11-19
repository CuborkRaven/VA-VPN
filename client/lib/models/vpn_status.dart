enum VpnConnectionState {
  case disconnected
  case connecting
  case connected;

  static VpnConnectionState fromString(String? value) {
    switch (value?.toUpperCase()) {
      case 'CONNECTED':
        return VpnConnectionState.connected;
      case 'CONNECTING':
        return VpnConnectionState.connecting;
      default:
        return VpnConnectionState.disconnected;
    }
  }
}

class VpnStatus {
  final VpnConnectionState state;
  final int uplinkSpeedBytesPerSec;
  final int downlinkSpeedBytesPerSec;
  final int uplinkTotalBytes;
  final int downlinkTotalBytes;
  final String? error;

  const VpnStatus({
    required this.state,
    this.uplinkSpeedBytesPerSec = 0,
    this.downlinkSpeedBytesPerSec = 0,
    this.uplinkTotalBytes = 0,
    this.downlinkTotalBytes = 0,
    this.error,
  });

  factory VpnStatus.fromMap(Map<dynamic, dynamic> map) {
    return VpnStatus(
      state: VpnConnectionState.fromString(map['status'] as String?),
      uplinkSpeedBytesPerSec: (map['uplinkSpeed'] as num?)?.toInt() ?? 0,
      downlinkSpeedBytesPerSec: (map['downlinkSpeed'] as num?)?.toInt() ?? 0,
      uplinkTotalBytes: (map['uplinkTotal'] as num?)?.toInt() ?? 0,
      downlinkTotalBytes: (map['downlinkTotal'] as num?)?.toInt() ?? 0,
      error: map['error'] as String?,
    );
  }
}
