# TASK-023 — Audit e coerenza visualizzazione numerica fissa (Cile / CLP)

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-023 |
| Stato                | **DONE** (review repo-grounded completata il 2026-03-30; fix mirato sul parser quantità applicato; `assembleDebug` / `lint` / `testDebugUnitTest` verdi) |
| Priorità             | ALTA (coerenza prodotto su mercato unico CL) |
| Area                 | UX numerica / formattazione / qualità percepita |
| Creato               | 2026-03-30 |
| Ultimo aggiornamento | 2026-03-30 — review finale completata: risolta l’ambiguità quantità `1.234`, riallineati task file e `MASTER-PLAN`, stato portato a `DONE`. |

---

## Dipendenze

- **TASK-019** (`DONE`) — localizzazione stringhe; questo task è **ortogonale**: la lingua UI resta selezionabile, il **formato numerico mostrato** deve restare **fisso** (convenzione cilena).
- Nessun vincolo duro su TASK-006 / TASK-011 (`BLOCKED`).
- **Coordinamento consigliato con TASK-016** (`BACKLOG`, UX polish History / ImportAnalysis / grid): TASK-023 riguarda **cifre**; TASK-016 **layout/spacing/stati**. In execution, evitare doppio passaggio sulla stessa riga di codice senza ordine (ideale: policy numerica prima o commit atomici per file).

---

## Scopo

Pianificare un **audit completo** dell’app Android sulle superfici user-visible dove servono **valori di misura** formattati (prezzi, quantità, totali, sconti %, contatori UX), **escludendo** identificativi (**barcode**, **itemNumber**, ID tecnici — guardrail), e definire una strategia **centralizzata** con convenzione **cilena fissa** dove applicabile, **indipendente** da it/en/es/zh.

**Planning:** chiuso con review repo-grounded (2026-03-30). **Fase task:** **DONE** — execution, review e fix chiusi il 2026-03-30.

---

## Contesto

- L’app è usata **solo in Cile**: l’utente non deve vedere il formato numerico cambiare al cambio lingua dell’app.
- **Problema attuale (evidenza da codice, campione repo-grounded):**
  - **`HistoryScreen.kt`**: `NumberFormat.getCurrencyInstance(currentLocale)` con `Currency CLP` — il **locale dell’app** influenza grouping/separatori/simbolo, quindi i totali ordine/pagamento **non** sono garantiti “fissi Cile” se `currentLocale` varia. **`totalItems`** e **`missingItems`** sono interpolati come interi grezzi in `Text` (linee ~447–457) — da allineare alla policy contatori (punto migliaia se ≥ 1000).
  - **`GeneratedScreen.kt`**: `formatDecimal` usa `String.format(Locale.getDefault(), "%.${digits}f", num)` — **locale-dependent** per i decimali; inoltre default 2 decimali può produrre `85,00`-style secondo locale.
  - **`ExcelUtils.kt`**: `formatNumberAsRoundedString` / `formatNumberAsRoundedStringForInput` arrotondano a intero ma producono **solo cifre senza separatore migliaia** (`47100` invece di `47.100`).
  - **`TableCell` / `ZoomableExcelGrid`**: mostrano stringhe **raw** della griglia (`List<List<String>>`) — il formato dipende da contenuto Excel / normalizzazioni upstream, non da una policy UI unica.
  - **`parseNumber` in ExcelUtils** — parsing robusto (migliaia `.` e decimale `,` ecc.) già orientato a realtà europee/cilene; **non** va “semplificato” per uniformità visiva se si rompe import/export.
- **Separazione richiesta dal prodotto:** distinguere nettamente **visualizzazione** vs **input/editing** vs **persistenza/parsing file & DB**; non cambiare schema Room, DAO, repository, navigation o contratti Excel senza necessità dimostrata.

---

## Guardrail — identificatori “numerico-looking” (NO policy cilena)

**Non applicare** separatori migliaia, formatter monetari o la policy numerica “Cile” a stringhe che sono **identificativi** o **codici**, anche se composti solo da cifre:

- **barcode**
- **itemNumber** / **codici articolo** (SKU-like)
- **`rowNumber`** e simili quando usati come **ID tecnico** o **riferimento riga**, **non** come **contatore UX** rivolto all’utente (es. “riga 3 di 480” sì contatore; “id riga persistenza” no)

**Motivo:** riformattare (es. `7800123456789` → `7.800.123.456.789`) può **rompere** scansione, match, export, ricerca e aspettative operatore.

In execution: **censire** colonne griglia e campi dialog che mappano su questi tipi; **escluderli** esplicitamente da qualsiasi `formatCl*` / grouping. La policy “cilena fissa” vale per **valori di misura** (prezzo, qty, totale, sconto % come numero), **non** per identificatori.

---

## Problema (UX / coerenza numerica)

- Incertezza per l’utente quando **stesso tipo di dato** (prezzo, totale) appare con separatori diversi tra schermate o dopo cambio lingua.
- Rischio di **doppie convenzioni** (CLP formattato con `NumberFormat` locale vs interi senza migliaia altrove).
- Griglia e dialog possono mostrare **tracce decimali** o stringhe importate non riallineate alla policy “pulita” desiderata.

---

## Obiettivo operativo (fase **EXECUTION**)

