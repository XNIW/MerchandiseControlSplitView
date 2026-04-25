# TASK-056 — Fix spinner post conferma import review

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-056 |
| Stato | PARTIAL |
| Priorita | ALTA |
| Area | Import Excel / Revisione Importazione / DatabaseViewModel / Navigation |
| Creato | 2026-04-24 |
| Ultimo aggiornamento | 2026-04-24 — patch minima `NavGraph` applicata e rifinita per contesto `Generated`; audit offline-first esteso a catalogo/history; remediation Supabase live RLS/security/performance applicata; anon REST `shared_sheet_sessions` non espone piu righe; build/lint/test mirati verdi; suite completa bloccata da ByteBuddy attach; smoke manuali UI/reconnect ancora necessari |

---

## Dipendenze

- **TASK-026** `DONE`: preview import side-effect-free e apply atomico.
- **TASK-044** `DONE`: history senza entry tecniche `APPLY_IMPORT_*`.
- **TASK-055** `PARTIAL`: audit sync Supabase; non blocca questo fix, ma conferma il vincolo Room-first/offline-first.

---

## Scopo

Risolvere il blocco UI dopo pressione di **Conferma Importazione** nella schermata **Revisione Importazione** quando l'apply locale termina con successo. Il fix futuro deve essere minimo e mirato al completamento post-import: stato UI, evento di successo, navigazione/reset preview e gestione del fallback quando la preview non e piu disponibile.

---

## Obiettivo tecnico

Garantire che, dopo `APPLY_IMPORT SUCCESS`, la schermata non rimanga mai su bianco con spinner centrale. La UX deve completare in modo deterministico: reset dello stato di applying/loading, feedback chiaro all'utente e navigazione/ritorno coerente alla destinazione prevista senza aspettare eventuale sync remoto.

---

## Perche serve

Bug osservato in runtime reale:

- Flusso: file Excel caricato -> schermata **Revisione Importazione** -> **Conferma Importazione**.
- Stato anteprima osservato: **Nuovi prodotti da aggiungere: 0**, **Prodotti da aggiornare: 1**, **Errori trovati: 0**.
- Dopo conferma: schermata bianca con solo spinner viola al centro.
- Logcat rilevante:

```text
DB_IMPORT APPLY_IMPORT START previewId=4 new=0 updated=1
DB_IMPORT APPLY_IMPORT SUCCESS previewId=4
```

Il database apply sembra completare correttamente. Il problema e quindi nel percorso post-success: stato UI, branch loading, navigation, reset preview/import analysis o completion event.

---

## Contesto / bug osservato

### Stato attuale osservato dal codice

Fonti lette per questa pianificazione:

- `AGENTS.md`
- `docs/MASTER-PLAN.md`
- `docs/TASKS/_TEMPLATE.md`
- `docs/TASKS/TASK-055-audit-sync-supabase-ux-scroll-database-history.md`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/Screen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/ImportNavOrigin.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportApplyModels.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportAnalysis.kt`
- test esistenti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `ImportNavOriginTest`

Audit sintetico:

1. **Entry point pulsante**
   - `ImportAnalysisScreen` contiene il pulsante **Conferma Importazione**.
   - Il pulsante chiama `onConfirm(previewId, editableNewProducts, editableUpdatedProducts)`.
   - `NavGraph` passa `onConfirm = { previewId, newProducts, updatedProducts -> dbViewModel.importProducts(previewId, newProducts, updatedProducts, context) }`.
   - `DatabaseViewModel.importProducts(...)` valida `previewId`, imposta `ImportFlowState.Applying(previewId)` e `UiState.Loading(...)`, poi lancia una coroutine in `viewModelScope`.

2. **Apply DB**
   - `DatabaseViewModel.importProducts(...)` chiama `repository.applyImport(importRequest)` su `Dispatchers.IO`.
   - `DefaultInventoryRepository.applyImport(...)` usa `applyImportMutex`, `db.withTransaction { applyImportAtomically(request) }`, ritorna `ImportApplyResult.Success` oppure `Failure`, e sblocca il mutex in `finally`.
   - `applyImportAtomically(...)` inserisce/aggiorna prodotti, registra price history e marca il catalogo dirty per cloud con `markEntireCatalogDirtyForCloud()`.
   - Dalla lettura attuale non risulta una chiamata diretta a `onProductCatalogChanged` dentro `applyImportAtomically(...)`; eventuale sync non deve comunque bloccare la UX locale.

3. **Stato dopo `APPLY_IMPORT SUCCESS`**
   - Su `ImportApplyResult.Success`, `DatabaseViewModel` logga `APPLY_IMPORT SUCCESS`, emette `ImportFlowState.Success(previewId)` e poi `UiState.Success(import_success)`.
   - Non esiste un evento one-shot dedicato a navigazione/snackbar del completamento import.
   - `ImportAnalysisScreen` non osserva direttamente `UiState.Success`; la completion dipende da `NavGraph` e da `ImportFlowState.Success`.

4. **Navigation post-success**
   - In `NavGraph`, la schermata import osserva `importFlowState`.
   - Quando vede `ImportFlowState.Success`, imposta `successNavigationRequested = true`, `fallbackNavigationRequested = true`, chiama `navigateToImportSuccessDestination(...)` e poi `dbViewModel.dismissImportPreview()`.
   - `dismissImportPreview()` cancella `importAnalysisResult`, resetta pending preview e mette `ImportFlowState.Idle`.
   - Se la route corrente resta su `ImportAnalysis` anche solo per un caso di navigazione fallita/non completata, il composable rientra nel branch `analysis == null`, che mostra un `CircularProgressIndicator` full-screen.
   - Poiche nel success path `fallbackNavigationRequested` e gia stato impostato a `true`, il branch `analysis == null` puo non eseguire piu il fallback navigation. Questo combacia con il sintomo: apply DB riuscito, poi spinner bianco infinito.

5. **UI state**
   - `ImportAnalysisScreen` usa `isApplying = importFlowState is ImportFlowState.Applying`.
   - Il pulsante e disabilitato durante applying e riabilitato su `Error` con stesso `previewId`.
   - Gli errori apply sono mostrati nel contenuto tramite `ImportFlowState.Error`.
   - Il loading full-screen in `NavGraph` viene usato come fallback quando `analysis == null`, ma questo stato puo rappresentare anche "preview gia pulita dopo success" e non solo "sto caricando preview".

6. **Sync / Supabase**
   - `MerchandiseControlApplication` collega `repository.onProductCatalogChanged` a `CatalogAutoSyncCoordinator.onLocalProductChanged`.
   - Nel path `applyImportAtomically(...)` letto qui si vede dirty marking dell'intero catalogo, non una dipendenza UI diretta dal sync remoto.
   - La futura patch non deve trasformarsi in refactor sync: l'import locale riuscito deve chiudere la UX anche se un eventuale auto-sync continua o fallisce in background.

---

## File Android da analizzare / potenzialmente toccare

| File | Motivo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` | Entry point pulsante, stato `isApplying`, error display, abilitazione retry. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` | Success navigation, branch `analysis == null`, reset preview, fallback route. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/Screen.kt` | Route `ImportAnalysis` e argomento origin. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/ImportNavOrigin.kt` | Destinazioni post-import: HOME, HISTORY, DATABASE, GENERATED. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` | `ImportFlowState`, `importProducts`, `dismissImportPreview`, `clearImportAnalysis`, `recoverImportPreviewAfterApplyError`. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` | Mark generated entry synced/error dopo import proveniente da Generated/History. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` | Baseline apply atomico e dirty marking; da non cambiare salvo evidenza necessaria. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportApplyModels.kt` | Contratto `ImportApplyRequest` / `ImportApplyResult`. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportAnalysis.kt` | Preview/result model. |
| `app/src/main/res/values*/strings.xml` | Eventuale copy di successo/errore se manca feedback localizzato. |
| `app/src/test/java/.../viewmodel/DatabaseViewModelTest.kt` | Test esistenti su success/error/double confirm/clear during apply. |
| `app/src/test/java/.../ui/navigation/ImportNavOriginTest.kt` | Test navigation origin; verificare se estendere o lasciare invariato. |

