# TASK-006 — Validazione e robustezza import Excel

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-006                   |
| Stato              | **BLOCKED**                |
| Priorità           | MEDIA                      |
| Area               | Import / Excel             |
| Creato             | 2026-03-29                 |
| Ultimo aggiornamento | 2026-03-29 — **REVIEW → BLOCKED:** implementazione e review tecnica OK; build/lint/test JVM OK; **solo** smoke manuali / criteri **M** pendenti (non blocco da difetti codice). |

---

## Dipendenze

- **TASK-005** — `DONE` (test JVM su `ExcelUtils` / `ImportAnalyzer`)

---

## Scopo

Rendere più **prevedibili e comprensibili** gli errori nel percorso di import Excel “foglio prodotti”: file illeggibili o corrotti, formati non supportati, assenza di dati utilizzabili, e messaggi riga senza dettaglio tecnico grezzo. Interventi **locali** in `ExcelUtils.kt` e `util/ImportAnalysis.kt`, con integrazione **minima** nei ViewModel solo dove il flusso UI lo richiede (whitelist messaggi noti, append). Nessun redesign ampio. Nessun assorbimento di TASK-017 / TASK-019.

---

## Contesto

- **Ingresso file:** `readAndAnalyzeExcel` legge i byte, poi POI o fallback HTML. Eccezioni non classificate finiscono in messaggi generici tramite `analysisErrorMessage` / `fileLoadErrorMessage`.
- **ViewModel esistenti:** `DatabaseViewModel` espone `knownUserFacingFileMessage` + `analysisErrorMessage`; `ExcelViewModel` espone `knownUserFacingFileMessage` + `fileLoadErrorMessage`. Entrambi riconoscono già un set di messaggi localizzati passati come `IllegalArgumentException.message`.
- **Heuristica:** `analyzeRows` + `ensureColumn` possono produrre colonne generate vuote e poi errori **per-riga** coerenti in `ImportAnalyzer`.
- **Append:** `appendFromMultipleUris` oggi può terminare senza errore e senza append se `allNewDataRows` resta vuoto → **no-op silenzioso** da correggere in Execution secondo il piano sotto.
- **Append senza base:** `appendFromMultipleUris` usa `excelData.first()`; se la griglia non è stata ancora caricata (lista vuota o assenza di header/riga base valida), rischio **crash** o messaggio generico — da gestire con guard esplicito e **`error_main_file_needed`** (vedi Planning).

---

## Non incluso

- OOM / full DB import → **TASK-017**, **TASK-018** (`DONE`); audit i18n **intera app** (4 lingue, include PriceHistory) → **TASK-019** (`ACTIVE` / `PLANNING`).
- Audit globale feedback → **TASK-008** `DONE`.
- DAO, repository, modelli dati, `NavGraph`, integrazioni piattaforma salvo emergenza documentata.
- Redesign UI, nuovi dialog complessi, refactor architetturale dei ViewModel.

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `util/ExcelUtils.kt` | Punto **primario** per errori “file non leggibile / vuoto / senza dati tabellari” tramite eccezioni con messaggio già localizzato |
| `util/ImportAnalysis.kt` | Errori **per-riga**; rimozione `ex.message` user-visible; nuova risorsa dedicata (vedi Planning) |
| `app/src/main/res/values*/strings.xml` | Nuove stringhe solo dove sotto definite; riuso esplicito dove indicato |
| `viewmodel/DatabaseViewModel.kt` | Whitelist `knownUserFacingFileMessage` se nuove stringhe in eccezioni; **`validateImportFile` solo fallback difensivo** (vedi autostrada); messaggi import → **`UiState.Error`** |
| `viewmodel/ExcelViewModel.kt` | Stessa whitelist; `loadFromMultipleUris` / `appendFromMultipleUris`; feedback errori → **`loadError` only**; mapping copy primo file (vedi Planning) |

