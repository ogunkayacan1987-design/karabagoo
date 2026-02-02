import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

/// Lisans durumu
enum LicenseStatus {
  valid,         // Geçerli
  expiringSoon,  // 10 gün içinde dolacak
  expired,       // Süresi dolmuş
  invalid,       // Geçersiz anahtar
  notFound,      // Anahtar girilmemiş
}

/// Lisans bilgisi
class LicenseInfo {
  final LicenseStatus status;
  final DateTime? expiryDate;
  final int? daysRemaining;
  final String? message;

  LicenseInfo({
    required this.status,
    this.expiryDate,
    this.daysRemaining,
    this.message,
  });
}

/// Lisans servisi
class LicenseService {
  static const String _licenseKey = 'license_key';
  static const String _lastCheckKey = 'last_license_check';

  // Okul kodu (anahtar doğrulama için)
  static const String _schoolCode = 'KBOA';

  // Gizli anahtar (basit şifreleme için)
  static const String _secretKey = 'HatipoğluÖmerAkarsel2024';

  /// Lisans anahtarını kaydet
  static Future<bool> saveLicense(String key) async {
    final prefs = await SharedPreferences.getInstance();

    // Anahtarı doğrula
    if (!_validateKeyFormat(key)) {
      return false;
    }

    await prefs.setString(_licenseKey, key.toUpperCase());
    await prefs.setString(_lastCheckKey, DateTime.now().toIso8601String());
    return true;
  }

  /// Kayıtlı lisans anahtarını al
  static Future<String?> getLicenseKey() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_licenseKey);
  }

  /// Lisans durumunu kontrol et
  static Future<LicenseInfo> checkLicense() async {
    final prefs = await SharedPreferences.getInstance();
    final key = prefs.getString(_licenseKey);

    // Anahtar yoksa
    if (key == null || key.isEmpty) {
      return LicenseInfo(
        status: LicenseStatus.notFound,
        message: 'Lisans anahtarı girilmemiş',
      );
    }

    // Anahtarı çöz
    final expiryDate = _decodeExpiryDate(key);
    if (expiryDate == null) {
      return LicenseInfo(
        status: LicenseStatus.invalid,
        message: 'Geçersiz lisans anahtarı',
      );
    }

    // Tarih manipülasyonunu engelle
    final lastCheck = prefs.getString(_lastCheckKey);
    if (lastCheck != null) {
      final lastCheckDate = DateTime.parse(lastCheck);
      if (DateTime.now().isBefore(lastCheckDate.subtract(const Duration(days: 1)))) {
        // Sistem tarihi geriye alınmış
        return LicenseInfo(
          status: LicenseStatus.expired,
          expiryDate: expiryDate,
          message: 'Sistem tarihi değiştirilmiş. Lisans geçersiz.',
        );
      }
    }

    // Son kontrol tarihini güncelle
    await prefs.setString(_lastCheckKey, DateTime.now().toIso8601String());

    final now = DateTime.now();
    final difference = expiryDate.difference(now).inDays;

    // Süre dolmuş
    if (now.isAfter(expiryDate)) {
      return LicenseInfo(
        status: LicenseStatus.expired,
        expiryDate: expiryDate,
        daysRemaining: 0,
        message: 'Lisans süresi ${_formatDate(expiryDate)} tarihinde doldu',
      );
    }

    // 10 gün içinde dolacak
    if (difference <= 10) {
      return LicenseInfo(
        status: LicenseStatus.expiringSoon,
        expiryDate: expiryDate,
        daysRemaining: difference,
        message: 'Lisans $difference gün içinde dolacak (${_formatDate(expiryDate)})',
      );
    }

    // Geçerli
    return LicenseInfo(
      status: LicenseStatus.valid,
      expiryDate: expiryDate,
      daysRemaining: difference,
      message: 'Lisans geçerli (${_formatDate(expiryDate)} tarihine kadar)',
    );
  }

  /// Lisansı sil
  static Future<void> clearLicense() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_licenseKey);
    await prefs.remove(_lastCheckKey);
  }

  /// Anahtar formatını doğrula
  static bool _validateKeyFormat(String key) {
    // Format: KBOA-2027-0201-X7K9
    final parts = key.toUpperCase().split('-');
    if (parts.length != 4) return false;
    if (parts[0] != _schoolCode) return false;
    if (parts[1].length != 4) return false;
    if (parts[2].length != 4) return false;
    if (parts[3].length != 4) return false;

    // Yıl kontrolü
    final year = int.tryParse(parts[1]);
    if (year == null || year < 2024 || year > 2100) return false;

    // Ay/gün kontrolü
    final monthDay = parts[2];
    final month = int.tryParse(monthDay.substring(0, 2));
    final day = int.tryParse(monthDay.substring(2, 4));
    if (month == null || day == null) return false;
    if (month < 1 || month > 12) return false;
    if (day < 1 || day > 31) return false;

    // Doğrulama kodu kontrolü
    final expectedCode = _generateVerificationCode(parts[1], parts[2]);
    if (parts[3] != expectedCode) return false;

    return true;
  }

  /// Bitiş tarihini çöz
  static DateTime? _decodeExpiryDate(String key) {
    try {
      final parts = key.toUpperCase().split('-');
      if (parts.length != 4) return null;

      final year = int.parse(parts[1]);
      final month = int.parse(parts[2].substring(0, 2));
      final day = int.parse(parts[2].substring(2, 4));

      return DateTime(year, month, day, 23, 59, 59);
    } catch (e) {
      return null;
    }
  }

  /// Doğrulama kodu oluştur
  static String _generateVerificationCode(String year, String monthDay) {
    final input = '$_schoolCode$year$monthDay$_secretKey';
    final bytes = utf8.encode(input);

    // Basit hash
    int hash = 0;
    for (var byte in bytes) {
      hash = ((hash << 5) - hash + byte) & 0xFFFFFFFF;
    }

    // 4 karakterlik kod
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    final code = StringBuffer();
    for (int i = 0; i < 4; i++) {
      code.write(chars[(hash >> (i * 5)) & 31]);
    }

    return code.toString();
  }

  /// Tarih formatla
  static String _formatDate(DateTime date) {
    return '${date.day.toString().padLeft(2, '0')}.${date.month.toString().padLeft(2, '0')}.${date.year}';
  }

  /// YENİ LİSANS ANAHTARI OLUŞTUR (Sadece geliştirici için)
  /// Bu fonksiyonu ayrı bir araçta kullanacaksınız
  static String generateLicenseKey(int year, int month, int day) {
    final monthDay = '${month.toString().padLeft(2, '0')}${day.toString().padLeft(2, '0')}';
    final yearStr = year.toString();
    final code = _generateVerificationCode(yearStr, monthDay);
    return '$_schoolCode-$yearStr-$monthDay-$code';
  }
}
