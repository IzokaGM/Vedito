package com.vedito.app.feature.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.vedito.app.R
import com.vedito.app.core.model.MediaAsset
import com.vedito.app.core.model.Project
import com.vedito.app.core.projects.ProjectRepository
import com.vedito.app.databinding.ActivityHomeBinding
import com.vedito.app.feature.editor.EditorActivity
import com.vedito.app.ui.applySystemBarInsets
import com.vedito.app.ui.configureVeditoSystemBars
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class HomeActivity : ComponentActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var projects: ProjectRepository

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) createProject(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureVeditoSystemBars()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        projects = ProjectRepository(this)

        binding.newProjectButton.setOnClickListener {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecentProjects()
    }

    private fun createProject(uri: Uri) {
        persistReadAccess(uri)
        val now = System.currentTimeMillis()
        val displayName = resolveDisplayName(uri)
        val asset = MediaAsset(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            displayName = displayName
        )
        val project = Project(
            id = UUID.randomUUID().toString(),
            title = displayName.substringBeforeLast('.').ifBlank { "Untitled edit" },
            updatedAt = now,
            assets = listOf(asset)
        )
        projects.save(project)
        openEditor(project.id)
    }

    private fun renderRecentProjects() {
        val recent = projects.list()
        binding.recentList.removeAllViews()
        binding.emptyRecentText.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE

        recent.forEach { project ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_recent_project, binding.recentList, false)
            val clipCount = project.clips.size.coerceAtLeast(project.assets.size)
            val audioCount = project.audioClips.size
            row.findViewById<TextView>(R.id.projectName).text = project.title
            val audioMeta = if (audioCount > 0) " · $audioCount audio" else ""
            row.findViewById<TextView>(R.id.projectMeta).text =
                "$clipCount clip${if (clipCount == 1) "" else "s"}$audioMeta · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.updatedAt))}"
            row.setOnClickListener { openEditor(project.id) }
            binding.recentList.addView(row)
        }
    }

    private fun openEditor(projectId: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT_ID, projectId))
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) return cursor.getString(column) ?: "Untitled edit"
            }
        }
        return "Untitled edit"
    }

    private fun persistReadAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
