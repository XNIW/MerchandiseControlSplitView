# TASK-002 — Decomposizione GeneratedScreen

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-002                   |
| Stato                | BLOCKED                    |
| Priorità             | MEDIA                      |
| Area                 | UI / GeneratedScreen       |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-27                 |

---

## Dipendenze

- TASK-001 (DONE)
- TASK-013 (DONE) — prerequisito di sequencing e baseline UX FilePicker/PreGenerate
- **Nota tracking:** task **BLOCKED** (smoke manuale rimandato dall’utente). Unico `ACTIVE` nel backlog: **TASK-015** (UX DatabaseScreen). **TASK-017** **`DONE`**. Vedi `MASTER-PLAN.md`.

---

## Scopo

Ridurre la complessità di `GeneratedScreen.kt` estraendo sotto-composable e helper dedicati, mantenendo invariati flussi, callback e comportamento utente. Il task serve a rendere il file più leggibile e manutenibile partendo dalla **baseline reale** del file in repo (vedi audit sotto: **non** assumere monolite puro). Nessuna modifica funzionale intenzionale.

**Perimetro obbligatorio:** TASK-002 è **solo** decomposizione tecnica. **Nessuna** modernization UX/UI, **nessun** cambio al layout percepito, **nessuna** scelta visiva nuova. Qualsiasi miglioramento UX di GeneratedScreen va rimandato a **TASK-014**.

---

## Contesto

`GeneratedScreen.kt` è storicamente uno dei file più grandi e densi della repo (~2471 righe allo stato 2026-03-27). Durante il pre-audit era emersa una decomposizione incompleta nel worktree, ma su richiesta utente il file è stato ripristinato al baseline di `HEAD`; l’esecuzione riparte da quella base e applica solo estrazioni deliberate e verificabili.

Il task è esclusivamente tecnico. L’Android repo resta fonte di verità per logica, wiring e integrazioni; non è richiesto alcun riferimento iOS. L’obiettivo è chiudere il refactor in modo progressivo, con cambi piccoli e verificabili.

### Allineamento governance (prima dell’esecuzione codice)

È stato chiesto di eliminare ambiguità tra materiali di planning:

| Fonte | Stato TASK-013 atteso |
|-------|------------------------|
| `docs/MASTER-PLAN.md` — backlog | `DONE` (chiusura documentale) |
| `docs/TASKS/TASK-013-ux-polish-filepicker-pregenerate.md` | `DONE` |
| Backlog — unico `ACTIVE` | **TASK-017** (dopo riallineamento 2026-03-27); TASK-002 = **BLOCKED** |

Se in log storici di questo file compare testo che suggerisce TASK-013 ancora attivo, **prevalgono** tabella e backlog in `MASTER-PLAN.md`. Per sessioni di codice su `GeneratedScreen.kt` in futuro: verificare che TASK-002 non sia più `BLOCKED` o che la ripresa sia esplicitamente autorizzata.

### Verifica governance **reale** nel repo (obbligatoria)

Non basarsi solo sul task file: **prima** del primo commit/touch su `GeneratedScreen.kt` in ogni sessione di esecuzione:

1. Leggere `docs/MASTER-PLAN.md` — tabella **TASK-013** nel backlog → **`DONE`**.
2. Tabella **TASK-002** → coerente con `MASTER-PLAN` (es. **`BLOCKED`** se sospeso).
3. Cercare altri task con stato **`ACTIVE`** nel backlog (atteso: **un solo** task, es. **TASK-017** durante il bugfix import).
4. Leggere `docs/TASKS/TASK-013-ux-polish-filepicker-pregenerate.md` — campo **Stato** → **`DONE`**.
5. Se qualcosa non coincide: **aggiornare subito** `MASTER-PLAN.md` e i file task interessati; **stop** su Kotlin finché non sono coerenti.

**Evidenza sessione 2026-03-27 (pre-codice):** verifica su working tree — `MASTER-PLAN` backlog: TASK-013 `DONE`, TASK-002 `ACTIVE`; file `TASK-013` Stato `DONE`; nessun altro `ACTIVE` nel backlog elencato.

### Stop rule — scope creep (operativa)

Se durante una **slice** emerge che per “completarla” servirebbe modificare **uno** tra:

- `ExcelViewModel`
- `DatabaseViewModel`
- `NavGraph.kt`
- `ZoomableExcelGrid.kt`
- **contratto pubblico** di `GeneratedScreen` (firma o semantica parametri)

allora:

1. **Non** allargare automaticamente il perimetro di TASK-002.
2. **Fermarsi**, documentare nel file task (Execution): slice, blocco, cosa mancherebbe.
3. Chiudere la slice al **massimo punto sicuro** (es. estrazione parziale lasciando inline il frammento bloccante).
4. Proporre il **minimo** follow-up (nuovo task o micro-task) fuori da TASK-002.

### Conservazione UI / UX (TASK-002)

- Qualsiasi ambiguità su **toolbar, dialog, focus, ordine azioni, CTA, spacing, gerarchia visiva** si risolve **preservando esattamente** la UI/UX attuale (baseline pre-slice).
- **Nessuna** reinterpretazione visiva, **nessun** polish implicito, **nessuna** “piccola miglioria grafica”.
- Ogni decisione UX/UI resta **TASK-014**.

---

## Non incluso

