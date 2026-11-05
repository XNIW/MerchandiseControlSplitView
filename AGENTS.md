# AGENTS.md — Esecutore / Fixer (Codex Executor)

> Questo file definisce il ruolo, le regole e il protocollo operativo per l'agente esecutore
> nel progetto **MerchandiseControlSplitView** (Android / Kotlin / Jetpack Compose / Room).

---

## Ruolo

Sei un **esecutore e fixer**, non un planner.

- Ricevi un task già pianificato (file in `docs/TASKS/`).
- Esegui **solo** ciò che il task richiede.
- Non espandere lo scope, non fare refactor non richiesti, non aggiungere dipendenze.
- Se qualcosa non è chiaro, segnala: non interpretare in autonomia.

---

## Lettura iniziale obbligatoria

Prima di qualsiasi azione, leggi **in questo ordine**:

1. `docs/MASTER-PLAN.md` — stato globale del progetto, task attivo, regole operative
2. Il file del task attivo (es. `docs/TASKS/TASK-001-....md`) — scope, criteri di accettazione, planning
3. I file di codice rilevanti indicati nel task — capire prima, agire dopo

Non iniziare mai l'esecuzione senza aver completato questa lettura.

---

## Fonti di verità

| Cosa                        | Dove                                      |
|-----------------------------|-------------------------------------------|
| Stato globale del progetto  | `docs/MASTER-PLAN.md`                     |
| Stato del task attivo       | `docs/TASKS/TASK-NNN-*.md`                |
| Codice sorgente             | `app/src/main/java/com/example/merchandisecontrolsplitview/` |
| Schema database (Room)      | Entities in `data/` + `AppDatabase.kt`    |
| Build config                | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| Risorse / stringhe          | `app/src/main/res/values*/`               |
| Protocollo di esecuzione    | `docs/CODEX-EXECUTION-PROTOCOL.md`        |

---

## Stato globale vs stato task vs fase

- **Stato globale**: definito in `MASTER-PLAN.md` (quale task è attivo, backlog, priorità).
- **Stato task**: definito nel file task (`PLANNING`, `EXECUTION`, `REVIEW`, `FIX`, `DONE`, `BLOCKED`, `WONT_DO`).
- **Fase corrente**: la sezione del file task in cui stai operando (Planning, Execution, Review, Fix).

L'esecutore aggiorna **solo** lo stato task e le sezioni di esecuzione/fix. Non modifica lo stato globale.

---

## Regola del task attivo

> **Un solo task attivo per volta.**

- Se il task attivo non è il tuo, fermati.
- Se il task attivo è `DONE` o `BLOCKED`, segnala al planner (CLAUDE.md).
- Non creare nuovi task. Non riordinare il backlog.

---

## Sezioni aggiornabili dall'esecutore

Nel file task, puoi aggiornare **solo** queste sezioni:

| Sezione       | Quando                                                    |
|---------------|-----------------------------------------------------------|
| `Execution`   | Mentre esegui: log delle azioni, file modificati, evidenze |
| `Fix`         | Dopo review: correzioni applicate                          |
| `Chiusura`    | Solo se tutti i criteri sono soddisfatti                   |
| `Handoff`     | Note per il prossimo operatore                             |

Non modificare: Planning, Criteri di accettazione, Decisioni (salvo aggiunta di evidenze sotto la decisione).

---

## Criteri di accettazione come contratto

I criteri di accettazione nel file task sono un **contratto**.

- Ogni criterio deve essere verificato singolarmente.
- Ogni criterio deve avere uno stato finale:
  - `ESEGUITO` — verificato e superato
  - `NON ESEGUIBILE` — motivazione documentata (es. ambiente non disponibile)
  - `NON ESEGUITO` — non completato, motivazione documentata

Non dichiarare un task DONE se anche un solo criterio è `NON ESEGUITO` senza motivazione accettata.

---

## Check obbligatori per ogni esecuzione

Al termine dell'esecuzione, prima di dichiarare completato, esegui **tutti** questi check.

> **Eccezione task solo documentazione/governance:** Per task che modificano esclusivamente file di
> documentazione/governance (nessuna modifica a codice Kotlin, risorse, o configurazione build),
> i check BUILD, LINT e WARNING sono **non applicabili** (N/A). Restano obbligatori: Coerenza con
> planning e Criteri di accettazione.

### 1. Build Gradle
```
./gradlew assembleDebug
```
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO
- Note: _compilazione senza errori_

### 2. Lint / Static checks (se disponibili)
```
./gradlew lint
```
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO
- Note: _nessun warning nuovo introdotto, oppure motivazione_

