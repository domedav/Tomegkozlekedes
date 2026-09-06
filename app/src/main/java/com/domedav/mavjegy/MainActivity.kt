package com.domedav.mavjegy

import com.domedav.mavjegy.R

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Feed
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import com.domedav.mavjegy.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.webkit.WebView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domedav.mavjegy.data.MavApi
import com.domedav.mavjegy.data.Purchase
import com.domedav.mavjegy.ui.components.LocalSnackbar
import com.domedav.mavjegy.ui.components.SnackbarHost
import com.domedav.mavjegy.ui.components.SnackbarState
import com.domedav.mavjegy.ui.screens.BuyScreen
import com.domedav.mavjegy.ui.screens.LoginScreen
import com.domedav.mavjegy.ui.screens.MavinformScreen
import com.domedav.mavjegy.ui.screens.TicketDetailScreen
import com.domedav.mavjegy.ui.screens.TicketsScreen
import com.domedav.mavjegy.ui.theme.MavJegyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Kötelezően álló képernyő
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        val app = application as MavJegyApp
        val tokenStore = app.tokenStore
        val api = app.api
        setContent {
            val snackbarState = remember { SnackbarState() }
            CompositionLocalProvider(LocalSnackbar provides snackbarState) {
                MavJegyTheme(dynamicColor = true) {
                // Ha mégsem álló a tájolás, szóljon az app, hogy forgatás
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    RotatePrompt()
                    return@MavJegyTheme
                }
                var loggedIn by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    loggedIn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { tokenStore.hasToken() || tokenStore.hasCredentials() }
                            .getOrElse { false }
                    }
                }
                if (loggedIn) {
                    LaunchedEffect(Unit) {
                        if (tokenStore.isDemo()) return@LaunchedEffect
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { api.ensureSession() }.getOrElse { false }
                        }
                        if (!ok && !tokenStore.hasCredentials()) loggedIn = false
                    }
                    val onLogout: () -> Unit = remember {
                        {
                            tokenStore.clear()
                            loggedIn = false
                        }
                    }
                    AppRoot(api, onLogout = onLogout)
                } else {
                    LoginScreen(
                        api = api,
                        onLoggedIn = { loggedIn = true }
                    )
                }
                SnackbarHost()
            }
        }
    }
}

