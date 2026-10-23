import SwiftUI
import AVKit

struct ContentView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    let passcodeManager: PasscodeManager
    @Binding var isUnlocked: Bool

    @State private var isChangingPasscode = false
    @State private var showSearch = false
    @State private var showProfile = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if isChangingPasscode {
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
                        onLock: { isUnlocked = false },
                        onOpenSearch: { showSearch = true },
                        onOpenProfile: { showProfile = true },
                        onChangePasscode: {
                            showProfile = false
                            isChangingPasscode = true
                        }
                    )
                    .sheet(isPresented: $showSearch) {
                        SearchSheetView(dbManager: dbManager)
                    }
                    .sheet(isPresented: $showProfile) {
                        ProfileSheetView(
                            dbManager: dbManager,
                            updateManager: updateManager,
                            onChangePasscode: {
                                showProfile = false
                                isChangingPasscode = true
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
}

// MARK: - Passcode View

struct PasscodeView: View {
    let isSetup: Bool
    let passcodeManager: PasscodeManager
    let onSuccess: (String) -> Bool
    var onCancel: (() -> Void)? = nil

    @State private var enteredPin = ""
    @State private var firstPin = ""
    @State private var isConfirming = false
    @State private var isError = false
    @State private var lockoutSeconds: Int64 = 0

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 28) {
            HStack {
                if let onCancel = onCancel {
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .font(.title3)
                            .foregroundColor(.white)
                            .padding()
                    }
                }
                Spacer()
            }

            Spacer()

            Image(systemName: "lock.fill")
                .font(.system(size: 44))
                .foregroundColor(.white)

            Text(titleText)
                .font(.title3.weight(.semibold))
                .foregroundColor(isError || lockoutSeconds > 0 ? .red : .white)

            HStack(spacing: 18) {
                ForEach(0..<4, id: \.self) { index in
                    Circle()
                        .fill(index < enteredPin.count ? (isError ? Color.red : Color.white) : Color.white.opacity(0.2))
                        .frame(width: 14, height: 14)
                }
            }
            .padding(.vertical, 8)

            Spacer()

            VStack(spacing: 18) {
                ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]], id: \.self) { row in
                    HStack(spacing: 28) {
                        ForEach(row, id: \.self) { digit in
                            KeypadButton(label: digit) {
                                handleDigit(digit)
                            }
                        }
                    }
                }
                HStack(spacing: 28) {
                    Color.clear.frame(width: 76, height: 76)
                    KeypadButton(label: "0") {
                        handleDigit("0")
                    }
                    Button {
                        if !enteredPin.isEmpty {
                            enteredPin.removeLast()
                        }
                    } label: {
                        Image(systemName: "delete.left")
                            .font(.title2)
                            .foregroundColor(.white)
                            .frame(width: 76, height: 76)
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
        }
        .onReceive(timer) { _ in
            lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
        }
    }

    private var titleText: String {
        if lockoutSeconds > 0 {
            return "Too many attempts. Try in \(lockoutSeconds)s"
        }
        if isError {
            return isSetup ? "Passcodes didn't match" : "Incorrect Passcode"
        }
        if isSetup {
            return isConfirming ? "Confirm New Passcode" : "Set a New Passcode"
        }
        return "Enter Passcode"
    }

    private func handleDigit(_ digit: String) {
        guard lockoutSeconds == 0, enteredPin.count < 4 else { return }
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
                triggerError()
                isConfirming = false
                firstPin = ""
            }
        } else {
            if !onSuccess(enteredPin) {
                lockoutSeconds = passcodeManager.getRemainingLockoutSeconds()
                triggerError()
            }
        }
    }

    private func triggerError() {
        isError = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            enteredPin = ""
            isError = false
        }
    }
}

struct KeypadButton: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 28, weight: .regular))
                .foregroundColor(.white)
                .frame(width: 76, height: 76)
                .background(Color.white.opacity(0.12))
                .clipShape(Circle())
        }
    }
}

// MARK: - Setup Progress View

struct SetupProgressView: View {
    let state: SetupState

    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
                .tint(Color(red: 0.25, green: 0.61, blue: 1.0))
                .scaleEffect(1.4)

