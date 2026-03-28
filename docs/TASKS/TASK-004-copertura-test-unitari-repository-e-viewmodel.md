# TASK-004 — Copertura test unitari — Repository e ViewModel

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-004                   |
| Stato                | DONE                       |
| Priorità             | ALTA                       |
| Area                 | Test / Qualità             |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-28 (execution completata: audit wiring runtime, completamento suite test, verifiche finali) |
| Tracking `MASTER-PLAN` | **`DONE`** (chiusura esecutiva completata; nessun nuovo task attivato dall’executor) |

**Nota tracking:** nel `MASTER-PLAN` questo task risulta chiuso in **`DONE`** dopo completamento codice + test + verifiche. Nessun nuovo task è stato attivato in questa esecuzione.

**Nota cronologia:** eventuali date di planning **anteriorsi** a **`Creato` (2026-03-27)** in bozze intermedie erano incoerenti e **non** valgono come timeline del task. **2026-03-30** = factory runtime + allineamento (**Decisione 6–7**). **2026-03-31** = micro-ottimizzazioni owner unico, regola timestamp, preferenza D8 POI (**`Ultimo aggiornamento`**).

---

## Dipendenze

- **TASK-001** (`DONE`) — governance e baseline
- **TASK-003** (`DONE`, 2026-03-27) — decomposizione `DatabaseScreen`
- **TASK-020** (`DONE`, 2026-03-28) — cleanup code analysis post-TASK-003 completato; **TASK-004** riattivato come unico **`ACTIVE`** nel `MASTER-PLAN`

---

## Scopo

Introdurre **test unitari** (JUnit su JVM o configurazione equivalente approvata) per:

- **`DefaultInventoryRepository`** — operazioni repository usate dall’app (CRUD e percorsi principali esposti dalla classe in `InventoryRepository.kt`).
- **`DatabaseViewModel`** — stato `UiState`, filtri, operazioni CRUD/import/export esposte al layer UI (con **doppi/test doubles** per il repository o Room, senza spostare business logic fuori dal ViewModel).
- **`ExcelViewModel`** — flussi principali di parsing/history/export **testabili** in unità (mock di dipendenze pesanti come DB o context dove necessario).

Obiettivo: **copertura minima significativa** (non template vuoti), allineata al `MASTER-PLAN`, che riduca il rischio di regressioni su path critici già noti (CRUD, import analysis, export).

---

## Contesto

La repo ha oggi soprattutto test template di default; **TASK-004** e **TASK-005** nel `MASTER-PLAN` coprono la qualità. Questo task colpisce il **cuore dati + ViewModel** usati da `DatabaseScreen`, Excel flow e navigazione. **TASK-017** (OOM import) è **`DONE`** — i test qui **non** sostituiscono test di carico/OOM su file enormi salvo esplicito ampliamento scope.

---

## Non incluso

- Test strumentati Android UI / Compose end-to-end (salvo richiesta futura).
- Copertura completa di ogni ramo di `ExcelUtils` / `ImportAnalysis` → **TASK-005**.
- Modifiche funzionali a prodotti, schema Room, API pubbliche repository **salvo** minimo indispensabile per testabilità **documentato** e approvato.
- CI/CD → **TASK-012**.
- Esecuzione in questo turno: **nessun** avvio **EXECUTION** finché il planning non è approvato.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `DefaultInventoryRepository` (sorgente sotto test o rifattor minimo per test doubles)
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — ctor + **companion `Factory`** (o equivalente) se scelta quella forma
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — come sopra
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — `viewModel(factory = …)` per le due VM
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — **rimuovere default `= viewModel()`** dal parametro `viewModel: DatabaseViewModel` (riga 47); il call-site in `NavGraph.kt:170` **già** passa `dbViewModel` esplicitamente, quindi il fix è solo rimozione del default, non aggiunta di wiring
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — contiene `object ImportAnalyzer` con `analyze()` e `analyzeStreaming()` (riferimento per D6/D7; **file non da modificare**, solo per sapere dove vive la dipendenza statica)
- `app/src/test/java/...` — nuovi file test (es. `DefaultInventoryRepositoryTest.kt`, `DatabaseViewModelTest.kt`, `ExcelViewModelTest.kt`; nomi definitivi in EXECUTION)
- `app/build.gradle.kts` + `gradle/libs.versions.toml` — dipendenze test (JUnit4, MockK, Coroutines Test, Turbine, Robolectric, `androidx.arch.core:core-testing`, AndroidX Test) **solo se** mancanti e approvate; oggi `libs.versions.toml` ha solo `junit = "4.13.2"` come dipendenza test
- `AppDatabase.kt`, DAO — possibile uso di **Room in-memory** o mock; decidere in planning

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Test unitari eseguibili con `./gradlew test` (o task Gradle concordato) per le tre aree (`DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`) con almeno un caso significativo per area | B | ESEGUITO |
| 2 | Nessuna regressione: `./gradlew assembleDebug` OK | B | ESEGUITO |
| 3 | `./gradlew lint` senza nuovi warning introdotti dal task | S | ESEGUITO |
| 4 | Documentazione in Execution: elenco test aggiunti, dipendenze test se aggiunte, limiti noti (es. parti non coperte) | S | ESEGUITO |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **TASK-004** = unico **`ACTIVE`** dopo **`DONE`** di **TASK-003** | Ordine backlog / utente | 2026-03-27 |
| 2 | Perimetro = tre classi indicate nel `MASTER-PLAN` | Coerenza backlog | 2026-03-27 |
| 3 | **TASK-004** rimesso in **`BACKLOG`**; **TASK-020** diventa unico **`ACTIVE`** | Decisione utente: cleanup analisi statica prima dei test unitari | 2026-03-27 |
| 4 | **TASK-020** → **`DONE`**; **TASK-004** → unico **`ACTIVE`** nel `MASTER-PLAN` | Sequenza post-cleanup; chiusura **TASK-020** su decisione utente (2026-03-28) | 2026-03-28 |
| 5 | Strategia test **TASK-004** = Room in-memory reale (Robolectric) per repository + seam injection repository sui ViewModel + coroutine test / Turbine | Piano operativo vincolante sotto (2026-03-29) | 2026-03-29 |
| 6 | Runtime **TASK-004:** creazione `DatabaseViewModel` / `ExcelViewModel` = **factory esplicita centralizzata** (`viewModel(factory = …)` o helper unico in `NavGraph`); **nessuna** dipendenza da reflection sul ctor «solo Application». Costruttore con `repository` iniettabile resta **seam per test**, non meccanismo primario di produzione. | §1a; obiettivi: stabilità runtime, una sola factory, stesso comportamento funzionale | 2026-03-30 |
| 7 | Cronologia decisioni: **Decisione 6** (2026-03-30) **sostituisce** la formulazione precedente basata su «ctor default + `viewModel()` invariato» (date intermedie incoerenti con **Creato** 2026-03-27). | Tracking coerente con il ciclo reale del task | 2026-03-30 |
| 8 | **Owner runtime unico** per `DatabaseViewModel` / `ExcelViewModel` = livello top `AppNavGraph()` in **`NavGraph.kt`** (già oggi, righe 29–30); **`DatabaseScreen`** perde il default `= viewModel()` (riga 47) per eliminare il path parallelo di creazione. | Evitare doppi `ViewModelStore` / scope incoerenti (§1a) | 2026-03-28 |

