package com.keepalarm.todo

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * WhatsApp-style floating bubble. It stays on top of every app, can be dragged anywhere,
 * snaps to the screen edge, and opens a small to-do popup when tapped.
 */
class BubbleService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: FrameLayout? = null
    private var badge: TextView? = null
    private var panel: LinearLayout? = null
    private var listBox: LinearLayout? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            addBubble()
            tick()
        }
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("bubble_channel", "Floating bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "bubble_channel")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Keep Alarm bubble is on")
            .setContentText("Tap to open your to-dos")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4343, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4343, n)
        }
    }

    // ------------------------------------------------------------ bubble

    private fun addBubble() {
        val size = dp(58)
        val fl = FrameLayout(this)

        val circle = TextView(this)
        circle.text = "✓"
        circle.textSize = 28f
        circle.setTextColor(Color.WHITE)
        circle.setTypeface(null, Typeface.BOLD)
        circle.gravity = Gravity.CENTER
        circle.background = ovalBg(0xFF1A73E8.toInt(), Color.WHITE, dp(2))
        circle.elevation = dpf(6)
        fl.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        val b = TextView(this)
        b.textSize = 11f
        b.setTextColor(Color.WHITE)
        b.setTypeface(null, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.background = ovalBg(0xFFD93025.toInt())
        b.elevation = dpf(8)
        b.visibility = View.GONE
        fl.addView(b, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        badge = b

        bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bubbleLp.gravity = Gravity.TOP or Gravity.START
        bubbleLp.x = 0
        bubbleLp.y = dp(220)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        fl.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleLp.x
                    startY = bubbleLp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        bubbleLp.x = startX + dx
                        bubbleLp.y = startY + dy
                        try { wm.updateViewLayout(fl, bubbleLp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel() else snapToEdge(fl)
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(fl, bubbleLp)
            bubble = fl
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun snapToEdge(v: View) {
        val screenW = resources.displayMetrics.widthPixels
        val w = if (v.width > 0) v.width else dp(58)
        val target = if (bubbleLp.x + w / 2 < screenW / 2) 0 else screenW - w
        val a = ValueAnimator.ofInt(bubbleLp.x, target)
        a.duration = 180
        a.addUpdateListener {
            bubbleLp.x = it.animatedValue as Int
            try { wm.updateViewLayout(v, bubbleLp) } catch (_: Exception) {}
        }
        a.start()
    }

    private fun tick() {
        val pending = Repo.all(this).count { !it.isDone() }
        val b = badge
        if (b != null) {
            if (pending > 0) {
                b.text = if (pending > 99) "99+" else pending.toString()
                b.visibility = View.VISIBLE
            } else {
                b.visibility = View.GONE
            }
        }
        h.postDelayed({ tick() }, 4000)
    }

    // ------------------------------------------------------------ popup

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(16), dp(14), dp(16), dp(14))
        p.background = roundBg(Color.WHITE, dpf(22), 0xFFDADCE0.toInt(), 1)
        p.elevation = dpf(12)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val ttl = TextView(this)
        ttl.text = "My to-dos"
        ttl.textSize = 18f
        ttl.setTypeface(null, Typeface.BOLD)
        ttl.setTextColor(0xFF202124.toInt())
        head.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        val open = TextView(this)
        open.text = "Open app"
        open.textSize = 13f
        open.setTextColor(0xFF1A73E8.toInt())
        open.setPadding(dp(8), dp(8), dp(8), dp(8))
        open.setOnClickListener {
            hidePanel()
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        head.addView(open)
        val close = TextView(this)
        close.text = "✕"
        close.textSize = 18f
        close.setPadding(dp(10), dp(6), dp(6), dp(6))
        close.setOnClickListener { hidePanel() }
        head.addView(close)
        p.addView(head)

        val addRow = LinearLayout(this)
        addRow.orientation = LinearLayout.HORIZONTAL
        addRow.gravity = Gravity.CENTER_VERTICAL
        val et = EditText(this)
        et.hint = "Quick add a task"
        et.textSize = 15f
        et.setSingleLine(true)
        val add = Button(this)
        add.text = "Add"
        add.isAllCaps = false
        add.setOnClickListener {
            val text = et.text.toString().trim()
            if (text.isNotEmpty()) {
                Repo.put(this, Note(id = Repo.newId(), title = text))
                et.setText("")
                fillList()
                tick()
            }
        }
        addRow.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        addRow.addView(add)
        p.addView(addRow)

        val sv = ScrollView(this)
        val lb = LinearLayout(this)
        lb.orientation = LinearLayout.VERTICAL
        sv.addView(lb)
        p.addView(sv, LinearLayout.LayoutParams(-1, -2))
        listBox = lb

        // close when tapping outside the popup
        p.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                hidePanel()
                true
            } else {
                false
            }
        }

        val lp = WindowManager.LayoutParams(
            minOf(dp(320), resources.displayMetrics.widthPixels - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(90)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        try {
            wm.addView(p, lp)
            panel = p
            fillList()
        } catch (_: Exception) {}
    }

    private fun fillList() {
        val box = listBox ?: return
        box.removeAllViews()
        val tasks = Repo.all(this)
            .filter { !it.isDone() }
            .sortedWith(compareBy<Note>({ !it.pinned }, { if (it.alarmAt == 0L) Long.MAX_VALUE else it.alarmAt }))
            .take(8)
        if (tasks.isEmpty()) {
            val t = TextView(this)
            t.text = "All done 🎉"
            t.textSize = 15f
            t.setPadding(dp(4), dp(14), dp(4), dp(8))
            box.addView(t)
            return
        }
        for (n in tasks) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val t = TextView(this)
            t.text = n.displayTitle() + (if (n.alarmAt > 0) "\n⏰ " + fmtTime(n.alarmAt) else "")
            t.textSize = 15f
            t.setTextColor(0xFF202124.toInt())
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            val open = Button(this)
            open.text = "Open"
            open.isAllCaps = false
            open.setOnClickListener {
                hidePanel()
                try {
                    startActivity(
                        Intent(this, EditorActivity::class.java)
                            .putExtra("noteId", n.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }
            row.addView(open)
            box.addView(row)
        }
    }

    private fun hidePanel() {
        val p = panel ?: return
        try { wm.removeView(p) } catch (_: Exception) {}
        panel = null
        listBox = null
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        hidePanel()
        val b = bubble
        if (b != null) {
            try { wm.removeView(b) } catch (_: Exception) {}
        }
        bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
