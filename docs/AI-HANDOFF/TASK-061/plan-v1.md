# TASK-061 — Plan v1 repo-grounded

> Planning-only handoff. Nessuna execution avviata e nessuna modifica a codice applicativo.

---

## 1. Governance check

| Voce | Stato repo-grounded |
|---|---|
| TASK-060 | `REVIEW` nel file `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md`; `MASTER-PLAN.md` lo indica come unico task attivo e bloccante. |
| TASK-061 | `PLANNING` nel file task; `BACKLOG` nel `MASTER-PLAN.md` finché non viene attivato esplicitamente. |
| Execution TASK-061 | **Bloccata**: TASK-061 può restare solo in planning. Non passare a `EXECUTION` finché TASK-060 non viene chiuso, sospeso o superato da decisione utente documentata. |
| Modifiche ammesse in questo handoff | Solo documentazione in `docs/AI-HANDOFF/TASK-061/plan-v1.md`. |

Nota: il file task TASK-061 già contiene la stessa regola governance; questo documento la conferma dopo lettura di `MASTER-PLAN.md`, `TASK-061`, `TASK-060`, `CLAUDE.md`, `AGENTS.md` e `docs/CODEX-EXECUTION-PROTOCOL.md`.

---

## 2. Findings repo-grounded

### File letti

Governance:
- `docs/MASTER-PLAN.md`
- `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md`
- `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md`
- `CLAUDE.md`
- `AGENTS.md`
- `docs/CODEX-EXECUTION-PROTOCOL.md`

Codice e test:
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryCatalogRemoteRows.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`
- `app/src/main/res/values*/strings.xml` per le stringhe `catalog_cloud_*`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt`

### Flusso attuale `sync_events`

1. `SupabaseSyncEventRemoteDataSource` espone:
   - `checkCapabilities(ownerUserId)`: prova una `select` su tabella `sync_events`; in caso di errore torna capabilities disabled con reason `sync_events_schema_or_rls_unavailable`.
   - `recordSyncEvent(...)`: RPC `record_sync_event`.
   - `fetchSyncEventsAfter(...)`: query owner-scoped, `id > watermark`, `store_id` null o specifico, ordine ascendente.

2. `SyncEventModels.kt` definisce:
   - `SyncEventEntityIds` con `supplierIds`, `categoryIds`, `productIds`, `priceIds`, `totalIds`, `isEmpty`.
   - `SyncEventRemoteRow` con `changedCount`, `entityIds`, `sourceDeviceId`, `clientEventId`.
   - Room locale per `sync_event_watermarks`, `sync_event_device_state`, `sync_event_outbox`.

3. `InventoryRepository.syncCatalogQuickWithEvents(...)`:
   - controlla capabilities remote; se `sync_events` o RPC non sono disponibili torna alla lane push-only (`pushDirtyCatalogDeltaToRemote`) e marca summary con `syncEventsFallback044`, `syncEventsDisabled`, capabilities e `syncEventOutboxPending`.
   - crea/usa `deviceId`, legge watermark, ritenta `sync_event_outbox`.
   - push di suppliers/categories/products/prices.
   - registra eventi catalogo/prezzi tramite `recordOrEnqueueSyncEvent`; gli ID sono remote ID, chunkati con budget `SYNC_EVENT_ENTITY_ID_BUDGET = 250`.
   - drena eventi remoti con `drainSyncEventsInternal(...)`.
   - produce `CatalogSyncSummary` con conteggi event/outbox/watermark, `syncEventsTooLarge`, `syncEventsGapDetected`, `manualFullSyncRequired`.

4. `InventoryRepository.drainSyncEventsFromRemote(...)`:
   - se il datasource non è configurato o `syncEventsAvailable` è false, restituisce summary disabled/fallback senza full pull nascosto.
   - se disponibile, ritenta outbox, drena eventi, logga summary, notifica gli ID prodotto locali applicati a valle di TASK-060.

