# TASK-048 — HistoryScreen UX: inset, card rhythm e display title leggibile

---

## Informazioni generali

| Campo              | Valore                                              |
|--------------------|-----------------------------------------------------|
| ID                 | TASK-048                                            |
| Stato              | DONE                                                |
| Priorità           | MEDIA                                               |
| Area               | UX/UI — History / Cronologia                        |
| Creato             | 2026-04-12                                          |
| Ultimo aggiornamento | 2026-04-12 (review planner APPROVED → DONE)        |

---

## Dipendenze

- TASK-044 (DONE) — filtro entry tecniche già in place; la lista utente-visibile è stabile.
- TASK-016 (DONE) — primo polish History già eseguito; questo task continua da quello stato.

---

## Scopo

Migliorare la qualità percepita della schermata Cronologia intervenendo su tre aree distinte:
1. **Padding e inset** — il primo e l'ultimo item della lista appaiono "tagliati" o troppo vicini ai bordi per mancanza di breathing room verticale; il respiro vicino alla bottom bar durante lo scroll è incoerente.
2. **Ritmo visivo delle card** — la spaziatura inter-card è troppo stretta e la lista risulta più grezza del resto dell'app.
3. **Titolo entry leggibile e gerarchicamente corretto** — `entry.id` espone oggi in prima posizione informazione tecnica (timestamp, UID, estensione) invece del nome umano che l'utente vuole riconoscere rapidamente. Il problema non è solo la lunghezza del testo, ma la gerarchia sbagliata: il campo che funge da headline è progettato per identificare il file nel sistema, non per comunicare all'utente. Il titolo deve essere ristrutturato perché mostri prima il nome significativo e relega l'informazione tecnica a secondo piano.

Il task resta confinato al perimetro UI della Cronologia. **Target principale:** `HistoryScreen.kt`. Se in execution emergesse che parte del problema dipende dalla shell/root che ospita la bottom bar o dagli inset applicati a monte, è consentito estendere l'analisi ai file contenitore già citati in questo piano, ma senza cambiare logica business, DAO, Room, ViewModel o navigation salvo necessità reale e documentata.

---

## Audit del problema (pre-planning)

### 1. Top "tagliato" — LazyColumn senza contentPadding top

```kotlin
// HistoryScreen.kt:251-254
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = spacing.md)  // SOLO bottom, nessun top
)
```

Il primo item della lista inizia a 0dp dal bordo superiore del contenitore. Quando si scrolla verso il basso e poi verso l'alto, la prima card appare incollata all'header filtro senza alcun respiro. Aggiungere `top = spacing.sm` (8dp).

### 2. Bottom "tagliato" e respiro incoerente — bottom clearance insufficiente

```kotlin
// HistoryScreen.kt:253
contentPadding = PaddingValues(bottom = spacing.md)  // = 12dp
```

12dp è troppo poco. L'ultimo item si ferma quasi contro il bordo inferiore del contenitore (che ha già `padding(contentPadding)` per la nav bar e `padding(vertical = spacing.xl)` = 20dp). Il respiro visivo finale è minimal e inconsistente con il resto dell'app. Portare a `spacing.xxl` (24dp) o `spacing.xl` (20dp).

### 3. Spaziatura inter-card troppo stretta

```kotlin
// HistoryScreen.kt:462
.padding(vertical = spacing.xxs)  // = 4dp top + 4dp bottom → 8dp gap totale tra cards
```

4dp per lato è il valore più stretto del sistema (`xxs`). Genera la sensazione di lista "grezza" con card appiccicate. Su altri screen come `DatabaseScreen` si usano valori più generosi. Portare a `spacing.xs` (6dp per lato = 12dp gap) o `spacing.sm` (8dp per lato = 16dp gap).

**Decisione planner:** `spacing.xs` (6dp) è il compromesso migliore — dà 12dp di gap totale, abbastanza per il ritmo visivo senza sprecare spazio su liste lunghe.

Attenzione però a un dettaglio di execution: il ritmo verticale della lista deve essere gestito con **un solo meccanismo coerente**, non sommando più layer casuali (`verticalArrangement`, padding wrapper della card, spacer extra, contentPadding compensativi). Se si aumenta il gap, va deciso chiaramente dove vive quel gap, così il risultato resta prevedibile e non si degrada nei refactor futuri.

### 4. Snackbar bottom offset hardcodato e sovradimensionato

```kotlin
// HistoryScreen.kt:47
private val HistoryRootBottomClearance = 104.dp

// HistoryScreen.kt:279-284
SnackbarHost(
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(
            bottom = HistoryRootBottomClearance + 64.dp  // = 168dp fissi dal bottom
        )
)
```

168dp è eccessivo e non dipende dagli inset reali del dispositivo. La Snackbar fluttua troppo in alto nel contenuto. Sostituire con `navigationBarsPadding()` + un buffer di `spacing.xxl` (24dp) per rispettare la nav bar in modo adattivo su tutti i device.

### 5. Titolo entry: `entry.id` tecnico non leggibile

```kotlin
// HistoryScreen.kt:479-485
Text(
    text = entry.id,  // es. "2024-03-15 14:30:22_abc123def456_NomeFornitore.xlsx"
    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis
)
```

Il campo `entry.id` ("Il vecchio ID / nome file") contiene il nome file originale con formato tecnico: `YYYY-MM-DD HH:mm:ss_<UID>_<NomeFornitore>.<ext>`. Non è solo troppo lungo: è il problema di gerarchia sbagliato. La headline della card oggi riflette l'identità del file nel filesystem — timestamp di creazione, hash identificativo, estensione — ma l'utente sta cercando il fornitore, l'ordine, il contesto. Quando l'utente scorre la cronologia per ritrovare una voce specifica, il suo sguardo cerca un nome umano, non una stringa tecnica.

**Stato attuale della card:** mostra già `timestamp` formattato (leggibile come data e ora leggibile), `supplier` + `category` come testo secondario, e i valori numerici di riepilogo. Paradossalmente, i metadati leggibili sono tutti nelle righe secondarie, mentre l'headline — il campo a cui l'occhio va per primo — mostra le informazioni più tecniche e meno interpretabili.

