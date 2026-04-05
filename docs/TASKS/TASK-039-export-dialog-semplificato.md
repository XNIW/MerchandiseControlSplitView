# TASK-039 — Export dialog semplificato (DatabaseScreen)

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-039 |
| Stato                | **DONE** |
| Priorità             | BASSA |
| Area                 | UX / UI / DatabaseScreen |
| Creato               | 2026-04-05 |
| Ultimo aggiornamento | 2026-04-05 — review veloce repo-grounded positiva sul rollback richiesto dall’utente; task chiusa in **DONE** su istruzione utente esplicita, con nota che la direzione **preset-only** è stata superata dal ripristino del dialog precedente |

---

## Dipendenze

- **Nessuna**

---

## Scopo

Semplificare **`DatabaseExportDialog`**: oggi espone **due** modi paralleli di scelta (`FilterChip` preset + righe **Checkbox** per foglio). Il task richiede **un solo** modello in dialogo: **solo preset** *oppure* **solo selezione manuale per foglio**, mai entrambi. Intervento **locale** al dialog e alle stringhe strettamente necessarie; **nessuno** scope creep su export engine, ViewModel o storage.

---

## Contesto

- Backlog: `docs/MASTER-PLAN.md` — **TASK-039** (`BACKLOG`, area DatabaseScreen).
- Riferimento incrociato: `docs/TASKS/TASK-034-...md` e `TASK-033` indicano redesign export → **TASK-039**.
- **TASK-021** (`DONE`) ha introdotto export unificato con `ExportSheetSelection` e dialog con preset **e** checkbox; i quattro preset mappano a `full()`, `productsOnly()`, `catalogOnly()`, `priceHistoryOnly()`.

---

## Non incluso (fuori scope)

- Modifiche a **`DatabaseViewModel.exportDatabase`**, **`DatabaseExportWriter`**, **`ExportSheetSelection`** (data class / factory), DAO, repository, Room, query di fetch condizionale.
- Modifiche a **`NavGraph`**, SAF / `CreateDocument`, `exportLauncher`, permessi, share intent.
- Nuove dipendenze Gradle; nuove API pubbliche ViewModel/repository.
- Aggiunta di **nuovi** preset o combinazioni foglio oltre le quattro già definite — salvo decisione esplicita futura fuori da questo task (qui si riduce UI, non si estende il prodotto).
- Redesign di **`DatabaseScreen`** oltre il wiring minimo se emergesse (idealmente **zero** righe in `DatabaseScreen.kt`).
- Test UI strumentati (Compose/Espresso) — non richiesti salvo decisione futura.
- Porting 1:1 da eventuale iOS; iOS solo riferimento visivo opzionale, non fonte di logica.

---

## File coinvolti (perimetro reale osservato nel codice)

| File | Ruolo |
|------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` | **`DatabaseExportDialog`** (L167–275): `AlertDialog` con titolo/sottotitolo, blocco **preset** (`FlowRow` + `FilterChip`), `HorizontalDivider`, blocco **checkbox** (`DatabaseExportSheet.entries` + `ExportSheetSelectionRow`), `Surface` informativo full/partial. Composable privato **`ExportSheetSelectionRow`** (L278–315) usato solo da questo dialog. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` | Call site: `showExportDialog`, `requestedExportSelection`, `DatabaseExportDialog(..., onSelectionChange, onConfirm, onDismiss)`, `buildDatabaseExportDisplayName(requestedExportSelection)`. **Probabile:** nessun cambio se la firma del dialog resta compatibile. |
| `app/src/main/java/com/example/merchandisecontrolsplitview/util/DatabaseExportWriter.kt` | Contiene **`ExportSheetSelection`** e `buildDatabaseExportDisplayName`. **Fuori scope** salvo emergenza tecnica non prevista (non attesa). |
| `app/src/main/res/values/strings.xml` e `values-en` / `values-es` / `values-zh` | Chiavi già usate: `export_database_dialog_*`, `export_preset_*`, `export_sheet_*`. In **EXECUTION** possono servire aggiustamenti copy (es. sottotitolo) se si elimina una delle due sezioni UI. |

