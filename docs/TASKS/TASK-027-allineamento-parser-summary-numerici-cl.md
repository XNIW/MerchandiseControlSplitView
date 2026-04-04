# TASK-027 — Allineare summary/totali ai parser numerici CL condivisi

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-027                   |
| Stato              | DONE                       |
| Priorità           | ALTA                       |
| Area               | Numeri / History / ExcelViewModel |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03 (review completa, micro-fix test, build/test/lint verdi, DONE) |
| Tracking `MASTER-PLAN` | **`DONE`**             |

---

## Dipendenze

- TASK-023 (DONE)
- TASK-004 (DONE)

---

## Scopo

Allineare il calcolo dei totali (`orderTotal`, `paymentTotal`, quantità e missing) alla policy numerica cilena già centralizzata con TASK-023. Il task esiste perché `ExcelViewModel` usa ancora parse locale semplificato nei summary, con rischio di risultati silenziosamente errati su input come `1.234`.

---

## Contesto

- `calculateInitialSummary` e `calculateFinalSummary` in `ExcelViewModel.kt` fanno parse con `replace(",", ".").toDoubleOrNull()`.
- La repo dispone già di parser centralizzati in `ClNumberFormatters.kt`.
- Il bug è localizzato, ma impatta dati user-visible in history e flussi generated/manual entry.

---

## Non incluso

- Refactor generale di tutta la pipeline numerica.
- Cambi alle regole di formatting display già definite in TASK-023.
- Nuove feature export/import.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — summary calculations
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt` — sola lettura salvo helper minimo strettamente necessario
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt` — solo se serve copertura aggiuntiva helper

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `calculateInitialSummary` usa parser coerenti con la policy CL condivisa | B + S | ESEGUITO |
| 2 | `calculateFinalSummary` usa parser coerenti con la policy CL condivisa | B + S | ESEGUITO |
| 3 | Input tipo `1.234`, `1,5`, `1.234,5` vengono trattati coerentemente con la convenzione definita da TASK-023 nei test mirati | B + S | ESEGUITO |
| 4 | Nessuna regressione sui casi già coperti di summary/history/manual entry | B + S | ESEGUITO |
| 5 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM rilevanti verdi | B + S | ESEGUITO |
| 6 | Nessuna duplicazione di logica di parsing numerico nei summary: solo delega alle API pubbliche condivise | S | ESEGUITO |
| 7 | Nessun peggioramento percepibile di UX/UI: i summary restano coerenti, stabili e leggibili con lo stile esistente | M | ESEGUITO |
| 8 | Nessuna modifica non necessaria a firme pubbliche, contract dei ViewModel o flussi esterni: il fix resta **interno** al perimetro summary / parsing e a **basso impatto** | B + S | ESEGUITO |
| 9 | Esiste almeno un set minimo di test “golden” che documenta in modo leggibile gli output attesi dei parser CL nei rami principali dei summary, così da rendere il fix facilmente revisionabile | S | ESEGUITO |
| 10 | I test aggiunti sono deterministici e non dipendono da locale di sistema, formatter impliciti o assert fragili su `Double` | S | ESEGUITO |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task stretto e localizzato | Evitare di riaprire inutilmente l’intero perimetro TASK-023 | 2026-04-03 |
| 2 | Parser condivisi come unica fonte di verità | Evitare nuova divergenza futura | 2026-04-03 |
| 3 | Summary ExcelViewModel → solo API pubbliche `ClNumberFormatters` elencate sotto | Allineamento esplicito a TASK-023 senza duplicare `parseNumericInput` | 2026-04-03 |
| 4 | Nessun helper nuovo salvo necessità documentata | Ridurre superficie e vincolare l’uso ai soli summary | 2026-04-03 |
| 5 | Ottimizzare per delega semplice, non per refactor esteso | Ridurre rischio, diff e costo di review su task localizzato | 2026-04-03 |
| 6 | Privilegiare UX coerente e prevedibile in caso di scelta | Totali più affidabili devono risultare anche più comprensibili all’utente | 2026-04-03 |
| 7 | Evitare cambi a firme / metodi pubblici salvo necessità strettamente tecnica **documentata** | Minimizzare impatto, review e rischio di regressione **fuori** dal perimetro del task | 2026-04-03 |
| 8 | Usare casi “golden” leggibili come oracolo di review nei test summary | Rendere immediata la verifica del fix senza reintrodurre ambiguità interpretative su input CL | 2026-04-03 |
| 9 | Preferire test deterministici e locale-agnostic con assert robusti | Evitare flaky test o falsi rossi/positivi dovuti a differenze di ambiente e rappresentazione floating point | 2026-04-03 |

---

## Planning (Claude)

### 1. Contesto e obiettivo operativo

