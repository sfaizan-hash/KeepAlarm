package com.keepalarm.todo

import android.animation.ValueAnimator
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/** Pixel-style "edge lighting": a soft, colourful, pulsing glow around the whole screen. */
class EdgeGlowView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: SweepGradient? = null
    private val m = Matrix()
    private var angle = 0f
    private val rect = RectF()
    private val radius = c.dpf(36)
    private val anim = ValueAnimator.ofFloat(0f, 360f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c.dpf(14)
        paint.maskFilter = BlurMaskFilter(c.dpf(18), BlurMaskFilter.Blur.NORMAL)
        anim.duration = 3000
        anim.repeatCount = ValueAnimator.INFINITE
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(
                0xFF4285F4.toInt(), 0xFF34A853.toInt(), 0xFFFBBC05.toInt(),
                0xFFEA4335.toInt(), 0xFFA142F4.toInt(), 0xFF4285F4.toInt()
            ),
            null
        )
        val inset = paint.strokeWidth / 2f
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        val s = shader ?: return
        m.setRotate(angle, width / 2f, height / 2f)
        s.setLocalMatrix(m)
        paint.shader = s
        paint.alpha = (175 + 80 * sin(angle * PI / 90.0)).toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}

/** Shows the glow on top of every app. durationMs = 0 means "until hide() is called". */
object GlowOverlay {
    private var view: View? = null
    private var wm: WindowManager? = null
    private val h = Handler(Looper.getMainLooper())
    private val hideRun = Runnable { hide() }

    fun show(ctx: Context, durationMs: Long) {
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        hide()
        val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = EdgeGlowView(app)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            w.addView(v, lp)
            view = v
            wm = w
        } catch (_: Exception) {
            return
        }
        if (durationMs > 0) h.postDelayed(hideRun, durationMs)
    }

    fun hide() {
        h.removeCallbacks(hideRun)
        val v = view
        if (v != null) {
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
        view = null
    }
}

/** Optional: lights the screen edge for every notification on the phone (needs Notification access). */
class GlowNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.glowAll(this)) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        GlowOverlay.show(applicationContext, 2800)
    }
}

/** Restores everything after a reboot, app update or clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AlarmScheduler.rescheduleAll(c)
        if (Prefs.bubbleOn(c) && Perms.overlay(c)) {
            try { c.startForegroundService(Intent(c, BubbleService::class.java)) } catch (_: Exception) {}
        }
        val locked = Prefs.lockedId(c)
        if (locked != 0L && Perms.overlay(c)) FreezeActivity.launch(c, locked)
    }
}
