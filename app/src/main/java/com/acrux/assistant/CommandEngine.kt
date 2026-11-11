
package com.acrux.assistant

import java.util.Locale

object CommandEngine {

    fun normalize(input: String): String {
        return input.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
    }

    fun isStop(input: String): Boolean =
        normalize(input) in setOf("stop", "stop speaking")

    fun isFreeze(input: String): Boolean =
        normalize(input) in setOf("freeze", "standby", "go to sleep")

    fun isArise(input: String): Boolean =
        normalize(input) in setOf("arise", "wake up", "resume")

    fun getLocalReply(input: String): String? {
        val command = normalize(input)

        return when {
            command in setOf(
                "hi", "hii", "hello", "hey", "good morning",
                "good afternoon", "good evening"
            ) ->
                "Hello, Sir ASTA. How can I assist you?"

            command.contains("your name") ||
            command == "who are you" ->
                "I am ACRUX, your personal AI assistant."

            command.contains("time") ->
                "The current time is " +
                    java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("hh:mm a")
                    )

            command.contains("date") ||
            command == "what day is it" ->
                "Today is " +
                    java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy")
                    )

            command == "help" ->
                "You can ask me questions, check the time and date, or use FREEZE, ARISE and STOP."

            command == "status" ->
                "ACRUX command engine is online."

            else -> null
        }
    }
}
