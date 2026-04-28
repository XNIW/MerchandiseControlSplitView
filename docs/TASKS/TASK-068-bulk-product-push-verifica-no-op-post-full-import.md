# TASK-068 — Bulk product push e verifica no-op reale post full database import

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-068 |
| Stato                | `PARTIAL` |
| Priorità             | `ALTA` |
| Area                 | Sync catalogo cloud / Supabase — push prodotti (batch), no-op post-import, price push, outbox, UX `CloudSyncIndicator` |
| Creato               | 2026-04-27 |
| Ultimo aggiornamento | 2026-04-28 — quinta passata **PARTIAL**: bulk product push client-side implementato con batch 100 + fallback 50/25/singolo, sync event massivi compatti, test JVM verdi; nessun cambio schema/RPC/outbox cleanup; serve ancora ciclo B live con lo stesso identico file |

---

## Dipendenze

- **TASK-067** `DONE ACCEPTABLE` — dirty marking delta-safe e osservabilità; **non** regressare logica/metriche introdotte lì durante eventuale execution futura.
- **TASK-066** `DONE` — navigazione `ImportAnalysis` / `DatabaseScreen`; **non** rompere flussi di ritorno durante eventuali fix UX copy/fasi sync.
- Baseline test / governance: **TASK-004** quando l’execution toccherà repository/ViewModel/sync.

---

## Scopo

Dopo **TASK-067**, i log live mostrano ancora una sync molto lunga dominata da **PUSH_PRODUCTS** (push prodotto-per-prodotto con `batchSize=1`) e da **SYNC_PRICES_PUSH**.

**Regola di gating:** *nessun* progetto di **bulk product push** e *nessuna* ottimizzazione di batch viene trattata come prioritaria finché non è superato il **no-op gate** (ciclo **B** — re-import identico dopo ciclo **A** documentato e precondizioni soddisfatte; vedi § *Ordine execution* e *Handoff*).

**Nota quinta passata (2026-04-28):** questa regola resta valida come criterio di **validazione/chiusura live**, ma l'utente ha autorizzato esplicitamente l'implementazione client-side del bulk product push in questa sessione, mantenendo TASK-068 `PARTIAL` finché il ciclo B live non sarà verde.

**Perimetro (sintesi):** bulk product dopo il gate; price da classificare senza fondere interventi che richiedono backend — **dettaglio in § *Perimetro: product bulk vs price optimization***.

Questo task ha eseguito il piano definito per: (1) dimostrare con evidenza se un **re-import identico** post-sync è davvero **no-op** a livello di metriche sync; (2) separare **dirty comparison / normalizzazione / allineamento locale** da **costo di rete/batch** del push; (3) progettare un **bulk product upsert sicuro** solo se il no-op è corretto ma il volume di modifiche *reali* resta alto; (4) classificare **outbox** / `syncEventOutboxPending` senza operazioni distruttive; (5) allineare copy/UX delle fasi in modo che l’utente non percepisca il pull come blocco durante push o drain brevi. **Nessun redesign UX ampio** fuori da copy/mapping fase/progress del `CloudSyncIndicator` e correlati.

---

## Contesto

**Evidenza live post TASK-067** (log — valori indicativi da riprodurre in execution):

| Metrica | Valore osservato (indicativo) |
|---------|-------------------------------|
| `productsTouched` | 6052 |
| `dirtyHints` | 6052 |
| `productsPushed` | 6052 |
| `PUSH_PRODUCTS` `batchSize` | 1 |
| `batchCount` | 6052 |
| `avgBatchMs` | ~99 |
| `pushProductsMs` | ~601170 (~601 s) |
| `pricesPushed` | 48244 |
| `syncPricesMs` | ~93883 (~94 s) |
| `durationMs` (ciclo) | ~724082 (~724 s) |
| `syncEventOutboxPending` | 353 → 571 |

**Interpretazione da validare:** il tempo è concentrato in **push prodotti** e **push prezzi**, non in **PULL_CATALOG** / drain `sync_events` post-sync (nell’ordine di ~214 ms per segnale realtime e drain non proporzionale ai minuti di push).

**Obiettivo di fondo:** capire il **collo di bottiglia residuo** dopo TASK-067 e pianificare interventi **senza** cambiare schema Supabase/Room e **senza** RPC/migration live in questo perimetro (need backend → task separato esplicito).

---

## Non incluso (vincoli operativi: EXECUTION)

- **Nota storica:** la transizione iniziale a `EXECUTION` non modificava codice Kotlin né eseguiva build/lint/test; la seconda passata documentata sotto ha invece applicato fix locali e verifiche.
- Durante l’**execution reale**, **non** espandere lo scope oltre il piano; **non** passare a `REVIEW` senza log in § **Execution** e check esecutore.
- **Protocollo live non distruttivo** (obbligo per qualsiasi raccolta evidenza in execution):
  - niente **reset remoto**;
  - niente **DELETE** / **TRUNCATE** su dati cloud o locali “per comodità”;
  - niente **pulizia manuale** di outbox / `sync_events` per far scendere i contatori;
  - log ed estratti con **segreti mascherati** (token, URL con chiavi, ecc.).
- **Non** cambiare schema **Supabase**, RLS, trigger, publication, **RPC**, né migration **live** — se emergono come necessari (es. dedup prezzi via checksum/RPC), aprire **follow-up dedicato** e **non** improvvisare dedup remota.
- **Non** espandere scope a refactor architetturali non motivati dalle evidenze del no-op e del profilo batch.

**Log e privacy (allegati in Execution / evidenze):**

- Mascherare **token** Supabase, **URL con apikey**, **email**, **user id** e **dati sensibili** in ogni log o estratto allegato al task.
- Sample di **barcode** / **product id**: **limitati**, niente dump massivi; evitare dati personali.
- **Non** incollare **JWT**, **API key** o segreti nel file task o in PR.

---

## File potenzialmente coinvolti (solo riferimento per execution futura)

- Sync cloud: classi con tag log `CatalogCloudSync`, `PUSH_PRODUCTS`, `SYNC_PRICES_PUSH`, `phase_metrics`, `import_dirty_marking`, `sync_events`, `sync_finish` (ricerca nel repo).
- Repository / DAO già toccati da TASK-067: `InventoryRepository` / implementazione default, `ProductPriceDao`, eventuali path dirty marking post-import.
- UI: `CloudSyncIndicator`, `CatalogSyncStateTracker`, stringhe `values*/strings.xml` (copy fasi).
- Test baseline: `DefaultInventoryRepositoryTest`, eventuali test sync/catalog se estesi.

---

## Criteri di accettazione (target a chiusura task — stati da aggiornare durante `EXECUTION`)

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Re-import identico post-sync** (verifica **prioritaria**) con **precondizioni** § *No-op gate*: stesso file dopo sync completa; risultati classificati con **matrice log obbligatoria** | M + log | ⚠️ PARZIALE — nuovo ciclo B live con build precedente alla terza patch fallisce ancora; serve rerun con questa patch |
| 2 | **No-op gate:** se il re-import identico **non** è no-op → **root cause** documentata **prima** di bulk/batch | Review + log | ✅ ESEGUITO — FK churn supplier/category + synthetic price generato da product dirty falso |
| 3 | Se no-op è **corretto** ma push su **delta reale** resta lento → **bulk product upsert sicuro** (§ *Bulk product push sicuro*) | S / M + Review | ⚠️ PARZIALE — bulk client implementato su istruzione esplicita utente (batch 100, fallback 50/25/singolo, single path preservato); manca smoke/live ciclo B e misura su delta reale |
| 4 | **Nessuna regressione TASK-067** | B / S / JVM / M | ✅ ESEGUITO |
| 5 | **Nessuna regressione TASK-066** | B / JVM / M | ✅ ESEGUITO |
| 6 | **Nessun** cambio schema Room/Supabase, RLS, trigger, publication, RPC, migration live | Review | ✅ ESEGUITO |
| 7 | **`syncEventOutboxPending`**: **classificata**; **mai** cancellazione manuale | M + Review + log | ✅ ESEGUITO |
| 8 | **UX/copy** § *CloudSyncIndicator*: mapping fedele alle fasi reali; **nessun redesign ampio** | M | ✅ ESEGUITO |
| 9 | **Price sync** § *Stop condition price push* + classificazione se 2° ciclo anomalo | Review + log | ⚠️ PARZIALE — synthetic price no-op corretto su JVM; price live post-terza patch da riclassificare |
| 10 | **Test JVM** § *Test JVM minimi* | JVM + Review | ✅ ESEGUITO — no-op/dirty/sync/navigation + bulk success/fallback/partial retry/offline retry/outbox compatto |
| 11 | Transizione **`PLANNING` → `EXECUTION`** registrata (2026-04-27); piano invariato; **nessun** lavoro implementativo in questo aggiornamento | Governance | ✅ |
| 12 | **Concorrenza:** nessuna doppia sync catalogo in parallelo durante misurazione; overlap/race → classificato **prima** di ottimizzare batch | M + log | ✅ ESEGUITO su log A + test stale-event; ciclo B live patchato pending |

Legenda: B=Build, S=Static, M=Manual/live log, E=Emulator.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Origine task in **PLANNING** | Analisi, criteri e piano prima del codice | 2026-04-27 |
| 2 | Backend schema/RPC esclusi dal perimetro | Allineato a TASK-067 e governance Supabase | 2026-04-27 |
| 3 | Piano raffinato integrato nel task ufficiale | Gate no-op, protocollo non distruttivo, albero decisionale, bulk sicuro, JVM minimi | 2026-04-27 |
| 4 | Piano **operativo/misurabile** (quiet window, cicli A/B, matrice log, stop price, UX/JVM/risk estesi) | Integrazione documentale successiva | 2026-04-27 |
| 6 | **`PLANNING` → `EXECUTION`** (2026-04-27) | Piano execution-ready approvato; lavoro reale **da svolgere**; **nessun** codice modificato in questo passaggio | 2026-04-27 |
| 7 | Bulk product push implementato in quinta passata su richiesta esplicita utente | La richiesta del 2026-04-28 autorizza bulk persistente/offline-safe senza nuova RPC/schema e senza cancellare outbox, pur mantenendo TASK-068 `PARTIAL` fino al ciclo B live | 2026-04-28 |

