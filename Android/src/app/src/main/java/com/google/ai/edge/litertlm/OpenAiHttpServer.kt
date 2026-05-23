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

package com.google.ai.edge.litertlm

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OpenAiModelInfo(
  val id: String,
  val `object`: String = "model",
  val owned_by: String = "litertlm",
)

data class OpenAiModelsResponse(
  val `object`: String = "list",
  val data: List<OpenAiModelInfo>,
)

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

data class OpenAiResponsesRequest(
  val model: String? = null,
  val input: JsonElement? = null,
  val messages: List<OpenAiChatMessage> = emptyList(),
  val conversation: JsonElement? = null,
  val audio_base64: String? = null,
  val stream: Boolean = false,
  val temperature: Double? = null,
  val top_p: Double? = null,
  val top_k: Int? = null,
  val max_tokens: Int? = null,
)

data class OpenAiAudioTranscriptionRequest(
  val model: String? = null,
  val prompt: String? = null,
  val audio_base64: String? = null,
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

data class OpenAiResponseOutputContent(
  val type: String,
  val text: String,
  val annotations: List<Any> = emptyList(),
)

data class OpenAiResponseOutput(
  val id: String,
  val type: String,
  val role: String,
  val status: String,
  val content: List<OpenAiResponseOutputContent>,
)

data class OpenAiConversation(
  val id: String,
  val `object`: String = "conversation",
  val created_at: Long,
)

data class OpenAiResponse(
  val id: String,
  val `object`: String = "response",
  val created_at: Long,
  val model: String,
  val conversation: OpenAiConversation? = null,
  val output: List<OpenAiResponseOutput>,
)

private data class HttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: ByteArray,
)

private data class HttpSessionState(
  val modelName: String,
  val conversationConfig: ConversationConfig,
  val conversationId: String,
  val history: List<Message>,
  var lastAccessMillis: Long = System.currentTimeMillis(),
  val mutex: Mutex = Mutex(),
)

private const val DEFAULT_MAX_HTTP_SESSIONS = 4
private const val DEFAULT_HTTP_SESSION_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L

