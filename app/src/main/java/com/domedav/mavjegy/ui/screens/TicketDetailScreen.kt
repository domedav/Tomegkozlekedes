@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.domedav.mavjegy.ui.screens

import com.domedav.mavjegy.R

import com.domedav.mavjegy.ui.components.ExpressiveLoader

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.domedav.mavjegy.data.MavApi
import com.domedav.mavjegy.data.SettingsStore
import com.domedav.mavjegy.data.Purchase
import com.domedav.mavjegy.data.TicketCache
import com.domedav.mavjegy.data.TicketDetails
import com.domedav.mavjegy.data.isPassTicket
import com.domedav.mavjegy.ui.components.LocalSnackbar
import com.domedav.mavjegy.util.BarcodeGenerator
import com.domedav.mavjegy.util.friendlyError
import com.domedav.mavjegy.util.PassOwnerPrefs
import com.domedav.mavjegy.util.TicketDecoder
import com.domedav.mavjegy.util.TicketOwner
import com.domedav.mavjegy.util.ViewerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale

@Composable
fun TicketDetailScreen(
    api: MavApi,
    purchase: Purchase,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Max brightness + keep-screen-on while this screen is visible
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val old = activity?.window?.attributes?.screenBrightness
        if (activity != null) {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = 1f }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (activity != null) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = old ?: -1f }
            }
        }
    }

    var details by remember { mutableStateOf<TicketDetails?>(null) }
    var loadingDetails by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasCached by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableStateOf(0) }

    var barcodeBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var generatingBarcode by remember { mutableStateOf(true) }

    var owner by remember { mutableStateOf<TicketOwner?>(null) }
    var photoBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isPass = purchase.isPassTicket()

    // Tulajdonosi adatok: felhasználói szerkesztés (fotó, név, dátum, azonosító)
    var showOwnerDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var ownerEdit by remember(purchase.id) { mutableStateOf(PassOwnerPrefs.load(context, purchase.id)) }

    // Utastípus kód -> emberi név (GetAlapadatok, offline cache-elve)
    var typeNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        typeNames = runCatching { api.getTypeNames(context) }.getOrDefault(emptyMap())
    }

    fun resolveType(code: String?): String? {
        val c = code?.takeIf { it.isNotBlank() } ?: return null
        return typeNames[c] ?: typeNames[c.uppercase()] ?: if (c.startsWith("HU_")) null else c
    }

    val effectiveOwner = when {
        owner == null && ownerEdit == null -> null
        else -> TicketOwner(
            name = ownerEdit?.name ?: owner?.name,
            birthDate = ownerEdit?.birthDate ?: owner?.birthDate,
            passengerType = resolveType(owner?.passengerType),
            photoBase64 = owner?.photoBase64,
            azonosito = ownerEdit?.azonosito ?: owner?.azonosito
        )
    }

    val savedViewer = remember { ViewerPrefs.load(context) }
    var scale by remember { mutableStateOf(savedViewer?.first ?: 1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf<Float?>(null) }

    val expired = isPurchaseExpired(purchase)

    val snackbar = LocalSnackbar.current

    val pageOffsetY = remember { Animatable(0f) }
    val pageScale = remember { Animatable(1f) }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val minOffsetToClose = screenH / 8f

    // Szerkesztett fotó (ha van) a hash-alapú cache-ből
    var customPhotoBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(ownerEdit?.photoHash) {
        val hash = ownerEdit?.photoHash ?: return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) {
            com.domedav.mavjegy.data.OfflineStore.loadOwnerPhoto(context, hash)
        }
        customPhotoBitmap = bytes?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val displayPhotoBitmap = customPhotoBitmap ?: photoBitmap

    // Hibák snackbarban
    LaunchedEffect(Unit) {
        if (!SettingsStore.getHasSwipedBack(context)) {
            snackbar.show(context.getString(R.string.hint_swipe_back), isError = false)
        }
    }

    LaunchedEffect(purchase.id, fetchTrigger) {
        loadingDetails = true
        errorMessage = null
        val cached = try { withContext(Dispatchers.IO) { TicketCache.load(context, purchase.id) } } catch (_: Exception) { null }
        if (cached != null) {
            hasCached = true
            details = cached
        }
        try {
            val fetched = api.getTicketDetails(purchase.id)
            details = fetched
            if (!expired) {
                withContext(Dispatchers.IO) { TicketCache.save(context, purchase.id, fetched) }
            }
            errorMessage = null
        } catch (e: Exception) {
            if (!hasCached) errorMessage = e.message ?: e.javaClass.simpleName
        }
        loadingDetails = false
    }

    val serialized = details?.ticketData?.serializedTicketData

    // FULL PAGE — themed page background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .graphicsLayer {
                translationY = pageOffsetY.value
                scaleX = pageScale.value
                scaleY = pageScale.value
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---------------- VONALKÓD ZÓNA ----------------
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val zoneW = constraints.maxWidth.toFloat()
                val zoneH = constraints.maxHeight.toFloat()
                val barcodeTargetPx = (with(density) { maxWidth.toPx() }.toInt() * 2).coerceAtLeast(256)

                // Glyph méret: agresszíven kicsi, hogy messziről / kicsinyítve is beolvasható legyen
                val zoneHorizontalInset = zoneW * 0.25f
                val zoneInnerW = zoneW - zoneHorizontalInset * 2f
                val baseSize = minOf(zoneInnerW, zoneH)
                val displaySize = baseSize * 0.55f

                // Pan szabályok: bőven felfele mozgatható (a zóna kétszerese), lefelé kicsit kevesebb
                val maxPanYUp = displaySize * 2f
                val maxPanYDown = displaySize * 0.75f

                fun clampOffsetY(y: Float): Float = y.coerceIn(-maxPanYUp, maxPanYDown)

                // Alapértelmezett (nincs mentett állapot): középen
                val topAnchoredOffsetY = 0f
                if (offsetY == null) {
                    offsetY = savedViewer?.let { (_, ratio) ->
                        clampOffsetY(ratio * displaySize)
                    } ?: topAnchoredOffsetY
                }

                LaunchedEffect(serialized) {
                    val decoded = if (serialized.isNullOrBlank()) null
                    else withContext(Dispatchers.Default) {
                        runCatching { TicketDecoder.decodeSerialized(serialized) }.getOrNull()
                    }
                    val o = decoded?.rawJson?.let { TicketDecoder.extractOwner(it) }
                    if (o != null) {
                        owner = o
                        photoBitmap = decodePhoto(o.photoBase64)
                    } else {
                        // Bérlet fallback: tulajdonos a HPT (bérletes utas) adatokból
                        owner = null
                        photoBitmap = null
                        val po = runCatching { api.getPassOwnerData(context) }.getOrNull()
                        po?.let {
                            owner = TicketOwner(it.fullName, it.birthDate, null, it.photoBase64, it.azonosito)
                            photoBitmap = decodePhoto(it.photoBase64)
                        }
                    }
                }

                @OptIn(FlowPreview::class)
                LaunchedEffect(displaySize) {
                    snapshotFlow { scale to (offsetY ?: 0f) }
                        .distinctUntilChanged()
                        .debounce(400)
                        .collect { (s, y) ->
                            ViewerPrefs.save(context, s, y / displaySize)
                        }
                }

                // A HIVATALOS kód: a szerver jegyképéből dekódolt vonalkód-tartalom.
                // Ez érkezik meg először cache-ből, majd frissül a hálózatról;
                // ha nem elérhető, marad a serialized adatokból épített fallback.
                var serverBarcodeText by remember(purchase.id) { mutableStateOf<String?>(null) }
                var serverImageBitmap by remember(purchase.id) {
                    mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
                }
                var loadingServerImage by remember(purchase.id) { mutableStateOf(false) }
                var showServerImage by remember(purchase.id) { mutableStateOf(SettingsStore.getDetailPreferServerImage(context)) }
                var serverFetchStarted by remember(purchase.id) { mutableStateOf(false) }

                fun requestServerJegyKep() {
                    if (loadingServerImage) return
                    if (serverFetchStarted && serverImageBitmap == null) {
                        serverFetchStarted = false
                    }
                    if (serverFetchStarted) return
                    serverFetchStarted = true
                    loadingServerImage = true
                    scope.launch {
                        val bizAzon = details?.ticketData?.bizonylatTechnikaiAzonosito
                            val result = runCatching {
                            api.getServerJegyKep(purchase.id, bizAzon, context)
                        }.getOrNull()
                        loadingServerImage = false
                        val bytes = result?.imageBytes
                        val bmp = bytes?.let {
                            withContext(Dispatchers.Default) {
                                runCatching {
                                    BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        if (bmp != null) serverImageBitmap = bmp
                        if (!result?.barcodeText.isNullOrBlank()) {
                            serverBarcodeText = result!!.barcodeText
                        }
                        if (bmp == null) {
                            // sikertelen: engedjük az újrapróbálást, és ha kép-nézetben voltunk,
                            // szóljunk (ne váltsunk Aztec-re)
                            serverFetchStarted = false
                            if (showServerImage) {
                                snackbar.show(context.getString(friendlyError(result?.error)), isError = true)
                            }
                        }
                    }
                }

                // Első nyitásra háttérben letölti + MENTI a jegyképet (utána offline is megvan)
                LaunchedEffect(purchase.id, serialized) {
                    if (!serialized.isNullOrBlank()) {
                        requestServerJegyKep()
                    }
                }

                LaunchedEffect(showServerImage) {
                    if (showServerImage && serverImageBitmap == null) requestServerJegyKep()
                }

                LaunchedEffect(serialized, serverBarcodeText, barcodeTargetPx, fetchTrigger, expired) {
                    if (expired || serialized.isNullOrBlank()) {
                        generatingBarcode = false
                        return@LaunchedEffect
                    }
                    generatingBarcode = true
                    // Csak a MÁV szerverkép vonalkódja a hivatalos – nincs fallback más forrásra.
                    val content = serverBarcodeText
                    if (content.isNullOrBlank()) {
                        generatingBarcode = false
                        // Sikertelen kinyerés: mutassuk az eredeti letöltött jegyképet.
                        showServerImage = true
                        // snackbar.show(
                        //     "Nem sikerült a kód kinyerése – az eredeti jegykép látható",
                        //     isError = false
                        // )
                        return@LaunchedEffect
                    }
                    barcodeBitmap = withContext(Dispatchers.Default) {
                        try {
                            BarcodeGenerator.generate(content, BarcodeGenerator.Type.AZTEC, barcodeTargetPx, barcodeTargetPx)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    generatingBarcode = false
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(displaySize, zoneInnerW, showServerImage, zoneW) {
                            val baseForMode = if (showServerImage) zoneW else displaySize
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.15f, 5f)
                                val mpx =
                                    if (scale > 1f) {
                                        ((baseForMode * scale - zoneInnerW) / 2f).coerceAtLeast(0f)
                                    } else 0f
                                offsetX = if (scale > 1f) {
                                    (offsetX + pan.x).coerceIn(-mpx, mpx)
                                } else 0f
                                offsetY = clampOffsetY((offsetY ?: 0f) + pan.y)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loadingDetails && details == null && errorMessage == null -> {
                            ExpressiveLoader()
                        }

                        errorMessage != null && !hasCached && details == null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = errorMessage ?: stringResource(R.string.err_unknown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = { fetchTrigger++ }) {
                                    Text(stringResource(R.string.btn_retry))
                                }
                            }
                        }

                        else -> {
                            val simg = serverImageBitmap
                            when {
                            showServerImage && simg != null -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    Image(
                                        bitmap = simg,
                                        contentDescription = stringResource(R.string.cd_server_img),
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                                translationX = offsetX
                                                translationY = offsetY ?: 0f
                                            }
                                    )
                                    if (expired) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(Color.Black.copy(alpha = 0.45f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(24.dp),
                                                color = MaterialTheme.colorScheme.errorContainer
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.detail_expired),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            showServerImage && loadingServerImage -> ExpressiveLoader()

                            else -> {
                                if (expired) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.errorContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_expired),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.detail_img_unavailable),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            }
                        }
                    }
                }
            }

            // ---------------- ALSÓ PANEL: TULAJ + ÉRVÉNYESSÉG ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val ny = (pageOffsetY.value + delta).coerceIn(-screenH, screenH)
                                pageOffsetY.snapTo(ny)
                                pageScale.snapTo(
                                    (1f - kotlin.math.abs(ny) / (screenH * 0.9f)).coerceIn(0.85f, 1f)
                                )
                            }
                        },
                        onDragStopped = { velocity ->
                            scope.launch {
                                if (kotlin.math.abs(pageOffsetY.value) > minOffsetToClose
                                    || kotlin.math.abs(velocity) > 1000f
                                ) {
                                    val dir = if (pageOffsetY.value >= 0f) 1f else -1f
                                    pageOffsetY.animateTo(dir * screenH, tween(250))
                                    pageScale.animateTo(0.85f, tween(250))
                                    SettingsStore.setHasSwipedBack(context, true)
                                    onBack()
                                } else {
                                    pageOffsetY.animateTo(0f, tween(200))
                                    pageScale.animateTo(1f, tween(200))
                                }
                            }
                        }
                    )
            ) {
                details?.let { d ->
                    OwnerAndValidityPanel(
                        details = d,
                        purchase = purchase,
                        owner = effectiveOwner,
                        photoBitmap = displayPhotoBitmap,
                        isPass = isPass,
                        onOwnerClick = { showOwnerDialog = true },
                        onEditClick = { showEditDialog = true }
                    )
                }
            }
        }

        // TOP ROW — back / retry "cookie" buttons over the page
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (errorMessage != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    IconButton(onClick = { fetchTrigger++ }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.btn_retry),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // TULAJDONOS RÉSZLET NÉZET (nagyított fotó + adatok)
        if (showOwnerDialog && effectiveOwner != null) {
            OwnerDetailsDialog(
                owner = effectiveOwner,
                photoBitmap = displayPhotoBitmap,
                onDismiss = { showOwnerDialog = false }
            )
        }

        // BÉRLETTULAJDONOS / JEGYTULAJDONOS SZERKESZTÉS
        if (showEditDialog) {
            // Kiinduló érték: a mentett szerkesztés győz, a hiányzókat a jegy adatai töltik ki
            // (így a popup nem üres, ha a jegyen már megvannak a tulajdonosi adatok)
            val mergedInitial = PassOwnerPrefs.Edit(
                name = ownerEdit?.name ?: effectiveOwner?.name,
                birthDate = ownerEdit?.birthDate ?: effectiveOwner?.birthDate,
                azonosito = ownerEdit?.azonosito ?: effectiveOwner?.azonosito,
                photoHash = ownerEdit?.photoHash
            )
            EditPassOwnerDialog(
                initial = mergedInitial,
                initialPhotoBase64 = if (ownerEdit?.photoHash == null) effectiveOwner?.photoBase64 else null,
                onDismiss = { showEditDialog = false },
                onSave = { edit ->
                    PassOwnerPrefs.save(context, purchase.id, edit)
                    ownerEdit = edit
                    showEditDialog = false
                }
            )
        }
    }
}

