# TASK-043 — Robustezza esclusione righe totali/footer da preview e import analysis

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-043 |
| Stato                | `DONE` |
| Priorità             | `ALTA` |
| Area                 | Import / Excel / parsing / anteprima / Import Analysis |
| Creato               | 2026-04-10 |
| Ultimo aggiornamento | 2026-04-10 (REVIEW repo-grounded completata: APPROVED senza fix; `ExcelUtilsTest` 40/40, `ExcelViewModelTest` 45/45, `DatabaseViewModelTest` 23/23; task chiuso `DONE`) |

---

## Dipendenze

- **TASK-041** — task attivo globale in fase `PLANNING` / `EXECUTION` a seconda del `MASTER-PLAN`; **questo task non va attivato come `ACTIVE` finché il workflow utente non chiude o sospende il task precedente** (regola un solo task attivo).
- **TASK-005** (`DONE`) — baseline test `ExcelUtils` / `analyzePoiSheet` / summary rows.
- **TASK-025** (`DONE`) — trimming strutturale `normalizeTabularRows` (non risolve righe footer piene).
- **TASK-042** (`DONE`) — header multi-riga / colonne; stesso file `ExcelUtils.kt`, attenzione a conflitti di merge e regressioni su layout Shopping Hogar.

---

## Scopo

Eliminare in modo **generalizzabile** (non hardcoded su singolo file HyperAsian) le **righe di riepilogo / footer / totali** che oggi entrano nella lista `dataRows` prodotta da `readAndAnalyzeExcel` → `analyzeRows` / `analyzeRowsDetailed`, così che **non** compaiano in anteprima PreGenerate, griglia Generated né nell’analisi import DB, senza introdurre regressioni sui workbook già supportati.

Il fix deve restare **concentrato nel parser**, mantenere invariati i flussi utente già funzionanti e preservare la compatibilità con i casi consolidati dei task precedenti su `ExcelUtils.kt`.

---

## Contesto

### Sintomo osservato (HyperAsian e analoghi)

- Ultima riga del foglio: etichette tipo **总数** / **总价** (o equivalenti) e **numeri aggregati** (quantità totale, importo totale).
- La riga **non** è un prodotto ma passa tutta la pipeline come riga dati.

### Perché accade (analisi repo-grounded)

1. **Ingresso unico**  
   `readAndAnalyzeExcel` delega a `analyzeRows` → `analyzeRowsDetailed` (`ExcelUtils.kt`).

2. **Classificazione “data-like” duplicata e permissiva**  
   `RowProfile.looksDataLike` e, con header rilevato, il filtro su `dataRows` usano la **stessa** condizione: almeno 4 celle non vuote, almeno 2 valori numerici (secondo `parseAnalysisNumber`), almeno 1 testo non numerico:

```229:238:app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt
private data class RowProfile(
    val index: Int,
    val nonBlankColumns: Set<Int>,
    val nonBlankCount: Int,
    val numericCount: Int,
    val textCount: Int,
    val aliasHits: Int
) {
    val looksDataLike: Boolean
        get() = nonBlankCount >= 4 && numericCount >= 2 && textCount >= 1
}
```

```896:905:app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt
    if (hasHeader) {
        header = mergeHeaderRows(rows, headerDetection.headerRows).toMutableList()
        headerSource = MutableList(header.size) { "unknown" }
        val start = headerDetection.dataRowIdx.coerceAtLeast(0)
        dataRows = rows.drop(start).filter { row ->
            val numericCount = row.count { parseAnalysisNumber(it) != null }
            val textCount = row.count { it.isNotBlank() && parseAnalysisNumber(it) == null }
            val nonBlankCount = row.count { it.isNotBlank() }
            nonBlankCount >= 4 && numericCount >= 2 && textCount >= 1
        }
```

   Una riga footer con più colonne numeriche + etichette soddisfa questa euristica esattamente come una riga prodotto.

3. **Filtro summary esistente ma incompleto rispetto ai token reali**  
   In coda a `analyzeRowsDetailed` esiste già `isSummaryRow` + `dataRows.filterNot` (`ExcelUtils.kt` ~1334–1352). Condizioni **cumulative**:
   - `looksLikeToken`: prima cella **testuale** non numerica nella riga (ordine colonne) **oppure** `productName` normalizzato deve iniziare con uno di `summaryTokens`;
   - `manyNumbers`: almeno due numeri nella riga;
   - `lacksIdentity`: barcode e itemNumber vuoti **e** `productName` con lunghezza &lt; 3.

   La lista `summaryTokens` include ad es. `合计`, `总计`, `总额`, `total`, `totale`, `subtotal`, … ma **non** include etichette molto comuni per totali fornitore cinese come **总数** (totale quantità) e **总价** (totale prezzo). Se l’etichetta visibile non fa match con `startsWith` sui token noti, `looksLikeToken` resta falso e la riga **non** viene rimossa anche se è chiaramente un footer.

4. **Effetto a valle (nessun workaround UI richiesto)**  
   - `ExcelViewModel.loadFromMultipleUris` / `appendFromMultipleUris` aggiungono `dataRows` a `excelData` senza ulteriore filtro.  
   - `DatabaseViewModel.parseImportFile` usa la stessa triple da `readAndAnalyzeExcel`; `buildChunkedRows` → `ImportAnalyzer` riceve le stesse righe.  
   Correggere il **parser** allinea automaticamente preview, generated e import analysis.

### Evidenza test esistente

- `ExcelUtilsTest`: `analyzePoiSheet filters summary rows when they match summary heuristics` — ultima riga `"Total", "", "", "", "3", "4", "12"` viene esclusa.  
- **Gap:** nessun test che copra footer con token **总数** / **总价** (o layout dove `firstText` / `productName` non allineano ai token attuali).

---

## Non incluso

