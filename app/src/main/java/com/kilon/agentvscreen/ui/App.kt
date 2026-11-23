package com.kilon.agentvscreen.ui

import androidx.compose.material.icons.outlined.AddLink
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Setup
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/*
 * 1.4: the app is organised the way miuix apps are, not as the 1.3 home page with new widgets on it.
 *
 *  - Four tabs at the bottom, by what the user came for: 现在 (how it is now, does anything need me), 接入 (connected
 *    agents and remote access), 记录 (what the agent did, booked, watches and learned), 设置 (permissions and screen).
 *  - Every second-level page is a real page on a stack (miuix-nav): its own large title that folds into the bar, swipe
 *    and predictive back. Nothing swaps content inside one page any more.
 *  - Each page's content scrolls BEHIND its glass top bar: the bar blurs the page's own layer, which starts with the
 *    page colour. (beta1 put the content below the bar and blurred an empty layer: the grey block behind the title.)
 *  - Rows are miuix preference rows in cards under small titles. A row says its current value; explanations live on
 *    the Help page, not behind a "?" on every row. Results are said in a snackbar or on the row, never a system toast.
 */

/** Every page of the app. Saved with the back stack, so a page survives the activity being recreated. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Setup : Route
    @Serializable data object Connect : Route
    @Serializable data object NewAgent : Route
    @Serializable data class Agent(val id: String) : Route
    @Serializable data object Remote : Route
    @Serializable data object Tokens : Route
    @Serializable data object Rules : Route
    @Serializable data object Schedules : Route
    @Serializable data object Lessons : Route
    @Serializable data object Sound : Route
    @Serializable data object Log : Route
    @Serializable data object Diag : Route
    @Serializable data object Help : Route
    @Serializable data class Task(val id: Long) : Route
}

/** What a page may ask of the activity. */
interface AppHost {
    fun copy(label: String, text: String)
    fun say(text: String)
    fun grantShizuku()
    fun diagnostics(items: List<Setup.Item>): String
    fun recreateForLanguage()
}

/** Moving between pages. */
@Stable
class Nav(val stack: NavBackStack, private val selectTab: (Int) -> Unit) {
    /** 1.4.0-beta8: 接入 is a tab of its own (rainbow: the way in was too deep); its old route opens the tab. */
    fun go(r: Route) { if (r == Route.Connect) { tab(TAB_CONNECT); return }; if (r !in stack) stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Back to the main tabs, on tab [i]. */
    fun tab(i: Int) { while (stack.size > 1) stack.removeAt(stack.lastIndex); selectTab(i) }
}

/** What several pages read: the setup check, the switch, background trouble; refreshed every 2 s while shown. */
@Stable
/** Setup items that never count as "left to do": accessibility (only the user can turn it on) and the detector (optional, AGPL notice). */
private val NOT_COUNTED = setOf("accessibility", "detector")

class AppState {
    var items by mutableStateOf(emptyList<Setup.Item>())
    var serviceOn by mutableStateOf(false)
    var liveProblem by mutableStateOf<String?>(null)
    /** Bumped after an action so lists read their store again. */
    var tick by mutableIntStateOf(0)
    val allOk get() = items.isNotEmpty() && items.filter { it.key !in NOT_COUNTED }.all { it.ok }
    val left get() = items.count { !it.ok && it.key !in NOT_COUNTED }
    fun bump() { tick++ }
}

const val TAB_CONNECT = 1
const val TAB_RECORDS = 2

val LocalHost = staticCompositionLocalOf<AppHost> { error("no host") }
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }
val LocalApp = staticCompositionLocalOf<AppState> { error("no state") }

