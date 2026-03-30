# TASK-014 — UX modernization GeneratedScreen

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-014                   |
| Stato                | **DONE**                   |
| Priorità             | MEDIA                      |
| Area                 | UX / UI / GeneratedScreen  |
| Creato               | 2026-03-29                 |
| Ultimo aggiornamento | 2026-03-29 (review planner APPROVED + fix overlap BoxWithConstraints + chiusura DONE; smoke manuali pendenti come rischio residuo) |
| Tracking `MASTER-PLAN` | **`DONE`** (2026-03-29) — review planner APPROVED, fix applicato, build/lint OK. Smoke manuali non eseguibili come rischio residuo documentato. |

---

## Dipendenze

- TASK-001 (DONE)
- **TASK-002** (**`BLOCKED`**) — decomposizione tecnica `GeneratedScreen`. **Autorizzazione utente ricevuta** (2026-03-29): il blocco formale da TASK-002 è superato; l’utente ha autorizzato esplicitamente la ripresa UX sullo stesso perimetro. La decomposizione tecnica (build/lint OK) è già nel codice; lo smoke manuale pendente su TASK-002 non blocca il lavoro UX di TASK-014.
- **TASK-009** (**`DONE`**) — il blocco governance residuo era solo di tracking; riallineato all’avvio di questa execution in coerenza con il file task di TASK-009 e con l’autorizzazione utente ricevuta.

---

## Scopo

Modernizzare **toolbar**, **dialog/sheet**, **affordance di riga** e **leggibilità della griglia** della schermata generata (Excel editing), in linea con Material3 / Compose idiomatico. **Nessun** cambio alla logica business (editing, export, sync, rename, search, scan, completamento righe). **Nessuna** rimozione di funzionalità esistenti.

---

## Contesto (baseline codice — lettura 2026-03-29)

- **`GeneratedScreen.kt`**: `Scaffold` con `GeneratedScreenTopBar` (`TopAppBar` + `TopInfoChipsBar` a chip scrollabile + `HorizontalDivider`); azioni condizionate a `excelData` non vuoto e `generated`: Home, Sync con badge, menu overflow (export, share, rename). Titolo con tap/marquee. Griglia via `GeneratedScreenGridHost` → `ZoomableExcelGrid` (**120×48 dp**). FAB (scanner + search). Molti **`AlertDialog`** inline: info riga (molto denso), rename, header custom/type, calcolatrice, manual entry, ecc.
- **`GeneratedScreenDialogs.kt`** (estratti **TASK-002**): dialog uscita / home / history / search — `AlertDialog` classici.
- **`ZoomableExcelGrid.kt`**: header tipizzati; righe `generated` / `manual` / `editMode`; colonna completo con `Box` + `Icon`; divider tra righe.
- **`TableCell.kt`**: tipografia celle/header, stati colore, click.
- **`ExcelViewModel.kt`**: **sola lettura** salvo necessità reale documentata in Execution.

---

## Riferimento iOS (repository GitHub pubblico `iOSMerchandiseControl`)

Allineato a **`MASTER-PLAN.md`**, **`AGENTS.md`**, **`TASK-008`**: la controparte iOS è il repository **`iOSMerchandiseControl`**, qui trattato come **repository GitHub pubblico di riferimento UX/UI** (guida visiva / gerarchia / spacing — **non** porting 1:1 e **non** fonte di logica Android).

**Punto operativo all’avvio di Execution:** aprire la repo **`iOSMerchandiseControl`** (clone, browse su GitHub, o IDE collegato) e **leggere i file Swift effettivi** elencati sotto, aggiornando in Execution solo se la struttura del repo è cambiata (rinomina / split file).

**Riferimenti file minimi confermati:**

| Priorità | File | Ruolo |
|----------|------|--------|
| 1 | **`GeneratedView.swift`** | Equivalente funzionale di `GeneratedScreen` — toolbar, griglia, flusso principale. |
| 2 | **`PreGenerateView.swift`** | Equivalente di `PreGenerateScreen` e anteprima tabella. |
| 3 | **`RowDetailSheetView`** (invocato da `GeneratedView.swift:2296` via `.sheet(item: $rowDetail)`) | **Dettaglio riga** (equivalente di `GeneratedScreenInfoDialog` Android): sheet con campi editabili, toggle complete, navigazione riga-a-riga, delete, scan next. **Nota:** `EntryInfoEditor.swift` è l’editor **metadati entry** (title/supplier/category) — equivalente Android di `GeneratedScreenRenameDialog`, **non** del dettaglio riga. |

### `GeneratedView.swift` → confronto con `GeneratedScreen`

| Aspetto | Cosa osservare (UX) nel file reale | Cosa adattare in Compose | Cosa **non** portare 1:1 |
|---------|-----------------------------------|---------------------------|---------------------------|
| Toolbar / azioni | Ordine percepito primario vs secondario, overflow o azioni visibili, icone e chiarezza | `TopAppBar`, menu overflow M3, `contentDescription`, separatori nel menu | Struttura `NavigationStack` / toolbar items SwiftUI; ridurre azioni Android per allineare iOS |
| Griglia / righe | Leggibilità header, righe, stati “completo”, tap affordance | Tipografia, divider, colori da `ColorScheme`, touch target | Layout colonne identico pixel-perfect; logica colonne qty/prezzo/completo (resta Android) |
| Apertura dettaglio riga | Come `GeneratedView` invoca l’editor (sheet, fullScreenCover, navigazione) — leggere il **file target** trovato al passo 3 sopra | `ModalBottomSheet` / `AlertDialog` scrollabile, sezioni, focus su Android | Binding `@State` iOS; ordine campi se rompe flussi VM Android |
| Feedback | Conferme, errori, progress | Snackbar/dialog già esistenti sul flusso Android | Testi o canali copiati alla lettera dall’iOS |

### `PreGenerateView.swift` → confronto con `PreGenerateScreen`

| Aspetto | Cosa osservare (UX) nel file reale | Cosa adattare in Compose | Cosa **non** portare 1:1 |
|---------|-----------------------------------|---------------------------|---------------------------|
| Anteprima tabella | Header, celle, selezione colonne, scroll orizzontale/verticale | Coerenza visiva con **stessi** `ZoomableExcelGrid` / `TableCell` **dopo** Fase C (se toccati) | Duplicare comportamenti iOS che confliggono con filtri/supplier Android |
| Stati vuoto / loading | Chiarezza messaggi | Allineamento tono con `PreGenerateScreen` esistente | Sostituire flussi PreGenerate Android |

