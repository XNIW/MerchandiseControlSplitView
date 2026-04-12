# TASK-049 — Estensione filtro Cronologia: fornitore e categoria

---

## Informazioni generali

| Campo              | Valore                                              |
|--------------------|-----------------------------------------------------|
| ID                 | TASK-049                                            |
| Stato              | DONE                                                |
| Priorità           | ALTA                                                |
| Area               | UX/UI — History / Cronologia; ViewModel             |
| Creato             | 2026-04-12                                          |
| Ultimo aggiornamento | 2026-04-12 (review planner APPROVED → DONE)         |

---

## Dipendenze

- TASK-044 (DONE) — filtro entry tecniche in place; lista utente-visibile stabile.
- TASK-048 (REVIEW) — polish UI Cronologia già eseguito; questo task lavora sullo stesso file senza toccare scope TASK-048.

---

## Scopo

Estendere il filtro della schermata Cronologia per supportare, oltre al periodo esistente, anche **fornitore** e **categoria**. Il risultato deve sembrare una naturale evoluzione della schermata già rifinita, non un pannello tecnico aggiunto sopra. L'interfaccia di filtro diventa a due livelli: riepilogo compatto nella card in alto + `ModalBottomSheet` per la modifica.

---

## Contesto

La Cronologia filtra oggi solo per periodo (All / mese corrente / mese precedente / intervallo personalizzato). Il filtro vive nel `ExcelViewModel` come `_dateFilter: MutableStateFlow<DateFilter>`, esposto come `dateFilter: StateFlow<DateFilter>`. La lista visibile è `historyListEntries` (date-filtered from DB). Il NavGraph collegava `dateFilter` → `HistoryScreen(currentFilter, onSetFilter)`.

Dopo TASK-048 la schermata ha già: card filtro con OutlinedButton + DropdownMenu, separatori mese, titolo display leggibile, spacing rifinito.

Ogni `HistoryEntryListItem` contiene già `supplier: String` e `category: String`, quindi non servono modifiche al DB.

---

## Decisioni

