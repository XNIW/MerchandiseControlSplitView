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

Portare **TASK-015** — **UX modernization `DatabaseScreen`**: toolbar, dialog import/export, affordance scanner, chiarezza visiva **senza** cambiare logica business né rimuovere feature. **TASK-017** (OOM full-import) è **`DONE`**. **TASK-002** resta **`BLOCKED`**. **TASK-014** resta in backlog finché **TASK-002** non è sbloccato o `DONE`.

---

## Stato globale

| Campo               | Valore                                           |
|---------------------|--------------------------------------------------|
| Task attivo          | **TASK-015** (UX modernization DatabaseScreen)   |
| Fase task attivo     | **PLANNING** (file task: `docs/TASKS/TASK-015-ux-modernization-databasescreen.md`) |
| Milestone            | **TASK-017** **`DONE`** (2026-03-27, conferma utente smoke). **TASK-002** ancora **`BLOCKED`** (smoke `GeneratedScreen` rimandati) |
| Prossimo passo operativo | Completare planning **TASK-015** → approvazione utente → **EXECUTION** (solo UI `DatabaseScreen` e composable collegati; ViewModel/repository in sola lettura salvo necessità esplicita) |
| Ultimo aggiornamento | 2026-03-27                                       |

---

## Workflow — task attivo

```
PLANNING → EXECUTION → REVIEW → FIX → REVIEW → ... → conferma utente → DONE
```

Il task attivo è sempre **uno solo**. Il suo stato è nel file task corrispondente (oggi: `docs/TASKS/TASK-015-ux-modernization-databasescreen.md`).

**TASK-015 — tracking:** unico task **`ACTIVE`** nel backlog (fase corrente **`PLANNING`** nel file task).

**Verifica governance reale (obbligatoria pre-codice):**

1. Sezione **Backlog**: **TASK-013** → **`DONE`**; **TASK-017** → **`DONE`**.
2. **TASK-002** → **`BLOCKED`** (smoke manuale rimandato; nessun `DONE` formale).
3. **TASK-015** → **`ACTIVE`** (unico attivo).
4. **TASK-014** → **`BACKLOG`** (non attivare finché dipende da **TASK-002** `BLOCKED`).
5. Nessun altro task con stato **`ACTIVE`** oltre **TASK-015**.
6. Incrociare con i file task corrispondenti; se disallineato, aggiornare subito questo file e i task — **stop** su codice finché non coincidono.

**Nota TASK-002:** decomposizione `GeneratedScreen` — review **statica positiva** (build/lint documentati nel file task); stato **`BLOCKED`** per decisione utente (smoke non eseguiti). **TASK-014** dipende da **TASK-002**: non attivarlo finché **TASK-002** resta bloccato.

**Coerenza governance TASK-013 (fonte unica):** nel backlog sotto, **TASK-013** è `DONE`. **Non** deve comparire `TASK-013` come `ACTIVE`.

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

### Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

### Guardrail UX/UI operativi

1. Leggere prima il codice Android della schermata coinvolta; usare iOS solo come riferimento visivo/UX.
2. Il ViewModel resta la fonte di verità dello stato — non spostare business logic nei composable.
3. Non modificare DAO, repository, modelli dati, navigation o integrazioni piattaforma salvo necessità reale del task.
4. Cambi piccoli e progressivi — no riscritture ampie se il task è soprattutto visuale.
5. Non rimuovere feature Android funzionanti.
6. Dettagli: vedi guardrail completi in `AGENTS.md` e `CLAUDE.md`.

### Definition of Done — task UX/UI

Checklist minima per dichiarare chiuso un task visuale:

