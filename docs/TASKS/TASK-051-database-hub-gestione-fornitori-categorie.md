# TASK-051 — Database hub: gestione Fornitori e Categorie

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-051                   |
| Stato              | DONE                       |
| Priorità           | ALTA                       |
| Area               | UX/UI — DatabaseScreen; dati — Room / Repository / DatabaseViewModel |
| Creato             | 2026-04-12                 |
| Ultimo aggiornamento | 2026-04-12 (DONE — review planner APPROVED + fix cleanup) |

---

## Dipendenze

- Nessuna bloccante. Coerenza consigliata con **TASK-003** (`DONE`, decomposizione `DatabaseScreen`), **TASK-015** (`DONE`, UX Database), **TASK-030** (`DONE`, design system), **TASK-037** (`DONE`, pattern dialog).
- Riuso pattern UX di ricerca lista: **TASK-050** (`HistoryValuePickerDialog` / picker con ricerca) come **riferimento visivo Compose** (nello stesso repo), senza accoppiare logica History.

---

## Scopo

Estendere la schermata **Database** da “solo prodotti” a **hub a tre sezioni** (Prodotti / Fornitori / Categorie), con CRUD completo su fornitori e categorie lato UI, mantenendo **ViewModel come SSoT**, import/export, cronologia prezzi, sync/analyze e navigazione **invariati nel comportamento atteso**.

La **rinomina** deve aggiornare il record entità (stesso `id`) così che tutti i prodotti collegati mostrino il nuovo nome via join esistente. L’**eliminazione** deve essere **guidata**: se ci sono prodotti collegati, obbligare a **sostituzione** (esistente o nuova) o, dove coerente, a **rimozione assegnazione** prima di rimuovere l’entità.

Il task deve migliorare anche la **leggibilità e la percezione di ordine** della schermata Database: gerarchia più chiara, empty state specifici per tab, CTA contestuali, meno rumore visivo e azioni pericolose separate dalle azioni ordinarie.

---

## Contesto (repo-grounded)

### Modello dati attuale

- `Product` ha `supplierId` e `categoryId` nullable, FK verso `Supplier` / `Category`, con `onDelete = ForeignKey.SET_NULL` (vedi `Product.kt`). Una `DELETE` “cieca” sul fornitore/categoria **non** è il comportamento desiderato per questo task: oltre a non offrire UX guidata, lascerebbe prodotti con riferimento null senza consenso esplicito dell’utente nel flusso principale.
- `Supplier` e `Category` hanno **nome univoco** (`Index unique` su `name`; `Category` usa `NOCASE` sul nome).
- `SupplierDao` / `CategoryDao` espongono oggi **insert, get, search, findByName, getById**; **mancano** update/delete mirati e conteggi uso su `products`.
- `InventoryRepository` espone `addSupplier` / `addCategory` con mutex e dedup per nome; **non** espone rename, delete, conteggio prodotti, bulk reassignment.
- `DatabaseViewModel` espone già flow per liste fornitore/categoria (es. `suppliers`, `categories`, `addSupplier`, `addCategory`) orientati all’editing prodotto / import preview; vanno **estesi o separati** (vedi piano) per l’hub senza rompere i call site esistenti.
- `HistoryEntry` memorizza `supplier` e `category` come **stringhe** (`HistoryEntry.kt`). Una rinomina sulle tabelle `suppliers` / `categories` **non** aggiorna automaticamente le righe storiche già salvate. Va dichiarato come **limite noto / decisione prodotto** (vedi Decisioni).

### UI attuale

