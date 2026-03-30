# TASK-018 — Eliminare double file staging nel full-import

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-018                   |
| Stato                | **REVIEW**                 |
| Priorità             | BASSA                      |
| Area                 | Import / Performance       |
| Creato               | 2026-03-29                 |
| Ultimo aggiornamento | 2026-03-29 — review completata: nessun bug bloccante nel perimetro; aggiunto test mirato ramo `SingleSheet`; task resta in **REVIEW**. |
| Tracking `MASTER-PLAN` | **`ACTIVE`** — fase **`REVIEW`**. File: `docs/TASKS/TASK-018-eliminare-double-file-staging-full-import.md`. |

---

## Dipendenze

- **TASK-017** (`DONE`) — full-import streaming senza `XSSFWorkbook` DOM; questo task è un follow-up **solo IO/staging**, non OOM.

### Distinzione da altri task

| Task | Rapporto |
|------|----------|
| **TASK-021** | **`DONE`** — export full/partial DB (SXSSF, dialog, fetch selettivo). **Non** riaprire; **non** è lo stesso perimetro di TASK-018. |
| **TASK-019** | Backlog — i18n messaggi PriceHistory nel full-import; **fuori scope** salvo coordinamento esplicito. |

---

## Scopo

Ridurre **IO e tempo** sul percorso **smart import → full database**: oggi il file XLSX viene **copiato due volte** in cache (`stageWorkbookToCache`): una volta in **`inspectWorkbookSheetNames`** (via `detectImportWorkbookRoute`) e di nuovo in **`withWorkbookReader`** (via `analyzeFullDbImportStreaming`). Obiettivo: **una sola copia** (o equivalente semanticamente sicuro) per l’intera sequenza detection + analisi full-import, **senza** cambiare il comportamento funzionale dell’import.

---

## Contesto (codice attuale — lettura planning 2026-03-29)

- `DatabaseViewModel.startSmartImport` → oggi `detectImportWorkbookRoute` poi eventualmente `startFullDbImport` → `analyzeFullDbImportStreaming` (doppio staging). **Planning:** una funzione orchestratrice **`internal`** in **`FullDbImportStreaming.kt`** che restituisce **`sealed`** `SingleSheet` | `FullDatabaseAnalyzed(result)`; dispatch con **`when`** nel ViewModel; apply post-conferma sul **`Uri`** originale.
- `FullDbImportStreaming.kt`:
  - `detectImportWorkbookRoute`: se sembra XLSX, `inspectWorkbookSheetNames(context, uri)` che fa **`stageWorkbookToCache`** + `OPCPackage.open` + lettura nomi foglio.
  - `analyzeFullDbImportStreaming`: **`withWorkbookReader(context, uri)`** → di nuovo **`stageWorkbookToCache`** + streaming fogli.
- I test JVM `FullDbExportImportRoundTripTest` invocano `detectImportWorkbookRoute` e `analyzeFullDbImportStreaming` con `Uri.fromFile(...)`: devono restare verdi dopo l’execution.

### Evidenza codice — review vs repo (2026-03-29)

| Verifica | Esito |
|----------|--------|
| **Doppio staging smart → full** | Confermato solo se `looksLikeXlsxWorkbook` è true **e** route è `FULL_DATABASE`: (1) `detectImportWorkbookRoute` → `inspectWorkbookSheetNames` → `stageWorkbookToCache` (righe ~396–414); (2) `startFullDbImport` → `analyzeFullDbImportStreaming` → `withWorkbookReader` → `stageWorkbookToCache` (righe ~351–366, ~80–85). |
| **`stageWorkbookToCache`** | Funzione **`private`**; **unici** call site nel file: `inspectWorkbookSheetNames` e `withWorkbookReader`. |
| **Percorso non-XLSX** | `detectImportWorkbookRoute` ritorna `SINGLE_SHEET` **senza** `stageWorkbookToCache` (early return riga ~64–66). |
| **Probe firma XLSX** | `looksLikeXlsxWorkbook` apre stream, legge **4 byte**, nessuna copia in cache — allineato all’anti-scope TASK-018. |
| **Terza copia (fuori obiettivo TASK-018)** | `applyFullDbPriceHistoryStreaming` usa `withWorkbookReader` → altro `stageWorkbookToCache` su `pendingFullImportUri` post-conferma (`DatabaseViewModel.applyPendingPriceHistory` ~580). Resta **accettato**; non ottimizzare in TASK-018. |
| **`startFullDbImport`** | In codice produzione chiamato **solo** da `startSmartImport` (riga ~315). Rifattorizzazione del ramo FULL può consolidare orchestrazione senza nuovi entry point UI. |
| **Call site pubblici `util`** | `detectImportWorkbookRoute`: `DatabaseViewModel`, `FullDbExportImportRoundTripTest` (2 test). `analyzeFullDbImportStreaming`: `DatabaseViewModel`, `FullDbExportImportRoundTripTest`. `applyFullDbPriceHistoryStreaming`: `DatabaseViewModel`, `FullDbExportImportRoundTripTest`. Nessun altro `.kt` in `app/src/main`. |
| **`getAllProducts`** | Oggi in `startFullDbImport` **dopo** il branch smart, **solo** per full import (righe ~778–780) — coerente con planning «fetch solo dopo route FULL». |
| **Coroutine** | `startSmartImport` lancia una coroutine che chiama `startFullDbImport`, il quale lancia **un’altra** `viewModelScope.launch` (nested). In EXECUTION: preservare **mutex** / `finally` unlock e comportamento history log; eventuale appiattimento **locale** solo se non cambia semantica. |

