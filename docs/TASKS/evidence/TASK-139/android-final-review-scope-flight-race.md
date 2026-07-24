# TASK-139 Android — final review scope-flight race

Data: `2026-07-19`
Esito fix: `FIXED`
Fase handoff: `READY_FOR_REVIEW / REVIEW`, non `DONE`.

## Finding riprodotti prima del fix

### P0 — risposta cloud A dopo replace locale B

Scenario deterministico su Room in-memory:

1. binding e flight History sullo scope account/shop A;
2. `fetchAllSessionsForOwner` sospeso con `CompletableDeferred`;
3. replace esplicito e nuovo binding B;
4. rilascio della risposta A;
5. la risposta A veniva applicata nel DB ormai associato a B.

Comando corretto con SDK/JBR espliciti:

```text
./gradlew testDebugUnitTest \
  --tests 'com.example.merchandisecontrolsplitview.data.Task139BusinessDataScopeBindingTest.deferred history response from previous scope cannot contaminate replacement' \
  --no-daemon
```

Esito pre-fix reale: `BUILD FAILED`; atteso `history count = 0`, ottenuto `1`.
Il primo tentativo privo di `ANDROID_HOME` era terminato prima dei test con
`SDK location not found` ed è stato ripetuto correttamente; non è contato come
evidence funzionale.

### P1 — ShopContext A pubblicato dopo switch auth B

Scenario deterministico:

1. `refresh(A)` sospeso nel fetch linked-shops;
2. owner auth corrente portato a B;
3. rilascio della risposta A;
4. il repository pubblicava ancora `owner=A/shop=A`.

Esito pre-fix reale del test deferred: `BUILD FAILED`; atteso owner B, ottenuto
owner A. La variante finale non avvia artificialmente `refresh(B)` prima di
rilasciare A, quindi riproduce anche il collector auth sequenziale reale.

## Correzione applicata

- Lease process-local con `generation`, scope owner/shop e propagazione nel
  `CoroutineContext` per catalogo, History, manual sync e realtime.
- Transizione esclusiva: chiusura atomica delle nuove admission, incremento
  generation, cancel e join dei flight attivi, poi resolve/discard/replace Room;
  le admission riaprono solo dopo la pubblicazione dello stato finale.
- Ogni transport catalogo/prezzi/History/sync-event del repository passa da un
  wrapper con verifica lease prima e dopo l'await; una cancellation catturata
  dentro `Result.failure` viene rilanciata.
- Ogni apply/bookkeeping Room conseguente a una risposta cloud verifica la lease
  subito prima della scrittura o come prima istruzione della transazione.
- Coordinator auto catalogo, auto History, manual ViewModel, realtime e smoke
  debug TASK-087 registrano il flight; lo scope invalidato non ripristina hint,
  non programma retry vecchi e non pubblica success/failure dello scope A.
- Il record `sync_event` History non usa più `NonCancellable`: verifica la lease
  prima/dopo rete e prima dell'eventuale insert outbox.
- `ShopContextRepository` usa generation e callback owner auth corrente; una
  risposta stale non modifica stato terminale né preferenze. L'Application
  richiede sempre `context.ownerUserId == auth.userId` prima di align/activate,
  discard o replace.
- L'allineamento iniziale valida direttamente lo stato `READY` appena risolto
  mentre la transition tiene intenzionalmente chiuso il gate.
- Realtime conserva owner/shop d'origine e un receipt generazionale: il canale
  è owner-filtered, valida shop/subscription generation, usa un topic unico e
  rimuove il vecchio canale. Il drain scarta receipt stale anche dopo ABA
  A→B→A e i log non emettono il `remoteId` raw.
- I preflight device-status di auto catalogo, History e manuale sono flight
  registrati; cache e callback sono validate dopo l'await. Una cancellation del
  preflight manuale libera sempre l'owner `MANUAL`.
- La transition resta admission-closed fino alla quiescenza anche se il caller
  viene cancellato e rifiuta fail-fast una transition annidata nel proprio
  flight.