L’audit (TASK-023) ha uniformato la **visualizzazione**; i **summary** in `ExcelViewModel` restano su `replace(",", ".").toDoubleOrNull()`, che interpreta male convenzioni CL (es. `1.234` come migliaia). Obiettivo: **stessa semantica di parse** già usata in griglia/input CL, **solo** nei percorsi `calculateInitialSummary` / `calculateFinalSummary`, senza cambiare policy numerica né formatting display definiti altrove.

**Vincoli globali (inviolabili in planning e in futura execution):**

- Focus **solo** sul parsing condiviso CL **nei summary**; nessun refactor laterale o allargamento di scope.
- **Nessuna** nuova API pubblica **non necessaria** (allineamento a §2.3).
- **Nessun** cambio implicito a **rounding**, **precedenze** delle operazioni aritmetiche nei summary, **criteri di completezza riga** (`complete` / missing), né **priorità dei rami decisionali** in `calculateFinalSummary` (vedi §3.1): solo sostituzione della sorgente del valore numerico parsato in ingresso.
- UX **prevedibile** e UI **coerente** con lo stile esistente (vedi §6); eventuali ritocchi UI/UX **solo minimi** e coerenti con Material3 / pattern già in uso; nessun nuovo formato visivo o stato di presentazione per “compensare” il parse.
- Fase **`EXECUTION`** aperta dopo validazione planning vs codice (vedi sezione **Execution**); **nessuna** modifica al codice di produzione è stata eseguita nello step di sola validazione documentale.

---

### 2. Decisioni tecniche operative

#### 2.1 Parser / policy CL — fonte unica per i summary

**File:** `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt`  
**Implementazione sottostante:** `parseNumericInput` (privata; **non** replicare la logica altrove).

Nei summary di `ExcelViewModel` si usano **esclusivamente** queste API pubbliche, già allineate alla policy CL (TASK-023):

| Campo concettuale | API da usare | Nota |
|-------------------|--------------|------|
| `purchasePrice`, `discountedPrice` | `parseUserPriceInput(String?)` | `allowGroupedIntegerPattern = true` (migliaia `.`, decimale `,`) |
| `quantity` (file o stringa effettiva da editable) | `parseUserQuantityInput(String?)` | stessa policy prezzo/gruppi per quantità |
| `discount` (percentuale) | `parseUserNumericInput(String?)` | Header colonna nel file: **`"discount"`** (variabile Kotlin locale: `discountPercent`); pattern “grouped integer” **non** applicato — coerente con `ClNumberFormattersTest` (`parseUserNumericInput("1.234")` → decimale `1.234`, **diverso** da prezzo/quantità dove `1.234` = migliaia). |

**Divieto esplicito:** nei due metodi summary **non** usare parse locale ad hoc (`replace(",", ".")`, `toDoubleOrNull()` diretto su stringhe grezze, regex proprie, `String.format` per normalizzare input numerici).

#### 2.2 Regole su input (nessuna regola nuova locale)

Tutti gli input stringa passano dalla policy già centralizzata:

- **`null` / stringa vuota / solo spazi:** dopo `trim` e rimozione spazi interni come in `parseNumericInput`, valore non numerico → `null`; nel ViewModel il fallback numerico resta **`?: 0.0`** dove già presente (stesso comportamento “non contribuisce” dei parse falliti).
- **Separatori misti / ordine `.` vs `,`:** risolti solo dalla logica esistente in `parseNumericInput` (inclusi `1.234`, `1,5`, `1.234,5`, più punti/virgole secondo i rami già definiti).
- **Segno `+` / `-`:** accettati se coerenti con `toDoubleOrNull` sul normalizzato (nessuna estensione locale).
- **Token non numerici / stringhe tipo `abc`:** `null` → `0.0` nel summary come oggi per parse fallito; **non** introdurre messaggi d’errore o eccezioni solo per il summary.
- **Simboli estranei (es. `$`, lettere miste):** se il normalizzato non è un `Double` valido → `null`; stesso fallback. Nessuna strip ad hoc di currency nel task (fuori scope se non già nella policy condivisa).
- **Prestazioni / efficienza:** nei summary evitare parse ripetuto inutile della stessa cella nello stesso ramo di calcolo; è ammessa una normalizzazione locale minima del flusso (es. valorizzare una variabile parsed una sola volta per campo/iterazione) **solo** se non reintroduce logica numerica duplicata e mantiene il codice più leggibile.

Se in sede di implementazione emergesse duplicazione rumorosa di chiamate, è ammesso **al massimo** un **helper minimo** in `ClNumberFormatters.kt` (o package util) che:

- delega **solo** a `parseUserPriceInput` / `parseUserQuantityInput` / `parseUserNumericInput`;
- è documentato come **riservato ai summary ExcelViewModel** (KDoc + riferimento a TASK-027);
- **non** aggiunge branch o regole non già presenti nelle tre API sopra.

#### 2.3 Vincoli di superficie del fix

- **Firme e contract:** non cambiare firme pubbliche di `ExcelViewModel`, DTO, modelli o contract usati da altre aree, salvo necessità strettamente tecnica **documentata** nel task (motivo + alternative scartate).
- **UI / presentazione:** non introdurre nuovi stati UI, flag o branch di presentazione per “compensare” il parsing corretto; il fix è **logico**, non di orchestrazione schermata.
- **Helper minimo:** se introdotto, preferire `internal` al package o visibilità limitata; **non** esporlo come nuova API pubblica dell’app se non indispensabile (obiettivo: diff piccolo, reversibile, facilmente revisionabile).
- **Obiettivo complessivo:** fix **interno**, **locale** e **reversibile**, con diff minimo e perimetro chiaro.

#### 2.4 Semantica dei fallback (fallback semantics)

- Dove il codice usa già **`?: 0.0`** (o equivalente) dopo un parse che restituisce `null`, il **fallback resta quello attuale**: valore nullo / non parsabile → contributo numerico **zero** come oggi, senza introdurre eccezioni o messaggi solo per il summary.
- Il fallback **non** deve essere interpretato come cambio di **significato della riga** (es. “riga vuota” vs “riga con dati corrotti”), né introdurre **nuova semantica di aggregazione** (conteggi `totalItems`, pesi sul totale, regole di inclusione righe): restano invariati salvo quanto già determinato dalla logica esistente **prima** del solo cambio parser.
- In sintesi: si corregge **solo** il valore `Double?` ottenuto dalla stringa; **non** si ridefinisce cosa significhi “riga valida” o come si cumulano le righe oltre a quanto già nel codice.

---

### 3. Punti di integrazione in `ExcelViewModel` (mappa)

| Metodo | Righe (indicative) | Sostituzione |
|--------|-------------------|--------------|
| `calculateInitialSummary` | quantità (`quantityIndex`); prezzo acquisto (`purchasePriceIndex`) | `parseUserQuantityInput` / `parseUserPriceInput` al posto di `replace(...).toDoubleOrNull()` |
| `calculateFinalSummary` | `quantityToUseStr`; `purchasePrice`; `discountedPrice`; valore colonna **`discount`** (variabile `discountPercent`) | quantità → `parseUserQuantityInput`; prezzi → `parseUserPriceInput`; sconto % → `parseUserNumericInput` |

Nessun altro metodo è obbligatoriamente nel perimetro; **non** toccare `saveExcelFileInternal` / `numericTypes` salvo evidenza di bug fuori scope (da task separato).

Vincolo di implementazione: preferire sostituzioni puntuali e locali nei due metodi, con eventuale caching locale del valore parsato per riga/campo quando migliora chiarezza ed evita chiamate duplicate; evitare invece refactor laterali o spostamenti architetturali non necessari al fix.

#### 3.1 Ordine decisionale in `calculateFinalSummary` (invariato)

Nel codice attuale, il prezzo effettivo di pagamento per riga (`finalPaymentPrice`) segue un `when` con questa **priorità fissa** (verificare su `ExcelViewModel.kt` al momento dell’implementazione se le righe fossero cambiate):

1. **`discountedPrice`** parsato: se **non** `null` → si usa quel valore (ramo scontato esplicito).
2. Altrimenti **`discount`** (percentuale) parsato: se **non** `null` → `purchasePrice * (1 - discount/100)`.
3. Altrimenti → **`purchasePrice`** parsato (nessuno sconto applicabile dai campi precedenti).

**Regola TASK-027:** il task **corregge solo** i valori in ingresso (`parseUserPriceInput` / `parseUserNumericInput` / `parseUserQuantityInput`) che alimentano questi rami; **non** va modificato l’**ordine** dei rami, le condizioni strutturali del `when`, né la formula del ramo percentuale. Se un input oggi mal parsato “finiva” nel ramo sbagliato, l’allineamento CL può far **entrare** la riga nel ramo corretto rispetto alla policy — questo è effetto del parse corretto, **non** un redesign della priorità.

**Allineamento codice verificato (2026-04-03):** in `ExcelViewModel.kt` il `when` su `finalPaymentPrice` corrisponde all’ordine sopra (circa righe 820–824); `discountIndex = header.indexOf("discount")`.

#### 3.2 Due semantiche di `missingItems` e punto di attenzione `buildHistoryEntry`

Nel codice attuale **non** esiste un solo significato di `missingItems` per tutti i percorsi:

| Percorso | Origine | Formula / assegnazione |
|----------|---------|-------------------------|
| **Inserimento history** (`buildHistoryEntry`) | Dopo `calculateInitialSummary` | `missingItems = initialTotalItems` (stesso valore del conteggio `totalItems` prodotto dall’initial summary — **non** è `totalDataRows - completedItems`). |
| **Aggiornamento history** (`updateHistoryEntry`, `saveCurrentStateToHistory`) | Dopo `calculateFinalSummary` | `missingItems = totalDataRows - completedItems` con `completedItems` che conta **ogni** riga con `complete == true` (incremento **prima** del controllo `quantity > 0`). |

Il task tocca **solo** i parse in `calculateInitialSummary` / `calculateFinalSummary`; **non** ridefinire l’intento prodotto di `buildHistoryEntry` (es. non “correggere” `missingItems = initialTotalItems` salvo task separato). I test devono sapere **quale** percorso stanno coprendo quando asseriscono su `missingItems`.

#### 3.3 Invarianti già presenti (coerenza con il planning)

- **`totalItems` (initial):** incrementato solo se `quantity > 0` dopo il parse.
- **`paymentTotal` (final):** accumulato solo se riga completa **e** `quantity > 0`.
- **`completedItems` (final):** incrementato per **ogni** riga con `complete == true`, anche se `quantity` è 0 dopo parse (nessun contributo a `paymentTotal` ma la riga conta nel denominatore del missing del percorso final).

---

### 4. Piano test dettagliato (da applicare in EXECUTION)

**File primario atteso:** `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` (estendere o aggiungere casi mirati).  
**Riferimento policy:** `ClNumberFormattersTest.kt` per comportamento isolato dei parser; i test ViewModel verificano **combinazione** righe + header.

#### 4.0 Metodi summary `private` — strategia di test **senza** cambiare visibilità

In `ExcelViewModel.kt`, `calculateInitialSummary` e `calculateFinalSummary` sono **`private`**: **non** vanno resi pubblici né `@VisibleForTesting` salvo **deviazione documentata** (sconsigliata: conflitto con vincoli di superficie §2.3).

Strategia realistico-attuale:

- **`calculateFinalSummary` →** verificare **`paymentTotal`** e **`missingItems`** tramite API pubbliche che li persistono, es. **`updateHistoryEntry(entryUid)`** con `coEvery { repository.getHistoryEntryByUid(...) }` e **`slot<HistoryEntry>()`** su `updateHistoryEntry` (pattern già presente nel file di test, es. `updateHistoryEntry writes edited state and summary to repository`).
- **`calculateInitialSummary` →** verificare **`orderTotal`**, **`paymentTotal`**, **`totalItems`**, **`missingItems`** sull’`HistoryEntry` inserito in coda a flussi che chiamano **`buildHistoryEntry`** (es. **`generateFilteredWithOldPrices`** con `insertHistoryEntry` in capture — estendere assert sullo slot oltre ai campi già controllati se necessario).

In entrambi i casi: **nessun** test che richieda reflection su metodi privati salvo decisione esplicita fuori perimetro standard.

#### 4.1 Matrice minima stringa (stessi literal per entrambe le funzioni dove applicabile)

| Input stringa | Uso |
|---------------|-----|
| `"1234"` | intero semplice |
| `"1.234"` | migliaia CL |
| `"1,5"` | decimale CL |
| `"1.234,5"` | migliaia + decimali CL |
| `""` | blank |
| `null` | assenza cella / null safety |
| `" "` | solo spazi |
| `"abc"` | non numerico |
| `"+1,5"` / `"-1,5"` (o equivalente valido dopo policy) | segno |
| `"  1,5  "` | trim laterale |

#### 4.2 Ambito per tipo di summary (stessi input da 4.1 dove ha senso)

| Ambito | Cosa testare | Note |
|--------|----------------|------|
| **Quantità** | `calculateInitialSummary` e `calculateFinalSummary` con sole variazioni su colonna `quantity` (e su `realQuantity` editable in final se il test lo simula) | Verifica conteggio `totalItems` / contributo al totale e ramo `quantity > 0` |
| **Totali ordine (initial)** | `orderTotal` = Σ `parseUserPriceInput(purchasePrice) * parseUserQuantityInput(quantity)` | Casi 4.1 su prezzo e quantità |
| **Totali pagamento (final)** | `paymentTotal` con ramo `discountedPrice` / `discount` / solo `purchasePrice` | Applicare matrice sui campi rilevanti; percentuale con `parseUserNumericInput` |
| **Missing** | Due casi: (1) percorso **final** → `missingItems = totalDataRows - completedItems`; (2) percorso **insert** (`buildHistoryEntry`) → `missingItems = initialTotalItems`. Il parse non deve alterare `completed` / `totalDataRows`; può cambiare `totalItems` initial e quindi **`missingItems` su insert** se il conteggio righe valide dipende dal parse della quantità. | Evitare di mescolare le due semantiche nello stesso assert senza chiarimento. |

