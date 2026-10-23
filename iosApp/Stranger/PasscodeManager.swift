import Foundation
import CommonCrypto

final class PasscodeManager {
    private let defaults = UserDefaults.standard
    private let keyHash = "passcode_hash_v2"
    private let keySalt = "passcode_salt_v2"
    private let keyFailedAttempts = "failed_attempts"
    private let keyLockoutUntil = "lockout_until_ms"

    private let maxFailedAttempts = 5
    private let lockoutDurationMs: Int64 = 30_000
    private let pbkdf2Iterations: UInt32 = 50_000

    func hasPasscode() -> Bool {
        return defaults.string(forKey: keyHash) != nil
    }

    func getRemainingLockoutSeconds() -> Int64 {
        let until = Int64(defaults.integer(forKey: keyLockoutUntil))
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if until > now {
            return (until - now + 999) / 1000
        }
        return 0
    }

    func recordFailedAttempt() {
        let attempts = defaults.integer(forKey: keyFailedAttempts) + 1
        if attempts >= maxFailedAttempts {
            let lockoutUntil = Int64(Date().timeIntervalSince1970 * 1000) + lockoutDurationMs
            defaults.set(0, forKey: keyFailedAttempts)
            defaults.set(Int(lockoutUntil), forKey: keyLockoutUntil)
        } else {
            defaults.set(attempts, forKey: keyFailedAttempts)
        }
    }

    func setPasscode(_ pin: String) {
        var salt = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, salt.count, &salt)
        let hash = pbkdf2(pin: pin, salt: salt)
        defaults.set(Data(salt).base64EncodedString(), forKey: keySalt)
        defaults.set(Data(hash).base64EncodedString(), forKey: keyHash)
        defaults.set(0, forKey: keyFailedAttempts)
        defaults.set(0, forKey: keyLockoutUntil)
    }

    func verifyPasscode(_ pin: String) -> Bool {
        if getRemainingLockoutSeconds() > 0 { return false }
        guard let saltB64 = defaults.string(forKey: keySalt),
              let hashB64 = defaults.string(forKey: keyHash),
              let saltData = Data(base64Encoded: saltB64),
              let expectedData = Data(base64Encoded: hashB64) else {
            return false
        }
        let actual = pbkdf2(pin: pin, salt: [UInt8](saltData))
        let expected = [UInt8](expectedData)
        guard actual.count == expected.count else {
            recordFailedAttempt()
            return false
        }
        var diff: UInt8 = 0
        for i in 0..<actual.count {
            diff |= actual[i] ^ expected[i]
        }
        if diff == 0 {
            defaults.set(0, forKey: keyFailedAttempts)
            defaults.set(0, forKey: keyLockoutUntil)
            return true
        } else {
            recordFailedAttempt()
            return false
        }
    }

    private func pbkdf2(pin: String, salt: [UInt8]) -> [UInt8] {
        let pinData = pin.data(using: .utf8) ?? Data()
        var derived = [UInt8](repeating: 0, count: 32)
        _ = pinData.withUnsafeBytes { pinRaw in
            salt.withUnsafeBufferPointer { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pinRaw.baseAddress?.assumingMemoryBound(to: Int8.self),
                    pinData.count,
                    saltPtr.baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    pbkdf2Iterations,
                    &derived,
                    32
                )
            }
        }
        return derived
    }
}
