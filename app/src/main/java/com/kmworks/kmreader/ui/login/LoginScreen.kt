package com.kmworks.kmreader.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmworks.kmreader.R

/**
 * Login / connect server (mirrors LoginView): a centered narrow column with the server URL, an optional
 * instance display name, plus either username-password or API Key authentication; a successful probe
 * enters the main UI automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    /** When used as "Add Server" from a signed-in state, notify the caller to close this page after login. */
    onLoggedIn: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.connectedUser) {
        if (state.connectedUser != null) onLoggedIn?.invoke()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.widthIn(max = 400.dp),
            ) {
                Text("KMReader", style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::updateServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.login_server_url)) },
                    placeholder = { Text("https://komga.example.com") },
                    leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    singleLine = true,
                    enabled = !state.loading,
                )
                Spacer(Modifier.height(12.dp))
                // Optional instance name (mirrors iOS LoginView "Instance Name (Optional)"),
                // persisted into the saved server row instead of falling back to the host.
                OutlinedTextField(
                    value = state.instanceName,
                    onValueChange = viewModel::updateInstanceName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.login_instance_name)) },
                    placeholder = { Text(stringResource(R.string.login_instance_name_placeholder)) },
                    supportingText = { Text(stringResource(R.string.server_edit_display_name_hint)) },
                    singleLine = true,
                    enabled = !state.loading,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AuthMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.authMode == mode,
                            onClick = { viewModel.updateAuthMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AuthMode.entries.size,
                            ),
                            enabled = !state.loading,
                        ) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (state.authMode) {
                    AuthMode.Password -> {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = viewModel::updateUsername,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_username)) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            singleLine = true,
                            enabled = !state.loading,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::updatePassword,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_password)) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.loading,
                        )
                    }

                    AuthMode.ApiKey -> {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = viewModel::updateApiKey,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_auth_api_key)) },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.loading,
                        )
                    }
                }
                state.error?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.connectedUser?.let { user ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.login_connected_as, user.email),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = viewModel::login,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canSubmit,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.login_connect))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
