# TASK-073 — Filtro conservativo righe riepilogo/footer Excel ordini (合计 / 总数+总价 / aggregate)

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-073 |
| Stato | DONE |
| Priorità | ALTA |
| Area | Import / Excel / parsing / PreGenerate / Generated / ImportAnalysis |
| Creato | 2026-05-16 |
| Ultimo aggiornamento | 2026-05-17 00:57 -04 — Review end-to-end APPROVED con fix applicati; TASK-073 chiuso DONE |

---

## Dipendenze

- **TASK-043** (`DONE`) — esclusione righe totali/footer da `dataRows` (`isSummaryRow`, `summaryTokens`, helper collegati); questo task è un **follow-up/regression fix mirato** su casi reali rimasti permeabili o riemersi. In Execution è obbligatorio verificare prima il codice corrente: se TASK-043 ha già introdotto helper equivalenti (`isSummaryLabel`, `hasPlausibleProductIdentity`, `hasShiftedAggregatePattern` o simili), **non duplicarli** e rafforzare solo la condizione esistente.
- **TASK-005** (`DONE`) — baseline test `ExcelUtils` / `analyzePoiSheet`.
- **TASK-072** — **non è prerequisito**: riservato a follow-up **backend** RPC `record_sync_event` (Android separato).
- **MASTER-PLAN** — al momento della creazione TASK-073 deve risultare un solo task attivo. Se `MASTER-PLAN.md` è ancora fermo a “Nessun task attivo” o non cita TASK-073, aggiornare la governance prima di qualsiasi Execution.
- **File reali allegati** — usabili come evidenza manuale/local-only; non committare workbook binari in repo salvo decisione esplicita. I test automatici devono preferire fixture POI in-memory piccole e leggibili.

---

## Scopo

Eliminare in modo **conservativo** e **pattern-based** le **righe finali di riepilogo/somma** presenti in alcuni file Excel ordine (tipicamente fornitori cinesi), così che **non** entrino in `dataRows`, **non** compaiano in anteprima PreGenerate, **non** arrivino a GeneratedScreen / ImportAnalysis e **non** diventino righe prodotto in import database. Il file `.xlsx` originale **non** viene modificato.

L’obiettivo non è “rendere più aggressivo” il parser, ma chiudere una classe precisa di footer aggregati mantenendo intatti i riconoscimenti attuali di header, colonne, righe prodotto, prezzi e quantità. La soluzione deve privilegiare **segnali multipli** e non una blacklist fragile.


La soluzione preferita è trattare queste righe come **trailing footer non-prodotto**: intervenire sul risultato tabellare già normalizzato e filtrare solo righe con profilo da riepilogo, evitando sia patch basate sul nome file sia controlli troppo globali che potrebbero intercettare testo legittimo dentro una descrizione prodotto.

Per evitare overengineering, l’Execution deve puntare prima al **caso minimo reale**: footer finali `总数`/`总价` e `合计` che entrano erroneamente in preview. Le coperture su righe meta/stampa, full-width CJK e HTML/Excel-like sono importanti come **guardrail**, ma **non** devono trasformare il task in un refactor generale del parser.


### Invarianti del task

Questi invarianti devono restare veri anche dopo Execution:

- `dataRows` mantiene lo stesso ordine originale delle righe prodotto riconosciute.
- Il filtro può **rimuovere** righe non-prodotto, ma non deve mai riordinare, fondere o modificare il contenuto delle righe prodotto.
- Il filtro non deve cambiare `header`, `headerSource`, mapping colonne o logica di scoring header.
- Il filtro non deve introdurre side effect: nessuna scrittura file, nessuna scrittura DB, nessun aggiornamento ViewModel.
- La stessa decisione parser-side deve valere per preview, generate, import analysis e test pubblici.
- Il filtro deve preservare lo **stesso comportamento** tra workbook **POI** (`.xls`/`.xlsx`) e fallback **HTML/Excel-like**, salvo limite **documentato** con test.
- Il filtro **non** deve modificare la semantica di **append/multi-file**: ogni file viene normalizzato e filtrato **senza** cambiare il confronto header tra file.
- Il filtro deve usare lo **stesso parsing numerico** già previsto per l’analisi Excel; **non** introdurre un secondo parser numerico incompatibile.
- Il filtro **non** deve richiedere nuove API pubbliche se i path pubblici esistenti (`analyzePoiSheet`, `analyzeRows` o equivalenti) permettono già di testare il comportamento.

### Regole di matching conservative

Per evitare falsi positivi, il matching dei token di riepilogo deve seguire regole diverse tra CJK e lingue latine:

- **token CJK** (`合计`, `总数`, `总价`, ecc.): normalizzare spazi / punteggiatura / full-width e poi accettare match su cella/token dove il termine è **semanticamente riconoscibile**;
- **token latini** (`total`, `subtotal`, `totale`, `sum`, ecc.): **evitare** match come sottostringa dentro parole prodotto (`consumable`, `summary bag`, `total look` se con barcode reale); preferire **parola intera**, **prefisso controllato** o **cella-label isolata**;
- i token **non bastano da soli**: serve sempre **profilo aggregato** + **2+ numeri** + **assenza identità prodotto plausibile**;
- i numeri devono essere contati con il **parser numerico di analisi già esistente**, non con `String.toDoubleOrNull()` grezzo se il codice usa formati CL/Excel normalizzati altrove.

### Guardrail anti-regressione funzionale

Questo task nasce da un falso positivo del parser, quindi il criterio di qualità principale è **non rompere ciò che già funziona**. Durante Execution sono **vietati** cambi che alterano questi comportamenti senza nuova evidenza e senza aggiornare il piano:

- identificazione header e `headerSource` già funzionante sui file ordine correnti;
- riconoscimento colonne `barcode`, `itemNumber`, `productName`, `quantity`, `purchasePrice`, `totalPrice`, `retailPrice`;
- gestione file multipli/append con header uguali;
- parsing numerico già allineato ai task precedenti su formati CL/Excel;
- import analysis e GeneratedScreen che consumano `dataRows` già pulite **senza** filtri aggiuntivi.

Se un test esistente fallisce dopo il fix, **non** va indebolito: prima classificare se il fallimento è **vera regressione**, **test obsoleto** o **bug precedente finalmente esposto**. La decisione va annotata nel log **Execution**.

Se durante il **preflight** emerge che il comportamento corrente è già **corretto** sui fixture/file reali, il task **non** deve forzare una patch di codice: trasformare l’Execution in **no-op documentale + test di regressione**, aggiornando il log con evidenza chiara. Una patch **non necessaria** è considerata **regressione potenziale**.

---

## Contesto

### Sintomo

Righe footer del tipo:

- `总数 316.000 总价 365300.000`
- `总数 810.000 总价 1026600.000`
- `总数 1914.000 总价 1901240.000`
- `合计 … quantità/totale … importo …`

La riga è nel foglio Excel ma **non** è un prodotto: contiene token di totale, numeri aggregati e una struttura che può soddisfare le stesse euristiche «data-like» (`nonBlankCount`, `numericCount`, `textCount`) usate per le righe prodotto dopo l’allineamento header.

### Impatto UX osservato

L’errore è percepito dall’utente come una riga “fantasma” in fondo alla preview/griglia: la riga di somma sembra selezionabile/editabile come un prodotto, può confondere la scansione visiva e può arrivare ai passaggi successivi. Il miglioramento UX atteso è quindi **silenzioso ma importante**: la preview deve terminare sull’ultimo prodotto reale, senza chiedere all’utente di cancellare manualmente la riga totale.

UX target: comportamento **zero-friction**. L’utente carica il file e vede direttamente la lista prodotti pulita; non deve scegliere opzioni extra, leggere warning o capire la struttura interna del foglio.

### Convergenza con TASK-043

In **TASK-043** è già stato chiuso un fix parser-side per righe totali/footer. Questo task deve partire dal presupposto che la repo attuale potrebbe già contenere parte degli helper necessari; perciò la prima attività di Execution sarà una **verifica repo-grounded** del codice reale e dei test esistenti. La formulazione corretta è: **TASK-073 non riscrive TASK-043, ma lo rinforza o ne corregge una regressione su nuovi casi reali**.

Se la verifica mostra che `isSummaryRow` / `isSummaryLabel` / `hasPlausibleProductIdentity` / `hasShiftedAggregatePattern` sono già presenti, l’intervento deve limitarsi a:

- correggere la soglia o la posizione del filtro;
- aggiungere token mancanti;
- migliorare la lettura delle colonne identitarie;
- aggiungere test di regressione per i file allegati.

Se invece il codice corrente è diverso dalla documentazione, documentare il drift nel log Execution prima di patchare.

### File / funzioni da ancorare (Execution futura)

Punto d’ingresso unico parser:

- `readAndAnalyzeExcel` → `readAndAnalyzeExcelDetailed` → `analyzeRowsDetailed`
- `analyzeRows` → `analyzeRowsDetailed`
- `analyzePoiSheet` → `analyzeRowsDetailed` (dopo `normalizeTabularRows` dove applicabile)
- Eventuali helper collegati (`RowProfile`, `looksDataLike`, parsing numerico analisi)

Preflight obbligatorio prima di modificare codice:

1. aprire `ExcelUtils.kt` corrente dalla repo Android;
2. cercare `isSummaryRow`, `summaryTokens`, `isSummaryLabel`, `hasPlausibleProductIdentity`, `hasShiftedAggregatePattern`, `analyzeRowsDetailed`, `analyzePoiSheet`;
3. aprire `ExcelUtilsTest.kt` e individuare test già presenti su footer/totali;
4. annotare nel task se il nuovo lavoro è **estensione**, **regressione**, o **drift documentale** rispetto a TASK-043.

Vincolo di posizionamento: il filtro deve stare in un punto condiviso da `readAndAnalyzeExcel`, `analyzeRows` e `analyzePoiSheet`. Non aggiungere filtri separati in `PreGenerateScreen`, `GeneratedScreen`, `DatabaseViewModel` o `ImportAnalysisScreen`, perché sposterebbe la responsabilità fuori dal parser e creerebbe divergenze tra flussi.

Verificare esplicitamente anche il path **HTML/Excel-like** (`parseExcelHtmlToRows` → analisi righe) se il filtro viene applicato dopo la normalizzazione comune. Se il path HTML **non** è coperto dal fix, **documentare perché** e aggiungere almeno un **test sintetico** sul path comune più vicino.

Diagnostica minima da raccogliere durante il preflight:

- numero righe `dataRows` prima/dopo sui fixture sintetici;
- ultima riga prodotto visibile prima/dopo sui file reali disponibili;
- se il footer passa per token mancanti, identità prodotto falsamente positiva o posizione del filtro errata;
- elenco helper esistenti riusati/modificati.

### Esempi file da validare in Execution (evidenza manuale / golden test)

| File | Footer osservato | Ultima riga prodotto attesa (identità) |
|------|------------------|----------------------------------------|
| HO20260119-buenafamilia.xlsx | `合计 … 103 … 1211 … 1252780` | Barcode `7888889601630` / item `960163` |
| Vs20260327-6(MOTARRO MANUALIDAD).xlsx | `总数 1344.000 总价 570780.000` | Item `ZX011-18` |
| Vs20260401-36(MODA LINA).xlsx | `总数 1914.000 总价 1901240.000` | Item `951210` |
| Vs20260430-22(Qiao Xiang2).xlsx | `总数 316.000 总价 365300.000` | Item/barcode `075607` / `6988888075607` |
| Vs20260430-23(Qiao Xiang1).xlsx | `总数 810.000 总价 1026600.000` | Item `529791-红木色` / barcode `6988235529791` |
| 20260330-Xingxing.xlsx | Righe finali non-prodotto (totale/stampa ecc.) | Verificare che **non** finiscano in `dataRows` |