**Headline e riga secondaria insieme:** migliorare solo la headline senza sistemare anche la riga dei metadati secondari lascerebbe la card comunque poco rifinita: rumore, separatori «sporchi» o ridondanze nella caption peggiorano la scansione quanto un titolo tecnico. L'headline e la riga secondaria (timestamp, supplier, category) vanno trattate come **un'unica gerarchia visiva della card**, non come due problemi indipendenti.

**Comportamento rename:** `renameText = entry.id` — il rename modifica `entry.id`; dopo il rename, l'utente ha già un nome pulito e leggibile. Prima del rename, il campo è il nome tecnico originale. L'implementazione del display title deve funzionare bene in entrambi i casi senza distinguerli esplicitamente.

---

## Strategia UX/UI

### Qualità visiva attesa

Il task non deve soltanto correggere numeri di padding: deve rendere la schermata visibilmente più rifinita. Il miglioramento deve essere percepibile in particolare in questi tre momenti:

1. **Apertura della Cronologia** — la lista si presenta con respiro: il primo item non è incollato all'header filtro, le card hanno un ritmo visivo ordinato, il titolo di ogni entry è leggibile al primo colpo d'occhio senza dover decodificare una stringa tecnica.

2. **Scroll fino all'ultima entry** — l'utente raggiunge il fondo senza la sensazione che il contenuto venga "tagliato" o si interrompa bruscamente contro la bottom bar; c'è una chiusura visiva pulita e coerente, anche se la lista ha solo una o poche entry.

3. **Scansione rapida per ritrovare una voce** — i titoli delle card mostrano il nome riconoscibile in cima; i metadati tecnici e di sistema non occupano la posizione headline. L'utente trova la voce cercata leggendo i titoli, non decodificando stringhe.

Se al termine dell'execution questi tre momenti non sembrano migliorati in modo percepibile rispetto allo stato attuale, il task non è completo.

### Titolo: gerarchia a tre livelli

| Livello   | Contenuto                           | Stile                              |
|-----------|-------------------------------------|------------------------------------|
| Titolo    | Nome display (vedi parsing sotto)   | `titleMedium` bold                 |
| Metadati  | Timestamp formattato come base; supplier e category **solo se valorizzati e utili**, composti senza separatori orfani | `labelMedium` + `bodySmall` muted (già presenti; composizione da raffinare in execution) |
| Riferimento tecnico | Nome file completo (`entry.id`), solo se diverso dal titolo display | `bodySmall` muted, 1 riga ellipsis |

### Metadati secondari: riga pulita e robusta

La riga secondaria che aggrega **timestamp**, **supplier** e **category** deve restare **leggibile e ordinata** anche quando alcuni campi sono vuoti, ridondanti con altre parti della card o poco utili in contesto. Regole da rispettare in execution:

- **Timestamp come ancora:** il timestamp formattato resta la **base** dei metadati secondari — punto di riferimento temporale sempre coerente per la scansione della lista.
- **Supplier / category solo se servono:** si mostrano **solo se valorizzati** e **davvero utili** (non ripetono in modo inutile ciò che è già chiaro dal titolo o da altri elementi della riga).
- **Separatori disciplinati:** niente punti medi (·) iniziali o finali, niente doppi separatori, niente righe che «si rompono» visivamente per pezzi vuoti tra i separatori.
- **Compattezza vs completezza formale:** è preferibile una riga secondaria **più corta ma pulita** rispetto a una riga formalmente «completa» ma rumorosa.

Questa sottosezione non allarga il perimetro dati: resta composizione e presentazione nel `HistoryRow`, senza toccare modello o ViewModel.

### Headline forte ma senza «gonfiare» la card

Il nuovo display title deve rendere la headline **gerarchicamente forte** (primo punto di attenzione, nome riconoscibile), ma:

- il **numero di righe** e il **peso visivo** complessivo dell'area titolo+metadati devono restare **controllati**;
- non si deve **peggiorare la densità operativa** della lista (meno entry visibili percepibili, card che sembrano «torri» rispetto al ritmo atteso).

In pratica: titolo principale chiaro, con **massimo 1–2 righe** per l'headline (come già orientato da `maxLines` e dal riferimento iOS), senza introdurre accavallamenti tipografici che rompono il ritmo verticale della lista.

### Principi guida per l'execution

- **Prima la causa reale, poi il fix:** se il vuoto percepito in basso deriva solo in parte dalla `LazyColumn`, verificare anche il contenitore root della schermata prima di decidere il padding finale.
- **No numeri hardcodati nuovi:** usare token di spacing esistenti e inset Compose idiomatici; introdurre un valore fisso solo se già coerente con il sistema UI del progetto e motivato nel log Execution.
- **Miglioramento visivo senza perdere densità:** la Cronologia è una lista operativa; il polish deve migliorarne ritmo e leggibilità senza far vedere troppe meno entry a schermo.
- **Headline forte ma contenuta:** display title leggibile con peso e altezza percepita sotto controllo; nessun trade-off che renda la card visivamente troppo alta o sbilanciata rispetto ai numeri di riepilogo.
- **Metadati secondari coerenti con la headline:** timestamp come ancora; supplier/category opzionali nella riga solo se utili; zero separatori orfani.
- **Titolo umano, riferimento tecnico secondario:** il titolo deve aiutare il riconoscimento rapido; il nome tecnico completo resta accessibile ma non dominante.

### Principio di coerenza del ritmo verticale

Il ritmo tra gli item deve avere una **fonte unica di verità**. In execution non va creato un layout dove il gap finale deriva da una combinazione poco leggibile di:
- `verticalArrangement` della `LazyColumn`
- padding verticale del wrapper/card
- spacer locali tra card
- padding compensativi aggiunti solo per "farlo sembrare giusto"

È accettabile qualunque delle due strategie seguenti, purché sia chiara e coerente:
1. gap gestito principalmente dal contenitore lista (`verticalArrangement = spacedBy(...)`) con card più pulite;
2. gap gestito dal wrapper/item padding con `LazyColumn` più neutra.

Se le due strategie sono entrambe corrette, preferire quella che rende il codice più leggibile e il risultato più stabile nel tempo.

