# TASK-070 — Outbox retry head-of-line + logging strutturato

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-070 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Sync catalogo cloud — `sync_event_outbox` retry & osservabilità |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-28 — Review tecnica completata; TASK-070 chiuso `DONE` |

---

## Dipendenze

- **TASK-069** `DONE` — audit diagnostico che identifica root cause P0 (head-of-line block + ambiguità `recordOrEnqueueSyncEvent` + metriche delta mancanti).
- **TASK-068** `PARTIAL` — bulk product push (no-regression richiesta).
- Baseline test **TASK-004** — `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest`, `CatalogAutoSyncCoordinatorTest`.
- **TASK-071** `BACKLOG` — solo backend (causa `PayloadValidation` / RPC `record_sync_event`). **Non** è prerequisito di TASK-070: TASK-070 è **app-side** (retry non bloccato + logging); può partire **senza** attendere TASK-071 — anche se il backend continua a fallire, il retry non deve restare in head-of-line e l’osservabilità deve migliorare. TASK-070 **non** modifica il contratto RPC.

---

## Scopo

Risolvere il blocco FIFO **head-of-line** del retry outbox e migliorare l'osservabilità del lifecycle outbox **senza** modifiche schema/RPC/backend e **senza** cleanup distruttivo.

Obiettivi:

1. **Retry non bloccato** dalle prime N righe a max attempts — il retry deve raggiungere righe `attemptCount < MAX_ATTEMPTS` anche con head FIFO “morto” (vedi *Scelta tecnica preferita*).
2. **Metriche / logging strutturato** lifecycle outbox coerenti con *Metriche/log minimi* (insert/retry/skip/delete/failure, pending before/after, campi diagnostici privacy-safe).
3. **Outcome esplicito** su `recordOrEnqueueSyncEvent`: non basarsi solo su `catalogEventEmitted` / `priceEventEmitted` (vedi *Distinzione semantica*).
4. **Test JVM** ancorati al fix (elenco in *Test JVM minimi*). **Non** obiettivo: azzerare `pending` / `syncEventOutboxPending` (vedi *Definizione del successo*).

---

## Contesto

L'audit TASK-069 (Execution 2026-04-27, criterio #6) ha dimostrato che:

- `InventoryRepository.retrySyncEventOutbox` (`InventoryRepository.kt:3112`) chiama `syncEventOutboxDao.listPending(owner, 20)` ordinato per `createdAtMs ASC, id ASC` (`SyncEventModels.kt:207`) — la query **non filtra** per `attemptCount`.
- Il loop salta con `continue` le righe a `attemptCount >= 5` (`InventoryRepository.kt:3119`) ma non recupera ulteriori righe ritentabili: se i primi 20 record FIFO sono tutti a max attempts, nel caso dello snapshot sotto **898 righe sotto soglia non venivano ritentate**.
- `recordOrEnqueueSyncEvent` (`InventoryRepository.kt:3152`) ritorna `false` sia per "nessun evento" sia per "RPC fallita + enqueue", rendendo il flag `catalogEventEmitted` / `priceEventEmitted` semanticamente ambiguo.
- Le metriche `syncEventOutboxInserted` e `syncEventOutboxDeleted_or_marked_sent` non sono loggate esplicitamente (deducibili solo per delta pending).

**Evidence anchor TASK-069 (storica, read-only):** `pending=918`, `attemptCount>=5: 20`, `belowMax: 898`, prime 20 FIFO con `attemptCount=5` e `lastErrorType=PayloadValidation`. **Non** è un valore da assumere in execution: prima di validare il fix, leggere lo **stato attuale** di `sync_event_outbox`. **Nessun** criterio di accettazione deve dipendere dal numero 918 come conteggio atteso oggi; il successo resta **comportamentale** (ritentabili raggiunti con head FIFO esaurito).

---

## Scelta tecnica preferita

**Preferenza esecutiva:** aggiungere una **nuova** query DAO, **senza** alterare la semantica di `listPending` esistente.