*Nota:* i file `.xlsx` possono non essere in repo; in Execution si preferiscono **test JVM in-memory** (POI) che replicano le righe footer + header/product, più smoke manuale su file reali se disponibili.

Durante Execution, per ogni file reale disponibile localmente, annotare nel log almeno: nome file, ultima riga prodotto riconosciuta prima del fix, ultima riga prodotto riconosciuta dopo il fix, e se il footer è stato escluso. Non serve importare questi file nel repository: l’evidenza può essere manuale/screenshot/log locale, mentre i test committati devono essere sintetici e stabili.

---

## Non incluso (non-goal)

- Riscrittura dell’algoritmo di riconoscimento colonne / header / `headerMap`.
- Filtri specifici per fornitore, nome file, posizione assoluta di riga o workbook singolo.
- Rimozione generalizzata di qualunque riga con testo “total/totale/合计”: serve sempre il profilo aggregato + assenza identità prodotto plausibile.
- Refactor ampio di `ExcelUtils.kt` o estrazione modulo nuovo non necessaria al fix.
- Modifiche a **Room**, **DAO**, **Repository**, modelli dati, **migration**, **navigation**, **NavGraph**.
- Modifiche UI Compose (salvo nota documentale nel log Execution se emergesse effetto collaterale non richiesto — improbabile).
- Nuovi banner, warning o dialog obbligatori nella preview per segnalare il footer rimosso: il comportamento desiderato è automatico e silenzioso. Una micro-UX opzionale è ammessa solo come log/debug non user-facing o come conteggio interno nei test.
- Porting da iOS.
- Modifica o riscrittura del file Excel su disco.
- Rimozione o indebolimento di funzionalità Android esistenti.
- Task **TASK-072** (backend RPC): fuori perimetro.
- Rendere helper **privati pubblici** solo per comodità di test, se i test possono passare dai **path pubblici** già disponibili.

---

