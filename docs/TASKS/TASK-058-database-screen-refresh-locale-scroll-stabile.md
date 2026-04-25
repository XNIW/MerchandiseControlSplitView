# TASK-058 — DatabaseScreen: refresh locale prodotto modificato e scroll stabile

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-058 |
| Stato | **PLANNING** |
| Priorità | **ALTA** |
| Area | UX/database — `DatabaseScreen`, Paging 3, `DatabaseViewModel`, `InventoryRepository`, `ProductDao` |
| Creato | 2026-04-25 |
| Ultimo aggiornamento | 2026-04-25 — file task creato; `PLANNING` in attesa approvazione utente per `EXECUTION`. |

*Revisione di questo file in fase `PLANNING` **non** implica `PLANNING → EXECUTION` e **non** autorizza patch su Kotlin/XML/Gradle senza conferma utente (`CLAUDE.md` / `AGENTS.md`).*

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
| `viewmodel/DatabaseViewModel.kt` | `pager = filter.flatMapLatest { Pager(...).flow.cachedIn(...) }` su `getProductsWithDetailsPaged(filterStr)`; `updateProduct` chiama `repository.updateProduct` e emette `UiState.Success` **senza** refresh esplicito del paging né reload del dettaglio. |
| `data/ProductWithDetails.kt` | `currentRetailPrice` / `currentPurchasePrice` da `lastRetail` / `lastPurchase` con fallback a `product.retailPrice` / `product.purchasePrice` — se il paging item è **stale**, l’intera riga (e `productWithCurrentPrices()`) resta stantia. |
| `data/ProductDao.kt` | `getAllWithDetailsPaged(filter)` e già `findDetailsByBarcode` con stessa struttura SELECT (join suppliers, categories, `product_price_summary`). **Non** esiste ancora get-by-id con la stessa shape della lista paginata. |
| `data/InventoryRepository.kt` + `DefaultInventoryRepository` | `updateProduct` aggiorna prodotto, inserisce storico prezzi, `notifyProductCatalogChanged` — nessun bridge verso l’item corrente della lista paginata. |
| Test | `DefaultInventoryRepositoryTest` (es. `updateProduct` e price history), `DatabaseViewModelTest` (`updateProduct` emette success e verifica `repository.updateProduct`) — vanno estesi/aggiornati se introduce `getProductDetailsById` e mappa override nel VM. |

Conclusione: la causa plausibile è la **natura Paging 3** (snapshot pagina non invalidata immediatamente per la singola riga) combinata a **assenza** di proiezione del nuovo `ProductWithDetails` nel layer UI finché non scatta un altro evento o refresh.

---

## Ipotesi tecnica (da verificare in EXECUTION)

1. Dopo `updateProduct`, `Room` e `product_price_summary` possono essere corretti, ma **`LazyPagingItems` non sostituisce** l’oggetto `ProductWithDetails` già emesso per quell’indice.
2. `ProductWithDetails.currentRetailPrice` dipende da `lastRetail` e da `product.retailPrice`: se l’**item** paginato è vecchio, card e `productWithCurrentPrices()` usati per aprire il dialog restano stantii.
3. Un tentativo di **invalidare** tutto il `Pager` o di **bump** aggressivo dello stato Composable **può** far perdere la posizione di scroll se si alterano `key` o si ricostruisce l’albero lista senza criteri attenti.

**Soluzione preferita (contract di implementazione):** **override locali** nel `DatabaseViewModel` (`StateFlow` di mappa `productId -> ProductWithDetails` o equivalente) popolata dopo `getProductDetailsById` post-update; in `DatabaseProductListSection` (o chiamante) **merge** con l’item del paging. **Niente** `key()` aggiuntivo sull’override o su `uiState` che ricrei l’intera `LazyColumn` inutilmente.

Riferimenti posizione scroll esistente:

```98:100:app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt
    val productListState = key(filter.orEmpty()) { rememberLazyListState() }
    val supplierListState = key(supplierCatalogQuery) { rememberLazyListState() }
    val categoryListState = key(categoryCatalogQuery) { rememberLazyListState() }
```

---

## Piano tecnico (EXECUTION)

