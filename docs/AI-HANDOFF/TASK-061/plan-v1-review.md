# TASK-061 — Review plan-v1

Data review: 2026-04-26
Ruolo: Codex Planner Critic B

## Verdict

**APPROVE WITH CHANGES**

Il piano v1 e repo-grounded e rispetta la governance principale: TASK-060 e ancora bloccante e TASK-061 deve restare in planning. La direzione tecnica e corretta, ma prima di diventare piano esecutivo va irrigidita su tre punti: fonte di verita dello stato sync, semantica di clear/merge dei fallback full sync, e test automatici su coordinator/tracker/ViewModel.

## Governance

- `docs/MASTER-PLAN.md` indica **TASK-060** come task attivo in **REVIEW** e bloccante.
- `docs/TASKS/TASK-061-hardening-sync-events-fallback-full-sync.md` mantiene **TASK-061** in **PLANNING**.
- Il piano v1 segnala correttamente che **TASK-061 non puo passare a EXECUTION** finche TASK-060 non esce esplicitamente da REVIEW e il master plan non viene aggiornato.
- Nessuna execution, nessun cambio Kotlin/XML/Gradle e nessun passaggio di stato sono ammessi in questa fase.

## Top Problemi Del Plan

1. **`CatalogSyncStateTracker` rischia di diventare una seconda fonte di verita.**
   Il tracker oggi e un osservabile sottile per progress/single-flight (`isSyncing`, progress, `tryBegin`, `finish`) scritto dal ViewModel e letto dalla root UI. Il piano propone di aggiungere un `StateFlow<CatalogSyncSummary?>` app-scoped: puo funzionare, ma cosi come scritto cambia responsabilita al tracker e rischia di duplicare `lastCatalogSyncSummary` del ViewModel senza regole precise di ownership.

2. **Mancano regole esplicite di merge/clear dello stato fallback.**
   Il piano dice che il ViewModel dovrebbe osservare i summary automatici, ma non specifica abbastanza quando un `manualFullSyncRequired=true` deve essere cancellato. Servono regole per full refresh riuscito, logout/cambio utente, capability false, errori successivi e summary automatici vecchi.

3. **Il piano non separa abbastanza bene summary tecnico e UX surface.**
   `CatalogSyncViewModel` oggi usa `incrementalDetailSurface` per distinguere quick/manual refresh e messaggi. Un summary automatico del coordinator non dovrebbe far comparire copy da “quick sync manuale” se l'utente non ha lanciato una quick sync. Il piano deve distinguere meglio fonte `MANUAL_QUICK`, `MANUAL_FULL`, `AUTO_PUSH`, `AUTO_DRAIN`.

4. **Osservabilita `manualFullSyncRequired` ancora troppo opzionale.**
   Nel repository il log `logSyncEventSummary` include gap/too-large/outbox/watermark ma non espone direttamente `manualFullSyncRequired`. Nel coordinator i log di push/drain non includono chiaramente `manualFullSyncRequired`, `syncEventsGapDetected`, `syncEventsTooLarge` e `syncEventOutboxRetried`. Se il task e hardening, questo non va lasciato come “se utile”.

5. **Il test “max iterations” e poco concreto.**
   L'idea di usare 20 pagine piene di eventi self/controllati e giusta, ma va resa eseguibile. Gli eventi self richiedono un device id noto nello stato locale; altrimenti il test puo diventare fragile o avviare fetch mirati inutili.

6. **Rischio interferenza con TASK-060/TASK-063 non abbastanza esplicito.**
   TASK-060 riguarda refresh remoto puntuale in DatabaseScreen; TASK-063 sembra vicino a governance/sync live. TASK-061 non deve introdurre refresh globali, cambi schema Supabase, DAO Room o smoke live dichiarati senza prova.

## Migliorie Concrete Da Incorporare

- Definire una sola ownership per il summary:
  - opzione A: estendere `CatalogSyncStateTracker` con un outcome channel minimale e documentato;
  - opzione B: introdurre un piccolo tracker separato, ad esempio `CatalogSyncOutcomeTracker`, evitando di gonfiare il tracker di progress.

- Se si usa `CatalogSyncStateTracker`, il piano deve specificare una API minima, per esempio:
  - `publishSummary(summary, source, ownerUserId)`
  - `clearSummary(ownerUserId)`
  - `lastSummary: StateFlow<...>`
  con metadati sufficienti a evitare leakage tra utenti e summary obsoleti.

- Aggiungere una policy deterministica nel ViewModel:
  - manual quick e manual full aggiornano il summary osservabile;
  - full refresh riuscito senza richiesta fallback cancella `manualFullSyncRequired`;
  - logout/cambio user cancella o ignora summary di owner diverso;
  - summary automatico puo mostrare full sync recommended/outbox hint, ma non deve usare copy specifica della quick sync manuale;
  - errori successivi non devono cancellare silenziosamente una raccomandazione full sync ancora valida.

