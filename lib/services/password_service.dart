import 'package:shared_preferences/shared_preferences.dart';

/// Şifre yönetim servisi
class PasswordService {
  static const String _adminPasswordKey = 'admin_password';
  static const String _teacherPasswordKey = 'teacher_password';

  static const String defaultAdminPassword = '6731213';
  static const String defaultTeacherPassword = '135790';

  static Future<String> getAdminPassword() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_adminPasswordKey) ?? defaultAdminPassword;
  }

  static Future<String> getTeacherPassword() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_teacherPasswordKey) ?? defaultTeacherPassword;
  }

  static Future<void> setAdminPassword(String password) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_adminPasswordKey, password);
  }

  static Future<void> setTeacherPassword(String password) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_teacherPasswordKey, password);
  }
}
