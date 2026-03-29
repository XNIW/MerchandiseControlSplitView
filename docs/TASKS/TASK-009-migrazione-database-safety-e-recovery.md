# TASK-009 — Migrazione database — safety e recovery

---

## Informazioni generali

| Campo                 | Valore |
|-----------------------|--------|
| ID                    | TASK-009 |
| Stato (backlog / `MASTER-PLAN`) | **`DONE`** |
| Fase pianificazione / esecuzione (`AGENTS.md`) | **`DONE`** — review planner APPROVED 2026-03-29; tutti i criteri ✅ |
| Priorità              | **ALTA** |
| Area                  | Database / Room / migrazioni / recovery |
| Creato                | 2026-03-29 |
| Ultimo aggiornamento  | 2026-03-29 — **REVIEW planner APPROVED → DONE**; tutti i check e criteri verificati con evidenza. |

**Nota governance:** la fase **`EXECUTION`** è attiva; restano vincolanti **C1–C6** **prima del primo commit** che modifica codice applicativo o Gradle. La transizione **non** equivale ad approvazione implicita di **`room-testing`** (vedi **C6**).

**Nota tracking:** `MASTER-PLAN.md` allineato a **`EXECUTION`** per **TASK-009** in questo branch.

---

## Freeze di perimetro (vincolo operativo)

**Incluso esplicitamente (solo questo ambito):**

- `AppDatabase.kt`: definizione migrazioni Room, catena **v1→v6**, `Room.databaseBuilder` / wiring migrations, validazione schema attesa da Room.
- **Recovery / backup:** strategia documentata (raccomandazioni utente e/o meccanismo minimo **locale**), **senza** scorciatoie distruttive (vedi § Guardrail recovery).
- **Test di migrazione:** introduzione di test automatizzati sui path critici, dipendenze `androidTest`/`test` strettamente necessarie.

**Escluso in modo esplicito (vietato come scope di default):**

- **TASK-009 resta tecnico puro:** migrazioni Room, validazione schema, test migrazione, strategia recovery documentata. **Nessun** intervento UI/UX di rilievo, **nessun** redesign, **nessuna** estensione di flussi utente.
- **Eccezione marginale ammessa (solo se necessità reale e locale):** eventuale **micro-copy** recovery (es. una stringa in dialogo/snackbar **già esistente** o nota release) — **mai** nuove schermate, **mai** navigation nuova, **mai** feature UX. Preferenza assoluta: documentazione / handoff senza toccare risorse UI.
- **Navigation**, **Compose screen** complessi, stringhe per flussi non strettamente legati a recovery *(default: documentazione utente)*.
- **Refactor** di `InventoryRepository`, **ViewModel**, **DAO**, altre entity/business logic **salvo necessità reale dimostrata** dalla correzione di una migrazione o da un fallimento di validazione schema **non risolvibile** con SQL minimo in `AppDatabase` + eventuale aggiustamento **localizzato** del modello persistito (ancora nel perimetro “schema/migrazione”).
- **Scope creep** di qualsiasi tipo: nessuna nuova feature prodotto, nessun redesign architetturale.

**Regola:** ogni eccezione al freeze va **motivata nel log Execution** con evidenza (errore, stack, mismatch schema) e resta **minima**.

---

## Dipendenze

- **TASK-004** (`DONE`) — suite JVM repository/ViewModel; **baseline regressione** da eseguire in `EXECUTION` **solo se** le modifiche toccano logica dati/repository/ViewModel oltre il solo layer migrazioni (vedi § Planning).
- **`androidx.room:room-testing` (opzione A)** — **non** è una dipendenza assunta dal planning: richiede **approvazione esplicita** (vedi § *Dipendenza `room-testing` — decisione condizionata* in Strategia test).

---

## Scopo

Verificare che i percorsi di upgrade Room **v1→v6** siano **sicuri**, **ripetibili** e coerenti con la **validazione schema** Room e con la **preservazione dei dati attesi** sui path **MVC** (non solo «il DB apre»). Definire strategia di **test migrazione** (primaria + fallback), **guardrail** su recovery **non distruttivi**, e documentare decisioni — **entro il perimetro congelato** sopra.

---

## Contesto

- Le migrazioni vivono in `AppDatabase.kt` (`MIGRATION_1_2` … `MIGRATION_5_6`); `getDatabase` usa `Room.databaseBuilder` + `addMigrations`, **senza** `fallbackToDestructiveMigration*` (comportamento da **mantenere** — vedi Decisioni).
- `exportSchema = true` con `room.schemaLocation` → in repo è presente lo snapshot **`6.json`**. Il planning **non** presuppone il recupero **completo** degli schema **1–5** come default: vedi § *Minimum viable migration coverage* e § *Guardrail anti-overengineering*.
- I test JVM esistenti (**TASK-004**) usano `Room.inMemoryDatabaseBuilder` **senza** `addMigrations` → **non** esercitano i percorsi di upgrade su DB versionati.

---

## Non incluso

- **UI/UX di prodotto** (allineato a § Freeze): nessun redesign, nessuna estensione flussi — TASK-009 **tecnico puro** (vedi eccezione micro-copy lì).
- Redesign UI / `DatabaseScreen` / navigation (**TASK-015** e affini).
- Refactor ampi di `InventoryRepository`, DAO o ViewModel **salvo** emergenza **dimostrata** e **minima** (vedi Freeze di perimetro).
- Logica import/export Excel (**TASK-006**, **TASK-007**, **TASK-021**) **salvo** riferimento testuale all’**export completo** come raccomandazione backup pre-update (recovery), senza implementare nuovi flussi.
- iOS come fonte di verità.

---

## Priorità di rischio (ordine obbligatorio per la futura EXECUTION)

L’**ordine di lavoro** in Execution **deve** seguire questa priorità (P0 prima, poi P1, poi P2). Non invertire senza motivazione scritta nel log Execution.

| Livello | Argomento | Motivo |
|---------|-----------|--------|
| **P0** | **`MIGRATION_5_6` vs `Product.kt` vs schema `6.json`** | Massimo impatto: mismatch colonne `products` / validazione Room / rischio crash all’avvio o due shape diversi (install vs upgrade). |
| **P1** | **`MIGRATION_3_4` e nome tabella `HistoryEntry` vs `history_entries`** | Fallimento SQL su upgrade per tabella history se il nome reale storico non coincide. |
| **P2** | **`JournalMode` / build / wiring test** | Garantire compilazione e infrastruttura test; dipende da P0/P1 per sapere quali path assertare. |

---

## Strategia test (decisa in PLANNING — non lasciare generico)

### Minimum viable migration coverage (default Execution)

**Non** è obiettivo del task recuperare o rigenerare **tutta** la catena di schema **1–5** salvo necessità dimostrata.

| Obiettivo | Copertura minima pianificata | Estensione |
|-----------|------------------------------|------------|
| **P0** | Test / validazione sul salto **5 → 6** (`MIGRATION_5_6`), coerente con `Product` / `6.json` | Estendere ad altri salti **solo** se l’analisi in Execution rileva rischio reale non coperto |
| **P1** | Test / validazione sul salto **3 → 4** (`MIGRATION_3_4`, tabella history) | Idem: niente catena completa v1→… se non emerge evidenza |

Path aggiuntivi (es. 4→5, 1→2) **solo** se documentata **evidenza reale** durante l’analisi (bug, dipendenze SQL, segnalazione campo).

### Guardrail anti-overengineering

- **Non** recuperare schema storici, JSON aggiuntivi o fixture **oltre il minimo necessario** per coprire **P0** e **P1**, salvo **evidenza reale** (log Execution: perché il minimo non basta).
- Evitare backlog “completo 1–5” come presupposto di lavoro: snellezza ed efficienza hanno priorità su copertura storica esaustiva non motivata.

