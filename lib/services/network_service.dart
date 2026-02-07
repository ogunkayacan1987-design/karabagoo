import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../models/message.dart';
import '../models/room.dart';

/// Ağ olayları için callback türleri
typedef MessageCallback = void Function(Message message);
typedef ConnectionCallback = void Function(String roomId, bool isConnected);
typedef ErrorCallback = void Function(String error);
typedef RoomStatusCallback = void Function(List<Map<String, dynamic>> rooms);

/// Sunucu servisi - Admin tarafında çalışır
class ServerService {
  ServerSocket? _server;
  final Map<String, Socket> _connectedClients = {};
  final Map<String, Room> _rooms = {};
  final Set<String> _teacherClients = {};

  MessageCallback? onMessageReceived;
  ConnectionCallback? onClientConnectionChanged;
  ErrorCallback? onError;

  bool get isRunning => _server != null;
  List<Room> get connectedRooms => _rooms.values.where((r) => r.isOnline).toList();

  /// Sunucuyu başlat
  Future<bool> startServer({int port = 5555}) async {
    try {
      _server = await ServerSocket.bind(InternetAddress.anyIPv4, port);
      print('Sunucu başlatıldı: ${_server!.address.address}:${_server!.port}');

      _server!.listen(
        _handleClientConnection,
        onError: (error) {
          onError?.call('Sunucu hatası: $error');
        },
        onDone: () {
          print('Sunucu kapatıldı');
        },
      );

      return true;
    } catch (e) {
      onError?.call('Sunucu başlatılamadı: $e');
      return false;
    }
  }

  /// Sunucuyu durdur
  Future<void> stopServer() async {
    for (var socket in _connectedClients.values) {
      await socket.close();
    }
    _connectedClients.clear();
    _rooms.clear();
    _teacherClients.clear();
    await _server?.close();
    _server = null;
  }

  /// İstemci bağlantısını yönet
  void _handleClientConnection(Socket client) {
    final clientAddress = '${client.remoteAddress.address}:${client.remotePort}';
    print('Yeni bağlantı: $clientAddress');

    String? roomId;
    final buffer = StringBuffer();

    client.listen(
      (Uint8List data) {
        try {
          buffer.write(utf8.decode(data));

          // Tam mesajları işle (newline ile ayrılmış)
          while (buffer.toString().contains('\n')) {
            final content = buffer.toString();
            final index = content.indexOf('\n');
            final jsonStr = content.substring(0, index);
            buffer.clear();
            buffer.write(content.substring(index + 1));

            final json = jsonDecode(jsonStr) as Map<String, dynamic>;

            // Kayıt mesajı
            if (json['type'] == 'register') {
              roomId = json['roomId'] as String;
              final roomName = json['roomName'] as String;
              final roomType = RoomType.values[json['roomType'] as int];
              final role = json['role'] as String? ?? 'client';

              _connectedClients[roomId!] = client;
              _rooms[roomId!] = Room(
                id: roomId!,
                name: roomName,
                type: roomType,
                ipAddress: client.remoteAddress.address,
                isOnline: true,
                lastSeen: DateTime.now(),
              );

              // Öğretmen ise kaydet
              if (role == 'teacher') {
                _teacherClients.add(roomId!);
              }

              print('Oda kaydedildi: $roomName ($roomId) [role: $role]');
              onClientConnectionChanged?.call(roomId!, true);

              // Kayıt onayı gönder
              _sendToClient(roomId!, {'type': 'registered', 'success': true});

              // Öğretmene oda durumlarını gönder
              if (role == 'teacher') {
                _sendRoomStatusToTeachers();
              }
            }
            // Mesaj alındı bildirimi
            else if (json['type'] == 'message_received') {
              final messageId = json['messageId'] as String;
              print('Mesaj alındı onayı: $messageId');
            }
            // Öğretmenden gelen mesaj relay
            else if (json['type'] == 'relay_message') {
              final message = Message.fromJson(json['data'] as Map<String, dynamic>);

              // Hedef odalara ilet
              if (message.targetRooms.isEmpty) {
                // Tüm odalara gönder (öğretmen hariç)
                for (var cRoomId in _connectedClients.keys) {
                  if (cRoomId != roomId) {
                    _sendToClient(cRoomId, {
                      'type': 'message',
                      'data': message.toJson(),
                    });
                  }
                }
              } else {
                for (var targetRoom in message.targetRooms) {
                  if (_connectedClients.containsKey(targetRoom) && targetRoom != roomId) {
                    _sendToClient(targetRoom, {
                      'type': 'message',
                      'data': message.toJson(),
                    });
                  }
                }
              }

              // Admin paneline bildir
              onMessageReceived?.call(message);
              print('Öğretmen mesajı relay edildi: ${message.content}');
            }
            // Normal mesaj (istemciden)
            else if (json['type'] == 'message') {
              final message = Message.fromJson(json['data'] as Map<String, dynamic>);
              onMessageReceived?.call(message);
            }
          }
        } catch (e) {
          print('Veri işleme hatası: $e');
        }
      },
      onError: (error) {
        print('İstemci hatası ($clientAddress): $error');
        _handleClientDisconnection(roomId);
      },
      onDone: () {
        print('İstemci bağlantısı kapandı: $clientAddress');
        _handleClientDisconnection(roomId);
      },
    );
  }

