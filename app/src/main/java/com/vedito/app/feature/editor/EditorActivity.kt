package com.vedito.app.feature.editor

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vedito.app.R
import com.vedito.app.core.audio.AudioPlaybackEngine
import com.vedito.app.core.audio.AudioProbe
import com.vedito.app.core.audio.AudioTimelineEditor
import com.vedito.app.core.audio.AudioWaveformCache
import com.vedito.app.core.color.ColorGradeEngine
import com.vedito.app.core.compositor.FrameCompositionBuilder
import com.vedito.app.core.media.MediaProbe
import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.CanvasAspect
import com.vedito.app.core.model.CanvasBackground
import com.vedito.app.core.model.CanvasSettings
import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.ColorGradeSpec
import com.vedito.app.core.model.CaptionPreset
import com.vedito.app.core.model.CaptionSegment
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTiming
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.KeyframeEasing
import com.vedito.app.core.model.TransformKeyframeSet
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.TransitionSpec
import com.vedito.app.core.model.VideoEffectKind
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.MaskSpec
import com.vedito.app.core.model.MotionTrackSpec
import com.vedito.app.core.model.StabilizationSpec
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.model.Project
import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextAnimationKind
import com.vedito.app.core.model.TextFontFamily
import com.vedito.app.core.model.TextPreset
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.model.TextTransform
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.core.caption.CaptionTimelineEditor
import com.vedito.app.core.caption.SrtCodec
import com.vedito.app.core.effect.EffectComposition
import com.vedito.app.core.effect.EffectTimelineEditor
import com.vedito.app.core.export.ExportPlanner
import com.vedito.app.core.export.ExportPreset
import com.vedito.app.core.export.ExportSettings
import com.vedito.app.core.export.ExportSupport
import com.vedito.app.core.export.ExportVideoCodec
import com.vedito.app.core.keyframe.KeyframeEngine
import com.vedito.app.core.overlay.OverlayTimelineEditor
import com.vedito.app.core.tracking.MotionTrackingEngine
import com.vedito.app.core.timeline.ClipTimeMap
import com.vedito.app.core.timeline.EditorHistory
import com.vedito.app.core.timeline.FrameTimecode
import com.vedito.app.core.timeline.TimelineEditor
import com.vedito.app.core.timeline.TimelineIndex
import com.vedito.app.core.timeline.TimelineMath
import com.vedito.app.core.text.TextMotion
import com.vedito.app.core.text.TextKeyframeEngine
import com.vedito.app.core.text.TextPresetCatalog
import com.vedito.app.core.text.TextTimelineEditor
import com.vedito.app.core.visual.MaskChromaComposition
import com.vedito.app.core.visual.VisualTransformMath
import com.vedito.app.databinding.ActivityEditorBinding
import com.vedito.app.feature.editor.player.PreviewPlayer
import com.vedito.app.feature.export.ExportCapabilityProbe
import com.vedito.app.feature.export.ExportForegroundService
import com.vedito.app.feature.export.ExportTaskStore
import com.vedito.app.feature.export.VideoExportEngine
import com.vedito.app.feature.export.ui.ExportProgressRingView
import com.vedito.app.feature.editor.caption.CaptionPreviewController
import com.vedito.app.feature.editor.color.ColorGradeToolbarView
import com.vedito.app.feature.editor.caption.CaptionToolbarView
import com.vedito.app.feature.editor.effect.EffectToolbarView
import com.vedito.app.feature.editor.overlay.OverlayPreviewController
import com.vedito.app.feature.editor.timeline.ThumbnailExtractor
import com.vedito.app.feature.editor.text.TextPreviewController
import com.vedito.app.feature.editor.text.TextToolbarView
import com.vedito.app.feature.editor.timing.TimingToolbarView
import com.vedito.app.feature.editor.tracking.TrackingStabilizationToolbarView
import com.vedito.app.feature.editor.visual.MaskChromaToolbarView
import com.vedito.app.feature.editor.visual.TransformToolbarView
import com.vedito.app.ui.applySystemBarInsets
import com.vedito.app.ui.configureVeditoSystemBars
import java.util.UUID
import kotlin.math.roundToInt

class EditorActivity : ComponentActivity(), PreviewPlayer.Listener {
    private enum class EditorToolMode {
        EDIT,
        SPEED,
        TEXT,
        AUDIO,
        EFFECTS,
        COLOR,
        LAYERS,
        CAPTIONS
    }
    private enum class EditSubtool {
        CLIP,
        TRANSFORM,
        MASK,
        TRACK
    }
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
    private lateinit var captionPreview: CaptionPreviewController
    private lateinit var exportEngine: VideoExportEngine
    private lateinit var exportTaskStore: ExportTaskStore
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
    private var captionSegments: List<CaptionSegment> = emptyList()
    private var effectClips: List<EffectClip> = emptyList()
    private var canvasSettings = CanvasSettings()
    private var waveformsByAssetId: Map<String, FloatArray> = emptyMap()
    private var selectedClipId: String? = null
    private var selectedAudioClipId: String? = null
    private var selectedOverlayClipId: String? = null
    private var selectedTextClipId: String? = null
    private var selectedCaptionSegmentId: String? = null
    private var selectedEffectClipId: String? = null
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
    private var pendingCaptionEditSnapshot: EditorHistory.Snapshot? = null
    private var pendingEffectEditSnapshot: EditorHistory.Snapshot? = null
    private var addingOverlay = false
    private var pendingExportSettings: ExportSettings? = null
    private var exportDialog: AlertDialog? = null
    private var exportProgressLabel: TextView? = null
    private var exportProgressPercent: TextView? = null
    private var exportProgressElapsed: TextView? = null
    private var exportProgressRing: ExportProgressRingView? = null
    private var exportProgressHiddenByUser = false
    private var exportReceiverRegistered = false
    private var lastHandledExportTerminalAt = 0L
    private var editorToolMode = EditorToolMode.EDIT
    private var editSubtool = EditSubtool.CLIP
    private var contextDrawerExpanded = false

