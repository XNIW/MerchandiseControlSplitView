# TASK-064 — Diagnosi outbox PayloadValidation e riallineamento baseline A/B

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-064 |
| Stato | `PLANNING` |
| Priorità | `ALTA` |
| Area | Supabase sync / outbox / QA baseline / multi-device |
| Creato | 2026-04-26 |
| Ultimo aggiornamento | 2026-04-26 — inizializzazione planner repo-grounded; nessuna execution |

### Governance check

| Verifica | Esito |
|----------|-------|
| `MASTER-PLAN`: TASK-063 `BLOCKED`, non `DONE` | OK |
| TASK-062 `DONE` | OK |
| TASK-061 `DONE` | OK |
| TASK-060 `BLOCKED` / sospeso, non `DONE` | OK |
| TASK-055 `PARTIAL` | OK |
| Nessun altro task in `EXECUTION` attivo al momento dell’init | OK (pre-init: nessun task attivo; post-init: solo TASK-064 in `PLANNING`) |
| TASK-063 non dichiarato risolto da questo file | OK |
| Nessun codice Android modificato in planning | OK |
| Nessuna migration live / `supabase db push` / modifica RLS/RPC remota in planning | OK |

---

## Contesto

**TASK-063** è stato eseguito in modalità **`ACCEPTABLE`** (OnePlus IN2013 + Medium Phone API 35). Lo smoke live A/B è **fermato in S1** con esito **non conforme** (`FAIL` documentato), non **`PASS`**.

Evidenze e sintomi registrati in TASK-063:

- **Baseline A/B non coerente:** conteggi Room divergenti tra device (es. fornitori/categorie/prezzi/storico) pur con stesso account osservato.
- **Outbox `sync_events` pendente:** device A ~6 notifiche outbox; device B ~108; stato cloud non “idle”.
- **Errore classificato come `PayloadValidation`:** `lastErrorType` sull’outbox locale (derivazione client-side, vedi § Findings).
- **APK hash diversi** tra installazioni A e B → **non provata** la stessa build artifact.
- **Dataset / ambiente:** dati non-prod e rollback **non confermati**; nessuna mutazione distruttiva eseguita in TASK-063.
- **S2–S6** correttamente **bloccati** finché S1 non è verde; S7/S8 non eseguiti o opzionali.

**TASK-063** resta **`BLOCKED`** finché baseline e outbox non sono diagnosticate e mitigate con piano sicuro.

---

## Scopo

Definire un **piano sicuro e sequenziale** (solo documentazione in `PLANNING`) per:

1. Capire **perché** l’outbox locale resta pending con errori classificati come **`PayloadValidation`** (causa HTTP 400/422, JSON-RPC, serializzazione risposta, ecc.).
2. Capire **perché** i due device hanno **baseline Room divergente** (storico locale, pull/bootstrap, timing, build diverse, watermark/outbox).
3. Capire **perché** gli **APK installati hanno hash diversi** e come ottenere **parity di build** prima di ogni smoke.
4. Definire **prerequisiti** per una baseline A/B “pulita” prima di **ripetere TASK-063** (stesso artifact, stesso backend effettivo, dataset/rollback dichiarati).
5. Separare **fix sicuri** (procedura, osservazione read-only, reinstall) da interventi che richiedono **task dedicato** o **backend/migration approvata** (mai in questo planning).

---

## Non obiettivi

- Nessun fix codice Kotlin/XML/Gradle in fase `PLANNING`.
- Nessuna cancellazione dati locale/remoti senza piano rollback e approvazione esplicita.
- Nessuna migration live, nessun `supabase db push`, nessuna modifica RLS/RPC/schema Supabase da questo task in planning.
- Nessuno smoke A/B completo (perimetro TASK-063) dentro TASK-064.
- Nessuna chiusura **TASK-055**; nessuna riapertura o promozione **TASK-060**; nessuna dichiarazione che **TASK-063** sia risolto.

---

## Findings repo-grounded

### File letti (audit codice + docs)

