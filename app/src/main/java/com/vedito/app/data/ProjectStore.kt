package com.vedito.app.data

import android.content.Context

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("vedito_projects", Context.MODE_PRIVATE)

    data class RecentProject(
        val uri: String,
        val name: String
    )

    fun saveRecent(uri: String, name: String) {
        prefs.edit()
            .putString(KEY_URI, uri)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun recent(): RecentProject? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        val name = prefs.getString(KEY_NAME, "Untitled video") ?: "Untitled video"
        return RecentProject(uri, name)
    }

    companion object {
        private const val KEY_URI = "recent_uri"
        private const val KEY_NAME = "recent_name"
    }
}
