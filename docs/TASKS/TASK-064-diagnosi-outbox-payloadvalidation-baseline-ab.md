# TASK-064 — Diagnosi outbox PayloadValidation e riallineamento baseline A/B

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-064 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Supabase sync / outbox / QA baseline / multi-device |
| Creato | 2026-04-26 |
| Ultimo aggiornamento | 2026-04-26 — post TASK-065: baseline A/B verde, outbox A/B `0`, B1-B9 chiusi; nessuna migration live / modifica Supabase remoto |

### Governance check

| Verifica | Esito |
|----------|-------|
| `MASTER-PLAN`: **TASK-064** chiuso `DONE` dopo TASK-065 | OK (verifica 2026-04-26 vs `docs/MASTER-PLAN.md`) |
| **TASK-063** riaperto solo dopo gate baseline, finale `BLOCKED`, non `DONE` | OK |
| **TASK-062** `DONE` | OK |
| **TASK-061** `DONE` | OK |
| **TASK-060** `DONE` | OK |
| **TASK-055** `PARTIAL` | OK |
| **TASK-063** non dichiarato risolto da questo file | OK |
| Nessun codice Android modificato in planning/refinement | OK |
| Nessuna migration live / `supabase db push` / modifica RLS-RPC-schema remota | OK |

---

## Contesto

**TASK-063** (`ACCEPTABLE`, OnePlus IN2013 + Medium Phone API 35): dopo TASK-065, **S1 PASS** e **S2 PASS** senza nuova outbox `PayloadValidation`; **S3-S6** non sono stati eseguiti e TASK-063 resta **`BLOCKED`**.

---

## Scopo

Piano **sicuro**, **ordinato** e **execution-ready** (solo documentazione finché resta `PLANNING`) per: causa `PayloadValidation`, parity APK/backend, outbox read-only, baseline A/B, separazione fix client vs backend vs dati storici — senza smoke A/B completo dentro TASK-064.

---

## Non obiettivi

- Fix codice Kotlin/XML/Gradle in `PLANNING`; nessuna **execution** finché non c’è gate § Review.
- Nessuna cancellazione outbox/dati; nessun reset app senza **Livello 1** esplicito.
- Nessuna migration live, `supabase db push`, modifica RLS/RPC/schema remota.
- Nessuno smoke TASK-063 completo qui; nessuna chiusura **TASK-055** / riapertura **TASK-060**; **TASK-063** non si sblocca solo con questo documento.

---

## Livelli di intervento consentiti

| Livello | Nome | Policy |
|---------|------|--------|
| **0** | Read-only — **consentito** in `EXECUTION` TASK-064 senza ulteriori gate | Solo osservazione e raccolta evidenza. |
| **1** | Richiede **conferma esplicita utente** prima dell’azione | Annotare nel log `Execution` chi ha autorizzato e quando. |
| **2** | **Vietato** in TASK-064 | Non eseguire; aprire altro task / approval stream. |

### Livello 0 — Read-only (consentito in execution TASK-064)

- Leggere **logcat** (tag noti: `CatalogCloudSync`, `SyncEventsRealtime`, `SupabaseRealtime`, `DB_REMOTE_REFRESH`, ecc.).
- Leggere **conteggi** DB locale (prodotti, fornitori, categorie, prezzi, history, outbox, watermarks).
- **Copiare** DB locale in area temporanea (es. `/tmp`) per **query read-only** — senza modificare il file sorgente sul device.
- **Estrarre APK** installato e calcolarne **hash** (`pm path` + `pull` + checksum).
- Leggere stato **outbox** (`sync_event_outbox`: count, `lastErrorType`, `domain`, `eventType`, campioni).
- Leggere **payload redatti** (troncare ID, niente segreti).
- Confrontare con **`docs/SUPABASE.md`** e con **migration / SQL** nel progetto locale Supabase (`LOCAL_SUPABASE_PROJECT` — non prova LIVE).

### Livello 1 — Richiede conferma esplicita utente

- **Reinstallare** lo stesso APK su entrambi i device (parity build).
- **Logout / login** Supabase.
- **Forzare full sync** manuale (se esposto in app e coerente con rischio dati).
- **Resettare dati locali** dell’app (impostazioni Android).
- **Pulire outbox locale** (DELETE su `sync_event_outbox` o clear app data — mai senza backup/rollback concordato).
- Usare **dataset non-prod** preparato (import controllato, ambiente dedicato).
- **Query diagnostiche su Supabase live** (SQL Editor, API) — solo se progetto/ruoli autorizzati e senza mutazioni distruttive non approvate.

### Livello 2 — Vietato in TASK-064

- Cancellare o alterare **dati remoti** in modo distruttivo.
- Applicare **migration live**; modificare **RLS / RPC / schema** remoto; eseguire **`supabase db push`**.
- **Correggere codice Android** senza passaggio formale a `EXECUTION` + task/fix dedicato.
- Dichiarare **TASK-063 sbloccato** senza nuova esecuzione smoke ed evidenze che rispettino § Baseline verde minima.

---

## Findings repo-grounded

### File letti (audit codice + docs)

| Tipo | Path |
|------|------|
| Task / governance | `TASK-063`, `MASTER-PLAN` |
| Backend runbook | `docs/SUPABASE.md`, `supabase/migrations/README.md` |
| RPC / modelli / outbox Room | `SyncEventModels.kt`, `SupabaseSyncEventRemoteDataSource.kt`, `AppDatabase.kt` |
| Repository | `InventoryRepository.kt` — `syncCatalogQuickWithEvents`, `drainSyncEventsFromRemote`, `retrySyncEventOutbox`, `recordOrEnqueueSyncEvent` |
| Errori | `SyncErrorClassifier.kt` |
| Runtime sync | `CatalogAutoSyncCoordinator.kt`, `CatalogSyncStateTracker.kt`, `CatalogSyncViewModel.kt` |
| VM Database | `DatabaseViewModel.kt` — CRUD locale; non owner di `record_sync_event` |
| Test JVM | `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest` |

### Comportamento outbox e classificazione (sintesi)

- Fallimento **`record_sync_event`** → riga **`sync_event_outbox`** con `lastErrorType` da `SyncErrorClassifier`.
- **`PayloadValidation`**: `SerializationException` in catena, oppure **HTTP 400/422** con `postgrestCode` **non** `PGRST204`/`PGRST205` → **non** implica testualmente “messaggio Postgres payload”; può essere risposta **non decodificabile** come `SyncEventRemoteRow` o validazione generica.
- **`sync_events_schema_or_rls_unavailable`**: da **`checkCapabilities`** (select `sync_events` range vuoto), **distinto** da errore RPC singolo.