---

## Planning (Claude)

*Analisi + piano operativo; aggiornamento 2026-04-27 (solo documentazione).*

### Due cicli distinti (live)

| Ciclo | Scopo | Cosa aspettarsi |
|-------|--------|-----------------|
| **A — Primo** import / apply / sync | Completare allineamento locale/cloud | Push alto **può** essere legittimo: delta reali, bridge/ref da consolidare, primo allineamento. **Non** è il no-op gate finale. |
| **B — Secondo** re-import identico post-sync **completa** | **Vero no-op gate** | Target: `new=0`, `updated=0` e **nessun** dirty/push inatteso (`productsTouched`, `dirtyHints`, `productsPushed`, `pricesPushed` ≈ 0 salvo moti documentati). Se `productsPushed` o `pricesPushed` restano **alti** → **stop** e investigare **prima** di qualsiasi bulk. |

### Ordine della futura execution (baseline A, poi gate B)

1. **Verificare** che esista una **baseline recente e completa** del **ciclo A** (import/apply/sync del primo file, con **matrice log** compilata per lo scenario corrispondente).
2. Se la baseline ciclo **A** **non** è disponibile o **non** è affidabile → **prima** riprodurre **ciclo A** e solo allora proseguire.
3. Solo dopo **sync `COMPLETED`** + **quiet window 30–60s** (e nessuna fase attiva rilevante) → eseguire **ciclo B** = **secondo re-import identico** post-sync completa.
4. Il **ciclo B** resta il **vero no-op gate**.
5. **Nessun** bulk product push **prima** del **risultato** del ciclo B (classificato nella matrice).

### No-op gate (obbligatorio prima di bulk push)

Il **re-import identico** (ciclo **B**) va eseguito **solo dopo** tutte le condizioni seguenti:

1. **Tracker** sync cloud in stato **`COMPLETED`** (o equivalente documentato nel codice: sync principale terminata con successo, non “in corso”).
2. **Nessuna fase attiva** tra: `PUSH_PRODUCTS`, `SYNC_PRICES_PUSH`, `PULL_CATALOG`, `sync_events_drain` (verificare log + stato tracker coerenti).
3. **Quiet window:** attendere **30–60 secondi** dopo il completamento per non misurare una sync precedente ancora in coda o effetti tardivi di scheduling.
4. **Log pre e post** con **timestamp** chiari (inizio re-import, fine apply, inizio/fine sync successiva se parte in automatico).

Solo allora: **re-importare lo stesso file** senza modifiche e compilare la **matrice log** per il ciclo B.

Se `updated` o altre metriche restano alte **senza** cambiamenti reali → **stop**: normalizzazione/confronto/dirty marking; **non** proporre bulk push.

### Matrice log obbligatoria (execution futura)

Compilare una **riga per scenario** (foglio di calcolo o CSV consigliato). **Ordine colonne** (header CSV di riferimento):

`scenario`, `timestamp_start`, `timestamp_end`, `new`, `updated`, `productsTouched`, `insertedProducts`, `updatedProducts`, `unchangedProductUpdates`, `dirtyMarkedProducts`, `dirtyHints`, `productsPushed`, `PUSH_PRODUCTS_batchSize`, `PUSH_PRODUCTS_batchCount`, `PUSH_PRODUCTS_avgBatchMs`, `pushProductsMs`, `priceHistoryInserted`, `dirtyMarkedPrices`, `dirtyMarkedPriceProducts`, `pricesPushed`, `SYNC_PRICES_PUSH_batchSize`, `SYNC_PRICES_PUSH_batchCount`, `SYNC_PRICES_PUSH_avgBatchMs`, `syncPricesMs`, `durationMs`, `syncEventOutboxPending`, `manualFullSyncRequired`, `syncEventsGapDetected`, `classificazione_risultato`

**Scenari minimi:**

| # | scenario | Note |
|---|----------|------|
| 1 | Baseline live già osservata | Valori post-TASK-067 noti (tabella Contesto). |
| 2 | Primo import dopo TASK-067 | Ciclo **A**. |
| 3 | Secondo re-import identico post-sync completa | Ciclo **B** — **no-op gate**. |
| 4 | Eventuale delta reale **piccolo** e controllato | Solo se **sicuro** e **non distruttivo**; utile per profilare batch dopo superamento gate. |

`classificazione_risultato` (esempi): `no-op_ok`, `delta_spurio_import`, `delta_reale_legittimo`, `price_anomaly_2nd_cycle`, `outbox_accumulo`, `da_chiarire`, ecc.

### Albero decisionale (execution)

```
Re-import identico post-sync (ciclo B, precondizioni soddisfatte)
├── NON no-op (delta spurio)
│   └── Investigare normalizzazione / confronto / dirty marking / ordine apply.
│       NON ottimizzare batch.
├── No-op OK
│   ├── Delta reale ancora lento → Bulk product upsert SICURO (§ sotto).
│   └── Price push alto al SECONDO ciclo identico → Stop condition price (§ sotto); possibile task dedicato.
```

### Protocollo live non distruttivo

- Vietati: reset remoto, truncate/delete “di servizio”, pulizia manuale outbox o `sync_events`.
- Consentito: log, screenshot, metriche; **segreti mascherati** (vedi anche § *Non incluso* → **Log e privacy**).

### Concorrenza e scheduling sync

- **Non** devono partire **due sync catalogo** in **parallelo** durante import/apply/sync della sessione misurata.
- **Preservare** il guard esistente `sync_busy` / tracker **busy** (o equivalente nel codice).
- **Trigger** realtime, foreground, `local_commit` o bootstrap devono **coalescere** o **saltare** se una **sync principale** è già in corso.
- Durante la misurazione del **ciclo B**, **non** includere una **sync precedente** ancora in coda (quiet window + timestamp nei log).
- Se i log mostrano **overlap** o **race** tra `catalog_push`, `sync_events_drain`, `catalog_bootstrap` (o simili), **classificare** nel task **prima** di ottimizzare batch.

### Bulk product push sicuro (solo dopo no-op gate superato)

- **Consentito** solo se il **no-op gate** (ciclo **B**) è **superato** e il problema rimanente è **delta reale** con push lento.
- **Product bulk** e **price optimization** complessa (dedup/bridge/checksum/RPC): **non** unire nello stesso intervento se serve backend — follow-up dedicato; non appesantire TASK-068.
- Se la causa è **`batchSize=1`** con **delta reale confermato**, il candidato preferito è **upsert batch bounded lato client Supabase** (più chiamate con N prodotti), **non** nuova RPC/migration/schema.
- **Batch size iniziale consigliato:** **50 o 100** prodotti — **documentare la scelta** e **misurare** (tempo, errori, `avgBatchMs`); **no** batch enormi non caratterizzati.
- **Feature flag / disabilitazione:** se implementato in futura execution, il bulk deve essere **facilmente disattivabile o isolabile** — preferenza: **costante interna** o **flag locale documentato** per tornare rapidamente al percorso **singolo** in caso di regressione.
- Il **percorso singolo esistente** deve restare **disponibile** come fallback; **non** rimuoverlo finché il bulk non è validato con **test JVM** e **smoke live** controllati.
- **Idempotenza:** retry dello **stesso batch** non deve corrompere refs né duplicare eventi in modo incoerente.
- **Fallback obbligatorio (ordine):**
  1. batch nella dimensione scelta;
  2. se fallisce → **split progressivo** (es. dimezzare N fino a soglia minima);
  3. se continua a fallire → **fallback al percorso singolo-record esistente**.
- Il fallback deve **preservare** recovery **409** / **23505** e **`product_remote_refs`** (nessuna perdita ref nota).
- **`product_remote_refs` e bridge locali:** aggiornati **solo dopo conferma remota riuscita** (o equivalente già sicuro nel repo — verificare in execution senza allentare la regola).
- **Stop:** se serve **RPC**, **schema remoto**, **checksum remoto** o **migration Supabase** → **non** implementare nel perimetro; **follow-up dedicato**.

### Perimetro: product bulk vs price optimization

- TASK-068 può **progettare/implementare** **bulk product push** **solo** dopo **no-op gate** (ciclo **B**).
- **Price push** resta da **classificare**; **non** unire ottimizzazione **product** push e dedup/bridge/checksum **price** nello stesso intervento se la price anomaly richiede **schema/RPC/backend**.
- Se **price push** resta alto al **ciclo B**, aprire **follow-up dedicato** (price bridge/checksum/RPC) — **non** appesantire TASK-068.

### Diagnosi “preparazione pull incrementale” / PULL vs push

**Nota esplicita (dai log osservati):** `PULL_CATALOG` / `sync_events_drain` di **pochi ms o pochi secondi** **non** sembrano il **collo di bottiglia principale** rispetto a **decine di minuti** di push; il tempo lungo percepito è **probabilmente** **push prodotti/prezzi**, mentre la UI può mostrare una **fase poco chiara** (es. “preparazione pull” / ricezione mentre il motore è in **push**).

In **execution** verificare:

