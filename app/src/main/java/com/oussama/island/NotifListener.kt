package com.oussama.island

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    private val declineWords = listOf(
        "decline", "reject", "hang up", "hangup", "end call", "refuse", "refuser", "rejeter", "raccrocher",
        "décliner", "decliner", "rechazar", "colgar", "ablehnen", "auflegen", "رفض", "إنهاء", "انهاء"
    )
    private val answerWords = listOf(
        "answer", "accept", "répondre", "repondre", "accepter", "aceptar", "contestar", "responder",
        "annehmen", "رد", "إجابة", "اجابة", "أجب", "قبول"
    )

    override fun onListenerConnected() {
        IslandService.instance?.setupMedia()
    }

    private fun toBitmap(d: Drawable): Bitmap? {
        return try {
            if (d is BitmapDrawable && d.bitmap != null) d.bitmap
            else {
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(b))
                b
            }
        } catch (e: Exception) { null }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = IslandService.instance ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification

        // Media notifications: use their cover image as a fallback for album art
        if (n.category == Notification.CATEGORY_TRANSPORT) {
            try {
                n.getLargeIcon()?.loadDrawable(this)?.let { d -> toBitmap(d)?.let { svc.mediaArtFallback(it) } }
            } catch (_: Exception) {}
            return
        }

        val cfg = Cfg(this)
        val blocked = cfg.p.getStringSet("blocked", emptySet()) ?: emptySet()
        if (blocked.contains(sbn.packageName)) return

        val isCall = n.category == Notification.CATEGORY_CALL
        val skip = (n.flags and Notification.FLAG_ONGOING_EVENT != 0) ||
                (n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        if (!isCall && skip) return
        if (isCall && !cfg.b("t_call")) return
        if (!isCall && !cfg.b("t_notif")) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        val small: Drawable? = try {
            n.smallIcon?.loadDrawable(this)?.mutate()?.apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        } catch (e: Exception) { null }
        val large: Drawable? = try { n.getLargeIcon()?.loadDrawable(this) } catch (e: Exception) { null }

        val actions = n.actions?.toList() ?: emptyList()

        if (isCall) {
            var answer: PendingIntent? = null
            var decline: PendingIntent? = null
            if (Build.VERSION.SDK_INT >= 31) {
                answer = ex.getParcelable<PendingIntent>("android.answerIntent")
                decline = ex.getParcelable<PendingIntent>("android.declineIntent")
                    ?: ex.getParcelable<PendingIntent>("android.hangUpIntent")
            }
            if (answer == null && decline == null) {
                for (a in actions) {
                    val t = a.title?.toString() ?: ""
                    if (declineWords.any { t.contains(it, true) }) { if (decline == null) decline = a.actionIntent }
                    else if (answerWords.any { t.contains(it, true) }) { if (answer == null) answer = a.actionIntent }
                }
                // Unknown wording: first button = decline, second = answer
                if (answer == null && decline == null && actions.size >= 2) {
                    decline = actions[0].actionIntent
                    answer = actions[1].actionIntent
                } else if (decline == null && answer != null && actions.size >= 2) {
                    decline = actions.firstOrNull { it.actionIntent != answer }?.actionIntent
                } else if (answer == null && decline != null && actions.size >= 2) {
                    answer = actions.firstOrNull { it.actionIntent != decline }?.actionIntent
                }
            }
            svc.showCall(sbn.key, title, text, large, answer, decline)
        } else {
            svc.showNotification(Notif(sbn.key, app, title, text, big, small, large, n.color, n.contentIntent, actions))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandService.instance?.callEnded(sbn.key)
        IslandService.instance?.notifRemoved(sbn.key)
    }
}
