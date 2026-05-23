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
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.createModelFromImportedModelInfo
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.delay

private const val TAG = "AGPhoneServerBoot"
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val BOOT_BIND_WAIT_ATTEMPTS = 60
private const val BOOT_BIND_WAIT_DELAY_MILLIS = 2_000L

internal suspend fun startPhoneServerIfAlwaysOn(
  context: Context,
  dataStoreRepository: DataStoreRepository,
) {
  loadPhoneOpenAiServerSettings(dataStoreRepository)
  val mode = PhoneOpenAiServerStore.state.value.autoStartMode
  if (mode != PhoneOpenAiServerAutoStartMode.ALWAYS_ON) {
    Log.d(TAG, "Boot start skipped; mode=$mode")
    return
  }
  if (!waitForPreferredBindAddressIfNeeded()) {
    Log.w(TAG, "Boot start skipped; preferred bind address never became available")
    return
  }
  val model = resolveBootStartModel(context, dataStoreRepository)
  if (model == null) {
    Log.w(TAG, "Boot start requested but no downloadable model could be resolved")
    return
  }
  val modelPath = model.getPath(context)
  if (!File(modelPath).exists()) {
    Log.w(TAG, "Boot start skipped; model file is missing: $modelPath")
    return
  }
  Log.i(TAG, "Boot starting phone server for model='${model.name}' mode=$mode")
  PhoneOpenAiServerManager.start(
    context = context,
    model = model,
    availableModels = listOf(model),
    allowLanNoAuth = PhoneOpenAiServerStore.allowLanNoAuth,
    noAuthSubnetCidr = PhoneOpenAiServerStore.noAuthSubnetCidr,
  )
}

private suspend fun waitForPreferredBindAddressIfNeeded(): Boolean {
  val preferredBindAddress = PhoneOpenAiServerStore.state.value.preferredBindAddress.trim()
  if (preferredBindAddress.isBlank()) {
    return true
  }
  repeat(BOOT_BIND_WAIT_ATTEMPTS) { attempt ->
    if (resolveBindAddress(preferredBindAddress) != null) {
      return true
    }
    Log.i(
      TAG,
      "Waiting for preferred bind address '$preferredBindAddress' to appear on boot (attempt ${attempt + 1}/$BOOT_BIND_WAIT_ATTEMPTS)",
    )
    delay(BOOT_BIND_WAIT_DELAY_MILLIS)
  }
  return resolveBindAddress(preferredBindAddress) != null
}

private fun resolveBootStartModel(
  context: Context,
  dataStoreRepository: DataStoreRepository,
): Model? {
  val selectedModelName = dataStoreRepository.readSecret(ACTIVE_MODEL_SECRET_KEY)?.trim().orEmpty()
  val availableModels = loadBootStartModels(context, dataStoreRepository)
  if (availableModels.isEmpty()) {
    return null
  }
  if (selectedModelName.isNotEmpty()) {
    availableModels.firstOrNull { it.name == selectedModelName }?.let { return it }
  }
  availableModels.firstOrNull { it.name.contains("Gemma_4_E2B_it", ignoreCase = true) }?.let {
    return it
  }
  availableModels.firstOrNull { it.name.contains("Gemma", ignoreCase = true) }?.let { return it }
  return availableModels.firstOrNull()
}

private fun loadBootStartModels(
  context: Context,
  dataStoreRepository: DataStoreRepository,
): List<Model> {
  val models = mutableListOf<Model>()
  readCachedModelAllowlist(context)?.models?.forEach { allowedModel ->
    if (allowedModel.disabled == true) {
      return@forEach
    }
    val model = allowedModel.toModel()
    if (model.runtimeType == RuntimeType.LITERT_LM) {
      models.add(model)
    }
  }
  dataStoreRepository.readImportedModels().mapTo(models) { importedModel: ImportedModel ->
    createModelFromImportedModelInfo(importedModel)
  }
  return models.distinctBy { it.name }
}

private fun readCachedModelAllowlist(context: Context): ModelAllowlist? {
  val file = File(context.filesDir, MODEL_ALLOWLIST_FILENAME)
  if (!file.exists()) {
    return null
  }
  return try {
    Gson().fromJson(file.readText(), ModelAllowlist::class.java)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to read cached model allowlist", e)
    null
  }
}
