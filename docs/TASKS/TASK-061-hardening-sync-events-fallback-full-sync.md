# TASK-061 — Hardening `sync_events` e fallback full sync

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-061 |
| Stato | `DONE` |
| Priorità | `MEDIA` / `ALTA` (hardening) |
| Area | Supabase `sync_events` / repository / coordinator / ViewModel / test JVM / fallback UX |
| Creato | 2026-04-25 |
| Ultimo aggiornamento | 2026-04-26 |

**Governance:** il 2026-04-26 l'utente ha deciso esplicitamente di sospendere **TASK-060** (`BLOCKED` / sospeso, non `DONE`) e attivare **TASK-061**. Execution, review e fix opzionali completati; su conferma utente del 2026-04-26 TASK-061 e' chiuso in `DONE`.

---

## Dipendenze

- **TASK-055** `PARTIAL` — contesto audit sync Supabase / UX
- **TASK-060** `BLOCKED` / sospeso (decisione esplicita utente 2026-04-26) — review non chiusa, ma non piu' bloccante per TASK-061

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

### Esecuzione — 2026-04-26

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt` — aggiunto canale minimo `lastOutcome` per pubblicare l'ultimo `CatalogSyncSummary` concluso da manual/auto/drain, filtrabile per owner.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt` — i cicli automatici `syncCatalogQuickWithEvents` e `drainSyncEventsFromRemote` pubblicano il summary riuscito nel tracker e loggano `manualFullSyncRequired`, gap, tooLarge e outbox.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt` — la UI usa un summary unico derivato dal tracker quando presente, filtrato per utente; i percorsi manuali pubblicano nello stesso canale, evitando una doppia fonte di verita.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — il log `sync_events` include anche `manualFullSyncRequired`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunti casi limite `sync_events`: ids null/empty, zero changed count, budget superato, max iterations, capability false, RPC `record_sync_event` non disponibile.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt` — aggiunti test su pubblicazione summary auto/drain, log fallback e nessuna pubblicazione su errore.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt` — aggiunti test su `fullSyncRecommended`, outbox hint, owner filtering e reset tramite full refresh.
- `docs/MASTER-PLAN.md`, `docs/TASKS/TASK-060-pull-remoto-refresh-puntuale-databasescreen.md`, `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md` — governance: TASK-060 sospesa/BLOCKED; TASK-061 portata a EXECUTION, poi REVIEW, e infine DONE su conferma utente.
- `docs/AI-HANDOFF/TASK-061/execution-blocked.md` — marcato come superseded dopo override esplicito utente.

**Azioni eseguite:**
1. Verificata governance dopo override esplicito utente: TASK-060 sospesa/BLOCKED, TASK-061 autorizzata a EXECUTION.
2. Implementato un canale tracker minimale per propagare `CatalogSyncSummary` dai cicli automatici senza collegare direttamente coordinator e ViewModel.
3. Collegato `CatalogSyncViewModel` al summary del tracker con filtro `ownerUserId`; quando non c'e' tracker resta attivo il comportamento locale precedente.
4. Pubblicati i summary dei cicli automatici solo dopo successi effettivi; su failure il tracker non viene aggiornato.
5. Nessun cambio schema Room/DAO, nessuna migrazione, nessuna modifica Gradle, nessun redesign `OptionsScreen`.
6. UI/UX: nessun composable nuovo; la raccomandazione full sync esistente in Options riceve ora anche gli outcome automatici via ViewModel/tracker (motivo: chiarezza senza redesign).

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → BUILD SUCCESSFUL |
| Lint | ✅ ESEGUITO | `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintDebug` → BUILD SUCCESSFUL |
| Warning nuovi | ✅ ESEGUITO | Nessun warning nuovo dal codice modificato; restano warning preesistenti Gradle/AGP e Compose in file non toccati |
| Coerenza con planning | ✅ ESEGUITO | Implementati test edge `sync_events`, propagazione auto summary tramite tracker, fallback UX via stato esistente |
| Criteri di accettazione | ✅ ESEGUITO | Vedi dettaglio sotto |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" ./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest" --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` → BUILD SUCCESSFUL.
  - `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" ./gradlew --no-daemon testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` → BUILD SUCCESSFUL.
  - `git diff --check` → OK.
- Test aggiunti/aggiornati:
  - `DefaultInventoryRepositoryTest`: `manualFullSyncRequired` su gap ids null/empty, no false positive con `changedCount=0`, tooLarge, max iterations, capability false, RPC record fallback.
  - `CatalogAutoSyncCoordinatorTest`: publish outcome auto/drain, log fallback, no publish on failure.
  - `CatalogSyncViewModelTest`: manual/auto fallback full sync, outbox pending hint, owner filtering, full refresh reset.
- Limiti residui:
  - Smoke live multi-device non eseguito e non dichiarato; resta per TASK-063.
  - Il comando multi-classe combinato con `CatalogSyncViewModelTest` dopo le suite repository/coordinator fallisce in questo ambiente per attach MockK/ByteBuddy (`AttachNotSupportedException`); le stesse classi mirate eseguite separatamente sono verdi.

