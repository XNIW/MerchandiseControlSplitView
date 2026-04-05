# TASK-032 — ManualEntryDialog: layout responsivo prezzi

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-032                                    |
| Stato                | DONE                                        |
| Priorità             | ALTA                                        |
| Area                 | UX / UI / GeneratedScreen                   |
| Creato               | 2026-04-04                                  |
| Ultimo aggiornamento | 2026-04-05 (chiusura documentale)           |

---

## Dipendenze

- Nessuna.

---

## Scopo

Rendere il blocco **prezzo acquisto / prezzo vendita / quantità** in `ManualEntryDialog` **leggibile e usabile su schermi stretti** (tipicamente smartphone in portrait), dove tre `OutlinedTextField` affiancati in `Row` con `weight(1f)` risultano compressi.

Intervento **solo layout Compose** nel perimetro del dialog: passaggio a **stack verticale** sotto una soglia di larghezza **unica e non ambigua**, mantenendo **ordine dei campi**, **focus/IME**, **validazione** e **CTA** invariati a livello funzionale.

Sono esplicitamente **ammessi micro-ritocchi UI/UX locali** nel blocco prezzi/quantità e nel suo intorno immediato (spacing, allineamento, leggibilità label, ritmo verticale con i campi adiacenti) se coerenti con Material3 e con il resto del dialog — vedi sezione **UX polish ammessi in Execution**.

**Stato:** implementazione completata in `GeneratedScreen.kt` (`ManualEntryPriceQuantityBlock`); task chiuso in **DONE** dopo verifica statica del codice, `assembleDebug` / `lint` verdi e review documentale (smoke manuale UI non eseguito in sessione — vedi criteri ⚠️ e Handoff).

---

## Contesto (repo-grounded)

- **Definizione:** `ManualEntryDialog` è un `@Composable` **top-level** in `GeneratedScreen.kt`, con `AlertDialog` e contenuto `text` = `Column` scrollabile (`verticalScroll`).
- **Problema originale (pre-TASK-032):** tra barcode e nome prodotto le tre `OutlinedTextField` (prezzo acquisto / vendita / quantità) erano in un’unica `Row` con `weight(1f)`, poco usabile su larghezze strette.
- **Stato attuale (post-TASK-032):** il blocco è estratto in `ManualEntryPriceQuantityBlock` nello stesso file: `BoxWithConstraints` locale, `Column` se `maxWidth < 400.dp`, altrimenti `Row` con tre `weight(1f)`; tre field definiti una volta tramite lambda `@Composable` condivise (`purchasePriceField`, `retailPriceField`, `quantityField`).
- **Dettaglio campi (invariati semanticamente):**
  - `header_purchase_price` (`purchasePrice`, `String`, normalizzazione on blur)
  - `header_retail_price` + `*` (`retailPrice`, `TextFieldValue`, `focusRequester` = `priceFocusRequester`, focus iniziale in `LaunchedEffect`)
  - `header_quantity` (`quantity`, `TextFieldValue`, `quantityFocusRequester`)
- In modalità larga (`maxWidth >= 400.dp`) ogni campo usa ancora `Modifier.weight(1f)` nella `Row`; in modalità stretta usano `fillMaxWidth()` nella `Column`.
- **Chiamate:** `ManualEntryDialog` è usato **solo** da `GeneratedScreen.kt` (stesso file); nessun altro modulo Kotlin referenzia il simbolo.
- **Coerenza governance:** TASK-014 ha già identificato `ManualEntryDialog` come ambito dove sono ammessi interventi **solo layout/spacing/scroll** senza toccare validazione o API verso `ExcelViewModel` / `DatabaseViewModel`. I **piccoli miglioramenti UI/UX intenzionali** restano tracciabili nel log **Execution** (`AGENTS.md`).

---

## Non incluso

