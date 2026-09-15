package com.vedito.app.feature.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.vedito.app.R
import com.vedito.app.core.audio.AudioPlaybackEngine
import com.vedito.app.core.audio.AudioProbe
import com.vedito.app.core.audio.AudioTimelineEditor
import com.vedito.app.core.audio.AudioWaveformCache
import com.vedito.app.core.media.MediaProbe
import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.CanvasAspect
import com.vedito.app.core.model.CanvasBackground
import com.vedito.app.core.model.CanvasSettings
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.Project
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.core.timeline.EditorHistory
import com.vedito.app.core.timeline.FrameTimecode
import com.vedito.app.core.timeline.TimelineEditor
import com.vedito.app.core.timeline.TimelineIndex
import com.vedito.app.core.timeline.TimelineMath
import com.vedito.app.core.visual.VisualTransformMath
import com.vedito.app.databinding.ActivityEditorBinding
import com.vedito.app.feature.editor.player.PreviewPlayer
import com.vedito.app.feature.editor.timeline.ThumbnailExtractor
import com.vedito.app.feature.editor.visual.TransformToolbarView
import com.vedito.app.ui.applySystemBarInsets
import com.vedito.app.ui.configureVeditoSystemBars
import java.util.UUID
import kotlin.math.roundToInt

class EditorActivity : ComponentActivity(), PreviewPlayer.Listener {
    private lateinit var binding: ActivityEditorBinding
    private lateinit var repository: ProjectRepository
    private lateinit var previewPlayer: PreviewPlayer
    private lateinit var thumbnailExtractor: ThumbnailExtractor
    private lateinit var mediaProbe: MediaProbe
    private lateinit var audioProbe: AudioProbe
    private lateinit var audioPlayback: AudioPlaybackEngine
    private lateinit var audioWaveformCache: AudioWaveformCache
    private lateinit var project: Project

    private val history = EditorHistory(HISTORY_LIMIT)
    private var timelineIndex = TimelineIndex(emptyList())
    private var assets: List<MediaAsset> = emptyList()
    private var clips: List<Clip> = emptyList()
    private var audioAssets: List<AudioAsset> = emptyList()
    private var audioClips: List<AudioClip> = emptyList()
    private var canvasSettings = CanvasSettings()
    private var waveformsByAssetId: Map<String, FloatArray> = emptyMap()
    private var selectedClipId: String? = null
    private var selectedAudioClipId: String? = null
    private var timelinePositionMs: Int = 0
    private var playbackClipId: String? = null
    private var userScrubbing = false
    private var lastScrubSeekAt = 0L
    private var addingMedia = false
    private var timelineZoom = 1f
    private var timelineViewportStartMs = 0
    private var pendingTrimSnapshot: EditorHistory.Snapshot? = null
    private var pendingReorderSnapshot: EditorHistory.Snapshot? = null
    private var replaceTargetClipId: String? = null
    private var pendingAudioEditSnapshot: EditorHistory.Snapshot? = null

