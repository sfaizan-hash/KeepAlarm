package com.keepalarm.todo

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** Note editor: checklist or plain text, colour, label, pin, reminder with repeat. Autosaves. */
class EditorActivity : Activity() {
    private lateinit var note: Note
    private var isNew = false
    private var deleted = false

    private lateinit var scroll: ScrollView
    private lateinit var titleEt: EditText
    private lateinit var contentBox: LinearLayout
    private var bodyEt: EditText? = null
    private var addBtn: TextView? = null
    private lateinit var labelEt: EditText
    private lateinit var alarmBtn: Button
    private lateinit var repeatBtn: Button
    private lateinit var pinBtn: TextView
    private lateinit var typeBtn: TextView
    private lateinit var colorRow: LinearLayout
    private val rows = mutableListOf<Pair<CheckBox, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("noteId", 0L)
        val existing = if (id != 0L) Repo.get(this, id) else null
        if (existing != null) {
            note = existing
        } else {
            note = Note(id = Repo.newId())
            isNew = true
        }
        buildUi()
    }

    private fun buildUi() {
        scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(10), dp(16), dp(40))
        scroll.addView(root)

        // top bar
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        fun barBtn(text: String, onClick: () -> Unit): TextView {
            val t = TextView(this)
            t.text = text
            t.textSize = 22f
            t.gravity = Gravity.CENTER
            t.setOnClickListener { onClick() }
            bar.addView(t, LinearLayout.LayoutParams(dp(48), dp(48)))
            return t
        }
        barBtn("←") { finish() }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pinBtn = barBtn("📌") {
            note.pinned = !note.pinned
            updatePin()
        }
        typeBtn = barBtn("☑") { toggleType() }
        barBtn("🗑") { confirmDelete() }
        root.addView(bar)

        titleEt = EditText(this)
        titleEt.hint = "Title"
        titleEt.textSize = 24f
        titleEt.setTypeface(null, Typeface.BOLD)
        titleEt.background = null
        titleEt.setText(note.title)
        titleEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        root.addView(titleEt)

        contentBox = LinearLayout(this)
        contentBox.orientation = LinearLayout.VERTICAL
        root.addView(contentBox)

        labelEt = EditText(this)
        labelEt.hint = "Label (optional)"
        labelEt.textSize = 15f
        labelEt.setSingleLine(true)
        labelEt.setText(note.label)
        labelEt.setPadding(dp(14), dp(10), dp(14), dp(10))
        labelEt.background = roundBg(0x22000000, dpf(12))
        root.addView(labelEt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // reminder
        alarmBtn = Button(this)
        alarmBtn.isAllCaps = false
        alarmBtn.textSize = 15f
        alarmBtn.setOnClickListener { pickAlarm() }
        root.addView(alarmBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val alarmRow = LinearLayout(this)
        alarmRow.orientation = LinearLayout.HORIZONTAL
        repeatBtn = Button(this)
        repeatBtn.isAllCaps = false
        repeatBtn.textSize = 14f
        repeatBtn.setOnClickListener {
            note.repeat = (note.repeat + 1) % 3
            updateAlarmUi()
        }
        val clearBtn = Button(this)
        clearBtn.text = "Clear reminder"
        clearBtn.isAllCaps = false
        clearBtn.textSize = 14f
        clearBtn.setOnClickListener {
            note.alarmAt = 0
            note.baseAlarm = 0
            note.snoozeCount = 0
            AlarmScheduler.cancel(this, note.id)
            updateAlarmUi()
        }
        alarmRow.addView(repeatBtn, LinearLayout.LayoutParams(0, -2, 1f))
        alarmRow.addView(clearBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(alarmRow)

        val verifyBtn = Button(this)
        verifyBtn.text = "✅  Verify task with screenshot"
        verifyBtn.isAllCaps = false
        verifyBtn.textSize = 15f
        verifyBtn.setOnClickListener {
            save()
            if (note.isEmpty()) {
                toast(this, "Write the task first")
            } else {
                startActivity(android.content.Intent(this, VerifyActivity::class.java).putExtra("noteId", note.id))
            }
        }
        root.addView(verifyBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // colours
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.setPadding(0, dp(8), 0, dp(8))
        hs.addView(colorRow)
        root.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        setContentView(scroll)
        renderContent()
        buildColors()
        applyColor()
        updatePin()
        updateAlarmUi()
    }

    // ------------------------------------------------------------ content

    private fun renderContent() {
        contentBox.removeAllViews()
        rows.clear()
        bodyEt = null
        addBtn = null
        if (note.isChecklist) {
            typeBtn.text = "📝"
            for (it in note.items) addRow(it, false)
            if (note.items.isEmpty()) addRow(Item(""), isNew)
            val add = TextView(this)
            add.text = "+  Add item"
            add.textSize = 16f
            add.setTextColor(0xFF5F6368.toInt())
            add.setPadding(dp(8), dp(14), dp(8), dp(14))
            add.setOnClickListener { addRow(Item(""), true) }
            contentBox.addView(add)
            addBtn = add
        } else {
            typeBtn.text = "☑"
            val b = EditText(this)
            b.hint = "Note"
            b.textSize = 17f
            b.background = null
            b.minLines = 6
            b.gravity = Gravity.TOP
            b.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            b.setText(note.body)
            contentBox.addView(b)
            bodyEt = b
        }
    }

    private fun addRow(item: Item, focus: Boolean) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val cb = CheckBox(this)
        cb.isChecked = item.done
        val et = EditText(this)
        et.hint = "List item"
        et.textSize = 17f
        et.background = null
        et.setText(item.text)
        et.setSingleLine(true)
        et.imeOptions = EditorInfo.IME_ACTION_NEXT
        et.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) {
                addRow(Item(""), true)
                true
            } else {
                false
            }
        }
        val x = TextView(this)
        x.text = "✕"
        x.textSize = 16f
        x.gravity = Gravity.CENTER
        x.setTextColor(0xFF5F6368.toInt())

        val pair = Pair(cb, et)
        x.setOnClickListener {
            rows.remove(pair)
            contentBox.removeView(row)
        }
        row.addView(cb)
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(x, LinearLayout.LayoutParams(dp(40), dp(40)))

        // keep the "+ Add item" button last
        val btn = addBtn
        val insertAt = if (btn != null) contentBox.indexOfChild(btn) else contentBox.childCount
        contentBox.addView(row, if (insertAt < 0) contentBox.childCount else insertAt)
        rows.add(pair)
        if (focus) et.requestFocus()
    }

    private fun collect() {
        note.title = titleEt.text.toString().trim()
        note.label = labelEt.text.toString().trim()
        if (note.isChecklist) {
            note.items = rows
                .map { Item(it.second.text.toString().trim(), it.first.isChecked) }
                .filter { it.text.isNotEmpty() }
                .toMutableList()
        } else {
            val b = bodyEt
            if (b != null) note.body = b.text.toString()
        }
    }

    private fun toggleType() {
        collect()
        if (note.isChecklist) {
            note.body = note.items.joinToString("\n") { it.text }
            note.items = mutableListOf()
            note.isChecklist = false
        } else {
            note.items = note.body.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Item(it) }
                .toMutableList()
            note.body = ""
            note.isChecklist = true
        }
        renderContent()
    }

    // ------------------------------------------------------------ look

    private fun buildColors() {
        colorRow.removeAllViews()
        for (i in NoteColors.list.indices) {
            val v = View(this)
            val selected = i == note.color
            v.background = ovalBg(
                NoteColors.list[i],
                if (selected) 0xFF1A73E8.toInt() else 0xFF9AA0A6.toInt(),
                if (selected) dp(3) else dp(1)
            )
            v.setOnClickListener {
                note.color = i
                buildColors()
                applyColor()
            }
            colorRow.addView(v, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun applyColor() {
        scroll.setBackgroundColor(NoteColors.get(note.color))
    }

    private fun updatePin() {
        pinBtn.alpha = if (note.pinned) 1f else 0.35f
    }

    private fun updateAlarmUi() {
        alarmBtn.text = if (note.alarmAt > 0) "⏰  ${fmtTime(note.alarmAt)}" else "⏰  Add reminder / alarm"
        repeatBtn.text = "🔁  " + listOf("Does not repeat", "Repeats daily", "Repeats weekly")[note.repeat]
    }

    private fun pickAlarm() {
        val cal = Calendar.getInstance()
        if (note.alarmAt > System.currentTimeMillis()) cal.timeInMillis = note.alarmAt
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val c2 = Calendar.getInstance()
                c2.set(y, m, d, hh, mm, 0)
                c2.set(Calendar.MILLISECOND, 0)
                if (c2.timeInMillis <= System.currentTimeMillis()) {
                    toast(this, "Pick a time in the future")
                } else {
                    note.alarmAt = c2.timeInMillis
                    note.baseAlarm = c2.timeInMillis
                    note.snoozeCount = 0
                    note.completed = false
                    updateAlarmUi()
                }
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ------------------------------------------------------------ save / delete

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this note?")
            .setPositiveButton("Delete") { _, _ ->
                deleted = true
                AlarmScheduler.cancel(this, note.id)
                Repo.delete(this, note.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        if (deleted) return
        collect()
        if (note.isEmpty()) {
            if (!isNew) {
                AlarmScheduler.cancel(this, note.id)
                Repo.delete(this, note.id)
            }
            return
        }
        Repo.put(this, note)
        isNew = false
        AlarmScheduler.schedule(this, note)
    }

    override fun onPause() {
        save()
        super.onPause()
    }
}
