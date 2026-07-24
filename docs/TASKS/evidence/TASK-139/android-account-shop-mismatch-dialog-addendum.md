# TASK-139 — Addendum UX account/shop mismatch Android

Data: `2026-07-19`

Stato handoff: `READY_FOR_REVIEW / REVIEW`, non `DONE`.

## Perimetro autorizzato

L'utente ha esteso l'esecuzione Android alla sola patch UI account/shop
mismatch. Per questo punto la lane non è più `review-only`. Non sono stati
modificati sync policy TASK-126, coordinator, gate owner/shop, repository,
schema o transazioni Room. Nessuna dipendenza è stata aggiunta.

## Implementazione

- `BusinessScopeMismatchChoiceDialog` usa la `AlertDialog` Material 3 nativa.
- Copy italiana esatta: `Scegli quali dati usare`,
  `Quali dati vuoi mantenere?`, `Mantieni dati locali`,
  `Sostituisci con dati cloud`.
- La dialog contiene un solo `confirmButton` distruttivo e un solo
  `dismissButton` secondario; la seconda confirmation mismatch precedente è
  rimossa. Il percorso discard unbound resta separato e invariato.
- Back, dismiss esterno e keep chiudono la dialog senza invocare callback
  business. `Rivedi` la riapre manualmente.
- Una fingerprint SHA-256 di stato/scope, priva di account/shop raw, viene
  registrata prima della presentazione: la stessa identità non riapre
  automaticamente la dialog dopo recomposition, relaunch o reconnect; una
  nuova identità può essere presentata una volta. La cronologia resta bounded
  a 64; raggiunto il cap, un marker persistito disabilita soltanto i futuri
  auto-show invece di espellere un hash e consentirgli di riapparire. `Rivedi`
  continua a funzionare manualmente.
- Il replace è abilitato soltanto con `SignedIn` non vuoto, owner coerente,
  `ShopContext` non in caricamento, sync consentita, shop remoto corrente non
  vuoto e snapshot locale verificato. Nessun default store/shop è accettato.
- Il tap distruttivo chiama esclusivamente
  `replaceMismatchedLocalBusinessDataAndBind()`. Il percorso esistente esegue
  quiescenza/cancel-join, transazione Room owner-safe e, solo dopo `READY`,
  riattiva catalog/history per bootstrap, pull e reconcile.

## Finding P1 chiuso — mutation immagini durante cambio scope

L'audit finale ha dimostrato un P1 nel boundary immagini preesistente:
`upload()` e `remove()` verificavano il gate all'ingresso e prima dell'apply
locale, ma non registravano l'intera mutation nella lease generazionale. Un
gateway non cooperativo poteva quindi riprendere intent/PUT/finalize/remove A
mentre account/shop entravano in discovery o una transition preparava B.

Fix minimo applicato senza nuova state machine:

- `ProductImageService` riceve il `Task126BusinessDataScopeRuntimeGuard`
  esistente; l'Application passa lo stesso `CatalogSyncStateTracker` usato dagli
  altri flight;
- preprocessing, intent, ogni PUT e relativo retry, finalize, remove e apply
  locale sono racchiusi nello stesso `withBusinessDataScopeFlight`;
- lease e provider account/shop vengono ricontrollati prima e dopo ogni
  boundary; la `CancellationException` di invalidazione non viene convertita in
  errore retryable;
- `purgeScope` resta intenzionalmente fuori dal flight, perché è cleanup
  owner/shop-scoped del vecchio contesto;
- `DatabaseViewModel` ripristina lo snapshot UI precedente se remove viene
  cancellato con generation ancora corrente, evitando uno stato `REMOVING`
  permanente durante discovery; se la generation è già cambiata, non ripubblica
  stato A.

Nessun coordinator, policy, repository, schema o transazione Room è stato
modificato dal fix P1.

File focali dell'addendum:

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/BusinessScopeMismatchDialog.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageService.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`;
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`;
- `app/src/main/res/values*/strings.xml`;
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/screens/BusinessScopeMismatchDialogTest.kt`;
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreenPublicUxTest.kt`;
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/ui/screens/BusinessScopeMismatchDialogDeviceTest.kt`;
- `app/src/test/java/com/example/merchandisecontrolsplitview/productimage/ProductImageServiceTest.kt`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt`.

## Test e gate reali

SDK e JBR sono stati passati esplicitamente ai comandi Gradle. Il primo tentativo
senza SDK esplicito aveva restituito realmente `SDK location not found`; dopo
lo stop/clean della cache incrementale, tutti i gate seguenti sono stati
eseguiti in serie.

