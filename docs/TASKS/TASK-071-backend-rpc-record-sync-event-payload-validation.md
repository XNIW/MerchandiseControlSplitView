# TASK-071 — Verifica contratto RPC `record_sync_event` / cause `PayloadValidation`

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-071 |
| Stato | `DONE` |
| Priorità | `ALTA` |
| Area | Diagnosi backend/contratto RPC `record_sync_event`, classificazione errore — **nessuna modifica backend/app in questo task** |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-28 — review PASS CON FIX DOCUMENTALI; task chiuso a `DONE` |

---

## Dipendenze

- **TASK-069** `DONE` — audit diagnostico (anchor: righe outbox con `lastErrorType=PayloadValidation`, RPC `record_sync_event` fallita per catalog/prices in O1a). Prima di una futura esecuzione di TASK-071: **riconfermare** che TASK-069 resti `DONE` e che i deliverable citati siano ancora validi.
- **TASK-064 / TASK-065** `DONE` — lineage `PayloadValidation` RPC/decoding storico.
- **TASK-070** (parallelo, app side) — **non bloccante** per TASK-071 e viceversa; TASK-071 non richiede chiusura TASK-070.

---

## Scopo

Determinare la **causa esatta** (o classificarla `da_chiarire`) del rigetto `PayloadValidation` da parte della RPC `record_sync_event`, confrontando **contratto documentato** vs **payload effettivamente prodotto dall’app**, **senza**:

- modifiche al codice Android;
- modifiche a schema/RPC/RLS/trigger/migration/backend **esecutive** (SQL deploy, funzioni, policy applicate);
- deploy live;
- reset/delete/truncate di outbox o `sync_events`.

Perimetro: **diagnosi e documentazione**; fix esecutivi → task separati. Solo **allineamenti documentali/runbook** sotto `/Users/minxiang/Desktop/MerchandiseControlSupabase` (e vedi tabella *Documentazione vs codice backend*).

---

## Contesto

L’audit TASK-069 ha dimostrato backlog `sync_event_outbox` con `lastErrorType=PayloadValidation` e failure RPC durante push ad alto volume; il payload include spesso `entity_ids` compatto (budget) con `changed_count` elevato. Il dettaglio del messaggio di validazione **non** è sempre visibile senza logging privacy-safe lato app (TASK-070) o estratti read-only dai log Postgres/Supabase.

---

## Fonti e livelli di fiducia

| Fonte | Ruolo | Affidabilità / limiti |
|-------|--------|----------------------|
| **Repo Android** (`MerchandiseControlSplitView`) | Payload client effettivo: `SupabaseSyncEventRemoteDataSource.recordSyncEvent` → RPC `record_sync_event` con `SyncEventRecordRpcParams`; `SyncEventEntityIds`; composizione in `InventoryRepository.recordOrEnqueueSyncEvent` | **Alta** per *cosa invia l’app*; non prova il contratto SQL |
| **Cartella Supabase locale tracciata** `/Users/minxiang/Desktop/MerchandiseControlSupabase` | **Fonte primaria locale** per DDL, migrations, RPC, RLS, runbook, README/documentazione backend attesi in version control | **Alta** per *definizione e docs dichiarati*; **non** prova automaticamente che lo schema **live** o i log coincidano |
| **Log Postgres / dashboard Supabase (live)** | Errore reale lato server (messaggi, codici) | **Alta** per sintomo reale; solo **read-only**, **privacy-safe** (estratto minimo, mascheramento) |
| **Divergenza locale vs live** | Possibile drift non rilevato dal solo repo | Classificare come **`schema_drift_da_chiarire`** — **nessun fix inventato** in TASK-071 |

**Divieto esplicito:** nessun deploy live; nessuna modifica **esecutiva** DDL/RPC/RLS/migration dentro TASK-071.

---

## Allineamento documentale Supabase locale

- Se TASK-071 chiarisce il contratto effettivo di `record_sync_event`, **aggiornare o proporre aggiornamento** dei documenti pertinenti sotto **`/Users/minxiang/Desktop/MerchandiseControlSupabase`** (non solo i file nel repo Android), oltre al log nel file task.
- Verificare se esistono e allinearli alla scoperta: `README.md`, `docs/*.md`, `migrations/README.md`, runbook Supabase, file che documentano RPC, RLS, `sync_events`, `record_sync_event`.
- Se la documentazione lì **manca o è incompleta**: **documentare il gap** nell’output (Execution / Handoff).
- Task **solo diagnostico** → ammessi **solo** interventi **documentali/runbook** in quella cartella; **niente** patch SQL/RPC applicate come “fix” dentro TASK-071.

---

## Documentazione vs codice backend

