package com.nova.workouts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject

/** The Nova Workouts log page, running inside the app with its data kept on this phone. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var loadedDay = ""
    private var openSettingsOnLoad = false

    companion object {
        const val RESTORE_REQ = 41
        const val NOTIF_REQ = 42
        const val STORAGE_REQ = 43
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.seed(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#FDE1EA")) }
        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.addJavascriptInterface(Bridge(), "NovaBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "file") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (openSettingsOnLoad) { openSettingsOnLoad = false; js("document.getElementById('profBtn').click()") }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)

        openSettingsOnLoad = intent.getBooleanExtra("settings", false)
        loadedDay = Store.today()
        web.loadUrl("file:///android_asset/www/index.html")

        Reminders.schedule(this)
        Updater.maybe(this)
        Backup.autoMaybe(this)
        Updater.offerOnOpen(this)
        selfTests(intent)
    }

    /** Used by the automatic build test only. */
    private fun selfTests(i: Intent) {
        val app = applicationContext
        if (i.getBooleanExtra("selftest", false))
            Thread { Log.i("NOVA_SELFTEST", Backup.selfTest(app)) }.start()
        i.getStringExtra("updtest")?.let { tag -> Thread { Log.i("NOVA_UPDTEST", Updater.selfTest(app, tag)) }.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selfTests(intent)
        if (intent.getBooleanExtra("settings", false)) js("document.getElementById('profBtn').click()")
    }

    override fun onResume() {
        super.onResume()
        // A new day since the page was opened: reload so "today" moves on; otherwise pick up changes
        // made from notifications (e.g. "+250 ml").
        if (loadedDay != Store.today()) { loadedDay = Store.today(); web.reload() }
        else js("window.novaReload&&novaReload()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.novaBack?novaBack():false") { r ->
            if (r != "true") finish()
        }
    }

    fun js(code: String) = runOnUiThread { try { web.evaluateJavascript(code, null) } catch (_: Exception) { } }

    private fun callback(fn: String, text: String) = js("window.$fn&&$fn(" + JSONObject.quote(text) + ")")

    // ---------------------------------------------------------------- restore
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RESTORE_REQ) return
        val uri: Uri = data?.data ?: run { callback("novaBackupStatus", "Restore cancelled"); return }
        val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) { callback("novaBackupStatus", "Could not read that file"); return }
        askPassword(bytes)
    }

    private fun askPassword(bytes: ByteArray) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Backup password"
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setMessage("This replaces everything in the log on this phone with the backup.")
            .setView(box)
            .setNegativeButton("Cancel") { _, _ -> callback("novaBackupStatus", "Restore cancelled") }
            .setPositiveButton("Restore") { _, _ ->
                val pw = input.text.toString()
                callback("novaBackupStatus", "Restoring…")
                val app = applicationContext
                Thread {
                    val msg = try { Backup.restore(app, bytes, pw) } catch (e: Exception) { "Restore failed: " + (e.message ?: "unknown error") }
                    runOnUiThread {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                        if (msg.startsWith("Restored")) { Reminders.schedule(app); web.reload() } else callback("novaBackupStatus", msg)
                    }
                }.start()
            }
            .show()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ)
    }

    private fun setBars(hex: String, dark: Boolean) {
        val col = try { Color.parseColor(hex.trim()) } catch (_: Exception) { return }
        root.setBackgroundColor(col)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION") window.statusBarColor = col
            @Suppress("DEPRECATION") window.navigationBarColor = col
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val f = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else f, f)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }

    /** What the page can ask the app to do. */
    inner class Bridge {
        private val app get() = applicationContext

        @JavascriptInterface fun get(k: String): String? = if (k in Store.KEYS) Store.get(app, k) else null
        @JavascriptInterface fun set(k: String, v: String?) { if (k in Store.KEYS) Store.set(app, k, v) }
        @JavascriptInterface fun version(): String = Updater.versionName(app)

        @JavascriptInterface fun setBars(bg: String, dark: Boolean) { runOnUiThread { this@MainActivity.setBars(bg, dark) } }

        // reminders
        @JavascriptInterface fun getReminders(): String = Reminders.config(app).toString()
        @JavascriptInterface fun setReminders(json: String): String = try {
            Reminders.save(app, json)
            val cfg = Reminders.config(app)
            if (cfg.optBoolean("waterOn") || cfg.optBoolean("workoutOn")) runOnUiThread { askNotifications() }
            Reminders.summary(app)
        } catch (e: Exception) { "Could not save the reminders: " + (e.message ?: "") }
        @JavascriptInterface fun reminderSummary(): String = Reminders.summary(app)

        // backup
        @JavascriptInterface fun hasBackupPassword(): Boolean = Backup.hasPassword(app)
        @JavascriptInterface fun setBackupPassword(pw: String) { if (pw.length >= 4) Backup.setPassword(app, pw) }
        @JavascriptInterface fun getAutoBackup(): Boolean = Backup.auto(app)
        @JavascriptInterface fun setAutoBackup(on: Boolean): String {
            if (on && !Backup.hasPassword(app)) return "Set a backup password first"
            Backup.setAuto(app, on)
            if (on && Backup.needsStoragePermission(app)) runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQ) }
            return if (on) "Automatic daily backup is on" else "Automatic backup is off"
        }
        @JavascriptInterface fun backupStatus(): String = Backup.status(app)
        @JavascriptInterface fun backupNow() {
            if (Backup.needsStoragePermission(app)) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQ) }
                callback("novaBackupStatus", "Allow storage access, then tap Back up now again"); return
            }
            Thread { callback("novaBackupStatus", Backup.write(app, auto = false)) }.start()
        }
        @JavascriptInterface fun restore() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                try { @Suppress("DEPRECATION") startActivityForResult(i, RESTORE_REQ) }
                catch (_: Exception) { callback("novaBackupStatus", "No file picker found on this phone") }
            }
        }

        // updates
        @JavascriptInterface fun updateStatus(): String = Updater.status(app)
        @JavascriptInterface fun checkUpdates() {
            Thread {
                val s = Updater.check(app, manual = true) { p -> callback("novaUpdateStatus", p) }
                callback("novaUpdateStatus", s)
                if (Updater.ready(app) != null) runOnUiThread { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) }
            }.start()
        }
    }
}
