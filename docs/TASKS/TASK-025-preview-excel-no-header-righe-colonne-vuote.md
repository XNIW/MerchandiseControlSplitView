# TASK-025 — Preview Excel senza header esplicito: rimozione righe vuote e colonne strutturali inutili

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-025 |
| Stato                | **DONE** |
| Priorità             | **MEDIA-ALTA** |
| Area                 | Import / Excel / Preview / qualità visualizzazione |
| Creato               | 2026-04-03 |
| Ultimo aggiornamento | 2026-04-03 — **chiusura finale:** review planner **APPROVED**, test manuali **passati** (conferma utente), documentazione allineata, stato → **`DONE`**. |

---

## Dipendenze

- **TASK-004** (`DONE`) — baseline test JVM su `ExcelUtils` / `ExcelViewModel` / `DatabaseViewModel`: obbligatoria in Execution se si tocca il parsing o il flusso preview.
- **TASK-024** (`DONE`) — compatibilità workbook `.xls` legacy / `.xlsx` Strict OOXML: questo task è un follow-up distinto e deve preservarne i fix senza regressioni.
- **Ortogonalità:** nessun cambio a DAO / Room / schema / NavGraph / repository salvo necessità reale documentata; default: **fuori scope**.

---

## Scopo

Correggere il caso reale del file **`EROORE3-Dreamdiy.xlsx`**, che oggi in **preview / pre-generazione** mostra molte **righe bianche** e **colonne inutili**, nonostante il codice dell'app punti a ripulire il risultato tabellare prima della visualizzazione.

Il task è mirato a:

1. verificare il **path reale** usato dalla preview;
2. spiegare con evidenza tecnica perché il file produce righe/colonne vuote;
3. applicare in Execution la **fix minima corretta** nel parser (**normalizzazione POI condivisa** tra i path che leggono il foglio, non solo patch isolate in `analyzeRows`), non nella UI, salvo prova contraria nel guardrail UI;
4. evitare regressioni sui file `.xls` / `.xlsx` già compatibili.
5. **non peggiorare** la funzionalità attuale di **identificazione** header/colonne e mapping verso le chiavi di dominio (dettaglio: sezione dedicata sotto).

### Focus del task (recap — anti–scope creep)

Per review e chiusura, il perimetro resta volutamente stretto:

| Pilastro | Contenuto |
|----------|-----------|
| **Cleanup strutturale parser-side** | Trim coda celle, righe vuote, colonne interamente vuote — **single source of truth**, vedi Strategia |
| **Non regressione identificazione** | Comportamento attuale di alias, `headerSource` / `headerTypes`, colonne essenziali, mapping chiavi **inalterato o migliorato solo per effetto del dataset meno rumoroso** |
| **Stabilità contratto pubblico** | Stesse aspettative verso ViewModel/UI salvo dataset normalizzato documentato |
| **Nessun mascheramento UI** | Nessuna fix che nasconda dati sporchi lato parser; UI solo in deroga documentata se parser già corretto |

---

## Target issue / fixture reale

| Voce | Dettaglio |
|------|-----------|
| File target | `EROORE3-Dreamdiy.xlsx` |
| Flusso target minimo | `PreGenerateScreen` → `ExcelViewModel.loadFromMultipleUris` → `readAndAnalyzeExcel` |
| Obbligo in Execution | riprodurre il problema reale nel flusso preview e registrare il dataset generato prima della griglia UI |
| Definizione di "risolto" | il file si apre in preview senza la coda di righe vuote e senza colonne totalmente inutili nel risultato tabellare finale |

---

## Evidenza tecnica raccolta in planning

### File reale

- Il file è un **`.xlsx` OOXML transitional** normale, non Strict OOXML, non HSSF, non evidentemente corrotto.
- Il workbook contiene un solo foglio: `YGO2603274845`.
- L'analisi della struttura XML mostra:
  - **86 righe con dati reali**
  - **889 elementi `<row/>` vuoti** in coda nel `sheetData`
  - celle realmente usate fino alla colonna **`H`**
- Il workbook contiene inoltre:
  - molte **immagini** ancorate alla colonna **`A`**
  - molte **merge cell** `D:E`

### Effetto pratico sul file target

- La colonna **`A`** risulta vuota nel dato tabellare perché il contenuto visivo reale e' l'immagine prodotto.
- La colonna **`E`** risulta spesso vuota perche' il workbook usa merge `D:E`; nel parsing testuale quella colonna resta un placeholder.
- La preview eredita anche le **889 righe vuote** perche' il path reale di parsing non le scarta nel ramo senza header esplicito.

---

## Causa probabile repo-grounded

### Path reale coinvolto

