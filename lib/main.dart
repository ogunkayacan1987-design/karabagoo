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

  // Türkiye yol tipi etiketlerine göre varsayılan hız limitleri
  int _resolveTurkishSpeedTag(String tag) {
    switch (tag) {
      case 'TR:urban':
        return 50;
      case 'TR:rural':
        return 90;
      case 'TR:motorway':
        return 120;
      default:
        return -1; // Bilinmeyen etiket
    }
  }

  // Yol tipine (highway) göre Türkiye varsayılan hız limitleri
  int _defaultSpeedForHighway(String? highway) {
    switch (highway) {
      case 'motorway':
      case 'motorway_link':
        return 120;
      case 'trunk':
      case 'trunk_link':
        return 110;
      case 'primary':
      case 'secondary':
        return 90;
      case 'tertiary':
      case 'residential':
      case 'living_street':
      case 'service':
      case 'unclassified':
        return 50;
      default:
        return 50;
    }
  }

  // Şehir içi yolları önceliklendir: düşük sıra = yüksek öncelik
  int _roadPriority(String? highway) {
    switch (highway) {
      case 'residential':
      case 'living_street':
        return 0;
      case 'service':
        return 1;
      case 'tertiary':
      case 'tertiary_link':
      case 'unclassified':
        return 2;
      case 'secondary':
      case 'secondary_link':
        return 3;
      case 'primary':
      case 'primary_link':
        return 4;
      case 'trunk':
      case 'trunk_link':
        return 5;
      case 'motorway':
      case 'motorway_link':
        return 6;
      default:
        return 3;
    }
  }

  Future<void> _fetchSpeedLimit(double lat, double lon) async {
    // Overpass API: tüm yolları çek (maxspeed olsun olmasın), highway etiketiyle birlikte
    final String query = """
      [out:json];
      way[highway](around:30,$lat,$lon);
      out tags;
    """;
    final String url = "https://overpass-api.de/api/interpreter?data=${Uri.encodeComponent(query)}";

    try {
      final response = await http.get(Uri.parse(url));
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data['elements'] != null && data['elements'].isNotEmpty) {
          final List elements = data['elements'];

          // En uygun yolu bul: şehir içi yolları öncelikle
          int bestLimit = -1;
          int bestPriority = 999;

          for (var element in elements) {
            final tags = element['tags'];
            if (tags == null) continue;

            String? highway = tags['highway'];
            String? maxSpeedStr = tags['maxspeed'];
            int priority = _roadPriority(highway);
            int limit = -1;

            if (maxSpeedStr != null) {
              // Önce sayısal değer dene
              int? numericLimit = int.tryParse(maxSpeedStr);
              if (numericLimit != null) {
                limit = numericLimit;
              } else {
                // TR:urban, TR:rural gibi etiketleri çözümle
                limit = _resolveTurkishSpeedTag(maxSpeedStr);
              }
            }

            // maxspeed etiketi yoksa veya ayrıştırılamadıysa, yol tipinden varsayılan kullan
            if (limit <= 0) {
              limit = _defaultSpeedForHighway(highway);
            }

            // Türkiye'de gerçekçi üst sınır: 120 km/h (otoyol)
            // 130'un üzerindeki değerler hatalı veri
            if (limit > 130) {
              limit = 120;
            }

            // En yüksek öncelikli (en düşük priority değeri) yolu seç
            if (priority < bestPriority) {
              bestPriority = priority;
              bestLimit = limit;
            }
          }

          if (bestLimit > 0) {
            setState(() {
              _speedLimitKmH = bestLimit;
              _checkOverspeed();
            });
          }
        }
      }
    } catch (e) {
      // API hatası: mevcut değeri koru
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
