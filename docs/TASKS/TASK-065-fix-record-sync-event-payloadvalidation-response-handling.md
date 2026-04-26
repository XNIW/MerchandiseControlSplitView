# TASK-065 — Fix record_sync_event PayloadValidation / response handling

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-065 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Supabase sync / RPC response decoding / outbox |
| Creato | 2026-04-26 |
| Ultimo aggiornamento | 2026-04-26 — fix Android applicato e verificato con test JVM, build/lint e S2 live `ACCEPTABLE` |

### Governance check

| Verifica | Esito |
|----------|-------|
| Task creato come follow-up tecnico richiesto da TASK-064/TASK-063 | OK |
| TASK-064 iniziale | `BLOCKED` per nuova outbox `PayloadValidation` su S2 |
| TASK-063 iniziale | `BLOCKED` |
| TASK-060 iniziale | `BLOCKED` / sospeso |
| TASK-055 iniziale | `PARTIAL` |
| Nessuna migration live / `supabase db push` / modifica DDL-RPC-RLS-publication remota | OK |
| Nessun DB dump, token, email completa o screenshot committato | OK |

---

## Contesto

Dopo baseline A/B pulita, A registrava eventi `sync_events` in outbox con `PayloadValidation` anche se B riceveva correttamente gli update. Il fatto che B ricevesse gli eventi indicava che `record_sync_event` inseriva la riga remota, mentre il client A falliva dopo la RPC e quindi accodava localmente eventi gia' creati.

---

## Root cause

`SupabaseSyncEventRemoteDataSource.recordSyncEvent` usava `decodeSingle<SyncEventRemoteRow>()` sulla risposta RPC. In `postgrest-kt 3.5.0`, `decodeSingle()` decodifica `data` come lista e prende il primo elemento.

La migration locale di riferimento `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260424021936_task045_sync_events.sql` definisce invece `record_sync_event` come funzione che ritorna un singolo row composite `public.sync_events` (`return v_row;`), non `setof`.

Effetto: la RPC poteva inserire davvero l'evento, ma la risposta object non era compatibile con il decoder list-only. Il fallimento di deserializzazione veniva classificato come `PayloadValidation` e attivava l'outbox, duplicando sintomi locali pur con propagazione remota riuscita.

---

## Execution

### Esecuzione — 2026-04-26

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt` — sostituito `decodeSingle()` con decoder interno robusto a risposta RPC object, array e campi extra; risposta vuota/shape sconosciuta resta errore esplicito.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSourceTest.kt` — aggiunti test JVM per risposta object, array e array vuoto; incluso campo extra `expires_at`.
- `docs/TASKS/TASK-065-fix-record-sync-event-payloadvalidation-response-handling.md` — creato task follow-up con evidenze.

**Azioni eseguite:**
1. Letti `MASTER-PLAN`, TASK-064, TASK-063, TASK-060, TASK-055, `docs/SUPABASE.md`, `supabase/migrations/README.md`, `AGENTS.md`, `CLAUDE.md`, `docs/CODEX-EXECUTION-PROTOCOL.md` e SQL locale Supabase `20260424021936_task045_sync_events.sql`.
2. Verificato il codice `SupabaseSyncEventRemoteDataSource`, `SyncEventModels`, repository, classifier, coordinator e test sync/outbox.
3. Ispezionata la semantica di `decodeSingle()` nella dipendenza locale `postgrest-kt 3.5.0`: decoder list-first.
4. Applicato fix Android minimo, senza cambiare payload, outbox, DAO, repository o ViewModel.
5. Installato lo stesso APK fixato su OnePlus A e emulator B; gli eventi storici in outbox A sono stati ritentati e drenati a `0`.
6. Ripetuto S2: A `1114 -> 1115`, B riceve sul target filtrato senza scroll/search jump; rollback A `1115 -> 1114`, B riceve; outbox A/B resta `0`.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL |
| Lint | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo; warning Gradle/AGP gia' esistenti |
| Coerenza con planning | ✅ ESEGUITO | Fix limitato al response handling RPC; nessun cambio backend/live |
| Criteri di accettazione | ✅ ESEGUITO | Vedi matrice sotto |

