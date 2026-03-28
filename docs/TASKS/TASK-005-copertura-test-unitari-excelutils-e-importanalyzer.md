# TASK-005 — Copertura test unitari — ExcelUtils e ImportAnalyzer

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-005                   |
| Stato              | DONE                       |
| Priorità           | ALTA                       |
| Area               | Test / Qualità             |
| Creato             | 2026-03-28                 |
| Ultimo aggiornamento | 2026-03-28 (chiusura: conferma utente → DONE; successore attivo **TASK-007**) |

---

## Dipendenze

- TASK-001 (DONE) — governance e baseline
- TASK-004 (DONE) — test unitari repository/ViewModel; questo task estende la copertura alle utility e all’analisi import

---

## Scopo

Aggiungere test unitari **JVM** (JUnit4, Robolectric dove serve `Context`, mockk per repository) per:

1. **`ExcelUtils.kt` — sottoinsieme esplicito**  
   API **pubbliche** stabili: `parseNumber`, `formatNumberAsRoundedString`, `formatNumberAsRoundedStringForInput`, **`analyzePoiSheet`**, **`getLocalizedHeader`**.  
   Il **mapping alias → chiave canonica (camelCase)** e la normalizzazione testo header vivono nella **pipeline privata** dietro `analyzeRows` (mappa `KNOWN_EXCEL_HEADER_ALIASES`, normalizzazione NFD/lowercase, ecc.). **Nel sorgente attuale del repo** esistono anche helper **`internal`** (es. `normalizeExcelHeader` e risoluzione lookup per header grezzo); **non** sono superficie contrattuale e **non** vanno invocati dai test nel perimetro standard — la regressione richiesta passa da **`analyzePoiSheet` → `analyzeRows`**.  
   Catena **POI in-memory → `analyzePoiSheet`**: stesso tipo di percorso dati del ramo **BIFF/XLSX** di `readAndAnalyzeExcel`, **senza** `Uri`/`ContentResolver`. **Fuori perimetro:** `readAndAnalyzeExcel` nella sua interezza, **HTML/Jsoup** e `parseExcelHtmlToRows` (vedi «Non incluso»).

2. **`ImportAnalyzer` in `util/ImportAnalysis.kt`**  
   `analyze()` e `analyzeStreaming()`: nuovi prodotti, aggiornamenti, **duplicati** (vedi sotto), validazioni, **supplier/category** con verifica sia delle **chiamate a `addSupplier` / `addCategory`** sia della **non-chiamata** quando l’entità è già nota (cache DB o `find*`). Confronti numerici (tolleranza), testuali **case-insensitive** dove il codice lo fa, supplier/category senza **falso** `changedField`.  
   **Duplicati stesso barcode:** emesso **`DuplicateWarning`** con **`rowNumbers`** (tutte le occorrenze **1-based**, ordine di comparsa); la **quantità** nel `finalRow` è **aggregata**; per **tutti gli altri campi** vale **last row wins**. Se **dopo il merge** la riga logica fallisce una validazione, gli errori **`RowImportError`** usano **`rowNumber`** riferito all’**ultima occorrenza del gruppo** (non alla prima né a un indice «medio»): in **`analyze`** coincide con la riga dell’ultimo elemento del gruppo (`originalRowIndex + 1`); in **`analyzeStreaming`** con **`p.rowNumbers.last()`**.  
   **`analyzeStreamingDeferredRelations()`** resta **fuori perimetro**.

**Estrema ratio (non default):** se in Execution emergesse un buco di osservabilità senza duplicare logica, una micro-modifica non funzionale (es. `@VisibleForTesting`) andrebbe **solo** documentata in Execution come eccezione a **S1** — **non** è parte del piano base.

---

## Contesto

TASK-004 ha coperto repository/ViewModel con test JVM. Questo task aggiunge regressione su **numeri e formattazione** (`parseNumber` / `formatNumber*`), **pipeline foglio POI** (`analyzePoiSheet`, senza stack HTML), **stringhe header UI** (`getLocalizedHeader`), e **`ImportAnalyzer`**. Nessun test UI; **nessun** test del percorso **HTML salvato come .xls** / Jsoup.

---

## Non incluso

- **`readAndAnalyzeExcel`**, **`Uri`**, **`ContentResolver`**
- **Percorso HTML** / Jsoup
- **`analyzeStreamingDeferredRelations()`**
- **Nuove dipendenze** di test
- **Test UI / instrumentati**
- **Fix lint** non richiesti

---

## File potenzialmente coinvolti