- Hardcode su nome file, foglio, supplier string literal “HyperAsian”.
- Workaround solo UI (nascondere l’ultima riga in griglia, in Generated o in ImportAnalysis) se il problema è risolvibile in ExcelUtils; eventuali micro-polish visivi sono ammessi solo se emergono come effetto collaterale locale e coerente, ma non sono parte dello scopo del task.
- Modifiche a DAO, Room, schema, `InventoryRepository`, `NavGraph`, business logic apply import (salvo emergenza documentata e fuori perimetro normale).
- Refactor ampio di `analyzeRowsDetailed` o estrazione modulo nuovo: solo interventi **mirati** nella stessa pipeline.
- Parser HTML/JSoup o `readAndAnalyzeExcel` URI/stream: fuori scope salvo scoperta che il bug sia solo nel ramo HTML (improbabile se il caso è XLSX POI).

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` | **Primario:** `analyzeRowsDetailed`, `isSummaryRow`, `summaryTokens`, eventuale helper dedicato riusabile e testabile. |
| `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` | Nuovi/aggiornati test JVM (`analyzePoiSheet` / workbook in-memory) per footer 总数/总价 e regressioni su test summary esistente. |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` | Opzionale: **solo** come test di conferma comportamentale (stesso parser, nessun filtro aggiunto nel VM). Preferire copertura primaria su `ExcelUtilsTest` / `analyzePoiSheet`. |
| `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` | Opzionale: **solo** come conferma a valle del parser, senza logica compensativa nel VM. |
| `docs/MASTER-PLAN.md` | Aggiornamento backlog / priorità alla creazione o attivazione del task. |

**Non toccare** (salvo evidenza contraria): `ImportAnalyzer.kt`, schermate Compose, repository.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|-----------|---------------|-------|
| 1 | Con workbook di regressione **HyperAsian-like** (fixture JVM: header reale + righe prodotto + **ultima riga footer** con 总数/总价 o equivalente documentato), `readAndAnalyzeExcel` / `analyzePoiSheet` producono `dataRows` **senza** quella riga footer | B + S | — |
| 1a | Con lo stesso workbook di regressione, `ExcelViewModel.loadFromMultipleUris` non deve più popolare `excelData` con la footer row finale; il conteggio righe preview deve riflettere solo le righe prodotto | B + S | — |
| 1a-bis | Con lo stesso workbook di regressione, anche il percorso `appendFromMultipleUris` non deve reintrodurre la footer row quando si aggiungono file compatibili; il merge finale deve continuare a riflettere solo righe prodotto | B + S | — |
| 1b | Con lo stesso workbook di regressione, `DatabaseViewModel.parseImportFile` / `buildChunkedRows` / import analysis non devono più vedere la footer row come riga candidata prodotto | B + S | — |
| 2 | Il test esistente `analyzePoiSheet filters summary rows when they match summary heuristics` resta **verde** (nessuna regressione sul caso "Total") | B + S | — |
| 3 | Suite mirata **TASK-004 / TASK-005**: almeno `./gradlew :app:testDebugUnitTest --tests "*ExcelUtilsTest*"` e, se toccato `ExcelViewModel`, i test pertinenti `ExcelViewModelTest`; in dubbio `./gradlew test` documentato | B | — |
| 4 | `assembleDebug` e `lint` senza nuovi errori (warning nuovi motivati o assenti) | B + S | — |
| 4a | Nessuna regressione prestazionale o di complessità evitabile: il fix non deve introdurre passaggi duplicati pesanti o scansioni superflue dell’intero foglio fuori dalla pipeline già esistente | S + Review | — |
| 5 | Nessun hardcode su brand/file; estensione documentata (token / euristica) **generalizzabile** ad altri fornitori con footer simile | S + Review | — |
| 6 | File reali già coperti da test automatici noti (es. Shopping Hogar printable, split header TASK-042, no-header structural TASK-025) restano coerenti: test JVM correlati **verdi** | B + S | — |
| 6a | Nessuna modifica non necessaria a firme pubbliche o comportamento esterno delle funzioni di parsing (`readAndAnalyzeExcel`, `analyzeRows`, `analyzePoiSheet`): il fix deve restare compatibile con i call site esistenti | B + S + Review | — |
| 7 | (Manuale opzionale) Smoke su 1 file HyperAsian reale in PreGenerate + 1 import analysis: ultima riga non è più riga prodotto | M | — |
| 8 | **Nessun filtro duplicato o workaround downstream:** non introdurre filtri, skip di riga o logiche compensative in `ExcelViewModel`, `DatabaseViewModel`, schermate Compose o `ImportAnalyzer`; la correzione resta **centrata nel parser** (`ExcelUtils` / `analyzeRowsDetailed`). Eventuali test che coinvolgono i ViewModel servono solo a **verificare** l’effetto del parser, non ad aggiungere regole parallele | S + Review | — |
| 9 | **Contratto pubblico del parser invariato** salvo esclusione footer/summary: la triple restituita da `readAndAnalyzeExcel` / `analyzeRows` (`header`, `dataRows`, `headerSource`) non deve cambiare **semanticamente** al di fuori della **sola** rimozione (o non-inclusione) di righe non-prodotto. **Nessun** cambiamento collaterale a mapping header, ordine delle righe prodotto valide, cardinalità colonne o “shape” dell’output salvo quanto **strettamente necessario** a escludere quella riga | B + S + Review | — |

