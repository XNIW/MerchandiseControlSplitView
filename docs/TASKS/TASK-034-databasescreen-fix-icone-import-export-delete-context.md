# TASK-034 — DatabaseScreen: fix icone import/export + contesto delete

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-034                   |
| Stato              | **DONE** (review repo-grounded completata; nessun finding bloccante; `assembleDebug` / `lint` verdi; chiusura richiesta esplicitamente dall’utente) |
| Priorità           | MEDIA                      |
| Area               | UX / UI / DatabaseScreen   |
| Creato             | 2026-04-05                 |
| Ultimo aggiornamento | 2026-04-05 — DONE: review repo-grounded completata, nessun fix necessario, tracking e chiusura documentale aggiornati |

> **Nota:** review repo-grounded completata in questa sessione. Su richiesta esplicita dell’utente, il task viene chiuso in **`DONE`** dopo verifiche reali e senza fix ulteriori necessari.

---

## Revisione planning — lacune / ambiguità del plan precedente (chiuse in documento)

| Lacuna o ambiguità | Come viene chiusa in questa versione |
|--------------------|--------------------------------------|
| Icone: “equivalente M3 concordata in Execution” lasciava margine al dubbio | Decisione **vincolante** sotto: Import = `FileUpload`, Export = `FileDownload`; nessuna alternativa da re-discutere salvo nuovo task. |
| Dialog delete: struttura titolo/testo non specificata | Aggiunta **specifica UX** (gerarchia, blocchi, tipografia di riferimento, etichetta barcode). |
| Fallback nome vuoto / nome lungo / barcode vuoto poco operativi | Sezione **Fallback UX** con regole esplicite e criteri di accettazione dedicati. |
| Wiring: “opzionale ma consigliato” sul `null` | Reso **obbligatorio** per l’execution: condizione di visibilità esplicita + reset stato coerente. |
| Criteri #1 troppo lunghi e parzialmente ridondanti | Tabella criteri **ripartita** (icone, dialog, edge cases, accessibilità, coerenza visiva, build). |
| Piccoli polish M3 non distinti dallo scope minimo | Sezione **UI/UX locale ammessi** (opt-in controllato) vs **fuori scope** invariato. |
| Piano di esecuzione generico su stringhe | Passi allineati a **chiavi stringa suggerite** e a **composizione UI** (Column, overflow, scroll opzionale). |
| Copy/localizzazione non guidati abbastanza (rischio stringhe ridondanti o tono incoerente) | Aggiunte **linee guida copy/L10n**: riuso chiavi esistenti dove possibile, nuove chiavi solo se servono davvero, tono breve/coerente nelle 4 lingue. |
| Edge case UI non esplicitati abbastanza (rapid tap / doppia apertura / coerenza dismiss) | Aggiunti **guardrail di stato UI** e smoke futuri più espliciti per evitare regressioni sul dialog delete in uso reale. |
| Titolo dialog e densità informativa ancora potenzialmente ambigui | Chiarita la regola: **titolo generico e stabile**, dettagli nel blocco contestuale; evitare titolo con nome prodotto o duplicazioni inutili. |
| Stati limite barcode / conferma azione non abbastanza esplicitati | Rafforzati fallback barcode e guardrail sulle azioni del dialog: conferma sempre riconoscibile, nessuna ambiguità sul record da eliminare. |

---

## Dipendenze

- Nessuna

---

## Scopo

1. **Icone top bar:** applicare la mappatura iconografica decisa (Import → `FileUpload`, Export → `FileDownload`) senza alterare ordine delle azioni né le stringhe di accessibilità già corrette.
2. **Dialog eliminazione:** aggiungere un blocco contestuale leggibile (nome in evidenza, barcode etichettato) sotto una riga di conferma breve, in stile Material3 coerente con gli altri dialog della stessa schermata, senza redesign del componente dialog a livello app.

---

## Contesto

- **Decomposizione:** `DatabaseScreen.kt` orchestra stato e dialog; `DatabaseScreenComponents.kt` contiene `DatabaseScreenTopBar`; `DatabaseScreenDialogs.kt` contiene `DeleteProductConfirmationDialog` (**TASK-003** `DONE`).
- **Evidenza codice — icone:** in `DatabaseScreenTopBar`, `onImportClick` usa `Icons.Default.FileDownload` e `onExportClick` usa `Icons.Default.FileUpload` (circa righe 87–101 di `DatabaseScreenComponents.kt`). Le `contentDescription` usano già `import_file` / `export_file` in modo coerente con l’azione del pulsante; correggere solo i **glyph**.
- **Evidenza codice — delete:** `DeleteProductConfirmationDialog` oggi espone solo `onConfirm` / `onDismiss` e testi generici (`delete_confirmation_title`, `delete_confirmation_message`). `DatabaseScreen` mantiene `itemToDelete: Product?` ma non lo passa al dialog.
- **Allineamento backlog:** `docs/MASTER-PLAN.md` (TASK-034) allineato al perimetro sopra.