1. Inventario esaustivo (file e call-site) di: display, formattazione, parsing UI, arrotondamenti user-visible, confronti solo UI, TextField/dialog.
2. Definizione di **API di formattazione** centralizzate (Kotlin/Android idiomatico: `DecimalFormat` / `NumberFormat` con simboli fissi “stile Cile”, o wrapper testabile) allineate alle **decisioni di planning assunte** (sezione sotto).
   - **Prezzi / importi user-visible** (celle read-only, righe lista, campi prezzo in sola lettura, dialog dove si mostra solo il valore): numero formattato **cileno fisso** (punto migliaia, **zero** decimali), **senza** simbolo monetario.
   - **Riepiloghi / totali espliciti** (es. totali ordine in History): prefisso **`$`** + stesso numero formattato (convenzione cilena fissa).
   - **Quantità (sola lettura):** convenzione cilena **completa**: **punto** migliaia sulla parte intera, **virgola** per decimali; se intero → solo parte intera con migliaia `.` se applicabile; se frazionaria → **max 3** decimali dopo la `,`, **senza** zeri finali.
   - **Quantità (TextField):** come da decisioni dedicate (niente migliaia in digitazione; normalizzazione a blur/conferma); dopo normalizzazione il valore mostrato in sola lettura segue la regola sopra.
   - **TextField numerici**: **nessun** separatore migliaia durante la digitazione; accettare input compatibile con **`,`** e **`.`** dove necessario; **normalizzazione a blur / conferma**, non live formatting aggressivo.
3. Piano di migrazione **a fasi piccole**, senza refactor architetturale largo; ViewModel resta fonte di stato; business logic non spostata nei composable.

---

## Perimetro (incluso)

- Tutte le schermate Compose e componenti che mostrano numeri o testo derivato da `Double`/`Float`/stringhe numeriche.
- ViewModel / util **solo dove** il risultato è **user-visible** (stringhe passate a UI, messaggi, export filename umano se numerico — valutare caso per caso).
- Test JVM esistenti (TASK-004) **se** in execution si tocca logica condivisa — da pianificare nel dettaglio in fase EXECUTION.

---

## Non-obiettivi (cosa NON deve cambiare funzionalmente, salvo evidenza nuova in audit)

- **Schema database Room**, migrazioni, DAO, query, invarianti di persistenza.
- **Import/export Excel** e contenuto celle su file: **fuori scope** e si assume **neutro** (nessun obbligo di allineare il file alla policy UI) **salvo evidenza contraria** emersa in audit (es. stringa export mostrata 1:1 in UI senza passare da formatter dedicato).
- **Navigation** (`NavGraph` e argomenti).
- **Logica di business** di calcolo (totali, sconti, completamento righe) — salvo bug dimostrato; l’obiettivo è **come si mostra**, non ricalcolare il dominio.
- **Parsing robusto** già corretto (es. `parseNumber`, sostituzioni `,`/`.` in pipeline Excel) — **non semplificare** se compromette dati reali.
- **Localizzazione dei testi** (stringhe it/en/es/zh) — fuori scope; resta TASK-019 completato; qui solo **numeri**.

---

## Rischi

| Rischio | Mitigazione (in execution) |
|--------|----------------------------|
| Confondere display con parsing e rompere import | Due layer espliciti: `formatCl*` (UI) vs `parse*` / pipeline Excel; **non** mutare `excelData` per la sola UI; test mirati se si tocca codice condiviso. |
| Applicare formatter CL a **barcode / itemNumber / ID** per errore | Rispettare guardrail **identificatori**; whitelist colonne in griglia; code review su `TableCell`/wrapper. |
| Quantità **frazionarie** nel dominio | Planning: fino a **3** decimali senza zeri finali se non intero; audit deve **confermare** dove esistono frazioni reali vs artefatti `Double`. |
| Griglia: costo CPU / inconsistenze | Direzione preferita: **solo display** su colonne numeriche note (vedi sotto); memoization / derive da `headerTypes` senza riscrivere sorgente dati. |
| Regressione TASK-004 | Eseguire baseline JVM se si modificano repository/ViewModel/ExcelViewModel/import paths. |

### Rischi input / editing (distinti dalla sola visualizzazione)

Oggi nel codice compaiono **`KeyboardType.Number`** e **`KeyboardType.Decimal`** (es. **`EditProductDialog`**: prezzi e stock con **Decimal** + filtro caratteri `isDigit` / `.` / `,` su quantità) e **parsing / confronti** tramite `toDoubleOrNull()` (spesso dopo `replace(",", ".")`) su stringhe legate a TextField o stati `MutableState<String>` / `TextFieldValue`. **`GeneratedScreen`** usa prevalentemente **Number** sui campi prezzo/qty interessati — censire entrambe le varianti in Fase A.

- **Rischio UX:** l’utente può digitare separatori o forme che il formatter display non ammetterebbe in sola lettura; un formatter “bello” in visualizzazione può **confliggere** con ciò che il campo accetta o con confronti numerici leggeri in UI (es. auto-completamento riga quando quantità coincide).
- **Rischio compatibilità:** normalizzazione a **blur/conferma** può cambiare il testo del campo rispetto a quanto digitato; va **censito** ogni punto in cui input, confronto e display condividono la stessa stringa.
- **Obbligo in execution futura:** costruire un **censimento** dei call-site dove **input numerico UI** e **visualizzazione formattata** possono entrare in conflitto (stesso stato usato sia per `Text()` sia per submit, oppure confronto su stringa raw vs numero parsato).

---

## Superfici da auditare (checklist per execution)

