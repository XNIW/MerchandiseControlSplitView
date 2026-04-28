# TASK-070 — Outbox retry head-of-line + logging strutturato

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-070 |
| Stato | `BACKLOG` |
| Priorità | `ALTA` |
| Area | Sync catalogo cloud — `sync_event_outbox` retry & osservabilità |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — planning raffinato (DAO retryable, metriche, test, privacy; resta `BACKLOG`, no EXECUTION) |

---

## Dipendenze

- **TASK-069** `DONE` — audit diagnostico che identifica root cause P0 (head-of-line block + ambiguità `recordOrEnqueueSyncEvent` + metriche delta mancanti).
- **TASK-068** `PARTIAL` — bulk product push (no-regression richiesta).
- Baseline test **TASK-004** — `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest`, `CatalogAutoSyncCoordinatorTest`.

---

## Scopo

Risolvere il blocco FIFO **head-of-line** del retry outbox e migliorare l'osservabilità del lifecycle outbox **senza** modifiche schema/RPC/backend e **senza** cleanup distruttivo.

Obiettivi:

1. **Retry non bloccato** dalle prime N righe a max attempts — il retry deve raggiungere righe `attemptCount < MAX_ATTEMPTS` anche con head FIFO “morto” (vedi *Scelta tecnica preferita*).
2. **Metriche / logging strutturato** lifecycle outbox coerenti con *Metriche/log minimi* (insert/retry/skip/delete/failure, pending before/after, campi diagnostici privacy-safe).
3. **Outcome esplicito** su `recordOrEnqueueSyncEvent`: non basarsi solo su `catalogEventEmitted` / `priceEventEmitted` (vedi *Distinzione semantica*).
4. **Test JVM** ancorati al fix (elenco in *Test JVM minimi*). **Non** obiettivo: azzerare `pending` (vedi *Definizione del successo*).

---

## Contesto

L'audit TASK-069 (Execution 2026-04-27, criterio #6) ha dimostrato che:

- `InventoryRepository.retrySyncEventOutbox` (`InventoryRepository.kt:3112`) chiama `syncEventOutboxDao.listPending(owner, 20)` ordinato per `createdAtMs ASC, id ASC` (`SyncEventModels.kt:207`) — la query **non filtra** per `attemptCount`.
- Il loop salta con `continue` le righe a `attemptCount >= 5` (`InventoryRepository.kt:3119`) ma non recupera ulteriori righe ritentabili: se i primi 20 record FIFO sono tutti a max attempts, **898 righe sotto soglia non vengono mai ritentate**.
- `recordOrEnqueueSyncEvent` (`InventoryRepository.kt:3152`) ritorna `false` sia per "nessun evento" sia per "RPC fallita + enqueue", rendendo il flag `catalogEventEmitted` / `priceEventEmitted` semanticamente ambiguo.
- Le metriche `syncEventOutboxInserted` e `syncEventOutboxDeleted_or_marked_sent` non sono loggate esplicitamente (deducibili solo per delta pending).

Stato osservato (snapshot read-only TASK-069): `pending=918`, `attemptCount>=5: 20`, `belowMax: 898`, prime 20 FIFO con `attemptCount=5` e `lastErrorType=PayloadValidation`.

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

Il successo è:

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
- **Nessuna modifica UI** in TASK-070 (indicatori, testi, stati visibili).
- Eventuale copy `CloudSyncIndicator` / `OptionsScreen` resta **task UX separato**; non introdurre nuovi stati UI basati solo su metriche interne se l’UI non è progettata per interpretarli.

---

## Ordine consigliato (futura execution)

1. **Preflight governance:** TASK-069 `DONE` confermato; TASK-070 promosso **esplicitamente** a `EXECUTION`; TASK-071 resta task separato (backend/RPC).
2. **Leggere il codice reale** aggiornato (Repository, DAO, call site `recordOrEnqueue*`).
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
| `listPending` | Non cambiarne semantica se altri call site lo usano per audit, count o debug. |
| Righe a max attempts | **Non** cancellarle in questo task; restano in DB fino a task futuro su cleanup/coalescing/TTL. |
| Scope | **Non** introdurre backoff, TTL semantico, coalescing o dedup globale in TASK-070. |
| RPC | **Non** modificare contratto RPC, schema Supabase, RLS, trigger, migration. |
| UI | **Non** trasformare metriche interne in nuovi stati UI non supportati. |
| Log | **Non** loggare dati sensibili (vedi *Metriche/log minimi*). |

---

## Non incluso

- **Nessun reset/delete/truncate** outbox o pulizia massiva delle righe `PayloadValidation` per "far scendere il numero".
- **Nessuna modifica schema Room/Supabase, RPC, RLS, trigger, migration**.
- **Nessun coalescing/TTL semantico** senza prova che gli eventi siano superseded (vincolo audit TASK-069).
- **Nessuna modifica UI** in questo task (eventuale copy `CloudSyncIndicator`/`OptionsScreen` resta task UX separato).
- **Nessun fix backend** RPC `record_sync_event` / `PayloadValidation` (vedi TASK-071).

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
| 3 | Backend RPC `record_sync_event` `PayloadValidation` resta TASK-071 separato | Separation of concerns; gate anti-scope-creep TASK-069 | 2026-04-27 |
| 4 | Preferenza tecnica: `listPendingRetryable` + `listPending` invariato | Head-of-line risolto senza migration; FIFO sui ritentabili; test JVM semplici | 2026-04-27 |

---

## Planning (Claude — bozza)

Sintesi: il dettaglio operativo vive nelle sezioni **Scelta tecnica preferita**, **Definizione del successo**, **Metriche/log minimi**, **Test JVM minimi**, **Ordine consigliato (futura execution)**, **Rischi e guardrail**. Qui solo ancoraggio conciso.

### Analisi (compatto)

TASK-069 ha ancorato P0 (codice + evidenze read-only). Alternative al filtro DAO (cursori, “skip dead” applicativo) restano **fuori preferenza** per TASK-070: la nuova query `listPendingRetryable` è la direzione vincolata in *Decisioni* #4. Logging: populate i contatori in `recordOrEnqueueSyncEvent` / `retrySyncEventOutbox` / `logSyncEventSummary` secondo *Metriche/log minimi*. Outcome espliciti per emit e per retry (oggetto risultato locale) riducono l’ambiguità dei soli boolean.

### Piano di esecuzione

Seguire l’ordine numerato in **Ordine consigliato (futura execution)**. Rischi e limiti: tabella **Rischi e guardrail**.

---

## Execution

(da compilare quando il task passerà a `EXECUTION`)

---

## Review

(vuota in `BACKLOG`)

---

## Fix

(vuota in `BACKLOG`)

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | — |
| Data chiusura | — |

---

## Handoff

- Riferimento principale: TASK-069 sezione **Execution / Step 4-6** e tabella **Lifecycle outbox**.
- Snapshot stato outbox al 2026-04-27: `pending=918`, head-of-line block confermato.
- Planning TASK-070 raffinato 2026-04-27: query `listPendingRetryable`, outcome emit espliciti, metriche minime, test JVM elencati, guardrail privacy/scope — resta **`BACKLOG`** fino a promozione esplicita a `EXECUTION`.
- TASK-071 cura il lato backend `PayloadValidation`. Coordinare ordine: questo task NON deve cambiare contratto RPC.
- Vincoli ereditati da TASK-069: no reset/delete/truncate, no schema/RPC, no UI redesign.
