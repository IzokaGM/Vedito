package com.vedito.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.vedito.app.ui.Ui
import com.vedito.app.ui.dp
import com.vedito.app.ui.safeClick
import kotlin.math.max

class EditorActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var playButton: TextView
    private lateinit var timeText: TextView
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var durationMs = 0

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::videoView.isInitialized && !userSeeking && durationMs > 0) {
                val current = videoView.currentPosition.coerceAtLeast(0)
                seekBar.progress = current.coerceAtMost(durationMs)
                timeText.text = "${formatTime(current)}  /  ${formatTime(durationMs)}"
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Color.BLACK

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "Video"
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }

        setContentView(buildEditor(Uri.parse(uriString), videoName))
        handler.post(progressUpdater)
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
            updatePlayState()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        if (::videoView.isInitialized) videoView.stopPlayback()
        super.onDestroy()
    }

    private fun buildEditor(uri: Uri, videoName: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        header.addView(Ui.text(this, "‹", 32f, Ui.TEXT, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(16), 0)
            safeClick { finish() }
        })

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleStack.addView(Ui.text(this, videoName, 15f, Ui.TEXT, true).apply { maxLines = 1 })
        titleStack.addView(Ui.text(this, "Native preview", 12f, Ui.MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header)

        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        videoView = VideoView(this).apply {
            setVideoURI(uri)
            setOnPreparedListener { mediaPlayer ->
                durationMs = max(0, mediaPlayer.duration)
                seekBar.max = max(1, durationMs)
                timeText.text = "00:00  /  ${formatTime(durationMs)}"
                mediaPlayer.setOnCompletionListener {
                    updatePlayState()
                    seekBar.progress = durationMs
                    timeText.text = "${formatTime(durationMs)}  /  ${formatTime(durationMs)}"
                }
            }
            setOnErrorListener { _, _, _ ->
                timeText.text = "Unable to preview this codec"
                true
            }
        }

        previewFrame.addView(videoView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }

        val timelineLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timelineLabelRow.addView(Ui.text(this, "TIMELINE", 11f, Ui.MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeText = Ui.text(this, "00:00  /  00:00", 12f, Ui.MUTED, true)
        timelineLabelRow.addView(timeText)
        controls.addView(timelineLabelRow)

        seekBar = SeekBar(this).apply {
            max = 1
            progress = 0
            setPadding(0, dp(7), 0, dp(7))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = "${formatTime(progress)}  /  ${formatTime(durationMs)}"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val target = seekBar?.progress ?: 0
                    videoView.seekTo(target)
                    userSeeking = false
                }
            })
        }
        controls.addView(seekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        playButton = Ui.labelButton(this, "▶  Play", Ui.ACCENT, Color.BLACK).apply {
            safeClick { togglePlayback() }
        }
        controls.addView(playButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { topMargin = dp(10) })

        controls.addView(Ui.text(this, "This migration intentionally ships only controls that already work. Editing tools come next on the native engine.", 12f, Ui.MUTED).apply {
            setPadding(dp(2), dp(12), dp(2), 0)
        })

        root.addView(controls)
        return root
    }

    private fun togglePlayback() {
        if (!::videoView.isInitialized) return
        if (videoView.isPlaying) {
            videoView.pause()
        } else {
            if (durationMs > 0 && videoView.currentPosition >= durationMs - 250) {
                videoView.seekTo(0)
            }
            videoView.start()
        }
        updatePlayState()
    }

    private fun updatePlayState() {
        if (!::playButton.isInitialized) return
        playButton.text = if (videoView.isPlaying) "Ⅱ  Pause" else "▶  Play"
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = max(0, ms) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
    }
}