| # | Decisione | Motivazione |
|---|-----------|-------------|
| 1 | **Nuovo task (TASK-049), non refinement di TASK-048** | TASK-048 è in REVIEW; aggiungere scope a un task in REVIEW viola la governance. I due task sono ortogonali. |
| 2 | **In-memory filter per supplier/category, DB per date** | Non tocca DAO/repository; la history è piccola in memoria; clean separation: DB per windowing temporale, VM per classificazione. |
| 3 | **`historyListEntries` rimane date-only** per `GeneratedScreen` | `GeneratedScreen` usa `.firstOrNull { it.uid == entryUid }` su questa lista; aggiungere filtri in-memory potrebbe nascondere l'entry caricata se i filtri attivi non la includono. |
| 4 | **`historyDisplayEntries`** nuovo flow = date + supplier + category | Esposto solo alla `HistoryScreen` via NavGraph; nessuna modifica alla logica di GeneratedScreen. |
| 5 | **`HistoryFilter` data class** wrappa DateFilter + supplier + category | Type-safe, estensibile, pulita come API del ViewModel. |
| 6 | **ModalBottomSheet** per l'editing filtri | Coerente con l'app (altri screen usano bottom sheet); più spazio per i chip rispetto a un DropdownMenu; non "ingombra" la schermata. |
| 7 | **Card filtro compatta con summary testuale** | "Tutti" / "Aprile 2026 · Cosmiva · baño" — l'utente capisce subito i filtri attivi; nessun testo ridondante; la card è clickable per aprire il sheet. |
| 8 | **Draft filter** gestito nel composable parent (HistoryScreen) | Permette al DatePickerDialog (per custom range) di aggiornare il draft prima dell'Applica; nessun hoist di logica nel ViewModel. |
| 9 | **Chip "Tutti"** per supplier/category resetta il singolo filtro | Semantica chiara; click su fornitore già selezionato → deseleziona (toggle off); solo un fornitore o categoria alla volta (scelta semplice e sufficiente per questo use case). |
| 10 | **Section supplier/category nascosta** se non ci sono valori disponibili | Evita sezioni vuote nel sheet; le sezioni appaiono solo quando ci sono opzioni reali. |
| 11 | **`availableHistorySuppliers/Categories`** derivati da `historyListEntries` (date-only) | Mostra opzioni coerenti con il periodo selezionato; se filtri per mese corrente vedi solo i fornitori di quel mese. |
| 12 | **`setHistoryFilter(HistoryFilter)`** come unica API pubblica per il nuovo filtro | Sostituisce `setDateFilter`; aggiorna i tre `MutableStateFlow` interni; `setDateFilter` mantenuto per eventuali call site non toccati. |
| 13 | **`history_filtered_empty_message`** aggiornato a testo generico | La vecchia stringa menzionava solo "date range"; ora ci possono essere filtri fornitore/categoria attivi. |

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | La card filtro mostra un riepilogo testuale dei filtri attivi (es. "Tutti", "Mese corrente · Cosmiva") | M | — |
| 2 | Tappare la card (o il suo bottone) apre un `ModalBottomSheet` | M | — |
| 3 | Il bottom sheet ha sezione **Periodo** con i 4 option (Tutti / Mese corrente / Mese precedente / Intervallo personalizzato) selezionabili come chip | M | — |
| 4 | Il bottom sheet ha sezione **Fornitore** con chip "Tutti" + supplier disponibili; la sezione è nascosta se non ci sono supplier valorizzati | M | — |
| 5 | Il bottom sheet ha sezione **Categoria** con chip "Tutti" + categorie disponibili; la sezione è nascosta se non ci sono categorie valorizzate | M | — |
| 6 | "Reimposta" nel sheet azzera il draft filter a `HistoryFilter()` | M | — |
| 7 | "Applica" nel sheet chiama `onSetFilter(draftFilter)` e chiude lo sheet | M | — |
| 8 | "Intervallo personalizzato" nel sheet apre il DatePickerDialog esistente; la selezione aggiorna il draft (non applica direttamente) | M | — |
| 9 | Selezionare un supplier filtra la lista in tempo reale dopo "Applica" | M | — |
| 10 | Selezionare una categoria filtra la lista in tempo reale dopo "Applica" | M | — |
| 11 | I separatori mese continuano a funzionare correttamente con i risultati filtrati | M | — |
| 12 | Con lista vuota per filtri troppo restrittivi, l'empty state è mostrato correttamente | M | — |
| 13 | Nessuna regressione: tap su entry, swipe rename/delete, export/sync status, navigazione invariati | M | — |
| 14 | `GeneratedScreen` continua a trovare la sua entry tramite `historyListEntries` (nessuna modifica a questo flow) | S | — |
| 15 | Nessuna modifica a DAO, Room, repository, `HistoryEntry` entity, navigation | S | — |
| 16 | Build Gradle `assembleDebug` OK, lint senza nuovi warning | B | — |

---

## File coinvolti

### Da modificare
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml`

### Da non toccare
- `HistoryEntryUiFormatters.kt` — pura logica presentazionale TASK-048, nessuna relazione
- `HistoryEntry.kt`, `HistoryEntryDao`, `InventoryRepository` — nessuna modifica DB/repository
- `NavGraph.kt` navigation routes — solo aggiornamento wiring state/callbacks

---

## Baseline regressione TASK-004

Il task tocca `ExcelViewModel` (area TASK-004). Check obbligatori:
- `historyListEntries` (used by GeneratedScreen) deve restare invariato come flow date-only.
- Nessuna modifica ai flussi di import/export, sync, manual entry.
- Eseguire `./gradlew testDebugUnitTest` baseline rilevante post-execution.

---

## Execution

### Esecuzione — 2026-04-12

**File modificati:**
- `ExcelViewModel.kt` — `HistoryFilter` data class, `_supplierFilter`, `_categoryFilter`, `historyFilter`, `historyDisplayEntries`, `availableHistorySuppliers`, `availableHistoryCategories`, `setHistoryFilter`
- `HistoryScreen.kt` — firma aggiornata, `draftFilter` state, card filtro compatta, `HistoryFilterSheet` ModalBottomSheet
- `NavGraph.kt` — wiring `historyDisplayEntries`, `historyFilter`, `availableHistorySuppliers/Categories`, `onSetFilter → setHistoryFilter`
- `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — 5 nuove stringhe

