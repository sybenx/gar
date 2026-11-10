package org.stypox.dicio.skills.offline_translator

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What Offline Translator answered. */
sealed interface OfflineTranslation {
    data class Translated(val text: String) : OfflineTranslation

    /**
     * It couldn't translate: [type] is its `ErrorType` (0 language not detected, 1 detected
     * but its language pack isn't installed, 2 anything else, like a missing target pack),
     * [message] what it says about it.
     */
    data class Failed(val type: Int?, val language: String?, val message: String?) :
        OfflineTranslation

    /** The service didn't bind or didn't answer in time. */
    data object Unreachable : OfflineTranslation
}

/** Offline Translator's translation service, behind an interface so tests use fakes. */
fun interface OfflineTranslatorClient {
    /**
     * Translates [text] from [from] to [to], Offline Translator's language codes ("en", "es",
     * "zh_hant"); an empty code means detect the source, or its default target.
     */
    suspend fun translate(text: String, from: String, to: String): OfflineTranslation
}

/**
 * Offline Translator (dev.davidv.translator, F-Droid, GPL-3.0; Firefox's Bergamot models on the
 * phone), checked against its source (github.com/DavidVentura/offline-translator, 2026-10-28):
 * - its exported `AidlTranslationService` (action `dev.davidv.translator.ITranslationService`, no
 *   permission) takes `translate(text, fromLanguage, toLanguage, callback)` and answers on the
 *   callback with the translation or a `TranslationError`; empty languages mean detect the source
 *   and its default target. Its AIDL files are GPL-3.0-or-later OR MIT "so that independently
 *   licensed apps can bind to the service". GAR writes the two binder calls by hand (the wire
 *   format the AIDL compiler generates) rather than turning on AIDL in the build;
 * - `PROCESS_TEXT` returns a translation in `EXTRA_PROCESS_TEXT` only when the user confirms it in
 *   its editable popup (not read-only), so GAR uses it read-only to show text the service couldn't
 *   translate ([open]): "Here's that in Offline Translator.". `ACTION_TRANSLATE` takes no
 *   languages either.
 */
class OfflineTranslatorService(private val context: Context) : OfflineTranslatorClient {

    override suspend fun translate(text: String, from: String, to: String): OfflineTranslation =
        withTimeoutOrNull(TIMEOUT_MILLIS) {
            val connection = Connection()
            try {
                val binder = connection.bind() ?: return@withTimeoutOrNull OfflineTranslation.Unreachable
                suspendCancellableCoroutine<OfflineTranslation> { continuation ->
                    val callback = Callback { result ->
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                    try {
                        call(binder, text, from, to, callback)
                    } catch (e: RemoteException) {
                        Log.w(TAG, "translate failed", e)
                        continuation.resume(OfflineTranslation.Unreachable)
                    } catch (e: RuntimeException) { // a SecurityException, a bad reply parcel
                        Log.w(TAG, "translate failed", e)
                        continuation.resume(OfflineTranslation.Unreachable)
                    }
                }
            } finally {
                connection.unbind()
            }
        } ?: OfflineTranslation.Unreachable

    /** A binding to the service, connected once [bind] returns its binder. */
    private inner class Connection : ServiceConnection {
        private var bound = false
        private var onBinder: ((IBinder?) -> Unit)? = null

        suspend fun bind(): IBinder? = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                onBinder = { binder ->
                    onBinder = null
                    if (continuation.isActive) {
                        continuation.resume(binder)
                    }
                }
                val intent = Intent(SERVICE_ACTION).setPackage(PACKAGE)
                bound = try {
                    context.bindService(intent, this@Connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Not allowed to bind Offline Translator", e)
                    false
                }
                if (!bound) {
                    onBinder?.invoke(null)
                }
            }
        }

        fun unbind() {
            if (bound) {
                bound = false
                try {
                    context.unbindService(this)
                } catch (e: IllegalArgumentException) {
                    // already unbound
                }
            }
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            onBinder?.invoke(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onBinder?.invoke(null)
        }

        override fun onNullBinding(name: ComponentName?) {
            onBinder?.invoke(null)
        }

        override fun onBindingDied(name: ComponentName?) {
            onBinder?.invoke(null)
        }
    }

    /** `ITranslationCallback`, a oneway interface: result first, error second. */
    private class Callback(private val onAnswer: (OfflineTranslation) -> Unit) : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                ON_RESULT -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    onAnswer(OfflineTranslation.Translated(data.readString().orEmpty()))
                    true
                }
                ON_ERROR -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    onAnswer(readError(data))
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }

        /**
         * `in TranslationError`: non-null marker, the parcelable's size, `ErrorType type` (an AIDL
         * enum, a byte), then `language` and `message`.
         */
        private fun readError(data: Parcel): OfflineTranslation.Failed = try {
            if (data.readInt() == 0) {
                OfflineTranslation.Failed(null, null, null)
            } else {
                data.readInt() // the parcelable's size
                val type = data.readByte().toInt()
                OfflineTranslation.Failed(type, data.readString(), data.readString())
            }
        } catch (e: RuntimeException) {
            OfflineTranslation.Failed(null, null, null)
        }
    }

    companion object {
        const val PACKAGE = "dev.davidv.translator"
        private const val TAG = "OfflineTranslator"
        private const val SERVICE_ACTION = "dev.davidv.translator.ITranslationService"
        private const val SERVICE_DESCRIPTOR = "dev.davidv.translator.ITranslationService"
        private const val CALLBACK_DESCRIPTOR = "dev.davidv.translator.ITranslationCallback"
        private const val TRANSLATE = IBinder.FIRST_CALL_TRANSACTION
        private const val ON_RESULT = IBinder.FIRST_CALL_TRANSACTION
        private const val ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 1

        /** The first translation may load its models: a few seconds on a phone. */
        private const val TIMEOUT_MILLIS = 15_000L

        /** `ITranslationService.translate`, as the AIDL compiler's proxy writes it. */
        private fun call(binder: IBinder, text: String, from: String, to: String, callback: Binder) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeString(text)
                data.writeString(from)
                data.writeString(to)
                data.writeStrongBinder(callback)
                binder.transact(TRANSACTION_TRANSLATE, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        private const val TRANSACTION_TRANSLATE = TRANSLATE

        /** Whether Offline Translator is installed (GAR may see every app: QUERY_ALL_PACKAGES). */
        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        /**
         * Shows [text] in Offline Translator's read-only popup (`PROCESS_TEXT`), where the user
         * picks the languages or downloads a missing one. @return false if it didn't start
         */
        fun open(context: Context, text: String): Boolean = try {
            context.startActivity(
                Intent(Intent.ACTION_PROCESS_TEXT)
                    .setPackage(PACKAGE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