- se il tracker espone `PULL_CATALOG` durante **drain** o **preparazione eventi**;
- se la UI usa label “pull/receiving” anche quando il motore è in **push**;
- se `PULL_CATALOG` è uno **stage generico** e confonde l’utente;
- se conviene distinguere stage **reali** (naming/copy, anche senza redesign):
  - `PUSH_SUPPLIERS`
  - `PUSH_CATEGORIES`
  - `PUSH_PRODUCTS`
  - `SYNC_PRICES_PUSH`
  - `SYNC_EVENTS_DRAIN`
  - `COMPLETED`

**Intervento ammesso:** solo **mapping fase/copy/progress** più fedele; **nessun redesign ampio**.

### CloudSyncIndicator — criteri UX concreti

- Durante **push prodotti:** **«Invio prodotti al cloud»** — **non** «Ricezione catalogo» / equivalente pull-centrico.
- Durante **push prezzi:** **«Invio prezzi al cloud»**.
- Durante **drain eventi:** **«Aggiornamento eventi sync»** o equivalente **breve**.
- Quando **apply locale** è completato ma cloud ancora in corso: messaggio tipo **«Database locale pronto»** / **«Puoi già usare il database»** (stringhe da localizzare come da prassi app).
- **Progress numerico** solo se **affidabile** (stesso valore che il motore espone).
- Se il motore lavora a **batch:** mostrare avanzamento per **batch** o **conteggi reali** — evitare progress che **sembra** “1 prodotto al secondo” se non è così.
- **Stato errore/retry:** comprensibile, **non** tecnico (niente dump codici verso l’utente finale salvo eccezioni documentate).
- **Nessun redesign ampio** della UI.

### Stop condition — price push (secondo re-import identico)

Se al **secondo** re-import identico post-sync (ciclo **B**, precondizioni ok) **`pricesPushed` resta alto** nonostante **`priceHistoryInserted=0`**, **non** risolvere con **dedup remota improvvisata**. **Stop condition:** fermarsi, classificare, **non** caricare TASK-068 con ottimizzazioni price che richiedono schema/RPC/backend.

**Prima classificare** (documentare nella matrice + nota):

- backfill remoto di ref/bridge ancora **incompleto**;
- price history locale **senza** remote ref;
- **confronto prezzo** insufficiente (logica client);
- evento prezzo **realmente nuovo** (legittimo);
- problema **checksum/chiave logica** (serve design);

Se la soluzione richiede **bridge/checksum/RPC** o **schema remoto** → **task separato**.

### Analisi — baseline live (riferimento)

| Fase | Durata indicativa |
|------|-------------------|
| Full import | ~25 s |
| Apply import | ~5.7 s |
| Push prodotti (`pushProductsMs`) | ~601 s |
| Push prezzi (`syncPricesMs`) | ~94 s |
| Ciclo totale (`durationMs`) | ~724 s |

### Outbox — `syncEventOutboxPending`

Classificare: self-skip, temporaneo, accumulo reale — **senza cancellare**.

### Metriche di successo attese (orientative, non tempo assoluto)

**Non** fissare **tempo assoluto** obbligatorio: dipende da rete/Supabase. Richiedere **confronto prima/dopo** sullo **stesso dataset** e ambiente.

- **Se ciclo B è no-op corretto:** `productsTouched`, `dirtyHints`, `productsPushed`, `pricesPushed` devono essere **zero o quasi zero**, salvo **motivazione documentata** in `classificazione_risultato`.
- **Se si implementa bulk su delta reale** (dopo gate superato):
  - `PUSH_PRODUCTS_batchSize` passa da **1** a valore **bounded documentato** (es. 50/100);
  - `PUSH_PRODUCTS_batchCount` **scende** in modo **proporzionale** al nuovo N;
  - `productsPushed` resta **uguale al delta reale**, **non** aumenta per artefatto;
  - **nessun** aumento di errori **409/23505** **non recuperati** rispetto al baseline;
  - **nessuna perdita** di `product_remote_refs`;
  - `syncEventOutboxPending` **non** cresce in modo **incontrollato** (classificare se c’è crescita).

### Test JVM minimi (dopo approvazione EXECUTION)

| Test / obiettivo | Dettaglio |
|------------------|-----------|
| No-op identico post-apply | Zero **product** dirty e zero **price** dirty attesi quando non c’è delta. |
| Delta prodotto reale | **Solo** quel prodotto entra in dirty (e derivati attesi). |
| Price-only | **Non** sporca prodotto se cambia solo prezzo, **salvo** regola/fallback già documentata (TASK-067). |
| Bulk success | **N** prodotti in batch aggiornano correttamente **remote refs** dopo success. |
| Bulk fallback | Batch fallisce → split/singolo **recupera** senza perdere refs. |
| Conflitto 409/23505 | Comportamento **uguale o migliore** del percorso attuale. |
| Outbox | **Nessun** path cancella manualmente outbox/`sync_events`. |
| Regressione TASK-067 | `dirtyMarkedPrices`, `dirtyMarkedPriceProducts` restano **semanticamente corretti**. |
| Regressione TASK-066 | **Nessun** touch navigazione/`ImportAnalysis` salvo necessità **documentata**. |
| Feature flag / bulk off con flag disabilitato | Percorso **singolo** usato; stesso risultato atteso rispetto al baseline noto. |
| Partial batch failure | Primo batch OK, secondo fallisce → refs aggiornate **solo** per successi **confermati** da remoto. |
| Retry idempotente | Ripetere lo **stesso batch** non duplica refs né eventi incoerenti. |
| Sync busy / no overlap | Se il codice espone guard testabile (`sync_busy`/tracker), verificare **nessun** avvio doppio concorrente nel caso coperto. |
| Mapping UX stage | Solo se la logica stage/copy è **separabile** da Compose (es. pura funzione / mapper testabile). |

### Piano di esecuzione (bozza — solo dopo approvazione `EXECUTION`)

1. **Verificare** disponibilità di una **baseline ciclo A** recente e completa (**matrice log** + log timestampati, privacy rispettata). Se assente o inaffidabile → **prima** riprodurre **ciclo A** (scenario 2 o equivalente).
2. Con baseline **A** solida: attendere **COMPLETED** + **quiet window 30–60s** + assenza overlap (§ *Concorrenza*); poi **ciclo B** — secondo re-import identico — compilare **matrice** scenario 3.
3. **Ciclo B** = no-op gate: **nessun** bulk prima del risultato classificato.
4. Applicare **albero decisionale** (no bulk se gate fallisce; price anomaly → follow-up, non dedup improvvisata).
5. Outbox: classificazione **non distruttiva**.
6. UX: audit tracker/`CloudSyncIndicator` vs log; patch **minima** copy/mapping/progress.
7. JVM: suite § *Test JVM minimi* + baseline **TASK-004**.
8. Opzionale scenario 4 (delta piccolo controllato) solo se **sicuro** e non distruttivo.

### Rischi identificati

| Rischio | Mitigazione |
|---------|-------------|
| Ottimizzare batch **prima** di verificare no-op | **No-op gate** obbligatorio + cicli A/B + matrice log. |
| Bulk upsert rompe remote refs o recovery conflitti | Fallback split/singolo + test JVM bulk/ref/409/23505. |
| UI mostra **pull** mentre è **push** | Verifica mapping su **log + schermata**; copy per stage reali. |
| Price push alto trattato come “normale” | **Secondo** re-import identico + **stop condition** + classifica. |
| Outbox pending **cresce** ma viene ignorata | Classificazione **non distruttiva**; follow-up se non converge. |
| Bulk upsert parziale senza fallback | Batch bounded + split + singolo (ordine fisso). |
| Falso no-op (tutto dirty) | Gate ciclo **B** + confronto con ciclo **A**. |
| Overlap / race tra `catalog_push`, `sync_events_drain`, `catalog_bootstrap` | Classificare nei log **prima** di batch; quiet window; § *Concorrenza*. |
| Feature flag bulk lascia regressioni nascoste | Smoke + JVM con flag on/off; percorso singolo sempre disponibile fino a validazione. |
| UX: regressione percepita TASK-059 | Copy review, Material3 coerente, **no** redesign ampio. |

---

## Execution

