# TASK-061 — Plan final

Data: 2026-04-26
Ruolo: Codex Planner Final C
Scope di questo handoff: solo planning, nessuna execution, nessun codice applicativo modificato.

**Nota 2026-04-26 — superseded by user override:** il gate sotto rimane come storico del piano finale originale. Successivamente l'utente ha sospeso TASK-060 / `BLOCKED` e autorizzato TASK-061 a `EXECUTION`; lo stato corrente va letto in `docs/MASTER-PLAN.md` e `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md`.

## 1. Governance Gate

**Execution blocked by governance — superseded by user override**

- `docs/MASTER-PLAN.md` indica ancora **TASK-060** come unico task attivo in `REVIEW` e bloccante.
- `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md` mantiene **TASK-061** in `PLANNING`.
- Questo piano finale non autorizza `EXECUTION`.
- Non promuovere TASK-061 a task attivo e non modificare Kotlin/XML/Gradle finche TASK-060 non viene chiuso, sospeso o superato da decisione utente documentata nel master plan.
- Quando la governance viene sbloccata, l'Executor deve ripetere il gate leggendo: `docs/MASTER-PLAN.md`, task TASK-061, `CLAUDE.md`, `AGENTS.md`, `docs/CODEX-EXECUTION-PROTOCOL.md`.

## 2. Obiettivo TASK-061

Rendere robusti, testati e comunicati i casi limite del percorso `sync_events`:

- gap di watermark o eventi senza ID utilizzabili;
- eventi troppo grandi per il budget ID;
- raggiungimento del limite massimo di drain;
- schema/RLS/RPC `sync_events` non disponibile o capability false;
- outbox locale pending/retry;
- propagazione affidabile di `manualFullSyncRequired`.

Esito atteso per l'utente: se quick/delta sync non basta a garantire coerenza, Options deve raccomandare una sync completa usando lo stato e il copy gia esistenti, senza redesign.

## 3. Non-obiettivi

- Non fare redesign della sezione cloud di `OptionsScreen`.
- Non introdurre una nuova architettura cloud-first.
- Non modificare schema Room, DAO, `AppDatabase`, migration, RPC Supabase, DDL o policy RLS.
- Non introdurre nuove dipendenze.
- Non aggiungere rete o business logic nei composable.
- Non trasformare TASK-061 in smoke live multi-device: quello resta separato da TASK-063.
- Non dichiarare emulator/device/live smoke se non eseguiti con evidenza.
- Non introdurre refresh full impliciti per nascondere problemi `sync_events`: TASK-061 deve raccomandare la full sync quando serve, non farla partire di nascosto.
- Non toccare il flusso TASK-060 di refresh puntuale DatabaseScreen salvo stretta necessita dimostrata dai test.

## 4. Strategia tecnica finale

### Direzione scelta

Usare **`CatalogSyncStateTracker` come canale app-scoped minimale anche per l'ultimo outcome catalog sync**, perche e gia cablato in:

- `MerchandiseControlApplication.catalogSyncStateTracker`;
- `CatalogAutoSyncCoordinator`;
- `CatalogSyncViewModel`;
- root UI via `NavGraph`.

La modifica deve restare piccola: il tracker non deve diventare uno store globale generico. Deve solo esporre un outcome owner-scoped dell'ultimo `CatalogSyncSummary` rilevante.

### Modello consigliato

In `CatalogSyncStateTracker.kt`, aggiungere un piccolo stato, ad esempio:

- `CatalogSyncOutcomeState`
  - `ownerUserId: String`
  - `source: CatalogSyncFlightOwner`
  - `summary: CatalogSyncSummary`
  - `updatedAtMs: Long`
- `lastOutcome: StateFlow<CatalogSyncOutcomeState?>`
- `publishSummary(ownerUserId, source, summary, nowMs = System.currentTimeMillis())`
- `clearSummary(ownerUserId: String? = null)`

Se l'Executor trova un nome migliore coerente con il codice, puo usarlo, ma la semantica deve restare questa.

### Regole anti doppia fonte di verita

- `CatalogSyncViewModel` deve leggere l'outcome dal tracker quando il tracker e disponibile.
- La `MutableStateFlow` locale nel ViewModel puo restare solo fallback per test o istanze senza tracker.
- Manual refresh e manual quick devono pubblicare il summary tramite un helper unico, non scrivere in due punti divergenti.
- Il ViewModel deve ignorare outcome con `ownerUserId` diverso dall'utente firmato.
- In stato signed-out, l'UI non deve mostrare summary/outbox/full-sync-required di un utente precedente.