#### 4.3 Distinzione obbligatoria nei test (tag / commento / nome test)

Per ogni caso significativo indicare esplicitamente:

- **(a) Comportamento già corretto con parser condiviso:** risultato atteso **invariato** rispetto alla semantica CL; il test protegge da regressioni.
- **(b) Comportamento oggi errato con parse ad hoc:** risultato atteso **nuovo** dopo il fix; nel messaggio di commit / log Execution (futuro) va documentato come **fix intenzionale di allineamento a TASK-023**, non come regressione.

**Tracciabilità dei fix intenzionali (test strategy + review):**

- Quando un **expected** cambia **solo** perché il parse CL è corretto (stesso input stringa, numero diverso da prima), il caso va marcato in modo esplicito come **`expected correction`** / **fix intenzionale** (nome test, `@DisplayName`, commento `// EXPECTED_CORRECTION`, o tag equivalente nel file di test — scegliere una convenzione unica nel PR).
- In **review** il revisore deve poter distinguere **a colpo d’occhio**: **correzione voluta** (allineamento a TASK-023) vs **regressione collaterale** (cambiamento non spiegato o fuori perimetro). I casi (b) non vanno “mescolati” senza etichetta ai casi (a).

#### 4.4 Casi di efficienza / stabilità da verificare

- Nessuna differenza di comportamento tra righe complete e righe con campi vuoti quando il parse fallisce: il summary deve degradare in modo stabile e prevedibile.
- Nessuna introduzione di branching UI-specifico dentro `ExcelViewModel`: il fix resta logico, ma con output più coerente per la UI esistente.
- Se il codice viene reso più efficiente tramite variabili locali parsed, i test devono continuare a validare gli stessi risultati finali, non l’implementazione interna.


#### 4.5 Guardrail strutturali / di integrazione

- I test **non** devono richiedere adattamenti a **call site esterni** (altre schermate, repository, navigation) solo per far passare il task: la correttezza va verificata lato summary / ViewModel nei limiti attuali.
- **Helper minimo:** se è pura delega alle tre API CL, basta **copertura indiretta** via test sui summary; test dedicato sull’helper solo se contiene **branching reale** o logica non banale.
- Nessun test deve validare **workaround UI**, stati di presentazione o flag grafici: il task resta confinato alla **correttezza logica** dei summary e alla delega ai parser condivisi.

#### 4.6 Casi “golden” consigliati per review rapida

Aggiungere almeno un piccolo gruppo di test leggibili, pensati anche come **oracolo di review**, con assert espliciti su output finali dei summary nei casi CL più sensibili.

**Review ergonomics (naming):** i test golden dovrebbero avere **naming molto leggibile** e **orientato al comportamento** (es. input chiave + outcome atteso in linguaggio naturale abbreviato), così la review resta **veloce**, il diff è **auto-esplicativo**, e le manutenzioni future capiscono il **perché** dell’assert senza aprire il codice prodotto.

Casi minimi consigliati:

- `purchasePrice = "1.234"`, `quantity = "2"` → `orderTotal = 2468.0` nel ramo initial.
- `purchasePrice = "1.234,5"`, `quantity = "2"` → `orderTotal = 2469.0` nel ramo initial.
- `purchasePrice = "1000"`, colonna **`discount` = `"10,5"`**, nessun `discountedPrice` → verificare `paymentTotal` nel ramo percentuale con parse CL coerente (`parseUserNumericInput`).
- `discountedPrice = "1.234"` con quantità valida → verificare che il ramo `discountedPrice` resti prioritario ma con valore parsato correttamente.
- almeno un caso con input invalido (`"abc"` o blank) che dimostri fallback stabile a `0.0` senza effetti collaterali su UI o contract.

Questi test non sostituiscono la matrice completa: servono a rendere il comportamento corretto **immediatamente verificabile** in code review.

#### 4.7 Determinismo dei test / robustezza assert

- I test dei summary devono essere **locale-agnostic**: nessun affidamento a `Locale.getDefault()`, formatter impliciti o parsing/formatting di supporto che possano cambiare tra macchina locale e CI.
- Nei casi con risultato intero o rappresentazione esatta attesa (`2468.0`, `2469.0`), usare assert diretti e leggibili.
- Nei casi con percentuali o operazioni che possono introdurre floating point non esatto, usare una tolleranza esplicita e minima, documentata nel test, senza nascondere errori di semantica.
- Evitare test che validano stringhe formattate quando il task riguarda il valore numerico del summary: verificare prima il dato logico, non il display.

---

### 5. Non-regressione (oltre ai test)