| Consentito in TASK-071 (futura `EXECUTION`) | Non consentito |
|---------------------------------------------|----------------|
| Allineare **documentazione** locale Supabase (e, se serve, riferimenti in Android `docs/SUPABASE.md` — solo se pertinente al contratto scoperto) al **contratto verificato** | Modificare **funzioni SQL**, **migration**, **RPC**, **RLS**, **trigger** |
| Annotare gap, drift, proposte | Applicare fix SQL/RPC come chiusura di TASK-071 |

Fix SQL/RPC → proporre **TASK-072** o task backend dedicato; **nessun deploy live**.

---

## Non incluso

- Modifiche al **codice Kotlin/Android** (inclusi `recordOrEnqueueSyncEvent`, remote data source, modelli).
- Modifiche **live** o **esecutive** a schema/RPC/RLS/trigger/migration/backend.
- **Deploy** su progetto Supabase live.
- **Pulizia** `sync_event_outbox` / `sync_events` (truncate/delete/reset).
- Implementazione di fix backend o app: solo **proposta** documentata fuori da questo task.

---

## File / aree (sola lettura in esecuzione futura)

- `SupabaseSyncEventRemoteDataSource.kt` — invocazione `.rpc("record_sync_event", params)`.
- `SyncEventModels.kt` — `SyncEventRecordRpcParams`, `SyncEventEntityIds`, serializzazione (`@SerialName`, snake_case JSON).
- `InventoryRepository.kt` — `recordOrEnqueueSyncEvent` (costruzione payload / budget ids).
- **`/Users/minxiang/Desktop/MerchandiseControlSupabase`** + `docs/SUPABASE.md` (policy `LOCAL_SUPABASE_PROJECT` vs `LIVE`).

---

## Matrice contratto RPC *(compilata in EXECUTION 2026-04-28)*

Legenda **Esito riga:** `OK` \| `mismatch` \| `da_chiarire` (default finché non si riempie con evidenza). La **classificazione sintetica finale** del task usa le etichette in **Output finale richiesto**.

| Parametro client | Valore/tipo prodotto da Android | Parametro RPC atteso | Tipo SQL atteso | Nullable? | Vincoli/RLS/check | Evidenza fonte | Esito |
|------------------|----------------------------------|----------------------|-----------------|-----------|-------------------|----------------|-------|
| `domain` -> serial `p_domain` | `String`; valori `catalog` / `prices` | `p_domain` | `text` | no lato Android; SQL senza default | RPC allowlist `catalog`, `prices`; colonna `domain text not null` + CHECK | Android `SyncEventModels.kt:17-19`, `:68-72`; SQL locale `20260424021936_task045_sync_events.sql:7,19,69-72,100-103` | OK |
| `eventType` -> `p_event_type` | `String`; `catalog_changed`, `prices_changed`, `catalog_tombstone`, `prices_tombstone` | `p_event_type` | `text` | no lato Android; SQL senza default | RPC allowlist e coerenza incrociata dominio/evento; colonna `event_type text not null` + CHECK | Android `SyncEventModels.kt:22-26`, `:68-72`; SQL locale `20260424021936_task045_sync_events.sql:8,20-27,105-125` | OK |
| `changedCount` -> `p_changed_count` | `Int`; se `entityIds` supera budget 250 Android invia `entityIds` vuoto ma conserva il totale reale (`changedCountOverride`) | `p_changed_count` | `integer` | no lato Android; SQL default `0`, colonna not null | SQL accetta solo `0..1000`; Android/TASK-069 ha evidenza di 6033 prodotti e 75569 prezzi in un delta reale | Android `InventoryRepository.kt:2479-2512`, `:3291-3324`, `:3385-3397`, `:3549`; test `DefaultInventoryRepositoryTest.kt:3650-3654`; SQL locale `20260424021936_task045_sync_events.sql:13,18,72,127-130`; TASK-069 `:547`, `:554`, `:563` | mismatch |
| `entityIds` -> `p_entity_ids` | `SyncEventEntityIds?`; JSON snake_case con `supplier_ids`, `category_ids`, `product_ids`, `price_ids`; in compatto liste vuote | `p_entity_ids` | `jsonb` | si' | Deve essere oggetto; chiavi solo allowlist; valori array; max 250 id per array; UUID v1-v5; payload <= 16 KiB. Nessun vincolo SQL locale lega somma id a `changed_count` | Android `SyncEventModels.kt:29-49`, `:68-79`; `InventoryRepository.kt:3306-3324`, `:3385-3432`; SQL locale `20260424021936_task045_sync_events.sql:14,28-30,73,91,167-208` | OK |
| `storeId` -> `p_store_id` | `String?`; nel quick path corrente `syncEventStoreScope(null)` -> `null` | `p_store_id` | `uuid` | si' | Colonna `store_id uuid null`; indice parziale se non null; nessun vincolo RLS/store nella RPC locale oltre owner | Android `InventoryRepository.kt:2428`, `:3187`, `:3325`; `SyncEventModels.kt:74`; SQL locale `20260424021936_task045_sync_events.sql:6,47-49,74,237` | OK |
| `source` -> `p_source` | `String?`; default model `"android"`, repository passa `"android"` | `p_source` | `text` | si' | Nessun check locale; usato come metadato tecnico | Android `SyncEventModels.kt:75`; `InventoryRepository.kt:3326`; SQL locale `20260424021936_task045_sync_events.sql:9,75,240` | OK |
| `sourceDeviceId` -> `p_source_device_id` | `String?`; UUID v4 generato e salvato localmente per installazione | `p_source_device_id` | `text` | si' | Lunghezza <= 160 | Android `InventoryRepository.kt:3161-3170`, `:3327`; `SyncEventModels.kt:76`; SQL locale `20260424021936_task045_sync_events.sql:10,76,137-140,241` | OK |
| `batchId` -> `p_batch_id` | `String?`; UUID v4 per ciclo quick sync, condiviso catalog/prezzi | `p_batch_id` | `uuid` | si' | Nessun unique; cast UUID SQL atteso | Android `InventoryRepository.kt:2473`, `:2497`, `:2509`, `:3328`; `SyncEventModels.kt:77`; SQL locale `20260424021936_task045_sync_events.sql:11,77,242` | OK |
| `clientEventId` -> `p_client_event_id` | `String?`; `android-{batchId}-{domain}-{eventType}-{chunkIndex}-{fingerprint}` | `p_client_event_id` | `text` | si' | Lunghezza <= 160; unique parziale `(owner_user_id, client_event_id)`; duplicate restituisce riga esistente | Android `InventoryRepository.kt:3308`, `:3329`, `:3400-3413`; `SyncEventModels.kt:78`; SQL locale `20260424021936_task045_sync_events.sql:12,51-53,78,132-135,210-219,252-265` | OK |
| `metadata` -> `p_metadata` | `JsonObject`; repository invia `task`, `source`, `chunk_index`, `chunk_count`, `entity_ids_compacted`, e `original_changed_count` se compatto | `p_metadata` | `jsonb` | no dopo coalesce/default | Deve essere oggetto; <= 4096 byte; denylist PII/business (`barcode`, `email`, `excel`, `path`, `price`, `product_name`, `supplier_name`, `category_name`, `token`) | Android `SyncEventModels.kt:79`; `InventoryRepository.kt:3310-3319`; SQL locale `20260424021936_task045_sync_events.sql:17,31-33,79,88,142-165,246` | OK |