### Esecuzione — 2026-04-28, quinta passata bulk product push offline-safe

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `pushCatalogProducts` usa ora i dirty product refs esistenti/candidati Room e invia upsert prodotti in batch bounded (`100`) con fallback progressivo `50` / `25` / percorso singolo; `product_remote_refs` viene marcato synced solo dopo successo remoto; log `PUSH_PRODUCTS` esteso con `bulkEnabled`, `batchSize`, `batchCount`, `productsPushed`, `pushProductsMs`, fallback count.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunti test JVM per bulk success, sync event compatto, outbox compatto offline, fallback singolo, partial failure con retry idempotente e offline retry completo; fake remote esteso con attempt sizes e failure hook.
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` — log quinta passata e stato finale ancora `PARTIAL`.
- `docs/MASTER-PLAN.md` — tracking globale riallineato alla quinta passata.

**Azioni eseguite:**
1. Implementato bulk product push persistente/offline-safe senza nuova RPC e senza cambio schema Supabase/Room: il datasource remoto usava gia' `upsertProducts(List<InventoryProductRow>)`, quindi il cambio resta client-side nel repository.
2. Preservato il percorso singolo come fallback e come path disattivabile tramite costante interna `PRODUCT_BULK_PUSH_ENABLED`.
3. Implementato fallback progressivo: batch iniziale `100`, split a `50`, poi `25`, poi singolo; il singolo riusa la recovery esistente per conflitti `409` / `23505`.
4. Aggiornamento refs locale solo dopo conferma remota: su batch riuscito viene aggiornato `lastSyncedLocalRevision` / `lastRemoteAppliedAt` in transazione; su failure/offline i refs restano dirty per retry.
5. Reso compatto il record `sync_events` per commit massivi: se gli id superano `SYNC_EVENT_ENTITY_ID_BUDGET`, l'evento conserva `changedCount` ma invia `entityIds` vuoto, evitando payload enormi; in outbox viene salvato lo stesso payload compatto senza cancellare record esistenti.
6. Nessun cambio a `SupabaseCatalogRemoteDataSource`, schema Supabase, RPC, RLS, trigger, migration live; nessuna cancellazione outbox/sync_events; nessuna nuova import analysis.
7. UI non toccata: il comportamento resta database locale pronto e cloud sync in background secondo mapping/stage gia' introdotti nelle passate precedenti.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` verde |
| Lint | ✅ ESEGUITO | `lintDebug` verde; warning AGP/Kotlin/toolchain preesistenti |
| Warning nuovi | ✅ ESEGUITO | `compileDebugKotlin`, `compileDebugUnitTestKotlin`, suite JVM e `git diff --check` verdi; nessun warning Kotlin nuovo rilevato |
| Coerenza con planning | ⚠️ PARZIALE | Bulk implementato su istruzione esplicita utente successiva al gate storico; invariati divieti su schema/RPC/outbox cleanup |
| Criteri di accettazione | ⚠️ PARZIALE | Bulk JVM implementato e verificato; manca ciclo B live stesso file + misura prodotti/prezzi/outbox |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `DefaultInventoryRepositoryTest.068*`, `DefaultInventoryRepositoryTest.045*`, `DefaultInventoryRepositoryTest.041*`, `DefaultInventoryRepositoryTest.042*`, `DefaultInventoryRepositoryTest.043*`, suite larga `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `DatabaseViewModelTest`, `GeneratedExitDestinationResolverTest`, full `testDebugUnitTest`.
- Test aggiunti/aggiornati: bulk product push batch 100/100/60 su 260 prodotti; compact catalog event con `changedCount=260` e ids vuoti; compact outbox offline; fallback split/singolo; partial failure con primo batch synced e tail dirty; offline totale con retry successivo.
- Limiti residui: nessuno smoke live eseguito in questa sessione; il ciclo B live con lo stesso identico file resta obbligatorio prima di dichiarare `DONE FULL`.

**Test / comandi eseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileDebugKotlin compileDebugUnitTestKotlin` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME=... ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.*068*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.*045*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.*041*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.*042*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.*043*'` → ✅ `BUILD SUCCESSFUL`.
- Primo run largo senza `JAVA_TOOL_OPTIONS` → ⚠️ fallito per ambiente ByteBuddy/MockK (`AttachNotSupportedException`), non per assert applicativi.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest' --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest' --tests 'com.example.merchandisecontrolsplitview.navigation.GeneratedExitDestinationResolverTest'` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → ✅ `BUILD SUCCESSFUL`.
- Primo full `testDebugUnitTest` con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading'` → ⚠️ fallito per attach ByteBuddy/MockK (`AttachNotSupportedException`), non per assert applicativi.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener -XX:+EnableDynamicAgentLoading' ./gradlew --no-daemon testDebugUnitTest` → ✅ `BUILD SUCCESSFUL`.
- `git diff --check` → ✅ nessun errore.

**Incertezze:**
- INCERTEZZA: manca misura live del nuovo bulk su Supabase reale; i test coprono il comportamento JVM/fake remote, non latenza/retry reali di rete.
- INCERTEZZA: TASK-068 resta `PARTIAL` finche' non esiste un ciclo B live con stesso identico file, sync completa e quiet window 30-60s.
- INCERTEZZA: `syncEventOutboxPending` non e' stato pulito; va osservato nel prossimo ciclo live per verificare che non cresca su import identico.

**Handoff notes:**
- Prossimo test manuale: stesso identico file due volte, sync completata e quiet window 30-60s tra ciclo A e ciclo B.
- Atteso ciclo B: `updatedProducts=0` o quasi spiegato, `dirtyMarkedProducts=0` o spiegato, `dirtyMarkedPrices=0`, `productsPushed=0`, `pricesPushed=0`, `syncEventOutboxPending` non cresce per effetto dell'import identico.
- Per delta reale massivo, verificare nei log `PUSH_PRODUCTS`: `batchSize=100`, `batchCount` proporzionale, `productsPushed` uguale al delta reale, `pushProductsMs` ridotto rispetto al precedente `batchSize=1`.
- Se il bulk fallisce live, il percorso singolo e' ancora disponibile nel codice come fallback e la costante interna consente isolamento rapido.

### Esecuzione — 2026-04-28, quarta passata price-aware/fingerprint

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportApplyModels.kt` — aggiunto payload diagnostico opzionale per conteggi/fingerprint dataset da riportare nell'apply.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — `recordImportedCurrentAndPreviousPrices` ora viene chiamato sugli update solo se c'e' un delta prezzo reale; gli update relation/stock/name/item-only sporcano il prodotto ma non i prezzi; log `import_dirty_marking` esteso con fingerprint, classificazione dataset e sintesi relation/price.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportDatasetFingerprint.kt` — nuovo helper leggero per fingerprint normalizzato di Products/Suppliers/Categories/PriceHistory e snapshot DB.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/FullDbImportStreaming.kt` — calcolo fingerprint workbook durante parsing streaming full DB senza loggare barcode/sample completi.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — log full import arricchito con `fileProductCount`, `filePriceHistoryCount`, `dbProductCountBefore`, `dbPriceHistoryCountBefore`, fingerprint short e `classificazione_risultato`; diagnostica passata all'apply.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunti test supplier-only, category-only, stock-only, productName-only, purchasePrice-only e retailPrice-only.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — il re-import full DB con PriceHistory completa simula refs prodotto/prezzo gia' synced e verifica zero candidate product/price al ciclo B JVM.
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` — log quarta passata e stato finale `PARTIAL`.
- `docs/MASTER-PLAN.md` — tracking globale riallineato alla quarta passata.

**Nuovo log live acquisito:**
- `FULL_IMPORT SUCCESS products=18868 suppliers=80 categories=43 priceHistory=true`
- `APPLY_IMPORT START previewId=1 new=0 updated=6052`
- `import_dirty_marking ... productDirtyReasons=supplierId:5854,categoryId:174,stockQuantity:173,purchasePrice:129,productName:91 ... priceHistoryRows=37936 priceHistoryInserted=0 priceHistoryAlreadyPresent=37936 syntheticPriceCandidates=24208 syntheticPriceSkippedAlreadyRepresented=12141 dirtyMarkedPrices=12067 priceDirtyReason=syntheticProductPrice:12067,pendingPriceInsert:0`

**Root cause aggiornata:**
1. Il log live non e' un vero no-op gate perche' `test_import Database1.xlsx` (~18.868 prodotti / 37.936 price rows) e `Database_test_import 2.xlsx` (~19.697 prodotti / 41.108 price rows) sono dataset diversi.
2. Resta pero' un bug reale: un update prodotto causato da relazione/stock/nome poteva ancora generare synthetic price tramite product dirty, soprattutto quando i vecchi prezzi del Products sheet differivano dai campi prodotto locali pur essendo gia' rappresentati dalla PriceHistory.
3. La PriceHistory importata ora prevale: con `priceHistoryRows > 0` le righe sintetiche non sono usate come fallback generico per ogni product dirty; per gli update prodotto vengono generate solo sui tipi prezzo semanticamente cambiati.

