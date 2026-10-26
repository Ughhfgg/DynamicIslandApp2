package com.oussama.island

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/* ===== Shared helpers (developer: oussama) ===== */

val ACCENTS = intArrayOf(
    0xFF7C4DFF.toInt(), 0xFF2979FF.toInt(), 0xFF00B8D4.toInt(), 0xFF00C853.toInt(),
    0xFFFF9100.toInt(), 0xFFFF4081.toInt(), 0xFFFF1744.toInt()
)
val BG_PRESETS = intArrayOf(
    0xFF000000.toInt(), 0xFF0D1B4C.toInt(), 0xFF3B1470.toInt(),
    0xFF6B0F1A.toInt(), 0xFF0B4F2E.toInt(), 0xFF2B2B2B.toInt()
)

object Lang {
    fun isAr(c: Context): Boolean {
        val p = c.getSharedPreferences("island", Context.MODE_PRIVATE).getString("lang", "auto")
        return if (p == "auto" || p == null) java.util.Locale.getDefault().language == "ar" else p == "ar"
    }
    fun t(c: Context, en: String, ar: String) = if (isAr(c)) ar else en
}

class Cfg(c: Context) {
    val p: SharedPreferences = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun i(k: String, d: Int) = p.getInt(k, d)
    fun b(k: String, d: Boolean = true) = p.getBoolean(k, d)
}

class Notif(
    val key: String, val app: String, val title: String, val text: String, val big: String,
    val icon: Drawable?, val large: Drawable?, val color: Int, val tap: PendingIntent?,
    val actions: List<Notification.Action>
)

class WaveView(c: Context) : View(c) {
    var color = Color.WHITE
    var active = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t = it.animatedFraction; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        paint.color = color
        val n = 5
        val w = width.toFloat()
        val hh = height.toFloat()
        val gap = w / n
        paint.strokeWidth = gap * 0.45f
        for (i in 0 until n) {
            val f = if (active) 0.3f + 0.7f * abs(sin(t * 6.2832f + i * 1.3f)) else 0.25f
            val bh = hh * f
            val x = gap * i + gap / 2
            cv.drawLine(x, (hh - bh) / 2, x, (hh + bh) / 2, paint)
        }
    }
}

/* ===== The island ===== */

class IslandService : Service() {

    companion object {
        @Volatile var instance: IslandService? = null
        const val ACTION_TIMER = "com.oussama.island.TIMER"
        const val EXTRA_SECONDS = "seconds"
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var box: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var cfg: Cfg
    private val bg = GradientDrawable()
    private val h = Handler(Looper.getMainLooper())

    // state
    private var callView: View? = null
    private var callKey: String? = null
    private var temp: View? = null
    private var tempId = 0
    private var tempColor = Color.BLACK
    private var notif: Notif? = null
    private var notifExpanded = false
    private val queue = ArrayDeque<Notif>()
    private var timerEnd = 0L
    private var timerTv: TextView? = null
    private var controller: MediaController? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var mediaExpanded = false
    private var pausedAt = 0L
    private var artBmp: Bitmap? = null
    private var trackKey = ""
    private var accent = ACCENTS[0]
    private var mediaBg = Color.BLACK
    private var repeatOn = false
    private var seekBar: SeekBar? = null
    private var posTv: TextView? = null
    private var seeking = false
    private var shownSig = ""
    private var curColor = Color.BLACK
    private var curRad = 0f
    private var colorAnim: ValueAnimator? = null
    private var radAnim: ValueAnimator? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = Cfg(this)
        accent = ACCENTS[cfg.i("accent", 0)]
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
        }
        root = FrameLayout(this).apply {
            addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            visibility = View.GONE
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        wm.addView(root, lp)
        styleNow()
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        })
        setupMedia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TIMER) {
            val s = intent.getIntExtra(EXTRA_SECONDS, 0)
            if (s > 0) {
                timerEnd = System.currentTimeMillis() + s * 1000L
                h.removeCallbacks(tick)
                shownSig = ""
                doRefresh()
                h.post(tick)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        h.removeCallbacksAndMessages(null)
        try { unregisterReceiver(power) } catch (_: Exception) {}
        controller?.unregisterCallback(mcb)
        try {
            sessionListener?.let { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(it) }
        } catch (_: Exception) {}
        try { wm.removeView(root) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Dynamic Island", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "island")
            .setContentTitle("Dynamic Island")
            .setContentText("by oussama")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }
