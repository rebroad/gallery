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
import com.google.ai.edge.litertlm.OpenAiChatRequest
import com.google.ai.edge.litertlm.OpenAiChatMessage
import com.google.ai.edge.litertlm.OpenAiHttpServer
import com.google.ai.edge.litertlm.OpenAiModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import java.net.Inet4Address
import java.net.NetworkInterface
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
        if (server == null) {
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
    val model = resolveModelToServe()
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

    val bindAddress = findBindAddress()
    if (bindAddress == null) {
      PhoneOpenAiServerStore.setError("No LAN address found. Connect to Wi-Fi and try again.")
      stopSelf()
      return
    }

    notificationToken = PhoneOpenAiServerStore.ensureToken()
    notificationHost = bindAddress.hostAddress ?: ""
    notificationPort = PHONE_SERVER_PORT
    PhoneOpenAiServerStore.setRunning(
      host = notificationHost,
      port = notificationPort,
      token = notificationToken,
      modelName = model.name,
    )

    val currentModel = model
    val currentInstance = instance
    server =
      OpenAiHttpServer(
        availableModelsProvider = {
          PhoneOpenAiServerStore.availableModels.ifEmpty {
              PhoneOpenAiServerStore.currentModel?.let { listOf(it) } ?: emptyList()
            }
            .map { OpenAiModelInfo(id = it.name, owned_by = "gallery") }
        },
        currentModelNameProvider = { PhoneOpenAiServerStore.currentModel?.name ?: model.name },
        chatConversationFactory = { request -> buildConversation(currentInstance, currentModel, request) },
        authTokenProvider = { PhoneOpenAiServerStore.state.value.token },
        lanAuthBypassProvider = { hostAddress ->
          PhoneOpenAiServerStore.allowLanNoAuth &&
            isInLanBypassSubnet(hostAddress, PhoneOpenAiServerStore.noAuthSubnetCidr)
        },
        onError = { message -> Log.w(TAG, message) },
      )

    try {
      server?.start(bindAddress = bindAddress, port = PHONE_SERVER_PORT)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to bind server socket", e)
      PhoneOpenAiServerStore.setError(e.message ?: "Failed to bind server socket")
      server = null
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
    Log.i(TAG, "Server started at http://${notificationHost}:${notificationPort}/v1")
  }

  private fun stopServer(reason: String) {
    Log.i(TAG, reason)
    try {
      server?.close()
    } catch (_: Exception) {
    }
    server = null
    PhoneOpenAiServerStore.stop()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun buildConversation(
    instance: LlmModelInstance,
    model: Model,
    request: OpenAiChatRequest,
  ): Conversation? {
    val activeModel = resolveModelForRequest(request.model) ?: model
    val activeInstance = activeModel.instance as? LlmModelInstance ?: instance

    val promptIndex = request.messages.indexOfLast { it.role.lowercase() != "system" }
    val historyMessages =
      if (promptIndex <= 0) {
        emptyList()
      } else {
        request.messages.take(promptIndex).filter { it.role.lowercase() != "system" }
      }

    val systemText =
      request.messages.filter { it.role.lowercase() == "system" }.joinToString("\n\n") {
        it.extractText()
      }

    val topK =
      request.top_k ?: activeModel.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP =
      request.top_p ?: activeModel.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      request.temperature
        ?: activeModel.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)

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

    return activeInstance.engine.createConversation(
      ConversationConfig(
        systemInstruction = systemInstruction,
        initialMessages = initialMessages,
        samplerConfig = samplerConfig,
      )
    )
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
    }
    return resolveModelToServe()
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