- [ ] **GeneratedScreen** — `formatDecimal`, `formatNumberAsRoundedStringForInput`, TextField prezzo/qty/totale, **manual entry dialog** (flusso barcode / prefill), **card “dati dal database”** e testo **database_lookup_price_summary** / prefill prezzo, **calculator / result path** (risultato espressione), **bottom sheet dettaglio riga** (prezzi, qty, vecchi prezzi), **chip** completamento e **counter / ratio** tipo `completed/total` (`InfoChip`), confronti numerici per **auto-completamento / automazioni leggere** (es. uguaglianza quantità), `KeyboardType` + `toDoubleOrNull` sui campi interessati.
- [ ] **DatabaseScreen** + **DatabaseScreenComponents** + **DatabaseScreenDialogs** — liste prodotto, stock, dialog prezzi, price history (timestamp è data/ora, non numerica monetaria). **Nota repo:** in **`DatabaseScreen.kt`** (shell) **non** compaiono chiamate a `formatNumber*` al grep corrente — la formattazione numerica è in **Components** / **Dialogs**.
- [ ] **EditProductDialog** — prezzi, quantità, hint “ultimo/precedente”.
- [ ] **HistoryScreen** — `NumberFormat` currency, **`totalItems`** / **`missingItems`** (interpolazione grezza in `Text`), totali ordine/pagamento, filtri data (date ≠ numeri monetari ma usano locale).
- [ ] **ImportAnalysisScreen** + **ImportAnalysis** (data/util) — confronti old/new, etichette con prezzi/quantità.
- [ ] **PreGenerateScreen** — eventuali conteggi/preview numerici.
- [ ] **FilePickerScreen** — messaggi con dimensioni file o conteggi (se presenti).
- [ ] **ZoomableExcelGrid** + **TableCell** — testo cella grezzo vs colonne tipizzate; **verifica esplicita** che colonne **barcode / itemNumber / rowNumber (ID)** **non** passino dai formatter numerici CL.
- [ ] **ExcelViewModel** — stringhe per UI (prefill, ecc.) **e** hotspot sotto (**summary** + export); vedi sotto.
- [ ] **ExcelUtils** — `formatNumberAsRoundedString*`, `parseNumber`, lettura celle, export stringhe (distinguere export vs UI).
- [ ] **FullDbImportStreaming** / **DatabaseExportWriter** — solo se output è letto da umano in UI o filename; altrimenti etichettare “non UI”.
- [ ] **PriceBackfillWorker** / worker — solo se messaggi o log user-visible.
- [ ] **SnackBar / Toast / stringhe con placeholder numerici** — grep su `stringResource` + parametri numerici; verificare se il numero è già **pre-formattato** lato codice prima del placeholder (`%s`) vs `%d` raw.
- [ ] **Ogni `Text()` o label** che concatena direttamente `.text` di TextField **senza** formatter (possibile incoerenza con policy display quando il campo è sia editabile sia riletto come riepilogo).

---

## File Android candidati (lettura / tocchi in fase **EXECUTION**)

> Elenco da confermare/ampliare durante execution; già emersi da grep iniziale planner.

| File | Motivo |
|------|--------|
| `util/ExcelUtils.kt` | `formatNumberAsRoundedString`, `formatNumberAsRoundedStringForInput`, `parseNumber`, normalizzazione celle |
| `ui/screens/GeneratedScreen.kt` | `formatDecimal` (Locale default), manual entry, griglia associata |
| `ui/screens/HistoryScreen.kt` | `NumberFormat.getCurrencyInstance(currentLocale)` + CLP |
| `ui/screens/ImportAnalysisScreen.kt` | `formatNumberAsRoundedString` su prezzi/qty |
| `ui/screens/DatabaseScreen.kt` | orchestrazione; **nessun** `formatNumber*` nel file (grep 2026-03-30) — per inventario numeri usare Components/Dialogs |
| `ui/screens/DatabaseScreenComponents.kt` | display prezzi/stock |
| `ui/screens/DatabaseScreenDialogs.kt` | price history list |
| `ui/screens/EditProductDialog.kt` | input + display riferimenti prezzo |
| `ui/components/TableCell.kt` | rendering testo cella (API potrebbe accettare formatter per colonna) |
| `ui/components/ZoomableExcelGrid.kt` | wiring celle |
| `viewmodel/ExcelViewModel.kt` | stringhe UI, prefill numerici; **hotspot** in sottosezione dedicata |
| `viewmodel/DatabaseViewModel.kt` | messaggi UI con numeri: **basso** segnale al grep (2026-03-30); verificare solo se emergono stringhe costruite con importi |
| `res/values*/strings.xml` | placeholder `%d` / `%s` con numeri formattati lato codice |
| Test JVM sotto `app/src/test/...` | aggiornamenti se cambiano stringhe attese o helper condivisi |

### Hotspot `ExcelViewModel` (censimento planning — no cambio business/export in questo task)

Nel codice attuale il riepilogo totali non è un unico `calculateSummary`, ma due funzioni private collegate allo stesso obiettivo (totali user-visible):

1. **`calculateInitialSummary(...)`** — calcola `totalItems` e **`orderTotal`** (somma su righe dati da `purchasePrice` × `quantity`).
2. **`calculateFinalSummary(...)`** — calcola **`paymentTotal`** (e prodotti mancanti) sulle righe complete, con logica sconti / `discountedPrice` / `discount`.

**Motivo in planning:** questi percorsi producono **`Double`** che finiscono in stato esposto alla UI (es. riepiloghi History / Generated) come **valori di dominio**; la **formattazione cilena** (`formatClSummaryMoney`, ecc.) va applicata **solo a valle**, in presentation, **senza** alterare i totali calcolati né il loro tipo. In execution: tracciare ogni flusso da queste funzioni fino a `Text()` / snackbar / stringhe.

**`saveExcelFileInternal(...)`** (top-level nello stesso file del ViewModel) e l’insieme **`numericTypes`** (chiavi header trattate come numeriche in export POI, es. `quantity`, `purchasePrice`, `rowNumber`, sconti, ecc.):

- **Motivo in planning:** definisce cosa viene scritto come **cella numerica** Excel vs stringa — è **dato file / persistito su file**, non policy UI. In questo task **non** si cambia logica di export né contratti; si **censisce** il punto per **non mescolare**:
  - formattazione **presentation** (migliaia, `$`, ecc.) destinata allo schermo, con
  - normalizzazione **`toDoubleOrNull` + `setCellValue(double)`** destinata al workbook.

**Attenzione incrocio guardrail:** `numericTypes` include **`rowNumber`**; il guardrail identificatori vieta di **riformattare** `rowNumber` come numero UX in UI quando è **ID tecnico**. In audit va verificato **per contesto** (cella griglia vs export vs contatore) per non applicare formatter CL ai codici/ID per errore.