Legenda: B=Build, S=Static (test JVM/lint), M=Manuale.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Fix **nel parser** (`ExcelUtils`), non in UI | Unica fonte di verità per `dataRows`; allinea PreGenerate, Generated, ImportAnalysis | 2026-04-10 |
| 2 | Estendere / raffinare **euristica summary** esistente invece di duplicare filtri downstream | `isSummaryRow` è già il punto unico pre-return; minimo diff e coerenza con TASK-005 A2 | 2026-04-10 |
| 3 | **Evitare doppie correzioni a valle:** niente filtri paralleli o post-processing delle righe nei call site | Una sola fonte di verità evita drift, bug doppi e manutenzione duplicata | 2026-04-10 |
| 4 | Se il parser è corretto, **preview e import analysis devono beneficiarne automaticamente** senza logica additiva in `ExcelViewModel` / `DatabaseViewModel` / UI / `ImportAnalyzer` | I call site consumano già `dataRows`; il contratto resta invariato | 2026-04-10 |
| 5 | Privilegiare una soluzione **a segnali combinati** (lessico + identità prodotto + struttura) rispetto a un semplice ampliamento cieco dei token | Riduce il rischio di falsi positivi su prodotti reali con testo borderline | 2026-04-10 |
| 6 | Mantenere il primo fix il più piccolo possibile e rinviare eventuali euristiche “avanzate” solo se i test mostrano che token + identità non bastano | Coerente con il principio di minimo cambiamento necessario | 2026-04-10 |
| 7 | Evitare di toccare la logica di header detection o la pipeline HTML/URI salvo evidenza diretta che il bug non sia risolvibile nel filtro summary/footer finale | Mantiene il task focalizzato e riduce il rischio di regressioni laterali su TASK-025 / TASK-042 | 2026-04-10 |
| 8 | **Preservare il contratto del parser** e limitare il diff alla **sola classificazione / esclusione** footer vs riga prodotto | I call site e i test consolidati dipendono dalla triple stabile; scope creep su altre fasi della pipeline aumenta il rischio di regressioni invisibili | 2026-04-10 |
| 9 | **Evitare regressioni indirette** su ViewModel, preview, import analysis e sui test che consumano la triple di `readAndAnalyzeExcel` | Ogni cambio fuori dal filtro footer/summary va trattato come costo aggiuntivo e probabile fuori perimetro | 2026-04-10 |
| 10 | Preferire helper `private` / `internal` locali e riusabili invece di allargare API o introdurre nuovi contratti pubblici | Mantiene basso l’impatto del task e riduce il rischio di coupling inutile fuori da `ExcelUtils.kt` | 2026-04-10 |

---

## Gate di planning per EXECUTION

> **Stato gate: APPROVATO — 2026-04-10** (audit repo-grounded completato; planning validato e integrato; task portato a `EXECUTION`).

I criteri sotto sono stati verificati in sede di audit e risultano soddisfatti. Restano come riferimento per l'esecutore:

- Esiste **almeno un test che fallisce (rosso)** prima del fix, che riproduce il caso footer **HyperAsian-like** in modo **sintetico** (fixture JVM / POI in-memory, niente dipendenza da file proprietario per la regressione obbligatoria).
- La strategia scelta è ancora la **più piccola sufficiente**: in ordine, **summaryTokens** (o equivalente lessicale mirato) + **identità prodotto plausibile** prima di euristiche più sofisticate (strutturali, aggregati, statistiche).
- **Non** è necessario introdurre filtri duplicati in `ExcelViewModel`, `DatabaseViewModel`, `ImportAnalyzer` o UI; ogni tentazione in quella direzione va documentata come **non necessaria** o respinta salvo prova documentata che il parser non possa restare source of truth.
- Il fix previsto **non deve modificare il contratto del parser** (triple `header` / `dataRows` / `headerSource`) al di là dell’esclusione delle righe footer/summary; **non** si allarga il perimetro a **mapping header**, **alias**, **`mergeHeaderRows`** o scoring colonne salvo **evidenza concreta** emersa dai test (fallimento ripetuto con fixture che provano che il bug non è risolvibile nel solo filtro footer).
- L’uso del file reale HyperAsian resta **solo smoke locale opzionale** (o manuale), non gate di merge né unica prova di correttezza.
- l’implementazione prevista non richiede nuove API pubbliche né modifiche di firma a `readAndAnalyzeExcel`, `analyzeRows` o `analyzePoiSheet`, salvo prova concreta che i test non siano risolvibili restando compatibili.

---

## Planning (Claude)

### Analisi — causa probabile

- **Primaria:** `isSummaryRow` non riconosce token footer comuni (**总数**, **总价**, …) presenti nella lista né in `firstText` né in `productName.startsWith(token)` dopo normalizzazione.
- **Secondaria:** dipendenza da **prima** cella testuale non numerica in ordine di colonna: se a sinistra ci sono celle vuote, numeri, o testo spurio, `firstText` può non essere l’etichetta di totale attesa (da validare con fixture).
- **Osservazione di efficienza:** oggi la classificazione “data-like” è già presente in più punti della pipeline; il fix futuro dovrebbe evitare di aumentare ulteriormente la duplicazione logica, privilegiando helper riusabili o condizioni centralizzate dove il diff resta piccolo e leggibile.
- **Terziaria:** se `lacksIdentity` fallisce (es. `productName` mappato su una cella con testo lungo per allineamento colonna errato), anche con token corretti la riga potrebbe non essere filtrata — da verificare sul layout reale anonimizzato.
- **Quaternaria (aderenza al bug reale):** il caso osservato **non** si riduce necessariamente a “token mancante in `summaryTokens`”. I **valori aggregati** (o etichette miste) possono finire in colonne che il parser mappa come **`barcode`**, **`itemNumber`** o **`productName`**, creando una **identità prodotto solo apparentemente valida**. In quella situazione `lacksIdentity` può restare falso e una **sola estensione cieca dei token** **non** va considerata automaticamente sufficiente a chiudere il task.

### Perché la riga passa i filtri oggi

1. Supera `looksDataLike` / filtro `dataRows` post-header (≥4 non blank, ≥2 numeri, ≥1 testo).
2. Non viene rimossa da `isSummaryRow` perché `looksLikeToken` è falso (token assenti o mismatch) **o** `lacksIdentity` è falso.
3. Nel caso **HyperAsian-like avanzato**, `lacksIdentity` resta **falso** se valori aggregati o testo di riepilogo occupano colonne interpretate come **barcode / itemNumber / productName**, così la riga sembra ancora un prodotto anche quando i token footer sono stati parzialmente coperti.

### Dove mettere il fix

- **Primario:** `ExcelUtils.kt`, blocco finale di `analyzeRowsDetailed` — ampliamento criteri `isSummaryRow` e/o `summaryTokens`, eventualmente estrazione in `internal fun isFooterOrSummaryRow(...)` con parametri espliciti (`row`, `headerMap`, contesto opzionale tipo “ultima riga del foglio”) per testabilità.
- **Perimetro preferito:** toccare prima il filtro summary/footer più a valle possibile dentro `analyzeRowsDetailed`, senza spostare il problema su detection header, merge header rows o altri stadi della pipeline se non strettamente necessario.
- **Non** spostare la logica in `ExcelViewModel` / `DatabaseViewModel`.
- **Vincolo anti-pattern:** se durante l’analisi emerge la tentazione di correggere anche `ExcelViewModel` o `DatabaseViewModel`, trattarla come **anti-pattern** salvo **prova forte e documentata** che il parser non possa restare l’unica source of truth per `dataRows` (eccezione teorica da evitare salvo evidenza irrefutabile).