    private val addVideoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ADDED_VIDEOS)
    ) { uris ->
        if (uris.isNotEmpty()) addMedia(uris)
    }

    private val replaceVideoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = replaceTargetClipId
        replaceTargetClipId = null
        if (uri != null && target != null) replaceClipMedia(target, uri)
    }

    private val addAudioPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) addAudio(uris.take(MAX_ADDED_AUDIO))
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
        audioProbe = AudioProbe(this)
        audioPlayback = AudioPlaybackEngine(this)
        audioWaveformCache = AudioWaveformCache(this)

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val loaded = projectId?.let(repository::find)
        if (loaded == null) {
            finish()
            return
        }

        project = loaded
        assets = project.assets
        clips = TimelineMath.sanitized(project.clips, assets)
        audioAssets = project.audioAssets
        audioClips = sanitizeAudioClips(project.audioClips)
        canvasSettings = project.canvasSettings
        selectedClipId = project.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = project.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        refreshTimelineIndex()
        timelinePositionMs = project.playheadMs.coerceIn(0, timelineIndex.totalDurationMs)
        timelineZoom = project.timelineZoom.coerceIn(1f, 8f)
        timelineViewportStartMs = project.timelineViewportStartMs.coerceAtLeast(0)

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
        binding.undoButton.setOnClickListener { undoEdit() }
        binding.redoButton.setOnClickListener { redoEdit() }
        binding.duplicateButton.setOnClickListener { duplicateSelectedClip() }
        binding.replaceButton.setOnClickListener { launchReplaceSelectedClip() }
        binding.addAudioButton.setOnClickListener { addAudioPicker.launch(arrayOf("audio/*")) }
        binding.audioMuteButton.setOnClickListener { toggleSelectedAudioMute() }
        binding.audioVolumeDownButton.setOnClickListener { adjustSelectedAudioVolume(-0.1f) }
        binding.audioVolumeUpButton.setOnClickListener { adjustSelectedAudioVolume(0.1f) }
        binding.audioDeleteButton.setOnClickListener { deleteSelectedAudio() }
        binding.audioSplitButton.setOnClickListener { splitSelectedAudioAtPlayhead() }
        binding.audioFadeInButton.setOnClickListener { cycleSelectedAudioFade(inward = true) }
        binding.audioFadeOutButton.setOnClickListener { cycleSelectedAudioFade(inward = false) }
        binding.extractAudioButton.setOnClickListener { extractAudioFromSelectedVideo() }
        binding.visualToolbar.onAction = ::handleVisualAction
        binding.previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCanvasPreviewLayout() }
        binding.audioTimeline.onAudioClipSelected = { id ->
            selectedAudioClipId = id
            updateAudioUi()
            renderAudioState()
            saveProject()
        }
        binding.audioTimeline.onAudioEditStart = { id ->
            selectedAudioClipId = id
            pendingAudioEditSnapshot = snapshot()
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
        }
        binding.audioTimeline.onAudioClipEditChanged = { edited, finished ->
            audioClips = audioClips.map { if (it.id == edited.id) edited else it }.sortedBy { it.timelineStartMs }
            selectedAudioClipId = edited.id
            if (finished) finishAudioGestureEdit()
        }

        binding.timeline.onClipSelected = { id ->
            selectedClipId = id
            updateSelectionUi()
            updateVisualToolbar()
            saveProject()
        }
        binding.timeline.onScrubbed = { position ->
            userScrubbing = true
            previewPlayer.pause()
            audioPlayback.pause()
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
            audioPlayback.seekTo(position)
            userScrubbing = false
            saveProject()
        }
        binding.timeline.onTrimChanged = { clipId, startMs, endMs, finished ->
            applyTrim(clipId, startMs, endMs, finished)
        }
        binding.timeline.onReorderRequested = { clipId, targetIndex, finished ->
            reorderClip(clipId, targetIndex, finished)
        }
        binding.timeline.onViewportChanged = { zoom, startMs, finished ->
            timelineZoom = zoom
            timelineViewportStartMs = startMs
            updateZoomUi()
            renderAudioState()
            if (finished) {
                requestThumbnails()
                saveProject()
            }
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        applyCanvasPreviewLayout()
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderTimelineState(restoreViewport = true)
        openInitialPreview()
        requestThumbnails()
        requestAudioWaveforms()
        probeKnownAssets()
    }

    override fun onPause() {
        super.onPause()
        if (::previewPlayer.isInitialized) previewPlayer.pause()
        if (::audioPlayback.isInitialized) audioPlayback.pause()
        if (::project.isInitialized) saveProject()
    }

    override fun onDestroy() {
        if (::thumbnailExtractor.isInitialized) thumbnailExtractor.release()
        if (::mediaProbe.isInitialized) mediaProbe.release()
        if (::audioProbe.isInitialized) audioProbe.release()
        if (::audioPlayback.isInitialized) audioPlayback.release()
        if (::audioWaveformCache.isInitialized) audioWaveformCache.release()
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
        refreshTimelineIndex()
        timelinePositionMs = timelinePositionMs.coerceIn(0, timelineIndex.totalDurationMs)

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

        val timelineStart = timelineIndex.startOf(clip.id)
        val offset = (positionMs - clip.sourceStartMs).coerceIn(0, clip.durationMs)
        setTimelinePosition(timelineStart + offset)
        audioPlayback.sync(timelinePositionMs, playing = true)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = if (isPlaying) "Pause" else "Play"
        if (isPlaying) audioPlayback.playFrom(timelinePositionMs) else audioPlayback.pause()
    }

    override fun onError(message: String) {
        binding.playerError.text = message
        binding.playerError.visibility = View.VISIBLE
        binding.playPauseButton.isEnabled = false
    }

    private fun openInitialPreview() {
        val location = timelineIndex.locate(timelinePositionMs)
        if (location != null) {
            showClip(location.clip, location.sourcePositionMs, play = false)
            return
        }
        assets.firstOrNull()?.let { asset ->
            binding.playerError.visibility = View.GONE
            previewPlayer.setVisualTransform(ClipTransform())
            applyCanvasPreviewLayout()
            previewPlayer.load(Uri.parse(asset.uri), 0, false)
        }
    }

    private fun startPlaybackFromTimeline() {
        val total = timelineIndex.totalDurationMs
        if (total <= 0) return
        if (timelinePositionMs >= total) setTimelinePosition(0)
        val location = timelineIndex.locate(timelinePositionMs) ?: return
        playbackClipId = location.clip.id
        selectedClipId = location.clip.id
        updateSelectionUi()
        showClip(location.clip, location.sourcePositionMs, play = true)
    }

    private fun advancePlayback(currentIndex: Int) {
        val next = clips.getOrNull(currentIndex + 1)
        if (next == null) {
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
            setTimelinePosition(timelineIndex.totalDurationMs)
            saveProject()
            return
        }

        playbackClipId = next.id
        selectedClipId = next.id
        setTimelinePosition(timelineIndex.startOf(next.id))
        showClip(next, next.sourceStartMs, play = true)
    }

    private fun seekPreviewToTimeline(positionMs: Int) {
        val location = timelineIndex.locate(positionMs) ?: return
        showClip(location.clip, location.sourcePositionMs, play = false)
    }

    private fun showClip(clip: Clip, sourcePositionMs: Int, play: Boolean) {
        val asset = assetFor(clip) ?: return
        binding.playerError.visibility = View.GONE
        binding.playPauseButton.isEnabled = true
        previewPlayer.setVisualTransform(clip.transform)
        applyCanvasPreviewLayout(clip)
        previewPlayer.load(Uri.parse(asset.uri), sourcePositionMs, play)
    }

    private fun handleVisualAction(action: TransformToolbarView.Action) {
        when (action) {
            TransformToolbarView.Action.SCALE_DOWN -> mutateSelectedTransform { it.copy(scale = it.scale - 0.1f) }
            TransformToolbarView.Action.SCALE_UP -> mutateSelectedTransform { it.copy(scale = it.scale + 0.1f) }
            TransformToolbarView.Action.MOVE_LEFT -> mutateSelectedTransform { it.copy(positionX = it.positionX - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_RIGHT -> mutateSelectedTransform { it.copy(positionX = it.positionX + POSITION_STEP) }
            TransformToolbarView.Action.MOVE_UP -> mutateSelectedTransform { it.copy(positionY = it.positionY - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_DOWN -> mutateSelectedTransform { it.copy(positionY = it.positionY + POSITION_STEP) }
            TransformToolbarView.Action.ROTATE_90 -> mutateSelectedTransform { it.copy(rotationDegrees = it.rotationDegrees + 90f) }
            TransformToolbarView.Action.FLIP_HORIZONTAL -> mutateSelectedTransform { it.copy(flipHorizontal = !it.flipHorizontal) }
            TransformToolbarView.Action.FLIP_VERTICAL -> mutateSelectedTransform { it.copy(flipVertical = !it.flipVertical) }
            TransformToolbarView.Action.OPACITY_CYCLE -> mutateSelectedTransform {
                val next = when {
                    it.opacity > 0.76f -> 0.75f
                    it.opacity > 0.51f -> 0.50f
                    it.opacity > 0.26f -> 0.25f
                    else -> 1f
                }
                it.copy(opacity = next)
            }
            TransformToolbarView.Action.FIT_TOGGLE -> mutateSelectedTransform {
                it.copy(fitMode = if (it.fitMode == ClipFitMode.FIT) ClipFitMode.FILL else ClipFitMode.FIT)
            }
            TransformToolbarView.Action.CROP_LEFT -> mutateSelectedTransform { it.copy(cropLeft = nextCropEdge(it.cropLeft)) }
            TransformToolbarView.Action.CROP_RIGHT -> mutateSelectedTransform { it.copy(cropRight = nextCropEdge(it.cropRight)) }
            TransformToolbarView.Action.CROP_TOP -> mutateSelectedTransform { it.copy(cropTop = nextCropEdge(it.cropTop)) }
            TransformToolbarView.Action.CROP_BOTTOM -> mutateSelectedTransform { it.copy(cropBottom = nextCropEdge(it.cropBottom)) }
            TransformToolbarView.Action.CROP_RESET -> mutateSelectedTransform {
                it.copy(cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f)
            }
            TransformToolbarView.Action.CANVAS_RATIO -> mutateCanvasSettings {
                val values = CanvasAspect.values().toList()
                val next = values[(values.indexOf(it.aspect) + 1) % values.size]
                it.copy(aspect = next)
            }
            TransformToolbarView.Action.CANVAS_BACKGROUND -> mutateCanvasSettings {
                val values = CanvasBackground.values().toList()
                val next = values[(values.indexOf(it.background) + 1) % values.size]
                it.copy(background = next)
            }
            TransformToolbarView.Action.RESET_TRANSFORM -> mutateSelectedTransform { ClipTransform() }
        }
    }

    private fun mutateSelectedTransform(change: (ClipTransform) -> ClipTransform) {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = clips[index]
        val nextTransform = VisualTransformMath.normalize(change(current.transform))
        if (nextTransform == current.transform) return

        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        clips = clips.toMutableList().apply { this[index] = current.copy(transform = nextTransform) }
        refreshTimelineIndex()
        history.record(before)

        val currentLocation = timelineIndex.locate(timelinePositionMs)
        if (currentLocation?.clip?.id != id) {
            setTimelinePosition(timelineIndex.startOf(id))
            seekPreviewToTimeline(timelinePositionMs)
        } else {
            previewPlayer.setVisualTransform(nextTransform)
            applyCanvasPreviewLayout(clips[index])
        }
        updateSelectionUi()
        updateVisualToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun mutateCanvasSettings(change: (CanvasSettings) -> CanvasSettings) {
        val before = snapshot()
        val next = change(canvasSettings)
        if (next == canvasSettings) return
        canvasSettings = next
        history.record(before)
        applyCanvasPreviewLayout()
        updateVisualToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun nextCropEdge(value: Float): Float {
        return if (value >= 0.40f) 0f else (value + CROP_STEP).coerceAtMost(0.40f)
    }

    private fun updateVisualToolbar() {
        val transform = clips.firstOrNull { it.id == selectedClipId }?.transform
        binding.visualToolbar.setState(transform, canvasSettings)
    }

    private fun applyCanvasPreviewLayout(preferredClip: Clip? = null) {
        val parentWidth = binding.previewContainer.width
        val parentHeight = binding.previewContainer.height
        if (parentWidth <= 0 || parentHeight <= 0) return

        val activeClip = preferredClip
            ?: timelineIndex.locate(timelinePositionMs)?.clip
            ?: clips.firstOrNull { it.id == selectedClipId }
            ?: clips.firstOrNull()
        val asset = activeClip?.let(::assetFor)
        val ratio = VisualTransformMath.canvasRatio(
            canvasSettings.aspect,
            asset?.width ?: 0,
            asset?.height ?: 0
        ).coerceIn(0.25f, 4f)

        val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val canvasWidth: Int
        val canvasHeight: Int
        if (parentRatio > ratio) {
            canvasHeight = parentHeight
            canvasWidth = (canvasHeight * ratio).roundToInt().coerceAtLeast(1)
        } else {
            canvasWidth = parentWidth
            canvasHeight = (canvasWidth / ratio).roundToInt().coerceAtLeast(1)
        }

        val current = binding.canvasSurface.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.width != canvasWidth || current.height != canvasHeight || current.gravity != Gravity.CENTER) {
            binding.canvasSurface.layoutParams = FrameLayout.LayoutParams(canvasWidth, canvasHeight, Gravity.CENTER)
        }
        binding.canvasSurface.setBackgroundColor(canvasSettings.background.argb)
        if (::previewPlayer.isInitialized && activeClip != null) {
            previewPlayer.setVisualTransform(activeClip.transform)
        }
    }

    private fun splitAtPlayhead() {
        previewPlayer.pause()
        playbackClipId = null
        val before = snapshot()
        val result = TimelineEditor.split(clips, timelinePositionMs, MIN_SPLIT_EDGE_MS) ?: return
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        commitMutation(before)
    }

    private fun deleteSelectedClip() {
        val before = snapshot()
        val result = TimelineEditor.delete(clips, selectedClipId, timelinePositionMs) ?: return
        previewPlayer.pause()
        playbackClipId = null
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        pruneUnusedAssets()
        commitMutation(before)
    }

    private fun duplicateSelectedClip() {
        val before = snapshot()
        val result = TimelineEditor.duplicate(clips, selectedClipId) ?: return
        previewPlayer.pause()
        playbackClipId = null
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        commitMutation(before)
    }

    private fun applyTrim(clipId: String, startMs: Int, endMs: Int, finished: Boolean) {
        if (pendingTrimSnapshot == null) pendingTrimSnapshot = snapshot()
        val clip = clips.firstOrNull { it.id == clipId } ?: return
        val assetDuration = assetFor(clip)?.durationMs ?: 0
        clips = TimelineEditor.trim(clips, clipId, startMs, endMs, assetDuration)
        refreshTimelineIndex()
        timelinePositionMs = timelinePositionMs.coerceIn(0, timelineIndex.totalDurationMs)
        renderTimelineState()

        if (finished) {
            previewPlayer.pause()
            playbackClipId = null
            val before = pendingTrimSnapshot
            pendingTrimSnapshot = null
            if (before != null && before != snapshot()) history.record(before)
            seekPreviewToTimeline(timelinePositionMs)
            requestThumbnails()
            saveProject()
            updateHistoryUi()
        }
    }

    private fun reorderClip(clipId: String, targetIndex: Int, finished: Boolean) {
        if (pendingReorderSnapshot == null) pendingReorderSnapshot = snapshot()
        val reordered = TimelineEditor.reorder(clips, clipId, targetIndex)
        if (reordered != clips) {
            clips = reordered
            selectedClipId = clipId
            refreshTimelineIndex()
            renderTimelineState()
        }

        if (finished) {
            previewPlayer.pause()
            playbackClipId = null
            selectedClipId = clipId
            timelinePositionMs = timelineIndex.startOf(clipId)
            val before = pendingReorderSnapshot
            pendingReorderSnapshot = null
            if (before != null && before != snapshot()) history.record(before)
            renderTimelineState()
            seekPreviewToTimeline(timelinePositionMs)
            requestThumbnails()
            saveProject()
            updateHistoryUi()
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
            val before = snapshot()
            val existingByUri = assets.associateBy { it.uri }.toMutableMap()
            val updatedAssets = assets.toMutableList()
            val additions = mutableListOf<Clip>()

            results.forEach { result ->
                val key = result.uri.toString()
                val asset = existingByUri[key] ?: MediaAsset(
                    id = UUID.randomUUID().toString(),
                    uri = key,
                    displayName = names[key] ?: "Video",
                    durationMs = result.durationMs,
                    frameRate = result.frameRate,
                    width = result.width,
                    height = result.height
                ).also {
                    existingByUri[key] = it
                    updatedAssets += it
                }

                val normalizedAsset = if (asset.durationMs != result.durationMs || asset.frameRate != result.frameRate || asset.width != result.width || asset.height != result.height) {
                    asset.copy(
                        durationMs = result.durationMs,
                        frameRate = result.frameRate,
                        width = result.width,
                        height = result.height
                    ).also { replacement ->
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
                refreshTimelineIndex()
                timelinePositionMs = timelineIndex.startOf(additions.first().id)
                history.record(before)
                renderTimelineState()
                seekPreviewToTimeline(timelinePositionMs)
                requestThumbnails()
                saveProject()
                updateHistoryUi()
            } else {
                binding.selectionLabel.text = "Selected video could not be read"
            }

            addingMedia = false
            updateAddButton()
        }
    }

    private fun launchReplaceSelectedClip() {
        val selected = selectedClipId ?: return
        if (clips.none { it.id == selected }) return
        replaceTargetClipId = selected
        replaceVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun replaceClipMedia(targetClipId: String, uri: Uri) {
        persistReadAccess(uri)
        val name = resolveDisplayName(uri)
        mediaProbe.probe(listOf(uri)) { results ->
            val result = results.firstOrNull() ?: run {
                binding.selectionLabel.text = "Replacement video could not be read"
                return@probe
            }
            val index = clips.indexOfFirst { it.id == targetClipId }
            if (index < 0) return@probe
            val before = snapshot()
            val original = clips[index]

            val existingAsset = assets.firstOrNull { it.uri == uri.toString() }
            val replacementAsset = existingAsset?.copy(
                durationMs = result.durationMs,
                frameRate = result.frameRate,
                width = result.width,
                height = result.height
            ) ?: MediaAsset(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                displayName = name,
                durationMs = result.durationMs,
                frameRate = result.frameRate,
                width = result.width,
                height = result.height
            )

            val updatedAssets = assets.toMutableList()
            val existingIndex = updatedAssets.indexOfFirst { it.id == replacementAsset.id }
            if (existingIndex >= 0) updatedAssets[existingIndex] = replacementAsset else updatedAssets += replacementAsset
            assets = updatedAssets

            val range = replacementRange(original, replacementAsset.durationMs)
            clips = clips.toMutableList().apply {
                this[index] = original.copy(
                    assetId = replacementAsset.id,
                    sourceStartMs = range.first,
                    sourceEndMs = range.second
                )
            }
            selectedClipId = targetClipId
            pruneUnusedAssets()
            refreshTimelineIndex()
            timelinePositionMs = timelineIndex.startOf(targetClipId)
            history.record(before)
            previewPlayer.pause()
            playbackClipId = null
            renderTimelineState()
            seekPreviewToTimeline(timelinePositionMs)
            requestThumbnails()
            saveProject()
            updateHistoryUi()
        }
    }

    private fun replacementRange(original: Clip, replacementDurationMs: Int): Pair<Int, Int> {
        if (replacementDurationMs <= 1) return 0 to replacementDurationMs.coerceAtLeast(1)
        val wantedDuration = original.durationMs.coerceAtLeast(1)
        var start = original.sourceStartMs.coerceIn(0, replacementDurationMs - 1)
        var end = (start + wantedDuration).coerceAtMost(replacementDurationMs)
        if (end - start < wantedDuration) start = (end - wantedDuration).coerceAtLeast(0)
        if (end <= start) end = (start + 1).coerceAtMost(replacementDurationMs)
        return start to end
    }

    private fun undoEdit() {
        previewPlayer.pause()
        playbackClipId = null
        pendingTrimSnapshot = null
        pendingReorderSnapshot = null
        pendingAudioEditSnapshot = null
        val target = history.undo(snapshot()) ?: return
        applySnapshot(target)
    }

    private fun redoEdit() {
        previewPlayer.pause()
        playbackClipId = null
        pendingTrimSnapshot = null
        pendingReorderSnapshot = null
        pendingAudioEditSnapshot = null
        val target = history.redo(snapshot()) ?: return
        applySnapshot(target)
    }

    private fun applySnapshot(snapshot: EditorHistory.Snapshot) {
        assets = snapshot.assets
        clips = TimelineMath.sanitized(snapshot.clips, assets)
        audioAssets = snapshot.audioAssets
        audioClips = sanitizeAudioClips(snapshot.audioClips)
        canvasSettings = snapshot.canvasSettings
        selectedClipId = snapshot.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = snapshot.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        refreshTimelineIndex()
        timelinePositionMs = snapshot.playheadMs.coerceIn(0, timelineIndex.totalDurationMs)
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        requestAudioWaveforms()
        saveProject()
        updateHistoryUi()
    }

    private fun commitMutation(before: EditorHistory.Snapshot) {
        refreshTimelineIndex()
        timelinePositionMs = timelinePositionMs.coerceIn(0, timelineIndex.totalDurationMs)
        if (before != snapshot()) history.record(before)
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
        updateHistoryUi()
    }

    private fun snapshot(): EditorHistory.Snapshot = EditorHistory.Snapshot(
        assets = assets.toList(),
        clips = clips.toList(),
        audioAssets = audioAssets.toList(),
        audioClips = audioClips.toList(),
        canvasSettings = canvasSettings,
        selectedClipId = selectedClipId,
        selectedAudioClipId = selectedAudioClipId,
        playheadMs = timelinePositionMs
    )

    private fun refreshTimelineIndex() {
        timelineIndex = TimelineIndex(clips)
    }

    private fun renderTimelineState(restoreViewport: Boolean = false) {
        refreshTimelineIndex()
        val frameRates = clips.associate { clip -> clip.id to (assetFor(clip)?.frameRate ?: MediaAsset.DEFAULT_FRAME_RATE) }
        binding.timeline.setClips(clips, selectedClipId, frameRates)
        binding.timeline.positionMs = timelinePositionMs
        if (restoreViewport) binding.timeline.restoreViewport(timelineZoom, timelineViewportStartMs)
        timelineZoom = binding.timeline.currentZoom
        timelineViewportStartMs = binding.timeline.currentViewportStartMs
        updateTimecodeUi()
        updateSelectionUi()
        updateHistoryUi()
        updateZoomUi()
        updateVisualToolbar()
        applyCanvasPreviewLayout()
        audioClips = sanitizeAudioClips(audioClips)
        if (selectedAudioClipId != null && audioClips.none { it.id == selectedAudioClipId }) selectedAudioClipId = audioClips.firstOrNull()?.id
        pruneUnusedAudioAssets()
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
    }

    private fun setTimelinePosition(positionMs: Int) {
        timelinePositionMs = positionMs.coerceIn(0, timelineIndex.totalDurationMs)
        binding.timeline.positionMs = timelinePositionMs
        timelineViewportStartMs = binding.timeline.currentViewportStartMs
        updateTimecodeUi()
        updateSelectionUi()
        updateAudioUi()
        binding.audioTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
    }

    private fun updateTimecodeUi() {
        val fps = frameRateAt(timelinePositionMs)
        binding.currentTime.text = FrameTimecode.format(timelinePositionMs, fps)
        binding.totalTime.text = FrameTimecode.format(timelineIndex.totalDurationMs, fps)
    }

    private fun frameRateAt(positionMs: Int): Float {
        val clip = timelineIndex.locate(positionMs)?.clip ?: clips.firstOrNull()
        return clip?.let { assetFor(it) }?.frameRate ?: MediaAsset.DEFAULT_FRAME_RATE
    }

    private fun updateSelectionUi() {
        val selected = clips.firstOrNull { it.id == selectedClipId }
        val location = timelineIndex.locate(timelinePositionMs)
        val canSplit = location != null &&
            location.offsetMs >= MIN_SPLIT_EDGE_MS &&
            location.clip.durationMs - location.offsetMs >= MIN_SPLIT_EDGE_MS

        binding.splitButton.isEnabled = canSplit
        binding.splitButton.alpha = if (canSplit) 1f else 0.42f
        binding.deleteButton.isEnabled = selected != null && clips.size > 1
        binding.deleteButton.alpha = if (binding.deleteButton.isEnabled) 1f else 0.42f
        binding.duplicateButton.isEnabled = selected != null
        binding.duplicateButton.alpha = if (selected != null) 1f else 0.42f
        binding.replaceButton.isEnabled = selected != null
        binding.replaceButton.alpha = if (selected != null) 1f else 0.42f
        binding.extractAudioButton.isEnabled = selected != null
        binding.extractAudioButton.alpha = if (selected != null) 1f else 0.42f

        if (selected == null) {
            binding.selectionLabel.text = "No clip selected"
        } else {
            val number = clips.indexOfFirst { it.id == selected.id } + 1
            val asset = assetFor(selected)
            val source = asset?.displayName?.substringBeforeLast('.')?.take(18).orEmpty()
            val transform = selected.transform
            val visual = "${(transform.scale * 100f).roundToInt()}% · ${transform.rotationDegrees.roundToInt()}° · ${(transform.opacity * 100f).roundToInt()}%"
            binding.selectionLabel.text = if (source.isBlank()) {
                "Clip $number · ${formatDuration(selected.durationMs)} · $visual"
            } else {
                "Clip $number · $source · $visual"
            }
        }
    }

    private fun updateHistoryUi() {
        binding.undoButton.isEnabled = history.canUndo
        binding.undoButton.alpha = if (history.canUndo) 1f else 0.38f
        binding.redoButton.isEnabled = history.canRedo
        binding.redoButton.alpha = if (history.canRedo) 1f else 0.38f
    }

    private fun updateZoomUi() {
        binding.zoomLabel.text = "${String.format("%.1f", timelineZoom)}×"
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
        val frameCount = (12f * timelineZoom).roundToInt().coerceIn(8, 48)
        thumbnailExtractor.request(assets, clips, frameCount) { frames ->
            if (!isFinishing && !isDestroyed) binding.timeline.setThumbnails(frames)
        }
    }

    private fun probeKnownAssets() {
        val snapshot = assets
        if (snapshot.isEmpty()) return
        mediaProbe.probe(snapshot.map { Uri.parse(it.uri) }) { results ->
            if (results.isEmpty()) return@probe
            val byUri = results.associateBy { it.uri.toString() }
            var changed = false
            assets = assets.map { asset ->
                val probe = byUri[asset.uri] ?: return@map asset
                if (asset.durationMs != probe.durationMs || asset.frameRate != probe.frameRate || asset.width != probe.width || asset.height != probe.height) {
                    changed = true
                    asset.copy(
                        durationMs = probe.durationMs,
                        frameRate = probe.frameRate,
                        width = probe.width,
                        height = probe.height
                    )
                } else asset
            }

            if (clips.isEmpty()) {
                val firstAsset = assets.firstOrNull { it.durationMs > 0 }
                if (firstAsset != null) {
                    val first = Clip(UUID.randomUUID().toString(), firstAsset.id, 0, firstAsset.durationMs)
                    clips = listOf(first)
                    selectedClipId = first.id
                    changed = true
                }
            }

            if (changed) {
                clips = TimelineMath.sanitized(clips, assets)
                refreshTimelineIndex()
                timelinePositionMs = timelinePositionMs.coerceIn(0, timelineIndex.totalDurationMs)
                renderTimelineState()
                requestThumbnails()
                saveProject()
            }
        }
    }

    private fun addAudio(uris: List<Uri>) {
        val projectDuration = timelineIndex.totalDurationMs
        if (projectDuration <= 0) return

        val unique = uris.distinctBy(Uri::toString)
        unique.forEach(::persistReadAccess)
        val names = unique.associate { it.toString() to resolveAudioDisplayName(it) }
        audioProbe.probe(unique) { results ->
            if (results.isEmpty()) {
                binding.audioSelectionLabel.text = "Selected audio could not be read"
                return@probe
            }

            val before = snapshot()
            val updatedAssets = audioAssets.toMutableList()
            val byUri = updatedAssets.associateBy { it.uri }.toMutableMap()
            val additions = mutableListOf<AudioClip>()
            val start = timelinePositionMs.coerceIn(0, projectDuration)
            val available = (projectDuration - start).coerceAtLeast(0)
            if (available <= 0) return@probe

            results.forEach { result ->
                val key = result.uri.toString()
                val existing = byUri[key]
                val asset = if (existing == null) {
                    AudioAsset(
                        id = UUID.randomUUID().toString(),
                        uri = key,
                        displayName = names[key] ?: "Audio",
                        durationMs = result.durationMs
                    ).also {
                        updatedAssets += it
                        byUri[key] = it
                    }
                } else if (existing.durationMs != result.durationMs) {
                    existing.copy(durationMs = result.durationMs).also { replacement ->
                        val index = updatedAssets.indexOfFirst { it.id == replacement.id }
                        if (index >= 0) updatedAssets[index] = replacement
                        byUri[key] = replacement
                    }
                } else existing

                val clipDuration = minOf(asset.durationMs, available)
                if (clipDuration > 0) {
                    additions += AudioClip(
                        id = UUID.randomUUID().toString(),
                        assetId = asset.id,
                        timelineStartMs = start,
                        sourceStartMs = 0,
                        sourceEndMs = clipDuration,
                        volume = 1f,
                        muted = false
                    )
                }
            }

            if (additions.isEmpty()) return@probe
            audioAssets = updatedAssets
            audioClips = (audioClips + additions).sortedBy { it.timelineStartMs }
            selectedAudioClipId = additions.first().id
            history.record(before)
            renderTimelineState()
            audioPlayback.seekTo(timelinePositionMs)
            requestAudioWaveforms()
            saveProject()
            updateHistoryUi()
        }
    }

    private fun splitSelectedAudioAtPlayhead() {
        val id = selectedAudioClipId ?: return
        val before = snapshot()
        val result = AudioTimelineEditor.split(audioClips, id, timelinePositionMs, MIN_AUDIO_SPLIT_EDGE_MS) ?: return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        audioClips = result.first
        selectedAudioClipId = result.second
        history.record(before)
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
        saveProject()
        updateHistoryUi()
    }

    private fun cycleSelectedAudioFade(inward: Boolean) {
        val id = selectedAudioClipId ?: return
        val index = audioClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = audioClips[index]
        val edited = if (inward) AudioTimelineEditor.cycleFadeIn(current) else AudioTimelineEditor.cycleFadeOut(current)
        audioClips = audioClips.toMutableList().apply { this[index] = edited }
        history.record(before)
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
        audioPlayback.seekTo(timelinePositionMs)
        saveProject()
        updateHistoryUi()
    }

    private fun extractAudioFromSelectedVideo() {
        val videoClip = clips.firstOrNull { it.id == selectedClipId } ?: return
        val sourceAsset = assetFor(videoClip) ?: return
        val before = snapshot()
        val existingAsset = audioAssets.firstOrNull { it.uri == sourceAsset.uri }
        val audioAsset = existingAsset ?: AudioAsset(
            id = UUID.randomUUID().toString(),
            uri = sourceAsset.uri,
            displayName = "Extracted · ${sourceAsset.displayName}",
            durationMs = sourceAsset.durationMs
        ).also { audioAssets = audioAssets + it }

        val newClip = AudioClip(
            id = UUID.randomUUID().toString(),
            assetId = audioAsset.id,
            timelineStartMs = timelineIndex.startOf(videoClip.id),
            sourceStartMs = videoClip.sourceStartMs,
            sourceEndMs = videoClip.sourceEndMs,
            volume = 1f,
            muted = false
        )
        audioClips = (audioClips + newClip).sortedBy { it.timelineStartMs }
        selectedAudioClipId = newClip.id
        history.record(before)
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
        requestAudioWaveforms()
        saveProject()
        updateHistoryUi()
    }

    private fun finishAudioGestureEdit() {
        val before = pendingAudioEditSnapshot
        pendingAudioEditSnapshot = null
        audioClips = sanitizeAudioClips(audioClips)
        if (before != null && before != snapshot()) history.record(before)
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
        audioPlayback.seekTo(timelinePositionMs)
        saveProject()
        updateHistoryUi()
    }

    private fun toggleSelectedAudioMute() {
        val id = selectedAudioClipId ?: return
        val index = audioClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        audioClips = audioClips.toMutableList().apply {
            this[index] = this[index].copy(muted = !this[index].muted)
        }
        history.record(before)
        renderTimelineState()
        audioPlayback.seekTo(timelinePositionMs)
        saveProject()
        updateHistoryUi()
    }

    private fun adjustSelectedAudioVolume(delta: Float) {
        val id = selectedAudioClipId ?: return
        val index = audioClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = audioClips[index]
        val next = (current.volume + delta).coerceIn(0f, 1f)
        if (kotlin.math.abs(next - current.volume) < 0.001f) return
        audioClips = audioClips.toMutableList().apply {
            this[index] = current.copy(volume = next)
        }
        history.record(before)
        renderTimelineState()
        audioPlayback.seekTo(timelinePositionMs)
        saveProject()
        updateHistoryUi()
    }

    private fun deleteSelectedAudio() {
        val id = selectedAudioClipId ?: return
        if (audioClips.none { it.id == id }) return
        val before = snapshot()
        audioPlayback.pause()
        audioClips = audioClips.filterNot { it.id == id }
        selectedAudioClipId = audioClips.firstOrNull()?.id
        pruneUnusedAudioAssets()
        history.record(before)
        renderTimelineState()
        audioPlayback.seekTo(timelinePositionMs)
        saveProject()
        updateHistoryUi()
    }

    private fun sanitizeAudioClips(input: List<AudioClip>): List<AudioClip> {
        val byId = audioAssets.associateBy { it.id }
        val projectDuration = clips.sumOf { it.durationMs }.coerceAtLeast(0)
        return input.mapNotNull { clip ->
            val asset = byId[clip.assetId] ?: return@mapNotNull null
            AudioTimelineEditor.normalized(clip, asset.durationMs, projectDuration)
        }.sortedBy { it.timelineStartMs }
    }

    private fun renderAudioState() {
        binding.audioTimeline.setState(
            clips = audioClips,
            labelsByAssetId = audioAssets.associate { it.id to it.displayName },
            assetDurationsById = audioAssets.associate { it.id to it.durationMs },
            waveformsByAssetId = waveformsByAssetId,
            snapPointsMs = videoSnapPoints(),
            selectedClipId = selectedAudioClipId,
            durationMs = timelineIndex.totalDurationMs,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs,
            positionMs = timelinePositionMs
        )
    }

    private fun updateAudioUi() {
        val selected = audioClips.firstOrNull { it.id == selectedAudioClipId }
        val enabled = selected != null
        listOf(
            binding.audioMuteButton,
            binding.audioVolumeDownButton,
            binding.audioVolumeUpButton,
            binding.audioDeleteButton,
            binding.audioSplitButton,
            binding.audioFadeInButton,
            binding.audioFadeOutButton
        ).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }

        if (selected == null) {
            binding.audioSelectionLabel.text = if (audioClips.isEmpty()) "No audio · add music or sound" else "Tap an audio clip to select"
            binding.audioMuteButton.text = "Mute"
            binding.audioSplitButton.isEnabled = false
            binding.audioSplitButton.alpha = 0.38f
            return
        }

        val asset = audioAssets.firstOrNull { it.id == selected.assetId }
        val name = asset?.displayName?.substringBeforeLast('.')?.take(20).orEmpty().ifBlank { "Audio" }
        val volumePercent = (selected.volume * 100).roundToInt()
        val fadeIn = String.format("%.1f", selected.fadeInMs / 1000f)
        val fadeOut = String.format("%.1f", selected.fadeOutMs / 1000f)
        binding.audioSelectionLabel.text = "$name · $volumePercent% · in ${fadeIn}s · out ${fadeOut}s${if (selected.muted) " · muted" else ""}"
        binding.audioMuteButton.text = if (selected.muted) "Unmute" else "Mute"
        val local = timelinePositionMs - selected.timelineStartMs
        val canSplitAudio = local >= MIN_AUDIO_SPLIT_EDGE_MS && selected.durationMs - local >= MIN_AUDIO_SPLIT_EDGE_MS
        binding.audioSplitButton.isEnabled = canSplitAudio
        binding.audioSplitButton.alpha = if (canSplitAudio) 1f else 0.38f
    }

    private fun requestAudioWaveforms() {
        audioAssets.forEach { asset ->
            audioWaveformCache.request(Uri.parse(asset.uri), asset.durationMs) { waveform ->
                if (waveform.isEmpty() || audioAssets.none { it.id == asset.id }) return@request
                waveformsByAssetId = waveformsByAssetId + (asset.id to waveform)
                if (!isFinishing && !isDestroyed) renderAudioState()
            }
        }
    }

    private fun videoSnapPoints(): List<Int> = buildList {
        var cursor = 0
        add(0)
        clips.forEach { clip ->
            cursor += clip.durationMs
            add(cursor)
        }
    }

    private fun pruneUnusedAudioAssets() {
        val used = audioClips.mapTo(mutableSetOf()) { it.assetId }
        audioAssets = audioAssets.filter { it.id in used }
    }

    private fun resolveAudioDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column) ?: "Audio"
            }
        }
        return "Audio"
    }

    private fun pruneUnusedAssets() {
        val used = clips.mapTo(mutableSetOf()) { it.assetId }
        assets = assets.filter { it.id in used }
    }

    private fun saveProject() {
        if (!::project.isInitialized) return
        timelineZoom = binding.timeline.currentZoom
        timelineViewportStartMs = binding.timeline.currentViewportStartMs
        project = project.copy(
            updatedAt = System.currentTimeMillis(),
            assets = assets,
            clips = clips,
            audioAssets = audioAssets,
            audioClips = audioClips,
            canvasSettings = canvasSettings,
            playheadMs = timelinePositionMs,
            selectedClipId = selectedClipId,
            selectedAudioClipId = selectedAudioClipId,
            timelineZoom = timelineZoom,
            timelineViewportStartMs = timelineViewportStartMs
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

    private fun formatDuration(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1_000
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun formatFps(fps: Float): String {
        val rounded = fps.roundToInt()
        return if (kotlin.math.abs(fps - rounded) < 0.05f) rounded.toString() else String.format("%.2f", fps)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "vedito.project_id"
        private const val MAX_ADDED_VIDEOS = 12
        private const val MAX_ADDED_AUDIO = 12
        private const val SCRUB_SEEK_INTERVAL_MS = 45L
        private const val MIN_SPLIT_EDGE_MS = 300
        private const val MIN_AUDIO_SPLIT_EDGE_MS = 150
        private const val END_GUARD_MS = 35
        private const val SEEK_GUARD_MS = 700
        private const val HISTORY_LIMIT = 40
        private const val POSITION_STEP = 0.08f
        private const val CROP_STEP = 0.05f
    }
}