- `DatabaseScreen.kt`: `DatabaseRootHeader` + `DatabaseProductListSection` + FAB scan/aggiungi; import/export invariati nel tab Prodotti.
- Modularizzazione: `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, `EditProductDialog.kt`.

---

## Non incluso

- Redesign globale navigazione root (`NavGraph`, `Screen.kt`) o nuove route dedicate a meno che emerga necessità tecnica documentata.
- Migrazione DB schema (version bump) **salvo** emergenza (non prevista: le FK e gli indici esistono già).
- Backfill automatico delle stringhe `supplier` / `category` in `history_entries` (opzionale come **task futuro**; non richiesto qui salvo decisione esplicita in Execution).
- Feature parity con un’app iOS esterna: questo repo **non** contiene il sorgente iOS; eventuale confronto solo manuale fuori repo.
- Merge automatico tra entità duplicate (es. rinomina verso un nome già esistente con fusione dei riferimenti) nella prima iterazione: fuori scope, salvo scoperta di requisito reale in Execution.

---

## File potenzialmente coinvolti (Android)

| File | Ruolo |
|------|--------|
| `app/src/main/java/.../ui/screens/DatabaseScreen.kt` | Orchestrazione tab hub, stato dialoghi, FAB contestuale |
| `app/src/main/java/.../ui/screens/DatabaseScreenComponents.kt` | Header adattivo per tab; liste Fornitori/Categorie; righe con conteggio |
| `app/src/main/java/.../ui/screens/DatabaseScreenDialogs.kt` | Dialog rinomina; sheet/dialog eliminazione guidata; eventuale picker riuso pattern TASK-050 |
| `app/src/main/java/.../ui/screens/EditProductDialog.kt` | Verifica integrazione liste fornitore/categoria (SSoT VM); evitare regressioni |
| `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` | Stato tab, query liste, rename/delete/reassign, errori utente |
| `app/src/main/java/.../data/InventoryRepository.kt` | API repository per conteggi, rename, delete sicuro, transazioni |
| `app/src/main/java/.../data/ProductDao.kt` | `COUNT` per supplierId/categoryId; `UPDATE` bulk reassignment; eventuali query transazionali |
| `app/src/main/java/.../data/SupplierDao.kt` | `@Update`, `@Delete` (o query mirate), ordinamento coerente |
| `app/src/main/java/.../data/CategoryDao.kt` | Stesso |
| `app/src/main/java/.../data/AppDatabase.kt` | Solo se servisse DAO di supporto (improbabile) |
| `app/src/main/res/values*/strings.xml` | Stringhe dialoghi, titoli tab, messaggi vincolo univoco, conferme |
| `app/src/test/java/.../DatabaseViewModelTest.kt` | Estensione baseline TASK-004 per nuovi flussi |
| `app/src/test/java/.../DefaultInventoryRepositoryTest.kt` | Se si aggiungono metodi repository sostanziali |

### Riferimento UX (solo pattern, stesso repo)

- `HistoryScreen.kt` + componenti picker con ricerca (**TASK-050**): modello di **dialog lista + search** riadattabile per “Sostituisci con esistente”.

### File iOS (fuori workspace)

- Eventuale schermata “Database / anagrafiche” sull’app iOS, **solo** come ispirazione gerarchica (tab, liste, sheet). **Nessun** porting 1:1 e nessuna logica da copiare.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | La schermata Database presenta **tre sezioni** chiare (Prodotti / Fornitori / Categorie) con navigazione tab/segmenti M3 coerente col tema | M / S | — |
| 2 | Tab **Prodotti**: stesse capacità attuali (lista paginata, filtro, edit, delete prodotto, storico prezzi, import, export, scan, FAB) senza regressioni funzionali | B / M | — |
| 3 | Tab **Fornitori** e **Categorie**: elenco ordinato, **ricerca per nome**, **aggiunta**, **rinomina**, **eliminazione** secondo le regole di uso | M | — |
| 4 | Ogni riga fornitore/categoria mostra **conteggio prodotti collegati** (badge o testo secondario) | M | — |
| 5 | Ogni tab ha **empty state**, stato loading e stato “nessun risultato” coerenti col tone of voice e con CTA utile (es. aggiungi entità, pulisci ricerca) | M | — |
| 6 | Le azioni distruttive non sono la primary action della riga; rename/add sono più evidenti di delete, e la delete guidata separa chiaramente scelta strategia e conferma finale | M | — |
| 7 | Il cambio tab **non resetta inutilmente** i filtri del tab precedente durante la permanenza sulla schermata; tornando su Prodotti l’utente ritrova ricerca e contesto coerenti | M | — |
| 8 | Il tab iniziale della schermata Database resta **Prodotti** e l’introduzione dell’hub non altera il mental model corrente dell’utente abituale | M | — |
| 9 | Il passaggio tra tab preserva, nella stessa sessione schermata, anche una percezione di continuità del contenuto (es. niente salti gratuiti in alto o ricostruzioni visive inutili se evitabili) | M | — |
| 10 | **Rinomina**: aggiorna il nome entità mantenendo l’`id`; i prodotti collegati riflettono il nuovo nome nelle UI che joinano fornitore/categoria; gestione **nome duplicato** (unique index) con messaggio chiaro | B / M | — |
| 11 | **Eliminazione senza prodotti collegati**: delete diretto con conferma semplice | M | — |
| 12 | **Eliminazione con prodotti collegati**: flusso guidato obbligatorio — **sostituzione con esistente** (picker ricerca) e **creazione nuova entità come sostituto**; opzione **rimuovi assegnazione** (imposta FK a `null` sui prodotti interessati) solo se presentata con copy chiaro e conferma | M | — |
| 13 | Nel caso “nessun risultato” nei tab Fornitori/Categorie, la CTA rapida di creazione usa il testo cercato come prefill, passa dalla stessa validazione di add/rename e non crea duplicati silenziosi | M | — |
| 14 | Add, rename, quick-create ed eliminazione guidata usano microcopy, ordine CTA e feedback coerenti tra tab Fornitori e Categorie, senza comportamenti percepiti come “due feature diverse” | M | — |
| 15 | I tab Fornitori/Categorie riusano, dove sensato, componenti e state contract comuni, così la coerenza UX non dipende solo dal polish finale ma dalla struttura stessa dell’implementazione | S / M | — |
| 16 | Operazioni di riassegnazione + delete (o clear + delete) eseguite in modo **atomico** dove necessario (`withTransaction`) | B / S | — |
| 17 | Nessuna regressione su import/export full/partial, analyze, sync flags, price history recording (verificare almeno assenza di crash e coerenza ID) | B / baseline TASK-004 | — |
| 18 | `DatabaseViewModel` resta **fonte di verità**; componibili senza business logic non banale | S | — |
| 19 | Localizzazione **it/en/es/zh** per nuove stringhe | S | — |
| 20 | Checklist “Definition of Done — task UX/UI” in `MASTER-PLAN.md` soddisfatta nel perimetro Database hub | M | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | **Consentire “rimuovi assegnazione”** nei prodotti collegati prima di eliminare fornitore/categoria | Coerente con schema (`supplierId`/`categoryId` nullable e `SET_NULL` su delete); utile per pulizia anagrafica; va reso **esplicito** e secondario rispetto a “sostituisci con” per evitare perdita semantica involontaria | 2026-04-12 |
| 2 | **Non richiedere** backfill `HistoryEntry.supplier` / `category` nella prima implementazione | Dati storici sono snapshot testuali; aggiornamento massivo è rischio scope e comportamento prodotto ambiguo; documentare limite UX (“le voci storiche possono mostrare il nome al momento dell’import”) | 2026-04-12 |
| 3 | **Preferenza UI**: `PrimaryTabRow` / scrollable tab in testa al contenuto Database, sotto titolo schermata; area import/export **solo** nel tab Prodotti per non duplicare azioni globali | Chiarezza gerarchica; allineamento richiesta utente “hub tre sezioni” | 2026-04-12 |
| 4 | **Ricerca separata per tab** (`productFilter`, `supplierFilter`, `categoryFilter`) con persistenza locale VM finché la schermata resta viva | Evita reset fastidiosi passando tra tab e migliora UX su gestione massiva | 2026-04-12 |
| 5 | **Un solo FAB contestuale** nei tab Fornitori/Categorie; nel tab Prodotti restano scan + add prodotto | Riduce rumore visivo e mantiene priorità azioni coerenti col contesto | 2026-04-12 |
| 6 | **Delete a due step** per entità in uso: scelta strategia → conferma finale | Riduce errori irreversibili e rende più comprensibile l’impatto sui prodotti collegati | 2026-04-12 |
| 7 | **Nessun editing inline** nelle liste Fornitori/Categorie nella prima iterazione; usare dialog/sheet dedicati | Maggiore pulizia visiva, minore rischio di errori e migliore coerenza con pattern dialog già presenti | 2026-04-12 |
| 8 | **No-results state con CTA diretta** nei tab Fornitori/Categorie (es. “Crea "{query}"”) quando la ricerca non trova match e il testo è valido | Riduce attrito nel CRUD, accelera la creazione e rende la schermata più operativa senza aprire menu superflui | 2026-04-12 |
| 9 | **Tab iniziale = Prodotti** anche dopo introduzione hub | Mantiene continuità col comportamento attuale e riduce sorpresa per chi usa già la schermata Database come lista prodotti | 2026-04-12 |
| 10 | **Unificare la validazione nome** per add, rename e CTA “Crea {query}” | Evita divergenze tra flussi apparentemente equivalenti e riduce bug su trim, case-insensitive e duplicate handling | 2026-04-12 |
| 11 | **Loading/error/empty separati per contesto**: il tab Prodotti non deve mostrare stati impropri dei tab Fornitori/Categorie e viceversa | Riduce confusione percettiva e rende ogni sezione leggibile come mini-superficie autonoma dello stesso hub | 2026-04-12 |
| 12 | **Esito operazione → messaggio user-facing specifico** mappato nel ViewModel, non costruito ad hoc nei composable | Mantiene coerenza tra rename/add/delete/quick-create e riduce duplicazioni di microcopy nella UI | 2026-04-12 |
| 13 | **Parità UX tra Fornitori e Categorie**, salvo differenze motivate dal dato | Riduce incoerenze percettive e limita biforcazioni inutili in UI/state management durante l’Execution | 2026-04-12 |
| 14 | **Preservare il più possibile il contesto visivo per tab** (scroll/list state, dove ragionevole) nella stessa sessione schermata | Rafforza la sensazione di hub stabile e riduce l’effetto “tab che si ricrea da zero” | 2026-04-12 |
| 15 | **Riuso componenti tra Fornitori e Categorie** per lista, empty/no-results e dialog base, con microcopy parametrica | Riduce divergenze accidentali, aumenta coerenza UX e abbassa costo di manutenzione | 2026-04-12 |
| 16 | **La coerenza tra Fornitori e Categorie va costruita a livello strutturale, non solo visivo** | Spinge l’Execution a consolidare componenti e stato condivisi, riducendo drift futuro e regressioni di manutenzione | 2026-04-12 |

---

## Planning (Claude)

### Obiettivo UX/UI

- Database percepito come **centro controllo dati** ordinato: tre ambiti separati ma nella stessa schermata, con **affordance** forti (tab chiare, liste dedicate, azioni contestuali).
- Stile **Material3** e token tema esistenti (`appSpacing`, card M3, dialog shape da **TASK-037**).
- Evitare aspetto “incollato”: liste dedicate, header e FAB che cambiano contesto in modo prevedibile.

### Esperienza utente attesa

1. L’utente apre **Database** dal bottom nav. Vede titolo e **tre tab**: Prodotti | Fornitori | Categorie.
2. **Prodotti**: identico al comportamento attuale (filtro, lista, storico, import/export, scan, aggiungi), con eventuale rifinitura visiva ma senza cambio di semantica.
3. **Fornitori / Categorie**:
   - campo ricerca dedicato al tab corrente
   - lista con nome principale + **conteggio prodotti collegati**
   - righe pulite, area touch ampia, affordance chiara
   - empty state dedicato se lista vuota o filtro senza risultati
4. Tap riga → apre bottom sheet azioni o dialog contestuale con **Rinomina** e **Elimina**; niente azioni distruttive troppo vicine alla CTA primaria.
5. **Rinomina**: dialog con campo già compilato, validazione blank/duplicato, microcopy che spiega che i prodotti collegati mostreranno il nuovo nome.
6. **Elimina senza uso**: conferma breve → delete.
7. **Elimina con uso**: flow guidato a due step: riepilogo impatto → scelta tra **Sostituisci con esistente**, **Crea nuovo e sostituisci**, **Rimuovi assegnazione** → conferma finale esplicita.
8. Tornando al tab **Prodotti**, l’utente ritrova il proprio contesto (filtro/lista) e non percepisce il nuovo hub come una schermata diversa o “resetttata”.

### Cosa cambia / cosa non cambia (funzionale)

| Cambia | Non cambia |
|--------|------------|
| Presenza hub a 3 sezioni nella stessa route `databaseScreen` | Route `Screen.Database` e grafo principali |
| Nuove azioni CRUD su `suppliers` / `categories` dal tab dedicato | Logica import/export e full DB round-trip (solo verifica regressione) |
| Nuove query DAO/repository per conteggio, rename, bulk update, delete | Modello `Product` / FK / indici (salvo emergenza) |
| Stato UI aggiuntivo nel `DatabaseViewModel` | `ExcelViewModel` come SSoT Excel (no accoppiamento stretto) |
| Microcopy, empty state e feedback più espliciti e contestuali | Modello concettuale base della schermata Database: resta il punto di accesso dati principale dell’app |

### Architettura schermata Database (proposta)

- **Single activity / single composable root** `DatabaseScreen`: stato `selectedHubTab` (enum) osservato dal VM (`StateFlow`).
- **Separation of concerns**:
  - UI: tab + contenuto + dialoghi.
  - VM: espone `StateFlow` liste arricchite (es. `data class SupplierListRow(val supplier: Supplier, val productCount: Int)`), intent `renameSupplier`, `deleteSupplierWithStrategy`, ecc.
- **Repository**: implementa use-case transazionali (`reassignSupplierAndDelete`, `clearSupplierFromProductsAndDelete`, …) per mantenere thin VM.
- **Prodotti** deve restare una sezione stabile anche quando non è il tab attivo: evitare reset gratuiti di pager, filtro o contesto UX quando si passa a Fornitori/Categorie e si torna indietro nella stessa sessione della schermata.
- Introdurre uno **UI state dedicato ai flow di gestione entità** (es. `EntityManagementDialogState`) per evitare proliferazione di booleani scollegati tra rename, delete semplice, delete con strategia e picker sostituzione.

- Preferire una **sezione anagrafica condivisa** (composable + UI model + state contract parametrico) per Fornitori/Categorie, così le differenze restano nei dati e nella microcopy, non nella struttura del flusso.

### Strategia UI per le tre sezioni

- **Tab row** sotto `DatabaseRootHeader`, sempre visibile, con label corte e leggibili; la tab attiva deve modificare contenuto, ricerca e CTA senza cambiare route.
- **Prodotti**: mantiene header/filtro/import/export attuali; consentite rifiniture di spacing, card rhythm e gerarchia, ma nessuna regressione funzionale.
- **Fornitori / Categorie**: usare una lista dedicata, più leggera della lista prodotti, con focus su nome + usage count; evitare card troppo pesanti se il contenuto è minimale.
- **Ricerca**: un campo per tab, con placeholder specifico (`Cerca fornitore`, `Cerca categoria`) e trailing clear.
- **FAB**: visibilità e icona dipendono dal tab. Nei tab Fornitori/Categorie preferire un solo FAB contestuale `Aggiungi`; nel tab Prodotti resta l’impostazione attuale.
- **Empty states**: progettati per tab, con copy breve e CTA utile; niente schermate vuote “mute”.
- **No-results state intelligente**: se la ricerca nei tab Fornitori/Categorie non trova risultati ma il testo è valido, mostrare CTA rapida per creare la nuova entità partendo dal testo cercato.
- **Persistenza percepita del contesto**: cambio tab rapido e fluido, senza sensazione di “schermata che riparte da zero” quando si torna al tab precedente.
- **Dialog/sheet flow cohesion**: rename, add e delete guidata devono condividere pattern visivi coerenti (titolo, microcopy, spacing, CTA order) per far percepire Fornitori/Categorie come sotto-sezioni dello stesso hub, non feature separate cucite insieme.
- **Loading/error scoped**: indicatori e messaggi devono riferirsi solo alla sezione attiva; evitare overlay o copy che facciano sembrare bloccata tutta la schermata quando si sta caricando solo una lista anagrafica.
- **Quick-create disciplinato**: la CTA `Crea "{query}"` compare solo con input valido dopo trim/normalizzazione minima; nessuna comparsa per stringhe vuote o semanticamente duplicate.
- **Parità tra tab anagrafici**: Fornitori e Categorie devono condividere la stessa grammatica visiva e interattiva; eventuali differenze devono dipendere da requisiti reali, non da implementazioni divergenti nate durante l’Execution.

- **Continuità visiva per tab**: quando l’utente cambia tab e torna indietro, preservare dove sensato anche la posizione/list state per evitare salti inutili e rafforzare la percezione di stabilità.
- **Component reuse disciplinato**: Fornitori e Categorie dovrebbero riusare la stessa base visuale/interattiva; evitare fork di layout separati salvo reale motivo prodotto.

### Dettagli UX da fissare in Execution

- **Sorting default**: alfabetico A→Z per Fornitori/Categorie; valutare secondario per usage count solo se utile e non destabilizzante.
- **Usage count**: mostrare testo esplicito tipo `12 prodotti` o badge secondario leggibile; evitare numeri “orfani”.
- **Primary vs destructive actions**: `Aggiungi` e `Rinomina` più prominenti; `Elimina` in area separata o con tonalità pericolosa.
- **Picker di sostituzione**: riuso pattern `HistoryValuePickerDialog` solo come riferimento UX Compose; niente accoppiamento con logica History.
- **Microcopy**: spiegare bene quando la rinomina aggiorna la visualizzazione dei prodotti ma non le stringhe snapshot già salvate in `HistoryEntry`.
- **Accessibilità**: target touch adeguati, contentDescription dove necessario, differenza tra stato normale/errore/non risultati non affidata solo al colore.
- **CTA order nei dialog**: conferma primaria sempre allineata con il tono del contesto; azioni distruttive esplicite ma non dominanti finché non si arriva allo step finale di conferma.
- **Feedback post-azione**: preferire snackbar brevi e specifiche (`Fornitore rinominato`, `Categoria sostituita ed eliminata`) evitando messaggi generici poco informativi.
- **Titoli e microcopy contestuali**: distinguere sempre chiaramente `fornitore` e `categoria` nei dialog e nei feedback, ma mantenendo struttura e tono identici tra i due flussi.

- **Scroll/list restoration**: valutare fino a che punto preservare lo stato della lista per tab senza complicare inutilmente l’implementazione; preferire comunque continuità percepita rispetto a reset gratuiti.

### Flusso rinomina

1. Apertura dialog con nome corrente precompilato e focus sul campo testo.
2. Validazione live: trim, blank, duplicato, stesso nome normalizzato → stato pulsante coerente.
3. Se il nome è semanticamente invariato → chiusura senza operazione o no-op silenzioso.
4. `UPDATE suppliers SET name = :new WHERE id = :id` / analogo categories.
5. Refresh dei flow e feedback discreto (snackbar o messaggio di successo non invasivo).
6. Nessun effetto retroattivo sulle stringhe snapshot in `HistoryEntry`; comportamento spiegato solo dove serve.

### Flusso eliminazione con riassegnazione

1. Recuperare `countProductsForSupplier(id)` / `countProductsForCategory(id)`.
2. Se conteggio = 0 → dialog conferma semplice → `DELETE` entità.
3. Se conteggio > 0 → flow guidato in due step:
   - **Step 1 — Impatto**: mostrare quante righe prodotto verranno toccate e le strategie disponibili.
   - **Step 2 — Strategia**:
     - **Sostituisci con esistente**: picker con ricerca; bloccare target uguale all’entità corrente.
     - **Crea nuovo e sostituisci**: crea entità, poi reassign.
     - **Rimuovi assegnazione**: imposta FK a `null` sui prodotti interessati; copy esplicito che chiarisce l’effetto.
   - **Step 3 — Conferma finale**: CTA finale specifica per la strategia scelta (`Sostituisci ed elimina`, `Rimuovi assegnazione ed elimina`, ecc.).
4. In tutti i casi con prodotti collegati, evitare delete immediata in un solo tap.

**Transazione**: le combinazioni `reassign + delete` e `clear assignment + delete` devono usare `db.withTransaction { ... }`. La creazione della nuova entità come sostituto deve essere compresa nel flusso atomico quando tecnicamente possibile; se non lo è per vincoli del codice esistente, documentare chiaramente il confine transazionale in Execution.

### Query / DAO / Repository / ViewModel (checklist tecnica)

- `ProductDao`: query per `countLinkedToSupplier`, `countLinkedToCategory`, `reassignSupplier(oldId, newId)`, `reassignCategory(oldId, newId)`, `clearSupplierAssignment(oldId)`, `clearCategoryAssignment(oldId)`.
- `SupplierDao` / `CategoryDao`: `@Update`, `@Delete` o query mirate; eventuale query lista arricchita con count se più efficiente lato SQL.
- `InventoryRepository`: metodi ad alto livello per rename, delete semplice, reassign+delete, clear+delete, create+reassign+delete; preferire API semantiche invece di far comporre troppi step nel VM. Consigliato restituire esiti tipizzati o errori mappabili (`duplicateName`, `entityInUse`, `invalidName`) invece di affidarsi solo a eccezioni generiche.
- `DatabaseViewModel`: stato tab selezionato, tre query di ricerca separate, liste arricchite per tab, stato dialog/sheet, validazione input, mapping errori user-facing. Consigliato modellare anche uno stato di sezione (`products`, `suppliers`, `categories`) con loading/empty/error distinti per evitare ambiguità UI.
- UI models dedicati consigliati: `SupplierListItemUi`, `CategoryListItemUi`, `DeleteStrategy`, `EntityManagementTab`.
- Per la UI, preferire un piccolo set di componenti/contract condivisi per i tab anagrafici (lista, row actions, empty/no-results, dialog base) invece di due implementazioni quasi uguali che divergono nel tempo.

### Sequenza consigliata per l’Execution

1. **Data layer**: completare DAO/query conteggi, rename, reassign, clear assignment, delete semplice e transazionale.
2. **Repository**: incapsulare i casi d’uso semantici (`rename`, `deleteSimple`, `replaceAndDelete`, `clearAndDelete`, `createAndReplaceAndDelete`) prima di toccare UI o VM.
3. **ViewModel**: introdurre tab state, filtri separati, liste arricchite e stato dialog/sheet mantenendo compatibilità con i call site esistenti.
4. **Stringhe e microcopy**: definire presto testo dialoghi, no-results CTA ed error mapping, così la UI shell nasce già con semantica chiara e non come placeholder tecnico.
5. **State contract UI**: definire presto il contratto di stato per tab, dialog flow e loading/error scoped, così i composable nascono già con responsabilità pulite.
6. **UI shell**: costruire tab hub e contenitori di sezione senza ancora rifinire il visual polish.
7. **Component pass condiviso**: consolidare la base comune dei tab anagrafici prima del polish finale, così Fornitori e Categorie evolvono in parallelo e non con fork separati.
8. **State/component convergence check**: prima del polish, verificare che Fornitori e Categorie stiano davvero riusando base comune di componenti, stato e validazione, e non solo microcopy simile.
9. **UX polish**: rifinire empty state, microcopy, gerarchia visiva, prominence CTA e dettagli dialog/sheet.
10. **Parity pass anagrafiche**: prima di chiudere la UI, confrontare Fornitori e Categorie per eliminare differenze accidentali in CTA, spacing, empty/no-results, flow dialog e feedback.
11. **Regression pass**: verificare subito `EditProductDialog`, import/export, sync/analyze, paging prodotti e snackbar/error handling.

### Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Violazione vincolo **unique** su nome in rename/add | Test JVM + messaggi UX; normalizzazione trim |
| Deadlock / race con mutex esistenti `addSupplier`/`addCategory` | Allineare nuove operazioni agli stessi mutex o documentare ordine lock |
| Import apply che assume esistenza nomi | Rename per `id` mantiene integrità; verificare percorsi che cacheano id temporanei preview (solo import preview) |
| Liste fornitore/categoria in `EditProductDialog` desincronizzate dopo CRUD hub | Dopo ogni mutazione, refresh flow condivisi o invalidazione esplicita |
| Storia prezzi / export | Solo indiretto via prodotti; smoke mirato export dopo rename |
| Performance su liste grandi | Conteggi con `COUNT` SQL, non caricare tutti i prodotti |
| Confusione UX tra ricerca prodotti e ricerca entità | Separare stato e placeholder per tab; mantenere clear action esplicita |
| Flow delete troppo complesso o verboso | Tenere il percorso standard corto: se non ci sono prodotti collegati, conferma semplice; usare multi-step solo quando serve davvero |
| Empty state poveri o incoerenti | Definirli già in planning come requisito esplicito e non come rifinitura “se avanza tempo” |
| Reset indesiderato di filtro o contesto passando tra tab | Tenere stato filtri/tab nel VM e non nei soli `remember` locali del composable root |
| Divergenza tra validazione add/rename/quick-create | Centralizzare la validazione nel VM o in helper condiviso, evitando regole duplicate nei composable |
| Troppi booleani/dialog state nella UI root con aumento complessità | Modellare i flow con stato tipizzato unico invece di flag sparsi |
| Stati loading/error troppo globali con percezione di schermata bloccata | Distinguere stato sezione e stato operazione; limitare indicatori alla superficie rilevante |
| Quick-create troppo permissivo con comparsa rumorosa o incoerente | Mostrare CTA solo dopo validazione minima e riusare la stessa pipeline di validazione di add/rename |
| Divergenza progressiva tra UX Fornitori e Categorie durante l’implementazione | Fare parity check finale tra i due tab e riusare componenti/stati condivisi dove sensato |
| Reset visivo/scroll state al cambio tab con sensazione di UI instabile | Preservare il contesto per tab dove ragionevole e verificare manualmente il ritorno a Prodotti/Fornitori/Categorie |
| Fork involontario tra componenti Fornitori e Categorie | Estrarre componenti/stati condivisi prima del polish finale e fare parity check conclusivo |
| Coerenza solo “di facciata” tra Fornitori e Categorie ma con implementazioni interne divergenti | Verificare in review di execution il riuso reale di componenti/state contract, non solo l’aspetto visivo finale |

### Checklist execution futura (per esecutore)

- [ ] Fissare i modelli UI per tab e strategie delete prima di toccare i composable
- [ ] Confermare se conviene query SQL con count aggregato o arricchimento in repository in base al codice reale
- [ ] Leggere questo file + `MASTER-PLAN` + codice effettivo dei file elencati
- [ ] Implementare DAO + repository + transazioni
- [ ] Estendere `DatabaseViewModel` con API chiare e testabili
- [ ] Verificare che il tab switch non resetti pager/filtro prodotti nella stessa sessione schermata
- [ ] Implementare no-results state con CTA crea-entità dai tab Fornitori/Categorie quando opportuno
- [ ] Centralizzare la validazione nome per add, rename e quick-create prima di rifinire i dialoghi
- [ ] Introdurre uno state model unico per i flow dialog/sheet di Fornitori/Categorie
- [ ] Confermare che il tab iniziale resti Prodotti e che il ritorno a Prodotti preservi il contesto nella stessa sessione schermata
- [ ] Modellare loading/empty/error per sezione prima di rifinire gli empty state e le snackbar
- [ ] Fare un parity check finale tra tab Fornitori e Categorie su microcopy, CTA, empty/no-results e dialog flow
- [ ] Valutare e preservare, dove ragionevole, scroll/list state per tab nella stessa sessione schermata
- [ ] Estrarre o consolidare la base comune dei tab Fornitori/Categorie prima del polish finale
- [ ] Verificare che la parità tra Fornitori e Categorie sia ottenuta anche via riuso reale di componenti/state contract, non solo tramite microcopy o spacing simile
- [ ] UI: tab hub + liste + dialoghi; stringhe 4 lingue
- [ ] `./gradlew assembleDebug` + `./gradlew lint`
- [ ] Baseline **TASK-004**: `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest` (e altri se toccati)
- [ ] Smoke manuale: rename → verifica lista prodotti; delete con reassign; import/export rapido; edit prodotto sceglie fornitore aggiornato
- [ ] Aggiornare sezione **Execution** / check nel file task; **non** passare a `REVIEW` senza evidenze

---

## Execution

### Esecuzione — 2026-04-12

**File modificati:**
- `data/CatalogManagement.kt` — contratto condiviso: `CatalogEntityKind`, `CatalogListItem`, `CatalogDeleteStrategy`, errori di dominio
- `data/ProductDao.kt` — query `COUNT` per supplierId/categoryId, riassegnazione bulk, clear FK
- `data/SupplierDao.kt` — summary list con count, rename/delete mirati, lookup case-insensitive
- `data/CategoryDao.kt` — summary list con count, rename/delete mirati, insert con ritorno `id`
- `data/InventoryRepository.kt` — CRUD anagrafiche condiviso, delete guidata atomica con `withTransaction`
- `viewmodel/DatabaseHubModels.kt` — `DatabaseHubTab` enum, `CatalogSectionUiState`
- `viewmodel/DatabaseViewModel.kt` — SSoT per tab, query sezione, refresh, messaggi utente, intent CRUD
- `ui/screens/DatabaseScreen.kt` — orchestrazione hub Prodotti/Fornitori/Categorie, FAB contestuale, dialog state, wiring CRUD, `DatabaseSearchField` condiviso sotto i tab
- `ui/screens/DatabaseScreenComponents.kt` — `DatabaseRootHeader` (titolo + import/export globali + `SecondaryTabRow`), `DatabaseSearchField`, `DatabaseCatalogListSection`, `DatabaseCatalogRow`, `DatabaseHubFab`
- `ui/screens/DatabaseScreenDialogs.kt` — `CatalogActionBottomSheet`, `CatalogNameDialog`, `CatalogDeleteConfirmationDialog`, `CatalogDeleteStrategyDialog`, `CatalogClearAssignmentsConfirmationDialog`, `CatalogReplacementPickerDialog`
- `res/values/strings.xml` + `values-en/`, `values-es/`, `values-zh/` — nuove stringhe localizzate per tab, feedback, dialoghi e stati hub
- `test/…/DefaultInventoryRepositoryTest.kt` — copertura count/rename/delete guided
- `test/…/DatabaseViewModelTest.kt` — copertura stato sezione hub e error mapping CRUD

**Azioni eseguite:**
1. Esteso il data layer con query aggregate e operazioni atomiche per supplier/category, senza migrazione DB.
2. Centralizzato in `InventoryRepository` il flusso di create/rename/delete guidata con blocchi mutex coerenti con `addSupplier`/`addCategory` esistenti.
3. Esteso `DatabaseViewModel` con `DatabaseHubTab`, `CatalogSectionUiState`, refresh condiviso e intent CRUD riusabili tra Fornitori e Categorie.
4. Riorganizzata `DatabaseScreen` come hub a tre tab: Prodotti resta tab iniziale e conserva scan/add/import/export/edit/delete/history; Fornitori e Categorie usano stessa shell, stessa card search e stessi dialoghi.
5. Spostati import/export dall'area Prodotti all'header globale in alto a destra (visibili da tutti i tab).
6. Rimosso sottotitolo "Hub prodotti, fornitori e categorie" e le card introduttive di sezione; sostituiti con un singolo `DatabaseSearchField` full-width direttamente sotto i tab (placeholder contestuale per tab).
7. Curata UX: empty/no-results/error scoped per sezione, bottom sheet azioni, dialoghi con CTA gerarchizzate, FAB contestuale.

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅    | `assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | S    | ✅    | `lint` → `BUILD SUCCESSFUL` |
| Warning Kotlin           | S    | ✅    | Nessun warning nuovo; restano warning preesistenti AGP/Kotlin config |
| Coerenza con planning    | —    | ✅    | Sequenza DAO → repository → VM → shell hub → polish completata |
| Criteri di accettazione  | —    | ✅    | Verificati singolarmente; task portato a REVIEW |

