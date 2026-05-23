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

package com.google.ai.edge.gallery.data

import com.google.ai.edge.gallery.proto.ImportedModel

fun createModelFromImportedModelInfo(info: ImportedModel): Model {
  val accelerators: MutableList<Accelerator> =
    info.llmConfig.compatibleAcceleratorsList
      .mapNotNull { acceleratorLabel ->
        when (acceleratorLabel.trim()) {
          Accelerator.GPU.label -> Accelerator.GPU
          Accelerator.CPU.label -> Accelerator.CPU
          Accelerator.NPU.label -> Accelerator.NPU
          else -> null // Ignore unknown accelerator labels
        }
      }
      .toMutableList()
  val llmMaxToken = info.llmConfig.defaultMaxTokens
  val llmSupportImage = info.llmConfig.supportImage
  val llmSupportAudio = info.llmConfig.supportAudio
  val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
  val llmSupportMobileActions = info.llmConfig.supportMobileActions
  val llmSupportThinking = info.llmConfig.supportThinking
  val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
  val configs: MutableList<Config> =
    createLlmChatConfigs(
        defaultMaxToken = llmMaxToken,
        defaultTopK = info.llmConfig.defaultTopk,
        defaultTopP = info.llmConfig.defaultTopp,
        defaultTemperature = info.llmConfig.defaultTemperature,
        accelerators = accelerators,
        supportThinking = llmSupportThinking,
        supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
      )
      .toMutableList()
  val capabilities: MutableList<ModelCapability> = mutableListOf()
  val capabilityToTaskTypes: MutableMap<ModelCapability, List<String>> = mutableMapOf()
  if (llmSupportThinking) {
    capabilities.add(ModelCapability.LLM_THINKING)
    capabilityToTaskTypes[ModelCapability.LLM_THINKING] =
      listOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
      )
  }
  if (llmSupportSpeculativeDecoding) {
    capabilities.add(ModelCapability.SPECULATIVE_DECODING)
    capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
      listOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
        BuiltInTaskId.LLM_PROMPT_LAB,
      )
  }
  val model =
    Model(
      name = info.fileName,
      url = "",
      configs = configs,
      sizeInBytes = info.fileSize,
      downloadFileName = "$IMPORTS_DIR/${info.fileName}",
      showBenchmarkButton = false,
      showRunAgainButton = false,
      imported = true,
      llmSupportImage = llmSupportImage,
      llmSupportAudio = llmSupportAudio,
      llmSupportTinyGarden = llmSupportTinyGarden,
      llmSupportMobileActions = llmSupportMobileActions,
      capabilities = capabilities.toList(),
      capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
      llmMaxToken = llmMaxToken,
      accelerators = accelerators,
      // We assume all imported models are LLM for now.
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
    )
  model.preProcess()

  return model
}