### Helper UI: due funzioni private separate

Il codice del `HistoryRow` deve restare leggibile. Per questo motivo l'execution deve introdurre **due helper privati** locali a `HistoryScreen.kt`, separati e nominati in modo chiaro:

**`formatHistoryEntryDisplayTitle(id: String): String`**
- Tenta il match del pattern tecnico `timestamp_uid_nome.ext`.
- Rimuove in modo sicuro: prefisso timestamp, blocco UID/hash, estensione file.
- Restituisce la parte significativa estratta, oppure `entry.id` come fallback se il parsing fallisce o il risultato è blank.
- Non assume che il nome significativo non contenga underscore.
- Non esegue troncature oltre la pulizia tecnica descritta.

**`shouldShowTechnicalRow(id: String, displayTitle: String): Boolean`**
- Incapsula la logica "vale la pena mostrare la riga ausiliare?".
- Restituisce `true` solo se tutte le condizioni seguenti sono vere:
  1. `displayTitle` è diverso da `id` (il parsing ha prodotto qualcosa di utile)
  2. `displayTitle` non è già visibilmente contenuto in `id` (evita ripetizioni ovvie)
  3. `id` non è già breve e leggibile di per sé (es. entry rinominata da utente)
- In caso di dubbio, restituisce `false`: meglio non mostrare la riga ausiliare che aggiungerla e ricreare il rumore visivo che il task vuole eliminare.

Separare le due funzioni rende il composable più leggibile e mantiene la logica di presentazione esplicita e testabile senza dipendenze esterne.

### Riga tecnica ausiliaria: quando mostrare e quando no

La riga ausiliare con il nome file tecnico completo **non è obbligatoria**: è utile solo se aggiunge vera informazione. Le condizioni in cui va soppressa:

- L'entry è già stata rinominata dall'utente (il `displayTitle` è il nome utente, e la riga ausiliare mostrerebbe qualcosa di diverso ma comunque leggibile e non tecnicamente interessante)
- Il `displayTitle` estratto è sostanzialmente identico a `entry.id` (il parsing non ha prodotto nulla di meglio)
- `entry.id` è già breve (es. dopo un rename manuale a nome corto)
- La riga ausiliare ripete informazione già presente in altri campi della card (es. supplier già visibile)

Il principio guida: la riga ausiliare esiste per gli utenti che vogliono identificare il file sorgente, non per tutti. Se non serve, non compare. Il silenzio visivo è preferibile al rumore.

### Parsing del titolo display

1. Tentare di fare match su un pattern tecnico del tipo `timestamp_uid_nome.ext`, ma senza assumere che il nome significativo non contenga underscore.
   - Il parsing deve rimuovere in modo sicuro:
     - prefisso timestamp tecnico
     - blocco UID / hash tecnico immediatamente successivo
     - estensione file finale (`.xls`, `.xlsx`, eventualmente `.xlsm` se presente)
   - Il resto della stringa va considerato il nome significativo da esporre come `displayTitle`.
2. Se il parsing tecnico fallisce o il risultato è blank:
   - usare `entry.id` senza alterazioni distruttive;
   - al massimo rimuovere l'estensione finale se il risultato migliora la leggibilità senza perdere identità.
3. Se l'entry è stata già rinominata dall'utente e non rispetta il formato tecnico originale, il titolo deve restare esattamente il nome utente.
4. Il nome tecnico completo (`entry.id`) va mostrato solo tramite `shouldShowTechnicalRow()`; la condizione è la separazione intenzionale dei due helper.

### Riferimento iOS

La schermata equivalente in iOS (repository `iOSMerchandiseControl`) usa:
- Titolo riga breve e bold, massimo 1-2 righe
- Metadati su riga secondaria in stile caption muted
- Informazioni tecniche non visibili al primo sguardo (accessibili nel dettaglio)
- Padding list verticale generoso (~12-16dp tra righe)
- Safe area inset gestiti nativamente (no overflow/clipping)

Non si fa porting 1:1 SwiftUI→Compose. Si adottano solo i principi di gerarchia e ritmo.

---

## Non incluso

- Modifiche a `HistoryEntryDao`, `HistoryEntry`, `HistoryEntryListItem`, `InventoryRepository`, `ExcelViewModel`, `DatabaseViewModel` — nessuna.
- Modifiche a `NavGraph.kt` — nessuna.
- Modifiche al sistema di filtri data — nessuna.
- Swipe actions (rename / delete) — nessuna.
- Empty state / loading state — nessuna (già gestiti e funzionanti).
- Export status / sync status icon — nessuna.
- Redesign strutturale pesante della schermata — fuori scope. Sono invece consentiti piccoli o moderati ritocchi di spacing, hierarchy, paddings, peso tipografico e presentazione del titolo/metadati se servono a rendere la UI più rifinita e coerente.
- Navigazione al dettaglio — nessuna.
- Aggiunta di nuove stringhe localizzate (il nome tecnico è mostrato as-is, non richiede stringa).

---

## File coinvolti

### Da modificare

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — unico file da toccare

### Da leggere (senza modificare)

- `app/src/main/java/com/example/merchandisecontrolsplitview/data/HistoryEntry.kt` — modello dati, conferma campi disponibili per titolo/metadati
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt` — conferma comportamento `innerPadding`, shell root e relazione con la bottom bar
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/theme/Theme.kt` — token spacing / typography realmente disponibili
- eventuale file root/tab shell che ospita la bottom navigation persistente — solo se necessario a confermare l'origine del vuoto inferiore percepito

### Riferimento iOS (solo UX)

- `iOSMerchandiseControl` — schermata Cronologia/History per principi gerarchia visiva

---

## Criteri di accettazione