### Dettaglio payload (per confronto con RPC)

- `SyncEventRecordRpcParams`: `p_domain`, `p_event_type`, `p_changed_count` (= somma ID nel chunk), `p_entity_ids` (liste `supplier_ids` / `category_ids` / `product_ids` / `price_ids`), `p_store_id`, `p_source`, `p_source_device_id`, `p_batch_id`, `p_client_event_id`, `p_metadata`.
- Chunk max **250** ID; `client_event_id` deterministico `android-{batchId}-…`.
- **Retry outbox** ricostruisce `metadata` come **`{"task":"045"}`** soltanto — se il backend valida `metadata` in modo stretto, primo tentativo vs retry possono divergere.

### Limiti senza live

- Corpo errore PostgREST/Postgres, shape risposta reale, drift RPC deployata vs SQL in `LOCAL_SUPABASE_PROJECT`.

---

## Decision tree PayloadValidation

Seguire **in ordine**. Se un passo fallisce la parity, **fermarsi** e correggere prima di attribuire “bug payload”.

| Step | Azione | Se sì / allora |
|------|--------|----------------|
| **1** | **APK parity** — stesso file/build, hash identici su A e B | Se **hash diversi** → **non** diagnosticare payload: prima **Livello 1** reinstall stesso APK. |
| **2** | **Backend/config parity** — stesso progetto Supabase (host), stesso account per S1–S6 | Se **URL/progetto/account** incoerenti → correggere **setup**, non RPC. |
| **3** | **Outbox read-only** — età entry vs data install/build; pattern `metadataJson` | Se eventi **pre-build** o retry-only metadata → segnare **outbox storica** possibile. |
| **4** | **Errore reale da logcat** (dopo `logcat -c`, un ciclo sync) | **HTTP 400/422** → probabile validazione RPC/payload PostgREST. **SerializationException** → possibile **response shape** ≠ `SyncEventRemoteRow`. Messaggi capability/select fallita → **schema/RLS** (`sync_events_schema_or_rls_unavailable` / select), non “payload puro” del singolo emit. |
| **5** | Confrontare campi **`entity_ids`**, **`changed_count`**, **`metadata`**, **`client_event_id`** (redatti) con intento codice | Cercare mismatch count vs liste, chunk fuori budget, metadata atteso da RPC. |
| **6** | Confrontare con **`record_sync_event`** nella fonte SQL locale Supabase (non LIVE) | Allineare vincoli documentati vs payload effettivo. |
| **7** | **Classificare** causa probabile | Una tra: **client payload** · **backend RPC/schema** · **outbox storica** · **config/build mismatch** · **unknown** (documentare incertezza). |

---

## Ipotesi da verificare

| # | Ipotesi | P | Evidenza | Rischio | Azione (planning) |
|---|---------|---|----------|---------|-------------------|
| 1 | APK diversi → comportamento diverso | Alta | Hash + `versionName`/`versionCode` | Falsa diagnosi | Livello 1 reinstall |
| 2 | RPC remota ≠ fonte locale | Media | Errore HTTP + SQL locale | Fix client al buio | Task backend / dump |
| 3 | `entity_ids` / `metadata` / `changed_count` vs validazione RPC | Media | Payload redatto + log | Outbox perpetuo | Step 5–6 albero |
| 4 | Outbox da build vecchie o retry metadata ridotto | Media | Timestamp vs install | Retry inutile | Cleanup solo task + backup |
| 5 | Room divergente per storico import, non bug sync | Alta | Conteggi come TASK-063 | Rumore | Dataset dichiarato |
| 6 | URL/key diversi tra installazioni | Media | Build unica nota | Incrocio impossibile | Una pipeline install |
| 7 | Realtime secondario vs record/outbox | Alta | Log su `rpc` | Perdita tempo | Priorità `CatalogCloudSync` |

---

## Baseline verde minima per ripetere TASK-063

Prima di dichiarare S1 ripetibile in sicurezza, **tutte** le voci rilevanti devono essere **sì** o **documentate** (nessun “assume”).

| # | Criterio |
|---|----------|
| B1 | **Stesso hash APK** su A e B (stesso artifact). |
| B2 | Stesso **`versionName`** / **`versionCode`** (verifica `dumpsys package` o UI build). |
| B3 | Stesso **Supabase URL/progetto** effettivo (non solo “stesso account”). |
| B4 | Stesso **account** per S1–S6 (come TASK-063). |
| B5 | **Dataset non-prod** dichiarato **oppure** **rollback** definito e accettato. |
| B6 | **`sync_event_outbox`**: **vuota**, **oppure** pending **spiegati** e **non bloccanti** per S1, **oppure** **pulizia approvata** (Livello 1) e tracciata. |
| B7 | **Options** / indicatori cloud: niente stato **ambiguo** “sempre in sync” vs outbox reale (allineare percezione con conteggio outbox se necessario — fix UX è follow-up, vedi § UX). |
| B8 | **Conteggi baseline** concordati tra A e B (minimo: prodotti, fornitori, categorie, prezzi; opzionale history/sessioni se nello scope preflight). |
| B9 | **Logcat**: durante un **ciclo sync baseline** (push+drain o percorso definito in preflight), **nessun nuovo** errore classificabile come blocco `PayloadValidation` **non spiegato**. |

---

## Piano execution futuro (checklist)

> Eseguibile solo dopo § **Review gate** → stato `EXECUTION`.

- [ ] **L0:** Hash APK + version su A/B; logcat pulito; outbox/watermark read-only; payload redatti.
- [ ] Seguire § **Decision tree** (non saltare step 1–2).
- [ ] Registrare classificazione causa (client / backend / outbox storica / mismatch / unknown).
- [ ] Verificare **Baseline verde minima** prima di proporre rerun **TASK-063**.
- [ ] Evidenze solo secondo § **Evidence / privacy**.

---

## Review gate per passare a EXECUTION

Il **reviewer** e/o **utente** conferma per iscritto (tabella o bullet in futura sezione `Execution`):

