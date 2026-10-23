import Foundation
import CryptoKit
import CommonCrypto
import Security

enum SecurityConfig {
    private static let keychainService = "com.tk.stranger.vault"
    private static let keychainAccount = "master_key_v1"
    private static let pbkdf2Iterations: UInt32 = 100_000
    private static let keyByteCount = 32

    private static let salt: [UInt8] = [
        0x73, 0x74, 0x72, 0x61, 0x6E, 0x67, 0x65, 0x72,
        0x5F, 0x6B, 0x64, 0x66, 0x5F, 0x76, 0x31, 0x21
    ]

    // BEGIN_ENDPOINT_ENC
    private static let endpointEnc: [UInt8] = [
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 
        0x99, 0xAA, 0xBB, 0xCC, 0x92, 0x15, 0x94, 0x7E, 
        0x5F, 0x4A, 0x3A, 0x2D, 0x00, 0xD0, 0xF7, 0x48, 
        0xA2, 0xA5, 0xAC, 0xF8, 0xDF, 0xF4, 0xAA, 0x1C, 
        0x13, 0x89, 0x6C, 0xC8, 0x6F, 0xA4, 0xBC, 0x0B, 
        0x88, 0xC4, 0x18, 0xCA, 0x33, 0x91, 0xFC, 0x61, 
        0x90, 0xE5, 0xBD, 0x43, 0x85, 0x85, 0x26, 0xAD, 
        0x85, 0xBB, 0xFB, 0x47, 0x2E, 0x08, 0x8D, 0x56, 
        0x6B, 0xE1, 0x83, 0xCF, 0xB1, 0x8C, 0x71, 0x7C, 
        0x2A, 0x1A, 0x51, 0xE2, 0x05, 0x03, 0x3F, 0x38, 
        0xD1, 0xF2, 0x08, 0xF0, 0xC3, 0xB6, 0x7B, 0x9A, 
        0x67, 0xFD, 0xCE, 0xE8, 0x34
    ]
    // END_ENDPOINT_ENC

    // BEGIN_BUCKET_ENC
    private static let bucketEnc: [UInt8] = [
        0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 
        0xAA, 0xBB, 0xCC, 0xDD, 0x2E, 0x8F, 0x14, 0x12, 
        0x13, 0x0C, 0x05, 0xAF, 0x21, 0x38, 0xD1, 0x48, 
        0x59, 0x68, 0x5A, 0xAA, 0x27, 0x07, 0xCE, 0xC7
    ]
    // END_BUCKET_ENC

    // BEGIN_ACCESS_ENC
    private static let accessKeyEnc: [UInt8] = [
        0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 
        0xBB, 0xCC, 0xDD, 0xEE, 0xD8, 0x40, 0x92, 0x14, 
        0x45, 0x07, 0x20, 0x58, 0x61, 0x9A, 0x22, 0x25, 
        0xEA, 0xF8, 0x55, 0x74, 0x2F, 0xBC, 0x14, 0x27, 
        0x43, 0xF6, 0x1A, 0x3C, 0x78, 0x8F, 0x32, 0xF1, 
        0x3F, 0x47, 0xB3, 0xB6, 0x3D, 0x28, 0x28, 0x8D, 
        0x61, 0xDC, 0x51, 0x69, 0xF8, 0x50, 0xC3, 0xD4, 
        0xC1, 0x35, 0xD7, 0x91
    ]
    // END_ACCESS_ENC