  /// İstemci bağlantı kopması
  void _handleClientDisconnection(String? roomId) {
    if (roomId != null) {
      _connectedClients.remove(roomId);
      _teacherClients.remove(roomId);
      if (_rooms.containsKey(roomId)) {
        _rooms[roomId] = _rooms[roomId]!.copyWith(
          isOnline: false,
          lastSeen: DateTime.now(),
        );
      }
      onClientConnectionChanged?.call(roomId, false);

      // Öğretmenlere güncel oda durumunu gönder
      _sendRoomStatusToTeachers();
    }
  }

  /// Öğretmenlere oda durumu gönder
  void _sendRoomStatusToTeachers() {
    final roomStatusList = _rooms.values
        .where((r) => !_teacherClients.contains(r.id))
        .map((r) => {
              'id': r.id,
              'name': r.name,
              'type': r.type.index,
              'isOnline': r.isOnline,
            })
        .toList();

    // Varsayılan odaları da ekle (bağlı olmayanlar)
    final defaultRooms = Room.getDefaultRooms()
        .where((r) => r.type == RoomType.classroom)
        .toList();
    for (var room in defaultRooms) {
      if (!roomStatusList.any((r) => r['id'] == room.id)) {
        roomStatusList.add({
          'id': room.id,
          'name': room.name,
          'type': room.type.index,
          'isOnline': false,
        });
      }
    }

    final data = {
      'type': 'room_status',
      'rooms': roomStatusList,
    };

    for (var teacherId in _teacherClients) {
      _sendToClient(teacherId, data);
    }
  }

  /// Belirli bir istemciye veri gönder
  void _sendToClient(String roomId, Map<String, dynamic> data) {
    final client = _connectedClients[roomId];
    if (client != null) {
      try {
        client.write('${jsonEncode(data)}\n');
      } catch (e) {
        print('Gönderim hatası ($roomId): $e');
      }
    }
  }

  /// Mesaj gönder
  void sendMessage(Message message) {
    final data = {
      'type': 'message',
      'data': message.toJson(),
    };

    if (message.targetRooms.isEmpty) {
      // Tüm bağlı istemcilere gönder
      for (var roomId in _connectedClients.keys) {
        _sendToClient(roomId, data);
      }
      print('Mesaj tüm odalara gönderildi');
    } else {
      // Sadece hedef odalara gönder
      for (var roomId in message.targetRooms) {
        if (_connectedClients.containsKey(roomId)) {
          _sendToClient(roomId, data);
          print('Mesaj gönderildi: $roomId');
        }
      }
    }
  }

  /// Uzaktan PC kapatma komutu gönder
  void sendShutdownCommand(List<String> targetRooms) {
    final data = {'type': 'shutdown'};

    if (targetRooms.isEmpty) {
      // Tüm istemcilere gönder (öğretmenler dahil)
      for (var roomId in _connectedClients.keys) {
        _sendToClient(roomId, data);
      }
      print('Kapatma komutu tüm odalara gönderildi');
    } else {
      for (var roomId in targetRooms) {
        if (_connectedClients.containsKey(roomId)) {
          _sendToClient(roomId, data);
          print('Kapatma komutu gönderildi: $roomId');
        }
      }
    }
  }

  /// Sunucunun IP adresini al
  Future<String?> getLocalIpAddress() async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
        includeLinkLocal: false,
      );

      for (var interface in interfaces) {
        for (var addr in interface.addresses) {
          if (!addr.isLoopback && addr.address.startsWith('192.168.')) {
            return addr.address;
          }
        }
      }

      // 192.168 bulunamazsa ilk uygun adresi döndür
      for (var interface in interfaces) {
        for (var addr in interface.addresses) {
          if (!addr.isLoopback) {
            return addr.address;
          }
        }
      }
    } catch (e) {
      print('IP adresi alınamadı: $e');
    }
    return null;
  }
}

