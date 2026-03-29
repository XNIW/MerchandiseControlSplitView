# MASTER-PLAN — MerchandiseControlSplitView (Android)

> Piano principale del progetto. Fonte di verità per stato globale, backlog, regole operative.
> Aggiornato dal planner (CLAUDE.md). Letto dall'esecutore (AGENTS.md) prima di ogni azione.

---

## Progetto

**Nome:** MerchandiseControlSplitView
**Piattaforma:** Android
**Stack:** Kotlin, Jetpack Compose, Material3, Room, Apache POI, ZXing, WorkManager
**Architettura:** MVVM (ViewModel + Repository + DAO)

---

## Obiettivo attuale

**Tracking globale:** **TASK-009** — **Migrazioni DB — safety e recovery** — **`DONE`** (2026-03-29: review planner APPROVED, tutti criteri ✅). File: `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md`. **TASK-021** — **Export DB** — **`DONE`** (2026-03-29). **TASK-012** **`DONE`**. **TASK-006** / **TASK-011** **`BLOCKED`**. **TASK-010** **`DONE`**.

---

## Stato globale

| Campo               | Valore                                           |
|---------------------|--------------------------------------------------|
| Task attivo          | **Nessuno** — TASK-009 chiuso; prossimo da attivare su conferma utente |
| Fase task attivo     | — |
| Milestone            | **TASK-009** **`DONE`** (2026-03-29). **TASK-021** **`DONE`**. **TASK-006** / **TASK-011** **`BLOCKED`**. **TASK-012** **`DONE`**. **TASK-010** **`DONE`**. **TASK-007** **`DONE`**. **TASK-015** **`BACKLOG`**. |
| Prossimo passo operativo | Utente sceglie il prossimo task da attivare. Candidati: **TASK-015** (backlog), **TASK-006** / **TASK-011** (blocked, richiedono smoke manuali), **TASK-014** (backlog, dipende da TASK-002 blocked). |
| Ultimo aggiornamento | 2026-03-29 — **TASK-009** → **`DONE`**; review planner APPROVED, tutti i criteri ✅, check rieseguiti indipendentemente. |

**Promemoria antiambiguità (governance):** **Nessun** task **`ACTIVE`** al momento. **TASK-009** **`DONE`** (2026-03-29). **TASK-021** **`DONE`**. **TASK-012** **`DONE`**. **TASK-006** / **TASK-011** **`BLOCKED`**. **TASK-010** **`DONE`**.

---

## Workflow — task attivo

```
PLANNING → EXECUTION → REVIEW → FIX → REVIEW → ... → conferma utente → DONE
```

Il task attivo è sempre **uno solo**. Attualmente **nessun task `ACTIVE`**. **TASK-009** è **`DONE`** (2026-03-29). **TASK-021** è **`DONE`**. **TASK-006** e **TASK-011** restano **`BLOCKED`**. **TASK-012** è **`DONE`**. **Ultimo task chiuso:** **TASK-009** (2026-03-29).

**TASK-004 — tracking:** chiuso in **`DONE`** il 2026-03-28. **TASK-005 — tracking:** chiuso in **`DONE`** il 2026-03-28 (conferma utente). **TASK-007 — tracking:** **`DONE`** (2026-03-28) — review **APPROVED**, conferma utente; round-trip JVM + fix `ExcelUtils` / export OOM. **TASK-008 — tracking:** **`DONE`** (2026-03-28) — review **APPROVED**; fix bug localizzazione EN (`untitled`/`exported_short`) + rimozione dead resources (`sheet_name_*`, `excel_header_*`) da tutti e 4 i file; tutti i check ✅.

**Baseline automatica post-Execution (TASK-004):** dopo la fase di **Execution**, se un task tocca aree già coperte dai test introdotti con **TASK-004** (`DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`, import/export, analisi import, history, flussi Excel, entry manuali, logica di sincronizzazione/stato collegata), l’esecutore deve usare automaticamente quei **test unitari / Robolectric su JVM** come baseline di regressione, aggiornandoli nello stesso task se la logica cambia. Questo step **non** sostituisce test manuali UI/UX, smoke di navigazione o verifiche manuali su integrazioni piattaforma.

**Verifica governance reale (obbligatoria pre-codice):**

