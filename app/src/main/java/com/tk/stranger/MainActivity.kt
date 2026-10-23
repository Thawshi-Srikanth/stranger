package com.tk.stranger

import android.content.ComponentName
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.insertSeparators
import androidx.paging.map
import com.tk.stranger.ui.screens.*
import com.tk.stranger.ui.theme.StrangerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var player: Player? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Prevent screenshots, screen recording, and recent-apps task switcher previews
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        val dbManager = DatabaseManager(this)
        val mediaManager = MediaManager(this)
        val passcodeManager = PasscodeManager(this)
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        var showOnboarding by mutableStateOf(!prefs.getBoolean("onboarding_finished", false))
        
        // Keep splash screen until the media player is ready.
        splashScreen.setKeepOnScreenCondition {
            player == null
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            player = controllerFuture.get()
        }, MoreExecutors.directExecutor())
        
        setContent {
            val setupState by dbManager.setupState.collectAsState(initial = SetupState.Idle)
            var isUnlocked by remember { mutableStateOf(false) }
            var isChangingPasscode by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current

            // Auto-lock the app whenever it moves to the background
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP && passcodeManager.hasPasscode()) {
                        isUnlocked = false
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Only download and decrypt the archive once unlocked with the valid PIN
            LaunchedEffect(isUnlocked) {
                if (isUnlocked) {
                    dbManager.setupDatabase()
                }
            }

            val currentPlayer = player
            if (currentPlayer == null) {
                // Loading screen while player connects (will be hidden by splashScreen)
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                return@setContent
            }

            var initialOffset by remember { mutableStateOf<Int?>(null) }
            var highlightedMessageId by remember { mutableStateOf<Int?>(null) }
            var isSearchActive by remember { mutableStateOf(false) }
            var isProfileActive by remember { mutableStateOf(false) }
            var showMediaExplorer by remember { mutableStateOf(false) }
            var initialMediaIndex by remember { mutableIntStateOf(0) }
            
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            val mediaPagingFlow = remember {
                Pager(PagingConfig(pageSize = 60)) {
                    dbManager.MediaPagingSource("Media")
                }.flow
            }
            val mediaItems = mediaPagingFlow.collectAsLazyPagingItems()

            val pagingFlow: Flow<PagingData<ChatListItem>> = remember(initialOffset) {
                Pager(
                    config = PagingConfig(
                        pageSize = 30,
                        enablePlaceholders = false,
                        initialLoadSize = 30,
                        prefetchDistance = 10
                    ),
                    initialKey = initialOffset ?: 0
                ) {
                    dbManager.MessagePagingSource()
                }.flow.map { pagingData ->
                    pagingData.map { ChatListItem.Message(it) }
                        .insertSeparators { before, after ->
                            val beforeDate = (before as? ChatListItem.Message)?.message?.formattedDate
                            val afterDate = (after as? ChatListItem.Message)?.message?.formattedDate
                            
                            if (before != null && (after == null || beforeDate != afterDate)) {
                                beforeDate?.let { ChatListItem.DateHeader(it) }
                            } else {
                                null
                            }
                        }
                }
            }
            
            fun jumpToMessage(id: Int, forceReload: Boolean = true) {
                scope.launch {
                    highlightedMessageId = id
                    if (forceReload) {
                        val offset = dbManager.getMessageOffset(id)
                        initialOffset = (offset - 25).coerceAtLeast(0)
                    }
                    isSearchActive = false
                    isProfileActive = false
                    delay(2000)
                    highlightedMessageId = null
                }
            }

            StrangerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showOnboarding) {
                            OnboardingScreen(
                                setupState = setupState,
                                onFinished = {
                                    prefs.edit().putBoolean("onboarding_finished", true).apply()
                                    showOnboarding = false
                                }
                            )
                        } else if (isChangingPasscode) {
                            PasscodeScreen(
                                isSetup = true,
                                onPasscodeSet = { pin ->
                                    passcodeManager.setPasscode(pin)
                                    isChangingPasscode = false
                                    true
                                },
                                onCancel = { isChangingPasscode = false }
                            )
                        } else if (!passcodeManager.hasPasscode()) {
                            // First-time archive unlock: user must enter the master PIN (0529)
                            // which cryptographically decrypts the AES-256-GCM R2 keys & database.
                            PasscodeScreen(
                                isSetup = false,
                                lockoutSecondsProvider = { passcodeManager.getRemainingLockoutSeconds() },
                                onPasscodeSet = { pin ->
                                    if (SecurityConfig.unlockWithPin(pin, this@MainActivity)) {
                                        passcodeManager.setPasscode(pin)
                                        isUnlocked = true
                                        true
                                    } else {
                                        // Record failed attempt in PasscodeManager for lockout protection
                                        passcodeManager.recordFailedAttempt()
                                        false
                                    }
                                }
                            )
                        } else if (!isUnlocked) {
                            PasscodeScreen(
                                isSetup = false,
                                lockoutSecondsProvider = { passcodeManager.getRemainingLockoutSeconds() },
                                onPasscodeSet = { pin ->
                                    if (passcodeManager.verifyPasscode(pin)) {
                                        val restored = SecurityConfig.restoreFromKeystore(this@MainActivity) ||
                                            SecurityConfig.unlockWithPin(pin, this@MainActivity)
                                        if (restored) {
                                            isUnlocked = true
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        false
                                    }
                                }
                            )
                        } else {
                            when (val state = setupState) {
                                is SetupState.Completed -> {
                                    ChatScreen(
                                        pagingFlow = pagingFlow,
                                        mediaManager = mediaManager,
                                        listState = listState,
                                        highlightedMessageId = highlightedMessageId,
                                        onSearchToggle = { isSearchActive = it },
                                        onProfileToggle = { isProfileActive = it },
                                        onLock = { isUnlocked = false },
                                        onMediaClick = { messageId ->
                                            scope.launch {
                                                initialMediaIndex =
                                                    dbManager.getMediaMessageIndex(
                                                        messageId,
                                                        "Media"
                                                    )
                                                showMediaExplorer = true
                                            }
                                        },
                                        onReplyClick = { id, reload ->
                                            jumpToMessage(
                                                id,
                                                reload
                                            )
                                        },
                                        exoPlayer = currentPlayer
                                    )
                                }
                                is SetupState.Error -> ErrorScreen(state.message)
                                else -> SetupScreen(state)
                            }

                            if (setupState is SetupState.Completed) {
                                AnimatedVisibility(
                                    visible = isSearchActive,
                                    enter = slideInVertically { it },
                                    exit = slideOutVertically { it }
                                ) {
                                    var searchQuery by remember { mutableStateOf("") }
                                    var searchResults by remember { mutableStateOf<List<ChatMessage>?>(null) }
                                    var isSearching by remember { mutableStateOf(false) }
                                    
                                    LaunchedEffect(searchQuery) {
                                        if (searchQuery.length >= 2) {
                                            isSearching = true
                                            delay(500)
                                            searchResults = dbManager.searchMessages(searchQuery)
                                            isSearching = false
                                        } else {
                                            searchResults = null
                                            isSearching = false
                                        }
                                    }

                                    SearchScreen(
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        results = searchResults ?: emptyList(),
                                        isSearching = isSearching,
                                        showEmptyState = searchResults?.isEmpty() == true && !isSearching,
                                        onClose = { isSearchActive = false },
                                        onResultClick = { jumpToMessage(it) },
                                        dbManager = dbManager,
                                        onDateSelected = { timestamp ->
                                            scope.launch {
                                                val offset = dbManager.getOffsetByDate(timestamp)
                                                initialOffset = (offset - 15).coerceAtLeast(0)
                                                isSearchActive = false
                                            }
                                        }
                                    )
                                }

                                AnimatedVisibility(
                                    visible = isProfileActive,
                                    enter = slideInHorizontally { it },
                                    exit = slideOutHorizontally { it }
                                ) {
                                    ProfileScreen(
                                        onClose = { isProfileActive = false },
                                        dbManager = dbManager,
                                        mediaManager = mediaManager,
                                        onJumpToMessage = { jumpToMessage(it) },
                                        onMediaClick = { index: Int ->
                                            initialMediaIndex = index
                                            showMediaExplorer = true
                                        },
                                        onChangePasscode = {
                                            isChangingPasscode = true
                                            isProfileActive = false
                                        },
                                        exoPlayer = currentPlayer
                                    )
                                }

                                if (showMediaExplorer) {
                                    MediaExplorer(
                                        items = mediaItems,
                                        initialIndex = initialMediaIndex,
                                        onClose = { showMediaExplorer = false },
                                        onJumpToMessage = { 
                                            showMediaExplorer = false
                                            jumpToMessage(it) 
                                        },
                                        mediaManager = mediaManager,
                                        exoPlayer = currentPlayer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
