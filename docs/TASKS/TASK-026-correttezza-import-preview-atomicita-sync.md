# TASK-026 — Correttezza import: preview side-effect-free, apply atomico, sync coerente

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-026                   |
| Stato              | PLANNING                   |
| Priorità           | CRITICA                    |
| Area               | Import / Database / Data integrity |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03               |
| Tracking `MASTER-PLAN` | **`BACKLOG`**          |

---

## Dipendenze

- TASK-004 (DONE)
- TASK-005 (DONE)
- TASK-018 (DONE)
- TASK-025 (DONE)

---

## Scopo

Chiudere i gap di correttezza residui nel percorso import emersi dall’audit repo-grounded del 2026-04-03. La preview non deve mutare il DB prima della conferma, l’apply import deve essere atomico, e lo stato sync/history deve riflettere l’esito reale dell’import invece di uno stato anticipato o ambiguo.

---

## Contesto

- `ImportAnalyzer.analyzeStreaming(...)` crea supplier/category già in fase di analisi single-sheet.
- `NavGraph.kt` aggiorna `syncStatus` subito dopo il launch dell’import, prima dell’esito reale.
- `DefaultInventoryRepository.applyImport(...)` esegue più scritture DB correlate senza una transazione Room unica.
- Il percorso full-db usa già deferred relations, quindi oggi esiste un comportamento incoerente tra full import e foglio singolo.

---

## Non incluso

- Ottimizzazioni grandi dataset come obiettivo primario: vedi TASK-028.
- Polish UI di ImportAnalysis o History: vedi TASK-016.
- Nuove feature import/export o redesign delle schermate.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — eliminare side-effect persistenti in preview
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — orchestrazione apply/sync/import state
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — atomicità apply import
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt` — supporto transaction se necessario
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — rimozione update sync anticipato
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ImportAnalyzerTest.kt`

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | L’analisi preview single-sheet non crea supplier/category persistenti se l’utente annulla prima del confirm | B + S | — |
| 2 | `applyImport` è atomico: prodotti, relazioni e storico prezzi non restano in stato parziale se una parte dell’apply fallisce | B + S | — |
| 3 | Lo stato sync/history viene aggiornato solo dopo esito reale di successo/failure dell’import | B + S | — |
| 4 | Nessuna regressione tra percorso single-sheet e full-db su mapping relazioni e apply finale | B + S | — |
| 5 | Baseline TASK-004 eseguita con test aggiornati/estesi dove necessario | B | — |
| 6 | `./gradlew assembleDebug` e test JVM rilevanti verdi; `lint` senza nuovi warning imputabili al diff | B + S | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Questo task precede i nuovi follow-up UX/performance | Qui c’è rischio reale di integrità dati e stato incoerente | 2026-04-03 |
| 2 | Preferire allineamento al modello deferred-relations già usato nel full-db | Riduce incoerenza tra percorsi import | 2026-04-03 |

---

## Planning (Claude)

### Analisi

L’audit ha evidenziato un difetto critico: la preview single-sheet non è side-effect-free, e l’apply import non è oggi garantito come transazione unica. In più la UI marca il sync status prima del completamento effettivo.

### Piano di esecuzione

1. Rimuovere persistenza di supplier/category dal percorso preview single-sheet e allinearlo al modello deferred.
2. Rendere atomico l’apply import con una transazione Room che copra prodotti, relazioni e storico prezzi.
3. Spostare l’aggiornamento sync/history sui rami di esito reale e coprire il comportamento con test.

### Rischi identificati

- Regressione sul full import se si duplica male la logica deferred.
- Test esistenti insufficienti se il fix tocca più layer insieme: aggiornare nello stesso task.

---

## Execution

_(Non avviata — in PLANNING.)_

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

- Task creato direttamente dall’audit completo 2026-04-03.
- Va trattato come priorità più alta prima di considerare conclusivo il percorso import lato robustezza/solidità.