@Composable
fun RotatePrompt() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.ConfirmationNumber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.rotate_prompt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AppRoot(api: MavApi, onLogout: () -> Unit = {}) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(SettingsStore.getLastTab(context)) }
    LaunchedEffect(selectedTab) {
        SettingsStore.setLastTab(context, selectedTab)
    }
    var detailPurchase by remember { mutableStateOf<Purchase?>(null) }
    var ticketsRefreshTrigger by remember { mutableIntStateOf(0) }
    var previousTab by remember { mutableIntStateOf(selectedTab) }
    LaunchedEffect(selectedTab) {
        if (previousTab == 1 && selectedTab == 0) ticketsRefreshTrigger++
        previousTab = selectedTab
        SettingsStore.setLastTab(context, selectedTab)
    }
    // 0 = jobb oldal, 1 = bal oldal – a pill oldalának megőrzése
    var pillSide by remember { mutableIntStateOf(SettingsStore.getPillSide(context)) }
    LaunchedEffect(pillSide) {
        SettingsStore.setPillSide(context, pillSide)
    }

    // WebView példány életben tartása tab-váltáskor (így a session sose vész el)
    val webView = remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { webView.value?.destroy() }
    }

    val doLogout: () -> Unit = remember(onLogout) {
        {
            webView.value?.destroy()
            webView.value = null
            runCatching { java.io.File(context.filesDir, "web_session.json").delete() }
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            onLogout()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = selectedTab,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
            modifier = Modifier.fillMaxSize(),
            label = "rootSwitch"
        ) { tab ->
            when (tab) {
                2 -> MavinformScreen(api = api)
                1 -> BuyScreen(webView)
                else -> TicketsScreen(
                    api = api,
                    refreshTrigger = ticketsRefreshTrigger,
                    onOpenDetail = { detailPurchase = it },
                    onNavigateToBuy = { selectedTab = 1 },
                    onLogout = doLogout
                )
            }
        }

        // M3 Expressive floating toolbar - jobboldali vertikális pill, alulra rögzítve,
        // húzható selection karika - húzás közben folytonos színátmenettel
        val itemSizePx = with(LocalDensity.current) { 58.dp.toPx() }
        val dragOffset = remember { Animatable(0f) } // -1..1 skálán a kettő ikon közt
        val scope = rememberCoroutineScope()

        // A pill oldalának (bal/jobb) vízszintes pozíciója – soha nincs középen,
        // elengedéskor a legközelebbi oldalra tapad (flick-barát: pici eltolás is elég)
        val density = LocalDensity.current
        val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        val edgePaddingPx = with(density) { 12.dp.toPx() }
        var pillWidthPx by remember { mutableStateOf(0f) }
        // 1f = jobb oldal, 0f = bal oldal – ez vezérli a pill folyamatos vízszintes pozícióját
        val sideFraction = remember { Animatable(if (pillSide == 0) 1f else 0f) }
        val travelPx = (screenWidthPx - pillWidthPx - 2 * edgePaddingPx).coerceAtLeast(1f)
        // Auto-hide: 3 mp tétlenség után a pill 60%-a kiúszik a képernyő szélére,
        // koppintásra visszajön. Bármely interakció (tab, oldal, tap, húzás) reseteli.
        var peeked by remember { mutableStateOf(false) }
        val peekAnim = remember { Animatable(0f) } // 0 = bent, 1 = 60% kint
        // Húzás/koppintás közben az időzítő szünetel — különben húzás
        // közben úszna ki a pill a kéz alól
        var pillBusy by remember { mutableStateOf(false) }
        LaunchedEffect(peeked, selectedTab, pillSide, pillBusy) {
            if (!peeked && !pillBusy) {
                delay(3000)
                peeked = true
            }
        }
        LaunchedEffect(peeked) {
            peekAnim.animateTo(
                if (peeked) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy)
            )
        }
        val hidePx = pillWidthPx * 0.6f * peekAnim.value
        val hideDir = if (sideFraction.value > 0.5f) 1f else -1f
        val currentX = edgePaddingPx + sideFraction.value * travelPx + hideDir * hidePx

        LaunchedEffect(selectedTab) {
            peeked = false
            dragOffset.animateTo(
                selectedTab * itemSizePx,
                spring(dampingRatio = Spring.DampingRatioLowBouncy)
            )
        }

        // A selection karika MINDIG primary; a HÁROM IKON színe a lerp alatt
        // folyamatosan cserélődik: amelyik ikon alatt épp a karika áll, az világosodik
        val dragProgress = (dragOffset.value / itemSizePx).coerceIn(0f, 2f)
        val circleColor = MaterialTheme.colorScheme.primary
        val pillColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val iconTints = (0..2).map { i ->
            val diff = kotlin.math.abs(dragProgress - i.toFloat()).coerceIn(0f, 1f)
            lerp(MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.onSurfaceVariant, diff)
        }

        if (detailPurchase == null) Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
                .offset { IntOffset(currentX.roundToInt(), 0) }
                .onSizeChanged { pillWidthPx = it.width.toFloat() }
                .shadow(6.dp, CircleShape)
                .pointerInput(pillWidthPx) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial)
                        // Bármi interakció indul (tap, oldal- vagy selection-húzás):
                        // pill azonnal vissza, timer szünetel a gesztus végéig
                        peeked = false
                        pillBusy = true
                        var horizontal = 0f
                        var vertical = 0f
                        var decided = false
                        var isHorizontal = false
                        var done = false
                        val touchSlop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.first()
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            horizontal += dx
                            vertical += dy
                            if (!decided) {
                                if (abs(horizontal) > touchSlop || abs(vertical) > touchSlop) {
                                    decided = true
                                    isHorizontal = abs(horizontal) > abs(vertical)
                                    if (!isHorizontal) {
                                        // függőleges húzás -> a belső Column kezeli
                                        done = true
                                    }
                                }
                            }
                            if (decided && isHorizontal) {
                                change.consume()
                                val newFraction = (sideFraction.value + dx / travelPx)
                                    .coerceIn(0f, 1f)
                                scope.launch { sideFraction.snapTo(newFraction) }
                            }
                        } while (event.changes.any { it.pressed } && !done)
                        // Gesztus vége (tapot nem fogyasztottuk, az ikonoké marad)
                        pillBusy = false
                        if (decided && isHorizontal) {
                            // Elengedéskor a legközelebbi oldalra tapad – sosem középen.
                            // Flick-barát: pici elhúzás az ellenkező irányba is oldalt vált.
                            val currentSideIsRight = pillSide == 0
                            val flickPx = travelPx * 0.12f
                            val goOther = (currentSideIsRight && horizontal < -flickPx) ||
                                (!currentSideIsRight && horizontal > flickPx)
                            val targetFraction = if (goOther) {
                                if (currentSideIsRight) 0f else 1f
                            } else {
                                if (sideFraction.value > 0.5f) 1f else 0f
                            }
                            pillSide = if (targetFraction == 1f) 0 else 1
                            scope.launch {
                                sideFraction.animateTo(
                                    targetFraction,
                                    spring(dampingRatio = Spring.DampingRatioLowBouncy)
                                )
                            }
                        }
                    }
                },
            shape = CircleShape,
            color = pillColor,
            tonalElevation = 3.dp
        ) {
            Box(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
            ) {
                // húzható selection karika (draw over, iPhone-szerű)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(circleColor)
                )
                Column(
                    modifier = Modifier.pointerInput(itemSizePx) {
                        detectDragGestures(
                            onDragStart = { pillBusy = true },
                            onDragEnd = {
                                pillBusy = false
                                val tabCount = 3
                                val nearestTab = (dragOffset.value / itemSizePx).roundToInt()
                                    .coerceIn(0, tabCount - 1)
                                val targetOffset = nearestTab * itemSizePx
                                scope.launch {
                                    dragOffset.animateTo(
                                        targetOffset,
                                        spring(dampingRatio = Spring.DampingRatioLowBouncy)
                                    )
                                    selectedTab = nearestTab
                                }
                            },
                            onDragCancel = { pillBusy = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxOffset = 2 * itemSizePx
                            val new = (dragOffset.value + dragAmount.y)
                                .coerceIn(0f, maxOffset)
                            scope.launch { dragOffset.snapTo(new) }
                        }
                    }
                ) {
                    listOf(
                        Icons.Rounded.ConfirmationNumber to stringResource(R.string.title_tickets),
                        Icons.Rounded.ShoppingCart to stringResource(R.string.title_buy),
                        Icons.Rounded.Feed to stringResource(R.string.title_news)
                    ).forEachIndexed { index, (icon, label) ->
                        val selected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .clickable { selectedTab = index },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = label,
                                tint = iconTints[index],
                                modifier = Modifier.size(26.dp)
                            )
            }
        }
    }
}
        }
        if (detailPurchase != null) {
            val purchase = detailPurchase!!
            androidx.activity.compose.BackHandler { detailPurchase = null }
            TicketDetailScreen(api = api, purchase = purchase, onBack = { detailPurchase = null })
        }
    }
}
}
