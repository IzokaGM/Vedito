package com.vedito.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.AudioRole
import kotlin.math.abs

/**
 * Lightweight preview mixer for timeline audio. One MediaPlayer is maintained per audio clip
 * that has been prepared. Multiple overlapping clips can play concurrently.
 *
 * This remains the lightweight preview mixer. Export uses the separate deterministic
 * AudioMixPlan + OfflineAudioMixer pipeline so UI player state never owns render math.
 */
class AudioPlaybackEngine(private val context: Context) {
    private data class Slot(
        val clipId: String,
        val player: MediaPlayer,
        var prepared: Boolean = false,
        var pendingPositionMs: Int = 0,
        var pendingPlay: Boolean = false
    )

    private val slots = mutableMapOf<String, Slot>()
    private var assetsById: Map<String, AudioAsset> = emptyMap()
    private var clips: List<AudioClip> = emptyList()
    private var lastTimelineMs: Int = 0
    private var wantsPlayback = false

    fun setTimeline(assets: List<AudioAsset>, clips: List<AudioClip>) {
        assetsById = assets.associateBy { it.id }
        this.clips = clips

        val validClipIds = clips.mapTo(mutableSetOf()) { it.id }
        val stale = slots.keys.filterNot { it in validClipIds }
        stale.forEach { id ->
            slots.remove(id)?.player?.release()
        }
        sync(lastTimelineMs, wantsPlayback, forceSeek = true)
    }

    fun playFrom(timelineMs: Int) {
        wantsPlayback = true
        sync(timelineMs, playing = true, forceSeek = true)
    }

    fun pause() {
        wantsPlayback = false
        slots.values.forEach { slot ->
            runCatching {
                if (slot.prepared && slot.player.isPlaying) slot.player.pause()
            }
            slot.pendingPlay = false
        }
    }

    fun seekTo(timelineMs: Int) {
        sync(timelineMs, playing = false, forceSeek = true)
    }

    fun sync(timelineMs: Int, playing: Boolean, forceSeek: Boolean = false) {
        lastTimelineMs = timelineMs.coerceAtLeast(0)
        wantsPlayback = playing

        val activeIds = mutableSetOf<String>()
        clips.forEach { clip ->
            val active = lastTimelineMs >= clip.timelineStartMs && lastTimelineMs < clip.timelineEndMs
            if (!active || clip.muted || clip.volume <= 0f) return@forEach
            val asset = assetsById[clip.assetId] ?: return@forEach
            activeIds += clip.id
            val slot = slots[clip.id] ?: createSlot(clip, asset) ?: return@forEach
            val target = (clip.sourceStartMs + (lastTimelineMs - clip.timelineStartMs))
                .coerceIn(clip.sourceStartMs, (clip.sourceEndMs - 1).coerceAtLeast(clip.sourceStartMs))
            val gain = effectiveGain(clip, lastTimelineMs)
            val pan = clip.pan.coerceIn(-1f, 1f)
            val left = if (pan < 0f) 1f else 1f - pan
            val right = if (pan > 0f) 1f else 1f + pan
            slot.player.setVolume(gain * left, gain * right)
            if (!slot.prepared) {
                slot.pendingPositionMs = target
                slot.pendingPlay = playing
            } else {
                val drift = abs(slot.player.currentPosition - target)
                if (forceSeek || drift > DRIFT_TOLERANCE_MS || (!slot.player.isPlaying && playing)) {
                    slot.pendingPlay = playing
                    slot.player.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else if (playing && !slot.player.isPlaying) {
                    slot.player.start()
                } else if (!playing && slot.player.isPlaying) {
                    slot.player.pause()
                }
            }
        }

        slots.forEach { (id, slot) ->
            if (id !in activeIds) {
                slot.pendingPlay = false
                runCatching {
                    if (slot.prepared && slot.player.isPlaying) slot.player.pause()
                }
            }
        }

        clips.asSequence()
            .filter { !it.muted && it.timelineStartMs in lastTimelineMs..(lastTimelineMs + PREWARM_WINDOW_MS) }
            .filterNot { slots.containsKey(it.id) }
            .take(MAX_PREWARM_SLOTS)
            .forEach { clip ->
                val asset = assetsById[clip.assetId] ?: return@forEach
                createSlot(clip, asset)?.apply { pendingPositionMs = clip.sourceStartMs }
            }
    }

    fun release() {
        slots.values.forEach { runCatching { it.player.release() } }
        slots.clear()
    }

    private fun effectiveGain(clip: AudioClip, timelineMs: Int): Float {
        if (clip.muted) return 0f
        val localMs = (timelineMs - clip.timelineStartMs).coerceIn(0, clip.durationMs)
        val remainingMs = (clip.timelineEndMs - timelineMs).coerceIn(0, clip.durationMs)
        val fadeInGain = if (clip.fadeInMs > 0) (localMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f) else 1f
        val fadeOutGain = if (clip.fadeOutMs > 0) (remainingMs.toFloat() / clip.fadeOutMs).coerceIn(0f, 1f) else 1f
        val duck = if (clip.role == AudioRole.MUSIC && clip.duckingAmount > 0f) {
            (1f - clip.duckingAmount.coerceIn(0f, 0.9f) * voiceDuckStrength(timelineMs)).coerceIn(0.1f, 1f)
        } else 1f
        return (clip.volume.coerceIn(0f, 1f) * minOf(fadeInGain, fadeOutGain) * duck).coerceIn(0f, 1f)
    }

    private fun voiceDuckStrength(timelineMs: Int): Float {
        var strongest = 0f
        clips.forEach { voice ->
            if (voice.role != AudioRole.VOICE || voice.muted || voice.volume <= 0f) return@forEach
            val start = voice.timelineStartMs - DUCK_ATTACK_MS
            val end = voice.timelineEndMs + DUCK_RELEASE_MS
            val strength = when {
                timelineMs < start || timelineMs > end -> 0f
                timelineMs < voice.timelineStartMs -> ((timelineMs - start).toFloat() / DUCK_ATTACK_MS).coerceIn(0f, 1f)
                timelineMs <= voice.timelineEndMs -> 1f
                else -> ((end - timelineMs).toFloat() / DUCK_RELEASE_MS).coerceIn(0f, 1f)
            }
            strongest = maxOf(strongest, strength)
        }
        return strongest
    }

    private fun createSlot(clip: AudioClip, asset: AudioAsset): Slot? {
        return runCatching {
            val player = MediaPlayer()
            val slot = Slot(clip.id, player)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(context, Uri.parse(asset.uri))
            player.setOnPreparedListener {
                slot.prepared = true
                val target = slot.pendingPositionMs.coerceIn(0, it.duration.coerceAtLeast(0))
                slot.pendingPlay = slot.pendingPlay && wantsPlayback
                it.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            }
            player.setOnSeekCompleteListener {
                if (slot.pendingPlay && wantsPlayback) {
                    slot.pendingPlay = false
                    runCatching { it.start() }
                }
            }
            player.setOnCompletionListener {
                slot.pendingPlay = false
            }
            player.setOnErrorListener { _, _, _ ->
                slot.pendingPlay = false
                true
            }
            slots[clip.id] = slot
            player.prepareAsync()
            slot
        }.getOrNull()
    }

    companion object {
        private const val DRIFT_TOLERANCE_MS = 140
        private const val PREWARM_WINDOW_MS = 15_000
        private const val MAX_PREWARM_SLOTS = 4
        private const val DUCK_ATTACK_MS = 180
        private const val DUCK_RELEASE_MS = 360
    }
}
