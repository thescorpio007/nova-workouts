package com.nova.workouts

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Updates from GitHub Releases, checked three times before anything is installed:
 *  1. SHA256SUMS.txt must carry a valid signature (SHA256SUMS.sig) made with this app's own signing key,
 *  2. the downloaded APK's SHA-256 must match its line in SHA256SUMS.txt,
 *  3. the APK must be signed with the same key as the installed app (Android checks this again when installing).
 * Checked once a day and from the "Check for updates" button; one tap to install.
 * Only releases that passed the automatic test are used (test builds stay marked "pre-release").
 */
object Updater {
    private const val REPO = "thescorpio007/nova-workouts"
    private const val TAG_PREFIX = "build-"
    private const val CODE_BASE = 0L
    const val APP = "Nova Workouts"
    const val SIGN = "Designed by A.HAMROUNI"
    private const val CHANNEL = "nova_update"
    private const val NOTE_ID = 9701
    private const val JOB_ID = 9702
    private const val DAY = 24 * 3600_000L

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("nova_update", Context.MODE_PRIVATE)

    fun current(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        ((if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()) - CODE_BASE).toInt()
    } catch (_: Exception) { 0 }

    fun versionName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    /** Ready to install: (build, file, release notes) or null. */
    fun ready(c: Context): Triple<Int, File, String>? {
        val p = sp(c); val b = p.getInt("ready_build", 0); val f = File(p.getString("ready_path", "") ?: "")
        if (b <= current(c) || !f.exists()) return null
        return Triple(b, f, p.getString("ready_notes", "") ?: "")
    }

    fun status(c: Context): String = sp(c).getString("status", "") ?: ""
    fun lastCheck(c: Context): Long = sp(c).getLong("last_check", 0)
    private fun setStatus(c: Context, s: String) { sp(c).edit().putString("status", s).apply() }

    // ---------------------------------------------------------------- scheduling (once a day, any network)
    fun schedule(c: Context) {
        try {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
        } catch (_: Exception) { }
    }

