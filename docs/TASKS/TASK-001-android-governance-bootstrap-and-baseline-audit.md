# TASK-001 — Bootstrap governance Android e baseline audit

---

## Informazioni generali

| Campo                | Valore                                              |
|----------------------|-----------------------------------------------------|
| ID                   | TASK-001                                            |
| Stato                | DONE                                                |
| Priorità             | CRITICA                                             |
| Area                 | Governance / Progetto                               |
| Creato               | 2026-03-26                                          |
| Ultimo aggiornamento | 2026-03-27                                          |

---

## Dipendenze

Nessuna. Questo è il primo task del progetto.

---

## Scopo

Creare da zero la struttura completa di governance, planning, tracking, handoff, review ed execution per il progetto Android MerchandiseControlSplitView. Eseguire un audit della repo per ricavare una baseline delle aree funzionali e definire un backlog iniziale concreto e prioritizzato.

---

## Contesto

Il progetto Android non aveva alcuna documentazione di governance, workflow o tracking. Il progetto iOS gemello utilizza un sistema disciplinato di planning/execution/review che deve essere replicato — adattato al contesto Android (Kotlin, Jetpack Compose, Room, Gradle) — per garantire la stessa qualità di processo.

La repo contiene un'app funzionante di gestione inventario con: import/export Excel, database Room, storico prezzi, scanner barcode, history degli import, 4 lingue, tema Material3. Il codice è funzionante ma privo di copertura test significativa (presenti solo test template di default), CI/CD, e documentazione di progetto.

---

## Non incluso

- Modifiche al codice applicativo dell'app
- Aggiunta di dipendenze
- Refactor di codice esistente
- Setup CI/CD (sarà TASK-012 se richiesto)
- Scrittura di test (saranno TASK-004 e TASK-005)
- Qualsiasi modifica funzionale all'app

---

## File potenzialmente coinvolti

File **da creare**:
- `AGENTS.md` — ruolo esecutore
- `CLAUDE.md` — ruolo planner/reviewer
- `docs/MASTER-PLAN.md` — piano principale e backlog
- `docs/CODEX-EXECUTION-PROTOCOL.md` — protocollo esecuzione
- `docs/TASKS/_TEMPLATE.md` — template task riusabile
- `docs/TASKS/TASK-001-android-governance-bootstrap-and-baseline-audit.md` — questo file
- `.claude/settings.json` — configurazione minimale (se utile)

File **da leggere** (per audit):
- Tutti i file Kotlin in `app/src/main/java/com/example/merchandisecontrolsplitview/`
- `app/build.gradle.kts`, `gradle/libs.versions.toml`
- `app/src/main/res/values*/strings.xml`
- `app/src/main/AndroidManifest.xml`

---

## Criteri di accettazione

| #  | Criterio                                                                        | Tipo | Stato |
|----|---------------------------------------------------------------------------------|------|-------|
| 1  | `AGENTS.md` esiste con ruolo esecutore, lettura obbligatoria, check, regole Android | S | ✅ |
| 2  | `CLAUDE.md` esiste con ruolo planner, transizioni, gestione backlog, review      | S    | ✅    |
| 3  | `docs/MASTER-PLAN.md` esiste con stato globale, mappa aree, backlog concreto     | S    | ✅    |
| 4  | `docs/CODEX-EXECUTION-PROTOCOL.md` esiste con tipologie verifica, regole Android | S    | ✅    |
| 5  | `docs/TASKS/_TEMPLATE.md` esiste, allineato al workflow reale                    | S    | ✅    |
| 6  | `docs/TASKS/TASK-001-*.md` esiste, compilato completamente                       | S    | ✅    |
| 7  | MASTER-PLAN e TASK-001 sono coerenti (stato, ID, descrizione)                    | S    | ✅    |
| 8  | AGENTS.md e CLAUDE.md hanno ruoli distinti e non sovrapposti                     | S    | ✅    |
| 9  | Il backlog è ricavato dall'audit reale della repo, non inventato                 | S    | ✅    |
| 10 | Nessun riferimento tecnico-operativo a iOS/Xcode/SwiftUI nelle istruzioni di governance; riferimenti contestuali storici/provenienza ammessi nel contesto del task | S | ✅ |
| 11 | Tutti i file usano terminologia Android (Kotlin, Compose, Room, Gradle)          | S    | ✅    |
| 12 | Nessun TODO vuoto, nessuna sezione "da completare"                               | S    | ✅    |
| 13 | Il sistema è pronto per partire con il prossimo task dopo conferma utente e transizione di TASK-001 a DONE | S | ✅ |