### `RowDetailSheetView` (dettaglio riga — confermato in review planning 2026-03-29)

**Verificato:** il dettaglio riga iOS è `RowDetailSheetView`, invocato da `GeneratedView.swift:2296` via `.sheet(item: $rowDetail)`. **`EntryInfoEditor.swift`** è invece l’editor metadati entry (title/supplier/category), equivalente Android di `GeneratedScreenRenameDialog`.

| Aspetto | Cosa osservare (UX) nel sorgente iOS | Cosa adattare in Compose | Cosa **non** portare 1:1 |
|---------|--------------------------------------|---------------------------|---------------------------|
| Presentazione | `.sheet(item:)` con altezza dinamica | `ModalBottomSheet` M3, `rememberModalBottomSheetState` | Presentazione iOS che nasconde azioni obbligatorie su Android |
| Contenuto | Raggruppamento (qty/prezzo, metadati prodotto), scroll | `HorizontalDivider`, titoli sezione, padding | Ridurre campi rispetto ad Android |
| **Feature iOS-only (fuori scope B1)** | Navigazione riga-a-riga (`onNavigateToRow`), delete row, open product editor, price history dal dettaglio | **Non importare** — scope creep; eventuale follow-up in task dedicato | Tutta la navigazione intra-sheet e le azioni distruttive/cross-screen |

**Regola trasversale:** iOS = **guida UX/UI** su file reali letti in **`iOSMerchandiseControl`**; Android = **logica, navigazione, stringhe di business, perimetro feature** (`CLAUDE.md` / `AGENTS.md`).

---

## Guardrail UX/UI (operativi)

1. **Chip informativi** (`TopInfoChipsBar`): **non devono sembrare cliccabili** se non eseguono alcuna azione — evitare componenti che per default comunicano affordance di tap (es. `AssistChip` con aspetto interattivo e `onClick` vuoto). Preferire varianti read-only / disabilitate / stile “badge” coerente M3.
2. **Toolbar e overflow**: maggiore **chiarezza gerarchica** e leggibilità (icone, etichette, `contentDescription`, eventuali separatori nel menu) **senza rimuovere** export, share, rename, sync, home, back, rename dal menu dove oggi esistono.
3. **Dialog / sheet dettaglio riga** (`GeneratedScreenInfoDialog` e flussi collegati): **priorità UX assoluta del task** — ridurre densità, migliorare scansionabilità e accesso tastiera, senza cambiare contratti di salvataggio / `updateHistoryEntry` / complete toggle.
4. **`CalculatorDialog` e `ManualEntryDialog`**: **solo** layout, spacing, scroll, dimensioni responsive dei controlli — **nessun** cambio a validazione, flusso dati, callback verso `ExcelViewModel`, ordine obbligatorio dei passi utente, né testi di business logic.

---

## Guardrail navigazione (vincolo forte)

- TASK-014 **non deve** modificare **route**, **argomenti di navigazione** né la **semantica degli ingressi** verso `GeneratedScreen`.
- Preservare gli **ingressi attuali** verso Generated:
  1. da **PreGenerate** (flusso file / generazione griglia),
  2. da **History** (replay entry),
  3. da **manual add** (entry manuale).
- Preservare i parametri esposti al composable **`GeneratedScreen`**: **`entryUid`**, **`isNewEntry`**, **`isManualEntry`** (nomi e significato invariati; nessun rename o riuso ambiguo).
- Qualsiasi cambio a **`NavGraph.kt`**, destinazioni, `popUpTo`, argomenti serializzati o wiring ViewModel↔route è **fuori scope** salvo **emergenza reale** (bug bloccante) **documentata** in Execution con approvazione esplicita e aggiornamento governance.

---

## Guardrail performance

- **Nessun** polish che peggiori **scroll** della griglia, **densità di recomposizione** percepita o **reattività** al tap (evitare `remember` inutili su path caldi, liste non lazy al posto di lazy, o effetti che invalidano l’intera griglia ad ogni frame).
- **Nessuna animazione decorativa** dentro **celle** o **header** della griglia (niente pulse, shimmer, transizioni continue su `TableCell` / righe `ZoomableExcelGrid`).
- **Nessuno spostamento di logica business** nei composable: stato e operazioni restano in **ViewModel** / layer esistente.
- **`ExcelViewModel.kt`**: **read-only** per default; ogni eccezione va **motivata** nel log Execution e richiede baseline **TASK-004** se si altera comportamento testato.

---

## Regola preferenziale sui componenti condivisi

> Se un miglioramento UX su **Generated** può essere ottenuto **solo** modificando **`GeneratedScreen.kt`** / **`GeneratedScreenDialogs.kt`** (es. padding del host, parametri già passati alla griglia, dialog), **preferire quella strada** rispetto a toccare **`ZoomableExcelGrid.kt`** / **`TableCell.kt`**.

---

## Superficie di regressione — componenti condivisi (Fase C)

Se in **Fase C** si modificano **`ZoomableExcelGrid.kt`** o **`TableCell.kt`**, la **superficie di regressione non è limitata a Generated**:

- **`PreGenerateScreen`** usa la stessa griglia / stessi componenti per l’**anteprima** e l’interazione su header e selezione colonne (e affordance tap coerenti con il resto dell’app).
- **`ImportAnalysisScreen`** / altre schermate elencate in **TASK-016** possono indirettamente risentire degli stessi componenti.

**Obbligo:** dopo ogni merge che tocca `ZoomableExcelGrid` o `TableCell`, eseguire lo smoke **PreGenerate** indicato nella matrice (**A9**) oltre ai test Generated, e documentare l’esito in Execution.

---

## Coordinamento con TASK-016 (anti doppio lavoro)