### Sorgenti
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportAnalysis.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/Product.kt`, `Supplier.kt`, `Category.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductUpdate.kt`, `RowImportError.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`

### Test da creare
- `app/src/test/.../util/ExcelUtilsTest.kt`
- `app/src/test/.../util/ImportAnalyzerTest.kt`

### Riuso
- `MainDispatcherRule.kt`

---

## Criteri di accettazione

I criteri **primari** sono **comportamentali**. I conteggi minimi sono **supporto**.

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| **B1** | **`parseNumber` / formattazione:** copertura dei casi nel piano, inclusi **formati ambigui documentati** (`"1,234"`, `"1.234"`, stringhe con spazi laterali) con assert sul **valore `Double?` effettivo** restituito oggi dal codice (obiettivo: fissare comportamento, non «assumere» US/EU) | B | ✅ |
| **B2** | **`getLocalizedHeader`:** almeno un caso **chiave nota** → stringa risorsa attesa; almeno un caso **chiave sconosciuta** → fallback **`else`**: ritorna la **chiave stessa** | B | ✅ |
| **B3** | **`analyzePoiSheet` — core obbligatorio** (Robolectric + POI in-memory): almeno (i) **happy path minimo** (header + righe dati, output coerente/sanity); (ii) **alias di colonna** → chiavi **camelCase** nella header finale; (iii) almeno un caso che fissi la **normalizzazione righe lato POI** prima di `analyzeRows`: `DataFormatter` + **`trim()`** per cella, **`dropLastWhile { empty }`** sulla lista celle, **esclusione righe** senza alcun contenuto non-blank dopo tale pipeline (vedi **C3** nel piano). Copertura mapping header **indiretta** (nessun helper `internal` obbligatorio) | B | ✅ |
| **B3b** | **`analyzePoiSheet` — avanzato / best effort (fortemente desiderato, non bloccante da soli):** tentare test per **summary rows**, **colonne vuote**, **ensureColumn / colonne minime** (`barcode`, `productName`, `purchasePrice`), **foglio senza header vero** (header generati). Se l’**euristica** di `analyzeRows` rende un caso **instabile, flaky o eccessivamente costoso**, documentare in **Execution** (motivo, cosa è stato saltato) e **non** considerare il task fallito **solo** per assenza di uno di questi sotto-casi | S | ✅ |
| **B4** | **Nessun test diretto obbligatorio** su helper **`internal`** di normalizzazione/lookup header; la fonte di verità per il mapping resta **B3** (+ **B3b** opzionale). Eventuali chiamate dirette restano fuori contratto accettazione | S | ✅ |
| **B5** | **`ImportAnalyzer.analyze`:** come nel piano (**add** / **non-add**, tolleranza, testo, changed field, **duplicati** last-row + qty + **`DuplicateWarning.rowNumbers`**); in più almeno un caso **duplicati** in cui il **merge** produce fallimento di validazione (es. **`discount`** fuori range, **`retailPrice`** invalido su prodotto nuovo/esistente secondo regole codice) → **`RowImportError.rowNumber`** deve essere l’indice **1-based dell’ultima occorrenza** del gruppo, **non** della prima | B | ✅ |
| **B6** | **`analyzeStreaming`:** caso base; **duplicati cross-chunk** con stessa semantica di warning / last-row / qty; **stesso vincolo su errori post-merge:** `rowNumber` = **`p.rowNumbers.last()`** (ultima occorrenza nella scansione) quando la riga mergeata fallisce una validazione | B | ✅ |
| **B7** | `./gradlew test` e `./gradlew assembleDebug` passano | B | ✅ |
| **S1** | Produzione: **nessuna modifica prevista**; micro-modifica solo testabilità → documentata in Execution, minima, non comportamentale (**estrema ratio**) | S | ✅ *(eccezione autorizzata: fix lint non funzionale `GeneratedScreen` / `ImportAnalysisScreen` — sezione **Fix**)* |
| **S2** | Stack come TASK-004 (JUnit4, Robolectric, mockk, `runTest` + `MainDispatcherRule`) | S | ✅ |
| **S3** | `ImportAnalyzer` con mock repository, **senza DB reale** | S | ✅ |
| **N1** *(supporto)* | `ExcelUtilsTest.kt`: ≥ **18** `@Test` | S | ✅ |
| **N2** *(supporto)* | `ImportAnalyzerTest.kt`: ≥ **18** `@Test` | S | ✅ |

Legenda: B=Build, S=Static

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Test JUnit puri per `parseNumber` e `formatNumber*` (nessun `Context`) | API pubbliche stabili | 2026-03-28 |
| 2 | Robolectric per `Context` in `getLocalizedHeader`, `analyzePoiSheet`, `ImportAnalyzer` | Allineato TASK-004 | 2026-03-28 |
| 3 | **Mapping header:** default = **solo indiretto** via `analyzePoiSheet` → `analyzeRows` | Superficie utile = pipeline foglio; evitare accoppiamento a singoli simboli `internal` | 2026-03-28 |
| 4 | Esporre helper solo come **estrema ratio** in Execution | Evita dipendenza da visibilità `internal` come piano standard | 2026-03-28 |
| 5 | Mock repository con **`addSupplier` / `addCategory`** + **`find*`** + **`getAll*`** | Spec reale `ImportAnalysis.kt` | 2026-03-28 |
| 6 | `analyzeStreamingDeferredRelations` fuori task | ROI / complessità | 2026-03-28 |
| 7 | **Execution:** preferire **helper/fixture riusabili** nei file test (vedi sotto) | Meno duplicazione, assert più stabili — **senza** nuove dipendenze | 2026-03-28 |
| 8 | **Disciplina località:** helper **privati nel file** di test per default; **`testutil`** solo se condiviso da **`ExcelUtilsTest`** e **`ImportAnalyzerTest`** o duplicazione reale | Test semplici, leggibili, rifattorizzabili; evitare astrazioni premature | 2026-03-28 |

---

## Planning (Claude)

### Verifica codice — API header (repo attuale)

In `ExcelUtils.kt` (stato attuale workspace): **`normalizeExcelHeader`** è una funzione **`internal`**; esiste anche **`internal fun canonicalExcelHeaderKey(rawHeader: String): String?`**, che applica la normalizzazione e il lookup sulla mappa pre-calcolata. **Non** sono API pubbliche. Il piano **non** assume che il nome di quel secondo simbolo resti immutabile nel tempo: l’obiettivo di test resta la **pipeline** esposta da **`analyzePoiSheet`**. Test diretti su `internal` = fuori criteri **B4**; **B3 / B3b** regolano cosa deve essere dimostrato a livello comportamentale.

### `parseNumber` — comportamento ambiguo (documentazione)

Implementazione attuale: `trim()`; poi regex EU/US per migliaia+decimali; altrimenti `replace(",", ".")` + `toDoubleOrNull()`.

- **Spazi laterali:** gestiti da `trim()`.
- **`"1,234"`** e **`"1.234"`:** ricadono nelle regex EU/US in modi che **non** corrispondono sempre all’interpretazione «umana» (migliaia vs decimali). I test devono **assertare il valore effettivo** oggi (es. entrambi possono risultare **`1.234`** come double — verificare al momento dell’implementazione) e servono da **documentazione** contro assunzioni future errate.

### `getChangedFields` (ImportAnalyzer) — riferimento pianificazione

- Prezzi/quantità: tolleranza **`PRICE_COMPARISON_TOLERANCE = 0.001`** (`abs` su differenza).
- **`productName`**, **`itemNumber`**: confronto **case-insensitive**.
- **`secondProductName`:** confronto **case-sensitive** (`.equals` senza `ignoreCase`).
- **Supplier/category:** se `supplierId` / `categoryId` differiscono ma il **nome risolto** (via mappe `getAll*`) è uguale **ignoreCase**, **non** si aggiunge `field_supplier` / `field_category`.

### ImportAnalyzer — duplicati stesso barcode (formalizzazione codice)

**`analyze`:** dopo `groupBy` sul barcode, se il gruppo ha più righe: viene aggiunto `DuplicateWarning(barcode, rowNumbers = group.map { it.index + 1 })` (indici **1-based**, ordine = ordine delle occorrenze nella lista `importedRows`). La mappa `finalRow` è una copia della **`value` dell’ultima** coppia `(index, row)` del gruppo (`group.last()`), poi `quantity` viene **sovrascritta** con la **somma** dei contributi riga per riga (`realQuantity` se `> 0`, altrimenti `quantity`). Si imposta **`originalRowIndex = lastRowInfo.index`** (0-based nella lista import): i **`RowImportError`** successivi usano **`rowNumber = originalRowIndex + 1`** (1-based) — coincide con l’**ultima** occorrenza del gruppo, anche se il contenuto di `finalRow` deriva dal merge di più righe.

**`analyzeStreaming`:** per ogni barcode, `Pending` mantiene `lastRow` aggiornato con **`putAll` dell’ultima riga vista** (stessa idea **last row wins** per i campi non aggregati lì), `rowNumbers` come lista append degli indici **1-based** globali in ordine di lettura, `qtySum` incrementato per ogni occorrenza. In chiusura, `quantity` nel `finalRow` è `qtySum`; se più di un indice, stesso tipo di `DuplicateWarning` con quella lista. I **`RowImportError`** post-merge usano **`rowNumber = p.rowNumbers.last()`** (ultima occorrenza nella scansione), coerente con `analyze`.

**Nota UX/diagnostica:** il **warning** elenca **tutte** le righe duplicate; un **errore** su riga mergeata punta alla **sola ultima** riga del gruppo — comportamento intenzionale nel codice attuale.

### Piano di esecuzione

#### Strategia implementativa (Execution) — efficienza e manutenibilità

Durante **Execution**, l’esecutore **preferisce** strutture **riusabili** nello stesso modulo test (**nessun** nuovo modulo, **nessuna** dipendenza aggiuntiva), per ridurre copia-incolla e fragilità.

**Disciplina — dove mettere gli helper (decisione #8):**

- **Default:** helper/fixture **privati** (o visibilità file) **nel singolo** `*Test.kt` che ne ha bisogno — massima leggibilità a colpo d’occhio.
- **`testutil/`:** estrarre codice condiviso **solo se** è **effettivamente** usato da **entrambi** `ExcelUtilsTest.kt` e `ImportAnalyzerTest.kt`, **oppure** se la duplicazione tra metodi nello stesso file diventa **oggettivamente** ingovernabile senza un unico punto.
- **Evitare** mini-framework di test, gerarchie astratte o layer «generici» che nascondono i dati del caso — se un helper rende il test **meno** chiaro, preferire duplicazione locale limitata e inline.

| Area | Suggerimento |
|------|----------------|
| Righe import | Builder o helper **nel file** che restituiscono `Map<String, String>` (chiavi canoniche) con default e override nominati |
| Entità DB | Factory/helper **locali** per `Product`, `Supplier`, `Category`; promozione a `testutil` solo se condivise tra i due file test (o duplicazione reale) |
| Mock repository | Setup **nel file** `ImportAnalyzerTest` (funzione privata o inner) per `getAll*` / `find*` / `add*`; niente «framework» mock generale |
| Funzioni pure | **`parseNumber`** / `formatNumber*`: tabelle `(input, expected)` e loop in `@Test` — **parameterized manuale**; **senza** aggiungere `junit-jupiter-params` se non già nel progetto |
| Assert dominio | Piccole funzioni **private** per `DuplicateWarning`, `RowImportError`, `changedFields` **solo se** ripetute molte volte nello stesso file |

Obiettivo: stesso **perimetro funzionale** (N1/N2, B1–B7), test **semplici** e **facili da rifattorizzare**, senza struttura inutile.

#### Fase A — `ExcelUtilsTest.kt`

**A.1 `parseNumber` (JUnit)**  
Oltre ai casi già noti (null, blank, US/EU espliciti, negativo, non numerico), aggiungere **obbligatoriamente**:  
- `"1,234"` → valore effettivo documentato  
- `"1.234"` → valore effettivo documentato  
- stringa numerica con **spazi** (es. `"  10.5  "`) → dopo `trim`, valore atteso  

**A.2 `formatNumber*` (JUnit)**  
Invariato: null → `"-"` vs `""`, arrotondamento, zero.

**A.3 `getLocalizedHeader` (Robolectric)**  
- Chiave nota (es. `purchasePrice`) → `context.getString(R.string.header_purchase_price)`  
- Chiave **sconosciuta** (es. `"unknownCustomKey"`) → **ritorno identico alla chiave** (ramo `else` in `getLocalizedHeader`)

**A.4 `analyzePoiSheet` (Robolectric + workbook POI in-memory)**  
Pipeline: solo **`analyzePoiSheet`** → `analyzeRows` (nessun `readAndAnalyzeExcel`, nessun HTML).

**Comportamento reale (estrazione righe, prima di `analyzeRows`):** per ogni riga POI non nulla, il codice usa **`DataFormatter.formatCellValue`**, applica **`trim()`** a ogni cella, rimuove le **celle vuote finali** con **`dropLastWhile { it.isEmpty() }`**, e **non aggiunge** la riga se, dopo questi passi, **nessun elemento è `isNotBlank()`** (riga solo spazi/vuota → ignorata). Una riga con `Row == null` viene saltata (`continue`).

**Core (obbligatorio — allineato a B3)**  
| # | Scenario | Cosa verificare (indicativo) |
|---|----------|------------------------------|
| **C1** | **Alias header** (es. `"Nombre del Producto"`, `"prezzo acquisto"`) | Nella lista header finale compaiono chiavi **camelCase** attese (`productName`, `purchasePrice`, …) |
| **C2** | **Happy path minimo** | Header riconoscibile + almeno una riga dati; `Triple` (header, rows, headerSource) coerente e non vuoto dove atteso |
| **C3** | **Normalizzazione dati da POI** (almeno **un** `@Test` dedicato, può combinare i punti) | (a) celle testuali/numeriche con **spazi laterali** → stringhe nei `List` passati a `analyzeRows` **senza** quegli spazi esterni; (b) **celle vuote in coda** alla riga **non** presenti nella riga normalizzata (effetto `dropLastWhile`); (c) una **riga interamente vuota** (o solo blank dopo trim) **non** compare tra le righe materializzate verso `analyzeRows` |

**Avanzato / best effort (fortemente desiderato — allineato a B3b; non bloccante se solo questi falliscono per euristica)**  
| # | Scenario | Cosa verificare (indicativo) |
|---|----------|------------------------------|
| A1 | Colonne **vuote** su tutte le righe dati | Effetto `nonEmptyCols` / colonne rimosse come da codice |
| A2 | Riga **summary / subtotale** | Esclusa da `dataRows` (filtro `isSummaryRow`) |
| A3 | **ensureColumn** / colonne minime | Presenza attesa di `barcode`, `productName`, `purchasePrice` (generate o mappate) |
| A4 | **Nessun header vero** (`hasHeader == false`) | Header con prefisso generato + `headerSource` `"generated"` |

**Nota:** C1–C3 devono restare **stabili** (C3 è deterministica lato estrazione POI). Per A1–A4, se il costrutto del foglio POI non innesca in modo **affidabile** il ramo voluto, documentare in **Execution** e proseguire — **B3** resta soddisfatto senza A1–A4.

#### Fase B — `ImportAnalyzerTest.kt` (Robolectric + mockk)

**B.1 Supplier / category — creazione**  
- Nuovi nomi → `coVerify { addSupplier }` / `addCategory` (con `coEvery` che restituisce entity con `id`).

**B.2 Supplier / category — NON creare inutilmente**  
- **Supplier già in `getAllSuppliers()`** (o categoria in `getAllCategories()`): import con stesso nome → **`addSupplier` / `addCategory` NON chiamati** (es. `coVerify(exactly = 0)` o equivalente).  
- **Non in lista iniziale** ma **`findSupplierByName` / `findCategoryByName`** restituisce entità → stesso risultato: **nessuna** `add*` (verificare ordine chiamate reali: cache nome → find → add).

**B.3 Confronti numerici**  
- **Dentro tolleranza** (`≤ 0.001` dopo `round3` dove applicato al flusso): **nessun** `ProductUpdate` se unico delta è prezzo/retail/qty.  
- **Oltre tolleranza:** compare `updatedProducts` con `changedFields` che includono la risorsa attesa (purchase/retail/stock).

**B.4 Confronti testuali**  
- **`productName` o `itemNumber`**: stesso contenuto **ma case diverso** → **nessun** update (o `changedFields` senza quel campo — allineare all’implementazione `getChangedFields`).  
- **`secondProductName`:** se il codice tratta case-sensitive, un caso che mostri differenza solo per maiuscole **può** generare update (documentare esito reale).

**B.5 Supplier/category — nessun falso changed field**  
- Due `supplierId` diversi ma **nome fornitore equivalente** ignoreCase (mock `getAllSuppliers` che risolvono i nomi uguali) → **`field_supplier` non** in `changedFields`. Stesso schema per **category**.

**B.6 Duplicati — `analyze`**  
- **Last row wins (campi non-quantità):** almeno due righe stesso barcode con **es. `productName` o `purchasePrice` diversi** tra prima e ultima riga → nel risultato (new/update) i campi non-quantità devono coincidere con l’**ultima** riga; **quantità** = **somma** (con regola `realQuantity`/`quantity` già pianificata).  
- **`DuplicateWarning`:** presenza di un warning con **stesso barcode**; **`rowNumbers`** lista **1-based** nell’**ordine di comparsa** atteso (es. `[2, 5]` se seconda e quinta riga import).  
- **Errore post-merge → ultima riga:** duplicati dove **`finalRow`** (dopo merge) viola una regola (es. **discount** `<0` o `>100` presente sull’ultima riga, **retail** mancante/≤0 per nuovo prodotto, ecc.) → un solo `RowImportError` (o l’errore atteso) con **`rowNumber`** uguale al **numero di riga 1-based dell’ultima** occorrenza del gruppo (verificare contro fixture).  
- (Opzionale) tre righe duplicate → stessi controlli su ordine e aggregazione.

**B.7 Duplicati — `analyzeStreaming` (cross-chunk)**  
- Stesso barcode in **due chunk** con campi non-quantità diversi tra prima occorrenza e ultima → **ultima vince** per quei campi, **qty** aggregata, **`rowNumbers`** nell’ordine in cui le righe sono state lette (indice globale), **`DuplicateWarning`** coerente.  
- **Errore post-merge:** costruire un caso cross-chunk in cui il merge fallisce validazione → assert su **`RowImportError.rowNumber`** uguale all’**ultima** voce di **`DuplicateWarning.rowNumbers`** per quel barcode (in codice: `p.rowNumbers.last()`), cioè l’ultima occorrenza nella scansione globale.

**B.8+**  
Restano validi: nuovi prodotti, retail nuovo vs esistente, sconti/`discountedPrice`, `prevPurchase`/`prevRetail`, nome lungo troncato, errori validazione, mix, **`analyzeStreaming` caso base** (se non già coperto da B.7).

---

### Rischi identificati

| Rischio | Probabilità | Impatto | Mitigazione |
|---------|-------------|---------|-------------|
| Euristica `analyzeRows` (header detection, soglie %) **fragile** nei test POI | Media | Test flaky o difficili da costruire | **Priorità C1–C3**; casi A1–A4 solo se stabili; altrimenti **B3b** + Execution |
| **`parseNumber` ambiguo:** test documentano comportamento attuale che l’utente potrebbe trovare sorprendente | Bassa | «Falso positivo» di qualità | Commento breve nel test: intento = **regressione**, non specifica prodotto |
| Mock **`getAll*` + `find*` + `add*`** incoerenti | Media | Falsi verdi/rossi | Allineare sequenza reale in `getOrCreateSupplierId` / `getOrCreateCategoryId` |
| `round3` + tolleranza 0.001 | Media | Assert al limite | Scelta valori con margine chiaro dentro/fuori tolleranza |
| Accoppiamento a nomi `internal` (normalizzazione / lookup) | Bassa | Test che si rompono su refactor innocui | **B3/B4**; non citare simboli `internal` come requisito di accettazione |
| **`rowNumbers` duplicati:** in `analyze` dipendono da ordine `groupBy`/lista import; in streaming da contatore globale | Bassa | Assert errati se si confondono 0-based vs 1-based o ordine | Usare indici **1-based** come in produzione; documentare nella fixture l’ordine righe |
| **Warning vs errore riga:** `DuplicateWarning.rowNumbers` elenca **tutte** le occorrenze; `RowImportError.rowNumber` post-merge punta solo all’**ultima** | Bassa | Assert che puntano alla prima riga del gruppo | Separare assert su warning (lista) e su errore (singolo indice = ultima) |

### Baseline TASK-004

- **`./gradlew test`** resta il gate unico suite JVM inclusa baseline TASK-004.  
- Robolectric: stesso runner/SDK pattern dei test esistenti.  
- Execution: log comandi, test aggiunti, limiti residui.

---

## Execution

### Tracking / avvio autorizzato (governance — 2026-03-28)

- **Pianificazione:** considerata **completa**; criteri di accettazione e piano in questo file restano **fonte di verità** per l’implementazione.
- **MASTER-PLAN:** allineato; **TASK-005** è l’**unico** task **ACTIVE** nel backlog; **nessun** altro task ACTIVE concorrente.
- **Autorizzazione:** passaggio a fase **EXECUTION** approvato per l’esecutore (es. Codex); l’**implementazione tecnica** (creazione test, Gradle, log file) **non** è avviata dal planner — vedi sotto.

### Esecuzione — 2026-03-28

**File modificati:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — aggiunti 23 test JVM/Robolectric per `parseNumber`, `formatNumber*`, `getLocalizedHeader` e `analyzePoiSheet` (core + best-effort stabili da planning)
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ImportAnalyzerTest.kt` — aggiunti 18 test JVM/Robolectric per `ImportAnalyzer.analyze()` e `analyzeStreaming()` con repository mockk e fixture locali

