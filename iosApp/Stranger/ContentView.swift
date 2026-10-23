import SwiftUI
import AVKit
import AVFoundation

// MARK: - Theme Colors (Matching Android Theme/Color.kt)

enum StrangerColors {
    static let telegramBlue = Color(red: 0x50 / 255.0, green: 0x95 / 255.0, blue: 0xF2 / 255.0)
    static let telegramPurple = Color(red: 0x8A / 255.0, green: 0x65 / 255.0, blue: 0xE6 / 255.0)
    static let telegramDarkBar = Color(red: 0x1C / 255.0, green: 0x1C / 255.0, blue: 0x1D / 255.0)
    static let telegramInboundBubble = Color(red: 0x26 / 255.0, green: 0x26 / 255.0, blue: 0x28 / 255.0)
    static let profileAvatarPink = Color(red: 1.0, green: 0.502, blue: 0.671)
    static let accentBlue = Color(red: 0x40 / 255.0, green: 0x9C / 255.0, blue: 1.0)
}

// MARK: - Shared Audio Player Manager (With Live Progress & MiniPlayer State)

@MainActor
final class AudioPlayerManager: ObservableObject {
    static let shared = AudioPlayerManager()

    @Published var isPlaying = false
    @Published var currentTrackURL: URL?
    @Published var currentTitle: String = ""
    @Published var currentTime: TimeInterval = 0
    @Published var duration: TimeInterval = 0

    private var player: AVAudioPlayer?
    private var timer: Timer?

    var progress: Double {
        guard duration > 0 else { return 0 }
        return min(1.0, max(0.0, currentTime / duration))
    }

    var formattedCurrentTime: String {
        let secs = Int(currentTime)
        return String(format: "%d:%02d", secs / 60, secs % 60)
    }

    func togglePlay(url: URL, title: String) {
        if currentTrackURL == url, let p = player {
            if p.isPlaying {
                p.pause()
                isPlaying = false
                stopTimer()
            } else {
                p.play()
                isPlaying = true
                startTimer()
            }
            return
        }

        stop()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            let newPlayer = try AVAudioPlayer(contentsOf: url)
            newPlayer.prepareToPlay()
            newPlayer.play()
            self.player = newPlayer
            self.currentTrackURL = url
            self.currentTitle = title
            self.duration = newPlayer.duration
            self.currentTime = 0
            self.isPlaying = true
            startTimer()
        } catch {
            self.isPlaying = false
        }
    }

    func stop() {
        stopTimer()
        player?.stop()
        player = nil
        isPlaying = false
        currentTrackURL = nil
        currentTitle = ""
        currentTime = 0
        duration = 0
    }

    private func startTimer() {
        stopTimer()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, let p = self.player else { return }
                self.currentTime = p.currentTime
                self.duration = p.duration
                if !p.isPlaying && p.currentTime >= p.duration - 0.15 {
                    self.isPlaying = false
                    self.currentTime = 0
                    self.stopTimer()
                }
            }
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }
}

// MARK: - Root Content View

struct ContentView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    let passcodeManager: PasscodeManager
    @Binding var isUnlocked: Bool

    @AppStorage("onboarding_finished") private var onboardingFinished = false
    @State private var isChangingPasscode = false
    @State private var showSearch = false
    @State private var showProfile = false
    @State private var jumpOffset: Int? = nil
    @State private var highlightedMessageId: Int? = nil

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if !onboardingFinished {
                OnboardingView(setupState: dbManager.setupState) {
                    onboardingFinished = true
                }
            } else if isChangingPasscode {
                PasscodeView(
                    isSetup: true,
                    passcodeManager: passcodeManager,
                    onSuccess: { pin in
                        passcodeManager.setPasscode(pin)
                        isChangingPasscode = false
                        return true
                    },
                    onCancel: { isChangingPasscode = false }
                )
            } else if !passcodeManager.hasPasscode() {
                PasscodeView(
                    isSetup: false,
                    passcodeManager: passcodeManager,
                    onSuccess: { pin in
                        if SecurityConfig.unlockWithPin(pin) {
                            passcodeManager.setPasscode(pin)
                            isUnlocked = true
                            return true
                        } else {
                            passcodeManager.recordFailedAttempt()
                            return false
                        }
                    }
                )
            } else if !isUnlocked {
                PasscodeView(
                    isSetup: false,
                    passcodeManager: passcodeManager,
                    onSuccess: { pin in
                        if passcodeManager.verifyPasscode(pin) {
                            let ok = SecurityConfig.restoreFromKeychain() || SecurityConfig.unlockWithPin(pin)
                            if ok {
                                isUnlocked = true
                                return true
                            }
                        }
                        return false
                    }
                )
            } else {
                switch dbManager.setupState {
                case .completed:
                    MainChatView(
                        dbManager: dbManager,
                        updateManager: updateManager,
                        jumpOffset: $jumpOffset,
                        highlightedMessageId: $highlightedMessageId,
                        onLock: { isUnlocked = false },
                        onOpenSearch: { showSearch = true },
                        onOpenProfile: { showProfile = true },
                        onChangePasscode: {
                            showProfile = false
                            isChangingPasscode = true
                        }
                    )
                    .fullScreenCover(isPresented: $showSearch) {
                        SearchSheetView(
                            dbManager: dbManager,
                            onJumpToMessage: { msgId in
                                showSearch = false
                                triggerJumpToMessage(msgId)
                            },
                            onJumpToDate: { targetDate in
                                showSearch = false
                                let off = dbManager.getOffsetByDate(targetDate)
                                jumpOffset = max(0, off - 15)
                            }
                        )
                    }
                    .fullScreenCover(isPresented: $showProfile) {
                        ProfileSheetView(
                            dbManager: dbManager,
                            updateManager: updateManager,
                            onChangePasscode: {
                                showProfile = false
                                isChangingPasscode = true
                            },
                            onJumpToMessage: { msgId in
                                showProfile = false
                                triggerJumpToMessage(msgId)
                            }
                        )
                    }
                    .sheet(item: $updateManager.availableUpdate) { update in
                        IOSUpdateSheet(updateInfo: update) {
                            updateManager.availableUpdate = nil
                        }
                    }

                case .error(let msg):
                    VStack(spacing: 16) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 48))
                            .foregroundColor(.red)
                        Text("Setup Failed")
                            .font(.title3.bold())
                            .foregroundColor(.white)
                        Text(msg)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        Button("Retry") {
                            Task { await dbManager.setupDatabase() }
                        }
                        .buttonStyle(.borderedProminent)
                    }

                default:
                    SetupProgressView(state: dbManager.setupState)
                }
            }
        }
        .task(id: isUnlocked) {
            if isUnlocked {
                await dbManager.setupDatabase()
                _ = await updateManager.checkForUpdate()
            }
        }
    }

    private func triggerJumpToMessage(_ msgId: Int) {
        let offset = dbManager.getMessageOffset(messageId: msgId)
        highlightedMessageId = msgId
        jumpOffset = max(0, offset - 15)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if highlightedMessageId == msgId {
                highlightedMessageId = nil
            }
        }
    }
}

// MARK: - Onboarding View (Matches Android OnboardingScreen)