- **TASK-016** (backlog) include esplicitamente **`ZoomableExcelGrid.kt`** e **`TableCell.kt`** per **History / ImportAnalysis**, non per Generated in prima battuta. **PreGenerate** non è nel titolo di TASK-016 ma **dipende dagli stessi componenti**: qualsiasi edit in Fase C impatta **Generated + PreGenerate + altre schermate** che riusano la griglia — il piano anti-doppio-lavoro con TASK-016 vale anche per **non** rifare due volte lo stesso tuning visivo su `TableCell` / `ZoomableExcelGrid`.
- **Prima** di modificare file condivisi in **Fase C**: rileggere `docs/TASKS/TASK-016-*.md` (o backlog aggiornato); annotare nel log **Execution** di TASK-014 **file condivisi toccati** e **motivo** (perché impossibile raggiungere l’obiettivo solo da Generated).
- Se TASK-016 è attivo o in esecuzione in parallelo nello stesso sprint, **coordinare** con il planner chi proprietà il primo merge su `TableCell`/`ZoomableExcelGrid` per ridurre conflitti.
- Obiettivo: **interventi minimi** sui condivisi, **incrementali**, e **documentati** per un eventuale follow-up TASK-016 senza ripetere lo stesso redesign.

---

## Non incluso

- Modifiche a business logic, DAO, repository, Room.
- **`NavGraph.kt` / navigazione** — vedi **Guardrail navigazione**; fuori scope salvo emergenza documentata.
- Rimozione o semplificazione funzionale di export, share, sync, rename, search, scan, manual entry, header column flow.
- Refactor architetturale ampio del ViewModel o astrazioni UI non necessarie.
- Test JVM **salvo** tocco reale a `ExcelViewModel` → baseline **TASK-004** in Execution.

---

## File potenzialmente coinvolti (Execution futura)

- `.../ui/screens/GeneratedScreen.kt`
- `.../ui/screens/GeneratedScreenDialogs.kt`
- `.../ui/components/ZoomableExcelGrid.kt` — **Fase C**, solo se necessario
- `.../ui/components/TableCell.kt` — **Fase C**, solo se necessario
- `app/src/main/res/values*/strings.xml`
- `ExcelViewModel.kt` — **read-only** salvo eccezione documentata

---

## Stringhe e localizzazione (minimizzare churn)

- **Preferire il riuso** di stringhe e chiavi **`strings.xml`** già esistenti (rinominare testo solo se il copy cambia in modo sostanziale e resta coerente con il significato della chiave).
- **Nuove chiavi** solo se **indispensabili** al miglioramento UX (es. nuovo `contentDescription`, titolo sezione) — evitare duplicati semantici.
- Se si aggiungono chiavi nuove: aggiornare **subito** tutte le localizzazioni previste dal progetto (**en, it, es, zh** o set reale sotto `values*`) nello stesso task, per non lasciare fallback incoerenti.

---

## Criteri di accettazione (alta livello)

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Toolbar, overflow e chip informativi allineati ai guardrail UX sopra | M | — |
| 2 | Dialog/sheet (in particolare dettaglio riga) più usabili, stessi esiti funzionali | M | — |
| 3 | Griglia almeno invariata o migliorata in leggibilità/affordance, senza cambiare semantica celle | M | — |
| 4 | Nessuna regressione sui flussi elencati nella **matrice di verifica** sotto (incluso **A9** se Fase C tocca componenti condivisi) | M | — |
| 4bis | **Navigazione:** ingressi PreGenerate / History / manual add invariati; `entryUid`, `isNewEntry`, `isManualEntry` invariati | M | — |
| 5 | Rispetto guardrail **performance** | M + S | — |
| 6 | `./gradlew assembleDebug` OK | B | — |
| 7 | `./gradlew lint` senza nuovi warning dal task | S | — |

Legenda: B=Build, S=Static, M=Manual

> Checklist aggiuntiva: *Definition of Done — task UX/UI* in `docs/MASTER-PLAN.md`.

---

## Matrice di verifica manuale (concreta)

Per ogni riga: eseguire smoke **dopo** le modifiche UI rilevanti; segnare evidenza nel log Execution.

### A. Percorsi funzionali (per modalità / flusso)

| # | Scenario | Cosa verificare (minimo) |
|---|----------|---------------------------|
| A1 | **Generated da file — nuova entry** | Da PreGenerate → Generated: griglia popolata, edit qty/prezzo, completo, nessun crash; toolbar visibile secondo `generated`. |
| A2 | **Generated da history** | Apertura entry da History → stessi comandi (sync, export, uscita con dialog history) e stato griglia coerente. |
| A3 | **Manual entry** | FAB scanner, aggiunta/modifica/cancellazione riga manuale, dialog manual entry; griglia vuota → empty state. |
| A4 | **Export / share** | Da overflow: export (tick stato se presente), share XLSX, messaggi/snackbar invariati nel significato. |
| A5 | **Sync / analyze** | Tap sync/analyze: badge/stato come oggi, nessuna perdita di feedback. |
| A6 | **Rename** | Dialog rename: file name + supplier/category dropdown, conferma/annulla, persistenza percepita. |
| A7 | **Search + scanner** | Dialog ricerca: input, tastiera, scan da dialog, esito ricerca sulla griglia. |
| A8 | **Dialog dettaglio riga** | Apertura da riga/celle rilevanti: scroll interno, focus qty/prezzo, edit mode, mark complete/incomplete, sotto-dialog calcolatrice, dismiss. |
| **A9** | **PreGenerate (obbligatorio se Fase C ha toccato `ZoomableExcelGrid` / `TableCell`)** | Aprire **PreGenerate** con file/tabella tipica: **header** leggibili, **celle** leggibili, **selezione colonne** funzionante, **tap** su header/celle con affordance chiara, scroll orizzontale/verticale fluido, **nessun peggioramento UX** rispetto al baseline pre-modifica. Se Fase C **non** è stata eseguita, A9 resta **opzionale** (sanity solo se in Execution si tocca incidentalmente `PreGenerateScreen` — non nel perimetro tipico di TASK-014). |

### B. Condizioni UX / dispositivo

| # | Condizione | Cosa verificare |
|---|------------|-----------------|
| B1 | **Dark mode** | Contrasto chip, toolbar, celle, dialog; nessun testo illeggibile; colonna completo ancora distinguibile. |
| B2 | **Schermi stretti** (piccolo width / fold chiuso) | Toolbar non tronca azioni critiche; overflow accessibile; dialog non tagliati irreversibilmente (scroll). |
| B3 | **Testi lunghi / marquee / chip** | Titolo lungo + marquee; chip supplier/category con stringhe lunghe; ellipsis/scroll orizzontale chip bar. |
| B4 | **Localizzazioni lunghe** | it / en / es / zh (o set usato dal team): pulsanti e menu senza overflow rotto; dialog leggibili. |
| B5 | **Overlay FAB vs griglia** | Ultima riga visibile e tappabile dove previsto; FAB non blocca azioni essenziali (o padding contenuto adeguato). |

