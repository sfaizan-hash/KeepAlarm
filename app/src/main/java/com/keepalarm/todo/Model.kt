package com.keepalarm.todo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(var text: String, var done: Boolean = false)

data class Note(
    val id: Long,
    var title: String = "",
    var body: String = "",
    var isChecklist: Boolean = true,
    var items: MutableList<Item> = mutableListOf(),
    var color: Int = 0,
    var pinned: Boolean = false,
    var label: String = "",
    var alarmAt: Long = 0L,
    var baseAlarm: Long = 0L,
    var repeat: Int = 0, // 0 none, 1 daily, 2 weekly
    var snoozeCount: Int = 0,
    var completed: Boolean = false,
    var updated: Long = System.currentTimeMillis()
) {
    fun isDone(): Boolean = completed || (isChecklist && items.isNotEmpty() && items.all { it.done })

    fun isEmpty(): Boolean = title.isBlank() && body.isBlank() && items.none { it.text.isNotBlank() }

    fun displayTitle(): String {
        if (title.isNotBlank()) return title
        val first = items.firstOrNull { it.text.isNotBlank() }?.text
        if (first != null) return first
        return body.trim().lines().firstOrNull().orEmpty().ifBlank { "Untitled" }
    }

    fun markDone() {
        completed = true
        items.forEach { it.done = true }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("body", body)
        o.put("isChecklist", isChecklist)
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().put("t", it.text).put("d", it.done))
        }
        o.put("items", arr)
        o.put("color", color)
        o.put("pinned", pinned)
        o.put("label", label)
        o.put("alarmAt", alarmAt)
        o.put("baseAlarm", baseAlarm)
        o.put("repeat", repeat)
        o.put("snoozeCount", snoozeCount)
        o.put("completed", completed)
        o.put("updated", updated)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Note {
            val items = mutableListOf<Item>()
            val arr = o.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    items.add(Item(x.optString("t"), x.optBoolean("d")))
                }
            }
            return Note(
                id = o.getLong("id"),
                title = o.optString("title"),
                body = o.optString("body"),
                isChecklist = o.optBoolean("isChecklist", true),
                items = items,
                color = o.optInt("color"),
                pinned = o.optBoolean("pinned"),
                label = o.optString("label"),
                alarmAt = o.optLong("alarmAt"),
                baseAlarm = o.optLong("baseAlarm"),
                repeat = o.optInt("repeat"),
                snoozeCount = o.optInt("snoozeCount"),
                completed = o.optBoolean("completed"),
                updated = o.optLong("updated", System.currentTimeMillis())
            )
        }
    }
}

object NoteColors {
    val list = intArrayOf(
        0xFFFFFFFF.toInt(), // white
        0xFFF28B82.toInt(), // red
        0xFFFBBC04.toInt(), // orange
        0xFFFFF475.toInt(), // yellow
        0xFFCCFF90.toInt(), // green
        0xFFA7FFEB.toInt(), // teal
        0xFFCBF0F8.toInt(), // blue
        0xFFD7AEFB.toInt(), // purple
        0xFFFDCFE8.toInt(), // pink
        0xFFE6C9A8.toInt(), // brown
        0xFFE8EAED.toInt()  // gray
    )

    fun get(i: Int): Int = list[if (i in list.indices) i else 0]
}

/** Notes are kept in one JSON file. Everything runs in a single process, so a cache is safe. */
object Repo {
    private var notes: MutableList<Note>? = null

    private fun file(c: Context) = File(c.applicationContext.filesDir, "notes.json")

    @Synchronized
    private fun ensure(c: Context): MutableList<Note> {
        val existing = notes
        if (existing != null) return existing
        val l = mutableListOf<Note>()
        val f = file(c)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) l.add(Note.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // corrupt file: start fresh but keep a backup
                try { f.copyTo(File(f.parentFile, "notes.bak"), overwrite = true) } catch (_: Exception) {}
            }
        }
        notes = l
        return l
    }

    @Synchronized
    private fun persist(c: Context) {
        val arr = JSONArray()
        notes?.forEach { arr.put(it.toJson()) }
        try {
            val f = file(c)
            val tmp = File(f.parentFile, "notes.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun all(c: Context): List<Note> = ensure(c).toList()

    @Synchronized
    fun get(c: Context, id: Long): Note? = ensure(c).firstOrNull { it.id == id }

    @Synchronized
    fun put(c: Context, n: Note) {
        val l = ensure(c)
        n.updated = System.currentTimeMillis()
        val i = l.indexOfFirst { it.id == n.id }
        if (i >= 0) l[i] = n else l.add(n)
        persist(c)
    }

    @Synchronized
    fun delete(c: Context, id: Long) {
        ensure(c).removeAll { it.id == id }
        persist(c)
    }

    fun newId(): Long = System.currentTimeMillis()
}

object Prefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("kp", Context.MODE_PRIVATE)

    fun lockedId(c: Context): Long = p(c).getLong("locked", 0L)
    fun setLocked(c: Context, id: Long) = p(c).edit().putLong("locked", id).apply()

    fun bubbleOn(c: Context): Boolean = p(c).getBoolean("bubble", false)
    fun setBubble(c: Context, on: Boolean) = p(c).edit().putBoolean("bubble", on).apply()

    fun glowAll(c: Context): Boolean = p(c).getBoolean("glowAll", true)
    fun setGlowAll(c: Context, on: Boolean) = p(c).edit().putBoolean("glowAll", on).apply()

    /** Minutes between re-rings while an alarm is ignored. Default 2. */
    fun retryMinutes(c: Context): Int = p(c).getInt("retryMin", 2)
    fun setRetryMinutes(c: Context, m: Int) = p(c).edit().putInt("retryMin", m.coerceIn(1, 60)).apply()
}
