# TASK-044 — History: cronologia utente senza entry tecniche `APPLY_IMPORT_*`

---

## Informazioni generali

| Campo                 | Valore |
|-----------------------|--------|
| ID                    | TASK-044 |
| Stato                 | **DONE** |
| Priorità              | **MEDIA** |
| Area                  | UX / History / Database import apply |
| Creato                | 2026-04-11 |
| Ultimo aggiornamento  | 2026-04-11 (review planner APPROVED; task portato a `DONE`) |

---

## Dipendenze

- Nessuna bloccante. Ortogonale a **TASK-041** (GeneratedScreen); non richiede chiusura di altri task.

---

## Scopo

Rimuovere dalla **cronologia visibile** in `HistoryScreen` le voci tecniche `APPLY_IMPORT_<timestamp>` create dopo apply import dal flusso preview/analisi (es. da `GeneratedScreen` → sync → apply). La cronologia deve restare una lista di **fogli / generated sheet** riapribili, non un log misto di operazioni interne.

Obiettivo secondario di qualità: rendere il contratto semantico della cronologia **più chiaro e più resistente nel tempo**, così futuri flussi tecnici non ricomincino a “sporcare” la lista utente con record interni nati per debugging o audit.

---

## Contesto

- Segnalazione prodotto: dopo sincronizzazione/apply import compaiono righe con id tipo `APPLY_IMPORT_…`, confuse e ridondanti rispetto alle vere history dei file generati.
- La tabella Room `history_entries` è oggi usata sia per **sheet utente** sia per **log strutturati** creati da `DatabaseViewModel` tramite `startHistoryLog` / `appendHistoryLog`.

---

## Non incluso

- Redesign di `HistoryScreen` (eventuale **TASK-036** resta separato).
- Modifiche al comportamento funzionale di `applyImport` / transazioni Room / preview import.
- Nuove dipendenze o schermate “diagnostica” dedicate salvo decisione esplicita successiva.
- Nessuna migrazione Room o nuovo campo persistente solo per distinguere log tecnici vs entry utente in questo task.
- Nessuna modifica al layout di dettaglio della riga History oltre a eventuali empty state / microcopy coerenti con il filtro.
- Nessuna pulizia distruttiva automatica dei record legacy tecnici dal database salvo esplicita necessità futura.
- Nessun filtro “euristico” basato su testo visuale, supplier/category placeholder o struttura della riga: il criterio deve essere esplicito, deterministico e fondato sull’identificativo tecnico.
- Nessuna duplicazione di query “quasi uguali” solo per inseguire il filtro: in execution va privilegiata una struttura chiara, leggibile e facile da estendere.
- Nessun filtro finale solo in memoria dopo aver già caricato dal DB tutta la history: la distinzione deve avvenire il più vicino possibile alla sorgente dati usata dalla cronologia utente.
- **Non** si intende cambiare il comportamento di apertura/caricamento di una **vera** history entry (foglio utente): rename, delete, reopen e flusso verso `GeneratedScreen` restano quelli attuali. L’unica variazione attesa sul percorso standard è che i **record tecnici** non siano più **raggiungibili** dalla cronologia utente (né in lista né tramite le stesse affordance usate oggi per i fogli).

---

## Analisi repo-grounded (comportamento attuale)

### 1) Dove nasce `APPLY_IMPORT_*`

In `DatabaseViewModel.importProducts` (apply dopo preview valido), all’inizio del `viewModelScope.launch`:

- `val applyLogUid = startHistoryLog("APPLY_IMPORT", "Applico import: …")`
- Su esito: `appendHistoryLog(applyLogUid, "SUCCESS" | "FAILED", …)`

`startHistoryLog` (sempre in `DatabaseViewModel`) inserisce una `HistoryEntry` con:

```kotlin
id = "${kind}_${System.currentTimeMillis()}"
```

Quindi per `kind = "APPLY_IMPORT"` l’`id` mostrato in lista è letteralmente `APPLY_IMPORT_<millis>`.

Riferimento codice:

```139:161:app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt
    private suspend fun startHistoryLog(kind: String, message: String): Long =
        withContext(Dispatchers.IO) {
            repository.insertHistoryEntry(
                HistoryEntry(
                    id = "${kind}_${System.currentTimeMillis()}", // etichetta generica, la chiave reale è 'uid' autogenerato
                    timestamp = LocalDateTime.now().format(tsFmt),
                    data = listOf(
                        listOf("status", "message"),
                        listOf("STARTED", message)
                    ),
                    // ...
                )
            )
        }
```

```683:701:app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt
        viewModelScope.launch {
            val applyLogUid = startHistoryLog(
                "APPLY_IMPORT",
                "Applico import: new=${newProducts.size}, updated=${updatedProducts.size}"
            )
            Log.d("DB_IMPORT", "APPLY_IMPORT START uid=$applyLogUid")
            // … applyImport … appendHistoryLog(applyLogUid, "SUCCESS", …)
```

### 2) Persistenza

- Entity: `HistoryEntry` → tabella `history_entries` (`HistoryEntry.kt`, `AppDatabase`).
- Inserimento/update: `InventoryRepository.insertHistoryEntry` / `updateHistoryEntry` → `HistoryEntryDao.insert` / `update`.

### 3) Lettura e UI (`HistoryScreen`)

- `ExcelViewModel.historyListEntries` espone `repository.getFilteredHistoryListFlow(filter)` (lista leggera per lo scroll).
- `getFilteredHistoryListFlow` in `DefaultInventoryRepository` delega a `HistoryEntryDao.getAllListItemsFlow()` o `getListItemsBetweenDatesFlow()` **senza filtri** sul tipo di entry.
- `HistoryScreen` consuma `historyListEntries` e mostra il campo `id` (nome) per ogni riga.

Riferimento DAO (nessun filtro oggi):

```11:18:app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntryDao.kt
    @Query(
        """
        SELECT uid, id, timestamp, supplier, category, wasExported, syncStatus,
               orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
        FROM history_entries
        ORDER BY timestamp DESC
        """
    )
    fun getAllListItemsFlow(): Flow<List<HistoryEntryListItem>>
```

### 3a) Lista leggera, `uid` e contratto «foglio riapribile»

