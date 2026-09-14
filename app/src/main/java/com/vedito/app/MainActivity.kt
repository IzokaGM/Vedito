package com.vedito.app

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.vedito.app.data.ProjectStore
import com.vedito.app.ui.Ui
import com.vedito.app.ui.dp
import com.vedito.app.ui.safeClick

class MainActivity : Activity() {

    private lateinit var projectStore: ProjectStore
    private lateinit var recentContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow(window)
        projectStore = ProjectStore(this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        if (::recentContainer.isInitialized) renderRecentProject()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(22), dp(26), dp(22), dp(24))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(
            Ui.text(this, "Vedito", 30f, Ui.TEXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        top.addView(Ui.text(this, "0.1", 12f, Ui.MUTED, true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.SURFACE_2, 10, this@MainActivity)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        })

        root.addView(top)
        root.addView(Ui.text(this, "Create fast. Edit precisely.", 15f, Ui.MUTED).apply {
            setPadding(0, dp(7), 0, 0)
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.outlined(Ui.SURFACE, Ui.DIVIDER, 24, this@MainActivity)
            setPadding(dp(20), dp(22), dp(20), dp(20))
        }

        hero.addView(Ui.text(this, "Start something new", 22f, Ui.TEXT, true))
        hero.addView(Ui.text(this, "Choose a video and open the native editor.", 14f, Ui.MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })

        hero.addView(Ui.labelButton(this, "+  New project", Ui.ACCENT, Color.BLACK).apply {
            safeClick { chooseVideo() }
        })

        root.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) })

        val recentTitle = Ui.text(this, "Recent", 17f, Ui.TEXT, true)
        root.addView(recentTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) })

        recentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(recentContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(Ui.text(this, "Native Android foundation • No Metro required", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        })

        renderRecentProject()
        return root
    }

    private fun renderRecentProject() {
        recentContainer.removeAllViews()
        val recent = projectStore.recent()

        if (recent == null) {
            recentContainer.addView(Ui.text(this, "No projects yet", 14f, Ui.MUTED).apply {
                setPadding(dp(2), dp(12), 0, dp(12))
            })
            return
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.outlined(Ui.SURFACE, Ui.DIVIDER, 18, this@MainActivity)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            safeClick { openEditor(recent.uri, recent.name) }
        }

        card.addView(Ui.text(this, recent.name, 16f, Ui.TEXT, true).apply {
            maxLines = 1
        })
        card.addView(Ui.text(this, "Tap to continue editing", 13f, Ui.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
        recentContainer.addView(card)
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_VIDEO)
    }

    @Deprecated("Kept intentionally dependency-free for the native bootstrap")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        persistReadPermission(uri, data.flags)
        val name = displayName(uri) ?: "Untitled video"
        projectStore.saveRecent(uri.toString(), name)
        openEditor(uri.toString(), name)
    }

    private fun persistReadPermission(uri: Uri, returnedFlags: Int) {
        val flags = returnedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers grant session access only. The editor can still open immediately.
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun openEditor(uri: String, name: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_VIDEO_URI, uri)
            putExtra(EditorActivity.EXTRA_VIDEO_NAME, name)
        })
    }

    private fun configureWindow(window: Window) {
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Color.BLACK
    }

    companion object {
        private const val REQUEST_VIDEO = 1001
    }
}
