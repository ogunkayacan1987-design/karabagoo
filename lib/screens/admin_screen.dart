import 'dart:io';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:intl/intl.dart';

import '../models/message.dart';
import '../models/room.dart';
import '../services/network_service.dart';

class AdminScreen extends StatefulWidget {
  const AdminScreen({super.key});

  @override
  State<AdminScreen> createState() => _AdminScreenState();
}

class _AdminScreenState extends State<AdminScreen> {
  final ServerService _server = ServerService();
  final TextEditingController _messageController = TextEditingController();
  final TextEditingController _studentNameController = TextEditingController();
  final List<Message> _sentMessages = [];
  final Set<String> _selectedRooms = {};

  String? _localIp;
  bool _isLoading = false;
  bool _sendToAll = true;

  @override
  void initState() {
    super.initState();
    _initServer();
  }

  Future<void> _initServer() async {
    setState(() => _isLoading = true);

    _server.onClientConnectionChanged = (roomId, isConnected) {
      setState(() {});
      if (isConnected) {
        _showSnackBar('$roomId bağlandı', Colors.green);
      } else {
        _showSnackBar('$roomId bağlantısı koptu', Colors.orange);
      }
    };

    _server.onMessageReceived = (message) {
      setState(() {});
    };

    _server.onError = (error) {
      _showSnackBar(error, Colors.red);
    };

    _localIp = await _server.getLocalIpAddress();
    final success = await _server.startServer();

    setState(() => _isLoading = false);

    if (success) {
      _showSnackBar('Sunucu başlatıldı: $_localIp:5555', Colors.green);
    }
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

  void _sendTextMessage() {
    if (_messageController.text.trim().isEmpty) return;

    final message = Message(
      id: _generateMessageId(),
      senderId: 'admin',
      senderName: 'Yönetim',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.text,
      content: _messageController.text.trim(),
    );

    _server.sendMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _messageController.clear();
    _showSnackBar('Mesaj gönderildi', Colors.green);
  }

  void _sendAlert() {
    if (_messageController.text.trim().isEmpty) return;

    final message = Message(
      id: _generateMessageId(),
      senderId: 'admin',
      senderName: 'Yönetim',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.alert,
      priority: MessagePriority.urgent,
      content: _messageController.text.trim(),
    );

    _server.sendMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _messageController.clear();
    _showSnackBar('Acil duyuru gönderildi!', Colors.orange);
  }

  void _callStudent() {
    if (_studentNameController.text.trim().isEmpty) return;
    if (_selectedRooms.isEmpty && !_sendToAll) {
      _showSnackBar('Lütfen bir sınıf seçin', Colors.red);
      return;
    }

    final message = Message(
      id: _generateMessageId(),
      senderId: 'admin',
      senderName: 'Yönetim',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.call,
      content: _studentNameController.text.trim(),
    );

    _server.sendMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });
    _studentNameController.clear();