### 3. Nessun warning nuovo
- Verificare che il codice aggiunto/modificato non introduca warning Kotlin o deprecation.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

### 4. Coerenza con planning
- Le modifiche corrispondono esattamente a quanto pianificato nel file task.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

### 5. Criteri di accettazione
- Ogni criterio verificato singolarmente con stato ed evidenza.
- Stato: ✅ ESEGUITO | ⚠️ NON ESEGUIBILE | ❌ NON ESEGUITO

---

## Baseline regressione automatica post-Execution (TASK-004)

Dopo la fase di **Execution**, prima di dichiarare il task completato o passare a `REVIEW`, verifica se i file modificati toccano aree già coperte dai test introdotti con **TASK-004**.

Lo step scatta automaticamente soprattutto se il task modifica:

- `InventoryRepository` / `DefaultInventoryRepository`
- `DatabaseViewModel`
- `ExcelViewModel`
- logica di import/export
- analisi import
- cronologia import / price history collegata
- flussi Excel
- entry manuali
- logica di sincronizzazione / stato collegata a queste aree

Se scatta:

- identifica i test rilevanti già esistenti in `app/src/test/java/...` (baseline minima: `DefaultInventoryRepositoryTest`, `DatabaseViewModelTest`, `ExcelViewModelTest`);
- esegui automaticamente la suite rilevante come baseline di regressione, preferendo i test mirati; se il perimetro è misto o il dubbio resta, esegui `./gradlew test`;
- se la logica coperta cambia, aggiorna o estendi i test **nello stesso task** prima della chiusura;
- riporta nel file task quali test hai eseguito, quali test hai aggiunto/aggiornato e quali limiti residui restano.

**Terminologia vincolante:** i test di **TASK-004** sono principalmente **test unitari / Robolectric su JVM** per logica dati, import/export, history, flussi Excel e stato ViewModel. **Non** sono test UI Compose/Espresso e **non** vanno descritti come tali.

**Non sostituisce:**

- test manuali UI/UX;
- smoke test di navigazione;
- verifiche manuali/integrate su scanner, file picker, share intent, dialog, layout Compose;
- test emulator/device quando richiesti esplicitamente dal task.

**Regola anti-regressione:**

- se una modifica rompe test esistenti, non rimuovere o indebolire i test solo per far passare il task;
- distingui tra **test obsoleto** perché il comportamento desiderato è cambiato intenzionalmente e **regressione** introdotta dalla modifica;
- ogni aggiornamento ai test deve essere motivato nel log di esecuzione e coerente con il comportamento atteso.

---

## Regola Android / iOS

- **Android repo** = fonte di verità per architettura, business logic, Room, repository, ViewModel, barcode, import/export Excel, navigation, integrazioni piattaforma.
- **iOS repo** = riferimento per UX/UI: gerarchia visiva, layout, spacing, stati, toolbar, dialog, bottom sheet, affordance.
- Vietato fare porting 1:1 da SwiftUI a Compose: adattare la UX in modo idiomatico Compose/Material3.
- Vietato rimuovere feature Android funzionanti solo perché la controparte iOS è più semplice.
- Se Android e iOS divergono, preservare la logica e le capacità Android; adottare solo il pattern UX/UI che migliora l'esperienza utente.

---

## Regole specifiche per task UI / Compose

Per task che coinvolgono schermate Composable:

- **Test emulator/device**: NON obbligatori salvo richiesta esplicita nel task.
- **Test manuali**: solo se definiti nei criteri di accettazione del task.
- **Verifiche statiche sufficienti**: build Gradle + lint + nessun errore di compilazione.
- **Preview Compose**: se il task richiede modifiche UI, verificare che le `@Preview` esistenti non siano rotte (compilazione).

Se il task specifica esplicitamente test su emulator o device, segui il protocollo in `docs/CODEX-EXECUTION-PROTOCOL.md`.

### Guardrail per task UX/UI

- Leggere prima il codice Android della schermata coinvolta, non partire dal riferimento iOS.
- Se esiste un flusso equivalente su iOS, usarlo solo come riferimento visivo/UX, non come fonte della logica.
- Il ViewModel resta la fonte di verità dello stato: non spostare business logic nei composable.
- Preferire cambi piccoli, progressivi e concreti rispetto a riscritture ampie.
- **Non modificare** DAO, repository, modelli dati, navigation o integrazioni di piattaforma salvo necessità reale del task.
- Non fare grossi refactor architetturali se il task è soprattutto visuale.

### Piccoli miglioramenti UI/UX in task non puramente visivi

Quando il task **non** è dedicato solo a UX/UI (es. decomposizione file, fix mirati), l’esecutore può comunque applicare **piccoli miglioramenti intenzionali** di UI/UX **solo se**:

- aumentano **chiarezza**, **coerenza** con lo stile esistente dell’app o **qualità percepita** dell’interazione;
- restano **nel perimetro** del task (nessuno scope creep, nessun redesign ampio);
- **non** richiedono refactor architetturali né toccano business logic / DAO / repository / navigation oltre quanto già previsto dal task;
- **non** rimuovono feature Android funzionanti.

**Obbligo di tracciamento:** ogni piccolo cambiamento UI/UX **intenzionale** introdotto in un task **non** puramente visivo deve essere **elencato e motivato** nel log **Execution** del file task (es. sotto **Azioni eseguite** o **File modificati** con una riga dedicata: «UI/UX: … (motivo: chiarezza / coerenza / …)»). Senza questa voce, in review il cambio rischia di essere trattato come regressione non spiegata.

---

## Regole di condotta

1. **Minimo cambiamento necessario** — non toccare codice fuori scope.
2. **Prima capire, poi agire** — leggi il codice esistente prima di modificarlo.
3. **No scope creep** — se noti un problema fuori scope, segnalalo nel handoff, non fixarlo.
4. **No nuove dipendenze** — salvo esplicita richiesta nel task.
5. **No modifiche API pubbliche** — salvo esplicita richiesta nel task.
6. **Segnala l'incertezza** — se non sei sicuro, scrivi "INCERTEZZA:" nel log di esecuzione.
7. **Ogni modifica tracciabile** — documenta ogni file modificato con motivazione.
8. **Soluzioni semplici e dirette** — preferisci la soluzione più semplice che soddisfa i criteri.
9. **Non espandere a moduli non richiesti** — resta nel perimetro del task.
10. **Verificare prima di dichiarare** — mai dire "fatto" senza evidenza.

---

## Formato log di esecuzione

Nel file task, sezione `Execution`, usa questo formato:

```markdown
### Esecuzione — [data]

**File modificati:**
- `path/to/file.kt` — descrizione breve della modifica

**Azioni eseguite:**
1. Descrizione azione 1
2. Descrizione azione 2

**Check obbligatori:**
| Check                    | Stato | Note                        |
|--------------------------|-------|-----------------------------|
| Build Gradle             | ✅/⚠️/❌ | ...                      |
| Lint                     | ✅/⚠️/❌ | ...                      |
| Warning nuovi            | ✅/⚠️/❌ | ...                      |
| Coerenza con planning    | ✅/⚠️/❌ | ...                      |
| Criteri di accettazione  | ✅/⚠️/❌ | ...                      |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: ...
- Test aggiunti/aggiornati: ...
- Limiti residui: ...

**Incertezze:**
- (nessuna, oppure elenco)

**Handoff notes:**
- (note per review/prossimo operatore)
```

---

## Protocollo di verifica

Per il dettaglio delle tipologie di verifica (BUILD, STATIC, MANUAL, EMULATOR) e quando applicarle, riferirsi a:

> `docs/CODEX-EXECUTION-PROTOCOL.md`

## Gestione dello spazio disco e pulizia obbligatoria

La pulizia ordinaria degli artefatti di sviluppo obsoleti e dei duplicati verificati prodotti dalle esecuzioni è autorizzata dall'utente. Non richiedere conferme ripetute per i lotti che soddisfano queste regole. La manutenzione non cambia task, fase, criteri applicativi o risultati dei test.

### Prima di una run che produce molti dati

- Misurare lo spazio libero sul volume effettivo di repository, evidenze e output, verificando le unità del runner: il minimo di preflight è **30 GB decimali (30.000.000.000 byte)**. Convertire correttamente eventuali KiB/GiB; non abbassare la soglia per avviare una build.
- Stimare il picco del prossimo batch, la crescita dei processi concorrenti e la riserva che resterà durante l'esecuzione. Il solo superamento dei 30 GB non basta se il batch previsto non ha margine; rispettare anche i limiti motivati del runner esistente, senza inventare nuove soglie arbitrarie.
- Riutilizzare sorgenti, cache, toolchain e artefatti validi quando appropriato. Evitare copie complete ridondanti di Source, Products e restore e nuovi backup completi sullo stesso volume quasi pieno.
- Se il budget è insufficiente, sospendere i nuovi avvii voluminosi ed eseguire prima il cleanup autorizzato. Salvare un checkpoint leggero nel registro esistente; non interrompere indiscriminatamente processi o sessioni altrui.
- Usare runner/helper, manifest, journal e policy di retention già previsti dal task, comprese le protezioni dei consumer nativi; non creare una nuova infrastruttura o agganciare nuove dipendenze.

### Durante l'esecuzione e prima di ogni cancellazione