---

## Scope incluso

- Fix mirato del completamento post import dalla schermata **Revisione Importazione**.
- Separazione chiara tra:
  - loading preview;
  - preview ready;
  - applying import;
  - apply success;
  - apply error.
- Gestione deterministica di `APPLY_IMPORT SUCCESS` senza spinner infinito.
- Feedback utente chiaro dopo import riuscito: snackbar/toast/dialog/card di successo oppure navigazione coerente alla schermata prevista.
- Reset stato applying/loading su success, error e cancellation.
- Gestione corretta di `importAnalysisResult == null` senza usarlo come spinner infinito generico.
- Eventuale evento one-shot per successo/navigazione/snackbar se serve a evitare perdita di evento in recomposition.
- Verifica che eventuale auto-sync/Supabase non blocchi la chiusura della UX import locale.
- Test ViewModel/navigation mirati se il fix modifica stato o completion event.

---

## Scope escluso

- Nessuna patch funzionale in questa fase di planning senza conferma utente.
- Nessun cambio schema Room.
- Nessuna migration Room.
- Nessuna modifica Supabase live, RLS, Edge Functions o migration remota.
- Nessun cloud-first: Room resta source of truth locale.
- Nessun refactor ampio di repository, DAO, parser Excel o sync cloud.
- Nessuna modifica a import/export Excel non necessaria al bug post-success.
- Nessuna modifica fuori scope a database CRUD, barcode scan, manual entry, generated flow, history flow o navigation generale.
- Nessuna business logic nei composable.
- Nessuna nuova dipendenza.

---

## Ipotesi root cause da verificare

1. **Ipotesi principale:** dopo `ImportFlowState.Success`, `NavGraph` chiama navigation e subito `dismissImportPreview()`. Se la navigation non completa o la route resta su `ImportAnalysis`, `analysis == null` mostra spinner full-screen; il fallback non riparte perche `fallbackNavigationRequested` e gia `true`.
2. `analysis == null` e usato come stato loading/fallback generico, ma dopo success significa anche "preview cancellata"; questo crea spinner infinito invece di redirect/errore leggibile.
3. Il successo e rappresentato come stato persistente `ImportFlowState.Success`, non come evento one-shot; in recomposition/navigation puo essere consumato o perso.
4. `UiState.Success(import_success)` non e una superficie di feedback affidabile per `ImportAnalysisScreen`, che dipende dal `NavGraph`.
5. Manca un `finally` o un helper centralizzato che normalizzi applying/loading in tutti i percorsi success/error/cancellation.
6. Un eventuale sync remoto post-import potrebbe essere percepito come blocco se la UI resta agganciata a loading globale, ma il codice letto non mostra una dipendenza necessaria dal sync per chiudere l'import locale.

---

## Direzione implementativa minima futura

La futura execution deve preferire la patch piu piccola che elimina lo spinner infinito:

1. Non usare `analysis == null` come spinner infinito generico nella route `ImportAnalysis`.
2. Dopo success:
   - emettere completion in modo deterministico;
   - resettare applying/loading;
   - navigare/popBackStack verso la destinazione coerente;
   - solo dopo aver gestito la completion, pulire preview/import analysis;
   - mostrare feedback di successo se la destinazione lo consente.
3. Se la navigation resta sulla route import senza preview valida, mostrare stato finale/fallback navigabile o eseguire redirect, non spinner infinito.
4. Usare `try/finally` o helper equivalente nel ViewModel per garantire reset loading nei percorsi success, error e cancellation.
5. Se serve un evento one-shot, usare `SharedFlow`/`Channel` nel ViewModel o un meccanismo equivalente gia coerente con il progetto, evitando logica business nel composable.
6. Tenere la logica di import/apply nel ViewModel/repository; il composable deve solo renderizzare stato e inviare callback.
7. Non attendere auto-sync remoto per chiudere l'import locale.
8. Non cambiare `InventoryRepository.applyImport(...)` se il problema resta confinato a UI state/navigation.

---

## Planning step-by-step

1. Rileggere il diff corrente e confermare se `NavGraph.kt` ha modifiche locali prima della patch.
2. Riprodurre staticamente il caso `APPLY_IMPORT SUCCESS`:
   - `ImportAnalysisScreen.onConfirm`;
   - `DatabaseViewModel.importProducts`;
   - `ImportFlowState.Success`;
   - `NavGraph.LaunchedEffect`;
   - `dismissImportPreview`;
   - branch `analysis == null`.
3. Definire la destinazione attesa per ogni origin:
   - `HOME` -> `FilePicker`;
   - `DATABASE` -> `DatabaseScreen`;
   - `HISTORY` -> `HistoryScreen`;
   - `GENERATED` -> `GeneratedScreen` se contesto valido, altrimenti fallback coerente.
4. Applicare patch minima:
   - correggere ordine completion/navigation/reset preview oppure rendere `analysis == null` non bloccante;
   - garantire reset loading su success/error/cancellation;
   - aggiungere feedback successo o preservare `UiState.Success` in modo visibile sulla destinazione.
5. Se il fix richiede evento one-shot:
   - introdurlo nel `DatabaseViewModel`;
   - raccoglierlo nel `NavGraph` con `LaunchedEffect`;
   - evitare consumo perso in recomposition.
6. Estendere test esistenti:
   - `DatabaseViewModelTest` per success/error/cancellation/loading reset;
   - eventuale test navigation/fallback se gia presente infrastruttura adatta.
7. Eseguire check finali obbligatori e smoke manuali.
8. Documentare in `Execution` ogni file modificato, test eseguito e limite residuo.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Dopo **Conferma Importazione**, se `APPLY_IMPORT SUCCESS`, la schermata non resta piu su spinner bianco. | M / B | PARTIAL — patch statica applicata in `NavGraph`, incluso caso Generated senza errori; smoke manuale device/emulator ancora necessario. |
| 2 | Il loading/applying viene sempre resettato in success, error e cancellation. | S / B | DONE — `DatabaseViewModel` non modificato; success/error gia coperti da `DatabaseViewModelTest`, rollback/cancellation repository coperti da `DefaultInventoryRepositoryTest`. |
| 3 | Dopo import riuscito, l'utente riceve feedback chiaro: snackbar/toast/dialog/card di successo oppure ritorno coerente alla schermata prevista. | M / S | PARTIAL — `UiState.Success(import_success)` e navigation coerente preservati; verifica visuale runtime pendente. |
| 4 | La preview import non viene cancellata prima che navigation/completion sia gestita, oppure la UI gestisce preview assente senza spinner infinito. | S / M | DONE — il branch `analysis == null` ora fa redirect fallback se non e `PreviewLoading`; spinner solo per preview loading reale. |
| 5 | Se dopo import parte auto-sync/Supabase, questo non blocca la chiusura della schermata import. | S / M | PARTIAL — import resta Room-first, notifica auto-sync dopo commit locale e rilancia catch-up su internet validato; audit esteso ha aggiunto trigger anche per catalogo/prezzi/history; letture Supabase live autenticate OK; smoke device offline/reconnect ancora necessario. |
| 6 | In caso di errore import, viene mostrato errore leggibile e il pulsante puo essere riutilizzato. | S / M / B | DONE — `recoverImportPreviewAfterApplyError()` e failure path preservati; `DatabaseViewModelTest` verde. |
| 7 | Nessuna regressione su import Excel. | B / M | PARTIAL — test mirati `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest` verdi; smoke manuale import pendente. |
| 8 | Nessuna regressione su export Excel. | B / M | PARTIAL — `DatabaseViewModelTest`/`ExcelViewModelTest` verdi e codice export non toccato; smoke manuale pendente. |
| 9 | Nessuna regressione su database CRUD. | B / M | PARTIAL — build/lint e `DatabaseViewModelTest` verdi; smoke CRUD pendente. |
| 10 | Nessuna regressione su history flow. | B / M | PARTIAL — `ExcelViewModelTest` verde e history code non toccato; smoke History pendente. |
| 11 | Nessuna regressione su barcode scan. | M | NEEDS MANUAL VERIFY — area non toccata, ma non testata manualmente. |
| 12 | Nessuna regressione su manual entry. | M | NEEDS MANUAL VERIFY — area non toccata, ma non testata manualmente. |
| 13 | Nessuna regressione su generated flow. | B / M | PARTIAL — `ExcelViewModelTest` verde e ritorno `GENERATED` esteso a success completo/cancel in contesto generated; smoke generated/import pendente. |
| 14 | Nessuna regressione su navigation. | S / M | PARTIAL — `ImportNavOriginTest` verde e review statica origin completata; smoke HOME/DATABASE/HISTORY/GENERATED pendente. |
| 15 | Nessun cambio schema Room. | S | DONE — nessuna entity/schema/migration modificata; `ProductPriceDao.insertIfChanged(...)` ora ritorna solo `Boolean` per notificare sync quando inserisce davvero, senza cambiare query/schema. |
| 16 | Nessuna modifica Supabase live. | S / Live | OVERRIDDEN — vincolo originale superato da autorizzazione esplicita utente del 2026-04-24 per remediation RLS live; applicata solo migration SQL controllata, senza Edge Functions, senza RLS broad, senza dati distruttivi. |
| 17 | Test unitari/build/lint verdi, o eventuale blocco ambiente documentato con evidenza. | B | PARTIAL — build/lint/diff check e test mirati verdi; suite completa `:app:testDebugUnitTest` bloccata da `MockK`/`ByteBuddy` attach. |
| 18 | Supabase live non espone dati privati ad anon e supporta read/pull autenticati. | S / Live | DONE — dopo migration live, anon REST: catalogo/prezzi/sync_events HTTP 401, `shared_sheet_sessions` HTTP 200 con 0 righe; REST autenticato: catalogo/prezzi/sessions/sync_events HTTP 200 con sample leggibile. |
| 19 | Remote sync e pronto per crescita iOS/POS senza problemi evidenti di efficienza. | S / Live | PARTIAL — remediation DB attuale applicata: RLS owner-scoped con `(select auth.uid())`, indice `shared_sheet_sessions(owner_user_id, remote_id)`, indici owner/id catalogo e performance advisor senza issue; resta fuori scope implementare contratto multi-store/ledger POS/iOS. |

