package com.domedav.mavjegy.ui.screens

import com.domedav.mavjegy.R

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.domedav.mavjegy.util.isOnline
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.domedav.mavjegy.data.SettingsStore
import com.domedav.mavjegy.ui.components.LocalSnackbar
import com.domedav.mavjegy.ui.components.ExpressiveLoader
import kotlinx.coroutines.delay

internal const val BUY_URL = "https://jegy.mav.hu"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BuyScreen(webViewState: MutableState<WebView?>) {
    var showLoader by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    var online by remember { mutableStateOf(isOnline(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // WebView <input type="file"> -> rendszer fájlválasztó híd
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = mutableListOf<Uri>()
            result.data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            result.data?.data?.let { uris.add(it) }
            cb?.onReceiveValue(uris.toTypedArray())
        } else {
            // Mégse: kötelező null, különben az oldal örökre vár
            cb?.onReceiveValue(null)
        }
    }

    // Első betöltés: min 450ms + oldal betöltése. Tab-váltás: azonnal.
    val isFirstLoad = remember { webViewState.value == null }
    var minDelayDone by remember { mutableStateOf(false) }
    var pageLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isFirstLoad) {
            showLoader = false
        } else {
            delay(450)
            minDelayDone = true
        }
    }

    // Első betöltésnél: mindkét feltétel kell. Tab-váltásnál ez már nem fut.
    LaunchedEffect(minDelayDone, pageLoaded) {
        if (isFirstLoad && minDelayDone && pageLoaded) showLoader = false
    }

    LaunchedEffect(Unit) {
        if (!SettingsStore.getWebviewLoginHintSeen(context)) {
            snackbar.show(
                context.getString(R.string.buy_login_hint),
                isError = false
            )
            SettingsStore.setWebviewLoginHintSeen(context, true)
        }
    }


    // Session-perzisztencia: kilépéskor / elhagyáskor elmentjük a WebView állapotát,
    // hogy app-újraindítás után is megmaradjon a bejelentkezés.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                webViewState.value?.let { WebViewSession.save(context, it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

    if (!online) {
        // Nincs internet – a WebView helyett értesítés + újrapróbálás
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.body_no_internet),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.body_buy_needs_internet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(20.dp))
            Button(onClick = {
                online = isOnline(context)
                if (online) {
                    webViewState.value?.loadUrl(BUY_URL)
                }
            }) {
                Text(stringResource(R.string.btn_retry))
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    val existing = webViewState.value
                    if (existing != null) {
                        // Újrahasználjuk a meglévő WebView-t (session + DOM megmarad)
                        existing
                    } else {
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            val cookies = CookieManager.getInstance()
                            cookies.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                var restored = false
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    cookies.flush()
                                    WebViewSession.save(context, this@apply)
                                    if (!restored && url?.contains("mav.hu") == true) {
                                        restored = true
                                        WebViewSession.restore(context, this@apply)
                                    }
                                    pageLoaded = true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    view: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>,
                                    fileChooserParams: FileChooserParams
                                ): Boolean {
                                    pendingFileCallback?.onReceiveValue(null)
                                    pendingFileCallback = filePathCallback
                                    runCatching {
                                        filePickerLauncher.launch(
                                            fileChooserParams.createIntent().apply {
                                                if (fileChooserParams.mode ==
                                                    FileChooserParams.MODE_OPEN_MULTIPLE
                                                ) {
                                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                                }
                                            }
                                        )
                                    }.onFailure {
                                        pendingFileCallback = null
                                        filePathCallback.onReceiveValue(null)
                                    }
                                    return true
                                }
                            }
                            loadUrl(BUY_URL)
                            webViewState.value = this
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (showLoader) 0f else 1f)
            )
            if (showLoader) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveLoader(size = 56.dp)
                }
            }
        }
    }
}