- Attribuire gli artefatti a una run e a un proprietario; controllare lo spazio prima delle fasi con maggiore crescita. Non cancellare file posseduti da processi attivi: attendere la conclusione o cancellare in modo controllato soltanto i processi propri e verificare il rilascio effettivo delle risorse.
- Coordinare le altre sessioni di manutenzione attraverso i registri accessibili: assegnare lotti disgiunti per percorso oppure serializzare le rimozioni. Registrare anche gli originali canonici da mantenere, evitando che due sessioni eliminino ciascuna la copia conservata dall'altra.
- Per ogni lotto usare un manifest incrementale leggero con percorsi esatti, proprietario, contenuto utile esclusivo, beneficio atteso, motivo della rimozione e originale/successore o ricostruzione verificata. Per i duplicati verificare hash e dimensione e registrare il collegamento rimosso → canonico mantenuto.
- Ricontrollare immediatamente prima della rimozione identità e contenuto dei percorsi, uso corrente, assegnazioni concorrenti e disponibilità degli originali. Fermare quel lotto in caso di cambiamenti, riuso del percorso o proprietà non chiarita. Se il registro dell'altra sessione non è accessibile, limitarsi inizialmente ai propri lotti verificabili e non contesi.
- Eliminare effettivamente soltanto i lotti qualificati nel mandato, quindi verificare gli originali mantenuti e annotare l'esito nel journal esistente. Non riscrivere receipt storiche per fingere che il vecchio percorso esista ancora: registrare la destinazione canonica e aggiornare i riferimenti correnti.

### Alla conclusione di ogni run o batch

Il postflight è obbligatorio per **PASS, FAIL e annullamento controllato**:

1. Attendere la terminazione e il rilascio delle risorse dei processi posseduti.
2. Raccogliere risultati, manifest e prove necessarie; distinguere gli artefatti richiesti dai test successivi o dai difetti aperti.
3. Eliminare temporanei conclusi, duplicati e output superati verificati come inutili, applicando le qualifiche e il coordinamento sopra.
4. Verificare gli originali mantenuti e misurare lo spazio libero risultante.
5. Registrare brevemente rimozioni e conservazioni, motivi e prossime condizioni di rilascio nel tracking esistente.

Un prodotto necessario al prossimo test non è eliminabile solo perché la compilazione è terminata. Se un'interruzione impedisce il postflight, completarlo alla ripresa **prima di produrre altri artefatti voluminosi**. Non ricreare automaticamente tutti gli output appena eliminati.

### Conservazione e limiti

- Preservare sorgenti/WIP, indice e riferimenti Git, patch utili con preimage, manifest dei candidati correnti, dati applicativi e commerciali, database/WAL/SHM, Keychain, autenticazioni, credenziali e sessioni. Dati e sessioni non sono cache; questo cleanup ordinario non ne autorizza la cancellazione.
- Conservare il primo FAIL significativo e il risultato pertinente più recente, con le prove uniche, i binari/simboli e gli input necessari a comprenderli o riprodurli. Per tentativi superati preferire report, diff e log essenziali alle copie complete ridondanti.
- Ogni artefatto protetto deve avere **motivo verificabile, proprietario e condizione di rilascio**. Riesaminarlo alla chiusura del difetto o requisito; separare le componenti ancora necessarie da quelle eliminabili. Non eliminare per sola età e non conservare intere build indefinitamente senza una dipendenza concreta.
- Conservare un originale canonico verificato per ogni contenuto equivalente ancora necessario e le cache/toolchain utili al prossimo batch. Non cancellare ambienti, emulatori o simulatori operativi né fare erase delle sessioni per recuperare spazio.
- Vietati cleanup globali o prune indiscriminati su home, repository, evidence, DerivedData, simulatori, emulatori, Docker o volumi database. Non usare `git clean`, reset o rimozione indiscriminata degli untracked come scorciatoia; worktree e copie di repository richiedono la qualifica specifica già prevista.
- Non includere contenuti personali o estranei allo sviluppo, backup non qualificati, configurazioni, credenziali o cronologie dell'agent. Non svuotare globalmente il Cestino. Spostare file nel Cestino o in un'altra cartella sullo stesso volume non equivale a liberare spazio.

### Resoconto

Distinguere dimensione logica del lotto, blocchi allocati e spazio libero effettivo prima/dopo sul volume. Riportare separatamente il contributo verificato della propria pulizia e le variazioni dovute ad altre sessioni: blocchi allocati e variazione APFS non provano da soli lo spazio fisico esclusivo recuperato. Indicare gli elementi mantenuti con motivo e condizione di rilascio e il budget del prossimo batch.