1. **`ProductDao`:** aggiungere query puntuale (nome suggerito: `getDetailsById(productId: Long): ProductWithDetails?`) con **stessa** SELECT di `getAllWithDetailsPaged` (join `suppliers`, `categories`, `product_price_summary`) e `WHERE p.id = :productId` **LIMIT 1**. Riutilizzare le colonne e alias già usati in `findDetailsByBarcode` / paged.
2. **`InventoryRepository` / `DefaultInventoryRepository`:** `suspend fun getProductDetailsById(productId: Long): ProductWithDetails?` (IO, coerente con altri get).
3. **`DatabaseViewModel`:** `StateFlow` (o `MutableStateFlow` interna + `asStateFlow`) per mappa di **override** per `id`; dopo `updateProduct` riuscito: `repository.getProductDetailsById(product.id)` e inserire/aggiornare la mappa; mantenere `UiState.Success` come oggi. Valutare **rimozione** override quando il paging fornisce già dato allineato (opzionale, documentare) o TTL minimale — preferenza **minimo cambiamento**.
4. **`DatabaseScreen` / `DatabaseProductListSection`:** raccogliere la mappa; per ogni `ProductWithDetails` dal paging, se esiste `override[product.id]`, usare quello per `ProductRow` e per i callback che aprono edit/storico (stesso `Product` derivato con `productWithCurrentPrices()` sull’override). **Non** aggiungere `key(overrideMap)` a livello `LazyColumn` se causa reset elenco.
5. **Scroll:** niente `rememberLazyListState` legato a `uiState` o a versione mappa; **non** introdurre chiavi su item che forzino ricomposizione globale a meno di necessità comprovata.
6. **Import / full import / pull remoto:** se esiste invalidazione Paging **inevitabile**, non peggiorarla. **Parte pull / catalogo remoto:** verificare a parte (riferincroce **TASK-041** addendum, **TASK-055**); se esiste meccanismo simile a override, valutare riuso **senza** allargare lo scope oltre `DatabaseScreen` + VM/repository per questo bug.

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
| `app/.../viewmodel/DatabaseViewModelTest.kt` | Mock `getProductDetailsById`, asserzioni su override. |
| `app/.../data/DefaultInventoryRepositoryTest.kt` | Dopo `updateProduct`, `getProductDetailsById` restituisce prezzi/summary attesi. |
| Fakes / test doubles | Aggiornare se aggiungono `InventoryRepository` (es. `RealtimeRefreshCoordinatorTest` fakes) con no-op o stub per nuovo metodo. |

---

## Perimetro incluso

- Fix **visualizzazione** post-update prodotto nella tab/lista **Prodotti** del database hub, con **Paging 3** invariato.
- **Stabilità scroll** in seguito a update singolo.
- **Coerenza** con storico prezzi e bottom sheet "price history" esistente (nessuna regressione intenzionale).

## Fuori scope

- **Redesign** `DatabaseScreen` o `ProductRow`.
- **Cambi** schema **Room** o **migrazioni**.
- **Rimozione** o sostituzione di **Paging 3**.
- Refactor ampio sync cloud / full catalog push-pull (**TASK-055** tranne nota di verifica).
- Spostare **business logic** nei composable (il merge presentazionale `override ?: paging` è ammesso; logica prezzi/DB resta in VM + repository + DAO).

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

---

## Check finali (obbligatori per EXECUTION / pre-REVIEW)

| Check | Comando / azione | Note |
|-------|------------------|------|
| Build | `./gradlew assembleDebug` | — |
| Lint | `./gradlew lintDebug` | — |
| Test unit JVM | `./gradlew testDebugUnitTest` | Inclusi mirati se esistenti. |
| Baseline TASK-004 | `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest` (ed eventuali fakes) | Eseguire/aggiornare se toccata la logica. |
| Hygiene | `git diff --check` | — |
| Test aggiunti (se ragionevole) | Repository: dopo `updateProduct`, `getProductDetailsById` allineato; VM: override pubblicato dopo `updateProduct`. | Documentare in Execution. |

---

## Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Mappa override **cresce** senza limiti se non si depura | Limitare a id toccati in sessione o rimuovere quando paging si allinea (decisione minima in task). |
| Doppia fonte di verità **temporanea** override vs DB | Sempre caricata da `getProductDetailsById` post-commit; allineata alla stessa query della lista. |
| Test fake `InventoryRepository` non compilano | Aggiornare tutti i mock/fake che implementano l’interfaccia. |
| Paging `refresh` globale introdotto per errore | Proibito in scope; code review: niente `invalidate()` sconsiderato. |

---

## Note review (per il planner `CLAUDE.md`)

- Verificare che i criteri "subito" e "scroll stabile" siano dimostrati con **M** (manuale) o log ragionevoli, e che la baseline **TASK-004** sia documentata nel log Execution.
- Distinguere **miglioramento intenzionale** minimo (merge override) da **regressione** non spiegata.
- Se in review emerge che **invalidazione** Paging basta **senza** mappa, valutare come alternativa **solo** se **non** resetta scroll: non espandere il task oltre il fix concordato.

---

## Planning (Claude) — sezione formale

### Analisi

Vedi *Contesto codice* e *Ipotesi tecnica*.

### Decisioni (da completare in EXECUTION se necessario)

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | TBD: nome final `StateFlow` / API repository | Nomi coerenti col codebase | — |

### Execution / Review / Chiusura

*(Da compilare dall’esecutore / revisore secondo `AGENTS.md` e `CLAUDE.md`.)*

---

## Handoff (pre-EXECUTION)

- **Prossimo passo operativo (MASTER-PLAN):** approvazione esplicita utente per passare a **`EXECUTION`**.
- **Dopo approvazione:** implementare *Piano tecnico*; rispettare vincoli *Fuori scope*; documentare ogni scostamento nel file task sotto *Execution*.