            switch state {
            case .downloading(let pct):
                Text("Downloading encrypted archive… \(pct)%")
                    .foregroundColor(.white)
                    .font(.headline)
                ProgressView(value: Double(pct), total: 100)
                    .tint(Color(red: 0.25, green: 0.61, blue: 1.0))
                    .frame(width: 220)
            case .extracting:
                Text("Decrypting & extracting database…")
                    .foregroundColor(.white)
                    .font(.headline)
            default:
                Text("Preparing archive…")
                    .foregroundColor(.white)
                    .font(.headline)
            }
        }
    }
}

// MARK: - Main Chat View

struct MainChatView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    let onLock: () -> Void
    let onOpenSearch: () -> Void
    let onOpenProfile: () -> Void
    let onChangePasscode: () -> Void

    @State private var messages: [ChatMessage] = []
    @State private var currentOffset = 0
    @State private var isLoadingMore = false
    private let pageSize = 50

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack(spacing: 12) {
                Button(action: onOpenProfile) {
                    HStack(spacing: 10) {
                        Circle()
                            .fill(Color(red: 1.0, green: 0.5, blue: 0.67))
                            .frame(width: 38, height: 38)
                            .overlay {
                                Image(systemName: "person.fill")
                                    .foregroundColor(.white)
                            }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Stranger")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text("last seen a long time ago")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                }
                Spacer()
                Button(action: onLock) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                        .padding(8)
                }
                Button(action: onOpenSearch) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                        .padding(8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)

            // Message list (inverted like Telegram)
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(messages) { message in
                        MessageBubbleView(message: message, dbManager: dbManager)
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
        }
        .background(Color.black)
        .onAppear {
            if messages.isEmpty {
                messages = dbManager.getMessages(limit: pageSize, offset: 0)
                currentOffset = messages.count
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
}

struct MessageBubbleView: View {
    let message: ChatMessage
    let dbManager: DatabaseManager

    @State private var mediaURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    var body: some View {
        let content = message.parsedContent
        HStack {
            if message.isSentByMe { Spacer(minLength: 50) }

            VStack(alignment: .leading, spacing: 6) {
                if let replySender = message.replyToSender, let replyText = message.replyToText {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(replySender)
                            .font(.caption.bold())
                            .foregroundColor(Color(red: 0.25, green: 0.61, blue: 1.0))
                        Text(replyText)
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.8))
                            .lineLimit(1)
                    }
                    .padding(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }

                if let mediaKey = content.photo ?? content.file ?? content.sticker {
                    MediaAttachmentView(
                        key: mediaKey,
                        title: content.title ?? content.file_name,
                        mediaType: message.mediaType ?? content.media_type,
                        fileSize: content.file_size ?? content.photo_file_size ?? 0,
                        dbManager: dbManager
                    )
                }

                if let text = message.textPlain, !text.isEmpty {
                    Text(text)
                        .font(.body)
                        .foregroundColor(.white)
                }

                Text(message.formattedTime)
                    .font(.caption2)
                    .foregroundColor(.white.opacity(0.55))
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(10)
            .frame(maxWidth: 290, alignment: .leading)
            .background(
                message.isSentByMe
                    ? Color(red: 0.17, green: 0.36, blue: 0.62)
                    : Color(white: 0.14)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))

            if !message.isSentByMe { Spacer(minLength: 50) }
        }
    }
}

struct MediaAttachmentView: View {
    let key: String
    let title: String?
    let mediaType: String?
    let fileSize: Int64
    let dbManager: DatabaseManager

    @State private var localURL: URL?
    @State private var isDownloading = false
    @State private var progress = 0

    var body: some View {
        Group {
            if let url = localURL {
                if key.hasSuffix(".jpg") || key.hasSuffix(".jpeg") || key.hasSuffix(".png") || key.hasSuffix(".webp") {
                    if let uiImage = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: 260, maxHeight: 260)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                } else if mediaType == "video" || mediaType == "rounded_video" || key.hasSuffix(".mp4") {
                    VideoPlayer(player: AVPlayer(url: url))
                        .frame(height: 200)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                } else {
                    ShareLink(item: url) {
                        HStack(spacing: 10) {
                            Image(systemName: "doc.fill")
                                .foregroundColor(.white)
                            VStack(alignment: .leading) {
                                Text(title ?? key.components(separatedBy: "/").last ?? "File")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(formatFileSize(fileSize))
                                    .font(.caption2)
                                    .foregroundColor(.white.opacity(0.7))
                            }
                        }
                        .padding(8)
                        .background(Color.white.opacity(0.1))
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
                    HStack(spacing: 10) {
                        if isDownloading {
                            ProgressView()
                                .tint(.white)
                            Text("Downloading \(progress)%")
                                .font(.caption)
                                .foregroundColor(.white)
                        } else {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.title3)
                                .foregroundColor(.white)
                            VStack(alignment: .leading) {
                                Text(title ?? key.components(separatedBy: "/").last ?? "Media")
                                    .font(.subheadline)
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
        .onAppear {
            if dbManager.isMediaDownloaded(key) {
                localURL = dbManager.localMediaURL(for: key)
            }
        }
    }
}

// MARK: - Search Sheet

struct SearchSheetView: View {
    @ObservedObject var dbManager: DatabaseManager
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [ChatMessage] = []

    var body: some View {
        NavigationStack {
            List(results) { msg in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(msg.sender)
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                        Spacer()
                        Text(msg.formattedSearchDate)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                    Text(msg.textPlain ?? "")
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.8))
                        .lineLimit(2)
                }
                .listRowBackground(Color(white: 0.1))
            }
            .scrollContentBackground(.hidden)
            .background(Color.black)
            .searchable(text: $query, prompt: "Search messages…")
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
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Profile Sheet

struct ProfileSheetView: View {
    @ObservedObject var dbManager: DatabaseManager
    @ObservedObject var updateManager: UpdateManager
    let onChangePasscode: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selectedTab = "Media"
    @State private var statusMessage: String?
    @State private var isCheckingUpdate = false
    private let tabs = ["Media", "Files", "Music", "Voice", "Location", "Contact"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Circle()
                        .fill(Color(red: 1.0, green: 0.5, blue: 0.67))
                        .frame(width: 92, height: 92)
                        .overlay {
                            Image(systemName: "person.fill")
                                .font(.system(size: 42))
                                .foregroundColor(.white)
                        }
                        .padding(.top, 12)

                    Text("Stranger")
                        .font(.title2.bold())
                        .foregroundColor(.white)
                    Text("last seen a long time ago")
                        .font(.subheadline)
                        .foregroundColor(.gray)

                    HStack(spacing: 12) {
                        Button(action: onChangePasscode) {
                            Label("Change Passcode", systemImage: "lock.fill")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color(white: 0.14))
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
                            Label(isCheckingUpdate ? "Checking…" : "Check Updates", systemImage: "arrow.triangle.2.circlepath")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color(white: 0.14))
                                .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal, 16)

                    Button {
                        updateManager.addSideStoreSource()
                    } label: {
                        Label("Add Auto-Update Source to SideStore / AltStore", systemImage: "sparkles")
                            .font(.footnote.weight(.medium))
                            .foregroundColor(Color(red: 0.25, green: 0.61, blue: 1.0))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color(white: 0.11))
                            .clipShape(Capsule())
                    }
                    .padding(.horizontal, 16)

                    if let statusMessage = statusMessage {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundColor(.green)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("9715278651")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                        Text("Mobile")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color(white: 0.11))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("You call me a stranger, but I’m still the name your heart remembers.")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                        Text("Bio")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color(white: 0.11))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(tabs, id: \.self) { tab in
                                Button {
                                    selectedTab = tab
                                } label: {
                                    Text(tab)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundColor(selectedTab == tab ? Color(red: 0.25, green: 0.61, blue: 1.0) : .gray)
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 8)
                                        .background(selectedTab == tab ? Color(red: 0.25, green: 0.61, blue: 1.0).opacity(0.15) : Color.clear)
                                        .clipShape(Capsule())
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }

                    let items = dbManager.getMediaMessages(category: selectedTab, limit: 90)
                    LazyVStack(spacing: 10) {
                        ForEach(items) { item in
                            MessageBubbleView(message: item, dbManager: dbManager)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .background(Color.black)
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
