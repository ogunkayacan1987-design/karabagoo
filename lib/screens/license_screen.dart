import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/license_service.dart';

/// Lisans giriş ekranı
class LicenseEntryScreen extends StatefulWidget {
  final VoidCallback onLicenseValid;

  const LicenseEntryScreen({super.key, required this.onLicenseValid});

  @override
  State<LicenseEntryScreen> createState() => _LicenseEntryScreenState();
}

class _LicenseEntryScreenState extends State<LicenseEntryScreen> {
  final _keyController = TextEditingController();
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void dispose() {
    _keyController.dispose();
    super.dispose();
  }

  Future<void> _activateLicense() async {
    final key = _keyController.text.trim().toUpperCase();

    if (key.isEmpty) {
      setState(() => _errorMessage = 'Lütfen lisans anahtarını girin');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final success = await LicenseService.saveLicense(key);

    if (success) {
      final info = await LicenseService.checkLicense();
      if (info.status == LicenseStatus.valid ||
          info.status == LicenseStatus.expiringSoon) {
        widget.onLicenseValid();
      } else {
        setState(() {
          _isLoading = false;
          _errorMessage = info.message;
        });
      }
    } else {
      setState(() {
        _isLoading = false;
        _errorMessage = 'Geçersiz lisans anahtarı.\nLütfen doğru anahtarı girdiğinizden emin olun.';
      });
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
            colors: [
              Colors.indigo[600]!,
              Colors.indigo[900]!,
            ],
          ),
        ),
        child: Center(
          child: SingleChildScrollView(
            child: Card(
              margin: const EdgeInsets.all(24),
              child: Container(
                constraints: const BoxConstraints(maxWidth: 450),
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // Logo
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.indigo[50],
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        Icons.key,
                        size: 48,
                        color: Colors.indigo[700],
                      ),
                    ),
                    const SizedBox(height: 24),

                    // Başlık
                    const Text(
                      'Lisans Aktivasyonu',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Programı kullanmak için lisans anahtarınızı girin',
                      style: TextStyle(
                        fontSize: 14,
                        color: Colors.grey[600],
                      ),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 32),

                    // Anahtar girişi
                    TextField(
                      controller: _keyController,
                      textCapitalization: TextCapitalization.characters,
                      inputFormatters: [
                        UpperCaseTextFormatter(),
                        LicenseKeyFormatter(),
                      ],
                      decoration: InputDecoration(
                        labelText: 'Lisans Anahtarı',
                        hintText: 'KBOA-2027-0201-XXXX',
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.vpn_key),
                        errorText: _errorMessage,
                        errorMaxLines: 3,
                      ),
                      style: const TextStyle(
                        fontSize: 18,
                        fontFamily: 'monospace',
                        letterSpacing: 2,
                      ),
                      onSubmitted: (_) => _activateLicense(),
                    ),
                    const SizedBox(height: 24),

                    // Aktivasyon butonu
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        onPressed: _isLoading ? null : _activateLicense,
                        icon: _isLoading
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : const Icon(Icons.check_circle),
                        label: Text(
                          _isLoading ? 'Kontrol ediliyor...' : 'Lisansı Etkinleştir',
                          style: const TextStyle(fontSize: 16),
                        ),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.indigo,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),

                    // Bilgi
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.grey[100],
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.info_outline, color: Colors.grey[600], size: 20),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'Lisans anahtarı için program geliştiricisine başvurun',
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.grey[700],
                              ),
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

/// Lisans süresi doldu ekranı
class LicenseExpiredScreen extends StatelessWidget {
  final LicenseInfo licenseInfo;
  final VoidCallback onNewLicense;

  const LicenseExpiredScreen({
    super.key,
    required this.licenseInfo,
    required this.onNewLicense,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Colors.red[600]!,
              Colors.red[900]!,
            ],
          ),
        ),
        child: Center(
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Container(
              constraints: const BoxConstraints(maxWidth: 450),
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // İkon
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Colors.red[50],
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      Icons.warning_amber,
                      size: 48,
                      color: Colors.red[700],
                    ),
                  ),
                  const SizedBox(height: 24),

                  // Başlık
                  const Text(
                    'Lisans Süresi Doldu',
                    style: TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.bold,
                      color: Colors.red,
                    ),
                  ),
                  const SizedBox(height: 16),

                  // Mesaj
                  Text(
                    licenseInfo.message ?? 'Lisans süresi doldu',
                    style: TextStyle(
                      fontSize: 16,
                      color: Colors.grey[700],
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),

                  // Yeni lisans butonu
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: onNewLicense,
                      icon: const Icon(Icons.refresh),
                      label: const Text(
                        'Yeni Lisans Anahtarı Gir',
                        style: TextStyle(fontSize: 16),
                      ),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.indigo,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),

                  // Bilgi
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.orange[50],
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.phone, color: Colors.orange[700], size: 20),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Yeni lisans için program geliştiricisine başvurun',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.orange[900],
                            ),
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
    );
  }
}

/// Büyük harf formatter
class UpperCaseTextFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    return newValue.copyWith(
      text: newValue.text.toUpperCase(),
    );
  }
}

/// Lisans anahtarı formatter (tire ekler)
class LicenseKeyFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    final text = newValue.text.replaceAll('-', '');
    if (text.length > 16) {
      return oldValue;
    }

    final buffer = StringBuffer();
    for (int i = 0; i < text.length; i++) {
      if (i > 0 && i % 4 == 0) {
        buffer.write('-');
      }
      buffer.write(text[i]);
    }

    return TextEditingValue(
      text: buffer.toString(),
      selection: TextSelection.collapsed(offset: buffer.length),
    );
  }
}