---

## Comportamento attuale osservato (evidenza codice)

1. All’apertura da toolbar export (`onExportClick` → `showExportDialog = true`), lo stato selezione è **`ExportSheetSelection.full()`** (default in `DatabaseScreen.kt` L61).
2. Nel dialog:
   - **Preset:** quattro `FilterChip`; al tap si imposta l’intera `ExportSheetSelection` al preset corrispondente (`full`, `productsOnly`, `catalogOnly`, `priceHistoryOnly`).
   - **Manuale:** quattro righe con `Checkbox` aggiornano via `selection.withSheet(sheet, checked)`.
3. I due meccanismi sono **sincronizzati solo indirettamente**: un preset può essere selezionato come chip «selected» (`selection == presetSelection`), ma l’utente può poi alterare i checkbox e **rompere** l’uguaglianza con un preset — modello duplicato e potenzialmente confusionario.
4. Il **`Surface`** in basso mostra copy **full** vs **partial** in base a `selection.isFullExport`.
5. Conferma: bottone **Esporta** abilitato se `!selection.isEmpty && !exportInProgress`.

---

## Problema UX concreto

- **Doppia interfaccia** per lo stesso concetto (quali fogli esportare): preset e checkbox competono per l’attenzione e possono divergere (chip su un preset ma combinazione checkbox diversa).
- **Carico cognitivo** e altezza dialog maggiori del necessario per un’azione frequente o occasionale di export.

---

## Proposta di direzione UX consigliata (PLANNING)

**Raccomandazione: solo preset (quattro `FilterChip`), rimuovere il blocco checkbox e `HorizontalDivider` intermedio.**

**Motivazione:**

- Allineata a **TASK-021**: i quattro preset sono già il contratto UX/documentato e coprono i casi d’uso dichiarati (full backup, solo prodotti, catalogo S+C, solo price history).
- **Minimo cambiamento**: si modifica soprattutto un unico composable (`DatabaseExportDialog`) e si può eliminare `ExportSheetSelectionRow` se non più referenziato.
- Il modello mentale resta **una sola scelta** tra opzioni chiuse, coerente con Material3 (chips come scelta mutuamente esclusiva tra preset — verificare in EXECUTION che la selezione visiva rifletta sempre un preset esatto; vedi § Rischi).

**Trade-off documentato (accettato per questo task se si approva la direzione preset-only):**

- Con **solo preset** non è più possibile dal dialog ottenere combinazioni **arbitrarie** (es. solo `PRODUCTS` + `PRICE_HISTORY` senza `SUPPLIERS`/`CATEGORIES`) che oggi i checkbox consentirebbero. Le factory in `ExportSheetSelection` restano nel codice per i test e il writer; sparisce solo l’UI per comporle manualmente.

---

## Alternative considerate e scartate (in PLANNING)

| Alternativa | Perché scartata / deprioritizzata |
|-------------|-----------------------------------|
| **Solo checkbox** (rimuovere preset) | Valida rispetto al vincolo «non entrambi», ma dialog più lungo e senza scorciatoie per i quattro casi già nominati nel prodotto; meno allineata al lavoro già fatto in TASK-021. |
| **Tab / segmented: «Preset» | «Avanzate»** | Reintroduce due modalità nello stesso dialog (stesso problema di duplicazione percepita). |
| **Due step wizard** | Scope creep e complessità fuori da «minimo cambiamento». |
| **Sheet / bottom sheet al posto di AlertDialog** | Redesign contenitore non richiesto; TASK-037 ha già allineato shape/elevazione dialog. |

*Nota:* se l’utente preferisse esplicitamente **solo manuale**, il piano di EXECUTION va riscritto di conseguenza prima del codice (stessi file, diverso blocco da rimuovere).

---

## Rischi e regressioni possibili