- [ ] Gerarchia visiva migliorata rispetto allo stato pre-task
- [ ] Spacing e layout più leggibili
- [ ] Empty / loading / error states più chiari (dove applicabile)
- [ ] Primary action più evidente
- [ ] Nessuna regressione funzionale intenzionale
- [ ] Nessun cambio a logica business / Room / repository / navigation salvo richiesta esplicita del task
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
- `EXECUTION → REVIEW`: richiede check obbligatori completati
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
- **DatabaseScreen.kt** (1084 righe) è il secondo file più grande. Gestisce molte responsabilità (CRUD, import, export, scanner).
- L'architettura MVVM è coerente: 2 ViewModel, 1 Repository, 5 DAO, 5+1 Entity/View.
- Schema database a v6 con migrazioni incrementali.
- Localizzazione in 4 lingue (en, it, es, zh).
- La repo contiene solo i test template di default (ExampleUnitTest / ExampleInstrumentedTest), ma non ha copertura di test significativa sul codice di progetto.
- Nessuna CI/CD configurata.
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
| Stato       | `BACKLOG`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | UI / DatabaseScreen                                     |
| Dipendenze  | TASK-001                                                |
| Descrizione | Ridurre la complessità di `DatabaseScreen.kt` (1084 righe) estraendo dialoghi, sezioni e logica in composable dedicati. Nessun cambio funzionale. |

### TASK-004 — Copertura test unitari — Repository e ViewModel
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `ALTA`                                                  |
| Area        | Test / Qualità                                          |
| Dipendenze  | TASK-001                                                |
| Descrizione | Creare test unitari per `DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`. Copertura minima delle operazioni CRUD, import analysis, export. **Nota:** test mirati al path **full import** / OOM non sostituiscono **TASK-017** (fix runtime prima). |

### TASK-005 — Copertura test unitari — ExcelUtils e ImportAnalyzer
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `ALTA`                                                  |
| Area        | Test / Qualità                                          |
| Dipendenze  | TASK-001                                                |
| Descrizione | Creare test unitari per `ExcelUtils.kt` (parsing, numero, HTML) e `ImportAnalysis.kt` (analisi, duplicati, errori). Queste utility sono testabili senza dipendenze Android. |

### TASK-006 — Validazione e robustezza import Excel
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | Import / Excel                                          |
| Dipendenze  | TASK-005                                                |
| Descrizione | Revisione della gestione errori in `ExcelUtils.kt` e `ImportAnalysis.kt`: file corrotti, formati inattesi, colonne mancanti. Migliorare messaggi di errore per l'utente. **Nota:** crash OOM import completo DB da XLSX → **TASK-017** (questo task resta generico). |

### TASK-007 — Export database completo — verifica round-trip
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `MEDIA`                                                 |
| Area        | Export / Database                                       |
| Dipendenze  | TASK-005                                                |
| Descrizione | Verificare che export full DB (4 fogli) + re-import produca dati identici. Definire test di round-trip per Products, Suppliers, Categories, PriceHistory. **Follow-up naturale** dopo che l’import completo è **stabile** (**TASK-017**). |

### TASK-008 — Gestione errori e UX feedback
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | UX / Error handling                                     |
| Dipendenze  | TASK-001                                                |
| Descrizione | Audit della gestione errori utente-visibili: toast, dialog, messaggi. Verificare che ogni errore sia comprensibile e localizzato. |

### TASK-009 — Migrazione database — safety e recovery
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `ALTA`                                                  |
| Area        | Database / Room                                         |
| Dipendenze  | TASK-004                                                |
| Descrizione | Verificare che le migrazioni Room (v1→v6) siano sicure per tutti i percorsi di upgrade. Valutare se servono fallback, backup pre-migrazione, o test di migrazione automatizzati. |

### TASK-010 — History screen — filtri e performance
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | History                                                 |
| Dipendenze  | TASK-001                                                |
| Descrizione | Valutare performance della HistoryScreen con molte entry. Verificare filtri data (custom range). Valutare paginazione se necessario. |

### TASK-011 — Storico prezzi — visualizzazione e completezza
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | Price History                                           |
| Dipendenze  | TASK-001                                                |
| Descrizione | Verificare completezza della visualizzazione storico prezzi nella DatabaseScreen. Valutare se serve un grafico o una vista dedicata. |

