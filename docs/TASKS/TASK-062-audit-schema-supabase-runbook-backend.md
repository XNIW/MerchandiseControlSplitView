# TASK-062 — Audit schema Supabase e runbook backend

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-062 |
| Stato | `DONE` |
| Priorità | `MEDIA` |
| Area | Documentazione / Supabase / RLS / setup backend |
| Creato | 2026-04-26 |
| Ultimo aggiornamento | 2026-04-26 — review finale repo-grounded approvata, fix documentali applicati, chiuso in DONE |

### Governance check

| Verifica | Esito |
|----------|-------|
| Pre-execution: nessun task attivo in `MASTER-PLAN` | OK |
| TASK-061 `DONE` | OK |
| TASK-060 `BLOCKED` / sospeso, non `DONE` | OK |
| TASK-055 `PARTIAL` | OK |
| TASK-059 `DONE` | OK |
| TASK-063 `BACKLOG`, non attivato | OK |
| TASK-062 promosso da `PLANNING` a `EXECUTION` per execution documentale | OK |
| TASK-062 lasciato in `REVIEW`, non `DONE`, a fine execution | OK |
| TASK-062: file task vs `MASTER-PLAN` post-execution | OK — `MASTER-PLAN` riallineato con TASK-062 unico task attivo in `REVIEW` |
| TASK-062: file task vs `MASTER-PLAN` post-review finale | OK — TASK-062 `DONE`, nessun task attivo; TASK-060 `BLOCKED`, TASK-055 `PARTIAL`, TASK-063 `BACKLOG` |

> **Warning — perimetro:** nessuna migration **live**, nessun DDL **inventato**, nessuna modifica **Kotlin/XML/Gradle**.

---

## Decisioni prese nel planning

| Decisione | Implicazione |
|-----------|--------------|
| TASK-062 = audit **documentale** / backend | Execution = `.md` + README + SQL solo come **artefatti** tracciati |
| Nessuna migration **live** nel perimetro | Deploy = atto separato con approvazione esplicita |
| Nessun codice Android obbligatorio | Gap che richiedono codice → altro task |
| `schema.sql` / `0001_init.sql` / RPC SQL | Solo se derivati da **fonte verificata** (dump, export, migration già validata) |
| Smoke / QA device | **TASK-063**; non sostituisce audit schema |
| **TASK-055** resta `PARTIAL` | TASK-062 non chiude l’audit UX/sync globale |
| **TASK-063** resta `BACKLOG` | Non attivare da questo piano |

---

## Dipendenze

- **TASK-055** — `PARTIAL`
- **TASK-059** — `DONE`
- **TASK-061** — `DONE`
- **TASK-060** — `BLOCKED` (non riaprire qui)
- **TASK-063** — `BACKLOG` (smoke live; fuori perimetro)

---

## Scopo (EXECUTION futura)

Audit **repo-grounded** + runbook per replicare un backend Supabase allineato al client Android (PostgREST, RPC, Realtime, RLS). Output = documentazione e tabelle di gap; **non** applicazione SQL a prod.

### Output minimo vs opzionale (EXECUTION)

| Tipo | Cosa |
|------|------|
| **Minimo (must)** | D1 `docs/SUPABASE.md` (seguendo § skeleton), D2 README migration, D3 checklist ambiente, D4 gap table, D6 blocco no-live; matrice backend→Android copiata o richiamata da SUPABASE.md; ogni oggetto con **evidence level** |
| **Opzionale (nice)** | Duplicare checklist in SUPABASE.md se migliora navigazione; SQL addizionale oltre ciò che esiste solo se da fonte verificata; diagrammi |

---

## Deliverable obbligatori di EXECUTION