| Tipo | Path |
|------|------|
| Task / governance | `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md`, `docs/MASTER-PLAN.md` |
| Backend runbook | `docs/SUPABASE.md`, `supabase/migrations/README.md` |
| Outbox / modelli / RPC client | `SyncEventModels.kt`, `SupabaseSyncEventRemoteDataSource.kt` |
| Repository sync events | `InventoryRepository.kt` (`syncCatalogQuickWithEvents`, `drainSyncEventsFromRemote`, `retrySyncEventOutbox`, `recordOrEnqueueSyncEvent`, `logSyncEventSummary`) |
| Classificazione errori | `SyncErrorClassifier.kt` |
| Coordinator / tracker / VM | `CatalogAutoSyncCoordinator.kt`, `CatalogSyncStateTracker.kt`, `CatalogSyncViewModel.kt` |
| Room migration outbox | `AppDatabase.kt` (DDL `sync_event_outbox`, indici) |
| VM Database (perimetro locale) | `DatabaseViewModel.kt` — hub CRUD/catalog locale; non è il proprietario della catena `record_sync_event` |
| Test JVM | `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest` |

### Cosa fa oggi l’outbox (comportamento codice)

- Dopo push catalogo/prezzi, `recordOrEnqueueSyncEvent` invia RPC **`record_sync_event`** con payload `SyncEventRecordRpcParams` (domain, event_type, `p_changed_count`, `p_entity_ids`, store_id opzionale, source/device/batch/`p_client_event_id`, `p_metadata` JSON).
- Se `recordSyncEvent` **fallisce**, viene inserita una riga in **`sync_event_outbox`** (`SyncEventOutboxEntry`) con `entityIdsJson` / `metadataJson` serializzati, `attemptCount`, `lastErrorType`.
- All’inizio di `syncCatalogQuickWithEvents` e in altri percorsi, **`retrySyncEventOutbox`** rilegge le entry pending (limite `SYNC_EVENT_OUTBOX_RETRY_LIMIT`), ricostruisce `SyncEventRecordRpcParams` e ritenta; successo → `deleteById`; fallimento → incremento `attemptCount` e aggiornamento `lastErrorType` (max `SYNC_EVENT_OUTBOX_MAX_ATTEMPTS`).
- `checkCapabilities` esegue una `select` su tabella **`sync_events`** con range `0..0`; eccezione → capabilities disabilitate con fallback reason **`sync_events_schema_or_rls_unavailable`** (distinto da errore singolo RPC).

### Come viene creato il payload `record_sync_event`

- Parametri Kotlin ↔ JSON: `@SerialName("p_domain")`, `p_event_type`, `p_changed_count`, `p_entity_ids` (`SyncEventEntityIds` con `supplier_ids`, `category_ids`, `product_ids`, `price_ids`), `p_store_id`, `p_source`, `p_source_device_id`, `p_batch_id`, `p_client_event_id`, `p_metadata`.
- `changed_count` = **`chunk.totalIds`** (somma dimensioni liste nell’chunk).
- Chunking: **`SYNC_EVENT_ENTITY_ID_BUDGET = 250`** ID totali per chunk (`chunkSyncEventIds`).
- `client_event_id` deterministico: prefisso `android-{batchId}-{domain}-{eventType}-{chunkIndex}-{fingerprint}`.
- Metadata in prima emissione: JSON con `task`, `source`, `chunk_index`, `chunk_count`. In **retry** outbox, il codice imposta metadata a **`buildJsonObject { put("task", "045") }`** (sovrascrive la forma ricca del primo tentativo nel params — rilevante solo se il backend valida metadata strettamente).

### Dove può nascere `PayloadValidation` (lato client)

`SyncErrorClassifier` assegna **`PayloadValidation`** quando:

- **`SerializationException`** in catena cause (es. risposta RPC non decodificabile come `SyncEventRemoteRow`).
- **HTTP 400 o 422** con `postgrestCode` **non** in `{PGRST204, PGRST205}` (questi ultimi vanno a `RemoteSchemaUnexpected`).

Quindi **`PayloadValidation`** nel task live **non** prova da sola che il messaggio Postgres contenga la stringa “payload”; può essere **400 generico**, **validazione RPC**, o **risposta inattesa** rispetto al decoder.

### Log / tag esistenti