### Assert sui dati (data preservation) — obbligatorio sul MVC

**Vincolo:** non basta che la migrazione passi la **validazione schema** / che il DB «apra»: i test sul **MVC** devono dimostrare che i **dati attesi** sopravvivono (o che eventuale perdita/riallineamento legacy è **esplicitamente** quello voluto dallo schema), per evitare **falsi positivi** (migrazione che non fallisce ma altera o perde valori utili).

**Pattern obbligatorio** per ogni salto MVC coperto da test automatico (**5→6** e **3→4**):

1. **Pre-migrazione:** inserire **almeno una riga rappresentativa** (o un set minimo documentato) nello stato DB alla versione sorgente — tramite SQL su `SupportSQLiteDatabase`, fixture, o equivalente.
2. **Migrazione:** eseguire il salto (catena ridotta al minimo necessario).
3. **Post-migrazione:** **query + assert sui valori** delle colonne chiave (non sostituibili con il solo “DB apre”); in aggiunta § *Integrità DB e oggetti secondari*.

**5→6 (`products`, `MIGRATION_5_6`):** il test deve distinguere in modo **esplicito**:

- **Campi che devono restare invariati** rispetto al valore inserito pre-migrazione (minimo richiesto dal criterio di accettazione **#11**): `barcode`, `itemNumber`, `productName`, `secondProductName`, `purchasePrice`, `retailPrice`, `supplierId`, `categoryId`, `stockQuantity` — con semantica coerente a `NULL`/tipi del fixture.
- **Campi legacy eventualmente rimossi o riallineati** per decisione di schema (es. `oldPurchasePrice`, `oldRetailPrice` se presenti a v5): assert **documentati** sul comportamento atteso post-migrazione (colonna assente, valore non più in `products`, migrazione verso altro canale se previsto da decisione P0, ecc.) così che non si confonda «verde» con «dati persi per errore».

**3→4 (tabella storico, `MIGRATION_3_4`):** assert che le **righe esistenti** prima del salto siano **preservate** (stesso conteggio atteso e/o stessi identificativi chiave del fixture, es. `uid` o chiave usata nel test); che la colonna **`category`** aggiunta dalla migrazione sia valorizzata come da **default previsto** dalla SQL di migrazione (nel codice attuale: `TEXT NOT NULL DEFAULT ''`) sulle righe già presenti pre-update. **Fixture v3:** la tabella creata nel DB di test deve essere quella su cui opera `ALTER TABLE HistoryEntry` in `AppDatabase.kt`; su SQLite Android i **nomi non quotati** sono in genere **case-insensitive**, quindi `HistoryEntry` può risolversi su `history_entries`, ma se il fixture usasse un nome **diverso** (es. quoting diverso) il test deve fallire in modo esplicito e guidare un fix SQL **minimo** — senza allargare il perimetro.

**Opzione A e B:** entrambe devono rispettare questo pattern dove applicabile (stesso livello di evidenza sui **valori**, non solo DDL).

### Integrità DB e oggetti secondari (MVC, leggero)

**Obiettivo:** evidenza che, oltre a schema Room / dati attesi, il DB post-migrazione **non** presenti inconsistenze strutturali o oggetti critici mancanti — **senza** allargare il task oltre il minimo sui path MVC.

- **`PRAGMA foreign_key_check`:** eseguire sul DB **dopo** il salto MVC (stesso fixture/test che copre **5→6** e/o **3→4**); evidenza che il risultato sia **vuoto** / nessuna violazione (log test o assert sul cursore).
- **`PRAGMA integrity_check`:** **se pratico** nello stesso perimetro (test JVM/androidTest su DB file o helper), eseguire e documentare esito **`ok`**; se **non** pratico (limite runner, tempo, API), documentare **⚠️ motivazione** nel log Execution — **non** è un pretesto per omettere `foreign_key_check`.

**Oggetti schema secondari (solo dove rilevante sul MVC):**

- **Indici critici** ricreati o toccati dalla migrazione in scope — es. su **5→6** gli indici su `products` (`barcode`, `supplierId`, `categoryId`) definiti in `MIGRATION_5_6`; su **3→4** indici coinvolti se presenti nel path. Verifica **minima:** esistenza (es. `sqlite_master` / `PRAGMA index_list`) sugli oggetti attesi, senza audit completo di tutti gli indici del DB.
- **View critiche** create o dipendenti dal path — es. **`product_price_summary`** su **5→6**. Verifica **minima:** la view **esiste** ed è interrogabile (query semplice o `SELECT COUNT(*)` senza errore) sul DB post-migrazione del test.

### A) Primaria — **preferita tecnicamente** *(subordinata all’approvazione dipendenza)*

**Scelta:** `androidTest` con **`MigrationTestHelper`** + dipendenza **`androidx.room:room-testing`** (versione **allineata** a Room tramite **Version Catalog** del progetto — vedi sotto).

| Pro | Contro |
|-----|--------|
| API ufficiale Room; `runMigrationsAndValidate` contro schema JSON attesi | Per **5→6** servono tipicamente **`5.json`** e **`6.json`** (non l’intera serie 1–5) |
| Validazione esplicita post-migrazione | Job CI più lento; richiede device/emulator o runner strumentato |
| | **Introduce una nuova dipendenza di progetto** — vincolata a governance (sotto) |

### Dipendenza `room-testing` — decisione condizionata (governance)

- L’opzione **A** è **preferita sul piano tecnico**, ma è **subordinata** all’**approvazione esplicita** dell’aggiunta della libreria **`androidx.room:room-testing`**, in linea con la regola di progetto **«no nuove dipendenze senza richiesta»** (`CLAUDE.md` / `AGENTS.md`).
- **Se l’approvazione viene concessa:** l’esecutore può aggiungere `room-testing` tramite **Version Catalog** + `app/build.gradle.kts` e implementare i test con `MigrationTestHelper` sul **MVC**.
- **Se l’approvazione non viene concessa:** l’esecutore **non** aggiunge `room-testing` e **non** modifica Gradle per quella dipendenza. Deve allora:
  1. **Usare l’opzione B** — **se** sufficiente a soddisfare il **MVC** (5→6 e 3→4), i criteri di accettazione inclusi il gate parità (#3) dove applicabile con mezzi **B**; oppure
  2. **`BLOCKED` / stop** — **prima** di qualsiasi modifica al codice applicativo o Gradle «per A», documentare nel file task che serve **sblocco** sulla dipendenza; **non** procedere per inerzia con A senza approvazione.

Questo punto **non** è implicito: è vincolante per l’ordine decisionale **approvazione dipendenza → A**, **oppure** **B idoneo**, **oppure** **stop**.

**Version Catalog (obbligatorio in Execution se si usa opzione A **e** l’approvazione dipendenza è ottenuta):**

- La coordinata **`androidx.room:room-testing`** va dichiarata in **`gradle/libs.versions.toml`** (versione / alias coerente con `room-runtime` / BOM già usati) e referenziata da **`app/build.gradle.kts`** tramite il catalog — **vietato** lasciare la dipendenza **solo** hardcoded nel modulo senza voce nel catalog (salvo eccezione documentata tipo assenza temporanea del catalog, non applicabile a questa repo).

**File tipicamente toccati (opzione A):**

- `gradle/libs.versions.toml` — versione / libreria `room-testing` (o alias) allineata a Room.
- `app/build.gradle.kts` — `androidTestImplementation(libs....)` (o equivalente catalog), **non** stringa versione duplicata ad hoc.
- `app/schemas/com.example.merchandisecontrolsplitview.data.AppDatabase/*.json` — **solo** le versioni **minime** necessarie (es. `5.json` **se** mancante e richiesto per 5→6; `6.json` già presente).
- `app/src/androidTest/.../AppDatabaseMigrationTest.kt` (nome indicativo) — test che invoca `MigrationTestHelper`, `createDatabase` alla versione sorgente (es. 5 o 3 per il caso P1), `runMigrationsAndValidate` con `AppDatabase::class`, `addMigrations(...)` **e** assert **data preservation** § *Assert sui dati*.

### B) Fallback — se gli artefatti per **A** sul perimetro **minimo** non sono ottenibili in modo **affidabile**

**Scelta:** **fixture DB minime** o **SQL raw** che rappresentano **solo** lo stato **pre-migrazione** necessario per **P0** (pre-v6 / post-v5) e/o **P1** (pre-v4 / post-v3), poi esecuzione della/e migrationi successive e assert su colonne/tabelle/indici critici.

| Pro | Contro |
|-----|--------|
| Nessun obbligo di serie JSON 1–5 | Meno “ufficiale”; manutenzione se schema evolve |
| Focus su P0/P1 | Fixture devono essere giustificate in Execution |

**File tipicamente toccati (opzione B):**

- `app/src/androidTest/assets/...` — eventuale `*.db` o SQL **minimo**.
- Package test migrazione — bootstrap + migrate con evidenza nel log Execution.
- `app/build.gradle.kts` / **`gradle/libs.versions.toml`** — solo se servono dipendenze o risorse aggiuntive **minime**.

**Ordine decisionale dichiarato (sintesi):**

1. **Approvazione esplicita** per **`room-testing`**? → Se **sì**, **A** è la traccia preferita sul MVC (salvo vincoli artefatti JSON come già descritto). Se **no**, **non** implementare A.
2. Senza **A**: adottare **B** **se** copre **MVC** (5→6 e 3→4) e i gate; altrimenti **stop** e richiesta sblocco (dipendenza e/o vincoli test).
3. Con **A** approvata ma artefatti **A** non affidabili per un salto: **B** per quel salto, come già previsto (tentativo documentato).

La scelta finale (A, B, misto A+B, o `BLOCKED`) va registrata in **Decisioni** e nel log **Execution**.

---

## Guardrail recovery

### Decisione esplicita (inviolabile in TASK-009)

- **NON** introdurre `fallbackToDestructiveMigration`, `fallbackToDestructiveMigrationOnDowngrade`, né altre **scorciatoie distruttive silenziose** che cancellino o ricreino il DB senza consapevolezza utente.
- Un fallimento migrazione deve restare **visibile** (crash / errore controllato), non **mascherato** da wipe dati.

### Ordine di pragmatismo (valutazione in Execution)

| Ordine | Opzione | Contenuto |
|--------|---------|-----------|
| **a)** | Nessun backup automatico in-app | **Raccomandazione esplicita** all’utente: **export database completo** (flusso già coperto da **TASK-021**) prima di installare build che cambiano migrazioni / versione DB. |
| **b)** | Copia file DB pre-migrazione | **Solo se** implementazione **locale**, **sicura** (es. copia in directory app dedicata, gestione spazio/permessi), **basso impatto** sulle altre componenti e **fuori** dal percorso hot di UI complessa. |