Legenda tipi: B=Build/test, S=Static, M=Manual, E=Emulator.

---

## Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Pulire preview troppo presto lascia `ImportAnalysis` senza dati e spinner infinito | Gestire completion/navigation prima del reset o rendere `analysis == null` un redirect/stato finale esplicito. |
| Evento successo perso in recomposition | Usare evento one-shot robusto o stato consumabile con owner chiaro nel ViewModel/NavGraph. |
| Fix navigation rompe origin `GENERATED`, `HISTORY`, `DATABASE` o `HOME` | Testare ogni origin e mantenere `ImportNavOrigin` come contratto unico. |
| Errore apply non permette retry | Preservare preview e `previewId` in `ImportFlowState.Error(occurredDuringApply = true)`. |
| Sync remoto viene accidentalmente atteso dalla UI | Tenere apply locale e sync cloud separati; non introdurre await del coordinator nel completion import. |
| Refactor eccessivo del flusso import/export | Patch minima su ViewModel/NavGraph/ImportAnalysisScreen, senza toccare parser/repository salvo necessita provata. |
| `UiState.Success` non viene mostrato nella destinazione | Decidere feedback esplicito nel punto di navigazione o snackbar/evento dedicato. |

---

## Test / check finali da eseguire

Check obbligatori:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
git diff --check
```

Test mirati da cercare o aggiungere se il fix tocca i relativi contratti:

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"
```

Note:

- Non e stato trovato un test specifico chiamato `ImportAnalysisTest`.
- Non e stato trovato un test specifico chiamato `DatabaseImportTest`.
- Esistono coperture correlate in `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest` e `ImportNavOriginTest`.

---

## Smoke manuali

1. Import file con 0 nuovi prodotti e 1 prodotto aggiornato.
2. Premere **Conferma Importazione**.
3. Verificare che non resta spinner bianco.
4. Verificare che il prodotto risulta aggiornato nel `DatabaseScreen`.
5. Verificare che eventuale history/import entry venga salvata correttamente se prevista dal flusso.
6. Ripetere con nuovo prodotto.
7. Ripetere con file con errore critico: deve mostrare errore e non restare bloccato.
8. Ripetere con connessione Supabase attiva: auto-sync non deve bloccare UX import.
9. Ripetere offline: import locale deve comunque completare UX correttamente.
10. Ripetere dai diversi origin dove applicabile: Home, Database, History, Generated.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task creata come planning, senza patch funzionale immediata | Il bug e post-success e tocca stato UI/navigation; serve execution mirata dopo conferma utente. | 2026-04-24 |
| 2 | Room resta source of truth locale; nessun cambio schema Room o Supabase | Il log indica apply locale riuscito; non serve cambiare persistenza o cloud. | 2026-04-24 |
| 3 | Prima root cause da verificare: reset preview/import analysis prima che navigation sia garantita | Il branch `analysis == null` mostra spinner full-screen e il success path marca gia il fallback come richiesto. | 2026-04-24 |
| 4 | Auto-sync remoto non deve bloccare completion locale | L'utente deve uscire dalla schermata import dopo successo DB anche se sync cloud continua/fallisce in background. | 2026-04-24 |

---

## Planning (Claude)

### Analisi

Il percorso DB apply appare gia atomico e coerente: `DatabaseViewModel` chiama `InventoryRepository.applyImport(...)`, il repository usa transaction Room e ritorna `Success`; il log runtime conferma questo tratto.

La parte fragile e il contratto post-success tra `DatabaseViewModel` e `NavGraph`: il successo e uno stato, non un evento one-shot; `NavGraph` naviga e subito pulisce la preview. Se la route resta su `ImportAnalysis`, il composable non ha piu `importAnalysisResult` e mostra spinner centrale. Il fallback e disabilitabile dal flag locale gia settato nel success path, quindi il bug osservato e plausibile senza toccare il database.

### Piano di esecuzione

1. Verificare working tree e preservare eventuali modifiche utente.
2. Aggiungere test/asserzioni su `DatabaseViewModel.importProducts` per success/error/cancellation e retry quando possibile.
3. Correggere `NavGraph` o il contratto ViewModel in modo che success completion sia one-shot e non dipenda da `analysis == null` come loading.
4. Garantire che `dismissImportPreview()` avvenga solo dopo completion gestita o che l'assenza preview venga renderizzata come redirect/stato finale.
5. Verificare origin HOME/DATABASE/HISTORY/GENERATED.
6. Eseguire test/check finali e smoke manuali.

### Rischi identificati

- Fixare solo il flag locale in `NavGraph` potrebbe mascherare il problema ma lasciare fragile il contratto success/reset.
- Spostare troppa logica in `ImportAnalysisScreen` violerebbe MVVM; mantenere decisione nel ViewModel/NavGraph.
- Introdurre evento one-shot senza test puo creare regressioni su ricomposizione/orientamento/back stack.

---

## Review multi-ruolo interna — 2026-04-24

### Reviewer A — ViewModel / import state

- Root cause ViewModel probabile: non primaria. `DatabaseViewModel.importProducts(...)` imposta `ImportFlowState.Applying(previewId)`, poi su `ImportApplyResult.Success` emette `ImportFlowState.Success(previewId)` e `UiState.Success(import_success)`. Il path success resetta quindi lo stato di loading globale e non resta in `Applying`.
- `ImportFlowState.Success` e uno stato persistente, non un evento one-shot. Per il bug osservato non serve introdurre subito `SharedFlow`/`Channel`: il problema nasce quando `NavGraph` consuma `Success`, naviga e pulisce la preview mentre la route puo restare visibile.
- Su errore apply, `ImportFlowState.Error(previewId, ..., occurredDuringApply = true)` preserva `activePreviewId` e `importAnalysisResult`; `recoverImportPreviewAfterApplyError()` torna a `PreviewReady`, quindi il retry e preservato.
- Patch minima consigliata: non cambiare repository; correggere `NavGraph` in modo che preview assente dopo completion non produca spinner infinito.
- Test consigliati: i test esistenti in `DatabaseViewModelTest` coprono success, failure, recover retry, double confirm e clear durante apply. Aggiungere test ViewModel solo se la patch modifica il contratto del VM.
- Rischi: introdurre un evento one-shot senza necessita aumenterebbe superficie e test richiesti.

