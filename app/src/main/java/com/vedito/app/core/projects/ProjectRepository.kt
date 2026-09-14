package com.vedito.app.core.projects

import android.content.Context
import com.vedito.app.core.model.Clip
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
                    val item = array.getJSONObject(index)
                    val clips = buildList {
                        val clipArray = item.optJSONArray("clips") ?: JSONArray()
                        for (clipIndex in 0 until clipArray.length()) {
                            val clip = clipArray.getJSONObject(clipIndex)
                            add(
                                Clip(
                                    id = clip.getString("id"),
                                    sourceStartMs = clip.optInt("sourceStartMs", 0),
                                    sourceEndMs = clip.optInt("sourceEndMs", 0)
                                )
                            )
                        }
                    }
                    add(
                        Project(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            sourceUri = item.getString("sourceUri"),
                            updatedAt = item.getLong("updatedAt"),
                            sourceDurationMs = item.optInt("sourceDurationMs", 0),
                            clips = clips,
                            playheadMs = item.optInt("playheadMs", 0),
                            selectedClipId = item.optString("selectedClipId").takeIf { it.isNotBlank() }
                        )
                    )
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
        projects.forEach { item ->
            val clipArray = JSONArray()
            item.clips.forEach { clip ->
                clipArray.put(
                    JSONObject()
                        .put("id", clip.id)
                        .put("sourceStartMs", clip.sourceStartMs)
                        .put("sourceEndMs", clip.sourceEndMs)
                )
            }
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("sourceUri", item.sourceUri)
                    .put("updatedAt", item.updatedAt)
                    .put("sourceDurationMs", item.sourceDurationMs)
                    .put("clips", clipArray)
                    .put("playheadMs", item.playheadMs)
                    .put("selectedClipId", item.selectedClipId ?: "")
            )
        }
        preferences.edit().putString(KEY_PROJECTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vedito_project_index_v2"
        private const val KEY_PROJECTS = "projects"
        private const val MAX_PROJECTS = 12
    }
}