**Azioni eseguite:**
1. Separata la dirty product logic dalla dirty price logic: `dirtyMarkedProducts` resta corretto per delta reali supplier/category/stock/name, ma `dirtyMarkedPrices` non cresce piu' per update non-price.
2. Limitata la synthetic fallback agli update con delta `purchasePrice` / `retailPrice` o, solo in assenza di PriceHistory sheet, `oldPurchasePrice` / `oldRetailPrice`.
3. Aggiunto fingerprint dataset short normalizzato e non sensibile su workbook e snapshot DB, con classificazione `no_op_candidate_same_dataset` vs `delta_reale_dataset_diverso`.
4. Estesa la diagnostica live con `relationDirtyRealChangedCount`, `relationDirtySemanticEquivalentCount`, `relationDirtyUnknownCount`, `priceDirtyFromPriceFieldChange`, `priceDirtyFromSyntheticFallback`, `priceDirtySkippedBecauseNonPriceProductUpdate`.
5. Nessun bulk product push implementato; nessuna modifica a schema Room/Supabase, RPC, RLS, trigger, migration; nessuna cancellazione outbox/sync_events.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` verde |
| Lint | ✅ ESEGUITO | `lintDebug` verde; warning AGP/Kotlin/toolchain preesistenti |
| Warning nuovi | ✅ ESEGUITO | `compileDebugKotlin`, `compileDebugUnitTestKotlin` e `git diff --check` verdi; nessun warning Kotlin nuovo rilevato |
| Coerenza con planning | ✅ ESEGUITO | Fix limitato a apply import/diagnostica/test; bulk e schema esclusi |
| Criteri di accettazione | ⚠️ PARZIALE | JVM/build/lint verdi; manca ciclo B live vero con stesso identico file due volte |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `DefaultInventoryRepositoryTest.068*`, `DefaultInventoryRepositoryTest.067*`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `DatabaseViewModelTest`, `GeneratedExitDestinationResolverTest`, full `testDebugUnitTest`.
- Test aggiunti/aggiornati: supplier-only/category-only/stock-only/productName-only non sporcano prezzi; purchasePrice/retailPrice sporcano solo il tipo prezzo corretto; round-trip full DB ciclo B JVM verifica zero candidate product/price dopo sync simulata; fingerprint dataset propagato da full import ad apply.
- Limiti residui: nessun ciclo B live eseguito da questa sessione; i file reali non sono fixture repo; `syncEventOutboxPending` resta da osservare senza cleanup manuale nel prossimo test live.

**Test / comandi eseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileDebugKotlin compileDebugUnitTestKotlin` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.068*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.067*' --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest'` → ✅ `BUILD SUCCESSFUL` dopo fix del test round-trip per simulare refs synced prima del ciclo B.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest'` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest` → ✅ `BUILD SUCCESSFUL`.
- `git diff --check` → ✅ nessun errore.

**Incertezze:**
- INCERTEZZA: manca ancora evidenza live post-quarta-patch con lo stesso identico file due volte, sync completa e quiet window 30-60s.
- INCERTEZZA: se il prossimo log resta con `updatedProducts>0`, la nuova `classificazione_risultato` e i fingerprint devono prima chiarire se e' dataset diverso o delta spurio.
- INCERTEZZA: `syncEventOutboxPending` non e' stato modificato o pulito; va osservato nel ciclo B live.

**Handoff notes:**
- Stato resta **`PARTIAL`**. Non dichiarare `DONE FULL` senza ciclo B live vero.
- Prossimo test manuale: usare lo stesso identico file due volte; non alternare `test_import Database1.xlsx` e `Database_test_import 2.xlsx`.
- Atteso sul ciclo B verde: `updatedProducts=0` o quasi spiegato, `dirtyMarkedProducts=0` o spiegato, `dirtyMarkedPrices=0`, `productsPushed=0`, `pricesPushed=0`, `syncEventOutboxPending` non cresce per effetto dell'import identico.
- Bulk product push resta vietato finche' il no-op gate live non e' verde.

### Esecuzione — 2026-04-28, terza passata no-op FK/prezzi

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — preview import preserva `supplierId` / `categoryId` esistenti quando il nome importato è semanticamente uguale (trim/case/accent folded), anche se un duplicato locale avrebbe un ID diverso; analysis e apply condividono ora la stessa semantica.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — apply import preserva FK esistenti per supplier/category semanticamente equivalenti; synthetic price rows vengono saltate quando la `PriceHistory` importata rappresenta già lo stesso product/type/price; log `import_dirty_marking` esteso con `relationDirtySample`, `priceRowsPendingBridgeBefore`, `syntheticPriceCandidates`, `syntheticPriceSkippedAlreadyRepresented`, `syntheticPriceBridgeCreated`, `syntheticPriceBridgeAlreadyExists`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ImportAnalyzerTest.kt` — test preview: supplier/category equivalenti con ID duplicato non generano FK churn quando cambia un altro campo.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test apply: FK equivalenti preservate, cambio relazione reale sporca solo il prodotto target, product-only non genera dirty price se la PriceHistory rappresenta già i prezzi.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — round-trip full DB aggiornato: con sheet `PriceHistory` completa non si generano righe sintetiche `IMPORT`; senza `PriceHistory` resta il comportamento sintetico.
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` — log terza passata e stato finale.
- `docs/MASTER-PLAN.md` — stato globale riallineato a `TASK-068 PARTIAL` terza passata, nessun task attivo, bulk non implementato.

**Nuovo log live acquisito:**
- `APPLY_IMPORT START previewId=1 new=0 updated=6052`
- `import_dirty_marking productsTouched=6052 updatedProducts=6052 dirtyMarkedProducts=6052 productDirtyReasons=supplierId:5854,categoryId:174,stockQuantity:173,purchasePrice:129,productName:91 priceHistoryInserted=0 priceHistoryAlreadyPresent=41108 dirtyMarkedPrices=24046 priceDirtyReason=syntheticProductPrice:24046,pendingPriceInsert:0 priceRowsPendingBridgeAfter=24046`
- `PUSH_PRODUCTS batchSize=1 batchCount=6052 pushProductsMs=615175`
- `SYNC_PRICES_PUSH pricesPushed=24046 batchSize=80 batchCount=301`
- `cycle=catalog_push outcome=ok reason=local_commit durationMs=679572 dirtyHints=6052 productsPushed=6052 pricesPushed=24046 syncEventOutboxPending=815`

**Root cause aggiornata:**
1. Root cause primaria: **FK churn supplier/category**. Il preview/apply poteva risolvere il nome importato verso un supplier/category semanticamente uguale ma con ID diverso; poi `productsEquivalentForImportDirty` confrontava gli ID e marcava dirty. Il log `supplierId:5854` conferma che questo era il contributore principale.
2. Root cause price: `dirtyMarkedPrices=24046` nasceva da `recordImportedCurrentAndPreviousPrices` sui 6.052 prodotti falsamente aggiornati. Anche con `priceHistoryInserted=0`, la product dirty falsa generava righe sintetiche `IMPORT`/`IMPORT_PREV` senza bridge, poi candidate al push prezzi.
3. Root cause di test file: i due file reali citati non sono identici (`test_import Database1.xlsx` ~18.868 prodotti / 37.936 price rows; `Database_test_import 2.xlsx` ~19.695 prodotti / 41.108 price rows). Il no-op gate live deve usare lo **stesso identico file** due volte dopo sync completa.

**Decisione Gate 3 / bulk:**
- **Bulk product push non implementato.**
- Motivo: il nuovo log live conferma che il gate no-op era ancora rosso prima di questa terza patch. La patch corregge le cause locali testabili, ma manca ancora evidenza live con la nuova build.

**Price classification:**
- `priceHistoryInserted=0` + `syntheticProductPrice=24046` era effetto secondario dei product dirty falsi.
- La nuova logica salta la synthetic row se la `PriceHistory` importata contiene già lo stesso prezzo per product/type; i candidati price push tornano basati su righe `ProductPrice` reali senza remote ref.
- Se un futuro ciclo B con questa patch mostra ancora `dirtyMarkedPrices>0`, leggere `syntheticPriceCandidates`, `syntheticPriceSkippedAlreadyRepresented`, `priceRowsPendingBridgeBefore/After` e `priceDirtyReason`.

**Outbox classification:**
- `syncEventOutboxPending` cresce fino a `815`; non è stato cancellato nulla.
- Classificazione invariata: accumulo reale/non convergente da indagare a parte, probabilmente collegato a eventi inutili generati dai dirty falsi più `PayloadValidation` già osservato. Verificare dopo no-op live: se `productsPushed=0` e `pricesPushed=0`, l’outbox non deve crescere per effetto dell’import identico.

**Test / comandi eseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew compileDebugKotlin compileDebugUnitTestKotlin` → ✅ `BUILD SUCCESSFUL`; warning Compose/toolchain preesistenti.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.068*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.067 supplier and category equivalent after temp resolution do not dirty product' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.067 medium full import price sheet does not scale dirty set with total rows' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.067 full import with identical bridged price history does not dirty the catalog'` → ✅ `BUILD SUCCESSFUL`.
- Primo rerun round-trip mirato → ❌ test storico aspettava righe sintetiche `IMPORT` anche con `PriceHistory` completa.
- Fix test/contratto: con `PriceHistory` rappresentativa il full import non deve creare synthetic rows; il test `RT-NOPH` continua a coprire il caso senza PriceHistory.
- Rerun round-trip/sync/navigation mirato → ✅ `BUILD SUCCESSFUL`.
- Rerun largo `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `DatabaseViewModelTest`, `GeneratedExitDestinationResolverTest` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → ✅ `BUILD SUCCESSFUL`; solo warning toolchain/AGP preesistenti.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest` → ✅ `BUILD SUCCESSFUL`.
- `git diff --check` → ✅ nessun errore.

**Check obbligatori — terza passata:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` verde |
| Lint | ✅ ESEGUITO | `lintDebug` verde; warning toolchain/AGP preesistenti |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo dai file modificati; `git diff --check` verde |
| Coerenza con planning | ✅ ESEGUITO | Nessun bulk; fix locale no-op/dirty nel perimetro |
| Criteri di accettazione | ⚠️ PARZIALE | JVM/build/lint verdi; manca ciclo B live con questa patch |

**Baseline regressione TASK-004 — terza passata:**
- Test eseguiti: `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `DatabaseViewModelTest`, `GeneratedExitDestinationResolverTest`, full `testDebugUnitTest`.
- Test aggiunti/aggiornati: relation-ID churn supplier/category, relation change reale, synthetic price skip con PriceHistory rappresentativa, round-trip full DB no synthetic quando la PriceHistory è completa.
- Limiti residui: i file reali in `Downloads` non sono stati aggiunti al repo come fixture binarie; il ciclo B live con lo stesso identico file deve essere rieseguito dall’app aggiornata.

**Incertezze:**
- INCERTEZZA: manca ancora evidenza live post-terza-patch. La chiusura resta `PARTIAL` finché non si osserva `updatedProducts≈0`, `dirtyMarkedProducts=0`, `dirtyMarkedPrices=0`, `productsPushed=0`, `pricesPushed=0` sullo stesso file importato due volte.
- INCERTEZZA: se dopo questa patch resta `supplierId` alto, il nuovo `relationDirtySample` deve mostrare i primi 5 vecchio/nuovo nome/id mascherando barcode per capire se sono cambi reali o duplicati non normalizzati.

**Handoff notes:**
- Installare questa build e ripetere il no-op gate con **`Database_test_import 2.xlsx` due volte**, non con `test_import Database1.xlsx`.
- Se il nuovo ciclo B è verde, TASK-068 può chiudere `DONE ACCEPTABLE` senza bulk e il bulk product push va pianificato come step successivo.
- Se resta dirty falso, usare `relationDirtySample`, `syntheticPriceCandidates`, `syntheticPriceSkippedAlreadyRepresented`, `priceRowsPendingBridgeBefore/After`.
- Bulk product push resta vietato fino a no-op live verde.