---

## Ipotesi di strategia tecnica (allineata al planning)

1. **Modulo unico** (es. `ClNumberFormatters` / `NumericDisplayPolicy`) con implementazione basata su **`DecimalFormatSymbols` espliciti** e/o pattern fissi “stile Cile”, così il risultato **non** dipende da `Locale.getDefault()` dell’app per la UI numerica.
2. **Formatter nominati (indicativi):**
   - **`formatClPricePlainDisplay(Double?)`** — prezzo/importo in liste, celle read-only, dialog in sola lettura: **solo numero**, punto migliaia, **0** decimali, null → `-` (o risorsa).
   - **`formatClSummaryMoney(Double?)`** — totali espliciti tipo History: **`$` +** `formatClPricePlainDisplay` (stessa base numerica, senza doppio simbolo).
   - **`formatClQuantityDisplayReadOnly(Double?)`** — **sola lettura**: parte intera con **`.`** migliaia; decimali con **`,`**; max **3** decimali, strip zeri finali; intero puro senza parte decimale.
   - **Quantità in TextField** — helper di input separato (stringa senza migliaia durante edit; vedi matrice).
   - **Valore iniziale TextField** — stringa **senza** migliaia, compatibile con digitazione; normalizzazione centralizzata a **blur/submit**.
3. **Input:** helper `parseUserNumericInputToDouble(String): Double?` (accetta `,` / `.` secondo regole documentate nella matrice) **separato** da `parseNumber` Excel; **non** sostituire `parseNumber` per i file.
4. **Sostituzioni mirate:** eliminare per la UI user-visible `String.format(Locale.getDefault(), …)` e `NumberFormat.getCurrencyInstance(currentLocale)` laddove impostano aspetto numerico; sostituire con i formatter CL sopra (History: totali → `formatClSummaryMoney`).
5. **Griglia (direzione preferita, non neutra):** applicare **formatting display-only** per **colonne numeriche note** (da `headerTypes` / mapping colonne) **nel layer UI** (es. `TableCell` o composable wrapper), derivando il valore numerico dalla stringa cella **solo per la resa**, **senza** mutare `excelData` / stato sorgente della griglia e **senza** preformattare dati persistiti o flusso export. **Motivi:** sicurezza (nessuna corruzione dati editabili), performance (solo colonne target), coerenza **MVVM** (ViewModel mantiene verità funzionale; UI applica presentazione). Se in audit emergono ostacoli (colonne non tipizzabili), documentare eccezione nel file task prima di deviare.

---

## Decisioni di planning già assunte

1. **Prezzi / CLP user-visible** (superfici dense non esplicitamente “totale”): formato **cileno fisso**, **punto** migliaia, **zero** decimali, **senza** simbolo monetario nel testo.
2. **`discountedPrice`:** trattato come **prezzo / importo normale** — stesse regole del punto 1 (plain, no `$` salvo blocco totale esplicito).
3. **`discountPercent`:** display con **massimo 2** decimali, **senza** zeri finali; **nessun** separatore migliaia **salvo** casi eccezionali documentati in audit (valori percentuali molto grandi rari); suffisso `%` coerente con UI esistente.
4. **Riepiloghi / totali espliciti:** prefisso **`$`** + numero con **punto** migliaia e **0** decimali (salvo eccezione documentata).
5. **Quantità in sola lettura:** convenzione cilena **completa** — **`.`** migliaia sulla parte intera, **`,`** per decimali; max **3** cifre decimali, **senza** zeri finali; se valore intero → solo intero formattato con migliaia `.` se applicabile.
6. **Quantità in TextField:** niente migliaia in digitazione; `,` e `.` ammessi dove serve; normalizzazione a **blur/conferma**; niente live formatting aggressivo.
7. **Valori null / blank / non numerici validi:**
   - **read-only / label:** mostrare **`"-"`** (o equivalente già usato dall’app, ma **coerente** e non ambiguo).
   - **TextField:** stringa **vuota** per “nessun valore” in input.
   - **Summary / totali espliciti:** evitare placeholder ambigui (`"--"`, `"... "`, stringa vuota accanto a `$`); preferire **non mostrare la riga**, **oppure** `$ 0` / messaggio risorsa **solo** se il prodotto lo richiede — da allineare per superficie in execution e documentare nella matrice.
8. **Import/export Excel:** **fuori scope**, **neutri**; nessun requisito di allineamento file ↔ UI salvo **evidenza** di testo export mostrato raw in UI.
9. **Date / orari:** regolati dal locale lingua (**TASK-019**); **fuori** da questo task.
10. **Policy “cilena fissa” — esclusioni esplicite:** **non** si applica a **barcode**, **itemNumber**, **codici articolo**, **rowNumber/ID tecnici** (vedi guardrail sopra).

### Residui che dipendono dall’audit (non decisioni prodotto generiche)

- **Elenco concreto** di campi `Double` che sono **realmente** frazionari in produzione (se la lista è vuota, la UI quantità resta di fatto sempre intera).
- **Colonne griglia** effettivamente classificabili come “numeriche” per header; eventuali colonne ibride o testo misto.

---

## Casi ambigui (non monetari / doppio uso)

- **stockQuantity**, **quantity**, **realQuantity** — **`formatClQuantityDisplayReadOnly`** in sola lettura; input TextField per matrice dedicata.
- **discountPercent** / **discountedPrice** — decisioni chiuse nella sezione **Decisioni** (percentuale max 2 dec; prezzo scontato = importo normale).
- **rowNumber** — se **contatore UX** (“riga N”) → formattazione contatore intera con `.` migliaia; se **ID tecnico** → **guardrail**, nessun formatter CL.
- **row counters / totalItems / chip completed/total** — interi con punto migliaia se ≥ 1000 (convenzione contatori).

---

## Layer: display / input / parsing / dominio (chiuso in planning)

