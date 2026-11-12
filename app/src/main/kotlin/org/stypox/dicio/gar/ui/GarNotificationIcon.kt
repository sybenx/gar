package org.stypox.dicio.gar.ui

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

/**
 * Garble's small notification icon, the mark's logo pose in one color, tinted with the middle mark
 * color so the notification shade shows it in Garble's indigo instead of the system's default.
 */
fun NotificationCompat.Builder.setGarSmallIcon(context: Context): NotificationCompat.Builder =
    setSmallIcon(R.drawable.ic_gar_notification)
        .setColor(ContextCompat.getColor(context, R.color.gar_notification_accent))