- Qualsiasi cambio funzionale a editing, completamento righe, export, rename, search, scan barcode, sync o save/share
- Redesign UX, modernization visiva, cambio gerarchia toolbar/FAB/dialog (**→ TASK-014**)
- Modifiche a `ExcelViewModel`, `DatabaseViewModel` o spostamento improprio di business logic fuori dai ViewModel
- Modifiche a DAO, repository, Room, import/export Excel, barcode flow
- Modifica di `NavGraph.kt` in esecuzione (**read-only**; solo verifica in review, salvo blocco documentato)
- Nuove dipendenze o refactor architetturali ampi
- Riscrittura di `ZoomableExcelGrid.kt` o `TableCell.kt`
- Introduzione di astrazioni “cosmetiche” (vedi guardrail sotto: niente `GeneratedScreenUiState` artificiale, niente mega-container di callback)

---

## File potenzialmente coinvolti

**Da modificare:**

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — riduzione del ruolo a orchestratore della schermata; estrazione sezioni UI e dialog identificati dall’audit
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — dialog e helper dedicati di `GeneratedScreen` (da creare se la separazione resta netta)
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenSections.kt` — top bar, chips, grid host, FAB area e helper UI locali (da creare solo se utile e non eccessivamente frammentato)

**Da leggere (non modificare salvo necessità reale scoperta in esecuzione):**

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — riferimento per `BadgeType` / `StatusIcon`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — **sola lettura** in esecuzione; in **REVIEW** verificare che il call site `GeneratedScreen(...)` sia invariato (parametri e ordine) salvo necessità bloccante documentata

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `./gradlew assembleDebug` passa senza errori | B | — |
| 2 | `./gradlew lint` passa senza nuovi warning introdotti | S | — |
| 3 | `GeneratedScreen.kt` si riduce come file orchestratore: top bar/actions, chips/info header, grid host, FAB area e dialog vengono spezzati in helper/composable leggibili senza cambiare wiring o comportamento | S | — |
| 4 | Le sezioni `top bar / actions / overflow menu`, `chips/info header`, `grid host` e `fab area` sono estraibili e vengono effettivamente isolate in helper privati o file dedicati nello stesso package, senza introdurre micro-file inutili | S | — |
| 5 | `search dialog`, `exit dialogs`, `info/edit row dialog` e `manual entry dialog` risultano separati dal corpo principale di `GeneratedScreen` mantenendo invariati callback, launcher e logica utente-visibile | S | — |
| 6 | L’esecuzione parte dalla baseline pulita di `GeneratedScreen.kt` e non lascia riferimenti a helper mancanti o sezioni in stato intermedio | S | — |
| 7 | Nessuna modifica a ViewModel, DAO, repository, entity, Room, navigation, import/export Excel o barcode flow | S | — |
| 8 | Nessuna nuova dipendenza e nessun refactor architetturale ampio | S | — |
| 9 | Nessun cambiamento funzionale intenzionale nei flussi di search, scan, export/share, rename, gestione header, edit row e manual entry | S | — |
| 10 | Helper/formatter puramente UI vengono estratti o riordinati solo se migliorano la leggibilità, senza alterare output e formattazione esistente | S | — |
| 11 | Self-review del diff conferma che il task resta nel perimetro tecnico e non introduce redesign UX | S | — |
| 12 | Il task file viene aggiornato con log `Execution`, evidenze build/lint e passaggio a `REVIEW` solo dopo verifiche minime soddisfatte | S | — |
| 13 | **API freeze:** firma pubblica `GeneratedScreen(...)` invariata; `entryUid`, `isNewEntry`, `isManualEntry`, `onNavigateToDatabase` e gli altri parametri esistenti preservati; call site in `NavGraph.kt` invariato (verificato in review) salvo blocco documentato | S | — |
| 14 | **Cleanup finale:** nessun commento temporaneo o marker tipo “AGGIUNTO”; import puliti; nessun helper morto; `GeneratedScreen` leggibile come orchestratore | S | — |

Legenda tipi: B=Build, S=Static, M=Manual, E=Emulator

> Per questo task di decomposizione tecnica UI/Compose valgono BUILD + STATIC; test emulator/device non sono richiesti salvo regressione scoperta durante l’esecuzione.

### Smoke checklist finale (non bloccante, consigliata prima di REVIEW)

Da eseguire su emulatore/dispositivo se disponibile; esito documentato nel log `Execution` (✅ / ⚠️ saltato con motivazione).

- [ ] Back da **nuova** entry (`isNewEntry` / flusso file)
- [ ] Back da entry **storica** (cronologia)
- [ ] Ricerca testuale (dialog search + navigazione risultati coerente con baseline)
- [ ] Scan barcode (flusso scanner dalla schermata)
- [ ] Export / share (inclusa semantica snackbar con azione **apri database** se presente)
- [ ] Apertura e salvataggio da **manual entry** e **edit row** (dialog info/edit)

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Ripartire dalla baseline pulita di `GeneratedScreen.kt` | Evita di incorporare una decomposizione incompleta e rende il diff finale più controllabile | 2026-03-27 |
| 2 | Estrarre prima blocchi puramente UI e dialog grossi, poi rifinire helper locali | Strategia progressiva con rischio regressione più basso | 2026-03-27 |
| 3 | Limitare la separazione a 1-2 file dedicati nello stesso package `ui/screens` se la divisione resta chiara | Evitare micro-file e frammentazione eccessiva | 2026-03-27 |
| 4 | Lasciare `GeneratedScreen` come orchestratore di stato e callback; nessuna business logic nei composable estratti | Preservare MVVM e fonte di verità nei ViewModel | 2026-03-27 |
| 5 | Nessun redesign UX in TASK-002 | Il task è decomposizione tecnica controllata, non modernization visiva | 2026-03-27 |
| 6 | API freeze su `GeneratedScreen` e call site `NavGraph` | Evitare regressioni di navigazione e contratto pubblico | 2026-03-27 |
| 7 | Governance TASK-013 = DONE ovunque | Eliminare mismatch tra backlog e note storiche nei log | 2026-03-27 |
| 8 | TASK-002 → BLOCKED se smoke mancati; crash DB import → TASK-017 | Separazione perimetro stabilità vs UI | 2026-03-27 |
| 9 | Verifica governance sul **file reale** `MASTER-PLAN.md` prima del codice | Evitare esecuzione su backlog disallineato | 2026-03-27 |
| 10 | Stop rule se serve toccare VM/NavGraph/Grid/firma `GeneratedScreen` | Impedire scope creep mascherato | 2026-03-27 |
| 11 | Ambiguità UI → baseline invariata; UX → TASK-014 | Decomposizione tecnica senza redesign | 2026-03-27 |

---

## Planning (Claude)

### Obiettivo

Ridurre dimensione e complessità cognitiva di `GeneratedScreen.kt`, partendo dalla baseline reale in repo e rendendo la schermata più manutenibile senza cambiare comportamento.

### Baseline reale (audit 2026-03-27 — non assumere monolite totale)

Il file è **grande e prevalentemente inline** (~2471 righe), ma contiene **già** estrazioni e helper nel medesimo file. In esecuzione:

- **Preservare o rifinire** (spostamento in file sibling oppure lasciare nel principale se già limpidi), **non riscrivere** da zero:
  - `TopInfoChipsBar`, `InfoChip`, `MenuIconWithTick`, `StatusIcon`
  - `CalculatorDialog`, `CalculatorKeyboard`
  - `ManualEntryDialog` (composable top-level nello stesso file)
  - Funzioni di supporto: `evalSimpleExpr`, `toNormalizedProduct`, `almostEqual`, `equalProducts`, `formatDecimal`, ecc.
- **Mappare** cosa resta nel corpo di `GeneratedScreen`: `Scaffold` (top bar, overflow, grid, FAB), dialog ancora inline (search, exit, rename, header custom, info/edit row, calcolatrice collegata, ecc.), `rememberLauncherForActivityResult` / `BackHandler`, snackbar e flussi export/share/sync.

Strategia: le **slice** sotto si adattano a questo stato — ad es. **Slice A** può consistere nel **collegare** la top bar / grid host / FAB a helper **nuovi o esistenti**, estendendo `TopInfoChipsBar` solo se serve, senza duplicare composable già presenti.

### API freeze (obbligatorio)

- **Non** cambiare la **firma pubblica** di `fun GeneratedScreen(...)` (parametri, tipi, ordine, default).
- **Non** cambiare i **call site** in `NavGraph.kt` salvo **necessità bloccante** documentata nel file task (caso atteso: nessuna modifica).
- Preservare esattamente: `entryUid`, `isNewEntry`, `isManualEntry`, `onNavigateToDatabase` e gli altri callback già passati da `NavGraph`.
- `NavGraph.kt`: **read-only** durante l’esecuzione; in **REVIEW** il revisore verifica esplicitamente il blocco `GeneratedScreen(...)` rispetto al pre-diff.

### Perimetro

- `GeneratedScreen.kt` e i relativi helper/composable nello stesso package `ui/screens`
- Estrazione di dialog, sezioni della `Scaffold`, helper UI e formatter puramente presentazionali
- Riordino del file principale per separare orchestration, rendering e dialog wiring

### Non-obiettivi

- Nessun cambio a business logic, validazioni, query, repository o ViewModel
- Nessun cambio a navigation (codice), barcode flow, import/export, sync o struttura dati
- Nessuna modernizzazione UX o variazione del layout percepito (**→ TASK-014**)

### Guardrail — punti fragili (invariati a livello comportamentale)

- **Snackbar** con azione tipo “apri database”: stessa semantica, stessi trigger e navigazione.
- **Launcher**: scanner principale, `dialogScanLauncher`, `saveLauncher` (e ogni `rememberLauncherForActivityResult` collegato) — **nessun** cambio di contratto, MIME o callback verso ViewModel.
- **Back handling** e logica di uscita (`handleBackPress`, dialog discard / history / home): **identici** alla baseline.
- **Export / share / sync analysis**: stesso ordine operazioni, stessi messaggi utente e side-effect percepibili.

### Guardrail — anti-astrazione (vietato)

- Nessuno stato UI aggregato artificiale tipo `GeneratedScreenUiState` solo per “pulire” il file.
- Nessun mega-oggetto “callbacks container” che sposta tutto in un’unica data class senza guadagno chiaro.
- Nessun refactor architetturale mascherato da “split file”. Preferire **estrazioni 1:1**, **callback esplicite** nei composable estratti, diff **reviewabile**.

### Rischi

| Rischio | Impatto | Probabilità | Mitigazione |
|---------|---------|-------------|-------------|
| Perdita di comportamento durante l’estrazione dei dialog | Alto | Media | Estrarre 1:1, mantenere nomi/callback invariati, fare self-review diff mirata |
| Rieseguire il refactor partendo da baseline pulita può richiedere più passaggi | Medio | Media | Scomporre l’implementazione in slice piccoli e verificabili, senza riscritture massive |
| Spostamento involontario di logica fuori dai ViewModel | Medio | Bassa | Limitare le estrazioni a UI/composable e helper di confronto/formattazione locale |
| Frammentazione eccessiva in troppi file | Medio | Media | Fermarsi a 1-2 file di supporto con separazione netta per dialog/sections |
| Duplicare composable già estratti nel file (es. `TopInfoChipsBar`) | Medio | Media | Rileggere baseline prima di ogni slice; riusare/spostare, non copiare |

### Ordine di implementazione

1. Verifica governance TASK-013 / TASK-002 e rilettura `GeneratedScreen.kt` aggiornando la mappa blocchi rispetto all’audit baseline reale.
2. **Slice A — Scaffold / corpo schermata:** top bar / actions / overflow, grid host, FAB area; integrare `TopInfoChipsBar` esistente senza duplicazioni.
3. **Slice B1 — Dialog semplici:** search dialog + exit dialogs (discard / history / home) — **bassa complessità**, basso accoppiamento con `DatabaseViewModel`.
4. **Slice B2 — Rename / header dialogs (dedicata):** rename + custom header; qui il rename coinvolge **supplier/category** e **preload** via `DatabaseViewModel` (`onSupplierSearchQueryChanged` / `onCategorySearchQueryChanged` in `LaunchedEffect`) — **non** mischiare con B1.
5. **Slice C — Alto rischio, per ultimi:** info/edit row dialog (inclusa calcolatrice collegata se il wiring è nel blocco), **`ManualEntryDialog`** e stato circostante — estrarre solo quando A/B1/B2 sono stabili; preservare `CalculatorDialog` / `ManualEntryDialog` come implementazioni, eventualmente **spostare file** senza riscrittura.
6. **Slice D — Cleanup:** criteri accettazione 13–14, import, rimozione helper morti, commenti spurii; `assembleDebug`, `lint`; smoke checklist (non bloccante); poi `REVIEW`.

### Slice di implementazione consigliate

1. **Slice A — Scaffold / sezioni**  
   Top bar + overflow + grid host + FAB; riuso `TopInfoChipsBar` / chip esistenti.
2. **Slice B1 — Dialog semplici**  
   Search + exit (tutte le varianti uscita).
3. **Slice B2 — Rename + header**  
   Dialog rename (supplier/category + `DatabaseViewModel`) e dialog header custom **separati** da B1.
4. **Slice C — Dialog ad alto rischio (ultimi)**  
   Info/edit row + calcolatrice associata + manual entry wiring; spostamento file opzionale per `ManualEntryDialog` / `CalculatorDialog` senza cambiare comportamento.
5. **Slice D — Cleanup orchestratore**  
   Leggibilità `GeneratedScreen` come orchestratore, build/lint, smoke checklist.

### Lista file da leggere/toccare

- Leggere: `GeneratedScreen.kt`, `ZoomableExcelGrid.kt`, `ExcelViewModel.kt`, `DatabaseViewModel.kt`, `HistoryScreen.kt`, `NavGraph.kt` (verifica API freeze in review)
- Toccare: `GeneratedScreen.kt` e al massimo 1-2 nuovi file di supporto nello stesso package `ui/screens`
- Non toccare in esecuzione: `ExcelViewModel.kt`, `DatabaseViewModel.kt`, `ZoomableExcelGrid.kt`, **`NavGraph.kt`**

### Checklist review/build/lint

- Confermare che launcher/callback di search, scan, export/share, rename, header edit e manual entry non cambiano
- Verificare che back handler, dialog di uscita e completion toggle mantengano la stessa semantica
- Verificare snackbar “open database”, export/share/sync analysis invariati
- Verificare che non vengano modificati ViewModel, DAO, repository, Room o dipendenze
- **Review:** confronto statico del blocco `GeneratedScreen(...)` in `NavGraph.kt` pre/post (atteso: identico)
- Eseguire `./gradlew assembleDebug`
- Eseguire `./gradlew lint`
- Ricontrollare warning Kotlin/deprecation nei file toccati
- Cleanup: nessun marker “AGGIUNTO”, nessun helper morto

### Audit tecnico — mappa sezioni estraibili (raffinata)

| Sezione | Note baseline reale | Rischio regressione |
|---------|---------------------|---------------------|
| Top bar / actions / overflow | Ancora nel corpo principale; estrazione consigliata | Medio |
| Chips / info header | **Già** `TopInfoChipsBar` / `InfoChip` nel file — preservare | Basso |
| Grid host | Inline nel `Scaffold`; dipende da dialog row/header | Medio |
| FAB area | Inline; collegata a `scanLauncher`, search | Basso |
| Search dialog | Slice **B1** | Basso |
| Exit dialogs | Slice **B1** | Medio |
| Rename dialog (+ supplier/category, preload DB VM) | Slice **B2** dedicata | Medio–Alto |
| Custom header dialog | Slice **B2** (affiancare rename per coerenza file dialog “meta colonne”) | Medio |
| Info / edit row + calc | Slice **C**, **ultima** | Alto |
| `ManualEntryDialog` + wiring | Slice **C**, **ultima** | Alto |
| `CalculatorDialog` / keyboard | Già estratti — spostamento opzionale, no riscrittura | Medio |
| Helper / formatter puramente UI | Slice D | Basso |

---

## Execution

### Esecuzione — 2026-03-27 (audit tracking reale + gate pre-codice)

**File modificati:**
- `docs/MASTER-PLAN.md` — conferma tracciabile del passaggio `TASK-013` → `TASK-002`
- `docs/TASKS/TASK-013-ux-polish-filepicker-pregenerate.md` — chiusura documentale collegata alla conferma utente di questo turno
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — audit reale `HEAD` vs worktree e gate pre-codice per perimetro tecnico

**Azioni eseguite:**
1. Verificato lo stato reale della repo distinguendo `HEAD` e worktree: in `HEAD` risultavano `MASTER-PLAN` su `TASK-013 ACTIVE` e `TASK-013` in `REVIEW`; nel worktree era già presente una transizione parziale a `TASK-002` con file task ancora non tracciato e slice codice **B1** già applicata a `GeneratedScreen`
2. Validata la chiusura documentale di `TASK-013` senza inventare avanzamenti: codice UX presente in repo, review/fix già documentati, nessun fix aperto; la conferma utente richiesta dal workflow è stata ricevuta in questo turno con la richiesta esplicita di riallineare il tracking e procedere con `TASK-002`
3. Confermato il gate pre-codice di `TASK-002`: unico task attivo = `TASK-002`, perimetro = sola decomposizione tecnica, nessuna modernization UX/UI ammessa, ogni ambiguità visuale risolta preservando la UX attuale

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | N/A   | Turno di solo tracking/governance prima della ripresa sul codice |
| Lint                     | S    | N/A   | Turno di solo tracking/governance prima della ripresa sul codice |
| Warning Kotlin           | S    | N/A   | Nessun file Kotlin toccato in questa sottofase di audit |
| Coerenza con planning    | —    | ✅    | Riallineato il task attivo senza introdurre transizioni arbitrarie |
| Criteri di accettazione  | —    | In corso | Gate pre-codice soddisfatto; esecuzione Kotlin prosegue solo dopo l’audit |

**Incertezze:**
- Nessuna incertezza bloccante sul tracking dopo il riallineamento

**Handoff notes:**
- La ripresa sul codice parte dalla baseline reale del worktree: `GeneratedScreenDialogs.kt` esiste già e la slice **B1** (search + exit dialogs) è presente; le slice successive devono adattarsi a questo stato senza riscrivere da zero

### Esecuzione — 2026-03-27

**File modificati:**
- `docs/MASTER-PLAN.md` — task attivo riallineato a `TASK-002` in fase `PLANNING`
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — piano esecutivo raffinato su baseline pulita
- `docs/TASKS/TASK-013-ux-polish-filepicker-pregenerate.md` — chiusura documentale coerente al passaggio di task

**Azioni eseguite:**
1. Letta governance (`MASTER-PLAN`, `AGENTS`, `CLAUDE`, protocollo) e verificato il conflitto documentale `TASK-013` vs stato reale
2. Creato il task file di `TASK-002` e consolidato il planning operativo
3. Eseguito audit completo di `GeneratedScreen.kt` (worktree iniziale + baseline `HEAD`) per mappare le estrazioni senza cambio funzionale
4. Ripristinato `GeneratedScreen.kt` al baseline pulito di `HEAD` su richiesta utente; nessuna decomposizione codice lasciata applicata
5. Riportato `TASK-002` a `PLANNING` e raffinato il piano per un’esecuzione futura controllata

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | N/A   | Nessuna modifica codice persistente: task riportato a puro planning/governance |
| Lint                     | S    | N/A   | Nessuna modifica codice persistente: task riportato a puro planning/governance |
| Warning Kotlin           | S    | N/A   | Nessun file Kotlin modificato nel risultato finale del turno |
| Coerenza con planning    | —    | ✅    | Baseline pulita ripristinata e task mantenuto in planning su richiesta utente |
| Criteri di accettazione  | —    | —     | Da verificare in fase di esecuzione futura |

---

### Aggiornamento planning — 2026-03-27 (guardrail utente, pre-codice)

**File modificati:**

- `docs/MASTER-PLAN.md` — tabella coerenza TASK-013; dipendenze TASK-002; descrizione TASK-002 con API freeze e defer UX a TASK-014; mappa aree / osservazioni / rischio GeneratedScreen allineati a baseline reale
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — governance, baseline non monolitica, API freeze, NavGraph read-only + verifica review, slice dialog B1/B2/C, punti fragili, anti-astrazione, smoke checklist, criteri 13–14, ordine implementazione aggiornato

**Azioni eseguite:**

1. Riallineata governance: TASK-013 = `DONE` (backlog + file task); unico `ACTIVE` = TASK-002; nota esplicita su priorità rispetto a log storici
2. Rieseguito audit su `GeneratedScreen.kt` in workspace: ~2471 righe; elencati composable/helper già presenti (`TopInfoChipsBar`, `ManualEntryDialog`, `CalculatorDialog`, ecc.); slice adattate
3. Documentati API freeze, call site `NavGraph`, parametri `entryUid` / `isNewEntry` / `isManualEntry` / `onNavigateToDatabase`
4. Raffinate scomposizioni dialog (B1 search+exit, B2 rename+header con DB VM, C info/edit+manual ultimi)
5. Aggiunti guardrail snackbar/launcher/back/export e divieto di wrapper artificiali; smoke checklist non bloccante; criterio cleanup finale; perimetro stretto vs TASK-014

**Check obbligatori:** N/A (solo documentazione)

**Incertezze:**

- Nessuna incertezza bloccante sul planning; l’esecuzione futura partirà dalla baseline `HEAD` di `GeneratedScreen.kt` (file grande con helper/composable già presenti nello stesso file — vedi sezione «Baseline reale» nel planning aggiornato 2026-03-27)

---

### Esecuzione codice — 2026-03-27 (avvio TASK-002)

**Governance reale (pre-codice):**

| Controllo | Esito |
|-----------|--------|
| `MASTER-PLAN.md` backlog TASK-013 | `DONE` |
| `MASTER-PLAN.md` backlog TASK-002 | `ACTIVE` |
| Altri `ACTIVE` nel backlog | Nessuno (solo riga TASK-002) |
| `TASK-013` file task Stato | `DONE` |
| `MASTER-PLAN` Stato globale / fase | Allineati a **EXECUTION** per TASK-002 |

**File modificati:**

- `docs/MASTER-PLAN.md` — fase EXECUTION, obiettivo attuale, workflow con verifica governance reale pre-codice, razionale priorità
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — Stato `EXECUTION`, verifica repo, stop rule, conservazione UI, decisioni 8–10
- `app/.../GeneratedScreenDialogs.kt` — **nuovo**: dialog uscita (bozza manuale, cronologia, home) + dialog ricerca (slice **B1**)
- `app/.../GeneratedScreen.kt` — sostituiti blocchi inline con chiamate ai composable estratti; **stesso ordine di composition** (exit → FAB → manual → search → exit home → info…)

**Slice completata in questo turno:** **B1** (search + exit dialogs). Restano pianificate: A (top bar / grid / FAB), B2 (rename + header), C (info/edit + manual wiring), D (cleanup #14).

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ⚠️ NON ESEGUIBILE | Ambiente agente senza JRE (`./gradlew assembleDebug` fallisce: «Unable to locate a Java Runtime») — rieseguire su macchina con JDK |
| Lint | ⚠️ NON ESEGUIBILE | Dipende da Gradle/Java locale |
| Warning / IDE | ✅ | `read_lints` su file Kotlin toccati: nessun diagnostico |
| Coerenza planning | ✅ | Stop rule / UI freeze / nessun touch a VM, NavGraph, grid |
| Criteri accettazione | In corso | B1 verso #5; criteri 1–2 da chiudere con build locale |

**Incertezze:**

- Build/lint vanno ripetuti dall’utente su Android Studio / JDK installato.

---

### Esecuzione — 2026-03-27 (completamento decomposizione tecnica)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — ridotto il corpo di `GeneratedScreen` a orchestratore: estratti top bar, grid host, FAB area, dialog info/edit row, dialog rename e dialog header; puliti marker/commenti temporanei e warning deprecati introdotti dal refactor
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — mantenuti i dialog semplici della slice B1 (search + exit) già presenti nel worktree e verificati come invarianti nel wiring
- `docs/MASTER-PLAN.md` — fase globale aggiornata a `REVIEW` per `TASK-002`
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — evidenze finali di esecuzione, criteri, smoke checklist e handoff review

**Azioni eseguite:**
1. Rieseguito audit della baseline reale di `GeneratedScreen.kt` tenendo la slice **B1** già presente nel worktree come punto di partenza, senza riscriverla
2. Estratti in helper dedicati `GeneratedScreenTopBar`, `GeneratedScreenGridHost` e `GeneratedScreenFabArea`, mantenendo invariati ordine azioni, launcher, snackbar, back handling e gerarchia della schermata
3. Separati dal corpo principale `GeneratedScreenInfoDialog`, `GeneratedScreenRenameDialog`, `GeneratedScreenCustomHeaderDialog` e `GeneratedScreenHeaderTypeDialog`; `ManualEntryDialog` è rimasto separato come top-level composable esistente
4. Preservati rigidamente `scanLauncher`, `dialogScanLauncher`, `saveLauncher`, export/share, rename, search, edit row, header edit e manual entry wiring; nessun tocco a `NavGraph`, `ExcelViewModel`, `DatabaseViewModel`, `ZoomableExcelGrid`, DAO o repository
5. Rimossi marker temporanei e allineato il refactor a `ExposedDropdownMenuAnchorType` per non introdurre warning nuovi nel file modificato
6. Eseguiti `assembleDebug` e `lint` con il JBR di Android Studio; verificato che il report lint non segnali `GeneratedScreen.kt` o `GeneratedScreenDialogs.kt`

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s` |
| Lint                     | S    | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lint` → `BUILD SUCCESSFUL in 18s`; report in `app/build/reports/lint-results-debug.html` |
| Warning Kotlin           | S    | ✅ ESEGUITO | Nessun warning Kotlin sul codice modificato; corretti i warning deprecati introdotti dal refactor (`ExposedDropdownMenuAnchorType`) |
| Coerenza con planning    | —    | ✅ ESEGUITO | Completate le slice previste senza allargare il perimetro a VM/NavGraph/Grid o a redesign UX |
| Criteri di accettazione  | —    | ✅ ESEGUITO | Tutti i 14 criteri verificati sotto con evidenze statiche concrete |

**Criteri di accettazione — dettaglio finale:**

| # | Tipo | Stato | Evidenza |
|---|------|-------|----------|
| 1 | B | ✅ ESEGUITO | `assembleDebug` riuscito (`BUILD SUCCESSFUL in 4s`) |
| 2 | S | ✅ ESEGUITO | `lint` riuscito (`BUILD SUCCESSFUL in 18s`); nessun riferimento a `GeneratedScreen.kt` o `GeneratedScreenDialogs.kt` nel report text |
| 3 | S | ✅ ESEGUITO | `GeneratedScreen` ora orchestra helper dedicati per top bar, grid host, FAB, info/edit row e dialog meta mantenendo lo stesso wiring |
| 4 | S | ✅ ESEGUITO | `GeneratedScreenTopBar`, `GeneratedScreenGridHost` e `GeneratedScreenFabArea` isolano top bar/actions/overflow, chips/info header, grid host e fab area |
| 5 | S | ✅ ESEGUITO | `GeneratedScreenSearchDialog` e dialog uscita stanno in `GeneratedScreenDialogs.kt`; `GeneratedScreenInfoDialog` e `ManualEntryDialog` restano separati dal corpo principale |
| 6 | S | ✅ ESEGUITO | Build verde dopo il cleanup; nessun helper mancante o sezione intermedia lasciata incompleta |
| 7 | S | ✅ ESEGUITO | Nessuna modifica a ViewModel, DAO, repository, entity, Room, navigation, import/export Excel o barcode flow |
| 8 | S | ✅ ESEGUITO | Nessuna dipendenza aggiunta e nessun refactor architetturale ampio; solo estrazioni UI/composable 1:1 |
| 9 | S | ✅ ESEGUITO | Search, scan, export/share, rename, header edit, info/edit row e manual entry mantengono launcher/callback e logica utente-visibile esistenti |
| 10 | S | ✅ ESEGUITO | Helper UI estratti solo per leggibilità; formatter e logica di supporto mantengono output e comportamento |
| 11 | S | ✅ ESEGUITO | Self-review del diff conferma perimetro tecnico puro: nessuna nuova stringa, nessun cambio layout intenzionale, nessun redesign UX |
| 12 | S | ✅ ESEGUITO | Task file aggiornato con audit, esecuzione, build/lint e passaggio a `REVIEW` solo dopo verifiche concluse |
| 13 | S | ✅ ESEGUITO | Firma pubblica `GeneratedScreen(...)` invariata; `NavGraph.kt` non modificato e call site preservato |
| 14 | S | ✅ ESEGUITO | Rimossi marker temporanei, warning deprecati introdotti dal refactor e helper morti; `GeneratedScreen` resta leggibile come orchestratore |

**Smoke checklist manuale (non bloccante, non eseguita nel turno corrente):**

| Flusso | Stato | Note |
|--------|-------|------|
| Back da nuova entry | ⚠️ NON ESEGUIBILE | Nessun emulator/device nel turno corrente |
| Back da entry storica | ⚠️ NON ESEGUIBILE | Nessun emulator/device nel turno corrente |
| Ricerca testuale | ⚠️ NON ESEGUIBILE | Nessun emulator/device nel turno corrente |
| Scan barcode | ⚠️ NON ESEGUIBILE | Nessun emulator/device / camera nel turno corrente |
| Export/share | ⚠️ NON ESEGUIBILE | Nessuna verifica manuale UI nel turno corrente |
| Apertura e salvataggio manual entry / edit row | ⚠️ NON ESEGUIBILE | Nessuna verifica manuale UI nel turno corrente |

**Incertezze:**
- Nessuna incertezza bloccante emersa durante l’esecuzione; restano solo smoke manuali opzionali fuori dal perimetro minimo statico del task

**Handoff notes:**
- Stato task portato a `REVIEW` con build/lint verdi e criteri statici chiusi
- `NavGraph.kt`, `ExcelViewModel.kt`, `DatabaseViewModel.kt` e `ZoomableExcelGrid.kt` sono rimasti invariati come da stop rule
- Per eventuale review manuale, concentrare il controllo su back flow, search/scanner, export/share e dialog info/manual entry

### Esecuzione — 2026-03-27 (micro-pass finale robustezza/wiring)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — corretto il gating dei dialog header, reso più robusto `openRenameDialog()` e chiusa la pulizia minima del wiring/stato temporaneo
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreenDialogs.kt` — allineata la label del dismiss button del search dialog a `cancel` mantenendo il comportamento invariato
- `docs/MASTER-PLAN.md` — riallineata l’ultima frase narrativa rimasta su `EXECUTION` con lo stato globale reale `REVIEW`
- `docs/TASKS/TASK-002-decomposizione-generatedscreen.md` — log del micro-pass con evidenze build/lint reali