5. `InventoryRepository.drainSyncEventsInternal(...)`:
   - fetch a pagine da `SYNC_EVENT_FETCH_LIMIT = 100`, massimo `SYNC_EVENT_DRAIN_MAX_ITERATIONS = 20`.
   - salta eventi self avanzando watermark.
   - se `entityIds == null` oppure `ids.isEmpty && changedCount > 0`, marca `gapDetected = true`, `manualFullSyncRequired = true`, avanza watermark.
   - se `ids.totalIds > 250`, marca `tooLarge = true`, `manualFullSyncRequired = true`, avanza watermark.
   - al raggiungimento del limite iterazioni marca `gapDetected = true`, `manualFullSyncRequired = true`.
   - per eventi validi usa fetch mirato: `fetchCatalogByIds(...)` e `fetchProductPricesByIds(...)`.

6. `CatalogAutoSyncCoordinator`:
   - `runPushCycle(...)` usa `syncCatalogQuickWithEvents(...)` quando `syncEventRemote.isConfigured`, altrimenti push-only.
   - `runSyncEventDrainCycle(...)` usa `drainSyncEventsFromRemote(...)`.
   - usa `CatalogSyncStateTracker` solo per single-flight/progress (`AUTO_PUSH`, `BOOTSTRAP`, `SYNC_EVENTS`) e logga parte del summary.
   - non propaga il `CatalogSyncSummary` automatico verso lo stato usato da Options.

7. `CatalogSyncViewModel` / `OptionsScreen`:
   - `lastCatalogSyncSummary` è privato nel ViewModel ed è aggiornato da `refreshCatalog()` e `syncCatalogQuick()`.
   - `manualFullSyncRequired` alimenta `fullSyncRecommended`, badge `catalog_cloud_badge_full_required`, detail `catalog_cloud_manual_full_sync_required_hint`, e highlight dell'azione full sync in `OptionsScreen`.
   - `onOptionsScreenVisible()` aggiorna pending locale/sessioni, ma non legge l'ultimo summary automatico del coordinator.

### Cosa è già implementato

- Remote data source `sync_events` e RPC `record_sync_event`.
- Watermark owner-scoped, device id locale, outbox locale con retry e max attempts.
- Quick sync con eventi, chunk ID, fallback push-only se capabilities/RPC non disponibili.
- Drain mirato catalogo/prezzi con avanzamento watermark controllato.
- Flag summary già presenti: `syncEventsTooLarge`, `syncEventsGapDetected`, `syncEventOutboxPending`, `syncEventOutboxRetried`, `manualFullSyncRequired`.
- UX Options già pronta per mostrare "Serve sincronizzazione completa" quando il ViewModel riceve un summary con `manualFullSyncRequired = true`.
- Stringhe localizzate già presenti per hint manual full sync e outbox retry.

### Cosa è sotto-testato

- `DefaultInventoryRepositoryTest` copre emissione eventi catalogo/prezzi, batch, capabilities false, drain mirato, outbox retry e watermark dopo apply riuscito.
- Mancano test espliciti per:
  - evento con `entityIds = null` e `changedCount > 0`;
  - evento con `entityIds` vuoti e `changedCount > 0`;
  - evento con `totalIds > SYNC_EVENT_ENTITY_ID_BUDGET`;
  - limite `SYNC_EVENT_DRAIN_MAX_ITERATIONS`;
  - assert diretti su `manualFullSyncRequired`, `syncEventsGapDetected`, `syncEventsTooLarge` e avanzamento watermark nei fallback controllati;
  - `drainSyncEventsFromRemote(...)` con capabilities/schema/RLS non disponibili.
- `CatalogSyncViewModelTest` oggi non ha assert diretti su `manualFullSyncRequired -> fullSyncRecommended`, badge full-required e detail/hint full sync.
- I test quick sync del ViewModel usano la lane `pushDirtyCatalogDeltaToRemote`; non coprono il ramo `syncCatalogQuickWithEvents(...)` con `syncEventRemote` configurato.
- `CatalogAutoSyncCoordinatorTest` non copre `runSyncEventDrainCycle(...)`, né un `runPushCycle(...)` con `syncEventRemote` configurato, né propagazione summary automatico a uno stato osservabile.