---

## Ipotesi `PayloadValidation` *(da falsificare con evidenza)*

| Ipotesi | Evidenza a favore | Evidenza contro | Come verificarla | Decisione attesa |
|---------|-------------------|-----------------|------------------|------------------|
| `entity_ids` compatto vuoto + `changed_count > 0` non accettato dalla RPC | Pattern app con budget ids; sospetto vincolo count vs liste | Potrebbero esserci altri errori prima della validazione | Confronto DDL/RPC locale + (se serve) payload sintetico | Confermare/refutare → mismatch o no-op |
| JSON shape **camelCase** vs **snake_case** atteso da SQL | Meno probabile: Kotlin usa `@SerialName` snake per `entity_ids` | decoding lato server potrebbe aspettare altro nesting | Log errore server + confronto con `SyncEventEntityIds` | OK / mismatch |
| `store_id` nullo o formato non accettato | `String?` lato app; RLS spesso lega store | alcuni flussi potrebbero richiedere store obbligatorio | Matrice + policy RLS repo locale + log | da_chiarire → mismatch RLS vs app |
| `metadata` JSON shape non conforme | Default `{}`; RPC potrebbe richiedere chiavi obbligate | se default già valido, ipotesi debole | DDL `record_sync_event` + prova mirata | OK / mismatch |
| `client_event_id` duplicato o formato non valido | Idempotenza cross-device; vincolo UNIQUE | errore diverso da “validation” in altri stack | Query read-only / log messaggio esatto | classificare |
| Vincolo RLS / owner | Sintomo `PayloadValidation` può mascherare rifiuti auth | potrebbe essere puramente CHECK interno alla funzione | Policy + `SET search_path` / `auth.uid()` in RPC | backend vs classificazione |
| **Schema drift** repo locale vs live | Deploy manuali non committati | repo locale allineato | Diff consapevole; **non** assumere | **`schema_drift_da_chiarire`** |

---

## Ordine consigliato (futura `EXECUTION`)