**Azioni eseguite:**
1. Corretto il flow dei dialog header: `GeneratedScreenHeaderTypeDialog` viene renderizzato solo quando `headerDialogIndex != null && !showCustomHeaderDialog`, evitando due `AlertDialog` contemporanei nello stesso flow
2. Rafforzato il reset del rename dialog: `openRenameDialog()` ora riallinea `renameText`, supplier/category correnti e chiude sempre gli expanded menu; su confirm e dismiss gli expanded menu vengono richiusi anche quando il rename non procede
3. Allineata la label del dismiss button del search dialog da `clear` a `cancel` senza cambiare la logica esistente del bottone
4. Rimosso il commento residuo di tuning in `GeneratedScreen.kt`, verificati import/helper usati e riallineata la frase narrativa restante in `MASTER-PLAN.md`
5. Eseguita self-review del diff del micro-pass per confermare assenza di scope creep: nessuna modifica a ViewModel, DAO, repository, `NavGraph.kt` o `ZoomableExcelGrid.kt`

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 7s` |
| Lint                     | S    | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lint` → `BUILD SUCCESSFUL in 15s`; report in `app/build/reports/lint-results-debug.html` |
| Warning Kotlin           | S    | ✅ ESEGUITO | Nessun warning segnalato per `GeneratedScreen.kt` o `GeneratedScreenDialogs.kt` nel report lint (`rg` senza match sui file modificati) |
| Coerenza con planning    | —    | ✅ ESEGUITO | Micro-pass limitato a robustezza/wiring e cleanup finale; nessun redesign UX/UI, nessun allargamento di perimetro |
| Criteri di accettazione  | —    | ✅ ESEGUITO | Nessun criterio riaperto: il micro-pass preserva task in `REVIEW` con verifiche statiche nuovamente verdi |

