import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../models/vpn_status.dart';

class VpnConfigFetchException implements Exception {
  final String message;
  VpnConfigFetchException(this.message);

  @override
  String toString() => 'VpnConfigFetchException: $message';
}

class VpnService {
  static const MethodChannel _methodChannel =
      MethodChannel('com.shieldbox.vpn/control');
  static const EventChannel _eventChannel =
      EventChannel('com.shieldbox.vpn/events');

  static const String _cachedConfigKey = 'shieldbox_last_valid_config';

  // Данные вашего VPS со Stage 1 — впишите свои значения для теста на смартфоне:
  static const String serverIp = 'SERVER_IP';
  static const String clientUuid = 'CLIENT_UUID';
  static const String realityPublicKey = 'REALITY_PUBLIC_KEY';
  static const String shortId = 'SHORT_ID';
  static const String hy2Password = 'HY2_PASSWORD';
  static const String hy2ObfsPassword = 'HY2_OBFS_PASSWORD';

  final http.Client _httpClient;
  final List<String> primaryApiBases;
  final List<String> mirrorBaseUrls;
  final String dohDiscoveryDomain;
  bool useMockConfig;

  Stream<VpnStatus>? _statusStream;
  String _activeProtocol = 'Auto (Reality / Hy2)';

  VpnService({
    http.Client? httpClient,
    this.primaryApiBases = const [
      'https://api.shieldbox.example',
    ],
    this.mirrorBaseUrls = const [
      'https://raw.githubusercontent.com/shieldbox-org/mirrors/main/configs',
      'https://d123456abcdef.cloudfront.net/configs',
    ],
    this.dohDiscoveryDomain = '_cfg.shieldbox.example',
    this.useMockConfig = true,
  }) : _httpClient = httpClient ?? http.Client();

  String get activeProtocol => _activeProtocol;

  /// Real-time stream of VPN state (DISCONNECTED / CONNECTING / CONNECTED) and speeds.
  Stream<VpnStatus> get statusStream {
    _statusStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((event) => VpnStatus.fromMap(event as Map<dynamic, dynamic>));
    return _statusStream!;
  }

  Future<VpnStatus> getStatus() async {
    final result =
        await _methodChannel.invokeMapMethod<dynamic, dynamic>('getStatus');
    return VpnStatus.fromMap(result ?? const {});
  }

  Future<bool> isBatteryOptimizationIgnored() async {
    try {
      final result = await _methodChannel
          .invokeMethod<bool>('isBatteryOptimizationIgnored');
      return result ?? true;
    } on MissingPluginException {
      return true;
    }
  }

  Future<void> openBatteryOptimizationSettings() async {
    try {
      await _methodChannel.invokeMethod<bool>('openBatteryOptimizationSettings');
    } on MissingPluginException {
      // iOS does not use Android battery optimization settings
    }
  }

  static const String _connectOnLaunchKey = 'connect_on_app_launch';

  Future<bool> getConnectOnAppLaunch() async {
    try {
      final result =
          await _methodChannel.invokeMethod<bool>('getConnectOnAppLaunch');
      return result ?? false;
    } on MissingPluginException {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getBool(_connectOnLaunchKey) ?? false;
    }
  }