### Criteri per scegliere tra **a)** e **b)** in Execution (documentare nel log + Decisioni)

- **Default atteso:** **solo a)** — documentazione + messaggio release/handoff, **nessun** codice aggiuntivo.
- Passare a **b)** **solo se** tutte vere:
  1. Esiste un **requisito** chiaro (es. beta interna con dati non sostituibili) **e** **a)** è giudicata insufficiente.
  2. La copia è **atomica** rispetto all’avvio migrazione, **limitata** al file DB noto (`app_database`), **senza** permessi storage ampi o share non necessari.
  3. Il rischio (spazio disco, file orfani) è **accettato** e **documentato** in Riepilogo finale / Handoff.

---

## Precondizioni obbligatorie prima di EXECUTION

**Prima del primo commit di Execution** l’esecutore deve completare la checklist sotto (tutte le voci **SÌ** o motivazione scritta nel log Execution).

| # | Check operativo obbligatorio | Esito atteso |
|---|------------------------------|--------------|
| C1 | **Branch / working tree reale** — Il branch su cui si committa è quello concordato per il task (nessun commit “orfano” su clone sbagliato senza consapevolezza) | SÌ / N/A documentato |
| C2 | **`docs/MASTER-PLAN.md`** — **TASK-009** risulta **`ACTIVE`** nel backlog; **nessun altro** task risulta **`ACTIVE`**; obiettivo attivo / tabella stato globale coerenti con “unico ACTIVE = TASK-009” | SÌ |
| C3 | **`docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md`** — Stato backlog nel testo (tabella *Informazioni generali*) coerente con **`ACTIVE`**; fase nel file task allineata a **`EXECUTION`** dopo transizione documentata | SÌ |
| C4 | **Incrocio C2↔C3** — Stesso significato: TASK-009 è l’unico task attivo, stesso file task referenziato dal `MASTER-PLAN` | SÌ |
| C5 | **Diff locale** — Eventuali modifiche non committate a `MASTER-PLAN` / file task **incluse** nel primo commit di Execution **oppure** esplicitamente escluse con motivo (no stato “a metà”) | SÌ |
| C6 | **Dipendenza `room-testing` (opzione A)** — Se si intende usare **A**: esiste **approvazione esplicita** (utente / owner progetto / governance) documentata nel file task o nel messaggio di avvio Execution per l’aggiunta di **`androidx.room:room-testing`**. Se **no** intenzione di usare **A**: segnare **N/A** e attenersi a **B** o a stop se B non basta | SÌ / N/A + traccia B |

**Regole operative legate a C6:**

- **Senza approvazione per `room-testing`:** vietato aggiungere la dipendenza; usare **B** se sufficiente per **MVC**; se **B** non è sufficiente, **non** iniziare modifiche Gradle/test “tipo A” — impostare **`BLOCKED`** e richiedere sblocco dipendenza.
- **Con approvazione:** procedere con **A** secondo § Strategia test (catalog + test).

Se **C2–C4** falliscono: **stop** — aggiornare i documenti **prima** di qualsiasi modifica al codice.

**Ulteriori precondizioni:**

