package com.kmworks.kmreader.ui.login

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.UserDto
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.awaitResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** Authentication method. */
enum class AuthMode(@param:StringRes val labelRes: Int) {
    Password(R.string.login_auth_password),
    ApiKey(R.string.login_auth_api_key),
}

/** Login page form and progress state. */
data class LoginUiState(
    val serverUrl: String = "",
    /** Optional custom display name for the saved instance (blank = fall back to the server host). */
    val instanceName: String = "",
    val authMode: AuthMode = AuthMode.Password,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** User filled in after a successful probe, used for the welcome text. */
    val connectedUser: UserDto? = null,
) {
    val canSubmit: Boolean
        get() = !loading && serverUrl.isNotBlank() && when (authMode) {
            AuthMode.Password -> username.isNotBlank() && password.isNotBlank()
            AuthMode.ApiKey -> apiKey.isNotBlank()
        }
}

/** Login: probe credentials with a temporary ApiClients and persist the config on success. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateServerUrl(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
    fun updateInstanceName(value: String) = _uiState.update { it.copy(instanceName = value, error = null) }
    fun updateUsername(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKey = value, error = null) }

    fun updateAuthMode(mode: AuthMode) = _uiState.update { it.copy(authMode = mode, error = null) }

    /** Probe login; on success persist the config (AppRoot gating enters the main UI automatically). */
    fun login() {
        val state = _uiState.value
        if (!state.canSubmit) return
        val config = ServerConfig(
            baseUrl = state.serverUrl.trim(),
            username = state.username.trim(),
            password = state.password,
            apiKey = state.apiKey.trim(),
        )
        if (!config.isConfigured) return
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val user = ApiClients(config).currentUser.getCurrentUser().awaitResponse()
                val finalConfig = config.copy(isAdmin = user.roles.contains("ADMIN"))
                // The admin flag is saved with the session (capability gates such as komf read ServerConfig.isAdmin)
                configStore.save(finalConfig)
                // Upsert into the instance list (mirrors iOS upsertInstance: repeat logins dedupe and refresh);
                // the instance name entered on this form is persisted with the row.
                configStore.upsertInstance(finalConfig, state.instanceName)
                _uiState.update { it.copy(loading = false, connectedUser = user) }
            } catch (e: HttpException) {
                val message = if (e.code() == 401 || e.code() == 403) {
                    context.getString(R.string.login_error_invalid_credentials)
                } else {
                    context.getString(R.string.login_error_unreachable)
                }
                _uiState.update { it.copy(loading = false, error = message) }
            } catch (e: IOException) {
                _uiState.update { it.copy(loading = false, error = context.getString(R.string.login_error_unreachable)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = context.getString(R.string.login_error_unreachable)) }
            }
        }
    }
}
