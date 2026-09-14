package com.vedito.app.core.projects

import android.content.Context
import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.Project
import org.json.JSONArray
import org.json.JSONObject

class ProjectRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<Project> {
        val raw = preferences.getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    parseProject(array.getJSONObject(index))?.let(::add)
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun find(id: String): Project? = list().firstOrNull { it.id == id }

    fun save(project: Project) {
        val projects = list()
            .filterNot { it.id == project.id }
            .toMutableList()
            .apply { add(0, project) }
            .take(MAX_PROJECTS)

        val array = JSONArray()
        projects.forEach { item -> array.put(toJson(item)) }
        preferences.edit().putString(KEY_PROJECTS, array.toString()).apply()
    }

    private fun parseProject(item: JSONObject): Project? {
        val id = item.optString("id")
        if (id.isBlank()) return null

        val assets = parseAssets(item)
        val clips = parseClips(item, assets, id)
        val audioAssets = parseAudioAssets(item)
        val audioClips = parseAudioClips(item, audioAssets)

        return Project(
            id = id,
            title = item.optString("title").ifBlank { "Untitled edit" },
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            assets = assets,
            clips = clips,
            audioAssets = audioAssets,
            audioClips = audioClips,
            playheadMs = item.optInt("playheadMs", 0),
            selectedClipId = item.optString("selectedClipId").takeIf { it.isNotBlank() },
            selectedAudioClipId = item.optString("selectedAudioClipId").takeIf { it.isNotBlank() },
            timelineZoom = item.optDouble("timelineZoom", 1.0).toFloat().coerceIn(1f, 8f),
            timelineViewportStartMs = item.optInt("timelineViewportStartMs", 0).coerceAtLeast(0)
        )
    }

    private fun parseAssets(item: JSONObject): List<MediaAsset> {
        val assetArray = item.optJSONArray("assets")
        if (assetArray != null && assetArray.length() > 0) {
            return buildList {
                for (index in 0 until assetArray.length()) {
                    val asset = assetArray.optJSONObject(index) ?: continue
                    val assetId = asset.optString("id")
                    val uri = asset.optString("uri")
                    if (assetId.isBlank() || uri.isBlank()) continue
                    add(
                        MediaAsset(
                            id = assetId,
                            uri = uri,
                            displayName = asset.optString("displayName").ifBlank { "Video" },
                            durationMs = asset.optInt("durationMs", 0),
                            frameRate = asset.optDouble("frameRate", MediaAsset.DEFAULT_FRAME_RATE.toDouble())
                                .toFloat()
                                .takeIf { it.isFinite() && it in 1f..240f }
                                ?: MediaAsset.DEFAULT_FRAME_RATE
                        )
                    )
                }
            }
        }

        val legacyUri = item.optString("sourceUri")
        if (legacyUri.isBlank()) return emptyList()
        return listOf(
            MediaAsset(
                id = "legacy-${item.optString("id")}",
                uri = legacyUri,
                displayName = item.optString("title").ifBlank { "Video" },
                durationMs = item.optInt("sourceDurationMs", 0),
                frameRate = MediaAsset.DEFAULT_FRAME_RATE
            )
        )
    }

    private fun parseClips(item: JSONObject, assets: List<MediaAsset>, projectId: String): List<Clip> {
        val clipArray = item.optJSONArray("clips") ?: JSONArray()
        val fallbackAssetId = assets.firstOrNull()?.id ?: "legacy-$projectId"
        return buildList {
            for (index in 0 until clipArray.length()) {
                val clip = clipArray.optJSONObject(index) ?: continue
                val clipId = clip.optString("id")
                if (clipId.isBlank()) continue
                add(
                    Clip(
                        id = clipId,
                        assetId = clip.optString("assetId").ifBlank { fallbackAssetId },
                        sourceStartMs = clip.optInt("sourceStartMs", 0),
                        sourceEndMs = clip.optInt("sourceEndMs", 0)
                    )
                )
            }
        }
    }

    private fun parseAudioAssets(item: JSONObject): List<AudioAsset> {
        val array = item.optJSONArray("audioAssets") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val asset = array.optJSONObject(index) ?: continue
                val id = asset.optString("id")
                val uri = asset.optString("uri")
                if (id.isBlank() || uri.isBlank()) continue
                add(
                    AudioAsset(
                        id = id,
                        uri = uri,
                        displayName = asset.optString("displayName").ifBlank { "Audio" },
                        durationMs = asset.optInt("durationMs", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    private fun parseAudioClips(item: JSONObject, assets: List<AudioAsset>): List<AudioClip> {
        val assetIds = assets.mapTo(mutableSetOf()) { it.id }
        val array = item.optJSONArray("audioClips") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val clip = array.optJSONObject(index) ?: continue
                val id = clip.optString("id")
                val assetId = clip.optString("assetId")
                if (id.isBlank() || assetId !in assetIds) continue
                val sourceStart = clip.optInt("sourceStartMs", 0).coerceAtLeast(0)
                val sourceEnd = clip.optInt("sourceEndMs", 0).coerceAtLeast(sourceStart)
                if (sourceEnd <= sourceStart) continue
                add(
                    AudioClip(
                        id = id,
                        assetId = assetId,
                        timelineStartMs = clip.optInt("timelineStartMs", 0).coerceAtLeast(0),
                        sourceStartMs = sourceStart,
                        sourceEndMs = sourceEnd,
                        volume = clip.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                        muted = clip.optBoolean("muted", false)
                    )
                )
            }
        }
    }

    private fun toJson(project: Project): JSONObject {
        val assetArray = JSONArray()
        project.assets.forEach { asset ->
            assetArray.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("uri", asset.uri)
                    .put("displayName", asset.displayName)
                    .put("durationMs", asset.durationMs)
                    .put("frameRate", asset.frameRate.toDouble())
            )
        }

        val clipArray = JSONArray()
        project.clips.forEach { clip ->
            clipArray.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("assetId", clip.assetId)
                    .put("sourceStartMs", clip.sourceStartMs)
                    .put("sourceEndMs", clip.sourceEndMs)
            )
        }

        val audioAssetArray = JSONArray()
        project.audioAssets.forEach { asset ->
            audioAssetArray.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("uri", asset.uri)
                    .put("displayName", asset.displayName)
                    .put("durationMs", asset.durationMs)
            )
        }

        val audioClipArray = JSONArray()
        project.audioClips.forEach { clip ->
            audioClipArray.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("assetId", clip.assetId)
                    .put("timelineStartMs", clip.timelineStartMs)
                    .put("sourceStartMs", clip.sourceStartMs)
                    .put("sourceEndMs", clip.sourceEndMs)
                    .put("volume", clip.volume.toDouble())
                    .put("muted", clip.muted)
            )
        }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", project.id)
            .put("title", project.title)
            .put("updatedAt", project.updatedAt)
            .put("assets", assetArray)
            .put("clips", clipArray)
            .put("audioAssets", audioAssetArray)
            .put("audioClips", audioClipArray)
            .put("playheadMs", project.playheadMs)
            .put("selectedClipId", project.selectedClipId ?: "")
            .put("selectedAudioClipId", project.selectedAudioClipId ?: "")
            .put("timelineZoom", project.timelineZoom.toDouble())
            .put("timelineViewportStartMs", project.timelineViewportStartMs)
    }

    companion object {
        private const val PREFS_NAME = "vedito_project_index_v2"
        private const val KEY_PROJECTS = "projects"
        private const val MAX_PROJECTS = 12
        private const val SCHEMA_VERSION = 6
    }
}
