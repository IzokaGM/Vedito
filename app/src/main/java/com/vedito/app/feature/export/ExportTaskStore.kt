package com.vedito.app.feature.export

import android.content.Context
import android.net.Uri
import com.vedito.app.core.export.ExportPreset
import com.vedito.app.core.export.ExportSettings
import com.vedito.app.core.export.ExportVideoCodec

/**
 * Small process-independent snapshot of the one foreground export Vedito may run at a time.
 * Recovery media itself remains owned by [ExportRecoveryStore]; this store only reconnects UI,
 * notification actions and service restarts to the active export request.
 */
class ExportTaskStore(context: Context) {
    enum class State {
        IDLE,
        STARTING,
        RUNNING,
        SUCCEEDED,
        CANCELLED,
        FAILED,
        TIMED_OUT;

        val isActive: Boolean get() = this == STARTING || this == RUNNING
        val isTerminal: Boolean get() = this == SUCCEEDED || this == CANCELLED || this == FAILED || this == TIMED_OUT
    }

    data class Snapshot(
        val taskId: String,
        val projectId: String,
        val projectTitle: String,
        val outputUri: String,
        val settings: ExportSettings,
        val state: State,
        val progress: Int,
        val message: String,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val elapsedMs: Long,
        val errorMessage: String?
    ) {
        val output: Uri get() = Uri.parse(outputUri)
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun begin(
        taskId: String,
        projectId: String,
        projectTitle: String,
        outputUri: Uri,
        settings: ExportSettings,
        nowMs: Long = System.currentTimeMillis()
    ): Snapshot {
        val snapshot = Snapshot(
            taskId = taskId,
            projectId = projectId,
            projectTitle = projectTitle,
            outputUri = outputUri.toString(),
            settings = settings,
            state = State.STARTING,
            progress = 0,
            message = "Preparing foreground export",
            startedAtMs = nowMs,
            updatedAtMs = nowMs,
            elapsedMs = 0L,
            errorMessage = null
        )
        write(snapshot, durable = true)
        return snapshot
    }

    @Synchronized
    fun updateProgress(taskId: String, progress: Int, message: String): Snapshot? {
        val current = read() ?: return null
        if (current.taskId != taskId || current.state.isTerminal) return current
        val now = System.currentTimeMillis()
        val next = current.copy(
            state = State.RUNNING,
            progress = progress.coerceIn(0, 99),
            message = message,
            updatedAtMs = now
        )
        if (next.progress != current.progress || now - current.updatedAtMs >= PROGRESS_PERSIST_INTERVAL_MS) {
            write(next, durable = false)
        }
        return next
    }

    @Synchronized
    fun finishSuccess(taskId: String, elapsedMs: Long): Snapshot? = finish(
        taskId = taskId,
        state = State.SUCCEEDED,
        progress = 100,
        message = "Export complete",
        elapsedMs = elapsedMs,
        errorMessage = null
    )

    @Synchronized
    fun finishCancelled(taskId: String): Snapshot? = finish(
        taskId = taskId,
        state = State.CANCELLED,
        progress = read()?.progress ?: 0,
        message = "Export cancelled · completed checkpoints kept",
        elapsedMs = elapsedSinceStart(),
        errorMessage = null
    )

    @Synchronized
    fun finishFailure(taskId: String, message: String): Snapshot? = finish(
        taskId = taskId,
        state = State.FAILED,
        progress = read()?.progress ?: 0,
        message = "Export failed",
        elapsedMs = elapsedSinceStart(),
        errorMessage = message
    )

    @Synchronized
    fun finishTimeout(taskId: String): Snapshot? = finish(
        taskId = taskId,
        state = State.TIMED_OUT,
        progress = read()?.progress ?: 0,
        message = "Export paused by Android time limit · checkpoints kept",
        elapsedMs = elapsedSinceStart(),
        errorMessage = "Android stopped foreground media processing after its allowed background time. Retry from Vedito to resume completed checkpoints."
    )

    @Synchronized
    fun read(): Snapshot? {
        val taskId = prefs.getString(KEY_TASK_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val projectId = prefs.getString(KEY_PROJECT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val outputUri = prefs.getString(KEY_OUTPUT_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val preset = enumOrDefault(prefs.getString(KEY_PRESET, null), ExportPreset.HD_720)
        val codec = enumOrDefault(prefs.getString(KEY_CODEC, null), ExportVideoCodec.AVC)
        val state = enumOrDefault(prefs.getString(KEY_STATE, null), State.IDLE)
        return Snapshot(
            taskId = taskId,
            projectId = projectId,
            projectTitle = prefs.getString(KEY_PROJECT_TITLE, null).orEmpty().ifBlank { "Vedito project" },
            outputUri = outputUri,
            settings = ExportSettings(
                preset = preset,
                frameRate = prefs.getInt(KEY_FRAME_RATE, 30).coerceIn(24, 60),
                videoCodec = codec,
                audioSampleRate = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, 48_000).coerceIn(44_100, 48_000),
                audioBitrate = prefs.getInt(KEY_AUDIO_BITRATE, 128_000).coerceIn(96_000, 256_000)
            ),
            state = state,
            progress = prefs.getInt(KEY_PROGRESS, 0).coerceIn(0, 100),
            message = prefs.getString(KEY_MESSAGE, null).orEmpty(),
            startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            elapsedMs = prefs.getLong(KEY_ELAPSED_MS, 0L).coerceAtLeast(0L),
            errorMessage = prefs.getString(KEY_ERROR, null)
        )
    }

    @Synchronized
    fun clearTerminal(taskId: String) {
        val current = read() ?: return
        if (current.taskId == taskId && current.state.isTerminal) prefs.edit().clear().apply()
    }

    private fun finish(
        taskId: String,
        state: State,
        progress: Int,
        message: String,
        elapsedMs: Long,
        errorMessage: String?
    ): Snapshot? {
        val current = read() ?: return null
        if (current.taskId != taskId) return current
        return current.copy(
            state = state,
            progress = progress.coerceIn(0, 100),
            message = message,
            updatedAtMs = System.currentTimeMillis(),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            errorMessage = errorMessage
        ).also { write(it, durable = true) }
    }

    private fun elapsedSinceStart(): Long {
        val started = read()?.startedAtMs ?: 0L
        return if (started > 0L) (System.currentTimeMillis() - started).coerceAtLeast(0L) else 0L
    }

    private fun write(snapshot: Snapshot, durable: Boolean) {
        val editor = prefs.edit()
            .putString(KEY_TASK_ID, snapshot.taskId)
            .putString(KEY_PROJECT_ID, snapshot.projectId)
            .putString(KEY_PROJECT_TITLE, snapshot.projectTitle)
            .putString(KEY_OUTPUT_URI, snapshot.outputUri)
            .putString(KEY_PRESET, snapshot.settings.preset.name)
            .putInt(KEY_FRAME_RATE, snapshot.settings.frameRate)
            .putString(KEY_CODEC, snapshot.settings.videoCodec.name)
            .putInt(KEY_AUDIO_SAMPLE_RATE, snapshot.settings.audioSampleRate)
            .putInt(KEY_AUDIO_BITRATE, snapshot.settings.audioBitrate)
            .putString(KEY_STATE, snapshot.state.name)
            .putInt(KEY_PROGRESS, snapshot.progress)
            .putString(KEY_MESSAGE, snapshot.message)
            .putLong(KEY_STARTED_AT, snapshot.startedAtMs)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtMs)
            .putLong(KEY_ELAPSED_MS, snapshot.elapsedMs)
            .putString(KEY_ERROR, snapshot.errorMessage)
        if (durable) editor.commit() else editor.apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    companion object {
        private const val PREFS = "vedito_export_task_v1"
        private const val PROGRESS_PERSIST_INTERVAL_MS = 750L
        private const val KEY_TASK_ID = "taskId"
        private const val KEY_PROJECT_ID = "projectId"
        private const val KEY_PROJECT_TITLE = "projectTitle"
        private const val KEY_OUTPUT_URI = "outputUri"
        private const val KEY_PRESET = "preset"
        private const val KEY_FRAME_RATE = "frameRate"
        private const val KEY_CODEC = "codec"
        private const val KEY_AUDIO_SAMPLE_RATE = "audioSampleRate"
        private const val KEY_AUDIO_BITRATE = "audioBitrate"
        private const val KEY_STATE = "state"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_ELAPSED_MS = "elapsedMs"
        private const val KEY_ERROR = "error"
    }
}