## File previsti

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` | **Primario:** rafforzare logica footer/summary **dopo** normalizzazione righe e disponibilità header/colonne canoniche; riusare / estendere **`isSummaryRow`** o helper equivalenti senza duplicazione gratuita; mantenere il filtro parser-side unico per PreGenerate / Generated / ImportAnalysis. |
| `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` | **Primario:** nuovi/aggiornati test JVM per footer 总数+总价, 合计 multi-colonna, regressioni identità prodotto, file senza footer, append multi-file. |
| `docs/TASKS/TASK-043-robustezza-esclusione-righe-totali-footer-preview-import-analysis.md` | **Sola lettura:** riferimento storico per capire cosa era già stato risolto e impedire duplicazioni/drift. **Non** modificare salvo puro cross-reference documentale motivato. |
| `app/src/test/java/.../ImportAnalyzerTest.kt` | **Solo se necessario:** non dovrebbe servire se il footer viene escluso prima di `ImportAnalyzer`; usare come guardrail solo se emerge una regressione nel passaggio `dataRows → map`. |
| `app/src/test/java/.../ExcelViewModelTest.kt` | **Opzionale / condizionale:** solo se Execution tocca flusso load/generate oltre il diretto `analyzePoiSheet` (preferire copertura su `ExcelUtilsTest`). |
| `docs/TASKS/TASK-073-excel-footer-summary-row-filter.md` | Questo file. |
| `docs/MASTER-PLAN.md` | Tracking backlog / task attivo. |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | I file allegati (o equivalenti costruiti in test) **non** mostrano più la riga finale di sommario in anteprima Android PreGenerate | M + S | — |
| 2 | Il conteggio righe prodotto riconosciute corrisponde alle righe reali, **escluso** il footer | M + S | — |
| 3 | **Qiao Xiang2**: ultima riga visibile = prodotto barcode **6988888075607**, non riga **总数** | M | — |
| 4 | **Qiao Xiang1**: ultima riga visibile = prodotto barcode **6988235529791**, non riga **总数** | M | — |
| 5 | **MODA LINA**: ultima riga visibile = item **951210**, non **总数** | M | — |
| 6 | **MOTARRO**: ultima riga visibile = item **ZX011-18**, non **总数** | M | — |
| 7 | **Buenafamilia**: footer **合计** escluso; ultima riga prodotto attesa **7888889601630** / **960163** | M | — |
| 8 | **Xingxing** (o fixture equivalente): righe finali non-prodotto (totale/stampa) non finiscono in `dataRows` | M + S | — |
| 9 | Nessuna regressione su riconoscimento barcode, quantità, purchasePrice, totalPrice, productName (test esistenti + nuovi) | S | — |
| 10 | Nessuna modifica schema DB / repository / file Excel originale | S | — |
| 11 | Filtro **solo** sul risultato analizzato in-memory; nessun salvataggio workbook | S | — |
| 12 | Prodotto reale con nome che contiene token tipo «total» / **合计** / **总数** **non** rimosso se ha identità plausibile (barcode / item / name secondario) | S | — |
| 13 | Workbook **senza** footer: output invariato rispetto a baseline (test di non-regressione) | S | — |
| 14 | Append / multi-file con header coerenti: nessun cambiamento comportamentale indesiderato (test dedicato o scenario documentato) | S | — |
| 15 | Check finali eseguiti e documentati in Execution (vedi sotto) | B + S | — |
| 16 | Il log Execution classifica il lavoro come estensione/regressione/drift rispetto a TASK-043 dopo verifica del codice reale | S | — |
| 17 | Nessun helper duplicato con semantica sovrapposta: se esistono helper TASK-043, vengono estesi o rinominati in modo coerente | S | — |
| 18 | Il filtro non dipende dal nome del file o dal fornitore specifico; usa solo pattern di riga/header/identità prodotto | S | — |
| 19 | I test sintetici coprono sia path `.xlsx` POI sia `analyzeRows`/`analyzePoiSheet` pubblico usato dai ViewModel, dove applicabile | S | — |
| 20 | Se i file reali non sono disponibili in ambiente CI, il limite è documentato e compensato da fixture in-memory + smoke manuale locale | S + M | — |
| 21 | Il filtro preserva ordine e contenuto delle righe prodotto; rimuove solo righe classificate non-prodotto | S | — |
| 22 | Il log Execution indica quale segnale ha causato l’esclusione del footer nei test principali (token, 2+ numeri, assenza identità) | S | — |
| 23 | Non vengono introdotte nuove stringhe UI/localizzazioni perché il fix resta silenzioso e parser-side | S | — |
| 24 | Token latini come `total` / `sum` **non** causano rimozione quando appaiono dentro nome prodotto reale con barcode/item plausibile | S | — |
| 25 | Token CJK e latini sono normalizzati **senza** introdurre parser numerico duplicato o incompatibile | S | — |
| 26 | I test esistenti collegati a **TASK-042** / **TASK-043** restano verdi o, se rinominati, vengono aggiornati **senza** indebolire le asserzioni | S | — |
| 27 | Nessun helper privato viene reso pubblico solo per test, salvo motivazione documentata nel log **Execution** | S | — |
| 28 | Il comportamento **append/multi-file** resta invariato: i file compatibili continuano a unirsi e i file incompatibili continuano a **fallire come prima** | S | — |
| 29 | Il path **HTML/Excel-like** resta coperto da test o da **limite documentato** se non coinvolto dal punto comune del filtro | S | — |
| 30 | Nessun test esistente su header detection, summary/footer o numeri viene **indebolito** per ottenere verde | S | — |
| 31 | In caso di fallimento test esistente, il log **Execution** **classifica** il fallimento **prima** di qualsiasi modifica al test | S | — |
| 32 | Se il bug **non** è più riproducibile sul codice corrente, il task può chiudersi con **no-op documentale** + test di regressione, **senza** patch applicativa | S + M | — |
| 33 | Ogni file reale allegato è mappato a una **fixture sintetica** equivalente o a un **limite manuale** documentato | S + M | — |
| 34 | Il diff finale, se esiste, è limitato a **parser/test/documentazione**; nessun file UI/ViewModel/Repository viene toccato salvo **nuova approvazione esplicita** | S | — |
| 35 | Prima del passaggio a **REVIEW** è presente una **matrice evidenze**: test nuovi, test regressione, file reali/smoke, limiti non verificati | S + M | — |
| 36 | L’Execution distingue nel log cosa è **obbligatorio**, **condizionale** e **opzionale**; eventuali parti non coperte sono motivate come limite/follow-up | S | — |
| 37 | La patch **non** introduce refactor generale del parser: il diff resta **proporzionato** al bug footer reale | S | — |
| 38 | Se HTML/full-width/meta rows **non** vengono coperti, il task resta chiudibile **solo** se i casi reali **`总数`/`总价`** e **`合计`** sono coperti e il limite è **documentato** | S + M | — |

Legenda: **B** = build/lint/static check; **S** = test JVM/statico; **M** = verifica manuale/device o file reale.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | ID **TASK-073** (saltare **TASK-072** file task Android) | **TASK-072** riservato nel `MASTER-PLAN` al follow-up backend RPC | 2026-05-16 |
| 2 | Fix nel parser `ExcelUtils` unico | Allinea PreGenerate, Generated, ImportAnalysis, import DB senza duplicare filtri a valle | 2026-05-16 |
| 3 | Nessun redesign UI: fix automatico e silenzioso | Il problema è un falso positivo del parser; aggiungere UI per correggerlo manualmente peggiorerebbe la UX e sposterebbe responsabilità sull’utente | 2026-05-16 |
| 4 | Test sintetici piccoli invece di workbook binari committati | Stabilità CI, leggibilità diff e minore peso repo; i file reali restano evidenza manuale/local-only | 2026-05-16 |
| 5 | Follow-up di TASK-043, non nuovo algoritmo parallelo | Evita divergenza tra filtri footer e riduce rischio regressione | 2026-05-16 |
| 6 | Preferire filtro in trailing-zone rispetto a filtro globale quando possibile | Riduce il rischio di eliminare prodotti reali con testo ambiguo nel nome o note prodotto | 2026-05-16 |
| 7 | Nessuna nuova localizzazione UI prevista | Il miglioramento UX è automatico: mostrare messaggi all’utente creerebbe rumore inutile | 2026-05-16 |
| 8 | Matching latino con attenzione a parola intera / label isolata | Evita falsi positivi su nomi prodotto che contengono `total`, `sum` o parole simili senza essere righe riepilogo | 2026-05-16 |
| 9 | Testare tramite API parser esistenti quando possibile | Mantiene incapsulamento e riduce modifiche non necessarie a visibilità/helper interni | 2026-05-16 |
| 10 | Non indebolire test esistenti per chiudere il task | La priorità utente è evitare regressioni funzionali; i test falliti vanno classificati, non aggirati | 2026-05-16 |
| 11 | Verificare anche il percorso HTML/Excel-like se passa dal filtro comune | Alcuni file Excel possono arrivare come HTML esportato; il fix deve restare coerente con le compatibilità già chiuse | 2026-05-16 |
| 12 | Accettare un esito **no-op** se il codice corrente è già corretto | Evita modifiche gratuite e riduce il rischio di regressioni funzionali | 2026-05-16 |
| 13 | Richiedere **matrice evidenze** prima di REVIEW | Rende verificabile che il fix copra file reali, fixture e regressioni senza affidarsi a dichiarazioni generiche | 2026-05-16 |
| 14 | **Stop immediato** se servono cambi **fuori parser** | Qualunque necessità su ViewModel/UI/Repository indica scope diverso e va ripianificata prima di eseguire | 2026-05-16 |
| 15 | Definire un **MVP Execution Slice** | Riduce il rischio di overengineering e permette di chiudere il bug reale senza trasformarlo in refactor parser generale | 2026-05-16 |
| 16 | Trattare HTML/full-width/meta rows come **condizionali** se non già coperti dal punto comune | Sono guardrail utili, ma non devono bloccare il fix principale se richiedono cambi più ampi | 2026-05-16 |
| 17 | **Splittare** nuovi problemi in follow-up | Mantiene TASK-073 focalizzato e riduce regressioni funzionali | 2026-05-16 |

---

## Definition of Ready per passare a Execution

Prima di promuovere TASK-073 da `PLANNING` a `EXECUTION`, devono essere veri tutti questi punti:

- [ ] `MASTER-PLAN.md` indica TASK-073 come unico task attivo, oppure viene aggiornato nello stesso passaggio di governance.
- [ ] TASK-072 è confermato come backend/RPC separato e non prerequisito.
- [ ] Il codice corrente di `ExcelUtils.kt` è stato letto, non dedotto dal piano.
- [ ] È chiaro se il lavoro è: **estensione TASK-043**, **regressione TASK-043**, oppure **drift documentale**.
- [ ] Esiste almeno una strategia di test rosso sintetico prima della patch.
- [ ] È definito dove applicare il filtro senza duplicare logica tra POI/HTML/lista righe.
- [ ] Non sono previsti cambi a Room, Repository, ViewModel business logic, Navigation o UI Compose.
- [ ] La soluzione resta parser-side e condivisa da PreGenerate / Generated / ImportAnalysis.
- [ ] È definita la regola di matching per token **CJK** vs token **latini**, evitando sottostringhe pericolose.
- [ ] È deciso quale **path pubblico** useranno i test prima di valutare cambi di visibilità a helper privati.
- [ ] È definita la **lista dei test esistenti di regressione** da eseguire prima/dopo, inclusi **TASK-042**/ **TASK-043** se presenti.
- [ ] È chiaro se il path **HTML/Excel-like** è coperto dal punto di filtro o va documentato come **non** coinvolto.
- [ ] È chiaro quali punti sono **MVP obbligatorio** e quali sono **condizionali**/**opzionale** per evitare scope creep.

Se uno di questi punti non è confermabile, restare in `PLANNING` e aggiornare prima questo file.

---

## Stop conditions / quando NON procedere con la patch

Durante **Execution**, **fermarsi** e tornare a **Planning** se si verifica uno di questi casi:

- il codice corrente **non** corrisponde al piano e il punto comune parser **non** è chiaro;
- per risolvere il bug sembra necessario toccare `ExcelViewModel`, `DatabaseViewModel`, UI Compose, Repository, DAO o navigation;
- il filtro richiede euristiche basate su **nome file**, **fornitore** o workbook specifico;
- i test rossi iniziali **non** riescono a riprodurre il bug e i file reali **non** mostrano più il footer;
- un test esistente **importante** fallisce e **non** è classificabile con sicurezza;
- la patch minima richiede di **indebolire** header detection, numeri o test **TASK-042**/ **TASK-043**.

In questi casi, **non** improvvisare: aggiornare questo task con la diagnosi e chiedere **nuova approvazione** prima di passare a Execution.

### Piano no-op ammesso

Se il preflight dimostra che **TASK-043** o una modifica successiva ha già risolto il problema:

1. aggiungere solo **test di regressione** mirati, se mancano;
2. documentare i risultati sui file reali o fixture equivalenti;
3. **non** modificare `ExcelUtils.kt` se non serve;
4. passare a **REVIEW** come «**no-op applicativo** / test+documentazione».

### Piano rollback concettuale

Se una patch parser produce regressioni:

1. **revertire** la modifica parser, mantenendo i test diagnostici se utili;
2. documentare il pattern che ha causato la regressione;
3. tornare a **PLANNING**/ **FIX** con una strategia più stretta;
4. **non** compensare con filtri UI o ViewModel.

---

## MVP Execution Slice e priorità interna

Per mantenere il task piccolo e sicuro, l’Execution deve distinguere tra **obbligatorio**, **condizionale** e **opzionale**.

### Obbligatorio per chiudere TASK-073

- Riprodurre almeno un caso **`总数 + 总价`** e un caso **`合计`** con test sintetico o file reale.
- Escludere il footer **senza** perdere l’ultima riga prodotto reale.
- Mantenere invariati header, mapping colonne, ordine righe prodotto e append/multi-file.
- Aggiungere test negativi anti-false-positive per prodotto reale con token ambiguo nel nome.
- Eseguire test mirati `ExcelUtils` + regression gate correlato a **TASK-042**/ **TASK-043** se presente.
- Documentare **evidence matrix** e limiti.

### Condizionale

Da fare **solo** se il codice reale passa dallo stesso punto comune o se il test è semplice:

- copertura HTML/Excel-like;
- normalizzazione full-width CJK;
- righe meta/stampa dopo il footer;
- fixture multi-file completa.

Se una voce condizionale richiede refactor o modifica architetturale, **documentarla come limite/follow-up** invece di allargare TASK-073.

### Opzionale / follow-up separato

Da **non** includere nell’Execution salvo evidenza forte e patch piccola:

- redesign della preview o messaggi utente;
- nuovo modulo parser;
- refactor esteso di `ExcelUtils.kt`;
- copertura esaustiva di tutti i possibili footer Excel fornitori;
- supporto perfetto a ogni variante HTML esportata.

### Regola di split

Se durante Execution emergono **più problemi distinti**, chiudere TASK-073 sul bug reale dei footer ordine e creare un **follow-up separato** per il resto. **Non** trasformare TASK-073 in un task «robustezza parser Excel generale».

---

## Planning freeze / soglia di sufficienza

Il piano è considerato **sufficientemente maturo** per passare a `EXECUTION` dopo **approvazione esplicita** dell’utente quando sono presenti:

- scope **parser-side** chiaro;
- **MVP obbligatorio** definito;
- **stop conditions** esplicite;
- **no-op path** ammesso;
- **regression gate** e **review checklist**;
- **evidence matrix** richiesta;
- limiti **anti-overengineering** e **split follow-up**.

Ulteriori cicli di **Planning** sono utili **solo** se emerge una **nuova evidenza concreta**, ad esempio:

- il codice reale di `ExcelUtils.kt` **contraddice** il piano;
- **TASK-043** non contiene gli helper o i test attesi;
- i file reali mostrano un **pattern diverso** da `总数`/`总价` o `合计`;
- esiste un **vincolo tecnico** non considerato su POI/HTML/append;
- la governance `MASTER-PLAN.md` risulta **disallineata**.

In assenza di nuove evidenze, **non** aggiungere altre sezioni o criteri: il rischio diventa **planning overfit**. Il passo corretto è chiedere **approvazione** per `PLANNING → EXECUTION` e applicare il piano in modo **piccolo**, **test-first** e **reversibile**.

### Prompt futuro per autorizzare Execution

Quando l’utente decide di passare a Execution, usare un prompt simile:

```text
Passa TASK-073 da PLANNING a EXECUTION. Prima verifica governance in MASTER-PLAN e leggi il codice corrente di ExcelUtils.kt / ExcelUtilsTest.kt / TASK-043. Applica il piano in modo MVP: test-first sui footer `总数 + 总价` e `合计`, patch parser-side minima, nessun filtro UI/ViewModel, nessun refactor generale. Se il bug è già risolto, fai no-op applicativo con test di regressione e documentazione. Rispetta stop conditions, evidence matrix, regression gate TASK-042/TASK-043 e check finali. Non toccare Room/Repository/Navigation/UI salvo nuova approvazione esplicita.
```

---

## Planning (Claude / mantainer)

### Direzione tecnica (Execution futura)

#### Sequenza operativa consigliata

La sequenza sotto è intenzionalmente test-first: prima riprodurre il falso positivo, poi applicare una patch minima.

0. **Preflight governance:** confermare che `MASTER-PLAN.md` indichi TASK-073 come unico task attivo in `PLANNING` e che TASK-072 resti backend separato/non prerequisito.
1. **Preflight codice:** leggere `ExcelUtils.kt` e `ExcelUtilsTest.kt` correnti, poi classificare il gap rispetto a TASK-043.
2. **Riproduzione minima:** creare prima test rosso sintetico per `总数 + 总价` e `合计` multi-colonna, con ultima riga prodotto reale subito prima del footer.
3. **Patch minima:** estendere il filtro esistente senza cambiare detection header/column scoring.
4. **Anti-regressione:** aggiungere test negativi per prodotto reale con token ambiguo nel nome.
5. **Verifica end-to-end leggera:** testare il path pubblico `analyzePoiSheet` / `readAndAnalyzeExcel` equivalente, non solo helper privati.
6. **Smoke locale file reali:** se i workbook allegati sono disponibili sul computer, aprirli dall’app o tramite test locale non committato e annotare evidenza.
7. **Regression gate:** rieseguire i test esistenti collegati a header/summary/numeri **prima** di dichiarare il task pronto per **REVIEW**; se falliscono, **classificare il motivo** nel log **prima** di toccare i test.
8. **Evidence matrix:** prima di **REVIEW**, compilare nel log una tabella con: fixture/test, file reale coperto, comportamento atteso, esito, eventuale limite residuo.
9. **Scope check finale:** prima di chiudere Execution, verificare che il diff sia ancora coerente con l’**MVP**; se sono apparsi refactor o tocchi a file **fuori** parser/test/documentazione, **fermarsi** e tornare a Planning.

**Nota strutturale:** l’elenco sopra è il **workflow**; i punti numerati sotto sono **requisiti tecnici**. Non interpretarli come autorizzazione a fare un refactor sequenziale ampio.

1. **Posizione del filtro:** applicare il rafforzamento **dopo** normalizzazione/allineamento righe tabellari e quando `header` / mappa colonne canoniche è disponibile, **prima** che `dataRows` venga consumata dai ViewModel — coerente con `analyzeRowsDetailed` e `filterNot(isSummaryRow)` esistente. Preferire una funzione unica del tipo `filterSummaryFooterRows(...)` o estensione dell’equivalente già presente, così il comportamento resta identico tra POI, HTML/Excel-like e test sintetici.

1-bis. **Strategia trailing-zone:** preferire un algoritmo che identifica l’ultima riga con identità prodotto plausibile e considera candidate footer soprattutto le righe successive o finali. Se il codice esistente lavora già con un filtro globale, mantenerlo solo se i test negativi coprono bene prodotti con token ambigui. Non basarsi su un numero fisso di righe finali: alcuni file possono avere una o più righe meta/print dopo il totale.

1-ter. **Compatibilità path comuni:** se `readAndAnalyzeExcel`, `analyzeRows` e `analyzePoiSheet` convergono davvero su `analyzeRowsDetailed`, applicare **lì** il filtro. Se uno dei path **bypassa** quel punto, **non** aggiungere filtri duplicati: prima **documentare il bypass** e valutare se conviene ricondurlo al punto comune con patch minima.

2. **Token e lingue (estensione conservativa di `summaryTokens` / matching):** riconoscere etichette di riepilogo in zh / it / es / en, inclusi varianti indicati nel brief:
   - 合计, 总计, 小计, 汇总, 合計, 總計, 小計, 總結
   - 总数, 总价, 总数量, 总金额, 总件数, 总额
   - total, subtotal, totale, tot., resumen, sum

2-bis. **Matching token sicuro:** per token **latini** usare matching a **parola intera**, **cella-label** o normalizzazione equivalente; evitare `contains("sum")` o `contains("total")` non contestualizzati. Per token **CJK**, dove non esistono spazi di parola affidabili, combinare match normalizzato con **profilo aggregato** e **assenza identità prodotto**.

3. **Pattern “shifted aggregate”:** gestire sequenze tipo **[总数, 316.000, 总价, 365300.000]** e **合计** con numeri su colonne non allineate al «primo testo» — senza assumere layout fisso colonne-fornitore.

   Regola di prudenza: se possibile, trattare questi pattern come footer soprattutto quando compaiono nella zona finale del blocco dati o dopo l’ultima identità prodotto plausibile. Se il codice esistente filtra globalmente tutte le summary row, mantenere il comportamento solo se i test negativi dimostrano che non elimina prodotti reali.

4. **Condizione di filtro (tutte vere, da definire con precisione nel codice):**
   - presenza di **token di riepilogo** riconosciuto (anche tramite normalizzazione stringa, non solo `startsWith` sulla prima cella se insufficiente);
   - almeno **due** valori numerici interpretabili come aggregati (stesso criterio numerico già usato in analisi, es. `parseAnalysisNumber`);
   - **manca identità prodotto plausibile** nelle colonne canoniche disponibili: barcode valido / itemNumber plausibile / productName reale / secondProductName reale;
   - la riga ha profilo da footer: pochi testi descrittivi, nessuna descrizione prodotto sostanziale, oppure label/numero/label/numero spostati rispetto all’header.

   Nota anti-false-positive: una riga con barcode o item valido non va rimossa solo perché il nome contiene “total”, “合计” o “总数”. Il token summary deve essere valutato nel contesto della riga, non come blacklist globale sul testo prodotto.

5. **Righe finali “meta” (se oggi passano il parser):** valutare filtro aggiuntivo per celle uniche con **打印时间**, **pagina**, **page**, **第x页/共x页**, stringhe con molti numeri embedded — **solo** se a basso rischio false positive e coperto da test.

   Questa parte è opzionale: non blocca TASK-073 se i casi reali allegati sono risolti e se aggiungerla aumenterebbe il rischio di false positive.

6. **Helper testabili (preferiti, nomi indicativi):** `normalizeSummaryLabel`, `isSummaryLabel`, `hasPlausibleProductIdentity`, `isSummaryFooterRow`, eventualmente `hasShiftedAggregatePattern` — **non** duplicare `isSummaryRow` se si può estendere internamente.

6-bis. **Output diagnostico interno ai test:** non serve esporre nuove API pubbliche, ma i test devono rendere chiaro perché una riga è stata esclusa. Se utile, usare nomi test descrittivi o helper test-only; evitare log runtime in produzione.

7. **Test JVM obbligatori** (`ExcelUtilsTest` o equivalente):
   - Footer **「总数 316.000 总价 365300.000»** → riga esclusa da `dataRows` / output `analyzePoiSheet` equivalente.
   - Footer **「合计 … (numeri aggregati) …」** → escluso.
   - Ultima riga prodotto reale **presente** immediatamente prima del footer nel fixture.
   - Riga prodotto con nome contenente «total» / token summary ma **barcode+item plausibili** → **non** filtrata.
   - Workbook senza footer → stesso numero righe dati della baseline.
   - Append / multi-file (header uguali): comportamento invariato rispetto a baseline attesa (test o documentazione + test minimo).
   - Footer preceduto da riga prodotto con barcode/item reale: il footer viene escluso e la riga prodotto resta ultima.
   - Footer con token in colonna diversa dalla prima: escluso se mancano identità prodotto e ci sono 2+ numeri aggregati.
   - Riga con una sola occorrenza testuale tipo `total` ma senza pattern numerico aggregato: non rimossa automaticamente.

8. **Performance e stabilità:** il filtro deve essere O(numero righe × numero celle) senza letture extra del workbook, senza caricare di nuovo il file, senza nuove dipendenze e senza regex catastrofiche su celle lunghe. Non introdurre logging verboso su ogni riga in build normale.

#### Matrice fixture sintetiche consigliate

| Fixture | Header minimo | Righe dati | Atteso |
|---------|---------------|------------|--------|
| `qiaoXiangTotalFooter` | itemNumber, productName, quantity, purchasePrice, totalPrice, barcode | 1 prodotto + `总数 316.000 总价 365300.000` | 1 sola data row prodotto |
| `buenafamiliaHejiFooter` | barcode, itemNumber, productName, quantity, purchasePrice, totalPrice | 1 prodotto + `合计` con numeri sparsi | footer escluso |
| `summaryTokenInProductName` | barcode, itemNumber, productName, quantity, purchasePrice | prodotto con nome contenente `total` / `合计` | prodotto conservato |
| `noFooterBaseline` | colonne standard | 2 prodotti reali | output invariato |
| `trailingPrintMeta` | colonne standard | 1 prodotto + eventuale riga `打印时间`/page | esclusa solo se il pattern è implementato e testato |
| `appendCompatibleHeaders` | stesso header su due workbook/row blocks | prodotti reali senza footer | comportamento invariato |
| `multipleTrailingMetaRows` | colonne standard | 1 prodotto + totale + riga stampa/page | prodotto conservato, righe finali non-prodotto escluse se pattern implementato |
| `barcodeLikeNumberInFooter` | barcode, itemNumber, productName, quantity, totalPrice | footer con numero lungo ma senza identità coerente nelle colonne canoniche | escluso solo se non sembra barcode prodotto reale |
| `realProductAfterAmbiguousText` | colonne standard | prodotto reale dopo una riga con testo ambiguo non aggregato | prodotto dopo la riga ambigua resta presente; nessun taglio prematuro |
| `latinTokenInsideProductName` | barcode, itemNumber, productName, quantity, purchasePrice | prodotto reale con nome `Total Look Bag` o `Consumable Set` | prodotto conservato se ha identità plausibile |
| `latinSummaryFooter` | itemNumber, productName, quantity, purchasePrice, totalPrice | 1 prodotto + `Total 12 34500` senza identità prodotto | footer escluso |
| `fullWidthCjkFooter` | itemNumber, productName, quantity, purchasePrice, totalPrice | 1 prodotto + `总数：３１６．０００　总价：３６５３００．０００` | footer escluso se normalizzazione full-width implementata |
| `htmlLikeSummaryFooter` | tabella HTML/Excel-like con header standard | 1 prodotto + footer `Total 12 34500` o `总数 12 总价 34500` | stesso comportamento del path POI se passa dal filtro comune |
| `multiFileOneWithFooter` | due blocchi con stesso header | file A con 1 prodotto + footer, file B con 1 prodotto senza footer | append conserva 2 prodotti reali e rimuove solo il footer |
| `existingHeaderDirtyFixture` | header sporco già coperto da TASK-042 | righe prodotto reali senza footer | output invariato rispetto ai test esistenti |

Le fixture devono essere create in memoria con POI o direttamente come `List<List<String>>` se il test target è `analyzeRows`; evitare file binari in repo.

#### Smoke manuale strutturato sui file reali

Se i workbook allegati sono disponibili localmente, usare questa checklist manuale durante **Execution** o **REVIEW**:

| Step | Azione | Evidenza da annotare |
|------|--------|----------------------|
| 1 | Aprire ogni file dalla schermata di caricamento Excel | nome file + numero righe preview |
| 2 | Verificare l’ultima riga visibile in PreGenerate | deve essere il prodotto atteso, non `总数` / `合计` |
| 3 | Generare la griglia | GeneratedScreen non deve contenere la riga totale |
| 4 | Avviare ImportAnalysis/sync solo se già previsto dallo scenario | nessuna riga prodotto derivata dal footer |
| 5 | Ripetere con almeno un file **senza** footer | output invariato / nessuna riga persa |

Se lo smoke reale **non** è possibile, dichiararlo esplicitamente nel log e indicare quali **fixture sintetiche** coprono ogni file reale.

#### Evidence matrix richiesta nel log Execution/Review

Prima di passare a **REVIEW**, il log deve contenere una matrice simile:

| Evidenza | Copre | Esito atteso | Esito reale | Limite |
|----------|-------|--------------|-------------|--------|
| `qiaoXiangTotalFooter` | Qiao Xiang2/Qiao Xiang1 pattern `总数 + 总价` | footer escluso, prodotto conservato | — | — |
| `buenafamiliaHejiFooter` | Buenafamilia `合计` | footer escluso | — | — |
| `latinTokenInsideProductName` | anti-false-positive latino | prodotto conservato | — | — |
| `multiFileOneWithFooter` | append/multi-file | solo footer rimosso | — | — |
| smoke reale file allegati | UX end-to-end | ultima riga = prodotto reale | — | se non eseguito, motivare |

Questa matrice **non** deve essere perfetta, ma deve rendere evidente cosa è stato verificato e cosa resta solo coperto indirettamente.

---

### Check finali richiesti (Execution — da riportare nel log)

| Check | Comando / azione |
|-------|------------------|
| Test mirati ExcelUtils | `./gradlew testDebugUnitTest --tests '*ExcelUtils*'` |
| Test ExcelViewModel (se toccato) | `./gradlew testDebugUnitTest --tests '*ExcelViewModel*'` |
| Test ImportAnalyzer (solo se toccato/necessario) | `./gradlew testDebugUnitTest --tests '*ImportAnalyzer*'` |
| Test parser pubblici / suite mirata se i nomi test non matchano | `./gradlew testDebugUnitTest --tests '*Excel*'` |
| Test footer specifici, se denominati | `./gradlew testDebugUnitTest --tests '*Summary*'` oppure `./gradlew testDebugUnitTest --tests '*Footer*'` |
| Build | `./gradlew assembleDebug` |
| Lint | `./gradlew lintDebug` |
| Diff whitespace | `git diff --check` |
| Verifica governance | confermare nel log che `MASTER-PLAN.md` e questo file concordano su TASK-073 in `PLANNING`/fase corrente |
| Test regressione TASK-042/TASK-043 correlati, se presenti | eseguire i test esistenti che coprono header sporchi e summary/footer; documentare i **nomi reali** trovati nel preflight |
| Smoke manuale file reali, se disponibili | compilare la checklist «Smoke manuale strutturato» nel log Execution/Review |
| Classificazione fallimenti test | se un test esistente fallisce, classificare **regressione** / **test obsoleto** / **bug preesistente** prima di modificarlo |
| Evidence matrix | compilare la matrice evidenze prima del passaggio a **REVIEW** |

---

### Baseline TASK-004

Se Execution tocca solo `ExcelUtils` + test dedicati, la baseline **TASK-004** può restare **non** obbligatoria oltre i test mirati, ma va comunque documentato perché. Se si modifica `ExcelViewModel`, `DatabaseViewModel`, `ImportAnalyzer`, o il contratto `dataRows`, rieseguire la suite pertinente introdotta da TASK-004/TASK-005 e documentare. Non indebolire test esistenti su TASK-042/TASK-043.

---

### Rischi identificati

| Rischio | Mitigazione |
|---------|-------------|
| False positive: esclusione di una riga prodotto reale | Richiedere congiunzione token summary + 2+ numeri aggregati + **assenza** identità prodotto plausibile; test negativi con nomi «ambigui». |
| False negative: footer con layout nuovo | Estendere token/normalizzazione in modo incrementale; preferire match su sotto-stringhe normalizzate rispetto a hack per file singolo. |
| Regressione colonne esistenti | Vietato toccare logica header/column detection salvo evidenza che il bug sia solo lì (non atteso); diff minimo e review. |
| Dipendenza da file binari non in CI | Riprodurre layout essenziale con POI in-memory nei test. |
| Duplicazione helper da TASK-043 | Preflight obbligatorio su codice corrente; estendere helper esistenti invece di crearne di paralleli. |
| Fix “per nome file” troppo fragile | Vietare condizioni basate su filename/supplier; usare solo segnali strutturali della riga. |
| Mascherare un problema di header detection | Prima di patchare, confermare se il footer passa perché `isSummaryRow` fallisce o perché header/colonne vengono mappati male; documentare nel log. |
| Logging eccessivo su dataset grandi | Nessun log per ogni riga in produzione; eventuale debug locale solo temporaneo e non committato. |
| Correggere solo i fixture sintetici ma non i file reali | Richiedere smoke locale sui workbook allegati quando disponibili e annotare ultima riga prodotto prima/dopo. |
| Ordinamento/append alterato accidentalmente | Testare che il filtro rimuova solo righe non prodotto e non riordini mai `dataRows`. |
| Filtro applicato troppo tardi | Guardrail esplicito: vietato filtrare a valle in UI/ViewModel; il parser deve produrre già `dataRows` pulite. |
| Taglio trailing troppo aggressivo | Non fermarsi alla prima riga ambigua; identificare l’ultima identità prodotto plausibile e testare caso con prodotto dopo testo ambiguo. |
| Numero lungo nel footer scambiato per barcode | Validare barcode/item nel contesto delle colonne canoniche e della presenza di nome prodotto reale, non solo sulla lunghezza numerica. |
| Drift tra documentazione e codice reale | Il preflight deve registrare helper e test già presenti prima della patch; se il piano non corrisponde al codice, aggiornare il task prima di Execution. |
| Token latino troppo permissivo | Usare matching a parola intera/label isolata e test con nomi prodotto reali contenenti `total`/`sum`. |
| Nuove API pubbliche inutili | Preferire test su `analyzePoiSheet`/`analyzeRows`; cambiare visibilità helper solo con motivazione documentata. |
| Parser numerico divergente | Riusare parsing numerico di analisi; aggiungere test con `316.000`, `365300.000` e formati compatibili già supportati. |
| Regressione append/multi-file | Aggiungere fixture `multiFileOneWithFooter` o test equivalente; **non** cambiare logica confronto header. |
| Path HTML dimenticato | Verificare se il filtro comune copre HTML/Excel-like; se **non** lo copre, documentare limite e testare il path più vicino. |
| Test esistenti indeboliti | Vietato rimuovere/ammorbidire asserzioni senza classificazione nel log e motivazione nel file task. |
| Patch non necessaria se bug già risolto | Accettare **no-op applicativo** con test/documentazione; **non** modificare codice per «fare qualcosa». |
| Scope creep verso UI/ViewModel | **Stop condition** esplicita: tornare a Planning e **non** compensare il parser con filtri a valle. |
| Evidenze manuali incomplete | Usare **evidence matrix** e dichiarare chiaramente quali file reali **non** sono stati verificati. |
| Overengineering del parser | Usare **MVP Execution Slice**; spostare HTML complesso, meta rows o refactor ampi in follow-up se **non** sono necessari al bug reale. |
| Task troppo ampio per essere reviewabile | Limitare diff a helper/test mirati; se il diff cresce troppo, **spezzare** in follow-up prima di REVIEW. |
| Coperture opzionali trattate come bloccanti | Distinguere obbligatorio/condizionale/opzionale nel log; **non** bloccare il fix reale per edge case non riprodotti. |

---

## Micro-UX prevista

Non è previsto alcun redesign UI. La UX migliora perché la riga di riepilogo non arriva più alla preview e la griglia termina sull’ultimo prodotto reale.

Eventuali micro-ritocchi ammessi solo se emergono durante Execution e restano locali:

- mantenere invariati loading/error/CTA esistenti;
- non mostrare dialog “riga totale rimossa”;
- non aggiungere azioni manuali per eliminare footer;
- se serve una diagnosi, preferire test/log di sviluppo e documentazione nel task, non UI user-facing.

Cosa cambia per l’utente:

- meno rumore in PreGenerate/Generated;
- meno rischio di sincronizzare/importare una riga non prodotto;
- nessuna nuova azione richiesta;
- la ricerca/scansione in GeneratedScreen non trova più una riga totale non-prodotto come risultato valido.

Cosa non cambia:

- struttura file Excel originale;
- riconoscimento colonne già funzionante;
- flussi di navigazione;
- Room/Repository/import database.

### Review checklist specifica

Durante REVIEW, verificare esplicitamente:

- [ ] diff limitato a parser/test/documentazione;
- [ ] nessuna stringa UI nuova non necessaria;
- [ ] nessun filtro in composable o ViewModel;
- [ ] test negativi anti-false-positive presenti;
- [ ] evidenza file reali o limite documentato;
- [ ] `git diff --check` pulito;
- [ ] token latini **non** usati come sottostringhe pericolose;
- [ ] **nessun** nuovo parser numerico parallelo;
- [ ] **nessuna** API pubblica nuova senza motivazione;
- [ ] append/multi-file **non** regressa;
- [ ] path HTML/Excel-like coperto o limite documentato;
- [ ] eventuali test esistenti modificati hanno motivazione esplicita nel log;
- [ ] se il task è **no-op applicativo**, la scelta è motivata da **evidenze** e non da impossibilità di riprodurre;
- [ ] **evidence matrix** presente e leggibile;
- [ ] **nessuna** stop condition ignorata durante Execution;
- [ ] **MVP obbligatorio** completato o **no-op** motivato;
- [ ] parti **condizionali**/**opzionali** non coperte sono esplicitamente documentate;
- [ ] il diff è abbastanza **piccolo** da essere reviewabile senza introdurre refactor parser generale;

---

## Execution

### Esecuzione — 2026-05-16 21:35 -04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — rafforzata la plausibilità dell’identità prodotto nel filtro summary/footer esistente: item/barcode/nome con numeri formattati da aggregato non bastano più a rendere plausibile una riga con token summary e 2+ numeri.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — aggiunte fixture JVM/POI e HTML per `总数 + 总价`, `合计`, anti-false-positive, workbook senza footer, header compatibili/multi-file.
- `docs/TASKS/TASK-073-excel-footer-summary-row-filter.md` — log Execution completo, evidenze, check, handoff.
- `docs/MASTER-PLAN.md` — governance aggiornata da `PLANNING` a `EXECUTION`, poi a `REVIEW`.

**Azioni eseguite:**
1. Letto `docs/MASTER-PLAN.md`: TASK-073 risulta unico task attivo in `PLANNING`; TASK-072 resta follow-up backend/RPC separato e non prerequisito.
2. Letto questo file task e confermato il perimetro approvato: patch solo parser/test/documentazione, nessun filtro UI/ViewModel/Repository/DAO/Navigation/Supabase.
3. Letti `ExcelUtils.kt`, `ExcelUtilsTest.kt` e `TASK-043` prima di modificare il parser.
4. Verificati helper/path richiesti:
   - `readAndAnalyzeExcel` → `readAndAnalyzeExcelDetailed` → `analyzeRowsDetailed`;
   - fallback HTML: `looksLikeExcelHtml` → `parseExcelHtmlToRows` → `normalizeTabularRows` → `analyzeRowsDetailed`;
   - `analyzeRows` → `analyzeRowsDetailed`;
   - `analyzePoiSheet` / `analyzePoiSheetDetailed` → `normalizeTabularRows` → `analyzeRowsDetailed`;
   - helper TASK-043 presenti: `summaryTokens`, `isSummaryLabel`, `hasPlausibleProductIdentity`, `hasShiftedAggregatePattern`, `isSummaryRow`.
5. Aggiunti test first. Prima patch: `./gradlew testDebugUnitTest --tests '*ExcelUtilsTest*'` ha fallito come atteso su 3 fixture nuove (`qiao xiang`, `multi-file/header`, `HTML`), classificazione: test rossi attesi che riproducono il bug; nessun test esistente importante fallito.
6. Applicata patch minima in `ExcelUtils.kt`, dentro il filtro summary/footer già locale ad `analyzeRowsDetailed`.
7. Eseguiti test mirati, regression gate, build, lint, diff check e smoke locale sui workbook allegati.
8. Rimosso il test temporaneo locale `Task073LocalWorkbookSmokeTest.kt` usato solo per smoke sui file in `Downloads`; i workbook binari non sono stati committati.

**Classificazione preflight TASK-043:**
- **Estensione mirata di TASK-043**. Il punto comune parser e gli helper attesi esistevano già; non c’è drift documentale sostanziale. Il gap residuo era in `hasPlausibleProductIdentity`: un aggregato numerico formattato finito in `itemNumber`/`productName`/`barcode` poteva apparire come identità prodotto plausibile e bloccare `isSummaryRow`.
- Non è un no-op applicativo: i nuovi test hanno riprodotto il falso positivo prima della patch.
- Segnale di esclusione nei test principali: token summary (`总数`/`总价` o `合计`) + almeno 2 numeri aggregati via `parseAnalysisNumber` + assenza di identità prodotto plausibile dopo la patch.

**Dettaglio patch:**
- `hasPlausibleItemIdentity`: un valore interpretabile come numero aggregato e non composto solo da cifre (`316.000`) non è più considerato item plausibile; codici puramente numerici reali (`075607`, `951210`) restano validi.
- `hasPlausibleProductIdentity`: barcode con separatori numerici aggregati (`365300.000`) non viene trattato come barcode plausibile; `productName` e `secondProductName` numerici non vengono trattati come nomi prodotto reali.
- Nessun nuovo parser numerico parallelo: viene riusato `parseAnalysisNumber`.
- Nessun cambio a header detection, mapping colonne, repository, ViewModel, DAO, Navigation, UI Compose o Supabase.

**Test eseguiti:**
- `./gradlew testDebugUnitTest --tests '*ExcelUtilsTest*'` — prima patch: rosso atteso su 3 fixture nuove; dopo patch: ✅ PASS.
- `./gradlew testDebugUnitTest --tests '*ExcelUtils*'` — ✅ PASS.
- `./gradlew testDebugUnitTest --tests '*Excel*'` — ✅ PASS.
- `./gradlew testDebugUnitTest --tests '*Footer*'` — ⚠️ NON ESEGUIBILE come filtro case-sensitive: Gradle non ha trovato test per `*Footer*`; rilanciato con filtro reale minuscolo.
- `./gradlew testDebugUnitTest --tests '*footer*'` — ✅ PASS.
- `./gradlew testDebugUnitTest --tests '*Summary*'` — ✅ PASS.
- `./gradlew testDebugUnitTest --tests '*ExcelViewModelTest*' --tests '*DatabaseViewModelTest*'` — ✅ PASS, gate a valle sul consumo parser esistente; nessun file ViewModel modificato.
- `./gradlew testDebugUnitTest --tests '*Task073LocalWorkbookSmokeTest*'` — ✅ PASS, test temporaneo locale rimosso dopo lo smoke.
- `./gradlew assembleDebug` — ✅ PASS.
- `./gradlew lintDebug` — ✅ PASS.
- `git diff --check` — ✅ PASS.

**Smoke locale file reali allegati:**
| File | Righe preview parser | Ultima riga visibile / parser output | Esito | Limite |
|------|----------------------|--------------------------------------|-------|--------|
| `HO20260119-buenafamilia.xlsx` | 98 | `960163 | 7888889601630 | 膨润土猫砂5KG... | ... | 12500` | ✅ ultima riga prodotto, non `合计` | Smoke via parser `readAndAnalyzeExcel`, non UI device. |
| `20260330-Xingxing.xlsx` | 34 | `SJ-4673 |  | 889-1太阳能灯 | ... | 25080` | ✅ ultima riga prodotto, nessun footer finale | Smoke via parser, non UI device. |
| `Vs20260327-6(MOTARRO MANUALIDAD).xlsx` | 75 | `75 | ZX011-18 | 5680160002585 | 大号 办公剪刀 | ... | 21600` | ✅ ultima riga prodotto, non `总数` | Smoke via parser, non UI device. |
| `Vs20260401-36(MODA LINA).xlsx` | 82 | `82 | 951210 | 6930009512102 | 成人款单色标签针织加绒帽（混） | ... | 15600` | ✅ ultima riga prodotto, non `总数` | Smoke via parser, non UI device. |
| `Vs20260430-22(Qiao Xiang2).xlsx` | 26 | `26 | 075607 | 6988888075607 | 12 | 1150 | 13800 | 空管裤架1PCS 34*35` | ✅ ultima riga prodotto, non `总数`/`总价` | Smoke via parser, non UI device. |
| `Vs20260430-23(Qiao Xiang1).xlsx` | 17 | `17 | 529791-红木色 | 6988235529791 | 30 | 1000 | 30000 | 相框-403-18*24 红木色` | ✅ ultima riga prodotto, non `总数`/`总价` | Smoke via parser, non UI device. |

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `./gradlew assembleDebug` PASS. |
| Lint                     | ✅ | `./gradlew lintDebug` PASS. |
| Warning nuovi            | ✅ | Nessun warning Kotlin/deprecation nuovo nel codice modificato; restano warning Gradle/toolchain preesistenti (`android.builtInKotlin`, `android.newDsl`, legacy variant API). |
| Coerenza con planning    | ✅ | Diff limitato a parser, test parser e documentazione governance/task; nessun filtro a valle. |
| Criteri di accettazione  | ✅ | Verificati singolarmente sotto. |

**Baseline regressione TASK-004 (se applicabile):**
- Applicabile come baseline mirata su flussi Excel/import, non come suite completa repository: sono stati toccati `ExcelUtils.kt` e `ExcelUtilsTest.kt`, non `ExcelViewModel`, `DatabaseViewModel`, `ImportAnalyzer`, Repository o Room.
- Test eseguiti: `ExcelUtilsTest`, filtri `*ExcelUtils*`, `*Excel*`, `*footer*`, `*Summary*`, più gate `ExcelViewModelTest` + `DatabaseViewModelTest` per copertura a valle già esistente.
- Test aggiunti/aggiornati: 6 test in `ExcelUtilsTest` (`qiao xiang`, `合计`, anti-false-positive, no-footer, header compatibili, HTML path).
- Limiti residui: nessun test UI Compose/device; full-width CJK digits non esteso per evitare modifica più ampia del parsing numerico, ma HTML path e file reali allegati sono coperti.

**Evidence matrix:**
| Evidenza | Copre | Esito atteso | Esito reale | Limite |
|----------|-------|--------------|-------------|--------|
| `analyzePoiSheet filters qiao xiang total footer with aggregate in product name column` | `总数 + 总价`, aggregato in colonna identità | footer escluso, prodotto `075607`/`6988888075607` conservato | ✅ PASS | Fixture sintetica POI. |
| `analyzePoiSheet filters heji footer with sparse aggregate numbers` | `合计` Buenafamilia-like | footer escluso, prodotto `7888889601630`/`960163` conservato | ✅ PASS | Fixture sintetica POI. |
| `analyzePoiSheet keeps real products with summary tokens in product names` | anti-false-positive CJK/latino | prodotti con `总数`, `合计`, `total` nel nome conservati se hanno identità plausibile | ✅ PASS | Fixture sintetica POI. |
| `analyzePoiSheet keeps workbook without footer unchanged` | no-footer baseline | output invariato | ✅ PASS | Fixture sintetica POI. |
| `analyzePoiSheet keeps compatible headers stable when one file has footer` | append/multi-file parser-side | header compatibili invariati, solo footer rimosso | ✅ PASS | Verifica parser/header; append ViewModel coperto da test esistente eseguito nel gate ViewModel. |
| `readAndAnalyzeExcel html path filters summary footer through shared parser` | HTML/Excel-like fallback | stesso filtro del path POI | ✅ PASS | HTML sintetico semplice. |
| Smoke reale file allegati | UX parser-equivalent end-to-end | ultima riga = prodotto reale, non footer | ✅ PASS su 6/6 file | Non eseguito su device/emulator; griglia Generated non aperta manualmente. |

**Verifica criteri di accettazione:**
| # | Stato | Evidenza / note |
|---|-------|------------------|
| 1 | ESEGUITO | Fixture POI/HTML + smoke reale parser: footer non in `dataRows`; PreGenerate consuma lo stesso output parser. |
| 2 | ESEGUITO | Conteggi reali: Buenafamilia 98, Xingxing 34, MOTARRO 75, MODA LINA 82, Qiao2 26, Qiao1 17; tutti senza footer finale. |
| 3 | ESEGUITO | Qiao Xiang2 ultima riga contiene `075607` / `6988888075607`, non `总数`. |
| 4 | ESEGUITO | Qiao Xiang1 ultima riga contiene `529791-红木色` / `6988235529791`, non `总数`. |
| 5 | ESEGUITO | MODA LINA ultima riga contiene item `951210`, non `总数`. |
| 6 | ESEGUITO | MOTARRO ultima riga contiene item `ZX011-18`, non `总数`. |
| 7 | ESEGUITO | Buenafamilia footer `合计` escluso; ultima riga prodotto reale `960163` / `7888889601630`. |
| 8 | ESEGUITO | Xingxing smoke: ultima riga `SJ-4673`, nessun footer finale in `dataRows`. |
| 9 | ESEGUITO | `*Excel*`, `ExcelUtilsTest`, `ExcelViewModelTest`, `DatabaseViewModelTest` PASS; header/quantità/prezzi/barcode preservati. |
| 10 | ESEGUITO | Nessun file DB/schema/repository modificato; workbook originali non modificati. |
| 11 | ESEGUITO | Filtro in-memory in `analyzeRowsDetailed`; nessun salvataggio workbook. |
| 12 | ESEGUITO | Test anti-false-positive CJK/latino conserva prodotti con identità plausibile. |
| 13 | ESEGUITO | Test no-footer conserva output atteso invariato. |
| 14 | ESEGUITO | Test header compatibili + gate ViewModel append esistente PASS. |
| 15 | ESEGUITO | Check finali eseguiti: test, build, lint, diff check. |
| 16 | ESEGUITO | Classificazione: estensione mirata TASK-043, non drift/no-op. |
| 17 | ESEGUITO | Helper TASK-043 riusati/rafforzati; nessun helper duplicato. |
| 18 | ESEGUITO | Nessuna euristica su nome file/supplier/workbook. |
| 19 | ESEGUITO | Test via `analyzePoiSheet` e `readAndAnalyzeExcel` pubblico; HTML path coperto. |
| 20 | ESEGUITO | File reali disponibili e verificati localmente; fixture sintetiche aggiunte per CI. |
| 21 | ESEGUITO | Test no-footer/header compatibili verificano ordine/contenuto prodotti; filtro rimuove solo footer. |
| 22 | ESEGUITO | Segnale documentato: token summary + 2+ numeri + assenza identità plausibile. |
| 23 | ESEGUITO | Nessuna stringa UI/localizzazione aggiunta. |
| 24 | ESEGUITO | Test con `total` nel nome prodotto conservato. |
| 25 | ESEGUITO | Matching riusa normalizzazione esistente e `parseAnalysisNumber`; nessun parser numerico duplicato. |
| 26 | ESEGUITO | Test TASK-042/TASK-043 correlati inclusi in `*Excel*`, `*Summary*`, `*footer*`, più ViewModel gate; PASS. |
| 27 | ESEGUITO | Nessun helper privato reso pubblico. |
| 28 | ESEGUITO | Header compatibili invariati; test ViewModel append esistente PASS; nessun cambio confronto header. |
| 29 | ESEGUITO | HTML/Excel-like coperto con test pubblico `readAndAnalyzeExcel`. |
| 30 | ESEGUITO | Nessun test esistente indebolito o rimosso. |
| 31 | ESEGUITO | Fallimento iniziale classificato come rosso atteso dei nuovi test; `*Footer*` no-tests classificato come filtro case-sensitive e rilanciato lowercase. |
| 32 | ESEGUITO | Bug riprodotto; no-op non applicabile. |
| 33 | ESEGUITO | Ogni file reale mappato a smoke locale; Qiao/Buenafamilia/MOTARRO/MODA coperti anche da fixture equivalenti. |
| 34 | ESEGUITO | Diff finale limitato a parser/test/documentazione; `.idea/deploymentTargetSelector.xml` era già dirty e non toccato. |
| 35 | ESEGUITO | Evidence matrix presente. |
| 36 | ESEGUITO | Obbligatorio completato; condizionale HTML coperto; full-width digits/UI device documentati come limite non bloccante. |
| 37 | ESEGUITO | Patch piccola in helper esistente; nessun refactor parser generale. |
| 38 | ESEGUITO | Casi reali `总数`/`总价` e `合计` coperti; HTML coperto; full-width digits/meta rows non ampliati per evitare scope creep. |

**Scope check finale:**
- Diff applicativo limitato a `ExcelUtils.kt` e `ExcelUtilsTest.kt`.
- Diff documentale limitato a `docs/TASKS/TASK-073-excel-footer-summary-row-filter.md` e `docs/MASTER-PLAN.md`.
- Nessun cambio a UI Compose, ViewModel, Repository, DAO, Room, Navigation o Supabase.
- `.idea/deploymentTargetSelector.xml` risulta modificato nel worktree ma non è stato toccato in questa Execution.

**Incertezze:**
- Nessuna stop condition aperta.
- Smoke UI/emulator su PreGenerate/Generated non eseguito; la verifica usa il parser reale `readAndAnalyzeExcel`, che è la fonte dei dati mostrati a valle.
- Full-width CJK digits non coperti da patch per non allargare il parser numerico oltre il bug reale; da considerare follow-up solo se emerge un workbook reale.

**Handoff notes:**
- Task passato a `REVIEW`; non dichiarare `DONE` senza conferma utente/review.
- Review consigliata: controllare soprattutto `hasPlausibleProductIdentity` e i test nuovi su Qiao/HTML/header compatibili.
- Nessun workaround downstream: la correzione resta nel parser comune.

---

## Execution pass 2 — Total quantity summary UI/UX Android + iOS

### Esecuzione — 2026-05-16 23:04 -04

**Obiettivo:**
- Migliorare la leggibilità del riepilogo ordine mostrando anche la quantità totale articoli/pezzi, separata dal numero prodotti/SKU/righe, dal totale ordine e dal totale pagato/pending.
- Classificazione: **UI/UX derived summary enhancement dentro TASK-073**, su richiesta esplicita utente; TASK-073 riaperto da REVIEW a EXECUTION per questo pass 2 e riportato a REVIEW a fine verifica.

**File Android modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/OrderQuantitySummary.kt` — helper derived `calculateTotalQuantityFromRows(data)` su header canonico `quantity`, con parser quantità esistente.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — espone `initialTotalQuantity` per GeneratedScreen e arricchisce la lista History con quantità derivata da `HistoryEntry.data`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntry.kt` — wrapper UI `HistoryDisplayEntry` senza schema/migration, per tenere `HistoryEntryListItem` separato dal derived summary.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — aggiunge riga secondaria `Total quantity` vicino al riepilogo prodotti e prima del totale economico.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — aggiunge quantità totale nel support text compatto e nei dettagli della progress card.
- `app/src/main/res/values*/strings.xml` — localizzazioni `total_quantity_label` (`Quantità totale`, `Total quantity`, `Cantidad total`, `总数量`).
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/OrderQuantitySummaryTest.kt` — test helper quantità intere/decimali/missing/non parsabili.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — test `initialTotalQuantity` e History display con `totalItems` invariato.

