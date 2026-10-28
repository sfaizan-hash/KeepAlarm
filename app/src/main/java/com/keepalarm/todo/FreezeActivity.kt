package com.keepalarm.todo

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The "frozen" screen. After the 3rd snooze the phone is pinned to this to-do list.
 * It only closes when the task is completed.
 * Layers: Android screen pinning (startLockTask) + a soft lock that re-opens this screen
 * if it is left, + a saved flag so it comes back after a restart.
 */
class FreezeActivity : Activity() {
    companion object {
        fun launch(c: Context, id: Long) {
            val i = Intent(c, FreezeActivity::class.java)
                .putExtra("noteId", id)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            try { c.startActivity(i) } catch (_: Exception) {}
        }
    }

    private var noteId = 0L
    private var done = false
    private val h = Handler(Looper.getMainLooper())
    private lateinit var listBox: LinearLayout
    private lateinit var progress: TextView
    private lateinit var completeBtn: Button
    private lateinit var pinHint: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        noteId = intent.getLongExtra("noteId", Prefs.lockedId(this))
        val note = Repo.get(this, noteId)
        if (note == null) {
            // The task no longer exists, so there is nothing to finish: unlock.
            Prefs.setLocked(this, 0)
            finish()
            return
        }
        Prefs.setLocked(this, noteId)
        build(note)
    }

    private fun build(note: Note) {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(NoteColors.get(note.color).let { if (it == Color.WHITE) 0xFFFFF8E1.toInt() else it })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(48), dp(22), dp(30))

        val banner = TextView(this)
        banner.text = "🔒  Your screen is locked. Finish this to-do to unlock."
        banner.setTextColor(0xFFB3261E.toInt())
        banner.setTypeface(null, Typeface.BOLD)
        banner.textSize = 15f
        col.addView(banner)

        val title = TextView(this)
        title.text = note.displayTitle()
        title.setTextColor(0xFF202124.toInt())
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.setPadding(0, dp(14), 0, dp(6))
        col.addView(title)

        progress = TextView(this)
        progress.setTextColor(0xFF5F6368.toInt())
        progress.textSize = 15f
        progress.setPadding(0, 0, 0, dp(10))
        col.addView(progress)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox)

        completeBtn = Button(this)
        completeBtn.isAllCaps = false
        completeBtn.textSize = 18f
        completeBtn.setTextColor(Color.WHITE)
        completeBtn.setOnClickListener {
            startActivity(Intent(this, VerifyActivity::class.java).putExtra("noteId", noteId))
        }
        col.addView(completeBtn, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(24) })

        pinHint = LinearLayout(this)
        pinHint.orientation = LinearLayout.VERTICAL
        pinHint.setPadding(dp(14), dp(12), dp(14), dp(12))
        pinHint.background = roundBg(0xFFFFE0B2.toInt(), dpf(14))
        val hint = TextView(this)
        hint.text = "Screen pinning is not active, so Android may let you leave this screen. " +
            "Turn on \"App pinning\" in Settings → Security, then tap Retry."
        hint.setTextColor(0xFF4E342E.toInt())
        hint.textSize = 13f
        pinHint.addView(hint)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val open = Button(this)
        open.text = "Open settings"
        open.isAllCaps = false
        open.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
        }
        val retry = Button(this)
        retry.text = "Retry"
        retry.isAllCaps = false
        retry.setOnClickListener { tryPin(); refreshHint() }
        row.addView(open)
        row.addView(retry)
        pinHint.addView(row)
        col.addView(pinHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        scroll.addView(col)
        setContentView(scroll)
        render()
    }

    private fun render() {
        val note = Repo.get(this, noteId) ?: return
        listBox.removeAllViews()
        if (note.isChecklist && note.items.isNotEmpty()) {
            for (it in note.items) {
                val cb = CheckBox(this)
                cb.text = it.text
                cb.textSize = 19f
                cb.isChecked = it.done
                cb.setTextColor(0xFF202124.toInt())
                cb.setPadding(dp(8), dp(10), dp(8), dp(10))
                cb.setOnCheckedChangeListener { _, checked ->
                    it.done = checked
                    Repo.put(this, note)
                    refreshProgress()
                }
                listBox.addView(cb)
            }
        } else if (!note.isChecklist) {
            val body = TextView(this)
            body.text = note.body
            body.textSize = 18f
            body.setTextColor(0xFF202124.toInt())
            listBox.addView(body)
        }
        refreshProgress()
    }

    private fun refreshProgress() {
        val note = Repo.get(this, noteId) ?: return
        val total = note.items.size
        val ready: Boolean
        if (note.isChecklist && total > 0) {
            val d = note.items.count { it.done }
            progress.text = "$d of $total done"
            ready = d == total
        } else {
            progress.text = "Complete the task, then tap the button"
            ready = true
        }
        completeBtn.isEnabled = true
        completeBtn.text = "📸  Verify with screenshot to unlock"
        completeBtn.background = roundBg(0xFF1E8E3E.toInt(), dpf(31))
    }

    private fun finishTask() {
        val note = Repo.get(this, noteId)
        if (note != null) {
            if (note.repeat != 0) {
                note.items.forEach { it.done = false }
                note.completed = false
            } else {
                note.markDone()
                AlarmScheduler.cancel(this, note.id)
            }
            Repo.put(this, note)
        }
        done = true
        Prefs.setLocked(this, 0)
        try { stopLockTask() } catch (_: Exception) {}
        finish()
    }

    private fun tryPin() {
        if (done) return
        try { startLockTask() } catch (_: Exception) {}
    }

    private fun pinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun refreshHint() {
        pinHint.visibility = if (pinned()) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.lockedId(this) == 0L) {
            // Task was verified: release the lock.
            done = true
            try { stopLockTask() } catch (_: Exception) {}
            finish()
            return
        }
        if (::pinHint.isInitialized) {
            tryPin()
            h.postDelayed({ refreshHint() }, 800)
        }
    }

    /** Soft lock: if the user leaves this screen (Home, recents, another app), bring it back. */
    private fun bringBack() {
        if (!done && !isFinishing && !VerifyActivity.active && Prefs.lockedId(this) != 0L) launch(this, noteId)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!done) h.postDelayed({ bringBack() }, 250)
    }

    override fun onStop() {
        super.onStop()
        if (!done && !isFinishing) h.postDelayed({ bringBack() }, 400)
    }

    @Deprecated("Back is blocked while the screen is locked")
    override fun onBackPressed() {
        // blocked on purpose
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