**Read-only salvo necessità dimostrata:** `data/ImportAnalysis.kt`, `data/RowImportError.kt`, DAO/repository, `NavGraph.kt`, `FullDbImportStreaming.kt`.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **File illeggibile / stream nullo / byte vuoti / workbook corrotto (eccezione POI):** l’utente riceve un messaggio **chiaro e localizzato** (tramite catena esistente ViewModel + messaggi noti o generici mappati), senza crash e senza griglia silenziosamente vuota dove oggi manca il feedback | B + S | — |
| 2 | **HTML senza tabella / nessun dato tabellare estraibile:** stessa **autostrada** di §Planning: rilevato in **`readAndAnalyzeExcel`** (esito equivalente a nessuna riga utile) → **`IllegalArgumentException(getString(R.string.error_file_empty_or_invalid))`** con default `allowEmptyTabularResult=false` | B + S | — |
| 3 | **Distinzione netta (nessuna ambiguità per l’esecutore):** (A) **Zero dati utili / file vuoto / non leggibile** → fallimento a livello **caricamento file** con messaggio dedicato o riuso stringhe esistenti §Planning stringhe; (B) **Dati presenti ma colonne non riconosciute / celle invalide** → si accetta il modello attuale **`ensureColumn` + errori riga** in `ImportAnalyzer`; **non** è richiesto fail-fast globale sulle colonne logiche mancanti | B + S | — |
| 4 | **ImportAnalyzer — errori imprevisti per riga:** nessun testo tecnico grezzo (`ex.message`); uso della risorsa dedicata **per-riga** definita nel Planning (**non** `error_data_analysis_generic` in lista errori riga, per non confondere “file” vs “riga”) | S | — |
| 5 | **ExcelViewModel — primo carico multi-URI:** se il **primo** file è vuoto o senza righe dati utili dopo parse → messaggio user-visible; riuso preferito di **`error_first_file_empty_or_invalid`** se il testo copre il caso (primo file della selezione) | B + S | — |
| 6 | **ExcelViewModel — append:** se **tutti** i file aggiunti producono **zero** righe dati aggregate → impostare **`loadError`** con **`error_append_no_data_rows`** (4 lingue); **no-op silenzioso vietato**. Se **almeno un** file apporta righe > 0 → append come oggi; URI con 0 righe nel batch → skip **senza** errore (parziale valido) | B + S | — |
| 7 | **Invariante stato griglia (append):** se l’append **fallisce** (eccezione, header incompatibile, o “tutti zero righe” con `loadError`) **oppure** tutti i file aggiunti hanno zero righe utili, **`excelData` / header / stato griglia esistente devono restare identici al pre-append** (nessuna modifica parziale, nessun clear) | B + S + M |
| 8 | Comportamento **duplicati / merge quantità / validazione prezzi** invariato rispetto ai test **TASK-005** salvo modifica motivata e test aggiornati | B | — |
| 9 | `assembleDebug` OK; `lint` eseguito; perimetro modificato senza nuovi problemi introdotti (preesistenti fuori scope OK) | B + S | — |
| 10 | Baseline **TASK-004** se modificati `DatabaseViewModel` / `ExcelViewModel`: eseguire i test JVM secondo **§Strategia test**; il percorso **primario** da coprire è la propagazione dell’**`IllegalArgumentException` con messaggio localizzato** da **`readAndAnalyzeExcel`**, non il solo arrivo di una triple vuota al `validateImportFile` | B | — |
| 11 | **Append senza file principale / senza header base:** se `appendFromMultipleUris` viene invocato senza stato griglia valido (`excelData` vuoto **oppure** nessuna riga header utilizzabile come riferimento — definizione operativa in Execution: stessa condizione del guard prima di `first()`), **nessun crash**, **nessun** messaggio tecnico grezzo (`NoSuchElementException` / stack in UI); impostare **`loadError`** con **`getString(R.string.error_main_file_needed)`** (già in `knownUserFacingFileMessage` se si passa da eccezione con quel messaggio, oppure assegnazione diretta a `loadError` con la stessa stringa); **nessuna modifica** a `excelData` né agli stati dipendenti | B + S + M |

