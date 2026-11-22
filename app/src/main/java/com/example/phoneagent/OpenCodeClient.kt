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

    fun mcpStatus(): String {
        val json = JSONObject(request("GET", "/api/mcp"))
        val servers = json.optJSONArray("data") ?: JSONArray()
        if (servers.length() == 0) return "MCP-серверов нет — проверь ~/.config/opencode/opencode.json"
        val sb = StringBuilder()
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            sb.append(server.optString("name", "?")).append(": ")
                .append(server.optString("status", "?")).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun createSession(): String {
        val json = JSONObject(request("POST", "/api/session", "{}"))
        return json.getJSONObject("data").getString("id")
    }

    /** Sends a prompt and polls for NEW assistant messages (by id, not by timestamp). */
    fun prompt(sessionId: String, text: String): String {
        val before = JSONObject(request("GET", "/api/session/$sessionId/message"))
            .optJSONArray("data") ?: JSONArray()
        val knownIds = HashSet<String>()
        for (i in 0 until before.length()) {
            knownIds.add(before.optJSONObject(i)?.optString("id", "") ?: "")
        }

        request("POST", "/api/session/$sessionId/prompt", JSONObject().put("text", text).toString())

        val deadline = System.currentTimeMillis() + 120000
        var partial = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            val messages = JSONObject(request("GET", "/api/session/$sessionId/message"))
                .optJSONArray("data") ?: JSONArray()
            val answer = StringBuilder()
            var finished = false
            var errorText = ""

            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                val id = message.optString("id", "")
                if (knownIds.contains(id)) continue
                if (message.optString("type") != "assistant") continue

                val content = message.optJSONArray("content") ?: JSONArray()
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> answer.append(part.optString("text", "")).append('\n')
                        "tool" -> answer.append("[инструмент: ").append(part.optString("tool", "?")).append("]\n")
                    }
                }
                val error = message.optJSONObject("error")
                if (error != null) errorText = error.toString().take(600)
                val time = message.optJSONObject("time")
                if (time?.has("completed") == true || message.has("finish")) finished = true
            }

            if (errorText.isNotBlank()) return "Ошибка OpenCode: $errorText"
            if (answer.isNotBlank()) {
                partial = answer.toString().trim()
                if (finished) return partial
            }
        }
        return if (partial.isNotBlank()) partial
               else "Нет ответа за 120 секунд. Проверь, что в Termux живёт `opencode serve`, а модель авторизована (в чате OpenCode нет ошибки про key/login)."
    }
}
