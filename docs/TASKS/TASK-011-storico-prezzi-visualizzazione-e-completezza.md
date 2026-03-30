# TASK-011 — Storico prezzi — visualizzazione e completezza

---

## Informazioni generali

| Campo                | Valore                                      |
|----------------------|---------------------------------------------|
| ID                   | TASK-011                                    |
| Stato (backlog / `MASTER-PLAN`) | **`BLOCKED`** — sospensione temporanea (2026-03-29); **non** `DONE` |
| Fase workflow        | **`BLOCKED`** — in attesa di **smoke manuali / validazione manuale** (checklist **M1–M15**, criteri accettazione con tipo **M**). Ultimo lavoro tecnico completato: **execution codice** + **review tecnica mirata** (2026-03-29); storico **Execution** / log **non** modificato sotto. |
| Priorità             | BASSA                                       |
| Area                 | Price History / DatabaseScreen (Compose)    |
| Creato               | 2026-03-29                                  |
| Ultimo aggiornamento | 2026-03-29 — riallineamento tracking: **`ACTIVE` → `BLOCKED`** (mancano smoke manuali); unico **`ACTIVE`** = **TASK-012** |

### Allineamento tracking / governance

- **Fonte backlog globale:** `docs/MASTER-PLAN.md` — **TASK-011** = **`BLOCKED`** (non più `ACTIVE`). L’unico task **`ACTIVE`** è **TASK-012** finché la governance non cambia.
- **Fonte perimetro / piano:** questo file; sezione **Execution** conserva l’intero storico (implementazione + review tecnica + check statici).
- **Regola:** un solo `ACTIVE` alla volta — **TASK-011** esce da `ACTIVE` senza chiusura in **`DONE`**.

### Stato BLOCKED — motivazione e sblocco

| Voce | Contenuto |
|------|-----------|
| **Perché `BLOCKED`** | Execution e review tecnica mirata risultano **completate** (vedi § **Execution** sotto: file modificati, `assembleDebug`/`lint`, micro-fix `values-zh`). Restano **non eseguiti** i **smoke manuali** e la **validazione manuale** previsti dal task (criteri **M**, checklist **M1–M15** su emulator/device). |
| **Cosa non significa** | Non è chiusura **`DONE`**, non è **`WONT_DO`**, non è annullamento del lavoro già fatto. |
| **Per sbloccare** | Eseguire smoke/validazione manuale; aggiornare gli stati dei criteri **M** nel file task; proseguire con **REVIEW** formale / eventuali **FIX** / conferma utente → **`DONE`** quando tutti i criteri saranno soddisfatti o documentati come **NON ESEGUIBILE** con motivazione accettata. |
| **Riattivazione come `ACTIVE`** | Solo se in futuro la governance richiede di rimettere in coda **TASK-011** come unico attivo (raro); altrimenti può essere completato da **`BLOCKED`** verso **REVIEW/DONE** senza passare di nuovo da `ACTIVE`. |

---

## Dipendenze

- **TASK-001** — `DONE` (governance / baseline)

---

## Scopo

Verificare la **completezza percepita** dello **storico prezzi** nel bottom sheet, rispetto a `product_prices`. **Bersaglio Execution:** **`DatabaseScreenDialogs.kt`** (`PriceHistoryBottomSheet`) + **stringhe**; vedi vincolo su **`DatabaseScreen.kt`** nella tabella file. **`EditProductDialog`** fuori perimetro salvo § **r**. Nessun cambio a business logic, DAO, repository, navigation o schema DB salvo emergenza documentata.

---

## Contesto (stato codice e repo verificati 2026-03-29 — include review pre-execution)

### Persistenza e popolamento

- **`ProductPrice`** (`product_prices`): `productId`, `type` (`PURCHASE` / `RETAIL`), `price`, `effectiveAt` (`yyyy-MM-dd HH:mm:ss`), `source` (nullable; in produzione compaiono anche valori oltre al commento breve nell’entity, vedi § **m**), `note`, `createdAt`.
- **`ProductPriceDao.getSeries`:** `ORDER BY effectiveAt DESC` — serie completa per prodotto e tipo, esposta come `Flow`.
- **`ProductPriceSummary`** (view Room `product_price_summary`): ultimo e penultimo prezzo per tipo; usata nei join **`ProductDao`** → **`ProductWithDetails`** per la lista paginata.
- **`DefaultInventoryRepository`:** registra punti prezzo su add/update prodotto, import, batch PriceHistory da Excel, ecc.; test in **`DefaultInventoryRepositoryTest`** verificano presenza di `source` sugli eventi.
- **`PriceBackfillWorker`:** one-shot all’avvio app — inserisce punti da `products.purchasePrice` / `retailPrice` per prodotti **senza** alcuna riga in `product_prices`, con `source = BACKFILL_CURR`.

### UI Android oggi

- **`DatabaseScreenComponents.ProductRow`:** mostra prezzi “nuovi” (campo prodotto o fallback `last*`) e “vecchi” barrati (`prevPurchase` / `prevRetail` da summary); pulsante **Storico prezzi** → `onShowHistory`.
- **`DatabaseScreen` + `PriceHistoryBottomSheetHost`:** due `collectAsState` su `viewModel.getPriceSeries(id, "PURCHASE"|"RETAIL")` → **`PriceHistoryBottomSheet`** in `DatabaseScreenDialogs.kt`.
- **`PriceHistoryBottomSheet`** (`DatabaseScreenDialogs.kt`): `ModalBottomSheet` + `SecondaryTabRow` (etichette `tab_purchase` / `tab_retail`) + `LazyColumn`. **Layout attuale (pre-TASK-011):** ogni item è un **`Row`** con `Arrangement.SpaceBetween` — **`effectiveAt` grezzo a sinistra**, **`formatNumberAsRoundedString(price)` a destra**, `padding(vertical = 8.dp)`, poi `HorizontalDivider`. **Nessuna** visualizzazione di `source`, `note`, né empty state se lista vuota. **Target TASK-011:** sostituire con colonna § **o-struttura**, tipografia § **o**, padding verticale riga **12.dp** § **o**, `source` + data localizzata come da planning — non è un semplice “add field”: cambia struttura riga.
- **`EditProductDialog`:** `supportingText` sui campi prezzo con ultimo / precedente ricavati da `getPriceSeries` (indici 0 e 1); **non** è oggetto di modifica in TASK-011 salvo eccezione motivata in Execution.

### `ProductPrice.note` — **fuori perimetro TASK-011**

- Il campo **`note`** esiste nello schema ma **non entra** in questo task: **nessuna** visualizzazione in UI, **nessun** placeholder o riga riservata.
- Non risultano oggi percorsi in produzione che popolano `note`; **fino a quando** non esiste un flusso reale che lo scrive, resta **escluso** da ogni intervento TASK-011 (eventuale task futuro dedicato).

---

## Non incluso

- **Nuova schermata dedicata** con route in **`NavGraph`** (fuori scope salvo decisione utente esplicita di espandere il task dopo questo Planning).
- **Grafico andamento prezzi** (richiederebbe libreria grafica o implementazione custom non banale) — **escluso** da TASK-011; eventuale **TASK futuro** se il prodotto lo richiede e si accettano nuove dipendenze o complessità.
- Modifiche a **DAO**, **repository**, **entity**, **migrazioni**, **navigation**, **ViewModel business** — **vietate** salvo necessità reale scoperta in esecuzione e documentata (eccezione motivata).
- Porting 1:1 da SwiftUI / iOS.
- **TASK-019** (audit i18n app completa; include messaggi PriceHistory/full-import) — **resta task separato**; non assorbire.
- Redesign completo **DatabaseScreen** — vedi **TASK-015** per modernization ampia; TASK-011 resta **locale** allo storico prezzi.
- **Redesign del bottom sheet storico prezzi** — vedi § **o-non-redesign**: nessun cambio strutturale a sheet / tab / header / summary / azioni.
- Modifiche a **`EditProductDialog.kt`** — **escluse** salvo eccezione documentata in Execution (§ **r**).
- **`ProductPrice.note`** — **non** mostrato, **non** pianificato in UI (§ Contesto).

