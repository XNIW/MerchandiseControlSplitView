# TASK-033 — Feedback azioni: save/sync/export conferma visiva

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-033                                    |
| Stato                | DONE                                        |
| Priorità             | ALTA                                        |
| Area                 | UX / Feedback                               |
| Creato               | 2026-04-05                                  |
| Ultimo aggiornamento | 2026-04-05 (review finale, fix mirati e chiusura) |

---

## Dipendenze

- Nessuna

---

## Scopo

Rendere **percettibile, coerente e gradevole** la conferma visiva dopo azioni critiche: salvataggio riga (percorsi oggi silenziosi), flusso sync griglia→database, export completato dove manca o è debole. Interventi **minimi**, allineati ai pattern già presenti (Snackbar host su schermate principali, Toast dove già usato), con libertà di applicare **micro-ritocchi UI/UX locali** se migliorano chiaramente chiarezza, tono e qualità percepita del feedback.

Sono ammessi **micro-miglioramenti** locali a copy, timing, tono, gerarchia visiva del messaggio, ordine emissione→azione e coerenza del feedback (sempre nel perimetro); **non** sono ammessi redesign ampi, nuovi flussi o scope creep. In caso di scelta tra alternative equivalenti, adottare quella **più sobria, chiara e coerente** con lo stile restante dell’app. Obiettivo: rifinitura UX leggibile, **non invasiva**, proporzionata all’azione e coerente con Material3 / pattern già presenti.

---

## Contesto

L’audit repo-grounded (2026-04-05) mostra un mix **Snackbar** (`Scaffold` + `SnackbarHostState` su `GeneratedScreen`, `DatabaseScreen`, `HistoryScreen`) e **Toast** (molti casi in `GeneratedScreen`, error export in `ImportAnalysisScreen`). **`DatabaseViewModel.UiState.Success/Error`** è già consumato in snackbar su Generated/Database.

**Gap reale principale — salvataggio riga (manual entry):** `ExcelViewModel.addManualRow`, `updateManualRow`, `deleteManualRow` persistono via `saveCurrentStateToHistory` ma **non** impostano `historyActionMessage` (a differenza di rename/delete entry). La UI non mostra alcuna conferma alla chiusura del dialog.

**Salvataggio riga (bottom sheet dettaglio):** `persistRowChanges()` in `GeneratedScreen.kt` mostra già **Toast** con `R.string.row_updated`.

**Sync — analisi griglia:** `analyzeCurrentGrid()` → Toast «avviata» (`sync_analysis_started`); durante l’analisi `GeneratedScreen` mostra `LoadingDialog` se `dbUiState is UiState.Loading`. A fine analisi `DatabaseViewModel.analyzeGridData` imposta **`UiState.Idle`**; la navigazione a `ImportAnalysis` è automatica (`NavGraph` + `importAnalysisResult`). **Decisione di planning:** nessun feedback testuale aggiuntivo obbligatorio (vedi § Planning — decisione sync).

**Sync — apply import:** `importProducts` imposta `UiState.Success(R.string.import_success)`; al `ImportFlowState.Success` il grafo fa `popBackStack()` verso Generated, dove `LaunchedEffect(dbUiState)` mostra **snackbar** (con azione «Apri database» su Success).

**Export — Generated:** `saveLauncher` dopo `exportToUri` → Toast success/failure. **`shareXlsx()`** in successo apre solo il chooser: **nessun messaggio esplicito** di export riuscito (solo Toast in caso di errore).

**Export — Database:** `exportDatabase` imposta `UiState.Success(R.string.export_success)` → snackbar su `DatabaseScreen`.

Riferimento governance precedente: **TASK-008** (`DONE`) ha già normalizzato parte del feedback errori; questo task è incrementale su **success path** e coerenza.

---

## Non incluso