- `PreGenerateScreen`
- `ExcelViewModel.loadFromMultipleUris` / `appendFromMultipleUris`
- `DatabaseViewModel` (import database — chiama anch'esso `readAndAnalyzeExcel`)
- `ExcelUtils.readAndAnalyzeExcel`

### Punto critico individuato

Nel path POI di `readAndAnalyzeExcel`, il codice:

- itera il foglio e aggiunge tutte le righe lette, comprese quelle vuote;
- passa poi il risultato a `analyzeRows`.

In `analyzeRows`, il file `EROORE3-Dreamdiy.xlsx` entra molto probabilmente nel ramo:

- **`hasHeader = false`**

perché la prima riga del foglio e' gia' una riga dati e non esiste una riga header separata sopra.

In quel ramo:

- `dataRows = rows`
- non viene filtrata la coda di righe vuote
- la rimozione delle colonne vuote oggi e' applicata solo al ramo `hasHeader = true`

La causa principale del bug e' quindi molto probabilmente una **normalizzazione incompleta del risultato tabellare quando manca un header esplicito**, non il rendering Compose.

**Nota planning (per EXECUTION):** la correzione **non** va limitata a «aggiungere filtri dentro `analyzeRows`» come unico luogo di verità. Va pianificata una **normalizzazione POI condivisa** tra il path **`readAndAnalyzeExcel`** (preview / pre-generazione / import DB) e **`analyzePoiSheet`** (usato dai test JVM), così i due percorsi non divergono dopo il fix. La logica di post-processing tabellare resta **parser-first** e a **diff minimo** (estrazione/riuso helper interno a `ExcelUtils.kt` o equivalente, senza refactor architetturale inutile).

**Nota review tecnica (2026-04-03):** `analyzePoiSheet` **non ha chiamanti in produzione** — è usata solo da `ExcelUtilsTest.kt`. Esegue **già** il trimming corretto (skip righe vuote POI null, `dropLastWhile` celle vuote in coda, scarto righe interamente blank). Il bug produzione è **solo** nel path `readAndAnalyzeExcel` → `analyzeRows`, dove il loop POI non applica queste normalizzazioni. Lo helper condiviso deve **estrarre** il comportamento di `analyzePoiSheet` e **applicarlo** al loop POI di `readAndAnalyzeExcel`. Chiamanti produzione di `readAndAnalyzeExcel`: `ExcelViewModel.loadFromMultipleUris`, `ExcelViewModel.appendFromMultipleUris`, `DatabaseViewModel` (import database).

---

## Non-obiettivi

- rendering immagini Excel in preview
- ricostruzione completa delle merge cell
- redesign della tabella preview
- modifiche a `ZoomableExcelGrid.kt` / `PreGenerateScreen.kt` **salvo** la deroga in guardrail UI sotto
- modifiche a DAO / Room / schema / repository / NavGraph
- nuove dipendenze Gradle
- refactor architetturali ampi

---

## Perimetro file candidato (Execution)

| File | Ruolo atteso |
|------|--------------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` | fix minima sul path reale di parsing e normalizzazione |
| `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` | eventuale wiring minimo o supporto test |
| `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` | test bug reale e non regressione |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` | test comportamento preview se necessario |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` | solo se il parsing condiviso impatta anche quel flusso |

---

## Guardrail UI (scope)

- **`ZoomableExcelGrid.kt`** e **`PreGenerateScreen.kt` restano fuori scope** in PLANNING e in EXECUTION **per default**.
- Eventuali ritocchi UI/UX in deroga sono ammessi **solo** come **polish minimo** e **solo se realmente necessari**; **non** si sposta la responsabilità del bug dal **parser** alla **preview**.
- **Vietate** fix **cosmetiche** che nascondono dati sporchi in griglia (es. nascondere righe/colonne in UI) **invece** di correggerli **alla fonte** nel parser quando il dataset parser-side è ancora errato.

### Regola decisionale (parser vs UI)

| Condizione | Azione |
|------------|--------|
| Dataset **parser-side** già **corretto** (evidenza Fase 0 + ispezione strutture verso UI), ma la preview mostra ancora righe/colonne **spurie** | Si può **aprire la deroga UI** documentata: tocchi minimi a `ZoomableExcelGrid` / `PreGenerateScreen` solo dopo prova concreta. |
| Dataset **parser-side** ancora **sporco** o incoerente | **UI fuori scope**; fix **solo parser-side** fino a normalizzazione corretta. |

In ogni deroga UI: documentare evidenza nel log **Execution** e limitare le modifiche al **minimo necessario**.

---

## Strategia

### Principi guida

- prima capire il path reale attivo, poi correggere
- diff minimo ma sufficiente
- parser first
- niente overengineering

### Cleanup strutturale vs inferenza semantica (perimetro netto)

| Ambito | Cosa include | Scope TASK-025 |
|--------|----------------|----------------|
| **Cleanup strutturale tabellare** | Rumore di layout: trimming celle finali vuote per riga, righe completamente vuote, rimozione colonne **totalmente** vuote nel dato parser-side | **Dentro scope** |
| **Inferenza semantica** | Identificazione header, alias, `headerSource` / `headerTypes`, colonne essenziali, mapping verso chiavi di dominio | **Fuori scope** come oggetto di modifica; solo **non regressione** rispetto allo stato attivo |

Il cleanup strutturale **non** deve diventare pretesto per modificare o «migliorare» euristiche semantiche: nessun tuning di riconoscimento header o di mapping oltre l’effetto **indiretto e necessario** del dataset già ripulito (stesse regole semantiche, input tabellare meno rumoroso).

### Scope creep — niente miglioramenti semantici opportunistici

- Se in Execution emergono idee di **miglioramento** a euristiche di **header**, **mapping**, **identificazione** o **alias** che **non** sono **strettamente necessarie** al bugfix (rimozione rumore strutturale), **non** vanno incluse in TASK-025.
- Annotarle nel log **Execution** come **follow-up separato** (nuovo task / backlog) con descrizione e beneficio atteso, **senza** mescolarle nel diff del fix core.

### Normalizzazione POI condivisa (preprocessing desiderato)

Obiettivo: **un solo comportamento** di normalizzazione dopo la lettura POI, riusato da **`readAndAnalyzeExcel`** e **`analyzePoiSheet`**, per evitare che un path applichi trimming/righe e l’altro no.

**Equivalenza tra path (vincolo esplicito):**

- **A parità di input tabellare** estratto da POI (stesso foglio stesso contenuto celle testuali), i due entrypoint devono produrre lo **stesso output normalizzato** per tutti gli aspetti coperti dal **cleanup strutturale** (trim coda celle, righe vuote, colonne totalmente vuote, rettangolarizzazione post-cleanup ove applicata).
- Dopo il fix **non** devono restare **divergenze silenziose** tra `readAndAnalyzeExcel` e `analyzePoiSheet` su quel sottoinsieme: se un path pota e l’altro no, è un difetto da correggere in Execution (test incrociati o confronto helper condiviso).

**Single source of truth (SSoT) del cleanup strutturale:**

- In Execution va **evitata** una logica di cleanup **spezzata** e **ridondante** in più punti del flusso (es. stesso trimming in un ramo e duplicato altrove con rischio di drift). Il cleanup strutturale deve concentrarsi in **un percorso condiviso** (helper / funzione unica) invocato dai path POI pertinenti, non replicato «a pezzi».
- La normalizzazione condivisa deve risultare **leggibile in review**: **responsabilità chiare** (chi fa cosa, in quale ordine), nomi e confini espliciti, così **path simili** futuri non reintroducano divergenze per copia-incolla parziale.

**Ordine del preprocessing (vincolante nel planning):**

1. **Trimming delle celle finali vuote** per ogni riga (ridurre la «coda» di celle vuote a destra senza alterare celle non vuote in mezzo).
2. **Scarto delle righe completamente vuote** (inclusa la coda strutturale di `<row/>` vuoti come nel file target).
3. **Eventuale rettangolarizzazione** (es. allineare la larghezza delle righe al massimo utile) **solo dopo** i passi 1–2, non prima.

### Direzione preferita

Il progetto possiede gia' un helper con comportamento **parzialmente** coerente con il trimming desiderato:

- `analyzePoiSheet` scarta righe vuote e trimma le celle finali vuote

La preview reale pero' usa:

- `readAndAnalyzeExcel`

La strategia preferita in Execution e' quindi:

1. **estrare o consolidare** la normalizzazione POI in un percorso **condiviso** usato da entrambi i path (senza refactor architetturale inutile);
2. applicare lo stesso preprocessing **prima** di `analyzeRows` / uso downstream, così `hasHeader = true` e `hasHeader = false` operano su dati già coerenti;
3. completare la pulizia **anche** nel ramo `hasHeader = false` (righe vuote, colonne totalmente vuote) **rispettando il vincolo di allineamento** sotto;
4. lasciare invariata la griglia UI se il dataset normalizzato risulta gia' corretto (guardrail UI).

---

## Vincolo tecnico — allineamento strutture (header / mapping UI)

Se in Execution si rimuovono **colonne totalmente vuote** anche nel ramo **`hasHeader = false`**, le seguenti strutture devono restare **sempre allineate** (stessa cardinalità colonne, stessi indici semantici) per non rompere **`headerTypes`**, **preview**, **header mapping** e affini nella UI:

- **`header`**
- **`headerSource`**
- **`dataRows`**

Ogni potatura di colonne va applicata in modo **atomico** su tutte le rappresentazioni che partecipano al contratto verso il ViewModel/UI, oppure dopo un passo esplicito che le sincronizza. Documentare in Execution come viene garantito l’allineamento.

---

## Vincolo — stabilità del contratto pubblico

- **Nessun cambio non necessario** a firme pubbliche, tipi di ritorno o forma del **contratto** verso ViewModel/UI oltre quanto indispensabile per passare il dataset normalizzato.
- **`readAndAnalyzeExcel`** e il **flusso preview** (caricamento → analisi → stato esposto al ViewModel) devono mantenere il **medesimo comportamento pubblico** atteso dai chiamanti, **salvo** il cleanup del dataset previsto da questo task (meno righe/colonne rumorose, stessa semantica di identificazione per input equivalenti dopo normalizzazione).
- Eventuali estensioni o breaking change al contratto vanno **evitate**; se in Execution emergesse un adattamento inevitabile, va **documentato**, motivato come strettamente necessario e coperto da test.

---

## Definizioni operative — vuoto (parser-side)

Per tutto il task, «vuoto» si intende sul **dato testuale parser-side**, **dopo** il trimming strutturale delle celle finali vuote per riga (e coerente con le convenzioni di stringa già usate nel codice, es. trim se già applicato altrove nello stesso path).

- **Cella vuota:** nessun contenuto testuale significativo secondo le regole **esistenti** del parser (nessuna nuova euristica che tratti come vuoti valori che oggi sono considerati **validi**).
- **Riga completamente vuota:** tutte le celle della riga (nel contesto dopo trimming di coda) sono vuote al senso sopra.
- **Colonna totalmente vuota:** per **ogni** riga del risultato tabellare considerato per il cleanup, la cella in quell’indice di colonna è vuota al senso sopra.

**Vincoli:**

- **Nessuna regola speciale** che classifichi come vuoti valori validi (es. `"0"`, spazi significativi se oggi non trimmati, formati numerici, ecc.) oltre quanto già definito nel codice **prima** di questo task.
- **Nessuna logica implicita o ad hoc** legata al solo layout di `EROORE3-Dreamdiy.xlsx`: le definizioni devono valere per **qualsiasi** workbook.

---

## Vincolo — non regressione identificazione (header / colonne / mapping)

La fix **non deve peggiorare** la logica di identificazione **già in uso** nell’app. In Execution va trattata come vincolo esplicito di non regressione su:

- **riconoscimento alias header** (sinonimi / varianti riconosciute oggi)
- **inferenza e mapping** di **`headerSource`** / **`headerTypes`**
- **identificazione colonne essenziali** (le regole attuali che marcano/usano colonne critiche per il flusso)
- **mapping finale** verso chiavi di dominio (es. **barcode**, **productName**, **purchasePrice**, e le altre già mappate nel codice esistente)

Ogni modifica al preprocessing tabellare deve preservare o migliorare solo i casi previsti dal task (righe/colonne strutturalmente vuote), **senza** alterare euristiche di header o di colonna in modo da far fallire casi oggi validi. Evidenza attesa: test JVM e/o fixture esistenti che coprono identificazione + controlli manuali mirati documentati se necessario.

**Ramo senza header esplicito già compatibile:** la non regressione vale **anche** per file che **prima** del fix entravano in **`hasHeader = false`** e funzionavano correttamente (preview, mapping, flusso successivo): il nuovo cleanup **non** deve peggiorarli rispetto alla baseline pre-fix (stessi test o confronto documentato).

---

## Regola di rimozione colonne (generale, non ad hoc)

- Una colonna è **candidata alla rimozione solo se** risulta **totalmente vuota** nel **risultato tabellare finale parser-side** (dopo il preprocessing condiviso e prima della UI), secondo la sezione **Definizioni operative — vuoto (parser-side)**.
- La regola deve essere **generale** e applicabile a qualsiasi workbook conforme; **nessuna eccezione** mirata solo a `EROORE3-Dreamdiy.xlsx` o al suo layout.
- **Vietato** introdurre logica speciale di **ricostruzione merge** o di **immagini** per «sistemare» colonne: merge e drawing restano fuori obiettivo (come in Non-obiettivi).
- Una colonna **non** va rimossa perché «visivamente poco utile» o perché il contenuto è solo immagine in Excel: si rimuove **solo** se nel **dato testuale parser-side finale** è **davvero** completamente vuota.

### Ordine colonne (vincolo)

- La fix può **solo** **rimuovere** colonne **totalmente vuote**; **non** deve **riordinare** le colonne (nessun sort, shuffle o normalizzazione dell’ordine oltre la sola eliminazione).
- Tra le colonne **superstiti**, l’**ordine relativo** e gli **indici semantici rispetto al foglio originale** (a meno delle colonne eliminate perché interamente vuote) **non** devono essere alterati: due colonne non vuote che erano adiacenti restano adiacenti nello stesso ordine dopo il drop delle colonne vuote intercalate.

---

## Guardrail — dataset quasi vuoto o vuoto dopo cleanup

In casi limite, dopo il cleanup strutturale il risultato può contenere **poche righe**, **una sola riga**, oppure **nessuna riga** dati (foglio effettivamente vuoto o solo rumore strutturale rimosso).

- **Rimozione colonne totalmente vuote:** la regola resta **la stessa** (solo colonne **interamente vuote** sul risultato tabellare su cui si applica il cleanup, secondo le definizioni operative). Con **zero righe** dopo lo scarto righe vuote, non devono restare «colonne fantasma» incoerenti rispetto a `header` / `headerSource` / `dataRows`; se il prodotto oggi gestisce il vuoto in un modo definito, **non introdurre** potature che creino stati intermedi **instabili** (es. cardinalità colonne diverse tra strutture, header sintetici incoerenti) rispetto a quel comportamento.
- **Evitare potature aggressive:** nessun passaggio aggiuntivo che, sotto pretesto di «pulizia», elimini righe o colonne **non** coperte dalla definizione di vuoto / regola colonne, né che renda la preview o il merge multi-file **incoerenti** rispetto al contratto attuale.
- **Contratto preview:** in questi casi il comportamento finale deve restare **coerente con il contratto attuale** del flusso preview (stessi invarianti attesi da ViewModel/UI oggi per foglio vuoto / quasi vuoto, senza introdurre nuovi stati «magici»).
- **Nessuna nuova logica «intelligente»** o comportamento di prodotto **non richiesto** (es. messaggi speciali, fallback semantici, auto-correzioni header) solo perché il dataset è vuoto: restare sul cleanup strutturale + coerenza esistente.
- Se emerge una **scelta non ovvia** (ambiguità tra due comportamenti leciti), **documentarla esplicitamente nel file task** (Execution / Handoff) invece di introdurla **implicitamente** nel codice senza traccia.

---

## Efficienza — normalizzazione POI condivisa

- La normalizzazione condivisa deve restare a **complessità lineare** sul numero di celle/righe effettivamente attraversate (o comunque **semplice**: niente algoritmi pesanti non necessari).
- Evitare **scansioni ripetute inutili** dello stesso foglio/dataset e **duplicazioni evitabili** di liste grandi (es. copie intere della griglia quando basta un passaggio o viste riusabili).
- Nessun refactor architetturale obbligatorio; in Execution **non** introdurre cleanup **ridondanti** (stesso passaggio due volte su path diversi senza motivo) né passaggi **costosi** rispetto al beneficio — allineato al principio **SSoT** del cleanup strutturale (Strategia).

---

## Multi-file — coerenza preview e header

In Execution va verificato esplicitamente che il cleanup:

- **non renda incompatibili** tra loro file che **prima** del fix erano **compatibili** nello stesso flusso (stesso merge preview / stesso ordine di colonne atteso dall’utente).
- se file diversi hanno **code di righe vuote** o **colonne strutturalmente vuote** di lunghezza/diversa posizione, il **merge preview** resti **coerente** (nessun allineamento incoerente introdotto solo dal nuovo preprocessing).
- la **compatibilità degli header tra file multipli** (unione / confronto colonne tra file nel flusso attuale) **non deve peggiorare** dopo il fix.

Documentare nel log Execution eventuali scenari multi-file provati e risultato.

---

## Note opzionali UX/UI (fuori obiettivo principale)

Il **bugfix core** del task era la normalizzazione / cleanup parser-side (deroga UI **solo** se provata necessaria dal guardrail). In chiusura: fix applicata senza deroga UI.

- **Polish UI/UX non strettamente necessario** al fix parser-side (es. copy, micro-layout, messaggi informativi «nice to have») va annotato come **follow-up opzionale separato** (nuovo task o backlog), **non** inglobato in TASK-025.
- **Non** includere in chiusura TASK-025 modifiche UI che non siano **indispensabili** per risolvere il difetto accettato o per la deroga documentata (parser OK + preview spuria).

Se durante EXECUTION emergesse un miglioramento UX utile ma non necessario al bugfix, registrarlo nel log Execution come **proposta di follow-up**, con perimetro chiaro.

---

## Strumentazione debug / preflight (Execution)

- Eventuali **log**, **metriche temporanee**, **diagnostica** o codice solo per ispezione inseriti durante Execution devono, **prima della chiusura del task** (passaggio a `REVIEW` / `DONE`): essere **rimossi** dall’app / dal path di produzione, **oppure** confinati in contesti **non user-visible** (es. solo test JVM, build type debug flaggato, `if (BuildConfig.DEBUG)` con policy esplicita documentata nel task).
- Obiettivo: niente rumore persistente o leakage di dettagli interni in release / esperienza utente normale.

---

## Criteri di accettazione

1. **`EROORE3-Dreamdiy.xlsx`** si apre nel flusso preview/pre-generazione senza la coda di righe vuote finali.
2. Le colonne **totalmente vuote** nel risultato tabellare finale parser-side non vengono mostrate in preview, anche nei file senza header esplicito, secondo la **regola generale** di rimozione (nessuna logica ad hoc sul solo file Dreamdiy).
3. **Non regressione identificazione:** la fix **non peggiora** la funzionalità attuale di identificazione header/colonne e mapping nei casi **già funzionanti**, inclusi:
   - riconoscimento **alias header**
   - inferenza / mapping **`headerSource`** e **`headerTypes`**
   - identificazione **colonne essenziali**
   - mapping verso chiavi (es. **barcode**, **productName**, **purchasePrice**, ecc.)
   Verifica esplicita: test JVM esistenti o nuovi che coprono questi percorsi restano verdi o aggiornati **solo** se il comportamento atteso è cambiato intenzionalmente (non applicabile a questo task); evidenza nel log Execution.
4. La fix non rompe i file con **header esplicito** già funzionanti né i file che **prima** del fix erano già compatibili nel ramo **`hasHeader = false`** (compatibilmente con il criterio **#3**).
5. La fix non rompe i fix di compatibilità workbook gia' introdotti in **TASK-024** per:
   - `.xls` legacy
   - `.xlsx` Strict/transitional
6. **Multi-file:** il cleanup **non** introduce incompatibilità tra file prima compatibili; con code vuote / colonne strutturali diverse tra file, il **merge preview** resta coerente; la **compatibilità degli header tra file multipli** non peggiora. Evidenza in Execution (scenari provati).
7. Nessun raw `Throwable.message` viene introdotto in UI o testi persistiti user-visible.
8. `assembleDebug`, `lint` e test JVM rilevanti risultano verdi oppure documentati come non eseguibili con motivazione.
9. Dopo rimozione colonne totalmente vuote (incluso ramo `hasHeader = false`), **`header`**, **`headerSource`** e **`dataRows`** restano allineati; nessuna regressione su mapping tipi / preview. **Ordine colonne:** solo eliminazione di colonne interamente vuote; **nessun riordino**; ordine relativo delle colonne superstiti **inalterato** rispetto all’originale (salvo la sola scomparsa delle colonne vuote).
10. Nessuna strumentazione di debug/preflight **persistente** in percorsi user-visible: log/diagnostica temporanea rimossa o confinata come da sezione dedicata.
11. Il file task (sezione **Execution**) riporta una **mini tabella before/after** del fix sul file target (o scenario principale), **almeno** con le colonne seguenti (valori **prima** vs **dopo** il cleanup, oppure due righe Before / After):

| Campo | Note |
|-------|------|
| Righe raw lette | conteggio prima del cleanup condiviso |
| Righe finali dopo cleanup | dopo trim / righe vuote / passi strutturali |
| Colonne raw massime | massimo osservato pre-cleanup |
| Colonne finali mostrate | dopo normalizzazione, allineate a header/`dataRows` |
| Ramo effettivo | `hasHeader = true` / `false` |
| Impatto identificazione | es. **confermato invariato** / **nessuna regressione** (riferimento test o nota) |

12. Il file task viene aggiornato con:
   - causa reale
   - path reale attivo
   - fix applicata
   - test eseguiti (inclusa verifica identificazione / multi-file ove applicabile)
   - tabella before/after (criterio **#11**)
   - rischi residui
   - stato finale `DONE`

---

## Piano di execution proposto

### Fase 0 — Preflight reale

- riprodurre il problema con `EROORE3-Dreamdiy.xlsx` nel flusso preview
- confermare forma del dataset **prima** della UI (dataset parser-side / strutture passate al ViewModel)
- se si aggiungono log o metriche per il preflight, seguire **Strumentazione debug / preflight**: ripulire o confinare prima della chiusura

**Obbligo di tracciamento nel file task (sezione Execution):** oltre alle note di preflight, inserire la **mini tabella before/after** richiesta dal **criterio di accettazione #11** (righe raw, righe finali, colonne raw max, colonne finali, ramo `hasHeader`, riga **impatto identificazione**: invariato / no regressioni + riferimento test).

Riferimento glossario metriche (stessi significati della tabella #11):

| Metrica | Descrizione |
|--------|-------------|
| Righe raw lette dal workbook | conteggio righe estratte da POI **prima** del cleanup condiviso |
| Righe dopo cleanup | conteggio dopo trimming coda celle, scarto righe vuote e passi successivi del preprocessing |
| Colonne raw massime | massimo indice/larghezza osservata sulle righe raw (o equivalente rilevato in Execution) |
| Colonne finali mostrate | numero di colonne nel risultato tabellare dopo normalizzazione (allineato a header/dataRows) |
| Ramo effettivo | `hasHeader = true` oppure `hasHeader = false` effettivamente usato per il file target |

### Fase 1 — Fix minima parser

- introdurre o consolidare la **normalizzazione POI condivisa** (`readAndAnalyzeExcel` + `analyzePoiSheet`) come **SSoT** del cleanup strutturale: **un** nucleo di logica, **non** cleanup duplicati sparsi; secondo l’ordine: trim celle finali vuote → righe vuote → rettangolarizzazione solo dopo cleanup, rispettando i vincoli di **efficienza** (complessità semplice, niente scansioni ridondanti)
- applicare il cleanup colonne/righe anche al caso **`hasHeader = false`**, con **allineamento garantito** tra `header`, `headerSource`, `dataRows` se si eliminano colonne **solo** se totalmente vuote nel risultato parser-side finale (**regola generale**)
- preservare la logica di **identificazione** esistente: nessun peggioramento su alias header, `headerSource` / `headerTypes`, colonne essenziali, mapping chiavi (**cleanup strutturale only**, vedi separazione scope in Strategia)
- rispettare **stabilità contratto pubblico** e **ordine colonne** (solo drop colonne totalmente vuote, no riordino); nessun cambio a firme/contratto salvo stretta necessità documentata

### Fase 2 — Test mirati e non regressione

Non limitarsi a un «test minimo»: in Execution prevedere **fixture e test JVM mirati** (es. `ExcelUtilsTest` / correlati) che coprano almeno:

| Scenario | Atteso |
|----------|--------|
| File **senza header esplicito** | stesso preprocessing del path con header; nessuna coda ingiustificata di righe vuote |
| **Molte righe vuote in coda** | righe totalmente vuote escluse dal risultato tabellare finale |
| **Colonne completamente vuote** | rimosse dal risultato **senza** disallineare `header` / `headerSource` / `dataRows`; **nessun riordino** colonne superstiti |
| **Merge cell tipo `D:E`** | parsing testuale **non** deve ricostruire o «riempire» artificialmente la colonna merge; nessun obiettivo di espandere merge |
| **Immagini ancorate alla colonna A** | non devono influenzare il **parsing testuale** (nessun effetto collaterale sulle stringhe celle rispetto al comportamento atteso POI) |

Completare con:

- **verifica esplicita non regressione identificazione:** casi con **header esplicito** **e** casi **`hasHeader = false` già funzionanti** prima del fix; mapping **headerSource** / **headerTypes** / colonne essenziali / chiavi (**barcode**, **productName**, **purchasePrice**, …) già coperti da test o fixture — stesso comportamento atteso salvo miglioramento documentato parser-side
- **equivalenza path:** stesso esito di normalizzazione strutturale tra `readAndAnalyzeExcel` e `analyzePoiSheet` ove applicabile (nessuna divergenza silenziosa)
- casi limite **dataset quasi vuoto / vuoto** dopo cleanup: stabilità allineamento e assenza di potature aggressive (vedi guardrail dedicato)
- scenari **multi-file** coerenti con il criterio di accettazione **#6** (merge preview, header tra file)
- baseline **TASK-004** rilevante (`ExcelUtils` / `ExcelViewModel` / percorsi toccati)

### Fase 3 — Chiusura execution

- verificare presenza della **mini tabella before/after** (criterio **#11**)
- verificare assenza di strumentazione debug/preflight **non** confinata (criterio accettazione **#10**)
- aggiornare il task markdown
- lasciare eventuali limiti residui esplicitati
- passare a `REVIEW`; dopo review **APPROVED** e conferma utente (test manuali), stato finale **`DONE`**

---

## Rischi

| Rischio | Mitigazione |
|--------|-------------|
| Rimuovere una colonna apparentemente vuota ma semanticamente utile | **solo** colonne **totalmente vuote** nel risultato parser-side finale; regola generale, no ad hoc Dreamdiy |
| Peggiorare **alias header**, **`headerSource` / `headerTypes`**, colonne essenziali o mapping chiavi | test di non regressione identificazione; diff minimo; **non** usare il cleanup strutturale per modificare euristiche semantiche (vedi Strategia) |
| **Riordino colonne** o shift indici superstiti oltre il solo drop colonne vuote | vincolo esplicito: solo rimozione colonne totalmente vuote; verifiche in test |
| Allargare lo scope con **definizioni ad hoc di «vuoto»** o regole Dreamdiy-only | attenersi alle definizioni operative e al codice esistente per «valore valido» vs vuoto |
| Disallineamento **`header` / `headerSource` / `dataRows`** dopo potatura colonne | applicare potatura in modo atomico o sincronizzato; verificare in test mapping tipi e preview |
| Due path POI (`readAndAnalyzeExcel` vs `analyzePoiSheet`) che divergono dopo il fix | normalizzazione **condivisa** + **stesso output** strutturale a parità input; test incrociati; nessuna divergenza silenziosa |
| Dataset **quasi vuoto / vuoto** dopo cleanup: preview o strutture **instabili** | guardrail dedicato; niente potatura oltre le regole di vuoto; documentare edge case |
| **Log/diagnostica** lasciati in produzione | criterio **#10**; rimuovere o confinare a test/debug non user-visible |
| **Polish UI** inglobato nel task senza essere necessario | annotare come follow-up separato; non scope creep |
| **Miglioramenti semantici opportunistici** (header/mapping) durante il fix | fuori TASK-025; solo follow-up documentato (Strategia) |
| Cleanup **frammentato** in più punti senza SSoT | un helper condiviso, responsabilità chiare in review; vedi Strategia + Efficienza |
| Cleanup **lento** o **ridondante** | una passata logica o condivisione risultati; evitare doppie scansioni inutili |
| Multi-file: merge preview o header tra file **incoerenti** dopo il fix | scenari multi-file in Execution; criterio accettazione **#6** |
| Toccare troppo il parser e rompere file gia' buoni | fix minima, test mirati, baseline TASK-004 |
| Confondere problema parser con problema UI / **fix cosmetiche** | regola decisionale guardrail: parser sporco → solo parser; prova parser OK + UI spuria → deroga UI minima |
| Interferire con i fix di TASK-024 | includere test di non regressione minima sui casi workbook gia' sistemati |

---

## Handoff planner / reviewer

- Il file reale `EROORE3-Dreamdiy.xlsx` non sembra un caso di corruzione o incompatibilita' POI.
- L'evidenza piu' forte e' la presenza di **889 righe vuote strutturali** e il fatto che il parser le lasci passare nel ramo **senza header**.
- La correzione piu' promettente appare oggi **parser-side**, non UI-side.
- La colonna `A` vuota e' coerente con immagini ancorate nel workbook; la colonna `E` vuota e' coerente con merge `D:E`.
- In Execution conviene verificare che la regola di eliminazione colonne resti strettamente "solo colonne completamente vuote", senza tentare una ricostruzione delle merge cell.
- Il planning richiede **SSoT** cleanup strutturale (niente logica spezzata/duplicata), **normalizzazione POI condivisa** con **stesso output strutturale** a parità input tra `readAndAnalyzeExcel` e `analyzePoiSheet` (niente divergenze silenziose), in Execution **tabella before/after** + riga impatto identificazione (criterio **#11**), suite test mirata (merge, immagini colonna A, righe vuote, colonne vuote, no header, **hasHeader=false già buono**, edge quasi/vuoto con contratto preview esplicito se ambiguo), **allineamento strutture**, **non regressione identificazione**, **no miglioramenti semantici opportunistici** in TASK-025, regola colonne generale, definizioni di vuoto, solo drop senza riordino, stabilità contratto pubblico, separazione cleanup vs semantica, efficienza, multi-file, guardrail UI, strumentazione ripulita, polish UI / semantiche solo follow-up.

---

## Execution

### Esecuzione — 2026-04-03

**Causa confermata:**
- Il path reale `PreGenerateScreen -> ExcelViewModel.loadFromMultipleUris -> readAndAnalyzeExcel` nel ramo POI costruiva la lista righe senza cleanup condiviso, quindi nel file reale passavano a valle **975** righe lette (incluse **889** righe vuote strutturali).
- Nel ramo `hasHeader = false`, `analyzeRows` manteneva `dataRows = rows` e non applicava la potatura delle colonne totalmente vuote; per `EROORE3-Dreamdiy.xlsx` restavano quindi visibili la colonna `A` vuota (immagini ancorate) e la colonna `E` vuota (merge `D:E`).

**Path reale attivo verificato:**
- `PreGenerateScreen`
- `ExcelViewModel.loadFromMultipleUris`
- `ExcelUtils.readAndAnalyzeExcel`
- Caller condiviso verificato in baseline: `DatabaseViewModel.parseImportFile -> readAndAnalyzeExcel`

**Mini tabella before/after — file reale `EROORE3-Dreamdiy.xlsx`:**
| Campo | Before | After |
|-------|--------|-------|
| Righe raw lette | 975 | 86 |
| Righe finali dopo cleanup | 975 | 86 |
| Colonne raw massime | 8 (`A:H`) | 8 (`A:H`) |
| Colonne finali mostrate | 8 | 6 (`B,C,D,F,G,H`) |
| Ramo effettivo | `hasHeader = false` | `hasHeader = false` |
| Impatto identificazione | rumoroso: coda vuota + colonne `A/E` inutili | confermato invariato, nessuna regressione su alias/`headerSource`/`headerTypes`/mapping; suite JVM verdi |

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — estratto cleanup strutturale condiviso (`normalizeTabularRows` / `readPoiRows`) e applicata potatura colonne totalmente vuote anche nel ramo `hasHeader = false`, mantenendo allineati `header`, `headerSource`, `dataRows`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — aggiunti test no-header con code vuote, colonne totalmente vuote, merge `D:E`, immagini in colonna `A` e test di equivalenza `readAndAnalyzeExcel`/`analyzePoiSheet`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — aggiunto test multi-file preview no-header per verificare merge coerente senza righe/colonne spurie.
- `docs/TASKS/TASK-025-preview-excel-no-header-righe-colonne-vuote.md` — aggiornato log Execution, tabella before/after, evidenze; chiusura con stato **`DONE`**.

**Azioni eseguite:**
1. Eseguito preflight sul file reale `/Users/minxiang/Downloads/EROORE3-Dreamdiy.xlsx` per confermare metrica raw (`975` row nodes, `889` vuoti strutturali, colonne usate fino a `H`) e ramo effettivo `hasHeader = false`.
2. Estratto il cleanup strutturale condiviso nel parser POI e riusato sia in `readAndAnalyzeExcel` sia in `analyzePoiSheet`, con ordine: trim celle finali vuote -> scarto righe completamente vuote.
3. Applicata la rimozione delle colonne totalmente vuote anche al ramo `hasHeader = false` senza riordinare le colonne superstiti; l’allineamento `header` / `headerSource` / `dataRows` è garantito dalla stessa potatura atomica.
4. Verificata la non regressione con test JVM mirati su `ExcelUtils`, preview multi-file su `ExcelViewModel` e baseline del caller condiviso `DatabaseViewModel`.
5. Nessuna modifica UI: `ZoomableExcelGrid.kt` e `PreGenerateScreen.kt` sono rimasti fuori scope perché il dataset parser-side risultava effettivamente sporco prima della fix.

**Check obbligatori:**
| Check | Stato | Note |
|--------------------------|-------|-----------------------------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` -> `BUILD SUCCESSFUL` in 3s |
| Lint | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lint` -> `BUILD SUCCESSFUL` in 29s |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo nei file toccati; restano solo warning/deprecazioni AGP/Kotlin plugin preesistenti |
| Coerenza con planning | ✅ ESEGUITO | Fix parser-side, cleanup POI condiviso, nessun intervento UI/DAO/Room/repository/NavGraph |
| Criteri di accettazione | ✅ ESEGUITO | Tutti i 12 criteri verificati singolarmente nella tabella sotto |

**Criteri di accettazione — verifica finale:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | `EROORE3-Dreamdiy.xlsx` senza coda di righe vuote in preview | ESEGUITO | Preflight reale: `975 -> 86` righe parser-side dopo cleanup; il path preview usa `readAndAnalyzeExcel` |
| 2 | Colonne totalmente vuote non mostrate anche senza header | ESEGUITO | Preflight reale: `8 -> 6` colonne finali, con drop delle sole colonne `A` e `E` totalmente vuote |
| 3 | Non regressione identificazione / mapping | ESEGUITO | Verdi `ExcelUtilsTest`, `ExcelViewModelTest`, `DatabaseViewModelTest`; i test esistenti su alias/header/mapping restano verdi |
| 4 | Nessuna rottura su header esplicito e casi `hasHeader = false` già buoni | ESEGUITO | Restano verdi i test esistenti su header esplicito e i nuovi test no-header/multi-file |
| 5 | Nessuna regressione sui fix TASK-024 | ESEGUITO | Restano verdi i test esistenti `readAndAnalyzeExcel recovers malformed legacy xls obj records` e `readAndAnalyzeExcel recovers strict ooxml xlsx workbook` |
| 6 | Multi-file coerente | ESEGUITO | Nuovo test `loadFromMultipleUris merges no-header files after structural cleanup without spurious rows or columns` verde |
| 7 | Nessun raw `Throwable.message` in UI | ESEGUITO | Nessuna modifica ai percorsi di errore / UI; scope limitato al parser e ai test |
| 8 | `assembleDebug`, `lint` e test JVM rilevanti verdi | ESEGUITO | `assembleDebug`, `lint`, `ExcelUtilsTest`, `ExcelViewModelTest`, `DatabaseViewModelTest` tutti verdi |
| 9 | `header` / `headerSource` / `dataRows` allineati e nessun riordino colonne | ESEGUITO | Potatura atomica condivisa + test su header/headerTypes e ordine colonne superstiti |
| 10 | Nessuna diagnostica persistente | ESEGUITO | Nessun log/debug aggiunto nei percorsi user-visible |
| 11 | Tabella before/after presente | ESEGUITO | Vedi tabella sopra con metriche raw/finali, ramo effettivo e impatto identificazione |
| 12 | Task markdown aggiornato con fix/test/rischi/stato finale | ESEGUITO | Questo log Execution; stato portato a **`DONE`** dopo review APPROVED e conferma utente (test manuali) |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.util.ExcelUtilsTest`
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest`
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest`
- Test aggiunti/aggiornati:
  - `ExcelUtilsTest` — nuovo caso no-header con colonne `A/E` strutturalmente vuote, merge `D:E`, immagini in `A`, righe vuote in coda
  - `ExcelUtilsTest` — nuovo caso di equivalenza tra `readAndAnalyzeExcel` e `analyzePoiSheet`
  - `ExcelViewModelTest` — nuovo caso multi-file no-header per preview coerente
- Limiti residui:
  - il file reale `EROORE3-Dreamdiy.xlsx` è stato verificato localmente in preflight da `/Users/minxiang/Downloads`, ma non è stato aggiunto come fixture al repo
  - test manuali di chiusura (conferma utente post-review): **passati**; non risultano regressioni funzionali in quel perimetro

**Incertezze:**
- nessuna

**Rischi residui:**
- Restano warning di toolchain AGP/Kotlin plugin già presenti prima del task; non dipendono dal diff di TASK-025 e sono fuori scope.
- La copertura automatica del file reale resta indiretta: la regressione è blindata con fixture sintetiche repo-safe che riproducono gli aspetti strutturali rilevanti (code vuote, `hasHeader = false`, merge `D:E`, immagini in `A`, multi-file).

**Handoff notes:**
- In review conviene fare un controllo mirato su `ExcelUtils.kt` per confermare che il drop colonne avvenga prima dell’inferenza nel ramo `hasHeader = false` e dopo alias nel ramo `hasHeader = true`, come previsto dal planning.
- Nessun follow-up semantico è stato incluso: eventuali miglioramenti a euristiche di mapping/header emersi durante il task restano fuori scope e vanno trattati separatamente.

---

## Review

### Review — 2026-04-03

**Revisore:** Claude (planner)

**Metodo:** lettura completa di `ExcelUtils.kt` (593 righe), `ExcelUtilsTest.kt` (494 righe), `ExcelViewModelTest.kt` (725 righe), diff Git di tutti i file modificati, esecuzione test JVM (`ExcelUtilsTest` + `ExcelViewModelTest` + `DatabaseViewModelTest` — BUILD SUCCESSFUL), verifica coerenza MASTER-PLAN.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | `EROORE3-Dreamdiy.xlsx` senza coda righe vuote | ✅ | `normalizeTabularRows` via `readPoiRows` elimina righe vuote pre-`analyzeRows`; preflight 975→86 documentato |
| 2 | Colonne totalmente vuote rimosse anche senza header | ✅ | `pruneTotallyEmptyColumns` chiamata nel ramo `hasHeader = false`; regola generale (`dataRows.any { isNotBlank }`) |
| 3 | Non regressione identificazione | ✅ | Alias matching, `headerSource`, `headerTypes`, colonne essenziali, mapping chiavi: tutti i test esistenti verdi, nessuna euristica modificata |
| 4 | No rottura file con header / `hasHeader = false` già buoni | ✅ | Suite completa verde; test preesistenti su header esplicito invariati |
| 5 | No regressione TASK-024 (legacy XLS / strict OOXML) | ✅ | Test `recovers malformed legacy xls obj records` e `recovers strict ooxml xlsx workbook` verdi |
| 6 | Multi-file coerente | ✅ | Nuovo test `loadFromMultipleUris merges no-header files...` verde |
| 7 | Nessun raw `Throwable.message` in UI | ✅ | Nessuna modifica a percorsi errore/UI |
| 8 | `assembleDebug` / `lint` / test JVM verdi | ✅ | BUILD SUCCESSFUL confermato in review |
| 9 | `header`/`headerSource`/`dataRows` allineati, nessun riordino | ✅ | Potatura atomica via `PrunedColumnsResult`; `nonEmptyCols` preserva ordine originale |
| 10 | Nessuna diagnostica debug persistente | ✅ | Nessun log/print aggiunto nei path produzione |
| 11 | Tabella before/after presente | ✅ | Presente nella sezione Execution con tutti i campi richiesti |
| 12 | Task markdown aggiornato | ✅ | Causa, path, fix, test, rischi documentati; chiusura in `DONE` dopo conferma utente |

**Problemi trovati:**
- MASTER-PLAN non allineato allo stato del task — da correggere in chiusura finale (`DONE`).

**Analisi tecnica dettagliata:**

1. **SSoT cleanup confermata:** `normalizeTabularRows` (dropLastWhile vuoto + filter righe blank) è l'unico punto di normalizzazione strutturale, riusato da: `readPoiRows` (path POI produzione), `analyzePoiSheet` (path test), path HTML. Nessuna duplicazione.

2. **`readPoiRows` estratto correttamente:** logica identica al vecchio inline in `readAndAnalyzeExcel`, con miglioramento: `coerceAtLeast(0)` su `lastCellNum` previene crash su righe POI vuote (il vecchio codice non aveva il guard).

3. **`pruneTotallyEmptyColumns` corretto:** filtra per `dataRows.any { isNotBlank }`, produce header/headerSource/dataRows filtrati + mappa indici vecchi→nuovi. Usata in entrambi i rami di `analyzeRows`.

4. **Ramo `hasHeader = true`:** la nuova implementazione è semanticamente equivalente al vecchio codice inline — le entry `headerMap` per colonne rimosse vengono scartate implicitamente (non presenti in `oldToNewIndex`), anziché rimosse esplicitamente. Risultato identico.

5. **Ramo `hasHeader = false`:** pruning applicato PRIMA del pattern matching → i pattern lavorano su dati puliti, indici coerenti col nuovo `header.size`. Corretto.

6. **Path HTML:** ora applica `normalizeTabularRows` — prima non lo faceva. Miglioramento coerente col design SSoT, nessun rischio di regressione (i dati HTML ricevono lo stesso cleanup dei dati POI).

**Verdetto:** **APPROVED**

**Note per fix:** nessuna — nessun fix richiesto.

**Chiusura review → DONE:** review tecnica **completata** (tutti i criteri ✅). **Conferma utente:** test manuali **passati**; nessuna regressione funzionale segnalata. Il task passa a **`DONE`** senza ulteriori round FIX/REVIEW.

---

## Chiusura finale — sintesi

| Voce | Esito |
|------|--------|
| **Esito** | **TASK-025** chiuso in **`DONE`** (2026-04-03). Obiettivo parser-side raggiunto: cleanup strutturale condiviso, colonne/righe rumorose rimosse nel perimetro definito, senza intervento UI. |
| **Review** | **Completata** — verdetto planner **APPROVED** (vedi sezione Review). |
| **Test automatici** | Documentati **verdi** in Execution: `assembleDebug`, `lint`, `ExcelUtilsTest`, `ExcelViewModelTest`, `DatabaseViewModelTest` (baseline TASK-004 rilevante). |
| **Test manuali** | **Passati** — conferma utente in chiusura. |
| **Regressioni funzionali** | **Non emerse** nel perimetro verificato (identificazione, multi-file, TASK-024, contratto preview). |
| **Limiti residui reali** | (1) Warning toolchain AGP/Kotlin **preesistenti**, fuori scope TASK-025. (2) File reale `EROORE3-Dreamdiy.xlsx` **non** in repo come fixture; copertura tramite fixture sintetiche allineate agli aspetti strutturali. |

Nessun altro limite bloccante noto alla chiusura.
