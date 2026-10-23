import Foundation
import SQLite3
import zlib

enum SetupState: Equatable {
    case idle
    case checking
    case downloading(Int)
    case extracting
    case completed
    case error(String)
}

@MainActor
final class DatabaseManager: ObservableObject {
    @Published var setupState: SetupState = .idle

    private let dbName = "chat.db"
    private let r2ObjectKey = "messages/chat.db.gz.enc"
    private let outgoingId = "user1110791963"
    private let incomingId = "user1159974332"
    private var dbPointer: OpaquePointer?

    private var dbURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(dbName)
    }

    var mediaDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func setupDatabase() async {
        setupState = .checking
        let fm = FileManager.default
        if fm.fileExists(atPath: dbURL.path),
           let attrs = try? fm.attributesOfItem(atPath: dbURL.path),
           let size = attrs[.size] as? Int64, size > 1024 {
            openDatabaseIfNeeded()
            setupState = .completed
            return
        }

        let encTempURL = fm.temporaryDirectory.appendingPathComponent("chat.db.gz.enc")
        do {
            setupState = .downloading(0)
            _ = try await R2Client.shared.downloadObject(
                key: r2ObjectKey,
                destinationURL: encTempURL
            ) { [weak self] pct in
                Task { @MainActor in
                    self?.setupState = .downloading(pct)
                }
            }

            setupState = .extracting
            let targetURL = dbURL
            try await Task.detached(priority: .userInitiated) {
                let encryptedData = try Data(contentsOf: encTempURL)
                try? FileManager.default.removeItem(at: encTempURL)
                let gzippedData = try SecurityConfig.decryptBytes(encryptedData)
                let decompressed = try Self.gunzip(data: gzippedData)
                try decompressed.write(to: targetURL, options: [.atomic, .completeFileProtection])
                var rv = URLResourceValues()
                rv.isExcludedFromBackup = true
                var mutableURL = targetURL
                try? mutableURL.setResourceValues(rv)
            }.value

            openDatabaseIfNeeded()
            setupState = .completed
        } catch {
            try? fm.removeItem(at: encTempURL)
            try? fm.removeItem(at: dbURL)
            setupState = .error(error.localizedDescription)
        }
    }

    private func openDatabaseIfNeeded() {
        guard dbPointer == nil else { return }
        sqlite3_open_v2(dbURL.path, &dbPointer, SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX, nil)
    }

    func getMessages(limit: Int, offset: Int) -> [ChatMessage] {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return [] }

        let sql = """
        SELECT m.id, m.message_id, m.date, m.from_id, m.content, m.media_type,
               m.reply_to_message_id, p.id as p_id, p.from_id as p_from, p.content as p_content
        FROM messages m
        LEFT JOIN messages p ON m.reply_to_message_id = p.message_id
        WHERE m.from_id IN (?, ?)
        ORDER BY m.id DESC
        LIMIT ? OFFSET ?
        """
        var stmt: OpaquePointer?
        var messages: [ChatMessage] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (incomingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (outgoingId as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 3, Int32(limit))
            sqlite3_bind_int(stmt, 4, Int32(offset))
            while sqlite3_step(stmt) == SQLITE_ROW {
                messages.append(readJoinedMessage(stmt: stmt))
            }
        }
        sqlite3_finalize(stmt)
        return messages
    }

    func searchMessages(query: String) -> [ChatMessage] {
        openDatabaseIfNeeded()
        guard let db = dbPointer, !query.isEmpty else { return [] }
        let sql = """
        SELECT id, message_id, date, from_id, content, media_type
        FROM messages
        WHERE from_id IN (?, ?) AND content LIKE ?
        ORDER BY id DESC
        LIMIT 100
        """
        var stmt: OpaquePointer?
        var results: [ChatMessage] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            let pattern = "%\(query)%"
            sqlite3_bind_text(stmt, 1, (incomingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (outgoingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (pattern as NSString).utf8String, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                results.append(readMessage(stmt: stmt))
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    func getMediaMessages(category: String, limit: Int = 200, offset: Int = 0) -> [ChatMessage] {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return [] }
        let typeFilter: String
        switch category {
        case "Media":
            typeFilter = "media_type IN ('photo', 'video', 'rounded_video', 'photos', 'videos')"
        case "Files":
            typeFilter = "media_type IN ('file', 'document', 'files')"
        case "Music":
            typeFilter = "media_type IN ('audio', 'music')"
        case "Voice":
            typeFilter = "media_type IN ('voice_message', 'voice')"
        case "Location":
            typeFilter = "media_type IN ('location', 'venue')"
        case "Contact":
            typeFilter = "media_type IN ('contact', 'phone_number')"
        default:
            typeFilter = "media_type IS NOT NULL"
        }

        let sql = """
        SELECT id, message_id, date, from_id, content, media_type
        FROM messages
        WHERE from_id IN (?, ?) AND \(typeFilter)
        ORDER BY id DESC
        LIMIT ? OFFSET ?
        """
        var stmt: OpaquePointer?
        var list: [ChatMessage] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (incomingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (outgoingId as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 3, Int32(limit))
            sqlite3_bind_int(stmt, 4, Int32(offset))
            while sqlite3_step(stmt) == SQLITE_ROW {
                list.append(readMessage(stmt: stmt))
            }
        }
        sqlite3_finalize(stmt)
        return list
    }

    func getMessageOffset(messageId: Int) -> Int {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return 0 }
        let sql = "SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND id > ?"
        var stmt: OpaquePointer?
        var offset = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (incomingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (outgoingId as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 3, Int32(messageId))
            if sqlite3_step(stmt) == SQLITE_ROW {
                offset = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return offset
    }

    func getDateRange() -> (minDate: Date, maxDate: Date)? {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return nil }
        let sql = "SELECT MIN(date), MAX(date) FROM messages"
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW,
              let minRaw = columnString(stmt, 0),
              let maxRaw = columnString(stmt, 1) else {
            return nil
        }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let minNorm = String(minRaw.replacingOccurrences(of: "T", with: " ").prefix(19))
        let maxNorm = String(maxRaw.replacingOccurrences(of: "T", with: " ").prefix(19))
        guard let minD = f.date(from: minNorm), let maxD = f.date(from: maxNorm) else { return nil }
        return (minD, maxD)
    }

    func getOffsetByDate(_ targetDate: Date) -> Int {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return 0 }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let dateStr = f.string(from: targetDate)

        let sql = "SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND date > ?"
        var stmt: OpaquePointer?
        var offset = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (incomingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (outgoingId as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (dateStr as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                offset = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return offset
    }

    func localMediaURL(for key: String) -> URL {
        let safeName = key.replacingOccurrences(of: "/", with: "_")
        return mediaDirectory.appendingPathComponent(safeName)
    }

    func isMediaDownloaded(_ key: String) -> Bool {
        let url = localMediaURL(for: key)
        guard FileManager.default.fileExists(atPath: url.path),
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? Int64 else { return false }
        return size > 0
    }

    func readTgsAsJson(url: URL) -> String? {
        guard let data = try? Data(contentsOf: url),
              let decompressed = try? Self.gunzip(data: data) else { return nil }
        return String(data: decompressed, encoding: .utf8)
    }

    func downloadMedia(key: String, onProgress: ((Int) -> Void)? = nil) async -> URL? {
        if isMediaDownloaded(key) {
            return localMediaURL(for: key)
        }
        let dest = localMediaURL(for: key)
        let cleanKey = key.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let rootFiles: Set<String> = ["avatar.jpg", "chat-bg.png"]
        let finalKey: String
        if rootFiles.contains(cleanKey) || cleanKey.hasPrefix("messages/") {
            finalKey = cleanKey
        } else {
            finalKey = "messages/\(cleanKey)"
        }
        return try? await R2Client.shared.downloadObject(
            key: finalKey,
            destinationURL: dest,
            onProgress: onProgress
        )
    }

    private func readJoinedMessage(stmt: OpaquePointer?) -> ChatMessage {
        let id = Int(sqlite3_column_int(stmt, 0))
        let messageId = sqlite3_column_int64(stmt, 1)
        let date = columnString(stmt, 2) ?? ""
        let fromId = columnString(stmt, 3) ?? ""
        let content = columnString(stmt, 4) ?? "{}"
        let mediaType = columnString(stmt, 5)
        let replyToMsgId: Int64? = sqlite3_column_type(stmt, 6) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 6)
        let parentLocalId: Int? = sqlite3_column_type(stmt, 7) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 7))
        let pFromId = columnString(stmt, 8)
        let pContent = columnString(stmt, 9)

        var replySender: String? = nil
        var replyText: String? = nil
        if let pFrom = pFromId {
            replySender = (pFrom == outgoingId) ? "You" : "Stranger"
            if let pRaw = pContent {
                let dummy = ChatMessage(
                    id: 0,
                    date: "",
                    fromId: pFrom,
                    content: pRaw,
                    isSentByMe: pFrom == outgoingId
                )
                if let tp = dummy.textPlain, !tp.isEmpty {
                    replyText = tp
                } else if let emoji = dummy.parsedContent.sticker_emoji, !emoji.isEmpty {
                    replyText = "Sticker \(emoji)"
                } else {
                    replyText = dummy.parsedContent.title
                        ?? dummy.parsedContent.media_type?.uppercased()
                        ?? "Media"
                }
            }
        }

        return ChatMessage(
            id: id,
            messageId: messageId,
            date: date,
            fromId: fromId,
            content: content,
            mediaType: mediaType,
            isSentByMe: fromId == outgoingId,
            replyToMessageId: replyToMsgId,
            parentLocalId: parentLocalId,
            replyToText: replyText,
            replyToSender: replySender
        )
    }

    private func readMessage(stmt: OpaquePointer?) -> ChatMessage {
        let id = Int(sqlite3_column_int(stmt, 0))
        let messageId = sqlite3_column_int64(stmt, 1)
        let date = columnString(stmt, 2) ?? ""
        let fromId = columnString(stmt, 3) ?? ""
        let content = columnString(stmt, 4) ?? "{}"
        let mediaType = columnString(stmt, 5)
        return ChatMessage(
            id: id,
            messageId: messageId,
            date: date,
            fromId: fromId,
            content: content,
            mediaType: mediaType,
            isSentByMe: fromId == outgoingId
        )
    }

    private func columnString(_ stmt: OpaquePointer?, _ index: Int32) -> String? {
        guard let cStr = sqlite3_column_text(stmt, index) else { return nil }
        return String(cString: cStr)
    }

    nonisolated private static func gunzip(data: Data) throws -> Data {
        guard !data.isEmpty else { return Data() }
        var stream = z_stream()
        var status: Int32 = Z_OK

        data.withUnsafeBytes { rawBuffer in
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: rawBuffer.bindMemory(to: Bytef.self).baseAddress!)
            stream.avail_in = uInt(data.count)
        }

        status = inflateInit2_(&stream, 15 + 32, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard status == Z_OK else {
            throw NSError(domain: "Gunzip", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "inflateInit2 failed"])
        }
        defer { inflateEnd(&stream) }

        var decompressed = Data(capacity: data.count * 3)
        let chunkSize = 64 * 1024
        var buffer = [UInt8](repeating: 0, count: chunkSize)

        repeat {
            let produced: Int = buffer.withUnsafeMutableBytes { outBuffer in
                stream.next_out = outBuffer.bindMemory(to: Bytef.self).baseAddress!
                stream.avail_out = uInt(chunkSize)
                status = inflate(&stream, Z_NO_FLUSH)
                return chunkSize - Int(stream.avail_out)
            }
            if produced > 0 {
                decompressed.append(buffer, count: produced)
            }
        } while status == Z_OK

        guard status == Z_STREAM_END else {
            throw NSError(domain: "Gunzip", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "Corrupt gzip stream"])
        }
        return decompressed
    }
}