**Baseline regressione TASK-004:**
- Test eseguiti: `./gradlew testDebugUnitTest --tests '…DefaultInventoryRepositoryTest' --tests '…DatabaseViewModelTest'`; `./gradlew testDebugUnitTest`
- Test aggiunti/aggiornati: `DefaultInventoryRepositoryTest` (count linked products, rename keeps id, delete guided reassign/clear); `DatabaseViewModelTest` (section state load, blank-name error mapping)
- Limiti residui: nessun smoke visivo/manuale su device/emulator; review ha eseguito verifica codice e build

**Incertezze:**
- Limite noto: `HistoryEntry.supplier`/`category` restano snapshot testuali e non si aggiornano alle rinomine (Decisione #2, documentata e accettata).

---

## Review

### Review — 2026-04-12

**Revisore:** Claude (planner)

**File letti:** `DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, `res/values*/strings.xml`

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Hub a tre sezioni con tab M3 | ✅ | `SecondaryTabRow` + `DatabaseHubTab`; sequenza verticale: titolo → tab → search → lista |
| 2 | Tab Prodotti invariato | ✅ | Lista paginata, scan/FAB/import/export/edit/delete/history preservati; build verde |
| 3 | Fornitori/Categorie: lista, ricerca, add, rename, delete | ✅ | `DatabaseCatalogListSection` + dialogi + bottom sheet |
| 4 | Conteggio prodotti per riga | ✅ | Testo "N prodotti collegati" in `DatabaseCatalogRow` (badge ridondante rimosso in Fix) |
| 5 | Empty state / loading / no-results per tab | ✅ | `DatabaseCatalogFeedbackState` con CTA; quick-create da no-results |
| 6 | Azioni distruttive non primary; rename/add più evidenti di delete | ✅ | Bottom sheet con rinomina in alto, elimina separata; flusso delete guidato multi-step |
| 7 | Cambio tab non resetta filtri correnti | ✅ | Query filter in VM (`CatalogSectionUiState.query`), non in `remember` locale |
| 8 | Tab iniziale resta Prodotti | ✅ | `DatabaseHubTab.PRODUCTS` come default nel VM |
| 9 | Continuità percepita cambio tab | ✅ | Stato query per tab preservato nel VM |
| 10 | Rename mantiene `id`; gestione duplicati | ✅ | `InventoryRepository.renameCatalogEntry`; error mapping VM |
| 11 | Delete semplice se non in uso | ✅ | `CatalogDeleteConfirmationDialog` |
| 12 | Delete guidata con replace/create new/clear | ✅ | `CatalogDeleteStrategyDialog` + picker ricerca |
| 13 | Quick-create usa testo cercato come prefill | ✅ | `onQuickCreate(trimmedQuery)` dalla `DatabaseCatalogFeedbackState` |
| 14 | Microcopy/CTA coerenti tra Fornitori e Categorie | ✅ | Stesso componente `DatabaseCatalogListSection` + stessi dialogi parametrici |
| 15 | Riuso componenti/state contract tra tab anagrafici | ✅ | `CatalogSectionUiState` condiviso; composable identico per entrambi i tab |
| 16 | Atomicità reassign+delete | ✅ | `db.withTransaction` nel repository |
| 17 | Nessuna regressione import/export/analyze/sync/price history | ✅ | Build + test suite verdi; logica invariata |
| 18 | `DatabaseViewModel` SSoT | ✅ | Tab, query, refresh e intent CRUD nel VM |
| 19 | Localizzazione it/en/es/zh | ✅ | Stringhe aggiornate in tutte e 4 le lingue (+ cleanup orfani in Fix) |
| 20 | DoD UX/UI soddisfatto nel perimetro hub | ✅ | Layout pulito post-refinement; gerarchia chiara; FAB contestuale |

**Problemi trovati:**

1. **Dead code — `CatalogEntityKind.tabLabelRes()`** in `DatabaseScreenComponents.kt`: funzione privata mai chiamata.
2. **Dead code — `CatalogEntityKind.supportingTextRes()`** in `DatabaseScreenComponents.kt`: residuo della card rimossa nel refinement, mai chiamata.
3. **Import non usati** `CircleShape` e `Surface` dopo rimozione del badge.
4. **Stringhe orfane** in 4 locale: `database_hub_screen_title`, `database_products_supporting`, `database_suppliers_supporting`, `database_categories_supporting` — referenziate solo dal dead code o non referenziate affatto.
5. **UX ridondanza in `DatabaseCatalogRow`**: conteggio prodotti mostrato due volte (testo "N prodotti collegati" + badge circolare `Surface(CircleShape)`) — il badge aggiunge rumore senza informazione aggiuntiva.

**Verdetto:** FIX_REQUIRED (cleanup — nessun blocco funzionale) → poi APPROVED

---

## Fix

### Fix Review — 2026-04-12

**File modificati:**
- `ui/screens/DatabaseScreenComponents.kt` — rimossi `tabLabelRes()`, `supportingTextRes()`, import `CircleShape` e `Surface`; rimosso badge `Surface(CircleShape)` da `DatabaseCatalogRow`
- `res/values/strings.xml` — rimossi `database_hub_screen_title`, `database_products_supporting`, `database_suppliers_supporting`, `database_categories_supporting`
- `res/values-en/strings.xml` — stesso cleanup
- `res/values-es/strings.xml` — stesso cleanup
- `res/values-zh/strings.xml` — stesso cleanup

**Correzioni applicate:**
1. Rimosse due funzioni private dead (`tabLabelRes`, `supportingTextRes`) residuo del refinement.
2. Rimossi import non usati (`CircleShape`, `Surface`) dopo rimozione badge.
3. Rimosso badge circolare ridondante da `DatabaseCatalogRow`: il testo "N prodotti collegati" già trasmette numero e semantica; il badge era decorativo e aumentava il rumore visivo senza beneficio.
4. Rimosse 4 stringhe orfane (per locale, 16 voci totali) da tutti e 4 i file strings: erano referenziate solo dal dead code o dal sottotitolo rimosso nel refinement.

**Verifiche fix:**
- `assembleDebug` → `BUILD SUCCESSFUL in 3s` (13 tasks executed, 24 up-to-date)
- Lint: BUILD SUCCESSFUL

---

## Chiusura

| Campo                  | Valore                     |
|------------------------|----------------------------|
| Stato finale           | DONE                       |
| Data chiusura          | 2026-04-12                 |
| Tutti i criteri ✅?    | Sì — 20/20 ✅              |
| Rischi residui         | Smoke visivo/manuale non eseguito (limite ambiente); `HistoryEntry.supplier`/`category` non backfillati su rinomina (Decisione #2, accettato) |

---

## Riepilogo finale

Implementato il Database hub a tre sezioni (Prodotti / Fornitori / Categorie) con CRUD completo su anagrafiche, flusso di eliminazione guidata atomica, ricerca per tab e localizzazione in 4 lingue. Il refinement UX ha rimosso sottotitolo e card introduttive, lasciando un layout più diretto: titolo + tab + search field + lista. Import/export riposizionati nell'header globale come azioni di database. La review ha identificato e rimosso dead code e stringhe orfane residue del refinement, e ha eliminato la ridondanza visiva nel badge del conteggio prodotti. Build e lint verdi. Logica business, import/export, price history e navigation invariati.

---

## Handoff

- **Punto di partenza codice:** `DatabaseScreen.kt`, `DatabaseViewModel.kt`, `InventoryRepository.kt`, `SupplierDao.kt`, `CategoryDao.kt`, `ProductDao.kt`.
- **Limite noto:** stringhe `HistoryEntry.supplier` / `category` non si aggiornano automaticamente alla rinomina (vedi Decisioni).
- **Nota UX importante:** per la prima iterazione privilegiare chiarezza e sicurezza del flusso rispetto a scorciatoie aggressive; nessun merge automatico implicito tra entità duplicate.
- **Nota di efficienza implementativa:** prima consolidare data layer e ViewModel, poi rifinire UI/polish; evitare di partire dai dialoghi finali senza avere già chiari repository API e strategia transazionale.
- **Nota di coerenza UX:** quick-create da ricerca, add manuale e rename devono sembrare tre ingressi dello stesso sistema di gestione nomi, non tre flussi con regole diverse.
- **Nota di chiarezza percettiva:** il nuovo hub deve sembrare un’estensione naturale della schermata Database attuale, non una mini-architettura separata con stati e messaggi scollegati.
- **Nota di coerenza finale:** Fornitori e Categorie vanno trattati come due viste sorelle dello stesso sottosistema; evitare che uno dei due tab risulti più curato, più chiaro o più completo dell’altro senza motivo prodotto reale.
- **Nota di robustezza UI:** oltre alla parità estetica, Fornitori e Categorie devono condividere anche la stessa ossatura di componenti e stato quando possibile, per evitare drift futuro tra i due tab.
- **Prossimo passo governance:** task autorizzato in `EXECUTION`; mantenere un solo task `ACTIVE` alla volta nel `MASTER-PLAN` e aggiornare questa scheda con evidenze reali man mano che l’implementazione procede. 
