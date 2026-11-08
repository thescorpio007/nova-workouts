package com.nova.workouts

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the log keeps, stored on this phone as JSON text (one entry per key).
 * The page reads and writes these keys through the NovaBridge; reminders and backups read them here.
 */
object Store {
    const val LOG = "taichi-log"
    const val WEIGHT = "taichi-weight"
    const val GOAL = "taichi-goal"
    const val WATER = "taichi-water"
    const val PROFILE = "taichi-profile"
    val KEYS = listOf(LOG, WEIGHT, GOAL, WATER, PROFILE)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("nova_data", Context.MODE_PRIVATE)

    fun get(c: Context, k: String): String? = sp(c).getString(k, null)

    fun set(c: Context, k: String, v: String?) {
        if (v == null) sp(c).edit().remove(k).commit() else sp(c).edit().putString(k, v).commit()
    }

    /** First start: the goal and profile already set up in the web version. */
    fun seed(c: Context) {
        val p = sp(c)
        if (p.getBoolean("seeded", false)) return
        if (get(c, GOAL) == null) set(c, GOAL, JSONObject().put("heightCm", 162).put("startKg", 90).put("targetKg", 65).toString())
        if (get(c, PROFILE) == null) set(c, PROFILE, JSONObject().put("name", "Asma").put("accent", "berry").put("bg", "pink").toString())
        p.edit().putBoolean("seeded", true).commit()
    }

    fun all(c: Context): JSONObject {
        val o = JSONObject()
        for (k in KEYS) get(c, k)?.let { o.put(k, it) }
        return o
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun obj(c: Context, k: String): JSONObject = try { JSONObject(get(c, k) ?: "{}") } catch (_: Exception) { JSONObject() }

    fun waterToday(c: Context): Int = obj(c, WATER).optInt(today(), 0)

    fun addWater(c: Context, ml: Int): Int {
        val w = obj(c, WATER); val d = today()
        val v = (w.optInt(d, 0) + ml).coerceAtLeast(0)
        if (v > 0) w.put(d, v) else w.remove(d)
        set(c, WATER, w.toString())
        return v
    }

    fun waterTargetMl(c: Context): Int {
        val l = obj(c, GOAL).optDouble("waterL", 2.0)
        return ((if (l.isNaN() || l <= 0) 2.0 else l) * 1000).toInt()
    }

    /** A tai chi session (full or short) or another workout is already logged today. */
    fun loggedToday(c: Context): Boolean {
        val e = obj(c, LOG).optJSONObject(today()) ?: return false
        val s = e.optString("status", "")
        val w = e.optJSONArray("workouts")
        return s == "done" || s == "partial" || (w != null && w.length() > 0)
    }

    fun name(c: Context): String = obj(c, PROFILE).optString("name", "").trim()
}