/** The whole app: the page stack over the tabs, and one snackbar for short results. */
@Composable
fun AppRoot(host: AppHost, firstPage: Route?, snack: SnackbarHostState, firstTab: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = remember { AppState() }
    LaunchedEffect(Unit) {
        while (true) {
            app.items = withContext(Dispatchers.IO) { runCatching { BackgroundOperation.refreshStatus(); Setup.check(ctx) }.getOrDefault(app.items) }
            app.liveProblem = com.kilon.agentvscreen.host.Liveness.problem()
            app.serviceOn = Prefs.serviceOn(ctx)
            delay(2_000)
        }
    }
    val pager = rememberPagerState(initialPage = firstTab, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val stack = rememberNavBackStack<Route>(Route.Main)
    // 1.4.0-beta11 (rainbow: from the first tab straight to the third or fourth stuttered): animating across several
    // pages composed and drew every page in between. Jump next to the target at once, then slide the last page only.
    val toTab: (Int) -> Unit = { i -> scope.launch {
        val from = pager.currentPage
        if (kotlin.math.abs(i - from) > 1) pager.scrollToPage(if (i > from) i - 1 else i + 1)
        pager.animateScrollToPage(i)
    } }
    val nav = remember(stack) { Nav(stack) { i -> toTab(i) } }
    LaunchedEffect(firstPage) { if (firstPage != null && firstPage != Route.Main) nav.go(firstPage) }
    // 1.4.0-beta11: a notification tapped while the app is open goes to its page (tab, then the page if it has one).
    val asked by MainActivity.pageRequests.collectAsState()
    LaunchedEffect(asked) {
        val name = asked?.second ?: return@LaunchedEffect
        nav.tab(MainActivity.tabOf(name))
        MainActivity.routeOf(name)?.takeIf { it != Route.Connect }?.let { nav.go(it) }
        MainActivity.pageRequests.value = null
    }
    CompositionLocalProvider(LocalHost provides host, LocalNav provides nav, LocalApp provides app) {
        // 1.4.1-beta1 (rainbow: the Headscale "advanced" sheet would not open): miuix overlays (OverlayBottomSheet,
        // OverlayDialog, dropdowns) only show under a Scaffold, which hosts them. The tabs had one, but a pushed page
        // that put its sheet next to its MiuixPage (the remote page) had none, so the sheet was queued and never drawn.
        // One Scaffold at the root hosts every overlay of every page; the pages' own Scaffolds pass theirs up to it.
        Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0), containerColor = Color.Transparent) { _ ->
        Box(Modifier.fillMaxSize()) {
            NavDisplay(backStack = stack, onBack = { nav.back() }) {
                val swipe = NavSwipeDirection.LeftToRight
                entry<Route.Main> { MainTabs(pager) }
                entry<Route.Setup>(swipeDismiss = swipe) { SetupFlowPage() }
                entry<Route.Connect>(swipeDismiss = swipe) { ConnectPage() }
                entry<Route.Remote>(swipeDismiss = swipe) { RemotePage() }
                entry<Route.NewAgent>(swipeDismiss = swipe) { NewAgentPage() }
                entry<Route.Agent>(swipeDismiss = swipe) { r -> AgentPage(r.id) }
                entry<Route.Tokens>(swipeDismiss = swipe) { TokensPage() }
                entry<Route.Rules>(swipeDismiss = swipe) { RulesPage() }
                entry<Route.Schedules>(swipeDismiss = swipe) { SchedulesPage() }
                entry<Route.Lessons>(swipeDismiss = swipe) { LessonsPage() }
                entry<Route.Sound>(swipeDismiss = swipe) { SoundPage() }
                entry<Route.Log>(swipeDismiss = swipe) { LogPage() }
                entry<Route.Diag>(swipeDismiss = swipe) { DiagPage() }
                entry<Route.Help>(swipeDismiss = swipe) { HelpPage() }
                entry<Route.Task>(swipeDismiss = swipe) { r -> TaskPage(r.id) }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp))
        }
        }
    }
}

/** The four tabs, swiped or picked in the floating bar; the bar blurs the tab behind it. */
@Composable
private fun MainTabs(pager: androidx.compose.foundation.pager.PagerState) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (Glass.blurOk) rememberLayerBackdrop { drawRect(surface); drawContent() } else null
    val app = LocalApp.current
    val tabs = listOf(
        Triple(tr("现在", "Now"), MiuixIcons.Home, 0),
        Triple(tr("接入", "Connect"), androidx.compose.material.icons.Icons.Outlined.AddLink, TAB_CONNECT),
        Triple(tr("记录", "Records"), MiuixIcons.Recent, TAB_RECORDS),
        Triple(tr("设置", "Settings"), MiuixIcons.Settings, 3),
    )
    Scaffold(
        bottomBar = {
            FloatingNavigationBar(
                modifier = if (backdrop != null) Modifier.textureBlur(
                    backdrop = backdrop, shape = RoundedCornerShape(50), blurRadius = 25f,
                    colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)))),
                ) else Modifier,
                color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            ) {
                tabs.forEach { (label, icon, i) ->
                    val needs = i == 0 && (pendingAsks().isNotEmpty() || (app.items.isNotEmpty() && !app.allOk))
                    val badge: (@Composable () -> Unit)? = if (needs) { { Dot(Palette.stopped) } } else null
                    FloatingNavigationBarItem(
                        selected = pager.currentPage == i, onClick = { nav.tab(i) },
                        icon = icon, label = label,
                        // A dot on 现在 while something there needs the user.
                        badge = badge,
                    )
                }
            }
        },
    ) { pad ->
        // 1.4.0-beta13 (rainbow: switching tabs still stutters). Two costs met in the frames of a switch: a tab composed
        // for the first time mid-slide (only the neighbours were kept), and every tab's top bar blurring its page live
        // while three pages move under the bar's own blur. All four tabs are now composed once the app has drawn its
        // first page, and a tab's bar blurs only when content is scrolled under it and the pager is still (MiuixPage).
        var keep by remember { mutableIntStateOf(1) }
        val moving = remember(pager) { { pager.isScrollInProgress } }
        LaunchedEffect(Unit) { delay(700); keep = 3 }
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            CompositionLocalProvider(LocalPagerMoving provides moving) {
            HorizontalPager(
                state = pager, beyondViewportPageCount = keep, verticalAlignment = Alignment.Top,
                flingBehavior = PagerDefaults.flingBehavior(state = pager),
            ) { page ->
                val bottom = pad.calculateBottomPadding()
                when (page) {
                    0 -> OverviewTab(bottom)
                    TAB_CONNECT -> ConnectPage(tab = true, bottom = bottom)
                    TAB_RECORDS -> RecordsTab(bottom)
                    else -> SettingsTab(bottom)
                }
            }
            }
        }
    }
}