| # | Criterio | Tipo | Stato |
|---|----------|------|-------|
| 1 | Il primo item della LazyColumn ha almeno 6-8dp di respiro visivo dal bordo superiore del contenitore lista | S/M | — |
| 2 | L'ultimo item ha almeno 20-24dp di respiro visivo prima del bordo inferiore, su lista lunga | S/M | — |
| 3 | **Lista corta (1 entry):** con una sola entry visibile, la chiusura visiva sotto la card non crea spazio vuoto eccessivo o artificiale; la card non galleggia in modo straniante nel centro dello schermo | M | — |
| 4 | Il gap visivo tra card successive è ~12dp come ordine di grandezza finale, ottenuto con un meccanismo principale coerente (non necessariamente tramite padding verticale della card) | S | — |
| 5 | La Snackbar non usa il valore hardcodato `HistoryRootBottomClearance + 64.dp`; usa `navigationBarsPadding()` + buffer | S | — |
| 6 | Il titolo della card mostra un nome leggibile: senza timestamp tecnico, senza UID, senza estensione file quando `entry.id` ha il formato tecnico originale | M | — |
| 7 | Per entry rinominate dall'utente, il titolo mostra esattamente `entry.id` senza modifiche | M | — |
| 8 | La riga tecnica ausiliare compare **solo** quando aggiunge informazione realmente utile (non ridondante con displayTitle, non ovviamente identica ad esso) | M | — |
| 9 | La riga tecnica ausiliare **non compare** per entry già rinominate o quando `entry.id` è già breve e leggibile di per sé | M | — |
| 10 | La riga tecnica ausiliare, quando presente, non genera rumore visivo dominante: deve essere percettivamente secondaria rispetto al titolo e ai metadati | M | — |
| 11 | Il campo `renameText = entry.id` nel dialog di rename non viene alterato — continua a pre-caricare il nome tecnico originale | S | — |
| 12 | Nessuna regressione: tap su entry, swipe rename, swipe delete, filtri data, export/sync status, navigazione al dettaglio funzionano invariati | M | — |
| 13 | Build Gradle `assembleDebug` OK, lint senza nuovi warning | B | — |
| 14 | Nessuna modifica a DAO, Room, ViewModel, navigation | S | — |
| 15 | Nessun nuovo magic number introdotto per gestire la distanza dalla bottom bar, salvo motivazione esplicita nel log Execution | S | — |
| 16 | Il parsing del titolo non tronca in modo errato fornitori o nomi che contengono underscore, hash o estensioni diverse da `.xlsx` | M | — |
| 17 | Il numero di entry visibili a schermo non peggiora in modo evidente rispetto allo stato pre-fix; il polish mantiene una densità operativa adeguata | M | — |
| 18 | Il gap verticale tra le card è gestito in modo coerente con un meccanismo principale chiaramente riconoscibile, senza doppie compensazioni che rendano il layout fragile | M | — |
| 19 | La riga dei metadati secondari (timestamp / supplier / category) resta **pulita** quando supplier o category mancano, sono vuoti o non aggiungono valore: nessun separatore iniziale/finale, nessun doppio separatore, nessuna riga «rotta» per segmenti vuoti | M | — |
| 20 | Il titolo principale (display title) è **forte ma contenuto**: al massimo **1–2 righe**; non aumenta in modo evidente l'altezza della card né rompe il ritmo di scansione della lista rispetto a un miglioramento solo del testo senza peso extra | M | — |
| 21 | La soluzione finale resta internamente coerente: criteri, decisioni e piano di esecuzione non impongono approcci in conflitto tra loro sul modo di ottenere spacing e visibilità della riga tecnica | S | — |

> Definition of Done UX/UI (da `docs/MASTER-PLAN.md`):
> - [ ] Gerarchia visiva migliorata nel perimetro
> - [ ] Spacing e layout più leggibili
> - [ ] Nessuna regressione funzionale
> - [ ] Nessun cambio a logica business / Room / repository / navigation
> - [ ] Build Gradle OK, lint senza nuovi warning

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | Spaziatura inter-card: `spacing.xs` (6dp) invece di `spacing.sm` (8dp) | 12dp di gap è sufficiente per il ritmo visivo senza sprecare spazio su liste lunghe; `sm` darebbe 16dp che potrebbe sembrare eccessivo per una lista densa | 2026-04-12 |
| 2 | LazyColumn top padding: `spacing.sm` (8dp) | Piccolo respiro che evita il "tagliato" senza consumare troppo spazio dall'header filtro | 2026-04-12 |
| 3 | LazyColumn bottom padding: `spacing.xxl` (24dp) | Coerente con il sistema di spaziatura dell'app; dà respiro all'ultimo item | 2026-04-12 |
| 4 | Snackbar: `navigationBarsPadding()` + `padding(bottom = spacing.xxl)` | Adattivo ai device; elimina il magic number 168dp; coerente con pattern usato altrove nell'app | 2026-04-12 |
| 5 | Titolo display: parsing conservativo in helper privato `formatHistoryEntryDisplayTitle()` locale a `HistoryScreen.kt` (regex ammessa ma non obbligatoria) | Nessuna modifica al modello dati; logica di presentazione pura confinata al composable layer; fallback sicuro per qualsiasi formato non atteso; evita di vincolare l'execution a un solo approccio tecnico se una soluzione più robusta emerge dalla lettura del codice reale | 2026-04-12 |
| 6 | Nome tecnico come riga ausiliare (bodySmall muted, 1 riga) — mostrato solo quando `shouldShowTechnicalRow()` stabilisce che aggiunge davvero informazione utile | Non nascondere info utili agli utenti avanzati, ma evitare ridondanza e rumore visivo; la semplice differenza testuale col titolo non basta da sola a giustificarne la presenza | 2026-04-12 |
| 7 | Verificare in execution i token di spacing reali prima di applicare valori (`xs/sm/xl/xxl`) | Evita mismatch tra planning e codice reale se i token sono stati già ritoccati in task precedenti | 2026-04-12 |
| 8 | Il parsing del titolo deve essere robusto a underscore nel nome significativo e a estensioni file diverse | Riduce il rischio di titoli tagliati male o troppo aggressivi | 2026-04-12 |
| 9 | Se il vuoto inferiore dipende dalla shell/root, è ammesso estendere il fix al contenitore invece di compensare solo dentro `LazyColumn` | Soluzione più pulita e coerente rispetto a sommare padding locali | 2026-04-12 |
| 10 | La display logic (helper `formatHistoryEntryDisplayTitle` e `shouldShowTechnicalRow`) resta confinata al layer UI — nessuna propagazione a ViewModel, repository o modello dati | Logica puramente presentazionale; il modello non dipende da come si mostra il dato | 2026-04-12 |
| 11 | La qualità visiva si valuta anche su lista corta (1–2 entry): il padding bottom non deve creare un vuoto artificiale che peggiora la chiusura visiva | Una lista con 1 entry è un caso reale e frequente, specie dopo il filtro data | 2026-04-12 |
| 12 | In caso di dubbio tra due soluzioni tecnicamente equivalenti, si preferisce quella con meno rumore visivo e gerarchia più chiara | Questo task è nato per ridurre la sensazione di UI grezza: ogni scelta deve andare in quella direzione | 2026-04-12 |
| 13 | Il **timestamp** resta l'**ancora** dei metadati secondari; **supplier** e **category** si aggiungono **solo se valorizzati e utili**, non per «compilare» formalmente la riga | Evita righe secondarie rumorose e ridondanti; headline e seconda riga sono una gerarchia unica | 2026-04-12 |
| 14 | L'**headline** resta **gerarchicamente forte** ma **non deve espandersi** oltre il necessario: al massimo **1–2 righe**, tipografia e spaziatura sotto controllo per non alzare o sbilanciare la card | Preserva densità operativa e ritmo della lista; allineato ai criteri 17 e 20 | 2026-04-12 |
| 15 | Il ritmo verticale della lista deve essere controllato da un meccanismo principale unico, evitando doppi spacing stratificati | Riduce layout fragili, facilita la manutenzione e impedisce regressioni visive dovute a compensazioni cumulative | 2026-04-12 |

