package com.copiosa.data.maintenance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.copiosa.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "cards are due" reminder. An interface so [com.copiosa.data.maintenance.tasks.ReviewReminderTask]
 * stays JVM-testable with a fake; the Android implementation is bound in MaintenanceModule.
 */
interface ReviewReminderNotifier {
    /** Posts (or silently skips, if notifications are off) a due-cards reminder for [profileTag]. */
    fun notifyDue(profileTag: String, dueCount: Int)

    companion object {
        /**
         * Intent action that opens the app straight into a review session. Matched by
         * `ui.navigation.LaunchAction`; also used (as a literal) by `res/xml/shortcuts.xml`.
         */
        const val ACTION_OPEN_REVIEW = "com.copiosa.action.REVIEW"
    }
}

/** Notification-channel + PendingIntent plumbing for the review reminder. */
@Singleton
class AndroidReviewReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReviewReminderNotifier {

    override fun notifyDue(profileTag: String, dueCount: Int) {
        val manager = NotificationManagerCompat.from(context)
        // Covers both the API 33+ runtime permission and notifications disabled in system settings.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        // The launcher intent (rather than an activity class reference) keeps this layer from
        // depending on the UI; the action routes it into a review session via LaunchAction.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.action = ReviewReminderNotifier.ACTION_OPEN_REVIEW
        val pending = PendingIntent.getActivity(
            context,
            profileTag.hashCode(),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_review)
            .setContentTitle(context.getString(R.string.review_reminder_title))
            .setContentText(
                context.resources.getQuantityString(R.plurals.review_reminder_body, dueCount, dueCount),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // One notification per profile (id = tag hash), so multi-profile reminders don't clobber
        // each other. notify can still throw if permission is revoked mid-flight — never crash
        // background maintenance over a notification.
        runCatching { manager.notify(profileTag.hashCode(), notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.review_reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "review_reminders"
    }
}
