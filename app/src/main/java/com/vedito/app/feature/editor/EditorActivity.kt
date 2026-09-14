package com.vedito.app.feature.editor

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import com.vedito.app.R
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.Project
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.core.timeline.TimelineMath
import com.vedito.app.databinding.ActivityEditorBinding
import com.vedito.app.feature.editor.player.PreviewPlayer
import com.vedito.app.feature.editor.timeline.ThumbnailExtractor
import com.vedito.app.ui.applySystemBarInsets
import com.vedito.app.ui.configureVeditoSystemBars
import java.util.UUID

class EditorActivity : ComponentActivity(), PreviewPlayer.Listener {
    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: ProjectRepository
    private lateinit var previewPlayer: PreviewPlayer
    private lateinit var thumbnailExtractor: ThumbnailExtractor
    private lateinit var project: Project

    private var clips: List<Clip> = emptyList()
    private var selectedClipId: String? = null
    private var timelinePositionMs: Int = 0
    private var sourceDurationMs: Int = 0
    private var playbackClipId: String? = null
    private var userScrubbing = false
    private var lastScrubSeekAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureVeditoSystemBars()
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        repository = ProjectRepository(this)
        thumbnailExtractor = ThumbnailExtractor(this)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val loaded = projectId?.let(repository::find)
        if (loaded == null) {
            finish()
            return
        }
        project = loaded
        clips = project.clips
        selectedClipId = project.selectedClipId ?: clips.firstOrNull()?.id
        timelinePositionMs = project.playheadMs.coerceAtLeast(0)

        binding.projectTitle.text = project.title
        binding.backButton.setOnClickListener { finish() }
        binding.playPauseButton.setOnClickListener {
            if (previewPlayer.isPlaying()) {
                previewPlayer.pause()
            } else {
                startPlaybackFromTimeline()
            }
        }
        binding.splitButton.setOnClickListener { splitAtPlayhead() }
        binding.deleteButton.setOnClickListener { deleteSelectedClip() }

        binding.timeline.onClipSelected = { id ->
            selectedClipId = id
            updateSelectionUi()
        }
        binding.timeline.onScrubbed = { position ->
            userScrubbing = true
            previewPlayer.pause()
            playbackClipId = null
            setTimelinePosition(position)
            val now = SystemClock.uptimeMillis()
            if (now - lastScrubSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
                seekPreviewToTimeline(position)
                lastScrubSeekAt = now
            }
        }
        binding.timeline.onSeekFinished = { position ->
            setTimelinePosition(position)
            seekPreviewToTimeline(position)
            userScrubbing = false
            saveProject()
        }
        binding.timeline.onTrimChanged = { clipId, startMs, endMs, finished ->
            applyTrim(clipId, startMs, endMs, finished)
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        previewPlayer.load(Uri.parse(project.sourceUri))

        renderTimelineState()
    }

    override fun onPause() {
        super.onPause()
        if (::previewPlayer.isInitialized) previewPlayer.pause()
        if (::project.isInitialized) saveProject()
    }

    override fun onDestroy() {
        if (::thumbnailExtractor.isInitialized) thumbnailExtractor.release()
        if (::previewPlayer.isInitialized) previewPlayer.release()
        super.onDestroy()
    }

    override fun onReady(durationMs: Int) {
        sourceDurationMs = durationMs.coerceAtLeast(0)
        clips = TimelineMath.sanitized(clips, sourceDurationMs)
        if (clips.isEmpty() && sourceDurationMs > 0) {
            clips = listOf(
                Clip(
                    id = UUID.randomUUID().toString(),
                    sourceStartMs = 0,
                    sourceEndMs = sourceDurationMs
                )
            )
        }
        if (selectedClipId == null || clips.none { it.id == selectedClipId }) {
            selectedClipId = clips.firstOrNull()?.id
        }
        timelinePositionMs = timelinePositionMs.coerceIn(0, TimelineMath.totalDurationMs(clips))
        binding.playerError.visibility = View.GONE
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
    }

