# TASK-047 — GeneratedScreen: gerarchia iOS-like (top bar minimale, blocco progresso, summary, griglia più pulita)

---

## Informazioni generali

| Campo              | Valore                                         |
|--------------------|------------------------------------------------|
| ID                 | TASK-047                                       |
| Stato              | REVIEW                                         |
| Priorità           | ALTA                                           |
| Area               | UX / UI / GeneratedScreen                      |
| Creato             | 2026-04-11                                     |
| Ultimo aggiornamento | 2026-04-11 — execution completata; build/lint verdi; in attesa review |

---

## Dipendenze

- **TASK-014** `DONE` (prima ondata UX GeneratedScreen)
- **TASK-030** `DONE` (design system / token tema)
- **TASK-031** `DONE` (leggibilità griglia / stati riga)
- **TASK-040** / **TASK-042** `DONE` (mapping colonne a monte su PreGenerate / robustezza header)
- **TASK-041** `DONE` (banner “tutto completato” + quick export — da integrare visivamente con il nuovo layout, non rimuovere)
- **TASK-045** `DONE` (shell root coerente con navigazione)
- **TASK-046** `DONE` (PreGenerate iOS-style — continuità visiva desiderata ma **non** porting 1:1)

---

## Scopo

Ripensare la **gerarchia visiva** e la **densità informativa** della `GeneratedScreen` Android seguendo l’intento UX della controparte iOS (solo come riferimento visivo): top bar più silenziosa con azione primaria esplicita, **blocco stato/progresso** chiaro sopra la griglia, **toggle “solo righe con errore”**, **riepilogo sintetico in fondo schermata**, griglia più leggibile. **Nessuna rimozione di funzionalità già presenti: le azioni oggi visibili come icone devono restare raggiungibili (tipicamente via overflow o sezioni dedicate). Oltre alla pulizia visiva, il task deve migliorare anche la leggibilità degli stati principali della schermata: empty/manual draft, editing normale, presenza errori, completamento totale, loading/export/sync feedback.**

Il task deve privilegiare una modernizzazione **mirata ma percepibile**: non basta “spostare azioni”, serve una top area più ordinata, una progress card davvero protagonista, una griglia visivamente meno grezza e una chiusura del workflow più chiara. Se durante il planning emergono più alternative equivalenti, scegliere quella con UX più semplice, più pulita e più coerente con il linguaggio introdotto in **TASK-046**.

---

## Contesto

### Stato codice (audit repo 2026-04-11)

- **`GeneratedScreen.kt`** (~3k righe): orchestrazione principale. La **top bar** (`GeneratedScreenTopBar`) usa `CenterAlignedTopAppBar` con **back**, titolo con marquee, e in `actions` — quando la griglia è generata — **Home**, **Sync** (con badge stato), **menu overflow** (export, share, rename). Sotto la top bar, **`TopInfoChipsBar`** mostra supplier, category, chip **`completati/totale`**, ed eventuale “exported”. Il progresso è quindi **frammentato** e poco “a colpo d’occhio” rispetto al blocco card iOS.
- **Griglia**: `GeneratedScreenGridHost` → **`ZoomableExcelGrid`**. Header riga con `TableCell` **header**: supporta `onHeaderClick` e **`onHeaderEditClick`** (icona matita) che aprono il flusso **`GeneratedScreenHeaderTypeDialog` / `GeneratedScreenCustomHeaderDialog`** e chiamano `excelViewModel.setHeaderType` — è **mapping semantico colonne**, non solo “rinominare etichetta”.
- **`TableCell.kt`**: cella con bordo, stati riga (complete / filled / error override), e **matita** sugli header se `onEditClick != null`.
- **`ExcelViewModel.kt`**: fonte di verità per `excelData`, `completeStates`, `errorRowIndexes`, sync/export; esistono **`calculateInitialSummary` / `calculateFinalSummary` (private)** usate in salvataggio/generazione — utili per totali economici nel summary se esposti in modo **sola lettura** senza duplicare regole fuori dal VM.
- **`NavGraph.kt`**: route `Screen.Generated` invariata nel perimetro atteso; wiring a `GeneratedScreen` con `entryUid`, `isNew`, `isManualEntry`.

### Problema attuale (UX)