**Dettaglio criteri di accettazione:**
| # | Verifica | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | Test repository su tooLarge, gap ID e max iterations con flag attesi | ESEGUITO | `DefaultInventoryRepositoryTest` verde con casi TASK-061 |
| 2 | Percorsi automatici rendono visibile fallback full sync nella UX esistente | ESEGUITO | `CatalogAutoSyncCoordinator` pubblica nel tracker; `CatalogSyncViewModelTest` verifica `fullSyncRecommended` e outbox hint senza toccare `OptionsScreen` |
| 3 | Nessun accesso rete da composable; VM/repository restano confini | ESEGUITO | Nessun composable modificato; rete invariata dietro repository/data source |
| 4 | `assembleDebug` e `lintDebug` OK; nessun warning nuovo | ESEGUITO | Build/lint verdi; warning solo preesistenti |
| 5 | Baseline TASK-004 eseguita | ESEGUITO | Test repository/coordinator/ViewModel mirati verdi come sopra |
| 6 | Nessuna dichiarazione smoke live senza evidenza | ESEGUITO | Smoke live non eseguito e documentato come residuo TASK-063 |

**Incertezze:**
- INCERTEZZA: la validazione live multi-device dei fallback automatici resta fuori scope e va coperta in TASK-063.

**Handoff notes:**
- Execution lasciata intenzionalmente in `REVIEW`; chiusura `DONE` applicata solo dopo review APPROVED e conferma utente del 2026-04-26.
- Reviewer: controllare soprattutto owner filtering del tracker, assenza di doppia fonte di verita tra `lastCatalogSyncSummary` e `lastOutcome`, e correttezza del segnale `manualFullSyncRequired` nei test repository.

---

## Review

Verdict finale post-fix: `APPROVED`. TASK-061 puo' essere chiuso in `DONE` su conferma utente.

---

## Fix

### Fix — 2026-04-26 (opzionali review)

**File modificati:**
- `docs/AI-HANDOFF/TASK-061/plan-final.md` — aggiunta nota esplicita `superseded by user override` sul gate storico "Execution blocked by governance".
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModelTest.kt` — aggiunto test signed-out dopo `tracker.publishSummary(...)` per garantire che Options non mostri summary/outbox/full-sync-required di un owner precedente.
- `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md` — documentato follow-up non bloccante su eventuale timestamp/clear di `CatalogSyncStateTracker.lastOutcome`.

**Azioni eseguite:**
1. Chiarita la lettura futura di `plan-final.md`: il blocco governance originario resta storico, ma e' stato superato dall'override utente gia documentato in Master Plan e TASK-061.
2. Coperto il contratto signed-out citato nel piano: dopo un outcome automatico pubblicato, il passaggio a `AuthState.SignedOut` nasconde raccomandazione full sync, detail e badge.
3. Valutazione timestamp/clear: non introdotti ora per mantenere il canale tracker minimale; se TASK-063 o un task successivo richiede distinguere "outcome corrente" da "outcome vecchio", aggiungere `updatedAtMs` e/o `clearSummary(ownerUserId)` con test dedicati.

**Check fix:**
| Check | Stato | Note |
|-------|-------|------|
| Test ViewModel mirato | ✅ ESEGUITO | `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading" ./gradlew --no-daemon testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` → BUILD SUCCESSFUL |
| `assembleDebug` | ✅ ESEGUITO | `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → BUILD SUCCESSFUL |
| `lintDebug` | ✅ ESEGUITO | `ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintDebug` → BUILD SUCCESSFUL |
| `git diff --check` | ✅ ESEGUITO | OK |

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Data chiusura | 2026-04-26 |

---

## Riepilogo finale

TASK-061 chiuso in `DONE` il 2026-04-26 su conferma utente.

**Sintesi finale:**
- Introdotto tracker minimale owner-scoped per l'ultimo `CatalogSyncSummary` rilevante da sync manuale, auto-push e drain `sync_events`.
- `CatalogAutoSyncCoordinator` propaga i summary riusciti e rende visibile `manualFullSyncRequired` dai cicli automatici, con log aggiuntivi per gap, tooLarge e outbox.
- `CatalogSyncViewModel` usa il summary owner-scoped del tracker quando disponibile e mantiene il fallback locale solo per istanze senza tracker.
- Aggiunti test repository, ViewModel e coordinator su `sync_events`, `manualFullSyncRequired`, capability false, outbox, owner filtering e signed-out.
- Nessun redesign sync / OptionsScreen.
- Nessun cambio schema Room, DAO o Gradle.

**Check eseguiti e documentati:**
- `DefaultInventoryRepositoryTest` + `CatalogAutoSyncCoordinatorTest` mirati: pass.
- `CatalogSyncViewModelTest` mirato con `--no-daemon`: pass.
- `assembleDebug`: pass.
- `lintDebug`: pass.
- `git diff --check`: pass.
- Nota: il batch combinato multi-classe con `CatalogSyncViewModelTest` resta fragile in questo ambiente per attach MockK/ByteBuddy (`AttachNotSupportedException`); non bloccante per la chiusura perche le suite mirate sono verdi.

---

## Handoff

- TASK-061 chiuso in `DONE` su conferma utente del 2026-04-26.
- TASK-060 resta `BLOCKED` / sospeso, non `DONE`.
- TASK-055 resta `PARTIAL` finche TASK-062/TASK-063 o una decisione futura non lo chiudono.
- Nessun nuovo task attivato automaticamente; prossimo passo da scegliere: TASK-062 o TASK-063.