| Rischio | Mitigazione in EXECUTION |
|---------|---------------------------|
| Utenti che si affidavano a combinazioni custom non coperte dai preset | Documentare nel log Execution / handoff; accettazione prodotto implicita nella scelta preset-only. |
| Copy (subtitle / label) ancora scritto come «scegli i fogli» mentre l’UI è solo preset | Aggiornare stringhe in tutti i `values*` interessati in modo coerente. |
| Chip «selected» ambiguo se si tentasse di mantenere stato non-preset | Con preset-only, ogni conferma deve corrispondere a uno dei quattro `ExportSheetSelection` factory; nessun toggle checkbox che lasci stato intermedio. |
| Regressioni visive (spacing, scroll) su schermi piccoli | Verifica manuale dialog dopo il taglio del contenuto. |
| Import / file name | `buildDatabaseExportDisplayName` invariato se `ExportSheetSelection` resta una delle quattro combinazioni; nessun cambio atteso. |

---

## Criteri di accettazione (verificabili)

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Il dialog export **non** mostra contemporaneamente preset **e** lista checkbox per foglio: un solo modello di scelta, come deciso in approvazione pre-EXECUTION. | S + M | — |
| 2 | Restano esportabili (via UI) le **stesse quattro intenzioni** di TASK-021: tutto, solo prodotti, catalogo (fornitori+categorie), solo price history — mapping invariato a `ExportSheetSelection`. | M | — |
| 3 | `Export` abilitato solo con selezione non vuota e export non in corso; dismiss/back come oggi. | M | — |
| 4 | Copy full vs partial (informativo) ancora sensato rispetto a `isFullExport` / selezione. | S + M | — |
| 5 | Nessuna modifica a DAO, repository, Room, `DatabaseViewModel` export path, `DatabaseExportWriter`, navigazione — salvo emergenza motivata nel file task. | S (review diff) | — |
| 6 | `./gradlew assembleDebug` — BUILD SUCCESSFUL. | B | — |
| 7 | `./gradlew lint` — nessun warning **nuovo** imputabile al diff. | S | — |

**Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): applicare checklist MASTER-PLAN; eventuali micro-UX intenzionali tracciati nel log Execution (`AGENTS.md`).

---

## Check / build / test previsti per la futura EXECUTION

| Step | Note |
|------|------|
| `./gradlew assembleDebug` | Obbligatorio (`AGENTS.md`). |
| `./gradlew lint` | Obbligatorio. |
| Verifica manuale | Aprire Database → Export → ciclo conferma; provare i quattro preset; verificare nome file suggerito e assenza di UI duplicata. |
| **Baseline TASK-004** | **N/A attesa** se il diff si limita a `DatabaseExportDialog` (+ stringhe) **senza** modifiche a `DatabaseViewModel`, repository, import/export logic. Se in EXECUTION si toccasse codice coperto da `DatabaseViewModelTest` / export writer tests, rivalutare ed eseguire la suite mirata o `./gradlew test` e documentare. |

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Direzione UX raccomandata: **preset-only** | Vedi § Proposta; allineamento TASK-021 e minimo cambiamento. | 2026-04-05 |
| 2 | Transizione `PLANNING → EXECUTION` | Solo dopo **approvazione esplicita** utente su direzione (preset-only vs solo manuale). | — |

---

## Planning (Claude) — sintesi operativa per EXECUTION futura

1. Ottenere conferma utente: **preset-only** (default raccomandato) **oppure** **solo checkbox**.
2. Editare `DatabaseExportDialog`: rimuovere la sezione non scelta (preset **o** checkbox), divider/label superflui, e il composable `ExportSheetSelectionRow` se diventa dead code.
3. Allineare stringhe localizzate (sottotitolo / etichette) al nuovo modello, tutti i `values*` già citati.
4. `DatabaseScreen.kt`: toccare solo se necessario (es. default state — improbabile).
5. Build, lint, smoke manuale export; aggiornare sezione **Execution** nel file task.

---

## Execution

### Gate planning — 2026-04-05

**Approvazione utente:** avvio **EXECUTION** autorizzato esplicitamente dall’utente con direzione UX non negoziabile **preset-only**.