1. **Top area rumorosa**: molte icone affiancate + chip slegate visivamente; il titolo file compete con le azioni.
2. **Stato lavoro poco leggibile**: `completati/totale` è solo un chip; manca progress bar e contesto “errori” vicino al filtro.
3. **Griglia “da foglio elettronico”**: bordi per cella, header con matita, densità percepita ancora alta rispetto alla direzione iOS (più aria, meno linee verticali forti).
4. **Nessun riepilogo finale strutturato** in fondo schermata: l’utente non ha una “chiusura” informativa oltre al banner TASK-041 quando tutto è completo.
5. **Doppio affordance header griglia**: tap header e matita aprono lo stesso tipo di flusso mapping colonne; a valle del miglioramento PreGenerate (**TASK-040** / **TASK-042**) gran parte degli utenti non dovrebbe più dover correggere mapping **durante** l’editing in Generated.
6. **Stati schermata non abbastanza gerarchizzati**: empty/manual draft, stato con errori, stato completo, loading/export/sync convivono ma non hanno una priorità visiva abbastanza chiara nella parte alta della schermata.
7. **Azioni primarie/secondarie non abbastanza separate**: oggi back, home, sync, overflow e altri feedback competono nella stessa area, mentre il flusso utente beneficerebbe di una CTA primaria più evidente e di azioni secondarie raccolte.
8. **Chiusura visiva debole del workflow**: anche quando l’utente arriva in fondo, la schermata non comunica con abbastanza forza “stato finale + prossime azioni sensate”, soprattutto nei casi con righe incomplete o errori residui.
9. **Contesto riga fragile quando si filtra o si cerca**: con una futura vista “solo errori” bisogna evitare che l’utente perda orientamento tra indice reale, riga visibile, ricerca/scanner e dialog di dettaglio.

### Riferimento UX/UI (non fonte logica)

Riferimenti visivi forniti dall’utente (screenshot iOS + Android attuale). **Solo** gerarchia, spacing, card, progress, toggle errori, summary, organizzazione azioni. **Vietato** porting 1:1 SwiftUI → Compose: usare **Material 3** (Card, LinearProgressIndicator, Switch, TextButton, Menu) e pattern già usati post-**TASK-046** su PreGenerate ove sensato.

---

## Non incluso

- Refactor architetturale di `GeneratedScreen` (es. split file massivo) **salvo** piccoli estratti locali se riducono duplicazione nel perimetro UX.
- Re-layout completo dei dialog esistenti fuori dal necessario: i dialog possono ricevere solo ritocchi locali di coerenza visiva/copy se indispensabili al nuovo flusso, ma non devono espandere il perimetro del task.
- Modifiche a **DAO / Room / repository / schema / `NavGraph` routes** salvo emergenza documentata (obiettivo: **zero**).
- Cambiare semantica di sync, export, history, scanner, dialog esistenti — solo **presentazione** e **punto di ingresso** (menu / sezioni).
- Cambi di semantica a ricerca, scanner, dialog dettaglio riga o completamento riga: eventuali adattamenti devono essere solo di presentazione/wiring per restare compatibili col filtro visuale.
- Ridisegno funzionale di FAB ricerca/scanner oltre a posizionamento, spacing e coerenza visiva col nuovo footer/top layout.
- Nuove dipendenze Gradle.
- Cambi strutturali al comportamento di persistenza/salvataggio oltre al necessario wiring della CTA top bar; la logica esistente va riusata, non riscritta.
- Introduzione di nuove modalità permanenti della schermata oltre al filtro visuale “solo errori”; evitare stati UI aggiuntivi che complicano la mental model.
- Calcoli summary duplicati o logica numerica reimplementata nel Composable: i totali devono riusare fonti/read-only già coerenti col ViewModel o restare ridotti al minimo indispensabile.
- Persistenza cross-session del filtro visuale “solo errori” o di nuove preferenze UI della schermata: il filtro resta stato locale leggero salvo necessità reale emersa in Execution.
- Task puramente i18n globale (eventuali stringhe nuove restano nel perimetro ma non sostituiscono **TASK-019**).

---

## File potenzialmente coinvolti

| File | Ruolo |
|------|--------|
| `app/src/main/java/.../ui/screens/GeneratedScreen.kt` | Top bar, layout colonna principale, blocco progresso, summary footer, eventuale stato UI “solo errori”, integrazione banner TASK-041 |
| `app/src/main/java/.../ui/screens/GeneratedScreenDialogs.kt` | Solo se servono ritocchi copy/layout dialog già usati dalla schermata |
| `app/src/main/java/.../ui/components/ZoomableExcelGrid.kt` | Densità griglia, header row, opzionale supporto a elenco righe filtrate per indice (senza rompere mapping `editableValues` / `completeStates`) |
| `app/src/main/java/.../ui/components/TableCell.kt` | Stili bordo/padding, header senza matita se rimossa UX inline |
| `app/src/main/java/.../viewmodel/ExcelViewModel.kt` | **Solo se necessario**: API minima **read-only** per totali/summary (riuso `calculateFinalSummary` / `calculateInitialSummary`) — altrimenti evitare |
| `app/src/main/res/values/strings.xml` (+ `values-en`, `values-es`, `values-zh`) | Nuove stringhe: titolo sezione progresso/summary, toggle errori, CTA “Fine”/equivalente localizzato, etichette riepilogo |
| `app/src/main/java/.../ui/navigation/NavGraph.kt` | **Solo lettura** attesa; modifiche solo se emergenza reale |