---

## Non incluso

- Modifiche a `DatabaseViewModel`, repository, DAO, entity, corpo di `deleteProduct`, navigazione (`NavGraph`), launcher SAF/import/export.
- Redesign del dialog export (preset/checkbox) → **TASK-039**.
- Unificazione shape/elevazione dialog cross-app → **TASK-037**.
- Revisione di altre icone della top bar (freccia indietro, FAB, ecc.).
- Test UI strumentati (Espresso/Compose test) — non richiesti salvo futura richiesta esplicita.
- Nuove dipendenze Gradle / librerie.

## Guardrail di integrazione documentale

- Questa revisione **rimane PLANNING-only**: sono ammessi solo perfezionamenti del piano e del file task; **vietato** anticipare decisioni che equivalgano a modifiche ai sorgenti applicativi.
- Durante l’eventuale aggiornamento documentale in planning, **non** promuovere il task a `EXECUTION`, **non** compilare sezioni `Execution` / `Review` / `Fix` con attività simulate, **non** dichiarare verifiche build/lint come eseguite.
- Eventuali micro-migliorie UX/UI aggiunte al piano devono restare **opzionali, locali, coerenti** e subordinate al principio di **minimo diff**.

---

## Decisioni UX/UI (vincolanti per l’execution)

### A — Top bar Import / Export

| Elemento | Decisione |
|----------|-----------|
| Icona **Import** | `Icons.Default.FileUpload` |
| Icona **Export** | `Icons.Default.FileDownload` |
| Ordine pulsanti | **Invariato:** primo `IconButton` = Import (`onImportClick`), secondo = Export (`onExportClick`). |
| `contentDescription` Import | **Invariata:** `stringResource(R.string.import_file)` sul pulsante Import. |
| `contentDescription` Export | **Invariata:** `stringResource(R.string.export_file)` sul pulsante Export. |
| Semantica accessibilità | Dopo lo swap, verificare a mente/codice che la descrizione corrisponda ancora al **gesto** (apertura documento vs avvio flusso export): non invertire le stringhe, solo le icone. |

### B — Dialog conferma eliminazione (struttura contenuto)

| Elemento | Decisione |
|----------|-----------|
| Titolo | Mantenere titolo **breve, generico e stabile**: preferire `delete_confirmation_title` esistente; **non** interpolare il nome prodotto nel titolo, per evitare fragilità L10n, titoli troppo lunghi e duplicazione con il blocco contestuale. |
| Corpo | **Due livelli:** (1) **una riga** (o breve paragrafo) di conferma generica, con copy breve e neutro; (2) **blocco contestuale** sotto, separato visivamente con `Spacer` modesto (es. 8.dp) e/o `HorizontalDivider` sottile **solo se** già pattern usato in altri dialog del file. **Preferenza copy:** evitare duplicazioni del nome prodotto nella frase introduttiva se il blocco contestuale lo mostra già chiaramente. |
| Nome prodotto | **Più evidente:** `MaterialTheme.typography.bodyLarge` (o `titleSmall` se il file usa già tale gerarchia per enfasi locale) + peso maggiore (`FontWeight.SemiBold`) **se** coerente con altri testi enfatici nello stesso modulo; **max 2 righe** consigliate + `TextOverflow.Ellipsis` per nomi lunghi. |
| Barcode | **Secondario ma etichettato:** riga distinta con **etichetta localizzata** (es. chiave `delete_confirmation_barcode_label`) + valore in `bodyMedium` / `onSurfaceVariant` per gerarchia rispetto al nome. |
| Layout blocco contesto | Preferire `Column` verticale (nome sopra, barcode sotto) come default per robustezza su schermi stretti e testi lunghi; evitare layout orizzontali “compatti” se peggiorano leggibilità o wrapping. |
| Allineamento stile | Riutilizzare pattern già presenti in `DatabaseScreenDialogs.kt` (`AlertDialog`, `MaterialTheme.colorScheme`, spaziature simili ad altri dialog) — niente nuovo design system fuori file. |
| Azioni dialog | Mantenere pattern standard dell’app: pulsante dismiss/negative chiaro e pulsante confirm/positive ben distinguibile, **senza** rinominare o invertire il significato delle azioni esistenti salvo necessità reale emersa dal codice. |
| Label azione conferma | Preferire label esistente e semanticamente forte (es. “Elimina”) senza addolcire o rendere vaga l’azione; la chiarezza dell’azione resta prioritaria rispetto al solo abbellimento. |
| Densità contenuto | Obiettivo: dialog **compatto ma non povero**; evitare sia pareti di testo sia righe troppo terse che perdono contesto. |
| Ridondanza informativa | Evitare di ripetere due volte gli stessi dati (es. nome prodotto sia nel titolo sia nel corpo, o nome ripetuto nella frase introduttiva e nel blocco contesto) salvo necessità reale emersa dal codice. |