- **Lista vs dettaglio:** la `HistoryScreen` usa la lista leggera (`HistoryEntryListItem`, `historyListEntries`). La **selezione** di una riga porta tipicamente a caricare l’entry **completa** tramite `uid` (es. `ExcelViewModel.loadHistoryEntry(entryUid)` dal `NavGraph`). Il contratto UX deve restare: *ogni riga mostrata è un candidato legittimo a quel flusso di riapertura*.
- **Oltre il “titolo nascosto”:** non basta che la lista non mostri più la stringa `APPLY_IMPORT_*`. Il filtro deve garantire che **nessun record tecnico** entri nel set di elementi che l’utente può trattare come **foglio** tramite tap, long-press, rename, delete o qualsiasi altra affordance **standard** della schermata History. In altre parole: la cronologia utente non deve essere solo visivamente più pulita, ma **semanticamente allineata** al modello mentale «solo fogli/generated sheet riapribili».
- **Coerenza end-to-end:** se un record tecnico restasse nel DB ma uscisse dalla lista grazie a un filtro UI superficiale, resterebbe comunque raggiungibile in teoria via `uid` da altri percorsi; il planning della soluzione raccomandata (filtro a monte + stop insert) mira proprio a evitare questa **ambiguità semantica** tra dati persistiti e percorso utente standard.

### 4) Altri log tecnici analoghi (stesso meccanismo)

- **`FULL_IMPORT_<timestamp>`**: `startHistoryLog(“FULL_IMPORT”, …)` chiamato in **due siti distinti** in `DatabaseViewModel.kt`:
  - `prepareSmartFullImportAnalysis` (riga ~461): analisi smart pre-apply.
  - `startFullDbImport` (riga ~954): percorso import completo DB.
  
  Stessa tabella `history_entries`, stesso pattern `id`, stesse query non filtrate → compare in `HistoryScreen` esattamente come `APPLY_IMPORT_*`.
- Logging **testuale** aggiuntivo: tag Android `Log.d` / `Log.e` con prefisso `”DB_IMPORT”` (già presente per `APPLY_IMPORT` / `FULL_IMPORT`) — **non** passa dalla UI History.

**Decisione confermata da verifica repo-grounded (2026-04-11):** `FULL_IMPORT_*` produce lo stesso problema UX di `APPLY_IMPORT_*` — stessa tabella, stesse query, stessa lista utente. **È incluso nel perimetro di questo task.** Entrambi i siti di inserimento (`prepareSmartFullImportAnalysis` e `startFullDbImport`) devono essere trattati nella stessa execution. Il filtro deve coprire entrambi i prefissi `APPLY_IMPORT_%` e `FULL_IMPORT_%` in modo centralizzato.

Scelta di design: progettare il filtro in modo **centralizzato e facilmente estendibile** ai prefissi tecnici, evitando query duplicate con condizioni hardcoded sparse. Il punto unico di centralizzazione sarà al livello DAO o repository.

### 5) Effetto collaterale se si filtrasse solo la lista

`HistoryEntryDao.hasEntriesFlow()` è:

```sql
SELECT EXISTS(SELECT 1 FROM history_entries)
```

Se si nascondessero righe in UI ma restassero solo entry tecniche, si potrebbe avere **lista vuota** ma **hasHistoryEntries = true** (badge/nav). Qualunque soluzione “solo query” deve allineare **tutte** le query usate per la cronologia utente **e** `hasEntriesFlow` agli stessi criteri di visibilità.


C’è anche un secondo rischio concettuale: se il filtro venisse applicato solo a valle nella UI, il dato sorgente resterebbe semanticamente ambiguo e futuri consumer potrebbero continuare a trattare i log tecnici come history utente. Per questo il planning deve esplicitare che la distinzione va risolta **a monte del rendering**, non con filtri locali in `HistoryScreen`.

### 6) Punto di attenzione: naming e manutenibilità

Il problema non è solo “nascondere una riga”, ma evitare che il significato di `history_entries` resti ambiguo per il team e per i task futuri. Se in execution si lasciano nomi troppo generici (`getAllFlow`, `getEntriesBetweenDatesFlow`, `hasEntriesFlow`) ma con semantica reale di “solo history utente visibile”, il rischio è ricreare confusione più avanti.

Per questo il planning dovrebbe favorire naming esplicito almeno al livello dove si centralizza il filtro (DAO o repository), ad esempio concetti tipo **visible history / user history**, senza obbligare un refactor ampio fuori scope ma evitando che la semantica resti nascosta.

### 7) Punto di attenzione: efficienza e coerenza del filtro

Se il filtro venisse applicato solo in memoria dopo query generiche (`Flow<List<...>>` completi), la UI otterrebbe comunque record tecnici inutili per poi scartarli a valle. Questo sarebbe meno efficiente, più ambiguo semanticamente e più fragile rispetto a date filter / empty state / eventuali consumer futuri.

Per questo il planning dovrebbe preferire un filtro applicato **a livello di query o al più nel repository come fonte unica di verità**, non nel composable o in trasformazioni locali del `Flow` usate solo dalla UI.

---

## Decisione architetturale proposta (scelta consigliata)

| Opzione | Pro | Contro |
|---------|-----|--------|
| **A — Non salvare più** `APPLY_IMPORT` in `history_entries` | Impatto minimo su schema; niente migrazioni; apply resta invariato; log già su `Log` / `DB_IMPORT`. | Restano **righe legacy** su DB installati esistenti finché non si fa pulizia o filtro. |
| **B — Salvare ma escludere da query/lista** | Audit persistente per supporto; nasconde anche dati già scritti se il filtro è su `id`. | Va aggiornato **ogni** query consumer della UI + `hasEntriesFlow`; elenco prefissi da mantenere. |
| **C — Colonna esplicita** (es. `isTechnicalLog`) | Modello dati chiaro. | Migrazione DB, touch entity/DAO/repository, più ampiezza. |

**Raccomandazione (TASK-044): combinazione pragmatica, con filtro centralizzato**

