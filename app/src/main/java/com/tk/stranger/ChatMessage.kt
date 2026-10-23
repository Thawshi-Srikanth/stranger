package com.tk.stranger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val jsonParser = Json { 
    ignoreUnknownKeys = true 
    coerceInputValues = true
    isLenient = true
    allowSpecialFloatingPointValues = true
}

private val dbFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val colomboZone = ZoneId.of("Asia/Colombo")
private val utcZone = ZoneId.of("UTC")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy")
private val searchDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

@Serializable
data class TextEntity(
    val text: String = "",
    val type: String = "plain",
    val url: String? = null
)

@Serializable
data class ContactInformation(
    val first_name: String = "",
    val last_name: String = "",
    val phone_number: String = ""
)

@Serializable
data class LocationInformation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

@Serializable
data class MessageContent(
    val text: String? = null,
    val media_type: String? = null,
    val text_entities: List<TextEntity>? = null,
    val file: String? = null,
    val photo: String? = null,
    val thumbnail: String? = null,
    val sticker: String? = null,
    val sticker_emoji: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    val file_size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mime_type: String? = null,
    val contact_information: ContactInformation? = null,
    val location_information: LocationInformation? = null
)

data class ChatMessage(
    val id: Int,
    val messageId: Long,
    val date: String,
    val fromId: String,
    val rawContent: String,
    val mediaType: String?,
    val isOutgoing: Boolean,
    val joinedMessageId: Long? = null,
    val replyToMessageId: Long? = null,
    val parentLocalId: Int? = null,
    val parentFromId: String? = null,
    val parentRawContent: String? = null
) {
    val parentParsedContent: MessageContent? by lazy {
        parentRawContent?.let { raw ->
            try {
                val trimmed = raw.trim()
                if (trimmed.startsWith("{")) {
                    jsonParser.decodeFromString(MessageContent.serializer(), trimmed)
                } else {
                    MessageContent(text = raw)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    val parentSummary: String? by lazy {
        parentParsedContent?.let { content ->
            val text = content.text?.trim()
            if (!text.isNullOrEmpty()) text.unescape()
            else if (!content.sticker_emoji.isNullOrEmpty()) "Sticker ${content.sticker_emoji}"
            else content.title ?: content.media_type?.uppercase() ?: "Media"
        } ?: parentRawContent?.unescape()
    }

    val parsedContent: MessageContent = try {
        val trimmed = rawContent.trim()
        if (trimmed.startsWith("{")) {
            jsonParser.decodeFromString(MessageContent.serializer(), trimmed)
        } else {
            MessageContent(text = rawContent)
        }
    } catch (e: Exception) {
        // Fallback for weirdly formatted messages (like the "no" quote issue)
        val textRegex = "\"text\"\\s*:\\s*\"(.*?)(?<!\\\\)\"".toRegex()
        val match = textRegex.find(rawContent)
        val extractedText = match?.groupValues?.get(1)
        
        if (extractedText != null) {
            // Unescape quotes and common characters since we are bypassing the JSON parser
            val cleaned = extractedText
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            MessageContent(text = cleaned)
        } else {
            MessageContent(text = rawContent)
        }
    }

    val formattedTime: String = try {
        val normalized = date.replace("T", " ").take(19)
        LocalDateTime.parse(normalized, dbFormatter)
            .atZone(utcZone)
            .withZoneSameInstant(colomboZone)
            .format(timeFormatter)
    } catch (e: Exception) {
        "00:00"
    }

    val formattedDate: String = try {
        val normalized = date.replace("T", " ").take(19)
        LocalDateTime.parse(normalized, dbFormatter)
            .atZone(utcZone)
            .withZoneSameInstant(colomboZone)
            .format(dateFormatter)
    } catch (e: Exception) {
        "Unknown"
    }

    val formattedSearchDate: String = try {
        val normalized = date.replace("T", " ").take(19)
        LocalDateTime.parse(normalized, dbFormatter)
            .atZone(utcZone)
            .withZoneSameInstant(colomboZone)
            .format(searchDateFormatter)
    } catch (e: Exception) {
        ""
    }

    val formattedDuration: String = try {
        val seconds = parsedContent.duration?.toInt() ?: 0
        val m = seconds / 60
        val s = seconds % 60
        java.util.Locale.US.let { String.format(it, "%d:%02d", m, s) }
    } catch (e: Exception) {
        "0:00"
    }

    private fun String.unescape(): String {
        return this.replace("\\\"", "\"")
                   .replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\'", "'")
                   .replace("\\\\n", "\n")
                   .replace("\\\\\"", "\"")
                   .replace("\\\\", "\\")
    }

    val annotatedText: AnnotatedString = buildAnnotatedString {
        val entities = parsedContent.text_entities
        if (!entities.isNullOrEmpty()) {
            entities.forEach { entity ->
                val fixedText = entity.text.unescape()
                when (entity.type) {
                    "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(fixedText)
                    }
                    "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(fixedText)
                    }
                    "link" -> {
                        pushStringAnnotation(tag = "URL", annotation = fixedText)
                        withStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) {
                            append(fixedText)
                        }
                        pop()
                    }
                    "text_link" -> {
                        val linkUrl = entity.url ?: fixedText
                        pushStringAnnotation(tag = "URL", annotation = linkUrl)
                        withStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) {
                            append(fixedText)
                        }
                        pop()
                    }
                    else -> append(fixedText)
                }
            }
        } else {
            val text = (parsedContent.text ?: "").unescape()
            // Robust regex for URLs
            val urlRegex = "https?://[\\w-]+(\\.[\\w-]+)+([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])?".toRegex()
            var lastMatchEnd = 0
            urlRegex.findAll(text).forEach { match ->
                append(text.substring(lastMatchEnd, match.range.first))
                pushStringAnnotation(tag = "URL", annotation = match.value)
                withStyle(SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) {
                    append(match.value)
                }
                pop()
                lastMatchEnd = match.range.last + 1
            }
            append(text.substring(lastMatchEnd))
        }
    }

    val firstLink: String? = annotatedText.getStringAnnotations("URL", 0, annotatedText.length).firstOrNull()?.item

    val mainFileKey: String? get() = parsedContent.photo ?: parsedContent.file ?: parsedContent.sticker
}
