/// Mesaj türleri
enum MessageType {
  text,      // Normal metin mesajı
  file,      // Dosya transferi
  alert,     // Acil duyuru
  call,      // Öğrenci çağrısı (örn: "8A'dan Ali gelsin")
  shutdown,  // PC kapatma komutu
}

/// Mesaj önceliği
enum MessagePriority {
  normal,
  urgent,
}

/// Mesaj modeli
class Message {
  final String id;
  final String senderId;
  final String senderName;
  final List<String> targetRooms; // Hedef odalar (boş = tüm odalar)
  final MessageType type;
  final MessagePriority priority;
  final String content;
  final String? fileName;
  final int? fileSize;
  final List<int>? fileData;
  final DateTime timestamp;
  final bool isRead;

  Message({
    required this.id,
    required this.senderId,
    required this.senderName,
    required this.targetRooms,
    required this.type,
    this.priority = MessagePriority.normal,
    required this.content,
    this.fileName,
    this.fileSize,
    this.fileData,
    DateTime? timestamp,
    this.isRead = false,
  }) : timestamp = timestamp ?? DateTime.now();

  /// JSON'a dönüştür
  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'senderId': senderId,
      'senderName': senderName,
      'targetRooms': targetRooms,
      'type': type.index,
      'priority': priority.index,
      'content': content,
      'fileName': fileName,
      'fileSize': fileSize,
      'fileData': fileData,
      'timestamp': timestamp.toIso8601String(),
      'isRead': isRead,
    };
  }

  /// JSON'dan oluştur
  factory Message.fromJson(Map<String, dynamic> json) {
    return Message(
      id: json['id'] as String,
      senderId: json['senderId'] as String,
      senderName: json['senderName'] as String,
      targetRooms: List<String>.from(json['targetRooms'] as List),
      type: MessageType.values[json['type'] as int],
      priority: MessagePriority.values[json['priority'] as int],
      content: json['content'] as String,
      fileName: json['fileName'] as String?,
      fileSize: json['fileSize'] as int?,
      fileData: json['fileData'] != null
          ? List<int>.from(json['fileData'] as List)
          : null,
      timestamp: DateTime.parse(json['timestamp'] as String),
      isRead: json['isRead'] as bool? ?? false,
    );
  }

  /// Okundu olarak işaretle
  Message markAsRead() {
    return Message(
      id: id,
      senderId: senderId,
      senderName: senderName,
      targetRooms: targetRooms,
      type: type,
      priority: priority,
      content: content,
      fileName: fileName,
      fileSize: fileSize,
      fileData: fileData,
      timestamp: timestamp,
      isRead: true,
    );
  }
}
