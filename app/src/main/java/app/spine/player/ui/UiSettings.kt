package app.spine.player.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import app.spine.player.ui.theme.Palette
import app.spine.player.ui.theme.PlayColour
import app.spine.player.ui.theme.ShelfColour
import app.spine.player.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the user wants the app to look: where it opens, its colours, and which extras Now playing shows. */
class UiSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _startTab = MutableStateFlow(
        ShelfTab.entries.firstOrNull { it.name == prefs.getString(KEY_START_TAB, null) } ?: ShelfTab.Albums,
    )

    /** The Shelf tab the app opens on. */
    val startTab: StateFlow<ShelfTab> = _startTab.asStateFlow()

    private val _tabOrder = MutableStateFlow(savedTabOrder())

    /** The Shelf's tabs in the order the user has put them, by holding one and dragging it. */
    val tabOrder: StateFlow<List<ShelfTab>> = _tabOrder.asStateFlow()

    private fun savedTabOrder(): List<ShelfTab> {
        val saved = prefs.getString(KEY_TAB_ORDER, null).orEmpty().split(',')
            .mapNotNull { name -> ShelfTab.entries.firstOrNull { it.name == name } }.distinct()
        if (saved.isEmpty()) return ShelfTab.DefaultOrder
        // A tab that a later version adds goes where it stands by default.
        val order = saved.toMutableList()
        for ((place, tab) in ShelfTab.DefaultOrder.withIndex()) {
            if (tab !in order) order.add(place.coerceAtMost(order.size), tab)
        }
        return order
    }

    fun setTabOrder(order: List<ShelfTab>) {
        _tabOrder.value = order
        // An order tried out while taking screenshots is not the user's choice.
        if (!DemoMode.enabled) prefs.edit { putString(KEY_TAB_ORDER, order.joinToString(",") { it.name }) }
    }

    private val _showEqualizer = MutableStateFlow(prefs.getBoolean(KEY_SHOW_EQ, true))

    /** Whether Now playing has the Equalizer link. It is always reachable from Settings. */
    val showEqualizer: StateFlow<Boolean> = _showEqualizer.asStateFlow()

    private val _showSleep = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SLEEP, true))

    /** Whether Now playing has the Sleep link. It is always reachable from Settings. */
    val showSleep: StateFlow<Boolean> = _showSleep.asStateFlow()

    private val _showLyrics = MutableStateFlow(prefs.getBoolean(KEY_SHOW_LYRICS, true))

    /** Whether Now playing has the Lyrics link. */
    val showLyrics: StateFlow<Boolean> = _showLyrics.asStateFlow()

    fun setShowLyrics(show: Boolean) {
        _showLyrics.value = show
        prefs.edit { putBoolean(KEY_SHOW_LYRICS, show) }
    }

    /** Whether "Fix your library" has been offered after the first scan. It is offered once. */
    var fixOffered: Boolean
        get() = prefs.getBoolean(KEY_FIX_OFFERED, false)
        set(value) = prefs.edit { putBoolean(KEY_FIX_OFFERED, value) }

    private val _vibration = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION, true))

    /** Whether the app vibrates under the finger: ticks for taps, drags, switches and so on. */
    val vibration: StateFlow<Boolean> = _vibration.asStateFlow()

    private val _animations = MutableStateFlow(DemoMode.animations ?: prefs.getBoolean(KEY_ANIMATIONS, true))

    /** Whether things move into place: the opening, screens sliding, colours rising. Off shows each change at once. */
    val animations: StateFlow<Boolean> = _animations.asStateFlow()

    fun setAnimations(on: Boolean) {
        _animations.value = on
        if (!DemoMode.enabled) prefs.edit { putBoolean(KEY_ANIMATIONS, on) }
    }

    fun setVibration(on: Boolean) {
        _vibration.value = on
        prefs.edit { putBoolean(KEY_VIBRATION, on) }
    }

    private val _playColour = MutableStateFlow(
        DemoMode.playColour ?: PlayColour.entries.firstOrNull { it.name == prefs.getString(KEY_PLAY_COLOUR, null) }
            ?: PlayColour.Blue,
    )

    /** The colour that rises as a song plays. */
    val playColour: StateFlow<PlayColour> = _playColour.asStateFlow()

    private val _shelfColour = MutableStateFlow(
        DemoMode.shelfColour ?: ShelfColour.entries.firstOrNull { it.name == prefs.getString(KEY_SHELF_COLOUR, null) }
            ?: ShelfColour.Ink,
    )

    /** The colour of albums, songs and the dark screens. */
    val shelfColour: StateFlow<ShelfColour> = _shelfColour.asStateFlow()

    private val _theme = MutableStateFlow(
        ThemeChoice.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeChoice.Light,
    )

    /** Light, dark, or whichever the phone itself is set to. */
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        if (!DemoMode.enabled) prefs.edit { putString(KEY_THEME, choice.name) }
    }

    private val _videoKeepsPlaying = MutableStateFlow(prefs.getBoolean(KEY_VIDEO_KEEPS_PLAYING, true))

    /** Whether a video's sound carries on, like a song, when its screen is left. */
    val videoKeepsPlaying: StateFlow<Boolean> = _videoKeepsPlaying.asStateFlow()

    fun setVideoKeepsPlaying(on: Boolean) {
        _videoKeepsPlaying.value = on
        prefs.edit { putBoolean(KEY_VIDEO_KEEPS_PLAYING, on) }
    }

    private val _name = MutableStateFlow(DemoMode.name ?: prefs.getString(KEY_NAME, null).orEmpty())

    /** What the user asked to be called, or empty. It heads the Shelf and the stats. */
    val name: StateFlow<String> = _name.asStateFlow()

    fun setName(name: String) {
        // Short enough to head a screen, and one line.
        val tidy = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME)
        _name.value = tidy
        if (!DemoMode.enabled) prefs.edit { putString(KEY_NAME, tidy) }
    }

    /** Whether the name has been asked for. It is asked once, after the first screen. */
    var nameAsked: Boolean
        get() = prefs.getBoolean(KEY_NAME_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NAME_ASKED, value) }

    init {
        // The saved colours are showing before the first screen is drawn.
        val phoneIsDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = DemoMode.dark ?: when (_theme.value) {
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
            ThemeChoice.Phone -> phoneIsDark
        }
        Palette.show(_playColour.value, _shelfColour.value, dark)
    }

    fun setPlayColour(colour: PlayColour) {
        _playColour.value = colour
        // A colour tried out while taking screenshots is not the user's choice.
        if (!DemoMode.enabled) prefs.edit { putString(KEY_PLAY_COLOUR, colour.name) }
    }

    fun setShelfColour(colour: ShelfColour) {
        _shelfColour.value = colour
        if (!DemoMode.enabled) prefs.edit { putString(KEY_SHELF_COLOUR, colour.name) }
    }

    fun setStartTab(tab: ShelfTab) {
        _startTab.value = tab
        prefs.edit { putString(KEY_START_TAB, tab.name) }
    }

    fun setShowEqualizer(show: Boolean) {
        _showEqualizer.value = show
        prefs.edit { putBoolean(KEY_SHOW_EQ, show) }
    }

    fun setShowSleep(show: Boolean) {
        _showSleep.value = show
        prefs.edit { putBoolean(KEY_SHOW_SLEEP, show) }
    }

    companion object {
        /** The longest name kept: enough for a first name and a second, short enough to head a screen. */
        const val MAX_NAME = 24

        private const val KEY_THEME = "theme"
        private const val KEY_VIDEO_KEEPS_PLAYING = "video_keeps_playing"
        private const val KEY_NAME = "name"
        private const val KEY_NAME_ASKED = "name_asked"
        private const val KEY_START_TAB = "start_tab"
        // "_2": the default order changed on 2026-11-16 (Downloader first), and an order
        // saved before that would have hidden the change.
        private const val KEY_TAB_ORDER = "tab_order_2"
        private const val KEY_SHOW_EQ = "show_equalizer"
        private const val KEY_SHOW_SLEEP = "show_sleep"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_ANIMATIONS = "animations"
        private const val KEY_SHOW_LYRICS = "show_lyrics"
        private const val KEY_FIX_OFFERED = "fix_offered"
        private const val KEY_PLAY_COLOUR = "play_colour"
        private const val KEY_SHELF_COLOUR = "shelf_colour"

        @Volatile
        private var instance: UiSettings? = null

        fun get(context: Context): UiSettings =
            instance ?: synchronized(this) {
                instance ?: UiSettings(context.applicationContext).also { instance = it }
            }
    }
}
