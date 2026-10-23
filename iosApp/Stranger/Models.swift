import Foundation

struct TextEntity: Decodable, Equatable {
    var text: String = ""
    var type: String = "plain"
    var url: String? = nil

    enum CodingKeys: String, CodingKey {
        case text, type, url
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.text = (try? container.decode(String.self, forKey: .text)) ?? ""
        self.type = (try? container.decode(String.self, forKey: .type)) ?? "plain"
        self.url = try? container.decode(String.self, forKey: .url)
    }
}

struct ChatMessage: Identifiable, Equatable {
    let id: Int
    let messageId: Int64
    let date: String
    let fromId: String
    let content: String
    let mediaType: String?
    let isSentByMe: Bool
    var replyToText: String?
    var replyToSender: String?
    let parsedContent: MessageContent
    let textPlain: String?

    init(
        id: Int,
        messageId: Int64 = 0,
        date: String,
        fromId: String,
        content: String,
        mediaType: String? = nil,
        isSentByMe: Bool,
        replyToText: String? = nil,
        replyToSender: String? = nil
    ) {
        self.id = id
        self.messageId = messageId
        self.date = date
        self.fromId = fromId
        self.content = content
        self.mediaType = mediaType
        self.isSentByMe = isSentByMe
        self.replyToText = replyToText
        self.replyToSender = replyToSender

        let parsed = Self.parseContent(content)
        self.parsedContent = parsed
        self.textPlain = Self.extractDisplayText(from: parsed)
    }

    var sender: String {
        isSentByMe ? "You" : "Stranger"
    }

    private static func parseContent(_ rawContent: String) -> MessageContent {
        let trimmed = rawContent.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{"),
           let data = trimmed.data(using: .utf8),
           let decoded = try? JSONDecoder().decode(MessageContent.self, from: data) {
            return decoded
        } else if !trimmed.hasPrefix("{") {
            return MessageContent(text: rawContent)
        } else {
            // Fallback regex for unescaped quotes inside "text": "..."
            if let range = rawContent.range(of: #""text"\s*:\s*"(.*?)(?<!\\)""#, options: .regularExpression) {
                let matchStr = String(rawContent[range])
                if let colonIdx = matchStr.firstIndex(of: ":") {
                    var val = String(matchStr[matchStr.index(after: colonIdx)...]).trimmingCharacters(in: .whitespaces)
                    if val.hasPrefix("\"") { val.removeFirst() }
                    if val.hasSuffix("\"") { val.removeLast() }
                    return MessageContent(text: unescape(val))
                }
            }
            return MessageContent(text: rawContent)
        }
    }

    static func unescape(_ str: String) -> String {
        return str
            .replacingOccurrences(of: "\\\"", with: "\"")
            .replacingOccurrences(of: "\\n", with: "\n")
            .replacingOccurrences(of: "\\r", with: "\r")
            .replacingOccurrences(of: "\\'", with: "'")
            .replacingOccurrences(of: "\\\\n", with: "\n")
            .replacingOccurrences(of: "\\\\\"", with: "\"")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }

    private static func extractDisplayText(from content: MessageContent) -> String? {
        if let entities = content.text_entities, !entities.isEmpty {
            let joined = entities.map { unescape($0.text) }.joined()
            if !joined.isEmpty { return joined }
        }
        if let text = content.text, !text.isEmpty {
            return unescape(text)
        }
        if let emoji = content.sticker_emoji, !emoji.isEmpty {
            return emoji
        }
        return nil
    }

    private var parsedDate: Date? {
        let normalized = String(date.replacingOccurrences(of: "T", with: " ").prefix(19))
        return Self.dbDateFormatter.date(from: normalized)
    }

    var formattedTime: String {
        guard let d = parsedDate else { return "00:00" }
        return Self.timeFormatter.string(from: d)
    }

    var formattedDate: String {
        guard let d = parsedDate else { return "Unknown" }
        return Self.dateFormatter.string(from: d)
    }

    var formattedSearchDate: String {
        guard let d = parsedDate else { return "" }
        return Self.searchDateFormatter.string(from: d)
    }

    var formattedDuration: String {
        let secs = Int(parsedContent.duration ?? Double(parsedContent.duration_seconds ?? 0))
        let mins = secs / 60
        let rem = secs % 60
        return String(format: "%d:%02d", mins, rem)
    }

    private static let dbDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Colombo")
        f.dateFormat = "HH:mm"
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Colombo")
        f.dateFormat = "MMMM dd, yyyy"
        return f
    }()