**File iOS modificati:**
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/HistoryEntryRuntimeSummary.swift` — derived `totalQuantity(from:)` da colonna canonica `quantity`.
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/PriceFormatting.swift` — formatter quantità CL senza valuta, fino a 3 decimali.
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/HistoryView.swift` — chip History `Total quantity` dopo prodotti/SKU.
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/GeneratedView.swift` — riga summary `Total quantity` dopo total items.
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/*.lproj/Localizable.strings` — localizzazioni `history.summary.total_quantity` e `generated.summary.total_quantity`.
- `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControlTests/OrderQuantitySummaryTests.swift` — test helper e formatter.

**Azioni eseguite:**
1. Letti governance Android, TASK-073, file Android richiesti (`ExcelViewModel`, `HistoryEntry`, `HistoryScreen`, `GeneratedScreen`, `ExcelUtils`, formatter/stringhe/test).
2. Letta governance iOS e file iOS equivalenti (`HistoryView`, `GeneratedView`, `HistoryEntry`, `HistoryEntryRuntimeSummary`, `PriceFormatting`, localizzazioni/test).
3. Implementato calcolo derivato senza Room/SwiftData migration, senza Supabase, senza sync/import/export e senza navigation.
4. Preservato significato di `totalItems`, `missingItems` e progress `0/82`: la quantità totale è un dato separato, derivato da `quantity`.
5. Aggiunti test Android/iOS su interi, decimali, valori mancanti/non parsabili e fallback sicuro.

**Check Android:**
| Check | Stato | Note |
|-------|-------|------|
| `./gradlew testDebugUnitTest --tests '*ExcelViewModel*'` | ✅ | PASS. |
| `./gradlew testDebugUnitTest --tests '*History*'` | ✅ | PASS con `GRADLE_OPTS="-Djdk.attach.allowAttachSelf=true" --no-daemon --max-workers=1`; il primo run broad senza worker seriale era fallito su `AttachNotSupportedException` MockK/ByteBuddy, classificato tooling/env e non regressione. |
| `./gradlew testDebugUnitTest --tests '*OrderQuantitySummary*'` | ✅ | PASS. |
| `./gradlew testDebugUnitTest --tests '*ExcelUtils*'` | ✅ | PASS; copre regressione parser/footer TASK-073 pass 1. |
| `./gradlew testDebugUnitTest` | ✅ | PASS con `JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true" --no-daemon --max-workers=1`; il run senza env era fallito su self-attach MockK/ByteBuddy. |
| `./gradlew assembleDebug` | ✅ | PASS. |
| `./gradlew lintDebug` | ✅ | PASS. |
| `git diff --check` | ✅ | PASS. |

**Check iOS:**
| Check | Stato | Note |
|-------|-------|------|
| `xcodebuild -list -project iOSMerchandiseControl.xcodeproj` | ✅ | PASS. |
| `plutil -lint` localizzazioni IT/EN/ES/ZH | ✅ | PASS. |
| `xcodebuild test ... -only-testing:iOSMerchandiseControlTests/OrderQuantitySummaryTests` | ✅ | PASS finale 5/5 su iPhone 17 Pro iOS 26.5; primo run ha esposto solo un errore nei nuovi test (`Double?` in `XCTAssertEqual`), corretto con unwrap esplicito. |
| `xcodebuild build ... iPhone 17 Pro iOS 26.5` | ✅ | PASS. |
| `git diff --check` | ✅ | PASS. |

**Smoke UI documentato:**
| Target | Schermata | Evidenza | Limite |
|--------|-----------|----------|--------|
| Android | HistoryScreen | La card ora renderizza: prodotti/SKU (`汇总: 82 产品` o localizzato), poi `总数量`/`Quantità totale`, poi valore ordine, missing e pagamento. La riga quantità è `bodyMedium` secondaria (`onSurfaceVariant`) e usa `formatClQuantityDisplayReadOnly`. | Verifica descrittiva/code+build; nessuno screenshot device per non creare dati locali artificiali. |
| Android | GeneratedScreen | Progress `0/82` resta progress righe/prodotti. Il support text compatto aggiunge `Quantità totale: 1.914`; espanso mostra tile separati per pending, quantità totale e totale ordine. Layout stretto sotto 316dp impila i tile. | Verifica descrittiva/code+build; nessuno screenshot emulator. |
| iOS | HistoryView | La griglia chip mostra `Items/Articoli/产品`, poi `Total quantity/Quantità totale/总数量`, poi ordine, pagato, mancanti. | Verifica descrittiva/code+build/test helper; nessuno screenshot simulator con dati reali. |
| iOS | GeneratedView | La sezione Summary mostra `Total items`, `Total quantity`, items to complete, error rows e initial order total come righe distinte `LabeledContent`. | Verifica descrittiva/code+build/test helper; nessuno screenshot simulator con dati reali. |

**Cosa cambia per l’utente:**
- Nelle schermate ordine/history viene distinta la quantità totale articoli/pezzi dal numero prodotti/SKU/righe.
- Esempio atteso MODA_LINA: `82` prodotti resta `82`; quantità totale mostra `1.914`; totale ordine resta `$ 1.901.240`.
- Esempio atteso Qiao Xiang: prodotti/SKU resta il conteggio righe prodotto; quantità totale è derivata dalla colonna `quantity`; il totale economico resta separato.

**Cosa NON cambia funzionalmente:**
- Nessuna modifica schema DB, Room, SwiftData/CoreData o migration.
- Nessuna modifica Supabase, sync/cloud, import/export Excel, parser TASK-073 pass 1 o navigation.
- Nessuna modifica al significato di `totalItems`, `missingItems`, progress `0/82`, pending o payment total.
- Nessun file Excel originale modificato.

**Rischi residui / limiti:**
- Android History ora combina la lista leggera con il flusso completo già esistente per derivare `totalQuantity`; non cambia schema, ma la quantità History dipende dalla disponibilità di `HistoryEntry.data`.
- Se una history vecchia non ha header `quantity` o ha quantità non parsabili, Android nasconde la riga nella card History e Generated mostra fallback `-`; iOS mostra `—` nel summary Generated e omette il chip History quando non derivabile.
- Smoke UI runtime con dati reali non eseguito; copertura affidata a build, test helper e verifica statica dei path UI.
- Warning Gradle/toolchain e deprecation Compose `rememberSwipeToDismissBoxState` preesistenti, non introdotti dal pass 2 e non corretti per evitare scope creep.

**Handoff review pass 2:**
- Verificare che il derived summary usi solo `quantity`, non `totalItems`.
- Verificare che Android/iOS mantengano la distinzione tra prodotti/SKU, quantità totale, totale ordine e pagato/pending.
- Verificare che nessuna modifica abbia toccato DB/schema, Supabase, import/export o navigation.

---

## Execution pass 2b — Compact order summary layout

**Obiettivo UX:**
Ridurre l'altezza della summary card Generated/dettaglio ordine dopo l'aggiunta di `Cantidad total` / `Quantità totale`, mantenendo `0/82` come progress principale e separando chiaramente pagamento, totale ordine, pending e quantità totale.

**Classificazione:**
- UI/UX compact layout refinement dentro TASK-073, pass 2b.
- Nessun nuovo task creato; nessun TASK-074.
- Nessun cambio business logic, parser, Room/schema, DAO, Repository, Supabase, import/export o navigation.

**File Android modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — layout expanded della progress card trasformato in griglia compatta 2×2 quando la larghezza lo consente; fallback a una colonna sotto `292.dp`; tile `Pago total` ancora evidenziata ma più bassa.

**File iOS modificati:**
- `iOSMerchandiseControl/GeneratedView.swift` — `summarySection` convertita da righe `LabeledContent` verticali a summary compatta con progress `checked/total`, `ProgressView`, griglia adattiva di metriche e riga compatta errori.
- `iOSMerchandiseControl/it.lproj/Localizable.strings` — aggiunta `generated.summary.payment_total`.
- `iOSMerchandiseControl/en.lproj/Localizable.strings` — aggiunta `generated.summary.payment_total`.
- `iOSMerchandiseControl/es.lproj/Localizable.strings` — aggiunta `generated.summary.payment_total`.
- `iOSMerchandiseControl/zh-Hans.lproj/Localizable.strings` — aggiunta `generated.summary.payment_total`.

**Log implementazione:**
1. Letti governance e codice reale di `GeneratedScreen.kt` / `GeneratedView.swift`.
2. Android: sostituito lo stack full-width sotto `352.dp` con layout 2×2:
   - riga 1: `Pago total` + `Total pedido inicial`;
   - riga 2: `Pendientes` + `Cantidad total`.
3. Android: mantenuto fallback single-column solo per larghezze molto strette, per evitare overflow.
4. iOS: applicato layout SwiftUI idiomatico con `LazyVGrid(.adaptive(minimum: 140))`, progress separato e metriche distinte.
5. iOS: aggiunta metrica `Pago total` / `Paid total` / `Totale pagato` / `支付总额` nella summary Generated.

**Test/check Android:**
| Check | Stato | Note |
|-------|-------|------|
| `./gradlew testDebugUnitTest --tests '*ExcelUtils*'` | ✅ PASS | No-regression parser TASK-073 |
| `./gradlew assembleDebug` | ✅ PASS | Build Compose OK |
| `./gradlew lintDebug` | ✅ PASS | Warning toolchain/Gradle preesistenti; nessun errore lint |
| `git diff --check` | ✅ PASS | Nessun whitespace error |
| `./gradlew testDebugUnitTest --tests '*ExcelViewModel*'` | N/A | Non toccati helper/calcolo/ViewModel in pass 2b |
| `./gradlew testDebugUnitTest --tests '*History*'` | N/A | History non modificata in pass 2b |

**Test/check iOS:**
| Check | Stato | Note |
|-------|-------|------|
| `plutil -lint .../Localizable.strings` | ✅ PASS | it/en/es/zh-Hans valide |
| `xcodebuild test ... -only-testing:iOSMerchandiseControlTests/OrderQuantitySummaryTests` | ✅ PASS | 5 test passati |
| `xcodebuild build -project iOSMerchandiseControl.xcodeproj -scheme iOSMerchandiseControl -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5'` | ✅ PASS | Build succeeded; warning AppIntents metadata preesistente/non correlato |
| `git diff --check` | ✅ PASS | Nessun whitespace error |

**Smoke UI:**
- Android GeneratedScreen: dallo screenshot fornito, il problema era lo stack verticale full-width; il nuovo codice mostra `0/82` grande in alto e, in expanded, una griglia compatta 2×2 con `Pago total`, `Total pedido inicial`, `Pendientes`, `Cantidad total`.
- Android schermo stretto: fallback documentato a una colonna solo sotto `292.dp`; sopra quella soglia i valori restano separati ma vicini.
- iOS Generated/order detail: summary compatta equivalente con progress `checked/total`, `ProgressView`, griglia adattiva due-colonne/una-colonna e valori separati.
- Nessuna nuova screenshot runtime catturata in questo pass; verifica visuale documentata via screenshot utente + ispezione codice + build UI.

**Cosa cambia per l'utente:**
- La card riepilogo occupa meno altezza e lascia più spazio alla griglia prodotti.
- `Pago total` e `Total pedido inicial` restano separati, evitando valori compositi ambigui tipo `0 / 1.901.240`.
- `Pendientes` e `Cantidad total` restano leggibili e distinti dal numero prodotti/progresso.

**Cosa NON cambia funzionalmente:**
- Nessun cambio al significato di `0/82`, `Pendientes`, `Cantidad total`, `Pago total`, `Total pedido inicial`.
- Nessun cambio al calcolo di `totalQuantity`, `totalItems`, `missingItems`, pagato o totale ordine.
- Nessun cambio parser TASK-073, DB/schema, DAO, Repository, Supabase, import/export o navigation.

**Rischi residui / limiti:**
- Smoke visuale manuale post-fix non catturato come nuova immagine; rimane consigliato un controllo su device/emulatore con viewport simile allo screenshot.
- Label lunghe in lingue diverse usano `maxLines = 1` / riduzione scala minima o ellipsis solo dove necessario; review visuale può tarare microcopy se una lingua risulta troppo compressa.

**Handoff review pass 2b:**
- Verificare visivamente che su viewport tipo screenshot la summary card sia effettivamente 2×2.
- Verificare che i valori MODA_LINA restino distinti: `0/82`, `Pendientes 82`, `Cantidad total 1.914`, `Pago total $ 0`, `Total pedido inicial $ 1.901.240`.
- Verificare che la rifinitura sia solo UI layout e non abbia esteso lo scope oltre TASK-073.

---

## Review

### Review end-to-end — 2026-05-17 00:57 -04

**Classificazione:** APPROVED con fix applicati.

**Cosa ho controllato:**
- Parser Excel: filtro footer resta parser-side in `analyzeRowsDetailed`, condiviso da POI, `analyzeRows` e fallback HTML; nessun filtro duplicato in UI/ViewModel; nessuna dipendenza da filename/supplier/workbook; header, mapping, ordine righe e append restano invariati.
- Android quantity summary: `totalQuantity` deriva solo da header canonico `quantity`; `totalItems`, `missingItems` e progress `0/82` restano numero righe/SKU; nessuna migration Room o modifica Repository/DAO.
- Android compact summary layout: progress principale resta `0/82`; metriche `Pago total`, `Total pedido inicial`, `Pendientes`, `Cantidad total` sono distinte; layout 2x2 con fallback a colonna singola sotto soglia stretta.
- iOS quantity summary/layout: History e Generated mostrano quantità totale separata da prodotti/SKU e importi; layout SwiftUI compatto con `LazyVGrid`, non porting 1:1 Compose.
- Localizzazioni: Android `values/values-en/values-es/values-zh` e iOS `it/en/es/zh-Hans` complete e parseabili.
- Test/verifiche: eseguiti test mirati, full Android JVM, build/lint Android, test/build iOS, lint localizzazioni e diff check.
- Smoke/file reali: parser reale verificato su workbook locali in `~/Downloads`; smoke launch Android/iOS eseguito. Smoke visuale con dati reali su History/Generated non eseguito per non creare/importare dati locali artificiali.
- Documentazione/governance: TASK-073 e MASTER-PLAN riallineati; TASK-072 non toccato.

**Review — fix applicati:**
- Android `ExcelUtils.kt`: rimosso dal diff l'alias header `discount = "折"` perché non documentato, non coperto dai test TASK-073 e fuori perimetro rispetto a footer/summary + total quantity.
- Android `GeneratedScreen.kt`: memoizzati con `derivedStateOf` i riepiloghi usati dalla progress card (`initialOrderTotal`, `initialTotalQuantity`, `currentEffectiveTotal`, `completedCount`) per evitare ricalcoli O(n) a ogni recomposition della UI compatta.
- Android `OrderQuantitySummaryTest.kt`: aggiunto test che verifica che barcode/itemNumber numerici non vengano trattati come quantità quando la colonna `quantity` è mancante/non parsabile.
- iOS `HistoryEntryRuntimeSummary.swift`: allineato il parsing delle quantità formattate CL (`1.914`, `1,234`) al comportamento Android per interi raggruppati, evitando che `1.914` venga letto come `1,914`.
- iOS `OrderQuantitySummaryTests.swift`: aggiunti test per quantità CL raggruppate e separazione barcode/itemNumber vs quantità.

**Test/check eseguiti:**
| Area | Comando / verifica | Esito | Note |
|------|--------------------|-------|------|
| Android parser | `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew testDebugUnitTest --tests '*ExcelUtils*' --no-daemon --max-workers=1` | ✅ PASS | Copre footer `总数`/`总价`, `合计`, HTML fallback, anti-false-positive e regressioni Excel. |
| Android quantity | `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew testDebugUnitTest --tests '*OrderQuantitySummary*' --no-daemon --max-workers=1` | ✅ PASS | Include interi, decimali, invalidi, header mancante, barcode/itemNumber non quantità. |
| Android ViewModel | `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew testDebugUnitTest --tests '*ExcelViewModel*' --no-daemon --max-workers=1` | ✅ PASS | Verifica `initialTotalQuantity` e History display senza alterare `totalItems`. |
| Android History | `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew testDebugUnitTest --tests '*History*' --no-daemon --max-workers=1` | ✅ PASS | Gate History/formatting collegato. |
| Android full JVM | `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew testDebugUnitTest --no-daemon --max-workers=1` | ✅ PASS | Warning Gradle/toolchain preesistenti. |
| Android build | `./gradlew assembleDebug` | ✅ PASS | Build Compose/Room OK. |
| Android lint | `./gradlew lintDebug` | ✅ PASS | Nessun errore lint; warning Gradle/toolchain preesistenti. |
| Android install/launch smoke | `./gradlew installDebug`; launch via SDK `adb` su `emulator-5554` | ✅ PASS | App avviata; History vuota sull'emulatore, quindi niente smoke visuale Generated con dati reali. Screenshot locale: `/tmp/task073-android-launch.png`, `/tmp/task073-android-history.png`. |
| Android file reali | test Robolectric temporaneo locale `Task073ReviewWorkbookSmokeTest` poi rimosso | ✅ PASS | 6/6 workbook: footer assente da `dataRows`; MODA_LINA 82 SKU / quantità 1.914; Qiao Xiang2 26 SKU / quantità 316; Qiao Xiang1 17 SKU / quantità 810; MOTARRO 75 SKU / quantità 1.344; Buenafamilia 98 SKU / quantità 1.211; Xingxing 34 SKU. |
| iOS project list | `xcodebuild -list -project iOSMerchandiseControl.xcodeproj` | ✅ PASS | Scheme `iOSMerchandiseControl`. |
| iOS localizzazioni | `plutil -lint` su `it/en/es/zh-Hans` | ✅ PASS | Tutte OK. |
| iOS test mirato | `xcodebuild test -project iOSMerchandiseControl.xcodeproj -scheme iOSMerchandiseControl -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -only-testing:iOSMerchandiseControlTests/OrderQuantitySummaryTests` | ✅ PASS | 7/7 test. Primo tentativo MCP su altro simulatore fallito per clone CoreSimulator, poi retry diretto PASS. |
| iOS build | `xcodebuild build -project iOSMerchandiseControl.xcodeproj -scheme iOSMerchandiseControl -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5'` | ✅ PASS | Solo warning AppIntents metadata preesistente/non correlato. |
| iOS launch smoke | XcodeBuildMCP `build_run_sim` + `screenshot` su iPhone 17 Pro iOS 26.5 | ✅ PASS | App avviata su Inventario; screenshot locale `/var/folders/nf/85_c2pqj60v6q0r7v8ktzkpw0000gn/T/screenshot_optimized_650c16a3-8a15-4738-9cc1-c4dc3e6a3725.jpg`. |
| Android diff | `git diff --check` | ✅ PASS | `.idea/deploymentTargetSelector.xml` resta dirty preesistente e non toccato. |
| iOS diff | `git diff --check` | ✅ PASS | Pulito. |

**Rischi residui:**
- Smoke visuale con dataset reale su Android Generated/History e iOS Generated/History non catturato: i simulatori/emulatori avviati non avevano dati History pronti e non ho creato import/sessioni artificiali per evitare side effect locali. La copertura funzionale è data da parser reale, test JVM/iOS e build UI.
- Android History carica anche il flusso completo delle entry per derivare `totalQuantity` senza migration/schema; accettato nel perimetro perché non introduce persistenza nuova. Da rivalutare solo se History con dataset molto grandi mostra lentezza reale.
- Full-width CJK digits nei footer restano non estesi oltre quanto già documentato in Execution, per evitare ampliamento del parser numerico senza workbook reale.

**Follow-up fuori perimetro:**
- Nessun follow-up bloccante aperto. Eventuale ottimizzazione persistente/cache di `totalQuantity` in History richiederebbe schema o projection dedicata e va pianificata separatamente se emergerà un problema prestazionale reale.

---

## Fix

N/A — i fix sono stati applicati direttamente nel pass di Review e documentati nella sezione `Review — fix applicati`.

---

## Chiusura

### Chiusura — 2026-05-17 00:57 -04

TASK-073 chiuso in **DONE** dopo review end-to-end positiva con fix applicati e check verdi. La richiesta utente corrente viene trattata come conferma esplicita alla chiusura in caso di review positiva.

**Esito finale:** build stabile, testata e pronta per handoff.

---

## Riepilogo finale

TASK-073 completa:
- Pass 1 parser-side: footer/totali `总数`/`总价`/`合计` esclusi dal parser comune senza filtri a valle.
- Pass 2: quantità totale articoli/pezzi aggiunta in Android e iOS, separata da prodotti/SKU e importi.
- Pass 2b: summary Generated/order detail compatta 2x2 in Android e iOS.
- Review finale: APPROVED con fix piccoli su scope, performance Compose e coerenza parsing quantità iOS.

---

## Handoff

TASK-073 è chiuso in **DONE**. Nessun handoff operativo richiesto.

Note residue non bloccanti:
- `.idea/deploymentTargetSelector.xml` resta dirty/preesistente e non è stato toccato.
- Smoke visuale con dataset reale su Generated/History non è stato catturato; build, test, parser reale sui workbook e smoke launch sono verdi.
