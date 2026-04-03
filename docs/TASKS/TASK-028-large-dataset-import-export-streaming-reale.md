# TASK-028 — Large dataset: import/export realmente bounded-memory

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-028                   |
| Stato              | PLANNING                   |
| Priorità           | MEDIA                      |
| Area               | Performance / Import / Export |
| Creato             | 2026-04-03                 |
| Ultimo aggiornamento | 2026-04-03               |
| Tracking `MASTER-PLAN` | **`BACKLOG`**          |

---

## Dipendenze

- TASK-017 (DONE)
- TASK-021 (DONE)
- TASK-026 (BACKLOG, consigliata prima)

---

## Scopo

Ridurre i picchi di memoria residui su dataset grandi nei percorsi import/export. Il task nasce dall’audit 2026-04-03: il writer export è oggi streaming, ma i fetch in `DatabaseViewModel` sono ancora preload completi; il single-sheet import continua a materializzare tutte le righe e l’analisi tiene comunque strutture grandi in memoria.

---

## Contesto

- `DatabaseViewModel.exportDatabase(...)` carica intere liste prima di scrivere.
- `readAndAnalyzeExcel(...)` e il percorso single-sheet import passano ancora da strutture completamente materializzate.
- `ImportAnalyzer.analyzeStreaming(...)` evita parte del costo ma mantiene accumulatori proporzionali al dataset.

---

## Non incluso

- Correzioni di data integrity / sync status: vedi TASK-026.
- Nuove feature export/import o redesign dialog/screen.
- Ottimizzazioni premature fuori dai percorsi realmente caldi.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ExcelUtils.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/DatabaseExportWriter.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductPriceDao.kt`
- test JVM mirati su import/export dove necessario

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Il full export DB non richiede più preload completo di tutte le liste nel ViewModel quando non necessario | B + S | — |
| 2 | Il percorso import foglio singolo riduce la materializzazione completa non necessaria rispetto allo stato attuale | B + S | — |
| 3 | Nessuna regressione funzionale su output export, preview import e compatibilità workbook | B + S | — |
| 4 | I test esistenti di export/import rilevanti restano verdi; quelli necessari vengono aggiornati/estesi | B | — |
| 5 | `./gradlew assembleDebug`, `./gradlew lint` e test JVM rilevanti verdi | B + S | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task separato dalla correttezza import | La priorità immediata è la data integrity, non la memoria | 2026-04-03 |
| 2 | Ottimizzare prima i veri preload completi | Miglior rapporto valore/costo | 2026-04-03 |

---

## Planning (Claude)

### Analisi

Le mitigazioni di TASK-017 e TASK-021 hanno migliorato molto la situazione, ma l’audit mostra che i percorsi grandi dataset non sono ancora bounded-memory end-to-end.

### Piano di esecuzione

1. Ridurre preload completi nel full export DB.
2. Migliorare il percorso import foglio singolo per evitare materializzazione totale dove possibile.
3. Verificare il risultato con test mirati e controlli statici.

### Rischi identificati

- Ottimizzazione troppo aggressiva che cambia comportamento utente o compatibilità file.
- Complessità eccessiva se si cerca streaming perfetto in un unico passo.

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

- Nuovo task performance post-audit 2026-04-03.
- Affrontarlo solo dopo TASK-026 o comunque senza mescolare fix di correttezza e ottimizzazioni.
