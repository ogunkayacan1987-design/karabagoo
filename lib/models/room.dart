/// Oda türleri
enum RoomType {
  classroom,      // Sınıf
  teachersRoom,   // Öğretmenler odası
  principalRoom,  // Müdür odası
}

/// Oda/Lokasyon modeli
class Room {
  final String id;
  final String name;
  final RoomType type;
  final String? ipAddress;
  final bool isOnline;
  final DateTime? lastSeen;

  Room({
    required this.id,
    required this.name,
    required this.type,
    this.ipAddress,
    this.isOnline = false,
    this.lastSeen,
  });

  /// JSON'a dönüştür
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'type': type.index,
      'ipAddress': ipAddress,
      'isOnline': isOnline,
      'lastSeen': lastSeen?.toIso8601String(),
    };
  }

  /// JSON'dan oluştur
  factory Room.fromJson(Map<String, dynamic> json) {
    return Room(
      id: json['id'] as String,
      name: json['name'] as String,
      type: RoomType.values[json['type'] as int],
      ipAddress: json['ipAddress'] as String?,
      isOnline: json['isOnline'] as bool? ?? false,
      lastSeen: json['lastSeen'] != null
          ? DateTime.parse(json['lastSeen'] as String)
          : null,
    );
  }

  /// Bağlantı durumunu güncelle
  Room copyWith({
    String? id,
    String? name,
    RoomType? type,
    String? ipAddress,
    bool? isOnline,
    DateTime? lastSeen,
  }) {
    return Room(
      id: id ?? this.id,
      name: name ?? this.name,
      type: type ?? this.type,
      ipAddress: ipAddress ?? this.ipAddress,
      isOnline: isOnline ?? this.isOnline,
      lastSeen: lastSeen ?? this.lastSeen,
    );
  }

  /// Eşitlik kontrolü (DropdownButton için gerekli)
  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is Room && other.id == id;
  }

  @override
  int get hashCode => id.hashCode;

  /// Varsayılan okul odaları
  static List<Room> getDefaultRooms() {
    return [
      Room(id: '5a', name: '5-A Sınıfı', type: RoomType.classroom),
      Room(id: '5b', name: '5-B Sınıfı', type: RoomType.classroom),
      Room(id: '6a', name: '6-A Sınıfı', type: RoomType.classroom),
      Room(id: '6b', name: '6-B Sınıfı', type: RoomType.classroom),
      Room(id: '7a', name: '7-A Sınıfı', type: RoomType.classroom),
      Room(id: '7b', name: '7-B Sınıfı', type: RoomType.classroom),
      Room(id: '8a', name: '8-A Sınıfı', type: RoomType.classroom),
      Room(id: '8b', name: '8-B Sınıfı', type: RoomType.classroom),
      Room(id: 'ogretmenler', name: 'Öğretmenler Odası', type: RoomType.teachersRoom),
      Room(id: 'mudur', name: 'Müdür Odası', type: RoomType.principalRoom),
    ];
  }
}