    final targetText = _sendToAll
        ? 'tüm sınıflardan'
        : _selectedRooms.join(', ') + ' sınıfından';
    _showSnackBar('${message.content} $targetText çağrıldı', Colors.blue);
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
        senderId: 'admin',
        senderName: 'Yönetim',
        targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
        type: MessageType.file,
        content: 'Dosya gönderildi: ${file.name}',
        fileName: file.name,
        fileSize: file.size,
        fileData: bytes,
      );

      _server.sendMessage(message);
      setState(() {
        _sentMessages.insert(0, message);
      });
      _showSnackBar('Dosya gönderildi: ${file.name}', Colors.green);
    } catch (e) {
      _showSnackBar('Dosya gönderilemedi: $e', Colors.red);
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  void dispose() {
    _server.stopServer();
    _messageController.dispose();
    _studentNameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final rooms = Room.getDefaultRooms()
        .where((r) => r.type == RoomType.classroom)
        .toList();
    final connectedRoomIds = _server.connectedRooms.map((r) => r.id).toSet();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Karabağ H.Ö.Akarsel Ortaokulu - Yönetim'),
        backgroundColor: Colors.indigo,
        foregroundColor: Colors.white,
        actions: [
          if (_localIp != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Center(
                child: Text(
                  'IP: $_localIp:5555',
                  style: const TextStyle(fontSize: 14),
                ),
              ),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : Row(
              children: [
                // Sol panel - Odalar
                Container(
                  width: 250,
                  color: Colors.grey[100],
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(16),
                        color: Colors.indigo[50],
                        child: Row(
                          children: [
                            Icon(Icons.people, color: Colors.indigo[700]),
                            const SizedBox(width: 8),
                            Text(
                              'Sınıflar',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                                color: Colors.indigo[700],
                              ),
                            ),
                          ],
                        ),
                      ),
                      CheckboxListTile(
                        title: const Text('Tüm Sınıflara Gönder'),
                        value: _sendToAll,
                        onChanged: (value) {
                          setState(() {
                            _sendToAll = value ?? true;
                            if (_sendToAll) _selectedRooms.clear();
                          });
                        },
                        activeColor: Colors.indigo,
                      ),
                      const Divider(),
                      Expanded(
                        child: ListView.builder(
                          itemCount: rooms.length,
                          itemBuilder: (context, index) {
                            final room = rooms[index];
                            final isOnline = connectedRoomIds.contains(room.id);
                            final isSelected = _selectedRooms.contains(room.id);

                            return ListTile(
                              leading: Icon(
                                Icons.meeting_room,
                                color: isOnline ? Colors.green : Colors.grey,
                              ),
                              title: Text(room.name),
                              subtitle: Text(
                                isOnline ? 'Çevrimiçi' : 'Çevrimdışı',
                                style: TextStyle(
                                  color: isOnline ? Colors.green : Colors.grey,
                                  fontSize: 12,
                                ),
                              ),
                              trailing: !_sendToAll
                                  ? Checkbox(
                                      value: isSelected,
                                      onChanged: isOnline
                                          ? (value) {
                                              setState(() {
                                                if (value == true) {
                                                  _selectedRooms.add(room.id);
                                                } else {
                                                  _selectedRooms.remove(room.id);
                                                }
                                              });
                                            }
                                          : null,
                                      activeColor: Colors.indigo,
                                    )
                                  : null,
                              selected: isSelected,
                              selectedTileColor: Colors.indigo[50],
                            );
                          },
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          'Bağlı: ${connectedRoomIds.length}/${rooms.length}',
                          style: TextStyle(
                            color: Colors.grey[600],
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                // Orta panel - Mesaj gönderme
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
                                  'Mesaj / Duyuru Gönder',
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
                                    hintText: 'Mesajınızı yazın...',
                                    border: OutlineInputBorder(),
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Row(
                                  children: [
                                    ElevatedButton.icon(
                                      onPressed: _sendTextMessage,
                                      icon: const Icon(Icons.send),
                                      label: const Text('Gönder'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.indigo,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 24,
                                          vertical: 12,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ElevatedButton.icon(
                                      onPressed: _sendAlert,
                                      icon: const Icon(Icons.warning),
                                      label: const Text('Acil Duyuru'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.orange,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 24,
                                          vertical: 12,
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 12),
                                    ElevatedButton.icon(
                                      onPressed: _sendFile,
                                      icon: const Icon(Icons.attach_file),
                                      label: const Text('Dosya Gönder'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.teal,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 24,
                                          vertical: 12,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),

                        const SizedBox(height: 24),

                        // Öğrenci çağırma
                        Card(
                          elevation: 2,
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text(
                                  'Öğrenci Çağır',
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
                                          hintText: 'Öğrenci adı (örn: Ali Yılmaz)',
                                          border: OutlineInputBorder(),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 16),
                                    ElevatedButton.icon(
                                      onPressed: _callStudent,
                                      icon: const Icon(Icons.person_search),
                                      label: const Text('Çağır'),
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.blue,
                                        foregroundColor: Colors.white,
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 24,
                                          vertical: 16,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),

                        const SizedBox(height: 24),

                        // Gönderilen mesajlar
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'Gönderilen Mesajlar',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            if (_sentMessages.isNotEmpty)
                              TextButton.icon(
                                onPressed: () {
                                  setState(() {
                                    _sentMessages.clear();
                                  });
                                  _showSnackBar('Mesajlar temizlendi', Colors.blue);
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
                                      'Henüz mesaj gönderilmedi',
                                      style: TextStyle(color: Colors.grey),
                                    ),
                                  )
                                : ListView.separated(
                                    padding: const EdgeInsets.all(8),
                                    itemCount: _sentMessages.length,
                                    separatorBuilder: (_, __) => const Divider(),
                                    itemBuilder: (context, index) {
                                      final msg = _sentMessages[index];
                                      return _buildMessageTile(msg);
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

  Widget _buildMessageTile(Message msg) {
    IconData icon;
    Color color;
    String typeText;

    switch (msg.type) {
      case MessageType.text:
        icon = Icons.message;
        color = Colors.indigo;
        typeText = 'Mesaj';
        break;
      case MessageType.alert:
        icon = Icons.warning;
        color = Colors.orange;
        typeText = 'Acil Duyuru';
        break;
      case MessageType.call:
        icon = Icons.person_search;
        color = Colors.blue;
        typeText = 'Öğrenci Çağrısı';
        break;
      case MessageType.file:
        icon = Icons.attach_file;
        color = Colors.teal;
        typeText = 'Dosya';
        break;
    }

    final targetText = msg.targetRooms.isEmpty
        ? 'Tüm Sınıflar'
        : msg.targetRooms.join(', ');

    return ListTile(
      leading: CircleAvatar(
        backgroundColor: color.withOpacity(0.2),
        child: Icon(icon, color: color, size: 20),
      ),
      title: Text(
        msg.type == MessageType.call
            ? '${msg.content} çağrıldı'
            : msg.content,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '$typeText - $targetText\n${DateFormat('HH:mm').format(msg.timestamp)}',
        style: const TextStyle(fontSize: 12),
      ),
      isThreeLine: true,
    );
  }
}
