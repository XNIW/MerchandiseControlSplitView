# TASK-013 — UX polish FilePicker + PreGenerate

---

## Informazioni generali

| Campo              | Valore                     |
|--------------------|----------------------------|
| ID                 | TASK-013                   |
| Stato              | PLANNING                   |
| Priorità           | MEDIA                      |
| Area               | UX / UI                    |
| Creato             | 2026-03-27                 |
| Ultimo aggiornamento | 2026-03-27               |

---

## Dipendenze

- TASK-001 (DONE)

---

## Scopo

Migliorare gerarchia visiva, leggibilità e affordance di **FilePickerScreen** (home) e **PreGenerateScreen** (anteprima Excel) in modo **deciso e coerente** con lo stile Material3 già usato dall’app: risultato più maturo visivamente, **senza** reinventare flussi, **senza** refactor architetturale, **senza** nuova logica business nei ViewModel.

---

## Contesto

**FilePickerScreen** oggi: griglia 2 colonne con 5 card omogenee (`primaryContainer`, stessa altezza, stessa tipografia). L’azione core “Carica Excel” non emerge; “Opzioni” occupa 2 colonne.

**Decisione di layout (implementation-friendly):** in alto una **primary hero card a tutta larghezza** per “Carica Excel”; sotto le **quattro** azioni secondarie statiche in **due righe × due colonne** (struttura non-lazy equivalente **più semplice e stabile** — **non** è un vincolo rigido usare `Row` con `Modifier.weight`). Obiettivo operativo: **altezza coerente** tra le secondarie, **spacing naturale**, **scroll dell’intera colonna** solo se l’**altezza disponibile non basta**; **evitare** layout che **allungano artificialmente** le card per riempire tutto lo schermo. **Non** forzare una `LazyVerticalGrid` annidata in `Column` se non serve: **meno complessità**, **nessun nested scrolling inutile**, **stessa resa visiva** 2×2. **Opzioni** occupa **una sola cella** (non più span 2).

**FilePicker — ordine definitivo delle secondarie (obbligatorio):** dopo la hero, **nessuna libertà** sull’ordine delle celle. **Riga 1:** **Cronologia** \| **Aggiungi manualmente**. **Riga 2:** **Database** \| **Opzioni**. Motivazione: prima le azioni **operative più frequenti**, poi **sistema / configurazione**.

**FilePicker — consistenza visiva secondarie:** le **quattro** card secondarie devono mantenere **altezza coerente** e **allineamento visivo uniforme** tra le due righe; **nessuna** secondaria deve risultare **sbilanciata** per **label più lunghe** (in locale) o per **padding incoerente** tra le celle.

**FilePicker — comportamento picker:** il refactor UI **non** deve alterare i **MIME type** già supportati né il contratto del **launcher** della primary “Carica Excel”. **Stessi tipi file accettati** e stesso flusso di scelta documento rispetto all’implementazione attuale.

**FilePicker — wiring azioni:** il refactor UI **non** deve cambiare **wiring, callback o navigazione** delle **cinque** azioni home: primary **“Carica Excel”**, **Cronologia**, **Aggiungi manualmente**, **Database**, **Opzioni**. La verifica finale include un **smoke test manuale rapido su tutte e cinque** (non solo il picker principale).

**PreGenerate — ingresso share / esterno:** la schermata deve restare **corretta** anche se aperta da **condivisione / import esterno**, non solo da **FilePickerScreen**. Loading, error, FAB, back e dialog **non** devono presupporre implicitamente che l’utente arrivi sempre dalla home. Check **UX/funzionale** in verifica finale; **nessuna** modifica a `NavGraph` né nuova logica obbligatoria per questo punto.

**Stile card:** la primary non si limita a “un altro colore”: hero evidente ma non aggressiva ( **`primary` / `onPrimary`** oppure variante **filled/tonal** molto leggibile ed elegante — **non** `primary` su tutte le superfici). Le secondary usano **`surfaceContainer` / `primaryContainer` / stile tonal** differenziato dalla primary. Leggero aumento di **altezza, padding e gerarchia tipografica** dove serve. **Solo la card “Carica Excel”** ha **supporting text**; le altre card **no** sottotitoli (meno rumore e meno stringhe).