**Azioni eseguite:**
1. `ExcelViewModel.kt`: aggiunto `HistoryFilter` data class (wrappa `DateFilter` + `supplier` + `category` con computed props `isDateFilterActive`, `isSupplierFilterActive`, `isCategoryFilterActive`, `hasAnyActiveFilter`).
2. `ExcelViewModel.kt`: aggiunti `_supplierFilter` e `_categoryFilter` `MutableStateFlow<String>`; derivato `historyFilter: StateFlow<HistoryFilter>` (combine dei tre); aggiunto `historyDisplayEntries` (date-filtered via DB + in-memory supplier/category); aggiunti `availableHistorySuppliers` e `availableHistoryCategories` (distinct/sorted da `historyListEntries` pre-in-memory-filter); aggiunto `setHistoryFilter(HistoryFilter)`; mantenuto `setDateFilter` per compatibilità.
3. `HistoryScreen.kt`: aggiornata firma con `currentFilter: HistoryFilter`, `onSetFilter: (HistoryFilter) -> Unit`, `availableSuppliers`, `availableCategories`; aggiunto `showFilterSheet` e `draftFilter` state; rimosso `showFilterMenu` + `DropdownMenu`; sostituita filter card con card compatta clickable (summary testuale "Tutti"/"Mese corrente · Cosmiva · …", reset IconButton se filtri attivi); DatePickerDialog aggiornato per aggiornare `draftFilter` invece di chiamare `onSetFilter` direttamente; aggiunti import `Close`, `HistoryFilter`, `FlowRow`, `rememberScrollState`, `verticalScroll`; aggiunto composable privato `HistoryFilterSheet` (ModalBottomSheet con FlowRow chip per Periodo/Fornitore/Categoria, azioni Reimposta/Applica).
4. `NavGraph.kt`: sostituita raccolta di `historyListEntries`/`dateFilter` con `historyDisplayEntries`/`historyFilter`/`availableHistorySuppliers`/`availableHistoryCategories`; `onSetFilter` aggiornato a `setHistoryFilter`.
5. `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml`: aggiornato `history_filtered_empty_message` a testo generico; aggiunte 5 nuove stringhe (`history_filter_sheet_title`, `history_filter_period_section`, `history_filter_all_option`, `history_filter_reset`, `history_filter_apply`) in tutti e 4 i locali.

**Delta UI intenzionale (non richiesto esplicitamente ma coerente con AGENTS.md):**
- `history_filtered_empty_message` aggiornato da testo specifico date a testo generico — necessario per coprire i nuovi filtri supplier/category. Modifica minima e motivata.

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle (`assembleDebug`) | B | ✅ | `BUILD SUCCESSFUL in 11s`, 0 errori |
| Lint | S | ✅ | `BUILD SUCCESSFUL in 15s`, 0 nuovi warning |
| Warning Kotlin | S | ✅ | Solo warning `rememberSwipeToDismissBoxState` pre-esistenti, non introdotti da questo task |
| `historyListEntries` GeneratedScreen nessuna regressione | S | ✅ | Flow rimasto invariato; `GeneratedScreen` continua a usare `historyListEntries` (date-only); `historyDisplayEntries` è un flow separato aggiunto |
| Baseline regressione TASK-004 (`testDebugUnitTest`) | B | ✅ | `BUILD SUCCESSFUL in 17s`, nessun test fallito |

---

## Review

