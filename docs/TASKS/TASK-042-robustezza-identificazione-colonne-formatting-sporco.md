# TASK-042 — Robustezza identificazione colonne con formatting sporco / layout fornitore non pulito

---

## Informazioni generali

| Campo                | Valore                                                                          |
| -------------------- | ------------------------------------------------------------------------------- |
| ID                   | TASK-042                                                                        |
| Stato                | **DONE**                                                                        |
| Priorità             | **ALTA**                                                                        |
| Area                 | Import / Excel / Preview / identificazione colonne                              |
| Creato               | 2026-04-04                                                                      |
| Ultimo aggiornamento | 2026-04-04 — **chiusura documentale:** review formale **APPROVED** confermata; baseline mirata `ExcelUtilsTest` + `ExcelViewModelTest` e `testDebugUnitTest` verdi; nessun finding bloccante/medio residuo; cautela **non bloccante** su `ShoppingHogarLocalDebugTest.kt` (solo evidenza locale, non test di suite portabile) documentata. Stato finale **DONE** dopo conferma utente. |

---

## Governance documento

* **2026-04-04 (planning iniziale):** perfezionamento piano: chiarezza, verificabilità, reviewability. Nessuna execution.
* **2026-04-04 (review repo-grounded):** review mirata del planning contro codice reale (`ExcelUtils.kt`) e caso reale Shopping Hogar. Confermato bug primario split-header, aggiunta diagnosi repo-grounded, piano rivisto con fasi aggiornate. Transizione a **EXECUTION** dopo verifica coerenza.
* Lo **scope funzionale** resta quello definito originariamente (identificazione colonne robusta); si aggiungono **requisiti di qualità** (determinismo, spiegabilità, prestazioni), non nuove feature prodotto arbitrarie.
* Le eventuali micro-migliorie UI/UX discusse in questo piano sono **accessorie e subordinate**: non autorizzano redesign, non cambiano i flussi.

---

## Dipendenze

* **TASK-004** (`DONE`) — baseline test JVM su `ExcelUtils` / `ExcelViewModel` / `DatabaseViewModel`: obbligatoria in Execution se si tocca parsing, preview o mapping.
* **TASK-005** (`DONE`) — copertura utilità Excel / import analyzer: base per estendere test su identificazione colonne.
* **TASK-024** (`DONE`) — compatibilità workbook `.xls` legacy / `.xlsx` Strict OOXML: da preservare senza regressioni.
* **TASK-025** (`DONE`) — cleanup strutturale righe/colonne vuote anche senza header esplicito: da preservare; questo task è distinto e riguarda il **riconoscimento semantico** delle colonne, non il solo trimming strutturale.
* **TASK-026** (`DONE`) — correttezza import end-to-end: da non rompere.
* **Ortogonalità:** nessun cambio a DAO / Room / schema / repository / NavGraph salvo necessità reale documentata.

---

## Scopo

Gestire correttamente i file fornitore in cui il **workbook è formalmente leggibile** e la preview mostra una tabella, ma il **layout reale del foglio è sporco / visivamente deformato** (merge, celle placeholder, colonne decorative, header rumorosi, prime righe miste tra metadati e dati), così che l’algoritmo identifichi comunque in modo affidabile le **colonne di dominio** corrette.

Obiettivi operativi:

1. riprodurre il caso reale nel flusso preview/pre-generazione;
2. correggere il difetto attuale sul file fornitore visivamente / formattativamente corrotto, così che il mapping colonne torni funzionale anche in presenza di layout molto sporco;
3. classificare il tipo di rumore strutturale/semantico che inganna il mapping attuale;
4. migliorare l’algoritmo di identificazione colonne per tollerare file con formattazione rumorosa ma dati coerenti;
5. preservare senza regressioni i casi già funzionanti (`.xls`, `.xlsx`, header esplicito, no-header già compatibili, multi-file e file storicamente riconosciuti correttamente);
6. evitare tuning ad hoc hardcoded sul solo fornitore Shopping Hogar.

**Obiettivo di qualità aggiuntivo (planning):** il risultato deve essere **verificabile in review** con evidenze leggibili (mapping **e** motivazione: punteggi, ranking, traccia diagnostica), non solo un’affermazione di correttezza a mano.

**Vincolo di prodotto aggiuntivo (planning):** questo task nasce per risolvere il problema reale del file fornitore attuale, molto degradato a livello visivo / formattativo, ma **non** deve peggiorare né alterare il comportamento sui file già riconosciuti correttamente in passato. Il miglioramento deve essere **additivo e compatibile**, non una riscrittura che sposti l’equilibrio dei casi già verdi.

---

## Contesto

Caso reale iniziale: file fornitore **Shopping Hogar** (`20260404-Shopping Hogar.xls`). Dalle evidenze attuali:

* il file si apre;
* la preview mostra i valori riga per riga;
* il formato del foglio sorgente è visivamente molto sporco / non tabellare puro;
* il caso reale somiglia più a un **documento stampabile / albaran** con blocchi intestazione multi-riga e tabella dati incastonata, che a un foglio Excel tabellare pulito;
* il mapping automatico attuale è fragile o errato su colonne chiave;
* serve un miglioramento **algoritmico parser-side**, non una semplice correzione cosmetica della UI.
* il fix deve risolvere il caso reale attuale senza compromettere l’analisi funzionale corretta dei file già supportati.

### Problema osservato

Il foglio può contenere colonne corrette a livello umano, ma il parser può essere fuorviato da:

* righe iniziali descrittive o metadati cliente/documento;
* blocchi intestazione multi-riga che possono sembrare header tabellari ma in realtà appartengono al documento stampabile;
* celle unite / layout da documento stampabile più che da tabella dati pura;
* colonne placeholder o decorative che degradano il profilo della riga;
* header mancanti, rumorosi, spezzati o non affidabili;
* dati reali riconoscibili solo dal **pattern delle celle**;
* colonne tecniche/numeriche superficialmente simili (codice articolo vs barcode vs quantità vs prezzo).

Sintomo: la preview può sembrare leggibile, ma il motore non collega sempre correttamente le colonne a `itemNumber`, `barcode`, `productName`, `quantity`, `purchasePrice`, ecc.

### Ipotesi tecnica iniziale

Probabile problema in una o più aree parser-side:

1. scelta della riga candidata come **header** troppo fragile;
2. peso eccessivo dell’**header testuale** rispetto al contenuto della colonna;
3. assenza di **scoring per pattern colonna** (barcode-like, prezzo-like, quantità-like, codice articolo-like, nome prodotto-like);
4. mancata distinzione tra **righe documento** e **righe tabellari**;
5. mancanza di **fallback robusto** quando l’header è assente o rumoroso ma i dati di colonna sono chiari;
6. possibile dipendenza eccessiva dall’indice/ordine colonna pre-cleanup.
7. normalizzazione troppo aggressiva o troppo precoce dei valori, con rischio di perdere segnali semantici utili (es. zeri iniziali, formato grezzo barcode/codice);
8. gestione non abbastanza esplicita delle celle derivate da merge/layout stampabile, che può sporcare header o prime righe dati.
9. comportamento non abbastanza definito quando il campione utile è troppo piccolo o troppo sporco per sostenere un auto-mapping affidabile.

### Diagnosi repo-grounded (review planning 2026-04-04)

**Analisi diretta del codice** (`ExcelUtils.kt` linee 291–580) — risultato della review mirata contro il caso reale Shopping Hogar:

#### Bug primario confermato: header su riga singola con split-header

Il codice attuale (`analyzeRows`, linea 312) assume che l’header sia **una singola riga** immediatamente precedente alla prima riga dati: `header = rows[dataRowIdx - 1]`. Nel file Shopping Hogar, l’header tabellare è **distribuito su due righe**:

* **Riga A** (sopra): `REF.CAJAS | COD.BARRA | (vuoto) | CANTID | PRE/U | (vuoto) | DTO% | PRE/U`
* **Riga B** (sotto, = `dataRowIdx - 1`): `(vuoto) | (vuoto) | ARTICULO | (vuoto) | (vuoto) | ...`

Il risultato è che l’algoritmo prende **solo Riga B** come header. Riga B contiene solo "ARTICULO" (che mappa a `productName`), ma all’indice di colonna sbagliato rispetto ai dati — producendo un mapping disallineato.

**Conseguenza osservata**: la preview mostra solo `itemNumber` (riconosciuto via pattern, non via header) con valori corretti; tutte le altre colonne (barcode, quantity, purchasePrice, productName) restano vuote o sbagliate perché l’alias matching fallisce (header incompleto/sbagliato) e il fallback pattern non compensa.