1. Sezione **Backlog**: **TASK-021** → **`DONE`** (2026-03-29); **TASK-013** → **`DONE`**; **TASK-017** → **`DONE`**; **TASK-003** → **`DONE`**; **TASK-020** → **`DONE`** (2026-03-28).
2. **TASK-002** → **`BLOCKED`** (smoke manuale rimandato; nessun `DONE` formale).
3. **TASK-004** → **`DONE`** (2026-03-28 — suite test completata, verifiche eseguite, lint globale fuori scope documentato).
4. **TASK-015** → **`BACKLOG`** (UX modernization DatabaseScreen — ripresa possibile dopo **TASK-003** `DONE` o su decisione utente).
5. **TASK-014** → **`BACKLOG`** (non attivare finché dipende da **TASK-002** `BLOCKED`).
6. **TASK-005** → **`DONE`** (2026-03-28 — conferma utente; vedi file task **Chiusura**).
7. **TASK-007** → **`DONE`** (2026-03-28 — review **APPROVED**, conferma utente).
8. **TASK-008** → **`DONE`** (2026-03-28) — review APPROVED, fix applicati.
9. **TASK-010** → **`DONE`** (2026-03-29) — execution, review e fix chiusi; dettaglio in `docs/TASKS/TASK-010-history-screen-filtri-e-performance.md`.
10. **TASK-011** → **`BLOCKED`** (2026-03-29) — execution + review tecnica completate; **smoke manuali / criteri M** non eseguiti; **non** `DONE`. Dettaglio: `docs/TASKS/TASK-011-storico-prezzi-visualizzazione-e-completezza.md`.
11. **TASK-012** → **`DONE`** (2026-03-29) — review planner APPROVED, conferma utente; dettaglio: `docs/TASKS/TASK-012-ci-cd-setup-base.md`.
12. **TASK-006** → **`BLOCKED`** (2026-03-29) — execution + review tecnica OK; **smoke manuali / criteri M** non eseguiti; **non** `DONE`. Dettaglio: `docs/TASKS/TASK-006-validazione-robustezza-import-excel.md`.
13. **TASK-021** → **`DONE`** (2026-03-29) — follow-up **TASK-007**; export unificato + smoke manuale positivo; conferma utente. Dettaglio: `docs/TASKS/TASK-021-export-full-db-memoria-streaming-ux.md`.
14. **TASK-009** → **`DONE`** (2026-03-29) — review planner APPROVED; bump v6→v7, MIGRATION_6_7, test Robolectric MVC 3/3 verdi, build/lint OK. Dettaglio: `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md`.
15. Incrociare con i file task corrispondenti; se disallineato, aggiornare subito questo file e i task — **stop** su codice finché non coincidono.

**Nota TASK-002:** decomposizione `GeneratedScreen` — review **statica positiva** (build/lint documentati nel file task); stato **`BLOCKED`** per decisione utente (smoke non eseguiti). **TASK-014** dipende da **TASK-002**: non attivarlo finché **TASK-002** resta bloccato.

**Coerenza governance TASK-013 (fonte unica):** nel backlog sotto, **TASK-013** è `DONE`. **Non** deve comparire `TASK-013` come `ACTIVE`.

**Coerenza TASK-006 / TASK-009 / TASK-010 / TASK-011 / TASK-012 / TASK-021:** **TASK-009** è **`DONE`** (2026-03-29). **TASK-021** è **`DONE`** (2026-03-29). **TASK-006** è **`BLOCKED`** (smoke pendenti). **TASK-010** è **`DONE`**. **TASK-011** è **`BLOCKED`**. **TASK-012** è **`DONE`** (2026-03-29). **Verifica rapida:** backlog TASK-009 → **`DONE`**; TASK-021 → **`DONE`**; TASK-006 / TASK-011 → **`BLOCKED`**; TASK-012 → **`DONE`**. **Nessun task `ACTIVE`.**

---

## Fonti di verità

| Cosa                        | Dove                                                           |
|-----------------------------|----------------------------------------------------------------|
| Stato globale               | Questo file (`docs/MASTER-PLAN.md`)                            |
| Stato task                  | `docs/TASKS/TASK-NNN-*.md`                                     |
| Ruolo esecutore             | `AGENTS.md`                                                    |
| Ruolo planner/reviewer      | `CLAUDE.md`                                                    |
| Protocollo di esecuzione    | `docs/CODEX-EXECUTION-PROTOCOL.md`                             |
| Codice sorgente             | `app/src/main/java/com/example/merchandisecontrolsplitview/`   |
| Build config                | `app/build.gradle.kts`, `gradle/libs.versions.toml`            |
| Database schema             | Room entities in `data/`, `AppDatabase.kt`                     |
| Risorse / localizzazione    | `app/src/main/res/values*/`                                    |

---

## Regole operative

1. **Minimo cambiamento necessario** — non fare più del richiesto.
2. **Prima capire, poi pianificare, poi agire** — mai saltare fasi.
3. **No refactor non richiesti** — il codice funzionante non si tocca senza motivo.
4. **No scope creep** — rispettare il perimetro del task.
5. **No nuove dipendenze senza richiesta** — segnalare se servono, aspettare conferma.
6. **No modifiche API pubbliche senza richiesta** — stessa regola.
7. **Verificare sempre prima di dichiarare completato** — evidenze concrete.
8. **Segnalare l'incertezza, non mascherarla** — onestà > completezza apparente.
9. **Un solo task attivo per volta** — regola inviolabile.
10. **Ogni modifica deve essere tracciabile** — log nel file task.
11. **Leggere il codice esistente prima di proporre modifiche** — sempre.
12. **Preferire soluzioni semplici e dirette** — no over-engineering.
13. **Non espandere a moduli non richiesti** — resta nel perimetro.
14. **Dopo `Execution`, usare automaticamente la baseline test di TASK-004 quando il task tocca aree già coperte** — eseguire i test rilevanti, aggiornarli se la logica cambia, documentare esito e limiti nel file task.

### Baseline regressione automatica (TASK-004)

- Ambito tipico: `InventoryRepository` / `DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`, import/export, analisi import, history, flussi Excel, entry manuali, sincronizzazione/stato collegata.
- Natura della suite: **test unitari / Robolectric su JVM**; baseline di regressione logica, **non** test UI Compose/Espresso.
- Obbligo operativo: finita `Execution`, controllare se i file modificati ricadono in queste aree e, se sì, eseguire automaticamente i test rilevanti prima della chiusura o del passaggio a `REVIEW`.
- Se il comportamento desiderato cambia, aggiornare o estendere i test nello stesso task; non rimuoverli o indebolirli solo per ottenere verde.
- Reporting minimo nel task file: test eseguiti, eventuali nuovi test/aggiornamenti, limiti residui.

### Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

### Guardrail UX/UI operativi

1. Leggere prima il codice Android della schermata coinvolta; usare iOS solo come riferimento visivo/UX.
2. Il ViewModel resta la fonte di verità dello stato — non spostare business logic nei composable.
3. **Invariato (non negoziabile):** non modificare DAO, repository, modelli dati, navigation o integrazioni piattaforma salvo necessità reale del task.
4. **Piccoli miglioramenti UI/UX intenzionali** sono ammessi **anche** in task **non** puramente visivi (es. decomposizione, fix mirati, refactor tecnico), se sono: **locali**, **coerenti** con lo stile Material3 / pattern già presenti nell’app, e **giustificati** da un guadagno chiaro in chiarezza, coerenza o qualità percepita. **Non** equivalgono a «cambiare qualsiasi UI»: vietati redesign ampi, nuovi flussi non pianificati e scope creep.
5. Preferire interventi **piccoli e progressivi**; niente riscritture UI che equivalgano a un redesign di schermata fuori perimetro.
6. **Non rimuovere** feature Android funzionanti.
7. Ogni intervento UI/UX intenzionale in un task che non sia dedicato solo alla UX va **documentato** nel log di esecuzione del file task (vedi `AGENTS.md`).
8. Dettagli review: vedi `CLAUDE.md` (distinzione regressione / miglioramento accettabile / fuori scope).

### Definition of Done — task UX/UI

Checklist minima per dichiarare chiuso un task visuale (o un task con esito UI rilevante):

- [ ] Gerarchia visiva migliorata rispetto allo stato pre-task (ove applicabile al perimetro)
- [ ] Spacing e layout più leggibili (ove nel perimetro)
- [ ] Empty / loading / error states più chiari (dove applicabile)
- [ ] Primary action più evidente (dove applicabile)
- [ ] Nessuna regressione funzionale intenzionale
- [ ] Nessun cambio a logica business / Room / repository / navigation salvo richiesta esplicita del task
- [ ] **Qualità visiva:** nessun cambio **incoerente, arbitrario o fuori scope** con lo stile dell’app e con il perimetro del task; i **piccoli miglioramenti intenzionali** ammessi devono essere coerenti e tracciati nel log
- [ ] Build Gradle OK, lint senza nuovi warning

---

## Transizioni di stato valide

```
PLANNING → EXECUTION → REVIEW → FIX → REVIEW → ... → conferma utente → DONE
```

Transizioni speciali:
- `Qualsiasi → BLOCKED` — dipendenza non risolta o decisione utente necessaria
- `Qualsiasi → WONT_DO` — solo su decisione esplicita dell'utente

Vincoli:
- `PLANNING → EXECUTION`: richiede criteri di accettazione definiti + approvazione utente
- `EXECUTION → REVIEW`: richiede check obbligatori completati e, se applicabile, baseline regressione **TASK-004** eseguita e documentata
- `REVIEW → DONE`: richiede conferma esplicita dell'utente

---

## Mappa aree funzionali dell'app

Baseline ricavata dall'audit della repo (2026-03-26):

| Area                          | File principali                                    | Stato      |
|-------------------------------|----------------------------------------------------|------------|
| **File Picker / Caricamento** | `FilePickerScreen.kt`                              | Funzionante |
| **Excel parsing**             | `ExcelUtils.kt`, `ExcelViewModel.kt`               | Funzionante |
| **PreGenerate / Anteprima**   | `PreGenerateScreen.kt`                             | Funzionante |
| **Generated / Editing**       | `GeneratedScreen.kt` (~2471 righe; helper/composable già presenti nello stesso file) | Funzionante, complesso |
| **Database / CRUD**           | `DatabaseScreen.kt`, `DatabaseViewModel.kt`        | Funzionante |
| **Repository / Room**         | `InventoryRepository.kt`, `AppDatabase.kt`         | Funzionante |
| **Entities / Schema**         | `Product.kt`, `Supplier.kt`, `Category.kt`, `HistoryEntry.kt`, `ProductPrice.kt` | v6, stabile |
| **Import Analysis**           | `ImportAnalysisScreen.kt`, `ImportAnalysis.kt`     | Funzionante |
| **History / Storico import**  | `HistoryScreen.kt`, `HistoryEntryDao.kt`           | Funzionante |
| **Price History / Prezzi**    | `ProductPrice.kt`, `ProductPriceDao.kt`, `ProductPriceSummary.kt` | Funzionante |
| **Price Backfill**            | `PriceBackfillWorker.kt`                           | Funzionante |
| **Barcode Scanner**           | `PortraitCaptureActivity.kt`                       | Funzionante |
| **Export Excel**              | `ExcelViewModel.kt`, `ErrorExporter.kt`            | Funzionante |
| **Options / Tema / Lingua**   | `OptionsScreen.kt`, `LocaleUtils.kt`               | Funzionante |
| **Navigazione**               | `NavGraph.kt`, `Screen.kt`                         | Funzionante |
| **Tema Material3**            | `MerchandiseControlTheme.kt`, `Color.kt`, `Type.kt` | Funzionante |
| **Share Intent**              | `MainActivity.kt` (ShareBus)                       | Funzionante |
| **Griglia / Componenti UI**   | `ZoomableExcelGrid.kt`, `TableCell.kt`             | Funzionante |
| **Migrazioni DB**             | `AppDatabase.kt` (v1→v6)                           | Stabile     |

### Metadati audit

