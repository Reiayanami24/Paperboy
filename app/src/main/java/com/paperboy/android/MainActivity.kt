package com.paperboy.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Game library. ROMs are added with the system file picker (works under Android 11
 * scoped storage with no permissions) and copied into app-private storage.
 * Tap = play, long-press = delete.
 *
 * Plain black-on-white, large rows and no animations: this is what reads well and
 * refreshes cleanly on an e-ink panel (and is fine on a normal screen too).
 */
class MainActivity : Activity() {
    private companion object {
        const val REQ_IMPORT = 1001
    }

    private lateinit var store: GameStore
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var games: List<GameStore.Game> = emptyList()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GameStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val title = TextView(this).apply {
            text = "Paperboy"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(12))
        }
        val addButton = Button(this).apply {
            text = "Add games…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener { pickRoms() }
        }
        emptyView = TextView(this).apply {
            text = "No games yet.\nTap “Add games” and choose .gb, .gbc or .zip files."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
            visibility = View.GONE
        }
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, ArrayList<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                v.setTextColor(Color.BLACK)
                v.minHeight = dp(64)
                v.gravity = Gravity.CENTER_VERTICAL
                return v
            }
        }
        listView = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = android.graphics.drawable.ColorDrawable(Color.BLACK)
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ -> games.getOrNull(position)?.let { launch(it) } }
            setOnItemLongClickListener { _, _, position, _ ->
                games.getOrNull(position)?.let { confirmDelete(it) }
                true
            }
        }

        root.addView(title)
        root.addView(addButton)
        root.addView(emptyView)
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (Eink.isOnyx()) {
            root.addView(TextView(this).apply {
                text = "BOOX tip: for smooth play, set this app to a fast refresh mode in your BOOX " +
                    "refresh settings. In-game: Menu → Display rate and Clear screen ghosting."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(12), 0, 0)
            })
        }
        setContentView(root)
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        games = store.list()
        adapter.clear()
        adapter.addAll(games.map { if (it.gbcOnly) "${it.name}   (Color-only, may not work)" else it.name })
        emptyView.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launch(game: GameStore.Game) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ID, game.id)
                .putExtra(GameActivity.EXTRA_NAME, game.name)
        )
        overridePendingTransition(0, 0)
    }

    private fun confirmDelete(game: GameStore.Game) {
        AlertDialog.Builder(this)
            .setTitle(game.name)
            .setMessage("Remove this game and its saves from the app?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.delete(game.id)
                refresh()
            }
            .show()
    }

    private fun pickRoms() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return

        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) importAll(uris)
    }

    private fun importAll(uris: List<Uri>) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        io.execute {
            var added = 0
            var duplicates = 0
            val problems = ArrayList<String>()
            for (uri in uris) {
                when (val r = store.importRom(contentResolver, uri)) {
                    is GameStore.ImportResult.Added -> if (r.alreadyPresent) duplicates++ else added++
                    is GameStore.ImportResult.Failed -> problems.add(r.reason)
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refresh()
                val msg = buildString {
                    if (added > 0) append("Added $added game(s). ")
                    if (duplicates > 0) append("$duplicates already in the library. ")
                    for (p in problems) append("\n").append(p)
                }.trim()
                if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