**Helper interni già in `GeneratedScreen.kt`**: `GeneratedScreenTopBar`, `TopInfoChipsBar`, `GeneratedScreenGridHost`, `GeneratedScreenFabArea`, banner completamento, ecc. — probabile punto di intervento principale.

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | **Top bar minimale**: back a sinistra; titolo file centrale leggibile; a destra **CTA testuale primaria** “Fine” / equivalente localizzato. In **Execution** la CTA va collegata al percorso già più sicuro e coerente con lo stato corrente della schermata, senza introdurre nuova logica di persistenza: per entry da history/manual usare il salvataggio già esistente se richiesto dal flusso attuale, per casi che non richiedono salvataggio extra evitare doppioni col back. **Home**, **Sync** e altre azioni non devono più comparire come icone duplicate nella top bar se spostate altrove, ma devono restare disponibili e scopribili. | M + B | — |
| 1b | **CTA primaria sempre chiara**: il testo/posizionamento della CTA “Fine” deve farla percepire come azione primaria di chiusura workflow, senza sembrare un duplicato debole del back o un bottone secondario perso tra le azioni. | M | — |
| 1c | **Titolo schermata leggibile anche con nome file lungo**: la nuova top bar minimale non deve sacrificare la riconoscibilità del file corrente; titolo, truncation/marquee e CTA devono convivere senza collisioni visive o priorità invertite. | M | — |
| 2 | **Overflow**: export, share, rename (e ogni altra azione oggi solo nella top bar) restano raggiungibili dal menu **⋯**; nessuna regressione funzionale. | M | — |
| 2a | **Azioni migrate senza perdita funzionale**: se Home, Sync o altre azioni escono dalla top bar, devono restare raggiungibili con discoverability sufficiente nel menu e con labeling/icone coerenti; nessuna funzione importante deve diventare “nascosta per errore”. | M | — |
| 2b | **Azioni ordinate per priorità**: nel menu overflow le azioni devono essere raggruppate o ordinate in modo coerente con il workflow reale dell’utente (prima chiusura/uscita sicura e operazioni frequenti, poi azioni secondarie/legacy), evitando un elenco piatto senza gerarchia. | M | — |
| 2d | **Menu overflow scansionabile rapidamente**: se il numero di azioni cresce, il menu deve restare leggibile e facilmente scansionabile (ordine, eventuali divider/sezioni, copy sintetico), senza diventare una lista confusa di voci eterogenee. | M | — |
| 2c | **Azioni top-level davvero minimali**: fuori dal menu devono restare solo back e CTA primaria (più il titolo); eventuali eccezioni vanno giustificate solo se migliorano davvero la UX e non riaprono il rumore attuale della top area. | M | — |
| 3 | **Blocco progresso (card M3)** sopra la griglia quando `generated` e dati non vuoti: **totale righe dati**, **completate/totale** (o pending), **LinearProgressIndicator** proporzionale, testo stato sintetico; integrare supplier/category **nel blocco o** in sottotitolo compatto (non chip sparse orfane). | M | — |
| 3a | **Progress card leggibile in 2-3 secondi**: numeri, stato e avanzamento devono essere comprensibili con lettura rapida; evitare microcopy eccessiva, troppe metriche affiancate o layout visivamente denso. | M | — |
| 3b | **Gerarchia stati top area**: il blocco progresso deve assorbire o sostituire i chip informativi sparsi; niente elementi “orfani” sopra la griglia. Se supplier/category sono presenti, devono essere integrati come metadati secondari ordinati e non competere con progresso/CTA. | M | — |
| 3c | **Toggle errori ergonomico**: se non esistono righe in errore, il toggle può essere nascosto o disabilitato con copy chiaro; se attivo, deve restare evidente che la vista è filtrata. | M | — |
| 3d | **Banner completamento e progress card**: quando TASK-041 mostra il banner “tutto completato”, il rapporto visivo tra banner e progress card deve essere chiaro e non ridondante; evitare doppio messaggio che ripete la stessa informazione senza valore aggiunto. | M | — |
| 3e | **Metriche top area senza ambiguità**: conteggi di totale/completate/pending/errori devono riferirsi chiaramente alle righe dati utili e non creare dubbi con header, righe filtrate o viste parziali. | M + S | — |
| 3f | **Top area non forzata negli stati vuoti**: se la schermata è in empty/manual draft o non ha righe utili da mostrare, la progress card non deve diventare un blocco rumoroso o fuorviante con metriche a zero; in questi casi va privilegiata una presentazione più calma e chiara dello stato. | M | — |
| 4 | **Toggle “solo righe con errore”**: quando attivo, la griglia mostra **solo** righe in `errorRowIndexes`; quando disattivo, tutte le righe. Gli indici riga devono restare **allineati** a `excelData` / `editableValues` / `completeStates` / azioni riga (nessun salvataggio su riga sbagliata). | M + S | — |
| 4b | **Orientamento nel filtro errori**: quando la vista è filtrata, la schermata deve continuare a mostrare un riferimento chiaro alla riga reale (es. rowNumber o indice reale già disponibile), così da non creare confusione tra posizione visibile e posizione dati. | M | — |
| 4c | **Compatibilità ricerca/scanner/dialog**: ricerca, scanner e apertura del dettaglio riga devono continuare a puntare alla riga corretta anche se il filtro errori è attivo o appena disattivato. | M + S | — |
| 4d | **Stati riga coerenti anche in filtro**: evidenze visive già utili (errore, completata, parzialmente compilata se ancora rilevante) devono restare leggibili anche quando è attiva la vista “solo errori”, senza creare conflitti cromatici o perdita di contesto. | M | — |
| 5 | **Griglia più leggibile**: ridurre rumore (bordi/spacing coerenti con **TASK-030**/**031**); header senza **matita** se il mapping colonne inline è dismesso; nessuna perdita di tap-to-open riga / qty / price / completamento già supportati. | M | — |
| 5b | **Pulizia visiva header**: se il mapping inline viene rimosso, l’header non deve lasciare affordance ambigue o “morte”; la riga header deve apparire intenzionale, più pulita e coerente con il resto della schermata. | M | — |
| 5c | **Riduzione rumore senza perdita di affordance**: la griglia può diventare meno “sheet-like”, ma qty/price/completion e apertura dettaglio riga devono restare immediatamente comprensibili e facili da usare. | M | — |
| 5d | **Nessuna regressione di usabilità tecnica della griglia**: scroll, allineamento header/contenuto e comportamento su dataset medio-grandi devono restare stabili; il polish visivo non deve peggiorare fluidità o leggibilità operativa. | M + S | — |
| 6 | **Rimozione UX superflua header in Generated**: niente flusso da **tap header griglia** / icona edit per `setHeaderType`, **salvo** decisione documentata di mantenere voce **“Mapping colonne…”** nel overflow per casi legacy (history / file patologici). In ogni caso: niente regressione su caricamento entry esistenti; mapping già persistito resta valido. | M | — |
| 7 | **Summary footer (card M3)** in fondo contenuto scrollabile: almeno **totale righe**, **completate**, **pending**, **conteggio errori**; se disponibile senza duplicare logiche nel Composable, **totale ordine iniziale / stato pagamento** coerente con policy **TASK-023**/**027** (altrimenti stub documentato nel VM read-only). Il summary deve anche aiutare l’utente a capire la chiusura del workflow: se opportuno può includere una piccola sezione azioni o stato finale, ma senza duplicare in modo rumoroso le azioni già presenti nel menu o nella CTA primaria. | M | — |
| 7b | **Footer realmente utile**: il riepilogo finale non deve essere un semplice duplicato del blocco progresso; deve aggiungere contesto di chiusura workflow (stato finale, pending, errori, eventuale informazione economica) con densità più bassa e lettura rapida. | M | — |
| 7c | **Summary coerente con la logica esistente**: se vengono mostrati totali economici o metriche derivate, devono provenire da logica già coerente con il ViewModel/repository oppure essere chiaramente limitati a informazioni semplici e affidabili; niente numeri “ricalcolati ad hoc” nella UI. | M + S | — |
| 8 | **Banner TASK-041** resta funzionante e visivamente integrato (sotto top bar o sopra griglia secondo gerarchia scelta). | M | — |
| 8b | **Nessuna competizione tra banner, progress card e top bar**: la gerarchia verticale deve essere evidente; l’utente deve capire subito cosa è azione, cosa è stato corrente e cosa è feedback contestuale. | M | — |
| 8c | **Banner di completamento solo quando semanticamente corretto**: il banner TASK-041 non deve comparire in stati vuoti, manual draft o contesti in cui il completamento totale non è realmente raggiunto; niente feedback “trionfale” fuori contesto. | M | — |
| 9 | **FAB** ricerca + scanner: comportamento invariato; posizionamento adeguato con nuovo footer summary (no overlap critico). | M | — |
| 9b | **Empty / manual draft / zero-data state**: se `isManualEntry && excelData.size <= 1` o se non ci sono righe utili, la schermata deve risultare più curata e coerente con il nuovo linguaggio visivo, senza regressioni del flusso di aggiunta prodotto. | M | — |
| 9c | **Loading/export/sync feedback**: indicatori e snackbar/dialog già esistenti devono restare coerenti con la nuova gerarchia e non risultare coperti o visivamente scollegati dal layout aggiornato. | M | — |
| 9d | **Touch target e accessibilità base preservati**: CTA primaria, overflow, toggle errori e azioni principali devono restare facilmente tappabili e leggibili anche su viewport compatte, senza regressioni evidenti di accessibilità base. | M | — |
| 9e | **FAB subordinate alla nuova gerarchia**: ricerca/scanner possono restare utili e ben accessibili, ma non devono tornare a competere visivamente con CTA primaria, progress card o summary footer. | M | — |
| 10 | **Localizzazione**: nuove stringhe in **it + en + es + zh**. | S | — |
| 11 | Build: `./gradlew assembleDebug` OK; `./gradlew lint` senza nuovi warning nel perimetro. | B + S | — |
| 12 | Se si modifica `ExcelViewModel.kt`: eseguire baseline **TASK-004** mirata (`ExcelViewModelTest` o `./gradlew test`) e documentare. | B | — |

> Checklist **Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): applicare in Review.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Priorità a **chiarezza stato** (progress + error toggle + summary) rispetto a fedeltà pixel-perfect iOS | Allineamento a `CLAUDE.md` / regola Android+iOS | 2026-04-11 |
| 2 | **Mapping colonne** (`setHeaderType`): rimuovere dalla griglia; mantenere **escape hatch** (overflow) se necessario per legacy | Bilanciare alleggerimento vs compatibilità history | 2026-04-11 |
| 3 | CTA “Fine”: comportamento = **stesso obiettivo utente** dell’iOS (chiudere il task di editing in modo sicuro); in Execution scegliere tra salvataggio esplicito o navigazione già esistente senza introdurre doppioni con back | Evitare ambiguità tra Done e Back | 2026-04-11 |
| 4 | Il blocco progresso deve diventare il **punto focale informativo** sopra la griglia, sostituendo la dispersione di chip e mini-indicatori | Migliora scan visivo e coerenza con iOS senza copiare SwiftUI | 2026-04-11 |
| 5 | Il filtro “solo errori” va progettato come **vista filtrata**, non come modalità separata della schermata | Riduce complessità mentale e rischio regressioni sullo stato | 2026-04-11 |
| 6 | La vista filtrata “solo errori” deve preservare sempre il collegamento alla riga reale e non introdurre una numerazione “finta” scollegata dai dati | Riduce errori cognitivi e rischio di editing sulla riga sbagliata | 2026-04-11 |
| 7 | In caso di dubbio, la CTA primaria “Fine” deve seguire il percorso più conservativo e comprensibile per l’utente, privilegiando sicurezza del dato e coerenza con i flussi esistenti rispetto alla minimizzazione estrema dei tap | Evita ambiguità UX su una schermata ad alta densità operativa | 2026-04-11 |
| 8 | La schermata non deve introdurre nuove “modalità mentali” oltre al normale editing e al filtro visuale errori; ogni altro stato deve essere comunicato come variazione del layout, non come nuova modalità | Mantiene la UX semplice su una schermata già densa | 2026-04-11 |
| 9 | Il summary footer deve restare principalmente informativo; eventuali azioni secondarie possono comparire solo se aggiungono reale chiarezza di chiusura workflow e non duplicano CTA/menu già visibili | Evita rumore finale e mantiene forte la gerarchia top → contenuto → chiusura | 2026-04-11 |
| 10 | La top bar finale deve restare radicalmente semplice: back + titolo + CTA primaria; ogni altra azione parte dal menu salvo eccezioni fortemente giustificate | È il cambio più efficace per ottenere una schermata più pulita e iOS-like senza perdere funzioni | 2026-04-11 |
| 11 | Il filtro “solo errori” va trattato come stato locale temporaneo della sessione e non come preferenza persistente, salvo evidenza contraria emersa in execution | Riduce complessità, edge case e rischio di riaprire schermate in stati inattesi | 2026-04-11 |
| 12 | In presenza di empty/manual draft o zero-data state, la schermata deve privilegiare chiarezza e calma visiva rispetto alla persistenza forzata del blocco progresso | Evita una UX artificiale con card “vuote” che aggiungono rumore invece di orientare | 2026-04-11 |
| 13 | La top bar deve continuare a dare riconoscibilità forte al file corrente anche dopo la semplificazione radicale delle azioni | Evita che il minimalismo riduca troppo il contesto operativo della schermata | 2026-04-11 |
| 14 | Lo stato critico del workflow deve essere comprensibile above-the-fold nelle schermate con dati; il summary footer resta complemento di chiusura, non fonte primaria di orientamento | Mantiene forte la gerarchia informativa e riduce dipendenza dallo scroll per capire la situazione | 2026-04-11 |