| Layer | Ruolo | Esempi repo (non esaustivi) |
|-------|--------|-----------------------------|
| **Display read-only** | Solo resa verso `Text` / label | `formatNumberAsRoundedString`, `currencyFormat.format`, `formatDecimal`, griglia via formatter display-only |
| **Input / editing** | TextField, stato stringa | `EditProductDialog` + `KeyboardType.Decimal`; `GeneratedScreen` + `KeyboardType.Number` |
| **Parsing UI** | Stringa → `Double?` per submit/confronti | `replace(",", ".").toDoubleOrNull()`, `strToD` in Generated |
| **Parsing Excel / file** | Contenuto celle / import | `parseNumber` in **ExcelUtils**, pipeline lettura righe |
| **Dominio / calcoli** | `Double` in ViewModel, DB | `calculateInitialSummary` / `calculateFinalSummary`, `saveExcelFileInternal` + **`numericTypes`** (tipo cella file, non stringa UI) |

**Regola:** non usare formatter CL su dati **dominio** o **file**; solo sul **percorso display** (o stringa dedicata snapshot per UI).

---

## Criteri di accettazione del **PLANNING** (chiusura 2026-03-30)

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Review repo-grounded su file elencati dall’utente; evidenze allineate al codice (helper, History, Generated, griglia, ViewModel) | S | ✅ |
| 2 | `MASTER-PLAN.md` aggiornato con **TASK-023** in fase **EXECUTION** come task corrente; **TASK-006** / **011** / **015** non promossi a lavorazione parallela concorrente | S | ✅ |
| 3 | Elenco repo-grounded di punti critici, file candidati e call-site concreti (inventario esteso) | S | ✅ |
| 4 | Separazione esplicita display / input / parsing + tabella **Layer**; rischi **input/editing** (Number + Decimal) | S | ✅ |
| 5 | Non-obiettivi (incl. Excel fuori scope/neutro) e rischi documentati | S | ✅ |
| 6 | Direzione **griglia** preferita documentata (display-only, no mutazione `excelData`, MVVM) | S | ✅ |
| 7 | Decisioni prodotto principali **assunte** (prezzi, `$` su totali, quantità, TextField, null, discount) | S | ✅ |
| 8 | Fasi A–E operative, incl. censimento conflitti input vs display | S | ✅ |
| 9 | **Matrice decisionale** con colonne obbligatorie e righe ID/discount/null/qty | S | ✅ |
| 10 | **Guardrail** barcode / itemNumber / ID tecnici | S | ✅ |

Legenda: S = Statico. **Planning chiuso** → promozione documentale a **EXECUTION** (2026-03-30).

> I criteri di accettazione **dell’implementazione** (build, lint, matrice completa per call-site, baseline TASK-004 se applicabile) si applicano al **lavoro codice** nelle fasi A–E.

---

## Matrice decisionale (obbligatoria in planning — da completare in execution)

In **execution** la matrice deve essere **riempita riga per riga** (nessun tipo dato lasciato implicito nelle superfici toccate). Colonne richieste:

| tipo dato | esempi | formatter display | formatter input | parsing consentito | superfici UI impattate | note/rischi |
|-----------|--------|-------------------|-----------------|----------------------|------------------------|-------------|
| Prezzo / importo in lista o cella read-only | `47100` → `47.100` | `formatClPricePlainDisplay` (0 dec, `.` migliaia, no simbolo) | N/A o stringa senza migliaia se edit | `parseUser…` / `toDoubleOrNull` post blur | Database, ImportAnalysis, griglia (colonne prezzo), dialog | no `$` salvo totale |
| `discountedPrice` | come prezzo | = riga prezzo | come prezzo | come prezzo | griglia, dialog | decisione: importo normale |
| Totale esplicito (ordine/pagamento) | `125000` → `$ 125.000` | `formatClSummaryMoney` | N/A | — | History | evitare placeholder ambigui se null |
| Quantità **sola lettura** | `1234.5` → `1.234,5` | `formatClQuantityDisplayReadOnly` (`.` migliaia, `,` dec, max 3 dec, no zeri finali) | N/A | — | liste, chip testuali, celle griglia read-only | **non** confondere con barcode |
| Quantità **TextField** | edit `1234,5` | dopo blur può allineare a RO | senza migliaia live; `,`/`.` | norm. blur/conferma | manual entry, EditProduct | conflitto con confronti → censire |
| `discountPercent` | `10,50` → `10,5%` | max **2** decimali, strip zeri; **no** migliaia salvo eccezione audit | come da campo | `toDoubleOrNull` / norm. | Generated, griglia sconto | `%` in UI |
| Contatori UX (chip, totalItems) | `12/480`, `1234` prodotti | interi, `.` migliaia se ≥ 1000 | N/A | — | GeneratedScreen, History | non ID tecnico |
| Barcode / itemNumber / **ID tecnico** | `7800123456789` invariato | **nessuno** (`raw` / identity) | **nessuno** (raw) | solo validazione dominio se esiste | griglia, dialog, scanner display | **guardrail** anti-regressione |
| `rowNumber` **come ID** | `42` invariato | raw | raw | — | persistenza / debug UI | se contatore UX → usa riga contatori |
| Stato **null / blank / invalid** numerico | `null`, `""`, `"abc"` | read-only: `"-"` | TextField: `""` | rifiuta o normalizza | tutte le superfici numeriche | totali: no `"--"` ambigui; vedi decisione #7 |
| Stato invalid in **summary money** | importo mancante | nascondi riga / copy risorsa / `$ 0` se prodotto | — | — | History totali | decidere per superficie in execution |

> **Nota:** prezzi usano **solo** `.` migliaia e 0 decimali; quantità **sola lettura** usa **`.` + `,`** pieno stile lettura cilena; TextField resta gestito senza migliaia durante digitazione — implementazione in **EXECUTION** con API distinte.