**PreGenerateScreen — loading:** **riusare** il componente **`LoadingDialog` esistente**. **Non** aggiungere `Card`/`Surface` aggiuntivi attorno al loading se si usa già `LoadingDialog`. Il miglioramento è **coerenza e pulizia**: niente duplicazioni, doppio container o rumore visivo. Durante il polish **non** lasciare un **secondo testo di stato** ridondante sotto/sopra il `LoadingDialog` che **ripeta** lo stesso messaggio, **salvo** testo che porti **informazione realmente diversa** e **non** già mostrata dal dialog. Obiettivo: **un solo** feedback di loading coerente, **non** due livelli di messaggistica sovrapposti.

**PreGenerateScreen — TopAppBar:** **invariata** rispetto all’odierna per **azioni disponibili**: **back**, **append**, **reload**. Il task migliora error / FAB / dialog; **non** ridistribuire né spostare queste azioni in altre aree della schermata. Il polish UI **non** deve alterare il **contratto dei document picker** legati ad **append** e **reload**: **stessi MIME type** accettati e **stesso comportamento** dei launcher rispetto al baseline pre-task.

**PreGenerateScreen — semantica reload (comportamento invariato):** per **reload**, oltre a MIME e launcher, va preservata **esplicitamente** anche la **semantica del flusso attuale**: **prima** **reset** dello **stato Excel**, **poi** caricamento dei **nuovi URI** scelti dal picker. Fa parte del **comportamento invariato** del reload, **non** va dato per scontato.

**PreGenerateScreen — error:** **non** solo testo + back. Blocco **empty/error centrato** con: icona, titolo breve, messaggio errore, CTA primaria con **label unica e definitiva: “Scegli di nuovo”** (apre il document picker tramite **`reloadLauncher` già esistente** — non è retry automatico né ricarica silenziosa dello stesso file), azione secondaria **“Indietro”**. **Nessun retry automatico** nel ViewModel.

**PreGenerateScreen — FAB e contenuto:** due azioni con gerarchia chiara: **“Genera”** = **`ExtendedFloatingActionButton`** con **label testuale**; **“Seleziona tutto”** = **`SmallFloatingActionButton`** o FAB **visivamente subordinato**. **Due obblighi di layout in stato dati/preview:** (1) **`padding` / `contentPadding` / inset bottom esplicito** sul **contenuto preview** così la **griglia** non finisce sotto il gruppo FAB (**small width**, **split-screen**). (2) Il **contenitore del gruppo FAB** deve rispettare **navigation bar / system insets** (o equivalente Compose: es. `WindowInsets`, `navigationBarsPadding`, `systemBarsPadding`) così il gruppo **non** risulta troppo basso né **addossato al bordo** con **gesture navigation**, **small width** e **split-screen**.

**Dialog supplier/category:** validazione, warning e divider **già presenti** — **non** rifare da zero. Solo **polish leggero**: titoletti/sezione supplier e category, spacing verticale migliore, separazione visiva maggiore tra **warning** e **campi**, eventuale **supporting text** discreto. **Nessun** bottom sheet, **nessuna** nuova logica business.

**Tema:** Material3 (light/dark). Verifiche esplicite in accettazione per dark mode e layout restrittivi.

---

## Non incluso

- Modifiche a `ExcelViewModel`, `DatabaseViewModel` o qualsiasi ViewModel (incluso: **nessun** retry automatico in VM)
- Modifiche a DAO, repository, entity, migration Room
- Modifiche alla navigazione (`NavGraph.kt`, `Screen.kt`)
- Modifiche a `ZoomableExcelGrid.kt` o `TableCell.kt` (TASK-014/016)
- Modifiche alla logica di parsing Excel o import analysis
- Aggiunta di nuove dipendenze
- Rimozione di feature Android funzionanti
- Bottom sheet al posto del dialog supplier/category
- Riscrittura del dialog supplier/category

**Stringhe:** consentite **nuove chiavi** per supporting text della primary hero, titoli/messaggi/error UI, label FAB estese, **CTA error “Scegli di nuovo”** (e traduzioni coerenti), titoletti dialog — con localizzazione in **tutti e 4 i file**: `values/` (it, default), `values-en/`, `values-es/`, `values-zh/` dove si introducono testi visibili.

---

## File potenzialmente coinvolti

**Da modificare:**

- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/FilePickerScreen.kt` — hero full-width, secondarie **2×2 non-lazy** con **ordine fisso** (Cronologia\|Manuale, Database\|Opzioni), stile card, spacing; **invariati** MIME/launcher primary e **wiring/callback** delle 5 azioni (criterio **#23**)
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/PreGenerateScreen.kt` — TopAppBar invariata (**append/reload**: launcher/MIME + **semantica reload** reset→nuovi URI, criterio **#19**); loading (`LoadingDialog`, **#10** senza testo duplicato); error block (“Scegli di nuovo” + `reloadLauncher`); FAB con **inset preview** + **inset system/nav sul gruppo FAB**; polish dialog supplier/category; verifica manuale **ingresso share/esterno** (**#24**)
- `app/src/main/res/values/strings.xml` (default = **italiano**), `values-en/`, `values-es/`, `values-zh/` — chiavi per testi UI introdotti (primary supporting, error, FAB, dialog sezione, ecc.). **Nota:** `values-it/` **non esiste** — l'italiano è nel default `values/`

**Da leggere (non modificare):**

- `ExcelViewModel.kt`, `DatabaseViewModel.kt` — stati loading/error/dati
- `DatabaseScreen.kt` — contiene la definizione di `LoadingDialog` (funzione package-level, riga ~446); **non** modificare, solo consultare per capire il contratto
- `MerchandiseControlTheme.kt`, `Color.kt`
- `NavGraph.kt` — flusso share intent (`ShareBus`) e navigazione a PreGenerate (non modificare)

---

## Criteri di accettazione

| # | Criterio | Tipo verifica | Stato |
|---|----------|---------------|-------|
| 1 | Build Gradle (`assembleDebug`) passa senza errori | B | — |
| 2 | Lint (`./gradlew lint`) senza nuovi warning introdotti | S | — |
| 3 | FilePickerScreen: “Carica Excel” **hero full-width in alto**; sotto le quattro secondarie in **layout non-lazy** a **due righe × due colonne** con **ordine fisso**: riga 1 **Cronologia** \| **Aggiungi manualmente**, riga 2 **Database** \| **Opzioni** (implementazione a scelta dell’esecutore: **non** prescritto `Row(weight)` come vincolo rigido); **senza** `LazyVerticalGrid` annidata in `Column` se non necessaria; **stessa resa visiva** 2×2; **nessuno stiramento artificiale** delle card per riempire lo schermo | S | — |
| 4 | FilePickerScreen: primary hero **evidente ma non aggressiva**; secondary **tonal/surfaceContainer/primaryContainer** — **non** tutte uguali alla primary; **nessun** `primary` applicato indiscriminatamente a tutte le superfici rilevanti | S | — |
| 5 | FilePickerScreen: **solo** la primary ha **supporting text**; le altre card **senza** sottotitoli | S | — |
| 6 | FilePickerScreen: altezza/padding/tipografia leggermente migliorati dove necessario | S | — |
| 7 | FilePickerScreen: su **display bassi** o **font scale elevato**, il **layout intero della home** può **scorrere** (es. `verticalScroll` sulla colonna contenente hero + 2×2) **senza clipping** e **senza compressione innaturale** delle card | M | — |
| 8 | FilePickerScreen: le **quattro card secondarie** mantengono **altezza coerente** e **griglia visivamente stabile** tra le righe; **nessuno sbilanciamento** dovuto a label più lunghe o padding incoerente tra celle | S / M | — |
| 9 | FilePickerScreen: **nessuna regressione** sui **MIME type** supportati e sul **comportamento del launcher** della primary “Carica Excel” (stessi tipi accettati e stesso flusso rispetto al baseline pre-task) | S / M | — |
| 10 | PreGenerateScreen loading: **solo** `LoadingDialog` (o equivalente già in uso) — **nessun** Card/Surface aggiuntivo ridondante attorno; **nessun** secondo **testo di stato** ridondante accanto al dialog che **duplichi** il messaggio di loading, **salvo** informazione **realmente diversa** e **non** già nel dialog — **un solo** feedback coerente | S | — |
| 11 | PreGenerateScreen error: blocco centrato con icona, titolo breve, messaggio, CTA primaria **“Scegli di nuovo”** collegata a **`reloadLauncher`**, secondario **“Indietro”**; **nessun** retry automatico in ViewModel | S | — |
| 12 | PreGenerateScreen FAB: **ExtendedFloatingActionButton** con label per “Genera”; “Seleziona tutto” **subordinato** (Small FAB o equivalente) | S | — |
| 13 | PreGenerateScreen stato **dati/preview**: (a) **padding / contentPadding / inset bottom esplicito** sul **contenuto preview** così la **griglia non finisce sotto il gruppo FAB**; (b) il **contenitore del gruppo FAB** applica **system / navigation bar insets** (o equivalente Compose) così il gruppo **non** è troppo basso né **attaccato al bordo** su **gesture navigation**, **small width** e **split-screen** | S / M | — |
| 14 | PreGenerateScreen **TopAppBar**: **invariata** per **back, append, reload** (nessuna rimozione, spostamento o ridistribuzione di queste azioni) | S | — |
| 15 | Dialog supplier/category: polish leggero (titoletti sezione, spacing, separazione warning/campi, supporting text opzionale); **nessuna** regressione validazione/warning/flusso esistente | S / M | — |
| 16 | **Dark mode** verificata (primary/secondary/error leggibili) | M | — |
| 17 | **Small width / split-screen / font scale elevato** non rompono layout (hero, 2×2, error block, FAB, **bottom inset** #13) | M | — |
| 18 | Stringhe **it / es / zh** (e en): **nessun** troncamento grave su label FAB estese, **“Scegli di nuovo”** e card principali | M | — |
| 19 | **Nessuna regressione** flusso **generate**. Per **append** e **reload** nella **TopAppBar**: **stessi MIME type** e **stesso contratto / comportamento dei document picker** (launcher) rispetto al **baseline pre-task**. Per **reload**, anche la **semantica del flusso** resta **invariata**: **prima** **reset** dello **stato Excel**, **poi** caricamento dei **nuovi URI** — verifica **esplicita** (diff o test manuale), **non** solo dedotta | S / M | — |
| 20 | Nessuna modifica a ViewModel, DAO, repository, entity, navigation | S | — |
| 21 | Nessuna nuova dipendenza | S | — |
| 22 | DoD UX/UI in `docs/MASTER-PLAN.md` soddisfatta | S | — |
| 23 | FilePickerScreen: **nessun** cambiamento al **wiring / callback / navigazione** delle **cinque** azioni (**Carica Excel**, **Cronologia**, **Aggiungi manualmente**, **Database**, **Opzioni**); verifica finale con **test manuale rapido su tutte e cinque** | S / M | — |
| 24 | PreGenerateScreen: comportamento **corretto** anche con **ingresso da share intent / flusso di import esterno** (non solo da home): loading, error, FAB, back, dialog **senza** assunzione implicita che l’utente arrivi sempre da `FilePickerScreen`; **nessuna** modifica a `NavGraph` richiesta per soddisfare questo criterio | M | — |

Legenda: B=Build, S=Static, M=Manual

---

## Decisioni

| # | Decisione | Motivazione | Data |
|---|-----------|-------------|------|
| 1 | FilePicker: hero full-width + **quattro secondarie** in **2 righe × 2 colonne** non-lazy; **ordine fisso** R1 Cronologia\|Aggiungi manualmente, R2 Database\|Opzioni; **nessun** obbligo rigido su `Row(weight)`; scroll colonna intera solo se serve; **no** stretch artificiale card | Operazioni frequenti prima, poi sistema/config | 2026-03-27 |
| 2 | Opzioni su 1 cella (non span 2) | Conferma: nell’ordine fisso ogni azione è una cella | 2026-03-27 |
| 3 | Supporting text **solo** su “Carica Excel” | Meno rumore visivo e meno stringhe | 2026-03-27 |
| 4 | Stile: primary `primary`/`onPrimary` **o** tonal/filled elegante; secondary differenziate | Evitare primary ovunque | 2026-03-27 |
| 5 | **MIME type e launcher** della primary home **inalterati** durante il refactor UI | Nessuna regressione funzionale file picker | 2026-03-27 |
| 6 | PreGenerate **TopAppBar**: back / append / reload **come oggi**, nessuno spostamento azioni; **append/reload**: **MIME e launcher invariati**; **reload**: **reset stato Excel → poi nuovi URI** (semantica invariata) | Comportamento picker + sequenza reload esplicita | 2026-03-27 |
| 7 | Loading PreGenerate: solo `LoadingDialog`, niente wrapper Card/Surface extra; **nessun** messaggio loading **duplicato** oltre al dialog salvo info nuova | Un solo feedback loading | 2026-03-27 |
| 8 | Error: CTA primaria **solo** “Scegli di nuovo” + `reloadLauncher` + Indietro; no retry in VM | Allineamento semantico al document picker | 2026-03-27 |
| 9 | FAB: Extended “Genera”; Select All subordinato; **inset bottom** sul preview **e** **system/nav insets** sul **contenitore gruppo FAB** | Griglia leggibile + FAB non addossato alla gesture/nav bar | 2026-03-27 |
| 10 | Dialog supplier/category: solo polish UI | Mantenere logica e validazione esistenti | 2026-03-27 |
| 11 | Ordine implementazione: FilePicker → PreGenerate (error/FAB/loading + inset) → dialog → verifiche | Priorità approvata | 2026-03-27 |

---

## Planning (Claude)

### Analisi — Stato codice (riferimento)

**FilePickerScreen (stato attuale):** `Scaffold` + `TopAppBar` + `LazyVerticalGrid(Fixed(2))` con `userScrollEnabled = false`. **Ordine attuale card:** (1) Cronologia, (2) Carica file Excel, (3) Aggiungi prodotti manualmente, (4) Database, (5) Opzioni (`GridItemSpan(2)`). Tutte usano `primaryContainer`, altezza fissa `96.dp`, `RoundedCornerShape(20.dp)`, stessa tipografia `titleMedium`. Nessun supporting text. Il launcher primary è `OpenMultipleDocuments` con 4 MIME type.

**PreGenerateScreen (stato attuale):** `Scaffold` + `TopAppBar(back, append, reload)` sempre visibile in tutti gli stati (loading/error/dati). Stato **loading**: `Column(fillMaxSize, center)` contiene `LoadingDialog(...)` + `Spacer(8.dp)` + `Text(statusText)` — il testo sotto il dialog è **ridondante** (ripete il messaggio già visibile nel LoadingDialog). Stato **error**: solo `Text(errorMessage)` centrato con `color = error` — nessuna icona, nessuna CTA, nessun “Indietro” inline. Stato **dati**: `ZoomableExcelGrid` senza bottom padding + due `FloatingActionButton` standard (DoneAll / ArrowForward) in `Column(BottomEnd, padding=16.dp)` senza inset system/navigation bar. **`LoadingDialog`** è definito in **`DatabaseScreen.kt`** (funzione package-level, riga ~446) — già usato da PreGenerateScreen tramite import implicito di package.

**Target struttura FilePicker (preferita):**

```
Scaffold
  └─ TopAppBar
  └─ Column (padding; verticalScroll sull’intera colonna solo se l’altezza non basta — evitare lazy annidato)
       ├─ HeroPrimaryCard "Carica Excel" (full width) + supporting text
       ├─ Riga 1: Cronologia | Aggiungi manualmente
       └─ Riga 2: Database | Opzioni
```

**PreGenerateScreen:** **non** toccare composizione azioni **TopAppBar** (back, append, reload); **non** alterare **MIME né contratto launcher** di **append** / **reload** né la **semantica reload** (**reset stato Excel** → **nuovi URI**). `LoadingDialog` senza **testo di stato ridondante** duplicato. error con “Scegli di nuovo” + `reloadLauncher`; stack FAB con **Extended** + secondario: (1) sul **contenitore padre della preview** in stato dati — **padding / inset bottom** calibrato sul gruppo FAB; (2) sul **contenitore del gruppo FAB** — **system / navigation bar insets**. **Non** richiede modifiche a `ZoomableExcelGrid.kt` (fuori scope TASK-013). Dialog supplier/category: solo composizione visiva (titoletti, `Spacer`, separazione warning/campi).

### Ordine di implementazione (obbligatorio)

1. **FilePickerScreen** — hero full-width; **2×2 non-lazy** con **ordine fisso** (R1 Cronologia\|Manuale, R2 Database\|Opzioni); implementazione flessibile salvo ordine; coerenza visiva; stile primary/secondary; supporting text solo sulla primary; **MIME/launcher primary** invariati (**#9**); **wiring 5 azioni** invariato (**#23**).
2. **PreGenerateScreen** — **non** modificare **TopAppBar** né **launcher append/reload** né **semantica reload** reset→URI (**#14**, **#19**); stato **loading**: rimuovere il `Column` wrapper e il `Text(statusText)` ridondante sotto `LoadingDialog` — basta la chiamata a `LoadingDialog(...)` poiché è un `Dialog` full-screen che gestisce il proprio overlay (**#10**); stato **error**: blocco centrato (“Scegli di nuovo” + `reloadLauncher` + “Indietro” via `onBack()`); nota: la **TopAppBar con il back** resta sempre visibile (è parte del `Scaffold`), quindi il pulsante “Indietro” nel blocco error è un'**azione aggiuntiva** di affordance, non l'unico modo per tornare — nessun conflitto; **FAB** (Extended + secondario) + **inset preview** + **inset system/nav sul gruppo FAB** (**#13**); dopo le modifiche, validare **ingresso share/esterno** (**#24**).
3. **Dialog supplier/category** — polish leggero (titoletti, spacing, separazione warning/campi, supporting text discreto).
4. **Verifiche finali** — `assembleDebug`, `lint`, controlli manuali UX (dark mode, width stretta, split-screen, font scale, **ordine secondarie home** **#3**, **scroll home** **#7**, **griglia secondarie** **#8**, stringhe it/es/zh **#18**, **MIME/launcher primary home** **#9**, **smoke test tutte e 5 azioni home** **#23**, **TopAppBar** **#14** + **append/reload** (MIME, launcher, **reload reset→URI**) **#19**, **loading** senza messaggio duplicato **#10**, **inset preview + FAB/system insets** **#13**, flusso **generate**, **PreGenerate da share/import esterno** **#24**, dialog).

### Output atteso

Interfaccia **più matura e leggibile**, allineata al tema esistente, con decisioni **già applicate** nel codice — **nessuna** formulazione tipo “valutare se” lasciata come requisito ambiguo nel piano.

### Rischi e mitigazione

| Rischio | Impatto | Probabilità | Mitigazione |
|---------|---------|-------------|-------------|
| Hero o FAB troppo invadenti su schermi piccoli / FAB addossato alla nav bar | Medio | Media | Criterio **#13** (preview **e** gruppo FAB con system/nav insets); test split-screen, gesture nav, font scale |
| Regressione MIME / launcher home | Alto | Bassa | Code review diff launcher; criterio **#9** |
| Regressione MIME / launcher **append** e **reload** o **semantica reload** (reset→URI) | Alto | Bassa | Diff + test manuale sequenza; criterio **#19** |
| Callback / navigazione home alterati dal refactor | Alto | Bassa | Code review wiring; smoke **#23** |
| Stringhe lunghe in it/es/zh | Basso | Media | Label compatte + verifica manuale criteri **#18** (localizzazione/troncamenti) e **#8** (griglia secondarie stabile) |
| Regressione visiva dark mode | Medio | Bassa | Solo `MaterialTheme.colorScheme`; test manuale dark (criterio **#16**) |
| Tocco accidentale ViewModel/nav | Alto | Bassa | Criterio **#20** (nessuna modifica VM/DAO/repository/entity/navigation); code review mirata |

---

## Execution

### Esecuzione — [data]

**File modificati:**

- (da compilare durante l'esecuzione)

**Azioni eseguite:**

1. (da compilare durante l'esecuzione)

**Check obbligatori:**

| Check | Tipo | Stato | Evidenza |
|-------|------|-------|----------|
| Build Gradle | B | — | |
| Lint | S | — | |
| Warning Kotlin | S | — | |
| Coerenza con planning | — | — | |
| Criteri di accettazione | — | — | |

**Incertezze:**

- (nessuna, oppure elenco)

---

## Review

### Review — [data]

**Revisore:** Claude (planner)

**Criteri di accettazione:**

| # | Criterio | Stato | Note |
|---|----------|-------|------|
| 1 | … | — | |

**Problemi trovati:**

- (elenco, oppure "nessuno")

**Verdetto:** APPROVED / FIX_REQUIRED / BLOCKED

---

## Fix

### Fix — [data]

**Correzioni applicate:**

- [Descrizione correzione]

**Ri-verifica:**

- [Evidenza che il problema è risolto]

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | — |
| Data chiusura | — |
| Tutti i criteri ✅? | — |
| Rischi residui | — |

---

## Riepilogo finale

[Sintesi di ciò che è stato fatto, decisioni chiave, rischi residui]

---

## Handoff

[Note per il prossimo operatore: contesto, decisioni, test manuali suggeriti, aree correlate]
