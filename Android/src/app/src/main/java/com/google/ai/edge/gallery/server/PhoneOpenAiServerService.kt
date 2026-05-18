/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGPhoneOpenAiServer"
private const val NOTIFICATION_CHANNEL_ID = "phone_openai_server"
private const val NOTIFICATION_ID = 0x5A17

data class OpenAiModelInfo(val id: String, val `object`: String = "model", val owned_by: String = "gallery")

data class OpenAiModelsResponse(val `object`: String = "list", val data: List<OpenAiModelInfo>)

data class OpenAiChatMessage(val role: String, val content: JsonElement? = null)

data class OpenAiChatRequest(
  val model: String? = null,
  val messages: List<OpenAiChatMessage> = emptyList(),
  val stream: Boolean = false,
  val temperature: Double? = null,
  val top_p: Double? = null,
  val top_k: Int? = null,
  val max_tokens: Int? = null,
)

data class OpenAiChatCompletionMessage(val role: String = "assistant", val content: String = "")

data class OpenAiChatChoice(
  val index: Int = 0,
  val message: OpenAiChatCompletionMessage? = null,
  val delta: OpenAiChatCompletionMessage? = null,
  val finish_reason: String? = null,
)

data class OpenAiUsage(
  val prompt_tokens: Int = 0,
  val completion_tokens: Int = 0,
  val total_tokens: Int = 0,
)

data class OpenAiChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChatChoice>,
  val usage: OpenAiUsage = OpenAiUsage(),
)

data class OpenAiChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChatChoice>,
)

private data class HttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: ByteArray,
)

class PhoneOpenAiServerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val gson = Gson()
  private val requestMutex = Mutex()
  private val activeConversation = AtomicReference<Conversation?>(null)
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null
  private var servedModelName: String? = null
  private var notificationToken: String = ""
  private var notificationHost: String = ""
  private var notificationPort: Int = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      PhoneOpenAiServerManager.ACTION_STOP -> {
        stopServer("Stopped by request")
        return START_NOT_STICKY
      }
      else -> {
        if (serverSocket == null) {
          scope.launch { startServer() }
        }
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopServer("Service destroyed")
    scope.cancel()
    super.onDestroy()
  }

  private suspend fun startServer() {
    val model = PhoneOpenAiServerStore.currentModel
    val instance = model?.instance as? LlmModelInstance
    if (model == null || instance == null) {
      PhoneOpenAiServerStore.setError("Initialize the selected model before starting the server.")
      stopSelf()
      return
    }
    if (model.runtimeType != RuntimeType.LITERT_LM) {
      PhoneOpenAiServerStore.setError("Only LiteRT-LM models are supported by the phone server.")
      stopSelf()
      return
    }

    servedModelName = model.name
    notificationToken = PhoneOpenAiServerStore.ensureToken()
    val bindAddress = findBindAddress()
    if (bindAddress == null) {
      PhoneOpenAiServerStore.setError("No LAN address found. Connect to Wi-Fi and try again.")
      stopSelf()
      return
    }

    val serverPort = PHONE_SERVER_PORT
    notificationHost = bindAddress.hostAddress ?: ""
    notificationPort = serverPort
    PhoneOpenAiServerStore.setRunning(
      host = notificationHost,
      port = notificationPort,
      token = notificationToken,
      modelName = model.name,
    )

    try {
      serverSocket = ServerSocket()
      serverSocket!!.reuseAddress = true
      serverSocket!!.bind(InetSocketAddress(bindAddress, serverPort))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to bind server socket", e)
      PhoneOpenAiServerStore.setError(e.message ?: "Failed to bind server socket")
      stopSelf()
      return
    }

    startForeground(
      NOTIFICATION_ID,
      buildNotification(
        title = "LiteRT-LM server running",
        content = "${notificationHost}:${notificationPort} on ${model.displayName.ifEmpty { model.name }}",
      ),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      },
    )

    acceptJob =
      scope.launch {
        Log.i(TAG, "Server started at http://${notificationHost}:${notificationPort}/v1")
        while (true) {
          val socket =
            try {
              serverSocket?.accept() ?: break
            } catch (e: IOException) {
              break
            }
          launch { handleClient(socket) }
        }
      }
  }

  private fun stopServer(reason: String) {
    Log.i(TAG, reason)
    activeConversation.getAndSet(null)?.let {
      try {
        it.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to cancel active conversation", e)
      }
      try {
        it.close()
      } catch (_: Exception) {
      }
    }
    try {
      serverSocket?.close()
    } catch (_: Exception) {
    }
    serverSocket = null
    acceptJob?.cancel()
    acceptJob = null
    PhoneOpenAiServerStore.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private suspend fun handleClient(socket: Socket) {
    socket.use { client ->
      try {
        val request = readRequest(client) ?: return
        val authOk =
          request.headers["authorization"] ==
            "Bearer ${PhoneOpenAiServerStore.state.value.token}"
        if (!authOk && request.path != "/health") {
          writeJsonResponse(
            client,
            401,
            "Unauthorized",
            jsonObjectOf("error" to jsonObjectOf("message" to "Missing or invalid bearer token")),
          )
          return
        }

        when {
          request.method == "GET" && request.path == "/v1/models" -> {
            val models =
              PhoneOpenAiServerStore.availableModels.ifEmpty {
                PhoneOpenAiServerStore.currentModel?.let { listOf(it) } ?: emptyList()
              }
            writeJsonResponse(
              client,
              200,
              "OK",
              OpenAiModelsResponse(data = models.map { OpenAiModelInfo(id = it.name) }),
            )
          }
          request.method == "POST" && request.path == "/v1/chat/completions" -> {
            val payload =
              gson.fromJson(
                request.body.toString(StandardCharsets.UTF_8),
                OpenAiChatRequest::class.java,
              )
            handleChatCompletion(client, payload)
          }
          request.method == "GET" && request.path == "/health" -> {
            writeJsonResponse(
              client,
              200,
              "OK",
              jsonObjectOf("status" to "ok", "model" to (servedModelName ?: "")),
            )
          }
          else -> {
            writeJsonResponse(
              client,
              404,
              "Not Found",
              jsonObjectOf("error" to jsonObjectOf("message" to "Unknown path ${request.path}")),
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Client handling failed", e)
      }
    }
  }

  private suspend fun handleChatCompletion(socket: Socket, request: OpenAiChatRequest) {
    val model = PhoneOpenAiServerStore.currentModel
    val instance = model?.instance as? LlmModelInstance
    if (model == null || instance == null) {
      writeJsonResponse(
        socket,
        503,
        "Service Unavailable",
        jsonObjectOf("error" to jsonObjectOf("message" to "Model is not initialized")),
      )
      return
    }
    if (servedModelName != null && request.model != null && request.model != servedModelName) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf("error" to jsonObjectOf("message" to "Requested model is not loaded")),
      )
      return
    }

    val nonSystemMessages = request.messages.filter { it.role.lowercase() != "system" }
    if (nonSystemMessages.isEmpty()) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf("error" to jsonObjectOf("message" to "No prompt message supplied")),
      )
      return
    }

    val promptIndex = request.messages.indexOfLast { it.role.lowercase() != "system" }
    val promptMessage = request.messages[promptIndex]
    val historyMessages =
      request.messages.take(promptIndex).filter { it.role.lowercase() != "system" }
    val systemText =
      request.messages.filter { it.role.lowercase() == "system" }.joinToString("\n\n") {
        it.extractText()
      }

    val conversation = buildConversation(instance, model, systemText, historyMessages, request)
    activeConversation.set(conversation)
    try {
      if (request.stream) {
        streamChat(socket, model, conversation, promptMessage)
      } else {
        val responseText =
          requestMutex.withLock {
            conversation.sendMessage(Contents.of(promptMessage.extractText()))
          }.toString()
        val response =
          OpenAiChatCompletionResponse(
            id = "chatcmpl-${UUID.randomUUID()}",
            created = System.currentTimeMillis() / 1000L,
            model = model.name,
            choices =
              listOf(
                OpenAiChatChoice(
                  message = OpenAiChatCompletionMessage(content = responseText),
                  finish_reason = "stop",
                )
              ),
          )
        writeJsonResponse(socket, 200, "OK", response)
      }
    } finally {
      activeConversation.compareAndSet(conversation, null)
      try {
        conversation.close()
      } catch (_: Exception) {
      }
    }
  }

  private suspend fun streamChat(
    socket: Socket,
    model: Model,
    conversation: Conversation,
    promptMessage: OpenAiChatMessage,
  ) {
    val outputStream = BufferedOutputStream(socket.getOutputStream())
    val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
    writer.write("HTTP/1.1 200 OK\r\n")
    writer.write("Content-Type: text/event-stream\r\n")
    writer.write("Cache-Control: no-cache\r\n")
    writer.write("Connection: close\r\n")
    writer.write("\r\n")
    writer.flush()

    val id = "chatcmpl-${UUID.randomUUID()}"
    val created = System.currentTimeMillis() / 1000L
    val completed = CompletableDeferred<Unit>()

    requestMutex.withLock {
      conversation.sendMessageAsync(
        Contents.of(promptMessage.extractText()),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val delta = message.toString()
            if (delta.isNotEmpty()) {
              writeSseEvent(
                writer,
                OpenAiChatCompletionChunk(
                  id = id,
                  created = created,
                  model = model.name,
                  choices =
                    listOf(
                      OpenAiChatChoice(
                        delta = OpenAiChatCompletionMessage(content = delta),
                      )
                    ),
                ),
              )
            }
          }

          override fun onDone() {
            writeSseEvent(
              writer,
              OpenAiChatCompletionChunk(
                id = id,
                created = created,
                model = model.name,
                choices =
                  listOf(
                    OpenAiChatChoice(
                      delta = OpenAiChatCompletionMessage(content = ""),
                      finish_reason = "stop",
                    )
                  ),
              ),
            )
            writer.write("data: [DONE]\n\n")
            writer.flush()
            if (!completed.isCompleted) {
              completed.complete(Unit)
            }
          }

          override fun onError(throwable: Throwable) {
            val finishReason = if (throwable is CancellationException) "cancelled" else "error"
            writeSseEvent(
              writer,
              jsonObjectOf(
                "id" to id,
                "object" to "chat.completion.chunk",
                "created" to created,
                "model" to model.name,
                "choices" to
                  listOf(
                    mapOf(
                      "index" to 0,
                      "delta" to mapOf("content" to ""),
                      "finish_reason" to finishReason,
                    )
                  ),
              ),
            )
            writer.write("data: [DONE]\n\n")
            writer.flush()
            if (!completed.isCompleted) {
              completed.complete(Unit)
            }
          }
        },
      )
    }
    completed.await()
  }

  private fun buildConversation(
    instance: LlmModelInstance,
    model: Model,
    systemText: String,
    historyMessages: List<OpenAiChatMessage>,
    request: OpenAiChatRequest,
  ): Conversation {
    val topK =
      request.top_k ?: model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP =
      request.top_p ?: model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      request.temperature
        ?: model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)

    val samplerConfig =
      SamplerConfig(
        topK = topK,
        topP = topP.toDouble(),
        temperature = temperature.toDouble(),
      )

    val initialMessages =
      historyMessages.mapNotNull {
        when (it.role.lowercase()) {
          "user" -> Message.user(Contents.of(it.extractText()))
          "assistant" -> Message.model(Contents.of(it.extractText()))
          "tool" -> Message.tool(Contents.of(it.extractText()))
          else -> null
        }
      }

    val systemInstruction =
      if (systemText.isBlank()) {
        null
      } else {
        Contents.of(systemText)
      }

    return instance.engine.createConversation(
      ConversationConfig(
        systemInstruction = systemInstruction,
        initialMessages = initialMessages,
        samplerConfig = samplerConfig,
      )
    )
  }

  private fun readRequest(socket: Socket): HttpRequest? {
    val input = BufferedInputStream(socket.getInputStream())
    val requestLine = input.readHttpLine() ?: return null
    if (requestLine.isBlank()) {
      return null
    }
    val lineParts = requestLine.split(" ", limit = 3)
    if (lineParts.size < 2) {
      return null
    }
    val method = lineParts[0]
    val path = lineParts[1]
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = input.readHttpLine() ?: break
      if (line.isEmpty()) {
        break
      }
      val idx = line.indexOf(':')
      if (idx > 0) {
        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }
    }
    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    val body = input.readExactBytes(contentLength)
    return HttpRequest(method = method, path = path, headers = headers, body = body)
  }

  private fun writeJsonResponse(
    socket: Socket,
    statusCode: Int,
    statusText: String,
    payload: Any,
  ) {
    val json = gson.toJson(payload)
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    val out = BufferedOutputStream(socket.getOutputStream())
    val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
    writer.write("HTTP/1.1 $statusCode $statusText\r\n")
    writer.write("Content-Type: application/json; charset=utf-8\r\n")
    writer.write("Content-Length: ${bytes.size}\r\n")
    writer.write("Connection: close\r\n")
    writer.write("\r\n")
    writer.flush()
    out.write(bytes)
    out.flush()
  }

  private fun writeSseEvent(writer: OutputStreamWriter, payload: Any) {
    val json = gson.toJson(payload)
    for (line in json.split('\n')) {
      writer.write("data: $line\n")
    }
    writer.write("\n")
    writer.flush()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val notificationManager = getSystemService<NotificationManager>() ?: return
    val channel =
      NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          "LiteRT-LM Server",
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply { description = "Notifications for the phone-hosted OpenAI-compatible server" }
    notificationManager.createNotificationChannel(channel)
  }

  private fun buildNotification(title: String, content: String) =
    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(content)
      .setOngoing(true)
      .addAction(
        0,
        "Stop",
        PendingIntent.getService(
          this,
          1,
          Intent(this, PhoneOpenAiServerService::class.java).apply {
            action = PhoneOpenAiServerManager.ACTION_STOP
          },
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      .build()

  private fun findBindAddress(): Inet4Address? {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (networkInterface in interfaces) {
      if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
        continue
      }
      val addresses = networkInterface.inetAddresses
      for (address in addresses) {
        if (
          address is Inet4Address &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            address.isSiteLocalAddress
        ) {
          return address
        }
      }
    }
    return null
  }

  private fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val obj = JsonObject()
    for ((key, value) in pairs) {
      when (value) {
        null -> obj.add(key, JsonNull.INSTANCE)
        is String -> obj.addProperty(key, value)
        is Number -> obj.addProperty(key, value)
        is Boolean -> obj.addProperty(key, value)
        is JsonElement -> obj.add(key, value)
        else -> obj.add(key, gson.toJsonTree(value))
      }
    }
    return obj
  }

  private fun OpenAiChatMessage.extractText(): String {
    val raw = content ?: return ""
    return when {
      raw.isJsonNull -> ""
      raw.isJsonPrimitive -> raw.asString
      raw.isJsonArray -> raw.asJsonArray.joinToString(separator = "") { element ->
        if (element.isJsonPrimitive) element.asString else element.toString()
      }
      raw.isJsonObject && raw.asJsonObject.has("text") ->
        raw.asJsonObject.get("text").asString
      else -> raw.toString()
    }
  }

  private fun BufferedInputStream.readHttpLine(): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
      val byte = read()
      if (byte == -1) {
        if (buffer.size() == 0) {
          return null
        }
        break
      }
      if (byte == '\n'.code) {
        break
      }
      if (byte != '\r'.code) {
        buffer.write(byte)
      }
    }
    return buffer.toString(StandardCharsets.UTF_8.name())
  }

  private fun BufferedInputStream.readExactBytes(length: Int): ByteArray {
    if (length <= 0) {
      return ByteArray(0)
    }
    val data = ByteArray(length)
    var offset = 0
    while (offset < length) {
      val count = read(data, offset, length - offset)
      if (count == -1) {
        break
      }
      offset += count
    }
    return if (offset == length) data else data.copyOf(offset)
  }
}