struct OnboardingPageData {
    let title: String
    let description: String
    let color: Color
}

struct OnboardingView: View {
    let state: SetupState
    let onFinished: () -> Void

    init(setupState: SetupState, onFinished: @escaping () -> Void) {
        self.state = setupState
        self.onFinished = onFinished
    }

    @State private var currentPage = 0

    private let pages: [OnboardingPageData] = [
        OnboardingPageData(
            title: "A Stranger's Regret",
            description: "You called me a stranger, and in my hurt, I tried to make it true. I deleted every word we ever shared.",
            color: Color(red: 0x0F / 255.0, green: 0x17 / 255.0, blue: 0x2A / 255.0)
        ),
        OnboardingPageData(
            title: "The Echoes Remain",
            description: "But silence is louder than any message. The guilt of erasing our history became a weight I couldn't carry.",
            color: Color(red: 0x1E / 255.0, green: 0x1B / 255.0, blue: 0x4B / 255.0)
        ),
        OnboardingPageData(
            title: "Fragments of Us",
            description: "I've gathered what was lost. Every laugh, every late-night talk, every piece of 'us' that I almost destroyed.",
            color: Color(red: 0x31 / 255.0, green: 0x2E / 255.0, blue: 0x81 / 255.0)
        ),
        OnboardingPageData(
            title: "Your Archive",
            description: "This isn't for me anymore. It's for you. A safe place for the memories you might one day want to revisit.",
            color: Color(red: 0x1E / 255.0, green: 0x29 / 255.0, blue: 0x3B / 255.0)
        )
    ]

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [pages[currentPage].color, .black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .animation(.easeInOut(duration: 0.35), value: currentPage)

            TabView(selection: $currentPage) {
                ForEach(0..<pages.count, id: \.self) { idx in
                    VStack(spacing: 24) {
                        Text(pages[idx].title)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)
                        Text(pages[idx].description)
                            .font(.system(size: 18))
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .lineSpacing(6)
                    }
                    .padding(.horizontal, 40)
                    .tag(idx)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            VStack(spacing: 24) {
                Spacer()

                HStack(spacing: 8) {
                    ForEach(0..<pages.count, id: \.self) { index in
                        Circle()
                            .fill(currentPage == index ? StrangerColors.telegramBlue : Color.gray.opacity(0.4))
                            .frame(width: currentPage == index ? 10 : 8, height: currentPage == index ? 10 : 8)
                    }
                }
                .padding(.bottom, 8)

                Button {
                    if currentPage < pages.count - 1 {
                        withAnimation { currentPage += 1 }
                    } else {
                        onFinished()
                    }
                } label: {
                    Text(currentPage == pages.count - 1 ? "Open the Archive" : "Continue")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: 260)
                        .frame(height: 56)
                        .background(StrangerColors.telegramBlue)
                        .clipShape(Capsule())
                }
                .padding(.bottom, 40)
            }
        }
    }
}

// MARK: - Passcode View (Matches Android PasscodeScreen)

struct PasscodeView: View {
    let isSetup: Bool
    let passcodeManager: PasscodeManager
    let onSuccess: (String) -> Bool
    var onCancel: (() -> Void)? = nil

    @State private var enteredPin = ""
    @State private var firstPin = ""
    @State private var isConfirming = false
    @State private var errorText: String? = nil
    @State private var lockoutSeconds: Int64 = 0

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private let rows: [[(digit: String, letters: String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")]
    ]

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0x0F / 255.0, green: 0x17 / 255.0, blue: 0x2A / 255.0), .black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    if let onCancel = onCancel {
                        Button(action: onCancel) {
                            Image(systemName: "arrow.left")
                                .font(.title3)
                                .foregroundColor(.white)
                                .padding()
                        }
                    }
                    Spacer()
                }

                Spacer()

                Image(systemName: "lock.fill")
                    .font(.system(size: 42))
                    .foregroundColor(StrangerColors.telegramBlue)
                    .padding(.bottom, 20)

                Text(titleText)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.bottom, 6)

                Text(subtitleText)
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
                    .padding(.bottom, 28)

                HStack(spacing: 20) {
                    ForEach(0..<4, id: \.self) { index in
                        let isFilled = index < enteredPin.count
                        ZStack {
                            if isFilled {
                                Circle()
                                    .fill(Color.white)
                                    .frame(width: 14, height: 14)
                            } else {
                                Circle()
                                    .stroke(Color.gray.opacity(0.5), lineWidth: 1.5)
                                    .frame(width: 14, height: 14)
                            }
                        }
                        .frame(width: 32, height: 32)
                    }
                }
                .padding(.vertical, 12)

                Text(lockoutSeconds > 0 ? "Too many attempts. Try again in \(lockoutSeconds)s." : (errorText ?? " "))
                    .font(.system(size: 14))
                    .foregroundColor(.red)
                    .opacity((lockoutSeconds > 0 || errorText != nil) ? 1 : 0)
                    .padding(.bottom, 32)

                VStack(spacing: 16) {
                    ForEach(0..<rows.count, id: \.self) { rIdx in
                        HStack(spacing: 24) {
                            ForEach(rows[rIdx], id: \.digit) { key in
                                KeypadButton(digit: key.digit, letters: key.letters) {
                                    handleDigit(key.digit)
                                }
                            }
                        }
                    }
                    HStack(spacing: 24) {
                        Color.clear.frame(width: 75, height: 75)
                        KeypadButton(digit: "0", letters: "") {
                            handleDigit("0")
                        }
                        Button {
                            if !enteredPin.isEmpty {
                                enteredPin.removeLast()
                            }
                        } label: {
                            Image(systemName: "delete.left")
                                .font(.system(size: 24))
                                .foregroundColor(.white)
                                .frame(width: 75, height: 75)
                        }
                    }
                }
                .padding(.bottom, 36)

                Spacer()
            }
        }
        .onAppear {
            lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
        }
        .onReceive(timer) { _ in
            lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
        }
    }

    private var titleText: String {
        if isSetup {
            return isConfirming ? "Confirm your Passcode" : "Set a Passcode"
        }
        return "Enter Passcode"
    }

    private var subtitleText: String {
        if isSetup {
            return isConfirming ? "Re-enter your 4-digit passcode" : "Enter a 4-digit passcode"
        }
        return "Enter your 4-digit passcode to unlock"
    }

    private func handleDigit(_ digit: String) {
        guard lockoutSeconds == 0, enteredPin.count < 4 else { return }
        errorText = nil
        enteredPin.append(digit)
        guard enteredPin.count == 4 else { return }

        if isSetup {
            if !isConfirming {
                firstPin = enteredPin
                enteredPin = ""
                isConfirming = true
            } else if enteredPin == firstPin {
                _ = onSuccess(enteredPin)
            } else {
                errorText = "Passcodes do not match. Try again."
                enteredPin = ""
                isConfirming = false
                firstPin = ""
            }
        } else {
            if !onSuccess(enteredPin) {
                lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
                errorText = "Wrong passcode. Try again."
                enteredPin = ""
            }
        }
    }
}