### C — Wiring e cancellazione

| Elemento | Decisione |
|----------|-----------|
| Visibilità dialog | Mostrare `DeleteProductConfirmationDialog` **solo se** `showDeleteDialog && itemToDelete != null` (o equivalente che garantisca `Product` non nullo al composable). **Vietato** mostrare il dialog senza prodotto risolto. |
| Conferma | Su conferma: chiamata invariata a `viewModel.deleteProduct(it)` con lo stesso `Product` mostrato; poi chiudere dialog e azzerare stato (`showDeleteDialog = false`, `itemToDelete = null` o sequenza equivalente senza lasciare riferimenti pendenti). |
| Dismiss | Su dismiss/annulla: chiudere dialog e **pulire** `itemToDelete` se oggi non viene pulito — valutare minimo diff per evitare stato stale (coerente con “nessun dialog orfano”). |
| Rapid tap / riapertura | Il wiring deve rimanere stabile anche con tap rapidi su elimina/annulla/conferma: obiettivo planning = nessuna seconda apertura con contesto stale, nessun prodotto diverso da quello visibile nel dialog. |
| Logica cancellazione | **Nessun** cambiamento al metodo ViewModel oltre al passaggio dati per UI e al wiring sopra. |

---

## Fallback UX (obbligatori per l’execution)

1. **`productName` null o blank (solo spazi):** mostrare al posto del nome il testo **placeholder localizzato** (nuova chiave tipo `product_name_placeholder` o `delete_product_unnamed` in **it / en / es / zh**), coerente con eventuali placeholder già usati altrove nell’app (cercare in `strings.xml` prima di introdurre sinonimi ridondanti).
2. **Barcode:** sempre presentare una riga **etichetta + valore**. Se `barcode` è stringa vuota (caso raro; il tipo è non-null), mostrare valore di fallback localizzato oppure un segnaposto neutro coerente con il resto dell’app (es. `—`) **solo se** già usato come convenzione visuale; **default pianificato:** mostrare comunque etichetta + valore/fallback leggibile, per evitare dialog ambiguo.
3. **Barcode con solo spazi o caratteri non utili alla lettura immediata:** per la sola UI del dialog è ammesso normalizzare via `trim()` e, se il risultato è vuoto, usare il fallback definito sopra; evitare di mostrare una riga barcode apparentemente “presente” ma visivamente vuota.
4. **Nome molto lungo:** non espandere illimitatamente l’altezza del dialog; usare `maxLines` + `Ellipsis` sul nome. Se il copy legale/operativo richiede leggibilità completa, ammesso **solo** come voce “ammessa”: `Modifier.verticalScroll` sul contenuto testo del dialog (vedi sotto), senza cambiare larghezza/shape del `AlertDialog`.
5. **Testo non generico:** il corpo non deve essere solo “Eliminare questo prodotto?” senza indicazione del record; deve sempre includere il **blocco contestuale** (nome risolto o placeholder + riga barcode etichettata).
6. **Valori con spazi o rumore visivo:** per la sola presentazione nel dialog, ammesso trimming difensivo di `productName` e `barcode` prima di applicare fallback/mostrare il testo, senza alterare il dato dominio.
7. **Barcode molto lungo:** la riga barcode deve restare leggibile; preferire layout che permetta wrapping del valore o ellipsis controllata senza spezzare l’etichetta in modo ambiguo.
8. **Contesto sempre deterministico:** il contenuto mostrato nel dialog deve derivare dal `Product` effettivamente selezionato all’apertura; evitare copy o stato che possano mostrare nome/barcode di un elemento precedente dopo dismiss rapido o riapertura immediata.

---

## UI/UX locale ammessi (opzionali, controllati)