### Reviewer B — NavGraph / navigation / origin

- Root cause navigation probabile: confermata. In `NavGraph`, su `ImportFlowState.Success`, vengono settati `successNavigationRequested` e `fallbackNavigationRequested`, poi viene chiamata `navigateToImportSuccessDestination(...)` e subito dopo `dismissImportPreview()`. Se la route resta su `ImportAnalysis`, `analysis == null` entra nel branch spinner; il fallback non riparte perche il flag e gia `true`.
- `analysis == null` oggi significa sia "preview non ancora pronta" sia "preview gia cancellata"; dopo success deve diventare redirect/stato finale sicuro, non spinner full-screen.
- Destinazioni attese: `HOME -> FilePicker`, `DATABASE -> DatabaseScreen`, `HISTORY -> HistoryScreen`, `GENERATED -> GeneratedScreen` se `currentEntryUid` valido, altrimenti `FilePicker`. Per generated con errori, mantenere il redirect a `GENERATED`.
- Patch minima consigliata: mantenere in `NavGraph` un fallback origin esplicito per preview assente, non bloccare il fallback dopo success e mostrare spinner solo durante `PreviewLoading`.
- Test/smoke consigliati: smoke manuale sui quattro origin; test automatico navigation Compose non presente e non va inventato in questa patch.

### Reviewer C — UI Compose / ImportAnalysisScreen

- `ImportAnalysisScreen` renderizza stato e callback; non contiene business logic e non e la root cause primaria.
- `isApplying` disabilita conferma/close e mostra `LinearProgressIndicator`; gli errori apply vengono mostrati nella card preview.
- Il branch spinner full-screen non e nel composable ma in `NavGraph`, quindi non serve redesign di `ImportAnalysisScreen`.
- Feedback successo puo restare `UiState.Success(import_success)` + navigazione coerente; non serve nuova copy se la patch resta solo navigation.
- Cosa non toccare: layout card/righe, parser, edit dialog, logica di business.

### Reviewer D — Test / regressioni / scope control

- Test esistenti rilevanti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `ImportNavOriginTest`, `FullDbExportImportRoundTripTest`.
- Test obbligatori per questa execution: suite `:app:testDebugUnitTest`, build, lint, `git diff --check`, piu test mirati richiesti dall'utente quando presenti.
- Non esiste infrastruttura di test navigation/Compose per questa route; per la patch minima basta non introdurre test complessi e documentare smoke manuali.
- Rischi regressione: origin `GENERATED` con errori, `HISTORY`/`DATABASE` root restore, retry dopo errore apply.
- Scope ancora minimo se la patch resta in `NavGraph.kt` e, solo se necessario, in `DatabaseViewModelTest` o stringhe.

---

## Execution Gate consolidato

- OK procedere con execution: la root cause resta confinata a `NavGraph` / stato import osservato, senza evidenza di bug in `InventoryRepository.applyImport(...)`.
- Prima patch preferita: impedire che `analysis == null` produca spinner infinito dopo success. Lo spinner full-screen deve restare ammesso solo come loading preview reale (`ImportFlowState.PreviewLoading`).
- Correggere il contratto navigation/reset preview: dopo `ImportFlowState.Success`, la completion deve navigare verso la destinazione corretta e il fallback per preview assente deve poter ripartire se la route resta temporaneamente o stabilmente su `ImportAnalysis`.
- Non cambiare `InventoryRepository.applyImport(...)`: log runtime e codice confermano che l'apply locale completa con `ImportApplyResult.Success` dentro transazione Room.
- Non cambiare sync/Supabase: l'import locale non deve attendere auto-sync remoto.
- Non introdurre nuova architettura/evento one-shot se una patch locale in `NavGraph` risolve il caso.
- Se durante implementation emerge la necessita di modificare `DatabaseViewModel`, limitarsi al reset stato success/error/cancellation e aggiungere test mirati.
- Se la patch richiedesse DAO, schema Room, parser Excel o refactor repository/cloud, fermarsi e documentare `BLOCKED` o `PARTIAL`.

**Decisione execution 2026-04-24:** CASO A — sicuro procedere con patch minima. Il codice conferma che la prima correzione puo stare in `NavGraph.kt`; nessun cambio schema, repository, parser Excel o Supabase e necessario.

---

## Execution

### Esecuzione — 2026-04-24

**Precheck working tree:**
- `git status --short` iniziale non pulito:
  - `M docs/MASTER-PLAN.md` preesistente, non modificato in questa execution;
  - `?? docs/TASKS/TASK-056-fix-spinner-post-conferma-import-review.md` task target non tracciato/preesistente.
- Nessuna modifica utente e stata revertita.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — patch minima sul ramo `ImportAnalysis`: `importFlowState` osservato anche quando `analysis == null`, fallback origin esplicito per preview assente, spinner full-screen limitato a `ImportFlowState.PreviewLoading`.
- `docs/TASKS/TASK-056-fix-spinner-post-conferma-import-review.md` — review multi-ruolo, execution gate consolidato, criteri aggiornati, log execution, review, chiusura e handoff.

**Root cause confermata:**
- `DatabaseViewModel.importProducts(...)` completa il path locale con `ImportApplyResult.Success`, logga `APPLY_IMPORT SUCCESS`, emette `ImportFlowState.Success(previewId)` e `UiState.Success(import_success)`.
- In `NavGraph`, il success path navigava e subito chiamava `dismissImportPreview()`, cancellando `importAnalysisResult`.
- Se la route restava su `ImportAnalysis`, il branch `analysis == null` mostrava `CircularProgressIndicator` full-screen; inoltre `fallbackNavigationRequested` era gia `true`, quindi il fallback non ripartiva. Questo combacia con lo spinner bianco post-success osservato.
- Feedback runtime utente dopo la prima patch: con righe di errore il ritorno a `Generated` funzionava, mentre success completo senza errori e pulsante Annulla potevano ancora finire su schermata bianca.
- Root cause raffinata: nel contesto `Generated`, il ramo con errori forzava `ImportNavOrigin.GENERATED`; success completo e close/cancel usavano invece `importOrigin` grezzo (`HOME`/`HISTORY`), saltando la route `Generated` che conserva lo stato entry e l'eventuale exit pendente.

**Patch applicata:**
1. Spostata la raccolta di `importFlowState` fuori dal ramo `analysis != null`, cosi `NavGraph` puo distinguere preview loading reale da preview assente.
2. Aggiunto `missingPreviewFallbackOrigin`, inizializzato con `importOrigin` e aggiornato al `successDestination` calcolato nel success path.
3. Rimosso il blocco preventivo del fallback nel success/onClose/onCorrectRows: il flag `fallbackNavigationRequested` viene impostato solo quando il branch `analysis == null` esegue davvero il redirect.
4. Nel branch `analysis == null`, `CircularProgressIndicator` viene mostrato solo per `ImportFlowState.PreviewLoading`. In tutti gli altri stati, se la route resta su `ImportAnalysis`, parte il redirect verso la destinazione sicura.
5. Aggiunto `generatedAwareReturnOrigin`: se esiste un contesto `Generated` reale (`currentEntryUid != 0` e origin non `DATABASE`), success completo, success con errori, close/cancel e fallback preview assente tornano a `ImportNavOrigin.GENERATED`.
6. Se manca `currentEntryUid`, `navigateToImportSuccessDestination` continua a usare il fallback `FilePicker` gia esistente.

**Cosa NON e stato cambiato:**
- Nessun cambio a entity, schema Room o migration.
- Nessun cambio strutturale ai DAO nella patch navigation; l'audit supplementare successivo ha modificato solo il ritorno di `ProductPriceDao.insertIfChanged(...)` per sapere se notificare il sync prezzi.
- Nessuna modifica a Supabase live, RLS, Edge Functions, migration remote o remote data source.
- Nessuna modifica a parser Excel, import/export Excel, barcode scan, manual entry, database CRUD, history business flow o generated business logic.
- Nessuna nuova dipendenza e nessun evento one-shot introdotto.
- Nessuna business logic spostata nei composable.

