# TASK-017 — Crash full DB import (OOM)

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-017                   |
| Stato                | PLANNING                   |
| Priorità             | CRITICA                    |
| Area                 | Import / Database / Stability |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-27                 |

---

## Dipendenze

- TASK-001 (DONE)

### Task correlati (il fix **non** vive lì)

| Task | Rapporto |
|------|-----------|
| **TASK-006** | Correlato (validazione/robustezza import Excel) ma **troppo generico** per questo crash specifico (OOM su import completo DB da XLSX). |
| **TASK-007** | Correlato come **follow-up** per verifica round-trip export/import database completo, dopo che l’import non crasha più. |
| **TASK-004** | Correlato come **follow-up** per copertura test su `DatabaseViewModel` / import; non sostituisce il bugfix runtime. |

**Regola:** il crash OOM sul full import resta **tracciato e risolto in TASK-017**, non “assorbito” da TASK-006/007/004.

---

## Scopo

Eliminare il **crash (OutOfMemoryError / OOM)** che si verifica durante l’**import completo del database** da file **XLSX** nella **DatabaseScreen**, lungo il percorso `DatabaseViewModel.startFullDbImport` → caricamento workbook **Apache POI** (`XSSFWorkbook`), preservando il **comportamento funzionale** dell’import sui quattro fogli supportati: **Suppliers**, **Categories**, **Products**, **PriceHistory** (nomi foglio case-insensitive come oggi).

---

## Contesto

In `DatabaseViewModel.kt`, `startFullDbImport` apre lo stream e costruisce `XSSFWorkbook(inStream)`, quindi analizza i fogli con `analyzePoiSheet` e pipeline di import/analysis. Per file XLSX molto grandi, il modello DOM di XSSF può esaurire la heap dell’app e causare chiusura improvvisa — segnalazione utente su import completo; **log crash non presente in repo** al momento della creazione del task (incollare evidenza stack/OOM nel log `Execution` alla prima analisi).

Obiettivo tecnico: ridurre il picco di memoria o gestire il fallimento in modo **controllato** (messaggio/stato UI, nessuna chiusura silenziosa), con **minimo cambiamento** rispetto al contratto funzionale attuale.

---

## Non incluso

- Redesign UX/UI della DatabaseScreen o dei dialog di import
- Refactor architetturale ampio (nuovi layer, riscrittura repository) salvo necessità strettamente motivata dal bugfix
- Modifiche non necessarie a navigation, DAO, entity, schema Room
- Estensione del formato file oltre al percorso XLSX/full-import attuale
- Chiusura o sblocco di **TASK-002** (GeneratedScreen): perimetro separato

---

## File potenzialmente coinvolti

**Primari (attesi):**

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — `startFullDbImport`, uso `XSSFWorkbook`, orchestrazione IO/UI state
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — wiring launcher/import completo, feedback utente se serve allineamento messaggi

**Solo se necessario al fix:**

- Utility Excel/POI esistenti (es. funzioni condivise con `analyzePoiSheet` / lettura sheet) — **nessuna** dipendenza nuova senza approvazione esplicita nel task

**Da leggere:**

- `InventoryRepository.kt`, `ImportAnalysis.kt` / analyzer — solo se il fix tocca il flusso di analysis post-lettura

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Con il **file XLSX di test** segnalato dall’utente (o equivalente dimensionato), l’app **non va in crash** aprendo/avviando l’import completo | M / E | — |
| 2 | L’import produce **analisi completa** oppure un **fallimento gestito** (stato errore / messaggio / assenza di kill process) senza chiusura improvvisa | M / E | — |
| 3 | Nessuna regressione funzionale sui **4 fogli** supportati (Suppliers, Categories, Products, PriceHistory) su file di dimensione **moderata** baseline | M | — |
| 4 | `./gradlew assembleDebug` OK | B | — |
| 5 | `./gradlew lint` senza nuovi warning introdotti dal fix | S | — |
| 6 | Log di history / tracciamento import (`FULL_IMPORT` / `currentImportLogUid` o equivalente) **coerenti**: successo, errore gestito o abort documentato senza stato fantasma | S / M | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task dedicato TASK-017, separato da TASK-002 | TASK-002 = decomposizione UI GeneratedScreen; crash DB import = altro perimetro e priorità stabilità | 2026-03-27 |
| 2 | Priorità CRITICA | Crash in produzione su percorso dati utente | 2026-03-27 |

---

## Planning (Claude)

### Analisi iniziale

Punto caldo: `XSSFWorkbook(inStream)` carica l’intero workbook in memoria. Mitigazioni tipiche (da valutare in EXECUTION): streaming SAX (`XSSFReader` / event API), riduzione allocazioni, chunking lato lettura, oppure UX di pre-check dimensione file + messaggio — sempre nel rispetto del vincolo “minimo cambiamento”.

### Piano di esecuzione (bozza)

1. Riprodurre o analizzare heap/stack (evidenza nel file task).
2. Confermare dove avviene l’OOM (workbook vs sheet vs `analyzePoiSheet`).
3. Implementare fix a basso rischio funzionale; verificare criteri 1–6.
4. Documentare rischio residuo e file di test in Handoff.

---

## Execution

_(Aggiornabile dall’esecutore.)_

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

- **Attivo in backlog:** sì (unico `ACTIVE` dopo riallineamento MASTER-PLAN).
- Allegare in Execution: stack trace / logcat OOM e path file di test quando disponibili.
