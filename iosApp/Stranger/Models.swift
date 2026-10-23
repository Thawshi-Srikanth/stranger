import Foundation

struct ChatMessage: Identifiable, Equatable {
    let id: Int
    let sender: String?
    let timestamp: Int64
    let type: String?
    let content: String
    let textPlain: String?
    var replyToText: String?
    var replyToSender: String?
    let parsedContent: MessageContent

    init(
        id: Int,
        sender: String?,
        timestamp: Int64,
        type: String?,
        content: String,
        textPlain: String?,
        replyToText: String? = nil,
        replyToSender: String? = nil
    ) {
        self.id = id
        self.sender = sender
        self.timestamp = timestamp
        self.type = type
        self.content = content
        self.textPlain = textPlain
        self.replyToText = replyToText
        self.replyToSender = replyToSender
        if let data = content.data(using: .utf8),
           let decoded = try? JSONDecoder().decode(MessageContent.self, from: data) {
            self.parsedContent = decoded
        } else {
            self.parsedContent = MessageContent()
        }
    }

    var isSentByMe: Bool {
        sender != "Stranger"
    }

    var formattedTime: String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        return Self.timeFormatter.string(from: date)
    }

    var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        return Self.dateFormatter.string(from: date)
    }

    var formattedSearchDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
        return Self.searchDateFormatter.string(from: date)
    }

    var formattedDuration: String {
        let secs = parsedContent.duration_seconds ?? 0
        let mins = secs / 60
        let rem = secs % 60
        return String(format: "%02d:%02d", mins, rem)
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "hh:mm a"
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMMM d, yyyy"
        return f
    }()

    private static let searchDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d, yyyy"
        return f
    }()
}

struct MessageContent: Decodable, Equatable {
    var id: Int?
    var type: String?
    var date: String?
    var date_unixtime: String?
    var from: String?
    var from_id: String?
    var reply_to_message_id: Int?
    var photo: String?
    var photo_file_size: Int64?
    var file: String?
    var file_name: String?
    var file_size: Int64?
    var thumbnail: String?
    var media_type: String?
    var mime_type: String?
    var duration_seconds: Int?
    var width: Int?
    var height: Int?
    var sticker_emoji: String?
    var performer: String?
    var title: String?
    var contact_information: ContactInfo?
    var location_information: LocationInfo?
    var action: String?
    var actor: String?
    var duration: Int?
    var discard_reason: String?

    enum CodingKeys: String, CodingKey {
        case id, type, date, date_unixtime, from, from_id, reply_to_message_id
        case photo, photo_file_size, file, file_name, file_size, thumbnail
        case media_type, mime_type, duration_seconds, width, height
        case sticker_emoji, performer, title, contact_information, location_information
        case action, actor, duration, discard_reason
    }
}

struct ContactInfo: Decodable, Equatable {
    var first_name: String?
    var last_name: String?
    var phone_number: String?
}

struct LocationInfo: Decodable, Equatable {
    var latitude: Double?
    var longitude: Double?
}

func formatFileSize(_ size: Int64) -> String {
    if size <= 0 { return "0 B" }
    let units = ["B", "KB", "MB", "GB"]
    let digitGroups = min(units.count - 1, Int(log10(Double(size)) / log10(1024.0)))
    let value = Double(size) / pow(1024.0, Double(digitGroups))
    return String(format: "%.1f %@", value, units[digitGroups])
}
