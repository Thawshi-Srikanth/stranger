package com.tk.stranger.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.*
import com.tk.stranger.ChatMessage
import com.tk.stranger.MediaManager
import com.tk.stranger.ui.theme.TelegramBlue
import com.tk.stranger.ui.theme.TelegramInboundBubble
import com.tk.stranger.ui.theme.TelegramPurple
import com.tk.stranger.utils.formatFileSize
import com.tk.stranger.utils.formatTime
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import kotlinx.coroutines.delay

@Composable
fun DateHeader(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(16.dp)) {
            Text(
                text = date,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    mediaManager: MediaManager,
    isHighlighted: Boolean = false,
    onMediaClick: (Int) -> Unit,
    onReplyClick: (Int) -> Unit,
    exoPlayer: Player
) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleShape = remember(message.isOutgoing, isFirstInGroup, isLastInGroup) {
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density
            ): Outline {
                val r = with(density) { 18.dp.toPx() }
                val f = with(density) { 4.dp.toPx() }
                val t = with(density) { 6.dp.toPx() }
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    if (message.isOutgoing) {
                        val tr = if (isFirstInGroup) r else f
                        val br = if (isLastInGroup) 0f else f
                        moveTo(r, 0f); lineTo(w - tr, 0f)
                        if (tr > 0) arcTo(Rect(w - tr * 2, 0f, w, tr * 2), 270f, 90f, false)
                        if (isLastInGroup) {
                            lineTo(w, h - r); lineTo(w + t, h); lineTo(w - r, h)
                        } else {
                            lineTo(w, h - br); if (br > 0) arcTo(
                                Rect(w - br * 2, h - br * 2, w, h),
                                0f,
                                90f,
                                false
                            ); lineTo(r, h)
                        }
                        arcTo(Rect(0f, h - r * 2, r * 2, h), 90f, 90f, false); lineTo(0f, r); arcTo(
                            Rect(0f, 0f, r * 2, r * 2),
                            180f,
                            90f,
                            false
                        ); close()
                    } else {
                        val tl = if (isFirstInGroup) r else f
                        val bl = if (isLastInGroup) 0f else f
                        moveTo(tl, 0f); if (tl > 0) arcTo(
                            Rect(0f, 0f, tl * 2, tl * 2),
                            270f,
                            -90f,
                            false
                        ) else lineTo(0f, 0f)
                        if (isLastInGroup) {
                            lineTo(0f, h - r); lineTo(-t, h); lineTo(r, h)
                        } else {
                            lineTo(0f, h - bl); if (bl > 0) arcTo(
                                Rect(0f, h - bl * 2, bl * 2, h),
                                180f,
                                -90f,
                                false
                            )
                        }
                        lineTo(w - r, h); arcTo(
                            Rect(w - r * 2, h - r * 2, w, h),
                            90f,
                            -90f,
                            false
                        ); lineTo(w, r); arcTo(
                            Rect(w - r * 2, 0f, w, r * 2),
                            0f,
                            -90f,
                            false
                        ); close()
                    }
                }
                return Outline.Generic(path)
            }
        }
    }
    val screenHeight =
        with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var bubbleY by remember { mutableFloatStateOf(0f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = if (isLastInGroup) 4.dp else 2.dp), horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .onGloballyPositioned { bubbleY = it.positionInWindow().y }
                .drawBehind {
                    if (message.parsedContent.sticker == null && message.parsedContent.media_type != "rounded_video") {
                        val brush = if (message.isOutgoing) Brush.verticalGradient(
                            listOf(
                                TelegramPurple,
                                TelegramBlue
                            ), -bubbleY, screenHeight - bubbleY
                        ) else androidx.compose.ui.graphics.SolidColor(TelegramInboundBubble)
                        val outline = bubbleShape.createOutline(size, layoutDirection, this)
                        if (outline is Outline.Generic) {
                            drawPath(outline.path, brush)
                            if (isHighlighted) drawPath(
                                outline.path,
                                Color.White.copy(alpha = 0.2f)
                            )
                        }
                    } else if (isHighlighted) {
                        val isRoundedVideo = message.parsedContent.media_type == "rounded_video"
                        if (isRoundedVideo) {
                            drawCircle(Color.White.copy(alpha = 0.2f), size.minDimension / 2f)
                        } else {
                            drawCircle(Color.White.copy(alpha = 0.15f), size.minDimension / 1.5f)
                        }
                    }
                }
                .clip(bubbleShape)
        ) {
            val parsed = message.parsedContent
            val isSticker = parsed.sticker != null
            val isAudio = parsed.media_type == "audio"
            val isVoice = parsed.media_type == "voice_message"
            val hasMedia = parsed.media_type != null || isSticker
            val hasText = message.annotatedText.isNotEmpty()
            Box {
                val uriHandler = LocalUriHandler.current
                Column {
                    if (message.replyToMessageId != null && message.parentLocalId != null) {
                        ReplyPreview(
                            message = message
                        ) {
                            onReplyClick(message.parentLocalId)
                        }
                    }

                    if (isSticker) StickerBubble(message, mediaManager)
                    else if (parsed.media_type == "photo") PhotoBubble(
                        message,
                        mediaManager
                    ) { onMediaClick(message.id) }
                    else if (parsed.media_type == "video") VideoBubble(
                        message,
                        mediaManager,
                        exoPlayer
                    ) { onMediaClick(message.id) }
                    else if (parsed.media_type == "rounded_video") RoundedVideoBubble(
                        message,
                        mediaManager,
                        exoPlayer
                    ) { onMediaClick(message.id) }
                    else if (parsed.media_type == "animation") AnimationBubble(
                        message,
                        mediaManager,
                        exoPlayer
                    )
                    else if (parsed.media_type == "file") FileBubble(message, mediaManager)
                    else if (parsed.media_type == "contact") ContactBubble(message)
                    else if (parsed.media_type == "location") LocationBubble(message)
                    else if (isAudio) AudioBubble(message, mediaManager, exoPlayer)
                    else if (isVoice) VoiceBubble(message, mediaManager, exoPlayer)
                    else if (hasMedia) Box(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.1f)),
                        Alignment.Center
                    ) {
                        Text(
                            parsed.media_type!!.uppercase(),
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (hasText) {
                        Column(
                            modifier = Modifier.padding(
                                12.dp,
                                if (hasMedia) 6.dp else 10.dp,
                                12.dp,
                                if (hasMedia) 8.dp else 10.dp
                            )
                        ) {
                            // Link Preview
                            val firstLink = message.firstLink
                            if (firstLink != null && !isAudio && !isVoice && !isSticker) {
                                LinkPreview(firstLink)
                                Spacer(Modifier.height(8.dp))
                            }

                            ClickableText(
                                text = buildAnnotatedString {
                                    append(message.annotatedText)
                                    append("\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0")
                                },
                                style = androidx.compose.ui.text.TextStyle(
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp
                                ),
                                onClick = { offset ->
                                    message.annotatedText.getStringAnnotations(
                                        "URL",
                                        offset,
                                        offset
                                    )
                                        .firstOrNull()?.let { annotation ->
                                            uriHandler.openUri(annotation.item)
                                        }
                                }
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp, 6.dp),
                    color = if (!hasText && (parsed.media_type == "photo" || parsed.media_type == "video" || parsed.media_type == "rounded_video" || isSticker)) Color.Black.copy(
                        alpha = 0.3f
                    ) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp, 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.formattedTime,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        if (message.isOutgoing) {
                            Spacer(Modifier.width(3.dp)); Icon(
                                TablerIcons.Checks,
                                null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioBubble(message: ChatMessage, mediaManager: MediaManager, exoPlayer: Player) {
    val fileKey = message.parsedContent.file ?: return
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(fileKey)) mediaManager.getLocalFile(
                fileKey
            ) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var currentPos by remember { mutableLongStateOf(0L) }

    // Sync state with ExoPlayer events
    DisposableEffect(exoPlayer, localFile) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                isPlaying = playing && currentMediaId == "audio_${localFile?.absolutePath}"
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentMediaId = mediaItem?.mediaId
                isPlaying = exoPlayer.isPlaying && currentMediaId == "audio_${localFile?.absolutePath}"
            }
        }
        exoPlayer.addListener(listener)
        val initialMediaId = exoPlayer.currentMediaItem?.mediaId
        isPlaying = exoPlayer.isPlaying && initialMediaId == "audio_${localFile?.absolutePath}"

        onDispose { exoPlayer.removeListener(listener) }
    }

    // Progress update loop
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                if (exoPlayer.duration > 0) {
                    currentPos = exoPlayer.currentPosition
                    playProgress = currentPos.toFloat() / exoPlayer.duration.toFloat()
                }
                delay(50)
            }
        }
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable {
                        if (localFile != null) {
                            val mediaId = "audio_${localFile!!.absolutePath}"
                            val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                            if (currentMediaId == mediaId) {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            } else {
                                val mediaItem = MediaItem.Builder()
                                    .setUri(Uri.fromFile(localFile))
                                    .setMediaId(mediaId)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(message.parsedContent.title ?: "Audio")
                                            .build()
                                    )
                                    .build()
                                exoPlayer.setMediaItem(mediaItem)
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                        } else if (!isDownloading) {
                            isDownloading = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isDownloading) CircularProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.size(24.dp),
                    color = Color.White
                )
                else if (localFile == null) Icon(
                    TablerIcons.Download,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                else Icon(
                    if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    message.parsedContent.title ?: "Audio File",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { if (isPlaying) playProgress else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (isPlaying) formatTime(currentPos) else message.formattedDuration,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        formatFileSize(message.parsedContent.file_size ?: 0L),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }
        }
        if (!message.annotatedText.isNotEmpty()) Spacer(Modifier.height(10.dp))
    }

    if (isDownloading) {
        LaunchedEffect(fileKey) {
            val res = mediaManager.downloadMedia(fileKey) { downloadProgress = it }
            localFile = res
            isDownloading = false
        }
    }
}

@Composable
fun VoiceBubble(message: ChatMessage, mediaManager: MediaManager, exoPlayer: Player) {
    val fileKey = message.parsedContent.file ?: return
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(fileKey)) mediaManager.getLocalFile(
                fileKey
            ) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var currentPos by remember { mutableLongStateOf(0L) }

    DisposableEffect(exoPlayer, localFile) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                isPlaying = playing && currentMediaId == "audio_${localFile?.absolutePath}"
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentMediaId = mediaItem?.mediaId
                isPlaying = exoPlayer.isPlaying && currentMediaId == "audio_${localFile?.absolutePath}"
            }
        }
        exoPlayer.addListener(listener)
        val initialMediaId = exoPlayer.currentMediaItem?.mediaId
        isPlaying = exoPlayer.isPlaying && initialMediaId == "audio_${localFile?.absolutePath}"
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                if (exoPlayer.duration > 0) {
                    currentPos = exoPlayer.currentPosition
                    playProgress = currentPos.toFloat() / exoPlayer.duration.toFloat()
                }
                delay(50)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable {
                    if (localFile != null) {
                        val mediaId = "audio_${localFile!!.absolutePath}"
                        val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                        if (currentMediaId == mediaId) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        } else {
                            val mediaItem = MediaItem.Builder()
                                .setUri(Uri.fromFile(localFile))
                                .setMediaId(mediaId)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Voice message")
                                        .build()
                                )
                                .build()
                            exoPlayer.setMediaItem(mediaItem)
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    } else if (!isDownloading) {
                        isDownloading = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) CircularProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier.size(20.dp),
                color = Color.White
            )
            else if (localFile == null) Icon(
                TablerIcons.Download,
                null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            else Icon(
                if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val barCount = 35
                    val barWidth = 2.5.dp.toPx()
                    val gap = 2.dp.toPx()

                    for (i in 0 until barCount) {
                        val h = when (i % 7) {
                            0 -> 8f; 1 -> 14f; 2 -> 10f; 3 -> 18f; 4 -> 12f; 5 -> 15f; else -> 9f
                        }
                        val barHeight = h.dp.toPx()
                        val x = i * (barWidth + gap)
                        val isPast = (x / canvasWidth) < playProgress
                        drawRect(
                            color = if (isPast) Color.White else Color.White.copy(alpha = 0.3f),
                            topLeft = Offset(x, (canvasHeight - barHeight) / 2),
                            size = Size(barWidth, barHeight)
                        )
                    }
                }
            }
            Text(
                if (isPlaying) formatTime(currentPos) else message.formattedDuration,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }

        if (!message.annotatedText.isNotEmpty()) Spacer(Modifier.width(40.dp))
    }

    if (isDownloading) {
        LaunchedEffect(fileKey) {
            val res = mediaManager.downloadMedia(fileKey) { downloadProgress = it }
            localFile = res
            isDownloading = false
        }
    }
}