### Esecuzione — 2026-04-27, seconda passata su no-op gate

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ImportAnalysis.kt` — confronto import reso semanticamente più stabile: barcode match trim/case-insensitive; testo prodotto/item/second name/supplier/category confrontato con trim + ignore-case; blank opzionali restano unchanged.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — log `import_dirty_marking` esteso con `productFieldChangedCount`, motivi dirty sintetici, price rows già presenti, pending bridge e ragione dirty prezzi; `sync_events_drain` protegge gli id appena pushati nello stesso `local_commit` da eventi remoti stale.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/ImportAnalyzerTest.kt` — test semantici su trim/case/blank.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test TASK-068 su no-op semantico, price history già bridged, stale sync event post local commit.
- `app/src/test/java/com/example/merchandisecontrolsplitview/util/FullDbExportImportRoundTripTest.kt` — test round-trip full DB: secondo re-import dello stesso workbook dopo apply è no-op prodotto/prezzo.
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` — log seconda passata, criteri finali e chiusura `PARTIAL`.

**Diagnosi live/non distruttiva:**
1. Evidenza utente acquisita come ciclo B valido arrivato ad apply + sync:
   - `APPLY_IMPORT START previewId=1 new=0 updated=6052`
   - `import_dirty_marking productsTouched=6052 ... updatedProducts=6052 unchangedProductUpdates=0 dirtyMarkedProducts=6052 priceHistoryRows=37936 priceHistoryInserted=0 dirtyMarkedPrices=24188 dirtyMarkedPriceProducts=6052`
   - `PUSH_PRODUCTS batchSize=1 batchCount=6052 pushProductsMs=614541`
   - `SYNC_PRICES_PUSH pricesPushed=24188`
   - `syncEventOutboxPending=693`
2. Lettura codice: `dirtyMarkedPrices=24188` con `priceHistoryInserted=0` non nasce dalla PriceHistory già esistente, ma dai product update: `recordImportedCurrentAndPreviousPrices` genera righe sintetiche quando un prodotto viene considerato realmente aggiornato.
3. Pull locale DB emulator in sola lettura (`run-as` + copia DB/WAL/SHM, nessuna scrittura): lo stato locale post-sync risulta pulito lato bridge (`product_remote_refs` pending = 0, price rows pending bridge = 0), ma il file candidato e il DB locale non sono semanticamente identici. Su 19.695 righe prodotto matchate, 6.052 risultano delta reali rispetto allo snapshot locale, dominati da relazione fornitore/categoria e prezzi/stock. Quindi quel file, nello stato locale attuale, non può dimostrare no-op senza prima riallineare il ciclo completo con la nuova patch.
4. Outbox locale in sola lettura: `sync_event_outbox` = 693 pending (`prices_changed` 564, `catalog_changed` 109 a tentativo 0, `catalog_changed` 20 a tentativo 5), tutti con `lastErrorType=PayloadValidation`. Nessuna pulizia manuale eseguita.

**Root cause classificata:**
- Root cause locale confermata e corretta: confronti import troppo sensibili a differenze semanticamente nulle potevano produrre update spurii e quindi dirty prodotto/prezzo.
- Root cause di sync/race corretta: durante il drain dello stesso `local_commit`, eventi remoti non-self con gli stessi id appena pushati potevano riapplicare payload stale perché il ref risultava ormai clean dopo il push; ora quegli id sono protetti solo per quel drain.
- Root cause live residua: lo snapshot locale post-sync e il file candidato non coincidono più; serve ripetere il ciclo B dall'app aggiornata e dal flusso Database corretto per separare delta reale da no-op.

**Decisione Gate 3 / bulk:**
- **Bulk product push non implementato.**
- Motivo: il no-op gate live non è ancora passato con questa patch. Il collo di bottiglia `batchSize=1` resta vero per delta reale, ma implementare bulk ora maschererebbe ancora il problema dirty/no-op.

**Price push:**
- Fix locale: se la PriceHistory è già presente e bridged, il re-import JVM non crea dirty prices né candidati push.
- Classificazione live residua: nel log utente `dirtyMarkedPrices=24188` è effetto dei 6.052 product update sintetici, non di nuove righe `PriceHistory` (`priceHistoryInserted=0`). Se dopo la nuova patch `updatedProducts=0` ma `pricesPushed` resta alto, aprire follow-up price bridge/checksum/backfill dedicato.

**Outbox classification:**
- Crescita `571 → 693` classificata come accumulo reale da `PayloadValidation` su eventi catalog/prezzi; non è stata cancellata.
- Fix osservabilità: il log `sync_events_summary` ora espone `syncEventsSkippedProtectedLocalCommit`.
- Follow-up necessario: indagare payload validation outbox/RPC in task dedicato se persiste.

**Test eseguiti durante la seconda passata:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.ImportAnalyzerTest' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.068*' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.067 full import with identical bridged price history does not dirty the catalog'` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME=... JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.util.FullDbExportImportRoundTripTest.RT-FULL reimporting the same workbook after apply is product and price no-op' --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest.068 quick sync does not let stale event overwrite product pushed in same local commit'` → ✅ `BUILD SUCCESSFUL`.
- Primo batch mirato allargato → ❌ un test storico (`061 drain marks manual full sync for empty entity ids with changed count`) ha intercettato una regressione nella condizione di skip eventi.
- Fix applicato: skip `protectedLocalCommitIds` solo quando l'evento aveva id e tutti sono stati rimossi dalla protezione; eventi con ids vuoti e `changedCount > 0` tornano a marcare `manualFullSyncRequired`.
- Rerun test storico + nuovo test stale event → ✅ `BUILD SUCCESSFUL`.
- Rerun batch mirato allargato (`DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`, `GeneratedExitDestinationResolverTest`) → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → ✅ `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → ✅ `BUILD SUCCESSFUL`; warning toolchain/AGP preesistenti.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest` → ✅ `BUILD SUCCESSFUL`.
- `git diff --check` → ✅ nessun errore.

**Check obbligatori — seconda passata:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `assembleDebug` verde |
| Lint | ✅ ESEGUITO | `lintDebug` verde; solo warning toolchain/AGP preesistenti |
| Warning nuovi | ✅ ESEGUITO | Nessun warning Kotlin nuovo osservato; `git diff --check` verde |
| Coerenza con planning | ⚠️ PARZIALE | No-op locale corretto e coperto; bulk bloccato correttamente finché manca ciclo B live patchato |
| Criteri di accettazione | ⚠️ PARZIALE | Criteri static/JVM/UX/no schema/outbox rispettati; live no-op e bulk restano non chiusi |

**Baseline regressione TASK-004 — seconda passata:**
- Test eseguiti: `DefaultInventoryRepositoryTest`, `ImportAnalyzerTest`, `FullDbExportImportRoundTripTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`, `GeneratedExitDestinationResolverTest`, full `testDebugUnitTest`.
- Test aggiunti/aggiornati: no-op semantico trim/case/blank; price history già bridged senza dirty infinito; round-trip full DB identico; protezione evento stale post local commit.
- Limiti residui: ciclo B live con app aggiornata non eseguito; bulk tests N/A perché bulk non implementato.

**Incertezze:**
- INCERTEZZA: il ciclo B live aggiornato non è stato rieseguito dall'app installata con questa patch; il file candidato osservato in sola lettura non è identico allo stato locale corrente.
- INCERTEZZA: outbox `PayloadValidation` richiede diagnosi dedicata sul payload/RPC remoto; non è corretto correggerlo con delete o cleanup.

**Handoff notes:**
- Installare build con questa patch e ripetere ciclo B dal flusso `DatabaseScreen`/picker full DB, non via `ACTION_VIEW`.
- Se il nuovo log mostra `updatedProducts=0`, `dirtyMarkedProducts=0`, `dirtyMarkedPrices=0`, `productsPushed=0`, `pricesPushed=0`, allora aprire Gate 3 bulk product push.
- Se resta `updatedProducts=6052`, usare i nuovi campi `productDirtyReasons` / `productDirtyReasonSample` per classificare se sono delta reali da file stale/local remote state oppure ulteriore confronto spurio.

### Esecuzione — 2026-04-27

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt` — aggiunti stage progress granulari `SYNC_PRICES_PUSH`, `SYNC_PRICES_PULL`, `SYNC_EVENTS_DRAIN` per distinguere push prezzi, pull prezzi e drain eventi.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt` — `runSyncEventDrainCycle` espone `SYNC_EVENTS_DRAIN` invece di `PULL_CATALOG`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — progress reporter separati per push prezzi, pull prezzi e apply da eventi sync; log/metriche `SYNC_PRICES_PUSH` e `syncPricesMs` preservati.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/CloudSyncIndicator.kt` — mapping breve dei nuovi stage.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt` — mapping esteso degli stage e badge upload/download coerenti.
- `app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — copy localizzate per invio prodotti, invio prezzi, aggiornamento prezzi, aggiornamento eventi sync e database locale pronto.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt` — fake drain allineato al nuovo stage `SYNC_EVENTS_DRAIN`.
- `docs/TASKS/TASK-068-bulk-product-push-verifica-no-op-post-full-import.md` — log execution/review/fix/chiusura.

**Codice letto / Gate 0:**
1. Letti `docs/MASTER-PLAN.md`, questo task, TASK-067, TASK-066, `AGENTS.md`, `docs/CODEX-EXECUTION-PROTOCOL.md`.
2. Letti i path principali: `CatalogAutoSyncCoordinator`, `CatalogSyncStateTracker`, `InventoryRepository`, `ProductPriceDao`, `ProductRemoteRefDao`, `ProductDao`, `SupabaseCatalogRemoteDataSource`, `SupabaseProductPriceRemoteDataSource`, `CloudSyncIndicator`, `CatalogSyncViewModel`, stringhe `values*`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`, test navigation TASK-066.
3. Ricostruito call graph effettivo:
   `full DB import` / file condiviso o picker → preview/analysis → `DatabaseViewModel.importProducts` → `DefaultInventoryRepository.applyImport` → `applyImportAtomically` → dirty marking (`touchProductDirty`, `ensureProductRefForPricePushIfMissing`) → `notifyProductCatalogChanged` → `CatalogAutoSyncCoordinator.onLocalProductChanged` / debounce → `runPushCycle(local_commit)` → `syncCatalogQuickWithEvents` se `sync_events` disponibile → `pushCatalogProducts` → `pushProductPricesToRemote` → `recordOrEnqueueSyncEvent` → `drainSyncEventsInternal`.