    // BEGIN_SECRET_ENC
    private static let secretKeyEnc: [UInt8] = [
        0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 
        0xCC, 0xDD, 0xEE, 0xFF, 0x52, 0x1F, 0xC4, 0x88, 
        0xD1, 0x3C, 0x1B, 0x15, 0xC7, 0x9E, 0x51, 0x94, 
        0x88, 0x2F, 0x7A, 0x85, 0xE1, 0x1B, 0xE1, 0x19, 
        0xA1, 0x6D, 0xF2, 0xFC, 0x88, 0x3A, 0x33, 0xF5, 
        0x8B, 0x69, 0x51, 0xCF, 0x65, 0x08, 0xA7, 0x2F, 
        0x88, 0xDB, 0x75, 0xD5, 0xA5, 0xDD, 0x9B, 0xDC, 
        0x3C, 0x46, 0x67, 0x8C, 0x77, 0x4F, 0x73, 0xFD, 
        0x11, 0x74, 0x70, 0x5B, 0xA9, 0x73, 0x3B, 0x1C, 
        0x3A, 0xE3, 0x9C, 0x76, 0x86, 0x2C, 0x34, 0x57, 
        0x32, 0xDE, 0x4D, 0xEA, 0xC4, 0x6A, 0x0B, 0x32, 
        0x46, 0x86, 0xAF, 0x87
    ]
    // END_SECRET_ENC

    private static var cachedMasterKey: SymmetricKey?

    private static func deriveKeyFromPin(_ pin: String) -> SymmetricKey? {
        guard let pinData = pin.data(using: .utf8) else { return nil }
        var derivedBytes = [UInt8](repeating: 0, count: keyByteCount)
        let status = pinData.withUnsafeBytes { pinRawBuffer in
            salt.withUnsafeBufferPointer { saltBuffer in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pinRawBuffer.baseAddress?.assumingMemoryBound(to: Int8.self),
                    pinData.count,
                    saltBuffer.baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    pbkdf2Iterations,
                    &derivedBytes,
                    keyByteCount
                )
            }
        }
        guard status == kCCSuccess else { return nil }
        return SymmetricKey(data: Data(derivedBytes))
    }

    private static func aesGcmDecrypt(key: SymmetricKey, combined: Data) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: combined)
        return try AES.GCM.open(box, using: key)
    }

    static func unlockWithPin(_ pin: String) -> Bool {
        guard let candidateKey = deriveKeyFromPin(pin) else { return false }
        do {
            _ = try aesGcmDecrypt(key: candidateKey, combined: Data(bucketEnc))
            cachedMasterKey = candidateKey
            saveKeyToKeychain(candidateKey)
            return true
        } catch {
            return false
        }
    }

    static func restoreFromKeychain() -> Bool {
        if cachedMasterKey != nil { return true }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let keyData = result as? Data else {
            return false
        }
        let key = SymmetricKey(data: keyData)
        do {
            _ = try aesGcmDecrypt(key: key, combined: Data(bucketEnc))
            cachedMasterKey = key
            return true
        } catch {
            return false
        }
    }

    private static func saveKeyToKeychain(_ key: SymmetricKey) {
        let keyData = key.withUnsafeBytes { Data($0) }
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecValueData as String: keyData,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    private static func requireKey() throws -> SymmetricKey {
        if let key = cachedMasterKey { return key }
        throw NSError(domain: "SecurityConfig", code: 401, userInfo: [NSLocalizedDescriptionKey: "Archive is locked"])
    }

    static func decryptBytes(_ encryptedPayload: Data) throws -> Data {
        return try aesGcmDecrypt(key: try requireKey(), combined: encryptedPayload)
    }

    static var r2Endpoint: String {
        guard let data = try? aesGcmDecrypt(key: requireKey(), combined: Data(endpointEnc)),
              let str = String(data: data, encoding: .utf8) else { return "" }
        return str
    }

    static var r2BucketName: String {
        guard let data = try? aesGcmDecrypt(key: requireKey(), combined: Data(bucketEnc)),
              let str = String(data: data, encoding: .utf8) else { return "" }
        return str
    }

    static var r2AccessKeyId: String {
        guard let data = try? aesGcmDecrypt(key: requireKey(), combined: Data(accessKeyEnc)),
              let str = String(data: data, encoding: .utf8) else { return "" }
        return str
    }

    static var r2SecretAccessKey: String {
        guard let data = try? aesGcmDecrypt(key: requireKey(), combined: Data(secretKeyEnc)),
              let str = String(data: data, encoding: .utf8) else { return "" }
        return str
    }
}