- Modifiche a **business logic**, `ExcelViewModel`, `DatabaseViewModel`, DAO, repository, Room, `NavGraph`, navigation, import/export.
- Cambio di **semantica** dei campi (regole `basicValid` / `passChangesGate`, calcolo `newRowData`, lookup barcode, dropdown categoria).
- **Modalità responsive ibride** o eccezioni non documentate: per questo task esistono **solo** due layout (vedi **Regola responsive**); niente terzo layout, niente soglie multiple, niente comportamento dipendente da altezza/font scale dentro questo task.
- **Spostamento/extract** del dialog in altro file (es. `GeneratedScreenDialogs.kt`) — fuori perimetro salvo necessità tecnica emergente in Execution (default: **no**).
- Nuove **dipendenze** Gradle.
- Nuove **stringhe**, abbreviazioni label o cambio del significato visivo dei campi (incluso l’asterisco del prezzo vendita) salvo regressione direttamente causata dal layout e documentata in Execution con motivazione esplicita.
- Redesign dell’intero `AlertDialog` (titolo, pulsanti, card DB lookup, altre sezioni) oltre al **blocco delle tre text field prezzo/quantità**, ai **micro-polish** elencati in **UX polish ammessi in Execution** e al minimo di **spacing** adiacente strettamente necessario per coerenza visiva locale.
- **Refactor esteso** del file (spezzare `ManualEntryDialog` in più file, estrarre ViewModel, riorganizzare stati).
- **Seconda implementazione parallela** dei tre `OutlinedTextField` (copy-paste divergente tra `Row` e `Column`): vietato; vedi vincolo anti-duplicazione in Planning.
- **Breakpoint o policy aggiuntive** basate su **altezza**, **orientamento** o **font scale** (nessun `WindowSizeClass` aggiuntivo, nessuna soglia extra): restano **fuori scope** — il limite su **font scale elevato** è formalizzato sotto **Limiti residui espliciti**.
- Modifiche alla **sequenza di focus** o alla priorità del focus iniziale: il task preserva il focus iniziale su prezzo vendita e la navigazione IME esistente; eventuali miglioramenti di ergonomia focus/keyboard non direttamente necessari al layout restano fuori scope.
- Introduzione di **animazioni**, `AnimatedVisibility`, transizioni di layout o effetti visivi aggiuntivi per il cambio `Row`/`Column`: il task privilegia un comportamento statico, semplice e prevedibile.

---

## File potenzialmente coinvolti

### Da modificare (Execution)

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — solo il layout del blocco con le tre `OutlinedTextField` (prezzo acquisto / prezzo vendita / quantità) in `ManualEntryDialog`; wrapper `BoxWithConstraints` **locale** al blocco; eventuale `@Composable private` **minimo** nello stesso file per DRY.

