# TASK-069 — Audit sync residui e diagnosi sync_event_outbox / price push / GeneratedScreen / HistorySessionSync

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-069 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Sync catalogo cloud / Supabase — outbox diagnostica, price push, GeneratedScreen analyzeGridData, HistorySessionSyncV2, osservabilità |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — review repo-grounded APPROVED, audit chiuso, follow-up TASK-070 (app) e TASK-071 (backend) creati |

---

## Dipendenze

- **TASK-068** `PARTIAL` — bulk product push migliorato (evidenza live: ~6052 prodotti, `bulkEnabled=true`, `batchSize=100`, `batchCount=61`, `pushProductsMs≈10274ms`, fallback 0). L’audit TASK-069 **non deve regressare** quanto già validato JVM/live per TASK-068 salvo nuove evidenze documentate.
- **TASK-067** `DONE ACCEPTABLE` — dirty marking delta-safe; baseline comportamento post full import.
- **TASK-064 / TASK-065** `DONE` — PayloadValidation RPC/decoding; contesto storico outbox.
- Baseline test **TASK-004** quando execution o task figli toccheranno codice repository/ViewModel/sync.

---

## Scopo

Audit **ordinato e completo** dei **residui sync** dell’app Android dopo i miglioramenti TASK-068, con focus su:

1. **`sync_event_outbox` pending elevato** (evidenza recente: `syncEventOutboxPending` **915 → 916** con **`catalogEventEmitted=false`** e **`priceEventEmitted=false`**) — capire **causa reale**, perimetro funzionale, rischio vs innocuità.
2. **Price push** massivo / batch / dirty zero — quando è già sufficiente e quando serve solo monitoraggio o follow-up ottimizzazione **senza** schema/RPC nel piano principale.
3. **`GeneratedScreen`** — flusso **`analyzeGridData`**, apply/sync dalla griglia, possibili query pesanti (`getAllProducts` ecc.).
4. **`HistorySessionSyncV2`** — chunking, retry offline, metriche (`sessionsAttempted` / `sessionsUploaded`), path lenti.
5. Eventuali sync in **`OptionsScreen`** o altri entry point — inventario minimo.
6. **`CloudSyncIndicator`** — copy/stadi solo se emergono indicazioni **fuorvianti** post-evidenza.

Output del task (in **EXECUTION** futura, non ora): classificazione documentata dell’outbox, deliverable § **Deliverable obbligatori**, matrice decisionale, piano fix **candidati** per area (implementazione in task separati), log live (scenari **O1–O6**), criteri di accettazione soddisfatti con evidenza — **senza** reset/delete/truncate, **senza** cancellazione outbox, **senza** modifiche distruttive Supabase/Room.

---

## Contesto

Dopo TASK-068 il **collo di bottiglia principale del product push** risulta mitigato (bulk batch). Restano segnali da chiarire:

| Segnale | Valore osservato (indicativo, da riprodurre in execution) |
|---------|------------------------------------------------------------|
| `syncEventOutboxPending` | 915 → 916 (incremento minimo ma persistente alto) |
| `catalogEventEmitted` | false |
| `priceEventEmitted` | false |

**Interpretazione preliminare (ipotesi da verificare in codice + log):** i flag `catalogEventEmitted` / `priceEventEmitted` potrebbero riflettere solo **alcuni** tipi di emissione catalog/prezzo, mentre **`sync_event_outbox`** può ricevere enqueue da **altri percorsi** (retry RPC, tipi evento diversi, drain/sync_events correlati, marker manual full sync, ecc.), oppure le metriche potrebbero essere **incoerenti** con la condizione di insert/delete — va dimostrato.

TASK-068 resta **PARTIAL** per: ciclo B live con **stesso identico file** due volte; outbox non riclassificata con evidenza definitiva; audit incompleto su price push, GeneratedScreen `analyzeGridData`, HistorySessionSyncV2.

**Fonti primarie:** repo Android (`InventoryRepository.kt`, coordinator/tracker/VM, DAO `SyncEventOutboxDao` in `SyncEventModels.kt`, test JVM esistenti). iOS non richiesto salvo confronto copy opzionale.

