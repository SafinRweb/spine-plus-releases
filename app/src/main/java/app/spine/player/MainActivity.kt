package app.spine.player

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import app.spine.player.ui.DemoMode
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.spine.player.data.audioPermission
import app.spine.player.data.hasAudioPermission
import app.spine.player.download.sharedLink
import app.spine.player.ui.LibraryViewModel
import app.spine.player.ui.NameScreen
import app.spine.player.ui.SpineApp
import app.spine.player.ui.UiSettings
import app.spine.player.ui.WelcomeScreen
import app.spine.player.ui.theme.Palette
import app.spine.player.ui.theme.SpineTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels()
    private val motion by lazy { AppMotion(UiSettings.get(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars. Each screen then sets dark or light bar icons
        // to suit its own background (see SystemBarIcons).
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // A debug build can be started with a made-up library, for taking screenshots.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        DemoMode.saveStats = debuggable && intent.getBooleanExtra("savestats", false)
        DemoMode.quiet = debuggable && intent.getBooleanExtra("quiet", false)
        DemoMode.wide = debuggable && intent.getBooleanExtra("wide", false)
        DemoMode.start(
            demo = debuggable && intent.getBooleanExtra("demo", false),
            play = intent.getStringExtra("play"),
            shelf = intent.getStringExtra("shelf"),
            screen = intent.getStringExtra("screen"),
            animations = if (intent.hasExtra("animations")) intent.getBooleanExtra("animations", true) else null,
            fontScale = if (intent.hasExtra("fontScale")) intent.getFloatExtra("fontScale", 1f) else null,
            getWords = intent.getStringExtra("get"),
            widthDp = intent.getIntExtra("width", 0).takeIf { it > 0 },
            dark = if (intent.hasExtra("dark")) intent.getBooleanExtra("dark", false) else null,
            name = intent.getStringExtra("name"),
            statsKind = intent.getStringExtra("stats"),
        )
        // A debug build trying the video player on a real file can also ask for another text
        // size and width, which the made-up library would otherwise be needed for.
        if (debuggable && !DemoMode.enabled) {
            DemoMode.fontScale = if (intent.hasExtra("fontScale")) intent.getFloatExtra("fontScale", 1f) else null
            DemoMode.widthDp = intent.getIntExtra("width", 0).takeIf { it > 0 }
        }
        // Not after a rotation: the intent is still the old one then, and was already acted on.
        if (savedInstanceState == null) {
            showPlayingIfAsked(intent)
            getIfShared(intent)
            openIfHanded(intent)
        }
        // Read the music now, not after the first frame: by the time there is something to
        // draw, the shelf has its albums, and the app never opens on an empty page.
        if (hasAudioPermission()) viewModel.scan()
        // Every animation in the app asks this how fast to run, so one switch stops them all.
        window.decorView.compositionContext =
            window.decorView.createLifecycleAwareWindowRecomposer(motion, lifecycle)
        // The window itself is the paper colour, so in dark nothing light shows around the
        // first frame or behind a screen as it slides.
        UiSettings.get(this)
        window.setBackgroundDrawable(ColorDrawable(Palette.paper.paper.toArgb()))
        setContent {
            SpineTheme {
                SpineRoot(viewModel)
            }
        }
        holdFirstFrameForLibrary()
    }

    /** The app was already open when the notification was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPlayingIfAsked(intent)
        getIfShared(intent)
        openIfHanded(intent)
    }

    /** Another app shared a link to Spine+: it goes into the Get tab's box. */
    private fun getIfShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val words = sharedLink(intent) ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!words.isNullOrEmpty()) viewModel.askToGet(words)
    }

    /** Another app opened a song or a video with Spine+: it is played. */
    private fun openIfHanded(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let { viewModel.askToOpen(it) }
    }

    private fun showPlayingIfAsked(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_PLAYING && hasAudioPermission()) viewModel.askToShowPlaying()
    }

    override fun onResume() {
        super.onResume()
        // The phone's own "remove animations" setting still counts.
        motion.phoneScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /**
     * Keeps the splash screen up until the library has been read, so the first thing drawn
     * is the shelf with its albums arriving. Gives up after a moment rather than hang on a
     * slow phone; the shelf then fills in as before.
     */
    private fun holdFirstFrameForLibrary() {
        if (!hasAudioPermission()) return
        val content = findViewById<View>(android.R.id.content)
        val giveUpAt = SystemClock.uptimeMillis() + FIRST_FRAME_WAIT_MS
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!viewModel.scanned.value && SystemClock.uptimeMillis() < giveUpAt) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    companion object {
        /** Sent by the playback notification: open on Now playing. */
        const val ACTION_SHOW_PLAYING = "app.spine.player.SHOW_PLAYING"

        private const val FIRST_FRAME_WAIT_MS = 1_200L
    }
}

/**
 * How fast animations run: at the phone's own speed, or not at all when the user has
 * switched animations off in Settings. At zero every animation jumps straight to its end.
 */
private class AppMotion(private val settings: UiSettings) : MotionDurationScale {
    @Volatile
    var phoneScale = 1f

    override val scaleFactor: Float
        get() = if (settings.animations.value) phoneScale else 0f
}

/** What is on show as the app opens: the first screen, the question of a name, or the app. */
private enum class FirstRun { Welcome, Name, App }

/** Shows the first-run screens until the audio permission is granted and the name asked, then the app itself. */
@Composable
private fun SpineRoot(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAudioPermission()) }
    var denied by rememberSaveable { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        denied = !allowed
    }

    // Runs on every return to the app: after the permission prompt, after a trip to
    // Settings, and after copying new music to the phone.
    LifecycleResumeEffect(Unit) {
        granted = context.hasAudioPermission()
        if (granted) viewModel.scan()
        onPauseOrDispose { }
    }

    // The name is asked once, after the first screen. Someone who had the app before it
    // asked is asked the next time they open it.
    val settings = remember { UiSettings.get(context) }
    var nameAsked by remember { mutableStateOf(settings.nameAsked) }
    // A debug build can show the first-run screens again, to check them; with a made-up
    // library it otherwise goes straight to the app.
    val step = when {
        !granted || DemoMode.screen == "welcome" || DemoMode.screen == "welcome-denied" -> FirstRun.Welcome
        DemoMode.screen == "name" && !nameAsked -> FirstRun.Name
        // (Not while a debug build is trying the video player on a real file.)
        !nameAsked && !DemoMode.enabled && !DemoMode.quiet -> FirstRun.Name
        else -> FirstRun.App
    }
    Crossfade(targetState = step, animationSpec = tween(450), label = "first run") { shown ->
        when (shown) {
            FirstRun.Welcome -> WelcomeScreen(
                permissionDenied = denied || DemoMode.screen == "welcome-denied",
                onFindMusic = { askPermission.launch(audioPermission) },
                onOpenSettings = { context.openAppSettings() },
            )
            FirstRun.Name -> {
                // The third screen is "Fix your library": answering the name clears the note
                // that it was offered, so it comes up once more after this, over the Shelf,
                // if there is anything to fix.
                val answered = {
                    if (!DemoMode.enabled) {
                        settings.nameAsked = true
                        settings.fixOffered = false
                    }
                    nameAsked = true
                }
                NameScreen(
                    onDone = { name ->
                        settings.setName(name)
                        answered()
                    },
                    onSkip = answered,
                )
            }
            FirstRun.App -> SpineApp(viewModel)
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