---

## Non incluso

- Modifiche al **formato** XLSX, ai nomi foglio, alla semantica di `ImportWorkbookRoute`, al flusso **single-sheet** (`startImportAnalysis` / `readAndAnalyzeExcel`) salvo incidenza tecnica minima documentata.
- **TASK-021** (export), **TASK-019** (stringhe PriceHistory).
- **Redesign UI/UX:** nessun cambiamento visuale o di flussi schermata richiesto; task **tecnico, locale, a basso rischio** (solo IO/staging vicino a POI).
- Cambi a DAO/entity/schema **non** motivati da questo staging.

---

## File potenzialmente coinvolti (ispezione / modifica in execution)

| File | Ruolo |
|------|--------|
| `app/src/main/java/.../util/FullDbImportStreaming.kt` | **Perimetro primario:** orchestratore **`internal suspend fun …`** (un solo nome da fissare in Execution) che compone detect→analyze su **un solo** `File` staged; helper **`private`** di supporto (`withStagedWorkbook`, overload su `File`); inspect route + analyze full DB + cleanup unico. |
| `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` | **Wiring:** `startSmartImport` (e se necessario `startFullDbImport`) chiama l’orchestratore **`internal`** nel modulo app; **`when`** sul **`sealed`** per dispatch; **nessuna** ownership del temp (path/delete/`finally`) nel ViewModel. |
| `app/src/test/java/.../util/FullDbExportImportRoundTripTest.kt` | Baseline funzionale round-trip / routing (resta obbligatoria). |
| Nuovo o esteso test JVM (stesso package `util` o test dedicato) | Verifica mirata **single staging** sul path smart→full (vedi Planning § Test). |
| Call site | Verificati 2026-03-29: vedi tabella «Evidenza codice» (nessun altro `.kt` main oltre ViewModel + test round-trip). |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Sul percorso **full-import** da URI content, il workbook non viene copiato in cache **due volte** per la stessa operazione logica (detection immediatamente seguita da analisi). | B + S (review diff / log o conteggio chiamate `stageWorkbookToCache` nel path) | — |
| 2 | `ImportWorkbookRoute` invariato: stesso criterio foglio **Products** (normalizzazione header come oggi). | B + test JVM | — |
| 3 | Dopo analisi full-import, **apply** (incluso **PriceHistory** post-conferma) continua a usare il **`Uri` originale** (`pendingFullImportUri` / `fullImportUri` come oggi): **nessun** obbligo di mantenere il file staged oltre la finestra detect→analyze. | B + `FullDbExportImportRoundTripTest` + S (review wiring apply) | — |
| 4 | Percorso **single-sheet** dopo detection non peggiorato in modo misurabile (nessuna doppia copia **aggiuntiva** ingiustificata). | S + B | — |
| 5 | `assembleDebug` OK; `lint` senza nuovi warning nel perimetro toccato. | B / S | — |
| 6 | **Cleanup lifecycle:** il file temporaneo creato per lo staging viene **sempre** eliminato in **tutti** i rami: percorso **full database**, **single sheet** (XLSX ispezionato poi classificato single), **errore**, **cancellation**; **ownership e delete in un solo punto** documentato nel codice (helper `withStagedWorkbook` / equivalente). Nessun leak di temp file in cache. | B + S + test JVM mirato o verifica strutturata sui rami | — |
| 7 | Nel path **smart import → full import** (detect immediatamente seguito da analyze), **non** avvengono **due** copie da `stageWorkbookToCache` non necessarie: verifica con **test mirato** o **seam** testabile (conteggio hook su funzione di copy interna, o API di test solo `test`/debug se accettata). `FullDbExportImportRoundTripTest` **non** sostituisce questo criterio. | JVM test dedicato + B | — |
| 8 | Baseline **TASK-004** solo se l’execution tocca **realmente** `DatabaseViewModel` o logica import oltre `FullDbImportStreaming.kt`: test rilevanti documentati in Execution. Se il diff resta confinato al file `util`, preferire test mirati su staging + round-trip. | JVM test | — |

