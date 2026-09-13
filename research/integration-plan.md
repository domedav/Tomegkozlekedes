# Integrációs Terv

## Jelenlegi Alkalmazás Szerkezete

- **Csomagnév**: `com.domedav.mavjegy`
- **UI**: 100% Jetpack Compose (NINCS XML layout)
- **Hálózat**: OkHttp 4.12.0 (raw, Retrofit nélkül)
- **Sorozatosítás**: kotlinx-serialization-json
- **Architektúra**: Single Activity, Compose Navigation
- **Feed funkció**: NEM létezik jelenleg

## Megoldás: Standalone RSS Olvasó (Alkalmazáson KÍVÜL)

Mivel az alkalmazást NEM módosíthatjuk, egy **független standalone RSS olvasó alkalmazást** vagy **eszközt** kell készíteni.

### Opció 1: Független Android Alkalmazás (Javasolt)

Külön, önálló alkalmazás a MÁV információk megjelenítésére.

**Architektúra:**
```
com.domedav.mavinform/
├── MainActivity.kt              # Entry point
├── data/
│   ├── RssParser.kt            # RSS parse-olás
│   ├── MavinformRepository.kt  # Adatforrás kezelés
│   └── MavinformItem.kt        # Adatmodell
├── ui/
│   ├── screens/
│   │   ├── FeedScreen.kt       # Fő feed lista
│   │   └── DetailScreen.kt     # Részletes nézet
│   ├── components/
│   │   └── FeedItem.kt         # Egy item megjelenítése
│   └── theme/
│       └── Theme.kt            # Material 3 téma
└── util/
    └── HtmlParser.kt           # HTML tartalom feldolgozás
```

**Függőségek (build.gradle.kts):**
```kotlin
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // XML Parsing (RSS-hez)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // HTML parse-oláshoz (description)
    implementation("org.jsoup:jsoup:1.18.1")

    // Pagination (ha kell)
    implementation("androidx.paging:paging-runtime-ktx:3.3.4")
}
```

### Opció 2: Kotlin Multiplatform Modul

Ha a meglévő alkalmazásba szeretné integrálni a jövőben:

```kotlin
// shared/src/commonMain/kotlin/com/domedav/mavinform/
// - RssParser.kt
// - MavinformItem.kt
// - MavinformRepository.kt
```

### Opció 3: Python CLI Eszköz

Gyors prototípus/kommandós eszköz:

```python
# mavinform_reader.py
import feedparser
import html2text

# RSS feed beolvasása
feed = feedparser.parse('https://www.mavcsoport.hu/rss.xml')

for entry in feed.entries:
    print(f"Cím: {entry.title}")
    print(f"Dátum: {entry.published}")
    print(f"Link: {entry.link}")
    # HTML -> szöveg
    text = html2text.html2text(entry.summary)
    print(f"Tartalom: {text[:200]}...")
    print("---")
```

## Kotlin RSS Parser Implementáció

### 1. Adatmodell

```kotlin
// data/MavinformItem.kt
package com.domedav.mavinform.data

import kotlinx.serialization.Serializable

@Serializable
data class MavinformItem(
    val title: String,
    val link: String,
    val description: String,      // HTML tartalom
    val pubDate: String,           // RFC 2822 formátum
    val creator: String,           // dc:creator
    val guid: String,
    val plainTextDescription: String = ""  // Szöveges változat
)
```

### 2. RSS Parser

