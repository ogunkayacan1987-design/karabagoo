import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

void main() {
  runApp(const SpeedGuardApp());
}

class SpeedGuardApp extends StatelessWidget {
  const SpeedGuardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'SpeedGuard',
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: Colors.black,
      ),
      home: const DashboardScreen(),
    );
  }
}

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  double _currentSpeedKmH = 0;
  int _speedLimitKmH = 50; // Default limit
  bool _isOverspeeding = false;
  FlutterTts _flutterTts = FlutterTts();
  Timer? _limitUpdateTimer;
  StreamSubscription<Position>? _positionStream;
  DateTime _lastAlertTime = DateTime.now();

  @override
  void initState() {
    super.initState();
    _initApp();
  }

  Future<void> _initApp() async {
    // Keep screen on
    await WakelockPlus.enable();

    // Setup TTS
    await _flutterTts.setLanguage("tr-TR");
    await _flutterTts.setSpeechRate(0.5);

    // Request permissions
    Map<Permission, PermissionStatus> statuses = await [
      Permission.location,
    ].request();

    if (statuses[Permission.location]!.isGranted) {
      _startTracking();
    }
  }

  void _startTracking() {
    // Location Settings
    const LocationSettings locationSettings = LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 0,
    );

    _positionStream = Geolocator.getPositionStream(locationSettings: locationSettings)
        .listen((Position position) {
      _updateSpeed(position);
      // Periodic speed limit check (throttled to avoid API spam, but user said "GPS refresh 1s", APi usage might be heavy if 1s.
      // Optimizing: Check limit only if moved significantly or every few seconds.
      // Requirement: "GPS 1 sec refresh". Limit logic: "Get limit using OSM".
      // We will check limit every 5 seconds to be safe on free API, but update speed instantly.
    });

    _limitUpdateTimer = Timer.periodic(const Duration(seconds: 5), (timer) async {
       Position? position = await Geolocator.getLastKnownPosition();
       if (position != null) {
         _fetchSpeedLimit(position.latitude, position.longitude);
       }
    });
  }

  void _updateSpeed(Position position) {
    // speed is in m/s. Convert to km/h.
    double speedKmH = (position.speed * 3.6);
    if (speedKmH < 0) speedKmH = 0;

    setState(() {
      _currentSpeedKmH = speedKmH;
      _checkOverspeed();
    });
  }

  Future<void> _fetchSpeedLimit(double lat, double lon) async {
    // Overpass API to get maxspeed of nearest way
    // Query: around 25m radius, look for ways with maxspeed.
    final String query = """
      [out:json];
      way[maxspeed](around:25,$lat,$lon);
      out tags;
    """;
    final String url = "https://overpass-api.de/api/interpreter?data=${Uri.encodeComponent(query)}";

    try {
      final response = await http.get(Uri.parse(url));
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data['elements'] != null && data['elements'].isNotEmpty) {
          // Parse maxspeed (can be "50", "30", "TR:urban" etc. Simplified parsing)
          String? maxSpeedStr = data['elements'][0]['tags']['maxspeed'];
          if (maxSpeedStr != null) {
             int? limit = int.tryParse(maxSpeedStr);
             if (limit != null) {
               setState(() {
                 _speedLimitKmH = limit;
                 _checkOverspeed();
               });
             } else {
               // Handle vague formats like "TR:urban" -> defaults?
               // For minimal MVP, ignore complex tags.
             }
          }
        }
      }
    } catch (e) {
      // API Fail: keep previous or default
      debugPrint("API Error: $e");
    }
  }

  void _checkOverspeed() {
    bool over = _currentSpeedKmH > _speedLimitKmH;
    if (over != _isOverspeeding) {
      setState(() {
        _isOverspeeding = over;
      });
    }

    if (over) {
      // Throttle voice alert to every 5 seconds
      if (DateTime.now().difference(_lastAlertTime).inSeconds > 5) {
        _flutterTts.speak("Hız sınırı aşıldı");
        _lastAlertTime = DateTime.now();
      }
    }
  }

  @override
  void dispose() {
    _positionStream?.cancel();
    _limitUpdateTimer?.cancel();
    WakelockPlus.disable();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Color textColor = _isOverspeeding ? Colors.red : Colors.green;

    return Scaffold(
      body: Row(
        children: [
          // Left: Current Speed
          Expanded(
            child: Container(
              color: Colors.black,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    _currentSpeedKmH.toStringAsFixed(0),
                    style: TextStyle(
                      fontSize: 120, // Huge font
                      fontWeight: FontWeight.bold,
                      color: textColor,
                    ),
                  ),
                  const Text(
                    "KM/S",
                    style: TextStyle(color: Colors.grey, fontSize: 20),
                  ),
                ],
              ),
            ),
          ),
          // Divider
          Container(width: 2, color: Colors.grey[800]),
          // Right: Speed Limit
          Expanded(
            child: Container(
              color: Colors.black,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.red, width: 10),
                      color: Colors.white,

                    ),
                    child: Text(
                      _speedLimitKmH.toString(),
                      style: const TextStyle(
                        fontSize: 80,
                        fontWeight: FontWeight.bold,
                        color: Colors.black,
                      ),
                    ),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    "SINIR",
                    style: TextStyle(color: Colors.grey, fontSize: 20),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