**Incertezze:**
- Nessuna incertezza bloccante; restano solo smoke manuali opzionali già documentati

**Handoff notes:**
- `TASK-002` resta in `REVIEW`; il micro-pass non cambia il perimetro del task né il requisito di review/conferma utente prima di `DONE`
- Controllo mirato consigliato in review: flow header dialog custom/standard e riapertura rename dialog dopo interazioni precedenti

---

## Review

### Review — 2026-03-27

**Revisore:** pianificazione / tracking (su decisione utente)

**Contesto verifica:** decomposizione tecnica `GeneratedScreen` portata in **REVIEW** con evidenze statiche positive (build/lint riusciti, criteri 1–14 verificati staticamente nel log `Execution` del 2026-03-27). **Smoke test manuali** della checklist finale **non eseguiti**, **rimandati esplicitamente dall’utente**.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Build | ✅ | Evidenza in Execution: `assembleDebug` OK (JBR Android Studio) |
| 2 | Lint | ✅ | Evidenza in Execution: `lint` OK |
| 3–14 | Statici / API freeze / cleanup | ✅ | Come da log Execution completamento + micro-pass |
| Smoke checklist (back, search, scan, export, manual/edit) | ⚠️ **NON ESEGUITI** | Rimandati dall’utente; nessun difetto statico aperto sul diff |

