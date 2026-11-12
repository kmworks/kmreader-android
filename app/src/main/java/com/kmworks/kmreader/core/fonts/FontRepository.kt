package com.kmworks.kmreader.core.fonts

import android.content.Context
import android.net.Uri
import com.kmworks.kmreader.core.api.FontsApi
import com.kmworks.kmreader.core.data.LocalApiFactory
import com.kmworks.kmreader.core.data.awaitBody
import com.kmworks.kmreader.core.epub.EpubResource
import com.kmworks.kmreader.core.epub.EpubWeb
import com.kmworks.kmreader.core.network.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Server font download store (mirrors Komga FontsApi + iOS 本地字体注入思路)。 */
@Singleton
class FontRepository @Inject constructor(
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
) {

    private val fontsDir: File get() = File(context.filesDir, "fonts")

    private fun api(): FontsApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, FontsApi::class.java) }

    /** 服务端字体家庭列表；失败返回空（界面门控）。 */
    suspend fun families(): List<String> =
        api()?.getFonts()?.awaitBody()?.sorted() ?: emptyList()

    /** 已下载（本地 css 存在视为完成）。 */
    fun downloadedFamilies(): Set<String> =
        fontsDir.listFiles()
            ?.filter { it.isDirectory && File(it, CSS_FILE).exists() }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    /**
     * 下载家庭的 css 与全部字体文件到 filesDir/fonts/{family}/；
     * css 内 url(...) 重写为 WebView 假域 fonts 路由（已存在的文件跳过）。
     */
    suspend fun downloadFontFamily(family: String): Boolean = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext false
        try {
            val css = api.getFontFamilyAsCss(family).awaitBody()?.string()
                ?: return@withContext false
            val dir = File(fontsDir, family).apply { mkdirs() }
            val urls = URL_PATTERN.findAll(css).map { it.groupValues[1].trim('\'', '"', ' ') }
                .distinct().toList()
            var rewritten = css
            urls.forEach { raw ->
                val fileName = raw.substringAfterLast('/')
                if (fileName.isBlank()) return@forEach
                val target = File(dir, fileName)
                if (!target.exists()) {
                    val body = api.getFontFile(family, fileName).awaitBody()
                        ?: return@withContext false
                    target.writeBytes(body.bytes())
                }
                val local = EpubWeb.fontUrl(family, fileName)
                rewritten = rewritten.replace(raw, local)
            }
            File(dir, CSS_FILE).writeText(rewritten)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 本地 @font-face css（已重写为假域 URL）；未下载返回 null。 */
    fun localCss(family: String): String? =
        File(fontsDir, "$family/$CSS_FILE").takeIf { it.exists() }?.readText()

    fun deleteFontFamily(family: String) {
        File(fontsDir, family).deleteRecursively()
    }

    /** 假域 fonts 路由的本地文件读取（WebViewClient 线程同步调用）。 */
    fun localFontResource(path: String): EpubResource? {
        if (!path.startsWith(EpubWeb.FONTS_PREFIX)) return null
        val relative = path.removePrefix(EpubWeb.FONTS_PREFIX)
        if (relative.contains("..")) return null
        val file = File(fontsDir, Uri.decode(relative))
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            EpubResource(
                data = file.readBytes(),
                mimeType = EpubWeb.mediaTypeFor(file.name),
                encoding = null,
            )
        }.getOrNull()
    }

    private companion object {
        const val CSS_FILE = "family.css"
        val URL_PATTERN = Regex("""url\(([^)]+)\)""")
    }
}
