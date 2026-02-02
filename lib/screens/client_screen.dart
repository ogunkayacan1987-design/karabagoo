import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:intl/intl.dart';
import 'package:window_manager/window_manager.dart';

import '../models/message.dart';
import '../models/room.dart';
import '../services/network_service.dart';

class ClientScreen extends StatefulWidget {
  const ClientScreen({super.key});

  @override
  State<ClientScreen> createState() => _ClientScreenState();
}

class _ClientScreenState extends State<ClientScreen> {
  final ClientService _client = ClientService();
  final TextEditingController _ipController = TextEditingController();
  final AudioPlayer _audioPlayer = AudioPlayer();
  final List<Message> _messages = [];

  Room? _selectedRoom;
  bool _isConnected = false;
  bool _isConnecting = false;
  bool _showSetup = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
    _setupClient();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final savedIp = prefs.getString('server_ip');
    final savedRoomId = prefs.getString('room_id');

    if (savedIp != null) {
      _ipController.text = savedIp;
    }

    if (savedRoomId != null) {
      final rooms = Room.getDefaultRooms();
      _selectedRoom = rooms.where((r) => r.id == savedRoomId).firstOrNull;
    }

    // Eğer daha önce ayarlar kaydedilmişse otomatik bağlan
    if (savedIp != null && _selectedRoom != null) {
      setState(() => _showSetup = false);
      _connect();
    }
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('server_ip', _ipController.text);
    if (_selectedRoom != null) {
      await prefs.setString('room_id', _selectedRoom!.id);
    }
  }

  void _setupClient() {
    _client.onConnectionChanged = (roomId, isConnected) {
      setState(() {
        _isConnected = isConnected;
        _isConnecting = false;
      });
      if (isConnected) {
        _showSnackBar('Sunucuya bağlandı', Colors.green);
      } else {
        _showSnackBar('Bağlantı kesildi, yeniden bağlanılıyor...', Colors.orange);
      }
    };

    _client.onMessageReceived = (message) {
      setState(() {
        _messages.insert(0, message);
      });
      _handleNewMessage(message);
    };

    _client.onError = (error) {
      _showSnackBar(error, Colors.red);
      setState(() => _isConnecting = false);
    };
  }

  void _handleNewMessage(Message message) {
    // Windows'ta taskbar'da yanıp sön ve pencereyi ön plana getir
    _showWindowsNotification(message);

    switch (message.type) {
      case MessageType.alert:
        _playAlertSound();
        _showAlertDialog(message);
        break;
      case MessageType.call:
        _playAlertSound();
        _showCallDialog(message);
        break;
      case MessageType.file:
        _showFileDialog(message);
        break;
      case MessageType.text:
        _playNotificationSound();
        break;
    }
  }

  Future<void> _showWindowsNotification(Message message) async {
    if (Platform.isWindows) {
      // Taskbar'da yanıp sön
      await windowManager.setAlwaysOnTop(true);
      await Future.delayed(const Duration(milliseconds: 100));
      await windowManager.setAlwaysOnTop(false);

      // Pencereyi ön plana getir
      await windowManager.focus();

      // Eğer minimize ise restore et
      if (await windowManager.isMinimized()) {
        await windowManager.restore();
      }
    }
  }

  Future<void> _playAlertSound() async {
    try {
      await _audioPlayer.play(AssetSource('sounds/alert.mp3'));
    } catch (e) {
      // Ses dosyası yoksa Windows sistem sesi çal
      print('Uyarı sesi çalınamadı: $e');
    }
  }

  Future<void> _playNotificationSound() async {
    try {
      await _audioPlayer.play(AssetSource('sounds/notification.mp3'));
    } catch (e) {
      print('Bildirim sesi çalınamadı: $e');
    }
  }

  void _showAlertDialog(Message message) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        backgroundColor: Colors.orange[50],
        title: Row(
          children: [
            Icon(Icons.warning, color: Colors.orange[700], size: 32),
            const SizedBox(width: 12),
            Text(
              'ACİL DUYURU',
              style: TextStyle(
                color: Colors.orange[700],
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
        content: Container(
          constraints: const BoxConstraints(minWidth: 400),
          child: Text(
            message.content,
            style: const TextStyle(fontSize: 24),
          ),
        ),
        actions: [
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.orange,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
            ),
            child: const Text('Tamam', style: TextStyle(fontSize: 18)),
          ),
        ],
      ),
    );
  }

  void _showCallDialog(Message message) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        backgroundColor: Colors.blue[50],
        title: Row(
          children: [
            Icon(Icons.person_search, color: Colors.blue[700], size: 32),
            const SizedBox(width: 12),
            Text(
              'ÖĞRENCİ ÇAĞRISI',
              style: TextStyle(
                color: Colors.blue[700],
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
        content: Container(
          constraints: const BoxConstraints(minWidth: 400),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(height: 16),
              Text(
                message.content,
                style: const TextStyle(
                  fontSize: 36,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                'Lütfen yönetim odasına gidin',
                style: TextStyle(fontSize: 18, color: Colors.grey),
              ),
            ],
          ),
        ),
        actions: [
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.blue,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
            ),
            child: const Text('Tamam', style: TextStyle(fontSize: 18)),
          ),
        ],
      ),
    );
  }

  void _showFileDialog(Message message) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(Icons.attach_file, color: Colors.teal[700]),
            const SizedBox(width: 12),
            const Text('Dosya Alındı'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Dosya Adı: ${message.fileName}',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            if (message.fileSize != null)
              Text('Boyut: ${_formatFileSize(message.fileSize!)}'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Kapat'),
          ),
          ElevatedButton.icon(
            onPressed: () {
              Navigator.of(context).pop();
              _saveFile(message);
            },
            icon: const Icon(Icons.save),
            label: const Text('Kaydet'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.teal,
              foregroundColor: Colors.white,
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _saveFile(Message message) async {
    if (message.fileData == null || message.fileName == null) return;

    try {
      final directory = await getDownloadsDirectory() ??
          await getApplicationDocumentsDirectory();
      final filePath = '${directory.path}/${message.fileName}';
      final file = File(filePath);
      await file.writeAsBytes(message.fileData!);
      _showSnackBar('Dosya kaydedildi: $filePath', Colors.green);
    } catch (e) {
      _showSnackBar('Dosya kaydedilemedi: $e', Colors.red);
    }
  }

  String _formatFileSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  void _showSnackBar(String message, Color color) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: color,
        duration: const Duration(seconds: 3),
      ),
    );
  }

  Future<void> _connect() async {
    if (_ipController.text.isEmpty || _selectedRoom == null) {
      _showSnackBar('Lütfen sunucu IP ve sınıf seçin', Colors.red);
      return;
    }

    setState(() => _isConnecting = true);
    await _saveSettings();

    await _client.connect(
      _ipController.text.trim(),
      5555,
      _selectedRoom!,
    );
  }

  void _showSettings() {
    setState(() => _showSetup = true);
  }

  @override
  void dispose() {
    _client.disconnect();
    _ipController.dispose();
    _audioPlayer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_showSetup) {
      return _buildSetupScreen();
    }
    return _buildMainScreen();
  }

  Widget _buildSetupScreen() {
    final rooms = Room.getDefaultRooms()
        .where((r) => r.type == RoomType.classroom)
        .toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Karabağ H.Ö.Akarsel Ortaokulu - Kurulum'),
        backgroundColor: Colors.teal,
        foregroundColor: Colors.white,
      ),
      body: Center(
        child: Card(
          margin: const EdgeInsets.all(32),
          child: Container(
            constraints: const BoxConstraints(maxWidth: 500),
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Bağlantı Ayarları',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 24),
                TextField(
                  controller: _ipController,
                  decoration: const InputDecoration(
                    labelText: 'Sunucu IP Adresi',
                    hintText: '192.168.1.100',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.computer),
                  ),
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<Room>(
                  value: _selectedRoom,
                  decoration: const InputDecoration(
                    labelText: 'Sınıf Seçin',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.meeting_room),
                  ),
                  items: rooms.map((room) {
                    return DropdownMenuItem(
                      value: room,
                      child: Text(room.name),
                    );
                  }).toList(),
                  onChanged: (room) {
                    setState(() => _selectedRoom = room);
                  },
                ),
                const SizedBox(height: 32),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    onPressed: _isConnecting
                        ? null
                        : () {
                            setState(() => _showSetup = false);
                            _connect();
                          },
                    icon: _isConnecting
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Icon(Icons.link),
                    label: Text(
                      _isConnecting ? 'Bağlanıyor...' : 'Bağlan',
                      style: const TextStyle(fontSize: 18),
                    ),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.teal,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 16),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMainScreen() {
    return Scaffold(
      appBar: AppBar(
        title: Text(_selectedRoom?.name ?? 'Sınıf'),
        backgroundColor: _isConnected ? Colors.teal : Colors.grey,
        foregroundColor: Colors.white,
        actions: [
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            decoration: BoxDecoration(
              color: _isConnected ? Colors.green : Colors.red,
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              children: [
                Icon(
                  _isConnected ? Icons.wifi : Icons.wifi_off,
                  size: 16,
                  color: Colors.white,
                ),
                const SizedBox(width: 4),
                Text(
                  _isConnected ? 'Bağlı' : 'Bağlantı Yok',
                  style: const TextStyle(color: Colors.white),
                ),
              ],
            ),
          ),
          if (_messages.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete_sweep),
              onPressed: () {
                showDialog(
                  context: context,
                  builder: (ctx) => AlertDialog(
                    title: const Text('Mesajları Temizle'),
                    content: const Text('Tüm mesajlar silinecek. Emin misiniz?'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.of(ctx).pop(),
                        child: const Text('İptal'),
                      ),
                      ElevatedButton(
                        onPressed: () {
                          setState(() => _messages.clear());
                          Navigator.of(ctx).pop();
                          _showSnackBar('Mesajlar temizlendi', Colors.blue);
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.red,
                          foregroundColor: Colors.white,
                        ),
                        child: const Text('Temizle'),
                      ),
                    ],
                  ),
                );
              },
              tooltip: 'Mesajları Temizle',
            ),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: _showSettings,
            tooltip: 'Ayarlar',
          ),
        ],
      ),
      body: _messages.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.inbox,
                    size: 80,
                    color: Colors.grey[300],
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Henüz mesaj yok',
                    style: TextStyle(
                      fontSize: 20,
                      color: Colors.grey[400],
                    ),
                  ),
                  if (!_isConnected) ...[
                    const SizedBox(height: 8),
                    Text(
                      'Sunucuya bağlanılıyor...',
                      style: TextStyle(
                        fontSize: 14,
                        color: Colors.grey[400],
                      ),
                    ),
                  ],
                ],
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: _messages.length,
              itemBuilder: (context, index) {
                final message = _messages[index];
                return _buildMessageCard(message);
              },
            ),
    );
  }

  Widget _buildMessageCard(Message message) {
    Color cardColor;
    IconData icon;
    String typeLabel;

    switch (message.type) {
      case MessageType.alert:
        cardColor = Colors.orange[50]!;
        icon = Icons.warning;
        typeLabel = 'ACİL DUYURU';
        break;
      case MessageType.call:
        cardColor = Colors.blue[50]!;
        icon = Icons.person_search;
        typeLabel = 'ÖĞRENCİ ÇAĞRISI';
        break;
      case MessageType.file:
        cardColor = Colors.teal[50]!;
        icon = Icons.attach_file;
        typeLabel = 'DOSYA';
        break;
      case MessageType.text:
      default:
        cardColor = Colors.white;
        icon = Icons.message;
        typeLabel = 'MESAJ';
    }

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      color: cardColor,
      elevation: 2,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 20, color: Colors.grey[700]),
                const SizedBox(width: 8),
                Text(
                  typeLabel,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Colors.grey[700],
                  ),
                ),
                const Spacer(),
                Text(
                  DateFormat('HH:mm').format(message.timestamp),
                  style: TextStyle(
                    fontSize: 12,
                    color: Colors.grey[500],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              message.type == MessageType.call
                  ? '${message.content} - Yönetim odasına gelmesi isteniyor'
                  : message.content,
              style: TextStyle(
                fontSize: message.type == MessageType.call ? 24 : 18,
                fontWeight: message.priority == MessagePriority.urgent
                    ? FontWeight.bold
                    : FontWeight.normal,
              ),
            ),
            if (message.type == MessageType.file && message.fileName != null) ...[
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => _saveFile(message),
                icon: const Icon(Icons.save),
                label: Text('${message.fileName} - Kaydet'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
