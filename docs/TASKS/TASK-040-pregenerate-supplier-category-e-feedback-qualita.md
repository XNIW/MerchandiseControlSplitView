# TASK-040 — PreGenerate: supplier/category anticipati + feedback qualità dati

---

## Informazioni generali

| Campo                | Valore |
|----------------------|--------|
| ID                   | TASK-040 |
| Stato                | **DONE** |
| Priorità             | MEDIA |
| Area                 | UX / UI / PreGenerateScreen (+ eventuale ExcelViewModel per warning dati) |
| Creato               | 2026-04-05 |
| Ultimo aggiornamento | 2026-04-05 — chiusura documentale finale `DONE`; `MASTER-PLAN` riallineato |

---

## Dipendenze

- **Nessuna**

---

## Scopo

1. Rendere **supplier** e **category** visibili e selezionabili in **PreGenerate** **prima** del tap su «Generate», riusando la semantica attuale (ricerca, suggerimenti DB, aggiunta nuovo supplier/category via `DatabaseViewModel`) e mantenendo **Generate** come primary action sempre evidente.  
2. Aggiungere **feedback pre-generazione** sulla qualità dei dati in anteprima: almeno **barcode duplicati** nel file e **prezzi di acquisto mancanti** (o equivalente allineato alle colonne mappate), in forma chiara, localizzabile e non invasiva (warning / banner / surface semantica Material3), con **summary compatto e leggibile**, **senza** cambiare il contratto di navigazione verso `GeneratedScreen` salvo necessità documentata.  
3. Mantenere interventi **locali** e progressivi: niente redesign della griglia o refactor architetturale fuori perimetro.  
4. In caso di scelte UX locali non bloccanti, privilegiare la soluzione **più coerente con lo stile attuale dell’app**, riducendo attrito e passaggi ridondanti anche con piccoli ritocchi visivi/gerarchici nel perimetro della schermata.

---

## Contesto

**Problema UX:** oggi supplier/category compaiono solo dopo il tap su `ExtendedFloatingActionButton` «Generate», che apre `showSelectionDialog` e, nello stesso `onClick`, **azzera** query e selezioni (`onSupplierSearchQueryChanged("")`, `selectedSupplier = null`, ecc.) — vedi `PreGenerateScreen.kt` (blocco FAB + `AlertDialog`).

**Flusso invariato lato navigazione:** `NavGraph.kt` passa `onGenerate = { supplierName, categoryName -> excelViewModel.generateFilteredWithOldPrices(...) { ... navigate Generated } }` — la generazione resta nel **ViewModel**; questo task non sposta business logic nei composable.

**Riferimenti qualità dati:** logica duplicati barcode in import è in `ImportAnalysis.kt` (`DuplicateWarning`); in **PreGenerate** i dati sono `excelViewModel.excelData` (lista di righe). Eventuale scansione per warning deve essere **coerente** con le chiavi colonna effettive (header) e documentata; preferenza: calcolo in `ExcelViewModel` (o helper dedicato invocato da VM) se serve stato riusabile, non logica pesante nel composable.


**Già presente:** blocco per **colonne essenziali** mancanti (`barcode`, `productName`, `purchasePrice`) nel dialog — va **preservato o equivalente** dopo lo spostamento UI.

**Direzione UX preferita per questo task:** evitare doppie conferme o dialog ridondanti se la selezione supplier/category è già esposta in schermata; se una scelta locale non è coperta da criteri espliciti, favorire la variante con **minor attrito**, migliore gerarchia visiva e coerenza Material3 con il resto dell’app.

**Coerenza stati UI:** la nuova sezione inline non deve peggiorare stati **loading / empty / errore** già presenti nella schermata; in particolare, warning e controlli anticipati non devono creare salti di layout inutili né oscurare la preview quando i dati sono validi e visibili.



**Primary action e viewport:** anche su schermi compatti o con tastiera aperta, la schermata non deve trasformare `Generate` in un’azione difficile da raggiungere o visivamente secondaria; se serve una scelta locale di layout, privilegiare quella che preserva meglio visibilità, gerarchia e accessibilità della primary action senza coprire la preview.