#### Bug secondario: euristica `numericCount >= 3` fragile su printable-layout

La ricerca della prima riga dati (linee 296–304) cerca `numericCount >= 3 && textCount >= 1`. Nei documenti stampabili, righe metadati come `Nº ALBARAN : 13076 | FECHA | COD.CLIE:1048 | PAG: 1/1` possono avere valori numerici in celle separate (dipende dalla struttura .xls), rischiando un **false positive** che sposta `dataRowIdx` e quindi l’header a una riga completamente sbagliata.

#### Bug terziario: pattern detection puramente posizionale e senza scoring

Il fallback pattern-based (linee 386–462) è **first-match-wins** con iterazione left-to-right sulle colonne:
* `quantity` e `purchasePrice` usano la **stessa identica euristica** (`nums.all { it > 0 } && nums.size >= dataRows.size * 0.7`) — la distinzione dipende **solo** dall’ordine di iterazione;
* nessun confidence score, nessun tie-breaker, nessuna competizione tra colonne candidate;
* nessun meccanismo per distinguere barcode da itemNumber tramite lunghezza/uniformità.

#### Conferma alias: tutti presenti

Tutti gli alias per le colonne Shopping Hogar sono già nel codice (`KNOWN_EXCEL_HEADER_ALIASES`):
* `ref.cajas` → `itemNumber` (linea 194) ✅
* `cod.barra` → `barcode` (linea 188) ✅
* `articulo` → `productName` (linea 192) ✅
* `cantid` → `quantity` (linea 189) ✅
* `pre/u` → `purchasePrice` (linea 190) ✅
* `dto%` → `discount` (linea 197) ✅

**Conclusione**: il problema NON è negli alias ma nel fatto che la riga header scelta è sbagliata (split-header), quindi gli alias non vengono mai consultati sulle celle giuste.

#### Implicazioni per l’Execution

1. **Priorità 1 — split-header**: l’Execution DEVE gestire header distribuiti su più righe (almeno 2), combinando le celle non vuote di righe adiacenti sopra la prima riga dati.
2. **Priorità 2 — zona tabellare**: l’Execution DEVE distinguere le righe metadato/documento dal blocco tabellare, per evitare false positive nell’individuazione di `dataRowIdx`.
3. **Priorità 3 — pattern scoring**: il fallback pattern-based DEVE usare criteri differenziati per quantity vs purchasePrice (almeno: distribuzione, range, interi vs decimali) e per barcode vs itemNumber (lunghezza, uniformità).
4. **Gate legacy**: queste migliorie devono attivarsi **dopo** il tentativo alias su header pulito, che resta il percorso fast-path per i file già funzionanti.

### Direzione strategica preferita

Strategia **ibrida header + contenuto**, con **confidence score** per (colonna × campo dominio), **tie-breaker documentati**, comportamento **deterministico** e **spiegabile**.

**Principi:** parser first, diff minimo ma sufficiente, nessun hardcode sul singolo fornitore, euristiche **semplici, locali e testabili**, non peggiorare i casi già verdi, preservare il **valore grezzo** quando serve per distinguere pattern (es. barcode/itemNumber con zeri iniziali), **nessuna scansione full-sheet** oltre un budget di righe/colonne definito in Execution (vedi guardrail prestazionali).

**Principio di compatibilità retroattiva:** quando il segnale header/contenuto dei file già storicamente verdi è già forte e coerente, il nuovo comportamento deve restare il più possibile **equivalente** al mapping legacy; le nuove euristiche devono entrare in gioco soprattutto nei casi rumorosi, ambigui o degradati come il file reale attuale.

**Gate legacy-high-confidence (preferenza di planning):** se il mapping legacy su un file produce già un allineamento forte e non ambiguo tra header/contenuto/struttura, l’Execution dovrebbe preservare quel risultato con il minor disturbo possibile; le euristiche aggiuntive più aggressive devono attivarsi soprattutto quando emergono segnali di rumore, printable-layout, conflitto o bassa evidenza.

**Segnali da combinare per colonna candidata:**

1. **Header:** alias noti, similarità lessicale con chiavi di dominio, tolleranza a header spezzati/rumorosi/vuoti.
2. **Contenuto:** pattern barcode, codice articolo, quantità, prezzo, nome prodotto (prevalenze su campione robusto).
3. **Strutturale:** stabilità nel campione dati utile, densità non vuota, esclusione colonne decorative/quasi vuote.
4. **Fallback:** senza header affidabile, scelta dal contenuto; in caso di competizione, **tie-breaker espliciti** e tracciati (non ordine casuale).

---

## Non incluso

* redesign della preview grid o del flusso pre-generazione;
* rendering fedele del layout Excel originario;
* ricostruzione completa merge cell / immagini / impaginazione documento;
* nuove dipendenze Gradle;
* refactor architetturale ampio;
* logiche speciali sul nome file, nome foglio o nome fornitore;
* interventi UI che **mascherino** un mapping errato (etichette «cosmetiche» senza fix parser-side).

### UX / UI (perimetro ammesso in Execution, solo se utile)

* Il task resta **parser-first**: la correttezza del mapping è nel motore, non nella griglia.
* Sono ammesse **solo micro-migliorie** UI/UX **locali**, coerenti con Material3 / stile app, se aumentano **chiarezza**, **leggibilità** o **diagnosi** (es. hint testuale su colonna incerta, **senza** cambiare flussi né ridisegnare schermate).
* In caso di **bassa confidenza** sono ammessi solo segnali UX **non bloccanti** e coerenti con lo stile esistente (es. badge/hint discreto, testo di supporto, micro-affordance in preview): niente wizard nuovi, niente step aggiuntivi, niente forcing manuale fuori scope.
* Ogni micro-intervento UI va **tracciato** nel log Execution (`AGENTS.md`), come da guardrail progetto.

---

## File potenzialmente coinvolti