| Elemento | Specifica |
|----------|-----------|
| Nome suggerito | `listPendingRetryable(ownerUserId, maxAttempts, limit)` |
| Semantica | Solo righe ancora ritentabili, stesso ordinamento FIFO dei pendenti “vivibili”. |
| Query attesa | `WHERE ownerUserId = :ownerUserId AND attemptCount < :maxAttempts ORDER BY createdAtMs ASC, id ASC LIMIT :limit` |
| Consumer | `retrySyncEventOutbox` usa **questa** query per il path di retry. |
| `listPending` originale | Resta invariato e disponibile per audit/debug/count o altri call site. |

**Motivi:** elimina il head-of-line block; mantiene FIFO tra i record ancora ritentabili; più semplice da coprire con test JVM rispetto a cursori o paginazione ad hoc; **nessuna migration/schema**.

---

## Definizione del successo

TASK-070 **non** deve promettere di azzerare `syncEventOutboxPending` né di “ripulire” il conteggio UI.

Il successo è (indipendentemente da TASK-071 e da conteggi storici del TASK-069):

1. I record con `attemptCount < MAX_ATTEMPTS` vengono **raggiunti** dal retry anche quando i primi record FIFO hanno esaurito i tentativi.
2. **Metriche e/o log strutturati** rendono osservabili almeno: insert, retry, skip (max attempts), delete on success, failure (vedi sezione *Metriche/log minimi*).
3. Il **pending può restare alto** se il backend continua a rispondere ad es. `PayloadValidation` — comportamento atteso, non fallimento del task.
4. **Nessun cleanup distruttivo** (reset/delete/truncate/mass delete righe fallite).

---

## Distinzione semantica `recordOrEnqueueSyncEvent`

Oggi l’ambiguità è mitigata solo da `catalogEventEmitted` / `priceEventEmitted`. In execution si richiede un **outcome esplicito**, forma semplice da adottare senza rompere i call site (es. overload + adapter interno, o tipo di ritorno nuovo con migrazione locale controllata).

**Proposta di sealed class / enum outcome** (nomi indicativi):

- `NoOp` — nessun evento da emettere / nulla da accodare.
- `Recorded` — RPC (o path record) ok, nessun enqueue necessario per quel ramo.
- `Enqueued` — evento accodato in outbox (tipicamente dopo RPC fallita o path enqueue).
- `PartiallyRecordedAndEnqueued` — dove applicabile: un ramo ok e l’altro accoda (chunk parziale).

**Requisito:** in execution, scegliere la forma minima che non costringa a rewrite massiccio dei call site; i flag esistenti possono restare come derivazione dall’outcome, ma **non** devono essere l’unica fonte di verità.

**Metriche outcome suggerite** (nomi indicativi, da allineare al logger): `eventNoOp`, `eventRecorded`, `eventEnqueued`, `eventPartialFailure` (o `eventPartial` se preferenza naming unificata).

---

## Metriche/log minimi

Richiedere almeno i seguenti campi **nei log strutturati o nell’oggetto risultato locale** (nomi possono essere leggermente adattati ma la semantica resta):

| Campo | Ruolo |
|-------|--------|
| `pendingBefore` | Snapshot/conteggio prima dell’operazione rilevante |
| `pendingAfter` | Dopo |
| `retryLoaded` | Quante righe caricate per il batch retry |
| `retryEligible` | Quante effettivamente idonee al tentativo |
| `retrySkippedMaxAttempts` | Saltate per `attemptCount >= maxAttempts` |
| `retrySucceeded` | RPC ok / delete riuscita |
| `retryFailed` | Fallimento dopo tentativo |
| `retryDeletedOnSuccess` | Righe rimosse da outbox dopo successo |
| `outboxInserted` | Nuove righe inserite (percorso enqueue) |
| `eventType` | Tipo evento sync |
| `lastErrorType` | Ultimo errore classificato |
| `attemptCount` | Tentativi correnti |
| `clientEventIdHash` **oppure** `clientEventIdShort` | Identificativo tracciabile senza esporre stringa piena |

**Privacy — strict:**

- **Vietato** loggare `entityIds` completi, elenchi barcode, nomi prodotto, token, URL complete, JWT, payload RPC grezzo identificante.
- Se serve campionatura: **massimo** 1–3 id **mascherati** o **solo** conteggi aggregati.

---

## `CatalogSyncSummary` e UI