| Domanda | Note |
|---------|------|
| Si può **reinstallare lo stesso APK** su A e B? | Se no, TASK-064 resta limitato a L0 parziale. |
| Si può leggere **DB locale in read-only** (copy `/tmp`)? | Device debuggable / autorizzazione. |
| Si può raccogliere **payload outbox redatto** (no segreti in git)? | Dove salvare (locale non tracciato ok). |
| Si può usare **dataset non-prod**? | Ambiente nominato. |
| Si autorizza **full sync manuale** se serve? | Livello 1. |
| Si autorizza **cleanup outbox**? | **Default: NO.** Solo se esplicito + backup. |

Senza queste risposte, **non** promuovere `EXECUTION`.

---

## Comandi diagnostici suggeriti

> Solo documentazione in `PLANNING`. In `EXECUTION`, rispettare **Livello 0** salvo conferma **Livello 1**.

| Azione | Comando / nota |
|--------|----------------|
| Device | `adb devices` / `adb devices -l` |
| Path APK | `adb -s <SERIAL> shell pm path <applicationId>` |
| Hash | `adb pull` APK → checksum locale |
| Logcat | `adb -s <SERIAL> logcat -c` poi `logcat -s CatalogCloudSync SyncEventsRealtime SupabaseRealtime DB_REMOTE_REFRESH` |
| SQLite read-only | Copia DB in `/tmp`; `sqlite3` su copia |
| Outbox | `SELECT COUNT(*) FROM sync_event_outbox;` campioni `lastErrorType`, `domain`, `eventType` |

---

## Evidence / privacy per TASK-064

- **Non committare** dump DB completi né log con segreti.
- **Non committare** payload con **ID completi** se inventario/PII sensibili — troncare/hash parziale.
- Redigere: email, UID, JWT, chiavi, URL con query sensibili.
- Log e payload **possono** restare in **cartella locale non tracciata** (es. `.gitignore`, export fuori repo).
- Nel file task **Execution**: solo **estratti redatti**, path allegato generico, hash file se utile — mai segreti in chiaro.

---

## Rischi

- Dati reali in DB/screenshot; perdita eventi se outbox cancellata senza analisi; reset locale che falsifica A/B; tocco accidentale a prod; fix client che nasconde drift RPC.

---

## Test e fix futuri possibili

> **Non eseguire** in `PLANNING`. Assegnare al task/fix successivo in base alla causa probabile § Decision tree step 7.

| Causa probabile | Piano (futuro) |
|-----------------|----------------|
| **Client** | Test JVM su serializzazione **`SyncEventRecordRpcParams`**; retry outbox con **metadata pieno vs ridotto**; **`SyncErrorClassifier`** per 400/422 e `SerializationException`; **`recordOrEnqueueSyncEvent`** / chunk boundaries. |
| **Backend** | **Task separato**: import/review SQL `sync_events` + `record_sync_event`; confronto RPC LIVE vs migration locale; grants/RLS; **nessuna** migration live senza approval e log apply. |
| **Dati / outbox storica** | Task o procedura **“cleanup outbox controllato”**: **backup prima**; criteri per non perdere eventi mai arrivati in remoto; gate utente Livello 1. |

---

## UX diagnostic note

**Nessuna modifica UI in TASK-064.** Se la diagnosi conferma che l’utente vede “da sincronizzare” / “waiting to sync” ma **non** capisce che l’outbox è bloccata (es. `PayloadValidation`), valutare **follow-up UX** (altro task):

- Messaggio più chiaro in **Options** (stato bloccante vs “in corso”).
- Indicatore **“sincronizzazione bloccata”** quando outbox > 0 e errori ripetuti.
- CTA **full sync** / “diagnosi” solo se **coerente** con `manualFullSyncRequired` / stato reale repository — nessun redesign ampio.

---

## Criteri di accettazione planning

| # | Criterio | Stato |
|---|----------|-------|
| 1 | File task strutturato con livelli intervento, decision tree, baseline verde, review gate, privacy, test futuri, UX note | OK (refinement 2026-04-26) |
| 2 | `MASTER-PLAN` coerente (verifica esterna: TASK-064 `PLANNING`, TASK-063 `BLOCKED`) | OK alla verifica refinement |
| 3 | Nessun codice Android toccato da questo documento | OK |
| 4 | Nessuna migration live / `db push` | OK |
| 5 | Piano operativo senza ripetizioni inutili; tono pratico e repo-grounded | OK |

---

## Execution

### Esecuzione — 2026-04-26 — post-fix TASK-065

**Stato finale execution:** `DONE` — la root cause e' stata isolata come mismatch client-side di decoding della risposta RPC `record_sync_event`; TASK-065 ha applicato fix Android minimo. Baseline A/B post-fix verde, outbox A/B `0`, nessun nuovo `PayloadValidation` durante S2 modifica+rollback.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt` — fix response handling in TASK-065.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSourceTest.kt` — test decoder RPC object/array/extra fields.
- `docs/TASKS/TASK-065-fix-record-sync-event-payloadvalidation-response-handling.md` — task follow-up dedicato.
- `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md` — chiusura baseline B1-B9 post-fix.

**Root cause confermata:**
- `decodeSingle()` della libreria PostgREST decodifica `data` come `List<T>` e poi prende il primo elemento.
- Il SQL locale di riferimento `20260424021936_task045_sync_events.sql` ritorna un singolo row composite (`return v_row;`), non `setof`.
- L'evento remoto veniva inserito (B lo riceveva), ma A falliva nel decode della risposta e accodava localmente `PayloadValidation`.

**Evidenza live post-fix:**
| Voce | A OnePlus | B Emulator | Esito |
|------|-----------|------------|-------|
| APK SHA-256 | `bc6250a93249965239922b15591236a81b84382340c9b20d27cdd7ff44b9fd97` | stesso | PASS |
| Versione | `1.0` / `1` | `1.0` / `1` | PASS |
| Owner redatto | `6425...257e` | `6425...257e` | PASS |
| Core counts finali | prodotti 18867, fornitori 70, categorie 43, prezzi 37932 | stessi | PASS |
| Outbox finale | 0 | 0 | PASS |
| Watermark finale | 128 | 128 | PASS |
| Target rollback | barcode `693...7055`, `retailPrice=1114.0`, `itemNumber=DM02` | stessi | PASS |