| File                                                                                           | Ruolo atteso                                                  |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt`                 | parsing tabellare, scoring colonne, fallback header/contenuto |
| `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`        | eventuale wiring minimo / esposizione metadati preview        |
| `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt`             | test reali/sintetici su identificazione colonne rumorose      |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt`    | eventuali test preview/mapping end-to-end                     |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` | solo se il flusso condiviso viene impattato                   |

### Guardrail di scope

* **Parser-side e locale:** modifiche concentrate dove oggi avviene identificazione/mapping colonne; nessuna propagazione inutile.
* Nessuna fix cosmetica che nasconda il problema senza correggere il mapping parser-side.
* Nessuna modifica a DAO, Room, repository, navigation salvo necessità reale documentata.
* Vietato hardcode tipo: «se file = Shopping Hogar allora mappa colonna X = barcode».
* Vietato introdurre un miglioramento che risolva il caso corrotto attuale ma sposti o destabilizzi i percorsi già affidabili per i file correttamente riconosciuti in passato.

### Guardrail prestazionali

* **Nessuna scansione obbligatoria dell’intero foglio** per il solo scoring colonne: usare un **budget** esplicito (es. prime *K* righe dopo rilevamento «zona tabellare», o finestra scorrevole con limite massimo) documentato in Execution.
* Riutilizzare, dove possibile, dati già materializzati dal path `readAndAnalyzeExcel` / normalizzazione esistente (**TASK-025**): evitare secondi passaggi full-sheet ridondanti.
* Preferire un **percorso cheap/early-exit** per i casi già ad alta confidenza legacy, riservando le euristiche più costose o articolate ai fogli rumorosi/degradati che mostrano segnali di printable-layout o mapping fragile.
* Complessità algoritmica **lineare o quasi** nel numero di celle del campione (righe × colonne visibili nel budget), senza strutture pesanti non giustificate.
* Nessuna nuova dipendenza; niente framework di ML o librerie esterne per questo task.
* Evitare doppia normalizzazione costosa o duplicazione di strutture dati se i valori grezzi e normalizzati possono convivere in forma locale/minimale per il solo scoring.
* La gestione di celle merge/placeholder deve restare **euristica e locale**: niente ricostruzione completa del layout Excel solo per migliorare il mapping.

---

## Modello algoritmico (planning — da implementare in Execution)

### Guardrail di normalizzazione
* Il planning richiede di distinguere tra **valore grezzo** cella e **valore normalizzato** per scoring/parsing: la normalizzazione non deve cancellare informazione utile alla classificazione semantica.
* Esempi da preservare finché servono allo scoring: **zeri iniziali**, differenza tra stringa numerica lunga e numero renderizzato, punteggiatura/segni che aiutano a distinguere prezzo, quantità o codice.
* Se in Execution serve una forma duale (raw + normalized), mantenerla **locale** al path di analisi/mapping, senza propagare nuovi contratti pubblici inutili.

### Strategia globale di assegnazione (decisione di planning)
* Per evitare doppie assegnazioni incoerenti, la strategia preferita in Execution è una **assegnazione greedy globale con lock colonna**: ordinare le coppie *(F, C)* per score decrescente, applicare i tie-breaker documentati, assegnare la migliore coppia valida e bloccare la colonna per gli altri campi incompatibili.
* Se due campi restano in conflitto sulla stessa colonna e l’alternativa per uno dei due è molto debole, preferire la soluzione che massimizza la **coerenza complessiva** del mapping, non il miglior score locale isolato.
* La strategia deve restare **semplice, deterministica e reviewable**: niente algoritmi pesanti, niente ottimizzazione combinatoria complessa se il greedy con lock soddisfa i criteri #21 e gli scenari N.

### Gate legacy-high-confidence
* Prima di attivare euristiche più aggressive, l’Execution può verificare se il mapping legacy corrente è già **forte, coerente e non ambiguo** sul file in esame.
* Se il caso rientra in questo profilo, preferire un comportamento **equivalente** al mapping legacy e limitare il ricalcolo ai soli controlli di coerenza minimi.
* Se invece emergono segnali di printable-layout, header rumoroso, conflitto tra candidate, densità anomala o bassa evidenza, attivare il ramo robusto descritto in questo planning.
* Questo gate serve a ridurre regressioni e costo computazionale, non a congelare bug esistenti: il caso reale Shopping Hogar deve comunque entrare nel ramo robusto se il mapping legacy è fragile/errato.

### Confidence score

* Per ogni **campo dominio** *F* (es. `barcode`, `itemNumber`, …) e ogni **colonna candidata** *C*, definire un punteggio **normalizzato o confrontabile** `score(F, C)` che combini almeno: segnale header, segnale contenuto (sul campione), segnale strutturale (densità, stabilità).
* Opzionale ma raccomandato: esporre anche `confidence(F)` = qualità della scelta finale per *F* (es. margine tra 1° e 2° classificata, saturazione del vincitore), per gestire **bassa confidenza** (vedi sotto).
* I pesi e le formule devono essere **codificate in modo leggibile** (costanti nominate, commenti sintetici) per review e test.

### Tie-breaker documentati (ordine suggerito — da fissare in Execution e ripetere identico ovunque serva)

1. **Margine di score:** se `score(F, C1) - score(F, C2) > ε` (soglia documentata), vince *C1*.
2. **Segnale header forte:** se solo una colonna ha header allineato ad alias / similarità sopra soglia, preferirla a parità o quasi parità di contenuto.
3. **Coerenza pattern dominio:** es. per `barcode`, preferire colonna con maggior quota di stringhe numeriche lunghe e a lunghezza quasi costante; per `quantity`, preferire interi piccoli/medi ricorrenti; per `purchasePrice`, preferire distribuzione monetaria plausibile (decimali, range).
4. **Indice colonna solo come ultima risorsa:** se tutto il resto è equivalente entro ε, usare **indice crescente** (0, 1, 2, …) come tie-breaker **esplicito e deterministico** — mai `HashMap` iteration order o random.
5. **Unicità assegnazione:** una colonna non dovrebbe «vincere» due campi *F1* e *F2* se esiste un’assegnazione alternativa con somma punteggi migliore; in planning, prevedere **assegnazione globale** semplice (es. greedy ordinato per score decrescente con lock colonna) o vincoli documentati.

### Ambiguità (due colonne con punteggi simili)

* Se `|score(F, C1) - score(F, C2)| ≤ ε`: dichiarare il caso **ambiguo** per *F*.
* Se il **campione utile** per *F* è sotto una soglia minima documentata (troppo poche celle non vuote / troppo rumore), trattare il caso come **low-confidence strutturale** anche se il vincitore numerico esiste.
* Comportamento richiesto (da implementare e documentare):
  * applicare tie-breaker in ordine;
  * se dopo tie-breaker resta incertezza, applicare la policy raccomandata di questo planning: **non mappare** il campo quando la bassa confidenza nasce da evidenza insufficiente o conflitto non risolto in modo pulito; usare invece **auto-selezione deterministica con hint discreto** solo quando esiste un vincitore plausibile ma il margine resta basso — il comportamento va riportato in Execution e verificato contro i criteri #15, #19 e #20;
  * se viene mostrato qualcosa in UI, deve essere un segnale **discreto e non bloccante**, coerente con la sezione UX/UI di questo piano.
* La **traccia diagnostica** deve riportare almeno: le due candidate, i loro score, la soglia ε, il tie-breaker applicato.

### Determinismo e ripetibilità

* **Stesso input workbook + stessa versione codice** ⇒ stesso mapping (stessa assegnazione colonna→campo per il perimetro del task) e stessa traccia diagnostica (se abilitata).
* Vietato: `Random` non seedato, dipendenza da ordine non garantito delle collezioni, uso di float non stabilizzato per chiavi di sort senza regole fisse.
* I test JVM devono poter **asserire** l’output atteso byte-per-byte o strutturalmente su fixture sintetiche.

---

## Campionamento righe e robustezza del profilo colonna (planning)

### Come campionare le righe «utili»

* Dopo aver individuato (o ipotizzato) la **zona tabellare** (header + righe dati), costruire un insieme di **righe di campionamento** *R* con:
  * limite massimo **N_max** righe (costante documentata, non l’intero foglio salvo fogli piccoli sotto soglia);
  * priorità alle righe **subito dopo** l’header candidato o dopo la prima riga «densa» se `hasHeader = false`;
  * possibilità di **sottocampione stratificato**: prime righe dati + ultime righe nel budget (per catturare drift) senza superare N_max.
  * preferire, quando il foglio ha aspetto da **documento stampabile**, il rilevamento della prima **zona a pattern colonnare ripetuto** rispetto alle righe solo testuali o meta-documentali.

### Ignorare righe sporche / meta-documentali / decorative

* Escludere o pesare molto basso le righe che risultano:
  * quasi totalmente vuote o con densità di celle << soglia rispetto alle colonne attive;
  * chiaramente **titoli** o **blocchi testo** (es. una sola cella occupata su molte colonne) se rilevabili con euristiche **semplici** già allineate a TASK-025;
  * ripetizioni di intestazioni di sezione senza dati sotto (pattern da definire in modo minimale).
  * righe alterate da merge/riempimenti visuali che producono una densità apparentemente alta ma semanticamente povera (da intercettare con euristiche locali, non con ricostruzione completa del layout).

### Resistenza a poche righe anomale (outlier)

* Non basare il profilo colonna su **una singola riga** o su un minimo non robusto: usare **aggregati** (conteggi, mediane di lunghezza, quote) su *R*.
* Introdurre **trimming leggero** sulle osservazioni per cella: es. ignorare la cella più aberrante o usare soglie a maggioranza (es. pattern che vale su ≥ p% delle celle non vuote nel campione) — parametri **pochi e fissi**, no statistiche complesse.
* Obiettivo: **poche righe rumorose** nelle prime posizioni non devono rovesciare il mapping se il **corpo** della colonna è coerente.

### Campione insufficiente / densità troppo bassa

* Definire in Execution una soglia minima di **evidenza utile** per considerare auto-mappabile un campo: ad esempio numero minimo di celle non vuote osservate o quota minima di righe informative nel campione.
* Se il campione resta troppo povero dopo il filtering di righe sporche/decorative, il sistema non deve “inventare” una certezza: trattare il risultato come **bassa confidenza** o non mappare il campo, secondo la policy scelta in planning.
* La soglia deve restare **semplice e testabile**; niente regole dipendenti da troppe variabili implicite.

### Principio di semplicità

* Preferire **regole leggibili in mezza pagina** rispetto a modelli con molte soglie interdipendenti.
* Ogni soglia nuova deve avere **almeno un test** che la protegga o un commento che giustifichi il default.

---

## Conflitti numerici: distinzioni attese e tie-breaker (planning)

### Barcode vs `itemNumber` (entrambi possono essere «numerici»)

| Aspetto | Barcode (tipico) | Item number (tipico) |
|--------|-------------------|----------------------|
| Lunghezza | Spesso **lunga** (8–13+ cifre), spesso **uniforme** tra righe | Spesso **più corta** o più variabile, alfanumerica possibile |
| Formato | Quasi sempre **solo cifre** (normalizzate) | Può contenere **lettere**, trattini, zeri a sinistra significativi |
| Cardinalità | Molti valori **unici** (quasi identificatori) | Può avere **ripetizioni** o pattern catalogo |

**Esempi di conflitto atteso:** due colonne tutte numeriche; una è EAN-13, l’altra codice interno 6 cifre.

**Tie-breaker (ordine logico, da allineare al modello score):** 1) lunghezza media/mediana; 2) quota «solo cifre» e lunghezza quasi fissa → barcode; 3) variabilità alfanumerica / lunghezze miste → itemNumber; 4) header residuali; 5) indice colonna.

### `quantity` vs `purchasePrice` (entrambi possono essere «interi» o apparentemente interi)

| Aspetto | Quantità (tipico) | Prezzo acquisto (tipico) |
|--------|-------------------|---------------------------|
| Range | Spesso **interi piccoli/medi**, raramente valori enormi | Spesso **decimali** o valori con parte frazionaria (anche se a volte interi in file «puliti») |
| Contesto | Counting, unità intere | Monetario: ordini di grandezza **più ampi** di una semplice count (dipende dominio — usare confronto **relativo tra colonne** sullo stesso file) |
| Pattern | Ripetizione di pochi valori (1, 2, 6, 12…) possibile | Più spesso spread continuo su una scala diversa dalla «count» |

**Esempi di conflitto atteso:** colonna «importe» intero in CLP senza decimali vs colonna «cantidad» intera; entrambe numeriche positive.

**Tie-breaker:** 1) presenza sistematica di **separatori decimali** o formati prezzo nel testo grezzo; 2) confronto **distribuzione** (mediana, spread) tra le due colonne candidate sullo stesso campione; 3) header; 4) coerenza con **altre** colonne già mappate (es. se esiste `lineTotal`, il prezzo tende a correlare diversamente dalla qty — solo se euristica **semplice** e locale); 5) indice colonna.

---

## Diagnosi, tracciabilità e evidenze per review (planning)

### Traccia diagnostica

* In Execution, prevedere una **traccia interna** (struttura dati in memoria, log `debug` guardato da flag, o dump documentato nei test) che per ogni campo dominio *F* indichi:
  * colonna scelta e **score finale**;
  * **top-2 (o top-3) candidate** con score;
  * **motivo sintetico** (enum/stringa corta): es. `HEADER_ALIAS`, `BARCODE_LENGTH`, `TIE_INDEX`, `LOW_CONFIDENCE`;
  * colonne **scartate** con almeno un motivo per i finalisti (non serve per ogni colonna del foglio se fuori budget).
* La traccia **non** è obbligatoriamente user-facing; deve essere **riproducibile** e **incollabile** nel file task per la review.
* La diagnosi deve poter mostrare, quando rilevante, sia l’esito sul **valore grezzo** sia quello sul **valore normalizzato**, così da rendere spiegabili conflitti tipo barcode/itemNumber o prezzo/quantità.
* La traccia deve anche distinguere tra **ambiguità competitiva** (due colonne vicine) e **bassa evidenza strutturale** (campione insufficiente), così che la review capisca se il problema è un conflitto tra candidate o mancanza di segnale.

### Evidenze in review

* Il revisore deve poter valutare non solo «il mapping è giusto» ma **perché** è plausibile: allineamento tra traccia, test JVM e caso reale Shopping Hogar.
* I test sintetici dovrebbero dove possibile assertare **anche** la **decisione** (es. colonna 3 vince su 5) **e** il **ranking** atteso, non solo l’esito aggregato a valle.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Il file reale `20260404-Shopping Hogar.xls` si apre nel flusso preview/pre-generazione senza regressioni rispetto all’apertura attuale. | M / B | — |
| 2 | L’algoritmo identifica correttamente, sul caso reale, le principali colonne di dominio presenti e riconoscibili (`itemNumber`, `barcode`, `productName`, `quantity`, `purchasePrice`, ecc.). | M / S | — |
| 3 | In assenza di header affidabili, il mapping continua a funzionare grazie al contenuto colonna, non solo alla prima riga. | S / M | — |
| 3bis | **Documento stampabile gestito correttamente:** quando il foglio contiene blocchi intestazione multi-riga e una tabella dati incastonata, il parser individua la zona tabellare senza confondere i metadati documento con l’header delle colonne. | S / M | — |
| 4 | Nessun hardcode su nome file, nome foglio o nome fornitore nel path di produzione. | S | — |
| 5 | I casi con header esplicito pulito non peggiorano. | S | — |
| 6 | I casi senza header esplicito già compatibili non peggiorano. | S | — |
| 7 | Nessuna regressione su compatibilità **TASK-024** (`.xls` legacy / `.xlsx` Strict-transitional). | S | — |
| 8 | Nessuna regressione su cleanup strutturale **TASK-025**. | S | — |
| 8bis | **Fix del caso reale attuale:** il file fornitore visivamente / formattativamente degradato che ha motivato il task viene gestito correttamente nel mapping delle principali colonne di dominio, con evidenza esplicita in Execution. | M / S | — |
| 8ter | **Non regressione funzionale storica:** i file o fixture già riconosciuti correttamente prima del task continuano a produrre un mapping equivalente o migliore, senza peggioramenti immotivati. | S / M | — |
| 9 | `header`, `headerSource`, `headerTypes`, `dataRows` e strutture equivalenti restano coerenti/allineate dopo scoring/mapping. | S / B | — |
| 10 | `assembleDebug`, `lint` e test JVM rilevanti verdi, oppure documentati come non eseguibili con motivazione. | B / S | — |
| 11 | La sezione Execution include la **mini tabella arricchita** (vedi sotto) per almeno un caso reale e per i principali scenari sintetici. | — | — |
| 12 | Il file task documenta euristiche aggiunte/corrette, soglie/tie-breaker effettivi implementati e rischi residui. | — | — |
| 13 | **Determinismo:** stesso input e stesso codice producono lo stesso mapping (e stessa traccia diagnostica se abilitata) su fixture e sul caso reale. | S / M | — |
| 14 | **Ordine colonne:** permutando l’ordine delle colonne in una fixture sintetica (stessi dati, colonne riordinate), il mapping segue la **semantica del contenuto/header**, non la posizione accidentale — verificabile con almeno un test o evidenza documentata. | S | — |
| 15 | **Bassa confidenza:** quando due candidate sono entro ε, il comportamento è quello documentato in Execution (scelta deterministica + flag/confidenza bassa **oppure** non mappare — come deciso e implementato) e riflesso nella traccia. | S | — |
| 16 | **Spiegabilità minima:** per ogni campo dominio mappato automaticamente, la traccia diagnostica consente di capire **perché** la colonna vincitrice ha vinto e perché le alternative hanno perso (almeno top-2). | — / M | — |
| 17 | **Prestazioni:** il campionamento per scoring rispetta il budget di righe/colonne documentato; nessun passaggio aggiuntivo che legga l’intero foglio solo per il mapping colonne (salvo file piccoli sotto soglia esplicita). | S / Code review | — |
| 17bis | **Gate retrocompatibile efficace:** i casi già ad alta confidenza legacy possono restare su un percorso equivalente/cheap senza perdere correttezza, mentre i casi rumorosi o printable-layout attivano il ramo robusto quando necessario. | S / Code review | — |
| 18 | **Normalizzazione sicura:** il nuovo mapping non perde segnali semantici necessari per distinguere colonne numeriche simili (es. zeri iniziali, formato grezzo barcode/codice) prima che lo scoring abbia finito di usarli. | S / Code review | — |
| 19 | **Low-confidence UX coerente:** se viene introdotto un feedback UI per colonne incerte, è non bloccante, locale, coerente con lo stile Material3/app e non introduce nuovi flussi o passaggi manuali fuori scope. | M / Code review | — |
| 20 | **Campione insufficiente gestito in modo sicuro:** quando il numero di osservazioni utili è sotto soglia, il sistema non forza un mapping con falsa certezza; applica la policy documentata di low-confidence / non-mappatura senza rompere il flusso. | S / M | — |
| 21 | **Unicità assegnazione:** una stessa colonna non viene assegnata implicitamente a più campi dominio incompatibili se esiste un’assegnazione alternativa più coerente secondo la strategia globale documentata. | S / Code review | — |

Legenda: B=Build, S=Static/test JVM, M=Manuale.

**Mini tabella obbligatoria in Execution (specifica colonne):**

| Caso / fixture | hasHeader | Colonne candidate (indici o header) | Ranking / score sintetico (top per campo) | Mapping finale | Tie-breaker / confidenza | Traccia (motivo scelta/scarto) | Esito |
|----------------|-----------|-------------------------------------|--------------------------------------------|------------------|----------------------------|---------------------------------|-------|

*(Le celle «score» possono usare valori normalizzati o stringhe tipo `barcode: C3=0.82, C5=0.79` purché comparabili in review.)*

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Approccio ibrido header + contenuto con scoring esplicito | «Preview leggibile ≠ mapping corretto» | 2026-04-04 |
| 2 | Introdurre **confidence** per campo e **tie-breaker** ordinati, con **determinismo** esplicito | Reviewability, testabilità, assenza di comportamenti «a caso» | 2026-04-04 |
| 3 | Traccia diagnostica **documentabile** (test o log strutturato) obbligatoria come evidenza di review | Il task deve essere giudicabile oltre l’esito manuale sul file reale | 2026-04-04 |
| 4 | Campionamento a **budget limitato** sulle righe | Guardrail prestazionali; no full-sheet scan obbligatorio | 2026-04-04 |
| 5 | Preservare il **valore grezzo** abbastanza a lungo da distinguere pattern numerici ambigui | Evitare falsi positivi causati da normalizzazione precoce | 2026-04-04 |
| 6 | Eventuale feedback UX su bassa confidenza solo in forma **non bloccante** e coerente con lo stile esistente | Migliorare chiarezza senza cambiare flusso né mascherare il problema parser-side | 2026-04-04 |
| 7 | Distinguere esplicitamente **ambiguità competitiva** da **bassa evidenza strutturale** | Migliorare diagnosi, review e policy low-confidence | 2026-04-04 |
| 8 | Non forzare auto-mapping ad alta apparente certezza quando il campione utile è insufficiente | Evitare falsi positivi mascherati da score numerici debolmente supportati | 2026-04-04 |
| 9 | Strategia globale preferita: **greedy con lock colonna** e tie-breaker espliciti | Semplice, deterministica, sufficiente per evitare doppie assegnazioni incoerenti senza over-engineering | 2026-04-04 |
| 10 | Policy low-confidence preferita: **non mappare** se manca evidenza sufficiente; **auto-selezione con hint discreto** solo se esiste un vincitore plausibile ma con margine basso | Ridurre falsi positivi senza introdurre UX invasiva o blocchi di flusso | 2026-04-04 |
| 11 | Priorità di prodotto: risolvere il caso reale corrotto attuale **senza** impattare negativamente sui file già storicamente verdi | Il task è un miglioramento mirato ma deve restare retrocompatibile | 2026-04-04 |
| 12 | Le nuove euristiche devono attivarsi soprattutto nei casi rumorosi/degradati, mentre i casi già ad alta confidenza devono restare quanto più possibile stabili | Ridurre rischio regressioni e mantenere prevedibilità sui file già supportati | 2026-04-04 |
| 13 | Nei fogli con aspetto da documento stampabile, privilegiare il rilevamento della **zona tabellare ripetuta** rispetto ai primi blocchi testuali del documento | Ridurre falsi header e migliorare robustezza sul caso reale senza hardcode file-specific | 2026-04-04 |
| 14 | Introdurre un **gate legacy-high-confidence** prima del ramo robusto | Ridurre regressioni e costo sui casi già verdi, lasciando il ramo avanzato ai file rumorosi/degradati | 2026-04-04 |

---

## Planning (Claude)

### Analisi

Task **semantico** di follow-up ai parser già chiusi (TASK-024/025/026): focus su **riconoscimento robusto colonne**, non solo pulizia strutturale. Questa revisione di planning aggiunge **contratto di qualità** (determinismo, spiegabilità, prestazioni) senza espandere il perimetro funzionale.

### Piano di execution proposto (aggiornato dopo review repo-grounded)

**Fase 0 — Preflight reale**

* riprodurre `20260404-Shopping Hogar.xls` in preview;
* confermare quale riga/segmento è oggi interpretato come header;
* estrarre profilo sintetico delle righe nel **campione** (non tutto il foglio salvo necessità);
* documentare dove il mapping attuale sbaglia e **catturare traccia** «before» (anche manuale) per confronto in review.

**Fase 1 — Fix strutturale split-header e zona tabellare (PRIORITÀ ALTA)**

Interventi mirati su `analyzeRows` in `ExcelUtils.kt`:

1. **Split-header**: quando `dataRowIdx > 0`, non prendere solo `rows[dataRowIdx - 1]` come header. Controllare se la riga immediatamente sopra (`dataRowIdx - 2`, se esiste) contiene celle non vuote che completano l’header. Costruire l’header combinato fondendo le celle non vuote delle righe candidate (preferenza alla riga più alta se entrambe non vuote nella stessa colonna). Limite: max 2–3 righe di lookback, non scansione illimitata.

2. **Filtro righe metadato pre-tabellari**: prima di cercare `dataRowIdx`, tentare di identificare la **zona tabellare** con euristiche semplici:
   * una riga con molte celle non vuote distribuite su colonne distinte (profilo "colonnare ripetuto") è candidata a essere header tabellare;
   * una riga con poche celle lunghe (tipicamente 1–2) è probabilmente un titolo/metadato;
   * implementare come pre-filtro leggero, non come ricostruzione completa del layout.

3. **Preservare il fast-path legacy**: se la riga `dataRowIdx - 1` da sola produce un alias match forte (es. ≥ 3 alias riconosciuti), non attivare il lookback — il file è già pulito.

**Fase 2 — Miglioramento pattern detection con scoring differenziato**

* definire criteri **distinti** per `quantity` vs `purchasePrice`: non la stessa euristica identica. Differenziare per range (interi piccoli vs valori più grandi), presenza di decimali, distribuzione;
* definire criteri **distinti** per `barcode` vs `itemNumber`: lunghezza media/mediana, uniformità di lunghezza, quota solo-cifre;
* opzionale: introdurre `score(F, C)` e `confidence(F)` per i casi ambigui; implementare greedy con lock colonna;
* tradurre in regole implementative la policy low-confidence: **campo non mappato** se evidenza insufficiente; **auto-selezione con hint** solo se vincitore plausibile ma margine basso.

**Fase 3 — Test e non regressione**

Matrice scenari (minimo richiesto):

| # | Scenario | Atteso |
|---|----------|--------|
| A | Header esplicito e pulito | Nessuna regressione; mapping invariato o migliorato senza peggiorare score/confidenza |
| B | Nessun header esplicito ma contenuto chiaro | Mapping corretto da contenuto |
| C | **Header fuorviante** ma contenuto colonna coerente con il dominio | Contenuto / pattern vincono su header rumoroso |
| D | **Prime righe rumorose**, corpo tabella pulito dopo | Profilo colonna stabile grazie a campionamento oltre le prime righe e/o aggregati robusti |
| E | Barcode e itemNumber **entrambi numerici** | Distinzione corretta secondo tie-breaker documentati |
| F | Quantità e prezzo **entrambi interi** (o senza decimali visibili) | Distinzione tramite distribuzione / segnali secondari / header, senza invertire sistematicamente |
| G | Colonne decorative, sparse, quasi vuote | Non selezionate come campi dominio; non «rubano» score a colonne valide |
| H | `.xls` legacy / Strict OOXML (**TASK-024**) | Nessuna regressione |
| I | Multi-file / flussi esistenti | Nessuna incompatibilità nuova |
| J | **Regressione esplicita:** fixture header pulito e ramo no-header già verde in main | Output mapping (e test golden) allineati o migliori, mai peggiori senza motivazione accettata |
| K | **Normalizzazione critica:** fixture con zeri iniziali / codici numerici lunghi / testo grezzo che cambierebbe significato se normalizzato troppo presto | Mapping corretto e spiegabile; il test protegge il confine raw vs normalized |
| L | **Bassa confidenza con UI discreta** (solo se il ramo UI viene introdotto) | Nessun blocco di flusso; feedback locale coerente; mapping/diagnosi allineati alla policy scelta |
| M | **Campione insufficiente / densità bassa:** poche celle informative dopo il filtering di righe sporche | Nessuna falsa certezza: low-confidence o non-mappatura secondo policy, con diagnosi esplicita |
| N | **Conflitto di assegnazione globale:** una colonna sembra buona per due campi, ma esiste una combinazione complessiva migliore | Strategia globale coerente; nessuna doppia assegnazione incompatibile |
| O | **Caso reale corrotto attuale:** file fornitore Shopping Hogar con layout molto sporco / visivamente degradato | Mapping corretto delle colonne chiave con traccia diagnostica esplicita; il fix risolve il difetto che ha motivato il task |
| P | **Set storico di regressione:** fixture/file già verdi prima del task | Mapping equivalente o migliore; nessun peggioramento funzionale immotivato |
| Q | **Documento stampabile con intestazione multi-riga + tabella incastonata** | La zona tabellare viene isolata correttamente; i metadati del documento non vengono scambiati per header o prime righe dati utili |
| R | **Legacy high-confidence:** file/fixture già verde con header e contenuto forti | Early-exit o percorso equivalente confermato; mapping invariato o migliore senza attivare inutilmente euristiche aggressive |

**Fase 4 — Chiusura execution**

* mini tabella arricchita (vedi criteri #11) per caso reale + scenari chiave;
* traccia diagnostica allegata o riassunta nel task;
* elenco soglie/tie-breaker effettivi vs questo planning (delta motivato);
* passaggio a `REVIEW`.

### Rischi identificati

| Rischio                                                       | Mitigazione                                                       |
| ------------------------------------------------------------- | ----------------------------------------------------------------- |
| Confondere `barcode` e `itemNumber`                           | Score su lunghezza, uniformità, alfanumericità; tie-breaker § Conflitti numerici |
| Confondere `quantity` e `purchasePrice`                       | Distribuzione, segni di decimali, confronto relativo; tie-breaker § |
| Overfitting Shopping Hogar                                    | Test sintetici A–J + divieto hardcode file-specific               |
| Peggiorare header già buono                                   | Alta confidenza header → peso forte; early exit o soglia minima   |
| Rompere `hasHeader = false`                                   | Scenari B, D, J dedicati                                          |
| Complessità / full-sheet scan                                 | Budget righe; niente nuove dipendenze; regole semplici            |
| Traccia troppo verbosa                                        | Limitare a top-K candidate per campo; formato tabellare in Execution |
| Perdere segnali utili per normalizzazione precoce                    | Guardrail raw vs normalized + scenario K dedicato              |
| Aggiungere feedback UI troppo invasivo per la bassa confidenza      | Limitare a hint/badge discreti; vietati nuovi flussi           |
| Forzare mapping su campione troppo debole                           | Soglia minima di evidenza + scenario M dedicato                    |
| Assegnazione greedy/localmente buona ma globalmente incoerente      | Vincolo di unicità assegnazione + scenario N dedicato              |
| Risolvere il caso attuale ma rompere file già storicamente corretti      | Scenario P dedicato + principio di compatibilità retroattiva      |
| Overfit sul layout corrotto reale invece di generalizzare il pattern      | Scenario O sul caso reale + scenario P e fixture sintetiche       |
| Scambiare l’intestazione del documento stampabile per header tabellare     | Scenario Q dedicato + rilevamento zona colonnare ripetuta         |
| Applicare il ramo robusto anche ai casi già chiarissimi, introducendo regressioni o costo inutile | Gate legacy-high-confidence + scenario R dedicato                 |
| **Split-header**: combinare più righe può produrre header spuri se le righe sopra non sono davvero parte dell'header | Gate: attivare lookback solo se la riga singola produce alias match debole (< 3 alias); limitare lookback a max 2–3 righe; preferire celle non vuote della riga più vicina ai dati |
| **Metadata false positive**: righe albaran con valori numerici in celle separate triggano `numericCount >= 3` troppo presto | Pre-filtro zona tabellare: distinguere righe "colonnari" da righe "titolo/metadato" prima di cercare dataRowIdx |

---

## Handoff planner / reviewer

* «Preview leggibile» non implica mapping corretto: serve euristica **ibrida** con **evidenze** (score, ranking, traccia).
* In review, verificare **criteri #13–#21** e il nuovo **#17bis** oltre ai mapping funzionali.
* Verificare in particolare che la policy scelta in planning resti coerente: **non mappare** nei casi di evidenza insufficiente; **hint discreto** solo nei casi di vincitore plausibile ma low-confidence.
* Verificare esplicitamente sia il **fix del caso reale corrotto attuale** sia la **non regressione** sui file già storicamente riconosciuti correttamente: il task non è accettabile se migliora Shopping Hogar ma peggiora il comportamento legacy.
* Sul caso reale, verificare anche che i **blocchi documento/intestazione** non vengano più interpretati come header tabellare: la review deve distinguere chiaramente tra zona documento e zona dati.
* Se in Execution il problema è più ristretto del previsto, restringere il diff mantenendo guardrail e criteri di non regressione.

---

## Execution

_(Compilare solo quando il task passa a **EXECUTION**. In **PLANNING** questa sezione resta vuota di evidenze reali.)_

### Esecuzione — 2026-04-04

**File modificati:**

- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — fix parser-side su split-header, isolamento zona tabellare, scoring/tracing deterministici, parsing numerico interno per interi con separatore migliaia, guardrail `REF.CAJAS` row-number-like, pairing `quantity × purchasePrice -> totalPrice`, cleanup review di codice morto post-pruning.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — regressioni mirate su legacy fast-path, split-header printable, conflitti barcode/itemNumber, conflitti quantity/purchasePrice, fixture Shopping Hogar-like con offset reali e totali `10,800`, protezione esplicita del caso `REF.CAJAS` valido come `itemNumber`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ShoppingHogarLocalDebugTest.kt` — supporto locale di debug/evidenza sul file reale Shopping Hogar; usato solo in workspace locale, non come parte del deliverable tracciato.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — copertura end-to-end preview/mapping su printable split-header e fixture Shopping Hogar-like.
- `docs/TASKS/TASK-042-robustezza-identificazione-colonne-formatting-sporco.md` — log Execution, check, criteri verificati, mini tabella mapping, handoff per review.