  Future<void> setConnectOnAppLaunch(bool enabled) async {
    try {
      await _methodChannel
          .invokeMethod<bool>('setConnectOnAppLaunch', {'enabled': enabled});
    } on MissingPluginException {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_connectOnLaunchKey, enabled);
    }
  }

  /// Resilient config retrieval + VPN startup.
  Future<void> connect({
    required String userId,
    String? countryCode,
    String splitCountry = 'ru',
  }) async {
    final configJson = useMockConfig
        ? buildDefaultClientTestConfig()
        : await fetchResilientConfig(
            userId: userId,
            countryCode: countryCode,
            splitCountry: splitCountry,
          );

    _activeProtocol = _detectPrimaryProtocol(configJson);
    await startVpnWithConfig(configJson);
  }

  /// Клиентский JSON-конфиг sing-box (v1.12/v1.13+) для мобильного libbox:
  /// - Входящий TUN-интерфейс (gVisor stack)
  /// - Исходящая группа urltest ("auto-proxy"): VLESS+XTLS-Reality + Hysteria 2
  /// - Маршрутизация "final": "auto-proxy"
  String buildDefaultClientTestConfig() {
    if (serverIp == 'SERVER_IP' ||
        clientUuid == 'CLIENT_UUID' ||
        realityPublicKey == 'REALITY_PUBLIC_KEY') {
      throw VpnConfigFetchException(
        'Замените SERVER_IP, CLIENT_UUID, REALITY_PUBLIC_KEY, SHORT_ID и HY2_PASSWORD в VpnService перед запуском.',
      );
    }

    final configMap = <String, dynamic>{
      'log': {
        'level': 'info',
        'timestamp': true,
      },
      'dns': {
        'servers': [
          {
            'tag': 'dns-remote',
            'type': 'https',
            'server': '1.1.1.1',
            'detour': 'auto-proxy',
          },
          {
            'tag': 'dns-direct',
            'type': 'udp',
            'server': '8.8.8.8',
            'server_port': 53,
            'detour': 'direct',
          },
        ],
        'final': 'dns-remote',
        'strategy': 'prefer_ipv4',
        'independent_cache': true,
      },
      'inbounds': [
        {
          'type': 'tun',
          'tag': 'tun-in',
          'address': [
            '172.19.0.1/30',
            'fdfe:dcba:9876::1/126',
          ],
          'mtu': 1400,
          'auto_route': true,
          'strict_route': true,
          'stack': 'gvisor',
        },
      ],
      'outbounds': [
        {
          'type': 'urltest',
          'tag': 'auto-proxy',
          'outbounds': [
            'vless-reality',
            'hysteria2',
          ],
          'url': 'https://www.gstatic.com/generate_204',
          'interval': '3m',
          'tolerance': 80,
          'interrupt_exist_connections': false,
        },
        {
          'type': 'vless',
          'tag': 'vless-reality',
          'server': serverIp,
          'server_port': 443,
          'uuid': clientUuid,
          'flow': 'xtls-rprx-vision',
          'tls': {
            'enabled': true,
            'server_name': 'www.microsoft.com',
            'utls': {
              'enabled': true,
              'fingerprint': 'chrome',
            },
            'reality': {
              'enabled': true,
              'public_key': realityPublicKey,
              'short_id': shortId,
            },
          },
        },
        {
          'type': 'hysteria2',
          'tag': 'hysteria2',
          'server': serverIp,
          'server_port': 443,
          'password': hy2Password,
          'up_mbps': 100,
          'down_mbps': 200,
          'obfs': {
            'type': 'salamander',
            'password': hy2ObfsPassword,
          },
          'tls': {
            'enabled': true,
            'server_name': 'bing.com',
            'alpn': ['h3'],
            'insecure': true,
          },
        },
        {
          'type': 'direct',
          'tag': 'direct',
        },
      ],
      'route': {
        'default_domain_resolver': 'dns-direct',
        'rules': [
          {'action': 'sniff'},
          {'protocol': 'dns', 'action': 'hijack-dns'},
          {'ip_is_private': true, 'action': 'route', 'outbound': 'direct'},
        ],
        'final': 'auto-proxy',
        'auto_detect_interface': true,
      },
    };
    return jsonEncode(configMap);
  }

  /// Measures live HTTP/TLS handshake latency for ping badge.
  Future<int?> measurePingMs({String url = 'https://www.gstatic.com/generate_204'}) async {
    final sw = Stopwatch()..start();
    try {
      final resp = await _httpClient
          .head(Uri.parse(url))
          .timeout(const Duration(seconds: 3));
      sw.stop();
      if (resp.statusCode >= 200 && resp.statusCode < 500) {
        return sw.elapsedMilliseconds;
      }
    } catch (_) {}
    return null;
  }

  String _detectPrimaryProtocol(String configJson) {
    try {
      final decoded = jsonDecode(configJson) as Map<String, dynamic>;
      final outbounds = decoded['outbounds'] as List<dynamic>? ?? const [];
      final hasUrltest = outbounds.any(
        (e) => e is Map<String, dynamic> && e['type'] == 'urltest',
      );
      if (hasUrltest) {
        return 'Auto (Reality / Hy2)';
      }
      for (final item in outbounds) {
        if (item is! Map<String, dynamic>) continue;
        final type = item['type'] as String?;
        if (type == 'hysteria2') return 'Hysteria 2';
        if (type == 'vless') {
          final tls = item['tls'] as Map<String, dynamic>? ?? const {};
          final transport = item['transport'] as Map<String, dynamic>? ?? const {};
          if (tls['reality'] != null) return 'Reality';
          if (transport['type'] == 'ws') return 'CDN';
          return 'VLESS';
        }
      }
    } catch (_) {}
    return 'Reality';
  }

  Future<void> startVpnWithConfig(String configJson) async {
    final decoded = jsonDecode(configJson);
    if (decoded is! Map<String, dynamic> || !decoded.containsKey('outbounds')) {
      throw VpnConfigFetchException('Invalid sing-box config structure');
    }
    await _methodChannel.invokeMethod<bool>('startVpn', {'config': configJson});
  }

  Future<void> stopVpn() async {
    await _methodChannel.invokeMethod<bool>('stopVpn');
  }

  Future<String> fetchResilientConfig({
    required String userId,
    String? countryCode,
    String splitCountry = 'ru',
  }) async {
    final queryParams = <String, String>{
      'split_country': splitCountry,
      if (countryCode != null && countryCode.isNotEmpty) 'country': countryCode,
    };

    // Tier 1: Primary Control Plane API
    for (final base in primaryApiBases) {
      final uri = Uri.parse('$base/api/v1/config/$userId')
          .replace(queryParameters: queryParams);
      final config = await _tryFetchJson(uri);
      if (config != null) {
        await _cacheConfig(config);
        return config;
      }
    }

    // Tier 2: Static Mirrors (GitHub Raw / S3 / CDN)
    for (final mirror in mirrorBaseUrls) {
      final uri = Uri.parse('$mirror/$userId.json');
      final config = await _tryFetchJson(uri);
      if (config != null) {
        await _cacheConfig(config);
        return config;
      }
    }

    // Tier 3: DNS-over-HTTPS (DoH) TXT Discovery -> Dynamic Mirror
    final discoveredEndpoints = await _discoverEndpointsViaDoH(dohDiscoveryDomain);
    for (final endpoint in discoveredEndpoints) {
      final uri = Uri.parse('$endpoint/api/v1/config/$userId')
          .replace(queryParameters: queryParams);
      final config = await _tryFetchJson(uri);
      if (config != null) {
        await _cacheConfig(config);
        return config;
      }
    }

    // Tier 4: Offline / Censorship emergency fallback from local cache
    final cached = await _loadCachedConfig();
    if (cached != null) {
      return cached;
    }

    throw VpnConfigFetchException(
      'All API endpoints, mirrors, DoH discovery, and local cache failed',
    );
  }

  Future<String?> _tryFetchJson(Uri uri) async {
    try {
      final response = await _httpClient.get(
        uri,
        headers: const {'Accept': 'application/json'},
      ).timeout(const Duration(seconds: 6));

      if (response.statusCode == 200) {
        final decoded = jsonDecode(response.body);
        if (decoded is Map<String, dynamic> && decoded.containsKey('outbounds')) {
          return response.body;
        }
      }
    } catch (_) {}
    return null;
  }

  Future<List<String>> _discoverEndpointsViaDoH(String domain) async {
    final dohResolvers = [
      Uri.parse('https://1.1.1.1/dns-query?name=$domain&type=TXT'),
      Uri.parse('https://dns.google/resolve?name=$domain&type=TXT'),
    ];

    final discovered = <String>[];
    for (final resolverUri in dohResolvers) {
      try {
        final response = await _httpClient.get(
          resolverUri,
          headers: const {'Accept': 'application/dns-json'},
        ).timeout(const Duration(seconds: 5));

        if (response.statusCode != 200) continue;
        final body = jsonDecode(response.body) as Map<String, dynamic>;
        final answers = body['Answer'] as List<dynamic>? ?? const [];

        for (final answer in answers) {
          if (answer is! Map<String, dynamic>) continue;
          final rawData = (answer['data'] as String? ?? '')
              .replaceAll('"', '')
              .trim();
          if (rawData.isEmpty) continue;

          if (rawData.startsWith('https://')) {
            discovered.add(rawData);
          } else {
            try {
              final decodedStr = utf8.decode(base64Decode(rawData)).trim();
              if (decodedStr.startsWith('https://')) {
                discovered.add(decodedStr);
              } else if (decodedStr.startsWith('[')) {
                final urls = (jsonDecode(decodedStr) as List<dynamic>)
                    .whereType<String>()
                    .where((u) => u.startsWith('https://'));
                discovered.addAll(urls);
              }
            } catch (_) {}
          }
        }
        if (discovered.isNotEmpty) break;
      } catch (_) {}
    }
    return discovered;
  }

  Future<void> _cacheConfig(String configJson) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_cachedConfigKey, configJson);
  }

  Future<String?> _loadCachedConfig() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_cachedConfigKey);
  }

  void dispose() {
    _httpClient.close();
  }
}