    /** From the app's own start or service: check if the last check is over a day old. */
    fun maybe(c: Context) {
        schedule(c)
        if (System.currentTimeMillis() - lastCheck(c) < DAY) return
        val app = c.applicationContext
        Thread { try { check(app, manual = false) } catch (_: Exception) { } }.start()
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Blocking. Returns a short status line. */
    fun check(c: Context, manual: Boolean, progress: (String) -> Unit = {}): String {
        if (!busy.compareAndSet(false, true)) return "Already checking…"
        try {
            sp(c).edit().putLong("last_check", System.currentTimeMillis()).apply()
            progress("Checking GitHub…")
            val rel = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=30").toString(Charsets.UTF_8))
            val legacy = Build.VERSION.SDK_INT < 24
            var best: Triple<Int, org.json.JSONObject, Map<String, String>>? = null
            for (i in 0 until rel.length()) {
                val r = rel.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX)) continue
                val b = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: continue
                val a = r.optJSONArray("assets") ?: continue
                val urls = HashMap<String, String>()
                for (k in 0 until a.length()) a.getJSONObject(k).let { urls[it.optString("name")] = it.optString("browser_download_url") }
                if ("SHA256SUMS.txt" !in urls || "SHA256SUMS.sig" !in urls) continue
                if (urls.keys.none { apkFits(it, legacy) }) continue
                if (best == null || b > best.first) best = Triple(b, r, urls)
            }
            val cur = current(c)
            val pick = best
            if (pick == null || pick.first <= cur) {
                return done(c, "✓ Up to date (build %02d)".format(cur))
            }
            val (build, r, urls) = pick
            ready(c)?.let { if (it.first == build) { if (!manual) notifyReady(c, build); return done(c, "Build %02d is ready to install".format(build)) } }
            progress("Checking the signature of build %02d…".format(build))
            val sums = get(urls["SHA256SUMS.txt"]!!); val sig = get(urls["SHA256SUMS.sig"]!!)
            if (!signatureOk(c, sums, sig)) return done(c, "⚠ Build %02d refused: the checksum file is not signed with this app's key".format(build))
            val apkName = urls.keys.filter { apkFits(it, legacy) }.maxByOrNull { it.length }!!
            val want = sums.toString(Charsets.UTF_8).lines().map { it.trim() }.firstOrNull { l ->
                l.split(Regex("\\s+"), 2).getOrNull(1)?.trimStart('*')?.trim() == apkName }?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                ?: return done(c, "⚠ Build %02d refused: the APK is not in the checksum file".format(build))
            progress("Downloading build %02d…".format(build))
            val dir = File(c.filesDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, apkName)
            download(urls[apkName]!!, f) { pct -> progress("Downloading build %02d… %d%%".format(build, pct)) }
            val got = sha256(f)
            if (got != want) { f.delete(); return done(c, "⚠ Build %02d refused: the download does not match its SHA-256 (deleted)".format(build)) }
            if (!sameSigner(c, f)) { f.delete(); return done(c, "⚠ Build %02d refused: signed with another key (deleted)".format(build)) }
            sp(c).edit().putInt("ready_build", build).putString("ready_path", f.absolutePath)
                .putString("ready_notes", r.optString("body").take(800)).apply()
            notifyReady(c, build)
            return done(c, "✓ Build %02d downloaded and verified (signature ✓ · SHA-256 ✓ · same key ✓)".format(build))
        } catch (e: Exception) {
            return done(c, "⚠ Update check failed: " + (e.message ?: e.javaClass.simpleName))
        } finally { busy.set(false) }
    }

    private fun done(c: Context, s: String): String { setStatus(c, s); return s }

    private fun apkFits(name: String, legacy: Boolean) = name.endsWith(".apk") && (name.contains("Android5.0") == legacy)

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val h = URL(u).openConnection() as HttpURLConnection
            h.instanceFollowRedirects = false
            h.connectTimeout = 20_000; h.readTimeout = 60_000
            h.setRequestProperty("User-Agent", "$APP-updater")
            if (u.contains("api.github.com")) h.setRequestProperty("Accept", "application/vnd.github+json")
            val code = h.responseCode
            if (code in 300..399) { u = URL(URL(u), h.getHeaderField("Location")).toString(); h.disconnect(); return@repeat }
            if (code != 200) throw Exception("GitHub answered $code")
            return h
        }
        throw Exception("Too many redirects")
    }

    private fun get(url: String): ByteArray = open(url).let { h -> h.inputStream.use { it.readBytes() }.also { h.disconnect() } }

    private fun download(url: String, f: File, pct: (Int) -> Unit) {
        val h = open(url)
        val total = h.contentLength.toLong()
        var n = 0L; var last = -1
        h.inputStream.use { i -> f.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) { val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k); n += k
                if (total > 0) { val p = (n * 100 / total).toInt(); if (p != last && p % 5 == 0) { last = p; pct(p) } } }
        } }
        h.disconnect()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun certs(pm: PackageManager, pkg: String?, path: String?): List<ByteArray> {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val pi = if (path != null) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES) else pm.getPackageInfo(pkg!!, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = pi?.signingInfo ?: return emptyList()
                (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.map { it.toByteArray() } ?: emptyList()
            } else {
                val pi = if (path != null) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES) else pm.getPackageInfo(pkg!!, PackageManager.GET_SIGNATURES)
                pi?.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    /** The checksum file must be signed with this app's own key (the same key that signs the APK). */
    private fun signatureOk(c: Context, data: ByteArray, sig: ByteArray): Boolean {
        val mine = certs(c.packageManager, c.packageName, null)
        val cf = CertificateFactory.getInstance("X.509")
        for (der in mine) try {
            val cert = cf.generateCertificate(der.inputStream()) as X509Certificate
            val alg = if (cert.publicKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val s = Signature.getInstance(alg); s.initVerify(cert.publicKey); s.update(data)
            if (s.verify(sig)) return true
        } catch (_: Exception) { }
        return false
    }

    /** Same signing key as the installed app (when this Android can read the downloaded file's key). */
    private fun sameSigner(c: Context, f: File): Boolean {
        val theirs = certs(c.packageManager, null, f.absolutePath)
        if (theirs.isEmpty()) return true          // older Android can't read it; the installer checks it anyway
        val mine = certs(c.packageManager, c.packageName, null)
        return theirs.any { t -> mine.any { it.contentEquals(t) } }
    }

    /** Automatic build test: this build's own release must pass all three checks, and a changed file must be refused. */
    fun selfTest(c: Context, tag: String): String = try {
        val r = org.json.JSONObject(get("https://api.github.com/repos/$REPO/releases/tags/$tag").toString(Charsets.UTF_8))
        val a = r.getJSONArray("assets"); val urls = HashMap<String, String>()
        for (k in 0 until a.length()) a.getJSONObject(k).let { urls[it.optString("name")] = it.optString("browser_download_url") }
        val sums = get(urls["SHA256SUMS.txt"] ?: throw Exception("no SHA256SUMS.txt")); val sig = get(urls["SHA256SUMS.sig"] ?: throw Exception("no SHA256SUMS.sig"))
        check(signatureOk(c, sums, sig)) { "signature refused" }
        val bad = sums.copyOf().also { it[0] = (it[0] + 1).toByte() }
        check(!signatureOk(c, bad, sig)) { "a changed checksum file was accepted" }
        val apk = urls.keys.first { it.endsWith(".apk") }
        val f = File(c.cacheDir, "updtest.apk"); download(urls[apk]!!, f) { }
        val want = sums.toString(Charsets.UTF_8).lines().first { it.trim().endsWith(apk) }.split(Regex("\\s+"))[0].lowercase()
        check(sha256(f) == want) { "SHA-256 mismatch" }
        check(sameSigner(c, f)) { "different signing key" }
        f.delete()
        "PASS · signature ✓ · changed file refused ✓ · SHA-256 ✓ · same key ✓ · this build %02d".format(current(c))
    } catch (e: Throwable) { "FAIL · " + (e.message ?: e.javaClass.simpleName) }

    // ---------------------------------------------------------------- telling the family
    private fun notifyReady(c: Context, build: Int) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, NOTE_ID, Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(c)
            nm.notify(NOTE_ID, b.setSmallIcon(c.applicationInfo.icon).setContentTitle("$APP build %02d is ready".format(build))
                .setContentText("Downloaded and verified (signature, SHA-256, same key). Tap to install.")
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: Exception) { }
    }

    /** Shown once per build when the app opens (also on Android TV, where notifications are not seen). */
    fun offerOnOpen(a: Activity) {
        val r = ready(a) ?: return
        val p = sp(a)
        if (p.getInt("offered", 0) == r.first) return
        p.edit().putInt("offered", r.first).apply()
        a.startActivity(Intent(a, UpdateActivity::class.java))
    }

    // ---------------------------------------------------------------- installing
    /** Why it should not install right now (null = fine). */
    fun blockReason(@Suppress("UNUSED_PARAMETER") c: Context): String? = null

    fun install(a: Activity): String? {
        blockReason(a)?.let { return it }
        val r = ready(a) ?: return "Nothing to install"
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) } catch (_: Exception) { }
            return "Allow \"Install unknown apps\" for $APP, then come back and tap Install again"
        }
        val pi = a.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(a.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, r.second.length()).use { o -> r.second.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) 0x02000000 else 0)   // FLAG_MUTABLE
            val cb = PendingIntent.getBroadcast(a, id, Intent(a, UpdateReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
        return null
    }
}

