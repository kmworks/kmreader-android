package com.kmworks.kmreader.core.server

import com.kmworks.kmreader.core.network.ApiClients
import com.kmworks.kmreader.core.network.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 能力探测基座（对齐 iOS ServerProfileService）：有会话时探测一次 /api/v1/server/info，
 * 404/网络错误一律按 stock Komga 处理（features 空集，不报错）；会话消失时清空。
 */
@Singleton
class ServerProfileStore @Inject constructor(
    sessionManager: SessionManager,
    scope: CoroutineScope,
) {

    private val _profile = MutableStateFlow<ServerProfile?>(null)
    val profile: StateFlow<ServerProfile?> = _profile.asStateFlow()

    init {
        scope.launch {
            sessionManager.clients.collect { clients ->
                if (clients == null) {
                    _profile.value = null
                } else {
                    probe(clients)
                }
            }
        }
    }

    /** 无 profile（stock Komga 或未探测）→ 全部 false。 */
    fun hasFeature(feature: ServerFeature): Boolean =
        _profile.value?.features?.contains(feature) == true

    private suspend fun probe(clients: ApiClients) = withContext(Dispatchers.IO) {
        _profile.value = try {
            val response = clients.serverApi.getServerInfo().execute()
            if (response.isSuccessful) {
                val info = response.body()
                ServerProfile(
                    isKmrs = true,
                    features = info?.features.orEmpty()
                        .mapNotNull(ServerFeature::fromRaw)
                        .toSet(),
                    version = info?.version,
                )
            } else {
                stockProfile()
            }
        } catch (_: Exception) {
            // 网络错误同样按 stock 处理，不向 UI 报错
            stockProfile()
        }
    }

    private fun stockProfile() = ServerProfile(
        isKmrs = false,
        features = emptySet(),
        version = null,
    )
}