**Azioni eseguite:**

1. Letti `docs/MASTER-PLAN.md`, `docs/TASKS/TASK-042-robustezza-identificazione-colonne-formatting-sporco.md`, `docs/CODEX-EXECUTION-PROTOCOL.md` e il codice reale di `ExcelUtils.kt` / `ExcelViewModel.kt` / test correlati prima di intervenire.
2. Confermato sul codice il ramo robusto già avviato in `ExcelUtils.kt`: `RowProfile`, `detectHeader`, `mergeHeaderRows`, `alignTabularWidths`, trace strutturata (`ExcelAnalysisTrace`) e campionamento a budget (`MAX_PATTERN_SAMPLE_ROWS = 40`, lookback max 2 righe, fast-path legacy con `>= 3` alias).
3. Riprodotto il caso reale locale `/Users/minxiang/Downloads/20260404-Shopping Hogar.xls` con test di debug non tracciato: problema residuo reale = `REF.CAJAS` intercettato come `itemNumber` e mancata attivazione del pairing prezzo/totale perché `10,800` veniva letto come `10.8`.
4. Introdotto `parseAnalysisNumber(...)` solo nel path interno di analisi/scoring, senza cambiare `parseNumber(...)` pubblico: ora gli interi con separatore migliaia (`1,100`, `10,800`, `12,000`) restano utilizzabili per ranking e pairing, preservando il valore grezzo nei dati importati.
5. Spostato il matching alias dopo la potatura colonne/campionamento e aggiunto il gate `shouldSkipHeaderAlias(...)`: se `REF.CAJAS` cade su una colonna progressiva sequenziale (`rowNumberLikeRatio >= 0.75`), l’alias `itemNumber` viene rifiutato e il campo torna al fallback pattern-based.
6. Rafforzata la distinzione `quantity` vs colonna progressiva con penalità esplicita sul segnale sequenziale (`seq=` nella trace): questo evita che il progressivo rubi il ruolo di `quantity` nei printable-layout e permette l’attivazione affidabile del pairing `quantity × purchasePrice -> totalPrice`.
7. Mantenuto il comportamento low-confidence coerente col planning: se il margine tra candidate resta sotto `AMBIGUITY_MARGIN = 0.08`, il campo non viene forzato; quando la moltiplicazione `quantity × price` è forte, `purchasePrice` e `totalPrice` vengono assegnati in modo deterministico con `reason = quantity-multiplication`.
8. Aggiornati i test tracciati con fixture che replicano sia il caso legacy/clean sia il printable-layout con header spezzato e dati incastonati, oltre ai conflitti numerici no-header già verdi; in review è stato aggiunto anche il caso opposto di protezione `REF.CAJAS` non progressivo, che deve restare `itemNumber`.
9. Ripulito un ramo morto di remapping post-pruning rimasto dopo lo spostamento dell’alias matching: nessun cambio funzionale atteso, solo riduzione del rumore implementativo.
10. Eseguiti i check richiesti: `testDebugUnitTest`, `assembleDebug`, `lint` e una verifica finale sul file reale Shopping Hogar tramite test locale di debug; in review, rieseguite baseline mirata (`ExcelUtilsTest`, `ExcelViewModelTest`) e suite JVM completa con `JAVA_HOME` esplicito al JBR di Android Studio.

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅ ESEGUITO | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false assembleDebug` verde |
| Lint                     | S    | ✅ ESEGUITO | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false lint` verde |
| Warning Kotlin           | S    | ✅ ESEGUITO | Nessun warning nuovo dai file toccati; restano solo warning/deprecazioni preesistenti in `DatabaseScreenComponents.kt`, `HistoryScreen.kt` e warning AGP/Kotlin di progetto |
| Coerenza con planning    | —    | ✅ ESEGUITO | Fix strutturale split-header + zona tabellare prima; poi miglioramento mapping `barcode/itemNumber` e `quantity/purchasePrice`; fast-path legacy e low-confidence preservati |
| Criteri di accettazione  | —    | ✅ ESEGUITO | Verifica puntuale 1–21 riportata sotto |