| Area | Controllo |
|------|-----------|
| **History** | Entry che riaprono griglia/generated: totali summary e coerenza con dati salvati; nessun nuovo crash su celle vuote. |
| **Manual entry** | Flusso `createManualEntry` → stessi summary; input tipici utente CL. |
| **Generated** | Righe complete / incomplete: `paymentTotal` e missing allineati; nessun cambio flusso export. |
| **UI / copy / formatting display** | **Non** modificare stringhe utente, formatter display (`formatCl*`), né layout, salvo **ritocchi minimi** di coerenza visiva con Material3 già in uso. Se emergono micro-migliorie UX/UI, devono restare decorative o di chiarezza percettiva, senza cambiare semantica, navigazione o flussi. |
| **Test JVM** | Qualsiasi aggiornamento dell’assert su totali va **motivato** nel log come allineamento alla policy CL (fix intenzionale), mai come “abbassamento” del test senza spiegazione. |
| **Superficie API / call site** | Completare TASK-027 **non** deve richiedere, in linea di principio, modifiche a call site, firme pubbliche o contract esterni. Se in analisi pre- o durante execution emergesse il contrario, documentarlo come **deviazione** da riesaminare (go/no-go) **prima** di procedere oltre. |
| **Reviewability** | Il diff finale deve restare abbastanza piccolo e leggibile da permettere di capire a colpo d’occhio: parser usato, rami coperti e differenze attese nei casi CL ambigui. |
| **Tracciabilità expected** | Ogni cambio di assert classificato come **expected correction** (§4.3) deve essere riconoscibile in review senza ambiguità rispetto a una regressione accidentale. |
| **Determinismo test** | I nuovi test non devono dipendere da locale macchina, formatter impliciti o confronti floating point fragili; stesso risultato atteso su ambiente locale e CI. |

---

### 6. UX / UI (nota breve, task prevalentemente logica)

- Mantenere la **presentazione numerica** già adottata nell’app (TASK-023): nessun nuovo pattern visivo (migliaia/decimali) introdotto solo per i summary.
- Se i totali calcolati diventano **più accurati**, l’utente deve percepire **maggiore coerenza** con quanto vede in griglia/dialoghi, non importi “a sorpresa” senza cambio dati.
- Eventuali piccoli ritocchi UI sono ammessi **solo** per allineamento estetico allo stile esistente, miglioramento della leggibilità o riduzione dell’ambiguità visiva.
- In caso di scelta tra più opzioni equivalenti, privilegiare quella con UX più prevedibile, meno rumore visivo e maggiore coerenza con il resto dell’applicazione.

---

### 7. Rischi identificati

- Aggiornamenti agli assert dei test esistenti: trattarli come **fix intenzionale** documentato, non come regressione silenziosa.
- Effetti su edge case rari (file Excel con formati strani): mitigazione = matrice 4.1–4.2 + esecuzione `./gradlew test` mirato / completo come da `AGENTS.md`.
- Over-engineering su task localizzato: mitigazione = mantenere il fix stretto, leggibile e facilmente revisionabile, evitando astrazioni premature.
- Allargare la superficie del fix con **API o helper pubblici non necessari**: mitigazione = preferire modifiche **interne**, **locali** e **non invasive** (allineamento a §2.3).
- Test troppo astratti o poco leggibili: mitigazione = aggiungere un nucleo di casi “golden” con output finali immediatamente verificabili in review, oltre alla matrice completa.
- Test non deterministici o sensibili all’ambiente (locale / floating point): mitigazione = assert locale-agnostic, verifica del dato logico e tolleranza esplicita solo dove necessaria.
- Confondere le due semantiche di **`missingItems`** (insert vs update) o il fatto che **`completedItems`** conti anche righe con qty 0: mitigazione = §3.2–§3.3 e test nominati in modo esplicito sul percorso coperto.

---

### 8. Checklist finale di planning (pre-EXECUTION — stato atteso)

