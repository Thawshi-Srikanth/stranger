import Foundation
import CryptoKit

final class R2Client: NSObject, URLSessionDownloadDelegate {
    static let shared = R2Client()

    private var progressHandlers: [Int: (Int) -> Void] = [:]
    private var continuations: [Int: CheckedContinuation<URL, Error>] = [:]
    private let lock = NSLock()

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    func downloadObject(
        key: String,
        destinationURL: URL,
        onProgress: ((Int) -> Void)? = nil
    ) async throws -> URL {
        let endpoint = SecurityConfig.r2Endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let bucket = SecurityConfig.r2BucketName
        let accessKey = SecurityConfig.r2AccessKeyId
        let secretKey = SecurityConfig.r2SecretAccessKey

        let encodedKey = key
            .split(separator: "/")
            .map { $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? String($0) }
            .joined(separator: "/")

        guard let url = URL(string: "\(endpoint)/\(bucket)/\(encodedKey)") else {
            throw URLError(.badURL)
        }

        let request = try signRequest(
            url: url,
            method: "GET",
            accessKey: accessKey,
            secretKey: secretKey,
            region: "auto",
            service: "s3"
        )

        let tempURL: URL = try await withCheckedThrowingContinuation { continuation in
            let task = session.downloadTask(with: request)
            lock.lock()
            if let onProgress = onProgress {
                progressHandlers[task.taskIdentifier] = onProgress
            }
            continuations[task.taskIdentifier] = continuation
            lock.unlock()
            task.resume()
        }

        let fm = FileManager.default
        try fm.createDirectory(
            at: destinationURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if fm.fileExists(atPath: destinationURL.path) {
            try fm.removeItem(at: destinationURL)
        }
        try fm.moveItem(at: tempURL, to: destinationURL)
        try fm.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: destinationURL.path
        )
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        var mutableURL = destinationURL
        try? mutableURL.setResourceValues(resourceValues)

        return destinationURL
    }

    private func signRequest(
        url: URL,
        method: String,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String
    ) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method

        let now = Date()
        let amzFormatter = DateFormatter()
        amzFormatter.locale = Locale(identifier: "en_US_POSIX")
        amzFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        amzFormatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        let amzDate = amzFormatter.string(from: now)

        let dateStampFormatter = DateFormatter()
        dateStampFormatter.locale = Locale(identifier: "en_US_POSIX")
        dateStampFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        dateStampFormatter.dateFormat = "yyyyMMdd"
        let dateStamp = dateStampFormatter.string(from: now)

        let host = url.host ?? ""
        let payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        request.setValue(host, forHTTPHeaderField: "Host")
        request.setValue(amzDate, forHTTPHeaderField: "x-amz-date")
        request.setValue(payloadHash, forHTTPHeaderField: "x-amz-content-sha256")

        let canonicalURI = url.path.isEmpty ? "/" : url.path
        let canonicalQuery = url.query ?? ""
        let canonicalHeaders = "host:\(host)\nx-amz-content-sha256:\(payloadHash)\nx-amz-date:\(amzDate)\n"
        let signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        let canonicalRequest = [
            method,
            canonicalURI,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ].joined(separator: "\n")

        let canonicalRequestHash = sha256Hex(canonicalRequest)
        let credentialScope = "\(dateStamp)/\(region)/\(service)/aws4_request"
        let stringToSign = [
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            canonicalRequestHash
        ].joined(separator: "\n")

        let kSecret = Data("AWS4\(secretKey)".utf8)
        let kDate = hmacSHA256(key: kSecret, data: Data(dateStamp.utf8))
        let kRegion = hmacSHA256(key: kDate, data: Data(region.utf8))
        let kService = hmacSHA256(key: kRegion, data: Data(service.utf8))
        let kSigning = hmacSHA256(key: kService, data: Data("aws4_request".utf8))
        let signature = hmacSHA256(key: kSigning, data: Data(stringToSign.utf8))
            .map { String(format: "%02x", $0) }
            .joined()

        let authorization = "AWS4-HMAC-SHA256 Credential=\(accessKey)/\(credentialScope), SignedHeaders=\(signedHeaders), Signature=\(signature)"
        request.setValue(authorization, forHTTPHeaderField: "Authorization")

        return request
    }

    private func sha256Hex(_ string: String) -> String {
        let digest = SHA256.hash(data: Data(string.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func hmacSHA256(key: Data, data: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
        return Data(mac)
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let pct = Int((totalBytesWritten * 100) / totalBytesExpectedToWrite)
        lock.lock()
        let handler = progressHandlers[downloadTask.taskIdentifier]
        lock.unlock()
        handler?(min(100, max(0, pct)))
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        lock.lock()
        let continuation = continuations.removeValue(forKey: downloadTask.taskIdentifier)
        progressHandlers.removeValue(forKey: downloadTask.taskIdentifier)
        lock.unlock()

        if let httpResponse = downloadTask.response as? HTTPURLResponse,
           !(200...299).contains(httpResponse.statusCode) {
            continuation?.resume(throwing: URLError(.badServerResponse))
            return
        }

        let tempDest = FileManager.default.temporaryDirectory
            .appendingPathComponent("r2_dl_\(UUID().uuidString)")
        do {
            try FileManager.default.moveItem(at: location, to: tempDest)
            continuation?.resume(returning: tempDest)
        } catch {
            continuation?.resume(throwing: error)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error = error else { return }
        lock.lock()
        let continuation = continuations.removeValue(forKey: task.taskIdentifier)
        progressHandlers.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        continuation?.resume(throwing: error)
    }
}