**Helper/fixture introdotti (locali al file, nessuna estrazione in `testutil`):**
- `ExcelUtilsTest.kt` — `withSheet(...)` per costruire sheet POI in-memory senza dipendere da `Uri`/`ContentResolver`
- `ImportAnalyzerTest.kt` — helper privati `analyze(...)`, `analyzeStreaming(...)`, `existingProduct(...)`, `importedRow(...)`, `duplicateWarningFor(...)`, `rowErrorFor(...)`

**Azioni eseguite:**
1. Creato `ExcelUtilsTest.kt` mantenendo helper privati locali al file e senza chiamare helper `internal` del parser header.
2. Coperti per `ExcelUtils`: `parseNumber` (null/blank/US/EU/ambigui `"1,234"` e `"1.234"`/spazi/negativi), `formatNumberAsRoundedString`, `formatNumberAsRoundedStringForInput`, `getLocalizedHeader` (chiave nota + fallback chiave sconosciuta).
3. Coperti per `analyzePoiSheet`: alias header → chiavi canoniche, happy path minimo, normalizzazione POI (`DataFormatter`, `trim`, `dropLastWhile`, righe blank ignorate) e tutti i casi best-effort stabili del piano: colonne vuote rimosse, summary row filtrata, `ensureColumn`/colonne minime, foglio senza header esplicito con colonne generate.
4. Creato `ImportAnalyzerTest.kt` con mock `InventoryRepository` e senza DB reale, riusando `MainDispatcherRule` come da stack TASK-004.
5. Coperti per `ImportAnalyzer.analyze()`: add/non-add supplier-category (`getAll*` e `find*`), tolleranza numerica, confronti testuali case-insensitive/case-sensitive, assenza di falso changed field su supplier/category con nomi equivalenti ignoreCase, duplicati same-barcode con `last row wins`, quantità aggregata (`realQuantity` preferita quando > 0), `DuplicateWarning.rowNumbers`, errore post-merge con `rowNumber` sull’ultima occorrenza, `discountedPrice`, alias `prevPurchase`/`prevRetail`, troncamento nome lungo.
6. Coperti per `ImportAnalyzer.analyzeStreaming()`: caso base, duplicati cross-chunk con stessa semantica di warning/merge/qty, errore post-merge con `rowNumber = rowNumbers.last()`.
7. Nessuna modifica al codice di produzione; nessuna nuova dipendenza; nessun refactor fuori scope.
8. Le prime esecuzioni Gradle erano bloccate dall’assenza di `JAVA_HOME` nell’ambiente shell. Per eseguire i check richiesti ho usato il JBR locale di Android Studio (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) senza modificare il progetto.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon assembleDebug` → `BUILD SUCCESSFUL in 8s` |
| Lint                     | ❌ | `JAVA_HOME='...' ./gradlew --no-daemon lint` eseguito ma fallito con `25 errors` e `68 warnings`; prime issue in `GeneratedScreen.kt` e `ImportAnalysisScreen.kt`, fuori scope di TASK-005 |
| Warning nuovi            | ✅ | Nessun warning Kotlin/deprecation introdotto nei file test modificati; i warning osservati sono di tooling preesistenti (AGP/Kotlin plugin/Robolectric) e il report lint non cita `ExcelUtilsTest.kt` o `ImportAnalyzerTest.kt` |
| Coerenza con planning    | ✅ | Modificati solo i due file test previsti; nessuna dipendenza nuova; nessuna modifica produzione; helper locali al file come da decisione #8 |
| Criteri di accettazione  | ✅ | Tutti i criteri B1–B7, S1–S3, N1–N2 verificati con evidenze sotto |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  `JAVA_HOME='...' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ExcelUtilsTest'`
  `JAVA_HOME='...' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest'`
  `JAVA_HOME='...' ./gradlew --no-daemon test`
