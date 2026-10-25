package com.mysi.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Allow/Deny as a heads-up notification, so the agent can ask while another app (WhatsApp,
 * Amazon…) is on screen. Unanswered requests are denied after two minutes.
 */
object Approvals {
    private const val CHANNEL = "approvals"
    private const val EXTRA_ID = "id"
    private const val EXTRA_ALLOW = "allow"
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private val ids = AtomicInteger(100)

    suspend fun ask(ctx: Context, title: String, message: String): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Boolean>()
        pending[id] = answer
        fun action(allow: Boolean) =
            PendingIntent.getBroadcast(
                ctx,
                id * 2 + if (allow) 1 else 0,
                Intent(ctx, ApprovalReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOW, allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message.lineSequence().firstOrNull { it.isNotBlank() })
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Deny", action(false)).build())
            .addAction(Notification.Action.Builder(null, "Allow", action(true)).build())
            .build()
        nm.notify(id, n)
        return try {
            withTimeoutOrNull(120_000) { answer.await() } ?: false
        } finally {
            pending.remove(id)
            nm.cancel(id)
        }
    }

    fun answer(id: Int, allow: Boolean) {
        pending[id]?.complete(allow)
    }

    internal fun extras(intent: Intent) = intent.getIntExtra(EXTRA_ID, -1) to intent.getBooleanExtra(EXTRA_ALLOW, false)
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (id, allow) = Approvals.extras(intent)
        Approvals.answer(id, allow)
    }
}