1. **Governance:** promuovere esplicitamente TASK-071 a `EXECUTION` (approvazione utente); non iniziare diagnosi “informale” senza stato task coerente.
2. **Allineamento backlog:** confermare **TASK-069** `DONE`; trattare **TASK-070** come separato e **non bloccante**.
3. Leggere **`docs/SUPABASE.md`** e policy **LOCAL vs LIVE**.
4. **Iniziare dal repo Supabase locale tracciato:** `/Users/minxiang/Desktop/MerchandiseControlSupabase` (README, docs, migrations, RPC/RLS pertinenti) — estrarre definizione **`record_sync_event`** e documentazione collegata.
5. In **Android**, leggere `SyncEventRecordRpcParams`, `SyncEventEntityIds`, `SupabaseSyncEventRemoteDataSource`, `recordOrEnqueueSyncEvent`.
6. **Compilare la matrice contratto** (almeno una fonte per parametro).
7. **Solo dopo**, se necessario, riprodurre con payload **sintetico o mascherato** (mai dati reali identificabili).
8. **Classificare** con le etichette sotto; registrare **drift** tra `docs/SUPABASE.md`, cartella Supabase locale, live/log.
9. **Allineare documentazione** nella cartella Supabase locale (e gap documentati); **proporre** fix esecutivi in task separati; **nessun deploy live**.

---

## Output finale richiesto

Al termine (fuori `BACKLOG` / al completamento futuro), TASK-071 deve produrre:

- **Contratto RPC** documentato (parametri, tipi, nullability, vincoli noti) con evidenze.
- **Payload Android** documentato (valori tipici, serializzazione, edge case budget ids).
- **Mismatch** esplicito **oppure** stato **`da_chiarire`** con motivazione e cosa manca come evidenza.
- **Elenco documenti Supabase locali letti** (path sotto `/Users/minxiang/Desktop/MerchandiseControlSupabase`).
- **Documenti Supabase locali aggiornati** in esecuzione **oppure elenco “da aggiornare”** con motivazione; **gap** se mancanti.
- **Drift** tra: Android `docs/SUPABASE.md` \| repo Supabase locale \| stato live/log (privacy-safe) — cosa diverge e da cosa dipende.
- **Classificazione** (una o più, coerenti con l’evidenza): `contract_ok` \| `contract_mismatch` \| `documentation_drift` \| `schema_drift_da_chiarire` \| `backend_fix_needed` \| `app_payload_fix_needed`.
- **Proposta fix separata** (backend e/o app) **se necessaria** — solo documentata; **mai** implementata in TASK-071.
- **Proposta** eventuale **TASK-072** (o altro ID backend) — **solo testo nel task / handoff**, senza creazione automatica del file task.
- **Elenco evidenze e fonti** (path file Android + Supabase locale, estratti log mascherati, commit/hash utili).

---

## Privacy e safety (obbligatorio)

- Payload di riproduzione: **sintetico** o **mascherato** (ID/batch/device inventati o troncati).
- **Mai** token/JWT, segreti, **URL** con chiavi o secrets in chiaro (né URL “completi” esposti).
- **Mai** barcode, nomi prodotto, PII reali negli allegati al task.
- **Owner / store id:** mascherati o placeholder.
- Log Postgres: solo **estratti minimi** necessari (messaggio errore, codice, eventuale param hint sanitizzato).

---

## Criteri di accettazione

*Verificabili al completamento di TASK-071 (dopo promozione ed EXECUTION); non richiedono EXECUTION per approvare questo planning.*

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Matrice contratto **compilata** (tutte le righe parametro minime) | Doc | — |
| 2 | **Almeno una fonte** citata per **ogni** parametro della matrice | Doc | — |
| 3 | Causa `PayloadValidation` **classificata** (root o sotto-causa) **oppure** `da_chiarire` con motivazione documentata | Doc / log | — |
| 4 | Se non classificata: motivazione **`da_chiarire`** + gap evidenza chiaro | Doc | — |
| 5 | **Nessun deploy live** eseguito nel task | Governance | — |
| 6 | **Nessun** cleanup outbox / `sync_events` | Governance | — |
| 7 | **TASK-070** non dipende da TASK-071 per procedere; TASK-071 non impone blocchi a TASK-070 | Governance | — |
| 8 | Proposta fix esecutivo (backend/app) **documentata ma non implementata** in TASK-071 | Doc | — |
| 9 | Percorso Supabase locale **assoluto** documentato e usato come riferimento: `/Users/minxiang/Desktop/MerchandiseControlSupabase` | Doc | — |
| 10 | Documenti Supabase locali **rilevanti letti e citati** nell’output | Doc | — |
| 11 | Documentazione Supabase locale **aggiornata** (se applicabile) **oppure gap documentato** esplicitamente | Doc | — |
| 12 | **Nessuna** modifica SQL/RPC/RLS/migration **applicata** come fix dentro TASK-071 | Governance | — |
| 13 | Eventuale fix backend **proposto come task separato** (e.g. TASK-072), non eseguito qui | Doc | — |

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task in `BACKLOG` come follow-up di TASK-069 | Gate anti-scope-creep TASK-069 | 2026-04-27 |
| 2 | Solo diagnosi; nessun fix esecutivo backend/app nel perimetro | Separazione fix / deploy | 2026-04-27 |
| 3 | TASK-070 e TASK-071 non bloccanti a vicenda | Parallelizzazione sicura | 2026-04-27 |
| 4 | Drift locale/live → `schema_drift_da_chiarire`, no fix speculativi | Evitare conclusioni senza evidenza | 2026-04-27 |
| 5 | TASK-072 solo **proponibile** al termine, non creato da questo planning | Anti-auto-task | 2026-04-27 |
| 6 | Cartella ufficiale tracciata: `/Users/minxiang/Desktop/MerchandiseControlSupabase`; allineamento docs anche lì | Fonte unica locale + tracciabilità | 2026-04-27 |

