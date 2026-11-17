package com.kmworks.kmreader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.R
import com.kmworks.kmreader.core.api.model.UserDto
import com.kmworks.kmreader.core.data.CoverUrlRegistry
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import com.kmworks.kmreader.core.network.ServerInstance
import com.kmworks.kmreader.core.network.awaitResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Server edit view model (mirrors iOS ServerEditView): display name, URL, and credentials
 * (username/password or API Key).
 * Changing only the display name saves directly; URL or credential changes must pass
 * connection validation first. When saving the currently active instance, the active config
 * is written back as well (SessionManager rebuilds the connection automatically).
 */
@HiltViewModel
class ServerEditViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val loaded: Boolean = false,
        val original: ServerInstance? = null,
        val name: String = "",
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        val apiKey: String = "",
        val verifying: Boolean = false,
        /** Snapshot of the last successfully validated config; any field change since requires re-validation. */
        val verifiedConfig: ServerConfig? = null,
        val verifiedUser: UserDto? = null,
        val saving: Boolean = false,
        val error: String? = null,
        val saved: Boolean = false,
    ) {
        val currentConfig: ServerConfig
            get() = ServerConfig(
                baseUrl = baseUrl.trim(),
                username = username.trim(),
                password = password,
                apiKey = apiKey.trim(),
            )

        /** Whether the URL or credentials differ from the original instance (display name changes do not count). */
        val credentialsChanged: Boolean
            get() = original?.let {
                baseUrl.trim() != it.baseUrl.trim() ||
                    username.trim() != it.username ||
                    password != it.password ||
                    apiKey.trim() != it.apiKey
            } ?: false

        val canVerify: Boolean
            get() = !verifying && !saving && currentConfig.isConfigured

        val canSave: Boolean
            get() = loaded && !verifying && !saving && currentConfig.isConfigured &&
                (!credentialsChanged || verifiedConfig == currentConfig)
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Binds the instance being edited (only bound once per instance). */
    fun start(instance: ServerInstance) {
        if (_state.value.original?.id == instance.id) return
        _state.value = UiState(
            loaded = true,
            original = instance,
            name = instance.name,
            baseUrl = instance.baseUrl,
            username = instance.username,
            password = instance.password,
            apiKey = instance.apiKey,
        )
    }

    fun updateName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun updateBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun updatePassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun updateApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null) }

    /** Validates the connection (mirrors iOS testConnection: on success records the config snapshot and user info). */
    fun verify() {
        val state = _state.value
        if (!state.canVerify) return
        val config = state.currentConfig
        _state.update { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            try {
                val user = ApiClients(config).currentUser.getCurrentUser().awaitResponse()
                _state.update {
                    it.copy(
                        verifying = false,
                        verifiedConfig = config,
                        verifiedUser = user,
                        error = null,
                    )
                }
            } catch (e: HttpException) {
                val message = if (e.code() == 401 || e.code() == 403) {
                    appContext.getString(R.string.server_error_invalid_credentials)
                } else {
                    appContext.getString(R.string.server_error_unreachable)
                }
                _state.update { it.copy(verifying = false, verifiedConfig = null, error = message) }
            } catch (e: IOException) {
                _state.update { it.copy(verifying = false, verifiedConfig = null, error = appContext.getString(R.string.server_error_unreachable)) }
            } catch (e: Exception) {
                _state.update { it.copy(verifying = false, verifiedConfig = null, error = appContext.getString(R.string.server_error_unreachable)) }
            }
        }
    }

    /** Saves: display-name-only changes are persisted directly; credential changes require the current config to be validated. */
    fun save() {
        val state = _state.value
        val original = state.original ?: return
        if (!state.canSave) return
        // When credentials were validated, refresh the admin flag from the server roles; otherwise keep the original value
        val isAdmin = if (state.credentialsChanged && state.verifiedUser != null) {
            state.verifiedUser.roles.contains("ADMIN")
        } else {
            original.isAdmin
        }
        val updated = original.copy(
            name = state.name.trim(),
            baseUrl = state.baseUrl.trim(),
            username = state.username.trim(),
            password = state.password,
            apiKey = state.apiKey.trim(),
            isAdmin = isAdmin,
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // If the edited instance is the active one (matched by original URL + username), write the active config back too
            val active = configStore.config.first()
            val wasActive = active != null &&
                active.normalizedBaseUrl == original.normalizedBaseUrl &&
                active.username == original.username
            configStore.updateInstance(updated)
            if (wasActive) {
                // The stale cover-id cache must not be reused after credential changes
                CoverUrlRegistry.clear()
                configStore.save(updated.toConfig())
            }
            _state.update { it.copy(saving = false, saved = true) }
        }
    }
}
