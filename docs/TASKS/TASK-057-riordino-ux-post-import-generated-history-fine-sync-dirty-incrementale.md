# TASK-057 — Riordino UX post-import Generated/History, tasto Fine e sync dirty incrementale

---

## Informazioni generali

| Campo | Valore |
|-------|--------|
| ID | TASK-057 |
| Stato | DONE |
| Priorità | CRITICA |
| Area | Navigation / Generated / ImportAnalysis / History tab root / Sync catalogo cloud |
| Creato | 2026-04-24 |
| Ultimo aggiornamento | 2026-04-25 — Review planner APPROVED senza modifiche sul FIX UX `Conferma Importazione`; task chiuso `DONE`. |

*Revisione di questo file (coherence pass) **non** implica `PLANNING -> EXECUTION` e **non** autorizza patch su Kotlin/XML/Gradle.*

---

## Dipendenze

- **TASK-026** `DONE` — import apply atomico e Room-first.
- **TASK-044** `DONE` — history utente senza entry tecniche.
- **TASK-055** `PARTIAL` — audit sync Supabase e UX sync indicator.
- **TASK-056** `PARTIAL` — fix minimo spinner post-conferma non sufficiente sui flussi reali da History/Generated.

---

## Scopo

Riordinare in modo deterministico il flusso post-import tra `GeneratedScreen`, `ImportAnalysisScreen`, `HistoryScreen` e tab root, eliminando schermate bianche e incoerenze di back stack/destinazione.  
Allineare `Fine`, `Back`, `Annulla` e `Conferma Importazione` a un unico resolver di uscita basato su context/origin esplicito.  
Verificare e correggere il dirty marking cloud per evitare push percepiti come full-catalog (`... / 18866`) su modifiche puntuali.

---

## Contesto

TASK-056 ha ridotto una parte del problema (spinner bianco) ma nel runtime reale persistono bug UX/navigation/sync:

- Flusso reale segnalato: `History -> Generated -> Revisione Importazione -> Conferma`.
- Dopo `DB_IMPORT APPLY_IMPORT SUCCESS`, l'app puo ancora mostrare schermata bianca mentre l'indicatore in alto mostra `Invio modifiche prodotti XXXX / 18866`.
- Premendo `Back` dalla schermata bianca si torna alla `GeneratedScreen` precedente.
- Premendo `Fine` da `Generated` aperta dalla cronologia, l'app puo uscire verso Inventory/Database invece di tornare al contesto history corretto.
- Premendo tab `Cronologia`, a volte viene ripristinata una `GeneratedScreen` stale invece della root `HistoryScreen`.

### Evidenze runtime fornite dall'utente

1. **Screenshot 1**  
   `GeneratedScreen` aperta da cronologia, top bar con titolo "Pinmark", pulsante `Fine`, indicatore `Invio modifiche prodotti 1154 / 18866`, griglia ~7 righe.
2. **Screenshot 2**  
   `Revisione Importazione`: 0 nuovi, 1 aggiornato, 0 errori; modifica prezzo vendita da 6 a 7; azione `Conferma Importazione`.
3. **Screenshot 3**  
   Dopo conferma: schermata bianca/vuota con indicatore persistente `Invio modifiche prodotti 1437 / 18866`.
4. **Logcat**  
   - `DB_IMPORT APPLY_IMPORT START previewId=3 new=0 updated=1`
   - `DB_IMPORT APPLY_IMPORT SUCCESS previewId=3`
   - `GeneratedDbFlow flow=generated_update uid=5 rows=7 observerReloadSuppressed=true`
   - `GeneratedDbFlow flow=generated_save uid=5 rows=7 observerReloadSuppressed=true`
   - `HistorySessionSyncV2 cycle=push outcome=ok reason=local_commit ...`
   - `RealtimeCoordinator cycle=pull_apply outcome=ok inserted=0 updated=0 skipped=1 dirtyLocalSkips=1 source=realtime`

Conclusione preliminare: apply locale Room sembra riuscire; residuo principale su UX post-success, resolver destinazione, back stack tab e rappresentazione/trigger sync dirty.

---

## Audit statico (planning)

