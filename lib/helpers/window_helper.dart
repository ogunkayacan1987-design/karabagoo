
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

class WindowHelper {
  static Future<void> ensureInitialized() async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      await windowManager.ensureInitialized();
    }
  }

  static Future<void> setWindowOptions({
    required Size size,
    required Size minimumSize,
    required bool center,
    required Color backgroundColor,
    required bool skipTaskbar,
    required TitleBarStyle titleBarStyle,
    required String title,
  }) async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      final windowOptions = WindowOptions(
        size: size,
        minimumSize: minimumSize,
        center: center,
        backgroundColor: backgroundColor,
        skipTaskbar: skipTaskbar,
        titleBarStyle: titleBarStyle,
        title: title,
      );

      windowManager.waitUntilReadyToShow(windowOptions, () async {
        await windowManager.show();
        await windowManager.focus();
        await windowManager.setPreventClose(true);
      });
    }
  }

  static Future<void> addListener(WindowListener listener) async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      windowManager.addListener(listener);
    }
  }

  static Future<void> removeListener(WindowListener listener) async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      windowManager.removeListener(listener);
    }
  }

  static Future<void> minimize() async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      await windowManager.minimize();
    }
  }

  static Future<void> setAlwaysOnTop(bool isAlwaysOnTop) async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      await windowManager.setAlwaysOnTop(isAlwaysOnTop);
    }
  }

  static Future<void> focus() async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      await windowManager.focus();
    }
  }

  static Future<bool> isMinimized() async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      return await windowManager.isMinimized();
    }
    return false;
  }

  static Future<void> restore() async {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
      await windowManager.restore();
    }
  }
}
