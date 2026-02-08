import 'dart:async';
import 'dart:convert';
import 'dart:io' show Platform;
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
  Position? _previousPosition;
  DateTime? _previousPositionTime;

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
    late LocationSettings locationSettings;

    if (Platform.isAndroid) {
      locationSettings = AndroidSettings(
        accuracy: LocationAccuracy.high,
        distanceFilter: 0,
        intervalDuration: const Duration(seconds: 1),
      );
    } else {
      locationSettings = const LocationSettings(
        accuracy: LocationAccuracy.high,
        distanceFilter: 0,
      );
    }

    _positionStream = Geolocator.getPositionStream(locationSettings: locationSettings)
        .listen((Position position) {
      _updateSpeed(position);
    });

    _limitUpdateTimer = Timer.periodic(const Duration(seconds: 5), (timer) async {
       Position? position = await Geolocator.getLastKnownPosition();
       if (position != null) {
         _fetchSpeedLimit(position.latitude, position.longitude);
       }
    });
  }

  void _updateSpeed(Position position) {
    double speedKmH = 0;

    // Birincil: GPS'in kendi hız verisi
    if (position.speed > 0) {
      speedKmH = position.speed * 3.6;
    } else if (_previousPosition != null && _previousPositionTime != null) {
      // Yedek: iki konum arası mesafe/süre hesabı
      double distanceMeters = Geolocator.distanceBetween(
        _previousPosition!.latitude,
        _previousPosition!.longitude,
        position.latitude,
        position.longitude,
      );
      double timeSec = DateTime.now().difference(_previousPositionTime!).inMilliseconds / 1000.0;
      if (timeSec > 0.5) {
        speedKmH = (distanceMeters / timeSec) * 3.6;
      }
    }

    _previousPosition = position;
    _previousPositionTime = DateTime.now();

    // GPS gürültüsünü filtrele (2 km/h altı = durağan)
    if (speedKmH < 2) speedKmH = 0;

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
        return -1;
    }
  }

  Future<void> _fetchSpeedLimit(double lat, double lon) async {
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
          final List elements = data['elements'];

          // Tüm yollardan en düşük hız limitini al (en güvenli)
          int minLimit = 999;

          for (var element in elements) {
            final tags = element['tags'];
            if (tags == null) continue;

            String? maxSpeedStr = tags['maxspeed'];
            if (maxSpeedStr == null) continue;

            int limit = -1;

            // Önce sayısal değer dene
            int? numericLimit = int.tryParse(maxSpeedStr);
            if (numericLimit != null) {
              limit = numericLimit;
            } else {
              // TR:urban, TR:rural gibi etiketleri çözümle
              limit = _resolveTurkishSpeedTag(maxSpeedStr);
            }

            // Geçerli ve en düşük limiti seç
            if (limit > 0 && limit < minLimit) {
              minLimit = limit;
            }
          }

          // Türkiye'de max 120 km/h (otoyol), 130 üzeri hatalı veri
          if (minLimit > 130) {
            minLimit = 120;
          }

          if (minLimit > 0 && minLimit < 999) {
            setState(() {
              _speedLimitKmH = minLimit;
              _checkOverspeed();
            });
          }
        }
      }
    } catch (e) {
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
                      fontSize: 90,
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
                        fontSize: 60,
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
