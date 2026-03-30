# TASK-017 — Crash full DB import (OOM)

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-017                   |
| Stato                | DONE                       |
| Priorità             | CRITICA                    |
| Area                 | Import / Database / Stability |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-27                 |

**Chiusura (2026-03-27):** task **`DONE`** su **conferma utente** dopo test manuali con esito positivo. Verifiche statiche (**assembleDebug**, **lint**) già OK in Execution/Fix; cicli review/fix completati. Il planning e lo storico Execution/Fix restano di riferimento; i criteri M/E precedentemente marcati non eseguibili in sessione sono stati **validati dall’utente** in smoke reale.

---

## Dipendenze

- TASK-001 (DONE)

### Task correlati (il fix **non** vive lì)

| Task | Rapporto |
|------|-----------|
| **TASK-006** | Correlato (validazione/robustezza import Excel) ma **troppo generico** per questo crash specifico (OOM su import completo DB da XLSX). |
| **TASK-007** | Correlato come **follow-up** per verifica round-trip export/import database completo, dopo che l’import non crasha più. |
| **TASK-004** | Correlato come **follow-up** per copertura test su `DatabaseViewModel` / import; non sostituisce il bugfix runtime. |

**Regola:** il crash OOM sul full import resta **tracciato e risolto in TASK-017**, non “assorbito” da TASK-006/007/004.

---

## Scopo