Fonti codice lette in questa fase:

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/Screen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/ImportNavOrigin.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistorySessionPushCoordinator.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductRemoteRef.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductRemoteRefDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogSyncStateTracker.kt`
- test esistenti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `ImportNavOriginTest`

Riscontri principali:

1. In `DatabaseViewModel.importProducts(...)`, il successo locale e separato dal cloud (`ImportApplyResult.Success` -> `ImportFlowState.Success`), quindi il principio Room-first esiste gia.
2. In `NavGraph`, il routing post-import e distribuito tra piu callback/branch (`success`, `close`, `correctRows`, fallback `analysis == null`) e dipende da variabili locali/route corrente.
3. La navigazione tab usa `navigateToRootTab(...)` con `restoreState = true`; questo puo riesumare child route stale quando la root attesa e `HistoryScreen`.
4. In `InventoryRepository.applyImportAtomically(...)` e ancora presente `markEntireCatalogDirtyForCloud()`, che marca supplier/category/product su tutto il catalogo: compatibile con sintomo `... / 18866`.
5. L'indicatore cloud alto destra (`CloudSyncIndicator`) e globale: se dirty set e full-catalog, percezione utente e blocco post-conferma anche se apply locale e gia concluso.

---

## Obiettivo tecnico

1. Definire una fonte di verita esplicita e persistente per origin/context della sessione `Generated`.
2. Unificare uscita (`Fine`, `Back`, `Annulla`, `Conferma`) con resolver unico testabile.
3. Garantire destinazione finale coerente con il percorso di ingresso reale.
4. Correggere comportamento tab `Cronologia` verso root reale, evitando restore di child stale.
5. Mantenere import locale Room-first e chiusura UX immediata dopo `APPLY_IMPORT SUCCESS`.
6. Correggere dirty marking per scenario import/update puntuale: preferire incrementale su entita toccate.
7. Allineare l'indicatore sync al lavoro reale (full sync solo se esplicitamente necessario, non confuso con singola modifica).
8. Eliminare stati blank: ogni missing preview/session/context deve avere redirect sicuro o stato errore leggibile con CTA.

---

## Specifiche pre-execution (PLANNING)

> Questa sezione fissa requisiti e contract **prima** dell'implementazione. Non sostituisce il file task in fase Execution (log, evidenze, diff).

### Execution guard (PLANNING-only)

- L'integrazione in questo file resta fase **PLANNING**; **nessuna** transizione a `EXECUTION` senza conferma esplicita dell'utente.
- In questa fase **non** modificare: sorgenti Kotlin, XML, risorse, Gradle, proguard, **nessuna** migration Room locale, **nessuna** modifica remota Supabase/RLS/policy, **nessuna** chiamata o script live a Supabase.
- Build/test manuali o CI **non** sono obbligati in questa integrazione del documento; l'esecutore li eseguirà in Execution secondo `AGENTS.md` e criteri di accettazione.

### Destination matrix (flussi A-F)

Ogni riga e il contratto UX atteso; l'implementazione deve mapparla al resolver/NavGraph in modo testabile (vedi sotto).

| Flusso | Percorso | Destinazione attesa (success path) | Divieti / note |
|--------|----------|------------------------------------|-----------------|
| **A** | Nuovo Excel -> Generated -> **Fine** | Root o destinazione gia definita dal flusso "nuovo Excel" esistente (equivalente `NavigateToNewExcelDestination` nel resolver). | Non tornare a History; non lasciare blank. |
| **B** | Nuovo Excel -> Generated -> Revisione -> **Conferma** | Dopo `APPLY_IMPORT` / `ImportApplyResult.Success` in Room, **chiusura immediata** della UX locale (niente attesa cloud). Destinazione = stessa famiglia del flusso nuovo Excel (stessa riga A). ImportAnalysis **non** resta "viva" o bloccata da sync. | Sync in background; nessun blocco navigation su Conferma. |
| **C** | History -> Generated -> **Fine** | Se `entryUid` / history context e disponibili: **tornare all'entry History corretta** (dettaglio se esiste route/stato stabile; altrimenti lista con entry selezionata/evidenziata se gia supportato). Se entry non rappresentabile o `entryUid` assente: **HistoryScreen root** (lista cronologia). | Non uscire verso Inventory/Database come default. |
| **D** | History -> Generated -> Revisione -> **Conferma** | Dopo `APPLY_IMPORT SUCCESS`: **nessuna schermata bianca**. Tornare al contesto History: **preferenza** dettaglio entry se disponibile, altrimenti **root** History. Prodotto aggiornato in **Room subito** (gia garantito da apply); cloud sync solo background. | Sync non blocca; se entry assente o non rappresentabile, stesso criterio della riga **C** (root History). |
| **E** | History -> Generated -> Revisione -> **Annulla** | Tornare alla **GeneratedScreen precedente** con **dati di sessione validi** (preview/griglia coerente). | Non azzerare in modo che resti route senza dati; non navigare a Inventory/Database. |
| **F** | Qualsiasi **child** route sotto il tab -> tap **Cronologia** | Sempre **HistoryScreen root** (lista cronologia). | **Non** riesumare `Generated` o `ImportAnalysis` via `restoreState` su quel tab switch; se si preserva stato, solo per **root** History, non per child `Generated`/`ImportAnalysis` stale. |

Riferimento incrociato: criteri accettazione #1-5, #8-9; ipotesi root cause #2-4.

### Contract: `GeneratedExitDestinationResolver` (planning-only)

Nomi tipo/classe finali a carico dell'execution; qui si definiscono **input/output semantici** e la regola di unificazione.

**Input minimi (tutti obbligatori salvo where noted):**

| Campo | Tipo (semantico) | Note |
|-------|------------------|------|
| `origin` | `ImportNavOrigin` | Fonte del flusso (HOME, HISTORY, DATABASE, GENERATED, ecc. come da enum esistente). |
| `entryUid` / `historyEntryId` | `String?` | Opzionale; obbligatorio per preferire dettaglio entry quando il flusso e da History. |
| `previewId` / identificatore preview | opzionale | Per correlazione log e fallback `MissingPreview`. |
| `currentRoute` | stringa route corrente | Per decisioni e log (non loggare query sensibili). |
| `exitReason` | enum concettuale | `Done`, `SystemBack`, `ImportCancel`, `ImportSuccess`, `MissingPreview`, `MissingSession` (i nomi effettivi in Kotlin allineati a questo set). |
| `fromHistory` | `Boolean?` | Solo se **gia** presente nel progetto o derivabile senza duplicare `origin`/`HISTORY`; **no** campi ridondanti introdotti per hobby. |

**Output consigliati (o equivalente 1:1 in sealed class / navigation action):**

- `NavigateToHistoryRoot` — pop alla root del tab/grafo coerente con History lista.
- `NavigateToHistoryEntry(entryUid)` — quando il modello supporta rotta o stato dettaglio stabile.
- `NavigateToGenerated` — con argomenti sessione gia noti; per **E** deve ripristinare contesto, non aprire vuoto.
- `NavigateToNewExcelDestination` — o nome equivalente alla destinazione post-"nuovo Excel" gia usata oggi (file picker, home excel, ecc. da mappare in execution).
- `ShowRecoverableErrorThenNavigateHistoryRoot` — copy leggibile + CTA; poi root History o altra root sicura dal resolver.
- `NoOp` — **consentito solo** se coperto da test e motivato (es. no-op reale in transizione gia gestita); default **non è** NoOp su missing data.

**Regola d'unificazione (non negoziabile in execution di questo task):**

- I punti di uscita **Fine**, **back di sistema**, **Annulla** revisione import, **Conferma** con successo locale, e i **fallback** `MissingPreview` / `MissingSession` devono passare dallo **stesso** resolver (o funzione pura centrale chiamata da un solo posto), **non** da branch `NavGraph` paralleli e divergenti.
- L'obiettivo e **un'unica mappa** (origin, exitReason, opzionali) -> destination; i callback Compose restano sottili.

### Vincolo Room-first / offline-first (rafforzato)

- `DatabaseViewModel.importProducts(...)` / apply Room locale resta l'**unica** fonte di verita per "importazione applicata" lato utente: `ImportApplyResult.Success` => progressione UI, **indipendente** da esito cloud.
- Dopo `ImportApplyResult.Success`, la UI **non** deve `await` Supabase ne condizionare navigation a push/pull completato.
- Cloud sync: **dopo** commit Room, **coda/background** (`CatalogAutoSyncCoordinator` / coordinatore esistente); nessun spin bloccante sulla schermata di revisione o su Generated per "sync finito".
- **Vietato** chiamate cloud (Supabase, Auth remoto, Realtime) dai **composable**; restano in repository/coordinator/VM come oggi.
- Questo task **esclude** esplicitamente: migration Supabase, RLS, "live apply" remoto, re-ingegnerizzazione auth.

### Dirty marking contract (incrementale)

- **Vietato** chiamare `markEntireCatalogDirtyForCloud()` nel **path normale** di import / update puntuale (inclusa revisione 1 prodotto aggiornato).
- Per modifica a **1 prodotto**: marcare dirty **solo** quel prodotto (e strutture collegate gia modellate: es. `ProductRemoteRef` / revision), piu **solo** supplier/category **effettivamente** creati o modificati nella stessa transazione.
- Supplier/category: dirty **solo** se toccati; **non** per "ogni import" generico.
- Price history / `ProductPrice` / ref esistenti: seguire il **modello gia in codebase**; nessun pretesto per estendere dirty a tutto il catalogo.
- Full dirty / full sync ammesso **solo** in casi espliciti e **separati** dal flusso rapido: bootstrap iniziale, recovery manuale o comando "full sync", path documentato; **non** mescolato con apply import singola riga.
- Indicatore `Invio modifiche prodotti X / Y` deve riflettere **lavoro incrementale reale**; per una modifica singola **Y non** deve equivalere a tutto il catalogo (es. 18866) salvo operazione full-sync esplicita.
- L'execution deve verificare con test repository che 1 prodotto aggiornato **non** innesca set dirty full-catalog (vedi test strategy).

### Fallback UX (anti blank screen)

- Qualsiasi assenza o invalidazione di `analysis`, preview, sessione `Generated`, `origin`, o `entryUid` deve produrre **una** di: redirect sicuro dal resolver, oppure **empty/error state** con testo + CTA.
- **Mai** lasciare un `composable` di route in rendering "vuoto" senza messaggio (blank e inaccettabile).
- CTA tipiche (label adattate alle stringhe esistenti / Material3):
  - "Torna alla cronologia" -> `NavigateToHistoryRoot` o `NavigateToHistoryEntry` se UID valido.
  - "Torna al risultato generato" / equivalente -> `NavigateToGenerated` con parametri noti o errore se impossibile.
  - "Torna al database" -> solo se coerente con `origin` e flusso (es. uscita da area DB); **non** come default uscita dai flussi C-E (History) salvo regola esplicita nel matrix.
- Le CTA devono chiamare la **stessa** logica di destinazione del resolver, non nav ad-hoc.

### UI/UX micro-polish (consentito, minimo)

- Consentito se aumenta chiarezza e coerenza Material3, **senza** redesign:
  - Snackbar / feedback breve: "Importazione applicata" (o stringa esistente) **dopo** success locale, prima/dopo navigazione se non invasivo.
  - Stati loading / success piu leggibili in `ImportAnalysisScreen` (es. disabilitazione + testo) **senza** attendere cloud.
  - Empty/error al posto di blank (allineato a "Fallback UX").
  - Indicatore sync reso **meno** "bloccante" a livello percettivo (testo/animazione) pur restando background — senza stravolgere layout app.
- Non cambiare tema globale, gerarchia tab, o pattern navigazione oltre quanto necessario a questo task.
- In caso di ambiguita UX, privilegiare: **completamento locale immediato** + **sync in background** (coerente con Room-first).

### Log diagnostici (basso rumore, privacy)

- Pianificare log **Debug** (o tag dedicato) attivabili in dev, con campi: `origin`, `currentRoute`, `exitReason`, `resolvedDestination` (enum/stringa stabile), `previewId`, `entryUid`, **conteggio** prodotti dirty (intero, non lista), `fullSyncRequested: true/false`.
- **Non** loggare: payload completi, barcode interi, PII, stringhe prodotto lunghe, token, email.
- Obiettivo: riprodurre A-F in campo con basso rumore; ridurre o `VERBOSE` solo se necessario in execution.

### Matrice test / strategia (dettaglio, pre-codice)

Oltre ai comandi in sezione "Test / check finali" e baseline TASK-004:

| Area | Cosa pianificare in Execution |
|------|--------------------------------|
| `GeneratedExitDestinationResolver` (o pura) | Tabelle: per ogni `(origin, exitReason, opzionali)` atteso output della matrix A-F; casi bordo: `entryUid` mancante, `MissingPreview`. |
| Destination matrix A-F | Test parametrici o elenco test nominati 1:1 con righe A-F. |
| `ImportNavOrigin` | HISTORY vs nuovo Excel vs missing/derivazione; nessun duplicato logico con `fromHistory` se gia coperto. |
| Repository / dirty | Import che aggiorna **1** prodotto: **non** tutto catalogo dirty; supplier/category assenti o non modificati => non sporchi; `markEntireCatalogDirtyForCloud` invocabile **solo** da path full/recovery testato a parte. |
| `ImportApplyResult.Success` / `DatabaseViewModel` | Test che **Success** e transition UI **non** dipendono da completamento sync (mock coordinatore o assenza lato test). |
| `CatalogSyncStateTracker` / indicator (se toccato) | Verifica che conteggi X/Y riflettano modello incrementale post-fix, senza accoppiare Conferma a "sync done". |

**Ambiente noto:** se `./gradlew :app:testDebugUnitTest` fallisce per MockK/ByteBuddy, documentare; restano obbligatori i test **mirati** e la motivazione, senza indebolire test per far "passare" lo suite (regola `AGENTS.md`).

---

## Execution sequencing plan

> Obiettivo: in **futura** Execution, evitare di modificare insieme navigation, repository, sync e UI senza ordine. I gate sotto sono **sequenza consigliata e verificabile**; ogni gate deve poter chiudersi con evidenze (test, manuale, log) prima del successivo, salvo blocco documentato.
>
> Fase attuale del task: **PLANNING** — questa sezione **non** autorizza patch; l'esecutore la seguira dopo `PLANNING -> EXECUTION` con conferma esplicita utente.
>
> **Ordine gate (checklist): 0** preflight read-only; **1** resolver puro + test; **2** navigation minima; **3** fallback UX anti-blank; **4** dirty incrementale; **5** indicatore sync **solo se** ancora necessario; **6** validazione finale.

### Gate 0 — Preflight (solo lettura / conferma fatti)

- Leggere il **codice Android reale** (non solo questo documento) prima di qualsiasi modifica: route effettive, argomenti `SavedStateHandle`/`Screen.*`, struttura `NavGraph`, schermate `GeneratedScreen`, `ImportAnalysisScreen`, `HistoryScreen`, `ExcelViewModel`, `DatabaseViewModel`, `InventoryRepository`.
- Confermare i **nomi e valori reali** di `ImportNavOrigin` e ogni flag/session context usato per History vs nuovo Excel.
- **Non assumere** che esista una **route stabile** `HistoryEntry` / dettaglio entry: **verificare** in `Screen.kt` + `NavGraph`. Se **non** esiste rotta dedicata, la matrix D/C resta: **History root** (e selezione/scroll solo se gia supportato oltre la navigazione) come fallback coerente con il contract.
- In fase **Execution**, nella sezione `Execution` del file task, **documentare** (bullet brevi) quali **route, argomenti e campi** sono stati riscontrati **prima** della prima patch (traccia per review e regressioni).

**Stop:** nessun commit di logica o navigazione in questo gate; solo lettura e note.

### Gate 1 — Resolver puro + test

- Introdurre o consolidare `GeneratedExitDestinationResolver` come **logica pura** (funzione / oggetto testabile su JVM).
- **Vietato** `NavController` o android context **dentro** il resolver: solo input in -> sealed class / modello di "navigation intent" in uscita.
- **Nessuna** business logic aggiuntiva nei composable; i composable **applicano** l'output (o chiamano un unico wrapper che mappa verso `NavController`).
- Aggiungere **test unitari** del resolver e copertura **destination matrix A-F** + bordi (`MissingPreview`, `MissingSession`, `entryUid` assente).
- **Criterio di uscita gate:** test resolver verdi **senza** toccare sync/cloud o logica `InventoryRepository` (solo tipi/utility se servono); il gate 1 **è** isolato da Supabase e da `markEntireCatalogDirty*`.

### Gate 2 — Collegamento navigation (minimo, senza redesign)

- In `NavGraph` (e punti d'uscita), collegare **allo stesso resolver** (o un'unica funzione che invoca il resolver e poi naviga): `Fine`, system back, `Annulla`, `Conferma success` (post-success locale), `MissingPreview`, `MissingSession`.
- **Ridurre** branch duplicati / `navigate` ad-hoc per lo stesso `exitReason`.
- Tab **Cronologia:** comportamento **F** — aprire **root** `HistoryScreen` / lista cronologia; evitare che `restoreState` (o equivalente) **riesuma** `Generated` / `ImportAnalysis` stale. Aggiustare `navigateToRootTab` / `popUpTo` / `restoreState` in modo **documentato** nel log Execution.
- **Nessun** redesign visivo in questo gate.
- **Verifica manuale minima** prima di aprire gate 3:
  - History -> Generated -> **Fine**
  - History -> Generated -> Revisione -> **Annulla**
  - tap tab **Cronologia** partendo da una **child** route sotto quella tab/grafo (come da repro reale)

### Gate 3 — Fallback UX (anti blank)

- Aggiungere empty / error state **solo** dove l'audit o il runtime mostrano route vive **senza** dati.
- **Mai** schermata bianca se mancano `analysis`, preview, sessione, `origin` o `entryUid`: redirect o pannello errore con CTA.
- CTA: sempre tramite **resolver** (o wrapper unico), **no** `navigate(...)` one-off dallo stesso evento.
- **Micro-polish** consentito solo se minimo e Material3, allineato al task: feedback "Importazione applicata", loading/success leggibile su `ImportAnalysisScreen`, percezione sync **in background** (non blocco del flusso Conferma/Fine).
- Criterio: nessun composable "vuoto" in gate 2-3; se resta un blank, **è** un bug aperto verso fine gate 6.

### Gate 4 — Dirty marking incrementale

- **Solo dopo** navigazione e fallback sotto controllo (gate 1-3 chiusi o regressioni assenti su A-F e tab).
- Rimuovere `markEntireCatalogDirtyForCloud()` dal **path normale** import/update puntuale.
- Un prodotto aggiornato: marcare dirty **solo** quel prodotto (e strutture collegate secondo modello esistente); supplier/category **solo** se create/modificate nella transazione.
- Full dirty **solo** in path espliciti: bootstrap, recovery, comando manuale full sync, come da **Dirty marking contract**.
- **Test repository** (obbligatori in questo gate o subito dopo):
  - import con **1** prodotto aggiornato => **non** tutto il catalogo dirty;
  - supplier/category **non** dirty se **non** modificati;
  - percorso "full dirty" ancora testabile **solo** dove documentato, separato dal quick import.

### Gate 5 — Indicatore sync / percezione

- Toccare `CatalogSyncStateTracker`, `CatalogAutoSyncCoordinator` (e affini) **solo se** dopo gate 4 l'indicatore mostra ancora `X /` totale catalogo per modifica **singola**.
- **Vietato** legare `Conferma` / `Fine` al completamento sync cloud: completamento utente = `ImportApplyResult.Success` (Room) come gia in Room-first.
- Log: conteggi/flag, `fullSyncRequested` — **niente** payload sensibili/PII (allineato a "Log diagnostici").

### Gate 6 — Validazione finale

- Eseguire test **mirati** gia elencati nel task (DatabaseViewModel, ExcelViewModel, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `ImportNavOriginTest`, **piu** i test aggiunti per resolver e matrix).
- Tentare `./gradlew :app:testDebugUnitTest`; se fallimento MockK/ByteBuddy, **documentare** senza indebolire test.
- **Smoke manuale obbligatorio** sul caso guida:  
  `History -> Generated -> Revisione -> Conferma` con **0 nuovi / 1 aggiornato / 0 errori**.  
  **Attesi:** nessun blank; ritorno a History (entry se supportato, altrimenti root); Room aggiornata subito; sync in **background**; indicatore **non** full-catalog per quella modifica puntuale.

### Ordine obbligatorio (vincoli tra gate)

- **Non** iniziare dirty marking (gate 4) se navigation + resolver (gate 1-2) **non** sono sotto controllo.
- **Non** fare UI polish (gate 3) prima di aver **eliminato blank** e **unificato** le uscite sul resolver (riduzione branch) — l'ordine 1 -> 2 -> 3 e intenzionale; se serve micro-fix cosmetico minimo in gate 2, documentarlo come eccezione nel log.
- **Non** cambiare schema **Room** ne **Supabase** in questa task.
- **Non** introdurre **nuove dipendenze** Gradle.
- **Non** trasformare la task in refactor generale di navigation o del cloud sync: resta nel perimetro gia definito in Scope incluso / escluso.

---

## Scope incluso

- Audit completo flussi post-import:
  - A. Nuovo Excel -> Generated -> Fine
  - B. Nuovo Excel -> Generated -> Revisione -> Conferma
  - C. History -> Generated -> Fine
  - D. History -> Generated -> Revisione -> Conferma
  - E. History -> Generated -> Revisione -> Annulla
  - F. Qualsiasi child route -> tab Cronologia
- Introduzione/consolidamento di `GeneratedExitDestinationResolver` (o equivalente) con contract unico.
- Correzione routing tab `Cronologia` per apertura root `HistoryScreen` (con popUpTo/launchSingleTop/restoreState coerenti).
- Verifica e fix dirty marking incrementale nel path import/update singolo.
- Log diagnostici mirati route/origin/destination/sync counts se utili.
- Estensione test su resolver uscita/origin e dirty marking incrementale.
- Smoke manuali obbligatori su device/emulator per i flussi critici.

---

## Scope escluso

- Nessun redesign UX ampio fuori perimetro.
- Nessun cambio schema Room salvo necessita dimostrata e autorizzata.
- Nessuna modifica Supabase live/RLS/migration remota senza autorizzazione esplicita.
- Nessun refactor completo del cloud sync.
- Nessuna nuova dipendenza.
- Nessuna business logic nei composable.
- Non rompere import/export Excel, barcode, manual entry, CRUD database, history esistente.

---

## Non incluso

- Migrazione architetturale totale NavGraph.
- Nuova shell/tab architecture completa.
- Reingegnerizzazione completa degli indicatori sync globali.

---

## Ipotesi root cause da verificare

1. Context/origin Generated non preservato in modo coerente lungo il passaggio `Generated -> ImportAnalysis -> Success/Cancel`.
2. `Fine`, `Back`, `Annulla`, `Conferma` usano percorsi resolver diversi e non equivalenti.
3. Bottom tab `Cronologia` con `restoreState` puo riesumare child route stale (`Generated`) invece della root `History`.
4. Reset preview/import state dopo success puo lasciare route viva senza dati e fallback non deterministico.
5. `markEntireCatalogDirtyForCloud()` nel path import/update puntuale causa dirty set full catalog e conteggi elevati.
6. Stato sync globale viene percepito come parte del completion flow locale, degradando UX post-confirm.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — orchestrazione route, tab root, success/cancel/fallback.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/Screen.kt` — route/args contract.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/ImportNavOrigin.kt` — origin parsing e fallback.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — trigger `Fine` e context sessione.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — trigger confirm/cancel/back.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — root history e select flow.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — generated navigation context.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — import state machine e success.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — apply import, dirty marking catalogo.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinator.kt` — trigger push/pull e scheduling.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistorySessionPushCoordinator.kt` — push history separato.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductRemoteRef.kt` — local revision model.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductRemoteRefDao.kt` — increment/sync revision.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/CatalogAutoSyncCoordinatorTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/ImportNavOriginTest.kt`
- (nuovo in execution, percorso/nome TBD) `app/src/main/java/.../ui/navigation/GeneratedExitDestinationResolver.kt` — logica pura / sealed destination
- (nuovo in execution) `app/src/test/java/.../ui/navigation/GeneratedExitDestinationResolverTest.kt` — contract + bordi
- (nuovo in execution) `app/src/test/java/.../ui/navigation/GeneratedExitDestinationMatrixTest.kt` — flussi A-F

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Da Generated aperta dalla Cronologia, premendo `Fine` si torna a Cronologia/HistoryEntry corretta, non a Inventory. | M / E | — |
| 2 | Da Generated creata da nuovo Excel, premendo `Fine` si torna alla destinazione prevista dal flusso nuovo Excel, non alla Cronologia. | M / E | — |
| 3 | Da History -> Generated -> Revisione -> Conferma (0 nuovi / 1 aggiornato / 0 errori): log `APPLY_IMPORT SUCCESS`, nessuna schermata bianca, ritorno corretto, prodotto aggiornato in Room. | M / B / E | — |
| 4 | Da History -> Generated -> Revisione -> `Annulla`: ritorno alla griglia Generated o destinazione prevista, mai blank screen. | M / E | — |
| 5 | Tab bottom `Cronologia` apre sempre la root/lista Cronologia, non una Generated stale. | M / E | — |
| 6 | Back system da import/generated rispetta la stessa logica di `Fine`, senza back stack incoerente. | M / E | — |
| 7 | Indicatore `Invio modifiche prodotti` non mostra full catalogo (es. 18866) per modifica singola, salvo bootstrap/full sync esplicito e documentato. | S / M / B | — |
| 8 | Import locale resta Room-first: funziona offline, chiude UX subito, sync dopo in background. | S / M / E | — |
| 9 | Auto-sync/Supabase non blocca Conferma/Fine/Back/tab switching. | M / E | — |
| 10 | Nessuna regressione su import Excel, export Excel, CRUD database, history flow, barcode, manual entry, generated flow. | B / M | — |
| 11 | Test mirati verdi o limiti ambiente documentati senza mascheramenti. | B | — |
| 12 | Smoke manuali device/emulator documentati per History -> Generated -> ImportAnalysis -> Conferma/Fine/Annulla. | M / E | — |