**Problemi trovati:**

- Nessun **difetto statico** o **FIX_REQUIRED** emerso sulla base delle verifiche documentate.
- **Gap:** assenza di **verifica manuale** su dispositivo/emulatore — accettato come blocco temporaneo per **non** dichiarare `DONE`.

**Verdetto:** **NON APPROVATO A `DONE`**. Transizione a **`BLOCKED`** per decisione utente: task **sospeso** in attesa di **smoke manuale futura** (o nuova esplicita scelta prodotto). La review statica resta **positiva** sul perimetro tecnico completato; il blocco è **solo** su chiusura formale senza test manuali.

**Motivazione `BLOCKED`:** smoke test rimandati dall’utente; nessun allargamento scope; nessun bug TASK-002 da correggere nel codice in questa fase. Il crash **full import database (OOM)** è **fuori perimetro** TASK-002 e viene tracciato in **TASK-017**.

---

## Fix

### Fix — [data]

**Correzioni applicate:**
- [Descrizione correzione]

**Ri-verifica:**
- [Evidenza che il problema è risolto]

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **BLOCKED** (non DONE) |
| Data                   | 2026-03-27 |
| Motivo                 | Smoke manuale rimandato dall’utente; chiusura formale `DONE` non autorizzata |
| Evidenze statiche      | Build/lint OK come da Execution |
| Tutti i criteri ✅?    | Statici sì; smoke ⚠️ non eseguiti |
| Rischi residui         | Regressione UI/flow solo su base non coperta da smoke |

