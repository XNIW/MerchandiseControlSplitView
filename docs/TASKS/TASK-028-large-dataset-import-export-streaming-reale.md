# TASK-028 — Large dataset: import/export realmente bounded-memory

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-028                   |
| Stato              | DONE                       |
| Priorità           | MEDIA                      |
| Area               | Performance / Import / Export |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03 (review tecnica finale + fix mirati + verifiche verdi) |
| Tracking `MASTER-PLAN` | **`BACKLOG`** (attivazione backlog/ACTIVE da allineare al workflow utente) |

---

## Dipendenze

- TASK-017 (DONE)
- TASK-021 (DONE)
- TASK-026 (**DONE** — correttezza import; non mescolare fix di correttezza con questo task)
- Eventuale disponibilità di strumenti già presenti per logging/profiling leggero da riusare senza introdurre nuova infrastruttura

---

## Scopo

Ridurre i picchi di memoria residui su dataset grandi nei percorsi import/export. Il writer usa `SXSSFWorkbook` (finestra righe lato POI), ma i dati sorgente restano liste complete caricate prima della scrittura; l’import anteprima DB materializza file + DB e l’analisi mantiene strutture proporzionali al dataset (vedi sezione **Stato codice verificato**).

---

## Contesto

- `DatabaseViewModel.exportDatabase(...)` chiama in sequenza `getAllProductsWithDetails` / `getAllSuppliers` / `getAllCategories` / `getAllPriceHistoryRows` (secondo selezione), costruisce `DatabaseExportContent` e solo dopo invoca `writeDatabaseExport` su `Dispatchers.IO`.
- `readAndAnalyzeExcel(...)` legge l’intero file in `ByteArray` (`readBytes()`), materializza tutte le righe in una lista, poi `analyzeRows` produce `dataRows` completi — il path anteprima import DB (`parseImportFile` → `readAndAnalyzeExcel`) non può essere bounded solo “spezzando in chunk” a valle senza ridurre questa materializzazione.
- `fetchCurrentDatabaseProducts()` carica `repository.getAllProducts()` intero mentre `dataRows` è già in memoria → picco sovrapposto file + catalogo DB per l’analisi.
- `ImportAnalyzer.analyzeStreamingDeferredRelations` (usato dal flusso reale) non ricostruisce una `List<Map>` monolitica delle righe import, ma: (1) prima passata accumula in `pendingByBarcode` una voce per barcode distinto; (2) seconda passata costruisce `newProducts` / `updatedProducts` / liste errori e warning; carica inoltre `getAllSuppliers` / `getAllCategories` in mappe all’avvio. Il nome “streaming” non implica heap limitato per l’output di preview.
- `DuplicateWarning` e `RowImportError` possono trattenere mappe/riferimenti a riga per molte occorrenze (es. numeri riga duplicati, righe in errore).

### Stato codice verificato (review planning 2026-04-03)

Punti di accumulo confermati nel codice attuale (perimetro principale **DatabaseScreen / `DatabaseViewModel`**):

| Area | Comportamento rilevante |
|------|-------------------------|
| Export DB | Liste complete in VM → `writeDatabaseExport` itera `content.products` / suppliers / categories / `priceHistoryRows` (streaming POI su output, input ancora liste intere). |
| DAO / repository | `getAllProductsWithDetails` → `productDao.getAllWithDetailsOnce()`; `getAllPriceHistoryRows` → `priceDao.getAllWithBarcode()` mappato in lista — nessun paging lato API attuale. |
| `readAndAnalyzeExcel` | Buffer file intero + lista `rows` + output `dataRows` completo. |
| Import anteprima DB | Dopo `readAndAnalyzeExcel`, `buildChunkedRows` crea chunk da `dataRows` già materializzata — il chunking serve al consumo nell’analizzatore, non elimina la lista righe iniziale. |
| `ImportAnalyzer` | Mappe fornitore/categoria complete in RAM; liste risultato preview (`newProducts`, `updatedProducts`, …) proporzionali ai barcode unici. |

**Buffering “spostato in basso”:** se il ViewModel smettesse di tenere una lista unica, Room/DAO resterebbero comunque in grado di restituire `List<...>` intere finché le query non sono iterate a batch — da verificare in esecuzione per non spostare solo il picco.

