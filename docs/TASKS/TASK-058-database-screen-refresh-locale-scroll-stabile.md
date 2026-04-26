# TASK-058 — DatabaseScreen: refresh locale prodotto modificato e scroll stabile

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-058 |
| Stato | **DONE** |
| Priorità | **ALTA** |
| Area | UX/database — `DatabaseScreen`, Paging 3, `DatabaseViewModel`, `InventoryRepository`, `ProductDao` |
| Creato | 2026-04-25 |
| Ultimo aggiornamento | 2026-04-25 — **Review planner APPROVED senza fix**; build/lint/test JVM + targeted verdi; nessuna modifica aggiuntiva richiesta; task chiuso `DONE`. |

*Task in fase `DONE`: review planner repo-grounded completata e approvata; nessun fix necessario; check verdi ripetuti dal planner.*

---

## Dipendenze

- **TASK-003** `DONE` — decomposizione `DatabaseScreen` (modularizzazione componenti).
- **TASK-004** `DONE` — baseline test repository/ViewModel: aggiornare/estendere se la logica cambia.
- **TASK-015** `DONE` — UX `DatabaseScreen` (non sostituito: questo task è fix mirato stale + scroll).
- **TASK-055** `PLANNING` (audit Supabase) — perimetro remoto: solo verifica/integrazione documentata, niente scope creep.

---

## Scopo

Correggere la **visualizzazione obsoleta** in `DatabaseScreen` dopo salvataggio prodotto da `EditProductDialog`: la card in lista e i campi del dialog devono mostrare **immediatamente** prezzi e metadati coerenti con Room/storico prezzi, **senza** dipendere da un secondo update o da refresh indiretto.

Mantenere **Paging 3** e **stabilire lo scroll** della lista: un update singolo non deve azzerare la posizione né forzare refresh globali inutili.

---

## Obiettivo UX / UI

- Dopo "Salva" su un prodotto esistente, l’utente vede subito in lista i prezzi (e quanto serve alla card) allineati al DB.
- Riapertura immediata dello stesso prodotto: i TextField / stato dialog riflettono i valori appena salvati, in linea con "Ultimo/Precedente" e `product_price_summary`.
- La lista **non salta in cima** per effetto del solo aggiornamento visivo del prodotto corrente.

---

## Problema osservato (bug)

- Dopo modifica e salvataggio da **Modifica prodotto**, il dialog si chiude ma la **card** nella lista continua a mostrare (es.) il **prezzo vendita precedente**.
- Riaprendo subito lo stesso prodotto, i **TextField** possono ancora mostrare il valore vecchio, mentre l’indicazione **"Ultimo"** (o equivalente nello storico) risulta già coerente con il nuovo valore — sintomo di **dati persistiti** ma **UI/lista ancora su snapshot Paging** non aggiornato.
- A volte, dopo un **altro** aggiornamento o un **refresh indiretto**, anche il primo prodotto si aggiorna: coerente con **list item stale** anziché con persistenza fallita.
- Sospizione UX: eventuali **refresh** che ricreano la `LazyColumn` o il `Pager` possono **resettare lo scroll** in cima, peggiorando l’esperienza in lista lunga.

---

## Contesto codice (audit statico — PLANNING)

Fonti lette in questa fase per ancorare ipotesi e perimetro:

| File | Rilevanza |
|------|-----------|
| `ui/screens/DatabaseScreen.kt` | `pager.collectAsLazyPagingItems()`; `rememberLazyListState()` dentro `key(filter.orEmpty())` (scroll legato al filtro, non a ogni `uiState`); `EditProductDialog` con `itemToEdit: Product?`; on save `updateProduct` + `itemToEdit = null`. |
| `ui/screens/DatabaseScreenComponents.kt` | `DatabaseProductListSection` — `products[idx]` come `ProductWithDetails`; `ProductRow(productDetails = details, ...)`; `onClick` / storico con `currentProduct = details.productWithCurrentPrices()`. |
| `ui/screens/EditProductDialog.kt` | **Audit mirato in EXECUTION:** inizializzazione stati locali TextField da `product`; `remember` / `rememberSaveable` e **eventuali key** (es. solo `product.id` troppo deboli — dopo Salva l’`id` è uguale ma i campi DB sono cambiati); rischio: TextField restano su valori in-memory mentre `ProductWithDetails` da lista è già corretto o viceversa. Se serve, key più robusta o re-sync locale quando cambiano (almeno) `purchasePrice`, `retailPrice`, `productName`, `supplierId`, `categoryId`, `stockQuantity` (elenco vincolante da validare sul codice reale). |
| `viewmodel/DatabaseViewModel.kt` | `pager = filter.flatMapLatest { Pager(...).flow.cachedIn(...) }` su `getProductsWithDetailsPaged(filterStr)`; `updateProduct` chiama `repository.updateProduct` e emette `UiState.Success` **senza** refresh esplicito del paging né reload del dettaglio. |
| `data/ProductWithDetails.kt` | `currentRetailPrice` / `currentPurchasePrice` da `lastRetail` / `lastPurchase` con fallback a `product.retailPrice` / `product.purchasePrice` — se il paging item è **stale**, l’intera riga (e `productWithCurrentPrices()`) resta stantia. |
| `data/ProductDao.kt` | `getAllWithDetailsPaged(filter)` e già `findDetailsByBarcode` con stessa struttura SELECT (join suppliers, categories, `product_price_summary`). **Non** esiste ancora get-by-id con la stessa shape della lista paginata. |
| `data/InventoryRepository.kt` + `DefaultInventoryRepository` | `updateProduct` aggiorna prodotto, inserisce storico prezzi, `notifyProductCatalogChanged` — nessun bridge verso l’item corrente della lista paginata. |
| Test | `DefaultInventoryRepositoryTest` (es. `updateProduct` e price history), `DatabaseViewModelTest` (`updateProduct` emette success e verifica `repository.updateProduct`) — vanno estesi/aggiornati se introduce `getProductDetailsById` e mappa override nel VM. |

Conclusione: la causa plausibile è la **natura Paging 3** (snapshot pagina non invalidata immediatamente per la singola riga) combinata a **assenza** di proiezione del nuovo `ProductWithDetails` nel layer UI finché non scatta un altro evento o refresh, e in parallelo un possibile **disallineamento** tra stato locale del **dialog** (TextField) e `Product` / `ProductWithDetails` passati dall’albero padre se le chiavi `remember` non riflettono i campi mutati.

---

## Ipotesi tecnica (da verificare in EXECUTION)