---

## File potenzialmente coinvolti (Android) — ipotesi per EXECUTION

> **Bersaglio reale del task:** **`DatabaseScreenDialogs.kt`** + **`values*/strings.xml`**. Obiettivo: **diff piccolo e focalizzato** sul bottom sheet.

| File | Ruolo |
|------|--------|
| `app/.../ui/screens/DatabaseScreenDialogs.kt` | **File principale:** `PriceHistoryBottomSheet`, empty state, data, `source`, helper locali (§ **u**) |
| `app/src/main/res/values/strings.xml` (**default italiano**) + **`values-en/`**, **`values-es/`**, **`values-zh/`** | Mapping `source`, `price_history_source_custom` = **`Altro: %1$s`**, empty, unspecified. **Nota repo:** **non** esiste `values-it/` — l’italiano è in `values/` (come **TASK-013**). |
| `app/.../ui/screens/DatabaseScreen.kt` | **Non toccare** salvo necessità **strettamente locale** di wiring o **compilazione** (es. firma/import impossibile da risolvere altrove — caso **improbabile**). Se toccato: **una** modifica minima + motivazione nel log Execution |
| `app/.../ui/screens/EditProductDialog.kt` | **Fuori perimetro** salvo eccezione § **r** |
| `app/.../viewmodel/DatabaseViewModel.kt` | **Sola lettura** — **nessun** nuovo stato/API (§ **q**, § **u**) |

**Probabilmente non toccati:** `ProductPriceDao.kt`, `InventoryRepository.kt`, `ProductPriceSummary.kt`, `PriceBackfillWorker.kt`, `NavGraph.kt`.

**Review — file fuori lista (vincolante):** ogni file modificato in Execution che **non** compare in questa tabella (né come bersaglio né come eccezione documentata) deve avere **motivazione esplicita** nel log Execution (perché wiring, compilazione o necessità reale). Se l’extra **non** è giustificato → trattare come **scope creep** e richiedere correzione in **Review** / **FIX** (ripristino perimetro o nuovo task).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Empty state per tab senza eventi: **solo testo** localizzato, **senza icona**, tono sobrio e compatto; **una sola** stringa parametrizzata per entrambe le tab (§ **q-empty**, § **q**) | B + M | — |
| 2 | Ogni riga: tipografia § **o** / **o-prezzo**; **data e ora** entrambe visibili (§ **p**) — non solo la data; fallback **raw** integra `effectiveAt`; **source** § **n**–**n-bis** (`SYNC` → custom); ordine § **o-ordine**; **fedeltà serie** § **o-fedeltà** (nessun filtro/nascondimento/salto righe) | B + M | — |
| 3 | **Layout lista:** struttura **colonna singola** § **o-struttura**; `source` e data/ora con **`maxLines = 1`** + **`TextOverflow.Ellipsis`** dove applicabile § **o-testo**; prezzo **sempre** leggibile e **non** compresso; **nessun** overlap; scansione verticale ordinata; **compattezza** § **o-compattezza** (niente spreco verticale gratuito) | M | — |
| 4 | Nessuna regressione: sheet, tab, lista prodotti; **`EditProductDialog`** invariato salvo eccezione documentata | B + M | — |
| 5 | **Nessun** cambio a contratti repository/DAO/schema/navigation senza eccezione motivata nel log Execution | S (review diff) | — |
| 6 | Build `./gradlew assembleDebug` OK; sui **file modificati** dal task: **nessun** nuovo problema lint rilevante — in particolare **nessuna** stringa user-facing hardcoded in Kotlin/Compose, **nessun** import inutilizzato introdotto, **nessun** warning nuovo imputabile al diff, **nessuna** nuova risorsa stringa senza le stesse chiavi in **`values-en`**, **`values-es`**, **`values-zh`** (oltre a `values/` — review verifica **missing translations**). Il **lint globale** della repo può restare rosso per debito **preesistente** fuori da questi file | B + S | — |
| 7 | **Nessun** nuovo stato in `DatabaseViewModel` né logica presentazionale in repository (§ **q**, § **u**) | S | — |
| 8 | **`./gradlew test`:** se il diff è **solo** Compose UI del bottom sheet + stringhe (nessun helper condiviso fuori quel file, nessuna logica oltre il rendering) → **N/A** con motivazione nel log; se il diff tocca **helper riusati**, **file oltre** `DatabaseScreenDialogs.kt`/stringhe, o **logica non puramente presentazionale** → **`./gradlew test` obbligatorio** (baseline TASK-004 se pertinente) | B / S | — |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