- Estendere `CatalogSyncSummary` **solo se** necessario per propagare metriche che il ViewModel usa già in modo coerente; altrimenti preferire **log interni** e **oggetto risultato locale** (`RetryOutboxResult`, ecc.) senza allargare la superficie UI.
- **Nessuna modifica UI** in TASK-070 (indicatori, testi, stati visibili). Metriche/log **non** devono introdurre **nuovi stati visibili** all’utente né rendere **più allarmistico** il copy esistente sul pending outbox.
- Eventuale copy `CloudSyncIndicator` / `OptionsScreen` resta **task UX separato**; non introdurre nuovi stati UI basati solo su metriche interne se l’UI non è progettata per interpretarli.

---

## Ordine consigliato (futura execution)

1. **Preflight governance:** TASK-069 `DONE` confermato; TASK-070 promosso **esplicitamente** a `EXECUTION`. **TASK-071 non blocca** TASK-070 (può procedere in parallelo o dopo; stesso vincolo: nessuna modifica contratto RPC lato app in TASK-070).
2. **Leggere il codice reale** aggiornato (Repository, DAO, call site `recordOrEnqueue*`); se si valida su DB reale, usare lo **stato attuale** di `sync_event_outbox`, non lo snapshot storico (`918`).
3. **Test JVM** che falliscono sul comportamento attuale (head-of-line) prima o **insieme** al fix — non dopo soltanto.
4. Aggiungere **DAO** `listPendingRetryable` (o nome equivalente) come da *Scelta tecnica preferita*.
5. Aggiornare **`retrySyncEventOutbox`** per usare la nuova query sul path retry.
6. Aggiungere **oggetto risultato / metriche** coerenti con *Metriche/log minimi*.
7. Aggiornare **`recordOrEnqueueSyncEvent`** con outcome esplicito (`NoOp` / `Recorded` / `Enqueued` / `PartiallyRecordedAndEnqueued`).
8. Eseguire **test mirati** (repository + DAO); poi, se sync core tocca in modo ampio: `assembleDebug`, `lintDebug`, `testDebugUnitTest` (o suite concordata con baseline TASK-004).
9. **Documentare in Execution** che **non** è stato eseguito cleanup outbox (nessun truncate/reset).

---

## Rischi e guardrail

| Guardrail | Dettaglio |
|-----------|-----------|
| Rollback / scope | Fix **piccolo** e **facilmente revertibile**: preferire **nuova query DAO** + modifica **mirata** a `retrySyncEventOutbox`; evitare refactor ampi del sync catalogo e tocchi ad altri domini se non necessari. |
| `listPending` | Invariato (semantica e call site audit/debug). |
| Righe a max attempts | **Non** cancellarle in questo task; restano in DB fino a task futuro su cleanup/coalescing/TTL. |
| Backoff / coalescing | **Non** introdurre backoff, TTL semantico, coalescing o dedup globale in TASK-070. |
| RPC / schema | **Non** modificare contratto RPC, schema Supabase, RLS, trigger, migration. |
| UI | Nessuna modifica schermate; metriche **non** → nuovi stati visibili; copy pending **non** più allarmistico (dettaglio § `CatalogSyncSummary`). |
| Log | **Non** loggare dati sensibili (vedi *Metriche/log minimi*). |

---

## Non incluso

- **Nessun reset/delete/truncate** outbox o pulizia massiva per "far scendere il numero".
- **Nessuna modifica schema Room locale (migration), Supabase, RPC, RLS, trigger** — overlap con tabella **Rischi e guardrail** (RPC/schema).
- **Nessun coalescing/TTL semantico** senza prova superseded (vincolo TASK-069).
- **UI/copy**: vedi § `CatalogSyncSummary` (nessuna modifica; copy UX = task separato).
- **Fix backend** `record_sync_event` / `PayloadValidation` = **TASK-071**; TASK-070 **non** lo attende.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `retrySyncEventOutbox`, `recordOrEnqueueSyncEvent`, `logSyncEventSummary`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt` — `SyncEventOutboxDao`: nuova `listPendingRetryable(ownerUserId, maxAttempts, limit)`; **`listPending` invariato**.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — nuovi test FIFO/retry/log.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt` — solo se `CatalogSyncSummary` / API VM viene estesa (altrimenti N/A).

---

## Test JVM minimi (casi richiesti)

In execution, coprire almeno:

1. **DAO / query:** `listPendingRetryable` esclude righe con `attemptCount >= maxAttempts`; tra le ritentabili l’ordine è FIFO (`createdAtMs`, poi `id`).
2. **Repository retry:** primi 20 record (o batch size) “esauriti” + record successivi ancora ritentabili → il retry **raggiunge** i ritentabili (nessun head-of-line block).
3. **Retry success:** RPC ok → `deleteById` (o equivalente) invocato / `pendingAfter` coerente con calo atteso.
4. **Retry failure:** RPC fail → `attemptCount` incrementato e `lastErrorType` aggiornato dove applicabile.
5. **Skip max attempts:** righe a max tentativi non conteggiate come `retrySucceeded`.
6. **No-op event:** nessun evento da emettere → **nessuna** insert outbox.
7. **RPC fail → enqueue:** evento inserito in outbox e outcome classificato come `Enqueued` (o equivalente).
8. **Chunk partial:** se il codice gestisce successi parziali, non confondere **full success** con outcome parziale (`PartiallyRecordedAndEnqueued` / metrica dedicata).
9. **Privacy / log:** assertion o review su messaggi di log verificabili — **nessun** `entityIds` completo, barcode, nomi prodotto, token, URL/JWT in test che leggono log (o in fixture di stringhe attese).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Retry raggiunge righe `attemptCount<MAX_ATTEMPTS` anche se le prime FIFO sono `>=MAX_ATTEMPTS` (nessun head-of-line) | S + JVM | — |
| 2 | `listPendingRetryable` (nome o equivalente documentato) + `listPending` **invariato** per altri call site; il path `retrySyncEventOutbox` usa la query retryable | S + JVM | — |
| 3 | Metriche/log includono i campi di *Metriche/log minimi*; lifecycle osservabile (insert/retry/skip/delete/failure) | S + M/R | — |
| 4 | Test JVM: scenari in *Test JVM minimi* soddisfatti (DAO FIFO, retry con head esaurito, success/fail/skip/no-op/enqueue/partial ove applicabile, privacy) | JVM | — |
| 5 | `recordOrEnqueueSyncEvent` espone outcome esplicito (`NoOp` / `Recorded` / `Enqueued` / `PartiallyRecordedAndEnqueued` o equivalente); metriche distinguono NoOp / Recorded / Enqueued / Partial; i flag emit non sono l’unica fonte di verità | S + JVM | — |
| 6 | Nessuna regressione TASK-068 (bulk product push — test JVM pertinenti verdi) | JVM | — |
| 7 | Nessun reset/delete/truncate outbox; **pending non** “obiettivo zero” artificiale; righe a max attempts **restano** in DB (cleanup = task futuro) | Governance | — |
| 8 | `assembleDebug`, `lintDebug`, `testDebugUnitTest` (o suite mirata + baseline TASK-004) verdi post-modifica sync core | B + JVM | — |
| 9 | Log privacy-safe: niente `entityIds` completi, barcode, nomi prodotto, token, URL, JWT; solo hash/short id o conteggi (vedi *Metriche/log minimi*) | Review + JVM se assert su log | — |
| 10 | Nessun file backend/schema: nessuna modifica Room migration/entity remota, Supabase, RPC, RLS, trigger | Governance | — |

Legenda: B=Build, S=Static, M=Manual, R=Review, JVM=test unitari su JVM.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task creato in `BACKLOG` come follow-up app di TASK-069 | Audit ha identificato fix non distruttivi safe-now: retry head-of-line + logging | 2026-04-27 |
| 2 | Nessun cleanup outbox autorizzato | Governance: vietato reset/delete; rischio perdita semantica | 2026-04-27 |
| 3 | Backend RPC `PayloadValidation` / TASK-071 separato; TASK-070 **non** in attesa di TASK-071 | App-side retry+log ha valore anche se RPC resta fallita; nessun cambio contratto RPC in TASK-070 | 2026-04-27 |
| 4 | Preferenza tecnica: `listPendingRetryable` + `listPending` invariato | Head-of-line risolto senza migration; FIFO sui ritentabili; test JVM semplici | 2026-04-27 |

---

## Planning (Claude — bozza)

Sintesi: il dettaglio operativo vive nelle sezioni **Scelta tecnica preferita**, **Definizione del successo**, **Metriche/log minimi**, **Test JVM minimi**, **Ordine consigliato (futura execution)**, **Rischi e guardrail**. Qui solo ancoraggio conciso.

