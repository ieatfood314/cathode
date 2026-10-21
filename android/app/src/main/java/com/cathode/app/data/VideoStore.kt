package com.cathode.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream

private val ID_IN_NAME = Regex("\\[([A-Za-z0-9_-]{6,})\\]\\.mp4$", RegexOption.IGNORE_CASE)

fun sanitizeName(s: String, maxLen: Int = 90): String {
    val clean = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.', ' ')
        .ifEmpty { "untitled" }
    return if (clean.length <= maxLen) clean else clean.take(maxLen).trim()
}

fun videoFileName(v: Video): String {
    val date = v.uploadDate?.takeIf { it.isNotBlank() } ?: "unknown-date"
    return "${sanitizeName(date, 12)} - ${sanitizeName(v.title.orEmpty())} [${v.id}].mp4"
}

fun channelDirName(v: Video): String = sanitizeName(v.channelTitle ?: "Unknown channel", 60)

fun parseIdFromName(name: String): String? = ID_IN_NAME.find(name)?.groupValues?.get(1)

/** Where saved videos live: the shared Movies/Cathode folder, or a folder you pick (SD card, USB drive…). */
sealed interface StoreLocation {
    data object DefaultMovies : StoreLocation
    data class CustomFolder(val treeUri: Uri, val label: String) : StoreLocation
}

fun resolveStore(context: Context, storageUri: String): StoreLocation {
    if (storageUri.isBlank()) return StoreLocation.DefaultMovies
    return runCatching {
        val uri = Uri.parse(storageUri)
        val doc = DocumentFile.fromTreeUri(context, uri)
        if (doc == null || !doc.exists()) return StoreLocation.DefaultMovies
        StoreLocation.CustomFolder(uri, doc.name ?: "chosen folder")
    }.getOrDefault(StoreLocation.DefaultMovies)
}

data class SavedFile(val videoId: String, val uri: Uri, val bytes: Long)

/**
 * Plain .mp4 files you can see in any file manager — never app-private data.
 * Source of truth is a folder scan (filenames carry the video id in [brackets]),
 * so files moved/renamed by hand are still recognized.
 */
object VideoStore {

    fun listSaved(context: Context, loc: StoreLocation): List<SavedFile> = when (loc) {
        is StoreLocation.DefaultMovies -> listMediaStore(context)
        is StoreLocation.CustomFolder -> listSaf(context, loc)
    }

    fun findUri(context: Context, loc: StoreLocation, videoId: String): Uri? =
        listSaved(context, loc).firstOrNull { it.videoId == videoId }?.uri

    fun delete(context: Context, loc: StoreLocation, videoId: String): Boolean {
        var gone = false
        when (loc) {
            is StoreLocation.DefaultMovies -> {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val toDelete = mutableListOf<Uri>()
                    while (c.moveToNext()) {
                        if (parseIdFromName(c.getString(nameCol)) == videoId) {
                            toDelete.add(Uri.withAppendedPath(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString()))
                        }
                    }
                    toDelete.forEach {
                        if (context.contentResolver.delete(it, null, null) > 0) gone = true
                    }
                }
            }
            is StoreLocation.CustomFolder -> {
                val root = DocumentFile.fromTreeUri(context, loc.treeUri) ?: return false
                root.listFiles().forEach { dir ->
                    val target = if (dir.isDirectory) {
                        dir.listFiles().firstOrNull {
                            !it.isDirectory && parseIdFromName(it.name.orEmpty()) == videoId
                        }
                    } else if (parseIdFromName(dir.name.orEmpty()) == videoId) dir else null
                    if (target != null && target.delete()) gone = true
                }
            }
        }
        return gone
    }

    fun clearAll(context: Context, loc: StoreLocation): Int {
        val ids = listSaved(context, loc).map { it.videoId }.toSet()
        var n = 0
        ids.forEach { if (delete(context, loc, it)) n++ }
        // Drop leftover partials too.
        when (loc) {
            is StoreLocation.DefaultMovies -> Unit // partials live in cache dir, cleaned with it
            is StoreLocation.CustomFolder -> {
                DocumentFile.fromTreeUri(context, loc.treeUri)?.listFiles()?.forEach { dir ->
                    (if (dir.isDirectory) dir.listFiles().toList() else listOf(dir))
                        .filter { it.name?.endsWith(".part") == true }
                        .forEach { runCatching { it.delete() } }
                }
            }
        }
        return n
    }

    // ---- MediaStore (default): Movies/Cathode/<Channel>/file.mp4 ----

    private fun listMediaStore(context: Context): List<SavedFile> {
        val out = mutableListOf<SavedFile>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.RELATIVE_PATH),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = parseIdFromName(c.getString(nameCol)) ?: continue
                val rel = c.getString(pathCol).orEmpty()
                if (!rel.contains("Movies/Cathode")) continue
                out.add(SavedFile(
                    videoId = id,
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol).toString()),
                    bytes = c.getLong(sizeCol),
                ))
            }
        }
        return out
    }

    /** Final resting place for a completed download (MediaStore default). */
    fun publishToMediaStore(context: Context, v: Video, part: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName(v))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cathode/${channelDirName(v)}/")
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("could not create library entry")
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            part.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("could not write library entry")
        return uri
    }

    // ---- SAF (SD card / USB / any chosen folder) ----

    private fun listSaf(context: Context, loc: StoreLocation.CustomFolder): List<SavedFile> {
        val out = mutableListOf<SavedFile>()
        val root = DocumentFile.fromTreeUri(context, loc.treeUri) ?: return out
        root.listFiles().forEach { dir ->
            val files = if (dir.isDirectory) dir.listFiles().toList() else listOf(dir)
            files.forEach { f ->
                if (!f.isDirectory && !f.name.orEmpty().endsWith(".part")) {
                    parseIdFromName(f.name.orEmpty())?.let { id ->
                        out.add(SavedFile(id, f.uri, f.length()))
                    }
                }
            }
        }
        return out
    }

    /** Channel subfolder inside the chosen tree (created on demand). */
    fun safDir(context: Context, loc: StoreLocation.CustomFolder, v: Video): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, loc.treeUri) ?: return null
        val name = channelDirName(v)
        return root.listFiles().firstOrNull { it.isDirectory && it.name == name }
            ?: root.createDirectory(name)
    }

    fun openSafAppend(context: Context, partUri: Uri): OutputStream? =
        // Append-only on purpose: falling back to "w" would truncate the
        // partial while the server sends a 206 body — a corrupt file.
        runCatching { context.contentResolver.openOutputStream(partUri, "wa") }.getOrNull()

    fun takeTreePermission(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** Scratch space for partial downloads (deleted on completion). Per-video so resume never mixes files. */
    fun scratchPart(context: Context, videoId: String): File {
        val dir = context.externalCacheDir ?: context.cacheDir
        val safe = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "cathode-$safe.bin")
    }
}
