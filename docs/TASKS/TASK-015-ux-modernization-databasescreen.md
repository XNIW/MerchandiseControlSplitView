# TASK-015 — UX modernization DatabaseScreen

---

## Informazioni generali

| Campo                | Valore                     |
|----------------------|----------------------------|
| ID                   | TASK-015                   |
| Stato                | PLANNING                   |
| Priorità             | MEDIA                      |
| Area                 | UX / UI / DatabaseScreen   |
| Creato               | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-27                 |

**Nota tracking:** unico task **`ACTIVE`** nel `MASTER-PLAN`; fase corrente **`PLANNING`** fino ad approvazione utente per **EXECUTION**. **TASK-017** è **`DONE`**. **TASK-002** resta **`BLOCKED`** — **TASK-014** non va attivato finché **TASK-002** dipende da smoke/decisione.

---

## Dipendenze

- TASK-001 (DONE)
- TASK-017 (DONE) — stabilità full-import; questo task è **solo** modernization UI DatabaseScreen

---

## Scopo

Modernizzare **layout**, **toolbar**, **dialog import/export** e **affordance scanner** su `DatabaseScreen`, in linea Material3/Compose idiomatico. **Nessun** cambio alla logica business (CRUD, import, export, barcode). **Nessuna** rimozione di feature esistenti.

**Feedback UX utente (da integrare nel planning/esecuzione):**

- **Import:** percorso diretto senza mini-menu ridondante (già parzialmente affrontato in TASK-017; verificare coerenza e rifinitura).
- **Icone:** coerenza percepita import/export in toolbar (allineamento semantico icone ↔ azioni).
- **Export:** mantenere menu a più voci **dove ha senso** (es. più destinazioni/formati).
- **Chiarezza** generale (gerarchia, stati, etichette) **senza** rifare architettura né navigazione.

---

## Contesto

`DatabaseScreen.kt` è tra i file più grandi dell’app; condivide import full DB e operazioni CRUD. TASK-017 ha toccato margini UI (import unificato, icone). TASK-015 completa una **modernizzazione UX** dedicata, nel perimetro guardrail `AGENTS.md` / `MASTER-PLAN` (ViewModel = stato; DAO/repository/navigation salvo necessità esplicita).

---

## Non incluso

- Refactor `DatabaseViewModel` / `InventoryRepository` / Room / `NavGraph` salvo necessità **motivata** e minima.
- Rimuovere capacità (scanner, export multiplo, full import, ecc.).
- TASK-003 (decomposizione file) come obiettivo primario: qui solo miglioramenti UX coerenti con il file attuale.
- Porting 1:1 da iOS: solo riferimento visivo se utile.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — principale
- `DatabaseViewModel.kt`, `InventoryRepository.kt` — **sola lettura** salvo wiring minimo strettamente necessario
- `app/src/main/res/values*/strings.xml` — stringhe UI se servono testi più chiari

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Toolbar / azioni primarie più chiare (import/export/scanner coerenti col feedback utente) | M | — |
| 2 | Dialog import/export più leggibili e idiomatici (Material3), senza cambiare contratti funzionali | M | — |
| 3 | Nessuna regressione funzionale su CRUD, import (single + full), export, scanner | M | — |
| 4 | `./gradlew assembleDebug` OK | B | — |
| 5 | `./gradlew lint` senza nuovi warning introdotti | S | — |

Legenda: B=Build, S=Static, M=Manual

> Checklist aggiuntiva: *Definition of Done — task UX/UI* in `docs/MASTER-PLAN.md`.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task attivato dopo **DONE** di **TASK-017** | Priorità stabilità import risolta | 2026-03-27 |
| 2 | **TASK-014** non attivato: dipende da **TASK-002** `BLOCKED` | Ordine backlog nel MASTER-PLAN | 2026-03-27 |

---

## Planning (Claude)

### Analisi

Leggere `DatabaseScreen.kt` attuale (toolbar, menu import/export, dialog, lista CRUD, integrazione scanner). Allineare le modifiche al feedback utente sopra senza duplicare lavoro già fatto in TASK-017 dove sufficiente.

### Piano di esecuzione (bozza)

1. Inventario UI esistente vs feedback (import diretto, icone, export menu).
2. Proporre modifiche incrementali (spacing, titoli dialog, ordine azioni, iconografia coerente).
3. Verificare criteri + DoD UX nel MASTER-PLAN; build/lint.

### Rischi identificati

- Accidentalmente toccare logica in ViewModel: mitigazione = diff mirato solo UI.
- `DatabaseScreen` molto lungo: estrazioni locali di composable solo se il task lo richiede esplicitamente (coerenza con guardrail).

---

## Execution

_(Non avviata — in **PLANNING**.)_

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

- **Predecessore:** TASK-017 (full-import OOM) **`DONE`**.
- **Bloccante altrove:** TASK-002 → TASK-014 resta in backlog.
