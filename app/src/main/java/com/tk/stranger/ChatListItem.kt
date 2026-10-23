package com.tk.stranger

sealed class ChatListItem {
    data class Message(val message: ChatMessage) : ChatListItem()
    data class DateHeader(val date: String) : ChatListItem()
}