1. Dopo `updateProduct`, `Room` e `product_price_summary` possono essere corretti, ma **`LazyPagingItems` non sostituisce** l’oggetto `ProductWithDetails` già emesso per quell’indice.
2. `ProductWithDetails.currentRetailPrice` dipende da `lastRetail` e da `product.retailPrice`: se l’**item** paginato è vecchio, card e `productWithCurrentPrices()` usati per aprire il dialog restano stantii.
3. Un tentativo di **invalidare** tutto il `Pager` o di **bump** aggressivo dello stato Composable **può** far perdere la posizione di scroll se si alterano `key` o si ricostruisce l’albero lista senza criteri attenti.
4. **`EditProductDialog`:** se gli stati testo sono inizializzati una sola volta o con `rememberSaveable`/`remember` con **key solo `product.id`**, al **secondo** `LaunchedEffect`/apertura con lo stesso `id` ma **proprietà modificate** i TextField possono **non** re-inizializzarsi e mostrare ancora i valori digitati o cache vecchi; obiettivo: riaprire subito dopo Salva e vedere allineamento a `ProductWithDetails` / `getProductDetailsById` freschi.
5. **Async:** `updateProduct` termina in coroutine; la UI può chiudere il dialog **prima** del commit visivo sull’override; la card si aggiorna al completamento + `getProductDetailsById`, non "magicamente" al tap (vedi *Piano tecnico — race* e *Vincoli tecnici*).

**Soluzione preferita (contract di implementazione):** **override locali** nel `DatabaseViewModel` (`StateFlow` di mappa `productId -> ProductWithDetails` o equivalente) popolata **solo** dopo `repository.updateProduct` **riuscito** e `getProductDetailsById` post-*commit*; in `DatabaseProductListSection` (o chiamante) **merge** con l’item del paging. **Niente** `key()` aggiuntivo sull’override, su mappa, su `uiState` o contatori success che ricreino l’intera `LazyColumn` o il `LazyListState`.

Riferimenti posizione scroll esistente:

```98:100:app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt
    val productListState = key(filter.orEmpty()) { rememberLazyListState() }
    val supplierListState = key(supplierCatalogQuery) { rememberLazyListState() }
    val categoryListState = key(categoryCatalogQuery) { rememberLazyListState() }
```

---

## Vincoli tecnici (anti refresh globale, scroll, dialog)

*(Vincolanti in EXECUTION; allineati al contract “merge presentazionale puntuale”.)*

- **Non** usare `LazyPagingItems.refresh()` / **refresh Paging globale** come **soluzione principale** al solo `updateProduct` su una riga: evitare “agitar la lista” e reset percepito dello scroll.
- **Vietato** (salvo prova che non tocca lo `LazyListState` centrale `DatabaseScreen`): `key(overrideMap)`, `key(uiState)`, `key(successCount)` o token di refresh attorno a `LazyColumn` / wrapper che ricostruiscono la lista o il branch prodotti.
- `rememberLazyListState` (oggi: `key(filter) { ... }` in `DatabaseScreen.kt`) **non** deve diventare dipendente da override, contatori success, `uiState` né da refresh token: lo stato di scroll resta ancorato solo a quanto già legittimo (es. `filter` / query cataloghi).
- Il merge `override.get(id) ?: itemPaging` resta **presentazionale e per-singolo-item**; la logica di prezzi/DB resta in VM + repository + DAO.
- Dopo un singolo **Salva**, **non** deve essere **necessaria** alcuna chiamata a refresh globale Paging per vedere la card aggiornata (criterio di accettazione dedicato).
- **Nessun optimistic UI in questo task:** la mappa override **non** si aggiorna **prima** del **successo reale** di `repository.updateProduct` (o equivalente: solo dopo che l’I/O del repository segnala completamento senza eccezione per quell’update). Obiettivo: allineare la UI a **Room dopo commit**, non simulare il commit in anticipo.
- Se `updateProduct` **fallisce**: **non** pubblicare `ProductWithDetails` “nuovo” o **parziale**; **non** inserire/aggiornare override. Mantenere il comportamento esistente: `UiState.Error` + snackbar; la card resta com’era (nessun dato “nuovo” non persistito in lista).
- **Vietata** ogni tattica che mostri in card valori “salvati” **non** realmente scritti solo per reattività percepita (override prima del successo repository).

---

## Piano tecnico (EXECUTION)

1. **`ProductDao`:** aggiungere query puntuale (nome suggerito: `getDetailsById(productId: Long): ProductWithDetails?`) con **stessa** SELECT di `getAllWithDetailsPaged` (join `suppliers`, `categories`, `product_price_summary`) e `WHERE p.id = :productId` **LIMIT 1**. Riutilizzare le colonne e alias già usati in `findDetailsByBarcode` / paged.
2. **`InventoryRepository` / `DefaultInventoryRepository`:** `suspend fun getProductDetailsById(productId: Long): ProductWithDetails?` (IO, coerente con altri get).

   **Nota read-after-write (prodotti + prezzi + summary):** `getProductDetailsById` (che legge `products` + join + `product_price_summary` / viste coerenti con la card) va invocata dal VM **solo dopo** che `updateProduct` ha **finito** l’intero *write path* necessario a una card coerente, inclusi **eventuali** insert/aggiornamenti su `product_prices` e quanto alimenta `product_price_summary` oggi previsti da `updateProduct`. In EXECUTION, verificare se `DefaultInventoryRepository.updateProduct` è già **sufficientemente sequenziale/atomico** (stesso sospend senza ritornare “troppo presto”); **non** introdurre refactor transazionale ampio se non serve. **Vietato** chiamare la read del dettaglio **prima** che i write usati per la visualizzazione siano conclusi. Se emerse incoerenza `products` vs `product_prices` in race path, **documentarla** e applicare **fix minimo** nel **repository/DAO** (no duplicare logica nel Composable).
3. **`DatabaseViewModel`:** `StateFlow` (o `MutableStateFlow` interna + `asStateFlow`) per mappa **override** `productId -> ProductWithDetails` (nomi finali a carico esecutore, coerenti col codebase). **Ordine vincolante:** (a) chiamata `updateProduct` → **solo se** ritorno senza eccezione, (b) `getProductDetailsById`, (c) aggiornamento mappa, (d) `UiState.Success` / snackbar **come oggi**. In caso (a) fallisca, saltare (b)–(c) ed emettere `Error` **senza** toccare l’override. Vedi *Strategia cleanup mappa override* e *Race / asincronia*.
4. **`DatabaseScreen` / `DatabaseProductListSection`:** raccogliere la mappa; per ogni riga, `detailsEffettivi = override[id] ?: detailsPaging`. **Stesso** `detailsEffettivi` per: rendering `ProductRow` **e** **tutti** i callback della riga, così la UI non mostra prezzi “nuovi” e apre ancora un `Product` o storico collegato al paging **stale**.
   - **onClick / edit (EditProductDialog):** passare `Product` = `detailsEffettivi.productWithCurrentPrices()` (o equivalente unico da quel `ProductWithDetails`).
   - **onShowHistory / price history:** usare il prodotto coerente con `detailsEffettivi` (stesso criterio).
   - **delete / swipe delete:** l’`id` resta l’id vero; se il callback prende un `Product`, **preferire** l’oggetto derivato da `detailsEffettivi` per testo/nome/etichette, evitando riferimenti al solo `detailsPaging.product` quando esiste override.
   - **Anti-pattern da evitare:** card aggiornata (override) ma tap che apre edit/storico da snapshot paging non mergiato.