@Composable
fun StickerBubble(message: ChatMessage, mediaManager: MediaManager) {
    val stickerKey = message.parsedContent.sticker ?: return
    val thumbKey = message.parsedContent.thumbnail
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(stickerKey)) mediaManager.getLocalFile(
                stickerKey
            ) else null
        )
    }
    var localThumb by remember {
        mutableStateOf(
            if (thumbKey != null && mediaManager.isFileDownloaded(
                    thumbKey
                )
            ) mediaManager.getLocalFile(thumbKey) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(localFile?.let {
        mediaManager.readTgsAsJson(
            it
        )
    } ?: ""))

    LaunchedEffect(stickerKey) {
        if (localFile == null) {
            if (thumbKey != null && localThumb == null) {
                localThumb = mediaManager.downloadMedia(thumbKey)
            }
        }
    }

    Box(Modifier
        .size(180.dp)
        .clickable {
            if (localFile == null && !isDownloading) {
                isDownloading = true
            }
        }, Alignment.Center) {
        if (isDownloading) {
            LaunchedEffect(stickerKey) {
                val res = mediaManager.downloadMedia(stickerKey) { downloadProgress = it }
                localFile = res
                isDownloading = false
            }
        }

        if (composition != null) {
            LottieAnimation(composition, iterations = 1, modifier = Modifier.fillMaxSize())
        } else {
            if (localThumb != null) AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(localThumb).crossfade(true).build(),
                null,
                Modifier
                    .fillMaxSize()
                    .alpha(0.6f),
                contentScale = ContentScale.Fit
            )
            if (isDownloading) CircularProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier.size(40.dp),
                color = Color.White.copy(alpha = 0.5f)
            )
            else if (localFile == null) Icon(
                TablerIcons.Download,
                null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun PhotoBubble(message: ChatMessage, mediaManager: MediaManager, onClick: () -> Unit) {
    val photoKey = message.parsedContent.photo ?: return
    val thumbKey = message.parsedContent.thumbnail
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(photoKey)) mediaManager.getLocalFile(
                photoKey
            ) else null
        )
    }
    var localThumb by remember {
        mutableStateOf(
            if (thumbKey != null && mediaManager.isFileDownloaded(
                    thumbKey
                )
            ) mediaManager.getLocalFile(thumbKey) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    val aspectRatio = (message.parsedContent.width ?: 300).toFloat() / (message.parsedContent.height
        ?: 300).toFloat()

    LaunchedEffect(photoKey) {
        if (thumbKey != null && localThumb == null) {
            localThumb = mediaManager.downloadMedia(thumbKey)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable {
                onClick()
                if (localFile == null && !isDownloading) {
                    isDownloading = true
                }
            }, Alignment.Center
    ) {
        if (isDownloading) {
            LaunchedEffect(photoKey) {
                val res = mediaManager.downloadMedia(photoKey) { downloadProgress = it }
                localFile = res
                isDownloading = false
            }
        }

        if (localFile != null) AsyncImage(
            ImageRequest.Builder(LocalContext.current).data(localFile).crossfade(true).build(),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        else {
            Box(Modifier.fillMaxSize()) {
                if (localThumb != null) AsyncImage(
                    ImageRequest.Builder(LocalContext.current).data(localThumb).build(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)))
            }
            if (isDownloading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                ); Spacer(Modifier.height(8.dp)); Text(
                "$downloadProgress%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            }
            else Box(
                Modifier
                    .size(50.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                Alignment.Center
            ) {
                Icon(
                    TablerIcons.Download,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoBubble(
    message: ChatMessage,
    mediaManager: MediaManager,
    exoPlayer: Player,
    onClick: () -> Unit
) {
    val videoKey = message.parsedContent.file ?: return
    val thumbKey = message.parsedContent.thumbnail
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(videoKey)) mediaManager.getLocalFile(
                videoKey
            ) else null
        )
    }
    var localThumb by remember {
        mutableStateOf(
            if (thumbKey != null && mediaManager.isFileDownloaded(
                    thumbKey
                )
            ) mediaManager.getLocalFile(thumbKey) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer, localFile) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying =
                    playing && exoPlayer.currentMediaItem?.mediaId == "video_${localFile?.absolutePath}"
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                isPlaying = exoPlayer.isPlaying && mediaItem?.mediaId == "video_${localFile?.absolutePath}"
            }
        }
        exoPlayer.addListener(listener)
        isPlaying =
            exoPlayer.isPlaying && exoPlayer.currentMediaItem?.mediaId == "video_${localFile?.absolutePath}"
        onDispose { exoPlayer.removeListener(listener) }
    }

    val aspectRatio =
        (message.parsedContent.width ?: 1).toFloat() / (message.parsedContent.height ?: 1).toFloat()

    LaunchedEffect(thumbKey) {
        if (thumbKey != null && localThumb == null) {
            localThumb = mediaManager.downloadMedia(thumbKey)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable {
                onClick()
                if (localFile == null && !isDownloading) isDownloading = true
            },
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading) {
            LaunchedEffect(videoKey) {
                val res = mediaManager.downloadMedia(videoKey) { downloadProgress = it }
                localFile = res
                isDownloading = false
            }
        }

        if (isPlaying && localFile != null) {
            AndroidView(
                factory = { context ->
                    android.view.TextureView(context).apply {
                        surfaceTextureListener =
                            object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                    exoPlayer.setVideoTextureView(this@apply)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                }

                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    exoPlayer.clearVideoTextureView(this@apply); return true
                                }

                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            }
                    }
                },
                update = { exoPlayer.setVideoTextureView(it) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!isPlaying || localFile == null) {
            if (localFile != null || localThumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(localThumb ?: localFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (isDownloading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$downloadProgress%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (localFile == null) {
            Box(
                Modifier
                    .size(50.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                Alignment.Center
            ) {
                Icon(
                    TablerIcons.Download,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else if (!isPlaying) {
            Box(
                Modifier
                    .size(50.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                Alignment.Center
            ) {
                Icon(
                    TablerIcons.PlayerPlay,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = message.formattedDuration,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun RoundedVideoBubble(
    message: ChatMessage,
    mediaManager: MediaManager,
    exoPlayer: Player,
    onClick: () -> Unit
) {
    val videoKey = message.parsedContent.file ?: return
    val thumbKey = message.parsedContent.thumbnail
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(videoKey)) mediaManager.getLocalFile(
                videoKey
            ) else null
        )
    }
    var localThumb by remember {
        mutableStateOf(
            if (thumbKey != null && mediaManager.isFileDownloaded(
                    thumbKey
                )
            ) mediaManager.getLocalFile(thumbKey) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer, localFile) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying =
                    playing && exoPlayer.currentMediaItem?.mediaId == "video_${localFile?.absolutePath}"
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                isPlaying = exoPlayer.isPlaying && mediaItem?.mediaId == "video_${localFile?.absolutePath}"
            }
        }
        exoPlayer.addListener(listener)
        isPlaying =
            exoPlayer.isPlaying && exoPlayer.currentMediaItem?.mediaId == "video_${localFile?.absolutePath}"
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(thumbKey) {
        if (thumbKey != null && localThumb == null) {
            localThumb = mediaManager.downloadMedia(thumbKey)
        }
    }

    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(CircleShape)
            .clickable {
                onClick()
                if (localFile == null && !isDownloading) isDownloading = true
            },
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading) {
            LaunchedEffect(videoKey) {
                val res = mediaManager.downloadMedia(videoKey) { downloadProgress = it }
                localFile = res
                isDownloading = false
            }
        }

        if (isPlaying && localFile != null) {
            AndroidView(
                factory = { context ->
                    android.view.TextureView(context).apply {
                        surfaceTextureListener =
                            object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                    exoPlayer.setVideoTextureView(this@apply)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                }

                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    exoPlayer.clearVideoTextureView(this@apply); return true
                                }

                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            }
                    }
                },
                update = { exoPlayer.setVideoTextureView(it) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!isPlaying || localFile == null) {
            if (localFile != null || localThumb != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(localThumb ?: localFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        if (isDownloading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$downloadProgress%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (localFile == null) {
            Icon(
                TablerIcons.Download,
                null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
        } else if (!isPlaying) {
            Icon(
                TablerIcons.PlayerPlay,
                null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AnimationBubble(message: ChatMessage, mediaManager: MediaManager, exoPlayer: Player) {
    val fileKey = message.parsedContent.file ?: return
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(fileKey)) mediaManager.getLocalFile(
                fileKey
            ) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer, localFile) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying =
                    playing && exoPlayer.currentMediaItem?.mediaId == "animation_${localFile?.absolutePath}"
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                isPlaying = exoPlayer.isPlaying && mediaItem?.mediaId == "animation_${localFile?.absolutePath}"
            }
        }
        exoPlayer.addListener(listener)
        isPlaying =
            exoPlayer.isPlaying && exoPlayer.currentMediaItem?.mediaId == "animation_${localFile?.absolutePath}"

        onDispose { exoPlayer.removeListener(listener) }
    }

    val aspectRatio =
        (message.parsedContent.width ?: 1).toFloat() / (message.parsedContent.height ?: 1).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable {
                if (localFile != null) {
                    val mediaId = "animation_${localFile!!.absolutePath}"
                    if (exoPlayer.currentMediaItem?.mediaId == mediaId) {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    } else {
                        val mediaItem = MediaItem.Builder()
                            .setUri(Uri.fromFile(localFile))
                            .setMediaId(mediaId)
                            .build()
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        // Note: volume and repeatMode might need special handling with MediaController
                        // but Player interface has volume and repeatMode too.
                    }
                } else if (!isDownloading) {
                    isDownloading = true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading) {
            LaunchedEffect(fileKey) {
                val res = mediaManager.downloadMedia(fileKey) { downloadProgress = it }
                localFile = res
                isDownloading = false
            }
        }

        if (isPlaying && localFile != null) {
            AndroidView(
                factory = { context ->
                    android.view.TextureView(context).apply {
                        surfaceTextureListener =
                            object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                    exoPlayer.setVideoTextureView(this@apply)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int
                                ) {
                                }

                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    exoPlayer.clearVideoTextureView(this@apply)
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            }
                    }
                },
                update = {
                    exoPlayer.setVideoTextureView(it)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            if (localFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(localFile)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            if (isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$downloadProgress%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (localFile == null) {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    Alignment.Center
                ) {
                    Icon(
                        TablerIcons.Download,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    Alignment.Center
                ) {
                    Icon(
                        TablerIcons.PlayerPlay,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FileBubble(message: ChatMessage, mediaManager: MediaManager) {
    val fileKey = message.parsedContent.file ?: return
    var localFile by remember {
        mutableStateOf(
            if (mediaManager.isFileDownloaded(fileKey)) mediaManager.getLocalFile(
                fileKey
            ) else null
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (localFile != null) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            localFile!!
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, message.parsedContent.mime_type ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("FileBubble", "Failed to open file", e)
                    }
                } else if (!isDownloading) {
                    isDownloading = true
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = if (localFile != null) TablerIcons.File else TablerIcons.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.parsedContent.title ?: fileKey.substringAfterLast("/"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(message.parsedContent.file_size ?: 0L),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }

    if (isDownloading) {
        LaunchedEffect(fileKey) {
            val res = mediaManager.downloadMedia(fileKey) { downloadProgress = it }
            localFile = res
            isDownloading = false
        }
    }
}

@Composable
fun ContactBubble(message: ChatMessage) {
    val contact = message.parsedContent.contact_information ?: return
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${contact.phone_number}")
                }
                context.startActivity(intent)
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(TablerIcons.User, null, tint = Color.White)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${contact.first_name} ${contact.last_name}".trim(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = contact.phone_number,
                color = Color.Gray,
                fontSize = 12.sp
            )
        }

        Icon(TablerIcons.Phone, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun LocationBubble(message: ChatMessage) {
    val location = message.parsedContent.location_information ?: return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val gmmIntentUri =
                    Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                context.startActivity(mapIntent)
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    TablerIcons.MapPin,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("View Location", color = Color.White, fontSize = 14.sp)
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Latitude: ${location.latitude}",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Text(
                text = "Longitude: ${location.longitude}",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun LinkPreview(url: String) {
    val uriHandler = LocalUriHandler.current
    val domain = remember(url) {
        try {
            val uri = Uri.parse(url)
            val host = uri.host ?: ""
            host.removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
    }

    if (domain.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { uriHandler.openUri(url) }
            .drawBehind {
                drawRect(
                    color = Color(0xFF64B5F6),
                    topLeft = Offset(0f, 0f),
                    size = Size(3.dp.toPx(), size.height)
                )
            }
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
    ) {
        Column {
            Text(
                text = domain,
                color = Color(0xFF64B5F6),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = url,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ReplyPreview(
    message: ChatMessage,
    onClick: () -> Unit
) {
    val author = if (message.parentFromId == "user1110791963") "You" else "Stranger"
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .drawBehind {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(0f, 0f),
                    size = Size(3.dp.toPx(), size.height)
                )
            }
            .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp)
    ) {
        Column {
            Text(
                text = author,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = message.parentSummary ?: "",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

