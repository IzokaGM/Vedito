package com.vedito.app.feature.editor

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTiming
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.model.Project
import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.model.TextStyle
import com.vedito.app.core.model.TextTransform
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.core.overlay.OverlayTimelineEditor
import com.vedito.app.core.timeline.ClipTimeMap
import com.vedito.app.core.timeline.EditorHistory
import com.vedito.app.core.timeline.FrameTimecode
import com.vedito.app.core.timeline.TimelineEditor
import com.vedito.app.core.timeline.TimelineIndex
import com.vedito.app.core.timeline.TimelineMath
import com.vedito.app.core.text.TextTimelineEditor
import com.vedito.app.core.visual.VisualTransformMath
import com.vedito.app.databinding.ActivityEditorBinding
import com.vedito.app.feature.editor.player.PreviewPlayer
import com.vedito.app.feature.editor.overlay.OverlayPreviewController
import com.vedito.app.feature.editor.timeline.ThumbnailExtractor
import com.vedito.app.feature.editor.text.TextPreviewController
import com.vedito.app.feature.editor.text.TextToolbarView
import com.vedito.app.feature.editor.timing.TimingToolbarView
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
    private lateinit var overlayPreview: OverlayPreviewController
    private lateinit var textPreview: TextPreviewController
    private lateinit var project: Project

    private val history = EditorHistory(HISTORY_LIMIT)
    private var timelineIndex = TimelineIndex(emptyList())
    private var assets: List<MediaAsset> = emptyList()
    private var clips: List<Clip> = emptyList()
    private var audioAssets: List<AudioAsset> = emptyList()
    private var audioClips: List<AudioClip> = emptyList()
    private var overlayAssets: List<OverlayAsset> = emptyList()
    private var overlayClips: List<OverlayClip> = emptyList()
    private var textClips: List<TextClip> = emptyList()
    private var canvasSettings = CanvasSettings()
    private var waveformsByAssetId: Map<String, FloatArray> = emptyMap()
    private var selectedClipId: String? = null
    private var selectedAudioClipId: String? = null
    private var selectedOverlayClipId: String? = null
    private var selectedTextClipId: String? = null
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
    private var pendingOverlayEditSnapshot: EditorHistory.Snapshot? = null
    private var pendingTextEditSnapshot: EditorHistory.Snapshot? = null
    private var addingOverlay = false

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

    private val addOverlayPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ADDED_OVERLAYS)
    ) { uris ->
        if (uris.isNotEmpty()) addOverlays(uris.take(MAX_ADDED_OVERLAYS))
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
        overlayAssets = project.overlayAssets
        overlayClips = sanitizeOverlayClips(project.overlayClips)
        textClips = sanitizeTextClips(project.textClips)
        canvasSettings = project.canvasSettings
        selectedClipId = project.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = project.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        selectedOverlayClipId = project.selectedOverlayClipId?.takeIf { id -> overlayClips.any { it.id == id } }
        selectedTextClipId = project.selectedTextClipId?.takeIf { id -> textClips.any { it.id == id } }
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
        binding.addOverlayButton.setOnClickListener {
            if (!addingOverlay) addOverlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        binding.overlayBackButton.setOnClickListener { changeOverlayLayer(-1) }
        binding.overlayFrontButton.setOnClickListener { changeOverlayLayer(1) }
        binding.overlayDeleteButton.setOnClickListener { deleteSelectedOverlay() }
        binding.addTextButton.setOnClickListener { showTextDialog(null) }
        binding.editTextButton.setOnClickListener { selectedTextClipId?.let { id -> textClips.firstOrNull { it.id == id } }?.let(::showTextDialog) }
        binding.textBackButton.setOnClickListener { changeTextLayer(-1) }
        binding.textFrontButton.setOnClickListener { changeTextLayer(1) }
        binding.textDeleteButton.setOnClickListener { deleteSelectedText() }
        binding.textToolbar.onAction = ::handleTextAction
        binding.visualToolbar.onAction = ::handleVisualAction
        binding.timingToolbar.onAction = ::handleTimingAction
        binding.previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCanvasPreviewLayout() }
        binding.overlayPreviewLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::overlayPreview.isInitialized) overlayPreview.render(timelinePositionMs, previewPlayer.isPlaying(), selectedOverlayClipId)
        }
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

        binding.overlayTimeline.onOverlaySelected = { id ->
            selectedOverlayClipId = id
            selectedTextClipId = null
            updateOverlayUi()
            updateTextUi()
            updateVisualToolbar()
            updateTimingToolbar()
            renderOverlayState()
            renderTextState()
            saveProject()
        }
        binding.overlayTimeline.onOverlayEditStart = { id ->
            selectedOverlayClipId = id
            pendingOverlayEditSnapshot = snapshot()
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
        }
        binding.overlayTimeline.onOverlayChanged = { edited, finished ->
            overlayClips = overlayClips.map { if (it.id == edited.id) edited else it }
            selectedOverlayClipId = edited.id
            if (finished) finishOverlayGestureEdit() else renderOverlayState()
        }

        binding.textTimeline.onTextSelected = { id ->
            selectedTextClipId = id
            selectedOverlayClipId = null
            updateTextUi()
            updateOverlayUi()
            updateVisualToolbar()
            updateTimingToolbar()
            renderTextState()
            renderOverlayState()
            saveProject()
        }
        binding.textTimeline.onTextEditStart = { id ->
            selectedTextClipId = id
            selectedOverlayClipId = null
            pendingTextEditSnapshot = snapshot()
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
        }
        binding.textTimeline.onTextChanged = { edited, finished ->
            textClips = textClips.map { if (it.id == edited.id) edited else it }
            selectedTextClipId = edited.id
            if (finished) finishTextGestureEdit() else renderTextState()
        }

        binding.timeline.onClipSelected = { id ->
            selectedClipId = id
            selectedOverlayClipId = null
            selectedTextClipId = null
            updateSelectionUi()
            updateOverlayUi()
            updateTextUi()
            updateVisualToolbar()
            updateTimingToolbar()
            renderOverlayState()
            renderTextState()
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
            renderOverlayState()
            renderTextState()
            if (finished) {
                requestThumbnails()
                saveProject()
            }
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        overlayPreview = OverlayPreviewController(this, binding.overlayPreviewLayer)
        overlayPreview.onOverlaySelected = { id ->
            selectedOverlayClipId = id
            selectedTextClipId = null
            updateOverlayUi()
            updateTextUi()
            updateVisualToolbar()
            updateTimingToolbar()
            renderOverlayState()
            renderTextState()
            saveProject()
        }
        textPreview = TextPreviewController(this, binding.textPreviewLayer)
        textPreview.onTextSelected = { id ->
            selectedTextClipId = id
            selectedOverlayClipId = null
            updateTextUi()
            updateOverlayUi()
            updateVisualToolbar()
            updateTimingToolbar()
            renderTextState()
            renderOverlayState()
            saveProject()
        }
        binding.textPreviewLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::textPreview.isInitialized) textPreview.render(timelinePositionMs, selectedTextClipId)
        }
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
        if (::overlayPreview.isInitialized) overlayPreview.release()
        if (::textPreview.isInitialized) textPreview.release()
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
        if (userScrubbing || clips.isEmpty()) return
        val clipId = playbackClipId ?: return
        val clipIndex = clips.indexOfFirst { it.id == clipId }
        if (clipIndex < 0) return
        val clip = clips[clipIndex]
        val asset = assetFor(clip) ?: return
        if (previewPlayer.currentUri != Uri.parse(asset.uri)) return

        val timelineStart = timelineIndex.startOf(clip.id)
        when (clip.timing.mode) {
            ClipPlaybackMode.FREEZE -> {
                val offset = previewPlayer.virtualTimelineElapsedMs.coerceIn(0, clip.durationMs)
                setTimelinePosition(timelineStart + offset)
                if (offset >= clip.durationMs - END_GUARD_MS || !isPlaying) {
                    advancePlayback(clipIndex)
                } else {
                    audioPlayback.sync(timelinePositionMs, playing = true)
                }
            }
            ClipPlaybackMode.REVERSE -> {
                val offset = ClipTimeMap.timelineOffsetForSourcePosition(clip, positionMs)
                setTimelinePosition(timelineStart + offset)
                if (positionMs <= clip.sourceStartMs + END_GUARD_MS || !isPlaying) {
                    advancePlayback(clipIndex)
                } else {
                    audioPlayback.sync(timelinePositionMs, playing = true)
                }
            }
            ClipPlaybackMode.FORWARD -> {
                if (!isPlaying) return
                if (positionMs >= clip.sourceEndMs - END_GUARD_MS) {
                    advancePlayback(clipIndex)
                    return
                }
                if (positionMs < clip.sourceStartMs - SEEK_GUARD_MS || positionMs > clip.sourceEndMs + SEEK_GUARD_MS) return
                val offset = ClipTimeMap.timelineOffsetForSourcePosition(clip, positionMs)
                setTimelinePosition(timelineStart + offset)
                audioPlayback.sync(timelinePositionMs, playing = true)
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        binding.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = if (isPlaying) "Pause" else "Play"
        if (isPlaying) audioPlayback.playFrom(timelinePositionMs) else audioPlayback.pause()
        if (::overlayPreview.isInitialized) overlayPreview.render(timelinePositionMs, isPlaying, selectedOverlayClipId)
        if (::textPreview.isInitialized) textPreview.render(timelinePositionMs, selectedTextClipId)
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
        showClip(next, ClipTimeMap.sourcePositionAtTimelineOffset(next, 0), play = true)
    }

    private fun seekPreviewToTimeline(positionMs: Int) {
        val location = timelineIndex.locate(positionMs) ?: return
        showClip(location.clip, location.sourcePositionMs, play = false)
    }

    private fun showClip(clip: Clip, sourcePositionMs: Int, play: Boolean) {
        val asset = assetFor(clip) ?: return
        binding.playerError.visibility = View.GONE
        binding.playPauseButton.isEnabled = true
        val timelineOffset = if (clip.timing.mode == ClipPlaybackMode.FREEZE) {
            (timelinePositionMs - timelineIndex.startOf(clip.id)).coerceIn(0, clip.durationMs)
        } else {
            ClipTimeMap.timelineOffsetForSourcePosition(clip, sourcePositionMs)
        }
        previewPlayer.setVisualTransform(clip.transform)
        previewPlayer.configureTiming(
            mode = clip.timing.mode,
            speed = clip.timing.speed,
            sourceStartMs = clip.sourceStartMs,
            sourceEndMs = clip.sourceEndMs,
            freezeDurationMs = clip.timing.freezeDurationMs,
            startTimelineOffsetMs = timelineOffset
        )
        applyCanvasPreviewLayout(clip)
        previewPlayer.load(Uri.parse(asset.uri), sourcePositionMs, play)
    }

    private fun handleTimingAction(action: TimingToolbarView.Action) {
        when (action) {
            TimingToolbarView.Action.SPEED_DOWN -> adjustSelectedSpeed(-1)
            TimingToolbarView.Action.SPEED_UP -> adjustSelectedSpeed(1)
            TimingToolbarView.Action.FREEZE -> insertFreezeAtPlayhead()
            TimingToolbarView.Action.REVERSE -> toggleSelectedReverse()
        }
    }

    private fun adjustSelectedSpeed(direction: Int) {
        val id = selectedClipId ?: return
        val selected = clips.firstOrNull { it.id == id } ?: return
        if (selected.timing.mode == ClipPlaybackMode.FREEZE) return
        val presets = SPEED_PRESETS
        val currentIndex = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - selected.timing.speed) } ?: 2
        val nextIndex = (currentIndex + direction).coerceIn(0, presets.lastIndex)
        val nextSpeed = presets[nextIndex]
        if (kotlin.math.abs(nextSpeed - selected.timing.speed) < 0.001f) return

        val before = snapshot()
        val oldStart = timelineIndex.startOf(id)
        val oldDuration = selected.durationMs.coerceAtLeast(1)
        val local = (timelinePositionMs - oldStart).coerceIn(0, oldDuration)
        val progress = local.toFloat() / oldDuration
        clips = TimelineEditor.setSpeed(clips, id, nextSpeed)
        refreshTimelineIndex()
        val updated = clips.firstOrNull { it.id == id } ?: return
        timelinePositionMs = (timelineIndex.startOf(id) + (updated.durationMs * progress).roundToInt())
            .coerceIn(0, timelineIndex.totalDurationMs)
        history.record(before)
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
        updateHistoryUi()
    }

    private fun toggleSelectedReverse() {
        val id = selectedClipId ?: return
        val selected = clips.firstOrNull { it.id == id } ?: return
        if (selected.timing.mode == ClipPlaybackMode.FREEZE) return
        val before = snapshot()
        val oldStart = timelineIndex.startOf(id)
        val oldLocal = (timelinePositionMs - oldStart).coerceIn(0, selected.durationMs)
        clips = TimelineEditor.toggleReverse(clips, id)
        refreshTimelineIndex()
        timelinePositionMs = (timelineIndex.startOf(id) + oldLocal).coerceIn(0, timelineIndex.totalDurationMs)
        history.record(before)
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
        updateHistoryUi()
    }

    private fun insertFreezeAtPlayhead() {
        val before = snapshot()
        val result = TimelineEditor.insertFreeze(clips, timelinePositionMs, ClipTiming.DEFAULT_FREEZE_DURATION_MS) ?: return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        clips = result.clips
        selectedClipId = result.selectedClipId
        timelinePositionMs = result.playheadMs
        history.record(before)
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
        requestThumbnails()
        saveProject()
        updateHistoryUi()
    }

    private fun handleTextAction(action: TextToolbarView.Action) {
        when (action) {
            TextToolbarView.Action.SCALE_DOWN -> mutateSelectedText { it.copy(transform = it.transform.copy(scale = it.transform.scale - 0.1f)) }
            TextToolbarView.Action.SCALE_UP -> mutateSelectedText { it.copy(transform = it.transform.copy(scale = it.transform.scale + 0.1f)) }
            TextToolbarView.Action.MOVE_LEFT -> mutateSelectedText { it.copy(transform = it.transform.copy(positionX = it.transform.positionX - TEXT_POSITION_STEP)) }
            TextToolbarView.Action.MOVE_RIGHT -> mutateSelectedText { it.copy(transform = it.transform.copy(positionX = it.transform.positionX + TEXT_POSITION_STEP)) }
            TextToolbarView.Action.MOVE_UP -> mutateSelectedText { it.copy(transform = it.transform.copy(positionY = it.transform.positionY - TEXT_POSITION_STEP)) }
            TextToolbarView.Action.MOVE_DOWN -> mutateSelectedText { it.copy(transform = it.transform.copy(positionY = it.transform.positionY + TEXT_POSITION_STEP)) }
            TextToolbarView.Action.ROTATE -> mutateSelectedText { it.copy(transform = it.transform.copy(rotationDegrees = it.transform.rotationDegrees + 15f)) }
            TextToolbarView.Action.OPACITY -> mutateSelectedText {
                val next = when {
                    it.transform.opacity > 0.76f -> 0.75f
                    it.transform.opacity > 0.51f -> 0.50f
                    it.transform.opacity > 0.26f -> 0.25f
                    else -> 1f
                }
                it.copy(transform = it.transform.copy(opacity = next))
            }
            TextToolbarView.Action.FONT_DOWN -> mutateSelectedText { it.copy(style = it.style.copy(fontSizeSp = it.style.fontSizeSp - 4f)) }
            TextToolbarView.Action.FONT_UP -> mutateSelectedText { it.copy(style = it.style.copy(fontSizeSp = it.style.fontSizeSp + 4f)) }
            TextToolbarView.Action.COLOR -> mutateSelectedText {
                val current = TEXT_COLORS.indexOf(it.style.textColorArgb).takeIf { index -> index >= 0 } ?: 0
                it.copy(style = it.style.copy(textColorArgb = TEXT_COLORS[(current + 1) % TEXT_COLORS.size]))
            }
            TextToolbarView.Action.BACKGROUND -> mutateSelectedText {
                val current = TEXT_BACKGROUNDS.indexOf(it.style.backgroundColorArgb).takeIf { index -> index >= 0 } ?: 0
                it.copy(style = it.style.copy(backgroundColorArgb = TEXT_BACKGROUNDS[(current + 1) % TEXT_BACKGROUNDS.size]))
            }
            TextToolbarView.Action.BOLD -> mutateSelectedText { it.copy(style = it.style.copy(bold = !it.style.bold)) }
            TextToolbarView.Action.ALIGN -> mutateSelectedText {
                val values = TextAlignment.values()
                val current = values.indexOf(it.style.alignment).coerceAtLeast(0)
                it.copy(style = it.style.copy(alignment = values[(current + 1) % values.size]))
            }
            TextToolbarView.Action.RESET_TRANSFORM -> mutateSelectedText { it.copy(transform = TextTransform()) }
        }
    }

    private fun mutateSelectedText(change: (TextClip) -> TextClip) {
        val id = selectedTextClipId ?: return
        val index = textClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = textClips[index]
        val changed = change(current)
        val normalized = changed.copy(
            style = TextTimelineEditor.normalizeStyle(changed.style),
            transform = TextTimelineEditor.normalizeTransform(changed.transform)
        )
        if (normalized == current) return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        textClips = textClips.toMutableList().apply { this[index] = normalized }
        history.record(before)
        renderTextState()
        updateTextUi()
        updateVisualToolbar()
        updateTimingToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun handleVisualAction(action: TransformToolbarView.Action) {
        when (action) {
            TransformToolbarView.Action.SCALE_DOWN -> mutateActiveTransform { it.copy(scale = it.scale - 0.1f) }
            TransformToolbarView.Action.SCALE_UP -> mutateActiveTransform { it.copy(scale = it.scale + 0.1f) }
            TransformToolbarView.Action.MOVE_LEFT -> mutateActiveTransform { it.copy(positionX = it.positionX - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_RIGHT -> mutateActiveTransform { it.copy(positionX = it.positionX + POSITION_STEP) }
            TransformToolbarView.Action.MOVE_UP -> mutateActiveTransform { it.copy(positionY = it.positionY - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_DOWN -> mutateActiveTransform { it.copy(positionY = it.positionY + POSITION_STEP) }
            TransformToolbarView.Action.ROTATE_90 -> mutateActiveTransform { it.copy(rotationDegrees = it.rotationDegrees + 90f) }
            TransformToolbarView.Action.FLIP_HORIZONTAL -> mutateActiveTransform { it.copy(flipHorizontal = !it.flipHorizontal) }
            TransformToolbarView.Action.FLIP_VERTICAL -> mutateActiveTransform { it.copy(flipVertical = !it.flipVertical) }
            TransformToolbarView.Action.OPACITY_CYCLE -> mutateActiveTransform {
                val next = when {
                    it.opacity > 0.76f -> 0.75f
                    it.opacity > 0.51f -> 0.50f
                    it.opacity > 0.26f -> 0.25f
                    else -> 1f
                }
                it.copy(opacity = next)
            }
            TransformToolbarView.Action.FIT_TOGGLE -> mutateActiveTransform {
                it.copy(fitMode = if (it.fitMode == ClipFitMode.FIT) ClipFitMode.FILL else ClipFitMode.FIT)
            }
            TransformToolbarView.Action.CROP_LEFT -> mutateActiveTransform { it.copy(cropLeft = nextCropEdge(it.cropLeft)) }
            TransformToolbarView.Action.CROP_RIGHT -> mutateActiveTransform { it.copy(cropRight = nextCropEdge(it.cropRight)) }
            TransformToolbarView.Action.CROP_TOP -> mutateActiveTransform { it.copy(cropTop = nextCropEdge(it.cropTop)) }
            TransformToolbarView.Action.CROP_BOTTOM -> mutateActiveTransform { it.copy(cropBottom = nextCropEdge(it.cropBottom)) }
            TransformToolbarView.Action.CROP_RESET -> mutateActiveTransform {
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
            TransformToolbarView.Action.RESET_TRANSFORM -> mutateActiveTransform { if (selectedOverlayClipId != null) ClipTransform(scale = 0.45f) else ClipTransform() }
        }
    }

    private fun mutateActiveTransform(change: (ClipTransform) -> ClipTransform) {
        val overlayId = selectedOverlayClipId
        if (overlayId != null) {
            val index = overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return
            val before = snapshot()
            val current = overlayClips[index]
            val nextTransform = VisualTransformMath.normalize(change(current.transform))
            if (nextTransform == current.transform) return
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
            overlayClips = overlayClips.toMutableList().apply { this[index] = current.copy(transform = nextTransform) }
            history.record(before)
            renderOverlayState()
            updateOverlayUi()
            updateVisualToolbar()
            saveProject()
            updateHistoryUi()
            return
        }

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
        val transform = if (selectedTextClipId != null) null else {
            selectedOverlayClipId?.let { id -> overlayClips.firstOrNull { it.id == id }?.transform }
                ?: clips.firstOrNull { it.id == selectedClipId }?.transform
        }
        binding.visualToolbar.setState(transform, canvasSettings)
    }

    private fun updateTimingToolbar() {
        binding.timingToolbar.setState(if (selectedTextClipId == null) clips.firstOrNull { it.id == selectedClipId } else null)
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
                this[index] = ClipTimeMap.normalizeTiming(
                    original.copy(
                        assetId = replacementAsset.id,
                        sourceStartMs = range.first,
                        sourceEndMs = range.second
                    )
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
        val wantedDuration = original.sourceDurationMs.coerceAtLeast(1)
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
        pendingOverlayEditSnapshot = null
        pendingTextEditSnapshot = null
        val target = history.undo(snapshot()) ?: return
        applySnapshot(target)
    }

    private fun redoEdit() {
        previewPlayer.pause()
        playbackClipId = null
        pendingTrimSnapshot = null
        pendingReorderSnapshot = null
        pendingAudioEditSnapshot = null
        pendingOverlayEditSnapshot = null
        pendingTextEditSnapshot = null
        val target = history.redo(snapshot()) ?: return
        applySnapshot(target)
    }

    private fun applySnapshot(snapshot: EditorHistory.Snapshot) {
        assets = snapshot.assets
        clips = TimelineMath.sanitized(snapshot.clips, assets)
        audioAssets = snapshot.audioAssets
        audioClips = sanitizeAudioClips(snapshot.audioClips)
        overlayAssets = snapshot.overlayAssets
        overlayClips = sanitizeOverlayClips(snapshot.overlayClips)
        textClips = sanitizeTextClips(snapshot.textClips)
        canvasSettings = snapshot.canvasSettings
        selectedClipId = snapshot.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = snapshot.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        selectedOverlayClipId = snapshot.selectedOverlayClipId?.takeIf { id -> overlayClips.any { it.id == id } }
        selectedTextClipId = snapshot.selectedTextClipId?.takeIf { id -> textClips.any { it.id == id } }
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
        overlayAssets = overlayAssets.toList(),
        overlayClips = overlayClips.toList(),
        textClips = textClips.toList(),
        canvasSettings = canvasSettings,
        selectedClipId = selectedClipId,
        selectedAudioClipId = selectedAudioClipId,
        selectedOverlayClipId = selectedOverlayClipId,
        selectedTextClipId = selectedTextClipId,
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
        updateTimingToolbar()
        applyCanvasPreviewLayout()
        audioClips = sanitizeAudioClips(audioClips)
        if (selectedAudioClipId != null && audioClips.none { it.id == selectedAudioClipId }) selectedAudioClipId = audioClips.firstOrNull()?.id
        pruneUnusedAudioAssets()
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderAudioState()
        updateAudioUi()
        overlayClips = sanitizeOverlayClips(overlayClips)
        if (selectedOverlayClipId != null && overlayClips.none { it.id == selectedOverlayClipId }) selectedOverlayClipId = null
        pruneUnusedOverlayAssets()
        renderOverlayState()
        updateOverlayUi()
        textClips = sanitizeTextClips(textClips)
        if (selectedTextClipId != null && textClips.none { it.id == selectedTextClipId }) selectedTextClipId = null
        renderTextState()
        updateTextUi()
        updateVisualToolbar()
        updateTimingToolbar()
    }

    private fun setTimelinePosition(positionMs: Int) {
        timelinePositionMs = positionMs.coerceIn(0, timelineIndex.totalDurationMs)
        binding.timeline.positionMs = timelinePositionMs
        timelineViewportStartMs = binding.timeline.currentViewportStartMs
        updateTimecodeUi()
        updateSelectionUi()
        updateAudioUi()
        binding.audioTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
        binding.overlayTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
        binding.textTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
        if (::textPreview.isInitialized) textPreview.render(timelinePositionMs, selectedTextClipId)
        if (::overlayPreview.isInitialized) overlayPreview.render(timelinePositionMs, previewPlayer.isPlaying(), selectedOverlayClipId)
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
        val canExtractAudio = selected != null && selected.timing.mode == ClipPlaybackMode.FORWARD && kotlin.math.abs(selected.timing.speed - 1f) < 0.001f
        binding.extractAudioButton.isEnabled = canExtractAudio
        binding.extractAudioButton.alpha = if (canExtractAudio) 1f else 0.42f

        if (selected == null) {
            binding.selectionLabel.text = "No clip selected"
        } else {
            val number = clips.indexOfFirst { it.id == selected.id } + 1
            val asset = assetFor(selected)
            val source = asset?.displayName?.substringBeforeLast('.')?.take(18).orEmpty()
            val transform = selected.transform
            val visual = "${(transform.scale * 100f).roundToInt()}% · ${transform.rotationDegrees.roundToInt()}° · ${(transform.opacity * 100f).roundToInt()}%"
            val timing = when (selected.timing.mode) {
                ClipPlaybackMode.FREEZE -> "Freeze ${formatDuration(selected.durationMs)}"
                ClipPlaybackMode.REVERSE -> "Reverse ${formatSpeed(selected.timing.speed)}"
                ClipPlaybackMode.FORWARD -> formatSpeed(selected.timing.speed)
            }
            binding.selectionLabel.text = if (source.isBlank()) {
                "Clip $number · $timing · $visual"
            } else {
                "Clip $number · $source · $timing · $visual"
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
        if (videoClip.timing.mode != ClipPlaybackMode.FORWARD || kotlin.math.abs(videoClip.timing.speed - 1f) >= 0.001f) return
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

    private fun addOverlays(uris: List<Uri>) {
        val projectDuration = timelineIndex.totalDurationMs
        if (addingOverlay || projectDuration < OverlayTimelineEditor.MIN_DURATION_MS) return
        addingOverlay = true
        updateOverlayUi()

        val unique = uris.distinctBy(Uri::toString)
        unique.forEach(::persistReadAccess)
        val names = unique.associate { it.toString() to resolveOverlayDisplayName(it) }
        val videoUris = unique.filter { contentResolver.getType(it)?.startsWith("video/") == true }
        val before = snapshot()

        fun commit(results: List<MediaProbe.Result>) {
            val resultByUri = results.associateBy { it.uri.toString() }
            val updatedAssets = overlayAssets.toMutableList()
            val byUri = updatedAssets.associateBy { it.uri }.toMutableMap()
            val additions = mutableListOf<OverlayClip>()
            val start = timelinePositionMs.coerceIn(0, (projectDuration - OverlayTimelineEditor.MIN_DURATION_MS).coerceAtLeast(0))
            val available = (projectDuration - start).coerceAtLeast(0)
            var nextZ = (overlayClips.maxOfOrNull { it.zIndex } ?: -1) + 1

            unique.forEach { uri ->
                val key = uri.toString()
                val probe = resultByUri[key]
                val isVideo = probe != null || contentResolver.getType(uri)?.startsWith("video/") == true
                val type = if (isVideo) OverlayMediaType.VIDEO else OverlayMediaType.IMAGE
                val existing = byUri[key]
                val asset = if (existing == null) {
                    OverlayAsset(
                        id = UUID.randomUUID().toString(),
                        uri = key,
                        displayName = names[key] ?: "Overlay",
                        type = type,
                        durationMs = probe?.durationMs ?: 0,
                        width = probe?.width ?: 0,
                        height = probe?.height ?: 0
                    ).also {
                        updatedAssets += it
                        byUri[key] = it
                    }
                } else {
                    val refreshed = existing.copy(
                        type = type,
                        durationMs = probe?.durationMs ?: existing.durationMs,
                        width = probe?.width ?: existing.width,
                        height = probe?.height ?: existing.height
                    )
                    val idx = updatedAssets.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) updatedAssets[idx] = refreshed
                    byUri[key] = refreshed
                    refreshed
                }

                val requestedDuration = if (asset.type == OverlayMediaType.VIDEO) asset.durationMs else DEFAULT_IMAGE_OVERLAY_MS
                val duration = minOf(requestedDuration, available)
                if (duration < OverlayTimelineEditor.MIN_DURATION_MS) return@forEach
                additions += OverlayClip(
                    id = UUID.randomUUID().toString(),
                    assetId = asset.id,
                    timelineStartMs = start,
                    durationMs = duration,
                    sourceStartMs = 0,
                    zIndex = nextZ++,
                    transform = ClipTransform(scale = 0.45f, fitMode = ClipFitMode.FIT)
                )
            }

            addingOverlay = false
            if (additions.isEmpty()) {
                updateOverlayUi()
                return
            }
            overlayAssets = updatedAssets
            overlayClips = sanitizeOverlayClips(overlayClips + additions)
            pruneUnusedOverlayAssets()
            selectedOverlayClipId = additions.last().id
            selectedTextClipId = null
            history.record(before)
            renderOverlayState()
            renderTextState()
            updateOverlayUi()
            updateTextUi()
            updateVisualToolbar()
            updateTimingToolbar()
            saveProject()
            updateHistoryUi()
        }

        if (videoUris.isEmpty()) commit(emptyList()) else mediaProbe.probe(videoUris, ::commit)
    }

    private fun sanitizeOverlayClips(input: List<OverlayClip>): List<OverlayClip> {
        val byId = overlayAssets.associateBy { it.id }
        val projectDuration = clips.sumOf { it.durationMs }.coerceAtLeast(0)
        return input.mapNotNull { clip ->
            val asset = byId[clip.assetId] ?: return@mapNotNull null
            OverlayTimelineEditor.normalized(clip, asset, projectDuration)
        }.sortedWith(compareBy<OverlayClip> { it.zIndex }.thenBy { it.timelineStartMs })
            .mapIndexed { index, clip -> if (clip.zIndex == index) clip else clip.copy(zIndex = index) }
    }

    private fun renderOverlayState() {
        binding.overlayTimeline.setState(
            clips = overlayClips,
            assets = overlayAssets,
            selectedClipId = selectedOverlayClipId,
            durationMs = timelineIndex.totalDurationMs,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs,
            positionMs = timelinePositionMs
        )
        if (::overlayPreview.isInitialized) {
            overlayPreview.setTimeline(overlayAssets, overlayClips)
            overlayPreview.render(timelinePositionMs, previewPlayer.isPlaying(), selectedOverlayClipId)
        }
    }

    private fun updateOverlayUi() {
        val selected = overlayClips.firstOrNull { it.id == selectedOverlayClipId }
        val enabled = selected != null
        listOf(binding.overlayBackButton, binding.overlayFrontButton, binding.overlayDeleteButton).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }
        binding.addOverlayButton.isEnabled = !addingOverlay
        binding.addOverlayButton.alpha = if (addingOverlay) 0.5f else 1f
        binding.addOverlayButton.text = if (addingOverlay) "Adding…" else "Add overlay"

        if (selected == null) {
            binding.overlaySelectionLabel.text = if (overlayClips.isEmpty()) "No overlay · add image or video PIP" else "Tap an overlay layer to select"
            return
        }
        val asset = overlayAssets.firstOrNull { it.id == selected.assetId }
        val name = asset?.displayName?.substringBeforeLast('.')?.take(18).orEmpty().ifBlank { "Overlay" }
        val kind = if (asset?.type == OverlayMediaType.VIDEO) "Video" else "Image"
        binding.overlaySelectionLabel.text = "$kind · $name · L${selected.zIndex + 1} · ${(selected.transform.scale * 100).roundToInt()}%"
    }

    private fun finishOverlayGestureEdit() {
        val before = pendingOverlayEditSnapshot
        pendingOverlayEditSnapshot = null
        overlayClips = sanitizeOverlayClips(overlayClips)
        if (before != null && before != snapshot()) history.record(before)
        renderOverlayState()
        updateOverlayUi()
        updateVisualToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun changeOverlayLayer(direction: Int) {
        val id = selectedOverlayClipId ?: return
        val before = snapshot()
        val updated = if (direction > 0) OverlayTimelineEditor.raise(overlayClips, id) else OverlayTimelineEditor.lower(overlayClips, id)
        if (updated == overlayClips) return
        overlayClips = updated
        history.record(before)
        renderOverlayState()
        updateOverlayUi()
        saveProject()
        updateHistoryUi()
    }

    private fun deleteSelectedOverlay() {
        val id = selectedOverlayClipId ?: return
        if (overlayClips.none { it.id == id }) return
        val before = snapshot()
        overlayClips = overlayClips.filterNot { it.id == id }
        selectedOverlayClipId = overlayClips.maxByOrNull { it.zIndex }?.id
        pruneUnusedOverlayAssets()
        history.record(before)
        renderOverlayState()
        updateOverlayUi()
        updateVisualToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun pruneUnusedOverlayAssets() {
        val used = overlayClips.mapTo(mutableSetOf()) { it.assetId }
        overlayAssets = overlayAssets.filter { it.id in used }
    }

    private fun resolveOverlayDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column) ?: "Overlay"
            }
        }
        return "Overlay"
    }

    private fun showTextDialog(existing: TextClip?) {
        if (timelineIndex.totalDurationMs < TextTimelineEditor.MIN_DURATION_MS) {
            binding.textSelectionLabel.text = "Add a video clip before adding text"
            return
        }
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty())
            hint = "Type text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
            setPadding((20 * resources.displayMetrics.density).roundToInt(), (12 * resources.displayMetrics.density).roundToInt(), (20 * resources.displayMetrics.density).roundToInt(), (12 * resources.displayMetrics.density).roundToInt())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add text" else "Edit text")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                upsertTextClip(existing, input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun upsertTextClip(existing: TextClip?, rawText: String) {
        val value = rawText.trim().take(TextTimelineEditor.MAX_TEXT_LENGTH)
        if (value.isBlank()) return
        val total = timelineIndex.totalDurationMs
        if (total < TextTimelineEditor.MIN_DURATION_MS) return
        val before = snapshot()
        if (existing == null) {
            val latestStart = (total - TextTimelineEditor.MIN_DURATION_MS).coerceAtLeast(0)
            val start = timelinePositionMs.coerceIn(0, latestStart)
            val duration = minOf(DEFAULT_TEXT_DURATION_MS, total - start).coerceAtLeast(TextTimelineEditor.MIN_DURATION_MS)
            val clip = TextClip(
                id = UUID.randomUUID().toString(),
                text = value,
                timelineStartMs = start,
                durationMs = duration,
                zIndex = (textClips.maxOfOrNull { it.zIndex } ?: -1) + 1,
                style = TextStyle(),
                transform = TextTransform()
            )
            textClips = sanitizeTextClips(textClips + clip)
            selectedTextClipId = clip.id
            selectedOverlayClipId = null
        } else {
            val index = textClips.indexOfFirst { it.id == existing.id }
            if (index < 0) return
            val updated = textClips[index].copy(text = value)
            if (updated == textClips[index]) return
            textClips = textClips.toMutableList().apply { this[index] = updated }
            selectedTextClipId = updated.id
            selectedOverlayClipId = null
        }
        if (before != snapshot()) history.record(before)
        renderTextState()
        renderOverlayState()
        updateTextUi()
        updateOverlayUi()
        updateVisualToolbar()
        updateTimingToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun sanitizeTextClips(input: List<TextClip>): List<TextClip> {
        val total = clips.sumOf { it.durationMs }.coerceAtLeast(0)
        return input.mapNotNull { TextTimelineEditor.normalized(it, total) }
            .sortedWith(compareBy<TextClip> { it.zIndex }.thenBy { it.timelineStartMs }.thenBy { it.id })
            .mapIndexed { index, clip -> if (clip.zIndex == index) clip else clip.copy(zIndex = index) }
    }

    private fun renderTextState() {
        binding.textTimeline.setState(
            clips = textClips,
            selectedClipId = selectedTextClipId,
            durationMs = timelineIndex.totalDurationMs,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs,
            positionMs = timelinePositionMs
        )
        if (::textPreview.isInitialized) {
            textPreview.setTimeline(textClips)
            textPreview.render(timelinePositionMs, selectedTextClipId)
        }
    }

    private fun updateTextUi() {
        val selected = textClips.firstOrNull { it.id == selectedTextClipId }
        val enabled = selected != null
        listOf(binding.editTextButton, binding.textBackButton, binding.textFrontButton, binding.textDeleteButton).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }
        binding.textToolbar.setState(selected)
        if (selected == null) {
            binding.textSelectionLabel.text = if (textClips.isEmpty()) "No text · add a title or caption" else "Tap a text layer to select"
            return
        }
        val preview = selected.text.replace('\n', ' ').take(26)
        binding.textSelectionLabel.text = "Text · $preview · T${selected.zIndex + 1} · ${selected.style.fontSizeSp.roundToInt()}sp"
    }

    private fun finishTextGestureEdit() {
        val before = pendingTextEditSnapshot
        pendingTextEditSnapshot = null
        textClips = sanitizeTextClips(textClips)
        if (before != null && before != snapshot()) history.record(before)
        renderTextState()
        updateTextUi()
        saveProject()
        updateHistoryUi()
    }

    private fun changeTextLayer(direction: Int) {
        val id = selectedTextClipId ?: return
        val before = snapshot()
        val updated = if (direction > 0) TextTimelineEditor.raise(textClips, id) else TextTimelineEditor.lower(textClips, id)
        if (updated == textClips) return
        textClips = sanitizeTextClips(updated)
        history.record(before)
        renderTextState()
        updateTextUi()
        saveProject()
        updateHistoryUi()
    }

    private fun deleteSelectedText() {
        val id = selectedTextClipId ?: return
        if (textClips.none { it.id == id }) return
        val before = snapshot()
        textClips = textClips.filterNot { it.id == id }
        textClips = sanitizeTextClips(textClips)
        selectedTextClipId = textClips.maxByOrNull { it.zIndex }?.id
        history.record(before)
        renderTextState()
        updateTextUi()
        updateVisualToolbar()
        updateTimingToolbar()
        saveProject()
        updateHistoryUi()
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
            overlayAssets = overlayAssets,
            overlayClips = overlayClips,
            textClips = textClips,
            canvasSettings = canvasSettings,
            playheadMs = timelinePositionMs,
            selectedClipId = selectedClipId,
            selectedAudioClipId = selectedAudioClipId,
            selectedOverlayClipId = selectedOverlayClipId,
            selectedTextClipId = selectedTextClipId,
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

    private fun formatSpeed(speed: Float): String = if (kotlin.math.abs(speed - speed.toInt()) < 0.001f) {
        "${speed.toInt()}.0×"
    } else {
        String.format("%.2f×", speed).trimEnd('0')
    }

    private fun formatFps(fps: Float): String {
        val rounded = fps.roundToInt()
        return if (kotlin.math.abs(fps - rounded) < 0.05f) rounded.toString() else String.format("%.2f", fps)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "vedito.project_id"
        private const val MAX_ADDED_VIDEOS = 12
        private const val MAX_ADDED_AUDIO = 12
        private const val MAX_ADDED_OVERLAYS = 8
        private const val DEFAULT_IMAGE_OVERLAY_MS = 3_000
        private const val DEFAULT_TEXT_DURATION_MS = 3_000
        private const val TEXT_POSITION_STEP = 0.08f
        private const val SCRUB_SEEK_INTERVAL_MS = 45L
        private const val MIN_SPLIT_EDGE_MS = 300
        private const val MIN_AUDIO_SPLIT_EDGE_MS = 150
        private const val END_GUARD_MS = 35
        private const val SEEK_GUARD_MS = 700
        private const val HISTORY_LIMIT = 40
        private const val POSITION_STEP = 0.08f
        private const val CROP_STEP = 0.05f
        private val SPEED_PRESETS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val TEXT_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF17131F.toInt(),
            0xFFFFE66D.toInt(),
            0xFF7DEBFF.toInt(),
            0xFFFF8EDB.toInt()
        )
        private val TEXT_BACKGROUNDS = intArrayOf(
            0x00000000,
            0xB3000000.toInt(),
            0xD9FFFFFF.toInt(),
            0xCC241E45.toInt()
        )
    }
}