**Verifica criteri di accettazione:**

| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | Il file reale `20260404-Shopping Hogar.xls` viene aperto e analizzato dal test locale di debug senza regressioni di apertura (`BUILD SUCCESSFUL`) |
| 2 | ✅ ESEGUITO | Evidenza reale: header finale `REF.CAJAS | itemNumber | barcode | productName | quantity | purchasePrice | totalPrice`, 30 righe dati, prima riga `1 | 10161 | 6120000101614 | XJ2204-3马桶刷 | 12 | 900 | … | 10,800` |
| 3 | ✅ ESEGUITO | Restano verdi i casi no-header su `ExcelUtilsTest` / `ExcelViewModelTest` e la suite JVM completa |
| 3bis | ✅ ESEGUITO | Caso reale e fixture printable usano `headerMode = combined-lookback`, con metadati documento esclusi dalla zona tabellare |
| 4 | ✅ ESEGUITO | Nessun hardcode su nome file/foglio/fornitore nel path di produzione; la sola regola specifica è sull’alias testuale `REF.CAJAS`, non sul fornitore |
| 5 | ✅ ESEGUITO | Test `analyzePoiSheet keeps legacy single-row header fast path for clean files` verde con `headerMode = legacy-fast-path` |
| 6 | ✅ ESEGUITO | Verdi i casi no-header storici e i merge multi-file già coperti in `ExcelViewModelTest` |
| 7 | ✅ ESEGUITO | `testDebugUnitTest` verde include ancora le regressioni `.xls` legacy / Strict OOXML |
| 8 | ✅ ESEGUITO | Verdi i test `readAndAnalyzeExcel cleans no-header structural blanks...` e `readAndAnalyzeExcel and analyzePoiSheet stay aligned...` |
| 8bis | ✅ ESEGUITO | Caso reale Shopping Hogar verificato esplicitamente con dump locale finale e mapping corretto delle colonne chiave |
| 8ter | ✅ ESEGUITO | Suite JVM completa verde (`163 tests completed, 0 failed`) e test storici su mapping puliti/no-header invariati o migliorati |
| 9 | ✅ ESEGUITO | `header`, `headerSource`, `excelData` e trace coerenti in `ExcelUtilsTest` e `ExcelViewModelTest` |
| 10 | ✅ ESEGUITO | `testDebugUnitTest`, `assembleDebug`, `lint` verdi |
| 11 | ✅ ESEGUITO | Mini tabella mapping compilata sotto con caso reale + sintetici principali |
| 12 | ✅ ESEGUITO | Euristiche, soglie e tie-breaker implementati sono documentati in questa sezione Execution |
| 13 | ✅ ESEGUITO | Output e trace deterministici su rerun locali del caso reale e delle fixture sintetiche |
| 14 | ✅ ESEGUITO | Le fixture no-header numeriche già verdi usano ordini colonna non canonici e continuano a mappare per semantica (`barcode/itemNumber`, `quantity/purchasePrice`) |
| 15 | ✅ ESEGUITO | I campi ambigui restano non mappati quando il margine è insufficiente; il comportamento è tracciato con `reason = low-confidence` |
| 16 | ✅ ESEGUITO | `ExcelAnalysisTrace` espone top candidate, score e motivo sintetico per ogni campo chiave |
| 17 | ✅ ESEGUITO | Budget esplicito: `MAX_PATTERN_SAMPLE_ROWS = 40`, lookback max 2, nessuna scansione addizionale full-sheet solo per scoring |
| 17bis | ✅ ESEGUITO | Ramo cheap/legacy protetto dal fast-path; printable-layout e casi rumorosi attivano il ramo robusto |
| 18 | ✅ ESEGUITO | Il dato grezzo resta in `dataRows`; la nuova interpretazione numerica è limitata al path interno di analisi (`parseAnalysisNumber`) |
| 19 | ✅ ESEGUITO | Nessun cambiamento UI/UX introdotto: nessun nuovo hint, badge o flusso manuale |
| 20 | ✅ ESEGUITO | `minimumEvidenceFor(...)` + `shouldAssignCandidate(...)` evitano mapping forzati in caso di evidenza insufficiente |
| 21 | ✅ ESEGUITO | Strategia globale semplice con `usedCols` lock + pairing dedicato `purchasePrice/totalPrice`; nessuna doppia assegnazione incoerente |

