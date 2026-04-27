# TASK-066 — Fix navigazione ImportAnalysis da DatabaseScreen

---

## Informazioni generali

| Campo                 | Valore |
|----------------------|--------|
| ID                   | TASK-066 |
| Stato                | `DONE` |
| Priorità             | `ALTA` |
| Area                 | Navigazione / UX — `ImportAnalysisScreen`, `NavGraph`, `DatabaseViewModel`, flussi import da `DatabaseScreen` e sync da `GeneratedScreen` |
| Creato               | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — execution, review e fix chiuse; task `DONE` con build/lint/test/smoke documentati |

---

## Dipendenze

- **TASK-057** `DONE` (resolver uscita `GeneratedExitDestinationResolver` / `navigateByGeneratedExitRequest` — riferimento comportamento atteso per Generated)
- Nessuna dipendenza bloccante per **completare** l’`EXECUTION`

---

## Scopo

Ripristinare un comportamento **deterministico** per **ritorno** da `Screen.ImportAnalysis` (Import Review) in funzione **dell’origine reale** del flusso: import da `DatabaseScreen` (single sheet, smart/full DB, ecc.) deve sempre tornare a `DatabaseScreen` con back / Annulla / Conferma; analisi sync dalla griglia `GeneratedScreen` deve continuare a tornare a `GeneratedScreen` con aggiornamento coerente di sync/errori, senza regressioni su stato entry.

---

## Contesto

Nell’app Android, `ImportAnalysisScreen` è **condivisa** tra:

- flussi avviati da **Database** (`startSmartImport`, `startImportAnalysis` con `ImportNavOrigin.DATABASE` o default coerente);
- flussi avviati da **Generated** (`analyzeGridData` con `peekImportOriginForGeneratedSession()`).

Segnalazione utente: da Database, dopo anteprima import (anche **full database**), **back**, **Annulla** e **Conferma** non riportano in modo affidabile a `DatabaseScreen` (stack incoerente, schermata bloccata su Import Review, o riapertura). Il problema sembra emerso in concomitanza con evoluzioni su navigazione post-import/sync lato `GeneratedScreen` e resolver condivisi.

**Evidenza repo (analisi pre-codice, 2026-04-27):**

1. **Navigazione verso `Screen.ImportAnalysis`:** in `NavGraph.kt`, un `LaunchedEffect(importAnalysisResult, …)` chiama `navController.navigate(Screen.ImportAnalysis.createRoute(dbViewModel.importNavigationOrigin.value))` quando `importAnalysisResult != null` e la destinazione corrente non è già la route di analisi. Va verificato l’assenza di **loop** se, dopo `popBackStack` / uscita, `importAnalysisResult` resta non-null abbastanza a lungo da **riattivare** la navigazione.

2. **Valorizzazione `importAnalysisResult` e origine:** `DatabaseViewModel.publishPreviewAnalysis(…, navigationOrigin)` imposta `_importNavigationOrigin` e `_importAnalysisResult`. `startSmartImport` / `startImportAnalysis` / `finalizeFullImportAnalysisSuccess` propagano l’origine. `clearPendingImportState` azzera anche `_importNavigationOrigin` a `HOME` se `clearAnalysisResult` è true.

3. **Concetto di origine (decisione planning):** si **riutilizza** `ImportNavOrigin` in `ImportNavOrigin.kt` (`HOME`, `HISTORY`, `DATABASE`, `GENERATED`) e `_importNavigationOrigin` in `DatabaseViewModel`. L’argomento di rotta in `Screen.ImportAnalysis.createRoute(origin)` resta l’unica fonte di origine per la navigazione; nessun enum duplicato (vedi **Decisioni**).

4. **Perché Database può finire su Generated (ipotesi verificata in codice):** in `NavGraph.kt`, `hasGeneratedImportContext` è `importOrigin != ImportNavOrigin.DATABASE && currentEntryUid != 0L`. Per origine `DATABASE` le azioni usano ancora `navigateByGeneratedExitRequest(…, importOrigin, …)` con `entryUid` preso da `ExcelViewModel`. In `GeneratedExitDestinationResolver`, per `ImportCancel` e `CorrectRows` la risoluzione passa da `generatedOrRecover`: se `entryUid != null` e > 0, la destinazione diventa **`Generated`**, altrimenti fallback che per `DATABASE` include `DatabaseRoot`. Se l’`ExcelViewModel` mantiene un **`currentEntryUid` residuo** da sessioni precedenti, un cancel da flusso Database può **erroneamente** risolvere verso `Generated` invece che verso `DatabaseRoot` — coerente con la regressione descritta.

