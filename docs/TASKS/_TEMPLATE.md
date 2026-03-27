# TASK-NNN — [Titolo breve e descrittivo]

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-NNN                   |
| Stato              | PLANNING / EXECUTION / REVIEW / FIX / DONE / BLOCKED / WONT_DO |
| Priorità           | CRITICA / ALTA / MEDIA / BASSA |
| Area               | [area funzionale]          |
| Creato             | [data]                     |
| Ultimo aggiornamento | [data]                   |

---

## Dipendenze

- [TASK-XXX se presente, oppure "Nessuna"]

---

## Scopo

[Descrizione chiara e concisa di cosa deve fare questo task. Max 3-5 frasi.]

---

## Contesto

[Perché questo task esiste. Qual è il problema o l'opportunità. Riferimenti a codice, aree, conversazioni.]

---

## Non incluso

[Cosa esplicitamente NON fa parte di questo task. Importante per evitare scope creep.]

---

## File potenzialmente coinvolti

- `path/to/file.kt` — [motivo]
- [elenco dei file che l'esecutore dovrà leggere o modificare]

---

## Criteri di accettazione

| # | Criterio                                                | Tipo verifica | Stato |
|---|---------------------------------------------------------|---------------|-------|
| 1 | [Criterio specifico e verificabile]                     | B/S/M/E       | —     |
| 2 | [Criterio specifico e verificabile]                     | B/S/M/E       | —     |

Legenda tipi: B=Build, S=Static, M=Manual, E=Emulator

> Per task UX/UI: includere anche la checklist "Definition of Done — task UX/UI" definita in `docs/MASTER-PLAN.md`.

---

## Decisioni

| # | Decisione                          | Motivazione                | Data       |
|---|------------------------------------|----------------------------|------------|
| 1 | [Decisione presa]                  | [Perché]                   | [data]     |

---

## Planning (Claude)

### Analisi

[Analisi del codice e del contesto]

### Piano di esecuzione

1. [Passo 1]
2. [Passo 2]
3. [Passo n]

### Rischi identificati

- [Rischio e mitigazione]

---

## Execution

### Esecuzione — [data]

**File modificati:**
- `path/to/file.kt` — [descrizione]

**Azioni eseguite:**
1. [Azione]

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza                          |
|--------------------------|------|-------|-----------------------------------|
| Build Gradle             | B    | —     | (N/A se task solo documentazione) |
| Lint                     | S    | —     | (N/A se task solo documentazione) |
| Warning Kotlin           | S    | —     | (N/A se task solo documentazione) |
| Coerenza con planning    | —    | —     |                                   |
| Criteri di accettazione  | —    | —     |                                   |

**Incertezze:**
- (nessuna, oppure elenco)

---

## Review

### Review — [data]

**Revisore:** Claude (planner)

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | ...      | —     |      |

**Problemi trovati:**
- (elenco, oppure "nessuno")

**Verdetto:** APPROVED / FIX_REQUIRED / BLOCKED

---

## Fix

### Fix — [data]

**Correzioni applicate:**
- [Descrizione correzione]

**Ri-verifica:**
- [Evidenza che il problema è risolto]

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

[Sintesi di ciò che è stato fatto, decisioni chiave, rischi residui]

---

## Handoff

[Note per il prossimo operatore: contesto, decisioni, test manuali suggeriti, aree correlate]
