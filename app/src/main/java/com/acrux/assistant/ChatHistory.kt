
package com.acrux.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ChatHistory {

    private const val PREFS = "acrux_chat_history"
    private const val KEY_MESSAGES = "messages"

    fun save(
        context: Context,
        role: String,
        content: String
    ) {
        if (content.isBlank()) return

        val messages = load(context).toMutableList()

        messages.add(
            ChatMessage(
                role = role,
                content = content
            )
        )

        // Keep the latest 200 messages.
        val limitedMessages = messages.takeLast(200)
        val array = JSONArray()

        limitedMessages.forEach { message ->
            val item = JSONObject().apply {
                put("role", message.role)
                put("content", message.content)
                put("timestamp", message.timestamp)
            }
            array.put(item)
        }

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_MESSAGES, array.toString())
            .apply()
    }

    fun load(context: Context): List<ChatMessage> {
        val json = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(KEY_MESSAGES, "[]") ?: "[]"

        return try {
            val array = JSONArray(json)
            val messages = mutableListOf<ChatMessage>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                messages.add(
                    ChatMessage(
                        role = item.optString("role"),
                        content = item.optString("content"),
                        timestamp = item.optLong("timestamp")
                    )
                )
            }

            messages
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(KEY_MESSAGES)
            .apply()
    }
}