Legenda: B=Build, S=Static/review, M=Manuale, E=Emulatore.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task **unico** `ACTIVE`; transizione **PLANNING → EXECUTION** dopo review planning vs codice (2026-03-29) | Review + utente | 2026-03-29 |
| 2 | Orchestratore **`internal`** + **`sealed`** `SingleSheet` / `FullDatabaseAnalyzed(result)`; ViewModel: chiamata orchestratore + `when` dispatch; **nessun** temp nel ViewModel | Allineato a rifinitura planning 2026-03-29 | 2026-03-29 |
| 3 | **Finestra staging:** riuso del temp **solo** detect→analyze; apply post-UI resta su **Uri** originale | Revisione planning utente 2026-03-29 | 2026-03-29 |

---

## Planning (Claude)

### Obiettivo tecnico

Eliminare la **doppia invocazione** di `stageWorkbookToCache` nella catena **detect route (full)** → **analyze streaming**, mantenendo `OPCPackage` / `XSSFReader` su **file locale stabile**. **Scelta fissa:** un orchestratore dichiarato **`internal`** in `FullDbImportStreaming.kt`, **chiamabile da `DatabaseViewModel`** (stesso modulo Android), che incapsula **una finestra unica** di staging — il ViewModel **non** orchestra due chiamate separate sullo stesso URI senza quella finestra. Ownership del temp e delete restano **nel file `util`**; **diff minimo**; eccezioni solo per impossibilità tecnica documentata in Execution.

### File Android da ispezionare (prima di EXECUTION)

1. **`FullDbImportStreaming.kt` (priorità assoluta)** — `stageWorkbookToCache`, `inspectWorkbookSheetNames`, `withWorkbookReader`, `looksLikeXlsxWorkbook`; firme pubbliche `detectImportWorkbookRoute`, `analyzeFullDbImportStreaming`, `applyFullDbPriceHistoryStreaming`; punti in cui introdurre **`withStagedWorkbook(context, uri) { stagedFile -> … }`** o equivalente con delete garantito.
2. **`DatabaseViewModel.kt` (lettura / wiring)** — `startSmartImport`, `startFullDbImport`, `pendingFullImportUri`: obiettivo che il percorso smart→full invochi **il compositore nel `util`** (una sola «transazione» di staging) invece di `detectImportWorkbookRoute` + `analyzeFullDbImportStreaming` come **due** chiamate separate sullo stesso URI; **nessuna** logica di temp path / delete nel ViewModel.
3. **Test** — `FullDbExportImportRoundTripTest.kt`; grep call site di `detectImportWorkbookRoute` / `analyzeFullDbImportStreaming`; progetto del **nuovo test mirato** (sotto).

### Orchestratore: visibilità **`internal`** (scelta unica)

Nel file `FullDbImportStreaming.kt`:

- **Orchestratore:** una funzione (o piccolo set coeso) marcata **`internal`** — **non** `private` (il ViewModel nel modulo `app` deve poterla chiamare); **non** pubblica.
- **Helper di supporto** (`withStagedWorkbook`, overload su `File`, ecc.): **`private`** nello stesso file, salvo necessità di test documentata.
- **`DatabaseViewModel`:** chiama **solo** l’orchestratore `internal` per il percorso che oggi fa detect+analyze con doppio staging; **nessun** path/delete/`File` temporaneo nel ViewModel.

