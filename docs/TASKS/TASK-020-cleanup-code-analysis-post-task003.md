# TASK-020 — Cleanup code analysis post-TASK-003

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-020                   |
| Stato                | DONE                       |
| Priorità             | ALTA                       |
| Area                 | Qualità / Analisi statica (UI Database) |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-28 (chiusura **DONE** su decisione utente) |
| Tracking `MASTER-PLAN` | **`DONE`** (2026-03-28) |

**Nota tracking:** il task è **`DONE`** dal **2026-03-28** per **decisione utente** (chiusura consapevole). I fix tecnici risultano completati e documentati in **Execution** (2026-03-28). I **smoke manuali** richiesti dal task **non risultano eseguiti** nel contesto documentato (⚠️ in **Execution**); la chiusura **non** li tratta come bloccanti. **Rischio residuo noto:** verifiche manuali residue consigliate su device/emulatore — vedi **Chiusura** e **Riepilogo finale**. **TASK-004** è il successore **`ACTIVE`** nel `MASTER-PLAN`.

### Chiusura planning (tracking)

| Voce | Valore |
|------|--------|
| Data | 2026-03-28 |
| Esito | **Planning completato e validato** — contenuto tecnico del task consolidato (incluso perimetro a **quattro** file e strategia bottom sheet allineata al codice post-TASK-003) |
| Execution | **Completata** 2026-03-28 — log, evidenze e check in sezione **Execution** sotto |
| Prossimo passo | Task chiuso **`DONE`** 2026-03-28 — vedi **Chiusura** |

**Nota storica:** la riga «Execution | Non avviata» nella bozza iniziale di questa tabella si riferiva al solo passaggio di chiusura *planning* documentale; l’**EXECUTION** su codice è stata eseguita in sessione successiva e registrata nella sezione **Execution**.

---

## Dipendenze

- **TASK-001** (`DONE`) — governance e baseline
- **TASK-003** (`DONE`, 2026-03-27) — decomposizione `DatabaseScreen` (origine dei file sotto analisi)

**Successore:** **TASK-004** (test unitari) — **`ACTIVE`** nel `MASTER-PLAN` dopo **`DONE`** di **TASK-020** (2026-03-28).

---

## Scopo

