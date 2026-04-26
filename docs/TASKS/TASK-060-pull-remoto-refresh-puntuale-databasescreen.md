# TASK-060 — Pull remoto → refresh puntuale DatabaseScreen

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-060 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Supabase sync / `DatabaseScreen` / Paging 3 / refresh UI |
| Creato | 2026-04-25 |
| Ultimo aggiornamento | 2026-04-26 — chiuso dopo S2 post-fix TASK-065: B riceve update remoto puntuale senza search/scroll jump |

**Nota governance:** task riaperto/chiuso nel contesto richiesto dall'utente su TASK-065/TASK-064/TASK-063. La verifica live S2 post-fix copre il criterio remoto puntuale DatabaseScreen; TASK-063 resta comunque non `DONE` per S3-S6 non eseguiti.

---

## Dipendenze

- **TASK-055** `PARTIAL` — contesto audit sync; perimetro remoto/UX senza conflitti di stato
- **TASK-058** `DONE` — meccanismo locale: `_productDetailsOverrides` + `getProductDetailsById` + cap 100 + merge in lista; scroll stabile su update manuale; remoto puntuale esplicitamente **N/A** in quella execution
- **TASK-059** `DONE` — copy/gerarchia indicatori sync; nessun impatto strutturale su propagazione ID post-pull (resta in questo task)

---

## Scopo

Quando un **pull remoto** (bootstrap catalogo, drain `sync_events`, o percorsi equivalenti in `DefaultInventoryRepository`) applica in Room modifiche a prodotti che l’utente potrebbe avere **già in lista** in `DatabaseScreen` (Paging), la UI deve mostrare i dati aggiornati **per soli gli elementi toccati**, **senza** usare `products.refresh()` / refresh globale Paging come strategia primaria e **senza** far saltare lo scroll in cima, né perdere filtro, ricerca o tab attivi.

---

## Obiettivo utente

Se un **altro device** (o stesso account altrove) modifica un prodotto e il dispositivo Android riceve l’aggiornamento via sync, l’elenco prodotti in **Database (hub)** resta coerente con Room **senza** «saltare» la posizione di lettura, **senza** azzerare la ricerca o la tab, e con aggiornamento percepito **puntuale** sulle card interessate (ove identificabili lato locale).

---

## Obiettivo tecnico (contratto)

1. **Propagare** l’insieme (o la sequenza debounced) di **ID prodotto locali** effettivamente mutati a seguito del pull / apply remoto, fino a un punto adatto per la UI **Database** (idealmente `DatabaseViewModel`).
2. Per ciascun `productId` notificato, **ricaricare** `ProductWithDetails` con `InventoryRepository.getProductDetailsById(id)` (stesso percorso già usato in **TASK-058** dopo `updateProduct` locale).
3. **Aggiornare** `MutableStateFlow` `_productDetailsOverrides` in modo coerente con **TASK-058** (stessa funzione di merge/cap, stesso `productDetailsOverrideMutex` o equivalente se si centralizza l’update).
4. **Evitare** `pager`/Paging `.refresh()` come soluzione **primaria**; eventuale `refresh` globale solo come **fallback documentato** (vedi rischi) se gli ID non sono ricavabili o serve recovery dopo stato inconsistente.

---

## Non obiettivi (perimetro esplicito)

- **Nessun** cambio schema Room / migrazioni
- **Nessun** redesign di `DatabaseScreen` o dei composable oltre al wiring minimo se necessario (niente nuova architettura visiva)
- **Nessuna** chiamata Supabase o HTTP dai `@Composable` — restano dietro repository / coordinator / sorgenti dati
- **Nessuna** architettura «cloud-first» nuova o sostituzione del modello Room-first
- **Nessuna** rimozione di Paging 3
- **Nessun** cambio a `NavGraph` / argomenti di navigazione

---

## Non incluso

- Hardening completo `sync_events`, fallback full sync, copy UX «manual full required»: **TASK-061**
- Documentazione / runbook backend Supabase: **TASK-062**
- Smoke live multi-device strutturato: **TASK-063**