### TASK-012 — CI/CD — setup base
| Campo       | Valore                                                  |
|-------------|---------------------------------------------------------|
| Stato       | `BACKLOG`                                               |
| Priorità    | `BASSA`                                                 |
| Area        | Infrastruttura                                          |
| Dipendenze  | TASK-004, TASK-005                                      |
| Descrizione | Configurare una pipeline CI base (GitHub Actions): build, lint, test unitari. Solo se l'utente lo richiede. |

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
| Stato       | `ACTIVE`                                                |
| Priorità    | `MEDIA`                                                 |
| Area        | UX / UI                                                 |
| Dipendenze  | TASK-001 (DONE), TASK-017 (DONE)                        |
| Descrizione | Modernizzare layout CRUD, dialog import/export, toolbar e scanner UI in DatabaseScreen. Nessun cambio alla logica business (CRUD, import, export, scanner barcode) né rimozione di feature esistenti. Feedback utente: import diretto senza mini-menu ridondante (già parzialmente emerso in TASK-017), coerenza icone import/export, export con menu dove ha senso, maggiore chiarezza senza rifare l’architettura. Dettaglio: `docs/TASKS/TASK-015-ux-modernization-databasescreen.md`. |
| File Android | `DatabaseScreen.kt`, `DatabaseViewModel.kt` (sola lettura), `InventoryRepository.kt` (sola lettura) |
| Rif. iOS    | Schermata Database iOS come guida visiva (se presente) |
| Obiettivo UX | Layout CRUD leggibile, dialog import/export chiari, toolbar e scanner con affordance |
| Note tracking | **Unico task ACTIVE**; fase **`PLANNING`** nel file task fino ad approvazione utente per EXECUTION. |

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

---

## Razionale priorità

### Priorità prodotto (focus corrente)

**Focus immediato: TASK-015 (MEDIA, ACTIVE, PLANNING)** — UX modernization `DatabaseScreen`. **TASK-017** è **`DONE`**. **TASK-002** è **`BLOCKED`**. **TASK-014** non attivabile finché **TASK-002** resta bloccato. Ordine suggerito:

1. **TASK-015 (MEDIA, ACTIVE):** UX modernization DatabaseScreen — planning → esecuzione dopo approvazione utente.
2. **TASK-002 (MEDIA, BLOCKED):** Ripresa quando l’utente eseguirà smoke / deciderà chiusura formale.
3. **TASK-014 (MEDIA):** UX modernization GeneratedScreen — dopo `DONE` o sblocco esplicito **TASK-002**.
4. **TASK-016 (BASSA):** UX polish History/ImportAnalysis/grid.
5. **TASK-018 / TASK-019 (BASSA / dip. TASK-017 DONE):** ottimizzazioni e i18n emerse da TASK-017 — su richiesta.

### Priorità tecnica / qualità

Task di qualità che riducono il rischio tecnico, attivabili su richiesta utente:

1. **TASK-001 (CRITICA):** Bootstrap governance — DONE (chiuso 2026-03-27).
2. **TASK-004, TASK-005 (ALTA):** Test unitari — la repo non ha copertura significativa.
3. **TASK-009 (ALTA):** Migrazioni database — toccano dati utente, rischio alto.
4. **TASK-002 (MEDIA, BLOCKED), TASK-003 (MEDIA):** Decomposizione file grandi.
5. **TASK-017 (CRITICA):** OOM full import DB — **`DONE`** (2026-03-27).
6. **TASK-006, TASK-007 (MEDIA):** Robustezza import/export / round-trip — follow-up naturali post-TASK-017.
7. **TASK-008, TASK-010, TASK-011 (BASSA):** Miglioramenti incrementali.
8. **TASK-012 (BASSA):** CI/CD — desiderabile ma non bloccante.

---

## Rischi e complessità strutturali

| Rischio                                    | Impatto | Probabilità | Mitigazione                          |
|--------------------------------------------|---------|-------------|--------------------------------------|
| GeneratedScreen troppo complesso (~2471 LOC, decomposizione parziale nello stesso file) | Medio   | Già presente | TASK-002 **BLOCKED** (smoke pendenti); lavoro statico completato |
| OOM su import DB completo (XLSX / POI) | Alto | Mitigato | **TASK-017** **DONE**; monitorare hotspot RAM residui (analyzer / `getAllProducts`) su file enormi |
| Nessuna copertura di test significativa (solo template default) | Alto | Certo | TASK-004 e TASK-005 priorità ALTA |
| Migrazioni DB non testate automaticamente   | Alto    | Possibile   | TASK-009 nel backlog                |
| Nessuna CI/CD                              | Medio   | Certo       | TASK-012, bassa priorità per ora    |
| File grandi con molte responsabilità        | Medio   | Già presente | TASK-002 e TASK-003                |