### C. Performance (percezione)

| # | Verifica |
|---|----------|
| C1 | Scroll verticale griglia con molte righe: niente jank evidente introdotto dal task. |
| C2 | Apertura/chiusura dialog e sheet: reattività comparabile o migliore rispetto al baseline pre-task. |

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Planning raffinato senza **EXECUTION** | Governance: **TASK-002** `BLOCKED` finché non sbloccato | 2026-03-29 |
| 7 | Dipendenza TASK-002 superata per autorizzazione utente | Utente ha autorizzato esplicitamente (2026-03-29); decomposizione tecnica già nel codice, smoke pendente non blocca UX | 2026-03-29 |
| 8 | iOS: `EntryInfoEditor.swift` ≠ dettaglio riga; dettaglio riga = `RowDetailSheetView` | Verificato leggendo `GeneratedView.swift:2296` e `EntryInfoEditor.swift` | 2026-03-29 |
| 9 | B1: non importare feature iOS-only (navigazione riga-a-riga, delete, product editor, price history) | Rischio scope creep confermato dalla lettura di `RowDetailSheetView` | 2026-03-29 |
| 2 | Riferimento iOS = repo GitHub pubblico **`iOSMerchandiseControl`**; lettura file `.swift` reali in Execution | Allineamento TASK-008 / MASTER-PLAN; ancoraggio a `GeneratedView.swift`, `PreGenerateView.swift`, verifica `EntryInfoEditor.swift` | 2026-03-29 |
| 3 | Vincolo attivazione TASK-014 = **TASK-002**, non un elenco statico di altri task `ACTIVE` | Il task file deve restare valido al variare dello stato globale; **sempre** rileggere `MASTER-PLAN` a runtime | 2026-03-29 |
| 4 | `ExcelViewModel` read-only salvo eccezione documentata | Guardrail logica / TASK-004 | 2026-03-29 |
| 5 | Stringhe: riuso prioritario, nuove chiavi solo se necessarie, tutte le lingue nello stesso task | Minimizzare churn i18n | 2026-03-29 |
| 6 | Stop condition dopo ogni fase A/B/C; Fase C solo se necessario; B1 = focus B, B2–B4 minimo dopo rivalutazione | Anti scope creep, task progressivo | 2026-03-29 |

---

## Planning (Claude) — fasi per rischio e conflitti

### Sintesi analisi UX

Schermata **densa**: toolbar ricca, chip che sembrano interattivi, griglia 120×48, **info riga** in dialog molto lungo, calcolatrice voluminosa. Interventi **incrementali** M3, rispettando guardrail UX e performance.

### Blocco TASK-002

| Aspetto | Nota |
|---------|------|
| **EXECUTION** | Non avviare TASK-014 finché TASK-002 è `BLOCKED` **senza** decisione utente + tracking aggiornato. |
| **Struttura file** | Possibili ulteriori estrazioni TASK-002 su `GeneratedScreen.kt` — allineare branch prima di massicci edit. |
| **Merge** | Modifiche parallele a `GeneratedScreen.kt` senza coordinamento aumentano conflitti. |

### Ordine di lavoro: Fase A → B → C

Le micro-step sotto sono raggruppate; **numerazione interna** conserva i titoli originali dove utile.

### Stop condition tra fasi (anti scope creep — futura Execution)

- Dopo il completamento di **ogni fase** (**A**, **B**, **C**), l’esecutore **rivaluta** se i **criteri di accettazione** del task (e la **Definition of Done** UX in `MASTER-PLAN`) risultano **già soddisfatti** nel perimetro **effettivamente** toccato.
- Se **sì** → **non** si procede automaticamente alla fase successiva; si documenta in **Execution** la decisione di fermarsi (o di chiudere il task dopo review), salvo nuova richiesta utente / task di follow-up.
- **Fase C** non si fa **per completezza** o “perché è nel piano”: si apre **solo** se, dopo A e B, resta un **bisogno UX reale** non risolvibile senza toccare `ZoomableExcelGrid` / `TableCell` (coerente con la **regola preferenziale** sui componenti condivisi e con la **Superficie di regressione**).

---

#### Fase A — Polish locale **low-risk** (`GeneratedScreen.kt` / `GeneratedScreenDialogs.kt`)

Obiettivo: massimo impatto percepito con **minimo** rischio su griglia condivisa.

| Step | Titolo | File principale |
|------|--------|-----------------|
| **A1** | Toolbar: gerarchia M3, `contentDescription`, elevazione/coerenza `TopAppBar` | `GeneratedScreen.kt` |
| **A2** | Menu overflow: icone, etichette, eventuali `Divider`, stessi handler | `GeneratedScreen.kt` |
| **A3** | `TopInfoChipsBar` / `InfoChip`: aspetto **non cliccabile** (guardrail) | `GeneratedScreen.kt` |
| **A4** | Dialog uscita / home / history | `GeneratedScreenDialogs.kt` |
| **A5** | `GeneratedScreenSearchDialog` (Outlined field, CTA scan, focus) | `GeneratedScreenDialogs.kt` |
| **A6** | Area FAB: padding, safe area, varianti dimensione **senza** logica nuova | `GeneratedScreen.kt` |
| **A7** | Stringhe / a11y | `strings.xml` + composable |

Per ogni step A: stessi callback e condizioni di visibilità; smoke mirato su toolbar, dialog estratti, FAB.  
**Stop condition:** a fine Fase A, applicare la **rivalutazione** sopra prima di iniziare la Fase B.

---

#### Fase B — Dialog/sheet **ad alto impatto**, ancora **locali** a Generated

**Priorità operativa:** **`B1` (`GeneratedScreenInfoDialog`) è il focus UX principale dell’intero TASK-014** — è qui che si concentra il maggior guadagno di usabilità percepita (densità, scansionabilità, tastiera). **B2**, **B3**, **B4** sono **secondari** rispetto a B1.

Ordine consigliato: eseguire **B1 per primo**; subito dopo **rivalutare** (come per la **Stop condition tra fasi**) se i criteri di accettazione su dialog/sheet e dettaglio riga sono **già** soddisfatti. Solo se resta un **bisogno misurabile**, procedere con **B2–B4** limitandoli al **minimo necessario** (niente polishing diffuso “tanto che ci siamo”). Obiettivo: task **piccolo, progressivo e controllato**.