### Cosa non viene comunicato bene in UX

- La UI comunica bene il caso `manualFullSyncRequired` solo quando il summary arriva al `CatalogSyncViewModel` tramite azione manuale.
- Se `manualFullSyncRequired` nasce da `CatalogAutoSyncCoordinator.runSyncEventDrainCycle(...)` o da auto quick/push, il coordinator logga il risultato ma non aggiorna `lastCatalogSyncSummary`; Options può restare senza raccomandazione full sync finché l'utente non avvia una sync manuale.
- Le cause tecniche (`gap`, `tooLarge`, max iterations, outbox pending) non devono diventare gergo user-facing, ma devono convergere in un segnale coerente: "Serve sincronizzazione completa" e azione full evidenziata.
- `syncEventOutboxPending` è visibile nel detail se presente nel summary, ma lo stesso problema di propagazione vale per i summary automatici.

---

## 3. Piano tecnico v1

### Principi

- Non iniziare execution finché TASK-060 resta `REVIEW` bloccante.
- Nessun cambio schema Room/DAO/migrazioni salvo nuova decisione documentata.
- Nessuna chiamata rete dai composable.
- Coordinator e ViewModel non devono conoscersi direttamente.
- `sync_events` resta ottimizzazione delta/catch-up; Room e full sync restano la recovery affidabile.

### Step ordinati

1. **Pre-flight governance**
   - Rileggere `MASTER-PLAN.md`, TASK-061 e TASK-060.
   - Se TASK-060 è ancora `REVIEW`, fermarsi: solo planning, niente codice.
   - Se TASK-061 viene attivato, aggiornare il file task secondo protocollo prima della execution.

2. **Aggiungere test repository prima/insieme alla logica**
   - In `DefaultInventoryRepositoryTest` aggiungere casi mirati:
     - gap `entityIds = null`, `changedCount = 1` -> `manualFullSyncRequired`, `syncEventsGapDetected`, watermark avanzato, nessun fetch mirato.
     - gap `SyncEventEntityIds()` vuoto, `changedCount = 1` -> stesso esito.
     - too-large con `productIds.size = 251` -> `manualFullSyncRequired`, `syncEventsTooLarge`, watermark avanzato, nessun fetch mirato.
     - max iterations: fake remote con 20 pagine piene da 100 eventi self o controllati -> `syncEventsFetched = 2000`, `manualFullSyncRequired`, `syncEventsGapDetected`, watermark coerente.
     - capabilities false su `drainSyncEventsFromRemote(...)` -> summary disabled/fallback senza full pull.
   - Non indebolire i test esistenti su outbox/watermark.

3. **Definire una sola sorgente osservabile per summary automatici**
   - Soluzione preferita: estendere `CatalogSyncStateTracker` con un flusso app-scoped dell'ultimo `CatalogSyncSummary` rilevante, per esempio `lastSummary: StateFlow<CatalogSyncSummary?>` e metodo `publishSummary(summary, source)`.
   - `CatalogAutoSyncCoordinator` pubblica il summary dopo `runPushCycle(...)` e `runSyncEventDrainCycle(...)` riusciti.
   - `CatalogSyncViewModel` usa lo stesso flusso quando il tracker è disponibile; la `MutableStateFlow` privata resta solo fallback/test o viene aggiornata tramite un helper unico.
   - Regola anti doppia fonte: un solo helper aggiorna il summary; i test devono fallire se manuale e automatico divergono nel ViewModel.

4. **Propagare i flag hardening senza accoppiare coordinator e VM**
   - In `CatalogAutoSyncCoordinator`, dopo ogni summary automatico riuscito, pubblicare almeno:
     - `manualFullSyncRequired`
     - `syncEventsGapDetected`
     - `syncEventsTooLarge`
     - `syncEventOutboxPending`
     - `syncEventsAvailable` / fallback flags
   - Arricchire i log coordinator con `manualFullSyncRequired`, `syncEventsGapDetected`, `syncEventsTooLarge`, `syncEventOutboxRetried` per debug TASK-063.
   - Evitare callback dirette verso `CatalogSyncViewModel`.