5. **`EditProductDialog`:** a valle dell’audit, se i TextField restano stantii con stesso `id`, correggere in modo **minimo** (es. `key` che include hash o versione dei campi elencati in *Contesto codice*, o `LaunchedEffect` su campi sorgente) **senza** redesign del layout dialog.
6. **`deleteProduct`:** rimuovere dalla mappa override la chiave per `product.id` eliminato (evita “ghost” o stale in memoria se il prodotto non esiste più). Vedi *Strategia cleanup*.
7. **Scroll:** invariata la regola: niente collegamento del `productListState` a override, `uiState` o mappa. Item **già** keyed per `product.id` nella lista resta accettabile; non forzare invalidazione di tutta la colonna.
8. **Import / full DB import:** se un flusso esistente forza **già** invalidazione Paging globale, **non peggiorare**; non introdurne di nuove per il solo save manuale.
9. **Add Product:** **non** agganciare il flusso “nuovo prodotto” alla mappa override **salvo** emerga in Execution lo stesso sintomo stale; dopo add, accettare il comportamento attuale di Paging/Room; smoke “aggiungi prodotto” in *Test*.

**Race / asincronia (Salva)**

- Oggi `onSave` in `DatabaseScreen` può azzerare `itemToEdit` **prima** che `updateProduct` in VM abbia finito. La **card** deve basarsi sull’override emesso **dopo** che la coroutine ha completato `updateProduct` **e** `getProductDetailsById` (ordinamento: persistenza, poi read coerente).
- **Niente** loading invasivo, **niente** ridisegno del pattern dialog, **niente** flussi o dialog di **conferma** aggiuntivi.
- **UX preferita (contract):** **chiusura dialog** come oggi, snackbar `Success` esistente, **override** appena pronto. Se in Execution il ritardo fino a card aggiornata è **percepibile**, è ammesso **solo** micro-feedback **locale** e in stile (es. disabilitare brevemente “Salva” al primo tap mentre parte il save). **Opzionale** in EXECUTION: stesso criterio + indicatore minimo sul bottone **solo** se già in linea con l’app. **Non** bloccare la chiusura del task con un nuovo modello di dialog o step extra.
- Se la card dovesse aggiornare 1–2 frame dopo la chiusura, documentare; se è visibilmente inaccettabile, preferire la micro-UX opzionale sopra.
- **Più `update` ravvicinati sullo stesso `productId`:** l’override visibile a fine sequenza deve corrispondere all’**ultimo salvataggio completato** con successo (ultimo `updateProduct` + `getProductDetailsById` che chiude con successo). **Mitigazione minima (senza infrastrutture pesanti):** aggiornamento **atomico** della mappa / `StateFlow` (es. sostituzione mappa o `update` in blocco in un’unica transizione per evitare read-modify-write “mezzo” su due coroutine); in alternativa, dopo ogni successo, rileggere da DB e pubblicare; se un completamento “vecchio” arriva dopo uno più recente, **scartare** o sovrascrivere in base al timestamp/ordine di completamento (documentare in Execution l’opzione scelta, rischio: race in microsecondi, impatto: tipicamente solo tap doppi anomali).
- **Rischio** concorrenza: documentare; **no** coda complessa, **no** attori aggiuntivi salvo chiarire nel log se si introduce solo `Mutex` sottile sull’id.

**Pull remoto (riferincroce)**

- Integrazione con i flussi sotto *Refresh locale anche dopo pull remoto / sync catalogo*; in EXECUTION: **prima** leggere il codice esistente (coordinator, callback `notify*`, eventi) **prima** di toccare i listener.

---

## Strategia cleanup mappa override (decisione consigliata in EXECUTION)

- **Scopo:** la mappa **non** è cache permanente long-lived: solo **stato `DatabaseViewModel` per la sessione** (processo+screen scope del VM, come da scelta architetturale consueta).
- **Rimozione su delete:** a `deleteProduct` riuscito, **rimuovere** `override[productId]`; evita voci orfane o riuso stale se un ID viene riassegnato in futuro lontano o in test.
- **Non** trattare gli override come sostituti del DB: sono **acceleratore UI** allineato a `getProductDetailsById` dopo eventi noti.
- **Crescita controllata (se serve):** opzioni non esclusive, sceglierne **una o due** a minimo costo, documentare nel log Execution:
  - cap esplicito, es. **ultimi N id** inserimenti/aggiornamenti (N suggerito **100**, tuning se necessario);
  - opzionale: depura parziale su **cambio filtro** o **cambio tab** hub (Prodotti/Fornitori/Categorie) *solo* se la mappa cresce e non si vuole M troppo grande in sessioni lunghe.
- **Vietato** come prima reazione: svuotare la mappa **immediatamente** dopo ogni `update` “perché il DB è già a posto” — comporterebbe il ritorno visivo allo **snapshot Paging stale** finché la pagina non si rinfresca.
- Rimuovere/aggiornare un override **quando** il dato paged risulta **allineato** (opzionale, bassa priorità): solo se c’è un criterio economico; altrimenti lasciare l’entry fino a cap/cleanup sessione (documentare scelta).

---

## Filtro attivo / membership della lista (edge)

**Caso tipico:** l’utente applica un **filtro** (barcode, nome, fornitore, categoria, ecc. come oggi in `getAllWithDetailsPaged`) e modifica **proprio** il campo su cui stava filtrando (o un campo che fa uscire la riga dalla clausula WHERE).

**Principi per TASK-058:**

- **Non** usare **refresh Paging globale** come **soluzione principale** a questo edge (allineato ai *Vincoli tecnici*).
- Se la riga è **ancora in vista** nello snapshot corrente, l’utente vede i dati aggiornati **subito** via **override** (stesso giro post-success), con **scroll stabile** (niente forzare `key` sull’intera colonna per “invalidare” il filtro).
- **Coerenza piena** col filtro (es. scomparire dalla lista sotto un filtro che non include più la riga) può avvenire al **prossimo cambio filtro**, al **refresh naturale** del Paging, o a meccanismi esistenti — **salvo** che in Execution si trovi una **soluzione puntuale e piccola** (es. nascondere/rimuovere visivamente quell’item se non matcha più) **senza** duplicare in Compose logica **SQL** della query Room e **senza** resettare lo scroll. Se si implementa, **documentare** il comportamento nel log Execution.
- Evitare duplicazione fragile del predicato `WHERE` tra VM e Composable: preferire merge override + ogni altra leva già centralizzata.

---

## Refresh locale anche dopo pull remoto / sync catalogo

**Obiettivo (best effort controllato in TASK-058):** se il **pull** o **sync catalogo (cloud/Supabase, realtime, ecc.)** modifica **prodotti locali** già in lista, l’utente vede le card dei soli interessi aggiornate **senza** refresh Paging **globale** aggressivo e **senza** saltare lo scroll, **quando** si conoscono gli identificativi toccati.

