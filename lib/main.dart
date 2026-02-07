import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';
import 'dart:io';

import 'screens/admin_screen.dart';
import 'screens/client_screen.dart';
import 'screens/teacher_screen.dart';
import 'screens/mobile_admin_screen.dart';
import 'screens/license_screen.dart';
import 'services/license_service.dart';
import 'services/password_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Windows icin pencere ayarlari
  if (Platform.isWindows) {
    await _initWindowManager();
  }

  runApp(const OkulMesajlasmaApp());
}

/// Windows pencere yoneticisini baslat
Future<void> _initWindowManager() async {
  try {
    await windowManager.ensureInitialized();

    const windowOptions = WindowOptions(
      size: Size(1200, 800),
      minimumSize: Size(800, 600),
      center: true,
      backgroundColor: Colors.transparent,
      skipTaskbar: false,
      titleBarStyle: TitleBarStyle.normal,
      title: 'Karabag H.O.Akarsel Ortaokulu - Mesajlasma',
    );

    windowManager.waitUntilReadyToShow(windowOptions, () async {
      await windowManager.show();
      await windowManager.focus();
      await windowManager.setPreventClose(true);
    });
  } catch (e) {
    print('Window manager baslatma hatasi: $e');
  }
}

class OkulMesajlasmaApp extends StatelessWidget {
  const OkulMesajlasmaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: _isMobile ? 'Okul Muduru' : 'Karabag H.O.Akarsel Ortaokulu - Mesajlasma',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.indigo,
          brightness: Brightness.light,
        ),
        useMaterial3: true,
        fontFamily: Platform.isWindows ? 'Segoe UI' : null,
      ),
      home: const LicenseCheckWrapper(),
    );
  }

  static bool get _isMobile => Platform.isAndroid || Platform.isIOS;
}

/// Lisans kontrolu wrapper
class LicenseCheckWrapper extends StatefulWidget {
  const LicenseCheckWrapper({super.key});

  @override
  State<LicenseCheckWrapper> createState() => _LicenseCheckWrapperState();
}

class _LicenseCheckWrapperState extends State<LicenseCheckWrapper> with WindowListener {
  LicenseInfo? _licenseInfo;
  bool _isChecking = true;

  bool get _isMobile => Platform.isAndroid || Platform.isIOS;

  @override
  void initState() {
    super.initState();
    if (Platform.isWindows) {
      windowManager.addListener(this);
    }
    _checkLicense();
  }

  @override
  void dispose() {
    if (Platform.isWindows) {
      windowManager.removeListener(this);
    }
    super.dispose();
  }

  // X butonuna basinca minimize et (sadece Windows)
  @override
  void onWindowClose() async {
    if (Platform.isWindows) {
      await windowManager.minimize();
    }
  }

  Future<void> _checkLicense() async {
    final info = await LicenseService.checkLicense();
    setState(() {
      _licenseInfo = info;
      _isChecking = false;
    });
  }

  void _onLicenseValid() {
    _checkLicense();
  }

  void _onNewLicense() async {
    await LicenseService.clearLicense();
    setState(() {
      _licenseInfo = LicenseInfo(status: LicenseStatus.notFound);
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_isChecking) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Lisans kontrol ediliyor...'),
            ],
          ),
        ),
      );
    }

    final info = _licenseInfo!;

    switch (info.status) {
      case LicenseStatus.notFound:
      case LicenseStatus.invalid:
        return LicenseEntryScreen(onLicenseValid: _onLicenseValid);

      case LicenseStatus.expired:
        return LicenseExpiredScreen(
          licenseInfo: info,
          onNewLicense: _onNewLicense,
        );

      case LicenseStatus.expiringSoon:
        return _buildWithWarningBanner(info);

      case LicenseStatus.valid:
        // Mobil ise direkt admin sifre ekranini goster
        if (_isMobile) {
          return const MobileLoginScreen();
        }
        return const ModeSelectionScreen();
    }
  }

  Widget _buildWithWarningBanner(LicenseInfo info) {
    return Scaffold(
      body: Column(
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [Colors.orange[600]!, Colors.orange[800]!],
              ),
            ),
            child: Row(
              children: [
                const Icon(Icons.warning_amber, color: Colors.white),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Lisans ${info.daysRemaining} gun icinde dolacak!',
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                TextButton(
                  onPressed: () {
                    Navigator.of(context).pushReplacement(
                      MaterialPageRoute(
                        builder: (_) => _isMobile
                            ? const MobileLoginScreen()
                            : const ModeSelectionScreen(),
                      ),
                    );
                  },
                  child: const Text('Tamam', style: TextStyle(color: Colors.white)),
                ),
              ],
            ),
          ),
          Expanded(
            child: _isMobile
                ? const MobileLoginScreen()
                : const ModeSelectionScreen(),
          ),
        ],
      ),
    );
  }
}