Eliminare il **crash (OutOfMemoryError / OOM)** che si verifica durante l’**import completo del database** da file **XLSX** nella **DatabaseScreen**, lungo il percorso `DatabaseViewModel.startFullDbImport` → costruzione workbook **Apache POI** tramite **`XSSFWorkbook(inStream)`** (root cause considerata **sufficientemente confermata**, vedi [Evidenza iniziale](#evidenza-iniziale)), preservando il **comportamento funzionale** dell’import sui quattro fogli supportati: **Suppliers**, **Categories**, **Products**, **PriceHistory** (nomi foglio case-insensitive come oggi).

Il **fix primario pianificato** è l’adozione di un parser XLSX **streaming / event-based** (Apache POI: **SAX / `OPCPackage` + `XSSFReader` o equivalente documentato POI**) **solo** per il percorso full-import, in modo che **`XSSFWorkbook` (DOM) non resti nel path del full import** e che **non si dipenda** dalla materializzazione completa via **`analyzePoiSheet`** in quel percorso.

---

## Evidenza iniziale

**Root cause (stato: confermata per il planning, non più ipotesi generica da esplorare a lungo):** l’OOM si manifesta nel flusso **`DatabaseViewModel.startFullDbImport`**, in fase di apertura del workbook, alla chiamata **`XSSFWorkbook(inStream)`** (modello DOM XSSF che carica struttura e celle in heap).

**Sintesi stack / log (pattern atteso — allineare al trace reale da logcat al momento dell’esecuzione):**

- `java.lang.OutOfMemoryError` (o messaggio correlato “Failed to allocate…” / GC thrashing immediatamente precedente).
- Frame applicativo tipico: `DatabaseViewModel.startFullDbImport` → lambda / `withContext(Dispatchers.IO)` → `openInputStream` → **`org.apache.poi.xssf.usermodel.XSSFWorkbook.<init>`** (o costruttore / factory equivalente che materializza il workbook).
- Assenza di gestione controllata se l’errore esce come **`Error`** e non viene intercettato al boundary (oggi il `catch` principale è su **`Exception`**, vedi [Decisioni](#decisioni) e planning error handling).

**Evidenza completa:** incollare in **Execution** (alla prima analisi runtime) il dump logcat completo e, se disponibile, dimensione file / dispositivo — qui resta solo la sintesi per vincolare il planning.

---

## Contesto

Oggi `startFullDbImport` apre lo stream, costruisce **`XSSFWorkbook(inStream)`**, risolve i fogli per nome, e per **Suppliers**, **Categories**, **Products**, **PriceHistory** usa **`analyzePoiSheet`**, che materializza header + righe in strutture complete — aggravando heap **dopo** il workbook DOM. Per XLSX molto grandi questo insieme è incompatibile con heap tipica app Android.

**Obiettivo tecnico (post-decisioni):** sostituire nel **solo** full-import la catena DOM + `analyzePoiSheet` con lettura **streaming**, mantenendo output verso `ImportAnalyzer.analyzeStreaming` e verso la costruzione minima di **PriceHistory** post-analisi, senza introdurre redesign UX né nuovi flussi UI.

---

## Perimetro bloccato (non incluso / out of scope)

| Vietato / limitato | Dettaglio |
|--------------------|-----------|
| Import single-sheet | **Non** modificare il normale import **`startImportAnalysis` / `readAndAnalyzeExcel`** salvo necessità reale emersa in EXECUTION (da documentare se mai accadesse). |
| UX/UI DatabaseScreen | **Nessun** redesign della schermata; nessun nuovo dialog di warning o pre-check invasivo. Errori **OOM** o **parsing** non devono introdurre **nuove superfici UI** (nessun dialog, banner o bottom sheet dedicati): solo i pattern già presenti (vedi [Vincoli UX (full import)](#vincoli-ux-full-import)). |
| Piattaforma dati | **Nessun** cambio a navigation, DAO, entity, schema Room o architettura generale senza motivazione **stretta** legata al bugfix. |
| Formato | Nessuna estensione oltre XLSX / perimetro full-import attuale. |
| Altri task | **TASK-002** (`GeneratedScreen`): perimetro separato. |
| Fix principale vs guardrail | Eventuale **pre-check dimensione file** solo **guardrail secondario** (opzionale), **non** sostituto del fix streaming. |

### Vincoli UX (full import)

- **Loading bloccante** già usato durante l’operazione (`UiState.Loading` / messaggio in corso).
- **`UiState.Error`** + **snackbar / messaggio** già coerenti con `DatabaseScreen` per altri fallimenti analisi.
- **Vietato:** dialog, banner, bottom sheet o altre superfici **nuove** specifiche per OOM o errore parsing full-import.

### Post-analisi invariata (DatabaseScreen)

Il fix **non** deve alterare il flusso utente **dopo** un’analisi full-import riuscita:

- **Nessun** auto-apply delle modifiche al database.
- **Nessuna** nuova conferma (dialog o altro) oltre a quanto già presente oggi.
- **Nessuna** schermata nuova o navigazione aggiuntiva legata al solo fix.
- **Nessun** nuovo dialog, banner o bottom sheet nel post-analisi.

Restano validi solo: loading esistente in fase operazione, `UiState.Error` in fallimento, snackbar/messaggi già in uso, e il pattern attuale di preview/apply (esplicito utente) come oggi sulla `DatabaseScreen`.

---

## File potenzialmente coinvolti

**Primari (attesi):**

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — `startFullDbImport`: rimozione di `XSSFWorkbook` + `analyzePoiSheet` da questo path; orchestrazione IO/UI state; boundary error (**incluso `OutOfMemoryError`**).
- Nuovo helper / package (nome da definire in EXECUTION) — **utility dedicata al full-import streaming**, separata dal flusso standard Excel, invocabile solo da `startFullDbImport`.

**Solo se necessario al fix (minimo):**

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — solo allineamento testi/stringhe se il messaggio errore OOM deve riusare chiavi esistenti (evitare nuove superfici UI).

**Da leggere in EXECUTION:**

- `InventoryRepository.kt`, `ImportAnalyzer` / modelli analysis — contract di `analyzeStreaming` e strutture **PriceHistory** (`PendingPriceEvent` / post-apply).

**Dipendenze:** nessuna nuova libreria senza approvazione esplicita; restare su **Apache POI** già presente (API streaming incluse).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Con il **file XLSX di test** segnalato dall’utente (o equivalente dimensionato), l’app **non va in crash** aprendo/avviando l’import completo | M / E | — |
| 2 | L’import produce **analisi completa** oppure un **fallimento gestito** (stato errore / messaggio / assenza di kill process) senza chiusura improvvisa | M / E | — |
| 3 | Nessuna regressione funzionale sui **4 fogli** supportati (Suppliers, Categories, Products, PriceHistory) su file di dimensione **moderata** baseline | M | — |
| 4 | `./gradlew assembleDebug` OK | B | — |
| 5 | `./gradlew lint` senza nuovi warning introdotti dal fix | S | — |
| 6 | Log di history / tracciamento import (`FULL_IMPORT` / `currentImportLogUid` o equivalente) **coerenti**: successo, errore gestito o abort documentato senza stato fantasma | S / M | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

*Nota validazione (RAM post-parser):* se i criteri 1–2 risultano OK sul workbook ma persistono **OOM/lentezza**, la matrice riga **Diag** + osservabilità mirata servono a **qualificare** il collo di bottiglia (**parser XLSX** vs **snapshot DB** vs **analyzer**) — vincoli: nessuna nuova UI, telemetry esterna o persistenza obbligatoria. Non estende il perimetro TASK-017 a refactor **repository/analyzer** in planning.

### Matrice di validazione (concreta, criteri 1–6)

| # | Come verificare | Evidenza attesa | Fallimento non accettabile |
|---|-----------------|-----------------|----------------------------|
| **1** | Avviare full import con XLSX grande noto per OOM precedente; ripetere 2–3 volte se instabile. | Processo vivo; nessun `OutOfMemoryError` non gestito in logcat; assenza di kill silenzioso. | Crash processo / ANR legato allo stesso stack su `XSSFWorkbook`. |
| **2** | Stesso scenario: completamento analisi **oppure** `UiState.Error` (o equivalente) con messaggio leggibile; mutex sbloccato. Verificare **reset stato** (vedi sotto). | Screenshot o log: stato UI + assenza kill; oppure analisi completata e flusso “applica” coerente. Dopo fallimento: `_importAnalysisResult` e `pendingPriceHistory` **non** devono mostrare dati di un run precedente (nessuna preview/apply “stantia”). | Schermata bloccata in Loading perpetuo; mutex non rilasciato; eccezione swallowed; UI o apply basati su risultati analisi/price history di un import precedente. |
| **3** | File moderato con tutti e 4 i fogli: confronto comportamento pre/post (supplier/category aggiunti, products analysis, price history pending) con smoke manuale o checklist del task. | Comportamento allineato al baseline documentato in Handoff. | Differenze funzionali su mapping colonne / fogli / case sensitivity. |
| **4** | `./gradlew assembleDebug` dalla root repo. | Build SUCCESS, 0 errori compilazione. | Errori Kotlin/Gradle nuovi. |
| **5** | `./gradlew lint` (o policy progetto); diff warning noto solo se pre-esistenti. | Nessun warning **nuovo** attribuibile al diff del task. | Nuovi warning su file toccati senza motivazione. |
| **6** | Ispezionare history log per `FULL_IMPORT`: SUCCESS dopo OK; FAILED (o tag equivalente) con messaggio su errore/OOM; `currentImportLogUid` non “orfano”. **Verifica boundary completa:** stesso criterio su **errori precoci** (file assente/stream null, workbook/OPC non apribile, foglio `Products` mancante, parsing), **file invalido** e **`OutOfMemoryError`** — in ogni caso: `appendHistoryLog` coerente, `currentImportLogUid` azzerato/nuovo come oggi, **`importMutex.unlock()`** nel `finally` (nessuna uscita senza unlock). | Logcat + entry history coerenti con esito reale su **tutti** i rami di uscita testati. | SUCCESS senza completamento; FAILED mancante su errore; uid inconsistente con UI; mutex ancora bloccato dopo errore; log SUCCESS/FAILED duplicati o incoerenti col flusso. |
| **Rec** (recovery sessione) | **Stessa sessione**, senza riavvio app: (1) primo full import **fallisce** (file invalido, errore parsing o OOM **gestito**); (2) subito dopo, secondo full import con file **valido** completo fino ad analisi OK. | Dopo (1): mutex **sbloccato**, log/history coerenti, stato senza dati stantii. Dopo (2): import eseguibile **normalmente**; `_importAnalysisResult` e `pendingPriceHistory` riflettono solo il secondo run; `currentImportLogUid` coerente (nessun uid “appeso” dal primo run); nessun blocco su nuovo import. | Secondo import impossibile o silenziosamente rotto; mutex ancora bloccato; risultati/messaggi del primo run mescolati col secondo; apply/preview incoerenti. |
| **Diag** (RAM post-parser) | Se l’OOM su **`XSSFWorkbook`** / workbook DOM **non si ripresenta** ma restano **OOM**, **memoria alta** o **lentezza** marcata: usare timing/metriche [Observability](#observability-tecnica-minimale-solo-execution) (e strumenti dev **senza** nuova UI/telemetry esterna) per **qualificare** se il picco è su **lettura/parsing XLSX** vs **snapshot DB** (`getAllProducts` / equivalente) vs fase **`ImportAnalyzer.analyzeStreaming`**. | Nota in **Execution** (o log) con conclusione documentata (es. “parser OK, picco dopo load DB” / “picco durante analyzer”). Nessun obbligo di persistenza. | attribuire a torto il problema al solo parser senza evidenza; ignorare hotspot residui se il sintomo persiste |

*Le righe **Rec** e **Diag** sono casi complementari ai criteri 1–6 (**recovery** sessione; **diagnosi** RAM oltre il parser).*

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task dedicato TASK-017, separato da TASK-002 | TASK-002 = UI GeneratedScreen; crash full import = altro perimetro e priorità stabilità | 2026-03-27 |
| 2 | Priorità CRITICA | Crash in produzione su percorso dati utente | 2026-03-27 |
| 3 | Root cause: OOM su **`XSSFWorkbook(inStream)`** in **`startFullDbImport`** | Confermata a livello planning; non trattare come ipotesi generica da lunga esplorazione | 2026-03-27 |
| 4 | Fix primario: parser **streaming / event-based** POI (**`OPCPackage` + `XSSFReader` / SAX**) **solo** full-import | Elimina DOM workbook sul percorso critico; allineato a vincoli memoria Android | 2026-03-27 |
| 5 | **`XSSFWorkbook` non resta nel path del full import** | Garantisce che il workbook intero non sia materializzato in heap | 2026-03-27 |
| 6 | **Niente** materializzazione completa via **`analyzePoiSheet`** nel full-import | `analyzePoiSheet` crea `List` complete di righe; incompatibile con file molto grandi | 2026-03-27 |
| 7 | **OutOfMemoryError** intercettato al **boundary** del full import | `OutOfMemoryError` è `Error`, non `Exception`; oggi il `catch (e: Exception)` non basta per gestione controllata | 2026-03-27 |
| 8 | Su OOM: aggiornare **`_uiState`**, **history log** (`appendHistoryLog` / tag FAILED), **`importMutex.unlock()`** in `finally` | Coerenza con stati attuali e assenza di deadlock UI | 2026-03-27 |
| 9 | **Nessun** nuovo flow UI; pre-check dimensione solo **opzionale secondario** | Decisione UX già fissata; fix = streaming, non avviso invasivo | 2026-03-27 |
| 10 | **Non** toccare single-sheet import salvo necessità documentata | Riduce rischio regressione su percorso già distinto | 2026-03-27 |
| 11 | **State hygiene (full import):** all’inizio di **ogni** nuova analisi full-import e in **ogni** percorso di **failure** (eccezioni, file invalido, OOM, abort), reset esplicito di **`_importAnalysisResult`** e **`pendingPriceHistory`** | Evita dati **stantii** e preview/apply incoerenti dopo fallimento o tra import successivi | 2026-03-27 |
| 12 | **Parità semantica** del parser streaming rispetto al path attuale è **contratto di accettazione implicito**; in EXECUTION va verificata tramite checklist in [Planning tecnico](#planning-tecnico-claude--backlog) | Riduce regressioni su fogli, header e tipi cella | 2026-03-27 |
| 13 | In **EXECUTION** va verificata la **coerenza completa** di `currentImportLogUid`, `appendHistoryLog` (SUCCESS/FAILED), `importMutex.unlock()` su **tutti** i rami di uscita (inclusi errore precoce, file invalido, OOM) | Nessuno stato fantasma né deadlock dopo errori | 2026-03-27 |
| 14 | Errori **OOM** o **parsing** full-import: **nessuna nuova superficie UI**; solo loading esistente, `UiState.Error`, snackbar/messaggio già in uso | Allineamento a `DatabaseScreen` senza scope creep UX | 2026-03-27 |
| 15 | **Fogli full-import:** **`Products`** = **obbligatorio**; **`Suppliers`**, **`Categories`**, **`PriceHistory`** = **opzionali**, come oggi. Manca **`Products`** → **fallimento gestito**; mancano solo fogli opzionali → analisi **comunque** possibile se `Products` è valido | Parità contrattuale col comportamento attuale; evita ambiguità in EXECUTION | 2026-03-27 |
| 16 | **Post-analisi invariata:** nessun auto-apply, nessuna nuova conferma/schermata/dialog/banner/bottom sheet; solo pattern già coerenti con `DatabaseScreen` | Il fix è memoria/stabilità, non cambio flusso apply | 2026-03-27 |
| 17 | **Chunk `Products` (streaming):** dimensione batch **iniziale conservativa**, definita in **un solo punto locale** nell’helper full-import (costante / parametro centralizzato lì), così da regolare in EXECUTION **senza refactor diffuso**. **Nessun valore numerico definitivo** nel task — solo il principio | Backpressure controllabile; tuning senza sparpaglio magic numbers | 2026-03-27 |
| 18 | **Lifecycle / cleanup IO-POI (full-import streaming):** tutte le risorse (**`InputStream`**, **`OPCPackage`**, reader/iterator **SAX/event-based**, eventuali **stream secondari** POI) devono avere **chiusura deterministica** anche su **errore precoce**, **parsing failure** e **`OutOfMemoryError` gestito** | Nessun leak di file handle / package aperti; EXECUTION senza ambiguità su *who closes what* | 2026-03-27 |
| 19 | **Memoria limitata end-to-end:** il full-import deve restare a footprint limitato non solo in lettura XLSX, ma nel **bridge** verso **`ImportAnalyzer.analyzeStreaming`** e nella raccolta dati **`PriceHistory`**. **Non accettabile:** leggere in streaming e poi **ricostruire in memoria l’intero dataset** prima dell’analisi o del post-apply. Eventuale buffering solo **locale**, **limitato** e ancorato al **chunk** configurato nell’helper full-import | Il goal anti-OOM non si esaurisce nel solo workbook; evita anti-pattern “stream poi lista completa” | 2026-03-27 |
| 20 | **Atomicità / side effect DB (full import):** un run che **fallisce** non deve lasciare **mutazioni parziali visibili** nel database attribuibili a quel tentativo. **Target di planning:** **`Suppliers`** e **`Categories`** non persistiti in modo definitivo **prima** che il foglio obbligatorio **`Products`** sia stato validato e l’analisi sia arrivata a uno **stato coerente** (es. lettura/analisi `Products` completata con successo secondo il contratto attuale). Su errore parsing, **`Products` mancante**, **OOM**, o altro fallimento del run → **nessun side effect parziale** del tentativo fallito | Coerenza dati utente; allineamento “tutto o niente” sul DB per run fallito | 2026-03-27 |
| 21 | **Eccezione di cancellazione:** **`CancellationException`** non va **inghiottita** né mappata genericamente a errore **OOM/parsing**. Restano obbligatori: **cleanup risorse**, **reset stato** (ove applicabile), **`importMutex.unlock()`**. **History/log** devono restare **coerenti e non fuorvianti** (non presentare un fallimento analisi quando l’utente/job ha solo cancellato). Vedi [Error handling](#error-handling-pianificato) | Evita regressioni da `catch` troppo ampi al boundary | 2026-03-27 |

**Nota su decisione 20:** se in **EXECUTION** il rispetto pieno della atomicità richiedesse un refactor **eccessivo**, il task deve **documentare** il comportamento **effettivo** del codice (anche se lascia side effect parziali come oggi) e **motivare** l’eccezione al target; il **preferito** resta **no partial side effects on failed run**.

---

## Planning tecnico (Claude / backlog)

### Architettura attesa

1. **Helper / utility dedicata** al full-import streaming (file o object Kotlin separato), **non** condivisa come default con `readAndAnalyzeExcel`, per chiarezza di perimetro e review.
2. **Risoluzione fogli** con mapping **case-insensitive** sui nomi canonici: `Suppliers`, `Categories`, `Products`, `PriceHistory` (stesso contratto attuale).
3. **Semantica obbligatorio/opzionale (allineata al codice attuale):**
   - **`Products`:** **obbligatorio**. Se assente → **fallimento gestito** (stesso tipo di esito attuale, es. eccezione/errore analisi con `UiState.Error` e history FAILED).
   - **`Suppliers`**, **`Categories`**, **`PriceHistory`:** **opzionali**. Se assenti → l’analisi prosegue ugualmente (solo con i dati disponibili); nessun fallimento solo per foglio opzionale mancante.
4. **`Products`:** lettura **vera streaming / chunking** — produrre sequenza (o chunk) di `Map<String,String>` (o tipo atteso da `ImportAnalyzer.analyzeStreaming`) **senza** costruire prima una **`List<List<String>>`** di tutte le righe.
   - **Chunk size / backpressure:** usare un valore iniziale **conservativo** per la dimensione del batch verso `ImportAnalyzer.analyzeStreaming` (o equivalente), definito in **un unico punto** nell’helper full-import (es. `private const` o singolo parametro top-level del file helper). **Non** fissare nel task il numero finale: in EXECUTION si potrà ridurre/aumentare localmente senza toccare molti file.
5. **`Suppliers` / `Categories`:** scansionare con **memoria minima** (solo colonne necessarie, es. header `name` + valori riga per riga o batch piccoli); evitare liste complete di tutte le celle. La **persistenza** in DB (`addSupplier` / `addCategory` o equivalente) va **pianificata** in coerenza con [Atomicità / side effect sul database](#atomicità--side-effect-sul-database) (decisione 20): in generale **dopo** validazione `Products` e stato analisi coerente, non prima.
6. **`PriceHistory`:** leggere il **formato minimo** necessario per il post-apply (stessi campi concettuali: barcode/productBarcode, timestamp, type, newPrice, source — case-insensitive come oggi), senza duplicare il carico DOM.
7. **Memoria limitata end-to-end (decisione 19):** il flusso dal parser al **`ImportAnalyzer.analyzeStreaming`** e verso **`pendingPriceHistory`** non deve introdurre una **materializzazione completa** del dataset (es. `List` di tutte le righe prodotto o tutti gli eventi price in RAM). La pipeline deve restare **a chunk** / streaming coerente con il chunk definito nell’helper; se il codice attuale di `PriceHistory` materializza tutto, il planning in EXECUTION deve **esplicitare** la deviazione o ridurre il carico — l’obiettivo contrattuale resta **nessun dataset intero** dopo una lettura nominalmente streaming.

### Picchi RAM residui (fuori dal parser XLSX)

*Solo **risk-awareness** per EXECUTION — **non** allarga il perimetro del task a refactor di `InventoryRepository` o `ImportAnalyzer` in fase planning.*

Dopo la rimozione di **`XSSFWorkbook`** dal full import, il **prossimo collo di bottiglia RAM** può spostarsi su componenti **già presenti** nel flusso attuale, ad es.:

- **Snapshot prodotti DB:** `repository.getAllProducts()` / struttura equivalente a **`currentDbProducts`** caricata in memoria per confronto con le righe import.
- **Strutture interne di `ImportAnalyzer.analyzeStreaming`:** mappe/accumulatori per **barcode**, **merge**, **deduplica** o cache equivalenti che crescono con il volume dati.

Questo **non** modifica il **fix primario** del task (parser XLSX **streaming** sul full import — decisioni 4–6), ma va tenuto **esplicitamente visibile** come **rischio residuo tecnico** se l’OOM sul workbook sparisce e restano **OOM secondari**, **GC pressione** o **lentezza** (vedi [Rischi residui](#rischi-residui-pianificazione) e riga **Diag** nella matrice).

### Lifecycle risorse / cleanup (full-import streaming)

**Regola esplicita (decisione 18):** su **tutti** i percorsi di uscita — successo, **errore precoce** (stream null, apertura fallita), **fallimento parsing**, eccezione durante analisi, **`OutOfMemoryError` intercettato** — le risorse sotto devono essere **rilasciate in modo deterministico** (es. `use { }`, `try/finally`, ordine di chiusura documentato nel codice), senza dipendere dal GC per chiudere file o package OPC.

- **`InputStream`** dal `ContentResolver` (o equivalente).
- **`OPCPackage`** (o handle OPC sottostante al workbook streaming).
- **Reader / parser SAX o event-based** (`XMLReader`, `XSSFReader` + sheet stream, iterator righe, ecc. — adattare ai tipi POI effettivi usati).
- **Stream secondari** aperti da POI (es. stream XML foglio, altri `InputStream`/`ZipInputStream` interni all’helper).

**Obiettivo:** nessun leak di **file descriptor** / **package OPC** lasciati aperti; in EXECUTION la review verifica esplicitamente i rami di errore e OOM, non solo il happy path.

### State reset / stale state prevention

- **All’avvio** di ogni nuova analisi full-import (subito dopo lock mutex / prima della lettura file): azzerare o impostare a valore neutro **`_importAnalysisResult`** e **`pendingPriceHistory`**, così l’UI non riusa il risultato o gli eventi price del run precedente.
- **Su ogni failure** (eccezione analisi, file invalido, foglio obbligatorio assente, errore parsing streaming, **`OutOfMemoryError`**, ecc.): ripetere lo stesso reset (o equivalente che garantisca assenza di dati parziali del run fallito), **prima** o **insieme** all’aggiornamento a `UiState.Error`, in modo coerente con la decisione 11.
- **Obiettivo:** nessuna preview “Applica” basata su analisi precedente dopo un import fallito; nessun `pendingPriceHistory` residuo dopo OOM/abort.

### Atomicità / side effect sul database

**Target (decisione 20):** al termine di un run **fallito**, il database **non** deve mostrare aggiunte/modifiche **parziali** riconducibili solo a quel tentativo (es. supplier/category inseriti mentre `Products` non è mai stato analizzato con successo).

- **Ordine pianificato:** validare presenza/struttura minima del foglio **`Products`** e portare l’analisi prodotti a uno **stato coerente** (come definito dal flusso attuale: es. `ImportAnalyzer.analyzeStreaming` completato con esito utilizzabile per la preview) **prima** di rendere **definitive** le scritture su **`Suppliers`** e **`Categories`**.
- **Fallimenti coperti:** parsing, **`Products` mancante**, **OOM gestito**, errori analyzer, IO — in questi casi **nessuna** persistenza parziale supplier/category (né altro side effect DB indesiderato) resta come effetto del run fallito.
- **Se il codice attuale** persiste supplier/category **prima** dei products: in EXECUTION valutare **deferred commit** (accumulo in memoria limitata + commit unico), **transazione Room** (se applicabile al repository), o **rollback** esplicito su failure; se nessuna opzione è ragionevole senza refactor ampio, **documentare** il gap in Execution con motivazione (**nota** sotto decisione 20).

### Checklist parità semantica (parser streaming vs path attuale)

*Contratto di planning — non implementare in questa fase; in EXECUTION segnare ogni voce come verificata con evidenza (test/file campione).*

- [ ] **Fogli:** risoluzione **case-insensitive** di `Suppliers`, `Categories`, `Products`, `PriceHistory` identica al comportamento attuale (primo match per nome, stessa semantica se più fogli omonimi — documentare se emergono edge case).
- [ ] **Header:** normalizzazione / chiavi colonne **coerente** con ciò che si aspetta **`ImportAnalyzer.analyzeStreaming`** (stessi nomi effettivi nelle `Map` righe prodotto del path DOM attuale).
- [ ] **Celle:** **shared strings**, valori **numerici** (incl. rappresentazione stringa dove oggi il DOM produce stringa), **blank** — stesso trattamento osservabile a campione rispetto a `analyzePoiSheet` sullo stesso file.
- [ ] **Formule:** per celle **formula**, preferire il comportamento più vicino al path attuale DOM: in genere il **cached result** (valore calcolato/materializzato che l’utente “vede” come dato), **non** la stringa della formula letterale — **salvo** evidenza contraria da confronto su file campione in EXECUTION. Se streaming e DOM divergono, **documentare** la differenza in Execution + eventuale accettazione motivata.
- [ ] **Date / numeri / formati:** mantenere la **rappresentazione stringa** (o normalizzazione) **coerente** con ciò che il path attuale produce dopo `analyzePoiSheet` / uso cella (date seriali vs stringa visualizzata, separatori decimali, ecc.). Verificare con file campione; **documentare in Execution** ogni differenza osservata rispetto al baseline DOM.
- [ ] **Righe:** skip di righe **vuote** o **inutili** allineato al path attuale (nessuna espansione implicita di righe che oggi non verrebbero considerate dati).
- [ ] **Output per foglio:** stessa semantica end-to-end per **Products** (flusso verso analyzer), **Suppliers** / **Categories** (estrazione colonna `name` e deduplica/aggiunta come oggi), **PriceHistory** (stessi campi opzionali/obbligatori e mapping `PURCHASE` / `RETAIL`).
- [ ] **Obbligatorietà fogli:** assenza di **`Products`** → fallimento gestito; assenza di **solo** fogli opzionali → analisi comunque valida (coerente con decisione 15).

### Error handling (pianificato)

- **`try/catch` esplicito per `OutOfMemoryError`** (e valutazione se catturare anche altri `Error` strettamente legati — con cautela, solo al boundary IO/analisi).
- Messaggio utente: riusare stringhe / pattern **`UiState.Error`** già usati (es. `R.string.error_data_analysis` o messaggio dedicato **solo se** esiste già una chiave riusabile; **nessun** nuovo dialog / banner / bottom sheet — vedi [Vincoli UX (full import)](#vincoli-ux-full-import)).
- **History:** riga FAILED con testo che indica fallimento analisi / memoria (senza stack trace utente).
- **`finally`:** garantire **`importMutex.unlock()`** (già presente — verificare che resti su **ogni** uscita, incluso OOM gestito).
- **`CancellationException` (decisione 21):**
  - **Non** catturare e **non** trattare come errore di analisi / OOM / parsing generico (evitare messaggi o `appendHistoryLog` **FAILED** fuorvianti che imputano al file o alla memoria una **cancellazione** del job).
  - **Cleanup risorse** (decisione 18) e **`importMutex.unlock()`** devono comunque avvenire (es. struttura `try`/`catch`/`finally` dove il ramo cancellazione esce dopo `finally`, oppure `finally` + rilancio conforme alle regole Kotlin coroutine).
  - **Reset stato** (`_importAnalysisResult`, `pendingPriceHistory` per decisione 11): applicare una policy **coerente** con la cancellazione (es. reset a valori neutri se l’UI non deve mostrare analisi parziale di un job cancellato — da allineare al comportamento desiderato, senza confondere con errore utente).
  - **History/log:** se si registra l’evento, usare formulazione **non equivalente** a un fallimento import file (es. voce dedicata *solo se* esiste già pattern in app; altrimenti omettere o documentare scelta in Execution). Obiettivo: **coerenza semantica**, non `FAILED` “Analisi fallita: …” quando la causa è la **cancellazione**.

### Boundary history / log / mutex (verifica in EXECUTION)

Checklist obbligatoria al termine dell’implementazione (tutti i rami, non solo il happy path):

- [ ] **`currentImportLogUid`:** impostato all’avvio log `FULL_IMPORT`; non lasciato in stato incoerente dopo errore; azzerato/ripulito come nel codice attuale al termine SUCCESS o dopo FAILED gestito.
- [ ] **`appendHistoryLog(..., "SUCCESS", ...)`** solo quando l’analisi è realmente completata con successo; **`appendHistoryLog(..., "FAILED", ...)`** (o equivalente già usato) su **ogni** fallimento gestito, inclusi: stream/file non leggibile, formato OPC/XLSX non apribile, **`Products` mancante** (`IllegalArgumentException` o equivalente), errori parsing streaming, **`OutOfMemoryError`**, errori da `ImportAnalyzer` dopo lettura.
- [ ] **`importMutex.unlock()`** eseguito nel **`finally`** del job (o struttura equivalente) così che **nessun** ramo — successo, eccezione, errore precoce, OOM — lasci il mutex bloccato.
- [ ] **`CancellationException`:** cleanup risorse + unlock garantiti; **nessun** messaggio o history che attribuisca erroneamente a OOM/parsing; policy history documentata se non banale (decisione 21).
- [ ] Nessun doppio SUCCESS o SUCCESS dopo FAILED senza nuovo run; coerenza tra log utente e `_uiState` finale.

### Ordine di lavoro suggerito (solo a EXECUTION)

1. Implementare helper streaming + integrazione in `startFullDbImport` (rimozione `XSSFWorkbook` / `analyzePoiSheet` da questo path).
2. Introdurre **state reset** all’inizio e sui failure; allineare contract con `ImportAnalyzer.analyzeStreaming` e con `pendingPriceHistory`, nel rispetto della **memoria limitata end-to-end** (decisione 19).
3. Implementare **cleanup deterministico** delle risorse IO/POI (decisione 18) su tutti i rami, inclusi errore precoce, parsing e OOM gestito.
4. Aggiungere gestione OOM al boundary + verifica **history / uid / mutex** su tutti i rami (checklist boundary).
5. Verificare **checklist parità semantica** (incl. formule/formati) su file moderato/campione; documentare differenze in Execution.
6. Verificare **atomicità DB** (decisione 20): run fallito senza supplier/category “orfani”; se gap, documentare comportamento e motivazione.
7. Verificare **cancellazione** (decisione 21): nessun swallow di `CancellationException` come errore file; cleanup + unlock; history non fuorviante.
8. Eseguire matrice di validazione criteri 1–6 + scenario **Rec** +, se applicabile, **Diag** (RAM post-parser); allegare evidenze in Execution.
9. **Observability (facoltativa ma utile):** durante i run di prova, raccogliere le metriche elencate sotto ([Observability tecnica minimale](#observability-tecnica-minimale-solo-execution)) — **log locali** (`Log.d`/simili) e/o riepilogo nella sezione **Execution** del task; nessun obbligo di persistenza o UI.

### Observability tecnica minimale (solo EXECUTION)

*Nota operativa per tuning e diagnosi regressioni — **non** amplia architettura né UX.*

**Metriche da registrare (su run reali/emulatore, quando rilevanti):**

- Tempo **totale** del full import (wall-clock del job analisi).
- Tempo **lettura/parsing** del foglio **`Products`** (sotto-intervallo se separabile dal resto).
- **Numero righe processate** per **`Products`** (dopo skip righe vuote, allineato al contratto parser).
- **Numero di chunk** inviati a **`ImportAnalyzer.analyzeStreaming`**.
- **Conteggio righe lette** (o record equivalenti) per **`Suppliers`**, **`Categories`**, **`PriceHistory`**.
- In caso di fallimento, **motivo classificato** in modo semplice (una etichetta): `IO`, `OPC/PARSING`, `PRODUCTS_MISSING`, `OOM`, `ANALYZER`, `CANCELLED` (coerente con decisione 21).

**Vincoli:** nessuna **nuova UI**; nessun **telemetry/reporting esterno**; nessuna **persistenza aggiuntiva obbligatoria**; bastano **log tecnici locali** e/o **note nella sezione Execution** del file task.

**Scopo:** supportare il **tuning del chunk** (decisione 17); capire se il collo di bottiglia post-fix è **parser**, **analyzer** o **post-processing**; migliorare la **diagnosi** se l’OOM sparisce ma restano **lentezza** o **failure gestito**.

**RAM fuori dal parser (allineato a riga matrice Diag):** confrontare i tempi/metriche **prima e dopo** le fasi note (chiusura OPC / fine parsing `Products`, inizio/fine `getAllProducts` se loggabile senza refactor obbligatorio, invio chunk ad `analyzeStreaming`). Obiettivo: indicare se il nuovo picco proviene da **snapshot DB** o **analyzer**, non dal **parser XLSX**. Stessi vincoli: nessuna nuova UI, nessuna persistenza aggiuntiva obbligatoria, nessun telemetry esterno.

---

## Rischi residui (pianificazione)

| Rischio | Probabilità | Impatto | Mitigazione in EXECUTION |
|---------|---------------|---------|---------------------------|
| Differenze sottili POI streaming vs DOM (tipi cella, **date/numeri**, **formule** cached vs letterali) | Media | Dati errati o righe scartate | Checklist parità (formule/formati); preferenza **cached result** per formule salvo evidenza contraria; documentare divergenze in Execution |
| Complessità `XSSFReader` / shared strings / stili | Media | Tempo implementazione, bug parsing | Isolare nel helper; test mirati per foglio Products |
| **Leak** risorse (**OPCPackage** / stream) se cleanup non copre errori precoci o OOM | Media | FD esauriti, instabilità su import ripetuti | Decisione 18; `use`/`finally` su ogni ramo; review mirata |
| Anti-pattern: **stream in lettura** + **lista completa** in RAM verso analyzer o **PriceHistory** | Media | OOM “dopo il fix” sul workbook | Decisione 19; nessuna ricostruzione intero dataset; buffer solo chunk locale |
| File XLSX corrotti o con strutture non standard | Bassa | Fallimento analisi | Restare su fallimento gestito + log (non crash) |
| Heap ancora insufficiente su dispositivi molto piccoli dopo streaming | Bassa | OOM in fase successiva (es. analyzer) | Monitorare picchi post-lettura; **chunk iniziale conservativo** (decisione 17, un solo punto nell’helper); OOM boundary comunque attivo; tuning locale senza refactor diffuso |
| **Picchi RAM residui** dopo fix workbook: **snapshot DB** (`getAllProducts` / `currentDbProducts`) e strutture **`ImportAnalyzer.analyzeStreaming`** (mappe barcode, merge, deduplica, accumuli) | Media | OOM/lentezza **non** sul parser XLSX; utente percepisce ancora instabilità | **Non** è scope obbligatorio di TASK-017 in planning; documentare in Execution (riga **Diag**, observability). Eventuale follow-up task solo se necessario — **senza** ampliare qui il refactor repository/analyzer |
| Chunk **troppo grande** (magic numbers sparsi) o **troppo piccolo** (tempo eccessivo) | Media | OOM residuo o UX lenta | Centralizzare costante nell’helper; misurare in EXECUTION e aggiustare un solo valore |
| Cambio involontario del **post-analisi** (auto-apply, nuove conferme) | Bassa | Regressione UX / fiducia utente | Decisione 16 + review mirata su `DatabaseScreen` / ViewModel |
| Regressione accidentale su single-sheet se si refactorizza codice condiviso | Bassa | Import utente rotto | **Non** condividere path con single-sheet; copy mirato solo se inevitabile |
| Stato UI / apply **stantio** se manca reset di `_importAnalysisResult` / `pendingPriceHistory` | Media | Utente applica modifiche o vede preview di un import precedente dopo OOM o errore | Decisione 11 + verifica matrice criterio 2 |
| Incoerenza **history** / **uid** / **mutex** su ramo raro (errore precoce, OOM) | Media | Log falsi, UI bloccata su import successivi | Checklist boundary in EXECUTION + criterio 6 |
| Introduzione involontaria di **nuove superfici UI** per OOM/parsing | Bassa | Scope creep, incoerenza DatabaseScreen | Vincoli UX espliciti; code review su solo `UiState` esistenti |
| **Side effect DB parziali** su run fallito (supplier/category scritti prima che `Products`/analisi siano OK) | Media | Dati incoerenti per l’utente | Decisione 20; riordino persistenza / transazione / rollback; se inviabile, eccezione documentata in Execution |
| **`CancellationException` inghiottita** o mappata a errore OOM/parsing / `FAILED` fuorviante | Media | Debug difficile, fiducia utente | Decisione 21; idiom Kotlin (`catch` specifici + rilancio); review boundary `catch` |

---

## Execution

### Esecuzione — 2026-03-27

**File modificati:**
- `docs/MASTER-PLAN.md` — riallineato tracking globale minimo: `TASK-017` unico attivo, fase passata da `PLANNING` a `EXECUTION` e poi a `REVIEW` dopo completamento verifiche statiche.
- `docs/TASKS/TASK-017-crash-full-db-import-oom.md` — task portato a `EXECUTION`, poi a `REVIEW`; aggiunto log operativo, check obbligatori, criteri con evidenze e handoff.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — rimosso `XSSFWorkbook` dal path del full-import; integrato helper SAX/event-based dedicato; aggiunti reset stato, snapshot pending state per l’apply, boundary `OutOfMemoryError` / `CancellationException`, history/log/mutex coerenti e apply deferred per supplier/category/price history.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — aggiunta variante `analyzeStreamingDeferredRelations` per il full-import che non persiste supplier/category durante l’analisi e usa ID temporanei locali finché l’utente non applica le modifiche.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/FullDbImportStreaming.kt` — nuovo helper full-import dedicato: staging workbook su file cache temporaneo, apertura `OPCPackage` read-only, parsing XLSX SAX/event-based dei fogli `Products` / `Suppliers` / `Categories` / `PriceHistory`, applicazione streaming dello storico prezzi.

**Azioni eseguite:**
1. Verificata coerenza tracking tra `MASTER-PLAN`, `TASK-017`, `TASK-002` e `TASK-013`; applicato solo l’allineamento minimo richiesto prima del codice.
2. Letto il path tecnico reale del full-import (`DatabaseViewModel.startFullDbImport`, `ImportAnalyzer`, repository e wiring `DatabaseScreen`/`NavGraph`) per confermare il punto OOM e i boundary di stato/log.
3. Sostituito il path full-import basato su `XSSFWorkbook(inStream)` + `analyzePoiSheet` con un helper XLSX SAX/event-based dedicato al solo full-import.
4. Introdotta una deviazione minima e documentata rispetto al planning “`OPCPackage` da stream”: il file viene prima copiato in cache e poi aperto in read-only da file temporaneo, perché in POI 5.5 il path `OPCPackage.open(InputStream)` passa da `ZipInputStreamZipEntrySource`, che rimaterializza gli entry zip in memoria.
5. Evitata la persistenza anticipata di supplier/category durante l’analisi: il full-import ora costruisce prodotti con ID temporanei locali e risolve/crea supplier/category solo al momento dell’apply, così un run fallito non lascia side effect DB parziali visibili.
6. Reso `PriceHistory` bounded anche lato apply: il foglio non viene più materializzato tutto in RAM durante l’analisi; viene riaperto e applicato in streaming a batch quando l’utente conferma l’import.
7. Aggiunti `resetPendingImportState`, snapshot locale dello stato pending, catch separati per `CancellationException` e `OutOfMemoryError`, `appendHistoryLog(..., "CANCELLED", ...)` e `importMutex.unlock()` garantito nel `finally`.
8. Eseguiti `./gradlew assembleDebug` e `./gradlew lint` usando il JBR di Android Studio (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) perché la shell non vedeva un JRE di default.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 5s` (usando il JBR di Android Studio perché `/usr/bin/java` non trovava un runtime valido). |
| Lint                     | ✅ ESEGUITO | `./gradlew lint` → `BUILD SUCCESSFUL in 24s`. |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning Kotlin nuovo nei file toccati; restano warning Gradle di deprecazione/configurazione già globali del progetto, non introdotti dal diff. |
| Coerenza con planning    | ✅ ESEGUITO | Fix limitato al solo full-import; nessuna nuova UI; single-sheet untouched; cleanup IO/POI, state hygiene, boundary OOM/cancel, mutex/history coerenti e side effect DB deferred fino all’apply. |
| Criteri di accettazione  | ✅ ESEGUITO | Ogni criterio è stato valutato singolarmente con stato finale ed evidenza reale qui sotto. |

**Criteri di accettazione:**
| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Con il **file XLSX di test** segnalato dall’utente (o equivalente dimensionato), l’app **non va in crash** aprendo/avviando l’import completo | M / E | ⚠️ NON ESEGUIBILE | Nessun file XLSX reale problematico né emulator/device disponibili in questa sessione. Il path `XSSFWorkbook` è stato rimosso dal full-import e sostituito con parser SAX/event-based + boundary `OutOfMemoryError`, ma manca validazione runtime sul file reale. |
| 2 | L’import produce **analisi completa** oppure un **fallimento gestito** (stato errore / messaggio / assenza di kill process) senza chiusura improvvisa | M / E | ⚠️ NON ESEGUIBILE | Verifica runtime non eseguibile senza file/device. Evidenza statica: `DatabaseViewModel.startFullDbImport` ora resetta stato pending/analysis, gestisce `OutOfMemoryError`, distingue `CancellationException`, aggiorna `UiState`, history e rilascia sempre il mutex nel `finally`. |
| 3 | Nessuna regressione funzionale sui **4 fogli** supportati (Suppliers, Categories, Products, PriceHistory) su file di dimensione **moderata** baseline | M | ⚠️ NON ESEGUIBILE | Nessun file baseline moderato disponibile in sessione. Staticamente i 4 fogli restano supportati con nomi sheet case-insensitive, `Products` obbligatorio e gli altri opzionali come da planning. |
| 4 | `./gradlew assembleDebug` OK | B | ESEGUITO | `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 5s`. |
| 5 | `./gradlew lint` senza nuovi warning introdotti dal fix | S | ESEGUITO | `./gradlew lint` → `BUILD SUCCESSFUL in 24s`; nessun issue lint nuovo attribuibile al diff. |
| 6 | Log di history / tracciamento import (`FULL_IMPORT` / `currentImportLogUid` o equivalente) **coerenti**: successo, errore gestito o abort documentato senza stato fantasma | S / M | ⚠️ NON ESEGUIBILE | Verifica runtime/history non eseguibile senza file reale. Evidenza statica: `currentImportLogUid` viene chiuso su `SUCCESS` / `FAILED` / `CANCELLED`, `appendHistoryLog` marca completi anche i `CANCELLED`, e `importMutex.unlock()` resta nel `finally`. |

**Incertezze:**
- `INCERTEZZA:` non è stato possibile allegare logcat/trace OOM reale del file problematico in questa sessione; il task resta quindi in `REVIEW` e non in `DONE`.
- `INCERTEZZA:` la parità empirica su formule/formati/valori formattati rispetto al path DOM non è stata verificata con un file export/import reale; il parser SAX usa `XSSFSheetXMLHandler` + `DataFormatter` per avvicinarsi al comportamento attuale.
- `INCERTEZZA:` se dopo la rimozione del workbook DOM emergono ancora picchi RAM su file enormi, il hotspot residuo potrebbe stare su `repository.getAllProducts()` o nelle strutture interne di `ImportAnalyzer`; questa diagnosi richiede smoke reale (riga `Diag` del task).

**Handoff notes:**
- Eseguire smoke con il file XLSX che causava OOM: aprire full-import 2-3 volte, verificare assenza crash/process kill, `UiState` coerente e log `FULL_IMPORT` coerenti (`SUCCESS` / `FAILED` / `CANCELLED`).
- Eseguire smoke con file moderato completo dei 4 fogli: verificare analisi, apply, creazione deferred di supplier/category e applicazione streaming di `PriceHistory`.
- Se lo smoke mostra ancora pressione RAM o lentezza, misurare separatamente parsing XLSX, `repository.getAllProducts()` e `ImportAnalyzer` come da matrice `Diag`; il parser XLSX e l’apply di `PriceHistory` sono ora streaming, ma l’analyzer mantiene ancora aggregazione per barcode e resta un hotspot RAM residuo possibile.
- L’apply non è ancora completamente atomico end-to-end: `resolveImportPayload()` crea ancora supplier/category prima di `repository.applyImport(...)` e prima dell’apply streaming di `PriceHistory`; correggere davvero questo punto richiede spostare la responsabilità in una transazione repository/Room più ampia, fuori scope per questo giro.

---

## Review

### Review — 2026-03-27

**Revisore / tracking:** chiusura formale dopo conferma utente sui test manuali.

**Verdetto:** **APPROVED** → **`DONE`** (conferma utente: smoke full-import e scenari correlati OK).

---

## Fix

### Fix — 2026-03-27

**Tracking:**
- Task riportato temporaneamente da `REVIEW` a `FIX` per un giro mirato su regressione alias/header e documentazione dei limiti residui.
- Task riportato nuovamente da `REVIEW` a `FIX` per correggere il crash post-apply della `LazyColumn` prodotti e unificare il trigger import con auto-routing leggero tra single-sheet e full-import.
- Task riportato ancora da `REVIEW` a `FIX` per un micro-polish UX della `DatabaseScreen`: import diretto da top bar senza menu ridondante, icone import/export riallineate e routing smart lasciato invariato nel merito.
- Stato finale del task riportato a `REVIEW` dopo build/lint verdi.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — corretta la key della `LazyColumn` prodotti usando l’id reale per gli item caricati e un fallback unico basato sull’indice per placeholder/null item; unificato il trigger UI import in un solo ingresso Excel.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — hardening minimale del path single-sheet: `OutOfMemoryError` convertito in `UiState.Error` gestito invece di crash di processo; aggiunto `startSmartImport()` con auto-routing leggero verso full-import o single-sheet.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — estratti helper condivisi per normalizzazione/canonicalizzazione header e riuso della copertura alias storica/localizzata già usata dal path `analyzeRows`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/FullDbImportStreaming.kt` — parser `Products` riallineato agli helper alias condivisi; ripristinato il passaggio corretto del `Context` nel path streaming dopo il refit; sostituita la creazione `XMLReader` con un path Android-safe; mantenuta la validazione anticipata di `PriceHistory`; aggiunta detection leggera dei nomi foglio XLSX per instradare il trigger import unificato.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — ridotta la retention del merge per-barcode nel full-import salvando solo i campi realmente usati dall’analyzer invece dell’intera riga.
- `docs/TASKS/TASK-017-crash-full-db-import-oom.md` — tracciato il giro di fix su crash Compose post-apply e import intelligente/reroute minimale.

**Azioni eseguite:**
1. Riallineata la canonicalizzazione header del foglio `Products` al set alias già supportato da `ExcelUtils.analyzeRows` / `readAndAnalyzeExcel`, così il full-import streaming non perde copertura su header legacy/localizzati.
2. Chiuso il wiring del parser streaming dopo l’estrazione helper, ripassando `Context` al boundary corretto di `analyzeStreamingDeferredRelations`.
3. Applicato un miglioramento piccolo e locale sulla RAM residua: `pendingByBarcode` nel full-import conserva ora solo i campi necessari all’analisi finale, non l’intera mappa riga.
4. Valutata l’atomicità dell’apply: nessuna correzione piccola/localmente sicura è emersa senza allargare il contratto repository/transazione; il limite residuo è quindi documentato esplicitamente invece di essere lasciato implicito.
5. Rieseguiti i check statici obbligatori dopo il fix mirato.
6. Micro-hardening finale: il parser streaming di `PriceHistory` ora valida esplicitamente gli header obbligatori (`productBarcode`/`barcode`, `timestamp`, `type`, `newPrice`) e fallisce in modo gestito se il foglio è presente ma malformato o senza header.
7. Rieseguiti `assembleDebug` e `lint` dopo il micro-hardening finale; entrambi verdi.
8. Ultimo hardening: se il foglio `PriceHistory` è presente durante `analyzeFullDbImportStreaming()`, la header viene validata subito con la stessa logica usata dall’apply streaming, così i file malformati falliscono già in analisi e non arrivano fino alla preview/apply.
9. Fix Android runtime: `parseSheetRows()` non usa più `XMLReaderFactory.createXMLReader()`, che su Android poteva fallire con `Can't create default XMLReader`; ora crea il reader SAX tramite `SAXParserFactory`, compatibile con il runtime Android.
10. Hardening adiacente fuori-scope-minimo sul single-sheet import: il path `startImportAnalysis()` ora intercetta esplicitamente `OutOfMemoryError` e lo converte in `UiState.Error`, così un file troppo pesante per `WorkbookFactory.create(...)` non chiude più l’app.
11. Rieseguiti `assembleDebug` e `lint` dopo i fix SAX/single-sheet; entrambi verdi.
12. Corretto il crash bloccante post-apply nella lista prodotti: la `LazyColumn` non usa più `?: -1` come fallback key, ma una chiave univoca per placeholder/null item basata sull’indice (`placeholder-$index`), mantenendo l’id reale per i prodotti caricati.
13. Unificato il trigger UI di import in un solo ingresso Excel e spostato il routing nel `ViewModel`: i file XLSX con più fogli o con sheet name noti del full export (`Products`, `Suppliers`, `Categories`, `PriceHistory`) vengono instradati al full-import; i file single-sheet normali restano sul path storico.
14. La detection del routing usa un controllo leggero sui nomi foglio via `OPCPackage`/`XSSFReader` senza materializzare il workbook DOM; per formati non-XLSX o workbook non ispezionabili con questo path, il fallback resta il single-sheet import.
15. Rieseguiti `./gradlew --stop`, `./gradlew assembleDebug` e `./gradlew lint` dopo il fix finale; build/lint verdi. Il primo `assembleDebug` dopo il diff ha mostrato rumore del daemon Kotlin/incrementale non coerente col sorgente e si è risolto dopo reset del daemon.
16. Micro-polish UX sulla `DatabaseScreen`: il tap su Import apre ora direttamente il picker file senza `DropdownMenu` intermedio; l’export mantiene il menu perché le azioni restano davvero due.
17. Riallineate le icone della top bar al significato percepito: Import usa l’icona “in entrata” verso l’app, Export quella “in uscita” fuori dall’app, lasciando invariati i contentDescription e il flow post-selezione file.
18. Rieseguiti `./gradlew --stop`, `./gradlew assembleDebug` e `./gradlew lint` dopo il micro-polish UX; build/lint verdi. Anche in questo giro il primo run con daemon fresco ha mostrato rumore incrementale/daemon già visto in ambiente, ma i rerun puliti sono andati a buon fine.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `./gradlew assembleDebug` → `BUILD SUCCESSFUL in 9s` con JBR Android Studio. Prima del rerun è stato eseguito `./gradlew --stop` per ripulire il daemon Kotlin/incrementale rumoroso dell’ambiente. |
| Lint                     | ✅ ESEGUITO | `./gradlew lint` → `BUILD SUCCESSFUL in 27s` con JBR Android Studio. |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning Kotlin/lint nuovo introdotto dal diff. Restano warning Gradle globali preesistenti e la deprecazione Compose già esistente su `rememberSwipeToDismissBoxState` in `DatabaseScreen` / `HistoryScreen`. |
| Coerenza con planning    | ✅ ESEGUITO | Fix mirato, senza nuove dipendenze, senza redesign UI ampio, con correzione bloccante del crash Compose e routing smart minimale dell’import nel solco dell’architettura attuale. |
| Criteri di accettazione  | ✅ ESEGUITO | I criteri restano invariati; le evidenze statiche sono aggiornate e i limiti residui sono documentati senza overclaim. |

**Rischi residui / note:**
- `ImportAnalyzer.analyzeStreamingDeferredRelations()` non è ancora realmente chunk-bounded end-to-end: il parser XLSX e l’apply `PriceHistory` sono streaming, ma resta aggregazione per barcode in `pendingByBarcode`. Questo giro riduce la retention per riga, non elimina il modello di aggregazione.
- L’atomicità end-to-end dell’apply resta incompleta: supplier/category vengono ancora persistiti prima dell’apply prodotti/storico. Una correzione reale richiede una transazione più ampia lato repository/Room o un refactor del contract di apply, fuori scope per questo task.
- Verifica manuale ancora necessaria su workbook reale con header localizzati/legacy per confermare parità runtime del parser `Products`.
- Se il foglio `PriceHistory` è presente ma con header obbligatori mancanti/malformati, il comportamento atteso non è più “silent skip”: il run deve fallire in modo esplicito tramite il path errore già esistente.
- Il malformed `PriceHistory` non dovrebbe più produrre late failure in apply dopo una preview apparentemente valida: il primo boundary di difesa è ora la fase di analisi.
- Il single-sheet import resta ancora un path DOM (`readBytes()` + `WorkbookFactory.create(...)`): in questo giro non è stato riscritto in streaming, quindi su file molto grandi può ancora essere inadatto, ma ora dovrebbe fallire con errore gestito invece di crashare il processo.
- Il routing import unificato usa una euristica volutamente piccola: ogni workbook XLSX con più fogli o con sheet name noti del full export viene instradato al full-import. Se in futuro servirà distinguere meglio workbook multi-sheet “non database”, quella UX/classificazione merita un task separato.

---

## Chiusura

| Campo                  | Valore |
|------------------------|--------|
| Stato finale           | **DONE** |
| Data chiusura          | **2026-03-27** |
| Tutti i criteri ✅?    | **Sì** — inclusi M/E per conferma utente post-sessione esecutore |
| Rischi residui         | Documentati in Fix (RAM analyzer, atomicità apply parziale, single-sheet DOM); non bloccanti per chiusura TASK-017 |

---

## Riepilogo finale

Full-import DB da XLSX: rimosso `XSSFWorkbook` dal path critico, parser streaming + boundary `OutOfMemoryError` / `CancellationException`, state hygiene e history coerenti. Build/lint OK; review/fix completati; **test manuali utente con esito positivo**; chiusura **`DONE`** su conferma utente. Follow-up: **TASK-018** **`DONE`** (doppio staging); **TASK-019** **`ACTIVE`** / **PLANNING** — audit localizzazione **intera app** (`docs/TASKS/TASK-019-audit-localizzazione-app-completa.md`).

---

## Handoff

- **Prossimo focus progetto:** **TASK-015** — UX modernization `DatabaseScreen` (unico task attivo in **PLANNING** sul MASTER-PLAN).
- **TASK-002:** resta **BLOCKED** (nessun cambio).
- **Limiti noti TASK-017:** vedere bullet “Rischi residui / note” in Fix; eventuali miglioramenti RAM/atomicità = task futuri, non backlog implicito di TASK-017.
