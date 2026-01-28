import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  runApp(const SpeedGuardApp());
}

class SpeedGuardApp extends StatelessWidget {
  const SpeedGuardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SpeedGuard',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: Colors.black,
      ),
      home: const SpeedScreen(),
    );
  }
}

class SpeedScreen extends StatefulWidget {
  const SpeedScreen({super.key});

  @override
  State<SpeedScreen> createState() => _SpeedScreenState();
}

class _SpeedScreenState extends State<SpeedScreen> with WidgetsBindingObserver {
  static const int _defaultSpeedLimit = 50;
  static const Duration _limitFetchInterval = Duration(seconds: 10);
  static const Duration _overpassTimeout = Duration(seconds: 5);

  int _currentSpeed = 0;
  int _speedLimit = _defaultSpeedLimit;
  bool _isExceeding = false;
  bool _hasPermission = false;
  bool _isLoading = true;
  String? _errorMessage;

  late FlutterTts _tts;
  StreamSubscription<Position>? _positionStream;
  Timer? _limitTimer;
  double _lastLat = 0;
  double _lastLon = 0;
  bool _warningSpoken = false;
  bool _isFetchingLimit = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initTts();
    _initLocation();
    WakelockPlus.enable();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _positionStream?.cancel();
    _limitTimer?.cancel();
    _tts.stop();
    WakelockPlus.disable();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check permission when user returns from Settings
    if (state == AppLifecycleState.resumed && !_hasPermission) {
      _initLocation();
    }
  }

  void _initTts() {
    _tts = FlutterTts();
    _tts.setLanguage('en-US');
    _tts.setSpeechRate(0.5);
    _tts.setVolume(1.0);
    _tts.setPitch(1.0);
  }

  Future<void> _initLocation() async {
    try {
      final serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        setState(() {
          _isLoading = false;
          _errorMessage = 'GPS kapalı!\nLütfen konum servislerini açın.';
        });
        return;
      }

      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          setState(() {
            _isLoading = false;
            _errorMessage = 'Konum izni reddedildi.\nLütfen izin verin.';
          });
          return;
        }
      }

      if (permission == LocationPermission.deniedForever) {
        setState(() {
          _isLoading = false;
          _errorMessage =
              'Konum izni kalıcı olarak reddedildi.\nAyarlardan izin verin.';
        });
        // Try to open app settings so user can grant permission
        await Geolocator.openAppSettings();
        return;
      }

      setState(() {
        _hasPermission = true;
        _isLoading = false;
        _errorMessage = null;
      });

      _startGpsStream();
      _startLimitFetching();
    } catch (e) {
      setState(() {
        _isLoading = false;
        _errorMessage = 'GPS hatası: $e';
      });
    }
  }

  void _startGpsStream() {
    // Cancel existing stream if any
    _positionStream?.cancel();

    // Use position stream for continuous tracking (much better than polling)
    const locationSettings = LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 0,
    );

    _positionStream = Geolocator.getPositionStream(
      locationSettings: locationSettings,
    ).listen(
      (Position position) {
        final speedMps = position.speed < 0 ? 0.0 : position.speed;
        final speedKmh = (speedMps * 3.6).round();

        final lat = position.latitude;
        final lon = position.longitude;

        final exceeding = speedKmh > _speedLimit;

        if (exceeding && !_warningSpoken) {
          _warningSpoken = true;
          _tts.speak('Speed limit exceeded');
        } else if (!exceeding) {
          _warningSpoken = false;
        }

        setState(() {
          _currentSpeed = speedKmh;
          _isExceeding = exceeding;
          _lastLat = lat;
          _lastLon = lon;
        });

        // Fetch speed limit on first valid position
        if (lat != 0 && lon != 0 && _speedLimit == _defaultSpeedLimit && !_isFetchingLimit) {
          _fetchSpeedLimit(lat, lon);
        }
      },
      onError: (error) {
        // Don't crash, just log
        debugPrint('GPS stream error: $error');
      },
    );
  }

  void _startLimitFetching() {
    _limitTimer?.cancel();
    _limitTimer = Timer.periodic(_limitFetchInterval, (_) {
      if (_lastLat != 0 && _lastLon != 0) {
        _fetchSpeedLimit(_lastLat, _lastLon);
      }
    });
  }

  Future<void> _fetchSpeedLimit(double lat, double lon) async {
    if (_isFetchingLimit) return;
    _isFetchingLimit = true;

    try {
      final query = '''
[out:json][timeout:5];
way(around:30,$lat,$lon)["maxspeed"];
out tags;
''';

      final uri = Uri.parse('https://overpass-api.de/api/interpreter');
      final response = await http.post(
        uri,
        body: {'data': query},
      ).timeout(_overpassTimeout);

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final elements = data['elements'] as List?;

        if (elements != null && elements.isNotEmpty) {
          final tags = elements[0]['tags'] as Map<String, dynamic>?;
          if (tags != null && tags.containsKey('maxspeed')) {
            final raw = tags['maxspeed'] as String;
            final parsed = _parseSpeedLimit(raw);
            if (parsed != null) {
              setState(() => _speedLimit = parsed);
            }
          }
        }
      }
    } catch (_) {
      // Network error — keep current speed limit
    } finally {
      _isFetchingLimit = false;
    }
  }

  int? _parseSpeedLimit(String raw) {
    final cleaned = raw.replaceAll(RegExp(r'[^0-9.]'), ' ').trim();
    final parts = cleaned.split(RegExp(r'\s+'));
    if (parts.isEmpty) return null;

    final value = int.tryParse(parts[0]);
    if (value == null) return null;

    if (raw.toLowerCase().contains('mph')) {
      return (value * 1.60934).round();
    }

    return value;
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(color: Colors.white),
              SizedBox(height: 20),
              Text(
                'GPS bekleniyor...',
                style: TextStyle(color: Colors.white70, fontSize: 20),
              ),
            ],
          ),
        ),
      );
    }

    if (_errorMessage != null) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(Icons.location_off, color: Colors.red, size: 64),
                const SizedBox(height: 20),
                Text(
                  _errorMessage!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 24,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 30),
                ElevatedButton.icon(
                  onPressed: _initLocation,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Tekrar Dene', style: TextStyle(fontSize: 18)),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.white24,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Row(
          children: [
            // Left side — Current Speed
            Expanded(
              child: _buildSpeedDisplay(
                label: 'km/h',
                value: _currentSpeed.toString(),
                color: _isExceeding ? Colors.red : Colors.green,
              ),
            ),
            // Divider
            Container(
              width: 2,
              margin: const EdgeInsets.symmetric(vertical: 40),
              color: Colors.white24,
            ),
            // Right side — Speed Limit
            Expanded(
              child: _buildSpeedDisplay(
                label: 'LIMIT',
                value: _speedLimit.toString(),
                color: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSpeedDisplay({
    required String label,
    required String value,
    required Color color,
  }) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text(
          value,
          style: TextStyle(
            color: color,
            fontSize: 140,
            fontWeight: FontWeight.w900,
            height: 1.0,
            fontFamily: 'monospace',
          ),
        ),
        const SizedBox(height: 8),
        Text(
          label,
          style: TextStyle(
            color: color.withAlpha(180),
            fontSize: 28,
            fontWeight: FontWeight.w600,
            letterSpacing: 4,
          ),
        ),
      ],
    );
  }
}
