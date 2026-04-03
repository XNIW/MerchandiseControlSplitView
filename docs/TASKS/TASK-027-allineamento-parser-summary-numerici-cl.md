# TASK-027 — Allineare summary/totali ai parser numerici CL condivisi

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-027                   |
| Stato              | PLANNING                   |
| Priorità           | ALTA                       |
| Area               | Numeri / History / ExcelViewModel |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03               |
| Tracking `MASTER-PLAN` | **`BACKLOG`**          |

---

## Dipendenze

- TASK-023 (DONE)
- TASK-004 (DONE)

---

## Scopo

Allineare il calcolo dei totali (`orderTotal`, `paymentTotal`, quantità e missing) alla policy numerica cilena già centralizzata con TASK-023. Il task esiste perché `ExcelViewModel` usa ancora parse locale semplificato nei summary, con rischio di risultati silenziosamente errati su input come `1.234`.

---

## Contesto

- `calculateInitialSummary` e `calculateFinalSummary` in `ExcelViewModel.kt` fanno parse con `replace(",", ".").toDoubleOrNull()`.
- La repo dispone già di parser centralizzati in `ClNumberFormatters.kt`.
- Il bug è localizzato, ma impatta dati user-visible in history e flussi generated/manual entry.

---

## Non incluso

- Refactor generale di tutta la pipeline numerica.
- Cambi alle regole di formatting display già definite in TASK-023.
- Nuove feature export/import.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — summary calculations
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ClNumberFormatters.kt` — sola lettura salvo helper minimo strettamente necessario
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ClNumberFormattersTest.kt` — solo se serve copertura aggiuntiva helper

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `calculateInitialSummary` usa parser coerenti con la policy CL condivisa | B + S | — |
| 2 | `calculateFinalSummary` usa parser coerenti con la policy CL condivisa | B + S | — |
| 3 | Input tipo `1.234`, `1,5`, `1.234,5` vengono trattati coerentemente con la convenzione definita da TASK-023 nei test mirati | B + S | — |
| 4 | Nessuna regressione sui casi già coperti di summary/history/manual entry | B + S | — |
| 5 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM rilevanti verdi | B + S | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task stretto e localizzato | Evitare di riaprire inutilmente l’intero perimetro TASK-023 | 2026-04-03 |
| 2 | Parser condivisi come unica fonte di verità | Evitare nuova divergenza futura | 2026-04-03 |

---

## Planning (Claude)

### Analisi

L’audit mostra che la visualizzazione numerica è stata corretta in molti punti, ma il calcolo dei summary usa ancora un parser ad hoc più fragile.

### Piano di esecuzione

1. Sostituire il parse locale dei summary con i parser condivisi coerenti con la policy CL.
2. Aggiungere test mirati sui casi ambigui che oggi possono produrre totali errati.
3. Verificare che history/manual/generated non cambino comportamento fuori dai casi corretti.

### Rischi identificati

- Cambiare il parse può modificare risultati di test esistenti: distinguere fix intenzionale da regressione.

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

- Nuovo follow-up da audit 2026-04-03.
- Alto rapporto impatto/costo: bug user-visible ma confinato.