> Checklist aggiuntiva: **Definition of Done — task UX/UI** in `docs/MASTER-PLAN.md` (gerarchia, spacing, empty state, primary action dove applicabile, nessuna rimozione feature).

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Opzione progettuale prescelta per EXECUTION: **rifinitura del bottom sheet esistente** + stringhe (source + empty + data), **senza** nuova route e **senza** grafico | Minimo rischio, massima coerenza con dati già in `ProductPrice`; niente nuove dipendenze | 2026-03-29 |
| 2 | **Vista dedicata** e **grafico** — **non** in scope TASK-011 | Scope creep / costo; riapribili come task futuri se l’utente lo chiede | 2026-03-29 |
| 3 | **Data + ora in UI:** parsing `effectiveAt` (`yyyy-MM-dd HH:mm:ss`); output **locale-aware** che include **sempre** sia la **data** sia l’**ora** (non solo data); l’informazione temporale completa è **parte del valore** dello storico; fallback **raw** se parse fallisce | Storico prezzi = eventi nel tempo | 2026-03-29 |
| 4 | **Stati UI:** nessuno **spinner/loading** dedicato; **nessun** nuovo stato nel **ViewModel** per questo task; task = **presentazione locale** (§ **q**) | Evita flicker, complessità artificiale sulla prima emissione vuota del Flow, violazione MVVM sul layer sbagliato | 2026-03-29 |
| 5 | **`EditProductDialog`:** fuori perimetro; eccezioni solo se emergenza documentata | Evita scope creep | 2026-03-29 |
| 6 | **`source` in UI:** **`Text`** + **`typography.labelSmall`** (§ **o**); **no** chip / badge | Gerarchia fissa | 2026-03-29 |
| 7 | **Ordinamento lista:** invariato DAO (`effectiveAt DESC`); nessun riordino/raggruppamento/dedup (§ **o-ordine**) | Coerenza dati | 2026-03-29 |
| 8 | **Helper** map/format: in `DatabaseScreenDialogs.kt` salvo eccezione § **u** | Layer UI | 2026-03-29 |
| 9 | **Custom `source`:** **24** caratteri max visibili nel segmento + **`…`** U+2026 se oltre; stringa **`Altro: %1$s`** (§ **n-bis**) | Eseguibile senza interpretazioni | 2026-03-29 |
| 10 | **Empty state:** solo testo, **nessuna** icona; **una sola** chiave stringa **parametrizzata** per tab vuote acquisto/vendita (§ **q-empty**) | Set risorse minimo | 2026-03-29 |
| 11 | **Target:** `DatabaseScreenDialogs.kt` + stringhe; **`DatabaseScreen.kt`** vietato salvo wiring/compilazione (tabella file) | Diff minimo | 2026-03-29 |
| 12 | **Tipografia riga:** `titleSmall` / `bodyMedium` / `labelSmall` (§ **o**) | Nessuna alternativa sul prezzo | 2026-03-29 |
| 13 | **`SYNC`:** **nessun** mapping dedicato; trattato come **non mappato** → **`price_history_source_custom`** (§ **n-bis**) | Una sola regola | 2026-03-29 |
| 14 | **Padding riga:** **`12.dp`** verticale (unico valore) | No range in Execution | 2026-03-29 |
| 15 | **Divider:** `HorizontalDivider` **standard** M3; **nessun** tuning `alpha` salvo problema visivo **reale** emerso in Execution (documentato) | Semplicità | 2026-03-29 |
| 16 | **Stringhe:** tutte user-facing via **`stringResource` / risorse**; **vietato** hardcode in Kotlin/Compose per questo task; review = **`values/`** + **`values-en`**, **`values-es`**, **`values-zh`** con **stesso set di chiavi** (**no** `values-it/` in questo repo) | Coerenza i18n | 2026-03-29 |
| 17 | **Leggibilità / dark:** contrasto sufficiente per prezzo/data/source; **no** `alpha` aggressiva che indebolisce il testo; verificare coerenza in **dark theme** (smoke manuale leggero, non audit a11y completo) | Fuori scope a11y ampia | 2026-03-29 |
| 18 | **Riga = colonna verticale** (prezzo → data/ora → source); **no** due colonne, **no** metadata trailing, **no** prezzo+data sulla stessa riga (§ **o-struttura**) | Layout stabile | 2026-03-29 |
| 19 | **Overflow testo:** `source` **`maxLines = 1`** + **`TextOverflow.Ellipsis`**; data/ora **una riga** con ellissi se necessario; prezzo **una riga**, elemento **prioritario** non sacrificato (§ **o-testo**) | Evita layout irregolari | 2026-03-29 |
| 20 | **Fallback data:** **nessun** placeholder tipo «data non disponibile»; mostrare **direttamente** `effectiveAt` raw — **zero** perdita informativa (§ **p**) | Coerenza storico | 2026-03-29 |
| 21 | **No-redesign** sheet: struttura sheet, `SecondaryTabRow`, lista — solo rifinitura contenuto riga (§ **o-non-redesign**) | Scope TASK-011 | 2026-03-29 |
| 22 | **Naming stringhe:** chiavi coerenti, set minimo, no ridondanza (§ **s-quater**, § **s-quinquies**) | Manutenibilità risorse | 2026-03-29 |
| 23 | **Diff minimo:** niente refactor cosmetico, cleanup massivo, riordino o riscrittura oltre il necessario; **no** rinomina helper/strutture esistenti se non serve al task (§ **v**) | Execution piccola, review chiara | 2026-03-29 |
| 24 | **Helper UI:** **locali** a `DatabaseScreenDialogs.kt`, **preferibilmente `private`**, **non** API riusabili né astrazioni premature (§ **u**) | Perimetro layer UI | 2026-03-29 |
| 25 | **`source` custom:** solo **`trim`** sul valore DB; **no** cambio casing, **no** traduzione del segmento custom, **no** normalizzazione oltre § **n-bis** (§ **n-ter**) | Fedeltà dati audit | 2026-03-29 |
| 26 | **Lista compatta:** rifinitura **non** aumenta densità verticale in modo gratuito; niente righe “ampie” decorative; elenco **compatto** e scansionabile (§ **o-compattezza**) | UX storico | 2026-03-29 |
| 27 | **File extra nel diff:** motivazione obbligatoria nel log; altrimenti scope creep in review (tabella file + nota sotto) | Governance perimetro | 2026-03-29 |
| 28 | **Prezzo:** dato **primario** della riga; **nessuna** abbreviazione artificiale né forma “compact”; stessa formattazione numerica **già coerente con l’app** (§ **o-prezzo**) | Coerenza prodotto | 2026-03-29 |
| 29 | **Fedeltà serie in UI:** nessun filtro, nascondimento o salto righe (inclusi `source` null/blank → riga resta, label § **n**) (§ **o-fedeltà**) | Audit = serie completa | 2026-03-29 |
| 30 | **Stringhe task:** niente chiave nuova se un placeholder basta; niente varianti quasi duplicate; § **s-quinquies** + **s-quater** | Diff risorse minimo | 2026-03-29 |
| 31 | **Animazioni / effetti:** TASK-011 **non** introduce animazioni, transizioni o effetti decorativi sulle righe (§ **o-no-effetti**) | Scope presentazione | 2026-03-29 |

---

## Planning (Claude) — piano completo

### a) Audit sintetico dello stato attuale

- I dati di storico sono **completi a livello DB** (serie, source, timestamp); la lista prodotti sfrutta la **view** per ultimo/penultimo prezzo.
- La **visualizzazione dettagliata** è un **ModalBottomSheet** a due tab con elenco cronologico (dal più recente); mancano **contesto** (`source`) e **empty state** esplicito.
- Il dialog modifica mostra solo **due** livelli (ultimo / precedente), non l’intera serie — comportamento accettabile come sintesi; il dettaglio resta sul bottom sheet.

### b) Problemi reali nella visualizzazione

1. **Incompletezza informativa:** `source` è significativo per audit (manuale vs import vs backfill) ma **non** è mostrato.
2. **Empty state assente:** con lista vuota l’utente vede un foglio quasi vuoto senza messaggio.
3. **Data raw:** `effectiveAt` è mostrato come stringa storage; formattazione localizzata migliorerebbe leggibilità (solo presentazione).
4. **Grafico / schermata dedicata:** non risolvono un “buco” dati oggi; sono **nice-to-have** con costo maggiore.

### c) Perimetro preciso del task

- Presentazione in **`DatabaseScreenDialogs.kt`** + **`values*/strings.xml`**; campi **`ProductPrice`** già disponibili (**no** `note` in UI — § Contesto).
- **`DatabaseScreen.kt`:** vedi tabella file (diff minimo).
- **`EditProductDialog`:** fuori perimetro (§ **r**).
- Fuori perimetro: nuove route, grafici, redesign lista/toolbar (**TASK-015**).

### d) Cosa cambierebbe per l’utente

- Empty **solo testo** quando non c’è storico per la tab — **un’unica** stringa parametrizzata per acquisto/vendita (§ **q-empty**, § **q**).
- Righe con **prezzo / data+ora / source** (§ **o**, § **p**); **`SYNC`** e non mappati → **custom** (§ **n-bis**).
- **Data e ora** sempre entrambe in formato localizzato, salvo fallback raw (§ **p**).

### e) Cosa NON cambierebbe a livello funzionale

- Regole di scrittura su `product_prices`, import/export, backfill, `insertIfChanged`, flussi **`DatabaseViewModel`** di business, paging prodotti, navigazione tra schermate.
- **Ordine e cardinalità** delle righe mostrate nello storico: **identici** alla serie emessa da Room (`ORDER BY effectiveAt DESC` già nel DAO) — nessuna trasformazione elenco in UI (§ **o-ordine** + **fedeltà** § **o-fedeltà**).

### f) File Android da toccare (previsione)

- **Primario:** **`DatabaseScreenDialogs.kt`**, **`values*/strings.xml`** — allineato alla tabella **File potenzialmente coinvolti**.
- **`DatabaseScreen.kt`:** vincolo **stretto** (wiring/compilazione); **`EditProductDialog.kt`:** § **r**.

### g) Eventuale riferimento iOS

- **Non presente in questo workspace.** Se esiste una repo iOS separata, usarla **solo** come riferimento visivo (gerarchia testi, spacing) per il bottom sheet — **non** come fonte di logica. Nessun porting 1:1.

### h) Rischi di regressione

- **Basso** se si limita a Compose + stringhe: errori di layout, traduzioni mancanti in una lingua, click assorbiti dal bottom sheet.
- **Medio-basso** se si toccasse accidentalmente `collectAsState` / lifecycle — mitigazione: cambi minimi, test manuale tab + dismiss.
- **Regressione TASK-004:** improbabile se si rispetta § **u** (nessuna logica presentazionale spostata in VM/repository); qualsiasi eccezione va documentata e coperta da test mirati.

### i) Check e test previsti

