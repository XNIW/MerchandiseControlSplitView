# TASK-007 — Export database completo — verifica round-trip

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-007                   |
| Stato              | DONE                       |
| Priorità           | MEDIA                      |
| Area               | Export / Database          |
| Creato             | 2026-03-28                 |
| Ultimo aggiornamento | 2026-03-28 (chiusura: review APPROVED + conferma utente → DONE; successore attivo **TASK-008**) |

---

## Dipendenze

- **TASK-005** (`DONE`, 2026-03-28) — copertura test utility/import analysis
- **TASK-017** (`DONE`) — import DB completo stabile (contesto OOM mitigato)

---

## Scopo

**Correttezza (round-trip):** verificare che l’**export full database** (workbook XLSX con fogli attesi dal codice) seguito da **import completo su un database target isolato e inizialmente vuoto** produca **snapshot dati equivalenti** per **Products**, **Suppliers**, **Categories** e, quando presente il foglio, **PriceHistory**. Lo scenario **non** principale è il re-import sullo stesso DB sorgente (può servire solo come smoke opzionale).

**Robustezza / efficienza (export full DB):** verificare che il percorso **`exportFullDbToExcel`** sia **affidabile** su dataset **realistici** (nessun crash, nessun fallimento ingiustificato dopo secondi di lavoro, nessun OOM nel perimetro verificabile). Contesto noto: su **dispositivo reale** l’export del **solo foglio Products** è stato percepito come funzionante mentre l’export **database completo** ha dato **crash o fallimento** dopo alcuni secondi — il task deve coprire anche questo rischio, **senza** trasformarsi in task UI.

Resta focalizzato su **export/import full DB**, **correttezza dati** e **robustezza tecnica**; dettaglio in **Planning** (matrix round-trip **§6bis**, verifica export **§6ter**, analisi hotspot **§1bis**).

---

## Contesto

Dopo **TASK-017** l’import completo è considerato stabile (path streaming, niente `XSSFWorkbook` DOM sul file in ingresso). Il round-trip export→import su **DB separato** riduce il rischio di perdita o distorsione semantica. In parallelo, la storia su **device** (export full DB fragile vs export solo prodotti) impone di trattare **memoria, I/O e completamento** dell’export full DB come obiettivo esplicito, non solo la logica dei dati. Mappa entrypoint, equivalenza, matrix test, hotspot export: **Planning**.

---

## Non incluso

- **Redesign UI export/import** — esplicitamente fuori scope; direzione UX consigliata e vincoli tecnici collegati sono documentati in **Planning → Follow-up (fuori scope)** per un task futuro.
- Modifiche schema Room non necessarie al task
- Scope **TASK-006** (robustezza errori import generici) — task separato

---

## File potenzialmente coinvolti

