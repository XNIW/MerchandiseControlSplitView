# TASK-061 — Hardening `sync_events` e fallback full sync

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-061 |
| Stato | `PLANNING` |
| Priorità | `MEDIA` / `ALTA` (hardening) |
| Area | Supabase `sync_events` / repository / coordinator / ViewModel / test JVM / fallback UX |
| Creato | 2026-04-25 |
| Ultimo aggiornamento | 2026-04-25 |

**Governance:** **`TASK-060`** risulta **unico task attivo** in `docs/MASTER-PLAN.md` in fase **`REVIEW`** (**bloccante**). Questo file inizializza **solo il piano** per TASK-061. **Non** impostare TASK-061 come task attivo nel `MASTER-PLAN` né avviare `EXECUTION` senza transizione esplicita (chiusura / sospensione documentata di TASK-060 o decisione utente).

---

## Dipendenze

- **TASK-055** `PARTIAL` — contesto audit sync Supabase / UX
- **TASK-060** `REVIEW` (bloccante in master-plan alla data di creazione di questo file) — nessun conflitto di codice previsto; ordine operativo: rispettare «un solo task attivo»

---

## Scopo

Rendere **provati** (JVM / Robolectric dove applicabile) e **comunicati in UX** i casi limite del percorso `sync_events` / quick sync: eventi troppo grandi; gap watermark / troppi batch; eventi senza ID utilizzabili; schema/RLS/RPC non disponibile; outbox locale pending; flag `manualFullSyncRequired`.

**Esito utente atteso:** quando la quick/delta sync **non basta** per garantire coerenza, l’app **lo dice** e **propone** la sync completa (allineato a controlli e copy già presenti su Options dopo TASK-059, estendendo la **propagazione dello stato** dove oggi manca).

---

## Non obiettivi / Non incluso

- Redesign completo della sync; nuova architettura cloud-first
- Modifiche schema Room / nuove migrazioni **senza** motivazione documentata nel task
- Riscrittura navigation / DAO pubblici **senza** necessità stretta
- Smoke live multi-device formale (**TASK-063**) salvo bug bloccante emerso in execution
- Dichiarare test device/emulator eseguiti se non lo sono (evidenza obbligatoria in `Execution`)

---

## Contesto (repo-grounded — sintesi planning 2026-04-25)

- **`InventoryRepository.drainSyncEventsInternal`**: imposta `manualFullSyncRequired` se `entityIds` assenti o vuoti con `changedCount > 0`, se `totalIds > SYNC_EVENT_ENTITY_ID_BUDGET` (250), o se si raggiunge `SYNC_EVENT_DRAIN_MAX_ITERATIONS` (20). Avanza comunque il watermark locale per non bloccare la coda.
- **`syncCatalogQuickWithEvents`**: push + `recordOrEnqueueSyncEvent` (RPC `record_sync_event`) + drain; outbox `sync_event_outbox` con retry; se capability false → fallback push-only con flag su summary.
- **`SupabaseSyncEventRemoteDataSource`**: tabella `sync_events`; `checkCapabilities` via `select`; RPC `record_sync_event`.
- **`CatalogSyncViewModel`**: `lastCatalogSyncSummary` aggiornato su **sync manuale** (`refreshCatalog` / `syncCatalogQuick`); `manualFullSyncRequired` alimenta `fullSyncRecommended`, badge e stringhe (TASK-059).
- **`CatalogAutoSyncCoordinator`**: `runPushCycle` (quick+events) e `runSyncEventDrainCycle` (solo drain) **non** aggiornano il ViewModel — rischio UX: fallback non visibile in Options finché l’utente non fa una sync manuale.
- **Test esistenti**: `DefaultInventoryRepositoryTest` (045: watermark, outbox, capability false, drain mirato); **mancano** casi espliciti tooLarge / gap iter / assert su `manualFullSyncRequired`; `CatalogSyncViewModelTest` **senza** assert su `fullSyncRecommended` + `manualFullSyncRequired`.

---

## File da leggere prima di EXECUTION

- `docs/MASTER-PLAN.md`, `CLAUDE.md`, `AGENTS.md`, `docs/CODEX-EXECUTION-PROTOCOL.md`
- `app/.../data/InventoryRepository.kt` — `syncCatalogQuickWithEvents`, `drainSyncEventsFromRemote`, `drainSyncEventsInternal`, `recordOrEnqueueSyncEvent`, `retrySyncEventOutbox`
- `app/.../data/SupabaseSyncEventRemoteDataSource.kt`
- `app/.../data/SyncEventModels.kt`
- `app/.../data/CatalogAutoSyncCoordinator.kt`
- `app/.../viewmodel/CatalogSyncViewModel.kt`
- `app/.../data/CatalogSyncStateTracker.kt`
- `app/.../ui/screens/OptionsScreen.kt` (CTA full/quick)
- Test: `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest`, `CatalogAutoSyncCoordinatorTest`