### Solo lettura

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/theme/Theme.kt` (o dove è definito `appSpacing`) — coerenza token `spacing.*` già usati nel dialog.
- Stringhe esistenti in `app/src/main/res/values*/strings.xml` — **nessuna nuova chiave attesa** se le label restano invariate (testi di business invariati).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Regola responsive rigida:** misurata la `maxWidth` del contenitore (`BoxWithConstraints` sul blocco), se **`maxWidth < 400.dp`** il layout del blocco è **esclusivamente verticale** (`Column` o equivalente); se **`maxWidth >= 400.dp`** è **esclusivamente orizzontale** (`Row` con tre `weight(1f)` come oggi). **Nessuna** modalità intermedia (es. due campi su una riga e uno sotto), **nessuna** eccezione non documentata nel log Execution. | S / M | **ESEGUITO** (S: `ManualEntryPriceQuantityBlock`, `if (useVerticalLayout) Column else Row`, soglia `400.dp`) |
| 2 | Con layout verticale, ogni campo ha **larghezza piena** rispetto al contenitore (`fillMaxWidth()` o equivalente): su **phone stretto in portrait** nessuno dei tre campi deve risultare **visivamente compresso** o con area di tap/label **clamped** come nella baseline a tre colonne. | M | **⚠️ NON ESEGUIBILE** — smoke su device non eseguito in sessione; codice: `fillMaxWidth()` sui tre field nel ramo `Column`. |
| 3 | Le **label** (`OutlinedTextField` `label`) restano **leggibili** (nessun effetto «cramped» tipico di tre colonne strette); niente troncamento sistematico introdotto dal task; tipografia coerente con **Material3** e con gli altri field del dialog (stessi componenti, stessi token di tema salvo micro-spaziatura locale ammessa). | M | **⚠️ NON ESEGUIBILE** — verifica percettiva su device non eseguita; stessi `OutlinedTextField` e `stringResource` delle label. |
| 3bis | **Testi e affordance dei campi invariati:** nessuna abbreviazione o riscrittura delle label è introdotta dal task; l’asterisco del campo prezzo vendita resta presente e leggibile come nella baseline, salvo fix minimo obbligato da regressione introdotta dal layout e documentato nel log Execution. | S / M | **ESEGUITO** (S: label invariata nel codice `header_retail_price + "*"`) |
| 4 | Con **`maxWidth >= 400.dp`**, il layout orizzontale è **funzionalmente e visivamente equivalente** alla baseline attuale: stesso ordine **acquisto → vendita → quantità**, stessi `weight(1f)` e stesso `spacedBy` orizzontale salvo **micro-ritocco** spacing dichiarato in Execution se migliora l’armonia senza cambiare struttura. | S / M | **ESEGUITO** (S: `Row` + `weight(1f)` + `spacedBy(fieldSpacing)` con `fieldSpacing = appSpacing.sm`) |
| 5 | **Ordine visivo dei campi** invariato: **1) prezzo acquisto, 2) prezzo vendita, 3) quantità** (sinistra→destra in `Row`, alto→basso in `Column`). | S / M | **ESEGUITO** (S: ordine nelle lambda e nelle chiamate Column/Row) |
| 6 | **Focus, `FocusRequester` e IME:** invariati rispetto alla baseline — stessi `keyboardOptions` / `keyboardActions`, stessi `focusRequester` su vendita e quantità, stessi `LaunchedEffect` che impostano il focus iniziale; **nessun peggioramento** percepito (tastiera che copre il campo attivo in modo peggiore della baseline, focus perso, doppio tap necessario). Non è richiesto di «correggere» eventuali comportamenti IME preesistenti se non introdotti da questo diff. | M | **ESEGUITO** (S: stessi parametri IME/focus nel blocco estratto); percezione **⚠️** non verificata su device |
| 7 | **Normalizzazione on blur** (`normalizeClPriceInput` / `normalizeClQuantityInput` / `TextFieldValue` + `TextRange`) **invariata** per ogni campo. | S / M | **ESEGUITO** (S: `onFocusChanged` invariato per campo) |
| 8 | **CTA** (`confirm` / `add_and_next` / `delete` in edit): **non peggiori** rispetto alla baseline (overflow, testo tagliato, ordine pulsanti, affordance); eventuale fix **solo** se regressione **direttamente** causata dal nuovo layout del blocco prezzi (diff minimo). | M | **ESEGUITO** (S: `confirmButton` / `dismissButton` non modificati dal diff del blocco) |
| 8bis | **Nessuna regressione di scroll o altezza utile del dialog:** il nuovo blocco responsive non deve introdurre nested scroll, perdita di raggiungibilità dei pulsanti o peggioramento strutturale della fruizione del `AlertDialog`; il `verticalScroll` già esistente resta l’unico meccanismo di scorrimento previsto. | M | **ESEGUITO** (S: nessun `verticalScroll`/`Lazy` aggiunto nel blocco; solo `Column`/`Row` interni al `Column` scrollabile esistente) |
| 9 | Il blocco prezzi/quantità risulta **coerente** con lo stile del resto del dialog (Outlined, spacing token, nessun colore hardcoded `#` / `Color(0x…)` introdotto). | S | **ESEGUITO** |
| 9bis | **Responsive confinato al blocco:** la decisione `compact`/`non-compact` resta locale al solo blocco prezzi/quantità; il task non introduce stato/UI conditionale propagato ad altre sezioni del dialog. | S | **ESEGUITO** (`useVerticalLayout` solo in `ManualEntryPriceQuantityBlock`) |
| 10 | **Implementazione unica dei tre field:** non esistono due set divergenti di `OutlinedTextField` per `Row` vs `Column`; il codice riusa **una sola definizione** di ciascun campo (stessi parametri) variando **solo** il container (`Row`/`Column` + `Modifier`) o tramite **helper `@Composable private` nello stesso file** che incapsula i tre field **una volta** — da verificare in review statica (grep/lettura diff). | S | **ESEGUITO** |
| 10bis | **Messaggi di supporto/errore e ritmo verticale:** l’eventuale passaggio a layout verticale non deve introdurre overlap, compressioni o salti visivi incoerenti tra field, label, `supportingText` ed eventuali stati errore; lo spacing verso i campi adiacenti resta uniforme e coerente con i token del dialog. | M | **⚠️ NON ESEGUIBILE** — smoke visivo non eseguito; i tre field non hanno `supportingText`; spacing token `sm`. |
| 11 | Smoke documentati in Execution: **portrait stretto** (<400dp effettivi nel blocco), **larghezza ampia** (≥400dp), **tastiera aperta** durante edit, **stringhe lunghe** nei campi numerici — nessun crash, campi usabili, scroll del dialog ancora efficace. | M | **⚠️ NON ESEGUIBILE** — non eseguito su emulator/device in questa sessione. |
| 11bis | **Stabilità del layout durante edit:** il passaggio al layout verticale non deve introdurre rimbalzi visivi, resize incoerenti o salti percepibili del contenuto durante digitazione, focus o comparsa/scomparsa della tastiera oltre quanto già presente nella baseline del dialog. | M | **⚠️ NON ESEGUIBILE** — non verificato su device. |
| 12 | **Limiti residui:** se con **font scale di sistema molto elevato** la riga orizzontale (caso `maxWidth >= 400.dp`) resta faticosa, va documentato come **limite residuo noto e fuori scope** di TASK-032 — **senza** introdurre nuove soglie o layout in questo task. | S | **ESEGUITO** — limite ribadito in Handoff |
| 13 | `./gradlew assembleDebug` OK. | B | **ESEGUITO** — `BUILD SUCCESSFUL` (JDK: Android Studio JBR 21) |
| 14 | `./gradlew lint` OK (nessun warning nuovo attribuibile al task). | S | **ESEGUITO** — `lint` / `lintDebug` OK nello stesso run |
| 15 | **Baseline TASK-004:** non applicabile se il diff è **solo** layout/UI locale in `ManualEntryDialog` senza toccare ViewModel/repository/import; se in Execution si tocca logica o file coperti da TASK-004, eseguire e documentare i test JVM pertinenti. | S | **ESEGUITO** — N/A; solo `GeneratedScreen.kt` layout |