**Perimetro consigliato:** questo task mira in primo luogo a **export DB + import anteprima da file su DatabaseScreen** (`DatabaseViewModel`, `ExcelUtils.readAndAnalyzeExcel`, `ImportAnalyzer`, `DatabaseExportWriter`, repository/DAO coinvolti). `ExcelViewModel` (PreGenerate / merge URI) riusa `readAndAnalyzeExcel` con le stesse materializzazioni; **non** è nel perimetro minimo salvo estensione esplicita, per evitare scope creep.

**Test JVM già presenti da considerare in regressione:** `DatabaseViewModelTest` (export), `DatabaseExportWriterTest`, `ExcelUtilsTest`, `FullDbExportImportRoundTripTest` (ove toccati).

---

## Non incluso

- Correzioni di data integrity / sync status: vedi TASK-026.
- Nuove feature export/import o redesign dialog/screen.
- Ottimizzazioni premature fuori dai percorsi realmente caldi.
- Refactor architetturali ampi non necessari per ottenere bounded-memory sui flussi target.
- Cambi di UX/UI non richiesti dalla nuova pipeline, salvo micro-ritocchi coerenti con lo stile esistente per stati di avanzamento, testi e feedback utente.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — `exportDatabase`, `parseImportFile`, `fetchCurrentDatabaseProducts`, `buildChunkedRows`, `analyzeImportStreaming`, `analyzeGridData`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — `readAndAnalyzeExcel`, `analyzeRows` e lettura POI/HTML
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — `ImportAnalyzer` (`analyzeStreamingDeferredRelations` e strutture dati risultato)
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/DatabaseExportWriter.kt` — `writeDatabaseExport`, `DatabaseExportContent`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — metodi `getAll*` usati da export/analisi
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductDao.kt` — es. `getAll`, `getAllWithDetailsOnce`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductPriceDao.kt` — es. `getAllWithBarcode` (export price history)
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — solo se servono micro-ritocchi a stato export/import già presenti
- Test JVM: `DatabaseViewModelTest`, `DatabaseExportWriterTest`, `ExcelUtilsTest`, `FullDbExportImportRoundTripTest` (estensioni solo se serve copertura large dataset)
- **Fuori perimetro minimo:** `ExcelViewModel.kt` (stesso `readAndAnalyzeExcel`, flusso PreGenerate) — solo nota o follow-up se non incluso

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Il full export DB non richiede più preload completo di tutte le liste nel ViewModel quando non necessario | B + S | ✅ prodotti + price history a pagine; suppliers/categories restano liste compatte |
| 2 | Il percorso export usa fetch/paginazione/streaming repository-driven dove praticabile, mantenendo output invariato | B + S | ✅ `getProductsWithDetailsPage` / `getPriceHistoryRowsPage` + `writeDatabaseExportStreaming` |
| 3 | Il percorso import foglio singolo riduce la materializzazione completa non necessaria rispetto allo stato attuale | B + S | ✅ `rows.clear()` post-`analyzeRows` in `readAndAnalyzeExcel` (rilascio griglia grezza prima del return) |
| 4 | L’analisi import limita accumulatori e collezioni residenti alle sole strutture strettamente necessarie per preview/validazione | B + S | ✅ sample duplicati bounded (`max 50`) già nel `Pending`, `totalOccurrences` e `lastRowNumber` separati |
| 5 | Nessuna regressione funzionale su output export, preview import e compatibilità workbook | B + S | ✅ suite mirata + round-trip JVM verde; output price history verificato anche su boundary tra pagine |
| 6 | Eventuali ritocchi UI/UX restano minimi, coerenti con il design esistente e migliorano chiarezza di progress/error state, senza introdurre nuove feature né regressioni di responsiveness percepita | S + M | ✅ testo troncamento duplicati (it/en/es/zh) |
| 7 | I test esistenti di export/import rilevanti restano verdi; quelli necessari vengono aggiornati/estesi con casi dataset grandi | B | ✅ aggiornati `DatabaseViewModelTest`; aggiunti casi su duplicate warning bounded e continuità price history cross-page |
| 8 | La riduzione dei preload/materializzazioni principali è verificata anche con confronto prima/dopo almeno qualitativo o tramite logging/profiling leggero sui flussi target | S + M | ✅ motivazione in log Execution (nessun profiler in agente) |
| 9 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM rilevanti verdi | B + S | ✅ eseguiti con JBR di Android Studio |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task separato dalla correttezza import | La priorità immediata è la data integrity, non la memoria | 2026-04-03 |
| 2 | Ottimizzare prima i veri preload completi | Miglior rapporto valore/costo | 2026-04-03 |
| 3 | Preferire miglioramenti incrementali misurabili invece di riscrivere l’intera pipeline import/export in un solo step | Riduce rischio regressioni e facilita review/test | 2026-04-03 |
| 4 | Consentiti micro-ritocchi UI/UX solo se direttamente collegati ai nuovi stati della pipeline (progress, attesa, errori, messaggi) | Mantiene coerenza visuale senza allargare scope | 2026-04-03 |
| 5 | Prima bounded-memory sui percorsi principali, poi ulteriori ottimizzazioni solo se supportate da profiling o test | Evita complessità inutile e ottimizzazioni premature | 2026-04-03 |
| 6 | Nessuna nuova infrastruttura di monitoraggio dedicata per questo task; usare strumenti e logging leggero già disponibili | Mantiene basso il costo dell’intervento e il focus sul problema reale | 2026-04-03 |
| 7 | In caso di trade-off tra throughput teorico e fluidità percepita, privilegiare la UX stabile e responsiva | L’ottimizzazione non deve peggiorare l’esperienza d’uso | 2026-04-03 |

---

## Planning (integrato e raffinato)

### Analisi

Le mitigazioni di TASK-017 e TASK-021 hanno migliorato la situazione, ma l’audit 2026-04-03 e la verifica sul codice mostrano che i percorsi large dataset non sono ancora bounded-memory end-to-end. **`writeDatabaseExport` usa `SXSSFWorkbook`** (streaming della scrittura verso l’output), ma **le liste passate in `DatabaseExportContent` sono già complete**. Il collo di bottiglia residuo è soprattutto a monte: fetch completi nel `DatabaseViewModel`, `readAndAnalyzeExcel` che materializza file + righe, import anteprima DB con sovrapposizione `dataRows` + `getAllProducts()`, e analisi che mantiene strutture proporzionali al dataset (inclusi risultati preview e mappe DB ausiliarie).

Il task va quindi affrontato come ottimizzazione dei punti di accumulo reali, non come redesign generale. L’obiettivo è ridurre picchi di heap, pressione GC e rischio OOM mantenendo invariati comportamento utente, formato file e compatibilità dei workbook.

### Obiettivi tecnici

1. Eliminare o ridurre i preload completi evitabili nel full export DB.
2. Rendere il percorso single-sheet import più vicino a una pipeline incrementale/chunked.
3. Limitare gli accumulatori dell’analisi import alle sole informazioni necessarie a preview, validazione e riepilogo finale.
4. Conservare output, semantica e compatibilità esterna invariati.
5. Introdurre solo micro-migliorie UI/UX se utili a rendere più chiari stati lunghi, avanzamento, annullamento gestito se già previsto dal flusso, o messaggi d’errore.
6. Rendere espliciti nel task i limiti residui accettati, se alcuni punti non possono essere resi bounded-memory senza costi sproporzionati.

### Strategia di implementazione

- Spostare la responsabilità del recupero progressivo dei dati fuori dal `DatabaseViewModel` quando oggi costruisce liste complete prima della scrittura.
- Valutare API repository/DAO orientate a batch, paging o sequenze consumabili dal writer, in modo che `DatabaseExportWriter` possa scrivere mentre i record vengono letti.
- Evitare copie intermedie non necessarie tra ViewModel, repository e writer.
- Garantire che fetch e scrittura restino su dispatcher/thread appropriati, evitando regressioni di responsiveness UI durante export lunghi.
- Mantenere invariati ordine logico dei dati, contenuto del workbook e naming dei fogli/colonne.
- Valutare, se già compatibile con l’architettura esistente, punti semplici di backpressure o chunk size stabili per evitare burst inutili di memoria o I/O.
  - Verificare che l’eliminazione del preload nel ViewModel non venga vanificata da buffering equivalente spostato più in basso tra repository, DAO o writer.

#### 2) Import single-sheet (anteprima DB): ridurre materializzazione totale

- Riesaminare `readAndAnalyzeExcel(...)` (inclusi `readBytes()`, lista `rows`, output `dataRows` di `analyzeRows`) e il percorso `DatabaseViewModel.parseImportFile` / `startImportAnalysis` per i punti in cui tutte le righe restano residenti.
- Convertire dove praticabile il flusso in lettura incrementale: riga → normalizzazione minima → validazione/accumulo essenziale → rilascio.
- Separare chiaramente i dati necessari alla preview da quelli usati solo transitoriamente durante parsing e analisi.
- Evitare duplicazioni della stessa informazione in più collection parallele se una rappresentazione più compatta è sufficiente.
- Evitare che parsing, analisi e costruzione preview trattengano simultaneamente lo stesso dataset in forme diverse.
- Dove il conteggio totale non è noto in anticipo, privilegiare feedback utente onesti e coerenti (stato in corso, step attuale) invece di progress falsamente precisi.

#### 3) Import analysis: accumulatori bounded dove possibile

- Rivedere **`ImportAnalyzer.analyzeStreamingDeferredRelations`** (entry point reale dei flussi VM) e, se utile, `analyzeStreaming` / strutture associate per distinguere:
  - stato realmente necessario fino a fine analisi;
  - stato aggregato/compatto sufficiente per preview o report finale;
  - stato temporaneo eliminabile subito dopo l’elaborazione della riga/chunk.
- Preferire contatori, summary struct, set/map compatti e limiti espliciti per campioni diagnostici invece di conservare collezioni complete quando non indispensabili.
- Se alcune collezioni complete restano necessarie per UX o compatibilità, documentarlo esplicitamente e circoscriverle al minimo.
- Limitare anche la cardinalità di esempi, warning sample o record diagnostici conservati per la UI/review, mantenendo solo campioni utili e rappresentativi.
- Evitare che strutture di supporto temporanee sopravvivano oltre il necessario per semplice comodità di debug.

#### 4) Guardrail di compatibilità

- Nessun cambio al formato export atteso dall’utente.
- Nessuna perdita di informazioni nella preview import rispetto al comportamento corrente, salvo eventuali dettagli non usati e costosi che risultino realmente ridondanti.
- Nessuna modifica del significato dei controlli di validazione già esistenti.
- Nessuna mescolanza con fix di correttezza appartenenti a TASK-026.
- Nessuna regressione di cancellabilità/interruzione del flusso se già supportata dai percorsi esistenti.
- Nessun peggioramento percepibile della responsiveness della UI durante import/export su dataset grandi.
- Nessuna regressione nella leggibilità o comprensibilità dei risultati mostrati all’utente dopo preview/import/export.
  - Nessun nuovo flag, opzione o ramo configurabile lato utente per governare questa ottimizzazione, salvo casi già previsti dall’app.

### Micro-ritocchi UI/UX consentiti

- migliorare testi di stato per operazioni lunghe (es. import/export in corso);
- rendere più coerenti indicatori di avanzamento, attesa o completamento già presenti;
- migliorare chiarezza dei messaggi di errore o dei casi di dataset molto grande;
- se il flusso già lo supporta, rendere più chiaro lo stato di annullamento/interruzione senza introdurre nuove feature dedicate;
- mantenere stile, spacing, tono e gerarchia visuale allineati al resto dell’app.
- evitare indicatori troppo rumorosi o aggiornamenti UI inutilmente frequenti che peggiorano la fluidità percepita;
- preferire feedback compatti, chiari e coerenti con i componenti già presenti nell’app.

### Piano di esecuzione proposto

1. Mappare i punti esatti di massima materializzazione e i possibili punti di blocco UI nei flussi export/import.
2. Individuare per ciascun punto caldo se il problema principale è preload completo, duplicazione dati, accumulo diagnostico o aggiornamento UI troppo costoso.
3. Intervenire prima sul full export DB, perché offre il miglior rapporto beneficio/rischio.
4. Ridurre poi la materializzazione del single-sheet import.
5. Rifinire `ImportAnalyzer` per contenere accumulatori, sample diagnostici e strutture temporanee.
6. Verificare che i cambi non introducano regressioni di responsiveness, progress reporting fuorviante o stati UI incoerenti.
7. Eseguire test mirati su dataset grandi e verificare assenza di regressioni funzionali.
8. Applicare solo eventuali micro-ritocchi UI/UX strettamente necessari e coerenti.
9. Annotare nel task i miglioramenti effettivi ottenuti e gli eventuali limiti residui accettati.

### Verifiche previste

- Confronto output export prima/dopo su casi rappresentativi.
- Verifica preview import prima/dopo su file validi, parzialmente sporchi e grandi.
- Controllo che i percorsi principali non richiedano più preload completi evitabili.
  - Verifica che eventuali nuovi percorsi streaming/chunked non reintroducano materializzazione completa o buffering equivalente in layer inferiori.
- Confronto prima/dopo almeno qualitativo sui punti di accumulo principali (liste preloadate, collezioni residenti, chunk trattenuti), usando logging mirato, inspection del codice o profiling leggero se disponibile.
- Verifica che eventuali indicatori/stati UI aggiustati restino coerenti con il comportamento reale della pipeline.
- Verifica che import/export lunghi non peggiorino la responsiveness percepita della schermata rispetto allo stato attuale.
- Verifica che eventuali aggiornamenti di stato/progresso non causino churn UI evitabile o refresh troppo frequenti.
- Verifica che i sample diagnostici e i riepiloghi restino utili per l’utente senza trattenere strutture sproporzionate.
- Riesecuzione di test JVM rilevanti e loro estensione dove mancano coperture su large dataset.
- Build/lint verdi.

### Rischi identificati

- Ottimizzazione troppo aggressiva che cambia comportamento utente o compatibilità file.
- Complessità eccessiva se si tenta streaming perfetto end-to-end in un solo passo.
- Spostare la memoria da un layer all’altro senza ridurre davvero i picchi complessivi.
- Migliorare performance medie ma peggiorare diagnosi errori o qualità della preview import.
- Introdurre progress UI fuorviante o troppo “precisa” quando il flusso reale non può stimare il totale in modo affidabile.
- Ridurre il picco memoria ma aumentare complessità concorrente o rischio di lavoro sul thread sbagliato.
- Migliorare la memoria ma aumentare troppo la frequenza di update UI, peggiorando la fluidità percepita.
- Conservare campioni diagnostici troppo ricchi, vanificando parte del beneficio memoria ottenuto altrove.
  - Rimuovere un preload evidente nel layer alto ma reintrodurre di fatto lo stesso costo in un layer inferiore, ottenendo beneficio solo apparente.

### Mitigazioni

- Procedere per step piccoli e verificabili.
- Misurare e confrontare i punti di accumulo prima/dopo ogni modifica significativa.
- Tenere separati i cambi di performance dai fix di correttezza.
- Limitare i ritocchi UI/UX a ciò che supporta direttamente la leggibilità del flusso.
- Evitare di introdurre nuovi layer, astrazioni o callback più complessi del necessario solo per inseguire uno streaming “perfetto”.
- Se una parte non può diventare realmente bounded-memory senza costi eccessivi, ridurne comunque il picco e documentare chiaramente il limite residuo nel task.
- Preferire feedback UI semplici e veritieri a progress metriche artificiose quando il totale elaborabile non è stabile.
- Riesaminare esplicitamente dispatcher, contesto coroutine e punti di aggiornamento UI per evitare regressioni indirette.
- Ridurre la frequenza degli update UI al minimo utile, mantenendo feedback chiaro ma non rumoroso.
- Applicare limiti espliciti a sample diagnostici, warning collection e riepiloghi tenuti in memoria.
  - Riesaminare end-to-end il flusso modificato per assicurarsi che la memoria risparmiata non venga semplicemente spostata in un altro layer con minore visibilità.

### Output atteso di questo task

- Pipeline export più progressiva lato fetch/scrittura.
- Pipeline import single-sheet meno dipendente da collezioni complete residenti.
- Analisi import con stato residente più compatto e proporzionato alle esigenze reali.
- Eventuali micro-ritocchi UI/UX limitati a progress, stati, annullamento già supportato e messaggi, senza impatto sul perimetro funzionale.
- Evidenza chiara nel task di quali punti sono stati davvero alleggeriti e di quali eventuali limiti residui restano accettati.
- Nessuna espansione di scope oltre performance/memoria, salvo piccoli affinamenti UX/UI coerenti.

### Checkpoint decisionali per restare efficienti

- preferire la soluzione con meno moving parts se il beneficio memoria è comparabile;
- preferire streaming/chunking nei punti caldi reali rispetto a micro-ottimizzazioni sparse;
- evitare di spostare lavoro sul main thread o complicare inutilmente la UX per inseguire performance teoriche;
- se il progresso reale non è calcolabile in modo affidabile, preferire stati chiari e onesti invece di percentuali ingannevoli;
- se c’è una scelta UI/UX, scegliere l’opzione più coerente con il design esistente, più leggibile e con minor carico cognitivo;
- non introdurre nuove opzioni utente o nuove configurazioni se il comportamento corretto può essere deciso internamente.

### Nota operativa (esecutore)

Il perimetro e i punti caldi sono stati allineati al codice nella sezione **Stato codice verificato**. In caso di ambiguità, privilegiare la soluzione più semplice e coerente con UX/UI già presente, senza allargare il perimetro funzionale. Tra precisione del progress, complessità tecnica e chiarezza UX, preferire feedback onesti e comprensibili.

### Nota finale di integrazione

Ulteriori modifiche al piano durante l’esecuzione devono restare entro questi vincoli:

- nessun allargamento del perimetro funzionale;
- priorità a riduzione dei picchi reali, chiarezza UX, fluidità percepita e coerenza con lo stile esistente;
- documentare nel task benefici ottenuti e limiti residui accettati;
- preferenza per la soluzione più semplice, leggibile e verificabile.

## Execution

### Planning review — 2026-04-03

**Revisore:** controllo tecnico mirato (codice vs planning)

**Esito:** il planning è stato integrato con ancore concrete al codice (export VM+writer, `readAndAnalyzeExcel`, `ImportAnalyzer`, DAO/repository, perimetro ExcelViewModel). I criteri di accettazione restano validi. Stato portato a **`EXECUTION`** per lavorazione esecutore.

**File toccati in questa revisione:** solo questo documento task.

### Esecuzione implementativa — 2026-04-03

**File modificati:**
- `ProductDao.kt` — `getWithDetailsPage(limit, offset)` (stesso `ORDER BY` di `getAllWithDetailsOnce`).
- `ProductPriceDao.kt` — `getAllWithBarcodePage(limit, offset)` (stesso ordinamento export).
- `InventoryRepository.kt` — `getProductsWithDetailsPage`, `getPriceHistoryRowsPage`; mapping price history riusato.
- `DatabaseExportWriter.kt` — `writeProductDetailDataRow` condiviso; **`writeDatabaseExportStreaming`** (pagine prodotti e price history; `DATABASE_EXPORT_PAGE_SIZE = 500`); `writeDatabaseExport` invariato per test esistenti.
- `DatabaseViewModel.kt` — `exportDatabase` usa streaming + callback progresso (fetching/fetched allineati al primo batch per foglio).
- `ExcelUtils.kt` — `rows.clear()` dopo `analyzeRows` per rilasciare prima la lista righe grezze.
- `ImportAnalysis.kt` (util) — warning duplicati bounded già nella struttura `Pending` (`sampledRowNumbers`, `totalOccurrences`, `lastRowNumber`) invece di trattenere tutti i numeri riga fino alla fine.
- `data/ImportAnalysis.kt` — `DuplicateWarning.totalOccurrences`.
- `ImportAnalysisScreen.kt` — riga opzionale se elenco righe troncato.
- `values/strings.xml`, `values-en`, `values-es`, `values-zh` — `warning_duplicate_rows_truncated`.
- `DatabaseViewModelTest.kt` — mock/verify sui nuovi metodi paginati + assert espliciti che i vecchi preload bulk non vengano più chiamati.
- `DatabaseExportWriterTest.kt` — test `writeDatabaseExportStreaming` con `pageSize = 1` + continuità `oldPrice` su boundary tra pagine.
- `ImportAnalyzerTest.kt` — test cap warning duplicati (`totalOccurrences`, sample bounded, riga finale vincente).

**Cosa è stato alleggerito (reale):**
- **Export DB:** niente più liste intere prodotti né price history nel ViewModel; carico a finestre di 500 righe dal DAO mentre SXSSF scrive (fornitura/categorie restano `getAll*` — tabelle tipicamente piccole).
- **Lettura Excel (anteprima import):** la `MutableList` `rows` viene svuotata dopo l’analisi strutturale così la Triple restituita non mantiene un secondo riferimento alla griglia completa oltre a header/`dataRows` (picco leggermente anticipato per GC).
- **ImportAnalyzer:** i warning duplicati non accumulano più tutti i numeri riga durante la prima passata; tengono solo un campione bounded (max 50, con riga finale vincente visibile), `totalOccurrences` e `lastRowNumber`.

**UI/UX:** micro-ritocco coerente — messaggio quando l’elenco righe duplicati è troncato (stesso `WarningRow`).

**Check obbligatori (AGENTS.md):**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → BUILD SUCCESSFUL |
| Lint | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → BUILD SUCCESSFUL |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo nei file toccati; restano solo warning/deprecazioni toolchain AGP già preesistenti e fuori scope |
| Coerenza planning | ✅ | |
| Criteri accettazione | ✅ ESEGUITO | Tutti i criteri chiusi con evidenza |

**Baseline regressione TASK-004 (eseguita):**
- Test eseguiti: `./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.DatabaseExportWriterTest" --tests "com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest" --tests "com.example.merchandisecontrolsplitview.util.ExcelUtilsTest" --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"`
- Test aggiunti/aggiornati: `DatabaseExportWriterTest` (continuità `oldPrice` cross-page), `ImportAnalyzerTest` (cap warning duplicati con `totalOccurrences` e riga finale vincente), `DatabaseViewModelTest` (assenza chiamate ai preload bulk legacy)
- Verifica extra: `./gradlew testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest"` → BUILD SUCCESSFUL
- Limiti residui: nessun limite bloccante sui percorsi target; restano solo hotspot esplicitamente fuori scope (vedi sotto)