- **`CatalogCloudSync`**: `InventoryRepository` (`sync_events_summary`, metriche fasi), `CatalogSyncViewModel` companion, `CatalogSyncStateTracker`, `MerchandiseControlApplication` (logger coordinator).
- Coordinator: `cycle=catalog_push`, `cycle=sync_events_drain`, `cycle=catalog_bootstrap` con `errCategory` / `httpStatus` / `postgrestCode` su failure.
- TASK-063 elenca anche: `SyncEventsRealtime`, `SupabaseRealtime`, `HistorySessionSyncV2`, `DB_REMOTE_REFRESH`.

### Test JVM che coprono comportamento correlato

- **`DefaultInventoryRepositoryTest`**: outbox su fallimento emit / retry; mock `checkCapabilities` / `record_sync_event`; summary `manualFullSyncRequired`.
- **`CatalogAutoSyncCoordinatorTest`**: log `manualFullSyncRequired`, `outboxPending`.
- **`CatalogSyncViewModelTest`**: hint outbox pending, `manualFullSyncRequired` in UI state.

### Cosa non è verificabile senza dati live / log reali

- Corpo errore grezzo da PostgREST/Postgres per `record_sync_event` (messaggio, `hint`, `details`).
- Allineamento effettivo tra **funzione RPC deployata** sul progetto remoto e **`LOCAL_SUPABASE_PROJECT`** (`20260424021936_task045_sync_events.sql` — non è in questo repo Android).
- Se **`PayloadValidation`** sia dovuta a risposta 200 con shape diversa, a 400 da check SQL nella funzione, o a limite su `entity_ids` / `changed_count` / `metadata`.
- Parità **BuildConfig** (URL/key) tra APK effettivamente installati senza estrazione o rebuild controllato.

---

## Ipotesi da verificare

| # | Ipotesi | Probabilità | Evidenza richiesta | Rischio se ignorata | Azione sicura (planning) |
|---|---------|-------------|-------------------|---------------------|--------------------------|
| 1 | APK diversi → codice/dipendenze diverse → payload o classifier diversi | Alta | Stesso artifact installato su A/B; `pm path` + hash identici; stesso `versionName`/`versionCode` da `dumpsys` | Falsi positivi in diagnosi | Installare **un solo** APK firmato dalla stessa build CI locale |
| 2 | Schema/RPC remoto non allineato al client (DDL/function diversa da fonte locale) | Media | Confronto read-only: definizione RPC attesa (`docs/SUPABASE.md` + SQL locale) vs errore reale; dump/dashboard solo se autorizzato | Fix client “al buio” | Documentare gap; eventuale **task backend** con approvazione apply |
| 3 | `entity_ids`, `metadata` o `changed_count` violano validazione nella RPC (es. limiti, tipi, vincoli JSON) | Media | Payload redatto di una singola entry outbox + risposta HTTP/logcat | Outbox perpetuo | Estrarre un chunk fallito; confrontare con parametri documentati in `SyncEventRecordRpcParams` |
| 4 | Outbox contiene eventi generati da **build precedenti** o retry con metadata ridotto | Media | Timestamp `createdAtMs` outbox vs data install; confronto `metadataJson` vs retry path | Retry infinito su payload storico | Pianificare **solo dopo backup** eventuale purge selettiva (task separato, non in planning) |
| 5 | Room divergente per **storico import/sync** locale, non per bug attuale | Alta | Conteggi prodotti uguali ma supplier/category/prices diversi come in TASK-063 | Falso “bug sync” | Dichiarare dataset test; opzionale reset controllato solo su ambiente non prod |
| 6 | URL/key o progetto Supabase **non identici** tra installazioni | Media | `BuildConfig` da build unica; confronto config effettiva (senza committare segreti) | Debug incrociato impossibile | Una sola pipeline di installazione da `local.properties` nota |
| 7 | Realtime non è la causa primaria: il blocco è su **record/outbox** prima del wake-up | Alta | Log: fallimento su `rpc` prima di eventi Realtime | Perdita tempo su subscription | Priorità logcat su `CatalogCloudSync` e errori RPC |

Ipotesi minime richieste dal brief: tutte coperte nella tabella (righe 1–7).

---

## Piano execution futuro

Step **piccoli e sicuri** (da eseguire solo dopo `PLANNING` → `EXECUTION` approvato):

