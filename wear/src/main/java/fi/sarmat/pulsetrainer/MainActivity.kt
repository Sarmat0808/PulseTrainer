package fi.sarmat.pulsetrainer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import fi.sarmat.pulsetrainer.core.WorkoutType
import kotlinx.coroutines.flow.MutableSharedFlow

sealed interface Scr {
    data object Home : Scr
    data object Sensor : Scr
    data object Workout : Scr
    data object Switch : Scr
    data class Summary(val id: String) : Scr
    data object Hrv : Scr
    data object Profile : Scr
    data object History : Scr
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Physical multi-function buttons (if the watch exposes them to apps) open the exercise switcher. */
        val stemPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val stem = keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3 || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY
        if (stem && WorkoutEngine.ui.value.running) {
            stemPresses.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private fun neededPermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BODY_SENSORS)
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
}

@Composable
fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val stack = remember {
        mutableStateListOf<Scr>(Scr.Home).apply { if (WorkoutEngine.ui.value.running) add(Scr.Workout) }
    }
    fun push(s: Scr) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        HrSensor.connectSaved()
    }
    LaunchedEffect(Unit) {
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else HrSensor.connectSaved()
    }

    // Workout finished (on the watch or from the phone) -> summary.
    val ui by WorkoutEngine.ui.collectAsState()
    LaunchedEffect(ui.finishedId) {
        val id = ui.finishedId ?: return@LaunchedEffect
        stack.removeAll { it == Scr.Workout || it == Scr.Switch }
        push(Scr.Summary(id))
        WorkoutEngine.clearFinished()
    }
    LaunchedEffect(Unit) {
        MainActivity.stemPresses.collect { if (stack.lastOrNull() != Scr.Switch) push(Scr.Switch) }
    }

    fun startWorkout(t: WorkoutType) {
        WorkoutService.start(ctx, t)
        stack.removeAll { it != Scr.Home }
        push(Scr.Workout)
    }

    val current = stack.last()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (current) {
            Scr.Workout -> WorkoutScreen(onSwitch = { push(Scr.Switch) })
            Scr.Home -> HomeScreen(
                onStart = ::startWorkout,
                open = { push(it) }
            )
            else -> androidx.compose.runtime.key(stack.size, current) { Dismissible(onDismiss = { pop() }) {
                when (current) {
                    Scr.Sensor -> SensorScreen()
                    Scr.Switch -> SwitchScreen(onPick = { t ->
                        if (WorkoutEngine.ui.value.running) { WorkoutEngine.switchTo(t); pop() } else startWorkout(t)
                    })
                    is Scr.Summary -> SummaryScreen(current.id, onDone = {
                        stack.removeAll { it != Scr.Home }
                    })
                    Scr.Hrv -> HrvScreen()
                    Scr.Profile -> ProfileScreen()
                    Scr.History -> HistoryScreen(open = { push(Scr.Summary(it)) })
                    else -> {}
                }
            } }
        }
    }
    if (current != Scr.Home && current != Scr.Workout) BackHandler { pop() }
}

/** Swipe right to go back (not used on the workout screen). */
@Composable
private fun Dismissible(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(state = state, onDismissed = onDismiss) { isBackground ->
        if (isBackground) Box(Modifier.fillMaxSize().background(Color.Black)) else content()
    }
}