_(Le righe sono obbligatorie come scheletro; l’audit le dettaglia per call-site.)_

---

## Fasi di lavoro (EXECUTION — implementazione codice)

1. **Fase A — Inventario:** grep strutturato (`NumberFormat`, `DecimalFormat`, `String.format`, `Locale.getDefault`, `formatNumber`, `KeyboardType`, `toDoubleOrNull`, `TextField` / `TextFieldValue`); classificazione display/input/parsing + **censimento conflitti** + **whitelist colonne** (numeriche vs **barcode/itemNumber/rowNumber ID**); completare la **matrice decisionale**.
2. **Fase B — Design API:** implementare (in un file o modulo minimo) formatter CL + documentazione kdoc; test unitari puri su formatter.
3. **Fase C — Schermate verticali:** History → Database → ImportAnalysis → dialog edit (basso rischio griglia).
4. **Fase D — GeneratedScreen + griglia:** massima densità; attenzione a performance e a non rompere editing.
5. **Fase E — Regressioni:** `./gradlew test` mirato o completo secondo TASK-004; smoke manuale cambio lingua it↔en con **stessi** numeri a schermo.

---

## Planning (Claude) — Analisi sintetica

- **Review finale planning (2026-03-30):** confronto con sorgenti elencate dall’utente — nessun gap bloccante; integrazioni minime (History contatori, `KeyboardType.Decimal`, `DatabaseScreen` vs Components, tabella **Layer**).
- Esiste già un **semi-centro** in `ExcelUtils.kt` per arrotondamento intero ma **senza** separatore migliaia e **senza** distinzione prezzo vs quantità vs totale con `$`.
- La **maggior incoerenza** rispetto al requisito “sempre Cile” è l’uso di **`Locale` UI / default** per currency (`HistoryScreen`) e `String.format` (`GeneratedScreen.formatDecimal`).
- **Griglia:** la direzione preferita è **solo presentation layer** su colonne numeriche note, **senza** mutare `excelData` / export; allineato a **MVVM** e a basso rischio regressione dati.
- **Input:** il rischio è **trasversale** (Keyboard + `toDoubleOrNull`); va trattato come workstream separato dalla sola sostituzione di `Text()` formattati.
- **Identificatori:** il guardrail **barcode / itemNumber / ID** è **obbligatorio** per evitare regressioni funzionali.

### Piano di esecuzione

Vedi sezione **Fasi di lavoro (EXECUTION — implementazione codice)**.

### Rischi identificati

Vedi tabella **Rischi**.

---

## Review planning repo-grounded — 2026-03-30

**Esito:** planning **sufficientemente maturo** per iniziare implementazione senza altro round di planning; integrazioni minime applicate nello stesso passaggio (vedi `Ultimo aggiornamento`).

| Checklist | Esito |
|-----------|--------|
| `formatNumberAsRoundedString` / `ForInput`, `parseNumber` (**ExcelUtils**) | Coerenti con quanto documentato; `parseNumber` usato anche in analisi colonne (oltre UI) |
| `formatDecimal` (**GeneratedScreen**) | Confermato `String.format(Locale.getDefault(), …)` L2766–L2767 |
| `NumberFormat` currency (**HistoryScreen**) | Confermato `currentLocale` + `currencyFormat.format(orderTotal/paymentTotal)` |
| Griglia **TableCell** / **ZoomableExcelGrid** | Testo `String` raw da `data` |
| `KeyboardType` + `toDoubleOrNull` | **Number** (Generated) + **Decimal** (EditProduct); censire entrambi |
| Hotspot **ExcelViewModel** | `calculateInitialSummary`, `calculateFinalSummary`, `saveExcelFileInternal`, `numericTypes` — coerenti col file task |
| Guardrail barcode / itemNumber / rowNumber | Sufficienti; incrocio `numericTypes` ↔ `rowNumber` già notato |
| Distinzione layer | Resa esplicita in tabella **Layer** |
| Null / discount / qty / `$` / contatori / griglia | Coperti in **Decisioni** e **matrice** |
| **DatabaseScreen.kt** | Nessun formatter diretto — puntare a Components/Dialogs |
| Coerenza MASTER-PLAN ↔ task | Allineata con promozione **EXECUTION** |

---

## Execution

### Esecuzione — 2026-03-30 (solo governance / review planning)

**Promozione documentale:** stato task **PLANNING → EXECUTION**. **Nessuna** modifica a sorgenti Kotlin/Android in questo passo.

**File modificati (documentazione):** `docs/TASKS/TASK-023-audit-visualizzazione-numerica-cl-fissa.md`, `docs/MASTER-PLAN.md`.

**Azioni:** review planning repo-grounded; integrazioni testuali; allineamento governance.

**Check obbligatori AGENTS (build/lint):** **N/A** — nessun intervento su codice applicativo.

**Prossimo passo esecutore:** avviare **Fase A** (inventario grep + matrice per call-site) sui file candidati.

### Esecuzione — 2026-03-30 (implementazione codice)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt` — nuova API centralizzata per display CL, input price/qty, parser UI e whitelist griglia.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt` — test puri su prezzi, quantità, percentuali, normalizzazione input e guardrail identità.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — totali espliciti con `\$` + formato CL fisso; contatori `totalItems`/`missingItems` con punto migliaia.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — prezzi read-only con migliaia `.` e quantità con formatter qty dedicato.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — storico prezzi con formatter prezzo read-only.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — prezzi/quantità coerenti, contatori sezione formattati lato UI, compare rows aggiornate.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt` — input prezzo/quantità separati da display, parser dedicati, normalizzazione a blur/conferma.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — rimosso formatter locale-dependent; card/dialog/counter/calculator/manual entry allineati alla policy CL.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt` — policy display-only su colonne numeriche note senza mutare `excelData`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/PreGenerateScreen.kt` — passaggio dei raw header key alla griglia per applicare la whitelist numerica anche in preview.
- `app/src/main/res/values/strings.xml` — placeholder count `%s` per contatori numerici formattati.
- `app/src/main/res/values-en/strings.xml` — placeholder count `%s` per contatori numerici formattati.
- `app/src/main/res/values-es/strings.xml` — placeholder count `%s` per contatori numerici formattati.
- `app/src/main/res/values-zh/strings.xml` — placeholder count `%s` per contatori numerici formattati.