---

## File probabili da modificare in EXECUTION

| File | Motivo probabile |
|------|------------------|
| `InventoryRepository.kt` | Logica già presente; eventuale chiarimento semantica summary / log (minimo) |
| `CatalogSyncViewModel.kt` / `CatalogSyncStateTracker.kt` | Propagare esito drain/auto-quick verso `lastCatalogSyncSummary` o stato equivalente |
| `CatalogAutoSyncCoordinator.kt` | Log con `manualFullSyncRequired`, gap, tooLarge; eventuale notifica verso tracker/VM |
| `MerchandiseControlApplication.kt` | Wiring minimo se serve collegare coordinator → VM (valutare accoppiamento) |
| `strings.xml` (+ IT/EN/ES/ZH) | Solo se servono micro-copy distintivi per causa fallback |
| Test sopra citati | Nuovi casi limite + VM |

**Vincolo:** nessun cambio schema/DAO senza sezione Decisioni motivata.

---

## Planning (Claude) — Analisi

Il problema **tecnico** è duplice: (1) i branch limite nel repository sono già in gran parte implementati ma **sotto-testati**; (2) lo **stato UX** che dipende da `lastCatalogSyncSummary` **non** riflette i cicli automatici (drain realtime / push debounced), quindi l’utente può non vedere «serve full sync» nonostante il codice lo sappia.

Il problema **utente** è la fiducia: dopo quick/auto sync deve essere chiaro **quando** la copia locale potrebbe essere incompleta e **quale azione** (full sync) è appropriata, senza redesign.

### Piano di esecuzione (bozza)

1. Definire canale minimo (es. estensione `CatalogSyncStateTracker` o merge controllato in VM) per riflettere `CatalogSyncSummary` o almeno `{ manualFullSyncRequired, syncEventsGapDetected, syncEventsTooLarge, syncEventOutboxPending }` dopo **ogni** `syncCatalogQuickWithEvents` / `drainSyncEventsFromRemote` riuscito nel coordinator, senza doppia fonte di verità.
2. Aggiungere test repository per i tre casi limite drain (IDs oltre budget; gap `changedCount`+ids null/empty; max iterations).
3. Aggiungere test ViewModel: summary con `manualFullSyncRequired == true` → `fullSyncRecommended` e presenza hint/badge attesi.
4. Opzionale: arricchire log coordinator per supporto TASK-063 / debug.
5. Check AGENTS: `assembleDebug`, `lint`, baseline TASK-004 documentata.

### Rischi identificati

| Rischio | Mitigazione |
|---------|-------------|
| Accoppiamento Application ↔ ViewModel troppo stretto | Preferire tracker/flow con confine chiaro; evitare singleton «god» |
| Doppio aggiornamento summary manuale + auto | Unificare merge (es. «ultimo summary vince» con regole documentate) |
| Falsi positivi full sync | Allineare test al comportamento reale watermark (eventi saltati ma coda avanzata) |

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Test JVM documentano e verificano tooLarge, gap ID, e/o max iterations → flag attesi su `CatalogSyncSummary` | B / S | — |
| 2 | Dopo percorsi **automatici** rilevanti, l’utente vede in **Options** (o canale concordato) la stessa raccomandazione full sync quando `manualFullSyncRequired` (o equivalente) | M / S | — |
| 3 | Nessun accesso rete da composable; ViewModel/repository restano confini | S | — |
| 4 | `assembleDebug` e `lint` OK; nessun warning Kotlin nuovo non motivato | B / S | — |
| 5 | Baseline **TASK-004**: test rilevanti eseguiti e loggati in `Execution` | S | — |
| 6 | Nessuna dichiarazione di smoke live senza evidenza | — | — |

Legenda: B=Build, S=Static/unit, M=Manual.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Piano inizializzato senza modifica sorgenti Kotlin | Richiesta utente (solo planning) | 2026-04-25 |

---

## Execution

_(vuoto — non avviare finché TASK-060 non esce da `REVIEW` bloccante o non c’è transizione governance esplicita.)_

---

## Review

_(vuoto)_

---

## Fix

_(vuoto)_

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | — |
| Data chiusura | — |

---

## Riepilogo finale

_(post chiusura)_

---

## Handoff

- Riprendere da **Planning → Analisi** quando TASK-061 diventa task attivo.
- Ripetere lettura `InventoryRepository` drain e `CatalogSyncViewModel` summary prima del primo commit.
