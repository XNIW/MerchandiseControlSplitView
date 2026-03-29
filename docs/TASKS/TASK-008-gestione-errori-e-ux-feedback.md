# TASK-008 — Gestione errori e UX feedback

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-008                   |
| Stato              | DONE                       |
| Priorità           | BASSA                      |
| Area               | UX / Error handling        |
| Creato             | 2026-03-28                 |
| Ultimo aggiornamento | 2026-03-28 — **DONE**: review completa post-Codex; seconda passata — pulizia commenti `NavGraph`/`HistoryScreen`; criteri accettazione e Handoff allineati; MASTER-PLAN backlog TASK-008 aggiornato; check Gradle rieseguiti in sessione review. |

---

## Dipendenze

- **TASK-001** (`DONE`) — bootstrap governance
- **TASK-007** (`DONE`, 2026-03-28) — precedente task attivo; nessuna dipendenza tecnica obbligatoria oltre governance

---

## Scopo

Eseguire un **audit** sistematico di **tutti** i feedback **user-visible** sull’**Android** (fonte primaria di perimetro e logica), indipendentemente dal canale:

- **Snackbar**
- **AlertDialog** / altri **dialog** Material/Compose
- **Toast** (se presente)
- Stati **inline**: errore, **empty**, **loading** (testi o placeholder visibili)
- **Loading / progress** bloccanti o semi-bloccanti (fullscreen, overlay, indicator con messaggio)
- **Artefatti esportati** con testo leggibile dall’utente (es. foglio/colonne di **export errori** in XLSX con stringhe hardcoded o non localizzate)
- **Superfici non solo “screen UI”** quando il testo è **visibile all’utente** tramite integrazioni di piattaforma, senza allargare lo scope a refactor o notifiche di sistema (vedi sotto e *Non incluso*):
  - testi in **share sheet** / **chooser** (`ACTION_SEND`, titoli o etichette passate al sistema)
  - **`Intent.EXTRA_SUBJECT`** / **`Intent.EXTRA_TEXT`** (e analoghi) mostrati o precompilati per l’utente
  - **nomi foglio / colonna** (e header tabellari) negli export che l’utente apre o condivide
  - altri testi esposti tramite API di sistema **effettivamente leggibili** dall’utente nel flusso

Obiettivo: **chiarezza**, **coerenza** del tono e **parità di localizzazione** laddove mancante — **senza** redesign di schermate, **senza** refactor architetturali e **senza** cambiare architettura dati oltre il minimo necessario per messaggi (vedi vincoli sotto).

Il perimetro **non** si limita a `ui/screens` e `ViewModel`: includere, quando pertinenti, **utility / helper** che generano output visibile all’utente (UI, file esportato o **testo passato al sistema** come share/Intent).

Includere nell’audit anche i casi in cui il feedback è **perso o non percepito**: il **ViewModel** (o layer equivalente) emette successo/errore, ma **navigazione**, **cambio route** o **completamento asincrono** avviene mentre la schermata attuale **non osserva** più quello stato — risultato possibile: nessun feedback per l’utente. In Execution va documentato nella matrice e valutato un **fix minimo** (es. snackbar su destinazione osservata, evento scoped, messaggio una tantum) **solo** se rientra nel perimetro senza refactor architetturale; altrimenti segnalare in Handoff.

Includere altresì il **rischio opposto**: **feedback duplicato** o **replay indesiderato** (stesso messaggio più volte, ripresentazione dopo **recomposition**, **config change** / **recreate** / **restore**, o ri-entro su schermata che **ri-osserva** uno stato ancora “caldo” o **consumato in modo non idempotente**). Vedi riga dedicata in Planning §1 e classificazione in matrice §2.

---

## Contesto

**Android** (questo repo) è la **fonte primaria** di perimetro, logica e stringhe da auditare. L’app usa Compose + Material3 e diversi ViewModel con `UiState` e messaggi d’errore. Percorsi come import Excel, database, export, history sono complessi; messaggi generici, duplicati o solo in inglese degradano la fiducia utente. **iOS** (`iOSMerchandiseControl`) resta solo **riferimento qualitativo** (tono, gerarchia del feedback), **non** fonte di logica né obbligo di allineamento testo-per-testo.

---

## Non incluso

- **Redesign** di schermate o layout; **nuovi flussi**; cambi a **navigation** salvo emergenza dimostrata e documentata
- **Refactor architetturali** (es. un unico bus errori globale, riscrivere layer stato) — fuori scope; solo fix **minimi** su messaggi/canale
- Modifiche a **DAO**, **repository**, **schema Room**, **NavGraph** salvo **emergenza dimostrata** (es. messaggio errato causato da eccezione non mappata — valutare caso per caso in Execution e documentare)
- **TASK-006** (robustezza errori *generici* import Excel a livello `ExcelUtils` / `ImportAnalysis`) — **non assorbire**; resta backlog separato; TASK-008 può incrociare messaggi UI/file ma non espandere lo scope tecnico di TASK-006
- **TASK-019** (localizzazione messaggi PriceHistory full-import) — **non assorbire**; backlog dedicato
- Trasformare TASK-008 in **task di test** automatizzato: la smoke checklist è solo **manuale mirata** sui flussi toccati
- **Notification** di sistema, canali notifica, **Foreground Service** persistenti o **refactor** ampi solo per “migliorare” la consegna del feedback — **fuori scope**; in TASK-008 solo **audit** e **fix minimi** a stringhe / canali **user-facing** già esistenti o strettamente necessari per chiarezza
- **Sheet names tecnici per round-trip import/export** (`”Products”`, `”Suppliers”`, `”Categories”`, `”PriceHistory”`) in `DatabaseViewModel.exportFullDbToExcel`: servono come **identificatori** usati anche da `detectImportWorkbookRoute` per la rilevazione automatica del tipo di file. **Non** rinominarli salvo evidenza che il valore corrente causi confusione utente **e** che il round-trip resti funzionante dopo la modifica — in quel caso documentare in matrice e testare. Header tecnici `”id”` / `”name”` nelle sheet Suppliers/Categories: **fuori scope** (non user-facing nel senso primario)