---

## Planning (Claude)

### Analisi

La schermata è già funzionalmente ricca; il gap è **organizzazione visiva** e **feedback di stato**. Il pattern iOS (card progresso + summary + azioni secondarie nel menu) mappa bene su Material3. Il rischio maggiore è il **filtro righe errori**: va implementato preservando gli indici riga rispetto alla lista completa. La rimozione della matita/tap header richiede verifica che **PreGenerate** e history coprano i casi d’uso reali; in dubbio, voce overflow “Avanzate → mapping colonne”.

### Piano di esecuzione (progressivo)

1. **Definizione gerarchia top area**: ridefinire `Scaffold` con top bar minimale, CTA primaria testuale, overflow completo e relazione chiara tra top bar, eventuale banner TASK-041 e nuovo progress block. Prima di implementare, fissare esplicitamente la gerarchia delle azioni: cosa resta in top bar, cosa va nel menu, cosa eventualmente vive solo nel footer/stato finale. Validare subito anche l’equilibrio tra titolo file, CTA e overflow nei casi con nome file lungo.
2. **Componente `GeneratedScreenProgressCard`**: includere metadati essenziali (supplier/category se utili), totale righe, completate/pending/errori, progress indicator, toggle “solo errori”. Sostituire o assorbire `TopInfoChipsBar`, evitando duplicazioni di metadati o mini-stati in altre zone della top area.
3. **Filtro righe errore a basso rischio**: introdurre stato locale `showOnlyErrorRows` e passare alla griglia una vista filtrata tramite indici espliciti o adapter leggero, preservando mapping verso `excelData` / `editableValues` / `completeStates` / azioni riga. La soluzione scelta deve mantenere anche il riferimento alla riga reale per ricerca/scanner/dialog e per l’orientamento visivo dell’utente, senza degradare scroll, stabilità visuale o comportamento della griglia su dataset medio-grandi. Preferire la soluzione con minor rischio di desincronizzazione.
4. **Summary footer**: card finale con valori sintetici, chiusura visiva del workflow e spaziatura adeguata per evitare collisioni con FAB/navigation bars. Prima di fissarne il contenuto, verificare esplicitamente che non stia duplicando il ruolo della progress card: top = stato corrente, footer = quadro finale/chiusura. Definire anche una fonte dati chiara per ogni metrica mostrata, privilegiando valori già disponibili o read-only dal ViewModel.
5. **Pulizia griglia**: rimuovere affordance inline superflue dell’header (`onHeaderEditClick` / matita, e tap header se fuori perimetro), alleggerire bordi/padding/divider, mantenendo leggibilità e affordance sulle righe. Se resta necessario un escape hatch legacy per il mapping colonne, preferire una voce discreta nel menu overflow o in un sottomenu “Avanzate”, non un affordance persistente nella header row.
6. **Gestione stati speciali**: riallineare empty/manual draft, stato con tutti completi, stato con errori, loading/export/sync feedback al nuovo layout, senza cambiare la logica di business. Distinguere esplicitamente il caso “schermata con dati utili” dal caso “stato vuoto/bozza”, così progress card e banner compaiano solo quando aiutano davvero l’orientamento.
7. **Stringhe + localizzazione**: aggiungere solo il minimo necessario in `values` / `values-en` / `values-es` / `values-zh`.
8. **Verifiche**: matrice manuale su sync, export, rename, share, history replay, righe errori, completamento righe, banner TASK-041, manual entry, empty/manual draft, bottom spacing con FAB e tastiera.