class OpenAiHttpServer(
  private val availableModelsProvider: () -> List<OpenAiModelInfo>,
  private val currentModelNameProvider: () -> String?,
  private val chatSessionFactory: (OpenAiChatRequest) -> Session?,
  private val responsesConversationFactory: ((ConversationConfig) -> Conversation?)? = null,
  private val audioTranscriptionConversationFactory: ((ConversationConfig) -> Conversation?)? =
    null,
  private val statefulResponsesEnabledProvider: () -> Boolean = { true },
  private val maxCachedHttpSessionsProvider: () -> Int = { DEFAULT_MAX_HTTP_SESSIONS },
  private val sessionIdleTimeoutMillisProvider: () -> Long =
    { DEFAULT_HTTP_SESSION_IDLE_TIMEOUT_MILLIS },
  private val authTokenProvider: () -> String? = { null },
  private val lanAuthBypassProvider: (String?) -> Boolean = { false },
  private val onError: (String) -> Unit = {},
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val gson = Gson()
  private val sessionRegistryLock = Any()
  private val activeResponses = LinkedHashMap<String, HttpSessionState>(16, 0.75f, true)
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null
  private var cleanupJob: Job? = null
  private var bindAddress: Inet4Address? = null
  private var port: Int = 0

  val servedModelName: String?
    get() = currentModelNameProvider()

  fun start(bindAddress: Inet4Address, port: Int) {
    check(serverSocket == null) { "Server is already running." }
    this.bindAddress = bindAddress
    this.port = port

    serverSocket = ServerSocket()
    serverSocket!!.reuseAddress = true
    serverSocket!!.bind(InetSocketAddress(bindAddress, port))

    acceptJob =
      scope.launch {
        while (true) {
          val socket =
            try {
              serverSocket?.accept() ?: break
            } catch (_: IOException) {
              break
          }
          launch { handleClient(socket) }
        }
      }

    cleanupJob =
      scope.launch {
        while (true) {
          val cleanupDelayMillis =
            when {
              !isStatefulResponsesEnabled() -> 60_000L
              sessionIdleTimeoutMillis() <= 0L -> 60_000L
              else -> maxOf(1_000L, minOf(sessionIdleTimeoutMillis() / 2, 60_000L))
          }
          delay(cleanupDelayMillis)
          synchronized(sessionRegistryLock) {
            evictExpiredSessionsLocked(System.currentTimeMillis())
          }
        }
      }
  }

  fun stop(reason: String) {
    synchronized(sessionRegistryLock) { activeResponses.clear() }
    try {
      serverSocket?.close()
    } catch (_: Exception) {
    }
    serverSocket = null
    acceptJob?.cancel()
    acceptJob = null
    cleanupJob?.cancel()
    cleanupJob = null
  }

  override fun close() {
    stop("Server closed")
    scope.cancel()
  }

  fun host(): String = bindAddress?.hostAddress.orEmpty()

  fun port(): Int = port

  private suspend fun handleClient(socket: Socket) {
    socket.use { client ->
      try {
        val request = readRequest(client) ?: return
        val expectedToken = authTokenProvider()?.takeIf { it.isNotBlank() }
        val authOk =
          expectedToken == null || request.headers["authorization"] == "Bearer $expectedToken"
        val allowLanNoAuth = lanAuthBypassProvider(client.inetAddress.hostAddress)
        if (!authOk && request.path != "/health" && !allowLanNoAuth) {
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
            writeJsonResponse(
              client,
              200,
              "OK",
              OpenAiModelsResponse(
                data = availableModelsProvider(),
              ),
            )
          }
          request.method == "POST" && request.path == "/v1/chat/completions" -> {
            val payload =
              gson.fromJson(request.body.toString(StandardCharsets.UTF_8), OpenAiChatRequest::class.java)
            handleChatCompletion(client, payload)
          }
          request.method == "POST" && request.path == "/v1/responses" -> {
            val payload =
              gson.fromJson(request.body.toString(StandardCharsets.UTF_8), OpenAiResponsesRequest::class.java)
            handleResponses(client, payload)
          }
          request.method == "POST" && request.path == "/v1/audio/transcriptions" -> {
            val payload =
              gson.fromJson(
                request.body.toString(StandardCharsets.UTF_8),
                OpenAiAudioTranscriptionRequest::class.java,
              )
            handleAudioTranscription(client, payload)
          }
          request.method == "GET" && request.path == "/health" -> {
            writeJsonResponse(
              client,
              200,
              "OK",
              jsonObjectOf(
                "status" to "ok",
                "model" to (servedModelName ?: ""),
                "stateful_http_responses" to isStatefulResponsesEnabled(),
                "max_cached_http_sessions" to maxCachedHttpSessions(),
                "http_session_idle_timeout_millis" to sessionIdleTimeoutMillis(),
              ),
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
        onError("Client handling failed: ${e.message ?: e::class.java.simpleName}")
      }
    }
  }

  private suspend fun handleAudioTranscription(
    socket: Socket,
    request: OpenAiAudioTranscriptionRequest,
  ) {
    val conversation = audioTranscriptionConversationFactory?.invoke(request.toConversationConfig())
    if (conversation == null) {
      writeJsonResponse(
        socket,
        503,
        "Service Unavailable",
        jsonObjectOf("error" to jsonObjectOf("message" to "Model is not initialized")),
      )
      return
    }

    val audioBase64 = request.audio_base64?.takeIf { it.isNotBlank() }
    if (audioBase64 == null) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf("error" to jsonObjectOf("message" to "Missing audio_base64")),
      )
      return
    }

    val prompt = request.prompt?.trim().orEmpty()
    try {
      conversation.use {
        val contents =
          if (prompt.isNotEmpty()) {
            Contents.of(
              Content.Text(prompt),
              Content.AudioBytes(Base64.getDecoder().decode(audioBase64)),
            )
          } else {
            Contents.of(Content.AudioBytes(Base64.getDecoder().decode(audioBase64)))
          }
        val transcript = conversation.sendMessage(contents).toString()
        writeJsonResponse(
          socket,
          200,
          "OK",
          jsonObjectOf(
            "text" to transcript,
            "prompt" to prompt,
          ),
        )
      }
    } catch (e: IllegalArgumentException) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf("error" to jsonObjectOf("message" to "Invalid audio_base64: ${e.message}")),
      )
    }
  }

  private suspend fun handleChatCompletion(socket: Socket, request: OpenAiChatRequest) {
    val session = chatSessionFactory(request)
    if (session == null) {
      writeJsonResponse(
        socket,
        503,
        "Service Unavailable",
        jsonObjectOf("error" to jsonObjectOf("message" to "Model is not initialized")),
      )
      return
    }

    val nonSystemMessages = request.messages.filter { it.role.lowercase() != "system" }
    if (nonSystemMessages.isEmpty()) {
      closeSession(session)
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
    try {
      if (request.stream) {
        streamChat(socket, session, promptMessage, modelName(request.model))
      } else {
        val responseText =
          session.generateContent(listOf(InputData.Text(promptMessage.extractText())))
        val response =
          OpenAiChatCompletionResponse(
            id = "chatcmpl-${UUID.randomUUID()}",
            created = System.currentTimeMillis() / 1000L,
            model = modelName(request.model),
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
      closeSession(session)
    }
  }

  private suspend fun handleResponses(socket: Socket, request: OpenAiResponsesRequest) {
    val prompt = request.extractPrompt()
    if (prompt.isBlank()) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf("error" to jsonObjectOf("message" to "Missing input")),
      )
      return
    }

    val statefulResponsesEnabled = isStatefulResponsesEnabled()
    val requestConversationId = request.extractConversationId()
    if (requestConversationId != null && !statefulResponsesEnabled) {
      writeJsonResponse(
        socket,
        400,
        "Bad Request",
        jsonObjectOf(
          "error" to
            jsonObjectOf(
              "message" to "Stateful HTTP responses are disabled. Omit conversation."
            )
        ),
      )
      return
    }

    val now = System.currentTimeMillis()
    val existingSessionState =
      requestConversationId?.let { conversationId ->
        synchronized(sessionRegistryLock) {
          evictExpiredSessionsLocked(now)
          activeResponses[conversationId]
        }
      }

    val sessionState =
      if (requestConversationId != null) {
        val parentSessionState =
          existingSessionState
            ?: run {
              writeJsonResponse(
                socket,
                404,
                "Not Found",
                jsonObjectOf(
                  "error" to
                    jsonObjectOf(
                      "message" to "Unknown conversation ${requestConversationId}"
                    )
                ),
              )
              return
            }
        parentSessionState.copy(lastAccessMillis = now)
      } else {
        val newConversationId = "conv_${UUID.randomUUID()}"
        HttpSessionState(
          modelName = modelName(request.model),
          conversationConfig = request.toConversationConfig(),
          conversationId = newConversationId,
          history = emptyList(),
          lastAccessMillis = now,
        )
      }
    val conversationId = sessionState.conversationId

    val responseId = "resp-${UUID.randomUUID()}"
    val responseText =
      try {
        if (request.stream) {
          val responseBuffer = StringBuilder()
          streamResponse(
            socket,
            sessionState,
            request,
            prompt,
            responseId,
            sessionState.modelName,
            responseBuffer,
          )
          responseBuffer.toString()
        } else {
          runStatefulResponse(sessionState, request, prompt)
        }
      } catch (e: IllegalArgumentException) {
        writeJsonResponse(
          socket,
          400,
          "Bad Request",
          jsonObjectOf("error" to jsonObjectOf("message" to "Invalid audio_base64: ${e.message}")),
        )
        return
      }

    val committedSessionState =
      sessionState.copy(
        lastAccessMillis = System.currentTimeMillis(),
        history =
          sessionState.history +
            Message.user(buildResponseContents(request, prompt)) +
            Message.model(responseText),
      )
    if (statefulResponsesEnabled) {
      synchronized(sessionRegistryLock) {
        activeResponses[conversationId] = committedSessionState
        evictSessionsLocked(System.currentTimeMillis())
      }
    }
    if (!request.stream) {
      writeJsonResponse(
        socket,
        200,
        "OK",
        OpenAiResponse(
          id = responseId,
          created_at = System.currentTimeMillis() / 1000L,
          model = sessionState.modelName,
          conversation =
            if (statefulResponsesEnabled) {
              OpenAiConversation(
                id = conversationId,
                created_at = now / 1000L,
              )
            } else {
              null
            },
          output =
            listOf(
              OpenAiResponseOutput(
                id = "msg-${UUID.randomUUID()}",
                type = "message",
                role = "assistant",
                status = "completed",
                content =
                  listOf(
                    OpenAiResponseOutputContent(
                      type = "output_text",
                      text = responseText,
                    )
                  ),
              )
            ),
        ),
      )
    }
  }

  private suspend fun streamChat(
    socket: Socket,
    session: Session,
    promptMessage: OpenAiChatMessage,
    modelName: String,
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

    try {
      session.generateContentStream(
        listOf(InputData.Text(promptMessage.extractText())),
        object : ResponseCallback {
          override fun onNext(response: String) {
            if (response.isNotEmpty()) {
              writeSseEvent(
                writer,
                OpenAiChatCompletionChunk(
                  id = id,
                  created = created,
                  model = modelName,
                  choices =
                    listOf(
                      OpenAiChatChoice(
                        delta = OpenAiChatCompletionMessage(content = response),
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
                model = modelName,
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
                "model" to modelName,
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
      completed.await()
    } finally {
      if (!completed.isCompleted) {
        completed.complete(Unit)
      }
    }
  }

  private suspend fun streamResponse(
    socket: Socket,
    sessionState: HttpSessionState,
    request: OpenAiResponsesRequest,
    prompt: String,
    responseId: String,
    modelName: String,
    responseBuffer: StringBuilder,
  ) {
    val outputStream = BufferedOutputStream(socket.getOutputStream())
    val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
    writer.write("HTTP/1.1 200 OK\r\n")
    writer.write("Content-Type: text/event-stream\r\n")
    writer.write("Cache-Control: no-cache\r\n")
    writer.write("Connection: close\r\n")
    writer.write("\r\n")
    writer.flush()

    val created = System.currentTimeMillis() / 1000L
    val completed = CompletableDeferred<Unit>()

    writer.write(
      "event: response.created\n" +
        "data: ${gson.toJson(jsonObjectOf("id" to responseId, "status" to "in_progress"))}\n\n"
    )
    writer.flush()

    sessionState.mutex.withLock {
      try {
        sessionState.lastAccessMillis = System.currentTimeMillis()
        val conversation =
          responsesConversationFactory?.invoke(
            sessionState.conversationConfig.copy(initialMessages = sessionState.history),
          )
            ?: throw IllegalStateException("Model is not initialized")
        conversation.use {
          conversation.sendMessageAsync(
            buildResponseContents(request, prompt),
            object : MessageCallback {
              override fun onMessage(message: Message) {
                val text = message.toString()
                if (text.isNotEmpty()) {
                  responseBuffer.append(text)
                  writer.write(
                    "event: response.output_text.delta\n" +
                      "data: ${gson.toJson(jsonObjectOf("delta" to jsonObjectOf("text" to text)))}\n\n"
                  )
                  writer.flush()
                }
              }

              override fun onDone() {
                writer.write(
                  "event: response.completed\n" +
                    "data: ${gson.toJson(jsonObjectOf("id" to responseId, "status" to "completed"))}\n\n"
                )
                writer.write("data: [DONE]\n\n")
                writer.flush()
                if (!completed.isCompleted) {
                  completed.complete(Unit)
                }
              }

              override fun onError(throwable: Throwable) {
                writer.write(
                  "event: response.error\n" +
                    "data: ${gson.toJson(jsonObjectOf("error" to (throwable.message ?: throwable::class.java.simpleName)))}\n\n"
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
      } finally {
        if (!completed.isCompleted) {
          completed.complete(Unit)
        }
      }
    }
  }

  private fun closeSession(session: Session?) {
    if (session == null) return
    try {
      session.close()
    } catch (_: Exception) {
    }
  }

  private fun evictSessionsLocked(nowMillis: Long): List<HttpSessionState> {
    val evicted = mutableListOf<HttpSessionState>()
    evicted.addAll(evictExpiredSessionsLocked(nowMillis))
    while (activeResponses.size > maxCachedHttpSessions()) {
      val oldestEntry = activeResponses.entries.iterator().next()
      activeResponses.remove(oldestEntry.key)
      evicted.add(oldestEntry.value)
    }
    return evicted
  }

  private fun evictExpiredSessionsLocked(nowMillis: Long): List<HttpSessionState> {
    if (!isStatefulResponsesEnabled()) {
      return emptyList()
    }
    val idleTimeoutMillis = sessionIdleTimeoutMillis()
    if (idleTimeoutMillis <= 0L) {
      return emptyList()
    }
    val expiredKeys =
      activeResponses.entries
        .filter { nowMillis - it.value.lastAccessMillis >= idleTimeoutMillis }
        .map { it.key }
    if (expiredKeys.isEmpty()) {
      return emptyList()
    }
    return expiredKeys.mapNotNull { activeResponses.remove(it) }
  }

  private fun modelName(requestModel: String?): String = requestModel?.takeIf { it.isNotBlank() } ?: currentModelNameProvider().orEmpty()

  private fun isStatefulResponsesEnabled(): Boolean = statefulResponsesEnabledProvider()

  private fun maxCachedHttpSessions(): Int = maxCachedHttpSessionsProvider().coerceAtLeast(1)

  private fun sessionIdleTimeoutMillis(): Long = sessionIdleTimeoutMillisProvider().coerceAtLeast(0L)

  private fun OpenAiResponsesRequest.toConversationConfig(): ConversationConfig =
    ConversationConfig(
      samplerConfig =
        when {
          top_k != null || top_p != null || temperature != null ->
            SamplerConfig(
              topK = top_k ?: 40,
              topP = top_p ?: 0.95,
              temperature = temperature ?: 0.7,
            )
          else -> null
        },
    )

  private fun OpenAiAudioTranscriptionRequest.toConversationConfig(): ConversationConfig =
    ConversationConfig()

  private fun buildResponseContents(request: OpenAiResponsesRequest, prompt: String): Contents {
    val contents = mutableListOf<Content>()
    if (prompt.isNotBlank()) {
      contents.add(Content.Text(prompt))
    }
    val audioBase64 = request.audio_base64?.takeIf { it.isNotBlank() }
    if (audioBase64 != null) {
      contents.add(Content.AudioBytes(Base64.getDecoder().decode(audioBase64)))
    }
    return Contents.of(contents)
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

  private fun OpenAiResponsesRequest.extractPrompt(): String {
    input?.let { raw ->
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

    val messagesFromRequest = messages.filter { it.role.lowercase() != "system" }
    if (messagesFromRequest.isNotEmpty()) {
      return messagesFromRequest.last().extractText()
    }
    return ""
  }

  private fun OpenAiResponsesRequest.extractConversationId(): String? {
    val raw = conversation ?: return null
    return when {
      raw.isJsonNull -> null
      raw.isJsonPrimitive -> raw.asString.trim().takeIf { it.isNotEmpty() }
      raw.isJsonObject -> {
        val idElement = raw.asJsonObject.get("id")
        if (idElement != null && idElement.isJsonPrimitive) {
          idElement.asString.trim().takeIf { it.isNotEmpty() }
        } else {
          null
        }
      }
      else -> null
    }
  }

  private suspend fun runStatefulResponse(
    sessionState: HttpSessionState,
    request: OpenAiResponsesRequest,
    prompt: String,
  ): String {
    val conversation =
      responsesConversationFactory?.invoke(
        sessionState.conversationConfig.copy(initialMessages = sessionState.history),
      ) ?: throw IllegalStateException("Model is not initialized")
    conversation.use {
      return conversation.sendMessage(buildResponseContents(request, prompt)).toString()
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