---

## Decisioni

| # | Decisione                                                | Motivazione                                       | Data       |
|---|----------------------------------------------------------|---------------------------------------------------|------------|
| 1 | Creare `.claude/settings.json` minimale                  | Supporta il workflow senza configurazioni invasive | 2026-03-26 |
| 2 | Non creare `.claude/commands/` con comandi custom        | Non aggiunge valore rispetto al costo di manutenzione in questa fase | 2026-03-26 |
| 3 | Backlog basato su audit reale, non su feature ipotetiche | Coerenza con la filosofia "prima capire"           | 2026-03-26 |
| 4 | Priorità ALTA ai test perché la repo ha solo test template di default | L'assenza di copertura test significativa è il rischio più concreto | 2026-03-26 |
| 5 | GeneratedScreen e DatabaseScreen come task MEDIA          | Funzionanti ma complessi, decomposizione non urgente | 2026-03-26 |

---

## Metadati audit

| Campo               | Valore                                                              |
|---------------------|---------------------------------------------------------------------|
| Data audit          | 2026-03-26                                                          |
| Branch              | main                                                                |
| Commit SHA          | 5fe06af147ba7dfe89949126d3369f8003a52172                            |
| Scope contato       | `app/src/main/java/com/example/merchandisecontrolsplitview/` (43 file Kotlin) |
| Esclusioni          | `app/build/`, `build/`, `generated/`, test template di default (`app/src/test/`, `app/src/androidTest/`) |

---

## Planning (Claude)

### Analisi — Audit della repo Android

**Struttura del progetto:**
- Single-module Android app con architettura MVVM
- 43 file Kotlin sorgente
- Package principale: `com.example.merchandisecontrolsplitview`
- Sub-packages: `data/`, `viewmodel/`, `ui/screens/`, `ui/components/`, `ui/navigation/`, `ui/theme/`, `util/`

**Database Room (v6):**
- 5 entity: Product, Supplier, Category, HistoryEntry, ProductPrice
- 1 database view: ProductPriceSummary
- 5 DAO: ProductDao, SupplierDao, CategoryDao, HistoryEntryDao, ProductPriceDao
- 6 migrazioni (v1→v6), inclusa ricostruzione tabella products in v5→v6
- Type converters per serializzazione JSON (Gson) di campi complessi in HistoryEntry

**Schermate UI (7 screen Composable):**
1. FilePickerScreen — menu principale con griglia 2 colonne
2. PreGenerateScreen — anteprima Excel con filtro colonne e supplier/category
3. GeneratedScreen (2471 LOC) — editing griglia completo, export, salvataggio
4. DatabaseScreen (1084 LOC) — CRUD prodotti, import/export, scanner barcode
5. HistoryScreen (448 LOC) — storico import con filtri data
6. ImportAnalysisScreen (465 LOC) — preview import con warning duplicati e errori
7. OptionsScreen (197 LOC) — tema e lingua

**Componenti riusabili:**
- ZoomableExcelGrid — griglia scrollabile con zoom per dati Excel
- TableCell — cella singola con stili condizionali

**ViewModel (2):**
- DatabaseViewModel — CRUD, import analysis, export, price history
- ExcelViewModel — parsing Excel, gestione griglia, history, filtri data

