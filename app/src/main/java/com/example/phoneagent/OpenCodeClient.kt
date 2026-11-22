package com.example.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Minimal client for the on-device OpenCode HTTP API (v2). */
class OpenCodeClient(private val baseUrl: String, private val password: String) {

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 6000
        connection.readTimeout = 180000
        connection.setRequestProperty("Content-Type", "application/json")
        if (password.isNotBlank()) {
            val credentials = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
            connection.setRequestProperty("Authorization", "Basic $credentials")
        }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        connection.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(400)}")
        return text
    }

    fun check(): String {
        val json = JSONObject(request("GET", "/api/session"))
        val sessions = json.optJSONArray("data") ?: JSONArray()
        return "OpenCode доступен. Сессий: ${sessions.length()}"
    }

    fun createSession(): String {
        val json = JSONObject(request("POST", "/api/session", "{}"))
        return json.getJSONObject("data").getString("id")
    }

    /** Sends a prompt and polls for the assistant answer. Returns text + tool calls. */
    fun prompt(sessionId: String, text: String): String {
        request("POST", "/api/session/$sessionId/prompt", JSONObject().put("text", text).toString())
        val startedAt = System.currentTimeMillis() - 2000
        val deadline = startedAt + 150000

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            val messages = JSONObject(request("GET", "/api/session/$sessionId/message"))
                .optJSONArray("data") ?: JSONArray()
            val answer = StringBuilder()
            var finished = false
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                if (message.optString("type") != "assistant") continue
                val time = message.optJSONObject("time")
                val created = time?.optLong("created") ?: 0L
                if (created < startedAt) continue
                val content = message.optJSONArray("content") ?: JSONArray()
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> answer.append(part.optString("text", "")).append('\n')
                        "tool" -> {
                            val tool = part.optString("tool", "tool")
                            val state = part.optString("state", "")
                            answer.append("[вызов инструмента: $tool, $state]\n")
                        }
                    }
                }
                if (time?.has("completed") == true || message.has("finish")) finished = true
            }
            if (answer.isNotBlank() && finished) return answer.toString().trim()
        }
        return "Ответ не получен за 150 секунд. Проверь, что в Termux запущен `opencode serve`, а MCP показывает `phone connected`."
    }
}