**Repo principale (riferimento):** [MerchandiseControlSplitView](https://github.com/XNIW/MerchandiseControlSplitView) — progetto Android «对货 / MerchandiseControl».

---

## Priorità audit

Ordine **obbligatorio** di indagine nella futura execution: chiudere **P0** con evidenza prima di dedicare tempo a P1/P2 (P1/P2 restano comunque nelle matrici e nei log dove indicato).

### P0 (bloccanti per una classificazione utile)

- **Causa reale** di `sync_event_outbox` / `syncEventOutboxPending` **alto** (non solo “è alto” ma *perché* e *quale path* lo alimenta).
- **Perché** può aumentare **915 → 916** (o analoghi) con **`catalogEventEmitted=false`** e **`priceEventEmitted=false`**: correlazione con insert/retry/count/altri tipi di evento.
- **Mappa completa** dei path: **enqueue → retry → success / failure → delete o mark-sent** (o assenza di mark-sent). Output nella tabella lifecycle § **Deliverable obbligatori** (punto 1).
- **Distinguere** pending **storico** (accumulo pre-esistente, limite di drain) da **delta nuovo** introdotto nel ciclo osservato (snapshot before/after per scenario).

### P1 (subito dopo P0; no-regression e coerenza sync “utente”)

- **No-regression TASK-068**: bulk product push (`batchSize`, fallback, aggiornamento ref) — solo verifica/incrocio log+test dove serve, non rifare il lavoro bulk.
- **Price push**: `dirtyMarkedPrices`, batch **80**, assenza di lavoro inutile quando dirty prezzi = 0.
- **HistorySessionSyncV2**: chunking, retry offline, coerenza **`sessionsAttempted` / `sessionsUploaded`** vs assenza di sessioni dirty.

### P2 (dopo evidenza P0; perimetro più ampio ma non bloccante per la classificazione outbox)

- **GeneratedScreen** — `analyzeGridData` e costo misurato/percorsi lettura dati (vedi § *GeneratedScreen — regole*).
- **OptionsScreen** e altri trigger sync **minori** — inventario, non redesign.
- **CloudSyncIndicator** — copy/stadio **solo** se emerge confusione UX documentata (vedi § *CloudSyncIndicator — regole UX/UI*).

---

## Sequenza operativa consigliata per futura execution

Ordine **imposto** per ridurre dispersione e costo live (deviazioni solo se **motivate e annotate nella Execution del task**, una volta in `EXECUTION`):

| Step | Azione |
|------|--------|
| **0** | **Governance:** confermare che **TASK-069** sia stato promosso esplicitamente **`PLANNING` → `EXECUTION`** (e che `MASTER-PLAN`/backlog siano coerenti) **prima** di qualsiasi raccolta log live o test manuali nel perimetro audit. |
| **1** | **Lettura statica P0 — solo outbox:** `InventoryRepository.kt`, `SyncEventModels.kt` / DAO, `AppDatabase.kt`, `SupabaseSyncEventRemoteDataSource.kt` (se nel path), **senza** espandere ancora a GeneratedScreen/price/history. **Tracker / coordinator / ViewModel** solo dove servono a **interpretare metriche** (`syncEventOutboxPending`, emit flags, ecc.). |
| **2** | Compilare una **bozza** della tabella **lifecycle outbox** § *Deliverable* (punto 1) **prima** degli scenari live — anche se incompleta, per avere ipotesi verificabili. |
| **3** | Eseguire **solo O1** e **O2** per primi (stesso file ×2 dopo quiet window; stesso file + restart + sync manuale). |
| **4** | Se O1/O2 **spiegano** l’incremento pending / confermano path → **classificare P0**; **solo dopo** espandere a **P1** (no-regression TASK-068, price, HistorySession) e poi **P2**. |
| **5** | Se O1/O2 **non bastano**, eseguire **O5** e **O6** **prima** di deep dive **P2** su GeneratedScreen (O5/O6 coprono offline→online e foreground — ancora orientati a outbox/sync globale). |
| **6** | **Dopo P0** risolto o **`da_chiarire`** con § *Stop condition*: applicare **P1** poi **P2** (non invertire): **P1** — no-regression TASK-068 bulk, price push, HistorySessionSyncV2; **P2** — GeneratedScreen (`analyzeGridData`), Options; **CloudSyncIndicator** — **solo** proposta follow-up **UX/copy** (nessuna implementazione UI dentro TASK-069). Completare **O3–O4** e scenari complementari § *Matrice log* se ancora necessari. |

---

## Non incluso (vincoli — anche in futura EXECUTION)

- **Questo documento (PLANNING):** nessuna modifica codice Kotlin, risorse, Gradle; nessuna esecuzione build/test salvo letture statiche del planner.
- **Futura execution:** nessun **reset remoto**, **delete/truncate** Supabase o Room, **cancellazione manuale outbox**, cleanup distruttivo per “far scendere il numero”.
- **Nessun** cambio schema **Room/Supabase**, **RPC**, **RLS**, **trigger**, **publication**, **migration** nel **piano principale** TASK-069; se emerge necessità backend/schema → **follow-up TASK separato** esplicitamente.
- Non assumere che “abbassare pending” sia obiettivo per sé — prima **classificazione** e **sicurezza**.

---

## Gate anti-scope-creep

Se durante l’audit emerge **necessità** di **schema**, **RPC**, **RLS**, **migration**, **trigger**, **publication**, o altro **backend** non modificabile in sicurezza lato app:

1. **STOP** qualsiasi implementazione dentro il perimetro TASK-069 (execution futura inclusa).
2. **Documentare** evidenza: messaggio errore, payload atteso vs effettivo, vincolo remoto — senza dati distruttivi.
3. Aprire **follow-up backend / schema** come **task separato** con criteri propri.
4. **Non** proporre workaround client **fragili** (forzature payload, bypass sicurezza, dedup inventati senza contratto).

Questo gate vale anche per proposal tipo «aggiusta RPC così» dentro TASK-069: resta **solo raccomandazione** nel task figlio backend.

---

## Cosa NON deve decidere TASK-069

TASK-069 (audit + classificazione) **non** deve:

| Divieto | Motivo |
|---------|--------|
| Abbassare **pending** come **obiettivo numerico** di successo | Il successo è **capire** e **classificare**; abbassare il numero senza causa è rischio operativo |
| **Cancellare** o svuotare outbox / dati locali/remoti | Vietato dalla governance task (solo diagnosi non distruttiva) |
| Decidere **coalescing** / superseded senza **prova** che gli eventi siano semanticamente superseded | Rischio perdita eventi o divergenza da remote |
| **Cambiare backend** (DDL, RPC, RLS, ecc.) | Fuori scope — task backend separato |
| Confondere il miglioramento **TASK-068 bulk product push** con un **bug outbox** non ancora **classificato** | Due problemi diversi; incrociare log ma non attribuire una causa all’altra senza evidenza |

---

## Domande da rispondere (execution)

1. Perché **`syncEventOutboxPending`** resta alto?
2. L’outbox pending è **innocuo**, **ritentabile**, **bloccato da PayloadValidation**, **duplicato**, **obsoleto**, o **realmente necessario**?
3. Perché **aumenta** anche quando **`catalogEventEmitted=false`** e **`priceEventEmitted=false`**?
4. Un pending alto può causare:
   - retry infiniti?
   - consumo rete/batteria?
   - sync lenta?
   - full sync manuale non necessaria?
   - perdita eventi?
   - UI cloud sync confusa?
5. L’outbox serve ancora per quegli eventi o sono **superati** dal modello attuale?
6. Esiste **coalescing/compattazione** sicura?
7. Esiste già **retry/backoff**? Limite massimo o **TTL**?
8. Comportamento **offline → online**?
9. L’utente può continuare a usare il database con outbox pending alto?

---

## File / aree da leggere (checklist lettura — execution)

| Percorso | Nota |
|----------|------|
| `docs/MASTER-PLAN.md` | Contesto backlog e TASK-068 |
| `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` | Stato PARTIAL e evidenze |
| `data/InventoryRepository.kt` | Outbox retry/insert, sync catalog, HistorySessionSync tag |
| `data/CatalogAutoSyncCoordinator.kt` | Orchestrazione foreground/sync |
| `data/CatalogSyncStateTracker.kt` | Metriche/stati sync |
| `viewmodel/CatalogSyncViewModel.kt` | Summary UI, hint outbox |
| `ui/components/CloudSyncIndicator.kt` | Stage/copy |
| `viewmodel/DatabaseViewModel.kt` | Trigger sync da database/import |
| `GeneratedScreen.kt` | `analyzeGridData`, flussi sync |
| `viewmodel/ExcelViewModel.kt` | Grid/analysis se coinvolti |
| `data/SyncEventModels.kt` | `SyncEventOutboxEntry`, `SyncEventOutboxDao` |
| `data/AppDatabase.kt` | Tabella/indici `sync_event_outbox` |
| DAO/entity **`product_remote_refs`**, **`product_price_remote_refs`** | Coerenza push/ref |
| `MerchandiseControlApplication.kt` | Logger `HistorySessionSyncV2` |
| `app/src/test/...` — `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest`, `CatalogAutoSyncCoordinatorTest`, test sync/import | Baseline TASK-004 |

Ricerca grep suggerita: `sync_event_outbox`, `SyncEventOutbox`, `retrySyncEventOutbox`, `catalogEventEmitted`, `priceEventEmitted`, `analyzeGridData`, `HistorySessionSyncV2`.

---

## Matrice di audit obbligatoria

| Area | File principali da leggere | Log tag da cercare | Rischio funzionale | Rischio performance | Possibile causa (ipotesi) | Metriche da raccogliere | Decisione attesa | Fix candidate (solo pianificazione) | Follow-up fuori scope |
|------|---------------------------|-------------------|---------------------|---------------------|---------------------------|-------------------------|------------------|-------------------------------------|-------------------------|
| **A — sync_event_outbox / sync_events** | `InventoryRepository.kt`, `SyncEventModels.kt`, `SupabaseSyncEventRemoteDataSource*` (se presente), `AppDatabase.kt` | `CatalogCloudSync`, `sync_finish`, eventuali tag outbox RPC | Eventi non registrati remotamente / stato incoerente | Retry ripetuti, backlog RPC | Insert senza delete/mark sent; PayloadValidation latente; tipi evento fuori mapping | `syncEventOutboxPending`, inserted/retried/deleted, esiti RPC | Classificazione § *Classificazioni outbox* | Log strutturato; validazione pre-insert; coalescing solo se sicuro | Schema RPC/backend dedicato |
| **B — product push post TASK-068** | `InventoryRepository.kt` (path bulk già introdotto) | `PUSH_PRODUCTS`, `phase_metrics` | Regression bulk vs singolo | — (già migliorato) | — | `productsPushed`, batchCount, fallback | Nessuna regressione; conferma live | Solo se evidenza regressione | — |
| **C — price push** | Repository + DAO price refs | `SYNC_PRICES_PUSH`, dirty price metrics | Prezzi non allineati | Tempo sync alto su molti dirty | `dirtyMarkedPrices` > 0 vs batch 80 | `pricesPushed`, `dirtyMarkedPrices`, `syncPricesMs` | Monitor vs ottimizzazione futura | Bulk/checksum solo come task separato senza schema | RPC/checksum backend |
| **D — GeneratedScreen analyzeGridData / apply** | `GeneratedScreen.kt`, `ExcelViewModel.kt` | tag VM/sync correlati | Apply errato | OOM / CPU su griglia grande | `getAllProducts()` full scan | Tempo analyze, righe, heap | Ottimizzazione subset barcode-only se misurato | Query subset; VM fonte verità | Refactor grande TASK-002 |
| **E — HistorySessionSyncV2** | `InventoryRepository.kt`, `MerchandiseControlApplication.kt` | `HistorySessionSyncV2` | Sessioni non salvate remote | Upload sequenziale | Chunk size, retry storm | sessionsAttempted/uploaded/skipped | Conferma “0 corretto” se non dirty | Chunk tuning; più log JVM | Backend session schema |
| **F — Catalog bootstrap / realtime drain / foreground** | `CatalogAutoSyncCoordinator.kt`, tracker | `CatalogCloudSync`, `SyncEventsRealtime`, `SupabaseRealtime` | Stato “sempre sync” errato | Wakeups | Race bootstrap vs push | drain ms, fetched/processed | Documentare ordine fasi | Debounce coordinator | — |
| **G — CloudSyncIndicator** | `CloudSyncIndicator.kt`, `CatalogSyncViewModel.kt`, stringhe | — | Utente confuso | — | Mapping fase ≠ realtà | Stage vs log backend | Copy minimo se UX | Solo copy/stage | Redesign TASK-059 scope |
| **H — OptionsScreen / sync minori** | `OptionsScreen.kt`, entry sync manuale | — | Toggle incompreso | — | — | — | Inventario trigger | Documentazione | — |

---

## Matrice log live (raccolta futura execution)

**Ordine di esecuzione degli scenari live:** § **Sequenza** — **O1 → O2** → (se serve) **O5 → O6** → poi **O3 → O4** (GeneratedScreen) dopo classificazione P0; scenari complementari 7–9 se utili.

Per **ogni scenario obbligatorio** (§ sotto), registrare **before** e **after** sullo stesso ciclo osservato:

**Campi minimi before/after per scenario:**  
`syncEventOutboxPending`, `syncEventOutboxInserted`, `syncEventOutboxRetried`, `syncEventOutboxDeleted_or_marked_sent`, `catalogEventEmitted`, `priceEventEmitted`, `productsPushed`, `pricesPushed`, `sessionsUploaded`, `durationMs` — più gli altri campi della tabella globale quando disponibili.

Campi **globali** consigliati per ogni riga di scenario (completare dove il codice espone la metrica):

| Campo |
|-------|
| scenario |
| timestamp_start |
| timestamp_end |
| trigger |
| syncEventOutboxPending_before |
| syncEventOutboxPending_after |
| syncEventOutboxRetried |
| syncEventOutboxInserted |
| syncEventOutboxDeleted_or_marked_sent |
| catalogEventEmitted |
| priceEventEmitted |
| syncEventsFetched |
| syncEventsProcessed |
| syncEventsSkippedSelf |
| syncEventsSkippedDirtyLocal |
| syncEventsSkippedProtectedLocalCommit |
| manualFullSyncRequired |
| syncEventsGapDetected |
| manualFullSyncRequired_reason |
| productsPushed |
| pricesPushed |
| sessionsUploaded |
| durationMs |
| outcome |
| classification |

### Scenari — obbligatori (TASK-069)

Questi scenari **devono** essere eseguiti e documentati nella matrice log (salvo **NON ESEGUIBILE** motivato):

| # | Scenario |
|---|----------|
| O1 | **Stesso identico file** importato **due volte** dopo **quiet window** (allineamento ciclo B TASK-068) |
| O2 | **Stesso identico file** importato → poi **restart app** → **sync manuale** (ordine e tempi annotati) |
| O3 | **GeneratedScreen** — sync con **nessuna modifica** locale alla griglia |
| O4 | **GeneratedScreen** — sync con **piccola modifica** (una modifica minimale ripetibile) |
| O5 | **Offline → modifica locale → online** (retry naturale o sync successiva) |
| O6 | **Foreground sync** senza modifiche locali al catalogo |

### Scenari — complementari (restano raccomandati)

7. App avvio **online**  
8. Import database con **delta reale** (non duplicato identico)  
9. History session sync con **nessuna sessione dirty**  

**Privacy:** mascherare segreti URL/token/JWT; barcode/id campione limitati.

---

## Classificazioni possibili dell’outbox

Usare una sola primaria per evento + nota secondaria se utile:

| ID classificazione | Significato |
|--------------------|-------------|
| `outbox_ok_pending_temporaneo` | Pending che convergerà al prossimo retry online senza loop |
| `outbox_payload_validation_blocked` | RPC/remote rifiuta payload — vedere TASK-064/065 pattern |
| `outbox_duplicate_event` | Stesso clientEventId o equivalente logico duplicato |
| `outbox_stale_event_safe_to_coalesce` | Evento vecchio già coperto da stato remoto — coalescing sicuro **solo dopo prova** |
| `outbox_required_retry` | Failure transitoria — backoff atteso |
| `outbox_orphaned_requires_followup` | Nessun path che marca sent/delete — bug o contratto |
| `outbox_bug_in_insert_condition` | Enqueue quando non dovrebbe |
| `outbox_bug_in_mark_sent_condition` | Successo remoto ma row locale non aggiornata |
| `outbox_backend_contract_mismatch` | Payload ↔ RPC ↔ DB — **TASK backend separato** |
| `da_chiarire` | Dati insufficienti |

---

## Albero decisionale (testo)

```
syncEventOutboxPending alto dopo ciclo sync?
├─ NON aumenta nel tempo e retry non loopano
│  └─ classifica: innocuo / monitoraggio — verificare TTL backoff comunque
├─ aumenta ad ogni sync senza catalog/price emitted true
│  └─ sospetto: bug insert/enqueue OR metriche incoerenti — tracciare insert vs emit per campo
├─ PayloadValidation su RPC/log remoti
│  └─ ispezionare shape payload: changedCount, entityIds, event type, limiti — TASK-064 lineage
├─ eventi stale/duplicati rappresentati già dal remote
│  └─ proporre coalescing/mark-safe in task implementativo — MAI delete manuale ad-hoc
├─ serve schema/RPC/migration
│  └─ STOP task-069 implementation — aprire TASK backend separato
└─ solo impatto UX (indicator copy)
   └─ follow-up UI minimo separato — no logica sync nel copy-only
```

---

## Stop condition / fallback

- Se la **causa P0** non è **dimostrabile** con **codice + log** (o scenari eseguibili): **non inventare** fix narrativa; classificare **`da_chiarire`**; task figlio prioritario = **logging strutturato minimo** (insert/retry/mark-sent/error); **niente** refactor outbox né cleanup distruttivo — vedi § *Regola osservabilità*.
- **Non** «chiudere» l’audit con aggiustamenti enqueue/mark-sent **senza** § **Evidence anchors**.

### Regola osservabilità (log mancante ≠ bug)

Se manca traccia per correlare pending / insert / retry / delete: fix candidate primario = **logging minimo** (task figlio). **Non** modificare solo condizioni enqueue/mark-sent per far quadrare metriche incomplete; modificare **logica sync** solo con **anchor** § **Evidence anchors** (codice **e** comportamento osservato, incluso JVM se pertinente).

---

## Evidence anchors

Ogni **conclusione** dell’audit (root cause, bug, innocuità, «serve full sync») deve citare **almeno uno** tra:

1. **File + funzione** (o metodo) nel repo;
2. **Log tag + campi** rilevanti riprodotti con mascheramento privacy;
3. **Scenario O1–O6** + metriche **before/after** coerenti;
4. **Test JVM** pertinente (nome test / classe) che ancorano il comportamento.

**Vietato** chiudere con linguaggio privo di anchor: es. «probabile», «sembra», «dovrebbe», «presumibilmente» **senza** evidenza tabellata o riferimento puntale.

---

## Deliverable obbligatori della futura execution

La chiusura dell’audit (nella execution corrente, dopo `PLANNING → EXECUTION` approvato) deve produrre **documentazione compilata obbligatoria**: **(1)** lifecycle outbox, **(2)** root cause candidate, **(3)** fix candidate, **(4)** decisione finale per area (vedi sotto). Righe vuote non sono accettabili dove è richiesta evidenza (indicare esplicitamente «non osservato» / «non applicabile»).

### 1) Tabella — lifecycle `sync_event_outbox` (mappa codice → comportamento)

Obbligatorio compilare almeno una riga per **ogni path** trovato (aggiungere righe se necessario):

| source / function (file:anchor oppure nome funzione) | event type / payload kind | insert condition | retry condition | success condition | delete OR mark-sent condition | failure / backoff condition | metric / log associato (nome campo o tag) |
|-----------------------------------------------------|-----------------------------|------------------|-----------------|-------------------|-------------------------------|------------------------------|-------------------------------------------|
| *(template)* | | | | | | | |

### 2) Tabella — root cause candidate

| Ipotesi | Evidenza a favore | Evidenza contro | Rischio (funzionale / perf / UX) | Classificazione outbox § sopra | Decisione (no-op / monitor / fix app / backend / UX follow-up) |
|---------|-------------------|-----------------|-------------------------------------|-------------------------------|----------------------------------------------------------------|
| *(template)* | | | | | |

### 3) Tabella — fix candidate (solo proposta; no implementazione dentro TASK-069 planning)

| Fix | Sicurezza (bassa/med/alta — perdita dati, divergenza remote) | Test richiesti (JVM / scenario log) | Impatto UX | Impatto performance | Dentro scope audit → task figlio **app** | Fuori scope → **backend** separato | Task figlio consigliato (titolo sintetico / dipendenze) |
|-----|---------------------------------------------------------------|---------------------------------------|--------------|---------------------|--------------------------------------------------|----------------------------------|--------------------------------------------------------|
| *(template)* | | | | | | | |

Classificazione esecutiva attesa per riga (da riportare anche nei criteri #8): **safe-now** (intervento minimo sicuro immediato nel task figlio app) / **follow-up app** / **follow-up backend** / **no-op** / **solo monitoraggio**.

### 4) Decisione finale per area (obbligatorio)

Per ciascuna tra: **outbox**, **price push**, **HistorySession**, **GeneratedScreen (perf)**, **Options**, **CloudSyncIndicator** — una riga:

| Area | Esito | Valori ammessi |
|------|--------|----------------|
| *(una riga per area)* | **no-op / solo monitoraggio** OR **fix app** (→ task figlio) OR **fix backend separato** OR **UX copy follow-up** | Motivo in una frase + riferimento tabella root cause |

---

## Fix candidate (pianificate, non implementate in TASK-069)

1. **Log strutturato** per outbox insert / retry / success / failure (campi type, clientEventId, esito).
2. **Distinguere** nel log pending **storico** vs **delta nuovo** (snapshot prima/dopo ciclo).
3. **Coalescing** eventi `catalog_changed` / `prices_changed` massivi — solo con test JVM e sicurezza senza perdita semantica.
4. **Evitare enqueue** quando entrambi `catalogEventEmitted` e `priceEventEmitted` sono false — **solo se** confermato bug (non ottimismo prematuro).
5. **TTL / superseded marker** — solo se modello dati e test lo rendono sicuri.
6. **Retry/backoff** più osservabile (metriche già esistenti da estendere).
7. **Validazione payload prima dell’insert** in outbox (fall-fast locale).
8. **Test JVM:** payload compatto, retry, coalescing, no-op no enqueue — dove coperto da TASK-004.
9. **Backend/RPC/schema** — sempre task separato se emergono.

---

## GeneratedScreen — regole per l’audit (anti-refactor prematuro)

1. **Non** proporre subito refactor di `GeneratedScreen` / estrazioni ampie come fix TASK-069.
2. **Prima** verificare (lettura codice + eventualmente timing/metriche se in execution): se **`analyzeGridData`** (o path collegati) usano davvero **full catalog** / **`getAllProducts`**-like, pattern **O(n²)** su griglia, o letture workbook eccessive.
3. **Solo** se **misurato** o **confermato** in codice con evidenza, proporre **follow-up** separato (task figlio) con ottimizzazione tipo **subset barcode-only** dalla griglia.
4. **`ExcelViewModel` / ViewModel interessati** restano **fonte di verità** dello stato griglia/sync: niente spostamento di business logic verso UI per “sistemare” la performance.
5. **Vincoli di non regressione** espliciti per qualsiasi follow-up tecnico: **scanner**, **export**, **history**, **navigation**, **manual entry** devono restare intatti salvo task dedicato che li elenca.

### Piano GeneratedScreen (solo dopo verifica sopra)

Verificare se `GeneratedScreen` / `ExcelViewModel`:

- invocano **`getAllProducts()`** (o equivalente full catalog) quando basterebbe **subset barcode griglia**;
- eseguono analisi **O(n²)** o letture file **eccessive** su workbook grandi;
- impostano **dirty** o **sync cloud** quando l’operazione è **no-op**.

Ottimizzazioni **future** (non anticipate come impegni TASK-069): estrarre **barcode unici** dalla griglia; query **per subset**; **nessuna regressione** su scanner, export, history, navigation.

---

## Price push audit (piano)

Verificare post-TASK-068:

- price push **ok** quando `dirtyMarkedPrices=0` (no work inutile);
- batch **80** sufficiente nei casi reali;
- esistenza **price push massivo** reale oltre smoke;
- **Solo monitoraggio** vs futura ottimizzazione bulk/bridge/checksum — quest’ultima come **follow-up** senza schema nel task principale.

---

## HistorySessionSyncV2 audit (piano)

Chiarire:

- presenza **chunking** / limiti batch;
- **retry offline** sicuro vs duplicati;
- `sessionsAttempted=0` / `sessionsUploaded=0` **corretto** quando nessuna modifica;
- path **uno-per-uno** lenti;
- **log mancanti** rispetto a catalog sync.

---

## CloudSyncIndicator — regole UX/UI

TASK-069 è stato promosso a **`EXECUTION`** per audit diagnostico; qui **nessuna** UI/sync runtime — solo criteri per eventuale **task figlio**.

- **Ammessi** solo **ritocchi di copy** / chiarimento **stadio** (stage) come **proposta** in **follow-up separato** **se** emerge che un **pending alto** genera **confusione utente** (interpretazione come errore bloccante o bisogno di reset).
- Preferire UX **chiara e non allarmistica**:
  - sync **funzionante** ma **eventi diagnostici / outbox** ancora in coda ≠ errore bloccante;
  - distinguere **«in attesa di retry»** da **«serve sync completa manuale»** (incrociare `manualFullSyncRequired`, gap eventi, stringhe VM — da verificare su codice).
- **Non** **redesign completo** dell’indicator dentro TASK-069; tocchi minimi solo come **task UX figlio** con perimetro stretto.

### UX/UI follow-up quality bar

Solo **planning** — nessuna implementazione qui. Se nasce follow-up UI su indicator:

| Regola | Dettaglio |
|--------|-----------|
| Ambito | Solo **copy** e/o **stage** minimi |
| Coerenza | Material 3 + **stile esistente** dell’app |
| Verità di stato | **Nessuno** stato visivo nuovo non supportato da **ViewModel / tracker** reali |
| Tono | Copy **non allarmistico**; sync ok con retry/outbox in coda ≠ errore bloccante |
| Distinzione | Chiarire **sync funzionante con retry in coda** vs **serve sync completa manuale** (`manualFullSyncRequired` / gap — da verificare su codice) |

---

## Criteri di accettazione (chiusura TASK-069 dopo execution futura)

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Causa** (o classificazione con evidenza) di `syncEventOutboxPending` elevato / incremento documentata | S + M + log | — |
| 2 | **Nessun** reset/delete manuale outbox / dati come metodo di “fix” | Governance | — |
| 3 | **Nessuna regressione** TASK-068 (bulk product push / test JVM correlati) | B + JVM | — |
| 4 | **Nessuna regressione** sync GeneratedScreen / History session path | B + JVM / M | — |
| 5 | Log ed evidenze **privacy-safe** | Review | — |
| 6 | **Priorità P0** § *Priorità audit* chiuse con **evidenza** (codice + log/scenari obbligatori); **coerente con § Evidence anchors (#14)** | S + M + Doc | — |
| 7 | **Lifecycle outbox** documentato nella tabella § *Deliverable* (punto 1) — tutti i path rilevanti coperti o motivati come N/A | Doc | — |
| 8 | **Ogni fix candidate** dalla tabella deliverable (punto 3) assegnato a **safe-now** / **follow-up app** / **follow-up backend** / **no-op** / **solo monitoraggio** | Doc | — |
| 9 | **Follow-up backend** separato se necessità RPC/schema | Doc | — |
| 10 | **Proposta fix separata** per ogni area interessata (documento o task figli) coerente con § *Decisione finale per area* | Doc | — |
| 11 | Finché TASK-069 **non** è promosso a **`EXECUTION`:** nessuna raccolta live / build / test nel perimetro audit; dopo promozione, obbligatori check esecutore e deliverable come da piano | Governance | — |
| 12 | Eventuali interventi **UX copy** solo come **follow-up separato** classificato — **non** come surrogato di fix sync nella chiusura audit | Governance | — |
| 13 | **Piano execution** futuro operativo (deliverable + scenari obbligatori + matrice log) documentato; stato PLANNING non equivale a esecuzione completata | Governance | — |
| 14 | **P0** non può considerarsi chiuso **senza** § **Evidence anchors** (almeno un tipo per la conclusione principale) | Doc + Review | — |
| 15 | Se la classificazione P0 resta **`da_chiarire`** dopo codice + O1/O2 (± O5/O6 se necessari): deve esistere **task figlio** dedicato a **logging strutturato minimo** (no rework enqueue/mark-sent «a sensazione») | Doc | — |
| 16 | **Nessun** fix candidate che **cambi logica sync** può basarsi **solo** su metriche incomplete / assenza di correlazione — serve anchor § sopra o resta **logging prima** | Governance | — |
| 17 | Esiti **P1/P2** non devono **oscurare** o contraddire la **classificazione P0** (P1/P2 sono subordinati alla verità outbox documentata) | Review | — |

Legenda: B=Build, S=Static/code read, M=Manual/live.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task creato in **PLANNING** solo documentazione | Richiesta utente: no execution, no codice | 2026-04-27 |
| 2 | Schema/RPC/backend fuori dal piano principale | Governance sicurezza + separation of concerns | 2026-04-27 |
| 3 | Piano TASK-069 perfezionato (priorità P0–P2, deliverable, gate scope, scenari) senza cambio stato | Più eseguibile e verificabile restando PLANNING | 2026-04-27 |
| 4 | Refinement sequenza Step 0–6, stop/fallback, evidence anchors, quality bar UX | Efficienza execution futura; solo documentazione | 2026-04-27 |
| 5 | Passata editoriale/governance: Step 6 ↔ P1/P2, ordine O1–O6, Stop/osservabilità, UX solo follow-up | Coerenza interna; stato PLANNING invariato | 2026-04-27 |
| 6 | TASK-069 promosso a EXECUTION per audit diagnostico secondo Step 0–6; fix implementativi demandati a task figli. | Approvazione utente esplicita; perimetro execution = audit non distruttivo | 2026-04-27 |

---

## Planning (Claude)

### Analisi (sintesi ipotesi inserite nel piano)

1. **`syncEventOutboxPending` vs flag emit:** è plausibile che `catalogEventEmitted` / `priceEventEmitted` descrivano solo sotto-insiemi della pipeline (`record_sync_event` per tipi catalog/price), mentre **incrementi outbox** possano derivare da **enqueue** legati a **retry**, **tipi evento diversi**, **sync_events** correlati, o da **misurazione COUNT** che include righe non ancora elaborate — va dimostrato leggendo `InventoryRepository` e punti di insert/delete.
2. **Incremento +1 (915→916) con emit false:** suggerisce **nuovo insert** o **rivalutazione COUNT** dopo operazione marginale — priorità a correlazione temporale con **singolo RPC** o **batch retry**.
3. **TASK-068** ha affrontato throughput **product push**; questo task isola **diagnostica residua** senza confondere con regressioni bulk.
4. **HistorySessionSyncV2** è centralizzato in repository con tag costante — audit incrociato con metriche session upload.

### Piano di esecuzione (futuro — non avviare senza approvazione `PLANNING → EXECUTION`)

Seguire **§ Sequenza operativa** (Step 0–6): **P1 prima di P2**; CloudSyncIndicator **solo** follow-up UX/copy pianificato, non implementazione in TASK-069. Incrociare § **Evidence anchors** e § **Stop condition / fallback**.

Completare **§ Deliverable obbligatori**; applicare **classificazioni** + **albero decisionale**; **Gate anti-scope-creep** se serve backend; task figli per fix/logging/UX; baseline **TASK-004** solo nei task figli che modificano codice.

### Rischi identificati

- **Falso positivo “bug”** se i flag emit non coprono tutti gli insert outbox — evitare fix prematuri su enqueue.
- **Confusione UX** se pending alto ma sync funzionale — solo follow-up copy (§ CloudSyncIndicator), non surrogato fix sync.
- **Metriche incomplete** confuse per bug — mitigazione: § *Stop condition* / *Regola osservabilità*.

---

## Execution

### Esecuzione — 2026-04-27

**File modificati:**
- `docs/TASKS/TASK-069-audit-sync-residui-outbox-price-generated-history.md` — promosso stato task a `EXECUTION`, aggiunta decisione governance, avvio log audit.
- `docs/MASTER-PLAN.md` — riallineamento stato globale/backlog TASK-069 a `EXECUTION`.

**Step 0 — Governance:**
- TASK-069 confermato promosso da `PLANNING` a `EXECUTION` per audit diagnostico.
- Vincoli confermati attivi: no reset/delete/truncate outbox o dati locali/remoti; no schema/RPC/backend/RLS/trigger/migration; no fix logici senza evidence anchors; no UI redesign; `CloudSyncIndicator` solo proposta follow-up UX/copy.
- Verifica task attivi: `MASTER-PLAN` indicava nessun task attivo prima della promozione; nessun altro task `EXECUTION` in conflitto rilevato.

**Step 1 — Lettura statica P0 solo outbox:**
- File letti: `InventoryRepository.kt`, `SyncEventModels.kt`, `AppDatabase.kt`, `SupabaseSyncEventRemoteDataSource.kt`, `CatalogSyncStateTracker.kt`, `CatalogAutoSyncCoordinator.kt`, `CatalogSyncViewModel.kt`.
- Ricerca eseguita: `sync_event_outbox`, `SyncEventOutbox`, `retrySyncEventOutbox`, `catalogEventEmitted`, `priceEventEmitted`, `record_sync_event`, insert/enqueue/delete/count/retry/backoff.
- Evidence anchors statici principali:
  - `InventoryRepository.syncCatalogQuickWithEvents` (`app/src/main/java/.../data/InventoryRepository.kt:2317`) chiama `retrySyncEventOutbox` prima del push, poi `recordOrEnqueueSyncEvent` per catalogo e prezzi, poi conta `syncEventOutboxPending`.
  - `InventoryRepository.retrySyncEventOutbox` (`InventoryRepository.kt:3112`) ritenta fino a `SYNC_EVENT_OUTBOX_RETRY_LIMIT=20`, salta righe con `attemptCount >= SYNC_EVENT_OUTBOX_MAX_ATTEMPTS=5`, cancella solo su successo RPC.
  - `InventoryRepository.recordOrEnqueueSyncEvent` (`InventoryRepository.kt:3152`) ritorna `false` sia quando non c'e nulla da registrare sia quando la RPC fallisce e l'evento viene inserito in outbox.
  - `SyncEventOutboxDao` (`SyncEventModels.kt:199`) espone `insert`, `update`, `listPending`, `countPending`, `deleteById`; non esiste stato `sent`, solo cancellazione su successo.
  - `SupabaseSyncEventRemoteDataSource.recordSyncEvent` (`SupabaseSyncEventRemoteDataSource.kt:41`) usa RPC `record_sync_event`.

**Step 2 — Bozza lifecycle `sync_event_outbox` (statico, pre-scenari live):**

| source / function (file:anchor) | event type / payload kind | insert condition | retry condition | success condition | delete OR mark-sent condition | failure / backoff condition | metric / log associato |
|---|---|---|---|---|---|---|---|
| `InventoryRepository.syncCatalogQuickWithEvents` -> `recordOrEnqueueSyncEvent` (`InventoryRepository.kt:2317`, `2413`, `3152`) | `catalog_changed` oppure `catalog_tombstone`; `SyncEventEntityIds` con supplier/category/product remote ids, oppure ids vuoti + `changedCount` compatto se oltre budget 250 | Dopo push/tombstone catalogo, se `changedCount > 0` e `remote.recordSyncEvent(...)` fallisce; inserisce `SyncEventOutboxEntry` con `lastAttemptAtMs=now` e `lastErrorType` classificato | Alla sync quick successiva, prima dei push, tramite `retrySyncEventOutbox(owner, limit=20)` | RPC `record_sync_event` riuscita nella chiamata diretta: nessuna riga outbox creata; valore booleano `catalogEventEmitted=true` solo se tutti i chunk sono registrati | Su retry riuscito: `syncEventOutboxDao.deleteById(entry.id)`; non esiste mark-sent | Su retry fallito: `attemptCount + 1`, `lastAttemptAtMs=now`, `lastErrorType`; righe con `attemptCount >= 5` saltate. Nessun backoff temporale osservabile nel codice | Log `CatalogCloudSync sync_events_summary phase=quick`: `syncEventOutboxPending`, `syncEventOutboxRetried`, `catalogEventEmitted`; log coordinator `cycle=catalog_push`; `syncEventOutboxInserted` non loggato esplicitamente (da verificare con delta pending/log) |
| `InventoryRepository.syncCatalogQuickWithEvents` -> `recordOrEnqueueSyncEvent` (`InventoryRepository.kt:2425`, `3152`) | `prices_changed`; `SyncEventEntityIds.priceIds` con remote ids prezzo, oppure ids vuoti + `changedCount` compatto | Dopo `pushProductPricesToRemote`, se `changedCount > 0` e `remote.recordSyncEvent(...)` fallisce; inserisce outbox | Alla sync quick successiva tramite `retrySyncEventOutbox` | RPC `record_sync_event` riuscita: nessuna riga outbox creata; `priceEventEmitted=true` solo se tutti i chunk sono registrati | Su retry riuscito: `deleteById`; nessun mark-sent | Su retry fallito: update attempt/error; max tentativi 5 poi skip. Backoff temporale non osservabile | Log `CatalogCloudSync sync_events_summary phase=quick`: `syncEventOutboxPending`, `syncEventOutboxRetried`, `priceEventEmitted`; log `phase_metrics syncDomain=PRICES`; `syncEventOutboxInserted` non loggato esplicitamente |
| `InventoryRepository.retrySyncEventOutbox` (`InventoryRepository.kt:3112`) | Qualsiasi evento gia' in `sync_event_outbox` (`catalog_changed`, `catalog_tombstone`, `prices_changed`, eventuali tipi futuri in tabella) | N/A: questa funzione non inserisce nuove righe | `listPending(ownerUserId, 20)` ordinato per `createdAtMs`; skip se `attemptCount >= 5`; chiamata da quick sync e da drain sync-events | RPC `record_sync_event` riuscita per la riga pendente | `syncEventOutboxDao.deleteById(entry.id)`; non esiste mark-sent | RPC fallita: `update(entry.copy(attemptCount + 1, lastAttemptAtMs=now, lastErrorType=...))`; dopo 5 tentativi la riga resta pending ma non viene ritentata | `syncEventOutboxRetried` conta solo successi retry; `syncEventOutboxDeleted_or_marked_sent` non loggato esplicitamente; deducibile da retry success + pending delta |
| `InventoryRepository.drainSyncEventsFromRemote` (`InventoryRepository.kt:2521`) | Nessun nuovo evento locale; drain remoto + retry outbox esistente | N/A: codice letto non inserisce outbox nel drain | Chiama `retrySyncEventOutbox` prima di `drainSyncEventsInternal` se remote/capabilities disponibili | Drain remoto riuscito + eventuali retry riusciti | Solo retry riusciti cancellano outbox; drain non marca sent | Retry fallito aggiorna attempt/error; errori drain propagano `Result.failure` | Log `sync_events_summary phase=drain` con `catalogEventEmitted=false`, `priceEventEmitted=false`, pending/retried/fetched/processed; log coordinator `cycle=sync_events_drain` |
| `SyncEventOutboxDao` / schema Room (`SyncEventModels.kt:173`, `AppDatabase.kt:284`) | Persistenza locale owner-scoped con unique `(ownerUserId, clientEventId)` e indice `(ownerUserId, createdAtMs)` | DAO `insert` usa `OnConflictStrategy.IGNORE`; `clientEventId` include batch UUID + domain/type/chunk/fingerprint (`InventoryRepository.kt:3233`) | `listPending(owner, limit)` non filtra per `attemptCount`; lo skip e' nel repository | N/A | `deleteById` e' unica rimozione osservata | Nessuna TTL/cleanup/backoff temporale nello schema; pending storico resta contato anche se non piu' ritentabile dopo max attempts | `countPending(owner)` alimenta `CatalogSyncSummary.syncEventOutboxPending`; UI hint in `CatalogSyncViewModel.buildCatalogDetail` (`CatalogSyncViewModel.kt:689`) |

**Osservazione P0 statica:**
- `catalogEventEmitted=false` / `priceEventEmitted=false` e' semanticamente ambiguo: puo' indicare sia "nessun evento da registrare" (`ids.isEmpty && changedCount<=0`) sia "RPC fallita e outbox inserita" (`recordOrEnqueueSyncEvent` ritorna `false` dopo insert). Quindi un incremento pending con flag emit `false` e' compatibile col codice se almeno una RPC `record_sync_event` fallisce dopo un push reale.
- Nel solo path `drainSyncEventsFromRemote`, con `catalogEventEmitted=false` e `priceEventEmitted=false`, il codice non inserisce nuove righe outbox: il pending dovrebbe restare uguale o diminuire se retry riesce. Incrementi in questo scenario richiedono log/live per escludere una quick sync concorrente o altro trigger.
- Mancano metriche esplicite `syncEventOutboxInserted` e `syncEventOutboxDeleted_or_marked_sent`; oggi si osservano `pending` e `retried` ma non il delta insert/delete puntuale. Campo marcato "da verificare con log" negli scenari O1/O2.

**Step 3 — Scenari O1/O2 live:**
- Ambiente: emulator Android `emulator-5554`, app `com.example.merchandisecontrolsplitview`, utente autenticato mascherato; URL/JWT/token non riportati.
- File usato: `task069_o1_export_Database_2026_04_20_22-52-52.xlsx` copiato in `/sdcard/Download/`; contenuto dati privati non riportato.
- Nota privacy: nei log `import_dirty_marking` erano presenti campioni di nomi/prodotti/barcode; nel task sono riportate solo metriche aggregate.

| Scenario | Trigger | Before | After | syncEventOutboxInserted | syncEventOutboxRetried | syncEventOutboxDeleted_or_marked_sent | catalogEventEmitted | priceEventEmitted | productsPushed | pricesPushed | sessionsUploaded | durationMs | Outcome | Classification |
|---|---|---|---|---|---|---|---|---|---:|---:|---:|---:|---|---|
| O1a | Primo import del file selezionato in Database | pending 916 (UI Options prima dello scenario) | pending 918 (`sync_events_summary phase=quick`) | 2 inferiti da delta pending + retry 0 + codice `recordOrEnqueueSyncEvent`; righe poi confermate da query read-only outbox: id 917 `catalog_changed`, id 918 `prices_changed`, `attemptCount=0`, `PayloadValidation` | 0 | 0 osservati | false | false | 6033 | 75569 | 0 (`HistorySessionSyncV2 resume_tick`) | 152277 (`cycle=catalog_push outcome=ok reason=local_commit`) | Il file non era identico al DB locale corrente: `FULL_IMPORT SUCCESS ... classificazione_risultato=delta_reale_dataset_diverso`; import apply consentita, push catalogo/prezzi riuscito; eventi sync non registrati via RPC e accodati | `outbox_payload_validation_blocked` + `outbox_head_of_line_blocked` (classificazione operativa, vedi P0) |
| O1b | Stesso file selezionato di nuovo dopo quiet window | pending 918 | pending 918 (`sync_events_summary phase=quick`) | 0 osservati/inferiti | 0 | 0 | false | false | 0 | 0 | 0 (`HistorySessionSyncV2 resume_tick`) | 2180 (`cycle=catalog_push outcome=ok reason=foreground`) | Preview import: `New products to add: 0`, `Products to update: 0`, `Errors found: 0`; `Confirm Import` disabilitato; nessuna apply eseguita | no-op import identico; pending stabile |
| O2a | Restart app dopo import identico/no-op, foreground drain automatico | pending 918 | pending 918 (`sync_events_summary phase=drain`) | 0 | 0 | 0 | false | false | 0 | 0 | 0 (`HistorySessionSyncV2 login_fresh_tick`) | 681 (`cycle=sync_events_drain outcome=ok reason=foreground`) | Drain remoto riuscito, nessun evento fetch/process, nessun retry riuscito | no-op foreground drain; pending storico stabile |
| O2b | Options -> Quick sync manuale dopo restart | pending 918 | pending 918 (`quick_sync ok=true`) | 0 | 0 | 0 | false | false | 0 | 0 | 0 | 736 (`sync_finish ok=true`) | Manual quick sync riuscita; prodotti/prezzi dirty zero; outbox non scende per retry 0 | no-op manual quick sync; pending storico bloccato in testa |

**Evidence anchors scenario O1/O2:**
- `DB_IMPORT FULL_IMPORT SUCCESS`: O1a classificato `delta_reale_dataset_diverso`; O1b ancora `delta_reale_dataset_diverso` a livello fingerprint complessivo per conteggio price history, ma UI preview prodotti = 0/0 e `Confirm Import` disabilitato.
- `CatalogCloudSync phase_metrics`: O1a `PUSH_PRODUCTS productsDirty=6033 productsPushed=6033 bulkEnabled=true batchSize=100 batchCount=61 splitFallbackCount=0 singleFallbackCount=0`; O1a `SYNC_PRICES_PUSH pricesEvaluated=75569 pricesPushed=75569 batchSize=80 batchCount=945`.
- `CatalogCloudSync sync_events_summary`: O1a pending 918, retry 0, emit flags false; O1b/O2 pending stabile 918, retry 0, emit flags false.
- Query read-only aggregata su snapshot locale Room `sync_event_outbox`: totale 918; `attemptCount>=5` = 20; `attemptCount<5` = 898; prime 20 righe FIFO hanno `attemptCount=5` e `lastErrorType=PayloadValidation`; eventi totali `catalog_changed=206`, `prices_changed=712`; ultimi due eventi creati dallo scenario O1a = `catalog_changed` e `prices_changed`, `attemptCount=0`, `PayloadValidation`.
- Nessun reset/delete/truncate/cleanup outbox o dati locali/remoti eseguito; la copia temporanea read-only del DB usata per conteggi aggregati e' stata rimossa dal filesystem locale.

**Step 4 — Classificazione P0:**

| Root cause candidate | Classificazione | Evidence anchors | Impatto / note |
|---|---|---|---|
| Le nuove righe outbox durante O1a non sono generate da import identico, ma da un import con delta reale: dopo push di 6033 prodotti e 75569 prezzi, `record_sync_event` fallisce per catalogo e prezzi; `recordOrEnqueueSyncEvent` ritorna `false`, inserisce outbox e i flag `catalogEventEmitted=false` / `priceEventEmitted=false` restano falsi. | `outbox_payload_validation_blocked` per i nuovi eventi O1a; non `outbox_bug_in_insert_condition` con le evidenze correnti | `InventoryRepository.kt:3152`; log `sync_events_summary phase=quick`; query outbox ultimi id 917/918 `PayloadValidation` | Spiega incremento 916 -> 918. Non autorizza fix client fragile: serve capire contratto payload/RPC in task figlio o logging strutturato. |
| Il pending alto resta stabile per blocco FIFO: `retrySyncEventOutbox` legge solo i primi 20 pending (`listPending(..., 20)` ordinati per `createdAtMs`), ma se hanno `attemptCount>=5` li salta e non prosegue verso le 898 righe sotto soglia. | `outbox_orphaned_requires_followup` / `outbox_head_of_line_blocked` (sotto-classificazione operativa TASK-069); secondaria `outbox_payload_validation_blocked` per errore remoto | `InventoryRepository.kt:3112`; `SyncEventOutboxDao.listPending` in `SyncEventModels.kt`; query read-only: prime 20 righe `attemptCount=5`, totale 918, belowMax 898; O1/O2 `syncEventOutboxRetried=0` | Spiega perche' pending non scende e perche' `retry=0` anche con 898 eventi teoricamente ritentabili. Non e' innocuo come UX/metrica, ma non blocca product/price push. |
| Nel solo drain foreground/manuale senza dirty locali non vengono inserite nuove righe outbox. | no-op / solo monitoraggio | O2a/O2b pending 918 -> 918; `productsPushed=0`, `pricesPushed=0`, `syncEventsFetched=0`, `syncEventsProcessed=0` | O5/O6 non necessari per classificare P0 in questa execution; da usare solo se un task figlio vuole riprodurre offline retry. |

**Step 5 — Stop condition:**
- P0 non resta `da_chiarire`: O1/O2 + query read-only dimostrano incremento da emit fallito/enqueue e blocco FIFO su max attempts.
- Non e' stato implementato alcun fix sync. Sono necessari task figli per logging/fix app/backend; nessun cambio enqueue/mark-sent/refactor dentro TASK-069.

**Step 6 — P1/P2 subordinati a P0:**
- **P1 / TASK-068 bulk product push:** no-regression live osservata in O1a: `bulkEnabled=true`, `batchSize=100`, `batchCount=61`, `productsPushed=6033`, fallback 0. Nessun codice modificato, quindi baseline JVM TASK-004 non applicabile in questa execution documentale/diagnostica.
- **P1 / price push:** O1a conferma batch price `80` con `pricesPushed=75569`, `batchCount=945`; O1b/O2 confermano dirty zero: `pricesEvaluated=0`, `pricesPushed=0`, nessun lavoro prezzo inutile.
- **P1 / HistorySessionSyncV2:** O1/O2 mostrano `sessionsUploaded=0` quando non ci sono sessioni dirty; dopo restart: `pull_apply outcome=ok inserted=0 updated=0 skipped=13 dirtyLocalSkips=13 failed=0`, quindi nessuna evidenza di regressione nel perimetro osservato.
- **P2 / GeneratedScreen:** non aperto deep dive perche' P0 e' spiegato da outbox/RPC/retry e non da `GeneratedScreen`. Decisione: nessun refactor/proposta tecnica senza misurazione specifica `analyzeGridData`.
- **P2 / OptionsScreen:** trigger osservati: Quick sync manuale da `OptionsScreen` (`OptionsScreen.kt:249`) chiama `CatalogSyncViewModel.syncCatalogQuick` (`CatalogSyncViewModel.kt:907`); Complete sync resta `refreshCatalog` (`CatalogSyncViewModel.kt:797`). Nessuna modifica UI.
- **P2 / CloudSyncIndicator / copy:** `CatalogSyncViewModel.buildCatalogDetail` (`CatalogSyncViewModel.kt:689`) aggiunge "sync notifications will retry later" quando `syncEventOutboxPending>0`; `CloudSyncIndicator` (`ui/components/CloudSyncIndicator.kt:57`) mostra stato transitorio di sync, non dettaglio outbox. Proposta solo follow-up UX/copy: distinguere "sync catalogo/prezzi riuscita con notifiche diagnostiche in coda" da "serve sync completa manuale".

**Deliverable 2 — Root cause candidate:**

| Candidate | Stato | Evidence anchors | Decisione |
|---|---|---|---|
| Pending alto causato da backlog storico `PayloadValidation` con blocco FIFO sui primi 20 a `attemptCount=5`. | Confermato | `InventoryRepository.retrySyncEventOutbox`; query read-only aggregata `total=918`, `atOrAboveMax=20`, `belowMax=898`, prime 20 `attemptCount=5`; O1/O2 `syncEventOutboxRetried=0` | Fix app in task figlio: evitare head-of-line skip e rendere osservabile il retry; nessuna pulizia dati dentro TASK-069. |
| Incremento pending con emit false causato da `recordOrEnqueueSyncEvent` che ritorna false sia per "nessun evento" sia per "fallimento RPC + enqueue". | Confermato per O1a | `InventoryRepository.recordOrEnqueueSyncEvent`; O1a pending 916 -> 918, nuovi id 917/918 `PayloadValidation`, `catalogEventEmitted=false`, `priceEventEmitted=false` | Logging/fix app + possibile backend contract follow-up; non dedurre bug insert da soli flag emit. |
| Import identico genera nuovi pending. | Non confermato / no-op | O1b preview 0/0, `Confirm Import` disabilitato; pending stabile 918 | No-op per import identico nel perimetro osservato. |
| Drain/manual quick sync senza modifiche locali genera nuovi pending. | Non confermato / no-op | O2a/O2b pending stabile, push zero | Solo monitoraggio. |

**Deliverable 3 — Fix candidate classificati:**

| Fix candidate | Classificazione | Evidence | Note / task figlio consigliato |
|---|---|---|---|
| Logging strutturato minimo outbox: insert/retry/skip/success/failure con `eventType`, `attemptCount`, `clientEventId` mascherabile, `lastErrorType`, delta pending before/after. | safe-now / follow-up app | Metriche mancanti `syncEventOutboxInserted` e `syncEventOutboxDeleted_or_marked_sent`; O1/O2 richiedono inferenza | Task figlio app consigliato: "Logging strutturato outbox e metriche delta pending". |
| Modificare retry per evitare blocco FIFO quando le prime 20 righe sono a max attempts (es. query solo ritentabili, skip persistenti fuori finestra, o cursore/limite diverso). | follow-up app | Query read-only: prime 20 `attemptCount=5`, belowMax 898 mai raggiunti; `retry=0` in O1/O2 | Richiede test JVM su `DefaultInventoryRepositoryTest`; non implementato ora. |
| Validazione/contratto payload `record_sync_event` per `PayloadValidation` catalog/price. | follow-up backend + follow-up app per diagnostica | Tutte le 918 righe hanno `PayloadValidation`; `SupabaseSyncEventRemoteDataSource.recordSyncEvent` chiama RPC `record_sync_event` | Se serve schema/RPC/RLS, STOP e task backend separato. App puo' aggiungere log/error body privacy-safe prima. |
| Pulizia manuale/TTL immediata degli eventi max-attempt per abbassare pending. | no-op in TASK-069 | Vincoli governance; rischio perdita semantica; nessuna prova che eventi siano superseded | Vietato in questo task; eventuale coalescing/TTL solo task figlio con test e prova semantica. |
| Coalescing massivo `catalog_changed` / `prices_changed`. | follow-up app, non safe-now | Pending composto da 206 catalog + 712 prices, ma supersedence non dimostrata | Solo dopo prova che stato remoto copre eventi; non usare come workaround fragile. |
| Price push bulk/checksum ulteriore. | solo monitoraggio | O1a batch 80 riuscito, O1b/O2 dirty zero | Non prioritario rispetto a outbox. |
| GeneratedScreen subset barcode-only per `analyzeGridData`. | no-op ora / follow-up solo se misurato | Nessun anchor P0 su GeneratedScreen; deep dive non necessario | Non proporre refactor senza costo reale. |
| Options/CloudSyncIndicator copy per pending alto. | UX copy follow-up | UI Options mostra "Sending changes: 918 sync notifications will retry later" anche quando product/price sync e quick sync sono ok | Copy non allarmistico; nessun nuovo stato visuale senza ViewModel/tracker. |

**Deliverable 4 — Decisione finale per area:**

| Area | Esito | Motivo / evidence |
|---|---|---|
| outbox | fix app + follow-up backend separato | App: head-of-line retry bloccato e logging insufficiente. Backend: `PayloadValidation` su `record_sync_event` richiede contratto RPC/payload in task separato se confermato da log error body privacy-safe. |
| price push | solo monitoraggio | O1a push massivo prezzi riuscito (`pricesPushed=75569`, batch 80); O1b/O2 dirty zero. |
| HistorySession | solo monitoraggio | `sessionsUploaded=0` coerente con assenza dirty; pull apply post-restart ok con dirty-local skips documentati. |
| GeneratedScreen perf | no-op ora | P0 spiegato senza GeneratedScreen; nessuna evidenza di `analyzeGridData` come causa. |
| Options | UX copy follow-up opzionale | Trigger Quick/Complete chiari; dettaglio outbox puo' confondere quando sync e' funzionante ma notifiche restano in coda. |
| CloudSyncIndicator | UX copy follow-up, nessun redesign | Indicator mostra stato sync transitorio; proposta solo copy/stage per distinguere retry in coda da richiesta di sync completa manuale. |

**Check obbligatori:**

| Check | Stato | Note |
|---|---|---|
| Build Gradle | ⚠️ N/A | Task execution ha modificato solo documentazione/governance; nessun Kotlin/risorse/build config modificato. |
| Lint | ⚠️ N/A | Solo documentazione. |
| Warning nuovi | ⚠️ N/A | Nessuna modifica codice. |
| Coerenza con planning | ✅ ESEGUITO | Seguiti Step 0-6: P0 statico, lifecycle pre-live, O1/O2, classificazione, P1/P2 subordinati. |
| Criteri di accettazione | ✅ ESEGUITO | Criteri verificati nel perimetro audit; TASK-069 resta `EXECUTION`, non `DONE`, per eventuale review/validazione planner. |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: N/A in questa execution, perche' non sono stati modificati repository/ViewModel/sync code.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: i task figli app su retry/logging dovranno eseguire/aggiornare `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest` e test coordinator pertinenti.

**Criteri di accettazione — stato execution:**

| # | Stato | Evidenza sintetica |
|---|---|---|
| 1 | ESEGUITO | Root cause outbox documentata: `PayloadValidation` + blocco FIFO retry. |
| 2 | ESEGUITO | Nessun reset/delete/truncate/cleanup outbox o dati eseguito. |
| 3 | ESEGUITO | No-regression live TASK-068: bulk product push O1a batch 100/fallback 0; JVM N/A per nessun cambio codice. |
| 4 | ESEGUITO | History session osservato; GeneratedScreen non toccato e non necessario per P0. |
| 5 | ESEGUITO | Log riportati privacy-safe; token/URL/JWT e dati prodotto omessi. |
| 6 | ESEGUITO | P0 chiuso con codice + O1/O2 + query read-only. |
| 7 | ESEGUITO | Lifecycle outbox compilato sopra. |
| 8 | ESEGUITO | Fix candidate classificati sopra. |
| 9 | ESEGUITO | Backend/RPC solo follow-up separato; nessuna modifica backend. |
| 10 | ESEGUITO | Decisione finale per area compilata. |
| 11 | ESEGUITO | Promozione a `EXECUTION` avvenuta prima di raccolta live. |
| 12 | ESEGUITO | UX solo follow-up; nessuna modifica UI. |
| 13 | ESEGUITO | Execution operativa documentata; task non chiuso come DONE. |
| 14 | ESEGUITO | Evidence anchors presenti per conclusione P0. |
| 15 | ESEGUITO | P0 non e' `da_chiarire`; logging strutturato resta comunque follow-up app consigliato. |
| 16 | ESEGUITO | Nessun fix logico sync implementato o proposto senza anchor. |
| 17 | ESEGUITO | P1/P2 subordinati e coerenti con classificazione P0. |

**Incertezze:**
- Il dettaglio remoto esatto del `PayloadValidation` non e' osservabile dai log attuali senza logging error body privacy-safe o diagnostica backend/RPC separata.
- Non e' stata dimostrata supersedence sicura degli eventi outbox storici: nessun coalescing/TTL puo' essere considerato safe-now senza task figlio e test.

**Handoff notes:**
- Prossimo task app consigliato: logging outbox + fix head-of-line retry con test JVM.
- Prossimo task backend consigliato: verificare contratto RPC `record_sync_event` per payload `catalog_changed` / `prices_changed` e causa `PayloadValidation`.
- Lasciare `Review` e `Fix` vuoti finche' non avviene review dedicata; TASK-069 non viene chiuso come `DONE` in questa execution.


---

## Review

### Review — 2026-04-27

**Revisore:** Claude (planner)

**Metodologia:** review repo-grounded; verifica anchor citati nell'Execution contro il codice reale (`InventoryRepository.kt`, `SyncEventModels.kt`, `AppDatabase.kt`, `CatalogSyncStateTracker.kt`, `CatalogAutoSyncCoordinator.kt`, `CatalogSyncViewModel.kt`, `OptionsScreen.kt`, `CloudSyncIndicator.kt`); coerenza con scenari live O1/O2 e snapshot read-only outbox; coerenza governance MASTER-PLAN; verifica perimetro non distruttivo.

**Verifiche puntuali sul codice:**

| Affermazione audit | Verifica | Esito |
|---|---|---|
| `retrySyncEventOutbox` usa `listPending(owner, 20)` ordinato per `createdAtMs` | `InventoryRepository.kt:3117`; DAO `SyncEventModels.kt:207-215` (`ORDER BY createdAtMs ASC, id ASC LIMIT :limit`) | ✅ confermato |
| Skip via `continue` se `attemptCount >= SYNC_EVENT_OUTBOX_MAX_ATTEMPTS=5` | `InventoryRepository.kt:3119`; costante `:3328` | ✅ confermato — head-of-line block reale |
| `listPending` non filtra per `attemptCount` | DAO query `SyncEventModels.kt:207-215`: nessun `WHERE attemptCount` | ✅ confermato |
| `SYNC_EVENT_OUTBOX_RETRY_LIMIT=20` | `InventoryRepository.kt:3327` | ✅ confermato |
| Retry conta solo successi (no insert/skip/delete espliciti) | `InventoryRepository.kt:3134-3147`: incrementa `retried` solo su `result.isSuccess`; aggiorna su failure ma non logga separatamente; nessuna metrica `inserted` o `skipped` esposta | ✅ confermato |
| `recordOrEnqueueSyncEvent` ritorna `false` per "nessun evento" e per "RPC fail + enqueue" | `InventoryRepository.kt:3163-3215`: `if (ids.isEmpty && totalChangedCount <= 0) return false`; loop chunk fallisce → `allRecorded=false` + insert outbox + return `allRecorded` | ✅ confermato — ambiguità semantica reale |
| DAO espone solo `insert`/`update`/`listPending`/`countPending`/`deleteById`; nessuno stato `sent` | `SyncEventModels.kt:200-222` | ✅ confermato |
| `syncCatalogQuickWithEvents` chiama retry → push → `recordOrEnqueueSyncEvent` catalog (`:2413`) e prices (`:2425`) → count pending finale | `InventoryRepository.kt:2317-2519` (path completo, retry `:2353`, catalog emit `:2413`, price emit `:2425`, count finale `:2452`) | ✅ confermato |
| `drainSyncEventsFromRemote` chiama `retrySyncEventOutbox` ma non inserisce outbox | `InventoryRepository.kt:2521-2598` (retry `:2551`, log con emit flags hardcoded `false` `:2567-2568`) | ✅ confermato |
| Schema outbox indici `(ownerUserId, clientEventId)` UNIQUE + `(ownerUserId, createdAtMs)` | `SyncEventModels.kt:175-178`; `AppDatabase.kt:284` | ✅ confermato |
| Hint UI "sync notifications will retry later" su pending>0 | `CatalogSyncViewModel.kt:689` `buildCatalogDetail` | ✅ confermato |
| Quick sync manuale da `OptionsScreen` chiama `CatalogSyncViewModel.syncCatalogQuick` | `OptionsScreen.kt:249`, `CatalogSyncViewModel.kt:907` | ✅ confermato |

**Criteri di accettazione TASK-069:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Causa/classificazione `syncEventOutboxPending` documentata | ✅ | `outbox_payload_validation_blocked` + `outbox_head_of_line_blocked` con anchor codice + log + query read-only |
| 2 | Nessun reset/delete manuale outbox | ✅ | Snapshot read-only; copia temporanea DB rimossa post-query; nessuna scrittura distruttiva |
| 3 | Nessuna regressione TASK-068 | ✅ | O1a `bulkEnabled=true batchSize=100 batchCount=61 fallback=0`; nessun cambio codice |
| 4 | Nessuna regressione GeneratedScreen / History | ✅ | O1/O2 osservato pull/apply ok, dirty-local skips coerenti; nessun deep dive necessario perché P0 spiegato altrove |
| 5 | Log/evidenze privacy-safe | ✅ | Nessun JWT/URL/token; barcode/id campioni omessi |
| 6 | P0 chiusa con evidence anchors | ✅ | Codice + O1/O2 + query read-only `total=918, atOrAboveMax=20, belowMax=898` |
| 7 | Lifecycle outbox documentato | ✅ | Tabella Step 2 con 5 path coperti |
| 8 | Fix candidate classificati safe-now/follow-up app/follow-up backend/no-op/monitor | ✅ | Tabella Deliverable 3 |
| 9 | Follow-up backend separato se necessità RPC/schema | ✅ | TASK-071 creato 2026-04-27 |
| 10 | Decisione finale per area | ✅ | Tabella Deliverable 4 (outbox/price/HistorySession/GeneratedScreen/Options/CloudSyncIndicator) |
| 11 | Promozione a EXECUTION prima di raccolta live | ✅ | Step 0 confermato; MASTER-PLAN allineato |
| 12 | UX solo follow-up | ✅ | Nessuna modifica UI in TASK-069 |
| 13 | Piano execution operativo documentato | ✅ | Step 0-6 + matrice scenari |
| 14 | Evidence anchors per conclusione P0 | ✅ | File+funzione + log tag + scenari + query |
| 15 | Logging strutturato come task figlio se P0 `da_chiarire` | ✅ N/A | P0 non `da_chiarire`; logging strutturato comunque accolto in TASK-070 come safe-now |
| 16 | Nessun fix logico sync senza anchor | ✅ | Nessun fix codice TASK-069 |
| 17 | P1/P2 non oscurano P0 | ✅ | P1 conferma no-regression; P2 GeneratedScreen no-op ora |

**Verifica governance:**

- TASK-069 era `EXECUTION` come task attivo in MASTER-PLAN ✅
- TASK-068 resta `PARTIAL` (non chiuso da questo task) ✅
- TASK-067 resta `DONE ACCEPTABLE` ✅
- TASK-066 resta `DONE` ✅
- Nessun altro task `EXECUTION` in conflitto ✅
- Vincoli rispettati: no reset/delete/truncate, no schema/RPC/backend, no UI redesign, no coalescing/TTL senza prova semantica ✅

**Verifica qualità piano fix:**

L'ordine fix candidate proposto dall'audit è coerente con la priorità tecnica (logging prima, head-of-line poi, contratto backend, UX copy ultimo, coalescing/TTL solo se safe). TASK-070 raggruppa appropriatamente (1) logging strutturato + (2) head-of-line retry come safe-now app-side; TASK-071 isola (3) contratto backend.

**Problemi trovati:** nessuno bloccante.

Note minori non bloccanti:
- La sotto-classificazione `outbox_head_of_line_blocked` non è nella lista canonica § *Classificazioni possibili dell'outbox*; l'audit la introduce esplicitamente come "operativa". Coerente con § *Stop condition* (non si chiude con linguaggio vago); accettabile.
- `syncEventOutboxRetried=0` registrato nel log copre sia il caso "nessuno ritentabile nei primi 20" sia "nessun successo" — TASK-070 introdurrà metriche delta osservabili che renderanno il caso esplicito.

**Verdetto:** **APPROVED**. Audit completo, root cause P0 ancorata, deliverable compilati, governance rispettata, perimetro non distruttivo mantenuto, follow-up TASK-070 (app) e TASK-071 (backend) creati e allineati.

**Check eseguiti:**

| Check | Stato | Note |
|---|---|---|
| Lettura statica codice | ✅ | Tutti gli anchor citati verificati |
| `git diff --check` | ✅ | Solo modifiche documentali (.md) |
| `assembleDebug` | ⚠️ N/A | Solo documentazione modificata |
| `lintDebug` | ⚠️ N/A | Solo documentazione modificata |
| `testDebugUnitTest` | ⚠️ N/A | Solo documentazione modificata; baseline TASK-004 non applicabile |
| Coerenza MASTER-PLAN ↔ TASK-069 | ✅ | Aggiornato in chiusura review |

**Rischi residui:**

- Backlog 918 righe outbox `PayloadValidation` resta in coda finché TASK-070 (retry head-of-line) e TASK-071 (contratto) non sono eseguiti. Nessun impatto su product/price push (verificato O1a). Possibile confusione UX su "Sending changes: 918 sync notifications will retry later" — copy follow-up opzionale.
- Snapshot read-only puntuale 2026-04-27: lo stato outbox può evolvere; TASK-070 dovrà operare sullo stato corrente al momento dell'esecuzione, non su questo snapshot.
- Causa esatta `PayloadValidation` non osservata dal client senza estensione log error body — TASK-071 deve usare repo Supabase locale o coordinarsi con TASK-070 per logging privacy-safe.

---

## Fix

Nessun fix codice applicato in TASK-069. Audit non distruttivo: la review non ha richiesto modifiche al codice né al planning. Eventuali fix sono demandati ai task figli:

- **TASK-070** (app) — retry head-of-line + logging strutturato outbox.
- **TASK-071** (backend) — verifica contratto RPC `record_sync_event` / cause `PayloadValidation`.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-27 |
| Tutti i criteri ✅? | Sì (17/17 ESEGUITI con evidence anchors) |
| Rischi residui | Backlog 918 righe `PayloadValidation` da smaltire via TASK-070/TASK-071; nessun impatto su sync funzionale corrente (push prodotti/prezzi ok). |

**Modalità chiusura:** `DONE` (audit diagnostico completo). Non è un task implementativo; il successo è la classificazione corretta + follow-up creati, non l'abbassamento del numero pending.

**Cosa è stato fatto:**

1. Audit repo-grounded del lifecycle `sync_event_outbox` con 5 path documentati.
2. Root cause P0 dimostrata con triangolazione codice + scenari live O1/O2 + query read-only Room: backlog `PayloadValidation` + blocco FIFO retry head-of-line sui primi 20 record a `attemptCount=5`.
3. Incremento O1a 916→918 spiegato come delta reale + RPC `record_sync_event` fallita + enqueue catalog/prices (non import identico).
4. O1b (import identico) e O2 (drain/quick sync manuale) confermano: pending stabile, nessuna inserzione, no-op.
5. P1 verifica no-regression TASK-068 (bulk batch 100, fallback 0, prices batch 80 ok).
6. P2 conferma: GeneratedScreen non causa P0 → no deep dive; Options/CloudSyncIndicator solo follow-up UX/copy opzionale.
7. Deliverable 1-4 compilati (lifecycle, root cause, fix candidate, decisione per area).
8. Vincoli governance rispettati: nessun reset/delete/truncate, nessuno schema/RPC/backend, nessuna modifica UI.

**Cosa NON è stato fatto (intenzionalmente):**

- Nessuna modifica codice (Kotlin/risorse/Gradle).
- Nessun cleanup outbox.
- Nessun cambio schema Room/Supabase, RPC, RLS, trigger, migration.
- Nessuna implementazione fix retry head-of-line (demandato a TASK-070).
- Nessuna verifica contratto RPC `record_sync_event` lato backend (demandata a TASK-071).
- Nessun copy/redesign `CloudSyncIndicator` o `OptionsScreen` (proposta UX/copy opzionale).

**Follow-up creati:**

- **TASK-070** — `BACKLOG`, `ALTA` — Outbox retry head-of-line + logging strutturato (app side).
- **TASK-071** — `BACKLOG`, `ALTA` — Verifica contratto RPC `record_sync_event` / cause `PayloadValidation` (backend side).

**Follow-up opzionali (non aperti come task):**

- Copy non allarmistico `CloudSyncIndicator` / `OptionsScreen` per distinguere "sync funzionante con notifiche diagnostiche in coda" da "serve sync completa manuale". Da aprire come task UX figlio se l'utente lo conferma necessario.

---

## Riepilogo finale

TASK-069 chiude come audit diagnostico repo-grounded. La review ha verificato puntualmente ogni anchor (file:linea, log, scenari, query) contro il codice reale: tutte le affermazioni sono confermate. La root cause P0 è solida: head-of-line block + ambiguità `recordOrEnqueueSyncEvent` + backend `PayloadValidation`. I follow-up sono stati separati con cura tra app (TASK-070) e backend (TASK-071) per rispettare il gate anti-scope-creep. Nessuna modifica distruttiva, nessuna pulizia outbox, nessun cambio backend. Sync utente (push prodotti/prezzi) **non bloccata**: il pending alto è notificazione diagnostica, non errore funzionale.

---

## Handoff

- **Stato:** TASK-069 `DONE` 2026-04-27. **TASK-068** resta `PARTIAL`. **TASK-067** `DONE ACCEPTABLE`. **TASK-066** `DONE`.
- **Root cause P0:** `outbox_payload_validation_blocked` + `outbox_head_of_line_blocked` (sotto-classificazione operativa). Anchor: `InventoryRepository.kt:3112-3149` + `SyncEventModels.kt:200-222` + scenari O1/O2 + snapshot 918/20/898.
- **Prossimo task consigliato (app):** **TASK-070** — retry head-of-line + logging strutturato outbox; può procedere indipendentemente da TASK-071.
- **Prossimo task consigliato (backend):** **TASK-071** — contratto RPC `record_sync_event` / `PayloadValidation`; richiede repo Supabase locale o log Postgres privacy-safe.
- **Vincoli ereditati:** nessun reset/delete/truncate outbox; nessun cambio schema/RPC/RLS/trigger/migration senza task backend dedicato; nessun coalescing/TTL senza prova semantica.
- **Snapshot evidenze 2026-04-27:** O1a `productsPushed=6033`, `pricesPushed=75569`, `bulkEnabled=true batchSize=100 batchCount=61`, `pricesEvaluated=75569 batchSize=80 batchCount=945`, durata `cycle=catalog_push outcome=ok 152277ms`. O1b/O2 pending stabile 918, push zero, dirty zero. Snapshot Room: total=918, attemptCount>=5: 20, belowMax: 898, prime 20 FIFO con attemptCount=5 e lastErrorType=PayloadValidation.
