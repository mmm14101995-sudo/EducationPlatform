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
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    companion object {
        private const val REQ_PDF = 42
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
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        setContentView(webView)
        webView.loadUrl("$HOST/assets/index.html")
    }

    inner class Bridge {
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
        if (requestCode == REQ_PDF && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val target = File(pdfDir(), safeName(displayName(uri)))
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                webView.post { webView.evaluateJavascript("if (window.onPdfAdded) onPdfAdded();", null) }
            } catch (e: Exception) {
                webView.post { webView.evaluateJavascript("if (window.onPdfError) onPdfError();", null) }
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