Ripulire e triageare le segnalazioni di **code analysis** (IDE / Compose compiler / lint dove applicabile) emerse dopo **TASK-003** sui quattro file UI della schermata Database (`DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, `EditProductDialog.kt`) estratti o toccati dalla decomposizione, **senza** redesign, **senza** cambiare business logic, DAO, repository o navigazione, **senza** micro-variazioni UX/UI non necessarie (vedi *Blocco micro-variazioni UX*). Obiettivo: eliminare **errori** bloccanti, correggere **warning** triviali e sicuri, documentare o posticipare il resto in modo tracciabile — restando un **cleanup mirato di static analysis**, non un refactor architetturale, con **diff minimi** (*Regola di diff hygiene*).

---

## Contesto

Dopo la decomposizione in `DatabaseScreen.kt` + `DatabaseScreenComponents.kt` + `DatabaseScreenDialogs.kt` + `EditProductDialog.kt`, l’analisi statica può segnalare errori (inclusi vincoli **Compose**), warning su import/operatori, warning “variabile assegnata mai letta”, e deprecazioni API Material3. **TASK-004** è stato rimesso in **`BACKLOG`** per dare priorità a questo cleanup mirato. In ambiente CI/sandbox locale la compilazione potrebbe non essere disponibile: in **EXECUTION** l’esecutore userà IDE + `./gradlew` come da `AGENTS.md`.

**Problema Compose (bottom sheet storico prezzi):** in `DatabaseScreen.kt` (righe ~228-238), i `collectAsState` sui flussi prezzi (`viewModel.getPriceSeries(...)`) risultano oggi **dentro** un branch condizionale (`if (showHistoryFor != null)`) — pattern potenzialmente segnalato dall'analisi statica / Compose compiler. Il composable `PriceHistoryBottomSheet` è **già estratto** in `DatabaseScreenDialogs.kt` (riga ~140) con signature `(product, purchase, retail, onDismiss)` — la strategia prescrittiva sotto riguarda **solo** lo spostamento delle chiamate `collectAsState` fuori dal branch condizionale in `DatabaseScreen.kt`, **non** l'estrazione di un composable già esistente.

**Decisione UX tab (richiamo):** default **Purchase** all’apertura; al cambio prodotto **ritorno a Purchase** (niente ereditarietà tab dal prodotto precedente). **Motivazione:** preservare la **baseline UX attuale** ed evitare stato **«sporco»** dopo l’estrazione del composable (dettaglio in criterio 2b, *Regression checks* e *Planning*).

---

## Non incluso

- Avvio o completamento di **TASK-004** (test unitari) o altri task backlog.
- Modifiche a **DAO**, **repository**, **ViewModel** (logica business), **`NavGraph.kt`**, entity, schema Room.
- Redesign UI, nuovi flussi, nuove stringhe/funzionalità (salvo stringhe strettamente necessarie a fix di compilazione — **non atteso** in questo perimetro).
- Refactor ampio “migrazione Material3” o bump dipendenze **salvo** quanto minimo e coperto dalla regola deprecazione swipe in questo task.
- Esecuzione in questo turno: **nessuna** modifica Kotlin, **nessuna** build.

### Regola anti-scope-creep (strutturale)

Se un warning o un fix richiede **refactor strutturale**, cambio di architettura o modifica di **API estesa** (oltre il minimo locale nei quattro file):

- **non** eseguirlo dentro **TASK-020**;
- **documentarlo** in **Execution** (descrizione, file, motivazione);
- **proporre follow-up** (nuovo task o voce backlog) per il lavoro più ampio.

**TASK-020** resta un cleanup mirato di static analysis su perimetro ristretto, non un contenitore di redesign o refactor profondo.

### Blocco micro-variazioni UX fuori scope

**TASK-020** **non** deve introdurre variazioni **visive** o **comportamentali** non necessarie al fix dell’analisi statica, **nemmeno** se piccole. Obiettivo: correggere static analysis mantenendo la **UX/UI percepita uguale** al pre-fix — **non** “migliorarla”.

Restano **invariati** salvo **necessità tecnica** strettamente dimostrata (es. impossibilità di compilare senza un cambio minimo documentato):

- **Tipo di contenitore UI** usato per lo storico prezzi (es. bottom sheet vs dialog — resta quello attuale).
- **Titoli** e **testi** (incluse **label** e **testo delle tab** purchase/retail).
- **Ordine delle azioni** nei flussi toccati dal perimetro.
- **Flusso di conferma** delete / edit (inclusi dialog di conferma).
- **Gerarchia visiva** percepita di dialog / sheet (spacing, struttura, primacy delle azioni — nessun polish intenzionale).

Qualsiasi deviazione da questa baseline va trattata come **fuori scope** o **eccezione**: documentare in **Execution** e preferire **follow-up** UX dedicato.

---

## File coinvolti

Percorsi attesi (package `ui/screens` — verifica nel **Preflight**):

| File | Percorso atteso | Ruolo |
|------|-----------------|--------|
| Schermata Database | `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` | Errori analysis (priorità); wiring schermata; `collectAsState` condizionale bottom sheet storico prezzi |
| Componenti lista / swipe | `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` | Lista prodotti, swipe dismiss; possibili warning Elvis/import |
| Dialog / bottom sheet Database | `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` | Contiene `PriceHistoryBottomSheet`, `LoadingDialog`, `DeleteProductConfirmationDialog` — già estratto da TASK-003 |
| Dialog modifica prodotto | `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt` | Dialog modifica prodotto; possibili import/variabili non usate |

**Nota perimetro (validazione 2026-03-28):** TASK-003 ha prodotto **quattro** file, non tre. `DatabaseScreenDialogs.kt` era mancante dal perimetro originale ed è stato aggiunto perché contiene `PriceHistoryBottomSheet` (target del fix Compose) e altri dialog usati da `DatabaseScreen`.

Altri file **solo** se l’analisi dimostra che l’origine della segnalazione è fuori da questi quattro — in quel caso documentare in **Execution** e **non** espandere il fix senza aggiornare il perimetro e senza violare la regola anti-scope-creep.

---

## Gate di ingresso / Preflight

**Prima di qualsiasi EXECUTION futura**, obbligatorio:

1. Verificare sul **branch corrente** che esistano i **quattro** file sopra (stessi path relativi alla root repo).
2. Confermare che la **topologia** corrisponda all’esito atteso di **TASK-003** (schermata Database decomposta in questi file).
3. Se la topologia **differisce** (file spostati/rinominati/mancanti rispetto al perimetro): **fermarsi**, annotare in **Execution** (subito, prima dei fix) la situazione reale vs attesa, e allinearsi con il planner **prima** di procedere con correzioni.

Questo gate evita di “riparare” segnalazioni su una base di file non allineata al task.

---

## Matrice di triage — tipo / fonte segnalazione

Usare in **EXECUTION** per non perdere tempo su warning **non riproducibili** o fuori perimetro. Per ogni voce: azione prescritta principale; le altre colonne indicano quando applicare l’alternativa.

| Tipo / fonte | Azione prescritta | Documentare | Falso positivo | Follow-up |
|--------------|-------------------|-------------|----------------|-----------|
| **Warning solo IDE** (non riproducibile con `lint` / `assembleDebug` sullo stesso commit) | Non inseguire oltre una verifica incrociata; **documentare** in Execution (messaggio, file, riga) | Sì — obbligo se si ignora | Sì — se l’IDE segnala ma compile/lint puliti e non c’è errore reale | Se blocca il team: task dedicato “allineamento toolchain/IDE” |
| **Errore Compose / compiler** | **Correggere** nel perimetro con diff minimo (priorità alta) | Solo se si sceglie workaround temporaneo | No — di solito è errore reale | Se il fix esce dal perimetro → anti-scope-creep |
| **Warning `lint` (Gradle)** | **Correggere** se low-risk e nel perimetro; altrimenti **documentare** o **follow-up** | Se non fixato: motivazione | Se noto falso positivo noto del plugin: **documentare** con riferimento | Refactor strutturale → nuovo task |
| **Errore `assembleDebug`** | **Correggere** se causato dalle modifiche del task o da errori nei file in perimetro | Se l’errore è fuori perimetro: evidenza + **follow-up** | Raro | Dipendenze/versioni → task infrastruttura |

**Regola operativa:** non dichiarare risolto un warning “solo IDE” senza aver tentato almeno una riproduzione con **lint** o **compile**; se non si riproduce, **documentare** e non consumare il budget del task su chasing infinito.

---

## Regola di diff hygiene / efficienza review

In **EXECUTION**, il **diff** deve restare il **più piccolo e locale possibile** rispetto al fix richiesto dall’analisi statica. Obiettivo: task **leggibile**, review **veloce**, rischio di regressione **più basso** (*review efficiency*).

**Evitare** (salvo stretta necessità collegata a un errore/warning documentato):

- **Formatting churn** (ri-indentazioni o riformattazioni di blocchi non toccati dal fix).
- **Riordino import** / cleanup import **non necessari** oltre a quanto **realmente segnalato** dall’analisi (rimozione import **unused** sì se confermato; riordino “cosmetico” no).
- **Rinomini gratuiti** di simboli, file o parametri.
- **Spostamenti di codice** non indispensabili al fix.
- **Creazione di nuovi file** oltre ai tre in perimetro, salvo **eccezione** già prevista nel task (es. quarto file UI per bottom sheet, documentata e motivata).

Se il tool IDE propone “ottimizzazioni” globali sul file, **rifiutare** ciò che espande il diff oltre il minimo.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 0 | **Preflight:** gate di ingresso eseguito e **documentato** in Execution (**quattro** path verificati sul branch; eventuale delta vs TASK-003 annotato prima di qualsiasi fix) | S / M | — |
| 1 | **Errori:** tutti gli **errori** di code analysis riprodotti sui **quattro** file nel perimetro sono **risolti** o, se dipendono da bug tool, documentati con evidenza (screenshot/log) e strategia | S / M | — |
| 2 | **Bottom sheet storico prezzi (Compose):** eliminato il pattern `collectAsState` **condizionale** in `DatabaseScreen` secondo la **strategia prescrittiva** in *Planning*; `PriceHistoryBottomSheet` in `DatabaseScreenDialogs.kt` **resta invariato** (già estratto da TASK-003); eventuale composable wrapper in `DatabaseScreen.kt` o `DatabaseScreenDialogs.kt`; `collectAsState` solo in posizione stabile (top-level o dentro composable non condizionale); **nessuna** business logic spostata nel composable | S / M | — |
| 2b | **Decisione UX tab bottom sheet (baseline da preservare):** il bottom sheet deve aprirsi di default sulla tab **Purchase**; se cambia il **prodotto selezionato**, la tab deve tornare coerentemente a **Purchase** e **non** ereditare stato dal prodotto precedente (es. non restare su **Retail** «appiccicata» al cambio prodotto). **Dismiss** e **riapertura** coerenti col pre-fix. **Motivazione:** preservare la **baseline UX attuale** ed evitare stato **«sporco»** dopo l’estrazione del composable. L’implementazione nel composable estratto deve **rispettare** questa scelta concreta (non un generico “comportamento deterministico”) | M | — |
| 3 | **Warning sicuri:** import non usati e Elvis **chiaramente** ridondanti rimossi o semplificati dove confermati dall’analisi | S | — |
| 4 | **Triage “never read”:** ogni occorrenza significativa è **corretta** (se problema reale) oppure **documentata** nel log Execution come falso positivo Compose/IDE (file + riga + motivazione), usando la *Matrice di triage* | S | — |
| 5 | **Deprecazione swipe:** valutata secondo *Planning* / *Handoff* / **Regola di abort** sotto; se **non** si fixa (incluso per **abort**): motivazione + **follow-up** in Execution; deprecazione lasciata **non risolta**; se si **fixa**: in Execution **tutte e tre** le evidenze — (a) versione Gradle/BOM, (b) API sostitutiva, (c) motivo località + **behavior-preserving**; senza una qualsiasi → **solo documentata** + follow-up | S | — |
| 5a | **Regola di abort (fix deprecazione swipe):** **non** eseguire il fix in **TASK-020** se **una qualsiasi** di queste condizioni: il fix richiede di toccare **più di un file**; oppure richiede cambi a **semantiche di gesture**, **soglie (threshold)**, **animazioni**, **direzioni swipe** o **comportamento percepito**; oppure introduce una **mini-migrazione Material3** non strettamente locale. In tal caso: **documentare** in Execution, lasciare la deprecazione **non risolta**, proporre **follow-up** dedicato. Obiettivo: rendere «locale e behavior-preserving» **non ambiguo** | S | — |
| 6 | `./gradlew assembleDebug` OK; `./gradlew lint` senza nuovi warning **introdotti** dal task (stesso baseline o migliore) — l’assemble deve coprire anche i **caller** di `EditProductDialog` (compilazione intero modulo app, non solo i quattro file) | B / S | — |
| 7 | Nessuna modifica a DAO/repository/ViewModel business/`NavGraph` (verifica diff) | S | — |
| 8 | **`EditProductDialog` (blast radius + API):** in **Execution**, nota esplicita che il dialog resta **compatibile** con gli usi esistenti (es. **`ImportAnalysisScreen`** e ogni altro caller nel modulo), senza refactor cross-screen; **signature stabile** (vedi criterio **8a**); cleanup senza regressioni sui caller | S / M | — |
| 8a | **Stabilità signature `EditProductDialog`:** restano **invariati** salvo necessità tecnica **documentata** in Execution: **nome** del composable, **signature pubblica**, **ordine dei parametri**, **eventuali default parameters**. Motivazione: componente **condiviso**, task = cleanup static analysis, niente **cambi API silenziosi** sui caller. Se una modifica alla signature risultasse **inevitabile** → **eccezione**: motivarla in Execution, **verificare** tutti i caller, trattare con **forte sospetto di scope creep** (preferire follow-up se non strettamente necessario al fix analysis) | S | — |
| 9 | **Diff hygiene:** il diff in review rispetta la *Regola di diff hygiene* (nessun formatting churn, nessun reorder import “cosmetico”, nessun rename o spostamento non necessario, nessun nuovo file oltre eccezione documentata); in **Execution**, breve nota che il diff è stato mantenuto minimale oppure motivazione per ogni eccezione | S / M | — |

### Regression checks minimi (obbligatori per DONE)

Il task **non** si considera **DONE** senza **smoke test manuali** post-fix su device/emulatore (o ambiente concordato), almeno:

1. **Bottom sheet storico prezzi:** apertura e chiusura (dati coerenti con prima del fix); **verifica obbligatoria** della **decisione UX tab** (criterio 2b): alla apertura la tab è **Purchase**; dopo **cambio prodotto** (o riapertura per altro prodotto, secondo il flusso attuale) la tab è di nuovo **Purchase**, **senza** ereditare Retail dal prodotto precedente; sequenza **dismiss → riapertura** coerente col pre-fix; nessun cambio a titoli/label/tab text o tipo contenitore (vedi *Blocco micro-variazioni UX*).
2. **Swipe delete:** swipe su riga prodotto → flusso con **dialog di conferma** invariato (conferma/annulla).
3. **`EditProductDialog`:** apertura da **DatabaseScreen** (lista), **salvataggio** e **annulla** (cancel/dismiss) senza regressioni evidenti; dove fattibile senza ampliare scope, **sanity** dello stesso dialog da un altro flusso noto (es. percorso **ImportAnalysis** se già usato oggi) — altrimenti affidarsi a **assembleDebug** + nota **Execution** sui caller.

Documentare in **Execution** esito di questi controlli (✅ / ⚠️ con motivazione se non eseguibili, per `AGENTS.md`).

Legenda tipi: B=Build, S=Static, M=Manual (IDE / device)

---

## Distinzione operativa (errori vs warning)

### Errori — da correggere (in scope EXECUTION)

- I **2 errori** (o l’elenco reale) segnalati dall’analisi su **`DatabaseScreen.kt`** — copia puntale in **Execution** al primo run IDE/compile.
- **Compose / compiler:** includono il pattern `collectAsState` nel branch `if (showHistoryFor != null)` in `DatabaseScreen.kt` righe ~228-238 — risolto con la strategia prescrittiva in *Planning* (wrapper composable o spostamento `collectAsState` a top-level; `PriceHistoryBottomSheet` in `DatabaseScreenDialogs.kt` **non** va riscritto; la baseline UX tab **Purchase** è già corretta).

### Warning sicuri — da correggere (in scope EXECUTION)

- **Import non utilizzati** sui file nel perimetro.
- **Elvis operator ridondante** dove il tipo è già non-null (es. `filter ?: ""` nel ramo in cui `filter` è già `String`).

### Warning da triageare

- **“Assigned value is never read”** (o equivalenti): usare la *Matrice di triage*; intervenire **solo** se indica codice morto o bug reale; altrimenti **documentare** come falso positivo.

### Warning da lasciare documentati (se applicabile)

- Deprecazioni **non** risolvibili con fix minimo locale → motivazione + task follow-up; se il fix swipe viola la **Regola di abort** (criterio **5a**) → **non** fixare in TASK-020, solo documentazione + follow-up.
- Warning **solo IDE** non riproducibili → **documentare**, non perseguire oltre la matrice.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **TASK-020** = unico **`ACTIVE`**; **TASK-004** → **`BACKLOG`** | Priorità utente: cleanup analysis prima dei test | 2026-03-27 |
| 2 | Perimetro = **quattro** file UI Database in *File coinvolti* + fix **locali** | No scope creep; nessun tocco a layer dati/nav | 2026-03-27 |
| 3 | Deprecazione swipe → fix **solo** se locale, piccolo, behavior-preserving, **senza** mini-migrazione Material3; se **Regola di abort** (criterio 5a) → **stop** nel task | «Locale / behavior-preserving» non ambiguo | 2026-03-27 |
| 4 | Preflight obbligatorio prima di EXECUTION | Allineamento topologia file vs TASK-003 | 2026-03-27 |
| 5 | Scelta API sostitutiva swipe (se mai): da **versione effettiva** in `gradle/libs.versions.toml` / `app/build.gradle.kts`, non ipotizzata | Evitare fix incompatibili col BOM reale | 2026-03-27 |
| 6 | Refactor strutturale → **fuori** TASK-020, documentato + follow-up | Anti-scope-creep | 2026-03-27 |
| 7 | Bottom sheet storico prezzi: tab default **Purchase**; al cambio prodotto tab → **Purchase** (no ereditarietà dalla selezione precedente) | Baseline UX attuale; evitare stato sporco post-estrazione composable | 2026-03-27 |
| 8 | Diff minimale + nessuna micro-variazione UX non necessaria | Review veloce; parity UX/UI percepita col pre-fix | 2026-03-27 |
| 9 | **`EditProductDialog`:** nessun cambio a nome / signature / ordine parametri / default salvo eccezione documentata + verifica caller | Evitare API break silenziosi su componente condiviso | 2026-03-27 |

---

## Planning (Claude)

### Gate di ingresso / Preflight (primo passo EXECUTION)

1. Verificare esistenza e path dei **quattro** file in *File coinvolti* sul branch di lavoro.
2. Se diverso dall’atteso: log in **Execution** + stop fino ad allineamento con planner.

### Analisi

1. Rieseguire code analysis (Android Studio / Compose compiler) sui **quattro** file; esportare elenco errori/warning classificato con la *Matrice di triage*.
2. **Bottom sheet storico prezzi — strategia prescrittiva e minimale (preferita):**
   - **Stato reale (validazione 2026-03-28):** il composable `PriceHistoryBottomSheet(product, purchase, retail, onDismiss)` è **già estratto** in `DatabaseScreenDialogs.kt` (riga ~140) da TASK-003. **Non** serve estrarre un nuovo composable. Il problema è **solo** che in `DatabaseScreen.kt` (righe ~228-238) le chiamate `collectAsState` su `viewModel.getPriceSeries(...)` sono dentro `if (showHistoryFor != null)`.
   - **Strategia fix:** creare in `DatabaseScreen.kt` (o in `DatabaseScreenDialogs.kt`, dove il composable già risiede) un **composable wrapper** (es. `PriceHistorySection(product, viewModel, onDismiss)`) che:
     - viene chiamato **sempre** (fuori dal branch condizionale) **oppure** contiene internamente i `collectAsState` in modo che il Compose compiler li consideri stabili;
     - delega la presentazione a `PriceHistoryBottomSheet` già esistente in `DatabaseScreenDialogs.kt`;
     - **non** duplica logica repository/DAO; **non** sposta business logic nel composable (solo binding UI ↔ stato già esposto dal ViewModel).
   - **Alternativa minima:** spostare i `collectAsState` a **top-level** in `DatabaseScreen` (sempre attivi, anche quando il bottom sheet non è visibile), passando i dati a `PriceHistoryBottomSheet` solo quando `showHistoryFor != null`. Più semplice ma raccoglie dati anche quando non servono — accettabile dato che `getPriceSeries` è un flusso Room leggero. **L’esecutore sceglie l’approccio** che produce il diff più piccolo e non introduce regressioni.
   - **Decisione UX tab (prescrittiva — baseline già corretta):** il bottom sheet **apre** sulla tab **Purchase** (tab 0) e al **cambio prodotto** la tab **torna a Purchase**. Questa è **già** la baseline attuale (`var tab by remember { mutableIntStateOf(0) }` in `PriceHistoryBottomSheet`, scoped al branch condizionale → reset automatico alla rientrata in composizione). Il fix **non** deve alterare questo comportamento: verificare in smoke che resti invariato.
   - Obiettivo: **nessun** `collectAsState` dentro branch condizionale in `DatabaseScreen`; dati e **UX percepita** **invariati** rispetto al pre-fix.
3. **`EditProductDialog.kt` — blast radius + signature:** il dialog è **condiviso**. Caller verificati (validazione 2026-03-28): **`DatabaseScreen.kt:207`**, **`ImportAnalysisScreen.kt:73`** e **`ImportAnalysisScreen.kt:86`** — tutti con signature `(product, viewModel, onDismiss, onSave)`. Cleanup solo static analysis; **non** cambiare nome, signature, ordine parametri o default **salvo** necessità tecnica documentata (criterio **8a**). L’esecutore deve: (a) **`assembleDebug`** su tutti i **caller**; (b) **Execution**: compatibilità usi attuali (**`ImportAnalysisScreen`**) **senza** refactor cross-schermata.
4. `DatabaseScreenComponents.kt`: Elvis ridondanti; **Nota swipe (validazione 2026-03-28):** il codice usa **già** `SwipeToDismissBox` / `SwipeToDismissBoxState` / `SwipeToDismissBoxValue` / `rememberSwipeToDismissBoxState` (l'API **nuova**, non la deprecata `SwipeToDismiss`). Con Compose BOM **`2026.03.01`** (da `gradle/libs.versions.toml`) questa è l'API corrente. **In Preflight/EXECUTION:** verificare se l'analisi statica segnala effettivamente una deprecazione; se **non** la segnala, la sezione deprecazione swipe si chiude con "nessuna azione necessaria — API già aggiornata" (documentare in Execution). Se la segnala: seguire la regola di abort e le tre evidenze come da planning originale.
5. **Regola di abort — fix deprecazione swipe (blindatura):** prima di qualsiasi implementazione, verificare che il fix **non** richieda: (a) modifiche in **più di un file**; (b) cambi a **semantiche gesture**, **threshold**, **animazioni**, **direzioni swipe** o **comportamento percepito**; (c) **mini-migrazione Material3** non strettamente locale. Se **sì** → **non** eseguire il fix in **TASK-020**; documentare in **Execution**, deprecazione **non risolta**, **follow-up** dedicato.
6. `EditProductDialog.kt`: import e assegnazioni; triage “never read” con matrice (vedi blast radius al punto 3). **Stabilità API:** mantenere **invariati** nome composable, signature pubblica, ordine parametri e default (criterio **8a**); ogni eccezione motivata in **Execution**, verifica **caller**, sospetto **scope creep**.

### Piano di esecuzione

0. **Preflight** (gate) — documentato in Execution.
0b. Per **tutta** l’EXECUTION: applicare la *Regola di diff hygiene* e il *Blocco micro-variazioni UX*; ogni deviazione va motivata in **Execution**.
1. Baseline: elenco errori/warning pre-fix (testo o screenshot) nel log **Execution**, con tipo/fonte per riga ove possibile.
2. Applicare fix **Compose** bottom sheet secondo strategia prescrittiva (punto 2 di *Analisi*), inclusa **decisione UX tab Purchase** e reset al cambio prodotto.
3. Correggere gli altri **errori** nel perimetro.
4. Applicare fix **sicuri** (import, Elvis).
5. Triage warning “never read” e warning solo-IDE secondo matrice.
6. Dopo modifiche a `EditProductDialog`: confermare compilazione **caller**; nota **Execution** su compatibilità caller; verificare **nessun** cambio a nome/signature/ordine/default salvo eccezione **8a** (nessun ampliamento perimetro oltre cleanup).
7. Deprecazione swipe: applicare **prima** la **Regola di abort** (*Planning* punto 5 / criterio **5a**); se abort → **stop**, documentazione + follow-up, **nessun** fix nel task. Solo se **non** in abort: tentativo **solo** se locale + behavior-preserving + nessuna mini-migrazione Material3; in **Execution** le **tre evidenze** obbligatorie (*Handoff*); **senza** le tre → deprecazione **non risolta**, solo documentazione + follow-up.
8. `assembleDebug` + `lint`; tabella check obbligatori `AGENTS.md`.
9. **Regression checks minimi** (sezione *Criteri di accettazione*) — obbligatori per dichiarare lavoro completabile verso **DONE** (inclusa verifica UX bottom sheet e nota blast radius dialog).

### Rischi identificati (nel planning)

- **Regressione UX** su swipe-to-delete o bottom sheet — mitigare con *Regression checks minimi* obbligatori; per il bottom sheet, rischio violazione della **decisione tab Purchase** (default + reset al cambio prodotto) — mitigare con criterio 2b e smoke; rischio **micro-variazioni** non volute — mitigare con *Blocco micro-variazioni UX* e diff minimi (*diff hygiene*).
- **`EditProductDialog` condiviso** — regressioni **laterali** su caller (`ImportAnalysisScreen`, ecc.) pur restando nel perimetro dei **quattro** file — mitigare con `assembleDebug` modulo app + nota **Execution** su compatibilità caller + **stabilità signature** (criterio **8a**); senza refactor cross-screen.
- **Falsi positivi / warning IDE-only** — mitigare con *Matrice di triage*, non con refactor inventati.
- **Scope creep** da warning che richiedono architettura — mitigare con regola anti-scope-creep + follow-up.
- **API swipe:** il codice usa **già** `SwipeToDismissBox` (API non deprecata con BOM 2026.03.01); la deprecazione potrebbe non essere un problema reale — verificare in Preflight/Execution; se non segnalata dall'analisi, chiudere senza azione.
- **Fix swipe che viola Regola di abort** (multi-file, gesture/threshold/animazioni/direzioni/comportamento percepito, mini-migrazione) — **non** eseguire in TASK-020; mitigare con documentazione + follow-up (criterio 5a).
- **Cambio silenzioso signature `EditProductDialog`** — mitigare con criterio **8a**; eccezione solo documentata + verifica caller + review scope creep.

---

## Rischi identificati

| Rischio | Probabilità | Impatto | Mitigazione |
|---------|-------------|---------|-------------|
| Fix Compose altera timing o dati storico prezzi | Media | Medio | Strategia prescrittiva (solo spostamento composizione); smoke bottom sheet obbligatorio |
| Tab bottom sheet non su **Purchase** all’apertura o eredità tab errata al cambio prodotto | Media | Medio | Decisione UX prescrittiva in Planning + criterio 2b + smoke; parity testi/contenitore |
| Diff “rumoroso” (format/reorder/rename) o micro-variazioni UX non necessarie | Media | Medio | *Regola di diff hygiene* + *Blocco micro-variazioni UX*; criterio 9 |
| Cleanup `EditProductDialog` rompe caller (`ImportAnalysisScreen` o altri) | Bassa | Alto | `assembleDebug` app; nota Execution su compatibilità caller; niente refactor fuori perimetro |
| Migrazione API swipe più ampia del previsto | **Bassa** (il codice usa già `SwipeToDismissBox`, API non deprecata con BOM 2026.03.01) | Medio | Verificare in Preflight se l'analisi segnala effettivamente deprecazione; se sì: Stop → documentare + follow-up; se no: chiudere senza azione |
| Fix swipe **non** ammissibile (abort: >1 file, gesture/threshold/animazioni/direzioni, percezione, mini-migrazione) | **Bassa** (probabile non necessario) | Alto | **Regola di abort** (5a): nessun fix in TASK-020; follow-up |
| Modifica non necessaria a signature `EditProductDialog` | Bassa | Alto | Criterio **8a**; eccezione solo documentata + caller + sospetto scope creep |
| Tempo perso su warning solo-IDE non riproducibili | Media | Basso | Matrice di triage; documentare e chiudere |
| Topologia file ≠ attesa TASK-003 | **Mitigato** (validazione 2026-03-28: 4 file confermati sul branch) | Alto | Preflight gate prima di qualsiasi fix |
| Tentativo di “fix” che diventa refactor strutturale | Media | Alto | Regola anti-scope-creep; proporre nuovo task |
| Toolchain locale assente in review | Bassa | Basso | Evidenza build + smoke dall’esecutore per avanzare verso **DONE** |

---

## Execution

### Esecuzione — 2026-03-28

**Preflight:**
- Verificati sul branch corrente i quattro path richiesti dal task: `DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, `EditProductDialog.kt` sotto `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/`.
- Topologia confermata coerente con l’esito atteso di **TASK-003**: schermata Database decomposta in quattro file; `PriceHistoryBottomSheet` presente in `DatabaseScreenDialogs.kt`; `EditProductDialog` componente condiviso separato.
- Caller `EditProductDialog` verificati in lettura prima dei fix: `DatabaseScreen.kt:207`, `ImportAnalysisScreen.kt:73`, `ImportAnalysisScreen.kt:86`.
- Working tree iniziale già sporca lato documentazione (`docs/MASTER-PLAN.md`, `docs/TASKS/TASK-004-copertura-test-unitari-repository-e-viewmodel.md`, file task `TASK-020` non tracciato): nessun intervento su modifiche non correlate.

**Baseline analisi pre-fix / matrice di triage:**
| File | Fonte | Segnalazione | Triage | Esito |
|------|-------|--------------|--------|-------|
| `DatabaseScreen.kt` | `lint` | `LocalContextGetResourceValueCall` a righe 149 e 188 | Errore reale in perimetro | **Fixato** precomputando `stringResource(...)` in posizione composable stabile |
| `DatabaseScreen.kt` | IDE / Compose analysis (planning task) | `collectAsState` dentro branch condizionale per bottom sheet storico prezzi | Errore/concern Compose in perimetro, anche se non riprodotto da Gradle | **Fixato** con wrapper composable locale `PriceHistoryBottomSheetHost` |
| `DatabaseScreenComponents.kt` | `compileDebugKotlin --warning-mode all` | Elvis ridondante in messaggio “no results” (`filter ?: ""`) | Warning sicuro | **Fixato** rimuovendo l’Elvis ridondante |
| `DatabaseScreenComponents.kt` | `compileDebugKotlin --warning-mode all` | Deprecazione `rememberSwipeToDismissBoxState(confirmValueChange=...)` a riga 192 | Warning reale ma soggetto a regola di abort | **Non fixato** in TASK-020; documentato + follow-up |
| `DatabaseScreenDialogs.kt` | `lint` + `compileDebugKotlin` | Nessuna segnalazione riprodotta nel perimetro | Nessuna azione | Invariato |
| `EditProductDialog.kt` | `lint` + `compileDebugKotlin` | Nessuna segnalazione riprodotta nel perimetro | Nessuna azione | Invariato; signature/caller preservati |

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — introdotto wrapper composable locale per lo storico prezzi; rimossi accessi `context.getString(...)` in composizione per chiudere gli errori lint locali.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — rimosso Elvis ridondante sul testo “no results”.
- `docs/TASKS/TASK-020-cleanup-code-analysis-post-task003.md` — aggiornato stato task, log execution, criteri, follow-up.

**Azioni eseguite:**
1. Eseguito il **Preflight** obbligatorio: conferma topologia reale a quattro file e verifica caller di `EditProductDialog`.
2. Raccolta baseline con `assembleDebug`, `lint` e `compileDebugKotlin --rerun-tasks --warning-mode all` usando `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
3. Applicato in `DatabaseScreen.kt` un wrapper composable locale `PriceHistoryBottomSheetHost(product, viewModel, onDismiss)` per spostare `collectAsState` fuori dal branch condizionale della schermata, lasciando **invariato** `PriceHistoryBottomSheet` in `DatabaseScreenDialogs.kt`.
4. Nel wrapper è stato usato `key(product.id)` per forzare la ricreazione del bottom sheet al cambio prodotto e preservare il reset della tab su **Purchase** senza cambiare testi, contenitore, ordine azioni o gerarchia visiva.
5. Corretti i due errori lint locali in `DatabaseScreen.kt` sostituendo gli accessi a `context.getString(...)` con valori derivati da `stringResource(...)` in posizione composable stabile.
6. Corretto il warning sicuro in `DatabaseScreenComponents.kt` rimuovendo l’Elvis ridondante sul filtro.
7. Valutata la deprecazione swipe leggendo la toolchain reale (`composeBom = 2026.03.01` in `gradle/libs.versions.toml`, `implementation(libs.androidx.material3)` in `app/build.gradle.kts`): la warning riguarda `confirmValueChange` su `rememberSwipeToDismissBoxState`, non il vecchio `SwipeToDismiss`. Applicata la **Regola di abort** e lasciato follow-up dedicato, perché il pattern è presente anche fuori perimetro (`HistoryScreen.kt`) e la sostituzione richiede rivalutazione delle semantiche gesture/anchor per restare behavior-preserving.
8. Verificata la compatibilità di `EditProductDialog` senza toccarne nome/signature/ordine/default: caller invariati e coperti da `assembleDebug` dell’intero modulo app.

**Regression checks minimi:**
| Check | Stato | Note |
|-------|-------|------|
| Bottom sheet storico prezzi: apertura/chiusura + default/reset tab Purchase | ⚠️ NON ESEGUIBILE | Nessun device/emulatore disponibile nel terminale; `adb` non presente nel `PATH`. Verifica statica: `PriceHistoryBottomSheet` invariato; reset al cambio prodotto garantito dal `key(product.id)` nel wrapper |
| Swipe delete + dialog conferma | ⚠️ NON ESEGUIBILE | Nessun device/emulatore disponibile nel terminale; `adb` non presente nel `PATH` |
| `EditProductDialog` da DatabaseScreen + sanity caller aggiuntivi | ⚠️ NON ESEGUIBILE | Nessun device/emulatore disponibile; copertura statica tramite caller verificati e `assembleDebug` modulo app |

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` finale OK (`BUILD SUCCESSFUL in 7s`) |
| Lint | ✅ ESEGUITO | `lint` eseguito; baseline progetto ancora fallente fuori scope (`25 errors`, `64 warnings`), ma dopo il fix il report non contiene più occorrenze dei quattro file di TASK-020 |
| Warning nuovi | ✅ ESEGUITO | Nessun warning nuovo introdotto nei file modificati; resta documentata solo la deprecazione swipe preesistente/non fixata per abort |
| Coerenza con planning | ✅ ESEGUITO | Applicati solo fix locali previsti: wrapper bottom sheet, lint locale, Elvis ridondante, triage/follow-up swipe |
| Criteri di accettazione | ✅ ESEGUITO | Tutti i criteri registrati sotto con stato ed evidenza; i smoke manuali richiesti risultano **NON ESEGUIBILI** in questo ambiente |

**Criteri di accettazione — dettaglio finale:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 0 | Preflight documentato | ESEGUITO | Quattro path verificati; topologia coerente con TASK-003; caller `EditProductDialog` riletti prima dei fix |
| 1 | Errori di code analysis nel perimetro risolti o documentati | ESEGUITO | Rimossi i 2 errori lint di `DatabaseScreen.kt`; issue Compose su `collectAsState` condizionale eliminata via wrapper locale |
| 2 | Bottom sheet storico prezzi: niente `collectAsState` condizionale in `DatabaseScreen`; `PriceHistoryBottomSheet` invariato | ESEGUITO | `DatabaseScreen.kt` usa `PriceHistoryBottomSheetHost`; `DatabaseScreenDialogs.kt` invariato |
| 2b | Baseline UX tab Purchase preservata | NON ESEGUIBILE | Ambiente senza `adb` / emulatore; verifica statica favorevole: `key(product.id)` forza reset stato al cambio prodotto e `PriceHistoryBottomSheet` resta invariato |
| 3 | Warning sicuri corretti | ESEGUITO | Elvis ridondante rimosso in `DatabaseScreenComponents.kt`; nessun import unused riprodotto nei check Gradle |
| 4 | Triage “never read” | ESEGUITO | Nessuna occorrenza significativa riprodotta nei quattro file con `lint` / `compileDebugKotlin`; nessun fix aggiuntivo necessario |
| 5 | Deprecazione swipe valutata e documentata secondo planning | ESEGUITO | Warning reale confermata da `compileDebugKotlin`; documentato abort + follow-up invece del fix |
| 5a | Regola di abort swipe applicata correttamente | ESEGUITO | Pattern presente anche in `HistoryScreen.kt` fuori scope e sostituzione richiede revisione comportamento gesture/anchor, quindi fix rinviato |
| 6 | `assembleDebug` OK; `lint` senza regressioni introdotte dal task | ESEGUITO | `assembleDebug` OK; `lint` migliora il baseline rimuovendo le occorrenze in `DatabaseScreen.kt`; nessuna occorrenza residua nei file del task |
| 7 | Nessuna modifica a DAO/repository/ViewModel business/`NavGraph` | ESEGUITO | Diff limitato a `DatabaseScreen.kt`, `DatabaseScreenComponents.kt` e task doc |
| 8 | `EditProductDialog` compatibile con i caller attuali | ESEGUITO | File non modificato; caller riletti in `DatabaseScreen.kt` e `ImportAnalysisScreen.kt`; compilazione modulo app OK |
| 8a | Signature `EditProductDialog` stabile | ESEGUITO | Nessun cambio a nome, signature pubblica, ordine parametri o default |
| 9 | Diff hygiene rispettata | ESEGUITO | Nessun formatting churn, nessun rename/spostamento gratuito, nessun nuovo file Kotlin |

**Incertezze:**
- INCERTEZZA: i regression manuali richiesti dal task non sono stati eseguiti perché l’ambiente terminale non espone `adb` / device / emulatore; la preservazione UX è verificata solo staticamente.

**Handoff notes:**
- Eseguire gli smoke manuali richiesti dal task appena disponibile un device/emulatore: bottom sheet storico prezzi (default/reset tab **Purchase**), swipe delete con dialog conferma, `EditProductDialog` da DatabaseScreen.
- Se si vuole chiudere anche la deprecazione swipe, aprire follow-up dedicato: la migrazione richiede decisione esplicita su semantiche gesture/anchor e andrebbe allineata almeno tra `DatabaseScreenComponents.kt` e `HistoryScreen.kt`.

---

## Review

**Chiusura operativa:** **DONE** dichiarato su **decisione utente** (2026-03-28), con **smoke manuali** del task **non eseguiti** nel contesto documentato in **Execution** (⚠️ NON ESEGUIBILE). Nessuna review formale tabellare separata richiesta oltre a questa nota e al **Riepilogo finale**.

---

## Fix

_(Se necessario.)_

---

## Chiusura

| Campo                  | Valore |
|------------------------|--------|
| Stato finale           | **DONE** |
| Data chiusura          | **2026-03-28** |
| Modalità               | **Chiusura su decisione utente** (consapevole): fix tecnici completati e documentati in **Execution**; **smoke manuali** richiesti dal planning **non eseguiti** nel contesto documentato (⚠️ in **Execution**) — trattati come **note operative residue**, **non bloccanti** per questa chiusura |
| Tutti i criteri ✅?    | **Sì**, con ⚠️ **NON ESEGUIBILE** documentato per i regression check manuali (device/emulatore non disponibili in sessione); criterio **2b** (UX tab) verificato solo in modo **statico** (vedi **Execution**) |
| Rischi residui         | **Noti:** (1) smoke su bottom sheet storico prezzi, swipe delete + dialog, `EditProductDialog` da eseguire quando disponibile device/emulatore; (2) deprecazione `rememberSwipeToDismissBoxState(confirmValueChange=...)` **non risolta** — follow-up possibile (`DatabaseScreenComponents.kt` + `HistoryScreen.kt`) |

---

## Riepilogo finale

- **Scope:** cleanup code analysis su quattro file UI Database post-**TASK-003**; fix locali su `DatabaseScreen.kt` (wrapper `PriceHistoryBottomSheetHost`, lint `stringResource`), `DatabaseScreenComponents.kt` (Elvis ridondante); triage swipe con **Regola di abort** e follow-up documentato.
- **Evidenze:** `assembleDebug` OK; `lint` / `compile` documentati in **Execution** (2026-03-28).
- **Chiusura:** **DONE** per **decisione utente** nonostante smoke manuali **non** eseguiti nel contesto documentato — **nessuna falsificazione** dello storico: vedi tabella regression in **Execution** e campo **Chiusura** sopra.
- **Prossimo operatore:** **`TASK-004`** è l’unico **`ACTIVE`** nel `MASTER-PLAN`; eseguire smoke residue su TASK-020 a titolo di sanity opzionale; valutare follow-up swipe/Material3.

---

## Handoff

_(Task **chiuso** — quanto segue è per il **prossimo operatore** / backlog.)_

- **TASK-004** è ora il task **`ACTIVE`** nel `MASTER-PLAN` (test unitari repository/ViewModel) — vedi `docs/TASKS/TASK-004-copertura-test-unitari-repository-e-viewmodel.md`.
- **Rischio residuo TASK-020:** eseguire quando possibile gli **smoke manuali** elencati in **Execution** (bottom sheet tab Purchase, swipe delete + conferma, `EditProductDialog`) — **non bloccanti** per la chiusura già avvenuta.
- **Follow-up tecnico:** migrazione deprecazione swipe / `confirmValueChange` con scope esplicito (`DatabaseScreenComponents.kt` + `HistoryScreen.kt`), come da **Execution**.
- **Storico execution:** dettaglio fix, check Gradle, criteri e incertezze restano nella sezione **Execution** (2026-03-28) — **non** alterare retroattivamente quel log.