Il primo run combinato Service/ViewModel del fix P1 ha restituito un compile
failure reale per l'import test mancante di
`Task126BusinessDataScopeChangedException`; aggiunto l'import, il rerun identico
ha prodotto `69/69 PASS`. Il failure intermedio non è contato come PASS.

| Gate | Esito osservato |
|---|---|
| JVM mirati: dialog/Options/binding/Product Images/catalog/history/realtime/ViewModel | `182/182 PASS`, 0 skipped/failure/error; `BUILD SUCCESSFUL in 33s` |
| Product Image Service + recovery ViewModel P1 | `69/69 PASS`, 0 skipped/failure/error; `BUILD SUCCESSFUL in 39s` |
| `testDebugUnitTest` completo | `697` totali: `692 PASS`, `5` opt-in/live skipped, 0 failure/error; `BUILD SUCCESSFUL in 45s` |
| `assembleDebug assembleDebugAndroidTest` | PASS; `BUILD SUCCESSFUL in 15s` |
| `lint` | PASS; 0 errori, 22 warning globali, 0 match sui file scoped; `BUILD SUCCESSFUL in 48s` |
| `BusinessScopeMismatchDialogDeviceTest` | `4/4 PASS`, `OK (4 tests)`, AVD effimero API 35 |
| `git diff --check` | PASS |

Copertura osservata:

- account mismatch e shop mismatch mostrano la stessa dialog a due azioni;
- esattamente due `TextButton`, nessuna seconda confirmation;
- keep e back chiudono con zero chiamate replace, e la CTA manuale riapre;
- replace parte una sola volta dopo il tap e resta disabilitato senza shop
  verificato;
- la fingerprint è stabile su reconnect/relaunch e non genera loop;
- fixture Room in-memory verifica preservazione di binding/dati/outbox in
  mismatch, commit atomico del replace, rollback completo e device identity
  invariata;
- i coordinator bloccati producono zero bootstrap/push/drain/history/realtime;
- immagini in scope bloccato producono zero preprocessing, intent, upload,
  finalize e remove;
- cambio scope durante create-intent o PUT main non avvia PUT successive né
  finalize; transition durante finalize/remove resta in attesa del gateway
  non cooperativo e, dopo il rilascio, non applica riferimenti Room/cache stale;
- la cancellazione remove ripristina lo stato UI precedente e libera il job,
  provato da un secondo tentativo successivo nello stesso test;
- dopo replace `READY`, il percorso esistente attiva automaticamente catalog e
  history, coprendo bootstrap/pull/reconcile.

## Sicurezza runtime

L'unico device preesistente era `emulator-5554`, autenticato e contenente il DB
di acceptance: non è stato usato. È stato creato da configurazione vuota l'AVD
temporaneo `Task139DialogIsolated` su `emulator-5560`; APK app/test sono stati
installati solo con `adb -s emulator-5560`, quindi è stata eseguita unicamente
la classe UI sintetica sopra. L'AVD è stato arrestato subito dopo e `adb devices`
ha nuovamente mostrato soltanto `emulator-5554`.

Nessun uninstall/clear-data/reinstall sul device reale, logout, replace,
discard, remove immagine, drain outbox o mutazione business reale è stato
eseguito. La directory dell'AVD effimero non conteneva dati copiati dall'AVD
reale ed è stata spostata in modo recuperabile da `/tmp` al Cestino dopo aver
verificato che `emulator-5560` fosse arrestato.

## Handoff

Acceptance dell'addendum Android coperta dai gate sopra. Rischi residui:
nessun replace è stato intenzionalmente eseguito sul database autenticato reale;
l'atomicità e il rollback restano provati sulla fixture Room isolata. La fase
torna a `READY_FOR_REVIEW / REVIEW`, mai `DONE` senza conferma utente.

## Follow-up review coordinata — 2026-07-22

Una review indipendente ha rilevato che la precedente eviction deterministica
dei 64 hash poteva, dopo molte identità distinte, consentire un secondo
auto-show della stessa identità. Il marker bounded descritto sopra chiude il
loop senza aggiungere azioni alla dialog e senza toccare il callback business.
Il targeted corrente `BusinessScopeMismatchDialogTest` e' `5/5 PASS`, 0
failure/error/skip. Emulator e database autenticato non sono stati usati e il
numero di replace resta `0` in questa iterazione.