- `./gradlew assembleDebug` + `./gradlew lint` (criterio **#6**); review contratti **#5**.
- **`./gradlew test`:** criterio di accettazione **#8** (N/A solo se diff puramente UI bottom sheet + stringhe; obbligatorio se diff più ampio).
- **Manuale:** § **t** (M1–**M15**), § **s-bis** / **s-ter** / **s-quater** / **s-quinquies** (stringhe a review).

### l) Opzioni progettuali e scelta

| Opzione | Pro | Contro |
|---------|-----|--------|
| A — Rifinitura bottom sheet + stringhe | Semplice, nessuna dipendenza, allineata ai dati esistenti | Non offre grafico |
| B — Schermata dedicata | Più spazio UI | Navigation + scope, sovrapposizione con TASK-015 |
| C — Grafico | Trend visivo | Dipendenza o complessità, dati già tabellari sufficienti per molti casi |

**Scelta:** **A** — implementare A in **EXECUTION** (workflow avviato 2026-03-29 dopo review planning ↔ codice).

---

### m) Inventory valori `ProductPrice.source` (codice verificato 2026-03-29)

Valori **scritti dal codice di produzione** (`app/src/main`):

| Valore `source` | Origine (file / uso) |
|-----------------|----------------------|
| `MANUAL` | `DefaultInventoryRepository` — `addProduct` / `updateProduct` (`insertIfChanged`, …, `"MANUAL"`) |
| `IMPORT` | Stesso repository — prezzi **nuovi** da import prodotto |
| `IMPORT_PREV` | Stesso — prezzi **precedenti** catturati in import (`oldPurchasePrice` / `oldRetailPrice`) |
| `BACKFILL_CURR` | `PriceBackfillWorker` — seed da `products.purchasePrice` / `retailPrice` quando non esisteva ancora alcuna riga in `product_prices` |
| `IMPORT_SHEET` | Default del batch `recordPriceHistoryByBarcodeBatch(..., source = "IMPORT_SHEET")`; **`FullDbImportStreaming`** e **`DatabaseViewModel.applyPendingPriceHistory`** usano `groupBy { it.source ?: "IMPORT_SHEET" }` quando la colonna source del foglio **PriceHistory** è assente/vuota |

Valori **dinamici (persistiti così in DB):** il foglio Excel **PriceHistory** può contenere una colonna **source** con **stringa arbitraria** (dopo trim); ogni valore distinto raggruppa un batch e diventa il `source` salvato sulle righe importate (`FullDbImportStreaming`).

**Valore `SYNC`:** compare solo nel **commento** dell’entity / eventuali dati **legacy**. **Decisione vincolante:** **nessuna** riga dedicata in § **n**; se in DB compare esattamente `SYNC`, seguire il ramo **custom** — **`price_history_source_custom`** con § **n-bis** (come qualsiasi altro valore non in tabella).

**Test JVM** (`app/src/test`): costruzione manuale di `ProductPrice` con `MANUAL` — non estende l’elenco produzione.

---

### n) Mapping UX user-facing (`source`)

- **Confronto** con i valori costanti: **case-sensitive** (il codice usa maiuscolo).
- **Etichette:** brevi, chiare, **non tecniche** (niente “IMPORT_PREV” visibile all’utente).
- **Localizzazione (vincolante):** vedi § **s-bis** — **nessun** testo user-facing hardcoded nel composable; tutte le chiavi nei **quattro** bucket effettivi del repo: **`values/`** (IT default), **`values-en/`**, **`values-es/`**, **`values-zh/`**.

| `source` (match esatto) | Chiave risorsa suggerita | Etichetta IT (bozza vincolante per intento) |
|-------------------------|---------------------------|-----------------------------------------------|
| `MANUAL` | `price_history_source_manual` | Manuale |
| `IMPORT` | `price_history_source_import` | Importazione |
| `IMPORT_PREV` | `price_history_source_import_prev` | Prima dell’import |
| `BACKFILL_CURR` | `price_history_source_backfill` | Da anagrafica |
| `IMPORT_SHEET` | `price_history_source_sheet` | Da file |
| `null`, stringa vuota, solo spazi | `price_history_source_unspecified` | Non indicato |
| **`SYNC`** e **qualsiasi altro** valore non nella tabella sopra (incluso testo libero da Excel) | `price_history_source_custom` | Template **`Altro: %1$s`** + § **n-bis** — **`SYNC` non ha eccezioni** |

**Fallback combinato (ordine logico in EXECUTION):**

1. Se `source` è `null` o blank → **`price_history_source_unspecified`**.
2. Se match **esatto** a una riga della tabella (`MANUAL`, `IMPORT`, `IMPORT_PREV`, `BACKFILL_CURR`, `IMPORT_SHEET`) → stringa dedicata.
3. **Tutto il resto** (incluso **`SYNC`**) → **`price_history_source_custom`** con `displaySegment` per § **n-bis**.

---

### n-bis) Comportamento `source` custom / non mappato — **vincolante per Execution**

Sia `raw = source.trim()` dal DB (stringa non mappata da § **n**).

1. **Risorsa:** sempre `stringResource(R.string.price_history_source_custom, displaySegment)` dove il formato XML/stringa è letteralmente **`Altro: %1$s`** in tutte le lingue (tradurre solo il prefisso **Altro:** se si localizza la chiave; il placeholder resta **`%1$s`**).
2. **Soglia fissa:** **24** — si intende **lunghezza carattere** Kotlin/Java su `raw` dopo `trim` (`.length` / `codePointCount` non richiesto: usare **`raw.length`** coerente col resto dell’app).
3. **Costruzione di `displaySegment` (unica algoritmo ammesso):**
   - se `raw.isEmpty()` → trattare come null/blank (non dovrebbe capitare per la riga «custom»; se capita, usare flusso **unspecified**);
   - se `raw.length <= 24` → **`displaySegment = raw`**;
   - se `raw.length > 24` → **`displaySegment = raw.take(24) + "…"`** dove **`…`** è il carattere Unicode **ELLIPSIS** U+2026 (stringa Kotlin `"\u2026"` o `…`).
4. **Esempio:** `raw = "VERY_LONG_CUSTOM_TAG_FROM_EXCEL_SHEET_2024"` → `displaySegment = "VERY_LONG_CUSTOM_TAG_FROM_EX…"` (24 caratteri del prefisso + ellissi).

**Vietato:** tooltip, long-press, expand/collapse, dialog, riga extra, clipboard — **nessun** ampliamento scope.

---

### n-ter) `source` custom / non mappato — **integrità del contenuto (vincolante)**

Per il ramo **`price_history_source_custom`** (placeholder **`%1$s`** = segmento derivato da `raw` dopo § **n-bis**):

- **Unica trasformazione sul testo persistito:** **`trim()`** (come già implicito in `raw = source.trim()` § **n-bis**). **Vietato:** `lowercase` / `uppercase` / `capitalize` / altre mutazioni di **casing**.
- **Vietato** “tradurre” o sostituire il **contenuto** custom (es. dizionario sinonimi, mappe euristiche). Si mostra il valore **così com’è** dal DB, **salvo** il troncamento **esplicitamente** definito in § **n-bis** (`take(24)` + U+2026) e l’ellissi di layout § **o-testo** sul `Text`.
- **Vietata** ogni **normalizzazione ulteriore** (NFC/NFD, rimozione caratteri, slug, collapse spazi interni oltre quanto fa `trim` sui bordi, ecc.) — il segmento deve riflettere il **raw** trattato solo come sopra.

Il prefisso **«Altro:»** (o equivalente localizzato nella risorsa) è **l’unica** parte user-facing “tradotta” della riga source per i custom; **`%1$s`** resta il dato di dominio **non interpretato**.

---

### o) Gerarchia visiva di ogni riga (bottom sheet) — **tipografia vincolante**

**Nessuna alternativa in Execution:** usare **esattamente** le varianti sotto (o equivalente semantico M3 se rinominate in tema).

| Livello | Elemento | Stile |
|---------|----------|--------|
| **Primario** | Prezzo (`formatNumberAsRoundedString`) | **`MaterialTheme.typography.titleSmall`**, `colorScheme.onSurface`, start — **una sola riga** (`maxLines = 1`, `overflow = TextOverflow.Ellipsis` se in futuro formati lunghi); **primo elemento visivo** e **più importante** della riga — **non** deve essere compresso, ridimensionato o spostato per «fare posto» a data/source |
| **Secondario** | Data/ora (§ **p**) | **`bodyMedium`**, `onSurfaceVariant`, `padding(top = 4.dp)` sotto il prezzo — **una riga** (`maxLines = 1`, `TextOverflow.Ellipsis` se la stringa localizzata eccede la larghezza) |
| **Terziario** | Source (§ **n**–**n-bis**) | **`labelSmall`**, `onSurfaceVariant`, `padding(top = 4.dp)` sotto la data — **`Text` una riga:** `maxLines = 1`, **`TextOverflow.Ellipsis`** — **no** chip / badge |

| Separazione | **`HorizontalDivider`** **standard** Material 3 — **nessuna** modifica a `alpha` / colore **salvo** problema visivo **reale** emerso in Execution (da documentare nel log se applicato). |
| Padding riga verticale | **`12.dp`** (**unico** valore; `Modifier.padding(vertical = 12.dp)` o equivalente per-riga) |

**Tono:** neutro; niente colori semantici forti per `source`. **Contrasto / tema:** § **s-ter**. **Importo / formato numerico (primario):** § **o-prezzo**.

---

### o-compattezza) Compattezza dell’elenco — **vincolante**

La rifinitura **non** deve **aumentare gratuitamente** l’ingombro verticale della lista (obiettivo: restare **compatto**, **leggibile**, **facilmente scansionabile**):

- **Vietato** introdurre righe item **volutamente “ampie”**, spaziature verticali **decorative** o contenitori extra che allungano ogni voce senza necessità funzionale.
- Restano ammessi **solo** gli spazi previsti dal piano: **`padding(vertical = 12.dp)`** per riga, **`padding(top = 4.dp)`** tra prezzo → data → source (§ **o**), **`HorizontalDivider`** standard — **nessun** incremento arbitrario di margini/padding oltre questi vincoli salvo problema visivo **reale** documentato in Execution.

---

### o-struttura) Struttura della riga — **vincolante**

- **Una sola colonna verticale** (`Column` o equivalente): dall’alto verso il basso → **1) prezzo** → **2) data e ora** → **3) source**.
- **Vietato:** layout a **due colonne** (es. prezzo a sinistra e data a destra sulla stessa linea); **trailing metadata** aggiuntivo; **comprimere prezzo e data sulla stessa riga**; riga «compatta» che mescola livelli della gerarchia.

