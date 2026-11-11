package org.stypox.dicio.gar.ui

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

/**
 * GAR's small notification icon, the white Tide glyph, tinted with the Tide background so the
 * notification shade shows it on a teal circle instead of the system's default color.
 */
fun NotificationCompat.Builder.setGarSmallIcon(context: Context): NotificationCompat.Builder =
    setSmallIcon(R.drawable.ic_gar_notification)
        .setColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
