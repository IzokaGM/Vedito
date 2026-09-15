package com.vedito.app.core.timeline

import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.CanvasSettings
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.TextClip
import java.util.ArrayDeque

/**
 * Small bounded history designed for destructive timeline and visual edits.
 * Runtime-only by design: projects persist their latest committed state,
 * while undo/redo starts fresh after reopening a project.
 */
class EditorHistory(private val capacity: Int = 40) {
    data class Snapshot(
        val assets: List<MediaAsset>,
        val clips: List<Clip>,
        val audioAssets: List<AudioAsset>,
        val audioClips: List<AudioClip>,
        val overlayAssets: List<OverlayAsset>,
        val overlayClips: List<OverlayClip>,
        val textClips: List<TextClip>,
        val canvasSettings: CanvasSettings,
        val selectedClipId: String?,
        val selectedAudioClipId: String?,
        val selectedOverlayClipId: String?,
        val selectedTextClipId: String?,
        val playheadMs: Int
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(before: Snapshot) {
        if (undoStack.peekLast() == before) return
        undoStack.addLast(before)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(current: Snapshot): Snapshot? {
        val target = undoStack.pollLast() ?: return null
        redoStack.addLast(current)
        while (redoStack.size > capacity) redoStack.removeFirst()
        return target
    }

    fun redo(current: Snapshot): Snapshot? {
        val target = redoStack.pollLast() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > capacity) undoStack.removeFirst()
        return target
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
