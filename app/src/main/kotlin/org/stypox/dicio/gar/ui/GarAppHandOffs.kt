package org.stypox.dicio.gar.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import org.stypox.dicio.gar.sheet.GarPermissionHandOff
import org.stypox.dicio.util.checkPermissions

/**
 * The assistant sheet's hand-offs to MainActivity (gar/DESIGN.md, "Home and chips"), read once
 * per intent from the activity's intent and from each new one. Composed by the navigation, not
 * by the home screen, so a hand-off is answered whatever screen the activity was left on:
 * `cc.aaro.gar.extra.PERMISSIONS` asks for those permissions and tells the sheet's waiting turn
 * the result; `cc.aaro.gar.extra.TYPE` calls [onType] (back to the home screen, text field open).
 */
@Composable
fun GarAppHandOffs(onType: () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val context = LocalContext.current
    // a second launcher: Dicio's feeds a channel that only the evaluator's requester receives from
    val launcher = rememberPermissionFlowRequestLauncher { granted ->
        GarPermissionHandOff.complete(granted.values.all { it })
    }
    val handle by rememberUpdatedState(fun(intent: Intent?) {
        if (intent == null) {
            return
        }
        if (intent.hasExtra(EXTRA_TYPE)) {
            intent.removeExtra(EXTRA_TYPE)
            onType()
        }
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: return
        intent.removeExtra(EXTRA_PERMISSIONS)
        if (checkPermissions(context, *permissions)) {
            GarPermissionHandOff.complete(true)
        } else {
            launcher.launch(permissions)
        }
    })
    // the intent that created the activity: once, not again after a configuration change
    var initialHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (!initialHandled) {
            initialHandled = true
            handle(activity.intent)
        }
    }
    DisposableEffect(activity) {
        val listener = Consumer<Intent> { handle(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

// copies of GarSheet.EXTRA_TYPE and GarSheet.EXTRA_PERMISSIONS (spec: no compile dependency)
private const val EXTRA_TYPE = "cc.aaro.gar.extra.TYPE"
private const val EXTRA_PERMISSIONS = "cc.aaro.gar.extra.PERMISSIONS"