---

## Planning (Claude)

### 1a) Seam ViewModel, **factory runtime** e `viewModel()` — **strategia vincolante per EXECUTION**

**Contesto attuale (verificato sul codice 2026-03-28):** `NavGraph.kt` crea entrambe le VM al livello top di `AppNavGraph()` (righe 29–30: `val excelViewModel: ExcelViewModel = viewModel()` / `val dbViewModel: DatabaseViewModel = viewModel()`) **senza factory**, poi le passa a tutte le route. `DatabaseScreen.kt` ha ancora un default `viewModel: DatabaseViewModel = viewModel()` (riga 47) che crea un path di creazione parallelo: il fix è **rimuovere il default** (il call-site `NavGraph.kt:170` già passa `dbViewModel`). La risoluzione via **`DefaultViewModelProviderFactory`** / reflection sul costruttore `(Application)` è **esplicitamente non** la strategia primaria di questo task (fragilità e dipendenza da dettagli Kotlin/JVM).

**Strategia primaria (runtime, produzione):**

1. **Una sola factory centralizzata per tipo** — forma ammessa (sceglierne **una** in EXECUTION, documentare):
   - **`companion object Factory : ViewModelProvider.Factory`** su ciascun ViewModel + `viewModel(factory = DatabaseViewModel.Factory)` / `ExcelViewModel.Factory`, **oppure**
   - **Object / file helper** (es. `ViewModelFactories` o funzioni in `NavGraph`) che incapsula **la stessa** `ViewModelProvider.Factory` riusata ovunque.
2. **Un solo owner reale della creazione runtime (vincolante):** le istanze `DatabaseViewModel` e `ExcelViewModel` in produzione devono essere create **in un unico luogo di wiring** — oggi già al **livello top** di `AppNavGraph()` (righe 29–30 di `NavGraph.kt`), **prima** del `NavHost`. In EXECUTION il refactor aggiunge `viewModel(factory = …)` **nello stesso punto** (o equivalente documentato). **È vietato** lasciare **`DatabaseScreen`** (o altre schermate) con capacità parallela di creare **da sole** `DatabaseViewModel` via default `viewModel()` o seconda chiamata `viewModel(factory = …)` sullo stesso tipo: se la schermata serve la VM, la riceve **come parametro** dalla composable chiamante (NavGraph / parent). Obiettivo: **nessun** doppio store, **nessun** scope incoerente, **nessun** wiring duplicato. Inventario obbligatorio in EXECUTION: `grep` su `DatabaseViewModel` / `ExcelViewModel` / `viewModel(` — dopo il refactor deve restare **una sola** coppia di creazioni (o equivalente esplicitamente documentato se una VM non serve su una route).
3. Il corpo della factory di produzione istanzia **`DefaultInventoryRepository(AppDatabase.getDatabase(application))`** e passa al costruttore del ViewModel — **identico al comportamento odierno**, solo reso esplicito e non dipendente da reflection.

**Seam costruttore (test e componibilità, non primario runtime):**

- Resta ammesso un costruttore del tipo  
  `DatabaseViewModel(app: Application, repository: InventoryRepository)` / `ExcelViewModel(application: Application, repository: InventoryRepository)`  
  eventualmente con **default** Kotlin sul secondo argomento **solo** per comodità di compilazione o chiamate interne — ma **la creazione in app** non deve affidarvisi tramite `viewModel()` implicito: passa **sempre** dalla factory §1a.
- **Test:** istanziare **direttamente** `DatabaseViewModel(robolectricApp, mockRepo)` / `ExcelViewModel(robolectricApp, mockRepo)` **senza** usare la factory di produzione, così da iniettare MockK / repository Room in-memory senza toccare il wiring Compose.

