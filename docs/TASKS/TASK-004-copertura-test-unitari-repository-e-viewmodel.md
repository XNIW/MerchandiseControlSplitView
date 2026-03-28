# TASK-004 — Copertura test unitari — Repository e ViewModel

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-004                   |
| Stato                | PLANNING                   |
| Priorità             | ALTA                       |
| Area                 | Test / Qualità             |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-28                 |
| Tracking `MASTER-PLAN` | **`ACTIVE`** (unico task attivo dopo **`DONE`** di **TASK-020**) |

**Nota tracking:** nel `MASTER-PLAN` questo task è l’unico **`ACTIVE`**. Nel file task il campo **`Stato`** resta **`PLANNING`** finché l’executor non avvia materialmente **`EXECUTION`** (poi aggiornare a **`EXECUTION`** per `AGENTS.md`). **Nessuna execution** di **TASK-004** è stata avviata nel passaggio di riattivazione documentale (2026-03-28).

---

## Dipendenze

- **TASK-001** (`DONE`) — governance e baseline
- **TASK-003** (`DONE`, 2026-03-27) — decomposizione `DatabaseScreen`
- **TASK-020** (`DONE`, 2026-03-28) — cleanup code analysis post-TASK-003 completato; **TASK-004** riattivato come unico **`ACTIVE`** nel `MASTER-PLAN`

---

## Scopo

Introdurre **test unitari** (JUnit su JVM o configurazione equivalente approvata) per:

- **`DefaultInventoryRepository`** — operazioni repository usate dall’app (CRUD e percorsi principali esposti dalla classe in `InventoryRepository.kt`).
- **`DatabaseViewModel`** — stato `UiState`, filtri, operazioni CRUD/import/export esposte al layer UI (con **doppi/test doubles** per il repository o Room, senza spostare business logic fuori dal ViewModel).
- **`ExcelViewModel`** — flussi principali di parsing/history/export **testabili** in unità (mock di dipendenze pesanti come DB o context dove necessario).

Obiettivo: **copertura minima significativa** (non template vuoti), allineata al `MASTER-PLAN`, che riduca il rischio di regressioni su path critici già noti (CRUD, import analysis, export).

---

## Contesto

La repo ha oggi soprattutto test template di default; **TASK-004** e **TASK-005** nel `MASTER-PLAN` coprono la qualità. Questo task colpisce il **cuore dati + ViewModel** usati da `DatabaseScreen`, Excel flow e navigazione. **TASK-017** (OOM import) è **`DONE`** — i test qui **non** sostituiscono test di carico/OOM su file enormi salvo esplicito ampliamento scope.

---

## Non incluso

- Test strumentati Android UI / Compose end-to-end (salvo richiesta futura).
- Copertura completa di ogni ramo di `ExcelUtils` / `ImportAnalysis` → **TASK-005**.
- Modifiche funzionali a prodotti, schema Room, API pubbliche repository **salvo** minimo indispensabile per testabilità **documentato** e approvato.
- CI/CD → **TASK-012**.
- Esecuzione in questo turno: **nessun** avvio **EXECUTION** finché il planning non è approvato.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `DefaultInventoryRepository` (sorgente sotto test o rifattor minimo per test doubles)
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`
- `app/src/test/java/...` — nuovi file test (es. `DefaultInventoryRepositoryTest.kt`, `DatabaseViewModelTest.kt`, `ExcelViewModelTest.kt`; nomi definitivi in EXECUTION)
- `app/build.gradle.kts` — dipendenze test (JUnit5/JUnit4, MockK/Mockito, Coroutines Test, Turbine, `androidx.arch.core:core-testing`, ecc.) **solo se** mancanti e approvate
- `AppDatabase.kt`, DAO — possibile uso di **Room in-memory** o mock; decidere in planning

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Test unitari eseguibili con `./gradlew test` (o task Gradle concordato) per le tre aree (`DefaultInventoryRepository`, `DatabaseViewModel`, `ExcelViewModel`) con almeno un caso significativo per area | B | — |
| 2 | Nessuna regressione: `./gradlew assembleDebug` OK | B | — |
| 3 | `./gradlew lint` senza nuovi warning introdotti dal task | S | — |
| 4 | Documentazione in Execution: elenco test aggiunti, dipendenze test se aggiunte, limiti noti (es. parti non coperte) | S | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **TASK-004** = unico **`ACTIVE`** dopo **`DONE`** di **TASK-003** | Ordine backlog / utente | 2026-03-27 |
| 2 | Perimetro = tre classi indicate nel `MASTER-PLAN` | Coerenza backlog | 2026-03-27 |
| 3 | **TASK-004** rimesso in **`BACKLOG`**; **TASK-020** diventa unico **`ACTIVE`** | Decisione utente: cleanup analisi statica prima dei test unitari | 2026-03-27 |
| 4 | **TASK-020** → **`DONE`**; **TASK-004** → unico **`ACTIVE`** nel `MASTER-PLAN` | Sequenza post-cleanup; chiusura **TASK-020** su decisione utente (2026-03-28) | 2026-03-28 |

---

## Planning (Claude)

### Analisi

Leggere le tre classi target: dipendenze su `Application`, Room, `ContentResolver`, coroutine, `StateFlow`/`LiveData`. Decidere strategia (in-memory DB, `FakeInventoryRepository`, `runTest` + `StandardTestDispatcher`, Turbine per flow). Verificare dipendenze test già presenti in `app/build.gradle.kts`.

### Piano di esecuzione (bozza)

1. Audit dipendenze test e convenzione esistente in `app/src/test`.
2. Implementare test `DefaultInventoryRepository` (Room in-memory o mock DAO — trade-off da documentare).
3. Implementare test `DatabaseViewModel` con repository fittizio.
4. Implementare test `ExcelViewModel` isolando IO dove possibile.
5. `assembleDebug` + `test` + `lint`; log in **Execution**.

### Rischi identificati

- **Android coupling:** ViewModel con `AndroidViewModel` / `Application` → richiede Robolectric o refactor leggero per testabilità; mitigare con doppi o `@VisibleForTesting` solo se inevitabile e minimo.
- **Room:** setup in-memory e migrazioni possono essere lenti o fragili — usare versione DB di test allineata a `AppDatabase`.
- **Scope creep:** limitarsi alla copertura **minima** concordata; dettaglio parsing in **TASK-005**.

---

## Execution

_(Non avviata — in **PLANNING**; attendere approvazione utente per **EXECUTION**.)_

---

## Review

_(Dopo EXECUTION.)_

---

## Fix

_(Se necessario.)_

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | —        |
| Data chiusura          | —        |
| Tutti i criteri ✅?    | —        |
| Rischi residui         | —        |

---

## Riepilogo finale

_(Al termine.)_

---

## Handoff

- **Predecessori:** **TASK-003** (`DONE`, decomposizione `DatabaseScreen`); **TASK-020** (`DONE` 2026-03-28, cleanup code analysis post-TASK-003).
- **Stato corrente:** **TASK-004** è l’unico task **`ACTIVE`** nel `MASTER-PLAN`; avviare **`EXECUTION`** dopo lettura `MASTER-PLAN` + questo file + `AGENTS.md` + approvazione planning se ancora richiesta.
- **Successore naturale in backlog:** **TASK-005** (test `ExcelUtils` / `ImportAnalysis`); **TASK-009** dipende da **TASK-004** per migrazioni.
- **Governance:** un solo `ACTIVE`; dopo **DONE** di **TASK-004**, aggiornare `MASTER-PLAN` e attivare il prossimo task con conferma utente.