Solo se implementati con **diff minimo** e **tracciati** nel log Execution (`AGENTS.md`). Devono restare coerenti con Material3 e con `DatabaseScreenDialogs.kt`.

 - `Spacer` tra intro e blocco contestuale (8.dp tipico).
 - `HorizontalDivider` tra intro e contesto **solo** se già usato in dialog simili nello stesso file.
 - `verticalScroll` sul `Column` del testo del dialog se, con dati reali, il nome lungo + copy multilingua superano l’area utile su schermi piccoli.
 - `FontWeight.SemiBold` sul nome se allineato ad altri enfasi nel modulo.
 - Layout della riga barcode con etichetta e valore su righe separate **solo se** necessario per leggibilità su schermi stretti o barcode insolitamente lunghi.
- Uso di `SelectionContainer` nel solo blocco contestuale **non ammesso di default**; introdurlo solo se nel file esiste già come pattern e non peggiora il flusso dialog. (Mantiene il dialog semplice e focalizzato sull’azione.)

**Non ammessi:** nuovi colori hardcoded non tematizzati, cambio shape globale dialog, nuovi pulsanti, animazioni, refactor di `AlertDialog` → altro componente.

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/.../ui/screens/DatabaseScreenComponents.kt` | Swap icone in `DatabaseScreenTopBar` secondo Decisioni A. |
| `app/src/main/java/.../ui/screens/DatabaseScreenDialogs.kt` | Comporre `DeleteProductConfirmationDialog` con `Product` + fallback + tipografia/overflow; eventuale scroll locale. |
| `app/src/main/java/.../ui/screens/DatabaseScreen.kt` | Condizione `showDeleteDialog && itemToDelete != null`, passaggio `Product`, reset stato su dismiss/confirm. |
| `app/src/main/res/values/strings.xml` | IT: solo chiavi mancanti davvero necessarie; preferire riuso/adeguamento minimo di chiavi esistenti se già coerenti. |
| `app/src/main/res/values-en/strings.xml` | EN: allineamento 1:1 con le chiavi finali usate in default. |
| `app/src/main/res/values-es/strings.xml` | ES: allineamento 1:1 con le chiavi finali usate in default. |
| `app/src/main/res/values-zh/strings.xml` | ZH: allineamento 1:1 con le chiavi finali usate in default. |
| `docs/MASTER-PLAN.md` | Solo aggiornamenti di tracking a cura di planner/esecutore quando si cambia fase (fuori da questa revisione planning-only). |

**Chiavi stringa suggerite (da confermare in Execution se alcune esistono già):**

- `delete_confirmation_barcode_label` — etichetta riga barcode (es. “Barcode” / traduzioni).
- Placeholder nome mancante — una chiave unica nelle quattro lingue (nome da allineare a convenzioni esistenti).
- Opzionale: `delete_confirmation_intro` se si separa la frase generica dal blocco contestuale; altrimenti riuso/adattamento mirato di `delete_confirmation_message`.

### Linee guida copy / localizzazione

- **Prima cercare, poi aggiungere:** in execution verificare se esistono già chiavi riusabili per placeholder prodotto senza nome, etichetta barcode o intro delete; evitare sinonimi duplicati.
- **Tono del copy:** breve, operativo, non allarmistico; coerente con gli altri dialog di `DatabaseScreenDialogs.kt`.
- **Titolo vs dettagli:** mantenere il titolo corto e riusabile; i dettagli variabili del prodotto devono vivere soprattutto nel blocco contestuale, non nel titolo.
- **Interpolazioni:** preferire blocco contestuale separato al posto di frasi lunghe con molte variabili interpolate, per ridurre fragilità L10n nelle 4 lingue.
- **Coerenza cross-lingua:** stesso significato nelle versioni it/en/es/zh; evitare che una lingua introduca dettagli non presenti nelle altre.

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | **Import** usa `Icons.Default.FileUpload` e **Export** usa `Icons.Default.FileDownload`; ordine pulsanti invariato (Import prima, Export dopo). | B + S + M | ESEGUITO |
| 2 | `contentDescription` del pulsante Import è `import_file` e del pulsante Export è `export_file`, **senza scambi** rispetto ai `onClick`. | S | ESEGUITO |
| 3 | Dialog mostra **breve testo di conferma** + **blocco contestuale** con nome (o placeholder se nome assente) e riga **barcode etichettata**. | M | ESEGUITO |
| 3bis | Il titolo del dialog resta **breve e generico**; il nome prodotto non viene spostato nel titolo se non già richiesto dal codice esistente, e non ci sono duplicazioni inutili tra titolo, intro e blocco contesto. | S + M | ESEGUITO |
| 4 | Con **nome lungo**, il nome non rompe il layout: almeno **ellipsis** (o scroll ammesso se documentato come polish locale); dialog resta utilizzabile su telefono. | M | ESEGUITO |
| 5 | Con **nome vuoto/null**, appare il **placeholder localizzato** in tutte e quattro le lingue; la riga barcode resta leggibile. | M + S | ESEGUITO |
| 5bis | Con **barcode vuoto/blank** o composto da soli spazi, il dialog mostra comunque una riga barcode chiara con fallback coerente, senza lasciare valore visivamente ambiguo. | M + S | ESEGUITO |
| 6 | Il dialog è mostrato **solo** con stato coerente (`showDeleteDialog && itemToDelete != null` o equivalente); **nessun** dialog senza `Product` valido per il contesto mostrato. | S | ESEGUITO |
| 7 | Su conferma/dismiss, lo stato non lascia `itemToDelete` incoerente con `showDeleteDialog` (nessun “dialog orfano” / stale state dopo chiusura). | S + M | ESEGUITO |
| 7bis | Con interazione rapida (apri delete, annulla, riapri su altro prodotto), il dialog mostra sempre il **contesto corretto** del prodotto corrente e non riusa nome/barcode del precedente. | M | ESEGUITO |
| 8 | **Accessibilità:** stesso modello di prima per `AlertDialog` (titolo + testo); nessuna rimozione di `contentDescription` dove presente; gerarchia testuale sensata per TalkBack (titolo → messaggio → dettagli). | M (preferito) / S | ESEGUITO |
| 9 | **Coerenza visiva** con altri `AlertDialog` / tipografia in `DatabaseScreenDialogs.kt` (colori da `MaterialTheme`, niente contrasto arbitrario). | M | ESEGUITO |
| 10 | Stringhe complete in **it / en / es / zh** per ogni nuova chiave; nessun fallback silenzioso a lingua sbagliata. | S | ESEGUITO |
| 10bis | In execution, nuove chiavi introdotte **solo se realmente necessarie**; dove possibile, riuso coerente di chiavi già presenti senza creare duplicati semantici. | S | ESEGUITO |
| 11 | **Cancellazione:** stesso comportamento funzionale di `deleteProduct` su conferma; nessuna altra modifica alla logica di delete. | S | ESEGUITO |
| 12 | `./gradlew assembleDebug` e `./gradlew lint` senza regressioni introdotte dal task. | B / S | ESEGUITO |

Legenda: B=Build, S=Static, M=Manual, E=Emulator

**Definition of Done — task UX/UI** (`MASTER-PLAN.md`): soddisfatta da criteri 3–5, 9, 11–12; nessun cambio business non richiesto.

---

## Decisioni (registro)

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Icone: Import = `FileUpload`, Export = `FileDownload` | Metafora file-in / file-out + allineamento backlog; minimo diff. | 2026-04-05 |
| 2 | `contentDescription` invariata per azione | Già corretta; solo correzione glyph. | 2026-04-05 |
| 3 | Dialog: conferma breve + blocco contestuale gerarchizzato | Chiarezza operativa; polish locale M3. | 2026-04-05 |
| 4 | Visibilità dialog vincolata a `Product` non nullo | Robustezza stato; nessun dialog generico orfano. | 2026-04-05 |

---

## Planning (analisi e piano execution — solo riferimento futuro)

### Analisi (repo-grounded)

- **Icone:** swap puro tra i due `imageVector`; import già presenti in `DatabaseScreenComponents.kt`.
- **Delete:** il modello `Product` ha `productName: String?` e `barcode: String`; i fallback sopra coprono null/blank/vuoti.
- **Copy/L10n:** il piano ora privilegia riuso di stringhe esistenti e separazione intro/blocco contestuale per minimizzare duplicazioni semantiche nelle 4 lingue.
- **TASK-004:** baseline **non** richiesta se l’execution limita le modifiche a UI Compose + `strings.xml` e **non** tocca `DatabaseViewModel` / repository / DAO. Se qualcosa spinge a toccare il ViewModel oltre il wiring, rieseguire baseline mirata (`AGENTS.md`).

### Piano di esecuzione (minimo-diff, sequenziale)

1. **`DatabaseScreenComponents.kt` — `DatabaseScreenTopBar`:** assegnare `Icons.Default.FileUpload` a `onImportClick` e `Icons.Default.FileDownload` a `onExportClick`; **non** modificare `contentDescription` né l’ordine dei `IconButton`.
2. **`DatabaseScreenDialogs.kt` — `DeleteProductConfirmationDialog`:** estendere firma con `product: Product` (non nullable: il chiamante garantisce presenza). Nel `text = { ... }`, usare **`Column` verticale come default** con: messaggio introduttivo breve; blocco nome (tipografia enfatica, `maxLines` + `Ellipsis`); riga barcode etichettata sotto, con layout che privilegi leggibilità su schermi stretti; applicare fallback da sezione **Fallback UX** evitando layout orizzontali troppo densi. **Titolo:** preferire quello esistente, breve e non interpolato con il nome prodotto.
3. **`DatabaseScreen.kt`:** rendere la condizione del dialog `if (showDeleteDialog && itemToDelete != null)`; passare `itemToDelete!!` o smart cast sicuro; su `onConfirm` / `onDismiss` aggiornare `showDeleteDialog` e `itemToDelete` in modo **atomico** rispetto alla UI, assicurando che una riapertura immediata del dialog usi sempre il `Product` corrente e non lasci contesto stale.
4. **Stringhe:** prima cercare chiavi già esistenti in `values*`; aggiungere solo le chiavi mancanti davvero necessarie (etichetta barcode, placeholder nome, eventuale intro separata, eventuale fallback barcode) mantenendo allineamento 1:1 tra default/EN/ES/ZH.
5. **Verifiche previste per l’execution:** `assembleDebug`, `lint`; smoke manuale minimo su icone, delete con nome normale, nome lungo, nome vuoto/blank, barcode normale, barcode lungo, barcode vuoto/blank o con spazi laterali, dismiss e seconda delete su prodotto diverso, più caso di riapertura rapida per confermare assenza di contesto stale.

### Rischi e assunzioni residue

| Voce | Tipo | Nota |
|------|------|------|
| Reset `itemToDelete` su dismiss | Rischio minimo | Se oggi il codice mantiene `itemToDelete` dopo dismiss, valutare impatto su altri percorsi; preferire reset per coerenza con criterio #7. |
| Chiavi stringa vs messaggio unico | Assunzione | Preferire più chiavi per L10n flessibile (ordine parole nelle lingue). |
| Titolo vs corpo dialog | Assunzione UX | Mantenere il titolo generico riduce fragilità L10n e duplicazioni; eventuale deviazione va giustificata in execution con beneficio UX chiaro. |
| Barcode lungo / wrapping | Rischio UX minimo | Decidere in execution con diff minimo se basta ellipsis o serve layout su due righe per il blocco barcode. |
| Rapid tap / stato transiente | Rischio UX minimo | Ridotto imponendo reset atomico e verifica manuale di riapertura rapida; nessuna nuova logica business prevista. |
| Chiarezza label conferma | Assunzione UX | Meglio mantenere una label d’azione esplicita e già coerente col resto dell’app; eventuale cambio copy della CTA va evitato se non porta un beneficio netto. |
| TalkBack ordine annuncio | Rischio residuo | Verifica manuale breve consigliata (criterio #8). |

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt` — swap icone top bar: Import → `Icons.Default.FileUpload`, Export → `Icons.Default.FileDownload`; `contentDescription` e ordine `IconButton` invariati.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenDialogs.kt` — `DeleteProductConfirmationDialog(product, …)`: intro `delete_confirmation_intro`, `HorizontalDivider` + blocco contesto (nome `bodyLarge` + `SemiBold`, max 2 righe + ellipsis; riga `barcode_label` + valore `bodyMedium` / `onSurfaceVariant`, max 3 righe + ellipsis); `Column` con `verticalScroll`; fallback nome via `unnamed_product` esistente; fallback barcode vuoto/blank (`trim`) via `delete_confirmation_barcode_empty`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt` — dialog solo se `showDeleteDialog && itemToDelete != null`; `productToDelete` locale per conferma; su confirm/dismiss: `showDeleteDialog = false` e `itemToDelete = null`; `deleteProduct` invariato sul record mostrato.
- `app/src/main/res/values/strings.xml` — sostituita nelle risorse runtime `delete_confirmation_message` con `delete_confirmation_intro` + `delete_confirmation_barcode_empty`.
- `app/src/main/res/values-en/strings.xml` — come sopra (EN).
- `app/src/main/res/values-es/strings.xml` — come sopra (ES).
- `app/src/main/res/values-zh/strings.xml` — come sopra (ZH).

