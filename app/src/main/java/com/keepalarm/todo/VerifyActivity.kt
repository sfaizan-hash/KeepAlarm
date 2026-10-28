package com.keepalarm.todo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Keyword check: the screenshot's text (read on the phone by ML Kit OCR) must contain the task's keywords. */
object Verifier {
    private val stop = setOf(
        "with", "that", "this", "from", "have", "will", "your", "full", "also", "into", "then",
        "than", "them", "they", "what", "when", "just", "need", "make", "done", "task", "todo", "list"
    )
    private val appMarkers = listOf(
        "my to-dos", "quick add a task", "keep alarm", "search your notes", "verify with screenshot"
    )

    class Result(val ok: Boolean, val found: List<String>, val needed: Int, val message: String)

    fun keywords(n: Note): List<String> {
        val src = n.title + " " + n.items.joinToString(" ") { it.text } + " " + n.body
        return src.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 4 && it !in stop }
            .distinct().sortedByDescending { it.length }.take(8)
    }

    fun check(ocr: String, kws: List<String>): Result {
        val text = ocr.lowercase().replace(Regex("\\s+"), " ")
        if (appMarkers.any { text.contains(it) }) {
            return Result(false, emptyList(), 0, "That is a screenshot of this app. Show the finished work itself.")
        }
        if (kws.isEmpty()) return Result(true, emptyList(), 0, "No keywords in this task, accepted.")
        val found = kws.filter { text.contains(it) }
        val need = minOf(2, kws.size)
        return Result(found.size >= need, found, need, "")
    }
}

class VerifyActivity : Activity() {
    companion object {
        @Volatile var active = false
        private const val PICK = 77
    }

    private var noteId = 0L
    private lateinit var status: TextView
    private lateinit var pick: Button
    private var kws: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        noteId = intent.getLongExtra("noteId", 0L)
        val note = Repo.get(this, noteId)
        if (note == null) { finish(); return }
        kws = Verifier.keywords(note)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(56), dp(24), dp(30))

        fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
            val t = TextView(this)
            t.text = s; t.textSize = size; t.setTextColor(color)
            if (bold) t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(6), 0, dp(6))
            return t
        }
        col.addView(tv("📸  Prove it is done", 24f, 0xFF202124.toInt(), true))
        col.addView(tv(note.displayTitle(), 18f, 0xFF3C4043.toInt()))
        col.addView(tv(
            "Pick a screenshot that shows the finished work. It must contain at least " +
                "${minOf(2, kws.size)} of these words:\n" + kws.joinToString("  •  "),
            14f, 0xFF5F6368.toInt()
        ))
        pick = Button(this)
        pick.text = "Choose screenshot"
        pick.isAllCaps = false
        pick.textSize = 18f
        pick.setTextColor(Color.WHITE)
        pick.background = roundBg(0xFF1A73E8.toInt(), dpf(30))
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { startActivityForResult(i, PICK) } catch (_: Exception) { toast(this, "No image picker found") }
        }
        col.addView(pick, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(16) })
        status = tv("", 16f, 0xFF202124.toInt())
        col.addView(status)

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFFF8F9FA.toInt())
        sv.addView(col)
        setContentView(sv)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != PICK || res != RESULT_OK || uri == null) return
        status.setTextColor(0xFF5F6368.toInt())
        status.text = "Reading screenshot…"
        pick.isEnabled = false
        val img = try { InputImage.fromFilePath(this, uri) } catch (e: Exception) {
            status.text = "Could not open that image. Try another."
            pick.isEnabled = true
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
            .addOnSuccessListener { handle(it.text) }
            .addOnFailureListener {
                status.text = "Could not read the screenshot. Try another."
                pick.isEnabled = true
            }
    }

    private fun handle(ocr: String) {
        val r = Verifier.check(ocr, kws)
        if (r.ok) {
            status.setTextColor(0xFF1E8E3E.toInt())
            status.text = "✅ Verified (${r.found.joinToString(", ")}). Task complete!"
            AlarmLogic.complete(this, noteId)
            try { stopLockTask() } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1300)
        } else {
            status.setTextColor(0xFFB3261E.toInt())
            status.text = if (r.message.isNotEmpty()) "❌ ${r.message}"
            else "❌ Not verified: found ${r.found.size} of the needed ${r.needed} keywords" +
                (if (r.found.isEmpty()) "." else " (${r.found.joinToString(", ")}).") + " The alarm will ring again."
            pick.isEnabled = true
            AlarmLogic.ringAgain(this, noteId)
        }
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }
}