#### Struttura logica del fix a `isSummaryRow` (prescrizione esplicita)

Il codice attuale (confermato repo-grounded):

```kotlin
val lacksIdentity = code.isBlank() && item.isBlank() && name.length < 3
return looksLikeToken && manyNumbers && lacksIdentity
```

**Problema preciso:** la congiunzione AND con `lacksIdentity` blocca il filtro non appena uno dei tre campi identità risulta non-vuoto o `name.length >= 3`. Casi reali che causano falso negativo:
- `productName = “总数量”` (3 chars) → `name.length < 3` è false → `lacksIdentity = false` → riga non filtrata nonostante token presente
- `productName = “合计总数”` (4 chars) → stesso problema
- `barcode` o `itemNumber` mappati su valori aggregati (numeri, somme) → `code.isBlank()` / `item.isBlank()` false → riga non filtrata

**Prescrizione:** il fix deve cambiare la condizione in modo che, quando `looksLikeToken` è forte (token noto presente) e `manyNumbers` è vero, la riga venga esclusa **anche se** i campi identità appaiono parzialmente popolati. Due approcci accettabili (l’esecutore sceglie quello più testabile e minimale):
1. **Segnali combinati senza `lacksIdentity` stretta:** `return looksLikeToken && manyNumbers` — più semplice ma richiede che `test 3 (negativo)` sia abbastanza robusto da catturare falsi positivi; ammissibile solo se `looksLikeToken` è già sufficientemente specifico.
2. **Congiunzione OR su identità:** `return looksLikeToken && manyNumbers && (lacksIdentity || !hasPlausibleProductIdentity(...))` — più conservativa, distingue “identità vera” da “aggregato mappato su campo identità”.

**Guardrail obbligatorio:** qualunque approccio scelto, il `test 3 (negativo)` — riga con barcode reale + productName lungo con “total” nella descrizione — deve restare verde. Se fallisce, la condizione token è troppo debole o la negazione su `lacksIdentity` è troppo aggressiva.

**Nota testabilità:** `isSummaryRow` è una `fun` locale che chiude su `headerMap` dello scope esterno. I test obbligatori devono passare tramite `analyzePoiSheet` / workbook in-memory (come l’esistente test summary), **non** testare `isSummaryRow` direttamente a meno che l’esecutore scelga di estrarla come `internal fun`.

### Strategie di robustezza (da valutare in EXECUTION, possibilmente combinate)

1. **Lessico generalizzato (primo step, costo basso)**  
   Aggiungere a `summaryTokens` (o set dedicato “quantity-total” / “price-total”) stringhe normalizzate come **总数**, **总价**, **总件数**, **合计数量**, **合计金额**, più eventuali equivalenti già ricorrenti osservati nei test sintetici. Questo è il primo tentativo perché è il più economico e leggibile.

   **Guardrail:** l’estensione dei token è **solo** il primo step; **da sola** può **non bastare** se la footer row continua a sembrare una **riga prodotto credibile** (es. campi mappati come identità riempiti da aggregati o testo ambiguo). Il planning e i test devono restare ancorati anche al passo successivo (identità plausibile / segnali combinati).

2. **Identità prodotto plausibile (secondo step, raccomandato)**  
   Introdurre un helper esplicito tipo `hasPlausibleProductIdentity(...)` che valuti in modo conservativo segnali come barcode plausibile, itemNumber non puramente riepilogativo, nome prodotto non banalmente coincidente con etichette summary. Il filtro summary non deve dipendere solo da campi vuoti, ma da assenza di identità prodotto credibile.

   **Efficienza / testabilità:** la prima implementazione deve preferire **helper piccoli, puri e facilmente testabili su JVM**, con **dipendenze minime** e **senza** bisogno di fixture Android, `Uri` reali o `ContentResolver` per la regressione obbligatoria (POI in-memory / `analyzeRowsDetailed` / `analyzePoiSheet` come superfici già consolidate).

3. **Match più tolleranti ma controllati**  
   Valutare `contains` o varianti normalizzate solo se protette da segnali aggiuntivi (`manyNumbers`, assenza identità prodotto, posizione finale o quasi finale), per evitare falsi positivi su descrizioni prodotto reali.

4. **Segnale strutturale “footer row” (opzionale, solo se serve)**  
   Se lessico + identità non bastano, considerare il fatto che la riga sia ultima / quasi ultima e molto diversa dal pattern delle righe precedenti. Questo segnale deve restare debole e mai sufficiente da solo.

5. **Euristiche avanzate rinviate salvo necessità**  
   Controlli come coerenza aggregati quantità/prezzo/totale o pattern statistici di colonna sono possibili, ma vanno introdotti solo se i test dimostrano che i livelli precedenti non sono sufficienti. Evitare over-engineering in prima iterazione.
6. **Normalizzazione e documentazione minima dei token scelti**  
   Se vengono aggiunti token footer nuovi, mantenerli coerenti con la normalizzazione già usata dal parser e documentare in `Execution` perché sono stati scelti, distinguendo chiaramente tra token realmente osservati e token aggiunti in via prudenziale.

### Rischi di regressione

> **Ambito:** la tabella seguente copre i **rischi funzionali del parser** (falsi positivi/negativi su classificazione righe, regressioni su workbook già supportati).

| Rischio | Mitigazione |
|---------|-------------|
| Escludere un prodotto reale con nome “Total …” o testo corto in barcode vuoto | Mantenere requisiti combinati (token + numeri + `lacksIdentity` o sostituto più robusto); test negativi con riga prodotto borderline |
| `contains` troppo aggressivo su `productName` lungo | Preferire match su `firstText` / colonne note o token con confini (word boundary / lunghezza max) |
| Footer a metà foglio (subtotali) rimossi indebitamente | Limitare euristica strutturale “ultima riga” o richiedere più segnali contemporanei |
| Rotura layout TASK-042 (header multi-riga) | Eseguire test Shopping Hogar / split header dopo modifica |
| Footer con “identità” apparente (aggregati in colonne barcode/item/description) non escluso dopo solo estensione token | Fixture §Test 1a + regole combinate (summary + plausibilità identità); non accettare fix solo-lessico se il test insidioso resta rosso |