- Suite rilevate da `./gradlew test`:
  `DefaultInventoryRepositoryTest` = 8 test verdi
  `DatabaseViewModelTest` = 14 test verdi
  `ExcelViewModelTest` = 12 test verdi
  `ExcelUtilsTest` = 23 test verdi
  `ImportAnalyzerTest` = 18 test verdi
  Totale suite TASK-004 + TASK-005 eseguite: **75 test verdi, 0 failure, 0 error**
- Test aggiunti/aggiornati:
  nuovi `ExcelUtilsTest.kt` (23 test)
  nuovi `ImportAnalyzerTest.kt` (18 test)
- Limiti residui:
  `lint` globale resta rosso per issue preesistenti fuori scope
  fuori perimetro confermato: `readAndAnalyzeExcel`, HTML/Jsoup, `analyzeStreamingDeferredRelations()`

**Dettaglio criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| B1 | ✅ ESEGUITO | `ExcelUtilsTest` copre `parseNumber` e formattazione con null/blank/US/EU/ambigui/spazi/negativo; XML report: 23 test, 0 failure |
| B2 | ✅ ESEGUITO | `ExcelUtilsTest` verifica `purchasePrice -> context.getString(...)` e fallback chiave sconosciuta |
| B3 | ✅ ESEGUITO | `ExcelUtilsTest` copre alias header, happy path minimo e normalizzazione POI (`trim`, `dropLastWhile`, riga blank ignorata) via `analyzePoiSheet` |
| B3b | ✅ ESEGUITO | `ExcelUtilsTest` copre anche colonne vuote, summary rows, `ensureColumn`/colonne minime e foglio senza header vero con fixture POI stabili |
| B4 | ✅ ESEGUITO | Nessun test diretto su `normalizeExcelHeader`, `canonicalExcelHeaderKey` o altri helper `internal`; regressione esercitata solo tramite `analyzePoiSheet` |
| B5 | ✅ ESEGUITO | `ImportAnalyzerTest` copre `analyze()` per add/non-add, tolleranza, testo, changed fields, duplicati, `rowNumbers`, errore post-merge su ultima occorrenza, `discountedPrice`, `prev*`, troncamento |
| B6 | ✅ ESEGUITO | `ImportAnalyzerTest` copre `analyzeStreaming()` con caso base, duplicati cross-chunk, merge/warning/qty e errore post-merge sull’ultima riga |
| B7 | ✅ ESEGUITO | `./gradlew --no-daemon test` e `./gradlew --no-daemon assembleDebug` entrambi `BUILD SUCCESSFUL` |
| S1 | ✅ ESEGUITO | Nessuna modifica a file di produzione |
| S2 | ✅ ESEGUITO | Stack usato: JUnit4 + Robolectric + mockk + `runTest`; `ImportAnalyzerTest` usa `MainDispatcherRule` |
| S3 | ✅ ESEGUITO | `ImportAnalyzerTest` usa `InventoryRepository` mockk, senza DB reale |
| N1 | ✅ ESEGUITO | `ExcelUtilsTest.kt` contiene 23 `@Test` (>= 18) e report XML con `tests="23"` |
| N2 | ✅ ESEGUITO | `ImportAnalyzerTest.kt` contiene 18 `@Test` e report XML con `tests="18"` |