/// İstemci servisi - Sınıflarda ve öğretmen odasında çalışır
class ClientService {
  Socket? _socket;
  Room? _room;
  Timer? _reconnectTimer;
  String? _serverAddress;
  int _serverPort = 5555;

  MessageCallback? onMessageReceived;
  ConnectionCallback? onConnectionChanged;
  ErrorCallback? onError;
  RoomStatusCallback? onRoomStatusChanged;
  VoidCallback? onShutdownReceived;

  bool get isConnected => _socket != null;

  /// Sunucuya bağlan
  Future<bool> connect(String serverAddress, int port, Room room, {String role = 'client'}) async {
    _serverAddress = serverAddress;
    _serverPort = port;
    _room = room;

    try {
      _socket = await Socket.connect(
        serverAddress,
        port,
        timeout: const Duration(seconds: 5),
      );

      print('Sunucuya bağlanıldı: $serverAddress:$port');

      final buffer = StringBuffer();

      _socket!.listen(
        (Uint8List data) {
          try {
            buffer.write(utf8.decode(data));

            while (buffer.toString().contains('\n')) {
              final content = buffer.toString();
              final index = content.indexOf('\n');
              final jsonStr = content.substring(0, index);
              buffer.clear();
              buffer.write(content.substring(index + 1));

              final json = jsonDecode(jsonStr) as Map<String, dynamic>;

              if (json['type'] == 'registered') {
                print('Kayıt onaylandı');
                onConnectionChanged?.call(room.id, true);
              } else if (json['type'] == 'message') {
                final message = Message.fromJson(json['data'] as Map<String, dynamic>);
                onMessageReceived?.call(message);

                // Mesaj alındı bildirimi gönder
                _send({
                  'type': 'message_received',
                  'messageId': message.id,
                });
              } else if (json['type'] == 'room_status') {
                final rooms = (json['rooms'] as List)
                    .map((r) => Map<String, dynamic>.from(r as Map))
                    .toList();
                onRoomStatusChanged?.call(rooms);
              } else if (json['type'] == 'shutdown') {
                print('Kapatma komutu alındı!');
                onShutdownReceived?.call();
              }
            }
          } catch (e) {
            print('Veri işleme hatası: $e');
          }
        },
        onError: (error) {
          print('Bağlantı hatası: $error');
          _handleDisconnection();
        },
        onDone: () {
          print('Bağlantı kapandı');
          _handleDisconnection();
        },
      );

      // Odayı kaydet
      _send({
        'type': 'register',
        'roomId': room.id,
        'roomName': room.name,
        'roomType': room.type.index,
        'role': role,
      });

      return true;
    } catch (e) {
      onError?.call('Bağlantı hatası: $e');
      _scheduleReconnect(role);
      return false;
    }
  }

  /// Bağlantı kopmasını yönet
  void _handleDisconnection() {
    _socket?.destroy();
    _socket = null;
    onConnectionChanged?.call(_room?.id ?? '', false);
    _scheduleReconnect('client');
  }

  /// Yeniden bağlanmayı planla
  void _scheduleReconnect([String role = 'client']) {
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(const Duration(seconds: 5), () {
      if (_serverAddress != null && _room != null) {
        print('Yeniden bağlanılıyor...');
        connect(_serverAddress!, _serverPort, _room!, role: role);
      }
    });
  }

  /// Bağlantıyı kes
  Future<void> disconnect() async {
    _reconnectTimer?.cancel();
    await _socket?.close();
    _socket = null;
  }

  /// Veri gönder
  void _send(Map<String, dynamic> data) {
    if (_socket != null) {
      try {
        _socket!.write('${jsonEncode(data)}\n');
      } catch (e) {
        print('Gönderim hatası: $e');
      }
    }
  }

  /// Mesaj gönder (istemciden sunucuya)
  void sendMessage(Message message) {
    _send({
      'type': 'message',
      'data': message.toJson(),
    });
  }

  /// Mesaj relay et (öğretmenden sunucu üzerinden tüm hedeflere)
  void sendRelayMessage(Message message) {
    _send({
      'type': 'relay_message',
      'data': message.toJson(),
    });
  }
}
