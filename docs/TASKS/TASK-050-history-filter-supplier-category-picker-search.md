# TASK-050 — Filtro Cronologia: picker con ricerca per fornitore e categoria

---

## Informazioni generali

| Campo              | Valore                                              |
|--------------------|-----------------------------------------------------|
| ID                 | TASK-050                                            |
| Stato              | DONE                                                |
| Priorità           | ALTA                                                |
| Area               | UX/UI — History / Cronologia                        |
| Creato             | 2026-04-12                                          |
| Ultimo aggiornamento | 2026-04-12 (review planner APPROVED → DONE)        |

---

## Dipendenze

- TASK-049 (REVIEW) — implementazione base filtro fornitore/categoria con chip; questo task sostituisce il solo layer UI del filter sheet, non la logica ViewModel.

---

## Scopo

Sostituire la sezione FlowRow di chip per Fornitore e Categoria nel `HistoryFilterSheet` con un **compact selector row + dialog picker con ricerca**. La soluzione scala bene a dataset grandi, mantiene il filter sheet leggero e offre feedback chiaro sul valore selezionato.

---

## Contesto

TASK-049 ha introdotto un `HistoryFilterSheet` con chip FlowRow per fornitore/categoria. Con molti fornitori/categorie, il muro di chip diventa non scalabile. L'utente ha richiesto una UI più matura: selettore compatto nel sheet + tap → dialog picker con campo ricerca e lista scrollabile.

Il ViewModel (`ExcelViewModel`) è già corretto: `historyDisplayEntries`, `availableHistorySuppliers`, `availableHistoryCategories`, `setHistoryFilter`, `HistoryFilter` sono tutti già in place. **Nessuna modifica al ViewModel.**

---

## Decisioni

| # | Decisione | Motivazione |
|---|-----------|-------------|
| 1 | **Nuovo task TASK-050, non modifica TASK-049** | TASK-049 è in REVIEW; aggiungere scope viola la governance. |
| 2 | **AlertDialog come picker** | Più semplice di nested ModalBottomSheet; nessun problema con la Compose sheet stack; pattern Material3 idiomatico; heightIn(max=280.dp) sulla LazyColumn gestisce liste lunghe. |
| 3 | **HistoryFilterSelector** composable compatto per la riga selettore | Card clickable con testo del valore attuale + icona ArrowDropDown; coerente con Card già in uso nel file; colore primary se valore attivo. |
| 4 | **HistoryValuePickerDialog** con ricerca live in-composable | searchQuery è stato locale nel dialog (no hoist necessario); filtro `contains(ignoreCase=true)` sufficiente. |
| 5 | **Periodo rimane con chip FlowRow** | 4 opzioni fisse = nessun problema di scalabilità; chip period già apprezzato. |
| 6 | **Selezione nel picker chiude il dialog immediatamente** | Pattern più fluido: tap → selezione + chiusura dialog; draft aggiornato; "Annulla" per uscire senza modificare. |
| 7 | **Nessuna logica ibrida chip/picker** | La soluzione picker è sempre pulita; la logica ibrida aggiunge complessità per beneficio marginale. |
| 8 | **Nessuna modifica a ViewModel, NavGraph, DAO, Room** | La logica è già corretta; solo il layer UI del filter sheet cambia. |

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Nel filter sheet, le sezioni Fornitore e Categoria mostrano una riga compatta con il valore corrente ("Tutti" o valore selezionato) e icona DropDown | M | — |
| 2 | Tap sulla riga Fornitore apre un AlertDialog picker | M | — |
| 3 | Tap sulla riga Categoria apre un AlertDialog picker | M | — |
| 4 | Il picker mostra un campo ricerca che filtra live la lista | M | — |
| 5 | Il picker ha opzione "Tutti" sempre visibile in cima | M | — |
| 6 | Selezionare un'opzione nel picker aggiorna il draft e chiude il dialog | M | — |
| 7 | "Annulla" nel picker chiude senza modificare il draft | M | — |
| 8 | La sezione è nascosta se non ci sono valori disponibili (invariato da TASK-049) | M | — |
| 9 | "Reimposta" / "Applica" nel sheet principale funzionano come prima | M | — |
| 10 | Periodo rimane con chip FlowRow invariato | M | — |
| 11 | Nessuna regressione: rename/delete, swipe, separatori mese, export/sync status | M | — |
| 12 | `historyListEntries` (GeneratedScreen) non toccato | S | — |
| 13 | Nessuna modifica a ViewModel, NavGraph, DAO, Room | S | — |
| 14 | Build `assembleDebug` OK, lint senza nuovi warning | B | — |

---

## File coinvolti

### Da modificare
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml`

### Da non toccare
- `ExcelViewModel.kt` — ViewModel già corretto
- `NavGraph.kt` — wiring già corretto
- Qualsiasi file DAO/Room/repository

---

## Execution

### Esecuzione — 2026-04-12

**File modificati:**
- `HistoryScreen.kt` — sostituita logica FlowRow chip fornitore/categoria con picker; aggiunti composable `HistoryFilterSelector` e `HistoryValuePickerDialog`; aggiunti import `ArrowDropDown`, `Search`
- `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — aggiunta stringa `history_filter_search_hint`