**Perche la patch e minima:**
- Il diff funzionale e confinato a `NavGraph.kt`.
- Il ViewModel e il repository restano source of truth per stato/import e apply locale.
- La correzione agisce solo sul contratto post-success/reset preview e sul fallback della route import senza refactor architetturale.

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | DONE | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL. Primo tentativo senza `JAVA_HOME` non eseguibile per Java Runtime mancante. |
| Lint | DONE | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL. |
| Warning nuovi | DONE | Nessun warning Kotlin/deprecation attribuibile al diff `NavGraph.kt`; restano warning Gradle/AGP preesistenti di configurazione. |
| Coerenza con planning | DONE | Patch limitata a `NavGraph`, nessun repository/parser/sync/schema. |
| Criteri di accettazione | PARTIAL | Criteri statici principali coperti; smoke manuali e suite unitaria completa restano pendenti/bloccati come documentato. |

**Test eseguiti:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"` -> BUILD SUCCESSFUL. Nota: un primo lancio parallelo non e valido per collisione file temporaneo Gradle; rieseguito singolarmente e verde.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest` -> FAIL ambiente/preesistente: ultima esecuzione finale 345 test, 121 failed, 2 skipped; failure a cascata da `MockK`/`ByteBuddyAgent` con `com.sun.tools.attach.AttachNotSupportedException`, gia osservata in TASK-055.

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `ImportNavOriginTest`.
- Test aggiunti/aggiornati: nessuno. La patch non modifica ViewModel/repository/analyzer e non esiste infrastruttura navigation Compose dedicata per testare questa route.
- Limiti residui: suite completa bloccata da ambiente MockK/ByteBuddy attach; smoke manuali necessari su device/emulator per il post-success reale.

**Smoke manuali ancora necessari:**
1. Import Excel con 0 nuovi, 1 aggiornato, 0 errori: confermare che dopo `APPLY_IMPORT SUCCESS` non resta spinner bianco e si arriva alla destinazione corretta.
2. Da `Generated`, verificare anche il pulsante **Annulla** nella schermata `ImportAnalysis`: deve tornare alla griglia, non alla schermata bianca.
3. Ripetere da origin `HOME`, `DATABASE`, `HISTORY`, `GENERATED`.
4. Ripetere con file con nuovo prodotto.
5. Ripetere con errore apply simulabile/errore critico: preview preservata, errore leggibile, retry possibile.
6. Ripetere online/offline con Supabase attivo: auto-sync non blocca UX locale.
7. Smoke regressione import/export Excel, database CRUD, history, barcode scan, manual entry, generated flow.

**Incertezze:**
- Nessuno smoke manuale su emulator/device eseguito in questa sessione.
- La suite completa resta non affidabile nell'ambiente corrente per ByteBuddy attach; i test mirati richiesti sono verdi.

**Handoff notes:**
- Prima verifica manuale: riprodurre esattamente il caso utente 0 nuovi / 1 aggiornato / 0 errori e controllare route post-success.
- Se resta un caso raro di route import visibile senza preview, il branch non mostra piu spinner infinito e rilancia redirect fallback.

### Esecuzione supplementare — 2026-04-24 — offline/reconnect

**Richiesta utente:** chiarire se import/Room funzionano senza internet e se le modifiche vengono inviate a Supabase appena torna la connessione; correggere se non e cosi.

**Diagnosi:**
- L'import e gia Room-first: `DatabaseViewModel.importProducts(...)` chiama `InventoryRepository.applyImport(...)`, che applica una transazione Room locale e non aspetta Supabase.
- `applyImportAtomically(...)` marcava il catalogo locale dirty (`localChangeRevision` / bridge remote refs), quindi i dati restavano candidati per push futuro.
- Gap trovato: l'import non notificava `onProductCatalogChanged`, quindi l'auto-sync catalogo non partiva subito dopo un import riuscito.
- Gap trovato: non esisteva un callback `ConnectivityManager` per rilanciare catalog/history sync quando internet tornava mentre l'app era gia aperta.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `applyImport(...)` ora raccoglie gli id prodotto toccati dalla transazione Room e notifica `onProductCatalogChanged` solo dopo commit riuscito.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt` — aggiunto `onNetworkAvailable()` per pianificare bootstrap, push dirty catalog e drain sync events.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistorySessionPushCoordinator.kt` — aggiunto `onNetworkAvailable()` per pianificare push sessioni history pendenti.
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt` — registrato `ConnectivityManager.NetworkCallback`; quando una rete ha internet validato, pianifica catch-up locale dei coordinator.
- `app/src/main/AndroidManifest.xml` — aggiunto `ACCESS_NETWORK_STATE` per osservare lo stato rete.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — esteso test import dirty per verificare notifica auto-sync post-import.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt` — aggiunto test del tickle `network_available`.

**Cosa NON e stato cambiato:**
- Nessun cambio schema Room, entity o migration.
- Nessun cambio strutturale ai DAO: `ProductPriceDao.insertIfChanged(...)` e stato aggiornato solo per ritornare se ha inserito una nuova riga, cosi il repository puo notificare il sync prezzi senza duplicare logica.
- Nessuna modifica Supabase live, RLS, Edge Functions, migration remote o remote data source.
- Nessun cambio parser Excel/export/barcode/manual entry.
- Nessuna attesa del sync remoto nel percorso import locale.

**Nota successiva:** il punto su Supabase live era vero in questa esecuzione supplementare; e stato poi superato da autorizzazione esplicita utente e dal fix live documentato nella sezione `Fix — 2026-04-24 — remediation Supabase live RLS/security/performance`.

**Verifiche supplementari:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` -> BUILD SUCCESSFUL.

**Smoke manuali aggiunti:**
1. Spegnere rete, completare import: il DB locale deve aggiornarsi e la UI deve uscire dalla review senza attendere Supabase.
2. Riaccendere rete con app in foreground: log attesi `Network: internet validato disponibile...` e ciclo `catalog_push`/history push se ci sono pending.
3. Verificare su Supabase che il catalogo venga aggiornato dopo riconnessione, senza intervento manuale.

### Audit supplementare — 2026-04-24 — offline-first catalogo/history

**Richiesta utente:** controllare anche il resto dell'applicazione in modalita offline: corretto uso di Room senza rete, push/pull alla riconnessione, sync completo/incrementale per catalogo prodotti, fornitori, categorie e `HistoryEntry`.

**Verdetto audit statico:**
- Room resta la fonte primaria per prodotti, fornitori, categorie, prezzi e history: le mutazioni locali scrivono prima su Room e non dipendono da Supabase per completare la UX.
- Catalogo prodotti: `addProduct(...)` e `updateProduct(...)` gia marcavano prodotto dirty e notificavano `onProductCatalogChanged`; `applyImport(...)` ora fa lo stesso dopo commit Room.
- Catalogo fornitori/categorie: `addSupplier(...)`, `addCategory(...)`, `createCatalogEntry(...)`, `renameCatalogEntry(...)`, `deleteCatalogEntry(...)` marcavano dirty/tombstone ma non avevano sempre un trigger generico verso l'auto-sync; gap corretto.
- Delete prodotto: enqueuava tombstone locale ma non notificava auto-sync; gap corretto con trigger generico catalogo.
- Prezzi: `recordPriceIfChanged(...)` scriveva Room ma non notificava sync; gap corretto notificando solo quando `ProductPriceDao.insertIfChanged(...)` inserisce davvero una nuova riga.
- HistoryEntry: `insertHistoryEntry(...)` e `updateHistoryEntry(...)` gia notificavano `onHistorySessionPayloadChanged` per payload rilevanti; la patch di reconnect fa ripartire il push history anche al ritorno della rete.
- Sync completo: `syncFullCatalogWithRemote(...)` continua a eseguire push locale e pull completo catalogo/prezzi con metriche oneste.
- Sync incrementale/quick: `syncCatalogQuickWithEvents(...)` usa `sync_events` quando disponibili, emette eventi locali dopo push e drena aggiornamenti remoti mirati; se le capability non sono disponibili torna a push-delta senza fingere pull incrementale verificabile.
- Riconnessione: `ConnectivityManager.NetworkCallback` ora pianifica bootstrap/push/drain catalogo e push history quando Android segnala internet validato.

**Gap corretti in questa fase:**
- `InventoryRepository.onCatalogChanged` aggiunto come hook generico per mutazioni catalogo senza `productId` preciso.
- `MerchandiseControlApplication` collega `repository.onCatalogChanged` a `CatalogAutoSyncCoordinator.onLocalCatalogChanged()`.
- `CatalogAutoSyncCoordinator.onLocalCatalogChanged()` pianifica un push catch-up del catalogo dirty.
- `addSupplier(...)`, `addCategory(...)`, `deleteProduct(...)`, `createCatalogEntry(...)`, `renameCatalogEntry(...)`, `deleteCatalogEntry(...)` e `recordPriceIfChanged(...)` ora notificano il coordinator dopo commit locale riuscito.
- `deleteCatalogEntry(... CreateNewAndReplace ...)` marca dirty anche la nuova entita replacement creata durante la cancellazione.

**Rischi / limiti residui dell'audit:**
- Verifica Supabase live read-only eseguita in questa sessione: REST autenticato, targeted fetch e `sync_events` incrementale OK; push/pull write live non eseguiti per non modificare dati remoti prima del fix RLS.
- Il worker di backfill prezzi puo ancora scrivere righe prezzo senza trigger diretto al coordinator; resta coperto da foreground/reconnect/manual sync, ma e un follow-up possibile se serve push immediato a fine backfill.
- Le policy background restano intenzionali: i coordinator saltano push quando l'app e in background; catch-up riparte a foreground/reconnect.

**Test aggiunti/aggiornati:**
- `DefaultInventoryRepositoryTest`: verifica notifica post-import, notifica add supplier/category solo su creazione, notifica mutazioni create/rename/delete supplier, notifica delete prodotto, e notifica prezzo solo su nuovo prezzo effettivo.
- `CatalogAutoSyncCoordinatorTest`: verifica `onNetworkAvailable()` e `onLocalCatalogChanged()` come trigger di push.

**Verifiche audit supplementare:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL; rieseguito dopo aggiunta notifiche `addSupplier`/`addCategory`, ancora verde.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` -> BUILD SUCCESSFUL.

