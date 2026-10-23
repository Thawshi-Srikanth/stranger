package com.tk.stranger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tk.stranger.SetupState

@Composable
fun SetupScreen(state: SetupState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is SetupState.Downloading -> {
                Text(text = "Downloading file ${state.progress}%", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            is SetupState.Extracting -> {
                Text(text = "Extracting database please wait", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            else -> {
                Text(text = "Starting setup...", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ErrorScreen(message: String) {
    Box(Modifier
        .fillMaxSize()
        .padding(16.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error", color = Color.White, fontWeight = FontWeight.Bold)
            Text(message, color = Color.Red, fontSize = 14.sp)
        }
    }
}