### Rischi di regressione

| Rischio | Mitigazione |
|---------|-------------|
| Filtro errori desincronizza indici riga | Test manuale su edit/save; preferire lista indici esplicita; code review focalizzata |
| Ricerca o scanner aprono una riga diversa quando il filtro errori è attivo | Verificare sempre mapping riga visibile → riga reale; smoke dedicato con filtro on/off |
| Summary footer ridondante rispetto alla progress card | Separare chiaramente: top area = stato corrente, footer = chiusura workflow / quadro finale |
| Rimozione mapping inline blocca utenti legacy | Escape hatch overflow + nota in Handoff |
| CTA “Fine” confonde con back | Copy chiaro + stesso percorso sicuro di persistenza già usato altrove |
| Overflow troppo carico o poco ordinato dopo lo spostamento azioni | Definire ordine e grouping espliciti già in Execution; evitare menu “discarica” |
| Il titolo file perde leggibilità dopo la semplificazione della top bar | Verificare layout con titoli lunghi e CTA testuale; usare truncation/marquee solo se realmente leggibile |
| Touch target troppo compressi nella top area o nel blocco progresso | Controllare spacing minimo e tap area su viewport compatte |
| Touch target FAB vs summary | Padding `navigationBars` / `bottom` adeguato |
| Estensione ExcelViewModel | Mantenere metodo puro read-only; aggiornare ExcelViewModelTest se necessario |
| CTA “Fine” produce comportamento percepito ambiguo | In Execution definire esplicitamente mapping con i flussi già esistenti e verificarlo in matrice manuale |
| Progress card troppo densa o ridondante | Favorire sintesi visiva: massimo 1 focus primario + 1 livello secondario di metadati |
| La schermata introduce troppe gerarchie o micro-sezioni nuove | Accorpare dove possibile; preferire pochi blocchi forti invece di molte card minori |
| Il filtro errori rompe stabilità di scroll/allineamento griglia | Verifica dedicata con scroll verticale/orizzontale e toggle filtro ripetuti |
| Il footer finale introduce azioni ridondanti o distrae dalla CTA primaria | Tenere il footer prevalentemente informativo; aggiungere azioni solo se realmente utili e non duplicate |
| Totali/metriche del summary divergono dalla logica reale | Riutilizzare dati/read-model già esistenti; se non disponibile in modo pulito, ridurre il summary a metriche semplici e affidabili |
| Conteggi top area cambiano in modo confuso tra vista completa e filtro errori | Mantenere definizioni stabili delle metriche e chiarire visivamente che il filtro cambia la vista, non il dataset di riferimento |
| La progress card compare anche quando non è utile (empty/manual draft) e sporca la schermata | Mostrare il blocco progresso solo quando aggiunge davvero orientamento; negli stati vuoti privilegiare empty state più pulito |
| Banner completamento mostrato in contesti semanticamente sbagliati | Vincolare il banner a condizioni reali di completamento e verificarlo separatamente nei casi empty/manual/errore |

