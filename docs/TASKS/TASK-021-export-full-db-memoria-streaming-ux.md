# TASK-021 — Export database: memoria/streaming, export selettivo fogli e UX DatabaseScreen (follow-up TASK-007)

---

## Informazioni generali

| Campo                 | Valore |
|-----------------------|--------|
| ID                    | TASK-021 |
| Stato (backlog / `MASTER-PLAN`) | **`DONE`** (2026-03-29) |
| Fase pianificazione / esecuzione (`AGENTS.md`) | **`DONE`** — review **APPROVED**; **conferma utente**; smoke manuale finale export **superato** (criterio **#14** ✅). |
| Priorità              | **ALTA** |
| Area                  | Export / Database / Stabilità / UX locale DatabaseScreen |
| Creato                | 2026-03-29 |
| Ultimo aggiornamento  | 2026-03-29 — chiusura **DONE**; smoke manuale finale confermato positivo dall’utente; tracking allineato a `MASTER-PLAN`. |

**Nota governance:** TASK-021 chiuso; **nessun** nuovo task attivato automaticamente — la prossima attivazione `ACTIVE` resta su **conferma utente / planner** (`MASTER-PLAN`).

---

## Dipendenze

- **TASK-007** (`DONE`) — round-trip import/export full DB, suite `FullDbExportImportRoundTripTest`. Questo task **non** riapre TASK-007.
- **TASK-004** (`DONE`) — baseline test automatica obbligatoria in Execution dove il perimetro coincide.

---

## Scopo

1. **Stabilità / memoria:** export stabile su **dataset grandi / device**; RAM ridotta (**SXSSF**, lettura **chunked** / cursor dove serve); **cleanup obbligatorio** di workbook streaming e **file temporanei** (vedi § Planning — requisito tecnico). **Fetch dati:** il repository/DAO deve essere interrogato **solo** per i fogli **selezionati** — nessun precaricamento di dataset per fogli esclusi (requisito esplicito § Planning).
2. **UX export DatabaseScreen:** dialog **Material 3** con **selezione multipla** dei quattro fogli + **preset rapidi** (Tutto, Solo Products, Anagrafica, Solo PriceHistory) + **copy esplicita** su full vs parziale / round-trip; default tutti selezionati; conferma disabilitata se nessuna selezione; **guard** contro doppio export e paralleli accidentali; **stato “export in corso” non ambiguo** (§ Planning — separato dal solo `UiState.Loading` generico del ViewModel).
3. **Unificazione logica:** un percorso export guidato da **`ExportSheetSelection`** (o equivalente); writer **testabile** in isolamento (preferenza **OutputStream** / layer senza Android per test JVM).
4. **Filename:** regola **vincolata** in planning (§ sotto), implementata in Execution senza reinterpretazione libera.
5. **Feedback:** progress sulle sole fasi selezionate, con **pesi coerenti col costo reale** delle fasi (§ Planning — non ripartizione uniforme solo sul conteggio fogli); messaggi OOM / IO / SAF / generico senza raw `Throwable.message` come primario.
6. **Single source of truth:** nomi foglio tecnici, header di schema (e pattern filename) definiti in **costanti centralizzate** nel perimetro writer/helper (§ Planning) per allineare export, test JVM e round-trip.

Nomi foglio tecnici invariati a livello funzionale: `Products`, `Suppliers`, `Categories`, `PriceHistory` (valori letterali da **unica** definizione condivisa).

---

## Contesto

- **Memoria:** dataset grandi → pressione RAM, `error_file_too_large_or_complex`; path attuale con liste complete + `XSSFWorkbook`.
- **UX:** dialog al posto del dropdown; preset e copy riducono attrito e aspettative errate su re-import.

---

## Non incluso

- **TASK-015** (modernization globale DatabaseScreen).
- Redesign fuori dal flusso **export**.
- Modifiche import full DB **salvo** necessità reale.
- **TASK-018** (ora **`DONE`** — fuori scope export; solo contesto storico).

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — oggi: `showExportMenu`, **due** `CreateDocument` launcher (`exportToExcel` / `exportFullDbToExcel`), `LoadingDialog` se `uiState is Loading`, blur su `isLoading`. Execution: dialog export unificato, launcher unico (nome file da costanti), wiring stato export dedicato.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — **`DatabaseScreenTopBar`**: `DropdownMenu` con `R.string.export_products` / `R.string.export_database_full`; Execution: sostituire con un’azione che apre il dialog (firma composable da aggiornare).
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — **`LoadingDialog(UiState.Loading)`** (progress lineare); oggi condiviso da **qualsiasi** `Loading` sulla DatabaseScreen; valutare in Execution messaggio/progress coerente con **reason** export vs import (o dialog dedicato export se necessario, nel perimetro locale).
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — oggi: `exportToExcel`, `exportFullDbToExcel`, `UiState.Loading(message, progress)` **senza** campo `reason` (import/analisi/full import usano tutti `Loading`). Execution: API export unificata + discriminante export (§ Planning) + progress pesato.
- **Helper writer** (preferito) — es. `DatabaseExportWriter.kt` in `util/`: scrittura su **`OutputStream`**; ospita o importa **`DatabaseExportConstants`** (o `object` companion) con **nomi foglio**, **header colonne** (letterali inglesi dove fissi; per Products header localizzati — ordine/colonne da **unica** lista di `R.string.*` o builder centralizzato), **pattern/template filename** full/partial. Obiettivo: test JVM senza `ContentResolver`; **zero** duplicazione stringhe magiche rispetto a `FullDbExportImportRoundTripTest` / import (ove possibile i test referenziano le stesse costanti).
- Repository / DAO — letture **condizionali** alla selezione (e paginate/chunked ove previsto); verificabili da test che **non** invochino metodi superflui.
- `strings.xml` (+ en, es, zh) — dialog (titolo, fogli, preset, **due righe copy** full/parziale, pulsanti).
- Test: `DatabaseViewModelTest`, `FullDbExportImportRoundTripTest`, test **writer**; test che verifichino **assenza di chiamate** repository per fogli non selezionati (mock/spy); test **header-only** per foglio vuoto ma selezionato.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Stress memoria:** export con **4 fogli** (e casi con PH e/o Products selezionati) su **dataset documentato** in Execution: l’esecutore deve **prima della chiusura** riportare nel log **ordine di grandezza numerico** (es. ≥ *N* prodotti, ≥ *M* righe `product_prices`) scelto per validare il criterio — **non** solo aggettivo “grande”. Soglia minima di intento: **almeno migliaia** di righe prezzi e/o prodotti nella fixture di validazione manuale o test di carico JVM ove fattibile; se l’ambiente non consente, ⚠️ **NON ESEGUIBILE** con motivazione e massimo carico raggiunto. Esito: stabilità o errore **chiaro e tempestivo** | M / B / E | — |
| 2 | **Round-trip** con **4 fogli** e schema atteso: `FullDbExportImportRoundTripTest` **verde** | E / B | — |
| 3 | Export **parziale** (1–3 fogli): solo fogli selezionati; nomi tecnici corretti; nessun sheet fantasma; **nessuna query repository/DAO** per dataset di fogli **non** selezionati (verificabile con mock: es. solo Anagrafica → **no** `getAllProductsWithDetails` / **no** `getAllPriceHistoryRows`) | E | — |
| 3bis | **Fogli selezionati ma senza righe dati:** per ogni foglio incluso nella selezione, il file deve contenere il foglio con **solo riga header** (schema coerente con export attuale). Con **almeno un foglio** selezionato, l’export **deve** completarsi con **successo** (file valido). **Unico** blocco conferma dialog: **nessun foglio** selezionato — **non** mostrare errore tipo *«nessun prodotto da esportare»* quando l’utente ha selezionato solo Suppliers/Categories/PriceHistory anche se vuoti | E / M | — |
| 4 | **Preset** nel dialog: **Tutto** (4 fogli), **Solo Products**, **Anagrafica** (Suppliers + Categories), **Solo PriceHistory** — ciascuno imposta la selezione attesa al tap | M | — |
| 5 | **Copy UX nel dialog** (stringhe localizzate): testo breve che spiega (a) **tutti i fogli selezionati** = backup/export **compatibile** con **round-trip full DB**; (b) **selezione parziale** = file Excel **valido** ma **non** necessariamente reimportabile come **full database**. La copy è **parte obbligatoria dell’UI**, non solo nota in Handoff | M / S | — |
| 6 | **Filename:** export **full** (4 fogli) → `Database_yyyy_MM_dd_HH-mm-ss.xlsx`; export **parziale** → `Database_partial_<sigle>_yyyy_MM_dd_HH-mm-ss.xlsx` con sigle nell’**ordine fisso dei fogli** **P → S → C → PH** (solo quelle selezionate), § Planning. Allineamento al `DISPLAY_NAME` proposto a SAF dove il flusso lo supporta | S / M | — |
| 7 | **Guard export + stato dedicato:** durante export — **nessun secondo avvio**; **nessun export parallelo**. Alla conferma: **chiudere subito il dialog**. La UI (toolbar export disabilitata, eventuale indicatore) deve basarsi su **stato esplicitamente legato all’export**, **non** sul solo fatto che `UiState` sia `Loading` **se** lo stesso sealed class è già usato per import/analisi/altri flussi (collisione UX vietata). Soluzione minima accettabile in Execution (una o combinazione): `UiState.Loading` con **campo `reason`/tag** distinto per export; oppure `StateFlow<Boolean>` / `exportJob` **exportInProgress** esposto alla UI; oppure sottotipo `Loading(ExportInProgress)` — da documentare nel log Execution. **Non** disabilitare il bottone export perché un **altro** loading non-export è attivo | M / S | — |
| 8 | **Cleanup streaming:** se si usano **SXSSFWorkbook** e/o **file temporanei** — `dispose()`/close workbook come da API POI; **cancellazione temp file** in **`finally`** (o `use` equivalente) anche su **eccezione** e **cancel**; niente leak file in cache dell’app verificabile almeno a ispezione logica in test o smoke | S / M | — |
| 9 | **Writer testabile:** test JVM sul motore di scrittura che **non** richiedono Android Context per il core (uso `ByteArrayOutputStream` / `File` temp JVM); regressione duplicazione Products/full risolta senza refactor gratuito fuori perimetro | E | — |
| 10 | **Progress** solo per fogli selezionati e **non** a pesi uniformi se fuorviante: **Products** e soprattutto **PriceHistory** devono contare **più** di **Suppliers/Categories** nel reparto percentuali (o frazioni di range) così che la barra non avanzi come se ogni foglio costasse uguale; in Execution documentare i pesi o la tabella fase→% usata | S / M | — |
| 11 | Messaggi utente senza raw primario; OOM / IO / SAF distinti | S / M | — |
| 12 | Nessuna regressione funzionale vs export equivalente pre-task (solo Products = preset; full = Tutto), **salvo** che il vecchio blocco *solo* su lista prodotti vuota **non** si applica quando **Products non è selezionato** (vedi **#3bis**) | E / M | — |
| 13 | **Baseline TASK-004** + build/lint documentati | B / E | — |
| 14 | Smoke manuale: preset, copy leggibile, SAF, file naming, impossibilità doppio export; **verifica** export con fogli vuoti (header-only) e **senza** Products in selezione → **nessun** `error_no_products_to_export` | M | — |

Legenda: B=Build, S=Static, M=Manuale, E=Test JVM.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1–4 | (invariate) TASK-021 vs TASK-007, un solo ACTIVE, dialog selettivo, full = 4 fogli | — | 2026-03-29 |
| 5 | **Preset** Tutto / Solo P / Anagrafica (S+C) / Solo PH | Micro-UX locale | 2026-03-29 |
| 6 | **Filename** full vs partial con pattern § Planning | Evitare ambiguità in Execution | 2026-03-29 |
| 7 | Dialog **chiuso** alla conferma; toolbar export disabilitata solo quando **export in corso** è **esplicito** (non solo `Loading` generico) | Guard + no collisione con altri loading ViewModel | 2026-03-29 |
| 11 | **Stato export dedicato** (reason / flag / variant) — vincolato in § Planning | `UiState.Loading` condiviso con altri flussi | 2026-03-29 |
| 8 | **Cleanup** SXSSF/temp = requisito obbligatorio | Affidabilità / spazio disco | 2026-03-29 |
| 9 | **Fetch strettamente selettivo:** nessuna lettura DB per fogli non selezionati | Efficienza / memoria / coerenza con export parziale | 2026-03-29 |
| 10 | **Dataset vuoti:** header-only OK; success se ≥1 foglio selezionato; **vietato** errore «no products» se Products **non** è in selezione | UX e regressione vs `error_no_products_to_export` | 2026-03-29 |
| 12 | **Progress pesato:** fasi «pesanti» (P, PH) > fasi «leggere» (S, C) per credibilità UX | Evitare progress lineare fuorviante | 2026-03-29 |
| 13 | **Costanti centralizzate** fogli / header / filename in un solo modulo writer (o file dedicato nello stesso package) | Allineamento export ↔ test ↔ round-trip | 2026-03-29 |

---

## Planning (Claude)

### Estensione scopo (sintesi)

TASK-021 = memoria/streaming + dialog (multi-selezione + **preset**) + **copy UX** + **filename** (sigle **P→S→C→PH**) + **stato export dedicato** + **mutex** + **cleanup** + **writer testabile** + **fetch solo fogli selezionati** + **fogli vuoti con header** + **progress pesato** (P/PH vs S/C) + **costanti centralizzate** + dataset numerico in Execution.

### Obiettivo UX/UI

- Dialog M3: checkbox per foglio + **azioni preset** (chip, menu compatto o righe cliccabili — scelta idiomatica Compose/M3 **locale al dialog**):
  - **Tutto** → tutti e quattro i fogli.
  - **Solo Products** → solo `Products`.
  - **Anagrafica** → `Suppliers` + `Categories`.
  - **Solo PriceHistory** → solo `PriceHistory`.
- I preset **impostano** la selezione (l’utente può poi rifinire manualmente).
- **Copy obbligatoria** (sotto titolo o corpo dialog, 1–2 frasi + eventuale bullet breve):
  - Con **tutti** i fogli selezionati: indicare che si ottiene un **backup completo** del database in Excel **compatibile** con il **re-import full DB / round-trip** (tono utente, senza jargon interno).
  - Con **almeno un foglio deselezionato**: indicare che il file resta un **export dati valido**, ma **potrebbe non essere** riconosciuto o adatto al flusso di **import database completo** — senza promesse di round-trip.
- Default: tutti selezionati; conferma disabilitata **solo** se **nessun** foglio selezionato (non dipendere da «ci sono righe nel DB»).
- **Dataset vuoti:** l’utente può esportare fogli senza righe: si ottiene comunque un file **valido** con header; nessun messaggio d’errore che replichi il vecchio *«nessun prodotto da esportare»* quando Products **non** è tra i fogli scelti.
- **Comportamento durante export (deciso in planning):** al tap **Esporta** → **chiudere il dialog** immediatamente; mostrare progress/export in corso in modo che **non** si confonda con altri caricamenti della stessa schermata.
- **Stato export dedicato (requisito):** `DatabaseViewModel` usa già `UiState.Loading` per **import**, analisi, ecc. **Non** basare la logica «export in corso» sul solo `uiState is Loading` senza discriminante — altrimenti la toolbar potrebbe restare incoerente (es. export disabilitato durante un import, o viceversa). **Obbligatorio** introdurre almeno uno tra: (a) **`reason` / enum** su `Loading` che includa un valore **`EXPORT`** (e la UI controlla quello per disabilitare export + mostrare messaggio/progress export); (b) **`StateFlow`/`MutableStateFlow` separato** `exportInProgress` (o `exportProgress`) letto da `DatabaseScreen` solo per export; (c) **`UiState` dedicato** per la fase export (es. `Exporting`) se compatibile col resto del modello — purché **non** sia ambiguo con altri flussi. L’export in corso deve essere **osservabile** in modo booleano o tagged per i composable interessati **senza** dipendere da messaggi stringa liberi.

### Regola filename (vincolante per Execution)

- **Full export** (selezione = tutti e 4 i fogli):  
  `Database_yyyy_MM_dd_HH-mm-ss.xlsx`  
  Esempio: `Database_2026_03_29_14-30-00.xlsx`

**Nota audit codice (coerenza locale):** in `DatabaseScreen.kt` oggi il nome proposto a SAF è **`stringResource(R.string.export_database_filename_prefix)` + timestamp** con pattern **`yyyy_MM_dd_HH-mm-ss`** (stesso formato data/ora del planning). Il prefisso è **localizzato** (es. EN `Database_`, IT `Database_`, ZH `数据库_`, ES `BaseDeDatos_`). In Execution: **o** si mantiene l’i18n del prefisso centralizzando prefisso + pattern nelle costanti collegate a `strings.xml`, **o** si adotta il literal ASCII `Database_` del planning per tutte le lingue — **decisione da documentare nel log Execution** (trade-off UX locale vs nome file identico cross-locale).

- **Export parziale** (almeno un foglio escluso):  
  `Database_partial_<sigle>_yyyy_MM_dd_HH-mm-ss.xlsx`

**Sigle** (ASCII, filesystem-safe). **Ordine nel nome file partial:** ordine **fisso business-friendly**, allineato all’ordine dei fogli nel workbook (**Products → Suppliers → Categories → PriceHistory**), **non** ordine alfabetico stringa.

| Ordine fisso | Foglio        | Sigla |
|--------------|---------------|-------|
| 1            | Products      | `P`   |
| 2            | Suppliers     | `S`   |
| 3            | Categories    | `C`   |
| 4            | PriceHistory  | `PH`  |

**Costruzione di `<sigle>`:** attraversare la tabella **dall’alto in basso**; per ogni foglio **selezionato**, aggiungere la sigla; unire con `_`. Così si ottiene sempre una sottosequenza di **`P_S_C_PH`** (es. solo `S` e `C` → `S_C`, mai `C_S`).

Esempi:
- Solo Products → `Database_partial_P_2026_03_29_14-30-00.xlsx`
- Anagrafica (S+C) → `Database_partial_S_C_2026_03_29_14-30-00.xlsx`
- Products + Suppliers + PriceHistory → `Database_partial_P_S_PH_2026_03_29_14-30-00.xlsx`

- **Nota SAF:** se il provider ignora il display name, documentare in Execution; la regola resta la **intenzione** del nome proposto.

### Architettura tecnica

1. **`ExportSheetSelection`** con helper `isEmpty`, `isFullExport` (4 fogli), e per il filename partial una funzione tipo **`partialSigilsInSheetOrder(): List<String>`** (o equivalente) che restituisce le sigle **solo** dei fogli selezionati nell’ordine **P → S → C → PH** (per costruire `P_S_PH` ecc.).
2. **Fetch strettamente selettivo (requisito efficienza/memoria):**
   - Il ViewModel (o orchestratore) deve chiamare il repository **solo** per i tipi di dati necessari ai fogli **selezionati** (es. se `Products` è off → **nessuna** `getAllProductsWithDetails`; se `PriceHistory` è off → **nessuna** `getAllPriceHistoryRows`; stessa logica per Suppliers/Categories).
   - **Vietato** precaricare in RAM liste per fogli che non verranno scritti.
   - In Execution: preferire struttura `if (selection.products) { … fetch … }` (o equivalente) documentata; i test con **mock/spy** devono poter **fallire** se compaiono chiamate inattese.
3. **Entry point unico** ViewModel → (fetch condizionale) → stream → **writer** su **`OutputStream`**.
4. **Boundary writer (requisito):** nucleo POI **testabile su JVM** con `OutputStream`. ViewModel: SAF, dispatching, catch. Un solo writer parametrico sulla selezione.
5. **Workbook:** solo fogli selezionati; ordine nel file **Products → Suppliers → Categories → PriceHistory** (omettendo assenti).
6. **Fogli selezionati con zero righe dati:**
   - L’export **non** fallisce per «database vuoto» su quel foglio: si emette comunque il foglio con **riga 0 = header** e **nessuna riga dati** (schema identico all’export odierno per quel foglio).
   - **Solo** `selection.isEmpty` (nessun foglio spuntato) impedisce la conferma nel dialog — **non** sostituire con errori snackbar tipo `error_no_products_to_export` quando l’utente **non** ha chiesto il foglio Products.
   - Caso limite: selezione include **solo** Products e lista prodotti vuota → file con foglio Products header-only e **success** (coerente con #3bis); opzionale messaggio informativo non bloccante **solo** se il task accetta micro-copy aggiuntiva — default planning: **success silenzioso** come altri fogli vuoti.
7. **Progress (realistico, non solo “1/N fogli”):**
   - **Vietato** come unico schema una ripartizione **uniforme** della percentuale solo sul **conteggio** dei fogli selezionati se ciò rende **incredibile** l’esperienza (es. Suppliers+Categories che «consumano» quanto PriceHistory su DB grandi).
   - **PriceHistory** è tipicamente la fase **più costosa** (lettura/scrittura molte righe, eventuale streaming); **Products** (con join/details) è **più pesante** di Suppliers/Categories.
   - **Suppliers** e **Categories** sono di solito **leggeri** → devono occupare una **quota minore** del range progress rispetto a P e PH quando tutti sono selezionati, oppure scalare via se non selezionati.
   - Implementazione in Execution: pesi fissi documentati (es. tabella percentuale o segmenti per fase), oppure progress derivato da **sottofasi** (fetch vs write per foglio) con pesi diversi; l’obiettivo è **coerenza percepita** col lavoro reale, non una formula rigida se il dataset è vuoto (in quel caso le fasi sono comunque brevi — si può normalizzare o saltare rapidamente).
8. **Costanti centralizzate (single source of truth):**
   - **Nomi tecnici dei fogli** (`Products`, `Suppliers`, `Categories`, `PriceHistory`): **una sola** definizione (es. `const val` / `object DatabaseExportConstants`) usata da writer, ViewModel (filename/sigle), e **preferibilmente** referenziata o re-esportata dai test JVM e dalle assert round-trip ove oggi esistono stringhe duplicate.
   - **Header di schema** per foglio: stesso modulo — colonne **fisse in inglese** (Suppliers, Categories, PriceHistory) come costanti; per **Products** (header localizzati) centralizzare l’**ordine** e i **riferimenti** `R.string.*` in un’unica lista/funzione usata solo dal writer, così test e manutenzione non divergono.
   - **Pattern filename** full/partial (`Database_yyyy_…`, `Database_partial_…`, formato timestamp): template in **unico** punto (stesso file delle costanti foglio o companion del writer), così SAF `DISPLAY_NAME` e test `buildExportDisplayName` non driftano.
9. **Performance POI (nota preventiva):** in Execution evitare su export grandi: **auto-size colonna** su migliaia di righe, proliferazione di **`CellStyle`** distinti, formattazioni condizionali, merge non necessari — ogni scelta che aumenti RAM o tempo deve essere **giustificata** da requisito funzionale esplicito.

### Cleanup obbligatorio (requisito tecnico)

- **`SXSSFWorkbook`:** chiamare **`dispose()`** (e/o pattern raccomandato POI per la versione in uso) quando applicabile; chiudere risorse in **`use`** / `try-finally`.
- **File temporanei** (se usati per staging): creati in cache controllata; **`delete()` in `finally`** anche se `write` fallisce o `OutOfMemoryError` dopo catch gestito; in caso di impossibilità di delete, log diagnostico + nota in Execution.
- Coprire con test o verifica mirata dove possibile (es. assert che `createTempFile` companion sia cancellato in test JVM).

### Guard anti-doppio export

- ViewModel: se export già in corso (**secondo il flag/reason dedicato**, non un generico `Loading` altrui), **ignorare** o **no-op** seconda richiesta; esporre stato chiaro.
- UI: dialog non riapribile per seconda conferma sullo stesso job; **bottone export toolbar disabilitato solo quando `exportInProgress` / `reason == EXPORT` / equivalente** — non quando è in corso solo un import o altra operazione.

### Compatibilità e limiti

| Scenario | Round-trip full DB |
|----------|-------------------|
| 4 fogli | Obbligatorio (test esistenti). |
| < 4 fogli | Export valido; round-trip full DB **non** richiesto. |

### Diagnosi memoria (riferimento)

Liste complete + `XSSFWorkbook` + `groupBy` PriceHistory → mitigare con streaming lettura/scrittura ove i fogli selezionati lo richiedono.

### Coerenza con codice reale — audit pre-Execution (2026-03-29)

Verificato sulla repo Android (nessuna modifica applicativa in questo turno):

| Elemento | Evidenza in codice |
|----------|-------------------|
| **Export UI** | `DatabaseScreenTopBar` in `DatabaseScreenComponents.kt`: `DropdownMenu` a due voci (`export_products`, `export_database_full`) — allineato al planning da sostituire con dialog M3. |
| **Launchers SAF** | `DatabaseScreen.kt`: `downloadLauncher` → `exportToExcel`; `downloadFullDbLauncher` → `exportFullDbToExcel` — da unificare dopo selezione fogli + nome file. |
| **`UiState`** | `DatabaseViewModel.kt`: `sealed class UiState` con `Loading(message, progress)` **senza** tag/reason — conferma obbligatorietà **stato export dedicato** (§ Obiettivo UX). |
| **`LoadingDialog`** | `DatabaseScreen.kt` righe 206–207: qualsiasi `UiState.Loading` mostra `LoadingDialog`; stesso schema per import smart, analisi, full import, export — **collisione UX** reale; mitigazione come da criterio #7 / Decisione #11. |
| **Blur lista** | `DatabaseScreen.kt`: `blur(if (isLoading) 10.dp else 0.dp)` per **qualsiasi** loading — accettabile se overlay blocca già l’interazione; se in futuro si distingue export “leggero” vs import, valutare nel perimetro task. |
| **Repository / DAO** | `getAllProductsWithDetails` → `productDao.getAllWithDetailsOnce()`; `getAllPriceHistoryRows` → `priceDao.getAllWithBarcode()`. Esiste `getAllWithDetailsPaged` per la lista UI — **non** ancora un cursore dedicato export; streaming/chunked in Execution = estensione DAO/repository come da planning. |
| **Test JVM** | `DatabaseViewModelTest`: `exportToExcel` lista vuota → `error_no_products_to_export` — andrà **allineato** a #3bis quando l’export sarà selettivo (solo Products vuoto vs header-only success). `exportFullDbToExcel` OOM test. `FullDbExportImportRoundTripTest`: `exportFullDatabase` → `viewModel.exportFullDbToExcel` — aggiornare alla nuova API / selezione piena. |
| **Stringhe menu export** | `export_products`, `export_database_full`, `export_database_filename_prefix`, `sheet_name_products` in `values*` — il dialog nuovo aggiungerà chiavi; menu dropdown potrà essere rimosso o deprecato. |

**Conclusione audit:** nessun errore strutturale nel planning rispetto alla codebase; integrazioni sopra rendono espliciti i punti di aggancio reali. Il perimetro resta il flusso export locale DatabaseScreen.

### Test planning

- JVM: workbook da `ByteArrayOutputStream` — presenza/assenza fogli per selezione e preset (mapping logico).
- **Header-only:** selezione con foglio X ma dataset mock vuoto → una sola riga header, `physicalNumberOfRows == 1` (o equivalente POI).
- **Fetch selettivo:** mock repository — selezione solo `S+C` → verificare **zero** chiamate a metodi Products/PriceHistory; selezione solo `PH` → **zero** Products/Suppliers/Categories se non richiesti.
- Round-trip: selezione piena (dati non vuoti per assert esistenti).
- ViewModel: progress per selezione; **seconda chiamata export mentre export dedicato attivo** → nessun doppio side-effect (mock). Test che **import/altro Loading** non attivi il guard export (toolbar logic) salvo che non si scelga di unificare messaggi — in tal caso documentare.
- Filename: unit test su `buildExportDisplayName(selection, timestamp)` (o simile) con assert **ordine P→S→C→PH** (es. S+C → `S_C`, P+S+PH → `P_S_PH`); i literal attesi devono provenire dalle **stesse costanti** del modulo export ove possibile.
- **Costanti:** assert sui **nomi foglio** e header attesi nei test workbook/referenziare `DatabaseExportConstants` (o nome scelto) — niente stringhe duplicate hardcoded fuori dal modulo centralizzato salvo eccezione documentata.
- **Progress (opzionale in JVM):** se si espone una funzione pura `phaseWeights(selection)`, test tabella pesi (es. PH ≥ quota S+C); altrimenti documentazione + smoke manuale per credibilità percepita.
- Smoke: preset, copy, mutex; scenario **solo Anagrafica vuota** → file valido, nessun errore «no products».

### Rischio regressione

| Rischio | Mitigazione |
|---------|-------------|
| Doppio export / race | Criterio #7; test ViewModel |
| Collisione UX **Loading** export vs import/analisi | Stato export **dedicato** (Decisione **#11**); UI legge tag/flow esplicito |
| Temp file orfani | Criterio #8; finally |
| Nome file errato su partial | Test ordine **P→S→C→PH** + review |
| POI lento / OOM da stili | Nota performance; review Execution |
| SAF ignora nome | Documentazione Execution |
| Regressione **`error_no_products_to_export`** su export senza Products | Criteri **#3bis**, **#12**; test ViewModel + mock |
| Fetch non selettivo (carico inutile) | Code review + test spy **#3** |
| Drift nomi foglio / header / filename tra writer, test, import | Costanti **#13**; review diff |
| Progress **uniforme** fuorviante su DB grandi | Criterio **#10**; pesi documentati in Execution |

### Piano di esecuzione (post-approvazione)

1. **`DatabaseExportConstants`** (o equivalente): fogli, header, template filename; writer + test + round-trip allineati.  
2. Writer su `OutputStream` + `ExportSheetSelection` (fogli condizionali, **header-only** se vuoto); test JVM.  
3. **Orchestrazione fetch** strettamente legata alla selezione; test spy.  
4. SXSSF/chunked + **cleanup** sempre.  
5. ViewModel: **progress pesato** P/PH vs S/C + filename + mutex + **stato export dedicato** + rimozione blocco «no products» fuori dal caso *solo Products vuoto* coerente con #3bis.  
6. Dialog: checkbox + preset + **copy** + chiusura su conferma.  
7. Toolbar: disabilitazione export legata **solo** allo stato export esplicito.  
8. Dataset numerico documentato per criterio #1.  
9. Baseline TASK-004 + smoke.

### Rischi identificati

- SXSSF + dispose su percorsi eccezione.  
- **Regression:** uso improprio di `Loading` generico senza tag → collisioni toolbar; mitigazione Decisione **#11**.  
- Progress **troppo uniforme** → utente percepisce stallo su PH; mitigazione Decisione **#12**.  
- Duplicazione stringhe foglio/header fuori dalle costanti → round-trip rotto silenziosamente; mitigazione **#13**.  
- Scope: restare sul dialog export.

---

## Execution

### Transizione governance — 2026-03-29

**Azione:** sanity check planning vs file reali (`DatabaseScreen*.kt`, `DatabaseViewModel.kt`, `InventoryRepository`/`ProductDao`/`ProductPriceDao`, `DatabaseViewModelTest`, `FullDbExportImportRoundTripTest`, `values*/strings.xml`); integrazione sezione **Coerenza con codice reale** + path assoluti package + nota filename i18n. **Stato:** `PLANNING` → **`EXECUTION`** (solo documentazione/governance in questo turno).

**File modificati (solo docs):** `docs/TASKS/TASK-021-export-full-db-memoria-streaming-ux.md`, `docs/MASTER-PLAN.md`.

**Codice applicativo:** **non modificato** in questo turno.

**Prossimo passo esecutore:** implementazione secondo Planning + checklist `AGENTS.md` (build/lint/test a fine ciclo Execution).

### Esecuzione — 2026-03-29

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/DatabaseExportWriter.kt` — nuovo modulo export con `ExportSheetSelection`, costanti centralizzate fogli/header/filename, writer `SXSSFWorkbook` su `OutputStream`, fogli selettivi, `header-only` e cleanup `close()+dispose()`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — entry point export unificato, `ExportUiState` dedicato, guard anti-doppio export, fetch strettamente selettivo, progress pesato e messaggi utente distinti.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — launcher SAF unico, dialog export M3, overlay/progress agganciato a `exportUiState`, toolbar coerente con stato export reale.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — rimosso dropdown export; top bar semplificata con singola azione export disabilitabile.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — aggiunto `DatabaseExportDialog` con preset, checkbox, copy full/parziale e overload `LoadingDialog(message, progress)`.
- `app/src/main/res/values/strings.xml` — nuove stringhe dialog/progress export in IT.
- `app/src/main/res/values-en/strings.xml` — nuove stringhe dialog/progress export in EN.
- `app/src/main/res/values-es/strings.xml` — nuove stringhe dialog/progress export in ES.
- `app/src/main/res/values-zh/strings.xml` — nuove stringhe dialog/progress export in ZH.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/DatabaseExportWriterTest.kt` — nuovi test JVM puri su filename, workbook selettivo, `header-only` e `oldPrice` progressivo nel foglio `PriceHistory`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — riallineati i test export a API unificata, `header-only` su Products vuoto, fetch selettivo e guard anti-doppio export.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — round-trip full DB riallineato a API/costanti condivise.
- `docs/TASKS/TASK-021-export-full-db-memoria-streaming-ux.md` — log Execution, esiti criteri e handoff review.
- `docs/MASTER-PLAN.md` — tracking allineato a chiusura `TASK-021` → `DONE` (2026-03-29).

**Azioni eseguite:**
1. Creato il perimetro condiviso `DatabaseExportWriter.kt` con:
   - fogli tecnici centralizzati (`Products`, `Suppliers`, `Categories`, `PriceHistory`);
   - header condivisi per Suppliers/Categories/PriceHistory e ordine centralizzato degli header Products localizzati;
   - filename deterministico `Database_...` / `Database_partial_<sigle>_...` con ordine fisso `P → S → C → PH`;
   - writer `SXSSFWorkbook(100)` con `compressTempFiles`, `close()` e `dispose()` in `finally`.
2. Unificato l’export del `DatabaseViewModel` in un solo metodo guidato da `ExportSheetSelection`, rimuovendo il doppio percorso `exportToExcel` / `exportFullDbToExcel`.
3. Implementato il fetch strettamente selettivo: il ViewModel interroga il repository solo per i fogli richiesti; i test falliscono se compaiono chiamate Products/PriceHistory fuori selezione.
4. Reso l’export robusto sui dataset vuoti: ogni foglio selezionato viene sempre scritto con la sola riga header se non ha dati; eliminata la regressione del blocco `error_no_products_to_export` quando Products è vuoto o non selezionato.
5. Sostituito il dropdown export con dialog Material 3 locale al task:
   - preset `Tutto`, `Solo prodotti`, `Anagrafica`, `Solo storico prezzi`;
   - checkbox multi-selezione;
   - copy dinamica full/parziale;
   - conferma disabilitata se nessun foglio è selezionato;
   - chiusura immediata del dialog alla conferma.
6. Introdotto `ExportUiState` separato da `UiState.Loading`, così toolbar e overlay dipendono solo dall’export reale e non da import/analisi.
7. Implementato il guard anti-doppio export con `Mutex.tryLock()` lato ViewModel; la toolbar si disabilita solo quando `exportUiState.inProgress == true`.
8. Aggiornato il progress export con pesi documentati:
   - pesi foglio: `Products=35`, `Suppliers=10`, `Categories=10`, `PriceHistory=45`;
   - range `5% → 95%` normalizzato sui soli fogli selezionati;
   - split per foglio `40% fetch / 60% write`;
   - `97%` riservato alla finalizzazione file.
9. Decisione filename documentata: adottato prefisso ASCII fisso `Database_` / `Database_partial_` in tutte le lingue per mantenere naming filesystem-safe, costanti condivise e assert testabili senza drift locale.
10. UI/UX: migliorato intenzionalmente il flusso export con dialog M3, preset visibili, caption tecnica dei fogli e copy esplicita full/parziale (motivo: chiarezza, coerenza locale al task, riduzione ambiguità sul round-trip).
11. Eseguiti build/lint e baseline JVM del perimetro; su questa macchina i test con MockK sono affidabili se eseguiti **isolando la classe suite** e usando `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true'` + `--max-workers=1`.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug --max-workers=1` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ ESEGUITO | `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint --max-workers=1` → `BUILD SUCCESSFUL`; corretto 1 errore lint introdotto (`LocalContext.getString`) prima del run finale |
| Warning nuovi            | ✅ ESEGUITO | Nessun nuovo warning Kotlin/lint nel codice modificato; restano warning/deprecazioni storiche di progetto/AGP/Robolectric fuori scope |
| Coerenza con planning    | ✅ ESEGUITO | Implementati selection model, writer SXSSF, stato export dedicato, dialog M3, filename, fetch selettivo, `header-only`, progress pesato e test aggiornati |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti i criteri verificati; **#14** soddisfatto con **smoke manuale finale** eseguito dall’utente con esito positivo (2026-03-29) |

**Verifica criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | `FullDbExportImportRoundTripTest.EX-SIGNIFICANT` verde con fixture numerica: `1200` prodotti + `4800` righe `product_prices` (`historyRowsPerProduct=4`), workbook full openable con 4 fogli |
| 2 | ✅ ESEGUITO | `FullDbExportImportRoundTripTest` verde in run isolato |
| 3 | ✅ ESEGUITO | `DatabaseViewModelTest.exportDatabase catalog only skips product and price history fetches` verifica workbook parziale + `coVerify(exactly = 0)` su `getAllProductsWithDetails` / `getAllPriceHistoryRows` |
| 3bis | ✅ ESEGUITO | `DatabaseViewModelTest.exportDatabase products only with empty dataset writes header only and emits success` + `DatabaseExportWriterTest.writeDatabaseExport keeps workbook selective and writes header only for empty selected sheet` |
| 4 | ✅ ESEGUITO | `DatabaseExportDialog` implementa i 4 preset richiesti con mapping diretto a `ExportSheetSelection.full/productsOnly/catalogOnly/priceHistoryOnly` |
| 5 | ✅ ESEGUITO | Copy full/parziale localizzata in `values*` e resa obbligatoria nel dialog (`export_database_dialog_full_copy` / `...partial_copy`) |
| 6 | ✅ ESEGUITO | `DatabaseExportWriterTest.buildDatabaseExportDisplayName follows full and partial naming rules` verifica full + partial con ordine `P → S → C → PH`; SAF usa `buildDatabaseExportDisplayName(...)` |
| 7 | ✅ ESEGUITO | `ExportUiState` dedicato + `Mutex.tryLock()` + toolbar disabilitata solo su `exportUiState.inProgress`; test `DatabaseViewModelTest.exportDatabase ignores second request while one export is already running` |
| 8 | ✅ ESEGUITO | Writer `SXSSFWorkbook` con `close()` + `dispose()` in `finally`; nessun temp staging aggiuntivo introdotto; cleanup ispezionato staticamente nel modulo export |
| 9 | ✅ ESEGUITO | Nuovo `DatabaseExportWriterTest` usa `ByteArrayOutputStream` / `XSSFWorkbook(ByteArrayInputStream(...))` senza `Context` per il core writer |
| 10 | ✅ ESEGUITO | Pesi documentati in Execution: `P=35`, `S=10`, `C=10`, `PH=45`, split `40/60`, range `5% → 95%`, `97%` finishing |
| 11 | ✅ ESEGUITO | `DatabaseViewModel.exportErrorMessage(...)` resta la fonte unica per OOM / IO / SAF / generico; UI usa solo stringhe localizzate |
| 12 | ✅ ESEGUITO | `productsOnly()` replica il vecchio caso export prodotti; `full()` replica il full DB; Products vuoto ora esporta header-only con successo coerente con `#3bis` |
| 13 | ✅ ESEGUITO | Baseline `TASK-004` eseguita + `assembleDebug` + `lint` documentati |
| 14 | ✅ ESEGUITO | **Smoke manuale finale** eseguito dall’utente con esito positivo: dialog export, preset/copy, SAF e naming file, guard anti-doppio export, scenari coerenti con il planning |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --max-workers=1 --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest'`
  - `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --max-workers=1 --tests 'com.example.merchandisecontrolsplitview.util.DatabaseExportWriterTest'`
  - `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true' JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --max-workers=1 --tests 'com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest'`
- Test aggiunti/aggiornati:
  - nuovo `DatabaseExportWriterTest`
  - aggiornato `DatabaseViewModelTest` per API export unificata, `header-only`, fetch selettivo e guard export
  - aggiornato `FullDbExportImportRoundTripTest` per API export unificata e costanti condivise
- Limiti residui:
  - in questa JBR l’invocazione **combinata** di più suite MockK/Robolectric nello stesso `testDebugUnitTest` è meno stabile; i run **isolati per classe** sopra sono verdi e usati come baseline affidabile
  - (nessun limite aperto sul criterio **#14**: smoke manuale finale **completato** — v. **Chiusura**)

**Incertezze:**
- Nessun blocco funzionale aperto.
- Nota ambiente: per i test MockK su questa macchina serve `GRADLE_OPTS='-Djdk.attach.allowAttachSelf=true'` e conviene isolare la suite con `--tests ... --max-workers=1`.

**Handoff notes (pre-chiusura):**
- Smoke manuale richiesto da **#14**: **completato** dall’utente con esito positivo — v. sezione **Chiusura**.
- Se serve rieseguire la baseline JVM, usare i tre comandi isolati sopra; il run combinato di più suite nello stesso processo Gradle può essere instabile su questa JBR.

### Chiusura / conferma utente — 2026-03-29

**Smoke manuale finale:** l’utente ha eseguito la prova manuale del flusso export (DatabaseScreen) con **esito positivo**. Sono stati confermati in ambiente reale gli aspetti previsti dal criterio **#14** (dialog, preset, copy full/parziale, SAF e naming, comportamento coerente con guard export e round-trip atteso). Il **criterio manuale residuo** del task risulta **soddisfatto**.

---

## Review

### Review — 2026-03-29

**Revisore:** conferma utente post-review tecnica (chiusura formale)

**Criteri di accettazione:** tutti **ESEGUITO** / ✅ (incluso **#14** dopo smoke manuale positivo).

**Verdetto:** **APPROVED** — passaggio a **`DONE`** con conferma utente esplicita.

---

## Fix

*(Vuoto — nessun ciclo FIX post-review)*

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | **`DONE`** |
| Data chiusura | **2026-03-29** |
| Tutti i criteri ✅? | **Sì** — incluso **#14** (smoke manuale finale utente, esito positivo) |
| Rischi residui | Bassi: stabilità run JVM combinati MockK/Robolectric su alcune JBR (documentata in Execution); mitigazione = suite isolate come da log |

**Testo di chiusura (sintesi):** Export DB unificato (`DatabaseExportWriter`, `ExportSheetSelection`, `ExportUiState`), dialog M3 con preset e copy, fetch selettivo, SXSSF + cleanup, progress pesato, test JVM e round-trip allineati. Build, lint e baseline TASK-004 eseguite in Execution. **Smoke manuale finale** confermato **positivo** dall’utente; nessun criterio residuo aperto.

---

## Riepilogo finale

- **Obiettivo:** follow-up TASK-007 su memoria/streaming, fogli selettivi e UX DatabaseScreen — **raggiunto**.
- **Evidenze:** implementazione e test documentati in **Execution**; check obbligatori ✅; baseline JVM per classe documentata.
- **Manuale:** **#14** — smoke export reale **eseguito dall’utente, esito positivo** (2026-03-29).
- **Governance:** stato **`DONE`** allineato a `docs/MASTER-PLAN.md`; **nessun** nuovo task attivato automaticamente.

---

## Handoff

**TASK-021 è `DONE`.** Per il prossimo operatore: attivare un nuovo task solo su **conferma utente** (regola un solo `ACTIVE`). Candidati operativi restano smoke **TASK-006** / **TASK-011** (stato `BLOCKED`), **TASK-015** (`BACKLOG`), altri voci `MASTER-PLAN` / **Razionale priorità**. Il codice export vive in `DatabaseExportWriter.kt`, `DatabaseViewModel` (export), schermate `DatabaseScreen*`; eventuali regressioni: rieseguire i tre comandi test isolati in Execution e smoke mirato export.