> Checklist **Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): miglioramento leggibilità nel perimetro; nessun cambio business non richiesto; build/lint OK. I micro-polish ammessi vanno **elencati e motivati** nel log Execution (`AGENTS.md`).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **Soluzione UX unica:** `BoxWithConstraints` sul blocco delle tre field; **`maxWidth < 400.dp` → solo `Column` + `fillMaxWidth()`**; **`maxWidth >= 400.dp` → solo `Row` + `weight(1f)`** come oggi. | Allinea il backlog; larghezza reale del contenuto dialog; due soli layout, nessun ibrido. | 2026-04-04 |
| 2 | **Vincolo assoluto:** nessuna terza variante layout, nessuna eccezione non scritta nel task/Execution. | Evita ambiguità in review e scope creep. | 2026-04-05 |
| 3 | Soglia numerica **400.dp** fissa (allineata al MASTER-PLAN). | Un solo breakpoint; tuning futuro = altro task se necessario. | 2026-04-04 |
| 3bis | **Scelta spacing predefinita:** salvo evidenza contraria in Execution, il planning preferisce riusare `spacing.sm` come baseline sia nel ramo orizzontale sia come primo candidato nel ramo verticale, evitando tuning arbitrari. | Riduce ambiguità e mantiene continuità visiva col dialog esistente. | 2026-04-05 |
| 4 | **Anti-duplicazione:** una sola «fonte di verità» per i tre `OutlinedTextField`, tramite **helper `@Composable private` nello stesso file** dedicato al blocco responsive. | Diff minimo, review più semplice, zero drift futuro tra i layout. | 2026-04-05 |
| 4bis | **Responsività locale, non architetturale:** la misura di `maxWidth` e la scelta `Row`/`Column` restano confinate al blocco prezzi/quantità, senza esporre flag o stato responsive al resto del dialog. | Mantiene il diff piccolo, evita accoppiamenti inutili e rende la review più lineare. | 2026-04-05 |
| 5 | **Gerarchia campi:** stesso ordine attuale (acquisto → vendita → quantità); vendita resta il campo con focus iniziale (`priceFocusRequester`). | Coerenza con flusso esistente. | 2026-04-04 |
| 6 | **Micro-polish:** ammessi solo quelli elencati in **UX polish ammessi in Execution**; tutto il resto è fuori scope. | Chiarezza tra miglioramento locale e redesign. | 2026-04-05 |
| 6bis | **Nessun intervento testuale previsto:** il planning privilegia la soluzione via layout/spacing; label e affordance testuali restano quelle esistenti, salvo regressione locale introdotta dal diff. | Mantiene diff minimo e coerenza con il resto del dialog. | 2026-04-05 |
| 7 | Nessun extract del dialog in altro file nella Execution prevista. | Diff minimo. | 2026-04-04 |
| 7bis | **Priorità a soluzione semplice e locale:** nessun tentativo di “generalizzare” il pattern responsive oltre il blocco prezzi/quantità in questo task. | Evita over-engineering e mantiene execution rapida/reviewabile. | 2026-04-05 |
| 8 | Il task **non** passa a **EXECUTION** senza **approvazione esplicita** dell’utente sul planning. | Governance | 2026-04-04 |