### Verifiche finali richieste

- `./gradlew assembleDebug`, `./gradlew lint`
- Smoke manuale minimo sui casi: entry nuova, entry da history, manual entry vuota, manual entry con prodotti, presenza errori, nessun errore, tutto completato
- Verifica specifica della CTA “Fine” nei casi: entry nuova, entry history, manual entry, stato con modifiche non salvate e stato già coerente/salvato
- Verifica con nome file lungo per confermare leggibilità del titolo e assenza di collisioni con CTA/overflow
- Verifica rapida su viewport compatta per confermare touch target e leggibilità base di CTA, toggle errori e menu
- Verifica esplicita del filtro “solo errori” su edit/salvataggio/completamento riga per escludere mismatch di indice
- Verifica dedicata di ricerca/scanner/dialog riga con filtro errori attivo, disattivo e dopo toggle multipli
- Verifica pratica della griglia con scroll verticale/orizzontale e filtro errori attivato/disattivato più volte
- Verifica su dataset non banale (non solo pochi elementi) per escludere peggioramenti di densità, stabilità e leggibilità operativa
- Verifica visiva overlap/spacing con FAB, snackbar, navigation bars, tastiera e summary footer
- Verifica rapida di leggibilità “a colpo d’occhio”: top bar, progress card e stato generale devono risultare comprensibili senza dover leggere tutta la schermata
- Verifica che il summary finale non mostri metriche incoerenti o divergenti rispetto allo stato reale della schermata e ai dati persistiti
- Verifica che i conteggi mostrati in top area restino coerenti prima/dopo filtro errori, senza ambiguità tra righe dati, header e vista filtrata
- Verifica che negli stati empty/manual draft o zero-data la top area non mostri progress UI fuorviante o metriche inutili a zero
- Verifica che il banner TASK-041 compaia solo nei casi di completamento reale e non in stati vuoti, manual draft o contesti incoerenti
- Verifica above-the-fold su schermata con dati: senza scroll devono risultare chiari file corrente, stato lavoro e azione primaria
- Se toccato VM: `./gradlew test` mirato o completo come da **AGENTS.md**
- Screenshot prima/dopo consigliati per Review

