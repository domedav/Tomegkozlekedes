# Fix: race condition + loader on each card

## Root cause
Multiple cards run `LaunchedEffect` in parallel, each doing `purchases = purchases.map { ... copy(name=n) }`. When two complete simultaneously, one overwrites the other's update (classic lost-update race).

## Fix: use a `mutableStateMapOf` for names (thread-safe)
Instead of updating `purchases` from each card (race-prone), store resolved names in a `mutableStateMapOf<String, String?>` which is backed by `ConcurrentHashMap` (thread-safe). Each card reads its own entry — no race, no Mutex needed.

### Changes in `TicketsScreen.kt`

1. Add `val nameOverrides = remember { mutableStateMapOf<String, String?>() }` after state vars

2. In items lambda — per-card LaunchedEffect writes to `nameOverrides`:
```kotlin
if (nameMissing) {
    LaunchedEffect(purchase.id) {
        val cached = withContext(Dispatchers.IO) {
            runCatching { TicketCache.loadName(context, purchase.id) }.getOrNull()
        }
        if (!cached.isNullOrBlank()) {
            nameOverrides[purchase.id] = cached
            return@LaunchedEffect
        }
        val n = runCatching {
            withContext(Dispatchers.IO) {
                val d = api.getTicketDetails(purchase.id)
                TicketCache.save(context, purchase.id, d)
                d.ajanlatNev
            }
        }.getOrNull()
        if (!n.isNullOrBlank()) {
            nameOverrides[purchase.id] = n
            withContext(Dispatchers.IO) { writeCache(context, purchases.map { p -> if (p.id == purchase.id) p.copy(name = n) else p }) }
        }
    }
}
```

3. Card title reads from `nameOverrides`:
```kotlin
val displayName = purchase.name ?: nameOverrides[purchase.id]
val titleText = displayName ?: if (isPass) stringResource(R.string.title_pass) else null
```

4. ExpressiveLoader shows when `nameMissing && nameOverrides[purchase.id] == null`:
```kotlin
if (enriching && nameMissing && nameOverrides[purchase.id] == null) {
    ExpressiveLoader(size = 16.dp)
} else { ... }
```

5. `writeCache` at the end (after enrichment for a card) persists names for next launch.

### Why this works
- `mutableStateMapOf` is thread-safe (ConcurrentHashMap under the hood)
- Each card reads its own entry — no race
- Compose observes `nameOverrides[purchase.id]` reads and recomposes when updated
- No Mutex needed, no complex serialization
- `TicketCache.save` + `writeCache` persist for next launch