| Campo               | Valore                                                              |
|---------------------|---------------------------------------------------------------------|
| Data audit          | 2026-03-26                                                          |
| Branch              | main                                                                |
| Commit SHA          | 5fe06af147ba7dfe89949126d3369f8003a52172                            |
| Scope contato       | `app/src/main/java/com/example/merchandisecontrolsplitview/` (43 file Kotlin) |
| Esclusioni          | `app/build/`, `build/`, `generated/`, test template di default (`app/src/test/`, `app/src/androidTest/`) |

### Osservazioni architetturali

- **GeneratedScreen.kt** (~2471 righe) è il file più complesso del progetto; contiene già alcuni composable/helper nello stesso file (es. chips bar, calcolatrice, manual entry). TASK-002 ne estende la decomposizione senza assumere monolite totale.
- **DatabaseScreen** — decomposizione (**TASK-003** `DONE`, 2026-03-27): logica UI ripartita su `DatabaseScreen.kt` + `DatabaseScreenComponents.kt` / `DatabaseScreenDialogs.kt` / `EditProductDialog.kt`; orchestrazione e wiring restano coerenti con `DatabaseViewModel`.
- L'architettura MVVM è coerente: 2 ViewModel, 1 Repository, 5 DAO, 5+1 Entity/View.
- Schema database a v6 con migrazioni incrementali.
- Localizzazione in 4 lingue (en, it, es, zh).
- La repo contiene solo i test template di default (ExampleUnitTest / ExampleInstrumentedTest), ma non ha copertura di test significativa sul codice di progetto.
- CI base configurata (**TASK-012** `DONE`): `.github/workflows/ci.yml` con `assembleDebug`/`lint`/`test` su GitHub Actions.
- Prima del bootstrap (2026-03-26) non esistevano governance o documentazione di progetto.

---

## Backlog

### Convenzioni
- **Stato:** `ACTIVE` | `BACKLOG` | `DONE` | `BLOCKED` | `WONT_DO`
- **Priorità:** `CRITICA` | `ALTA` | `MEDIA` | `BASSA`
- **Area:** area funzionale principale coinvolta
- **Un solo task ACTIVE alla volta**

---

### TASK-001 — Bootstrap governance e baseline audit
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `CRITICA`                                               |
| Area        | Governance / Progetto                                   |
| Dipendenze  | Nessuna                                                 |
| Descrizione | Creare la struttura completa di governance, planning e tracking per il progetto Android. Audit della repo e definizione del backlog iniziale. |

### TASK-002 — Decomposizione GeneratedScreen
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BLOCKED`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | UI / GeneratedScreen                                    |
| Dipendenze  | TASK-001 (DONE), TASK-013 (DONE)                        |
| Descrizione | Decomposizione **tecnica** di `GeneratedScreen.kt` (estratti UI, API freeze, `NavGraph` read-only in esecuzione). **Esecuzione tecnica e review statica completate** (build/lint OK nel file task). **Chiusura `DONE` non effettuata:** smoke manuale rimandato dall’utente → task **`BLOCKED`** in attesa di verifica manuale futura o nuova decisione. **Non** include il crash OOM full import DB → **TASK-017**. |
| Note tracking | Ripresa: eseguire smoke checklist in `TASK-002`, poi `REVIEW` → conferma utente → `DONE`. |

### TASK-003 — Decomposizione DatabaseScreen
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `MEDIA`                                                 |
| Area        | UI / DatabaseScreen                                     |
| Dipendenze  | TASK-001 (DONE)                                         |
| Descrizione | Ridurre la complessità di `DatabaseScreen.kt` estraendo dialoghi, sezioni e logica UI in composable dedicati. Nessun cambio funzionale inteso. **Chiusura 2026-03-27** dopo build/lint/review statica positivi e **conferma utente** (test manuale). Dettaglio: `docs/TASKS/TASK-003-decomposizione-databasescreen.md`. |
| Note tracking | **`DONE`** 2026-03-27.                                                 |

### TASK-004 — Copertura test unitari — Repository e ViewModel
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `ALTA`                                                  |
| Area        | Test / Qualità                                          |
| Dipendenze  | TASK-001 (DONE); TASK-003 (`DONE`); **TASK-020** (`DONE`, 2026-03-28) |
| Descrizione | Creare test unitari per `DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`. Copertura minima delle operazioni CRUD, import analysis, export. **Nota:** test mirati al path **full import** / OOM non sostituiscono **TASK-017** (fix runtime già in **DONE**). Dettaglio: `docs/TASKS/TASK-004-copertura-test-unitari-repository-e-viewmodel.md`. |
| Note tracking | **`DONE`** 2026-03-28. Suite completata con 34 test verdi su repository + ViewModel; `assembleDebug` verde; `lint` eseguito ma ancora rosso per issue preesistenti fuori scope documentate nel file task. |

### TASK-020 — Cleanup code analysis post-TASK-003
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `ALTA`                                                  |
| Area        | Qualità / Analisi statica / UI (solo cleanup tecnico)   |
| Dipendenze  | TASK-003 (`DONE`)                                       |
| Descrizione | Eliminare errori e triage warning di code analysis emersi dopo la decomposizione `DatabaseScreen` (**TASK-003**): `DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, `EditProductDialog.kt`. Perimetro stretto: nessun redesign, nessun cambio business logic / DAO / repository / `NavGraph`. Dettaglio: `docs/TASKS/TASK-020-cleanup-code-analysis-post-task003.md`. |
| Note tracking | **`DONE`** 2026-03-28. Chiusura su **decisione utente** con **rischio residuo noto:** smoke manuali **non eseguiti** nel contesto documentato (vedi file task **Chiusura** / **Execution**). Successore naturale completato: **TASK-004** `DONE`. |

