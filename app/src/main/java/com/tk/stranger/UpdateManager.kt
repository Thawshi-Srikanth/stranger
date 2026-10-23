package com.tk.stranger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val ipaUrl: String = "",
    val altstoreRepoUrl: String = "",
    val releaseNotes: String = ""
)

class UpdateManager(private val context: Context) {
    companion object {
        const val VERSION_JSON_URL =
            "https://github.com/Thawshi-Srikanth/stranger/releases/latest/download/version.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    suspend fun checkForUpdate(): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$VERSION_JSON_URL?t=${System.currentTimeMillis()}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = json.decodeFromString<AppVersionInfo>(body)
            if (info.versionCode > getCurrentVersionCode()) {
                info
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallApk(
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "Stranger-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val url = URL(apkUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                installApk(apkFile)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permissionIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}

@Composable
fun UpdateAvailableDialog(
    updateInfo: AppVersionInfo,
    updateManager: UpdateManager,
    onDismiss: () -> Unit
) {
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            errorMessage = null
            val ok = updateManager.downloadAndInstallApk(updateInfo.apkUrl) { pct ->
                progress = pct
            }
            isDownloading = false
            if (ok) {
                onDismiss()
            } else {
                errorMessage = "Download failed. Please try again."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Update Available (${updateInfo.versionName})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (updateInfo.releaseNotes.isNotBlank()) {
                        updateInfo.releaseNotes
                    } else {
                        "A new version of Stranger is ready to install."
                    },
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF409CFF),
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Downloading update… $progress%",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFFF453A),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { isDownloading = true },
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF409CFF))
            ) {
                Text(if (isDownloading) "Downloading…" else "Update Now", color = Color.White)
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Later", color = Color.Gray)
                }
            }
        }
    )
}