// ==================== MOBiL GiRiS EKRANI ====================
class MobileLoginScreen extends StatefulWidget {
  const MobileLoginScreen({super.key});

  @override
  State<MobileLoginScreen> createState() => _MobileLoginScreenState();
}

class _MobileLoginScreenState extends State<MobileLoginScreen> {
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  String? _error;

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }

  void _login() async {
    if (_passwordController.text.isEmpty) {
      setState(() => _error = 'Lutfen sifre girin');
      return;
    }

    setState(() {
      _isLoading = true;
      _error = null;
    });

    final adminPw = await PasswordService.getAdminPassword();

    if (_passwordController.text == adminPw) {
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const MobileAdminScreen()),
      );
    } else {
      setState(() {
        _isLoading = false;
        _error = 'Hatali sifre!';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Colors.indigo[600]!, Colors.indigo[900]!],
          ),
        ),
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(32),
              child: Card(
                elevation: 8,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                child: Padding(
                  padding: const EdgeInsets.all(32),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.indigo[50],
                          shape: BoxShape.circle,
                        ),
                        child: Icon(Icons.admin_panel_settings, size: 48, color: Colors.indigo[700]),
                      ),
                      const SizedBox(height: 20),
                      const Text(
                        'Okul Muduru',
                        style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Karabag H.O. Akarsel Ortaokulu',
                        style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 32),
                      TextField(
                        controller: _passwordController,
                        obscureText: true,
                        autofocus: true,
                        decoration: InputDecoration(
                          labelText: 'Yonetim Sifresi',
                          border: const OutlineInputBorder(),
                          prefixIcon: const Icon(Icons.lock),
                          errorText: _error,
                        ),
                        style: const TextStyle(fontSize: 18),
                        onSubmitted: (_) => _login(),
                      ),
                      const SizedBox(height: 24),
                      SizedBox(
                        width: double.infinity,
                        height: 52,
                        child: ElevatedButton.icon(
                          onPressed: _isLoading ? null : _login,
                          icon: _isLoading
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                )
                              : const Icon(Icons.login),
                          label: Text(
                            _isLoading ? 'Giris yapiliyor...' : 'Giris Yap',
                            style: const TextStyle(fontSize: 18),
                          ),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.indigo,
                            foregroundColor: Colors.white,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ==================== MASAUSTU MOD SECIM EKRANI ====================
class ModeSelectionScreen extends StatefulWidget {
  const ModeSelectionScreen({super.key});

  @override
  State<ModeSelectionScreen> createState() => _ModeSelectionScreenState();
}

class _ModeSelectionScreenState extends State<ModeSelectionScreen> {
  final _passwordController = TextEditingController();

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }

  void _showPasswordDialog({required bool isTeacher}) {
    _passwordController.clear();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(
              isTeacher ? Icons.school : Icons.lock,
              color: isTeacher ? Colors.deepPurple[700] : Colors.indigo[700],
            ),
            const SizedBox(width: 12),
            Text(isTeacher ? 'Ogretmen Girisi' : 'Yonetim Girisi'),
          ],
        ),
        content: TextField(
          controller: _passwordController,
          obscureText: true,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Sifre',
            border: OutlineInputBorder(),
            prefixIcon: Icon(Icons.password),
          ),
          onSubmitted: (_) => _checkPassword(isTeacher: isTeacher),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Iptal'),
          ),
          ElevatedButton(
            onPressed: () => _checkPassword(isTeacher: isTeacher),
            style: ElevatedButton.styleFrom(
              backgroundColor: isTeacher ? Colors.deepPurple : Colors.indigo,
              foregroundColor: Colors.white,
            ),
            child: const Text('Giris'),
          ),
        ],
      ),
    );
  }

  void _checkPassword({required bool isTeacher}) async {
    final enteredPassword = _passwordController.text;
    final correctPassword = isTeacher
        ? await PasswordService.getTeacherPassword()
        : await PasswordService.getAdminPassword();

    if (enteredPassword == correctPassword) {
      if (!mounted) return;
      Navigator.of(context).pop();
      if (isTeacher) {
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const TeacherScreen()),
        );
      } else {
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const AdminScreen()),
        );
      }
    } else {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Hatali sifre!'), backgroundColor: Colors.red),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Colors.indigo[400]!, Colors.teal[400]!],
          ),
        ),
        child: Center(
          child: SingleChildScrollView(
            child: Card(
              elevation: 8,
              margin: const EdgeInsets.all(16),
              child: Container(
                constraints: const BoxConstraints(maxWidth: 700),
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.indigo[50],
                        shape: BoxShape.circle,
                      ),
                      child: Icon(Icons.school, size: 40, color: Colors.indigo[700]),
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      'Karabag Hatipoglu Omer Akarsel Ortaokulu',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 2),
                    const Text(
                      'Okul Mesajlasma Sistemi',
                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: Colors.indigo),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'WiFi tabanli okul ici iletisim platformu',
                      style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 20),

                    // 3 mod secim butonu
                    Row(
                      children: [
                        Expanded(
                          child: _ModeCard(
                            icon: Icons.admin_panel_settings,
                            title: 'Yonetim Paneli',
                            subtitle: 'Mesaj gonderin ve yonetin',
                            color: Colors.indigo,
                            onTap: () => _showPasswordDialog(isTeacher: false),
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: _ModeCard(
                            icon: Icons.school,
                            title: 'Ogretmen Girisi',
                            subtitle: 'Ogretmenler odasi',
                            color: Colors.deepPurple,
                            onTap: () => _showPasswordDialog(isTeacher: true),
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: _ModeCard(
                            icon: Icons.tv,
                            title: 'Sinif Ekrani',
                            subtitle: 'Mesajlari goruntuleyin',
                            color: Colors.teal,
                            onTap: () {
                              Navigator.of(context).push(
                                MaterialPageRoute(builder: (_) => const ClientScreen()),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 32),

                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.blue[50],
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.info_outline, color: Colors.blue[700]),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              'Yonetim Paneli: Mudur odasinda\n'
                              'Ogretmen Girisi: Ogretmenler odasinda\n'
                              'Sinif Ekrani: Akilli tahtalarda calistirin',
                              style: TextStyle(fontSize: 13, color: Colors.blue[900]),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ModeCard extends StatefulWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  final VoidCallback onTap;

  const _ModeCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.color,
    required this.onTap,
  });

  @override
  State<_ModeCard> createState() => _ModeCardState();
}

class _ModeCardState extends State<_ModeCard> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        transform: Matrix4.identity()..scale(_isHovered ? 1.02 : 1.0),
        child: InkWell(
          onTap: widget.onTap,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: _isHovered ? widget.color.withOpacity(0.1) : Colors.grey[50],
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: _isHovered ? widget.color : Colors.grey[300]!,
                width: 2,
              ),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: widget.color.withOpacity(0.1),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(widget.icon, size: 32, color: widget.color),
                ),
                const SizedBox(height: 8),
                Text(
                  widget.title,
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: widget.color),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 2),
                Text(
                  widget.subtitle,
                  style: TextStyle(fontSize: 11, color: Colors.grey[600]),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