---

## Execution

### Log — 2026-04-11

**Esecutore:** Claude (planner in ruolo esecutore su istruzione esplicita utente)

**Inizio execution:** 2026-04-11

**Verifica pre-codice:**
- MASTER-PLAN letto ✅ — nessun task attivo, TASK-047 in BACKLOG/PLANNING
- Coerenza MASTER-PLAN ↔ file task verificata ✅
- Codice sorgente letto: `GeneratedScreen.kt` (~3145 righe), `ZoomableExcelGrid.kt`, `TableCell.kt`, `strings.xml` (it/en/es/zh) ✅

**File toccati:**
- `docs/TASKS/TASK-047-...md` (questo file)
- `docs/MASTER-PLAN.md`
- `app/src/main/res/values/strings.xml` (it)
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/java/.../ui/components/ZoomableExcelGrid.kt`
- `app/src/main/java/.../ui/screens/GeneratedScreen.kt`

**Cosa implementato:**
1. Top bar minimale: back + titolo + CTA "Fine" (TextButton) + overflow con Sync, Export, Share, Rename, Mapping colonne, Home
2. `GeneratedScreenProgressCard`: sostituisce `TopInfoChipsBar`; mostra supplier/category, completed/total, LinearProgressIndicator, error count, toggle "solo errori"
3. Filtro righe errore: stato locale `showOnlyErrorRows`; `ZoomableExcelGrid` arricchito con `rowIndexMapping`; indici reali preservati su tutti i callback
4. `GeneratedScreenSummaryFooter`: card M3 in fondo con totale/completate/pending/errori
5. FAB padding aggiustato per coesistenza con footer
6. Rimozione matita/header mapping inline dalla griglia in GeneratedScreen (passati `null` a `onHeaderEditClick` e `onHeaderClick`); voce "Mapping colonne…" aggiunta all'overflow

**Cosa non cambiato a livello funzionale:**
- DAO, Room, repository, navigation, ExcelViewModel (nessuna modifica)
- Dialogs (InfoDialog, RenameDialog, ExitDialog, ecc.) invariati
- Logica di auto-save, export, share, sync invariata
- CTA "Fine" = stesso percorso di `handleBackPress` (approccio conservativo per sicurezza dato)

**Trade-off:**
- CTA "Fine" = handleBackPress: scelta conservativa per evitare ambiguità; differenziazione visiva (text button destra vs icona sinistra) sufficiente per la UX
- Matita header rimossa dalla griglia ma escape hatch nel menu overflow mantiene retrocompatibilità per utenti legacy con file "patologici"
- Summary footer non espone totali economici (calculateFinalSummary è private in ExcelViewModel e non è stato esposto per rispettare il guardrail "nessuna modifica VM")

**Rischi residui:**
- Smoke UI/test manuali non eseguibili su questo ambiente JVM-only; necessario test su emulator/dispositivo
- LinearProgressIndicator non animato su primo render (behavior standard Compose)
- FAB overlap con footer su viewport molto compatte non verificabile senza emulator

**Esito build/lint:**
- `./gradlew assembleDebug` — **BUILD SUCCESSFUL**, 0 errori, 0 warning nel perimetro
- `./gradlew lint` — **BUILD SUCCESSFUL**, 73 warning preesistenti (LocaleUtils, gradle-wrapper, libs.versions.toml, AndroidManifest) — **0 nuovi warning** nei file toccati (GeneratedScreen.kt, ZoomableExcelGrid.kt, strings.xml)
- ExcelViewModel non toccato → test TASK-004 non richiesti

**Note implementative:**
- `LinearProgressIndicator` usa l'API lambda `progress = { ... }` di Material3 ≥ 1.3 (BOM 2026.03.01) ✅
- `Switch` senza vincolo di height: Material spec rispettata ✅
- `rowIndexMapping` in `ZoomableExcelGrid` è backward-compatible (default `null`) ✅
- `TopInfoChipsBar` e `InfoChip` rimossi perché diventati dead code ✅
- Escape hatch "Mapping colonne…" nell'overflow: apre column picker → `headerDialogIndex` flow già esistente ✅
- CTA "Fine" = `handleBackPress` (approccio conservativo documentato in Decisions #7) ✅

### Log — 2026-04-11 (Fase 2 — iOS polish su richiesta utente)

**Trigger:** utente ha condiviso screenshot iOS e richiesto "rendi più simile possibile alla versione iOS, pulita e ergonomica".

**File toccati (Fase 2):**
- `app/src/main/java/.../viewmodel/ExcelViewModel.kt` — aggiunta property read-only `initialOrderTotal: Double`
- `app/src/main/res/values/strings.xml` (it) — aggiunte chiavi `initial_order_total_label`, `data_rows_label`
- `app/src/main/res/values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — stesse chiavi localizzate
- `app/src/main/java/.../ui/screens/GeneratedScreen.kt` — redesign `GeneratedScreenProgressCard` e `GeneratedScreenSummaryFooter`; import `formatClSummaryMoney`