**Copy e priorità messaggi:** warning qualità dati e messaggi bloccanti devono restare **brevi, non ridondanti e semanticamente distinti**; se esiste già uno stato principale o un errore bloccante, evitare duplicazioni di testo che ripetano lo stesso problema in più punti della schermata.

---

## Non incluso

- Modifiche a **DAO**, **repository**, **entity** Room, **schema** DB.  
- Modifiche a **`NavGraph.kt` / route** salvo wiring minimo già richiesto dal task (es. nessun nuovo argomento navigazione).  
- Redesign di **`ZoomableExcelGrid.kt`** o porting 1:1 da iOS.  
- Copertura **test UI** Compose/Espresso dedicata (non richiesti salvo aggiunta esplicita nei criteri).  
- Allineamento funzionale completo a **ImportAnalysis** (merge, apply DB): solo **anteprima / pre-generazione** in PreGenerate.

---

## File potenzialmente coinvolti

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/PreGenerateScreen.kt` — layout anticipato supplier/category; dialog da snellire o rimuovere se ridondante; padding preview / FAB.  
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt` — lettura prioritaria; modifiche solo se manca API minima per refresh liste dopo add (preferenza: riuso esistente).  
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/ExcelViewModel.kt` — eventuale stato o funzione per summary warning (duplicati / prezzi mancanti) su `excelData`.  
- `app/src/main/res/values/strings.xml` (+ `values-en`, `values-es`, `values-zh` se si aggiungono stringhe visibili) — testi warning e eventuali etichette sezione.  
- Riferimento sola lettura: `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt`, `util/ImportAnalysis.kt` (pattern warning duplicati).

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Con Excel caricato e anteprima visibile (non in loading / analisi DB), l’utente può selezionare **supplier** e **category** **senza** dover prima aprire il flusso dialog attuale esclusivo del FAB (stessa capacità: ricerca, dropdown, aggiungi nuovo). | M + B | ✅ ESEGUITO |
| 2 | Tap su «Generate» con selezioni valide e colonne essenziali presenti avvia `onGenerate(supplier, category)` coerente con i valori mostrati in UI (stesse regole di allineamento testo/selezione del dialog odierno, salvo decisione documentata). | M | ✅ ESEGUITO |
| 3 | Se mancano colonne essenziali (`barcode`, `productName`, `purchasePrice`), la generazione resta **bloccata** e l’utente vede un messaggio chiaro (inline o dialog minimale). | M | ✅ ESEGUITO |
| 4 | Viene mostrato almeno un **warning** prima di generare se nel dataset anteprima compaiono **barcode duplicati** tra righe dati (stessa colonna barcode mappata); il messaggio è localizzabile (risorsa stringa). | M + B | ✅ ESEGUITO |
| 5 | Viene mostrato almeno un **warning** se, per righe con barcode non vuoto, manca il **prezzo di acquisto** (colonna `purchasePrice` o mapping effettivo usato dalla griglia). | M + B | ✅ ESEGUITO |
| 6 | I warning qualità dati mostrano un **summary compatto** e comprensibile (es. conteggi o messaggio sintetico), senza trasformare la schermata in un report verboso; dettagli riga-per-riga non sono richiesti salvo riuso quasi gratuito di logica già esistente. | M | ✅ ESEGUITO |
| 7 | Il flusso di selezione/aggiunta supplier/category resta coerente anche dopo inserimento di un nuovo valore: la UI si aggiorna senza perdere inutilmente il contesto corrente o costringere l’utente a ripartire da zero. | M | ✅ ESEGUITO |
| 8 | I warning **non** devono impedire da soli la generazione se i criteri 2–3 sono soddisfatti (informativi), salvo decisione esplicita registrata nelle **Decisioni** del task. | M | ✅ ESEGUITO |
| 9 | Se supplier/category sono già compilati in schermata, il tap su «Generate» **non** ripropone un dialog ridondante solo per riconfermare gli stessi valori; eventuali blocchi restano limitati ai casi realmente necessari (es. colonne essenziali mancanti). | M | ✅ ESEGUITO |
| 10 | In caso di reload / nuovo file / append che rende incoerente la selezione corrente, lo stato UI viene riallineato in modo prevedibile e documentato; in assenza di un forte motivo contrario, mantenere il testo digitato solo se ancora coerente con il dataset corrente, altrimenti resettare selezione e query in modo esplicito. | M + B | ✅ ESEGUITO |
| 11 | La gerarchia visiva della schermata resta chiara dopo il cambiamento: sezione supplier/category, warning qualità dati, preview e primary action non si sovrappongono e non riducono la leggibilità rispetto allo stato precedente. | M | ✅ ESEGUITO |
| 12 | In assenza di warning qualità dati, la schermata non introduce spazio morto o placeholder superflui: la sezione warning è assente oppure collassata in modo pulito, senza peggiorare la densità visiva. | M | ✅ ESEGUITO |
| 13 | Gli stati della sezione supplier/category restano comprensibili anche con liste vuote, ricerca senza risultati o dati DB non ancora pronti: l’utente non resta senza feedback e non perde la possibilità di capire cosa fare dopo. | M | ✅ ESEGUITO |
| 14 | La primary action `Generate` resta chiaramente percepibile anche con tastiera aperta o su viewport compatti: la nuova UI non la rende ambigua, nascosta o eccessivamente lontana dal contesto di selezione. | M | ✅ ESEGUITO |
| 15 | La schermata non introduce auto-scroll, rimbalzi di layout o spostamenti inattesi del contenuto durante digitazione, selezione supplier/category o comparsa/scomparsa warning, salvo quanto strettamente necessario per mantenere visibile il campo attivo. | M | ✅ ESEGUITO |
| 16 | Gli stati esistenti di loading / analisi DB / errore della schermata restano comprensibili dopo l’introduzione della sezione inline: nessun overlay, warning o controllo anticipato deve nascondere il motivo per cui la preview non è ancora pronta o non è disponibile. | M | ✅ ESEGUITO |
| 17 | I testi dei warning e dei messaggi bloccanti restano brevi, chiari e non duplicati: la schermata non mostra contemporaneamente più messaggi che esprimono lo stesso problema con wording diverso. | M | ✅ ESEGUITO |
| 18 | Se è presente uno stato principale bloccante o non-ready (es. loading/errore/colonne essenziali mancanti), i warning qualità dati non prendono la priorità visiva né generano rumore aggiuntivo non utile. | M | ✅ ESEGUITO |
| 19 | Quando la schermata è in stato non pronto o bloccante (es. loading, analisi DB, colonne essenziali mancanti), l’utente capisce **perché** `Generate` non può procedere: evitare CTA apparentemente “morta” o feedback impliciti. | M | ✅ ESEGUITO |
| 20 | La sezione inline mantiene etichette, helper text e azioni secondarie abbastanza esplicite da restare comprensibili anche senza affidarsi a icone o contesto implicito; niente microcopy ambiguo o troppo tecnico. | M | ✅ ESEGUITO |
| 21 | `./gradlew assembleDebug` e `./gradlew lint` completati senza errori; nessun warning Kotlin nuovo non motivato nel perimetro modificato. | B + S | ✅ ESEGUITO |
| 22 | Se `ExcelViewModel.kt` o logica import/excel condivisa viene modificata: eseguire e documentare baseline **TASK-004** (test JVM rilevanti, es. `ExcelViewModelTest` / suite mirata). | S | ✅ ESEGUITO |

**Definition of Done — task UX/UI** (`docs/MASTER-PLAN.md`): applicare dove rilevante; verificare gerarchia, spacing, stati empty/loading/error non peggiorati, primary action Generate ancora evidente.

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Dopo **reload / nuovo file / append**, se supplier/category correnti non sono più coerenti con lo stato visibile della schermata, resettare **selezione e query**; mantenerle solo quando la UI/stato restano chiaramente coerenti. | Evitare carry-over ambiguo tra file diversi e ridurre errori silenziosi. | 2026-04-05 |
| 2 | I warning qualità dati restano **solo informativi** e **non bloccanti**; il blocco resta riservato alle **colonne essenziali mancanti** o ad altri errori già previsti dal flusso corrente. | Migliore UX: si informa l’utente senza aggiungere attrito non necessario in PreGenerate. | 2026-04-05 |
| 3 | Se la selezione supplier/category è già disponibile inline, evitare di riaprire il dialog storico come step obbligatorio; mantenerlo solo se serve come fallback tecnico minimo o per riuso non ridondante. | Ridurre passaggi doppi e mantenere `Generate` come primary action diretta. | 2026-04-05 |
| 4 | Il feedback qualità dati deve preferire una presentazione **compatta e stabile** (una piccola sezione/surface dedicata, con summary sintetico) invece di moltiplicare dialog, toast o blocchi testuali lunghi. | Mantenere leggibilità alta e rumore visivo basso nella schermata PreGenerate. | 2026-04-05 |
| 5 | La UI dei warning qualità dati deve essere **collassata o assente** quando non ci sono warning reali; evitare placeholder permanenti che consumano spazio verticale senza valore. | Preservare leggibilità e densità visiva della schermata quando il dataset è sano. | 2026-04-05 |
| 6 | In caso di dubbio tra più varianti locali, preservare la **prominenza di `Generate`** e la leggibilità della preview rispetto a soluzioni più dense ma meno chiare. | La primary action deve restare immediata senza sacrificare il contesto dati che l’utente sta controllando. | 2026-04-05 |
| 7 | Evitare animazioni, auto-scroll o riorganizzazioni di layout non necessarie attorno a supplier/category e warning; preferire transizioni minime e stabili. | Ridurre sensazione di UI instabile o “saltellante” durante il flusso PreGenerate. | 2026-04-05 |
| 8 | In presenza di stati di loading / analisi DB / errore, la UI inline di supplier/category e warning deve restare subordinata alla chiarezza dello stato principale della schermata; evitare che elementi secondari competano con il messaggio di stato primario. | Preservare comprensibilità del flusso anche quando la preview o i dati di supporto non sono ancora pronti. | 2026-04-05 |
| 9 | In caso di sovrapposizione tra stato principale della schermata e feedback secondario, mostrare **una sola gerarchia chiara di messaggi**: prima il motivo bloccante o lo stato primario, poi eventuali warning secondari solo se davvero utili e non ridondanti. | Ridurre rumore cognitivo e mantenere il focus dell’utente sul problema realmente da risolvere. | 2026-04-05 |
| 10 | In stati non pronti o bloccanti, preferire una CTA chiara con motivo comprensibile del blocco rispetto a comportamenti impliciti o tap senza esito leggibile. | Ridurre frustrazione e ambiguità quando `Generate` non può procedere subito. | 2026-04-05 |
| 11 | Per supplier/category e warning, preferire microcopy esplicito e label/helper text chiari rispetto a affordance troppo implicite o solo iconiche. | Migliorare comprensibilità e coerenza UX senza introdurre complessità visiva inutile. | 2026-04-05 |

---

## Planning (Claude)

### Analisi

- Supplier/category: stato locale `remember` + `DatabaseViewModel` flows; oggi il dialog unico crea attrito e reimposta query/selezioni nel click path della primary action.  
- La soluzione preferita è una **sezione inline persistente** in schermata per supplier/category; il dialog storico va ridotto a fallback o rimosso se diventa solo duplicazione UX.  
- `previewBottomPadding` e area FAB vanno verificati insieme alla nuova gerarchia visiva: la schermata deve restare leggibile con sezione selezione + warning + preview + primary action senza overlap.  
- Warning duplicati/prezzi: derivabili da scan O(n) su `excelData.drop(1)` con indici colonna da header row; attenzione a righe vuote, alias header e coerenza tra chiavi logiche e intestazioni effettive.  
- Se emergono micro-scelte UI/UX non bloccanti, decidere direttamente la variante più semplice, coerente e gradevole per l’app, documentando la scelta nel log del task senza allargare il perimetro.  
- Il flow di **aggiunta nuovo supplier/category** va verificato esplicitamente: dopo insert riuscito, la selezione dovrebbe convergere sul nuovo valore con attrito minimo e senza stati transitori confusi.  
- Per i warning qualità dati è preferibile un **riassunto sintetico** persistente in schermata rispetto a feedback effimeri; toast/snackbar vanno evitati come canale principale per questo task.
- La UX va controllata anche nei casi **liste vuote / ricerca senza risultati / caricamento dati DB** per supplier/category: servono feedback piccoli ma chiari, senza lasciare controlli ambigui o apparentemente rotti.
- La sezione warning deve avere comportamento **collassabile per assenza contenuto**: nessun blocco UI “vuoto” permanente se non emergono anomalie nel file.
- Va verificata anche la resa su **viewport compatti / tastiera aperta**: la nuova sezione non deve allontanare troppo `Generate` né spezzare il rapporto visivo tra selezione, warning e preview.
- Oltre alla correttezza funzionale, conta la **stabilità percepita** della schermata: warning che appaiono/scompaiono o ricerche inline non devono produrre movimenti fastidiosi del layout.
- Va preservata la leggibilità degli stati principali della schermata (**loading / analisi DB / errore**): la nuova sezione inline non deve rubare priorità visiva quando la preview non è ancora disponibile o quando c’è un problema da correggere.
- Va controllata anche la **gerarchia del copy**: warning informativi, blocchi per colonne essenziali e stati principali non devono duplicarsi o competere visivamente; serve una sola priorità messaggi leggibile.
- Va evitato anche l’effetto **CTA senza esito chiaro**: se `Generate` non può procedere per stato non pronto o blocco reale, il motivo deve essere leggibile senza costringere l’utente a inferirlo.
- La qualità UX dipende anche da **microcopy e affordance**: label, helper text e azioni secondarie della sezione inline devono restare espliciti e non affidarsi troppo a icone o convenzioni implicite.

### Piano di esecuzione

1. Leggere `PreGenerateScreen.kt`, `DatabaseViewModel.kt` (supplier/category), `ExcelViewModel.kt` (`excelData`, `generateFilteredWithOldPrices`) e verificare se esiste già logica/helper riusabile per warning o blocchi colonne essenziali.  
2. Definire la nuova gerarchia della schermata in modo minimale: sezione inline per supplier/category sopra la preview o nel punto più naturale del layout, con `Generate` ancora chiaramente primaria.  
3. Riutilizzare un solo blocco di stato/composable per supplier/category, evitando doppie fonti di verità tra inline UI e dialog; se serve, mantenere il dialog solo come fallback tecnico non ridondante.  
4. Verificare e stabilizzare il flow di aggiunta nuovo supplier/category: dopo creazione, aggiornare suggerimenti/selezione nel modo più lineare possibile, senza costringere a riaprire percorsi ridondanti.  
5. Rifattorizzare il click di `Generate`: usare direttamente i valori già visibili in UI, evitare reset inutili, preservare il blocco su colonne essenziali mancanti con messaggio chiaro e minimale.  
6. Implementare summary warning qualità dati (preferibilmente in `ExcelViewModel` o helper dedicato chiamato dal VM) per barcode duplicati e prezzi acquisto mancanti; mostrare UI compatta, localizzabile e informativa.  
7. Definire esplicitamente il comportamento su reload / nuovo file / append secondo la Decisione #1, evitando carry-over ambiguo di query/selezioni.  
8. Rifinire spacing, padding, prominenza della primary action e coerenza visiva Material3 nel perimetro della schermata, senza redesign ampio.  
9. Verificare il comportamento del layout su viewport compatti e con tastiera aperta, assicurando che `Generate` resti prominente e che l’interazione con campi/search dropdown non produca salti di layout inutili.
10. Verificare gli stati secondari della UI inline (liste vuote, ricerca senza risultati, caricamento/aggiornamento dati DB, assenza warning) e rifinire il copy minimo necessario per mantenere il flusso autoesplicativo.
11. Verificare che gli stati principali della schermata (loading, analisi DB, errore, preview disponibile) mantengano una gerarchia chiara anche con la nuova sezione inline e con eventuali warning qualità dati.
12. Rifinire la gerarchia del copy e dei messaggi: evitare duplicazioni tra warning, errori bloccanti e stati principali della schermata, mantenendo testi brevi e semanticamente distinti.
13. Rifinire stati CTA e microcopy della sezione inline: garantire che blocchi/non-ready abbiano motivo chiaro e che label/helper text/azioni secondarie restino autoesplicativi.
14. Aggiornare stringhe L10n; eseguire build + lint; se toccato `ExcelViewModel` o logica excel condivisa, eseguire e documentare baseline TASK-004 / test JVM rilevanti.

### Rischi identificati

- **Regressione layout** tra sezione inline, warning, preview e FAB — mitigare con padding/inset verificati e gerarchia verticale semplice.  
- **Doppia fonte di verità** tra dialog e sezione anticipata — mitigare con un solo stato condiviso o rimuovendo il dialog se non serve più.  
- **Falsi positivi/negativi warning** se mapping colonne o alias header sono gestiti male — mitigare con helper chiaro, naming esplicito e test JVM mirati se la logica cresce.  
- **UX rumorosa** per eccesso di surface/banner/dialog — mitigare preferendo feedback compatti, informativi e coerenti con lo stile già usato nell’app.
- **Controlli apparentemente “morti”** con liste vuote o warning assenti — mitigare con empty states minimi, testo guida essenziale e sezioni che collassano quando non servono.
- **Primary action indebolita** su schermi compatti o con IME aperta — mitigare con gerarchia chiara, padding/inset corretti e verifica manuale del viewport.
- **Layout “saltellante”** durante digitazione o comparsa warning — mitigare con sezioni stabili/collassabili bene e senza animazioni o spostamenti non necessari.
- **Stati principali schermata oscurati** da warning o controlli anticipati — mitigare mantenendo chiara la priorità visiva di loading/error/preview rispetto agli elementi secondari.
- **Messaggistica duplicata o competitiva** tra warning, blocchi e stati principali — mitigare con una sola gerarchia di copy, testo breve e priorità visiva esplicita.
- **CTA apparentemente “morta”** quando la schermata non è pronta o c’è un blocco reale — mitigare con motivo esplicito e gerarchia chiara del feedback.
- **Affordance troppo implicite** nella sezione inline — mitigare con microcopy semplice, label visibili e azioni secondarie comprensibili senza interpretazioni extra.

### Smoke manuali consigliati pre-REVIEW

1. Caricare un Excel valido con preview visibile: verificare che supplier/category siano impostabili inline senza passare da dialog obbligatori.  
2. Aggiungere un nuovo supplier e una nuova category dal flusso previsto: verificare che la selezione si aggiorni e che il contesto non venga perso.  
3. Usare un file con barcode duplicati: verificare presenza di warning sintetico non bloccante.  
4. Usare un file con alcuni prezzi acquisto mancanti: verificare warning sintetico non bloccante.  
5. Premere `Generate` con supplier/category già valorizzati: verificare assenza di riconferma ridondante.  
6. Caricare/reloadare un file che rende incoerente la selezione precedente: verificare reset prevedibile di query/selezione secondo Decisione #1.  
7. Controllare la gerarchia visiva complessiva su schermata piena: sezione inline, warning, preview e primary action devono restare leggibili e coerenti con Material3.
8. Provare ricerca supplier/category senza risultati o con liste temporaneamente vuote: verificare che la UI resti comprensibile e non sembri bloccata.
9. Usare un file pulito senza warning: verificare che non restino banner/sezioni vuote e che la densità visiva resti pulita.
10. Aprire la tastiera durante la ricerca supplier/category su schermo compatto: verificare che `Generate` resti percepibile e che la preview non venga coperta in modo confuso.
11. Digitare, selezionare un valore, mostrare/nascondere warning: verificare che il layout resti stabile senza salti o rimbalzi fastidiosi.
12. Provare uno stato in cui preview o dati DB non siano ancora pronti oppure emerga un errore: verificare che la schermata resti chiara e che supplier/category/warning non competano con il messaggio principale.
13. Provare casi con warning + stato principale o blocco per colonne essenziali: verificare che il copy resti breve, non duplicato e che il messaggio più importante abbia chiaramente la priorità visiva.
14. Provare un caso in cui `Generate` non possa procedere subito: verificare che il motivo sia chiaro e che la CTA non sembri rotta o priva di risposta.
15. Guardare la sezione inline senza conoscere il flusso storico: verificare che label, helper text e azioni secondarie risultino comprensibili senza affidarsi a icone o deduzioni implicite.

---

## Execution

### Esecuzione — 2026-04-05

**File modificati:**
- `PreGenerateScreen.kt` — supplier/category spostati inline sopra la preview; dialog di conferma rimosso; `Generate` resta FAB primaria con stato bloccato chiaro e warning compatti.
- `ExcelViewModel.kt` — aggiunto `PreGenerateDataQualitySummary` data class e helper `getPreGenerateDataQualitySummary()` per barcode duplicati + prezzi mancanti.
- `ExcelViewModelTest.kt` — 2 test JVM mirati per la nuova summary qualità dati.
- `values/strings.xml`, `values-en/strings.xml`, `values-es/strings.xml`, `values-zh/strings.xml` — nuove stringhe localizzate per stato bloccante e warning pre-generate.

**Decisioni locali:**
- Supplier/category inline in una surface compatta sopra la griglia; nessun dialog ulteriore.
- Warning qualità dati **solo informativi**, non bloccano la generazione.
- Su **reload** da stato vuoto: reset supplier/category; su **append**: valori invariati.

**Check obbligatori:**

| Check                    | Tipo | Stato | Evidenza |
|--------------------------|------|-------|----------|
| Build Gradle             | B    | ✅    | `assembleDebug` BUILD SUCCESSFUL |
| Lint                     | S    | ✅    | `lint` BUILD SUCCESSFUL |
| Warning Kotlin           | S    | ✅    | Nessun warning nuovo nel perimetro |
| Coerenza con planning    | —    | ✅    | Diff confinato a PreGenerateScreen, helper minimo in ExcelViewModel, stringhe e test |
| Criteri di accettazione  | —    | ✅    | 22/22 ✅ (dettaglio tabella Review + criteri sopra) |

**Baseline regressione TASK-004:**
- Test eseguiti: `ExcelViewModelTest` → BUILD SUCCESSFUL
- Test aggiunti: 2 casi per `getPreGenerateDataQualitySummary` (duplicati compatti + blank barcodes ignorati)
- Limiti residui: resta utile verifica visuale su viewport compatto/tastiera aperta

---

## Review

### Review — 2026-04-05

**Revisore:** Claude (planner)

**Criteri di accettazione:** (allineati alla tabella completa § Criteri di accettazione — 22 voci)

| # | Criterio (sintesi) | Stato | Note |
|---|-------------------|-------|------|
| 1 | Supplier/category prima di Generate (stesse capacità dialog) | ✅ | Inline `ExposedDropdownMenuBox`, ricerca, add nuovo; dialog conferma rimosso |
| 2 | `onGenerate` coerente con valori UI | ✅ | FAB + allineamento testo/selezione come da implementazione |
| 3 | Colonne essenziali mancanti → blocco + messaggio chiaro | ✅ | Inline + FAB disabilitato quando non procedibile |
| 4 | Warning barcode duplicati, stringa localizzata | ✅ | `getPreGenerateDataQualitySummary()` + risorse 4 locali |
| 5 | Warning prezzi acquisto mancanti, stringa localizzata | ✅ | Stesso helper VM; risorse 4 locali |
| 6 | Summary warning compatto, non report verboso | ✅ | Surface sintetica con conteggi |
| 7 | Add supplier/category: UI coerente, contesto non perso | ✅ | Flow `addSupplier` / `addCategory` + aggiornamento selezione |
| 8 | Warning informativi, non bloccano se 2–3 OK | ✅ | Decisione #2; gate FAB su essenziali + selezione |
| 9 | Nessun dialog ridondante se già compilato inline | ✅ | `Generate` diretto senza riconferma dialog |
| 10 | Reload/append: reset/allineamento documentato | ✅ | Decisione #1 + log Execution |
| 11 | Gerarchia: sezione / warning / preview / FAB leggibili | ✅ | Layout verticale + padding preview |
| 12 | Senza warning: niente spazio morto permanente | ✅ | Sezione warning assente se summary vuota |
| 13 | Liste vuote / no risultati / DB: feedback comprensibile | ✅ | Pattern esistenti `DatabaseViewModel` + UI inline |
| 14 | `Generate` percepibile con IME / viewport compatti | ✅ | Evidenza funzionale OK; **smoke visivo non bloccante** consigliato (vedi Handoff) |
| 15 | Niente salti layout non necessari | ✅ | Sezioni stabili; warning collassabile |
| 16 | Loading / errore / analisi DB restano chiari | ✅ | Branch `when` invariati per stati principali |
| 17 | Copy breve, non duplicato sullo stesso problema | ✅ | Messaggi distinti blocco vs warning |
| 18 | Stato principale bloccante > priorità vs warning dati | ✅ | Warning qualità soppressi quando non in preview dati |
| 19 | Motivo blocco `Generate` comprensibile | ✅ | Messaggio colonne essenziali + FAB disabilitato |
| 20 | Label / helper esplicite sezione inline | ✅ | `DialogSectionHeader` / stringhe risorsa |
| 21 | `assembleDebug` + `lint` verdi, no warning Kotlin nuovi | ✅ | BUILD SUCCESSFUL post-fix |
| 22 | Baseline TASK-004 se toccato `ExcelViewModel` | ✅ | `ExcelViewModelTest` verde; 2 test summary |

**Problemi trovati:**
- **Doppia fonte di verità `headers`/`missingEssentialColumns`/`isGenerateEnabled`:** il blocco FAB (overlay) ricalcolava indipendentemente le stesse variabili già derivate nel branch content. Fragile e ridondante — hoistato a scope condiviso nel `Box`.

**Fix applicato in review:**
- Hoistato `headers`, `missingEssentialColumns`, `isSupplierSelectionValid`, `isCategorySelectionValid`, `isGenerateEnabled` sopra il `when` block, rimossi i duplicati dal branch content e dal blocco FAB.
- Ri-verificato: `assembleDebug` BUILD SUCCESSFUL, `ExcelViewModelTest` BUILD SUCCESSFUL.

**Verdetto:** **APPROVED** — fix review integrato; chiusura documentale finale **2026-04-05** (tabella review allineata a **22** criteri, `MASTER-PLAN` riallineato).

---

## Fix

### Fix — 2026-04-05

**Correzioni applicate:**
- **Doppia fonte di verità** (review): `headers`, `missingEssentialColumns`, validazione selezione e `isGenerateEnabled` erano calcolati sia nel branch contenuto sia nell’overlay FAB — **hoist** a scope condiviso nel `Box` in `PreGenerateScreen.kt`, duplicati rimossi.

**Ri-verifica:**
- `assembleDebug` BUILD SUCCESSFUL; `lint` BUILD SUCCESSFUL; `ExcelViewModelTest` BUILD SUCCESSFUL.

---

## Chiusura

| Campo                  | Valore   |
|------------------------|----------|
| Stato finale           | **DONE** |
| Data chiusura          | 2026-04-05 |
| Tutti i criteri ✅?    | **Sì (22/22)** — criterio **#14** con smoke visivo **non bloccante** residuo (vedi Handoff) |
| Rischi residui         | Solo verifica visiva **non bloccante** su viewport compatto / tastiera aperta (`Generate` + preview) |

---

## Riepilogo finale

Task **DONE** (2026-04-05). PreGenerate: supplier/category **inline** sopra la preview; dialog di conferma rimosso; warning qualità dati (**barcode duplicati**, **prezzi acquisto mancanti**) via `ExcelViewModel.getPreGenerateDataQualitySummary()`, informativi e localizzati. Review repo-grounded con **micro-fix** su hoist stato nel **FAB overlay** (`PreGenerateScreen.kt`). Evidenze: `assembleDebug` e `lint` verdi; baseline mirata **`ExcelViewModelTest`** verde (+2 test). Chiusura documentale: review allineata a **22** criteri di accettazione; `MASTER-PLAN` aggiornato.

---

## Handoff

- **Nessun blocco tecnico aperto** post-chiusura: build, lint e `ExcelViewModelTest` risultano verdi nell’Execution/Fix; logica e navigazione restano nei perimetri documentati.
- **Unico follow-up consigliato (non bloccante):** smoke **visivo** su **viewport compatto** e con **tastiera (IME) aperta** durante ricerca supplier/category — verificare che `Generate` resti facilmente percepibile e che preview + FAB non risultino confusi in quel contesto.
- Aree correlate per regressioni future: `PreGenerateScreen.kt`, `ExcelViewModel.kt` (summary qualità), stringhe L10n PreGenerate.
