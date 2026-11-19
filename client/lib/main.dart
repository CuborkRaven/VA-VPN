import 'package:flutter/material.dart';
import 'screens/home_screen.dart';
import 'services/vpn_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ShieldBoxApp());
}

class ShieldBoxApp extends StatefulWidget {
  const ShieldBoxApp({super.key});

  @override
  State<ShieldBoxApp> createState() => _ShieldBoxAppState();
}

class _ShieldBoxAppState extends State<ShieldBoxApp> {
  final VpnService _vpnService = VpnService(useMockConfig: true);

  @override
  void dispose() {
    _vpnService.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ShieldBox',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF0A0D12),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00E5A3),
          surface: Color(0xFF131822),
        ),
      ),
      home: HomeScreen(vpnService: _vpnService),
    );
  }
}