**Produzione vs test (chiarezza):**

| Contesto | Meccanismo | Repository |
|----------|------------|------------|
| **Produzione** | Factory + `viewModel(factory = …)` **solo** nell’**owner** top-level `AppNavGraph()` (**`NavGraph.kt`** righe 29–30); le schermate ricevono le VM **per parametro** (§1a punto 2) | `DefaultInventoryRepository(AppDatabase.getDatabase(app))` come oggi |
| **Test unitari** | Costruttore diretto | MockK o `DefaultInventoryRepository` su DB in-memory |
| **Comportamento funzionale** | Invariato rispetto all’attuale app | Stessa logica business nel ViewModel |

**Perché questa è la scelta primaria:** nessuna dipendenza da reflection fragile; factory **esplicita** e ispezionabile; **un solo owner** di creazione (no doppi store); **una** definizione di factory riusata (no proliferazione); refactor **locale** (ViewModel + `NavGraph` + firme schermate), non architetturale.

**Esplicitamente deprecato come primario per questo task:** «ctor con default + `viewModel()` invariato» affidato alla reflection AndroidX.

---

### 1) Strategia di test per area (scelta esplicita, non ambigua)

#### `DefaultInventoryRepository`

- **Strategia:** **Room database in-memory** con **schema e versione identici** a `AppDatabase` (stessa classe `AppDatabase`, `Room.inMemoryDatabaseBuilder`, **senza** mock superficiali dei DAO che bypassano la logica SQL/transazioni.
- **Motivazione:** la classe orchestra **inserimenti prodotto**, **`ProductPriceDao.insertIfChanged`**, **`applyImport`** con timestamp `prev`/`current`, mutex supplier/category — la copertura deve colpire **comportamento reale** tra DAO (prodotti, prezzi, fornitori, categorie), non solo «verify(mock).wasCalled».
- **Non usare:** un unico mega-mock di `InventoryRepository` per sostituire il repository sotto test (qui il SUT **è** il repository).

**Assert su timestamp / date (vincolante, repository e percorsi import collegati):**

- **Non** basare i test sull’**uguaglianza esatta** a un valore wall-clock (`System.currentTimeMillis()`, `LocalDateTime.now()` fissato a stringa letterale) per righe price history o log import.
- **Preferire:** presenza delle **righe attese** (conteggio / chiavi); **`source`** / tipo corretto; **ordine relativo** o vincolo **`prevTs` ≤ `currentTs`** (o equivalente semantico del codice) dove applicabile; **pattern** sul timestamp (es. formato `yyyy-MM-dd HH:mm:ss`) o **non-null** + coerenza interna, non uguaglianza a «adesso».
- **Seam clock** (`Clock` / `InstantProvider` / parametro tempo) **solo** se un caso resta **davvero** ingovernabile senza (flaky ripetuto); **non** introdurla come scelta automatica o default della suite.

#### `DatabaseViewModel`

- **Problema attuale:** istanzia `private val repository = DefaultInventoryRepository(AppDatabase.getDatabase(app))` nel corpo del costruttore — **non** testabile in isolamento senza seam.
- **Strategia mista (vincolata):**
  1. **Refactor minimo obbligatorio:** come in **§1a** — parametro `repository` sul costruttore (seam test) + **factory** usata **solo** dall’**owner** **`NavGraph`**; schermate senza creazione locale della VM.
  2. **Doppio del repository — preferenza MockK:** per la **maggior parte** dei casi della matrice §3.D usare **`mockk<InventoryRepository>(relaxed = true)`** con `coEvery { … }` / `coVerify` solo sui metodi toccati da quel test. **Non** introdurre un `FakeInventoryRepository` monolitico che implementa tutta `InventoryRepository`. Per **`SQLiteConstraintException`** su `addProduct`/`updateProduct`, usare `coEvery { addProduct(any()) } throws SQLiteConstraintException(...)` (o equivalente) senza fake.
  3. **Test di integrazione leggera (opzionale, 1–2 casi):** Robolectric + DB in-memory + **`DefaultInventoryRepository` reale** iniettato nel ViewModel (es. `exportToExcel` con DB vuoto vs righe presenti) — **nessun** fake condiviso richiesto.
- **Non** spostare logica business dal ViewModel al mock; il mock risponde, non decide i flussi.

#### `ExcelViewModel`

- **Problema attuale:** `AndroidViewModel`, stato **Compose Runtime** (`mutableStateListOf`, …), `repository` hardcoded come `DatabaseViewModel`.
- **Strategia:**
  1. **Stessa seam §1a:** costruttore con `repository` iniettabile + **factory** usata solo da **`NavGraph`** per `ExcelViewModel` (non creazione implicita via reflection nelle schermate).
  2. **Repository in test — MockK per default, fake micro solo se utile:** per E4–E8 preferire **`mockk<InventoryRepository>(relaxed = true)`** con stub mirati (`insertHistoryEntry`, `getHistoryEntryByUid`, `updateHistoryEntry`, `deleteHistoryEntry`, `getPreviousPricesForBarcodes`, …). Usare un **fake/minimal stub** (pochi metodi, stesso file o inner class nel test) **solo** se un caso (es. stato `Flow` history) risulta più chiaro con comportamento reale minimale — **mai** una «seconda implementazione» completa di `InventoryRepository`.
  3. **Test su JVM** con **Robolectric** (`Application`/`Context` di test) per istanziare il ViewModel e mutare stato griglia/history **senza** Compose UI test.
  4. Per **`exportToUri`:** **non** testare stream su file reale; vedere §5 (seam su writer / `ContentResolver`) e §3 (caso minimo).

---

### 2) Ambiente tecnico di test (combinazione e motivazione)

| Componente | Uso | Motivo |
|------------|-----|--------|
| **Robolectric** (`@RunWith(RobolectricTestRunner::class)` o AndroidX test runner equivalente) | `Application`/`Context`, `AppDatabase` in-memory | `AndroidViewModel` e Room richiedono stack Android; resta su **JVM** (`test` source set), coerente con `./gradlew test`. |
| **Room in-memory** (`Room.inMemoryDatabaseBuilder`) | Test `DefaultInventoryRepository` e (se usati) integrazione VM | Copre SQL, vincoli UNIQUE, `insertIfChanged`, transazioni effettive dei DAO. |
| **`kotlinx-coroutines-test`** | `runTest`, **`StandardTestDispatcher`**, eventuale **`UnconfinedTestDispatcher`** per regole semplici | Tutti i metodi pubblici critici sono `suspend` o `viewModelScope.launch`. |
| **`MainDispatcherRule`** (o equivalente) | Allineare `Dispatchers.Main` nei test dove il codice posta su Main (es. parti di `ExcelViewModel`) | Evita flaky / «Module with Main dispatcher» errors. |
| **Turbine** | Assert su `StateFlow` / `Flow` (`uiState`, `filter`, `historyEntries` dove esposto) | API stabile per aspettative temporali sui flussi. |
| **MockK** (preferito in Kotlin) **o** Mockito | **Strumento principale** per `InventoryRepository` nei test ViewModel (`relaxed` + `coEvery`/`coVerify` mirati) | Evita fake monolitici; vedi §1 `DatabaseViewModel` / `ExcelViewModel`. |
| **Robolectric** (ripetuto) | `ExcelViewModel` | Accesso a `Application` senza device. |

**Esplicitamente escluso in questa fase (salvo bloccante tecnico documentato):** suite che richieda **refactor ampio** solo per testare (es. estrarre metà `DatabaseViewModel` in use case); **instrumented `androidTest`** per logica che può restare in `test` con Robolectric.

---

### 3) Matrice minima obbligatoria di casi (per classe)

Ogni riga = almeno **un** `@Test` (o scenario parametrizzato) da implementare in **EXECUTION**.

#### `DefaultInventoryRepository` (target **≥ 8** test)

| # | Caso | Atteso (alto livello) |
|---|------|------------------------|
| R1 | `addProduct` con prezzi purchase/retail | Inserimento prodotto + righe `ProductPrice` iniziali con source `MANUAL` e timestamp **validato per pattern/ordine** (blocco «Assert su timestamp» sotto `DefaultInventoryRepository` §1) |
| R2 | `updateProduct` con prezzi modificati | Update prodotto + nuove righe price history via `insertIfChanged` (stessa regola sui timestamp) |
| R3 | `applyImport` con `newProducts` + `updatedProducts` con `old*` e nuovi prezzi | Persistenza prodotti + righe `IMPORT_PREV` / `IMPORT` con **ordine relativo** `prev` vs `current` e source corretti (non uguaglianza wall-clock esatta) |
| R4 | `addSupplier` due volte stesso nome | Seconda chiamata restituisce lo **stesso** fornitore (idempotenza) |
| R5 | `addCategory` due volte stesso nome | Idempotenza come sopra |
| R6 | `getFilteredHistoryFlow` o `getPriceSeries` (scegliere **uno** dei due flussi) | Dati coerenti dopo inserimento history/prezzi (senza coprire tutte le date filter) |
| R7 | `getCurrentPriceSnapshot` o `getCurrentPricesForBarcodes` (scegliere **uno**) | Snapshot/barcode map coerente dopo `addProduct`/`updateProduct` |
| R8 | (facoltativo se già coperto da R3) `recordPriceIfChanged` / `getLastPrice` | Coerenza lettura ultimo prezzo dopo scrittura |

#### `DatabaseViewModel` (target **≥ 11** test)

Usare **repository iniettato** tramite **costruttore diretto** nei test (§1a); **`runTest` + Turbine** su `uiState` dove applicabile. La matrice D1–D13 **non** richiede la factory di produzione.

| # | Caso | Atteso |
|---|------|--------|
| D1 | `addProduct` success | `UiState.Success` (messaggio coerente con stringhe o pattern) |
| D2 | `addProduct` → repository lancia `SQLiteConstraintException` | `UiState.Error` con messaggio barcode duplicato (`R.string.error_barcode_already_exists`) |
| D3 | `updateProduct` success | `UiState.Success` |
| D4 | `updateProduct` → constraint / errore generico | `UiState.Error` (duplicato o generico secondo ramo) |
| D5 | `deleteProduct` success | `UiState.Success` |
| D6 | `analyzeGridData` con repository che restituisce lista DB fissa + analyzer che non fallisce | Completamento con `UiState.Idle` e `_importAnalysisResult` valorizzato (accesso test-only o osservazione effetti se esposto) — se lo stato interno non è leggibile, documentare in Execution e usare **MockK** su repository + verifica `uiState` finale **Idle** |
| D7 | `analyzeGridData` con eccezione da `ImportAnalyzer` / repository | `UiState.Error` (eccezione simulata via **micro-seam** §5 **oppure** `mockkObject` static solo se accettato in Execution come ultima ratio) |
| D8 | `startImportAnalysis` **success** (percorso felice) | **Preferenza operativa vincolata:** generare nel test un workbook **minimo** con **Apache POI** (`XSSFWorkbook`: header + poche righe dati allineate a quanto si aspetta `readAndAnalyzeExcel` / import), scriverlo su file sotto **`applicationContext.cacheDir`** (o `createTempFile` equivalente in ambiente Robolectric) e passare un **`Uri` da file** (`Uri.fromFile` o API supportata dal runner). **Perché:** stesso stack POI già usato dall’app (classpath modulo `test` eredita le dipendenze `main` — verificare in Execution se necessario), **nessun blob binario** opaco in `test/resources`, struttura **leggibile nel sorgente** del test e più semplice da aggiornare se cambiano colonne attese. **Alternativa secondaria:** file `.xlsx` statico in `app/src/test/resources/` **solo** se la generazione POI risultasse più fragile del previsto (formato rifiutato dalla pipeline reale): documentare in Execution il motivo dello scarto. Se entrambi falliscono → **micro-seam** §5. |
| D9 | `startImportAnalysis` **errore** | **Strategia vincolata:** `Uri` che non apre stream **oppure** file vuoto/invalido tale che `validateImportFile` fallisca (`error_file_empty_or_invalid`) **oppure** eccezione durante analisi → `UiState.Error` (`error_data_analysis` o stringa validazione) verificata con Turbine |
| D10 | `exportToExcel` con `getAllProductsWithDetails()` vuoto | `UiState.Error` (`error_no_products_to_export`) |
| D11 | `exportToExcel` con lista non vuota + `ContentResolver` che non fallisce | `UiState.Success` — usare shadow Robolectric / mock resolver |
| D12 | `consumeUiState()` | `uiState` torna `Idle` |
| D13 | `clearImportAnalysis()` | Stato import/analysis resettato coerentemente (verificabile via effetto su stato esposto o su prossima chiamata) |
| D14 | `importProducts` (success) | Verifica invocazione `repository.applyImport` e scrittura log history (via `insertHistoryEntry` / `updateHistoryEntry`). Risultato finale `UiState.Success` |

*Nota D8/D9:* il percorso file reale è accoppiato a `readAndAnalyzeExcel`; ordine preferito: **workbook minimo generato in-test con POI** (§3 D8) → **file statico** in `test/resources` solo come ripiego documentato → **micro-seam** reader/analyzer (§5). Documentare in Execution quale ramo è attivo (non ampliare oltre).

#### `ExcelViewModel` (target **≥ 10** test)

Istanziazione nei test: **costruttore** con `Application` Robolectric + repository mock/in-memory (§1a); stessa matrice E1–E10.

| # | Caso | Atteso |
|---|------|--------|
| E1 | `toggleColumnSelection` su colonna **essenziale** (`barcode` / `productName` / `purchasePrice`) | Nessun cambio selezione |
| E2 | `toggleColumnSelection` su colonna non essenziale | Toggle |
| E3 | `toggleSelectAll` | Essenziali sempre `true`; le altre si comportano come «select all / deselect all» |
| E4 | `generateFilteredWithOldPrices` | `repository.insertHistoryEntry` chiamato; stato griglia/`generated`/supplier/category/`currentEntryStatus` coerenti; callback `onResult` con `uid` > 0 |
| E5 | `loadHistoryEntry` | `excelData`, `editableValues`, `completeStates`, `supplier`/`category`, flag `generated` allineati all’entry |
| E6 | `updateHistoryEntry(uid)` | `repository.updateHistoryEntry` con `data`/`editable`/`complete`/`paymentTotal`/`missingItems` aggiornati |
| E7 | `renameHistoryEntry(entry, newName, newSupplier?, newCategory?)` | `repository.updateHistoryEntry` chiamato con `id = newName`, `supplier`/`category` aggiornati se forniti |
| E8 | `deleteHistoryEntry` | `repository.deleteHistoryEntry` invocato |
| E9 | `exportToUri` | **Seam minima:** estrarre interfaccia interna tipo `ExcelExportSink` o parametro default `writer: (OutputStream) -> Unit` **solo** per test, oppure mock `ContentResolver` + Uri Robolectric — **nessun** file reale sul disco host nei test CI |
| E10 | Flusso manuale (`createManualEntry` / `addManualRow`) | Verifica creazione di una `HistoryEntry` con `isManualEntry = true` e il corretto popolamento delle liste interne `excelData` / `editableValues` senza lettura file |

---

### 4) Confini del task e known gaps

**Fuori da TASK-004 (scope creep):**

- Parsing approfondito **`ExcelUtils`**, alias HTML, **`ImportAnalyzer`** interno, **`readAndAnalyzeExcel`** completo → principalmente **TASK-005**.
- Test UI Compose / Espresso end-to-end.
- **`exportFullDbToExcel`**, **`startSmartImport`**, full-import OOM/streaming, **`PagingSource`** / `pager` (salvo nota esplicita «non coperto»).
- Copertura totale `ImportWorkbookRoute` / `XSSFWorkbook` multi-foglio.

**Known gaps (documentare in Execution, non espandere il task):**

- Se né **workbook POI in-test** né **XLSX statico** di ripiego consentono un happy path stabile (alias colonne / parser), dichiarare **gap** temporaneo: coprire **solo** D9 + rami `validateImportFile` fino a **TASK-005** o iterazione successiva — **non** allargare TASK-004 a «supportare ogni Excel del mondo».
- Flussi che dipendono da **ContentResolver** reale o percorsi file esterni senza shadow.
- **`generateFilteredWithOldPrices`** con `repository.getPreviousPricesForBarcodes` — usare **MockK** (`coEvery { getPreviousPricesForBarcodes(any(), any()) returns mapOf(...) }`) o stub minimo locale, **non** un fake universale.

---

### 5) Refactor minimi ammessi (solo testabilità)

| Consentito | Vietato |
|------------|---------|
| **Costruttore** con `InventoryRepository` su `DatabaseViewModel` / `ExcelViewModel` (parametro esplicito; default Kotlin **opzionale** solo se utile alla factory / compilazione) + **una** `ViewModelProvider.Factory` centralizzata per VM che in produzione passa `DefaultInventoryRepository(AppDatabase.getDatabase(application))` | Cambiare semantica CRUD, import, export |
| **Interfaccia / lambda** per output file (`exportToUri` / export Excel) di **1–2 righe** di indirection | Estrarre use case layer completo |
| **Micro-seam opzionale import (solo fallback):** parametro costruttore con default che delega all’implementazione attuale per (a) **lettura file / normalizzazione header-righe** (equivalente funzionale a `readAndAnalyzeExcel` + contesto minimo) e/o (b) **invocazione analyzer** (`ImportAnalyzer.analyze` / `analyzeStreaming`) — tipicamente `typealias` + lambda `(Context, Uri) -> …` e/o `(Context, Sequence<…>, …) -> ImportAnalysis` con default = chiamata statica odierna. **Ammessa solo se** il percorso **Robolectric + workbook minimo** (§3 D8: **POI in-test**, eventualmente **XLSX statico** di ripiego) risulta **fragile o non deterministico** dopo tentativi documentati. La logica di orchestrazione (ordine loading, `validateImportFile`, aggiornamento `_uiState`) **resta nel ViewModel**. | Spostare regole di business in classi esterne nuove; introdurre layer use-case |
| **`@VisibleForTesting`** su metodo **package-private** già esistente, solo se inevitabile | Esporre API pubblica nuova all’app solo per test |
| Clock / `InstantProvider` **solo** se, **dopo** assert su pattern/ordine (§1 repository), un test resta **davvero** flaky; **non** come scelta automatica | Refactor architetturale «clean» non richiesto |

**Invariato:** DAO pubblici, schema Room, **comportamento funzionale** dell’app e flussi business.

**Da aggiornare in EXECUTION (minimo, coerente con §1a):** **`NavGraph.kt`** = **owner** che crea le VM con `viewModel(factory = …)` e le passa alle route; **`DatabaseScreen.kt`** = **solo** parametro `databaseViewModel` (niente `viewModel()` default); altri siti emersi da `grep` allineati allo stesso modello. File toccati documentati in Execution.

---

### 6) Deliverable del planning (file, dipendenze, target, rischi, definition of ready)

#### File di test previsti (nomi target)

- `app/src/test/java/.../data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/.../viewmodel/DatabaseViewModelTest.kt`
- `app/src/test/java/.../viewmodel/ExcelViewModelTest.kt`
- **Nessun** `FakeInventoryRepository.kt` obbligatorio: **MockK** copre il grosso dei test ViewModel. Se un file stub/fake condiviso risulta comunque utile, deve essere **minimale** (solo firme/metodi referenziati dai test esistenti, niente implementazione «completa» da produzione parallela); preferire **inner class / object nel file di test** prima di un file condiviso.

#### Dipendenze test da aggiungere (in `app/build.gradle.kts` + eventuale `libs.versions.toml`)

- `testImplementation` **Robolectric** (versione compatibile AGP 9 / API 36)
- `testImplementation` **kotlinx-coroutines-test**
- `testImplementation` **Turbine**
- `testImplementation` **MockK** (o Mockito + `mockito-kotlin`)
- `testImplementation` **androidx.arch.core:core-testing** (se necessario per executor)
- `testImplementation` **AndroidX Test** (`core`, `runner`/`rules` se richiesti da Robolectric)

*Oggi presente solo `junit = "4.13.2"` in `testImplementation` (+ AndroidTest: `espresso-core`, `ext:junit`, `ui-test-junit4`). Le nuove dipendenze test vanno aggiunte sia in `gradle/libs.versions.toml` (versions + libraries) sia in `app/build.gradle.kts` (`testImplementation`).*

#### Numero minimo target (casi `@Test` conteggiati)

| Classe | Minimo |
|--------|--------|
| `DefaultInventoryRepository` | **8** |
| `DatabaseViewModel` | **11** |
| `ExcelViewModel` | **10** |
| **Totale** | **≥ 29** |

#### Rischi residui (post-planning)

- Robolectric + Room + API recenti possono richiedere **config manifest/shadow** aggiuntiva.
- `ExcelViewModel` accoppiato a Compose Runtime: possibili warning o necessità di inizializzazione composition (se emergono, documentare; non espandere scope).
- **`analyzeGridData` / `ImportAnalyzer`:** preferire **workbook POI in-test** (§3 D8) + **MockK** su repository; **micro-seam** §5 solo se fragile; **mockk static** su object Kotlin solo se la seam è esplicitamente respinta e documentata come ultima ratio.

#### Planning «pronto per EXECUTION» (checklist)

- [ ] Congelate le voci in **§ Decisioni tecniche ancora da congelare** (sotto).
- [ ] **Owner unico:** `viewModel(factory = …)` **solo** in **`NavGraph`** (o unico entrypoint route documentato); **`DatabaseScreen`** (e analoghi) ricevono le VM **solo** via parametri — **nessuna** creazione parallela.
- [ ] `./gradlew assembleDebug` OK + **smoke** navigazione sulle rotte che usano `DatabaseViewModel` / `ExcelViewModel` (nessuna regressione runtime).
- [ ] Robolectric verificato su macchina sviluppo (`./gradlew test` verde dopo scaffold vuoto).
- [ ] Elenco dipendenze test congelato in **Execution** (versioni).
- [ ] Matrice §3 coperta da elenco test implementati o da gap documentati con motivazione.

---

### Decisioni tecniche ancora da congelare prima della EXECUTION

Da chiudere con evidenza (build/run o prova ctor) **prima** di scrivere la suite completa:

| Decisione | Opzioni / output atteso |
|-----------|-------------------------|
| **Forma della factory runtime** | **Primario (TASK-004):** factory esplicita + `viewModel(factory = …)` **solo** nell’**owner** route (**`NavGraph`**). **`DatabaseScreen`:** rimozione `viewModel()` default, solo parametro. Output: forma factory + elenco file (`NavGraph`, `DatabaseScreen`, altri da grep). |
| **Costruttore con `repository`** | Resta **seam per test** e per uso interno dalla factory; **non** è il meccanismo primario di creazione runtime via `viewModel()` implicito. Output: firma finale documentata in Execution. |
| **Import D8: POI in-test vs XLSX statico vs micro-seam** | **Primario:** workbook **generato in test con POI** + file in `cacheDir` + `Uri`. **Ripiego:** `.xlsx` in `test/resources`. **Fallback:** micro-seam §5. Output: ramo scelto per D6–D9. |
| **`DatabaseViewModel` tests: mock vs fake** | **Default pianificato:** **MockK** per tutti i casi §3.D salvo 1–2 integrazione con repo reale. Output: elenco test che usano mock vs DB reale. |
| **`ExcelViewModel` tests: mock vs fake micro** | **Default pianificato:** **MockK**; fake/stub **solo** locale e minimale se un test lo rende più leggibile. Output: se esiste file fake condiviso, elenco metodi implementati (deve restare corto). |

---

### Analisi (sintesi)

Le tre classi dipendono da **Room** e/o **`AndroidViewModel`**: la combinazione **Robolectric + Room in-memory + coroutine test + Turbine** è la più adatta **senza** `androidTest` di massa e **senza** spostare business logic. `InventoryRepository` è già interfaccia: **in produzione** wiring via **factory** + **owner unico** (`NavGraph`); **nei test** injection diretta sul costruttore del ViewModel.

### Piano di esecuzione (ordine)

1. Aggiungere dipendenze test (Robolectric, coroutines-test, Turbine, MockK).
2. Implementare **costruttore** con `repository` + **factory** §1a; **`NavGraph`** = unico owner `viewModel(factory = …)`; **`DatabaseScreen`** = solo parametro VM (niente creazione locale); inventario `grep` per altri siti; **smoke** navigazione + `assembleDebug`.
3. Implementare `DefaultInventoryRepositoryTest` con DB in-memory (matrice §3.R).
4. Implementare `DatabaseViewModelTest` con **MockK** (+ Turbine) e integrazione leggera opzionale (matrice §3.D).
5. Implementare `ExcelViewModelTest` con Robolectric e **MockK** su repository (matrice §3.E).
6. `./gradlew test` + `assembleDebug` + `lint`; log **Execution** con gap §4 se applicabile.

### Rischi identificati (nel planning)

- Combinazione Robolectric/Room/Kotlin 2.x: stack attuale = **Kotlin 2.3.20, AGP 9.1.0, KSP 2.3.2, Room 2.8.4, compileSdk/targetSdk 36**. Mitigare con versioni Robolectric allineate (verificare compatibilità API 36 target) e test «hello world» prima della suite piena.
- **Scope VM:** mitigato da **owner unico** §1a (NavGraph); se emergono route annidate che richiedono scope diverso, documentare in Execution (fuori dal default pianificato).
- Accoppiamento statico `ImportAnalyzer` / `readAndAnalyzeExcel`: mitigare con gap documentati o micro-seam **solo** se necessario.
- **Scope creep:** mitigare rispettando §3–§5 e delegando parsing a **TASK-005**.

---

## Execution

### Esecuzione — 2026-03-28

**Audit iniziale:**
- Verificato che il wiring runtime pianificato era già correttamente in sede: `DatabaseViewModel` e `ExcelViewModel` usano repository iniettato + `factory(...)`, `NavGraph.kt` è l’unico owner con `viewModel(factory = …)`, `DatabaseScreen.kt` non crea più un `viewModel()` implicito.
- Verificato che `app/build.gradle.kts` e `gradle/libs.versions.toml` avevano già le dipendenze test principali previste dal planning (`Robolectric`, `kotlinx-coroutines-test`, `Turbine`, `MockK`, `core-testing`); non è stato necessario riaprire il wiring runtime né aggiungere nuove dipendenze.
- Trovate bozze test presenti ma incomplete/incoerenti con le firme attuali (`ProductUpdate`, `applyImport`, sincronizzazione Robolectric/coroutines, template `ExampleUnitTest` ancora presente).

**File modificati:**
- `app/build.gradle.kts` — abilitato `unitTests.isIncludeAndroidResources = true` per l’esecuzione Robolectric dei test JVM con accesso corretto alle risorse Android.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — riscritta la suite repository con Room in-memory reale, assert robusti su history/timestamp/source e copertura CRUD/import/filter/snapshot.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — completata la suite ViewModel con MockK, workbook POI minimo per `startImportAnalysis`, verifica di CRUD/import/export/reset state e attese asincrone robuste su `Dispatchers.IO/Default`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — completata la suite ViewModel con MockK, copertura selezione colonne, generazione history, rename/delete, export, entry manuali e persistenza stato.
- `app/src/test/java/com/example/merchandisecontrolsplitview/testutil/MainDispatcherRule.kt` — nuova rule condivisa per `Dispatchers.Main` nei test ViewModel.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ExampleUnitTest.kt` — rimosso template di default ormai ridondante.

**Azioni eseguite:**
1. Eseguito mini-audit documentale e del codice già toccato dall’esecuzione precedente per evitare di rifare lavoro già corretto.
2. Confermato che il wiring runtime approvato nel planning era già coerente e non richiedeva ulteriori modifiche a `DatabaseViewModel.kt`, `ExcelViewModel.kt`, `NavGraph.kt` o `DatabaseScreen.kt`.
3. Riallineata la base test al codice reale del progetto: firme attuali di `ProductUpdate`, comportamento di `applyImport`, owner unico dei ViewModel e uso del repository iniettato.
4. Consolidata una suite unitaria unica e pulita:
   `DefaultInventoryRepositoryTest` = 8 test
   `DatabaseViewModelTest` = 14 test
   `ExcelViewModelTest` = 12 test
   Totale = 34 test
5. Eliminato il template `ExampleUnitTest` e introdotta una `MainDispatcherRule` condivisa per evitare duplicazioni e flakiness nei test ViewModel.
6. Verificata la strategia D8 pianificata con workbook `.xlsx` minimo generato in-test tramite Apache POI e passato via `Uri.fromFile(...)`.
7. Verificato che `lint` fallisce per errori/warning preesistenti fuori scope (`GeneratedScreen.kt`, `ImportAnalysisScreen.kt`, warning di dipendenze/Gradle), senza riferimenti ai file modificati da TASK-004 nel report lint corrente.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | ⚠️ ESEGUITO | `./gradlew lint` eseguito ma fallisce per 25 errori / 68 warning preesistenti fuori scope; primo errore in `GeneratedScreen.kt:223`; nessun riferimento ai file toccati da TASK-004 nel report |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning/error lint riferito ai file modificati dal task; warning Gradle/Kotlin plugin e lint Compose sono preesistenti |
| Coerenza con planning    | ✅ ESEGUITO | Confermato owner unico runtime già corretto; completate le tre suite secondo strategia Room in-memory + MockK + POI in-test |
| Criteri di accettazione  | ✅ ESEGUITO | C1 `./gradlew test` verde; C2 `./gradlew assembleDebug` verde; C3 verificato nessun issue nei file del task nonostante lint globale preesistente; C4 documentazione aggiornata qui |

**Dettaglio criteri di accettazione:**
| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Test unitari eseguibili per repository + due ViewModel | `./gradlew test` | ESEGUITO | `BUILD SUCCESSFUL`; 34 test verdi distribuiti su 3 suite |
| 2 | Nessuna regressione build | `./gradlew assembleDebug` | ESEGUITO | `BUILD SUCCESSFUL` |
| 3 | Nessun warning nuovo introdotto dal task | `./gradlew lint` + audit report | ESEGUITO | Report lint senza occorrenze sui file modificati da TASK-004; errori/warning residui già presenti fuori scope |
| 4 | Documentazione execution completa | Ispezione file task | ESEGUITO | Audit, file modificati, verifiche e limiti residui riportati in questa sezione |

**Incertezze:**
- Nessuna sul perimetro del task.

**Handoff notes:**
- `lint` globale della repo resta rosso per problemi preesistenti in `GeneratedScreen.kt`, `ImportAnalysisScreen.kt` e warning di dipendenze/Gradle: fuori scope di TASK-004, ma da considerare in un task dedicato di cleanup qualità.
- Il runtime wiring dei ViewModel era già corretto prima di questa ripresa; questa esecuzione non ha cambiato il comportamento funzionale dell’app.

---

## Review

_(Dopo EXECUTION.)_

---

## Fix

_(Se necessario.)_

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | DONE |
| Data chiusura          | 2026-03-28 |
| Tutti i criteri ✅?    | Sì |
| Rischi residui         | `lint` globale della repo fallisce ancora per errori/warning preesistenti fuori scope; nessuna issue lint sui file modificati da TASK-004 |

---

## Riepilogo finale

- Audit iniziale: wiring runtime già corretto e dipendenze test principali già presenti; il lavoro rimasto era completare e rendere verde la suite.
- Completamento: 34 test verdi complessivi (`DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`), rimozione del template inutile, aggiunta di `MainDispatcherRule`, supporto Robolectric alle risorse unit test.
- Verifiche: `./gradlew test` e `./gradlew assembleDebug` verdi; `./gradlew lint` eseguito ma ancora rosso per debito preesistente fuori scope.

---

## Handoff

- **Predecessori:** **TASK-003** (`DONE`, decomposizione `DatabaseScreen`); **TASK-020** (`DONE` 2026-03-28, cleanup code analysis post-TASK-003).
- **Stato corrente:** **TASK-004** chiuso in **`DONE`** lato esecuzione; nessun nuovo task attivato automaticamente.
- **Successore naturale in backlog:** **TASK-005** (test `ExcelUtils` / `ImportAnalysis`) oppure un task dedicato al cleanup del debito `lint`.
- **Governance:** mantenere un solo task `ACTIVE`; la prossima attivazione richiede decisione planner/utente.