| Step | Titolo | File | Note |
|------|--------|------|------|
| **B1** | **`GeneratedScreenInfoDialog`** — **focus UX principale del task**: sezioni, scroll, eventuale sheet M3; **non** alterare flussi save/complete. **Guardrail iOS:** non importare feature iOS-only (navigazione riga-a-riga, delete row, product editor, price history dal dettaglio) — vedi tabella `RowDetailSheetView` sopra. | `GeneratedScreen.kt` | Dopo B1: stop/review vs B2–B4; rischio focus tastiera — test A8 + B2 (schermo stretto) |
| **B2** | Header type / custom header: lista lunga → valutare `ModalBottomSheet` + `LazyColumn` | `GeneratedScreen.kt` | Solo se B1 non basta o gap rimasto documentato; back gesture / stato sheet |
| **B3** | `GeneratedScreenRenameDialog`: scroll con tastiera, spacing | `GeneratedScreen.kt` | Solo se necessario dopo rivalutazione post-B1 |
| **B4** | `ManualEntryDialog` + `CalculatorDialog`: **solo** layout/spacing/scroll (guardrail) | `GeneratedScreen.kt` | Minimo necessario; nessun cambio validazione o API VM |

**Stop condition:** a fine B1 (e, se eseguiti, a fine B2–B4), rivalutare prima di aprire la **Fase C**.

---

#### Fase C — Componenti condivisi: **ultimi** e **minimi**

Toccare **`ZoomableExcelGrid.kt`** / **`TableCell.kt`** **solo se** Fase A/B non bastano **e** dopo check TASK-016 e regola preferenziale sopra.

| Step | Titolo | File |
|------|--------|------|
| **C1** | `TableCell`: tipografia, padding, ripple — **senza** animazioni decorative | `TableCell.kt` |
| **C2** | `ZoomableExcelGrid`: divider, colori colonna completo verso `ColorScheme` dove sicuro | `ZoomableExcelGrid.kt` |
| **C3** | (Opzionale) `cellWidth` / `cellHeight` solo in `GeneratedScreenGridHost` — valutare impatto scroll | `GeneratedScreen.kt` |

Ogni modifica in Fase C: documentare in Execution + riga per coordinamento TASK-016 + esito smoke **A9** (PreGenerate).

**Stop condition:** la Fase C **non** è obbligatoria; vedi **Stop condition tra fasi**. Se A+B bastano, **non** toccare i file condivisi.

---

### Check finali (build + matrice)

- `./gradlew assembleDebug`, `./gradlew lint` (nessun warning nuovo dal task).
- Completare le sezioni **A** e **B** della **matrice di verifica** come minimo; sezione **C** (performance) se rilevante.
- Se è stata eseguita **Fase C** (`ZoomableExcelGrid` / `TableCell`): eseguire obbligatoriamente **A9** (PreGenerate) oltre ai flussi Generated; vedi **Superficie di regressione**.
- Se si tocca `ExcelViewModel`: `ExcelViewModelTest` + nota TASK-004.

---

## Execution

### Esecuzione — 2026-03-29

**File modificati:**
- `docs/MASTER-PLAN.md` — riallineato tracking globale: `TASK-009` riportato coerentemente a `DONE`, `TASK-014` portato a `ACTIVE`/`EXECUTION` come da autorizzazione utente.
- `docs/TASKS/TASK-014-ux-modernization-generatedscreen.md` — stato task aggiornato a `EXECUTION`; log Execution/Handoff riallineati al lavoro svolto.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — Fase A locale su top bar/chips/FAB e focus **B1**: `GeneratedScreenInfoDialog` convertito in `ModalBottomSheet` con card/sezioni, focus tastiera più leggibile e azioni invarianti sullo stesso flusso `updateHistoryEntry`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — polish locale dei dialog estratti (discard/history/home/search): shape M3 più pulita, gerarchia tipografica migliore, search dialog con `OutlinedTextField` e CTA scanner secondaria.

