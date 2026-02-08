// Window manager stub for non-desktop platforms (Android/iOS)
// Bu dosya Android buildde window_manager yerine kullanilir

mixin WindowListener {
  void onWindowClose() {}
  void onWindowFocus() {}
  void onWindowBlur() {}
  void onWindowMaximize() {}
  void onWindowUnmaximize() {}
  void onWindowMinimize() {}
  void onWindowRestore() {}
  void onWindowResize() {}
  void onWindowMove() {}
  void onWindowEvent(String eventName) {}
}

class _WMStub {
  Future<void> ensureInitialized() async {}
  void waitUntilReadyToShow(dynamic options, dynamic callback) {}
  Future<void> show() async {}
  Future<void> focus() async {}
  Future<void> minimize() async {}
  Future<void> restore() async {}
  Future<void> setPreventClose(bool v) async {}
  Future<void> setAlwaysOnTop(bool v) async {}
  Future<bool> isMinimized() async => false;
  void addListener(dynamic l) {}
  void removeListener(dynamic l) {}
}

class WindowOptions {
  final dynamic size;
  final dynamic minimumSize;
  final bool? center;
  final dynamic backgroundColor;
  final bool? skipTaskbar;
  final dynamic titleBarStyle;
  final String? title;
  const WindowOptions({
    this.size,
    this.minimumSize,
    this.center,
    this.backgroundColor,
    this.skipTaskbar,
    this.titleBarStyle,
    this.title,
  });
}

class TitleBarStyle {
  static const normal = TitleBarStyle._();
  const TitleBarStyle._();
}

final windowManager = _WMStub();
