# TASK-016 — UX polish History / ImportAnalysis / grid readability

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-016                   |
| Stato              | PLANNING                   |
| Priorità           | BASSA                      |
| Area               | UX / UI                    |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03               |
| Tracking `MASTER-PLAN` | **`BACKLOG`**          |

---

## Dipendenze

- TASK-001 (DONE)
- TASK-010 (DONE) — per non duplicare lavoro su HistoryScreen

---

## Scopo

Migliorare leggibilità e chiarezza percepita di `HistoryScreen`, `ImportAnalysisScreen` e delle griglie dati (`ZoomableExcelGrid` / `TableCell`) senza toccare la business logic. Il task nasce dall’audit repo-grounded del 2026-04-03: lo storico usa ancora timestamp raw, la preview import full-db può risultare poco chiara su supplier/category, e le griglie hanno margini di affordance/readability.

---

## Contesto

- In `HistoryScreen.kt` la lista principale mostra ancora `entry.timestamp` raw.
- In `ImportAnalysisScreen.kt` i nomi supplier/category vengono risolti con fetch per-riga e non gestiscono bene relazioni pending / temp ID.
- In `CompareRow` i campi supplier/category sugli aggiornamenti oggi mostrano ID grezzi.
- `ZoomableExcelGrid.kt` / `TableCell.kt` sono funzionali ma ancora dense e con affordance migliorabili su dataset ampi.

---

## Non incluso

- Correzioni di correttezza import o transazionalità: vedi TASK-026.
- Refactor di repository / DAO / ViewModel salvo wiring minimo motivato.
- Redesign ampio di schermate o nuove route/navigation.
- Ottimizzazioni performance grandi dataset come obiettivo primario.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — localizzazione timestamp e micro-gerarchia lista
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — chiarezza preview e label supplier/category
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/ZoomableExcelGrid.kt` — micro-affordance griglia
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/TableCell.kt` — leggibilità / affordance cella
- `app/src/main/res/values*/strings.xml` — copy minima se necessaria

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `HistoryScreen` non mostra più il timestamp storage raw nella lista principale quando il parse è valido; fallback raw integro se il parse fallisce | B + S | — |
| 2 | `ImportAnalysisScreen` mostra supplier/category in modo comprensibile anche nei casi di relazioni pending o non ancora persistite | B + M | — |
| 3 | Le comparazioni update non espongono più raw ID come UX primaria per supplier/category salvo fallback documentato | B + M | — |
| 4 | Nessuna regressione funzionale su conferma/cancel/edit import analysis o navigazione history | B + M | — |
| 5 | `./gradlew assembleDebug` OK e `./gradlew lint` senza nuovi warning introdotti | B + S | — |

Legenda: B=Build, S=Static, M=Manual

> Per task UX/UI: includere anche la checklist "Definition of Done — task UX/UI" definita in `docs/MASTER-PLAN.md`.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task tenuto locale a tre aree precise (History / ImportAnalysis / grid) | Evitare scope creep e sovrapposizione con TASK-015 | 2026-04-03 |
| 2 | Correttezza import esclusa da questo task | I problemi critici stanno in TASK-026 | 2026-04-03 |

---

## Planning (Claude)

### Analisi

L’audit 2026-04-03 mostra problemi reali ma non bloccanti di polish/coerenza UX in tre aree già note del backlog, senza richiedere refactor architetturali.

### Piano di esecuzione

1. Rifinire la lista `HistoryScreen` con timestamp localizzato e gerarchia invariata.
2. Rendere più chiari supplier/category in `ImportAnalysisScreen`, specialmente sugli aggiornamenti e sulle relazioni pending.
3. Applicare piccoli miglioramenti di leggibilità/affordance a `ZoomableExcelGrid` / `TableCell` senza alterare la logica della griglia.

### Rischi identificati

- Toccare accidentalmente la logica import invece della sola presentazione.
- Introdurre stringhe o fallback incoerenti tra lingue.

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

- Task formalizzato il 2026-04-03 per allineare governance e backlog dopo audit completo repo-grounded.
- Coordinare l’eventuale esecuzione con TASK-015 e TASK-026 per evitare mix tra polish UI e fix di correttezza import.