- [x] Parser unico identificato: `parseUserPriceInput`, `parseUserQuantityInput`, `parseUserNumericInput` (`ClNumberFormatters.kt`).
- [x] Punti di integrazione in `ExcelViewModel` mappati (`calculateInitialSummary`, `calculateFinalSummary`).
- [x] Casi test minimi elencati (matrice + separazione quantità / order / payment / missing).
- [x] Impatti e rischi documentati (non-regressione history / manual / generated; vincoli UI).
- [x] Vincoli di efficienza e semplicità esplicitati (niente duplicazione logica, niente refactor superfluo).
- [x] Superficie del fix **minimizzata**: nessuna nuova API pubblica non necessaria; nessun impatto richiesto sui call site esterni (allineamento a criterio 8 e §5).
- [x] Presenza prevista di test “golden” leggibili per rendere il fix facile da verificare in review, oltre alla matrice completa.
- [x] Strategia test deterministica definita (locale-agnostic, assert robusti su `Double`, focus sul dato logico prima del display).
- [x] Vincoli globali §1 rispettati in planning (solo parse CL nei summary; nessun cambio implicito a rounding / precedenze aritmetiche / completezza riga / **ordine rami** `calculateFinalSummary`; nessuna nuova API pubblica non necessaria; UI/UX solo ritocchi minimi coerenti).
- [x] Semantica fallback §2.4 e ordine decisionale §3.1 documentati (nessuna nuova semantica di aggregazione; solo valori parsati corretti).
- [x] Strategia tracciabilità §4.3 (expected correction vs regressione) e naming test §4.6 concordate per la futura review.
- [x] Validazione planning vs codice: §3.2 (due `missingItems`), §3.3 (invarianti), §4.0 (test senza esporre metodi privati), ordine rami e header `"discount"` verificati su sorgente — **nessun blocker**.
- [x] Stato **`EXECUTION`** aperto; **implementazione codice di produzione non ancora eseguita** nello step di sola revisione documentale (vedi sezione **Execution**).

---

### 9. Piano di esecuzione (fase EXECUTION — implementazione)

1. Sostituire i parse ad hoc nei due metodi summary con le tre API CL, mantenendo modifiche locali e nessuna logica numerica duplicata.
2. Dove utile, ridurre parse ripetuti della stessa cella tramite variabili locali parsed, senza alterare la semantica né introdurre helper non necessari.
3. Implementare i test secondo la matrice §4, con etichette (a)/(b) per invariato vs corretto intenzionalmente.
4. Eseguire `./gradlew assembleDebug`, `./gradlew lint`, test JVM rilevanti (e baseline TASK-004 se toccato `ExcelViewModel` in modo sostanziale — già coperto da suite esistente).
5. Smoke ragionato su history / manual entry / generated, verificando anche coerenza percettiva del summary rispetto alla UI esistente.

---

## Execution

### Apertura fase EXECUTION — 2026-04-03

**Contesto:** validazione mirata del planning contro i soli file di perimetro (`ExcelViewModel.kt`, `ClNumberFormatters.kt`, test collegati). **Nessuna modifica al codice di produzione** in questo step.

**Esiti verifica (coerenza con il codice attuale):**

| Controllo | Esito |
|-----------|--------|
| API `parseUserPriceInput` / `parseUserQuantityInput` / `parseUserNumericInput` in `ClNumberFormatters.kt` | Presenti; delegano a `parseNumericInput` con flag grouped dove previsto. |
| Parse ad hoc nei summary | Ancora `replace(",", ".").toDoubleOrNull()` in `calculateInitialSummary` e `calculateFinalSummary` (circa righe 769–774, 811–818). |
| Header colonna sconto | `header.indexOf("discount")` — nome colonna **`"discount"`**; variabile locale `discountPercent`. |
| Ordine rami `finalPaymentPrice` | `discountedPrice != null` → `discountPercent != null` (ramo %) → `else` `purchasePrice` (come §3.1). |
| `buildHistoryEntry` | `missingItems = initialTotalItems` (non la formula di `calculateFinalSummary`) — documentato in §3.2. |
| `calculateFinalSummary` missing | `totalDataRows - completedItems`; `completedItems++` per ogni `complete == true` prima del check quantità — §3.3. |
| Test ViewModel | Metodi summary **private**; pattern esistente: `updateHistoryEntry` + slot, `generateFilteredWithOldPrices` + insert — allineato §4.0. |
| `ClNumberFormattersTest` | Conferma semantica diversa `parseUserNumericInput("1.234")` vs price/qty — coerente con uso colonna `discount`. |

**Blocker:** nessuno.

**Prossimo passo operativo per l’esecutore:** implementare §9 (sostituzione parse nei due metodi + test secondo §4), check `AGENTS.md` e baseline TASK-004 dove applicabile.