---

## File potenzialmente coinvolti (indicativi — confermati dal code review pre-Execution)

- `app/src/main/java/.../ui/screens/*.kt` — `DatabaseScreen`, `GeneratedScreen`, `HistoryScreen`, `ImportAnalysisScreen`, `FilePickerScreen`, `PreGenerateScreen`, `OptionsScreen`, ecc.
- `app/src/main/java/.../viewmodel/DatabaseViewModel.kt` — origine di stringhe passate a UI, catch con `e.message` propagato a `UiState.Error`
- `app/src/main/java/.../viewmodel/ExcelViewModel.kt` — `loadError.value = e.message` (raw exception → UI)
- `app/src/main/java/.../util/ErrorExporter.kt` — **confermato** in scope: foglio `"Errori di Importazione"` (hardcoded IT), colonna `"Errore"` (hardcoded IT), `IOException` **catturata e silenziata** senza feedback utente
- `app/src/main/java/.../ui/navigation/NavGraph.kt` — flusso `ImportAnalysis → onConfirm → popBackStack()` (feedback async post-navigazione da verificare)
- Punti che costruiscono **share** / **`Intent`** — **confermato** in `GeneratedScreen.shareXlsx()`: `EXTRA_SUBJECT = "Inventario"` (hardcoded IT), `EXTRA_TEXT = "File generato dall'app 对货"` (hardcoded IT+ZH misto)
- `app/src/main/res/values/strings.xml`, `values-it/`, `values-es/`, `values-zh/` (o path effettivi nel repo)
- Eventuali `Theme` / componenti dialog riusati

**File ispezionati e confermati a basso rischio (no issue rilevante trovato):**
- `FilePickerScreen.kt` — nessun canale di feedback; solo navigazione. Nessun problema in scope.
- `OptionsScreen.kt` — nessun canale di feedback; azioni immediate (tema/lingua → `recreate()`). Nessun problema in scope.

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | In Execution: **matrice di audit obbligatoria** compilata (vedi Planning) per ogni voce nel perimetro toccato; **censimento separato** di tutti i messaggi user-visible che interpolano testo grezzo da eccezioni (§1bis); problemi classificati (vago, EN-only, inconsistente tra canali, **feedback perso post-nav/async**, **feedback duplicato / replay indesiderato**, **consumo evento non idempotente**, **failure senza feedback**, **asimmetria success/failure**, **canale solo per esito positivo**, **raw `exception.message` in UI**, ecc.) | S | **ESEGUITO** |
| 2 | Correzioni **minime** applicate: messaggi resi comprensibili e coerenti; stringhe spostate in risorse dove mancava; traduzioni allineate per le lingue supportate **nel perimetro toccato** | B/S | **ESEGUITO** |
| 3 | `./gradlew assembleDebug` OK; `./gradlew lint` senza nuovi warning non motivati sulle aree modificate | B/S | **ESEGUITO** |
| 4 | Nessuna regressione funzionale documentata; nessun cambio a DAO/repository/navigation salvo motivazione esplicita nel log Execution | S | **ESEGUITO** (eccezione documentata: `NavGraph` solo inoltro `historyActionMessage` / `consumeHistoryActionMessage`, senza cambio rotte) |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

> Riferimento: checklist **Definition of Done — task UX/UI** in `docs/MASTER-PLAN.md` dove applicabile.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Task attivato come unico **ACTIVE** dopo chiusura **TASK-007** `DONE` | Ordine backlog / priorità prodotto | 2026-03-28 |

---

## Planning (Claude)

### Vincoli operativi (da rispettare in Execution)

- **Niente** redesign schermate.
- **Niente** refactor architetturali.
- **Niente** modifiche a DAO / repository / navigation salvo **emergenza dimostrata** e tracciata nel log Execution.
- **Non assorbire** **TASK-006** né **TASK-019** (scope e backlog restano distinti).
- **iOS** (`iOSMerchandiseControl`): solo **riferimento qualitativo** di tono e gerarchia del feedback; **non** fonte di logica né obbligo di porting.

### 1. Perimetro audit — tutti i feedback user-visible

Prima di **EXECUTION**, mappare nel codice (grep + lettura mirata) ogni occorrenza rilevante, includendo almeno:

| Canale | Esempi di ricerca / attenzione |
|--------|--------------------------------|
| Snackbar | `Snackbar`, `showSnackbar`, `SnackbarHost` |
| Dialog | `AlertDialog`, `Dialog`, bottom sheet con messaggio di errore |
| Toast | `Toast`, `makeText` |
| Stati inline | `UiState.Error`, messaggi in empty state, errori in campo/lista |
| Loading / progress | overlay fullscreen, `CircularProgressIndicator` + testo, dialog “please wait” |
| Export / file | celle o header in XLSX/CSV con testo per l’utente (es. **export errori** da Import Analysis); **nomi foglio e colonne** |
| Share / chooser / Intent | `ACTION_SEND`, `createChooser`, **`Intent.EXTRA_SUBJECT`**, **`Intent.EXTRA_TEXT`**, etichette o titoli visibili all’utente nel flusso di condivisione |
| Feedback **perso** / non osservato | stato aggiornato nel **ViewModel** mentre l’utente ha già **navigato** o la **route** non ha più `collect`/observer sullo stesso flusso; **completamento async** dopo uscita dalla schermata che ha avviato l’azione |
| Feedback **duplicato** / **replay indesiderato** | stesso **success** o **error** mostrato **due o più volte**; **Snackbar** / **Toast** / **dialog** ripetuti dopo **recomposition**; replay dopo **config change**, **Activity recreate**, **process death + restore**; tornando su una schermata, **ri-osservazione** di uno stato che **rimane valorizzato troppo a lungo** o **non è stato consumato** in modo coerente; evento di feedback **non one-shot**; **consumo non idempotente** (stesso segnale riletto come “nuovo”) |
| Testo da **eccezione grezza** | `.message`, `localizedMessage`, `toString()` su `Throwable`, interpolazioni tipo `"${e.message}"` / `+ exception.message` verso UI o export user-visible; cfr. **§1bis** |