### Test da aggiungere o aggiornare

1. **Nuovo test positivo** in `ExcelUtilsTest`: workbook/sheet con header standard + righe prodotto valide + ultima riga footer con **总数** / **总价** e numeri aggregati; assert che la footer row venga esclusa.
1a. **Variante sintetica “HyperAsian-like insidiosa” (obbligatoria):** oltre al footer con token **总数** / **总价**, aggiungere una fixture in cui **parte dei valori aggregati o del testo di riepilogo** finisce in colonne che `analyzeRowsDetailed` può mappare come **`barcode`**, **`itemNumber`** o **`productName`** (simulando il fallimento reale in cui la riga totale **non** soddisfa più `lacksIdentity` “vuoto”). Questa variante deve fallire **prima** del fix e passare **dopo**, coprendo esplicitamente il caso osservato.

   **Esempio concreto di dati fixture (obbligatorio per validare il caso insidioso):** la footer row deve avere almeno uno tra:
   - `productName = “总数量”` (3 chars, length ≥ 3 → `name.length < 3` è false con il codice attuale)
   - oppure `productName = “合计总数”` (4 chars)
   - oppure `barcode` o `itemNumber` non-blank (es. valore aggregato `”0”` o `”150”` mappato su quella colonna dal layout reale)

   Il test **deve** dimostrare che, **prima** del fix, `analyzePoiSheet` include quella riga in `dataRows` (cioè `lacksIdentity` è false e la riga passa). Se la fixture scelta soddisfa comunque `lacksIdentity`, il test non copre il caso reale — rivedere i dati.
2. **Opzionale — conferma a valle:** test leggero in `ExcelViewModelTest` (o simile) solo se l’infrastruttura esiste già **senza** aggiungere filtri nel VM; obiettivo = verificare che, con parser corretto, `loadFromMultipleUris` non includa la footer row. La copertura **obbligatoria** resta su `ExcelUtilsTest`.
2a. **Opzionale — append path:** se esiste copertura comoda in `ExcelViewModelTest`, aggiungere un caso minimo che confermi che anche `appendFromMultipleUris` eredita il fix del parser senza logica speciale.
3. **Test negativo importante (obbligatorio)**: riga prodotto reale con descrizione contenente parola simile a `total` / `totale` ma con barcode o identità prodotto credibile; la riga deve restare inclusa **sia prima che dopo il fix**.

   **Esempio concreto di dati fixture:** `barcode = "12345678"`, `itemNumber = "ITEM-001"`, `productName = "Totale assortimento prodotti"` (o `"Subtotale colori varianti"`, comunque lunghezza > 3 e con parola token), `quantity = "5"`, `purchasePrice = "10.00"`, `totalPrice = "50.00"`. Con questo dato, `lacksIdentity` è false e `looksLikeToken` potrebbe essere true — il test verifica che la riga **non** venga filtrata. Se il fix rimuove troppo aggressivamente `lacksIdentity` e questo test diventa rosso, la condizione va ristretta.
4. Rieseguire e mantenere verdi i test summary già esistenti e i casi consolidati dei task `TASK-025` e `TASK-042` collegati a `ExcelUtils.kt`.
5. Valutare `DatabaseViewModelTest` solo se il percorso import analysis ha già copertura facile da estendere senza introdurre complessità sproporzionata.
6. Verificare che i test nuovi continuino a coprire il caso senza header / generated columns già consolidato, evitando che il fix summary/footer sposti accidentalmente il comportamento del parser in workbook meno strutturati.

### Piano di esecuzione (per futuro esecutore, post-approvazione)

1. Creare prima **almeno un test rosso** che riproduca il caso HyperAsian-like in modo sintetico e condivisibile, includendo **non solo** il footer con token noti, ma anche il caso **più insidioso** in cui la riga totale mantiene una **falsa identità prodotto apparente** (vedi fixture §Test 1a). Senza quest’ultimo, il rischio è di chiudere il task solo sul lessico e reintrodurre il bug in produzione.
2. Applicare il **minimo fix** nel parser (`summaryTokens` + eventuale helper per identità prodotto plausibile).
2a. Verificare durante l’implementazione che il fix non allarghi inutilmente la duplicazione di euristiche già presenti; se possibile mantenere la nuova logica in helper locali chiari e riusabili, senza refactor estesi fuori scope.
3. Verificare preview (`ExcelViewModel`) e import analysis (`DatabaseViewModel`) **solo come conferma del fix a monte** (comportamento osservato / test di integrazione leggeri se già convenienti), **senza** introdurre filtri duplicati, skip espliciti di riga o logiche compensative downstream.
4. Rieseguire la baseline test mirata (`ExcelUtilsTest`, eventuale `ExcelViewModelTest`, più gli altri test collegati a `ExcelUtils.kt`).
5. Fare smoke manuale opzionale sul file reale HyperAsian solo come conferma finale, non come unica prova.
6. Documentare in `Execution` quali segnali sono stati scelti, quali sono stati scartati e perché.

### Rischi identificati

> **Ambito:** questa sezione copre rischi **trasversali di processo e copertura** (estendibilità del lessico, disponibilità di fixture condivisibili, tentazione di workaround downstream), complementari alla tabella «Rischi di regressione» sopra.

- Euristica basata solo su lessico: fornitori nuovi possono usare etichette non coperte → mitigare con helper di identità prodotto e test sintetici estendibili.
- Euristica troppo aggressiva: rischio di escludere prodotti reali con descrizioni borderline → mitigare con test negativi obbligatori e regole combinate.
- Mancanza di file fixture pubblicabile: usare righe sintetiche che replicano la struttura, non il file proprietario, e tenere il file reale solo per smoke locale opzionale.

---

## Ottimizzazioni consigliate al planning

