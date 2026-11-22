package com.example.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object McpServer {
    private const val PORT = 8765

    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "phone-mcp-worker") }

    fun isRunning(): Boolean = serverSocket?.isClosed == false

    fun start(context: Context) {
        if (isRunning()) return
        val app = context.applicationContext
        Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", PORT))
                serverSocket = socket
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Throwable) { break }
                    workers.execute { handle(client, app) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("PhoneAgent", "MCP server failed", t)
                serverSocket = null
            }
        }, "phone-mcp-http").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket, app: Context) {
        try {
            socket.soTimeout = 20000
            val reader = BufferedReader(InputStreamReader(BufferedInputStream(socket.getInputStream()), StandardCharsets.UTF_8))
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""

            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val length = contentLength.coerceAtMost(1_000_000)
            val bodyChars = CharArray(length)
            var readTotal = 0
            while (readTotal < length) {
                val read = reader.read(bodyChars, readTotal, length - readTotal)
                if (read < 0) break
                readTotal += read
            }
            val body = String(bodyChars, 0, readTotal)

            if (method != "POST" || !path.startsWith("/mcp")) {
                send(output, 404, "Not Found", "text/plain")
                return
            }
            if (body.isBlank()) {
                send(output, 202, "", "application/json")
                return
            }

            val request = JSONObject(body)
            val notification = !request.has("id") || request.isNull("id")
            if (notification) {
                send(output, 202, "", "application/json")
                return
            }

            val id = request.get("id")
            val requestMethod = request.optString("method", "")
            val params = request.optJSONObject("params") ?: JSONObject()

            val result: JSONObject
            try {
                result = when (requestMethod) {
                    "initialize" -> {
                        val requested = params.optString("protocolVersion", "")
                        val version = if (requested in setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) requested
                                      else "2025-11-25"
                        obj(
                            "protocolVersion" to version,
                            "capabilities" to obj("tools" to obj("listChanged" to false)),
                            "serverInfo" to obj("name" to "phone-android", "version" to "0.1.0"),
                            "instructions" to "Управление Android-устройством без ПК. Перед действиями вызови ui_dump, чтобы увидеть текущий экран и координаты элементов."
                        )
                    }
                    "ping" -> JSONObject()
                    "tools/list" -> obj("tools" to tools())
                    "tools/call" -> {
                        val name = params.optString("name", "")
                        val args = params.optJSONObject("arguments") ?: JSONObject()
                        val toolResult = ToolExecutor(app).execute(name, args)
                        val content = JSONArray().put(obj("type" to "text", "text" to toolResult.text))
                        val response = obj("content" to content)
                        if (toolResult.isError) response.put("isError", true)
                        response
                    }
                    "resources/list" -> obj("resources" to JSONArray())
                    "prompts/list" -> obj("prompts" to JSONArray())
                    else -> throw NoSuchMethodException(requestMethod)
                }
            } catch (t: Throwable) {
                val error = obj(
                    "code" to if (t is NoSuchMethodException) -32601 else -32603,
                    "message" to (t.message ?: t.javaClass.simpleName)
                )
                send(output, 200, obj("jsonrpc" to "2.0", "id" to id, "error" to error).toString(), "application/json")
                return
            }

            send(output, 200, obj("jsonrpc" to "2.0", "id" to id, "result" to result).toString(), "application/json")
        } catch (t: Throwable) {
            android.util.Log.e("PhoneAgent", "MCP request failed", t)
            try {
                send(BufferedOutputStream(socket.getOutputStream()), 500, "{\"error\":\"internal\"}", "application/json")
            } catch (_: Throwable) {}
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun send(output: BufferedOutputStream, code: Int, body: String, type: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun schema(vararg properties: Pair<String, JSONObject>, required: Array<String> = emptyArray()): JSONObject {
        val props = JSONObject()
        properties.forEach { (key, value) -> props.put(key, value) }
        val result = obj("type" to "object", "properties" to props)
        if (required.isNotEmpty()) result.put("required", JSONArray(required))
        return result
    }

    private fun str(description: String) = obj("type" to "string", "description" to description)
    private fun int(description: String) = obj("type" to "integer", "description" to description)

    private fun tools(): JSONArray {
        val t = JSONArray()
        t.put(tool("device_info", "Device model, Android version and screen resolution.", schema()))
        t.put(tool("battery", "Battery level, charging state and temperature.", schema()))
        t.put(tool("ui_dump", "Dump the current screen as a tree of UI elements with text, content descriptions, bounds and clickable flags. CALL THIS FIRST before any tap/type.", schema("maxNodes" to int("Maximum number of nodes, default 350"))))
        t.put(tool("ui_tap", "Tap the screen at coordinates (x, y) in pixels. Use bounds from ui_dump.", schema("x" to int("X coordinate"), "y" to int("Y coordinate"), required = arrayOf("x", "y"))))
        t.put(tool("ui_swipe", "Swipe from (x1,y1) to (x2,y2) in pixels.", schema(
            "x1" to int("Start X"), "y1" to int("Start Y"), "x2" to int("End X"), "y2" to int("End Y"),
            "durationMs" to int("Duration in milliseconds, default 300"),
            required = arrayOf("x1", "y1", "x2", "y2")
        )))
        t.put(tool("ui_type", "Set text into the currently focused input field. Works with Cyrillic. Tap the field first.", schema("text" to str("Text to enter"), required = arrayOf("text"))))
        t.put(tool("ui_key", "Press a system key/action: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER, LOCKSCREEN.", schema("key" to str("Key name"), required = arrayOf("key"))))
        t.put(tool("open_app", "Launch an app by package name or alias (telegram, gmail, chrome, settings, vk, discord...).", schema("app" to str("Package name or alias"), required = arrayOf("app"))))
        t.put(tool("open_uri", "Open a URI in the matching app: tg://, mailto:, https://, tel:, sms:.", schema("uri" to str("URI to open"), required = arrayOf("uri"))))
        t.put(tool("sms_compose", "Open the SMS editor with a prefilled number and text. Does NOT send the message.", schema("number" to str("Phone number"), "text" to str("Message body"), required = arrayOf("number", "text"))))
        t.put(tool("notifications", "Read active notifications (package, title, text). Needs notification access enabled in the app.", schema("limit" to int("Maximum number of notifications, default 50"))))
        t.put(tool("clipboard_set", "Put text into the clipboard.", schema("text" to str("Text"), required = arrayOf("text"))))
        t.put(tool("wait_ms", "Wait for the given number of milliseconds (max 10000). Useful after opening an app.", schema("ms" to int("Milliseconds, default 1000"))))
        t.put(tool("screenshot", "Take a screenshot via MediaProjection and save it to a PNG file. Needs screenshot permission granted in the app.", schema("path" to str("Optional destination path in device storage"))))
        return t
    }

    private fun tool(name: String, description: String, inputSchema: JSONObject): JSONObject =
        obj("name" to name, "description" to description, "inputSchema" to inputSchema)

    fun toolNames(): List<String> = (0 until tools().length()).map { tools().getJSONObject(it).getString("name") }
}