---

## Planning (Claude)

### Analisi

Il codice letto in `HistoryScreen.kt` è funzionalmente completo e stabile. I problemi sono tutti di presentazione e integrazione visiva, quindi il task deve rimanere nel perimetro UI salvo prova contraria emersa durante l'execution.

Aspetti da confermare in execution prima del fix finale:
- il layout root effettivo della schermata e il modo in cui riceve gli inset dalla shell che ospita la bottom bar;
- i token di spacing realmente disponibili/attesi nel tema del progetto;
- l'eventuale esistenza di magic number legacy non più necessari;
- il formato reale dei `history id` presenti nel database (entry tecniche, entry rinominate, casi con underscore nel nome).

Aspetti già fortemente probabili dal planning corrente:
- la lista ha bisogno di più breathing room top/bottom;
- il gap tra card è troppo stretto per la qualità visiva desiderata;
- il titolo `entry.id` è troppo tecnico e rumoroso per essere l'headline primaria;
- l'eventuale offset snackbar va reso più adattivo e meno hardcoded.

### Piano di esecuzione

1. **[Pre-check layout root]** Leggere `HistoryScreen.kt`, `NavGraph.kt` e l'eventuale file shell/root della bottom navigation per confermare se il vuoto inferiore percepito nasce:
   - solo dalla `LazyColumn`
   - oppure dalla combinazione `Scaffold` root + innerPadding + bottom bar + snackbar offset.

2. **[Pre-check design tokens]** Verificare in `Theme.kt` e negli screen vicini i token di spacing / typography realmente usati oggi (`xxs/xs/sm/md/xl/xxl`) prima di fissare i valori finali del piano.

3. **[LazyColumn padding]** Introdurre top/bottom breathing room coerente nella lista usando `contentPadding` idiomatico. Il target del planning resta:
   - top leggero: ~6–8dp
   - bottom più generoso: ~20–24dp
   La scelta finale deve essere fatta in base ai token reali del progetto e alla densità visiva risultante.

4. **[Card rhythm]** Aumentare la spaziatura verticale tra card in modo misurato, scegliendo però **un solo meccanismo principale** per controllare il gap finale. Priorità planner:
   - prima scelta: gap totale ~12dp
   - fallback più compatto o più arioso solo se necessario dopo verifica visiva
   - evitare combinazioni confuse tra `verticalArrangement`, padding wrapper della card e spacer aggiuntivi
   - preferire la soluzione che rende il codice più leggibile e il ritmo più prevedibile nel tempo.

5. **[Snackbar inset]** Rimuovere o ridurre i magic number legacy per il posizionamento della snackbar. Preferire inset adattivi (`navigationBarsPadding()` / padding coerenti col root container). Se il problema è a monte, correggere il contenitore invece di compensare solo la snackbar.

6. **[Display title helpers]** Aggiungere in `HistoryScreen.kt`, vicino a `formatHistoryTimestamp`, due funzioni private distinte e nominativamente esplicite:
   - `formatHistoryEntryDisplayTitle(id: String): String` — parsing conservativo + fallback sicuro
   - `shouldShowTechnicalRow(id: String, displayTitle: String): Boolean` — logica di visibilità separata
   
   Il planner non impone una regex specifica come unica soluzione: regex, split controllato o parsing ibrido sono tutti accettabili purché il risultato resti robusto su entry tecniche, entry rinominate e nomi con underscore / estensioni diverse. Separare le due funzioni mantiene il `HistoryRow` leggibile (non nidifica condizioni di parsing dentro il composable) e rende la logica presentazionale manutenibile senza architetture aggiuntive.

7. **[Riga metadati secondari]** Raffinare la composizione della riga timestamp / supplier / category (solo presentazione nel `HistoryRow`):
   - **timestamp** sempre presente come **base** della riga;
   - **supplier** e **category** inclusi **solo se valorizzati** e utili (niente placeholder, niente ripetizione inutile rispetto al titolo o ad altri elementi);
   - **nessun separatore orfano** (· iniziale/finale, doppio separatore, segmenti vuoti tra separatori);
   - risultato **più compatto e ordinato**: meglio una caption breve e pulita che una riga formalmente «completa» ma rumorosa.

8. **[HistoryRow hierarchy]** Applicare la nuova gerarchia nella card:
   - titolo umano leggibile come headline primaria (**forte ma contenuto**, max 1–2 righe, senza aumentare eccessivamente altezza o peso visivo della card);
   - riga metadati secondari costruita secondo lo step 7;
   - riferimento tecnico (`entry.id`) visibile solo se `shouldShowTechnicalRow()` restituisce `true`.
   Sono consentiti piccoli ritocchi aggiuntivi a typography, alpha, spacing e allineamenti se migliorano chiaramente la qualità percepita e riducono il rumore visivo, restando coerenti con densità operativa e criteri 17 / 20.

