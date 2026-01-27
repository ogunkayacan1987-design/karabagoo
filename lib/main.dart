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

class _SpeedScreenState extends State<SpeedScreen> {
  static const int _defaultSpeedLimit = 50;
  static const Duration _gpsInterval = Duration(seconds: 1);
  static const Duration _limitFetchInterval = Duration(seconds: 10);
  static const Duration _overpassTimeout = Duration(seconds: 5);

  int _currentSpeed = 0;
  int _speedLimit = _defaultSpeedLimit;
  bool _isExceeding = false;
  bool _hasPermission = false;
  bool _isLoading = true;
  String? _errorMessage;

  late FlutterTts _tts;
  Timer? _gpsTimer;
  Timer? _limitTimer;
  double _lastLat = 0;
  double _lastLon = 0;
  bool _warningSpoken = false;
  bool _isFetchingLimit = false;

  @override
  void initState() {
    super.initState();
    _initTts();
    _initLocation();
    WakelockPlus.enable();
  }

  @override
  void dispose() {
    _gpsTimer?.cancel();
    _limitTimer?.cancel();
    _tts.stop();
    WakelockPlus.disable();
    super.dispose();
  }

  void _initTts() {
    _tts = FlutterTts();
    _tts.setLanguage('en-US');
    _tts.setSpeechRate(0.5);
    _tts.setVolume(1.0);
    _tts.setPitch(1.0);
  }

  Future<void> _initLocation() async {
    final serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      setState(() {
        _isLoading = false;
        _errorMessage = 'Location services are disabled.\nPlease enable GPS.';
      });
      return;
    }

    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        setState(() {
          _isLoading = false;
          _errorMessage = 'Location permission denied.\nPlease grant access.';
        });
        return;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      setState(() {
        _isLoading = false;
        _errorMessage =
            'Location permission permanently denied.\nPlease enable in Settings.';
      });
      return;
    }

    setState(() {
      _hasPermission = true;
      _isLoading = false;
    });

    _startGpsUpdates();
    _startLimitFetching();
  }

  void _startGpsUpdates() {
    _updatePosition();
    _gpsTimer = Timer.periodic(_gpsInterval, (_) => _updatePosition());
  }

  void _startLimitFetching() {
    _limitTimer = Timer.periodic(_limitFetchInterval, (_) {
      if (_lastLat != 0 && _lastLon != 0) {
        _fetchSpeedLimit(_lastLat, _lastLon);
      }
    });
  }

  Future<void> _updatePosition() async {
    try {
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          distanceFilter: 0,
        ),
      );

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
      if (_lastLat != 0 && _lastLon != 0 && _speedLimit == _defaultSpeedLimit && !_isFetchingLimit) {
        _fetchSpeedLimit(lat, lon);
      }
    } catch (_) {
      // GPS reading failed, keep last known values
    }
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
    // Handle formats: "50", "50 km/h", "30 mph", etc.
    final cleaned = raw.replaceAll(RegExp(r'[^0-9.]'), ' ').trim();
    final parts = cleaned.split(RegExp(r'\s+'));
    if (parts.isEmpty) return null;

    final value = int.tryParse(parts[0]);
    if (value == null) return null;

    // Convert mph to km/h if needed
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
          child: CircularProgressIndicator(color: Colors.white),
        ),
      );
    }

    if (_errorMessage != null) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Text(
              _errorMessage!,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white70,
                fontSize: 24,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
        ),
      );
    }

    if (!_hasPermission) {
      return const Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: Text(
            'Waiting for GPS permission...',
            style: TextStyle(color: Colors.white70, fontSize: 24),
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