Sequenza garantita dall’orchestratore per il percorso che oggi implica **due** staging:

1. **Una sola** copia in cache (`stageWorkbookToCache` o astrazione unica di copy byte→file).
2. **Inspect / routing** nomi foglio sullo **stesso** `File` staged (semantica equivalente a oggi per `ImportWorkbookRoute`).
3. Se **FULL_DATABASE**, **analyze full DB** (streaming) sullo **stesso** `File` **senza** seconda copia.
4. **Cleanup** del temp in **un solo punto** (`finally` / `use` nell’helper usato dall’orchestratore).

**API pubbliche esistenti** (`detectImportWorkbookRoute`, `analyzeFullDbImportStreaming`, `applyFullDbPriceHistoryStreaming`): **mantenerle stabili** dove possibile — implementazioni che **delegano** all’orchestratore `internal` o ai `private` helper **senza** duplicare staging; call site esterni (test, eventuali altri) restano validi. **Nessuna nuova API pubblica combinata** salvo stretta necessità già disciplinata in § «API pubblica e stabilità».

### Contratto di ritorno: **`sealed`** fisso per il dispatch nel ViewModel

**Scelta fissa** (nomi esatti adattabili in Execution solo per collisioni di simboli, vincolo di forma invariato):

- **`sealed class SmartImportWorkbookOutcome`** (o nome strettamente analogo) definita in `FullDbImportStreaming.kt` con **`internal`** (o `public` se i test JVM nel modulo devono referenziarla senza workaround — preferenza: **`internal`** + test nello stesso modulo che referenziano il tipo).
- Sottotipi:
  - **`SingleSheet`** — nessun payload aggiuntivo obbligatorio (il ViewModel procede con `startImportAnalysis(uri)` come oggi).
  - **`FullDatabaseAnalyzed(val result: FullDbImportStreamingResult)`** — analisi full completata nell’unica finestra di staging; il ViewModel applica lo stesso wiring di stato che oggi segue `analyzeFullDbImportStreaming`.

**Obiettivo:** un **solo** `when (outcome)` in `startSmartImport` (o equivalente) come **punto unico di dispatch** dello smart import, senza ambiguità in EXECUTION.

**Nota:** `FullDbImportStreamingResult` resta il tipo già usato per l’esito dell’analyze full; non introdurre un duplicato semantico del payload salvo motivazione in Execution.

### Anti-scope-creep: `looksLikeXlsxWorkbook()`

- **Obiettivo di TASK-018:** eliminare la **doppia copia in cache** tra **detect** (ispezione nomi foglio) e **analyze full DB** sul percorso smart→full.
- **Fuori scope deliberato:** ottimizzare o unificare **ogni** altra lettura preliminare dall’URI, in particolare il **probing della signature ZIP/XLSX** in `looksLikeXlsxWorkbook()` (pochi byte in stream), salvo **necessità tecnica minima** emersa in EXECUTION (es. impossibilità a separare i flussi senza toccarla — da documentare).
- Motivo: mantenere il task **locale**, **a basso rischio** e senza allargare il perimetro a «deduplicare tutto ciò che legge il file».

### API pubblica e stabilità

- **Obiettivo:** cambiare **il meno possibile** le firme pubbliche attuali (`detectImportWorkbookRoute`, `analyzeFullDbImportStreaming`, `applyFullDbPriceHistoryStreaming`).
- **Orchestratore:** **`internal`** + **`sealed`** `SingleSheet` / `FullDatabaseAnalyzed` come sopra; helper **`private`** nel `util`.
- **Nuova API pubblica:** **evitare**; il ViewModel usa l’orchestratore **`internal`**, non una nuova superficie pubblica per il composito detect+analyze.

### Perimetro temporale del file staged (vincolo)

| Regola | Dettaglio |
|--------|-----------|
| **Riuso consentito** | Solo nella finestra **detect route (full)** → **analyze streaming** (stessa azione utente / stesso avvio smart import verso full DB). |
| **Fuori scope esplicito** | **Non** mantenere il temp file «vivo» fino alla fase di **apply dopo conferma utente** (preview/dialog). |
| **Apply successivo** | `applyFullDbPriceHistoryStreaming` e apply principale devono continuare a usare il **`Uri` originale** (`pendingFullImportUri` come oggi), con **nuova** lettura/staging lato POI se il codice apply lo richiede — **non** è obiettivo di TASK-018 ottimizzare quel secondo passaggio. |

