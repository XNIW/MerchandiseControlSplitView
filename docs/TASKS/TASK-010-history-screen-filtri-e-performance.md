# TASK-010 — History screen — filtri e performance

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-010                                    |
| Stato (backlog / `MASTER-PLAN`) | `DONE` |
| Fase workflow        | **DONE** (review completata 2026-03-29; fix mirati applicati e tracking chiuso) |
| Priorità             | BASSA                                       |
| Area                 | History / ExcelViewModel / Room             |
| Creato               | 2026-03-28                                  |
| Ultimo aggiornamento | 2026-03-29 — review completa post-execution; fix puntuali su `currentEntryName`/Generated title e micro-UX filtro attivo; task chiuso in **DONE** |

### Allineamento tracking / governance

- **Fonte backlog globale:** `docs/MASTER-PLAN.md` — `TASK-010` deve risultare **`DONE`** e non più `ACTIVE`.
- **Fonte perimetro / piano:** questo file (`Stato (backlog)` = **DONE**, `Fase workflow` = **DONE**).
- Se in qualsiasi punto si legge TASK-010 come **`ACTIVE`** / **`EXECUTION`**, riallineare subito `MASTER-PLAN.md` e questo file nello stesso intervento.
- **Verifica tracking finale:** nessun altro task deve essere marcato `ACTIVE` come side effect della chiusura di TASK-010.
- **Regola:** un solo `ACTIVE` alla volta; la chiusura di TASK-010 non attiva automaticamente un task successivo.

---

## Dipendenze

- **TASK-001** — `DONE` (governance / baseline)

---

## Scopo

Valutare `HistoryScreen` con molte cronologie import: correttezza e robustezza dei filtri data (incluso intervallo personalizzato e stato del dialog custom range), UX dei filtri e degli stati vuoti, e performance end-to-end (query Room → ViewModel → Compose). Seguire un **decision gate** ordinato (misurazione → lite list → full row solo su apertura entry → Paging3 solo se necessario). **Nessun redesign** di schermata, nessuna rimozione di feature, nessun porting 1:1 da iOS.

---

## Contesto (stato codice verificato 2026-03-28)

- **UI:** `HistoryScreen.kt` — `LazyColumn` + `items(..., key = uid)`; filtri da `DropdownMenu`; intervallo custom con `DatePickerDialog` a due step (start → end); snackbar per messaggi rename/delete (TASK-008).
- **Stato:** `ExcelViewModel` — `_dateFilter: MutableStateFlow<DateFilter>` + `historyEntries` = `flatMapLatest { repository.getFilteredHistoryFlow(filter) }.stateIn(...)` — pattern corretto per cancellare query obsolete.
- **Persistenza:** `DefaultInventoryRepository.getFilteredHistoryFlow` — `DateFilter.All` → `getAllFlow()`; mese corrente / mese precedente / custom → `getEntriesBetweenDatesFlow` con stringhe `yyyy-MM-dd HH:mm:ss` allineate a `buildHistoryEntry` / insert.
- **DAO:** `HistoryEntryDao` — `SELECT *` su tutte le query history; nessuna paginazione; confronto range su colonna `timestamp` **stringa** (ordinamento lessicografico OK per il pattern usato). **`getByUid` già disponibile** per caricare una singola riga completa quando l’utente apre un entry — base naturale per strategia “lite in lista, full on demand”.
- **Test:** `DefaultInventoryRepositoryTest` — `getFilteredHistoryFlow respects custom date range` copre almeno il custom range su DB in-memory.
- **Sovrapposizione backlog:** **TASK-016** (polish visivo History / ImportAnalysis / grid) — **TASK-010** può includere solo **micro-UX** locale (filtro attivo, empty state distinti, reset dialog); polish ampio della scheda History resta in **TASK-016**.

---

## Non incluso

- Redesign completo della History (toolbar, layout card, nuovi flussi) — fuori perimetro; vedere **TASK-016** se necessario.
- Refactor ampio di **GeneratedScreen** / griglia — fuori scope; **eccezione mirata:** se si attiva la lista **lite**, possono servire **modifiche locali** a `GeneratedScreen.kt` solo dove usa `historyEntries.find { uid }` (stesso `StateFlow` del ViewModel), senza redesign.
- **TASK-006** (robustezza import Excel generica) — non assorbito salvo emergenza documentata.
- **Indici, migrazioni Room o altre modifiche allo schema** (es. indice su `timestamp`) — **non** entrano automaticamente in TASK-010. Se la profilazione dimostrasse che servono davvero: documentare **eccezione motivata** nel file task **oppure** aprire/riallineare follow-up sotto **TASK-009** (migrazioni safety), per evitare scope creep e rischi sui dati utente.
- Porting 1:1 da iOS.
- Attivazione di altri task in parallelo come `ACTIVE`.
- **Commit di audit 2026-03-28:** nessuna modifica al codice sorgente Android; solo documentazione e tracking → **EXECUTION**.

---

## File potenzialmente coinvolti (Android) — ipotesi per EXECUTION

> Tabella **candidati** per l’implementazione: toccare **DAO / repository / modelli / navigation** solo se il gate e il perimetro del task lo impongono (necessità reale documentata).

