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

    fun isDatabaseReady(): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        return isDatabaseValid(dbFile)
    }

    private fun isDatabaseValid(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() < 1024L) return false
        return try {
            val testDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val cursor = testDb.rawQuery("SELECT COUNT(*) FROM messages", null)
            val valid = cursor.moveToFirst()
            cursor.close()
            testDb.close()
            valid
        } catch (e: Exception) {
            try {
                val testDb = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                val cursor = testDb.rawQuery("SELECT COUNT(*) FROM messages", null)
                val valid = cursor.moveToFirst()
                cursor.close()
                testDb.close()
                valid
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun ensureIndices(db: SQLiteDatabase) {
        try {
            if (!db.isReadOnly) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_id ON messages(id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_message_id ON messages(message_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_from_id ON messages(from_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_reply_to ON messages(reply_to_message_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_media_type ON messages(media_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_date ON messages(date)")
            }
        } catch (e: Exception) {
            android.util.Log.w("DatabaseManager", "Failed to ensure indices", e)
        }
    }

    private fun getDb(): SQLiteDatabase? {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() < 1024L) return null
        synchronized(dbLock) {
            if (sqliteDb == null || !sqliteDb!!.isOpen) {
                try {
                    sqliteDb = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    )
                    ensureIndices(sqliteDb!!)
                } catch (e: Exception) {
                    android.util.Log.w("DatabaseManager", "Failed to open SQLite database read-write; trying read-only", e)
                    try {
                        sqliteDb = SQLiteDatabase.openDatabase(
                            dbFile.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                        )
                    } catch (e2: Exception) {
                        android.util.Log.e("DatabaseManager", "Failed to open SQLite database read-only", e2)
                        return null
                    }
                }
            }
            return sqliteDb
        }
    }

    suspend fun setupDatabase() {
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(DB_NAME)
            
            if (isDatabaseValid(dbFile)) {
                _setupState.value = SetupState.Completed
                return@withContext
            }

            // If an invalid or corrupted file exists (e.g. after OS upgrade), clean it up
            if (dbFile.exists()) {
                close()
                dbFile.delete()
                File("${dbFile.absolutePath}-wal").delete()
                File("${dbFile.absolutePath}-shm").delete()
                File("${dbFile.absolutePath}-journal").delete()
            }

            val encFile = File(context.cacheDir, DB_ENC_NAME)
            
            try {
                downloadDatabase(encFile)
                _setupState.value = SetupState.Extracting
                decryptAndExtractDatabase(encFile, dbFile)
                if (!isDatabaseValid(dbFile)) {
                    throw IllegalStateException("Extracted database is invalid or corrupt")
                }
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
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            ensureIndices(db)
            db.close()
        } catch (e: Exception) {
            android.util.Log.w("DatabaseManager", "Initial indexing after extract failed", e)
        }
        delay(500) // Pause before finishing
    }

    suspend fun getMessages(limit: Int = 100, offset: Int = 0): List<ChatMessage> = withContext(Dispatchers.IO) {
        val db = getDb() ?: throw IllegalStateException("Database not available")

        val messages = mutableListOf<ChatMessage>()
        val outgoingId = "user1110791963"
        val incomingId = "user1159974332"

        try {
            // First fetch the requested page of messages directly without a heavy full-table self-join
            val cursor = db.rawQuery(
                "SELECT id, message_id, date, from_id, content, media_type, joined_message, reply_to_message_id " +
                "FROM messages " +
                "WHERE from_id IN (?, ?) ORDER BY id DESC LIMIT ? OFFSET ?",
                arrayOf(incomingId, outgoingId, limit.toString(), offset.toString())
            )

            data class RawMsg(
                val id: Int,
                val messageId: Long,
                val date: String,
                val fromId: String,
                val content: String,
                val mediaType: String?,
                val joinedId: Long?,
                val replyToId: Long?
            )
            val rawList = mutableListOf<RawMsg>()
            val replyIdsToFetch = mutableSetOf<Long>()

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

                    rawList.add(RawMsg(id, messageId, date, fromId, content, mediaType, joinedId, replyToId))
                    if (replyToId != null && replyToId > 0) {
                        replyIdsToFetch.add(replyToId)
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()

            // Fetch only the parent messages referenced by this page
            data class ParentInfo(val id: Int, val fromId: String?, val content: String?)
            val parentMap = mutableMapOf<Long, ParentInfo>()

            if (replyIdsToFetch.isNotEmpty()) {
                val chunks = replyIdsToFetch.chunked(500)
                for (chunk in chunks) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val pCursor = db.rawQuery(
                        "SELECT id, message_id, from_id, content FROM messages WHERE message_id IN ($placeholders)",
                        chunk.map { it.toString() }.toTypedArray()
                    )
                    if (pCursor.moveToFirst()) {
                        do {
                            val pId = pCursor.getInt(0)
                            val pMsgId = pCursor.getLong(1)
                            val pFrom = pCursor.getString(2)
                            val pContent = pCursor.getString(3)
                            parentMap[pMsgId] = ParentInfo(pId, pFrom, pContent)
                        } while (pCursor.moveToNext())
                    }
                    pCursor.close()
                }
            }

            for (m in rawList) {
                val parent = m.replyToId?.let { parentMap[it] }
                messages.add(
                    ChatMessage(
                        id = m.id,
                        messageId = m.messageId,
                        date = m.date,
                        fromId = m.fromId,
                        rawContent = m.content,
                        mediaType = m.mediaType,
                        isOutgoing = m.fromId == outgoingId,
                        joinedMessageId = m.joinedId,
                        replyToMessageId = m.replyToId,
                        parentLocalId = parent?.id,
                        parentFromId = parent?.fromId,
                        parentRawContent = parent?.content
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "getMessages failed", e)
            throw e
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
                android.util.Log.e("DatabaseManager", "MessagePagingSource.load failed", e)
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
                android.util.Log.e("DatabaseManager", "MediaPagingSource.load failed for $type", e)
                LoadResult.Error(e)
            }
        }
    }
}
