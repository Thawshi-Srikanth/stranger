import SwiftUI

@main
struct StrangerApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var dbManager = DatabaseManager()
    @StateObject private var updateManager = UpdateManager.shared
    @State private var isUnlocked = false

    private let passcodeManager = PasscodeManager()

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView(
                    dbManager: dbManager,
                    updateManager: updateManager,
                    passcodeManager: passcodeManager,
                    isUnlocked: $isUnlocked
                )
                .preferredColorScheme(.dark)

                // Privacy shield in iOS App Switcher / Background
                if scenePhase != .active {
                    Color.black
                        .ignoresSafeArea()
                        .overlay {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 56))
                                .foregroundColor(.white.opacity(0.4))
                        }
                }
            }
            .onChange(of: scenePhase) { newPhase in
                if newPhase == .background && passcodeManager.hasPasscode() {
                    isUnlocked = false
                }
            }
        }
    }
}
