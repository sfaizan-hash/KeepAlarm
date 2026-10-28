package com.keepalarm.todo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Google-Keep-style home screen: two-column cards, search, labels, pinning, colours. */
class MainActivity : Activity() {
    private lateinit var search: EditText
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var bubbleSwitch: Switch
    private lateinit var glowSwitch: Switch
    private var labelFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = Prefs.lockedId(this)
        if (locked != 0L) {
            FreezeActivity.launch(this, locked)
            finish()
            return
        }
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notif(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::search.isInitialized) return
        val locked = Prefs.lockedId(this)
        if (locked != 0L) {
            FreezeActivity.launch(this, locked)
            finish()
            return
        }
        bubbleSwitch.isChecked = Prefs.bubbleOn(this) && Perms.overlay(this)
        glowSwitch.isChecked = Prefs.glowAll(this) && Perms.listener(this) && Perms.overlay(this)
        if (bubbleSwitch.isChecked) startBubble()
        refreshSetup()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (::setupBox.isInitialized) refreshSetup()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFFF1F3F4.toInt())

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        search = EditText(this)
        search.hint = "Search your notes"
        search.setSingleLine(true)
        search.textSize = 16f
        search.setPadding(dp(20), dp(12), dp(20), dp(12))
        search.background = roundBg(Color.WHITE, dpf(28), 0xFFDADCE0.toInt(), 1)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        col.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(10), dp(12), dp(6))
        })

        bubbleSwitch = Switch(this)
        bubbleSwitch.setOnClickListener { onBubbleToggled() }
        col.addView(switchRow("Floating to-do bubble", bubbleSwitch))

        glowSwitch = Switch(this)
        glowSwitch.setOnClickListener { onGlowToggled() }
        col.addView(switchRow("Screen-edge glow on every notification", glowSwitch))

        val retryBtn = Button(this)
        retryBtn.isAllCaps = false
        retryBtn.textSize = 13f
        retryBtn.setOnClickListener { pickRetry(retryBtn) }
        col.addView(retryBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(2), dp(12), dp(2)) })
        updateRetryBtn(retryBtn)

        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        chipsRow.setPadding(dp(8), dp(4), dp(8), dp(4))
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipsRow)
        col.addView(chipScroll)

        setupBox = LinearLayout(this)
        setupBox.orientation = LinearLayout.VERTICAL
        col.addView(setupBox, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(4), dp(12), dp(4))
        })

        val scroll = ScrollView(this)
        val cols = LinearLayout(this)
        cols.orientation = LinearLayout.HORIZONTAL
        cols.setPadding(dp(6), dp(4), dp(6), dp(110))
        leftCol = LinearLayout(this)
        leftCol.orientation = LinearLayout.VERTICAL
        rightCol = LinearLayout(this)
        rightCol.orientation = LinearLayout.VERTICAL
        cols.addView(leftCol, LinearLayout.LayoutParams(0, -2, 1f))
        cols.addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f))
        scroll.addView(cols)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val fab = TextView(this)
        fab.text = "+"
        fab.textSize = 34f
        fab.gravity = Gravity.CENTER
        fab.setTextColor(0xFF202124.toInt())
        fab.background = ovalBg(0xFFFBBC04.toInt())
        fab.elevation = dpf(8)
        fab.setOnClickListener { startActivity(Intent(this, EditorActivity::class.java)) }
        root.addView(fab, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(22), dp(26))
        })

        setContentView(root)
    }

    private fun switchRow(label: String, sw: Switch): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(2), dp(20), dp(2))
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTextColor(0xFF3C4043.toInt())
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(sw)
        return row
    }

    // ---------------------------------------------------------------- Actions

    private fun updateRetryBtn(b: Button) {
        b.text = "🔔  Ignored alarm rings again every ${Prefs.retryMinutes(this)} min  (change)"
    }

    private fun pickRetry(b: Button) {
        val options = intArrayOf(1, 2, 3, 5, 10)
        AlertDialog.Builder(this)
            .setTitle("Ring again every…")
            .setItems(options.map { "$it min" }.toTypedArray()) { _, which ->
                Prefs.setRetryMinutes(this, options[which])
                updateRetryBtn(b)
            }
            .show()
    }

    private fun startBubble() {
        try { startForegroundService(Intent(this, BubbleService::class.java)) } catch (_: Exception) {}
    }

    private fun onBubbleToggled() {
        if (bubbleSwitch.isChecked) {
            if (!Perms.overlay(this)) {
                bubbleSwitch.isChecked = false
                toast(this, "Allow \"Display over other apps\" first")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            Prefs.setBubble(this, true)
            startBubble()
        } else {
            Prefs.setBubble(this, false)
            stopService(Intent(this, BubbleService::class.java))
        }
    }

    private fun onGlowToggled() {
        if (glowSwitch.isChecked) {
            if (!Perms.overlay(this)) {
                glowSwitch.isChecked = false
                toast(this, "Allow \"Display over other apps\" first")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            if (!Perms.listener(this)) {
                glowSwitch.isChecked = false
                toast(this, "Turn on notification access for Keep Alarm")
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
                return
            }
            Prefs.setGlowAll(this, true)
        } else {
            Prefs.setGlowAll(this, false)
        }
    }

    private fun refreshSetup() {
        setupBox.removeAllViews()
        fun add(text: String, act: () -> Unit) {
            val b = Button(this)
            b.text = "⚠  $text"
            b.isAllCaps = false
            b.textSize = 13f
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setTextColor(0xFF5D4037.toInt())
            b.background = roundBg(0xFFFFE0B2.toInt(), dpf(14))
            b.setOnClickListener { act() }
            setupBox.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val pkg = Uri.parse("package:$packageName")
        if (!Perms.notif(this) && Build.VERSION.SDK_INT >= 33) {
            add("Allow notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (!Perms.overlay(this)) {
            add("Allow \"Display over other apps\" (bubble, glow, lock)") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            }
        }
        if (!Perms.battery(this)) {
            add("Turn off battery optimization (keeps alarms alive)") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) } catch (_: Exception) {}
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !Perms.fullScreen(this)) {
            add("Allow full-screen alarms") {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) } catch (_: Exception) {}
            }
        }
        if (!Perms.pinningEnabled(this)) {
            add("Turn on \"App pinning\" in Security settings (needed for the lock)") {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
            }
        }
        setupBox.visibility = if (setupBox.childCount == 0) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- List

    private fun refresh() {
        if (!::leftCol.isInitialized) return
        val q = search.text.toString().trim().lowercase()
        val all = Repo.all(this)

        // label chips
        chipsRow.removeAllViews()
        val labels = all.map { it.label }.filter { it.isNotBlank() }.distinct().sorted()
        if (labelFilter.isNotEmpty() && labelFilter !in labels) labelFilter = ""
        if (labels.isNotEmpty()) {
            addChip("All", labelFilter.isEmpty()) { labelFilter = ""; refresh() }
            for (l in labels) addChip(l, labelFilter == l) { labelFilter = l; refresh() }
        }

        val shown = all.filter { n ->
            (labelFilter.isEmpty() || n.label == labelFilter) &&
                (q.isEmpty() ||
                    n.title.lowercase().contains(q) ||
                    n.body.lowercase().contains(q) ||
                    n.label.lowercase().contains(q) ||
                    n.items.any { it.text.lowercase().contains(q) })
        }.sortedWith(compareBy<Note>({ it.isDone() }, { !it.pinned }, { -it.updated }))

        leftCol.removeAllViews()
        rightCol.removeAllViews()
        shown.forEachIndexed { i, n ->
            val card = card(n)
            val lp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            if (i % 2 == 0) leftCol.addView(card, lp) else rightCol.addView(card, lp)
        }
        if (shown.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (all.isEmpty()) "No notes yet.\nTap + to add your first to-do." else "Nothing found."
            empty.textSize = 16f
            empty.setTextColor(0xFF80868B.toInt())
            empty.setPadding(dp(16), dp(40), dp(16), 0)
            leftCol.addView(empty)
        }
    }

    private fun addChip(text: String, selected: Boolean, onClick: () -> Unit) {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setPadding(dp(14), dp(7), dp(14), dp(7))
        t.setTextColor(if (selected) Color.WHITE else 0xFF3C4043.toInt())
        t.background = roundBg(if (selected) 0xFF1A73E8.toInt() else Color.WHITE, dpf(20), 0xFFDADCE0.toInt(), 1)
        t.setOnClickListener { onClick() }
        chipsRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
    }

    private fun card(n: Note): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = roundBg(NoteColors.get(n.color), dpf(14), 0xFFDADCE0.toInt(), 1)
        if (n.isDone()) c.alpha = 0.6f

        if (n.pinned) {
            val p = TextView(this)
            p.text = "📌"
            p.textSize = 12f
            c.addView(p)
        }
        if (n.title.isNotBlank()) {
            val t = TextView(this)
            t.text = n.title
            t.textSize = 16f
            t.setTypeface(null, Typeface.BOLD)
            t.setTextColor(0xFF202124.toInt())
            if (n.isDone()) t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            c.addView(t)
        }
        if (n.isChecklist) {
            val shownItems = n.items.take(6)
            for (it in shownItems) {
                val r = TextView(this)
                r.text = (if (it.done) "☑  " else "☐  ") + it.text
                r.textSize = 14f
                r.setTextColor(if (it.done) 0xFF80868B.toInt() else 0xFF202124.toInt())
                if (it.done) r.paintFlags = r.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                r.setPadding(0, dp(3), 0, 0)
                c.addView(r)
            }
            if (n.items.size > 6) {
                val more = TextView(this)
                more.text = "+ ${n.items.size - 6} more"
                more.textSize = 12f
                more.setTextColor(0xFF5F6368.toInt())
                c.addView(more)
            }
        } else if (n.body.isNotBlank()) {
            val b = TextView(this)
            b.text = n.body
            b.textSize = 14f
            b.maxLines = 8
            b.ellipsize = TextUtils.TruncateAt.END
            b.setTextColor(0xFF202124.toInt())
            b.setPadding(0, dp(4), 0, 0)
            c.addView(b)
        }
        if (n.label.isNotBlank()) {
            val l = TextView(this)
            l.text = "  ${n.label}  "
            l.textSize = 11f
            l.setTextColor(0xFF3C4043.toInt())
            l.background = roundBg(0x33000000, dpf(10))
            c.addView(l, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        if (n.alarmAt > 0 && !n.isDone()) {
            val a = TextView(this)
            a.text = "⏰ " + fmtTime(n.alarmAt) + (if (n.repeat != 0) "  🔁" else "")
            a.textSize = 12f
            a.setTextColor(0xFF3C4043.toInt())
            a.setPadding(0, dp(8), 0, 0)
            c.addView(a)
        }

        c.setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java).putExtra("noteId", n.id))
        }
        c.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(n.displayTitle())
                .setItems(arrayOf(if (n.pinned) "Unpin" else "Pin", "Delete")) { _, which ->
                    if (which == 0) {
                        n.pinned = !n.pinned
                        Repo.put(this, n)
                    } else {
                        AlarmScheduler.cancel(this, n.id)
                        Repo.delete(this, n.id)
                    }
                    refresh()
                }
                .show()
            true
        }
        return c
    }
}
