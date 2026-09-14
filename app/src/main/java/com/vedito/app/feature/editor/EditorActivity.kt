package com.vedito.app.feature.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.vedito.app.R
import com.vedito.app.core.media.MediaProbe
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.Project
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.core.timeline.TimelineEditor
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
    private lateinit var mediaProbe: MediaProbe
    private lateinit var project: Project

    private var assets: List<MediaAsset> = emptyList()
    private var clips: List<Clip> = emptyList()
    private var selectedClipId: String? = null
    private var timelinePositionMs: Int = 0
    private var playbackClipId: String? = null
    private var userScrubbing = false
    private var lastScrubSeekAt = 0L
    private var addingMedia = false

    private val addVideoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ADDED_VIDEOS)
    ) { uris ->
        if (uris.isNotEmpty()) addMedia(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureVeditoSystemBars()
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        repository = ProjectRepository(this)
        thumbnailExtractor = ThumbnailExtractor(this)
        mediaProbe = MediaProbe(this)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val loaded = projectId?.let(repository::find)
        if (loaded == null) {
            finish()
            return
        }

        project = loaded
        assets = project.assets
        clips = TimelineMath.sanitized(project.clips, assets)
        selectedClipId = project.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        timelinePositionMs = project.playheadMs.coerceIn(0, TimelineMath.totalDurationMs(clips))

        binding.projectTitle.text = project.title
        binding.backButton.setOnClickListener { finish() }
        binding.playPauseButton.setOnClickListener {
            if (previewPlayer.isPlaying()) previewPlayer.pause() else startPlaybackFromTimeline()
        }
        binding.addClipButton.setOnClickListener {
            if (!addingMedia) {
                addVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
        binding.timeline.onReorderRequested = { clipId, targetIndex, finished ->
            reorderClip(clipId, targetIndex, finished)
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        renderTimelineState()
        openInitialPreview()
        requestThumbnails()
    }

    override fun onPause() {
        super.onPause()
        if (::previewPlayer.isInitialized) previewPlayer.pause()
        if (::project.isInitialized) saveProject()
    }

    override fun onDestroy() {
        if (::thumbnailExtractor.isInitialized) thumbnailExtractor.release()
        if (::mediaProbe.isInitialized) mediaProbe.release()
        if (::previewPlayer.isInitialized) previewPlayer.release()
        super.onDestroy()
    }

    override fun onReady(uri: Uri, durationMs: Int) {
        val index = assets.indexOfFirst { Uri.parse(it.uri) == uri }
        if (index >= 0 && durationMs > 0 && assets[index].durationMs != durationMs) {
            assets = assets.toMutableList().apply {
                this[index] = this[index].copy(durationMs = durationMs)
            }
        }

        if (clips.isEmpty() && index >= 0 && durationMs > 0) {
            val asset = assets[index]
            val first = Clip(
                id = UUID.randomUUID().toString(),
                assetId = asset.id,
                sourceStartMs = 0,
                sourceEndMs = durationMs
            )
            clips = listOf(first)
            selectedClipId = first.id
            timelinePositionMs = 0
        }

        clips = TimelineMath.sanitized(clips, assets)
        if (selectedClipId == null || clips.none { it.id == selectedClipId }) {
            selectedClipId = clips.firstOrNull()?.id
        }
        timelinePositionMs = timelinePositionMs.coerceIn(0, TimelineMath.totalDurationMs(clips))

        binding.playerError.visibility = View.GONE
        binding.playPauseButton.isEnabled = true
        renderTimelineState()
        requestThumbnails()
        saveProject()
    }

    override fun onProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean) {
        if (!isPlaying || userScrubbing || clips.isEmpty()) return
        val clipId = playbackClipId ?: return
        val clipIndex = clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        val clip = clips[clipIndex]
        val asset = assetFor(clip) ?: return
        if (previewPlayer.currentUri != Uri.parse(asset.uri)) return

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

    private fun openInitialPreview() {
        val location = TimelineMath.locate(clips, timelinePositionMs)
        if (location != null) {
            showClip(location.clip, location.sourcePositionMs, play = false)
            return
        }
        assets.firstOrNull()?.let { asset ->
            binding.playerError.visibility = View.GONE
            previewPlayer.load(Uri.parse(asset.uri), 0, false)
        }
    }

    private fun startPlaybackFromTimeline() {
        val total = TimelineMath.totalDurationMs(clips)
        if (total <= 0) return
        if (timelinePositionMs >= total) setTimelinePosition(0)
        val location = TimelineMath.locate(clips, timelinePositionMs) ?: return
        playbackClipId = location.clip.id
        selectedClipId = location.clip.id
        updateSelectionUi()
        showClip(location.clip, location.sourcePositionMs, play = true)
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
        selectedClipId = next.id
        setTimelinePosition(TimelineMath.clipStartMs(clips, next.id))
        showClip(next, next.sourceStartMs, play = true)
    }

    private fun seekPreviewToTimeline(positionMs: Int) {
        val location = TimelineMath.locate(clips, positionMs) ?: return
        showClip(location.clip, location.sourcePositionMs, play = false)
    }

    private fun showClip(clip: Clip, sourcePositionMs: Int, play: Boolean) {
        val asset = assetFor(clip) ?: return
        binding.playerError.visibility = View.GONE
        binding.playPauseButton.isEnabled = true
        previewPlayer.load(Uri.parse(asset.uri), sourcePositionMs, play)
    }

    private fun splitAtPlayhead() {
        previewPlayer.pause()
        playbackClipId = null
        val result = TimelineEditor.split(clips, timelinePositionMs, MIN_SPLIT_EDGE_MS) ?: return
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
    }

    private fun deleteSelectedClip() {
        val result = TimelineEditor.delete(clips, selectedClipId, timelinePositionMs) ?: return
        previewPlayer.pause()
        playbackClipId = null
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
    }

    private fun applyTrim(clipId: String, startMs: Int, endMs: Int, finished: Boolean) {
        val clip = clips.firstOrNull { it.id == clipId } ?: return
        val assetDuration = assetFor(clip)?.durationMs ?: 0
        clips = TimelineEditor.trim(clips, clipId, startMs, endMs, assetDuration)
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

    private fun reorderClip(clipId: String, targetIndex: Int, finished: Boolean) {
        val reordered = TimelineEditor.reorder(clips, clipId, targetIndex)
        if (reordered != clips) {
            clips = reordered
            selectedClipId = clipId
            renderTimelineState()
        }

        if (finished) {
            previewPlayer.pause()
            playbackClipId = null
            selectedClipId = clipId
            timelinePositionMs = TimelineMath.clipStartMs(clips, clipId)
            renderTimelineState()
            seekPreviewToTimeline(timelinePositionMs)
            requestThumbnails()
            saveProject()
        }
    }

    private fun addMedia(uris: List<Uri>) {
        if (addingMedia) return
        addingMedia = true
        updateAddButton()

        val unique = uris.distinctBy(Uri::toString)
        unique.forEach(::persistReadAccess)
        val names = unique.associate { it.toString() to resolveDisplayName(it) }

        mediaProbe.probe(unique) { results ->
            val existingByUri = assets.associateBy { it.uri }.toMutableMap()
            val updatedAssets = assets.toMutableList()
            val additions = mutableListOf<Clip>()

            results.forEach { result ->
                val key = result.uri.toString()
                val asset = existingByUri[key] ?: MediaAsset(
                    id = UUID.randomUUID().toString(),
                    uri = key,
                    displayName = names[key] ?: "Video",
                    durationMs = result.durationMs
                ).also {
                    existingByUri[key] = it
                    updatedAssets += it
                }

                val normalizedAsset = if (asset.durationMs != result.durationMs) {
                    asset.copy(durationMs = result.durationMs).also { replacement ->
                        val assetIndex = updatedAssets.indexOfFirst { it.id == replacement.id }
                        if (assetIndex >= 0) updatedAssets[assetIndex] = replacement
                        existingByUri[key] = replacement
                    }
                } else asset

                additions += Clip(
                    id = UUID.randomUUID().toString(),
                    assetId = normalizedAsset.id,
                    sourceStartMs = 0,
                    sourceEndMs = result.durationMs
                )
            }

            assets = updatedAssets
            if (additions.isNotEmpty()) {
                clips = TimelineEditor.insertAfter(clips, selectedClipId, additions)
                selectedClipId = additions.first().id
                timelinePositionMs = TimelineMath.clipStartMs(clips, additions.first().id)
                renderTimelineState()
                seekPreviewToTimeline(timelinePositionMs)
                requestThumbnails()
                saveProject()
            } else {
                binding.selectionLabel.text = "Selected video could not be read"
            }

            addingMedia = false
            updateAddButton()
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
            val source = assetFor(selected)?.displayName?.substringBeforeLast('.')?.take(18).orEmpty()
            binding.selectionLabel.text = if (source.isBlank()) {
                "Clip $number · ${formatTime(selected.durationMs)}"
            } else {
                "Clip $number · $source · ${formatTime(selected.durationMs)}"
            }
        }
    }

    private fun updateAddButton() {
        binding.addClipButton.isEnabled = !addingMedia
        binding.addClipButton.alpha = if (addingMedia) 0.5f else 1f
        binding.addClipButton.text = if (addingMedia) "Adding…" else "Add video"
    }

    private fun requestThumbnails() {
        if (clips.isEmpty()) {
            binding.timeline.setThumbnails(emptyList())
            return
        }
        thumbnailExtractor.request(assets, clips) { frames ->
            if (!isFinishing && !isDestroyed) binding.timeline.setThumbnails(frames)
        }
    }

    private fun saveProject() {
        if (!::project.isInitialized) return
        project = project.copy(
            updatedAt = System.currentTimeMillis(),
            assets = assets,
            clips = clips,
            playheadMs = timelinePositionMs,
            selectedClipId = selectedClipId
        )
        repository.save(project)
    }

    private fun assetFor(clip: Clip): MediaAsset? = assets.firstOrNull { it.id == clip.assetId }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column) ?: "Video"
            }
        }
        return "Video"
    }

    private fun persistReadAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
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
        private const val MAX_ADDED_VIDEOS = 12
        private const val SCRUB_SEEK_INTERVAL_MS = 45L
        private const val MIN_SPLIT_EDGE_MS = 300
        private const val END_GUARD_MS = 35
        private const val SEEK_GUARD_MS = 700
    }
}