**Governance:** `docs/MASTER-PLAN.md` non riportava task attivo al momento della lettura; execution avviata su approvazione utente senza aggiornare lo stato globale, in coerenza con il prompt del turno.

### Esecuzione — 2026-04-05

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — rimosso il blocco checkbox/divider, eliminato `ExportSheetSelectionRow`, mantenuti solo i quattro preset esistenti e compattato il body del dialog per overflow su layout compatti / font grandi
- `app/src/main/res/values/strings.xml` — copy dialog riallineato al modello “scegli cosa esportare”; rimosse stringhe morte collegate alla sezione checkbox e al label ridondante dei preset
- `app/src/main/res/values-en/strings.xml` — stesso riallineamento copy + rimozione stringhe morte
- `app/src/main/res/values-es/strings.xml` — stesso riallineamento copy + rimozione stringhe morte
- `app/src/main/res/values-zh/strings.xml` — stesso riallineamento copy + rimozione stringhe morte
- `docs/TASKS/TASK-039-export-dialog-semplificato.md` — aggiornato log Execution / review-ready

**Azioni eseguite:**
1. Riletto planning e codice reale (`DatabaseScreenDialogs.kt`, `DatabaseScreen.kt`, `DatabaseExportWriter.kt`, `values*`) confermando che la direzione approvata fosse **preset-only** e che il wiring esistente potesse restare invariato.
2. Semplificato `DatabaseExportDialog` rimuovendo `HorizontalDivider`, lista checkbox per foglio e il composable morto `ExportSheetSelectionRow`, lasciando solo i quattro `FilterChip` già previsti da `TASK-021`.
3. Lasciati invariati call site, default iniziale `ExportSheetSelection.full()` in `DatabaseScreen.kt`, gating `!selection.isEmpty && !exportInProgress`, dismiss e wiring verso `CreateDocument`.
4. Allineato il copy localizzato al modello “scegli cosa esportare” in `values`, `values-en`, `values-es`, `values-zh` e rimosse le stringhe morte `export_database_dialog_sheets_label` / `export_database_dialog_presets_label`.
5. UI/UX: resa la colonna del body scrollabile in overflow e ridotto leggermente spacing, padding e tipografia del box informativo per mantenere compattezza e leggibilità senza cambiare preset o semantica export.
6. UI/UX: rimosso il label ridondante sopra i chip dopo il passaggio preset-only per recuperare altezza utile e ridurre rumore visivo; motivazione: chiarezza e migliore resa in condizioni compatte / font grandi.
7. Eseguito smoke manuale su `emulator-5554`: apertura dialog, tap sui quattro preset, verifica preset completo e non completi, conferma export, dismiss via back e tap outside, nome file suggerito full/partial, controllo compatto `720x1280` con `font_scale=1.3`.
8. Eseguiti `assembleDebug` e `lint`; corretto durante l’execution un unico incidente locale di import (`HorizontalDivider`) emerso dopo il cleanup degli import, senza impatti fuori scope.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 9s` (rieseguito finale dopo i micro-polish compatti) |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 14s`; nessun warning/lint nuovo sui file toccati |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning nuovo attribuibile al diff; restano solo warning/toolchain AGP-Kotlin preesistenti e deprecazioni già presenti fuori perimetro |
| Coerenza con planning    | ✅ ESEGUITO | Diff confinato a `DatabaseExportDialog` + stringhe/localizzazioni + task file; nessuna modifica a `DatabaseViewModel`, writer, DAO, repository, Room, navigation, SAF/share intent |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti i criteri verificati singolarmente nella tabella seguente |