### Regole di merge/clear

- Manual quick riuscita:
  - pubblica summary con source `MANUAL`;
  - imposta `incrementalDetailSurface = AFTER_QUICK_SUCCESS`.
- Manual full refresh riuscito:
  - pubblica summary con source `MANUAL`;
  - imposta `incrementalDetailSurface = OTHER`;
  - se il summary non richiede `manualFullSyncRequired`, sovrascrive e quindi cancella la precedente raccomandazione full sync.
- Auto push con `syncCatalogQuickWithEvents` riuscito:
  - pubblica summary con source `AUTO_PUSH`;
  - non deve impostare superficie quick manuale.
- Auto drain con `drainSyncEventsFromRemote` riuscito:
  - pubblica summary con source `SYNC_EVENTS`;
  - non deve impostare superficie quick manuale.
- Failure, skip per busy, signed-out, background policy o remote unconfigured:
  - non devono pubblicare un nuovo summary.
- Capability false:
  - resta fallback controllato;
  - non deve diventare automaticamente `manualFullSyncRequired` se il repository non lo indica.

### UX finale

- `OptionsScreen.kt` deve idealmente restare invariato.
- La raccomandazione deve passare da `CatalogSyncUiState.fullSyncRecommended`.
- Il detail deve usare le stringhe esistenti, in particolare `catalog_cloud_manual_full_sync_required_hint` e `catalog_cloud_sync_event_outbox_hint`.
- Non esporre termini tecnici user-facing come `sync_events`, `watermark`, `outbox`, RLS o `record_sync_event`.
- Toccare `OptionsScreen.kt` solo se uno stato necessario non e rappresentabile con `statusBadges`, `catalogDetail`, `quickSyncRecommended`, `fullSyncRecommended` e gli action block gia presenti.

### Osservabilita tecnica

- In `InventoryRepository.logSyncEventSummary`, aggiungere `manualFullSyncRequired=${drain.manualFullSyncRequired}`.
- Nei log `CatalogAutoSyncCoordinator` per `catalog_push` e `sync_events_drain`, includere:
  - `manualFullSyncRequired`;
  - `syncEventsGapDetected`;
  - `syncEventsTooLarge`;
  - `syncEventOutboxRetried`;
  - `syncEventOutboxPending`.
- I log sono supporto debug, non sostituiscono test.

## 5. Step execution piccoli

Eseguire questi step solo dopo sblocco governance.

1. **Pre-flight**
   - Rileggere governance e confermare che TASK-061 sia attivo.
   - Documentare in `Execution` del file task che TASK-060 non e piu bloccante.
   - Non iniziare codice se il gate non e chiaro.

2. **Repository tests prima dei fix**
   - Aggiungere in `DefaultInventoryRepositoryTest` test per gap null/empty IDs, too-large, max iterations, capability false drain, record RPC unavailable.
   - Se passano gia, lasciare la logica repository quasi intatta.
   - Se falliscono, applicare solo fix minimi in `InventoryRepository.kt`.

3. **Outcome channel nel tracker**
   - Estendere `CatalogSyncStateTracker.kt` con outcome owner-scoped.
   - Non rompere `tryBegin`, `finish`, `state`, `isSyncing`.
   - Aggiungere o aggiornare test coordinator/tracker per verificare che progress e single-flight restino invariati.

4. **ViewModel come consumer dello stato**
   - In `CatalogSyncViewModel.kt`, sostituire l'uso diretto esclusivo di `lastCatalogSyncSummary` con un helper/sorgente unica:
     - tracker outcome se disponibile e owner coerente;
     - fallback locale se tracker assente.
   - Manual quick/full pubblicano summary tramite helper.
   - Summary automatici non impostano `AFTER_QUICK_SUCCESS`.

5. **Coordinator come publisher**
   - In `CatalogAutoSyncCoordinator.runPushCycle`, dopo `syncCatalogQuickWithEvents` riuscito, pubblicare summary su tracker con source `AUTO_PUSH`.
   - In `runSyncEventDrainCycle`, dopo drain riuscito, pubblicare summary con source `SYNC_EVENTS`.
   - Non pubblicare su failure o skip.
   - Arricchire i log con i flag hardening.

6. **InventoryRepository log minimo**
   - Aggiungere `manualFullSyncRequired` al log `sync_events_summary`.
   - Evitare refactor del drain, dell'outbox, del watermark o del chunking se i test non lo richiedono.