| File | Ruolo |
|------|--------|
| `app/.../ui/screens/HistoryScreen.kt` | Filtri UI, date picker custom, stato dialog (`datePickerTargetIsStart`, reset), lista |
| `app/.../viewmodel/ExcelViewModel.kt` | `DateFilter`, `setDateFilter`, `historyEntries`, eventuale orchestrazione “lite + load full by uid” |
| `app/.../data/InventoryRepository.kt` / `DefaultInventoryRepository` | Eventuale `getFilteredHistoryFlow` su proiezione lite + uso esistente di fetch per `uid` |
| `app/.../data/HistoryEntryDao.kt` | Eventuale query lista senza colonne pesanti (solo se gate #2–3 lo impongono) |
| `app/.../data/HistoryEntry.kt` | Eventuale tipo/proiezione “lite” (solo se accettato in scope) |
| `app/.../ui/navigation/NavGraph.kt` | Wiring History → ViewModel; eventuale `loadHistoryEntry` dopo fetch by `uid` se lista lite |
| `app/.../ui/screens/GeneratedScreen.kt` | **Solo se lista lite:** usi di `excelViewModel.historyEntries` / `find { uid }` — possibile bisogno di fetch completo o API VM (percorso già sensibile ai blob) |
| `app/src/main/res/values*/strings.xml` | Etichette filtri / empty state / messaggi UX |
| `app/src/test/.../DefaultInventoryRepositoryTest.kt` | Estensioni test filtri se cambia repository |
| `app/src/test/.../ExcelViewModelTest.kt` | Se cambia orchestrazione osservabile |

---

## Matrice test / scenari (obbligatoria in EXECUTION)

Documentare esito per ogni riga (pass/fail + nota). Dataset di prova con `timestamp` noti nel formato `yyyy-MM-dd HH:mm:ss`.

| # | Scenario | Atteso (alto livello) |
|---|----------|------------------------|
| M1 | Filtro **All** con DB che contiene entry su più mesi | Tutte le entry visibili (ordine DESC coerente con DAO) |
| M2 | **Mese corrente** (`DateFilter.LastMonth` in codice = UI “mese corrente”) — entry il **primo giorno** del mese alle 00:00:00 | Inclusa nel range |
| M3 | **Mese corrente** — entry l’**ultimo giorno** del mese alle 23:59:59 | Inclusa nel range |
| M4 | **Mese precedente** — entry ultimo giorno mese precedente 23:59:59 | Inclusa; non confondere con mese corrente |
| M5 | **Mese precedente** — entry primo giorno mese corrente 00:00:00 | Esclusa dal filtro “mese precedente” |
| M6 | **Custom range** start ≤ end, entrambe le date con entry nel mezzo | Solo entry nel range |
| M7 | **Custom range con data fine precedente alla data inizio** (ordine “invertito” rispetto alla selezione) | Prima di `onSetFilter`, **normalizzare** così: `rangeStart = min(dStart, dEnd)`, `rangeEnd = max(dStart, dEnd)` (con `dStart` / `dEnd` date confermate negli step). Il filtro applicato è sempre con **`rangeStart ≤ rangeEnd`**; risultato **identico** al range calendario [min,max] — **nessun** elenco vuoto dovuto **solo** all’ordine scelto; **nessun** messaggio obbligatorio né biforcazione UX |
| M8 | DB **senza alcuna** cronologia (tabella history vuota) | UX: empty state **distinto** (“nessuna cronologia”) se incluso nel perimetro micro-UX |
| M9 | Cronologia presente ma **nessun risultato** per filtro attivo | UX: empty state **distinto** (“nessun risultato per questo filtro”) se incluso nel perimetro micro-UX |
| M10 | Dopo custom range: dismiss / cancel / conferma finale e **riapertura** da menu | Step 1, `datePickerTargetIsStart = true`, `customStartDate` e `customEndDate` = **oggi** (baseline policy); nessun `onSetFilter` su dismiss/cancel |
| M11 | Navigazione History → tap entry → **Generated**; rename; delete | Nessuna regressione rispetto a oggi |
| M12 | **Snackbar** dopo **rename** e dopo **delete** (messaggi da `historyActionMessage` + `consumeHistoryActionMessage`) | Messaggio visibile come oggi; nessun consumo senza show; non coperto né soppresso da empty state, filtro attivo in top bar o altre micro-UX introdotte nel task |
| M13 | **Solo se lista lite attiva:** dopo export/sync da Generated che tocca `markCurrentEntryAsExported` / `updateSyncStatus`, e dopo rename dalla lista | Verificare su DB (o dump) che **`data`/`editable`/`complete`** della riga **non** siano stati azzerati — **nessuna corruzione** |

**Micro-UX ammessi in TASK-010 (locali, tracciati in Execution):** indicazione **filtro attivo** in top bar (testo secondario / chip compatto); **due empty state** distinti (M8 vs M9) con stringhe dedicate; reset esplicito stato dialog custom range. **Fuori scope:** ridisegno card, spacing globale, gerarchia ampia — **TASK-016**.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Filtri **All / mese corrente / mese precedente / custom** coerenti con `timestamp` persistiti; **matrice M1–M7** eseguita e riportata in Execution (anche se alcune righe N/A motivate) | B + S + M | — |
| 2 | Custom range: **policy dialog** (tabella *Policy UX — dialog custom range*) + **normalizzazione M7** (`min`/`max` su date confermate prima di `DateFilter.CustomRange`); **M7** e **M10** soddisfatte; **nessuna** alternativa (blocco/messaggio) per l’inversione | M (+ S) | — |
| 3 | **Decision gate performance** (sezione Planning) rispettato e **riassunto in Execution**: misurazione → preferenza lite list → full solo su apertura entry → Paging3 solo se lite insufficiente; motivazione scritta se si salta uno step | B + S / M | — |
| 4 | Con lista grande, nessun **ANR** o scroll ingestibile in condizioni documentate; se si introduce lite list, **contratto minimo** + tabella **Consumatori aggiuntivi** + **M13**; **nessuna regressione** su load/rename/update/export/sync | B + M / S | — |
| 5 | `ExcelViewModel` resta fonte di verità del filtro; business logic dei filtri **non** spostata nei composable oltre presentazione/reset UI locale | S | — |
| 6 | **Schema / indici / migrazioni:** nessun cambiamento salvo eccezione motivata nel file task **o** delega esplicita a **TASK-009** | S / review | — |
| 7 | **Nessuna regressione** History → Generated, rename, delete (**M11**) | B + M | — |
| 8 | **Feedback rename/delete (TASK-008):** **M12** — le micro-UX su filtri, empty state o top bar **non** devono rompere `SnackbarHost`, `LaunchedEffect(historyActionMessage)` né l’ordine di `consumeHistoryActionMessage`; verifica manuale documentata in Execution | M | — |
| 9 | Se si toccano `InventoryRepository` / `ExcelViewModel` per history: baseline **TASK-004** (`DefaultInventoryRepositoryTest`, `ExcelViewModelTest` minimo; `./gradlew test` se incerto) documentata | B / S | — |
| 10 | Build Gradle OK; lint senza nuovi warning **nel perimetro modificato** | B / S | — |

**Definition of Done UX (da MASTER-PLAN):** applicare checklist UX dove si tocca UI; micro-UX sopra elencate restano in TASK-010; polish ampio → TASK-016.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **Policy UX custom range — date provvisorie:** all’apertura `customStartDate = customEndDate = LocalDate.now()`; dopo conferma start, allo step end `customEndDate` = data inizio **appena confermata** (stesso giorno); dismiss / cancel / conferma finale → reset completo a step 1 con stato provvisorio **pulito** (`datePickerTargetIsStart = true`, entrambe le date provvisorie di nuovo = **oggi**). **Nessuna variante alternativa** — implementazione in EXECUTION deve rispettare solo questa tabella. | Chiusura decisione in **PLANNING**; UX deterministica senza biforcazioni | 2026-03-28 |
| 2 | **M7 — ordine date in custom range:** se la data fine confermata è **precedente** alla data inizio confermata, **normalizzare sempre** con `rangeStart = min(dStart, dEnd)` e `rangeEnd = max(dStart, dEnd)` prima di costruire `DateFilter.CustomRange` / chiamare `onSetFilter`. Filtro sempre con **start ≤ end**; nessun vuoto ingannevole per solo inversione; **nessun** blocco + messaggio obbligatorio; **nessuna** scelta rimandata all’execution. | Chiusura in **PLANNING**; allineato a repository (range SQL su stringhe `yyyy-MM-dd HH:mm:ss`) | 2026-03-28 |
| 3 | **Lista lite (se gate #2):** proiezione che può omettere `data` / `editable` / (se possibile) `complete`, ma **deve** includere almeno: `uid`, `id`, `timestamp`, `supplier`, `category`, `totalItems`, `orderTotal`, `paymentTotal`, `missingItems`, `syncStatus`, `wasExported`, `isManualEntry` — vedi tabella *Contratto minimo*. **Ogni** `updateHistoryEntry` da riga lista richiede riga completa da `getHistoryEntryByUid` (o refactor equivalente) — tabella *Consumatori aggiuntivi*. | Evita regressioni UI e **corruzione DB** | 2026-03-28 |
| 4 | **EXECUTION (tracking):** avvio fase **EXECUTION** dopo audit planning↔codice 2026-03-28; **nessun** commit di codice applicativo nello stesso intervento. | Governance | 2026-03-28 |

---

## Planning (Claude) — 2026-03-28 (revisione integrata)

### Distinzione richiesta

#### Problemi / osservazioni **già visibili nel codice**

1. **Payload lista pesante:** `HistoryEntry` include `data: List<List<String>>` e `editable`; le query DAO usano `SELECT *`. La lista UI mostra principalmente metadati, ma il Flow carica l’intera griglia per **ogni** entry nel range — rischio principale di lentezza e memoria con molte cronologie / fogli grandi.
2. **Intervallo custom senza normalizzazione (codice attuale):** oggi, se l’utente conferma una fine **precedente** all’inizio, la query può restituire **vuoto** senza che sia chiaro il motivo. **Policy M7 (chiusa):** in execution si applica sempre **normalizzazione `min`/`max`** prima del filtro — vedi **Decisioni #2** e tabella policy sotto.
3. **Stato dialog custom range (`datePickerTargetIsStart`):** oggi la flag passa a `false` nello step end ma **non** c’è reset esplicito su dismiss/cancel/riapertura — rischio di ripartire sul passo “fine” o con date provvisorie stale. **Policy desiderata:** fissata nella sottosezione **Policy UX — dialog custom range** sotto (implementazione in EXECUTION).
4. **Naming fuorviante:** il sealed object `DateFilter.LastMonth` implementa il **mese corrente** (coerente con `R.string.filter_current_month`), non “mese scorso” in senso letterale — fonte di confusione per manutenzione e review.
5. **UX filtro attivo (miglioramento micro, opzionale ma raccomandato nel perimetro):** dopo la scelta, la top bar non indica quale filtro è applicato (solo icona generica).
6. **Empty state (miglioramento micro, opzionale ma raccomandato nel perimetro):** nessun messaggio dedicato per lista vuota globale vs vuoto per filtro — da distinguere se si interviene in UX (M8/M9).

#### Policy UX — dialog custom range (**decisione chiusa in PLANNING**; implementazione in EXECUTION)

Comportamento **unico** obbligatorio; **nessuna** alternativa o scelta rimandata all’execution salvo bugfix. Baseline provvisoria dopo ogni reset: **step 1**, `customStartDate = LocalDate.now()`, `customEndDate = LocalDate.now()` («oggi» = data di sistema al momento del reset).

| Momento | Comportamento atteso |
|---------|----------------------|
| **Apertura flusso** (tap menu → intervallo personalizzato / `filter_custom_range`) | Sempre **step 1** (scelta **data inizio**): `datePickerTargetIsStart = true`. **`customStartDate = oggi`** e **`customEndDate = oggi`** (`LocalDate.now()`). **Non** invocare `onSetFilter` solo per l’apertura. |
| **Dismiss** (tap fuori, back di sistema, `onDismissRequest`) | Chiudere il dialog **senza** `onSetFilter`. **Reset completo del wizard:** `datePickerTargetIsStart = true`; stato provvisorio **pulito**: `customStartDate` e `customEndDate` entrambe = **oggi** (stessa baseline dell’apertura). |
| **Cancel** (pulsante Annulla / `dismissButton`) | **Identico al dismiss:** nessun cambio filtro DB; reset completo a step 1 + date provvisorie = **oggi**. |
| **Dopo conferma data inizio** (tap «Avanti» / `Next` con data valida) | Persistere la data inizio scelta in `customStartDate`. Impostare `datePickerTargetIsStart = false`. Impostare **`customEndDate = customStartDate`** (data fine preimpostata **uguale** alla start appena confermata). **Restare nel dialog** sullo step **data fine** (titolo + `DatePicker` coerenti con `customEndDate`). |
| **Dopo conferma data fine** (tap «Conferma» con data valida) | Siano `dStart` = data inizio confermata (step 1) e `dEnd` = data fine appena confermata (step 2). Calcolare **`rangeStart = min(dStart, dEnd)`** e **`rangeEnd = max(dStart, dEnd)`** (normalizzazione **obbligatoria**; **Decisioni #2** / **M7**). Chiamare **`onSetFilter(DateFilter.CustomRange(rangeStart, rangeEnd))`** — il repository continua a usare 00:00 su `rangeStart` e 23:59:59 su `rangeEnd`. **Nessun** messaggio obbligatorio se `dEnd < dStart`; **nessuna** biforcazione. Chiudere il dialog. Poi **reset completo del wizard** come dismiss/cancel: **step 1**, `datePickerTargetIsStart = true`, `customStartDate = customEndDate = oggi`. |
| **Nuova apertura da menu dopo qualsiasi chiusura** | **Indistinguibile** dalla prima apertura: stessa riga «Apertura flusso». |

**M7** verifica esplicitamente la normalizzazione (stesso risultato del range calendario [min, max]). **M10** verifica dismiss, cancel, conferma finale e riapertura rispetto a questa tabella (incluso che dopo reset le date provvisorie siano di nuovo **oggi** e lo step sia **1**).

#### Ipotesi da **verificare in EXECUTION** (misurazione / device)

- **A.** Il collo di bottiglia dominante è **I/O Room + deserializzazione** delle liste annidate rispetto al costo Compose.
- **B.** `SharingStarted.Lazily` su `historyEntries` influisce sul primo ingresso in History — valutare solo se misurato.
- **C.** Dopo implementazione “lite list”, il caricamento completo via **`getHistoryEntryByUid` / `loadHistoryEntry`** resta sufficiente per Generated senza ulteriori ottimizzazioni.

**Nota indici / schema:** non più ipotesi “da provare dentro TASK-010” in autonomia — vedi **Guardrail schema** sotto.

#### Guardrail schema / indice `timestamp`

- **TASK-010 non include** per default: creazione indici, modifiche a `AppDatabase`, version bump, migrazioni.
- Se la profilazione post-lite indicasse comunque bisogno di indice: **(i)** documentare nel file task **eccezione motivata** con evidenza, **oppure** **(ii)** spostare la modifica schema sotto **TASK-009** (o task dedicato migrazioni) con dipendenze chiare — **mai** come aggiunta implicita al solo “fix performance History”.

#### Decision gate performance (ordine vincolante)

Strategia preferita perché **meno invasiva di Paging3** quando basta ridurre il payload: il repository/DAO espone già fetch per **singola** entry (`uid`).

1. **Misurazione** — Riprodurre N entry (o dataset realistico) e osservare: tempo apertura History, cambio filtro, allocazioni; identificare se il costo è dominato da colonne `data`/`editable`.
2. **Se il problema è il payload in lista** — Preferire **query / tipo proiezione “lite”** rispettando il **contratto minimo** nella sottosezione seguente (nessuna proiezione più aggressiva che ometta campi richiesti dalla riga History / navigazione), mantenendo il filtro data lato SQL come oggi. Il ViewModel o il repository combinano Flow lite + eventuale refresh.
3. **Caricamento completo / integrità update** — Prima di `loadHistoryEntry`, **rename**, e **ogni** `updateHistoryEntry` scaturito da riga lista (export/sync badge inclusi), garantire riga **completa** da DB (`getHistoryEntryByUid`) se il Flow è lite — vedi tabella *Consumatori aggiuntivi*.
4. **Paging3** — Valutare **solo se** la lista lite resta ingestibile (memoria / tempo su range enormi) **dopo** i passi 1–3, con motivazione scritta; Paging3 resta l’opzione più costosa in complessità e test.

#### Contratto minimo — lista / query **«lite»** (solo se si attiva il gate #2)

Obiettivo: alleggerire **I/O e deserializzazione** senza rompere **History row**, badge o riepilogo, né il wiring **NavGraph → Generated**.

**Payload tipicamente escludibile dalla sola riga mostrata in `HistoryRow`:** `data`, `editable`, e **se la proiezione Room lo consente** anche `complete` — non letti dalla riga lista. **Attenzione (codice reale verificato):** lo **stesso** `StateFlow` `historyEntries` alimenta anche percorsi che oggi assumono un **`HistoryEntry` completo**; vedi sottosezione **Consumatori aggiuntivi** — **non** si possono lasciare blob vuoti in quelle code path senza refactor.

**Campi che la lista «lite» deve preservare** (minimo contratto; allineato a `HistoryScreen.kt` / `HistoryRow` e a `NavGraph` per `onSelect`):

| Campo | Motivo |
|-------|--------|
| `uid` | Chiave `LazyColumn`, rename/delete, navigazione `entryUid` |
| `id` | Titolo riga, rename dialog |
| `timestamp` | Riga data |
| `supplier`, `category` | Dettaglio riga |
| `totalItems`, `orderTotal`, `paymentTotal`, `missingItems` | Blocco riepilogo (se `totalItems > 0`) |
| `syncStatus`, `wasExported` | Badge sync / export |
| `isManualEntry` | Icona manuale in riga **e** argomento navigazione `isManualEntry` verso Generated |

Se in execution si scopre **altro** campo letto dal composable lista o dal callback `onSelect` / `onRename` / `onDelete`, va **aggiunto** al contratto prima di ometterlo dalla proiezione. **Vietato** ridurre la lite sotto questo minimo senza aggiornare la UI o il wiring in modo equivalente.

##### Consumatori aggiuntivi di `historyEntries` (audit codice — coerenza gate lite)

Oltre a `HistoryScreen`, il **medesimo** flusso `ExcelViewModel.historyEntries` è usato così (file `ExcelViewModel.kt` / `GeneratedScreen.kt`):

| Percorso | Comportamento attuale | Implicazione per lista **lite** |
|----------|----------------------|-----------------------------------|
| `loadHistoryEntry(entry)` | Legge `entry.data`, `editable`, `complete` e popola la griglia | **Obbligatorio** caricare riga completa via `getHistoryEntryByUid(entry.uid)` (o equivalente) **prima** di `populateStateFromEntry`, se la lista è lite |
| `renameHistoryEntry(entry, …)` | `entry.copy(id, supplier, category)` → `updateHistoryEntry(updated)` | Room `@Update` persiste **tutta** l’entity; una lite senza blob farebbe **sovrascrivere `data`/`editable`/`complete` con valori vuoti** → **corruzione dati**. **Obbligatorio:** fetch completo by `uid` prima dell’update, oppure refactor dedicato nello stesso task |
| `deleteHistoryEntry(entry)` | `repository.delete(entry)` | Room `@Delete` usa in pratica la PK; rischio medio-basso, ma **consigliato** allineare a fetch by `uid` per coerenza |
| `markCurrentEntryAsExported(entryUid)` | `historyEntries.value.find { uid }` → `entry.copy(wasExported = true)` → `updateHistoryEntry` | Stesso rischio di **corruzione** se `entry` è lite |
| `updateSyncStatus` (privata) | `find` → `copy(syncStatus)` → `updateHistoryEntry` | Stesso rischio |
| `GeneratedScreen` | Più `historyEntries.find { it.uid == entryUid }` per delete/rename/load | Se il risultato è lite e viene passato a VM come oggi, **stessi rischi** |

**Obbligo di execution (se gate #2):** ogni `updateHistoryEntry` che oggi parte da una riga presa da `historyEntries` deve ricevere un’entity **completa** (tipicamente **`getHistoryEntryByUid`** subito prima del `copy`/`update`), **oppure** il task introduce un refactor esplicito e testato (es. update parziale SQL — fuori scope implicito, da evitare salvo necessità documentata). **M13** (sotto) copre la regressione.

#### Miglioramenti (classificazione)

| Tipo | Voce |
|------|------|
| **Necessari (post-verifica)** | Implementare la **policy UX dialog** (tabella); **normalizzazione M7** (`min`/`max`) prima di ogni `CustomRange`; se lista lite: **contratto minimo** colonne; riepilogo **decision gate** in Execution. |
| **Micro-UX in TASK-010** | Filtro attivo visibile; empty state **distinti** (nessuna cronologia vs nessun risultato filtro); stringhe in `strings.xml`. |
| **Opzionale tecnico** | KDoc/commento su `LastMonth` vs “current month”; tuning `SharingStarted` solo se misurato. |
| **Solo con necessità reale + scope esplicito** | Nuove query DAO / entity lite / Paging3 — citate solo come **opzioni** del gate; nessun impegno in PLANNING. |

### Obiettivo UX/UI

- Filtri data **comprensibili e prevedibili**; custom range con stato dialog **deterministico**; nessun vuoto **ingannevole** per solo ordine start/end (**M7** = normalizzazione silenziosa accettata in planning).
- Micro-UX: **filtro attivo** + **empty state** distinti (M8/M9), senza redesign.

### Obiettivo tecnico

- Applicare il **decision gate** sopra; preferire **lite + full on demand** rispetto a Paging3 quando sufficiente.
- `ViewModel` resta orchestratore del filtro e del flusso dati verso la UI.

### Rischi / regressioni

- **Alta:** Paging3 o nuovi tipi senza test su rename/delete/open by `uid`.
- **Alta (lista lite):** `updateHistoryEntry` con entity lite (`data`/`editable`/`complete` vuoti) — **corruzione irreversibile** della cronologia in Room; percorsi: `renameHistoryEntry`, `markCurrentEntryAsExported`, `updateSyncStatus`. **Mitigazione:** fetch `getHistoryEntryByUid` prima di ogni update da lista, o non attivare lite senza refactor completo documentato.
- **Media:** DAO lite + dimenticanza di un call site che assume sempre `data`/`editable` in lista; oppure proiezione che omette un campo del **contratto minimo** (badge, `isManualEntry`, riepilogo).
- **Media (feedback utente):** estensioni a `Scaffold`, top bar, contenuto centrale (empty state) o z-order che **rimuovono** `SnackbarHost`, spostano `LaunchedEffect(historyActionMessage)`, o fanno **consumare** il messaggio senza mostrare lo snackbar — regressioni su rename/delete rispetto a TASK-008. **Mitigazione:** rispettare **M12** e riusare il pattern attuale (stesso host, stesso flusso `historyActionMessage` → show → `onHistoryActionMessageConsumed`).
- **Bassa:** altre regressioni wiring History se si tocca troppo il composable radice.
- **Bassa (M7):** normalizzazione **senza** messaggio esplicito: l’utente potrebbe non accorgersi che il range applicato è il calendario [min,max]; accettato per **PLANNING**; eventuale hint copy in top bar/filtro attivo resta **opzionale** e locale, fuori obbligo.
- **Coordinamento:** **TASK-016** — non duplicare polish strutturale della History.

### Riferimento iOS

- Repository iOS **non presente** in questo workspace; eventuale confronto solo su clone separato, **solo** tono/gerarchia, senza porting 1:1.

### Check / test previsti (solo dopo passaggio a EXECUTION)

- Matrice **M1–M13** (sopra); **M13** obbligatoria se attivata lista lite.
- `./gradlew assembleDebug`, `./gradlew lint` (perimetro modifiche).
- Baseline TASK-004 se toccati repository/ViewModel.
- Opzionale: Android Studio Profiler per evidenza gate #1.

### Confini del task

- **EXECUTION (fase attuale):** implementazione codice applicativo **non ancora iniziata** nell’intervento di audit 2026-03-28 (solo docs + tracking).
- **Perimetro implementazione:** History list, filtri, performance percorso history, micro-UX elencate; niente redesign; niente migrazioni schema salvo guardrail; niente porting iOS; eventuali tocchi **minimi** a `GeneratedScreen` / VM solo se necessari per coerenza lista lite (vedi *Consumatori aggiuntivi*).

### Piano di esecuzione (progressivo — da attivare solo post-approvazione)

1. Eseguire **matrice M1–M13** (o sottoinsieme + N/A motivati) su build di prova.
2. **Misurazione** (gate **#1**): confermare o smentire dominanza payload `data`/`editable`.
3. Se necessario, implementare **lista lite** (gate **#2**) + adeguare **tutti** i consumatori in tabella *Consumatori aggiuntivi* (gate **#3**); documentare diff e file toccati (incluso eventuale `GeneratedScreen.kt`).
4. Solo se insufficente: proporre **Paging3** (gate **#4**) con approvazione scope esplicita.
5. Chiudere **custom range**: **normalizzazione M7** (`min`/`max`), reset dialog (policy tabella), **M10**.
6. Micro-UX (filtro attivo, empty M8/M9) se ancora mancanti.
7. Test JVM aggiornati se cambia repository; baseline TASK-004.
8. **Handoff** per **TASK-016** (resti puramente estetici).

---

## Execution

### Esecuzione — 2026-03-28 (avvio tracking EXECUTION, audit planning ↔ codice)

**Contesto:** chiusura planning con verifica mirata sui file TASK-010; **nessuna modifica** ai sorgenti Android in questo intervento.

**File modificati (solo documentazione):**
- `docs/TASKS/TASK-010-history-screen-filtri-e-performance.md` — integrazione *Consumatori aggiuntivi* `historyEntries`; **M13**; gate #3 riformulato; rischio corruzione DB; `GeneratedScreen` / `NavGraph` in tabella file; fase **EXECUTION**.
- `docs/MASTER-PLAN.md` — task attivo TASK-010, fase **EXECUTION**, prossimo passo operativo.

**Azioni eseguite:**
1. Letti e incrociati: `HistoryScreen.kt`, `ExcelViewModel.kt` (history, rename/delete/load, markExported, updateSyncStatus), `InventoryRepository.kt` / `getFilteredHistoryFlow`, `HistoryEntryDao.kt`, `HistoryEntry.kt`, `NavGraph.kt` (History); individuato gap lite vs `updateHistoryEntry`.
2. Aggiornato planning e tracking; transizione **PLANNING → EXECUTION** senza implementazione codice.

**Check obbligatori (AGENTS.md):** N/A per questo sotto-intervento (solo markdown); la prossima sessione di implementazione dovrà eseguire build/lint/test come da task.

**Baseline TASK-004:** da eseguire quando si modificano repository/ViewModel.

**Incertezze:** nessuna sul gap lite+update; comportamento Room `@Delete` con entity parziale non approfondito oltre la raccomandazione conservativa.

**Handoff notes:** prima riga di implementazione consigliata — misurazione gate #1; poi dialog custom range + M7; poi valutare lite con fetch by `uid` su **tutti** i percorsi in tabella *Consumatori aggiuntivi*.

### Esecuzione — 2026-03-29

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntry.kt` — introdotto `HistoryEntryListItem` con il contratto minimo della riga History.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntryDao.kt` — aggiunte query lite per la lista History e `hasEntriesFlow()` per distinguere gli empty state.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — aggiunti `getFilteredHistoryListFlow()` e `hasHistoryEntriesFlow()`; centralizzata la costruzione del range data.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — separati `historyListEntries` / `hasHistoryEntries` / `dateFilter`; fetch completo per `uid` su load/rename/delete/export/sync; aggiunto `currentEntryName`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — History ora consuma la lite list e apre Generated solo dopo fetch completo per `uid`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — custom range robusto (reset wizard + normalizzazione `min`/`max`), filtro attivo visibile, empty state distinti, lista su payload lite.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — rimossi i lookup sulla lista completa per title/delete/reload/rename; uso diretto delle API per `uid`.
- `app/src/main/res/values/strings.xml` — testi IT per filtro attivo e empty state distinti.
- `app/src/main/res/values-en/strings.xml` — testi EN per filtro attivo e empty state distinti.
- `app/src/main/res/values-es/strings.xml` — testi ES per filtro attivo e empty state distinti.
- `app/src/main/res/values-zh/strings.xml` — testi ZH per filtro attivo e empty state distinti.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — estesi i test su filtri data, flow lite e presenza cronologia.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — estesi i test su fetch completo per `uid` in rename/load/export/sync.

**Azioni eseguite:**
1. **Decision gate #1 — misurazione minima / verifica:** verificato il percorso reale `HistoryScreen -> ExcelViewModel -> Room`; la lista usava `SELECT *` su `HistoryEntry` con payload serializzato `data` / `editable` / `complete`, mentre la UI visualizza solo metadati. Questo è stato considerato il collo di bottiglia principale del path list.
2. **Decision gate #2 — lite list:** applicata una proiezione dedicata `HistoryEntryListItem` per la sola schermata History, evitando Paging3 e senza toccare schema/migrazioni.
3. **Decision gate #3 — full on demand:** tutti i punti sensibili già mappati nel planning (`loadHistoryEntry`, rename/delete da History, `markCurrentEntryAsExported`, `updateSyncStatus`, consumer locali di Generated) ora recuperano la riga completa via `getHistoryEntryByUid(uid)` prima di usare `updateHistoryEntry`, evitando corruzione dei blob history.
4. **Custom range / robustezza filtro:** il wizard custom range ora si apre sempre da step 1 con start/end = oggi, resetta correttamente su dismiss/cancel/conferma finale e normalizza sempre `rangeStart = min(...)`, `rangeEnd = max(...)` prima di applicare `DateFilter.CustomRange`.
5. **Micro-UX locali:** aggiunta indicazione del filtro attivo in top bar e distinti gli empty state “nessuna cronologia” vs “nessun risultato”. Motivo: chiarezza e coerenza locale senza redesign.
6. **Generated / wiring:** rimosso il vincolo ai lookup sulla lista completa dentro Generated per title/delete/reload/rename; il titolo corrente viene mantenuto dal ViewModel e le azioni lavorano per `uid`.
7. **Baseline TASK-004:** eseguita la suite mirata repository + ViewModel dopo le modifiche a History / ExcelViewModel / repository.

**Matrice M1–M13:**
| Scenario | Stato | Evidenza |
|----------|-------|----------|
| M1 | ✅ ESEGUITO | Test `getFilteredHistoryFlow all returns every entry ordered descending` |
| M2 | ✅ ESEGUITO | Test `getFilteredHistoryFlow current month includes both month boundaries` (primo giorno 00:00:00 incluso) |
| M3 | ✅ ESEGUITO | Stesso test M2 (ultimo giorno 23:59:59 incluso) |
| M4 | ✅ ESEGUITO | Test `getFilteredHistoryFlow previous month keeps previous boundary and excludes current month start` (ultimo giorno mese precedente incluso) |
| M5 | ✅ ESEGUITO | Stesso test M4 (primo giorno mese corrente escluso) |
| M6 | ✅ ESEGUITO | Test `getFilteredHistoryFlow respects custom date range` |
| M7 | ✅ ESEGUITO | Verifica statica in `HistoryScreen.kt`: conferma finale del range usa sempre `minOf(customStartDate, selectedDate)` / `maxOf(...)` |
| M8 | ✅ ESEGUITO | Verifica statica: `hasHistoryEntries == false` mostra `history_empty_title` / `history_empty_message` |
| M9 | ✅ ESEGUITO | Verifica statica: lista vuota con cronologia presente mostra `history_filtered_empty_title` / `history_filtered_empty_message` |
| M10 | ✅ ESEGUITO | Verifica statica: `resetCustomRangeDraft()` + `dismissCustomRangePicker()` riportano sempre lo step a start con date = oggi e senza `onSetFilter` su dismiss/cancel |
| M11 | ⚠️ NON ESEGUIBILE | Smoke manuale History → Generated → rename/delete non eseguito su emulator/device in questa sessione; wiring statico aggiornato e build/test verdi |
| M12 | ⚠️ NON ESEGUIBILE | Verifica manuale snackbar rename/delete non eseguita su emulator/device; il `SnackbarHost` e il flusso `historyActionMessage -> showSnackbar -> consumeHistoryActionMessage()` non sono stati rimossi |
| M13 | ✅ ESEGUITO | Test `renameHistoryEntry by uid fetches full entry before update`, `markCurrentEntryAsExported fetches full entry before update`, `markCurrentEntryAsSyncedSuccessfully fetches full entry before update` |

**Criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | Matrice M1–M7 riportata sopra; boundary test aggiunti per mese corrente/precedente, custom range verificato |
| 2 | ✅ ESEGUITO | Policy dialog applicata in `HistoryScreen.kt`; M7 + M10 soddisfatte con reset wizard e normalizzazione silenziosa `min`/`max` |
| 3 | ✅ ESEGUITO | Gate rispettato e documentato: audit payload -> lite list -> fetch completo per `uid`; Paging3 non introdotto perché non necessario |
| 4 | ⚠️ NON ESEGUIBILE | Contratto lite + M13 coperti con test; assenza di ANR/scroll ingestibile non verificata manualmente su lista grande in questa sessione |
| 5 | ✅ ESEGUITO | `ExcelViewModel` resta fonte di verità di filtro/stato; la UI gestisce solo presentazione e reset locale del dialog |
| 6 | ✅ ESEGUITO | Nessuna modifica a schema, indici o migrazioni |
| 7 | ⚠️ NON ESEGUIBILE | Nessuno smoke manuale completo History → Generated in questa sessione; wiring aggiornato e build/test verdi |
| 8 | ⚠️ NON ESEGUIBILE | Nessuno smoke manuale snackbar; il pattern TASK-008 è stato preservato senza spostare host o consumo messaggio |
| 9 | ✅ ESEGUITO | Baseline TASK-004 eseguita: `DefaultInventoryRepositoryTest` + `ExcelViewModelTest` |
| 10 | ✅ ESEGUITO | `assembleDebug` verde; `lint` verde con soli warning pre-esistenti fuori perimetro |

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `assembleDebug` verde (`BUILD SUCCESSFUL in 5s`) con `JAVA_HOME` puntato al JBR di Android Studio perché la shell non esponeva un JRE di default |
| Lint                     | ✅ ESEGUITO | `lint` verde (`BUILD SUCCESSFUL in 33s`); report `0 errors, 57 warnings` pre-esistenti fuori scope |
| Warning nuovi            | ✅ ESEGUITO | Nessun nuovo warning Kotlin introdotto nei file modificati; restano warning globali AGP/Gradle e la deprecazione Compose già pre-esistente su `rememberSwipeToDismissBoxState` |
| Coerenza con planning    | ✅ ESEGUITO | Eseguiti i fix a basso rischio richiesti, seguito il gate performance, evitato Paging3 e nessuna modifica a schema/migrazioni |
| Criteri di accettazione  | ⚠️ NON ESEGUIBILE | Residui solo i punti manuali M11/M12 e la verifica runtime “lista grande” del criterio 4; tutto il resto è verificato con build/static/test |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `:app:testDebugUnitTest --tests com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest --tests com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest`
- Esito: `DefaultInventoryRepositoryTest` = **13/13** verdi; `ExcelViewModelTest` = **18/18** verdi
- Test aggiunti/aggiornati: boundary test M1–M5; test su flow lite / `hasHistoryEntriesFlow`; test VM su fetch completo per `uid` in rename/load/export/sync
- Limiti residui: nessun test manuale UI/snackbar eseguito; nessuna prova device su dataset molto grande in questa sessione

**Incertezze:**
- Nessuna sul perimetro implementato.
- Residuo intenzionale: la verifica “scroll ingestibile / ANR” su dataset molto grande resta da chiudere in review manuale, non in build JVM.

**Handoff notes:**
- Task tecnicamente pronto per REVIEW: concentrarsi su smoke manuale rapido di `M11` e `M12` (History → Generated, rename, delete, snackbar, filtro attivo, empty state).
- Se in review emergono ancora lag con dataset reali molto grandi, il prossimo step va misurato sul dispositivo prima di valutare altro oltre la lite list; Paging3 resta **non necessario** allo stato attuale.
- Tracking globale (`docs/MASTER-PLAN.md`) non aggiornato in questo intervento per rispetto del ruolo esecutore; il task file contiene ora l’handoff completo per il passaggio successivo.

---

## Review

### Review — 2026-03-29

**Esito:** APPROVATA con fix mirati applicati nello stesso intervento di review.

**Verifiche review eseguite:**
1. Controllato il wiring completo lite list → fetch full by `uid` su DAO, repository, ViewModel, `NavGraph` e `GeneratedScreen`.
2. Ricontrollata la policy del custom range in `HistoryScreen.kt` contro la tabella decisionale del task.
3. Verificati i test aggiunti su repository / ViewModel, con particolare attenzione a M13 e ai boundary dei filtri mese.
4. Rieseguiti `:app:testDebugUnitTest` mirati, `assembleDebug` e `lint` dopo i fix di review.

**Finding corretti in review:**
- Il path `generateFilteredWithOldPrices` non inizializzava `currentEntryName`, quindi `GeneratedScreen` poteva mostrare titolo vuoto o stale sulle nuove entry generate.
- `GeneratedScreen` aggiornava il `titleText` in modo ottimistico dopo il rename prima della conferma del ViewModel, con rischio di UI incoerente in caso di errore.
- La top bar di History mostrava la stringa “filtro attivo” anche con `DateFilter.All`, risultando meno coerente del necessario rispetto al micro-obiettivo UX del task.

---

## Fix

### Fix — 2026-03-29

**Correzioni applicate in review:**
1. `ExcelViewModel.applyGeneratedState(...)` ora riceve e imposta anche `entryName`, così `currentEntryName` è corretto anche per le nuove entry generate da PreGenerate.
2. `GeneratedScreen.kt` non forza più `titleText = renameText` dopo il rename: il titolo visualizzato torna a dipendere dal dato confermato nel ViewModel.
3. `HistoryScreen.kt` mostra il testo del filtro in top bar solo quando il filtro è davvero attivo (`DateFilter` diverso da `All`).
4. `ExcelViewModelTest.kt` esteso con asserzioni su `currentEntryName` nei path di generate/load.

**Check post-fix:**
- `:app:testDebugUnitTest --tests com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest --tests com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest` ✅
- `./gradlew assembleDebug` ✅
- `./gradlew lint` ✅

---

## Chiusura

| Campo                  | Valore |
|------------------------|--------|
| Stato finale           | **DONE** |
| Data chiusura          | **2026-03-29** |
| Tutti i criteri ✅?    | **Sì, nel contratto finale** — criteri tecnici eseguiti; criteri manuali rimasti `NON ESEGUIBILE` con motivazione documentata e non bloccante per la chiusura |
| Rischi residui         | Nessun rischio tecnico aperto nel perimetro del task; smoke manuale consigliata su device/emulator per History → Generated, snackbar e percezione scroll su dataset grande |

---

## Riepilogo finale

- Il planning di TASK-010 è stato rispettato: gate performance seguito, niente Paging3, niente modifiche a schema/migrazioni, ViewModel fonte di verità preservata.
- La lite list è stata implementata con il taglio più semplice e robusto per il codice reale: query dedicata per la lista History e fetch completo per `uid` in tutti i percorsi che aggiornano/aprono una entry.
- Review finale: approvata con 3 fix mirati e locali emersi durante il controllo del wiring e della UX.
- Build, lint e baseline TASK-004 mirata sono verdi al termine della review.

---

## Handoff

- Task **`DONE`** (2026-03-29).
- Nessun nuovo task viene attivato automaticamente da questo file.
- Smoke manuale consigliata, non bloccante per la chiusura:
  - filtri All / mese corrente / mese precedente
  - custom range normale e invertito
  - dismiss / cancel / riapertura custom range
  - empty state distinti
  - rename / delete / snackbar
  - History → Generated
  - export / share / sync badge su entry esistenti e manuali
