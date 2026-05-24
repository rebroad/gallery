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
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SharedModelStorage
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.OpenAiChatMessage
import com.google.ai.edge.litertlm.OpenAiChatRequest
import com.google.ai.edge.litertlm.OpenAiHttpServer
import com.google.ai.edge.litertlm.OpenAiModelInfo
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AGPhoneOpenAiServer"
private const val NOTIFICATION_CHANNEL_ID = "phone_openai_server"
private const val NOTIFICATION_ID = 0x5A17

class PhoneOpenAiServerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var server: OpenAiHttpServer? = null
  private var notificationHost: String = ""
  private var notificationPort: Int = 0
  private var notificationModelName: String = ""
  private var servedModel: Model? = null

  private fun logModelLifecycle(event: String, model: Model? = null, extra: String = "") {
    val currentModel = model ?: servedModel
    val modelName = currentModel?.name?.takeIf { it.isNotBlank() } ?: notificationModelName
    val modelInstanceState =
      when {
        currentModel == null -> ""
        currentModel.instance != null -> " resident=true"
        else -> " resident=false"
      }
    val suffix = buildString {
      if (modelName.isNotBlank()) {
        append(" model=")
        append(modelName)
      }
      append(modelInstanceState)
      if (extra.isNotBlank()) {
        append(" ")
        append(extra)
      }
    }
    Log.i(TAG, "lifecycle $event$suffix")
  }

  private fun buildDebugModelInfo(model: Model): JsonObject {
    val state = PhoneOpenAiServerStore.state.value
    return JsonObject().apply {
      addProperty("name", model.name)
      addProperty("display_name", model.displayName.ifEmpty { model.name })
      addProperty("runtime_type", model.runtimeType.name)
      addProperty("resident", model.instance != null)
      addProperty("initializing", model.initializing)
      addProperty("clean_up_after_init", model.cleanUpAfterInit)
      addProperty("hosting", PhoneOpenAiServerStore.isHostingModel(model))
      addProperty("server_status", state.status.name)
      addProperty("server_host", state.host)
      addProperty("server_port", state.port)
      addProperty("stateful_http_responses", state.statefulHttpResponses)
      addProperty("max_cached_http_sessions", state.maxCachedHttpSessions)
      addProperty(
        "http_session_idle_timeout_millis",
        state.httpSessionIdleTimeoutMinutes * 60_000L,
      )
      state.liveStatefulHttpResponses?.let { addProperty("live_stateful_http_responses", it) }
      addProperty(
        "live_stateful_http_responses_checked_at_millis",
        state.liveStatefulHttpResponsesCheckedAtMillis,
      )
      state.liveHealthError?.let { addProperty("live_health_error", it) }
      state.error?.let { addProperty("error", it) }
    }
  }

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
        if (server == null) {
          startForeground(
            NOTIFICATION_ID,
            buildNotification(
              title = "LiteRT-LM server starting",
              content = "Preparing the selected model…",
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
              0
            },
          )
        }
        val preferredBindAddress = PhoneOpenAiServerStore.state.value.preferredBindAddress.trim()
        val bindAddress = resolveBindAddress(preferredBindAddress)
        if (server == null && bindAddress == null) {
          val errorMessage =
            if (preferredBindAddress.isNotEmpty()) {
              "Selected interface $preferredBindAddress is unavailable. Wait for ZeroTier or choose another interface."
            } else {
              "No LAN address found. Connect Wi-Fi or your VPN interface and try again."
            }
          Log.w(TAG, errorMessage)
          PhoneOpenAiServerStore.setError(errorMessage)
          stopServer(errorMessage)
          return START_NOT_STICKY
        }

        val desiredModel = resolveModelToServe()
        val desiredHost = bindAddress?.hostAddress.orEmpty()
        val desiredPort = PhoneOpenAiServerStore.state.value.port
        val desiredModelName = desiredModel?.name.orEmpty()
        val needsRestart =
          server != null &&
            (notificationHost != desiredHost ||
              notificationPort != desiredPort ||
              notificationModelName != desiredModelName)

        if (server == null) {
          scope.launch { startServer() }
        } else if (needsRestart) {
          scope.launch {
            stopServer("Restarting with updated settings", stopServiceSelf = false)
            startServer()
          }
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
    val preferredBindAddress = PhoneOpenAiServerStore.state.value.preferredBindAddress.trim()
    val bindAddress = resolveBindAddress(preferredBindAddress)
    if (bindAddress == null) {
      val errorMessage =
        if (preferredBindAddress.isNotEmpty()) {
          "Selected interface $preferredBindAddress is unavailable. Wait for ZeroTier or choose another interface."
        } else {
          "No LAN address found. Connect Wi-Fi or your VPN interface and try again."
        }
      Log.w(TAG, errorMessage)
      PhoneOpenAiServerStore.setError(errorMessage)
      stopServer(errorMessage)
      return
    }

    val model = resolveModelToServe()
    servedModel = model
    logModelLifecycle("server_start_requested", model, "bind=${bindAddress.hostAddress.orEmpty()}")
    val instance = model?.let { ensureModelInitialized(it) }
    if (model == null || instance == null) {
      PhoneOpenAiServerStore.setError("Initialize the selected model before starting the server.")
      stopServer("Initialize the selected model before starting the server.")
      return
    }
    if (model.runtimeType != RuntimeType.LITERT_LM) {
      PhoneOpenAiServerStore.setError("Only LiteRT-LM models are supported by the phone server.")
      stopServer("Only LiteRT-LM models are supported by the phone server.")
      return
    }

    notificationHost = bindAddress.hostAddress ?: ""
    notificationPort = PhoneOpenAiServerStore.state.value.port
    notificationModelName = model.name
    PhoneOpenAiServerStore.setRunning(
      host = notificationHost,
      port = notificationPort,
      token = "",
      modelName = model.name,
    )

    server =
      OpenAiHttpServer(
        availableModelsProvider = {
          PhoneOpenAiServerStore.availableModels.ifEmpty {
              PhoneOpenAiServerStore.currentModel?.let { listOf(it) } ?: emptyList()
            }
            .map { OpenAiModelInfo(id = it.name, owned_by = "gallery") }
        },
        currentModelNameProvider = { PhoneOpenAiServerStore.currentModel?.name ?: model.name },
        debugModelInfoProvider = {
          PhoneOpenAiServerStore.currentModel?.let { buildDebugModelInfo(it) }
            ?: buildDebugModelInfo(model)
        },
        chatSessionFactory = { request -> createHttpSession(request.model, request) },
        responsesConversationFactory = { config -> createHttpConversation(config) },
        audioTranscriptionConversationFactory = { config -> createHttpConversation(config) },
        statefulResponsesEnabledProvider = {
          PhoneOpenAiServerStore.state.value.statefulHttpResponses
        },
        maxCachedHttpSessionsProvider = {
          PhoneOpenAiServerStore.state.value.maxCachedHttpSessions
        },
        sessionIdleTimeoutMillisProvider = {
          PhoneOpenAiServerStore.state.value.httpSessionIdleTimeoutMinutes * 60_000L
        },
        authTokenProvider = { null },
        lanAuthBypassProvider = { hostAddress ->
          PhoneOpenAiServerStore.allowLanNoAuth &&
            isInLanBypassSubnet(hostAddress, PhoneOpenAiServerStore.noAuthSubnetCidr)
        },
        onError = { message -> Log.w(TAG, message) },
      )

    try {
      server?.start(bindAddress = bindAddress, port = notificationPort)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to bind server socket", e)
      PhoneOpenAiServerStore.setError(e.message ?: "Failed to bind server socket")
      PhoneOpenAiServerStore.setLiveHttpSessionHealth(null, error = e.message)
      server = null
      servedModel = null
      stopServer(e.message ?: "Failed to bind server socket")
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
    Log.i(TAG, "Server started at http://${notificationHost}:${notificationPort}/v1")
    logModelLifecycle("server_started", model)
  }

  private fun stopServer(reason: String) {
    stopServer(reason, stopServiceSelf = true)
  }

  private fun stopServer(reason: String, stopServiceSelf: Boolean) {
    Log.i(TAG, reason)
    logModelLifecycle("server_stopping")
    try {
      server?.close()
    } catch (_: Exception) {
    }
    server = null
    notificationModelName = ""
    PhoneOpenAiServerStore.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
    logModelLifecycle("server_stopped")
    servedModel = null
    if (stopServiceSelf) {
      stopSelf()
    }
  }

  private fun createHttpSession(
    requestModelName: String?,
    request: OpenAiChatRequest,
  ): Session? {
    val activeModel =
      if (requestModelName.isNullOrBlank()) {
        resolveModelToServe()
      } else {
        resolveModelForRequest(requestModelName)
      }
    val activeInstance = activeModel?.instance as? LlmModelInstance
    if (activeModel == null || activeInstance == null) {
      return null
    }
    return try {
      activeInstance.engine.createSession(request.toSessionConfig(activeModel))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create HTTP session for '${activeModel.name}'", e)
      null
    }
  }

  private fun createHttpConversation(conversationConfig: ConversationConfig): Conversation? {
    val activeModel = resolveModelToServe()
    val activeInstance = activeModel?.instance as? LlmModelInstance
    if (activeModel == null || activeInstance == null) {
      return null
    }
    return try {
      activeInstance.engine.createConversation(conversationConfig)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create HTTP responses conversation for '${activeModel.name}'", e)
      null
    }
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

  private fun resolveModelToServe(): Model? {
    PhoneOpenAiServerStore.currentModel?.let { return it }
    val available = PhoneOpenAiServerStore.availableModels
    if (available.isEmpty()) {
      return null
    }
    available.firstOrNull { it.name.contains("Gemma_4_E2B_it", ignoreCase = true) }?.let {
      PhoneOpenAiServerStore.setCurrentModel(it)
      return it
    }
    available.firstOrNull { it.name.contains("Gemma", ignoreCase = true) }?.let {
      PhoneOpenAiServerStore.setCurrentModel(it)
      return it
    }
    val first = available.first()
    PhoneOpenAiServerStore.setCurrentModel(first)
    return first
  }

  private fun resolveModelForRequest(requestModelName: String?): Model? {
    val available = PhoneOpenAiServerStore.availableModels
    val candidateName = requestModelName?.trim().orEmpty()
    if (candidateName.isNotEmpty()) {
      available.firstOrNull { it.name == candidateName }?.let {
        PhoneOpenAiServerStore.setCurrentModel(it)
        return it
      }
      PhoneOpenAiServerStore.currentModel?.let { current ->
        if (current.name == candidateName) {
          return current
        }
      }
      return null
    }
    return resolveModelToServe()
  }

  private suspend fun ensureModelInitialized(model: Model): LlmModelInstance? {
    val instance = model.instance as? LlmModelInstance
    if (instance != null) {
      logModelLifecycle("model_reused", model)
      return instance
    }
    if (model.runtimeType != RuntimeType.LITERT_LM) {
      return null
    }
    val modelPath = model.getPath(this)
    if (SharedModelStorage.isSharedModelPath(modelPath) && !Environment.isExternalStorageManager()) {
      Log.e(
        TAG,
        "Missing all-files access for shared model path $modelPath. Grant MANAGE_EXTERNAL_STORAGE and retry.",
      )
      return null
    }

    logModelLifecycle("model_initializing", model, "path=$modelPath")
    val done = CompletableDeferred<String>()
    try {
      model.runtimeHelper.initialize(
        context = this,
        model = model,
        taskId = BuiltInTaskId.LLM_CHAT,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { done.complete(it) },
        systemInstruction = null,
        tools = emptyList(),
        enableConversationConstrainedDecoding = false,
        coroutineScope = scope,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start model initialization for '${model.name}'", e)
      return null
    }
    val error = done.await()
    if (error.isNotEmpty()) {
      Log.e(TAG, "Failed to initialize model '${model.name}': $error")
      return null
    }
    logModelLifecycle("model_initialized", model)
    return model.instance as? LlmModelInstance
  }

  private fun OpenAiChatRequest.toSessionConfig(model: Model): SessionConfig =
    SessionConfig(buildSamplerConfig(model, top_k, top_p, temperature))

  private fun buildSamplerConfig(
    model: Model,
    topK: Int?,
    topP: Double?,
    temperature: Double?,
  ): SamplerConfig {
    val resolvedTopK = topK ?: model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val resolvedTopP = topP ?: model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP).toDouble()
    val resolvedTemperature =
      temperature
        ?: model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE).toDouble()
    return SamplerConfig(
      topK = resolvedTopK,
      topP = resolvedTopP,
      temperature = resolvedTemperature,
    )
  }

  private fun isInLanBypassSubnet(hostAddress: String?, subnetCidr: String): Boolean {
    if (hostAddress.isNullOrBlank()) {
      return false
    }
    val prefix = subnetCidr.substringBefore("/").trim()
    return prefix.isNotEmpty() && hostAddress.startsWith(prefix.substringBeforeLast(".") + ".")
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
}