1. **Rimuovere** dal path `importProducts` le chiamate a `startHistoryLog` / `appendHistoryLog` per `APPLY_IMPORT`, **mantenendo** i `Log.d` / `Log.e` esistenti su `DB_IMPORT` per tracciamento tecnico in logcat (nessuna esposizione utente).
2. **Aggiungere un criterio centralizzato di visibilità history utente** (DAO o repository, ma con una sola fonte di verità) per escludere i prefissi tecnici dalla cronologia visibile.
3. Applicare lo **stesso identico criterio** sia alla lista history sia a `hasEntriesFlow` / empty state, per evitare disallineamenti UX.
4. In planning, predisporre già la struttura per poter estendere in futuro il filtro ad altri prefissi tecnici senza dover riscrivere più query.
5. **Decisione di perimetro consigliata:** in questo task mantenere **obbligatorio** il caso `APPLY_IMPORT_%`, mentre `FULL_IMPORT_%` resta da includere nello stesso task **solo se** la verifica repo-grounded conferma che oggi raggiunge davvero la cronologia utente con lo stesso impatto confusionale; altrimenti predisporre il filtro ma lasciare l’estensione come follow-up esplicito.

**Motivazione:** UX più pulita, nessuna regressione sul flusso di apply/sync, impatto architetturale contenuto, copertura dei dati legacy già persistiti e migliore manutenibilità rispetto a filtri sparsi. `FULL_IMPORT_*` è incluso (confermato da verifica repo-grounded) perché usa lo stesso meccanismo e produce lo stesso problema UX — escluderlo ora significherebbe riaprire gli stessi file in un follow-up per aggiungere una riga al filtro già in place. Questo mantiene il task focalizzato sul problema reale segnalato dall’utente.

**Nota di implementazione preferita:** se possibile, preferire query DAO dedicate alla “history utente visibile” con naming chiaro (es. concetto di visible/user history) invece di continuare a usare query generiche che in realtà includono anche record tecnici. In questo modo il contratto semantico resta chiaro anche per task futuri.

**Vincolo di robustezza:** evitare filtri basati su campi secondari come `supplier = "—"`, `category = "—"`, `totalItems = 0` o shape del payload `data/editable/complete`, perché sono segnali deboli e possono produrre falsi positivi su entry utente reali o future. Il filtro deve basarsi su prefissi tecnici espliciti e documentati.

**Vincolo di leggibilità/manutenzione:** se il filtro viene implementato con più query dedicate, mantenere comunque un punto evidente e documentato dove vive l’elenco dei prefissi tecnici esclusi, così il task resta estendibile senza dover fare reverse engineering sulle stringhe SQL sparse.

**Vincolo di efficienza/coerenza:** evitare una soluzione che interroghi la history completa e poi filtri solo in memoria per la UI. Il criterio di visibilità deve vivere il più vicino possibile alla sorgente dati della cronologia utente, così empty state, date filter e futuri consumer restano coerenti senza passaggi ridondanti.

**Vincolo di coerenza UX (percorso utente):** la distinzione tra **entry utente** (fogli/generated sheet) e **record tecnici** deve valere in modo uniforme per:
- la **lista** mostrata in `HistoryScreen`;
- il **normale flusso** di selezione → caricamento per `uid` → riapertura verso `GeneratedScreen` (e ogni azione contestuale esposta sulla lista per le voci “da foglio”).

**Nessun** record tecnico deve restare **raggiungibile** tramite le affordance **standard** della schermata History (tap per aprire, azioni su riga, ecc.). Obiettivo: il modello mentale utente «ciò che vedo in cronologia è ciò che posso riaprire come foglio» resta vero **sia visivamente sia nel comportamento**, non solo nello scroll della lista.

**Sottotraccia esplicitamente scartata per questo task:** introdurre una seconda tabella “audit” — utile ma fuori perimetro “minimo impatto” salvo nuova richiesta.

### Decisione UX/UI di supporto

Nessun redesign della schermata è richiesto. Tuttavia, in execution è accettabile un **micro-polish UX** strettamente locale e coerente con il resto dell’app se serve a rendere il comportamento più chiaro dopo il filtro, ad esempio:

- empty state/history vuota più coerente se spariscono le sole voci tecniche;
- microcopy più chiara se oggi il testo implicava una “cronologia globale” mentre in realtà resta una cronologia utente di fogli;
- nessun cambio a rename/delete/reopen/azioni esistenti.

Questi ritocchi sono ammessi solo se piccoli, progressivi e senza scope creep.

---

## File potenzialmente coinvolti (execution futura)

| File | Motivo |
|------|--------|
| `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` | Rimuovere log history su apply; mantenere logging Android. |
| `app/src/main/java/.../data/HistoryEntryDao.kt` | Aggiungere condizioni `WHERE` / query dedicate per escludere prefissi tecnici; allineare `hasEntriesFlow`. |
| `app/src/main/java/.../data/HistoryEntry.kt` | Nessuna modifica prevista al model, ma verificare che il planning non introduca assunzioni sbagliate sulla semantica dei campi esistenti. |
| `app/src/main/java/.../ui/screens/HistoryScreen.kt` | Solo eventuale micro-polish UX locale su empty state / microcopy, se necessario dopo il nuovo filtro; nessun redesign previsto. |
| `app/src/main/java/.../data/InventoryRepository.kt` | Solo se serve esporre in modo semantico la distinzione tra history visibile all’utente e record tecnici, mantenendo una fonte unica di verità. |
| `app/src/main/java/.../viewmodel/ExcelViewModel.kt` | Espone sia `historyListEntries` (lista leggera, via `getFilteredHistoryListFlow`) sia `historyEntries` (lista completa, via `getFilteredHistoryFlow`) sia `hasHistoryEntries` (via `hasHistoryEntriesFlow`). Tutte e tre passano per query DAO non filtrate: il criterio di visibilità va applicato coerentemente su tutti e tre i consumer. |
| `app/src/main/java/.../data/HistoryEntry.kt` | Contiene sia `HistoryEntry` sia `HistoryEntryListItem` (stesso file; **non** esiste `HistoryEntryListItem.kt` separato). Nessuna modifica prevista al model. |
| `app/src/test/.../viewmodel/DatabaseViewModelTest.kt` | Test `importProducts … history log` oggi verificano insert/update history — vanno riscritti per il nuovo contratto (nessuna history DB per apply, oppure verifica assenza `insertHistoryEntry` per apply). |
| `app/src/test/.../data/DefaultInventoryRepositoryTest.kt` | Se si aggiungono test su query filtrate / `hasHistoryEntriesFlow` con mix entry utente + tecniche. |
| `docs/MASTER-PLAN.md` | Solo aggiornamento governance/stato quando e se il task passerà realmente di fase; nessun avanzamento ora oltre il planning. |
| `app/src/main/java/.../ui/navigation/NavGraph.kt` | **Solo verifica indiretta** (lettura / coerenza del wiring): confermare che il percorso standard **History → selezione entry → `loadHistoryEntry` / navigazione verso Generated** continui a ricevere esclusivamente elementi provenienti dalla stessa fonte dati «history utente visibile». **Non** allargare il perimetro a refactor di navigazione; eventuale intervento solo se una review a valle rivelasse un percorso alternativo che aggira il filtro (oggi non atteso). |

