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

class Cfg(private val ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun i(k: String, d: Int) = p.getInt(k, d)
    fun b(k: String, d: Boolean = true) = p.getBoolean(k, d)
    private fun swDp() = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density
    /** Default size = same proportions as the phone's native island */
    fun def(k: String): Int = when (k) {
        "width" -> (swDp() * 0.345f).toInt()
        "height" -> (swDp() * 0.082f).toInt()
        "top" -> (swDp() * 0.019f).toInt()
        else -> 0
    }
    fun d(k: String) = p.getInt(k, def(k))
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
    private var tempW = 0
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        styleNow()
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
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

    /* ---------- helpers ---------- */
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ll(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)
    private fun base() = BG_PRESETS[cfg.i("bgIdx", 0).coerceIn(0, BG_PRESETS.size - 1)]
    private fun t(en: String, ar: String) = Lang.t(this, en, ar)
    private fun mmss(ms: Long): String { val s = max(0L, ms) / 1000; return "%d:%02d".format(s / 60, s % 60) }
    private fun haptic() { if (cfg.b("haptic")) box.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun tv(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        }

    private fun circleClip(v: View) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setOval(0, 0, view.width, view.height) }
        }
    }

    private fun roundClip(v: View, radiusDp: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) {
                o.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
    }

    private fun artView(cornerDp: Int): View {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val b = artBmp
            if (b != null) setImageBitmap(b) else setBackgroundColor(accent)
        }
        if (cornerDp < 0) circleClip(iv) else roundClip(iv, cornerDp)
        return iv
    }

    private fun avatar(large: Drawable?, small: Drawable?, color: Int, size: Int): View {
        val f = FrameLayout(this)
        if (large != null) {
            val iv = ImageView(this).apply { setImageDrawable(large); scaleType = ImageView.ScaleType.CENTER_CROP }
            circleClip(iv)
            f.addView(iv, FrameLayout.LayoutParams(size, size))
        } else {
            val circle = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }
            f.addView(circle, FrameLayout.LayoutParams(size, size))
            if (small != null) {
                f.addView(ImageView(this).apply { setImageDrawable(small) },
                    FrameLayout.LayoutParams(size * 5 / 9, size * 5 / 9, Gravity.CENTER))
            }
        }
        return f
    }

    private fun circleBtn(glyph: String, color: Int, click: () -> Unit): View {
        val v = tv(glyph, 18f, Color.WHITE, true)
        v.gravity = Gravity.CENTER
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        v.setOnClickListener { click() }
        return v
    }

    /* ---------- window / style ---------- */
    fun applyStyle() { h.post { styleNow() } }

    fun preview() {
        h.post {
            styleNow()
            shownSig = ""
            if (callView == null) {
                enqueue(Notif("demo", "Dynamic Island", "oussama",
                    t("Preview of your island ✨", "معاينة الجزيرة الخاصة بك ✨"),
                    t("Preview of your island ✨\nChange size, colors and position from the app.",
                        "معاينة الجزيرة الخاصة بك ✨\nغيّر الحجم والألوان والموضع من التطبيق."),
                    null, null, ACCENTS[cfg.i("accent", 0)], null, emptyList()))
            }
        }
    }

    private fun styleNow() {
        accent = ACCENTS[cfg.i("accent", 0)]
        var y = dp(cfg.d("top"))
        var x = dp(cfg.i("xoff", 0))
        if (cfg.b("autopos") && Build.VERSION.SDK_INT >= 30) {
            try {
                val m = wm.currentWindowMetrics
                val r = m.windowInsets.displayCutout?.boundingRects?.firstOrNull { it.top <= dp(4) && it.width() < m.bounds.width() / 3 }
                if (r != null) {
                    y = max(0, r.centerY() - dp(cfg.d("height")) / 2)
                    x += r.centerX() - m.bounds.width() / 2
                }
            } catch (_: Exception) {}
        }
        lp.y = y
        lp.x = x
        root.layoutDirection = if (Lang.isAr(this)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (cfg.b("border", false)) bg.setStroke(dp(1), 0x33FFFFFF) else bg.setStroke(0, Color.TRANSPARENT)
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    private fun windowBig(on: Boolean) {
        lp.width = if (on) (resources.displayMetrics.widthPixels * 0.97f).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = if (on) dp(320) else WindowManager.LayoutParams.WRAP_CONTENT
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    private fun applyBox(expanded: Boolean, wDp: Int) {
        val p = box.layoutParams as FrameLayout.LayoutParams
        p.width = if (expanded) (resources.displayMetrics.widthPixels * cfg.i("expw", 94) / 100f).toInt()
                  else dp(if (wDp > 0) wDp else cfg.d("width"))
        box.minimumWidth = 0
        box.minimumHeight = if (expanded) 0 else dp(cfg.d("height"))
        box.layoutParams = p
    }

    private fun setBg(color: Int, radius: Float, animate: Boolean) {
        val a = cfg.i("opacity", 100).coerceIn(30, 100) * 255 / 100
        val target = (color and 0x00FFFFFF) or (a shl 24)
        if (!animate) {
            bg.setColor(target); bg.cornerRadius = radius
            curColor = target; curRad = radius
            return
        }
        colorAnim?.cancel(); radAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), curColor, target).apply {
            duration = 350
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
        radAnim = ValueAnimator.ofFloat(curRad, radius).apply {
            duration = 300
            addUpdateListener { bg.cornerRadius = it.animatedValue as Float }
            start()
        }
        curColor = target; curRad = radius
    }

    private fun present(v: View, expanded: Boolean, color: Int, wDp: Int = 0) {
        val appearing = root.visibility != View.VISIBLE
        val rad = if (expanded) dp(cfg.i("radius", 30)).toFloat() else dp(cfg.d("height") / 2).toFloat()
        box.animate().cancel()
        if (appearing) {
            windowBig(false)
            applyBox(expanded, wDp)
            box.removeAllViews()
            box.addView(v, ll(MATCH, WRAP))
            setBg(color, rad, false)
            root.visibility = View.VISIBLE
            box.alpha = 0f; box.scaleX = 0.6f; box.scaleY = 0.6f; box.translationY = -dp(16).toFloat()
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(340).setInterpolator(OvershootInterpolator(1.1f)).start()
            haptic()
        } else {
            box.alpha = 1f; box.scaleX = 1f; box.scaleY = 1f; box.translationY = 0f
            windowBig(true)
            val set = TransitionSet()
            set.setOrdering(TransitionSet.ORDERING_TOGETHER)
            set.addTransition(ChangeBounds())
            set.addTransition(Fade())
            set.setDuration(300)
            set.setInterpolator(OvershootInterpolator(0.7f))
            set.addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) { windowBig(false) }
            })
            TransitionManager.beginDelayedTransition(root, set)
            applyBox(expanded, wDp)
            box.removeAllViews()
            box.addView(v, ll(MATCH, WRAP))
            setBg(color, rad, true)
        }
    }

    private fun hide() {
        if (root.visibility != View.VISIBLE) return
        shownSig = ""
        box.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).translationY(-dp(12).toFloat())
            .setDuration(220).withEndAction {
                if (shownSig.isEmpty()) {
                    root.visibility = View.GONE
                    box.removeAllViews()
                    windowBig(false)
                }
            }.start()
    }

    /* ---------- state machine ---------- */
    fun refresh() { h.post { doRefresh() } }

    private fun mediaVisible(): Boolean {
        if (!cfg.b("t_media")) return false
        val s = controller?.playbackState?.state ?: return false
        return when (s) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
            PlaybackState.STATE_PAUSED -> System.currentTimeMillis() - pausedAt < 20000
            else -> false
        }
    }

    private fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun mediaColor(): Int = if (cfg.b("adaptive")) mediaBg else base()

    private fun doRefresh() {
        val cv = callView
        val nf = notif
        val tp = temp
        when {
            cv != null -> show("call:$callKey", true, base()) { cv }
            nf != null -> show("n:${nf.key}:$notifExpanded:${nf.text}", true, base()) { buildNotif(nf, notifExpanded) }
            tp != null -> show("t:$tempId", false, tempColor, tempW) { tp }
            mediaVisible() -> {
                val expanded = mediaExpanded
                show("m:$trackKey:$expanded:${isPlaying()}:${timerEnd > System.currentTimeMillis()}:${repeatOn}:${favs().contains(trackKey)}",
                    expanded, mediaColor()) { if (expanded) buildMediaExpanded() else buildMediaPill() }
            }
            timerEnd > System.currentTimeMillis() -> show("timer", false, base()) { buildTimer() }
            else -> hide()
        }
    }

    private fun show(sig: String, expanded: Boolean, color: Int, wDp: Int = 0, build: () -> View) {
        if (sig == shownSig && root.visibility == View.VISIBLE) return
        shownSig = sig
        present(build(), expanded, color, wDp)
    }

    /* ---------- transient toasts ---------- */
    private val clearTemp = Runnable { temp = null; doRefresh() }
    private fun setTemp(v: View, ms: Long, color: Int = Color.BLACK, wDp: Int = 0) {
        temp = v; tempColor = color; tempW = wDp; tempId++
        h.removeCallbacks(clearTemp)
        h.postDelayed(clearTemp, ms)
        doRefresh()
    }

    /* ---------- notifications ---------- */
    fun showNotification(n: Notif) { h.post { enqueue(n) } }

    private val nextR = Runnable { notif = queue.removeFirstOrNull(); notifExpanded = false; armNotif(); doRefresh() }
    private val notifTimer = Runnable { finishNotif() }

    private fun armNotif() {
        h.removeCallbacks(notifTimer)
        if (notif != null) {
            val base = cfg.i("dur", 5) * 1000L
            h.postDelayed(notifTimer, if (notifExpanded) base * 2 else base)
        }
    }

    private fun enqueue(n: Notif) {
        if (notif?.key == n.key) { notif = n; armNotif(); doRefresh(); return }
        if (notif == null) { notif = n; notifExpanded = false; armNotif(); doRefresh() }
        else { queue.removeAll { it.key == n.key }; queue.addLast(n) }
    }

    private fun finishNotif() {
        h.removeCallbacks(notifTimer)
        notif = null; notifExpanded = false
        if (queue.isNotEmpty()) h.postDelayed(nextR, 250) else doRefresh()
    }

    fun notifRemoved(key: String) {
        h.post {
            queue.removeAll { it.key == key }
            if (notif?.key == key && key != "demo") finishNotif()
        }
    }

    private fun expandNotif() {
        if (notif == null || notifExpanded) return
        notifExpanded = true; armNotif(); haptic(); doRefresh()
    }

    private fun buildNotif(n: Notif, expanded: Boolean): View {
        val col = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val head = row()
        val color = if (n.color != 0) n.color else accent
        head.addView(avatar(n.large, n.icon, color, dp(44)), ll(dp(44), dp(44)))
        val tx = column().apply { setPadding(dp(12), 0, dp(4), 0) }
        tx.addView(tv(n.app, 11f, 0x99FFFFFF.toInt()))
        if (n.title.isNotEmpty()) tx.addView(tv(n.title, 15f, Color.WHITE, true))
        if (n.text.isNotEmpty() || n.big.isNotEmpty()) {
            tx.addView(tv(if (expanded) n.big else n.text, 13f, 0xCCFFFFFF.toInt(), false, if (expanded) 6 else 2))
        }
        head.addView(tx, ll(0, WRAP, 1f))
        col.addView(head)

        if (expanded) {
            val acts = n.actions.filter { it.remoteInputs == null && it.actionIntent != null }.take(3)
            if (acts.isNotEmpty()) {
                val ar = row().apply { setPadding(0, dp(10), 0, 0) }
                acts.forEach { a ->
                    val b = tv(a.title.toString(), 13f, Color.WHITE, true)
                    b.gravity = Gravity.CENTER
                    b.setPadding(dp(14), dp(8), dp(14), dp(8))
                    b.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(18).toFloat() }
                    b.setOnClickListener { try { a.actionIntent?.send() } catch (_: Exception) {}; finishNotif() }
                    ar.addView(b, ll(0, WRAP, 1f).apply { marginEnd = dp(6) })
                }
                col.addView(ar)
            }
        }

        col.setOnTouchListener(object : View.OnTouchListener {
            var y0 = 0f
            var moved = false
            var long = false
            val lr = Runnable { long = true; expandNotif() }
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = e.rawY; moved = false; long = false; h.postDelayed(lr, 450) }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - y0
                        if (abs(dy) > dp(8)) { moved = true; h.removeCallbacks(lr) }
                        box.translationY = dy.coerceIn(-dp(60).toFloat(), dp(16).toFloat())
                    }
                    MotionEvent.ACTION_UP -> {
                        h.removeCallbacks(lr)
                        val dy = e.rawY - y0
                        box.animate().translationY(0f).setDuration(150).start()
                        if (!long) {
                            if (dy < -dp(28)) finishNotif()
                            else if (dy > dp(28)) expandNotif()
                            else if (!moved) {
                                try { n.tap?.send() } catch (_: Exception) {}
                                finishNotif()
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(lr); box.animate().translationY(0f).setDuration(150).start() }
                }
                return true
            }
        })
        return col
    }

    /* ---------- calls ---------- */
    fun showCall(key: String, title: String, text: String, large: Drawable?, actions: List<Notification.Action>) {
        h.post {
            callKey = key
            val r = row().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
            r.addView(avatar(large, null, accent, dp(48)), ll(dp(48), dp(48)))
            val tx = column().apply { setPadding(dp(12), 0, dp(8), 0) }
            tx.addView(tv(title, 16f, Color.WHITE, true))
            tx.addView(tv(if (text.isEmpty()) t("Incoming call", "مكالمة واردة") else text, 13f, 0xB3FFFFFF.toInt()))
            r.addView(tx, ll(0, WRAP, 1f))
            actions.take(2).forEach { a ->
                val tt = a.title.toString()
                val bad = listOf("decline", "reject", "hang", "end", "رفض", "إنهاء", "انهاء")
                    .any { tt.contains(it, ignoreCase = true) }
                r.addView(
                    circleBtn(if (bad) "✕" else "✓", if (bad) 0xFFFF453A.toInt() else 0xFF30D158.toInt()) {
                        try { a.actionIntent?.send() } catch (_: Exception) {}
                    },
                    ll(dp(46), dp(46)).apply { marginStart = dp(8) }
                )
            }
            callView = r
            doRefresh()
        }
    }

    fun callEnded(key: String) {
        h.post { if (key == callKey) { callKey = null; callView = null; doRefresh() } }
    }

    /* ---------- timer ---------- */
    fun cancelTimer() { h.post { timerEnd = 0; h.removeCallbacks(tick); shownSig = ""; doRefresh() } }

    private fun buildTimer(): View {
        val r = row().apply { setPadding(dp(14), dp(4), dp(14), dp(4)); gravity = Gravity.CENTER }
        r.addView(tv("⏱  ", 15f))
        val tt = tv(mmss(timerEnd - System.currentTimeMillis()), 16f, accent, true)
        timerTv = tt
        r.addView(tt)
        return r
    }

    private val tick = object : Runnable {
        override fun run() {
            if (timerEnd == 0L) return
            val left = timerEnd - System.currentTimeMillis()
            if (left <= 0) {
                timerEnd = 0
                try {
                    getSystemService(Vibrator::class.java)
                        .vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {}
                val r = row().apply { setPadding(dp(16), dp(6), dp(16), dp(6)); gravity = Gravity.CENTER }
                r.addView(tv("⏱ " + t("Timer finished", "انتهى المؤقت"), 15f, Color.WHITE, true))
                setTemp(r, 5000, base(), 200)
                return
            }
            timerTv?.text = mmss(left)
            h.postDelayed(this, 1000)
        }
    }

    /* ---------- lock / unlock ---------- */
    private fun lockToast(locked: Boolean) {
        val r = row().apply { setPadding(dp(14), dp(6), dp(16), dp(6)); gravity = Gravity.CENTER }
        val ic = tv(if (locked) "🔒" else "🔓", 20f)
        r.addView(ic, ll(WRAP, WRAP).apply { marginEnd = dp(8) })
        r.addView(tv(if (locked) t("Locked", "تم القفل") else t("Unlocked", "تم فتح القفل"), 14f, Color.WHITE, true))
        ic.scaleX = 0.3f; ic.scaleY = 0.3f
        ic.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(3f)).start()
        setTemp(r, 1800, base(), 190)
    }

    /* ---------- battery ---------- */
    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF || i.action == Intent.ACTION_USER_PRESENT) {
                if (cfg.b("t_lock")) lockToast(i.action == Intent.ACTION_SCREEN_OFF)
                return
            }
            if (!cfg.b("t_charge")) return
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = (b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 /
                    max(1, b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100)
            val (icon, label, col) = when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> Triple("⚡", t("Charging", "جارٍ الشحن"), 0xFF30D158.toInt())
                Intent.ACTION_BATTERY_LOW -> Triple("🪫", t("Low battery", "البطارية منخفضة"), 0xFFFF453A.toInt())
                else -> Triple("🔌", t("Unplugged", "تم فصل الشاحن"), 0xFFFFFFFF.toInt())
            }
            val r = row().apply { setPadding(dp(14), dp(8), dp(16), dp(8)) }
            r.addView(tv(icon, 22f, col), ll(WRAP, WRAP).apply { marginEnd = dp(10) })
            val tx = column()
            tx.addView(tv(label, 13f, 0xCCFFFFFF.toInt()))
            val pb = ProgressBar(this@IslandService, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = lvl
                progressTintList = ColorStateList.valueOf(col)
                progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            }
            tx.addView(pb, ll(dp(90), dp(6)).apply { topMargin = dp(4) })
            r.addView(tx, ll(0, WRAP, 1f))
            r.addView(tv("$lvl%", 18f, Color.WHITE, true), ll(WRAP, WRAP).apply { marginStart = dp(12) })
            setTemp(r, 3500, base(), 250)
        }
    }

    /* ---------- music ---------- */
    private val mcb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PAUSED) {
                pausedAt = System.currentTimeMillis()
                h.postDelayed({ doRefresh() }, 20500)
            }
            refresh()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) { updateTrack(); refresh() }
    }

    fun setupMedia() {
        h.post {
            try {
                val msm = getSystemService(MediaSessionManager::class.java)
                val cn = ComponentName(this, NotifListener::class.java)
                sessionListener?.let { msm.removeOnActiveSessionsChangedListener(it) }
                val l = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
                sessionListener = l
                msm.addOnActiveSessionsChangedListener(l, cn)
                pick(msm.getActiveSessions(cn))
            } catch (_: SecurityException) { /* notification access not granted yet */ }
        }
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(mcb)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller?.registerCallback(mcb)
        updateTrack()
        refresh()
    }

    private fun updateTrack() {
        val m = controller?.metadata
        val key = (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" +
                (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        if (key == trackKey) return
        trackKey = key
        artBmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        applyArtColors()
        if (artBmp == null && m != null) loadArtUri(m, key)
    }

    private fun loadArtUri(m: MediaMetadata, key: String) {
        val uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: return
        Thread {
            try {
                val bmp = contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) h.post {
                    if (trackKey == key) { artBmp = bmp; applyArtColors(); shownSig = ""; doRefresh() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun applyArtColors() {
        val b = artBmp
        if (b == null) { accent = ACCENTS[cfg.i("accent", 0)]; mediaBg = base(); return }
        val c = dominant(b)
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val sat = hsv[1]
        val hue = hsv[0]
        accent = Color.HSVToColor(floatArrayOf(hue, if (sat < 0.12f) sat else max(sat, 0.55f), 1f))
        mediaBg = Color.HSVToColor(floatArrayOf(hue, if (sat < 0.12f) 0f else sat.coerceAtMost(0.9f), 0.26f))
    }

    private fun dominant(b: Bitmap): Int {
        return try {
            val s = Bitmap.createScaledBitmap(b, 24, 24, true)
            var r = 0.0; var g = 0.0; var bl = 0.0; var ws = 0.0
            val hsv = FloatArray(3)
            for (x in 0 until 24) for (y in 0 until 24) {
                val p = s.getPixel(x, y)
                Color.colorToHSV(p, hsv)
                val w = (hsv[1] * hsv[2]).toDouble() + 0.02
                r += Color.red(p) * w; g += Color.green(p) * w; bl += Color.blue(p) * w; ws += w
            }
            Color.rgb((r / ws).toInt(), (g / ws).toInt(), (bl / ws).toInt())
        } catch (_: Exception) { ACCENTS[0] }
    }

    private fun favs(): MutableSet<String> = (cfg.p.getStringSet("fav", emptySet()) ?: emptySet()).toMutableSet()

    private fun position(c: MediaController): Long {
        val s = c.playbackState ?: return 0L
        var p = s.position
        if (p < 0) return 0L
        if (s.state == PlaybackState.STATE_PLAYING)
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        val d = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return if (d > 0) p.coerceIn(0L, d) else p
    }

    private val collapseR = Runnable { mediaExpanded = false; doRefresh() }
    private fun armCollapse() { h.removeCallbacks(collapseR); h.postDelayed(collapseR, 7000) }

    private val prog = object : Runnable {
        override fun run() {
            val sb = seekBar
            val c = controller
            if (mediaExpanded && sb != null && c != null && sb.isAttachedToWindow) {
                if (!seeking) {
                    val pos = position(c)
                    sb.progress = pos.toInt()
                    posTv?.text = mmss(pos)
                }
                h.postDelayed(this, 500)
            }
        }
    }

    private fun buildMediaPill(): View {
        val hgt = cfg.d("height")
        val r = row().apply { setPadding(dp(6), dp(3), dp(14), dp(3)) }
        val s = dp(hgt - 10)
        r.addView(artView(-1), ll(s, s))
        r.addView(View(this), ll(0, 1, 1f))
        if (timerEnd > System.currentTimeMillis()) {
            val tt = tv(mmss(timerEnd - System.currentTimeMillis()), 14f, accent, true)
            timerTv = tt
            r.addView(tt)
        } else {
            r.addView(WaveView(this).apply { color = accent; active = isPlaying() && cfg.b("t_wave") }, ll(dp(26), dp(18)))
        }
        r.setOnClickListener { mediaExpanded = true; armCollapse(); haptic(); doRefresh() }
        return r
    }

    private fun buildMediaExpanded(): View {
        val c = controller ?: return TextView(this)
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: t("Unknown", "غير معروف")
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val dur = (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val playing = isPlaying()

        val col = column().apply { setPadding(dp(18), dp(16), dp(18), dp(10)) }

        val head = row()
        val art = artView(14)
        art.setOnClickListener { try { c.sessionActivity?.send() } catch (_: Exception) {}; mediaExpanded = false; doRefresh() }
        head.addView(art, ll(dp(60), dp(60)))
        val tx = column().apply { setPadding(dp(14), 0, dp(10), 0) }
        tx.addView(tv(title, 17f, Color.WHITE, true))
        if (artist.isNotEmpty()) tx.addView(tv(artist, 14f, 0xB3FFFFFF.toInt()))
        tx.setOnClickListener { mediaExpanded = false; doRefresh() }
        head.addView(tx, ll(0, WRAP, 1f))
        head.addView(WaveView(this).apply { color = accent; active = playing && cfg.b("t_wave") }, ll(dp(28), dp(22)))
        col.addView(head)

        val sr = row().apply { setPadding(0, dp(14), 0, dp(2)) }
        val pt = tv(mmss(position(c)), 12f, 0xCCFFFFFF.toInt())
        posTv = pt
        val sb = SeekBar(this).apply {
            max = dur.toInt().coerceAtLeast(1)
            progress = position(c).toInt()
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) pt.text = mmss(p.toLong()) }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true; h.removeCallbacks(collapseR) }
                override fun onStopTrackingTouch(s: SeekBar) {
                    seeking = false
                    c.transportControls.seekTo(s.progress.toLong())
                    armCollapse()
                }
            })
        }
        seekBar = sb
        sr.addView(pt)
        sr.addView(sb, ll(0, WRAP, 1f))
        sr.addView(tv(if (dur > 0) mmss(dur) else "--:--", 12f, 0xCCFFFFFF.toInt()))
        col.addView(sr)

        val ctr = row().apply { gravity = Gravity.CENTER }
        val fav = favs().contains(trackKey)
        val heart = tv(if (fav) "♥" else "♡", 26f, if (fav) accent else Color.WHITE)
        heart.gravity = Gravity.CENTER
        heart.setOnClickListener {
            val s = favs(); if (!s.remove(trackKey)) s.add(trackKey)
            cfg.p.edit().putStringSet("fav", s).apply(); armCollapse(); doRefresh()
        }
        val rep = tv("⟳", 28f, if (repeatOn) accent else Color.WHITE)
        rep.gravity = Gravity.CENTER
        rep.setOnClickListener {
            repeatOn = !repeatOn
            c.playbackState?.customActions?.firstOrNull {
                val n = (it.name?.toString() ?: "") + it.action
                n.contains("repeat", true) || n.contains("loop", true)
            }?.let { ca -> c.transportControls.sendCustomAction(ca, null) }
            armCollapse(); doRefresh()
        }
        fun icon(res: Int, f: () -> Unit): ImageView {
            val iv = ImageView(this)
            iv.setImageResource(res)
            iv.setColorFilter(Color.WHITE)
            iv.setOnClickListener { f(); armCollapse() }
            return iv
        }
        ctr.addView(heart, ll(0, dp(48), 1f))
        ctr.addView(icon(android.R.drawable.ic_media_previous) { c.transportControls.skipToPrevious() }, ll(0, dp(40), 1f))
        ctr.addView(icon(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) {
            if (playing) c.transportControls.pause() else c.transportControls.play()
        }, ll(0, dp(56), 1f))
        ctr.addView(icon(android.R.drawable.ic_media_next) { c.transportControls.skipToNext() }, ll(0, dp(40), 1f))
        ctr.addView(rep, ll(0, dp(48), 1f))
        col.addView(ctr, ll(MATCH, WRAP).apply { topMargin = dp(6) })

        h.removeCallbacks(prog)
        h.postDelayed(prog, 150)
        return col
    }
}
