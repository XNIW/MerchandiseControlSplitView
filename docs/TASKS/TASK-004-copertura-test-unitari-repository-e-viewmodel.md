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
| Ultimo aggiornamento | 2026-03-29 — editing Planning: separazione storico/operativo, dedup §1a, gerarchie primario/ripiego, Obbl./Opz., lint, razionale _(task resta `DONE`)_ |
| Tracking `MASTER-PLAN` | **`DONE`** (chiusura esecutiva completata; nessun nuovo task attivato dall’executor) |

**Nota tracking:** nel `MASTER-PLAN` questo task risulta chiuso in **`DONE`** dopo completamento codice + test + verifiche. Nessun nuovo task è stato attivato in questa esecuzione.

**Nota cronologia:** eventuali date di planning **anteriorsi** a **`Creato` (2026-03-27)** in bozze intermedie erano incoerenti e **non** valgono come timeline del task. **2026-03-30** = factory runtime + allineamento (**Decisione 6**). **2026-03-31** = micro-ottimizzazioni owner unico, regola timestamp, preferenza D8 POI (**`Ultimo aggiornamento`**).

### Come leggere questo file (governance)

| Sezione | Natura |
|---------|--------|
| **Fino a «Criteri di accettazione»** | Contratto e contesto del task (ancora validi come riferimento). |
| **§ Planning operativo (Claude)** | Piano **congelato**: era l’istruzione per Execution; **non** è planning «attivo» — il task è **`DONE`**. |
| **§ Execution in poi** | **Storico** dell’esecuzione (audit, file toccati, evidenze, chiusura). |

Per baseline regressione post-task successivi, usare **`MASTER-PLAN`** + suite effettiva in `app/src/test/...`; il Planning qui sotto documenta **perché** e **come** è stata concepita la suite, non un work item aperto.

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
_(Voce storica pre-chiusura; il task è **`DONE`**.)_

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
| 3 | **Lint (perimetro task):** nessun **nuovo** errore o warning di lint **nei file toccati da TASK-004** (sorgenti test + eventuali sorgenti `main` modificati per testabilità). **`./gradlew lint` globale** può restare **rosso** per debito **preesistente** fuori perimetro — da documentare, non da «sistemare» allargando TASK-004 | S | ESEGUITO |
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
| 6 | **Wiring ViewModel (fonte di verità unica = §1a):** factory esplicita + **`viewModel(factory = …)` solo nell’owner top** (`NavGraph` / `AppNavGraph`); **nessun** secondo path (`DatabaseScreen` **senza** default `viewModel()`). Ctor con `repository` = **seam test**, non creazione runtime implicita. *(Sostituisce ogni bozza precedente «ctor + viewModel() reflection».)* | Stabilità, un solo store, stesso comportamento funzionale | 2026-03-28 → 2026-03-30 |

---

## Planning operativo (Claude) — **congelato**

> **TASK-004 è `DONE`.** Questa sezione è il **piano approvato** usato in Execution (riferimento storico + contratto di design). Non sostituisce il log in **§ Execution** per evidenze puntuali.  
> **Fonte di verità unica — factory / owner ViewModel:** tutto ciò che riguarda creazione runtime delle due VM è definito **solo** in **§1a**; altre sezioni **rimandano a §1a** senza ripetere la strategia.

### 1a) Seam ViewModel, factory runtime e `viewModel()` — strategia vincolante

**Contesto (2026-03-28):** `AppNavGraph()` deve restare **unico owner** delle VM; `DatabaseScreen` **non** deve avere default `viewModel()` parallelo. Reflection sul ctor «solo `Application`» **non** è strategia accettata.

**Runtime (produzione) — tre regole:**

1. **Una factory per tipo** (`companion Factory` **o** helper unico in `NavGraph` che incapsula la stessa `ViewModelProvider.Factory`) — una forma sola, documentata in Execution.
2. **Un solo punto di creazione:** `viewModel(factory = …)` **solo** nell’owner top (oggi `NavGraph` / `AppNavGraph`); le schermate ricevono la VM **solo per parametro**. Dopo refactor: `grep` su `viewModel(` / `DatabaseViewModel` / `ExcelViewModel` → **nessuna** doppia creazione.
3. La factory di produzione costruisce **`DefaultInventoryRepository(AppDatabase.getDatabase(application))`** come oggi — comportamento invariato.

