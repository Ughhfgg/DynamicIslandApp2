package com.oussama.island

import android.app.Notification
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandService.instance?.setupMedia()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = IslandService.instance ?: return
        if (sbn.packageName == packageName) return
        val cfg = Cfg(this)
        val blocked = cfg.p.getStringSet("blocked", emptySet()) ?: emptySet()
        if (blocked.contains(sbn.packageName)) return

        val n = sbn.notification
        val isCall = n.category == Notification.CATEGORY_CALL
        val skip = (n.flags and Notification.FLAG_ONGOING_EVENT != 0) ||
                (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) ||
                n.category == Notification.CATEGORY_TRANSPORT
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
            svc.showCall(sbn.key, title, text, large, actions)
        } else {
            svc.showNotification(Notif(sbn.key, app, title, text, big, small, large, n.color, n.contentIntent, actions))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandService.instance?.callEnded(sbn.key)
        IslandService.instance?.notifRemoved(sbn.key)
    }
}
