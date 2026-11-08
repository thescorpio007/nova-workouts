package com.nova.workouts

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backups in Download/NovaWorkouts, so the log can be restored after a reinstall
 * or on a new phone. File: NovaWorkouts-Backup-YYYY-MM-DD-HHMM.novaworkouts (AES-256-GCM, key from the password).
 * Automatic backup: at most once a day when the app is opened, keeping the last 14 files.
 */
object Backup {
    private val MAGIC = "NOVAWK1".toByteArray(Charsets.US_ASCII)
    private const val ITER = 120_000
    const val FOLDER = "NovaWorkouts"
    private const val PREFIX = "NovaWorkouts-Backup-"
    private const val EXT = ".novaworkouts"
    private const val KEEP = 14
    private const val DAY = 24 * 3600_000L

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("nova_backup", Context.MODE_PRIVATE)

    fun hasPassword(c: Context) = (sp(c).getString("pw", "") ?: "").isNotEmpty()
    fun setPassword(c: Context, pw: String) { sp(c).edit().putString("pw", pw).commit() }
    fun auto(c: Context) = sp(c).getBoolean("auto", false)
    fun setAuto(c: Context, on: Boolean) { sp(c).edit().putBoolean("auto", on).commit() }
    fun status(c: Context): String = sp(c).getString("status", "") ?: ""
    private fun setStatus(c: Context, s: String): String { sp(c).edit().putString("status", s).apply(); return s }

    private fun key(pw: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val k = f.generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun encrypt(plain: ByteArray, pw: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, key(pw, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + ci.doFinal(plain)
    }

    fun decrypt(data: ByteArray, pw: String): ByteArray {
        if (data.size < MAGIC.size + 28 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
            throw Exception("This is not a Nova Workouts backup file")
        var i = MAGIC.size
        val salt = data.copyOfRange(i, i + 16); i += 16
        val iv = data.copyOfRange(i, i + 12); i += 12
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, key(pw, salt), GCMParameterSpec(128, iv))
        return try { ci.doFinal(data.copyOfRange(i, data.size)) } catch (_: Exception) { throw Exception("Wrong password, or the file is damaged") }
    }

    private fun payload(c: Context): ByteArray = JSONObject()
        .put("app", "Nova Workouts").put("format", 1)
        .put("created", System.currentTimeMillis())
        .put("version", Updater.versionName(c))
        .put("data", Store.all(c)).toString().toByteArray(Charsets.UTF_8)

    fun needsStoragePermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Blocking. Returns a short status line. */
    fun write(c: Context, auto: Boolean): String {
        val pw = sp(c).getString("pw", "") ?: ""
        if (pw.isEmpty()) return setStatus(c, "Set a backup password first")
        if (needsStoragePermission(c)) return setStatus(c, "Allow storage access, then tap Back up now again")
        return try {
            val name = PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + EXT
            save(c, name, encrypt(payload(c), pw))
            prune(c)
            sp(c).edit().putLong("last", System.currentTimeMillis()).apply()
            val t = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
            setStatus(c, (if (auto) "Automatic backup" else "Backed up") + " $t · Download/$FOLDER/$name")
        } catch (e: Exception) {
            setStatus(c, "Backup failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** On opening the app: once a day when automatic backup is on. */
    fun autoMaybe(c: Context) {
        if (!auto(c) || !hasPassword(c) || needsStoragePermission(c)) return
        if (System.currentTimeMillis() - sp(c).getLong("last", 0) < DAY - 3600_000L) return
        val app = c.applicationContext
        Thread { try { write(app, auto = true) } catch (_: Exception) { } }.start()
    }

    private fun save(c: Context, name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("Could not create the file in Download/$FOLDER")
            (c.contentResolver.openOutputStream(uri) ?: throw Exception("Could not write the file")).use { it.write(bytes) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.exists() && !dir.mkdirs()) throw Exception("Could not create Download/$FOLDER")
            File(dir, name).writeBytes(bytes)
        }
    }

    /** Keep the newest 14 backup files this app made. */
    private fun prune(c: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val ids = ArrayList<Long>()
                c.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    arrayOf("%" + FOLDER + "%", "$PREFIX%"), MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cur ->
                    while (cur.moveToNext()) ids.add(cur.getLong(0))
                }
                ids.drop(KEEP).forEach { id -> c.contentResolver.delete(ContentUris.withAppendedId(col, id), null, null) }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            }
        } catch (_: Exception) { }
    }

    /** Restores every saved key from a backup file. Returns a short status line. */
    fun restore(c: Context, data: ByteArray, pw: String): String {
        val o = JSONObject(String(decrypt(data, pw), Charsets.UTF_8))
        if (o.optString("app") != "Nova Workouts") throw Exception("This backup is not from Nova Workouts")
        val d = o.getJSONObject("data")
        for (k in Store.KEYS) Store.set(c, k, if (d.has(k)) d.getString(k) else null)
        val t = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(o.optLong("created")))
        return setStatus(c, "Restored the backup from $t")
    }

    /** Automatic build test: encrypt → decrypt round trip, wrong password refused, file written to Download/NovaWorkouts. */
    fun selfTest(c: Context): String = try {
        val sample = "{\"2026-11-08\":750}".toByteArray()
        val enc = encrypt(sample, "test1234")
        check(decrypt(enc, "test1234").contentEquals(sample)) { "round trip changed the data" }
        val refused = try { decrypt(enc, "wrong"); false } catch (_: Exception) { true }
        check(refused) { "a wrong password was accepted" }
        val name = PREFIX + "selftest" + EXT
        if (!needsStoragePermission(c)) save(c, name, enc)
        "PASS · round trip ✓ · wrong password refused ✓ · file in Download/$FOLDER ✓"
    } catch (e: Throwable) { "FAIL · " + (e.message ?: e.javaClass.simpleName) }
}
