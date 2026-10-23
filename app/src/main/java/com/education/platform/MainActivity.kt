package com.education.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT = 1001
        private const val REQ_FILE = 1002
        private const val APP_URL = "https://appassets.androidplatform.net/app/index.html"
        private const val UI_URL = "https://raw.githubusercontent.com/mmm14101995-sudo/EducationPlatform/main/index.html"
        private val REVIEW_FOLDERS = setOf("reviews", "review", "ملازم", "مراجعات")
        private val EXAM_FOLDERS = setOf("exams", "exam", "اختبارات", "اختبار")
        private const val REQ_VIDEO = 1003
        private const val REQ_BG = 1004
        private val COURSE_RE = Regex("^[a-z0-9]{1,40}$")
        private val VIDEO_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp")
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var booksDir: File
    private lateinit var exportDir: File
    private lateinit var stateFile: File
    private val uiDir: File by lazy { File(filesDir, "ui") }
    private val mediaDir: File by lazy { File(filesDir, "videos") }
    private var pendingCourse = ""

    private var pendingGrade = "sec3"
    private var pendingKind = "books"
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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

        val assetsHandler = WebViewAssetLoader.AssetsPathHandler(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", assetsHandler)
            .addPathHandler("/app/", WebViewAssetLoader.PathHandler { path -> serveApp(path, assetsHandler) })
            .addPathHandler("/books/", WebViewAssetLoader.PathHandler { path -> serveBook(path) })
            .build()

        // تحديث الواجهة في الخلفية: التحديث يطبّق عند فتح التطبيق المرة الجاية
        Thread { updateUi() }.start()

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
                // الفيديوهات تتخدم مباشرة مع دعم Range عشان التقديم والترجيع
                if (uri.encodedPath?.startsWith("/videos/") == true) {
                    val range = request.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
                    return serveVideo(uri.encodedPath ?: "", range)
                }
                return assetLoader.shouldInterceptRequest(uri)
            }
        }

        // اختيار الصور من الموبايل (مثلاً صورة الخلفية)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = try { fileChooserParams?.createIntent() } catch (e: Exception) { null }
                if (intent == null) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    return false
                }
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    return false
                }
                return true
            }
        }

        webView.loadUrl(APP_URL)
    }

    // زر الرجوع: يغلق أول نافذة مفتوحة في الصفحة، وإن لم توجد يخرج من التطبيق
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(typeof window.androidBack === 'function') && window.androidBack()"
        ) { result ->
            if (result != "true") finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_BG) {
            val imgUri = if (resultCode == RESULT_OK) data?.data else null
            if (imgUri != null) sendBackground(imgUri)
            return
        }

        if (requestCode == REQ_VIDEO) {
            val videoUri = if (resultCode == RESULT_OK) data?.data else null
            if (videoUri != null) importVideo(videoUri, pendingCourse)
            return
        }

        if (requestCode == REQ_FILE) {
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(results)
            fileCallback = null
            return
        }

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

    // ---------- خلفية التطبيق من صور الموبايل ----------
    private fun sendBackground(uri: Uri) {
        Thread {
            try {
                // أول مرة نقرأ الأبعاد فقط، وبعدين نفك الصورة مصغّرة عشان ما تتقلش الذاكرة
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxSide / sample > 1600) sample *= 2
                val decode = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
                    ?: return@Thread
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                webView.post {
                    webView.evaluateJavascript("onBackgroundPicked('data:image/jpeg;base64,$b64')", null)
                }
            } catch (e: Exception) {
                toast("تعذر تحميل الصورة")
            }
        }.start()
    }

    // ---------- فيديوهات الكورسات ----------
    private fun courseDir(cid: String): File? =
        if (COURSE_RE.matches(cid)) File(mediaDir, cid) else null

    private fun hasVideoExt(name: String): Boolean {
        val lower = name.lowercase()
        return VIDEO_EXT.any { lower.endsWith(it) }
    }

    private fun importVideo(uri: Uri, cid: String) {
        try {
            val dir = courseDir(cid) ?: return
            dir.mkdirs()
            val name = cleanVideoName(displayName(uri))
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var target = File(dir, name)
            var i = 2
            while (target.exists()) {
                target = File(dir, "$base ($i).$ext")
                i++
            }
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("cannot open input")
            input.use { src ->
                FileOutputStream(target).use { dst -> src.copyTo(dst) }
            }
            toast("تمت إضافة الفيديو")
            webView.post { webView.evaluateJavascript("onMediaChanged('$cid')", null) }
        } catch (e: Exception) {
            toast("تعذر إضافة الفيديو")
        }
    }

    private fun cleanVideoName(raw: String): String {
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video.mp4" }
        return if (hasVideoExt(safe)) safe else "$safe.mp4"
    }

    private fun serveVideo(encodedPath: String, rangeHeader: String?): WebResourceResponse? {
        return try {
            val rel = encodedPath.removePrefix("/videos/")
            val decoded = URLDecoder.decode(rel.replace("+", "%2B"), "UTF-8")
            val slash = decoded.indexOf('/')
            if (slash <= 0) return null
            val cid = decoded.substring(0, slash)
            val name = decoded.substring(slash + 1)
            if (name.contains('/') || name.contains('\\')) return null
            val dir = courseDir(cid) ?: return null
            val file = File(dir, name).canonicalFile
            if (!file.path.startsWith(dir.canonicalPath + File.separator) || !file.isFile) return null

            val size = file.length()
            var start = 0L
            var end = size - 1
            var status = 200
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').split('-')
                start = if (spec[0].isNotEmpty()) {
                    spec[0].toLong()
                } else {
                    maxOf(0L, size - (spec.getOrNull(1)?.toLongOrNull() ?: 0L))
                }
                if (spec.size > 1 && spec[1].isNotEmpty()) end = spec[1].toLong()
                end = minOf(end, size - 1)
                if (start > end) return null
                status = 206
            }
            val length = end - start + 1
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            headers["Content-Length"] = length.toString()
            if (status == 206) headers["Content-Range"] = "bytes $start-$end/$size"
            val mime = if (name.lowercase().endsWith(".webm")) "video/webm" else "video/mp4"
            WebResourceResponse(
                mime, null, status,
                if (status == 206) "Partial Content" else "OK",
                headers,
                RangeInputStream(file, start, length)
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------- الواجهة: النسخة المحدّثة إن وُجدت، وإلا المدمجة ----------
    private fun serveApp(path: String, fallback: WebViewAssetLoader.PathHandler): WebResourceResponse? {
        val cached = File(uiDir, "index.html")
        if (path == "index.html" && cached.isFile) {
            return try {
                WebResourceResponse("text/html", "UTF-8", FileInputStream(cached))
            } catch (e: Exception) {
                fallback.handle(path)
            }
        }
        return fallback.handle(path)
    }

    private fun updateUi() {
        try {
            val conn = URL(UI_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val text = String(bytes, Charsets.UTF_8)
            if (bytes.size > 10000 && text.contains("</html>")) {
                uiDir.mkdirs()
                val tmp = File(uiDir, "index.html.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(uiDir, "index.html"))
            }
        } catch (e: Exception) {
            // لا يوجد إنترنت: التطبيق يكمل بالنسخة المحفوظة أو المدمجة
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
        val sub = when (kind) {
            "reviews" -> "reviews"
            "exams" -> "exams"
            else -> ""
        }
        val dir = if (sub.isEmpty()) File(booksDir, folder) else File(booksDir, "$folder/$sub")
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

    // ---------- ملء الشاشة (إخفاء شريط النظام) ----------
    @Suppress("DEPRECATION")
    private fun applyFullscreen(on: Boolean) {
        val decor = window.decorView
        if (on) {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ---------- نافذة الذكاء الاصطناعي داخل التطبيق ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun showAiWindow(url: String) {
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val closeBtn = Button(this)
        closeBtn.text = "✕ إغلاق"
        closeBtn.setOnClickListener { dialog.dismiss() }

        column.addView(
            closeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        dialog.setContentView(column)
        dialog.setOnDismissListener {
            web.stopLoading()
            web.destroy()
        }
        dialog.show()
        web.loadUrl(url)
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
                        val folders = parts.drop(1).dropLast(1).map { it.lowercase() }
                        val kind = when {
                            folders.any { it in EXAM_FOLDERS } -> "exams"
                            folders.any { it in REVIEW_FOLDERS } -> "reviews"
                            else -> "books"
                        }
                        result.put(JSONObject().apply {
                            put("id", rel)
                            put("title", file.nameWithoutExtension)
                            put("url", "/books/" + Uri.encode(rel, "/"))
                            put("grade", grade)
                            put("kind", kind)
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
        fun openAi(url: String) {
            if (!url.startsWith("https://")) return
            runOnUiThread { showAiWindow(url) }
        }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) {
            runOnUiThread { applyFullscreen(on) }
        }

        @JavascriptInterface
        fun pickBackground() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, REQ_BG)
            }
        }

        @JavascriptInterface
        fun listMedia(cid: String): String {
            val arr = JSONArray()
            val dir = courseDir(cid)
            if (dir != null && dir.isDirectory) {
                dir.listFiles()
                    ?.filter { it.isFile && hasVideoExt(it.name) }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { f ->
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("url", "/videos/" + Uri.encode(cid) + "/" + Uri.encode(f.name))
                        })
                    }
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun importMedia(cid: String) {
            if (courseDir(cid) == null) return
            pendingCourse = cid
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }
                startActivityForResult(intent, REQ_VIDEO)
            }
        }

        @JavascriptInterface
        fun deleteMedia(cid: String, name: String): Boolean {
            val dir = courseDir(cid) ?: return false
            val f = File(dir, name)
            return f.parentFile == dir && f.isFile && f.delete()
        }

        @JavascriptInterface
        fun deleteCourse(cid: String): Boolean {
            val dir = courseDir(cid) ?: return false
            return dir.deleteRecursively()
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

// قراءة جزء من ملف الفيديو فقط (لدعم Range)
private class RangeInputStream(file: File, start: Long, length: Long) : java.io.InputStream() {
    private val channel = java.io.FileInputStream(file).channel
    private var remaining: Long = length

    init {
        channel.position(start)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val want = minOf(len.toLong(), remaining).toInt()
        val n = channel.read(java.nio.ByteBuffer.wrap(b, off, want))
        if (n <= 0) return -1
        remaining -= n.toLong()
        return n
    }

    override fun close() {
        channel.close()
    }
}