**Posizione rispetto al fix manuale:** il percorso **update prodotto da UI** (dialog) resta **obbligato** a chiudere in questo task. Il refresh puntuale post-pull è **best effort:** implementarlo **solo** se **ID/barcode mutati** sono **già** disponibili o **ricavabili con cambi minimi** (pochi file, niente redesign sync). Se servisse **refactor ampio** sync/cloud, lasciare **N/A** motivato, rimandare a **TASK-055** o task dedicata, e **non** ritardare la chiusura del fix manuale in attesa del remoto.

**Processo in EXECUTION (ordine vincolante):**

1. **Mappare il flusso reale** già in repo: coordinator sync, `InventoryRepository` / `DefaultInventoryRepository`, notifiche (`notifyProductCatalogChanged`, `onCatalogChanged`, listener Paging, ecc.) e incrocio con **TASK-041** (addendum sync) e **TASK-055** **senza** espandere lo scope a refactor cloud.
2. Se il flusso **espone o può derivare** con **cambi minori** un insieme di **`productId`** o **barcode** effettivamente mutati in un singolo “batch” remoto, **soluzione preferita:** dopo l’**apply Room** lato remoto, per ogni `id` interessato chiamare `getProductDetailsById(id)` (stessa proiezione della lista) e **aggiornare la stessa mappa override** usata per l’update manuale.
3. **Non** aggiungere `products.refresh()` globale o equivalente come risposta predefinita al completamento del pull, se il punto (2) è realizzabile con costo ragionevole.
4. **Se il flusso remoto non espone** ID/barcode toccati **senza** refactor ampio (o solo a costo “catalogo pieno”):
   - **documentare** il **limite** nel log Execution;
   - **nessuno scope creep** forzato in questo task;
   - criteri **#19–#20** (pull / non-blocco fix manuale) — **nessun** blocco alla chiusura del fix **manuale**;
   - **non** condizionare la chiusura del perimetro “DatabaseScreen + save prodotto” a un refactor remoto fuori perimetro.

**Test:** eventuale test/fake per “id cambiati dal remoto” **solo** se l’infrastruttura di test o hook esistono o sono aggiungibili con poche righe, senza clonare metà dello stack sync.

---

## File da toccare (indicativi)

| File | Nota |
|------|------|
| `app/.../data/ProductDao.kt` | Nuova query `getDetailsById` (o nome allineato al repo). |
| `app/.../data/InventoryRepository.kt` | Firma `getProductDetailsById`. |
| `app/.../data/InventoryRepository.kt` (DefaultInventoryRepository) | Implementazione. |
| `app/.../viewmodel/DatabaseViewModel.kt` | Mappa override + `updateProduct` / eventuale `clear` su delete se necessario. |
| `app/.../ui/screens/DatabaseScreen.kt` | Passare override al sottoalbero prodotto se il merge non è interamente in `DatabaseProductListSection`. |
| `app/.../ui/screens/DatabaseScreenComponents.kt` | Merge paging + override in `DatabaseProductListSection` (e/o `ProductRow` call site). |
| `app/.../ui/screens/EditProductDialog.kt` | Audit key `remember*`, inizializzazione TextField, eventuale `key` o `LaunchedEffect` su campi sorgente; **zona ad alto rischio** per criteri “riapri subito”. |
| Sync / pull (se applicabile, vedi § *Refresh locale anche dopo pull remoto*) | File rilevati in EXECUTION: wiring `notify*`, coordinator, listener — toccare **minimo** per applicare `getProductDetailsById` agli id noti dopo apply remoto. |
| `app/.../viewmodel/DatabaseViewModelTest.kt` | Mock `getProductDetailsById`, asserzioni su override, delete, opz. pull (vedi *Test / check*). |
| `app/.../data/DefaultInventoryRepositoryTest.kt` | Dopo `updateProduct`, `getProductDetailsById` restituisce prezzi/summary attesi. |
| Fakes / test doubles | Aggiornare se aggiungono `InventoryRepository` (es. `RealtimeRefreshCoordinatorTest` fakes) con no-op o stub per nuovo metodo. |

---

## Perimetro incluso

- Fix **visualizzazione** post-update prodotto nella tab/lista **Prodotti** del database hub, con **Paging 3** invariato.
- **Stabilità scroll** in seguito a update singolo e, **ove possibile senza refactor ampio**, dopo eventi di **sync/pull** che toccano prodotti noti per id.
- **Coerenza** con storico prezzi e bottom sheet "price history" esistente (nessuna regressione intenzionale).
- Mappa **override** pensata soprattutto per **update** di prodotti **già** in lista e, se opportuno, per **pull** con id noti — **non** complicare il flusso **Add Product** senza evidenza dello **stesso** bug. Dopo add, regge il comportamento **Paging/Room** attuale; **nessuna** nuova UX obbligatoria se sotto filtro l’inserito non appare: fuori perimetro salvo bug dimostrato.

## Fuori scope

