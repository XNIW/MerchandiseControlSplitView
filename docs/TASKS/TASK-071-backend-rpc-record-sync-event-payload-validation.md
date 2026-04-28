# TASK-071 — Verifica contratto RPC `record_sync_event` / cause `PayloadValidation`

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-071 |
| Stato | `BACKLOG` |
| Priorità | `ALTA` |
| Area | Backend Supabase — RPC `record_sync_event`, contratto payload, classificazione errore |
| Creato | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — creato come follow-up backend di TASK-069 (audit) |

---

## Dipendenze

- **TASK-069** `DONE` — audit diagnostico (anchor: 918 righe outbox tutte con `lastErrorType=PayloadValidation`, RPC `record_sync_event` fallisce per catalog/prices durante O1a).
- **TASK-064 / TASK-065** `DONE` — lineage `PayloadValidation` RPC/decoding storico.
- **TASK-070** (parallelo, app side) — può procedere indipendentemente; TASK-071 non sblocca TASK-070 né viceversa.

---

## Scopo

Determinare la **causa esatta** del rigetto `PayloadValidation` da parte della RPC `record_sync_event` per eventi `catalog_changed` / `prices_changed` / `catalog_tombstone`, **senza** modifiche live distruttive e senza chiusura forzata di TASK-069.

Obiettivi:

1. Riprodurre `PayloadValidation` con un payload **rappresentativo privacy-safe** (campioni mascherati, conteggi reali).
2. Documentare il **contratto effettivo** dell'RPC vs il payload prodotto da `recordOrEnqueueSyncEvent` (`InventoryRepository.kt:3152`): nomi parametri (`p_domain`, `p_event_type`, `p_changed_count`, `p_entity_ids`, `p_store_id`, `p_source`, `p_source_device_id`, `p_batch_id`, `p_client_event_id`, `p_metadata`), tipi attesi, vincoli RLS.
3. Identificare quale parte del payload è invalida (es. `entity_ids` shape, `changed_count` con `entity_ids` vuoti, `store_id` null vs string, `client_event_id` formato, `metadata` JSON shape).
4. Decidere fix backend (DDL/RPC) o app-side (correzione payload) — **fix app solo se** sicuro e coerente con contratto documentato; **fix backend** sempre come task separato (es. `TASK-072 — Fix RPC record_sync_event payload contract`) o coordinato con repo Supabase locale.

---

## Contesto

L'audit TASK-069 (Execution 2026-04-27) ha dimostrato:

- 918 righe in `sync_event_outbox`, **tutte con `lastErrorType=PayloadValidation`**.
- Durante O1a (import con delta reale 6033 prodotti / 75569 prezzi pushati con successo), la RPC `record_sync_event` ha fallito sia per `catalog_changed` (id 917) sia per `prices_changed` (id 918), accodando le righe in outbox con `attemptCount=0`.
- Il payload include `entity_ids` compatto (`SyncEventEntityIds()` vuoto) + `changedCount` quando `totalIds > SYNC_EVENT_ENTITY_ID_BUDGET=250` (`InventoryRepository.kt:3219`).
- Il dettaglio remoto del rigetto **non è osservabile** dai log attuali senza estensione di logging error body privacy-safe (proposta in TASK-070) o accesso al log Supabase Postgres.

L'audit ha esplicitamente vincolato: **STOP** qualsiasi implementazione backend dentro TASK-069 (gate anti-scope-creep). Questo task raccoglie il follow-up backend.

---

## Non incluso

- **Nessuna modifica live** schema/RPC/RLS/trigger/migration **senza approvazione esplicita** dell'utente; questo task può proporre, non eseguire deploy.
- **Nessuna pulizia** di `sync_event_outbox` o `sync_events` lato app o lato backend per "far scendere il numero".
- **Nessun fix app** `recordOrEnqueueSyncEvent` o retry — quello vive in TASK-070.
- **Nessun cambio UX** `CloudSyncIndicator` / `OptionsScreen`.
- **Nessuna assunzione** sullo schema remoto senza evidenza dal repo Supabase locale o da log Postgres.

---