7. **UX check**
   - Verificare via ViewModel test che Options riceva `fullSyncRecommended=true` quando l'outcome automatico lo richiede.
   - Non modificare `OptionsScreen.kt` salvo necessita provata.
   - Non aggiungere stringhe se il copy esistente basta.

8. **Verifiche finali**
   - Eseguire test mirati repository/ViewModel/coordinator.
   - Eseguire baseline TASK-004 applicabile.
   - Eseguire build/lint e `git diff --check`.
   - Documentare ogni check nel file task, distinguendo cio che e stato eseguito da cio che non e applicabile.

## 6. File da modificare

Probabili in execution:

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt`
  - outcome owner-scoped e metodi publish/clear.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt`
  - consumo outcome tracker, helper unico publish/merge, owner filtering, clear semantico via full refresh.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt`
  - publish summary automatici e log hardening.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
  - solo log `manualFullSyncRequired` o fix minimi se i test repository evidenziano gap reali.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
  - test gap/too-large/max-iterations/capability/outbox.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt`
  - test UI state per summary manuale e automatico.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt`
  - test publish summary automatici, skip/failure, tracker invariants.

Solo se necessario:

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt`
  - solo se lo stato esistente non basta, senza redesign.
- `app/src/main/res/values*/strings.xml`
  - solo se serve micro-copy non tecnico in tutte le lingue.