---

## UX polish ammessi in Execution

Interventi **estetici/locali** accettabili **solo** nel perimetro del blocco prezzo acquisto / prezzo vendita / quantità (e suo contenitore immediato), senza allargare il task:

- **Spacing verticale/orizzontale** più armonico tra i tre field e verso i campi immediatamente adiacenti (es. `spacedBy` interno al blocco allineato a `spacing.sm` o `spacing.md` — **una scelta unica**, motivata nel log Execution).
- **Allineamento visivo:** larghezza piena in colonna; in `Row` baseline invariata salvo micro-aggiustamento `spacedBy` se non altera la struttura a tre colonne.
- **Leggibilità label:** nessun cambio di stringa obbligatorio; ammessi solo accorgimenti **presentazionali** già supportati da Material3 sugli `OutlinedTextField` esistenti (es. coerenza `modifier` / padding implicito del componente), **senza** nuove dipendenze e **senza** ridisegnare label custom fuori dal componente standard.
- **Stabilità semantica dei campi:** non introdurre label abbreviate, placeholder sostitutivi o affordance testuali nuove come scorciatoia per “far stare” il layout; la preferenza del task è risolvere il problema con responsive layout e spacing, non con compressione semantica.
- **Gerarchia visiva locale:** enfasi leggera solo se ottenuta con mezzi già usati nel dialog (es. ordine campi, spacing) — **no** nuove card, no colori semantici aggiuntivi, no tipografia custom fuori da `MaterialTheme.typography`.
- **Robustezza visuale stati field:** ammessi micro-aggiustamenti che preservino leggibilità di label, testo inserito, cursore e `supportingText`/errore nei due layout, purché ottenuti senza cambiare business logic né introdurre componenti custom fuori standard.
- **Coerenza del ritmo del dialog:** ammessi micro-aggiustamenti minimi al margine superiore/inferiore del blocco se servono a evitare “buchi” o densità eccessiva rispetto ai campi barcode/nome prodotto, purché restino locali e senza alterare la gerarchia complessiva del dialog.

**Escluso:** ridisegnare titolo dialog, area pulsanti, card lookup DB, sezioni categoria/barcode/nome; introdurre animazioni decorative; nuovi componenti non necessari al layout responsive.

---

## Planning (Claude)

### Analisi (repo-grounded)

- Il collo di bottiglia è localizzato: una sola `Row` con tre `OutlinedTextField` pesati.
- Il resto del dialog è già prevalentemente **full width**; l’intervento non richiede redesign globale.
- `LocalConfiguration.screenWidthDp` è **sconsigliato** come unica misura: preferire **`maxWidth` da `BoxWithConstraints`** sul blocco.

### Regola responsive (vincolo assoluto per Execution)