Per ogni **schermata / flusso** principale (Database, Generated, History, Import Analysis, file picker, PreGenerate, Options, ecc.): annotare **quali canali** sono usati e se il testo è **risorsa**, **hardcoded**, o **derivato da eccezione** non mappata.

**Mini verifica dedicata (obbligatoria in Execution, per flussi a rischio):**
- **feedback continuity across navigation / route change / async completion** — *chi osserva ancora il risultato?* Se nessuno → classificare **feedback perso** in **Problema**; **fix minimo** o Handoff.
- **Simmetricamente:** **duplicate feedback / unwanted replay** — il messaggio può ripresentarsi dopo **recomposition**, **rotazione/recreate**, **back** sulla stessa schermata, o perché lo stato **non è stato azzerato/consumato** dopo la prima emissione? Se sì → classificare in **Problema** (duplicato, replay, consumo non idempotente); **fix minimo** o Handoff se serve delivery **one-shot** più strutturale.

**Confronto locale:** per le stringhe toccate, verificare presenza in `values-it`, `values-es`, `values-zh` (stessi `name`); segnare gap **solo** per lingue già supportate dall’app (no espansione linguaggi fuori scope).

### 1ter Findings confermati dal code review pre-Execution (guida per l'audit)

Riscontri concreti emersi dalla lettura del codice, da usare come **punto di partenza** per la matrice di audit (non esaustivi):

| # | File / Flusso | Problema confermato | Tipo |
|---|---------------|---------------------|------|
| A | `ErrorExporter.kt:26,32` | Nome foglio `"Errori di Importazione"` e header colonna `"Errore"` — **hardcoded italiano** | Hardcoded user-facing (file export) |
| B | `ErrorExporter.kt:63-64` | `catch (e: IOException) { e.printStackTrace() }` — **failure silenziata**, nessun feedback per l'utente se la scrittura del file fallisce | Failure path senza feedback |
| C | `GeneratedScreen.kt:307-308` | `EXTRA_SUBJECT = "Inventario"`, `EXTRA_TEXT = "File generato dall'app 对货"` — **hardcoded IT+ZH misto**, non localizzato | Hardcoded user-facing (share/Intent) |
| D | `DatabaseViewModel.kt:199,228` | OOM catch → fallback `"Not enough memory to analyze this file"` — **hardcoded EN** prima del wrapping in `R.string.error_data_analysis` | Hardcoded (fallback stringa) |
| E | `ExcelViewModel.kt:263,300` | `loadError.value = e.message ?: ...` — **raw exception.message** assegnato direttamente come stato errore mostrato in UI (`PreGenerateErrorState`) | Raw exception in UI |
| F | `DatabaseViewModel.kt:291` | `handleImportAnalysisError`: `e.message ?: R.string.unknown` → wrappato in `R.string.error_data_analysis` — il testo da eccezione finisce nel messaggio utente | Raw exception in UI (wrappato) |
| G | `DatabaseViewModel.kt:345,477,591,737,742,820,833` | Pattern ricorrente: `e.message ?: R.string.unknown_error` → interpolato in `R.string.import_error` / `R.string.export_error` / `R.string.error_data_analysis` — eccezione grezza visibile all'utente come parte del messaggio | Raw exception in UI (wrappato, multipli) |
| H | `HistoryScreen.kt` | Rename e delete di `HistoryEntry` — **nessun feedback** (successo/errore) dopo l'azione; l'entry scompare/cambia silenziosamente | Asimmetria: azione utente senza conferma percepibile |
| I | `DatabaseViewModel.kt:175-178` | Sheet names export full-DB: `"Products"`, `"Suppliers"`, `"Categories"`, `"PriceHistory"` — EN-only ma servono come **identificatori tecnici** per round-trip import (vedi `detectImportWorkbookRoute`); **probabilmente fuori scope** salvo decisione diversa in Execution | Boundary check (tecnico vs user-visible) |
| J | `DatabaseScreen.kt:158`, `GeneratedScreen.kt:300` | Filename `"Database_${ts}.xlsx"` — prefisso `"Database"` EN-only nel nome file suggerito al SAF picker e nel file condiviso; visibile all'utente | Hardcoded (filename user-visible) |

> **Nota:** Questa tabella è una guida per l'esecutore, **non** la matrice di audit finale. In Execution va completata con le colonne obbligatorie di §2 e con qualsiasi ulteriore finding emerso durante l'audit completo.

### 1bis Messaggi derivati da eccezioni grezze (`Exception.message`, `IOException.message`, ecc.)

- **Censimento separato (obbligatorio in Execution):** oltre alle righe della matrice §2, elencare in una **sezione o tabella dedicata** tutti i punti in cui un messaggio **user-visible** (UI, file export, share/Intent, ecc.) **include o equivale** a testo tecnico/raw proveniente da eccezioni (es. `e.message`, `localizedMessage`, catene `cause`, template string con throwable). Obiettivo: non “perdere” i casi dove l’origine è `Origine = eccezione` ma il flusso è disperso nel codice.

- **Criteri di fix minimo (ordine di preferenza):**
  1. **Mapping** degli errori **noti** (tipo causa, codice dominio, branch prevedibili) verso **stringhe utente** in **`R.string.*`** con traduzioni allineate laddove toccato.
  2. **`Fallback` generico localizzato** quando il dettaglio dall’eccezione **non è affidabile**, **non è utile** all’utente finale, o varia troppo tra dispositivi/lingue fornitore.
  3. **Evitare** di mostrare **direttamente** `exception.message` (o equivalente grezzo) all’utente finale, **salvo** caso **già intenzionale** e **documentato** nella riga matrice (motivo + dove è stato concordato/review).

