package com.mysi.agent

import android.app.Notification
import android.service.notification.NotificationListenerService

class NotificationReader : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    companion object {
        @Volatile var instance: NotificationReader? = null

        fun read(limit: Int): Any {
            val svc = instance ?: return "Notification access is not granted. Ask the user to enable it."
            return svc.activeNotifications
                .sortedByDescending { it.postTime }
                .take(limit)
                .map {
                    val e = it.notification.extras
                    mapOf(
                        "app" to it.packageName,
                        "title" to e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                        "text" to e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                        "time" to Tools.time(it.postTime),
                    )
                }
        }
    }
}