**Azioni eseguite:**
1. Letti e riallineati i file di verità richiesti (`MASTER-PLAN`, `TASK-014`, `CLAUDE`, `AGENTS`) e verificato che il solo blocco reale fosse il tracking incoerente di `TASK-009` in `MASTER-PLAN`.
2. Letti i file Android reali coinvolti (`GeneratedScreen.kt`, `GeneratedScreenDialogs.kt`, `NavGraph.kt` in read-only) e i riferimenti iOS reali in `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/GeneratedView.swift`, `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/PreGenerateView.swift`, `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/EntryInfoEditor.swift`, oltre agli screenshot caricati.
3. Applicata **Fase A** senza toccare navigation, `ExcelViewModel`, `ZoomableExcelGrid.kt` o `TableCell.kt`: top bar centrata e più leggibile, overflow con separazione visiva, chip informativi trasformati in badge non cliccabili, gerarchia FAB search/scanner più chiara, dialog search/uscita/home/history più coerenti Material3.
4. Applicato **B1** come focus principale: il vecchio `AlertDialog` molto denso del dettaglio riga è stato sostituito da un `ModalBottomSheet` con card e campi raggruppati, migliore scansionabilità, supporto tastiera/focus più robusto e stessi callback di modifica/completamento.
5. Rivalutato il perimetro dopo B1: **B2/B3/B4 non necessari in questo turno**; **Fase C non eseguita** perché l’obiettivo UX è stato raggiunto localmente senza toccare componenti condivisi.
6. UI/UX intenzionale: bottom sheet dettagli riga, badge informativi read-only, search dialog e FAB più maturi (motivo: chiarezza, gerarchia visiva, qualità percepita, nessun impatto su business logic).

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `assembleDebug` riuscito con `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| Lint                     | ✅ | `lint` riuscito; report presente in `app/build/reports/lint-results-debug.*` |
| Warning nuovi            | ✅ | Nessun warning/lint issue rilevato nei file toccati (`GeneratedScreen.kt`, `GeneratedScreenDialogs.kt`); restano warning/deprecazioni preesistenti di progetto/Gradle fuori scope |
| Coerenza con planning    | ✅ | Eseguite Fase A + focus B1; B2/B3/B4 e Fase C saltate dopo rivalutazione, come previsto dal task |
| Criteri di accettazione  | ⚠️ | Implementazione statica completata; verifiche manuali della matrice M restano non eseguibili in questo ambiente CLI |

**Criteri di accettazione:**
| # | Criterio | Stato | Evidenza |
|---|----------|-------|----------|
| 1 | Toolbar, overflow e chip informativi allineati ai guardrail UX sopra | NON ESEGUIBILE | Implementati in `GeneratedScreen.kt`, ma smoke UI/manuale non eseguito in ambiente CLI |
| 2 | Dialog/sheet (in particolare dettaglio riga) più usabili, stessi esiti funzionali | NON ESEGUIBILE | `GeneratedScreenInfoDialog` migrato a bottom sheet mantenendo callback e update esistenti; manca verifica manuale A8/B2 |
| 3 | Griglia almeno invariata o migliorata in leggibilità/affordance, senza cambiare semantica celle | NON ESEGUIBILE | Nessun tocco a `ZoomableExcelGrid.kt` / `TableCell.kt`; verifica manuale A1/A2/A5/B1/C1 pendente |
| 4 | Nessuna regressione sui flussi elencati nella matrice di verifica sotto (incluso A9 se applicabile) | NON ESEGUIBILE | Nessuna route o logica condivisa toccata; smoke A1-A8, B1-B5, C1-C2 non eseguibili qui; **A9 non applicabile** (Fase C non eseguita) |
| 4bis | Navigazione: ingressi PreGenerate / History / manual add invariati; `entryUid`, `isNewEntry`, `isManualEntry` invariati | ESEGUITO | `NavGraph.kt` solo letto; nessuna modifica a route/argomenti/semantica ingressi |
| 5 | Rispetto guardrail performance | ESEGUITO | Nessun tocco a componenti condivisi/path caldi della griglia; nessuna animazione decorativa introdotta |
| 6 | `./gradlew assembleDebug` OK | ESEGUITO | `BUILD SUCCESSFUL` |
| 7 | `./gradlew lint` senza nuovi warning dal task | ESEGUITO | `BUILD SUCCESSFUL`; report lint senza occorrenze per i file toccati |

**Smoke matrix (stato del turno):**
- A1–A8, B1–B5, C1–C2: **NON ESEGUIBILE** in questo ambiente CLI (nessun emulator/device/sessione UI disponibile nel turno).
- A9: **NON APPLICABILE** in questo turno perché `ZoomableExcelGrid.kt`, `TableCell.kt` e `PreGenerateScreen.kt` non sono stati modificati.

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: non applicabile
- Test aggiunti/aggiornati: nessuno
- Limiti residui: non sono stati toccati `ExcelViewModel`, repository, DAO o logica dati coperta dalla baseline TASK-004

**Incertezze:**
- Verifica manuale UX residua su device/emulator per la matrice del task

**Handoff notes:**
- Il perimetro si è fermato intenzionalmente dopo **B1**: nessun intervento su `GeneratedScreenRenameDialog`, `ManualEntryDialog`, `CalculatorDialog`, `ZoomableExcelGrid.kt` o `TableCell.kt` in questo turno.
- Se nei test manuali emergesse ancora un gap di leggibilità della griglia, prima di aprire **Fase C** rileggere `TASK-016` e coordinare i file condivisi.
- Per rilanciare i check Gradle in questo ambiente serve impostare `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

### Esecuzione — 2026-03-29 (ritocco sheet dettaglio riga)

**File modificati:**
- `docs/TASKS/TASK-014-ux-modernization-generatedscreen.md` — log Execution aggiornato con il ritocco mirato richiesto sulla sheet dettaglio riga.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — B1 rifinita localmente: parte alta della sheet più compatta, stato/prodotto più efficienti, `Quantità contata` + `Prezzo vendita` affiancati più spesso, `Prezzo acquisto` riportato nel blocco operativo alto.