- Redesign toolbar, dialog o flussi di navigazione.
- Modifiche a DAO, repository, schema import/apply, logica `ImportAnalyzer` / round-trip.
- Task **TASK-034** (icone import/export), **TASK-039** (export dialog semplificato), **TASK-041** (celebrazione completamento workflow).
- Sostituzione globale Toast→Snackbar in tutta l’app (fuori perimetro).
- Nuovi bus/eventi tipo `SharedFlow` / channel dedicati salvo evidenza in esecuzione che `historyActionMessage` sia inadeguato (allora documentare in Execution e rivalutare in review).
- Cambio del feedback già esistente in **`persistRowChanges()`** o nel callback **`saveLauncher`** (export file SAF) **solo per uniformare** o per «bellezza» teorica: **vietato** nel planning; si tocca quel codice **solo** se in EXECUTION/smoke emerge **problema reale** (regressione, doppio feedback, copy fuorviante) **attribuibile o collegata** a TASK-033.
- Estensione del task a celebratory UX, badge, animazioni o affordance decorative fuori dal feedback transiente strettamente utile.

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `GeneratedScreen.kt` | Toast su share success (regola UX § Strategia); eventuale micro-copy su messaggi esistenti; consumo snackbar già presente. |
| `ExcelViewModel.kt` | Impostare `historyActionMessage` dopo esito positivo di `addManualRow` / `updateManualRow` / `deleteManualRow` (solo segnale UX, stesso pattern rename/delete). |
| `app/src/main/res/values/strings.xml` e `values-en` / `values-es` / `values-zh` | Nuove chiavi solo se `row_updated` / `file_exported_successfully` non sono semanticamente riutilizzabili (vedi § Stringhe e i18n). |
| `DatabaseViewModel.kt` | **Non richiesto** per questo task se si conferma la decisione «nessun Success a fine analisi griglia» (vedi § Decisione sync). |
| `NavGraph.kt` | **Sola lettura / verifica** del rientro da ImportAnalysis e del timing con snackbar `import_success`; non candidato a modifiche salvo evidenza reale di bug nel passaggio. |
| `HistoryScreen.kt` | **Sola lettura / verifica:** stesso `historyActionMessage` è consumato qui (`LaunchedEffect` + `onHistoryActionMessageConsumed`). Serve a controllare che l’uso aggiuntivo per manual row **non** introduca replay, consumi doppi o interferenze percettibili quando l’utente passa **rapidamente** da Generated a History dopo un’azione sulla riga. |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Manual row — aggiunta:** dopo tap **Conferma** (o flusso «Aggiungi e prossimo» se nel perimetro dello stesso salvataggio), compare **un solo** feedback chiaro (Snackbar tramite `historyActionMessage` o pattern equivalente documentato); messaggio **leggibile** e coerente col tono delle altre azioni storico. | B, S, M | — |
| 2 | **Manual row — modifica:** stesso requisito del punto 1 dopo modifica e conferma. | B, S, M | — |
| 3 | **Manual row — eliminazione:** stesso requisito dopo eliminazione confermata dal dialog. | B, S, M | — |
| 3bis | **Sobrietà feedback manual row:** le snackbar per add/update/delete manual row sono **brevi**, **passive**, **leggibili a colpo d’occhio**, **senza nuova CTA** (nessun `actionLabel` aggiuntivo) salvo **evidenza reale** in review che una CTA migliori il flusso senza appesantirlo. Il feedback **rassicura** senza competere con la griglia o con le azioni principali della schermata. | S, M | — |
| 4 | **Nessun doppio feedback confuso:** per ciascun evento della matrice § Matrice decisionale, non compaiono **due messaggi success** sovrapposti o ridondanti nello stesso passaggio (es. niente Toast+Snackbar sullo stesso esito salvo motivazione documentata in Execution). | M | — |
| 4bis | **Ordine del feedback corretto:** quando l’azione apre subito un chooser o una UI esterna, il feedback di successo deve comparire **prima** del passaggio al contesto esterno, così da non essere perso visivamente. | S, M | — |
| 5 | **Export share:** dopo `exportToUri` riuscito e **prima** di `startActivity(chooser)`, l’utente riceve feedback breve (**Toast** con regola § Strategia); se l’utente annulla il chooser, non deve esserci errore finto: il file è stato comunque generato (comportamento atteso da verificare in smoke). | B, S, M | — |
| 6 | **Export file (SAF):** resta almeno un feedback esplicito di successo (oggi Toast); nessuna regressione del flusso document picker. | B, M | — |
| 7 | **Sync — analisi:** nessun messaggio aggiuntivo obbligatorio oltre Toast di avvio + `LoadingDialog` + transizione a ImportAnalysis (decisione § Decisione sync); nessuna regressione navigazione. | B, M | — |
| 8 | **Sync — apply:** al rientro su Generated dopo apply riuscito, la **snackbar** con `import_success` (o stringa equivalente) è **visibile** in condizioni normali; se non compare, è **bug di accettazione** da correggere nello stesso task o documentato come blocco. | B, M | — |
| 9 | **Export database:** snackbar `export_success` invariata e visibile su DatabaseScreen; nessuna regressione. | B, M | — |
| 9bis | **Nessuna regressione dei feedback già corretti:** il Toast esistente su `persistRowChanges()` e il feedback su export file SAF restano coerenti e non vengono peggiorati o duplicati dal task senza motivazione concreta documentata. | B, M | — |
| 10 | **Coerenza pattern UX/UI + proporzione:** le scelte Toast vs Snackbar rispettano la **Strategia UX esplicita** § Planning; **densità del messaggio** (poche parole, no ridondanza) e **proporzione del feedback rispetto all’azione** (micro-azione → conferma leggera, non «annuncio») restano coerenti con lo stile dell’app. Micro-ritocchi a copy, timing o gerarchia visiva solo se locali e non discontinui. Deviazioni motivate nel log Execution. | S, M | — |
| 11 | **Guardrail codice:** nessuna modifica a repository/DAO/navigation salvo necessità documentata; `./gradlew assembleDebug` e `./gradlew lint` verdi; nessun warning Kotlin nuovo nel codice toccato. | B, S | — |
| 12 | **i18n:** tutte le stringhe toccate o introdotte presenti e coerenti in **it (default `values/`), en, es, zh**; niente chiavi duplicate quasi equivalenti se esiste già una stringa adatta (vedi § Stringhe). | S | — |

