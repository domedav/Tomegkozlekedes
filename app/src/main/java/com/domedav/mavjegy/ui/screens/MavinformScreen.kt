package com.domedav.mavjegy.ui.screens

import com.domedav.mavjegy.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domedav.mavjegy.data.MavApi
import com.domedav.mavjegy.data.MavinformItem
import com.domedav.mavjegy.ui.components.ExpressiveLoader
import com.domedav.mavjegy.ui.components.LocalSnackbar
import com.domedav.mavjegy.util.NewsPinPrefs
import com.domedav.mavjegy.util.friendlyError
import com.domedav.mavjegy.util.isOnline
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MavinformScreen(api: MavApi) {
    var loading by remember { mutableStateOf(false) }
    var allItems by remember { mutableStateOf<List<MavinformItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }

    // Pin state
    var pinnedLinks by remember { mutableStateOf(setOf<String>()) }
    var pinTick by remember { mutableIntStateOf(0) }

    // Keresés
    var query by remember { mutableStateOf("") }
    // Aktív keresésnél végiglapozunk; true, ha minden oldalt átnéztünk (vagy hiba állította le)
    var searchComplete by remember { mutableStateOf(false) }

    // Detail popup
    var selectedItem by remember { mutableStateOf<MavinformItem?>(null) }
    var detailDescription by remember { mutableStateOf("") }
    var detailLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val rotation by animateFloatAsState(
        targetValue = if (loading) 360f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "refreshSpin"
    )

    val snackbar = LocalSnackbar.current
    LaunchedEffect(error) {
        error?.let {
            snackbar.show(context.getString(friendlyError(it)), isError = true)
            error = null
        }
    }

    // Pin state betöltése + lejárt takarítás
    LaunchedEffect(pinTick) {
        NewsPinPrefs.cleanExpired(context)
        pinnedLinks = NewsPinPrefs.getPinnedLinks(context)
    }

    // Lista betöltése — minden kilépési ág visszaállítja a flageket,
    // különben a lapozó loader örökké pörögne újratöltés nélkül
    suspend fun loadPage(page: Int) {
        if (loading || loadingMore) return
        if (!isOnline(context)) {
            error = context.getString(R.string.err_no_internet)
            if (query.trim().isNotEmpty()) searchComplete = true
            return
        }
        if (page == 0) loading = true else loadingMore = true
        var pageOk = false
        try {
            val newItems = api.fetchMavinformList(page)
            if (page == 0) {
                allItems = newItems
            } else {
                // duplikátum-védelem link alapján
                val known = allItems.map { it.link }.toSet()
                allItems = allItems + newItems.filter { it.link !in known }
            }
            hasMore = newItems.isNotEmpty()
            currentPage = page
            pageOk = newItems.isNotEmpty()
        } catch (e: Exception) {
            if (allItems.isEmpty()) error = e.message ?: e.javaClass.simpleName
            // Aktív keresésnél a sikertelen oldal véget vet a láncnak (nincs retry-loop)
            if (query.trim().isNotEmpty()) searchComplete = true
        } finally {
            loading = false
            loadingMore = false
        }
        // Aktív keresésnél a háttérben végiglapozunk, hogy a még be nem
        // töltött oldalakon is keressen. Csak sikeres oldal után láncolunk,
        // hiba esetén nem retry-loopolunk.
        if (pageOk && hasMore && query.trim().isNotEmpty()) {
            scope.launch { loadPage(currentPage + 1) }
        } else if (query.trim().isNotEmpty()) {
            searchComplete = true
        }
    }

    fun refresh() {
        scope.launch {
            allItems = emptyList()
            currentPage = 0
            hasMore = true
            loadingMore = false
            searchComplete = false
            loadPage(0)
        }
    }

    // Keresés indulásakor/módosításakor: ha van még be nem töltött oldal,
    // elindítjuk a háttér-láncot (a loadPage vége láncol tovább)
    LaunchedEffect(query) {
        searchComplete = false
        if (query.trim().isNotEmpty() && hasMore && !loading && !loadingMore) {
            scope.launch { loadPage(currentPage + 1) }
        }
    }

    // Lapozás: csak ha van betöltött item ÉS a user tényleg legörgetett a végére.
    // Üres listánál és képernyőre kiférő listánál nem lő magától.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            val userScrolled = listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
            !loading && !loadingMore && hasMore &&
                allItems.isNotEmpty() && total > 0 &&
                query.isBlank() &&
                userScrolled && lastVisible >= total - 2
        }
    }
    // A töltést scope-ban indítjuk, NE az effect korutinjában:
    // különben a loadingMore=true flagváltás újraindítaná az effectet
    // és cancelölné a még futó hálózati kérést (loader-villogás, megakadás)
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            scope.launch { loadPage(currentPage + 1) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Rendezés: pinelt elöl, aztán eredeti sorrend
    val sortedItems = remember(allItems, pinnedLinks) {
        val pinned = allItems.filter { it.link in pinnedLinks }
        val unpinned = allItems.filter { it.link !in pinnedLinks }
        pinned + unpinned
    }

    // Szűrés cím + kategória alapján (leírás csak kattintásra töltődik, arra nem szűrünk)
    val filteredItems = remember(sortedItems, query) {
        val q = query.trim()
        if (q.isBlank()) sortedItems
        else sortedItems.filter {
            it.title.contains(q, ignoreCase = true) ||
                context.getString(categoryLabelRes(it.category)).contains(q, ignoreCase = true)
        }
    }

    // --- LISTA OLDAL ---
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_news),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(loading) {
                            detectTapGestures(
                                onTap = { if (!loading) refresh() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        modifier = Modifier.rotate(rotation)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            AnimatedVisibility(loading && allItems.isEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.news_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.btn_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            )
            if (!loading && filteredItems.isEmpty() && error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.body_no_tickets
                            else R.string.news_no_results
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        top = 20.dp,
                        end = 20.dp,
                        bottom = 96.dp + WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(filteredItems, key = { it.link }) { item ->
                        val isPinned = item.link in pinnedLinks
                        MavinformCard(
                            item = item,
                            isPinned = isPinned,
                            onClick = {
                                selectedItem = item
                                detailDescription = ""
                                detailLoading = true
                                scope.launch {
                                    try {
                                        detailDescription = api.fetchMavinformDetail(item.link)
                                    } catch (_: Exception) {}
                                    detailLoading = false
                                }
                            },
                            onLongClick = {
                                NewsPinPrefs.togglePin(context, item.link)
                                pinTick++
                            }
                        )
                    }
                    if (query.trim().isNotEmpty()) {
                        // Keresés lábléc: töltünk még, vagy ez minden
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!searchComplete || loadingMore) {
                                    Text(
                                        text = stringResource(R.string.news_searching_more),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LinearProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.news_search_done, filteredItems.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (loadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LinearProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DETAIL POPUP ---
    if (selectedItem != null) {
        Dialog(onDismissRequest = { selectedItem = null }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = selectedItem!!.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = categoryIcon(selectedItem!!.category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(categoryLabelRes(selectedItem!!.category)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (detailLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ExpressiveLoader(
                                color = MaterialTheme.colorScheme.primary,
                                size = 48.dp,
                                strokeWidth = 4.5.dp
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = detailDescription,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.25f,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- KÁRTYA ---

@Composable
private fun categoryColor(category: String): Color = when (category) {
    "vonat" -> MaterialTheme.colorScheme.tertiary
    "busz" -> MaterialTheme.colorScheme.secondary
    "helyi_busz" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun categoryIconTint(category: String): Color = when (category) {
    "vonat" -> MaterialTheme.colorScheme.onTertiary
    "busz" -> MaterialTheme.colorScheme.onSecondary
    "helyi_busz" -> MaterialTheme.colorScheme.onPrimary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "vonat" -> Icons.Rounded.Train
    "busz" -> Icons.Rounded.DirectionsBus
    "helyi_busz" -> Icons.Rounded.DirectionsBus
    else -> Icons.Rounded.Info
}

private fun categoryLabelRes(category: String): Int = when (category) {
    "vonat" -> R.string.news_cat_train
    "busz" -> R.string.news_cat_bus
    "helyi_busz" -> R.string.news_cat_local_bus
    else -> R.string.news_cat_other
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MavinformCard(
    item: MavinformItem,
    isPinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isPinned) MaterialTheme.colorScheme.primaryContainer
                         else MaterialTheme.colorScheme.surfaceContainerHigh
    val iconTint = if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer
                   else categoryIconTint(item.category)

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(min = 84.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isPinned) MaterialTheme.colorScheme.primary
                                    else categoryColor(item.category),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon(item.category),
                        contentDescription = null,
                        tint = if (isPinned) MaterialTheme.colorScheme.onPrimary
                               else iconTint,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        minLines = 1,
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(categoryLabelRes(item.category)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // Pin badge - jobb felső sarok
        if (isPinned) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