### TASK-005 — Copertura test unitari — ExcelUtils e ImportAnalyzer
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `ALTA`                                                  |
| Area        | Test / Qualità                                          |
| Dipendenze  | TASK-001 (DONE), TASK-004 (DONE)                        |
| Descrizione | Test JVM: `parseNumber`/`formatNumber*`, `getLocalizedHeader`, **`analyzePoiSheet`** (core + POI row norm; best effort colonne/summary/no-header). **Esclusi:** `readAndAnalyzeExcel`, HTML. **`ImportAnalyzer`:** duplicati — last row wins, qty aggregata, **`DuplicateWarning.rowNumbers`**; validazione fallita post-merge → **`RowImportError.rowNumber` = ultima occorrenza** (streaming: ultimo `rowNumbers`). Dettaglio: `docs/TASKS/TASK-005-copertura-test-unitari-excelutils-e-importanalyzer.md`. Minimi ≥18 test/file. |
| Note tracking | **`DONE`** 2026-03-28 — conferma utente; review **APPROVED**; **TASK-007** poi **`DONE`** (2026-03-28); successore **TASK-008** **`DONE`** (2026-03-28). |

### TASK-006 — Validazione e robustezza import Excel
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BLOCKED`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | Import / Excel                                          |
| Dipendenze  | TASK-005 (`DONE`)                                       |
| Descrizione | Revisione della gestione errori in `ExcelUtils.kt` e `ImportAnalysis.kt`: file corrotti, formati inattesi, colonne mancanti. Migliorare messaggi di errore per l'utente. **Nota:** crash OOM import completo DB da XLSX → **TASK-017** (questo task resta generico). |
| File task   | `docs/TASKS/TASK-006-validazione-robustezza-import-excel.md` |
| Note tracking | **`BLOCKED`** 2026-03-29 — execution + review tecnica completate; build/lint/test JVM OK; **smoke manuali / criteri M** non eseguiti → **non** `DONE`. Sblocco: smoke poi **REVIEW** / conferma utente. **Nessun** difetto tecnico aperto come causa del blocco. |

### TASK-007 — Export database completo — verifica round-trip
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `MEDIA`                                                 |
| Area        | Export / Database                                       |
| Dipendenze  | TASK-005 (DONE), TASK-017 (DONE)                        |
| Descrizione | **Round-trip** export full DB → import su DB isolato (Products, Suppliers, Categories, PriceHistory; matrix **§6bis**, normalizzazione **§3bis**, doppio canale storico **§5**). **Robustezza export** su dataset realistico (**§6ter**, criteri **#4–#5**, failure mode **FM‑*** **§1bis**). **Fuori scope:** redesign UI (follow-up in Planning §10). Dipende da import stabile **TASK-017**. Dettaglio: `docs/TASKS/TASK-007-export-database-round-trip.md`. |
| Note tracking | **`DONE`** 2026-03-28 — review **APPROVED**, **conferma utente**; fix `ExcelUtils` (alias header old\*) + `DatabaseViewModel.exportFullDbToExcel` (OOM/`CancellationException`); suite `FullDbExportImportRoundTripTest`. Criterio **#5** ⚠️ NON ESEGUIBILE (smoke SAF/device) con motivazione accettata. Successore **TASK-008** **`DONE`** (2026-03-28). **Follow-up export device/grandi dataset:** **TASK-021** **`DONE`** (2026-03-29). |

### TASK-008 — Gestione errori e UX feedback
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `BASSA`                                                 |
| Area        | UX / Error handling                                     |
| Dipendenze  | TASK-001 (DONE)                                         |
| Descrizione | Audit **tutti** i feedback user-visible (Snackbar, dialog, Toast, inline, progress, share/Intent, export, feedback perso/duplicato-replay, raw eccezione §1bis); **matrice di audit** obbligatoria in Execution; priorità; regole intervento §6; utility user-visible; confine log/interni; smoke mirata. Android fonte primaria; iOS solo tono/gerarchia. Vincoli: no redesign, no refactor architetturale, no DAO/repository/navigation salvo emergenza; non assorbire **TASK-006** / **TASK-019**. Dettaglio: `docs/TASKS/TASK-008-gestione-errori-e-ux-feedback.md`. |
| Note tracking | **`DONE`** 2026-03-28 — Execution Codex + review; fix EN `untitled`/`exported_short` + rimozione dead resources; review finale pulizia `NavGraph`/`HistoryScreen`; test `DatabaseViewModelTest`/`ExcelViewModelTest`, `assembleDebug`, `lint` ✅. Successore operativo completato: **TASK-010** **`DONE`** (2026-03-29). |

### TASK-009 — Migrazione database — safety e recovery
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `ACTIVE` *(fase **`EXECUTION`** nel file task; un solo ACTIVE)* |
| Priorità    | `ALTA`                                                  |
| Area        | Database / Room                                         |
| Dipendenze  | TASK-004 (`DONE`)                                       |
| File task   | `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md` |
| Descrizione | Verificare che le migrazioni Room (v1→v6) siano sicure per tutti i percorsi di upgrade. Valutare se servono fallback, backup pre-migrazione, o test di migrazione automatizzati. |
| Note tracking | **`ACTIVE`** / **`EXECUTION`** dal 2026-03-29 — review planning vs repo + transizione fase; **C1–C6** prima del primo commit codice; `room-testing` solo con approvazione **C6**; log in file task § **Execution**. |

### TASK-010 — History screen — filtri e performance
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `BASSA`                                                 |
| Area        | History                                                 |
| Dipendenze  | TASK-001 (`DONE`)                                       |
| Descrizione | History: filtri data; custom range (dialog + reset; **M7**); performance gate (lite + **consumatori `historyEntries`** / integrità `updateHistoryEntry`, **M13**). Micro-UX: filtro attivo, empty M8/M9. Schema/indici: **non** automatici; eccezione o **TASK-009**. **File task:** `docs/TASKS/TASK-010-history-screen-filtri-e-performance.md`. |
| Note tracking | **`DONE`** 2026-03-29 — execution completata, review approvata con fix mirati; lite list sicura con fetch completo per `uid`, custom range robusto, micro-UX locali coerenti, baseline TASK-004 + `assembleDebug` + `lint` verdi. Nessun task successivo attivato automaticamente. |

### TASK-011 — Storico prezzi — visualizzazione e completezza
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BLOCKED`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | Price History / DatabaseScreen (UI)                     |
| Dipendenze  | TASK-001 (`DONE`)                                       |
| Descrizione | Verificare completezza visualizzazione storico prezzi in DatabaseScreen; rifinitura bottom sheet (source + empty + data). **File task:** `docs/TASKS/TASK-011-storico-prezzi-visualizzazione-e-completezza.md`. |
| Note tracking | **`BLOCKED`** 2026-03-29 — execution + review tecnica completate; **smoke manuali / validazione M (M1–M15)** non eseguiti; task **sospeso**, **non** `DONE`. Sblocco: smoke poi **REVIEW/DONE** come da file task. |