5. **`clearImportAnalysis()`:** delega a `cancelImportPreview()` / `dismissImportPreview()` a seconda di `importFlowState`; in `Applying` non fa null. Va verificato l’ordine rispetto a `importProducts()`: **non** pulire lo stato pending (full import, `pendingPriceHistory`, supplier/category temporanei) **prima** che `importProducts` abbia acquisito ciò che serve. Eventuale separazione (solo null su `_importAnalysisResult` vs clear completo) va valutata solo se necessaria ai criteri di accettazione.

6. **Back di sistema:** se non coperto, `BackHandler` in `ImportAnalysisScreen` o wrapper route deve inoltrare alla stessa logica di **cancel contestuale** per origine.

---

## Non incluso

- Modifiche a **schema Room**, migrazioni, DAO, entity, repository o parser Excel **salvo** stretta necessità tecnica per il task (vincolo: evitare).
- Cambi alla **logica business** di import/export full DB, contenuto fogli, PriceHistory, Suppliers, Categories, Products.
- **Redesign** UI di `ImportAnalysisScreen` oltre a icon back / `BackHandler` se necessari per allineare back a cancel.
- **Non** rimuovere la schermata condivisa `ImportAnalysis` né **duplicarla** in una seconda route.
- **Non** introdurre un nuovo enum `ImportAnalysisSource` / `ImportAnalysisOrigin` parallelo a `ImportNavOrigin` (vincolo allineato a **Decisioni**).

---

## File potenzialmente coinvolti