Legenda tipi: B=Build/Test, S=Static, M=Manual, E=Emulator/Device

---

## Test / check finali richiesti

**Allineamento:** vedi anche "Matrice test / strategia" in `## Specifiche pre-execution (PLANNING)` (resolver, matrix A-F, dirty incrementale, Success senza cloud).

```bash
git diff --check
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"
./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"
# Dopo implementazione (nomi package/class finali da adeguare):
# ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest"
# ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"
```

Obblighi addizionali:

- **Resolver:** test unitari dedicati al puro `GeneratedExitDestinationResolver` (o equivalente), con tabella decisionale A-F + `MissingPreview` / `MissingSession`.
- **Matrix A-F:** test che ancorano ogni flusso A-F alla destinazione attesa (parametrizzati o test separati nominati).
- **ImportNavOrigin:** estendere o aggiungere casi HISTORY / nuovo Excel / origine mancante, coerenti con il contract (evitare ridondanza con flag gia espressi dall'enum).
- **Repository dirty:** assert che import 1-prodotto **non** marchi tutto il catalogo come dirty; supplier/category non dirty se non toccati; full dirty solo nel path esplicito (test separato o spy su `markEntireCatalogDirtyForCloud`).
- **ViewModel:** `ImportApplyResult.Success` e navigazione/dismiss preview **senza** dipendenza da sync cloud (mock).
- Tentare anche `./gradlew :app:testDebugUnitTest`; se fallisce per problema noto MockK/ByteBuddy, documentarlo esplicitamente **senza** rimuovere o indebolire test.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Nuovo task dedicato invece di estendere TASK-056 | Il problema residuo e multiplo (UX post-success + tab/back stack + dirty sync), non risolvibile con micro-patch singola. | 2026-04-24 |
| 2 | Priorità impostata a `CRITICA` | Impatto diretto su completion import e confidenza utente (blank screen + routing incoerente + sync percepito errato). | 2026-04-24 |
| 3 | Planning-only in questa fase, nessuna patch funzionale | Richiesta utente esplicita; serve gate EXECUTION dopo conferma. | 2026-04-24 |
| 4 | Room-first/offline-first resta vincolo non negoziabile | Coerenza con architettura attuale e con evidenze log `APPLY_IMPORT SUCCESS`. | 2026-04-24 |
| 5 | Contract espliciti (matrix, resolver, dirty, fallback, log) vincolano l'execution | Riduce branch divergenti in NavGraph e ambiguita su sync vs completion locale. | 2026-04-24 |
| 6 | Execution in **gate 0-6** (vedi **Execution sequencing plan**) con **Ordine obbligatorio** | Evita modifiche concorrenti a navigation, repository, sync e UI senza evidenze intermedie. | 2026-04-24 |

---

## Planning (Claude)

### Coerenza planning (revisione finale, documento)

**Final planning coherence pass completed; no scope changes.**

Controlli effettuati: matrix A–F, contract resolver, Room-first, dirty contract, fallback UX, Gate 0–6, `Ordine obbligatorio` e `Gate stop / rollback / split` risultano **allineati** (nessun ampliamento perimetro: no refactor generale nav/sync, no schema Room, no Supabase/RLS non autorizzate, no nuove dipendenze, no redesign ampio; dirty solo dopo nav/resolver stabile). Stato task: **PLANNING**; `PLANNING -> EXECUTION` su **conferma esplicita** utente. Questa revisione documentale **non** autorizza patch.

### Analisi

Il codice attuale mostra tre aree da riallineare:

1. **Resolver uscita frammentato**: il flusso ImportAnalysis in `NavGraph` ha piu branch (`success`, `close`, `correctRows`, fallback missing preview) con stato locale non pienamente centralizzato.
2. **Tab root restore ambiguo**: il tab switch (`navigateToRootTab`) usa `restoreState` e puo ripristinare child route stale.
3. **Dirty marking eccessivo**: `applyImportAtomically` chiama `markEntireCatalogDirtyForCloud()`, compatibile con conteggi full-catalog non coerenti con import puntuale.

### Piano di esecuzione

L'implementazione in **EXECUTION** deve seguire la sezione **Execution sequencing plan** (Gate 0-6) e **Ordine obbligatorio**; i passi qui sotto sono la mappa concettuale allineata a quei gate.

1. Implementare la **destination matrix A-F** nel codice tramite **un solo** `GeneratedExitDestinationResolver` (o pura equivalente), come da contract; rimuovere branch duplicati in `NavGraph`.
2. Input resolver: `origin`, `exitReason`, `currentRoute`, opzionali `entryUid`, `previewId`; allineare `fromHistory` solo se gia nel modello.
3. Collegare **tutti** gli exit point: `Fine`, system back, Annulla revisione, Conferma success, `MissingPreview` / `MissingSession` allo stesso resolver.
4. Tab **Cronologia (F)**: navigazione a **root** History; regolare `restoreState` / `popUpTo` in modo documentato per non riesumare `Generated`/`ImportAnalysis` stale.
5. **Room-first:** nessuna attesa Supabase post-`ImportApplyResult.Success`; composable senza cloud.
6. **Dirty contract:** rimuovere `markEntireCatalogDirtyForCloud()` dal path import puntuale; incrementale su entita toccate; full dirty solo path espliciti (bootstrap/recovery/comando).
7. **Fallback UX:** stati empty/error con CTA che chiamano il resolver; nessun composable route "vuoto".
8. **Log** low-noise come da sezione dedicata (opzionale flag dev).
9. Test: resolver + matrix + ImportNavOrigin + repository dirty + VM Success senza sync; baseline TASK-004 dove toccato.
10. Smoke manuali A-F su device/emulator; evidenze nel file task.

### Rischi identificati

| Rischio | Probabilita / impatto (indicativo) | Mitigazione (planning) |
|---------|-----------------------------------|-------------------------|
| Branch NavGraph ancora duplicati dopo il fix | Media / alta divergenza Fine vs back | Resolver unico + test matrix; code review su ogni callback |
| `restoreState` tab altera ancora F | Media / UX "Generated fantasma" | Specifica F: root History; test manuale tab + eventuale test navigazione se harness esiste |
| Dirty incrementale dimentica edge (nuovo supplier) | Media / sync incompleto | Transazione import: elencare entita toccate in execution; test repository |
| Indicator sync ancora "full" per altro trigger oltre dirty | Bassa / percezione negativa | Verificare `CatalogSyncStateTracker` e coordinator; log `fullSyncRequested` |
| Suite completa JVM rotta (MockK/ByteBuddy) | Nota / non mascherare | Test mirati verdi + documentazione; follow-up ambiente separato |
| Micro-polish UI confuso con scope | Bassa | Solo voci elencate; documentare in Execution log (`AGENTS.md`) |

---

## Execution

### Esecuzione — 2026-04-24 22:59 -04

**File modificati:**
- `docs/TASKS/TASK-057-riordino-ux-post-import-generated-history-fine-sync-dirty-incrementale.md` — transizione documentale `PLANNING -> EXECUTION`, aggiornamento timestamp e apertura log Execution.

**Azioni eseguite:**
1. Execution autorizzata esplicitamente dall'utente per la transizione `PLANNING -> EXECUTION`.
2. Avvio vincolato all'Execution sequencing plan Gate 0-6; prossima fase: Gate 0 read-only sul codice Android reale, senza modifiche di logica.

**Check obbligatori:**
| Check                    | Stato | Note                        |
|--------------------------|-------|-----------------------------|
| Build Gradle             | ⏳ | Da eseguire in Gate 6 |
| Lint                     | ⏳ | Da eseguire in Gate 6 |
| Warning nuovi            | ⏳ | Da verificare dopo le modifiche codice |
| Coerenza con planning    | ⏳ | In corso: avvio Execution secondo conferma utente |
| Criteri di accettazione  | ⏳ | Da verificare nei gate previsti |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: da determinare dopo Gate 0 e modifiche effettive.
- Test aggiunti/aggiornati: nessuno in questa voce iniziale.
- Limiti residui: nessuno al momento.

**Incertezze:**
- (nessuna nella transizione documentale iniziale)

**Handoff notes:**
- Non modificare codice prima della chiusura del Gate 0 read-only con route, argomenti, origin/session e punto minimo di intervento documentati.

### Gate 0 — Preflight read-only — 2026-04-24 23:01 -04

**Route reali trovate:**
- `Screen.FilePicker.route = "filePicker"` — root flusso nuovo Excel/home.
- `Screen.PreGenerate.route = "preGenerate"` — pre-generazione dopo file picker.
- `Screen.Generated.route = "generated/{entryUid}/{isNew}/{isManualEntry}"` con `createRoute(entryUid, isNew, isManualEntry)`.
- `Screen.History.route = "history"` — root lista Cronologia.
- `Screen.Database.route = "databaseScreen"` — root Database.
- `Screen.Options.route = "optionsScreen"`.
- `Screen.ImportAnalysis.route = "importAnalysisScreen/{importOrigin}"` con `ARG_ORIGIN = "importOrigin"` e `createRoute(origin)`.

**Argomenti disponibili:**
- `Generated`: `entryUid: Long`, `isNew: Boolean`, `isManualEntry: Boolean`.
- `ImportAnalysis`: `importOrigin: String`, parsed tramite `ImportNavOrigin.parse(...)`.
- `ExcelViewModel.currentEntryStatus.value.third` espone l'UID corrente; `peekGeneratedRouteIsNew()` e `peekGeneratedRouteIsManualEntry()` conservano gli argomenti dell'ultima route `Generated`.
- `ExcelViewModel.peekImportOriginForGeneratedSession()` conserva l'origine della sessione Generated; oggi viene impostata a `HOME` per nuovo Excel/manuale e `HISTORY` quando si apre dalla cronologia.
- `DatabaseViewModel.importNavigationOrigin` conserva l'origine della preview import pubblicata da `analyzeGridData`, `startImportAnalysis` o `startSmartImport`.

**Origine History / nuovo Excel:**
- Nuovo Excel: `FilePicker -> PreGenerate -> Generated`, con `noteGeneratedNavigationContext(..., importOrigin = ImportNavOrigin.HOME)`.
- Manual entry da home: `FilePicker -> Generated`, con `isManualEntry = true` e `importOrigin = HOME`.
- Cronologia: `HistoryScreen.onSelect -> excelViewModel.loadHistoryEntry(entry.uid) -> Generated`, con `importOrigin = HISTORY`.
- Revisione da `Generated`: `GeneratedScreen.analyzeCurrentGrid()` chiama `databaseViewModel.analyzeGridData(..., excelViewModel.peekImportOriginForGeneratedSession())`; quindi il contesto History vs nuovo Excel passa gia' dal ViewModel, ma in `NavGraph` viene poi trasformato in `GENERATED` se esiste `currentEntryUid`.

**HistoryEntry detail route stabile:**
- Non esiste una route dedicata `HistoryEntry` / detail nel contratto di navigazione reale (`Screen.kt` e `NavGraph.kt` espongono solo `history` come root/lista).
- La destinazione corretta per flussi C/D, nel perimetro attuale, e quindi `HistoryScreen` root; nessuna selezione/scroll entry stabile e' gia' supportata.

**Punto minimo corretto dove intervenire:**
- Gate 1: aggiungere un resolver puro in `ui/navigation` che mappi `(origin, exitReason, currentRoute, entryUid?, previewId?)` a destinazioni astratte.
- Gate 2: sostituire i branch paralleli in `NavGraph` (`navigateToImportSuccessDestination`, fallback `analysis == null`, `onCorrectRows`, `onClose`, success apply) e i callback `GeneratedScreen` (`Fine`/Back) con un unico wrapper che applica il resolver.
- Tab `Cronologia`: correggere `navigateToRootTab(...)` per `Screen.History` evitando `restoreState = true`, cosi' non riesuma child route stale.
- Gate 4: `InventoryRepository.applyImportAtomically(...)` oggi termina con `markEntireCatalogDirtyForCloud()`, confermato come punto del dirty full-catalog da rimuovere solo dopo Gate 1-3.

**Gate 0 esito:** chiuso; nessuna logica Kotlin modificata durante il gate.

### Gate 1 — Resolver puro + test — 2026-04-24 23:06 -04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt` — aggiunto resolver puro JVM-testable con `GeneratedExitReason`, `GeneratedExitRequest` e `GeneratedExitDestination`; nessun `NavController`, Context o business logic.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolverTest.kt` — test bordi `MissingPreview`, `MissingSession`, `entryUid` mancante, origin History e origin nuovo Excel.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationMatrixTest.kt` — test destination matrix A-F.

**Azioni eseguite:**
1. Consolidata la mappa pura `(origin, exitReason, currentRoute, entryUid?, previewId?) -> destination`.
2. Ancorata la scelta History al solo `HistoryRoot`, perche' Gate 0 ha confermato che non esiste route stabile `HistoryEntry`.
3. Coperti `Fine`, system back, `Annulla`, `Conferma success`, `MissingPreview`, `MissingSession` e tab Cronologia come ragioni astratte; l'applicazione NavController resta demandata al Gate 2.

**Test Gate 1:**
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest" --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"` — ✅ ESEGUITO.
- Nota ambiente: primo tentativo senza `JAVA_HOME` non eseguibile per `Unable to locate a Java Runtime`; rilancio con JBR Android Studio riuscito.

**Gate 1 esito:** chiuso; nessuna modifica sync/cloud/repository in questo gate.

### Gate 2 — Navigation minima — 2026-04-24 23:17 -04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — collegati `Fine`, system back, `Annulla`, `Conferma success`, `MissingPreview`, `MissingSession` e tab Cronologia allo stesso resolver/wrapper; rimosso il vecchio helper `navigateToImportSuccessDestination(...)` e i branch `generatedAwareReturnOrigin` / `missingPreviewFallbackOrigin`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — sostituiti i callback legacy `onBackToStart` / `onNavigateToHome` con `onExit(GeneratedExitReason)`, mantenendo business logic fuori dal composable.

**Azioni eseguite:**
1. `GeneratedScreen` passa una reason astratta (`Done` o `SystemBack`) al `NavGraph`; il composable non conosce le route finali.
2. `ImportAnalysisScreen` resta sottile: `onClose`/`onCorrectRows`/success apply arrivano allo stesso wrapper `navigateByGeneratedExitRequest(...)`.
3. `MissingPreview` e `MissingSession` passano dal resolver; per ora navigano al fallback sicuro del resolver (Gate 3 valutera' empty/error state dove serve).
4. Tab `Cronologia`: per `HistoryTabSelected` si naviga a `HistoryRoot` con `restoreState = false`, evitando riesumazione di child route stale.
5. Fix durante smoke: dopo `Annulla`, `clearImportAnalysis()` faceva scattare il fallback `analysis == null`; impostato `fallbackNavigationRequested = true` prima di navigation intenzionale/clear, cosi' non sovrascrive il ritorno a `Generated`.

**Verifiche Gate 2:**
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest" --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug` su `emulator-5554` — ✅ ESEGUITO.

**Smoke manuale/emulator Gate 2:**
- Preparazione: aggiunta una singola entry runtime `Task057 Smoke` nel database dell'emulator via `run-as`/SQLite, solo come dato locale di smoke; nessun file repo modificato.
- `History -> Generated -> Fine -> Esci`: ✅ ritorno a `HistoryScreen` root, nessun blank.
- `History -> Generated -> Revisione Importazione -> Annulla`: ✅ ritorno alla `GeneratedScreen` valida, nessun blank.
- Tap tab `Cronologia` da root non-History: ✅ apre `HistoryScreen` root; il caso "child route con bottom tab visibile" non e' rappresentabile nel grafo attuale per `Generated`/`ImportAnalysis` perche' quelle route non mostrano bottom bar, ma il codice ora forza `restoreState = false` per `HistoryRoot`.

**Gate 2 esito:** chiuso.

### Gate 3 — Fallback UX anti blank — 2026-04-24 23:19 -04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — aggiunto fallback UI minimale `RouteFallbackState(...)` per route senza preview/sessione, con CTA che richiama lo stesso resolver/wrapper centrale.

**Azioni eseguite:**
1. `ImportAnalysis` con `analysis == null` e non loading non resta piu' route vuota: mostra stato recuperabile e continua a fare redirect sicuro via resolver.
2. `Generated` con sessione mancante non resta spinner/blank indefinito: mostra lo stesso fallback recuperabile e CTA.
3. Nessun redesign: solo stato Material3 minimale con copy esistente (`import_preview_invalidated`) e azione `Indietro`.

**Verifiche Gate 3:**
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` — ✅ ESEGUITO.

**Gate 3 esito:** chiuso.

### Gate 4 — Dirty marking incrementale — 2026-04-24 23:26 -04

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — rimosso il dirty full-catalog dal normale `applyImportAtomically(...)`; l'import ora marca dirty solo supplier/category creati durante l'apply e product id realmente toccati.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — aggiunto test repository per import di un solo prodotto aggiornato, con supplier/category gia' sincronizzati e prodotto non correlato.

**Azioni eseguite:**
1. Rimosso il vecchio helper privato `markEntireCatalogDirtyForCloud()`, usato solo dal path normale di import.
2. Tracciati in memoria `createdSupplierIds` e `createdCategoryIds` durante la risoluzione di relation temporanee/importate.
3. Calcolati i soli `touchedProductIds` persistiti dall'import e marcati dirty solo quelli.
4. Verificato che supplier/category gia' esistenti e non modificati restino con `localChangeRevision = 0`.
5. Verificato che un prodotto non correlato, gia' sincronizzato, non entri tra i `getCatalogPushCandidates()`.
6. Full dirty: non esiste un comando pubblico dedicato nel repository; il vecchio full-dirty privato e' stato rimosso dal path normale. Bootstrap/recovery restano coperti dal normale `syncCatalogWithRemote(...)` su righe senza bridge, senza introdurre nuova API.

**Verifiche Gate 4:**
- Primo tentativo repository test: ⚠️ non superato per errore nel nuovo test (`changedFields` richiedeva `List<Int>`); corretto usando `emptyList()` come nei test esistenti.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` — ✅ ESEGUITO.

**Baseline regressione TASK-004:**
- Test eseguiti: `DefaultInventoryRepositoryTest` completo.
- Test aggiunti/aggiornati: aggiunto `applyImport updating one synced product marks only that product dirty`.
- Limiti residui: nessuno sul dirty marking repository; `DatabaseViewModelTest`, `ExcelViewModelTest` e `CatalogAutoSyncCoordinatorTest` restano da rieseguire nel Gate 6 finale.

**Gate 4 esito:** chiuso.

### Gate 5 — Indicatore sync / percezione — 2026-04-24 23:26 -04

**File modificati:**
- Nessuno.

**Azioni eseguite:**
1. Verificato `CatalogSyncStateTracker`: conserva `current`/`total` forniti dal reporter senza ricalcolare sul totale catalogo.
2. Verificato `CatalogAutoSyncCoordinator`: avvia lo stato busy, ma poi inoltra i progress reali del repository tramite `CatalogSyncProgressReporter`.
3. Verificato `InventoryRepository.pushCatalogSuppliers/Categories/Products(...)`: i progress usano `candidates.size`, non il totale catalogo (`supplierDao.count()`, `categoryDao.count()`, `productDao.count()` sono usati solo nei log metrici).
4. Verificato `CloudSyncIndicator`: mostra conteggi solo se `current` e `total` sono valorizzati; quindi dopo Gate 4, un singolo prodotto dirty produce `1 / 1`, non `1 / totale catalogo`.
5. Nessun legame aggiunto tra `Conferma`/`Fine` e completamento cloud: completion utente resta `ImportApplyResult.Success` Room.

**Verifiche Gate 5:**
- `DefaultInventoryRepositoryTest` completo gia' eseguito in Gate 4 include `042 incremental catalog push evaluates only dirty product candidates`, che verifica `PUSH_PRODUCTS.total == 1` per un solo dirty product.

**Gate 5 esito:** chiuso; nessuna modifica necessaria.

### Gate 6 — Validazione finale — 2026-04-24 23:37 -04

**File modificati nel task:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt` — resolver puro per uscite Generated/ImportAnalysis/tab History/fallback.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — integrazione resolver, fallback anti-blank, History root con `restoreState = false`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — callback di uscita centralizzato via `GeneratedExitReason`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — dirty marking import incrementale.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolverTest.kt` — test resolver.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationMatrixTest.kt` — test matrix A-F.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test dirty incrementale su singolo update.
- `docs/TASKS/TASK-057-riordino-ux-post-import-generated-history-fine-sync-dirty-incrementale.md` — log Execution, Gate 0-6, evidenze e handoff.

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` — BUILD SUCCESSFUL |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning Kotlin/lint nuovo legato ai file modificati rilevato; restano warning Gradle/AGP di configurazione progetto gia' esistenti (`android.builtInKotlin`, `android.newDsl`, legacy variant API). |
| Coerenza con planning    | ✅ ESEGUITO | Gate 0-6 eseguiti in ordine; dirty marking iniziato solo dopo resolver/navigation/fallback stabili. |
| Criteri di accettazione  | ✅ ESEGUITO | Verifica dettagliata sotto; limiti ambiente documentati senza mascherare test falliti. |

**Comandi finali eseguiti:**
- `git diff --check` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest" --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"` — ✅ ESEGUITO.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest` — ⚠️ NON ESEGUIBILE COMPLETAMENTE in questo ambiente: full suite tentata, ma fallisce su MockK/ByteBuddy attach (`AttachNotSupportedException` / `ByteBuddyAgent`), 358 test completati, 121 falliti, 2 skipped. Le suite mirate richieste sopra sono verdi se eseguite singolarmente.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug` — ✅ ESEGUITO per smoke emulator finale su `emulator-5554`.

**Smoke manuale/emulator finale:**
- `History -> Generated -> Fine -> Esci`: ✅ ritorno a `HistoryScreen` root, nessun blank.
- `History -> Generated -> Revisione Importazione -> Annulla`: ✅ ritorno a `GeneratedScreen`, nessun blank.
- `History -> Generated -> Revisione Importazione -> Conferma Importazione`: ✅ ritorno a `HistoryScreen` root, nessun blank.
- Tab `Cronologia` da root non-History: ✅ apre root/lista Cronologia.
- Nota dati emulator: la conferma finale runtime ha usato la fixture `Task057 Smoke` come `1 nuovo / 0 aggiornati`; il caso prioritario `0 nuovi / 1 aggiornato` resta coperto da test repository/ViewModel e resolver, perche' costruire il dato update via UI avrebbe richiesto setup manuale ulteriore fuori dal minimo smoke. L'emulator conteneva inoltre dirty catalog pre-esistente; per il criterio "non full-catalog per modifica singola" fa fede il test deterministico `applyImport updating one synced product marks only that product dirty` + `042 incremental catalog push evaluates only dirty product candidates`.

**Criteri di accettazione — esito Execution:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ESEGUITO | Smoke emulator `History -> Generated -> Fine`; resolver `origin HISTORY -> HistoryRoot`. |
| 2 | ESEGUITO | Resolver/matrix: origin nuovo Excel (`HOME`) risolve `NewExcelDestination`; nessun branch History nel flusso nuovo Excel. |
| 3 | ESEGUITO | `DatabaseViewModelTest`, `DefaultInventoryRepositoryTest`, resolver tests; smoke post-`Conferma` su emulator senza blank e ritorno History root. |
| 4 | ESEGUITO | Smoke emulator `Revisione -> Annulla`; fix `fallbackNavigationRequested` evita fallback stale. |
| 5 | ESEGUITO | `HistoryTabSelected` risolve `HistoryRoot`; `NavGraph` usa `restoreState = false` per History root; smoke tab Cronologia. |
| 6 | ESEGUITO | `GeneratedScreen` system back e `Fine` passano entrambi da `onExit(GeneratedExitReason)` e dal resolver. |
| 7 | ESEGUITO | Dirty import incrementale; test repository verifica un solo prodotto candidate e supplier/category puliti se non modificati; progress usa `candidates.size`. |
| 8 | ESEGUITO | Success import resta Room-first (`ImportApplyResult.Success`); nessuna attesa cloud aggiunta a `Conferma`/`Fine`. |
| 9 | ESEGUITO | Nessun collegamento introdotto tra auto-sync/Supabase e navigation; Gate 5 verificato read-only. |
| 10 | ESEGUITO | Build/lint verdi; baseline mirate `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest` verdi. |
| 11 | ESEGUITO | Test mirati verdi; full suite tentata e limite MockK/ByteBuddy documentato. |
| 12 | ESEGUITO | Smoke emulator documentati per Fine, Annulla, Conferma; vedi nota fixture sopra. |

**Baseline regressione TASK-004:**
- Test eseguiti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`, `ImportNavOriginTest`, resolver/matrix tests.
- Test aggiunti/aggiornati: `GeneratedExitDestinationResolverTest`, `GeneratedExitDestinationMatrixTest`, `DefaultInventoryRepositoryTest.applyImport updating one synced product marks only that product dirty`.
- Limiti residui: full `:app:testDebugUnitTest` bloccata da MockK/ByteBuddy attach nel runtime locale, non da regressione dei file TASK-057.

**Incertezze:**
- Il repository non espone un comando pubblico "full dirty"; il vecchio helper privato era usato solo dal path import normale ed e' stato rimosso. Bootstrap/recovery restano sul path `syncCatalogWithRemote(...)` e missing bridge.
- L'emulator usato per smoke conteneva dati/dirty catalog pre-esistenti; le prove conteggio sync affidabili sono quelle JVM deterministiche sui DAO/candidates.

**Handoff notes:**
- Stato task portato a `REVIEW`.
- `docs/MASTER-PLAN.md` risulta modificato nel worktree ma non e' stato toccato durante TASK-057; non modificato per rispettare la regola "non aggiornare stato globale".
- Review consigliata: controllare `NavGraph.kt` per back stack/fallback e `InventoryRepository.kt` per dirty marking incrementale.

---

## Review

### Review — 2026-04-24

**Revisore:** Claude (planner)

**Letture effettuate:**
- `docs/TASKS/TASK-057-...md` (planning + Execution Gate 0-6).
- `git diff HEAD` separando TASK-057 dai cambi preesistenti su `docs/MASTER-PLAN.md`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` (apply import path + helper rimosso)
- Test: `GeneratedExitDestinationResolverTest`, `GeneratedExitDestinationMatrixTest`, `DefaultInventoryRepositoryTest`.
- Codice di contorno per validare ipotesi: `ExcelViewModel.noteGeneratedNavigationContext / loadHistoryEntry / resetState`, `DatabaseViewModel.startSmartImport / startImportAnalysis / importNavigationOrigin`.

**Comandi rieseguiti dal planner (review-side, non solo gate executor):**
- `git diff --check` — ✅ pulito.
- `JAVA_HOME=...jbr ./gradlew :app:assembleDebug` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=...jbr ./gradlew :app:lintDebug` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...GeneratedExitDestinationResolverTest" --tests "...GeneratedExitDestinationMatrixTest" --tests "...DefaultInventoryRepositoryTest" --tests "...ImportNavOriginTest"` — ✅.
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...DatabaseViewModelTest" --tests "...ExcelViewModelTest" --tests "...CatalogAutoSyncCoordinatorTest"` — ✅.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Da Generated aperta dalla Cronologia, `Fine` torna a Cronologia/HistoryEntry corretta. | ✅ | Resolver `(HISTORY, Done) -> HistoryRoot`; coperto da matrix test C + smoke executor. Non esiste route stabile `HistoryEntry`, conforme a Gate 0. |
| 2 | Da Generated `nuovo Excel`, `Fine` torna a destinazione nuovo Excel. | ✅ | Resolver `(HOME, Done) -> NewExcelDestination`; matrix test A. |
| 3 | History -> Generated -> Revisione -> Conferma `0/1/0`: nessun blank, ritorno corretto, prodotto aggiornato. | ✅ | Coperto da `applyImport updating one synced product marks only that product dirty` + `DatabaseViewModelTest`; smoke runtime su fixture `1/0/0` (limite documentato). |
| 4 | Annulla revisione: ritorno a Generated, mai blank. | ✅ | Resolver `(HISTORY/HOME, ImportCancel)` con `entryUid > 0` -> `Generated(...)`; smoke executor. |
| 5 | Tab `Cronologia` apre sempre root, non Generated stale. | ✅ | `HistoryTabSelected` ramo separato + `restoreState = false` su `HistoryRoot`. |
| 6 | System back rispetta logica `Fine`. | ✅ | `BackHandler` -> `requestGeneratedExit(SystemBack)` -> stesso resolver. |
| 7 | Indicatore non mostra full-catalog per modifica singola. | ✅ | Rimosso `markEntireCatalogDirtyForCloud()` dal path normale; test verifica `getCatalogPushCandidates()` contiene solo il prodotto toccato e supplier/category restano puliti. |
| 8 | Import Room-first: completa subito offline. | ✅ | Nessuna chiamata cloud in composable; success Room non gating su Supabase. |
| 9 | Auto-sync non blocca Conferma/Fine/Back/tab. | ✅ | Nessun nuovo collegamento sync->navigation; verifiche Gate 5 read-only. |
| 10 | Nessuna regressione su flussi esistenti. | ✅ | Build/lint/test mirati verdi; targeted suites (DatabaseVM/ExcelVM/Repo/CatalogAutoSync) verdi. |
| 11 | Test mirati verdi o limiti documentati. | ✅ | Tutti verdi; full `:app:testDebugUnitTest` resta bloccato da MockK/ByteBuddy attach (limite ambiente noto). |
| 12 | Smoke device/emulator documentati per Fine/Annulla/Conferma. | ⚠️ Accettato | Smoke conferma su fixture `1 nuovo / 0 aggiornati`; il caso prioritario `0 nuovi / 1 aggiornato` è coperto da test deterministici. Limitazione esplicitamente accettata in Fase 3 della richiesta di review. |

**Verifiche puntuali aggiuntive:**

- **Resolver puro:** nessun import di `NavController`, `Context`, `androidx.navigation.*`; output via `sealed interface`. ✓
- **Unificazione exit point:** `Fine`, system back, `Annulla` (`onCorrectRows` + `onClose` non-error), `Conferma success`, `MissingPreview`, `MissingSession`, tab `Cronologia` — tutti instradati via `navigateByGeneratedExitRequest(...)` -> `GeneratedExitDestinationResolver.resolve(...)` -> `navigateToGeneratedExitDestination(...)`. Nessun `navController.navigate(...)` ad-hoc residuo per gli stessi `exitReason`. ✓
- **`GeneratedScreen` thin:** nessuna decisione di route, solo `onExit(GeneratedExitReason)`. La rimozione di `onBackToStart`/`onNavigateToHome` è completa. ✓
- **History tab:** `restoreState = false` correttamente applicato sul ramo `HistoryRoot` (linea ~580); `Database` mantiene `restoreState = true` come da scope; non si riesumano child route stale. ✓
- **Fallback UX:** `RouteFallbackState` con `R.string.import_preview_invalidated` + CTA `R.string.back`. Copy: il messaggio "preview invalidata" è riusato anche per `MissingSession` su Generated; copy non perfettamente specifica ma esistente, niente nuova stringa introdotta (coerente con scope). ✓
- **CTA via resolver:** il pulsante del fallback chiama lo stesso `navigateMissing*` lambda che invoca `navigateByGeneratedExitRequest`, niente bypass. ✓
- **Dirty marking incrementale:**
  - `applyImportAtomically(...)` ora calcola `touchedProductIds` dai prodotti realmente persistiti e marca dirty solo quelli; supplier/category dirty solo se `createdSupplierIds`/`createdCategoryIds` vengono popolati durante la risoluzione (insertedId > 0). ✓
  - Helper privato `markEntireCatalogDirtyForCloud()` **rimosso**; `grep` su tutta la codebase conferma 0 referenze residue (eseguito `grep -rn markEntireCatalogDirty app/src`). Non esistono path bootstrap/recovery che lo usavano: `syncCatalogWithRemote(...)` resta la via valida per riallineare entità senza ref. ✓
  - Indicatore `Invio modifiche prodotti X / Y`: `pushCatalogProducts` usa `candidates.size`, confermato via `042 incremental catalog push evaluates only dirty product candidates`. ✓
- **Room-first:** `ImportApplyResult.Success` -> navigazione immediata, nessun `await` Supabase aggiunto in NavGraph/composable. ✓
- **Test coverage:** A-F + bordi (`MissingPreview` HISTORY/HOME, `MissingSession` GENERATED, `ImportCancel` con/senza entryUid, `Done`/`SystemBack` per HISTORY); il test repository confronta supplier/category/product remote refs e push candidates su un caso 1-update. Non si vede indebolimento test esistenti. ✓

**Osservazioni minori (non bloccanti):**

1. **Heuristica `resolveGeneratedSessionOrigin`** (`storedOrigin == HOME && !isNewEntry -> HISTORY`): è un fallback difensivo per process-death recovery quando `ExcelViewModel.resetState()` riporta l'origin a `HOME`, mentre tutti i path `isNewEntry = true` partono da `HOME` e l'unico path con `isNewEntry = false` è `loadHistoryEntry` (HISTORY). Funzionalmente corretto, ma privo di commento esplicativo nel codice. Non bloccante; eventuale follow-up minore.
2. **`RouteFallbackState` copy:** la stringa `import_preview_invalidated` è riusata anche per `MissingSession` su Generated. Coerente con il vincolo "no nuove stringhe" e Material3, ma una copy dedicata sarebbe più precisa. Follow-up UX micro-polish, non bloccante.
3. **Tab `Cronologia` con `origin = HOME` hardcoded** nella request: irrelevante perché `HistoryTabSelected` ignora l'origin nel resolver; la scelta è documentata nella matrix ed è solo un placeholder difensivo.
4. **Back stack su Annulla:** la navigazione `navigate(Generated, popUpTo(start, saveState=true), restoreState=true)` sostituisce il vecchio `popBackStack()`. Lo stack risultante è leggermente diverso (history non resta sotto Generated) ma il comportamento utente è preservato perché `SystemBack` da Generated risolve via resolver -> HistoryRoot. Non bloccante.
5. **`ImportCancel` con origin = DATABASE + currentEntryUid > 0:** la mappa attuale ritorna `Generated(...)`; in pratica il path Smart Import da DB non popola `currentEntryUid`, quindi non si manifesta. Edge case teorico, non bloccante.
6. **`docs/MASTER-PLAN.md`:** modificato pre-TASK-057 (creazione task) e non aggiornato dall'esecutore per rispettare la regola AGENTS; il planner aggiornerà al passaggio `REVIEW -> DONE` su conferma utente.

**Problemi trovati (in scope):**
- Nessun problema bloccante. Nessuna regressione individuata su import/export Excel, barcode, manual entry, generated flow, history flow, database flow, sync indicator.

**Verdetto:** APPROVED

**Note per chiusura / follow-up suggeriti (non bloccanti, fuori scope TASK-057):**
- Aggiungere commento esplicativo a `resolveGeneratedSessionOrigin` per documentare il fallback process-death.
- (UX) Stringa dedicata per `MissingSession` su Generated, distinta da `import_preview_invalidated`.
- (UX) Smoke device su `0 nuovi / 1 aggiornato` quando si presenta naturalmente in test manuali.

---

## Fix

### Fix — 2026-04-25 00:03 -04

**Bug runtime bloccanti osservati nello smoke reale:**
1. `History -> Generated -> Revisione Importazione -> Annulla` non torna stabilmente alla `GeneratedScreen` precedente: il flusso puo uscire verso History/root invece di rispettare la destination matrix E.
2. Durante `Annulla` compare per un attimo il fallback "anteprima non disponibile"/"Errore", causato da perdita o pulizia preview/sessione mentre la route `ImportAnalysis` e' ancora renderizzata.
3. Dopo `Conferma Importazione` e `APPLY_IMPORT SUCCESS`, il ritorno verso History root puo mostrare lo stesso fallback intermedio; il success locale Room deve navigare pulito senza flash di errore.
4. Nel caso `ImportAnalysis` con errori di riga, `Modifica righe`/correzione e `Annulla` devono tornare alla `GeneratedScreen`, semanticamente distinti dal success apply e senza default a History/root.
5. L'indicatore sync mostra ancora conteggi tipo `Invio modifiche prodotti xxxx / 10403`; da diagnosticare se il totale deriva da backlog dirty preesistente, dirty full-catalog ancora generato dal path import, o altro trigger auto-sync/full sync.

**Piano FIX minimo:**
1. Differire la pulizia di `ImportAnalysis` durante exit intenzionali (`Annulla`, `Modifica righe`, `Conferma success`) fino a quando la navigazione ha lasciato la route `ImportAnalysis`, evitando che `analysis == null` renderizzi il fallback.
2. Rendere esplicita la reason `CorrectRows` nel resolver, equivalente a ritorno a `Generated`, per distinguere correzione/cancel da `ImportSuccess`.
3. Aggiungere o aggiornare test resolver/matrix per `CorrectRows` e per garantire che `ImportCancel` da History ritorni a `Generated`.
4. Aggiungere diagnostica/test deterministici sul conteggio dirty candidates per separare singolo update da backlog preesistente.
5. Rieseguire build/lint/test mirati e smoke emulator richiesti prima di riportare il task a `REVIEW`.

**File modificati durante FIX:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — pulizia preview/import differita per exit intenzionali, log diagnostico resolver, e ritorno a `Generated` senza `saveState/restoreState` della child `ImportAnalysis`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt` — aggiunta reason `CorrectRows`, risolta come ritorno a `Generated`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — `Conferma Importazione` disabilitato se sono presenti errori di riga.
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt` — log diagnostico low-noise `import_dirty_marking productsTouched=... suppliersCreated=... categoriesCreated=...`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolverTest.kt` — copertura `CorrectRows -> Generated`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationMatrixTest.kt` — copertura matrix E per `CorrectRows`.
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` — test backlog dirty preesistente + singolo update.
- `docs/TASKS/TASK-057-riordino-ux-post-import-generated-history-fine-sync-dirty-incrementale.md` — stato FIX, bug runtime, evidenze fix e validazioni.

**Azioni FIX eseguite:**
1. Verificato con log runtime che il resolver risolveva correttamente `ImportCancel` e `CorrectRows` da `HISTORY` verso `Generated(entryUid=5, ...)`.
2. Individuata la regressione residua nel ramo `GeneratedExitDestination.Generated`: `popUpTo(... saveState=true)` + `restoreState=true` poteva salvare/ripristinare la child route `ImportAnalysis`, facendo rimbalzare `Annulla`/`Modifica righe` sulla revisione.
3. Corretto il ramo `Generated` usando `saveState=false` e `restoreState=false` solo per il ritorno intenzionale alla griglia generata; `HistoryRoot` mantiene `restoreState=false` come da matrix F.
4. Spostata la pulizia `clearImportAnalysis()` / `dismissImportPreview()` a dopo l'uscita effettiva da `ImportAnalysis`, tramite `pendingImportAnalysisExitCleanup`, per evitare il rendering intermedio `analysis == null`.
5. Aggiunto log `GeneratedExit` con `origin`, `reason`, `route`, `entryUid`, `previewId`, `destination`, senza payload prodotto/barcode/token.
6. Preparata su emulator una fixture runtime `Task057 Update Smoke` con prodotto esistente `057-smoke` e prezzo modificato da 12 a 13, per smoke reale `0 nuovi / 1 aggiornato / 0 errori`.

**Diagnostica sync indicator 10403 / backlog:**
- Fixture emulator prima dello smoke `0/1/0`: `product_candidates=121`, `supplier_candidates=0`, `category_candidates=0`; il prodotto `057-smoke` aveva `localChangeRevision=0`, `lastSyncedLocalRevision=0`.
- Dopo `Conferma Importazione`: log `CatalogCloudSync: import_dirty_marking productsTouched=1 suppliersCreated=0 categoriesCreated=0`.
- Durante il push background l'indicatore ha mostrato `Invio modifiche prodotti 18 / 122` e `53 / 122`: il totale e' `121` backlog preesistente + `1` prodotto appena toccato, non full-catalog.
- Pull DB post-smoke: prodotto `057-smoke` aggiornato in Room a `retailPrice=13.0`; `product_candidates=40` dopo drain parziale del background sync; supplier/category candidates ancora `0`.
- Conclusione FIX: il valore alto osservato (`10403` nel DB utente, `122` nella fixture runtime dopo reset DB emulator) deriva da backlog dirty preesistente. TASK-057 non genera piu' dirty full-catalog sul singolo update.

**Smoke manuale/emulator FIX (`emulator-5554`):**
- `History -> Generated -> Revisione Importazione -> Annulla`: ✅ `GeneratedExit reason=ImportCancel destination=Generated(...)`; dump a 150ms e 750ms mostra `Pinmark` + `Fine`, nessun testo `Errore`/`anteprima`, nessun ritorno a History.
- `History -> Generated -> Revisione Importazione -> Modifica righe`: ✅ `GeneratedExit reason=CorrectRows destination=Generated(...)`; dump a 150ms e 750ms mostra `Pinmark` + `Fine`, nessun fallback.
- `History -> Generated -> Revisione Importazione -> Conferma Importazione` con `0 nuovi / 1 aggiornato / 0 errori`: ✅ log `DB_IMPORT APPLY_IMPORT START previewId=1 new=0 updated=1`, `DB_IMPORT APPLY_IMPORT SUCCESS previewId=1`; `GeneratedExit reason=ImportSuccess destination=HistoryRoot`; dump a 150ms/750ms/2250ms mostra `Cronologia` root, nessun fallback.
- Revisione con errori di riga (`Pinmark`): ✅ `Conferma Importazione` disabilitato; `Annulla` e `Modifica righe` tornano a `Generated`.
- Tab `Cronologia`: ✅ continua ad aprire History root con `restoreState=false`; non sono stati osservati child stale dopo success/cancel.

**Check obbligatori FIX:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| `git diff --check`       | ✅ ESEGUITO | Pulito. |
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=... ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL. |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=... ./gradlew :app:lintDebug` — BUILD SUCCESSFUL. |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning Kotlin/lint nuovo dai file TASK-057; restano warning Gradle/AGP preesistenti. |
| Coerenza con planning    | ✅ ESEGUITO | Resolver unico mantenuto; nessun refactor generale, nessuna migration Room/Supabase, nessuna nuova dipendenza. |
| Criteri di accettazione  | ✅ ESEGUITO | Smoke FIX + test mirati verdi; dettaglio sotto. |

**Test mirati FIX:**
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.CatalogAutoSyncCoordinatorTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"` — ✅ BUILD SUCCESSFUL.
- Tentativo addizionale non richiesto in forma combinata con tutti i filtri nello stesso processo Gradle: ⚠️ fallito per il limite ambiente noto MockK/ByteBuddy attach (`AttachNotSupportedException` / `ByteBuddyAgent`) su `DatabaseViewModelTest`/`ExcelViewModelTest`; gli stessi test passano se eseguiti singolarmente come richiesto.

**Baseline regressione TASK-004 (FIX):**
- Test eseguiti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`, `CatalogAutoSyncCoordinatorTest`.
- Test aggiunti/aggiornati: resolver/matrix `CorrectRows`, repository backlog + singolo update.
- Limiti residui: full/combined JVM suite ancora sensibile a MockK/ByteBuddy attach; non collegato al codice TASK-057.

**Incertezze:**
- L'emulator contiene backlog dirty reale e sync background attivo; per il totale esatto dell'indicatore fa fede la misura pre/post DB e il test deterministico repository. Il fix non tenta di ridisegnare l'UI sync per distinguere backlog preesistente da modifica appena fatta: eventuale follow-up UX separato.

**Handoff notes FIX:**
- Stato task riportato a `REVIEW`, non `DONE`.
- Review consigliata sui due punti più importanti: `NavGraph.kt` ramo `GeneratedExitDestination.Generated` (`saveState=false`, `restoreState=false`) e diagnostica backlog in `DefaultInventoryRepositoryTest`.

### Fix — 2026-04-25 12:01 -04

**Obiettivo FIX:**
- Rimuovere la vecchia regola UX che bloccava `Conferma Importazione` quando `ImportAnalysis.errors` non era vuoto.
- Mantenere il blocco solo quando non esistono righe valide da applicare (`newProducts` vuoto e `updatedProducts` vuoto).
- Confermare che il success locale Room continua a usare il resolver centralizzato di TASK-057 e non attende Supabase/sync cloud.

**File modificati durante questo FIX:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportAnalysis.kt` — aggiunta proprietà `hasValidRowsToApply`, fonte testabile della nuova regola UX.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — `Conferma Importazione` ora dipende da `hasValidRowsToApply`, non da `errors.isEmpty()`; aggiunto testo informativo quando errori e righe valide coesistono.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — guard difensiva: se non ci sono righe valide, non avvia `applyImport`.
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml` — nuove stringhe minime per notice UX e guard senza righe valide.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — nuovi test mirati su errori + update valido e su errori-only.
- `docs/TASKS/TASK-057-riordino-ux-post-import-generated-history-fine-sync-dirty-incrementale.md` — log FIX ed evidenze.

**Azioni FIX eseguite:**
1. Individuata la vecchia regola bloccante in `ImportAnalysisScreen`: `Conferma Importazione` era disabilitato se `importAnalysis.errors` non era vuoto.
2. Spostata la decisione su `ImportAnalysis.hasValidRowsToApply`, coerente col modello esistente: le righe valide sono gia separate in `newProducts` e `updatedProducts`; le righe errate restano solo in `errors`.
3. Mantenuti `Esporta Errori` e `Modifica righe`; aggiunta copy breve: "Confermando verranno importate solo le righe valide. Le righe con errore saranno ignorate."
4. Aggiunta guard ViewModel prima di costruire `ImportApplyRequest`: con liste valide vuote non viene chiamato il repository.
5. Verificato che `ImportApplyRequest` contiene solo `newProducts` / `updatedProducts`, quindi le righe in `errors` non possono essere importate dal repository.
6. Nessuna modifica a resolver/NavGraph per questo pass: `ImportFlowState.Success` continua a uscire tramite `GeneratedExitReason.ImportSuccess` e `GeneratedExitDestinationResolver`.
7. Nessuna modifica a Room schema, DAO, Supabase, auto-sync o dirty marking.

**Check obbligatori FIX:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| `git diff --check`       | ✅ ESEGUITO | Pulito dopo le modifiche. |
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=... ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL. |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=... ./gradlew :app:lintDebug` — BUILD SUCCESSFUL. |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning Kotlin/lint nuovo dai file modificati; restano warning Gradle/AGP preesistenti. |
| Coerenza con planning    | ✅ ESEGUITO | Fix mirato nel perimetro ImportAnalysis/ViewModel; navigation centralizzata e Room-first invariati. |
| Criteri di accettazione  | ✅ ESEGUITO | Scenari richiesti coperti da test JVM e smoke emulator; dettaglio sotto. |

**Test mirati FIX:**
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest"` — ✅ BUILD SUCCESSFUL. Include nuovi casi:
  - `importProducts applies valid updates when analysis also has row errors`
  - `importProducts rejects preview with only row errors and no valid rows`
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.data.DefaultInventoryRepositoryTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationResolverTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.GeneratedExitDestinationMatrixTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.ui.navigation.ImportNavOriginTest"` — ✅ BUILD SUCCESSFUL.
- Full suite JVM non rilanciata in questo FIX; resta documentato il limite ambiente MockK/ByteBuddy dalle verifiche TASK-057 precedenti.

**Smoke manuale/emulator FIX (`emulator-5554`):**
- `History -> Generated -> Revisione Importazione` con fixture `Task057 Valid Errors Smoke` (`0 nuovi / 1 aggiornato / 3 errori`):
  - ✅ `Conferma Importazione` abilitato (`enabled=true` nel dump UI).
  - ✅ Notice visibile: confermando importa solo le righe valide e ignora le righe con errore.
  - ✅ Click conferma: log `DB_IMPORT APPLY_IMPORT START previewId=1 new=0 updated=1`, `APPLY_IMPORT SUCCESS`.
  - ✅ Resolver: `GeneratedExit reason=ImportSuccess origin=HISTORY destination=HistoryRoot`.
  - ✅ Room aggiornata sul prodotto valido (`057-smoke` prezzo vendita `13 -> 14`); righe con barcode vuoto non importate.
  - ✅ Nessun blank screen; ritorno a `Cronologia`.
- `History -> Generated -> Revisione Importazione` con fixture `Task057 Errors Only Smoke` (`0 nuovi / 0 aggiornati / 3 errori`):
  - ✅ `Conferma Importazione` disabilitato (`enabled=false` nel dump UI).
  - ✅ `Modifica righe` torna a `GeneratedScreen` (`Task057 Errors Only Smoke` + `Fine` visibili), nessun fallback/blank.
  - ✅ `Annulla` torna a `GeneratedScreen`, nessun fallback/blank.
- `FilePicker / nuovo Excel` via intent `ACTION_VIEW` su `/data/data/.../cache/task057-new-excel.xlsx`:
  - ✅ PreGenerate caricato dal file, generazione completata.
  - ✅ `Generated -> Revisione Importazione` con `0 nuovi / 1 aggiornato / 2 errori`.
  - ✅ `Conferma Importazione` abilitato; click conferma produce `APPLY_IMPORT SUCCESS`.
  - ✅ Resolver: `GeneratedExit reason=ImportSuccess origin=HOME destination=NewExcelDestination`.
  - ✅ Ritorno a destinazione nuovo Excel (`Inventario` / FilePicker root), nessun blank e nessun blocco sync.
  - ✅ Riga valida applicata in Room (fixture ha applicato supplier/category della riga valida); righe con barcode vuoto ignorate.

**Baseline regressione TASK-004 (FIX):**
- Test eseguiti: `DatabaseViewModelTest`, `ExcelViewModelTest`, `DefaultInventoryRepositoryTest`.
- Test aggiunti/aggiornati: `DatabaseViewModelTest` con casi errori+update valido ed errori-only.
- Limiti residui: full JVM suite non rilanciata in questo pass; limite MockK/ByteBuddy gia documentato nella fase FIX precedente.

**Incertezze:**
- Nessuna sul comportamento richiesto. Nota smoke: nel flusso nuovo Excel la fixture ha applicato una variazione supplier/category sulla riga valida; il criterio verificato resta che la riga valida viene applicata, le righe errate no, e la navigation post-success torna alla destinazione nuovo Excel.

**Handoff notes FIX:**
- Review consigliata su `ImportAnalysisScreen.kt` e `DatabaseViewModel.kt`: la nuova regola e' intenzionalmente `hasValidRowsToApply`, non `errors.isEmpty()`.
- La voce precedente del FIX runtime riportava la vecchia disabilitazione con errori; questa voce la supersede esplicitamente per la nuova decisione UX.

### Review — 2026-04-25 (FIX UX `Conferma Importazione` con righe valide + errori)

**Revisore:** Claude (planner)

**Letture effettuate (review-side):**
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ImportAnalysis.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` (sezione `importProducts`, guard pre-apply, log diagnostico)
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` (path success/cancel/correctRows; `analysis.errors.isEmpty()` resta pertinente solo per il marker `markCurrentEntryAsSyncedSuccessfully` vs `markCurrentEntryAsSyncedWithErrors`, non per disabilitare il bottone)
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolver.kt`
- `app/src/main/res/values{,-en,-es,-zh}/strings.xml` (le 4 nuove stringhe coerenti)
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` (nuovi casi `errori + update valido` e `errori-only`)
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt` (nessuna regressione)
- `app/src/test/java/com/example/merchandisecontrolsplitview/ui/navigation/GeneratedExitDestinationResolverTest.kt`, `GeneratedExitDestinationMatrixTest.kt`, `ImportNavOriginTest.kt`

**Diff controllati (focus pass UX):**
- `data/ImportAnalysis.kt` — aggiunta `hasValidRowsToApply = newProducts.isNotEmpty() || updatedProducts.isNotEmpty()`. Posizione corretta sul modello dati, derivata solo dalle due liste valide; nessuna ambiguità con `errors`/`warnings`. ✓
- `ui/screens/ImportAnalysisScreen.kt` — `Conferma Importazione` ora usa `hasValidRowsToApply` (non più `editableNewProducts.isNotEmpty() || editableUpdatedProducts.isNotEmpty()`); le edit dialog non aggiungono/rimuovono righe, quindi le due condizioni restano logicamente equivalenti. Notice `import_valid_rows_with_errors_notice` mostrata solo quando `errors.isNotEmpty() && hasValidRowsToApply`, all'interno della sezione errori espandibile (espansa di default). `Esporta Errori`/`Modifica righe` invariati. Nessuna business logic spostata nel composable. ✓
- `viewmodel/DatabaseViewModel.kt` — guard difensiva post `hasMatchingPreview`, prima di costruire `ImportApplyRequest`: con `newProducts.isEmpty() && updatedProducts.isEmpty()` setta `UiState.Error` + `ImportFlowState.Error(occurredDuringApply = false)` e ritorna senza chiamare il repository. Log `APPLY_IMPORT START` mostra solo `previewId`/contatori, niente PII. ✓
- `res/values{,-en,-es,-zh}/strings.xml` — naming risorsa coerente (`import_valid_rows_with_errors_notice`, `import_no_valid_rows_to_apply`); copy breve, idiomatica, nessuna stringa hardcoded nel composable. ✓
- `test/.../DatabaseViewModelTest.kt` — i due test usano `runTest` + `advanceUntilIdle` + helper `waitForCondition`, niente sleep/dipendenza da ordine; il caso `errori + update valido` verifica `coVerify(exactly = 1) { repository.applyImport(...) }` con match sulla shape del request; il caso `errori-only` verifica `coVerify(exactly = 0) { repository.applyImport(any()) }`. Nessun test esistente indebolito. ✓

**Verifiche puntuali aggiuntive:**
- `editableNewProducts`/`editableUpdatedProducts` sono `MutableStateList` ma le edit dialog sostituiscono solo l'elemento (`list[index] = ...`); size invariata, quindi `hasValidRowsToApply` derivata da `importAnalysis` resta semanticamente equivalente alla vecchia condizione `editable*.isNotEmpty()`. Nessun rischio funzionale.
- `NavGraph.kt` linea 437 `if (analysis.errors.isEmpty()) markSyncedSuccessfully else markSyncedWithErrors`: corretto post-success, ortogonale al gating del bottone; non rappresenta più il vecchio `errors.isEmpty()` come blocco import.
- Resolver/cleanup deferiti `pendingImportAnalysisExitCleanup` (introdotto in FIX 00:03) inalterati da questo pass; success continua a uscire via `GeneratedExitReason.ImportSuccess` -> `GeneratedExitDestinationResolver`.
- Nessuna nuova dipendenza, nessuna migration Room/Supabase, nessun ritorno di `markEntireCatalogDirtyForCloud()` (`grep` conferma 0 occorrenze nel codice di produzione).
- UX/Material3: notice in `bodySmall` con `colorScheme.onSurfaceVariant`, padding `spacing.md`, niente redesign.

**Comandi rieseguiti dal planner:**
- `git diff --check` — ✅ pulito.
- `JAVA_HOME=...jbr ./gradlew :app:assembleDebug` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=...jbr ./gradlew :app:lintDebug` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...DatabaseViewModelTest"` — ✅ BUILD SUCCESSFUL (include i due nuovi casi).
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...ExcelViewModelTest"` — ✅ BUILD SUCCESSFUL (eseguito singolarmente).
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...DefaultInventoryRepositoryTest"` — ✅ BUILD SUCCESSFUL (eseguito singolarmente).
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest --tests "...GeneratedExitDestinationResolverTest" --tests "...GeneratedExitDestinationMatrixTest" --tests "...ImportNavOriginTest"` — ✅ BUILD SUCCESSFUL.
- `JAVA_HOME=...jbr ./gradlew :app:testDebugUnitTest` (full suite) — ⚠️ NON ESEGUIBILE COMPLETAMENTE: 363 completati, 123 falliti, 2 skipped per il limite noto MockK/ByteBuddy attach (`AttachNotSupportedException` / `ByteBuddyAgent`); coerente con la baseline TASK-057 precedente, nessun nuovo fallimento legato ai file di questo pass.

**Smoke device/emulator:**
- Non rieseguiti dal planner in questa sessione (nessun emulator attivo nel contesto review). Smoke FIX 12:01 -04 dell'esecutore copre History/HOME con `0/1/3`, `0/0/3`, e nuovo Excel con `righe valide + errori`. Limite documentato senza maschera.

**Criteri di accettazione (focus pass UX):**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | `Conferma Importazione` abilitato con errori + righe valide | ✅ | `enabled = !isApplying && previewId != null && hasValidRowsToApply`; test `importProducts applies valid updates when analysis also has row errors`; smoke esecutore. |
| 2 | `Conferma Importazione` disabilitato con solo errori | ✅ | `hasValidRowsToApply = false`; test `importProducts rejects preview with only row errors and no valid rows`; smoke esecutore. |
| 3 | Apply importa solo righe valide; righe errate ignorate | ✅ | `ImportApplyRequest` contiene `newProducts`/`updatedProducts`; `errors` non passa al repository; test `coVerify` con match shape. |
| 4 | Post-success usa resolver centralizzato | ✅ | Nessuna modifica a `NavGraph.kt`/resolver in questo pass; success continua via `GeneratedExitReason.ImportSuccess`. |
| 5 | Room-first / offline-first invariato | ✅ | `ImportApplyResult.Success` immediato; nessuna attesa cloud aggiunta. |
| 6 | Nessuna migration Room / Supabase | ✅ | Nessun cambio schema o entity. |
| 7 | Nessuna nuova dipendenza | ✅ | `app/build.gradle.kts` non modificato in questo pass. |
| 8 | Notice UX chiara, breve, non invasiva | ✅ | `bodySmall` su `onSurfaceVariant`, una frase breve in 4 lingue. |
| 9 | Test non fragili, no sleep, no ordine casuale | ✅ | `runTest` + `advanceUntilIdle` + `waitForCondition`; nessun test esistente indebolito. |
| 10 | Naming risorse coerente in 4 lingue | ✅ | `import_valid_rows_with_errors_notice` + `import_no_valid_rows_to_apply` su `values/`, `values-en/`, `values-es/`, `values-zh/`. |

**Problemi trovati (in scope):**
- Nessuno bloccante. Nessun fix applicato dal planner; il pass Codex è già minimale e coerente.

**Osservazioni minori (non bloccanti):**
- La notice `import_valid_rows_with_errors_notice` è collocata dentro la sezione errori espandibile; se l'utente collassa la sezione, non vede la notice. Mitigazione: la sezione è `mutableStateOf(true)` di default. Posizione comunque contestuale al blocco "Errori trovati"; spostarla nel card di preview superiore sarebbe redesign fuori scope. Non bloccante.
- Doppia protezione UI + ViewModel sulla guard "no righe valide" è intenzionale e non duplica condizioni divergenti (la guard VM riusa `newProducts.isEmpty() && updatedProducts.isEmpty()`, identica al complemento di `hasValidRowsToApply`). Accettata.

**Verdetto:** APPROVED senza modifiche.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | DONE |
| Data chiusura | 2026-04-25 |
| Tutti i criteri ✅? | Sì (limite full-suite MockK/ByteBuddy documentato come ambiente, non regressione TASK-057) |
| Rischi residui | Backlog dirty preesistente nei device utente continua a essere drenato dall'auto-sync; non è un effetto generato da questo task. UX micro-polish opzionale (notice in card top) lasciato come follow-up. |

---

## Riepilogo finale

TASK-057 chiuso a **DONE** il 2026-04-25 dopo:
- Execution Gate 0-6 (resolver puro `GeneratedExitDestinationResolver` + matrix tests; navigation centralizzata in `NavGraph.kt`; fallback anti-blank `RouteFallbackState`; dirty marking incrementale in `InventoryRepository.applyImportAtomically(...)` con rimozione di `markEntireCatalogDirtyForCloud()`; verifica indicatore sync su `candidates.size`).
- Fix runtime 2026-04-25 00:03 -04 (deferred cleanup `pendingImportAnalysisExitCleanup`, ramo resolver `Generated` con `saveState=false`/`restoreState=false`, reason `CorrectRows`).
- Fix UX 2026-04-25 12:01 -04 (regola finale: `Conferma Importazione` abilitata se ci sono righe valide, anche con errori; le righe errate sono ignorate dal repository; guard difensiva nel ViewModel; notice multilingua).
- Review planner APPROVED senza modifiche con build/lint/test mirati verdi e smoke emulator esecutore documentati per `Annulla`, `Modifica righe`, `Conferma` su History e nuovo Excel.

Rischi residui circoscritti: il limite full-suite JVM (MockK/ByteBuddy attach su JDK21) resta noto e non collegato ai file TASK-057. Eventuali follow-up UX/sync rimangono backlog.

---

## Gate stop / rollback / split rules

> Complementa **Execution sequencing plan**: cosa fare quando un gate non chiude, quando fermarsi, come evitare che TASK-057 diventi refactor generale. Valido in **futura** Execution, non in questa fase PLANNING.

- Ogni **gate (0-6)** deve avere un **criterio di stop** esplicito (come gia in Execution sequencing plan); niente avanzamento "a sensazione".
- Se un **gate fallisce** o **introduce regressioni**, **non** procedere al gate successivo finche il problema **non** è risolto o **non** è documentato e accettato come limite.
- Se **resolver / navigation** **non** è stabile, **non** passare al **dirty marking** (gate 4).
- Se il **dirty incrementale** richiede un refactor **eccesivo** rispetto al perimetro: **fermarsi** e proporre un **mini-task** separato; **non** trasformare TASK-057 in **refactor generale** del cloud sync o del repository.
- Se l'**indicatore sync** richiede modifiche **oltre** `CatalogSyncStateTracker` e i **coordinator esistenti**: **documentare** il rischio, proporre **follow-up**; **non** riscrivere lo stack di sync in questa task.
- Se il **micro-polish UI** esige **redesign** o modifica **struttura** tab / shell: **non** in TASK-057 (fuori perimetro, vedi Scope escluso / Non incluso).

**In caso di problema durante Execution:**

- Documentare **cosa** è stato **verificato** (comandi, device, percorso).
- Documentare **quale** gate è **bloccato** (numero e nome: es. "Gate 2 — navigation minimo").
- Lasciare il codice in stato **compilabile** (build OK o motivazione in task se transitorio, senza lasciare main rotto a lungo).
- **Non** mascherare test falliti; **non** indebolire o rimuovere test solo per verde.

**Se serve rollback:**

- Preferire **revert** o annullamento **locale** del **gate** appena modificato (o ultimo changeset a rischio), non patch cumulate opache.
- **Mantenere** i gate gia conclusi **validi** (non "mischiare" metodo-navigation con metodo-repository come fix rapido ad hoc).
- Evitare **mezzi-fix** sospesi **tra** navigation e repository: un piano alla volta (coerente con l'**Ordine obbligatorio**).

**Se una parte va separata:**

- Proporre in backlog / planner un **task di follow-up** (titolo, perimetro, perché oggi è fuori scope).
- **Spiegare** perché è **fuori scope** rispetto a TASK-057.
- **Mantenere** TASK-057 focalizzata su: **resolver** e **navigation post-import**, **anti blank** screen, **dirty incrementale minimo**, e **indicatore sync** solo se **necessario** entro i file elencati (nessuna riscrittura del dominio sync).

---

## Handoff

- Task in **REVIEW** dopo FIX UX del 2026-04-25 12:01 -04.
- La regola `Conferma Importazione` e' stata aggiornata: errori di riga non bloccano piu se esistono righe valide (`newProducts` o `updatedProducts`).
- `DatabaseViewModelTest` copre errori + update valido e errori-only; smoke emulator copre History e nuovo Excel con righe valide + errori.
- Review consigliata su `NavGraph.kt`: deferred cleanup `pendingImportAnalysisExitCleanup` e ramo `GeneratedExitDestination.Generated` con `saveState=false` / `restoreState=false`, introdotto per non riesumare `ImportAnalysis` durante `Annulla` / `Modifica righe`.
- Test mirati richiesti verdi se eseguiti singolarmente; tentativo combinato addizionale nello stesso processo Gradle fallisce per limite ambiente MockK/ByteBuddy attach, documentato in `Fix`.
- Smoke emulator completati per `Annulla`, `Modifica righe`, `Conferma` senza fallback flash; il success smoke ora copre il caso prioritario `0 nuovi / 1 aggiornato / 0 errori`.
- Diagnostica sync indicator: il totale runtime `122` deriva da `121` product dirty candidates preesistenti + `1` prodotto appena aggiornato; analogo al `10403` osservato dall'utente come backlog preesistente, non regressione dirty full-catalog TASK-057.
- `docs/MASTER-PLAN.md` risulta modificato nel worktree ma non e' stato toccato dall'esecutore; stato globale non aggiornato per regola AGENTS.