**Fuori perimetro tipico:** `HistoryScreen.kt` (nessun cambio necessario se la lista è già pulita a monte).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | `HistoryScreen` non mostra alcuna voce il cui `id` inizi con `APPLY_IMPORT_` (inclusi dati già presenti su DB). | M + S | — |
| 1a | Il criterio che nasconde le entry tecniche non rimuove per errore fogli utente reali con metadati incompleti o placeholder leciti. | S + M | — |
| 2 | Dopo apply import da flusso preview (es. da `GeneratedScreen` fino ad apply), **nessuna** nuova riga `APPLY_IMPORT_*` compare in cronologia. | M | — |
| 3 | Selezione, rename, delete, reopen delle **vere** history entries (fogli generati) restano invariati percepibilmente (nessuna rimozione di feature). | M | — |
| 3a | **Nessun** record tecnico nascosto (es. `APPLY_IMPORT_*` nel perimetro minimo) resta **selezionabile** o **riapribile** tramite il **normale percorso utente** della schermata History (tap per aprire, azioni su riga coerenti con i fogli). Il contratto «ciò che compare in lista = foglio riapribile» vale per lista **e** flusso di apertura. | M (+ S dove copribile da test su flow dati) | — |
| 4 | `applyImport` completa con successo/errore come oggi; snackbar/dialog di esito import non regressano. | B + M | — |
| 5 | `hasHistoryEntries` / empty state coerenti: se l’utente non ha fogli in cronologia, non si resta “bloccati” da sole entry tecniche nascoste. | M / S | — |
| 5a | Le query/flow usati dalla cronologia utente hanno una fonte di verità chiara e non duplicano logica di filtro in più punti incoerenti. | S | — |
| 5b | I record legacy tecnici già presenti non ricompaiono in lista dopo riapertura app / cambio filtro data. | M + S | — |
| 5c | Il criterio di filtro tecnico è documentato in modo chiaro nel punto di centralizzazione scelto, così l’estensione a futuri prefissi non richiede reverse engineering. | S | — |
| 5d | Il task resta focalizzato sul problema utente segnalato (`APPLY_IMPORT_*`) e non introduce scope creep non giustificato su altri log tecnici. | S | — |
| 5e | La cronologia utente visibile non dipende da un filtro finale solo in memoria nella UI; il criterio è applicato alla fonte dati o nel suo punto unico di orchestrazione. | S | — |
| 5f | `FULL_IMPORT_%` è incluso nel perimetro (confermato da verifica repo-grounded 2026-04-11): nessuna voce `FULL_IMPORT_*` compare in cronologia utente, né viene più inserita su analisi/full-import. | S + M | — |
| 6 | Logging tecnico: resta disponibile via `adb logcat` / tag `DB_IMPORT` per diagnostica (nessun obbligo di nuova UI). | S | — |
| 7 | Build Gradle e lint senza nuovi errori; baseline **TASK-004** aggiornata se si toccano repository/ViewModel testati. | B + S | — |

Legenda: B=Build, S=Static/test JVM, M=Manuale.

> Checklist **Definition of Done — task UX/UI** (`MASTER-PLAN.md`): applicabile per aspetto visivo; qui l’intervento è principalmente **dati/lista**, ma la gerarchia e gli stati History non devono peggiorare.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Planning completato senza execution | Richiesta utente: solo governance + analisi repo. | 2026-04-11 |
| 2 | Soluzione preferita: stop insert apply + filtro query (+ allineamento `hasEntries`) | UX pulita, minimo schema, copertura legacy. | 2026-04-11 |
| 3 | Evitare semantica nascosta nelle query history | Il filtro deve essere centralizzato ma anche comprensibile/manutenibile per task futuri. | 2026-04-11 |
| 4 | Perimetro minimo guidato dal problema reale: `APPLY_IMPORT_*` obbligatorio, `FULL_IMPORT_*` solo se confermato come stesso difetto UX attuale | Evitare scope creep, mantenendo però il filtro estendibile. | 2026-04-11 |
| 5 | Coerenza **lista + percorso standard** (selezione/apertura): nessun record tecnico raggiungibile dalle affordance normali di History | Evita lista “pulita” ma flusso ancora semanticamente ambiguo verso `uid` / foglio. | 2026-04-11 |
| 6 | `FULL_IMPORT_*` **confermato in perimetro** di questo task (non follow-up) | Verifica repo-grounded 2026-04-11: stesso meccanismo (`startHistoryLog`), stessa tabella, stesse query non filtrate, stesso problema UX; due siti in `DatabaseViewModel.kt` (`prepareSmartFullImportAnalysis` r.~461, `startFullDbImport` r.~954). Includerlo ora evita riaprire gli stessi file per aggiungere un solo prefisso al filtro già in place. | 2026-04-11 |

---

## Planning (Claude)

### Analisi

Vedi sezione **Analisi repo-grounded** sopra.

### Piano di esecuzione (futuro, dopo approvazione `PLANNING → EXECUTION`)