| # | Deliverable | Contenuto minimo |
|---|-------------|------------------|
| D1 | `docs/SUPABASE.md` | Schema atteso, RLS, RPC, Realtime, secrets/build, link a migration in repo, note remediation TASK-056 |
| D2 | `supabase/migrations/README.md` | Ordine file, prerequisiti, avvisi **non applicare a prod senza review**, differenza artefatto repo vs deploy |
| D3 | Checklist nuovo ambiente | Passi ordinati (progetto → DDL → RLS → RPC → indici → Realtime → build Android); checkbox |
| D4 | Tabella gap schema/versioning | Riga per oggetto: in repo? fonte? azione (dump / doc / migration futura) |
| D5 | Matrice backend → consumer Android | Questo documento § [Matrice backend → consumer Android](#matrice-backend--consumer-android); in execution copiare/estendere in `docs/SUPABASE.md` se utile |
| D6 | Blocco **Non applicare live senza approvazione** | Procedura esplicita: chi approva, dove si traccia, niente `supabase db push` / SQL prod ad hoc dal task |

**Non sono deliverable:** smoke device (→ TASK-063), chiusura TASK-055, codice Android.

---

## Struttura obbligatoria di `docs/SUPABASE.md` (skeleton — non creare il file in PLANNING)

> Il file va creato solo in **EXECUTION**. L’executor deve rispettare questa struttura (titoli adattabili, ordine logico rispettato).

| Sezione | Contenuto atteso (1–2 righe) |
|---------|------------------------------|
| **Overview** | Scopo doc, versione/ambiente di riferimento, link a questo task e a `supabase/migrations/`. |
| **Backend objects summary** | Elenco tabelle/RPC/viste citate dall’app; cosa è legacy vs attivo. |
| **Android ↔ Supabase mapping** | Riferimento alla matrice oggetto→file Kotlin; operazioni PostgREST/RPC/Realtime. |
| **Tables** | Per tabella: colonne attese, vincoli/upsert, indici, **evidence level** per riga; gap espliciti. |
| **RPC** | `record_sync_event`: firma lato client, comportamento atteso, SQL/versione se disponibile, evidence. |
| **Realtime** | Tabelle in publication; eventi (I/U/D); differenza `sync_events` vs `shared_sheet_sessions`. |
| **RLS / Security model** | Modello owner-scoped; policy note o riferimento migration; rischi cross-account. |
| **Secrets / BuildConfig** | Variabili `BuildConfig`, `local.properties` vs CI; cosa non committare. |
| **Migration/versioning policy** | Come si applicano i file in repo; separazione artifact vs deploy; regola no-SQL-inventato. |
| **New environment runbook** | Passi ordinati per nuovo progetto Supabase (allineato a checklist D3). |
| **Verification checklist** | RLS, RPC, Realtime, paginazione PostgREST; puntatore a TASK-063 per smoke. |
| **Troubleshooting** | Sintomi comuni (auth, 403, realtime muto, `sync_events` unavailable). |
| **Known gaps / Follow-up TASK-063** | Ciò che resta solo **LIVE** o **ASSUMPTION**; cosa validare su device. |

---

## Evidence level (obbligatorio in `docs/SUPABASE.md`)

Ogni tabella, RPC, policy RLS o configurazione Realtime documentata deve avere **almeno un** livello:

| Livello | Significato | Uso |
|---------|-------------|-----|
| `CODE` | Dedotto dal codice Kotlin Android (DTO, query, subscriber) | OK per forma client; **non** sostituisce DDL |
| `MIGRATION` | Confermato da file `.sql` versionato in **questa** repo | Es. RLS/index in `20260424190000_...` |
| `LIVE` | Verificato su progetto Supabase reale | Obbligo: allegare evidenza (dump, log SQL, screenshot, output CLI, path artifact interno) |
| `ASSUMPTION` | Ipotesi ragionevole | **Non** presentarla come fatto; segnare “da confermare” |

**Regola:** affermazioni `LIVE` senza evidenza concreta = **non accettabili** in review. Incrociare `CODE` + `MIGRATION` dove possibile; dove manca `MIGRATION`, dichiarare gap.

---

## Non obiettivi / Non incluso

- Migration **live**; DDL **fantasma**
- Cambio **Room/DAO/repository** obbligatorio
- **TASK-060** / **TASK-063** nel perimetro
- Dichiarare RLS “verificata” **senza evidenza** (dump, log test, export policy)

---

## Quick start — nuovo ambiente Supabase (bozza)

1. Progetto Supabase dedicato (meglio staging prima di prod).
2. Ricostruire schema da **fonte verificata** (dump schema-only o migrazioni complete), **non** da deduzione sola.
3. Applicare catena RLS coerente con `20260424190000_harden_rls_and_sync_indexes.sql` + policy **`sync_events`** se assenti nel file.
4. RPC `record_sync_event` + grant `authenticated`.
5. Realtime: publication su `sync_events` e `shared_sheet_sessions` (vedi § Realtime).
6. Android: `local.properties` o env CI con URL + anon key + Google Web Client ID; build e login smoke.
7. Matrice gap aggiornata; **nessun** deploy prod implicito.

---

## Matrice backend → consumer Android

Legenda **Stato:** **M** = migration repo; **K** = Kotlin; **∅** = mancante in repo; **L** = live. In `docs/SUPABASE.md` mappare ogni riga a **evidence level** (`MIGRATION`/`CODE`/…).

| Oggetto | File Kotlin (principali) | Operazioni | Stato | Rischio se schema diverge |
|---------|--------------------------|------------|-------|---------------------------|
| `inventory_suppliers` | `SupabaseCatalogRemoteDataSource`, `InventoryRepository` | select paginato, upsert, update tombstone (`deleted_at`/`updated_at`) | M RLS/index; K colonne | Upsert/tombstone falliscono; leak se RLS assente |
| `inventory_categories` | idem | idem | idem | idem |
| `inventory_products` | idem | idem | idem | idem |
| `inventory_product_prices` | `SupabaseProductPriceRemoteDataSource`, `InventoryRepository` | select paginato / by ids, upsert | M RLS/index; K colonne | Prezzi stale; 23505 se vincoli diversi |
| `shared_sheet_sessions` | `SupabaseSessionBackupRemoteDataSource`, `InventoryRemoteFetchSupport`, `SupabaseRealtimeSessionSubscriber`, `HistorySessionPushCoordinator`, `InventoryRepository` | select paginato, upsert; **Realtime** tutti i `PostgresAction` | M RLS/index; K DTO | Sessioni non visibili; Realtime muto se tabella non in publication |
| `sync_events` | `SupabaseSyncEventRemoteDataSource`, `SupabaseSyncEventRealtimeSubscriber`, `InventoryRepository` | select filtrato; **Realtime Insert**; capability probe | K; ∅ DDL/RLS in repo | Quick sync rotta; leak cross-owner se RLS assente |
| RPC `record_sync_event` | `SupabaseSyncEventRemoteDataSource` | rpc → decode `SyncEventRemoteRow` | K firma client; ∅ SQL repo | Outbox/retry; fallback full sync (TASK-061) |
| Realtime `sync_events` | `SupabaseSyncEventRealtimeSubscriber`, `MerchandiseControlApplication` | channel + `PostgresAction.Insert` filtro `owner_user_id` | K | Nessun wake-up incrementale |
| Realtime `shared_sheet_sessions` | `SupabaseRealtimeSessionSubscriber`, `MerchandiseControlApplication` | channel + `postgresChangeFlow` generico su tabella | K | Stesso |

---

## Migration strategy (vincoli operativi)

| Tipo | Definizione | Policy TASK-062 |
|------|-------------|-----------------|
| **DDL iniziale** | `CREATE TABLE`, estensioni, PK/UNIQUE per upsert | Solo se da **dump reale** (`pg_dump --schema-only`) o altra **fonte autoritativa**; **vietato inventare** |
| **Migration incrementale** | `ALTER`, policy, indici, `CREATE FUNCTION` | Ammissibile come file in `supabase/migrations/`; **artefatto repo**, non “ho applicato a prod” |
| **Dump schema-only** | Snapshot di un ambiente noto | Allegare evidenza (hash/data) in `docs/SUPABASE.md` o README |
| **Runbook manuale** | Passi umani (Dashboard, toggle Realtime) | Obbligatorio dove SQL non basta |

**Ordine preferito in EXECUTION:** (1) `docs/SUPABASE.md` + `supabase/migrations/README.md` + checklist; (2) eventuale `schema.sql` / `0001_init.sql` **solo** dopo dump controllato; (3) ulteriori `.sql` versionati come delta.

**Separazione netta:** commit in git ≠ deploy. Ogni SQL versionato è **tracciabilità**; l’applicazione a un progetto remoto è **atto separato** con approvazione.

### Policy operativa: nessun SQL “definitivo” senza fonte

> **Warning:** nessun `schema.sql`, `0001_init.sql` o testo RPC può essere scritto come schema **definitivo** se non deriva da fonte verificata (dump controllato, export Dashboard, migration già validata altrove tracciata).

Ogni **nuovo** file `.sql` aggiunto in execution deve essere intestato o accompagnato da metadati (README o header comment) con:

| Campo | Valore esempio |
|-------|----------------|
| **Fonte** | `pg_dump schema-only 2026-…` / adattamento da `20260424190000_…` / migration proposta review |
| **Ambiente target** | `dev` / `staging` / `prod` / `generic` (solo doc) |
| **Stato** | `artifact only` / `reviewed` / `applied` (quest’ultimo solo con log esterno al task) |
| **Applicazione live** | `no` (default TASK-062) / `sì` solo con decisione tracciata |
| **Approvazione** | Ruolo/data o ticket; vuoto se `artifact only` |

**Regola:** proposta SQL senza fonte = marcare **`ASSUMPTION`** nel doc, mai come verità backend.

---

## Secrets / BuildConfig / ambiente

| Variabile | Uso | Dove è definita (repo-grounded) |
|-----------|-----|----------------------------------|
| `SUPABASE_URL` | `createSupabaseClient` | `app/build.gradle.kts` → `BuildConfig.SUPABASE_URL` via `readLocalOrEnv` |
| `SUPABASE_PUBLISHABLE_KEY` | stesso | `BuildConfig.SUPABASE_PUBLISHABLE_KEY` |
| `GOOGLE_WEB_CLIENT_ID` | Google sign-in → Supabase | `BuildConfig.GOOGLE_WEB_CLIENT_ID` |

**Risoluzione valori:** `readLocalOrEnv` = prima `System.getenv`, poi `local.properties` (root progetto). Vedi `app/build.gradle.kts`.

| Contesto | Azione |
|----------|--------|
| Debug locale | `local.properties` (non committare segreti se repo pubblico; usare `.gitignore` già tipico per `local.properties`) |
| CI | Variabili d’ambiente nel runner/secrets store |
| **Non committare** | Chiavi reali in file tracciati; backup `service_role` in repo |

**Dev / staging / prod:** il codice **non** distingue flavor per Supabase; eventuali ambienti multipli = **build diverse** o override env a livello pipeline. Documentare in execution se esistono convenzioni di team (non deducibili dal solo `build.gradle.kts`).

---

## Realtime requirements

| Tabella | Publication | Eventi attesi dal codice | Note |
|---------|-------------|--------------------------|------|
| `sync_events` | Richiesta | **Insert** (`PostgresAction.Insert` in `SupabaseSyncEventRealtimeSubscriber`) | Filtro client `owner_user_id`; wake-up → coordinator |
| `shared_sheet_sessions` | Richiesta | **Insert / Update / Delete** (flow generico `PostgresAction` in `SupabaseRealtimeSessionSubscriber`) | RLS limita righe per JWT; verificare Dashboard “Realtime” per tabella |

**Differenza funzionale:** `sync_events` = bus eventi per **quick sync / catch-up** catalogo-prezzi; `shared_sheet_sessions` = **backup/restauri sessioni** cronologia + notifiche Realtime payload.

**Da verificare live / TASK-063:** publication effettiva; replica; latenza; permessi `authenticated` vs `anon` sul canale.

---

## RLS test checklist (EXECUTION / review, non ora)

Usare due utenti Supabase A/B (JWT distinti). Evidenza: risultato query/RPC o screenshot/log strumentale.

- [ ] User A **non** vede righe con `owner_user_id` di B su `inventory_*`, `inventory_product_prices`, `shared_sheet_sessions`, `sync_events`
- [ ] **Anon** non legge/scrive tabelle business via PostgREST
- [ ] RPC `record_sync_event` **non** consente inserimento con owner diverso da `auth.uid()` (no spoofing)
- [ ] Sessioni: solo owner corrente (policy allineate a migration TASK-056)
- [ ] `sync_events` filtrato per owner su select e coerente con RPC
- [ ] `history_entries` legacy: non esposto ad accesso cross-user utile (post-drop policy); nessun client Android dipendente

---

## Tabella gap schema / versioning (stato PLANNING)

| Oggetto | In repo (SQL) | Fonte attuale | Azione EXECUTION |
|---------|---------------|---------------|------------------|
| Tabelle `inventory_*` | Patch RLS/index solo | Dedotto Kotlin + migration delta | Dump o DDL da fonte autoritativa |
| `sync_events` | ∅ | Kotlin | Dump + policy + indici |
| `record_sync_event` | ∅ | Kotlin | Export funzione + grant |
| `shared_sheet_sessions` | RLS/index in migration | Kotlin + migration | Verifica DDL vs DTO |
| `history_entries` | Drop policy | Commento migration | Confermare obsolescenza |
| View `product_price_summary` | Hardening | Migration | Verifica definizione view |

---

## Repo-grounded findings (sintesi)

**Esiste:** un file `supabase/migrations/20260424190000_harden_rls_and_sync_indexes.sql` (RLS owner-scoped, indici, hardening advisor, rimozione policy legacy).

**Manca in repo:** `docs/SUPABASE.md`; DDL completo; `sync_events` + RPC versionati.

**Room-only (non Supabase):** `sync_event_outbox`, `sync_event_watermarks`, `sync_event_device_state` — vedi warning in § Execution traps.

Dettaglio colonne/policy: § [Backend schema atteso](#backend-schema-atteso-compatto) sotto.

---

## Backend schema atteso (compatto)

Legenda: **M** migration repo, **K** Kotlin, **∅** assente, **L** live.

- **`inventory_suppliers` / `inventory_categories`:** `id`, `owner_user_id`, `name`, `deleted_at`; upsert `onConflict id`. **M** RLS+index; **K** row types.
- **`inventory_products`:** campi in `InventoryProductRow`; tombstone `deleted_at`. **M** RLS+index; trigger `inventory_catalog_block_update_when_tombstoned` **L**.
- **`inventory_product_prices`:** `InventoryProductPriceRow`; upsert `onConflict id`. **M** RLS+index; view `product_price_summary` **M** (security_invoker).
- **`shared_sheet_sessions`:** DTO `SharedSheetSessionRecord` / upsert `remote_id`. **M** RLS+index `(owner_user_id, remote_id)`.
- **`sync_events`:** `SyncEventRemoteRow`; fetch per `owner_user_id`, `store_id`, `id` > watermark. **K**; **∅** DDL/RLS in repo.
- **RPC `record_sync_event`:** `SyncEventRecordRpcParams` → riga evento. **K**; **∅** SQL repo.
- **`history_entries`:** legacy; Android usa `shared_sheet_sessions`. **M** rimozione policy permissive.

---

## RLS / security model (sintesi)

- Owner = `auth.uid()` ≡ `owner_user_id` sulle righe (forma `(select auth.uid())` in migration).
- Rischio principale: **`sync_events`** e RPC non versionati → drift e leak se mal configurati.
- RPC deve forzare owner lato server (**L**).

---

## RPC `record_sync_event` (sintesi)

Parametri/response: `SyncEventModels.kt`. Failure: capability disabled / fallback TASK-061. SQL da versionare in EXECUTION con evidenza.

---

## Runbook esteso

Oltre al [Quick start](#quick-start--nuovo-ambiente-supabase-bozza): indici watermark `sync_events`, advisor Supabase, conferma `max_rows` PostgREST vs `INVENTORY_REMOTE_PAGE_SIZE` in `InventoryRemoteFetchSupport.kt`.

---

## Troubleshooting (documentazione)

| Sintomo | Cause probabili | Dove guardare |
|---------|-----------------|---------------|
| Client null / sync disabilitato | `BuildConfig` vuoto | `local.properties` / env |
| `sync_events_schema_or_rls_unavailable` | RLS/policy o tabella assente | Supabase logs, `docs/SUPABASE.md` post-audit |
| Realtime non scatta | Tabella non in publication o filtro | Dashboard Realtime |
| Paginazione troncata | `max_rows` PostgREST | `InventoryRemoteFetchSupport` |
| 403 sessioni | RLS / owner mismatch | Policy `shared_sheet_sessions` |

---

## Piano di execution (step)

1. Audit statico + export controllato da Supabase (se disponibile).
2. Scrivere **D1–D6** (deliverable).
3. Aggiornare tabella gap e matrice in `docs/SUPABASE.md`.
4. Allineare `MASTER-PLAN` quando lo stato TASK-062 uscirà da `PLANNING` (post-approvazione utente).
5. Nessun codice Android salvo nuovo task.

---

## Review gate (prima di approvare chiusura TASK-062)

Checklist per il **reviewer** (nessun item saltato senza motivazione):

- [ ] `docs/SUPABASE.md` esiste, ha indice/skeleton rispettato, leggibile
- [ ] Mapping backend → consumer Android **completo** (matrice + eventuali note)
- [ ] Nessuna affermazione presentata come fatto **live** senza evidenza (`LIVE` + allegato)
- [ ] Gap **`sync_events`** e **`record_sync_event`** espliciti (non minimizzati)
- [ ] Outbox / watermark / device state **Room-only** — non citati come tabelle Supabase
- [ ] `supabase/migrations/README.md` (o equivalente) presente
- [ ] Nessun deploy live dichiarato come obbligazione del task senza traccia di approvazione
- [ ] **TASK-055** non chiuso come `DONE` solo perché TASK-062 esiste
- [ ] **TASK-063** non attivato dal contenuto del task 062
- [ ] TASK-062 **non** `DONE` senza **conferma utente** esplicita (e allineamento `MASTER-PLAN`)

---

## Criteri di accettazione (EXECUTION)

| # | Criterio | Verifica |
|---|----------|----------|
| A1 | `docs/SUPABASE.md` creato, leggibile, struttura conforme al **skeleton** in § *Struttura obbligatoria di docs/SUPABASE.md* | Lettura + evidence level per oggetto |
| A2 | Mapping backend → consumer Android **completo** (matrice + eventuali note in SUPABASE.md) | Check vs tabella in questo file |
| A3 | Gap SQL/versioning documentati (tabella + azioni) | Nessun “buco” silenzioso su `sync_events`/RPC |
| A4 | Checklist RLS/RPC/Realtime **presenti** (questo file + copia in doc se serve) | Review |
| A5 | README `supabase/migrations` o equivalente | File in repo |
| A6 | Nessuna migration **live** applicata **come obbligatorio** del task senza traccia approvazione | Log task / decisione |
| A7 | Nessun codice Kotlin/XML/Gradle modificato **salvo** task separato | `git diff` |
| A8 | `MASTER-PLAN` coerente allo **stato dichiarato** del task (es. dopo `EXECUTION`/`DONE`) | Confronto backlog vs file task |
| A9 | TASK-062 **non** `DONE` finché review/conferma utente non chiudono il ciclo | Governance |
| A10 | § **Review gate** percorsa dal reviewer senza voci saltate ingiustificatamente | Checklist spuntata nel file task o in review |

---

## Execution traps

1. **Inventare** `schema.sql` senza dump o fonte autoritativa.
2. **Confondere** outbox/watermark Room con tabelle Supabase.
3. Dichiarare **RLS verificata** senza evidenza (test/snapshot).
4. **Applicare** migration a prod dal branch documentazione.
5. **Modificare** codice Android per “far quadrare” la documentazione invece di correggere il doc o aprire task.
6. **Attivare** TASK-063 o **chiudere** TASK-055 da questo task.
7. Trattare commit SQL come **deploy implicito** — è sempre un atto separato.

---

## Rischi

Deduzione senza DB; drift; Realtime non pubblicato; RPC permissiva; dipendenza da TASK-063 per prova comportamentale.

---

## Domande aperte

1. Fonte DDL completa esterna al repo?
2. Live: colonne extra obbligatorie su `sync_events` oltre `SyncEventRemoteRow`?

---

## Handoff

- **Executor:** produrre D1–D6; evidenze per RLS checklist; niente live senza approval tracciata.
- **Reviewer:** rigore “evidence-based”; niente affermazioni live senza prova.
- **TASK-063:** smoke A/B; non sostituisce audit schema.

---

## Execution

### Esecuzione — 2026-04-26

**Transizione stato:**
- Avvio: TASK-062 in `PLANNING`, nessun altro task attivo.
- Durante execution: TASK-062 promosso a `EXECUTION` documentale.
- Fine execution: TASK-062 lasciato in `REVIEW`, non `DONE`.

**File creati/modificati:**
- `docs/SUPABASE.md` — creato runbook Supabase repo-grounded con evidence level, mapping Android/backend, tabelle, RPC, Realtime, RLS, secrets, policy migration, quick start, checklist, troubleshooting e gap table.
- `supabase/migrations/README.md` — creato README policy per artefatti SQL versionati, differenza commit/deploy live, prerequisiti, tipi di SQL e metadata obbligatori futuri.
- `docs/MASTER-PLAN.md` — riallineato TASK-062 come unico task attivo in `REVIEW`; mantenuti TASK-060 `BLOCKED`, TASK-055 `PARTIAL`, TASK-063 `BACKLOG`.
- `docs/TASKS/TASK-062-audit-schema-supabase-runbook-backend.md` — aggiornato stato/log execution; nessuna chiusura `DONE`.

**Evidenze lette:**
- Governance: `docs/MASTER-PLAN.md`, `CLAUDE.md`, `AGENTS.md`, `docs/CODEX-EXECUTION-PROTOCOL.md`.
- Planning TASK-062: questo file task completo.
- SQL repo: `supabase/migrations/20260424190000_harden_rls_and_sync_indexes.sql`.
- Android Supabase/sync: `SupabaseCatalogRemoteDataSource.kt`, `SupabaseProductPriceRemoteDataSource.kt`, `SupabaseSyncEventRemoteDataSource.kt`, `SupabaseSyncEventRealtimeSubscriber.kt`, `SupabaseSessionBackupRemoteDataSource.kt`, `SupabaseRealtimeSessionSubscriber.kt`, `SupabaseAuthManager.kt`, `InventoryRemoteFetchSupport.kt`, `InventoryCatalogRemoteRows.kt`, `SharedSheetSessionRecord.kt`, `SyncEventModels.kt`, `MerchandiseControlApplication.kt`, `CatalogAutoSyncCoordinator.kt`, `CatalogSyncStateTracker.kt`, sezioni rilevanti di `InventoryRepository.kt`, `SessionRemotePayload.kt`.
- Test consultati come contesto: `DefaultInventoryRepositoryTest.kt`, `CatalogAutoSyncCoordinatorTest.kt`, `CatalogSyncViewModelTest.kt`, `SupabaseRealtimeSessionSubscriberLiveTest.kt`.
- Build config: `app/build.gradle.kts`.

**Azioni eseguite:**
1. Verificata governance iniziale: nessun task attivo; TASK-061 `DONE`; TASK-060 `BLOCKED`; TASK-055 `PARTIAL`; TASK-063 `BACKLOG`; TASK-062 in `PLANNING`.
2. Audit statico repo-grounded su migration SQL e codice Android che consuma Supabase/PostgREST/RPC/Realtime.
3. Creato `docs/SUPABASE.md` con separazione esplicita tra `CODE`, `MIGRATION`, `ASSUMPTION` e assenza di evidenze `LIVE`.
4. Creato `supabase/migrations/README.md` con policy `commit SQL != deploy live` e no-SQL-inventato.
5. Documentata matrice backend -> consumer Android per inventory tables, product prices, shared sessions, `sync_events`, RPC, Realtime e legacy `history_entries`.
6. Documentata gap table: DDL completo non versionato, `sync_events` DDL/RLS non versionato, RPC `record_sync_event` SQL non versionato, publication Realtime da verificare live, schema dump assente.
7. Portate nel runbook checklist RLS/RPC/Realtime senza marcarle come eseguite.
8. Riallineato `MASTER-PLAN` allo stato finale `REVIEW` di TASK-062.

**Gap trovati:**
- DDL completo delle tabelle Supabase principali non versionato nel repo: presente solo una migration incrementale di hardening RLS/indici.
- `sync_events` DDL/RLS/indici non versionati.
- RPC `record_sync_event` SQL/grants/owner enforcement non versionati.
- Publication Realtime per `sync_events` e `shared_sheet_sessions` da verificare live.
- Schema dump assente.
- `product_price_summary` remoto ha hardening `security_invoker` versionato, ma non la definizione view.
- `history_entries` remoto resta legacy: il repo versione drop policy permissive, mentre Android usa `shared_sheet_sessions` per sync sessioni.

**Cosa NON è stato fatto:**
- Nessuna modifica Kotlin/XML/Gradle.
- Nessuna migration live, nessun `supabase db push`, nessun accesso/modifica a Supabase remoto.
- Nessun SQL definitivo inventato o aggiunto.
- TASK-063 non attivato.
- TASK-060 non riaperto.
- TASK-055 non chiuso.
- TASK-062 non marcato `DONE`.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ⚠️ N/A | Task solo documentazione/governance; nessun codice Kotlin, risorse o build config modificati. |
| Lint | ⚠️ N/A | Task solo documentazione/governance; nessun codice Kotlin, risorse o build config modificati. |
| Warning nuovi | ⚠️ N/A | Nessun codice modificato; warning Kotlin/deprecation non applicabili. |
| `git diff --check` | ✅ ESEGUITO | Eseguito a fine modifiche; nessun whitespace error. |
| Coerenza con planning | ✅ ESEGUITO | Prodotti D1-D6 documentali; nessun fuori scope intenzionale. |
| Criteri di accettazione | ✅ ESEGUITO | Vedi tabella criteri sotto. |

**Baseline regressione TASK-004 (se applicabile):**
- Applicabilità: non applicabile.
- Motivazione: task solo documentazione/governance; nessun file `InventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`, import/export, Room, risorse o build config modificato.
- Test eseguiti: nessuno.
- Test aggiunti/aggiornati: nessuno.
- Limiti residui: le checklist live RLS/RPC/Realtime restano da eseguire in review operativa/TASK-063 o task backend dedicato.

**Criteri di accettazione:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| A1 | `docs/SUPABASE.md` creato, leggibile, struttura conforme allo skeleton | ESEGUITO | File creato con indice, overview, summary, mapping, tables, RPC, Realtime, RLS, secrets, migration policy, runbook, checklist, troubleshooting, gaps. |
| A2 | Mapping backend -> consumer Android completo | ESEGUITO | Matrice in `docs/SUPABASE.md` con inventory tables, product prices, shared sessions, `sync_events`, RPC, Realtime, `history_entries`. |
| A3 | Gap SQL/versioning documentati | ESEGUITO | Gap table esplicita in `docs/SUPABASE.md`, inclusi `sync_events` e `record_sync_event`. |
| A4 | Checklist RLS/RPC/Realtime presenti | ESEGUITO | Checklist operative presenti e non dichiarate eseguite. |
| A5 | README `supabase/migrations` presente | ESEGUITO | Creato `supabase/migrations/README.md`. |
| A6 | Nessuna migration live applicata senza approvazione | ESEGUITO | Nessun comando Supabase live eseguito; policy no-live documentata. |
| A7 | Nessun codice Kotlin/XML/Gradle modificato | ESEGUITO | Modifiche limitate a `.md`; verificabile via `git diff --name-only`. |
| A8 | `MASTER-PLAN` coerente allo stato dichiarato | ESEGUITO | TASK-062 unico attivo in `REVIEW`; TASK-060/055/063 mantenuti nei rispettivi stati. |
| A9 | TASK-062 non `DONE` | ESEGUITO | Stato finale `REVIEW`. |
| A10 | Review gate pronto per reviewer | ESEGUITO | Runbook e gap table predisposti; reviewer deve percorrere checklist senza saltare voci. |

**Incertezze:**
- Nessuna evidenza `LIVE` disponibile nel repo: tutte le verifiche live restano da eseguire separatamente.
- DDL/RPC effettivi del progetto Supabase reale non sono deducibili integralmente dal repo.

**Handoff notes:**
- Reviewer: verificare che `docs/SUPABASE.md` non presenti come live alcun oggetto privo di evidenza.
- Backend/TASK futuro: acquisire schema dump o export SQL verificato prima di aggiungere DDL/RPC definitivi.
- TASK-063: mantenere separato per smoke live A/B sync, Realtime publication e isolamento RLS.

---

## Fix

### Fix — 2026-04-26

**File modificati:**
- `docs/SUPABASE.md` — aggiunto livello `LOCAL_SUPABASE_PROJECT`, sezione fonti locali Supabase lette in sola lettura, riferimenti puntuali alle migration locali e correzione dei gap da “mancante” a “assente nel repo Android ma presente come fonte locale da validare/importare”.
- `supabase/migrations/README.md` — aggiunta policy per usare la cartella locale Supabase come fonte esterna senza confonderla con deploy live.
- `docs/TASKS/TASK-062-audit-schema-supabase-runbook-backend.md` — registrata review finale, fix applicati e chiusura.
- `docs/MASTER-PLAN.md` — riallineato stato finale TASK-062 `DONE`, nessun task attivo; TASK-060 `BLOCKED`, TASK-055 `PARTIAL`, TASK-063 `BACKLOG`.

**Azioni eseguite:**
1. Percorsa la review gate del task e verificata governance corrente.
2. Letta la cartella locale `/Users/minxiang/Desktop/MerchandiseControlSupabase` in sola lettura.
3. Integrate come evidenza locale, non live, le migration:
   - `20260416_task010_shared_sheet_sessions_realtime.sql`
   - `20260417_task012_ownership_rls.sql`
   - `20260417120000_task013_inventory_catalog_rls.sql`
   - `20260417200000_task016_inventory_product_prices.sql`
   - `20260418200000_task019_inventory_catalog_tombstone.sql`
   - `20260421120000_task038_restrict_authenticated_delete_inventory.sql`
   - `20260422120000_task040_shared_sheet_sessions_v2.sql`
   - `20260424021936_task045_sync_events.sql`
4. Integrati come contesto locale `docs/mapping_room_to_supabase.md`, `docs/decisions.md` e `TASKS/046_apply_verify_sync_events_staging_live.md`, senza marcarli come evidenza `LIVE`.
5. Nessuna migration live applicata, nessun `supabase db push`, nessuna modifica a Supabase remoto.
6. Nessun codice Android Kotlin/XML/Gradle modificato.

**Review gate finale:**
| Gate | Stato | Evidenza |
|------|-------|----------|
| `docs/SUPABASE.md` esiste, indice/skeleton leggibile | ESEGUITO | Runbook con indice, mapping, tabelle, RPC, Realtime, RLS, secrets, policy migration, checklist, troubleshooting e gap. |
| Mapping backend -> consumer Android completo | ESEGUITO | Matrice aggiornata per `inventory_*`, prezzi, sessioni, `sync_events`, RPC, Realtime, legacy `history_entries`. |
| Nessuna affermazione `LIVE` senza evidenza | ESEGUITO | Evidenza locale marcata `LOCAL_SUPABASE_PROJECT`; `LIVE` non usato come stato verificato. |
| Gap `sync_events` / `record_sync_event` espliciti | ESEGUITO | Gap qualificati: SQL assente nel repo Android, fonte locale trovata, live da verificare. |
| Outbox/watermark/device state Room-only | ESEGUITO | `docs/SUPABASE.md` li mantiene come tabelle locali, non Supabase. |
| README migration presente e sicuro | ESEGUITO | `supabase/migrations/README.md` distingue commit SQL, fonte locale, artifact e deploy live. |
| Nessun deploy live implicito | ESEGUITO | Divieti no-live confermati e rispettati. |
| TASK-055 non chiuso | ESEGUITO | Resta `PARTIAL`. |
| TASK-063 non attivato | ESEGUITO | Resta `BACKLOG`. |
| TASK-062 chiuso solo dopo review esplicita utente | ESEGUITO | Utente ha richiesto review finale + chiusura se OK; questo fix conclude in `DONE`. |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ⚠️ N/A | Task solo documentazione/governance; nessun codice Kotlin, risorse o build config modificati. |
| Lint | ⚠️ N/A | Task solo documentazione/governance; nessun codice Kotlin, risorse o build config modificati. |
| Warning nuovi | ⚠️ N/A | Nessun codice modificato; warning Kotlin/deprecation non applicabili. |
| `git diff --check` | ✅ ESEGUITO | Eseguito dopo i fix finali; nessun whitespace error. |
| `git status` | ✅ ESEGUITO | Modifiche limitate a documentazione/governance. |
| Coerenza con planning | ✅ ESEGUITO | Scope documentale rispettato; nessun SQL definitivo inventato; nessun deploy live. |
| Criteri di accettazione | ✅ ESEGUITO | A1-A10 verificati; dettagli in review gate finale e tabella sotto. |

**Criteri di accettazione finali:**
| # | Stato | Evidenza finale |
|---|--------|-----------------|
| A1 | ESEGUITO | `docs/SUPABASE.md` leggibile e conforme, ora include anche evidenza locale distinta da live. |
| A2 | ESEGUITO | Mapping backend -> Android completo. |
| A3 | ESEGUITO | Gap SQL/versioning corretti: Android repo vs fonte locale vs live. |
| A4 | ESEGUITO | Checklist RLS/RPC/Realtime presenti e non marcate come eseguite live. |
| A5 | ESEGUITO | `supabase/migrations/README.md` presente e aggiornato. |
| A6 | ESEGUITO | Nessuna migration live applicata. |
| A7 | ESEGUITO | Nessun codice Kotlin/XML/Gradle modificato. |
| A8 | ESEGUITO | `MASTER-PLAN` riallineato a TASK-062 `DONE`. |
| A9 | ESEGUITO | Chiusura `DONE` avvenuta solo dopo richiesta esplicita di review finale. |
| A10 | ESEGUITO | Review gate percorsa e documentata in questa sezione. |

**Rischi residui:**
- La cartella Supabase locale è una fonte locale, non una verifica live; TASK-063 resta necessario per smoke A/B e verifica live RLS/RPC/Realtime.
- Il repo Android non contiene ancora la catena completa di migration Supabase; serve decisione futura se importare artefatti SQL o aggiungere dump schema-only.
- `product_price_summary` remoto resta senza definizione view versionata nel repo Android.

**Handoff per TASK-063 / futuro backend:**
- Usare `docs/SUPABASE.md` come checklist.
- Se si vuole validazione live A/B, aprire esplicitamente TASK-063.
- Se si vuole rendere il backend replicabile dal repo Android, aprire un task separato per importare/revisionare gli artefatti SQL dalla cartella Supabase locale o da dump schema-only.

## Chiusura

### Chiusura — 2026-04-26

**Stato finale:** `DONE`.

**Verdict:** APPROVED -> DONE.

**Sintesi:** la review severa ha trovato un problema medio di completezza: il runbook non considerava la cartella locale Supabase, dove esiste una catena SQL utile per catalogo, prezzi, sessioni, `sync_events`, RPC e Realtime. Il fix ha integrato quella fonte come `LOCAL_SUPABASE_PROJECT`, senza dichiararla `LIVE` e senza copiare SQL definitivo nel repo Android.

**Non fatto:** nessuna migration live, nessun `supabase db push`, nessuna modifica a Supabase remoto, nessun codice Android modificato, TASK-063 non attivato, TASK-055 non chiuso, TASK-060 non riaperto.