- Il percorso preferito per l’implementazione futura deve essere: **test rosso → minimo fix parser → verifica preview/import analysis → regressione mirata**.
- Evitare di pianificare da subito euristiche statistiche o controlli troppo sofisticati: inserirli solo come fallback documentato.
- Evitare anche di allargare il task a stadi più alti della pipeline (header detection, merge header, ViewModel, ImportAnalyzer) finché il bug resta risolvibile nel filtro summary/footer finale.
- La priorità non è “riconoscere tutti i footer possibili” in un colpo solo, ma risolvere bene il caso reale estendendo la robustezza senza rompere i file già compatibili.
- Nessun cambiamento UX/UI è richiesto per chiudere questo task; se durante l’esecuzione emergerà un micro-miglioramento visivo locale e coerente, dovrà essere secondario, tracciato nel log e non confuso con il fix del parser.
- Se emergono **più soluzioni equivalenti** sul piano funzionale, scegliere quella con **minor costo cognitivo**, **minor diff** e **miglior leggibilità** per chi manterrà `ExcelUtils.kt` nel tempo.
- Evitare anche di “sporcare” il parser con euristiche poco leggibili o liste di token opache: ogni nuova regola deve restare spiegabile, localizzata e facilmente copribile con test.
- La regressione principale da coprire non è solo “footer con token nuovo”, ma **“footer che passa ancora perché sembra avere identità prodotto”** (barcode/itemNumber/productName popolati in modo plausibile ma non merceologico). Questo caso deve restare **centrale** nel planning e nella suite JVM obbligatoria.

---

## Execution

### Esecuzione — 2026-04-10

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt` — fix locale nel filtro finale summary/footer di `analyzeRowsDetailed`: normalizzazione label, token footer cinesi aggiuntivi e helper per distinguere identità prodotto plausibile / forma coerente della riga da aggregati di riepilogo spostati nelle colonne identitarie.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ExcelUtilsTest.kt` — nuovi test JVM per footer HyperAsian-like semplice, variante insidiosa con falsa identità prodotto, caso reale con aggregati spostati nelle colonne identitarie e caso negativo con prodotto reale che inizia con `Total`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — test di conferma parser-side su `loadFromMultipleUris` e `appendFromMultipleUris`, senza filtri downstream.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — test di conferma parser-side su `startImportAnalysis`, senza workaround in `DatabaseViewModel`.
- `docs/TASKS/TASK-043-robustezza-esclusione-righe-totali-footer-preview-import-analysis.md` — log di execution, stato task portato a `REVIEW`, criteri verificati e handoff aggiornato.

**Azioni eseguite:**
1. Letti `docs/MASTER-PLAN.md`, il file task e i file reali `ExcelUtils.kt`, `ExcelUtilsTest.kt`, `ExcelViewModelTest.kt` prima di intervenire; rilevato il mismatch di governance (`MASTER-PLAN` ancora su `TASK-041`) e lasciato invariato perché fuori scope e già dirty nel workspace.
2. Aggiunti prima i test rossi in `ExcelUtilsTest` per il caso footer con `总数` / `总价`, per la variante insidiosa con `barcode = "0"`, `itemNumber = "150"` e `productName = "合计总数"`, per il negativo `Total Care Shampoo` e, nel follow-up, per il layout reale in cui la footer row mette `总数` / `728.000` / `总价` / `685920.000` nelle colonne `itemNumber` / `barcode` / `productName` / `secondProductName` lasciando vuote `quantity` / `purchasePrice` / `totalPrice`; con `JAVA_HOME` puntato al JBR di Android Studio, `./gradlew :app:testDebugUnitTest --tests "*ExcelUtilsTest"` è andato rosso sui casi footer prima del fix.
3. Applicato il minimo fix nel parser solo in `ExcelUtils.kt`, nel filtro summary/footer finale di `analyzeRowsDetailed`: nessun cambiamento a `readAndAnalyzeExcel`, `analyzeRows`, `analyzePoiSheet`, header detection, merge header, pipeline HTML/URI o call site downstream.
4. Rafforzato il riconoscimento footer con segnali locali e leggibili: `isSummaryLabel(...)` per label/footer normalizzati (`总数`, `总价`, `总数量`, `总金额`, `总件数`, oltre ai token summary già esistenti), `hasPlausibleProductIdentity(...)` per non trattare aggregati corti come identità prodotto, e `hasShiftedAggregatePattern(...)` per escludere righe in cui i numeri aggregati finiscono nelle colonne identitarie mentre le colonne numeriche mappate del prodotto restano vuote.
5. Aggiunti solo test di conferma a valle in `ExcelViewModelTest` e `DatabaseViewModelTest` per dimostrare che preview e import analysis beneficiano automaticamente del fix del parser, senza filtri duplicati in `ExcelViewModel`, `DatabaseViewModel`, `ImportAnalyzer` o UI.
6. Eseguito anche uno smoke parser-side locale sul file reale fornito dall’utente `/Users/minxiang/Downloads/20260404-Hyper asian2.xlsx` tramite test temporaneo non tracciato, poi rimosso per non lasciare path assoluti nella repo; il workbook reale non presenta più la footer row nei `dataRows`.
7. Rieseguiti i check richiesti con `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`: baseline `ExcelUtilsTest` verde dopo il fix; poi baseline mirata `ExcelUtilsTest` + `ExcelViewModelTest` + `DatabaseViewModelTest`; infine `assembleDebug` e `lint`, tutti verdi.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` verde |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` verde |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning nuovo dai file toccati; osservati solo warning/deprecazioni Gradle/AGP/Kotlin già preesistenti al task |
| Coerenza con planning    | ✅ ESEGUITO | Fix confinato al parser (`ExcelUtils.kt`), senza workaround UI/ViewModel/ImportAnalyzer e senza modifiche a header detection / merge header / pipeline HTML-URI |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti con stato finale documentato sotto; criterio manuale opzionale `#7` marcato `⚠️ NON ESEGUIBILE` nel workspace attuale |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti:
  - rosso pre-fix: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest --tests "*ExcelUtilsTest"` con fallimento dei nuovi test footer
  - verde post-fix: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest --tests "*ExcelUtilsTest" --tests "*ExcelViewModelTest" --tests "*DatabaseViewModelTest"`
- Test aggiunti/aggiornati:
  - `analyzePoiSheet filters hyperasian like footer rows with Chinese total labels`
  - `analyzePoiSheet filters footer rows even when they keep false product identity`
  - `analyzePoiSheet filters footer rows when aggregates shift into identity columns`
  - `analyzePoiSheet keeps real products whose names start with total`
  - `loadFromMultipleUris excludes footer rows with false product identity from preview`
  - `appendFromMultipleUris keeps footer rows excluded for compatible appended files`
  - `startImportAnalysis excludes footer rows with false product identity`