Legenda: B=Build, S=Static/test JVM, M=Manuale (smoke).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task in backlog **`BLOCKED`** (2026-03-29): smoke manuali / criteri **M** pendenti; implementazione e check automatici OK | Governance allineata a TASK-011 | 2026-03-29 |
| 2 | **Nessun fail-fast** obbligatorio per “colonne logiche non riconosciute” quando esistono già `ensureColumn` + errori riga: evita falsi negativi su layout limite e allinea UX a feedback incrementale | Codice reale + richiesta utente | 2026-03-29 |
| 3 | Errori **imprevisti per riga:** nuova stringa dedicata **senza placeholder** tecnico (non riuso di `error_data_analysis_generic` in UI elenco righe) | Chiarezza UX riga vs file | 2026-03-29 |
| 4 | Errori **livello file** (vuoto/illeggibile/zero righe utili): **`readAndAnalyzeExcel`** in **`ExcelUtils.kt`** è la **sorgente primaria** delle eccezioni `IllegalArgumentException(getString(...))`; ViewModel **non** replicano la stessa condizione | Autostrada unica | 2026-03-29 |
| 5 | **Append — stato invariato** su fallimento o “tutti zero righe”: griglia e stato già caricati **non** devono mutare | UX e assenza regressioni | 2026-03-29 |
| 6 | **Canale feedback:** `ExcelViewModel` → **`loadError`**; `DatabaseViewModel` (import analisi) → **`UiState.Error`**; nessun nuovo toast/snackbar/dialog per TASK-006 | Coerenza UI esistente | 2026-03-29 |
| 7 | Parametro (o overload) **`allowEmptyTabularResult`**: default **`false`** (carico normale + primo file multi-URI + Database); **`true` solo** nel loop di **`appendFromMultipleUris`** per consentire skip file vuoti senza throw | Una sola implementazione del “vuoto tabellare” in ExcelUtils | 2026-03-29 |
| 8 | **Append senza file principale:** prima di qualsiasi lettura URI o `excelData.first()`, verificare che esista una **base griglia valida**; se no → **`loadError` = `R.string.error_main_file_needed`**, uscita immediata, **stato invariato** | Messaggio già previsto nel flusso Excel; niente nuove risorse; coerenza con TASK-008 | 2026-03-29 |
| 9 | **Append con righe valide — codice attuale:** in `appendFromMultipleUris`, il ramo `if (allNewDataRows.isNotEmpty())` contiene **solo un commento**; **non** aggiunge ancora righe a `excelData` né allinea `editableValues` / `completeStates`. Per rispettare criteri **#6–#7** e smoke «append parziale», in **EXECUTION** va **implementato** l’inserimento minimo coerente con la griglia (stesso spirito di `loadFromMultipleUris` + stato editabile), **senza** redesign fuori perimetro | Verifica codice reale 2026-03-29 | 2026-03-29 |

---

## Planning (Claude) — piano integrato

### Perimetro stretto (conferma)

- **Nessun** redesign UI ampio: solo messaggi, assenza di no-op silenziosi, chiarezza copy.
- **Canale feedback:** per **Excel flow** (`ExcelViewModel`) usare **solo** `loadError` — **nessun** toast/snackbar/dialog **nuovo** per questo task. Per **Database import analisi** usare **solo** `UiState.Error` già esistente (vedi sotto).
- **Nessun** refactor architetturale gratuito; **nessuna** modifica a DAO / repository / navigation / integrazioni piattaforma salvo necessità reale documentata in Execution.

### Autostrada unica — no-data / no-table / file vuoto (decisione vincolante)

**Sorgente primaria: `ExcelUtils.readAndAnalyzeExcel` (eventuale overload con stessa logica interna).** Tutte le condizioni sotto sono valutate **qui**, non ripetute in ViewModel come logica parallela.

| Condizione | Dove nasce l’errore | Comportamento |
|------------|---------------------|---------------|
| **`openInputStream(uri) == null`** | **ExcelUtils** | Subito prima/dopo tentativo apertura → `throw IllegalArgumentException(getString(R.string.error_file_empty_or_invalid))` |
| **`readBytes()` vuoto (0 byte)** | **ExcelUtils** | Stesso throw |
| **POI / formato:** file corrotto o non leggibile | **ExcelUtils** (eccezione POI propagata) o wrap locale se necessario | Messaggio utente tramite catena ViewModel esistente (`IOException` / fallback generico) — **nessun** secondo check duplicato nei ViewModel |
| **Dopo parse:** **nessuna riga dati tabellare utilizzabile** (stessa semantica di oggi `validateImportFile`: `normalizedHeader` vuoto **oppure** `dataRows` vuoto), **inclusi** HTML senza `<table>` utile o tabella senza righe | **ExcelUtils**, **solo se** `allowEmptyTabularResult == false` (default) | `throw IllegalArgumentException(getString(R.string.error_file_empty_or_invalid))` |
| **Append:** singolo URI nel loop produce esito vuoto | **ExcelUtils** con **`allowEmptyTabularResult == true`** | **Nessun throw**; triple vuota per quel URI. **Dopo** il loop, **solo `ExcelViewModel.appendFromMultipleUris`**: se somma righe aggregate == 0 → **`loadError`** + **`error_append_no_data_rows`** (nessuna logica di parse duplicata, solo conteggio). |

**Dove non si duplica:** `DatabaseViewModel.validateImportFile` è **solo fallback difensivo**: se una triple vuota arrivasse comunque (regressione o caller futuro), impostare `UiState.Error(error_file_empty_or_invalid)` e uscire — **nessuna** seconda euristica “righe utili”. La logica normale è: **ExcelUtils ha già lanciato** con default `allowEmptyTabularResult=false`.

