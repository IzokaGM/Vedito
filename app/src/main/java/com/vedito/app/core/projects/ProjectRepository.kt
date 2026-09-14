package com.vedito.app.core.projects

import android.content.Context
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
                    add(
                        Project(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            sourceUri = item.getString("sourceUri"),
                            updatedAt = item.getLong("updatedAt")
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
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("sourceUri", item.sourceUri)
                    .put("updatedAt", item.updatedAt)
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
