package com.tk.stranger.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skydoves.cloudy.cloudy
import com.skydoves.cloudy.rememberSky
import com.skydoves.cloudy.sky
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tk.stranger.AppVersionInfo
import com.tk.stranger.ChatMessage
import com.tk.stranger.DatabaseManager
import com.tk.stranger.MediaManager
import com.tk.stranger.UpdateManager
import com.tk.stranger.ui.components.ContactBubble
import com.tk.stranger.ui.components.LocationBubble
import com.tk.stranger.ui.components.MiniPlayer
import com.tk.stranger.utils.formatFileSize
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    onClose: () -> Unit,
    dbManager: DatabaseManager,
    mediaManager: MediaManager,
    updateManager: UpdateManager,
    onUpdateAvailable: (AppVersionInfo) -> Unit,
    onJumpToMessage: (Int) -> Unit,
    onMediaClick: (Int) -> Unit,
    onChangePasscode: () -> Unit,
    exoPlayer: Player
) {
    BackHandler {
        onClose()
    }

    val selectedTabState = remember { mutableStateOf("Media") }
    var selectedTab by selectedTabState
    val tabs = listOf("Media", "Files", "Music", "Voice", "Location", "Contact")
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    
    val density = LocalDensity.current
    val topBarHeightPx = with(density) { 64.dp.toPx() }
    var tabsPlaceholderY by remember { mutableFloatStateOf(0f) }
    
    val isSticky by remember {
        derivedStateOf {
            tabsPlaceholderY <= topBarHeightPx
        }
    }
    
    val currentMediaItem by remember { 
        derivedStateOf { exoPlayer.currentMediaItem } 
    }

    val sky = rememberSky()
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    
    var avatarFile by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(Unit) {
        avatarFile = mediaManager.downloadMedia("avatar.jpg")
    }

    // Keep paging flows for all tabs to prevent scroll reset and jumping
    val mediaMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Media") }.flow }.collectAsLazyPagingItems()
    val filesMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Files") }.flow }.collectAsLazyPagingItems()
    val musicMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Music") }.flow }.collectAsLazyPagingItems()
    val voiceMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Voice") }.flow }.collectAsLazyPagingItems()
    val locationMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Location") }.flow }.collectAsLazyPagingItems()
    val contactMediaItems = remember { Pager(PagingConfig(60)) { dbManager.MediaPagingSource("Contact") }.flow }.collectAsLazyPagingItems()

    val currentMediaItems = when (selectedTab) {
        "Media" -> mediaMediaItems
        "Files" -> filesMediaItems
        "Music" -> musicMediaItems
        "Voice" -> voiceMediaItems
        "Location" -> locationMediaItems
        "Contact" -> contactMediaItems
        else -> mediaMediaItems
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { 
                    viewportHeight = it.size.height.toFloat() 
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .sky(sky)
            ) {
                item {
                    Spacer(Modifier.height(64.dp)) // Space for TopAppBar
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF80AB)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarFile != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(avatarFile)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    TablerIcons.Ghost,
                                    null,
                                    modifier = Modifier.size(60.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Pakki",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text("last seen a long time ago", fontSize = 14.sp, color = Color.Gray)

                        Spacer(Modifier.height(24.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ProfileActionButton(
                                modifier = Modifier.weight(1f),
                                icon = TablerIcons.Lock,
                                label = "Change Passcode",
                                shape = CircleShape,
                                onClick = onChangePasscode
                            )
                            ProfileActionButton(
                                modifier = Modifier.weight(1f),
                                icon = TablerIcons.Refresh,
                                label = if (isCheckingUpdate) "Checking…" else "Check Updates",
                                shape = CircleShape,
                                onClick = {
                                    if (!isCheckingUpdate) {
                                        scope.launch {
                                            isCheckingUpdate = true
                                            val update = updateManager.checkForUpdate()
                                            isCheckingUpdate = false
                                            if (update != null) {
                                                onUpdateAvailable(update)
                                            } else {
                                                snackbarHostState.showSnackbar(
                                                    "You're on the latest version (v${updateManager.getCurrentVersionName()})"
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ProfileActionButton(
                                modifier = Modifier.fillMaxWidth(),
                                icon = null,
                                label = "9715278651",
                                sublabel = "Mobile",
                                alignment = Alignment.Start
                            )
                            
                            ProfileActionButton(
                                modifier = Modifier.fillMaxWidth(),
                                icon = null,
                                label = "You call me a stranger, but I’m still the name your heart remembers.",
                                sublabel = "Bio",
                                alignment = Alignment.Start
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }

                item {
                    // Placeholder for tabs + mini player to reserve space in the list
                    val playerHeight = if (currentMediaItem != null) 48.dp else 0.dp
                    val placeholderHeight by animateDpAsState(
                        targetValue = 56.dp + playerHeight,
                        label = "placeholderHeight"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(placeholderHeight)
                            .onGloballyPositioned { 
                                tabsPlaceholderY = it.positionInParent().y 
                            }
                    )
                }

                val loadState = currentMediaItems.loadState
                if (loadState.refresh is LoadState.Loading) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (loadState.refresh is LoadState.Error) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Failed to load content", color = Color.Red)
                        }
                    }
                }

                if (selectedTab == "Media") {
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                    val columns = 3
                    val rows = (currentMediaItems.itemCount + columns - 1) / columns
                    items(
                        count = rows,
                        key = { rowIndex -> "media_row_${rowIndex}" }
                    ) { rowIndex ->
                        Row(Modifier.fillMaxWidth()) {
                            for (colIndex in 0 until columns) {
                                val itemIndex = rowIndex * columns + colIndex
                                Box(Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(1.dp)) {
                                    if (itemIndex < currentMediaItems.itemCount) {
                                        val message = currentMediaItems[itemIndex]
                                        if (message != null) {
                                            var showMenu by remember { mutableStateOf(false) }
                                            
                                            ProfileMediaItem(
                                                message = message, 
                                                mediaManager = mediaManager,
                                                onClick = { onMediaClick(itemIndex) },
                                                onLongClick = { showMenu = true }
                                            )

                                            ProfileItemMenu(
                                                expanded = showMenu,
                                                onDismiss = { showMenu = false },
                                                onShowInChat = { onJumpToMessage(message.id) },
                                                onDownload = {
                                                    scope.launch {
                                                        val key = message.parsedContent.file ?: message.parsedContent.photo ?: ""
                                                        if (key.isNotEmpty()) {
                                                            val file = mediaManager.downloadMedia(key)
                                                            if (file != null) {
                                                                val success = mediaManager.saveToDownloads(
                                                                    key,
                                                                    message.parsedContent.title ?: "Stranger_Media_${message.id}"
                                                                )
                                                                snackbarHostState.showSnackbar(
                                                                    if (success) "Saved to Gallery" else "Failed to save"
                                                                )
                                                            } else {
                                                                snackbarHostState.showSnackbar("Failed to download media")
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTab == "Location") {
                    items(
                        count = currentMediaItems.itemCount,
                        key = currentMediaItems.itemKey { "loc_${it.id}" },
                        contentType = currentMediaItems.itemContentType { "location" }
                    ) { index ->
                        currentMediaItems[index]?.let { LocationBubble(it) }
                    }
                } else if (selectedTab == "Contact") {
                    items(
                        count = currentMediaItems.itemCount,
                        key = currentMediaItems.itemKey { "con_${it.id}" },
                        contentType = currentMediaItems.itemContentType { "contact" }
                    ) { index ->
                        currentMediaItems[index]?.let { ContactBubble(it) }
                    }
                } else {
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                    items(
                        count = currentMediaItems.itemCount,
                        key = currentMediaItems.itemKey { "file_${selectedTab}_${it.id}" },
                        contentType = currentMediaItems.itemContentType { "file" }
                    ) { index ->
                        val message = currentMediaItems[index]
                        if (message != null) {
                            var showMenu by remember { mutableStateOf(false) }
                            val isFirst = index == 0
                            val isLast = index == currentMediaItems.itemCount - 1
                            
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = if (isFirst) 16.dp else 0.dp,
                                            topEnd = if (isFirst) 16.dp else 0.dp,
                                            bottomStart = if (isLast) 16.dp else 0.dp,
                                            bottomEnd = if (isLast) 16.dp else 0.dp
                                        )
                                    )
                                    .background(Color(0xFF1C1C1D))
                            ) {
                                ProfileFileItem(
                                    message = message,
                                    mediaManager = mediaManager,
                                    exoPlayer = exoPlayer,
                                    onLongClick = { showMenu = true },
                                    showDivider = !isLast
                                )

                                ProfileItemMenu(
                                    expanded = showMenu,
                                    onDismiss = { showMenu = false },
                                    onShowInChat = { onJumpToMessage(message.id) },
                                    onDownload = {
                                        scope.launch {
                                            val key = message.parsedContent.file ?: message.parsedContent.photo ?: ""
                                            if (key.isNotEmpty()) {
                                                val file = mediaManager.downloadMedia(key)
                                                if (file != null) {
                                                    val success = mediaManager.saveToDownloads(
                                                        key,
                                                        message.parsedContent.title
                                                    )
                                                    snackbarHostState.showSnackbar(
                                                        if (success) "Saved to Downloads" else "Failed to save"
                                                    )
                                                } else {
                                                    snackbarHostState.showSnackbar("Failed to download media")
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
                
                item {
                    // Spacer that allows the header to scroll off just enough 
                    // to make the tabs sticky, then stops.
                    val extraSpacerHeight = with(density) {
                        val stickyAreaHeight = viewportHeight - topBarHeightPx - 48.dp.toPx()
                        max(0f, stickyAreaHeight).toDp()
                    }
                    Spacer(Modifier.height(extraSpacerHeight))
                }
            }

            // Floating Date Indicator (Scroll Bar)
            val firstVisibleItemDate by remember {
                derivedStateOf {
                    try {
                        val layoutInfo = listState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        if (visibleItems.isEmpty()) return@derivedStateOf ""

                        // Target the item closest to the vertical center of the viewport
                        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                        val centerItem = visibleItems.minByOrNull { 
                            val itemCenter = it.offset + it.size / 2
                            kotlin.math.abs(itemCenter - viewportCenter) 
                        } ?: visibleItems.first()

                        if (centerItem.index >= 3 && currentMediaItems.itemCount > 0) {
                            val contentIndex = centerItem.index - 3
                            // For Media tab, index is row-based (3 columns), so we adjust
                            val actualMediaIndex = if (selectedTab == "Media") {
                                contentIndex * 3
                            } else {
                                contentIndex
                            }
                            
                            val snapshot = currentMediaItems.itemSnapshotList
                            if (actualMediaIndex in 0 until snapshot.size) {
                                snapshot[actualMediaIndex]?.formattedSearchDate ?: ""
                            } else ""
                        } else ""
                    } catch (e: Exception) {
                        ""
                    }
                }
            }

            if (firstVisibleItemDate.isNotEmpty() && listState.isScrollInProgress) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .offset(y = (-100).dp), // Adjust position
                    shape = RoundedCornerShape(20.dp),
                    color = Color.DarkGray.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = firstVisibleItemDate,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 12.sp
                    )
                }
            }

            // The single, seamless Tab Bar overlay + Mini Player
            val tabsHorizontalPadding by animateDpAsState(if (isSticky) 0.dp else 8.dp, label = "padding")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { 
                        IntOffset(0, max(topBarHeightPx, tabsPlaceholderY).toInt()) 
                    }
                    .padding(horizontal = tabsHorizontalPadding, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.shape = CircleShape
                            this.clip = true
                        }
                        .cloudy(sky = sky, radius = 100)
                ) {
                    ProfileTabs(
                        tabs = tabs,
                        selectedTab = selectedTab,
                        onTabSelected = { 
                            selectedTab = it 
                            scope.launch {
                                listState.scrollToItem(0)
                            }
                        }
                    )
                }
                
                MiniPlayer(exoPlayer = exoPlayer, sky = sky, modifier = Modifier.padding(top = 8.dp))
            }

            // Glassy Top Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .cloudy(sky = sky, radius = 100)
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(TablerIcons.ArrowLeft, null, tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(
                                TablerIcons.DotsVertical,
                                null,
                                tint = Color.White
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun ProfileTabs(
    tabs: List<String>,
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll partially visible tab into view only if not fully visible
    LaunchedEffect(selectedTab) {
        val index = tabs.indexOf(selectedTab)
        if (index >= 0) {
            val layoutInfo = listState.layoutInfo
            val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
            
            val isFullyVisible = visibleItem != null && 
                visibleItem.offset >= layoutInfo.viewportStartOffset &&
                (visibleItem.offset + visibleItem.size) <= layoutInfo.viewportEndOffset

            if (!isFullyVisible) {
                listState.animateScrollToItem(index)
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1D).copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs) { tab ->
            val isSelected = selectedTab == tab
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF409CFF).copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Text(
                    text = tab,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) Color(0xFF409CFF) else Color(0xFF8E8E93)
                )
            }
        }
    }
}

@Composable
fun ProfileItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShowInChat: () -> Unit,
    onDownload: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF262628))
    ) {
        DropdownMenuItem(
            text = { Text("Show in Chat", color = Color.White) },
            leadingIcon = { Icon(TablerIcons.Message, null, tint = Color.White) },
            onClick = {
                onShowInChat()
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = { Text("Save to Downloads", color = Color.White) },
            leadingIcon = { Icon(TablerIcons.Download, null, tint = Color.White) },
            onClick = {
                onDownload()
                onDismiss()
            }
        )
    }
}

@Composable
fun ProfileActionButton(
    modifier: Modifier,
    icon: ImageVector?,
    label: String,
    sublabel: String? = null,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = modifier
            .height(if (sublabel == "Bio") 80.dp else 64.dp)
            .clickable { onClick() },
        shape = shape,
        color = Color(0xFF1C1C1D)
    ) {
        Column(
            horizontalAlignment = alignment,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = if (sublabel != null) 13.sp else 12.sp,
                fontWeight = if (sublabel != null) FontWeight.Bold else FontWeight.Normal,
                maxLines = if (sublabel == "Bio") 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = when (alignment) {
                    Alignment.Start -> TextAlign.Start
                    Alignment.End -> TextAlign.End
                    else -> TextAlign.Center
                },
                lineHeight = 16.sp
            )
            if (sublabel != null) {
                Text(
                    text = sublabel, 
                    color = Color.Gray, 
                    fontSize = 10.sp,
                    textAlign = when (alignment) {
                        Alignment.Start -> TextAlign.Start
                        Alignment.End -> TextAlign.End
                        else -> TextAlign.Center
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileMediaItem(
    message: ChatMessage, 
    mediaManager: MediaManager, 
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val key =
        message.parsedContent.thumbnail ?: message.parsedContent.photo ?: message.parsedContent.file
    var localFile by remember {
        mutableStateOf(
            if (key != null && mediaManager.isFileDownloaded(key)) mediaManager.getLocalFile(
                key
            ) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    LaunchedEffect(key) {
        if (key != null && localFile == null) {
            // Background download just to show preview
            localFile = mediaManager.downloadMedia(key)
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .combinedClickable(
            onClick = {
                if (localFile != null) onClick()
                else if (!isDownloading) isDownloading = true
            },
            onLongClick = onLongClick
        )) {
        
        if (localFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(localFile)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    LaunchedEffect(key) {
                        if (key != null) {
                            localFile = mediaManager.downloadMedia(key) { downloadProgress = it }
                        }
                        isDownloading = false
                    }
                } else {
                    Icon(TablerIcons.Download, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                }
            }
        }

        if (message.parsedContent.media_type == "video" || message.parsedContent.media_type == "rounded_video") {
            Box(Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        TablerIcons.PlayerPlay,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(message.formattedDuration, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileFileItem(
    message: ChatMessage, 
    mediaManager: MediaManager,
    exoPlayer: Player,
    onLongClick: () -> Unit,
    showDivider: Boolean = false
) {
    val key = message.parsedContent.file
    val thumbKey = message.parsedContent.thumbnail
    var localFile by remember {
        mutableStateOf(if (key != null && mediaManager.isFileDownloaded(key)) mediaManager.getLocalFile(key) else null)
    }
    var localThumb by remember {
        mutableStateOf(if (thumbKey != null && mediaManager.isFileDownloaded(thumbKey)) mediaManager.getLocalFile(thumbKey) else null)
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    var isPlayerPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var currentMediaId by remember { mutableStateOf(exoPlayer.currentMediaItem?.mediaId) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayerPlaying = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaId = mediaItem?.mediaId
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val isThisPlaying = isPlayerPlaying && currentMediaId == "audio_${localFile?.absolutePath}"

    LaunchedEffect(thumbKey) {
        if (thumbKey != null && localThumb == null) {
            localThumb = mediaManager.downloadMedia(thumbKey)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (localFile != null) {
                        val type = message.parsedContent.media_type
                        if (type == "audio" || type == "voice_message") {
                            val mediaId = "audio_${localFile!!.absolutePath}"
                            val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                            if (currentMediaId == mediaId) {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            } else {
                                exoPlayer.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(android.net.Uri.fromFile(localFile!!))
                                        .setMediaId(mediaId)
                                        .build()
                                )
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                        } else {
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", localFile!!)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, message.parsedContent.mime_type ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("ProfileFileItem", "Failed to open file", e)
                            }
                        }
                    }
                    else if (key != null && !isDownloading) isDownloading = true
                },
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (localThumb != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(localThumb)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    LaunchedEffect(key) {
                        if (key != null) {
                            localFile = mediaManager.downloadMedia(key) { downloadProgress = it }
                        }
                        isDownloading = false
                    }
                } else {
                    val isAudio = message.parsedContent.media_type == "audio" || message.parsedContent.media_type == "voice_message"
                    Icon(
                        when {
                            localFile == null -> TablerIcons.Download
                            isAudio -> if (isThisPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay
                            else -> TablerIcons.FileCheck
                        },
                        null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = message.parsedContent.title
                        ?: message.parsedContent.file?.substringAfterLast("/")
                        ?: "Unknown",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(message.parsedContent.file_size ?: 0L)} • ${message.formattedSearchDate}",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.05f)
            )
        }
    }
}
