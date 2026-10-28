package com.keepalarm.todo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen alarm shown over the lock screen, with the edge glow behind it. */
class AlarmActivity : Activity() {
    private var noteId = 0L
    private val h = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            // Close this screen when the service stopped ringing (e.g. the 2-minute ignore timeout).
            if (AlarmService.ringingId != noteId) {
                finish()
            } else {
                h.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build(intent.getLongExtra("noteId", 0L))
        h.postDelayed(poll, 1500)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        build(intent.getLongExtra("noteId", 0L))
    }

    private fun build(id: Long) {
        noteId = id
        val note = Repo.get(this, id)
        if (note == null) {
            AlarmService.stop(this)
            finish()
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0F1318.toInt())
        root.addView(EdgeGlowView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(28), dp(70), dp(28), dp(40))

        val bell = TextView(this)
        bell.text = "⏰"
        bell.textSize = 56f
        bell.gravity = Gravity.CENTER
        col.addView(bell)

        val time = TextView(this)
        time.text = fmtClock(System.currentTimeMillis())
        time.setTextColor(0xFFBDC1C6.toInt())
        time.textSize = 18f
        time.gravity = Gravity.CENTER
        col.addView(time)

        val title = TextView(this)
        title.text = note.displayTitle()
        title.setTextColor(Color.WHITE)
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(18), 0, dp(14))
        col.addView(title)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val lines: List<String> = if (note.isChecklist) {
            note.items.take(8).map { (if (it.done) "☑  " else "☐  ") + it.text }
        } else {
            listOf(note.body.take(200))
        }
        for (l in lines) {
            val t = TextView(this)
            t.text = l
            t.setTextColor(0xFFE8EAED.toInt())
            t.textSize = 17f
            t.setPadding(0, dp(4), 0, dp(4))
            list.addView(t)
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val left = AlarmLogic.MAX_SNOOZE - note.snoozeCount
        val warn = TextView(this)
        warn.gravity = Gravity.CENTER
        warn.textSize = 14f
        if (left <= 1) {
            warn.text = "⚠ Snoozing again will LOCK your screen on this to-do list until you complete it."
            warn.setTextColor(0xFFFFB4AB.toInt())
        } else {
            warn.text = "$left snoozes left before your screen locks on this list."
            warn.setTextColor(0xFFBDC1C6.toInt())
        }
        warn.setPadding(0, 0, 0, dp(12))
        col.addView(warn)

        val snooze = Button(this)
        snooze.text = "Snooze 5 min"
        snooze.isAllCaps = false
        snooze.textSize = 18f
        snooze.setTextColor(Color.WHITE)
        snooze.background = roundBg(0xFF3C4043.toInt(), dpf(32))
        snooze.setOnClickListener {
            val locked = AlarmLogic.snooze(this, noteId)
            if (locked) FreezeActivity.launch(this, noteId)
            finish()
        }
        col.addView(snooze, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(12) })

        val dismiss = Button(this)
        dismiss.text = "Dismiss"
        dismiss.isAllCaps = false
        dismiss.textSize = 20f
        dismiss.setTextColor(Color.WHITE)
        dismiss.background = roundBg(0xFF1E8E3E.toInt(), dpf(32))
        dismiss.setOnClickListener {
            AlarmLogic.dismiss(this, noteId)
            finish()
        }
        col.addView(dismiss, LinearLayout.LayoutParams(-1, dp(64)))

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    @Deprecated("Back is blocked so the alarm cannot be dismissed by accident")
    override fun onBackPressed() {
        // ignored on purpose
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