**Azioni eseguite:**
1. Riletti `MASTER-PLAN`, `TASK-014`, `CLAUDE`, `AGENTS`, `GeneratedScreen.kt` e il riferimento iOS reale in `/Users/minxiang/Desktop/iOSMerchandiseControl/iOSMerchandiseControl/GeneratedView.swift`, confrontando anche gli screenshot Android/iOS caricati.
2. Ridotta l’altezza della prima fascia della sheet senza cambiare la logica: padding/spaziature più stretti, badge stato integrato meglio nel blocco identità prodotto, barcode e item number resi più spesso inline.
3. Riorganizzato il blocco operativo principale: `Quantità contata` e `Prezzo vendita` ora condividono la stessa riga quando la larghezza reale lo consente; `Prezzo acquisto` è stato spostato nello stesso contesto operativo, subito sotto i campi economici.
4. Verifica statica rapida dei flussi sensibili: edit mode, calcolatrice, toggle complete/incomplete, dismiss sheet e aggiornamento dei campi quantità/prezzo mantengono gli stessi callback e gli stessi side effect (`editableValues`, `completeStates`, `updateHistoryEntry`, `onDismiss`).

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug lint` completato con `BUILD SUCCESSFUL` |
| Lint                     | ✅ | Report aggiornato in `app/build/reports/lint-results-debug.html` |
| Warning nuovi            | ✅ | Nessuna occorrenza lint per `GeneratedScreen.kt`; warning Kotlin/AGP osservati restano in file/config fuori scope (`DatabaseScreenComponents.kt`, `HistoryScreen.kt`, Gradle) |
| Coerenza con planning    | ✅ | Ritocco locale coerente con **B1**; nessuna espansione a B2/B3/B4 o Fase C |
| Criteri di accettazione  | ⚠️ | Miglioramento della sheet applicato e compilato; smoke manuali richiesti dal task ancora non eseguibili in ambiente CLI |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: non applicabile
- Test aggiunti/aggiornati: nessuno
- Limiti residui: non sono stati toccati `ExcelViewModel`, repository, DAO o logica dati coperta dalla baseline TASK-004

**Incertezze:**
- Resta da confermare manualmente su device/emulator il comportamento finale above-the-fold con tastiera aperta e dismiss gesture della sheet.

**Handoff notes:**
- Nessun tocco a `GeneratedScreenDialogs.kt`, `ZoomableExcelGrid.kt`, `TableCell.kt`, `NavGraph.kt` o `ExcelViewModel.kt` in questo ritocco.
- Se un device molto stretto mostrasse ancora stacking verticale, il follow-up deve restare locale alla soglia responsive/spaziature della sheet prima di valutare scope più ampi.

### Esecuzione — 2026-03-29 (ritocco sheet dettaglio riga: header, campi, CTA)

**File modificati:**
- `docs/TASKS/TASK-014-ux-modernization-generatedscreen.md` — log Execution aggiornato con il ritocco mirato su header, campi principali e CTA della sheet dettaglio riga.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — top area resa più efficiente, campi `Quantità contata` / `Prezzo vendita` convertiti in layout compatto a label esterna stabile, CTA calcolatrice riallineate al comportamento reale.

**Azioni eseguite:**
1. Riletti `MASTER-PLAN`, `TASK-014`, `CLAUDE`, `AGENTS`, il blocco reale di `GeneratedScreenInfoDialog` e il riferimento iOS `GeneratedView.swift`, verificando il perimetro locale della sola sheet dettaglio riga.
2. Rimossa la CTA `Chiudi` dalla riga alta della sheet e riorganizzato l’header per liberare spazio utile: matita/salvataggio a sinistra, titolo al centro, controllo `Completato` / `Incompleto` a destra con gli stessi side effect del toggle precedente.
3. Resi stabili e più compatti i due campi operativi principali con label esterna fissa sopra `OutlinedTextField`: niente floating label interna, niente salto visivo tra stato inattivo/attivo, più spazio verticale disponibile sopra la piega.
4. Corrette le azioni calcolatrice: rimossa l’icona sbagliata dal campo `Prezzo vendita`, mantenuta la calcolatrice purchase solo nel blocco `Prezzo acquisto`, e riallineata la CTA della calcolatrice generica alla stringa già corretta `generic_calculator_title`.
5. Compattata la card prodotto senza nascondere troppo contenuto: nome prodotto più controllato (`maxLines`/ellipsis ragionevoli) e gerarchia più densa per lasciare visibili prima i dati operativi.
6. Verifica statica dei flussi richiesti: edit mode, dismiss sheet, toggle complete/incomplete, campi quantità/prezzo, calcolatrice generica e modifica prezzo acquisto mantengono gli stessi callback e la stessa logica.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug lint` completato con `BUILD SUCCESSFUL` |
| Lint                     | ✅ | Report aggiornato in `app/build/reports/lint-results-debug.html` |
| Warning nuovi            | ✅ | Nessuna occorrenza lint per `GeneratedScreen.kt`; warning residui restano in file/config fuori scope (`DatabaseScreenComponents.kt`, `HistoryScreen.kt`, Gradle/AGP) |
| Coerenza con planning    | ✅ | Ritocco strettamente locale a **B1**; nessun allargamento a dialog esterni, navigation, grid condivisa o ViewModel |
| Criteri di accettazione  | ⚠️ | Ritocco applicato e compilato; smoke manuali/device richieste dal task restano non eseguibili in ambiente CLI |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: non applicabile
- Test aggiunti/aggiornati: nessuno
- Limiti residui: non sono stati toccati `ExcelViewModel`, repository, DAO o logica dati coperta dalla baseline TASK-004

**Incertezze:**
- Resta da confermare manualmente su device/emulator il feeling finale dell’header senza pulsante `Chiudi` dedicato e la resa above-the-fold con tastiera aperta.

**Handoff notes:**
- Nessun aggiornamento a `GeneratedScreenDialogs.kt` o ai file di localizzazione: il riallineamento CTA è stato risolto riusando `generic_calculator_title` già presente.
- Se nei test manuali il controllo di stato in alto risultasse troppo ambiguo, il follow-up deve restare locale alla sola affordance del bottone, senza riaprire il perimetro funzionale della sheet.

---

## Review

### Review — 2026-03-29

**Revisore:** Claude (planner)

**Scope del review:** codice reale di `GeneratedScreen.kt` e `GeneratedScreenDialogs.kt` dopo le execution di Codex (Fase A + B1 + ritocchi sheet dettaglio riga); verifica statica dei flussi, coerenza stringhe, check build/lint.

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Toolbar, overflow e chip informativi allineati ai guardrail UX | ✅ | `CenterAlignedTopAppBar` con `surfaceColorAtElevation`, chip `InfoChip` non cliccabili (Surface read-only), overflow con `HorizontalDivider`, `contentDescription` su tutti i bottoni |
| 2 | Dialog/sheet (dettaglio riga) più usabili, stessi esiti funzionali | ✅ | `ModalBottomSheet` con card/sezioni, campi compatti con label esterna, focus/keyboard preservati, callback `updateHistoryEntry`/`persistRowChanges`/`completeStates` invariati |
| 3 | Griglia invariata | ✅ | Nessun tocco a `ZoomableExcelGrid.kt` / `TableCell.kt` |
| 4 | Nessuna regressione sui flussi | ⚠️ NON ESEGUIBILE | Verifica statica OK; smoke manuali A1–A8, B1–B5, C1–C2 non eseguibili senza device/emulator |
| 4bis | Navigazione invariata | ✅ | `NavGraph.kt` non toccato; `entryUid`, `isNewEntry`, `isManualEntry` invariati |
| 5 | Guardrail performance | ✅ | Nessun tocco a path caldi della griglia, nessuna animazione decorativa |
| 6 | `assembleDebug` OK | ✅ | `BUILD SUCCESSFUL` (post-fix) |
| 7 | `lint` senza nuovi warning | ✅ | `BUILD SUCCESSFUL`; nessun warning nuovo nei file toccati |

**Problemi trovati:**
1. **Bug layout overlap (FIXED):** `BoxWithConstraints` nella card prodotto (view mode, narrow screen) — due `GeneratedScreenCompactMetaBlock` figli diretti del `Box` si sovrappongono quando `maxWidth < 260.dp` e sia barcode che itemNumber sono non-blank. Corretto wrappando in `Column(verticalArrangement = Arrangement.spacedBy(8.dp))`.