---

## Contesto (repo-grounded, lettura pre-planning)

- **`DatabaseViewModel`**: Paging 20 (`repository.getProductsWithDetailsPaged`); mappa `productDetailsOverrides: StateFlow<Map<Long, ProductWithDetails>>` con cap `PRODUCT_DETAILS_OVERRIDE_LIMIT = 100`; dopo `updateProduct` manuale si fa `getProductDetailsById` e `withCappedProductDetailsOverride` (TASK-058).
- **`DatabaseScreen` / `DatabaseScreenComponents`**: le card usano `productDetailsOverrides[details.product.id] ?: details` per il merge presentazionale.
- **`CatalogAutoSyncCoordinator`**: cicli `runPushCycle`, `runBootstrapCycle` (`pullCatalogBootstrapFromRemote` → `pullCatalogFromRemote`), `runSyncEventDrainCycle` (`drainSyncEventsFromRemote`); loggano conteggi `CatalogSyncSummary` ma **non** aggiornano la UI Database.
- **`MerchandiseControlApplication`**: `repository.onProductCatalogChanged` è cablato a `coordinator.onLocalProductChanged(productId)` per **auto-push** dirty — non è un canale di refresh lista Database.
- **`InventoryRepository` / `DefaultInventoryRepository`**: `notifyProductCatalogChanged` oggi è usato per mutazioni locali/price insert ecc.; i percorsi di **apply inbound remoto** (es. `applyCatalogBundleInbound`, `drainSyncEventsInternal` + `applyCatalogEventByIds`) vanno **incrociati in execution** per elencare dove raccogliere gli **ID prodotto locali** dopo commit, senza raddoppiare notifiche push in modo errato (rischio: confondere «locale dirty» con «remoto applicato»).

---

## File probabili da toccare in execution

- `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` — metodo (o subscription) per applicare override puntuali a partire da `Set<Long>` / lista ID; eventuale osservazione di flusso applicativo
- `app/src/main/java/.../data/InventoryRepository.kt` (firma o callback secondo design) — raccolta ID a fine transazione pull/delta, **o** emissione su `SharedFlow`/`callback` dedicato al solo refresh UI
- `app/src/main/java/.../data/CatalogAutoSyncCoordinator.kt` e/o `MerchandiseControlApplication.kt` — punto di attacco post-ciclo sync per inoltrare ID al layer UI (se non tutto vive nel repository)
- `app/src/main/java/.../viewmodel/CatalogSyncViewModel.kt` — solo se il wiring manuale sync deve riallineare lo stesso canale (da valutare: evitare accoppiamento stretto se il repository basta)
- Test: `CatalogAutoSyncCoordinatorTest`, `DatabaseViewModelTest`, eventuali ampliamenti `DefaultInventoryRepositoryTest` se cambia contratto notifiche

**Nota decisionale (planning):** la scelta precisa *callback su repository vs `SharedFlow` vs event bus in Application* va chiusa in fase iniziale di **EXECUTION** con lettura call-site e test di regressione, rispettando **minimo cambiamento** e niente doppia fonte di verità per lo stato Paging.

---

## Piano tecnico proposto (non eseguito in questa fase)

1. **Mappare** in codice i punti in cui un pull remoto **scrive** prodotti/prezzi in Room e ottiene **productId** locale (transazione/e apply già esistenti). Documentare: full bootstrap vs evento per-ID vs gap/full fetch.
2. **Definire** un canale unico «**catalog remote applied → local product IDs**» (nome da definire) che:
   - non invochi in modo fuorviante solo `onLocalProductChanged` (push) se l’intento è solo UI;
   - permetta al `DatabaseViewModel` (quando sottoscritto) di chiamare `getProductDetailsById` e aggiornare `_productDetailsOverrides` in batch o in sequenza con mutex come TASK-058.