- Approvazione utente su **freeze di perimetro**, **priorità P0–P2**, **minimum viable coverage**, **guardrail recovery**, **gate parità fresh vs upgrade**, **data preservation** (**#11**) e **integrità / oggetti secondari minimi** (**#12**), distinti dalla sola schema validation.
- Dove si sceglie **A:** approvazione esplicita anche sulla **nuova dipendenza** (coerente con **C6** — non duplicare richieste contraddittorie).

---

## File potenzialmente coinvolti (ispezione / Execution)

| File / percorso | Motivo |
|-----------------|--------|
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt` | Migrazioni, builder, `JournalMode` |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/Product.kt` | Solo se P0 impone allineamento entity/SQL *(motivazione obbligatoria)* |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntry.kt` | Riferimento nome tabella vs P1 |
| `app/src/main/java/com/example/merchandisecontrolsplitview/data/*.kt` (entities, DAO) | Solo lettura / incrocio schema, salvo eccezione freeze |
| `gradle/libs.versions.toml` | **Solo se opzione A approvata (C6):** dichiarazione **`room-testing`** nel catalog; **non** aggiungere senza approvazione dipendenza |
| `app/schemas/com.example.merchandisecontrolsplitview.data.AppDatabase/*.json` | Validazione Room; **solo** JSON **minimi** per P0/P1 (es. `5.json` se mancante per 5→6), vedi § anti-overengineering |
| `app/build.gradle.kts` | **Solo se A + C6:** `androidTestImplementation` `room-testing` via catalog; altrimenti solo quanto serve a **B** / build test |
| `app/src/androidTest/...` | Test migrazione (A o B), perimetro minimo 5→6 e 3→4 |
| `app/src/test/java/...` (suite TASK-004) | Solo se si tocca logica oltre migrazioni pure |

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Mappa percorsi:** tabella finale ogni salto **N→N+1** (v1→v6): migration, SQL principali, dipendenze | S | — |
| 2 | **Validazione schema post-migrazione:** evidenza (test o log) che DB migrato fino a **v6** apre **senza** errore di validazione Room | E / M | — |
| 3 | **Gate tecnico — parità fresh install vs upgrade:** evidenza (test automatizzato **preferito**, o procedura documentata equivalente) che lo **schema v6** risultante da **installazione pulita** (Room crea DB alla v6 attesa) e lo **schema v6** risultante da **migrazione** sul perimetro minimo pianificato (almeno **5→6** dopo fix P0; e coerenza con catena reale se si testa anche **3→4**) siano **coerenti** per: (a) **validazione Room** (`runMigrationsAndValidate` / stesso esito di apertura `AppDatabase` senza errore di schema); (b) **shape delle tabelle critiche** (`products`, `history_entries` se toccata da P1, `product_prices` / view se nel percorso) — colonne/indici rilevanti allineati tra i due percorsi. **Non** è sufficiente un giudizio implicito: va **esplicitato nel log Execution** come gate superato (comando test, nome metodo, o tabella comparativa DDL/assert). | E / S | — |
| 4 | **P1:** `MIGRATION_3_4` — nome tabella history risolto o accettato con **evidenza** (git/storico/fix SQL minimo); copertura minima **3→4** per § MVC | S / E / M | — |
| 5 | **P0:** `MIGRATION_5_6` vs `Product` / `6.json` — allineamento risolto o accettato con **evidenza**; copertura minima **5→6** per § MVC | S / E / M | — |
| 6 | **P2:** build OK; `JournalMode` risolto correttamente | B | — |
| 7 | **Test automatici (MVC):** almeno **un** test che copre **P0 (5→6)** e almeno **un** test che copre **P1 (3→4)** — ciascuno conforme a § *Assert sui dati*, **#11** e **#12**. **Salvo** motivazione scritta se un salto risulta impossibile da riprodurre (documentare limite e rischio residuo). Opzione **A** o **B** registrata. | E | — |
| 8 | **Recovery:** decisione finale **a)** o **b)** con criteri § Guardrail; **nessuna** migrazione distruttiva silenziosa | S | — |
| 9 | **Baseline TASK-004** solo se perimetro oltre migrazioni pure | E | — |
| 10 | **Build + lint** (`AGENTS.md`). **Se** è stata adottata **A** con approvazione **C6:** dipendenza `room-testing` tramite **Version Catalog** + `app/build.gradle.kts`. **Se** solo **B:** nessun `room-testing` — evidenza che non è stata aggiunta dipendenza non approvata | B / S | — |
| 11 | **Data preservation (MVC) — criterio separato dalla schema validation:** evidenza (test preferito) che sui path minimi: **(a) 5→6** — dopo `MIGRATION_5_6`, i valori delle colonne che devono sopravvivere alla ricostruzione di `products` restino corretti per almeno la riga fixture: `barcode`, `itemNumber`, `productName`, `secondProductName`, `purchasePrice`, `retailPrice`, `supplierId`, `categoryId`, `stockQuantity`; **(b) 3→4** — le righe storiche presenti pre-migrazione restino (conteggio/chiavi attese dal fixture) e la colonna **`category`** sia valorizzata col **default previsto** dalla migrazione (`''` se coerente col SQL attuale). **Non** si considera soddisfatto solo perché #2/#3 passano: servono assert sui **dati** come § *Assert sui dati*. | E | — |
| 12 | **Integrità strutturale post-MVC + oggetti secondari minimi:** sullo stesso DB usato per i test **5→6** e **3→4** (o equivalente documentato), evidenza di **`PRAGMA foreign_key_check`** senza violazioni; **`PRAGMA integrity_check`** con esito **`ok`** **se pratico**, altrimenti motivazione scritta per omissione. Dove rilevante: **indici critici** attesi dal path (es. indici `products` post-**5→6**); **view critiche** (es. **`product_price_summary`** post-**5→6**) — verifica **minima** di esistenza/usabilità come § *Integrità DB e oggetti secondari*, senza inventario globale schema. | E / S | — |

Legenda: B=Build, S=Static, M=Manuale, E=Test.

---

## Definition of Done verso REVIEW (gate esplicito)

**TASK-009 non può passare a `REVIEW` se manca anche solo uno dei seguenti:**

1. **Evidenza di validazione schema post-migrazione** (test `runMigrationsAndValidate` / assert equivalenti documentati, o smoke manuale **eccezionale** solo se concordato e comunque con evidenza scritta).
2. **Gate parità fresh install vs upgrade** (criterio accettazione **#3**) soddisfatto con evidenza **esplicita** nel log Execution — non solo implicito in altri test.
3. **Test automatici sul perimetro minimo:** almeno **5→6** e **3→4** (criterio **#7**), salvo deroga motivata lì prevista.
4. **Decisione documentata** su backup/recovery (**a** o **b**) e rispetto dei **guardrail** (nessun fallback distruttivo silenzioso).
5. **Tracking coerente:** file task aggiornato (Execution log, Decisioni) **e** `MASTER-PLAN.md` allineato allo stato reale del task prima della richiesta di review — come già verificato in precondizione **C2–C4** all’inizio dell’Execution, **ri-verificato** prima di `REVIEW`.
6. **Data preservation sul MVC:** evidenza che i path minimi **5→6** e **3→4** preservino i dati come da criterio **#11** (deliverable **D7**) — **salvo deroga motivata** con **rischio residuo** documentato (probabilità, impatto, perché il test non è stato possibile) e accettazione esplicita in review; **non** è sufficiente la sola evidenza di schema validation / parità DDL.
7. **Integrità SQLite + oggetti secondari minimi** (criterio **#12**, deliverable **D8**): `foreign_key_check` obbligatorio; `integrity_check` o motivazione; indici/view critici sul MVC ove applicabile — **salvo deroga motivata** con rischio residuo documentato.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task **`ACTIVE`** in `MASTER-PLAN`; fase file task **`EXECUTION`** (dal 2026-03-29) | Governance | 2026-03-29 |
| 2 | **Vietato** `fallbackToDestructiveMigration*` e wipe silenziosi | Safety dati utente | 2026-03-29 |
| 3 | **Opzione A** (`MigrationTestHelper` + `room-testing`): **preferita tecnicamente**, ma **condizionata** ad **approvazione esplicita** nuova dipendenza (regola «no nuove dipendenze senza richiesta»). Senza approvazione: **B** se copre **MVC**, altrimenti **BLOCKED** / richiesta sblocco **prima** di codice Gradle per A | Governance + pragmatismo | 2026-03-29 |
| 4 | **Minimum viable coverage:** default **solo** P0 **5→6** e P1 **3→4**; estensioni solo con evidenza reale | Efficienza | 2026-03-29 |
| 5 | **Anti-overengineering:** niente recupero schema/fixture oltre il minimo per P0/P1 salvo evidenza | Task snello | 2026-03-29 |
| 6 | Recovery: default **a)** export pre-update; **b)** solo se criteri § Guardrail soddisfatti | Pragmatismo senza scope creep | 2026-03-29 |
| 7 | Ordine Execution: **P0 → P1 → P2** | Riduzione rischio | 2026-03-29 |
| 8 | **UI:** task tecnico puro; micro-copy recovery solo se necessità reale e locale | Perimetro | 2026-03-29 |
| 9 | *(Execution)* Approvazione `room-testing` registrata (sì/no/N/A+B); scelta finale A / B / misto / `BLOCKED`; deroga test P0/P1 se motivata | — | — |
| 10 | **Data preservation ≠ schema validation:** su MVC servono assert sui **valori** (criterio **#11**), non solo DDL / `runMigrationsAndValidate` | Evitare falsi positivi | 2026-03-29 |
| 11 | **Review planning vs repo (2026-03-29):** incrocio codice completato; planning giudicato **sufficiente** per avviare `EXECUTION`; transizione fase su richiesta utente — **nessun** codice applicativo modificato nel turno di review | Chiusura PLANNING | 2026-03-29 |

---

## Planning (Claude)

### Analisi (codice ispezionato) — sintesi

- **`MIGRATION_3_4`:** `ALTER TABLE HistoryEntry` vs `@Entity(tableName = "history_entries")` → **P1** (nome tabella); su SQLite tipico Android spesso mitigato da case-insensitivity, ma va **provato** dal test MVC.
- **`MIGRATION_5_6`:** tabella `products` senza `old*` vs `Product.kt` e `6.json` con `old*` → **P0** (mismatch confermato nel codice/schema esportato).
- **Builder:** assenza di fallback distruttivo = positivo; da **mantenere**.
- **Test attuali:** nessun exercise della catena migratoria.

### Verifica incrociata codice repository (2026-03-29)

Controllo mirato **solo** file rilevanti TASK-009 (nessuna modifica applicativa in questo turno):

| Elemento | Esito |
|----------|--------|
| `AppDatabase.kt` | `version = 6`; `addMigrations(MIGRATION_1_2 … MIGRATION_5_6)`; **nessun** `fallbackToDestructiveMigration*` — coerente coi guardrail recovery. |
| `MIGRATION_5_6` | Ricrea `products` **senza** `oldPurchasePrice` / `oldRetailPrice`; ricrea indici `index_products_*`; `CREATE VIEW … product_price_summary` — allineato a `ProductPriceSummary.kt` (`viewName = "product_price_summary"`). |
| `Product.kt` | Include ancora `oldPurchasePrice` / `oldRetailPrice` — **conferma P0** (DB post-5→6 vs entity/`6.json`). |
| `6.json` | Include colonne `old*` in `products` — **conferma P0**. |
| `app/schemas/...` | **Solo** `6.json`; **nessun** `5.json` — coerente con piano MVC (recupero/generazione **5.json** se opzione **A**). |
| `JournalMode` (riga ~111) | Uso di `JournalMode.WRITE_AHEAD_LOGGING` **senza** import esplicito nel file — **P2:** in Execution verificare compilazione (`./gradlew assembleDebug`) e correggere con `RoomDatabase.JournalMode` o import se necessario. |
| `gradle/libs.versions.toml` | `room = "2.8.4"`; **nessuna** voce `room-testing` — coerente con governance **C6**. |
| `app/build.gradle.kts` | `room.schemaLocation` → `app/schemas`; **nessun** `room-testing` — coerente. |
| Test migrazione | **Nessun** `MigrationTestHelper` / classe dedicata trovata — vuoto atteso; da introdurre in Execution. |

**Conclusione review:** il planning è **coerente** col codice reale; i gap critici (**P0**/`old*`, **P1**/nome history, **P2**/build) erano già coperti; si aggiungono solo le evidenze tabellari sopra e la nota **fixture 3→4** in § *Assert sui dati*. **Nessun** accorciamento burocratico necessario oltre questo chiarimento.