---

## Planning — note analitiche compatte

Cause probabili da falsificare con la matrice e le ipotesi (non assunte come vere): vincoli su `changed_count` vs liste vuote, shape JSONB, RLS su `store_id`, `metadata`, unicità `client_event_id`, drift schema.

**Rischi:** schema live ≠ repo locale; log error body sensibili → vedi *Privacy e safety*.

---

## Execution / Review / Fix

### Esecuzione — 2026-04-28

**Preflight governance:**
- TASK-069 riconfermato `DONE` da `docs/MASTER-PLAN.md` e `docs/TASKS/TASK-069-audit-sync-residui-outbox-price-generated-history.md`.
- TASK-070 riconfermato separato/non bloccante; al momento dell'avvio risulta `DONE` in `docs/MASTER-PLAN.md` e `docs/TASKS/TASK-070-outbox-retry-head-of-line-logging-strutturato.md`.
- Perimetro riconfermato: solo diagnosi/documentazione; nessun fix app/backend, nessun deploy live, nessuna modifica SQL/RPC/RLS/trigger/migration, nessun cleanup dati.

**File modificati:**
- `docs/TASKS/TASK-071-backend-rpc-record-sync-event-payload-validation.md` — stato, matrice contratto RPC, log execution, classificazione, handoff e passaggio a `REVIEW`.
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/mapping_room_to_supabase.md` — nota diagnostica TASK-071 sul mismatch `changed_count` locale vs payload compatto Android.

**File Supabase locali letti:**
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/MASTER_PLAN.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260424021936_task045_sync_events.sql`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/README.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/mapping_room_to_supabase.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/decisions.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/functions/README.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/TASKS/045_sync_events_incremental_catalog_price_pull_option_b.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/TASKS/046_apply_verify_sync_events_staging_live.md`

**Gap Supabase locali:**
- `README.md` alla root non presente; letti `supabase/migrations/README.md` e `supabase/functions/README.md`.
- La cartella `/Users/minxiang/Desktop/MerchandiseControlSupabase` non e' un repository git alla root (`git status --short` non eseguibile: `fatal: not a git repository`), quindi non esiste un diff git locale affidabile per quella cartella in questa execution.

**File Android letti:**
- `docs/SUPABASE.md`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncErrorClassifier.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md`
- `docs/TASKS/TASK-069-audit-sync-residui-outbox-price-generated-history.md`
- `docs/TASKS/TASK-070-outbox-retry-head-of-line-logging-strutturato.md`