5. **Aggiornare ViewModel / Options**
   - In `CatalogSyncViewModelTest`, coprire:
     - summary manual quick con `manualFullSyncRequired = true` -> `uiState.fullSyncRecommended == true`, `quickSyncRecommended == false`, badge full-required, detail con `catalog_cloud_manual_full_sync_required_hint`;
     - summary automatico pubblicato nel tracker -> Options state raccomanda full sync senza azione manuale;
     - full refresh successivo con summary full senza `manualFullSyncRequired` -> raccomandazione full sync rimossa;
     - outbox pending nel summary automatico -> detail mostra hint retry.
   - `OptionsScreen.kt` dovrebbe idealmente restare invariato se `CatalogSyncUiState` è già corretto. Toccarlo solo se serve a esporre uno stato già presente nel ViewModel.
   - Evitare nuove stringhe se le attuali bastano; se si distinguono cause in copy, aggiornare IT/EN/ES/ZH insieme.

6. **Aggiornare coordinator test**
   - In `CatalogAutoSyncCoordinatorTest`, estendere fake repository per override di:
     - `syncCatalogQuickWithEvents(...)`
     - `drainSyncEventsFromRemote(...)`
   - Testare:
     - `runPushCycle(...)` con `syncEventRemote` configurato pubblica summary nel tracker;
     - `runSyncEventDrainCycle(...)` pubblica summary nel tracker;
     - busy/signed-out/remote-unconfigured non pubblicano summary nuovo;
     - log contiene i flag critici, se il logger resta il canale di osservabilità debug.

7. **Valutare `InventoryRepository.kt` solo se i test rivelano gap reali**
   - La logica di fallback sembra già presente.
   - Possibili fix minimi, se necessari:
     - rendere più esplicito `manualFullSyncRequired` nel log `sync_events_summary`;
     - assicurare che max-iterations non produca falsi positivi inattesi;
     - correggere eventuale incongruenza tra `watermarkBefore` usato in quick summary e drain summary.
   - Nessun refactor ampio del repository.

8. **Check finali obbligatori in execution futura**
   - `./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"`
   - `./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"`
   - `./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"`
   - Se il perimetro tocca piu aree o i test mirati non bastano: `./gradlew testDebugUnitTest` oppure `./gradlew test`.
   - `./gradlew assembleDebug`
   - `./gradlew lint`
   - `git diff --check`
   - Non dichiarare smoke live/device/emulator se non eseguiti con evidenza.

### File da toccare in execution futura

Probabili:
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt`

Solo se necessario:
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt`
- `app/src/main/res/values*/strings.xml`