/** Whether the tabs are sliding (read lazily): pages skip their bar's live blur meanwhile. */
val LocalPagerMoving = androidx.compose.runtime.staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
fun Dot(c: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(c))

/**
 * One page: a glass top bar with a large title that folds as the page scrolls under it, the content in a scrolling
 * column. [back] shows the back arrow (every page but the four tabs). [bottom] keeps the last group clear of the
 * floating bar on a tab.
 */
@Composable
fun MiuixPage(
    title: String,
    back: Boolean = true,
    bottom: Dp = 0.dp,
    subtitle: String = "",
    /** Top-left of the bar when there is no back arrow (the home page's state pill). */
    lead: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    marqueeTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    val surface = MiuixTheme.colorScheme.surface
    val backdrop: LayerBackdrop? = if (Glass.blurOk) rememberLayerBackdrop { drawRect(surface); drawContent() } else null
    val bar = MiuixScrollBehavior()
    val scroll = rememberScrollState()
    val moving = LocalPagerMoving.current
    // 1.4.0-beta13: blur only what is there to blur: content scrolled under the bar, and not while the tabs slide.
    val blurNow by remember(backdrop) { androidx.compose.runtime.derivedStateOf { backdrop != null && scroll.value > 0 && !moving() } }
    Scaffold(
        topBar = {
            Box(if (blurNow && backdrop != null) Modifier.textureBlur(backdrop = backdrop, shape = RectangleShape, blurRadius = 25f,
                colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = surface.copy(0.8f))))) else Modifier) {
                val navigation: @Composable () -> Unit = {
                    if (back) IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 16.dp)) {
                        Icon(MiuixIcons.Back, tr("返回", "Back"))
                    } else lead?.invoke()
                }
                val barColor = if (blurNow) Color.Transparent else surface
                if (marqueeTitle) TaskTitleBar(title, subtitle, bar, barColor, navigation, actions)
                else TopAppBar(title = title, subtitle = subtitle, scrollBehavior = bar,
                    color = barColor, navigationIcon = navigation, actions = actions)
            }
        },
    ) { pad ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            Column(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(bar.nestedScrollConnection).verticalScroll(scroll)
                    .padding(top = pad.calculateTopPadding(), bottom = maxOf(bottom, pad.calculateBottomPadding()) + 24.dp),
            ) { content() }
        }
    }
}

/** Miuix's string-only TopAppBar wraps the expanded title and truncates the collapsed one. Task titles
 * need a composable in both slots, but keep the same scroll state, paddings and navigation. */
@Composable
internal fun TaskTitleBar(
    title: String,
    subtitle: String,
    scrollBehavior: ScrollBehavior,
    color: Color,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val state = scrollBehavior.state
    val collapsed by remember(state) { androidx.compose.runtime.derivedStateOf { state.collapsedFraction >= 0.5f } }
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    Column(Modifier.fillMaxWidth().background(color)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().height(TopAppBarDefaults.CollapsedHeight), verticalAlignment = Alignment.CenterVertically) {
            navigation()
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                if (collapsed) Text(title, modifier = Modifier.scrollIfLong(), maxLines = 1, softWrap = false,
                    fontSize = styles.title3.fontSize, color = colors.onSurface, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        // Keep the large slot measured while collapsed so it can expand again. Only the active title animates.
        Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
            val child = measurable.measure(constraints.copy(minHeight = 0))
            val limit = -child.height.toFloat()
            if (state.heightOffsetLimit != limit) {
                state.heightOffsetLimit = limit
                state.heightOffset = state.heightOffset
            }
            layout(child.width, (child.height + state.heightOffset.toInt()).coerceAtLeast(0)) { child.placeRelative(0, 0) }
        }.padding(horizontal = TopAppBarDefaults.TitlePadding).padding(bottom =
            if (subtitle.isEmpty()) TopAppBarDefaults.LargeTitleBottomPadding else TopAppBarDefaults.SubtitleBottomPadding)) {
            // Keep the same line height when hidden; an empty title still occupies one line.
            Text(if (collapsed) "" else title, modifier = if (collapsed) Modifier else Modifier.scrollIfLong(),
                color = colors.onSurface, fontSize = styles.title1.fontSize, maxLines = 1, softWrap = false)
            if (subtitle.isNotEmpty()) Text(subtitle, color = colors.onSurfaceVariantSummary, style = styles.body2)
        }
    }
}

/** A group on a page: a small title, then a card of rows. Rows bring their own padding ([padded] for free content). */
@Composable
fun Group(title: String? = null, padded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SmallTitle(title) else Spacer(Modifier.height(12.dp))
    Card(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).fillMaxWidth(),
        insideMargin = if (padded) PaddingValues(16.dp) else PaddingValues(0.dp),
    ) {
        if (padded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } else content()
    }
}