- Limiti residui:
  - smoke parser-side eseguito sul file reale locale fornito dall’utente, ma nessun smoke UI/manuale end-to-end su emulator/device dopo il follow-up;
  - nessun test emulator/device richiesto dal task o necessario per un fix parser-side.

**Criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | `ExcelUtilsTest`: `analyzePoiSheet filters hyperasian like footer rows with Chinese total labels` + `analyzePoiSheet filters footer rows when aggregates shift into identity columns`; smoke parser-side locale verde anche sul file reale `/Users/minxiang/Downloads/20260404-Hyper asian2.xlsx` |
| 1a | ✅ ESEGUITO | `ExcelViewModelTest`: `loadFromMultipleUris excludes footer rows with false product identity from preview` conferma che `excelData` contiene solo righe prodotto |
| 1a-bis | ✅ ESEGUITO | `ExcelViewModelTest`: `appendFromMultipleUris keeps footer rows excluded for compatible appended files` conferma che l’append non reintroduce la footer row |
| 1b | ✅ ESEGUITO | `DatabaseViewModelTest`: `startImportAnalysis excludes footer rows with false product identity` conferma che l’analysis non vede la footer row come nuovo prodotto |
| 2 | ✅ ESEGUITO | Il test esistente `analyzePoiSheet filters summary rows when they match summary heuristics` resta verde nella suite completa `ExcelUtilsTest` |
| 3 | ✅ ESEGUITO | Eseguita la baseline mirata `ExcelUtilsTest` + `ExcelViewModelTest` + `DatabaseViewModelTest` con esito verde |
| 4 | ✅ ESEGUITO | `assembleDebug` e `lint` verdi |
| 4a | ✅ ESEGUITO | Nessun passaggio full-sheet aggiuntivo: il fix aggiunge solo helper locali string-based nel filtro finale di singola riga |
| 5 | ✅ ESEGUITO | Nessun hardcode su file/brand; il fix estende token/footer generalizzabili e usa segnali combinati leggibili |
| 6 | ✅ ESEGUITO | Restano verdi le classi complete `ExcelUtilsTest` e `ExcelViewModelTest`, che includono le regressioni correlate a `TASK-025` e `TASK-042` |
| 6a | ✅ ESEGUITO | Nessuna firma pubblica cambiata; contratto di `readAndAnalyzeExcel` / `analyzeRows` / `analyzePoiSheet` invariato |
| 7 | ⚠️ NON ESEGUIBILE | Smoke manuale opzionale UI non eseguito dopo il follow-up; eseguito però smoke parser-side locale sul file reale fornito dall’utente, oltre alla copertura automatica JVM/VM/DB |
| 8 | ✅ ESEGUITO | Nessun filtro duplicato introdotto in `ExcelViewModel`, `DatabaseViewModel`, `ImportAnalyzer` o UI; cambiano solo test di verifica a valle |
| 9 | ✅ ESEGUITO | `header`, `dataRows` e `headerSource` restano semanticamente invariati fuori dalla sola esclusione della riga footer/summary |

**Incertezze:**
- `MASTER-PLAN.md` risulta già modificato localmente e continua a indicare `TASK-041` come task attivo globale; non è stato aggiornato in questo task per rispettare il perimetro e la regola “un solo task attivo”.
- Il file reale HyperAsian fornito dall’utente è stato verificato parser-side, ma non è stata eseguita una nuova verifica manuale UI su emulator/device dopo il follow-up del fix.

**Handoff notes:**
- Il task è pronto per `REVIEW`: il fix è confinato al parser, i test mirati sono verdi e non ci sono workaround downstream da ripulire.
- In review verificare solo il tradeoff dell’euristica locale `isSummaryLabel(...)` + `hasPlausibleProductIdentity(...)` + `hasShiftedAggregatePattern(...)`; non sono emerse evidenze che richiedano toccare header detection o altri stadi della pipeline.
- Se serve una conferma extra fuori CI, lo smoke manuale opzionale può essere eseguito in un secondo momento su un file HyperAsian reale, ma non è stato necessario per chiudere l’Execution.

---

## Review

### Review — 2026-04-10

**Revisore:** Claude (planner)

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Footer HyperAsian-like (`总数`/`总价`) esclusa da `dataRows` | ✅ | Test `filters hyperasian like footer rows with Chinese total labels` verde; smoke locale su file reale confermato dall'esecutore |
| 1a | `loadFromMultipleUris` non popola `excelData` con la footer row | ✅ | `ExcelViewModelTest`: `loadFromMultipleUris excludes footer rows with false product identity from preview` |
| 1a-bis | `appendFromMultipleUris` non reintroduce la footer row | ✅ | `ExcelViewModelTest`: `appendFromMultipleUris keeps footer rows excluded for compatible appended files` |
| 1b | `startImportAnalysis` non vede la footer row come riga prodotto | ✅ | `DatabaseViewModelTest`: `startImportAnalysis excludes footer rows with false product identity` |
| 2 | Test esistente `filters summary rows when they match summary heuristics` resta verde | ✅ | Incluso nella suite completa; 40/40 senza regressioni |
| 3 | Baseline mirata `ExcelUtilsTest` + `ExcelViewModelTest` + `DatabaseViewModelTest` verde | ✅ | 40 + 45 + 23 test, 0 failures, 0 errors — verificato dai report XML build |
| 4 | `assembleDebug` e `lint` senza nuovi errori | ✅ | Documentato in Execution; nessun warning nuovo dai file toccati |
| 4a | Nessun passaggio full-sheet aggiuntivo o logica pesante introdotta | ✅ | Helper locali string-based a costo O(1) per riga; nessuna scansione aggiuntiva |
| 5 | Nessun hardcode su brand/file; token/euristica generalizzabili | ✅ | Token cinesi + latini normalizzati; helper basati su segnali combinati, non su nomi produttore |
| 6 | Test correlati a TASK-025 e TASK-042 restano verdi | ✅ | Suite completa ExcelUtilsTest e ExcelViewModelTest verdi incluse le regressioni precedenti |
| 6a | Nessuna modifica a firme pubbliche di `readAndAnalyzeExcel` / `analyzeRows` / `analyzePoiSheet` | ✅ | Verificato: solo helper locali privati aggiunti in `analyzeRowsDetailed`; contratto esterno invariato |
| 7 | Smoke manuale UI su file HyperAsian reale | ⚠️ NON ESEGUIBILE | Non eseguito su emulator/device; eseguito smoke parser-side locale sul file reale da parte dell'esecutore |
| 8 | Nessun filtro duplicato o workaround downstream | ✅ | `ExcelViewModel.kt` e `DatabaseViewModel.kt` verificati: nessuna mention di footer/summary/filterNot ad hoc introdotta |
| 9 | Contratto del parser invariato salvo esclusione footer | ✅ | `header`, `dataRows`, `headerSource` semanticamente invariati; ordine righe prodotto valide preservato |