struct KeypadButton: View {
    let digit: String
    let letters: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: -2) {
                Text(digit)
                    .font(.system(size: 30, weight: .regular))
                    .foregroundColor(.white)
                if !letters.isEmpty {
                    Text(letters)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white.opacity(0.5))
                }
            }
            .frame(width: 75, height: 75)
            .background(Color.white.opacity(0.1))
            .clipShape(Circle())
        }
    }
}

// MARK: - Setup Progress View

struct SetupProgressView: View {
    let state: SetupState

    var body: some View {
        VStack(spacing: 18) {
            switch state {
            case .downloading(let pct):
                Text("Downloading file \(pct)%")
                    .foregroundColor(.white)
                    .font(.headline)
                ProgressView(value: Double(pct), total: 100)
                    .tint(StrangerColors.telegramBlue)
                    .frame(width: 240)
            case .extracting:
                Text("Extracting database please wait")
                    .foregroundColor(.white)
                    .font(.headline)
                ProgressView()
                    .tint(StrangerColors.telegramBlue)
                    .scaleEffect(1.3)
            default:
                Text("Starting setup...")
                    .foregroundColor(.white)
                    .font(.headline)
                ProgressView()
                    .tint(StrangerColors.telegramBlue)
                    .scaleEffect(1.3)
            }
        }
    }
}

// MARK: - MiniPlayer Bar (Matches Android MiniPlayer.kt)

struct MiniPlayerBar: View {
    @ObservedObject var audioManager = AudioPlayerManager.shared

    var body: some View {
        if audioManager.currentTrackURL != nil {
            HStack(spacing: 10) {
                Button {
                    if let url = audioManager.currentTrackURL {
                        audioManager.togglePlay(url: url, title: audioManager.currentTitle)
                    }
                } label: {
                    Image(systemName: audioManager.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18))
                        .foregroundColor(StrangerColors.accentBlue)
                        .frame(width: 34, height: 34)
                }

                Text(audioManager.currentTitle.isEmpty ? "Unknown Audio" : audioManager.currentTitle)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button {
                    audioManager.stop()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.gray)
                        .frame(width: 30, height: 30)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(StrangerColors.telegramDarkBar.opacity(0.9))
            .clipShape(Capsule())
        }
    }
}

// MARK: - Main Chat View (Matches Android ChatScreen)

struct MainChatView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    @Binding var jumpOffset: Int?
    @Binding var highlightedMessageId: Int?
    let onLock: () -> Void
    let onOpenSearch: () -> Void
    let onOpenProfile: () -> Void
    let onChangePasscode: () -> Void

    @State private var messages: [ChatMessage] = []
    @State private var currentOffset = 0
    @State private var isLoadingMore = false
    @State private var avatarURL: URL?
    @State private var chatBgURL: URL?
    @State private var selectedExplorerMessage: ChatMessage?
    private let pageSize = 50

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let bgURL = chatBgURL, let uiImage = UIImage(contentsOfFile: bgURL.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .ignoresSafeArea()
            }

            VStack(spacing: 0) {
                // Top Bar + MiniPlayer
                VStack(spacing: 6) {
                    HStack(spacing: 12) {
                        Button(action: onOpenProfile) {
                            HStack(spacing: 12) {
                                AvatarImageView(avatarURL: avatarURL, size: 36, iconSize: 18)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Stranger")
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundColor(.white)
                                    Text("last seen a long time ago")
                                        .font(.system(size: 11))
                                        .foregroundColor(.gray)
                                }
                            }
                        }
                        Spacer()
                        Button(action: onOpenSearch) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 18, weight: .medium))
                                .foregroundColor(.white)
                                .padding(8)
                        }
                        Button(action: onLock) {
                            Image(systemName: "lock")
                                .font(.system(size: 18, weight: .medium))
                                .foregroundColor(.white)
                                .padding(8)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)

                    MiniPlayerBar()
                        .padding(.horizontal, 12)
                        .padding(.bottom, 4)
                }
                .background(.ultraThinMaterial)

                // Inverted Message List with Date Separators
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                let nextMessage: ChatMessage? = (index + 1 < messages.count) ? messages[index + 1] : nil
                                let showDatePill = nextMessage == nil || nextMessage?.formattedDate != message.formattedDate

                                VStack(spacing: 8) {
                                    MessageBubbleView(
                                        message: message,
                                        dbManager: dbManager,
                                        isHighlighted: highlightedMessageId == message.id,
                                        onMediaTap: { selectedExplorerMessage = message },
                                        onReplyTap: { parentId in
                                            jumpToParent(parentId, proxy: proxy)
                                        }
                                    )

                                    if showDatePill {
                                        DateHeaderPill(date: message.formattedDate)
                                    }
                                }
                                .id(message.id)
                                .rotationEffect(.degrees(180))
                                .scaleEffect(x: -1, y: 1, anchor: .center)
                                .onAppear {
                                    if message.id == messages.last?.id {
                                        loadMoreMessages()
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 12)
                    }
                    .rotationEffect(.degrees(180))
                    .scaleEffect(x: -1, y: 1, anchor: .center)
                    .onChange(of: jumpOffset) { newOffset in
                        guard let offset = newOffset else { return }
                        messages = dbManager.getMessages(limit: pageSize, offset: offset)
                        currentOffset = offset + messages.count
                        jumpOffset = nil
                        if let targetId = highlightedMessageId {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                withAnimation {
                                    proxy.scrollTo(targetId, anchor: .center)
                                }
                            }
                        }
                    }
                }

                // Bottom Telegram Input Bar (Matches Android ChatScreen bottomBar)
                HStack(spacing: 10) {
                    Image(systemName: "paperclip")
                        .font(.system(size: 20))
                        .foregroundColor(.gray)
                        .padding(.horizontal, 6)

                    HStack {
                        Text("Message")
                            .font(.system(size: 15))
                            .foregroundColor(.gray)
                        Spacer()
                        Image(systemName: "face.smiling")
                            .font(.system(size: 20))
                            .foregroundColor(.gray)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(StrangerColors.telegramInboundBubble)
                    .clipShape(Capsule())

                    Image(systemName: "mic")
                        .font(.system(size: 20))
                        .foregroundColor(StrangerColors.telegramBlue)
                        .padding(.horizontal, 6)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .background(StrangerColors.telegramDarkBar.opacity(0.95))
            }
        }
        .fullScreenCover(item: $selectedExplorerMessage) { msg in
            MediaExplorerSheet(
                message: msg,
                dbManager: dbManager,
                onClose: { selectedExplorerMessage = nil },
                onJumpToMessage: nil
            )
        }
        .onAppear {
            if messages.isEmpty {
                messages = dbManager.getMessages(limit: pageSize, offset: 0)
                currentOffset = messages.count
            }
            Task {
                avatarURL = await dbManager.downloadMedia(key: "avatar.jpg")
                chatBgURL = await dbManager.downloadMedia(key: "chat-bg.png")
            }
        }
    }

    private func loadMoreMessages() {
        guard !isLoadingMore else { return }
        isLoadingMore = true
        let more = dbManager.getMessages(limit: pageSize, offset: currentOffset)
        if !more.isEmpty {
            messages.append(contentsOf: more)
            currentOffset += more.count
        }
        isLoadingMore = false
    }

    private func jumpToParent(_ parentId: Int, proxy: ScrollViewProxy) {
        highlightedMessageId = parentId
        if messages.contains(where: { $0.id == parentId }) {
            withAnimation {
                proxy.scrollTo(parentId, anchor: .center)
            }
        } else {
            let offset = dbManager.getMessageOffset(messageId: parentId)
            jumpOffset = max(0, offset - 15)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            if highlightedMessageId == parentId {
                highlightedMessageId = nil
            }
        }
    }
}