- **Classificazione obbligatoria** (per ogni voce che coinvolge un’eccezione): distinguere esplicitamente nella colonna **Esposizione eccezione / diagnostica** (§2):
  - **Messaggio tecnico esposto** — testo grezzo o semi-grezzo **visibile** all’utente.
  - **Messaggio tecnico solo nei log** — UI con messaggio sicuro/generico o assente; dettaglio solo in `Log` / diagnostica.
  - **Fallback utente corretto; dettaglio tecnico solo per diagnostica** — messaggio UI **localizzato e chiaro**; `message` eccezione (o stack) **solo** in log, non in copy utente.

### 2. Matrice di audit obbligatoria (da compilare in Execution)

Ogni riga del perimetro **effettivamente analizzato** (e ogni voce corretta) deve comparire in Execution in una tabella con almeno queste colonne:

| Colonna | Contenuto atteso |
|---------|------------------|
| **Screen / flow** | Schermata o flusso (es. PreGenerate, Import Analysis export errori) |
| **Trigger** | Azione utente o evento (es. import file invalido, export DB vuoto) |
| **Canale feedback** | Snackbar / Dialog / Toast / inline / loading / file esportato / **share sheet / chooser / Intent** / **nessuno (silenzioso)** / altro |
| **Testo attuale** | Testo mostrato (o estratto) così com’è |
| **Origine** | `strings.xml` (chiave) / **hardcoded** / **eccezione** (tipo; se UI mostra testo da `Throwable`: indicare es. **`message` grezzo**, `localizedMessage`, interpolazione) / misto |
| **Esposizione eccezione / diagnostica** | **N/A** (non da eccezione) / **messaggio tecnico esposto** (in UI o export visibile) / **messaggio tecnico solo nei log** / **fallback utente corretto; dettaglio tecnico solo diagnostica (log)** — vedi §1bis |
| **Localizzato?** | Sì / parziale / no (per le lingue in scope) |
| **Problema** | Es. vago, EN-only, incoerente con altro canale stesso flusso, **`exception.message` o raw throwable in UI**, dettaglio tecnico esposto, **feedback perso** (nav/route/async), **feedback duplicato**, **replay indesiderato** (recomposition / config change / recreate / restore / nav restore), **consumo evento non idempotente**, **`failure path` senza feedback utente**, **successo con `failure` silenzioso**, **canale presente solo per esito positivo e assente per quello negativo** |
| **Fix minimo proposto** | Es. spostare in `R.string.*`, allineare traduzioni, uniformare canale — **senza** redesign |

La matrice è **obbligatoria**; può essere spezzata per sezione (es. per schermata) se la tabella diventa lunga, mantenendo le stesse colonne (inclusa **Esposizione eccezione / diagnostica**). Le voci solo-**§1bis** (censimento eccezioni) devono comunque usare le stesse colonne o rimandare a una riga matrice esplicita.

### 3. Priorità di intervento (ordine consigliato)

1. **Testi hardcoded user-facing** (UI, file esportato, share/Intent, header foglio/colonna — dove visibile all’utente).
2. **`Failure path` senza feedback utente**; **success path con feedback ma `failure path` silenzioso**; **canale di feedback presente solo per esito positivo e non per quello negativo** — ripristinare **simmetria percettiva** con fix **minimi** (stesso canale o equivalente chiaro), senza redesign.
3. **Feedback perso** dopo **navigazione**, **cambio route** o **completamento async** (**feedback continuity** — vedi mini verifica §1): l’utente non vede successo/errore perché nessuna UI attiva osserva lo stato.
4. **Bilanciamento percezione — né perso né duplicato:** evitare sia **feedback perso** sia **feedback duplicato / replay indesiderato** (stesso messaggio più volte, replay post-recomposition o post-config/recreate/restore, ri-osservazione senza consumo coerente — vedi riga §1 e mini verifica §1). Intervenire con **fix minimi** aderenti all’architettura esistente (**nessun** bus globale o refactor ampio nel perimetro TASK-008); se serve una **delivery one-shot** più strutturale, **documentare in Handoff** (vedi §6).
5. **Fallback poco chiari o incoerenti** (messaggio generico che non aiuta, o diverso per lo stesso errore).
6. **Incoerenze tra canali di feedback** nella **stessa area** (es. stesso flusso: Snackbar vs dialog con toni o contenuti divergenti).
7. **Gap di localizzazione** nelle lingue **già** supportate (it / es / zh dove esiste baseline).
8. **Testo grezzo da eccezione in superficie utente** (`Exception.message`, `IOException.message`, `localizedMessage`, stringhe costruite con `Throwable`, ecc.): applicare §1bis — **mapping** noto → `R.string`; altrimenti **fallback generico localizzato**; **non** propagare `message` grezzo in UI salvo **intenzionale e documentato** in matrice. Comprende anche altri **dettagli tecnici** in UI (stack, nome tipo, path interni): mascherare in UI; mantenere diagnostica **solo nei log** dove serve.

### 4. Confine con messaggi interni (log, history tecnica)

- **Stringhe hardcoded** in **log** (`Log.d`, ecc.), **history tecnica** o diagnostica **solo per sviluppatore**: **fuori perimetro** di TASK-008 **salvo** che risultino **realmente user-visible** (es. stessa stringa riusata in UI, o export che l’utente apre).
- In Execution: se si incontrano hardcoded solo interni, **documentarli** in una riga tipo “fuori perimetro — non user-visible” senza obbligo di fix nel task.

### 5. Mini smoke checklist (solo flussi toccati — non è un task di test)

Dopo le modifiche, smoke **manuale mirata** solo sui flussi **effettivamente toccati** dall’Execution (non suite completa):