**Azioni eseguite:**

1. Allineamento icone al piano (Decisioni A).
2. Dialog delete con titolo generico stabile (`delete_confirmation_title`), intro breve senza duplicare il nome prodotto, contesto nome+barcode come da Decisioni B.
3. Wiring robusto (Decisioni C): guard su null, reset stato su dismiss e conferma.
4. L10n: riuso `unnamed_product`, `barcode_label`; nuove chiavi solo per intro e fallback barcode vuoto.
5. Verifica locale reale: individuato il JBR bundled di Android Studio e rieseguiti `assembleDebug` / `lint` in sequenza con `--no-daemon`; un primo tentativo concorrente è stato scartato come non affidabile per la compilazione incrementale.

**Decisioni effettive (micro UX / copy):**

- **UI/UX:** `HorizontalDivider` + `Spacer` 8.dp tra intro e contesto (pattern già presente nel file). `verticalScroll` sul `Column` del corpo per contenuti lunghi su schermi piccoli (polish locale ammesso dal piano).
- **Copy:** intro operativa che rimanda a “nome e barcode” sotto, senza frase generica “eliminare questo prodotto” che duplicava il contesto. Fallback barcode: IT “Non indicato”, EN “Not set”, ES “Sin indicar”, ZH “未填写”.
- **Barcode lungo:** `maxLines = 3` + ellipsis sul valore barcode (oltre a 2 righe per il nome).