## File / aree potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SupabaseSyncEventRemoteDataSource.kt` — definizione RPC params lato client.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/SyncEventModels.kt` — `SyncEventRecordRpcParams`, `SyncEventEntityIds`, costanti budget.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt:3152` — `recordOrEnqueueSyncEvent` (solo lettura per contratto).
- Repo Supabase locale (riferimento, **non live**): `/Users/minxiang/Desktop/MerchandiseControlSupabase` (vedi `docs/SUPABASE.md` policy `LOCAL_SUPABASE_PROJECT` vs `LIVE`).
- `docs/SUPABASE.md`, `docs/TASKS/TASK-064-*.md`, `docs/TASKS/TASK-065-*.md` — lineage storico.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Definizione **contratto effettivo** RPC `record_sync_event` documentata (parametri, tipi, vincoli) — fonte: repo locale Supabase o documentazione live | Doc | — |
| 2 | Payload prodotto da `recordOrEnqueueSyncEvent` confrontato voce per voce con il contratto; differenze elencate | Doc + S | — |
| 3 | Causa `PayloadValidation` identificata (es. shape `entity_ids`, `changed_count`>0 con `entity_ids` vuoti, vincolo RLS, ecc.) o classificata `da_chiarire` con motivazione | Doc + log | — |
| 4 | Fix proposto: **(a)** backend RPC change → task figlio backend con DDL/RPC; **(b)** correzione payload app-side → task figlio app con test JVM; o **(c)** combinazione | Doc | — |
| 5 | **Nessuna** modifica live schema/RPC/RLS/trigger/migration eseguita in questo task (solo proposta + diagnosi) | Governance | — |
| 6 | **Nessuna** pulizia outbox/sync_events come surrogato di fix | Governance | — |
| 7 | Privacy-safe: nessun JWT/token/URL completo nei log/documenti; campioni `clientEventId` / `entityIds` mascherati | Review | — |
| 8 | Coerenza con TASK-069 (`outbox_payload_validation_blocked` + `outbox_backend_contract_mismatch`) | Doc | — |

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task creato in `BACKLOG` come follow-up backend di TASK-069 | Gate anti-scope-creep TASK-069: backend richiede task separato | 2026-04-27 |
| 2 | Nessuna modifica live autorizzata in questo task | Governance: solo diagnosi + proposta; deploy richiede task dedicato + conferma utente | 2026-04-27 |
| 3 | Coordinamento con TASK-070 lato app: non bloccante reciproco | TASK-070 può migliorare retry/logging anche senza chiusura TASK-071 | 2026-04-27 |

---

## Planning (Claude — bozza)

### Analisi

`SupabaseSyncEventRemoteDataSource.recordSyncEvent` chiama l'RPC `record_sync_event` con `SyncEventRecordRpcParams`. La causa più probabile del `PayloadValidation` è uno tra:

- vincolo `changed_count >= entity_ids.length` quando `entityIdsCompacted=true` (ids vuoti + count grande);
- shape `entity_ids` JSON non riconosciuta (chiavi snake_case vs camelCase nel JSONB);
- vincolo RLS che richiede `store_id` non null;
- vincolo `metadata` JSON shape (es. richiede chiavi specifiche);
- vincolo unique `(owner_user_id, client_event_id)` con duplicato pre-esistente.

L'analisi richiede accesso al repo Supabase locale o ai log Postgres remoti (privacy-safe).

### Piano di esecuzione (bozza)

1. Estrarre dal repo locale Supabase la definizione RPC `record_sync_event` (DDL + funzione PL/pgSQL + eventuali constraint RLS).
2. Confrontare con `SyncEventRecordRpcParams`.
3. Riprodurre con un payload campione mascherato (es. tramite curl o emulator con logging error body esteso — coordinare con TASK-070).
4. Documentare causa + proposta fix.
5. Aprire task figlio `TASK-072 — Fix RPC record_sync_event payload contract` (backend) e/o task figlio app se serve correzione client-side.

### Rischi identificati

- Schema remoto non coincidente con repo locale → diagnosi `da_chiarire` se non si trova evidenza definitiva.
- Privacy: log error body può contenere dati prodotto/owner → mascheramento obbligatorio.
- Tentazione di "fixare l'app" con workaround fragili senza contratto → vietato (vincolo TASK-069).

---

## Execution / Review / Fix

(vuoti in `BACKLOG`)

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | — |
| Data chiusura | — |

---

## Handoff

- Riferimento: TASK-069 § *Execution / Deliverable 3 / Fix candidate / "Validazione/contratto payload record_sync_event"* + § *Gate anti-scope-creep*.
- TASK-070 cura retry head-of-line + logging app-side; può fornire log error body privacy-safe utili a questo task.
- Nessun deploy live senza conferma esplicita utente; usare repo Supabase locale come prima fonte.
