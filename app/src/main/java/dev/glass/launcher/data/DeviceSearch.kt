package dev.glass.launcher.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DeviceHitKind { Contact, Message, File }

/** Rehber / mesaj / dosya arama sonucu. Dokununca [intent] ilgili uygulamada açar. */
data class DeviceHit(val kind: DeviceHitKind, val title: String, val subtitle: String, val intent: Intent)

/**
 * Telefon içi arama: kişiler, SMS mesajları ve dosyalar. Yalnızca kullanıcı izin verdiyse çalışır;
 * her şey cihazda, çevrimdışı aranır ve hiçbir veri dışarı gönderilmez.
 */
object DeviceSearch {
    val mediaPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    )

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canContacts(ctx: Context) = granted(ctx, Manifest.permission.READ_CONTACTS)
    fun canMessages(ctx: Context) = granted(ctx, Manifest.permission.READ_SMS)
    fun allFiles() = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    fun canFiles(ctx: Context) = allFiles() || mediaPermissions.any { granted(ctx, it) }

    /** Açık olan aramalar için henüz verilmemiş izinler. */
    fun missingPermissions(ctx: Context, contacts: Boolean, messages: Boolean, files: Boolean): List<String> = buildList {
        if (contacts && !canContacts(ctx)) add(Manifest.permission.READ_CONTACTS)
        if (messages && !canMessages(ctx)) add(Manifest.permission.READ_SMS)
        if (files && !canFiles(ctx)) addAll(mediaPermissions)
    }

    suspend fun search(
        ctx: Context, q: String, contacts: Boolean, messages: Boolean, files: Boolean, limit: Int = 5,
    ): List<DeviceHit> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        buildList {
            if (contacts && canContacts(ctx)) runCatching { addAll(contacts(ctx, q, limit)) }
            if (messages && canMessages(ctx)) runCatching { addAll(messages(ctx, q, limit)) }
            if (files && canFiles(ctx)) runCatching { addAll(files(ctx, q, limit)) }
        }
    }

    private fun contacts(ctx: Context, q: String, limit: Int): List<DeviceHit> {
        val out = mutableListOf<DeviceHit>()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        val cols = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val key = c.getString(1) ?: continue
                val name = c.getString(2) ?: continue
                val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, key))
                out += DeviceHit(DeviceHitKind.Contact, name, "Kişi", intent)
            }
        }
        return out
    }

    private fun messages(ctx: Context, q: String, limit: Int): List<DeviceHit> {
        val out = mutableListOf<DeviceHit>()
        ctx.contentResolver.query(
            Uri.parse("content://sms"), arrayOf("address", "body"),
            "body LIKE ?", arrayOf("%$q%"), "date DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val addr = c.getString(0) ?: continue
                val body = c.getString(1) ?: ""
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(addr)))
                out += DeviceHit(DeviceHitKind.Message, addr, snippet(body, q), intent)
            }
        }
        return out
    }

    private fun files(ctx: Context, q: String, limit: Int): List<DeviceHit> {
        val out = mutableListOf<DeviceHit>()
        val uri = MediaStore.Files.getContentUri("external")
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$q%"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        ctx.contentResolver.query(uri, cols, args, null)?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: "*/*"
                val path = (c.getString(3) ?: "").trimEnd('/')
                val itemUri = ContentUris.withAppendedId(uri, id)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(itemUri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                out += DeviceHit(DeviceHitKind.File, name, path.ifEmpty { "Dosya" }, intent)
            }
        }
        return out
    }

    /** Mesaj gövdesinden, aranan kelimenin çevresinden kısa bir parça. */
    private fun snippet(body: String, q: String): String {
        val flat = body.replace('\n', ' ')
        val i = flat.lowercase().indexOf(q.lowercase())
        if (i < 0 || flat.length <= 60) return flat.take(60)
        val start = (i - 20).coerceAtLeast(0)
        val end = (start + 60).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}