**Test:** ctor diretto `ViewModel(app, mockRepo)` **senza** factory di produzione.

| Contesto | Meccanismo | Repository |
|----------|------------|------------|
| **Produzione** | Factory + `viewModel(factory = …)` solo owner top | `DefaultInventoryRepository(…)` |
| **Test** | Ctor diretto | MockK **o** repository su Room in-memory |
| **Deprecato** | Ctor default + `viewModel()` implicito (reflection) | — |

*(Dettaglio narrativo originale conservato in **§ Execution** se serviva audit wiring.)*

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

- **Seam / wiring:** **§1a** (ctor `repository` + factory solo owner; schermate senza `viewModel()` locale).
- **Doppio `InventoryRepository` nei test:**

| Livello | Scelta | Note |
|---------|--------|------|
| **Primario** | **MockK** `relaxed` + `coEvery` / `coVerify` mirati | Matrice §3 (D1–D14) |
| **Ripiego** | 1–2 test con **`DefaultInventoryRepository`** + Room in-memory | Es. export con DB vuoto/popolato |
| **Non fare** | `FakeInventoryRepository` monolitico su tutta l’interfaccia | Drift e costo di manutenzione |

- Errori tipo **`SQLiteConstraintException`:** `coEvery { … throws … }` — niente fake dedicato.
- Il mock **non** sostituisce la logica nel ViewModel.

#### `ExcelViewModel`

- **Seam / wiring:** **§1a** (stesso modello di `DatabaseViewModel`).
- **Repository in test:** tabella sopra — **MockK** primario; **stub minimo locale** (inner class / object nello stesso file test) **solo** se un `Flow`/stato risulta più leggibile — **mai** implementazione completa parallela di `InventoryRepository`.
- **Ambiente:** Robolectric + JVM, **senza** UI test Compose.
- **`exportToUri`:** vedi **gerarchia §3** (tabella sotto) e §5 — **nessun** file reale obbligatorio su disco host in CI.

---

### 2) Ambiente tecnico di test (combinazione e motivazione)

| Componente | Uso | Motivo |
|------------|-----|--------|
| **Robolectric** | `Application`/`Context`, DB in-memory, **`ExcelViewModel`** | Stack Android su **JVM** (`test`); runner tipo `RobolectricTestRunner` / AndroidX equivalente; coerente con `./gradlew test`. |
| **Room in-memory** | `DefaultInventoryRepository` e integrazione VM opzionale | SQL reale, vincoli, `insertIfChanged`, transazioni DAO. |
| **`kotlinx-coroutines-test`** | `runTest`, **`StandardTestDispatcher`**, eventuale **`UnconfinedTestDispatcher`** per regole semplici | Tutti i metodi pubblici critici sono `suspend` o `viewModelScope.launch`. |
| **`MainDispatcherRule`** (o equivalente) | Allineare `Dispatchers.Main` nei test dove il codice posta su Main (es. parti di `ExcelViewModel`) | Evita flaky / «Module with Main dispatcher» errors. |
| **Turbine** | Assert su `StateFlow` / `Flow` (`uiState`, `filter`, `historyEntries` dove esposto) | API stabile per aspettative temporali sui flussi. |
| **MockK** (preferito in Kotlin) **o** Mockito | `InventoryRepository` nei test ViewModel (`relaxed` + `coEvery`/`coVerify`) | Vedi tabella primario/ripiego §1 `DatabaseViewModel`. |

**Esplicitamente escluso in questa fase (salvo bloccante tecnico documentato):** suite che richieda **refactor ampio** solo per testare (es. estrarre metà `DatabaseViewModel` in use case); **instrumented `androidTest`** per logica che può restare in `test` con Robolectric.

---

### 3) Matrice minima di casi (per classe)

**Legenda**

| Tag | Significato |
|-----|-------------|
| **Obbl.** | Almeno un `@Test` atteso per la chiusura TASK-004 (salvo sostituzione documentata in Execution). |
| **Opz.** | Facoltativo se già coperto da altro caso della stessa classe. |
| **Gap OK** | Non implementare se **documentato** in §4 — **senza** allargare scope. |