**Baseline regressione TASK-004 (se applicabile):**

* Test eseguiti: `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false testDebugUnitTest` (suite completa); verifica mirata anche su `ExcelUtilsTest`, `ExcelViewModelTest` e sul file reale locale con `ShoppingHogarLocalDebugTest.dumpShoppingHogarCurrentMapping`.
* Test aggiunti/aggiornati: `analyzePoiSheet keeps legacy single-row header fast path for clean files`; `analyzePoiSheet isolates printable table and combines split headers`; `analyzePoiSheet keeps REF CAJAS as item number when values are not row-like`; `analyzePoiSheet distinguishes barcode and item number on headerless numeric columns`; `analyzePoiSheet distinguishes quantity and purchase price when both are integers`; `analyzePoiSheet handles printable shopping layout with grouped integer totals`; `loadFromMultipleUris handles printable split header workbook`; `loadFromMultipleUris handles shopping hogar printable offsets with grouped totals`; helper `createWorkbook(...)` esteso con `List<List<Any?>>` + `configure`.
* Limiti residui: nessun test emulator/device richiesto o introdotto; la verifica sul file reale usa una fixture locale non tracciata (`/Users/minxiang/Downloads/20260404-Shopping Hogar.xls`); nel caso reale restano raw/non tipizzate le colonne secondarie decorative (`REF.CAJAS` come header grezzo, sconto / secondo `PRE/U`) ma i campi di dominio prioritari sono corretti e i valori non vengono persi.