---

## Riepilogo finale

[Sintesi di ciò che è stato fatto, decisioni chiave, rischi residui]

---

## Handoff

- **Stato:** **`BLOCKED`** — non **`DONE`**. Review statica positiva; smoke manuale **non eseguito** (rimandato dall’utente).
- **Ripresa:** quando l’utente vorrà chiudere formalmente: eseguire smoke checklist, aggiornare Execution, passare a **REVIEW** → conferma → **DONE** (o rivalutare `WONT_DO` / criteri).
- **Scope TASK-002:** crash **OOM full import database** → **TASK-017** (non mescolare).
- **Scope rispettato (lavoro svolto):** nessun cambio a `NavGraph`, `ExcelViewModel`, `DatabaseViewModel`, `ZoomableExcelGrid`, DAO, repository o UX percepita oltre decomposizione UI.
- **Smoke manuale pendente:** back nuova/storica entry, search/scanner, export/share, manual entry / edit row.
- **Task attivo backlog:** **TASK-017** fino a nuovo riallineamento.

### Tracking — passaggio a BLOCKED (2026-03-27)

**Azioni:** aggiornato stato task a **`BLOCKED`**; inserita review con verdetto “non DONE”; **`MASTER-PLAN`** aggiornato: TASK-002 → `BLOCKED`, **TASK-017** → unico `ACTIVE`. **Nessuna modifica a codice Kotlin** in questo turno.

**File modificati (solo documentazione):** `docs/TASKS/TASK-002-decomposizione-generatedscreen.md`, `docs/TASKS/TASK-017-crash-full-db-import-oom.md`, `docs/MASTER-PLAN.md`.