private suspend fun decodePhoto(base64: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val b64 = base64?.takeIf { it.isNotBlank() } ?: return null
    return withContext(Dispatchers.Default) {
        try {
            val bytes = Base64.getDecoder().decode(b64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun OwnerAndValidityPanel(
    details: TicketDetails,
    purchase: Purchase,
    owner: TicketOwner?,
    photoBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    isPass: Boolean,
    onOwnerClick: () -> Unit,
    onEditClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            owner?.let { o ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Fotó / avatar – koppintásra nagyított nézet a részletekkel
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .clickable(onClick = onOwnerClick),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = photoBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = stringResource(R.string.owner_photo),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onOwnerClick),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (!o.name.isNullOrBlank()) {
                            Text(
                                text = o.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        o.birthDate?.takeIf { it.isNotBlank() }?.let { bd ->
                            Text(
                                text = stringResource(R.string.fmt_birth, formatBirthDate(bd)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Bérletigazolvány azonosító – kiemelve, ez a lényeg
                        o.azonosito?.takeIf { it.isNotBlank() }?.let { azon ->
                            Text(
                                text = azon,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                        }
                        o.passengerType?.takeIf { it.isNotBlank() }?.let { pt ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = pt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    // Szerkesztés – jegyeknél és bérleteknél egyaránt
                    IconButton(onClick = onEditClick) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.title_edit_owner),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pontos megnevezés – ha az API nem adja, nem írunk ki semmit
            titleFor(details)?.let { nev ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconValueRow(
                        icon = Icons.Rounded.ConfirmationNumber,
                        value = nev,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Érvényességi intervallum + hátralévő napok badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconValueRow(
                    icon = Icons.Rounded.Schedule,
                    value = stringResource(R.string.fmt_date_range, formatDate(purchase.validFrom, stringResource(R.string.dash_fallback)), formatDate(purchase.validTo, stringResource(R.string.dash_fallback))),
                    iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                DaysRemainingBadge(purchase = purchase)
            }
        }
    }
}

@Composable
private fun DaysRemainingBadge(purchase: Purchase) {
    val to = parseDate(purchase.validTo)
    val now = LocalDateTime.now()
    val days = to?.let { ChronoUnit.DAYS.between(now.toLocalDate(), it.toLocalDate().plusDays(1)).toInt() }
    val expiredNow = days == null || days <= 0
    val notYetNow = !expiredNow && parseDate(purchase.validFrom)?.isAfter(now) == true

    val container = if (expiredNow) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val content = if (expiredNow) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Rounded.Timer,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (expiredNow) stringResource(R.string.detail_expired)
                else if (notYetNow) stringResource(R.string.not_yet_valid)
                else stringResource(R.string.fmt_days, days),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = content
            )
        }
    }
}

@Composable
private fun IconValueRow(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .background(iconContainerColor, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.height(20.dp).width(20.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun parseDate(iso: String?): LocalDateTime? {
    if (iso.isNullOrBlank()) return null
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
    )
    for (f in formats) {
        try {
            return ZonedDateTime.parse(iso, DateTimeFormatter.ofPattern(f)).toLocalDateTime()
        } catch (_: Exception) {}
    }
    return try {
        LocalDate.parse(iso).atStartOfDay()
    } catch (_: Exception) { null }
}

private fun isPurchaseExpired(purchase: Purchase): Boolean {
    val to = parseDate(purchase.validTo) ?: return false
    return to.isBefore(LocalDateTime.now())
}

private fun formatBirthDate(raw: String): String {
    val cleaned = raw.trim()
    // .NET JSON dátum: /Date(1234567890123)/ vagy /Date(1234567890123+0100)/
    val netDate = Regex("/Date\\((-?\\d+)").find(cleaned)?.groupValues?.get(1)
    // Epoch millisec / sec
    val epochVal = netDate ?: (cleaned.takeIf { it.matches(Regex("\\d{10,13}")) })
    if (epochVal != null) {
        return try {
            val n = epochVal.toLong()
            val ms = if (n > 99_999_999_999L) n else n * 1000L
            java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy.MM.dd.", Locale.forLanguageTag("hu")))
        } catch (_: Exception) {
            cleaned
        }
    }
    // ISO dátum / dátum-idő
    return try {
        LocalDate.parse(cleaned.take(10))
            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd.", Locale.forLanguageTag("hu")))
    } catch (_: Exception) {
        cleaned
    }
}

private fun titleFor(details: TicketDetails?): String? =
    details?.ajanlatNev?.takeIf { it.isNotBlank() }

@Composable
private fun OwnerDetailsDialog(
    owner: TicketOwner,
    photoBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Nagyított fotó
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = photoBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                                contentDescription = stringResource(R.string.owner_photo_zoom),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(90.dp)
                        )
                    }
                }

                if (!owner.name.isNullOrBlank()) {
                    Text(
                        text = owner.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    owner.birthDate?.takeIf { it.isNotBlank() }?.let { bd ->
                        OwnerDetailRow(stringResource(R.string.birth_date), formatBirthDate(bd))
                    }
                    owner.azonosito?.takeIf { it.isNotBlank() }?.let { azon ->
                        OwnerDetailRow(stringResource(R.string.row_pass_id), azon)
                    }
                    owner.passengerType?.takeIf { it.isNotBlank() }?.let { pt ->
                        OwnerDetailRow(stringResource(R.string.row_pax), pt)
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        }
    }
}

@Composable
private fun OwnerDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EditPassOwnerDialog(
    initial: PassOwnerPrefs.Edit?,
    initialPhotoBase64: String? = null,
    onDismiss: () -> Unit,
    onSave: (PassOwnerPrefs.Edit) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var birthDate by remember { mutableStateOf(initial?.birthDate ?: "") }
    var azonosito by remember { mutableStateOf(initial?.azonosito ?: "") }
    var photoHash by remember { mutableStateOf(initial?.photoHash) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Fotó választó – a kiválasztott kép hash-elt, kompresszált cache-be kerül
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    photoHash = com.domedav.mavjegy.data.OfflineStore.saveOwnerPhoto(context, bytes) ?: photoHash
                }
            } catch (_: Exception) {}
        }
    }

    if (showDatePicker) {
        val initialState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = birthDate.takeIf { it.isNotBlank() }?.let { bd ->
                try {
                    java.time.LocalDate.parse(bd.replace(".", "-").let { s ->
                        if (s.endsWith("-")) s.dropLast(1) else s })
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                } catch (_: Exception) { null }
            }
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    initialState.selectedDateMillis?.let { ms ->
                        birthDate = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd."))
                    }
                    showDatePicker = false
                    }) { Text(stringResource(R.string.btn_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        ) {
            androidx.compose.material3.DatePicker(state = initialState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_edit_owner)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Fotó: koppintásra cserélhető; ha nincs saját fotó, a jegy fotója látszik
                val customBmp = photoHash?.let { hash ->
                    runCatching {
                        val bytes = com.domedav.mavjegy.data.OfflineStore.loadOwnerPhoto(context, hash)
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }.getOrNull()
                }
                val initialBmp = initialPhotoBase64?.let { b64 ->
                    runCatching {
                        val bytes = java.util.Base64.getDecoder().decode(b64)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
                val shownBmp = customBmp ?: initialBmp
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .clickable {
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (shownBmp != null) {
                        Image(
                            bitmap = shownBmp,
                            contentDescription = stringResource(R.string.cd_photo_edit),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = stringResource(R.string.cd_photo_add),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.cd_photo_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_fullname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Dátum: selector, nem input box – átfedő kattintási réteggel
                Box {
                    OutlinedTextField(
                        value = birthDate.ifBlank { "" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.birth_date)) },
                        placeholder = { Text(stringResource(R.string.hint_pick_date)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showDatePicker = true }
                    )
                }
                OutlinedTextField(
                    value = azonosito,
                    onValueChange = { azonosito = it },
                    label = { Text(stringResource(R.string.label_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    PassOwnerPrefs.Edit(
                        name = name.trim().takeIf { it.isNotEmpty() },
                        birthDate = birthDate.trim().takeIf { it.isNotEmpty() },
                        azonosito = azonosito.trim().takeIf { it.isNotEmpty() },
                        photoHash = photoHash
                    )
                )
            }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