**Baseline verde B1-B9 finale post-fix:**
| # | Criterio | Stato | Evidenza / nota |
|---|----------|-------|-----------------|
| B1 | Stesso hash APK | PASS | A/B/local artifact SHA-256 `bc6250a93249965239922b15591236a81b84382340c9b20d27cdd7ff44b9fd97`. |
| B2 | Stesso `versionName` / `versionCode` | PASS | `1.0` / `1` su entrambi. |
| B3 | Stesso Supabase URL/progetto | PASS | stesso APK/config; nessun runtime override osservato; host non stampato per privacy. |
| B4 | Stesso account | PASS | owner locale redatto `6425...257e` su A/B. |
| B5 | Dataset non-prod o rollback/reset controllato | PASS con limite | Dataset corrente usato con modifica reversibile; prezzo target ripristinato a `1114.0` e verificato su A/B. |
| B6 | Outbox vuota / non bloccante | PASS | Outbox A/B `0` dopo retry storico, modifica e rollback. |
| B7 | Options non ambiguo o limite documentato | PASS con limite documentato | Nessun nuovo blocco outbox; resta limite UX storico sulla label principale, non bloccante per baseline tecnica. |
| B8 | Conteggi baseline concordati | PASS con limite | Core catalogo pari; `history_entries` resta diverso A=13/B=12 ed e' fuori core catalogo per questa baseline. |
| B9 | Nessun nuovo `PayloadValidation` non spiegato | PASS | S2 modifica+rollback: A/B outbox `0`, log senza nuovo `PayloadValidation`. |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `:app:assembleDebug` verde in TASK-065 |
| Lint | ✅ ESEGUITO | `:app:lintDebug` verde in TASK-065 |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo |
| Coerenza con planning | ✅ ESEGUITO | Diagnosi root cause, fix Android separato, baseline B1-B9 ripetuta |
| Criteri di accettazione | ✅ ESEGUITO | B1-B9 valutati singolarmente e verdi/con limite documentato |

**Baseline regressione TASK-004:**
- Test eseguiti: `SupabaseSyncEventRemoteDataSourceTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`.
- Test aggiunti/aggiornati: `SupabaseSyncEventRemoteDataSourceTest`.
- Limiti residui: TASK-063 S3-S6 non eseguiti; non incide su chiusura TASK-064.

**Incertezze:**
- Nessuna bloccante per TASK-064. Il backend live non e' stato modificato o migrato.

**Handoff notes:**
- TASK-063 puo' ripartire solo per completare S3-S6/S7-S8 se serve; S1/S2 post-fix sono verdi in `ACCEPTABLE`, non `FULL`.
- Evidenze locali in `/tmp/task065-live/`, non tracciate.

### Esecuzione — 2026-04-26 — ciclo finale Livello 1B

**Stato finale execution:** `BLOCKED` — baseline locale A/B riallineata prima dello smoke, ma evento live nuovo in S2 ha riprodotto `PayloadValidation` su outbox A. TASK-063 non puo' chiudere; S3-S6 non sono stati eseguiti.

**File modificati:**
- `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md` — stato finale, baseline B1-B9 aggiornata, evidenze e blocco nuovo.
- `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md` — rerun `ACCEPTABLE` S1-S2 e stop su outbox nuova.
- `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md` — handoff con evidenza S2 parziale.
- `docs/TASKS/TASK-055-audit-sync-supabase-ux-scroll-database-history.md` — handoff: audit resta `PARTIAL`.
- `docs/MASTER-PLAN.md` — governance finale TASK-064/TASK-063/TASK-060/TASK-055.

**Codice Android modificato:** nessuno.

**Evidenze locali non tracciate:**
- Root: `/tmp/task064-final/`.
- DB backup prima reset: `/tmp/task064-final/db-before/A_OnePlus-IN2013/`, `/tmp/task064-final/db-before/B_MediumPhone-API35/`.
- DB post-bootstrap / post-S2 / post-rollback: `/tmp/task064-final/db-after/`.
- Logcat filtrati: `/tmp/task064-final/logcat/`.
- Screenshot/XML: `/tmp/task064-final/screenshots/`.
- Privacy: non committare DB, log completi, screenshot non redatti, email completa, token o payload non redatti.

**Backup prima di reset / cleanup:**
| Voce | A OnePlus | B Emulator |
|------|-----------|------------|
| Copia DB | eseguita in `/tmp/task064-final/db-before/A_OnePlus-IN2013/` | eseguita in `/tmp/task064-final/db-before/B_MediumPhone-API35/` |
| SHA-256 main DB | `98462d25082978b8fd1918c7b2000e59785a581a7b2464c3c861c4991a1bf42b` | `849c9db197fecb58b5b0d611a9f3e039b2d6aa5eff4db721deeb63299f15b309` |
| `products` | 18867 | 18867 |
| `suppliers` | 69 | 70 |
| `categories` | 42 | 43 |
| `product_prices` | 37903 | 37928 |
| `history_entries` | 13 | 12 |
| `sync_event_outbox` | 6 (`PayloadValidation`) | 108 (`PayloadValidation`) |
| `sync_event_watermarks` | 1, watermark 120 | 1, watermark 120 |
| `sync_event_device_state` | 1 | 1 |

**Build parity definitiva:**
| Voce | Esito |
|------|-------|
| Branch / commit | `main` / `6a935a1` |
| Build | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` -> BUILD SUCCESSFUL |
| APK locale | `app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 APK | `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94` |
| Install A/B | `adb install -r -d` eseguito su A e B |
| Hash APK installato A/B | identico a SHA-256 locale |
| Versione | `versionName=1.0`, `versionCode=1`, `targetSdk=36` su entrambi |
| Supabase host | stesso host redatto `jpg...yvm.supabase.co`; nessuna chiave stampata |

**Reset/bootstrap locale controllato:**
1. B: `pm clear` eseguito.
2. A: `pm clear` negato da sistema (`CLEAR_APP_USER_DATA`); fallback eseguito con uninstall/reinstall dello stesso APK.
3. Login Supabase/Google sullo stesso account redatto (`x***@gmail.com` / owner locale `6425...257e`).
4. Bootstrap/pull iniziale completato su entrambi.
5. Quick sync manuale eseguita solo dopo verifica dirty refs `0` su supplier/category/product refs; non sono state fatte mutazioni remote distruttive.

**Baseline post-bootstrap / pre-smoke:**
| Conteggio | A | B | Esito |
|-----------|---|---|-------|
| `products` | 18867 | 18867 | pari |
| `suppliers` | 70 | 70 | pari |
| `categories` | 43 | 43 | pari |
| `product_prices` | 37928 | 37928 | pari |
| `history_entries` | 13 | 12 | diverso ma non core catalogo |
| `sync_event_outbox` | 0 | 0 | pari / verde |
| `sync_event_watermarks` | 1 | 1 | pari |
| watermark | 120 | 120 | pari |
| dirty refs | 0 | 0 | pari |

