package com.tk.stranger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tk.stranger.ui.theme.TelegramBlue
import kotlinx.coroutines.delay

@Composable
fun PasscodeScreen(
    isSetup: Boolean = true,
    lockoutSecondsProvider: () -> Int = { 0 },
    onPasscodeSet: (String) -> Boolean,
    onCancel: (() -> Unit)? = null
) {
    var step by remember { mutableStateOf(if (isSetup) PasscodeStep.EnterNew else PasscodeStep.Verify) }
    var enteredPin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lastCharVisible by remember { mutableStateOf(false) }
    var lockoutSeconds by remember { mutableIntStateOf(lockoutSecondsProvider()) }

    LaunchedEffect(lockoutSeconds) {
        while (lockoutSeconds > 0) {
            error = "Too many attempts. Try again in ${lockoutSeconds}s."
            delay(1000)
            lockoutSeconds = lockoutSecondsProvider()
            if (lockoutSeconds == 0) {
                error = null
            }
        }
    }

    LaunchedEffect(enteredPin) {
        if (enteredPin.isNotEmpty()) {
            lastCharVisible = true
            delay(300)
            lastCharVisible = false
        }
    }

    val title = when (step) {
        PasscodeStep.EnterNew -> "Set a Passcode"
        PasscodeStep.ConfirmNew -> "Confirm your Passcode"
        PasscodeStep.Verify -> "Enter Passcode"
    }

    val subtitle = when (step) {
        PasscodeStep.EnterNew -> "Enter a 4-digit passcode"
        PasscodeStep.ConfirmNew -> "Re-enter your 4-digit passcode"
        PasscodeStep.Verify -> "Enter your 4-digit passcode to unlock"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F172A), Color.Black)
                )
            )
    ) {
        if (onCancel != null) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .padding(16.dp)
                    .statusBarsPadding()
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cancel",
                    tint = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = TelegramBlue,
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = Color.Gray,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // Dots / Numbers
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val isFilled = index < enteredPin.length
                    val isLastChar = index == enteredPin.length - 1
                    
                    Box(
                        modifier = Modifier
                            .size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isFilled) {
                            if (isLastChar && lastCharVisible) {
                                Text(
                                    text = enteredPin[index].toString(),
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color.DarkGray, CircleShape)
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.height(20.dp)) {
                if (error != null) {
                    Text(
                        text = error!!,
                        color = Color.Red,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Number Pad
            NumberPad(
                onNumberClick = { num ->
                    val currentLockout = lockoutSecondsProvider()
                    if (currentLockout > 0) {
                        lockoutSeconds = currentLockout
                        return@NumberPad
                    }
                    if (enteredPin.length < 4) {
                        enteredPin += num
                        error = null
                        
                        if (enteredPin.length == 4) {
                            when (step) {
                                PasscodeStep.EnterNew -> {
                                    firstPin = enteredPin
                                    enteredPin = ""
                                    step = PasscodeStep.ConfirmNew
                                }
                                PasscodeStep.ConfirmNew -> {
                                    if (enteredPin == firstPin) {
                                        onPasscodeSet(enteredPin)
                                    } else {
                                        error = "Passcodes do not match. Try again."
                                        enteredPin = ""
                                        step = PasscodeStep.EnterNew
                                    }
                                }
                                PasscodeStep.Verify -> {
                                    if (!onPasscodeSet(enteredPin)) {
                                        val newLockout = lockoutSecondsProvider()
                                        if (newLockout > 0) {
                                            lockoutSeconds = newLockout
                                            error = "Too many attempts. Try again in ${newLockout}s."
                                        } else {
                                            error = "Wrong passcode. Try again."
                                        }
                                        enteredPin = ""
                                    }
                                }
                            }
                        }
                    }
                },
                onDeleteClick = {
                    if (enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                    }
                }
            )
        }
    }
}

enum class PasscodeStep {
    EnterNew, ConfirmNew, Verify
}

@Composable
fun NumberPad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("" to "", "0" to "", "back" to "")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { (item, letters) ->
                    Box(
                        modifier = Modifier
                            .size(75.dp)
                            .clip(CircleShape)
                            .then(
                                if (item.isNotEmpty() && item != "back") {
                                    Modifier.background(Color.White.copy(alpha = 0.1f))
                                } else Modifier
                            )
                            .clickable(enabled = item.isNotEmpty()) {
                                if (item == "back") onDeleteClick() else onNumberClick(item)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when (item) {
                            "back" -> Icon(
                                Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            "" -> {}
                            else -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((-2).dp)
                            ) {
                                Text(
                                    text = item,
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Normal
                                )
                                if (letters.isNotEmpty()) {
                                    Text(
                                        text = letters,
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
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