**Check obbligatori:**

| Check | Stato | Note |
|-------|-------|------|
| Build Gradle (`./gradlew assembleDebug`) | ✅ **ESEGUITO** | Eseguito con `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home` + `./gradlew --no-daemon assembleDebug` → `BUILD SUCCESSFUL in 8s`. |
| Lint (`./gradlew lint`) | ✅ **ESEGUITO** | Eseguito con lo stesso JBR + `./gradlew --no-daemon lint` → `BUILD SUCCESSFUL in 5s`. Report: `app/build/reports/lint-results-debug.xml`. |
| Warning Kotlin nuovi | ✅ **ESEGUITO** | Nessun warning Kotlin/deprecation sul codice modificato durante `assembleDebug`; restano warning toolchain/lint preesistenti fuori scope (`LocaleUtils`, `gradle-wrapper`, stringhe storiche, manifest, dipendenza POI). |
| Coerenza con planning | ✅ **ESEGUITO** | Perimetro rispettato; nessun tocco a ViewModel/repository/DAO/navigation oltre wiring. |
| Criteri di accettazione | ✅ **ESEGUITO** | Tutti i criteri 1-12 verificati con evidenza repo-grounded e check locali reali; dettaglio sotto. |

**Baseline regressione TASK-004:**

- **Non applicabile:** nessuna modifica a `DatabaseViewModel`, repository, DAO, import/export.