### TASK-012 — CI/CD — setup base
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `BASSA`                                                 |
| Area        | Infrastruttura                                          |
| Dipendenze  | TASK-004 (`DONE`), TASK-005 (`DONE`)                    |
| Descrizione | Pipeline CI base (**GitHub Actions**): `assembleDebug`, `lint`, `test` JVM. **File task:** `docs/TASKS/TASK-012-ci-cd-setup-base.md`. |
| Note tracking | **`DONE`** 2026-03-29. Workflow `.github/workflows/ci.yml` con job singolo `Build` su `ubuntu-24.04`, Temurin 17, pin SHA, artifact diagnostici. Review planner APPROVED. |

### TASK-013 — UX polish FilePicker + PreGenerate
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `MEDIA`                                                 |
| Area        | UX / UI                                                 |
| Dipendenze  | TASK-001 (DONE)                                         |
| Descrizione | Hero full-width “Carica Excel” + secondarie 2×2 non-lazy (ordine fisso); PreGenerate: `LoadingDialog`, error “Scegli di nuovo”, FAB gerarchici, inset preview + system/nav, polish dialog supplier/category. **Perimetro:** nessuna modifica a ViewModel, DAO, repository, entity, `NavGraph` / navigation, `ZoomableExcelGrid.kt`; wiring/MIME/launcher e semantica reload invariati. Dettaglio: file task. |
| File Android | `FilePickerScreen.kt`, `PreGenerateScreen.kt`, `app/src/main/res/values*/strings.xml` |
| Rif. iOS    | Solo riferimento visivo/UX (se presenti); non porting 1:1 |
| Obiettivo UX | Gerarchia Material3, stati loading/error coerenti, primary action evidente, nessuna regressione funzionale |
| Note tracking | Esecuzione, review e fix completati nel file task; chiusura documentale validata dall’utente nel turno di riallineamento del 2026-03-27 prima del passaggio a `TASK-002`. Verifiche statiche concluse; restano note manuali nel handoff del task. |

### TASK-014 — UX modernization GeneratedScreen
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | UX / UI                                                 |
| Dipendenze  | TASK-001, TASK-002 (**BLOCKED** — modernization solo dopo `DONE` o sblocco esplicito di TASK-002) |
| Descrizione | Modernizzare toolbar, dialog/sheet, row affordance e leggibilità della griglia in GeneratedScreen. Nessun cambio alla logica business (editing, export, completamento righe) né rimozione di feature esistenti. |
| File Android | `GeneratedScreen.kt`, `ZoomableExcelGrid.kt`, `TableCell.kt`, `ExcelViewModel.kt` (sola lettura) |
| Rif. iOS    | Schermata Generated iOS come guida visiva (se presente) |
| Obiettivo UX | Toolbar compatta, dialog/sheet idiomatici, leggibilità righe/colonne griglia |

### TASK-015 — UX modernization DatabaseScreen
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | UX / UI                                                 |
| Dipendenze  | TASK-001 (DONE), TASK-017 (DONE); **TASK-003** (`DONE`, decomposizione DatabaseScreen) — ripresa UX consigliata ma non vincolo rigido |
| Descrizione | Modernizzare layout CRUD, dialog import/export, toolbar e scanner UI in DatabaseScreen. Nessun cambio alla logica business (CRUD, import, export, scanner barcode) né rimozione di feature esistenti. Feedback utente: import diretto senza mini-menu ridondante (già parzialmente emerso in TASK-017), coerenza icone import/export, export con menu dove ha senso, maggiore chiarezza senza rifare l’architettura. Dettaglio: `docs/TASKS/TASK-015-ux-modernization-databasescreen.md`. |
| File Android | `DatabaseScreen.kt`, `DatabaseViewModel.kt` (sola lettura), `InventoryRepository.kt` (sola lettura) |
| Rif. iOS    | Schermata Database iOS come guida visiva (se presente) |
| Obiettivo UX | Layout CRUD leggibile, dialog import/export chiari, toolbar e scanner con affordance |
| Note tracking | **Sospeso** finché non riattivato: **TASK-003** ora **`DONE`**. Riattivazione: impostare **`ACTIVE`** in questo backlog e nel file task (un solo ACTIVE alla volta). |