**Incertezze:**
- Nessuna sul comportamento coperto; i casi best-effort A1–A4 di `analyzePoiSheet` sono stati implementati con fixture risultate stabili.

**Handoff notes:**
- Review focalizzata su coerenza tra criteri pianificati e casi implementati: non ci sono modifiche produzione da validare.
- `lint` non è stato corretto perché le issue segnalate sono preesistenti e fuori scope (`GeneratedScreen.kt`, `ImportAnalysisScreen.kt` nel report debug).
- Stato corretto post-Execution: task pronto per `REVIEW`, non per `DONE`.

---

## Review

### Review — 2026-03-28

**Revisore:** Claude (planner)

**Metodo:** lettura completa dei file test (`ExcelUtilsTest.kt`, `ImportAnalyzerTest.kt`), diff dei file UI (`GeneratedScreen.kt`, `ImportAnalysisScreen.kt`), confronto con codice sorgente di produzione (`ExcelUtils.kt`, `ImportAnalysis.kt`), verifica criteri di accettazione, esecuzione `./gradlew test` e `./gradlew assembleDebug`.

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| B1 | `parseNumber` / formattazione | ✅ | 9 test coprono null, blank, US, EU, ambigui ("1,234"/"1.234"), spazi, negativo; assert sui valori Double effettivi coerenti col codice |
| B2 | `getLocalizedHeader` | ✅ | Chiave nota (`purchasePrice`) e fallback chiave sconosciuta coperti |
| B3 | `analyzePoiSheet` core | ✅ | Alias header → camelCase, happy path, normalizzazione POI (trim, dropLastWhile, blank row) — 3 test dedicati |
| B3b | `analyzePoiSheet` avanzato | ✅ | Colonne vuote, summary row, ensureColumn/colonne minime, foglio senza header — 4 test stabili |
| B4 | Nessun test su `internal` | ✅ | Confermato: nessun import o chiamata a `normalizeExcelHeader`, `canonicalExcelHeaderKey` |
| B5 | `ImportAnalyzer.analyze` | ✅ | add/non-add supplier-category, tolleranza, testo case-insensitive/sensitive, changed field, duplicati, errore post-merge — tutti coerenti col sorgente |
| B6 | `analyzeStreaming` | ✅ | Caso base, cross-chunk duplicati, errore post-merge con `rowNumbers.last()` |
| B7 | Build + test | ✅ | `./gradlew test` BUILD SUCCESSFUL (75 test, 0 failure); `./gradlew assembleDebug` BUILD SUCCESSFUL |
| S1 | Nessuna modifica produzione | ✅ | Confermato: i file test non toccano codice di produzione; i fix UI sono limitati a lint |
| S2 | Stack TASK-004 | ✅ | JUnit4, Robolectric, mockk, `runTest`, `MainDispatcherRule` |
| S3 | Mock repository senza DB | ✅ | `mockk(relaxed = true)` con `coEvery` per `getAll*`, `find*`, `add*` |
| N1 | ≥ 18 test ExcelUtils | ✅ | 23 `@Test` |
| N2 | ≥ 18 test ImportAnalyzer | ✅ | 18 `@Test` |