    private static let searchDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Colombo")
        f.dateFormat = "dd MMM yyyy"
        return f
    }()
}

struct MessageContent: Decodable, Equatable {
    var id: Int?
    var type: String?
    var text: String?
    var text_entities: [TextEntity]?
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
    var sticker: String?
    var media_type: String?
    var mime_type: String?
    var duration_seconds: Int?
    var duration: Double?
    var width: Int?
    var height: Int?
    var sticker_emoji: String?
    var performer: String?
    var title: String?
    var contact_information: ContactInfo?
    var location_information: LocationInfo?
    var action: String?
    var actor: String?
    var discard_reason: String?

    init(text: String? = nil) {
        self.text = text
    }

    enum CodingKeys: String, CodingKey {
        case id, type, text, text_entities, date, date_unixtime, from, from_id, reply_to_message_id
        case photo, photo_file_size, file, file_name, file_size, thumbnail, sticker
        case media_type, mime_type, duration_seconds, duration, width, height
        case sticker_emoji, performer, title, contact_information, location_information
        case action, actor, discard_reason
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try? c.decode(Int.self, forKey: .id)
        type = try? c.decode(String.self, forKey: .type)
        if let strText = try? c.decode(String.self, forKey: .text) {
            text = strText
        }
        text_entities = try? c.decode([TextEntity].self, forKey: .text_entities)
        date = try? c.decode(String.self, forKey: .date)
        date_unixtime = try? c.decode(String.self, forKey: .date_unixtime)
        from = try? c.decode(String.self, forKey: .from)
        from_id = try? c.decode(String.self, forKey: .from_id)
        reply_to_message_id = try? c.decode(Int.self, forKey: .reply_to_message_id)
        photo = try? c.decode(String.self, forKey: .photo)
        photo_file_size = try? c.decode(Int64.self, forKey: .photo_file_size)
        file = try? c.decode(String.self, forKey: .file)
        file_name = try? c.decode(String.self, forKey: .file_name)
        file_size = try? c.decode(Int64.self, forKey: .file_size)
        thumbnail = try? c.decode(String.self, forKey: .thumbnail)
        sticker = try? c.decode(String.self, forKey: .sticker)
        media_type = try? c.decode(String.self, forKey: .media_type)
        mime_type = try? c.decode(String.self, forKey: .mime_type)
        duration_seconds = try? c.decode(Int.self, forKey: .duration_seconds)
        if let d = try? c.decode(Double.self, forKey: .duration) {
            duration = d
        } else if let dInt = try? c.decode(Int.self, forKey: .duration) {
            duration = Double(dInt)
        }
        width = try? c.decode(Int.self, forKey: .width)
        height = try? c.decode(Int.self, forKey: .height)
        sticker_emoji = try? c.decode(String.self, forKey: .sticker_emoji)
        performer = try? c.decode(String.self, forKey: .performer)
        title = try? c.decode(String.self, forKey: .title)
        contact_information = try? c.decode(ContactInfo.self, forKey: .contact_information)
        location_information = try? c.decode(LocationInfo.self, forKey: .location_information)
        action = try? c.decode(String.self, forKey: .action)
        actor = try? c.decode(String.self, forKey: .actor)
        discard_reason = try? c.decode(String.self, forKey: .discard_reason)
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