- Import **file invalido** o **vuoto**
- **Export DB** con database **vuoto** (messaggi / stati)
- **Sync / analysis** senza righe valide (o equivalente nel codice)
- **Export errori** da **Import Analysis** (incluso contenuto testuale file se applicabile; se il fix tocca `ErrorExporter`, verificare che un errore di scrittura **non** resti silenzioso — vedi finding B in §1ter)
- **Errore inline** in **PreGenerate** (o stati empty/error collegati; verificare che il messaggio mostrato in `PreGenerateErrorState` **non** sia un raw `e.message` — vedi finding E)
- Dove applicabile al perimetro toccato: scenario in cui un’azione async **completa dopo** un **cambio schermata** — verificare se il feedback resta **percepibile** (continuità §1)
- Dove applicabile: dopo **rotazione** / **tema** (recreate) o **torno indietro** sulla schermata — verificare **assenza di doppio** snackbar/dialog/toast per lo stesso evento (replay §1)

Assenza di regressione funzionale resta coperta dai criteri di accettazione e dai check AGENTS; questa lista **non** sostituisce test automatici.

### 6. Regole di intervento (Execution)

- **Preferenza:** riusare pattern già presenti (es. stesso `SnackbarHost`, durata, dismiss coerente).
- **Messaggi:** linguaggio utente finale; dettaglio tecnico in `Log` dove serve, non in UI salvo pattern già consolidato in app.
- **Fix minimi e coerenza:** preferire interventi **piccoli** e **allineati all’architettura attuale** (stesso ViewModel / stesso pattern di stato già usato nello schermo); **non** introdurre **bus globali** di eventi, **singleton** di feedback o **refactor ampi** della consegna messaggi solo per TASK-008.
- **Perso vs duplicato:** quando si corregge **continuity** o **consumo** messaggi, verificare di **non** introdurre **replay** (es. clear stato / one-shot dopo show, dove il pattern esistente lo consente senza redesign).
- **Handoff:** se il caso richiede una **revisione strutturale** della delivery **one-shot** (es. `Channel`, `SharedFlow` dedicato, riprogettazione scoped alla navigazione) **oltre** il fix minimo sicuro, **non** espandere lo scope: **documentare** in **Handoff** il flusso, il sintomo (perso/duplicato) e la soluzione proposta per un task futuro.
- **Eccezioni → UI:** seguire §1bis — **censimento separato**; in fix, preferire **mapping** tipi/cause noti a stringhe localizzate; usare **fallback generico localizzato** se il raw non è utile/affidabile; **vietato per default** mostrare `exception.message` (o equivalente) all’utente; se si mantiene, **documentare** nella matrice come intenzionale. Dopo il fix, la colonna **Esposizione eccezione / diagnostica** deve riflettere **fallback utente + solo log** o **solo log**, salvo eccezione documentata.
- **Non** allargare il task a refactor ViewModel o spostamento logica errori salvo micro-accoppiamento stringa ↔ risorsa (o mapping errore ↔ risorsa senza redesign del flusso).
- **Non** unificare tutti gli errori in un unico componente globale salvo evidenza che sia il **modo più piccolo** per soddisfare i criteri.

### 7. Rischi residui attesi

- Copertura **non** esaustiva di ogni riga in un solo task: documentare in **Handoff** ciò che resta.
- Alcuni messaggi dipendono da eccezioni dal repository: se il fix richiede touch repository, **documentare** e valutare spezzatura in task tecnico **solo** se inevitabile.
- Correggere **solo** il “perso” o **solo** il “duplicato” può peggiorare l’altro asse: valutare entrambi; se un equilibrio robusto richiede infrastruttura **one-shot** oltre il minimo, **Handoff** (non scope creep in TASK-008).
- **`ErrorExporter.exportErrorsToXlsx` silent failure** (finding B, §1ter): il fix minimo (propagare errore all’utente) richiede un meccanismo di callback o return value dalla funzione → potrebbe richiedere firma leggermente diversa; restare nel perimetro minimo (es. return Boolean + Toast nel chiamante) senza redesign.
- **Pattern `e.message` wrappato in `R.string`** (findings F-G): il testo grezzo dell’eccezione finisce comunque nel messaggio utente anche se wrappato (es. `”Errore analisi dati: java.io.FileNotFoundException: ...”`). Il fix minimo è un fallback generico localizzato; ma se il numero di catch-points è alto, il rischio è di non coprirli tutti in un solo task — documentare copertura effettiva vs residua in Handoff.

---

## Pre-EXECUTION (tracking)

- **Planning:** **consolidato** (2026-03-28) — audit user-visible completo nel file task (canali, share/Intent, export, feedback **perso** / **duplicato-replay**, raw eccezione §1bis, matrice §2, priorità §3, regole §6, smoke §5); **nessun allargamento di scope** rispetto al planning corrente.
- **Stato nel file task:** **`PLANNING`** (convenzione repo: resta tale finché l’esecutore non passa a **EXECUTION**).
- **Pronto per EXECUTION** (significato operativo, come **TASK-007** «pronto per EXECUTION»): l’esecutore (**Codex** / **AGENTS.md**) può iniziare **solo** dopo **`Stato` → EXECUTION** nel presente file e lettura **MASTER-PLAN** + task; **nessuna modifica a codice applicativo** è stata eseguita in questa chiusura governance.
- **MASTER-PLAN:** task **`ACTIVE`**, fase **`PLANNING`**, nota **planning consolidato — pronto per EXECUTION** (vedi backlog / stato globale).

---

## Execution

