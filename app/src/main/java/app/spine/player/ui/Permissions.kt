package app.spine.player.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.spine.player.data.audioPermission
import app.spine.player.data.hasAudioPermission
import app.spine.player.ui.components.BackLink
import app.spine.player.ui.components.BlockButton
import app.spine.player.ui.theme.BarlowCondensed
import app.spine.player.ui.theme.Hairline
import app.spine.player.ui.theme.Paper
import app.spine.player.ui.theme.PaperMuted
import app.spine.player.ui.theme.PaperText
import app.spine.player.ui.theme.Play

// What Spine+ may do on the phone: one place that says what each permission is for and
// whether it is given, and one check that asks for what is missing before a download.

/** Whether Android will show this app's notifications, such as a download's progress. */
fun Context.canNotify(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

/**
 * Whether Android lets this app keep working with the screen off. Android calls it
 * "unrestricted battery use"; without it a long download can be paused or stopped once
 * the app is out of sight. It has no effect on how fast a download is.
 */
fun Context.runsInBackground(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

private fun Context.askToRunInBackground(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))

/**
 * Whether Android lets this app hand a newer version of itself to the installer. Android
 * asks the first time an update is installed, so this is never asked for ahead of time.
 */
fun Context.canInstallUpdates(): Boolean = packageManager.canRequestPackageInstalls()

private fun Context.askToInstallUpdates(): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))

/** Android's page for which files and links this app opens by itself. */
private fun Context.openByDefaultSettings(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName"))
    } else {
        appSettings()
    }

private fun Context.appSettings(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/** "All allowed", or how many are not, for the row in Settings. */
fun permissionsSummary(context: Context): String {
    val missing = listOf(context.hasAudioPermission(), context.canNotify(), context.runsInBackground()).count { !it }
    return if (missing == 0) "All allowed" else "$missing not allowed"
}

private const val PREFS = "downloads"
private const val KEY_ASKED_BACKGROUND = "asked_background"

/**
 * Asks for whatever a download needs and does not have, then goes ahead. Use the returned
 * function around the code that starts a download.
 *
 * It asks, in turn and only for what is missing: to see the phone's music (or the new
 * song would not show on the Shelf), to show notifications (the download's progress), and,
 * once only, to keep running in the background. Whatever the answers, the download starts:
 * none of the three stops a download from working while the app is open.
 */
@Composable
fun rememberDownloadGate(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // What is waiting to start, and what has been asked for it so far.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var asked by remember { mutableStateOf(emptySet<String>()) }
    // Each answer comes back through here and the next question, if any, is asked.
    val advance = remember { mutableStateOf<() -> Unit>({}) }

    val askMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance.value() }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance.value() }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { advance.value() }

    advance.value = {
        val then = pending
        if (then != null) {
            when {
                "music" !in asked && !context.hasAudioPermission() -> {
                    asked = asked + "music"
                    askMusic.launch(audioPermission)
                }
                "notify" !in asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.canNotify() -> {
                    asked = asked + "notify"
                    askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                "background" !in asked && !context.runsInBackground() && !prefs.getBoolean(KEY_ASKED_BACKGROUND, false) -> {
                    asked = asked + "background"
                    prefs.edit { putBoolean(KEY_ASKED_BACKGROUND, true) }
                    // Some phones have no such screen; then there is nothing to ask.
                    runCatching { askBackground.launch(context.askToRunInBackground()) }.onFailure { advance.value() }
                }
                else -> {
                    pending = null
                    asked = emptySet()
                    then()
                }
            }
        }
    }
    return remember {
        { then ->
            pending = then
            advance.value()
        }
    }
}

/**
 * Settings, Permissions: everything Spine+ may do on the phone, what each is for and
 * whether it is given. A tap asks for one that is missing, or opens Android's own page
 * for the app where Android no longer lets the app ask.
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Counted up whenever the answers may have changed, so the rows are read again.
    var looked by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        looked++
        onPauseOrDispose { }
    }
    val music = remember(looked) { context.hasAudioPermission() }
    val notify = remember(looked) { context.canNotify() }
    val background = remember(looked) { context.runsInBackground() }
    val installs = remember(looked) { context.canInstallUpdates() }

    val openAndroidPage: () -> Unit = { runCatching { context.startActivity(context.appSettings()) } }
    // If Android does not show its question (it stops after two refusals), its page is opened instead.
    val askMusic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        looked++
        if (!allowed) openAndroidPage()
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        looked++
        if (!allowed) openAndroidPage()
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { looked++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackLink(text = "Settings", onClick = onBack, color = PaperText)
        Text(
            text = "PERMISSIONS",
            modifier = Modifier.padding(top = 8.dp),
            color = PaperText,
            fontFamily = BarlowCondensed,
            fontSize = 46.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "What Spine+ may do on this phone, and what each is for. Tap one to change it.",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            color = PaperMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )

        PermissionRow(
            name = "Music on this phone",
            purpose = "To show and play your songs. Without it the Shelf is empty.",
            allowed = music,
            onClick = { if (music) openAndroidPage() else askMusic.launch(audioPermission) },
        )
        PermissionRow(
            name = "Notifications",
            purpose = "To show what is playing, and a download's progress while the app is closed.",
            allowed = notify,
            onClick = {
                if (!notify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAndroidPage()
                }
            },
        )
        PermissionRow(
            name = "Keep running in the background",
            purpose = "Lets a long download finish with the screen off. Android calls this unrestricted " +
                "battery use. It does not make downloads faster.",
            allowed = background,
            onClick = {
                if (background) {
                    openAndroidPage()
                } else {
                    runCatching { askBackground.launch(context.askToRunInBackground()) }.onFailure { openAndroidPage() }
                }
            },
        )
        PermissionRow(
            name = "Install updates",
            purpose = "Lets Spine+ pass a newer version of itself to Android's installer, which still " +
                "asks you each time. Android asks for this the first time you update.",
            // Not having it yet is no fault: it is asked for when it is first needed.
            allowed = true,
            state = if (installs) "Allowed" else "Asks on first update",
            onClick = { runCatching { context.startActivity(context.askToInstallUpdates()) }.onFailure { openAndroidPage() } },
        )
        PermissionRow(
            name = "Default player",
            purpose = "Android offers Spine+ when you open a song or a video from another app. Choose " +
                "Always there, or set it on Android's page, to have Spine+ open them every time.",
            allowed = true,
            state = "Android's page",
            onClick = {
                runCatching { context.startActivity(context.openByDefaultSettings()) }.onFailure { openAndroidPage() }
            },
        )
        PermissionRow(
            name = "Internet",
            purpose = "Used only to find and download music and to look for a newer version of the app. " +
                "Android gives this to every app without asking.",
            allowed = true,
            state = "Always on",
            onClick = null,
        )

        Row(modifier = Modifier.padding(top = 20.dp)) {
            BlockButton(text = "Open Android's page for Spine+", onClick = openAndroidPage, onInk = false)
        }
    }
}

/** One permission: its name, what it is for, and whether it is given. */
@Composable
private fun PermissionRow(
    name: String,
    purpose: String,
    allowed: Boolean,
    onClick: (() -> Unit)?,
    state: String = if (allowed) "Allowed" else "Not allowed",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .drawBehind {
                val thickness = 1.dp.toPx()
                drawRect(Hairline, Offset(0f, size.height - thickness), Size(size.width, thickness))
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = name, color = PaperText, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(text = purpose, color = PaperMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Text(
            text = state,
            color = if (allowed) PaperMuted else Play,
            fontSize = 15.sp,
            fontWeight = if (allowed) FontWeight.Normal else FontWeight.SemiBold,
        )
    }
}