**Gate 0 — baseline A disponibile:**
- Baseline A recente trovata in logcat emulator (`CatalogCloudSync`, 2026-04-27 19:50-20:02).
- Il file host candidato `/Users/minxiang/Downloads/Database_2026_04_21_14-06-26.xlsx` combacia con i conteggi workbook del full DB import: `Products=19695` righe dati, `Suppliers=57`, `Categories=27`, `PriceHistory=41108` righe dati. Questa corrispondenza non basta da sola a dichiarare "stesso identico file" per ciclo B senza passare dal flusso app.

**Matrice log sintetica:**

| scenario | timestamp_start | timestamp_end | new | updated | productsTouched | dirtyHints | productsPushed | PUSH_PRODUCTS_batchSize | PUSH_PRODUCTS_batchCount | PUSH_PRODUCTS_avgBatchMs | pushProductsMs | priceHistoryInserted | dirtyMarkedPrices | dirtyMarkedPriceProducts | pricesPushed | SYNC_PRICES_PUSH_batchSize | SYNC_PRICES_PUSH_batchCount | SYNC_PRICES_PUSH_avgBatchMs | syncPricesMs | durationMs | syncEventOutboxPending | manualFullSyncRequired | syncEventsGapDetected | classificazione_risultato |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| Baseline live già osservata / ciclo A post TASK-067 | 2026-04-27 19:50:46 | 2026-04-27 20:02:50 | N/D | N/D | 6052 (da contesto/log task) | 6052 | 6052 | 1 | 6052 | 99 | 601170 | N/D | N/D | N/D | 48244 | 80 | 604 | 139 | 93883 | 724082 | 353 → 571 | false | false | delta_reale_o_bridge_backfill_lento; collo di bottiglia `PUSH_PRODUCTS batchSize=1`; price push alto da classificare solo con ciclo B |
| Drain pre-A / foreground | 2026-04-27 19:50:10 | 2026-04-27 19:50:12 | N/A | N/A | N/A | 0 (`sync_busy` skip push) | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | 0 | N/A | N/A | N/A | N/A | 2744 | 353 | false | false | sync_events_drain_no_fetch; outbox pendente non drenata |
| Drain post-A / realtime_signal | 2026-04-27 20:02:50 | 2026-04-27 20:02:51 | N/A | N/A | N/A | N/A | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | 0 | N/A | N/A | N/A | N/A | 214 | 571 | false | false | sync_events_drain_no_fetch; outbox cresciuta e non convergente |
| Ciclo B re-import identico | 2026-04-27 20:33:xx | 2026-04-27 20:33:53 | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | N/D | BLOCKED: il tentativo non distruttivo via `ACTION_VIEW` ha aperto la preview Excel generica e l'app e' terminata con OOM prima di qualunque confirm/apply/sync |

**Gate 1 — no-op ciclo B:**
- Precondizione osservata: dopo A il tracker e' tornato `COMPLETED`; il drain realtime successivo e' durato ~214 ms con `syncEventsFetched=0`.
- Tentativo non distruttivo: push del file candidato su emulator Download + `ACTION_VIEW` verso `MainActivity`.
- Esito: prima prova `file://` → schermata "Couldn't open the file"; seconda prova con `content://media/external/file/...` → OOM in `ExcelUtils.readAndAnalyzeExcelDetailed` / `WorkbookFactory.create`, prima dell'apply. Nessun `APPLY_IMPORT` e nessun `catalog_push` del ciclo B sono partiti.
- Decisione: **no-op gate non superato**. Bulk product push **non autorizzato** e **non implementato**.

**Gate 2 — concorrenza/scheduling:**
- Evidenza log: durante drain foreground iniziale `cycle=catalog_push outcome=skip reason=sync_busy dirtyHints=0`; poi il push A parte dopo completamento del drain.
- Evidenza log: dopo A il `realtime_signal` avvia solo `sync_events_drain` breve, dopo `tracker busy=false stage=COMPLETED`.
- Classificazione: nessuna doppia sync catalogo parallela osservata nei log A; `tryBegin`/tracker busy protegge l'overlap. Limite: ciclo B non eseguito, quindi nessuna evidenza B su coalescing.

**Gate 3 — bulk product push:**
- Non implementato. Motivo: il contratto del task vieta bulk prima del ciclo B; il ciclo B live e' bloccato prima dell'apply da OOM nel percorso preview generico.
- Collo di bottiglia A confermato: `PUSH_PRODUCTS batchSize=1`, `batchCount=6052`, `pushProductsMs=601170`, `avgBatchMs=99`.
- Follow-up necessario: riprodurre ciclo B dal flusso full DB import corretto (picker/Database import che usa la pipeline streaming) oppure fornire un path testabile che non passi dalla preview POI generica.

**Gate 4 — price push:**
- Baseline A: `pricesPushed=48244`, `SYNC_PRICES_PUSH batchSize=80`, `batchCount=604`, `avgBatchMs=139`, `syncPricesMs=93883`.
- Ciclo B non disponibile: non si puo' distinguere in modo sicuro tra bridge remoto incompleto, `product_price_remote_refs` assenti, eventi prezzo realmente nuovi o bisogno di checksum/chiave logica remota.
- Nessuna dedup remota improvvisata, nessun checksum/RPC/schema aggiunto.

**Outbox classification:**
- `syncEventOutboxPending=353` prima di A; `571` dopo A.
- I drain osservati hanno `syncEventsFetched=0`, `syncEventsProcessed=0`, `syncEventOutboxRetried=0`, `manualFullSyncRequired=false`, `syncEventsGapDetected=false`.
- Classificazione: **accumulo reale / non convergente da follow-up**, non self-skip risolto. Nessuna pulizia manuale, nessun delete/truncate.

**UX changes:**
- UI/UX: aggiunti stage progress reali per `SYNC_PRICES_PUSH`, `SYNC_PRICES_PULL`, `SYNC_EVENTS_DRAIN` (motivo: chiarezza e coerenza con log reali).
- UI/UX: copy `PUSH_PRODUCTS` ora comunica esplicitamente "Invio prodotti al cloud"; `SYNC_PRICES_PUSH` "Invio prezzi al cloud"; drain "Aggiornamento eventi sync"; hint locale "Database locale pronto..." (motivo: evitare messaggi pull-centrici mentre la sync sta inviando o drenando eventi).
- Nessun redesign, nessuna modifica navigation, DAO, schema Room/Supabase, RPC, RLS, trigger o migration.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint | ✅ ESEGUITO | `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → `BUILD SUCCESSFUL`; solo warning toolchain/AGP preesistenti |
| Warning nuovi | ✅ ESEGUITO | `git diff --check` verde; nessun warning Kotlin nuovo dal codice modificato osservato nei check |
| Coerenza con planning | ⚠️ NON ESEGUIBILE COMPLETAMENTE | Gate A letto e classificato; Gate B tentato ma bloccato da OOM prima dell'apply; bulk correttamente non implementato |
| Criteri di accettazione | ⚠️ PARZIALE | Criteri live/no-op/bulk non chiudibili senza ciclo B; criteri static/JVM/UX/no schema rispettati |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `CatalogSyncViewModelTest`, `GeneratedExitDestinationResolverTest`; full `testDebugUnitTest`.
- Test aggiunti/aggiornati: `CatalogAutoSyncCoordinatorTest` fake drain aggiornato al nuovo stage `SYNC_EVENTS_DRAIN`.
- Limiti residui: nessun ciclo B live riuscito; bulk test non aggiunti perche' bulk non implementato.

**Test / comandi eseguiti:**
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest' --tests 'com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest'` → fallito per attach MockK/ByteBuddy nel `CatalogSyncViewModelTest` (problema ambientale, non regressione funzionale).
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true' ./gradlew --no-daemon testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest' --tests 'com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest' --tests 'com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest'` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew lintDebug` → `BUILD SUCCESSFUL`.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true' ./gradlew --no-daemon testDebugUnitTest` → fallito per attach ByteBuddy su full suite (400 test avviati, 134 failure ambientali).
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --stop` → daemon fermati.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener' ./gradlew --no-daemon testDebugUnitTest` → `BUILD SUCCESSFUL`.
- `git diff --check` → nessun errore.

**Incertezze:**
- INCERTEZZA: il file candidato combacia con i conteggi del full DB import, ma il tentativo live non ha raggiunto il flusso full DB streaming; quindi non e' provato come "stesso identico file" per ciclo B.
- INCERTEZZA: `syncEventOutboxPending=571` richiede log/cause sul path `recordOrEnqueueSyncEvent`/retry; non e' stato pulito ne' modificato.
- INCERTEZZA: `pricesPushed=48244` resta non classificabile senza ciclo B riuscito o design remoto dedicato.

**Handoff notes:**
- Prossimo operatore: riprodurre ciclo B dal pulsante Import della `DatabaseScreen` / picker documentato, non via `ACTION_VIEW` preview generica.
- Se ciclo B passa, si puo' riaprire Gate 3 e implementare bulk product push con batch bounded + split/fallback/singolo.
- Se ciclo B fallisce o crasha ancora, la priorita' e' correggere il percorso full DB re-import/no-op o la scelta del parser, non il batch product push.

---

## Review

### Review autonoma — 2026-04-28, terza passata

