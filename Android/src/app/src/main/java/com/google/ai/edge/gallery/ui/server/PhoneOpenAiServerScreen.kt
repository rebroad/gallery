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

package com.google.ai.edge.gallery.ui.server

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults.FocusedBorderThickness
import androidx.compose.material3.OutlinedTextFieldDefaults.UnfocusedBorderThickness
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.server.PhoneOpenAiServerManager
import com.google.ai.edge.gallery.server.PhoneOpenAiServerAutoStartMode
import com.google.ai.edge.gallery.server.PhoneOpenAiServerStatus
import com.google.ai.edge.gallery.server.PhoneOpenAiServerStore
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneOpenAiServerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val serverState by PhoneOpenAiServerStore.state.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val selectedDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  val selectedModelDownloaded =
    selectedDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  var availableBindAddresses by remember {
    mutableStateOf(modelManagerViewModel.getPhoneServerBindAddresses())
  }
  var bindMenuExpanded by remember { mutableStateOf(false) }
  var autoStartMenuExpanded by remember { mutableStateOf(false) }
  var portText by remember(serverState.port) { mutableStateOf(serverState.port.toString()) }
  var maxCachedSessionsText by remember(serverState.maxCachedHttpSessions) {
    mutableStateOf(serverState.maxCachedHttpSessions.toString())
  }
  var idleTimeoutText by remember(serverState.httpSessionIdleTimeoutMinutes) {
    mutableStateOf(serverState.httpSessionIdleTimeoutMinutes.toString())
  }
  val isRunning = serverState.status == PhoneOpenAiServerStatus.RUNNING
  val isStarting = serverState.status == PhoneOpenAiServerStatus.STARTING
  val startPulseTransition = rememberInfiniteTransition(label = "phone_server_start_pulse")
  val startPulse =
    if (isStarting) {
      startPulseTransition
        .animateFloat(
          initialValue = 0.0f,
          targetValue = 1.0f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
              repeatMode = RepeatMode.Reverse,
            ),
          label = "phone_server_start_pulse_value",
        )
        .value
    } else {
      0.0f
    }
  val liveStatefulHttpResponses = serverState.liveStatefulHttpResponses
  val effectiveStatefulHttpResponses =
    if (isRunning && liveStatefulHttpResponses != null) {
      liveStatefulHttpResponses
    } else {
      serverState.statefulHttpResponses
    }
  val statusText =
    when (serverState.status) {
      PhoneOpenAiServerStatus.RUNNING -> stringResource(R.string.phone_server_status_running)
      PhoneOpenAiServerStatus.STARTING -> stringResource(R.string.phone_server_status_starting)
      PhoneOpenAiServerStatus.ERROR -> stringResource(R.string.phone_server_status_error)
      PhoneOpenAiServerStatus.STOPPED -> stringResource(R.string.phone_server_status_stopped)
    }
  val autoStartModeText =
    when (serverState.autoStartMode) {
      PhoneOpenAiServerAutoStartMode.DISABLED ->
        stringResource(R.string.phone_server_auto_start_disabled)
      PhoneOpenAiServerAutoStartMode.APP_LAUNCH ->
        stringResource(R.string.phone_server_auto_start_app_launch)
      PhoneOpenAiServerAutoStartMode.ALWAYS_ON ->
        stringResource(R.string.phone_server_auto_start_always_on)
    }

  val selectedBindAddressLabel =
    if (serverState.preferredBindAddress.isBlank()) {
      stringResource(R.string.phone_server_bind_address_auto)
    } else {
      serverState.preferredBindAddress
    }
  fun refreshBindAddresses() {
    availableBindAddresses = modelManagerViewModel.getPhoneServerBindAddresses()
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.phone_server_title),
        leftAction =
          AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = navigateUp),
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(innerPadding)
          .padding(12.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.phone_server_status_label),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = statusText,
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = selectedModel.displayName.ifEmpty { selectedModel.name },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (serverState.status == PhoneOpenAiServerStatus.RUNNING) {
            Text(
              text = "${serverState.host}:${serverState.port}/v1",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              text = stringResource(R.string.phone_server_listen_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Card(
        colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = stringResource(R.string.phone_server_active_model_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = selectedModel.displayName.ifEmpty { selectedModel.name },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          val startButtonText =
            when {
              isRunning -> stringResource(R.string.phone_server_stop)
              isStarting -> stringResource(R.string.phone_server_starting_model)
              else -> stringResource(R.string.phone_server_start)
            }
          Button(
            onClick = {
              if (isStarting) {
                return@Button
              }
              if (isRunning) {
                PhoneOpenAiServerManager.stop(context = context)
              } else {
                PhoneOpenAiServerManager.start(
                  context = context,
                  model = selectedModel,
                  availableModels = modelManagerViewModel.getAllDownloadedModels(),
                  serverToken = serverState.token.takeIf { it.isNotBlank() },
                  allowLanNoAuth = serverState.allowLanNoAuth,
                  noAuthSubnetCidr = serverState.noAuthSubnetCidr,
                )
              }
            },
            enabled = selectedModelDownloaded || isRunning || isStarting,
            colors =
              if (isStarting) {
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              } else {
                ButtonDefaults.buttonColors()
              },
            modifier =
              Modifier.fillMaxWidth().graphicsLayer {
                if (isStarting) {
                  val pulse = 1f + (startPulse * 0.06f)
                  scaleX = pulse
                  scaleY = pulse
                }
              },
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(text = startButtonText)
            }
          }
        }
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = stringResource(R.string.phone_server_bind_address_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
              Button(
                onClick = {
                  refreshBindAddresses()
                  bindMenuExpanded = true
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(text = selectedBindAddressLabel)
              }
              DropdownMenu(
                expanded = bindMenuExpanded,
                onDismissRequest = { bindMenuExpanded = false },
              ) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.phone_server_bind_address_auto)) },
                  onClick = {
                    bindMenuExpanded = false
                    modelManagerViewModel.setPhoneServerBindAddress("")
                  },
                )
                availableBindAddresses.forEach { address ->
                  DropdownMenuItem(
                    text = { Text(address) },
                    onClick = {
                      bindMenuExpanded = false
                      modelManagerViewModel.setPhoneServerBindAddress(address)
                    },
                  )
                }
              }
            }
            Text(
              text = stringResource(R.string.phone_server_bind_address_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          val portFieldInteractionSource = remember { MutableInteractionSource() }
          BasicTextField(
            value = portText,
            onValueChange = { next ->
              if (next.isEmpty() || next.all(Char::isDigit)) {
                portText = next
                next.toIntOrNull()?.let { modelManagerViewModel.setPhoneServerPort(it) }
              }
            },
            singleLine = true,
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(120.dp).heightIn(min = 48.dp),
            decorationBox = { innerTextField ->
              OutlinedTextFieldDefaults.DecorationBox(
                value = portText,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = portFieldInteractionSource,
                contentPadding =
                  androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                  ),
                container = {
                  OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = portFieldInteractionSource,
                    colors = OutlinedTextFieldDefaults.colors(),
                    shape = OutlinedTextFieldDefaults.shape,
                    focusedBorderThickness = FocusedBorderThickness,
                    unfocusedBorderThickness = UnfocusedBorderThickness,
                  )
                },
              )
            },
          )

          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.phone_server_auto_start_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = stringResource(R.string.phone_server_auto_start_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Box {
              Button(
                onClick = {
                  autoStartMenuExpanded = true
                },
              ) {
                Text(text = autoStartModeText)
              }
              DropdownMenu(
                expanded = autoStartMenuExpanded,
                onDismissRequest = { autoStartMenuExpanded = false },
              ) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.phone_server_auto_start_disabled)) },
                  onClick = {
                    autoStartMenuExpanded = false
                    modelManagerViewModel.setPhoneServerAutoStartMode(
                      PhoneOpenAiServerAutoStartMode.DISABLED,
                    )
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.phone_server_auto_start_app_launch)) },
                  onClick = {
                    autoStartMenuExpanded = false
                    modelManagerViewModel.setPhoneServerAutoStartMode(
                      PhoneOpenAiServerAutoStartMode.APP_LAUNCH,
                    )
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.phone_server_auto_start_always_on)) },
                  onClick = {
                    autoStartMenuExpanded = false
                    modelManagerViewModel.setPhoneServerAutoStartMode(
                      PhoneOpenAiServerAutoStartMode.ALWAYS_ON,
                    )
                  },
                )
              }
            }
          }
        }
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.phone_server_advanced_settings_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.phone_server_stateful_http_responses_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = stringResource(R.string.phone_server_stateful_http_responses_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = effectiveStatefulHttpResponses,
              onCheckedChange = { checked ->
                modelManagerViewModel.setPhoneServerStatefulHttpResponses(checked)
              },
            )
          }
          Text(
            text =
              when {
                !isRunning -> stringResource(R.string.phone_server_stateful_http_responses_hint)
                liveStatefulHttpResponses == true -> stringResource(R.string.phone_server_stateful_http_responses_operational_on)
                liveStatefulHttpResponses == false -> stringResource(R.string.phone_server_stateful_http_responses_operational_off)
                else -> stringResource(R.string.phone_server_stateful_http_responses_operational_unknown)
              },
            style = MaterialTheme.typography.bodySmall,
            color =
              when (liveStatefulHttpResponses) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
              },
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = maxCachedSessionsText,
              onValueChange = { next ->
                if (next.isEmpty() || next.all(Char::isDigit)) {
                  maxCachedSessionsText = next
                  next.toIntOrNull()?.let { modelManagerViewModel.setPhoneServerMaxCachedHttpSessions(it) }
                }
              },
              label = { Text(stringResource(R.string.phone_server_max_cached_http_sessions_label)) },
              supportingText = {
                Text(stringResource(R.string.phone_server_max_cached_http_sessions_hint))
              },
              singleLine = true,
              textStyle = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = idleTimeoutText,
              onValueChange = { next ->
                if (next.isEmpty() || next.all(Char::isDigit)) {
                  idleTimeoutText = next
                  next.toIntOrNull()?.let {
                    modelManagerViewModel.setPhoneServerHttpSessionIdleTimeoutMinutes(it)
                  }
                }
              },
              label = { Text(stringResource(R.string.phone_server_http_session_idle_timeout_label)) },
              supportingText = {
                Text(stringResource(R.string.phone_server_http_session_idle_timeout_hint))
              },
              singleLine = true,
              textStyle = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      if (serverState.error != null) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = stringResource(R.string.error),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              text = serverState.error ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }

    }
  }
}