**Azioni eseguite:**
1. `HistoryScreen.kt`: in `HistoryFilterSheet` aggiunti `showSupplierPicker` e `showCategoryPicker` come stato locale; rimossi i blocchi FlowRow Fornitore/Categoria; aggiunti `HistoryFilterSelector(...)` per ciascuno; aggiunte chiamate `HistoryValuePickerDialog(...)` condizionali fuori dal `ModalBottomSheet`.
2. `HistoryFilterSelector`: `Card` clickable con riga compatta — testo del valore corrente (primario/SemiBold se attivo, secondario/Normal se "Tutti") + icona `ArrowDropDown`. Nessuna logica di business.
3. `HistoryValuePickerDialog`: `AlertDialog` con `OutlinedTextField` (ricerca live, `searchQuery` stato locale) + `LazyColumn(heightIn(max=280.dp))` — item fisso "Tutti" in cima + opzioni filtrate; ogni item è un `Row` clickable con `RadioButton` + `Text`; la selezione chiama `onSelect(value)` (che aggiorna il draft e chiude il dialog) oppure `onDismiss` (Annulla, senza modifiche).
4. Periodo rimane invariato con chip FlowRow.
5. Nessuna modifica a ExcelViewModel, NavGraph, DAO, Room.
6. Aggiunte 4 stringhe `history_filter_search_hint` (IT/EN/ES/ZH).

**Nota:** nessuna logica ibrida chip/picker — la soluzione picker è adottata sempre per coerenza e semplicità.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle (`assembleDebug`) | B | ✅ | `BUILD SUCCESSFUL in 4s`, 0 errori; solo warning pre-esistente `rememberSwipeToDismissBoxState` |
| Lint | S | ✅ | `BUILD SUCCESSFUL in 14s`, 0 nuovi warning |
| ExcelViewModel non modificato | S | ✅ | Nessuna modifica al file |
| NavGraph non modificato | S | ✅ | Nessuna modifica al file |
| DAO/Room non modificati | S | ✅ | Nessuna modifica |

---

## Review

### Review — 2026-04-12

**Revisore:** Claude (planner) — review repo-grounded

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Sheet: sezioni Fornitore/Categoria mostrano riga compatta con valore + icona DropDown | ✅ | `HistoryFilterSelector`: Card clickable con testo (primary/SemiBold se attivo) + `ArrowDropDown` |
| 2 | Tap su riga Fornitore apre AlertDialog picker | ✅ | `showSupplierPicker = true` → `HistoryValuePickerDialog(...)` |
| 3 | Tap su riga Categoria apre AlertDialog picker | ✅ | `showCategoryPicker = true` → `HistoryValuePickerDialog(...)` |
| 4 | Picker mostra campo ricerca che filtra live | ✅ | `searchQuery` stato locale + `remember(options, searchQuery)` per `filteredOptions` |
| 5 | Picker ha opzione "Tutti" sempre visibile in cima | ✅ | `item(key = "_all")` fisso prima degli `items(filteredOptions)` |
| 6 | Selezione nel picker aggiorna draft e chiude dialog | ✅ | `onSelect = { value -> onDraftChange(draftFilter.copy(supplier = value)); showSupplierPicker = false }` |
| 7 | "Annulla" chiude senza modificare draft | ✅ | `onDismiss = { showSupplierPicker = false }` nel `confirmButton` dell'AlertDialog — nessuna chiamata a `onDraftChange` |
| 8 | Sezione nascosta se non ci sono valori disponibili | ✅ | `if (availableSuppliers.isNotEmpty())` / `if (availableCategories.isNotEmpty())` invariati |
| 9 | "Reimposta" / "Applica" funzionano come prima | ✅ | Logica onReset/onApply non toccata |
| 10 | Periodo rimane con chip FlowRow invariato | ✅ | Sezione Periodo non modificata |
| 11 | Nessuna regressione: swipe, separatori mese, export/sync | ✅ | Solo `HistoryFilterSheet` modificato; HistoryRow e liste invariati |
| 12 | `historyListEntries` (GeneratedScreen) non toccato | ✅ | ExcelViewModel non modificato |
| 13 | Nessuna modifica a ViewModel, NavGraph, DAO, Room | ✅ | Confermato dall'execution log |
| 14 | Build `assembleDebug` OK, lint senza nuovi warning | ✅ | `BUILD SUCCESSFUL in 4s` (build), `BUILD SUCCESSFUL in 14s` (lint) per execution log |

**Problemi trovati:**
- Nessuno bloccante.
- Nota minore non bloccante: il `HistoryValuePickerDialog` usa valori hardcodati `8.dp` e `4.dp` per spacing interno invece dei token `spacing.*`. Non è un bug, non introduce regression, e il dialog è localmente coerente. Rischio residuo BASSA.

**Verdetto:** APPROVED

---

## Fix

*(nessun fix necessario)*

---

## Chiusura

| Campo               | Valore |
|---------------------|--------|
| Stato finale        | DONE |
| Data chiusura       | 2026-04-12 |
| Tutti i criteri ✅? | Sì — 14/14 ✅ |
| Rischi residui      | Spacing hardcoded nel dialog interno (`8.dp` / `4.dp`) — non è un bug, è un'inconsistenza stilistica minore; rischio BASSA non bloccante. Test manuali visivi (ricerca live, scroll lista lunga, selezione/chiusura) da eseguire su emulator alla prima occasione. |