Da trattare principalmente come lettura/contesto:
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`

---

## 4. Rischi

| Rischio | Dettaglio | Mitigazione |
|---|---|---|
| Accoppiamento VM/coordinator/tracker | Il coordinator è application-scoped; il ViewModel è UI-scoped. Collegarli direttamente creerebbe ownership fragile. | Pubblicare summary su `CatalogSyncStateTracker` o canale app-scoped minimale; niente reference a VM dal coordinator. |
| Doppia fonte di verità | `lastCatalogSyncSummary` oggi vive nel ViewModel; aggiungere summary nel tracker può creare due stati divergenti. | Helper unico di publish/merge; ViewModel usa tracker come sorgente se disponibile, fallback locale solo in assenza tracker/test. |
| Falsi positivi full sync | Max-iterations o eventi self potrebbero raccomandare full sync anche quando il drain sta solo processando coda lunga. | Testare esattamente watermark, numero eventi e condizione limite; mantenere copy non allarmista ma chiara. |
| Regressioni watermark | Cambi a drain possono bloccare la coda o saltare eventi applicabili. | Test gap/tooLarge/max-iteration devono assertare watermark; non cambiare avanzamento watermark senza motivazione. |
| Regressioni outbox | Retry o record failure potrebbero perdere notifiche pending o duplicarle. | Tenere/estendere test outbox esistenti; non cambiare unique `clientEventId` o chunking senza test. |
| UX troppo tecnica | Esporre `sync_events`, `watermark`, RLS, outbox in UI violerebbe TASK-059. | Mappare tutte le cause a "Serve sincronizzazione completa"; dettagli tecnici solo log/test/handoff. |
| Smoke live dichiarato senza prova | TASK-061 non deve assorbire TASK-063. | I criteri devono distinguere test JVM/build/lint da smoke live opzionali/non eseguiti. |

---

## 5. Criteri di accettazione migliorati

| # | Criterio | Verifica attesa | Stato iniziale |
|---|---|---|---|
| 1 | `DefaultInventoryRepositoryTest` verifica gap `entityIds = null` / IDs vuoti con `changedCount > 0`: `manualFullSyncRequired = true`, `syncEventsGapDetected = true`, watermark avanzato, nessun full pull nascosto. | JVM/Robolectric mirato | Da implementare |
| 2 | `DefaultInventoryRepositoryTest` verifica evento oltre budget ID: `syncEventsTooLarge = true`, `manualFullSyncRequired = true`, watermark avanzato, fetch mirato non invocato. | JVM/Robolectric mirato | Da implementare |
| 3 | `DefaultInventoryRepositoryTest` verifica max drain iterations: flag full-sync-required/gap coerenti, conteggi e watermark documentati. | JVM/Robolectric mirato | Da implementare |
| 4 | `DefaultInventoryRepositoryTest` mantiene verde outbox retry, capability false, drain mirato e watermark-after-success già esistenti. | Regressione JVM | Esistente, da non rompere |
| 5 | `CatalogSyncViewModelTest` verifica `manualFullSyncRequired -> fullSyncRecommended`, badge full-required, hint "Serve sincronizzazione completa", quick non raccomandata. | Robolectric ViewModel | Da implementare |
| 6 | `CatalogSyncViewModelTest` verifica che un summary automatico pubblicato dal tracker arrivi allo stato Options senza richiedere sync manuale. | Robolectric ViewModel | Da implementare |
| 7 | `CatalogAutoSyncCoordinatorTest` copre `runPushCycle` con `syncCatalogQuickWithEvents` e `runSyncEventDrainCycle`, inclusa pubblicazione summary o stato equivalente. | Robolectric/unit | Da implementare |
| 8 | `OptionsScreen` non contiene rete/logica sync; riceve solo `CatalogSyncUiState`. Full sync è evidenziata quando `state.fullSyncRecommended` è true. | Static/build + review codice | In gran parte già vero |
| 9 | Copy user-facing resta non tecnico: niente `sync_events`, `watermark`, `outbox`, RLS o nomi RPC in stringhe visibili. | Static/search risorse | Da verificare se si toccano stringhe |
| 10 | Build e static checks verdi: `assembleDebug`, `lint`, nessun warning Kotlin nuovo nel codice modificato. | Build/static | Da eseguire in execution futura |
| 11 | Baseline TASK-004 documentata nel log Execution: test repository/ViewModel/coordinator eseguiti, test aggiunti/aggiornati elencati, limiti residui dichiarati. | Governance | Da eseguire in execution futura |
| 12 | Nessuno smoke live/device/emulator dichiarato se non eseguito con evidenza; eventuale smoke multi-device resta TASK-063. | Handoff/review | Da rispettare |

---

## Handoff per il prossimo operatore

- Questo documento non autorizza execution: prima serve risolvere la governance di TASK-060.
- La logica repository sembra già in gran parte presente; il valore del task è soprattutto testare i branch limite e propagare il summary automatico verso Options senza creare una seconda fonte di verità.
- La scelta architetturale da prendere con cautela è dove far vivere l'ultimo `CatalogSyncSummary` automatico. Preferenza v1: estendere `CatalogSyncStateTracker` come canale app-scoped leggero, non collegare coordinator e ViewModel direttamente.
