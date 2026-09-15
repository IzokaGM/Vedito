package com.vedito.app.core.projects

import android.content.Context
import com.vedito.app.core.model.AudioAsset
import com.vedito.app.core.model.AudioClip
import com.vedito.app.core.model.CanvasAspect
import com.vedito.app.core.model.CanvasBackground
import com.vedito.app.core.model.CanvasSettings
import com.vedito.app.core.model.CaptionPreset
import com.vedito.app.core.model.CaptionSegment
import com.vedito.app.core.model.Clip
import com.vedito.app.core.model.ClipPlaybackMode
import com.vedito.app.core.model.ClipTiming
import com.vedito.app.core.model.ClipFitMode
import com.vedito.app.core.model.ClipTransform
import com.vedito.app.core.model.EffectClip
import com.vedito.app.core.model.FloatKeyframe
import com.vedito.app.core.model.KeyframeEasing
import com.vedito.app.core.model.TransformKeyframeSet
import com.vedito.app.core.model.TransitionKind
import com.vedito.app.core.model.TransitionSpec
import com.vedito.app.core.model.VideoEffectKind
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.MaskShape
import com.vedito.app.core.model.MaskSpec
import com.vedito.app.core.model.ChromaKeySpec
import com.vedito.app.core.model.OverlayAsset
import com.vedito.app.core.model.OverlayClip
import com.vedito.app.core.model.OverlayMediaType
import com.vedito.app.core.model.Project
import com.vedito.app.core.model.TextAlignment
import com.vedito.app.core.model.TextAnimationKind
import com.vedito.app.core.model.TextAnimationSpec
import com.vedito.app.core.model.TextFontFamily
import com.vedito.app.core.model.TextPreset
import com.vedito.app.core.model.TextClip
import com.vedito.app.core.model.TextStyle
import com.vedito.app.core.model.TextTransform
import com.vedito.app.core.visual.VisualTransformMath
import com.vedito.app.core.visual.MaskChromaComposition
import com.vedito.app.core.keyframe.KeyframeEngine
import com.vedito.app.core.caption.CaptionTimelineEditor
import com.vedito.app.core.text.TextTimelineEditor
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
        val overlayAssets = parseOverlayAssets(item)
        val overlayClips = parseOverlayClips(item, overlayAssets)
        val textClips = parseTextClips(item)
        val captionSegments = parseCaptionSegments(item)
        val effectClips = parseEffectClips(item)

        return Project(
            id = id,
            title = item.optString("title").ifBlank { "Untitled edit" },
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            assets = assets,
            clips = clips,
            audioAssets = audioAssets,
            audioClips = audioClips,
            overlayAssets = overlayAssets,
            overlayClips = overlayClips,
            textClips = textClips,
            captionSegments = captionSegments,
            effectClips = effectClips,
            canvasSettings = parseCanvasSettings(item),
            playheadMs = item.optInt("playheadMs", 0),
            selectedClipId = item.optString("selectedClipId").takeIf { it.isNotBlank() },
            selectedAudioClipId = item.optString("selectedAudioClipId").takeIf { it.isNotBlank() },
            selectedOverlayClipId = item.optString("selectedOverlayClipId").takeIf { it.isNotBlank() },
            selectedTextClipId = item.optString("selectedTextClipId").takeIf { it.isNotBlank() },
            selectedCaptionSegmentId = item.optString("selectedCaptionSegmentId").takeIf { it.isNotBlank() },
            selectedEffectClipId = item.optString("selectedEffectClipId").takeIf { it.isNotBlank() },
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
                                ?: MediaAsset.DEFAULT_FRAME_RATE,
                            width = asset.optInt("width", 0).coerceAtLeast(0),
                            height = asset.optInt("height", 0).coerceAtLeast(0)
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
                        sourceEndMs = clip.optInt("sourceEndMs", 0),
                        transform = parseClipTransform(clip.optJSONObject("transform")),
                        keyframes = parseTransformKeyframes(clip.optJSONObject("keyframes")),
                        timing = parseClipTiming(clip.optJSONObject("timing"), clip.optInt("sourceStartMs", 0), clip.optInt("sourceEndMs", 0)),
                        transitionOut = parseTransition(clip.optJSONObject("transitionOut")),
                        mask = parseMask(clip.optJSONObject("mask")),
                        chromaKey = parseChromaKey(clip.optJSONObject("chromaKey"))
                    ).let { parsed ->
                        parsed.copy(keyframes = KeyframeEngine.normalize(parsed.keyframes, parsed.durationMs))
                    }
                )
            }
        }
    }


    private fun parseClipTiming(json: JSONObject?, sourceStartMs: Int, sourceEndMs: Int): ClipTiming {
        if (json == null) return ClipTiming(freezeSourceMs = sourceStartMs)
        val mode = enumValueOrDefault(json.optString("mode"), ClipPlaybackMode.FORWARD)
        val endSafe = (sourceEndMs - 1).coerceAtLeast(sourceStartMs)
        return ClipTiming(
            speed = json.optDouble("speed", 1.0).toFloat().coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED),
            mode = mode,
            freezeSourceMs = json.optInt("freezeSourceMs", sourceStartMs).coerceIn(sourceStartMs, endSafe),
            freezeDurationMs = json.optInt("freezeDurationMs", ClipTiming.DEFAULT_FREEZE_DURATION_MS)
                .coerceIn(ClipTiming.MIN_FREEZE_DURATION_MS, ClipTiming.MAX_FREEZE_DURATION_MS)
        )
    }

    private fun parseClipTransform(json: JSONObject?): ClipTransform {
        if (json == null) return ClipTransform()
        val fitMode = enumValueOrDefault(json.optString("fitMode"), ClipFitMode.FIT)
        return VisualTransformMath.normalize(
            ClipTransform(
                scale = json.optDouble("scale", 1.0).toFloat(),
                positionX = json.optDouble("positionX", 0.0).toFloat(),
                positionY = json.optDouble("positionY", 0.0).toFloat(),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
                flipHorizontal = json.optBoolean("flipHorizontal", false),
                flipVertical = json.optBoolean("flipVertical", false),
                opacity = json.optDouble("opacity", 1.0).toFloat(),
                cropLeft = json.optDouble("cropLeft", 0.0).toFloat(),
                cropTop = json.optDouble("cropTop", 0.0).toFloat(),
                cropRight = json.optDouble("cropRight", 0.0).toFloat(),
                cropBottom = json.optDouble("cropBottom", 0.0).toFloat(),
                fitMode = fitMode
            )
        )
    }

    private fun parseMask(json: JSONObject?): MaskSpec {
        if (json == null) return MaskSpec()
        return MaskChromaComposition.normalize(
            MaskSpec(
                shape = enumValueOrDefault(json.optString("shape"), MaskShape.NONE),
                centerX = json.optDouble("centerX", 0.5).toFloat(),
                centerY = json.optDouble("centerY", 0.5).toFloat(),
                width = json.optDouble("width", 0.78).toFloat(),
                height = json.optDouble("height", 0.78).toFloat(),
                feather = json.optDouble("feather", 0.0).toFloat(),
                inverted = json.optBoolean("inverted", false)
            )
        )
    }

    private fun parseChromaKey(json: JSONObject?): ChromaKeySpec {
        if (json == null) return ChromaKeySpec()
        return MaskChromaComposition.normalize(
            ChromaKeySpec(
                enabled = json.optBoolean("enabled", false),
                keyColorArgb = json.optInt("keyColorArgb", 0xFF00FF00.toInt()),
                tolerance = json.optDouble("tolerance", 0.22).toFloat(),
                softness = json.optDouble("softness", 0.10).toFloat(),
                spill = json.optDouble("spill", 0.15).toFloat()
            )
        )
    }

    private fun parseCanvasSettings(item: JSONObject): CanvasSettings {
        val json = item.optJSONObject("canvas") ?: return CanvasSettings()
        return CanvasSettings(
            aspect = enumValueOrDefault(json.optString("aspect"), CanvasAspect.SOURCE),
            background = enumValueOrDefault(json.optString("background"), CanvasBackground.BLACK)
        )
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
                        muted = clip.optBoolean("muted", false),
                        fadeInMs = clip.optInt("fadeInMs", 0).coerceAtLeast(0),
                        fadeOutMs = clip.optInt("fadeOutMs", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    private fun parseOverlayAssets(item: JSONObject): List<OverlayAsset> {
        val array = item.optJSONArray("overlayAssets") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val asset = array.optJSONObject(index) ?: continue
                val id = asset.optString("id")
                val uri = asset.optString("uri")
                if (id.isBlank() || uri.isBlank()) continue
                add(
                    OverlayAsset(
                        id = id,
                        uri = uri,
                        displayName = asset.optString("displayName").ifBlank { "Overlay" },
                        type = enumValueOrDefault(asset.optString("type"), OverlayMediaType.IMAGE),
                        durationMs = asset.optInt("durationMs", 0).coerceAtLeast(0),
                        width = asset.optInt("width", 0).coerceAtLeast(0),
                        height = asset.optInt("height", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    private fun parseOverlayClips(item: JSONObject, assets: List<OverlayAsset>): List<OverlayClip> {
        val assetIds = assets.mapTo(mutableSetOf()) { it.id }
        val array = item.optJSONArray("overlayClips") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val clip = array.optJSONObject(index) ?: continue
                val id = clip.optString("id")
                val assetId = clip.optString("assetId")
                if (id.isBlank() || assetId !in assetIds) continue
                val duration = clip.optInt("durationMs", 0).coerceAtLeast(0)
                if (duration <= 0) continue
                add(
                    OverlayClip(
                        id = id,
                        assetId = assetId,
                        timelineStartMs = clip.optInt("timelineStartMs", 0).coerceAtLeast(0),
                        durationMs = duration,
                        sourceStartMs = clip.optInt("sourceStartMs", 0).coerceAtLeast(0),
                        zIndex = clip.optInt("zIndex", 0).coerceAtLeast(0),
                        transform = parseClipTransform(clip.optJSONObject("transform")),
                        keyframes = parseTransformKeyframes(clip.optJSONObject("keyframes"))
                    ).let { parsed ->
                        parsed.copy(keyframes = KeyframeEngine.normalize(parsed.keyframes, parsed.durationMs))
                    }
                )
            }
        }
    }

    private fun parseTextClips(item: JSONObject): List<TextClip> {
        val array = item.optJSONArray("textClips") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val clip = array.optJSONObject(index) ?: continue
                val id = clip.optString("id")
                val text = clip.optString("text").trim().take(TextTimelineEditor.MAX_TEXT_LENGTH)
                val duration = clip.optInt("durationMs", 0).coerceAtLeast(0)
                if (id.isBlank() || text.isBlank() || duration <= 0) continue
                val styleJson = clip.optJSONObject("style")
                val transformJson = clip.optJSONObject("transform")
                add(
                    TextClip(
                        id = id,
                        text = text,
                        timelineStartMs = clip.optInt("timelineStartMs", 0).coerceAtLeast(0),
                        durationMs = duration,
                        zIndex = clip.optInt("zIndex", 0).coerceAtLeast(0),
                        style = TextTimelineEditor.normalizeStyle(
                            TextStyle(
                                fontSizeSp = styleJson?.optDouble("fontSizeSp", 32.0)?.toFloat() ?: 32f,
                                textColorArgb = styleJson?.optInt("textColorArgb", 0xFFFFFFFF.toInt()) ?: 0xFFFFFFFF.toInt(),
                                backgroundColorArgb = styleJson?.optInt("backgroundColorArgb", 0x00000000) ?: 0x00000000,
                                bold = styleJson?.optBoolean("bold", true) ?: true,
                                alignment = enumValueOrDefault(styleJson?.optString("alignment").orEmpty(), TextAlignment.CENTER),
                                fontFamily = enumValueOrDefault(styleJson?.optString("fontFamily").orEmpty(), TextFontFamily.SANS),
                                letterSpacingEm = styleJson?.optDouble("letterSpacingEm", 0.0)?.toFloat() ?: 0f,
                                shadowEnabled = styleJson?.optBoolean("shadowEnabled", false) ?: false
                            )
                        ),
                        transform = TextTimelineEditor.normalizeTransform(
                            TextTransform(
                                scale = transformJson?.optDouble("scale", 1.0)?.toFloat() ?: 1f,
                                positionX = transformJson?.optDouble("positionX", 0.0)?.toFloat() ?: 0f,
                                positionY = transformJson?.optDouble("positionY", 0.55)?.toFloat() ?: 0.55f,
                                rotationDegrees = transformJson?.optDouble("rotationDegrees", 0.0)?.toFloat() ?: 0f,
                                opacity = transformJson?.optDouble("opacity", 1.0)?.toFloat() ?: 1f
                            )
                        ),
                        preset = enumValueOrDefault(clip.optString("preset"), TextPreset.CUSTOM),
                        animation = parseTextAnimation(clip.optJSONObject("animation"))
                    )
                )
            }
        }
    }

    private fun parseCaptionSegments(item: JSONObject): List<CaptionSegment> {
        val array = item.optJSONArray("captionSegments") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val segment = array.optJSONObject(index) ?: continue
                val id = segment.optString("id")
                val text = segment.optString("text").trim().take(CaptionTimelineEditor.MAX_TEXT_LENGTH)
                val duration = segment.optInt("durationMs", 0).coerceAtLeast(0)
                if (id.isBlank() || text.isBlank() || duration <= 0) continue
                add(
                    CaptionSegment(
                        id = id,
                        text = text,
                        timelineStartMs = segment.optInt("timelineStartMs", 0).coerceAtLeast(0),
                        durationMs = duration,
                        preset = enumValueOrDefault(segment.optString("preset"), CaptionPreset.BOXED),
                        fontFamily = enumValueOrDefault(segment.optString("fontFamily"), TextFontFamily.SANS),
                        animation = parseTextAnimation(segment.optJSONObject("animation"))
                    )
                )
            }
        }
    }

    private fun parseEffectClips(item: JSONObject): List<EffectClip> {
        val array = item.optJSONArray("effectClips") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val effect = array.optJSONObject(index) ?: continue
                val id = effect.optString("id")
                val duration = effect.optInt("durationMs", 0)
                if (id.isBlank() || duration <= 0) continue
                add(
                    EffectClip(
                        id = id,
                        timelineStartMs = effect.optInt("timelineStartMs", 0).coerceAtLeast(0),
                        durationMs = duration,
                        kind = enumValueOrDefault(effect.optString("kind"), VideoEffectKind.WARM),
                        intensity = effect.optDouble("intensity", 0.6).toFloat().coerceIn(0f, 1f)
                    )
                )
            }
        }
    }

    private fun parseTransition(json: JSONObject?): TransitionSpec {
        if (json == null) return TransitionSpec()
        return TransitionSpec(
            kind = enumValueOrDefault(json.optString("kind"), TransitionKind.NONE),
            durationMs = json.optInt("durationMs", TransitionSpec.DEFAULT_DURATION_MS)
                .coerceIn(TransitionSpec.MIN_DURATION_MS, TransitionSpec.MAX_DURATION_MS)
        )
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
                    .put("width", asset.width)
                    .put("height", asset.height)
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
                    .put("transform", transformToJson(clip.transform))
                    .put("keyframes", transformKeyframesToJson(clip.keyframes))
                    .put("timing", timingToJson(clip.timing))
                    .put("transitionOut", transitionToJson(clip.transitionOut))
                    .put("mask", maskToJson(clip.mask))
                    .put("chromaKey", chromaKeyToJson(clip.chromaKey))
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
                    .put("fadeInMs", clip.fadeInMs)
                    .put("fadeOutMs", clip.fadeOutMs)
            )
        }

        val overlayAssetArray = JSONArray()
        project.overlayAssets.forEach { asset ->
            overlayAssetArray.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("uri", asset.uri)
                    .put("displayName", asset.displayName)
                    .put("type", asset.type.name)
                    .put("durationMs", asset.durationMs)
                    .put("width", asset.width)
                    .put("height", asset.height)
            )
        }

        val overlayClipArray = JSONArray()
        project.overlayClips.forEach { clip ->
            overlayClipArray.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("assetId", clip.assetId)
                    .put("timelineStartMs", clip.timelineStartMs)
                    .put("durationMs", clip.durationMs)
                    .put("sourceStartMs", clip.sourceStartMs)
                    .put("zIndex", clip.zIndex)
                    .put("transform", transformToJson(clip.transform))
                    .put("keyframes", transformKeyframesToJson(clip.keyframes))
            )
        }

        val textClipArray = JSONArray()
        project.textClips.forEach { clip ->
            textClipArray.put(
                JSONObject()
                    .put("id", clip.id)
                    .put("text", clip.text)
                    .put("timelineStartMs", clip.timelineStartMs)
                    .put("durationMs", clip.durationMs)
                    .put("zIndex", clip.zIndex)
                    .put(
                        "style",
                        JSONObject()
                            .put("fontSizeSp", clip.style.fontSizeSp.toDouble())
                            .put("textColorArgb", clip.style.textColorArgb)
                            .put("backgroundColorArgb", clip.style.backgroundColorArgb)
                            .put("bold", clip.style.bold)
                            .put("alignment", clip.style.alignment.name)
                            .put("fontFamily", clip.style.fontFamily.name)
                            .put("letterSpacingEm", clip.style.letterSpacingEm.toDouble())
                            .put("shadowEnabled", clip.style.shadowEnabled)
                    )
                    .put(
                        "transform",
                        JSONObject()
                            .put("scale", clip.transform.scale.toDouble())
                            .put("positionX", clip.transform.positionX.toDouble())
                            .put("positionY", clip.transform.positionY.toDouble())
                            .put("rotationDegrees", clip.transform.rotationDegrees.toDouble())
                            .put("opacity", clip.transform.opacity.toDouble())
                    )
                    .put("preset", clip.preset.name)
                    .put("animation", textAnimationToJson(clip.animation))
            )
        }

        val captionSegmentArray = JSONArray()
        project.captionSegments.forEach { segment ->
            captionSegmentArray.put(
                JSONObject()
                    .put("id", segment.id)
                    .put("text", segment.text)
                    .put("timelineStartMs", segment.timelineStartMs)
                    .put("durationMs", segment.durationMs)
                    .put("preset", segment.preset.name)
                    .put("fontFamily", segment.fontFamily.name)
                    .put("animation", textAnimationToJson(segment.animation))
            )
        }

        val effectClipArray = JSONArray()
        project.effectClips.forEach { effect ->
            effectClipArray.put(
                JSONObject()
                    .put("id", effect.id)
                    .put("timelineStartMs", effect.timelineStartMs)
                    .put("durationMs", effect.durationMs)
                    .put("kind", effect.kind.name)
                    .put("intensity", effect.intensity.toDouble())
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
            .put("overlayAssets", overlayAssetArray)
            .put("overlayClips", overlayClipArray)
            .put("textClips", textClipArray)
            .put("captionSegments", captionSegmentArray)
            .put("effectClips", effectClipArray)
            .put(
                "canvas",
                JSONObject()
                    .put("aspect", project.canvasSettings.aspect.name)
                    .put("background", project.canvasSettings.background.name)
            )
            .put("playheadMs", project.playheadMs)
            .put("selectedClipId", project.selectedClipId ?: "")
            .put("selectedAudioClipId", project.selectedAudioClipId ?: "")
            .put("selectedOverlayClipId", project.selectedOverlayClipId ?: "")
            .put("selectedTextClipId", project.selectedTextClipId ?: "")
            .put("selectedCaptionSegmentId", project.selectedCaptionSegmentId ?: "")
            .put("selectedEffectClipId", project.selectedEffectClipId ?: "")
            .put("timelineZoom", project.timelineZoom.toDouble())
            .put("timelineViewportStartMs", project.timelineViewportStartMs)
    }


    private fun maskToJson(mask: MaskSpec): JSONObject {
        val safe = MaskChromaComposition.normalize(mask)
        return JSONObject()
            .put("shape", safe.shape.name)
            .put("centerX", safe.centerX.toDouble())
            .put("centerY", safe.centerY.toDouble())
            .put("width", safe.width.toDouble())
            .put("height", safe.height.toDouble())
            .put("feather", safe.feather.toDouble())
            .put("inverted", safe.inverted)
    }

    private fun chromaKeyToJson(chroma: ChromaKeySpec): JSONObject {
        val safe = MaskChromaComposition.normalize(chroma)
        return JSONObject()
            .put("enabled", safe.enabled)
            .put("keyColorArgb", safe.keyColorArgb)
            .put("tolerance", safe.tolerance.toDouble())
            .put("softness", safe.softness.toDouble())
            .put("spill", safe.spill.toDouble())
    }

    private fun parseTransformKeyframes(json: JSONObject?): TransformKeyframeSet {
        if (json == null) return TransformKeyframeSet()

        fun parseTrack(name: String): List<FloatKeyframe> {
            val array = json.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val value = item.optDouble("value", Double.NaN).toFloat()
                    if (!value.isFinite()) continue
                    add(
                        FloatKeyframe(
                            timeMs = item.optInt("timeMs", 0).coerceAtLeast(0),
                            value = value,
                            easing = enumValueOrDefault(item.optString("easing"), KeyframeEasing.LINEAR)
                        )
                    )
                }
            }
        }

        return TransformKeyframeSet(
            scale = parseTrack("scale"),
            positionX = parseTrack("positionX"),
            positionY = parseTrack("positionY"),
            rotationDegrees = parseTrack("rotationDegrees"),
            opacity = parseTrack("opacity")
        )
    }

    private fun transformKeyframesToJson(keyframes: TransformKeyframeSet): JSONObject {
        fun track(points: List<FloatKeyframe>): JSONArray = JSONArray().apply {
            points.sortedBy { it.timeMs }.forEach { point ->
                put(
                    JSONObject()
                        .put("timeMs", point.timeMs.coerceAtLeast(0))
                        .put("value", point.value.toDouble())
                        .put("easing", point.easing.name)
                )
            }
        }

        return JSONObject()
            .put("scale", track(keyframes.scale))
            .put("positionX", track(keyframes.positionX))
            .put("positionY", track(keyframes.positionY))
            .put("rotationDegrees", track(keyframes.rotationDegrees))
            .put("opacity", track(keyframes.opacity))
    }

    private fun parseTextAnimation(json: JSONObject?): TextAnimationSpec {
        if (json == null) return TextAnimationSpec()
        return TextAnimationSpec(
            kind = enumValueOrDefault(json.optString("kind"), TextAnimationKind.NONE),
            inDurationMs = json.optInt("inDurationMs", 300),
            outDurationMs = json.optInt("outDurationMs", 250)
        )
    }

    private fun textAnimationToJson(animation: TextAnimationSpec): JSONObject = JSONObject()
        .put("kind", animation.kind.name)
        .put("inDurationMs", animation.inDurationMs)
        .put("outDurationMs", animation.outDurationMs)

    private fun transitionToJson(transition: TransitionSpec): JSONObject = JSONObject()
        .put("kind", transition.kind.name)
        .put("durationMs", transition.durationMs.coerceIn(TransitionSpec.MIN_DURATION_MS, TransitionSpec.MAX_DURATION_MS))

    private fun timingToJson(timing: ClipTiming): JSONObject {
        return JSONObject()
            .put("speed", timing.speed.coerceIn(ClipTiming.MIN_SPEED, ClipTiming.MAX_SPEED).toDouble())
            .put("mode", timing.mode.name)
            .put("freezeSourceMs", timing.freezeSourceMs)
            .put("freezeDurationMs", timing.freezeDurationMs.coerceIn(ClipTiming.MIN_FREEZE_DURATION_MS, ClipTiming.MAX_FREEZE_DURATION_MS))
    }

    private fun transformToJson(transform: ClipTransform): JSONObject {
        val safe = VisualTransformMath.normalize(transform)
        return JSONObject()
            .put("scale", safe.scale.toDouble())
            .put("positionX", safe.positionX.toDouble())
            .put("positionY", safe.positionY.toDouble())
            .put("rotationDegrees", safe.rotationDegrees.toDouble())
            .put("flipHorizontal", safe.flipHorizontal)
            .put("flipVertical", safe.flipVertical)
            .put("opacity", safe.opacity.toDouble())
            .put("cropLeft", safe.cropLeft.toDouble())
            .put("cropTop", safe.cropTop.toDouble())
            .put("cropRight", safe.cropRight.toDouble())
            .put("cropBottom", safe.cropBottom.toDouble())
            .put("fitMode", safe.fitMode.name)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T {
        if (raw.isBlank()) return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    companion object {
        private const val PREFS_NAME = "vedito_project_index_v2"
        private const val KEY_PROJECTS = "projects"
        private const val MAX_PROJECTS = 12
        private const val SCHEMA_VERSION = 16
    }
}