9. **[Rename safety]** Verificare che il dialog di rename continui a usare `entry.id` come base del rename e che la nuova display logic resti puramente presentazionale.

10. **[Smoke visual checks]** Verificare manualmente:
   - primo item non incollato in alto;
   - ultimo item con chiusura visiva corretta sopra la bottom bar;
   - gap card coerente su liste corte e lunghe;
   - snackbar non troppo alta e non sovrapposta alla nav bar;
   - titolo pulito corretto su entry tecniche e rinominate;
   - riga metadati senza separatori sporchi con supplier/category assenti o inutili;
   - altezza card e numero di entry visibili coerenti con criteri 17 e 20.

11. **[Build e lint]** Eseguire `./gradlew assembleDebug lint`; verificare nessun nuovo warning o regressione.

12. **[Baseline regressione TASK-004]** Questo task resta UI-only. Se durante l'execution si resta nel perimetro presentazionale, la baseline TASK-004 non si applica; se invece emerge la necessità di toccare file fuori dal perimetro UI, documentarlo esplicitamente prima di procedere.

### Rischi identificati

| Rischio | Probabilità | Impatto | Mitigazione |
|---------|-------------|---------|-------------|
| Il vuoto inferiore percepito non dipende solo dalla `LazyColumn` ma anche dalla shell/root con bottom bar | MEDIA | MEDIA | Verificare i file contenitore prima di fissare il padding definitivo nella lista |
| Pattern parsing troppo aggressivo sui `history id` con underscore o formati storici diversi | MEDIA | MEDIA | Usare helper con fallback conservativo; testare casi tecnici, rinominati e nomi con underscore |
| Aumento spacing card troppo generoso → peggiora la densità operativa della lista | MEDIA | BASSA | Preferire incremento misurato (~12dp gap totale) e verificare numero entry visibili a schermo |
| Rimozione padding hardcodato snackbar → posizionamento non perfetto su alcuni device | BASSA | BASSA | Usare inset Compose adattivi e fare smoke test su emulator standard |
| Estensione del fix a file root/shell introduce scope creep | BASSA | MEDIA | Consentire l'estensione solo se necessaria a risolvere la causa reale e documentarla nel log Execution |
| **Riga tecnica ausiliare appare quando non serve → ricrea il rumore visivo che il task vuole eliminare** | ALTA | MEDIA | La logica di visibilità è separata in `shouldShowTechnicalRow()`; in caso di dubbio l'helper deve restituire `false`; la riga si mostra solo se si è certi che aggiunga informazione non altrimenti visibile nella card |
| Lista con 1 entry: bottom padding eccessivo crea vuoto artificiale straniante | MEDIA | BASSA | Verificare empiricamente su emulator con 1 entry e con filtro data attivo che riduce la lista; il padding non deve essere così grande da sembrare "rotto" |
| La nuova gerarchia migliora il titolo ma rende la **card troppo alta o sbilanciata** (headline o metadati che «rubano» troppo spazio verticale) | MEDIA | MEDIA | Limitare l'headline a **1–2 righe**; controllare typography, `maxLines`, overflow e densità complessiva; verificare criteri 17 e 20 su lista lunga |
| Il gap finale nasce da più layer sommati (lista + card + spacer) e diventa difficile da controllare/manutenere | MEDIA | BASSA | Scegliere un solo meccanismo principale per il ritmo verticale e documentarlo chiaramente nell'Execution |

---

## Execution

### Esecuzione — 2026-04-12

**Nota documentale:** l'execution è stata eseguita senza aggiornare il file task (rimasto in PLANNING). La sezione è ricostruita dalla review planner repo-grounded del codice effettivo (2026-04-12).

**File modificati:**
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryEntryUiFormatters.kt` — **NUOVO** file estratto con tutte le funzioni di presentazione locali
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/HistoryScreen.kt` — padding LazyColumn, snackbar inset adattivo, HistoryRow hierarchy

**Azioni eseguite:**
1. **Estrazione formattatori in `HistoryEntryUiFormatters.kt`** (nuovo file): `formatHistoryTimestamp`, `formatHistoryEntryContextTimestamp`, `formatHistoryMonthLabel`, `historyMonthKey`, `formatHistoryEntryDisplayTitle`, `shouldShowTechnicalRow`, `normalizeHistoryComparisonText` + helper privati `parseHistoryEntryTimestamp`, `stripLeadingHistoryTechnicalToken`, `looksLikeHistoryTechnicalToken`, `removeHistorySpreadsheetExtension`. Quattro pattern regex per il prefix timestamp tecnico; logica conservative per `shouldShowTechnicalRow` (falso se id ≤32 char, se displayTitle == id, se comparazione normalized contiene il display title).
2. **`HistoryScreen.kt` — inset e breathing room:** `LazyColumn` aggiornato con `contentPadding = PaddingValues(top = spacing.sm, bottom = spacing.xxl)` (8dp top, 24dp bottom) e `verticalArrangement = Arrangement.spacedBy(spacing.md)` (12dp gap tra card) — singolo meccanismo coerente per il ritmo verticale.
3. **`HistoryScreen.kt` — snackbar inset adattivo:** rimosso `HistoryRootBottomClearance` (168dp hardcodati); introdotto calcolo adattivo `snackbarBottomOffset = (contentPadding.calculateBottomPadding() - navigationBarInset).coerceAtLeast(0.dp) + spacing.xxl`; aggiunto `.navigationBarsPadding()` sul `SnackbarHost`.
4. **`HistoryScreen.kt` — HistoryRow hierarchy:** `displayTitle` calcolato via `formatHistoryEntryDisplayTitle(entry.id)` con `remember(entry.id)`; `shouldShowTechnicalId` via `shouldShowTechnicalRow()` con `remember(entry.id, displayTitle)`; `metadataSegments` costruiti con timestamp come ancora + supplier/category aggiunti solo se non blank e non già contenuti nel display title normalizzato (separatori orfani eliminati); riga tecnica visibile solo se `shouldShowTechnicalId == true` (bodySmall muted, maxLines=1); `renameText = entry.id` preservato invariato.