**Verifiche statiche dei wiring (richieste dall'utente):**
- ✅ **Edit mode:** `isInfoDialogInEditMode` toggle via `FilledTonalIconButton`; `persistRowChanges()` invocato al salvataggio; campi editabili correttamente collegati ai loro `MutableState<TextFieldValue>`
- ✅ **Dismiss sheet:** `onDismiss` callback passato a `ModalBottomSheet.onDismissRequest` e invocato nei punti corretti
- ✅ **Toggle complete/incomplete:** `completeStates[infoRowIndex]` toggle + `updateHistoryEntry(entryUid)` + `onDismiss()` — stessi side effect del codice precedente
- ✅ **Campi quantità/prezzo:** `qtyTf`/`priceTf` write-through a `editableValues[infoRowIndex][0/1]` + `updateHistoryEntry`; focus chain `qtyReq → priceReq` con `ImeAction.Next/Done` preservata
- ✅ **Calcolatrice generica:** `OutlinedButton` → `onOpenGenericCalculator` → `showGenericCalcDialog = true` → `CalculatorDialog` con `generic_calculator_title` (CTA corretta)
- ✅ **Calcolatrice purchase:** `IconButton` su purchase field → `onOpenPurchaseCalculator(purchasePriceState.value.text)` → `CalculatorDialog` con `calc_title` — nessuna calcolatrice spuria sul retail
- ✅ **Modifica prezzo acquisto:** `purchasePriceState` editabile in edit mode; in view mode: `Surface` + `IconButton` per calcolatrice purchase

**Coerenza stringhe:**
- Nessuna nuova chiave aggiunta; tutte le stringhe usate (`row_info`, `save_changes`, `edit_row`, `status_completed`, `status_incomplete`, `generic_calculator_title`, `fast_calculator_desc`, `calculate_new_value`, `header_*`, ecc.) esistono e sono coerenti in it/en/es/zh.

**Verdetto:** APPROVED (con fix applicato in questo stesso review)

**Note:** il fix del bug `BoxWithConstraints` è stato applicato direttamente e verificato con build+lint OK. Non richiede ulteriore ciclo FIX → REVIEW.

---

## Fix

### Fix — 2026-03-29 (applicato durante review)

**File modificato:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — branch `else` di `BoxWithConstraints` nella card prodotto: wrappati i due `GeneratedScreenCompactMetaBlock` in `Column(verticalArrangement = Arrangement.spacedBy(8.dp))` per evitare overlap su schermi stretti.

**Check:**
- Build Gradle: ✅ `BUILD SUCCESSFUL`
- Lint: ✅ `BUILD SUCCESSFUL`

---

## Chiusura

| Campo                  | Valore |
|------------------------|--------|
| Stato finale           | **DONE** |
| Data chiusura          | 2026-03-29 |
| Tutti i criteri ✅?    | ✅ (tranne matrice smoke manuale: ⚠️ NON ESEGUIBILE — nessun device/emulator disponibile) |
| Rischi residui         | Smoke manuali A1–A8, B1–B5, C1–C2 non eseguiti; se emergono problemi su device, aprire fix mirato o follow-up. |

---

## Riepilogo finale

**Cosa è stato fatto (Fase A + B1):**
- Toolbar: `CenterAlignedTopAppBar` con `surfaceColorAtElevation`, gerarchia visiva migliorata, overflow con separatore
- Chip informativi: convertiti in `Surface` read-only (non cliccabili), coerenti M3
- Dialog estratti (`GeneratedScreenDialogs.kt`): shape M3 `RoundedCornerShape(28.dp)`, tipografia pulita, search con `OutlinedTextField` + CTA scanner
- **Sheet dettaglio riga (focus B1):** `AlertDialog` → `ModalBottomSheet` con drag handle; top bar con edit/save + titolo + toggle stato; card prodotto compatta con meta inline/stacked responsive; campi operativi con label esterna stabile; prezzo acquisto nel blocco operativo; calcolatrice solo su purchase + CTA generica corretta; card reference per quantità/totale
- FAB: gerarchia `SmallFAB` search + `FAB` scanner, colori M3

**Cosa NON è stato toccato (invarianti preservati):**
- `NavGraph.kt`, `ExcelViewModel.kt`, `ZoomableExcelGrid.kt`, `TableCell.kt`
- Tutti i callback di business logic (updateHistoryEntry, persistRowChanges, completeStates, export, sync, rename, manual entry)
- Route, argomenti di navigazione, semantica ingressi

**Bug corretto in review:**
- Overlap `BoxWithConstraints` su narrow screen (barcode + itemNumber stacked)

**Limite residuo:**
- Smoke manuali non eseguibili senza device/emulator. Test manuali suggeriti al prossimo operatore: A1–A8, B1–B5, con focus su A8 (sheet dettaglio), B2 (schermo stretto), B1 (dark mode).

---

## Handoff

- **Stato:** `TASK-014` è **`DONE`** (2026-03-29). Review planner APPROVED con fix overlap applicato.
- **Smoke manuali pendenti:** eseguire su device/emulator la matrice `A1–A8`, `B1–B5`, `C1–C2` per conferma definitiva. Focus prioritario: **A8** (sheet dettaglio riga), **B2** (schermo stretto — il fix overlap è stato applicato ma non testato su device), **B1** (dark mode).
- **`A9` non applicabile:** `ZoomableExcelGrid.kt` / `TableCell.kt` non toccati (Fase C non eseguita).
- **iOS:** repository `iOSMerchandiseControl` in `/Users/minxiang/Desktop/iOSMerchandiseControl/`. File confermati: **`GeneratedView.swift`** (toolbar + griglia), **`PreGenerateView.swift`** (anteprima), **`RowDetailSheetView`** (dettaglio riga). **`EntryInfoEditor.swift`** = editor metadati entry (≈ `GeneratedScreenRenameDialog` Android).
- **Follow-up potenziali:** B2–B4, Fase C (componenti condivisi) — solo se emergono gap da smoke test. Coordinare con TASK-016 per `ZoomableExcelGrid` / `TableCell`.

---

## Stato planning (chiusura di questo passaggio)

**Planning verificato e integrato** (2026-03-29, review planning vs file reali Android + iOS):

- **Stop condition** tra fasi confermata; **Fase C** solo se A/B insufficienti.
- **Priorità B1** con rivalutazione obbligatoria prima di B2–B4 confermata.
- **Mappatura iOS corretta:** `RowDetailSheetView` = dettaglio riga (non `EntryInfoEditor.swift`); guardrail anti-scope-creep aggiunto a B1.
- **Dipendenza TASK-002 superata** per autorizzazione esplicita utente.
- **Nota storica:** questo blocco planning è stato superato nello stesso giorno con autorizzazione utente esplicita e riallineamento del tracking globale.

**Stato planning storico:** passaggio chiuso; vedere **Execution** per lo stato reale corrente del task.
