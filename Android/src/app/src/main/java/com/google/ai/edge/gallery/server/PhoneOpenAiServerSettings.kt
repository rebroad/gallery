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

import com.google.ai.edge.gallery.data.DataStoreRepository

internal const val ACTIVE_MODEL_SECRET_KEY = "active_litertlm_model_name"
internal const val PHONE_SERVER_BIND_ADDRESS_SECRET_KEY = "phone_server_bind_address"
internal const val PHONE_SERVER_PORT_SECRET_KEY = "phone_server_port"
internal const val PHONE_SERVER_AUTO_START_SECRET_KEY = "phone_server_auto_start"
internal const val PHONE_SERVER_STATEFUL_HTTP_RESPONSES_SECRET_KEY =
  "phone_server_stateful_http_responses"
internal const val PHONE_SERVER_MAX_CACHED_HTTP_SESSIONS_SECRET_KEY =
  "phone_server_max_cached_http_sessions"
internal const val PHONE_SERVER_HTTP_SESSION_IDLE_TIMEOUT_MINUTES_SECRET_KEY =
  "phone_server_http_session_idle_timeout_minutes"

enum class PhoneOpenAiServerAutoStartMode {
  DISABLED,
  APP_LAUNCH,
  ALWAYS_ON,
  ;

  val autoStartOnAppLaunch: Boolean
    get() = this != DISABLED

  companion object {
    fun fromStoredValue(rawValue: String?): PhoneOpenAiServerAutoStartMode {
      val normalized = rawValue?.trim().orEmpty()
      if (normalized.isEmpty()) {
        return DISABLED
      }
      if (normalized.equals("true", ignoreCase = true)) {
        return APP_LAUNCH
      }
      if (normalized.equals("false", ignoreCase = true)) {
        return DISABLED
      }
      return runCatching { valueOf(normalized) }.getOrDefault(DISABLED)
    }
  }
}

internal fun loadPhoneOpenAiServerSettings(dataStoreRepository: DataStoreRepository) {
  PhoneOpenAiServerStore.setServerConfig(
    preferredBindAddress = dataStoreRepository.readSecret(PHONE_SERVER_BIND_ADDRESS_SECRET_KEY) ?: "",
    port =
      dataStoreRepository.readSecret(PHONE_SERVER_PORT_SECRET_KEY)?.toIntOrNull()
        ?: PHONE_SERVER_PORT,
    autoStartMode =
      PhoneOpenAiServerAutoStartMode.fromStoredValue(
        dataStoreRepository.readSecret(PHONE_SERVER_AUTO_START_SECRET_KEY)
      ),
  )
  PhoneOpenAiServerStore.setHttpSessionConfig(
    statefulHttpResponses =
      dataStoreRepository.readSecret(PHONE_SERVER_STATEFUL_HTTP_RESPONSES_SECRET_KEY)
        ?.toBooleanStrictOrNull()
        ?: true,
    maxCachedHttpSessions =
      dataStoreRepository.readSecret(PHONE_SERVER_MAX_CACHED_HTTP_SESSIONS_SECRET_KEY)
        ?.toIntOrNull()
        ?: 4,
    httpSessionIdleTimeoutMinutes =
      dataStoreRepository.readSecret(PHONE_SERVER_HTTP_SESSION_IDLE_TIMEOUT_MINUTES_SECRET_KEY)
        ?.toIntOrNull()
        ?: 10,
  )
}
