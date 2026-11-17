package com.kmworks.kmreader.core.fonts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

/** Server font download store (mirrors Komga FontsApi + the iOS local-font injection approach). */
@Singleton
class FontRepository @Inject constructor(
    private val sessionManager: SessionManager,
    @ApplicationContext private val context: Context,
) {

    private val fontsDir: File get() = File(context.filesDir, "fonts")

    private fun api(): FontsApi? =
        sessionManager.clients.value?.let { LocalApiFactory.create(it, FontsApi::class.java) }

    /** Server-side font families; empty on failure (the UI gates on it). */
    suspend fun families(): List<String> =
        api()?.getFonts()?.awaitBody()?.sorted() ?: emptyList()

    /** Downloaded (an existing local css counts as complete). */
    fun downloadedFamilies(): Set<String> =
        fontsDir.listFiles()
            ?.filter { it.isDirectory && File(it, CSS_FILE).exists() }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    /**
     * Download a family's css and all font files to filesDir/fonts/{family}/;
     * url(...) entries inside the css are rewritten to the WebView fake-domain fonts route (existing files skipped).
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

    /** Local @font-face css (already rewritten to fake-domain URLs); null when not downloaded. */
    fun localCss(family: String): String? =
        File(fontsDir, "$family/$CSS_FILE").takeIf { it.exists() }?.readText()

    fun deleteFontFamily(family: String) {
        File(fontsDir, family).deleteRecursively()
    }

    /**
     * Import local font files (.ttf/.otf/.ttc picked via SAF):
     * writes filesDir/fonts/{family}/ and generates an @font-face family.css (fake-domain URLs),
     * then takes the same route as server-downloaded fonts (downloadedFamilies/localCss) consumed by the EPUB reader.
     * Returns the imported family name; unsupported extensions or read failures return null.
     */
    suspend fun importLocalFont(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val displayName = queryDisplayName(uri) ?: return@withContext null
            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension !in IMPORTABLE_EXTENSIONS) return@withContext null
            val family = sanitizeFamilyName(displayName.substringBeforeLast('.'))
                .ifBlank { return@withContext null }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val dir = File(fontsDir, family).apply { mkdirs() }
            // A same-named family is treated as an overwrite import: clear old files to avoid stale fonts
            dir.listFiles()?.forEach { it.delete() }
            val fileName = "$family.$extension"
            File(dir, fileName).writeBytes(bytes)
            val css = """
                @font-face {
                    font-family: "${family.replace("\"", "\\\"")}";
                    src: url("${EpubWeb.fontUrl(family, fileName)}");
                }
            """.trimIndent()
            File(dir, CSS_FILE).writeText(css)
            family
        } catch (_: Exception) {
            null
        }
    }

    /** SAF file name (OpenableColumns.DISPLAY_NAME). */
    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        }

    /** Family name sanitization: strips path/URL-dangerous characters, keeps CJK, Latin and spaces. */
    private fun sanitizeFamilyName(raw: String): String =
        raw.trim().replace(Regex("""[\\/:*?"<>|]+"""), "_").trim('.', ' ')

    /** Local file read for the fake-domain fonts route (called synchronously on the WebViewClient thread). */
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
        val IMPORTABLE_EXTENSIONS = setOf("ttf", "otf", "ttc")
    }
}
