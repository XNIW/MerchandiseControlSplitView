# TASK-067 — Ottimizzazione sync cloud post full database import

---

## Informazioni generali

| Campo                 | Valore |
|----------------------|--------|
| ID                   | TASK-067 |
| Stato                | `DONE` |
| Priorità             | `ALTA` |
| Area                 | Sync catalogo cloud / Supabase — dirty marking post-import, push prodotti/prezzi, osservabilità, UX `CloudSyncIndicator` |
| Creato               | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-27 — review post-execution completata; chiusura confermata `DONE ACCEPTABLE` con fix osservabilità e test fallback conservativo |

---

## Dipendenze

- **TASK-066** `DONE` — navigazione `ImportAnalysis` → `DatabaseScreen` non va regressa; **non** modificare perimetro navigazione ImportAnalysis salvo evidenza di regressione.
- Riferimento contesto sync/UX: **TASK-059** `DONE`, **TASK-041** addendum sync (MASTER-PLAN), baseline **TASK-004** ove si tocca repository/ViewModel/import.

---

## Scopo

Dopo un **import database completo** confermato da `DatabaseScreen`, la sincronizzazione **cloud** risulta lenta e poco chiara: i log suggeriscono push quasi totale del catalogo e di gran parte della price history, nonostante l’apply locale segnali numeri molto inferiori (`new` / `updated`). Il task mira a **ridurre il lavoro inutile** (dirty marking e push sovradimensionati), **migliorare osservabilità** (metriche e log distinti per fase) e **migliorare UX** dell’indicatore di sync (fase, progress numerico, messaggio di completamento, chiarezza locale vs remoto).

---

## Contesto

**TASK-066** ha corretto il ritorno da `ImportAnalysis` a `DatabaseScreen`. Dopo conferma import da Database, la schermata torna correttamente a `DatabaseScreen`.

**Problema osservato:** parte la sync catalogo cloud/remota; la UI mostra notifica/progress in alto a destra, ma la sync sembra molto lenta e poco chiara. Da logcat sembra partire una sync quasi completa del catalogo, non una sync realmente parziale.

**Log osservato (esempio):**

- `FULL_IMPORT START` 15:41:55.853  
- `FULL_IMPORT SUCCESS products=19695 suppliers=57 categories=27 priceHistory=true` 15:42:16.723  
- `APPLY_IMPORT START previewId=1 new=829 updated=5992` 15:42:39.202  
- `APPLY_IMPORT SUCCESS previewId=1` 15:42:45.315  
- `import_dirty_marking productsTouched=19695 suppliersCreated=0 categoriesCreated=0`  
- `cycle=catalog_push outcome=ok reason=local_commit durationMs=2048679 dirtyHints=19695 productsPushed=19695 pricesPushed=68251`  
- `pushProductsMs=1900349`  
- `syncPricesMs=110141`  
- `phase_metrics PUSH_PRODUCTS productsTotal=19697 productsEvaluated=19695 productsDirty=19695 productsPushed=19695`  
- `phase_metrics SYNC_PRICES_PUSH pricesEvaluated=68251 pricesEligible=68251 pricesPushed=68251`  

**Interpretazione iniziale:**

- Il full import locale è relativamente veloce.  
- Il collo di bottiglia è la **sync cloud post-import**.  
- Anche se l’apply preview segnala `new=829 updated=5992`, la dirty marking marca `productsTouched=19695`.  
- Ciò fa partire push di quasi tutto il catalogo e di una porzione enorme di price history.  
- Verificare se la dirty marking è troppo ampia e se il push remoto lavora per record o con batch troppo piccoli.

**Outbox post-sync:** la classificazione di valori tipo `syncEventOutboxPending=353` è un **criterio operativo** in § Planning (“Outbox `syncEventOutboxPending`…”) e nel **criterio di accettazione #14**.

---

## Non incluso

- **Non** rompere **TASK-066** (navigazione ImportAnalysis).  
- **Non** cambiare navigazione ImportAnalysis salvo regressione documentata.  
- **Non** cambiare schema Room/Supabase senza reale necessità; **nessuna** migration/RPC/DDL **live** senza task separato e approvazione esplicita.  
- **Non** cambiare parser Excel / full import locale salvo stretta necessità dimostrata.  
- **Non** rimuovere sync cloud, `sync_events`, realtime, PriceHistory.  
- **Non** introdurre refactor architetturali ampi fuori perimetro.

---

## File da leggere prima dell’execution (fonti codice)

### Sync cloud / catalogo (tag log indicativi)

- Classi con log: `CatalogCloudSync`, `sync_phase_durations`, `phase_metrics`, `import_dirty_marking`, `dirtyHints`, `PUSH_PRODUCTS`, `SYNC_PRICES_PUSH`, `sync_events_summary` (ricerca nel repo).

### Import full DB

- `FullDbImportStreaming.kt`  
- `DatabaseViewModel.kt`  
- Repository / metodi `applyImport`  
- Metodi di **dirty marking** post import  

### Tracker / UI