- **Redesign** `DatabaseScreen` o `ProductRow`.
- **Cambi** schema **Room** o **migrazioni**.
- **Rimozione** o sostituzione di **Paging 3**.
- Refactor ampio sync cloud / full catalog push-pull (**TASK-055** tranne nota di verifica).
- Spostare **business logic** nei composable (il merge presentazionale `override ?: paging` è ammesso; logica prezzi/DB resta in VM + repository + DAO).
- **Refresh Paging globale** (`refresh()` / invalidazione elenco) come **tattica standard** al solo `updateProduct` su una riga (vincolante; eccezioni assenti o solo documentate come limite altrui).
- **Ottimistic UI** / override **prima** del successo **repository** (vincolante: vedi *Vincoli tecnici*).
- Garantire a priori ogni UX “aggiungi prodotto sotto filtro” (nuova ricerca, apparizione forzata) **non** richiesta: resta *Perimetro* “non regressione Add”.

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Dopo modifica prezzo (es. vendita) da `DatabaseScreen`, salva: dialog chiuso e **card** con **nuovo** valore subito. | M / E | — |
| 2 | Riaprire subito lo stesso prodotto: TextField prezzo (e campi toccati) = valori appena salvati, non snapshot vecchio. | M | — |
| 3 | "Ultimo" / "Precedente" (e riga prezzi) **coerenti** con `product_prices` / summary. | M / S | — |
| 4 | Dopo update di un prodotto **visibile** in lista, **scroll** resta percepito come **stabile** (niente salto in cima causato dal solo save). | M | — |
| 5 | **Non** serve modificare un secondo prodotto per vedere il primo aggiornato. | M | — |
| 6 | **Aggiunta** prodotto, **eliminazione**, **ricerca/filtro**, **price history** bottom sheet, **import/export** database: nessuna regressione funzionale nota. | M / S / B | — |
| 7 | **Nessun** cambiamento schema Room/migrazioni. | S | — |
| 8 | **Nessun** redesign UI oltre il minimo per merge dati. | S | — |
| 9 | Modifica di **nome prodotto**, **fornitore**, **categoria** o **giacenza** (e altri campi mostrati in card, se presenti): la **card** riflette i nuovi valori **subito** come per i prezzi, senza secondo prodotto / refresh indiretto. | M / E | — |
| 10 | Dopo `delete` di un prodotto che aveva un override, la voce di override è **assente** (nessun dato “fantasma” o stale in lista). | M / S | — |
| 11 | Dopo `update` manuale, **nessun** `refresh` globale Paging / lista è **necessario** per vedere la card aggiornata (l’update passa per override/merge o equivalente minimo, non per “shake” completo). | S / M | — |
| 12 | `EditProductDialog` riaperto **subito** dopo Salva: TextField inizializzano ai valori **aggiornati** (stesso criterio #2, chiuso esplicitamente su dialog). | M | — |
| 13 | **Pull / sync remoto (condizionale):** se il runtime espone o ricava senza refactor ampio l’elenco di **id** (o barcode risolvibili a id) mutati, le relative card in `DatabaseScreen` si aggiornano **puntualmente** e **non** c’è salto in cima alla lista. | M / E | — |
| 14 | **Pull / sync (limite ammesso):** se il flusso **non** espone id toccati senza refactor, documentare in Execution con esito criterio **N/A** / ⚠️ e **non** forzare implementazione: resta criterio di **verifica** e trasparenza, non requisito assoluto su quell’aspetto. | Doc | — |
| 15 | `updateProduct` **fallito**: nessun dato “nuovo” o non salvato in **card** / override; nessun `ProductWithDetails` finto; solo percorso **Error** + snackbar come oggi. | S / M | — |
| 16 | **Due** salvataggi ravvicinati sullo stesso `productId` (stesso o diverso set di campi): l’**override** finale = dettaglio coerente con l’**ultimo** `update` completato con successo, non un override “in ritardo” di un salvataggio precedente. | S / M | — |
| 17 | Con **filtro attivo**, update su prodotto **ancora in vista**: nessun salto in cima; se dopo modifica il prodotto **non** soddisfa più il filtro, il **comportamento** (riga che resta, scompare, messaggio) è **scelto e documentato** in Execution, senza refresh globale obbligatorio. | M / Doc | — |
| 18 | **Add Product**: flusso e percezione **invariati**; il meccanismo override **non** complica inutilmente l’inserimento né introduce regressione (smoke; vedi *Test*). | M / S | — |
| 19 | **Pull puntuale post-sync:** best effort **solo** se id/barcode mutati noti o ricavabili con **cambi minimi**; altrimenti **N/A** documentato, **senza** ritardare chiusura del **fix manuale** in questo task. | Doc | — |
| 20 | Chiusura e verifica del perimetro **update prodotto / lista / dialog** (fix principale) **indipendente** dall’esito **implementato vs N/A** del ramo remoto. | Doc | — |

---

## Check finali (obbligatori per EXECUTION / pre-REVIEW)

| Check | Comando / azione | Note |
|-------|------------------|------|
| Build | `./gradlew assembleDebug` | — |
| Lint | `./gradlew lintDebug` | — |
| Test unit JVM | `./gradlew testDebugUnitTest` | Inclusi mirati se esistenti. |
| Baseline TASK-004 | `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest` (ed eventuali fakes) | Eseguire/aggiornare se toccata la logica. |
| Hygiene | `git diff --check` | — |
| **Test unit pianificati (JVM, TASK-004)** | Vedi sotto. | Obbligatori in Execution per i rami di codice introdotti. |

**Test / unit pianificati (da implementare in EXECUTION se il codice lo consente):**

- **`DatabaseViewModelTest`**
  - `updateProduct` success (mock repository): dopo completamento, la **mappa override** (o struttura equivalente) contiene l’`id` del prodotto con `ProductWithDetails` coerente con `coEvery { getProductDetailsById }` o fixture.
  - `updateProduct` **fallisce** (mock: `updateProduct` lancia o fallisce): la mappa override **non** contiene/aggiorna quell’id con un dettaglio “nuovo” (criterio #15).
  - **Due** `updateProduct` in sequenza (o due successi ravvicinati con delay controllato) sullo stesso `productId`: l’override finale = output di `getProductDetailsById` allineato all’**ultimo** successo (criterio #16; usare risposta mock diversa per il primo/secondo read).
  - `deleteProduct` success: **nessuna** entry override per l’`id` eliminato.
- **`DefaultInventoryRepositoryTest`**
  - Dopo `updateProduct` (prezzi o altri campi), `getProductDetailsById(id)` restituisce `currentPurchasePrice` / `currentRetailPrice` e, dove applicabile, `last*` / `prev*` coerenti con la logica prezzi e summary esistente.
- **Remoto (opzionale):** se esiste o si aggiunge con poche righe (ordine 10) un hook finto “ids changed from remote”, un test unico; altrimenti **esplicitamente N/A** con motivo nel file task.
- **Manuale scroll (M obbligato a fine Execution per criteri scroll):** lista lunga, **scroll a metà**, aprire prodotto **visibile**, Salva, verificare **percezione** posizione invariata o sufficientemente stabile (niente “teletrasporto” in cima).
- **Manuale / filtro (M, criterio #17):** con **stringa filtro** attiva, modifica a prodotto visibile + Salva, scroll percepito stabile; se si testa l’edge “modifica toglie la riga dal filtro”, descrivere esito o documentazione **Sì/N/A**.
- **Smoke Add Product (M, criterio #18):** percorso aggiunta invariato, senza regressioni o attriti inattesi rispetto al pre-task.

---

## Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Mappa override **cresce** senza limiti se non si depura | **Strategia cleanup** (cap N, opz. cambio filtro/tab, delete); non cache permanente. |
| **Svuotare** subito l’override post-save per “pulizia” | **Rischio reintroduzione snapshot stale;** vincolo: non farlo (vedi *Strategia cleanup*). |
| Doppia fonte di verità **temporanea** override vs DB | Sempre `getProductDetailsById` post-commit; stessa proiezione della lista. |
| **Race** Salva: dialog chiuso prima del completamento I/O | Documentare; mitigare con override **dopo** `update+read`; opz. micro-UX su bottone. |
| Test fake `InventoryRepository` non compilano | Aggiornare mock/fake (es. `RealtimeRefreshCoordinatorTest`, altri) per `getProductDetailsById`. |
| Paging `refresh` / `LazyPagingItems.refresh` come scorciatoia | Proibito come soluzione principale save; code review. |
| **Ottimistic** override o card aggiornata **prima** del successo repository | Criteri #15, vincoli; review deve rigettare. |
| Doppi save concorrenti / completamenti fuori ordine | `Race` in *Piano tecnico*; test #16. |
| Filtro modifica “membership” riga rispetto `WHERE` Room | *Filtro attivo*; documentare; no duplicare SQL fragile in UI. |
| Pull remoto: ID non tracciabili o refactor ampio | Best effort, criteri #13–#14, #19–#20; niente blocco fix manuale. |
| Add Product inglobato in override | Fuori perimetro; solo se stesso bug provato. |

---

## Note review (per il planner `CLAUDE.md`)

- Verificare che i criteri "subito" e "scroll stabile" siano dimostrati con **M** (manuale) o log ragionevoli, e che la baseline **TASK-004** sia documentata nel log Execution.
- Distinguere **miglioramento intenzionale** minimo (merge override) da **regressione** non spiegata.
- Se in review emerge che **invalidazione** Paging basta **senza** mappa, valutare come alternativa **solo** se **non** resetta scroll: non espandere il task oltre il fix concordato.
- Criteri **#13–#14**, **#19–#20** (pull / non-blocco): percorso remoto in best effort; se N/A, motivazione; **verificare** che la chiusura del fix **manuale** **non** sia subordinata al merge remoto.
- Criteri **#15–#16** (failure, doppi save): evidenze test o M.
- Criteri **#17** (filtro): richiedere o esplicita documentazione d’esecutore, o M su scenario filtro.
- Criteri **#18** (Add Product): minimo smoke o motivazione.
- **EditProductDialog:** assicurare che criteri #2 e #12 non falliscano per `remember` debole: evidenza M o riferimento a riga/approccio nel log Execution.

---

## Planning (Claude) — sezione formale

### Analisi

Vedi *Contesto codice* e *Ipotesi tecnica*.

### Decisioni (da completare in EXECUTION se necessario)

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | TBD: nome final `StateFlow` / `getProductDetailsById` | Nomi coerenti col codebase | — |
| 2 | Mappa override: **sessione VM**, rimozione su `delete`, possibile cap **N=100** (o depura su filtro/tab) | No cache permanente; no ritorno a stale; controlla RAM | TBD in Execution |
| 3 | `EditProductDialog`: key/effetto se solo `id` insufficiente | Allinea TextField a `Product` aggiornato al rientro | TBD in Execution |
| 4 | Pull remoto: stessa mappa override se id noti; altrimenti **N/A** documentato; **no** blocco chiusura fix manuale | Riuso meccanismo manuale; best effort; criteri #19–#20 | TBD in Execution |
| 5 | **Mai** optimistic: override **solo** post-`updateProduct` di successo | Criteri #15; allineamento a Room reale | TBD in Execution |
| 6 | Conflitto due save: ultimo vince; **atomic** su mappa o serializza per id | Criterio #16; test JVM | TBD in Execution |
| 7 | Edge filtro: comportamento se riga non matcha più; **se** soluzione puntuale, log Execution | Criterio #17 | TBD in Execution |

### Execution — 2026-04-25

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductDao.kt` — aggiunta query puntuale `getDetailsById(productId)` con stessa proiezione lista (`products`, `suppliers`, `categories`, `product_price_summary`).
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — aggiunta API `getProductDetailsById` e implementazione IO in `DefaultInventoryRepository`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — aggiunta mappa override `productDetailsOverrides`, aggiornata solo dopo `updateProduct` riuscito + read fresca; rimozione override dopo `deleteProduct`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — raccolta override dal ViewModel e passaggio alla lista prodotti.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — merge per riga `override[id] ?: pagingItem`, usato per rendering, edit, storico e delete.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt` — re-sync minimo dei campi locali quando cambia il `Product` sorgente.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test read-after-write `updateProduct` + `getProductDetailsById`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — test override su successo, failure, delete e due update sequenziali.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/RealtimeRefreshCoordinatorTest.kt` — fake aggiornato alla nuova firma `InventoryRepository`.
- `docs/TASKS/TASK-058-database-screen-refresh-locale-scroll-stabile.md` — log Execution, verifiche, scelte e handoff.
- `docs/MASTER-PLAN.md` — stato globale riallineato a `REVIEW`.

**Riepilogo tecnico:**
1. `DefaultInventoryRepository.updateProduct` resta sequenziale nel write path esistente (`productDao.update`, `product_prices`, dirty catalog) e il ViewModel legge `getProductDetailsById` solo dopo il ritorno senza eccezioni.
2. L’override è pubblicato solo con `ProductWithDetails` reale letto da Room; nessuna optimistic UI e nessun `LazyPagingItems.refresh()`.
3. Il merge avviene solo a livello presentazionale per singola card; `rememberLazyListState` resta legato al filtro, non a override o `uiState`.
4. I callback della riga usano lo stesso `effectiveDetails`, evitando il bug “card nuova, dialog/storico vecchio”.

**Cosa cambia per l’utente:**
- Dopo Salva su prodotto esistente, la card visibile si aggiorna subito con prezzo/metadati freschi.
- Riaprendo subito lo stesso prodotto, i TextField e “Ultimo/Precedente” partono dal dettaglio aggiornato.
- La lista non viene ricreata con un refresh globale per il singolo save.

**Cosa NON cambia funzionalmente:**
- Nessun cambio schema Room, nessuna migration.
- Nessuna rimozione di Paging 3.
- Nessun redesign di `DatabaseScreen`, `ProductRow` o `EditProductDialog`.
- Add Product resta sul percorso esistente; non è stato agganciato all’override perché il bug riguarda update di righe già nello snapshot.
- Import/export database e sync cloud non sono stati refactorati.

**Scelte implementative documentate:**
- Override cleanup/cap: mappa in sessione ViewModel con cap ultimi 100 productId; su update dello stesso id l’entry viene spostata in coda; su delete riuscito l’id viene rimosso.
- Race multi-save: un `Mutex` sottile serializza `updateProduct`/read/override/delete nel ViewModel; due update sequenziali sullo stesso id lasciano l’override più recente.
- `EditProductDialog`: nessun redesign; aggiunto `LaunchedEffect` sui campi sorgente (`id`, barcode, nomi, prezzi, giacenza, supplier/category) per riallineare TextField e selection state se arriva un `Product` aggiornato con stesso id.
- Filtro attivo: nessuna duplicazione della `WHERE` Room in Compose. Se la riga è già nello snapshot visibile, l’override la aggiorna subito; se dopo la modifica non matcha più il filtro, resta visibile finché Paging/filtro non si riallinea naturalmente.
- Pull remoto/sync: N/A per questa execution. Il flusso catalogo espone remote id e conteggi dentro repository/sync event, ma non porta in modo minimo productId locali fino al `DatabaseViewModel`; collegarlo richiederebbe refactor cloud/coordinator fuori scope TASK-058. Il fix manuale non è bloccato.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` — BUILD SUCCESSFUL. |
| Lint | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug` — BUILD SUCCESSFUL. |
| Warning nuovi | ✅ ESEGUITO | Nessun warning nuovo attribuibile al fix. Presenti warning toolchain AGP/Kotlin già esistenti; durante compilazione mirata è riemerso anche il warning Compose già presente su `rememberSwipeToDismissBoxState(confirmValueChange)`, call non modificata semanticamente. |
| Coerenza con planning | ✅ ESEGUITO | Implementati DAO/repository/VM/merge UI/dialog/tests nel perimetro; nessun refresh globale, nessuna migration, nessun redesign. |
| Criteri di accettazione | ✅ ESEGUITO | Verifica singola sotto; criteri remoti marcati N/A motivato, criteri filtro/Add coperti staticamente e con limiti manuali indicati. |

**Baseline regressione TASK-004:**
- Test eseguiti: `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest --tests com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest` — ✅; `JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./gradlew testDebugUnitTest` — ✅.
- Nota ambiente test: il primo `./gradlew testDebugUnitTest` senza `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true` è fallito per `ByteBuddy/MockK AttachNotSupportedException`; rieseguito con self-attach esplicito, suite completa verde.
- Test aggiunti/aggiornati: `DefaultInventoryRepositoryTest` copre read-after-write prezzi correnti/precedenti; `DatabaseViewModelTest` copre override su successo, failure, delete e update sequenziali; fake `RealtimeRefreshCoordinatorTest` aggiornato.
- Limiti residui: nessun test UI Compose/Espresso aggiunto; la copertura è unit/Robolectric JVM, coerente con baseline TASK-004.

**Smoke manuale emulatore:**
- Ambiente: emulatore `emulator-5554`, app già installata.
- Prodotto visibile in alto: vendita `1.103 → 1.104`; dopo Salva la card ha mostrato subito `1.104`, vecchio `1.103`; riapertura immediata dialog con TextField `1104`, “Ultimo: 1.104”, “Precedente: 1.103”.
- Lista scrollata: vendita `1.113 → 1.114`; dopo Salva la riga aggiornata è rimasta visibile e non c’è stato reset in cima.

**Criteri di accettazione — esito:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ESEGUITO | Smoke emulatore `1.103 → 1.104`: card aggiornata subito. |
| 2 | ESEGUITO | Riapertura immediata dialog: TextField vendita `1104`. |
| 3 | ESEGUITO | Smoke + `DefaultInventoryRepositoryTest`: last/prev allineati a `product_price_summary`. |
| 4 | ESEGUITO | Smoke lista scrollata: nessun salto in cima, riga aggiornata rimasta visibile. |
| 5 | ESEGUITO | Primo prodotto aggiornato senza modificare un secondo prodotto. |
| 6 | ESEGUITO | Delete coperto da test override; import/export coperti da suite `testDebugUnitTest`; price history usa `effectiveDetails`. Smoke manuale esteso su import/export non eseguito. |
| 7 | ESEGUITO | Solo query DAO; nessuna entity/schema/migration modificata. |
| 8 | ESEGUITO | Nessun redesign; solo wiring dati e re-sync dialog. |
| 9 | ESEGUITO | `ProductRow` riceve `effectiveDetails`, quindi nome/fornitore/categoria/giacenza/prezzi usano lo stesso override. |
| 10 | ESEGUITO | `DatabaseViewModelTest`: delete rimuove override. |
| 11 | ESEGUITO | Nessuna chiamata a `products.refresh()` / invalidazione globale aggiunta. |
| 12 | ESEGUITO | Smoke riapertura dialog + re-sync sorgente in `EditProductDialog`. |
| 13 | NON ESEGUIBILE | Pull/sync puntuale non implementato: productId locali non disponibili al VM senza refactor sync fuori scope. |
| 14 | ESEGUITO | N/A remoto documentato e motivato. |
| 15 | ESEGUITO | `DatabaseViewModelTest`: failure update non aggiorna override e non chiama get detail. |
| 16 | ESEGUITO | `DatabaseViewModelTest`: due update sequenziali lasciano l’override più recente; Mutex VM riduce race. |
| 17 | ESEGUITO | Comportamento filtro scelto/documentato: riga visibile aggiornata via override; uscita dal filtro riallineata da Paging/filtro naturale. Smoke filtro non eseguito. |
| 18 | ESEGUITO | Add Product non modificato; test esistenti addProduct passano in suite completa. Smoke manuale Add non eseguito. |
| 19 | ESEGUITO | Best effort remoto valutato: N/A senza bloccare fix manuale. |
| 20 | ESEGUITO | Fix principale update/lista/dialog verificato indipendentemente dal ramo remoto. |

**Hygiene:**
- `git diff --check` — ✅ nessun whitespace/error diff.

**Incertezze:**
- Nessuna incertezza sul fix manuale principale.
- Limite manuale residuo: non eseguita matrice completa filtro/Add/import/export su device; documentata come area di review/smoke utente, non come blocco del fix tecnico.

**Handoff notes:**
- Review planner/utente: verificare soprattutto edge filtro “riga non matcha più” e smoke Add Product se desiderato.
- Pull remoto resta candidato a TASK-055/follow-up dedicato se si vuole propagare productId locali dal sync al ViewModel senza refresh globale.

### Review — 2026-04-25

**Revisore:** Claude (planner)

**Sintesi tecnica della review:**

- **`ProductDao.getDetailsById`** — proiezione identica a `getAllWithDetailsPaged` / `findDetailsByBarcode` (alias `supplier_name`, `category_name`, `lastPurchase`/`prevPurchase`/`lastRetail`/`prevRetail`). `WHERE p.id = :productId LIMIT 1`. Nessuna duplicazione né modifica schema/migration. ✅
- **`InventoryRepository` / `DefaultInventoryRepository`** — nuova `suspend fun getProductDetailsById` su `Dispatchers.IO`, allineata allo stile delle altre `get*`. Nessun refactor del write path; `updateProduct` resta sequenziale come prima e completa il proprio I/O prima che il VM legga il dettaglio fresco. Coerenza con `product_prices` / `product_price_summary` mantenuta. ✅
- **`DatabaseViewModel.productDetailsOverrides`** — esposto come `StateFlow<Map<Long, ProductWithDetails>>` read-only. Override popolato **solo** dopo `repository.updateProduct` riuscito + `getProductDetailsById` non null. `Mutex` `productDetailsOverrideMutex` serializza update/delete sull’intera mappa: nessun deadlock (lock locale, niente chiamate annidate ad altri lock). Cap a 100 voci tramite `LinkedHashMap` con re-insert in coda → comportamento MRU coerente. Su `deleteProduct` riuscito l’`id` viene rimosso. `failure` di update non aggiorna l’override e non chiama `getProductDetailsById`. Test mirati (success/failure/sequential/delete) coprono il comportamento. ✅
- **`DatabaseScreen` / `DatabaseScreenComponents`** — `effectiveDetails = productDetailsOverrides[id] ?: details` calcolato per riga e usato sia per `ProductRow` che per i callback (click/edit, history, swipe-delete). Nessun `products.refresh()` aggiunto; il `productListState` resta keyed solo su `filter`; nessuna nuova `key()` su `uiState`/override/successCount. La key di `items(...)` resta `product.id`. ✅
- **`EditProductDialog`** — nuovo `LaunchedEffect` con keys sui campi sorgente (`id`, `barcode`, `productName`, `secondProductName`, `itemNumber`, `purchasePrice`, `retailPrice`, `stockQuantity`, `supplierId`, `categoryId`). **Verifica anti-overwrite digitazione utente:** mentre il dialog è aperto la `product` referenza resta stabile (parent `itemToEdit` cambia solo su click/save), quindi il `LaunchedEffect` **non** rifà fire mentre l’utente digita; le `key` sono `product.*` (non gli stati locali). Riapertura immediata dello stesso `id` con valori aggiornati → tutte le TextField + selection/state si riallineano. ✅
- **Test** — `DefaultInventoryRepositoryTest` copre read-after-write con `last/prev` correnti; `DatabaseViewModelTest` copre override su success, failure, sequential e delete. Fake in `RealtimeRefreshCoordinatorTest` aggiornato. ✅

**Criteri di accettazione (verdetto planner):**

| # | Criterio | Stato | Note review |
|---|----------|-------|-------------|
| 1 | Card aggiornata subito dopo Salva | ✅ | Override post-success + merge presentazionale; smoke esecutore positivo. |
| 2 | TextField del dialog riaperto = valori salvati | ✅ | `LaunchedEffect` su campi sorgente del `Product`. |
| 3 | "Ultimo"/"Precedente" coerenti con `product_prices`/summary | ✅ | Test repository read-after-write `lastRetail`/`prevRetail`/`lastPurchase`/`prevPurchase`. |
| 4 | Scroll stabile dopo update | ✅ | `productListState` non dipendente da override/uiState; nessun refresh globale. |
| 5 | Niente “workaround secondo update” | ✅ | Override deterministico per id. |
| 6 | Nessuna regressione nei flussi esistenti | ✅ | Suite `testDebugUnitTest` integrale verde con JBR + `JAVA_TOOL_OPTIONS`. |
| 7 | Nessun cambio schema Room/migration | ✅ | Solo nuova `@Query` di sola lettura. |
| 8 | Nessun redesign UI fuori scope | ✅ | Solo wiring dati + sync minimo dialog. |
| 9 | Card riflette anche nome/fornitore/categoria/giacenza | ✅ | `ProductRow` riceve `effectiveDetails`. |
| 10 | Delete rimuove override | ✅ | Test `deleteProduct success removes product details override`. |
| 11 | Niente refresh globale Paging come scorciatoia | ✅ | Verificato in `DatabaseScreen`/`DatabaseScreenComponents`. |
| 12 | Dialog riaperto subito = TextField aggiornati | ✅ | Stesso `LaunchedEffect` di criterio #2. |
| 13 | Pull/sync remoto puntuale | N/A | Documentato come limite scope; criteri #19–#20 rispettati. |
| 14 | Limite remoto documentato | ✅ | Sezione *Refresh locale anche dopo pull remoto*. |
| 15 | Failure update non sporca card/override | ✅ | Test `updateProduct failure does not store product details override`. |
| 16 | Due update sequenziali → ultimo vince | ✅ | Test + `Mutex` serializza la sequenza completa update+read+publish. |
| 17 | Filtro attivo: scroll stabile + scelta documentata | ✅ | Documentato in *Filtro attivo / membership*. |
| 18 | Add Product invariato | ✅ | Override non collegato al flow add; suite test verde. |
| 19 | Pull puntuale best effort | ✅ | N/A motivato senza ritardare chiusura del fix manuale. |
| 20 | Chiusura indipendente dal ramo remoto | ✅ | Confermato. |

**Problemi trovati:** nessuno. Punti potenzialmente migliorabili ma **non** materiali e quindi **non** modificati per rispetto del principio "minimo cambiamento necessario":

- `withCappedProductDetailsOverride` usa un `while` per la rimozione delle voci eccedenti, ma per costruzione al massimo una voce per chiamata può essere eliminata: un `if` avrebbe lo stesso effetto. Difensivo, leggibile, accettato.
- In `deleteProduct` il check `if (current.containsKey(product.id)) current - product.id else current` è una micro-ottimizzazione di allocazione mappa: corretta, mantenuta.

**Verdetto planner:** **APPROVED** (nessun fix necessario).

**Check rieseguiti dal planner:**

| Check | Comando | Esito |
|-------|---------|-------|
| Build | `JAVA_HOME=…/jbr/Contents/Home ./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| Lint | `JAVA_HOME=…/jbr/Contents/Home ./gradlew lintDebug` | ✅ BUILD SUCCESSFUL |
| Test mirati | `JAVA_HOME=…/jbr/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./gradlew testDebugUnitTest --tests …DatabaseViewModelTest --tests …DefaultInventoryRepositoryTest` | ✅ BUILD SUCCESSFUL |
| Test full suite | `JAVA_HOME=…/jbr/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./gradlew testDebugUnitTest --rerun-tasks` (con daemon stop preventivo) | ✅ BUILD SUCCESSFUL |
| Hygiene | `git diff --check` | ✅ |

**Nota ambiente test:** confermata la quirk documentata — la prima esecuzione di `testDebugUnitTest` ha fallito con `MockK / ByteBuddy AttachNotSupportedException`. Riavviato il daemon con `./gradlew --stop` e rieseguito con `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true` → suite verde. Non considerata regressione, in linea con istruzioni del task.

### Chiusura — 2026-04-25

**Esito:** **APPROVED** (planner). Task **`DONE`**.

**Riepilogo finale:**

- Bug "card stale dopo Salva" risolto via override locale per id, popolato solo dopo `updateProduct` di successo + `getProductDetailsById` di Room.
- Scroll lista stabile: nessun `LazyPagingItems.refresh()` né nuove `key()` attorno a `LazyColumn`/`productListState`.
- `EditProductDialog` riallinea i campi locali quando arriva un `Product` aggiornato con stesso `id`, senza interferire con la digitazione.
- Cap override 100 voci con cleanup su delete.
- Nessun cambio schema Room, nessuna migration, nessuna rimozione di Paging 3, nessun redesign.

**Test/check finali:**

- `assembleDebug` ✅
- `lintDebug` ✅
- `testDebugUnitTest` (full suite, fresh daemon + `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true`) ✅
- `testDebugUnitTest --tests DatabaseViewModelTest DefaultInventoryRepositoryTest` ✅
- `git diff --check` ✅

**Rischi residui (non bloccanti):**

- Pull/sync remoto **non** propaga ancora i singoli `productId` mutati al `DatabaseViewModel` (criterio #13 N/A motivato). Resta candidato a un task dedicato (TASK-055/follow-up) se in futuro emergesse il sintomo "card stale post-pull".
- Smoke manuali estesi su matrice filtro/Add/import-export non eseguiti dal planner: la copertura unit JVM è stata considerata sufficiente per APPROVE; eventuale verifica utente consigliata per scenari con filtro attivo + modifica che esce dalla `WHERE`.

---

## Handoff (chiusura)

- **Stato 2026-04-25:** task `DONE`. Review planner APPROVED senza fix; build/lint/test JVM verdi (mirati + full suite con quirk ambiente noto); smoke emulatore esecutore positivo.
- **Eventuali follow-up futuri:** estensione del meccanismo override agli `id` mutati dal pull cloud (oggi N/A senza refactor sync). Da valutare in TASK-055 o task dedicato.
- **Smoke manuali consigliati per validation utente:** modifica prodotto sotto filtro attivo che fa uscire la riga dalla `WHERE`; flusso Add Product end-to-end; price history bottom sheet dopo Salva.