### TASK-016 — UX polish History / ImportAnalysis / grid readability
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | UX / UI                                                 |
| Dipendenze  | TASK-001                                                |
| Descrizione | Migliorare leggibilità e gerarchia visiva di HistoryScreen, ImportAnalysisScreen e delle griglie dati (ZoomableExcelGrid/TableCell). Spacing, empty/loading/error states, affordance. Nessun cambio alla logica business né rimozione di feature esistenti. |
| File Android | `HistoryScreen.kt`, `ImportAnalysisScreen.kt`, `ZoomableExcelGrid.kt`, `TableCell.kt` |
| Rif. iOS    | Schermate History / ImportAnalysis iOS come guida visiva (se presenti) |
| Obiettivo UX | Leggibilità tabelle/griglie, empty/loading/error states chiari, spacing coerente |

### TASK-017 — Crash full DB import (OOM)
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `CRITICA`                                               |
| Area        | Import / Database / Stability                           |
| Dipendenze  | TASK-001 (DONE)                                         |
| Descrizione | Fix crash **OOM** durante **import completo** database da XLSX (`DatabaseViewModel.startFullDbImport`, `XSSFWorkbook`), preservando Suppliers / Categories / Products / PriceHistory. Chiusura **2026-03-27**: verifiche statiche OK, review/fix completati, **test manuali utente con esito positivo**. Dettaglio: `docs/TASKS/TASK-017-crash-full-db-import-oom.md`. |
| File Android | `DatabaseViewModel.kt`, `DatabaseScreen.kt`, `FullDbImportStreaming.kt`, `ExcelUtils.kt`, ecc. (vedi file task) |
| Note tracking | Follow-up backlog: **TASK-018** (double staging file), **TASK-019** (localizzazione errori PriceHistory). |

### TASK-018 — Eliminare double file staging nel full-import
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | Import / Performance                                    |
| Dipendenze  | TASK-017                                                |
| Descrizione | `detectImportWorkbookRoute` copia il file XLSX in cache per ispezionare i nomi foglio, poi `analyzeFullDbImportStreaming` lo copia di nuovo. Per file molto grandi è IO doppio non necessario. Ottimizzare passando il file staged dalla detection all'analisi, oppure unificando i due step. Emerso dalla review di TASK-017. |

### TASK-019 — Localizzare messaggi errore PriceHistory
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | Import / Localizzazione                                 |
| Dipendenze  | TASK-017                                                |
| Descrizione | I messaggi di errore del foglio PriceHistory nel full-import streaming sono hardcoded in inglese ("PriceHistory sheet is empty or missing the header row.", "PriceHistory sheet missing required headers: ..."). Spostarli in `strings.xml` con traduzioni it/es/zh coerenti col pattern localizzato esistente. Emerso dalla review di TASK-017. |