### Analisi (compatto)

TASK-069 ha ancorato P0 (codice + evidenze read-only). Alternative al filtro DAO (cursori, “skip dead” applicativo) restano **fuori preferenza** per TASK-070: la nuova query `listPendingRetryable` è la direzione vincolata in *Decisioni* #4. Logging: popolare i contatori in `recordOrEnqueueSyncEvent` / `retrySyncEventOutbox` / `logSyncEventSummary` secondo *Metriche/log minimi*. Outcome espliciti per emit e per retry (oggetto risultato locale) riducono l’ambiguità dei soli boolean.

### Piano di esecuzione

Seguire l’ordine numerato in **Ordine consigliato (futura execution)**. Rischi e limiti: tabella **Rischi e guardrail**.

---

## Execution

### Esecuzione — 2026-04-28

**File modificati:**
- `docs/MASTER-PLAN.md` — promosso TASK-070 come task attivo; poi allineato a fase `REVIEW` dopo verifiche.
- `docs/TASKS/TASK-070-outbox-retry-head-of-line-logging-strutturato.md` — stato task, log Execution, criteri verificati, handoff.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt` — aggiunta query DAO `listPendingRetryable(ownerUserId, maxAttempts, limit)` e conteggio max-attempt per metriche; `listPending` invariata.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — retry outbox usa query retryable, outcome esplicito locale per record/enqueue, `RetryOutboxResult`, log strutturati privacy-safe.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test JVM mirati DAO/retry/no-op/enqueue/privacy/outcome; baseline TASK-068 già inclusa nello stesso file.

**Azioni eseguite:**
1. Governance: TASK-069 confermato `DONE`; TASK-070 promosso da `BACKLOG` a `EXECUTION`; TASK-071 confermato non prerequisito.
2. Preflight: `git status` iniziale mostrava modifiche documentali preesistenti su TASK-070/TASK-071; codice reale letto prima degli edit.
3. Call site trovati: `retrySyncEventOutbox` chiamato da `syncCatalogQuickWithEvents` e `drainSyncEventsFromRemote`; `recordOrEnqueueSyncEvent` chiamato per catalog e prices nella quick sync; `syncEventOutboxDao.listPending` usato nel vecchio retry e nei test/audit; logging preesistente in `logSyncEventSummary` e `CatalogAutoSyncCoordinator`.
4. DAO: aggiunta `listPendingRetryable` con filtro `attemptCount < maxAttempts`, ordine `createdAtMs ASC, id ASC`, limit; `listPending` non modificata.
5. Retry: `retrySyncEventOutbox` carica solo righe retryable, mantiene FIFO tra retryable, non elimina righe a max attempts e conta `retrySkippedMaxAttempts`.
6. Outcome: `recordOrEnqueueSyncEvent` ora ritorna outcome esplicito locale (`NoOp`, `Recorded`, `Enqueued`, `PartiallyRecordedAndEnqueued`); i boolean `catalogEventEmitted` / `priceEventEmitted` restano derivati per compatibilità logica.
7. Logging: aggiunti `RetryOutboxResult`, log retry summary/entry e outcome record/enqueue con `pendingBefore`, `pendingAfter`, `retryLoaded`, `retryEligible`, `retrySkippedMaxAttempts`, `retrySucceeded`, `retryFailed`, `retryDeletedOnSuccess`, `outboxInserted`, `eventType`, `lastErrorType`, `attemptCount`, `clientEventIdHash`.
8. Privacy: nessun log di `entityIds` completi, barcode, nomi prodotto, token, URL complete, JWT o payload RPC grezzo; test Robolectric verifica le stringhe log attese.
9. Vincoli rispettati: nessun cleanup outbox, nessuna modifica UI, nessuna modifica backend/Supabase/RPC/RLS/trigger, nessuna migration/schema Room.

**Cosa cambia tecnicamente:**
- Il retry non resta bloccato se le prime righe FIFO sono a `attemptCount >= 5`: quelle righe restano in DB ma non consumano il batch retry.
- Il pending può restare alto: ora il risultato è osservabile, ma non viene abbassato artificialmente.
- Le metriche distinguono retry success/failure/delete, skip max attempts, insert outbox e outcome record/enqueue/no-op.

**Cosa NON cambia:**
- Nessun reset/delete/truncate/mass delete outbox.
- Nessun cambio UI/copy/stato visibile.
- Nessun cambio schema Room, migration, Supabase, RPC, RLS, trigger o backend.
- Nessun backoff, TTL, coalescing o dedup globale.
- Nessuna regressione intenzionale al bulk product push TASK-068.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s` |
| Lint | ✅ ESEGUITO | `JAVA_HOME=... JAVA_TOOL_OPTIONS=... ./gradlew lintDebug` → `BUILD SUCCESSFUL in 38s`; warning toolchain AGP/Kotlin preesistenti, nessun warning nuovo dal codice modificato |
| Warning nuovi | ✅ ESEGUITO | `compileDebugKotlin` / `compileDebugUnitTestKotlin` verdi; nessun warning Kotlin nuovo nel codice modificato |
| Coerenza con planning | ✅ ESEGUITO | Implementati query retryable, retry fix, outcome esplicito, logging strutturato, test JVM; nessun fuori-scope UI/backend/schema |
| Criteri di accettazione | ✅ ESEGUITO | Vedi dettaglio criteri sotto |