### Esecuzione — 2026-03-28

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/GeneratedScreen.kt` — localizzati `EXTRA_SUBJECT` / `EXTRA_TEXT`, riusato il prefisso filename da risorsa, aggiunto feedback di errore sugli export/share e consumo snackbar per rename/delete cronologia.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — sostituito il prefisso hardcoded `Database_` con la risorsa localizzata già esistente per l’export completo.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — aggiunto `SnackbarHost` locale per feedback rename/delete senza cambiare il flusso della schermata.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/ImportAnalysisScreen.kt` — gestito l’esito dell’export errori con toast di successo/failure coerenti.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — passato il messaggio one-shot di feedback cronologia a `HistoryScreen` senza introdurre nuovi pattern globali.
- `app/src/main/java/com/example/merchandisecontrolsplitview/util/ErrorExporter.kt` — localizzati nome foglio/header export errori e propagato l’esito di scrittura al chiamante; rimosso il failure path silenzioso.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — sostituiti i raw `exception.message` user-visible con mapping/fallback localizzati per analisi/import/export; resi espliciti i failure path con `openOutputStream == null`; allineati i messaggi salvati nello storico import per i branch toccati.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — sostituiti i raw `loadError` con fallback localizzati; aggiunto feedback one-shot locale per rename/delete cronologia; reso esplicito il failure path di export quando `openOutputStream` è nullo.
- `app/src/main/res/values/strings.xml` — aggiunte le sole chiavi nuove necessarie per export errori, fallback generici, share text e feedback cronologia.
- `app/src/main/res/values-en/strings.xml` — aggiunte le traduzioni inglesi per le nuove chiavi toccate.
- `app/src/main/res/values-es/strings.xml` — aggiunte le traduzioni spagnole per le nuove chiavi toccate.
- `app/src/main/res/values-zh/strings.xml` — aggiunte le traduzioni cinesi per le nuove chiavi toccate.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModelTest.kt` — aggiornati/estesi i test per i nuovi fallback import/export/analysis e per il log sicuro in failure.
- `app/src/test/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModelTest.kt` — aggiornati/estesi i test per feedback rename/delete e per il fallback localizzato di `loadError`.

**Azioni eseguite:**
1. Letti `MASTER-PLAN`, `TASK-008` e `CODEX-EXECUTION-PROTOCOL`; verificato che `TASK-008` fosse l’unico task attivo e impostato `Stato` → `EXECUTION`.
2. Confermati nel codice i finding del planning su share/export, `ErrorExporter`, `DatabaseViewModel`, `ExcelViewModel` e feedback cronologia; evitata l’apertura di file fuori perimetro non necessari.
3. Sostituiti i testi hardcoded user-visible in share/export (`GeneratedScreen`, `DatabaseScreen`, `ErrorExporter`) riusando risorse esistenti dove possibile e aggiungendo solo le chiavi strettamente necessarie.
4. Rimosse le esposizioni di `exception.message` nelle superfici utente toccate: `DatabaseViewModel` ora usa mapping/fallback localizzati per analisi/import/export; `ExcelViewModel` fa lo stesso per `loadError` usato da `PreGenerateScreen`.
5. Corretto il failure path silenzioso di export errori facendo restituire a `ErrorExporter` un esito booleano al chiamante e mostrando il feedback appropriato in `ImportAnalysisScreen`.
6. Corretto il failure path silenzioso di export/share quando `ContentResolver.openOutputStream(...)` restituisce `null`, così l’utente non riceve più un falso positivo di successo.
7. UI/UX: aggiunto un feedback snackbar minimo e coerente per rename/delete di `HistoryEntry` in `HistoryScreen` e `GeneratedScreen` tramite stato locale del `ExcelViewModel` consumato in modo one-shot (motivo: simmetria success/failure e migliore percezione dell’azione, senza bus globale né refactor architetturale).
8. Verificata la continuità del feedback post-navigazione per il perimetro toccato: il flusso `ImportAnalysis -> popBackStack() -> GeneratedScreen` continua a mostrare gli esiti di `DatabaseViewModel` sulla schermata osservante; nessuna modifica a navigation/DAO/repository/schema.
9. Aggiornata la baseline JVM/Robolectric di `TASK-004` con test mirati ai nuovi fallback e al nuovo feedback cronologia.

**Matrice audit (aree effettivamente toccate):**

| Screen / flow | Trigger | Canale feedback | Testo attuale | Origine | Esposizione eccezione / diagnostica | Localizzato? | Problema | Fix minimo applicato |
|---------------|---------|-----------------|---------------|---------|-------------------------------------|--------------|----------|----------------------|
| `GeneratedScreen` share XLSX | Tap su `Condividi` | Share sheet / chooser / `Intent.EXTRA_SUBJECT` / `Intent.EXTRA_TEXT` | Subject = `app_name`; body = `share_export_message`; filename = `export_database_filename_prefix + timestamp` | `strings.xml` | N/A | Sì (`values`, `values-en`, `values-es`, `values-zh`) | Subject/body hardcoded e testo misto IT+ZH; prefisso filename EN-only | Sostituiti hardcoded con risorse localizzate e prefisso filename già esistente |
| `GeneratedScreen` export/share failure | Export o share fallito | Toast | `error_export_generic` | `strings.xml` | fallback utente corretto; dettaglio tecnico solo diagnostica (log/eccezione) | Sì | Failure path senza feedback utente in caso di eccezione / `openOutputStream == null` | Aggiunto `try/catch` locale e fallback generico coerente |
| `DatabaseScreen` export full DB | Tap su export completo | Nome file suggerito dal SAF | `export_database_filename_prefix + timestamp` | `strings.xml` | N/A | Sì | Prefisso `Database_` hardcoded visibile all’utente | Riusata la risorsa localizzata già presente |
| `ImportAnalysisScreen` export errori | Tap su `Esporta Errori` | Toast + file esportato | Successo = `error_file_exported`; failure = `error_export_generic` | `strings.xml` + esito `ErrorExporter` | fallback utente corretto; dettaglio tecnico solo diagnostica (log) | Sì | Success path con feedback, failure path silenzioso | Il chiamante ora riceve l’esito e mostra un feedback simmetrico |
| `ErrorExporter` file XLSX errori | Scrittura file errori | File esportato | Nome foglio `import_error_export_sheet_name`; colonna finale `import_error_export_column_reason` | `strings.xml` | N/A | Sì | Foglio/header hardcoded in italiano | Spostati in risorse localizzate |
| `PreGenerateScreen` / `ExcelViewModel.loadError` | File multiplo invalido / incompatibile | Errore inline | Messaggio noto localizzato oppure fallback `error_unknown_file_analysis` / `error_adding_files` / `error_file_access_denied` | `strings.xml` + mapping locale in `ExcelViewModel` | fallback utente corretto; dettaglio tecnico solo diagnostica | Sì | Raw `e.message` mostrato in UI | Rimossi i raw throwable; mantenuti solo messaggi noti o fallback localizzati |
| `DatabaseViewModel` analisi import | Import file invalido / accesso negato / OOM | Snackbar / inline su schermata osservante | `error_data_analysis_generic`, `error_file_access_denied`, `error_file_too_large_or_complex` o messaggio noto già localizzato | mapping locale + `strings.xml` | fallback utente corretto; dettaglio tecnico solo diagnostica (log) | Sì | Raw `e.message` e fallback hardcoded EN in UI | Introdotto mapping locale minimo senza toccare repository/navigation |
| `DatabaseViewModel` import/export DB | Apply import o export fallito | Snackbar | `error_import_generic`, `error_export_generic`, `error_file_access_denied`, `error_file_too_large_or_complex` | mapping locale + `strings.xml` | fallback utente corretto; dettaglio tecnico solo diagnostica (log) | Sì | Raw `e.message` wrappato in stringhe utente | Sostituiti i wrapper con fallback completi localizzati |
| `HistoryScreen` / `GeneratedScreen` rename-delete cronologia | Rename o delete di `HistoryEntry` | Snackbar | `history_entry_renamed`, `history_entry_deleted`, `error_history_entry_rename`, `error_history_entry_delete` | `strings.xml` + stato locale `ExcelViewModel.historyActionMessage` | N/A | Sì | Azione silenziosa; nessuna simmetria successo/failure | Aggiunto feedback one-shot locale osservato in entrambe le schermate che usano la feature |

**Censimento separato messaggi da eccezioni grezze (§1bis):**

| Punto censito | Superficie utente | Prima | Dopo | Esposizione finale |
|---------------|-------------------|-------|------|--------------------|
| `DatabaseViewModel.startSmartImport` / `startImportAnalysis` / `handleImportAnalysisError` / `analyzeGridData` / `startFullDbImport` | Snackbar / inline `UiState.Error` | `e.message` o fallback hardcoded EN interpolati in `error_data_analysis` | Mapping minimo di messaggi noti + fallback `error_data_analysis_generic` / `error_file_access_denied` / `error_file_too_large_or_complex` | fallback utente corretto; dettaglio tecnico solo diagnostica |
| `DatabaseViewModel.importProducts` | Snackbar + storico import per branch fallito toccato | `e.message` interpolato in `import_error`; storico con testo tecnico | `error_import_generic` o fallback sicuro; storico allineato al messaggio utente sicuro per il branch toccato | fallback utente corretto; dettaglio tecnico solo diagnostica |
| `DatabaseViewModel.exportToExcel` / `exportFullDbToExcel` | Snackbar | `e.message` interpolato in `export_error` | `error_export_generic` / `error_file_access_denied` / `error_file_too_large_or_complex` | fallback utente corretto; dettaglio tecnico solo diagnostica |
| `ExcelViewModel.loadFromMultipleUris` / `appendFromMultipleUris` | Errore inline in `PreGenerateScreen` | `loadError.value = e.message` | Messaggio noto localizzato o fallback sicuro (`error_unknown_file_analysis`, `error_adding_files`, `error_file_access_denied`) | fallback utente corretto; dettaglio tecnico solo diagnostica |

**Verifica criteri di accettazione:**

| # | Criterio | Verifica | Stato | Evidenza |
|---|----------|----------|-------|----------|
| 1 | Matrice audit obbligatoria + censimento raw eccezioni | Static | ✅ ESEGUITO | Matrice compilata sopra solo per le aree toccate + sezione §1bis dedicata |
| 2 | Correzioni minime + stringhe in risorse + traduzioni allineate nel perimetro | Static | ✅ ESEGUITO | Fix applicati in `GeneratedScreen`, `DatabaseScreen`, `HistoryScreen`, `ImportAnalysisScreen`, `ErrorExporter`, `DatabaseViewModel`, `ExcelViewModel`; chiavi allineate in `values`, `values-en`, `values-es`, `values-zh` |
| 3 | `assembleDebug` OK + `lint` senza nuovi warning non motivati | Build / Static | ✅ ESEGUITO | `assembleDebug` SUCCESS; `lint` SUCCESS dopo allineamento Compose stringResource e traduzioni mancanti |
| 4 | Nessuna regressione funzionale; nessun cambio a DAO/repository/navigation salvo motivazione esplicita | Static | ✅ ESEGUITO | Nessun file DAO/repository/schema toccato; `NavGraph` modificato solo per inoltrare il feedback locale di cronologia, senza cambiare rotte o flussi |

**Check obbligatori:**
| Check                    | Stato | Note |
|--------------------------|-------|------|
| Build Gradle             | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew assembleDebug` → `BUILD SUCCESSFUL` |
| Lint                     | ✅ ESEGUITO | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew lint` → `BUILD SUCCESSFUL`; corretto un nuovo lint Compose e allineate le traduzioni mancanti |
| Warning nuovi            | ✅ ESEGUITO | Nessun warning/lint nuovo attribuibile ai fix introdotti; restano warning globali/preesistenti di AGP/Gradle e warning Kotlin legacy già presenti nel progetto |
| Coerenza con planning    | ✅ ESEGUITO | Fix limitati a share/export, raw exception UI, failure path silenziosi e feedback cronologia; nessun redesign o refactor architetturale |
| Criteri di accettazione  | ✅ ESEGUITO | Tutti i criteri verificati singolarmente nella tabella sopra |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests 'com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModelTest' --tests 'com.example.merchandisecontrolsplitview.viewmodel.ExcelViewModelTest'`
- Test aggiunti/aggiornati: `DatabaseViewModelTest` (fallback generici analisi/import/export + storico sicuro in failure), `ExcelViewModelTest` (feedback rename/delete cronologia + fallback `loadError`)
- Limiti residui: nessun test JVM dedicato ai composable/snackbar host; utile smoke manuale in review per share/export errori, rename/delete cronologia e invalid file su `PreGenerateScreen`