struct DateHeaderPill: View {
    let date: String

    var body: some View {
        Text(date)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.55))
            .clipShape(Capsule())
            .padding(.vertical, 6)
    }
}

struct AvatarImageView: View {
    let avatarURL: URL?
    let size: CGFloat
    let iconSize: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(StrangerColors.profileAvatarPink)
                .frame(width: size, height: size)

            if let url = avatarURL, let uiImage = UIImage(contentsOfFile: url.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: iconSize))
                    .foregroundColor(.white)
            }
        }
    }
}

// MARK: - Chat Bubble View (Matches Android ChatBubbles.kt)

struct MessageBubbleView: View {
    let message: ChatMessage
    let dbManager: DatabaseManager
    var isHighlighted: Bool = false
    var onMediaTap: (() -> Void)? = nil
    var onReplyTap: ((Int) -> Void)? = nil

    var body: some View {
        let content = message.parsedContent
        let effMediaType = message.mediaType ?? content.media_type
        let isSticker = content.sticker != nil
        let isRoundedVideo = effMediaType == "rounded_video"
        let hasText = (message.textPlain?.isEmpty == false) && !isSticker

        HStack {
            if message.isSentByMe { Spacer(minLength: 55) }

            VStack(alignment: .leading, spacing: 6) {
                // Reply Preview
                if let replySender = message.replyToSender, let replyText = message.replyToText {
                    Button {
                        if let pid = message.parentLocalId {
                            onReplyTap?(pid)
                        }
                    } label: {
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 1.5)
                                .fill(Color.white)
                                .frame(width: 3, height: 30)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(replySender)
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                Text(replyText)
                                    .font(.system(size: 12))
                                    .foregroundColor(.white.opacity(0.8))
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(6)
                        .background(Color.white.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }

                // Media / Special Content
                if let contact = content.contact_information {
                    ContactCardView(contact: contact)
                } else if let loc = content.location_information {
                    LocationCardView(location: loc, dateString: nil)
                } else if isRoundedVideo, let videoKey = content.file {
                    RoundedVideoAttachmentView(
                        key: videoKey,
                        thumbKey: content.thumbnail,
                        dbManager: dbManager,
                        onTap: onMediaTap
                    )
                } else if effMediaType == "voice_message" || effMediaType == "voice", let voiceKey = content.file {
                    VoiceWaveformBubbleView(
                        key: voiceKey,
                        durationText: message.formattedDuration,
                        dbManager: dbManager
                    )
                } else if effMediaType == "audio" || effMediaType == "music", let audioKey = content.file {
                    AudioTrackBubbleView(
                        key: audioKey,
                        title: content.title ?? content.file_name,
                        durationText: message.formattedDuration,
                        fileSize: content.file_size ?? 0,
                        dbManager: dbManager
                    )
                } else if let mediaKey = content.photo ?? content.file ?? content.sticker {
                    MediaAttachmentView(
                        key: mediaKey,
                        thumbKey: content.thumbnail,
                        title: content.title ?? content.file_name,
                        mediaType: effMediaType,
                        fileSize: content.file_size ?? content.photo_file_size ?? 0,
                        durationText: message.formattedDuration,
                        dbManager: dbManager,
                        onMediaTap: onMediaTap
                    )
                }

                // Text Content
                if hasText, let text = message.textPlain {
                    Text(text)
                        .font(.system(size: 16))
                        .foregroundColor(.white)
                        .fixedSize(horizontal: false, vertical: true)
                }

                // Timestamp + Outgoing Checkmarks
                HStack(spacing: 3) {
                    Spacer(minLength: 0)
                    Text(message.formattedTime)
                        .font(.system(size: 11))
                        .foregroundColor(.white.opacity(0.7))
                    if message.isSentByMe {
                        Image(systemName: "checkmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white.opacity(0.7))
                    }
                }
            }
            .padding(isSticker || isRoundedVideo ? 4 : 10)
            .frame(maxWidth: 280, alignment: .leading)
            .background {
                if !isSticker && !isRoundedVideo {
                    if message.isSentByMe {
                        LinearGradient(
                            colors: [StrangerColors.telegramPurple, StrangerColors.telegramBlue],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    } else {
                        StrangerColors.telegramInboundBubble
                    }
                } else {
                    Color.clear
                }
            }
            .overlay {
                if isHighlighted {
                    RoundedRectangle(cornerRadius: 18)
                        .fill(Color.white.opacity(0.22))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 18))

            if !message.isSentByMe { Spacer(minLength: 55) }
        }
    }
}

// MARK: - Voice Waveform & Audio Track Bubbles (Matches Android VoiceBubble / AudioBubble)

struct VoiceWaveformBubbleView: View {
    let key: String
    let durationText: String
    let dbManager: DatabaseManager

    @ObservedObject private var audioManager = AudioPlayerManager.shared
    @State private var localURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    private let barHeights: [CGFloat] = [8, 14, 10, 18, 12, 15, 9]

    var body: some View {
        let isPlayingThis = localURL != nil && audioManager.isPlaying && audioManager.currentTrackURL == localURL
        let playProgress = isPlayingThis ? audioManager.progress : 0.0

        HStack(spacing: 10) {
            Button {
                if let url = localURL {
                    audioManager.togglePlay(url: url, title: "Voice message")
                } else if !isDownloading {
                    isDownloading = true
                    Task {
                        localURL = await dbManager.downloadMedia(key: key) { pct in
                            Task { @MainActor in progress = pct }
                        }
                        isDownloading = false
                        if let url = localURL {
                            audioManager.togglePlay(url: url, title: "Voice message")
                        }
                    }
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.15))
                        .frame(width: 38, height: 38)
                    if isDownloading {
                        ProgressView().tint(.white)
                    } else if localURL == nil {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.white)
                    } else {
                        Image(systemName: isPlayingThis ? "pause.fill" : "play.fill")
                            .font(.system(size: 16))
                            .foregroundColor(.white)
                    }
                }
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 2.5) {
                    ForEach(0..<32, id: \.self) { i in
                        let h = barHeights[i % barHeights.count]
                        let isPast = Double(i) / 32.0 < playProgress
                        RoundedRectangle(cornerRadius: 1.5)
                            .fill(isPast ? Color.white : Color.white.opacity(0.35))
                            .frame(width: 2.5, height: h)
                    }
                }
                .frame(height: 20)

                Text(isPlayingThis ? audioManager.formattedCurrentTime : durationText)
                    .font(.system(size: 11))
                    .foregroundColor(.white.opacity(0.75))
            }
        }
        .padding(.vertical, 4)
        .onAppear {
            if dbManager.isMediaDownloaded(key) {
                localURL = dbManager.localMediaURL(for: key)
            }
        }
    }
}

