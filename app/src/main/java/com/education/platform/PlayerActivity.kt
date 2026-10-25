package com.education.platform

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

// مشغّل الفيديو الأصلي للأندرويد: بيشغّل أغلب الصيغ (MKV وWebM وMP4 وغيرها)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f)
    private var speedIndex = 0
    private lateinit var speedBtn: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        if (!file.isFile) {
            finish()
            return
        }

        val exo = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = exo

        val pv = PlayerView(this)
        pv.player = exo
        pv.setControllerShowTimeoutMs(10_000)
        pv.setShowNextButton(false)
        pv.setShowPreviousButton(false)
        pv.setShowRewindButton(true)
        pv.setShowFastForwardButton(true)

        speedBtn = TextView(this).apply {
            text = "1x"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(36, 18, 36, 18)
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { cycleSpeed() }
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(
            pv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        lp.topMargin = 40
        lp.marginEnd = 40
        root.addView(speedBtn, lp)
        setContentView(root)

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        player?.playbackParameters = PlaybackParameters(speeds[speedIndex])
        speedBtn.text = speeds[speedIndex].toString().removeSuffix(".0") + "x"
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