**Baseline regressione TASK-004 (applicabile):**
- Test eseguiti:
  - `:app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.SupabaseSyncEventRemoteDataSourceTest"` -> BUILD SUCCESSFUL.
  - `:app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` con JBR/JAVA_TOOL_OPTIONS -> BUILD SUCCESSFUL.
  - `:app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` con JBR/JAVA_TOOL_OPTIONS -> BUILD SUCCESSFUL.
  - `:app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` con JBR/JAVA_TOOL_OPTIONS -> BUILD SUCCESSFUL.
- Test aggiunti/aggiornati: `SupabaseSyncEventRemoteDataSourceTest`.
- Limiti residui: smoke TASK-063 S3-S6 non eseguiti in questa sessione; TASK-063 resta non chiudibile.

**Evidenza live post-fix:**
| Voce | Esito |
|------|-------|
| APK locale/installato A/B | SHA-256 `bc6250a93249965239922b15591236a81b84382340c9b20d27cdd7ff44b9fd97` |
| Versione A/B | `versionName=1.0`, `versionCode=1` |
| Account | owner redatto `6425...257e` |
| Outbox dopo foreground post-install | A `0`, B `0`; A ha ritentato 4 eventi storici e li ha drenati |
| S2 modifica | A emette catalogo+prezzi; B drena `eventsFetched=2`, `eventsProcessed=2`, `targetedProductsFetched=1`, `targetedPricesFetched=1`, outbox `0` |
| S2 rollback | watermark A/B `128`, outbox A/B `0`, target `693...7055` a `retailPrice=1114.0` su A/B |
| UX B | search `6937962107055` preservata; card visibile aggiornata; nessun salto search/scroll osservato |

**Criteri di accettazione:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | Root cause isolata | ESEGUITO | `decodeSingle()` list-only vs RPC row object |
| 2 | Fix Android minimo se client-side | ESEGUITO | Decoder object/array/extra fields in data source |
| 3 | Nessuna migration live / backend mutation | ESEGUITO | Nessun `supabase db push`, nessuna modifica remota DDL/RPC/RLS/publication |
| 4 | Outbox non cresce su S2 dopo fix | ESEGUITO | S2 modifica+rollback: outbox A/B `0` |
| 5 | Test automatici richiesti | ESEGUITO | build/lint/test mirati verdi |

**Incertezze:**
- Nessuna sulla root cause client-side osservata. Il backend live non e' stato introspezionato via DDL/RPC dump; non necessario per questo fix perche' la prova live post-fix e' positiva.

**Handoff notes:**
- TASK-063 resta `BLOCKED`/incompleto per S3-S6 non eseguiti; non dichiarare `DONE`.
- S6 fallback full sync non e' stato simulato perche' richiederebbe condizioni backend/gap non distruttive non disponibili.
- Le evidenze live sono in `/tmp/task065-live/` e non devono essere committate.

---

## Review

### Self review — 2026-04-26

**Verdetto:** APPROVED.

**Controlli:**
- Il fix non nasconde errori reali HTTP/RPC: risposte vuote, array vuoto e shape non supportata restano errori.
- Il decoder accetta la shape object coerente con SQL locale e array per compatibilita' eventuale.
- `ignoreUnknownKeys` protegge da colonne extra come `expires_at` senza indebolire i campi richiesti.
- Nessun cambiamento a idempotenza, `client_event_id`, outbox retry, repository o UI.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-26 |
| Tutti i criteri soddisfatti? | Si |

## Handoff

- TASK-064 puo' essere chiuso `DONE` per B1-B9 post-fix.
- TASK-060 puo' essere chiuso `DONE` per il criterio remoto puntuale osservato in S2 post-fix.
- TASK-063 non puo' essere chiuso: S1/S2 coperti, S3-S6 non eseguiti.
- TASK-055 resta `PARTIAL`: audit ampio e smoke completo non sono ancora conclusi.