/** Once a day: check GitHub (works when the app is closed). */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        val app = applicationContext
        Thread { try { Updater.check(app, manual = false) } catch (_: Exception) { }; jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters?): Boolean = true
}

/** Result of the install: Android may ask the family to confirm. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> android.widget.Toast.makeText(c, "Update not installed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** "Build NN is ready": what changed, the three checks, and one Install button. */
class UpdateActivity : Activity() {
    private val bg = 0xFFFDE1EA.toInt(); private val card = 0xFFFFF4F7.toInt(); private val accent = 0xFFC2507A.toInt()
    private val fg = 0xFF33252B.toInt(); private val muted = 0xFF7A6670.toInt()
    private lateinit var status: TextView
    private lateinit var installBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setLineSpacing(0f, 1.15f) }
    private fun btn(s: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = s; isAllCaps = false; textSize = 16f; setTextColor(if (primary) Color.WHITE else fg)
        background = GradientDrawable().apply { setColor(if (primary) accent else 0xFFF3C6D5.toInt()); cornerRadius = dp(12).toFloat() }
        isFocusable = true; setOnClickListener { click() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(18)) }
        root.addView(tv("⬆ $APP update", 22f, fg, true))
        val r = Updater.ready(this)
        val cur = Updater.current(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(16).toFloat() } }
        if (r == null) {
            box.addView(tv("No update waiting. You have build %02d.".format(cur), 15f, fg))
        } else {
            box.addView(tv("Build %02d → build %02d".format(cur, r.first), 18f, accent, true))
            box.addView(tv("✓ Checksum file signed with $APP's own key\n✓ SHA-256 of the download matches\n✓ Same signing key as the installed app", 14f, fg)
                .apply { setPadding(0, dp(10), 0, dp(10)) })
            if (r.third.isNotBlank()) box.addView(tv(r.third, 13f, muted))
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        status = tv(Updater.status(this), 13f, muted).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(status)
        installBtn = btn(if (r != null) "Install build %02d".format(r.first) else "Check for updates", true) {
            if (Updater.ready(this) != null) {
                val err = Updater.install(this)
                status.setTextColor(if (err == null) muted else accent)
                status.text = err ?: "Installing… Android may ask you to confirm."
            } else checkNow()
        }
        root.addView(installBtn, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        root.addView(btn("Later", false) { finish() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(tv("$SIGN\n" + Updater.versionName(this), 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(sys.left, sys.top, sys.right, sys.bottom); android.view.WindowInsets.CONSUMED }
        }
        setContentView(scroll)
        installBtn.requestFocus()
    }

    private fun checkNow() {
        installBtn.isEnabled = false
        val app = applicationContext
        Thread {
            val s = Updater.check(app, manual = true) { p -> runOnUiThread { status.text = p } }
            runOnUiThread { finish(); startActivity(Intent(this, UpdateActivity::class.java)); android.widget.Toast.makeText(app, s, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }

    private val SIGN get() = Updater.SIGN
    private val APP get() = Updater.APP
}