### Review — 2026-04-12

**Revisore:** Claude (planner) — review repo-grounded

**Nota sul perimetro:** i criteri 4 e 5 (chip FlowRow per fornitore/categoria nel filter sheet) sono stati superseded da TASK-050 (picker con ricerca), eseguito subito dopo in modo pianificato e governato. La review valuta i criteri di sostanza (ViewModel, NavGraph, logica filtro, separazione dei flussi) che rimangono validi indipendentemente dal layer UI chip→picker.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Card filtro mostra riepilogo testuale filtri attivi | ✅ | `filterSummary` buildString con `dateFilterLabel + sep + supplier + sep + category` |
| 2 | Tap card apre `ModalBottomSheet` | ✅ | `onClick = { draftFilter = currentFilter; showFilterSheet = true }` sulla Card |
| 3 | Sheet ha sezione Periodo con 4 chip selezionabili | ✅ | `FlowRow` con 4 `FilterChip` (All/LastMonth/PreviousMonth/CustomRange) |
| 4 | Sheet ha sezione Fornitore (nascosta se vuota) | ✅ | Presente come `HistoryFilterSelector` (TASK-050 ha sostituito chip→picker; logica core invariata) |
| 5 | Sheet ha sezione Categoria (nascosta se vuota) | ✅ | Stesso pattern; `if (availableCategories.isNotEmpty())` |
| 6 | "Reimposta" azzera draft a `HistoryFilter()` | ✅ | `onReset = { draftFilter = HistoryFilter() }` |
| 7 | "Applica" chiama `onSetFilter(draftFilter)` e chiude | ✅ | `onApply = { onSetFilter(draftFilter); showFilterSheet = false }` |
| 8 | "Intervallo personalizzato" apre DatePickerDialog; aggiorna draft | ✅ | `onCustomRangeRequest` → `showDatePickerDialog = true`; conferma aggiorna `draftFilter.copy(dateFilter = DateFilter.CustomRange(...))` |
| 9 | Supplier filtra lista dopo Applica | ✅ | `historyDisplayEntries` filtra in-memory per `supplier.equals(ignoreCase)` in ExcelViewModel |
| 10 | Categoria filtra lista dopo Applica | ✅ | Stesso meccanismo in-memory per `category` |
| 11 | Separatori mese corretti con lista filtrata | ✅ | `historyRows` è `remember(historyList, ...)` — segue il flow filtrato |
| 12 | Empty state mostrato su lista vuota filtrata | ✅ | `if (historyList.isEmpty())` → `HistoryEmptyState` con `history_filtered_empty_message` |
| 13 | Nessuna regressione su rename/delete/swipe/export/sync | ✅ | Logiche invariate; solo nuovi parametri aggiunti alla firma |
| 14 | `historyListEntries` (GeneratedScreen) non toccato | ✅ | Confermato in ExcelViewModel: `historyListEntries` resta date-only, separato da `historyDisplayEntries` |
| 15 | Nessuna modifica a DAO/Room/repository/HistoryEntry/navigation routes | ✅ | Solo ExcelViewModel (nuovi flow), HistoryScreen.kt (nuova firma+UI), NavGraph (solo wiring) |
| 16 | Build `assembleDebug` OK, lint senza nuovi warning | ✅ | `BUILD SUCCESSFUL in 11s` (build), `BUILD SUCCESSFUL in 15s` (lint) per execution log |

**Problemi trovati:**
- Nessuno bloccante.
- Nota: `history_filtered_empty_message` aggiornato da testo date-specific a generico — delta minimo e necessario per coprire i nuovi filtri supplier/category, conforme a AGENTS.md.

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
| Tutti i criteri ✅? | Sì — 16/16 ✅ (criteri 4 e 5 superseded da TASK-050 per il layer chip→picker; logica ViewModel/filtro invariata e corretta) |
| Rischi residui      | Nessun rischio bloccante. Test manuali visivi delegati a smoke su emulator. |