**Verifica correttezza tecnica dei test:**

- **`parseNumber` ambigui:** `"1,234"` → 1.234 e `"1.234"` → 1.234 confermati contro il codice (fallback `replace(",",".")` + `toDoubleOrNull`). Assert corretti.
- **Duplicati `analyze`:** fixture con 5 righe, barcode "99999999" a indice 1 e 4. `rowNumbers = [2,5]` (1-based), last row wins su campi, `stockQuantity = 12.0` (2.0 + 10.0 con `realQuantity` preferita). Verificato contro `groupBy` + `sumOf` nel sorgente. Corretto.
- **Errore post-merge `analyze`:** discount=150 sull'ultima riga → `error_invalid_discount`, `rowNumber = 4` (indice 3 + 1). Corretto.
- **Cross-chunk streaming:** 2 chunk, barcode duplicato, `rowNumbers = [2,4]`, qty aggregata = 9.0 (2.0 + 7.0 con `realQuantity`). Corretto.
- **Errore post-merge streaming:** `rowNumber = 3` = `rowNumbers.last()` = ultima scansione. Corretto.
- **Supplier/category:** cache `getAllSuppliers` con nome case-insensitive, `findCategoryByName`, `coVerify(exactly = 0)` per `add*`. Sequenza coerente con `getOrCreateSupplierId`.
- **Tolleranza:** 10.001 vs 10.0 = 0.001 ≤ `PRICE_COMPARISON_TOLERANCE` → no update; 10.002 vs 10.0 = 0.002 > 0.001 → update. Corretto.
- **`secondProductName` case-sensitive:** il sorgente usa `!=` (non `ignoreCase`), il test verifica che cambio di case genera update. Corretto.
- **`discountedPrice`:** priorità su `purchasePrice * (1 - discount/100)`. Test corretto.
- **`prevPurchase`/`prevRetail`:** mappati a `oldPurchasePrice`/`oldRetailPrice`. Corretto.
- **Troncamento:** `MAX_PRODUCT_NAME_LENGTH = 100`, `take(100)`. Test con 150 chars → assert length 100. Corretto.