- Condizione **`maxWidth < 400.dp`** (nel `BoxWithConstraints` che avvolge il blocco): layout **solo verticale** — tre field in colonna, ciascuno a larghezza piena del contenitore.
- Condizione **`maxWidth >= 400.dp`**: layout **solo orizzontale** — `Row` con tre `weight(1f)`, ordine acquisto → vendita → quantità.
- **Vietato:** combinazioni parziali (2+1 su righe diverse), soglie multiple, switch basato su altezza/orientamento/font scale, «eccezioni» non registrate nel log Execution.

**Implementazione raccomandata unica (DRY):** estrarre **un solo** `@Composable private fun ManualEntryPriceQuantityBlock(...)` **nello stesso file** che incapsula i tre `OutlinedTextField` e sceglie internamente tra `Column` e `Row` in base a `useVerticalLayout`; i field restano definiti una sola volta, con gli stessi parametri (`keyboardOptions`, `keyboardActions`, `focusRequester`, `onFocusChanged`, normalizzazione on blur), mentre varia solo il container e il `Modifier` di layout. Questa è la soluzione preferita del planning; alternative equivalenti non sono richieste in questo task.

### Comportamento atteso (stretto vs largo)

| Condizione (sul blocco) | Layout | Contenuto |
|-------------------------|--------|-----------|
| `maxWidth < 400.dp` | Solo verticale | Tre righe full width, ordine fisso |
| `maxWidth >= 400.dp` | Solo orizzontale | Tre colonne elastiche, ordine fisso |

### Landscape, tastiera, testi lunghi, font scale

- **Landscape:** si applica **solo** la regola su `maxWidth`. Spesso `maxWidth >= 400.dp` → riga orizzontale; se in un dispositivo landscape il blocco resta `< 400.dp`, la colonna è corretta per vincolo — **nessuna** regola aggiuntiva sull’altezza.
- **Tastiera aperta:** il `Column` esterno ha già `verticalScroll`; il layout verticale aggiunge altezza → verificare in smoke che lo scroll permetta di portare il campo focalizzato in vista; **nessun** `WindowInsets` redesign fuori perimetro salvo fix minimo se regressione diretta.
- **Testi lunghi:** full width in colonna deve migliorare la resa rispetto a tre colonne strette; normalizzazione on blur resta l’unica logica di accorciamento.
- **Font scale elevato:** TASK-032 **non** introduce policy dedicate. Se, con `maxWidth >= 400.dp` e font scale molto alto, la riga orizzontale resta difficile, ciò è **limite residuo noto**, da documentare in Execution/Handoff come **fuori scope** — **non** si aggiungono breakpoint o layout extra in questo task.

### Piano di esecuzione futuro (per l’esecutore)

1. **Localizzare** in `GeneratedScreen.kt` → `ManualEntryDialog` il `Row` che contiene le tre `OutlinedTextField` (prezzo acquisto, vendita, quantità).
2. **Introdurre** `BoxWithConstraints` **immediatamente** attorno a quel blocco; calcolare `val compact = maxWidth < 400.dp`.
3. **Mantenere locale la decisione responsive:** `compact` non deve diventare stato condiviso del dialog né guidare altre sezioni UI; il wrapper resta confinato al blocco prezzi/quantità.
4. **Estrarre** `@Composable private fun ManualEntryPriceQuantityBlock(...)` nello stesso file, così che i tre field restino definiti **una sola volta** e il composable scelga solo `Column` vs `Row`.
5. **Preservare** integralmente parametri e comportamento dei field: `keyboardOptions`, `keyboardActions`, `focusRequester`, `onFocusChanged`, normalizzazione on blur, `isError` e testo delle label.
6. **Ramo `compact`:** `Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(...))` con i tre field ciascuno `Modifier.fillMaxWidth()` — **nessun** `weight` in questo ramo.
7. **Ramo non-compact:** `Row` con `spacedBy(spacing.sm)` (o valore scelto e motivato) e `Modifier.weight(1f)` su ogni field come oggi.
8. **Preservare la struttura di scroll esistente:** nessun nested scroll, nessun container aggiuntivo che cambi il comportamento del `AlertDialog`; il blocco responsive resta dentro il `Column` già scrollabile.
9. **Rifinire** lo spacing del blocco e del suo intorno immediato solo se necessario a migliorare ritmo verticale, leggibilità e coerenza visiva locale, documentando ogni micro-polish nel log Execution.
10. **Evitare generalizzazioni:** mantenere la soluzione confinata al solo blocco prezzi/quantità; nessuna estrazione di pattern responsive riusabile fuori perimetro, salvo necessità tecnica strettamente locale.
11. **Verifica statica:** confronto diff per assicurarsi che non esistano due copie divergenti dei tre `OutlinedTextField` e che `supportingText`/error state non introducano overlap o compressioni nel ramo verticale.
12. **Verifica contenutistica minima:** confermare che label, asterisco del prezzo vendita e affordance testuali dei tre field siano rimasti invariati; se serve un fix testuale per regressione introdotta dal layout, documentarlo come eccezione locale nel log Execution.
13. **Smoke manuale:** portrait stretto, largo/tablet o dialog largo, tastiera aperta, input lunghi, eventuali stati errore/supporting text; controllare CTA, scroll e raggiungibilità del campo attivo.
14. **Preservazione comportamento generale:** nessuna modifica a `basicValid`, `newRowData`, lookup barcode, effetti `LaunchedEffect`, stringhe.
15. **Build/qualità:** `./gradlew assembleDebug`, `./gradlew lint`; aggiornare **Execution** con elenco polish UI/UX ammessi effettivamente applicati (riga per riga, motivazione).
16. **Baseline TASK-004:** solo se il diff esce dal perimetro UI puro.