### Audit live Supabase read-only — 2026-04-24

**Perimetro eseguito:**
- Usata la configurazione locale reale (`SUPABASE_URL`, publishable key, Google client id) senza stampare segreti.
- Recuperata sessione utente gia presente sull'emulatore debug e usato il JWT solo in memoria per richieste REST live.
- Eseguiti controlli live non distruttivi: REST anon, REST autenticato, targeted fetch, `sync_events` incremental fetch, introspezione SQL read-only via Supabase CLI linked, security/performance advisors.
- Nessun upsert/delete/SQL mutation live applicato in questa fase.

**Risultati live REST:**
- Auth session emulator: `/auth/v1/user` -> HTTP 200.
- REST autenticato:
  - `inventory_suppliers`, `inventory_categories`, `inventory_products`, `inventory_product_prices`, `shared_sheet_sessions`, `sync_events` -> HTTP 206/200 con sample leggibile.
  - Targeted product fetch via `id=in.(...)` -> HTTP 200, 1 riga.
  - Targeted price fetch via `id=in.(...)` -> HTTP 200, 1 riga.
  - Incremental `sync_events` fetch `id > sample` -> HTTP 200, 8 eventi.
- Conteggi live via HEAD autenticato:
  - suppliers 68, categories 42, products 18.866, product prices 37.866, sessions 5, sync events 9.
  - Questo conferma che full sync catalogo/prezzi e gia costoso; quick/incrementale deve restare la corsia normale per i client futuri.

**Risultati anon/RLS:**
- `inventory_suppliers`, `inventory_categories`, `inventory_products`, `inventory_product_prices`, `sync_events` -> HTTP 401 / `42501`, quindi anon e bloccato.
- `shared_sheet_sessions` -> HTTP 200 con 1 riga visibile ad anon, inclusi `owner_user_id`, `remote_id`, `data` e `session_overlay`.
- Root cause live: policy `shared_sheet_sessions_select_public` con `USING (true)` per ruoli `anon, authenticated`.

**Audit SQL read-only live:**
- RLS abilitata sulle tabelle principali.
- Inventory tables hanno policy owner-scoped per `authenticated` su select/insert/update.
- `sync_events` ha select owner-scoped e realtime publication attiva.
- Realtime publication attiva per `shared_sheet_sessions` e `sync_events`, coerente col codice Android.
- `record_sync_event(...)` esiste ed e `SECURITY DEFINER`.

**Supabase advisors live:**
- Security:
  - ERROR `security_definer_view`: `public.product_price_summary`.
  - WARN `function_search_path_mutable`: `inventory_catalog_block_update_when_tombstoned`, `set_updated_at`.
  - WARN `rls_policy_always_true`: legacy `public.history_entries` insert/update/delete permissivi.
  - WARN leaked password protection disabled.
- Performance:
  - WARN `auth_rls_initplan`: policy RLS inventory e sessions usano `auth.uid()` invece di `(select auth.uid())`.
  - WARN `multiple_permissive_policies`: `shared_sheet_sessions` ha `shared_sheet_sessions_select_owner` + `shared_sheet_sessions_select_public`.

**Blocker per chiusura DONE:**
- Non e sicuro dichiarare DONE con `shared_sheet_sessions` leggibile da anon. Le sessioni contengono dati operativi/history e devono essere owner-scoped o condivise tramite un modello esplicito di share token.
- Non applico automaticamente `DROP POLICY` / `ALTER POLICY` sul live perche e una modifica RLS remota ad alto impatto e la task originale vietava modifiche Supabase live. Serve una decisione esplicita di migration remota.

**Stato successivo:** superato dal fix live autorizzato in seguito dall'utente; vedere `Fix — 2026-04-24 — remediation Supabase live RLS/security/performance`.

**Remediation SQL consigliata, da applicare come migration controllata dopo approvazione:**
```sql
-- Blocca leak anon delle sessioni history.
drop policy if exists shared_sheet_sessions_select_public on public.shared_sheet_sessions;

-- Mantieni/ricrea policy owner-scoped, ottimizzate per initplan.
drop policy if exists shared_sheet_sessions_select_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_select_owner
on public.shared_sheet_sessions
for select
to authenticated
using ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_insert_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_insert_owner
on public.shared_sheet_sessions
for insert
to authenticated
with check ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_update_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_update_owner
on public.shared_sheet_sessions
for update
to authenticated
using ((select auth.uid()) = owner_user_id)
with check ((select auth.uid()) = owner_user_id);

drop policy if exists shared_sheet_sessions_delete_owner on public.shared_sheet_sessions;
create policy shared_sheet_sessions_delete_owner
on public.shared_sheet_sessions
for delete
to authenticated
using ((select auth.uid()) = owner_user_id);

create index if not exists shared_sheet_sessions_owner_remote_id_idx
on public.shared_sheet_sessions(owner_user_id, remote_id);
```

**Remediation future iOS / Windows POS:**
- Estrarre un contratto cloud condiviso versionato (`inventory_*`, `product_prices`, `shared_sheet_sessions`, `sync_events`, payload v2) prima di implementare iOS/POS.
- Aggiungere `store_id`/tenant alle tabelle inventory prima del POS multi-store; oggi `sync_events` lo prevede, ma inventory rows sono ancora solo `owner_user_id`.
- Per POS Windows serve modello ledger/event-sourced per vendite e stock movements, non update diretto dello stock come unica fonte; usare `client_event_id`, `device_id`, idempotenza e conflitti last-write solo dove accettabile.
- Rendere quick/incrementale la default lane per i client futuri; full sync solo bootstrap/manual repair, visto il volume live attuale.

---

## Review

### Review post-execution — 2026-04-24

