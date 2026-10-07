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
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
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
    data class Arrange(val type: WorkoutType) : Scr
    data object More : Scr
    data object Order : Scr
    data object Ready : Scr
    data object Stress : Scr
    data object Breathe : Scr
    data class Intervals(val type: WorkoutType) : Scr
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Physical multi-function buttons (if the watch exposes them to apps) open the exercise switcher. */
        val stemPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        /** A workout to start right away (from a tile or from the phone). */
        val pendingStart = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("start")?.let { pendingStart.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra("start")?.let { pendingStart.value = it }
        setContent {
            // Larger, crisper text on the wrist (adjustable in Profile → «Размер шрифта»).
            val scale by Storage.fontScale.collectAsState()
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, base.fontScale * scale)
            ) { MaterialTheme { AppRoot() } }
        }
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
    if (Build.VERSION.SDK_INT >= 29) list += Manifest.permission.ACTIVITY_RECOGNITION
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

    // Background pulse needs a separate "allow all the time" grant after the normal sensor permission.
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Passive.register(ctx)
    }
    fun askBackground() {
        val bgPerm = "android.permission.BODY_SENSORS_BACKGROUND"
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, bgPerm) != PackageManager.PERMISSION_GRANTED && Passive.enabled
        ) bgLauncher.launch(bgPerm) else Passive.register(ctx)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        HrSensor.connectSaved()
        askBackground()
    }
    LaunchedEffect(Unit) {
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else { HrSensor.connectSaved(); askBackground() }
    }

    // Workout finished (on the watch or from the phone) -> summary.
    val ui by WorkoutEngine.ui.collectAsState()
    LaunchedEffect(ui.finishedId) {
        val id = ui.finishedId ?: return@LaunchedEffect
        stack.removeAll { it == Scr.Workout || it == Scr.Switch }
        push(Scr.Summary(id))
        WorkoutEngine.clearFinished()
    }
    LaunchedEffect(ui.discarded) {
        if (!ui.discarded) return@LaunchedEffect
        stack.removeAll { it != Scr.Home }
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

    /** Interval workouts first show their timer settings (Start is at the top). */
    fun requestStart(t: WorkoutType) {
        if (t.mode == fi.sarmat.pulsetrainer.core.Mode.ROUNDS && !WorkoutEngine.ui.value.running) push(Scr.Intervals(t))
        else startWorkout(t)
    }

    val pending by MainActivity.pendingStart.collectAsState()
    LaunchedEffect(pending) {
        val name = pending ?: return@LaunchedEffect
        MainActivity.pendingStart.value = null
        val t = WorkoutType.of(name)
        if (WorkoutEngine.ui.value.running) { WorkoutEngine.switchTo(t); stack.removeAll { it != Scr.Home }; push(Scr.Workout) }
        else startWorkout(t)
    }

    val current = stack.last()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (current) {
            Scr.Workout -> WorkoutScreen(onSwitch = { push(Scr.Switch) })
            Scr.Home -> HomeScreen(
                onStart = ::requestStart,
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
                    is Scr.Arrange -> ArrangeScreen(current.type, onStart = ::requestStart)
                    Scr.More -> MoreScreen(onStart = ::requestStart, open = { push(it) })
                    is Scr.Intervals -> IntervalSetupScreen(current.type, onStart = ::startWorkout)
                    Scr.Order -> OrderScreen()
                    Scr.Ready -> ReadyScreen(open = { push(it) })
                    Scr.Stress -> StressScreen(open = { push(it) })
                    Scr.Breathe -> BreatheScreen(onDone = { pop() })
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
    SwipeToDismissBox(onDismissed = onDismiss, state = state) { isBackground: Boolean ->
        if (isBackground) Box(Modifier.fillMaxSize().background(Color.Black)) else content()
    }
}
