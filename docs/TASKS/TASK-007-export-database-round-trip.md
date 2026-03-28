# TASK-007 — Export database completo — verifica round-trip

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-007                   |
| Stato              | PLANNING                   |
| Priorità           | MEDIA                      |
| Area               | Export / Database          |
| Creato             | 2026-03-28                 |
| Ultimo aggiornamento | 2026-03-28 (attivazione da backlog dopo TASK-005 `DONE`) |

---

## Dipendenze

- **TASK-005** (`DONE`, 2026-03-28) — copertura test utility/import analysis
- **TASK-017** (`DONE`) — import DB completo stabile (contesto OOM mitigato)

---

## Scopo

Verificare che l’**export full database** (workbook con **4 fogli** previsti) seguito da **re-import** produca dati **equivalenti** (round-trip) per **Products**, **Suppliers**, **Categories**, **PriceHistory**. Definire o estendere **test** (o procedura verificabile) che documentino l’equivalenza attesa.

---

## Contesto

Dopo **TASK-017** l’import completo è considerato stabile. Il round-trip export→import riduce il rischio di perdita o distorsione dati su percorsi Excel full-DB. Dettaglio implementativo da ricavare dal codice (`FullDbImportStreaming`, export corrispondente, ViewModel/repository).

---

## Non incluso

- Redesign UI export/import
- Modifiche schema Room non necessarie al task
- Scope **TASK-006** (robustezza errori import generici) — task separato

---

## File potenzialmente coinvolti

- Codice export full DB e import streaming (da mappare in **Planning** all’avvio **EXECUTION**)
- Test JVM / fixture file (se introdotti nel task)
- `docs/MASTER-PLAN.md` — solo tracking a chiusura

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Round-trip documentato e verificabile per le quattro entità/fogli nel perimetro | B/S | — |
| 2 | `./gradlew test` e build rilevanti passano dopo le modifiche | B | — |
| 3 | Nessuna regressione non motivata su flussi export/import esistenti | S | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task attivato da backlog su richiesta utente | Priorità prodotto post-TASK-005 | 2026-03-28 |

---

## Planning (Claude)

_(Da compilare in PLANNING prima di EXECUTION: mappare entrypoint export/import, formato fogli, vincoli di equivalenza.)_

---

## Execution

_(Da compilare dall'esecutore)_

---

## Review

_(Da compilare dal reviewer)_

---

## Fix

_(Da compilare dall'esecutore se necessario)_

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

_(Da compilare a chiusura)_

---

## Handoff

_(Da compilare a chiusura)_
