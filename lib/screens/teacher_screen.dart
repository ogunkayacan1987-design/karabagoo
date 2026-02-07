import 'dart:io';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:intl/intl.dart';

import '../models/message.dart';
import '../models/room.dart';
import '../services/network_service.dart';

class TeacherScreen extends StatefulWidget {
  const TeacherScreen({super.key});

  @override
  State<TeacherScreen> createState() => _TeacherScreenState();
}

class _TeacherScreenState extends State<TeacherScreen> {
  final ClientService _client = ClientService();
  final TextEditingController _ipController = TextEditingController();
  final TextEditingController _messageController = TextEditingController();
  final TextEditingController _studentNameController = TextEditingController();
  final List<Message> _sentMessages = [];
  final List<Message> _receivedMessages = [];
  final Set<String> _selectedRooms = {};

  List<Map<String, dynamic>> _roomStatuses = [];
  bool _isConnected = false;
  bool _isConnecting = false;
  bool _showSetup = true;
  bool _sendToAll = true;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _loadSettings();
    _setupClient();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final savedIp = prefs.getString('teacher_server_ip');

    if (savedIp != null) {
      _ipController.text = savedIp;
      setState(() => _showSetup = false);
      _connect();
    }
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('teacher_server_ip', _ipController.text);
  }

  void _setupClient() {
    _client.onConnectionChanged = (roomId, isConnected) {
      setState(() {
        _isConnected = isConnected;
        _isConnecting = false;
      });
      if (isConnected) {
        _showSnackBar('Sunucuya baglandi', Colors.green);
      } else {
        _showSnackBar('Baglanti kesildi, yeniden baglaniliyor...', Colors.orange);
      }
    };

    _client.onMessageReceived = (message) {
      setState(() {
        _receivedMessages.insert(0, message);
      });
    };

    _client.onRoomStatusChanged = (rooms) {
      setState(() {
        _roomStatuses = rooms;
      });
    };

    _client.onError = (error) {
      _showSnackBar(error, Colors.red);
      setState(() => _isConnecting = false);
    };
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

  String _generateMessageId() {
    return DateTime.now().millisecondsSinceEpoch.toString();
  }

  Future<void> _connect() async {
    if (_ipController.text.isEmpty) {
      _showSnackBar('Lutfen sunucu IP adresini girin', Colors.red);
      return;
    }

    setState(() => _isConnecting = true);
    await _saveSettings();

    final room = Room(
      id: 'ogretmenler',
      name: 'Ogretmenler Odasi',
      type: RoomType.teachersRoom,
    );

    await _client.connect(
      _ipController.text.trim(),
      5555,
      room,
      role: 'teacher',
    );
  }

  void _sendTextMessage() {
    if (_messageController.text.trim().isEmpty) return;

    final message = Message(
      id: _generateMessageId(),
      senderId: 'ogretmenler',
      senderName: 'Ogretmenler Odasi',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.text,
      content: _messageController.text.trim(),
    );

    _client.sendRelayMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _messageController.clear();
    _showSnackBar('Mesaj gonderildi', Colors.green);
  }

  void _sendAlert() {
    if (_messageController.text.trim().isEmpty) return;

    final message = Message(
      id: _generateMessageId(),
      senderId: 'ogretmenler',
      senderName: 'Ogretmenler Odasi',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.alert,
      priority: MessagePriority.urgent,
      content: _messageController.text.trim(),
    );

    _client.sendRelayMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _messageController.clear();
    _showSnackBar('Acil duyuru gonderildi!', Colors.orange);
  }

  void _callStudent() {
    if (_studentNameController.text.trim().isEmpty) return;
    if (_selectedRooms.isEmpty && !_sendToAll) {
      _showSnackBar('Lutfen bir sinif secin', Colors.red);
      return;
    }

    final message = Message(
      id: _generateMessageId(),
      senderId: 'ogretmenler',
      senderName: 'Ogretmenler Odasi',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.call,
      content: _studentNameController.text.trim(),
    );

    _client.sendRelayMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _studentNameController.clear();

    final targetText = _sendToAll
        ? 'tum siniflardan'
        : '${_selectedRooms.join(', ')} sinifindan';
    _showSnackBar('${message.content} $targetText cagrildi', Colors.blue);
  }

  Future<void> _sendFile() async {
    final result = await FilePicker.platform.pickFiles();
    if (result == null || result.files.isEmpty) return;

    final file = result.files.first;
    if (file.path == null) return;

    setState(() => _isLoading = true);

    try {
      final bytes = await File(file.path!).readAsBytes();

      final message = Message(
        id: _generateMessageId(),
        senderId: 'ogretmenler',
        senderName: 'Ogretmenler Odasi',
        targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
        type: MessageType.file,
        content: 'Dosya gonderildi: ${file.name}',
        fileName: file.name,
        fileSize: file.size,
        fileData: bytes,
      );

      _client.sendRelayMessage(message);
      setState(() {
        _sentMessages.insert(0, message);
      });
      _showSnackBar('Dosya gonderildi: ${file.name}', Colors.green);
    } catch (e) {
      _showSnackBar('Dosya gonderilemedi: $e', Colors.red);
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  void dispose() {
    _client.disconnect();
    _ipController.dispose();
    _messageController.dispose();
    _studentNameController.dispose();
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Ogretmen Girisi - Kurulum'),
        backgroundColor: Colors.deepPurple,
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
                Row(
                  children: [
                    Icon(Icons.school, color: Colors.deepPurple[700], size: 32),
                    const SizedBox(width: 12),
                    const Text(
                      'Ogretmenler Odasi Baglantisi',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  'Yonetim bilgisayarinin IP adresini girin',
                  style: TextStyle(color: Colors.grey[600]),
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
                      _isConnecting ? 'Baglaniliyor...' : 'Baglan',
                      style: const TextStyle(fontSize: 18),
                    ),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.deepPurple,
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
    // Oda listesi: sunucudan gelen veya varsayilan
    final rooms = _roomStatuses.isNotEmpty
        ? _roomStatuses
        : Room.getDefaultRooms()
            .where((r) => r.type == RoomType.classroom)
            .map((r) => {
                  'id': r.id,
                  'name': r.name,
                  'type': r.type.index,
                  'isOnline': false,
                })
            .toList();

    final onlineCount = rooms.where((r) => r['isOnline'] == true).length;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ogretmenler Odasi - Mesaj Paneli'),
        backgroundColor: Colors.deepPurple,
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
                  _isConnected ? 'Bagli' : 'Baglanti Yok',
                  style: const TextStyle(color: Colors.white),
                ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => setState(() => _showSetup = true),
            tooltip: 'Ayarlar',
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : Row(
              children: [
                // Sol panel - Odalar
                Container(
                  width: 260,
                  color: Colors.grey[100],
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(16),
                        color: Colors.deepPurple[50],
                        child: Row(
                          children: [
                            Icon(Icons.people, color: Colors.deepPurple[700]),
                            const SizedBox(width: 8),
                            Text(
                              'Siniflar',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                                color: Colors.deepPurple[700],
                              ),
                            ),
                          ],
                        ),
                      ),
                      CheckboxListTile(
                        title: const Text('Tum Siniflara Gonder'),
                        value: _sendToAll,
                        onChanged: (value) {
                          setState(() {
                            _sendToAll = value ?? true;
                            if (_sendToAll) _selectedRooms.clear();
                          });
                        },
                        activeColor: Colors.deepPurple,
                      ),
                      const Divider(),
                      Expanded(
                        child: ListView.builder(
                          itemCount: rooms.length,
                          itemBuilder: (context, index) {
                            final room = rooms[index];
                            final roomId = room['id'] as String;
                            final roomName = room['name'] as String;
                            final isOnline = room['isOnline'] as bool;
                            final isSelected = _selectedRooms.contains(roomId);

                            return ListTile(
                              leading: Icon(
                                Icons.meeting_room,
                                color: isOnline ? Colors.green : Colors.grey,
                              ),
                              title: Text(roomName),
                              subtitle: Text(
                                isOnline ? 'Cevrimici' : 'Cevrimdisi',
                                style: TextStyle(
                                  color: isOnline ? Colors.green : Colors.grey,
                                  fontSize: 12,
                                ),
                              ),
                              trailing: !_sendToAll
                                  ? Checkbox(
                                      value: isSelected,
                                      onChanged: (value) {
                                        setState(() {
                                          if (value == true) {
                                            _selectedRooms.add(roomId);
                                          } else {
                                            _selectedRooms.remove(roomId);
                                          }
                                        });
                                      },
                                      activeColor: Colors.deepPurple,
                                    )
                                  : null,
                              selected: isSelected,
                              selectedTileColor: Colors.deepPurple[50],
                            );
                          },
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          'Cevrimici: $onlineCount/${rooms.length}',
                          style: TextStyle(
                            color: Colors.grey[600],
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                // Orta panel
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Mesaj kutusu
                        Card(
                          elevation: 2,
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text(
                                  'Mesaj / Duyuru Gonder',
                                  style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                const SizedBox(height: 16),
                                TextField(
                                  controller: _messageController,
                                  maxLines: 3,
                                  decoration: const InputDecoration(
                                    hintText: 'Mesajinizi yazin...',
                                    border: OutlineInputBorder(),
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    ElevatedButton.icon(
                                      onPressed: _isConnected ? _sendTextMessage : null,
                                      icon: const Icon(Icons.send),
                                      label: const Text('Gonder'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.deepPurple,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 24, vertical: 12),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ElevatedButton.icon(
                                      onPressed: _isConnected ? _sendAlert : null,
                                      icon: const Icon(Icons.warning),
                                      label: const Text('Acil Duyuru'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.orange,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 24, vertical: 12),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ElevatedButton.icon(
                                      onPressed: _isConnected ? _sendFile : null,
                                      icon: const Icon(Icons.attach_file),
                                      label: const Text('Dosya Gonder'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.teal,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 24, vertical: 12),
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),

                        const SizedBox(height: 24),

                        // Ogrenci cagirma
                        Card(
                          elevation: 2,
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text(
                                  'Ogrenci Cagir',
                                  style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    Expanded(
                                      child: TextField(
                                        controller: _studentNameController,
                                        decoration: const InputDecoration(
                                          hintText: 'Ogrenci adi (orn: Ali Yilmaz)',
                                          border: OutlineInputBorder(),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 16),
                                    ElevatedButton.icon(
                                      onPressed: _isConnected ? _callStudent : null,
                                      icon: const Icon(Icons.person_search),
                                      label: const Text('Cagir'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.blue,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 24, vertical: 16),
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),

                        const SizedBox(height: 24),

                        // Gelen mesajlar (yonetimden)
                        if (_receivedMessages.isNotEmpty) ...[
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              const Text(
                                'Gelen Mesajlar',
                                style: TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                  color: Colors.indigo,
                                ),
                              ),
                              TextButton.icon(
                                onPressed: () {
                                  setState(() => _receivedMessages.clear());
                                },
                                icon: const Icon(Icons.delete_sweep, size: 20),
                                label: const Text('Temizle'),
                                style: TextButton.styleFrom(
                                  foregroundColor: Colors.red,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          SizedBox(
                            height: 100,
                            child: Card(
                              elevation: 1,
                              color: Colors.indigo[50],
                              child: ListView.builder(
                                padding: const EdgeInsets.all(8),
                                itemCount: _receivedMessages.length,
                                itemBuilder: (context, index) {
                                  final msg = _receivedMessages[index];
                                  return ListTile(
                                    dense: true,
                                    leading: const Icon(Icons.message, size: 20),
                                    title: Text(msg.content, maxLines: 1, overflow: TextOverflow.ellipsis),
                                    subtitle: Text(
                                      '${msg.senderName} - ${DateFormat('HH:mm').format(msg.timestamp)}',
                                      style: const TextStyle(fontSize: 11),
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                          const SizedBox(height: 16),
                        ],

                        // Gonderilen mesajlar
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'Gonderilen Mesajlar',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            if (_sentMessages.isNotEmpty)
                              TextButton.icon(
                                onPressed: () {
                                  setState(() => _sentMessages.clear());
                                },
                                icon: const Icon(Icons.delete_sweep, size: 20),
                                label: const Text('Temizle'),
                                style: TextButton.styleFrom(
                                  foregroundColor: Colors.red,
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Expanded(
                          child: Card(
                            elevation: 1,
                            child: _sentMessages.isEmpty
                                ? const Center(
                                    child: Text(
                                      'Henuz mesaj gonderilmedi',
                                      style: TextStyle(color: Colors.grey),
                                    ),
                                  )
                                : ListView.builder(
                                    padding: const EdgeInsets.all(8),
                                    itemCount: _sentMessages.length,
                                    itemBuilder: (context, index) {
                                      final msg = _sentMessages[index];
                                      return ListTile(
                                        dense: true,
                                        leading: Icon(
                                          _getMessageIcon(msg.type),
                                          color: _getMessageColor(msg.type),
                                          size: 20,
                                        ),
                                        title: Text(
                                          msg.type == MessageType.call
                                              ? '${msg.content} cagrildi'
                                              : msg.content,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                        subtitle: Text(
                                          '${msg.targetRooms.isEmpty ? "Tum Siniflar" : msg.targetRooms.join(", ")} - ${DateFormat('HH:mm').format(msg.timestamp)}',
                                          style: const TextStyle(fontSize: 11),
                                        ),
                                      );
                                    },
                                  ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
    );
  }

  IconData _getMessageIcon(MessageType type) {
    switch (type) {
      case MessageType.text:
        return Icons.message;
      case MessageType.alert:
        return Icons.warning;
      case MessageType.call:
        return Icons.person_search;
      case MessageType.file:
        return Icons.attach_file;
      case MessageType.shutdown:
        return Icons.power_settings_new;
    }
  }

  Color _getMessageColor(MessageType type) {
    switch (type) {
      case MessageType.text:
        return Colors.deepPurple;
      case MessageType.alert:
        return Colors.orange;
      case MessageType.call:
        return Colors.blue;
      case MessageType.file:
        return Colors.teal;
      case MessageType.shutdown:
        return Colors.red;
    }
  }
}