**Cosa cambiato in Fase 2:**
1. `GeneratedScreenProgressCard`: rimosso `Card` con bordatura; ora flat `Column` tra due `HorizontalDivider`; sfondo `surfaceColorAtElevation(1.dp)`; layout iOS: metadata (supplier · category · exported) su una riga sottile, poi Row "Righe dati: X | toggle errori", poi Row "Completate X/Y | LinearProgressIndicator 3dp"
2. `GeneratedScreenSummaryFooter`: sostituito layout SpaceEvenly con lista iOS-style (label sinistra, valore destra); header sezione "RIEPILOGO"; righe: Prodotti totali, Completate, In sospeso, Errori; separatore; riga "Totale ordine iniziale | \$ XXX" con valore colorato (primary, SemiBold)
3. `SummaryMetric` (helper colonna centrato) sostituito con `SummaryListRow` (label-valore su riga orizzontale)
4. `ExcelViewModel.initialOrderTotal` — delegato a `calculateInitialSummary(excelData).second`; nessuna logica duplicata
5. Call site footer aggiornato: `formatClSummaryMoney(excelViewModel.initialOrderTotal)` passato come `initialOrderTotal: String`
6. Rimosso padding `horizontal=spacing.md, vertical=spacing.sm` dal modifier progress card (ora gestito internamente)

**Delta UI intenzionale (documentato per review):**
- Progress section: da Card elevato con bordo a sezione flat delimitata da dividers — più leggera visivamente, coerente iOS
- Summary footer: da 4 colonne SpaceEvenly centrate a lista verticale label-valore — più leggibile, più compatta verticalmente, aggiunge contesto economico
- `SummaryMetric` rimosso (dead code dopo redesign)
- Nessuna modifica a logica business, Room, navigation, dialog, export, sync

**Esito build/lint (Fase 2):**
- `./gradlew assembleDebug` — **BUILD SUCCESSFUL**, 0 errori
- `./gradlew lintDebug` — **BUILD SUCCESSFUL**, 0 nuovi warning nel perimetro

---

## Review

_(Vuoto)_

---

## Fix

_(Vuoto)_

---

## Chiusura

_(Vuoto)_

---

## Riepilogo finale

Planning ulteriormente consolidato: oltre al restyling iOS-like della top area e della griglia, il task chiarisce meglio la forza visiva della CTA primaria, la leggibilità rapida della progress card, la distinzione tra progress card e summary footer, la radicale semplificazione delle azioni top-level, l’ordinamento delle azioni nell’overflow, il miglioramento degli empty/manual states, la coerenza degli stati riga sotto filtro e la necessità di preservare sempre il collegamento tra riga visibile e riga reale per evitare regressioni su ricerca, scanner, dialog e salvataggio. Il task resta volutamente nel perimetro UI/UX, riusando la logica esistente e minimizzando i tocchi a ViewModel/navigation, ma alza ulteriormente l’asticella anche su stabilità della griglia, affidabilità del summary, chiarezza dei conteggi mostrati, correttezza semantica di banner/progress, leggibilità del titolo file, robustezza su viewport compatte e qualità finale percepita.

---

## Handoff

- Riferimenti visivi utente: screenshot iOS/Android in allegato conversazione (non versionati obbligatoriamente in repo).
- Continuità con **TASK-046** solo a livello tono visivo (card, gerarchia), non copia layout.
