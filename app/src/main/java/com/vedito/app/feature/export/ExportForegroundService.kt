package com.vedito.app.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vedito.app.R
import com.vedito.app.core.export.ExportPlan
import com.vedito.app.core.export.ExportSettings
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.feature.editor.EditorActivity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Patch 27 foreground owner for long-running media export.
 *
 * The service deliberately reloads the saved project by id instead of receiving a giant Parcelable.
 * If Android recreates the process and redelivers the start intent, VideoExportEngine reopens the
 * Patch 24 checkpoint session and resumes finalized segments rather than starting from frame zero.
 */
class ExportForegroundService : Service() {
    private lateinit var taskStore: ExportTaskStore
    private lateinit var exportEngine: VideoExportEngine
    private val terminalHandled = AtomicBoolean(false)
    private var activeTaskId: String? = null
    private var activeProjectId: String? = null

    override fun onCreate() {
        super.onCreate()
        taskStore = ExportTaskStore(this)
        exportEngine = VideoExportEngine(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelActiveExport()
                return START_NOT_STICKY
            }
            ACTION_START -> startFromIntent(intent)
            else -> restoreRedeliveredTaskIfPossible()
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        val taskId = activeTaskId ?: taskStore.read()?.taskId
        if (taskId != null && terminalHandled.compareAndSet(false, true)) {
            exportEngine.cancel()
            val snapshot = taskStore.finishTimeout(taskId)
            broadcastStatus()
            notifyTerminal(snapshot)
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    override fun onDestroy() {
        exportEngine.close()
        super.onDestroy()
    }

    private fun startFromIntent(intent: Intent) {
        if (exportEngine.isRunning()) {
            updateForeground(taskStore.read())
            return
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)?.takeIf { it.isNotBlank() }
        val output = intent.getStringExtra(EXTRA_OUTPUT_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val settings = settingsFrom(intent)

        // Android requires a newly started FGS to promote promptly. Do this before reading a
        // potentially large project or running any codec preflight, including on intent redelivery.
        val pending = taskStore.read()
        try {
            startForegroundCompat(buildNotification(pending, "Preparing video export", pending?.progress ?: 0, false))
        } catch (t: Throwable) {
            pending?.takeIf { it.taskId == taskId && it.state.isActive }?.let {
                taskStore.finishFailure(taskId, t.message ?: "Android refused to start foreground media export")
            }
            broadcastStatus()
            stopSelf()
            return
        }

        if (projectId == null || output == null || settings == null) {
            pending?.takeIf { it.taskId == taskId && it.state.isActive }?.let {
                taskStore.finishFailure(taskId, "Export request is incomplete")
            }
            broadcastStatus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val project = try {
            ProjectRepository(this).find(projectId)
        } catch (t: Throwable) {
            val snapshot = taskStore.finishFailure(taskId, "Unable to reopen project: ${t.message ?: "unknown error"}")
            broadcastStatus()
            notifyTerminal(snapshot)
            finishService(removeNotification = false)
            return
        }
        if (project == null) {
            val title = intent.getStringExtra(EXTRA_PROJECT_TITLE).orEmpty().ifBlank { "Vedito project" }
            taskStore.begin(taskId, projectId, title, output, settings)
            val snapshot = taskStore.finishFailure(taskId, "Project could not be reopened for export")
            broadcastStatus()
            notifyTerminal(snapshot)
            finishService(removeNotification = false)
            return
        }

        terminalHandled.set(false)
        activeTaskId = taskId
        activeProjectId = projectId
        val existing = taskStore.read()
        val snapshot = if (existing?.taskId == taskId && existing.state.isActive) {
            existing
        } else {
            taskStore.begin(taskId, projectId, project.title, output, settings)
        }
        updateForeground(snapshot)
        broadcastStatus()

        val started = exportEngine.export(project, output, settings, object : VideoExportEngine.Listener {
            override fun onProgress(percent: Int, message: String) {
                val current = taskStore.updateProgress(taskId, percent, message)
                updateForeground(current)
                broadcastStatus()
            }

            override fun onCompleted(uri: Uri, plan: ExportPlan, elapsedMs: Long) {
                if (!terminalHandled.compareAndSet(false, true)) return
                val done = taskStore.finishSuccess(taskId, elapsedMs)
                broadcastStatus()
                notifyTerminal(done, plan)
                finishService(removeNotification = false)
            }

            override fun onCancelled() {
                if (!terminalHandled.compareAndSet(false, true)) return
                val done = taskStore.finishCancelled(taskId)
                broadcastStatus()
                notifyTerminal(done)
                finishService(removeNotification = false)
            }

            override fun onError(message: String, throwable: Throwable?) {
                if (!terminalHandled.compareAndSet(false, true)) return
                val done = taskStore.finishFailure(taskId, message)
                broadcastStatus()
                notifyTerminal(done)
                finishService(removeNotification = false)
            }
        })
        if (!started && terminalHandled.compareAndSet(false, true)) {
            val failed = taskStore.finishFailure(taskId, "Unable to start export engine")
            broadcastStatus()
            notifyTerminal(failed)
            finishService(removeNotification = false)
        }
    }

    private fun restoreRedeliveredTaskIfPossible() {
        if (exportEngine.isRunning()) return
        val snapshot = taskStore.read()?.takeIf { it.state.isActive } ?: run {
            stopSelf()
            return
        }
        val intent = startIntent(
            context = this,
            taskId = snapshot.taskId,
            projectId = snapshot.projectId,
            projectTitle = snapshot.projectTitle,
            outputUri = snapshot.output,
            settings = snapshot.settings
        )
        startFromIntent(intent)
    }

    private fun cancelActiveExport() {
        val current = taskStore.read()
        if (current?.state?.isActive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        activeTaskId = current.taskId
        updateForeground(current.copy(message = "Cancelling after current codec checkpoint…"))
        if (exportEngine.isRunning()) {
            exportEngine.cancel()
        } else if (terminalHandled.compareAndSet(false, true)) {
            val cancelled = taskStore.finishCancelled(current.taskId)
            broadcastStatus()
            notifyTerminal(cancelled)
            finishService(removeNotification = false)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        // AndroidX Core 1.16 ServiceCompat masks out the Android 15 mediaProcessing bit (0x2000)
        // and forwards type NONE; targetSdk 37 rejects that at runtime. Use the platform API so
        // the requested type is passed intact, while retaining the old-Android 2-arg overload.
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(snapshot: ExportTaskStore.Snapshot?) {
        val current = snapshot ?: return
        val notification = buildNotification(current, current.message, current.progress, false)
        NotificationManagerCompatFacade.notify(this, NOTIFICATION_ID, notification)
    }

    private fun notifyTerminal(snapshot: ExportTaskStore.Snapshot?, plan: ExportPlan? = null) {
        val current = snapshot ?: return
        val text = when (current.state) {
            ExportTaskStore.State.SUCCEEDED -> {
                val shape = plan?.let { "${it.width}×${it.height} · ${it.frameRate} fps" }
                if (shape != null) "Export complete · $shape" else "Export complete"
            }
            ExportTaskStore.State.CANCELLED -> "Export cancelled · checkpoints kept for retry"
            ExportTaskStore.State.TIMED_OUT -> "Android time limit reached · retry to resume checkpoints"
            ExportTaskStore.State.FAILED -> current.errorMessage ?: "Export failed"
            else -> current.message
        }
        NotificationManagerCompatFacade.notify(
            this,
            NOTIFICATION_ID,
            buildNotification(current, text, current.progress, true)
        )
    }

    private fun buildNotification(
        snapshot: ExportTaskStore.Snapshot?,
        text: String,
        progress: Int,
        terminal: Boolean
    ): Notification {
        val projectId = snapshot?.projectId ?: activeProjectId
        val contentIntent = projectId?.let { editorPendingIntent(it) }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                when (snapshot?.state) {
                    ExportTaskStore.State.SUCCEEDED -> "Vedito export complete"
                    ExportTaskStore.State.CANCELLED -> "Vedito export cancelled"
                    ExportTaskStore.State.FAILED -> "Vedito export failed"
                    ExportTaskStore.State.TIMED_OUT -> "Vedito export paused"
                    else -> "Exporting ${snapshot?.projectTitle ?: "Vedito project"}"
                }
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(!terminal)
            .setOngoing(!terminal)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setAutoCancel(terminal)

        if (!terminal) {
            builder.setProgress(100, progress.coerceIn(0, 99), false)
            builder.addAction(0, "Cancel", cancelPendingIntent())
        } else if (snapshot?.state == ExportTaskStore.State.SUCCEEDED) {
            builder.setProgress(0, 0, false)
            if (contentIntent != null) builder.addAction(0, "Open Vedito", contentIntent)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun editorPendingIntent(projectId: String): PendingIntent {
        val intent = Intent(this, EditorActivity::class.java)
            .putExtra(EditorActivity.EXTRA_PROJECT_ID, projectId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_EDITOR,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, ExportForegroundService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video exports",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and recovery status for long Vedito exports"
                setShowBadge(false)
            }
        )
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    private fun finishService(removeNotification: Boolean) {
        if (removeNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(STOP_FOREGROUND_DETACH)
        activeTaskId = null
        activeProjectId = null
        stopSelf()
    }

    companion object {
        const val ACTION_STATUS_CHANGED = "com.vedito.app.action.EXPORT_STATUS_CHANGED"
        private const val ACTION_START = "com.vedito.app.action.START_EXPORT"
        private const val ACTION_CANCEL = "com.vedito.app.action.CANCEL_EXPORT"
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_PROJECT_ID = "projectId"
        private const val EXTRA_PROJECT_TITLE = "projectTitle"
        private const val EXTRA_OUTPUT_URI = "outputUri"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_FRAME_RATE = "frameRate"
        private const val EXTRA_CODEC = "codec"
        private const val EXTRA_AUDIO_SAMPLE_RATE = "audioSampleRate"
        private const val EXTRA_AUDIO_BITRATE = "audioBitrate"
        private const val CHANNEL_ID = "vedito_exports"
        private const val NOTIFICATION_ID = 2701
        private const val REQUEST_OPEN_EDITOR = 2702
        private const val REQUEST_CANCEL = 2703

        fun startExport(
            context: Context,
            projectId: String,
            projectTitle: String,
            outputUri: Uri,
            settings: ExportSettings
        ): String {
            val taskId = UUID.randomUUID().toString()
            val store = ExportTaskStore(context)
            store.begin(taskId, projectId, projectTitle, outputUri, settings)
            try {
                ContextCompat.startForegroundService(
                    context,
                    startIntent(context, taskId, projectId, projectTitle, outputUri, settings)
                )
            } catch (t: Throwable) {
                store.finishFailure(taskId, t.message ?: "Android refused to start foreground media export")
                throw t
            }
            return taskId
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ExportForegroundService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }

        private fun startIntent(
            context: Context,
            taskId: String,
            projectId: String,
            projectTitle: String,
            outputUri: Uri,
            settings: ExportSettings
        ): Intent = Intent(context, ExportForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_PROJECT_ID, projectId)
            .putExtra(EXTRA_PROJECT_TITLE, projectTitle)
            .putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
            .putExtra(EXTRA_PRESET, settings.preset.name)
            .putExtra(EXTRA_FRAME_RATE, settings.frameRate)
            .putExtra(EXTRA_CODEC, settings.videoCodec.name)
            .putExtra(EXTRA_AUDIO_SAMPLE_RATE, settings.audioSampleRate)
            .putExtra(EXTRA_AUDIO_BITRATE, settings.audioBitrate)

        private fun settingsFrom(intent: Intent): ExportSettings? {
            val preset = com.vedito.app.core.export.ExportPreset.values().firstOrNull {
                it.name == intent.getStringExtra(EXTRA_PRESET)
            } ?: return null
            val codec = com.vedito.app.core.export.ExportVideoCodec.values().firstOrNull {
                it.name == intent.getStringExtra(EXTRA_CODEC)
            } ?: return null
            return ExportSettings(
                preset = preset,
                frameRate = intent.getIntExtra(EXTRA_FRAME_RATE, 30).coerceIn(24, 60),
                videoCodec = codec,
                audioSampleRate = intent.getIntExtra(EXTRA_AUDIO_SAMPLE_RATE, 48_000).coerceIn(44_100, 48_000),
                audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128_000).coerceIn(96_000, 256_000)
            )
        }
    }
}

private object NotificationManagerCompatFacade {
    fun notify(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