### Piano di esecuzione (allineato a P0 → P1 → P2)

1. **P0 —** Analisi e fix **minimo** per coerenza `MIGRATION_5_6` / `products` / entity / `6.json` (solo nel perimetro freeze); evidenza con test o validazione.
2. **P1 —** Indagine storico nome tabella history + fix SQL **minimo** in `MIGRATION_3_4` se necessario; evidenza.
3. **P2 —** `assembleDebug`, `JournalMode`; **solo dopo C6 chiaro:** dipendenze test (**A** con approvazione `room-testing`, oppure **B** senza), wiring CI locale.
4. Implementare **test migrazione** sul **MVC**: **5→6** (P0) e **3→4** (P1); opzione **A** (catalog + `MigrationTestHelper`) o **B** con fixture **minima**; includere **sempre** assert **data preservation** § *Assert sui dati* (**#11**) e controlli leggeri § *Integrità DB e oggetti secondari* (**#12**); **estendere** altri path **solo** se emerge necessità reale in analisi.
5. **Gate parità:** dimostrare coerenza **fresh v6** vs **migrated v6** (criterio **#3**).
6. Finalizzare **decisione recovery** **a)** o **b)** con criteri documentati.
7. **Baseline TASK-004** e `test` completo **solo se** il freeze è stato superato con modifiche oltre `AppDatabase`/test migrazione.
8. Aggiornare **Deliverable previsti** nel log Execution; **ri-allineare** **MASTER-PLAN** prima di `REVIEW` (DoD §5); solo allora richiedere **REVIEW**.

### Rischi identificati (regressione / dati)

- Perdita dati / crash all’avvio (P0, P1).
- **Falso positivo:** migrazione che **apre** e passa schema check ma **corrompe o perde** valori in `products` o history — mitigato da criterio **#11** e § *Assert sui dati*.
- Fixture o JSON non rappresentativi (opzione B o A con schema parziale).
- Mitigazione: priorità rispettata, test automatici su dati, raccomandazione export, nessun wipe silenzioso.

### Verifiche previste (post-approvazione Execution)

| Verifica | Quando |
|----------|--------|
| `./gradlew assembleDebug` | Sempre |
| `./gradlew lint` | Sempre (perimetro modifiche) |
| `connectedAndroidTest` o task equivalente per modulo `androidTest` | Se test migrazione in androidTest |
| `./gradlew test` | Se toccata logica oltre migrazioni; altrimenti come da log Execution |
| **`PRAGMA foreign_key_check`** / **`integrity_check`** (e assert indici/view minimi) | Nei test MVC o log Execution — criterio **#12** / **D8** |
| Smoke manuale upgrade (opzionale) | Se utente/planner richiede evidenza M |

---

## Deliverable previsti (output concreti dell’Execution)

Al termine dell’Execution (prima di `REVIEW`), il log / le sezioni task devono contenere:

| # | Deliverable | Note |
|---|-------------|------|
| D1 | **Tabella finale** path migratori **v1→v6** verificati (N→N+1, SQL, dipendenze) | Può coincidere con criterio accettazione #1 |
| D2 | **Decisione finale** backup/recovery (**a** o **b**) con motivazione | Guardrail rispettati |
| D3 | **Test migrazione** (MVC: **5→6**, **3→4**), opzione A/B; **evidenza gate parità** fresh v6 vs migrated v6 (criterio **#3**) — **solo** shape/schema/validazione Room | Separato da **D7** |
| D4 | **Eventuale fix minimo** SQL / migrazione (diff sintetico) | Solo se necessario |
| D5 | Esito **build / lint / test** (comandi ed esito) | Evidenza obbligatoria |
| D6 | **Rischi residui** | Probabilità, impatto, mitigazione |
| D7 | **Evidenza di preservazione dati** sui path MVC (**5→6** colonne chiave `products`; **3→4** righe history + default `category`) — **distinta** da sola schema validation / **D3** | Nome test, valori attesi/effettivi o estratto query; deroga = rischio documentato |
| D8 | **Evidenza integrità post-MVC** — esito `foreign_key_check`; `integrity_check` o motivazione se omesso; nota minima su **indici/view critici** ove #12 | **Distinta** da **D3** / **D7**; deroga = rischio documentato |

---

## Execution

### Esecuzione — 2026-03-29

**Ambito turno:** **solo** review del planning contro la repo Android + integrazioni testuali nel file task + transizione **`PLANNING` → `EXECUTION`** + allineamento `MASTER-PLAN`. **Nessuna** modifica a sorgenti Kotlin, risorse o `app/build.gradle.kts` / `libs.versions.toml` (nessuna implementazione test migrazione, nessuna dipendenza `room-testing` aggiunta).

**File modificati (documentazione):**
- `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md` — § *Verifica incrociata codice repository*, nota fixture **3→4**, tabella *Informazioni generali* (`EXECUTION`), **Decisioni** #11, sezione **Execution** (questo log).
- `docs/MASTER-PLAN.md` — **TASK-009** in fase **`EXECUTION`** (coerenza globale).

**File ispezionati (solo lettura, codice):**
- `app/src/main/java/.../data/AppDatabase.kt`
- `app/src/main/java/.../data/Product.kt`
- `app/src/main/java/.../data/HistoryEntry.kt`
- `app/src/main/java/.../data/ProductPriceSummary.kt` (controllo nome view)
- `app/schemas/.../AppDatabase/6.json`
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- Ricerca test: nessun test migrazione preesistente.

**Azioni eseguite:**
1. Confronto migrazioni reali (`MIGRATION_3_4`, `MIGRATION_5_6`, …) con entity, `6.json`, strategia MVC, guardrail, A/B, **C6**.
2. Integrazione § *Verifica incrociata* e rafforzamento nota fixture **3→4** (allineamento a `ALTER TABLE HistoryEntry`).
3. Aggiornamento tracking: fase **`EXECUTION`**; `MASTER-PLAN` allineato.

**Check obbligatori (`AGENTS.md`) — turno documentazione / transizione fase:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ⚠️ **N/A** | Turno senza modifica codice; primo `./gradlew assembleDebug` obbligatorio quando si tocca `AppDatabase` / Gradle (P2). |
| Lint | ⚠️ **N/A** | Idem. |
| Warning Kotlin | ⚠️ **N/A** | Idem. |
| Coerenza con planning | ✅ | Review completata; evidenze in § *Verifica incrociata*. |
| Criteri di accettazione | — | Da soddisfare nel corso dell’`EXECUTION` (nessuno chiuso in questo turno). |

**Precondizioni C1–C6:** da rieseguire/validare **sul branch reale** immediatamente **prima del primo commit** che modifica codice applicativo o Gradle (questo turno non sostituisce la checklist operativa).

**Incertezze:** compilazione effettiva non eseguita in questo turno — **P2** (`JournalMode`) da chiudere con build in ambiente JDK locale.

**Handoff notes (esecutore):** partire da **P0**; ottenere **C6** prima di aggiungere `room-testing`; se solo **B**, coprire MVC con fixture/SQL; obiettivo **#11–#12** e deliverable **D7–D8**.

### Esecuzione — 2026-03-29 (avvio implementazione reale)

**Check iniziali verificati prima del codice (C1–C6):**
- **C1 Branch / working tree reale:** `main`; working tree con sole modifiche di tracking su `docs/MASTER-PLAN.md` e `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md`, da mantenere nello stesso change set di Execution.
- **C2 `MASTER-PLAN.md`:** verificato `TASK-009` unico `ACTIVE`, fase `EXECUTION`; nessun altro task `ACTIVE`.
- **C3 file task:** verificata coerenza tabella iniziale (`ACTIVE`) + fase `EXECUTION`.
- **C4 incrocio C2↔C3:** allineato; stesso task e stesso file referenziato.
- **C5 diff locale tracking:** coerente e già riallineato prima del codice; nessuno stato “a metà” tra `MASTER-PLAN` e file task.
- **C6 decisione dipendenza `room-testing`:** **N/A** per avvio Execution con **opzione B**; copertura MVC pianificata con test Robolectric + fixture SQL minime, senza nuova dipendenza Gradle.

**Nota operativa pre-patch:**
- Build/lint iniziali rieseguibili con JBR locale di Android Studio (`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`); l’ambiente shell di default senza `JAVA_HOME` esplicito non trova una JVM.

### Esecuzione — 2026-03-29 (implementazione P0 → P2)

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt` — fix minimi Room su `MIGRATION_4_5` e `MIGRATION_5_6`: ripristino rename `HistoryEntry -> history_entries`, riallineamento indici `product_prices`, preservazione colonne `old*` in `products`, SQL view condiviso con `@DatabaseView`, `JournalMode` qualificato.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductPriceSummary.kt` — estratta costante SQL condivisa `PRODUCT_PRICE_SUMMARY_QUERY` per eliminare mismatch fresh install vs upgrade sulla view `product_price_summary`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/AppDatabaseMigrationTest.kt` — nuovi test Robolectric opzione **B** con fixture SQL minime/mirate per coprire MVC **5→6** e **3→4** senza `room-testing`.
- `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md` — log Execution aggiornato con check iniziali, decisione A/B, evidenze tecniche e check finali.

**Decisione A/B:**
- **Scelta finale:** **B**.
- **Motivo:** il perimetro MVC richiesto (**5→6** e **3→4**) è coperto in modo pulito con test Robolectric + fixture SQL minime; non è stato necessario introdurre `androidx.room:room-testing`, quindi **nessuna** nuova dipendenza Gradle e **C6** resta **N/A**.

**Mappa percorsi verificata (#1):**
| Salto | Evidenza / SQL principale | Note |
|------|----------------------------|------|
| `1→2` | `MIGRATION_1_2` no-op | invariata |
| `2→3` | `MIGRATION_2_3` no-op | invariata |
| `3→4` | crea `categories`, indice `index_categories_name`, `ALTER TABLE products ADD COLUMN categoryId`, `ALTER TABLE HistoryEntry ADD COLUMN category TEXT NOT NULL DEFAULT ''` | confermato da git history e test MVC |
| `4→5` | rename condizionale `HistoryEntry -> history_entries`, creazione `product_prices`, indici Room-coerenti `index_product_prices_*` | fix minimo applicato in questo turno |
| `5→6` | ricostruzione `products` con preservazione `oldPurchasePrice` / `oldRetailPrice`, riallineamento indici `product_prices`, ricreazione `product_price_summary` con SQL identico a `@DatabaseView` | fix minimo applicato in questo turno |

**Azioni eseguite:**
1. **P0:** verificato mismatch reale `MIGRATION_5_6` vs `Product.kt` vs `6.json`; scelto il riallineamento verso entity/schema v6 già in uso dall’app, preservando `oldPurchasePrice` / `oldRetailPrice` nella ricostruzione di `products`.
2. **P1:** verificata via git history la tabella storica reale `HistoryEntry` a v3; individuato il bug effettivo nel rename perso della vecchia `MIGRATION_4_5`; ripristinato rename condizionale verso `history_entries`.
3. **P1/P2:** emerso dai test un ulteriore mismatch Room sui nomi indice di `product_prices`; riallineati i nomi indici alla forma attesa da Room e aggiunto repair in `5→6` per DB v5 esistenti.
4. **P0/P2:** emerso dai test un mismatch formale della view `product_price_summary`; eliminata la duplicazione SQL usando una costante condivisa tra `@DatabaseView` e migration.
5. Implementati test Robolectric opzione **B** con fixture sintetiche **minime**:
   - **5→6:** verifica apertura Room a v6, gate fresh install vs upgrade su `products`, preservazione dati (`barcode`, `itemNumber`, `productName`, `secondProductName`, `purchasePrice`, `retailPrice`, `old*`, `supplierId`, `categoryId`, `stockQuantity`), integrità SQLite, indici `products`, usabilità della view `product_price_summary`.
   - **3→4** (catena reale fino a v6): verifica aggiunta `category` con default `''`, preservazione riga history, rename finale verso `history_entries`, gate fresh install vs upgrade sulla shape della tabella, integrità SQLite.
6. Confermata strategia recovery **a)**: nessun backup automatico invasivo, nessun wipe/fallback distruttivo; raccomandazione invariata di export completo pre-update (**TASK-021**).

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** |
| Lint | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:lint` → **BUILD SUCCESSFUL**; solo warning preesistenti fuori scope (`LocaleUtils`, versioni dipendenze, manifest portrait, unused resources, POI jar), **nessun** warning sui file toccati |
| Warning nuovi | ✅ | Nessun warning Kotlin nuovo nei file modificati; restano solo warning/notice preesistenti di progetto/AGP fuori scope |
| Coerenza con planning | ✅ | Eseguiti in ordine **P0 → P1 → P2**; nessuna dipendenza nuova; nessun refactor architetturale/UI |
| Criteri di accettazione | ✅ | Tutti verificati sotto con evidenza |

**Test eseguiti:**
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.AppDatabaseMigrationTest'` → ✅
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest` → ✅

**Dettaglio criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ **ESEGUITO** | Mappa `1→2` / `2→3` / `3→4` / `4→5` / `5→6` documentata in questo log |
| 2 | ✅ **ESEGUITO** | Entrambi i test MVC aprono DB migrato fino a v6 senza errore di validazione Room |
| 3 | ✅ **ESEGUITO** | Confronto `columnShape(fresh)` vs `columnShape(migrated)` su `products` e `history_entries`; confronto indici `products`; view `product_price_summary` verificata usabile post-upgrade |
| 4 | ✅ **ESEGUITO** | Git history: `HistoryEntry` era il nome reale a v3; fix minimo nel rename perso di `MIGRATION_4_5`; test MVC history verde |
| 5 | ✅ **ESEGUITO** | `MIGRATION_5_6` riallineata a `Product.kt` / schema v6 preservando `old*`; test MVC 5→6 verde |
| 6 | ✅ **ESEGUITO** | `assembleDebug` verde; `JournalMode` qualificato con `RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING` |
| 7 | ✅ **ESEGUITO** | Nuovo `AppDatabaseMigrationTest` copre path minimi **5→6** e **3→4** (catena reale fino a v6) |
| 8 | ✅ **ESEGUITO** | Recovery confermata su opzione **a)**; nessun `fallbackToDestructiveMigration*` introdotto |
| 9 | ✅ **ESEGUITO** | Baseline **TASK-004** non applicabile: perimetro rimasto su `AppDatabase` / `ProductPriceSummary` / test migrazione |
| 10 | ✅ **ESEGUITO** | `lint` e `assembleDebug` verdi; nessun `room-testing` aggiunto |
| 11 | ✅ **ESEGUITO** | Assert espliciti su preservazione dati `products` (**5→6**) e riga history + default `category=''` (**3→4**) |
| 12 | ✅ **ESEGUITO** | `foreign_key_check` vuoto, `integrity_check = ok`, indici `products` verificati, view `product_price_summary` interrogabile |

**Baseline regressione TASK-004 (se applicabile):**
- **Non applicabile.**
- Perimetro rimasto su migrazioni Room / schema validation / test dedicati; nessuna modifica a repository, ViewModel, import/export logic o suite TASK-004.

**Incertezze:**
- Nessuna sul perimetro tecnico toccato.
- Restano warning lint **preesistenti** di progetto fuori scope; non introdotti da TASK-009.

**Handoff notes:**
- Implementazione tecnica e check richiesti completati nel perimetro di TASK-009.
- Stato tracking **non** spostato qui fuori da `EXECUTION` perché il file `MASTER-PLAN.md` era già coerente all’avvio e l’esecutore non modifica lo stato globale oltre il riallineamento richiesto pre-codice.
- Il task è tecnicamente pronto per il passaggio successivo di review del planner senza ulteriori fix nel branch corrente.

### Esecuzione — 2026-03-29 (fix crash identity hash Room su DB esistente)

**Check iniziali rieseguiti prima del fix:**
- **C1 Branch / working tree reale:** verificato sul branch reale con working tree coerente al change set di `TASK-009`.
- **C2 `MASTER-PLAN.md`:** confermato `TASK-009` unico `ACTIVE`, fase globale ancora `EXECUTION`.
- **C3 file task:** confermato tracking coerente e nessun task concorrente attivo.
- **C4 incrocio C2↔C3:** allineato; nessun disallineamento governance da sanare prima del codice.
- **C5 diff locale tracking:** coerente; nessuno stato “a metà”.
- **C6 `room-testing`:** confermata scelta **B**; nessuna nuova dipendenza introdotta.

**Diagnosi del crash verificata sul codice reale:**
- Il crash manuale è coerente con un **mismatch di Room identity hash**, non con un problema locale/transitorio.
- Evidenza concreta:
  1. lo schema Room esportato e già presente per il **vecchio v6** è `app/schemas/.../6.json` con `identityHash = c52a22bb706c042a91802612b02570a4`;
  2. il codice compilato dopo le modifiche TASK-009 generava `AppDatabase_Impl` con hash atteso diverso (`470cd31ab22e597e1b3c946e54b77c48`) pur lasciando `AppDatabase` a **`version = 6`**;
  3. un utente con DB v6 precedente avrebbe quindi aperto un **DB versione 6** contro un **nuovo schema Room ancora versione 6**, causando l’errore: *“Room cannot verify the data integrity … Expected identity hash != found identity hash”*.
- Conclusione: il fix corretto è **version bump `6 → 7` + `MIGRATION_6_7`**, non wipe dati e non fallback distruttivo.

**File modificati in questo fix:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt` — bump `version = 7`, nuova `MIGRATION_6_7`, builder aggiornato, helper SQL minimi per riallineare schema/indici/view senza fallback distruttivo.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/AppDatabaseMigrationTest.kt` — estesi i test per coprire anche upgrade da **vecchio DB v6** con identity hash rilasciato; i test chain `5→…` e `3→…` ora verificano l’apertura al **v7** finale.
- `app/schemas/com.example.merchandisecontrolsplitview.data.AppDatabase/7.json` — export schema Room del nuovo **v7** (`identityHash = 470cd31ab22e597e1b3c946e54b77c48`).
- `docs/TASKS/TASK-009-migrazione-database-safety-e-recovery.md` — log Execution aggiornato con diagnosi crash, fix e check.

**Azioni eseguite:**
1. Verificato operativamente il mismatch confrontando hash del **v6 rilasciato** (`6.json`) con l’hash atteso dal codice compilato dopo le modifiche TASK-009.
2. Applicato il fix minimo corretto:
   - `AppDatabase.version` aumentata da **6** a **7**;
   - aggiunta `MIGRATION_6_7`;
   - `addMigrations(...)` aggiornato includendo `MIGRATION_6_7`.
3. Implementata `MIGRATION_6_7` con scope stretto:
   - rename difensivo `HistoryEntry -> history_entries` se necessario;
   - ricostruzione `products` verso lo schema Room corrente con preservazione dati e compatibilità anche con shape v6 legacy;
   - riallineamento indici `product_prices`;
   - ricreazione della view `product_price_summary` con la SQL Room corrente.
4. Estesi i test MVC:
   - upgrade da **v6 precedente** con `room_master_table.identity_hash = c52a22bb706c042a91802612b02570a4`;
   - apertura corretta post-upgrade a **v7**;
   - schema validation / gate fresh install vs upgrade;
   - data preservation;
   - `foreign_key_check`, `integrity_check`, indici/view critici.
5. Rigenerato `7.json` per rendere tracciabile lo schema Room realmente voluto.

**Decisione A/B:**
- **Confermata: B**
- **Motivo:** la copertura MVC richiesta resta completa con test Robolectric + fixture SQL minime; il nuovo rischio `6→7` è coperto senza introdurre `room-testing`.

**Check obbligatori:**
| Check | Stato | Note |
|-------|-------|------|
| Build Gradle | ✅ | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint` → **BUILD SUCCESSFUL** |
| Lint | ✅ | Nessun warning sui file toccati (`rg` su `lint-results-debug.*` senza match per `AppDatabase.kt`, `ProductPriceSummary.kt`, `AppDatabaseMigrationTest.kt`); restano solo warning preesistenti di progetto fuori scope |
| Warning nuovi | ✅ | Nessun warning Kotlin/Room nuovo introdotto nel perimetro toccato |
| Coerenza con planning | ✅ | Fix minimo strettamente in `TASK-009`; nessuna dipendenza nuova, nessun fallback distruttivo, nessun refactor/UI |
| Criteri di accettazione | ✅ | Evidenze già verdi nella precedente implementazione + nuova copertura esplicita su upgrade da vecchio v6 a v7 |

**Test eseguiti:**
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.data.AppDatabaseMigrationTest'` → ✅
- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint` → ✅

**Evidenza specifica nuovo fix `6→7`:**
- Test dedicato: `migration 6 to 7 upgrades released v6 schema without identity hash crash` → ✅
- Copertura del test:
  - crea un **DB v6 precedente** con schema Room rilasciato e `room_master_table.identity_hash = c52a22bb706c042a91802612b02570a4`;
  - esegue upgrade reale tramite `MIGRATION_6_7`;
  - verifica apertura a **`PRAGMA user_version = 7`**;
  - verifica parità con fresh install su shape `products`, indici `product_prices`, SQL della view `product_price_summary`;
  - verifica preservazione dati fixture e integrità SQLite minima.

**Baseline regressione TASK-004 (se applicabile):**
- **Non applicabile.**
- Il fix è rimasto confinato a Room migration/schema tests.

**Incertezze:**
- Nessuna sul perimetro del fix.

**Handoff notes:**
- `MASTER-PLAN.md` non richiede aggiornamenti aggiuntivi in questo turno: task attivo e fase globale erano già coerenti; il fix resta interamente dentro `TASK-009`.
- Stato task mantenuto in **`EXECUTION`** per governance, ma il branch è nuovamente pronto per review tecnica.

---

## Review

### Review — 2026-03-29

**Revisore:** Claude (planner)

**Metodo:** review tecnico completo sul codice reale del repository + riesecuzione check indipendente.

**File ispezionati:**
- `AppDatabase.kt` — version=7, MIGRATION_6_7, catena completa 1→7, nessun fallbackToDestructiveMigration
- `Product.kt` — entity coerente con 7.json
- `ProductPrice.kt` — entity coerente con 7.json
- `ProductPriceSummary.kt` — view query condivisa, coerente con 7.json
- `HistoryEntry.kt` — tableName="history_entries", coerente con 7.json
- `6.json` — identityHash `c52a22bb706c042a91802612b02570a4` (rilasciato)
- `7.json` — identityHash `470cd31ab22e597e1b3c946e54b77c48` (corrente)
- `AppDatabaseMigrationTest.kt` — 3 test Robolectric opzione B

**Check rieseguiti indipendentemente dal planner:**

| Check | Esito |
|-------|-------|
| `testDebugUnitTest --tests AppDatabaseMigrationTest` | ✅ 3/3 PASSED, 0 failures, 0 errors |
| `assembleDebug` | ✅ BUILD SUCCESSFUL |
| `lintDebug` | ✅ BUILD SUCCESSFUL |

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Mappa percorsi v1→v7 | ✅ | Documentata nel log Execution; catena 1→2→3→4→5→6→7 completa |
| 2 | Validazione schema post-migrazione | ✅ | 3 test aprono DB migrato a v7 senza errore Room |
| 3 | Gate parità fresh vs upgrade | ✅ | Ogni test confronta `columnShape`, `indexInfo`, `viewSql` tra fresh e migrated |
| 4 | P1: MIGRATION_3_4 nome tabella history | ✅ | Test v3→…→7 verifica rename `HistoryEntry` → `history_entries` |
| 5 | P0: MIGRATION_5_6 vs Product / schema | ✅ | Preservazione `old*` verificata; test v5→…→7 verde |
| 6 | P2: build OK, JournalMode | ✅ | `assembleDebug` verde; `RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING` |
| 7 | Test automatici MVC | ✅ | 3 test: 5→…→7, 3→…→7, 6→7 con data preservation e integrità |
| 8 | Recovery: decisione a/b | ✅ | Opzione a) confermata; nessun fallback distruttivo |
| 9 | Baseline TASK-004 | ✅ | N/A: perimetro rimasto su migrazioni pure |
| 10 | Build + lint + nessun room-testing | ✅ | Opzione B; nessuna dipendenza aggiunta |
| 11 | Data preservation MVC | ✅ | Assert campo-per-campo su products (5→7) e history (3→7) |
| 12 | Integrità + oggetti secondari | ✅ | `foreign_key_check` vuoto, `integrity_check = ok`, indici/view verificati |

**Problemi trovati:**
- Nessuno.

**Verdetto:** **APPROVED**

**Note:**
- Il bump v6→v7 è necessario e corretto: l'identity hash Room è cambiato dopo i fix delle migrazioni precedenti.
- MIGRATION_6_7 è idempotente e difensiva: riallinea lo schema anche se il DB v6 era già corretto.
- I test coprono tutti i path critici con evidenza sui dati, non solo sulla struttura.
- Strategia B (Robolectric senza room-testing) è adeguata e ben implementata.
- Nessun rischio residuo bloccante.

---

## Fix

*(Vuoto)*

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **DONE** |
| Data chiusura          | 2026-03-29 |
| Tutti i criteri ✅?    | ✅ Sì (12/12) |
| Rischi residui         | Minori — vedi Riepilogo finale |

---

## Riepilogo finale

### Risultato
Migrazioni Room v1→v7 sicure, testate e coerenti. Il crash identity hash mismatch su DB v6 esistenti è risolto con bump a v7 + MIGRATION_6_7 idempotente. Strategia test B (Robolectric) con copertura completa MVC. Recovery: raccomandazione export pre-update (opzione a), nessun fallback distruttivo.

### Rischi residui

| Rischio | Probabilità | Impatto | Mitigazione |
|---------|-------------|---------|-------------|
| Schema JSON mancanti per v1–v5 | Bassa | Basso | Non necessari: migrazioni 1→2, 2→3 sono no-op; 3→4 e 4→5 testate via catena; fixture SQL rappresentative |
| Smoke manuale su device fisico non eseguito | Media | Basso | Test Robolectric coprono la logica; assembleDebug verde; raccomandazione smoke al prossimo rilascio |
| Migrazioni future (v7→v8+) | N/A | N/A | Pattern e test esistenti come riferimento; helper SQL riutilizzabili |

---

## Handoff

- **Stato:** implementazione Room/test completata nel perimetro; file task resta documentato in **`EXECUTION`** fino al passaggio successivo di review tracking.
- **Opzione test:** **B** confermata; nessuna dipendenza `room-testing`.
- **Fix chiusi:** preservazione `old*` in `5→6`, rename `HistoryEntry -> history_entries`, riallineamento indici `product_prices`, SQL condiviso della view `product_price_summary`, `JournalMode` qualificato.
- **Check verdi:** `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lint`.
- **Recovery:** confermata opzione **a)** (export completo pre-update), nessun fallback distruttivo.
