# TASK-071 — Verifica contratto RPC `record_sync_event` / cause `PayloadValidation`

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-071 |
| Stato | `BACKLOG` *(pianificazione operativa raffinata; **non** promosso a `EXECUTION`)* |
| Priorità | `ALTA` |
| Area | Diagnosi backend/contratto RPC `record_sync_event`, classificazione errore — **nessuna modifica backend/app in questo task** |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — passata editoriale/governance (coerenza, output, criteri verificabili) |

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

## Matrice contratto RPC *(da compilare in EXECUTION)*

Legenda **Esito riga:** `OK` \| `mismatch` \| `da_chiarire` (default finché non si riempie con evidenza). La **classificazione sintetica finale** del task usa le etichette in **Output finale richiesto**.

| Parametro client | Valore/tipo prodotto da Android | Parametro RPC atteso | Tipo SQL atteso | Nullable? | Vincoli/RLS/check | Evidenza fonte | Esito |
|------------------|----------------------------------|----------------------|-----------------|-----------|-------------------|----------------|-------|
| `domain` → serial `p_domain` | `String` (`SyncEventDomains`: `catalog` / `prices`) | `p_domain` | *da repo SQL locale* | *da definizione* | *da RPC/RLS* | Android `SyncEventRecordRpcParams` + DDL locale | da_chiarire |
| `eventType` → `p_event_type` | `String` (`SyncEventTypes`, es. `catalog_changed`, `prices_changed`, …) | `p_event_type` | *da repo SQL* | *da definizione* | *da RPC/RLS* | Idem | da_chiarire |
| `changedCount` → `p_changed_count` | `Int` (può essere >0 con ids compatti) | `p_changed_count` | *da repo SQL* | *da definizione* | es. coerenza con `entity_ids` | `recordOrEnqueueSyncEvent` + DDL locale | da_chiarire |
| `entityIds` → `p_entity_ids` | `SyncEventEntityIds?` JSON: `supplier_ids`, `category_ids`, `product_ids`, `price_ids` (liste stringhe, **snake_case**) | `p_entity_ids` | jsonb / testo / *tipo SQL* | *da definizione* | shape, lunghezze | `SyncEventModels.kt` + DDL locale | da_chiarire |
| `storeId` → `p_store_id` | `String?` | `p_store_id` | *uuid/text?* | sì (nullable lato Kotlin) | RLS / ownership store | Android + DDL/RLS | da_chiarire |
| `source` → `p_source` | `String?` default `"android"` | `p_source` | *da repo SQL* | *da definizione* | *check* | Android + DDL | da_chiarire |
| `sourceDeviceId` → `p_source_device_id` | `String?` | `p_source_device_id` | *da repo SQL* | *da definizione* | *check* | Android + DDL | da_chiarire |
| `batchId` → `p_batch_id` | `String?` | `p_batch_id` | *da repo SQL* | *da definizione* | *unicità/consistenza* | Android + DDL | da_chiarire |
| `clientEventId` → `p_client_event_id` | `String?` (idempotenza client) | `p_client_event_id` | *da repo SQL* | *da definizione* | unique / formato | Android + DDL | da_chiarire |
| `metadata` → `p_metadata` | `JsonObject` kotlinx (default `{}`) | `p_metadata` | jsonb | *da definizione* | shape ammesso | Android + DDL | da_chiarire |

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

(vuoti finché il task resta `BACKLOG` / non è in `EXECUTION`)

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | — |
| Data chiusura | — |

---

## Handoff

- Una futura **EXECUTION** di TASK-071 deve **iniziare** da **`/Users/minxiang/Desktop/MerchandiseControlSupabase`** (struttura, docs, RPC/RLS/migrations pertinenti), poi incrociare Android e `docs/SUPABASE.md`.
- Se si propone una **modifica backend esecutiva**, il **task successivo** deve prevedere aggiornamento **sia** del repo Supabase locale **sia**, se pertinente, della **documentazione Android** collegata (es. `docs/SUPABASE.md`).
- **TASK-070** resta **indipendente**: può procedere **prima** o in parallelo; logging privacy-safe da TASK-070 può alimentare TASK-071.
- Fix backend (RPC/DDL/RLS): proporre **TASK-072** o task dedicato — **non** creazione automatica da questo file. Fix payload app: task Android separato (eventuale link a TASK-070 solo se in scope).
- Riferimento audit: TASK-069 — gate anti-scope-creep, **nessun deploy live** senza workflow dedicato.
