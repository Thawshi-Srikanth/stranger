package com.tk.stranger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.skydoves.cloudy.Sky
import com.skydoves.cloudy.cloudy
import compose.icons.TablerIcons
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.X

@Composable
fun MiniPlayer(
    exoPlayer: Player,
    sky: Sky?,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var currentMediaItem by remember { mutableStateOf(exoPlayer.currentMediaItem) }
    var currentMetadata by remember { mutableStateOf(exoPlayer.mediaMetadata) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentMediaItem = item
                currentMetadata = exoPlayer.mediaMetadata
            }
            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                currentMetadata = metadata
            }
        }
        exoPlayer.addListener(listener)
        // Initial sync
        isPlaying = exoPlayer.isPlaying
        currentMediaItem = exoPlayer.currentMediaItem
        currentMetadata = exoPlayer.mediaMetadata
        
        onDispose { exoPlayer.removeListener(listener) }
    }

    if (currentMediaItem != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(40.dp)
                .graphicsLayer {
                    this.shape = CircleShape
                    this.clip = true
                }
                .let { if (sky != null) it.cloudy(sky = sky, radius = 100) else it }
                .background(Color(0xFF1C1C1D).copy(alpha = 0.8f))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                        null,
                        tint = Color(0xFF409CFF),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))

                val title = currentMetadata.title?.toString()
                    ?: currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: currentMediaItem?.localConfiguration?.uri?.lastPathSegment
                    ?: "Unknown Audio"
                val artist = currentMetadata.artist?.toString()
                    ?: currentMediaItem?.mediaMetadata?.artist?.toString()

                val displayText = if (!artist.isNullOrEmpty()) "$title - $artist" else title

                Text(
                    text = displayText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee()
                )
                IconButton(
                    onClick = { exoPlayer.stop(); exoPlayer.clearMediaItems() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(TablerIcons.X, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