struct AudioTrackBubbleView: View {
    let key: String
    let title: String?
    let durationText: String
    let fileSize: Int64
    let dbManager: DatabaseManager

    @ObservedObject private var audioManager = AudioPlayerManager.shared
    @State private var localURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    var body: some View {
        let isPlayingThis = localURL != nil && audioManager.isPlaying && audioManager.currentTrackURL == localURL
        let trackTitle = title ?? (key.components(separatedBy: "/").last ?? "Audio File")

        HStack(spacing: 12) {
            Button {
                if let url = localURL {
                    audioManager.togglePlay(url: url, title: trackTitle)
                } else if !isDownloading {
                    isDownloading = true
                    Task {
                        localURL = await dbManager.downloadMedia(key: key) { pct in
                            Task { @MainActor in progress = pct }
                        }
                        isDownloading = false
                        if let url = localURL {
                            audioManager.togglePlay(url: url, title: trackTitle)
                        }
                    }
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.12))
                        .frame(width: 42, height: 42)
                    if isDownloading {
                        ProgressView().tint(.white)
                    } else if localURL == nil {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.white)
                    } else {
                        Image(systemName: isPlayingThis ? "pause.fill" : "play.fill")
                            .font(.system(size: 18))
                            .foregroundColor(.white)
                    }
                }
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 4) {
                Text(trackTitle)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .lineLimit(1)

                ProgressView(value: isPlayingThis ? audioManager.progress : 0.0, total: 1.0)
                    .tint(.white)

                HStack {
                    Text(isPlayingThis ? audioManager.formattedCurrentTime : durationText)
                        .font(.system(size: 11))
                        .foregroundColor(.white.opacity(0.65))
                    Spacer()
                    if fileSize > 0 {
                        Text(formatFileSize(fileSize))
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.65))
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .onAppear {
            if dbManager.isMediaDownloaded(key) {
                localURL = dbManager.localMediaURL(for: key)
            }
        }
    }
}

struct RoundedVideoAttachmentView: View {
    let key: String
    let thumbKey: String?
    let dbManager: DatabaseManager
    var onTap: (() -> Void)? = nil

    @State private var localURL: URL?
    @State private var thumbURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.white.opacity(0.08))
                .frame(width: 220, height: 220)

            if let tURL = thumbURL ?? localURL, let uiImg = UIImage(contentsOfFile: tURL.path) {
                Image(uiImage: uiImg)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 220, height: 220)
                    .clipShape(Circle())
            }

            if isDownloading {
                VStack(spacing: 4) {
                    ProgressView().tint(.white)
                    Text("\(progress)%").font(.caption.bold()).foregroundColor(.white)
                }
            } else {
                Image(systemName: localURL == nil ? "arrow.down.circle.fill" : "play.circle.fill")
                    .font(.system(size: 42))
                    .foregroundColor(.white.opacity(0.85))
            }
        }
        .frame(width: 220, height: 220)
        .clipShape(Circle())
        .onTapGesture {
            if localURL != nil {
                onTap?()
            } else if !isDownloading {
                isDownloading = true
                Task {
                    localURL = await dbManager.downloadMedia(key: key) { pct in
                        Task { @MainActor in progress = pct }
                    }
                    isDownloading = false
                    if localURL != nil { onTap?() }
                }
            }
        }
        .onAppear {
            if dbManager.isMediaDownloaded(key) {
                localURL = dbManager.localMediaURL(for: key)
            }
            if let tk = thumbKey {
                if dbManager.isMediaDownloaded(tk) {
                    thumbURL = dbManager.localMediaURL(for: tk)
                } else {
                    Task { thumbURL = await dbManager.downloadMedia(key: tk) }
                }
            }
        }
    }
}

struct MediaAttachmentView: View {
    let key: String
    var thumbKey: String? = nil
    let title: String?
    let mediaType: String?
    let fileSize: Int64
    var durationText: String = "0:00"
    let dbManager: DatabaseManager
    var onMediaTap: (() -> Void)? = nil

