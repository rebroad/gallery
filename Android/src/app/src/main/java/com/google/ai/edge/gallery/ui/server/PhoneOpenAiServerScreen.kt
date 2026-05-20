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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.server.PhoneOpenAiServerManager
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
  var portText by remember(serverState.port) { mutableStateOf(serverState.port.toString()) }
  val isRunning = serverState.status == PhoneOpenAiServerStatus.RUNNING
  val isStarting = serverState.status == PhoneOpenAiServerStatus.STARTING
  val statusText =
    when (serverState.status) {
      PhoneOpenAiServerStatus.RUNNING -> stringResource(R.string.phone_server_status_running)
      PhoneOpenAiServerStatus.STARTING -> stringResource(R.string.phone_server_status_starting)
      PhoneOpenAiServerStatus.ERROR -> stringResource(R.string.phone_server_status_error)
      PhoneOpenAiServerStatus.STOPPED -> stringResource(R.string.phone_server_status_stopped)
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
        subtitle = stringResource(R.string.phone_server_subtitle),
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
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
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
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
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
          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = {
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
            enabled = !isStarting && (selectedModelDownloaded || isRunning),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text =
                if (isRunning) stringResource(R.string.phone_server_stop)
                else stringResource(R.string.phone_server_start)
            )
          }
        }
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = stringResource(R.string.phone_server_bind_address_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(
              text = stringResource(R.string.phone_server_bind_address_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          OutlinedTextField(
            value = portText,
            onValueChange = { next ->
              if (next.isEmpty() || next.all(Char::isDigit)) {
                portText = next
                next.toIntOrNull()?.let { modelManagerViewModel.setPhoneServerPort(it) }
              }
            },
            label = { Text(stringResource(R.string.phone_server_port_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 160.dp),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.phone_server_auto_start_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = stringResource(R.string.phone_server_auto_start_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
              checked = serverState.autoStartOnAppLaunch,
              onCheckedChange = { checked -> modelManagerViewModel.setPhoneServerAutoStart(checked) },
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
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.phone_server_endpoint_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (serverState.status == PhoneOpenAiServerStatus.RUNNING) {
                "http://${serverState.host}:${serverState.port}/v1"
              } else {
                stringResource(R.string.phone_server_listen_hint)
              },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.phone_server_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