**Incertezze:**
- Nessuna incertezza bloccante.
- Smoke manuale UI non eseguita in questa sessione terminale; resta raccomandata in review sui flussi toccati dal task.

**Handoff notes (Execution):**
- In review successiva: smoke manuale su share/export errori, rename/delete cronologia, file invalido PreGenerate.
- Fuori scope: messaggi legacy non nel perimetro consolidato; delivery one-shot strutturale → task futuro se necessario.

---

## Review

### Review — 2026-03-28 (prima passata)

**Revisore:** Claude (planner)

**Criteri di accettazione:**
| # | Criterio | Stato | Note |
|---|-----------|-------|------|
| 1 | Matrice audit obbligatoria + censimento raw eccezioni | ✅ | Compilata in Execution per tutte le aree toccate; §1bis separato |
| 2 | Correzioni minime + stringhe in risorse + traduzioni allineate | ✅ | Fix applicati su tutti i file toccati; gap EN corretti in Fix (vedi sotto) |
| 3 | `assembleDebug` OK + `lint` senza nuovi warning non motivati | ✅ | `assembleDebug` SUCCESS; `lint` SUCCESS dopo Fix |
| 4 | Nessuna regressione funzionale; nessun cambio DAO/repository/navigation salvo motivazione | ✅ | Nessun file DAO/repository/schema toccato; `NavGraph` modificato solo per inoltro feedback cronologia |

