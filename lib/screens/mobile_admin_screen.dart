import 'dart:io';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:intl/intl.dart';

import '../models/message.dart';
import '../models/room.dart';
import '../services/network_service.dart';

/// Mobil Yonetim Paneli - Telefon icin optimize edilmis admin ekrani
class MobileAdminScreen extends StatefulWidget {
  const MobileAdminScreen({super.key});

  @override
  State<MobileAdminScreen> createState() => _MobileAdminScreenState();
}

class _MobileAdminScreenState extends State<MobileAdminScreen> {
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
  int _currentTab = 0;

  @override
  void initState() {
    super.initState();
    _loadSettings();
    _setupClient();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final savedIp = prefs.getString('mobile_admin_server_ip');

    if (savedIp != null) {
      _ipController.text = savedIp;
      setState(() => _showSetup = false);
      _connect();
    }
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('mobile_admin_server_ip', _ipController.text);
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
        duration: const Duration(seconds: 2),
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
      id: 'mudur_mobil',
      name: 'Mudur (Mobil)',
      type: RoomType.principalRoom,
    );

    await _client.connect(
      _ipController.text.trim(),
      5555,
      room,
      role: 'mobile_admin',
    );
  }

  void _sendTextMessage() {
    if (_messageController.text.trim().isEmpty) return;

    final message = Message(
      id: _generateMessageId(),
      senderId: 'mudur_mobil',
      senderName: 'Yonetim (Mobil)',
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
      senderId: 'mudur_mobil',
      senderName: 'Yonetim (Mobil)',
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

    final message = Message(
      id: _generateMessageId(),
      senderId: 'mudur_mobil',
      senderName: 'Yonetim (Mobil)',
      targetRooms: _sendToAll ? [] : _selectedRooms.toList(),
      type: MessageType.call,
      content: _studentNameController.text.trim(),
    );

    _client.sendRelayMessage(message);
    setState(() {
      _sentMessages.insert(0, message);
    });

    final targetText = _sendToAll
        ? 'tum siniflardan'
        : '${_selectedRooms.join(', ')} sinifindan';
    _showSnackBar('${message.content} $targetText cagrildi', Colors.blue);
    _studentNameController.clear();
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
        senderId: 'mudur_mobil',
        senderName: 'Yonetim (Mobil)',
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

  void _sendShutdownCommand({String? roomId}) {
    final targetText = roomId ?? 'TUM BILGISAYARLAR';
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Icon(Icons.power_settings_new, color: Colors.red[700]),
            const SizedBox(width: 8),
            const Flexible(child: Text('PC Kapatma')),
          ],
        ),
        content: Text(
          '$targetText kapatilacak.\nEmin misiniz?',
          style: const TextStyle(fontSize: 16),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Iptal'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.of(context).pop();
              if (roomId != null) {
                _client.sendRelayShutdown([roomId]);
                _showSnackBar('$roomId kapatma komutu gonderildi', Colors.red);
              } else {
                _client.sendRelayShutdown([]);
                _showSnackBar('Tum bilgisayarlara kapatma komutu gonderildi', Colors.red);
              }
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.red,
              foregroundColor: Colors.white,
            ),
            child: const Text('Kapat'),
          ),
        ],
      ),
    );
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
    if (_showSetup) return _buildSetupScreen();
    return _buildMainScreen();
  }

  // ==================== KURULUM EKRANI ====================
  Widget _buildSetupScreen() {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Okul Muduru - Baglanti'),
        backgroundColor: Colors.indigo,
        foregroundColor: Colors.white,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 32),
            Center(
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: Colors.indigo[50],
                  shape: BoxShape.circle,
                ),
                child: Icon(Icons.admin_panel_settings, size: 64, color: Colors.indigo[700]),
              ),
            ),
            const SizedBox(height: 24),
            const Center(
              child: Text(
                'Okul Muduru\nMobil Yonetim Paneli',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 8),
            Center(
              child: Text(
                'Yonetim bilgisayarinin IP adresini girin',
                style: TextStyle(color: Colors.grey[600], fontSize: 14),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _ipController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Sunucu IP Adresi',
                hintText: '192.168.1.100',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.computer),
              ),
              style: const TextStyle(fontSize: 20),
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              height: 56,
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
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    : const Icon(Icons.link),
                label: Text(
                  _isConnecting ? 'Baglaniliyor...' : 'Baglan',
                  style: const TextStyle(fontSize: 18),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.indigo,
                  foregroundColor: Colors.white,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ==================== ANA EKRAN ====================
  Widget _buildMainScreen() {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Okul Muduru'),
        backgroundColor: Colors.indigo,
        foregroundColor: Colors.white,
        actions: [
          // Baglanti durumu
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
              color: _isConnected ? Colors.green : Colors.red,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  _isConnected ? Icons.wifi : Icons.wifi_off,
                  size: 14,
                  color: Colors.white,
                ),
                const SizedBox(width: 4),
                Text(
                  _isConnected ? 'Bagli' : 'Yok',
                  style: const TextStyle(color: Colors.white, fontSize: 12),
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
          : IndexedStack(
              index: _currentTab,
              children: [
                _buildMessagesTab(),
                _buildCallTab(),
                _buildRoomsTab(),
                _buildHistoryTab(),
              ],
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _currentTab,
        onDestinationSelected: (index) => setState(() => _currentTab = index),
        destinations: [
          NavigationDestination(
            icon: const Icon(Icons.message_outlined),
            selectedIcon: const Icon(Icons.message),
            label: 'Mesaj',
          ),
          NavigationDestination(
            icon: const Icon(Icons.person_search_outlined),
            selectedIcon: const Icon(Icons.person_search),
            label: 'Cagri',
          ),
          NavigationDestination(
            icon: Badge(
              label: Text('${_roomStatuses.where((r) => r['isOnline'] == true).length}'),
              child: const Icon(Icons.meeting_room_outlined),
            ),
            selectedIcon: const Icon(Icons.meeting_room),
            label: 'Siniflar',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: _receivedMessages.isNotEmpty,
              label: Text('${_receivedMessages.length}'),
              child: const Icon(Icons.history_outlined),
            ),
            selectedIcon: const Icon(Icons.history),
            label: 'Gecmis',
          ),
        ],
      ),
    );
  }

  // ==================== MESAJ TAB ====================
  Widget _buildMessagesTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Hedef secimi
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Hedef', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                  SwitchListTile(
                    title: const Text('Tum Siniflara Gonder'),
                    value: _sendToAll,
                    onChanged: (val) => setState(() {
                      _sendToAll = val;
                      if (val) _selectedRooms.clear();
                    }),
                    activeColor: Colors.indigo,
                    contentPadding: EdgeInsets.zero,
                  ),
                  if (!_sendToAll) _buildRoomChips(),
                ],
              ),
            ),
          ),

          const SizedBox(height: 16),

          // Mesaj yazma
          TextField(
            controller: _messageController,
            maxLines: 4,
            decoration: const InputDecoration(
              hintText: 'Mesajinizi yazin...',
              border: OutlineInputBorder(),
            ),
            style: const TextStyle(fontSize: 16),
          ),
          const SizedBox(height: 12),

          // Butonlar
          Row(
            children: [
              Expanded(
                child: SizedBox(
                  height: 48,
                  child: ElevatedButton.icon(
                    onPressed: _isConnected ? _sendTextMessage : null,
                    icon: const Icon(Icons.send),
                    label: const Text('Gonder'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.indigo,
                      foregroundColor: Colors.white,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: SizedBox(
                  height: 48,
                  child: ElevatedButton.icon(
                    onPressed: _isConnected ? _sendAlert : null,
                    icon: const Icon(Icons.warning),
                    label: const Text('Acil'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.orange,
                      foregroundColor: Colors.white,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton.icon(
              onPressed: _isConnected ? _sendFile : null,
              icon: const Icon(Icons.attach_file),
              label: const Text('Dosya Gonder'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.teal,
                foregroundColor: Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRoomChips() {
    final rooms = _roomStatuses.isNotEmpty
        ? _roomStatuses
        : Room.getDefaultRooms()
            .where((r) => r.type == RoomType.classroom)
            .map((r) => {'id': r.id, 'name': r.name, 'isOnline': false})
            .toList();

    return Wrap(
      spacing: 6,
      runSpacing: 4,
      children: rooms.map((r) {
        final id = r['id'] as String;
        final name = r['name'] as String;
        final isOnline = r['isOnline'] as bool;
        final isSelected = _selectedRooms.contains(id);

        return FilterChip(
          label: Text(
            name,
            style: TextStyle(fontSize: 12, color: isSelected ? Colors.white : null),
          ),
          selected: isSelected,
          onSelected: (val) {
            setState(() {
              if (val) {
                _selectedRooms.add(id);
              } else {
                _selectedRooms.remove(id);
              }
            });
          },
          avatar: Icon(
            Icons.circle,
            size: 10,
            color: isOnline ? Colors.green : Colors.grey,
          ),
          selectedColor: Colors.indigo,
          checkmarkColor: Colors.white,
        );
      }).toList(),
    );
  }

  // ==================== CAGRI TAB ====================
  Widget _buildCallTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 16),
          Center(
            child: Icon(Icons.person_search, size: 64, color: Colors.blue[300]),
          ),
          const SizedBox(height: 16),
          const Center(
            child: Text(
              'Ogrenci Cagir',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
            ),
          ),
          const SizedBox(height: 24),

          // Hedef secimi
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Hedef Sinif', style: TextStyle(fontWeight: FontWeight.bold)),
                  SwitchListTile(
                    title: const Text('Tum Siniflara'),
                    value: _sendToAll,
                    onChanged: (val) => setState(() {
                      _sendToAll = val;
                      if (val) _selectedRooms.clear();
                    }),
                    activeColor: Colors.indigo,
                    contentPadding: EdgeInsets.zero,
                  ),
                  if (!_sendToAll) _buildRoomChips(),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          TextField(
            controller: _studentNameController,
            decoration: const InputDecoration(
              labelText: 'Ogrenci Adi',
              hintText: 'Orn: Ali Yilmaz',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.person),
            ),
            style: const TextStyle(fontSize: 18),
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            height: 56,
            child: ElevatedButton.icon(
              onPressed: _isConnected ? _callStudent : null,
              icon: const Icon(Icons.campaign, size: 28),
              label: const Text('Ogrenciyi Cagir', style: TextStyle(fontSize: 18)),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.blue,
                foregroundColor: Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ==================== SINIFLAR TAB ====================
  Widget _buildRoomsTab() {
    final rooms = _roomStatuses.isNotEmpty
        ? _roomStatuses
        : Room.getDefaultRooms()
            .where((r) => r.type == RoomType.classroom)
            .map((r) => {'id': r.id, 'name': r.name, 'type': r.type.index, 'isOnline': false})
            .toList();

    final onlineCount = rooms.where((r) => r['isOnline'] == true).length;

    return Column(
      children: [
        // Ozet bari
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          color: Colors.indigo[50],
          child: Row(
            children: [
              Icon(Icons.people, color: Colors.indigo[700]),
              const SizedBox(width: 8),
              Text(
                'Cevrimici: $onlineCount / ${rooms.length}',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: Colors.indigo[700],
                  fontSize: 16,
                ),
              ),
              const Spacer(),
              if (onlineCount > 0)
                TextButton.icon(
                  onPressed: () => _sendShutdownCommand(),
                  icon: Icon(Icons.power_settings_new, color: Colors.red[700], size: 18),
                  label: Text('Tumunu Kapat', style: TextStyle(color: Colors.red[700], fontSize: 12)),
                ),
            ],
          ),
        ),
        // Oda listesi
        Expanded(
          child: ListView.builder(
            itemCount: rooms.length,
            itemBuilder: (context, index) {
              final room = rooms[index];
              final roomId = room['id'] as String;
              final roomName = room['name'] as String;
              final isOnline = room['isOnline'] as bool;

              return ListTile(
                leading: CircleAvatar(
                  backgroundColor: isOnline ? Colors.green[100] : Colors.grey[200],
                  child: Icon(
                    Icons.meeting_room,
                    color: isOnline ? Colors.green[700] : Colors.grey,
                  ),
                ),
                title: Text(roomName),
                subtitle: Text(
                  isOnline ? 'Cevrimici' : 'Cevrimdisi',
                  style: TextStyle(
                    color: isOnline ? Colors.green : Colors.grey,
                    fontSize: 12,
                  ),
                ),
                trailing: isOnline
                    ? IconButton(
                        icon: Icon(Icons.power_settings_new, color: Colors.red[400]),
                        onPressed: () => _sendShutdownCommand(roomId: roomId),
                        tooltip: 'Kapat',
                      )
                    : null,
              );
            },
          ),
        ),
      ],
    );
  }

  // ==================== GECMIS TAB ====================
  Widget _buildHistoryTab() {
    final allMessages = [
      ..._receivedMessages.map((m) => _MessageEntry(m, true)),
      ..._sentMessages.map((m) => _MessageEntry(m, false)),
    ]..sort((a, b) => b.message.timestamp.compareTo(a.message.timestamp));

    return Column(
      children: [
        if (allMessages.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${allMessages.length} mesaj',
                  style: TextStyle(color: Colors.grey[600]),
                ),
                TextButton.icon(
                  onPressed: () {
                    setState(() {
                      _sentMessages.clear();
                      _receivedMessages.clear();
                    });
                  },
                  icon: const Icon(Icons.delete_sweep, size: 18),
                  label: const Text('Temizle'),
                  style: TextButton.styleFrom(foregroundColor: Colors.red),
                ),
              ],
            ),
          ),
        Expanded(
          child: allMessages.isEmpty
              ? const Center(
                  child: Text('Henuz mesaj yok', style: TextStyle(color: Colors.grey)),
                )
              : ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  itemCount: allMessages.length,
                  itemBuilder: (context, index) {
                    final entry = allMessages[index];
                    final msg = entry.message;
                    final isReceived = entry.isReceived;

                    return Card(
                      color: isReceived ? Colors.blue[50] : Colors.grey[50],
                      margin: const EdgeInsets.symmetric(vertical: 4),
                      child: ListTile(
                        dense: true,
                        leading: Icon(
                          _getIcon(msg.type),
                          color: _getColor(msg.type),
                          size: 24,
                        ),
                        title: Text(
                          msg.type == MessageType.call ? '${msg.content} cagrildi' : msg.content,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 14),
                        ),
                        subtitle: Text(
                          '${isReceived ? "Gelen" : "Giden"} - ${msg.senderName} - ${DateFormat('HH:mm').format(msg.timestamp)}',
                          style: const TextStyle(fontSize: 11),
                        ),
                        trailing: isReceived
                            ? const Icon(Icons.arrow_downward, size: 16, color: Colors.blue)
                            : const Icon(Icons.arrow_upward, size: 16, color: Colors.green),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }

  IconData _getIcon(MessageType type) {
    switch (type) {
      case MessageType.text: return Icons.message;
      case MessageType.alert: return Icons.warning;
      case MessageType.call: return Icons.person_search;
      case MessageType.file: return Icons.attach_file;
      case MessageType.shutdown: return Icons.power_settings_new;
    }
  }

  Color _getColor(MessageType type) {
    switch (type) {
      case MessageType.text: return Colors.indigo;
      case MessageType.alert: return Colors.orange;
      case MessageType.call: return Colors.blue;
      case MessageType.file: return Colors.teal;
      case MessageType.shutdown: return Colors.red;
    }
  }
}

class _MessageEntry {
  final Message message;
  final bool isReceived;
  _MessageEntry(this.message, this.isReceived);
}