**Azioni eseguite:**
1. Fase A completata con inventario reale dei call-site: History totals/counters, Database list + price history, ImportAnalysis compare/list counts, EditProductDialog, GeneratedScreen (detail sheet, calculator, manual entry, chips), grid `ZoomableExcelGrid`.
2. Fase A completata con whitelist numerica esplicita nel nuovo layer centralizzato:
   - prezzi/importi read-only: `purchasePrice`, `retailPrice`, `totalPrice`, `discountedPrice`, `oldPurchasePrice`, `oldRetailPrice`
   - quantità read-only: `quantity`, `realQuantity`, `stockQuantity`
   - percentuali: `discount`
   - esclusi guardrail: `barcode`, `itemNumber`, `rowNumber`
3. Fase B completata con API centrale semplice e testabile (`formatClPricePlainDisplay`, `formatClSummaryMoney`, `formatClQuantityDisplayReadOnly`, `formatClPriceInput`, `formatClQuantityInput`, `parseUserPriceInput`, `parseUserNumericInput`, `formatGridNumericDisplay`).
4. Step verticali applicati in ordine:
   - `HistoryScreen`
   - `Database` / dialog
   - `ImportAnalysis`
   - `EditProductDialog`
   - `GeneratedScreen`
   - griglia display-only
5. Input numerici separati da display:
   - prezzi TextField: nessun migliaia live; parsing price-aware; normalizzazione a blur/conferma verso stringa intera senza migliaia
   - quantità TextField: nessun migliaia live; `,` e `.` ammessi; normalizzazione a blur/conferma verso max 3 decimali senza zeri finali
6. UI/UX locale, intenzionale e tracciata:
   - contatori sezione `ImportAnalysis` e chip `completed/total` ora usano la stessa convenzione numerica dell’app (motivo: chiarezza/coerenza visiva)
   - card riepilogo DB in `ManualEntryDialog` separa display read-only (`47.100`) da valore copiato nel campo input (`47100`) (motivo: evitare conflitto tra resa e editing)

**Matrice decisionale execution (call-site reali):**
| tipo dato | call-site reali toccati | formatter display | formatter input | parsing consentito | note |
|-----------|-------------------------|-------------------|-----------------|--------------------|------|
| Prezzo / importo read-only | History totali, Database cards, price history, ImportAnalysis rows, Generated detail/grid | `formatClPricePlainDisplay` | `formatClPriceInput` | `parseUserPriceInput` | no simbolo monetario nelle superfici dense |
| Totale esplicito | `HistoryScreen` (`orderTotal`, `paymentTotal`) | `formatClSummaryMoney` | N/A | dominio invariato | `\$` solo qui |
| Quantità read-only | Database stock, ImportAnalysis stock, Generated detail, grid | `formatClQuantityDisplayReadOnly` | `formatClQuantityInput` | `parseUserNumericInput` | max 3 decimali, no zeri finali |
| Quantità TextField | EditProductDialog, Generated detail/manual entry | resa read-only a valle | `formatClQuantityInput` / `normalizeClQuantityInput` | `parseUserNumericInput` | niente migliaia live |
| Contatori UX | History summary counts, ImportAnalysis section counts, Generated chips | `formatClCount` | N/A | N/A | punto migliaia se >= 1000 |
| Percentuale sconto | griglia colonne `discount` | `formatClPercentDisplay` | N/A in questo task | `parseUserNumericInput` | max 2 decimali |
| Identificatori | barcode, itemNumber, rowNumber | raw / identity | raw / identity | parsing dominio invariato | esclusi da formatter CL |

