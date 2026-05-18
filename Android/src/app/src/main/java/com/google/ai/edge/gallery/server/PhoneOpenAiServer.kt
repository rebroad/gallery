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

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val PHONE_SERVER_PORT = 11435

enum class PhoneOpenAiServerStatus {
  STOPPED,
  STARTING,
  RUNNING,
  ERROR,
}

data class PhoneOpenAiServerState(
  val status: PhoneOpenAiServerStatus = PhoneOpenAiServerStatus.STOPPED,
  val host: String = "",
  val port: Int = PHONE_SERVER_PORT,
  val token: String = "",
  val modelName: String = "",
  val error: String? = null,
)

object PhoneOpenAiServerStore {
  private val _state = MutableStateFlow(PhoneOpenAiServerState())
  val state = _state.asStateFlow()

  @Volatile var currentModel: Model? = null
    private set

  @Volatile var availableModels: List<Model> = emptyList()
    private set

  fun setCurrentModel(model: Model?) {
    currentModel = model
    if (model != null) {
      _state.update { it.copy(modelName = model.name, error = null) }
    }
  }

  fun setAvailableModels(models: List<Model>) {
    availableModels = models.filter { it.isLlm }
  }

  fun beginStarting(token: String, port: Int = PHONE_SERVER_PORT) {
    _state.update {
      it.copy(
        status = PhoneOpenAiServerStatus.STARTING,
        host = "",
        port = port,
        token = token,
        error = null,
      )
    }
  }

  fun setRunning(host: String, port: Int, token: String, modelName: String) {
    _state.update {
      it.copy(
        status = PhoneOpenAiServerStatus.RUNNING,
        host = host,
        port = port,
        token = token,
        modelName = modelName,
        error = null,
      )
    }
  }

  fun setError(message: String) {
    _state.update { it.copy(status = PhoneOpenAiServerStatus.ERROR, error = message) }
  }

  fun stop() {
    _state.update {
      it.copy(
        status = PhoneOpenAiServerStatus.STOPPED,
        host = "",
        port = PHONE_SERVER_PORT,
        token = "",
        error = null,
      )
    }
  }

  fun ensureToken(): String {
    val curState = _state.value
    if (curState.token.isNotEmpty()) {
      return curState.token
    }
    val token = UUID.randomUUID().toString().replace("-", "")
    _state.update { it.copy(token = token) }
    return token
  }
}

object PhoneOpenAiServerManager {
  const val ACTION_START = "com.google.ai.edge.gallery.server.action.START"
  const val ACTION_STOP = "com.google.ai.edge.gallery.server.action.STOP"

  fun start(context: Context, model: Model, availableModels: List<Model>): String? {
    val curStatus = PhoneOpenAiServerStore.state.value.status
    if (curStatus == PhoneOpenAiServerStatus.STARTING || curStatus == PhoneOpenAiServerStatus.RUNNING) {
      return null
    }
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      val message = "Model '${model.name}' is not initialized."
      PhoneOpenAiServerStore.setError(message)
      return message
    }

    PhoneOpenAiServerStore.setCurrentModel(model)
    PhoneOpenAiServerStore.setAvailableModels(availableModels)
    val token = PhoneOpenAiServerStore.ensureToken()
    PhoneOpenAiServerStore.beginStarting(token = token)

    val intent = Intent(context, PhoneOpenAiServerService::class.java).apply {
      action = ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
    return null
  }

  fun stop(context: Context) {
    val intent = Intent(context, PhoneOpenAiServerService::class.java).apply {
      action = ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
  }
}