**Primo file multi-URI (`loadFromMultipleUris`):** usa **`allowEmptyTabularResult=false`**. Se l’eccezione ha message == `getString(error_file_empty_or_invalid)`, **ExcelViewModel** può sostituire il testo mostrato in **`loadError`** con **`getString(error_first_file_empty_or_invalid)`** — è **solo riformulazione UX**, non una seconda ispezione del file.

**Canale di feedback (unico per flusso):**

- **`ExcelViewModel`:** tutti gli errori di load/append di questo task → **`loadError.value`** (stringa finale già localizzata). **Eccezione documentata:** errori durante **apply** import nel DB non sono in scope qui.
- **`DatabaseViewModel`** (smart import foglio prodotti): → **`UiState.Error(...)`** tramite `analysisErrorMessage` / stato esistente. **Nessun** nuovo Toast/Snackbar/Dialog per TASK-006.

### `ImportAnalysis.kt` (livello riga)

Solo `RowImportError` + risorse dedicate; **nessun** path “file intero” duplicato.

### Stringhe e copy (direzione decisa)

| Caso | Scelta planning |
|------|-----------------|
| File vuoto / non leggibile / nessun dato tabellare (livello **file**, allineato a Database) | **Riuso prioritario** `error_file_empty_or_invalid` dove il significato coincide. Per il **primo file** in selezione multipla in `ExcelViewModel`, **riuso prioritario** `error_first_file_empty_or_invalid` (già pensato per “primo file selezionato”). |
| Append: **tutti** i file aggiunti con **zero** righe dati utili complessive | **Obbligo:** nuova stringa **`error_append_no_data_rows`** in **4 lingue**, mostrata tramite **`loadError`**. |
| Append senza base griglia | **`error_main_file_needed`** tramite **`loadError`** (nessuna nuova stringa). |
| Errori **per-riga** imprevisti (ex catch in `ImportAnalyzer`) | **Nuova stringa** dedicata, **senza** `%s`, tono non tecnico, es. `error_import_row_processing_failed` (titolo breve: impossibile elaborare la riga / errore imprevisto sulla riga). **Non** riusare `error_data_analysis_generic` nell’elenco errori riga: parla di “file selezionato” e rende ambigua la UI riga-per-riga. |
| `error_unexpected_parsing` | Valutare deprecazione **in questi tre catch** dopo grep globale; se usata altrove con `%1$s`, **non** rompere — sostituire solo il percorso ImportAnalyzer con la nuova risorsa. |

### Append — decisioni UX (vincolanti)

