package com.vedito.app.feature.editor

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import com.vedito.app.R
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.databinding.ActivityEditorBinding
import com.vedito.app.feature.editor.player.PreviewPlayer

class EditorActivity : ComponentActivity(), PreviewPlayer.Listener {
    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: ProjectRepository
    private lateinit var previewPlayer: PreviewPlayer
    private var durationMs: Int = 0
    private var userScrubbing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ProjectRepository(this)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val project = projectId?.let(repository::find)
        if (project == null) {
            finish()
            return
        }

        binding.projectTitle.text = project.title
        binding.backButton.setOnClickListener { finish() }
        binding.playPauseButton.setOnClickListener { previewPlayer.toggle() }

        binding.timeline.onScrubbed = { position ->
            userScrubbing = true
            binding.currentTime.text = formatTime(position)
        }
        binding.timeline.onSeekFinished = { position ->
            previewPlayer.seekTo(position)
            userScrubbing = false
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        previewPlayer.load(Uri.parse(project.sourceUri))

        repository.save(project.copy(updatedAt = System.currentTimeMillis()))
    }

    override fun onPause() {
        super.onPause()
        if (::previewPlayer.isInitialized) previewPlayer.pause()
    }

    override fun onDestroy() {
        if (::previewPlayer.isInitialized) previewPlayer.release()
        super.onDestroy()
    }

    override fun onReady(durationMs: Int) {
        this.durationMs = durationMs
        binding.timeline.durationMs = durationMs
        binding.totalTime.text = formatTime(durationMs)
        binding.playerError.visibility = View.GONE
    }

    override fun onProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean) {
        if (!userScrubbing) {
            binding.timeline.positionMs = positionMs
            binding.currentTime.text = formatTime(positionMs)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = if (isPlaying) "Pause" else "Play"
    }

    override fun onError(message: String) {
        binding.playerError.text = message
        binding.playerError.visibility = View.VISIBLE
        binding.playPauseButton.isEnabled = false
    }

    private fun formatTime(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1_000
        val tenths = (safe % 1_000) / 100
        return "%02d:%02d.%d".format(minutes, seconds, tenths)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "vedito.project_id"
    }
}