    @State private var localURL: URL?
    @State private var thumbURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    var body: some View {
        Group {
            if let url = localURL {
                if key.hasSuffix(".jpg") || key.hasSuffix(".jpeg") || key.hasSuffix(".png") || key.hasSuffix(".webp") || mediaType == "photo" {
                    if let uiImage = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: 260, maxHeight: 260)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .onTapGesture { onMediaTap?() }
                    }
                } else if mediaType == "video" || key.hasSuffix(".mp4") {
                    ZStack(alignment: .topTrailing) {
                        if let tURL = thumbURL, let thumbImg = UIImage(contentsOfFile: tURL.path) {
                            Image(uiImage: thumbImg)
                                .resizable()
                                .scaledToFill()
                                .frame(maxWidth: 260, maxHeight: 220)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        } else {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color.black.opacity(0.4))
                                .frame(width: 240, height: 180)
                        }
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 44))
                            .foregroundColor(.white.opacity(0.9))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        Text(durationText)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.6))
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                            .padding(8)
                    }
                    .frame(maxWidth: 260, maxHeight: 220)
                    .onTapGesture { onMediaTap?() }
                } else {
                    ShareLink(item: url) {
                        HStack(spacing: 10) {
                            ZStack {
                                Circle()
                                    .fill(Color.white.opacity(0.12))
                                    .frame(width: 40, height: 40)
                                Image(systemName: "doc.fill")
                                    .foregroundColor(.white)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(title ?? key.components(separatedBy: "/").last ?? "File")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(formatFileSize(fileSize))
                                    .font(.system(size: 11))
                                    .foregroundColor(.white.opacity(0.7))
                            }
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }
            } else {
                Button {
                    guard !isDownloading else { return }
                    isDownloading = true
                    Task {
                        localURL = await dbManager.downloadMedia(key: key) { pct in
                            Task { @MainActor in progress = pct }
                        }
                        isDownloading = false
                    }
                } label: {
                    ZStack {
                        if let tURL = thumbURL, let thumbImg = UIImage(contentsOfFile: tURL.path), (mediaType == "photo" || mediaType == "video") {
                            Image(uiImage: thumbImg)
                                .resizable()
                                .scaledToFill()
                                .frame(maxWidth: 260, maxHeight: 220)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .overlay(Color.black.opacity(0.4).clipShape(RoundedRectangle(cornerRadius: 12)))
                            if isDownloading {
                                VStack(spacing: 6) {
                                    ProgressView().tint(.white)
                                    Text("\(progress)%").font(.caption.bold()).foregroundColor(.white)
                                }
                            } else {
                                Image(systemName: "arrow.down.circle.fill")
                                    .font(.system(size: 38))
                                    .foregroundColor(.white)
                            }
                        } else {
                            HStack(spacing: 10) {
                                if isDownloading {
                                    ProgressView().tint(.white)
                                    Text("Downloading \(progress)%")
                                        .font(.caption)
                                        .foregroundColor(.white)
                                } else {
                                    Image(systemName: "arrow.down.circle.fill")
                                        .font(.title3)
                                        .foregroundColor(.white)
                                    VStack(alignment: .leading) {
                                        Text(title ?? key.components(separatedBy: "/").last ?? "Media")
                                            .font(.subheadline.bold())
                                            .foregroundColor(.white)
                                            .lineLimit(1)
                                        if fileSize > 0 {
                                            Text(formatFileSize(fileSize))
                                                .font(.caption2)
                                                .foregroundColor(.white.opacity(0.7))
                                        }
                                    }
                                }
                            }
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.white.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .onAppear {
            if dbManager.isMediaDownloaded(key) {
                localURL = dbManager.localMediaURL(for: key)
            }
            if let tk = thumbKey {
                if dbManager.isMediaDownloaded(tk) {
                    thumbURL = dbManager.localMediaURL(for: tk)
                } else {
                    Task {
                        thumbURL = await dbManager.downloadMedia(key: tk)
                    }
                }
            }
        }
    }
}

// MARK: - Search Sheet (Matches Android SearchScreen with Jump-to-Message & Calendar Date Picker)

struct SearchSheetView: View {
    @ObservedObject var dbManager: DatabaseManager
    let onJumpToMessage: (Int) -> Void
    let onJumpToDate: (Date) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var results: [ChatMessage] = []
    @State private var showDatePicker = false
    @State private var selectedDate = Date()
    @State private var dateBounds: (minDate: Date, maxDate: Date)? = nil

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if results.isEmpty && query.count >= 2 {
                    VStack(spacing: 12) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 54))
                            .foregroundColor(.white.opacity(0.12))
                        Text("No messages found")
                            .font(.headline)
                            .foregroundColor(.gray)
                        Text("Try a different search term")
                            .font(.subheadline)
                            .foregroundColor(.gray.opacity(0.6))
                    }
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(results) { msg in
                                Button {
                                    onJumpToMessage(msg.id)
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack {
                                            Text(msg.sender)
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundColor(msg.isSentByMe ? StrangerColors.telegramBlue : StrangerColors.telegramPurple)
                                            Spacer()
                                            Text(msg.formattedSearchDate)
                                                .font(.system(size: 11))
                                                .foregroundColor(.gray)
                                        }
                                        Text(msg.textPlain ?? "")
                                            .font(.system(size: 15))
                                            .foregroundColor(.white.opacity(0.85))
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                    }
                                    .padding(16)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)

                                Divider()
                                    .background(Color.white.opacity(0.1))
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search...")
            .onChange(of: query) { newValue in
                if newValue.count >= 2 {
                    results = dbManager.searchMessages(query: newValue)
                } else {
                    results = []
                }
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "arrow.left")
                            .foregroundColor(.white)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showDatePicker = true
                    } label: {
                        Image(systemName: "calendar")
                            .foregroundColor(.white)
                    }
                }
            }
            .sheet(isPresented: $showDatePicker) {
                NavigationStack {
                    VStack(spacing: 20) {
                        if let bounds = dateBounds {
                            DatePicker(
                                "Select Date",
                                selection: $selectedDate,
                                in: bounds.minDate...bounds.maxDate,
                                displayedComponents: [.date]
                            )
                            .datePickerStyle(.graphical)
                            .tint(StrangerColors.telegramBlue)
                            .padding()
                        } else {
                            DatePicker(
                                "Select Date",
                                selection: $selectedDate,
                                displayedComponents: [.date]
                            )
                            .datePickerStyle(.graphical)
                            .tint(StrangerColors.telegramBlue)
                            .padding()
                        }
                        Spacer()
                    }
                    .background(Color.black.ignoresSafeArea())
                    .navigationTitle("Jump to Date")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { showDatePicker = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Jump") {
                                showDatePicker = false
                                onJumpToDate(selectedDate)
                            }
                            .bold()
                        }
                    }
                }
                .presentationDetents([.medium, .large])
            }
        }
        .onAppear {
            if let bounds = dbManager.getDateRange() {
                dateBounds = bounds
                selectedDate = bounds.maxDate
            }
        }
    }
}

// MARK: - Profile Sheet (Matches Android ProfileScreen)