**Analisi tecnica repo-grounded:**

Fix in `ExcelUtils.kt` — blocco finale di `analyzeRowsDetailed` (righe ~1334–1439):

1. **`normalizeSummaryLabel` + `summaryTokens` estesi**: aggiunto `总数`, `总价`, `总数量`, `总金额`, `总件数` direttamente in lista (erano mancanti). `summarySuffixTokens` abilitano il matching di pattern composti come `合计总数`, `合计总价` via `startsWith(token) + suffixMatch`. Logica corretta e verificata su tutti i casi di test.

2. **`isSummaryLabel(value)`**: helper locale che combina direct-match + prefix+suffix match con normalizzazione. Efficiente (O(n) sui token, costante sul numero token), testabile tramite `analyzePoiSheet`.

3. **`hasPlausibleItemIdentity(item)`**: soglia conservativa — stringhe solo-digit richiedono almeno 4 cifre (esclude "0", "2", "150"); stringhe alfanumeriche o con separatori sono ammesse. Corretto: "ITEM-001" è plausibile; "150" non lo è (case reale HyperAsian).

4. **`hasPlausibleProductIdentity(code, item, name, secondName)`**: combina barcode (≥8 digit), itemNumber plausibile, productName lungo+non-summary, secondProductName lungo+non-summary. Condizione OR robusta.

5. **`hasShiftedAggregatePattern(...)`**: rileva layout dove i numeri aggregati finiscono nelle colonne identitarie (code/item/name/secondName) mentre le colonne numeriche del prodotto (quantity/price/total/...) sono tutte vuote. Cattura il caso reale HyperAsian dove `总数 | 728.000 | 总价 | 685920.000 | "" | "" | ""`.

6. **`isSummaryRow` aggiornato**: `looksLikeToken && manyNumbers && (lacksPlausibleIdentity || shiftedAggregates)`. La congiunzione OR sul secondo fattore copre sia il caso semplice (nessuna identità) che il caso insidioso (falsa identità con aggregati spostati).

7. **Negative test**: `Total Care Shampoo` con barcode reale `12345678` e itemNumber `ITEM-001` → `looksLikeToken = false` (suffix "careshampoo" non in `summarySuffixTokens`) e `hasPlausibleProductIdentity = true` → riga conservata ✓

**Nessun problema trovato.** Fix corretto, minimale, senza regressioni. Nessun ciclo FIX necessario.

**Problemi trovati:** nessuno.

**Verdetto:** APPROVED

**Note residue (non bloccanti):**
- `isSummaryLabel("合计金额")` restituisce false (suffix "金额" non in `summarySuffixTokens`): gap minore non rilevante per il task; "合计" + "总金额" sono comunque in lista. Se in futuro si incontra questo pattern, aggiungere "金额" a `summarySuffixTokens` è 1-liner.
- Smoke manuale UI su device/emulator non eseguito: non bloccante per un fix parser-side con copertura JVM completa e smoke parser-side locale sul file reale confermato.

---

## Fix

### Fix — 2026-04-10

- Nessun ciclo `REVIEW → FIX` eseguito in questa fase: il fix parser descritto in **Execution** è l’implementazione iniziale completata e consegnata a `REVIEW`.

---

## Chiusura

| Campo           | Valore |
|-----------------|--------|
| Stato finale    | `DONE` |
| Data chiusura   | 2026-04-10 |
| Tutti i criteri | Sì sul perimetro automatico; criterio `#7` manuale opzionale documentato come `⚠️ NON ESEGUIBILE` nel workspace attuale |
| Rischi residui  | Nessun rischio bloccante emerso; resta solo l’eventuale smoke manuale opzionale su file HyperAsian reale se richiesto in review |

---

## Riepilogo finale

- Fix parser-side locale in `ExcelUtils.kt`: la footer row summary non entra più in `dataRows`, anche quando mantiene una falsa identità prodotto apparente o quando gli aggregati si spostano nelle colonne identitarie lasciando vuote le colonne numeriche mappate del prodotto.
- Preview (`loadFromMultipleUris` / `appendFromMultipleUris`) e import analysis (`startImportAnalysis`) beneficiano automaticamente del fix senza logiche parallele.
- Baseline mirata, `assembleDebug` e `lint` verdi; task aggiornato a `REVIEW`, senza toccare `MASTER-PLAN.md` perché fuori perimetro e già sporco nel workspace.

---

## Handoff

- **Stato task:** `REVIEW` (2026-04-10) — execution completata, verifiche statiche verdi, in attesa della review formale.
- **Fix concentrato:** la logica del fix vive solo in `ExcelUtils.kt`, nel filtro finale summary/footer di `analyzeRowsDetailed`; non ci sono compensazioni da ripulire in ViewModel, UI o `ImportAnalyzer`.
- **Nota governance:** `MASTER-PLAN.md` non è stato riallineato in questo task per evitare scope creep su una worktree già sporca e per rispettare la governance del task attivo globale.
- **Se la review chiede maggiore robustezza:** partire dai nuovi test negativi/positivi in `ExcelUtilsTest`, in particolare dal caso `aggregates shift into identity columns`, prima di allargare l’euristica o toccare altri stadi della pipeline.
- **Smoke/manuale opzionale:** se desiderato in review, aprire di nuovo `20260404-Hyper asian2.xlsx` in preview e import analysis per confermare visivamente che l’ultima riga footer non venga più proposta come prodotto.
