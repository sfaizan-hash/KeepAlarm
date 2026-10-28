package com.keepalarm.todo

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radius
    if (strokeW > 0) g.setStroke(strokeW, stroke)
    return g
}

fun ovalBg(color: Int, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.shape = GradientDrawable.OVAL
    g.setColor(color)
    if (strokeW > 0) g.setStroke(strokeW, stroke)
    return g
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("EEE, d MMM  h:mm a", Locale.getDefault()).format(Date(ms))

fun fmtClock(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

fun toast(c: Context, s: String) {
    Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
}

object Perms {
    fun overlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun notif(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun battery(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun fullScreen(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
    }

    fun listener(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
        return s != null && s.contains(c.packageName)
    }

    /** Best-effort check of the system "App pinning" switch (not available on every phone). */
    fun pinningEnabled(c: Context): Boolean {
        return try {
            Settings.System.getInt(c.contentResolver, "lock_to_app_enabled", 0) == 1 ||
                Settings.Secure.getInt(c.contentResolver, "lock_to_app_enabled", 0) == 1
        } catch (e: Exception) {
            true
        }
    }
}
