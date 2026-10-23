package com.tk.stranger.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tk.stranger.SetupState
import com.tk.stranger.ui.theme.TelegramBlue
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val color: Color
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    setupState: SetupState,
    onFinished: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            title = "A Stranger's Regret",
            description = "You called me a stranger, and in my hurt, I tried to make it true. I deleted every word we ever shared.",
            color = Color(0xFF0F172A)
        ),
        OnboardingPage(
            title = "The Echoes Remain",
            description = "But silence is louder than any message. The guilt of erasing our history became a weight I couldn't carry.",
            color = Color(0xFF1E1B4B)
        ),
        OnboardingPage(
            title = "Fragments of Us",
            description = "I've gathered what was lost. Every laugh, every late-night talk, every piece of 'us' that I almost destroyed.",
            color = Color(0xFF312E81)
        ),
        OnboardingPage(
            title = "Your Archive",
            description = "This isn't for me anymore. It's for you. A safe place for the memories you might one day want to revisit.",
            color = Color(0xFF1E293B)
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(pages[pagerState.currentPage].color, Color.Black)
                )
            )
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { position ->
            val page = pages[position]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = page.title,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = page.description,
                    color = Color.Gray,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Bottom section
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Setup Progress
            val progressText = when (setupState) {
                is SetupState.Downloading -> "Preparing Archive: ${setupState.progress}%"
                is SetupState.Extracting -> "Restoring Messages..."
                is SetupState.Completed -> "Archive Ready"
                else -> ""
            }

            if (progressText.isNotEmpty()) {
                Text(
                    text = progressText,
                    color = TelegramBlue.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (setupState is SetupState.Downloading) {
                    LinearProgressIndicator(
                        progress = { setupState.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(2.dp)
                            .clip(CircleShape),
                        color = TelegramBlue,
                        trackColor = Color.DarkGray
                    )
                } else if (setupState is SetupState.Extracting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(2.dp)
                            .clip(CircleShape),
                        color = TelegramBlue,
                        trackColor = Color.DarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                repeat(pages.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) TelegramBlue else Color.DarkGray)
                    )
                }
            }

            Button(
                onClick = {
                    if (pagerState.currentPage < pages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onFinished()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp)
            ) {
                Text(
                    text = if (pagerState.currentPage == pages.size - 1) "Open the Archive" else "Continue",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