**Verifica qualità test:**

- Helper `withSheet` in ExcelUtilsTest: pulito, minimo, gestisce String e Number. Adeguato.
- Helper `importedRow`, `existingProduct`, `duplicateWarningFor`, `rowErrorFor` in ImportAnalyzerTest: locali al file, nomi chiari, default ragionevoli. Nessuna astrazione eccessiva.
- Nessuna duplicazione inutile tra test.
- Nessun test fragile o accoppiato a dettagli di implementazione `internal`.

**Verifica fix UI (GeneratedScreen.kt, ImportAnalysisScreen.kt):**

- Pattern: `context.getString(R.string.xxx)` → `stringResource(R.string.xxx)` pre-risolto in variabili locali al composable.
- Correzione minima, non funzionale, motivata da lint `LocalContextGetResourceValueCall`.
- Nessun cambio di logica business, navigation, DAO, repository.
- `DisplayProductRow`: variabile `context` rimossa — verificato che non era usata altrove nella funzione. Corretto.
- Newline aggiunta a fine file `ImportAnalysisScreen.kt` — fix cosmetico standard.

**Verifica tracking:**

- File task coerente: stato REVIEW, execution log completo, criteri tutti ✅, baseline TASK-004 documentata.
- MASTER-PLAN coerente: TASK-005 unico ACTIVE, fase REVIEW, nessun altro ACTIVE.

**Problemi trovati:**

- Nessuno.

**Verdetto:** APPROVED

**Note:**
- Nessun fix necessario. Test corretti, coerenti col planning e col codice sorgente.
- Fix UI minimali e giustificati dal lint.
- Pronto per conferma utente → DONE.

### Review — 2026-03-28 (verifica indipendente, iterazione 2)

**Revisore:** Claude (planner)

**Audit iniziale:** Rilettura `ExcelUtilsTest.kt`, `ImportAnalyzerTest.kt`, assenza di `context.getString` in `GeneratedScreen.kt` / `ImportAnalysisScreen.kt`, confronto con `ImportAnalysis.kt` / `ExcelUtils.kt` per duplicati, tolleranza, `rowNumbers`, streaming. **MASTER-PLAN** e file task già allineati su **TASK-005** unico **ACTIVE** in **REVIEW**.

**Problemi trovati:**
- **Minore:** nel test summary row di `ExcelUtilsTest`, l’assert sul barcode usava l’indice fisso `[1]` — fragile se l’ordine colonne della header cambiasse.

**Correzioni applicate:**
- `ExcelUtilsTest.kt` — assert sul barcode della riga dati tramite `header.indexOf("barcode")` invece dell’indice letterale.

