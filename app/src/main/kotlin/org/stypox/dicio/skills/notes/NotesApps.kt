package org.stypox.dicio.skills.notes

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** A notes app that takes a note shared to it as text. */
data class NotesApp(val packageName: String, val label: String)

/**
 * The notes app a new note is also offered to, from a button on its card: the first of
 * [PREFERRED] that is installed and has an activity for `ACTION_SEND` with `text/plain`, checked
 * on the phone with the package manager, never assumed. Notally's manifest declares its
 * `TakeNote` activity for exactly that (github.com/OmGodse/Notally, `AndroidManifest.xml`); FUTO
 * Notes and Obsidian are offered only if their installed version declares it too. The note is
 * sent as `EXTRA_TEXT`; the app shows it and the user saves it there. Nothing is sent without
 * that tap.
 */
object NotesApps {
    private const val TAG = "NotesApps"

    /** Notally, FUTO Notes, Obsidian: the notes apps on Aaron's phone, in this order. */
    val PREFERRED = listOf("com.omgodse.notally", "com.futo.notes", "md.obsidian")

    @SuppressLint("QueryPermissionsNeeded") // GAR can see all apps (QUERY_ALL_PACKAGES)
    fun find(packageManager: PackageManager): NotesApp? = PREFERRED.firstNotNullOfOrNull { pkg ->
        val takes = try {
            packageManager.queryIntentActivities(shareIntent(pkg, ""), 0).isNotEmpty()
        } catch (e: RuntimeException) {
            false
        }
        if (!takes) {
            return@firstNotNullOfOrNull null
        }
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return@firstNotNullOfOrNull null
        }
        NotesApp(pkg, label)
    }

    fun shareIntent(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .setPackage(packageName)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [app] with [text]; false if it can't take it anymore. */
    fun send(context: Context, app: NotesApp, text: String): Boolean = try {
        context.startActivity(shareIntent(app.packageName, text))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Could not share the note with ${app.packageName}", e)
        false
    }

    /** Lets the user pick any app for [text] (export). */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not share", e)
        }
    }
}