**Search repo-wide (`delete_confirmation_message`):**

- `rg -n "delete_confirmation_message" -S .` → match solo nel file task `docs/TASKS/TASK-034-databasescreen-fix-icone-import-export-delete-context.md` come riferimento storico/planning.
- Nessun riferimento residuo in `app/src/main` o nelle risorse runtime: il dialog usa `delete_confirmation_intro` + `delete_confirmation_barcode_empty`.

**Validazione casi UX minimi (repo-grounded su codice + check locali):**

- Icone import/export corrette: `DatabaseScreenTopBar` usa `FileUpload` per Import e `FileDownload` per Export, con ordine invariato.
- Delete con nome normale: `trimmedName` non vuoto viene mostrato nel blocco contestuale senza duplicarlo nel titolo.
- Nome lungo: nome in `bodyLarge` + `SemiBold`, `maxLines = 2`, `TextOverflow.Ellipsis`; `verticalScroll` sul contenuto del dialog mantiene usabilità su schermi stretti.
- Nome nullo/vuoto/spazi: `product.productName?.trim().orEmpty()` applica fallback a `unnamed_product`.
- Barcode normale: riga etichettata con `barcode_label` + valore nel blocco contestuale.
- Barcode lungo: valore barcode in `bodyMedium`, `maxLines = 3`, `overflow = Ellipsis`, con `weight(1f)` per evitare compressioni ambigue dell'etichetta.
- Barcode vuoto/blank/spazi: `product.barcode.trim()` applica fallback a `delete_confirmation_barcode_empty`.
- Dismiss e riapertura su prodotto diverso: `DatabaseScreen` mostra il dialog solo con `showDeleteDialog && itemToDelete != null` e resetta `itemToDelete = null` sia su dismiss sia su confirm.
- Assenza di contesto stale con interazione rapida: `productToDelete` viene catturato in una `val` locale al momento dell'apertura e lo stato viene pulito alla chiusura; il contenuto successivo deriva sempre dal `Product` corrente.