**Ricostruzione payload Android verso RPC:**
- Nome RPC: `record_sync_event`, chiamata da `SupabaseSyncEventRemoteDataSource.recordSyncEvent` con `.rpc("record_sync_event", params).decodeSingle()` (`SupabaseSyncEventRemoteDataSource.kt:41-45`).
- Mapping params: `domain -> p_domain`, `eventType -> p_event_type`, `changedCount -> p_changed_count`, `entityIds -> p_entity_ids`, `storeId -> p_store_id`, `source -> p_source`, `sourceDeviceId -> p_source_device_id`, `batchId -> p_batch_id`, `clientEventId -> p_client_event_id`, `metadata -> p_metadata` (`SyncEventModels.kt:68-79`).
- JSON/snake_case: `SyncEventEntityIds` usa `@SerialName` per `supplier_ids`, `category_ids`, `product_ids`, `price_ids` e `@EncodeDefault(ALWAYS)` sulle liste (`SyncEventModels.kt:29-42`); `SyncEventRecordRpcParams` usa `@SerialName("p_*")`.
- `domain` / `event_type`: catalogo usa `catalog` + `catalog_changed` o `catalog_tombstone`; prezzi usa `prices` + `prices_changed` (`InventoryRepository.kt:2482-2512`).
- `store_id`: nel quick path attuale e' `null`, perche' `syncEventStoreScope(null)` produce stringa vuota e `recordOrEnqueueSyncEvent` invia `storeScope.ifBlank { null }` (`InventoryRepository.kt:2428`, `:3187`, `:3325`).
- `source`: repository passa `"android"` (`InventoryRepository.kt:3326`).
- `source_device_id`: UUID v4 locale stabile per installazione (`InventoryRepository.kt:3161-3170`, `:3327`).
- `batch_id`: UUID v4 generato per ciclo quick e condiviso tra evento catalogo e prezzi (`InventoryRepository.kt:2473`, `:2497`, `:2509`, `:3328`).
- `client_event_id`: stringa tecnica `android-{batchId}-{domain}-{eventType}-{chunkIndex}-{fingerprint}` (`InventoryRepository.kt:3400-3413`).
- `metadata`: oggetto tecnico con `task`, `source`, `chunk_index`, `chunk_count`, `entity_ids_compacted` e, se compatto, `original_changed_count` (`InventoryRepository.kt:3310-3319`).
- Edge case compatto: se `ids.totalIds > SYNC_EVENT_ENTITY_ID_BUDGET` (`250`), Android invia `SyncEventEntityIds()` vuoto ma conserva `changedCount = ids.totalIds` (`InventoryRepository.kt:3385-3397`, `:3549`). TASK-068 ha test JVM per `260` con `changedCount=260` e `entityIds` vuoto (`DefaultInventoryRepositoryTest.kt:3650-3654`) e outbox compatta (`:3682-3687`).

**Contratto RPC locale Supabase:**
- `sync_events.changed_count integer not null default 0` con CHECK `changed_count >= 0`; la funzione `record_sync_event` pero' aggiunge il limite applicativo `p_changed_count < 0 or p_changed_count > 1000` -> `changed_count out of allowed range`, errcode `22023` (`20260424021936_task045_sync_events.sql:13,18,72,127-130`).
- `p_entity_ids jsonb` e' nullable; se presente deve essere un oggetto, con sole chiavi `supplier_ids`, `category_ids`, `product_ids`, `price_ids`; ogni valore deve essere array, max 250 id per array, UUID strict; payload max 16 KiB (`20260424021936_task045_sync_events.sql:73,91,167-208`).
- `p_metadata jsonb` viene coalesced a `{}`; deve essere oggetto, max 4096 byte, senza chiavi business/PII vietate (`20260424021936_task045_sync_events.sql:79,88,142-165`).
- `p_store_id` e `p_batch_id` sono `uuid` nullable; `p_source`, `p_source_device_id`, `p_client_event_id` sono `text`; `source_device_id` e `client_event_id` max 160 char (`20260424021936_task045_sync_events.sql:69-79,132-140`).
- Owner/RLS: RPC `SECURITY DEFINER`, owner derivato da `auth.uid()`, policy SELECT owner-scoped su `sync_events`, grant tabella solo SELECT ad authenticated, grant execute RPC ad authenticated (`20260424021936_task045_sync_events.sql:57-67,81-88,269-285`).

**Causa classificata:**
- Root/sotto-causa confermata nel confronto locale: `contract_mismatch` su `changedCount -> p_changed_count`.
- Android produce payload compatti con `entity_ids` vuoto e `changed_count` totale reale. Il contratto SQL locale accetta l'oggetto `entity_ids` vuoto, ma rifiuta `p_changed_count > 1000`.
- TASK-069 documenta scenario reale privacy-safe O1a: dopo push riuscito di 6033 prodotti e 75569 prezzi, i due eventi `catalog_changed` e `prices_changed` sono finiti in outbox con `lastErrorType=PayloadValidation` (`TASK-069:547`, `:554`, `:556`, `:563`). Questi conteggi superano il cap locale `1000`, quindi spiegano il rigetto senza invocare shape JSON o RLS.

**Falsificazione ipotesi:**