1. Individuare il punto **più corretto e unico** dove definire la visibilità della history utente (preferenza: query DAO dedicate o repository con contratto esplicito, evitando filtri UI/ViewModel sparsi), formalizzare un elenco centralizzato dei prefissi tecnici ammessi/esclusi e scegliere naming abbastanza esplicito da non lasciare semantica nascosta.
2. Implementare l’esclusione di `APPLY_IMPORT_%` e `FULL_IMPORT_%` nelle query Room usate dalla cronologia utente e in `hasEntriesFlow` / eventuali query correlate, usando lo stesso predicato semantico ed evitando filtri finali solo in memoria lato UI.
2b. Verificare che il filtro sia applicato coerentemente su tutti e tre i consumer di `ExcelViewModel`: `historyListEntries` (via `getFilteredHistoryListFlow`), `historyEntries` (via `getFilteredHistoryFlow`) e `hasHistoryEntries` (via `hasHistoryEntriesFlow`). Se `historyEntries` è usato solo internamente senza esporre entry tecniche all’utente, documentare questa scelta esplicitamente.
3. Rimuovere `startHistoryLog` / `appendHistoryLog` dal corpo di `importProducts`, conservando `Log.*("DB_IMPORT", …)` e ogni feedback utente già esistente (snackbar/stato import).
4. Rimuovere `startHistoryLog` / `appendHistoryLog` anche dai due siti `FULL_IMPORT_*` in `DatabaseViewModel.kt`:
   - `prepareSmartFullImportAnalysis` (riga ~461): rimuovere `startHistoryLog` e tutti gli `appendHistoryLog` che usano `currentImportLogUid` in quel percorso;
   - `startFullDbImport` (riga ~954): stessa operazione sul blocco che usa `currentImportLogUid`.
   Conservare tutti i `Log.*("DB_IMPORT", …)` esistenti. **Decisione già presa (confermata da verifica repo-grounded 2026-04-11):** `FULL_IMPORT_*` è in perimetro, stesso difetto UX, stessa tabella, stesse query.
4b. Il filtro DAO/repository deve coprire entrambi i prefissi `APPLY_IMPORT_%` e `FULL_IMPORT_%` in un unico punto centralizzato.
4a. Se il codice esistente usa query/history flow con naming troppo generico rispetto alla nuova semantica, valutare un micro-riordino locale dei nomi pubblici più esposti (senza refactor ampio) per rendere evidente la distinzione tra history utente visibile e record tecnici.
5. Aggiornare `DatabaseViewModelTest` e, se necessario, `DefaultInventoryRepositoryTest` per riflettere:
   - assenza di nuove history entry tecniche su apply import;
   - filtro coerente delle entry legacy;
   - coerenza di `hasEntriesFlow` / empty state.
6. Eseguire `./gradlew assembleDebug`, `./gradlew lint`, baseline JVM **TASK-004** mirata (`DatabaseViewModelTest`, eventuali test repository/DAO interessati).
7. Smoke manuale UX:
   - apply import da Import Analysis → nessuna voce tecnica in History;
   - cronologia con soli record tecnici legacy → empty state coerente;
   - presenza mista di entry utente + record tecnici legacy → lista pulita e ordinamento invariato per le sole entry utente visibili;
   - rename/delete/reopen di una entry reale invariati;
   - **percorso standard History → tap su voce in lista → apertura foglio:** verificare che **nessun** record tecnico (nel perimetro del task) sia ancora apribile da quel flusso; non deve esistere una “lista pulita” ma un’apertura che carichi ancora un log interno come se fosse un foglio;
   - filtro data ancora coerente con le entry utente visibili;
   - nessuna regressione percepibile su performance scroll/lista History.

### Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Test che si aspettano `insertHistoryEntry` su ogni `importProducts` | Aggiornare test nello stesso task; non rimuovere asserzioni su `applyImport` / UI state. |
| `hasEntriesFlow` disallineato dalla lista filtrata | Aggiornare la query EXISTS con lo stesso filtro su `id`. |
| Filtro implementato in più punti con semantiche diverse | Centralizzare il criterio di visibilità e riusarlo per lista, count/exists ed eventuali flow correlati. |
| Semantica poco chiara nonostante il filtro corretto | Usare naming/documentazione locale chiari nel punto di centralizzazione, evitando che in futuro qualcuno riusi query “generiche” credendo che includano tutto. |
| Empty state o data filter non coerenti dopo la scomparsa delle entry tecniche | Verificare manualmente scenario “solo record tecnici legacy” e scenario con range date attivo. |
| Filtro corretto ma applicato troppo tardi (solo UI/in-memory) | Preferire query/repository come fonte unica di verità; evitare trasformazioni locali che mascherano il problema senza risolverlo alla sorgente. |
| Prefissi duplicati o futuri `kind_` | Documentare in codice costante elenco prefissi “technical”; eventuale follow-up colonna boolean. |
| Scope creep su altri log tecnici non verificati | Mantenere obbligatorio il fix su `APPLY_IMPORT_*`; estendere ad altri prefissi solo con conferma repo-grounded dello stesso problema UX. |
| Falso positivo su entry utente se il filtro usa euristiche deboli | Limitare il criterio a prefissi tecnici espliciti e coprire con test caso utente con placeholder/metadati minimi. |
| Utente si aspetta audit persistito apply in-app | Non in scope: logcat resta; eventuale schermata debug = nuovo task. |
| Lista visivamente pulita ma **percorso di apertura** ancora **semanticamente ambiguo** (es. record tecnico ancora raggiungibile via `uid` o affordance parallele non allineate al filtro) | Allineare lista, `hasEntries` e **stesso** sottoinsieme di entry idonee al tap/azioni standard; in execution verificare `NavGraph` / wiring History → `loadHistoryEntry` solo come **controllo di coerenza**, senza allargare il perimetro. |

---

## Execution

**Execution autorizzata dall'utente il 2026-04-11** — planning review repo-grounded completata; task portato a `EXECUTION` dopo conferma coerenza codice/planning e integrazione correzioni (FULL_IMPORT_* in perimetro, file table corretto, consumer ExcelViewModel allineati).

