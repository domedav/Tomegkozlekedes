package com.domedav.mavjegy.ui.screens

import com.domedav.mavjegy.R

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domedav.mavjegy.data.MavApi
import com.domedav.mavjegy.data.Purchase
import com.domedav.mavjegy.data.TicketCache
import com.domedav.mavjegy.data.isPassTicket
import com.domedav.mavjegy.data.isValidTicket
import com.domedav.mavjegy.ui.components.shimmerPlaceholder
import com.domedav.mavjegy.util.friendlyError
import com.domedav.mavjegy.util.isOnline
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun String?.isRealName(): Boolean = !isNullOrBlank() && this != "null"

@Composable
private fun ValiditySubtitle(purchase: Purchase, isPass: Boolean) {
    val now = LocalDateTime.now()
    val to = parseIso(purchase.validTo)
    val notYet = parseIso(purchase.validFrom)?.isAfter(now) == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (notYet) {
            // Még nem érvényes — jegyre és bérletre is
            Text(
                text = stringResource(R.string.not_yet_valid),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        } else if (isPass) {
            // Bérlet: hátralévő napok
            if (to != null && !to.isBefore(now)) {
                val days = ChronoUnit.DAYS.between(now.toLocalDate(), to.toLocalDate().plusDays(1)).coerceAtLeast(0)
                Icon(
                    Icons.Rounded.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    stringResource(R.string.fmt_days, days),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        } else {
            // Jegy: melyik napon/időpontban érvényes
            purchase.validFrom?.takeIf { it.isNotBlank() }?.let { vf ->
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    formatDate(vf, stringResource(R.string.dash_fallback)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
    }
}

private fun parseIso(iso: String?): LocalDateTime? {
    if (iso.isNullOrBlank()) return null
    // PAPI: 7 tizedes + Z (pl. 2026-09-09T22:00:00.0000000Z) -> Instant kezeli.
    runCatching {
        return java.time.Instant.parse(iso.trim()).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    for (fmt in isoDateTimeFormatters) {
        try {
            val parsed = fmt.parse(iso)
            return try {
                OffsetDateTime.from(parsed)
                    .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            } catch (_: Exception) {
                try {
                    LocalDateTime.from(parsed)
                } catch (_: Exception) {
                    LocalDate.from(parsed).atStartOfDay(ZoneId.systemDefault()).toLocalDateTime()
                }
            }
        } catch (_: Exception) {}
    }
    return null
}

private val huDateTime = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.forLanguageTag("hu"))

private val isoDateTimeFormatters = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
)

internal fun formatDate(iso: String?, fallback: String): String {
    if (iso.isNullOrBlank()) return fallback
    parseIso(iso)?.let { return huDateTime.format(it) }
    return try {
        var formatted: String? = null
        for (fmt in isoDateTimeFormatters) {
            try {
                val parsed = fmt.parse(iso)
                val temporal = try {
                    OffsetDateTime.from(parsed).toInstant().atZone(ZoneId.systemDefault())
                } catch (_: Exception) {
                    try {
                        LocalDateTime.from(parsed).atZone(ZoneId.systemDefault())
                    } catch (_: Exception) {
                        LocalDate.from(parsed).atStartOfDay(ZoneId.systemDefault())
                    }
                }
                formatted = huDateTime.format(temporal)
                break
            } catch (_: Exception) {}
        }
        formatted ?: fallback
    } catch (_: Exception) { fallback }
}

private const val CACHE_FILE = "purchases_cache.json"

private fun readCache(context: Context): List<Purchase> = try {
    val raw = context.getFileStreamPath(CACHE_FILE).takeIf { it.exists() }
        ?.bufferedReader()?.use { it.readText() } ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        try {
            Purchase(
                id = o.getString("id"),
                validFrom = o.optString("validFrom", "").ifEmpty { null },
                validTo = o.optString("validTo", "").ifEmpty { null },
                startStation = o.optString("startStation", "").ifEmpty { null },
                endStation = o.optString("endStation", "").ifEmpty { null },
                status = o.optString("status", ""),
                takenOver = o.optBoolean("takenOver", false),
                amount = o.optDouble("amount", 0.0),
                currency = o.optString("currency", "HUF"),
                name = o.optString("name", "").ifEmpty { null },
                passHolderId = o.optString("passHolderId", "").ifEmpty { null }
            )
        } catch (_: Exception) { null }
    }
} catch (_: Exception) { emptyList() }

private fun writeCache(context: Context, purchases: List<Purchase>) = try {
    val arr = JSONArray()
    purchases.forEach { p ->
        arr.put(
            org.json.JSONObject().apply {
                put("id", p.id)
                put("validFrom", p.validFrom ?: "")
                put("validTo", p.validTo ?: "")
                put("startStation", p.startStation ?: "")
                put("endStation", p.endStation ?: "")
                put("status", p.status)
                put("takenOver", p.takenOver)
                put("amount", p.amount)
                put("currency", p.currency)
                put("name", p.name ?: "")
                put("passHolderId", p.passHolderId ?: "")
            }
        )
    }
    val target = java.io.File(context.filesDir, CACHE_FILE)
    val tmp = java.io.File(context.filesDir, CACHE_FILE + ".tmp")
    tmp.writeText(arr.toString())
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }
    Unit
} catch (_: Exception) {}

@Composable
fun TicketsScreen(
    api: MavApi,
    refreshTrigger: Int = 0,
    onOpenDetail: (Purchase) -> Unit = {},
    onNavigateToBuy: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    var loading by remember { mutableStateOf(false) }
    var purchases by remember { mutableStateOf<List<Purchase>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var includeExpired by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Lejárt jegyek: nem létezőnek vesszük – listából, cache-ből is eltávolítjuk
    fun keepAlive(list: List<Purchase>, keepExpired: Boolean = false): List<Purchase> {
        val now = LocalDateTime.now()
        return list.filter { p ->
            val statusOk = p.isValidTicket
            val to = parseIso(p.validTo)
            val expired = to != null && to.isBefore(now)
            val notYet = parseIso(p.validFrom)?.isAfter(now) == true
            if (keepExpired) statusOk || expired || notYet else (statusOk && !expired) || notYet
        }
    }

    suspend fun purgeRemoved(context: Context, oldList: List<Purchase>, kept: List<Purchase>) {
        val keptIds = kept.map { it.id }.toSet()
        withContext(Dispatchers.IO) {
            for (p in oldList.filter { it.id !in keptIds }) {
                TicketCache.delete(context, p.id)
            }
        }
    }

    suspend fun fetchAndMerge(previousList: List<Purchase>, force: Boolean = false): List<Purchase> {
        loading = true
        try {
            if (!isOnline(context) && includeExpired && previousList.isEmpty() && purchases.isEmpty()) {
                error = context.getString(R.string.err_no_internet)
                return previousList
            }
            val fresh = keepAlive(withContext(Dispatchers.IO) { api.getPurchases() }, includeExpired)
            purgeRemoved(context, previousList, fresh)
            val merged = mutableListOf<Purchase>()
            for (p in fresh) {
                if (p.name.isRealName()) merged.add(p)
                else {
                    val n = try { TicketCache.loadName(context, p.id) } catch (_: Exception) { null }
                    merged.add(n?.let { p.copy(name = it) } ?: p)
                }
            }
            purchases = merged
            withContext(Dispatchers.IO) {
                writeCache(context, merged.filter { p ->
                    parseIso(p.validTo)?.isBefore(LocalDateTime.now()) != true
                })
            }
            error = null
            return merged
        } catch (e: Exception) {
            // cache-first: ha van megjeleníthető adat, auto csendes, kézi (force) hangos
            if (previousList.isNotEmpty() || purchases.isNotEmpty()) {
                error = if (force) e.message else null
            } else error = e.message
            return previousList
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { readCache(context) }
        purchases = keepAlive(cached, includeExpired)
        fetchAndMerge(previousList = cached)
    }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger != 0) {
            fetchAndMerge(previousList = purchases)
        }
    }

    fun refresh() {
        scope.launch {
            fetchAndMerge(previousList = purchases, force = true)
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (loading) 360f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "refreshSpin"
    )

    val valid = purchases

    // Hiba snackbar: globális overlay (5 mp után eltűnik, le/bal/jobb swipe)
    val snackbar = com.domedav.mavjegy.ui.components.LocalSnackbar.current
    LaunchedEffect(error) {
        error?.let {
            snackbar.show(context.getString(friendlyError(it)), isError = true)
            error = null
        }
    }

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
                    text = stringResource(R.string.title_tickets),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(loading) {
                            detectTapGestures(
                                onTap = { if (!loading) refresh() },
                                onLongPress = { includeExpired = true; refresh() }
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
                IconButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                        contentDescription = stringResource(R.string.cd_logout),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            androidx.compose.animation.AnimatedVisibility(loading) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            if (!loading && valid.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.body_no_tickets),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(onClick = onNavigateToBuy) {
                            Text(stringResource(R.string.btn_buy_ticket))
                        }
                    }
                }
            } else {
            LazyColumn(
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
                items(valid, key = { it.id }) { purchase ->
                    val isValid = purchase.isValidTicket
                    val isPass = purchase.isPassTicket()
                    val isExpired = parseIso(purchase.validTo)?.isBefore(LocalDateTime.now()) ?: false
                    val nameMissing = !purchase.name.isRealName() && !isPass
                    if (nameMissing) {
                        LaunchedEffect(purchase.id) {
                            val mem = TicketCache.getNameMem(purchase.id)
                            if (mem.isRealName()) return@LaunchedEffect
                            if (isExpired && !isOnline(context)) return@LaunchedEffect
                            if (!isExpired) {
                                val cached = try { withContext(Dispatchers.IO) { TicketCache.loadName(context, purchase.id) } } catch (_: Exception) { null }
                                if (cached.isRealName()) {
                                    TicketCache.putNameMem(purchase.id, cached)
                                    return@LaunchedEffect
                                }
                            }
                            val n = runCatching {
                                withContext(Dispatchers.IO) {
                                    val d = api.getTicketDetails(purchase.id)
                                    if (!isExpired) withContext(Dispatchers.IO) { TicketCache.save(context, purchase.id, d) }
                                    d.ajanlatNev
                                }
                            }.getOrNull()
                            if (n.isRealName()) {
                                TicketCache.putNameMem(purchase.id, n)
                            }
                        }
                    }
                    val cardColor = when {
                        !isValid -> MaterialTheme.colorScheme.surfaceContainerHighest
                        isPass -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val shareTint = when {
                        !isValid -> MaterialTheme.colorScheme.onSurfaceVariant
                        isPass -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    val shareAction = remember(purchase.id, nameMissing) {
                        {
                            if (!nameMissing) {
                                scope.launch {
                                    performShareTicket(context, api, purchase, snackbar)
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isExpired) {
                            IconButton(
                                onClick = shareAction,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(cardColor, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = stringResource(R.string.share_title),
                                    tint = shareTint
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        PurchaseCard(
                            purchase = purchase,
                            onClick = { onOpenDetail(purchase) },
                            onLongClick = if (isExpired) {{}} else shareAction,
                            modifier = Modifier.weight(1f),
                            containerColor = cardColor,
                            enriching = nameMissing
                        )
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PurchaseCard(
    purchase: Purchase,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    containerColor: Color,
    enriching: Boolean = false,
) {
    val displayName = (purchase.name ?: TicketCache.getNameMem(purchase.id)).takeIf { it.isRealName() }
    val isValid = purchase.isValidTicket
    val isPass = purchase.isPassTicket()
    val now = LocalDateTime.now()
    val isExpired = parseIso(purchase.validTo)?.isBefore(now) ?: false
    val isNotYet = parseIso(purchase.validFrom)?.isAfter(now) == true
    val priceBadgeColor = if (isExpired) MaterialTheme.colorScheme.errorContainer
        else if (isPass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val priceTextColor = if (isExpired) MaterialTheme.colorScheme.onErrorContainer
        else if (isPass) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            shape = if (isValid) RoundedCornerShape(28.dp) else RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = if (!isValid) androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline
            ) else null,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                modifier = Modifier.padding(16.dp).heightIn(min = 84.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isPass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPass)
                            Icons.Rounded.CardMembership
                        else
                            Icons.Rounded.ConfirmationNumber,
                        contentDescription = null,
                        tint = if (isPass) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (enriching && displayName.isNullOrBlank() && !isPass) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmerPlaceholder()
                        )
                    } else {
                    val titleText = displayName?.takeIf { it.isRealName() }
                        ?: if (isPass) stringResource(R.string.title_pass) else null
                    if (!titleText.isNullOrBlank()) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            minLines = 1,
                            maxLines = 2,
                            softWrap = true,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    }
                    ValiditySubtitle(purchase = purchase, isPass = isPass)
                }
            }
        }
        }
        Surface(
            shape = CircleShape,
            color = priceBadgeColor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-14).dp)
        ) {
            Text(
                text = if (isExpired) stringResource(R.string.detail_expired)
                else if (isNotYet) stringResource(R.string.not_yet_valid)
                else if (purchase.currency == "HUF") stringResource(R.string.price_ft, purchase.amount)
                else stringResource(R.string.price_curr, purchase.amount, purchase.currency),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = priceTextColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private suspend fun performShareTicket(
    context: Context,
    api: MavApi,
    purchase: Purchase,
    snackbar: com.domedav.mavjegy.ui.components.SnackbarState
) {
    try {
        snackbar.show(context.getString(R.string.hint_img_download), isError = false)
        val cachedDetails = try { withContext(Dispatchers.IO) { TicketCache.load(context, purchase.id) } } catch (_: Exception) { null }
        val details = try { api.getTicketDetails(purchase.id) } catch (_: Exception) { null } ?: cachedDetails
        // PAPI-ban nincs ticketDatas[] -> bizonylat-azonosító gyakran null; a PAPI
        // jegykép-ághoz nem is kell (csak purchaseId), ezért nincs hard gate.
        val bizAzon = details?.ticketData?.bizonylatTechnikaiAzonosito
            ?: cachedDetails?.ticketData?.bizonylatTechnikaiAzonosito
        if (details != null && !(parseIso(purchase.validTo)?.isBefore(LocalDateTime.now()) ?: false)) {
            withContext(Dispatchers.IO) { TicketCache.save(context, purchase.id, details) }
        }
        val expired = parseIso(purchase.validTo)?.isBefore(LocalDateTime.now()) ?: false
        val result = api.getServerJegyKep(purchase.id, bizAzon, context, expired = expired)
        val bytes = result.imageBytes
        if (bytes == null) {
            snackbar.show(
                context.getString(R.string.fmt_download_fail, context.getString(friendlyError(result?.error))),
                isError = true
            )
            return
        }
        val name = "mavjegy_${purchase.id}.png"
        shareServerJegyKep(context, bytes, name)
        snackbar.show(context.getString(R.string.info_share_ready), isError = false)
    } catch (e: Exception) {
        snackbar.show(
            context.getString(friendlyError(e.message ?: e.toString())),
            isError = true
        )
    }
}

private suspend fun shareServerJegyKep(context: android.content.Context, bytes: ByteArray, displayName: String) {
    val mime = if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) "image/png" else "image/jpeg"
    val uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, displayName).apply { writeBytes(bytes) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_title)))
}