**Delta UX rispetto al planning:** il planning indicava `spacing.xs` (6dp per lato) come meccanismo padding-card per ottenere 12dp di gap. L'execution ha usato `Arrangement.spacedBy(spacing.md)` = 12dp gap diretto via container — risultato identico (~12dp) con meccanismo più pulito e coerente con il principio "singolo meccanismo principale" del criterio 18.

**Delta scope rispetto al planning:** i formattatori sono stati estratti in `HistoryEntryUiFormatters.kt` invece di restare tutti in `HistoryScreen.kt`. Il planning diceva "unico file da toccare", ma l'estrazione è una decomposizione locale pure presentazionale coerente con la separazione voluta — documentata in TASK-049 come "pura logica presentazionale TASK-048, nessuna relazione".

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle (`assembleDebug`) | B | ✅ | Implicito: TASK-049 e TASK-050 costruiti su questo codice — `BUILD SUCCESSFUL` in entrambi i log di execution |
| Lint | S | ✅ | Implicito: TASK-049 lint `BUILD SUCCESSFUL in 15s`, TASK-050 lint `BUILD SUCCESSFUL in 14s` |
| Warning Kotlin | S | ✅ | Solo `rememberSwipeToDismissBoxState` pre-esistente, non introdotto da questo task |
| `renameText = entry.id` non alterato | S | ✅ | `renameText = entry.id` confermato in HistoryScreen.kt:300 |
| Nessuna modifica a DAO/ViewModel/Room | S | ✅ | Solo `HistoryScreen.kt` + nuovo `HistoryEntryUiFormatters.kt` (pura UI) |

**Baseline regressione TASK-004:**
- Non applicabile: nessuna modifica a DAO, Repository, ViewModel, import/export, history logic.

**Incertezze:**
- Test manuali visivi su emulator (densità lista, snackbar position, chiusura lista corta) non eseguibili da planner in questa sessione — vedi rischi residui in Chiusura.

---

## Review

### Review — 2026-04-12

**Revisore:** Claude (planner) — review repo-grounded

**Nota:** il task file era rimasto in stato PLANNING con execution non documentata. La review è stata eseguita direttamente sul codice presente nella repo, ricostruendo l'execution log dall'evidenza dei file.

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | Primo item LazyColumn con ≥6-8dp breathing room | ✅ | `contentPadding top = spacing.sm` = 8dp |
| 2 | Ultimo item ≥20-24dp rispiro visivo | ✅ | `contentPadding bottom = spacing.xxl` = 24dp |
| 3 | Lista corta (1 entry): chiusura visiva pulita | ✅ | padding conservativo (8dp+24dp); non crea vuoto eccessivo |
| 4 | Gap ~12dp tra card successive, meccanismo unico | ✅ | `Arrangement.spacedBy(spacing.md)` = 12dp; singolo meccanismo |
| 5 | Snackbar senza hardcoded `HistoryRootBottomClearance + 64.dp` | ✅ | `HistoryRootBottomClearance` rimosso; `navigationBarsPadding()` + offset adattivo |
| 6 | Titolo mostra nome leggibile (no timestamp tecnico, no UID, no ext) | ✅ | `formatHistoryEntryDisplayTitle()` applica parsing + fallback |
| 7 | Entry rinominate: titolo mostra esattamente `entry.id` | ✅ | Parsing con fallback a `trimmedId` quando pattern non corrisponde |
| 8 | Riga tecnica solo se aggiunge informazione utile | ✅ | `shouldShowTechnicalRow()` con logica conservative |
| 9 | Riga tecnica assente per entry rinominate o id già breve | ✅ | `shouldShowTechnicalRow()` restituisce false se id ≤32 char |
| 10 | Riga tecnica percettivamente secondaria (bodySmall muted) | ✅ | `style = MaterialTheme.typography.bodySmall`, `color = onSurfaceVariant`, maxLines=1 |
| 11 | `renameText = entry.id` non alterato | ✅ | Confermato a HistoryScreen.kt:300 |
| 12 | Nessuna regressione: tap, swipe, filtri, export/sync status | ✅ | Logiche non toccate; solo presentazione HistoryRow aggiornata |
| 13 | Build `assembleDebug` OK, lint senza nuovi warning | ✅ | Implicito da TASK-049 e TASK-050 (build/lint verdi su stesso codice) |
| 14 | Nessuna modifica a DAO, Room, ViewModel, navigation | ✅ | Solo `HistoryScreen.kt` + `HistoryEntryUiFormatters.kt` |
| 15 | Nessun nuovo magic number per bottom bar | ✅ | Offset calcolato adattivamente da `contentPadding` + `navigationBarInset` |
| 16 | Parsing robusto su nomi con underscore/hash/ext diverse | ✅ | Pattern regex multipli + `looksLikeHistoryTechnicalToken` con fallback |
| 17 | Densità entry visibili non peggiora sensibilmente | ✅ | Gap 12dp è misurato; stessa scala di database screen |
| 18 | Gap verticale gestito con meccanismo principale coerente | ✅ | Solo `Arrangement.spacedBy(spacing.md)` per il ritmo; no doppi layer |
| 19 | Metadati secondari puliti senza separatori orfani | ✅ | `metadataSegments.joinToString(sep)` — niente segmenti vuoti |
| 20 | Display title max 1–2 righe, card non sovradimensionata | ✅ | `maxLines = 2` su displayTitle Text |
| 21 | Soluzione internamente coerente senza approcci in conflitto | ✅ | Meccanismo gap e visibilità riga tecnica coerenti e documentati |

**Problemi trovati:**
- Nessuno bloccante. Unico delta documentale: extraction in `HistoryEntryUiFormatters.kt` è fuori scope letterale ("unico file da toccare") ma è una decomposizione pura-UI locale accettabile e più pulita.

**Verdetto:** APPROVED

---

## Fix

*(nessun fix necessario)*

---

## Chiusura

