package com.vedito.app.feature.export

import android.content.Context
import com.vedito.app.core.export.ExportRecoveryPlan
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/** Persistent cache-backed checkpoint store for segmented exports. */
internal class ExportRecoveryStore(context: Context) {
    private val root = File(context.cacheDir, ROOT_NAME).apply { mkdirs() }

    data class ResumeStats(
        val completedSegments: Int,
        val reusableBytes: Long,
        val audioCheckpointPresent: Boolean
    )

    data class Session(
        val directory: File,
        val fingerprint: String,
        val segmentCount: Int,
        val resumedSegmentIndices: Set<Int>
    ) {
        fun finalSegmentFile(index: Int): File = File(directory, "segment_${index.toString().padStart(4, '0')}.mp4")
        fun partialSegmentFile(index: Int): File = File(directory, "segment_${index.toString().padStart(4, '0')}.part")
        fun finalAudioFile(): File = File(directory, "audio_track.mp4")
        fun partialAudioFile(): File = File(directory, "audio_track.part")
    }

    fun open(plan: ExportRecoveryPlan): Session {
        cleanupStale(exceptFingerprint = plan.sessionFingerprint)
        val directory = File(root, plan.sessionFingerprint).apply { mkdirs() }
        val metadata = File(directory, METADATA_FILE)
        val validMetadata = readProperties(metadata)?.let {
            it.getProperty(KEY_FINGERPRINT) == plan.sessionFingerprint &&
                it.getProperty(KEY_SEGMENT_COUNT)?.toIntOrNull() == plan.segmentCount
        } == true
        if (!validMetadata) {
            directory.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            writeMetadata(metadata, plan)
        } else {
            touchMetadata(metadata)
        }
        directory.listFiles { file -> file.extension == "part" }?.forEach { runCatching { it.delete() } }
        val completed = (0 until plan.segmentCount).filterTo(linkedSetOf()) { index ->
            val file = File(directory, "segment_${index.toString().padStart(4, '0')}.mp4")
            file.isFile && file.length() >= MIN_SEGMENT_BYTES
        }
        return Session(directory, plan.sessionFingerprint, plan.segmentCount, completed)
    }

    fun markCompleted(session: Session, index: Int) {
        val partial = session.partialSegmentFile(index)
        val final = session.finalSegmentFile(index)
        check(partial.isFile && partial.length() >= MIN_SEGMENT_BYTES) { "Rendered recovery segment is empty" }
        if (final.exists()) check(final.delete()) { "Could not replace old recovery segment" }
        check(partial.renameTo(final)) { "Could not checkpoint recovery segment" }
        touchMetadata(File(session.directory, METADATA_FILE))
    }


    fun markAudioCompleted(session: Session) {
        val partial = session.partialAudioFile()
        val final = session.finalAudioFile()
        check(partial.isFile && partial.length() >= MIN_SEGMENT_BYTES) { "Rendered recovery audio is empty" }
        if (final.exists()) check(final.delete()) { "Could not replace old recovery audio" }
        check(partial.renameTo(final)) { "Could not checkpoint recovery audio" }
        touchMetadata(File(session.directory, METADATA_FILE))
    }

    fun discardAudio(session: Session) {
        runCatching { session.partialAudioFile().delete() }
        runCatching { session.finalAudioFile().delete() }
    }

    fun discardSegment(session: Session, index: Int) {
        runCatching { session.partialSegmentFile(index).delete() }
        runCatching { session.finalSegmentFile(index).delete() }
    }

    fun discard(session: Session) {
        runCatching { session.directory.deleteRecursively() }
    }

    fun resumeStats(plan: ExportRecoveryPlan): ResumeStats {
        val directory = File(root, plan.sessionFingerprint)
        val metadata = File(directory, METADATA_FILE)
        val properties = readProperties(metadata) ?: return ResumeStats(0, 0L, false)
        if (properties.getProperty(KEY_FINGERPRINT) != plan.sessionFingerprint) return ResumeStats(0, 0L, false)
        if (properties.getProperty(KEY_SEGMENT_COUNT)?.toIntOrNull() != plan.segmentCount) return ResumeStats(0, 0L, false)

        var reusableBytes = 0L
        var completed = 0
        (0 until plan.segmentCount).forEach { index ->
            val file = File(directory, "segment_${index.toString().padStart(4, '0')}.mp4")
            if (file.isFile && file.length() >= MIN_SEGMENT_BYTES) {
                completed++
                reusableBytes += file.length()
            }
        }
        val audio = File(directory, "audio_track.mp4")
        val audioPresent = audio.isFile && audio.length() >= MIN_SEGMENT_BYTES
        if (audioPresent) reusableBytes += audio.length()
        return ResumeStats(completed, reusableBytes, audioPresent)
    }

    fun resumableCount(plan: ExportRecoveryPlan): Int = resumeStats(plan).completedSegments

    private fun writeMetadata(file: File, plan: ExportRecoveryPlan) {
        val now = System.currentTimeMillis()
        val properties = Properties().apply {
            setProperty(KEY_FINGERPRINT, plan.sessionFingerprint)
            setProperty(KEY_SEGMENT_COUNT, plan.segmentCount.toString())
            setProperty(KEY_CREATED_AT, now.toString())
            setProperty(KEY_LAST_TOUCHED, now.toString())
        }
        FileOutputStream(file).use { properties.store(it, "Vedito export recovery checkpoint") }
    }

    private fun touchMetadata(file: File) {
        val properties = readProperties(file) ?: return
        properties.setProperty(KEY_LAST_TOUCHED, System.currentTimeMillis().toString())
        FileOutputStream(file).use { properties.store(it, "Vedito export recovery checkpoint") }
    }

    private fun readProperties(file: File): Properties? = runCatching {
        if (!file.isFile) return@runCatching null
        Properties().also { properties -> FileInputStream(file).use(properties::load) }
    }.getOrNull()

    private fun cleanupStale(exceptFingerprint: String) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { directory ->
            if (!directory.isDirectory || directory.name == exceptFingerprint) return@forEach
            val properties = readProperties(File(directory, METADATA_FILE))
            val lastTouched = properties?.getProperty(KEY_LAST_TOUCHED)?.toLongOrNull()
                ?: directory.lastModified()
            if (now - lastTouched > MAX_SESSION_AGE_MS) {
                runCatching { directory.deleteRecursively() }
            }
        }
    }

    companion object {
        private const val ROOT_NAME = "vedito_export_recovery"
        private const val METADATA_FILE = "session.properties"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_SEGMENT_COUNT = "segmentCount"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_LAST_TOUCHED = "lastTouched"
        private const val MIN_SEGMENT_BYTES = 1_024L
        private const val MAX_SESSION_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