---

### o-testo) Testo lungo e overflow — **vincolante (Compose)**

- **`source`:** sempre **una riga** — `maxLines = 1`, `overflow = TextOverflow.Ellipsis` (oltre al troncamento logico del segmento custom § **n-bis** per `%1$s`, l’`Text` deve comunque gestire overflow di layout).
- **Data/ora:** **una riga**; se la stringa formattata è troppo larga per la larghezza disponibile → **`maxLines = 1`** + **`TextOverflow.Ellipsis`** (l’informazione resta quella di § **p**; in caso di parse OK l’utente vede data+ora fino al limite visivo).
- **Prezzo:** **una riga**; resta l’elemento **prioritario** e **chiaramente leggibile** — non è l’elemento da **sacrificare** (niente `scale` riduttivo, niente font più piccolo del `titleSmall` scelto, niente condivisione riga con data).

---

### o-prezzo) Prezzo — **valore primario e formattazione (vincolante)**

- Il **prezzo** è il **dato primario** della riga (gerarchia informativa sopra data e `source`).
- **Vietato** abbreviarlo in modo **artificiale** (es. suffissi tipo «K», notazione scientifica, cifre significative ridotte **solo** per guadagnare spazio).
- **Vietato** convertirlo in forme **“compact”** o shorthand **non** allineate al resto dell’app.
- **Obbligo:** usare la **stessa** pipeline di formattazione numerica **già coerente con l’app** per questo contesto — oggi il bottom sheet usa **`formatNumberAsRoundedString`** sul valore prezzo; in Execution **mantenere** quell’approccio (o equivalente **esplicitamente** lo stesso se il nome del helper cambiasse altrove nel codebase — **non** introdurre un formatter parallelo senza necessità documentata).

---

### o-non-redesign) Perimetro struttura bottom sheet

TASK-011 è una **rifinitura locale** della **lista** dentro `PriceHistoryBottomSheet`, **non** un redesign del contenitore:

- **Non** modificare la **struttura generale** del bottom sheet (resta `ModalBottomSheet` + contenuto come oggi salvo padding interno minimo alle righe).
- **Non** modificare la **TabRow** delle tab acquisto/vendita (**`SecondaryTabRow`** nel codice attuale) né le **etichette** tab, salvo bug bloccante documentato.
- **Non** introdurre **header** aggiuntivi sopra la lista, **summary**, **conteggi** («N eventi»), né **nuove azioni** (FAB, pulsanti extra, menu).
- **Sì:** migliorare **solo** presentazione di ogni riga elenco + empty state testuale + stringhe.

---

### o-no-effetti) Animazioni ed effetti — **vincolante**

TASK-011 **non** introduce:

- **nuove animazioni** sulle righe o sul contenuto dello storico (`AnimatedVisibility` decorativa, `animate*AsState` per testo prezzo/data/source, effetti di entrata/uscita item, ecc.);
- **transizioni** custom tra tab o tra stati lista/empty oltre al comportamento **predefinito** dei componenti;
- **effetti decorativi** (ombre animate, shimmer, pulsazioni).

Restano ammessi **solo** il comportamento **standard** di **`ModalBottomSheet`**, **`LazyColumn`**, **`SecondaryTabRow`** e dei token M3 già in uso — la rifinitura resta **tipografica e informativa** (§ **o**, § **p**, § **n**).

---

### o-ordine) Ordinamento elenco — vincoli EXECUTION

- **Mantenere** l’ordine **cronologico discendente** già fornito da `ProductPriceDao.getSeries` / `Flow` (stesso ordine di oggi: più recente in alto).
- **Non** raggruppare le righe per `source`.
- **Non** riordinare lato UI (niente sort per prezzo, per source, alfabetico).
- **Non** accorpare eventi con lo **stesso prezzo** in una sola riga.
- **Non** introdurre deduplicazione o compressione righe in UI.

L’elenco è una **vista fedele** della serie persistita, con sola **presentazione** (testo data + testo source) migliorata. Ulteriori vincoli di **completezza** delle righe mostrate: § **o-fedeltà**.

---

### o-fedeltà) Fedeltà della serie in UI — **vincolante**

La lista nello sheet deve restare una **rappresentazione fedele** della serie emessa dal `Flow` / DAO (stessi elementi, stesso ordine), salvo **pura presentazione** (tipografia, data formattata, `source` mappato a stringa — § **n**–**n-ter**):

- **Vietato** **filtrare** eventi lato UI (niente “mostra solo ultimi N”, niente esclusione per `source`, prezzo duplicato, ecc., oltre quanto già vietato in § **o-ordine**).
- **Vietato** **nascondere** righe ritenute “meno utili” o rumorose.
- **Vietato** **saltare** righe con **`source` `null`, vuoto o solo spazi**: la riga **deve** comparire come le altre; per il terzo livello testuale si usa **`price_history_source_unspecified`** (§ **n**). **Nessun** comportamento anomalo (crash, item vuoto senza prezzo/data, collasso della riga).

Ogni `ProductPrice` della serie → **esattamente un** item visibile nella `LazyColumn` (più divider come oggi), finché la lista non è vuota.

---

### p) Formattazione data/ora (decisione vincolante, non opzionale)

- **Dato sorgente:** sempre `ProductPrice.effectiveAt` (pattern atteso **`yyyy-MM-dd HH:mm:ss`**).
- **Parsing (UI layer):** `LocalDateTime.parse` + `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")` (o equivalente sicuro).
- **Output in UI — obbligo di completezza temporale:** la stringa mostrata all’utente deve contenere **sia la data sia l’ora** (es. stile `FormatStyle.MEDIUM` / `SHORT` combinati, o formatter locale che non **ometta** l’ora). **Non** mostrare **solo** la data: l’**ora** è parte del **valore informativo** dello storico (eventi distinti nello stesso giorno).
- **Locale:** `Locale` corrente da `LocalContext` / `Configuration`.
- **Fallback obbligatorio — senza placeholder:** se parse fallisce o formato inatteso → mostrare **immediatamente** la stringa **`effectiveAt` esattamente come nel DB** (raw). **Vietato** sostituirla con messaggi tipo «data non disponibile», «—», o altro testo che **nasconda** o **sostituisca** l’informazione persistita. **Nessuna perdita** di contenuto: il raw **è** la rappresentazione fallback completa (già `yyyy-MM-dd HH:mm:ss`).