```kotlin
// data/RssParser.kt
package com.domedav.mavinform.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object RssParser {

    fun parse(xml: String): List<MavinformItem> {
        val items = mutableListOf<MavinformItem>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var creator = ""
        var guid = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            inItem = true
                            title = ""; link = ""; description = ""
                            pubDate = ""; creator = ""; guid = ""
                        }
                        "title" -> if (inItem) title = readText(parser)
                        "link" -> if (inItem) link = readText(parser)
                        "description" -> if (inItem) description = readText(parser)
                        "pubDate" -> if (inItem) pubDate = readText(parser)
                        "guid" -> if (inItem) guid = readText(parser)
                    }
                    // dc:creator namespace kezelés
                    if (parser.name == "creator" &&
                        parser.namespace == "http://purl.org/dc/elements/1.1/") {
                        if (inItem) creator = readText(parser)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && inItem) {
                        inItem = false
                        items.add(
                            MavinformItem(
                                title = title,
                                link = link,
                                description = description,
                                pubDate = pubDate,
                                creator = creator,
                                guid = guid,
                                plainTextDescription = htmlToPlainText(description)
                            )
                        )
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun readText(parser: XmlPullParser): String {
        val result = StringBuilder()
        if (parser.next() == XmlPullParser.TEXT) {
            result.append(parser.text)
            parser.nextTag()
        }
        return result.toString().trim()
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")  // HTML tag-ek eltávolítása
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
```

### 3. Repository

```kotlin
// data/MavinformRepository.kt
package com.domedav.mavinform.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

class MavinformRepository {

    private val client = OkHttpClient()
    private val feedUrl = "https://www.mavcsoport.hu/rss.xml"

    suspend fun fetchFeed(): List<MavinformItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "MavInformApp/1.0")
            .build()

        val response = client.newCall(request).execute()
        val xml = response.body?.string() ?: throw Exception("Üres válasz")

        val items = RssParser.parse(xml)

        // Dátumok parse-olása és rendezés
        items.sortedByDescending { parseDate(it.pubDate) }
    }

    private fun parseDate(dateStr: String): java.util.Date? {
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            format.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}
```

### 4. ViewModel

```kotlin
// ui/screens/FeedViewModel.kt
package com.domedav.mavinform.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.domedav.mavinform.data.MavinformItem
import com.domedav.mavinform.data.MavinformRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FeedUiState(
    val items: List<MavinformItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class FeedViewModel : ViewModel() {

    private val repository = MavinformRepository()
    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState

    init {
        loadFeed()
    }

    fun loadFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val items = repository.fetchFeed()
                _uiState.value = _uiState.value.copy(
                    items = items,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Ismeretlen hiba"
                )
            }
        }
    }
}
```

### 5. Feed Screen

```kotlin
// ui/screens/FeedScreen.kt
package com.domedav.mavinform.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.domedav.mavinform.data.MavinformItem
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel = viewModel(),
    onItemClick: (MavinformItem) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MÁV Mavinform") },
                actions = {
                    IconButton(onClick = { viewModel.loadFeed() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Frissítés"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error ?: "Hiba",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadFeed() }) {
                            Text("Újra")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.items) { item ->
                            FeedItemCard(item = item, onClick = { onItemClick(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeedItemCard(item: MavinformItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.plainTextDescription,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatDate(item.pubDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun formatDate(dateStr: String): String {
    return try {
        val inputFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        val outputFormat = SimpleDateFormat("yyyy. MM. dd. HH:mm", Locale("hu"))
        val date = inputFormat.parse(dateStr)
        date?.let { outputFormat.format(it) } ?: dateStr
    } catch (e: Exception) {
        dateStr
    }
}
```

## Telepítési Lehetőségek

### 1. Külön APK
- Független alkalmazás
- Saját ikon, név
- Telepíthető mellé a MavJegyApp mellé

### 2. Widget
- Android App Widget a kezdőképernyőre
- RSS feed megjelenítés listában

### 3. Notification Service
- Háttérben futó szolgáltatás
- Új hír értesítés push notification-nel

## Tesztelés

```bash
# Buildelés
bash build.sh assembleDebug

# APK telepítés
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Következő Lépések

1. [ ] Standalone alkalmazás projekt létrehozása
2. [ ] RSS parser implementálása
3. [ ] UI komponensek megírása
4. [ ] Tesztelés valós adatokkal
5. [ ] Hibakezelés finomítása
6. [ ] Offline caching (utolsó ismert állapot)