**OK:**
- La patch risolve la root cause probabile dello spinner post-success: `analysis == null` non resta piu un loading infinito se la preview e stata cancellata dopo completion.
- Patch navigation minima in `NavGraph.kt`; patch supplementare offline/reconnect limitata a notifica post-import, coordinator locali e callback rete applicativo.
- Room-first rispettato: Room/repository restano source of truth locale.
- La remediation Supabase live autorizzata e stata limitata a RLS, security-invoker/search_path e indici; nessuna Edge Function e nessuna attesa sync remoto nel percorso import.
- Nessuna business logic spostata in `ImportAnalysisScreen` o altri composable; `NavGraph` resta responsabile del routing.
- Parser Excel, schema, barcode, manual entry e history business flow non sono stati refactorati; `InventoryRepository` e `ProductPriceDao` sono stati toccati solo per hook locali post-commit e ritorno booleano di inserimento prezzo.
- Retry su errore apply preservato: `recoverImportPreviewAfterApplyError()` e `ImportFlowState.Error(... occurredDuringApply = true)` invariati.
- Origin `HOME`, `DATABASE`, `HISTORY`, `GENERATED` preservati; in contesto `Generated`, success completo/cancel/fallback tornano a `Generated`, mentre `DATABASE` resta esplicitamente escluso da questa correzione.
- Offline-first audit: Room-first confermato per import/catalogo/history; aggiunti trigger per mutazioni catalogo generiche, prezzi, delete/tombstone e reconnect.
- Supabase live audit post-remediation: anon non legge piu session data; REST autenticato OK per catalogo/prezzi/sessions/sync_events, targeted fetch e `sync_events` incremental OK.
- Realtime publication live presente per `shared_sheet_sessions` e `sync_events`, coerente col codice Android.
- Task aggiornato con gate, execution, criteri, test, review, chiusura e handoff.
- Test mirati richiesti verdi; build/lint/diff check verdi.

**BLOCKER:**
- Nessun blocker di codice trovato nella patch.
- Il blocker remoto `shared_sheet_sessions_select_public` e stato rimosso e anon REST ora restituisce 0 righe su `shared_sheet_sessions`.
- Non dichiaro `DONE` perfetto perche mancano smoke manuali device/emulator su success/cancel/offline-reconnect e la suite completa `:app:testDebugUnitTest` e bloccata da errore ambiente/preesistente `MockK`/`ByteBuddy`.
- Resta un warning Supabase Auth dashboard (`auth_leaked_password_protection`) non correggibile via migration SQL; non blocca il flusso Google attuale, ma va valutato se si abilita login password.

**FIX CONSIGLIATI:**
- Applicato fix piccolo dopo feedback runtime utente: estendere la destinazione generated-aware anche a success completo e close/cancel, non solo al ramo con errori.
- Eseguire smoke manuali del flusso reale post-success e del pulsante Annulla.
- Risolvere o aggirare ambiente ByteBuddy/MockK per rendere affidabile la suite completa.
- Migration Supabase controllata applicata: `shared_sheet_sessions_select_public` rimossa, RLS ottimizzata con `(select auth.uid())`, indice owner-scoped su `shared_sheet_sessions` aggiunto.
- `product_price_summary` e funzioni advisor rimediate; performance advisor senza issue dopo migration.

**Test mancanti / limiti:**
- Nessun test Compose/navigation route-specific esistente; non introdotto per evitare scope creep.
- Smoke HOME/DATABASE/HISTORY/GENERATED non eseguiti.
- Smoke Supabase online/offline e riconnessione rete non eseguiti su device.
- Push/pull completo e incrementale verificati staticamente nel codice e con letture live autenticate/targeted; write/upsert/delete live controllato non eseguito per evitare dati di test persistenti dopo circuit breaker temporaneo del pooler SQL CLI.

**Verdetto review:** OK per stato `PARTIAL`: blocker RLS remoto risolto, ma restano smoke manuali e suite completa bloccata da ambiente.

---

## Fix

### Fix — 2026-04-24

**Trigger:** feedback runtime utente dopo la prima patch. Il caso con righe di errore tornava correttamente a `Generated`, ma il success completo senza errori e il pulsante **Annulla** potevano ancora arrivare a schermata bianca.

**Causa raffinata:**
- Il ramo success con errori usava gia `ImportNavOrigin.GENERATED`.
- Il ramo success senza errori e il ramo close/cancel usavano `importOrigin` grezzo.
- Nel flusso generated, `importOrigin` puo rappresentare il punto di ingresso originario (`HOME`/`HISTORY`) e non la schermata corrente che conserva `entryUid`, stato griglia e possibile exit pendente.

**Fix applicato:**
- In `NavGraph.kt` introdotto `generatedAwareReturnOrigin`.
- Se `currentEntryUid != 0L` e l'origin non e `DATABASE`, success, close/cancel e fallback preview assente ritornano a `ImportNavOrigin.GENERATED`.
- Il ramo `DATABASE` resta escluso per non rompere import diretto da database.

**Verifiche dopo fix:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest` -> FAIL ambiente/preesistente `MockK`/`ByteBuddyAgent` (`AttachNotSupportedException`), ultima esecuzione finale 345 test / 121 failed / 2 skipped.

**Limite residuo:** smoke manuale device/emulator ancora necessario per confermare il caso preciso: `Generated` -> `ImportAnalysis` -> success completo senza errori e `Annulla`.

### Fix — 2026-04-24 — offline/reconnect

**Trigger:** richiesta utente su funzionamento import senza internet e push automatico alla riconnessione.

**Fix applicato:**
- `InventoryRepository.applyImport(...)` continua a completare prima la transazione Room locale, poi notifica `onProductCatalogChanged` per i prodotti toccati solo dopo commit riuscito.
- `MerchandiseControlApplication` osserva `ConnectivityManager` e, quando Android segnala una rete con internet validato, chiama i coordinator locali.
- `CatalogAutoSyncCoordinator.onNetworkAvailable()` pianifica bootstrap/push dirty/drain eventi.
- `HistorySessionPushCoordinator.onNetworkAvailable()` pianifica push delle sessioni history pendenti.

**Verifiche dopo fix:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` -> BUILD SUCCESSFUL.

**Limite residuo:** da verificare manualmente con rete disattivata/riattivata su emulator/device e Supabase configurato.

### Fix — 2026-04-24 — audit offline-first catalogo/history

**Trigger:** richiesta utente di audit completo sul resto dell'app in offline/reconnect, incluso catalogo prodotti, fornitori, categorie e `HistoryEntry`.

**Fix applicato:**
- `InventoryRepository` espone `onCatalogChanged` per mutazioni catalogo senza id prodotto specifico.
- `MerchandiseControlApplication` collega `onCatalogChanged` a `CatalogAutoSyncCoordinator.onLocalCatalogChanged()`.
- `CatalogAutoSyncCoordinator.onLocalCatalogChanged()` pianifica un push delta/catch-up.
- `addSupplier(...)`, `addCategory(...)`, `deleteProduct(...)`, `createCatalogEntry(...)`, `renameCatalogEntry(...)`, `deleteCatalogEntry(...)` e `recordPriceIfChanged(...)` notificano il sync locale solo dopo scrittura Room riuscita.
- `ProductPriceDao.insertIfChanged(...)` ritorna `Boolean`, cosi i prezzi notificano il sync solo quando viene inserita una nuova riga.
- La strategy `CreateNewAndReplace` marca dirty anche la nuova entita supplier/category creata come replacement.

**Verifiche dopo fix:**
- `git diff --check` -> PASS.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` -> BUILD SUCCESSFUL.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` -> BUILD SUCCESSFUL.

**Limite residuo:** push/pull completo e quick/incrementale devono essere smoke-testati con Supabase reale, account autenticato e rete disattivata/riattivata.

### Fix — 2026-04-24 — remediation Supabase live RLS/security/performance

**Trigger:** autorizzazione esplicita utente a passare dalla remediation consigliata alla modifica live Supabase per arrivare almeno a:
- rimuovere `shared_sheet_sessions_select_public`;
- mantenere solo policy owner-scoped per `authenticated`;
- ottimizzare RLS con `(select auth.uid())`;
- aggiungere indice `shared_sheet_sessions(owner_user_id, remote_id)`;
- rieseguire anon REST verificando che `shared_sheet_sessions` non esponga piu dati.