---

### q) Stati UI del bottom sheet

| Stato | Trattamento |
|-------|-------------|
| **Loading dedicato** | **Non introdotto** — né `CircularProgressIndicator` nel sheet, né skeleton, né stato “caricamento” composito. |
| **ViewModel** | **Non aggiungere** nuovi `StateFlow` / `MutableState` / flag in `DatabaseViewModel` (né altri VM) **solo** per questo task. Il bottom sheet continua a osservare i `Flow` esistenti come oggi. |
| **Prima emissione vuota del Flow** | **Non** costruire logica artificiale (es. distinguere “loading” vs “empty” con timeout, contatori, o stati derivati nel VM) per gestire il primo `emptyList()`. Se la lista è vuota → trattare come **empty state** (§ sotto). Comportamento allineato al principio: task **puramente di presentazione locale** nel layer UI. |
| **Lista vuota reale** | **Solo testo** localizzato (`bodyMedium` o `bodySmall` + `onSurfaceVariant`), **senza icona**, layout **compatto** (padding modesto, coerente col bottom sheet). **Una sola** risorsa stringa **parametrizzata** per entrambe le tab — vincolo § **q-empty**. **Nessuna** illustrazione, **nessun** `Icon` decorativo. |
| **Lista popolata** | `LazyColumn` con righe secondo § **o**, ordine § **o-ordine**, compattezza § **o-compattezza**, fedeltà § **o-fedeltà**, **no** effetti extra § **o-no-effetti**. |
| **Rischio residuo:** frame iniziale vuoto su dispositivi lentissimi — **accettato**; niente complessità aggiunta per perseguirlo. Se in Execution emergesse problema **reale** e frequente, mitigare solo con accorgimento **locale al composable** (senza VM) e documentare — **mai** obbligare spinner o stato loading globale per TASK-011. |

---

### q-empty) Empty state per tab senza eventi — **scelta unica (vincolante)**

Per evitare duplicazioni nelle risorse e mantenere il set **pulito**:

- Usare **una sola** chiave stringa **parametrizzata** per il messaggio quando la lista della tab è vuota (acquisto **e** vendita), con **`%1$s`** = etichetta tab già esistente: in Execution passare **`stringResource(R.string.tab_purchase)`** o **`stringResource(R.string.tab_retail)`** a seconda della tab selezionata (stesse chiavi già usate in `SecondaryTabRow` in `PriceHistoryBottomSheet` — **nessun** hardcode, **nessun** duplicato testuale).
- **Vietato** introdurre **due** stringhe empty quasi identiche (`..._purchase` / `..._retail`) **salvo** emergenza UX documentata nel log Execution (caso **non** atteso: una forma parametrizzata basta per chiarezza).
- **Obiettivo:** **un** messaggio, **un** punto di traduzione per empty tab, **stesso** tono sobrio in tutte le lingue.

---

### r) Perimetro `EditProductDialog`

- **Focus Execution:** **`DatabaseScreenDialogs.kt`** (`PriceHistoryBottomSheet`) + **`values*/strings.xml`**; helper **confinati** a quel file salvo § **u**. **`DatabaseScreen.kt`** — vedi tabella **File potenzialmente coinvolti**.
- **`EditProductDialog.kt`:** **non modificare** salvo **micro-incoerenza strettamente necessaria** (es. regressione compile/lint causata da refactor condiviso — caso improbabile se il diff resta nel bottom sheet). Ogni eccezione richiede **motivazione nel log Execution** e sarà verificata in Review.

---

### s) Regola estetica micro (obbligatoria)

- L’intervento deve **migliorare leggibilità** e **qualità percepita** (gerarchia tipografica, spaziatura, coerenza colori M3).
- **Evitare** componenti troppo pesanti (card impilate, **chip/badge**, icone nell’empty state, animazioni decorative, nuove dipendenze grafiche) e qualsiasi **redesign** della lista prodotti, toolbar, FAB o flussi oltre il bottom sheet. Allineamento esplicito: § **o-no-effetti**.
- Restare **allineati** a **DatabaseScreen** / dialog **Material 3** esistenti (`ModalBottomSheet`, `SecondaryTabRow`, toni `surface` / `onSurface` / `onSurfaceVariant`).

---

### s-bis) Localizzazione — vincolo su stringhe

- **Vietato** introdurre testo visibile all’utente come **letterale** in `DatabaseScreenDialogs.kt` (o altri file toccati) per questo task: usare **`stringResource`**, **`pluralStringResource`**, ecc.
- **Obbligatorio:** ogni nuova chiave in **`values/strings.xml`** ha la **stessa** chiave in **`values-en/strings.xml`**, **`values-es/strings.xml`**, **`values-zh/strings.xml`** — **nessun** `tools:ignore="MissingTranslation"` per nascondere gap. (**Non** cercare `values-it/`: **non** è presente nel repo; italiano = `values/`.)
- **Review:** verificare assenza di **missing translations** e di stringhe hardcoded introdotte dal diff.

---

### s-quater) Pulizia e naming delle risorse stringa

- **Naming:** prefisso coerente (es. `price_history_*` dove già usato nel task) — chiavi **definitive**, non provvisorie da duplicare poi.
- **Empty tab:** vincolo **chiuso** in § **q-empty** (una sola chiave parametrizzata).
- **Obiettivo:** set di risorse **minimo** e leggibile per chi manterrà il modulo; niente proliferazione di stringhe equivalenti.

---

### s-quinquies) Contenimento del diff sulle stringhe — **vincolante**

- **Vietato** aggiungere **nuove** chiavi se **una** stringa esistente o **una** sola chiave **parametrizzata** copre già il caso (stesso messaggio con variante minima → **placeholder**, non seconda risorsa).
- **Vietate** varianti **quasi duplicate** (stesso testo con micro-differenze evitabili) senza necessità reale documentata.
- Il **set di stringhe introdotte/modificate** per TASK-011 deve restare **minimo** e **pulito** — ogni chiave nuova deve avere **motivo** chiaro in review (copertura i18n § **s-bis** invariata).

---

### s-ter) Leggibilità e dark theme (micro, senza scope a11y completo)

- **Contrasto:** prezzo (`onSurface`), data e source (`onSurfaceVariant`) devono restare **chiaramente leggibili** sul `surface` del bottom sheet — **non** applicare `alpha` così bassa da rendere il testo difficile da leggere.
- **Dark theme:** controllare a occhio (smoke manuale) che le tre righe testuali restino leggibili con **tema scuro** attivo; niente combinazioni arbitrarie fuori dai token del tema salvo motivazione locale documentata.

---

### u) Confine layer — helper e presentazione

- **Mapping** `source` → stringa user-facing, **parse/format** di `effectiveAt`, e **eventuali** micro-helper per troncamento o layout riga devono restare nel **layer UI**, **locali al file** che contiene **`PriceHistoryBottomSheet`** — cioè **`DatabaseScreenDialogs.kt`** — salvo eccezione § sotto.
- **Preferibilmente `private`:** funzioni **`private`** top-level nello stesso file, o **`private`** su membri del file / composable interni — così gli helper **non** diventano **contratti pubblici** del modulo né “utility” generiche da riusare altrove senza un task dedicato.
- **Non** trattare questi helper come **API riusabili** o come **astrazioni premature** (niente interfacce, niente spostamento in `util` “per pulizia”, niente naming da “framework” se non richiesto dal task).
- **Eccezione** (file diverso o `internal`/`public`): ammessa **solo** se necessità **minima** documentata nel log Execution — caso atteso **improbabile**; in ogni caso **vietato** spostare la logica in **`DatabaseViewModel`**, **`InventoryRepository`**, né DAO.
- Il task resta **presentazione locale**: il ViewModel continua a esporre gli stessi `Flow`/`getPriceSeries`; nessuna nuova API “di formattazione” lato VM salvo decisione esplicita fuori scope TASK-011.

---

### v) Diff minimo in Execution — **vincolante**

Per mantenere il diff **piccolo**, **lineare** e **facile da review**:

- **Vietato** refactor **cosmetico** nello stesso file (rinomina massiva, riformattazione non necessaria al diff funzionale, spostamenti di blocchi non legati al bottom sheet).
- **Vietato** **cleanup massivo** non richiesto (import globali, dead code fuori perimetro, “while we’re here” su altre dialog nel file) salvo **stretta** adiacenza al codice toccato e **motivazione** nel log Execution.
- **Vietato** **riordini** o **riscritture** di sezioni del file **oltre il minimo indispensabile** per implementare `PriceHistoryBottomSheet` / empty / stringhe.
- **Vietato** **rinominare** helper, classi interne o strutture **esistenti** nel file se il task **non** lo richiede direttamente (es. solo per gusto stilistico); se un rename è **davvero** necessario, documentarlo nel log con **una riga di motivazione** tecnica.

---

### t) Checklist test manuali obbligatoria (EXECUTION)

Registrare per ogni riga: **esito** (OK / KO) e **nota** breve.

| ID | Scenario |
|----|----------|
| M1 | Prodotto con storico **solo** `MANUAL` (una o entrambe le tipologie PURCHASE/RETAIL) |
| M2 | Prodotto con eventi **mist**i `MANUAL` + `IMPORT` (+ eventualmente `IMPORT_PREV`) |
| M3 | Prodotto con **solo** `BACKFILL_CURR` |
| M4 | Prodotto con **solo una tab** popolata (es. storico acquisto pieno, vendita vuota) — verificare empty state sull’altra tab |
| M5 | Prodotto **senza** alcun record in `product_prices` — empty su **entrambe** le tab |
| M6 | **Cambio tab rapido** ripetuto (acquisto ↔ vendita) |
| M7 | **Dismiss** sheet (swipe, tap fuori, back) e **riapertura** sullo stesso prodotto e su **altro** prodotto |
| M8 | **Lingua:** verificare almeno **una** locale oltre al default — tipicamente passare a **`en`** / **`es`** / **`zh`** (System language o App language) e controllare mapping `source`, empty, **data+ora**; **nessun** testo hardcoded visibile |
| M9 | (Se riproducibile) `effectiveAt` **non parsabile** — deve apparire **raw** (data+ora nel testo grezzo), nessun crash |
| M10 | `source` **custom** — verifica **esatta** § **n-bis**: `Altro: %1$s`, **24** + **`…`** U+2026 se oltre soglia; **nessun** tooltip / long-press |
| M11 | (Se DB di test) `source == "SYNC"` esattamente → **`price_history_source_custom`** + segmento § **n-bis** (stesso ramo dei non mappati) |
| M12 | **Dark theme:** smoke — prezzo/data/source leggibili (§ **s-ter**) |
| M13 | **Alta densità:** prodotto con **molti** eventi nello storico (entrambe le tab se popolate) — scroll della `LazyColumn` fluido; densità visiva accettabile; **nessuna** rottura layout, jitter o testo che esce dai bordi; prezzo ancora **scansionabile** in verticale |
| M14 | **Combo:** lingua **diversa dal solo default** (es. `en` / `es` / `zh` rispetto a `values/` IT) **+** evento con **`source` custom lungo** (≥ soglia § **n-bis** se possibile) — verificare prefisso localizzato **`Altro:`** / equivalente, segmento custom fedele § **n-ter**, **ellissi** (troncamento **24** + carattere **U+2026** § **n-bis** **e** layout `Text` § **o-testo**), **nessun** overlap. *Facoltativo nello stesso passaggio:* ripetere con **dark theme** (§ **s-ter**) se utile |
| M15 | **`source` assente o vuoto:** almeno un record con **`source = null`** **oppure** stringa **vuota** / **solo spazi** — la riga compare **normalmente** in lista (§ **o-fedeltà**); terza riga testuale = **`price_history_source_unspecified`** (§ **n**); **nessuna** riga saltata, **nessun** crash o layout rotto |

---

## Execution

### Esecuzione — 2026-03-29 (avvio workflow)

**Transizione `PLANNING → EXECUTION`:** effettuata dopo **review mirata** planning ↔ codice (nessuna modifica Kotlin/XML applicativa in questa transizione — solo documentazione).

**Verifiche codice effettuate (sintesi revisore):**

| Elemento | Esito |
|----------|--------|
| `PriceHistoryBottomSheet` | `ModalBottomSheet` + `SecondaryTabRow` + `LazyColumn`; item = `Row` + `SpaceBetween`, `effectiveAt` raw + `formatNumberAsRoundedString`; `padding(vertical = 8.dp)`; **no** `source` / empty — coerente con § Contesto aggiornato |
| `DatabaseScreen.kt` — `PriceHistoryBottomSheetHost` | `getPriceSeries(id, "PURCHASE"\|"RETAIL").collectAsState(emptyList())` → passa liste a `PriceHistoryBottomSheet` — coerente con planning |
| `DatabaseViewModel.getPriceSeries` | Delega a `repository.getPriceSeries` — **nessuna** API nuova richiesta dal task |
| `DefaultInventoryRepository.getPriceSeries` | → `priceDao.getSeries` |
| `ProductPriceDao.getSeries` | `ORDER BY effectiveAt DESC` — coerente con § **o-ordine** |
| `ProductPrice` / `PriceBackfillWorker` | `source` nullable; backfill `"BACKFILL_CURR"` — coerente con § **m** |
| Risorse `values*` | Presenti **`values/`**, **`values-en/`**, **`values-es/`**, **`values-zh/`** — **assente** `values-it/` (planning corretto il 2026-03-29) |
| Chiavi tab per § **q-empty** | `tab_purchase`, `tab_retail` già usate nel bottom sheet — adatte a `%1$s` |

**File modificati in questa transizione (solo docs):** `docs/TASKS/TASK-011-…md`, `docs/MASTER-PLAN.md`.

**Prossimo step esecutore:** implementare UI/stringhe secondo Planning; compilare sotto **Azioni eseguite** / **Check obbligatori** (formato `AGENTS.md`).

### Esecuzione — 2026-03-29 (codice)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — bottom sheet storico prezzi rifinito con riga verticale `prezzo → data+ora → source`, empty state testuale parametrizzato e helper `private` locali per formattazione data/source
- `app/src/main/res/values/strings.xml` — aggiunte stringhe italiane per empty state e mapping `source`; corretto escape XML in `price_history_source_import_prev`
- `app/src/main/res/values-en/strings.xml` — aggiunte stringhe inglesi per empty state e mapping `source`
- `app/src/main/res/values-es/strings.xml` — aggiunte stringhe spagnole per empty state e mapping `source`
- `app/src/main/res/values-zh/strings.xml` — aggiunte stringhe cinesi per empty state e mapping `source`

**Azioni eseguite:**
1. Sostituita la riga `Row` pre-task del bottom sheet con una struttura `Column` singola e compatta, mantenendo `ModalBottomSheet`, `SecondaryTabRow`, ordine della serie e `HorizontalDivider` standard.
2. Implementati helper UI locali e `private` nello stesso file per:
   - parsing `effectiveAt` con `yyyy-MM-dd HH:mm:ss`
   - formattazione locale-aware data+ora con fallback raw in caso di parse failure
   - mapping `source` per `MANUAL`, `IMPORT`, `IMPORT_PREV`, `BACKFILL_CURR`, `IMPORT_SHEET`, `null/blank` e ramo custom/non mappato (`SYNC` incluso) con `trim`, soglia 24 e ellissi U+2026