**File modificati in questa review:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt`
- `docs/TASKS/TASK-005-copertura-test-unitari-excelutils-e-importanalyzer.md` (questa sezione)

**Verifiche eseguite (ambiente locale, `JAVA_HOME` = JBR Android Studio):**
| Comando | Esito |
|---------|--------|
| `./gradlew --no-daemon :app:testDebugUnitTest --tests '…ExcelUtilsTest'` | BUILD SUCCESSFUL |
| `./gradlew --no-daemon test` | BUILD SUCCESSFUL |
| `./gradlew --no-daemon assembleDebug` | BUILD SUCCESSFUL |

**Nota su S1:** il criterio originario prevedeva «nessuna modifica produzione»; le modifiche a `GeneratedScreen` / `ImportAnalysisScreen` sono **eccezione documentata** nella sezione **Fix** del task (lint autorizzato, non business logic). Coerente con handoff utente.

**Verdetto:** **APPROVED** — review positiva; **nessun altro fix** richiesto. **Stato task:** resta **REVIEW** fino a **conferma esplicita utente** → `DONE` (per CLAUDE.md).

### Chiusura — conferma utente (2026-03-28)

- **Utente:** conferma formale passaggio a **`DONE`**.
- **Stato file task:** aggiornato a **DONE**; **TASK-007** attivato nel **MASTER-PLAN** come unico **ACTIVE** (`PLANNING`).

---

## Fix

### Fix — 2026-03-28

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — corretti gli accessi a risorse via `LocalContext.getString(...)` in composable, callback ed effect sostituendoli con stringhe risolte via `stringResource(...)`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — corretti gli stessi accessi a risorse Compose-safe nei punti segnalati dal lint

**Problemi reali trovati:**
1. `lint` falliva per errori `LocalContextGetResourceValueCall` nei due file UI autorizzati.
2. Le chiamate problematiche erano concentrate in toast, `ScanOptions.setPrompt(...)`, `Snackbar` e `LaunchedEffect`, senza necessità di cambiare la logica funzionale.

**Azioni eseguite:**
1. In `GeneratedScreen.kt` ho introdotto stringhe locali tramite `stringResource(...)` e le ho riusate nei lambda/callback già esistenti, mantenendo invariati flusso utente e comportamento.
2. In `GeneratedScreenInfoDialog` e `ManualEntryDialog` ho applicato lo stesso pattern per i messaggi di toast e per il prompt scanner.
3. In `ImportAnalysisScreen.kt` ho sostituito gli accessi `context.getString(...)` nel toast di export e nel caricamento asincrono di supplier/category in `DisplayProductRow`.
4. Ho verificato con ricerca mirata che non restano `context.getString(...)` nei due file autorizzati.
5. Nessuna modifica a logica business, DAO, repository, modelli, navigation o import logic.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process lint` → `BUILD SUCCESSFUL`; gli errori `LocalContextGetResourceValueCall` nei file autorizzati risultano risolti |
| Warning nuovi            | ✅ | Nessun warning nuovo introdotto nei file toccati; restano solo warning/deprecazioni preesistenti fuori scope in altri file |
| Coerenza con planning    | ✅ | Fix limitato ai due file UI autorizzati e al tracking del task; logica funzionale invariata |
| Criteri di accettazione  | ✅ | I criteri già soddisfatti in Execution restano validi; il fix aggiunge solo la chiusura dei problemi di lint rimasti nel perimetro autorizzato |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process test`
- Test aggiunti/aggiornati:
  nessun test cambiato in questa fase di fix; mantenuta come regressione la baseline verde di TASK-004 + TASK-005
- Limiti residui:
  restano warning/deprecazioni di lint fuori scope in altri file del progetto; nessun errore residuo nei file autorizzati per questo fix

**Incertezze:**
- Nessuna sul comportamento: le correzioni sono non funzionali e limitate al modo in cui le risorse stringa vengono risolte in Compose.

**Handoff notes:**
- Review focalizzata sul fatto che il fix non modifica la logica ma chiude gli errori di lint/code analysis rimasti nei due file autorizzati.
- Stato del task invariato: `REVIEW`, non `DONE`.

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **DONE** |
| Data chiusura          | 2026-03-28 |
| Tutti i criteri ✅?    | Sì (vedi tabella criteri; S1 con eccezione lint UI documentata) |
| Rischi residui         | Lint/warning preesistenti fuori scope in altri file (documentato in Execution/Fix) |

---

## Riepilogo finale

- Aggiunti **ExcelUtilsTest** (23 test) e **ImportAnalyzerTest** (18 test), allineati al piano TASK-005.
- Fix lint **autorizzati** su `GeneratedScreen.kt` e `ImportAnalysisScreen.kt` (stringhe Compose-safe).
- Baseline **TASK-004** + nuovi test: suite JVM verde al momento della chiusura.
- Successore operativo backlog: **TASK-007** (round-trip export full DB).

---

## Handoff

- Verificare in review che la copertura nuova resti confinata al perimetro definito: utility Excel e `ImportAnalyzer`, nessun ramo HTML / `readAndAnalyzeExcel` / `analyzeStreamingDeferredRelations()`.
- I problemi di `lint` nei file autorizzati `GeneratedScreen.kt` e `ImportAnalysisScreen.kt` sono stati corretti nel fix del 2026-03-28; restano eventualmente solo warning/deprecazioni fuori scope in altri file del progetto.
- Se la review richiede fix, intervenire solo sui file test introdotti in questo task salvo emersione di una reale micro-modifica di testabilità non comportamentale.

---

## Elenco casi di test (checklist esecutore)

- [ ] **Struttura:** helper **locali al file** per default; `testutil` solo se usato da **entrambi** i test file o duplicazione reale; niente mini-framework — come da *Strategia implementativa* + decisione #8 (documentare in Execution)

### ExcelUtils
- [ ] **parseNumber:** null, blank, US/EU chiari, negativo, non numerico, **`"1,234"`**, **`"1.234"`**, spazi laterali
- [ ] **formatNumber*** (come prima)
- [ ] **getLocalizedHeader:** chiave nota + **chiave sconosciuta → stessa stringa**
- [ ] **analyzePoiSheet C1 (core)** alias → camelCase
- [ ] **analyzePoiSheet C2 (core)** happy path minimo
- [ ] **analyzePoiSheet C3 (core)** POI: `trim`, celle vuote finali rimosse, riga blank ignorata (un `@Test` può coprire tutti e tre gli aspetti)
- [ ] **analyzePoiSheet A1–A4 (best effort)** colonne vuote, summary, colonne minime, no-header — se instabili: solo Execution
- [ ] _(Fuori contratto)_ test diretti su helper `internal` header — **non** richiesti da B4

### ImportAnalyzer — analyze
- [ ] addSupplier / addCategory quando necessario
- [ ] **NO** add* se già in **getAll*** o trovato da **find***
- [ ] tolleranza: **dentro** → no update; **fuori** → update
- [ ] **productName** / **itemNumber** case-insensitive → no update se solo case
- [ ] **secondProductName** (case-sensitive) — documentare esito reale se solo case
- [ ] supplierId diversi, **stesso nome** ignoreCase → **no** `field_supplier`; analogo category
- [ ] **Duplicati `analyze`:** last row wins su campi non-qty; **qty aggregata**; **`DuplicateWarning.rowNumbers`** 1-based ordine comparsa
- [ ] **Duplicati + validazione fallita:** `RowImportError.rowNumber` = **ultima** occorrenza gruppo (`originalRowIndex + 1`)
- [ ] (resto: retail, discount, discountedPrice, prev*, troncamento nome, errori, mix)

### ImportAnalyzer — analyzeStreaming
- [ ] caso base
- [ ] **cross-chunk duplicati:** last-row / qty / **`rowNumbers`** + warning
- [ ] **cross-chunk + errore post-merge:** `rowNumber` = **`rowNumbers.last()`** (ultima scansione)

_(Fuori: `analyzeStreamingDeferredRelations`, `readAndAnalyzeExcel`, HTML.)_