- `CatalogSyncStateTracker`  
- `CloudSyncIndicator`  
- Eventuale `CatalogSyncViewModel`  
- `Application` / scheduler che avvia sync post local commit  

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Dopo full DB import, la navigazione resta corretta verso `DatabaseScreen` (nessuna regressione TASK-066) | B / M | ✅ ESEGUITO — navigation non modificata; test resolver TASK-066 mirati verdi |
| 2 | Import locale resta funzionante | B / M | ✅ ESEGUITO — `assembleDebug`, repository tests e full JVM verdi |
| 3 | Sync cloud post-import **non** marca dirty tutto il catalogo se solo una parte è realmente cambiata | S / M + log | ✅ ESEGUITO — test JVM delta 2/10 e 1/500; log `dirtyMarkedProducts` aggiunto |
| 4 | Se il file è identico al catalogo già sincronizzato, la sync è quasi no-op o molto più breve (evidenza numerica prima/dopo) | M + log | ✅ ESEGUITO (JVM) — identico bridged: product candidates `0`, price candidates `0`; live non eseguito |
| 5 | Con ~829 nuovi + ~5992 realmente aggiornati, il lavoro cloud è proporzionale a quei record, non a ~19.695 prodotti (salvo motivazione documentata se impossibile) | M + log | ✅ ESEGUITO (logica) — dirty set = nuovi + update reali; dataset reale non rieseguito |
| 6 | Price sync pusha solo prezzi nuovi/modificati quando possibile | S / M + log | ✅ ESEGUITO — price-only: `pushedProductPrices=1`, `pushedProducts=0`, coda prezzo svuotata |
| 7 | UI mostra chiaramente fase e progress della sync (cloud), senza percezione di blocco; messaggio tipo completamento catalogo cloud | M | ✅ ESEGUITO (static/build) — `CloudSyncIndicator` aggiornato; visual smoke non eseguito |
| 8 | Nessuna regressione su Supabase auth / realtime / `sync_events` | B / M | ✅ ESEGUITO (static/JVM) — nessuna modifica auth/realtime/transport; full JVM verde |
| 9 | Nessun cambio schema Supabase live senza task separato e approvazione | Review | ✅ ESEGUITO — nessun schema/RPC/migration/DDL/RLS/trigger/publication |
| 10 | `assembleDebug`, `lintDebug`, test JVM pertinenti OK | B / S | ✅ ESEGUITO — build, lint, test mirati e full JVM verdi |
| 11 | Smoke manuale: full DB import; sync post-import; import identico/no-op; pochi prodotti cambiati; Generated sync normale; pull/quick sync da Options se esiste | M | ⚠️ NON ESEGUIBILE — emulator rilevato, ma smoke cloud/live richiede dataset/account/rollback non documentati; non eseguito per safety |
| 12 | Rispetto di **§ Sicurezza dati (Safety)**: nessuna perdita sync per prodotti/prezzi che devono essere propagati; fallback conservativo documentato se remoto incerto | S / Review | ✅ ESEGUITO — fallback product ref per prezzi nuovi senza bridge; no dedup remota non verificabile |
| 13 | Dopo full import: al massimo **una** sync cloud catalogo principale per il `local_commit` **oppure** richieste duplicate **coalesciate** (comportamento documentato nel task, nessuna corsa non gestita) | S / M + log | ✅ ESEGUITO (static) — `dirtyHints` set + debounce + `tryBegin`/requeue documentati |
| 14 | **`syncEventOutboxPending` post-sync**: significato classificato nel file task (atteso temporaneo / self-skip / accumulo reale); **non** accumulo non gestito senza follow-up o fix locale sicuro documentato; **mai** cancellazione manuale outbox per “far sparire” il numero | S / Review / log | ⚠️ PARZIALE — significato tecnico documentato; valore live `353` non classificato senza monitor sicuro; follow-up indicato; nessuna pulizia manuale |
| 15 | **Coerenza post-sync** (oltre a `productsPushed` / `pricesPushed` ridotti): nel file task documentare almeno una verifica **proporzionata** al perimetro — vedi § **Sicurezza dati** → *Verifica di coerenza post-sync* | S / M / Review + log | ✅ ESEGUITO (JVM) — price-only push svuota coda; no-op/delta senza candidati inattesi; live non eseguito |

Legenda: B=Build, S=Static, M=Manual/emulator.

**Misurazione obbligatoria (documentare nel task prima/dopo):**

- durata apply locale  
- `productsTouched` / `dirtyHints` / `dirtyMarkedProducts` (o equivalente tracciato)  
- `productsPushed` / `pricesPushed`  
- `pushProductsMs` / `syncPricesMs` / `durationMs` totale  
- `syncEventOutboxPending` (pre/post ciclo, se rilevante)  

**Regola:** non dichiarare miglioramento performance senza **baseline numerica** (Gate 0) e **numeri reali** dopo nel file task.

---

## Sicurezza dati (Safety)

Vincoli di correttezza per l’execution (verificabili in review, non solo “ottimizza”):

- Nessun prodotto **nuovo** deve restare non sincronizzato sul cloud quando la policy di sync prevede il push.  
- Nessun prodotto **realmente modificato** deve essere saltato dal dirty marking in modo da perdere aggiornamenti remoti attesi.  
- Nessun prezzo **nuovo o modificato** necessario al modello cloud deve essere perso.  
- Se lo **stato remoto è incerto** (auth stale, ultima sync sconosciuta, scenario restore non classificato), preferire **push conservativo** rispetto a ottimizzazione rischiosa; documentare il ramo scelto.  
- La **no-op detection** (hash/checksum/confronto remoto) si applica solo quando il confronto è **affidabile** e documentato; altrimenti non usarla per ridurre il dirty set.  
- Se per una no-op detection robusta servisse un concetto di **checksum/version/hash remoto** o colonne non esistenti oggi, **non improvvisare**: aprire **task separato** (eventuale migration/RPC) con approvazione; in TASK-067 restare entro ciò che il codice/schema attuale consente.

### Fail-safe / escape hatch

- Qualunque ottimizzazione **delta-safe** **non** deve **rimuovere** la possibilità di una sincronizzazione **completa** o **forzata** quando serve (path utente o sistema già esistente).  
- Se esiste già **full sync** / **refresh catalogo** / **quick sync** da Options (o equivalente), **preservarlo**; non “ottimizzare” eliminando la via di recupero.  
- Se **non** esiste un comando chiaro per **riallineare tutto**, valutare in execution se documentare un **follow-up dedicato** — **non** implementarlo per forza in TASK-067 se fuori MVP.  
- In caso di **stato remoto incerto**, errore `sync_events`, **watermark** sospetto o **gap** rilevato, il sistema deve poter **ricadere** su push/pull **conservativo** (coerente con § Safety).  
- L’utente **non** deve restare bloccato in uno stato **“ottimizzato ma non recuperabile”**.

### Verifica di coerenza post-sync

Non basta mostrare `productsPushed` / `pricesPushed` più bassi: serve evidenza che l’ottimizzazione **non** ha rotto l’allineamento atteso.

Documentare nel file task **almeno una** verifica **proporzionata** al perimetro (quanto consentito dal codice attuale senza query remote extra non previste), ad esempio **ove applicabile**:

- conteggi **prodotti/prezzi** quando disponibili da log, UI o ispezioni locali;  
- **watermark** / `sync_events` coerenti (nessun gap ingiustificato documentato);  
- assenza di flag tipo **`manualFullSyncRequired`** (o equivalente nel codice) dopo un ciclo riuscito, salvo motivazione;  
- assenza di **gap** `sync_events` non spiegati;  
- **spot-check** di alcuni prodotti **nuovi/modificati** nel flusso sotto test;  
- verifica che i prodotti nuovi siano **visibili** dopo pull/refresh, se il flusso lo consente.

