package com.education.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder

class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT = 1001
        private const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
        private val REVIEW_FOLDERS = setOf("reviews", "review", "ملازم", "مراجعات")
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var booksDir: File
    private lateinit var exportDir: File
    private lateinit var stateFile: File

    private var pendingGrade = "sec3"
    private var pendingKind = "books"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // الكتب تُحفظ داخل التطبيق: books/SEC2, books/SEC3 وملازمها في reviews
        booksDir = File(filesDir, "books")
        File(booksDir, "SEC2/reviews").mkdirs()
        File(booksDir, "SEC3/reviews").mkdirs()
        exportDir = File(getExternalFilesDir(null) ?: filesDir, "exports")
        exportDir.mkdirs()
        stateFile = File(filesDir, "state.json")

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/books/", WebViewAssetLoader.PathHandler { path -> serveBook(path) })
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(NativeBridge(), "Native")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }
        }

        webView.loadUrl(APP_URL)
    }

    // زر الرجوع: يغلق القارئ أولاً، وإن لم يكن مفتوحاً يخرج من التطبيق
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(typeof window.androidBack === 'function') && window.androidBack()"
        ) { result ->
            if (result != "true") finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            val name = cleanName(displayName(uri))
            val dir = targetDir(pendingGrade, pendingKind)
            val target = uniqueFile(dir, name)
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("cannot open input")
            input.use { src ->
                FileOutputStream(target).use { dst -> src.copyTo(dst) }
            }
            toast("تمت إضافة: ${target.nameWithoutExtension}")
            webView.evaluateJavascript("loadBooks()", null)
        } catch (e: Exception) {
            toast("تعذر استيراد الملف")
        }
    }

    // ---------- الكتب من التخزين الداخلي ----------
    private fun serveBook(rawPath: String): WebResourceResponse? {
        return try {
            val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            val file = File(booksDir, decoded).canonicalFile
            if (!file.path.startsWith(booksDir.canonicalPath + File.separator) || !file.isFile) {
                null
            } else {
                WebResourceResponse("application/pdf", null, FileInputStream(file))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun targetDir(grade: String, kind: String): File {
        val folder = if (grade == "sec2") "SEC2" else "SEC3"
        val dir = if (kind == "reviews") File(booksDir, "$folder/reviews") else File(booksDir, folder)
        dir.mkdirs()
        return dir
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.removeSuffix(".pdf")
        var candidate = File(dir, name)
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i).pdf")
            i++
        }
        return candidate
    }

    private fun cleanName(raw: String): String {
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "book" }
        return if (safe.lowercase().endsWith(".pdf")) safe else "$safe.pdf"
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "book"
        }
        return "book"
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- الجسر: يستدعيه JavaScript باسم Native ----------
    inner class NativeBridge {

        @JavascriptInterface
        fun listBooks(): String {
            val result = JSONArray()
            val gradeDirs = booksDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (gradeDir in gradeDirs) {
                val grade = gradeDir.name.lowercase()
                if (grade != "sec2" && grade != "sec3") continue

                gradeDir.walkTopDown()
                    .filter { it.isFile && it.name.lowercase().endsWith(".pdf") }
                    .sortedBy { it.name.lowercase() }
                    .forEach { file ->
                        val rel = file.relativeTo(booksDir).invariantSeparatorsPath
                        val parts = rel.split("/")
                        val isReview = parts.drop(1).dropLast(1).any { it.lowercase() in REVIEW_FOLDERS }
                        result.put(JSONObject().apply {
                            put("id", rel)
                            put("title", file.nameWithoutExtension)
                            put("url", "/books/" + Uri.encode(rel, "/"))
                            put("grade", grade)
                            put("kind", if (isReview) "reviews" else "books")
                        })
                    }
            }
            return result.toString()
        }

        @JavascriptInterface
        fun loadState(): String =
            if (stateFile.exists()) stateFile.readText(Charsets.UTF_8) else "{}"

        @JavascriptInterface
        fun saveState(json: String) {
            val tmp = File(filesDir, "state.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(stateFile)) stateFile.writeText(json, Charsets.UTF_8)
        }

        @JavascriptInterface
        fun savePdf(name: String, base64Data: String): String {
            exportDir.mkdirs()
            val out = File(exportDir, cleanName(name))
            out.writeBytes(Base64.decode(base64Data, Base64.DEFAULT))
            return out.absolutePath
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    toast("تعذر فتح الرابط")
                }
            }
        }

        @JavascriptInterface
        fun importBook(grade: String, kind: String) {
            pendingGrade = grade
            pendingKind = kind
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                }
                startActivityForResult(intent, REQ_IMPORT)
            }
        }
    }
}
