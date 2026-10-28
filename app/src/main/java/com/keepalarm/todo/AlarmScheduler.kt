package com.keepalarm.todo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object AlarmScheduler {
    private fun reqCode(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun pi(c: Context, id: Long): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction("com.keepalarm.todo.ALARM")
            .putExtra("noteId", id)
        return PendingIntent.getBroadcast(
            c, reqCode(id), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Uses setAlarmClock: the strongest kind of alarm. It ignores Doze and battery saver. */
    fun schedule(c: Context, n: Note) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c, n.id))
        if (n.alarmAt <= System.currentTimeMillis() || n.isDone()) return
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(n.alarmAt, show), pi(c, n.id))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.alarmAt, pi(c, n.id))
        }
    }

    fun cancel(c: Context, id: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c, id))
    }

    /** Called after reboot, app update or clock change. Alarms missed while off ring again soon. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        for (n in Repo.all(c)) {
            if (n.alarmAt in 1..now && !n.isDone() && now - n.alarmAt < 6 * 3600_000L) {
                n.alarmAt = now + 5_000
                Repo.put(c, n)
            }
            schedule(c, n)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("noteId", 0L)
        val s = Intent(c, AlarmService::class.java).putExtra("noteId", id)
        c.startForegroundService(s)
    }
}

object AlarmLogic {
    const val MAX_SNOOZE = 3
    const val SNOOZE_MS = 5 * 60 * 1000L

    /** Moves a repeating alarm to its next future time, or clears a one-time alarm. */
    fun advance(n: Note) {
        if (n.repeat == 0) {
            n.alarmAt = 0
            n.baseAlarm = 0
            return
        }
        val step = if (n.repeat == 1) 86_400_000L else 7 * 86_400_000L
        var b = if (n.baseAlarm > 0) n.baseAlarm else n.alarmAt
        val now = System.currentTimeMillis()
        while (b <= now) b += step
        n.baseAlarm = b
        n.alarmAt = b
    }

    /** Returns true when this was the 3rd snooze, which locks the screen on the to-do list. */
    fun snooze(c: Context, id: Long): Boolean {
        AlarmService.stop(c)
        val n = Repo.get(c, id) ?: return false
        n.snoozeCount += 1
        if (n.snoozeCount >= MAX_SNOOZE) {
            n.snoozeCount = 0
            advance(n)
            Repo.put(c, n)
            AlarmScheduler.schedule(c, n)
            Prefs.setLocked(c, n.id)
            return true
        }
        n.alarmAt = System.currentTimeMillis() + SNOOZE_MS
        Repo.put(c, n)
        AlarmScheduler.schedule(c, n)
        return false
    }

    fun dismiss(c: Context, id: Long) {
        AlarmService.stop(c)
        val n = Repo.get(c, id) ?: return
        n.snoozeCount = 0
        advance(n)
        Repo.put(c, n)
        AlarmScheduler.schedule(c, n)
    }
}
