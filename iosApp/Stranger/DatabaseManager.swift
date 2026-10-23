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

        let sql = "SELECT id, sender, timestamp, type, content, text_plain FROM messages ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?"
        var stmt: OpaquePointer?
        var messages: [ChatMessage] = []
        var replyIds = Set<Int>()

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int(stmt, 1, Int32(limit))
            sqlite3_bind_int(stmt, 2, Int32(offset))
            while sqlite3_step(stmt) == SQLITE_ROW {
                var msg = readMessage(stmt: stmt)
                if let replyId = msg.parsedContent.reply_to_message_id {
                    replyIds.insert(replyId)
                }
                messages.append(msg)
            }
        }
        sqlite3_finalize(stmt)

        if !replyIds.isEmpty {
            let replyMap = fetchReplies(ids: Array(replyIds))
            for i in messages.indices {
                var m = messages[i]
                if let rId = m.parsedContent.reply_to_message_id, let info = replyMap[rId] {
                    m.replyToSender = info.sender
                    m.replyToText = info.text
                    messages[i] = m
                }
            }
        }
        return messages
    }

    private func fetchReplies(ids: [Int]) -> [Int: (sender: String, text: String)] {
        guard let db = dbPointer, !ids.isEmpty else { return [:] }
        let placeholders = ids.map { String($0) }.joined(separator: ",")
        let sql = "SELECT id, sender, text_plain, content FROM messages WHERE id IN (\(placeholders))"
        var stmt: OpaquePointer?
        var map: [Int: (String, String)] = [:]
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = Int(sqlite3_column_int(stmt, 0))
                let sender = columnString(stmt, 1) ?? "Unknown"
                let textPlain = columnString(stmt, 2)
                let content = columnString(stmt, 3) ?? "{}"
                let displayText: String
                if let tp = textPlain, !tp.isEmpty {
                    displayText = tp
                } else if content.contains("\"photo\"") {
                    displayText = "Photo"
                } else if content.contains("\"video\"") {
                    displayText = "Video"
                } else if content.contains("\"voice_message\"") {
                    displayText = "Voice message"
                } else {
                    displayText = "Media"
                }
                map[id] = (sender, displayText)
            }
        }
        sqlite3_finalize(stmt)
        return map
    }

    func searchMessages(query: String) -> [ChatMessage] {
        openDatabaseIfNeeded()
        guard let db = dbPointer, !query.isEmpty else { return [] }
        let sql = "SELECT id, sender, timestamp, type, content, text_plain FROM messages WHERE text_plain LIKE ? ORDER BY timestamp DESC LIMIT 100"
        var stmt: OpaquePointer?
        var results: [ChatMessage] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            let pattern = "%\(query)%"
            sqlite3_bind_text(stmt, 1, (pattern as NSString).utf8String, -1, nil)
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
        let filter: String
        switch category {
        case "Media":
            filter = "(content LIKE '%\"photo\":%' OR content LIKE '%\"media_type\":\"video%' OR content LIKE '%\"media_type\": \"video%')"
        case "Files":
            filter = "(content LIKE '%\"file\":%' AND content NOT LIKE '%\"media_type\":%')"
        case "Music":
            filter = "(content LIKE '%\"media_type\":\"audio%' OR content LIKE '%\"media_type\": \"audio%')"
        case "Voice":
            filter = "(content LIKE '%\"media_type\":\"voice_message%' OR content LIKE '%\"media_type\": \"voice_message%')"
        case "Location":
            filter = "content LIKE '%\"location_information\":%'"
        case "Contact":
            filter = "content LIKE '%\"contact_information\":%'"
        default:
            filter = "1=1"
        }

        let sql = "SELECT id, sender, timestamp, type, content, text_plain FROM messages WHERE \(filter) ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?"
        var stmt: OpaquePointer?
        var list: [ChatMessage] = []
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int(stmt, 1, Int32(limit))
            sqlite3_bind_int(stmt, 2, Int32(offset))
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
        let sql = """
        SELECT COUNT(*) FROM messages WHERE timestamp > (SELECT timestamp FROM messages WHERE id = ?)
        OR (timestamp = (SELECT timestamp FROM messages WHERE id = ?) AND id > ?)
        """
        var stmt: OpaquePointer?
        var offset = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int(stmt, 1, Int32(messageId))
            sqlite3_bind_int(stmt, 2, Int32(messageId))
            sqlite3_bind_int(stmt, 3, Int32(messageId))
            if sqlite3_step(stmt) == SQLITE_ROW {
                offset = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return offset
    }

    func getOffsetByDate(targetTimestamp: Int64) -> Int {
        openDatabaseIfNeeded()
        guard let db = dbPointer else { return 0 }
        let sql = "SELECT COUNT(*) FROM messages WHERE timestamp > ?"
        var stmt: OpaquePointer?
        var offset = 0
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int64(stmt, 1, targetTimestamp)
            if sqlite3_step(stmt) == SQLITE_ROW {
                offset = Int(sqlite3_column_int(stmt, 0))
            }
        }
        sqlite3_finalize(stmt)
        return offset
    }

    func localMediaURL(for key: String) -> URL {
        return mediaDirectory.appendingPathComponent(key)
    }

    func isMediaDownloaded(_ key: String) -> Bool {
        let url = localMediaURL(for: key)
        guard FileManager.default.fileExists(atPath: url.path),
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? Int64 else { return false }
        return size > 0
    }

    func downloadMedia(key: String, onProgress: ((Int) -> Void)? = nil) async -> URL? {
        if isMediaDownloaded(key) {
            return localMediaURL(for: key)
        }
        let dest = localMediaURL(for: key)
        return try? await R2Client.shared.downloadObject(
            key: key,
            destinationURL: dest,
            onProgress: onProgress
        )
    }

    private func readMessage(stmt: OpaquePointer?) -> ChatMessage {
        let id = Int(sqlite3_column_int(stmt, 0))
        let sender = columnString(stmt, 1)
        let timestamp = sqlite3_column_int64(stmt, 2)
        let type = columnString(stmt, 3)
        let content = columnString(stmt, 4) ?? "{}"
        let textPlain = columnString(stmt, 5)
        return ChatMessage(
            id: id,
            sender: sender,
            timestamp: timestamp,
            type: type,
            content: content,
            textPlain: textPlain
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