**Gerarchia primario / ripiego / ultima ratio** (import file, analyzer, export):

| Tema | **Primario** | **Ripiego** | **Ultima ratio / evitare** |
|------|--------------|-------------|----------------------------|
| D8 `startImportAnalysis` felice | POI in-test + `cacheDir` + `Uri` | `.xlsx` in `test/resources` | Micro-seam §5; **no** `androidTest` UI; **no** blob opaco |
| D6–D7 `analyzeGridData` / errori | MockK repository + percorso stabile | — | `mockkObject` static **solo** se seam impraticabile **e** documentato |
| E9 `exportToUri` | Shadow/mock `ContentResolver` + `Uri` | Lambda/writer §5 (1–2 righe) | File host CI; layer use-case |

Ogni riga delle tabelle R/D/E = almeno un `@Test` (salvo **Opz.** / **Gap OK**).

#### `DefaultInventoryRepository` (target **≥ 8** test, di cui **≥ 7 Obbl.**)

| Tag | # | Caso | Atteso (alto livello) |
|-----|---|------|------------------------|
| Obbl. | R1 | `addProduct` con prezzi purchase/retail | Inserimento prodotto + righe `ProductPrice` `MANUAL`; timestamp per **pattern/ordine**, non wall-clock (blocco «Assert su timestamp» sotto **`DefaultInventoryRepository`** in §1) |
| Obbl. | R2 | `updateProduct` con prezzi modificati | Update + nuove righe price history via `insertIfChanged` |
| Obbl. | R3 | `applyImport` con `newProducts` + `updatedProducts` con `old*` e nuovi prezzi | Righe `IMPORT_PREV` / `IMPORT`; ordine relativo `prev` vs `current`; no wall-clock esatto |
| Obbl. | R4 | `addSupplier` due volte stesso nome | Idempotenza (stesso fornitore) |
| Obbl. | R5 | `addCategory` due volte stesso nome | Idempotenza |
| Obbl. | R6 | `getFilteredHistoryFlow` **oppure** `getPriceSeries` (**uno**) | Coerenza dopo insert; **non** matrice completa filtri data |
| Obbl. | R7 | `getCurrentPriceSnapshot` **oppure** `getCurrentPricesForBarcodes` (**uno**) | Snapshot/map coerenti dopo CRUD prezzi |
| Opz. | R8 | `recordPriceIfChanged` / `getLastPrice` | Solo se **non** già implicito in R2/R3 — altrimenti omettere e annotare |

#### `DatabaseViewModel` (target **≥ 11** test, tutti **Obbl.** salvo nota)

**Setup test:** ctor + repository iniettato (**§1a**); **`runTest` + Turbine** dove serve; **nessuna** factory di produzione nei test.

| Tag | # | Caso | Atteso |
|-----|---|------|--------|
| Obbl. | D1 | `addProduct` success | `UiState.Success` |
| Obbl. | D2 | `addProduct` → `SQLiteConstraintException` | `UiState.Error` barcode duplicato |
| Obbl. | D3 | `updateProduct` success | `UiState.Success` |
| Obbl. | D4 | `updateProduct` → errore | `UiState.Error` |
| Obbl. | D5 | `deleteProduct` success | `UiState.Success` |
| Obbl. | D6 | `analyzeGridData` happy path | `Idle` + risultato analysis (se interno opaco → MockK + `Idle`, documentare) |
| Obbl. | D7 | `analyzeGridData` errore | `UiState.Error` — preferire seam §5; **`mockkObject` static** solo **ultima ratio** documentata |
| Obbl. | D8 | `startImportAnalysis` success | Seguire **tabella gerarchica** in cima a §3: **POI in-test** → ripiego **XLSX statico** → **micro-seam** §5 |
| Obbl. | D9 | `startImportAnalysis` errore | `Uri` invalido / validazione / eccezione analisi → `UiState.Error` (Turbine) |
| Obbl. | D10 | `exportToExcel` DB vuoto | `UiState.Error` `error_no_products_to_export` |
| Obbl. | D11 | `exportToExcel` con dati | `UiState.Success` — shadow/mock resolver |
| Obbl. | D12 | `consumeUiState()` | `Idle` |
| Obbl. | D13 | `clearImportAnalysis()` | Reset coerente |
| Obbl. | D14 | `importProducts` success | `applyImport` + history; `Success` |

