package com.kmworks.kmreader.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kmworks.kmreader.core.api.model.UserDto
import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.ServerConfig
import com.kmworks.kmreader.core.network.ServerConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Inject
import kotlin.coroutines.resumeWithException

/** Retrofit Call → suspend（项目无 kotlinx-coroutines-retrofit，本地桥接）。 */
private suspend fun <T> Call<T>.awaitResponse(): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            val body = response.body()
            if (response.isSuccessful && body != null) {
                cont.resume(body) { }
            } else {
                cont.resumeWithException(HttpException(response))
            }
        }

        override fun onFailure(call: Call<T>, t: Throwable) {
            cont.resumeWithException(t)
        }
    })
}

/** 认证方式。 */
enum class AuthMode(val label: String) {
    Password("账号密码"),
    ApiKey("API Key"),
}

/** 登录页表单与进度状态。 */
data class LoginUiState(
    val serverUrl: String = "",
    val authMode: AuthMode = AuthMode.Password,
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** 探测成功后回填的用户，用于欢迎语。 */
    val connectedUser: UserDto? = null,
) {
    val canSubmit: Boolean
        get() = !loading && serverUrl.isNotBlank() && when (authMode) {
            AuthMode.Password -> username.isNotBlank() && password.isNotBlank()
            AuthMode.ApiKey -> apiKey.isNotBlank()
        }
}

/** 登录：临时 ApiClients 探测凭据，成功后持久化配置。 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateServerUrl(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
    fun updateUsername(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKey = value, error = null) }

    fun updateAuthMode(mode: AuthMode) = _uiState.update { it.copy(authMode = mode, error = null) }

    /** 探测登录；成功保存配置（AppRoot 门控自动进入主界面）。 */
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
                configStore.save(config)
                _uiState.update { it.copy(loading = false, connectedUser = user) }
            } catch (e: HttpException) {
                val message = if (e.code() == 401 || e.code() == 403) "凭据无效" else "无法连接服务器"
                _uiState.update { it.copy(loading = false, error = message) }
            } catch (e: IOException) {
                _uiState.update { it.copy(loading = false, error = "无法连接服务器") }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = "无法连接服务器") }
            }
        }
    }
}