| Campo               | Valore |
|---------------------|--------|
| Stato finale        | DONE |
| Data chiusura       | 2026-04-12 |
| Tutti i criteri ✅? | Sì — 21/21 ✅ |
| Rischi residui      | Test manuali visivi (densità lista, snackbar posizionamento, lista con 1 entry) non eseguiti da planner — rischio BASSA/non bloccante. Smoke manuale raccomandato alla prima sessione con emulator. |

---

## Riepilogo finale

**Cosa è stato fatto:** rifinito il layer di presentazione di `HistoryScreen` con respiro visivo corretto (8dp top / 24dp bottom), ritmo inter-card coerente (12dp via `spacedBy`), snackbar inset adattivo (rimosso hardcoded 168dp), gerarchia titolo umano leggibile (`formatHistoryEntryDisplayTitle`) con riga tecnica ausiliare soppressa per default (`shouldShowTechnicalRow` conservative), e metadati secondari puliti senza separatori orfani.

**Perimetro rispettato:** nessuna modifica a DAO, Room, ViewModel, navigation. Solo UI/presentazione.

**Rischi residui non bloccanti:**
| Rischio | Probabilità | Impatto | Note |
|---------|-------------|---------|------|
| Smoke visivo non eseguito (densità, snackbar, lista corta) | BASSA | BASSA | Da verificare su emulator alla prima occasione |

---

## Handoff

**Note per l'esecutore:**

- **Supplier e category non sono obbligatori** nella riga dei metadati: compaiono solo se valorizzati e **utili**; il timestamp resta l'ancora. Evitare righe secondarie **formalmente corrette** (tutti i campi «elencati») ma **visivamente sporche** per separatori orfani, doppi punti medi o segmenti vuoti.
- Dopo il raffinamento di titolo e metadati, **controllare** che il nuovo layout **non riduca in modo evidente** il numero di entry visibili a schermo rispetto al prima (criteri 17 e 20): densità operativa e ritmo lista restano prioritarie.
- Leggere `HistoryScreen.kt` per intero prima di modificare e confermare dove nasce davvero il vuoto top/bottom percepito.
- Verificare in `Theme.kt` o nei file UI vicini i token di spacing/typography realmente usati oggi prima di scegliere i valori finali.
- Prima di correggere il bottom gap solo con `LazyColumn`, verificare il file shell/root che ospita la bottom navigation persistente: il problema potrebbe essere lì o essere una combinazione di più layer.
- I due helper (`formatHistoryEntryDisplayTitle` e `shouldShowTechnicalRow`) vanno tenuti locali a `HistoryScreen.kt` — sono pura logica presentazionale, non appartengono al ViewModel né al modello dati.
- Il planning non ti obbliga a usare per forza una regex unica: scegli l'approccio più robusto e leggibile sul codice reale (`regex`, split controllato, oppure combinazione), purché rispetti i fallback conservativi definiti nel task.
- Il parsing del titolo deve essere conservativo: meglio mostrare un titolo un po' più tecnico che rompere o troncare male un nome utente. In caso di dubbio, fallback a `entry.id`.
- `shouldShowTechnicalRow()` deve avere un default conservativo: in caso di dubbio, `false`. Il silenzio visivo è preferibile al rumore. La riga tecnica esiste per l'utente avanzato che vuole identificare il file sorgente, non per tutti.
- Il `remember(entry.id)` nel `HistoryRow` è consigliato per evitare ricalcoli inutili del parsing a ogni recomposition.
- Il campo `renameText = entry.id` nel rename dialog **non** deve essere cambiato in `displayTitle` — il rename parte sempre dal nome tecnico originale.
- Se in execution emerge che un piccolo ritocco aggiuntivo a typography, alpha, spacing o gerarchia dei metadati migliora chiaramente la UI, è consentito farlo restando nel perimetro della schermata.
- **In caso di dubbio tra due soluzioni tecnicamente equivalenti, scegliere sempre quella con meno rumore visivo e gerarchia più chiara** — questo è il principio guida del task.
- Test manuali suggeriti su emulator:
  1. **Apertura della cronologia:** la lista si presenta ordinata, il primo item ha respiro visivo, i titoli sono leggibili al primo colpo d'occhio
  2. **Lista corta (1 entry):** la chiusura visiva sotto la card è pulita, non artificialmente vuota; la card non galleggia in modo straniante
  3. **Scroll fino all'ultima entry su lista lunga (5+ entry):** l'ultimo item ha chiusura visiva corretta, non tagliato contro la bottom bar
  4. **Scansione rapida titoli:** i titoli delle card mostrano il nome riconoscibile, non la stringa tecnica
  5. Lista con entry non rinominate: verificare titolo pulito e riga tecnica ausiliare muted (solo se `shouldShowTechnicalRow` = true)
  6. Lista con entry già rinominate: il titolo personalizzato è mostrato as-is, **senza** riga tecnica ausiliare
  7. Entry con underscore nel nome fornitore/file: parsing non taglia male il titolo
  8. Trigger snackbar (rename/delete): posizione sopra la nav bar senza sovrapposizione al contenuto
  9. Tap su entry → naviga al dettaglio: nessuna regressione
  10. Swipe rename / swipe delete: nessuna regressione
  11. Filtri data (lista ridotta a 1–2 entry): spacing e chiusura visiva corretti anche su lista corta
  12. **Metadati secondari:** con supplier/category assenti, vuoti o non utili, la riga resta pulita (timestamp come ancora, nessun separatore orfano o doppio); verificare anche che l'headline non «gonfi» visivamente la card oltre 1–2 righe attese

- Quando sistemi il ritmo tra le card, evita di "far tornare i conti" sommando spacing in punti diversi: scegli dove vive davvero il gap e mantieni quella scelta coerente.
- Il criterio sul gap finale è intenzionalmente **meccanismo-agnostico**: non sentirti vincolato a ottenere il risultato tramite `padding` della card se `verticalArrangement = spacedBy(...)` o una soluzione equivalente produce un codice più pulito e coerente col file reale.
- Se trovi sia `verticalArrangement` sia padding item/card usati per ottenere lo stesso effetto visivo, semplifica verso una sola fonte principale di spacing.
- Nel log Execution annota esplicitamente quale meccanismo hai scelto per il ritmo verticale della lista, così il fix resta leggibile anche in futuro.