**Dettaglio criteri di accettazione:**
| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Il dialog export **non** mostra contemporaneamente preset **e** lista checkbox per foglio | S + M | ESEGUITO | Screenshot/dump emulator mostrano solo i quattro chip preset; rimossi divider e righe checkbox |
| 2 | Restano esportabili (via UI) le **stesse quattro intenzioni** di `TASK-021` con mapping invariato a `ExportSheetSelection` | M | ESEGUITO | Verificati `Tutto`, `Solo prodotti`, `Anagrafica`, `Solo storico prezzi`; mutua esclusione confermata da dump UI (`checked_count=1`) |
| 3 | `Export` abilitato solo con selezione non vuota e export non in corso; dismiss/back come oggi | S + M | ESEGUITO | Bottone `Esporta` attivo con preset non vuoti; gate `!selection.isEmpty && !exportInProgress` invariato a codice; dismiss verificato con `Back` e tap outside |
| 4 | Copy full vs partial (informativo) ancora sensato rispetto a `isFullExport` / selezione | S + M | ESEGUITO | `Tutto` mostra copy full; `Solo prodotti`, `Anagrafica`, `Solo storico prezzi` mostrano copy partial; in compatto `720x1280` + `font_scale=1.3` il box informativo resta visibile dopo il polish finale |
| 5 | Nessuna modifica a DAO, repository, Room, `DatabaseViewModel` export path, `DatabaseExportWriter`, navigazione | S | ESEGUITO | Diff reale limitato a `DatabaseScreenDialogs.kt`, `values*` e questo task file |
| 6 | `./gradlew assembleDebug` — BUILD SUCCESSFUL | B | ESEGUITO | `BUILD SUCCESSFUL in 9s` |
| 7 | `./gradlew lint` — nessun warning **nuovo** imputabile al diff | S | ESEGUITO | `BUILD SUCCESSFUL in 14s`; nessuna issue nuova nei file toccati |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: N/A — diff confinato a composable dialog + stringhe localizzate, senza modifiche a ViewModel/repository/import-export logic coperti da TASK-004
- Test aggiunti/aggiornati: nessuno
- Limiti residui: nessuno per la baseline TASK-004

**Incertezze:**
- Nessuna sul perimetro finale del task
- `docs/MASTER-PLAN.md` risultava già dirty e non è stato toccato in questo turno, come richiesto

**Handoff notes:**
- `TASK-039` pronto per review repo-grounded
- Smoke manuale eseguito su `emulator-5554` in italiano; dopo la verifica compatta l’emulator è stato ripristinato a size default e `font_scale=1.0`
- Filename suggeriti verificati: preset full `Database_2026_04_05_16-33-28.xlsx`; preset partial price history `Database_partial_PH_2026_04_05_16-32-53.xlsx`

### Esecuzione — rollback utente 2026-04-05

**Trigger:**
- Dopo la verifica visiva del dialog semplificato, l’utente ha chiesto esplicitamente il ritorno all’opzione 1: **ripristino esatto del dialog precedente** con preset + checkbox per foglio.

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — ripristinato il doppio modello precedente (preset + checkbox) con `ExportSheetSelectionRow`, divider intermedio e body originale del dialog
- `app/src/main/res/values/strings.xml` — ripristinato il copy precedente del dialog (`subtitle`, `Preset rapidi`, `Fogli da esportare`)
- `app/src/main/res/values-en/strings.xml` — stesso ripristino copy precedente
- `app/src/main/res/values-es/strings.xml` — stesso ripristino copy precedente
- `app/src/main/res/values-zh/strings.xml` — stesso ripristino copy precedente
- `docs/TASKS/TASK-039-export-dialog-semplificato.md` — aggiornato log di rollback / handoff

**Azioni eseguite:**
1. Ripristinato `DatabaseExportDialog` alla variante precedente con preset + selezione manuale per foglio, in risposta alla richiesta utente di tornare al comportamento originario.
2. Ripristinati `ExportSheetSelectionRow`, `selection.withSheet(...)` e le stringhe/localizzazioni precedenti, senza toccare `DatabaseScreen.kt`, `DatabaseViewModel`, `DatabaseExportWriter`, DAO, repository, Room, navigation o SAF/share intent.
3. Rieseguiti `assembleDebug` e `lint` dopo il rollback.
4. Eseguito smoke manuale su `emulator-5554` per verificare il ritorno del layout precedente e la possibilità di combinazioni manuali custom.