3. **Wiring lifecycle**: istanza `DatabaseViewModel` vede il canale (flow da `Application`, `ViewModel` factory, o repository con `Flow` replicato lato `Application` con scope) — scelta in execution con attenzione a memory leak e a **un solo subscriber** consapevole.
4. **Debounce/merge** se in un breve lasso arrivano più pull (stesso `productId` ripetuto): consolidare o `distinct` prima degli override.
5. **Interazione con override locale** (post-edit manuale): stesse regole TASK-058 — un pull remoto successivo **può** sovrascrivere l’override se rappresenta lo stato Room aggiornato; documentare se serve eccezione per «dirty locale non ancora pushato» (coordinare con `syncEventsSkippedDirtyLocal` / policy repository).
6. **Fallback** `pager.refresh()`: ammesso solo in documentazione task con condizione (es. `manualFullSyncRequired`, lista ID vuota ma summary indica mutamento massiccio) e **non** come prima scelta.
7. **Test JVM**: simulare repository/coordinator finti che emettono insiemi di ID; verificare `DatabaseViewModel` aggiorna mappa override senza chiamate Paging refresh.

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|------|
| 1 | Dopo un pull remoto che modifica un prodotto già mappato a `productId` locale noto, le card in lista (se la riga è nel page cache) mostrano `ProductWithDetails` allineato a Room tramite `productDetailsOverrides` o dati Paging coerenti, **senza** `refresh` globale come prima opzione. | S / M | — |
| 2 | La lista **non** salta in cima **per** quel tipo di update puntuale (stesso criterio qualitativo di TASK-058: niente `refresh` Paging forzato per singolo id noto). | S / M | — |
| 3 | Filtro ricerca, testo, tab **hub** e stato navigazione `Database` restano invariati dal solo arrivo remoto. | M | — |
| 4 | Gli ID propagati corrispondono a prodotti realmente toccati dal apply (niente notifiche fantasma) entro i limiti di rilevamento del sync corrente. | S | — |
| 5 | Se un evento remoto **non** consente elenco ID (gap, `manualFullSyncRequired`, ecc.), il comportamento è **definito** (es. nessun override; eventuale messaggio/flag già coperto da altri task; **non** introdire UX nuova se fuori perimetro) e **fallback** full refresh eventuale solo dove documentato. | S / M | — |
| 6 | Nessun accesso rete da composable; ViewModel/Repository restano confini. | S | — |
| 7 | Build `./gradlew :app:assembleDebug` OK; `./gradlew :app:lintDebug` senza nuovi errori bloccanti; `git diff --check` pulito. | B / S | — |
| 8 | Test JVM: coordinator/sync (eventi con ID) + `DatabaseViewModel` (override) + regressione logica **TASK-058** su path locale. | S | — |

---

## Piano test (execution)

- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`
- Test JVM mirati: `CatalogAutoSyncCoordinatorTest`, `DatabaseViewModelTest` (e repository/sync se toccati), più eventuali test **TASK-058** su update locale/override se estesi
- Baseline **TASK-004** se si modificano `DatabaseViewModel` / `DefaultInventoryRepository` (vedi `AGENTS.md`)
- `git diff --check`
- Test manuali / emulator: consigliati per scroll + due device, ma **matrice formale** resta in **TASK-063** se non richiesti come bloccanti qui

---

## Rischi e mitigazioni

| Rischio | Mitigazione proposta |
|--------|------------------------|
| Eventi remoti **senza** ID locali mappabili (gap, bundle incompleto) | Non simulare refresh puntuale; impostare policy documentata; `@manualFullSyncRequired` / TASK-061 per UX; fallback elencato a parte |
| Prodotto aggiornato in Room ma **non** nella finestra Paging attuale | Override comunque in mappa: quando l’utente scrolla, merge TASK-058 mostra dato coerente |
| Conflitto **override** post-edit locale vs pull | Allineare a policy dirty (`skippedDirty` già in drain); in execution valutare se non sovrascrivere override se prodotto ha modifiche locali non ancora inviate (se detectabile) |
| **Pull multipli** ravvicinati | Debounce o merge ID prima di N query `getProductDetailsById` |
| Doppia notifica **push** | Separare canale «UI refresh post-remote» da `onLocalProductChanged` o filtrare cause |
| **Fallback** `refresh` globale | Solo ultima risorsa, con commento e criteri nel file task; non come implementazione predefinita |

---

## Verifica governance (pre-EXECUTION, 2026-04-25)

- Task attivo precedente: **nessuno** (TASK-059 `DONE` secondo `MASTER-PLAN` all’inizializzazione di questo planning).
- **TASK-059**: `DONE`
- **TASK-055**: `PARTIAL`
- **TASK-058**: `DONE`
- **TASK-060**: da `BACKLOG` → `PLANNING` con questo file

---

## Planning (planner / utente)

### Analisi

Il codice oggi risolve **refresh puntuale** per **update manuale** in `DatabaseViewModel` (TASK-058). I cicli remoto in `CatalogAutoSyncCoordinator` **non** collegano gli ID mutati a `_productDetailsOverrides`. Il collegamento `onProductCatalogChanged` → `onLocalProductChanged` **non** soddisfa il requisito UI post-pull. Serve un percorso esplicito **remote-apply → local product ids → ViewModel**.

### Piano di esecuzione (bozza per EXECUTION, da approvare)

1. Inventario chiamate `apply*Inbound` / drain con output di `productId` locali.
2. Progettare canale notifica UI + API `DatabaseViewModel` (es. `onRemoteCatalogPatchApplied(localProductIds: Set<Long>)` interno o subscription).
3. Implementare, test, lint, build, documentare nel log **Execution** eventuali micro-decisioni UX.

### Decisioni

| # | Decisione | Motivazione | Data |
|---|------------|------------|------|
| 1 | Planning inizializzato senza modifica sorgenti Kotlin | Richiesta esplicita utente (solo documentazione) | 2026-04-25 |
| 2 | Canale minimo repository `remoteAppliedProductIds` dedicato alla UI, separato da `onProductCatalogChanged` | Evita doppie notifiche push e non confonde remote-applied con local dirty push | 2026-04-25 |
| 3 | `DatabaseViewModel` sottoscrive il canale e riusa `getProductDetailsById(id)` + `_productDetailsOverrides` con mutex/cap TASK-058 | Mantiene Room/Paging come fonte primaria, evita `products.refresh()` globale e conserva scroll/filtro/tab | 2026-04-25 |

### Sintesi multi-stage pre-EXECUTION — 2026-04-25

**Iteration 1 — architettura minima**
- Preferenza confermata: `DefaultInventoryRepository` emette solo ID locali applicati da remoto tramite un `Flow` dedicato; nessun event bus in `Application`, nessun accesso rete dai composable, nessun nuovo coordinatore UI.
- `DatabaseViewModel` resta il punto di merge presentazionale: riceve batch di ID, ricarica `ProductWithDetails` via repository e aggiorna `_productDetailsOverrides`.
- `onProductCatalogChanged` resta esclusivamente canale locale/dirty per auto-push.

**Iteration 2 — test, regressioni, race condition, TASK-058**
- Riutilizzare lo stesso `productDetailsOverrideMutex` e lo stesso cap `PRODUCT_DETAILS_OVERRIDE_LIMIT = 100`.
- Coprire con test JVM: emissione repository post bootstrap/drain/prezzi, sottoscrizione `DatabaseViewModel`, regressione TASK-058 su update manuale.
- Non indebolire test esistenti su dirty locale: se `localChangeRevision > lastSyncedLocalRevision`, l'apply remoto resta skipped e non deve emettere ID UI.

**Iteration 3 — UX DatabaseScreen**
- Nessun redesign e nessuna modifica a tab, search field, navigation o composable salvo wiring gia' presente.
- Lo scroll resta stabile perche' non si chiama `products.refresh()` come strategia primaria; le card gia' in page cache leggono l'override come in TASK-058.
- Se gli ID non sono disponibili o il drain segnala gap/full manual required, non si crea UX nuova in questo task.

**Piano finale approvato per execution**
1. Aggiungere un canale `Flow<Set<Long>>` nel repository per ID prodotto locali applicati da remoto.
2. Raccogliere gli ID da apply catalogo, tombstone prodotto e price pull remoto dopo commit Room.
3. Far osservare il canale a `DatabaseViewModel` e centralizzare il refresh puntuale degli override.
4. Aggiungere test mirati repository/ViewModel e rieseguire baseline TASK-004 rilevante.

---

## Execution

### Esecuzione — 2026-04-26 — verifica live post-fix TASK-065

**File modificati:**
- `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md` — chiusura basata su evidenza S2 post-fix.

**Azioni eseguite:**
1. Ripetuto S2 in modalita `ACCEPTABLE` dopo fix `record_sync_event`.
2. Su B, mantenuta ricerca `6937962107055` attiva con card target visibile.
3. Su A, modificato prezzo vendita del target `1114` -> `1115`.
4. B ha ricevuto due eventi realtime (`catalog` + `prices`) con `targetedProductsFetched=1`, `targetedPricesFetched=1`, card aggiornata a `Retail (New) 1.115` senza perdere ricerca/posizione.
5. Su A, rollback `1115` -> `1114`.
6. B ha ricevuto rollback con stesso pattern; card target visibile, ricerca preservata, prezzo finale `1.114`.

**Evidenza:**
- UI/XML: `/tmp/task065-live/S2_B_after_1115.xml`, `/tmp/task065-live/S2_B_after_rollback_final.xml`.
- Logcat: `/tmp/task065-live/logcat/S2_B_after_1115.log`, `/tmp/task065-live/logcat/S2_B_after_rollback_final.log`.
- DB finali: `/tmp/task065-live/final/A/app_database`, `/tmp/task065-live/final/B/app_database`.
- Query finale: A/B target `693...7055`, `itemNumber=DM02`, `retailPrice=1114.0`; outbox A/B `0`; watermark A/B `128`.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `:app:assembleDebug` verde in TASK-065 |
| Lint | ✅ ESEGUITO | `:app:lintDebug` verde in TASK-065 |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo |
| Coerenza con planning | ✅ ESEGUITO | Remote apply -> ID locale -> override puntuale; nessun refresh globale osservato |
| Criteri di accettazione | ✅ ESEGUITO | Vedi dettaglio criteri aggiornato |

**Criteri di accettazione — verifica finale:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | Card B aggiornata da Room/override dopo evento remoto sul target; log `targetedProductsFetched=1`, `targetedPricesFetched=1`. |
| 2 | ✅ ESEGUITO | Nessun salto in cima osservato; search target e card visibile preservati. |
| 3 | ✅ ESEGUITO | Ricerca `6937962107055` resta attiva su B durante update e rollback. |
| 4 | ✅ ESEGUITO | Eventi remoti contengono target noto; DB finale A/B coerente. |
| 5 | ✅ ESEGUITO | Gap/full-sync non coinvolto; nessuna UX nuova introdotta. |
| 6 | ✅ ESEGUITO | Nessun accesso rete da composable introdotto. |
| 7 | ✅ ESEGUITO | Build/lint/diff check eseguiti nel ciclo TASK-065. |
| 8 | ✅ ESEGUITO | Baseline JVM mirata eseguita nel ciclo TASK-065; `DatabaseViewModelTest` era gia' verde da execution TASK-060. |

**Baseline regressione TASK-004:**
- Test eseguiti nel ciclo complessivo: `DatabaseViewModelTest` (execution TASK-060), `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`, `SupabaseSyncEventRemoteDataSourceTest`.
- Test aggiunti/aggiornati in questo task: gia' documentati nell'execution 2026-04-25.
- Limiti residui: verifica su due device fisici `FULL` non eseguita; non blocca questo task per decisione scope `ACCEPTABLE`.

**Incertezze:**
- Nessuna bloccante per TASK-060.

**Handoff notes:**
- TASK-063 resta separatamente `BLOCKED` per S3-S6 non eseguiti.

### Esecuzione — 2026-04-25

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — aggiunto canale `remoteAppliedProductIds` e raccolta ID locali realmente applicati da pull catalogo, drain `sync_events`, tombstone prodotto e pull prezzi remoto.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — sottoscrizione del canale repository e refresh puntuale degli override tramite `getProductDetailsById(id)`, riusando mutex/cap TASK-058.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — estesa baseline repository/sync: bootstrap e drain emettono ID locali applicati.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — aggiunto test ViewModel su ID remoto applicato → override puntuale.
- `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md` — documentata sintesi multi-stage, execution, self review e handoff.

**Azioni eseguite:**
1. Completate le tre plan review richieste: architettura minima, test/regressioni/TASK-058, UX `DatabaseScreen`.
2. Implementato `Flow<Set<Long>>` repository dedicato agli ID locali applicati da remoto, separato da `onProductCatalogChanged`.
3. Propagati ID locali da bootstrap/full pull, drain `sync_events`, parent product fetch per price event, tombstone prodotto e insert di price row remota.
4. Agganciato `DatabaseViewModel` al flow: per ogni ID ricarica `ProductWithDetails` e aggiorna `_productDetailsOverrides` senza chiamare `products.refresh()`.
5. Nessuna modifica a composable, Room schema, navigation, DAO API o rete da UI.
6. Tentativo test JVM combinato con piu' classi MockK fallito per `AttachNotSupportedException`/ByteBuddy in questo terminale; le classi mirate sono state rieseguite separatamente con `JAVA_TOOL_OPTIONS` e sono verdi.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL in 2s |
| Lint | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` → BUILD SUCCESSFUL in 26s |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo nel codice modificato; warning Gradle/AGP esistenti non introdotti dal task |
| Coerenza con planning | ✅ ESEGUITO | Implementato canale remote-applied → local product IDs → `DatabaseViewModel` → `getProductDetailsById` → `_productDetailsOverrides`; nessun refresh Paging globale |
| Criteri di accettazione | ✅ ESEGUITO | Vedi dettaglio criteri sotto |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` → BUILD SUCCESSFUL in 11s
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest" --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` → BUILD SUCCESSFUL in 12s
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` → BUILD SUCCESSFUL in 3s
- Test aggiunti/aggiornati:
  - `DatabaseViewModelTest`: nuovo caso remote applied ID → override senza stato UI di successo.
  - `DefaultInventoryRepositoryTest`: bootstrap e drain `sync_events` verificano emissione ID locali applicati.
- Limiti residui:
  - Smoke live multi-device/scroll reale non eseguito; resta nel perimetro TASK-063.
  - Suite combinata multi-classe MockK fallisce in questo terminale per attach ByteBuddy (`AttachNotSupportedException`), mentre le classi mirate rieseguite singolarmente risultano verdi.

**Dettaglio criteri di accettazione:**
| # | Verifica | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | Pull remoto con `productId` locale noto aggiorna card via override, senza refresh globale primario | ESEGUITO | `InventoryRepository.remoteAppliedProductIds`; `DatabaseViewModel` ricarica `getProductDetailsById`; test `DatabaseViewModelTest` verde |
| 2 | Lista non salta in cima per update puntuale | ESEGUITO | Nessuna chiamata `products.refresh()` / `.refresh()` aggiunta; merge override identico a TASK-058 |
| 3 | Filtro, ricerca, tab hub e navigation invariati | ESEGUITO | Nessun composable/navigation modificato; state flow UI esistenti non toccati |
| 4 | ID propagati corrispondono ad apply remoto effettivo | ESEGUITO | ID raccolti solo dopo apply/tombstone/prezzo insert; test repository su bootstrap/drain |
| 5 | Eventi senza ID/gap definiti senza UX nuova | ESEGUITO | Path `manualFullSyncRequired`/gap non emette override se non applica ID; nessun fallback globale introdotto |
| 6 | Nessun accesso rete da composable | ESEGUITO | Rete resta nei remote data source/repository/coordinator; composable non modificati |
| 7 | Build/lint/diff check | ESEGUITO | `assembleDebug` e `lintDebug` verdi; `git diff --check` verde |
| 8 | Test JVM mirati | ESEGUITO | `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest` verdi se eseguiti come sopra |

**Incertezze:**
- INCERTEZZA: il test end-to-end visivo su scroll e multi-device reale resta fuori da questo task ed e' da coprire in TASK-063.

**Handoff notes:**
- Stato suggerito: `REVIEW`, non `DONE`.
- Reviewer: controllare soprattutto che il canale `remoteAppliedProductIds` non venga interpretato come trigger push locale; e' solo refresh UI post-remote.

---

## Review

### Self review — 2026-04-25

**Revisore:** Codex (self review severa)

**Criteri di accettazione:**
| # | Stato | Note |
|---|-------|------|
| 1 | ✅ | Percorso remote apply → local ID → override implementato e testato |
| 2 | ✅ | Nessun refresh Paging globale introdotto |
| 3 | ✅ | Nessun cambio UI/navigation/tab/search |
| 4 | ✅ | ID emessi dopo apply remoto; bridge-only supplier/category non forza override prodotto |
| 5 | ✅ | Gap/manual full non simulano refresh puntuale |
| 6 | ✅ | Confini rete invariati |
| 7 | ✅ | Build/lint/diff check verdi |
| 8 | ✅ | Test mirati verdi con note ambiente MockK |

**Problemi trovati:**
- Warning nuovo da `runCurrent()` nei test repository: risolto con `@OptIn(ExperimentalCoroutinesApi::class)`.
- Ambiente test: comando combinato con piu' classi MockK puo' fallire per attach ByteBuddy; riesecuzione separata verde, quindi non classificato come regressione codice.

**Verdetto:** APPROVED_FOR_REVIEW

**Note per reviewer:**
- Verificare se il reviewer vuole estendere in futuro il canale anche a un coalescing/debounce esplicito; l'implementazione attuale emette batch per operazione sync e il ViewModel deduplica per batch.

---

## Fix

### Fix — 2026-04-25

**Correzioni applicate dopo self review:**
- `DefaultInventoryRepositoryTest`: aggiunto opt-in `ExperimentalCoroutinesApi` per eliminare i warning introdotti dall'uso di `runCurrent()`.

**Fix funzionali richiesti:** nessuno.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-26 |
| Tutti i criteri soddisfatti? | Si |

---

## Riepilogo finale

TASK-060 chiuso dopo evidenza live S2 post-fix: B riceve modifica e rollback remoto sul prodotto target filtrato, la card si aggiorna senza perdita di ricerca o salto di scroll osservato, e il path resta coerente con il canale remote-applied implementato.

---

## Handoff

- **Evidenza TASK-065/TASK-063 2026-04-26:** durante S2 `ACCEPTABLE` post-fix, B era filtrato sul prodotto target `693...7055`; ha ricevuto modifica prezzo A `1114` -> `1115` e successivo rollback `1115` -> `1114` con card visibile aggiornata e senza scroll/search jump osservato. Evidenze in `/tmp/task065-live/`.
- **SOSPESO 2026-04-26 (storico):** stato superato dalla verifica post-fix documentata sopra.
- **Stato corrente:** task `DONE`.
- Verifiche tecniche verdi: `assembleDebug`, `lintDebug`, test JVM mirati separati, `git diff --check`.
- Rischio residuo non bloccante: smoke completo S3-S6 da eseguire in TASK-063; eventuale `FULL` richiede due device reali.
- Nota ambiente: se i test MockK falliscono con `AttachNotSupportedException`, rieseguire con `JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"` e, se necessario, classi separate / `--no-daemon`.