struct ProfileSheetView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    let onChangePasscode: () -> Void
    let onJumpToMessage: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selectedTab = "Media"
    @State private var statusMessage: String?
    @State private var isCheckingUpdate = false
    @State private var avatarURL: URL?
    @State private var tabItems: [String: [ChatMessage]] = [:]
    @State private var selectedExplorerMessage: ChatMessage?

    private let tabs = ["Media", "Files", "Music", "Voice", "Location", "Contact"]
    private let mediaGridColumns = [
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2)
    ]

    var body: some View {
        let items = tabItems[selectedTab] ?? []

        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    // Profile Header
                    VStack(spacing: 16) {
                        AvatarImageView(avatarURL: avatarURL, size: 100, iconSize: 46)
                            .padding(.top, 12)

                        VStack(spacing: 4) {
                            Text("Stranger")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(.white)
                            Text("last seen a long time ago")
                                .font(.system(size: 14))
                                .foregroundColor(.gray)
                        }

                        // Action Buttons Row (Change Passcode & Check Updates)
                        HStack(spacing: 12) {
                            Button(action: onChangePasscode) {
                                VStack(spacing: 4) {
                                    Image(systemName: "lock")
                                        .font(.system(size: 18))
                                    Text("Change Passcode")
                                        .font(.system(size: 12))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 64)
                                .background(StrangerColors.telegramDarkBar)
                                .clipShape(Capsule())
                            }

                            Button {
                                guard !isCheckingUpdate else { return }
                                isCheckingUpdate = true
                                Task {
                                    let update = await updateManager.checkForUpdate()
                                    isCheckingUpdate = false
                                    if update == nil {
                                        statusMessage = "You're on the latest version (v\(updateManager.currentVersionName))"
                                    } else {
                                        dismiss()
                                    }
                                }
                            } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.system(size: 18))
                                    Text(isCheckingUpdate ? "Checking…" : "Check Updates")
                                        .font(.system(size: 12))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 64)
                                .background(StrangerColors.telegramDarkBar)
                                .clipShape(Capsule())
                            }
                        }
                        .padding(.horizontal, 16)

                        if let statusMessage = statusMessage {
                            Text(statusMessage)
                                .font(.caption)
                                .foregroundColor(.green)
                        }

                        // Info Cards (Mobile & Bio)
                        VStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("9715278651")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                                Text("Mobile")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .padding(.horizontal, 16)
                            .background(StrangerColors.telegramDarkBar)
                            .clipShape(RoundedRectangle(cornerRadius: 12))

                            VStack(alignment: .leading, spacing: 2) {
                                Text("You call me a stranger, but I’m still the name your heart remembers.")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(2)
                                Text("Bio")
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                            }
                            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                            .padding(.horizontal, 16)
                            .background(StrangerColors.telegramDarkBar)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 12)
                    }

                    // Sticky Tab Section + MiniPlayer
                    Section {
                        if selectedTab == "Media" {
                            // 3-Column Square Media Grid (Matches Android ProfileMediaItem)
                            LazyVGrid(columns: mediaGridColumns, spacing: 2) {
                                ForEach(items) { item in
                                    ProfileMediaGridCell(
                                        message: item,
                                        dbManager: dbManager,
                                        onTap: {
                                            selectedExplorerMessage = item
                                        },
                                        onShowInChat: {
                                            onJumpToMessage(item.id)
                                        }
                                    )
                                }
                            }
                            .padding(.top, 8)
                        } else if selectedTab == "Location" {
                            LazyVStack(spacing: 10) {
                                ForEach(items) { item in
                                    if let loc = item.parsedContent.location_information {
                                        LocationCardView(location: loc, dateString: item.formattedSearchDate)
                                            .padding(.horizontal, 16)
                                            .contextMenu {
                                                Button {
                                                    onJumpToMessage(item.id)
                                                } label: {
                                                    Label("Show in Chat", systemImage: "bubble.left")
                                                }
                                            }
                                    }
                                }
                            }
                            .padding(.top, 8)
                        } else if selectedTab == "Contact" {
                            LazyVStack(spacing: 10) {
                                ForEach(items) { item in
                                    if let contact = item.parsedContent.contact_information {
                                        ContactCardView(contact: contact, dateString: item.formattedSearchDate)
                                            .padding(.horizontal, 16)
                                            .contextMenu {
                                                Button {
                                                    onJumpToMessage(item.id)
                                                } label: {
                                                    Label("Show in Chat", systemImage: "bubble.left")
                                                }
                                            }
                                    }
                                }
                            }
                            .padding(.top, 8)
                        } else {
                            // Files, Music, Voice List inside rounded container (#1C1C1D)
                            LazyVStack(spacing: 0) {
                                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                                    ProfileFileRowView(
                                        message: item,
                                        dbManager: dbManager,
                                        showDivider: index < items.count - 1,
                                        onShowInChat: {
                                            onJumpToMessage(item.id)
                                        }
                                    )
                                }
                            }
                            .background(StrangerColors.telegramDarkBar)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                            .padding(.bottom, 24)
                        }
                    } header: {
                        VStack(spacing: 6) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 4) {
                                    ForEach(tabs, id: \.self) { tab in
                                        let isSelected = selectedTab == tab
                                        Button {
                                            selectedTab = tab
                                            loadTabIfNeeded(tab)
                                        } label: {
                                            Text(tab)
                                                .font(.system(size: 14, weight: .medium))
                                                .foregroundColor(
                                                    isSelected
                                                        ? StrangerColors.accentBlue
                                                        : Color(red: 0.557, green: 0.557, blue: 0.576)
                                                )
                                                .padding(.horizontal, 18)
                                                .padding(.vertical, 6)
                                                .background(
                                                    isSelected
                                                        ? StrangerColors.accentBlue.opacity(0.15)
                                                        : Color.clear
                                                )
                                                .clipShape(Capsule())
                                        }
                                    }
                                }
                                .padding(.horizontal, 6)
                                .padding(.vertical, 6)
                            }
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())

                            MiniPlayerBar()
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                        .background(Color.black.opacity(0.85))
                    }
                }
            }
            .background(Color.black.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "arrow.left")
                            .foregroundColor(.white)
                    }
                }
            }
            .fullScreenCover(item: $selectedExplorerMessage) { msg in
                MediaExplorerSheet(
                    message: msg,
                    dbManager: dbManager,
                    onClose: { selectedExplorerMessage = nil },
                    onJumpToMessage: { targetId in
                        selectedExplorerMessage = nil
                        onJumpToMessage(targetId)
                    }
                )
            }
        }
        .onAppear {
            loadTabIfNeeded("Media")
            Task {
                avatarURL = await dbManager.downloadMedia(key: "avatar.jpg")
            }
        }
    }

    private func loadTabIfNeeded(_ tab: String) {
        if tabItems[tab] == nil {
            tabItems[tab] = dbManager.getMediaMessages(category: tab, limit: 300, offset: 0)
        }
    }
}

// MARK: - Profile 3-Column Square Media Grid Cell (Matches Android ProfileMediaItem)

struct ProfileMediaGridCell: View {
    let message: ChatMessage
    let dbManager: DatabaseManager
    let onTap: () -> Void
    let onShowInChat: () -> Void

    @State private var previewURL: URL?
    @State private var isDownloading = false

    private var previewKey: String? {
        message.parsedContent.photo
            ?? message.parsedContent.thumbnail
            ?? message.parsedContent.file
    }

    private var isVideo: Bool {
        let mt = message.mediaType ?? message.parsedContent.media_type
        return mt == "video" || mt == "rounded_video"
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottomLeading) {
                Color.white.opacity(0.05)

                if let url = previewURL, let uiImage = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: uiImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.width)
                        .clipped()
                } else if isDownloading {
                    ProgressView()
                        .tint(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Image(systemName: "arrow.down")
                        .font(.system(size: 18))
                        .foregroundColor(.white.opacity(0.3))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                if isVideo {
                    HStack(spacing: 3) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 9))
                            .foregroundColor(.white)
                        Text(message.formattedDuration)
                            .font(.system(size: 10, weight: .medium))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .padding(4)
                }
            }
            .frame(width: geo.size.width, height: geo.size.width)
            .contentShape(Rectangle())
            .onTapGesture {
                onTap()
            }
            .contextMenu {
                Button {
                    onShowInChat()
                } label: {
                    Label("Show in Chat", systemImage: "bubble.left")
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .task(id: message.id) {
            guard let key = previewKey else { return }
            if dbManager.isMediaDownloaded(key) {
                previewURL = dbManager.localMediaURL(for: key)
            } else {
                isDownloading = true
                previewURL = await dbManager.downloadMedia(key: key)
                isDownloading = false
            }
        }
    }
}

// MARK: - Full-Screen Media Explorer (Photo / Video Viewer)

struct MediaExplorerSheet: View {
    let message: ChatMessage
    let dbManager: DatabaseManager
    let onClose: () -> Void
    let onJumpToMessage: ((Int) -> Void)?

    @State private var fullMediaURL: URL?
    @State private var thumbURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    private var mediaKey: String? {
        message.parsedContent.file ?? message.parsedContent.photo
    }

