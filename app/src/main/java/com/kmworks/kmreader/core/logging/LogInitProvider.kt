package com.kmworks.kmreader.core.logging

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.kmworks.kmreader.core.download.AutoDownloadUnreadTrigger
import com.kmworks.kmreader.core.sse.SseService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LogInitEntryPoint {
    fun sseService(): SseService
    fun appScope(): CoroutineScope
    fun autoDownloadUnreadTrigger(): AutoDownloadUnreadTrigger
}

/**
 * 启动期自初始化（manifest 注册，app 进程创建即跑）：
 * 安装日志文件输出，并把 SSE 事件/连接状态接入 AppLog。
 */
class LogInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        AppLog.install(context.applicationContext)
        runCatching {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                LogInitEntryPoint::class.java,
            )
            val sse = entryPoint.sseService()
            // 提前实例化自动下载未读触发器（内部在 appScope 常驻订阅 SSE 事件）
            entryPoint.autoDownloadUnreadTrigger()
            entryPoint.appScope().launch {
                sse.connectionState.collect { state ->
                    AppLog.i("SSE", "connection -> $state")
                }
            }
            entryPoint.appScope().launch {
                sse.events.collect { event ->
                    AppLog.d("SSE", "${event.name}: ${event.data.take(200)}")
                }
            }
        }.onFailure { AppLog.w("AppLog", "SSE log hook failed: ${it.message}") }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