**TASK-063 S2 e rollback dato test:**
- Prodotto esistente target redatto: barcode `693...7055`.
- A ha modificato prezzo vendita `1114` -> `1115`; B ha ricevuto update realtime sul target filtrato senza salto di scroll/search.
- A ha poi ripristinato `1115` -> `1114`; B ha ricevuto anche il rollback senza salto di scroll/search.
- Stato finale DB target: A e B `retailPrice=1114.0`; `product_prices` contiene le due history manuali `1115.0` e rollback `1114.0`.
- Non sono stati eseguiti S3-S6 per non moltiplicare mutazioni/outbox dopo il nuovo blocco.

**Outbox nuova dopo S2/rollback:**
| Voce | A OnePlus | B Emulator |
|------|-----------|------------|
| Outbox finale | 4 | 0 |
| `lastErrorType` | `PayloadValidation` | N/A |
| Watermark finale | 124 | 124 |
| Log push A | due cicli con `productsPushed=1`, `pricesPushed=1`, `syncEventsSkippedSelf=2`; outbox pending 2 poi 4 |
| Log drain B | due cicli realtime con `syncEventsFetched=2`, `syncEventsProcessed=2`, `targetedProductsFetched=1`, `targetedPricesFetched=1`; outbox 0 |
| Classificazione | bug reale nuovo: evento remoto arriva a B, ma A conserva outbox `PayloadValidation` per catalogo/prezzi | ricezione OK |

**Baseline verde B1-B9 finale:**
| # | Criterio | Stato | Evidenza / nota |
|---|----------|-------|-----------------|
| B1 | Stesso hash APK | PASS | A/B/local artifact SHA-256 `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94`. |
| B2 | Stesso `versionName` / `versionCode` | PASS | `1.0` / `1` su entrambi. |
| B3 | Stesso Supabase URL/progetto | PASS | stesso APK/config; host redatto `jpg...yvm.supabase.co`. |
| B4 | Stesso account | PASS | stesso account redatto e owner locale `6425...257e`. |
| B5 | Dataset non-prod o rollback/reset controllato | PASS con limite | Nessun dataset non-prod confermato; usato dataset corrente come autorizzato, con reset locale e modifica test reversibile. Prezzo target ripristinato da `1115` a `1114` e verificato su A/B. |
| B6 | Outbox vuota / non bloccante | FAIL | Pre-smoke era vuota su A/B; S2+rollback ha creato 4 eventi nuovi `PayloadValidation` su A. Non puliti. |
| B7 | Options non ambiguo | PARTIAL | Summary post-baseline indicava pending catalogo 0; label principale resta ambigua (`Da sincronizzare` / `Waiting to sync`). Dopo S2 il DB mostra outbox reale su A. |
| B8 | Conteggi baseline concordati | PASS con limite | Core catalogo pari prima dello smoke; dopo S2+rollback `product_prices=37930` su A/B, watermark 124 su A/B, target a `1114.0`. `history_entries` resta diverso A=13/B=12. |
| B9 | Nessun nuovo `PayloadValidation` non spiegato | FAIL | Nessun nuovo errore durante solo bootstrap/quick sync; nuovo `PayloadValidation` riprodotto da S2 live su A. |

**Decisione:**
- TASK-064 non puo' andare `DONE`: il blocco storico e' stato neutralizzato dal reset, ma il bug e' riproducibile su evento nuovo.
- TASK-063 non puo' andare `DONE`: S1 PASS, S2 solo parziale per outbox nuova; S3-S6 bloccati.
- Nessun cleanup outbox locale eseguito dopo il bug nuovo.
- Nessuna migration live, nessun `supabase db push`, nessuna modifica DDL/RPC/RLS/publication, nessuna cancellazione/modifica distruttiva remota.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ESEGUITO | `assembleDebug` eseguito con JBR Android Studio; build success. |
| Lint | N/A | Nessun codice/risorsa/build config modificato. |
| Warning nuovi | N/A | Nessun codice modificato. |
| Coerenza con planning | ESEGUITO | Reset/bootstrap locale, backup DB, APK parity, S1-S2 controllati; stop dopo nuovo bug. |
| Criteri di accettazione | ESEGUITO | B1-B9 valutati singolarmente; B6/B9 finali FAIL documentati. |
| `git diff --check` | ESEGUITO | OK. |
| `git status` | ESEGUITO | Solo documentazione modificata: `docs/MASTER-PLAN.md`, `docs/TASKS/TASK-055-audit-sync-supabase-ux-scroll-database-history.md`, `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md`, `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md`, `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md`. |

**Baseline regressione TASK-004:**
- Test eseguiti: N/A; nessun codice Android modificato.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: se si applica fix client, eseguire `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest` ed eventuali `DatabaseViewModelTest`.

**Incertezze:**
- INCERTEZZA: senza corpo errore PostgREST/RPC live, la causa root resta da isolare tra decode/response client e drift RPC/schema live.
- INCERTEZZA: Options label principale resta ambigua anche con summary pending 0; e' UX follow-up, non causa root del blocco.

**Handoff notes:**
- Primo follow-up consigliato: catturare errore live di `record_sync_event`/response shape sul client, per distinguere client decode da RPC/schema drift.
- Non cancellare le 4 outbox nuove su A: sono evidenza primaria del bug riprodotto.
- TASK-063 puo' ripartire solo dopo B6/B9 verdi su evento nuovo.

### Esecuzione — 2026-04-26 (storica pre-reset)

**Stato finale execution:** `REVIEW` — diagnosi Livello 0 + Livello 1 controllato completata; baseline non verde, quindi TASK-063 non e' stato ripetuto.

**File modificati:**
- `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md` — stato, log execution, baseline B1-B9, classificazione e handoff.
- `docs/MASTER-PLAN.md` — governance TASK-064 `EXECUTION` -> `REVIEW`; TASK-063 resta `BLOCKED`, TASK-060 `BLOCKED`, TASK-055 `PARTIAL`.

**Codice Android modificato:** nessuno.

