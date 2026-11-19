import 'dart:async';
import 'package:flutter/material.dart';
import '../models/vpn_status.dart';
import '../services/vpn_service.dart';

class CountryNodeOption {
  final String code;
  final String name;
  final String city;
  final String flag;
  final int basePingMs;

  const CountryNodeOption({
    required this.code,
    required this.name,
    required this.city,
    required this.flag,
    required this.basePingMs,
  });
}

class HomeScreen extends StatefulWidget {
  final VpnService vpnService;
  final String userId;

  const HomeScreen({
    super.key,
    required this.vpnService,
    this.userId = 'demo-device-user',
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  static const Color _bgColor = Color(0xFF0A0D12);
  static const Color _surfaceColor = Color(0xFF131822);
  static const Color _borderColor = Color(0xFF202938);
  static const Color _connectedColor = Color(0xFF00E5A3);
  static const Color _connectingColor = Color(0xFFFFB020);
  static const Color _disconnectedColor = Color(0xFF6B778D);

  static const List<CountryNodeOption> _countries = [
    CountryNodeOption(
      code: 'DE',
      name: 'Германия',
      city: 'Франкфурт',
      flag: '🇩🇪',
      basePingMs: 38,
    ),
    CountryNodeOption(
      code: 'NL',
      name: 'Нидерланды',
      city: 'Амстердам',
      flag: '🇳🇱',
      basePingMs: 42,
    ),
    CountryNodeOption(
      code: 'FI',
      name: 'Финляндия',
      city: 'Хельсинки',
      flag: '🇫🇮',
      basePingMs: 29,
    ),
    CountryNodeOption(
      code: 'US',
      name: 'США',
      city: 'Нью-Йорк',
      flag: '🇺🇸',
      basePingMs: 112,
    ),
  ];

  late final AnimationController _pulseController;
  StreamSubscription<VpnStatus>? _statusSub;
  Timer? _pingTimer;

  VpnStatus _status = const VpnStatus(state: VpnConnectionState.disconnected);
  CountryNodeOption _selectedCountry = _countries.first;
  String _activeProtocol = 'Reality';
  int? _livePingMs;
  bool _isBusy = false;
  bool _batteryOptimizationIgnored = true;
  bool _connectOnAppLaunch = false;
  bool _autoConnectAttempted = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: false);

    _activeProtocol = widget.vpnService.activeProtocol;
    _initVpnState();
    _refreshBatteryOptimization();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshBatteryOptimization();
    }
  }

  Future<void> _refreshBatteryOptimization() async {
    final ignored = await widget.vpnService.isBatteryOptimizationIgnored();
    if (!mounted) return;
    setState(() => _batteryOptimizationIgnored = ignored);
  }

  Future<void> _initVpnState() async {
    final initial = await widget.vpnService.getStatus();
    final connectOnLaunch = await widget.vpnService.getConnectOnAppLaunch();
    if (!mounted) return;
    setState(() {
      _status = initial;
      _connectOnAppLaunch = connectOnLaunch;
    });

    if (connectOnLaunch &&
        !_autoConnectAttempted &&
        initial.state == VpnConnectionState.disconnected) {
      _autoConnectAttempted = true;
      unawaited(_startVpn());
    }

    _statusSub = widget.vpnService.statusStream.listen((status) {
      if (!mounted) return;
      setState(() {
        _status = status;
        _activeProtocol = widget.vpnService.activeProtocol;
        if (status.state != VpnConnectionState.connecting) {
          _isBusy = false;
        }
      });

      if (status.state == VpnConnectionState.connected && _pingTimer == null) {
        _startPingLoop();
      } else if (status.state == VpnConnectionState.disconnected) {
        _pingTimer?.cancel();
        _pingTimer = null;
        setState(() => _livePingMs = null);
      }

      if (status.error != null && status.error!.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            backgroundColor: const Color(0xFF2B171B),
            content: Text(
              status.error!,
              style: const TextStyle(color: Color(0xFFFF8A9B)),
            ),
          ),
        );
      }
    });
  }

  void _startPingLoop() {
    _refreshPing();
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 5), (_) => _refreshPing());
  }

  Future<void> _refreshPing() async {
    final ping = await widget.vpnService.measurePingMs();
    if (!mounted) return;
    setState(() {
      _livePingMs = ping ?? _selectedCountry.basePingMs;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pulseController.dispose();
    _statusSub?.cancel();
    _pingTimer?.cancel();
    super.dispose();
  }

  Future<void> _startVpn() async {
    if (_isBusy || _status.state != VpnConnectionState.disconnected) return;
    setState(() {
      _isBusy = true;
      _status = const VpnStatus(state: VpnConnectionState.connecting);
    });

    try {
      await widget.vpnService.connect(
        userId: widget.userId,
        countryCode: _selectedCountry.code,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isBusy = false;
        _status = VpnStatus(
          state: VpnConnectionState.disconnected,
          error: e.toString(),
        );
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF2B171B),
          content: Text(
            e.toString(),
            style: const TextStyle(color: Color(0xFFFF8A9B)),
          ),
        ),
      );
    }
  }

  Future<void> _toggleConnection() async {
    if (_isBusy) return;
    if (_status.state == VpnConnectionState.disconnected) {
      await _startVpn();
      return;
    }

    setState(() => _isBusy = true);
    try {
      await widget.vpnService.stopVpn();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isBusy = false;
        _status = VpnStatus(
          state: VpnConnectionState.disconnected,
          error: e.toString(),
        );
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF2B171B),
          content: Text(
            e.toString(),
            style: const TextStyle(color: Color(0xFFFF8A9B)),
          ),
        ),
      );
    }
  }

  void _openCountryPicker() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: _surfaceColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: _borderColor,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Выбор локации',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 12),
                ..._countries.map((node) {
                  final isSelected = node.code == _selectedCountry.code;
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(16),
                      onTap: () {
                        setState(() => _selectedCountry = node);
                        Navigator.of(ctx).pop();
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 14,
                        ),
                        decoration: BoxDecoration(
                          color: isSelected
                              ? _connectedColor.withOpacity(0.10)
                              : _bgColor,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(
                            color: isSelected
                                ? _connectedColor.withOpacity(0.6)
                                : _borderColor,
                          ),
                        ),
                        child: Row(
                          children: [
                            Text(node.flag, style: const TextStyle(fontSize: 24)),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '${node.name} (${node.code})',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  Text(
                                    node.city,
                                    style: const TextStyle(
                                      color: Color(0xFF8B96AA),
                                      fontSize: 12,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            Text(
                              '${node.basePingMs} ms',
                              style: TextStyle(
                                color: node.basePingMs < 60
                                    ? _connectedColor
                                    : _connectingColor,
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                            const SizedBox(width: 8),
                            if (isSelected)
                              const Icon(
                                Icons.check_circle_rounded,
                                color: _connectedColor,
                                size: 20,
                              ),
                          ],
                        ),
                      ),
                    ),
                  );
                }),
              ],
            ),
          ),
        );
      },
    );
  }

  Color get _stateAccentColor {
    switch (_status.state) {
      case VpnConnectionState.connected:
        return _connectedColor;
      case VpnConnectionState.connecting:
        return _connectingColor;
      case VpnConnectionState.disconnected:
        return _disconnectedColor;
    }
  }

  String get _stateLabel {
    switch (_status.state) {
      case VpnConnectionState.connected:
        return 'ЗАЩИЩЕНО';
      case VpnConnectionState.connecting:
        return 'ПОДКЛЮЧЕНИЕ...';
      case VpnConnectionState.disconnected:
        return 'ОТКЛЮЧЕНО';
    }
  }

  String _formatSpeed(int bytesPerSec) {
    if (bytesPerSec <= 0) return '0.0 KB/s';
    final kb = bytesPerSec / 1024.0;
    if (kb < 1024) return '${kb.toStringAsFixed(1)} KB/s';
    final mb = kb / 1024.0;
    return '${mb.toStringAsFixed(2)} MB/s';
  }

  String _formatTotal(int bytes) {
    if (bytes <= 0) return '0 MB';
    final mb = bytes / (1024.0 * 1024.0);
    if (mb < 1024) return '${mb.toStringAsFixed(1)} MB';
    return '${(mb / 1024.0).toStringAsFixed(2)} GB';
  }

  @override
  Widget build(BuildContext context) {
    final accent = _stateAccentColor;
    final isConnected = _status.state == VpnConnectionState.connected;
    final isConnecting = _status.state == VpnConnectionState.connecting;

    return Scaffold(
      backgroundColor: _bgColor,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: (constraints.maxHeight - 32).clamp(0.0, double.infinity),
                ),
                child: IntrinsicHeight(
                  child: Column(
                    children: [
              // Top bar: Brand + Mock Mode switch
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 10,
                        height: 10,
                        decoration: BoxDecoration(
                          color: accent,
                          shape: BoxShape.circle,
                          boxShadow: [
                            BoxShadow(
                              color: accent.withOpacity(0.6),
                              blurRadius: 8,
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 10),
                      const Text(
                        'SHIELDBOX',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 2.0,
                        ),
                      ),
                    ],
                  ),
                  GestureDetector(
                    onTap: () {
                      setState(() {
                        widget.vpnService.useMockConfig =
                            !widget.vpnService.useMockConfig;
                      });
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: _surfaceColor,
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(
                          color: widget.vpnService.useMockConfig
                              ? _connectingColor.withOpacity(0.5)
                              : _borderColor,
                        ),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            widget.vpnService.useMockConfig
                                ? Icons.science_rounded
                                : Icons.cloud_done_rounded,
                            size: 14,
                            color: widget.vpnService.useMockConfig
                                ? _connectingColor
                                : _connectedColor,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            widget.vpnService.useMockConfig
                                ? 'TUN TEST'
                                : 'LIVE API',
                            style: TextStyle(
                              color: widget.vpnService.useMockConfig
                                  ? _connectingColor
                                  : _connectedColor,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 0.6,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 24),

              // Active Protocol & Ping pill bar
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  color: _surfaceColor,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: _borderColor),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.shield_outlined, size: 16, color: accent),
                        const SizedBox(width: 8),
                        Text(
                          _activeProtocol,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                    Container(width: 1, height: 14, color: _borderColor),
                    Row(
                      children: [
                        Icon(
                          Icons.speed_rounded,
                          size: 16,
                          color: isConnected
                              ? _connectedColor
                              : const Color(0xFF8B96AA),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          isConnected
                              ? '${_livePingMs ?? _selectedCountry.basePingMs} ms'
                              : '-- ms',
                          style: TextStyle(
                            color: isConnected
                                ? Colors.white
                                : const Color(0xFF8B96AA),
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),

              const Spacer(),

              // Big Animated Power Button
              GestureDetector(
                onTap: _toggleConnection,
                child: SizedBox(
                  width: 230,
                  height: 230,
                  child: AnimatedBuilder(
                    animation: _pulseController,
                    builder: (context, child) {
                      final pulse = _pulseController.value;
                      final showPulse = isConnected || isConnecting;
                      return Stack(
                        alignment: Alignment.center,
                        children: [
                          if (showPulse)
                            Container(
                              width: 168 + (pulse * 56),
                              height: 168 + (pulse * 56),
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: accent.withOpacity((1.0 - pulse) * 0.45),
                                  width: 2,
                                ),
                              ),
                            ),
                          AnimatedContainer(
                            duration: const Duration(milliseconds: 350),
                            curve: Curves.easeOutCubic,
                            width: 168,
                            height: 168,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              gradient: RadialGradient(
                                colors: [
                                  accent.withOpacity(isConnected ? 0.22 : 0.10),
                                  _surfaceColor,
                                ],
                              ),
                              border: Border.all(
                                color: accent.withOpacity(isConnected ? 0.9 : 0.45),
                                width: 2.5,
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: accent.withOpacity(isConnected ? 0.28 : 0.08),
                                  blurRadius: 36,
                                  spreadRadius: 4,
                                ),
                              ],
                            ),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                Icon(
                                  isConnecting
                                      ? Icons.sync_rounded
                                      : Icons.power_settings_new_rounded,
                                  size: 52,
                                  color: isConnected ? accent : Colors.white,
                                ),
                                const SizedBox(height: 10),
                                Text(
                                  isConnected
                                      ? 'ОТКЛЮЧИТЬ'
                                      : (isConnecting ? 'ОЖИДАНИЕ' : 'ПОДКЛЮЧИТЬ'),
                                  style: TextStyle(
                                    color: isConnected
                                        ? accent
                                        : const Color(0xFFD0D7E3),
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: 1.4,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ),

              const SizedBox(height: 20),
              Text(
                _stateLabel,
                style: TextStyle(
                  color: accent,
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 2.2,
                ),
              ),

              const Spacer(),

              // Country Selector Card
              InkWell(
                borderRadius: BorderRadius.circular(18),
                onTap: _openCountryPicker,
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
                  decoration: BoxDecoration(
                    color: _surfaceColor,
                    borderRadius: BorderRadius.circular(18),
                    border: Border.all(color: _borderColor),
                  ),
                  child: Row(
                    children: [
                      Text(
                        _selectedCountry.flag,
                        style: const TextStyle(fontSize: 26),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${_selectedCountry.name} · ${_selectedCountry.code}',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 15,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '${_selectedCountry.city} • Auto-Fallback (Reality / Hy2 / CDN)',
                              style: const TextStyle(
                                color: Color(0xFF8B96AA),
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Icon(
                        Icons.keyboard_arrow_down_rounded,
                        color: Color(0xFF8B96AA),
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(height: 14),

              // Battery Optimization Check Card
              InkWell(
                borderRadius: BorderRadius.circular(16),
                onTap: () => widget.vpnService.openBatteryOptimizationSettings(),
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(
                    color: _surfaceColor,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: _batteryOptimizationIgnored
                          ? _borderColor
                          : _connectingColor.withOpacity(0.55),
                    ),
                  ),
                  child: Row(
                    children: [
                      Icon(
                        _batteryOptimizationIgnored
                            ? Icons.battery_charging_full_rounded
                            : Icons.battery_alert_rounded,
                        size: 20,
                        color: _batteryOptimizationIgnored
                            ? _connectedColor
                            : _connectingColor,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              'Оптимизация батареи',
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              _batteryOptimizationIgnored
                                  ? 'Без ограничений — фон защищён'
                                  : 'Нажмите, чтобы отключить ограничения в фоне',
                              style: TextStyle(
                                color: _batteryOptimizationIgnored
                                    ? const Color(0xFF8B96AA)
                                    : _connectingColor,
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Icon(
                        Icons.chevron_right_rounded,
                        color: _batteryOptimizationIgnored
                            ? const Color(0xFF6B778D)
                            : _connectingColor,
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(height: 12),

              // Connect on App Launch Preference Card
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: _surfaceColor,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: _borderColor),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.flash_on_rounded,
                      size: 20,
                      color: _connectOnAppLaunch
                          ? _connectedColor
                          : const Color(0xFF8B96AA),
                    ),
                    const SizedBox(width: 12),
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Подключаться при запуске',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          Text(
                            'Автозапуск VPN-туннеля при открытии',
                            style: TextStyle(
                              color: Color(0xFF8B96AA),
                              fontSize: 11,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: _connectOnAppLaunch,
                      activeColor: _bgColor,
                      activeTrackColor: _connectedColor,
                      onChanged: (value) {
                        setState(() => _connectOnAppLaunch = value);
                        unawaited(widget.vpnService.setConnectOnAppLaunch(value));
                      },
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 14),

              // Real-time Download / Upload Speed Cards
              Row(
                children: [
                  Expanded(
                    child: _SpeedMetricCard(
                      label: 'DOWNLOAD',
                      speedText: _formatSpeed(_status.downlinkSpeedBytesPerSec),
                      totalText: _formatTotal(_status.downlinkTotalBytes),
                      icon: Icons.south_rounded,
                      accentColor: _connectedColor,
                      surfaceColor: _surfaceColor,
                      borderColor: _borderColor,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _SpeedMetricCard(
                      label: 'UPLOAD',
                      speedText: _formatSpeed(_status.uplinkSpeedBytesPerSec),
                      totalText: _formatTotal(_status.uplinkTotalBytes),
                      icon: Icons.north_rounded,
                      accentColor: const Color(0xFF56A8FF),
                      surfaceColor: _surfaceColor,
                      borderColor: _borderColor,
                    ),
                  ),
                ],
              ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _SpeedMetricCard extends StatelessWidget {
  final String label;
  final String speedText;
  final String totalText;
  final IconData icon;
  final Color accentColor;
  final Color surfaceColor;
  final Color borderColor;

  const _SpeedMetricCard({
    required this.label,
    required this.speedText,
    required this.totalText,
    required this.icon,
    required this.accentColor,
    required this.surfaceColor,
    required this.borderColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: borderColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 15, color: accentColor),
              const SizedBox(width: 6),
              Text(
                label,
                style: const TextStyle(
                  color: Color(0xFF8B96AA),
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 1.0,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            speedText,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 17,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            'Всего: $totalText',
            style: const TextStyle(
              color: Color(0xFF6B778D),
              fontSize: 11,
            ),
          ),
        ],
      ),
    );
  }
}