**File modificati:**
- `supabase/migrations/20260424190000_harden_rls_and_sync_indexes.sql` — migration controllata per RLS owner-scoped, hardening advisor e indici sync.

**Fix applicato live:**
1. Eseguito dry-run SQL con `rollback` prima dell'applicazione live: nessun errore SQL.
2. Applicata live la migration `20260424190000_harden_rls_and_sync_indexes.sql` sul progetto Supabase `merchandisecontrol-dev`.
3. Rimossa la policy pubblica `shared_sheet_sessions_select_public`.
4. Ricreate policy `shared_sheet_sessions` `select/insert/update/delete` solo per `authenticated` e owner `((select auth.uid()) = owner_user_id)`.
5. Ricreate policy owner-scoped inventory catalog/prezzi con `(select auth.uid())` per evitare initplan per-riga.
6. Rimossi i policy broad legacy su `public.history_entries`, perche la tabella non ha `owner_user_id` e Android usa `shared_sheet_sessions`.
7. Impostata `public.product_price_summary` come `security_invoker = true`.
8. Fissato `search_path` per `inventory_catalog_block_update_when_tombstoned()` e `set_updated_at()`.
9. Aggiunti indici owner/id per catalogo/prezzi e `shared_sheet_sessions_owner_remote_id_idx` su `(owner_user_id, remote_id)`.

**Verifiche live dopo migration:**
- Anon REST:
  - `inventory_suppliers`, `inventory_categories`, `inventory_products`, `inventory_product_prices`, `sync_events` -> HTTP 401 / `42501`.
  - `shared_sheet_sessions` -> HTTP 200 con 0 righe; non espone piu `owner_user_id`, `remote_id`, `data` o `session_overlay`.
  - `history_entries` -> HTTP 200 con 0 righe.
- REST autenticato con sessione emulator:
  - `inventory_suppliers`, `inventory_categories`, `inventory_products`, `inventory_product_prices`, `shared_sheet_sessions`, `sync_events` -> HTTP 200 con sample leggibile.
  - Targeted fetch prodotto e prezzo via `id=in.(...)` -> HTTP 200 con 1 riga.
  - Incremental `sync_events` dopo sample id -> HTTP 200 con 8 eventi.
- Supabase advisors dopo migration:
  - Performance: nessuna issue riportata.
  - Security: risolti `security_definer_view`, `function_search_path_mutable`, `rls_policy_always_true` su history e policy permissive/RLS initplan; resta solo WARN `auth_leaked_password_protection`, impostazione Auth dashboard non correggibile via migration SQL e non critica per il flusso Google attuale.

**Limiti residui:**
- Smoke write live non persistente/non distruttivo non eseguito: il canale SQL CLI ha avuto un circuit breaker temporaneo sul login pooler dopo query parallele; non ho insistito per evitare rumore operativo sul live.
- Smoke device offline -> reconnect -> push -> read-back autenticato resta manuale.
- Per iOS/POS futuro resta necessario definire contratto cloud versionato e, per POS multi-store, un modello `store_id`/ledger/idempotenza separato: non e corretto improvvisarlo dentro TASK-056.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | PARTIAL — remediation codice e Supabase applicata, smoke manuali/suite completa pendenti |
| Data chiusura | 2026-04-24 |
| Tutti i criteri soddisfatti? | No — codice, live RLS/security/performance e test mirati coperti; smoke manuali post-success/cancel/offline-reconnect e suite completa restano pendenti/bloccati |
| Rischi residui | Smoke manuali post-success/cancel/origin/Supabase/reconnect/offline catalogo-history ancora da eseguire; write/upsert/delete live controllato non eseguito; suite completa bloccata da ByteBuddy attach; warning Auth leaked-password protection residuo se si abilita login password |

**Conferme esplicite:**
- Supabase live modificato solo per remediation esplicitamente autorizzata: RLS owner-scoped, hardening advisor e indici; nessuna Edge Function e nessun dato applicativo distruttivo.
- Nessuna migration Room.
- Nessun cambio schema Room.
- Nessun refactor parser Excel/cloud sync; repository/DAO toccati solo per hook locali post-commit, ritorno booleano prezzo e reconnect, senza cambio schema.
- Migration SQL live applicata: `supabase/migrations/20260424190000_harden_rls_and_sync_indexes.sql`.

---

## Riepilogo finale

Applicata patch minima in `NavGraph.kt`: il ramo `ImportAnalysis` senza preview ora distingue `PreviewLoading` reale da preview assente dopo success/close/correction. Se la preview e assente e non si sta caricando, parte un redirect fallback verso la destinazione sicura invece di mostrare spinner full-screen infinito. Dopo feedback runtime, la destinazione sicura in contesto `Generated` e stata estesa anche a success completo senza errori e ad **Annulla**.

Applicata anche patch locale offline/reconnect e audit esteso: import/catalogo/history restano Room-first, le mutazioni locali notificano l'auto-sync dopo commit riuscito e catalog/history sync ripartono quando Android segnala internet validato. Push/pull full e quick/incrementale sono stati verificati staticamente nel codice e read-only sul Supabase live.

Applicata remediation live Supabase: `shared_sheet_sessions_select_public` rimossa, RLS owner-scoped ottimizzata con `(select auth.uid())`, indici owner-scoped aggiunti, `product_price_summary` e funzioni segnalate dagli advisor rimediate. Anon REST ora non espone righe `shared_sheet_sessions`; REST autenticato resta operativo.

Build, lint, `git diff --check` e test mirati richiesti sono verdi. La suite unitaria completa resta bloccata da errore ambiente/preesistente `MockK`/`ByteBuddyAgent`.

---

## Handoff

- Stato finale `PARTIAL`.
- Comandi verdi:
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug`
  - `git diff --check`
  - test mirati: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `ImportAnalyzerTest`, `ImportNavOriginTest`
- Comando bloccato:
  - `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest` -> failure ambiente `MockK`/`ByteBuddyAgent` (`AttachNotSupportedException`), ultima esecuzione finale 345 test / 121 failed / 2 skipped.
- Smoke manuale prioritario:
  1. `Generated` -> Revisione Importazione -> Conferma Importazione con 0 nuovi / 1 aggiornato / 0 errori.
  2. Confermare che dopo `DB_IMPORT APPLY_IMPORT SUCCESS` la UI torna a `Generated` o completa l'uscita gia pendente, senza schermata bianca.
  3. Da `Generated` -> Revisione Importazione, premere **Annulla** e confermare ritorno alla griglia.
  4. Ripetere da `HOME`, `DATABASE`, `HISTORY`, `GENERATED`.
  5. Verificare retry dopo errore apply.
  6. Verificare online/offline Supabase che l'auto-sync non blocchi completion locale.
  7. Spegnere internet, fare import, riaccendere internet con app in foreground e verificare push automatico dei pending.
  8. Offline: creare/aggiornare/eliminare prodotto, supplier e categoria; riconnettere e verificare push tombstone/dirty su Supabase.
  9. Offline: modificare prezzi e una `HistoryEntry`; riconnettere e verificare push catalog prices e `shared_sheet_sessions`.
  10. Eseguire manual full sync e quick sync incrementale con `sync_events` attivi, verificando metriche push/pull e fallback `manualFullSyncRequired` se viene simulato un gap.
- Supabase live remediation:
  1. `shared_sheet_sessions_select_public USING true` rimossa.
  2. Anon REST dopo migration: `shared_sheet_sessions` HTTP 200 con 0 righe, nessun dato privato esposto.
  3. REST autenticato dopo migration: catalogo/prezzi/sessions/sync_events leggibili.
  4. Performance advisors dopo migration: nessuna issue; resta solo warning Auth dashboard `auth_leaked_password_protection` se si abilita login password.
  5. Smoke write live controllato ancora da fare: offline edit/import/history -> reconnect -> push -> read back autenticato.
- Follow-up consigliato: sistemare ambiente test MockK/ByteBuddy o configurare runner compatibile, poi rieseguire `:app:testDebugUnitTest`.
