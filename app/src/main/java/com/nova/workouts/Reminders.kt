package com.nova.workouts

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Water and workout reminders, with times set in the app.
 *  - Water: every N minutes between two times; stops for the day once the water target is reached.
 *    The notification has a "+250 ml" button that logs a glass without opening the app.
 *  - Workout: once a day at a set time; skipped when a session or workout is already logged today.
 */
object Reminders {
    const val WATER = "com.nova.workouts.WATER"
    const val WORKOUT = "com.nova.workouts.WORKOUT"
    const val ADD_WATER = "com.nova.workouts.ADD_WATER"
    private const val CHANNEL = "nova_reminders"
    const val WATER_NOTE = 8801
    const val WORKOUT_NOTE = 8802

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("nova_reminders", Context.MODE_PRIVATE)

    fun defaults(): JSONObject = JSONObject()
        .put("waterOn", false).put("waterEvery", 120).put("waterFrom", "09:00").put("waterTo", "21:00")
        .put("workoutOn", false).put("workoutTime", "18:00")

    fun config(c: Context): JSONObject {
        val d = defaults()
        val s = sp(c).getString("cfg", null) ?: return d
        return try {
            val o = JSONObject(s)
            val it = d.keys()
            while (it.hasNext()) { val k = it.next(); if (!o.has(k)) o.put(k, d.get(k)) }
            o
        } catch (_: Exception) { d }
    }

    fun save(c: Context, json: String) {
        val o = JSONObject(json)
        val every = o.optInt("waterEvery", 120).coerceIn(30, 360)
        o.put("waterEvery", every)
        sp(c).edit().putString("cfg", o.toString()).commit()
        schedule(c)
    }

    private fun minutes(hhmm: String, def: Int): Int = try {
        val p = hhmm.split(":"); p[0].toInt() * 60 + p[1].toInt()
    } catch (_: Exception) { def }

    private fun at(base: Calendar, dayOffset: Int, minuteOfDay: Int): Long = (base.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextWater(cfg: JSONObject, now: Long = System.currentTimeMillis()): Long {
        val from = minutes(cfg.optString("waterFrom"), 9 * 60)
        val to = minutes(cfg.optString("waterTo"), 21 * 60).let { if (it <= from) from + 60 else it }
        val every = cfg.optInt("waterEvery", 120).coerceIn(30, 360)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var m = from
        while (m <= to) { val t = at(cal, 0, m); if (t > now + 30_000) return t; m += every }
        return at(cal, 1, from)
    }

    fun nextWorkout(cfg: JSONObject, now: Long = System.currentTimeMillis()): Long {
        val m = minutes(cfg.optString("workoutTime"), 18 * 60)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val t = at(cal, 0, m)
        return if (t > now + 30_000) t else at(cal, 1, m)
    }

    private fun pending(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val cfg = config(c)
        val w = pending(c, WATER, 1); val k = pending(c, WORKOUT, 2)
        am.cancel(w); am.cancel(k)
        if (cfg.optBoolean("waterOn")) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWater(cfg), w)
        if (cfg.optBoolean("workoutOn")) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWorkout(cfg), k)
    }

    /** Text shown in the app under the reminder settings. */
    fun summary(c: Context): String {
        val cfg = config(c); val parts = ArrayList<String>()
        val f = { t: Long -> String.format(Locale.getDefault(), "%1\$ta %1\$tH:%1\$tM", t) }
        if (cfg.optBoolean("waterOn")) parts.add("Next water reminder " + f(nextWater(cfg)))
        if (cfg.optBoolean("workoutOn")) parts.add("Next workout reminder " + f(nextWorkout(cfg)))
        return if (parts.isEmpty()) "Reminders are off" else parts.joinToString(" · ")
    }

    private fun channel(c: Context): NotificationManager? {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }

    private fun builder(c: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(c)

    private fun openApp(c: Context, code: Int): PendingIntent =
        PendingIntent.getActivity(c, code, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun litres(ml: Int) = String.format(Locale.US, "%.2f L", ml / 1000.0)

    fun notifyWater(c: Context, logged: Boolean = false) {
        val nm = channel(c) ?: return
        val now = Store.waterToday(c); val tgt = Store.waterTargetMl(c)
        val title = if (logged) "Logged 250 ml of water" else "Time for a glass of water"
        val text = "Today: ${litres(now)} of ${litres(tgt)}" + if (now >= tgt) " · goal met" else ""
        val b = builder(c).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setContentIntent(openApp(c, 11)).setAutoCancel(true)
        if (now < tgt) {
            val icon: android.graphics.drawable.Icon? = null
            b.addAction(Notification.Action.Builder(icon, "+250 ml", pending(c, ADD_WATER, 3)).build())
        }
        nm.notify(WATER_NOTE, b.build())
    }

    fun notifyWorkout(c: Context) {
        val nm = channel(c) ?: return
        val n = Store.name(c)
        nm.notify(WORKOUT_NOTE, builder(c).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (n.isNotEmpty()) "Time to move, $n" else "Time for your workout")
            .setContentText("Tai chi or another workout today? Tap to log it.")
            .setContentIntent(openApp(c, 12)).setAutoCancel(true).build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Reminders.WATER -> {
                if (Store.waterToday(c) < Store.waterTargetMl(c)) Reminders.notifyWater(c)
                Reminders.schedule(c)
            }
            Reminders.WORKOUT -> {
                if (!Store.loggedToday(c)) Reminders.notifyWorkout(c)
                Reminders.schedule(c)
            }
            Reminders.ADD_WATER -> {
                Store.addWater(c, 250)
                Reminders.notifyWater(c, logged = true)
            }
        }
    }
}

/** After a restart or an update, set the reminders again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { Reminders.schedule(c) } catch (_: Exception) { }
        try { Updater.schedule(c) } catch (_: Exception) { }
    }
}
