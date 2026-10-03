package com.kwaris.shootcam

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Lists the clips saved in Movies/ShootCam: play, share, delete. */
class ClipsActivity : AppCompatActivity() {

    data class Clip(val uri: Uri, val name: String, val dateMs: Long, val durationMs: Long, val size: Long)

    private val clips = mutableListOf<Clip>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<Uri, Bitmap>(40)
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private val dateFmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.FRANCE)

    private val readPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    // Clips from a previous install are not "ours" any more: reading them needs the media permission.
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    // Deleting a clip we don't own asks the user to confirm through the system dialog.
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        title = getString(R.string.clips_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        list.emptyView = emptyView
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(clips[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> actions(clips[pos]); true }
        if (ContextCompat.checkSelfPermission(this, readPerm) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(readPerm)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        exec.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        exec.execute {
            val out = mutableListOf<Clip>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            )
            val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${ClipWriter.RELATIVE_DIR}%")
            val base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            try {
                contentResolver.query(base, proj, sel, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val taken = c.getLong(2)
                        out += Clip(
                            uri = ContentUris.withAppendedId(base, id),
                            name = c.getString(1) ?: "clip",
                            dateMs = if (taken > 0) taken else c.getLong(3) * 1000,
                            durationMs = c.getLong(4),
                            size = c.getLong(5),
                        )
                    }
                }
            } catch (_: Exception) {
            }
            main.post {
                clips.clear()
                clips.addAll(out)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun play(c: Clip) {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun share(c: Clip) {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, c.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Partager le clip"))
    }

    private fun actions(c: Clip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(arrayOf("Lire", "Partager", "Supprimer")) { _, which ->
                when (which) {
                    0 -> play(c)
                    1 -> share(c)
                    2 -> confirmDelete(c)
                }
            }
            .show()
    }

    private fun confirmDelete(c: Clip) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer ce clip ?")
            .setMessage(c.name)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                delete(c)
            }
            .show()
    }

    private fun delete(c: Clip) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    if (contentResolver.delete(c.uri, null, null) > 0) {
                        load(); return
                    }
                } catch (_: SecurityException) {
                }
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(c.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                try {
                    contentResolver.delete(c.uri, null, null)
                    load()
                } catch (e: RecoverableSecurityException) {
                    deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                }
            }
        } catch (_: Exception) {
            load()
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = clips.size
        override fun getItem(position: Int) = clips[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false)
            val c = clips[position]
            v.findViewById<TextView>(R.id.title).text = dateFmt.format(Date(c.dateMs))
            val shots = Regex("_(\\d+)tir").find(c.name)?.groupValues?.get(1)
            val dur = c.durationMs / 1000
            v.findViewById<TextView>(R.id.subtitle).text = buildString {
                if (shots != null) append(shots).append(if (shots == "1") " tir · " else " tirs · ")
                append(String.format(Locale.FRANCE, "%d:%02d", dur / 60, dur % 60))
                append(" · ").append(Formatter.formatShortFileSize(this@ClipsActivity, c.size))
            }
            val img = v.findViewById<ImageView>(R.id.thumb)
            img.tag = c.uri
            val cached = thumbs.get(c.uri)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                exec.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(c.uri, Size(320, 180), null)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        thumbs.put(c.uri, bmp)
                        main.post { if (img.tag == c.uri) img.setImageBitmap(bmp) }
                    }
                }
            }
            return v
        }
    }
}