| Ipotesi | Esito | Evidenza |
|---------|-------|----------|
| `entity_ids` compatto/vuoto + `changed_count > 0` non accettato | Parzialmente confermata e precisata: il vuoto e' accettabile; il mismatch e' `changed_count > 1000` | SQL valida `entity_ids` come oggetto/array e non controlla somma ids vs count; SQL rifiuta `p_changed_count > 1000`; Android O1a produce 6033/75569 |
| camelCase vs snake_case | Refutata | Android usa `@SerialName("p_*")` e `supplier_ids`/`category_ids`/`product_ids`/`price_ids`; SQL allowlist usa gli stessi nomi |
| `store_id` nullo/formato non accettato | Refutata per il payload effettivo corrente | Quick path invia `p_store_id=null`; SQL `uuid default null`, colonna nullable |
| `metadata` shape | Refutata per il payload effettivo corrente | Metadata Android e' oggetto tecnico piccolo; chiavi inviate non sono nella denylist SQL |
| `client_event_id` duplicato/formato | Refutata come causa primaria | SQL prevede idempotenza su `(owner, client_event_id)` e ritorna riga esistente; stringa Android resta sotto 160 char nel formato corrente |
| RLS/owner/store | Non supportata come root del `PayloadValidation` O1a | RPC deriva owner da `auth.uid()`; TASK-046 storico documenta RLS/RPC live verdi; O1a ha push prodotti/prezzi riuscito e fallisce sulla registrazione evento |
| Schema drift locale vs live | Da chiarire solo per lo stato live attuale | TASK-046 documenta apply/verifica live 2026-04-24, ma TASK-071 non ha rieseguito query live read-only; non usare questa execution come prova live corrente |

**Drift documentale / live:**
- Android `docs/SUPABASE.md` gia' segnala che il SQL di `sync_events`/`record_sync_event` non e' versionato nel repo Android e che il live puo' driftare (`docs/SUPABASE.md:189-198`, `:408-411`), ma non esplicita il mismatch `changed_count > 1000` vs payload compatto TASK-068.
- Supabase locale aveva il contratto nel SQL, ma `docs/mapping_room_to_supabase.md` non riportava il rischio `changed_count` compatto; aggiornato in questa execution.
- Live/log: TASK-046 e' evidenza storica di apply/verifica live, non verifica corrente. Nessuna query live eseguita in TASK-071; nessun token/URL/secret usato o esposto.

**Classificazione finale:**
- `contract_mismatch`
- `backend_fix_needed`
- `documentation_drift`
- Residuo limitato: `schema_drift_da_chiarire` solo per confermare che il live attuale sia ancora allineato al SQL locale/storico.

**Proposta fix separata (non implementata):**
- Proporre TASK-072 backend: riallineare `record_sync_event` alla semantica degli eventi compatti. Opzione preferita da valutare: alzare/rimuovere il cap `p_changed_count <= 1000` oppure sostituirlo con una policy piu' ampia e documentata per eventi compatti, mantenendo i limiti difensivi su `entity_ids` e `metadata`.
- Se il backend vuole mantenere un cap rigido a 1000, aprire task Android separato per spezzare gli eventi compatti in piu' record con `changed_count <= 1000` senza payload id massivo. Non applicato qui.

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | N/A | Task diagnostico/documentale; nessun codice Kotlin, risorsa o build config modificati |
| Lint | N/A | Task diagnostico/documentale |
| Warning nuovi | N/A | Nessun codice modificato |
| Coerenza con planning | ✅ ESEGUITO | Lettura Supabase locale prima di Android; matrice compilata; nessun fix app/backend |
| Criteri di accettazione | ✅ ESEGUITO | Vedi dettaglio criteri sotto |

**Baseline regressione TASK-004:**
- Test eseguiti: non richiesti; nessun codice Kotlin/Room/repository/ViewModel modificato.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: il live attuale non e' stato interrogato; eventuale confronto live read-only resta task separato/autorizzato.

**Dettaglio criteri di accettazione:**

| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ESEGUITO | Matrice compilata nella sezione dedicata |
| 2 | ESEGUITO | Ogni riga matrice cita almeno Android + SQL locale o fonte pertinente |
| 3 | ESEGUITO | Causa classificata: mismatch `changed_count` > 1000 |
| 4 | N/A | Causa classificata; residuo live corrente documentato come drift da chiarire |
| 5 | ESEGUITO | Nessun deploy live |
| 6 | ESEGUITO | Nessun cleanup outbox / `sync_events` |
| 7 | ESEGUITO | TASK-070 riconfermato non bloccante e gia' `DONE` |
| 8 | ESEGUITO | Fix proposto solo testualmente |
| 9 | ESEGUITO | Percorso Supabase locale assoluto usato e documentato |
| 10 | ESEGUITO | File Supabase locali letti elencati sopra |
| 11 | ESEGUITO | Aggiornato `/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/mapping_room_to_supabase.md`; root README assente documentato |
| 12 | ESEGUITO | Nessuna modifica SQL/RPC/RLS/migration applicata |
| 13 | ESEGUITO | TASK-072 proposto solo come testo/handoff |