**Definition of Done — task UX/UI** (`MASTER-PLAN.md`): applicare checklist; nessuna regressione funzionale intenzionale.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| D1 | **Strategia Toast vs Snackbar** (dettaglio in § Planning — Strategia UX): Snackbar per feedback legati a **stato schermata / ViewModel condiviso** e host già presente; Toast quando si apre **subito** chooser/intent esterno o dove la Snackbar è **meno affidabile** (finestra sistema sopra, composable smontato). | Allinea l’implementazione allo stile attuale e riduce snackbar «perse». | 2026-04-05 |
| D2 | **Manual row:** usare **`historyActionMessage`** in `ExcelViewModel` dopo operazioni riuscite; consumo su **Generated** (e History se stesso stato condiviso) come oggi. Nessun nuovo bus salvo rischio non mitigabile (documentare se emerge). | Minimo cambiamento; pattern già usato per rename/delete entry. | 2026-04-05 |
| D3 | **Fine analisi griglia (prima di ImportAnalysis):** **nessun** feedback testuale aggiuntivo obbligatorio. Segnale UX sufficiente = scomparsa loading + **navigazione automatica** alla schermata Import Analysis (contenuto pieno di significato). Evita doppio feedback con eventuale Snackbar/Toast immediatamente prima del cambio schermata. | Soluzione più pulita; l’audit non ha evidenziato «vuoto» dopo loading se la nav avviene in tempi accettabili. | 2026-04-05 |
| D4 | **Export share (success):** **Toast** breve **prima** del chooser, riusando **`R.string.file_exported_successfully`** se semanticamente ok (file scritto in cache). | Coerente con D1; stringa già presente in 4 lingue. | 2026-04-05 |
| D5 | **Micro-miglioramenti UX/UI** ammessi: copy, durata percepita, ordine messaggio→azione, tono allineato a stringhe vicine; vietati redesign e nuove dipendenze. | Allineato a `AGENTS.md` / `CLAUDE.md` su miglioramenti locali tracciati in Execution. | 2026-04-05 |
| D6 | **Manual row:** se il flusso «Aggiungi e prossimo» riusa lo stesso salvataggio logico della conferma standard, il feedback resta **uno solo** per salvataggio riuscito; non va duplicato per la sola transizione al prossimo inserimento. | Evita rumore e doppie conferme nello stesso micro-flusso. | 2026-04-05 |
| D7 | **Priorità UX in caso di conflitto tra feedback transient:** dare precedenza al messaggio più vicino all’azione appena compiuta dall’utente; evitare accodamenti lunghi di snackbar “storiche” che riducano la leggibilità. | Migliora chiarezza percepita e mantiene la UI pulita. | 2026-04-05 |
| D8 | **Feedback già adeguati restano stabili:** `persistRowChanges()` e export file SAF (`saveLauncher`) non vanno “normalizzati” a tutti i costi nel task; si interviene lì **solo** se dall’esecuzione emerge una regressione o incoerenza **reale** collegata a TASK-033. La coerenza UX si persegue **sui gap**, non riscrivendo percorsi già chiari. | Perimetro stretto; evita scope creep mascherato da uniformità forzata. | 2026-04-05 |
| D9 | **Snackbar routine per manual row:** **nessuna CTA aggiuntiva** (`actionLabel`) sulle conferme add/update/delete riga manuale. Restano ammessi solo `withDismissAction` / dismiss implicito come negli altri messaggi `historyActionMessage` oggi. Una CTA extra è ammessa **solo** se emerge **evidenza reale** (smoke/review) che migliori il flusso senza appesantire; altrimenti è fuori stile per una micro-azione. | Sobrietà e allineamento al ruolo del canale (conferma breve, non hub di azioni). | 2026-04-05 |

---

## Planning (Claude)

### Strategia UX esplicita — Snackbar vs Toast

| Meccanismo | Quando usarlo | Esempi nel perimetro TASK-033 |
|------------|---------------|--------------------------------|
| **Snackbar** (host `Scaffold` + `SnackbarHostState`) | Feedback legato al **flusso interno** della schermata, stato **persistente** fino a consumo, nessuna finestra di sistema che copre subito il contenuto. Messaggi da **`DatabaseViewModel.uiState`** o da **`historyActionMessage`** già orchestrati con `LaunchedEffect`. | Success/error import da DB su Generated/Database; conferma rename/delete **entry** storico; (dopo task) conferma add/update/delete **riga manuale** su Generated. |
| **Toast** | Quando **immediatamente dopo** l’azione si apre **chooser / activity esterna** o si rischia che la Snackbar non sia letta o che il composable venga **smontato** prima del consumo. Anche dove l’app **già** usa Toast per simmetria nello stesso file (`saveLauncher`, `persistRowChanges`). | **Export share** success (prima di `createChooser`); opzionale mantenere Toast su **export file SAF** e **row_updated** per non introdurre doppi pattern nello stesso flusso senza necessità. |

**Regola operativa per l’esecutore:** preferire Snackbar solo se il messaggio resta nel contesto della schermata attiva per almeno un intervallo utile; in caso di dubbio tra Toast e Snackbar su share, **scegliere Toast** (D1/D4).

**Rifinitura UI/UX ammessa nel planning:** oltre alla scelta del canale, sono consentiti micro-affinamenti locali del feedback (copy più naturale, timing più leggibile, ordine più chiaro tra conferma e azione successiva, eventuale allineamento del tono con stringhe vicine) purché non cambino il flusso e non introducano nuovi pattern globali.

**Regola di conservazione del buono esistente:** dove un feedback attuale è già chiaro, coerente e non problematico nel suo contesto (`persistRowChanges`, export SAF), il task deve preferire **non** riscriverlo; la coerenza va cercata prima nei gap reali, non uniformando forzatamente tutto.

**Sobrietà del feedback (obbligatoria per manual row):**

- Snackbar di add/update/delete riga manuale: **brevi**, **passive**, **senza nuove CTA** (D9); devono **rassicurare** («fatto») senza competere con griglia, toolbar o contenuto principale.
- Evitare copy lunghi, tono celebrativo, snackbar «pesanti» o affollate di azioni per **micro-interazioni locali**.
- Il criterio qualitativo: l’utente percepisce una **routine confirmation**, non un evento di prodotto.

---

### Decisione sync — fine analisi griglia

- **Scelta:** **nessun messaggio aggiuntivo** (né `UiState.Success` dedicato, né Toast/Snackbar «analisi pronta») al termine di `analyzeGridData` **solo** per chiudere il gap descritto nel backlog.
- **Motivazione:** la combinazione **LoadingDialog** + transizione a **`ImportAnalysis`** è un segnale UX **forte e non ambiguo**; un secondo messaggio testuale sul Generated appena prima della navigazione crea **alto rischio di doppio feedback** o di messaggio flash non letto.
- **Eccezione:** se durante EXECUTION o smoke emerge un caso reale (nav lenta, `importAnalysisResult` null, utente «appeso» dopo loading), si documenta l’evidenza e si valuta **un solo** intervento mirato (copy o stato loading), senza contraddire D3 senza review.