**Mini tabella mapping (obbligatoria a chiusura Execution — usare colonne definite in Criteri #11):**

| Caso / fixture | hasHeader | Colonne candidate | Ranking / score sintetico | Mapping finale | Tie-breaker / confidenza | Traccia | Esito |
|----------------|-----------|-------------------|---------------------------|----------------|--------------------------|---------|-------|
| Reale locale `20260404-Shopping Hogar.xls` | Sì | `itemNumber`: C1/C7/C0; `quantity`: C4/C6/C5; pairing `purchasePrice/totalPrice`: C5/C8 | `itemNumber`: C1=0.80 > C7=0.65 > C0=0.35; `quantity`: C4=0.94 > C6=0.75 > C5=0.61; `pair`: C5/C8 match=1.00 | `REF.CAJAS | itemNumber | barcode | productName | quantity | purchasePrice | totalPrice` | `REF.CAJAS` alias rifiutato se row-number-like; `quantity` alta; `purchasePrice`/`totalPrice` alti via moltiplicazione | `headerMode=combined-lookback`, `headerRows=[10,11]`, `reason=itemNumber pattern-score`, `reason=purchasePrice quantity-multiplication` | OK |
| Sintetico printable offsets + grouped totals (`ExcelUtilsTest`) | Sì | `barcode`, `productName`, `itemNumber`, `quantity`, `purchasePrice`, `totalPrice` su righe sparse con header spezzato | `barcode`: top=1.00; `purchasePrice/totalPrice`: pair=1.00; `itemNumber`: pattern-score su colonna codice interno | Campi chiave riconosciuti con valori `10161`, `6120000101614`, `12`, `900`, `10,800` | Nessun hardcode; confidenza alta sui campi chiave | `combined-lookback`, `reason=itemNumber pattern-score`, `reason=quantity-multiplication` | OK |
| Header pulito legacy (`ExcelUtilsTest`) | Sì | Alias diretti su singola riga header | Fast-path alias diretto | `barcode | productName | purchasePrice | quantity | totalPrice` | High confidence; nessun ramo robusto aggressivo | `headerMode=legacy-fast-path` | OK |
| No-header numerico `barcode` vs `itemNumber` (`ExcelUtilsTest`) | No | Colonne tutte numeriche con barcode lungo e codice articolo corto | `barcode`: C2=1.00 > C1=0.45; `itemNumber`: pattern-score su colonna codice corto | `itemNumber | barcode | productName | quantity | purchasePrice | totalPrice` | Tie-breaker su lunghezza/uniformità; confidenza alta | `reason=pattern-score` su entrambi | OK |
| No-header `quantity` vs `purchasePrice` entrambi interi (`ExcelUtilsTest`) | No | Colonne numeriche positive senza decimali visibili | `quantity` batte la colonna progressiva grazie a `seq`; `purchasePrice` resta distinto | `quantity` corretto su `12`; `purchasePrice` corretto su `900` | Penalità esplicita su colonne sequenziali row-number-like | `reason=pattern-score`, `confidence=high` | OK |

**Incertezze:**

* Sul caso reale Shopping Hogar, le colonne secondarie decorative/di servizio restano volutamente raw o non tipizzate (`REF.CAJAS` come header grezzo, sconto e secondo `PRE/U` non promossi a campo dominio) perché il task era focalizzato sul mapping corretto delle colonne chiave e sul diff minimo.
* La verifica sul file reale dipende da una fixture locale non tracciata e da un test di debug non committato; la copertura versionata usa una fixture sintetica aderente al layout reale.
* Nessuna verifica manuale su emulator/device è stata eseguita perché il task è parser-side e il planning non la richiede esplicitamente.

---

## Review

### Review — 2026-04-04

**Revisore:** Claude (planner)

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Il file reale `20260404-Shopping Hogar.xls` si apre nel flusso preview/pre-generazione senza regressioni rispetto all’apertura attuale. | ✅ | Verificato su file reale locale con `ShoppingHogarLocalDebugTest` + build verdi |
| 2 | L’algoritmo identifica correttamente, sul caso reale, le principali colonne di dominio presenti e riconoscibili (`itemNumber`, `barcode`, `productName`, `quantity`, `purchasePrice`, ecc.). | ✅ | Dump reale: `itemNumber`, `barcode`, `productName`, `quantity`, `purchasePrice`, `totalPrice` corretti |
| 3 | In assenza di header affidabili, il mapping continua a funzionare grazie al contenuto colonna, non solo alla prima riga. | ✅ | Coperto da test no-header numerici e suite JVM completa verde |
| 3bis | Documento stampabile gestito correttamente. | ✅ | `combined-lookback` e isolamento zona tabellare verificati su fixture printable e caso reale |
| 4 | Nessun hardcode su nome file, nome foglio o nome fornitore nel path di produzione. | ✅ | Nessun riferimento a file/foglio/fornitore; solo euristica reviewable su alias `REF.CAJAS` + pattern sequenziale |
| 5 | I casi con header esplicito pulito non peggiorano. | ✅ | `legacy-fast-path` protetto e testato |
| 6 | I casi senza header esplicito già compatibili non peggiorano. | ✅ | Test no-header storici ancora verdi |
| 7 | Nessuna regressione su compatibilità TASK-024 (`.xls` legacy / `.xlsx` Strict-transitional). | ✅ | Inclusa nella suite `testDebugUnitTest` verde |
| 8 | Nessuna regressione su cleanup strutturale TASK-025. | ✅ | Test cleanup strutturale ancora verdi |
| 8bis | Fix del caso reale attuale. | ✅ | Shopping Hogar reale migliorato con evidenza parser-side esplicita |
| 8ter | Non regressione funzionale storica. | ✅ | Suite JVM completa verde dopo i micro-fix di review |
| 9 | `header`, `headerSource`, `headerTypes`, `dataRows` e strutture equivalenti restano coerenti/allineate dopo scoring/mapping. | ✅ | Verificato in `ExcelUtilsTest` e `ExcelViewModelTest` |
| 10 | `assembleDebug`, `lint` e test JVM rilevanti verdi, oppure documentati come non eseguibili con motivazione. | ✅ | `assembleDebug`, `lint`, baseline mirata e suite JVM completa verdi |
| 11 | La sezione Execution include la mini tabella arricchita. | ✅ | Presente e coerente con le evidenze |
| 12 | Il file task documenta euristiche aggiunte/corrette, soglie/tie-breaker effettivi implementati e rischi residui. | ✅ | `Execution` aggiornata con soglie, guardrail, limiti residui |
| 13 | Determinismo. | ✅ | Mapping/trace stabili su fixture e rerun locali |
| 14 | Ordine colonne. | ✅ | Coperto dai test no-header con ordini non canonici |
| 15 | Bassa confidenza. | ✅ | Policy `low-confidence` presente e non bloccante |
| 16 | Spiegabilità minima. | ✅ | `ExcelAnalysisTrace` espone candidate, reason e confidence |
| 17 | Prestazioni. | ✅ | Budget esplicito: `MAX_PATTERN_SAMPLE_ROWS = 40`, no full-sheet addizionale per scoring |
| 17bis | Gate retrocompatibile efficace. | ✅ | `legacy-fast-path` protegge i casi puliti, `combined-lookback` entra solo quando serve |
| 18 | Normalizzazione sicura. | ✅ | `parseAnalysisNumber(...)` confinato allo scoring interno; `parseNumber(...)` pubblico invariato |
| 19 | Low-confidence UX coerente. | ✅ | Nessun cambiamento UI/UX introdotto |
| 20 | Campione insufficiente gestito in modo sicuro. | ✅ | Soglia minima + non assegnazione in caso di margine insufficiente |
| 21 | Unicità assegnazione. | ✅ | Lock `usedCols` + pairing dedicato evitano doppie assegnazioni incoerenti |

**Problemi trovati:**

- Nessun problema bloccante residuo dopo i micro-fix review.
- Problemi non bloccanti già corretti in review:
  - cleanup di un ramo morto post-pruning in `ExcelUtils.kt`;
  - aggiunta test di protezione per `REF.CAJAS` valido come `itemNumber` non progressivo.

**Verdetto:** APPROVED

**Note per fix:**
- Nessun fix ulteriore richiesto nel perimetro di `TASK-042`.
- Mantenere `ShoppingHogarLocalDebugTest.kt` come evidenza locale/non tracciata, non come test portabile di suite.

---

## Fix

### Fix — [data]

_(solo se FIX_REQUIRED post-review)_

---

## Chiusura

| Campo           | Valore |
|-----------------|--------|
| Stato finale    | **DONE** |
| Data chiusura   | 2026-04-04 |
| Tutti i criteri | **Sì** — tabella Review 2026-04-04: 21/21 ✅; evidenze in **Execution** (check build/lint/test, mini tabella mapping). |
| Rischi residui  | **Non bloccanti:** verifica sul file reale Shopping Hogar dipende da path locale e da `ShoppingHogarLocalDebugTest.kt` come evidenza di debug (non parte della suite portabile); colonne decorative/secondarie sul caso reale possono restare raw/non tipizzate come documentato in Execution. |

---

## Riepilogo finale

* **Caso reale Shopping Hogar:** corretto nel perimetro parser-side (split-header / zona tabellare / scoring differenziato, `parseAnalysisNumber` per totali con migliaia, guardrail `REF.CAJAS` row-number-like, pairing prezzo/totale). Mapping delle colonne di dominio principali allineato alle attese documentate in Execution.
* **Non regressione:** nessuna regressione funzionale emersa sui casi già verdi; baseline mirata `ExcelUtilsTest` + `ExcelViewModelTest` e suite `testDebugUnitTest` confermate verdi in chiusura.
* **Review:** verdetto formale **APPROVED** (2026-04-04); nessun finding bloccante o medio residuo.
* **Cautela documentata (non bloccante):** `ShoppingHogarLocalDebugTest.kt` resta evidenza locale per il file reale, **non** test portabile della suite CI — non sostituisce le fixture versionate in `ExcelUtilsTest` / `ExcelViewModelTest`.

---

## Handoff

**Stato task:** **DONE** (2026-04-04) — chiusura documentale dopo review **APPROVED** e conferma utente.

**Per operatori successivi:**

* La logica vive principalmente in `ExcelUtils.kt` (path `analyzeRows` / traccia `ExcelAnalysisTrace`); estensioni future: nuovi layout fornitore preferibilmente con fixture JVM + aggiornamento soglie documentate, senza hardcode su nome file/foglio/fornitore.
* **Non** fare affidamento su `ShoppingHogarLocalDebugTest.kt` come gate di regressione in CI: mantenerlo opzionale/locale o equivalente evidenza manuale sul file reale se necessario.

**Smoke/manuale (opzionale, fuori obbligo task):**

* Aprire in preview `20260404-Shopping Hogar.xls` e verificare allineamento griglia / flusso generazione su device reale se si desidera doppio controllo oltre ai test JVM.