### Cosa NON deve cambiare a livello funzionale

- Classificazione **full DB vs single sheet** (presenza foglio **Products** normalizzato).
- Output di `analyzeFullDbImportStreaming` (struttura `FullDbImportStreamingResult`, analisi Products, flag PriceHistory, conteggi riga per log history).
- Messaggi errore utente e comportamento mutex import / history log esistenti.
- Round-trip export→import documentato in **TASK-007** / test JVM associati (stessa semantica dati).
- **Semantica post-UI:** apply da **`Uri` originale**; **nessun** requisito di redesign UI/UX (nessun cambiamento visuale necessario).

### UI/UX

- Task **puramente tecnico** (IO/staging). **Nessun** abbellimento UI né ottimizzazione UX di schermata in **TASK-018** (coerenza con `Non incluso` e basso rischio).
- Miglioramenti **visibili** all’utente su Database (testi, progress, dialog import) restano nel perimetro **TASK-015** (UX modernization DatabaseScreen) o in task dedicato; **non** mescolare qui per evitare scope creep e review incrociate.

### Chiarimenti operativi — ordine `getAllProducts` e contratto orchestratore

**Problema da risolvere in planning:** oggi `startFullDbImport` carica i prodotti **dopo** che `startSmartImport` ha già chiamato `detectImportWorkbookRoute` (con eventuale staging). L’orchestratore che unifica **detect + analyze** deve ricevere **`currentDbProducts`** (e `repository`) **al momento dell’analyze**, senza introdurre un secondo staging.

**Pattern consigliato (da implementare in EXECUTION, firme indicative):**

1. **`looksLikeXlsxWorkbook`**: resta **prima** dello staging completo (solo probe; fuori scope ottimizzarlo salvo nota anti-scope-creep).
2. Se **non** sembra XLSX → comportamento uguale a oggi (`SINGLE_SHEET` senza `stageWorkbookToCache` per questo path).
3. Se sembra XLSX → entrare in **`withStagedWorkbook`** (una copia):
   - leggere nomi foglio sullo **stesso** `File`;
   - se route **`SINGLE_SHEET`** → uscire, **cleanup** del temp, delegare a `startImportAnalysis(uri)` come oggi;
   - se route **`FULL_DATABASE`** → **solo a questo punto** ottenere `currentDbProducts` (es. **lambda/callback `suspend` passata dall’orchestratore** invocata dal ViewModel, oppure parametro fornito dopo fetch nel ViewModel ma **prima** della chiamata che esegue l’analyze sullo staged file — l’importante è: **analyze sullo stesso `File` senza uscire** dal blocco che ha fatto l’unico staging);
   - eseguire l’analyze streaming equivalente a `analyzeFullDbImportStreaming` sullo **stesso** file;
   - **cleanup** nel `finally` dello stesso helper.

**Cosa evitare:** fetch prodotti **prima** dello staging per ogni XLSX (sprecherebbe I/O su file single-sheet classificati dopo inspect). Il fetch deve essere **condizionato** alla route **`FULL_DATABASE`**.

**Esito verso il ViewModel:** **`sealed`** con **`SingleSheet`** e **`FullDatabaseAnalyzed(result: FullDbImportStreamingResult)`** (vedi § «Contratto di ritorno»); `startSmartImport` usa un **solo** `when` per il dispatch.

### Checklist pre-implementazione (stato post-review 2026-03-29)

