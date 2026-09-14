package com.vedito.app.core.timeline

import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.MediaAsset
import java.util.ArrayDeque

/**
 * Small bounded history designed for destructive timeline edits.
 * Runtime-only by design: projects persist their latest committed state,
 * while undo/redo starts fresh after reopening a project.
 */
class EditorHistory(private val capacity: Int = 40) {
    data class Snapshot(
        val assets: List<MediaAsset>,
        val clips: List<Clip>,
        val selectedClipId: String?,
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