### Esecuzione — implementazione codice 2026-04-03

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — delega parse summary a `parseUserQuantityInput` / `parseUserPriceInput` / `parseUserNumericInput`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — test golden / EXPECTED_CORRECTION via `updateHistoryEntry` e `generateFilteredWithOldPrices`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt` — copertura parser CL espansa per grouped decimal e quantity decimal.
- `docs/TASKS/TASK-027-allineamento-parser-summary-numerici-cl.md` — log Execution/Review/Fix/Chiusura aggiornato con evidenze reali.

**Azioni eseguite:**
1. `calculateInitialSummary`: sostituito parse ad hoc con `parseUserQuantityInput` e `parseUserPriceInput`.
2. `calculateFinalSummary`: stessa coppia per quantità e prezzi; `parseUserNumericInput` per colonna `discount`; invariato ordine `when` su `finalPaymentPrice`.
3. Aggiunti test con commenti `EXPECTED_CORRECTION` dove il totale atteso cambia rispetto al vecchio parse (es. `"1.234"` = migliaia CL).
4. In review sono emersi gap di copertura sui casi `1,5`, `1.234,5`, ramo `discountedPrice` grouped e fallback invalido; corretti con micro-fix **solo test**, senza riaprire la logica di produzione.
5. `ClNumberFormatters.kt` non modificato (nessun helper aggiuntivo, nessuna nuova API pubblica).

**Check obbligatori (AGENTS.md):**

| Check | Stato | Note |
|-------|-------|------|
| Build `./gradlew assembleDebug` | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` verde |
| Lint `./gradlew lint` | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` verde; report `0 errors, 63 warnings` preesistenti, nessun warning sui file di perimetro |
| Test JVM rilevanti | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest' --tests 'com.example.merchandisecontrolsplitview.util.ClNumberFormattersTest'` verde |
| Baseline TASK-004 | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest` verde; `ExcelViewModel` ricade nella baseline JVM/Robolectric |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `ExcelViewModelTest`, `ClNumberFormattersTest`, poi suite completa `testDebugUnitTest`
- Test aggiunti/aggiornati: `ExcelViewModelTest` (quantity `1,5`, grouped decimal `1.234,5`, ramo `discountedPrice` grouped, fallback invalido), `ClNumberFormattersTest` (grouped decimal price/quantity)
- Limiti residui: nessuno nel perimetro logico del task

**Incertezze:** nessuna

---

## Review

### Review — 2026-04-03

**Esito rispetto al Plan:** APPROVATA con micro-fix test applicati in review.

**Verifiche repo-grounded:**
- `calculateInitialSummary` usa realmente `parseUserQuantityInput` e `parseUserPriceInput`.
- `calculateFinalSummary` usa realmente `parseUserQuantityInput`, `parseUserPriceInput` e `parseUserNumericInput` per `discount`.
- Nei due summary non restano parse ad hoc `replace(",", ".").toDoubleOrNull()`.
- Rounding, aggregazione, semantica `totalItems`, `missingItems`, `completedItems` e ordine del `when` su `finalPaymentPrice` risultano invariati.
- Nessuna nuova API pubblica, nessun refactor laterale, nessun impatto UI fuori perimetro.

**Problemi trovati in review:**
1. La logica di produzione era corretta, ma la copertura test non documentava ancora in modo sufficiente tutti i casi CL sensibili citati dal Plan (`1,5`, `1.234,5`, ramo `discountedPrice` grouped, fallback invalido).
2. Un test parlava di “CL grouped price” pur usando `500`, rendendo meno reviewable il ramo realmente coperto.

**Esito finale review:** dopo i fix test-only sotto, il task soddisfa il Plan validato, resta locale, leggibile e senza deviazioni di scope.

---

## Fix

### Fix — 2026-04-03

**Correzioni applicate durante la review:**
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt`
  - aggiunto caso golden su quantità CL `1,5` nel percorso `updateHistoryEntry`
  - aggiunto caso `EXPECTED_CORRECTION` su `1.234,5` nel summary initial
  - aggiunto caso `EXPECTED_CORRECTION` su `discountedPrice = "1.234"` che deve vincere sul ramo percentuale
  - aggiunto caso fallback invalido a `0.0` senza cambiare la semantica `missingItems` del percorso final
  - rinominato il test sul ramo `discountedPrice` semplice per allinearlo al comportamento realmente coperto
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt`
  - aggiunti casi mirati per `parseUserPriceInput("1.234,5")`
  - aggiunti casi mirati per `parseUserQuantityInput("1,5")` e `parseUserQuantityInput("1.234,5")`

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | DONE     |
| Data chiusura          | 2026-04-03 |
| Tutti i criteri ✅?    | Sì, 10/10 |
| Rischi residui         | Nessun blocker nel perimetro; restano solo warning lint globali preesistenti fuori scope |

---

## Riepilogo finale

- Produzione allineata al Plan senza scope creep: summary su parser CL condivisi, nessun cambio a semantica o API esterne.
- Review completata direttamente sui file; unici fix necessari applicati ai test per chiudere i casi CL esplicitamente richiesti dal task.
- Verifiche reali eseguite con `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`: `assembleDebug`, `lint`, `testDebugUnitTest` tutti verdi.

---

## Handoff

- Nuovo follow-up da audit 2026-04-03.
- Alto rapporto impatto/costo: bug user-visible ma confinato.
- Task chiuso in `DONE` dopo review completa repo-grounded e micro-fix test-only coerenti col Plan.