**Check obbligatori:**
| Check                    | Stato | Note                        |
|--------------------------|-------|-----------------------------|
| Build Gradle             | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ | `./gradlew lint` eseguito con stesso `JAVA_HOME` → `0 errors`, report con `63 warnings` baseline progetto (`UnusedResources` e warning infrastrutturali già presenti) |
| Warning nuovi            | ✅ | nessun warning nuovo attribuibile alla policy numerica introdotta; persistono warning preesistenti di progetto / Gradle / Robolectric |
| Coerenza con planning    | ✅ | formatter CL centralizzati, separazione display/input/parsing rispettata, griglia display-only con whitelist, guardrail barcode/itemNumber/ID preservati |
| Criteri di accettazione  | ✅ | execution aderente agli obiettivi del task: prezzi/totali/qty/input/guardrail coperti nei call-site censiti |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest` con suite verdi, incluse `ExcelUtilsTest` (28), `ExcelViewModelTest` (23), `DatabaseViewModelTest` (18), `DefaultInventoryRepositoryTest` (13), `ImportAnalyzerTest` (20), `ClNumberFormattersTest` (10).
- Test aggiunti/aggiornati: aggiunto `ClNumberFormattersTest` per formatter CL, normalizzazione input e guardrail identità.
- Limiti residui: nessun test UI Compose/manuale automatico; resta raccomandato smoke manuale rapido cambio lingua e paste di valori numerici reali in dialog/campi.

**Incertezze:**
- INCERTEZZA: per gli input quantità, una stringa con singolo `.` viene interpretata come decimale (`1.234` -> `1,234` dopo normalizzazione), scelta coerente con input-editing e con il parsing esistente; il caso “copia/incolla di una quantità intera già raggruppata con `.`” resta da osservare in smoke manuale.
- `lint` conferma una baseline di warning storici di progetto (`UnusedResources`, AGP/library advisory) che questo task non ha ripulito perché fuori scope.

**Handoff notes:**
- Execution completata; task pronto per review planner.
- Verificare in review i casi limite UX: copia/incolla `47.100` nei campi prezzo, copia/incolla `1.234` nei campi quantità, cambio lingua it/en con numeri invariati.
- Controllare in review che `barcode`, `itemNumber` e `rowNumber` restino raw in tutte le superfici dove fungono da identificatori.
- `MASTER-PLAN.md` non aggiornato in questo passo: `AGENTS.md` riserva lo stato globale al planner.

---

## Review

### Review finale — 2026-03-30

**Esito:** review repo-grounded **positiva con fix mirato**. L’implementazione di TASK-023 era sostanzialmente corretta e coerente col planning, ma restava un problema reale sulla quantità editabile: un input/paste come `1.234` veniva normalizzato a `1,234`, entrando in conflitto con il formato read-only cileno già mostrato dall’app per gli interi con migliaia.

**Problema confermato in review:**
- `ClNumberFormatters.kt` — il parser quantity riusava il percorso generico e interpretava `1.234` come decimale, non come intero raggruppato; il rischio residuo dichiarato in execution non era quindi accettabile come chiusura `DONE`.

**Verifiche review:**
- Centralizzazione `ClNumberFormatters.kt` confermata: API semplice, testabile e senza dipendenza impropria da `Locale.getDefault()` per la UI numerica del task.
- Wiring confermato su `HistoryScreen`, `DatabaseScreenComponents`, `DatabaseScreenDialogs`, `ImportAnalysisScreen`, `EditProductDialog`, `GeneratedScreen`, `PreGenerateScreen`, `ZoomableExcelGrid`.
- Guardrail confermati: `barcode`, `itemNumber`, `rowNumber` di griglia restano fuori dalla policy numerica CL.
- Nessuna mutazione di `excelData` introdotta per comodità UI: la griglia continua a formattare solo in display.

---

## Fix

### Fix — 2026-03-30

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt` — introdotto parser quantity-aware per riconoscere interi raggruppati copiati dal display CL senza alterare il parser numerico generico.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — tutti i path quantità passano dal parser quantity-aware (supporting text, confronti, save/edit, build prodotto).
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt` — salvataggio stock quantity riallineato al parser quantity-aware.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt` — aggiunti test su parser quantità, parser prezzi e ambiguità `1.234`.
- `docs/TASKS/TASK-023-audit-visualizzazione-numerica-cl-fissa.md` — aggiornato log review/fix/chiusura.
- `docs/MASTER-PLAN.md` — allineato lo stato globale a task chiuso.

**Azioni eseguite:**
1. Corretto il parser centrale in modo minimo: pattern esplicito per interi raggruppati (`1.234`, `47.100`) e nuovo helper dedicato alle quantità.
2. Separato definitivamente i percorsi:
   - `parseUserNumericInput` resta generico/permissivo
   - `parseUserPriceInput` resta price-aware
   - `parseUserQuantityInput` diventa quantity-aware per input/paste coerenti con il display CL
3. Aggiornati i call-site quantità reali nei dialog e in `GeneratedScreen`.
4. Estesa la suite test per coprire il caso che aveva motivato il rischio residuo.

**Check post-fix:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug lint testDebugUnitTest` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ | `0 errors, 63 warnings` nel report `app/build/reports/lint-results-debug.txt`; warning baseline invariata |
| Warning nuovi            | ✅ | nessun warning nuovo attribuibile al fix review |
| Coerenza con planning    | ✅ | fix dentro il perimetro del task, senza cambiare dominio/export/navigation |
| Criteri di accettazione  | ✅ | review chiusa con problema reale corretto e verificato |

---

## Chiusura

### Chiusura — 2026-03-30

**Stato finale:** `DONE`

**Cosa cambia per l’utente:**
- I numeri dell’app seguono una policy CL coerente e stabile in tutte le superfici toccate dal task.
- Prezzi e importi read-only usano migliaia `.` senza simbolo nelle superfici dense.
- I totali espliciti usano `\$` + numero formattato.
- Le quantità read-only usano migliaia `.` e decimali `,` fino a 3 cifre senza zeri finali.
- Nei campi quantità, un valore copiato dal display come `1.234` torna ora coerentemente a `1234` in input, invece di diventare `1,234`.

**Cosa NON cambia funzionalmente:**
- Nessuna modifica a schema Room, DAO, repository, navigation o contratti Excel.
- Nessuna mutazione di `excelData` per scopi di sola UI.
- Nessun uso dei formatter display sui dati dominio o sui dati file/export.

**Rischi residui:**
- Nessun rischio bloccante aperto emerso dalla review.
- Restano solo warning lint storici di progetto fuori scope (`UnusedResources`, advisory AGP/Robolectric già preesistenti).

**Check finali eseguiti:**
- `assembleDebug` ✅
- `lint` ✅
- `testDebugUnitTest` ✅
- `ClNumberFormattersTest` aggiornato: 11 test, `0 failures`, `0 errors`
- Suite JVM complessiva: 9 suite XML, 126 test totali, `0 failures`, `0 errors`

---

## Riepilogo finale

_(Da compilare solo a chiusura task dopo EXECUTION/REVIEW.)_

---

## Handoff

- Nessun handoff operativo aperto: review completata, fix applicati, tracking globale allineato.
- La policy CL resta centralizzata in `ClNumberFormatters.kt`; futuri cambi numerici dovranno preservare la separazione display / input / parsing / dominio introdotta qui.
- Follow-up cleanup 2026-03-30: rimossi i formatter legacy `formatNumberAsRoundedString*` da `ExcelUtils.kt`; `ExcelViewModel` usa ora `formatClPriceInput`. `parseNumber` è stato mantenuto perché ancora usato dal parsing Excel/import (`ExcelUtils` / `ImportAnalysis`) e non va confuso con i formatter UI.