- Rendere obbligatorio l'hardening dei log:
  - repository: includere `manualFullSyncRequired` nel riepilogo sync_events;
  - coordinator push/drain: loggare `manualFullSyncRequired`, `syncEventsGapDetected`, `syncEventsTooLarge`, `syncEventOutboxRetried`, `syncEventOutboxPending`;
  - nessun log deve essere usato come sostituto dei test.

- Precisare che `OptionsScreen.kt` resta idealmente intoccato:
  - la UX deve passare da `CatalogSyncUiState`;
  - modificare `OptionsScreen` solo se uno stato richiesto non e rappresentabile con `statusBadges`, `catalogDetail`, `uploadHint`, `fullSyncRecommended`;
  - niente redesign della sezione cloud.

- Esplicitare i no-scope:
  - nessun cambio Room entity/DAO/schema per sync watermark/outbox;
  - nessuna migration;
  - nessun cambio RPC o DDL Supabase;
  - nessun live smoke TASK-062/TASK-063 dichiarato come prova di TASK-061.

## Test Mancanti

### Repository

- `drainSyncEventsFromRemote` con capability `syncEventsAvailable=false`: deve tornare summary disabled/fallback senza fetch remoti puntuali o full pull nascosto.
- `syncCatalogQuickWithEvents` con `recordSyncEventAvailable=false`: deve usare fallback push-only, preservare pending outbox e non chiamare RPC.
- Evento con `entityIds=null` e `changedCount>0`: deve settare `gapDetected=true` e `manualFullSyncRequired=true`, avanzando il watermark.
- Evento con `entityIds=[]` e `changedCount>0`: stesso comportamento gap/manual full.
- Controllo negativo: `entityIds=[]` e `changedCount=0` non deve generare falso positivo full sync.
- Evento con oltre `SYNC_EVENT_ENTITY_ID_BUDGET`: deve settare `tooLarge=true` e `manualFullSyncRequired=true`.
- Max iterations: preparare device id locale noto o eventi che non richiedono fetch mirati, poi verificare `maxIterationsReached=true`, `manualFullSyncRequired=true` e watermark coerente.
- Outbox: confermare che eventi pending/max-attempts restano visibili in summary e non vengono persi durante fallback.

### ViewModel

- Quick sync manuale con sync_events configurato e summary `manualFullSyncRequired=true`: `fullSyncRecommended=true`, badge/hint corretti.
- Summary automatico pubblicato dal coordinator con `manualFullSyncRequired=true`: Options deve raccomandare full sync senza avviare lavoro e senza copy da quick manuale.
- Summary automatico con `syncEventOutboxPending>0`: mostrare hint/badge di upload pendente.
- Full refresh manuale riuscito: cancella raccomandazione fallback precedente.
- Logout/cambio utente: non deve mostrare fallback/outbox di owner precedente.
- Capability false: non deve produrre loop di raccomandazioni se il sistema sta solo facendo push-only fallback.

### Coordinator / Tracker

- `runPushCycle` con `syncEventRemote` configurato usa `syncCatalogQuickWithEvents` e pubblica il summary.
- `runSyncEventDrainCycle` pubblica il summary del drain.
- Nessun summary pubblicato su skip per busy/manual sync o su failure.
- Log coordinator contiene manual/gap/too-large/outbox retried/pending.
- Tracker/outcome channel non rompe `tryBegin`, `finish`, progress e `isSyncing`.
- Eventuali summary con source/owner diverso sono ignorati o puliti secondo policy.

## Execution Traps

- Non trasformare `CatalogSyncStateTracker` in un mini store globale generico. Deve restare piccolo, o va creato un outcome tracker separato.
- Non avere due summary concorrenti (`ViewModel.lastCatalogSyncSummary` e tracker) senza una regola chiara di precedenza.
- Non far partire sync o refresh dal solo fatto che il ViewModel osserva un summary automatico.
- Non usare `OptionsScreen` per business logic: la schermata deve solo renderizzare `CatalogSyncUiState`.
- Non introdurre refresh globale del catalogo per risolvere il fallback; TASK-061 deve raccomandare/abilitare full sync, non nascondere il problema con pull impliciti.
- Non toccare schema Room, DAO watermark/outbox o Supabase RPC/DDL in questo task.
- Non indebolire test esistenti di `DefaultInventoryRepositoryTest`, `CatalogSyncViewModelTest` o `CatalogAutoSyncCoordinatorTest`.
- Non dichiarare smoke live, RLS o RPC reali verificati senza esecuzione reale e prove. In questo task i test devono essere fake/unitari.
- Non confondere capability false con errore temporaneo: capability false deve restare fallback controllato, non un segnale automatico di full sync sempre necessario.
- Non far trapelare summary/outbox tra utenti o store scope diversi.

## Conclusione

Il piano v1 e una buona base e puo essere approvato solo dopo queste correzioni. La modifica piu importante e rendere esplicita la strategia di stato condiviso tra coordinator, tracker e ViewModel: senza questa, il rischio principale non e la singola query sync_events, ma una UX che raccomanda full sync per motivi vecchi, duplicati o attribuiti al ciclo sbagliato.