| Scenario | Comportamento atteso |
|----------|------------------------|
| **Append senza file principale / senza header base** (`excelData` vuoto o non pronto per `first()` come header di confronto) | **Guard all’ingresso** di `appendFromMultipleUris` (prima di IO e prima di `excelData.first()`): impostare **`loadError.value = context.getString(R.string.error_main_file_needed)`**, `return@launch` (o equivalente). **Nessun crash**, nessun `NoSuchElementException` verso l’utente, nessun messaggio generico opaco se evitabile. **Stato griglia invariato.** Risorsa già nelle 4 lingue; già elencata in `knownUserFacingFileMessage` — se si usa eccezione con quel testo, `fileLoadErrorMessage` lo riconosce; **preferenza planning:** assegnazione **diretta** a `loadError` per evitare eccezioni di controllo flusso. |
| Primo file principale vuoto / non valido in `loadFromMultipleUris` | Eccezione da ExcelUtils (`error_file_empty_or_invalid`); in **`loadError`** mostrare **`error_first_file_empty_or_invalid`** (mapping in ViewModel, vedi sopra). |
| Append: **tutti** i file nella lista producono 0 righe | **`loadError`** = **`error_append_no_data_rows`**. **Vietato** uscire senza feedback. **`excelData` e stato griglia invariati** (criterio #7). |
| Append: eccezione (es. header incompatibile) prima di completare | **`loadError`** via `fileLoadErrorMessage`; **stato griglia invariato** — nessuna modifica parziale a `excelData` / righe. |
| Append: almeno un file ha righe > 0 | **Dopo EXECUTION:** persistere le righe nella griglia (oggi il blocco sotto `if (allNewDataRows.isNotEmpty())` è **placeholder** — vedi **Decisione #9**); URI con 0 righe → skip silenzioso (parziale valido). |
| Header incompatibile durante append | Eccezione `error_incompatible_file_structure` — invariato; **stato griglia invariato** se non si è applicato nulla. |

### Colonne mancanti — decisione esplicita (no criterio ambiguo)

- **Non** introdurre fail-fast obbligato sul trio `barcode` / `productName` / `purchasePrice` a livello file: `ensureColumn` e validazione riga restano la fonte di verità per layout “strani” ma con dati.
- Il fail-fast resta vincolato ai casi **“nessun dato utile”** / **file non leggibile** / **append tutto vuoto** come da tabella append.

### Strategia test (concreta) — allineata all’autostrada

**Percorso primario (da verificare nei test):** fallimenti **file-level** per vuoto / no-data / no-table nascono in **`ExcelUtils.readAndAnalyzeExcel`** come **`IllegalArgumentException`** (o eccezioni POI/IO propagate) con messaggio **user-facing** già noto o mappato da `analysisErrorMessage` / `fileLoadErrorMessage`. I test devono dimostrare che **ViewModel + catena esistente** mostrano l’esito atteso quando **questo** percorso scatta — **non** che il sistema si affidi a una triple vuota consegnata al caller.

**Percorso secondario / difensivo:** `DatabaseViewModel.validateImportFile` su triple vuota **senza** eccezione precedente — **solo** regressione o caller anomalo; test **opzionale**, non sostituisce la copertura primaria.

---

**Priorità alta**

1. **`ExcelViewModelTest`** — `loadError` e stato griglia:
   - primo file vuoto / no-data (eccezione o equivalente da monte) → messaggio atteso (`error_first_file_empty_or_invalid` dove previsto dal mapping);
   - append con somma righe 0 → `error_append_no_data_rows`;
   - append senza base griglia → `error_main_file_needed`, nessun crash, stato invariato;
   - append parziale valido → righe aggiunte, nessun falso errore bloccante (**dipende da implementazione §Decisione #9** — finché il placeholder persiste, questo assert può fallire a ragione);
   - (opzionale) eccezione lettura file / messaggio in whitelist `knownUserFacingFileMessage`.

2. **`DatabaseViewModelTest`** — **`startImportAnalysis`** / parse quando **`readAndAnalyzeExcel` lancia** `IllegalArgumentException` con `message == getString(R.string.error_file_empty_or_invalid)` (o altra stringa in whitelist): verificare che **`analysisErrorMessage`** / `UiState.Error` espongano il messaggio corretto **senza** dipendere dal solo `validateImportFile`. Usare mock/stub del layer IO se il test suite lo consente (stesso approccio già usato per altri scenari ViewModel).

3. **`ImportAnalyzerTest`** — errori riga da catch: **nessun** `formatArgs` con testo tecnico; `errorReasonResId` = risorsa dedicata (`error_import_row_processing_failed` o nome finale scelto in Execution).

---

**Priorità bassa (fallback difensivo)**

4. **`DatabaseViewModelTest` (opzionale):** se, per artefatto di test, `parseImportFile` restituisse ancora una **triple vuota senza** eccezione da ExcelUtils, verificare che `validateImportFile` imposti `UiState.Error` con `error_file_empty_or_invalid`. **Non** contare questo come prova principale dell’autostrada.

---

**Nota su `readAndAnalyzeExcel` in isolamento:** **nessun** obbligo di unit test diretto su Uri/ContentResolver se fragile; la copertura primaria resta **ViewModel** + **ImportAnalyzer** come sopra. Helper puri in ExcelUtils testabili senza Uri solo se costo marginale.

### Verifica codice reale (2026-03-29) — coerenza piano

| Elemento | Stato repo | Azione EXECUTION |
|----------|-------------|------------------|
| `readAndAnalyzeExcel(context, uri)` | Nessun parametro `allowEmptyTabularResult`; stream null / byte vuoti → spesso triple vuota **senza** throw | Introdurre parametro + throw `IllegalArgumentException(getString(error_file_empty_or_invalid))` come da autostrada |
| `DatabaseViewModel.validateImportFile` | Esiste; primario su triple vuota oggi | Mantenere **fallback** dopo ExcelUtils |
| `knownUserFacingFileMessage` (entrambi VM) | Include `error_file_empty_or_invalid`, `error_first_file_empty_or_invalid`, `error_main_file_needed`, colonne/struttura | Aggiungere a whitelist **solo se** si introduce nuova IAE user-facing non ancora in set (es. copy finale) |
| `ExcelViewModel.appendFromMultipleUris` | `excelData.first()` senza guard; `allNewDataRows` non appendato alla griglia se non vuoto (solo commento) | Guard `error_main_file_needed` + `loadError`; append righe + stato editabile (**Decisione #9**); `loadError` se tutti zero righe |
| `ImportAnalyzer` catch | `error_unexpected_parsing` + `formatArgs = ex.message` (3 punti) | Nuova risorsa riga senza placeholder tecnico |
| `strings.xml` | **Assenti** `error_append_no_data_rows`, `error_import_row_processing_failed` | Aggiungere 4 lingue in EXECUTION |

### Audit codice (storico sintetico)

- Stream null / bytes vuoti → triple vuota senza eccezione (da correggere in ExcelUtils).
- `appendFromMultipleUris`: silenzio se `allNewDataRows` vuoto; placeholder se non vuoto (**Decisione #9**).
- Tre `catch` in `ImportAnalyzer` con `ex.message` in UI.

### Rischi

- Regressione su file reali al limite dell’euristica → mitigare limitando i fail a “vuoto/illeggibile”, non tocché `analyzeRows` salvo necessità.
- Nuove stringhe → aggiornare tutte e 4 le lingue.

### Smoke manuale minima (post build / lint / test JVM)

Eseguire su device/emulator **dopo** i check automatici; solo percorsi mal coperti dai test.

- [ ] **Primo file** vuoto o senza dati utili → errore **visibile** (`loadError` / UI PreGenerate coerente con oggi).
- [ ] File **corrotto** o non leggibile → messaggio **pulito**, localizzato, **senza** stack trace in UI.
- [ ] **Append:** tutti i file senza righe utili → **`loadError`** con testo dedicato append; **griglia e dati già caricati identici** al tentativo.
- [ ] **Append parziale:** almeno un file con righe → righe aggiunte; **nessun** falso errore bloccante.
- [ ] **Append senza file principale** (griglia non ancora caricata / `excelData` vuoto): **nessun crash**; messaggio **`error_main_file_needed`** visibile via percorso **`loadError`**; stato griglia **invariato**.
- [ ] **ImportAnalysis / errori riga** imprevisti → **nessun** testo tecnico grezzo (es. messaggio POI) nell’elenco errori.

---

## Execution

### Esecuzione — 2026-03-29 (avvio fase)

**Transizione:** **PLANNING → EXECUTION** dopo check finale piano vs codice (file: `ExcelUtils.kt`, `ImportAnalysis.kt`, `ExcelViewModel.kt`, `DatabaseViewModel.kt`, stringhe note, test esistenti).

**Verifica pre-codice (sintesi):** piano allineato al codice su canali `loadError` / `UiState.Error` e whitelist; gap reale su **append non implementato** nel ramo non vuoto e su **firma/parametro** `readAndAnalyzeExcel` — integrati in **Decisione #9** e tabella §Verifica codice reale. Nessuna contraddizione sulle decisioni già fissate (autostrada, no fail-fast colonne, no testo tecnico riga).

**File modificati (questa transizione):** solo `docs/TASKS/TASK-006-…md` e `docs/MASTER-PLAN.md` (tracking).

**Prossimi passi esecutore:** implementare secondo Planning + §Verifica codice reale; log dettagliato nelle righe successive della Execution.

### Esecuzione — 2026-03-29 (implementazione codice)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — aggiunti guard file-level per stream nullo / byte vuoti / esito tabellare vuoto; introdotto `allowEmptyTabularResult` con triple vuota solo per il loop append
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — mapping del primo file vuoto su `error_first_file_empty_or_invalid`; guard append senza base griglia; errore `error_append_no_data_rows`; implementato l’append reale delle righe valide con stato editabile coerente
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — rimosso `ex.message` dagli errori riga; introdotto catch condiviso con risorsa dedicata non tecnica
- `app/src/main/res/values/strings.xml` — aggiunte `error_append_no_data_rows` e `error_import_row_processing_failed`
- `app/src/main/res/values-en/strings.xml` — aggiunte `error_append_no_data_rows` e `error_import_row_processing_failed`
- `app/src/main/res/values-es/strings.xml` — aggiunte `error_append_no_data_rows` e `error_import_row_processing_failed`
- `app/src/main/res/values-zh/strings.xml` — aggiunte `error_append_no_data_rows` e `error_import_row_processing_failed`
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — aggiunti test su file vuoto, workbook vuoto con `allowEmptyTabularResult`, HTML senza tabella
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — aggiunti test su primo file vuoto, append senza base, append tutto vuoto, append header incompatibile, append parziale valido con file vuoto nel batch
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — aggiunto test sulla propagazione di `error_file_empty_or_invalid` da workbook vuoto
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ImportAnalyzerTest.kt` — aggiunti test su errori riga senza testo tecnico grezzo (`analyze` / `analyzeStreaming`)

**Azioni eseguite:**
1. Implementata in `ExcelUtils` l’autostrada file-level del piano: stream nullo e file a 0 byte ora falliscono subito con `IllegalArgumentException(getString(R.string.error_file_empty_or_invalid))`; esiti tabellari vuoti falliscono di default e diventano triple vuota solo con `allowEmptyTabularResult=true`.
2. Implementato in `ExcelViewModel` il mapping UX del primo file vuoto su `error_first_file_empty_or_invalid`, senza introdurre logica parallela di parse nel ViewModel.
3. Sistemato `appendFromMultipleUris`: guard esplicito su base griglia assente (`error_main_file_needed`), skip dei file vuoti nel batch append, errore dedicato su batch tutto vuoto, append reale delle righe valide solo dopo aver validato l’intero batch, nessuna mutazione parziale su fallimento.
4. Rimossi i messaggi tecnici grezzi dagli errori riga in `ImportAnalyzer`; ora i tre catch interessati usano solo `error_import_row_processing_failed` senza `formatArgs`.
5. Aggiunte solo le stringhe strettamente necessarie in 4 lingue; nessun altro cambio copy fuori perimetro.
6. `DatabaseViewModel.kt` **non modificato**: la catena `ExcelUtils -> IllegalArgumentException(message localizzato) -> analysisErrorMessage / UiState.Error` era già coerente con il piano; il comportamento è stato verificato con test dedicato.
7. **Non toccati** DAO, repository, modelli dati, navigation, integrazioni piattaforma, `DatabaseViewModel.kt` (salvo verifica tramite test), né altri moduli fuori scope.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` OK |
| Lint                     | ✅ | `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` OK |
| Warning nuovi            | ✅ | Nessun warning nuovo nei file toccati; osservati warning/deprecazioni Gradle/Compose/Kotlin preesistenti e fuori scope (`DatabaseScreenComponents.kt`, `HistoryScreen.kt`, `FullDbImportStreaming.kt`, configurazione AGP) |
| Coerenza con planning    | ✅ | Perimetro rispettato: `ExcelUtils`, `ExcelViewModel`, `ImportAnalysis`, stringhe minime, test; `DatabaseViewModel` lasciato invariato per scelta coerente con planning |
| Criteri di accettazione  | ⚠️ | Copertura codice/JVM/build completata; smoke manuale device/emulator non eseguito in questa esecuzione |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ExcelUtilsTest' --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest'`
- Test aggiunti/aggiornati: `ExcelUtilsTest` (3 casi nuovi), `ExcelViewModelTest` (5 casi nuovi), `DatabaseViewModelTest` (1 caso nuovo), `ImportAnalyzerTest` (2 casi nuovi)
- Limiti residui: smoke manuale non eseguito per i criteri con componente `M`; nessuna modifica ai test fuori perimetro TASK-006

**Criteri di accettazione — verifica puntuale:**
1. `ESEGUITO` — coperti `stream null / byte vuoti / workbook corrotto / file-level error path` con guard in `ExcelUtils`, test `ExcelUtilsTest` (file vuoto) + test esistenti/aggiornati `ExcelViewModelTest` e `DatabaseViewModelTest` su file invalido/corrotto.
2. `ESEGUITO` — `HTML` senza tabella utile ora ricade su `error_file_empty_or_invalid`; verificato con `ExcelUtilsTest`.
3. `ESEGUITO` — nessun fail-fast globale sulle colonne mancanti; la nuova logica fallisce solo sui casi file-level vuoti/no-data, lasciando invariati `analyzeRows`, `ensureColumn` ed errori riga.
4. `ESEGUITO` — nessun `ex.message` user-visible nei catch per-riga; verificato con `ImportAnalyzerTest` (`analyze` / `analyzeStreaming`).
5. `ESEGUITO` — `loadFromMultipleUris` mostra `error_first_file_empty_or_invalid` se il primo file è vuoto/no-data; verificato con `ExcelViewModelTest`.
6. `ESEGUITO` — append tutto vuoto -> `error_append_no_data_rows`; append parziale valido -> righe aggiunte e file vuoti nel batch skippati; verificato con `ExcelViewModelTest`.
7. `⚠️ NON ESEGUIBILE` — componente statica/JVM verificata (`append` invariato su tutto-vuoto e header incompatibile), ma smoke manuale su device/emulator non eseguito in questa sessione.
8. `ESEGUITO` — logica duplicati / merge quantità / validazione prezzi non modificata; baseline `ImportAnalyzerTest`, `ExcelViewModelTest`, `DatabaseViewModelTest` verde.
9. `ESEGUITO` — `assembleDebug` e `lint` OK; nessun problema nuovo introdotto nel perimetro modificato.
10. `ESEGUITO` — baseline TASK-004 eseguita sui ViewModel coinvolti e sul parsing/import analysis, con nuova copertura esplicita sulla propagazione delle `IllegalArgumentException` localizzate da `ExcelUtils`.
11. `⚠️ NON ESEGUIBILE` — guard append senza base implementato e verificato via `ExcelViewModelTest`; smoke manuale device/emulator non eseguito in questa sessione.

**Incertezze:**
- Smoke manuale non eseguito: restano da confermare su device/emulator i casi UI dei criteri `#7` e `#11`, più la resa finale del messaggio riga in `ImportAnalysisScreen`.

**Handoff notes:**
- Implementazione lato codice completata; vedi §Review e §**BLOCKED** per stato attuale e smoke pendenti.

---

## Review

### Review tecnica — 2026-03-29

**Esito review:**
- Review tecnica positiva sul perimetro TASK-006: non emerse regressioni funzionali in `ExcelUtils.kt`, `ExcelViewModel.kt`, `ImportAnalysis.kt`, stringhe o test già aggiunti.
- La logica implementata resta coerente con il planning: autostrada file-level in `ExcelUtils`, mapping del primo file vuoto in `ExcelViewModel`, append reale con stato invariato sui fallimenti, sanificazione errori riga in `ImportAnalyzer`.

**Problemi trovati nel review:**
1. Copertura test incompleta su un caso esplicitamente richiesto dal task: `openInputStream(uri) == null` in `ExcelUtils.readAndAnalyzeExcel` non era ancora verificato da test dedicato.

**Fix applicati durante il review:**
1. Aggiornato `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` con test diretto del caso `openInputStream == null` tramite `ContentResolver` mockato.
2. Rieseguiti tutti i check automatici del task dopo il micro-fix di review.

**Check review rieseguiti:**
- `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ExcelUtilsTest' --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest'` → ✅
- `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → ✅
- `env JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → ✅

**Esito stato task (pre-BLOCKED):**
- Review tecnica completata con esito positivo; micro-fix test applicato; check automatici verdi.
- **Non** `DONE`: mancano smoke manuali / criteri **M**.

---

## BLOCKED — sospensione (2026-03-29)

**Motivo del blocco:** **non** dipende da problemi tecnici del codice né da fallimenti di build/lint/test JVM. **EXECUTION implementativa completata**; **review tecnica positiva**; **test automatici OK**. Il task è **sospeso** perché restano **smoke manuali su device/emulator** e **criteri di accettazione con componente M** (`#7`, `#11`) **senza evidenza eseguita** — allineamento a **TASK-011** (BLOCKED per smoke pendenti).

**Sblocco verso `DONE`:** eseguire la checklist smoke sotto, documentare esito (Execution o sezione dedicata), poi **REVIEW** finale / conferma utente. **Nessun** nuovo task attivato automaticamente.

### Smoke manuali ancora pendenti (checklist minima)

- [ ] **Primo file** vuoto o senza dati utili → errore visibile (`loadError` / flusso PreGenerate coerente).
- [ ] **Append tutto vuoto** → `loadError` con messaggio dedicato append; **griglia invariata**.
- [ ] **Append parziale valido** → righe aggiunte correttamente; nessun falso errore bloccante.
- [ ] **Append senza base griglia** → `error_main_file_needed`, nessun crash, stato invariato.
- [ ] **Resa UI** messaggi errore **per riga** in ImportAnalysis (chiarezza, 4 lingue, **nessun** testo tecnico grezzo).
- [ ] (Opzionale ma consigliato) File **corrotto** / non leggibile → messaggio pulito, localizzato, senza stack in UI.

---

## Chiusura

_(Vuoto — nessun `DONE` finché BLOCKED non è risolto con smoke + conferma.)_

---

## Handoff

- Task in **`BLOCKED`**: pronto per sblocco **solo** dopo smoke manuali documentati.
- Non promuovere a `DONE` senza evidenza sui criteri **M** e sulla checklist §BLOCKED.
- Dopo smoke positivi: aggiornare questo file, poi passaggio a **REVIEW** / conferma utente come da governance.