Se il codice **non** consente una verifica robusta senza **query remote** aggiuntive o **schema** nuovo, **documentare il limite** nel task e aprire **follow-up** (criterio **#15** può essere soddisfatto con motivazione + piano follow-up).

---

## Guardrail cloud/live

Vincoli operativi per l’execution e per qualsiasi verifica su ambiente reale:

- **Non** eseguire reset remoto, delete massivi, truncate, pulizia manuale di tabelle Supabase, né cancellazione **outbox** / `sync_events` senza **approvazione esplicita**.  
- **Non** fare test **distruttivi** sul catalogo cloud reale.  
- Se servono prove su **dataset grande live**, documentare **prima** nel task: account usato, dimensione dataset, **rischio**, possibilità di **rollback**.  
- Preferire **test JVM / locali** e **smoke controllati** prima di test live lunghi.  
- **Non** modificare RLS, RPC, DDL, publication, trigger o schema Supabase in TASK-067.  
- Se emerge necessità **backend/schema**, **fermarsi** e aprire **task separato**.  
- **Non** considerare “ottimizzazione riuscita” se rischia **incoerenza locale/cloud** o viola § Safety / § Guardrail.

---

## MVP del task

**Obiettivo minimo** per considerare TASK-067 **completabile** (chiusura con risultato utile, non necessariamente “tutto il backlog ideale”):

1. **Baseline numerica** documentata (Gate 0).  
2. **Root cause** del dirty marking (o del trigger sync) **identificata** e riportata nel task.  
3. **Dirty marking delta-safe** implementato **se fattibile** senza schema remoto nuovo, con fallback conservativo dove serve.  
4. **Test JVM** per no-op / delta (vedi fixture suggerite).  
5. **UX minima** su `CloudSyncIndicator`: fase, progress o stato chiaro, completamento (e errore/retry leggibile).  
6. **Misurazione prima/dopo** nel file task.

**Non è obbligatorio** chiudere in TASK-067:

- nuove **RPC** Supabase;  
- **redesign** completo del sync engine;  
- nuova **architettura outbox**;  
- schema remoto con **checksum/version** dedicato;  
- ottimizzazione **estrema** batch se il collo di bottiglia è già risolto dal dirty marking.

Se in execution emerge che il vero collo di bottiglia richiede questi interventi → **follow-up task** mirato, **non** espandere il perimetro di TASK-067.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Strategia: prima ridurre lavoro inutile, poi ottimizzare performance | Allineato a richiesta stakeholder | 2026-04-27 |
| 2 | Eventuale RPC/migration Supabase solo in task dedicato con approvazione | Governance MASTER-PLAN / CLAUDE | 2026-04-27 |
| 3 | Prima del fix aggressivo sul dirty set, chiarire la **semantica del full DB import** (restore vs aggiornamento ordinario) — vedi § Planning “Semantica full DB import…” | Evita ottimizzazioni che lasciano il cloud disallineato | 2026-04-27 |

---

## Planning (Claude / stakeholder)

### Semantica full DB import da chiarire prima del fix

L’execution deve **stabilire quale scenario è vero** (anche entrambi in rami diversi del codice), prima di restringere il dirty marking.

**A. Full DB import come restore / sorgente autorevole locale**

- Il file importato rappresenta la **fonte di verità locale**.  
- Il **cloud** può essere vecchio o diverso.  
- In questo caso un push **ampio** può essere **corretto**; l’ottimizzazione deve passare da **hash/checksum/no-op detection** quando il **remoto è già uguale** al locale, non dal silenziare il push a priori.

**B. Full DB import come aggiornamento catalogo ordinario**

- Cloud e locale sono **già quasi allineati**.  
- In questo caso marcare dirty ~19.695 prodotti quando l’apply segnala `new=829 updated=5992` è **probabilmente eccessivo** e va ristretto con criteri misurabili.

**Vincolo operativo:** **non** ridurre il dirty set in modo aggressivo finché non è chiaro che **non** si rischia di lasciare il cloud disallineato rispetto alla policy di sync attesa. In dubbio, **fallback conservativo** + documentazione nel log di execution.

### Analisi richiesta (pre-codice in execution)

1. Tracciare il percorso: **full DB import → preview → confirm/apply locale → dirty marking → catalog cloud sync**.  
2. Capire perché `productsTouched=19695` anche se `new=829 updated=5992`.  
3. Capire se `updated=5992` rappresenta prodotti realmente cambiati o solo “presenti nel file”.  
4. Capire se il repository aggiorna righe anche quando i campi non cambiano.  
5. Capire se la dirty marking usa “tutti i prodotti importati” invece di “prodotti realmente changed”.  
6. Capire se price sync push usa tutta la price history invece di soli eventi nuovi.  
7. Capire dimensione batch attuale per prodotti/prezzi e se ci sono chiamate remote per singolo record.  
8. Verificare se Supabase usa upsert bulk o RPC.  
9. Verificare se il limite è rete/API, batch size, serializzazione JSON, query Room, o logica dirty troppo ampia.  
10. Classificare `syncEventOutboxPending` post-sync secondo § **Outbox `syncEventOutboxPending` — criterio operativo** (non limitarsi a “è alto/basso”).

#### Coalescing / concorrenza sync

- Verificare se dopo full import partono **più sync** ravvicinate (es. bootstrap, push, drain realtime, `local_commit`).  
- Verificare che **`sync_busy`** (o equivalente) eviti davvero la **concorrenza** e **non perda** richieste (o che le **coalesca** in modo documentato).  
- Verificare se un full import grande genera **più** trigger `local_commit` o **un solo** trigger coalesciato.  
- Verificare se una sync lunga può essere **interrotta** da realtime reconnect / auth refresh.  
- Verificare se il **tracker UI** riceve update per **record singolo** o per **batch** (allineare UX al modello reale).

#### Ipotesi tecniche da verificare

- `productsTouched=19695` potrebbe derivare dal fatto che il full import considera **“toccati” tutti i prodotti presenti nel file**, non solo quelli **cambiati** rispetto al DB.  
- `updated=5992` potrebbe significare **“prodotti da aggiornare secondo la preview”**, ma non necessariamente **“prodotti realmente diversi dopo normalizzazione”** rispetto alle righe Room esistenti.  
- `applyImport` o il full import potrebbero **aggiornare righe** anche quando i **campi sono identici**, generando dirty marking inutile.  
- Il confronto pre-update potrebbe **non normalizzare** correttamente numeri, null vs stringhe vuote, supplier/category id, prezzi arrotondati.  
- Il **push prodotti** è probabilmente il collo di bottiglia principale: nell’esempio `pushProductsMs=1900349` è molto più alto di `syncPricesMs=110141`.  
- `syncEventOutboxPending` dopo sync richiede **classificazione operativa** (vedi sotto), non solo una lettura numerica.

### Outbox `syncEventOutboxPending` — criterio operativo

Valori tipo **`syncEventOutboxPending=353`** vanno classificati in execution e **documentati nel file task**:

| Classe | Significato indicativo |
|--------|-------------------------|
| **Atteso e temporaneo** | Coda in drenaggio normale subito dopo il ciclo; si risolve senza intervento o nel ciclo successivo. |
| **Atteso (self-skip / già gestiti)** | Eventi che restano contati ma sono **no-op** o gestiti da logica esistente; il numero non indica lavoro pendente reale. |
| **Accumulo reale** | Coda che **non** converge, errori ripetuti, o stato incoerente; richiede analisi. |

**Regole:**

- Se è **accumulo reale**, il fix resta in TASK-067 **solo** se **locale**, **sicuro** e nei **Guardrail**; altrimenti **follow-up** dedicato.  
- **Non** cancellare manualmente l’outbox (né altre tabelle) per far sparire il contatore.  
- Il criterio di accettazione **#14** richiede che il significato post-sync sia **spiegato**, non lasciato ambiguo.

### Fasi preferite (allineate ai gate)

**Fase A — Osservabilità (Gate 1)**

- Metriche più precise se mancanti, es.: `dirtyMarkedProducts`, `actuallyChangedProducts`, `insertedProducts`, `updatedProducts`, `unchangedProducts`, `dirtyMarkedPrices`, `pushedProducts`, `pushedPrices`, `batchSize`, `batchCount`, `avgBatchMs`.  
- Log separati per **full import locale** vs **sync cloud**.

**Fase B — Dirty marking corretta (Gate 2)**

- Marcare dirty solo insert/modifiche **reali** quando **sicuro**; mantenere **fallback** full dirty / push conservativo se lo stato remoto non è affidabile (coerente con scenario **A** restore).  
- Valutare “full import no-op detection” solo con confronto affidabile (vedi Safety).  
- Non marcare prodotti identici; non marcare price history intera se i prezzi non sono cambiati — quando i gate e la semantica A/B lo consentono.

**Fase C — Price history delta (Gate 3)**

- Allineata al perimetro prezzi; non perdere eventi necessari.

**Fase D — Push remoto (Gate 4)**

- Batch/upsert solo se il collo di bottiglia resta **dopo** dirty marking; nessuna RPC/migration Supabase **live** in questo task.

**Fase E — UX (Gate 5)**

- `CloudSyncIndicator` deve distinguere chiaramente:  
  - **“Database locale importato”** (o equivalente: l’utente può usare il catalogo locale).  
  - **“Sincronizzazione cloud in background”** (non bloccare la percezione di utilizzo del DB locale).  
- **Fase corrente** esplicita, in ordine coerente con il motore di sync, es.: **fornitori** → **categorie** → **prodotti** → **prezzi** → **eventi** (adeguare i label ai nomi reali delle fasi nel codice).  
- **Progress numerico** quando il tracker espone conteggi, es.:  
  - `Prodotti 4.320 / 19.695`  
  - `Prezzi 12.000 / 68.251`  
- **Stato finale:** messaggio tipo **“Catalogo cloud sincronizzato”** solo quando il tracker conferma completamento; in errore recuperabile, **azione “Riprova”** (o pattern già usato nell’app).  
- **Vincolo UX:** l’utente deve capire che può **già usare il Database locale** mentre il cloud completa la sync.  
- **Progress e batch:** il progress **non** deve suggerire “1 prodotto al secondo” se il motore lavora a **batch**; preferire aggiornamenti per **batch/fase** con conteggi **reali** quando disponibili: prodotti **valutati / dirty / pushati**; prezzi **valutati / pushati**; **fase corrente**.  
- **Percentuale / indeterminato:** se non esiste una **percentuale affidabile**, usare stato **indeterminato** ma testo chiaro, es.: *“Sincronizzazione prodotti…”*, *“Sincronizzazione prezzi…”*, *“Il database locale è già disponibile”*.  
- **Completamento:** feedback **breve** e **non invasivo**.  
- **Errore/retry:** copy **chiaro per l’utente**, non solo messaggi tecnici da log.  
- **Copy da distinguere esplicitamente** (stringhe finali da allineare a L10n esistente):  
  - *Import locale completato* (o equivalente dopo conferma apply).  
  - *Sincronizzazione cloud in corso*.  
  - *Sincronizzazione cloud completata*.  
  - *Sincronizzazione cloud non completata* + invito a **Riprova** (copy non tecnico).  
- **Evitare** copy ambiguo: es. solo *“Receiving catalog”* se in realtà si sta facendo **push** prodotti/prezzi — allineare il testo alla **direzione** e **fase** reali.  
- Sync lunga: copy **rassicurante**, es. *“Puoi usare il database locale mentre il cloud si aggiorna.”*

### Piano di esecuzione a gate

**Gate 0 — Baseline numerica**

- Riprodurre il flusso o raccogliere log **equivalenti** al problema.  
- Documentare nel file task una **tabella baseline** (prima di qualsiasi fix comportamentale):  
  - `productsTouched`  
  - `dirtyHints`  
  - `productsPushed`  
  - `pricesPushed`  
  - `pushProductsMs`  
  - `syncPricesMs`  
  - `durationMs`  
  - `syncEventOutboxPending` (e contesto: quando misurato)  
- **Osservazione memoria / GC (non-scope):** durante baseline, notare se i log mostrano **GC frequenti** in import/apply/sync. **Non** trasformare TASK-067 in task generico memoria/performance; se GC/memoria emerge come **causa primaria** di lentezza o UI jank, **documentare** e aprire **follow-up** separato, salvo **micro-fix locale** ovvio e a costo zero di scope creep.  
- **Non** dichiarare miglioramento senza questa baseline.

**Gate 1 — Osservabilità**

- Aggiungere **solo** metriche/log mancanti, **senza** cambiare comportamento di sync/dirty.  
- Se la causa è già evidente (es. “dirty = tutte le righe del file”), pianificare Gate 2 con evidenza.

**Gate 2 — Dirty marking delta-safe**

- Marcare dirty solo prodotti **realmente inseriti/modificati** quando la semantica **B** e la Safety lo consentono.  
- Mantenere **fallback** full dirty / push conservativo se lo stato remoto non è affidabile (semantica **A** o incertezza).  
- Evitare regressioni sul **restore** full DB.

**Gate 3 — Price history delta**

- Verificare se `pricesPushed=68251` (o ordini di grandezza simili) è **necessario** o sovradimensionato.  
- Pushare solo eventi prezzo **nuovi/modificati** quando possibile, senza perdere audit necessario.  
- Non deduplicare senza capire **chiave logica** (es. `effectiveAt`, sorgente).

**Gate 4 — Push performance**

- Ottimizzare batch/upsert **solo se** il collo di bottiglia resta dopo Gate 2–3.  
- **Non** introdurre RPC/migration Supabase **live** in TASK-067.  
- Se `productsPushed` scende ma `pushProductsMs` no, documentare che il limite è **batch/API/rete** (evidenza).

**Gate 5 — UX**

- Migliorare `CloudSyncIndicator` usando **metriche reali** già esposte dal tracker/ViewModel.  
- Fase, conteggi (valutati/dirty/pushati ove possibile), completamento/errore come in **Fase E** sopra; allineare la **granularità** del progress al modello **batch** del motore.  
- Il database locale resta utilizzabile durante la sync cloud.

Ordine consigliato in execution: **0 → 1 → (analisi) → 2 → 3 → 4 → 5**. Saltare un gate solo se documentato (es. nessun cambio prezzi nel perimetro del test).

### Target performance indicativi

Soglie **non rigide in ms assoluti**: il criterio è il **confronto prima/dopo** sulla stessa macchina/rete quando possibile.

- **File identico** a catalogo già sincronizzato (scenario **B** + confronto affidabile): target **push quasi no-op**; `productsPushed` e `pricesPushed` **molto bassi o zero**, salvo motivazione documentata (es. eventi obbligatori).  
- **Import** con `new=829` e `updated=5992` **realmente** cambiati: target `productsPushed` **vicino** a `new + actuallyChanged`, **non** al totale catalogo (~19k), salvo scenario **A** o fallback conservativo documentato.  
- `pushProductsMs` dovrebbe **ridursi in modo proporzionale** alla riduzione di `productsPushed`; se non si riduce, trattare come segnale che il collo di bottiglia è **batch/API/serializzazione/rete** e documentarlo.  
- Evitare obiettivi numerici fissi non ancorati a baseline; ogni claim di miglioramento richiede **numeri nel file task**.

### Rischi identificati

- **Ridurre dirty marking e saltare record necessari** al cloud.  
  - *Mitigazione:* fallback conservativo quando lo stato remoto è incerto; semantica A/B chiarita; Safety.  
- **No-op detection errata** per differenze di **normalizzazione** (null/vuoto/numeri arrotondati).  
  - *Mitigazione:* confronto normalizzato; test JVM e casi limite documentati.  
- **Price history** apparentemente duplicata ma **necessaria per audit**.  
  - *Mitigazione:* non deduplicare senza chiaro modello di chiave logica / `effectiveAt` / sorgente.  
- **UX troppo ottimistica** (“tutto ok” mentre la sync fallisce o è in corso).  
  - *Mitigazione:* completamento solo con tracker in stato **completato**; stati errore e **Riprova** visibili.  
- **Task troppo grande** (sync + schema + RPC).  
  - *Mitigazione:* execution per **gate**; se emerge bisogno di RPC/migration o redesign profondo, **task separato** + non espandere TASK-067 oltre i vincoli.  
- **Correttezza vs performance (generale):** restringere troppo il dirty set senza scenario chiaro.  
  - *Mitigazione:* vedi § Semantica full DB import e Safety.  
- **Dataset grandi:** test JVM da soli possono non riprodurre tempi reali.  
  - *Mitigazione:* smoke manuale (criteri di accettazione).  
- **Backend:** bulk upsert potrebbe richiedere migration/RPC → fuori scope TASK-067 senza nuovo task.  
- **Prove su cloud live** senza piano: rischio dati o conclusioni false.  
  - *Mitigazione:* § **Guardrail cloud/live**; preferire JVM/local prima.  
- **Pressione GC / memoria** durante import/sync: può mascherare o dominare la lentezza rispetto al solo push rete.  
  - *Mitigazione:* nota in **Gate 0**; nessun redesign memoria in TASK-067 salvo micro-fix; follow-up se causa primaria.

### Test JVM mirati (suggeriti)

Da implementare in **EXECUTION** quando si tocca la logica; qui solo **intento atteso** (non eseguiti in PLANNING):

| Scenario | Atteso (qualitativo) |
|----------|----------------------|
| Import / full import **identico** (nessun delta reale) | `dirtyMarkedProducts` vicino a **0** o comunque **molto inferiore** al totale catalogo; dirty prezzi vicino a **0** se la price history è identica. |
| Import con **pochi** prodotti modificati | Dirty set **proporzionale** ai prodotti realmente cambiati. |
| Import con **solo prezzi** cambiati | Prodotti non dirty se i campi prodotto non cambiano; dirty sugli **eventi prezzo** solo dove necessario. |
| Import con supplier/category **invariati** | Non marcare prodotti dirty **solo** per relazioni già equivalenti. |
| **Restore** autorevole con remoto potenzialmente stale (scenario **A**) | **Fallback conservativo** documentato anche se più lento; nessuna perdita di allineamento atteso. |

#### Fixture / dataset controllati (JVM)

- **Dataset sintetici piccoli** (preferibilmente **senza cloud reale**) per no-op/delta, es.:  
  - 10 prodotti **identici**;  
  - 10 prodotti con **2 modificati**;  
  - prezzi **invariati** vs **modificati**;  
  - supplier/category **equivalenti** (stesso risultato logico, rappresentazioni diverse se applicabile).  
- Almeno un test con dataset **medio/grande simulato** (se fattibile in JVM senza Supabase), per escludere **O(n²)** o query inefficienti sul percorso dirty/compare.  
- Verificare che il **confronto normalizzato** non marchi dirty per differenze solo **cosmetiche**:  
  - `null` vs stringa vuota;  
  - numeri arrotondati;  
  - “ordine” di relazioni se irrilevante al modello;  
  - supplier/category già **equivalenti**.

---

## Stop conditions

Durante execution **fermarsi** e chiedere decisione / creare **follow-up** se:

- serve **schema remoto** nuovo;  
- serve **RPC** Supabase o cambi **DDL/RLS/publication/trigger**;  
- serve **migrazione live**;  
- serve cambiare il **protocollo** `sync_events` / semantica outbox a livello contratto;  
- il fix **delta-safe** non è **dimostrabile** senza rischio dati accettabile;  
- i test indicano **possibile perdita di sync** o incoerenza locale/cloud.

---

## Criterio di chiusura

Definisce come dichiarare la chiusura in review / conferma utente (etichette indicative; allineare a governance `MASTER-PLAN` / file task **Chiusura**).

### `DONE` (FULL)

Usare **solo** se **tutti** i punti seguenti sono soddisfatti con evidenza nel file task:

- baseline documentata (**Gate 0**);  
- **root cause** identificata;  
- dirty marking **delta-safe** implementato **oppure** motivazione documentata con **fallback** accettato e fail-safe integro;  
- test **JVM** pertinenti aggiornati/passanti;  
- **UX minima** migliorata (fase/progress/completamento/errore, copy non ambiguo);  
- **prima/dopo** documentato (metriche + § coerenza post-sync **#15**);  
- nessuna regressione **TASK-066**, **sync**, **auth**, **realtime** rispetto ai criteri.

### `DONE` (`ACCEPTABLE`) o `PARTIAL`

Ammissibile se:

- baseline e root cause sono **documentate**;  
- l’**osservabilità** è migliorata (metriche, classificazione outbox, coalescing chiarito);  
- ma il dirty marking **sicuro** al livello desiderato richiede **schema/RPC/protocollo remoto** → si apre **follow-up** invece di forzare TASK-067.

In questo caso **non** dichiarare “performance **risolta**” in senso assoluto; formulare come **diagnosticata** / **parzialmente migliorata** con limite e prossimo task.

---

## Execution

### Gate 0 — 2026-04-27 — Baseline, call graph e root cause candidata

**File letti:**
- `docs/MASTER-PLAN.md`
- `docs/TASKS/TASK-067-ottimizzazione-sync-cloud-post-full-import.md`
- `docs/TASKS/TASK-066-fix-importanalysis-database-return-navigation.md`
- `docs/CODEX-EXECUTION-PROTOCOL.md`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/FullDbImportStreaming.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductPriceDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductRemoteRefDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/CloudSyncIndicator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`

**Baseline osservata dal log fornito prima di qualsiasi fix comportamentale:**

| Metrica | Valore baseline | Note |
|---|---:|---|
| Durata lettura/analyze full import locale | ~20.870 ms | `FULL_IMPORT START` 15:41:55.853 → `FULL_IMPORT SUCCESS` 15:42:16.723 |
| Durata apply locale | ~6.113 ms | `APPLY_IMPORT START` 15:42:39.202 → `APPLY_IMPORT SUCCESS` 15:42:45.315 |
| Products sheet rows | 19.695 | `FULL_IMPORT SUCCESS products=19695` |
| Suppliers / categories rows | 57 / 27 | `FULL_IMPORT SUCCESS suppliers=57 categories=27` |
| Apply preview new / updated | 829 / 5.992 | `APPLY_IMPORT START previewId=1 new=829 updated=5992` |
| `productsTouched` | 19.695 | `import_dirty_marking productsTouched=19695` |
| `dirtyHints` | 19.695 | `cycle=catalog_push ... dirtyHints=19695` |
| `productsPushed` | 19.695 | `cycle=catalog_push ... productsPushed=19695` |
| `pricesPushed` | 68.251 | `cycle=catalog_push ... pricesPushed=68251` |
| `pushProductsMs` | 1.900.349 ms | fase dominante |
| `syncPricesMs` | 110.141 ms | molto inferiore a push prodotti |
| `durationMs` ciclo cloud | 2.048.679 ms | ~34,1 minuti |
| `syncEventOutboxPending` | 353 (da contesto task) | Da classificare post-fix; il log primario riportato nel task non include la riga completa. |
| GC / memoria | Nessuna evidenza nel log fornito | Non trasformare TASK-067 in task memoria senza nuova evidenza. |

**Call graph ricostruito:**
1. `DatabaseScreen` avvia full DB import tramite `DatabaseViewModel.startFullDbImport(...)`.
2. `startFullDbImport` carica lo snapshot locale con `repository.getAllProducts()`.
3. `analyzeFullDbImportStreaming(...)` legge workbook e passa `Products` a `ImportAnalyzer.analyzeStreamingDeferredRelations(...)`; `PriceHistory` viene raccolto in `pendingPriceHistory`.
4. `ImportAnalyzer` produce `newProducts` e `updatedProducts` solo quando i campi prodotto risultano cambiati secondo confronto normalizzato lato analyzer.
5. `publishPreviewAnalysis(...)` conserva `pendingPriceHistory`, supplier/category pending e origine `DATABASE`.
6. Conferma da `ImportAnalysisScreen` chiama `DatabaseViewModel.importProducts(...)`.
7. `importProducts` costruisce `ImportApplyRequest` e chiama `repository.applyImport(...)`.
8. `DefaultInventoryRepository.applyImportAtomically(...)` risolve relazioni, inserisce/aggiorna prodotti, inserisce price history e marca dirty.
9. `applyImport(...)` chiama `notifyProductCatalogChanged(productId)` per ogni id restituito.
10. In `MerchandiseControlApplication`, `onProductCatalogChanged` chiama `CatalogAutoSyncCoordinator.onLocalProductChanged(productId)`.
11. `CatalogAutoSyncCoordinator` accumula `dirtyHints` in `LinkedHashSet`, debounce 2s, poi avvia `runPushCycle("local_commit")`.
12. Il ciclo cloud usa `syncCatalogQuickWithEvents(...)` se `sync_events` e' configurato, altrimenti `pushDirtyCatalogDeltaToRemote(...)`.
13. `pushCatalogProducts(...)` valuta `ProductDao.getCatalogPushCandidates()` e pusha i product remote ref dirty.
14. `pushProductPricesToRemote(...)` valuta `ProductPriceDao.getAllForCloudPush()` e pusha righe prezzo senza `product_price_remote_refs`, con `requireProductSynced=true` nel ciclo quick.

**Coalescing / concorrenza osservati staticamente:**
- `dirtyHints` e' un set: più notifiche dello stesso prodotto vengono coalesciate.
- `pushTickle` ha buffer 1 + `DROP_OLDEST` + debounce 2s: trigger ravvicinati vengono compressi.
- `CatalogSyncStateTracker.tryBegin(...)` impedisce sync concorrenti; se `AUTO_PUSH` trova il tracker busy, re-inserisce gli hint e logga `reason=sync_busy dirtyHints=...`.
- Non ho ancora evidenza live di più cicli post-import; la semantica statica è “coalescing con retry degli hint se busy”.

**Root cause candidata scritta prima di modificare comportamento:**
- In `applyImportAtomically`, `allBarcodes` viene costruito da `resolvedNewProducts + resolvedUpdatedProducts + request.pendingPriceHistory`.
- In un full DB import con sheet `PriceHistory` completa, `request.pendingPriceHistory` può contenere quasi tutto il catalogo anche se i prodotti invariati non sono in `updatedProducts`.
- `persistedProducts = productDao.findByBarcodes(allBarcodes)` ricarica quindi quasi tutti i prodotti referenziati dalla price history.
- `touchedProductIds = persistedProducts.map { it.id }.toSet()` viene passato a `touchProductDirty(...)` e poi a `notifyProductCatalogChanged(...)`.
- Risultato: prodotti invariati ma presenti nella price history diventano dirty hints, causando `productsTouched=19695`, `dirtyHints=19695` e push quasi totale.
- `pricesPushed=68251` è coerente con `ProductPriceDao.getAllForCloudPush()`: vengono pushate le righe `product_prices` senza bridge `product_price_remote_refs` quando il prodotto ha un `product_remote_ref`. Se la full import crea/dirty-mark-a refs prodotto inutilmente, può rendere eleggibile una grossa porzione di storico prezzi. Per eventi prezzo già bridged il DAO è già delta-safe; per righe locali senza bridge non si può dedurre che il remoto le abbia già senza query/schema aggiuntivo.

**Semantica A/B e fallback candidato:**
- Scenario B ordinario/delta: se il prodotto ha remote ref affidabile e i campi prodotto/prezzo non cambiano, non va incrementata la revisione prodotto solo perché il barcode compare nel file o nella price history.
- Scenario A restore/stato remoto incerto: se un prodotto necessario alla sync prezzo non ha `product_remote_ref`, oppure il ref non e' mai stato applicato, il fallback resta conservativo: il prodotto deve rimanere eleggibile al push prima dei prezzi.
- Nessun codice comportamentale modificato fino a questo punto.

### Esecuzione — 2026-04-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — dirty marking post-import ristretto a prodotti nuovi/modificati reali; trigger sync separato per nuovi eventi prezzo; fallback conservativo per price rows senza product remote ref; log `import_dirty_marking` ampliato; metriche batch/call per push prodotti/prezzi.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductPriceDao.kt` — nuovo insert batch con ritorno degli id inseriti per distinguere price history nuova vs duplicata.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — fixture TASK-067 per no-op, delta prodotti, solo prezzi, relazioni equivalenti e dataset medio.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/CloudSyncIndicator.kt` — UX minima: fase cloud + hint database locale disponibile, completamento/errore espliciti.
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml` — copy localizzato per indicatore cloud e prezzo non ambiguo.
- `docs/TASKS/TASK-067-ottimizzazione-sync-cloud-post-full-import.md` — baseline, root cause, execution, verifiche e chiusura.
- `docs/MASTER-PLAN.md` — governance aggiornata a TASK-067 `DONE`.

**Azioni eseguite:**
1. Gate 1 osservabilità: aggiunte metriche `insertedProducts`, `updatedProducts`, `unchangedProductUpdates`, `dirtyMarkedProducts`, `priceHistoryRows`, `priceHistoryInserted`, `dirtyMarkedPrices`, `priceOnlyProducts`, `priceProductRefsCreated`; aggiunte metriche `batchSize`, `batchCount`, `avgBatchMs` su push prezzi e chiamate push prodotti.
2. Gate 2 dirty marking: `applyImportAtomically` non usa più tutti i barcode di `pendingPriceHistory` come dirty prodotti; marca dirty prodotto solo per insert reali e update realmente diversi dopo normalizzazione.
3. Gate 2 fallback: se un evento prezzo nuovo riguarda un prodotto senza `product_remote_ref`, viene creato il ref minimo per rendere il prodotto eleggibile al push prima del prezzo; se il prodotto e' già sincronizzato, non si incrementa la revisione prodotto.
4. Gate 3 price history delta: gli eventi prezzo duplicati non vengono reinseriti e non generano sync; eventi prezzo nuovi restano candidati push tramite `product_price_remote_refs` mancanti. Non viene deduplicato nulla lato remoto senza bridge/schema/RPC.
5. Gate 4 push performance: nessuna ottimizzazione batch prodotti applicata; il collo di bottiglia baseline era causato soprattutto dal dirty set sovradimensionato. Il push prodotti resta one-row-per-call per non ampliare il perimetro con recovery bulk/409.
6. Gate 5 UX: l’indicatore distingue meglio sync cloud in corso vs locale già usabile, mostra completamento “catalogo cloud sincronizzato”, errore retry comprensibile e usa “sincronizzazione prezzi” invece di copy di sola ricezione.
7. Review autonoma diff: verificato scope, nessun cambio Room/Supabase schema, nessuna RPC/migration/DDL/RLS/trigger, nessun reset remoto/outbox, nessuna modifica navigation TASK-066.

**Prima / dopo misurato in JVM controllato:**

| Scenario | Prima attesa dal codice baseline | Dopo patch | Evidenza |
|---|---:|---:|---|
| Full import identico con price history già bridged | `productsTouched` proporzionale ai barcode in `pendingPriceHistory` | `productsTouched=0`, product candidates `0`, price candidates `0` | Test `067 full import with identical bridged price history does not dirty the catalog` |
| Full import con 2 prodotti modificati + price sheet completa (10 prodotti) | fino a 10 dirty hints se price sheet contiene tutti | 2 prodotti notificati/dirty, price candidates `0` | Test `067 full import with few modified products marks only changed products dirty` |
| Solo prezzo nuovo su prodotto già sincronizzato | prodotto poteva essere dirty-marked insieme alla price history | `productsPushed=0`, `pushedProductPrices=1`, coda prezzo svuotata dopo push | Test `067 full import with only a new price event does not dirty product fields` |
| Supplier/category equivalenti dopo temp resolution | rischio dirty prodotto per relazione solo apparentemente diversa | `productsTouched=0`, product candidates `0` | Test `067 supplier and category equivalent after temp resolution do not dirty product` |
| Dataset medio 500 prodotti + price sheet completa + 1 modifica reale | rischio dirty set proporzionale a 500 | 1 prodotto notificato/dirty, price candidates `0` | Test `067 medium full import price sheet does not scale dirty set with total rows` |

**Cosa NON è cambiato:**
- Nessuno schema Room o Supabase.
- Nessuna migration, RPC, DDL, RLS, trigger, publication.
- Nessun reset remoto, delete massivo, truncate, pulizia manuale outbox o `sync_events`.
- Nessuna modifica a navigazione `ImportAnalysis` / TASK-066.
- Nessun redesign sync engine; `sync_events` e fallback full/quick sync restano invariati.
- Nessun bulk product upsert nuovo: il push prodotti resta seriale per preservare recovery/bridge esistenti.

**Outbox `syncEventOutboxPending`:**
- Il valore baseline `353` non è stato cancellato né manipolato.
- Staticamente rappresenta righe outbox ancora pendenti dopo retry; può essere temporaneo se converge al ciclo successivo, oppure accumulo reale se resta alto con errori ripetuti.
- In questa execution non ho eseguito smoke live distruttivo/remote; quindi il valore baseline specifico non è classificabile con certezza post-fix. Follow-up consigliato: monitor non distruttivo su più cicli con log `sync_events_summary` e `cycle=catalog_push`, senza pulizia manuale.

**Coerenza post-sync proporzionata al perimetro:**
- Nel test price-only, dopo `pushDirtyCatalogDeltaToRemote`, `pushedProductPrices=1`, `pushedProducts=0` e `ProductPriceDao.getAllForCloudPush()` torna vuoto: il nuovo evento prezzo non resta perso e non forza dirty prodotto.
- Nei test no-op/delta con price history bridged, non restano candidati product/price push inattesi.
- Limite: non è stata verificata coerenza remota live perché richiederebbe account/dataset/rollback espliciti; nessuna query remota extra o schema nuovo introdotti.

**Check obbligatori:**
| Check | Stato | Note |
|---|---|---|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → `BUILD SUCCESSFUL`; warning AGP/toolchain preesistenti |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin/lint nuovo nel codice modificato; `git diff --check` verde |
| Coerenza con planning | ✅ ESEGUITO | Gate 0→5 rispettati; Gate 4 limitato a metriche perché Gate 2/3 riducono il lavoro inutile |
| Criteri di accettazione | ⚠️ PARZIALE | Criteri statici/JVM soddisfatti; smoke manuale/live non eseguito per safety e assenza piano dati cloud/rollback |

**Baseline regressione TASK-004 (applicabile):**
- Test eseguiti:
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest'` → `BUILD SUCCESSFUL`
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest'` → `BUILD SUCCESSFUL`
  - Primo `./gradlew testDebugUnitTest` con daemon: ❌ fallito per attach MockK/ByteBuddy (`AttachNotSupportedException`) già noto come issue ambientale.
  - `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true' ./gradlew --no-daemon testDebugUnitTest` → `BUILD SUCCESSFUL`
- Test aggiunti/aggiornati: 5 casi TASK-067 in `DefaultInventoryRepositoryTest`.
- Limiti residui: nessun test UI Compose/Espresso; smoke live non eseguito.

**Smoke manuale/emulatore:**
- `adb` non è nel `PATH`; disponibile come `/Users/minxiang/Library/Android/sdk/platform-tools/adb`.
- Device rilevato: `emulator-5554 device`.
- Non ho eseguito full DB import / cloud sync live da UI: il task vieta test distruttivi o cloud live senza account, dataset, rischio e rollback documentati. I flussi cloud sono quindi coperti da JVM/local e review statica.
- TASK-066: nessun file navigation modificato; resolver TASK-066 mirato rieseguito verde.

**Incertezze:**
- Il valore live `syncEventOutboxPending=353` resta da classificare con monitor non distruttivo su ambiente reale.
- La riduzione reale di `pushProductsMs` / `durationMs` sul dataset da 19.695 prodotti non è stata misurata in live; la riduzione del lavoro è dimostrata sui candidati/dirty set JVM.

**Handoff notes:**
- Se dopo questa patch `productsPushed` resta alto su dataset reale, controllare se i `product_remote_refs` sono mancanti/non applicati: in quel caso il fallback conservativo sta facendo riallineamento, non no-op.
- Se `pricesPushed` resta alto ma `product_price_remote_refs` sono assenti, non deduplicare “a vista”: serve verifica remota/schema/RPC dedicata per distinguere backfill necessario da duplicato remoto.

---

## Review

### Review — 2026-04-27

**Ambito controllato:**
- Dirty marking delta-safe in `InventoryRepository.applyImportAtomically(...)`.
- Price history import/push e `ProductPriceDao.insertAllReturningIds(...)`.
- Sync cloud/outbox/coalescing tramite lettura statica dei path `local_commit`, candidati push e progress tracker.
- Performance locale: assenza di loop O(n²) evidenti introdotti dalla patch; nessun log per-record aggiunto.
- UX e localizzazione `CloudSyncIndicator` in 4 lingue.
- Test JVM TASK-067 e regressione TASK-066 via test navigation già dichiarati.

**Esito tecnico:**
- Dirty marking prodotto corretto: prodotti identici non sono dirty-marked; nuovi/modificati reali restano dirty; supplier/category equivalenti dopo resolution non producono falsi dirty.
- Price history: la separazione prodotto/prezzo è corretta nel perimetro attuale; gli eventi prezzo nuovi restano candidati push e non richiedono dirty revision prodotto quando il bridge prodotto è già affidabile.
- Fallback conservativo: confermato e rafforzato con test dedicato per prezzo nuovo su prodotto senza `product_remote_ref`; il ref prodotto viene creato e il ciclo pusha prima prodotto e poi prezzo.
- Sync/outbox: nessuna cancellazione outbox, nessun reset, nessun cambio `sync_events`, nessuna migration/RPC/schema live.
- UX: copy più chiaro su sync cloud e database locale utilizzabile; nessuna hardcoded string user-visible nuova; placeholder localizzati coerenti.
- TASK-066: nessun file navigation/ImportAnalysis modificato in TASK-067 review/fix.

**Problemi trovati:**
- Osservabilità: la metrica `dirtyMarkedPrices` aggiunta in execution era semanticamente ambigua perché contava prodotti con prezzi cambiati, non righe prezzo. Fix applicato: `dirtyMarkedPrices` ora conta righe prezzo inserite e `dirtyMarkedPriceProducts` conta i prodotti coinvolti.
- Copertura test: mancava un caso esplicito per il fallback conservativo price-only quando manca il bridge prodotto. Fix applicato con nuovo test JVM.

**Rischi residui:**
- Il valore live `syncEventOutboxPending=353` resta non classificato senza monitor remoto non distruttivo.
- La riduzione reale di `pushProductsMs` / `durationMs` sul dataset da 19.695 prodotti resta da misurare in ambiente reale sicuro.
- Se price rows senza bridge sono già presenti sul remoto ma non riconciliabili localmente, serve follow-up schema/RPC/checksum o strategia di backfill verificabile; non risolto in TASK-067 per guardrail.

**Esito review:** APPROVED con fix mirati; stato finale confermato `DONE ACCEPTABLE`, non `DONE FULL`.

---

## Fix

### Fix — 2026-04-27

**File modificati in review/fix:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — separata la metrica righe prezzo (`dirtyMarkedPrices`) dalla metrica prodotti coinvolti (`dirtyMarkedPriceProducts`).
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunto test `067 price-only import creates conservative product ref when bridge is missing`.
- `docs/TASKS/TASK-067-ottimizzazione-sync-cloud-post-full-import.md` — compilata review/fix/chiusura.
- `docs/MASTER-PLAN.md` — riallineamento stato finale post-review.

**Motivo:**
- Evitare ambiguità nei log di osservabilità richiesti da Gate 1.
- Coprire esplicitamente il ramo safety/fallback previsto dal task per stato remoto/bridge prodotto incerto.

**Verifica post-fix:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest'` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → `BUILD SUCCESSFUL`; warning AGP/toolchain preesistenti.
- Primo batch misto repository/sync/navigation senza attach listener esplicito: ❌ fallito per `AttachNotSupportedException` MockK/ByteBuddy, stesso problema ambientale già noto.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest'` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest` → `BUILD SUCCESSFUL`.
- `git diff --check` → OK.

---

## Chiusura

| Campo           | Valore |
|-----------------|--------|
| Stato finale    | `DONE ACCEPTABLE` |
| Data chiusura   | 2026-04-27 |
| Tutti i criteri | Parziali ma coerenti con `ACCEPTABLE`: codice/test/check OK; smoke manuale/live e metriche reali post-fix non eseguiti per safety |
| Review finale   | APPROVED con fix osservabilità/test; nessuna regressione TASK-066 rilevata |

---

## Riepilogo finale

TASK-067 ha identificato e corretto la root cause primaria del dirty set post full DB import: la price history completa veniva usata come sorgente dei prodotti “touched”, quindi prodotti invariati venivano dirty-marked e notificati alla sync cloud.

La patch rende il dirty marking delta-safe sul catalogo:
- prodotti nuovi → dirty;
- prodotti realmente modificati dopo normalizzazione → dirty;
- prodotti invariati presenti solo in `PriceHistory` → non dirty;
- eventi prezzo nuovi → schedulano la sync e restano candidati prezzo;
- se manca il bridge prodotto necessario al push prezzi, resta fallback conservativo.

La review post-execution ha confermato il comportamento e ha applicato due fix mirati: metrica prezzo non ambigua (`dirtyMarkedPrices` righe, `dirtyMarkedPriceProducts` prodotti) e test fallback conservativo per prezzo nuovo su prodotto senza bridge cloud.

Il risultato resta `DONE ACCEPTABLE`, non `DONE FULL`, perché non ho eseguito smoke live/remoto né misurato `pushProductsMs`/`durationMs` sul dataset reale da 19.695 prodotti. I test JVM dimostrano la riduzione del dirty set e preservano coerenza locale/prezzo senza cambiare schema.

---

## Handoff

**TASK-067 chiuso in `DONE ACCEPTABLE`.**

Follow-up consigliati, senza bloccare questa chiusura:
- Monitor live non distruttivo su dataset reale: full import identico/no-op, pochi prodotti cambiati, verifica `productsTouched`, `dirtyHints`, `productsPushed`, `pricesPushed`, `pushProductsMs`, `syncPricesMs`, `durationMs`, `syncEventOutboxPending`.
- Classificare `syncEventOutboxPending=353` con log su più cicli (`sync_events_summary`, retry, watermark, error category), senza cancellare outbox.
- Se `pricesPushed` resta alto per price rows senza bridge ma già presenti sul remoto, aprire task separato per checksum/RPC/schema o strategia di backfill verificabile.
- Se `productsPushed` scende ma `pushProductsMs` resta sproporzionato, aprire task dedicato per bulk product upsert/recovery 409; non è stato ampliato in TASK-067.

---

## Vincoli finali (documentazione)

- Nessun cambio schema Room/Supabase.
- Nessuna migration/RPC/DDL/RLS/trigger/publication.
- Nessun reset remoto, delete massivo, truncate o pulizia manuale outbox.
- Nessuna modifica navigation; TASK-066 preservato con test resolver mirati.
- Stato finale intenzionale: `DONE ACCEPTABLE` per assenza di smoke live/remoto sicuro.