1. **Installare lo stesso APK** su A e B (stesso file o stessa pipeline Gradle → `adb install -r`).
2. **Confermare** stesso backend: URL host redatto, stesso account; non assumere parity senza build unica.
3. **Logcat pulito:** `adb logcat -c`, poi riprodurre un solo ciclo sync; catturare `CatalogCloudSync` e stacktrace/RestException.
4. **Ispezionare outbox in sola lettura:** query SQLite su `sync_event_outbox` (conteggio, `lastErrorType`, campioni `domain`/`eventType`); stesso per watermarks se utile.
5. **Estrarre un esempio redatto** di `entityIdsJson` / params falliti (troncare ID, niente PII).
6. **Confrontare** con `docs/SUPABASE.md` e con migration SQL **`record_sync_event`** nel progetto locale Supabase (fonte `LOCAL_SUPABASE_PROJECT`, non prova live).
7. **Attribuire** l’errore: client (encoding, chunk, decoder), rete, o RPC/schema (400/422, vincoli).
8. **Proporre fix** in ordine: (a) correzione codice Android se contratto errato; (b) procedura outbox sicura con rollback; (c) migration/backend se RPC non deployata o diversa; (d) procedura manuale operatore.
9. **Solo dopo** baseline pulita e S1 verde su carta, **ripetere TASK-063** (matrice S1–S6).

---

## Comandi diagnostici suggeriti

> **Solo documentazione.** Non eseguire in questa fase `PLANNING` senza transizione a `EXECUTION`.

- `adb devices` / `adb devices -l` — serial A e B.
- `adb -s <SERIAL> shell pm path com.example.merchandisecontrolsplitview` (o `applicationId` effettivo) — path APK.
- Hash APK: `adb -s <SERIAL> pull <path.apk> /tmp/` poi `shasum -a 256` (o equivalente) sul file estratto.
- Logcat: `adb -s <SERIAL> logcat -s CatalogCloudSync SyncEventsRealtime SupabaseRealtime DB_REMOTE_REFRESH`
- DB locale read-only: `run-as` + copia `*.db` in area temporanea (solo device debuggable); oppure export documentato in TASK-063.
- SQLite: `SELECT COUNT(*) FROM sync_event_outbox;` `SELECT domain, eventType, lastErrorType, attemptCount FROM sync_event_outbox LIMIT 5;`
- **Privacy:** redigere email, token, ID completi; non committare dump.

---

## Rischi

- **Dati reali** nel database locale (inventario, PII in screenshot).
- **Cancellazione outbox** → perdita di eventi mai registrati in remoto se poi si elimina senza capire l’impatto.
- **Reset locale** → falsifica test A/B se fatto senza protocollo.
- **Modifica backend prod** per errore durante diagnostica.
- **Fix rapido** lato client che maschera drift RPC/schema senza allineamento documentato.

---

## Criteri di accettazione planning

| # | Criterio | Stato |
|---|----------|-------|
| 1 | File task TASK-064 creato e strutturato | OK (2026-04-26 init) |
| 2 | `MASTER-PLAN.md` allineato (task attivo `PLANNING`, TASK-063 `BLOCKED`) | OK (2026-04-26 init) |
| 3 | Nessun file Kotlin/XML/Gradle modificato in questa init | OK |
| 4 | Nessuna migration live né `supabase db push` | OK |
| 5 | Piano diagnostico sicuro, ipotesi tabellate, step futuri chiari | OK |

---

## Execution

_(Vuoto.)_

---

## Review

_(Vuoto.)_

---

## Fix

_(Vuoto.)_

---

## Chiusura

_(Vuoto.)_

---

## Handoff

- **Executor (fase futura):** seguire § Piano execution futuro e § Comandi; documentare ogni evidenza in TASK-064 `Execution`; nessun `supabase db push`; nessuna cancellazione dati senza rollback approvato.
- **Reviewer:** verificare che le conclusioni distinguano causa client vs backend; che TASK-063 non sia chiuso prematuramente; che TASK-060/055 non cambino stato automaticamente.
- **Sblocco TASK-063:** quando S1 può essere ripetuto con **stessa build**, **backend confermato**, **outbox drenata o spiegata**, **dataset non-prod + rollback** espliciti, e baseline A/B coerente per i conteggi definiti nel preflight.
- **Task separati possibili:** fix Kotlin per contratto RPC; task migration Supabase approvato; task “reset outbox controllato”; task UX se emergono problemi solo dopo S1 verde.