**Test eseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest'` → `BUILD SUCCESSFUL in 32s`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest` → primo tentativo fallito per ambiente MockK/ByteBuddy (`AttachNotSupportedException`) prima dei test di dominio.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL in 19s`.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS=... ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s`.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS=... ./gradlew lintDebug` → `BUILD SUCCESSFUL in 38s`.
- `git diff --check` → ✅ nessun whitespace error.

**Baseline regressione TASK-004:**
- Test eseguiti: `DefaultInventoryRepositoryTest` mirato completo; `testDebugUnitTest` completo; include baseline repository/ViewModel/Excel e i test TASK-068 bulk product push presenti in `DefaultInventoryRepositoryTest`.
- Test aggiunti/aggiornati: `DefaultInventoryRepositoryTest` con casi `070` per DAO retryable FIFO, head-of-line, retry failure, no-op, enqueue privacy-safe, outcome recorded/enqueued.
- Limiti residui: nessuna validazione live su DB reale; non necessaria per il successo comportamentale e nessun valore storico `pending=918` è stato assunto. Se si vorrà misurare il pending reale, farlo read-only in task/step separato.

**Dettaglio criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | Test `070 retry reaches retryable rows behind exhausted fifo head`: 20 righe max-attempt restano, 2 retryable successive vengono registrate e cancellate. |
| 2 | ✅ ESEGUITO | `listPendingRetryable` aggiunta; `listPending` invariata e verificata nel test DAO; `retrySyncEventOutbox` usa la nuova query. |
| 3 | ✅ ESEGUITO | Log/oggetto risultato includono campi minimi lifecycle retry/enqueue/outcome. |
| 4 | ✅ ESEGUITO | Coperti DAO FIFO, retry head, success/delete, failure/update, skip max attempts, no-op, enqueue, outcome misto, privacy log. |
| 5 | ✅ ESEGUITO | Outcome esplicito locale introdotto; log distingue `no_op`, `recorded`, `enqueued`, `partially_recorded_and_enqueued`; flag emit derivati. |
| 6 | ✅ ESEGUITO | `DefaultInventoryRepositoryTest` completo verde, inclusi test `068` bulk product push. |
| 7 | ✅ ESEGUITO | Nessun cleanup outbox; righe max-attempt non eliminate; pending non trattato come obiettivo zero. |
| 8 | ✅ ESEGUITO | `testDebugUnitTest`, `assembleDebug`, `lintDebug` verdi con JBR Android Studio e opzioni ByteBuddy per la suite completa. |
| 9 | ✅ ESEGUITO | Test log privacy-safe: niente barcode/nome prodotto/clientEventId pieno/`entityIds=`; solo `clientEventIdHash`. |
| 10 | ✅ ESEGUITO | Nessun file backend/schema/RPC/RLS/trigger; nessuna migration/entity modificata. |

**Incertezze:**
- Nessuna sul fix app-side. Nota ambiente: la suite completa richiede `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading'` per i test MockK/ByteBuddy in questa shell.