**Check rollback:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s` |
| Lint | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL in 14s` |
| Smoke manuale dialog export | ✅ ESEGUITO | Dialog precedente visibile su emulator; `Preset rapidi` + `Fogli da esportare` ripristinati; verificata combinazione manuale custom con filename suggerito `Database_partial_C_PH_2026_04_05_16-52-47.xlsx` |

**Esito operativo:**
- Il comportamento richiesto dall’utente è stato ripristinato.
- Questo rollback reintroduce intenzionalmente il modello doppio preset + checkbox e quindi **non** è più coerente con i criteri di accettazione attuali di `TASK-039` basati su direzione **preset-only**.
- Il task viene quindi lasciato in **`BLOCKED`** finché il planner non riallinea planning/criteri alla decisione utente.

---

## Review

### Review-ready — 2026-04-05

- Nessuna review eseguita in questo turno: la sezione resta solo come consegna verso il reviewer.
- Diff pronto per review repo-grounded, confinato a `DatabaseExportDialog`, localizzazioni `values*` e questo file task.
- Focus review consigliato: confermare coerenza preset-only, assenza di regressioni su filename suggerito e resa del box informativo in condizioni compatte / font grandi.

---

## Chiusura

### Chiusura — 2026-04-05

| Campo | Valore |
|-------|--------|
| Stato finale | `DONE` |
| Motivo chiusura | Ripristino del dialog precedente su richiesta utente esplicita + review veloce positiva sullo stato finale |

**Review rapida repo-grounded:**
- Nessun finding sostanziale sullo stato finale ripristinato.
- Dialog export tornato al comportamento precedente con preset + checkbox; `assembleDebug` e `lint` verdi; smoke manuale su emulator positivo anche per combinazione manuale custom.

**Esito finale criteri di accettazione:**
| # | Criterio | Stato finale | Evidenza |
|---|----------|--------------|----------|
| 1 | Il dialog export non mostra contemporaneamente preset e lista checkbox per foglio | NON ESEGUITO | Motivazione accettata: l’utente ha richiesto esplicitamente il ripristino del comportamento precedente con preset + checkbox e la chiusura della task |
| 2 | Restano esportabili via UI le stesse quattro intenzioni di TASK-021 | ESEGUITO | Verificati preset e combinazioni manuali; comportamento precedente ripristinato |
| 3 | `Export` abilitato solo con selezione non vuota e export non in corso; dismiss/back come oggi | ESEGUITO | Gating invariato a codice; smoke manuale positivo |
| 4 | Copy full vs partial ancora sensato rispetto a `isFullExport` / selezione | ESEGUITO | Ripristinato copy originario; messaggio partial verificato su combinazione custom |
| 5 | Nessuna modifica a DAO, repository, Room, `DatabaseViewModel`, `DatabaseExportWriter`, navigazione salvo emergenza | ESEGUITO | Nessun intervento su questi file |
| 6 | `./gradlew assembleDebug` BUILD SUCCESSFUL | ESEGUITO | `BUILD SUCCESSFUL in 4s` |
| 7 | `./gradlew lint` senza warning nuovi imputabili al diff | ESEGUITO | `BUILD SUCCESSFUL in 14s`; nessun warning nuovo attribuibile al rollback |

**Rischi residui:**
- Bassi sul comportamento runtime: l’app è tornata al baseline precedente.
- Resta solo un disallineamento documentale: il planning originario di `TASK-039` descriveva `preset-only`, ma la task è stata chiusa su richiesta utente con rollback al dialog precedente.

## Handoff

- Task chiusa in **DONE** su richiesta utente esplicita.
- Perimetro finale del rollback: `DatabaseScreenDialogs.kt`, `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml`, questo file task.
- La precedente nota “review-ready” è superata dal rollback e dalla chiusura richiesta dall’utente.
- `docs/MASTER-PLAN.md` non aggiornato in questo turno per rispettare il vincolo di non toccare lo stato globale; eventuale riallineamento globale resta al planner.
