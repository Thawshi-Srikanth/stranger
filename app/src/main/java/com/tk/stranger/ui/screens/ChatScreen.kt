package com.tk.stranger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.tk.stranger.ChatListItem
import com.tk.stranger.MediaManager
import com.tk.stranger.ui.components.ChatBubble
import com.tk.stranger.ui.components.DateHeader
import com.tk.stranger.ui.components.MiniPlayer
import com.skydoves.cloudy.cloudy
import com.skydoves.cloudy.rememberSky
import com.skydoves.cloudy.sky
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.FaceSmiling
import io.github.alexzhirkevich.cupertino.icons.outlined.Mic
import io.github.alexzhirkevich.cupertino.icons.outlined.Paperclip
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    pagingFlow: Flow<PagingData<ChatListItem>>,
    mediaManager: MediaManager,
    listState: LazyListState,
    highlightedMessageId: Int?,
    onSearchToggle: (Boolean) -> Unit,
    onProfileToggle: (Boolean) -> Unit,
    onLock: () -> Unit,
    onMediaClick: (Int) -> Unit,
    onReplyClick: (Int, Boolean) -> Unit,
    exoPlayer: Player
) {
    val lazyPagingItems = pagingFlow.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    
    var avatarFile by remember { mutableStateOf<java.io.File?>(null) }
    var chatBgFile by remember { mutableStateOf<java.io.File?>(null) }
    
    LaunchedEffect(Unit) {
        avatarFile = mediaManager.downloadMedia("avatar.jpg")
        chatBgFile = mediaManager.downloadMedia("chat-bg.png")
    }

    LaunchedEffect(lazyPagingItems.itemCount, highlightedMessageId) {
        if (highlightedMessageId != null) {
            val snapshots = lazyPagingItems.itemSnapshotList
            for (i in 0 until snapshots.size) {
                val item = snapshots[i]
                if (item is ChatListItem.Message && item.message.id == highlightedMessageId) {
                    listState.animateScrollToItem(i, -300)
                    break
                }
            }
        }
    }

    val currentVisibleDate by remember(lazyPagingItems) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) ""
            else {
                val topItem = visibleItems.lastOrNull()
                val index = topItem?.index ?: -1
                val snapshots = lazyPagingItems.itemSnapshotList

                if (index >= 0 && index < snapshots.size) {
                    when (val item = snapshots[index]) {
                        is ChatListItem.Message -> item.message.formattedDate
                        is ChatListItem.DateHeader -> item.date
                        null -> ""
                    }
                } else ""
            }
        }
    }

    var stickyDate by remember { mutableStateOf("") }
    LaunchedEffect(currentVisibleDate) {
        if (currentVisibleDate.isNotEmpty()) {
            stickyDate = currentVisibleDate
        }
    }

    var isPillVisible by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isPillVisible = true
        } else {
            delay(1500)
            isPillVisible = false
        }
    }

    val sky = rememberSky()

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val path = mediaItem?.localConfiguration?.uri?.path ?: ""

                if (path.contains("round_video_messages")) {
                    exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                } else {
                    exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = {}) {
                    Icon(
                        CupertinoIcons.Outlined.Paperclip,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF262628))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Message", color = Color.Gray, modifier = Modifier.weight(1f))
                        Icon(
                            CupertinoIcons.Outlined.FaceSmiling,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = {}) {
                    Icon(
                        CupertinoIcons.Outlined.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(bottom = padding.calculateBottomPadding())) {
            
            // Content to be blurred (Background + List)
            Box(modifier = Modifier.fillMaxSize().sky(sky)) {
                if (chatBgFile != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(chatBgFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, 
                        end = 12.dp, 
                        top = 100.dp, // Enough space for status bar + Top Bar + MiniPlayer
                        bottom = 12.dp
                    ),
                    state = listState,
                    reverseLayout = true
                ) {
                    items(
                        count = lazyPagingItems.itemCount,
                        key = lazyPagingItems.itemKey { item ->
                            when (item) {
                                is ChatListItem.Message -> "msg_${item.message.id}"
                                is ChatListItem.DateHeader -> "date_${item.date}"
                            }
                        }
                    ) { index ->
                        when (val item = lazyPagingItems[index]) {
                            is ChatListItem.Message -> {
                                val snapshots = lazyPagingItems.itemSnapshotList
                                val prev =
                                    if (index > 0) snapshots[index - 1] as? ChatListItem.Message else null
                                val next =
                                    if (index < snapshots.size - 1) snapshots[index + 1] as? ChatListItem.Message else null

                                val isLastInGroup =
                                    prev == null || prev.message.fromId != item.message.fromId
                                val isFirstInGroup =
                                    next == null || next.message.fromId != item.message.fromId

                                ChatBubble(
                                    message = item.message,
                                    isFirstInGroup = isFirstInGroup,
                                    isLastInGroup = isLastInGroup,
                                    mediaManager = mediaManager,
                                    isHighlighted = highlightedMessageId == item.message.id,
                                    onMediaClick = onMediaClick,
                                    onReplyClick = { targetId ->
                                        val snapshots = lazyPagingItems.itemSnapshotList
                                        val exists = snapshots.any { it is ChatListItem.Message && it.message.id == targetId }
                                        onReplyClick(targetId, !exists)
                                    },
                                    exoPlayer = exoPlayer
                                )
                            }

                            is ChatListItem.DateHeader -> DateHeader(item.date)
                            null -> {}
                        }
                    }
                }
            }

            // Frosted Glass Top Bar + MiniPlayer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .cloudy(sky = sky, radius = 100)
                    .background(Color.Black.copy(alpha = 0.8f))
            ) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onProfileToggle(true) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
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
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        TablerIcons.Ghost,
                                        null,
                                        modifier = Modifier.size(24.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    "Pakki",
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 18.sp
                                )
                                Text(
                                    "last seen a long time ago",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onSearchToggle(true) }) {
                            Icon(
                                TablerIcons.Search,
                                contentDescription = "Search",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = onLock) {
                            Icon(
                                TablerIcons.Lock,
                                contentDescription = "Lock",
                                tint = Color.White
                            )
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )

                MiniPlayer(
                    exoPlayer = exoPlayer,
                    sky = null, // Parent blurs everything
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            AnimatedVisibility(
                visible = isPillVisible && stickyDate.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp) // Moved up from 160.dp
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f), 
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .cloudy(sky = sky, radius = 100)
                ) {
                    Text(
                        text = stickyDate,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