### TASK-021 — Export DB: memoria/streaming, fogli selettivi, dialog M3 (follow-up TASK-007)
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `DONE`                                                  |
| Priorità    | `ALTA`                                                  |
| Area        | Export / Database / Stabilità / UX locale DatabaseScreen |
| Dipendenze  | TASK-007 (`DONE`), TASK-004 (`DONE`)                    |
| Descrizione | **(1)** Stabilità: **SXSSF** / **chunked** + **cleanup** (`dispose`, temp in `finally`). **(2)** **Fetch:** repository/DAO **solo** per fogli selezionati (niente precarico inutile). **(3)** **Fogli vuoti:** export con header-only + **success** se ≥1 foglio selezionato; **no** regressione `error_no_products` se Products non selezionato. **(4)** Dialog M3: multi-selezione + **preset** + **copy** full/parziale. **(5)** Filename `Database_*.xlsx` / `Database_partial_*`. **(6)** Guard export + writer **OutputStream** JVM. **(7)** Round-trip solo **4 fogli**. **Non** TASK-015. File: `docs/TASKS/TASK-021-export-full-db-memoria-streaming-ux.md`. |
| Note tracking | **`DONE`** 2026-03-29 — review/conferma utente; build/lint/baseline JVM + **smoke manuale export positivo** (criterio **#14**). Successivo **`ACTIVE`:** **TASK-009** (2026-03-29). |

---

## Razionale priorità

### Priorità prodotto (focus corrente)

**Focus immediato:** **TASK-009** **`ACTIVE`** / **`EXECUTION`** (2026-03-29) — migrazioni DB safety/recovery (implementazione in corso). **TASK-021** **`DONE`** (2026-03-29). **TASK-006** è **`BLOCKED`**. **TASK-012** **`DONE`**. **TASK-011** **`BLOCKED`**. **TASK-010** **`DONE`**. **TASK-008** / **TASK-007** / **TASK-005** / **TASK-004** / **TASK-020** / **TASK-003** `DONE`. **TASK-015** **`BACKLOG`**. **TASK-017** `DONE`. **TASK-002** **`BLOCKED`**. **TASK-014** dipende da TASK-002. Ordine suggerito:

1. **TASK-009** — `EXECUTION` → test migrazione MVC / fix schema / evidenze #1–#12 / recovery documentato → `REVIEW` → conferma utente → `DONE`.
2. **TASK-006** — smoke manuali su device/emulator → poi **REVIEW** / conferma utente → `DONE` *(non `ACTIVE` finché TASK-009 è `ACTIVE`)*.
3. **Parallelo (senza altro `ACTIVE`):** quando possibile, eseguire **smoke TASK-011** per portare **TASK-011** da **`BLOCKED`** verso chiusura **`DONE`** (vedi file task).
4. **Task dedicato lint cleanup (nuovo solo su decisione planner/utente):** affrontare i 25 errori / 68 warning preesistenti fuori scope emersi da `./gradlew lint`.
5. **TASK-015 (MEDIA, BACKLOG):** UX modernization DatabaseScreen — dopo **TASK-003** `DONE` o su richiesta utente.
6. **TASK-002 (MEDIA, BLOCKED):** ripresa quando l’utente eseguirà smoke / deciderà chiusura formale.
7. **TASK-014 (MEDIA):** UX modernization GeneratedScreen — dopo `DONE` o sblocco esplicito **TASK-002**.
8. **TASK-016 (BASSA):** UX polish History/ImportAnalysis/grid — coordinare con **TASK-010** per non duplicare lavoro sulla History.
9. **TASK-018 / TASK-019 (BASSA / dip. TASK-017 DONE):** ottimizzazioni e i18n emerse da TASK-017 — su richiesta.
10. **TASK-011 (`BLOCKED`):** completare smoke manuali e validazione M per sblocco verso **DONE** (vedi file task).

### Priorità tecnica / qualità

Task di qualità che riducono il rischio tecnico, attivabili su richiesta utente:

1. **TASK-001 (CRITICA):** Bootstrap governance — DONE (chiuso 2026-03-27).
2. **TASK-004, TASK-005 (ALTA):** Test unitari — **TASK-004** **`DONE`** (2026-03-28); **TASK-005** **`DONE`** (2026-03-28); copertura utility/import analysis completata, con fix lint autorizzato applicato.
3. **TASK-009 (ALTA):** Migrazioni database — **`ACTIVE`** / **`EXECUTION`** (2026-03-29); toccano dati utente, rischio alto — vedere file task per log e criteri.
4. **TASK-003 (MEDIA, DONE):** Decomposizione `DatabaseScreen` — chiuso 2026-03-27. **TASK-002 (MEDIA, BLOCKED):** Decomposizione `GeneratedScreen`.
5. **TASK-017 (CRITICA):** OOM full import DB — **`DONE`** (2026-03-27).
6. **TASK-006 (MEDIA, BLOCKED):** Robustezza import Excel — dip. TASK-005 `DONE`; **BLOCKED** (2026-03-29) per smoke manuali; implementazione e test JVM OK.
7. **TASK-007 (MEDIA):** Round-trip export full DB — **`DONE`** (2026-03-28); follow-up runtime grandi dataset → **TASK-021** **`DONE`** (2026-03-29).
8. **TASK-008 (BASSA):** Gestione errori / UX feedback — **`DONE`** (2026-03-28). **TASK-010 (BASSA):** **`DONE`** (2026-03-29) — History filtri e performance. **TASK-011 (BASSA):** **`BLOCKED`** (2026-03-29) — storico prezzi; smoke manuali pendenti.
9. **TASK-012 (BASSA):** CI/CD — **`DONE`** (2026-03-29).
10. **TASK-021 (ALTA):** Export DB — **`DONE`** (2026-03-29) — streaming/selettivo, dialog M3, smoke manuale positivo.

---

## Rischi e complessità strutturali

| Rischio                                    | Impatto | Probabilità | Mitigazione                          |
|--------------------------------------------|---------|-------------|--------------------------------------|
| GeneratedScreen troppo complesso (~2471 LOC, decomposizione parziale nello stesso file) | Medio   | Già presente | TASK-002 **BLOCKED** (smoke pendenti); lavoro statico completato |
| OOM su import DB completo (XLSX / POI) | Alto | Mitigato | **TASK-017** **DONE**; monitorare hotspot RAM residui (analyzer / `getAllProducts`) su file enormi |
| OOM / fallimenti tardivi su **export** DB (`XSSFWorkbook` + liste intere + `groupBy` PriceHistory) | Alto | Mitigato (implementazione **TASK-021** `DONE`) | **TASK-021** **`DONE`** (2026-03-29): SXSSF/chunked, cleanup, **fetch condizionale**, dialog selettivo, round-trip 4 fogli; monitorare regressioni su dataset estremi |
| Copertura test ancora parziale sulle utility/import analysis | Medio | Mitigato (perimetro TASK-005) | **TASK-004** `DONE`; **TASK-005** `DONE` (ExcelUtils/ImportAnalyzer) |
| Migrazioni DB non testate automaticamente   | Alto    | Possibile   | **TASK-009** `ACTIVE`/`EXECUTION` (2026-03-29) — mitigazione in corso nel file task |
| Nessuna CI/CD                              | Mitigato | Risolto   | **TASK-012** `DONE` (2026-03-29) — pipeline CI base operativa (`assembleDebug`/`lint`/`test`); follow-up: branch protection |
| File grandi con molte responsabilità        | Medio   | Mitigato su DB screen | **TASK-003** `DONE` (DatabaseScreen modularizzato); **TASK-002** **BLOCKED** (`GeneratedScreen`) |