**Problemi trovati (prima passata):**
1. **Bug localizzazione EN** (`values-en/strings.xml`): `untitled` / `exported_short` in italiano — corretto in Fix.
2. **Dead resources** non usati — rimossi in Fix.

**Verdetto (dopo Fix):** **APPROVED**

### Review — 2026-03-28 (seconda passata / chiusura)

**Revisore:** Claude (review completa post-Codex)

**Esito:** Codex Execution **conforme** a TASK-008. `knownUserFacingFileMessage` in `DatabaseViewModel` / `ExcelViewModel` usa `throwable.message` solo per **confronto** con messaggi già localizzati noti — non espone raw arbitrario in UI.

**Micro-pulizia applicata in review (non funzionale):** commenti di sviluppo e blocco commentato rimossi da `NavGraph.kt`; commenti «NUOVA» rimossi da `HistoryScreen.kt`.

**Verdetto finale:** **APPROVED** — task **DONE**.

---

## Fix

### Fix — 2026-03-28 (prima passata — localizzazione EN + dead resources)

**Esecutore fix:** Claude (planner/reviewer)

**Fix applicati:**

1. `app/src/main/res/values-en/strings.xml`:
   - `untitled`: "Senza titolo" → "Untitled"
   - `exported_short`: "Esportato" → "Exported"

2. Rimossi da tutti e 4 i file (`values/`, `values-en/`, `values-es/`, `values-zh/`):
   - `sheet_name_suppliers`, `sheet_name_categories`, `sheet_name_price_history` (unused, localized values would break round-trip)
   - `excel_header_*` (unused dead code)

### Fix — 2026-03-28 (seconda passata — pulizia commenti)

**File:** `NavGraph.kt`, `HistoryScreen.kt` — rimossi commenti noise / codice commentato morto (nessun cambio logica).

**Check post–seconda passata (sessione review 2026-03-28):** `JAVA_HOME` = JBR Android Studio — `./gradlew testDebugUnitTest --tests '…DatabaseViewModelTest' --tests '…ExcelViewModelTest' assembleDebug lint` → **BUILD SUCCESSFUL**.

**Check post-fix:**
| Check | Stato | Note |
|-------|-------|------|
| `testDebugUnitTest` (DatabaseViewModelTest + ExcelViewModelTest) | ✅ BUILD SUCCESSFUL | Tutti i test passano |
| `assembleDebug` | ✅ BUILD SUCCESSFUL | — |
| `lint` | ✅ BUILD SUCCESSFUL | Nessun nuovo warning attribuibile ai fix |

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **DONE** |
| Data chiusura          | 2026-03-28 |
| Tutti i criteri ✅?    | Sì (✅ tutti e 4) |
| Rischi residui         | Smoke manuale non eseguita su emulatore (UI/nav); fallback error generici by design — se in futuro serve diagnostica granulare, task dedicato |

---

## Riepilogo finale

Audit e fix completi nell'ambito del planning consolidato. Corretti share/export hardcoded, raw exception in UI, silent failure export errori, feedback rename/delete cronologia mancante. Fix reviewer: corretto bug `untitled`/`exported_short` in EN e rimossi dead resources (`sheet_name_*`, `excel_header_*`) aggiunti da Execution ma mai referenziati nel codice. Review finale: pulizia commenti `NavGraph.kt` / `HistoryScreen.kt`. Build, lint e test baseline TASK-004 tutti verdi (rieseguiti dopo pulizia).

---

## Handoff

- Task **`DONE`** (2026-03-28). Nessun task ACTIVE in coda da questo file.
- **Smoke manuale consigliata** (non bloccante per chiusura documentale): §5 planning — share `GeneratedScreen`, export errori `ImportAnalysisScreen`, rename/delete cronologia, PreGenerate file invalido, rotazione/back senza doppio snackbar.
- **Residui / follow-up opzionali:** `ImportAnalysis.kt` può ancora includere `ex.message` in `RowImportError` per export errori XLSX (fuori elenco file “toccati” da Codex in questa sessione); fallback generici ViewModel restano by design; diagnostica granulare utente → task futuro se richiesto.
- **File toccati in review finale:** `NavGraph.kt`, `HistoryScreen.kt` (solo pulizia commenti).