- Refresh ShopContext da owner stale non incrementa la generation corrente; la
  selezione shop è rifiutata durante loading senza lasciare loading permanente.

## Test deferred post-fix

- risposta History A non cooperativa (`NonCancellable`) mentre la transition B
  attende: al rilascio A viene scartata, binding finale B, zero History A e zero
  follow-up outbound;
- transition con flight A sospeso: nessuna nuova admission/outbound durante la
  barriera;
- cancellazione del caller della transition durante un flight non cooperativo:
  admission chiusa fino al rilascio, poi pubblicazione coerente di B;
- receipt Realtime A attraverso A→B→A: stale per generation e zero apply Room;
  payload proveniente da un altro shop rifiutato all'admission;
- cambio auth A→B senza `refresh(B)` concorrente: risposta A ignorata e zero
  mutation preferenze A; il successivo refresh B pubblica solo shop B;
- due refresh concorrenti A/B: la generation più recente B resta terminale;
- refresh stale A mentre B è loading e selezione shop durante loading non
  invalidano/troncano il refresh B;
- cambio scope durante il device preflight manuale: zero remote catalogo e
  owner `MANUAL` nuovamente acquisibile.

## Gate reali post-fix

| Gate | Esito |
|---|---|
| Test mirati coordinator/ViewModel/Application/scope binding/ShopContext | `124/124 PASS`, `0` skip/failure/error; `BUILD SUCCESSFUL in 23s` |
| JVM completo `testDebugUnitTest` | `687` totali: `682 PASS`, `5` skip opt-in/live, `0` failure/error; `BUILD SUCCESSFUL in 45s` |
| `assembleDebug assembleDebugAndroidTest lintDebug` | PASS; `BUILD SUCCESSFUL in 1m 11s`; lint `0` errori, `22` warning globali |
| Emulator API 35, `ProductImageDeviceTest` | `3/3 PASS`; `BUILD SUCCESSFUL in 11s` |
| `git diff --check` | PASS |
| Secret/generated-artifact scan scoped | PASS; nessun secret o artefatto compilato aggiunto |

La suite instrumentation globale non è dichiarata PASS: ha eseguito
`connectedDebugAndroidTest` ma due harness live storici, TASK-072C e TASK-072D,
falliscono intenzionalmente senza i rispettivi `task072*RunPrefix` espliciti.
Il sottoinsieme locale TASK-139 sopra è verde; nessun prefisso live, credenziale
o backend è stato inventato per aggirare quel gate.

## File del fix e rischi residui

Runtime: `MainActivity.kt`, `MerchandiseControlApplication.kt`,
`CatalogAutoSyncCoordinator.kt`, `CatalogSyncStateTracker.kt`,
`HistorySessionPushCoordinator.kt`, `InventoryRepository.kt`,
`RealtimeRefreshCoordinator.kt`, `RemoteSignal.kt`,
`SupabaseRealtimeSessionSubscriber.kt`, `ShopContext.kt`,
`ShopDeviceRegistrationRemoteDataSource.kt`,
`Task126BusinessDataScopeRuntimeGuard.kt`, `CatalogSyncViewModel.kt`.

Test: `Task126BusinessDataScopeRuntimeGuardTest.kt`,
`Task139BusinessDataScopeBindingTest.kt`, `ShopContextTest.kt`,
`RealtimeRefreshCoordinatorTest.kt`, `CatalogAutoSyncCoordinatorTest.kt`,
`HistorySessionPushCoordinatorTest.kt`, `CatalogSyncViewModelTest.kt` e
`MerchandiseControlApplicationTest.kt`.

Rischi/gap non trasformati in PASS: harness live TASK-072C/D senza input
espliciti, device fisico non eseguito e blocker cross-platform/iOS già tracciati.
Nessun commit, stage, push, merge, deploy, secret o operazione distruttiva su DB
reale è stato eseguito.