**Repository:**
- InventoryRepository (interfaccia) + DefaultInventoryRepository (implementazione)
- Pattern repository completo su tutti e 5 i DAO

**Utility:**
- ExcelUtils.kt — parsing Excel (POI), HTML table (jsoup), numeri europei/US
- ImportAnalysis.kt — analisi streaming, dedup, validazione, auto-creation supplier/category
- LocaleUtils.kt — gestione locale per 4 lingue (en, it, es, zh)
- ErrorExporter.kt — export errori import in XLSX

**Background:**
- PriceBackfillWorker — WorkManager per backfill prezzi storici all'avvio

**Dipendenze principali:**
- Jetpack Compose + Material3 + Navigation Compose
- Room + Paging3
- Apache POI (Excel)
- ZXing (barcode)
- Gson, jsoup, WorkManager

### Mappa sintetica delle aree principali

```
┌─────────────────────────────────────────────────────────────┐
│                    MerchandiseControlSplitView               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────┐    ┌──────────────┐    ┌───────────────────┐  │
│  │FilePicker │───▶│ PreGenerate  │───▶│  GeneratedScreen  │  │
│  │  Screen   │    │   Screen     │    │  (editing/export) │  │
│  └────┬─────┘    └──────────────┘    └───────────────────┘  │
│       │                                                      │
│       ├──▶ HistoryScreen (storico import, filtri data)       │
│       │                                                      │
│       ├──▶ DatabaseScreen (CRUD, scanner, import/export)     │
│       │       └──▶ ImportAnalysisScreen (preview import)     │
│       │                                                      │
│       └──▶ OptionsScreen (tema, lingua)                      │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│  Data Layer:                                                 │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ ViewModels │─▶│  Repository  │─▶│  Room DB (5 entity)  │ │
│  │ (2)        │  │ (interface)  │  │  5 DAO, 1 view       │ │
│  └────────────┘  └──────────────┘  │  6 migrazioni        │ │
│                                     └──────────────────────┘ │
│  Utilities:                                                  │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ ExcelUtils │  │ImportAnalysis│  │  PriceBackfillWorker │ │
│  │ + jsoup    │  │ (streaming)  │  │  (WorkManager)       │ │
│  └────────────┘  └──────────────┘  └──────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Proposta backlog iniziale

Il backlog è stato definito partendo dalle osservazioni dell'audit:

1. **TASK-001 (CRITICA):** Questo task — bootstrap governance. Prerequisito per tutto.
2. **TASK-004, TASK-005 (ALTA):** Test unitari — la repo ha solo test template di default, nessuna copertura significativa. Rischio concreto e immediato.
3. **TASK-009 (ALTA):** Migrazioni DB — toccano dati utente, 6 versioni senza test automatici.
4. **TASK-002, TASK-003 (MEDIA):** Decomposizione file grandi — funzionanti ma difficili da mantenere.
5. **TASK-006, TASK-007 (MEDIA):** Robustezza import/export — l'app funziona ma edge case non testati.
6. **TASK-008, TASK-010, TASK-011 (BASSA):** Miglioramenti incrementali UX, filtri, visualizzazione.
7. **TASK-012 (BASSA):** CI/CD — utile ma non bloccante.

### Rischi identificati

| Rischio                                       | Impatto | Mitigazione                        |
|-----------------------------------------------|---------|-------------------------------------|
| Nessuna copertura test significativa (solo template default) | Alto | TASK-004, TASK-005 priorità ALTA |
| GeneratedScreen 2471 LOC                      | Medio   | TASK-002, non urgente              |
| Migrazioni DB non testate                     | Alto    | TASK-009 priorità ALTA            |
| No CI/CD                                      | Medio   | TASK-012, da fare dopo i test     |
| Dipendenza da Apache POI (libreria pesante)   | Basso   | Funziona, nessuna azione necessaria |

---

## Execution

### Esecuzione — 2026-03-26

**File creati:**
- `AGENTS.md` — ruolo esecutore/fixer con check obbligatori e regole Android
- `CLAUDE.md` — ruolo planner/reviewer con transizioni, backlog, review
- `docs/MASTER-PLAN.md` — piano principale con mappa aree, backlog iniziale (12 task, successivamente esteso), rischi
- `docs/CODEX-EXECUTION-PROTOCOL.md` — protocollo con tipologie BUILD/STATIC/MANUAL/EMULATOR
- `docs/TASKS/_TEMPLATE.md` — template riusabile allineato al workflow
- `docs/TASKS/TASK-001-android-governance-bootstrap-and-baseline-audit.md` — questo file
- `.claude/settings.json` — configurazione minimale

**Azioni eseguite:**
1. Audit completo della repo: letti tutti i 43 file Kotlin in `app/src/main/java/`, build config, risorse
2. Mappate tutte le aree funzionali (19 aree nella mappa MASTER-PLAN)
3. Identificati rischi architetturali (file grandi, no test, no CI)
4. Creato backlog iniziale di 12 task concreti basati sull'audit (successivamente esteso a 16 task nel MASTER-PLAN)
5. Definite priorità con razionale
6. Creati tutti i file di governance con ruoli distinti
7. Verificata coerenza incrociata tra tutti i file

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza                                             |
|--------------------------|------|-------|------------------------------------------------------|
| Build Gradle             | B    | N/A   | Non applicabile: task solo documentazione/governance |
| Lint                     | S    | N/A   | Non applicabile: task solo documentazione/governance |
| Warning Kotlin           | S    | N/A   | Non applicabile: nessun codice Kotlin modificato     |
| Coerenza con planning    | —    | ✅    | Tutti i deliverable pianificati sono stati creati    |
| Criteri di accettazione  | —    | ✅    | Tutti i 13 criteri verificati (vedi tabella sopra)   |

**Incertezze:**
- Nessuna incertezza residua. Tutti i deliverable sono stati creati e verificati.

---

## Review

### Review — 2026-03-26

**Revisore:** Claude (planner)

**Criteri di accettazione:**

| #  | Criterio                                                    | Stato | Note                                          |
|----|-------------------------------------------------------------|-------|-----------------------------------------------|
| 1  | AGENTS.md con ruolo esecutore e check Android               | ✅    | Completo, include check Gradle/lint/warning   |
| 2  | CLAUDE.md con ruolo planner e transizioni                   | ✅    | Completo, transizioni e gestione backlog      |
| 3  | MASTER-PLAN con stato globale e backlog concreto            | ✅    | 19 aree mappate, backlog iniziale 12 task (stato corrente: 16 task) |
| 4  | CODEX-EXECUTION-PROTOCOL con tipologie verifica             | ✅    | BUILD/STATIC/MANUAL/EMULATOR definiti         |
| 5  | Template task allineato al workflow                          | ✅    | Tutte le sezioni del workflow coperte         |
| 6  | TASK-001 compilato completamente                             | ✅    | Tutte le sezioni popolate                     |
| 7  | Coerenza MASTER-PLAN ↔ TASK-001                             | ✅    | Stato, ID, descrizione allineati              |
| 8  | Ruoli distinti AGENTS.md ↔ CLAUDE.md                        | ✅    | Esecutore vs planner, non sovrapposti         |
| 9  | Backlog ricavato dall'audit reale                            | ✅    | Basato su 43 file Kotlin analizzati in app/src/main/java/ |
| 10 | Nessun riferimento tecnico-operativo iOS/Xcode/SwiftUI nella governance | ✅ | Verificato: nessun riferimento operativo; riferimenti contestuali storici/provenienza ammessi nel task |
| 11 | Terminologia Android coerente                                | ✅    | Kotlin, Compose, Room, Gradle ovunque         |
| 12 | Nessun TODO vuoto o sezione incompleta                       | ✅    | Tutti i file sono completi                    |
| 13 | Sistema pronto per il prossimo task (dopo conferma utente)   | ✅    | Workflow operativo; attivazione prossimo task richiede conferma utente |

**Problemi trovati:**
- Nessuno.

**Verdetto:** APPROVED

---

## Fix

Nessun fix necessario. Review approvata al primo passaggio.

---

## Chiusura

| Campo                  | Valore                                                       |
|------------------------|--------------------------------------------------------------|
| Stato finale           | DONE                                                         |
| Data chiusura          | 2026-03-27                                                   |
| Tutti i criteri ✅?    | Sì (13/13), review APPROVED, conferma utente ricevuta        |
| Rischi residui         | Nessuno per questo task                                      |

---

## Riepilogo finale

Creata la struttura completa di governance per il progetto Android MerchandiseControlSplitView:

- **7 file creati** con ruoli distinti, protocolli operativi, e tracking coerente
- **Audit completo** della repo: 43 file Kotlin (scope: `app/src/main/java/`), 19 aree funzionali mappate
- **Backlog iniziale di 12 task** concreti e prioritizzati, basati sull'audit reale (stato corrente del MASTER-PLAN: 16 task)
- **Sistema pronto all'uso** — dopo conferma utente e transizione a DONE, il prossimo task può essere attivato senza modifiche alla governance

Rischi strutturali identificati nell'audit:
- Nessuna copertura di test significativa sul codice di progetto — solo test template di default (priorità ALTA nel backlog)
- File UI molto grandi (GeneratedScreen 2471 LOC, DatabaseScreen 1084 LOC)
- Migrazioni DB (v1→v6) senza test automatici
- Nessuna CI/CD

Nessun rischio residuo per questo task specifico.

---

## Handoff

**Per il prossimo operatore:**

1. **TASK-001 è DONE.** Conferma utente ricevuta 2026-03-27.
2. **Prossimo task attivato:** TASK-013 (UX polish FilePicker + PreGenerate).
3. **Focus corrente del progetto:** UX/UI modernization Android. Il prossimo task suggerito è **TASK-013** (UX polish FilePicker + PreGenerate) o **TASK-014** (UX modernization GeneratedScreen), in quanto allineati al focus prodotto. I task di qualità tecnica (TASK-004, TASK-005, TASK-009) restano ad alta priorità e possono essere attivati su richiesta utente.
4. **Per creare un nuovo task:** usa `docs/TASKS/_TEMPLATE.md`, aggiungi al backlog in `MASTER-PLAN.md`.
5. **Leggi sempre AGENTS.md (se esecutore) o CLAUDE.md (se planner) prima di operare.**

**Guardrail per esecuzioni future:**
- Le future esecuzioni UX/UI devono applicare i guardrail operativi e la DoD visuale definiti in `docs/MASTER-PLAN.md` (sezioni "Guardrail UX/UI operativi" e "Definition of Done — task UX/UI"), oltre alle regole dettagliate in `AGENTS.md` e `CLAUDE.md`.

**Aree che richiederanno decisione dell'utente:**
- Conferma chiusura TASK-001 e scelta del prossimo task da attivare
- Ordine di esecuzione tra task UX (TASK-013–016) e task tecnici (TASK-004, TASK-005, TASK-009)
- Se e quando attivare CI/CD (TASK-012)
- Se la decomposizione dei file grandi (TASK-002, TASK-003) è desiderata
- Se servono test su emulator/device per task futuri

> **Nota:** Le sezioni Execution e Review di questo file riportano lo snapshot del bootstrap iniziale (2026-03-26, 12 task).
> Il backlog corrente nel MASTER-PLAN è stato successivamente esteso (16 task). Per lo stato aggiornato, fare sempre riferimento a `docs/MASTER-PLAN.md`.