**Gap OK (solo con nota §4):** happy path D8 **non** stabile dopo tentativi POI + statico → coprire **D9** + validazione e delegare parsing profondo a **TASK-005**.

#### `ExcelViewModel` (target **≥ 10** test, tutti **Obbl.**)

Istanziazione: **§1a** + Robolectric.

| Tag | # | Caso | Atteso |
|-----|---|------|--------|
| Obbl. | E1 | `toggleColumnSelection` su colonna essenziale | Nessun cambio |
| Obbl. | E2 | `toggleColumnSelection` non essenziale | Toggle |
| Obbl. | E3 | `toggleSelectAll` | Essenziali sempre selezionate; altre all/select-none |
| Obbl. | E4 | `generateFilteredWithOldPrices` | `insertHistoryEntry`; stato griglia/coerente; `uid` > 0 |
| Obbl. | E5 | `loadHistoryEntry` | Stato allineato all’entry |
| Obbl. | E6 | `updateHistoryEntry(uid)` | `updateHistoryEntry` con campi aggiornati |
| Obbl. | E7 | `renameHistoryEntry` | `updateHistoryEntry` con nuovo nome/supplier/category |
| Obbl. | E8 | `deleteHistoryEntry` | `deleteHistoryEntry` invocato |
| Obbl. | E9 | `exportToUri` | **Tabella gerarchica §3** (resolver/Uri **primario**; seam §5 **ripiego**) — **no** file host CI |
| Obbl. | E10 | `createManualEntry` / `addManualRow` | `HistoryEntry` manuale; liste interne popolate |

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

| Obbl. / Opz. | Deliverable |
|--------------|-------------|
| **Obbl.** | `DefaultInventoryRepositoryTest.kt`, `DatabaseViewModelTest.kt`, `ExcelViewModelTest.kt` |
| **Opz.** | `FakeInventoryRepository.kt` **non** richiesto. Stub/fake condiviso solo se **minimale** (pochi metodi); preferire **inner class** nel file test. |

#### Dipendenze test (in `app/build.gradle.kts` + `libs.versions.toml`)

| Obbl. / Opz. | Pacchetti |
|--------------|-----------|
| **Obbl.** (se mancanti al momento dell’Execution) | Robolectric, `kotlinx-coroutines-test`, Turbine, MockK (o Mockito-Kotlin), `androidx.arch.core:core-testing` se serve, AndroidX Test core/runner se richiesto da Robolectric |
| **Opz.** | Estensioni aggiuntive **solo** se necessità tecnica documentata |

*Allineamento versioni: congelare in Execution.*

#### Numero minimo target (`@Test`)

| Classe | Minimo | Note |
|--------|--------|------|
| `DefaultInventoryRepository` | **8** | **≥ 7 Obbl.** (R1–R7); R8 **Opz.** |
| `DatabaseViewModel` | **≥ 11** | Matrice §3 (D1–D14); conteggio effettivo in **§ Execution** |
| `ExcelViewModel` | **10** | E1–E10 tutti **Obbl.** |
| **Totale** | **≥ 29** | Gap D8 documentato → conteggio ridotto ammesso con **Gap OK** §4 |

#### Rischi residui (post-planning)

- Robolectric + Room + API recenti possono richiedere **config manifest/shadow** aggiuntiva.
- `ExcelViewModel` accoppiato a Compose Runtime: possibili warning o necessità di inizializzazione composition (se emergono, documentare; non espandere scope).
- **`analyzeGridData` / `ImportAnalyzer`:** preferire **workbook POI in-test** (§3 D8) + **MockK** su repository; **micro-seam** §5 solo se fragile; **mockk static** su object Kotlin solo se la seam è esplicitamente respinta e documentata come ultima ratio.

#### Checklist pre-Execution _(storico — completata 2026-03-28)_

- [x] Voci tecniche congelate (vedi **§ Execution** per esito: factory + owner, D8 POI, MockK, 34 test).
- [x] Owner unico VM; `DatabaseScreen` senza default `viewModel()` (verificato in audit Execution).
- [x] `assembleDebug` + suite `./gradlew test` verde.
- [x] Dipendenze test presenti (`libs` / `build.gradle.kts`).
- [x] Matrice §3 coperta o gap §4 documentati.