    private val exportStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ExportForegroundService.ACTION_STATUS_CHANGED) {
                syncExportUiFromStore()
            }
        }
    }

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

    private val importSrtPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importSrt(uri)
    }

    private val exportSrtPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) exportSrt(uri)
    }

    private val exportVideoPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val settings = pendingExportSettings
        pendingExportSettings = null
        if (uri != null && settings != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            startVideoExport(uri, settings)
        }
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
        exportEngine = VideoExportEngine(this)
        exportTaskStore = ExportTaskStore(this)

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
        captionSegments = sanitizeCaptionSegments(project.captionSegments)
        effectClips = sanitizeEffectClips(project.effectClips)
        canvasSettings = project.canvasSettings
        selectedClipId = project.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = project.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        selectedOverlayClipId = project.selectedOverlayClipId?.takeIf { id -> overlayClips.any { it.id == id } }
        selectedTextClipId = project.selectedTextClipId?.takeIf { id -> textClips.any { it.id == id } }
        selectedCaptionSegmentId = project.selectedCaptionSegmentId?.takeIf { id -> captionSegments.any { it.id == id } }
        selectedEffectClipId = project.selectedEffectClipId?.takeIf { id -> effectClips.any { it.id == id } }
        refreshTimelineIndex()
        timelinePositionMs = project.playheadMs.coerceIn(0, timelineIndex.totalDurationMs)
        timelineZoom = project.timelineZoom.coerceIn(1f, 8f)
        timelineViewportStartMs = project.timelineViewportStartMs.coerceAtLeast(0)

        binding.projectTitle.text = project.title
        binding.backButton.setOnClickListener { finish() }
        binding.exportVideoButton.setOnClickListener { showExportOptions() }
        binding.toolEditButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.EDIT) }
        binding.toolSpeedButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.SPEED) }
        binding.toolTextButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.TEXT) }
        binding.toolAudioButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.AUDIO) }
        binding.toolEffectsButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.EFFECTS) }
        binding.toolColorButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.COLOR) }
        binding.toolLayersButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.LAYERS) }
        binding.toolCaptionsButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.CAPTIONS) }
        binding.editClipTab.setOnClickListener { setEditSubtool(EditSubtool.CLIP) }
        binding.editTransformTab.setOnClickListener { setEditSubtool(EditSubtool.TRANSFORM) }
        binding.editMaskTab.setOnClickListener { setEditSubtool(EditSubtool.MASK) }
        binding.editTrackTab.setOnClickListener { setEditSubtool(EditSubtool.TRACK) }
        setEditSubtool(EditSubtool.CLIP)
        setEditorToolMode(EditorToolMode.EDIT, expandDrawer = false)
        binding.timeline.showPlayhead = false
        binding.audioTimeline.showPlayhead = false
        binding.textTimeline.showPlayhead = false
        binding.overlayTimeline.showPlayhead = false
        binding.captionTimeline.showPlayhead = false
        binding.effectTimeline.showPlayhead = false
        binding.playPauseButton.setOnClickListener {
            if (previewPlayer.isPlaying()) previewPlayer.pause() else startPlaybackFromTimeline()
        }
        binding.addClipButton.setOnClickListener {
            if (!addingMedia) {
                addVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }
        binding.timelineQuickAddButton.setOnClickListener {
            if (!addingMedia) {
                addVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }
        binding.timelineQuickSplitButton.setOnClickListener { splitAtPlayhead() }
        binding.timelineQuickCopyButton.setOnClickListener { duplicateSelectedClip() }
        binding.timelineQuickAudioButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.AUDIO) }
        binding.timelineQuickTextButton.setOnClickListener { toggleEditorToolMode(EditorToolMode.TEXT) }
        binding.splitButton.setOnClickListener { splitAtPlayhead() }
        binding.deleteButton.setOnClickListener { deleteSelectedClip() }
        binding.undoButton.setOnClickListener { undoEdit() }
        binding.redoButton.setOnClickListener { redoEdit() }
        binding.duplicateButton.setOnClickListener { duplicateSelectedClip() }
        binding.replaceButton.setOnClickListener { launchReplaceSelectedClip() }
        binding.addAudioButton.setOnClickListener { addAudioPicker.launch(arrayOf("audio/*")) }
        binding.addAudioLaneButton.setOnClickListener { addAudioPicker.launch(arrayOf("audio/*")) }
        binding.audioMuteButton.setOnClickListener { toggleSelectedAudioMute() }
        binding.audioVolumeDownButton.setOnClickListener { adjustSelectedAudioVolume(-0.1f) }
        binding.audioVolumeUpButton.setOnClickListener { adjustSelectedAudioVolume(0.1f) }
        binding.audioDeleteButton.setOnClickListener { deleteSelectedAudio() }
        binding.audioSplitButton.setOnClickListener { splitSelectedAudioAtPlayhead() }
        binding.audioFadeInButton.setOnClickListener { cycleSelectedAudioFade(inward = true) }
        binding.audioFadeOutButton.setOnClickListener { cycleSelectedAudioFade(inward = false) }
        binding.audioRoleButton.setOnClickListener { cycleSelectedAudioRole() }
        binding.audioPanButton.setOnClickListener { cycleSelectedAudioPan() }
        binding.audioDuckButton.setOnClickListener { cycleSelectedAudioDucking() }
        binding.extractAudioButton.setOnClickListener { extractAudioFromSelectedVideo() }
        binding.addOverlayButton.setOnClickListener {
            if (!addingOverlay) addOverlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        binding.overlayBackButton.setOnClickListener { changeOverlayLayer(-1) }
        binding.overlayFrontButton.setOnClickListener { changeOverlayLayer(1) }
        binding.overlayDeleteButton.setOnClickListener { deleteSelectedOverlay() }
        binding.addTextButton.setOnClickListener { showTextDialog(null) }
        binding.addTextLaneButton.setOnClickListener { showTextDialog(null) }
        binding.editTextButton.setOnClickListener { selectedTextClipId?.let { id -> textClips.firstOrNull { it.id == id } }?.let(::showTextDialog) }
        binding.textBackButton.setOnClickListener { changeTextLayer(-1) }
        binding.textFrontButton.setOnClickListener { changeTextLayer(1) }
        binding.textDeleteButton.setOnClickListener { deleteSelectedText() }
        binding.addCaptionButton.setOnClickListener { showCaptionDialog(null) }
        binding.editCaptionButton.setOnClickListener { selectedCaptionSegmentId?.let { id -> captionSegments.firstOrNull { it.id == id } }?.let(::showCaptionDialog) }
        binding.splitCaptionButton.setOnClickListener { splitSelectedCaptionAtPlayhead() }
        binding.captionStyleButton.setOnClickListener { cycleSelectedCaptionStyle() }
        binding.deleteCaptionButton.setOnClickListener { deleteSelectedCaption() }
        binding.importSrtButton.setOnClickListener { importSrtPicker.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream")) }
        binding.exportSrtButton.setOnClickListener { exportSrtPicker.launch("${project.title.ifBlank { "vedito" }}-captions.srt") }
        binding.captionShiftBackButton.setOnClickListener { shiftAllCaptions(-CAPTION_SHIFT_STEP_MS) }
        binding.captionShiftForwardButton.setOnClickListener { shiftAllCaptions(CAPTION_SHIFT_STEP_MS) }
        binding.captionToolbar.onAction = ::handleCaptionAction
        binding.effectToolbar.onAction = ::handleEffectAction
        binding.textToolbar.onAction = ::handleTextAction
        binding.visualToolbar.onAction = ::handleVisualAction
        binding.maskChromaToolbar.onAction = ::handleMaskChromaAction
        binding.colorToolbar.onAction = ::handleColorAction
        binding.trackingToolbar.onAction = ::handleTrackingAction
        binding.trackingOverlay.onAnchorCommitted = { x, y -> upsertTrackingAnchor(x, y) }
        binding.timingToolbar.onAction = ::handleTimingAction
        binding.previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCanvasPreviewLayout() }
        binding.timelineTracksFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePersistentTimelinePlayheadBounds() }
        binding.overlayPreviewLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::overlayPreview.isInitialized) overlayPreview.render(timelinePositionMs, previewPlayer.isPlaying(), selectedOverlayClipId)
        }
        binding.audioTimeline.onAudioClipSelected = { id ->
            setEditorToolMode(EditorToolMode.AUDIO)
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
            setEditorToolMode(EditorToolMode.LAYERS)
            selectedOverlayClipId = id
            selectedTextClipId = null
            selectedCaptionSegmentId = null
            updateOverlayUi()
            updateTextUi()
            updateCaptionUi()
            updateVisualToolbar()
            updateMaskChromaToolbar()
            updateTimingToolbar()
            updateEffectUi()
            renderEffectState()
            renderOverlayState()
            renderTextState()
            renderCaptionState()
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
            setEditorToolMode(EditorToolMode.TEXT)
            selectedTextClipId = id
            selectedOverlayClipId = null
            selectedCaptionSegmentId = null
            updateTextUi()
            updateOverlayUi()
            updateCaptionUi()
            updateVisualToolbar()
            updateMaskChromaToolbar()
            updateTimingToolbar()
            renderTextState()
            renderOverlayState()
            renderCaptionState()
            saveProject()
        }
        binding.textTimeline.onTextEditStart = { id ->
            selectedTextClipId = id
            selectedOverlayClipId = null
            selectedCaptionSegmentId = null
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

        binding.captionTimeline.onCaptionSelected = { id ->
            setEditorToolMode(EditorToolMode.CAPTIONS)
            selectedCaptionSegmentId = id
            selectedTextClipId = null
            selectedOverlayClipId = null
            updateCaptionUi()
            updateTextUi()
            updateOverlayUi()
            updateMaskChromaToolbar()
            renderCaptionState()
            renderTextState()
            renderOverlayState()
            saveProject()
        }
        binding.captionTimeline.onCaptionEditStart = { id ->
            selectedCaptionSegmentId = id
            selectedTextClipId = null
            selectedOverlayClipId = null
            updateMaskChromaToolbar()
            pendingCaptionEditSnapshot = snapshot()
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
        }
        binding.captionTimeline.onCaptionChanged = { edited, finished ->
            captionSegments = captionSegments.map { if (it.id == edited.id) edited else it }.sortedBy { it.timelineStartMs }
            selectedCaptionSegmentId = edited.id
            if (finished) finishCaptionGestureEdit() else renderCaptionState()
        }

        binding.effectTimeline.onEffectSelected = { id ->
            setEditorToolMode(EditorToolMode.EFFECTS)
            selectedEffectClipId = id
            updateEffectUi()
            renderEffectState()
            saveProject()
        }
        binding.effectTimeline.onEffectEditStart = { id ->
            selectedEffectClipId = id
            pendingEffectEditSnapshot = snapshot()
            previewPlayer.pause()
            audioPlayback.pause()
            playbackClipId = null
        }
        binding.effectTimeline.onEffectChanged = { edited, finished ->
            effectClips = effectClips.map { if (it.id == edited.id) edited else it }.sortedBy { it.timelineStartMs }
            selectedEffectClipId = edited.id
            if (finished) finishEffectGestureEdit() else renderEffectState()
        }

        binding.timeline.onClipSelected = { id ->
            selectedClipId = id
            selectedOverlayClipId = null
            selectedTextClipId = null
            selectedCaptionSegmentId = null
            updateSelectionUi()
            updateOverlayUi()
            updateTextUi()
            updateCaptionUi()
            updateVisualToolbar()
            updateMaskChromaToolbar()
            updateTimingToolbar()
            updateEffectUi()
            renderEffectState()
            renderOverlayState()
            renderTextState()
            renderCaptionState()
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
            renderCaptionState()
            renderEffectState()
            updatePersistentTimelineChrome()
            if (finished) {
                requestThumbnails()
                saveProject()
            }
        }

        previewPlayer = PreviewPlayer(this, binding.previewTexture, this)
        overlayPreview = OverlayPreviewController(this, binding.overlayPreviewLayer)
        overlayPreview.onOverlaySelected = { id ->
            setEditorToolMode(EditorToolMode.LAYERS)
            selectedOverlayClipId = id
            selectedTextClipId = null
            selectedCaptionSegmentId = null
            updateOverlayUi()
            updateTextUi()
            updateCaptionUi()
            updateVisualToolbar()
            updateMaskChromaToolbar()
            updateTimingToolbar()
            renderOverlayState()
            renderTextState()
            renderCaptionState()
            saveProject()
        }
        textPreview = TextPreviewController(this, binding.textPreviewLayer)
        textPreview.onTextSelected = { id ->
            setEditorToolMode(EditorToolMode.TEXT)
            selectedTextClipId = id
            selectedOverlayClipId = null
            selectedCaptionSegmentId = null
            updateTextUi()
            updateOverlayUi()
            updateCaptionUi()
            updateVisualToolbar()
            updateMaskChromaToolbar()
            updateTimingToolbar()
            renderTextState()
            renderOverlayState()
            renderCaptionState()
            saveProject()
        }
        binding.textPreviewLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::textPreview.isInitialized) textPreview.render(timelinePositionMs, selectedTextClipId)
        }
        captionPreview = CaptionPreviewController(this, binding.captionPreviewLayer)
        captionPreview.onCaptionSelected = { id ->
            setEditorToolMode(EditorToolMode.CAPTIONS)
            selectedCaptionSegmentId = id
            selectedTextClipId = null
            selectedOverlayClipId = null
            updateCaptionUi()
            updateTextUi()
            updateOverlayUi()
            updateMaskChromaToolbar()
            renderCaptionState()
            renderTextState()
            renderOverlayState()
            saveProject()
        }
        binding.captionPreviewLayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::captionPreview.isInitialized) captionPreview.render(timelinePositionMs, selectedCaptionSegmentId)
        }
        applyCanvasPreviewLayout()
        audioPlayback.setTimeline(audioAssets, audioClips)
        renderTimelineState(restoreViewport = true)
        openInitialPreview()
        requestThumbnails()
        requestAudioWaveforms()
        probeKnownAssets()
    }

    override fun onStart() {
        super.onStart()
        if (!exportReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                exportStatusReceiver,
                IntentFilter(ExportForegroundService.ACTION_STATUS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            exportReceiverRegistered = true
        }
        syncExportUiFromStore()
    }

    override fun onStop() {
        if (exportReceiverRegistered) {
            unregisterReceiver(exportStatusReceiver)
            exportReceiverRegistered = false
        }
        super.onStop()
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
        if (::captionPreview.isInitialized) captionPreview.release()
        if (::previewPlayer.isInitialized) previewPlayer.release()
        if (::exportEngine.isInitialized) exportEngine.close()
        exportDialog?.dismiss()
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
        renderEffectPreview()
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
            previewPlayer.setChromaKey(ChromaKeySpec())
            previewPlayer.setColorGrade(ColorGradeSpec())
            binding.maskPreviewLayer.render(MaskSpec(), canvasSettings.background.argb)
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
        previewPlayer.setVisualTransform(
            MotionTrackingEngine.applyStabilization(
                base = KeyframeEngine.evaluate(clip.transform, clip.keyframes, timelineOffset, clip.durationMs),
                track = clip.motionTrack,
                stabilization = clip.stabilization,
                localTimeMs = timelineOffset,
                durationMs = clip.durationMs
            )
        )
        applyMaskChromaPreview(clip)
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

    private fun handleMaskChromaAction(action: MaskChromaToolbarView.Action) {
        if (selectedOverlayClipId != null || selectedTextClipId != null || selectedCaptionSegmentId != null) return
        when (action) {
            MaskChromaToolbarView.Action.MASK_SHAPE -> mutateSelectedMask { MaskChromaComposition.cycleShape(it) }
            MaskChromaToolbarView.Action.MASK_SMALLER -> mutateSelectedMask { MaskChromaComposition.resize(it, -MASK_SIZE_STEP) }
            MaskChromaToolbarView.Action.MASK_LARGER -> mutateSelectedMask { MaskChromaComposition.resize(it, MASK_SIZE_STEP) }
            MaskChromaToolbarView.Action.MASK_LEFT -> mutateSelectedMask { MaskChromaComposition.move(it, -MASK_MOVE_STEP, 0f) }
            MaskChromaToolbarView.Action.MASK_RIGHT -> mutateSelectedMask { MaskChromaComposition.move(it, MASK_MOVE_STEP, 0f) }
            MaskChromaToolbarView.Action.MASK_UP -> mutateSelectedMask { MaskChromaComposition.move(it, 0f, -MASK_MOVE_STEP) }
            MaskChromaToolbarView.Action.MASK_DOWN -> mutateSelectedMask { MaskChromaComposition.move(it, 0f, MASK_MOVE_STEP) }
            MaskChromaToolbarView.Action.MASK_FEATHER -> mutateSelectedMask { mask ->
                val presets = MASK_FEATHER_PRESETS
                val index = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - mask.feather) } ?: 0
                mask.copy(feather = presets[(index + 1) % presets.size])
            }
            MaskChromaToolbarView.Action.MASK_INVERT -> mutateSelectedMask { it.copy(inverted = !it.inverted) }
            MaskChromaToolbarView.Action.CHROMA_TOGGLE -> mutateSelectedChroma { it.copy(enabled = !it.enabled) }
            MaskChromaToolbarView.Action.CHROMA_COLOR -> mutateSelectedChroma { chroma ->
                val colors = CHROMA_KEY_COLORS
                val index = colors.indexOf(chroma.keyColorArgb).takeIf { it >= 0 } ?: 0
                chroma.copy(keyColorArgb = colors[(index + 1) % colors.size])
            }
            MaskChromaToolbarView.Action.CHROMA_TOLERANCE -> mutateSelectedChroma { chroma ->
                chroma.copy(tolerance = cycleFloatPreset(chroma.tolerance, CHROMA_TOLERANCE_PRESETS))
            }
            MaskChromaToolbarView.Action.CHROMA_SOFTNESS -> mutateSelectedChroma { chroma ->
                chroma.copy(softness = cycleFloatPreset(chroma.softness, CHROMA_SOFTNESS_PRESETS))
            }
            MaskChromaToolbarView.Action.CHROMA_SPILL -> mutateSelectedChroma { chroma ->
                chroma.copy(spill = cycleFloatPreset(chroma.spill, CHROMA_SPILL_PRESETS))
            }
            MaskChromaToolbarView.Action.RESET -> mutateSelectedMaskChroma { clip ->
                clip.copy(mask = MaskSpec(), chromaKey = ChromaKeySpec())
            }
        }
    }

    private fun mutateSelectedMask(change: (MaskSpec) -> MaskSpec) {
        mutateSelectedMaskChroma { clip -> clip.copy(mask = MaskChromaComposition.normalize(change(clip.mask))) }
    }

    private fun mutateSelectedChroma(change: (ChromaKeySpec) -> ChromaKeySpec) {
        mutateSelectedMaskChroma { clip -> clip.copy(chromaKey = MaskChromaComposition.normalize(change(clip.chromaKey))) }
    }

    private fun mutateSelectedMaskChroma(change: (Clip) -> Clip) {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = clips[index]
        val updated = change(current)
        if (updated == current) return
        val before = snapshot()
        pauseForVisualEdit()
        clips = clips.toMutableList().apply { this[index] = updated }
        refreshTimelineIndex()
        history.record(before)
        val active = timelineIndex.locate(timelinePositionMs)?.clip
        if (active?.id == id) applyMaskChromaPreview(updated)
        updateSelectionUi()
        updateMaskChromaToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun cycleFloatPreset(current: Float, presets: FloatArray): Float {
        val index = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - current) } ?: 0
        return presets[(index + 1) % presets.size]
    }

    private fun applyMaskChromaPreview(clip: Clip) {
        if (!::previewPlayer.isInitialized) return
        val frame = FrameCompositionBuilder.build(clips, effectClips, timelinePositionMs)
            ?.takeIf { it.clipId == clip.id }
        previewPlayer.setChromaKey(frame?.chromaKey ?: clip.chromaKey)
        previewPlayer.setColorGrade(frame?.colorGrade ?: clip.colorGrade)
        binding.maskPreviewLayer.render(frame?.mask ?: clip.mask, canvasSettings.background.argb)
    }

    private fun handleColorAction(action: ColorGradeToolbarView.Action) {
        if (selectedOverlayClipId != null || selectedTextClipId != null || selectedCaptionSegmentId != null) return
        when (action) {
            ColorGradeToolbarView.Action.EXPOSURE_DOWN -> mutateSelectedColor { it.copy(exposure = it.exposure - COLOR_EXPOSURE_STEP) }
            ColorGradeToolbarView.Action.EXPOSURE_UP -> mutateSelectedColor { it.copy(exposure = it.exposure + COLOR_EXPOSURE_STEP) }
            ColorGradeToolbarView.Action.CONTRAST_DOWN -> mutateSelectedColor { it.copy(contrast = it.contrast - COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.CONTRAST_UP -> mutateSelectedColor { it.copy(contrast = it.contrast + COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.SATURATION_DOWN -> mutateSelectedColor { it.copy(saturation = it.saturation - COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.SATURATION_UP -> mutateSelectedColor { it.copy(saturation = it.saturation + COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.TEMPERATURE_DOWN -> mutateSelectedColor { it.copy(temperature = it.temperature - COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.TEMPERATURE_UP -> mutateSelectedColor { it.copy(temperature = it.temperature + COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.TINT_DOWN -> mutateSelectedColor { it.copy(tint = it.tint - COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.TINT_UP -> mutateSelectedColor { it.copy(tint = it.tint + COLOR_ADJUST_STEP) }
            ColorGradeToolbarView.Action.FADE -> mutateSelectedColor { color ->
                val presets = COLOR_FADE_PRESETS
                val index = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - color.fade) } ?: 0
                color.copy(fade = presets[(index + 1) % presets.size])
            }
            ColorGradeToolbarView.Action.CURVE_PRESET -> mutateSelectedColor { color ->
                color.copy(curves = ColorGradeEngine.nextCurvePreset(color.curves))
            }
            ColorGradeToolbarView.Action.HUE_DOWN -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(hueDegrees = color.hsl.hueDegrees - COLOR_HUE_STEP))
            }
            ColorGradeToolbarView.Action.HUE_UP -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(hueDegrees = color.hsl.hueDegrees + COLOR_HUE_STEP))
            }
            ColorGradeToolbarView.Action.HSL_SAT_DOWN -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(saturation = color.hsl.saturation - COLOR_HSL_STEP))
            }
            ColorGradeToolbarView.Action.HSL_SAT_UP -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(saturation = color.hsl.saturation + COLOR_HSL_STEP))
            }
            ColorGradeToolbarView.Action.HSL_LUMA_DOWN -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(luminance = color.hsl.luminance - COLOR_HSL_STEP))
            }
            ColorGradeToolbarView.Action.HSL_LUMA_UP -> mutateSelectedColor { color ->
                color.copy(hsl = color.hsl.copy(luminance = color.hsl.luminance + COLOR_HSL_STEP))
            }
            ColorGradeToolbarView.Action.LUT_PRESET -> mutateSelectedColor { color ->
                color.copy(lut = color.lut.copy(preset = ColorGradeEngine.nextLutPreset(color.lut.preset)))
            }
            ColorGradeToolbarView.Action.LUT_STRENGTH -> mutateSelectedColor { color ->
                val presets = COLOR_LUT_STRENGTH_PRESETS
                val index = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - color.lut.intensity) } ?: 0
                color.copy(lut = color.lut.copy(intensity = presets[(index + 1) % presets.size]))
            }
            ColorGradeToolbarView.Action.RESET -> mutateSelectedColor { ColorGradeSpec() }
        }
    }

    private fun mutateSelectedColor(change: (ColorGradeSpec) -> ColorGradeSpec) {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = clips[index]
        val nextColor = ColorGradeEngine.normalize(change(current.colorGrade))
        if (nextColor == current.colorGrade) return
        val before = snapshot()
        pauseForVisualEdit()
        val updated = current.copy(colorGrade = nextColor)
        clips = clips.toMutableList().apply { this[index] = updated }
        refreshTimelineIndex()
        history.record(before)
        if (timelineIndex.locate(timelinePositionMs)?.clip?.id == id) applyMaskChromaPreview(updated)
        updateSelectionUi()
        updateColorToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun updateColorToolbar() {
        if (!::binding.isInitialized) return
        val supported = selectedOverlayClipId == null && selectedTextClipId == null && selectedCaptionSegmentId == null
        val clip = if (supported) selectedClipId?.let { id -> clips.firstOrNull { it.id == id } } else null
        binding.colorToolbar.setState(clip?.colorGrade, clip != null)
    }

    private fun updateMaskChromaToolbar() {
        if (!::binding.isInitialized) return
        val supported = selectedOverlayClipId == null && selectedTextClipId == null && selectedCaptionSegmentId == null
        val clip = if (supported) selectedClipId?.let { id -> clips.firstOrNull { it.id == id } } else null
        binding.maskChromaToolbar.setState(clip?.mask, clip?.chromaKey, clip != null)
        updateColorToolbar()
        updateTrackingUi()
    }

    private fun handleTrackingAction(action: TrackingStabilizationToolbarView.Action) {
        if (selectedOverlayClipId != null || selectedTextClipId != null || selectedCaptionSegmentId != null) return
        val clip = selectedClipId?.let { id -> clips.firstOrNull { it.id == id } } ?: return
        val local = visualLocalTimeMs(clip)
        when (action) {
            TrackingStabilizationToolbarView.Action.TRACK_TOGGLE -> {
                if (clip.motionTrack.enabled) {
                    mutateSelectedTracking { it.copy(motionTrack = it.motionTrack.copy(enabled = false)) }
                } else {
                    val evaluated = MotionTrackingEngine.evaluate(clip.motionTrack, local, clip.durationMs)
                    val track = if (clip.motionTrack.points.isEmpty()) {
                        MotionTrackingEngine.upsert(clip.motionTrack, local, evaluated?.x ?: 0.5f, evaluated?.y ?: 0.5f, clip.durationMs)
                    } else {
                        clip.motionTrack.copy(enabled = true)
                    }
                    mutateSelectedTracking { it.copy(motionTrack = track) }
                }
            }
            TrackingStabilizationToolbarView.Action.ADD_ANCHOR -> {
                val point = MotionTrackingEngine.evaluate(clip.motionTrack, local, clip.durationMs)
                upsertTrackingAnchor(point?.x ?: 0.5f, point?.y ?: 0.5f)
            }
            TrackingStabilizationToolbarView.Action.REMOVE_ANCHOR -> mutateSelectedTracking { current ->
                current.copy(motionTrack = MotionTrackingEngine.removeNearest(current.motionTrack, local, current.durationMs))
            }
            TrackingStabilizationToolbarView.Action.PREVIOUS_ANCHOR -> {
                MotionTrackingEngine.previousPointTime(clip.motionTrack, local, clip.durationMs)?.let { target ->
                    setTimelinePosition((timelineIndex.startOf(clip.id) + target).coerceIn(0, timelineIndex.totalDurationMs))
                    seekPreviewToTimeline(timelinePositionMs)
                }
            }
            TrackingStabilizationToolbarView.Action.NEXT_ANCHOR -> {
                MotionTrackingEngine.nextPointTime(clip.motionTrack, local, clip.durationMs)?.let { target ->
                    setTimelinePosition((timelineIndex.startOf(clip.id) + target).coerceIn(0, timelineIndex.totalDurationMs))
                    seekPreviewToTimeline(timelinePositionMs)
                }
            }
            TrackingStabilizationToolbarView.Action.STABILIZE_TOGGLE -> mutateSelectedTracking { current ->
                current.copy(stabilization = current.stabilization.copy(enabled = !current.stabilization.enabled))
            }
            TrackingStabilizationToolbarView.Action.STRENGTH -> mutateSelectedTracking { current ->
                val strength = cycleFloatPreset(current.stabilization.strength, STABILIZATION_STRENGTH_PRESETS)
                current.copy(stabilization = current.stabilization.copy(strength = strength))
            }
            TrackingStabilizationToolbarView.Action.AUTO_CROP -> mutateSelectedTracking { current ->
                current.copy(stabilization = current.stabilization.copy(autoCrop = !current.stabilization.autoCrop))
            }
            TrackingStabilizationToolbarView.Action.RESET -> mutateSelectedTracking { current ->
                current.copy(motionTrack = MotionTrackSpec(), stabilization = StabilizationSpec())
            }
        }
    }

    private fun upsertTrackingAnchor(x: Float, y: Float) {
        val id = selectedClipId ?: return
        val clip = clips.firstOrNull { it.id == id } ?: return
        if (timelineIndex.locate(timelinePositionMs)?.clip?.id != id) return
        val local = visualLocalTimeMs(clip)
        mutateSelectedTracking { current ->
            current.copy(
                motionTrack = MotionTrackingEngine.upsert(
                    current.motionTrack,
                    local,
                    x,
                    y,
                    current.durationMs
                )
            )
        }
    }

    private fun mutateSelectedTracking(change: (Clip) -> Clip) {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = clips[index]
        val changed = change(current)
        val updated = changed.copy(
            motionTrack = MotionTrackingEngine.normalize(changed.motionTrack, changed.durationMs),
            stabilization = MotionTrackingEngine.normalize(changed.stabilization)
        )
        if (updated == current) return
        val before = snapshot()
        pauseForVisualEdit()
        clips = clips.toMutableList().apply { this[index] = updated }
        refreshTimelineIndex()
        history.record(before)
        if (timelineIndex.locate(timelinePositionMs)?.clip?.id == id) {
            previewPlayer.setVisualTransform(effectiveTransformForClip(updated, timelinePositionMs))
        }
        updateSelectionUi()
        updateTrackingUi()
        saveProject()
        updateHistoryUi()
    }

    private fun updateTrackingUi() {
        if (!::binding.isInitialized) return
        val supported = selectedOverlayClipId == null && selectedTextClipId == null && selectedCaptionSegmentId == null
        val clip = if (supported) selectedClipId?.let { id -> clips.firstOrNull { it.id == id } } else null
        val local = clip?.let(::visualLocalTimeMs) ?: 0
        binding.trackingToolbar.setState(clip?.motionTrack, clip?.stabilization, local, clip != null)
        val editable = clip != null && timelineIndex.locate(timelinePositionMs)?.clip?.id == clip.id
        binding.trackingOverlay.render(
            track = clip?.motionTrack ?: MotionTrackSpec(),
            localTimeMs = local,
            durationMs = clip?.durationMs ?: 0,
            editable = editable
        )
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
            TextToolbarView.Action.SCALE_DOWN -> mutateSelectedTextTransform { it.copy(scale = it.scale - 0.1f) }
            TextToolbarView.Action.SCALE_UP -> mutateSelectedTextTransform { it.copy(scale = it.scale + 0.1f) }
            TextToolbarView.Action.MOVE_LEFT -> mutateSelectedTextTransform { it.copy(positionX = it.positionX - TEXT_POSITION_STEP) }
            TextToolbarView.Action.MOVE_RIGHT -> mutateSelectedTextTransform { it.copy(positionX = it.positionX + TEXT_POSITION_STEP) }
            TextToolbarView.Action.MOVE_UP -> mutateSelectedTextTransform { it.copy(positionY = it.positionY - TEXT_POSITION_STEP) }
            TextToolbarView.Action.MOVE_DOWN -> mutateSelectedTextTransform { it.copy(positionY = it.positionY + TEXT_POSITION_STEP) }
            TextToolbarView.Action.ROTATE -> mutateSelectedTextTransform { it.copy(rotationDegrees = it.rotationDegrees + 15f) }
            TextToolbarView.Action.OPACITY -> mutateSelectedTextTransform { transform ->
                val next = when {
                    transform.opacity > 0.76f -> 0.75f
                    transform.opacity > 0.51f -> 0.50f
                    transform.opacity > 0.26f -> 0.25f
                    else -> 1f
                }
                transform.copy(opacity = next)
            }
            TextToolbarView.Action.FONT_DOWN -> mutateSelectedText { it.copy(style = it.style.copy(fontSizeSp = it.style.fontSizeSp - 4f), preset = TextPreset.CUSTOM) }
            TextToolbarView.Action.FONT_UP -> mutateSelectedText { it.copy(style = it.style.copy(fontSizeSp = it.style.fontSizeSp + 4f), preset = TextPreset.CUSTOM) }
            TextToolbarView.Action.COLOR -> mutateSelectedText {
                val current = TEXT_COLORS.indexOf(it.style.textColorArgb).takeIf { index -> index >= 0 } ?: 0
                it.copy(style = it.style.copy(textColorArgb = TEXT_COLORS[(current + 1) % TEXT_COLORS.size]), preset = TextPreset.CUSTOM)
            }
            TextToolbarView.Action.BACKGROUND -> mutateSelectedText {
                val current = TEXT_BACKGROUNDS.indexOf(it.style.backgroundColorArgb).takeIf { index -> index >= 0 } ?: 0
                it.copy(style = it.style.copy(backgroundColorArgb = TEXT_BACKGROUNDS[(current + 1) % TEXT_BACKGROUNDS.size]), preset = TextPreset.CUSTOM)
            }
            TextToolbarView.Action.BOLD -> mutateSelectedText { it.copy(style = it.style.copy(bold = !it.style.bold), preset = TextPreset.CUSTOM) }
            TextToolbarView.Action.ALIGN -> mutateSelectedText {
                val values = TextAlignment.values()
                val current = values.indexOf(it.style.alignment).coerceAtLeast(0)
                it.copy(style = it.style.copy(alignment = values[(current + 1) % values.size]), preset = TextPreset.CUSTOM)
            }
            TextToolbarView.Action.PRESET -> mutateSelectedText { clip ->
                val next = TextPresetCatalog.next(clip.preset)
                clip.copy(
                    preset = next,
                    style = TextPresetCatalog.style(next),
                    transform = TextPresetCatalog.transform(next, clip.transform)
                )
            }
            TextToolbarView.Action.FONT_FAMILY -> mutateSelectedText { clip ->
                val values = TextFontFamily.values()
                val current = values.indexOf(clip.style.fontFamily).coerceAtLeast(0)
                clip.copy(
                    style = clip.style.copy(fontFamily = values[(current + 1) % values.size]),
                    preset = TextPreset.CUSTOM
                )
            }
            TextToolbarView.Action.ANIMATION -> mutateSelectedText { clip ->
                val values = TextAnimationKind.values()
                val current = values.indexOf(clip.animation.kind).coerceAtLeast(0)
                clip.copy(animation = clip.animation.copy(kind = values[(current + 1) % values.size]))
            }
            TextToolbarView.Action.SHADOW -> mutateSelectedText { clip ->
                clip.copy(style = clip.style.copy(shadowEnabled = !clip.style.shadowEnabled), preset = TextPreset.CUSTOM)
            }
            TextToolbarView.Action.KEYFRAME_TOGGLE -> toggleSelectedTextKeyframe()
            TextToolbarView.Action.KEYFRAME_PREVIOUS -> jumpSelectedTextKeyframe(previous = true)
            TextToolbarView.Action.KEYFRAME_NEXT -> jumpSelectedTextKeyframe(previous = false)
            TextToolbarView.Action.KEYFRAME_EASING -> cycleSelectedTextKeyframeEasing()
            TextToolbarView.Action.RESET_TRANSFORM -> mutateSelectedTextTransform { TextTransform() }
        }
    }

    private fun mutateSelectedTextTransform(change: (TextTransform) -> TextTransform) {
        val id = selectedTextClipId ?: return
        val index = textClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = textClips[index]
        val local = (timelinePositionMs - current.timelineStartMs).coerceIn(0, current.durationMs)
        val evaluated = TextKeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
        val changed = TextTimelineEditor.normalizeTransform(change(evaluated))
        val updated = if (current.keyframes.isEmpty) {
            current.copy(transform = changed)
        } else {
            current.copy(keyframes = TextKeyframeEngine.upsertTransform(changed, current.keyframes, local, current.durationMs))
        }
        if (updated == current) return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        textClips = textClips.toMutableList().apply { this[index] = updated }
        history.record(before)
        renderTextState()
        updateTextUi()
        saveProject()
        updateHistoryUi()
    }

    private fun toggleSelectedTextKeyframe() {
        val id = selectedTextClipId ?: return
        val index = textClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val current = textClips[index]
        val local = (timelinePositionMs - current.timelineStartMs).coerceIn(0, current.durationMs)
        val next = if (TextKeyframeEngine.hasAt(current.keyframes, local)) {
            current.copy(keyframes = TextKeyframeEngine.removeAt(current.keyframes, local, current.durationMs))
        } else {
            val evaluated = TextKeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
            current.copy(keyframes = TextKeyframeEngine.upsertTransform(evaluated, current.keyframes, local, current.durationMs))
        }
        if (next == current) return
        textClips = textClips.toMutableList().apply { this[index] = next }
        history.record(before)
        renderTextState()
        updateTextUi()
        saveProject()
        updateHistoryUi()
    }

    private fun jumpSelectedTextKeyframe(previous: Boolean) {
        val clip = textClips.firstOrNull { it.id == selectedTextClipId } ?: return
        val local = (timelinePositionMs - clip.timelineStartMs).coerceIn(0, clip.durationMs)
        val target = if (previous) TextKeyframeEngine.previousPosition(clip.keyframes, local) else TextKeyframeEngine.nextPosition(clip.keyframes, local)
        if (target == null) return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        timelinePositionMs = (clip.timelineStartMs + target).coerceIn(0, timelineIndex.totalDurationMs)
        renderTimelineState()
        seekPreviewToTimeline(timelinePositionMs)
    }

    private fun cycleSelectedTextKeyframeEasing() {
        val id = selectedTextClipId ?: return
        val index = textClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = textClips[index]
        val local = (timelinePositionMs - current.timelineStartMs).coerceIn(0, current.durationMs)
        val active = TextKeyframeEngine.easingAt(current.keyframes, local) ?: return
        val values = KeyframeEasing.values()
        val nextEasing = values[(values.indexOf(active).coerceAtLeast(0) + 1) % values.size]
        val nextKeys = TextKeyframeEngine.setEasingAt(current.keyframes, local, nextEasing, current.durationMs)
        if (nextKeys == current.keyframes) return
        val before = snapshot()
        textClips = textClips.toMutableList().apply { this[index] = current.copy(keyframes = nextKeys) }
        history.record(before)
        renderTextState()
        updateTextUi()
        saveProject()
        updateHistoryUi()
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
            transform = TextTimelineEditor.normalizeTransform(changed.transform),
            animation = TextMotion.normalize(changed.animation, changed.durationMs)
        )
        if (normalized == current) return
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
        textClips = textClips.toMutableList().apply { this[index] = normalized }
        history.record(before)
        renderTextState()
        updateTextUi()
        captionSegments = sanitizeCaptionSegments(captionSegments)
        if (selectedCaptionSegmentId != null && captionSegments.none { it.id == selectedCaptionSegmentId }) selectedCaptionSegmentId = null
        renderCaptionState()
        updateCaptionUi()
        updateVisualToolbar()
        updateTimingToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun handleVisualAction(action: TransformToolbarView.Action) {
        when (action) {
            TransformToolbarView.Action.KEYFRAME_TOGGLE -> toggleVisualKeyframe()
            TransformToolbarView.Action.KEYFRAME_PREVIOUS -> jumpVisualKeyframe(previous = true)
            TransformToolbarView.Action.KEYFRAME_NEXT -> jumpVisualKeyframe(previous = false)
            TransformToolbarView.Action.KEYFRAME_EASING -> cycleVisualKeyframeEasing()
            TransformToolbarView.Action.KEYFRAME_CLEAR -> clearVisualKeyframes()
            TransformToolbarView.Action.SCALE_DOWN -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(scale = it.scale - 0.1f) }
            TransformToolbarView.Action.SCALE_UP -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(scale = it.scale + 0.1f) }
            TransformToolbarView.Action.MOVE_LEFT -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(positionX = it.positionX - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_RIGHT -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(positionX = it.positionX + POSITION_STEP) }
            TransformToolbarView.Action.MOVE_UP -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(positionY = it.positionY - POSITION_STEP) }
            TransformToolbarView.Action.MOVE_DOWN -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(positionY = it.positionY + POSITION_STEP) }
            TransformToolbarView.Action.ROTATE_90 -> mutateActiveTransform(animateIfKeyframed = true) { it.copy(rotationDegrees = it.rotationDegrees + 90f) }
            TransformToolbarView.Action.FLIP_HORIZONTAL -> mutateActiveTransform { it.copy(flipHorizontal = !it.flipHorizontal) }
            TransformToolbarView.Action.FLIP_VERTICAL -> mutateActiveTransform { it.copy(flipVertical = !it.flipVertical) }
            TransformToolbarView.Action.OPACITY_CYCLE -> mutateActiveTransform(animateIfKeyframed = true) {
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
            TransformToolbarView.Action.RESET_TRANSFORM -> mutateActiveTransform {
                if (selectedOverlayClipId != null) ClipTransform(scale = 0.45f) else ClipTransform()
            }
        }
    }

    private fun mutateActiveTransform(
        animateIfKeyframed: Boolean = false,
        change: (ClipTransform) -> ClipTransform
    ) {
        val overlayId = selectedOverlayClipId
        if (overlayId != null) {
            val index = overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return
            val before = snapshot()
            val current = overlayClips[index]
            val local = visualLocalTimeMs(current)
            val effective = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
            val changed = VisualTransformMath.normalize(change(if (animateIfKeyframed && !current.keyframes.isEmpty) effective else current.transform))
            val updated = if (animateIfKeyframed && !current.keyframes.isEmpty) {
                current.copy(
                    keyframes = KeyframeEngine.upsertTransform(
                        current = changed,
                        keyframes = current.keyframes,
                        localTimeMs = local,
                        durationMs = current.durationMs
                    )
                )
            } else {
                current.copy(transform = changed)
            }
            if (updated == current) return
            pauseForVisualEdit()
            overlayClips = overlayClips.toMutableList().apply { this[index] = updated }
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
        val local = visualLocalTimeMs(current)
        val effective = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
        val changed = VisualTransformMath.normalize(change(if (animateIfKeyframed && !current.keyframes.isEmpty) effective else current.transform))
        val updated = if (animateIfKeyframed && !current.keyframes.isEmpty) {
            current.copy(
                keyframes = KeyframeEngine.upsertTransform(
                    current = changed,
                    keyframes = current.keyframes,
                    localTimeMs = local,
                    durationMs = current.durationMs
                )
            )
        } else {
            current.copy(transform = changed)
        }
        if (updated == current) return
        pauseForVisualEdit()
        clips = clips.toMutableList().apply { this[index] = updated }
        refreshTimelineIndex()
        history.record(before)
        val currentLocation = timelineIndex.locate(timelinePositionMs)
        if (currentLocation?.clip?.id != id) {
            setTimelinePosition(timelineIndex.startOf(id))
            seekPreviewToTimeline(timelinePositionMs)
        } else {
            previewPlayer.setVisualTransform(effectiveTransformForClip(updated, timelinePositionMs))
            applyCanvasPreviewLayout(updated)
        }
        updateSelectionUi()
        updateVisualToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun toggleVisualKeyframe() {
        val before = snapshot()
        val overlayId = selectedOverlayClipId
        if (overlayId != null) {
            val index = overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return
            val current = overlayClips[index]
            val local = visualLocalTimeMs(current)
            val next = if (KeyframeEngine.hasAt(current.keyframes, local)) {
                current.copy(keyframes = KeyframeEngine.removeAt(current.keyframes, local, current.durationMs))
            } else {
                val effective = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
                current.copy(
                    keyframes = KeyframeEngine.upsertTransform(
                        effective,
                        current.keyframes,
                        local,
                        current.durationMs
                    )
                )
            }
            if (next == current) return
            pauseForVisualEdit()
            overlayClips = overlayClips.toMutableList().apply { this[index] = next }
        } else {
            val id = selectedClipId ?: return
            val index = clips.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = clips[index]
            val local = visualLocalTimeMs(current)
            val next = if (KeyframeEngine.hasAt(current.keyframes, local)) {
                current.copy(keyframes = KeyframeEngine.removeAt(current.keyframes, local, current.durationMs))
            } else {
                val effective = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
                current.copy(
                    keyframes = KeyframeEngine.upsertTransform(
                        effective,
                        current.keyframes,
                        local,
                        current.durationMs
                    )
                )
            }
            if (next == current) return
            pauseForVisualEdit()
            clips = clips.toMutableList().apply { this[index] = next }
            refreshTimelineIndex()
        }
        history.record(before)
        renderVisualKeyframeState()
        saveProject()
        updateHistoryUi()
    }

    private fun cycleVisualKeyframeEasing() {
        val before = snapshot()
        val easings = KeyframeEasing.values()
        val overlayId = selectedOverlayClipId
        if (overlayId != null) {
            val index = overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return
            val current = overlayClips[index]
            val local = visualLocalTimeMs(current)
            val currentEasing = KeyframeEngine.easingAt(current.keyframes, local) ?: return
            val nextEasing = easings[(easings.indexOf(currentEasing) + 1) % easings.size]
            val nextKeyframes = KeyframeEngine.setEasingAt(current.keyframes, local, nextEasing, current.durationMs)
            if (nextKeyframes == current.keyframes) return
            pauseForVisualEdit()
            overlayClips = overlayClips.toMutableList().apply { this[index] = current.copy(keyframes = nextKeyframes) }
        } else {
            val id = selectedClipId ?: return
            val index = clips.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = clips[index]
            val local = visualLocalTimeMs(current)
            val currentEasing = KeyframeEngine.easingAt(current.keyframes, local) ?: return
            val nextEasing = easings[(easings.indexOf(currentEasing) + 1) % easings.size]
            val nextKeyframes = KeyframeEngine.setEasingAt(current.keyframes, local, nextEasing, current.durationMs)
            if (nextKeyframes == current.keyframes) return
            pauseForVisualEdit()
            clips = clips.toMutableList().apply { this[index] = current.copy(keyframes = nextKeyframes) }
            refreshTimelineIndex()
        }
        history.record(before)
        renderVisualKeyframeState()
        saveProject()
        updateHistoryUi()
    }

    private fun clearVisualKeyframes() {
        val before = snapshot()
        val overlayId = selectedOverlayClipId
        if (overlayId != null) {
            val index = overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return
            val current = overlayClips[index]
            if (current.keyframes.isEmpty) return
            val local = visualLocalTimeMs(current)
            val baked = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
            pauseForVisualEdit()
            overlayClips = overlayClips.toMutableList().apply {
                this[index] = current.copy(transform = baked, keyframes = TransformKeyframeSet())
            }
        } else {
            val id = selectedClipId ?: return
            val index = clips.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = clips[index]
            if (current.keyframes.isEmpty) return
            val local = visualLocalTimeMs(current)
            val baked = KeyframeEngine.evaluate(current.transform, current.keyframes, local, current.durationMs)
            pauseForVisualEdit()
            clips = clips.toMutableList().apply {
                this[index] = current.copy(transform = baked, keyframes = TransformKeyframeSet())
            }
            refreshTimelineIndex()
        }
        history.record(before)
        renderVisualKeyframeState()
        saveProject()
        updateHistoryUi()
    }

    private fun jumpVisualKeyframe(previous: Boolean) {
        val overlay = selectedOverlayClipId?.let { id -> overlayClips.firstOrNull { it.id == id } }
        if (overlay != null) {
            val local = visualLocalTimeMs(overlay)
            val target = if (previous) {
                KeyframeEngine.previousPosition(overlay.keyframes, local)
            } else {
                KeyframeEngine.nextPosition(overlay.keyframes, local)
            } ?: return
            setTimelinePosition((overlay.timelineStartMs + target).coerceIn(0, timelineIndex.totalDurationMs))
            seekPreviewToTimeline(timelinePositionMs)
            updateVisualToolbar()
            return
        }

        val clip = selectedClipId?.let { id -> clips.firstOrNull { it.id == id } } ?: return
        val local = visualLocalTimeMs(clip)
        val target = if (previous) {
            KeyframeEngine.previousPosition(clip.keyframes, local)
        } else {
            KeyframeEngine.nextPosition(clip.keyframes, local)
        } ?: return
        setTimelinePosition((timelineIndex.startOf(clip.id) + target).coerceIn(0, timelineIndex.totalDurationMs))
        seekPreviewToTimeline(timelinePositionMs)
        updateVisualToolbar()
    }

    private fun pauseForVisualEdit() {
        previewPlayer.pause()
        audioPlayback.pause()
        playbackClipId = null
    }

    private fun renderVisualKeyframeState() {
        renderOverlayState()
        updateOverlayUi()
        updateSelectionUi()
        updateVisualToolbar()
        updateMaskChromaToolbar()
        updateTrackingUi()
        val active = timelineIndex.locate(timelinePositionMs)?.clip
        if (active != null) previewPlayer.setVisualTransform(effectiveTransformForClip(active, timelinePositionMs))
        applyCanvasPreviewLayout(active)
    }

    private fun visualLocalTimeMs(clip: Clip): Int =
        (timelinePositionMs - timelineIndex.startOf(clip.id)).coerceIn(0, clip.durationMs)

    private fun visualLocalTimeMs(clip: OverlayClip): Int =
        (timelinePositionMs - clip.timelineStartMs).coerceIn(0, clip.durationMs)

    private fun effectiveTransformForClip(clip: Clip, timelineMs: Int): ClipTransform {
        FrameCompositionBuilder.build(clips, effectClips, timelineMs)?.let { frame ->
            if (frame.clipId == clip.id) return frame.transform
        }
        val local = (timelineMs - timelineIndex.startOf(clip.id)).coerceIn(0, clip.durationMs)
        return MotionTrackingEngine.applyStabilization(
            base = KeyframeEngine.evaluate(clip.transform, clip.keyframes, local, clip.durationMs),
            track = clip.motionTrack,
            stabilization = clip.stabilization,
            localTimeMs = local,
            durationMs = clip.durationMs
        )
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
        if (selectedTextClipId != null) {
            binding.visualToolbar.setState(null, canvasSettings)
            return
        }

        val overlay = selectedOverlayClipId?.let { id -> overlayClips.firstOrNull { it.id == id } }
        if (overlay != null) {
            val local = visualLocalTimeMs(overlay)
            binding.visualToolbar.setState(
                transform = KeyframeEngine.evaluate(overlay.transform, overlay.keyframes, local, overlay.durationMs),
                canvas = canvasSettings,
                keyframes = overlay.keyframes,
                localTimeMs = local
            )
            return
        }

        val clip = selectedClipId?.let { id -> clips.firstOrNull { it.id == id } }
        if (clip != null) {
            val local = visualLocalTimeMs(clip)
            binding.visualToolbar.setState(
                transform = KeyframeEngine.evaluate(clip.transform, clip.keyframes, local, clip.durationMs),
                canvas = canvasSettings,
                keyframes = clip.keyframes,
                localTimeMs = local
            )
        } else {
            binding.visualToolbar.setState(null, canvasSettings)
        }
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
            previewPlayer.setVisualTransform(effectiveTransformForClip(activeClip, timelinePositionMs))
            applyMaskChromaPreview(activeClip)
        } else if (::previewPlayer.isInitialized) {
            previewPlayer.setChromaKey(ChromaKeySpec())
            previewPlayer.setColorGrade(ColorGradeSpec())
            binding.maskPreviewLayer.render(MaskSpec(), canvasSettings.background.argb)
            binding.trackingOverlay.render(MotionTrackSpec(), 0, 0, false)
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
        pendingCaptionEditSnapshot = null
        pendingEffectEditSnapshot = null
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
        pendingCaptionEditSnapshot = null
        pendingEffectEditSnapshot = null
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
        captionSegments = sanitizeCaptionSegments(snapshot.captionSegments)
        effectClips = sanitizeEffectClips(snapshot.effectClips)
        canvasSettings = snapshot.canvasSettings
        selectedClipId = snapshot.selectedClipId?.takeIf { id -> clips.any { it.id == id } }
            ?: clips.firstOrNull()?.id
        selectedAudioClipId = snapshot.selectedAudioClipId?.takeIf { id -> audioClips.any { it.id == id } }
        selectedOverlayClipId = snapshot.selectedOverlayClipId?.takeIf { id -> overlayClips.any { it.id == id } }
        selectedTextClipId = snapshot.selectedTextClipId?.takeIf { id -> textClips.any { it.id == id } }
        selectedCaptionSegmentId = snapshot.selectedCaptionSegmentId?.takeIf { id -> captionSegments.any { it.id == id } }
        selectedEffectClipId = snapshot.selectedEffectClipId?.takeIf { id -> effectClips.any { it.id == id } }
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
        captionSegments = captionSegments.toList(),
        effectClips = effectClips.toList(),
        canvasSettings = canvasSettings,
        selectedClipId = selectedClipId,
        selectedAudioClipId = selectedAudioClipId,
        selectedOverlayClipId = selectedOverlayClipId,
        selectedTextClipId = selectedTextClipId,
        selectedCaptionSegmentId = selectedCaptionSegmentId,
        selectedEffectClipId = selectedEffectClipId,
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
        captionSegments = sanitizeCaptionSegments(captionSegments)
        if (selectedCaptionSegmentId != null && captionSegments.none { it.id == selectedCaptionSegmentId }) selectedCaptionSegmentId = null
        renderCaptionState()
        updateCaptionUi()
        effectClips = sanitizeEffectClips(effectClips)
        if (selectedEffectClipId != null && effectClips.none { it.id == selectedEffectClipId }) selectedEffectClipId = null
        renderEffectState()
        updateEffectUi()
        updateVisualToolbar()
        updateMaskChromaToolbar()
        updateTrackingUi()
        updateTimingToolbar()
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
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
        binding.captionTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
        binding.effectTimeline.updatePlayhead(timelinePositionMs, timelineZoom, timelineViewportStartMs)
        renderEffectPreview()
        if (::textPreview.isInitialized) textPreview.render(timelinePositionMs, selectedTextClipId)
        if (::captionPreview.isInitialized) captionPreview.render(timelinePositionMs, selectedCaptionSegmentId)
        if (::overlayPreview.isInitialized) overlayPreview.render(timelinePositionMs, previewPlayer.isPlaying(), selectedOverlayClipId)
        val activeClip = timelineIndex.locate(timelinePositionMs)?.clip
        if (activeClip != null && ::previewPlayer.isInitialized) {
            previewPlayer.setVisualTransform(effectiveTransformForClip(activeClip, timelinePositionMs))
            applyMaskChromaPreview(activeClip)
        }
        updateTrackingUi()
        if (::previewPlayer.isInitialized && !previewPlayer.isPlaying()) {
            updateVisualToolbar()
            updateMaskChromaToolbar()
        }
        updatePersistentTimelineChrome()
    }

    private fun setEditSubtool(subtool: EditSubtool) {
        editSubtool = subtool
        binding.clipToolsRow.visibility = if (subtool == EditSubtool.CLIP) View.VISIBLE else View.GONE
        binding.visualToolbar.visibility = if (subtool == EditSubtool.TRANSFORM) View.VISIBLE else View.GONE
        binding.maskChromaToolbar.visibility = if (subtool == EditSubtool.MASK) View.VISIBLE else View.GONE
        binding.trackingToolbar.visibility = if (subtool == EditSubtool.TRACK) View.VISIBLE else View.GONE

        val tabs = listOf(
            EditSubtool.CLIP to binding.editClipTab,
            EditSubtool.TRANSFORM to binding.editTransformTab,
            EditSubtool.MASK to binding.editMaskTab,
            EditSubtool.TRACK to binding.editTrackTab
        )
        val activeColor = ContextCompat.getColor(this, R.color.vedito_brand_cyan)
        val idleColor = ContextCompat.getColor(this, R.color.vedito_text_secondary)
        tabs.forEach { (tabSubtool, tab) ->
            val selected = tabSubtool == subtool
            tab.setBackgroundResource(if (selected) R.drawable.bg_editor_subtool_selected else R.drawable.bg_editor_subtool)
            tab.setTextColor(if (selected) activeColor else idleColor)
        }
    }

    private fun toggleEditorToolMode(mode: EditorToolMode) {
        val expand = mode != editorToolMode || !contextDrawerExpanded
        setEditorToolMode(mode, expandDrawer = expand)
    }

    private fun setEditorToolMode(mode: EditorToolMode, expandDrawer: Boolean = true) {
        editorToolMode = mode
        contextDrawerExpanded = expandDrawer
        binding.contextDrawer.visibility = if (expandDrawer) View.VISIBLE else View.GONE

        val panels = listOf(
            binding.editToolsPanel,
            binding.speedToolsPanel,
            binding.textToolsPanel,
            binding.audioToolsPanel,
            binding.effectsToolsPanel,
            binding.colorToolsPanel,
            binding.layersToolsPanel,
            binding.captionsToolsPanel
        )
        panels.forEach { it.visibility = View.GONE }

        val activePanel = when (mode) {
            EditorToolMode.EDIT -> binding.editToolsPanel
            EditorToolMode.SPEED -> binding.speedToolsPanel
            EditorToolMode.TEXT -> binding.textToolsPanel
            EditorToolMode.AUDIO -> binding.audioToolsPanel
            EditorToolMode.EFFECTS -> binding.effectsToolsPanel
            EditorToolMode.COLOR -> binding.colorToolsPanel
            EditorToolMode.LAYERS -> binding.layersToolsPanel
            EditorToolMode.CAPTIONS -> binding.captionsToolsPanel
        }
        activePanel.visibility = View.VISIBLE

        // Timeline lanes are persistent and independent from the context drawer.
        // Tool mode only controls the lower editing controls, not whether timeline data exists.
        updatePersistentTimelineLanes()
        if (mode == EditorToolMode.EDIT) setEditSubtool(editSubtool)

        val modeButtons = listOf(
            EditorToolMode.EDIT to binding.toolEditButton,
            EditorToolMode.SPEED to binding.toolSpeedButton,
            EditorToolMode.TEXT to binding.toolTextButton,
            EditorToolMode.AUDIO to binding.toolAudioButton,
            EditorToolMode.EFFECTS to binding.toolEffectsButton,
            EditorToolMode.COLOR to binding.toolColorButton,
            EditorToolMode.LAYERS to binding.toolLayersButton,
            EditorToolMode.CAPTIONS to binding.toolCaptionsButton
        )

        val activeColor = when (mode) {
            EditorToolMode.TEXT, EditorToolMode.CAPTIONS -> ContextCompat.getColor(this, R.color.vedito_text_track)
            EditorToolMode.AUDIO -> ContextCompat.getColor(this, R.color.vedito_audio)
            EditorToolMode.EFFECTS -> ContextCompat.getColor(this, R.color.vedito_effect)
            EditorToolMode.LAYERS -> ContextCompat.getColor(this, R.color.vedito_video)
            else -> ContextCompat.getColor(this, R.color.vedito_brand_cyan)
        }
        val idleColor = ContextCompat.getColor(this, R.color.vedito_text_secondary)

        modeButtons.forEach { (buttonMode, button) ->
            val selected = contextDrawerExpanded && buttonMode == mode
            val color = if (selected) activeColor else idleColor
            button.setBackgroundResource(if (selected) R.drawable.bg_editor_tool_mode_selected else R.drawable.bg_editor_tool_mode)
            button.setTextColor(color)
            button.compoundDrawableTintList = ColorStateList.valueOf(color)
        }

        val track = when (mode) {
            EditorToolMode.TEXT -> "TEXT" to R.color.vedito_text_track
            EditorToolMode.AUDIO -> "AUDIO" to R.color.vedito_audio
            EditorToolMode.EFFECTS -> "EFFECTS" to R.color.vedito_effect
            EditorToolMode.LAYERS -> "OVERLAY" to R.color.vedito_video
            EditorToolMode.CAPTIONS -> "CAPTIONS" to R.color.vedito_text_track
            else -> "VIDEO" to R.color.vedito_video
        }
        binding.activeTrackLabel.text = track.first
        binding.activeTrackLabel.setTextColor(ContextCompat.getColor(this, track.second))
    }

    private fun updatePersistentTimelineLanes() {
        if (!::binding.isInitialized) return
        binding.auxTimelineContainer.visibility = View.VISIBLE
        binding.audioLane.visibility = View.VISIBLE
        binding.textLane.visibility = View.VISIBLE
        binding.audioTimeline.visibility = View.VISIBLE
        binding.textTimeline.visibility = View.VISIBLE
        binding.addAudioLaneButton.visibility = if (audioClips.isEmpty()) View.VISIBLE else View.GONE
        binding.addTextLaneButton.visibility = if (textClips.isEmpty()) View.VISIBLE else View.GONE

        binding.overlayLane.visibility = if (overlayClips.isNotEmpty()) View.VISIBLE else View.GONE
        binding.captionLane.visibility = if (captionSegments.isNotEmpty()) View.VISIBLE else View.GONE
        binding.effectLane.visibility = if (effectClips.isNotEmpty()) View.VISIBLE else View.GONE
        updatePersistentTimelinePlayheadBounds()
    }

    private fun updatePersistentTimelinePlayheadBounds() {
        if (!::binding.isInitialized) return
        val density = resources.displayMetrics.density
        var desiredDp = 116f // ruler + Video + persistent Audio/Text lanes
        if (binding.overlayLane.visibility == View.VISIBLE) desiredDp += 25f
        if (binding.captionLane.visibility == View.VISIBLE) desiredDp += 25f
        if (binding.effectLane.visibility == View.VISIBLE) desiredDp += 25f
        val desiredPx = (desiredDp * density).roundToInt()
        val fallbackMaxPx = (132f * density).roundToInt()
        val maxPx = binding.timelineTracksFrame.height.takeIf { it > 0 } ?: fallbackMaxPx
        val height = minOf(desiredPx, maxPx)
        val current = binding.timelinePlayheadOverlay.layoutParams as? FrameLayout.LayoutParams
        if (current == null || current.height != height || current.gravity != Gravity.TOP) {
            binding.timelinePlayheadOverlay.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                height,
                Gravity.TOP
            )
        }
    }

    private fun updatePersistentTimelineChrome() {
        if (!::binding.isInitialized) return
        val duration = timelineIndex.totalDurationMs
        binding.timelineRuler.setState(duration, timelineZoom, timelineViewportStartMs)
        binding.timelinePlayheadOverlay.setState(
            positionMs = timelinePositionMs,
            durationMs = duration,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs
        )
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
        binding.timelineQuickSplitButton.isEnabled = canSplit
        binding.timelineQuickSplitButton.alpha = if (canSplit) 1f else 0.42f
        binding.deleteButton.isEnabled = selected != null && clips.size > 1
        binding.deleteButton.alpha = if (binding.deleteButton.isEnabled) 1f else 0.42f
        binding.duplicateButton.isEnabled = selected != null
        binding.duplicateButton.alpha = if (selected != null) 1f else 0.42f
        binding.timelineQuickCopyButton.isEnabled = selected != null
        binding.timelineQuickCopyButton.alpha = if (selected != null) 1f else 0.42f
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
            binding.selectionLabel.text = if (source.isBlank()) {
                "Video $number"
            } else {
                source
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
        binding.addClipButton.text = if (addingMedia) "Adding" else "Add"
        binding.timelineQuickAddButton.isEnabled = !addingMedia
        binding.timelineQuickAddButton.alpha = if (addingMedia) 0.5f else 1f
        binding.timelineQuickAddButton.text = if (addingMedia) "Adding" else "Add"
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

    private fun cycleSelectedAudioRole() {
        mutateSelectedAudio(AudioTimelineEditor::cycleRole)
    }

    private fun cycleSelectedAudioPan() {
        mutateSelectedAudio(AudioTimelineEditor::cyclePan)
    }

    private fun cycleSelectedAudioDucking() {
        mutateSelectedAudio(AudioTimelineEditor::cycleDucking)
    }

    private fun mutateSelectedAudio(change: (AudioClip) -> AudioClip) {
        val id = selectedAudioClipId ?: return
        val index = audioClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = audioClips[index]
        val edited = change(current)
        if (edited == current) return
        val before = snapshot()
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
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
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
            binding.audioFadeOutButton,
            binding.audioRoleButton,
            binding.audioPanButton,
            binding.audioDuckButton
        ).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }

        if (selected == null) {
            binding.audioSelectionLabel.text = if (audioClips.isEmpty()) "No audio · add music or sound" else "Tap an audio clip to select"
            binding.audioMuteButton.text = "Mute"
            binding.audioRoleButton.text = "Role music"
            binding.audioPanButton.text = "Pan center"
            binding.audioDuckButton.text = "Duck 55%"
            binding.audioSplitButton.isEnabled = false
            binding.audioSplitButton.alpha = 0.38f
            return
        }

        val asset = audioAssets.firstOrNull { it.id == selected.assetId }
        val name = asset?.displayName?.substringBeforeLast('.')?.take(20).orEmpty().ifBlank { "Audio" }
        val volumePercent = (selected.volume * 100).roundToInt()
        val fadeIn = String.format("%.1f", selected.fadeInMs / 1000f)
        val fadeOut = String.format("%.1f", selected.fadeOutMs / 1000f)
        val role = selected.role.name.lowercase()
        val panLabel = when {
            selected.pan < -0.15f -> "L ${(-selected.pan * 100).roundToInt()}%"
            selected.pan > 0.15f -> "R ${(selected.pan * 100).roundToInt()}%"
            else -> "center"
        }
        binding.audioSelectionLabel.text = "$name · $role · $volumePercent% · pan $panLabel · in ${fadeIn}s · out ${fadeOut}s${if (selected.muted) " · muted" else ""}"
        binding.audioMuteButton.text = if (selected.muted) "Unmute" else "Mute"
        binding.audioRoleButton.text = "Role $role"
        binding.audioPanButton.text = "Pan $panLabel"
        binding.audioDuckButton.text = if (selected.role.name == "MUSIC") "Duck ${(selected.duckingAmount * 100).roundToInt()}%" else "Duck → music"
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
            updateMaskChromaToolbar()
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
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
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
                style = TextPresetCatalog.style(TextPreset.CLASSIC),
                transform = TextTransform(),
                preset = TextPreset.CLASSIC
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
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
    }

    private fun updateTextUi() {
        val selected = textClips.firstOrNull { it.id == selectedTextClipId }
        val enabled = selected != null
        listOf(binding.editTextButton, binding.textBackButton, binding.textFrontButton, binding.textDeleteButton).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }
        val textLocalMs = selected?.let { (timelinePositionMs - it.timelineStartMs).coerceIn(0, it.durationMs) } ?: 0
        binding.textToolbar.setState(selected, textLocalMs)
        if (selected == null) {
            binding.textSelectionLabel.text = if (textClips.isEmpty()) "No text · add a title or caption" else "Tap a text layer to select"
            return
        }
        val preview = selected.text.replace('\n', ' ').take(26)
        binding.textSelectionLabel.text = "Text · $preview · T${selected.zIndex + 1} · ${selected.style.fontFamily.name.lowercase()} · ${selected.animation.kind.name.lowercase()}"
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
        captionSegments = sanitizeCaptionSegments(captionSegments)
        if (selectedCaptionSegmentId != null && captionSegments.none { it.id == selectedCaptionSegmentId }) selectedCaptionSegmentId = null
        renderCaptionState()
        updateCaptionUi()
        updateVisualToolbar()
        updateTimingToolbar()
        saveProject()
        updateHistoryUi()
    }

    private fun showCaptionDialog(existing: CaptionSegment?) {
        if (timelineIndex.totalDurationMs < CaptionTimelineEditor.MIN_DURATION_MS) {
            binding.captionSelectionLabel.text = "Add a video clip before adding captions"
            return
        }
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty())
            hint = "Caption text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            setPadding(
                (20 * resources.displayMetrics.density).roundToInt(),
                (12 * resources.displayMetrics.density).roundToInt(),
                (20 * resources.displayMetrics.density).roundToInt(),
                (12 * resources.displayMetrics.density).roundToInt()
            )
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add caption" else "Edit caption")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                upsertCaptionSegment(existing, input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun upsertCaptionSegment(existing: CaptionSegment?, rawText: String) {
        val value = rawText.trim().take(CaptionTimelineEditor.MAX_TEXT_LENGTH)
        if (value.isBlank()) return
        val total = timelineIndex.totalDurationMs
        if (total < CaptionTimelineEditor.MIN_DURATION_MS) return
        val before = snapshot()
        if (existing == null) {
            val latestStart = (total - CaptionTimelineEditor.MIN_DURATION_MS).coerceAtLeast(0)
            val start = timelinePositionMs.coerceIn(0, latestStart)
            val duration = minOf(DEFAULT_CAPTION_DURATION_MS, total - start)
                .coerceAtLeast(CaptionTimelineEditor.MIN_DURATION_MS)
            val segment = CaptionSegment(
                id = UUID.randomUUID().toString(),
                text = value,
                timelineStartMs = start,
                durationMs = duration,
                preset = CaptionPreset.BOXED
            )
            captionSegments = sanitizeCaptionSegments(captionSegments + segment)
            selectedCaptionSegmentId = segment.id
        } else {
            val index = captionSegments.indexOfFirst { it.id == existing.id }
            if (index < 0) return
            val updated = captionSegments[index].copy(text = value)
            if (updated == captionSegments[index]) return
            captionSegments = captionSegments.toMutableList().apply { this[index] = updated }
            selectedCaptionSegmentId = updated.id
        }
        selectedTextClipId = null
        selectedOverlayClipId = null
        if (before != snapshot()) history.record(before)
        renderCaptionState()
        renderTextState()
        renderOverlayState()
        updateCaptionUi()
        updateTextUi()
        updateOverlayUi()
        saveProject()
        updateHistoryUi()
    }

    private fun sanitizeCaptionSegments(input: List<CaptionSegment>): List<CaptionSegment> {
        return CaptionTimelineEditor.normalizeAll(input, clips.sumOf { it.durationMs }.coerceAtLeast(0))
    }

    private fun renderCaptionState() {
        binding.captionTimeline.setState(
            segments = captionSegments,
            selectedSegmentId = selectedCaptionSegmentId,
            durationMs = timelineIndex.totalDurationMs,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs,
            positionMs = timelinePositionMs
        )
        if (::captionPreview.isInitialized) {
            captionPreview.setTimeline(captionSegments)
            captionPreview.render(timelinePositionMs, selectedCaptionSegmentId)
        }
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
    }

    private fun updateCaptionUi() {
        val selected = captionSegments.firstOrNull { it.id == selectedCaptionSegmentId }
        val hasCaptions = captionSegments.isNotEmpty()
        val selectedEnabled = selected != null
        listOf(
            binding.editCaptionButton,
            binding.splitCaptionButton,
            binding.captionStyleButton,
            binding.deleteCaptionButton
        ).forEach { view ->
            view.isEnabled = selectedEnabled
            view.alpha = if (selectedEnabled) 1f else 0.38f
        }
        binding.exportSrtButton.isEnabled = hasCaptions
        binding.exportSrtButton.alpha = if (hasCaptions) 1f else 0.38f
        binding.captionShiftBackButton.isEnabled = hasCaptions
        binding.captionShiftBackButton.alpha = if (hasCaptions) 1f else 0.38f
        binding.captionShiftForwardButton.isEnabled = hasCaptions
        binding.captionShiftForwardButton.alpha = if (hasCaptions) 1f else 0.38f
        binding.captionToolbar.setState(selected)
        binding.captionStyleButton.text = selected?.let { "Style ${it.preset.name.lowercase()}" } ?: "Style"
        if (selected == null) {
            binding.captionSelectionLabel.text = if (hasCaptions) {
                "${captionSegments.size} captions · tap a segment to edit"
            } else {
                "No captions · add or import SRT"
            }
            return
        }
        val preview = selected.text.replace('\n', ' ').take(30)
        binding.captionSelectionLabel.text = "Caption · $preview · ${formatDuration(selected.durationMs)} · ${selected.preset.name.lowercase()} · ${selected.fontFamily.name.lowercase()} · ${selected.animation.kind.name.lowercase()}"
    }

    private fun finishCaptionGestureEdit() {
        val before = pendingCaptionEditSnapshot
        pendingCaptionEditSnapshot = null
        captionSegments = sanitizeCaptionSegments(captionSegments)
        if (before != null && before != snapshot()) history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun splitSelectedCaptionAtPlayhead() {
        val id = selectedCaptionSegmentId ?: return
        val index = captionSegments.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = captionSegments[index]
        val split = CaptionTimelineEditor.split(current, timelinePositionMs) ?: run {
            binding.captionSelectionLabel.text = "Move playhead inside caption before splitting"
            return
        }
        val before = snapshot()
        val right = split.second.copy(id = UUID.randomUUID().toString())
        captionSegments = captionSegments.toMutableList().apply {
            this[index] = split.first
            add(index + 1, right)
        }
        captionSegments = sanitizeCaptionSegments(captionSegments)
        selectedCaptionSegmentId = right.id
        history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun handleCaptionAction(action: CaptionToolbarView.Action) {
        val id = selectedCaptionSegmentId ?: return
        val index = captionSegments.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = captionSegments[index]
        val updated = when (action) {
            CaptionToolbarView.Action.FONT_FAMILY -> {
                val values = TextFontFamily.values()
                val at = values.indexOf(current.fontFamily).coerceAtLeast(0)
                current.copy(fontFamily = values[(at + 1) % values.size])
            }
            CaptionToolbarView.Action.ANIMATION -> {
                val values = TextAnimationKind.values()
                val at = values.indexOf(current.animation.kind).coerceAtLeast(0)
                current.copy(animation = current.animation.copy(kind = values[(at + 1) % values.size]))
            }
        }
        if (updated == current) return
        val before = snapshot()
        captionSegments = captionSegments.toMutableList().apply { this[index] = updated }
        captionSegments = sanitizeCaptionSegments(captionSegments)
        history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun cycleSelectedCaptionStyle() {
        val id = selectedCaptionSegmentId ?: return
        val index = captionSegments.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = captionSegments[index]
        val values = CaptionPreset.values()
        val next = values[(values.indexOf(current.preset).coerceAtLeast(0) + 1) % values.size]
        val before = snapshot()
        captionSegments = captionSegments.toMutableList().apply { this[index] = current.copy(preset = next) }
        history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun deleteSelectedCaption() {
        val id = selectedCaptionSegmentId ?: return
        if (captionSegments.none { it.id == id }) return
        val before = snapshot()
        val deletedIndex = captionSegments.indexOfFirst { it.id == id }
        captionSegments = captionSegments.filterNot { it.id == id }
        selectedCaptionSegmentId = captionSegments.getOrNull(deletedIndex.coerceAtMost(captionSegments.lastIndex))?.id
            ?: captionSegments.lastOrNull()?.id
        history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun shiftAllCaptions(deltaMs: Int) {
        if (captionSegments.isEmpty()) return
        val before = snapshot()
        val shifted = CaptionTimelineEditor.shiftAll(captionSegments, deltaMs, timelineIndex.totalDurationMs)
        if (shifted == captionSegments) return
        captionSegments = shifted
        history.record(before)
        renderCaptionState()
        updateCaptionUi()
        saveProject()
        updateHistoryUi()
    }

    private fun importSrt(uri: Uri) {
        if (timelineIndex.totalDurationMs < CaptionTimelineEditor.MIN_DURATION_MS) {
            binding.captionSelectionLabel.text = "Add a video clip before importing subtitles"
            return
        }
        val raw = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            binding.captionSelectionLabel.text = "Could not read SRT file"
            return
        }
        val parsed = sanitizeCaptionSegments(SrtCodec.parse(raw))
        if (parsed.isEmpty()) {
            binding.captionSelectionLabel.text = "No valid subtitle cues found"
            return
        }
        val before = snapshot()
        captionSegments = parsed
        selectedCaptionSegmentId = parsed.firstOrNull()?.id
        selectedTextClipId = null
        selectedOverlayClipId = null
        if (before != snapshot()) history.record(before)
        renderCaptionState()
        renderTextState()
        renderOverlayState()
        updateCaptionUi()
        updateTextUi()
        updateOverlayUi()
        saveProject()
        updateHistoryUi()
        binding.captionSelectionLabel.text = "Imported ${captionSegments.size} captions · tap a segment to edit"
    }

    private fun exportSrt(uri: Uri) {
        if (captionSegments.isEmpty()) return
        val output = SrtCodec.serialize(captionSegments)
        val success = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(output)
                writer.flush()
            } ?: error("No output stream")
        }.isSuccess
        binding.captionSelectionLabel.text = if (success) {
            "Exported ${captionSegments.size} captions to SRT"
        } else {
            "Could not export SRT"
        }
    }

    private fun handleEffectAction(action: EffectToolbarView.Action) {
        when (action) {
            EffectToolbarView.Action.ADD_EFFECT -> addEffectAtPlayhead()
            EffectToolbarView.Action.EFFECT_KIND -> cycleSelectedEffectKind()
            EffectToolbarView.Action.INTENSITY_DOWN -> adjustSelectedEffectIntensity(-0.1f)
            EffectToolbarView.Action.INTENSITY_UP -> adjustSelectedEffectIntensity(0.1f)
            EffectToolbarView.Action.DELETE_EFFECT -> deleteSelectedEffect()
            EffectToolbarView.Action.TRANSITION_KIND -> cycleSelectedTransitionKind()
            EffectToolbarView.Action.TRANSITION_DURATION -> cycleSelectedTransitionDuration()
        }
    }

    private fun addEffectAtPlayhead() {
        val total = timelineIndex.totalDurationMs
        if (total < EffectTimelineEditor.MIN_DURATION_MS) return
        val before = snapshot()
        val start = timelinePositionMs.coerceIn(0, (total - EffectTimelineEditor.MIN_DURATION_MS).coerceAtLeast(0))
        val duration = EffectTimelineEditor.DEFAULT_DURATION_MS.coerceAtMost(total - start).coerceAtLeast(EffectTimelineEditor.MIN_DURATION_MS)
        val effect = EffectClip(
            id = UUID.randomUUID().toString(),
            timelineStartMs = start,
            durationMs = duration,
            kind = VideoEffectKind.WARM,
            intensity = 0.6f
        )
        effectClips = (effectClips + effect).sortedBy { it.timelineStartMs }
        selectedEffectClipId = effect.id
        commitMutation(before)
    }

    private fun cycleSelectedEffectKind() {
        val id = selectedEffectClipId ?: return
        val index = effectClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = snapshot()
        val values = VideoEffectKind.values()
        val current = effectClips[index]
        val next = values[(current.kind.ordinal + 1) % values.size]
        effectClips = effectClips.toMutableList().apply { this[index] = current.copy(kind = next) }
        commitMutation(before)
    }

    private fun adjustSelectedEffectIntensity(delta: Float) {
        val id = selectedEffectClipId ?: return
        val index = effectClips.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = effectClips[index]
        val next = (current.intensity + delta).coerceIn(0.1f, 1f)
        if (kotlin.math.abs(next - current.intensity) < 0.001f) return
        val before = snapshot()
        effectClips = effectClips.toMutableList().apply { this[index] = current.copy(intensity = next) }
        commitMutation(before)
    }

    private fun deleteSelectedEffect() {
        val id = selectedEffectClipId ?: return
        if (effectClips.none { it.id == id }) return
        val before = snapshot()
        effectClips = effectClips.filterNot { it.id == id }
        selectedEffectClipId = null
        commitMutation(before)
    }

    private fun cycleSelectedTransitionKind() {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0 || index >= clips.lastIndex) return
        val before = snapshot()
        val current = clips[index]
        val values = TransitionKind.values()
        val next = values[(current.transitionOut.kind.ordinal + 1) % values.size]
        clips = clips.toMutableList().apply {
            this[index] = current.copy(transitionOut = current.transitionOut.copy(kind = next))
        }
        commitMutation(before)
    }

    private fun cycleSelectedTransitionDuration() {
        val id = selectedClipId ?: return
        val index = clips.indexOfFirst { it.id == id }
        if (index < 0 || index >= clips.lastIndex) return
        val before = snapshot()
        val current = clips[index]
        val choices = intArrayOf(300, 500, 800, 1_200)
        val next = choices.firstOrNull { it > current.transitionOut.durationMs } ?: choices.first()
        clips = clips.toMutableList().apply {
            this[index] = current.copy(transitionOut = current.transitionOut.copy(durationMs = next))
        }
        commitMutation(before)
    }

    private fun sanitizeEffectClips(input: List<EffectClip>): List<EffectClip> =
        EffectTimelineEditor.normalizeAll(input, TimelineMath.totalDurationMs(clips))

    private fun renderEffectState() {
        if (!::binding.isInitialized) return
        binding.effectTimeline.setState(
            effects = effectClips,
            selectedEffectId = selectedEffectClipId,
            durationMs = timelineIndex.totalDurationMs,
            zoom = timelineZoom,
            viewportStartMs = timelineViewportStartMs,
            positionMs = timelinePositionMs
        )
        renderEffectPreview()
        updatePersistentTimelineLanes()
        updatePersistentTimelineChrome()
    }

    private fun renderEffectPreview() {
        if (!::binding.isInitialized) return
        binding.effectPreviewLayer.render(
            effects = EffectComposition.activeEffects(effectClips, timelinePositionMs),
            transitionFrame = EffectComposition.transitionFrame(clips, timelinePositionMs),
            timelinePositionMs = timelinePositionMs
        )
    }

    private fun updateEffectUi() {
        if (!::binding.isInitialized) return
        val effect = selectedEffectClipId?.let { id -> effectClips.firstOrNull { it.id == id } }
        val clip = selectedClipId?.let { id -> clips.firstOrNull { it.id == id } }
        val hasNext = clip != null && clips.indexOfFirst { it.id == clip.id } in 0 until clips.lastIndex
        binding.effectToolbar.setState(effect, clip, hasNext)
        binding.effectSelectionLabel.text = when {
            effect != null -> "FX ${effect.kind.name.lowercase().replace('_', ' ')} · ${(effect.intensity * 100f).roundToInt()}% · ${formatDuration(effect.durationMs)}"
            clip != null && hasNext -> "Transition ${clip.transitionOut.kind.name.lowercase().replace('_', ' ')} · ${clip.transitionOut.durationMs}ms"
            else -> "No effect · add a timed video effect"
        }
    }

    private fun finishEffectGestureEdit() {
        val before = pendingEffectEditSnapshot ?: return
        pendingEffectEditSnapshot = null
        effectClips = sanitizeEffectClips(effectClips)
        if (before != snapshot()) history.record(before)
        renderTimelineState()
        saveProject()
        updateHistoryUi()
    }

    private fun pruneUnusedAssets() {
        val used = clips.mapTo(mutableSetOf()) { it.assetId }
        assets = assets.filter { it.id in used }
    }

    private fun showExportOptions() {
        val activeExport = if (::exportTaskStore.isInitialized) exportTaskStore.read() else null
        if (activeExport?.state?.isActive == true) {
            if (activeExport.projectId == project.id) {
                exportProgressHiddenByUser = false
                showExportProgressDialog(activeExport.progress, activeExport.message)
                updateExportProgress(activeExport.progress, activeExport.message, activeExport.elapsedMs)
            } else {
                Toast.makeText(this, "Another Vedito export is already running", Toast.LENGTH_LONG).show()
            }
            return
        }
        saveProject()
        if (!ExportSupport.inspect(project).canExport) {
            Toast.makeText(this, "Add a video clip before exporting", Toast.LENGTH_SHORT).show()
            return
        }
        showExportSettingsDialog()
    }

    /** Only the supported settings are selectable; bitrate is the planner's read-only result. */
    private fun showExportSettingsDialog(initial: ExportSettings = ExportSettings()) {
        var settings = initial
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_export_settings, null)
        val title = view.findViewById<TextView>(R.id.exportProjectTitle)
        val meta = view.findViewById<TextView>(R.id.exportProjectMeta)
        val resolution = view.findViewById<TextView>(R.id.exportPresetValue)
        val fps = view.findViewById<TextView>(R.id.exportFpsValue)
        val codec = view.findViewById<TextView>(R.id.exportCodecValue)
        val audio = view.findViewById<TextView>(R.id.exportAudioValue)
        val dimensions = view.findViewById<TextView>(R.id.exportDimensionsValue)
        val bitrate = view.findViewById<TextView>(R.id.exportBitrateValue)
        val size = view.findViewById<TextView>(R.id.exportSizeValue)

        title.text = project.title.ifBlank { "Untitled project" }
        meta.text = "${formatDuration(timelineIndex.totalDurationMs)}  ·  ${clips.size} video clip(s)"
        fun refresh() {
            val plan = ExportPlanner.plan(project, settings)
            resolution.text = settings.preset.label
            fps.text = "${settings.frameRate} FPS"
            codec.text = settings.videoCodec.label.substringBefore('·').trim()
            audio.text = "${settings.audioBitrate / 1_000} kbps"
            dimensions.text = "${plan.width} × ${plan.height}  ·  ${plan.frameRate} FPS"
            bitrate.text = "Video ${formatExportMbps(plan.videoBitrate)} Mbps  ·  AAC ${plan.audioBitrate / 1_000} kbps"
            size.text = "Estimated file size  ~${formatExportBytes(plan.estimatedOutputBytes)}"
        }
        fun choose(titleText: String, labels: Array<String>, checked: Int, onChoose: (Int) -> Unit) {
            AlertDialog.Builder(this)
                .setTitle(titleText)
                .setSingleChoiceItems(labels, checked) { menu, index ->
                    menu.dismiss()
                    onChoose(index)
                    refresh()
                }
                .setNegativeButton("Back", null)
                .show()
        }
        view.findViewById<View>(R.id.exportPresetRow).setOnClickListener {
            val choices = ExportPreset.values()
            choose("Resolution", choices.map { it.label }.toTypedArray(), choices.indexOf(settings.preset)) {
                settings = settings.copy(preset = choices[it])
            }
        }
        view.findViewById<View>(R.id.exportFpsRow).setOnClickListener {
            val choices = intArrayOf(24, 30, 60)
            choose("Frame rate", choices.map { "$it FPS" }.toTypedArray(), choices.indexOf(settings.frameRate)) {
                settings = settings.copy(frameRate = choices[it])
            }
        }
        view.findViewById<View>(R.id.exportCodecRow).setOnClickListener {
            val choices = ExportVideoCodec.values()
            choose("Video codec", choices.map { it.label }.toTypedArray(), choices.indexOf(settings.videoCodec)) {
                settings = settings.copy(videoCodec = choices[it])
            }
        }
        view.findViewById<View>(R.id.exportAudioRow).setOnClickListener {
            val choices = intArrayOf(96_000, 128_000, 192_000, 256_000)
            choose("AAC audio quality", choices.map { "${it / 1_000} kbps" }.toTypedArray(), choices.indexOf(settings.audioBitrate)) {
                settings = settings.copy(audioBitrate = choices[it])
            }
        }
        refresh()
        val dialog = showExportBrandedDialog(view)
        view.findViewById<View>(R.id.exportClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.exportContinue).setOnClickListener {
            dialog.dismiss()
            showExportPreflight(settings)
        }
    }

    private fun showExportPreflight(settings: ExportSettings) {
        saveProject()
        val support = ExportSupport.inspect(project, settings)
        val requestedPlan = ExportPlanner.plan(project, settings)
        val device = ExportCapabilityProbe.inspect(requestedPlan)
        val effectivePlan = requestedPlan.copy(
            videoBitrate = device.selection?.effectiveBitrate ?: requestedPlan.videoBitrate
        )
        val recovery = exportEngine.inspectRecovery(project, settings)
        val warnings = support.warnings + device.warnings + recovery.warnings
        val canStart = support.canExport && device.canEncode && recovery.canStart
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_export_review, null)
        view.findViewById<TextView>(R.id.exportReviewProfile).text =
            "${effectivePlan.width} × ${effectivePlan.height}  ·  ${effectivePlan.frameRate} FPS"
        view.findViewById<TextView>(R.id.exportReviewDetails).text =
            "${effectivePlan.videoCodec.label}\n" +
                "Video ${formatExportMbps(effectivePlan.videoBitrate)} Mbps  ·  AAC ${effectivePlan.audioBitrate / 1_000} kbps\n" +
                "Estimated file ~${formatExportBytes(effectivePlan.estimatedOutputBytes)}"
        view.findViewById<TextView>(R.id.exportReviewBudget).text =
            "Recovery working budget ~${formatExportBytes(recovery.requiredCacheBytes)}" +
                if (recovery.completedSegments > 0)
                    "\nResume ready: ${recovery.completedSegments}/${recovery.totalSegments} segments" +
                        "  ·  extra free ~${formatExportBytes(recovery.requiredAdditionalCacheBytes)}"
                else ""
        val failure = when {
            !support.canExport -> "This project cannot be exported with these settings."
            !device.canEncode -> device.failureReason ?: "No compatible encoder was found."
            !recovery.canStart -> recovery.failureReason ?: "Local export preflight failed."
            else -> null
        }
        view.findViewById<TextView>(R.id.exportReviewStatus).apply {
            text = if (canStart) "✓  Ready to save" else "Export is not available for this profile"
            setTextColor(getColor(if (canStart) R.color.vedito_success else R.color.vedito_error))
        }
        view.findViewById<TextView>(R.id.exportReviewNotes).apply {
            val notes = buildList {
                if (failure != null) add(failure)
                device.selection?.let { add("Encoder: ${it.codecName}" + if (it.hardwareAccelerated) " · hardware" else " · software") }
                addAll(warnings)
            }
            text = notes.joinToString("\n") { "• $it" }
            visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        }
        val dialog = showExportBrandedDialog(view)
        view.findViewById<View>(R.id.exportReviewBack).setOnClickListener {
            dialog.dismiss()
            showExportSettingsDialog(settings)
        }
        view.findViewById<TextView>(R.id.exportReviewSave).apply {
            visibility = if (canStart) View.VISIBLE else View.GONE
            setOnClickListener {
                dialog.dismiss()
                launchVideoExportDocument(settings)
            }
        }
    }

    private fun showExportBrandedDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = minOf(resources.displayMetrics.widthPixels - (24f * resources.displayMetrics.density).roundToInt(),
                (440f * resources.displayMetrics.density).roundToInt())
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        // A short phone can scroll the whole sheet; never obscure action rows off-screen.
        view.post {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).roundToInt()
            if (view.height > maxHeight) {
                view.layoutParams = view.layoutParams.apply { height = maxHeight }
            }
        }
        return dialog
    }

    private fun formatExportMbps(bitrate: Int): String {
        val mbps = bitrate / 1_000_000f
        return if (mbps >= 10f) String.format("%.0f", mbps) else String.format("%.1f", mbps)
    }

    private fun formatExportBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mib = bytes / (1024f * 1024f)
        return if (mib >= 1024f) String.format("%.2f GB", mib / 1024f) else String.format("%.0f MB", mib)
    }

    private fun launchVideoExportDocument(settings: ExportSettings) {
        saveProject()
        pendingExportSettings = settings
        val safeTitle = project.title
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifBlank { "vedito" }
        exportVideoPicker.launch("$safeTitle-${settings.preset.name.lowercase()}-${settings.frameRate}fps-${settings.videoCodec.name.lowercase()}.mp4")
    }

    private fun startVideoExport(uri: Uri, settings: ExportSettings) {
        saveProject()
        previewPlayer.pause()
        audioPlayback.pause()

        val existing = exportTaskStore.read()
        if (existing?.state?.isActive == true) {
            if (existing.projectId == project.id) {
                showExportProgressDialog(existing.progress, existing.message)
            } else {
                Toast.makeText(this, "Another Vedito export is already running", Toast.LENGTH_LONG).show()
            }
            return
        }

        val started = runCatching {
            ExportForegroundService.startExport(
                context = this,
                projectId = project.id,
                projectTitle = project.title,
                outputUri = uri,
                settings = settings
            )
        }
        if (started.isSuccess) {
            showExportProgressDialog(0, "Preparing foreground export")
            binding.exportVideoButton.isEnabled = true
            binding.exportVideoButton.alpha = 1f
            binding.exportVideoButton.text = "Progress"
        } else {
            val message = started.exceptionOrNull()?.message ?: "Unable to start foreground export"
            exportTaskStore.read()?.let { current ->
                if (current.projectId == project.id && current.state.isActive) {
                    exportTaskStore.finishFailure(current.taskId, message)
                }
            }
            finishExportUi()
            AlertDialog.Builder(this)
                .setTitle("Export could not start")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showExportProgressDialog(initialProgress: Int = 0, initialMessage: String = "Preparing foreground export") {
        exportProgressHiddenByUser = false
        exportDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_export_progress, null)
        exportProgressRing = view.findViewById(R.id.exportProgressRing)
        exportProgressPercent = view.findViewById(R.id.exportProgressPercent)
        exportProgressLabel = view.findViewById(R.id.exportProgressStage)
        exportProgressElapsed = view.findViewById(R.id.exportProgressElapsed)
        val dialog = showExportBrandedDialog(view)
        dialog.setCancelable(false)
        exportDialog = dialog
        updateExportProgress(initialProgress, initialMessage, 0L)
        view.findViewById<View>(R.id.exportProgressBackground).setOnClickListener {
            exportProgressHiddenByUser = true
            dialog.dismiss()
            exportDialog = null
            exportProgressRing = null
            exportProgressPercent = null
            exportProgressLabel = null
            exportProgressElapsed = null
        }
        view.findViewById<View>(R.id.exportProgressCancel).setOnClickListener {
            exportProgressHiddenByUser = true
            dialog.dismiss()
            exportDialog = null
            exportProgressRing = null
            exportProgressPercent = null
            exportProgressLabel = null
            exportProgressElapsed = null
            ExportForegroundService.cancel(this)
            Toast.makeText(this, "Cancelling export…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateExportProgress(progress: Int, message: String, elapsedMs: Long) {
        val percent = progress.coerceIn(0, 100)
        exportProgressRing?.progressPercent = percent
        exportProgressPercent?.text = "$percent%"
        exportProgressLabel?.text = message.ifBlank { "Rendering video" }
        exportProgressElapsed?.text = "Elapsed ${formatDuration(elapsedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}  ·  ETA unavailable"
    }

    private fun syncExportUiFromStore() {
        if (!::exportTaskStore.isInitialized || !::project.isInitialized || !::binding.isInitialized) return
        val snapshot = exportTaskStore.read() ?: run {
            finishExportUi()
            return
        }
        if (snapshot.projectId != project.id) return

        when {
            snapshot.state.isActive -> {
                binding.exportVideoButton.isEnabled = true
                binding.exportVideoButton.alpha = 1f
                binding.exportVideoButton.text = "Progress"
                if (!exportProgressHiddenByUser && exportDialog == null) {
                    showExportProgressDialog(snapshot.progress, snapshot.message)
                }
                if (exportDialog != null) {
                    updateExportProgress(snapshot.progress, snapshot.message, snapshot.elapsedMs)
                }
            }
            snapshot.state.isTerminal -> {
                finishExportUi()
                if (snapshot.updatedAtMs <= lastHandledExportTerminalAt) return
                lastHandledExportTerminalAt = snapshot.updatedAtMs
                when (snapshot.state) {
                    ExportTaskStore.State.SUCCEEDED -> showForegroundExportCompleted(snapshot)
                    ExportTaskStore.State.CANCELLED -> Toast.makeText(
                        this,
                        "Export cancelled · completed checkpoints kept for retry",
                        Toast.LENGTH_LONG
                    ).show()
                    ExportTaskStore.State.TIMED_OUT -> AlertDialog.Builder(this)
                        .setTitle("Export paused")
                        .setMessage(snapshot.errorMessage ?: snapshot.message)
                        .setPositiveButton("OK", null)
                        .show()
                    ExportTaskStore.State.FAILED -> AlertDialog.Builder(this)
                        .setTitle("Export failed")
                        .setMessage(snapshot.errorMessage ?: snapshot.message)
                        .setPositiveButton("OK", null)
                        .show()
                    else -> Unit
                }
                exportTaskStore.clearTerminal(snapshot.taskId)
            }
        }
    }

    private fun showForegroundExportCompleted(snapshot: ExportTaskStore.Snapshot) {
        val seconds = (snapshot.elapsedMs / 1_000f).coerceAtLeast(0f)
        val settings = snapshot.settings
        AlertDialog.Builder(this)
            .setTitle("Export complete")
            .setMessage("${settings.preset.label} · ${settings.frameRate} fps · ${settings.videoCodec.label.substringBefore('·').trim()} · ${String.format("%.1f", seconds)}s render time")
            .setNegativeButton("Done", null)
            .setPositiveButton("Open") { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(snapshot.output, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { startActivity(viewIntent) }
                    .onFailure { Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show() }
            }
            .show()
    }

    private fun finishExportUi() {
        exportDialog?.dismiss()
        exportDialog = null
        exportProgressLabel = null
        exportProgressPercent = null
        exportProgressElapsed = null
        exportProgressRing = null
        exportProgressHiddenByUser = false
        binding.exportVideoButton.isEnabled = true
        binding.exportVideoButton.alpha = 1f
        binding.exportVideoButton.text = "Export"
        binding.root.keepScreenOn = false
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
            captionSegments = captionSegments,
            effectClips = effectClips,
            canvasSettings = canvasSettings,
            playheadMs = timelinePositionMs,
            selectedClipId = selectedClipId,
            selectedAudioClipId = selectedAudioClipId,
            selectedOverlayClipId = selectedOverlayClipId,
            selectedTextClipId = selectedTextClipId,
            selectedCaptionSegmentId = selectedCaptionSegmentId,
            selectedEffectClipId = selectedEffectClipId,
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

    private val MASK_FEATHER_PRESETS = floatArrayOf(0f, 0.05f, 0.10f, 0.18f, 0.25f)
    private val CHROMA_TOLERANCE_PRESETS = floatArrayOf(0.12f, 0.18f, 0.22f, 0.30f, 0.40f)
    private val CHROMA_SOFTNESS_PRESETS = floatArrayOf(0.04f, 0.08f, 0.12f, 0.20f, 0.30f)
    private val CHROMA_SPILL_PRESETS = floatArrayOf(0f, 0.15f, 0.30f, 0.50f, 0.75f)
    private val STABILIZATION_STRENGTH_PRESETS = floatArrayOf(0.35f, 0.50f, 0.65f, 0.80f, 1.0f)
    private val COLOR_FADE_PRESETS = floatArrayOf(0f, 0.12f, 0.25f, 0.40f, 0.60f)
    private val COLOR_LUT_STRENGTH_PRESETS = floatArrayOf(0.25f, 0.50f, 0.75f, 1f)
    private val CHROMA_KEY_COLORS = intArrayOf(
        MaskChromaToolbarView.KEY_GREEN,
        MaskChromaToolbarView.KEY_BLUE,
        MaskChromaToolbarView.KEY_MAGENTA
    )

    companion object {
        private const val MASK_MOVE_STEP = 0.05f
        private const val MASK_SIZE_STEP = 0.10f
        private const val COLOR_EXPOSURE_STEP = 0.25f
        private const val COLOR_ADJUST_STEP = 0.10f
        private const val COLOR_HUE_STEP = 15f
        private const val COLOR_HSL_STEP = 0.10f
        const val EXTRA_PROJECT_ID = "vedito.project_id"
        private const val MAX_ADDED_VIDEOS = 12
        private const val MAX_ADDED_AUDIO = 12
        private const val MAX_ADDED_OVERLAYS = 8
        private const val DEFAULT_IMAGE_OVERLAY_MS = 3_000
        private const val DEFAULT_TEXT_DURATION_MS = 3_000
        private const val DEFAULT_CAPTION_DURATION_MS = 2_000
        private const val CAPTION_SHIFT_STEP_MS = 250
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