- [x] Grep call site: solo `DatabaseViewModel` + `FullDbExportImportRoundTripTest` per le tre API pubbliche rilevanti (vedi «Evidenza codice»).
- [x] Apply post-analisi: `applyPendingPriceHistory` usa **`Uri`** (`pendingFullImportUri`), non path temp analisi.
- [ ] In implementazione: nome orchestratore `internal` + `sealed` (`SmartImportWorkbookOutcome` / `SingleSheet` / `FullDatabaseAnalyzed`) — documentare in Execution se rinomina per collisioni.
- [ ] Strategia test single-staging: `internal` copy + spy **oppure** motivazione + review call graph (criterio accettazione #7).

### Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Temp file cancellato troppo presto tra detect e analyze | Ownership in **un solo helper** nel file `util`; stessa finestra transazione logica. |
| Leak su cancellation/exception su coroutine | `finally` / `use` sul temp nel helper; verificare ramo **single-sheet** dopo staging per ispezione nomi. |
| Apply che dipende da path temporaneo invece che da `Uri` | **Vietato** da perimetro: apply resta su `Uri` originale. |
| Test JVM `Uri.fromFile` vs content `Uri` | Round-trip + test mirato; smoke SAF opzionale ma utile. |
| Tocco a `DatabaseViewModel` | **Atteso:** solo wiring (chiamata orchestratore `internal` + `when` sul sealed); **vietata** ownership del temp. Se oltre il wiring, baseline TASK-004 mirata e motivazione in Execution. |

### Test / check dopo l’eventuale EXECUTION (prima di REVIEW)

**Funzionale (baseline obbligatoria, non sufficiente da sola per il doppio staging):**

- `./gradlew :app:test --tests "com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest"` — **sempre** verde; conferma routing + apply da `Uri` invariati a livello comportamentale.

**Verifica doppio staging (obbligatoria in aggiunta):**

- Introdurre almeno **un test JVM mirato** che eserciti il percorso **smart → full** (o la funzione interna/seam esposta come `internal` + `@VisibleForTesting` solo se strettamente necessario) e asserisca che **`stageWorkbookToCache` (o la funzione unica di copy byte-to-file usata per lo staging)** venga invocata **una sola volta** per quell’operazione — es.:
  - estrarre la logica di copy in una funzione **`internal`** `copyUriToStagingForPoi(...)` e sostituire le chiamate duplicate con quella, poi **mock/spy** in test; oppure
  - contatore / flag di test dietro interfaccia iniettabile **solo in `test` source set** se il team accetta una seam minima.
- In assenza di mock: **review obbligatoria** del call graph + **commento nel task Execution** che spiega perché il test strutturale non è applicabile (da evitare salvo motivazione forte).

**Build / statici:**

- `./gradlew :app:assembleDebug`
- `./gradlew :app:lint` (annotare warning preesistenti fuori scope)

**Manuale (opzionale):** smart import full DB da file picker — nessun cambiamento UI atteso.

**TASK-004:** eseguire suite mirata **solo** se il diff tocca `DatabaseViewModel` o repository collegati; altrimenti documentare «N/A perimetro confinato a `FullDbImportStreaming.kt`».

### Preferenze, alternative, criterio di scelta

**Preferenze nette (ordine di priorità):**

1. **Orchestratore `internal`** in `FullDbImportStreaming.kt` + **`sealed`** `SingleSheet` / `FullDatabaseAnalyzed(result)`; **una** copia, **un** cleanup nel `util`.
2. **ViewModel:** una chiamata all’orchestratore `internal` + **`when`** sul sealed; **nessuna** nuova API pubblica per il composito; API pubbliche esistenti **stabili** con implementazioni che delegano senza doppio staging.
3. **Nessuna** ownership del temp nel **`DatabaseViewModel`**.

| Preferenza (default) | Alternativa | Quando scegliere l’alternativa |
|----------------------|-------------|--------------------------------|
| Orchestratore **`internal`** + helper **`private`** + sealed **`SingleSheet` / `FullDatabaseAnalyzed`** | — | **Nessuna** alternativa prevista per visibilità/contratto; solo il nome del `sealed` può variare se collisione (documentare). |
| Test con **seam** `internal` / spy sulla funzione di copy | Solo review statica del call graph | Solo con motivazione registrata se la seam non è accettabile (sforare il preferibile). |

---

## Execution

### Esecuzione — 2026-03-29 (avvio — solo review documentale)

**Azioni (nessuna modifica al codice applicativo in questo passaggio):**

1. Letti e incrociati: `FullDbImportStreaming.kt`, `DatabaseViewModel.kt` (`startSmartImport`, `startFullDbImport`, `applyPendingPriceHistory`), `FullDbExportImportRoundTripTest.kt`; grep su `detectImportWorkbookRoute`, `analyzeFullDbImportStreaming`, `applyFullDbPriceHistoryStreaming`, `stageWorkbookToCache`, `looksLikeXlsxWorkbook`.
2. Confermato: doppio `stageWorkbookToCache` sul percorso **XLSX + FULL_DATABASE** come descritto nel planning; `stageWorkbookToCache` ha esattamente due call site privati; terzo staging su apply PriceHistory documentato come fuori scope.
3. Planning integrato con tabella **Evidenza codice** e checklist aggiornata.
4. Stato task: **`PLANNING` → `EXECUTION`**; implementazione Kotlin **da eseguire nei commit successivi** secondo planning (orchestratore `internal`, sealed outcome, test mirato).

**File modificati (solo documentazione):** `docs/TASKS/TASK-018-eliminare-double-file-staging-full-import.md`, `docs/MASTER-PLAN.md`.

**Check obbligatori (AGENTS):** N/A per questo sotto-passo solo doc — build/lint/test alla chiusura dell’implementazione reale.

### Esecuzione — 2026-03-29 (implementazione Kotlin + verifiche)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/FullDbImportStreaming.kt` — introdotto orchestratore `internal` `analyzeSmartImportWorkbook`, outcome sealed `SmartImportWorkbookOutcome`, riuso del singolo file staged tra detect→analyze, helper `withStagedWorkbook` con cleanup unico e seam `internal` per conteggio staging nei test.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — `startSmartImport` ridotto a wiring + dispatch `when` sull’outcome sealed; `getAllProducts()` eseguito solo dopo route `FULL_DATABASE`; `pendingFullImportUri` e apply post-conferma lasciati invariati.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — aggiunto test JVM mirato `SMART-FULL stages workbook once and cleans temp after analyze`.
- `docs/TASKS/TASK-018-eliminare-double-file-staging-full-import.md` — log execution, verifiche, handoff.
- `docs/MASTER-PLAN.md` — avanzamento fase task da `EXECUTION` a `REVIEW`.

**Azioni eseguite:**
1. Centralizzato lo staging POI in `withStagedWorkbook(context, uri)`, lasciando al layer `util` l’unica ownership del file temporaneo e del `delete()` in `finally`.
2. Introdotto overload privati su `File` per inspect route e analyze full DB, così detect e analyze condividono lo stesso workbook staged nella finestra smart import → full import.
3. Mantenute stabili le API pubbliche esistenti (`detectImportWorkbookRoute`, `analyzeFullDbImportStreaming`, `applyFullDbPriceHistoryStreaming`); il nuovo percorso composito è `internal`.
4. Aggiornato `DatabaseViewModel.startSmartImport` a usare un solo `when (outcome)` con `SingleSheet` / `FullDatabaseAnalyzed(result)`, senza ownership di temp file nel ViewModel.
5. Spostato il fetch `repository.getAllProducts()` dentro la callback lazy passata all’orchestratore, invocata solo dopo route `FULL_DATABASE`.
6. Lasciato invariato l’apply post-conferma sul `Uri` originale tramite `pendingFullImportUri` e `applyFullDbPriceHistoryStreaming(...)`.
7. Aggiunto un test JVM che conta un solo staging nel path smart→full e verifica il cleanup del temp file a fine analisi.

**Decisioni implementative finali:**
- Orchestratore scelto: `internal suspend fun analyzeSmartImportWorkbook(...)`.
- Shape finale del dispatch: `SmartImportWorkbookOutcome.SingleSheet` / `SmartImportWorkbookOutcome.FullDatabaseAnalyzed(result)`.
- Punto unico di cleanup: `withStagedWorkbook(...)`.
- Seam di test: `FullDbImportStreamingTestHooks.onWorkbookStaged`, `internal` e usato solo nei test JVM del modulo.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME` puntato al JBR di Android Studio; `./gradlew :app:assembleDebug` OK |
| Lint                     | ✅ | `./gradlew :app:lint` OK |
| Warning nuovi            | ✅ | nessun warning Kotlin/lint nuovo nel perimetro toccato; restano solo warning/deprecations Gradle/AGP preesistenti e fuori scope |
| Coerenza con planning    | ✅ | orchestratore `internal`, sealed outcome, fetch DB solo su route FULL, apply su `Uri` originale, cleanup unico nel layer `util` |
| Criteri di accettazione  | ✅ | tutti verificati con evidenza sotto |

**Criteri di accettazione verificati:**
1. **#1 — ESEGUITO:** il path smart→full usa una sola finestra `withStagedWorkbook`; test `SMART-FULL stages workbook once and cleans temp after analyze` verifica una sola copia.
2. **#2 — ESEGUITO:** il criterio di route resta la presenza del foglio `Products` normalizzato; `detectImportWorkbookRoute(stagedFile)` conserva la stessa logica, e `FullDbExportImportRoundTripTest` resta verde.
3. **#3 — ESEGUITO:** `pendingFullImportUri` e `applyFullDbPriceHistoryStreaming(context, fullImportUri, repository)` restano invariati in `DatabaseViewModel`.
4. **#4 — ESEGUITO:** il path single-sheet non riceve staging aggiuntivi; i non-XLSX restano senza staging, gli XLSX single-sheet fanno solo l’ispezione necessaria con cleanup unico.
5. **#5 — ESEGUITO:** `:app:assembleDebug` OK e `:app:lint` OK.
6. **#6 — ESEGUITO:** il delete del temp è centralizzato in `withStagedWorkbook(...){...} finally { stagedFile.delete() }`; il test mirato verifica anche il cleanup sul ramo smart→full.
7. **#7 — ESEGUITO:** il test `SMART-FULL...` verifica 1 sola invocazione di staging e 1 sola chiamata a `loadCurrentDbProducts()`.
8. **#8 — ESEGUITO:** baseline `DatabaseViewModelTest` eseguita in run isolato perché il file `DatabaseViewModel.kt` è stato toccato per il wiring del dispatch.

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - `./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest"`
  - `./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"`
- Test aggiunti/aggiornati:
  - aggiunto `SMART-FULL stages workbook once and cleans temp after analyze` in `FullDbExportImportRoundTripTest`
- Limiti residui:
  - una run combinata con doppio filtro `--tests` nello stesso comando ha mostrato `AttachNotSupportedException` in `DatabaseViewModelTest`; le esecuzioni isolate richieste sopra sono verdi.

**Incertezze:**
- Nessuna sul perimetro implementato.

**Handoff notes:**
- TASK-018 è pronto per **REVIEW**.
- Il terzo staging lato apply `PriceHistory` resta deliberatamente fuori scope, come da planning e criterio #3.

---

## Review

### Review — 2026-03-29

**Revisore:** Codex

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Eliminato il doppio staging smart import → full import | ✅ | `analyzeSmartImportWorkbook(...)` usa una sola finestra `withStagedWorkbook(...)`; review call graph + test `SMART-FULL...` |
| 2 | `ImportWorkbookRoute` invariato | ✅ | stessa logica di rilevamento foglio `Products`; round-trip verde |
| 3 | Apply post-conferma continua a usare `Uri` originale | ✅ | `pendingFullImportUri` + `applyFullDbPriceHistoryStreaming(...)` invariati |
| 4 | Single-sheet non peggiorato | ✅ | aggiunto test review `SMART-SINGLE skips db fetch and cleans temp after route inspect` |
| 5 | Build/lint verdi nel perimetro | ✅ | verifiche execution confermate; nessuna modifica codice app nel review |
| 6 | Cleanup unico del temp nei rami rilevanti | ✅ | `withStagedWorkbook(...)` mantiene il `finally`; full path e single-sheet coperti da test |
| 7 | Test mirato single staging presente e sensato | ✅ | test `SMART-FULL...` + rinforzo review su ramo single-sheet |
| 8 | Baseline TASK-004 coerente col tocco ViewModel | ✅ | esecuzione precedente confermata; nessun fix review nel ViewModel |

**Problemi trovati:**
- Nessun bug o regressione bloccante nel perimetro del task.
- Gap minore di copertura: mancava un test diretto sul ramo XLSX `SingleSheet` per verificare che `loadCurrentDbProducts()` non partisse fuori route `FULL_DATABASE` e che il temp venisse comunque ripulito.

**Fix applicati in review:**
- Aggiunto in `FullDbExportImportRoundTripTest` il test `SMART-SINGLE skips db fetch and cleans temp after route inspect`.
- Aggiunto helper locale di test per creare un workbook single-sheet minimale.

**Test rieseguiti:**
- `./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest"` ✅

**Rischi residui:**
- Nessun rischio bloccante emerso.
- Cleanup su rami `error` / `cancellation` resta verificato soprattutto per struttura (`withStagedWorkbook(...){...} finally { stagedFile.delete() }`) più che con un test JVM dedicato.

**Verdetto:** APPROVED

---

## Chiusura / Handoff

- Stato corrente: **REVIEW**.
- Review attesa su coerenza del wiring minimo in `DatabaseViewModel.startSmartImport` e sull’uso del seam `internal` di test per validare il single staging.