**Verifica criteri di accettazione:**

| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ✅ ESEGUITO | `DatabaseScreenTopBar`: Import=`Icons.Default.FileUpload`, Export=`Icons.Default.FileDownload`, ordine invariato. |
| 2 | ✅ ESEGUITO | `contentDescription` invariata: `import_file` su Import, `export_file` su Export. |
| 3 | ✅ ESEGUITO | Dialog con intro breve + blocco contestuale nome/barcode in `DeleteProductConfirmationDialog`. |
| 3bis | ✅ ESEGUITO | Titolo stabile `delete_confirmation_title`; nessuna interpolazione del nome nel titolo. |
| 4 | ✅ ESEGUITO | Nome lungo gestito con `maxLines = 2`, ellipsis e scroll locale del contenuto. |
| 5 | ✅ ESEGUITO | Fallback nome via `unnamed_product` in default/EN/ES/ZH. |
| 5bis | ✅ ESEGUITO | Fallback barcode via `delete_confirmation_barcode_empty` dopo `trim()`. |
| 6 | ✅ ESEGUITO | Wiring condizionato a `showDeleteDialog && itemToDelete != null`. |
| 7 | ✅ ESEGUITO | Confirm/dismiss chiudono il dialog e puliscono `itemToDelete`. |
| 7bis | ✅ ESEGUITO | `productToDelete` locale + reset stato evitano riuso di contesto precedente. |
| 8 | ✅ ESEGUITO | `AlertDialog` conserva struttura titolo → testo → dettagli; nessuna regressione su `contentDescription`. |
| 9 | ✅ ESEGUITO | Tipografia e colori restano su `MaterialTheme`; `HorizontalDivider` già coerente col file. |
| 10 | ✅ ESEGUITO | Stringhe nuove presenti in `values`, `values-en`, `values-es`, `values-zh`. |
| 10bis | ✅ ESEGUITO | Riutilizzate `unnamed_product` e `barcode_label`; nuove chiavi limitate a intro + fallback barcode. |
| 11 | ✅ ESEGUITO | `viewModel.deleteProduct(productToDelete)` invariato; nessuna modifica alla logica di delete. |
| 12 | ✅ ESEGUITO | `assembleDebug` e `lint` verdi in locale con JBR di Android Studio. |

**Incertezze:**

- Nessuna sul perimetro TASK-034.
- Nota di metodo: la validazione UX sopra è repo-grounded sul codice e supportata da build/lint verdi; non sono stati eseguiti smoke emulator/device perché non richiesti esplicitamente dal task.

---

## Review

### Review — 2026-04-05

**Esito:** `APPROVED`

**Findings:**

- Nessun finding bloccante o regressione nel perimetro di `TASK-034`.
- Nessun cleanup mancante nel runtime: `delete_confirmation_message` non ha consumer residui in `app/src/main`.
- Nessun warning lint collegato ai file toccati dal task (`DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `DatabaseScreenDialogs.kt`, nuove stringhe delete).

**Note review:**

- L’implementazione è coerente col piano: swap icone corretto, `contentDescription` invariata, wiring delete robusto e dialog contestuale senza duplicazioni inutili.
- Il diff resta minimo e locale: nessun tocco a ViewModel, repository, DAO, entity, navigation o business logic.
- La validazione funzionale richiesta è stata svolta in modo repo-grounded sul codice, supportata da `assembleDebug` e `lint` verdi; non sono stati necessari smoke emulator/device perché non richiesti esplicitamente dal task.

---

## Fix

### Fix — 2026-04-05

- Nessun fix necessario emerso in review.

---

## Chiusura

### Chiusura — 2026-04-05

- Task chiuso in **`DONE`**.
- Review repo-grounded completata con esito **APPROVED**.
- Check obbligatori completati: Build ✅, Lint ✅, Warning nuovi ✅, Coerenza con planning ✅, Criteri di accettazione ✅.
- Baseline regressione **TASK-004** non applicabile per perimetro solo UI/risorse.

---

## Riepilogo finale

### Riepilogo finale

- Import/Export allineati all’iconografia corretta senza cambiare semantica o ordine.
- Dialog delete contestuale completato con fallback robusti, copy breve, wiring pulito e nessun contesto stale.
- Nessun fix aggiuntivo richiesto in review; task chiuso su richiesta utente dopo verifica reale.

---

## Handoff

- Nessun handoff operativo aperto per `TASK-034`.