---

### Congelamento tecnico _(chiuso in Execution; non è un backlog aperto)_

| Tema | Esito documentato (riferimento) |
|------|----------------------------------|
| Factory + owner | **§1a** + audit **§ Execution** (wiring già coerente). |
| Ctor `repository` | Seam test; produzione via factory. |
| D8 / import | **POI in-test** (Execution); gerarchia §3 per eventuali iterazioni future. |
| MockK vs repo reale | MockK predominante; integrazione leggera opzionale. |

---

### Analisi (sintesi)

Room + `AndroidViewModel` → stack **§2**; wiring produzione vs test → **§1a**; motivazioni condensate → **§ Razionale sintetico**.

### Piano di esecuzione (ordine)

1. Aggiungere dipendenze test (Robolectric, coroutines-test, Turbine, MockK).
2. Implementare **costruttore** con `repository` + **factory** §1a; **`NavGraph`** = unico owner `viewModel(factory = …)`; **`DatabaseScreen`** = solo parametro VM (niente creazione locale); inventario `grep` per altri siti; **smoke** navigazione + `assembleDebug`.
3. Implementare `DefaultInventoryRepositoryTest` con DB in-memory (matrice §3.R).
4. Implementare `DatabaseViewModelTest` con **MockK** (+ Turbine) e integrazione leggera opzionale (matrice §3).
5. Implementare `ExcelViewModelTest` con Robolectric e **MockK** su repository (matrice §3.E).
6. `./gradlew test` + `assembleDebug` + `lint`; log **Execution** con gap §4 se applicabile.

### Rischi identificati (nel planning)

- Combinazione Robolectric/Room/Kotlin 2.x: stack attuale = **Kotlin 2.3.20, AGP 9.1.0, KSP 2.3.2, Room 2.8.4, compileSdk/targetSdk 36**. Mitigare con versioni Robolectric allineate (verificare compatibilità API 36 target) e test «hello world» prima della suite piena.
- **Scope VM:** mitigato da **owner unico** §1a (NavGraph); se emergono route annidate che richiedono scope diverso, documentare in Execution (fuori dal default pianificato).
- Accoppiamento statico `ImportAnalyzer` / `readAndAnalyzeExcel`: mitigare con gap documentati o micro-seam **solo** se necessario.
- **Scope creep:** mitigare rispettando §3–§5 e delegando parsing a **TASK-005**.

---

### Razionale sintetico (perché questa strategia)

1. **Niente fake repository monolitico:** `InventoryRepository` ha molte operazioni; un fake completo duplica la semantica di produzione, va in drift e costa più dei **MockK mirati** che verificano solo i metodi toccati da ogni test.
2. **Niente instrumented UI test come spina dorsale:** la logica di repository/ViewModel è verificabile su **JVM** con Robolectric + Room in-memory; `androidTest` Compose/Espresso sarebbe più lento, più fragile in CI e **fuori perimetro** TASK-004.
3. **Niente refactor architetturale non richiesto:** use case layer / estrazioni ampie solo per test allargano il rischio di regressioni funzionali; **seam minime** (ctor, factory, eventuale lambda §5) massimizzano testabilità con **diff piccolo**.
4. **Robolectric + Room in-memory + MockK + coroutine test (+ Turbine):** copre `AndroidViewModel`, SQL reale, `viewModelScope`/`suspend` e `Flow` in un unico **task** `./gradlew test`, allineato al backlog e alla baseline regressione citata nel `MASTER-PLAN`.
5. **Factory + owner unico (§1a):** evita doppi `ViewModelStore` e rende il wiring **ispezionabile** senza reflection — stesso comportamento runtime, migliore governabilità.

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
| 3 | Nessun **nuovo** errore/warning lint **nei file toccati da TASK-004**; lint **globale** può restare rosso per debito preesistente | `./gradlew lint` + audit report | ESEGUITO | Report: nessuna issue sui file del task; residui fuori scope (es. `GeneratedScreen`, `ImportAnalysisScreen`) |
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
