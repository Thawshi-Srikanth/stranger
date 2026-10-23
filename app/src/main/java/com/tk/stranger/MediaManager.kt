package com.tk.stranger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MediaManager(private val context: Context) {

    private val mediaDir = File(context.filesDir, "media")
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val client by lazy {
        S3Client {
            region = "auto"
            endpointUrl = Url.parse(SecurityConfig.r2Endpoint)
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = SecurityConfig.r2AccessKeyId
                secretAccessKey = SecurityConfig.r2SecretAccessKey
            }
        }
    }

    init {
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
    }

    fun getLocalFile(r2Key: String): File {
        return File(mediaDir, r2Key.replace("/", "_"))
    }

    fun isFileDownloaded(r2Key: String): Boolean {
        val file = getLocalFile(r2Key)
        return file.exists() && file.length() > 0
    }

    fun readTgsAsJson(file: File): String? {
        return try {
            java.util.zip.GZIPInputStream(file.inputStream()).use { gzip ->
                gzip.bufferedReader().readText()
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaManager", "Failed to read TGS", e)
            null
        }
    }

    suspend fun downloadMedia(r2Key: String, onProgress: (Int) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        val targetFile = getLocalFile(r2Key)
        if (targetFile.exists() && targetFile.length() > 0) return@withContext targetFile

        // Treatment for root files like avatar.jpg, chat-bg.png vs everything else in messages/
        val cleanKey = r2Key.trimStart('/')
        val rootFiles = listOf("avatar.jpg", "chat-bg.png")
        val finalKey = if (rootFiles.contains(cleanKey) || cleanKey.startsWith("messages/")) {
            cleanKey
        } else {
            "messages/$cleanKey"
        }
        
        val deferred = async {
            try {
                val getObjectRequest = GetObjectRequest {
                    bucket = SecurityConfig.r2BucketName
                    key = finalKey
                }

                var success = false
                client.getObject(getObjectRequest) { response ->
                    val body = response.body ?: throw Exception("Body is null")
                    val totalBytes = body.contentLength ?: -1L
                    
                    body.toInputStream().use { inputStream ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastPercent = -1
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalBytes > 0) {
                                    val percent = ((totalRead * 100) / totalBytes).toInt()
                                    if (percent != lastPercent) {
                                        withContext(Dispatchers.Main) { onProgress(percent) }
                                        lastPercent = percent
                                    }
                                }
                            }
                        }
                    }
                    success = true
                }
                
                if (success && targetFile.exists() && targetFile.length() > 0) {
                    targetFile
                } else {
                    null
                }
            } catch (e: Exception) {
                // Clean up on failure
                if (targetFile.exists()) targetFile.delete()
                null
            }
        }

        try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Move cleanup to background IO scope to avoid NetworkOnMainThreadException
            managerScope.launch {
                deferred.cancelAndJoin()
            }
            throw e
        }
    }
    
    suspend fun saveToDownloads(r2Key: String, originalName: String? = null): Boolean = withContext(Dispatchers.IO) {
        val localFile = getLocalFile(r2Key)
        if (!localFile.exists()) {
            android.util.Log.e("MediaManager", "Local file not found for key: $r2Key")
            return@withContext false
        }

        val mimeType = getMimeType(r2Key)
        val fileName = originalName ?: r2Key.substringAfterLast("/")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = when {
                    mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val relativePath = when {
                    mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES + "/Stranger"
                    mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES + "/Stranger"
                    mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC + "/Stranger"
                    else -> Environment.DIRECTORY_DOWNLOADS + "/Stranger"
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }

                val contentResolver = context.contentResolver
                val uri = contentResolver.insert(collection, contentValues)
                
                uri?.let {
                    contentResolver.openOutputStream(it).use { output ->
                        localFile.inputStream().use { input ->
                            input.copyTo(output!!)
                        }
                    }
                    return@withContext true
                }
            } else {
                val targetDir = when {
                    mimeType.startsWith("image/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    mimeType.startsWith("video/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    mimeType.startsWith("audio/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
                val appDir = File(targetDir, "Stranger")
                if (!appDir.exists()) appDir.mkdirs()
                
                val targetFile = File(appDir, fileName)
                localFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Notify media scanner
                android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                return@withContext true
            }
            false
        } catch (e: Exception) {
            android.util.Log.e("MediaManager", "Failed to save media", e)
            false
        }
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
    
    fun close() {
        try {
            client.close()
        } catch (e: Exception) {
            // Ignore closure errors
        }
    }
}