---

### Matrice decisionale — eventi, emissione, feedback, rischi

| Evento | Punto di emissione (codice / flusso) | Feedback scelto | Motivazione UX | Rischio doppio feedback | Note implementative |
|--------|--------------------------------------|-----------------|----------------|-------------------------|---------------------|
| Add manual row | `ExcelViewModel.addManualRow` → `saveCurrentStateToHistory` OK | **Snackbar** via `historyActionMessage` | Conferma **routine**, proporzionata; nessuna CTA extra (D9) | Basso se non si aggiunge Toast sul dismiss dialog | Dopo persist OK; copy **breve**; **nessuna `actionLabel`** aggiuntiva; **routine confirmation**; no duplicati col dialog |
| Update manual row | `ExcelViewModel.updateManualRow` | **Snackbar** via `historyActionMessage` | Come sopra | Come sopra | Come sopra; copy distinto da «add» se chiavi separate; stessa sobrietà |
| Delete manual row | `ExcelViewModel.deleteManualRow` | **Snackbar** via `historyActionMessage` | Conferma lato dati senza drammatizzare | Dialog delete senza secondo success oggi | Come sopra; **non** riusare `history_entry_deleted` se suggerisce «file»; chiavi dedicate § Stringhe |
| Sync analysis start | `GeneratedScreen.analyzeCurrentGrid` | **Toast** esistente (`sync_analysis_started`) | Avvio immediato, pattern già in uso | Con LoadingDialog: ok se Toast non ripetuto | Nessun secondo Toast su stesso tap |
| Sync analysis completed / passaggio ImportAnalysis | `DatabaseViewModel.analyzeGridData` → `Idle` + `publishPreviewAnalysis`; `NavGraph` nav | **Nessuno aggiuntivo** (D3) | Nav + schermata analisi = conferma implicita | **Evitato** deliberatamente | Non impostare `UiState.Success` solo per messaggio «pronto» senza nuova evidenza |
| Sync apply success | `importProducts` → `UiState.Success(import_success)`; pop a Generated | **Snackbar** (`LaunchedEffect(dbUiState)` su Generated) | Stato globale import; azione opzionale «database» | Con `historyActionMessage` ravvicinato: basso se non si emette altro messaggio nello stesso frame | Verificare smoke § Checklist; se snackbar persa → bug (criterio #8) |
| Export file (SAF) | `saveLauncher` callback | **Toast** esistente (`file_exported_successfully` o equivalente già in uso) | Simmetria con error path; nessun chooser | Nessuno se share non usa stesso tap | Non obbligatorio cambiare a Snackbar in questo task; mantenerlo stabile salvo evidenza reale di incoerenza |
| Export share | `GeneratedScreen.shareXlsx` dopo `exportToUri` OK | **Toast** (`file_exported_successfully`, D4) | Chooser sopra l’app; Toast resta leggibile | Un solo Toast prima del chooser | Ordine: export → Toast → `startActivity` |
| Export database | `DatabaseViewModel.exportDatabase` → Success | **Snackbar** su `DatabaseScreen` | Host dedicato; export lungo con overlay progress | `exportUiState` + snackbar: accettabile se non due messaggi success testuali concatenati senza senso | Verificare che solo un messaggio «completato» sia mostrato a export finito; ammesso solo micro-polish locale del copy se migliora la percezione senza cambiare il flusso |

---

### Canale `historyActionMessage` — audit e rischi

**Dove viene impostato oggi** (`ExcelViewModel.kt`): su esito di `renameHistoryEntry` (success/error), `deleteHistoryEntry` (success/error), e rami di errore quando l’entry non è trovata. Non su add/update/delete manual row (gap TASK-033).

**Dove viene consumato:**
- **`GeneratedScreen`:** `LaunchedEffect(historyActionMessage)` → `showSnackbar` → **`consumeHistoryActionMessage()`** al termine della sospensione `showSnackbar`.
- **`HistoryScreen`:** stesso schema con `onHistoryActionMessageConsumed` passato da `NavGraph` che chiama `excelViewModel.consumeHistoryActionMessage()`.

**Reset:** `consumeHistoryActionMessage()` azzera `mutableStateOf<String?>(null)`.

**Rischi:**

| Rischio | Descrizione | Mitigazione (planning) |
|---------|-------------|------------------------|
| **Sovrapposizione** | Due operazioni rapide impostano due messaggi; solo l’ultimo «vince» (stato singolo). | Accettabile per perimetro; evitare burst dal dialog (debounce lato UI solo se necessario, fuori scope salvo evidenza). |
| **Replay** | Stesso valore stringa ri-emesso: `LaunchedEffect` potrebbe non ri-triggerare se lo stato non passa da null. | Se in EXECUTION si usa sempre **clear** dopo consumo, il pattern attuale mitiga; per messaggi identici consecutivi, valutare micro-reset `null` prima di re-set (documentare in Execution se applicato). |
| **Perdita su navigazione** | `LaunchedEffect` cancellato durante `showSnackbar` potrebbe ritardare `consume` (raro). | Smoke su back veloce; se necessario, `finally`/consumo anticipato — solo con evidenza, non nel planning minimo. |
| **Conflitto con Snackbar `dbUiState`** | Import success e `historyActionMessage` in rapida successione. | Ordine temporale: rispettare priorità percepita; test manuale apply subito dopo manual row; criterio #4. |
| **Messaggi identici consecutivi** | Due salvataggi uguali di fila (es. update ripetuto) potrebbero rendere meno evidente la seconda conferma se il valore non viene percepito come nuovo. | In execution verificare che il pattern attuale (`consume` → `null`) basti; se no, documentare micro-mitigazione locale senza introdurre nuovi canali. |

**È il canale giusto per manual row?** **Sì**, nel perimetro attuale: stesso ViewModel, stesso host Snackbar su Generated, zero nuove API, coerente con altre azioni «storico Excel». Alternative (callback Composable-only) spostano la logica di «quando mostrare» fuori dal pattern esistente e sono sconsigliate salvo fallimento verificato di questo canale.

**Uso consentito del canale in TASK-033:** `historyActionMessage` va impiegato per **conferme brevi e non azionabili** (oltre al dismiss standard già usato negli snackbar esistenti). **Non** deve diventare un contenitore generico per messaggi lunghi, promozionali o snackbar con **CTA** aggiuntive: ciò aumenterebbe il rischio di **interferenza tra i due consumer** (Generated / History), di code percettive e di **calo di leggibilità** UX.

**Nota di perimetro:** l’introduzione del feedback per manual row non deve peggiorare o alterare il consumo di `historyActionMessage` su `HistoryScreen`; se durante l’audit di execution emerge interferenza reale tra consumer diversi, va documentata come issue di integrazione e trattata con il minimo cambiamento possibile.

---

### Stringhe e i18n — piano

**Sicuramente da coprire in it/en/es/zh (nuove chiavi o riuso):**

| Messaggio | Preferenza | Nota |
|-----------|------------|------|
| Riga aggiunta (manual) | **Nuova chiave** consigliata (es. `manual_row_added`) | «Riga» ≠ «file»; non riusare `history_entry_*` |
| Riga aggiornata (manual) | **Riuso `row_updated` solo se** il significato in **it / en / es / zh** copre **equamente** sia il salvataggio dal **dettaglio riga (sheet)** sia la **conferma manual entry**, senza far pensare che il messaggio sia **esclusivo** del bottom sheet dettaglio | Se in una o più lingue il copy suona legato solo al dettaglio o è ambiguo → **chiave dedicata** (es. `manual_row_updated`) per chiarezza e sobrietà |
| Riga eliminata (manual) | **Nuova chiave** (es. `manual_row_deleted`) | Non confondere con `history_entry_deleted` (file storico) |
| Export share success | **Riuso `file_exported_successfully`** | Già presente; descrive file pronto / scritto |

**Non introdurre** quasi-duplicati di `import_success`, `export_success`, `sync_analysis_started` se le chiavi esistenti bastano.

**Verifica:** diff su `values/`, `values-en/`, `values-es/`, `values-zh/` con stesso set di chiavi.

**Criterio qualitativo copy:** preferire messaggi brevi, positivi, immediatamente comprensibili e coerenti con il lessico già usato nell’app; evitare formulazioni troppo tecniche o quasi-duplicate che cambiano solo una parola senza reale beneficio UX.

**Criterio di coerenza terminologica:** dove il contesto è Generated/manual row, preferire lessico riferito a **riga** o **modifica**; evitare copy che facciano pensare a file, import o storico completo quando l’azione è solo locale alla riga.

---

### Edge case — da verificare in EXECUTION / smoke

1. **Share chooser annullato:** Toast pre-chooser mostrato; utente torna su Generated senza inviare il file — nessun messaggio di errore; griglia/storico coerenti.
2. **Doppio tap rapido su Conferma (manual entry):** al massimo un messaggio coerente o idempotenza senza doppia Snackbar ridondante (criterio #4).
3. **One-shot durante recomposition / ritorno schermata:** Snackbar `dbUiState` dopo pop da ImportAnalysis deve restare visibile almeno per la durata Short in condizioni normali (criterio #8).
4. **Altro messaggio transient già visibile:** Snackbar precedente ancora in coda; accettabile sovrascrittura Material; se fastidioso, nota in Execution (no redesign).
5. **Race import success + popBackStack + snackbar:** ordine: `Success` impostato → composable Generated attivo → `LaunchedEffect` mostra snackbar; se fallisce, bug.
6. **`historyActionMessage` mentre `LoadingDialog` DB è visibile:** raro; smoke dopo manual row durante import altrui se scenario realistico.
7. **Snackbar già presente quando arriva una conferma manual row:** il nuovo messaggio non deve creare una coda lunga o confusa; se emerge comportamento poco leggibile, documentare il compromesso.
8. **Flusso “Aggiungi e prossimo” (se coinvolto dal path reale):** conferma visibile ma non ridondante; il passaggio al prossimo inserimento non deve “mangiare” il feedback.
9. **Ritorno da ImportAnalysis con snackbar apply:** verificare che il messaggio non venga perso se il rientro avviene molto rapidamente o con device sotto carico.
10. **Consumer multipli di `historyActionMessage`:** un feedback emesso per manual row su Generated non deve causare comportamento anomalo se l’utente passa subito a History.
11. **Snackbar manual row troppo lunga, invasiva o con CTA inutile:** se in EXECUTION/review il messaggio risulta **sproporzionato** alla micro-azione, rumoroso o con azione superflua, va considerato **difetto UX del task** e corretto **nello stesso perimetro** (copy, assenza di CTA, durata percepita), senza ampliare lo scope.

---

### Checklist smoke manuale (sequenziale, esito atteso)

Eseguire in ordine logico; per ogni passo l’**esito atteso** è quello indicato.

1. **Add manual row:** FilePicker → manual add → Generated → apri dialog → compila valido → **Conferma**. **Atteso:** dialog si chiude, griglia mostra la riga, **una Snackbar breve** (copy sobrio, **nessuna CTA aggiuntiva**, sensazione di routine confirmation), nessun secondo messaggio success ridondante. **Se esiste il path “Aggiungi e prossimo” e usa lo stesso salvataggio logico:** stesso atteso, ma sempre con **una sola** conferma per salvataggio.
2. **Update manual row:** stessa sessione → modifica riga → **Conferma**. **Atteso:** **una Snackbar breve**, copy **sobrio**, **nessuna action superflua** sul messaggio (solo dismiss come oggi per `historyActionMessage` su Generated); sensazione di **conferma leggera**, non feedback «pesante» per una micro-azione; testo coerente con § Stringhe (`row_updated` o chiave dedicata).
3. **Delete manual row:** elimina riga con conferma. **Atteso:** come punto 2: **snackbar breve**, **nessuna CTA aggiuntiva**, copy sobrio, **routine confirmation**; riga assente dalla griglia.
4. **Sync analysis:** griglia non manual (o manual se stesso pulsante) → **Sync**. **Atteso:** Toast avvio (se presente), **LoadingDialog**, poi **navigazione** a ImportAnalysis **senza** ulteriore messaggio «analisi pronta» obbligatorio (D3).
5. **Apply import:** ImportAnalysis → **Conferma import** → attendi fine. **Atteso:** ritorno Generated, **Snackbar** `import_success` (o equivalente) **visibile**, flusso DB non rotto.
6. **Export file:** Export da toolbar → salva con SAF. **Atteso:** Toast success (come oggi), file valido.
7. **Export share:** Share → **Atteso:** Toast success **prima** del chooser; chooser si apre; annulla chooser → nessun errore, app stabile.
8. **Export database:** Database → export → completa. **Atteso:** **Snackbar** export success, come oggi.
9. **Regressione `persistRowChanges`:** modifica riga dal dettaglio esistente. **Atteso:** il Toast già presente resta unico, leggibile e non viene duplicato dalla nuova logica manual row.
10. **Passaggio rapido a History dopo manual row:** esegui add/update/delete manual row e naviga rapidamente verso History. **Atteso:** nessun comportamento anomalo del canale `historyActionMessage`; niente replay confuso o messaggi persi in modo incoerente.

---

### Analisi tecnica sintetica (riferimento audit)

| Azione | Punto nel codice | Feedback attuale | Lacuna |
|--------|------------------|------------------|--------|
| Salva riga (sheet dettaglio) | `persistRowChanges` | Toast `row_updated` | — |
| Salva riga (manual) | `addManualRow` / `updateManualRow` | Nessuno | Gap |
| Elimina riga manual | `deleteManualRow` | Nessuno | Gap |
| Sync avvio | `analyzeCurrentGrid` | Toast | OK |
| Sync analisi → ImportAnalysis | `analyzeGridData` + nav | Nav | Nessun aggiuntivo (D3) |
| Sync apply OK | `importProducts` + pop | Snackbar Generated | Verificare visibilità |
| Export SAF | `saveLauncher` | Toast | OK |
| Export share | `shareXlsx` | Solo errore | Gap |
| Export DB | `exportDatabase` | Snackbar | OK |

### Piano di esecuzione (post-approvazione utente — sintesi)

1. Verificare in **sola lettura** i **consumer** di `historyActionMessage` su **`GeneratedScreen.kt`** e **`HistoryScreen.kt`** (stesso stato condiviso, `LaunchedEffect`, ordine show/consume): obiettivo = nessun replay anomalo né interferenza quando si naviga in fretta dopo manual row.
2. Verificare il path reale dei tre gap (manual row, export share, visibilità snackbar apply) e confermare che non esista già un feedback equivalente non considerato nell’audit.
3. Lasciare **`persistRowChanges()`** e **`saveLauncher`** **invariati** salvo problema reale emerso in smoke (D8); non toccarli solo per uniformare.
4. Definire chiavi stringa (§ Stringhe) e aggiornare le 4 lingue: copy **corto**, **denso**, proporzionato; decisione documentata su `row_updated` vs chiave dedicata per update manual.
5. Impostare `historyActionMessage` in `ExcelViewModel` per add/update/delete manual row al termine con successo di `saveCurrentStateToHistory` / rimozione: snackbar **senza `actionLabel` aggiuntiva** (D9); nessun doppio feedback locale.
6. In `shareXlsx`, dopo export OK, `Toast` con `file_exported_successfully` **prima** del chooser.
7. **Micro-polish** ammessi **solo** su copy, timing, ordine messaggio→azione successiva e **densità** del testo, purché il feedback resti **proporzionato** all’azione e sobrio.
8. Smoke: checklist § sopra + passaggio rapido Generated→History dopo manual row; `persistRowChanges` e rientro ImportAnalysis senza regressioni.
9. `assembleDebug` + `lint`.
10. **Baseline TASK-004:** **N/A** se il diff non tocca `DatabaseViewModel` / repository / import apply; altrimenti test mirati come da `AGENTS.md`.
11. Restare in **PLANNING** finché non arriva approvazione utente esplicita al passaggio in **EXECUTION**.

### Rischi residui (oltre alla tabella `historyActionMessage`)

- Snackbar import success non mostrata su device lenti: trattare come bug (criterio #8).
- Disallineamento semantico se si riusa `row_updated` per manual edit: preferire chiave dedicata se il copy in una lingua è ambiguo.
- Micro-polish di copy incoerenti tra Generated e Database: evitare differenze arbitrarie di tono.
- Feedback transient troppo “denso” in una singola sessione: preferire chiarezza e sottrazione, non aggiungere messaggi solo perché tecnicamente possibili.
- Tentazione di uniformare tutti i success feedback della schermata Generated: evitare, salvo evidenza oggettiva che il mix esistente crei davvero frizione.
- Interferenza indiretta tra Generated e History sul canale condiviso dei messaggi: rischio basso ma da tenere sotto controllo negli smoke rapidi di navigazione.
- **Snackbar routine troppo verbosa o visivamente «pesante»** (anche se tecnicamente coerente col canale): peggiora la UX su azioni piccole; va trattato come rischio di accettazione — correggere con copy più corto o rimozione di elementi superflui nello stesso task.
- **Eccesso di entusiasmo nel copy** (celebrazione, frasi lunghe) per conferme locali: degrada sobrietà e densità percepita; preferire sottrazione.

---

## Execution

_(Non iniziare senza approvazione utente e passaggio esplicito a EXECUTION.)_

### Esecuzione — 2026-04-04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — aggiunto feedback `historyActionMessage` per add/update/delete manual row solo dopo il salvataggio riuscito nello storico.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — aggiunto Toast di export riuscito nel path share subito prima del chooser.
- `app/src/main/res/values/strings.xml` — nuove chiavi `manual_row_added`, `manual_row_updated`, `manual_row_deleted` in italiano.
- `app/src/main/res/values-en/strings.xml` — nuove chiavi `manual_row_*` in inglese.
- `app/src/main/res/values-es/strings.xml` — nuove chiavi `manual_row_*` in spagnolo.
- `app/src/main/res/values-zh/strings.xml` — nuove chiavi `manual_row_*` in cinese.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — estesa la baseline JVM per coprire i nuovi feedback manual row.

**Azioni eseguite:**
1. Verifica in sola lettura dei consumer di `historyActionMessage` su `GeneratedScreen`, `HistoryScreen` e del rientro da `ImportAnalysis` in `NavGraph`: nessuna modifica necessaria emersa; il task resta sul canale già esistente senza introdurre nuovi bus/eventi.
2. Implementato il gap manual row in `ExcelViewModel` usando snackbar passive via `historyActionMessage` per add/update/delete solo dopo `saveCurrentStateToHistory(...)`.
3. Lasciati invariati `persistRowChanges()` e `saveLauncher`, come richiesto dal planning e dalle istruzioni utente.
4. Inserito in `shareXlsx()` il Toast `file_exported_successfully` dopo export riuscito e prima di `startActivity(Intent.createChooser(...))`, così il feedback resta visibile prima del passaggio al chooser.
5. UI/UX: scelta di copy dedicate `manual_row_*` invece del riuso ambiguo di `row_updated`, per mantenere il toast del dettaglio riga separato dal feedback delle manual row e più naturale in tutte le lingue.
6. Aggiornata la baseline di regressione TASK-004 con test mirati su add/update/delete manual row; mantenuto verde anche `DatabaseViewModelTest` per il path `import_success`.

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 5s` |
| Lint | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 33s`; nessuna issue sui file toccati |
| Warning nuovi | ✅ | Nessun warning Kotlin nuovo nel codice modificato; restano solo warning/deprecation preesistenti di progetto/toolchain fuori scope |
| Coerenza con planning | ✅ | Implementati solo i gap pianificati: manual row feedback, toast pre-chooser share, verifica import/apply e audit read-only dei consumer |
| Criteri di accettazione | ✅ | Tutti verificati con evidenza statica/JVM e smoke ragionata repo-grounded (vedi tabella sotto) |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest'` → `BUILD SUCCESSFUL in 15s`
- Test aggiunti/aggiornati: `ExcelViewModelTest` aggiornato per verificare i feedback `manual_row_added`, `manual_row_updated`, `manual_row_deleted`
- Limiti residui: nessuna automazione UI per Snackbar/Toast/chooser; le smoke visuali richieste sono state verificate in modo ragionato sul codice e sui path di stato/navigazione

**Criteri di accettazione — verifica finale:**
| # | Criterio | Stato finale | Evidenza |
|---|----------|--------------|----------|
| 1 | Manual row — aggiunta | ESEGUITO | `addManualRow()` ora imposta `manual_row_added` dopo `saveCurrentStateToHistory(...)`; coperto da `ExcelViewModelTest` |
| 2 | Manual row — modifica | ESEGUITO | `updateManualRow()` ora imposta `manual_row_updated` dopo salvataggio riuscito; coperto da `ExcelViewModelTest` |
| 3 | Manual row — eliminazione | ESEGUITO | `deleteManualRow()` ora imposta `manual_row_deleted` dopo persist riuscita; coperto da `ExcelViewModelTest` |
| 3bis | Sobrietà feedback manual row | ESEGUITO | Riutilizzato il canale snackbar già esistente (`historyActionMessage`) senza `actionLabel` aggiuntiva; copy brevi e passive |
| 4 | Nessun doppio feedback confuso | ESEGUITO | Manual row = una sola snackbar; export share = un solo Toast; `persistRowChanges()` e `saveLauncher` lasciati invariati |
| 4bis | Ordine del feedback corretto | ESEGUITO | In `shareXlsx()` l’ordine ora è: export riuscito → Toast → chooser |
| 5 | Export share | ESEGUITO | `shareXlsx()` mostra `file_exported_successfully` prima del chooser; l’annullamento del chooser non passa nel catch e quindi non genera falso errore |
| 6 | Export file (SAF) | ESEGUITO | `saveLauncher` non modificato; continua a mostrare il Toast esistente dopo export riuscito |
| 7 | Sync — analisi | ESEGUITO | `analyzeCurrentGrid()` e il path verso `ImportAnalysis` sono rimasti invariati; nessun nuovo messaggio aggiunto |
| 8 | Sync — apply | ESEGUITO | Verifica repo-grounded: `DatabaseViewModel.importProducts()` continua a impostare `UiState.Success(import_success)`, `NavGraph` fa `popBackStack()` su `ImportFlowState.Success`, `GeneratedScreen` continua a mostrare la snackbar su `dbUiState`; `DatabaseViewModelTest` verde |
| 9 | Export database | ESEGUITO | `DatabaseScreen` / `DatabaseViewModel.exportDatabase()` non toccati; snackbar `export_success` invariata |
| 9bis | Nessuna regressione feedback già corretti | ESEGUITO | `persistRowChanges()` e `saveLauncher` non toccati; nessuna nuova duplicazione introdotta |
| 10 | Coerenza pattern UX/UI + proporzione | ESEGUITO | Snackbar per stato interno manual row, Toast prima di UI esterna (chooser); copy dense e non celebrative |
| 11 | Guardrail codice | ESEGUITO | Nessuna modifica a repository/DAO/navigation; `assembleDebug`, `lint` e baseline JVM mirata verdi |
| 12 | i18n | ESEGUITO | Chiavi `manual_row_*` presenti e coerenti in `values/`, `values-en/`, `values-es/`, `values-zh/` |

**Smoke obbligatorie — verifica ragionata/pratica:**
- Add manual row: coperto dal nuovo path `manual_row_added` + test JVM su persist dello storico.
- Update manual row: coperto dal nuovo path `manual_row_updated` + test JVM su persist dello storico.
- Delete manual row: coperto dal nuovo path `manual_row_deleted` + test JVM su rimozione stato/persist.
- Export share: verificato staticamente il nuovo ordine `exportToUri` → Toast → chooser.
- Apply import → ritorno Generated con snackbar visibile: verificato repo-grounded sul path `DatabaseViewModel.Success(import_success)` + `NavGraph.popBackStack()` + `GeneratedScreen.LaunchedEffect(dbUiState)`.
- Regressione `persistRowChanges`: percorso lasciato invariato; nessun nuovo hook aggiunto su quel toast.
- Passaggio rapido Generated → History: audit read-only dei due consumer conferma che TASK-033 non introduce un nuovo meccanismo di replay; il messaggio resta sul canale condiviso già esistente e non sono stati aggiunti accodamenti/CTA.
- Locale non default: verificate nuove chiavi in `en`, `es`, `zh`; scelta di chiave dedicata per update manual evita il riuso ambiguo di `row_updated`.

**Incertezze:**
- Nessun blocco tecnico emerso.
- INCERTEZZA: la verifica della visibilità effettiva di Snackbar/Toast resta repo-grounded e non su emulator/device in questa sessione; non sono emersi però path rotti o regressioni nel wiring.

**Handoff notes:**
- Execution completata e pronta per review.
- Se in review/manual smoke emergesse replay percepito del canale condiviso `historyActionMessage` durante navigazione rapidissima tra Generated e History, il punto di intervento minimo sarebbe il timing di `consumeHistoryActionMessage()` nei consumer, non un nuovo bus/evento.

## Review

### Review — 2026-04-05

**Revisore:** Codex

**Checklist review richiesta:**
1. Code path verificati per add/update/delete manual row, export share e ritorno da `ImportAnalysis` con `import_success`.
2. Verificata l’emissione di `historyActionMessage` solo dopo successo reale di persist.
3. Verificata assenza di doppi feedback/confitti nei path toccati e stabilità dei consumer `historyActionMessage` su Generated/History.
4. Verificate coerenza i18n in `it/en/es/zh`, senso delle chiavi dedicate `manual_row_*` e copertura test.

**Problemi trovati:**
- Il feedback manual row veniva emesso anche se `saveCurrentStateToHistory(...)` non trovava nessuna entry da aggiornare, quindi non era rigorosamente “solo dopo successo reale”.
- `manual_row_updated` era stata introdotta come chiave dedicata ma con copy sostanzialmente uguale a `row_updated`, lasciando una duplicazione poco giustificata.

**Esito review:** **APPROVED**

**Note:**
- I problemi trovati sono stati corretti nello stesso task con diff minimo.
- Nessun’altra regressione concreta emersa nel perimetro di TASK-033.

## Fix

### Fix — 2026-04-05

**Correzioni applicate dopo review:**
1. `ExcelViewModel.saveCurrentStateToHistory(...)` ora restituisce `Boolean`; add/update/delete manual row mostrano il feedback e aggiornano `lastUsedCategory` solo quando il persist va davvero a buon fine.
2. `manual_row_updated` è stata resa semanticamente distinta da `row_updated`:
   - it: `Riga salvata`
   - en: `Row saved`
   - es: `Fila guardada`
   - zh: `行已保存`
3. `ExcelViewModelTest` è stato esteso con casi negativi che verificano l’assenza di feedback success quando l’entry storico non esiste.

**Check rieseguiti dopo fix:**
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest'` → `BUILD SUCCESSFUL in 22s`
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 2s`
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 29s`

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | **DONE** |
| Data chiusura | 2026-04-05 |
| Review finale | **APPROVED** |
| Tutti i criteri soddisfatti | Sì, con evidenza statica/JVM completa e rischio residuo documentato |
| Rischi residui | Nessun bug aperto nel perimetro del task; resta solo il limite già documentato di smoke UI non eseguite su emulator/device in questa sessione |

---

## Handoff

- TASK-033 chiuso in `DONE`.
- Nessuna azione ulteriore richiesta nel perimetro del task.