- `app/src/main/java/.../ui/navigation/NavGraph.kt` — **solo se necessario** dopo il resolver: `LaunchedEffect(importAnalysisResult, pendingImportAnalysisExitCleanup)`, `pendingImportAnalysisExitCleanup`, wiring uscite; **evitare** fix sparse che azzerano o riscrivono `entryUid` in più punti; preferire decisione centralizzata nel **resolver** + test JVM.
- `app/src/main/java/.../ui/navigation/GeneratedExitDestinationResolver.kt` — **file primario** della fix: regole su `request.origin == DATABASE` vs reason ImportAnalysis (vedi **Piano di esecuzione**).
- `app/src/main/java/.../ui/navigation/ImportNavOrigin.kt` / `Screen.kt` — **non** estendere l’enum salvo scoperta reale in execution documentata nel log (default: nessun cambio file).
- `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` — verifica `importNavigationOrigin` sui path da Database; **nessuna** session object `ImportAnalysisSession` salvo scoperta reale e documentata in execution.
- `app/src/main/java/.../ui/screens/DatabaseScreen.kt` — verifica che ogni avvio import passi l’origine attesa (oggi `startSmartImport(context, uri)` usa default `DATABASE` — confermare e allineare eventuali altre chiamate).
- `app/src/main/java/.../ui/screens/GeneratedScreen.kt` — nessun cambio logica sync se non necessario; verifica `analyzeGridData(..., peekImportOriginForGeneratedSession())`.
- `app/src/main/java/.../ui/screens/ImportAnalysisScreen.kt` — `BackHandler` / allineamento back toolbar a `onClose`.
- `app/src/test/.../GeneratedExitDestinationResolverTest.kt` — **obbligatorio** se si modifica `GeneratedExitDestinationResolver` (matrice sotto in **Criteri** / test).
- `app/src/test/.../DatabaseViewModelTest.kt` e test import esistenti — aggiornamenti mirati solo se il comportamento testabile in VM cambia (baseline TASK-004 ove applicabile).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Database → import (single sheet) → Import Review → **Cancel** → `DatabaseScreen`; **nessuna** riapertura automatica di Import Review | M | ✅ ESEGUITO — smoke emulator single sheet: Cancel torna a Database, Import Review non riaperta |
| 2 | Stesso flusso → **back di sistema** → `DatabaseScreen`; **nessuna** riapertura automatica di Import Review | M | ✅ ESEGUITO — smoke emulator: `KEYCODE_BACK` torna a Database, nessun loop |
| 3 | Stesso flusso → **Conferma** (success) → `DatabaseScreen` con feedback (lista/snackbar) coerente; **nessun** blocco su Import Review dopo navigazione | M | ✅ ESEGUITO — smoke emulator con single sheet valido: `APPLY_IMPORT SUCCESS`, ritorno a Database |
| 4 | Se **top bar** `navigationIcon` back (polish opzionale): stesso comportamento di **Cancel** (Database), disabilitata durante `Applying` | M | ✅ ESEGUITO — micro-polish applicato; smoke top bar back torna a Database; `enabled = !isApplying` |
| 5 | Database → Import Review con errori → **Correggi dati** / flusso “correct rows” (copy esistente) → chiude la preview nel **flusso Database**; **non** apre `GeneratedScreen` con `entryUid` residuo | M | ✅ ESEGUITO — smoke emulator con barcode mancante: `Fix data` torna a Database, non Generated |
| 6 | Generated → Import Review con errori → **Correggi dati** → ritorno alla griglia `GeneratedScreen` con `errorRowIndexes` valorizzato come oggi (nessuna regressione) | M | ✅ ESEGUITO — smoke emulator: sync da griglia con errore → `Edit rows` torna a griglia con filtro `Errors only = 1` |
| 7 | Database → **import database completo** → Import Review → Cancel / back / Conferma come criteri 1–3; applicazione Products/Suppliers/Categories/PriceHistory **invariata** rispetto al baseline | M + JVM ove coperto | ✅ ESEGUITO — codice full import non modificato; navigazione Database verificata su preview DB; full JVM verde |
| 8 | **Apply error** (da Database o Generated): preview / pending state **recuperabili** (`recoverImportPreviewAfterApplyError` o equivalente); nessuna navigazione distruttiva che invalidi il recovery documentato | M | ✅ ESEGUITO — review statica + JVM: `recoverImportPreviewAfterApplyError` preserva preview/pending; nessuna navigazione distruttiva aggiunta |
| 9 | Durante `ImportFlowState.Applying`: back sistema, top bar back (se presente) e **Cancel**/**Close** disabilitati o **no-op** sicuri (nessuna doppia apply / nessun pop incoerente) | M | ✅ ESEGUITO — review statica: `BackHandler` e top bar/bottom action condizionati da `!isApplying`; full JVM copre doppia apply |
| 10 | Conferma da **Database**: chiama `importProducts(...)`; **non** pulisce pending full import / `pendingPriceHistory` / stato necessario **prima** che `importProducts` abbia consumato i dati; dopo success → Database | M + S | ✅ ESEGUITO — call path invariato; smoke success Database; `clearImportAnalysis()` non anticipato |
| 11 | Conferma da **Generated**: `syncStatus` / `errorRowIndexes` (se errori) coerenti con UX sync esistente; nessun salto a Database per stale `entryUid` quando l’origine del flusso è Generated | M | ✅ ESEGUITO — route Generated non cambiata; smoke errori preserva griglia/error indexes; resolver test copre `GENERATED` |
| 12 | Generated → sync / Import Review → **Cancel** / **back** → `GeneratedScreen`, **non** Database | M | ✅ ESEGUITO — smoke emulator: Cancel da review Generated torna alla griglia, non Database |
| 13 | **No-loop / post-uscita:** dopo uscita intenzionale da ImportAnalysis, `importAnalysisResult` (e ordine con `pendingImportAnalysisExitCleanup`) **non** deve innescare una **nuova** `navigate` verso `ImportAnalysis` senza azione utente | M + S | ✅ ESEGUITO — smoke Cancel/back/top-back/Fix/Confirm senza riapertura; cleanup esistente preservato |
| 14 | **Invariante `entryUid`:** con `importOrigin == DATABASE` (rotta + stato VM coerente), nessun `entryUid` stale in `ExcelViewModel` può spostare la destinazione su **Generated** (Cancel / CorrectRows / Success / Missing*) | S + M | ✅ ESEGUITO — resolver patch + test matrice stale uid; smoke Database resta su Database |
| 15 | `HOME` / `HISTORY`: comportamento attuale delle reason ImportAnalysis **preservato** salvo bug documentato | M | ✅ ESEGUITO — resolver non altera fallback origin diversi da `DATABASE`; test HOME/HISTORY verdi |
| 16 | **Nessuna regressione** import/export full DB oltre criteri sopra; nessun cambio schema Room / migration | S + M | ✅ ESEGUITO — nessuna modifica Room/DAO/entity/repository/parser; build/lint/JVM verdi |
| 17 | `./gradlew assembleDebug` | B | ✅ ESEGUITO — `BUILD SUCCESSFUL` |
| 18 | `./gradlew lintDebug` | S | ✅ ESEGUITO — `BUILD SUCCESSFUL`; solo warning toolchain/AGP preesistenti |
| 19 | Con modifica al resolver: `GeneratedExitDestinationResolverTest` (matrice minima sotto) **+** `testDebugUnitTest` o suite mirata documentata; `DatabaseViewModelTest` se VM toccata | B | ✅ ESEGUITO — test resolver mirati + `testDebugUnitTest --no-daemon` full suite verdi |
| 20 | Verifica esplicita: Database → Import Review → **Cancel** → Database e **nessuna** riapertura di Import Review (cfr. #1) | M | ✅ ESEGUITO — smoke emulator dedicato |
| 21 | Verifica esplicita: stesso per **back di sistema** e, se **top bar** back presente, stesso di Cancel (cfr. #2, #4) | M | ✅ ESEGUITO — smoke emulator dedicato per system back e top bar back |
| 22 | Verifica esplicita: Database → **Correggi dati** / correct rows → **Database**, mai **Generated** per `entryUid` residuo (cfr. #5) | M | ✅ ESEGUITO — smoke emulator dedicato con error row |
| 23 | Verifica esplicita: Conferma **success** → Database, feedback coerente; **apply error** → stato recuperabile (cfr. #3, #8, #10) | M | ✅ ESEGUITO — smoke success Database + review statica/JVM per apply error recovery |
| 24 | Verifica esplicita: Generated → Cancel / CorrectRows / Conferma → comportamento invariato, `syncStatus` coerente (cfr. #6, #11, #12) | M | ✅ ESEGUITO — smoke Generated Cancel/CorrectRows; Confirm path preservato da static review/full JVM |
| 25 | Invariante: con `importOrigin == DATABASE`, **nessun** `entryUid` stale influisce sulla destinazione (cfr. #14) | S + M | ✅ ESEGUITO — test JVM diretto + smoke manuale Database |

Legenda: B=Build, S=Static, M=Manuale, E=Emulatore (non richiesto salvo estensione task).

#### Test JVM obbligatori sul resolver (se si tocca `GeneratedExitDestinationResolver`)

**Obbligatorio:** creare o aggiornare `app/src/test/.../GeneratedExitDestinationResolverTest.kt` con almeno la matrice in **Test JVM obbligatori (resolver)** (sezione Planning). Questo test **blocca** la regressione da **`entryUid` stale**; non sostituisce smoke UI.

#### Test e check Gradle (in execution, da documentare in `Execution`)

- `./gradlew assembleDebug`, `./gradlew lintDebug` (criteri #17–#18).
- Suite JVM: `GeneratedExitDestinationResolverTest` se resolver modificato; `DatabaseViewModelTest` / altri se VM o import toccati (baseline **TASK-004** ove applicabile; criterio #19).

> Definition of Done — task UX/nav (vedi `docs/MASTER-PLAN.md`): nessun cambio business/DAO oltre il necessario; build/lint verdi; smoke manuale documentato in `Execution` **durante** il lavoro svolto.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|------------|------------|------|
| 1 | **Fonte unica di “origine flusso”:** riusare `ImportNavOrigin` esistente (`HOME`, `HISTORY`, `DATABASE`, `GENERATED`) e `Screen.ImportAnalysis.createRoute(origin)`; **non** introdurre `ImportAnalysisSource` / `ImportAnalysisOrigin`. | L’app ha già route args stabili; duplicare il concetto aumenta complessità senza beneficio. | 2026-04-27 |
| 2 | **Nessuna** session object tipo `ImportAnalysisSession(analysis, origin)` **salvo** scoperta reale in corso d’`EXECUTION` (documentata nel log) che renda obbligatoria una composizione aggiuntiva. Default: **nessun** nuovo tipo sessione. | Minimo cambiamento; `publishPreviewAnalysis` + `importNavigationOrigin` sono già accoppiati. | 2026-04-27 |
| 3 | **Fix preferita (ordine vincolante di intento):** patch **minima** e **centralizzata** in `GeneratedExitDestinationResolver` per le regole `ImportNavOrigin.DATABASE` × reason (vedi tabelle sotto). | Una sola fonte di verità per le destinazioni. | 2026-04-27 |
| 4 | **`NavGraph.kt`:** intervenire **solo** se necessario per wiring, **cleanup** globale o **no-loop** (`LaunchedEffect(importAnalysisResult, …)` / `pendingImportAnalysisExitCleanup`); **non** come sostituto del fix nel resolver. | Evita fix sparse e doppia fonte di verità. | 2026-04-27 |
| 5 | **Evitare** manipolazione manuale ripetuta di `entryUid` in più punti del `NavGraph`. La regola d’uscita per `DATABASE` vive **nel resolver**; eventuale helper **unico** interno, non N call site. | Stesso obiettivo della riga 4; riduce regressioni su `ExcelViewModel`. | 2026-04-27 |
| 6 | **Scelta implementativa** (`navigate` a `DatabaseRoot` vs `popBackStack` mirato, `launchSingleTop`, ecc.): a carico dell’esecutore, purché rispettati criteri di accettazione e stack coerente; **vietato** compensare con workaround distribuiti su `entryUid`. | Flessibilità tecnica; vincolo su risultato, non su API unica. | 2026-04-27 |

---

## Planning (Claude)

### Analisi (riepilogo)

- L’app già differenzia `hasGeneratedImportContext` per `ImportNavOrigin.DATABASE` ma le **uscite** passano attraverso `navigateByGeneratedExitRequest` + `GeneratedExitDestinationResolver`, dove **`ImportCancel` / `CorrectRows` ignorano `destinationForOrigin`** e usano `generatedOrRecover`, esponendosi a **`entryUid` non nullo** da `ExcelViewModel` anche in flusso Database — **root cause** principale documentata.
- Rischio secondario: `LaunchedEffect(importAnalysisResult, pendingImportAnalysisExitCleanup)` che **naviga** a `ImportAnalysis` se `importAnalysisResult != null` — da allineare all’ordine di clear/dismiss e al meccanismo `pendingImportAnalysisExitCleanup` per **no-loop** (vedi sotto).

### Regola tecnica centrale (resolver)

Quando `request.origin == ImportNavOrigin.DATABASE`, le **reason** legate al flusso ImportAnalysis devono **ignorare** qualsiasi `entryUid` (anche “valido” ma **stale** rispetto al flusso Database) e risolvere **sempre** verso `GeneratedExitDestination.DatabaseRoot` o un **fallback equivalente** a DatabaseRoot (es. `RecoverableError` il cui `fallback` risolve a DatabaseRoot), secondo le tabelle sotto. **Non** forzare Database per `GENERATED` / `HOME` / `HISTORY` se non qui specificato (preservare comportamento attuale per quegli origin).

| Reason | `DATABASE` (entryUid qualsiasi) | Note |
|--------|------------------------------|------|
| `ImportCancel` | `DatabaseRoot` | **Mai** `Generated` per stale uid |
| `CorrectRows` | `DatabaseRoot` | **Mai** `Generated` per stale uid |
| `ImportSuccess` | `DatabaseRoot` | Già `destinationForOrigin`; confermare assenza di ramo parallelo che usa uid |
| `MissingPreview` | `RecoverableError` → **fallback = DatabaseRoot** (o destinazione unificata a DB) | Oggi `recoverableFallbackForOrigin` già mappa `DATABASE` → `DatabaseRoot`; allineare se il tipo wrappato differisce |
| `MissingSession` | come `MissingPreview` | Stesso `recoverableFallbackForOrigin` |

| Origin | `ImportCancel` / `CorrectRows` con `entryUid` valido |
|--------|------------------------------------------------------|
| `GENERATED` | `Generated` (comportamento attuale) — **mantenere** |
| `HOME` / `HISTORY` | Comportamento attuale **salvo** bug documentato — **non** alterare in questo task salvo prova di regressione |

### Piano di esecuzione (ordine obbligato in intento)

1. **Correggere** `GeneratedExitDestinationResolver` applicando la regola centrale (tabella sopra) e i casi `MissingPreview` / `MissingSession` per `DATABASE` + uid stale.
2. **Aggiungere** (o estendere) test JVM puro: `GeneratedExitDestinationResolverTest` — matrice minima obbligatoria (vedi sotto). Questo test **blocca** la regressione su `entryUid` stale.
3. **Solo se necessario** dopo verde test + smoke mirato: aggiustare `NavGraph` (cleanup, ordine con `pendingImportAnalysisExitCleanup`, condizioni su `LaunchedEffect(importAnalysisResult, …)`) per **no-loop** e coerenza con `clearImportAnalysis` / `dismissImportPreview`.
4. **Opzionale ma consigliato (micro UX):** `ImportAnalysisScreen` — `navigationIcon` in `TopAppBar` con **stesso handler** di `onClose`; disabilitare l’azione durante `isApplying` / `ImportFlowState.Applying`; nessun redesign; nessun cambio logica import. Scopo: allineamento visivo a Material3, back = cancel contestuale.

**Verifica call site (non sostituisce il resolver):** confermare `importNavigationOrigin` / `startSmartImport` / `startImportAnalysis` / `analyzeGridData` (Database default `DATABASE`, Generated con `peekImportOriginForGeneratedSession()`) e che **nessuna** uscita Database dipenda da patch manuali a `entryUid` nel `NavGraph`.

### Applying, Confirm da Database/Generated, apply error

- **`ImportFlowState.Applying`:** back di sistema, eventuale `navigationIcon` back, **Cancel** / **Close** devono essere **disabilitati** o equivalenti a **no-op** sicuro (nessuna navigazione parziale, nessuna doppia `importProducts`). Allineare a `DatabaseViewModel.clearImportAnalysis()` che in `Applying` **non** esegue clear distruttivo.
- **Confirm da Database:** percorso esistente `dbViewModel.importProducts(...)`; **non** chiamare `clearImportAnalysis()` / `clearPendingImportState` in modo da perdere `pendingPriceHistory` o pending full import **prima** che `importProducts` abbia materializzato/consumato ciò che serve. Dopo **success** uscita verso **Database** (come oggi `ImportSuccess` + cleanup `DismissPreview`); dopo **apply error** mantenere recovery (`recoverImportPreviewAfterApplyError`) senza **navigazione distruttiva** che impedisca il corretto flusso di correzione.
- **Confirm da Generated:** preservare aggiornamento `syncStatus` e, se errori, `errorRowIndexes` e comportamento attuale dello sync; **nessun** adeguamento funzionale oltre quanto necessario al fix navigazione per `DATABASE`.

### Cleanup / no-loop (rafforzato)

- **Preservare** `pendingImportAnalysisExitCleanup` (`CancelPreview` vs `DismissPreview`) e l’`LaunchedEffect` secondario che applica `clearImportAnalysis()` / `dismissImportPreview()` **dopo** che la rotta non è più `ImportAnalysis` — evitare doppi clear o clear anticipati inutili.
- **Non** chiamare `clearImportAnalysis()` in un ordine che **cancelli** stato pending **prima** che `importProducts` (o l’apply full DB) abbia acquisito i dati necessari.
- Dopo **ogni** uscita intenzionale da ImportAnalysis (Cancel / back / Conferma post-success / CorrectRows da Database con destinazione DB), l’**ordine** di aggiornamento dello stack + null su `importAnalysisResult` (via cleanup corrente) deve impedire che il **primo** `LaunchedEffect` riconduca l’utente a `importAnalysis` senza un nuovo trigger utente.
- **Criterio di verifica esplicito:** dopo uscita intenzionale, `importAnalysisResult` (non nullo) **non** deve da solo innescare una nuova `navigate(Screen.ImportAnalysis.…)` — salvo un nuovo import/avvio analisi da parte dell’utente.

### Test JVM obbligatori (resolver)

Se si tocca `GeneratedExitDestinationResolver`, **obbligatorio** creare o aggiornare `GeneratedExitDestinationResolverTest` con almeno:

| # | Input | Destinazione attesa |
|---|--------|---------------------|
| 1 | `DATABASE` + `ImportCancel` + `entryUid` valido (>0) | `DatabaseRoot` |
| 2 | `DATABASE` + `CorrectRows` + `entryUid` valido | `DatabaseRoot` |
| 3 | `DATABASE` + `ImportSuccess` + `entryUid` valido | `DatabaseRoot` |
| 4 | `DATABASE` + `MissingPreview` + `entryUid` valido | `RecoverableError` con `fallback == DatabaseRoot` (o struttura equivalente se il resolver unifica) |
| 5 | `DATABASE` + `MissingSession` + `entryUid` valido | come riga 4 |
| 6 | `GENERATED` + `ImportCancel` + `entryUid` valido | `Generated` |
| 7 | `GENERATED` + `CorrectRows` + `entryUid` valido | `Generated` |
| 8 | `HISTORY` + `ImportSuccess` | `HistoryRoot` |
| 9 | `HOME` + `ImportSuccess` | `NewExcelDestination` |

**Nota:** obbligatorio perché vincola direttamente la regressione su **`entryUid` stale** senza dipendere da test UI.

### Micro-polish UX (opzionale, consigliato)

- Consentito in execution: `navigationIcon` + stesso `onClose`; disabilitare durante apply; nessun redesign; scopo: coerenza Material3 e affordance back = cancel.

### Rischi identificati (aggiornato)

| Rischio | Mitigazione |
|--------|-------------|
| Fix **solo** nel `NavGraph` senza correggere il resolver | **Non accettabile** come strategia primaria: patch nel **resolver** + `GeneratedExitDestinationResolverTest` obbligatorio se toccato il file. |
| `Back` / `navigationIcon` aggiunta ma **attiva** durante `Applying` | Disabilitare icona o route back; stesso criterio per **Cancel** (vedi criteri accettazione #9, #4). |
| `clearImportAnalysis()` / `dismiss` **troppo anticipati** rispetto a `importProducts` o full import pending | Ordine e cleanup solo dopo consumo; criteri #10, #8; preservare meccanismo `Applying`. |
| Regressione **sync Generated** (Cancel/CorrectRows/Conferma) | Test resolver righe 6–7; smoke manuale Generated; non alterare rami `GENERATED` oltre tabella. |
| Fix resolver che altera **HOME/HISTORY** senza intenzione | Preservare `destinationForOrigin` / `recoverableFallbackForOrigin` per quegli origin; test riga 8–9. |
| Race `LaunchedEffect` vs stato dopo `pop` | Aggiustare solo in `NavGraph` **dopo** resolver+test; criterio #13. |
| `ExcelViewModel` state stale | Risolto **nel resolver** ignorando `entryUid` per `DATABASE` nelle reason indicate — **non** come prima scelta N fix nel `NavGraph`. |

---

## Execution

### Esecuzione — 2026-04-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt` — fix centrale: `DATABASE + ImportCancel/CorrectRows` ignora `entryUid` stale e risolve a `DatabaseRoot`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolverTest.kt` — aggiunta matrice JVM obbligatoria per `DATABASE` stale uid, `GENERATED`, `HOME`, `HISTORY`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — micro UX locale: `navigationIcon` Material3 con stesso `onClose`, disabilitata durante `isApplying`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — fix no-loop/restore: navigazione `DatabaseRoot` senza `saveState`/`restoreState` per evitare ripristino della route `ImportAnalysis`.
- `docs/TASKS/TASK-066-fix-importanalysis-database-return-navigation.md` — log execution/review/fix/chiusura e criteri aggiornati.
- `docs/MASTER-PLAN.md` — governance riallineata a `TASK-066 DONE`, nessun task attivo.

**Azioni eseguite:**
1. Letti `docs/MASTER-PLAN.md`, il file task e il codice coinvolto (`GeneratedExitDestinationResolver`, `NavGraph`, `ImportAnalysisScreen`, path VM/import rilevanti).
2. Applicata patch minima nel resolver: solo le reason `ImportCancel` / `CorrectRows` usano il nuovo helper, con override esclusivo per `ImportNavOrigin.DATABASE`.
3. Esteso `GeneratedExitDestinationResolverTest` con la matrice richiesta: `DATABASE + stale entryUid` non torna mai a `Generated`; `GENERATED + entryUid` preserva `Generated`; `HOME`/`HISTORY` preservati.
4. Aggiunto micro-polish UX consentito in `ImportAnalysisScreen`: top bar back visibile, stesso handler di `Cancel`, disabilitata durante `Applying`.
5. Durante smoke manuale è emerso che `DatabaseRoot` veniva risolto correttamente ma `NavGraph` ripristinava la route salvata di `ImportAnalysis` per via di `saveState/restoreState`.
6. Applicato fix post-review in `NavGraph`: `DatabaseRoot` naviga a `Screen.Database.route` senza salvare/ripristinare lo stato del child `ImportAnalysis`.
7. Verificato che non sono stati modificati Room, DAO, entity, repository, parser Excel, build config o schema DB.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → `BUILD SUCCESSFUL`; warning AGP/toolchain preesistenti |
| Warning nuovi | ✅ ESEGUITO | `git diff --check` verde prima della chiusura codice; nessun nuovo warning Kotlin/lint introdotto |
| Coerenza con planning | ✅ ESEGUITO | Ordine rispettato: resolver → test resolver → micro UX → NavGraph solo dopo evidenza no-loop/restore |
| Criteri di accettazione | ✅ ESEGUITO | 25/25 criteri chiusi; dettagli nella tabella sopra e nelle evidenze smoke sotto |

**Test JVM / Gradle:**
- `GeneratedExitDestinationResolverTest` + `GeneratedExitDestinationMatrixTest` mirati:
  `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest'` → `BUILD SUCCESSFUL`.
- Full JVM baseline:
  `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --stop && JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon testDebugUnitTest` → `BUILD SUCCESSFUL`.
- Nota: un primo tentativo full JVM su daemon già attivo con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true'` ha fallito per attach MockK/ByteBuddy nel worker Robolectric; rieseguito con daemon spento e `--no-daemon`, suite completa verde.

**Smoke emulator/manuale:**
- Device: `emulator-5554`, Android 15 (`sdk_gphone64_arm64`), APK debug reinstallato con `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Database + preview 0 changes: top bar back → `DatabaseScreen`; Import Review non riaperta.
- Database + preview 0 changes: bottom `Cancel` → `DatabaseScreen`; Import Review non riaperta.
- Database + preview 0 changes: system back (`KEYCODE_BACK`) → `DatabaseScreen`; log resolver `origin=DATABASE reason=ImportCancel ... destination=DatabaseRoot`.
- Database + single sheet valido (`/sdcard/Download/Task066_single_import_success.xlsx`, foglio `Items`): `Confirm Import` → `APPLY_IMPORT SUCCESS`, ritorno a `DatabaseScreen`; log resolver `origin=DATABASE reason=ImportSuccess ... destination=DatabaseRoot`.
- Database + single sheet con errore (`/sdcard/Download/Task066_single_import_error.xlsx`): Import Review mostra `Errors found: 1`, `Row 1: Barcode is required`; `Fix data` → `DatabaseScreen`, non `Generated`.
- Generated + griglia con errore: `Sync with database` → Import Review; `Edit rows` → ritorno alla griglia con `Errors only = 1`; log resolver `reason=CorrectRows ... destination=Generated(...)`.
- Generated + griglia con errore: `Sync with database` → Import Review; `Cancel` → ritorno alla griglia Generated, non Database; log resolver `reason=ImportCancel ... destination=Generated(...)`.

**Baseline regressione TASK-004 (se applicabile):**
- Applicabile: il task tocca navigazione import/sync e analisi import, aree collegate ai test JVM introdotti/rafforzati da TASK-004.
- Test eseguiti: suite mirata resolver + `testDebugUnitTest` completa.
- Test aggiunti/aggiornati: `GeneratedExitDestinationResolverTest` esteso con matrice TASK-066.
- Limiti residui: smoke manuale Generated Confirm success non ripetuto come percorso device dedicato; il call path è invariato e coperto da full JVM/static review, mentre Cancel/CorrectRows Generated sono stati verificati manualmente.

**Incertezze:**
- Nessuna incertezza bloccante.
- Nota fuori perimetro: un workbook temporaneo incompleto con sheet name `Products` ha attivato il rilevatore full DB e loggato una `NullPointerException` in `FullDbImportStreaming.kt:638`; non è causato dal fix TASK-066 e non sono stati toccati parser/full import in questo task.

**Handoff notes:**
- La fix effettiva è intenzionalmente piccola: resolver per la decisione, NavGraph solo per impedire il restore della route `ImportAnalysis`.
- Il polish UI è locale e tracciato: top app bar back, stesso handler di `Cancel`, disabilitato durante `Applying`.

---

## Review

### Review — 2026-04-27

**Esito iniziale:** FIX REQUIRED.

**Finding:**
- Durante smoke Database, `GeneratedExitDestinationResolver` risolveva correttamente `origin=DATABASE reason=ImportCancel` verso `DatabaseRoot`, ma la UI restava su `ImportAnalysisScreen`.
- Root cause repo-grounded: il ramo `GeneratedExitDestination.DatabaseRoot` in `NavGraph.kt` usava `popUpTo(startRoute) { saveState = true }` + `restoreState = true`; questo poteva ripristinare la route salvata `ImportAnalysis` sotto il tab Database, vanificando la destinazione corretta.

**Seconda review dopo fix: APPROVED.**
- Resolver centralizzato corretto e coperto da test.
- `NavGraph` toccato solo per cleanup/no-loop, come consentito dal piano.
- `ImportAnalysisScreen` contiene solo micro UX locale consentita.
- Nessun cambio a Room/DAO/entity/repository/parser Excel.
- Build/lint/test/smoke coerenti con criteri.

---

## Fix

### Fix — 2026-04-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — nel ramo `DatabaseRoot`, impostato `saveState = false` e `restoreState = false`.

**Azioni eseguite:**
1. Riprodotto il problema post-resolver con smoke emulator: destinazione loggata `DatabaseRoot`, ma UI ripristinata su Import Review.
2. Applicato fix minimo nel solo ramo `DatabaseRoot`.
3. Ripetuti smoke Database top bar back, Cancel, system back, Confirm success e Fix data: tutti tornano a Database senza riapertura Import Review.
4. Ripetuti test resolver/full JVM/build/lint dopo il fix.

**Esito:** FIX APPROVED.

---

## Chiusura

### Chiusura — 2026-04-27

**Stato finale:** `DONE`.

**Criteri:** 25/25 soddisfatti e documentati.

**Verifiche finali:** `assembleDebug`, `lintDebug`, suite resolver mirata, `testDebugUnitTest` completa, `git diff --check`, smoke emulator Database e smoke Generated essenziali.

**Scope rispettato:** nessun refactor architetturale, nessuna nuova dipendenza, nessun cambio Room/DAO/entity/repository/parser Excel.

---

## Riepilogo finale

TASK-066 chiude la regressione di navigazione da `ImportAnalysisScreen`:

- Da `DATABASE`, le reason ImportAnalysis ignorano `entryUid` stale e risolvono verso `DatabaseRoot`.
- Da `GENERATED`, il ritorno alla griglia con `entryUid` valido resta preservato.
- `NavGraph` non ripristina più la route `ImportAnalysis` quando la destinazione richiesta è `DatabaseRoot`.
- `ImportAnalysisScreen` ha ora una back icon Material3 coerente con `Cancel`, disabilitata durante `Applying`.

Il task è chiuso in `DONE` con evidenze JVM, Gradle e smoke emulator.

---

## Handoff

- **TASK-066** è `DONE`.
- Patch finale minima:
  1. resolver;
  2. test resolver;
  3. micro UX back icon;
  4. wiring `NavGraph` solo per cleanup/no-loop emerso in smoke.
- Non sono stati fatti refactor architetturali.
- Non sono stati modificati Room, DAO, entity, repository o parser Excel.
- `ImportAnalysisScreen` non è stata duplicata.
- Nota fuori perimetro da valutare in task separato se utile: workbook non-export con sheet name `Products` può essere scambiato per full DB import e arrivare a NPE in `FullDbImportStreaming.kt:638`.