**Autorizzazione utente registrata:**
- Livello 0 autorizzato.
- Livello 1 autorizzato in modo controllato per: reinstall stesso APK su entrambi i device; logout/login Supabase se necessario; full sync manuale se esposto e coerente; lettura DB locale solo tramite copia temporanea read-only; raccolta payload outbox redatti; uso dataset test non-prod o dataset esistente senza mutazioni distruttive; cleanup outbox locale solo se tutte le condizioni di backup, evidenza, storicita/non recuperabilita e necessita sono soddisfatte.
- Esplicitamente non autorizzati: migration live, `supabase db push`, modifica DDL/RPC/RLS/publication Supabase, cancellazione/alterazione dati remoti, SQL distruttivo su Supabase, modifica codice Android Kotlin/XML/Gradle senza fermarsi prima, chiusura automatica TASK-055, riapertura automatica TASK-060, dichiarazione `FULL` con OnePlus + emulator.

**Governance iniziale:**
| Verifica | Esito |
|----------|-------|
| TASK-064 unico task attivo | OK — avviato in `EXECUTION`. |
| TASK-063 | `BLOCKED`. |
| TASK-062 | `DONE`. |
| TASK-061 | `DONE`. |
| TASK-060 | `BLOCKED` / sospeso. |
| TASK-055 | `PARTIAL`. |

**Azioni iniziali eseguite:**
1. Letti `docs/MASTER-PLAN.md`, `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md`, `docs/TASKS/TASK-063-smoke-live-a-b-sync-android-supabase.md`, `docs/SUPABASE.md`, `supabase/migrations/README.md`.
2. Confermata coerenza governance prima della transizione.
3. Aggiornato TASK-064 e `MASTER-PLAN` a `EXECUTION`.

**Device letti:**
| Ruolo | Serial | Modello | API | Note |
|-------|--------|---------|-----|------|
| A | `8ac48ff0` | OnePlus `IN2013` | 33 | device reale |
| B | `emulator-5554` | `sdk_gphone64_arm64` / Medium Phone API 35 | 35 | emulator; modalita TASK-063 massima `ACCEPTABLE`, non `FULL` |

