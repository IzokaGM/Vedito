package com.vedito.app.feature.home

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import java.util.UUID
import java.util.concurrent.Executors

class HomeActivity : ComponentActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var projects: ProjectRepository
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private var recentExpanded = false

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

        binding.newProjectButton.setOnClickListener { launchVideoPicker() }
        binding.templatesCard.setOnClickListener { showTemplatePreviewNotice() }
        binding.draftsCard.setOnClickListener { scrollToRecentProjects() }
        binding.exportsCard.setOnClickListener { openMostRecentForExport() }
        binding.recentSeeAll.setOnClickListener {
            recentExpanded = !recentExpanded
            renderRecentProjects()
        }

        binding.templateCinematic.setOnClickListener { showTemplatePreviewNotice() }
        binding.templateSocial.setOnClickListener { showTemplatePreviewNotice() }
        binding.templateVlog.setOnClickListener { showTemplatePreviewNotice() }
        binding.templateMinimal.setOnClickListener { showTemplatePreviewNotice() }

        binding.navEdit.setOnClickListener { openMostRecentOrImport() }
        binding.navAssets.setOnClickListener { launchVideoPicker() }
        binding.navExport.setOnClickListener { openMostRecentForExport() }
        binding.navProfile.setOnClickListener {
            Toast.makeText(this, R.string.profile_later, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecentProjects()
    }

    override fun onDestroy() {
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun launchVideoPicker() {
        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
        binding.draftsCount.text = recent.size.toString()
        binding.emptyRecentText.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        binding.recentSeeAll.visibility = if (recent.size > RECENT_COLLAPSED_LIMIT) View.VISIBLE else View.GONE
        binding.recentSeeAll.setText(if (recentExpanded) R.string.show_less else R.string.see_all)
        binding.recentGrid.removeAllViews()

        val visibleProjects = if (recentExpanded) recent else recent.take(RECENT_COLLAPSED_LIMIT)
        visibleProjects.forEachIndexed { index, project ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_recent_project, binding.recentGrid, false)
            bindRecentProject(card, project)
            binding.recentGrid.addView(card, recentGridLayoutParams(index))
        }
    }

    private fun bindRecentProject(card: View, project: Project) {
        val clipCount = project.clips.size.coerceAtLeast(project.assets.size)
        val thumbnail = card.findViewById<ImageView>(R.id.projectThumbnail)
        card.findViewById<TextView>(R.id.projectName).text = project.title
        card.findViewById<TextView>(R.id.projectDuration).text = formatDuration(projectDurationMs(project))
        card.findViewById<TextView>(R.id.projectMeta).text =
            "$clipCount clip${if (clipCount == 1) "" else "s"} · ${resolutionLabel(project)}"
        card.setOnClickListener { openEditor(project.id) }

        val firstUri = project.assets.firstOrNull()?.uri?.takeIf { it.isNotBlank() } ?: return
        loadVideoThumbnail(Uri.parse(firstUri), thumbnail, project.id)
    }

    private fun recentGridLayoutParams(index: Int): GridLayout.LayoutParams {
        val gap = dp(5)
        return GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(index % 2, 1f)
            rowSpec = GridLayout.spec(index / 2)
            setMargins(
                if (index % 2 == 0) 0 else gap,
                0,
                if (index % 2 == 0) gap else 0,
                dp(10)
            )
        }
    }

    private fun loadVideoThumbnail(uri: Uri, target: ImageView, projectId: String) {
        target.tag = projectId
        thumbnailExecutor.execute {
            val bitmap = decodeVideoThumbnail(uri)
            if (bitmap == null || isDestroyed) return@execute
            runOnUiThread {
                if (target.tag == projectId && !isDestroyed) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeVideoThumbnail(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dp(360),
                    dp(210)
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun projectDurationMs(project: Project): Int {
        val timeline = project.clips.sumOf { it.durationMs }
        return if (timeline > 0) timeline else project.assets.maxOfOrNull { it.durationMs } ?: 0
    }

    private fun formatDuration(durationMs: Int): String {
        val totalSeconds = (durationMs.coerceAtLeast(0) / 1_000)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun resolutionLabel(project: Project): String {
        val asset = project.assets.firstOrNull() ?: return "Draft"
        val longEdge = maxOf(asset.width, asset.height)
        val shortEdge = minOf(asset.width, asset.height)
        return when {
            longEdge >= 3_840 || shortEdge >= 2_160 -> "4K"
            longEdge >= 1_920 || shortEdge >= 1_080 -> "1080p"
            longEdge >= 1_280 || shortEdge >= 720 -> "720p"
            asset.width > 0 && asset.height > 0 -> "${shortEdge}p"
            else -> "Draft"
        }
    }

    private fun openMostRecentOrImport() {
        val recent = projects.list().firstOrNull()
        if (recent == null) launchVideoPicker() else openEditor(recent.id)
    }

    private fun openMostRecentForExport() {
        val recent = projects.list().firstOrNull()
        if (recent == null) {
            launchVideoPicker()
            return
        }
        Toast.makeText(this, R.string.export_from_editor, Toast.LENGTH_SHORT).show()
        openEditor(recent.id)
    }

    private fun showTemplatePreviewNotice() {
        Toast.makeText(this, R.string.templates_later, Toast.LENGTH_SHORT).show()
    }

    private fun scrollToRecentProjects() {
        binding.homeScroll.post {
            binding.homeScroll.smoothScrollTo(0, binding.recentHeader.top)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val RECENT_COLLAPSED_LIMIT = 4
    }
}