    private var isVideo: Bool {
        let mt = message.mediaType ?? message.parsedContent.media_type
        return mt == "video" || mt == "rounded_video"
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if isVideo, let url = fullMediaURL {
                VideoPlayer(player: AVPlayer(url: url))
                    .ignoresSafeArea()
            } else if let url = fullMediaURL ?? thumbURL, let uiImage = UIImage(contentsOfFile: url.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if isDownloading && fullMediaURL == nil {
                VStack(spacing: 8) {
                    ProgressView()
                        .tint(.white)
                        .scaleEffect(1.3)
                    Text("Downloading \(progress)%")
                        .font(.caption.bold())
                        .foregroundColor(.white)
                }
                .padding(16)
                .background(Color.black.opacity(0.6))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            VStack {
                HStack {
                    Button(action: onClose) {
                        Image(systemName: "arrow.left")
                            .font(.title3.weight(.semibold))
                            .foregroundColor(.white)
                            .padding(12)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    Spacer()
                    Text(message.formattedSearchDate)
                        .font(.subheadline.bold())
                        .foregroundColor(.white)
                    Spacer()
                    HStack(spacing: 8) {
                        if let jump = onJumpToMessage {
                            Button {
                                jump(message.id)
                            } label: {
                                Image(systemName: "bubble.left")
                                    .font(.title3)
                                    .foregroundColor(.white)
                                    .padding(10)
                                    .background(Color.black.opacity(0.5))
                                    .clipShape(Circle())
                            }
                        }
                        if let url = fullMediaURL {
                            ShareLink(item: url) {
                                Image(systemName: "square.and.arrow.up")
                                    .font(.title3)
                                    .foregroundColor(.white)
                                    .padding(10)
                                    .background(Color.black.opacity(0.5))
                                    .clipShape(Circle())
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
            }
        }
        .task {
            if let tk = message.parsedContent.thumbnail, dbManager.isMediaDownloaded(tk) {
                thumbURL = dbManager.localMediaURL(for: tk)
            }
            guard let key = mediaKey else { return }
            if dbManager.isMediaDownloaded(key) {
                fullMediaURL = dbManager.localMediaURL(for: key)
            } else {
                isDownloading = true
                fullMediaURL = await dbManager.downloadMedia(key: key) { pct in
                    Task { @MainActor in progress = pct }
                }
                isDownloading = false
            }
        }
    }
}

// MARK: - Profile File / Music / Voice Row (Matches Android ProfileFileItem)

struct ProfileFileRowView: View {
    let message: ChatMessage
    let dbManager: DatabaseManager
    let showDivider: Bool
    let onShowInChat: () -> Void

    @ObservedObject private var audioManager = AudioPlayerManager.shared
    @State private var localFileURL: URL?
    @State private var localThumbURL: URL?
    @State private var isDownloading = false
    @State private var downloadProgress = 0

    private var fileKey: String? {
        message.parsedContent.file ?? message.parsedContent.photo
    }

    private var thumbKey: String? {
        message.parsedContent.thumbnail
    }

    private var isAudio: Bool {
        let mt = message.mediaType ?? message.parsedContent.media_type
        return mt == "audio" || mt == "voice_message" || mt == "music" || mt == "voice"
    }

    private var displayTitle: String {
        if let title = message.parsedContent.title, !title.isEmpty {
            return title
        }
        if let fileName = message.parsedContent.file_name, !fileName.isEmpty {
            return fileName
        }
        if let key = fileKey {
            return key.components(separatedBy: "/").last ?? "Unknown"
        }
        return "Unknown"
    }

    var body: some View {
        let isPlayingThis = localFileURL != nil && audioManager.isPlaying && audioManager.currentTrackURL == localFileURL

        VStack(spacing: 0) {
            Button {
                if let url = localFileURL {
                    if isAudio {
                        audioManager.togglePlay(url: url, title: displayTitle)
                    }
                } else if let key = fileKey, !isDownloading {
                    isDownloading = true
                    Task {
                        localFileURL = await dbManager.downloadMedia(key: key) { pct in
                            Task { @MainActor in downloadProgress = pct }
                        }
                        isDownloading = false
                        if isAudio, let url = localFileURL {
                            audioManager.togglePlay(url: url, title: displayTitle)
                        }
                    }
                }
            } label: {
                HStack(spacing: 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.white.opacity(0.05))
                            .frame(width: 48, height: 48)

                        if let tURL = localThumbURL, let uiImg = UIImage(contentsOfFile: tURL.path) {
                            Image(uiImage: uiImg)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 48, height: 48)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        } else if isDownloading {
                            ProgressView()
                                .tint(.white)
                        } else {
                            let iconName: String = {
                                if localFileURL == nil { return "arrow.down" }
                                if isAudio { return isPlayingThis ? "pause.fill" : "play.fill" }
                                return "doc.text.fill"
                            }()
                            Image(systemName: iconName)
                                .font(.system(size: 20))
                                .foregroundColor(.white.opacity(0.65))
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(displayTitle)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.white)
                            .lineLimit(1)

                        Text("\(formatFileSize(message.parsedContent.file_size ?? 0)) • \(message.formattedSearchDate)")
                            .font(.system(size: 13))
                            .foregroundColor(.gray)
                            .lineLimit(1)
                    }

                    Spacer()

                    if let url = localFileURL, !isAudio {
                        ShareLink(item: url) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 16))
                                .foregroundColor(.white.opacity(0.6))
                                .padding(8)
                        }
                    }
                }
                .padding(12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button {
                    onShowInChat()
                } label: {
                    Label("Show in Chat", systemImage: "bubble.left")
                }
            }

            if showDivider {
                Divider()
                    .background(Color.white.opacity(0.05))
                    .padding(.leading, 76)
            }
        }
        .onAppear {
            if let key = fileKey, dbManager.isMediaDownloaded(key) {
                localFileURL = dbManager.localMediaURL(for: key)
            }
            if let tk = thumbKey {
                if dbManager.isMediaDownloaded(tk) {
                    localThumbURL = dbManager.localMediaURL(for: tk)
                } else {
                    Task {
                        localThumbURL = await dbManager.downloadMedia(key: tk)
                    }
                }
            }
        }
    }
}

// MARK: - Location & Contact Cards (Matches Android LocationBubble / ContactBubble)

struct LocationCardView: View {
    let location: LocationInfo
    var dateString: String? = nil

    var body: some View {
        let lat = location.latitude ?? 0.0
        let lon = location.longitude ?? 0.0

        Button {
            if let url = URL(string: "https://maps.apple.com/?ll=\(lat),\(lon)&q=\(lat),\(lon)") {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(StrangerColors.accentBlue.opacity(0.2))
                        .frame(width: 44, height: 44)
                    Image(systemName: "location.fill")
                        .foregroundColor(StrangerColors.accentBlue)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("Shared Location")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                    Text(String(format: "%.5f, %.5f", lat, lon))
                        .font(.system(size: 13))
                        .foregroundColor(.gray)
                    if let d = dateString, !d.isEmpty {
                        Text(d)
                            .font(.system(size: 11))
                            .foregroundColor(.gray.opacity(0.8))
                    }
                }
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.caption)
                    .foregroundColor(.gray)
            }
            .padding(12)
            .background(StrangerColors.telegramDarkBar)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

struct ContactCardView: View {
    let contact: ContactInfo
    var dateString: String? = nil

    var body: some View {
        let fullName = [contact.first_name ?? "", contact.last_name ?? ""]
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(StrangerColors.accentBlue.opacity(0.2))
                    .frame(width: 44, height: 44)
                Image(systemName: "person.crop.circle.fill")
                    .font(.system(size: 22))
                    .foregroundColor(StrangerColors.accentBlue)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(fullName.isEmpty ? "Contact" : fullName)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                if let phone = contact.phone_number, !phone.isEmpty {
                    Text(phone)
                        .font(.system(size: 13))
                        .foregroundColor(.gray)
                }
                if let d = dateString, !d.isEmpty {
                    Text(d)
                        .font(.system(size: 11))
                        .foregroundColor(.gray.opacity(0.8))
                }
            }
            Spacer()
        }
        .padding(12)
        .background(StrangerColors.telegramDarkBar)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}