**Comandi / strumenti usati:**
- `git branch --show-current`, `git rev-parse --short HEAD`.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`.
- `adb devices -l`, `adb install -r -d`, `pm path`, `dumpsys package`, `adb pull` APK installati.
- `adb logcat -c`, `adb shell monkey -p ...`, `adb logcat -d -s CatalogCloudSync SyncEventsRealtime SupabaseRealtime DB_REMOTE_REFRESH HistorySessionSyncV2`.
- `adb exec-out run-as ... cat databases/app_database*` verso `/tmp/task064-db`.
- `sqlite3` solo su copie DB in `/tmp/task064-db`.
- `rg` / `sed` su `docs/SUPABASE.md`, `supabase/migrations/README.md` e sorgente locale non-live `/Users/minxiang/Desktop/MerchandiseControlSupabase/.../20260424021936_task045_sync_events.sql`.

**Build / APK parity:**
| Voce | Risultato |
|------|-----------|
| Branch / commit | `main` / `6a935a1` |
| Artifact | `app/build/outputs/apk/debug/app-debug.apk` |
| Artifact SHA-256 | `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94` |
| Artifact size | `117585273` bytes |
| Install Livello 1 | Eseguito su A e B con `adb install -r -d`; dati app preservati. |
| Hash APK installato A | `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94` |
| Hash APK installato B | `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94` |
| Versione A/B | `versionName=1.0`, `versionCode=1`, `targetSdk=36` su entrambi |
| Supabase host | stesso host redatto da config/APK: `jpg...yvm.supabase.co`; nessuna chiave stampata nel task |

**DB read-only su copie temporanee:**
| Conteggio | A OnePlus | B Emulator | Esito |
|-----------|-----------|------------|-------|
| `products` | 18867 | 18867 | pari |
| `suppliers` | 69 | 70 | divergente |
| `categories` | 42 | 43 | divergente |
| `product_prices` | 37903 | 37928 | divergente |
| `history_entries` | 13 | 12 | divergente |
| `sync_event_outbox` | 6 | 108 | divergente |
| `sync_event_watermarks` | 1 | 1 | pari |
| `sync_event_device_state` | 1 | 1 | pari |
| `pending_catalog_tombstones` | 0 | 0 | pari |

**Watermark / account redatti:**
| Voce | A | B | Esito |
|------|---|---|-------|
| owner redatto | `6425...257e` | `6425...257e` | stesso account/owner osservato localmente |
| `lastSyncEventId` | 120 | 120 | pari |
| device state redatto | `cb9f29...5cfa` | `323679...0106` | device distinti |

**Outbox summary post-reinstall:**
| Voce | A OnePlus | B Emulator |
|------|-----------|------------|
| Pending prima cleanup | 6 | 108 |
| Cleanup outbox locale | non eseguito | non eseguito |
| Pending dopo ciclo foreground | 6 | 108 |
| `lastErrorType` | tutte `PayloadValidation` | tutte `PayloadValidation` |
| Domain/eventType | 3 `catalog/catalog_changed`, 3 `prices/prices_changed` | 105 `catalog/catalog_changed`, 3 `prices/prices_changed` |
| `attemptCount` | dopo foreground tutte a 5; due entry sono state ritentate dall'app al foreground | range 0-5 invariato |
| Created range UTC | 2026-04-24 17:25:42 -> 2026-04-24 17:39:30 | 2026-04-24 17:21:40 -> 2026-04-26 02:21:04 |
| Rapporto con APK corrente | eventi creati prima della reinstall/build corrente | eventi creati prima della reinstall/build corrente |
| `changedCount` | max 1; totale 6 | max 250; totale 19161 |
| max `entityIdsJson` | 107 bytes | 9818 bytes |
| max `metadataJson` | 76 bytes | 78 bytes |

**Payload redatto:**
| Voce | A | B |
|------|---|---|
| `entityIdsJson` | non vuoto; chiavi `category_ids`, `price_ids`, `product_ids`, `supplier_ids`; max array osservato: product 1 / price 1 | non vuoto; stesse chiavi; max array osservato: supplier 68, category 42, product 250, price 24 |
| `metadataJson` | forma ricca, es. chiavi `task`, `source`, `chunk_index`, `chunk_count`; non solo `{"task":"045"}` | forma ricca, stesse chiavi; chunk catalogo fino a `chunk_count=76` |
| Coerenza con SQL locale non-live | entro i limiti locali letti: `changed_count <= 1000`, array per chiave <= 250, `entity_ids` oggetto, metadata oggetto < 4096 bytes e senza chiavi vietate osservate | entro i limiti locali letti; live remoto non verificato |

**Logcat summary:**
- Log filtrati in `/tmp/task064-logcat/A_filtered.log` e `/tmp/task064-logcat/B_filtered.log`.
- Entrambi i device: `sync_events` capability disponibile, Realtime subscribed, drain foreground `outcome=ok`, watermark 120 -> 120, `eventsFetched=0`, `eventsProcessed=0`.
- A: `syncEventOutboxPending=6`; B: `syncEventOutboxPending=108`.
- Ricerca mirata su log completi per `PayloadValidation`, `SerializationException`, `record_sync_event`, `sync_events_schema`, HTTP 400/422, `httpStatus=` e `postgrestCode=`: nessun match utile.
- Limite: non e' emerso il corpo errore PostgREST/Postgres; la causa originaria delle righe gia' in outbox resta non osservata in questo ciclo.

**Decision tree / classificazione causa probabile:**
| Step | Esito |
|------|-------|
| APK parity | Risolto: stesso artifact e stesso SHA-256 su A/B. |
| Backend/config parity | Pass redatto: stesso APK/config host e stesso owner locale osservato. Chiavi non esposte. |
| Outbox read-only | FAIL baseline: pending storici/pre-current build su entrambi; B molto piu' sporco (108 vs 6). |
| Errore reale da logcat | UNKNOWN: nessun HTTP 400/422, `SerializationException` o schema/RLS unavailable nei log raccolti. |
| Payload fields | Coerenti con il contratto locale non-live; entity IDs non vuoti, metadata ricco, chunk entro budget locale. |
| Confronto RPC locale | Il SQL locale `record_sync_event` accetta domini/eventi osservati e limiti rispettati; live remoto non provato. |
| Causa probabile | **outbox storica + precedente build/config mismatch risolto** come causa del blocco baseline attuale; causa root di `PayloadValidation` **unknown**, con **backend RPC/schema live drift** ancora plausibile. Client payload meno probabile rispetto al SQL locale, ma non escluso senza errore live. |

**Baseline verde B1-B9:**
| # | Criterio | Stato | Evidenza / nota |
|---|----------|-------|-----------------|
| B1 | Stesso hash APK | PASS | A/B/local artifact SHA-256 `e88364c51dd13e439b6732df50849777fd38e440a52fff755c68e7a133a53b94`. |
| B2 | Stesso `versionName` / `versionCode` | PASS | `1.0` / `1` su entrambi. |
| B3 | Stesso Supabase URL/progetto | PASS | stesso artifact + host redatto `jpg...yvm.supabase.co`. |
| B4 | Stesso account | PASS | owner locale redatto `6425...257e` su watermark/outbox A e B. |
| B5 | Dataset non-prod o rollback | FAIL / UNKNOWN | non e' stato confermato dataset non-prod ne' rollback remoto; nessuna mutazione prodotto/prezzo/tombstone eseguita. |
| B6 | Outbox vuota / spiegata non bloccante / pulita | FAIL | outbox non vuota: A 6, B 108, `PayloadValidation`; spiegata come storica/pre-build ma ancora bloccante per baseline. Cleanup non eseguito perche' B8 resta fail e la pulizia non basterebbe a sbloccare TASK-063. |
| B7 | Options non ambiguo rispetto allo stato reale | PARTIAL | stato outbox reale e log mostrano pending; serve follow-up UX se la UI non spiega `PayloadValidation`/blocco. Nessun fix UI in TASK-064. |
| B8 | Conteggi baseline concordati | FAIL | fornitori/categorie/prezzi/history/outbox divergono; prodotti pari. |
| B9 | Nessun nuovo `PayloadValidation` non spiegato durante ciclo baseline | PASS con limite | nessun nuovo errore nei log raccolti; A ha ritentato due entry portandole a attempt 5 senza match log utile. Outbox preesistente resta. |

**Azioni Livello 1 eseguite:**
- Reinstall stesso APK su A e B: eseguito.
- Lettura DB locale tramite copie temporanee: eseguita.
- Payload outbox redatti: raccolti da copie.

**Azioni Livello 1 NON eseguite:**
- Logout/login Supabase: non necessario per B4, stesso owner osservato.
- Full sync manuale: non eseguita; dal codice `refreshCatalog()` puo' pushare catalogo/prezzi prima del pull, quindi non e' sicura senza dataset non-prod/rollback.
- Cleanup outbox locale: non eseguito; sarebbe locale e autorizzabile, ma non sufficiente a rendere verde B8 e rischierebbe di nascondere eventi non verificati.

**TASK-063:**
- Non ripetuto. Gate minimo non soddisfatto: B5, B6 e B8 falliscono.
- Stato TASK-063 resta `BLOCKED`; nessun S1-S8 ripetuto in questa execution.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ESEGUITO | `assembleDebug` eseguito con JBR Android Studio; build success. Warning Gradle/Kotlin preesistenti osservati. |
| Lint | N/A | Nessun codice/risorsa/build config modificato; non richiesto per sola diagnostica/documentazione. |
| Warning nuovi | N/A | Nessun codice modificato. |
| Coerenza con planning | ESEGUITO | Livello 0 + Livello 1 controllato; nessuna azione vietata. |
| Criteri di accettazione | ESEGUITO | B1-B9 valutati; TASK-063 non sbloccato. |
| `git diff --check` | ESEGUITO | OK. |
| `git status` | ESEGUITO | Solo documentazione modificata: `docs/MASTER-PLAN.md`, `docs/TASKS/TASK-064-diagnosi-outbox-payloadvalidation-baseline-ab.md`. |

**Baseline regressione TASK-004:**
- Test eseguiti: N/A.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: nessun codice repository/ViewModel/DAO/import-export modificato.

**Rischi / limiti:**
- I DB/log completi restano solo in `/tmp` e non vanno committati.
- Nessuna introspezione Supabase live: drift RPC/schema/RLS resta possibile.
- L'app in foreground ha aggiornato automaticamente `attemptCount` su due outbox A; non e' stato eseguito SQL manuale ne' cleanup.
- Con OnePlus + emulator la modalita massima resta `ACCEPTABLE`, non `FULL`.

**Raccomandazioni prossima fase:**
1. Decidere esplicitamente un dataset non-prod o un rollback remoto accettato.
2. Scegliere una procedura controllata per riallineare B8: staging/non-prod preferibile; full sync manuale solo sapendo che puo' pushare prima del pull.
3. Solo dopo B8 verde, valutare cleanup outbox locale con backup se gli eventi sono confermati storici/non recuperabili.
4. Se `PayloadValidation` ricompare su evento nuovo, raccogliere corpo errore/HTTP e confrontare RPC live vs SQL locale `20260424021936_task045_sync_events.sql`.
5. Ripetere TASK-063 in `ACCEPTABLE` solo quando B1-B9 sono verdi/documentati.

---

## Review

### Review — Livello 0/1 — 2026-04-26 (storica pre-Livello 1B)

**Verdict reviewer:** `APPROVED FOR LEVEL 1B` — solo piano/gate, **non** execution. Al momento di questa review TASK-064 restava `REVIEW`; l'execution successiva e' documentata sopra e termina `BLOCKED`.

**Governance verificata:**
| Verifica | Esito |
|----------|-------|
| TASK-064 | `REVIEW` |
| TASK-063 | `BLOCKED` |
| TASK-060 | `BLOCKED` |
| TASK-055 | `PARTIAL` |
| Codice Android modificato | No |
| DB/outbox modificati in questa review | No |
| Supabase remoto modificato | No |

**Review tecnica:**
| Punto | Valutazione |
|-------|-------------|
| APK mismatch iniziale | Correttamente risolto: stesso artifact debug installato su A/B, stesso hash, stessa versione. |
| Outbox storica | Ipotesi plausibile: eventi creati prima della reinstall/build corrente, pending persistenti, `attemptCount` gia' alto su parte delle entry. |
| Root `PayloadValidation` | Resta `unknown`: mancano log HTTP 400/422, `SerializationException`, schema/RLS unavailable o corpo errore PostgREST. |
| Payload vs SQL locale | Il payload osservato sembra coerente con il SQL locale non-live; questo riduce il rischio di bug client ovvio, ma non elimina drift backend live/RPC/schema. |
| TASK-063 non ripetuto | Corretto: con B5/B6/B8 falliti, ripetere S1-S6 avrebbe prodotto rumore o mutazioni non sicure. |
| Cleanup outbox non eseguito | Corretto: senza B8 verde e senza dataset/rollback, pulire outbox avrebbe mascherato sintomi senza riallineare baseline. |
| Full sync manuale non eseguita | Corretto: dal codice il refresh manuale puo' fare push prima del pull; non e' sicuro senza dataset non-prod o rollback accettato. |

**Rischi integrativi da tenere espliciti:**
- Le copie DB/log in `/tmp` sono evidenza locale volatile, non artifact committabile.
- Il reset dati locali e il login stesso account possono attivare bootstrap/pull/push: vanno eseguiti solo su dataset non-prod/staging o con rollback remoto chiaro.
- Cleanup outbox locale puo' essere accettabile solo dopo backup e solo se non serve conservare eventi mai arrivati al remoto.
- Con OnePlus + emulator l'eventuale smoke successivo resta `ACCEPTABLE`, non `FULL`.

### Piano Livello 1B — riallineamento baseline controllato

**Obiettivo:** rendere B5/B6/B8 verdi senza toccare Supabase remoto in modo distruttivo e senza modificare codice Android.

**Gate utente obbligatori prima di eseguire:**
| Decisione | Opzioni | Default sicuro |
|-----------|---------|----------------|
| Dataset | A: dataset non-prod/staging dedicato; B: dataset esistente con rollback documentato | A consigliata |
| Rollback remoto | disponibile / non disponibile | se non disponibile, non procedere con mutazioni |
| Strategia baseline | reset dati locali A/B + login + bootstrap controllato; oppure full sync manuale; oppure import controllato dataset non-prod | scegliere una sola strategia |
| Cleanup outbox locale | autorizzato / non autorizzato | non autorizzato finche' backup e B8 non sono gestiti |
| TASK-063 successivo | ripetere in `ACCEPTABLE` solo se B1-B9 verdi | non ripetere prima |

**Step proposti:**
1. **Decidere dataset.** Preferire dataset non-prod/staging dedicato. Se si usa dataset esistente, documentare rollback remoto e accettazione del rischio prima di ogni mutazione.
2. **Backup locale A/B.** Prima di reset/cleanup, copiare DB locali completi in area temporanea/non tracciata e registrare hash/path redatti.
3. **Scegliere una strategia baseline unica.** Opzione consigliata: reset dati locali su A e B, installare/verificare lo stesso APK gia' usato, login stesso account, bootstrap/pull controllato su dataset non-prod/staging. Non combinare reset, full sync manuale e import controllato senza motivazione.
4. **Outbox.** Se resta outbox storica e impedisce baseline, cleanup locale solo dopo backup, con count/campione redatto prima e count dopo. Non cancellare dati remoti e non applicare SQL Supabase.
5. **Verifica post-riallineamento.** Rileggere su copie DB: `products`, `suppliers`, `categories`, `product_prices`, `history_entries`, `sync_event_outbox`, `sync_event_watermarks`, `sync_event_device_state`.
6. **Criteri B1-B9.** B1-B4 devono restare PASS; B5 deve essere PASS con dataset/rollback; B6 deve essere vuota/spiegata/non bloccante; B7 non ambiguo; B8 conteggi concordati; B9 logcat senza nuovo `PayloadValidation` non spiegato.
7. **Ripresa TASK-063.** Solo se B1-B9 sono verdi/documentati, ripetere TASK-063 in modalita `ACCEPTABLE` con OnePlus + emulator. Non dichiarare `FULL`.

**Decisione consigliata:**
- Usare dataset non-prod/staging.
- Eseguire reset dati locali A/B + login stesso account + bootstrap/pull controllato.
- Evitare full sync manuale sul dataset esistente finche' non e' chiaro se il percorso spinge dati locali prima del pull.
- Se non esiste dataset non-prod/staging e non c'e' rollback, fermare la fase 1B come `BLOCKED`.

---

## Fix

_(Vuoto.)_

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-26 |
| Tutti i criteri soddisfatti? | Si — B1-B9 verdi o con limite documentato non bloccante |
| Note | Chiusura dopo TASK-065; non equivale a chiusura TASK-063 completo. |

---

## Handoff

- **TASK-065:** `DONE`; fix Android client-side applicato.
- **TASK-063:** resta da completare per S3-S6; non dichiarare `DONE` senza matrice completa.
- **TASK-060:** S2 post-fix copre il comportamento remoto puntuale DatabaseScreen; vedi task dedicato.
- **TASK-055:** resta `PARTIAL` finche' smoke/live follow-up non sono tutti chiusi.
