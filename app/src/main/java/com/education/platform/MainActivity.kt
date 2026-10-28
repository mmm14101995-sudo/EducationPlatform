package com.education.pdfboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingGrade: String = "sec3"
    private var pendingKind: String = "books"

    private fun gradeDir(grade: String): File {
        val safeGrade = grade.lowercase().takeIf { it in setOf("sec2", "sec3", "y3", "y4") } ?: "sec3"
        return File(pdfDir(), safeGrade.uppercase()).also { it.mkdirs() }
    }

    private fun kindDir(grade: String, kind: String): File {
        val folder = when (kind.lowercase()) {
            "reviews" -> "reviews"
            "exams" -> "exams"
            else -> ""
        }
        return if (folder.isEmpty()) gradeDir(grade) else File(gradeDir(grade), folder).also { it.mkdirs() }
    }

    private fun uniqueDestination(dir: File, originalName: String): File {
        val cleaned = safeName(originalName).ifBlank { "book.pdf" }
        val dot = cleaned.lastIndexOf('.')
        val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ".pdf"
        var dest = File(dir, if (ext.equals(".pdf", true)) cleaned else "$cleaned.pdf")
        var n = 2
        while (dest.exists()) {
            dest = File(dir, "$stem ($n)$ext")
            n++
        }
        return dest
    }

    private fun stateFile(): File = File(filesDir, "state.json")
    private fun mediaRoot(): File = File(filesDir, "videos").also { it.mkdirs() }

    companion object {
        private const val REQ_PDF = 42
        private const val REQ_BOOKS = 43
        private const val HOST = "https://appassets.androidplatform.net"
    }

    private fun pdfDir(): File = File(filesDir, "pdfs").also { it.mkdirs() }
    private fun annDir(): File = File(filesDir, "annotations").also { it.mkdirs() }

    private fun safeName(n: String): String = n.replace(Regex("[^\\p{L}\\p{N}._ \\-]"), "_")

    private fun annFile(name: String): File = File(annDir(), safeName(name) + ".json")

    private fun safePdf(name: String): File? {
        val f = File(pdfDir(), safeName(name))
        return if (f.isFile && f.parentFile == pdfDir()) f else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/pdfs/", WebViewAssetLoader.InternalStoragePathHandler(this, pdfDir()))
            .build()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(false)
        webView.addJavascriptInterface(Bridge(), "Native")\n        // اسم قديم للتوافق مع أي صفحة محلية أقدم\n        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        setContentView(webView)
        webView.loadUrl("$HOST/assets/index.html")
    }

    inner class Bridge {
        // واجهة المكتبة التي يستدعيها index.html
        @JavascriptInterface
        fun listBooks(): String {
            val out = JSONArray()
            val grades = listOf("sec2", "sec3", "y3", "y4")
            for (grade in grades) {
                val base = File(pdfDir(), grade.uppercase())
                if (!base.isDirectory) continue
                base.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.forEach { file ->
                    val relative = file.relativeTo(pdfDir()).invariantSeparatorsPath
                    val parts = relative.split("/")
                    val kind = when {
                        parts.any { it.equals("exams", true) || it.equals("exam", true) || it == "اختبارات" || it == "اختبار" } -> "exams"
                        parts.any { it.equals("reviews", true) || it.equals("review", true) || it == "ملازم" || it == "مراجعات" } -> "reviews"
                        else -> "books"
                    }
                    val encodedPath = relative.split("/").joinToString("/") { Uri.encode(it) }
                    out.put(JSONObject().apply {
                        put("id", relative)
                        put("title", file.nameWithoutExtension)
                        put("url", "$HOST/pdfs/$encodedPath")
                        put("grade", grade)
                        put("kind", kind)
                    })
                }
            }
            // الملفات القديمة التي كانت تحفظ مباشرة داخل pdfs/ لا تضيع؛ تظهر تحت ثالثة ثانوي.
            pdfDir().listFiles { f -> f.isFile && f.extension.equals("pdf", true) }?.forEach { file ->
                val relative = file.name
                val encoded = Uri.encode(relative)
                out.put(JSONObject().apply {
                    put("id", "SEC3/$relative")
                    put("title", file.nameWithoutExtension)
                    put("url", "$HOST/pdfs/$encoded")
                    put("grade", "sec3")
                    put("kind", "books")
                })
            }
            return out.toString()
        }

        @JavascriptInterface
        fun importBook(grade: String, kind: String) {
            val validGrade = grade.lowercase().takeIf { it in setOf("sec2", "sec3", "y3", "y4") } ?: "sec3"
            val validKind = kind.lowercase().takeIf { it in setOf("books", "reviews", "exams") } ?: "books"
            runOnUiThread {
                pendingGrade = validGrade
                pendingKind = validKind
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(i, REQ_BOOKS)
            }
        }

        @JavascriptInterface
        fun loadState(): String {
            return try {
                val f = stateFile()
                if (f.isFile) f.readText(Charsets.UTF_8) else "{}"
            } catch (_: Exception) { "{}" }
        }

        @JavascriptInterface
        fun saveState(json: String): Boolean {
            return try {
                stateFile().writeText(json, Charsets.UTF_8)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse(url)
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun openAi(url: String) = openExternal(url)

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                if (enabled) applyImmersive() else {
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
        }

        @JavascriptInterface
        fun listPdfs(): String {
            val names = pdfDir().listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }
                ?.map { it.name }?.sorted() ?: emptyList()
            return JSONArray(names).toString()
        }

        @JavascriptInterface
        fun loadAnnotations(name: String): String {
            val f = annFile(name)
            return if (f.isFile) f.readText() else ""
        }

        @JavascriptInterface
        fun saveAnnotations(name: String, json: String): Boolean {
            return try {
                annFile(name).writeText(json)
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun deletePdf(name: String): Boolean {
            val f = safePdf(name) ?: return false
            annFile(name).delete()
            return f.delete()
        }

        @JavascriptInterface
        fun listNotebooks(): String {
            val ids = annDir().listFiles { f -> f.isFile && f.name.startsWith("nb_") && f.name.endsWith(".json") }
                ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
            return JSONArray(ids).toString()
        }

        @JavascriptInterface
        fun deleteNotebook(id: String): Boolean {
            if (!id.startsWith("nb_")) return false
            return annFile(id).delete()
        }

        @JavascriptInterface
        fun pickPdf() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                }
                startActivityForResult(i, REQ_PDF)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQ_PDF || requestCode == REQ_BOOKS) && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            data?.data?.let { if (uris.isEmpty()) uris.add(it) }
            if (uris.isEmpty()) return

            try {
                for (uri in uris) {
                    val name = displayName(uri)
                    val target = if (requestCode == REQ_BOOKS) {
                        uniqueDestination(kindDir(pendingGrade, pendingKind), name)
                    } else {
                        uniqueDestination(pdfDir(), name)
                    }
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                webView.post {
                    webView.evaluateJavascript(
                        if (requestCode == REQ_BOOKS)
                            "if (typeof loadBooks === 'function') loadBooks();"
                        else "if (window.onPdfAdded) onPdfAdded();",
                        null
                    )
                }
            } catch (e: Exception) {
                webView.post {
                    webView.evaluateJavascript(
                        if (requestCode == REQ_BOOKS)
                            "if (typeof toast === 'function') toast('تعذر استيراد ملفات PDF');"
                        else "if (window.onPdfError) onPdfError();",
                        null
                    )
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "file_" + System.currentTimeMillis() + ".pdf"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx) ?: name
        }
        if (!name.endsWith(".pdf", true)) name += ".pdf"
        return name
    }

    // زرار الرجوع: الصفحة نفسها بتقرر (تسأل عن الحفظ لو فيه كتابة جديدة)، ولو مش متعاملة معاه نقفل التطبيق
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        webView.evaluateJavascript("(typeof handleBack === 'function' && handleBack()) ? 'true' : 'false'") { result ->
            if (result != "\"true\"") finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }
}
