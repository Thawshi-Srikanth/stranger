import Foundation
import SwiftUI
import UIKit

struct AppVersionInfo: Decodable, Identifiable {
    var id: Int { versionCode }
    let versionCode: Int
    let versionName: String
    let apkUrl: String
    let ipaUrl: String
    let altstoreRepoUrl: String?
    let releaseNotes: String?
}

@MainActor
final class UpdateManager: ObservableObject {
    static let shared = UpdateManager()

    static let versionJsonURL = "https://github.com/Thawshi-Srikanth/stranger/releases/latest/download/version.json"
    static let defaultAltstoreSourceURL = "https://github.com/Thawshi-Srikanth/stranger/releases/latest/download/altstore-source.json"

    @Published var availableUpdate: AppVersionInfo?

    var currentBuildNumber: Int {
        let buildStr = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return Int(buildStr) ?? 1
    }

    var currentVersionName: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    func checkForUpdate() async -> AppVersionInfo? {
        guard let url = URL(string: "\(Self.versionJsonURL)?t=\(Int(Date().timeIntervalSince1970))") else {
            return nil
        }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                return nil
            }
            let info = try JSONDecoder().decode(AppVersionInfo.self, from: data)
            if info.versionCode > currentBuildNumber {
                availableUpdate = info
                return info
            }
            return nil
        } catch {
            return nil
        }
    }

    func updateViaSideStore(ipaUrl: String) {
        let encoded = ipaUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ipaUrl
        if let url = URL(string: "sidestore://install?url=\(encoded)") {
            UIApplication.shared.open(url)
        }
    }

    func updateViaAltStore(ipaUrl: String) {
        let encoded = ipaUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ipaUrl
        if let url = URL(string: "altstore://install?url=\(encoded)") {
            UIApplication.shared.open(url)
        }
    }

    func addSideStoreSource(sourceUrl: String? = nil) {
        let repo = sourceUrl ?? Self.defaultAltstoreSourceURL
        let encoded = repo.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? repo
        if let url = URL(string: "sidestore://source?url=\(encoded)") {
            UIApplication.shared.open(url)
        }
    }

    func openDirectIpaDownload(ipaUrl: String) {
        if let url = URL(string: ipaUrl) {
            UIApplication.shared.open(url)
        }
    }
}

struct IOSUpdateSheet: View {
    let updateInfo: AppVersionInfo
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 20) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.system(size: 54))
                        .foregroundColor(Color(red: 0.25, green: 0.61, blue: 1.0))

                    Text("Update Available (\(updateInfo.versionName))")
                        .font(.title2.bold())
                        .foregroundColor(.white)

                    Text(updateInfo.releaseNotes?.isEmpty == false
                         ? updateInfo.releaseNotes!
                         : "A new version of Stranger is available for your iPhone.")
                        .font(.subheadline)
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)

                    VStack(spacing: 12) {
                        Button {
                            UpdateManager.shared.updateViaSideStore(ipaUrl: updateInfo.ipaUrl)
                            onDismiss()
                        } label: {
                            Label("One-Tap Update in SideStore", systemImage: "bolt.fill")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color(red: 0.25, green: 0.61, blue: 1.0))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }

                        Button {
                            UpdateManager.shared.updateViaAltStore(ipaUrl: updateInfo.ipaUrl)
                            onDismiss()
                        } label: {
                            Label("One-Tap Update in AltStore", systemImage: "arrow.triangle.2.circlepath")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color(white: 0.16))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }

                        Button {
                            UpdateManager.shared.addSideStoreSource(sourceUrl: updateInfo.altstoreRepoUrl)
                        } label: {
                            Label("Enable Background Auto-Updates (Add Source)", systemImage: "plus.circle")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(Color(red: 0.25, green: 0.61, blue: 1.0))
                                .frame(maxWidth: .infinity)
                                .padding(12)
                                .background(Color(white: 0.12))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }

                        Button {
                            UpdateManager.shared.openDirectIpaDownload(ipaUrl: updateInfo.ipaUrl)
                            onDismiss()
                        } label: {
                            Text("Download .ipa Directly (TrollStore / Sideloadly)")
                                .font(.footnote)
                                .foregroundColor(.gray)
                                .padding(.top, 4)
                        }
                    }
                    .padding(.horizontal, 20)

                    Spacer()
                }
                .padding(.top, 28)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Later") { onDismiss() }
                        .foregroundColor(.gray)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