### Esecuzione — 2026-04-11

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntryDao.kt` — centralizzato il criterio SQL della history utente visibile ed esclusi `APPLY_IMPORT_%` / `FULL_IMPORT_%` da lista, flow completi e `hasEntries`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — riallineato il repository alle query DAO user-visible senza duplicare logica di filtro.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — rimossa la creazione/update di `HistoryEntry` tecniche per `APPLY_IMPORT_*` e `FULL_IMPORT_*`, mantenendo il logging `Log.*("DB_IMPORT", ...)`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — chiarita nei commenti la semantica di history utente visibile consumata dalla schermata History.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunti/aggiornati test su filtro legacy tecnico, `hasHistoryEntriesFlow` e protezione da falsi positivi su entry utente con placeholder.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — aggiornati i test di apply import per verificare l’assenza di persistenza di log tecnici in `history_entries`.

**Azioni eseguite:**
1. Introdotto in `HistoryEntryDao` un unico predicato `USER_VISIBLE_HISTORY_WHERE_CLAUSE`, documentato e riusato da tutte le query della cronologia utente.
2. Rinominati i metodi DAO coinvolti con semantica esplicita `UserVisible...`, lasciando il repository come orchestratore unico dei flow già consumati da `ExcelViewModel`.
3. Rimosse da `DatabaseViewModel.importProducts` le chiamate a `startHistoryLog` / `appendHistoryLog`; il percorso apply ora usa solo `repository.applyImport(...)` e logging tecnico `DB_IMPORT`.
4. Rimossi anche i log persistiti `FULL_IMPORT_*` dai due percorsi in perimetro (`prepareSmartFullImportAnalysis` e `startFullDbImport`), mantenendo `Log.d` / `Log.w` / `Log.e`.
5. Verificato il wiring del percorso standard `History -> tap entry -> loadHistoryEntry(uid) -> GeneratedScreen`: `NavGraph` continua a partire da `historyListEntries`, quindi con il filtro a monte nessun record tecnico resta raggiungibile dalle affordance standard della schermata.
6. Aggiornata la baseline di regressione JVM sulle aree toccate (`HistoryEntryDao`/repository history + `DatabaseViewModel` apply import) senza indebolire i test esistenti.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL`; nessun nuovo issue introdotto |
| Warning nuovi            | ✅ | Nessun warning Kotlin nuovo nei file modificati; presenti solo warning toolchain/AGP preesistenti del progetto |
| Coerenza con planning    | ✅ | Eseguiti i 4 punti pianificati: stop insert/update tecnici, filtro centralizzato, allineamento consumer history, test/verifiche |
| Criteri di accettazione  | ✅ | Tutti verificati con evidenze statiche/JVM e audit del wiring History; dettaglio sotto |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest --tests com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest --tests com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest`
- Test aggiunti/aggiornati: `DefaultInventoryRepositoryTest` (filtro entry legacy tecniche, `hasHistoryEntriesFlow`, falso positivo placeholder), `DatabaseViewModelTest` (assenza persistenza log tecnici su apply).
- Limiti residui: nessun smoke su emulator/device eseguito; il percorso utente standard è stato verificato staticamente sul wiring `NavGraph`/`ExcelViewModel`, coerentemente con il task Compose/data-driven e senza richiesta esplicita di test manuale su device.

**Verifica criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ESEGUITO | `USER_VISIBLE_HISTORY_WHERE_CLAUSE` esclude `APPLY_IMPORT_%`; `DefaultInventoryRepositoryTest.getFilteredHistoryFlow respects custom date range` copre anche un record legacy `APPLY_IMPORT_*` e conferma che non compare. |
| 1a | ESEGUITO | `DefaultInventoryRepositoryTest.getFilteredHistoryFlow keeps user entries with placeholder metadata while filtering technical prefixes` conferma che il filtro non usa euristiche su supplier/category/totali. |
| 2 | ESEGUITO | `DatabaseViewModel.importProducts` non chiama più `insertHistoryEntry`/`updateHistoryEntry`; coperto dai test `importProducts applies import without persisting technical history entries` e failure counterpart. |
| 3 | ESEGUITO | Nessuna modifica a `HistoryScreen`, rename/delete/reopen o `GeneratedScreen`; il percorso utente resta invariato e riceve solo un sottoinsieme più pulito di entry. |
| 3a | ESEGUITO | Audit statico di `NavGraph.kt`: `HistoryScreen` riceve `historyListEntries`, il tap usa `entry.uid` proveniente da quella lista filtrata; nessun record tecnico resta selezionabile dal normale percorso History. |
| 4 | ESEGUITO | Verdi i test `DatabaseViewModelTest` su apply success/failure; feedback `UiState` e `ImportFlowState` restano invariati. |
| 5 | ESEGUITO | `hasHistoryEntriesFlow` usa lo stesso predicato DAO; test `hasHistoryEntriesFlow ignores technical-only history` conferma empty state coerente se esistono solo record tecnici legacy. |
| 5a | ESEGUITO | La fonte unica di verità è il predicato `USER_VISIBLE_HISTORY_WHERE_CLAUSE` riusato da tutte le query DAO user-visible. |
| 5b | ESEGUITO | I test su custom range/list/all coprono record legacy `APPLY_IMPORT_*` e `FULL_IMPORT_*`, quindi il filtro resta coerente anche dopo riapertura/filtro data a parità di DB persistito. |
| 5c | ESEGUITO | Il punto di centralizzazione è commentato in `HistoryEntryDao.kt`; i metodi DAO sono rinominati con semantica `UserVisible...`. |
| 5d | ESEGUITO | Il task tocca solo `APPLY_IMPORT_*` e `FULL_IMPORT_*`, come da planning confermato; nessun altro prefisso/log tecnico è stato alterato. |
| 5e | ESEGUITO | Il filtro vive nel DAO/Repository source layer, non nella UI né in `Flow` in-memory locali. |
| 5f | ESEGUITO | Rimossi i log persistiti `FULL_IMPORT_*` sia in `prepareSmartFullImportAnalysis` sia in `startFullDbImport`; lo stesso filtro DAO esclude i record legacy `FULL_IMPORT_*`. |
| 6 | ESEGUITO | `DatabaseViewModel` mantiene logging tecnico `DB_IMPORT` per start/success/failure/cancel dei flussi coinvolti. |
| 7 | ESEGUITO | Baseline JVM mirata, `assembleDebug` e `lint` eseguiti con esito verde. |

**Smoke reasoning/manual checklist coerente col task:**
- Lista History: i record tecnici non vengono più caricati dalle query user-visible del DAO.
- Empty state: `hasHistoryEntriesFlow` usa lo stesso predicato della lista, quindi non resta “vero” con soli legacy tecnici.
- Percorso standard: `NavGraph` apre `GeneratedScreen` solo da entry selezionate nella lista filtrata.
- Rename/Delete/Reopen: il wiring di `HistoryScreen` è invariato; cambia solo il set sorgente di entry mostrate.
- Logging tecnico: resta disponibile in logcat via tag `DB_IMPORT`, senza reintrodurre record tecnici nell’esperienza utente.

**Incertezze:**
- Nessuna sul comportamento implementato.
- Verifica manuale su emulator/device non eseguita perché non richiesta esplicitamente dal task; copertura statica/JVM sufficiente per il perimetro corrente.

**Handoff notes:**
- Task pronto per `REVIEW`.
- Focus review consigliato: confermare che il naming DAO/repository renda chiara la distinzione “history utente visibile” senza necessità di ulteriore refactor pubblico.
- Nessuna modifica a `docs/MASTER-PLAN.md`: per protocollo dell’esecutore non è stato alterato lo stato globale.

---

## Review

### Review — 2026-04-11

**Revisore:** Claude (planner) — review repo-grounded completa su tutti i file dichiarati

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | `HistoryScreen` non mostra voci `APPLY_IMPORT_*` (inclusi legacy) | ✅ | `USER_VISIBLE_HISTORY_WHERE_CLAUSE` esclude `APPLY_IMPORT_%` a livello DAO; test `getFilteredHistoryFlow respects custom date range` lo conferma con record legacy. |
| 1a | Il criterio non rimuove fogli utente con metadati placeholder | ✅ | Test `getFilteredHistoryFlow keeps user entries with placeholder metadata while filtering technical prefixes` copre supplier="—", category="—", totalItems=0. |
| 2 | Nessuna nuova riga `APPLY_IMPORT_*` dopo apply import | ✅ | `DatabaseViewModel.importProducts` non chiama più `insertHistoryEntry`/`updateHistoryEntry`; Grep conferma zero chiamate a `startHistoryLog`/`appendHistoryLog`/`insertHistoryEntry` nel file. |
| 3 | Rename/delete/reopen entry reali invariati | ✅ | Nessuna modifica a `HistoryScreen`, `NavGraph`, flusso `GeneratedScreen`. |
| 3a | Nessun record tecnico selezionabile tramite affordance standard History | ✅ | `historyListEntries` (ExcelViewModel) usa `getFilteredHistoryListFlow` → filtrato a monte; `NavGraph` usa `entry.uid` dalla lista filtrata. |
| 4 | `applyImport` completa come prima; snackbar/stato invariati | ✅ | Test `DatabaseViewModelTest` su apply success/failure/AlreadyRunning verdi; `UiState`/`ImportFlowState` invariati. |
| 5 | `hasHistoryEntries` / empty state coerenti con soli record tecnici | ✅ | `hasUserVisibleEntriesFlow` usa lo stesso predicato; test `hasHistoryEntriesFlow ignores technical-only history` lo verifica. |
| 5a | Fonte di verità unica per il filtro | ✅ | `USER_VISIBLE_HISTORY_WHERE_CLAUSE` `const val` riusata da tutte e 5 le query DAO user-visible; repository delega senza aggiungere logica propria. |
| 5b | Record legacy non ricompaiono dopo riapertura/filtro data | ✅ | Test su custom range, list-flow e all-flow includono record legacy `APPLY_IMPORT_*`/`FULL_IMPORT_*` e verificano l'esclusione. |
| 5c | Criterio documentato nel punto di centralizzazione | ✅ | Commento sopra la costante in `HistoryEntryDao.kt`; naming `UserVisible…` esplicito su tutti i metodi DAO coinvolti. |
| 5d | Nessun scope creep su altri prefissi non verificati | ✅ | Solo `APPLY_IMPORT_%` e `FULL_IMPORT_%` — entrambi in perimetro confermato da planning. |
| 5e | Filtro non solo in-memory nella UI | ✅ | Filtro a livello SQL nel DAO; nessuna trasformazione `Flow` locale in ExcelViewModel o HistoryScreen. |
| 5f | `FULL_IMPORT_*` esclusi da lista e non più inseriti | ✅ | Rimossi da `prepareSmartFullImportAnalysis` e `startFullDbImport`; stesso predicato DAO copre i record legacy. |
| 6 | Logging tecnico resta disponibile via logcat `DB_IMPORT` | ✅ | `Log.d/w/e("DB_IMPORT", ...)` mantenuti in tutti i percorsi coinvolti. |
| 7 | Build, lint, baseline JVM verdi | ✅ | `assembleDebug` e `lint` verdi; `testDebugUnitTest` mirato su `DefaultInventoryRepositoryTest` + `DatabaseViewModelTest` verde. |

**Problemi trovati:** nessuno.

**Punti di qualità verificati:**
- `USER_VISIBLE_HISTORY_WHERE_CLAUSE` è `internal const val` → Room vede la stringa completa a compile time; il build verde lo conferma.
- `getLatestTimestamp()` nel DAO è intenzionalmente non filtrato (query di utilità non usata dalla lista utente): corretto, non è in scope.
- I test aggiunti non sono superficiali: coprono casi limite reali (falso positivo placeholder, empty state solo con tecnici, legacy in range date, legacy in all-flow).
- Nessuna funzione `startHistoryLog`/`appendHistoryLog` residua — Grep su DatabaseViewModel.kt conferma zero match.
- ExcelViewModel: tutti e tre i consumer (`historyListEntries`, `historyEntries`, `hasHistoryEntries`) passano per query filtrate.

**Verdetto:** **APPROVED**

**Note per fix:** nessuna.

---

## Fix

_(Non applicabile.)_

---

## Chiusura

**Chiuso il:** 2026-04-11
**Chiuso da:** Claude (planner) — review repo-grounded APPROVED, nessun fix richiesto.
**Stato finale:** `DONE`

---

## Riepilogo finale

**Obiettivo raggiunto:** la cronologia utente in `HistoryScreen` non mostra più voci tecniche `APPLY_IMPORT_*` o `FULL_IMPORT_*`, né le genera dopo apply import. Il contratto semantico «solo fogli/generated sheet riapribili in lista» è garantito dall'intera chain: DAO → repository → ExcelViewModel → UI.

**Soluzione adottata:** combinazione stop-insert (DatabaseViewModel) + filtro centralizzato a sorgente (DAO, `USER_VISIBLE_HISTORY_WHERE_CLAUSE`). Approccio pulito, manutenibile, estendibile a futuri prefissi tecnici senza toccare più punti.

**Rischi residui:**
| Rischio | Probabilità | Impatto | Mitigazione |
|---------|-------------|---------|-------------|
| Record tecnici legacy ancora presenti su DB di installazioni esistenti | CERTA (per design) | Basso | Il filtro DAO li esclude correttamente anche per dati pregressi; coperti da test. |
| Smoke manuale su device non eseguito | N/A | Non bloccante | Copertura statica/JVM sufficiente per il perimetro; il task non richiedeva smoke su device. |

**Test manuali consigliati (prossima sessione con device):**
1. Apply import da Import Analysis → nessuna voce tecnica in History.
2. Cronologia con soli record tecnici legacy → empty state coerente.
3. Presenza mista entry utente + record tecnici legacy → lista pulita, ordinamento invariato.
4. Tap su entry reale → apertura foglio funzionante.
5. Filtro data attivo → solo entry utente nel range.

---

## Handoff

- **Stato attuale:** execution completata, task portato a `REVIEW` con build/lint/baseline JVM verdi.
- **Conferma per reviewer:** il fix è concentrato in DAO/repository/viewmodel/test; `HistoryScreen`, navigation e altre aree fuori scope non sono state modificate.
- **Contesto:** le voci `APPLY_IMPORT_*` sono `HistoryEntry` create solo per log; la UI History le tratta come fogli.
- **Direzione consigliata:** la cronologia utente deve avere un contratto semantico esplicito di “entry visibili all’utente”, non essere un semplice dump di tutto ciò che esiste in `history_entries`.
- **Scelta tecnica da preservare:** niente euristiche fragili sui contenuti della riga; usare solo criteri tecnici espliciti e centralizzati, così la UX resta prevedibile e il comportamento è estendibile.
- **Qualità del piano:** oltre alla correttezza del filtro, preservare anche chiarezza di naming e manutenibilità, così il significato di “history utente visibile” non resta implicito.
- **Efficienza/coerenza da preservare:** non trasformare il fix in un semplice filtro UI in-memory; la cronologia utente deve nascere già pulita dalla sua fonte dati o dal suo punto unico di orchestrazione.
- **Dopo execution:** verificare manualmente apply da Import Analysis + lista History, scenario legacy con soli record tecnici, coerenza empty state e filtro date; i due siti di inserimento `FULL_IMPORT_*` (`prepareSmartFullImportAnalysis` e `startFullDbImport`) sono **entrambi in perimetro** — verificare entrambi.
- **Disciplina di perimetro:** `APPLY_IMPORT_*` e `FULL_IMPORT_*` sono **entrambi in perimetro** (decisione #6, confermata da verifica repo-grounded 2026-04-11); eventuali altri prefissi tecnici futuri vanno inclusi solo con stessa verifica.
- **Tracciabilità decisionale:** perimetro già chiuso in planning — `FULL_IMPORT_%` incluso, non follow-up. Nessuna ambiguità residua su questo punto.
- **File task:** `docs/TASKS/TASK-044-history-cronologia-utente-senza-entry-tecniche-apply-import.md`
- **Nota finale di coerenza:** una cronologia «pulita» deve esserlo **sia visivamente (lista)** sia nel **comportamento di selezione/apertura** del percorso standard utente — non è sufficiente uno scroll senza titoli tecnici se il flusso History → foglio potesse ancora trattare un log interno come sheet.


## Ottimizzazioni aggiunte al planning

- Chiarita la distinzione tra **history utente visibile** e record tecnici persistiti.
- Rafforzata la richiesta di **centralizzare** il criterio di visibilità per evitare regressioni future.
- Esplicitato l’allineamento necessario tra lista cronologia, `hasEntriesFlow`, empty state e filtro data.
- Aggiunto spazio per eventuale **micro-polish UX/UI** locale, senza redesign e senza scope creep.
- Reso più forte il piano test sui casi legacy, che sono il punto più facile da dimenticare ma il più importante per evitare cronologia sporca dopo l’update.
- Esplicitato che il filtro non deve mai basarsi su euristiche fragili dei contenuti, ma solo su prefissi tecnici documentati.
- Rafforzato lo smoke test sul caso reale più importante: lista mista con entry utente vere + record legacy tecnici, senza alterare ordinamento e leggibilità.
- Rafforzata la dimensione di **manutenibilità**, non solo di correttezza: il filtro deve essere anche facile da capire e aggiornare.
- Aggiunto un richiamo esplicito al **naming semantico** per evitare che query apparentemente generiche continuino a creare ambiguità futura.
- Chiarito meglio il **perimetro minimo intelligente**: fix certo su `APPLY_IMPORT_*`, estensione ad altri prefissi solo se il problema UX è davvero confermato.
- Ridotto il rischio di **scope creep difensivo**, mantenendo comunque il design del filtro già pronto per crescere senza refactor inutile.
- Rafforzata anche la parte di **efficienza architetturale**: il filtro non deve vivere come toppa finale nella UI dopo aver caricato record tecnici inutili.
- Rafforzata anche la **tracciabilità del perimetro**: la decisione su `FULL_IMPORT_%` dovrà essere esplicitata nel task file, non lasciata implicita.
- Reso più esplicito che correttezza UX e pulizia del dato devono stare insieme: lista, empty state e date filter devono derivare dalla stessa fonte già filtrata.
- **Chiusura concettuale del planning:** la coerenza richiesta non è solo «lista senza righe brutte», ma **percorso utente completo** (selezione → apertura) in cui **solo** le entry utente restano fogli riapribili tramite le affordance normali di History; lista e apertura devono restare allineate allo stesso contratto semantico.
- Aggiunto al planning un **controllo di coerenza** su `NavGraph` / History → Generated come verifica indiretta del wiring, **senza** allargare il perimetro a refactor di navigazione.
- **Review repo-grounded 2026-04-11:** verificata coerenza completa del planning con il codice reale. Corretti: (1) file table `HistoryEntryListItem.kt` → definita dentro `HistoryEntry.kt` (non file separato); (2) `FULL_IMPORT_*` elevato da "valutare in execution" a "confermato in perimetro" (due siti: `prepareSmartFullImportAnalysis` r.~461 e `startFullDbImport` r.~954); (3) aggiunta nota consumer `ExcelViewModel.historyEntries` full flow via `getFilteredHistoryFlow`; (4) step 4 piano esecuzione aggiornato; (5) decisione #6 aggiunta; (6) criteri 5f aggiornato; (7) handoff riallineato.