**Incertezze:**
- INCERTEZZA: lo stato live attuale non e' stato ricontrollato read-only in questa execution; TASK-046 resta evidenza storica, non prova corrente.
- INCERTEZZA: il messaggio server esatto dell'ultimo `PayloadValidation` live non e' stato raccolto in questa execution; la causa e' comunque classificata sul contratto locale + payload Android + conteggi TASK-069.

**Handoff notes:**
- Execution aveva portato TASK-071 a `REVIEW`; la review 2026-04-28 lo chiude a `DONE`.
- Proposta TASK-072 backend: adeguare contratto `record_sync_event` a eventi compatti massivi o formalizzare un nuovo limite e aprire eventuale task Android separato.
- Non cancellare outbox storica come scorciatoia: le righe sono evidenza del mismatch e del retry, non materiale da pulire dentro TASK-071.

### Review — 2026-04-28

**Verdetto:** PASS CON FIX DOCUMENTALI — execution corretta e sufficiente per chiudere TASK-071; applicati solo allineamenti documentali/governance.

**File letti in review:**
- `docs/MASTER-PLAN.md`
- `docs/TASKS/TASK-071-backend-rpc-record-sync-event-payload-validation.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/docs/mapping_room_to_supabase.md`
- `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260424021936_task045_sync_events.sql`
- `docs/SUPABASE.md`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `docs/TASKS/TASK-069-audit-sync-residui-outbox-price-generated-history.md`

**Correzioni applicate in review:**
- Chiuso TASK-071 a `DONE` dopo verifica severa della causa e della matrice.
- Allineato `docs/MASTER-PLAN.md` allo stato `DONE` e al follow-up backend proposto.
- Nessuna modifica a Kotlin, SQL, RPC, RLS, trigger, migration, schema o dati.

**Verifiche review:**
1. Confermato che la RPC locale `record_sync_event` accetta `p_entity_ids` oggetto JSONB con array vuoti ma rifiuta `p_changed_count < 0 or p_changed_count > 1000`.
2. Confermato che Android chiama `rpc("record_sync_event", params)` con parametri serializzati `p_*` e puo' inviare `changed_count` reale > 1000 quando compatta gli ID oltre budget.
3. Confermato che la causa non e' stata attribuita a camelCase/snake_case, `store_id`, `metadata`, `client_event_id` o RLS senza evidenza: tali ipotesi restano refutate o limitate al perimetro locale documentato.
4. Confermato che la verifica live corrente non e' stata rieseguita: `schema_drift_da_chiarire` resta solo residuo di parita' locale/live, non affermazione di drift reale.
5. Confermato che il fix necessario resta fuori perimetro e deve vivere in TASK-072/backend o task Android separato.

**Classificazione finale confermata:**
- `contract_mismatch`
- `backend_fix_needed`
- `documentation_drift`
- residuo: `schema_drift_da_chiarire` solo per live corrente non ricontrollato.

**Check review:**

| Check | Stato | Note |
|-------|-------|------|
| `git status --short` Android | ✅ ESEGUITO | Solo modifiche documentali/governance attese |
| `git diff --check` Android | ✅ ESEGUITO | Pulito |
| Trailing whitespace Markdown | ✅ ESEGUITO | Pulito sui Markdown modificati |
| Build/Lint/Test Android | N/A | Task solo documentale; nessun codice Kotlin/app modificato |
| Supabase locale `.git` | ✅ ESEGUITO | Cartella Supabase locale senza `.git`; review effettuata sui file locali |
| Perimetro safety | ✅ ESEGUITO | Nessun deploy live, nessuna modifica SQL/RPC/RLS/trigger/migration, nessun cleanup dati, nessun dato sensibile esposto |

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-28 |
| Esito review | PASS CON FIX DOCUMENTALI |

---

## Handoff

- TASK-071 e' chiuso `DONE`. Classificazione finale: `contract_mismatch` + `backend_fix_needed` + `documentation_drift`; residuo `schema_drift_da_chiarire` solo per live corrente non ricontrollato.
- Proposta TASK-072 backend: modificare/documentare `record_sync_event` affinche' accetti eventi compatti massivi (`changed_count` reale > 1000 con `entity_ids` vuoto) oppure definire formalmente un limite e coordinare task Android separato.
- Se si procede con TASK-072, includere test SQL/RLS/RPC sintetici privacy-safe: evento compatto `catalog_changed` con count > 1000, evento compatto `prices_changed` con count > 1000, metadata tecnico consentito, denylist ancora attiva, idempotenza `client_event_id`.
- Aggiornamento Android docs consigliato in task separato o fix documentale: `docs/SUPABASE.md` dovrebbe citare il mismatch `changed_count` se TASK-072 conferma la direzione.
- Vincoli mantenuti: nessun deploy live, nessuna modifica SQL/RPC/RLS/trigger/migration, nessun cleanup outbox/`sync_events`, nessuna modifica Kotlin.
