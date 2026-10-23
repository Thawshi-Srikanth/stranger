package com.tk.stranger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

// To use Apple Emojis on Android, you must:
// 1. Download an Android-compatible Apple Emoji TTF file (CBDT format).
// 2. Place it in res/font/apple_emoji.ttf.
// 3. Uncomment the code below.

// val AppleEmojiFont = FontFamily(
//     androidx.compose.ui.text.font.Font(com.tk.stranger.R.font.apple_emoji)
// )