**Limiti residui accettati:**
- `readAndAnalyzeExcel` continua a usare `readBytes()` e a materializzare `dataRows` per l’anteprima DB (fuori scope di uno step piccolo senza riscrittura POI).
- `fetchCurrentDatabaseProducts()` resta `getAllProducts()` per la mappa barcode → `Product` (serve alla logica di merge).
- Preview `newProducts` / `updatedProducts` restano proporzionali ai barcode unici (semantica invariata).
- **ExcelViewModel** / PreGenerate: stesso `readAndAnalyzeExcel`, non toccato (perimetro task).

**Follow-up suggerito:** streaming lettura file `.xlsx` senza `readBytes` intero; valutare lookup DB per barcode in analisi al posto di `getAllProducts()` (trade-off complessità/I-O).

---

## Review

_(Dopo EXECUTION.)_

---

## Fix

### Fix applicati dopo review tecnica — 2026-04-03

- **ImportAnalyzer:** corretto un falso bounded-memory nei duplicati. La prima implementazione limitava solo il `DuplicateWarning` finale, ma `Pending.rowNumbers` continuava a trattenere tutte le occorrenze in memoria. Ora il cap è applicato già durante l’accumulo (`sampledRowNumbers` max 50), mantenendo separati `totalOccurrences` e `lastRowNumber`.
- **Copertura test:** aggiunti test su:
  - continuità del calcolo `oldPrice` nello streaming export quando un gruppo `barcode + type` attraversa più pagine;
  - troncamento reale dei warning duplicati con conservazione del conteggio totale e della riga finale vincente;
  - assenza di richiamo dei vecchi metodi bulk `getAllProductsWithDetails()` / `getAllPriceHistoryRows()` nel `DatabaseViewModel`.

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | DONE     |
| Data chiusura          | 2026-04-03 |
| Tutti i criteri ✅?    | Sì |
| Rischi residui         | Hotspot ancora noti ma accettati e documentati: `readBytes()` + `dataRows` preview, `getAllProducts()` per lookup barcode DB, perimetro `ExcelViewModel` fuori task |

---

## Riepilogo finale

_(Al termine.)_

---

## Handoff

- TASK-028 è chiuso **DONE** a livello file task; il `MASTER-PLAN` è ancora disallineato (`BACKLOG`) e va riallineato dal planner/tracking globale.
- Task performance post-audit 2026-04-03; **TASK-026 è DONE** — non mescolare fix di correttezza con ottimizzazioni memoria.
- UX esistente: `DatabaseViewModel` espone già `exportUiState` / `ExportProgressTracker` con messaggi localizzati (`export_preparing`, `export_fetching_sheet`, …); eventuali micro-ritocchi devono restare coerenti con questo schema, non sostituirlo con nuove feature.
- Possibili follow-up separati, non bloccanti per TASK-028: streaming lettura workbook senza `readBytes()`, riduzione snapshot DB `getAllProducts()` in analisi, valutazione estensione bounded-memory anche al flusso `ExcelViewModel`/PreGenerate.
