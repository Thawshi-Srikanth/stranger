package com.tk.stranger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.format.DateTimeFormatter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

sealed class SetupState {
    object Idle : SetupState()
    data class Downloading(val progress: Int) : SetupState()
    object Extracting : SetupState()
    object NeedPasscode : SetupState()
    object Completed : SetupState()
    data class Error(val message: String) : SetupState()
}

class DatabaseManager(private val context: Context) {

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
    val setupState: Flow<SetupState> = _setupState

    companion object {
        private const val DB_NAME = "chat.db"
        private const val DB_ENC_NAME = "chat.db.gz.enc"
        private const val DB_ENC_R2_KEY = "messages/chat.db.gz.enc"
    }

    private var sqliteDb: SQLiteDatabase? = null
    private val dbLock = Any()

    private fun getDb(): SQLiteDatabase? {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return null
        synchronized(dbLock) {
            if (sqliteDb == null || !sqliteDb!!.isOpen) {
                try {
                    sqliteDb = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                } catch (e: Exception) {
                    android.util.Log.e("DatabaseManager", "Failed to open SQLite database", e)
                    return null
                }
            }
            return sqliteDb
        }
    }

    fun isDatabaseReady(): Boolean {
        return context.getDatabasePath(DB_NAME).exists()
    }

    suspend fun setupDatabase() {
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(DB_NAME)
            
            if (dbFile.exists()) {
                _setupState.value = SetupState.Completed
                return@withContext
            }

            val encFile = File(context.cacheDir, DB_ENC_NAME)
            
            try {
                downloadDatabase(encFile)
                _setupState.value = SetupState.Extracting
                decryptAndExtractDatabase(encFile, dbFile)
                _setupState.value = SetupState.Completed
            } catch (e: Exception) {
                _setupState.value = SetupState.Error(e.message ?: "Unknown error")
                throw e
            } finally {
                if (encFile.exists()) {
                    encFile.delete()
                }
            }
        }
    }

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private suspend fun downloadDatabase(targetFile: File) = withContext(Dispatchers.IO) {
        val deferred = async {
            val s3 = S3Client {
                region = "auto"
                endpointUrl = Url.parse(SecurityConfig.r2Endpoint)
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = SecurityConfig.r2AccessKeyId
                    secretAccessKey = SecurityConfig.r2SecretAccessKey
                }
            }

            try {
                val getObjectRequest = GetObjectRequest {
                    bucket = SecurityConfig.r2BucketName
                    key = DB_ENC_R2_KEY
                }

                s3.getObject(getObjectRequest) { response ->
                    val body = response.body ?: throw Exception("Response body is null")
                    val totalBytes = body.contentLength ?: -1L
                    
                    _setupState.value = SetupState.Downloading(0)
                    delay(200) // Give UI time to show 0%
                    
                    val inputStream = body.toInputStream()
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastPercent = -1
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((totalRead * 100) / totalBytes).toInt()
                                if (progress != lastPercent) {
                                    _setupState.value = SetupState.Downloading(progress)
                                    lastPercent = progress
                                }
                            }
                        }
                    }
                    _setupState.value = SetupState.Downloading(100)
                    delay(500) // Small pause so user sees 100%
                }
            } finally {
                s3.close()
            }
        }

        try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            managerScope.launch {
                deferred.cancelAndJoin()
            }
            throw e
        }
    }

    private suspend fun decryptAndExtractDatabase(encFile: File, dbFile: File) {
        synchronized(dbLock) {
            try {
                sqliteDb?.close()
            } catch (e: Exception) {
                // Ignore close error
            }
            sqliteDb = null
        }

        dbFile.parentFile?.let {
            if (!it.exists()) {
                it.mkdirs()
            }
        }

        delay(500) // Let user see "Extracting..." message
        val encryptedBytes = encFile.readBytes()
        val decryptedGzBytes = SecurityConfig.decryptBytes(encryptedBytes)
        GZIPInputStream(ByteArrayInputStream(decryptedGzBytes)).use { gzipIn ->
            FileOutputStream(dbFile).use { fileOut ->
                gzipIn.copyTo(fileOut)
            }
        }
        delay(500) // Pause before finishing
    }

    suspend fun getMessages(limit: Int = 100, offset: Int = 0): List<ChatMessage> = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext emptyList()

        val messages = mutableListOf<ChatMessage>()
        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"

        try {
            val cursor = db.rawQuery(
                "SELECT m.id, m.message_id, m.date, m.from_id, m.content, m.media_type, m.joined_message, " +
                "m.reply_to_message_id, p.id as p_id, p.from_id as p_from, p.content as p_content " +
                "FROM messages m " +
                "LEFT JOIN messages p ON m.reply_to_message_id = p.message_id " +
                "WHERE m.from_id IN (?, ?) ORDER BY m.id DESC LIMIT ? OFFSET ?",
                arrayOf(incomingId, outgoingId, limit.toString(), offset.toString())
            )

            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getInt(0)
                    val messageId = cursor.getLong(1)
                    val date = cursor.getString(2) ?: ""
                    val fromId = cursor.getString(3) ?: ""
                    val content = cursor.getString(4) ?: ""
                    val mediaType = cursor.getString(5)
                    val joinedId = if (cursor.isNull(6)) null else cursor.getLong(6)
                    val replyToId = if (cursor.isNull(7)) null else cursor.getLong(7)
                    val pLocalId = if (cursor.isNull(8)) null else cursor.getInt(8)
                    val pFromId = cursor.getString(9)
                    val pContent = cursor.getString(10)
                    
                    messages.add(
                        ChatMessage(
                            id = id,
                            messageId = messageId,
                            date = date,
                            fromId = fromId,
                            rawContent = content,
                            mediaType = mediaType,
                            isOutgoing = fromId == outgoingId,
                            joinedMessageId = joinedId,
                            replyToMessageId = replyToId,
                            parentLocalId = pLocalId,
                            parentFromId = pFromId,
                            parentRawContent = pContent
                        )
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getMessages failed", e)
        }
        messages
    }

    suspend fun searchMessages(query: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext emptyList()
        if (query.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<ChatMessage>()
        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"

        try {
            val cursor = db.rawQuery(
                "SELECT id, message_id, date, from_id, content, media_type, joined_message FROM messages " +
                "WHERE from_id IN (?, ?) AND content LIKE ? ORDER BY id DESC LIMIT 100",
                arrayOf(incomingId, outgoingId, "%$query%")
            )

            if (cursor.moveToFirst()) {
                do {
                    results.add(
                        ChatMessage(
                            id = cursor.getInt(0),
                            messageId = cursor.getLong(1),
                            date = cursor.getString(2) ?: "",
                            fromId = cursor.getString(3) ?: "",
                            rawContent = cursor.getString(4) ?: "",
                            mediaType = cursor.getString(5),
                            isOutgoing = cursor.getString(3) == outgoingId,
                            joinedMessageId = if (cursor.isNull(6)) null else cursor.getLong(6)
                        )
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Search failed", e)
        }
        results
    }

    suspend fun getMessageOffset(targetId: Int): Int = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext 0

        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"
        var offset = 0

        try {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND id > ?",
                arrayOf(incomingId, outgoingId, targetId.toString())
            )
            if (cursor.moveToFirst()) {
                offset = cursor.getInt(0)
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getMessageOffset failed", e)
        }
        offset
    }

    suspend fun getDateRange(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext 0L to 0L

        var range = 0L to 0L
        try {
            val cursor = db.rawQuery("SELECT MIN(date), MAX(date) FROM messages", null)
            if (cursor.moveToFirst()) {
                val minStr = cursor.getString(0)?.replace("T", " ")?.take(19)
                val maxStr = cursor.getString(1)?.replace("T", " ")?.take(19)
                
                val minTs = minStr?.let { 
                    try {
                        java.time.LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(java.time.ZoneId.of("UTC"))
                            .toInstant()
                            .toEpochMilli()
                    } catch (e: Exception) { 0L }
                } ?: 0L
                
                val maxTs = maxStr?.let { 
                    try {
                        java.time.LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(java.time.ZoneId.of("UTC"))
                            .toInstant()
                            .toEpochMilli()
                    } catch (e: Exception) { 0L }
                } ?: 0L
                range = minTs to maxTs
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getDateRange failed", e)
        }
        range
    }

    suspend fun getOffsetByDate(timestampMs: Long): Int = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext 0

        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"
        var offset = 0

        val targetDate = java.time.Instant.ofEpochMilli(timestampMs)
            .atZone(java.time.ZoneId.of("UTC"))
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        try {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND date > ?",
                arrayOf(incomingId, outgoingId, targetDate)
            )
            if (cursor.moveToFirst()) {
                offset = cursor.getInt(0)
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getOffsetByDate failed", e)
        }
        offset
    }

    suspend fun getMediaMessages(type: String, limit: Int, offset: Int): List<ChatMessage> = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext emptyList()

        val results = mutableListOf<ChatMessage>()
        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"

        val typeFilter = when(type) {
            "Media" -> "media_type IN ('photo', 'video', 'rounded_video', 'photos', 'videos')"
            "Files" -> "media_type IN ('file', 'document', 'files')"
            "Music" -> "media_type IN ('audio', 'music')"
            "Voice" -> "media_type IN ('voice_message', 'voice')"
            "Location" -> "media_type IN ('location', 'venue')"
            "Contact" -> "media_type IN ('contact', 'phone_number')"
            else -> "media_type IS NOT NULL"
        }

        try {
            val cursor = db.rawQuery(
                "SELECT id, message_id, date, from_id, content, media_type, joined_message FROM messages " +
                "WHERE from_id IN (?, ?) AND $typeFilter ORDER BY id DESC LIMIT ? OFFSET ?",
                arrayOf(incomingId, outgoingId, limit.toString(), offset.toString())
            )

            if (cursor.moveToFirst()) {
                do {
                    results.add(
                        ChatMessage(
                            id = cursor.getInt(0),
                            messageId = cursor.getLong(1),
                            date = cursor.getString(2) ?: "",
                            fromId = cursor.getString(3) ?: "",
                            rawContent = cursor.getString(4) ?: "",
                            mediaType = cursor.getString(5),
                            isOutgoing = cursor.getString(3) == outgoingId,
                            joinedMessageId = if (cursor.isNull(6)) null else cursor.getLong(6)
                        )
                    )
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getMediaMessages failed for $type", e)
        }
        results
    }

    suspend fun getMediaMessageIndex(targetId: Int, type: String): Int =
        withContext(Dispatchers.IO) {
            val db = getDb() ?: return@withContext 0

            val outgoingId = "user1110791963"
            val incomingId = "user1159974332"
            var index = 0

            val typeFilter = when (type) {
                "Media" -> "media_type IN ('photo', 'video', 'rounded_video', 'photos', 'videos')"
                "Files" -> "media_type IN ('file', 'document', 'files')"
                "Music" -> "media_type IN ('audio', 'music')"
                "Voice" -> "media_type IN ('voice_message', 'voice')"
                else -> "media_type IS NOT NULL"
            }

            try {
                val cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND $typeFilter AND id > ?",
                    arrayOf(incomingId, outgoingId, targetId.toString())
                )
                if (cursor.moveToFirst()) {
                    index = cursor.getInt(0)
                }
                cursor.close()
            } catch (e: Exception) {
                android.util.Log.e("DatabaseManager", "getMediaMessageIndex failed", e)
            }
            index
        }

    suspend fun getMediaCount(type: String): Int = withContext(Dispatchers.IO) {
        val db = getDb() ?: return@withContext 0
        var count = 0
        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"
        val typeFilter = when(type) {
            "Media" -> "media_type IN ('photo', 'video', 'rounded_video', 'photos', 'videos')"
            "Files" -> "media_type IN ('file', 'document', 'files')"
            "Music" -> "media_type IN ('audio', 'music')"
            "Voice" -> "media_type IN ('voice_message', 'voice')"
            "Location" -> "media_type IN ('location', 'venue')"
            "Contact" -> "media_type IN ('contact', 'phone_number')"
            else -> "media_type IS NOT NULL"
        }
        try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM messages WHERE from_id IN (?, ?) AND $typeFilter", arrayOf(incomingId, outgoingId))
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getMediaCount failed for $type", e)
        }
        count
    }

    fun close() {
        synchronized(dbLock) {
            try {
                sqliteDb?.close()
            } catch (e: Exception) {
                // Ignore close error
            }
            sqliteDb = null
        }
    }

    inner class MessagePagingSource : PagingSource<Int, ChatMessage>() {
        override fun getRefreshKey(state: PagingState<Int, ChatMessage>): Int? {
            return state.anchorPosition?.let { anchorPosition ->
                state.closestPageToPosition(anchorPosition)?.prevKey?.plus(state.config.pageSize)
                    ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(state.config.pageSize)
            }
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChatMessage> {
            val offset = params.key ?: 0
            val loadSize = params.loadSize
            
            return try {
                val messages = getMessages(loadSize, offset)
                val prevKey = if (offset <= 0) null else (offset - loadSize).coerceAtLeast(0)
                val nextKey = if (messages.size < loadSize) null else offset + messages.size

                LoadResult.Page(
                    data = messages,
                    prevKey = prevKey,
                    nextKey = nextKey
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    inner class MediaPagingSource(private val type: String) : PagingSource<Int, ChatMessage>() {
        override fun getRefreshKey(state: PagingState<Int, ChatMessage>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChatMessage> {
            val offset = params.key ?: 0
            val limit = params.loadSize
            return try {
                val media = getMediaMessages(type, limit, offset)
                val totalCount = getMediaCount(type)
                LoadResult.Page(
                    data = media,
                    prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                    nextKey = if (media.isEmpty() || offset + media.size >= totalCount) null else offset + media.size,
                    itemsBefore = offset.coerceAtLeast(0),
                    itemsAfter = (totalCount - (offset + media.size)).coerceAtLeast(0)
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }
}
