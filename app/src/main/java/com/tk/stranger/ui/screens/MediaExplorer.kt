package com.tk.stranger.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tk.stranger.ChatMessage
import com.tk.stranger.MediaManager
import android.widget.Toast
import kotlinx.coroutines.withContext
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaExplorer(
    items: LazyPagingItems<ChatMessage>,
    initialIndex: Int,
    onClose: () -> Unit,
    onJumpToMessage: (Int) -> Unit,
    mediaManager: MediaManager,
    exoPlayer: Player
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { items.itemCount }
    )

    // Robust scroll-to-initial-index logic that waits for Paging items
    var hasInitialJumped by remember { mutableStateOf(false) }
    LaunchedEffect(items.itemCount, initialIndex) {
        if (!hasInitialJumped && items.itemCount > 0) {
            val target = initialIndex.coerceIn(0, items.itemCount - 1)
            pagerState.scrollToPage(target)
            hasInitialJumped = true
        }
    }
    
    val thumbnailListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val thumbWidthPx = with(density) { 60.dp.toPx() }
    val centerOffset = ((screenWidthPx - thumbWidthPx) / 2).toInt()

    LaunchedEffect(pagerState.currentPage) {
        if (items.itemCount > 0) {
            thumbnailListState.animateScrollToItem(pagerState.currentPage, -centerOffset)
        }
    }

    var isUiVisible by remember { mutableStateOf(true) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var isImageZoomed by remember { mutableStateOf(false) }

    fun resetHideTimer() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3000)
            isUiVisible = false
        }
    }

    // Auto-hide UI on video play
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    isUiVisible = false
                } else {
                    isUiVisible = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(pagerState.currentPage) {
        val currentMessage = items[pagerState.currentPage]
        if (currentMessage != null &&
            (currentMessage.parsedContent.media_type == "video" || currentMessage.parsedContent.media_type == "rounded_video")
        ) {
            val key = currentMessage.parsedContent.file
            if (key != null && mediaManager.isFileDownloaded(key)) {
                val file = mediaManager.getLocalFile(key)
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMediaId("explorer_${file.absolutePath}")
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
                exoPlayer.repeatMode =
                    if (currentMessage.parsedContent.media_type == "rounded_video")
                        Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
            isUiVisible = false
        } else {
            // Only stop if we were playing a video from the explorer
            if (exoPlayer.currentMediaItem?.mediaId?.startsWith("explorer_") == true) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            isUiVisible = true
            resetHideTimer()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            exoPlayer.playbackParameters = PlaybackParameters.DEFAULT
        }
    }

    var isFastForwarding by remember { mutableStateOf(false) }

    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                userScrollEnabled = !isFastForwarding && !isImageZoomed
            ) { page ->
                val message = items[page]
                if (message != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val key = message.parsedContent.file ?: message.parsedContent.photo
                        val thumbKey = message.parsedContent.thumbnail
                        var localFile by remember {
                            mutableStateOf(
                                if (key != null && mediaManager.isFileDownloaded(key)) mediaManager.getLocalFile(key) else null
                            )
                        }
                        var localThumb by remember {
                            mutableStateOf(
                                if (thumbKey != null && mediaManager.isFileDownloaded(thumbKey)) mediaManager.getLocalFile(thumbKey) else null
                            )
                        }
                        var isDownloading by remember { mutableStateOf(false) }

                        LaunchedEffect(key) {
                            if (key != null && localFile == null) {
                                isDownloading = true
                                if (thumbKey != null && localThumb == null) {
                                    localThumb = mediaManager.downloadMedia(thumbKey)
                                }
                                localFile = mediaManager.downloadMedia(key)
                                isDownloading = false
                            }
                        }

                        val isVideo = message.parsedContent.media_type == "video" || message.parsedContent.media_type == "rounded_video"

                        if (isVideo) {
                            // Video Logic
                            if (localFile != null || localThumb != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(localThumb ?: localFile)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }

                            if (localFile != null && page == pagerState.currentPage) {
                                val videoAspectRatio = (message.parsedContent.width?.toFloat() ?: 1f) / (message.parsedContent.height?.toFloat() ?: 1f)
                                
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    AndroidView(
                                        factory = { context ->
                                            PlayerView(context).apply {
                                                player = exoPlayer
                                                useController = true
                                                controllerAutoShow = false
                                                setShowNextButton(false)
                                                setShowPreviousButton(false)
                                                setShowFastForwardButton(false)
                                                setShowRewindButton(false)
                                                setBackgroundColor(android.graphics.Color.BLACK)
                                            }
                                        },
                                        update = { 
                                            it.player = exoPlayer
                                            if (isUiVisible) it.showController() else it.hideController()
                                        },
                                        modifier = Modifier.fillMaxSize().aspectRatio(videoAspectRatio)
                                    )

                                    // Gesture Overlay for Fast Forward / Rewind / UI Toggle
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onTap = { 
                                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                                        isUiVisible = true
                                                        resetHideTimer()
                                                    },
                                                    onDoubleTap = { offset ->
                                                        if (offset.x < size.width / 2) {
                                                            exoPlayer.seekBack()
                                                        } else {
                                                            exoPlayer.seekForward()
                                                        }
                                                    },
                                                    onLongPress = { offset ->
                                                        if (offset.x > size.width / 2) {
                                                            isFastForwarding = true
                                                            exoPlayer.playbackParameters = PlaybackParameters(2.5f)
                                                        }
                                                    },
                                                    onPress = {
                                                        tryAwaitRelease()
                                                        if (isFastForwarding) {
                                                            isFastForwarding = false
                                                            exoPlayer.playbackParameters = PlaybackParameters.DEFAULT
                                                        }
                                                    }
                                                )
                                            }
                                    )

                                    // Fast Forward Indicator
                                    AnimatedVisibility(
                                        visible = isFastForwarding,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                        modifier = Modifier.align(Alignment.TopCenter)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 100.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(TablerIcons.PlayerPlay, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("2.5X", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Image Logic with "True Gallery" behavior
                            val scale = remember { androidx.compose.animation.core.Animatable(1f) }
                            val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
                            val offsetY = remember { androidx.compose.animation.core.Animatable(0f) }

                            LaunchedEffect(pagerState.currentPage) {
                                scale.snapTo(1f)
                                offsetX.snapTo(0f)
                                offsetY.snapTo(0f)
                                isImageZoomed = false
                            }

                            if (localFile != null || localThumb != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(localFile ?: localThumb)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale.value,
                                            scaleY = scale.value,
                                            translationX = offsetX.value,
                                            translationY = offsetY.value
                                        )
                                        .pointerInput(Unit) {
                                            // Low-level gesture detection to allow Pager to work at scale 1.0
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val pointers = event.changes.size
                                                    val currentScale = scale.value
                                                    
                                                    // Calculate zoom/pan even if we don't use them yet
                                                    val zoom = if (pointers > 1) event.calculateZoom() else 1f
                                                    val pan = event.calculatePan()

                                                    // Decision: Should we consume this event?
                                                    val shouldConsume = currentScale > 1.05f || (pointers > 1 && zoom != 1f)
                                                    
                                                    if (shouldConsume) {
                                                        event.changes.forEach { it.consume() }
                                                        
                                                        val newScale = (currentScale * zoom).coerceIn(0.5f, 5f)
                                                        val newOffsetX = if (newScale > 1.05f) offsetX.value + pan.x else 0f
                                                        val newOffsetY = if (newScale > 1.05f) offsetY.value + pan.y else 0f
                                                        
                                                        scope.launch {
                                                            scale.snapTo(newScale)
                                                            offsetX.snapTo(newOffsetX)
                                                            offsetY.snapTo(newOffsetY)
                                                        }
                                                        
                                                        isImageZoomed = newScale > 1.05f
                                                        if (isImageZoomed) isUiVisible = false
                                                    } else {
                                                        // We are at scale 1.0 and swiping (or just touching)
                                                        if (event.changes.all { !it.pressed }) {
                                                            if (currentScale < 1f || (currentScale < 1.1f && currentScale > 1f)) {
                                                                scope.launch {
                                                                    launch { scale.animateTo(1f) }
                                                                    launch { offsetX.animateTo(0f) }
                                                                    launch { offsetY.animateTo(0f) }
                                                                    isImageZoomed = false
                                                                    isUiVisible = true
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = {
                                                    isUiVisible = !isUiVisible
                                                    if (isUiVisible) resetHideTimer()
                                                },
                                                onDoubleTap = {
                                                    scope.launch {
                                                        if (scale.value > 1.05f) {
                                                            launch { scale.animateTo(1f) }
                                                            launch { offsetX.animateTo(0f) }
                                                            launch { offsetY.animateTo(0f) }
                                                            isImageZoomed = false
                                                            isUiVisible = true
                                                        } else {
                                                            launch { scale.animateTo(3f) }
                                                            isImageZoomed = true
                                                            isUiVisible = false
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }

                        if (isDownloading && localThumb == null) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                } else {
                    // Show this while Paging is fetching the record from DB
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // Top Bar
            AnimatedVisibility(
                visible = isUiVisible && !isFastForwarding && !isImageZoomed,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(TablerIcons.ArrowLeft, null, tint = Color.White)
                    }
                    
                    val currentMessage = items[pagerState.currentPage]
                    if (currentMessage != null) {
                        Text(currentMessage.formattedSearchDate, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    val isImage = currentMessage?.parsedContent?.media_type == "photo"
                    
                    Box {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { 
                            if (isImage) showMenu = true else { /* Handle settings click */ }
                        }) {
                            Icon(
                                if (isImage) TablerIcons.DotsVertical else TablerIcons.Settings, 
                                null, 
                                tint = Color.White
                            )
                        }

                        if (isImage && currentMessage != null) {
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(Color(0xFF262628))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Save to Gallery", color = Color.White) },
                                    leadingIcon = { Icon(TablerIcons.Download, null, tint = Color.White) },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            val key = currentMessage.parsedContent.file ?: currentMessage.parsedContent.photo ?: ""
                                            val success = mediaManager.saveToDownloads(
                                                key,
                                                "Stranger_Image_${currentMessage.id}.jpg"
                                            )
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    if (success) "Saved to Gallery" else "Failed to save",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Go to Message", color = Color.White) },
                                    leadingIcon = { Icon(TablerIcons.Message, null, tint = Color.White) },
                                    onClick = {
                                        showMenu = false
                                        onJumpToMessage(currentMessage.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Thumbnail Strip
            AnimatedVisibility(
                visible = isUiVisible && !isFastForwarding && !isImageZoomed,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        state = thumbnailListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items.itemCount) { index ->
                            val msg = items[index]
                            val key = msg?.parsedContent?.photo ?: msg?.parsedContent?.thumbnail
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(
                                        width = if (index == pagerState.currentPage) 2.dp else 0.dp,
                                        color = if (index == pagerState.currentPage) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                            ) {
                                if (key != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(
                                                if (mediaManager.isFileDownloaded(key)) mediaManager.getLocalFile(
                                                    key
                                                ) else null
                                            )
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
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