- Diff controllato: nessun cambio schema Room/Supabase, nessuna RPC/migration/RLS/trigger/publication, nessuna cancellazione outbox/sync_events, nessun bulk product push.
- Root cause no-op: il nuovo log live spiega il grosso del falso dirty con `supplierId:5854`; la patch preserva l’ID locale esistente se il nome relazione importato è semanticamente uguale, invece di accettare un duplicato locale equivalente.
- Price no-op: le synthetic rows non vengono più create quando la PriceHistory importata rappresenta già lo stesso prezzo; il push prezzi resta guidato dalle righe reali senza `product_price_remote_ref`.
- TASK-067: metriche dirty preservate ed estese; test `067` mirati e repository largo verdi.
- TASK-066: navigation non toccata; `GeneratedExitDestinationResolverTest` verde.
- Scope: nessun refactor architetturale, nessuna dipendenza nuova, nessun path distruttivo.
- Rischi residui: serve ciclo B live con questa build e lo stesso file due volte; outbox `815` da riclassificare dopo no-op; duplicati accent/trim/case nuovi sono mitigati lato import, non consolidati nel DB.

### Review autonoma — 2026-04-27, seconda passata

- Diff controllato: nessun cambio schema Room/Supabase, nessuna RPC/migration/RLS/trigger/publication, nessuna cancellazione outbox/sync_events.
- Dirty/no-op: i confronti semanticamente nulli sono coperti da `ImportAnalyzerTest` + `DefaultInventoryRepositoryTest`; il round-trip full DB copre il secondo re-import identico post-apply su JVM.
- TASK-067: metriche `dirtyMarkedPrices` / `dirtyMarkedPriceProducts` preservate ed estese; il test price-only e la suite repository mirata restano verdi.
- TASK-066: navigation non modificata; `GeneratedExitDestinationResolverTest` incluso nel batch mirato allargato verde.
- Sync events: aggiunta protezione locale limitata al drain dello stesso `local_commit`; test nuovo copre evento remoto stale sugli stessi id appena pushati; test storico sugli eventi vuoti ripristinato dopo fix.
- Bulk: correttamente non implementato perché il no-op gate live aggiornato non è ancora passato con questa patch.
- Rischi residui: serve nuovo ciclo B live dall'app aggiornata; outbox `PayloadValidation` resta da follow-up; il file candidato nello snapshot locale corrente rappresenta delta reale, non no-op.

### Review autonoma — 2026-04-27

- Diff controllato: nessun cambio schema Room/Supabase, nessuna migration/RPC/RLS/trigger/publication, nessuna delete/truncate/outbox cleanup.
- TASK-067: dirty marking e metriche `dirtyMarkedPrices` / `dirtyMarkedPriceProducts` non modificate; log `SYNC_PRICES_PUSH`, `syncPricesMs`, `PUSH_PRODUCTS batchSize=1` preservati.
- TASK-066: navigation non modificata; test `GeneratedExitDestinationResolverTest` mirato verde nel batch JVM.
- Remote refs: nessun path di push/ref modificato; solo progress stage/copy.
- Sync events: nessun path outbox cancellato; drain ora espone stage UX dedicato.
- Rischio residuo principale: no-op gate live non completato; bulk rinviato correttamente.
- Rischio residuo aggiuntivo: OOM su apertura `ACTION_VIEW` del full DB export attraverso preview generica; fuori dal bulk, ma blocca questa via di misura.

---

## Fix

### Fix — 2026-04-28, quinta passata

- Primo run largo mirato senza `JAVA_TOOL_OPTIONS` ha fallito per attach ByteBuddy/MockK (`AttachNotSupportedException`) su `ImportAnalyzerTest` / `DatabaseViewModelTest`.
- Correzione/verifica mirata: rieseguito lo stesso set con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading'`; esito verde.
- Primo full `testDebugUnitTest` con solo `EnableDynamicAgentLoading` ha fallito ancora per attach ByteBuddy/MockK; rieseguito full con `-XX:+StartAttachListener` aggiunto; esito verde.
- Nessun fix codice post-test necessario sui nuovi casi bulk.

### Fix — 2026-04-28, quarta passata

- Primo giro mirato `FullDbExportImportRoundTripTest` ha fallito sul nuovo assert zero product push candidate: il test misurava il secondo re-import senza simulare la sync completata dopo il primo import, quindi i candidate derivavano dal ciclo A e non dal ciclo B.
- Fix test: prima del secondo re-import JVM vengono marcati come synced i ref prodotto e prezzo creati dal primo import; cosi' l'assert misura il no-op gate vero (`productsPushed=0` / `pricesPushed=0` come assenza candidate).
- Dopo il fix test: test mirati, `DatabaseViewModelTest`, regressione navigation, `assembleDebug`, `lintDebug`, full `testDebugUnitTest` e `git diff --check` tutti verdi.

### Fix — 2026-04-28, terza passata

- Primo compile/test lanciato in parallelo con un altro Gradle ha prodotto un errore KSP transitorio; riesecuzione sequenziale usata per le verifiche finali.
- Primo compile Kotlin ha segnalato una condizione nullable spezzata su più righe nel calcolo `alreadyRepresented`; corretta con parentesi esplicite.
- Primo round-trip mirato ha fallito perché un test storico richiedeva synthetic rows anche con PriceHistory completa. Il comportamento desiderato del task è l’opposto: con PriceHistory completa non si devono generare synthetic rows. Aggiornati i test; `RT-NOPH` continua a coprire il caso senza PriceHistory.
- Dopo i fix: compile, test mirati, suite larga, `assembleDebug`, `lintDebug`, full `testDebugUnitTest`, `git diff --check` tutti verdi.

### Fix — 2026-04-27, seconda passata

- Il batch mirato allargato ha fallito sul test storico `061 drain marks manual full sync for empty entity ids with changed count`.
- Root cause fix: la protezione `protectedLocalCommitIds` trattava anche eventi con `entityIds` vuoti come skippabili; corretto lo skip per applicarlo solo a eventi con almeno un id originario e tutti gli id rimossi dalla protezione.
- Rerun test storico + nuovo test stale-event: verde.
- Rerun batch mirato allargato: verde.

### Fix — 2026-04-27

- Primo giro test mirato fallito per attach MockK/ByteBuddy nel `CatalogSyncViewModelTest`.
- Correzione/verifica: rieseguito lo stesso set con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true'` e `--no-daemon`; esito verde.
- Primo giro full `testDebugUnitTest` fallito ancora per attach ByteBuddy su più suite.
- Correzione/verifica: fermati i daemon e rieseguita la full suite con `JAVA_TOOL_OPTIONS='-Djdk.attach.allowAttachSelf=true -XX:+StartAttachListener'` e `--no-daemon`; esito verde.
- Nessun fix codice post-review necessario oltre alla patch UX/stage già descritta.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | `PARTIAL` |
| Data chiusura | 2026-04-28 |
| Tutti i criteri ✅? | No — bulk JVM implementato e verificato, ma serve ciclo B live con stesso identico file; non dichiarare `DONE FULL` |
| Rischi residui | No-op gate da ripetere con lo stesso identico file; outbox `syncEventOutboxPending` da riclassificare dopo no-op; bulk product push da validare live su delta reale; se resta dirty falso, usare fingerprint/classificazione, `relationDirtySample` e metriche price-aware |

---

## Riepilogo finale

Execution chiusa in **PARTIAL** dopo quinta passata: synthetic price generation sugli update prodotto resta price-aware, PriceHistory prevale sui fallback sintetici, fingerprint dataset/log diagnostici sono presenti, e il bulk product push client-side e' implementato con batch bounded + fallback singolo. Mancano ancora ciclo B live con stesso file e validazione live del bulk su delta reale.

---

## Handoff

- **TASK-068** è chiuso in **`PARTIAL`**: quinta passata implementa bulk product push JVM/offline-safe e conserva i fix no-op/dirty price-aware, ma il ciclo B live con questa build resta da rieseguire.
- **Prossimo passo reale:** installare build aggiornata e riprodurre ciclo B con lo stesso identico file due volte, con sync completa e quiet window 30-60s tra ciclo A e ciclo B.
- Non usare `test_import Database1.xlsx` come secondo ciclo di no-op contro `Database_test_import 2.xlsx`: sono dataset diversi.
- Se il ciclo B resta non no-op, leggere i nuovi campi log `fileProductCount`, `filePriceHistoryCount`, `dbProductCountBefore`, `dbPriceHistoryCountBefore`, `importFingerprintShort`, `dbSnapshotFingerprintShort`, `classificazione_risultato`, `relationDirtySample`, `relationDirtyRealChangedCount`, `relationDirtySemanticEquivalentCount`, `relationDirtyUnknownCount`, `priceDirtyFromPriceFieldChange`, `priceDirtyFromSyntheticFallback`, `priceDirtySkippedBecauseNonPriceProductUpdate`, `priceRowsPendingBridgeBefore`, `priceRowsPendingBridgeAfter`.
- Bulk product push e' gia' implementato lato client su istruzione esplicita utente; non dichiararlo validato live finche' non passa un ciclo su delta reale e un ciclo B identico senza push.
- **Non** cambiare schema Room/Supabase/RPC/RLS/trigger/publication nel perimetro del task senza follow-up dedicato.
- **Non** cancellare outbox/`sync_events`; **non** reset remoto.
- **Percorso singolo** preservato come **fallback** e isolabile tramite costante interna.
- **Ottimizzazione price** complessa **fuori perimetro** se richiede schema/RPC/backend — task separato.
- **UX**: stage/copy già migliorati per push prodotti, push/pull prezzi e drain eventi sync; nessun redesign ampio.
- **Evidenza futura:** matrice log ciclo B con `import_dirty_marking`, `phase_metrics`, `sync_phase_durations`, `sync_events_summary`, senza segreti e senza sample massivi.
