package com.oussama.island

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Dynamic Island - app UI. Developer: oussama */
class MainActivity : Activity() {

    private lateinit var cfg: Cfg
    private var ar = false
    private var accent = ACCENTS[0]
    private val BG = 0xFF0B0D17.toInt()
    private val CARD = 0xFF151A2C.toInt()
    private val SUB = 0xFF9AA3C0.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val refreshers = ArrayList<() -> Unit>()
    private var powerBtn: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun t(en: String, a: String) = if (ar) a else en
    private fun running() = IslandService.instance != null
    private fun ready() = Settings.canDrawOverlays(this) || IslandAccess.instance != null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Cfg(this)
        ar = Lang.isAr(this)
        accent = ACCENTS[cfg.i("accent", 0)]
        window.setBackgroundDrawable(ColorDrawable(BG))
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        setContentView(scroll)
        build()
        scroll.post { scroll.scrollTo(0, cfg.i("scrollY", 0)) }
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    private fun restart() {
        cfg.p.edit().putInt("scrollY", scroll.scrollY).apply()
        recreate()
    }

    /* ---------- building blocks ---------- */
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun lpv(w: Int = MATCH, hh: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh).apply { topMargin = dp(top) }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(CARD, 22)
        }
        c.addView(text(title.uppercase(), 12f, accent, true))
        content.addView(c, lpv(top = 14))
        return c
    }

    private fun states() = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())

    private fun switchRow(card: LinearLayout, en: String, a: String, key: String, def: Boolean = true) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2)) }
        r.addView(text(t(en, a), 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = cfg.b(key, def)
            thumbTintList = ColorStateList(states(), intArrayOf(accent, 0xFF8A93B0.toInt()))
            trackTintList = ColorStateList(states(), intArrayOf((accent and 0x00FFFFFF) or 0x66000000, 0x33FFFFFF))
            setOnCheckedChangeListener { _, v ->
                cfg.p.edit().putBoolean(key, v).apply()
                IslandService.instance?.preview()
            }
        }
        r.addView(sw)
        card.addView(r)
    }

    private fun slider(card: LinearLayout, en: String, a: String, key: String, min: Int, max: Int, def: Int, unit: String) {
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        head.addView(text(t(en, a), 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = text("${cfg.i(key, def)}$unit", 14f, accent, true)
        head.addView(value)
        card.addView(head)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = cfg.i(key, def) - min
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    if (!u) return
                    cfg.p.edit().putInt(key, p + min).apply()
                    value.text = "${p + min}$unit"
                    IslandService.instance?.preview()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        card.addView(sb)
    }

    private fun swatches(card: LinearLayout, colors: IntArray, key: String, def: Int) {
        val r = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0) }
        val cur = cfg.i(key, def)
        colors.forEachIndexed { i, c ->
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(if (i == cur) 3 else 1), if (i == cur) Color.WHITE else 0x44FFFFFF)
                }
                setOnClickListener { cfg.p.edit().putInt(key, i).apply(); IslandService.instance?.applyStyle(); restart() }
            }
            r.addView(v, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        }
        card.addView(r)
    }

    private fun button(card: LinearLayout, label: String, filled: Boolean = false, click: () -> Unit): TextView {
        val b = text(label, 14f, if (filled) Color.WHITE else accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = if (filled) rounded(accent, 14) else GradientDrawable().apply {
                setColor(0x00000000); cornerRadius = dp(14).toFloat(); setStroke(dp(1), accent)
            }
            setOnClickListener { click() }
        }
        card.addView(b, lpv(top = 10))
        return b
    }

    private fun statusRow(card: LinearLayout, en: String, a: String, ok: () -> Boolean, click: () -> Unit) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        r.addView(text(t(en, a), 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val badge = text("", 12f, Color.WHITE, true).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        r.addView(badge)
        r.setOnClickListener { click() }
        val upd = {
            val good = ok()
            badge.text = if (good) t("Enabled ✓", "مفعّل ✓") else t("Enable", "تفعيل")
            badge.background = rounded(if (good) 0xFF1E9E4F.toInt() else 0xFFD93A3A.toInt(), 14)
        }
        upd()
        refreshers.add(upd)
        card.addView(r)
    }

    /* ---------- screen ---------- */
    private fun build() {
        val darkAccent = floatArrayOf(0f, 0f, 0f).also {
            Color.colorToHSV(accent, it); it[2] = it[2] * 0.55f
        }.let { Color.HSVToColor(it) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, darkAccent)).apply {
                cornerRadius = dp(28).toFloat()
            }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Dynamic Island", 26f, Color.WHITE, true))
        titles.addView(text(t("Smart capsule for your phone", "كبسولة ذكية لهاتفك"), 13f, 0xCCFFFFFF.toInt()))
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        val lang = LinearLayout(this).apply { background = rounded(0x33000000, 16); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        listOf("en" to "EN", "ar" to "عربي").forEach { (code, label) ->
            val sel = (code == "ar") == ar
            lang.addView(text(label, 13f, if (sel) accent else Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                if (sel) background = rounded(Color.WHITE, 13)
                setOnClickListener { cfg.p.edit().putString("lang", code).apply(); IslandService.instance?.applyStyle(); restart() }
            })
        }
        top.addView(lang)
        header.addView(top)

        val pb = text("", 16f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, 16)
        }
        powerBtn = pb
        val updPower = { pb.text = if (running()) t("■  Stop Island", "■  إيقاف الجزيرة") else t("▶  Start Island", "▶  تشغيل الجزيرة") }
        updPower()
        refreshers.add(updPower)
        pb.setOnClickListener {
            if (running()) {
                stopService(Intent(this, IslandService::class.java))
            } else if (ready()) {
                startForegroundService(Intent(this, IslandService::class.java))
            } else {
                Toast.makeText(this, t("Enable overlay permission first", "فعّل إذن الظهور فوق التطبيقات أولاً"), Toast.LENGTH_LONG).show()
            }
            pb.postDelayed({ updPower() }, 500)
        }
        header.addView(pb, lpv(top = 18))
        content.addView(header)

        // Setup
        val setup = card(t("Setup", "الإعداد"))
        statusRow(setup, "Display over other apps", "الظهور فوق التطبيقات", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        statusRow(setup, "Above system island (Accessibility)", "الظهور فوق جزيرة النظام (إمكانية الوصول)", { IslandAccess.instance != null }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusRow(setup, "Notification access", "الوصول إلى الإشعارات", {
            (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        statusRow(setup, "Unrestricted battery", "بطارية بلا قيود", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        // Features
        val feat = card(t("Features", "الميزات"))
        switchRow(feat, "Music capsule", "كبسولة الموسيقى", "t_media")
        switchRow(feat, "Notifications", "الإشعارات", "t_notif")
        switchRow(feat, "Incoming calls", "المكالمات الواردة", "t_call")
        switchRow(feat, "Charging & battery", "الشحن والبطارية", "t_charge")
        switchRow(feat, "Flashlight indicator", "مؤشر الكشاف", "t_torch")
        switchRow(feat, "Lock / unlock animation", "حركة القفل وفتح القفل", "t_lock")
        switchRow(feat, "Adaptive color from album art", "لون متكيّف مع غلاف الأغنية", "adaptive")
        switchRow(feat, "Equalizer animation", "حركة المعادل الصوتي", "t_wave")
        switchRow(feat, "Haptic feedback", "الاهتزاز اللمسي", "haptic")
        switchRow(feat, "Glass border", "إطار شفاف", "border", false)

        // Size & position
        val size = card(t("Size & position", "الحجم والموضع"))
        switchRow(size, "Auto-align to camera", "محاذاة تلقائية مع الكاميرا", "autopos")
        slider(size, "Width", "العرض", "width", 90, 260, cfg.def("width"), " dp")
        slider(size, "Height", "الارتفاع", "height", 24, 64, cfg.def("height"), " dp")
        slider(size, "Vertical position", "الموضع العمودي", "top", 0, 90, cfg.def("top"), " dp")
        slider(size, "Horizontal shift", "الإزاحة الأفقية", "xoff", -150, 150, 0, " dp")
        slider(size, "Expanded width", "عرض الوضع الموسّع", "expw", 70, 100, 94, " %")
        slider(size, "Corner radius", "استدارة الزوايا", "radius", 12, 44, 30, " dp")
        slider(size, "Opacity", "الشفافية", "opacity", 40, 100, 100, " %")
        slider(size, "Notification duration", "مدة ظهور الإشعار", "dur", 2, 12, 5, " s")
        button(size, t("Preview island", "معاينة الجزيرة")) { IslandService.instance?.preview() ?: toastStart() }
        button(size, t("Reset to default", "إعادة الضبط الافتراضي")) {
            listOf("width", "height", "top", "xoff", "expw", "radius", "opacity", "dur").forEach { cfg.p.edit().remove(it).apply() }
            IslandService.instance?.applyStyle(); restart()
        }

        // Appearance
        val look = card(t("Appearance", "المظهر"))
        look.addView(text(t("App accent color", "لون التطبيق"), 14f), lpv(top = 10))
        swatches(look, ACCENTS, "accent", 0)
        look.addView(text(t("Island background", "خلفية الجزيرة"), 14f), lpv(top = 14))
        swatches(look, BG_PRESETS, "bgIdx", 0)

        // Timer
        val tm = card(t("Timer", "المؤقت"))
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = t("Minutes, e.g. 5", "الدقائق، مثال: 5")
            setTextColor(Color.WHITE); setHintTextColor(SUB)
            backgroundTintList = ColorStateList.valueOf(accent)
        }
        tm.addView(et, lpv(top = 6))
        button(tm, t("Start timer", "ابدأ المؤقت"), true) {
            val m = et.text.toString().toIntOrNull() ?: 0
            if (m > 0 && ready()) {
                startForegroundService(Intent(this, IslandService::class.java)
                    .setAction(IslandService.ACTION_TIMER).putExtra(IslandService.EXTRA_SECONDS, m * 60))
            } else Toast.makeText(this, t("Enter minutes and enable permission", "أدخل الدقائق وفعّل الإذن"), Toast.LENGTH_SHORT).show()
        }
        button(tm, t("Cancel timer", "إلغاء المؤقت")) { IslandService.instance?.cancelTimer() }

        // Apps
        val apps = card(t("Apps", "التطبيقات"))
        apps.addView(text(t("Choose which apps can show notifications on the island.",
            "اختر التطبيقات التي يُسمح لها بعرض الإشعارات على الجزيرة."), 13f, SUB), lpv(top = 6))
        button(apps, t("Choose apps", "اختيار التطبيقات")) { chooseApps() }

        // About
        val about = card(t("About", "حول"))
        about.addView(text(t("Developer: oussama", "المطور: oussama"), 15f, Color.WHITE, true), lpv(top = 8))
        about.addView(text("Dynamic Island v2.0", 12f, SUB))
        about.addView(text(t("Tip: after enabling notification access, stop and start the island once.",
            "نصيحة: بعد تفعيل الوصول إلى الإشعارات أوقف الجزيرة ثم شغّلها مرة واحدة."), 12f, SUB), lpv(top = 8))
    }

    private fun toastStart() {
        Toast.makeText(this, t("Start the island first", "شغّل الجزيرة أولاً"), Toast.LENGTH_SHORT).show()
    }

    private fun chooseApps() {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val blocked = (cfg.p.getStringSet("blocked", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(list.size) { !blocked.contains(list[it].first) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(t("Show notifications from", "عرض إشعارات من"))
            .setMultiChoiceItems(list.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) blocked.remove(list[i].first) else blocked.add(list[i].first)
            }
            .setPositiveButton(t("Save", "حفظ")) { _, _ -> cfg.p.edit().putStringSet("blocked", blocked).apply() }
            .setNegativeButton(t("Cancel", "إلغاء"), null)
            .show()
    }
}