3. Aggiunta una sola stringa parametrizzata per l'empty state tab vuota e replicate in `values/`, `values-en/`, `values-es/`, `values-zh` tutte le nuove chiavi richieste dal task.
4. Eseguiti `assembleDebug` e `lint` usando il JBR di Android Studio (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) perché il terminale non aveva una JVM di default.
5. Corrette due micro-issue emerse durante i check:
   - escape dell'apostrofo nella risorsa italiana `price_history_source_import_prev`
   - lettura del locale in Compose spostata da `LocalContext.resources.configuration` a `LocalConfiguration.current` per rispettare lint

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 2s` sullo stato finale del diff |
| Lint | ✅ | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lint` → `BUILD SUCCESSFUL in 16s`; nessun nuovo problema lint nel diff finale |
| Warning nuovi | ✅ | Nessun warning nuovo imputabile ai file modificati; restano warning/deprecazioni preesistenti in `DatabaseScreenComponents.kt`, `HistoryScreen.kt` e configurazione Gradle |
| Coerenza con planning | ✅ | Diff confinato a `DatabaseScreenDialogs.kt` + `values*`; nessuna modifica a `DatabaseScreen.kt`, `EditProductDialog.kt`, DAO, repository, ViewModel business, schema DB o navigation |
| Criteri di accettazione | ⚠️ | Criteri statici/build verificati; smoke manuali UI previsti dal task non eseguibili in questo ambiente shell senza emulator/device attivo |

**Dettaglio criteri di accettazione:**
| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Empty state per tab senza eventi: solo testo localizzato, senza icona, una sola stringa parametrizzata | B + M | ⚠️ NON ESEGUIBILE | Implementata `price_history_empty_for_tab` e usata con `tab_purchase` / `tab_retail`; build/lint OK; verifica visiva tab vuote rimandata a smoke manuale |
| 2 | Ogni riga mostra prezzo / data+ora / source con fallback raw e fedeltà serie | B + M | ⚠️ NON ESEGUIBILE | Implementati tipografia, ordine, fallback raw di `effectiveAt`, mapping `source` dedicato, `SYNC` → custom, nessun filtro/raggruppamento/dedup; verifica visiva manuale pendente |
| 3 | Layout lista a colonna singola, overflow gestito, prezzo prioritario, compattezza senza overlap | M | ⚠️ NON ESEGUIBILE | `Column` con `titleSmall` / `bodyMedium` / `labelSmall`, `padding(vertical = 12.dp)`, `maxLines = 1` + `TextOverflow.Ellipsis`; serve smoke visivo su emulator/device |
| 4 | Nessuna regressione su sheet/tab/lista prodotti; `EditProductDialog` invariato salvo eccezioni | B + M | ⚠️ NON ESEGUIBILE | Nessun tocco a `EditProductDialog.kt`; build/lint OK; smoke tab-switch/dismiss/riapertura non eseguibile qui |
| 5 | Nessun cambio a contratti repository/DAO/schema/navigation | S | ✅ ESEGUITO | Diff limitato a UI locale + risorse stringa |
| 6 | `assembleDebug` OK; nessun nuovo problema lint rilevante sui file modificati; chiavi presenti in tutte le lingue richieste | B + S | ✅ ESEGUITO | `assembleDebug` e `lint` verdi; nessuna stringa hardcoded in Kotlin/Compose; chiavi replicate in `values-en`, `values-es`, `values-zh` oltre `values/` |
| 7 | Nessun nuovo stato in `DatabaseViewModel` né logica presentazionale in repository | S | ✅ ESEGUITO | Nessuna modifica a ViewModel/repository; helper confinati e `private` in `DatabaseScreenDialogs.kt` |
| 8 | `./gradlew test` N/A se diff solo UI bottom sheet + stringhe; obbligatorio altrimenti | B / S | ✅ ESEGUITO | N/A motivato: diff confinato a rendering bottom sheet + stringhe, nessun helper condiviso fuori file, nessuna logica non puramente presentazionale |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: N/A — il diff non tocca repository, ViewModel, import/export, history logic o altre aree coperte dalla baseline TASK-004
- Test aggiunti/aggiornati: nessuno
- Limiti residui: restano da eseguire solo gli smoke manuali UI del task (M1-M15) in ambiente con emulator/device

**Incertezze:**
- Nessuna sul perimetro del diff o sulle decisioni applicate
- Le verifiche manuali UI richieste dal task non sono eseguibili in questo ambiente shell senza emulator/device

**Handoff notes:**
- Eseguire in review gli smoke manuali M1-M15, con attenzione a M4-M5 (empty state), M10-M11 (custom/SYNC), M12-M14 (dark theme + locale non default + `source` lungo)
- In `values-zh` il task usa i tab label già presenti nel repo per il placeholder dell'empty state; eventuali correzioni semantiche di quelle etichette restano fuori scope TASK-011

### Esecuzione — 2026-03-29 (review tecnica mirata)

**Review svolta:**
1. Confronto puntuale tra planning/task e implementazione reale sui soli file del diff: struttura UI del bottom sheet, mapping `source`, gestione `effectiveAt`, risorse i18n, perimetro del diff e helper locali.
2. Verifica statica del composable: nessun redesign del bottom sheet, `Column` verticale coerente, tipografia corretta, `padding(vertical = 12.dp)`, `HorizontalDivider` standard, empty state solo testuale, nessun hardcode user-facing in Kotlin.
3. Verifica delle stringhe: stesso set di chiavi in `values`, `values-en`, `values-es`, `values-zh`; nessuna chiave extra oltre al set minimo richiesto dal task.

**Fix applicati:**
1. `app/src/main/res/values-zh/strings.xml` — allineato `price_history_source_custom` da `其他：%1$s` a `其他: %1$s` per rispettare il formato custom previsto dal task (`prefix: %1$s`).

**Check rieseguiti:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug` → `BUILD SUCCESSFUL in 3s` dopo il micro-fix |
| Lint | ✅ | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lint` → `BUILD SUCCESSFUL in 27s` dopo il micro-fix |
| Warning nuovi | ✅ | Nessun warning nuovo imputabile ai file del task dopo la review tecnica |

**Esito review tecnica:**
- Nessun altro mismatch reale trovato tra planning e codice implementato.
- Il diff resta locale ai file previsti dal task; nessuna modifica extra fuori perimetro.
- Il task è pronto per fase **REVIEW**; restano soltanto le verifiche manuali UI già documentate (non eseguite in shell).

---

## Review

_(Quando il task non è più **`BLOCKED`** per smoke — oppure review formale su diff già implementato.)_

**Nota tracking (2026-03-29):** con **TASK-011** in **`BLOCKED`**, una review “finale” / approvazione **`DONE`** resta **congelata** finché non eseguiti gli smoke manuali documentati in § **Execution** / criteri **M**.

**Controllo consigliato (non sostituisce criteri):** coerenza con § **s-bis** / **#6** — nessuna stringa hardcoded nei file del diff; traduzioni **`values-en` / `values-es` / `values-zh`** complete per ogni chiave nuova (oltre `values/`); § **s-ter** su dark theme se il revisore può verificare rapidamente.

**Perimetro file (obbligatorio in review):** confrontare l’elenco dei file nel diff con la tabella **File potenzialmente coinvolti** + nota **Review — file fuori lista**. Ogni file **extra** deve essere coperto da motivazione nel log Execution; in assenza → **FIX_REQUIRED** per scope creep (ripristino o split in altro task).

**Diff minimo:** verificare che non compaiano refactor cosmetici, cleanup massivi o rename non motivati rispetto a § **v**.

**Stringhe:** coerenza con § **q-empty** (un solo empty parametrizzato) e § **s-quinquies** / **s-quater** — niente chiavi duplicate o superflue.

**Fedeltà dati in lista:** coerenza con § **o-fedeltà** (nessun filtro o salto righe per `source` null/blank).

---

## Fix

_(Vuoto)_

---

## Chiusura

_(Vuoto)_

---

## Riepilogo finale

_(Da compilare a fine task)_

---

## Handoff

- **Stato corrente (tracking):** **`BLOCKED`** — sospeso per **mancanza smoke manuali**; vedi § **Stato BLOCKED** in cima al file.
- Execution completata lato codice con diff confinato ai file previsti dal task; review tecnica mirata registrata in § **Execution** (invariata).
- Build finale `assembleDebug` e `lint` verdi (dettaglio nel log Execution).
- `./gradlew test` non eseguito per criterio #8: diff solo UI bottom sheet + stringhe, nessuna baseline TASK-004 applicabile.
- **Pendente per sblocco:** checklist **M1–M15** su emulator/device, criteri con tipo **M**; poi chiusura formale **REVIEW → … → DONE** se applicabile.