    override fun onProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean) {
        if (!isPlaying || userScrubbing || clips.isEmpty()) return
        val clipId = playbackClipId ?: return
        val clipIndex = clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        val clip = clips[clipIndex]

        if (positionMs >= clip.sourceEndMs - END_GUARD_MS) {
            advancePlayback(clipIndex)
            return
        }
        if (positionMs < clip.sourceStartMs - SEEK_GUARD_MS || positionMs > clip.sourceEndMs + SEEK_GUARD_MS) {
            return
        }

        val timelineStart = TimelineMath.clipStartMs(clips, clip.id)
        val offset = (positionMs - clip.sourceStartMs).coerceIn(0, clip.durationMs)
        setTimelinePosition(timelineStart + offset)
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

    private fun startPlaybackFromTimeline() {
        val total = TimelineMath.totalDurationMs(clips)
        if (total <= 0) return
        if (timelinePositionMs >= total) setTimelinePosition(0)
        val location = TimelineMath.locate(clips, timelinePositionMs) ?: return
        playbackClipId = location.clip.id
        selectedClipId = location.clip.id
        updateSelectionUi()
        previewPlayer.playFrom(location.sourcePositionMs)
    }

    private fun advancePlayback(currentIndex: Int) {
        val next = clips.getOrNull(currentIndex + 1)
        if (next == null) {
            previewPlayer.pause()
            playbackClipId = null
            setTimelinePosition(TimelineMath.totalDurationMs(clips))
            saveProject()
            return
        }
        playbackClipId = next.id
        previewPlayer.playFrom(next.sourceStartMs)
    }

    private fun seekPreviewToTimeline(positionMs: Int) {
        val location = TimelineMath.locate(clips, positionMs) ?: return
        previewPlayer.seekTo(location.sourcePositionMs)
    }

    private fun splitAtPlayhead() {
        previewPlayer.pause()
        playbackClipId = null
        val location = TimelineMath.locate(clips, timelinePositionMs) ?: return
        if (location.offsetMs < MIN_SPLIT_EDGE_MS || location.clip.durationMs - location.offsetMs < MIN_SPLIT_EDGE_MS) {
            return
        }
        val splitSource = location.clip.sourceStartMs + location.offsetMs
        val left = location.clip.copy(
            id = UUID.randomUUID().toString(),
            sourceEndMs = splitSource
        )
        val right = location.clip.copy(
            id = UUID.randomUUID().toString(),
            sourceStartMs = splitSource
        )
        clips = clips.toMutableList().apply {
            removeAt(location.clipIndex)
            add(location.clipIndex, right)
            add(location.clipIndex, left)
        }
        selectedClipId = right.id
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
    }

    private fun deleteSelectedClip() {
        if (clips.size <= 1) return
        val selected = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == selected }
        if (index < 0) return

        previewPlayer.pause()
        playbackClipId = null
        val removedTimelineStart = TimelineMath.clipStartMs(clips, selected)
        val nextClips = clips.toMutableList().apply { removeAt(index) }
        clips = nextClips
        selectedClipId = clips.getOrNull(index)?.id ?: clips.lastOrNull()?.id
        timelinePositionMs = removedTimelineStart.coerceIn(0, TimelineMath.totalDurationMs(clips))
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
    }

    private fun applyTrim(clipId: String, startMs: Int, endMs: Int, finished: Boolean) {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return
        val sourceMax = sourceDurationMs.takeIf { it > 1 } ?: Int.MAX_VALUE
        val safeStart = startMs.coerceIn(0, sourceMax - 1)
        val safeEnd = endMs.coerceIn(safeStart + 1, sourceMax)

        clips = clips.toMutableList().apply {
            this[index] = this[index].copy(sourceStartMs = safeStart, sourceEndMs = safeEnd)
        }
        timelinePositionMs = timelinePositionMs.coerceIn(0, TimelineMath.totalDurationMs(clips))
        renderTimelineState()

        if (finished) {
            previewPlayer.pause()
            playbackClipId = null
            seekPreviewToTimeline(timelinePositionMs)
            requestThumbnails()
            saveProject()
        }
    }

    private fun renderTimelineState() {
        binding.timeline.setClips(clips, selectedClipId)
        binding.timeline.positionMs = timelinePositionMs
        binding.currentTime.text = formatTime(timelinePositionMs)
        binding.totalTime.text = formatTime(TimelineMath.totalDurationMs(clips))
        updateSelectionUi()
    }

    private fun setTimelinePosition(positionMs: Int) {
        timelinePositionMs = positionMs.coerceIn(0, TimelineMath.totalDurationMs(clips))
        binding.timeline.positionMs = timelinePositionMs
        binding.currentTime.text = formatTime(timelinePositionMs)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val selected = clips.firstOrNull { it.id == selectedClipId }
        val location = TimelineMath.locate(clips, timelinePositionMs)
        val canSplit = location != null &&
            location.offsetMs >= MIN_SPLIT_EDGE_MS &&
            location.clip.durationMs - location.offsetMs >= MIN_SPLIT_EDGE_MS

        binding.splitButton.isEnabled = canSplit
        binding.splitButton.alpha = if (canSplit) 1f else 0.42f
        binding.deleteButton.isEnabled = selected != null && clips.size > 1
        binding.deleteButton.alpha = if (binding.deleteButton.isEnabled) 1f else 0.42f

        if (selected == null) {
            binding.selectionLabel.text = "No clip selected"
        } else {
            val number = clips.indexOfFirst { it.id == selected.id } + 1
            binding.selectionLabel.text = "Clip $number · ${formatTime(selected.durationMs)}"
        }
    }

    private fun requestThumbnails() {
        if (clips.isEmpty()) return
        thumbnailExtractor.request(Uri.parse(project.sourceUri), clips) { frames ->
            if (!isFinishing && !isDestroyed) binding.timeline.setThumbnails(frames)
        }
    }

    private fun saveProject() {
        if (!::project.isInitialized) return
        project = project.copy(
            updatedAt = System.currentTimeMillis(),
            sourceDurationMs = sourceDurationMs.coerceAtLeast(project.sourceDurationMs),
            clips = clips,
            playheadMs = timelinePositionMs,
            selectedClipId = selectedClipId
        )
        repository.save(project)
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
        private const val SCRUB_SEEK_INTERVAL_MS = 45L
        private const val MIN_SPLIT_EDGE_MS = 300
        private const val END_GUARD_MS = 35
        private const val SEEK_GUARD_MS = 700
    }
}