### Rischi / guardrail

- **Rischio:** duplicazione dei tre field → drift e bug futuri → **mitigazione:** helper privato o composable unico (criterio accettazione #10).
- **Rischio:** uso di `screenWidthDp` al posto di `maxWidth` → **mitigazione:** `BoxWithConstraints` obbligatorio sul blocco.
- **Rischio:** far “uscire” la logica responsive dal perimetro del blocco (flag condivisi, condizioni riusate altrove nel dialog) → **mitigazione:** decisione 4bis + criterio 9bis; misurazione e branch UI restano strettamente locali.
- **Rischio:** introdurre una soluzione “troppo intelligente” (helper generici, policy responsive riusabili, branch multipli) per un problema locale → **mitigazione:** decisione 7bis + perimetro stretto del task; preferire soluzione minimale, esplicita e facilmente reviewabile.
- **Rischio:** scope creep «miglioro anche il titolo / i pulsanti» → **mitigazione:** sezione **UX polish ammessi** + **Non incluso**.
- **Rischio:** introdurre una transizione/animazione non necessaria tra i due layout con effetti collaterali su misura o stabilità visiva → **mitigazione:** cambio di layout statico, senza animazioni, coerente con il carattere locale e funzionale del task.
- **Rischio:** “risolvere” la densità del layout accorciando label o cambiando affordance testuali → **mitigazione:** il task privilegia layout responsive + spacing; testi invariati salvo regressione locale documentata.
- **Guardrail:** nessun cambio a lambda di business (`onValueChange` semanticamente diverso), `isError`, testi errore, `supportingText` del barcode, logica categoria.
- **Guardrail:** nessuna “ottimizzazione UX” deve cambiare gerarchia funzionale del dialog o introdurre differenze tra layout compatto e largo che vadano oltre leggibilità, spacing e allineamento locale del blocco.
- **Guardrail:** nessun colore hardcoded; solo `MaterialTheme` / `appSpacing` / `appColors` esistenti.

### Definition of Ready (ingresso in EXECUTION)

- [x] Utente ha **letto e approvato** questo planning (o richiesto revisioni integrate nel file task).
- [x] Regola **<400 / ≥400** e **vietato layout ibrido** accettate.
- [x] Strategia **anti-duplicazione** (helper o equivalente) accettata.
- [x] Sezione **UX polish ammessi** compresa; nessun ambiguità su redesign vs polish.
- [x] Esecuzione completata e task portato a **DONE** con review documentale e tracking allineato.

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — introdotto `ManualEntryPriceQuantityBlock` (`@Composable private`): `BoxWithConstraints` + `useVerticalLayout = maxWidth < 400.dp`; ramo `Column` con `fillMaxWidth()` / ramo `Row` con `weight(1f)`; tre `OutlinedTextField` definiti una sola volta tramite lambda `@Composable` (`purchasePriceField`, `retailPriceField`, `quantityField`) con stessi `keyboardOptions`, `keyboardActions`, `focusRequester`, normalizzazione on blur; `ManualEntryDialog` chiama il blocco al posto della `Row` inline. **UI/UX:** `fieldSpacing = appSpacing.sm` per `spacedBy` verticale e orizzontale (coerenza con decisione 3bis e ritmo locale).

**Azioni eseguite:**
1. Verifica statica codice vs planning (soglia 400dp, solo `Column`/`Row`, nessun ibrido, responsive confinato al blocco).
2. `./gradlew assembleDebug` e `./gradlew lint` con `JAVA_HOME` = Android Studio JBR — entrambi **OK** (`BUILD SUCCESSFUL`).
3. Allineamento file task e sezioni di chiusura; fix markdown heading `## Non incluso`.

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` OK |
| Lint | ✅ ESEGUITO | `lint` OK |
| Warning Kotlin nuovi | ✅ ESEGUITO | Nessun warning nuovo attribuibile al blocco |
| Coerenza con planning | ✅ ESEGUITO | Conforme a regola responsive e DRY |
| Criteri di accettazione | ⚠️ / ✅ | Vedi tabella: alcuni **M** ⚠️ NON ESEGUIBILE (smoke UI) |

**Baseline regressione TASK-004 (se applicabile):**
- N/A — diff solo UI locale in `GeneratedScreen.kt`, nessun ViewModel/repository.

**Incertezze:**
- Nessuna su coerenza codice/planning; smoke manuale UI da eseguire su device (vedi Handoff).

**Handoff notes:**
- Verificare su phone stretto/largo, tastiera, font scale elevato (limite noto fuori scope se `maxWidth >= 400.dp` + font molto grande).

---

## Review

### Review — 2026-04-05

**Revisore:** Claude (planner) — review documentale su evidenza codice + build/lint.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1–15 | (vedi tabella in cima al file) | ✅ / ⚠️ | ⚠️ = smoke manuale non eseguito in sessione; S/B verificati |

**Problemi trovati:**
- Nessuno sul codice o sul planning; correzione minore: heading markdown `## Non incluso` (era `- ## Non incluso`).

**Verdetto:** **APPROVED**

**Note:**
- Chiusura **DONE** accettata con rischio residuo documentato su smoke UI (coerente con pratica altri task UX).

---

## Fix

### Fix — 2026-04-05 (documentale)

- Corretto heading **Non incluso** (rimosso `-` errato che rompeva la gerarchia markdown).

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | **DONE** |
| Data chiusura | 2026-04-05 |
| Tutti i criteri ✅? | Sì, salvo criteri **M** marcati **⚠️ NON ESEGUIBILE** (smoke UI) con motivazione accettata |
| Rischi residui | Smoke manuale non eseguito; possibile affaticamento riga orizzontale con font scale molto alto (fuori scope TASK-032) |

---

## Riepilogo finale

TASK-032 ha introdotto il blocco responsive **`ManualEntryPriceQuantityBlock`** in `GeneratedScreen.kt`: sotto **400.dp** di larghezza misurata nel blocco, i tre campi prezzo/quantità sono in **colonna** a larghezza piena; altrimenti restano in **riga** con **weight**. Una sola definizione per campo (lambda condivise); **nessun** cambio a business logic, ViewModel, DAO, navigation. **Build e lint verdi.** Il file task è allineato allo stato **DONE** del `MASTER-PLAN.md`. I criteri puramente manuali restano da confermare su device quando possibile.

---

## Handoff

- **Prossimi passi consigliati:** smoke su dispositivo/emulator — portrait stretto (<400dp nel blocco), larghezza ampia, tastiera aperta, stringhe lunghe nei campi numerici, scroll del dialog e CTA raggiungibili.
- **Limite noto:** con **font scale** molto elevato e `maxWidth >= 400.dp`, la `Row` orizzontale può restare faticosa; non coperto da TASK-032 (eventuale task futuro).
- **Aree correlate:** `ManualEntryDialog` completo in `GeneratedScreen.kt`; TASK-033+ backlog UX non toccati.