**Handoff notes:**
- Pronto per review planner.
- TASK-071 resta separato/non bloccante: se RPC continua a fallire con `PayloadValidation`, le righe retryable vengono comunque raggiunte e osservate.
- Non è stato eseguito alcun cleanup outbox e non è stato letto/modificato alcun DB reale.

---

## Review

### Review finale — 2026-04-28

**Verdetto:** APPROVED — nessun fix codice necessario. TASK-070 è coerente con il planning e può chiudere `DONE`.

**Cosa è stato verificato:**
1. Preflight: `git status`, diff completo dei file modificati, assenza diff su `gradle/libs.versions.toml`; `TASK-071` risulta modificato nel worktree ma resta `BACKLOG` e non è stato toccato in review.
2. Scope: nessuna UI, nessun backend/Supabase/RPC/RLS/trigger/schema/migration, nessun cleanup distruttivo outbox, nessun refactor largo sync catalogo.
3. DAO: `listPendingRetryable(ownerUserId, maxAttempts, limit)` filtra `ownerUserId` + `attemptCount < maxAttempts`, ordina `createdAtMs ASC, id ASC`, applica `LIMIT`; `listPending` resta semanticamente invariata.
4. Retry: `retrySyncEventOutbox` usa la query retryable; righe a max attempts restano in DB e non bloccano le successive retryable; `retrySkippedMaxAttempts` è calcolato come conteggio pendenti già a/sopra soglia, non dal batch retryable caricato.
5. Outcome/log: outcome esplicito locale usato nei call site; log lifecycle privacy-safe con conteggi e `clientEventIdHash` breve/stabile; nessun barcode, nome prodotto, payload RPC grezzo, token/JWT/URL o `entityIds` completi nei nuovi log.
6. TASK-068: i test repository mirati includono i casi bulk product push già presenti; nessuna regressione rilevata.

**Fix applicati in review:** nessun fix codice. Aggiornata solo documentazione di chiusura (`TASK-070` + `MASTER-PLAN`).

**Test/comandi eseguiti in review:**
- `git diff --check` → ✅ nessun whitespace error.
- `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest --tests '*DefaultInventoryRepositoryTest*'` → ⚠️ non eseguibile nella shell senza `JAVA_HOME`: `Unable to locate a Java Runtime`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest --tests '*DefaultInventoryRepositoryTest*'` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest` → ⚠️ non eseguibile nella shell senza `JAVA_HOME`: `Unable to locate a Java Runtime`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest` → ⚠️ fallisce per ambiente daemon/ByteBuddy (`AttachNotSupportedException`) su test MockK non correlati.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew --no-daemon testDebugUnitTest` → ✅ `BUILD SUCCESSFUL`.
- `./gradlew assembleDebug` → ⚠️ non eseguibile nella shell senza `JAVA_HOME`: `Unable to locate a Java Runtime`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → ✅ `BUILD SUCCESSFUL`.
- `./gradlew lintDebug` → ⚠️ non eseguibile nella shell senza `JAVA_HOME`: `Unable to locate a Java Runtime`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → ✅ `BUILD SUCCESSFUL`.

**Conferme finali:**
- Nessun cleanup outbox (`reset`/`delete`/`truncate`/mass delete) eseguito.
- Nessuna UI modificata.
- Nessun backend/schema/RPC/RLS/trigger/migration modificato.
- `TASK-071` resta separato e non bloccante.
- `gradle/libs.versions.toml`: nessun diff presente in review; nessun rollback necessario.

**Rischi residui:**
- Nessuna validazione live su DB reale eseguita; non richiesta per il successo comportamentale di TASK-070. Il pending può restare alto finché TASK-071/backend `PayloadValidation` resta separato.

---

## Fix

Nessun fix codice necessario in review.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-28 |

---

## Handoff

- TASK-070 chiuso `DONE` dopo review tecnica.
- TASK-069 resta `DONE`; TASK-071 resta backend separato/non bloccante.
- Snapshot TASK-069 (`pending=918`, ecc.) resta evidence storica; il fix è comportamentale e testato su JVM.
- Nessun cleanup outbox, nessuna UI, nessun backend/schema/RPC/RLS/trigger/migration.
- Eventuale follow-up live/read-only su pending reale resta separato; TASK-071 copre il contratto RPC/backend.