- Produzione (solo se necessario a bugfix o hook minimi verificati): `DatabaseViewModel.kt` (**export full DB** e orchestrazione), `FullDbImportStreaming.kt`, eventualmente `ImportAnalyzer` / repository se emerge disallineamento apply; eventuali ottimizzazioni export **solo** se motivate da evidenza **§1bis / §6ter**.
- Test / fixture: nuova o estesa classe sotto `app/src/test/...`, eventuale helper in `app/src/test/.../testutil/` per workbook e snapshot.
- `docs/MASTER-PLAN.md` — solo tracking a chiusura task (fuori da questo aggiornamento di planning).

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Round-trip documentato e verificabile per le quattro entità/fogli nel perimetro (matrix **§6bis**) | B/S | — |
| 2 | `./gradlew test` e build rilevanti passano dopo le modifiche | B | — |
| 3 | Nessuna regressione non motivata su flussi export/import esistenti | S | — |
| 4 | **Export full DB** eseguito **con successo** (nessun crash/OOM/eccezione non gestita nel percorso) su **fixture JVM significativa / dataset realistico** definito in Execution (volumi maggiori della sola matrix minima round-trip — vedi **§6ter**); il file prodotto è utilizzabile (es. non vuoto, apribile come XLSX). **Se non va a buon fine:** nel log Execution classificare il fallimento (**FM‑*** in **§1bis**) e documentare l’**artefatto** (dimensione, apertura XLSX) | B/S | — |
| 5 | Se i test JVM **non** riproducono il profilo memoria/ANR del **device storico**: **smoke manuale o su emulatore** mirato sull’export full DB, con **esito documentato** (dispositivo, dimensioni DB approssimative, pass/fail, **FM‑*** e artefatto se fallisce) — stato **ESEGUITO** o **⚠️ NON ESEGUIBILE** con motivazione | M/E | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task attivato da backlog su richiesta utente | Priorità prodotto post-TASK-005 | 2026-03-28 |
| 2 | **Planning consolidato** nel file task (entrypoint, matrix §6bis/§6ter, export/FM‑*, dual-DB, criteri **#1–#5**); task **pronto per EXECUTION** | Chiusura fase di rifinitura planning; passaggio a codice solo dopo approvazione utente / handoff esecutore (**AGENTS.md**, **docs/CODEX-EXECUTION-PROTOCOL.md**) | 2026-03-28 |
| 3 | **Chiusura `DONE`** dopo review **APPROVED** e **conferma utente** | Nessun fix richiesto; criterio **#5** ⚠️ con motivazione accettata in review | 2026-03-28 |

---

## Pre-EXECUTION (tracking)

- **Superato:** task chiuso **`DONE`** (2026-03-28). Le istruzioni sotto si applicavano alla fase pre-codice; l’esecuzione e la review sono documentate nelle sezioni **Execution** / **Review**.

---

## Planning (Claude)

### 1. Entrypoint reali (codice attuale)

| Ruolo | Dove | Comportamento sintetico |
|-------|------|-------------------------|
| UI import file | `DatabaseScreen.kt` — `ActivityResultContracts.OpenDocument()` → `viewModel.startSmartImport(context, uri)` | Grant URI read; avvio smart import. |
| Routing workbook | `FullDbImportStreaming.kt` — `detectImportWorkbookRoute(context, uri)` | Se XLSX e esiste un foglio il cui nome normalizzato coincide con **Products** → `ImportWorkbookRoute.FULL_DATABASE`, altrimenti **single sheet**. |
| Export full DB | `DatabaseViewModel.exportFullDbToExcel(context, uri)` | Legge `repository`: prodotti con dettagli, suppliers, categories, `getAllPriceHistoryRows()`; scrive **XSSFWorkbook** con fogli `Products`, `Suppliers`, `Categories`, `PriceHistory` (costanti private nel ViewModel). |
| Analisi full import | `DatabaseViewModel.startFullDbImport(context, uri)` | Mutex import; history log; su IO chiama `analyzeFullDbImportStreaming(...)` (`FullDbImportStreaming.kt`): streaming fogli **Suppliers** / **Categories** (nomi), **Products** (analisi deferred relations via `ImportAnalyzer.analyzeStreamingDeferredRelations`), **PriceHistory** solo validazione header + flag `hasPriceHistorySheet`. Popola stato pending (incluso `pendingFullImportUri`, `hasPendingPriceHistorySheet`). |
| Apply storico prezzi full DB | `DatabaseViewModel.applyPendingPriceHistory` → `applyFullDbPriceHistoryStreaming(context, uri, repository)` | Se non c’è `pendingPriceHistory` da single-sheet e `hasPendingPriceHistorySheet` è true, rilegge il workbook e inserisce in batch tramite `repository.recordPriceHistoryByBarcodeBatch` (campi effettivi: barcode, type, timestamp, newPrice, source; **oldPrice** del foglio non è richiesto in header). |
| Apply prodotti / supplier / category | `DatabaseViewModel.importProducts(newProducts, updatedProducts, context)` → `resolveImportPayload` + `repository.applyImport` | Risolve nomi supplier/category pending e id temporanei negativi prima dell’apply (stesso entrypoint usato dalla UI dopo analisi). |

**Nota:** `DatabaseScreen` espone anche export singolo foglio prodotti (`exportToExcel`) — **fuori scope UI**; resta **riferimento tecnico** per il confronto con export full DB in **§1bis** e per regressioni incrociate su repository/POI.

### 1bis. Robustezza ed efficienza — export full DB

**Confronto percorsi (codice attuale):**

| Aspetto | Export **solo Products** (`exportToExcel` → `writeProductsToExcel`) | Export **full DB** (`exportFullDbToExcel`) |
|---------|---------------------------------------------------------------------|--------------------------------------------|
| Dati caricati | Lista **prodotti con dettagli** | **Quattro** fetch: `getAllProductsWithDetails()`, `getAllSuppliers()`, `getAllCategories()`, `getAllPriceHistoryRows()` — tutti **materializzati in memoria** prima della scrittura. |
| Workbook POI | `XSSFWorkbook` DOM | `XSSFWorkbook` DOM — **intero workbook in RAM** fino a `write` su `OutputStream`. |
| Complessità fogli | Un foglio Products | Quattro fogli; sul foglio **PriceHistory** il codice fa **`groupBy`** su tutte le righe storiche poi, per gruppo, **`sortedBy { timestamp }`** e costruzione progressiva di `oldPrice` — lavoro **globale in memoria** sul dataset completo prima di scrivere le righe. |

**Hotspot probabili (memoria / I/O / CPU):**

1. **`XSSFWorkbook`:** modello DOM XSSF; crescita heap con numero di celle (prodotti × colonne + suppliers + categories + storico).
2. **Materializzazione completa:** nessuno streaming dal DB verso il file; picco heap = somma delle quattro liste + strutture POI.
3. **Foglio PriceHistory:** `groupBy` + ordinamenti e loop annidati su **tutto** `priceRows` — costo aggiuntivo CPU e tenuta in memoria delle strutture intermedie.
4. **I/O:** copy su `ContentResolver.openOutputStream`; in caso di file grandi, tempo lungo percepito come «fallimento dopo secondi» se combinato con pressione memoria o ANR (da valutare su device).

**Vincoli sul codice attuale — `DatabaseViewModel.exportFullDbToExcel` (solo documentazione planning, nessuna modifica ora):**

- Usa **`XSSFWorkbook`** (modello DOM in memoria) dentro `withContext(Dispatchers.IO) { ... }`.
- **Materializza** in RAM, prima di costruire il workbook, quattro collezioni: `getAllProductsWithDetails()`, `getAllSuppliers()`, `getAllCategories()`, `getAllPriceHistoryRows()`.
- Per il foglio **PriceHistory**: **`groupBy`** su chiave `barcode|type` su tutto `priceRows`, poi per ogni gruppo **`sortedBy { timestamp }`** e scrittura righe con calcolo `oldPrice`.
- Il `try/catch` finale cattura **`Exception`** e imposta `UiState.Error`; **`OutOfMemoryError`** (e in generale **`Error`**) **non** è gestito in quel blocco — in caso di OOM il processo può **terminare** senza passare dall’handler di export (comportamento rilevante per il problema storico su device).
- La scrittura è `openOutputStream(uri)?.use { wb.write(it) }`: in caso di eccezione a metà, il destinatario del file può restare con **output parziale** a seconda di buffer/flush (da verificare caso per caso — vedi sotto).

**Failure mode esplicito — non basta «passa / non passa»:**

In **Execution** (test JVM, smoke device o riproduzione reale), se l’export full DB **non** completa con successo, il log **Execution** deve classificare il fallimento in **almeno una** categoria (o combinazione), non solo «failed»:

| Categoria | Descrizione | Note |
|-----------|-------------|------|
| **FM‑CRASH** | Terminazione processo / crash nativo | Tipico OOM non catturato, o altro `Error`. |
| **FM‑OOM** | `OutOfMemoryError` osservato (es. in logcat o test) | Distinto da crash generico se documentabile. |
| **FM‑UIERR** | Eccezione **gestita** → `UiState.Error` / messaggio export | Percorso `catch (Exception)`. |
| **FM‑ARTIFACT** | File XLSX **parziale**, **vuoto**, **corrotto** o non apribile da Excel/POI | Vedi paragrafo successivo. |
| **FM‑FREEZE** | **ANR**, UI bloccata, o attesa percepita come infinita (solo osservazione utente / timeout strumentato) | Allineato al «dopo alcuni secondi» su device reale. |

Motivazione: il problema storico era **crash o fallimento dopo secondi** su device — la diagnosi richiede **tipologia**, non solo esito booleano.

**Verifica artefatto in caso di fallimento:**

- Controllare se sul **URI / path di output** resta un file: **dimensione 0**, **ZIP/XLSX non valido** (POI/Excel fallisce all’apertura), o file **apribile ma incompleto** (fogli mancanti o righe troncate).
- Documentare nel log **Execution**: percorso, dimensione, esito apertura (tool usato), così si distingue **solo crash** da **corruzione / residuo sporco** utile per l’utente o per import successivi.

**Obiettivo TASK-007 (export):** rendere **misurabile e verificabile** il completamento su dati non banali **e**, in caso contrario, lasciare **traccia classificata** (tabella sopra + artefatto). Aprire **§1ter / §1quater** in base all’evidenza — **nessuna implementazione in questa fase di planning**.

### 1ter. Nota tecnica — ramo decisionale (solo se Execution conferma collo di bottiglia)

Se l’evidenza indica che il problema è l’**implementazione attuale dell’export** (non solo dati errati), valutare in **task successivo o fase FIX** (non ora):

- Scrittura workbook **più memory-friendly** (es. API streaming SXSSF dove applicabile, o chunking documentato).
- **Ridurre la materializzazione completa** (paginazione / cursor Room verso writer, se compatibile con obiettivi prodotto).
- **PriceHistory:** calcolo più efficiente (es. ordinamento/stream per chiave senza tenere tutto in mappe intermedie giganti), sempre con attenzione a equivalenza export.

**TASK-007 in planning:** solo documentare questo ramo; **non implementare** finché non c’è conferma da test/smoke.

### 1quater. Diagnostica per fasi (pianificazione; strumento in Execution / FIX)

**Non implementare nel solo aggiornamento di planning.** Se in **Execution** (o fase **FIX**) serve **localizzare il collo di bottiglia reale** su export full DB, è **utile** introdurre temporaneamente misure **per fase** (timestamp, durata, o log strutturato), ad esempio:

1. **Fetch repository** — dopo le quattro chiamate `getAll*` (o tra una e l’altra, se si vuole granularità).
2. **Costruzione foglio Products** — inizio/fine del blocco righe.
3. **Costruzione fogli Suppliers / Categories** — idem per ciascun foglio o aggregati.
4. **Costruzione foglio PriceHistory** — prima/dopo `groupBy` / loop ordinamento / scrittura celle.
5. **Scrittura finale** — subito prima e dopo `wb.write(...)` sull’`OutputStream`.

Obiettivo: capire se il tempo/memoria esplode in **fetch**, in **DOM POI**, in **PriceHistory**, o in **flush su disco**. Rimuovere o ridurre il logging rumoroso dopo la diagnosi. Coerente con criteri **#4–#5** e con **FM‑FREEZE** / **FM‑CRASH**.

### 2. Scenario round-trip principale (obbligatorio in verifica)

1. **DB sorgente** (popolato): dati noti (prodotti con barcode univoci, supplier/category, eventuale price history) su **`DefaultInventoryRepository` + `Room.inMemoryDatabaseBuilder` dedicato** (vedi **§6**).
2. **Export**: workbook XLSX bit‑equivalente alla logica di `DatabaseViewModel.exportFullDbToExcel` — **preferenza:** helper di test / stesso blocco POI lato test (due `InventoryRepository` distinti: solo il sorgente legge i dati). Per il dual-DB **non** fare affidamento sul wiring **tipico** app (un ViewModel + `AppDatabase.getDatabase` singleton); orchestrare due repository senza ViewModel come perno resta la scelta più lineare (**§6**).
3. **DB target**: **seconda istanza** `Room.inMemoryDatabaseBuilder` + **nuovo** `DefaultInventoryRepository`; **mai** riusare la stessa istanza `AppDatabase` della sorgente.
4. **Import**: sequenza **repository-level** come in **§6** — `analyzeFullDbImportStreaming` + risoluzione payload equivalente a `resolveImportPayload` + `applyImport` + eventualmente `applyFullDbPriceHistoryStreaming` (stesso ordine del ViewModel). Il ViewModel (`startFullDbImport` / `importProducts`) è **opzionale** e solo per test mirati.
5. **Confronto**: snapshot normalizzati (**§3 / §3bis**); per PriceHistory con foglio presente applicare partizione **§5** dove richiesto dalla **matrix §6bis**.

**Non scenario principale:** re-import sullo stesso database (aggiorna/incrementa in modo diverso e confonde equivalenza semantica). Eventuale smoke manuale o test secondario solo se esplicitamente motivato.

### 3. Vincoli di equivalenza per area

| Area | Confronto | Escludere / note |
|------|-----------|------------------|
| **Products** | Dati di business: barcode (chiave), item number, nomi, prezzi correnti, stock, **associazione logica** supplier/category risolta (id target diversi da sorgente accettati se coerenti con nomi). | Non confrontare `id` Room. Applicare le regole di normalizzazione in **§3bis**. |
| **Suppliers** / **Categories** | Insieme di **nomi** dopo normalizzazione (es. `trim().lowercase()` coerente con `resolveImportPayload` / mappe per nome). | Non confrontare colonna `id` esportata né id Room; l’import full usa i nomi dalle righe dati. |
| **PriceHistory** | Record semantici importabili dal foglio; confronto come **multiset** con chiave stabile (**§3bis**) e vincoli sul doppio canale (**§5**). | **Ignorare `oldPrice`** nel workbook (derivato in export). Attenzione: sul target esistono anche righe sintetiche da `applyImport` — vedi **§5**. |

### 3bis. Regole di normalizzazione snapshot (operative)

Da implementare come helper di test documentati (stesso file della classe round-trip o `testutil`).

| Tema | Regola |
|------|--------|
| **Ordinamento stabile** | Dopo normalizzazione, ordinare le liste per chiave primaria del confronto: **Products** per `barcode` lessicografico; **Suppliers/Categories** per nome normalizzato; **PriceHistory** per tupla `(barcode_norm, type, timestamp, source_norm, price)` così l’output è deterministico negli assert. |
| **Stringhe: null vs vuoto** | `trim()`; stringa vuota dopo trim **equivalente** a `null` per campi opzionali testuali (nomi secondari, item number, source assente → trattare come `null` o stringa vuota in modo **unico** e documentato nella classe di test). |
| **Numeri (prezzi, stock)** | Confronto `Double` con tolleranza assoluta **ε = 0.0005** (allineata a `insertIfChanged` in `ProductPriceDao`) salvo diversa motivazione; per «uguali a zero» usare la stessa ε. Evitare confronto bitwise. **Attenzione null → 0.0:** l'export scrive `p.purchasePrice ?: 0.0` (idem per `retailPrice`, `stockQuantity`, `prevPurchase`, `prevRetail`); l'import potrebbe restituire `0.0` dove il sorgente aveva `null`. La normalizzazione deve trattare `null` ≡ `0.0` (entro ε) per questi campi, oppure lo snapshot deve convertire `null → 0.0` prima del confronto. Comportamento **pre-esistente** (identico in export single-sheet), non da correggere in TASK-007 ma da gestire nei test. |
| **Arrotondamento display** | Se si serializzano valori per chiave (es. stringa composta), usare **stesso numero di decimali** documentato (es. 4) o solo confronto ε senza string rounding — scegliere una strategia e non mescolarne due nello stesso assert. |
| **PriceHistory `source`: trasformazione `null` → `"IMPORT_SHEET"` nel round-trip** | **Meccanismo reale:** export scrive `e.source ?: ""` (stringa vuota per `null`); l'import in `applyFullDbPriceHistoryStreaming` legge `source` come `row.trim().orEmpty().ifBlank { null }` → `null`; ma in `flushBatch` raggruppa per `it.source ?: "IMPORT_SHEET"` → chiama `recordPriceHistoryByBarcodeBatch(rows, source = "IMPORT_SHEET")`. Risultato: record con **`source = null`** nel DB sorgente diventa **`source = "IMPORT_SHEET"`** nel DB target. La normalizzazione deve **mappare entrambi allo stesso valore** (es. `null` e `"IMPORT_SHEET"` → stesso literal). Record con `source` valorizzato e non vuoto (es. `"MANUAL"`, `"BACKFILL"`) vengono preservati fedelmente. |
| **PriceHistory come multiset** | Chiave stabile consigliata: `(barcode_trim, type_uppercase, timestamp_canonical, source_norm, price)` dove `source_norm` normalizza `source` così: `null`, `""`, `"IMPORT_SHEET"` → tutti mappati a **stesso literal stabile** (es. `"IMPORT_SHEET"` o `null` — scegliere uno e documentare); gli altri source restano come `source.trim()`. Due record sono «stessi» se tutte le componenti matchano e il prezzo è entro ε. |
| **Confronto insiemistico** | Preferire: costruire `List` normalizzate → ordinare come sopra → `assertEquals` su liste, oppure `Multiset`/frequency map esplicita con stessa chiave. |

### 4. PriceHistory opzionale nel workbook

- **Con foglio `PriceHistory`:** export standard lo include; `analyzeFullDbImportStreaming` imposta `hasPriceHistorySheet = true` se il foglio esiste e l’header è valido (`productBarcode`/`barcode`, `timestamp`, `type`, `newPrice`; `source` opzionale nel parsing). Coperto obbligatoriamente da **RT‑FULL** nella matrix **§6bis**.
- **Senza foglio:** workbook ancora full DB se presente foglio **Products**; `hasPriceHistorySheet = false` → nessuna chiamata a `applyFullDbPriceHistoryStreaming`. Coperto obbligatoriamente da **RT‑NOPH** nella matrix **§6bis**.

### 5. Rischio round-trip PriceHistory: doppio canale (Products + foglio)

**Meccanismo attuale (codice):**

1. Il foglio **Products** in export include colonne «prezzi precedenti» (`prevPurchase` / `prevRetail` nel `ProductWithDetails`); in import le righe mappate su `Product` possono portare **`oldPurchasePrice` / `oldRetailPrice`** oltre ai prezzi correnti.
2. **`DefaultInventoryRepository.applyImport`** (non solo persistenza prodotti): per ogni new/updated product chiama `recordBothStates`, che inserisce in `product_prices` eventi sintetici con sorgenti **`IMPORT_PREV`** (timestamp `now - 1s`) e **`IMPORT`** (timestamp `now`) per PURCHASE/RETAIL quando i campi sono valorizzati.
3. Se il workbook ha anche il foglio **`PriceHistory`**, dopo `applyImport` viene eseguito **`applyFullDbPriceHistoryStreaming`**, che reimporta la **serie storica completa** dal file (timestamp e `source` come nel foglio, o default raggruppamento per source).

**Nota tecnica — `insertAll(IGNORE)` vs `insertIfChanged`:**

- `applyImport` → `recordBothStates` usa **`priceDao.insertIfChanged`** (controlla l'ultimo prezzo per tipo; inserisce solo se diverso entro ε).
- `applyFullDbPriceHistoryStreaming` → `recordPriceHistoryByBarcodeBatch` usa **`priceDao.insertAll`** con **`OnConflictStrategy.IGNORE`** — nessun check di dedup logico, ma la **unique index** `(productId, type, effectiveAt)` impedisce inserimenti con stessa chiave; i duplicati sono **silenziosamente scartati**.
- Ordine di esecuzione: `applyImport` (prima) → `applyFullDbPriceHistoryStreaming` (dopo). Se un record sintetico `IMPORT`/`IMPORT_PREV` ha lo stesso `(productId, type, effectiveAt)` di un record dal foglio PriceHistory, il record dal foglio viene scartato per unique index. Nella pratica il rischio è molto basso (timestamp `now`/`now-1s` vs timestamp storici), ma **i test devono tenerne conto** se usano timestamp coincidenti nella fixture.

**Perché non è un round-trip «bit-identico» sullo storico:**

- Sul DB **target** lo storico sarà, in generale, **unione** di: (A) eventi ricostruiti dal foglio PriceHistory, (B) eventi aggiunti da `applyImport` con timestamp «vicini all’istante di import» e source `IMPORT` / `IMPORT_PREV`.
- `insertIfChanged` evita duplicati solo se l’**ultimo** prezzo per tipo è già uguale entro ε — **non** elimina doppioni tra eventi a timestamp diversi (tipico: stesso prezzo nel foglio a data storica + evento `IMPORT` a `nowTs`).

**Come verificare o delimitare (obbligatorio nel design del test):**

| Approccio | Uso |
|-----------|-----|
| **A — Confronto per partizione** | Snapshot target partizionato per `source`: assert sul multiset delle righe con `source` **non** in `{IMPORT, IMPORT_PREV}` (o equivalente) **uguale** al multiset atteso ricavato dall’export sorgente (normalizzato §3bis); assert **separato** sul conteggio/presenza righe `IMPORT`/`IMPORT_PREV` coerente con la fixture (es. se old* valorizzati → attesi fino a 2 righe IMPORT_PREV + 2 IMPORT per prodotto importato, salvo `insertIfChanged`). |
| **B — Test di regressione esplicito** | Fixture minima: **un** barcode, prezzi correnti + **old*** valorizzati nel foglio Products, e **almeno due** righe reali nel foglio PriceHistory (timestamp distinti). Dopo round-trip: (1) nessuna perdita delle righe «da foglio» rispetto al multiset atteso; (2) documentare che le righe sintetiche `IMPORT*` sono **attese** e non sono errori di round-trip se il criterio di accettazione è la partizione A. |
| **C — Evitare ambiguità nel criterio task** | Non dichiarare «uguaglianza totale `getAllPriceHistoryRows` sorgente vs target» senza filtri: sarebbe falsato dagli eventi sintetici. Il criterio di accettazione va aggiornato in Execution/review se si adotta formalmente la partizione A. |

**Suite minima vincolante:** il caso **old* + foglio PriceHistory** è obbligatorio come test dedicato **RT‑PART** nella matrix **§6bis** (non più solo raccomandazione generica).

### 6. Strategia di test preferita e ruolo `AppDatabase` / `DatabaseViewModel`

**Wiring reale vs test (allineamento al codice):**

- `DatabaseViewModel` riceve **`InventoryRepository` nel costruttore** (e viene creato tramite factory / `ViewModelProvider` come da app). **In produzione**, `NavGraph.kt` costruisce il repository a partire da **`AppDatabase.getDatabase(app)`**: il DB è **singleton per processo**, quindi il ViewModel **in app** è di fatto legato a **un’unica** istanza Room.
- **Nei test**, lo stesso tipo è **iniettabile** con repository **arbitrari** (mock, `DefaultInventoryRepository` su `Room.inMemoryDatabaseBuilder`, ecc.). In linea di principio si potrebbero usare **due** ViewModel con **due** repository su **due** DB distinti; in pratica il round-trip completo passa da stato interno (`UiState`, pending import, coroutine) e rende la doppia istanza ViewModel **più fragile e laboriosa** della catena repository pura.

**Decisione di planning (invariata):**

- Per la suite **dual-DB** (matrix **§6bis**) la strategia **preferita** resta **repository-level** + helper, con **due** istanze Room separate:
  - `dbSource = Room.inMemoryDatabaseBuilder(...).build()` → `repoSource = DefaultInventoryRepository(dbSource)`
  - `dbTarget = Room.inMemoryDatabaseBuilder(...).build()` → `repoTarget = DefaultInventoryRepository(dbTarget)`
- **Flusso:** popola `repoSource` → export su `File` temp (POI, stessa semantica di `exportFullDbToExcel`) → `Uri.fromFile` → su **dispatcher IO** su `repoTarget`: `analyzeFullDbImportStreaming(app, uri, currentProducts, repoTarget)` + risoluzione nomi/id come `resolveImportPayload` + `repoTarget.applyImport` + `applyFullDbPriceHistoryStreaming` se `hasPriceHistorySheet`.

**Ruolo del `DatabaseViewModel` nei test:**

- **Secondario / opzionale:** test **mirati** (es. `exportFullDbToExcel` con **un** repository in-memory o mock, flusso `UiState`, URI reali) o riuso del pattern in `DatabaseViewModelTest`.
- **Non** la via **principale** per RT‑FULL…RT‑PART: non per limitazione del tipo, ma per **semplicità e stabilità** della suite rispetto a doppio stato ViewModel.
- Eventuali `internal` / hook minimi in produzione vanno **motivati nel log Execution** e restano eccezione.

**Altri punti:**

- **Priorità:** JVM ripetibili (Robolectric + in-memory Room, come `DefaultInventoryRepositoryTest`).
- **Workbook:** file temporaneo + `Uri.fromFile`.
- **Locale:** requisito soddisfatto dalla riga **RT‑LOCALE** della matrix **§6bis** (non solo testo generico).
- **Manuale:** opzionale per round-trip; per **export full DB** vedi **§6ter** e criterio di accettazione **#5**.

### 6bis. Test matrix minima obbligatoria (Execution)

La suite round-trip non è considerata completa senza **tutte** le righe seguenti (nomi metodo sono **convenzione**; in Execution si possono adattare purché 1:1 con gli obiettivi). Ogni test deve chiudere `use`/close sui DB o usare `@After` equivalente.

| ID | Obiettivo | Fixture / condizioni | Assert minimo |
|----|-----------|----------------------|---------------|
| **RT‑FULL** | Round-trip **completo** con **quattro fogli** (`Products`, `Suppliers`, `Categories`, `PriceHistory`) | Sorgente con almeno un prodotto, supplier/category collegati, **e** righe price history esportabili; workbook generato dall’export full; target vuoto. | Products + Suppliers + Categories equivalenti (**§3 / §3bis**). PriceHistory: **partizione A** (**§5**) — multiset `source ∉ {IMPORT, IMPORT_PREV}` uguale al multiset atteso dal foglio/sorgente; presenza eventuale righe `IMPORT*` documentata/asserita separatamente se la fixture ha old* valorizzati. |
| **RT‑NOPH** | Round-trip **senza foglio `PriceHistory`** | Stesso perimetro RT‑FULL ma workbook **senza** foglio PriceHistory (rimosso dopo export o generato senza quella sheet); foglio `Products` presente per routing full DB. | Products + Suppliers + Categories OK; **nessun** inserimento da `applyFullDbPriceHistoryStreaming`; storico prezzi target coerente solo con ciò che `applyImport` ha eventualmente scritto (`IMPORT*` se old*/prezzi presenti), da assert espliciti. |
| **RT‑LOCALE** | Header **Products localizzati** reimportabili | Prima dell’export, `Locale` non inglese o fissato (es. `forLanguageTag("it")` / `@Config(qualifiers="it")`); export che usa `context.getString` per header; target vuoto; workbook con **4 fogli** o almeno Products+altri fogli richiesti dal caso. **Nota:** i fogli **Suppliers** e **Categories** usano header hardcoded inglesi (`"id"`, `"name"`), non localizzati; il rischio locale è concentrato sul foglio **Products** (header da `context.getString`). Il foglio **PriceHistory** usa header hardcoded inglesi (`"productBarcode"`, `"timestamp"`, …). | Round-trip analisi+apply riuscito; **Products** (e resto perimetro scelto) allineati a **§3bis**; nessun fallimento in `analyzeFullDbImportStreaming` per header. |
| **RT‑PART** | **oldPurchasePrice / oldRetailPrice** nel foglio Products **e** foglio **PriceHistory** presente | Almeno un barcode con **old*** e prezzi correnti valorizzati; foglio PriceHistory con **≥ 2** righe dati con **timestamp distinti** (serie «reale»). | (1) Multiset righe storiche **non sintetiche** sul target = atteso normalizzato. (2) Assert **separato** su righe con `source` **`IMPORT`** / **`IMPORT_PREV`**: presenza e cardinalità coerenti con fixture (**§5**), senza confondere con fallimento round-trip. |

**Regola:** se un test fallisce, non ridurre la matrix; si corregge codice o si aggiorna il criterio con review motivata.

### 6ter. Verifica dedicata — path export full DB (robustezza)

Complementare alla **matrix round-trip §6bis** (che può usare dataset piccoli). Obiettivo: intercettare **crash / OOM / eccezioni** sul percorso export, non solo mismatch semantici.

| ID | Obiettivo | Note operative |
|----|-----------|----------------|
| **EX‑SIGNIFICANT** | Eseguire l’export full DB (stessa semantica di `exportFullDbToExcel`: helper di test o `DatabaseViewModel` + **un** `DefaultInventoryRepository` in-memory) su una **fixture significativa**: ordini di grandezza realistici (es. **migliaia** di prodotti e/o **molte** righe `product_prices`), definiti numericamente nel log Execution. | Assert: completamento **senza** `OutOfMemoryError` / crash JVM, `UiState` success se via ViewModel, file XLSX **non vuoto** e apribile. Opzionale: timeout esplicito documentato se si teme hang. **Se fallisce:** nel log Execution classificare con **§1bis** (FM‑CRASH / FM‑OOM / FM‑UIERR / FM‑ARTIFACT / FM‑FREEZE) e documentare **artefatto** (dimensione, apertura XLSX). |
| **EX‑BASELINE** (opzionale) | Stesso export su dataset **minimo** (sanity) per isolare regressioni logiche da problemi di scala. | Utile se EX‑SIGNIFICANT fallisce: capire se è scala o bug funzionale. |

**Limite dei test JVM:** heap e comportamento **non** identici al device Android; **non** riproducono sempre **FM‑CRASH** / **FM‑FREEZE** come su device. Se **EX‑SIGNIFICANT** passa in CI ma il problema storico persiste, il criterio **#5** richiede **smoke manuale o emulatore** con stessa **tassonomia di fallimento** e nota sull’artefatto dove applicabile.

**Verifica export = round-trip (logico) + export scala (JVM) + eventuale smoke runtime (M/E)**; in caso di insuccesso, **sempre** tipologia FM + stato file (**§1bis**).

### 7. File candidati per EXECUTION (scope minimo)

| File | Motivo |
|------|--------|
| `app/src/test/java/.../FullDbExportImportRoundTripTest.kt` (nome indicativo) | Implementa **matrix §6bis** + verifica **EX‑*** (**§6ter**), **dual in-memory DB** + helper export/import (**§6**). |
| `app/src/test/java/.../testutil/...` (consigliato) | Export full DB (POI), normalizzazione snapshot (**§3bis**), eventuale replica della logica `resolveImportPayload` per test. |
| `app/src/test/java/.../data/DefaultInventoryRepositoryTest.kt` | Pattern `Room.inMemoryDatabaseBuilder`; estensione solo se condiviso senza appesantire. |
| `app/src/test/java/.../viewmodel/DatabaseViewModelTest.kt` | Opzionale: **solo** test mirato export ViewModel / URI; **non** sostituisce RT‑FULL…RT‑PART. |
| `DatabaseViewModel.kt` | Solo bugfix o hook minimi motivati (**§6**); non per aggirare il dual-DB. |
| `FullDbImportStreaming.kt` | Solo per correzioni se il round-trip fallisce per difetto reale. |
| `DefaultInventoryRepository.kt` / DAO | Solo se l’apply o le query snapshot richiedono fix (fuori scope ideale). |

### 8. Rischi di regressione concreti

**A — Correttezza / semantica (round-trip, mismatch dati):**

- **Header Products / canonical:** mitigato da **RT‑LOCALE** (**§6bis**); resta il rischio se si aggiungono colonne o si cambia `canonicalExcelHeaderKey` senza aggiornare export/import.
- **Prezzi / numeri:** formattazione Excel (virgola, celle numeriche vs stringhe) vs parsing in streaming.
- **Supplier/Category:** ordine di insert e duplicati nomi; mismatch tra id nel foglio export e risoluzione per nome in import.
- **PriceHistory:** disallineamento tra tipo normalizzato export (`purchase`/`retail`) e logica import (`pur*` → PURCHASE); timezone/stringhe timestamp; **doppio canale** (**§5**) se il confronto snapshot è definito male.
- **Routing:** file senza foglio `Products` non entra in full import — test devono usare nomi foglio coerenti con `normalizeExcelHeader("Products")`.

**B — Robustezza runtime export full DB (distinto da A e da semplice mismatch semantico):**

- **Crash / OOM / kill process** durante `exportFullDbToExcel` su dataset grandi (**§1bis**): DOM `XSSFWorkbook` + quattro liste complete + costo **PriceHistory** (`groupBy` / `sortedBy` globali); **`Error` / OOM fuori da `catch (Exception)`**.
- **Fallimento percepito «dopo alcuni secondi»** (**FM‑FREEZE**), **file parziale/corrotto** (**FM‑ARTIFACT**), o **errore UI** (**FM‑UIERR**) — storicamente rilevante su **device** mentre l’export **solo Products** reggeva meglio il carico.
- Mitigazione in verifica: **EX‑SIGNIFICANT** + criteri **#4 / #5** + documentazione **FM‑*** e artefatto; diagnosi mirata **§1quater** se si entra in FIX; rimedi strutturali **§1ter** dopo evidenza.

**Nota:** i rischi **A** e **B** sono **ortogonali**: round-trip può essere corretto su piccolo dataset mentre l’export full fallisce in produzione su scala reale — il task deve coprire **entrambi**.

### 9. Check finali concreti (post-EXECUTION)

- `./gradlew test` — **matrix §6bis** + verifica **EX‑SIGNIFICANT** (**§6ter**); se toccato codice produzione TASK-004-related, baseline documentata in `AGENTS.md` (es. `DefaultInventoryRepositoryTest`, `DatabaseViewModelTest` se modificati).
- `./gradlew assembleDebug` — compilazione senza errori.
- `./gradlew lint` — nessun warning nuovo non motivato (se eseguito).
- Verifica criteri di accettazione **#1–#5** con evidenza (output Gradle, dimensioni fixture EX‑*, eventuale report smoke **#5**). Se export fallisce: **classificazione FM** + **esito artefatto XLSX** (**§1bis**) nel log Execution.

### 10. Follow-up (fuori scope TASK-007) — UX export modulare e routing import

TASK-007 in **questo perimetro** copre **round-trip dati**, **robustezza/efficienza export full DB** (verifica **§6ter**, criteri **#4–#5**) e test JVM; **non** include redesign schermate. La seguente direzione UX è **backlog / task futuro** (da creare quando prioritizzato).

**UX export consigliata (proposta prodotto):**

- Al tap su **Export**, evitare il **mini menu a due voci** attuale; preferire **bottom sheet** o **dialog a tutta larghezza** (large dialog).
- **Preset rapidi:** «Solo prodotti» | «Database completo» | «Personalizzato».
- In **Personalizzato:** selezione esplicita delle sezioni **Products / Suppliers / Categories / PriceHistory** (checkbox o lista).
- **Una sola CTA primaria** «Esporta» (evitare più pulsanti primari concorrenti).
- **Riepilogo dinamico** delle sezioni selezionate prima della conferma.

**Vincolo tecnico oggi (da documentare nel task UX/import futuro):**

- `detectImportWorkbookRoute` classifica **full database** solo se trova un foglio il cui nome normalizzato coincide con **Products** (`FullDbImportStreaming.kt`). Un export **parziale** che omette il foglio Products **non** verrà trattato come full DB dallo smart import.
- **Miglioramento futuro suggerito:** introdurre un foglio **Manifest / ExportInfo** (nome da definire) con **versione schema**, **elenco sezioni incluse** e magari hash/conteggio righe; il routing potrebbe usare questo foglio **oltre** al nome `Products`, così export modulari restano riapribili con regole esplicite senza dipendere solo dalla presenza del foglio Products.

---

**Planning:** chiuso con il task (**DONE** 2026-03-28). Il contenuto resta riferimento storico per export/import full DB e test round-trip.

---

## Execution

### Esecuzione — 2026-03-28

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — aggiunti alias per gli header short `oldPurchasePrice` / `oldRetailPrice` effettivamente esportati dal full DB (`Purchase/Retail (Old)` + varianti i18n), così il round-trip reimporta i campi `old*` correttamente.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — `exportFullDbToExcel` ora preserva `CancellationException` e converte `OutOfMemoryError` in `UiState.Error` invece di lasciare il path non gestito.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — nuova suite dual-DB in-memory con matrix `RT-FULL`, `RT-NOPH`, `RT-LOCALE`, `RT-PART` e verifica `EX-SIGNIFICANT` sul path export full DB.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — aggiunto test mirato sul comportamento OOM di `exportFullDbToExcel`.

**Azioni eseguite:**
1. Implementata una suite JVM dedicata al round-trip full DB usando due `Room.inMemoryDatabaseBuilder(...)` isolati, export reale via `DatabaseViewModel.exportFullDbToExcel(...)`, import repository-level (`analyzeFullDbImportStreaming` + risoluzione payload + `applyImport` + `applyFullDbPriceHistoryStreaming`) e snapshot normalizzati coerenti con la planning matrix §6bis.
2. Riprodotto un bug reale del path export/import full DB: i casi `RT-FULL`, `RT-NOPH`, `RT-LOCALE` e `RT-PART` fallivano perché gli header esportati con `product_purchase_price_old_short` / `product_retail_price_old_short` non venivano canonicalizzati in import; di conseguenza i campi `old*` andavano persi nel round-trip.
3. Applicato il fix minimo richiesto dal task in `ExcelUtils.kt`, limitato agli alias degli header realmente prodotti dall’export full DB nelle lingue già presenti in app.
4. Rafforzato il path produzione `exportFullDbToExcel(...)` gestendo `OutOfMemoryError` come errore UI verificabile e aggiungendo un test dedicato.
5. Eseguita la verifica `EX-SIGNIFICANT` prevista dal planning su fixture JVM significativa: `1200` products, `30` suppliers, `20` categories, `4800` righe `product_prices`; export completato con successo e workbook XLSX apribile con i 4 fogli attesi.
6. Verificata la disponibilità runtime per il criterio manuale/emulatore: `adb devices` rileva `emulator-5554` (`sdk_gphone64_arm64`, Android 15 / API 35), ma il flow export full DB richiede interazione SAF/destinazione file e un dataset realistico sul device; nel repo non esiste un harness instrumented per automatizzarlo entro il perimetro di TASK-007.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug --max-workers=1` → `BUILD SUCCESSFUL` in ~4s |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint --max-workers=1` → `BUILD SUCCESSFUL` in ~23s; report: `app/build/reports/lint-results-debug.html` |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning/lint finding sui file modificati; il report lint resta con `68 warnings` preesistenti (es. `UnusedResources`, versioning/deprecation Gradle, `LocaleUtils.kt`) fuori scope del task |
| Coerenza con planning    | ✅ ESEGUITO | Coperti integralmente `RT-FULL`, `RT-NOPH`, `RT-LOCALE`, `RT-PART`, `EX-SIGNIFICANT`; fix produzione limitati a bug reale round-trip + robustezza OOM export |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti i criteri verificati e documentati sotto; `#5` registrato come `⚠️ NON ESEGUIBILE` con motivazione concreta |

**Criteri di accettazione (verifica puntuale):**
| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Round-trip documentato e verificabile per le quattro entità/fogli nel perimetro (matrix **§6bis**) | `./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest` | ✅ ESEGUITO | Suite verde con `RT-FULL`, `RT-NOPH`, `RT-LOCALE`, `RT-PART`; i fallimenti iniziali hanno evidenziato il bug reale sugli header `old*`, corretto in `ExcelUtils.kt` |
| 2 | `./gradlew test` e build rilevanti passano dopo le modifiche | `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' ./gradlew test --max-workers=1`; `./gradlew assembleDebug --max-workers=1` | ✅ ESEGUITO | `BUILD SUCCESSFUL` per test suite completa e build debug; su questa macchina la suite MockK richiede `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true'` e `--max-workers=1` con JBR di Android Studio |
| 3 | Nessuna regressione non motivata su flussi export/import esistenti | Suite completa + baseline TASK-004 | ✅ ESEGUITO | `./gradlew test --max-workers=1` verde dopo il fix; nessuna API/navigation cambiata, nessun refactor fuori scope |
| 4 | Export full DB eseguito con successo su fixture JVM significativa / dataset realistico | `EX-SIGNIFICANT` in `FullDbExportImportRoundTripTest` | ✅ ESEGUITO | Fixture: `1200` products / `30` suppliers / `20` categories / `4800` history rows; workbook non vuoto, apribile con `XSSFWorkbook`, 4 fogli presenti, nessuna classificazione `FM-*` necessaria |
| 5 | Se i test JVM non riproducono il profilo memoria/ANR del device storico: smoke manuale o su emulatore mirato sull’export full DB, con esito documentato | Verifica disponibilità emulator/device + valutazione automatizzabilità del flow | ⚠️ NON ESEGUIBILE | Emulator connesso: `emulator-5554` (`sdk_gphone64_arm64`, Android 15 / API 35, AVD `Medium_Phone_API_35`), ma il path export full DB richiede interazione SAF/destinazione file e dataset realistico on-device; nessun harness instrumented/manual scripted nel repo per completare lo smoke senza uscire dal perimetro del task |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest`; `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' ./gradlew test --max-workers=1` (include `DefaultInventoryRepositoryTest`, `DatabaseViewModelTest`, `ExcelViewModelTest`, `ImportAnalyzerTest`, `ExcelUtilsTest`)
- Test aggiunti/aggiornati: nuovo `FullDbExportImportRoundTripTest`; aggiornato `DatabaseViewModelTest` con caso OOM su `exportFullDbToExcel`
- Limiti residui: manca ancora uno smoke manuale/emulatore completo del file picker SAF + export full DB con dataset realistico sul device, documentato come criterio `#5` `⚠️ NON ESEGUIBILE`

**Incertezze:**
- Nessuna sul path JVM verificato; l’unico limite residuo è il confronto col profilo storico device/SAF del criterio `#5`, non coperto automaticamente dal repo.

**Handoff notes:**
- Review focus: alias header `old*` in `ExcelUtils.kt` e gestione `OutOfMemoryError` in `DatabaseViewModel.exportFullDbToExcel(...)`.
- Per rieseguire la suite completa su questa macchina: `export JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`, `export GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true'`, poi `./gradlew test --max-workers=1`.
- Se si vuole chiudere anche il criterio `#5`, eseguire smoke manuale/emulatore dell’export full DB con DB realistico e destinazione SAF, documentando device, dimensioni dataset, esito e eventuale classificazione `FM-*`.

---

## Review

### Review — 2026-03-28

**Revisore:** Claude (planner)

**Verifica indipendente:** `./gradlew test --max-workers=1` → **BUILD SUCCESSFUL** (81 test, 6 suite, 0 failures); `./gradlew assembleDebug` → BUILD SUCCESSFUL.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|---------|-------|------|
| 1 | Round-trip documentato e verificabile per le quattro entità/fogli nel perimetro (matrix §6bis) | ✅ | RT-FULL, RT-NOPH, RT-LOCALE, RT-PART tutti implementati e verdi in `FullDbExportImportRoundTripTest`; dual-DB in-memory + import repository-level + snapshot normalizzati coerenti con §3/§3bis. |
| 2 | `./gradlew test` e build rilevanti passano dopo le modifiche | ✅ | Verificato in review: 81 test verdi, build debug OK. |
| 3 | Nessuna regressione non motivata su flussi export/import esistenti | ✅ | Nessun test preesistente rotto; modifiche produzione limitate a: alias header old\* in `ExcelUtils.kt` (bugfix) + catch OOM/CancellationException in `exportFullDbToExcel` (hardening). Nessuna modifica a DAO, repository, navigation, schema, UI. |
| 4 | Export full DB eseguito con successo su fixture JVM significativa | ✅ | EX-SIGNIFICANT: 1200 products, 30 suppliers, 20 categories, 4800 righe PH; workbook non vuoto, apribile, 4 fogli con row count atteso. |
| 5 | Smoke manuale o su emulatore mirato sull'export full DB | ⚠️ NON ESEGUIBILE | Motivazione accettabile: il flow richiede interazione SAF/destinazione file + dataset realistico on-device; nessun harness instrumented nel repo. Emulatore documentato come disponibile. Raccomandazione: coprire in task UX futuro o harness dedicato. |

**Verifica di dettaglio (codice produzione):**

1. **`ExcelUtils.kt` — alias old price headers:** Corretto. Le stringhe aggiunte (`"Purchase (Old)"`, `"Acquisto (Vecchio)"`, `"Compra (Antiguo)"`, `"进价（旧）"` + equivalenti retail) corrispondono esattamente ai valori di `product_purchase_price_old_short` / `product_retail_price_old_short` nelle 4 lingue (en, it, es, zh). `normalizeExcelHeader` gestisce correttamente i caratteri full-width cinesi (es. `（）` → stripped, `"进价旧"` → match). Il fix era necessario: senza gli alias, l'import non canonicalizzava le colonne old\* e i campi `oldPurchasePrice`/`oldRetailPrice` andavano persi nel round-trip.

2. **`DatabaseViewModel.kt` — CancellationException + OOM in `exportFullDbToExcel`:** Corretto. Il pattern `catch (CancellationException) → throw` / `catch (OutOfMemoryError) → UiState.Error` / `catch (Exception)` è identico a quello già usato in `startFullDbImport` e `startSmartImport`. Risolve il gap documentato in planning §1bis dove `OutOfMemoryError` (che è `Error`, non `Exception`) non era catturato dal `catch (Exception)` esistente.

**Verifica di dettaglio (test):**

3. **`FullDbExportImportRoundTripTest.kt`:** Suite ben strutturata. Copertura 1:1 con la matrix §6bis. `resolveImportPayloadForTest` replica fedelmente la logica di `DatabaseViewModel.resolveImportPayload`. Partition PH (§5) correttamente applicata: `isSyntheticImportRow` filtra per source IMPORT/IMPORT_PREV; assert separati su conteggio sintetico. RT-LOCALE usa locale spagnolo per export e locale default per import — verifica che gli header localizzati siano reimportabili cross-locale. `captureExpectation` cattura lo snapshot normalizzato dal source DB *prima* dell'export, con `null → 0.0` / `null → ""` coerenti con l'export effettivo.

4. **`DatabaseViewModelTest.kt` — test OOM export:** Corretto e minimale. Mock di `getAllPriceHistoryRows()` che lancia OOM, verifica che `uiState` diventa `UiState.Error` con messaggio formattato.

**Problemi trovati:**

Nessun problema bloccante.

**Rischi documentati (non bloccanti):**

| Rischio | Impatto | Mitigazione |
|---------|---------|-------------|
| **Confronto Double senza ε nei test PH** | Se fixture future usano decimali con rappresentazione binaria non esatta, gli assert esatti su `HistoryRowSnapshot` (data class `assertEquals`) potrebbero fallire falsamente. | I valori fixture attuali (3.2, 7.8, etc.) round-trippano esattamente attraverso Excel write/SAX read. Per fixture più complesse, convertire a confronto con ε. Non bloccante ora. |
| **`source = null` → `"IMPORT_SHEET"` non esercitato esplicitamente** | La trasformazione documentata in planning §3bis (record con source null nel sorgente diventa IMPORT_SHEET nel target) non è coperta da un test dedicato. | Tutti i fixture PH usano `source = "MANUAL"` che bypassa il problema. La logica di `flushBatch` è corretta per codice (verificata staticamente). Copertura esplicita opzionale in task futuro se si aggiungono fixture con source null. |
| **Criterio #5 non eseguibile** | Nessuna verifica runtime device/emulatore dell'export full DB. | Il test JVM EX-SIGNIFICANT mitiga il rischio su scala. Il profilo memoria/ANR del device reale resta non verificato. Da coprire con harness instrumented o smoke manuale in task separato. |

**Verdetto:** **APPROVED**

**Note per chiusura:**
- Aggiornare MASTER-PLAN.md per allineare lo stato di TASK-007 (REVIEW → DONE su conferma utente).
- Il criterio #5 resta ⚠️ NON ESEGUIBILE con motivazione accettata; non blocca la chiusura del task dato che i test JVM coprono il perimetro verificabile automaticamente.

---

## Fix

_(Da compilare dall'esecutore se necessario)_

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **DONE** |
| Data chiusura          | **2026-03-28** |
| Tutti i criteri ✅?    | **Sì**, con **#5** = **⚠️ NON ESEGUIBILE** (motivazione accettata in review: flow SAF/device non harnessato nel repo) |
| Rischi residui         | Smoke reale export full DB + dataset realistico non eseguito (**#5**); rischi non bloccanti in tabella review (ε su Double in test PH; `source null` → `IMPORT_SHEET` non coperto da test dedicato) |

---

## Riepilogo finale

Round-trip export full DB verificato con suite JVM **FullDbExportImportRoundTripTest** (matrix §6bis + **EX-SIGNIFICANT**); fix **ExcelUtils** (alias header old\*) e **DatabaseViewModel.exportFullDbToExcel** (OOM / `CancellationException`). Review **APPROVED** (2026-03-28); chiusura **DONE** su **conferma utente** successiva. Successore attivo in **MASTER-PLAN**: **TASK-008**.

---

## Handoff

- **TASK-008** (`Gestione errori e UX feedback`) — successore **`DONE`** (2026-03-28); vedi `docs/MASTER-PLAN.md` e `docs/TASKS/TASK-008-gestione-errori-e-ux-feedback.md`.
- Per coprire il residuo **#5** in futuro: smoke manuale/emulatore export full DB + SAF + dataset realistico, oppure harness dedicato (fuori da questo task).