Da non modificare per TASK-061:

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt`
- DAO e Room entities di watermark/outbox, salvo bug grave e decisione documentata.
- `app/build.gradle.kts`, Gradle catalog, manifest.
- Supabase SQL/RPC/DDL/policy RLS.

Nota wiring: `MerchandiseControlApplication` gia passa lo stesso `catalogSyncStateTracker` a coordinator e ViewModel; non dovrebbe servire nuovo wiring applicativo.

## 7. Test plan

### Repository: `DefaultInventoryRepositoryTest`

Aggiungere test mirati:

- `drain sync events marks manual full sync for null entity ids`
  - evento remoto con `entityIds = null`, `changedCount > 0`;
  - attesi: `manualFullSyncRequired=true`, `syncEventsGapDetected=true`, watermark avanzato, nessun fetch full nascosto.
- `drain sync events marks manual full sync for empty ids with changed count`
  - `SyncEventEntityIds()` vuoto e `changedCount > 0`;
  - stessi attesi.
- `drain sync events ignores empty ids with zero changed count`
  - controllo negativo: niente falso positivo full sync.
- `drain sync events marks too large when id budget exceeded`
  - oltre `SYNC_EVENT_ENTITY_ID_BUDGET` usando, per esempio, 251 product IDs;
  - attesi: `syncEventsTooLarge=true`, `manualFullSyncRequired=true`, watermark avanzato, fetch mirato non chiamato.
- `drain sync events marks manual full sync at max iterations`
  - fake remote con abbastanza eventi da raggiungere `SYNC_EVENT_DRAIN_MAX_ITERATIONS`;
  - preparare un device id locale noto se si usano eventi self, oppure usare eventi controllati che non innescano fetch mirati costosi;
  - attesi: `manualFullSyncRequired=true`, `syncEventsGapDetected=true`, watermark/conteggi coerenti.
- `drain sync events capability false returns disabled fallback without hidden pull`
  - `syncEventsAvailable=false`;
  - attesi: summary disabled/fallback, nessun full pull.
- `quick sync record rpc unavailable falls back push only`
  - `recordSyncEventAvailable=false`;
  - attesi: `syncEventsDisabled=true`, `syncEventsFallback044=true`, pending outbox preservato, nessuna RPC riuscita richiesta.
- Mantenere verdi i test esistenti su targeted drain, outbox retry e watermark-after-success.

### ViewModel: `CatalogSyncViewModelTest`

Aggiungere test:

- manual quick con `syncEventRemote` configurato e summary `manualFullSyncRequired=true`;
  - attesi: `fullSyncRecommended=true`, quick non raccomandata, badge/hint full-required.
- outcome automatico tracker con `manualFullSyncRequired=true`;
  - attesi: Options state raccomanda full sync senza azione manuale e senza copy quick manuale.
- outcome automatico tracker con `syncEventOutboxPending>0`;
  - attesi: detail/hint outbox visibile.
- full refresh manuale riuscito dopo fallback;
  - attesi: raccomandazione full sync precedente rimossa.
- logout/cambio utente;
  - attesi: nessun summary/outbox/fallback del vecchio owner visibile.
- capability false summary;
  - attesi: nessun loop/falso full-sync-required se `manualFullSyncRequired=false`.

### Coordinator / Tracker: `CatalogAutoSyncCoordinatorTest`

Estendere fake repository per override di:

- `syncCatalogQuickWithEvents`;
- `drainSyncEventsFromRemote`.

Aggiungere test:

- `runPushCycle` con `syncEventRemote` configurato usa `syncCatalogQuickWithEvents` e pubblica outcome `AUTO_PUSH`.
- `runSyncEventDrainCycle` pubblica outcome `SYNC_EVENTS`.
- skip per manual busy, no auth, background, remote unconfigured non pubblicano outcome nuovo.
- failure repository non pubblica outcome nuovo e lascia `isSyncing=false` a fine ciclo.
- progress/single-flight del tracker restano funzionanti.
- log coordinator contiene `manualFullSyncRequired`, gap, too-large, outbox retried/pending.

### Comandi futuri

Da eseguire in execution futura, non in questa fase planning:

```bash
./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"
./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"
./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lint
git diff --check
```

Se il repo usa `lintDebug` come check locale pratico, eseguirlo pure, ma il log deve chiarire quale comando e stato davvero eseguito.

## 8. Criteri di accettazione

1. Governance: TASK-061 entra in execution solo dopo sblocco esplicito di TASK-060.
2. Repository: test JVM/Robolectric coprono gap ID null/empty, controllo negativo empty+0, too-large, max iterations, capability false drain, RPC unavailable fallback.
3. Repository: outbox retry, targeted drain e watermark-after-success esistenti restano verdi.
4. Tracker: outcome owner-scoped pubblicabile/clearable senza rompere `state`, `isSyncing`, `tryBegin`, `finish`.
5. Coordinator: auto push/drain pubblicano summary solo su successo e loggano manual/gap/too-large/outbox.
6. ViewModel: `manualFullSyncRequired=true` da summary manuale o automatico produce `fullSyncRecommended=true`.
7. ViewModel: full refresh riuscito cancella una precedente raccomandazione fallback quando il nuovo summary non richiede full sync.
8. ViewModel: owner filtering impedisce leakage tra signed-out/user switch.
9. UX Options: full sync fallback e outbox pending sono comunicati con stato esistente, senza redesign e senza gergo tecnico user-facing.
10. Scope: nessun cambio schema/DAO/migration/Gradle/Supabase DDL-RPC-policy.
11. Build/static: `assembleDebug`, `lint` e test mirati/full baseline applicabile documentati in `Execution`.
12. Nessuno smoke live/device/emulator dichiarato senza prova; eventuali verifiche live restano TASK-063 o evidenza separata.

## 9. Handoff per Executor

- Fermati subito se `MASTER-PLAN.md` indica ancora TASK-060 in `REVIEW` bloccante.
- Quando il gate si apre, lavora con patch piccole e test-first sui casi repository.
- La logica repository per `manualFullSyncRequired` sembra gia quasi tutta presente; il rischio principale e la propagazione dello stato automatico verso Options.
- Usa il tracker esistente, non creare accoppiamenti diretti coordinator -> ViewModel.
- Mantieni `OptionsScreen` come rendering di `CatalogSyncUiState`.
- Non introdurre un nuovo store globale generico.
- Il test max-iterations va costruito con attenzione: se usi eventi self, prepara un device id locale noto; se usi eventi non-self, evita fetch mirati costosi o indesiderati.
- Documenta nel file task ogni file modificato, ogni test aggiunto, ogni check eseguito e ogni limite residuo.

## 10. Handoff per Reviewer

Controlli prioritari:

- Governance: TASK-061 non deve essere stato avviato mentre TASK-060 era ancora bloccante.
- Stato: esiste una sola policy chiara per summary manuali/automatici; niente doppia fonte di verita divergente.
- Owner: nessun summary/outbox di un utente precedente appare dopo logout o cambio utente.
- UX: nessun redesign Options e nessun gergo tecnico visibile.
- Scope: nessun cambio schema Room, DAO, Gradle o Supabase backend.
- TASK-060: nessuna regressione del refresh puntuale via `remoteAppliedProductIds`; TASK-061 non deve trasformare full-sync fallback in refresh globale implicito.
- TASK-063: nessun live smoke dichiarato senza evidenza.
- Test: repository, ViewModel e coordinator/tracker coprono i casi nuovi e non indeboliscono la baseline esistente.
