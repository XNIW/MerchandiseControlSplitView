# TASK-059 — Rifinitura UX sync cloud (Android ↔ Supabase)

---

## Informazioni generali

| Campo                 | Valore |
|-----------------------|--------|
| ID                    | TASK-059 |
| Stato                 | **DONE** — review repo-grounded completata e check verdi il 2026-04-26 |
| Priorità              | **ALTA** |
| Area                  | Supabase sync / UX / `OptionsScreen` / `CloudSyncIndicator` / `CatalogSyncViewModel` (solo presentazione stato) |
| Creato                | 2026-04-25 |
| Ultimo aggiornamento  | 2026-04-26 — review Codex completata; nessun fix codice necessario; `assembleDebug`, `lintDebug`, `CatalogSyncViewModelTest` e `git diff --check` verdi |

---

## Dipendenze

- **TASK-055** `PARTIAL` — audit e mappa percorsi sync; follow-up UX raccomandato verso questa task.
- **TASK-057** / **TASK-058** `DONE` — nessun conflitto; perimetro import/Database locale chiuso.

---

## Scopo

Rendere **più chiara e leggibile** in UI l’esperienza della sincronizzazione cloud Android ↔ Supabase: distinguere percezione e copy tra sync completa, sync rapida/delta, auto-push dopo modifica locale, arrivo aggiornamenti remoti, backup/restore sessioni history, e casi in cui serve una **sync completa manuale** (`manualFullSyncRequired` e affini), **senza modificare la logica di sync** in repository, Supabase, Room o coordinator.

**Vincolo architetturale:** Room resta fonte di verità; il ViewModel resta fonte dello stato; i composable fanno solo rendering e interazione; nessuna chiamata di rete dai composable.

---

## Contesto (da TASK-055 e da codice letto in planning)

L’audit **TASK-055** documenta che il backend sync è già articolato (full refresh, quick/delta, bootstrap, drenaggio `sync_events`, auto-push debounced, stato globale strutturato). Il **rischio UX** segnalato: l’utente percepisce «un unico cloud sync» senza capire **direzione** (invio vs ricezione), **ampiezza** (full vs delta) e **esito** (successo parziale, pending, errore, sessione).

Nel codice attuale:

- `CatalogSyncViewModel` costruisce `CatalogSyncUiState` con `primaryMessage`, `catalogDetail` (blocchi su `CatalogSyncSummary`, pending, ultimo OK, hint `manualFullSyncRequired`, conteggi quick/post-eventi), `sessionDetail` (backup sessioni history), e `quickSyncBodyRes` differenziato se `sync_events` è disponibile.
- `OptionsScreen` (`CatalogCloudSection`) mostra titolo sezione, sottotitolo = primario, dettaglio catalogo, blocco separato «Backup sessioni history», pulsanti **Sincronizzazione rapida** e **Sincronizzazione completa** con body dedicati.
- `CloudSyncIndicator` (in `NavGraph` sulle rotte **non** Options) mostra fase e conteggi in una riga, con possibile **ellipsis**; icona/rotazione e colori per RUNNING / COMPLETED / FAILED.

I testi in `values/strings.xml` (e `en`/`es`/`zh`) coprono già molti casi, ma la **gerarchia informativa** (titolo sezione, ordine paragrafi, ridondanze) può ancora confondere full vs quick e invio vs ricezione.

---

## Cosa NON cambia funzionalmente (fuori esito TASK-059)

- Comportamento di `InventoryRepository`, data source Supabase, DAO, Room, entità, schema remoto, RLS.
- `CatalogAutoSyncCoordinator`, `RealtimeRefreshCoordinator` (comportamento auto-sync; eventuali chiarimenti copy **senza** cambiare policy).
- Rotte, `NavGraph` (wiring invariato: nessun nuovo destino, nessun cambio parametri), firme repository.
- **Nessuna nuova dipendenza** Gradle.

Eventuali scoperte che richiederebbero toccare repository/remote: **documentare nel task** come *fuori perimetro* e **bloccate** fino a nuovo task / conferma (allineato a vincolo utente: Supabase e repository **non** modificati salvo emergenza, non eseguibile senza nuova approvazione).

---

## Cosa cambia per l’utente (esito desiderato post-execution)

- Capisce la **differenza** tra pulsante *sync completa* (riallineamento ampio, catalogo/prezzi + sessioni history in refresh manuale) e *sync rapida* (delta, eventi dove disponibili, push locale).
- Riconosce **quando** il dispositivo sta **inviando** modifiche locali rispetto a quando sta **ricevendo** o applicando **aggiornamenti remoti** (dove l’UI può mostrarlo con copy già alimentato da `CatalogSyncSummary` / fasi, senza nuova logica di rete).
- Comprende **stati di errore / parziale / pending** (inclusa sessione, permessi, config, offline) e il messaggio su **necessità di full sync manuale** senza gergo interno.
- Sezione **sessioni history** (backup/restore) resta distinta e leggibile rispetto al blocco catalogo/prezzi, senza rumore in eccesso.

---

## Mappa delle modalità sync attuali (repo — riepilogo per UX)

| Modalità | Dove nasce in UI / flusso | Cosa rappresenta (per copy) |
|----------|---------------------------|----------------------------|
| **Manual full sync** | `CatalogSyncViewModel.refreshCatalog()` → `syncCatalogWithRemote` + fasi REALIGN…PULL + history session cloud refresh (bootstrap + push) | Riallineamento ampio, pull catalogo completo, prezzi, poi blocco history session. È l’azione «completa» in Options. |
| **Manual quick / delta** | `syncCatalogQuick()`: con `sync_events` configurato → `syncCatalogQuickWithEvents`; altrimenti `pushDirtyCatalogDeltaToRemote` | Invio modifiche dirty; con eventi anche fetch mirato e `remoteUpdatesApplied` / outbox; **non** full fetch catalogo. |
| **Auto-push dopo modifica locale** | `CatalogAutoSyncCoordinator` + `onLocalProductChanged` (non è il focus UI principale di Options, ma l’utente vede indirettamente esiti su indicator/stato) | Comunicare in copy che le modifiche locali possono essere inviate in background (debounce) senza ridefinire il coordinator. |
| **Ricezione aggiornamenti remoti (realtime / sync_events)** | Drenaggio eventi, fetch mirato, campi `remoteUpdatesApplied`, `syncEventsProcessed` in summary | Distinguere da «solo inviato in alto» nelle descrizioni e nei dettagli post-quick quando disponibili. |
| **Bootstrap / restore (sessioni)** | `runAutomaticSessionBootstrapIfNeeded` / `runHistorySessionBootstrap` | Restore da cloud a locale e metriche in `HistorySessionCloudUiSummary` → testi `catalog_cloud_sessions_*`. |
| **Session history backup/restore in sync manuale completa** | Dopo catalog OK in `refreshCatalog`, `runHistorySessionCloudRefresh` | Il dettaglio sessioni in `sessionDetail` già separato; rifinire copy se serve chiarezza restore vs upload. |

**Nota planning:** l’utente **non** vede in Options un pulsante distinto per «solo auto-sync»; la chiarezza passa da copy secondario, hint dopo ultima operazione, e (ove utile) micro-copy nel `CloudSyncIndicator` **senza** nuove rotte.

---

## File da leggere / probabilmente in scope in futura `EXECUTION`

| File | Ruolo |
|------|--------|
| `app/.../viewmodel/CatalogSyncViewModel.kt` | Unione stato, `buildCatalogDetail`, `buildHistorySessionSecondary`, `quickSyncBodyRes`, `CatalogSyncUiState`. Possibili aggiunte **solo** di stringhe/ordinamento testo esposte (no nuova logica remota). |
| `app/.../ui/components/CloudSyncIndicator.kt` | Label fase; single-line; affiancare copy breve che non duichi Options. |
| `app/.../ui/screens/OptionsScreen.kt` | `CatalogCloudSection`: gerarchia titolo/sottotitolo/dettaglio/pulsanti. |
| `app/src/main/res/values/strings.xml` | Chiavi `catalog_cloud_*` (e affini) — **4 lingue in execution**: `values`, `values-en`, `values-es`, `values-zh`. |

**Sola lettura / no modifica** salvo evidenza contraria in review (vincolo task):

- `NavGraph.kt` — nessun cambio navigation; opzionalmente **solo** lettura per confermare wiring indicator.
- `InventoryRepository.kt`, `CatalogAutoSyncCoordinator.kt`, `Supabase*RemoteDataSource.kt`, DAO, `AppDatabase.kt`, entity.

---

## File fuori scope (salvo decisione esplicita / nuovo task)

- `DatabaseScreen.kt` / `HistoryScreen.kt` scroll-refresh: **TASK-060**.
- Hardening `sync_events` e fallback: **TASK-061**.
- Smoke live A/B e matrice QA: **TASK-063**.
- Documentazione schema Supabase / runbook: **TASK-062**.

---

## Decisioni UX proposte (non implementate — futura `EXECUTION`)

Decisioni **concrete** per guidare l’esecutore; restano in planning fino ad approvazione `EXECUTION`.

### OptionsScreen — sezione «Catalogo cloud»

- **Struttura card/section più leggibile** rispetto all’attuale `OptionsGroup` + sottotitolo unico: mantenere il pattern card esistente ma con gerarchia esplicita:
  - **Stato principale** in una riga breve (equivalente concettuale a `primaryMessage` / headline), senza tecnicismi.
  - **Fila di chip / badge o riga secondaria** (da implementare in Compose) per sintetizzare, quando i flag/dati in `CatalogSyncUiState` / summary lo consentono, almeno le dimensioni: **Completa** vs **Rapida** (ultima azione o modalità consigliata *non* come gergo API), **Invio** (c’è lavoro in uscita), **Ricezione** (c’erano aggiornamenti applicati o pull mirato), **Sessioni** (attività backup/restore / pending). I chip sono **opzionali** dove i dati mancano: meglio assenza che etichette false.
  - **Dettaglio secondario** (`catalogDetail` + eventuali `sessionDetail` già separati) in blocco `bodySmall`, paragrafi corti, linguaggio per l’utente; **evitare muri di numeri** e righe stile log.
  - **Due CTA** già presenti (**Sincronizzazione rapida** = `OutlinedButton`, **Sincronizzazione completa** = `Button`): in execution, rendere la **differenza funzionale** percepibile anche per titolo+body (non solo ordine visivo); nessun terzo flusso sync manuale aggiuntivo in questa task.

### CloudSyncIndicator

- **Resta un riquadro compatto** (angoli di design attuali: chip circolare + testo) — **non** diventare pannello espandibile o lista.
- **Non troncare messaggi che trasportano un avviso obbligatorio** (es. necessità di sync completa, errore rete): in execution, preferire **stringhe fase accorciate** dedicated (`@StringRes` «short») e/o `maxLines` / layout solo se coerente con il guardrail sotto; evitare che l’utente veda solo «…» su uno stato d’errore.

### Gergo tecnico

- **Mai** mostrare in stringhe product-facing termini come `sync_events`, `outbox`, `bootstrap`, `watermark`, `RLS`, `delta` in inglese grezzo, nomi tabelle, ecc. (vedi *Tassonomia*). Il codice e i log possono restare invariati.

---

## Tassonomia copy user-facing (glossario obbligatorio in execution)

Uso consigliato dei termini (da tradurre coerentemente in **IT / EN / ES / ZH**):

| Termine / concetto | Definizione per l’utente |
|--------------------|-------------------------|
| **Sincronizzazione completa** (full) | Controlla e **riallinea** dal cloud: **catalogo**, **prezzi** e **sessioni** (fogli/cronologia collegate al backup), con il percorso già oggi usato da `refreshCatalog()`. |
| **Sincronizzazione rapida** (quick) | **Invia** le modifiche locali in sospeso e, **quando possibile**, **scarica/applica** solo le novità recenti (percorso quick/delta); non garantisce allineamento totale. |
| **Invio modifiche** | I dati **da questo dispositivo verso il cloud** (upload, push). |
| **Ricezione aggiornamenti** | I dati **dal cloud verso il dispositivo** (download, applicazione in locale) — *non* confondere con «solo inviato». |
| **Sessioni** / blocco **Cronologia (backup)** | I **fogli / sessioni** di lavoro e la relativa **cronologia** che passano per il canale *history session* (backup/restore) — distinto da «catalogo + prezzi» in una sezione a parte. |
| **Serve la sincronizzazione completa** | La rapida **non basta** a verificare o incorporare tutto; l’utente deve usare l’azione **completa** (mapping UX a `manualFullSyncRequired` e casi limiti già mappati nel VM). |

### Cosa evitare nel testo visibile (sostituire con la tassonomia sopra)

- «Eventi» da solo se suona a log (`sync events`) → preferire *aggiornamenti recenti tracciati* / *notifiche in coda* a seconda del contesto.
- **Outbox**, **bootstrap**, **payload**, **watermark**, **RLS**, **delta** (termine inglese grezzo), **sync_events** in chiaro.
- **«Delta non verificabile»** come stringa all’utente → formulazioni già in linea con `catalog_cloud_remote_incremental_not_verifiable_hint` ma in execution rivedere in tutte le lingue per coerenza con *Serve sincronizzazione completa*.

---

## Matrice stato → messaggio → azione (planning)

Tabella guida: in execution i testi finali vanno nelle `strings` (4 lingue). **Nota:** la colonna *Primary* nelle celle sotto indica l’**intento**; le chiavi `CatalogSyncViewModel` attuali sono indic tra parentesi dove utili. **Sorgente** = dove l’utente vede soprattutto il messaggio.

| Caso | Primary message (intento) | Dettaglio secondario (sintesi) | Azione consigliata all’utente | Options / `CloudSyncIndicator` |
|------|---------------------------|---------------------------------|------------------------------|--------------------------------|
| Supabase non configurato | Cloud non disponibile su questo dispositivo | — | Contattare chi distribuisce l’app o usare un build con config — *nessun* retry sync | **Options** (no indicator utile) |
| Utente non loggato | Accedi per sincronizzare | — | Tocca **Accedi** (Account) | **Options** |
| Sessione in verifica / `Checking` | Verifica account… | — | Attendere o riprovare accesso | **Options** (primario) |
| Sessione scaduta / recuperabile `ErrorRecoverable` | Serve un nuovo accesso | eventuale messaggio server in `catalogDetail` | Tocca **Accedi** / rifare login | **Options** |
| Offline / rete | Connessione assente o instabile | — | Ripristinare rete, poi **Rapida** o **Completa** | **Entrambi** (in errore: indicator breve) |
| Pending lavori (coda) | In attesa / da sincronizzare | coda catalogo, ultimo OK, hint prezzi | **Rapida** per smaltire; **Completa** se non si sblocca | **Options**; indicator in idle poco rilevante |
| Sync in corso | Sincronizzazione in corso + fase | progress stage opzionale | Attendere; non avviare secondo sync | **Entrambi** (indicator: fase corrente) |
| **Completa** riuscita (nessun errore parziale) | Sincronizzato / tutto ok | ultimo orario, eventuali conteggi sintetici | — o **Rapida** se serve solo coda | **Options**; indicator: breve check |
| **Rapida** ok, **solo** invio locale (nessun aggiornamento remoto in evidenza) | Aggiornato (rapida) o messaggio invii | *Inviate N modifiche…* | — | **Options** (dettaglio); indicator già concluso |
| **Rapida** ok con **aggiornamenti remoto applicati** | Non solo «inviato» | riga *Ricezione*: es. N aggiornamenti applicati / allineato con summary | Chiarire che ha ricevuto dal cloud, non solo inviato | **Options** (obbligo chiarezza); indicator opz. |
| Prezzi parzialmente incompleti | Catalogo ok; prezzi non completi | vedi `catalog_cloud_state_prices_incomplete` | Tocca **Completa** o **Riprova** (stesso percorso refresh) | **Options**; indicator: stato corto se visibile |
| Sessioni history incomplete | Cloud ok; sessioni non completate | vedi `catalog_cloud_state_sessions_incomplete` | **Completa** (refresh che include blocco sessioni) | **Options** (blocco *Sessioni* evidenziato) |
| Permessi / **Forbidden** | Permessi cloud da verificare | — | Verificare account/ruoli in Supabase; riprovare | **Options** |
| Config / schema remoto anomalo | Configurazione da verificare | — | Controllare ambiente; **Completa** dopo fix | **Options** |
| `manualFullSyncRequired` in summary | **Serve sincronizzazione completa** | accorciare motivazione in linguaggio umano (niente RLS/watermark) | Eseguire **Sincronizzazione completa** | **Options** (dettaglio visibile); **Indicator**: riga fase/ hint **brevi** (no ellissi sul solo avviso) |

In execution, se `CloudSyncIndicator` mostra **Completed/Failed** con testo troppo lungo, usare voci *short* o priorità: **errore > avviso full sync > fase > generico**.

---

## Gerarchia visiva consentita (futura `EXECUTION`)

L’obiettivo **non** è solo testo, ma also **percezione qualità** coerente con Material3 e lo stile già in `OptionsScreen` (spaziatura `appSpacing`, card, titoli sezione).

- **Ammessi** ritocchi **piccoli / moderati** se migliorano chiarezza e non violano vincoli architetturali: chip/badge di stato, card più ordinata, **spacing** più respirato, **divider** più soft (tono `OutlineVariant` / alfa), blocco dettaglio separato, **icone** `Icons.Default` coerenti (es. `Sync` / `CloudUpload` / `CloudDownload` se già in uso o equivalenti Material) per upload vs download / sessione — **nessuna** nuova dipendenza e **nessuna** business logic nel composable.
- **Ammesso** rendere l’**azione primaria** (sync completa) più evidente rispetto alla secondaria (rapida) **entro** la sezione, senza invertire requisito di prodotto (la scelta *quale* CTA primaria resta: se il team vuole *completa* come filled e *rapida* come outlined, va **documentato** nel log execution).
- **Vietato:** redesign complessivo `OptionsScreen`, **nuove rotte**, **nuovi flussi**, **nuove dipendenze** Gradle, logica oltre UI binding, **chiamate** remote dai composable.

---

## Accessibilità e robustezza UI (criteri in `EXECUTION`)

- **Testi** compatibili con **font scaling** Android (fino a ~200% ove ragionevole); evitare altezze fisse che tagliano 2+ righe di dettaglio.
- **Nessun messaggio critico** solo per **colore** (stato ok/ko: affiancare icona o testo come già in parte su indicator).
- **`contentDescription`** (e semantics dove serve) per: pulsanti rapida/completa, chip stato, sezione che riassume la sync, indicatore in overlay; mantenere stringhe `cd` localizzate o pattern coerente.
- **Evitare ellissi** su messaggi che portano l’**unica** istruzione (es. «serve completa»); se serve troncamento, usare `Marquee` **solo** come ultima opzione o testo più breve.
- **Portrait stretto** e **dark theme:** contrasto `onSurface` / `onSurfaceVariant` come da tema; nessun grigio troppo tenue su `surfaceContainerLow`.
- **L10n:** verificare che ES/ZH (stringhe spesso **più lunghe**) non rompano layout: `weight`, `fillMaxWidth`, andare a capo previsto nel dettaglio.

---

## Problemi UX attuali o ambiguità da verificare in execution

1. **Full vs quick:** in card, il sottotitolo mostra `primaryMessage`; dopo successo o in pending le stringhe possono sovrapporsi concettualmente a «Sincronizzato» / «Da sincronizzare» senza rafforzare *quale* azione l’utente ha usato l’ultima volta.
2. **Indicatore globale** (`CloudSyncIndicator`): `maxLines = 1` e `Ellipsis` — fasi lunghe o conteggi possono **troncare** messaggi utili; valutare testi più corti o micro-interazione **solo se** resta perimetro copy/layout leggero (no redesign shell).
3. **Invio vs ricezione:** `buildCatalogDetail` aggiunge spesso più righe; l’utente potrebbe non collegare «Eventi… / aggiornamenti applicati» a «ricezione remota» senza un’etichetta esplicita.
4. **`manualFullSyncRequired`:** hint presente; verificare comprensibilità in IT e nelle 4 lingue in fase L10n.
5. **Auto-sync:** nessun messaggio dedicato in Options; accettabile come limite se documentato, oppure una riga guida in `catalog_cloud_sync_quick_body*` **senza** toccare coordinator.

---

## Interventi considerati *sicuri* per TASK-059 (copy / gerarchia / indicatori)

- Rinominare/riordinare **titoli, sottotitoli, body** sotto i due pulsanti (completa vs rapida) per enfatizzare *ambito* e *direzione* (invio / ricezione / entrambi) usando dati **già** in `CatalogSyncUiState` e stringhe.
- Chiarire **primary** vs **secondario** (es. `catalogDetail` a paragrafi con ordine: pending → ultima azione → conteggi utili).
- In `CloudSyncIndicator`, messaggi fase **più corti** o chiavi dedicate per troncamento ridotto.
- Uso selettivo di conteggi `CatalogSyncSummary` (pushed/pulled, `remoteUpdatesApplied`, `syncEventsProcessed`, outbox) **solo** quando aggiungono segnale (evitare elenchi da «dashboard»).

**Follow-up lasciati a TASK-060 / TASK-061 / TASK-063 (non in TASK-059)**

| Task | Cosa resta fuori |
|------|------------------|
| **TASK-060** | Refresh puntuale lista `DatabaseScreen` dopo pull remoto; possibile tocco `DatabaseViewModel` + repository per merge righe. |
| **TASK-061** | Hardening `sync_events`, limiti, fallback, test repository; allineamento UX severo agli errori lato dato se serve **logica** oltre copy. |
| **TASK-063** | Matrice smoke live multi-device, evidenze QA; nessun requisito di codice se non bugfile. |
| (Opz.) **TASK-062** | Runbook e schema — documentazione, non stringhe product in questa fase. |

---

## Proposta implementativa minima e progressiva (futura `EXECUTION`)

1. **Inventario stringhe** — Elenco `catalog_cloud_*` usate da `CatalogSyncViewModel` e `OptionsScreen` / `CloudSyncIndicator`; nessuna rimozione di chiavi senza sostituzione in tutte e 4 le lingue.
2. **Riscrittura copy** (IT default + EN/ES/ZH) — Obiettivo: full vs quick, invio vs ricezione, stati (pending, success, errore, sessione, config), `manualFullSyncRequired`.
3. **Micro-ritocco `CatalogSyncViewModel.buildUi` / `buildCatalogDetail` / `buildHistorySessionSecondary`** — Solo assemblaggio testo, ordine paragrafi, ed eventuale `StringRes` aggiuntivo; **nessun** nuovo `suspend` verso rete.
4. **Options** — Spaziature/gerarchia minima nella `CatalogCloudSection` (stesso `OptionsGroup` pattern); nessun redesign card.
5. **Indicator** — Allineare lunghezze fase; opzionale seconda riga **solo** se accettata in review per rumore (default: restare su 1 riga con copy più corti).
6. **Test** — Se toccato `CatalogSyncViewModel`, valutare `CatalogSyncViewModelTest` (vedi sotto). Nessun test UI Compose obbligatorio se non richiesto dal task in corso d’esecuzione.

---

## Criteri di accettazione

| # | Criterio | Tipo |
|---|----------|------|
| 1 | L’interfaccia distingue **sync completa** da **sync rapida / delta** (etichette, body pulsanti, dettagli dopo operazione) | S / M |
| 2 | L’interfaccia distingue, **dove i dati lo consentono**, **invio modifiche locali** da **ricezione / applicazione aggiornamenti remoti** (copy basato su summary/fasi) | S / M |
| 3 | Copy più chiari per: **pending**, **successo**, **errore** (inclusi parziali prezzi/sessions), **sessione / auth richiesti**, **config / permessi**, **offline** | S / M |
| 4 | `manualFullSyncRequired` è spiegato in linguaggio comprensibile all’utente (non solo interno) | S / M |
| 5 | I conteggi in `CatalogSyncSummary` e le righe del riepilogo sessioni sono usati **in modo selettivo**; niente muri di numeri inutili | M |
| 6 | **Nessuna** modifica a repository, DAO, Room, data source, schema remoto, **nessun** cambio navigation | S / B |
| 7 | **Nessuna regressione funzionale** su sync (stessi entrypoint, stessi flussi) — verificabile con test JVM mirati ove toccato il VM e smoke manuali | B / M |
| 8 | **Localizzazione 4 lingue** (`values`, `en`, `es`, `zh`) aggiornata in **fase di execution** (questo file planning non modifica L10n) | — (futuro) |
| 9 | Build/lint in **fase di execution** (`assembleDebug`, `lintDebug`, `git diff --check`); nessun esito dichiarato in questo planning se non eseguito | B / S (futuro) |
| 10 | **Nessun** testo user-facing con termini tecnici interni non tradotti (cfr. *Tassonomia* e *Cosa evitare*) | S / M |
| 11 | La differenza **rapida vs completa** è comprensibile **senza** leggere documentazione esterna (titoli+body+eventuali chip) | M |
| 12 | Se la **rapida non basta**, l’UI indica in modo non ambiguo di usare la **sincronizzazione completa** (allineato a `manualFullSyncRequired` e casi limite) | S / M |
| 13 | Dopo **quick** con **ricezione remoto**, il messaggio **non** sembra un mero “invio completato” (presenza riga/etichetta *ricezione* o equivalente) | M |
| 14 | **Sessioni** (backup/cronologia fogli) restano **visivamente separate** da catalogo/prezzi (sezione o blocco distinto, coerente con oggi) | M |
| 15 | `CloudSyncIndicator` resta **compatto** (no lista, no pannello espandibile, no “dashboard” di conteggi) | M |
| 16 | `OptionsScreen` (sezione cloud) resta coerente con **Material3** e lo **stile** già in app (spaziature, card, toni) | M |
| 17 | Criteri **Accessibilità e robustezza UI** (sezione omonima) soddisfatti o eventuali scostamenti documentati in Execution | S / M |

> Legenda: B=Build, S=Static/Lint, M=Manuale.  
> *Definition of Done — UX* (sezione omonima in `docs/MASTER-PLAN.md`): applicabile a chiusura **post-review** con conferma utente.

---

## Rischi di regressione

- **L10n:** divergenze tra 4 file `strings` se un aggiornamento è parziale.
- **Truncation:** `CloudSyncIndicator` a una riga — messaggi «migliori» che risultano illeggibili; mitigare con brevità.
- **Doppia verità percepita:** `primaryMessage` e dettaglio in contrasto se i testi non sono coordinati; rivedere insieme VM + Options.
- **Unit test** `CatalogSyncViewModelTest`: se si cambia solo testo/risorse, i test potrebbero assert su stringhe — aggiornare in pari.

---

## Test / check previsti per **futura** `EXECUTION` (obbligo esecutore, non in questo commit planning)

- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`
- `git diff --check`
- **Se** si modifica `CatalogSyncViewModel.kt` (stringhe, assemblaggio `CatalogSyncUiState`, condizioni `buildUi` / `buildCatalogDetail`, mappe `StringRes`):
  - **Eseguire** `CatalogSyncViewModelTest` (stesso module/app):  
    `./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"`
  - **Aggiornare** o estendere gli assert nel test se le aspettative testuali o i rami visibili cambiano (niente rimozione test solo per “verde” senza motivazione, vedi `AGENTS.md`).
- Se il task a execution tocca aree **TASK-004** percepito (sync/stato), applicare regola baseline in `AGENTS.md`.

---

## Smoke manuali consigliati (post-execution, non in questo planning)

- Options con **utente non loggato**
- Options con **Supabase non configurato** (build flavor / config assente)
- **Sync completa** manuale
- **Quick sync** manuale
- **Auto-sync** dopo modifica prodotto (verifica copy indiretti / indicatore)
- Arrivo **update remoto** (altro device o ambiente) — coerenza con perimetro TASK-059 (messaggi), full verifica in **TASK-063** se serve
- **Errore / offline**
- Caso in cui l’app segnala **necessità di full sync manuale**
- Verifica testi in **IT / EN / ES / ZH**

### Checklist manuale copy (mini — esecutore in `REVIEW` / chiusura)

- Leggere **tutta** la sezione cloud in **IT, EN, ES, ZH** (non solo `values`).
- Verificare **stringhe lunghe** (soprattutto **spagnolo** e **cinese**): niente overflow, niente ellissi su messaggi d’obbligo.
- Stato **offline** (messaggio + CTA abilitate/disabilitate coerenti).
- Stato **non loggato** / **sign-in** richiesto.
- Stato **sync in corso** (Options + `CloudSyncIndicator` su altra rotta se applicabile).
- Stato **quick success** (solo invio, e variante con **aggiornamenti remoto**).
- Stato **full success** (nessun errore parziale).
- Stato `manualFullSyncRequired` (dettaglio leggibile, chip/messaggio coerente).

---

## Non incluso (rinvio o altri task) — **restano esplicitamente fuori scope**

| Task | Perimetro escluso da TASK-059 |
|------|------------------------------|
| **TASK-060** | Refresh puntuale `DatabaseScreen` dopo pull remoto; merge/override riga; eventuali tocchi a `DatabaseViewModel` / repository per *lista*. |
| **TASK-061** | Hardening `sync_events`, fallback logico, outbox, limiti repository, test di integrazione logica. |
| **TASK-062** | Runbook / audit schema Supabase / documentazione backend. |
| **TASK-063** | Smoke live multi-device, matrice A/B, QA strutturato (l’esecutore di TASK-059 può citare allineamento copy, non sostituisce TASK-063). |

Ulteriori voci: modifiche a logica `sync_events`, watermark, outbox, repository **salvo** pura rifinitura *testi* mappata qui sopra; **nessuna** modifica a dati/DAO.

---

## Planning (Claude) — nota iniziale

**Analisi:** Il codice sorgente (`CatalogSyncViewModel`, `OptionsScreen`, `CloudSyncIndicator`, stringhe) conferma un buon sottoinsieme informativo già pronto; il gap principale è **comunicazione** (gerarchia, distinzioni, brevità) non implementazione remota.

**Adeguamento 2026-04-25 (stesso giro, sempre `PLANNING`):** Integrate sezioni **Decisioni UX proposte**, **Tassonomia copy**, **Matrice stato → messaggio → azione**, **Gerarchia visiva consentita**, **Accessibilità e robustezza UI**; criteri e test plan rafforzati; out-of-scope **TASK-060**–**TASK-063** tabellare.

**Piano di esecuzione (sopra + sezioni dettagliate):** Rifinire testi e, se necessario, ordine/aggregazione stringhe in VM e Options; chip/badge ove dati supportano; `CloudSyncIndicator` con voci corte; 4 lingue in un solo giro; se tocca il VM, **verificare/aggiornare** `CatalogSyncViewModelTest`; build/lint/`git diff --check` come da tabella.

**Rischi identificati:** vedi sezione *Rischi di regressione*.

---

## Execution

### Esecuzione — 2026-04-25

**File modificati:**
- `docs/TASKS/TASK-059-rifinitura-ux-sync-cloud.md` — stato portato a `EXECUTION`, poi a `REVIEW` dopo check; log execution, criteri e handoff aggiornati.
- `docs/MASTER-PLAN.md` — task attivo riallineato a TASK-059, fase `REVIEW`; TASK-055 resta `PARTIAL`, TASK-057/TASK-058 restano `DONE`.
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/CatalogSyncViewModel.kt` — aggiunti badge/stati di raccomandazione solo UI, riordinato dettaglio catalogo per full-required/invio/ricezione/sessioni, filtrati conteggi rumorosi.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt` — rifinita sezione cloud con badge, blocchi dettaglio catalogo/sessioni separati, CTA rapida/completa più leggibili e contentDescription.
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/components/CloudSyncIndicator.kt` — indicatore compatto con testi fase corti, massimo due righe e contentDescription con stato.
- `app/src/main/res/values/strings.xml` — copy IT aggiornato e nuove chiavi UI/a11y.
- `app/src/main/res/values-en/strings.xml` — copy EN aggiornato e nuove chiavi UI/a11y.
- `app/src/main/res/values-es/strings.xml` — copy ES aggiornato e nuove chiavi UI/a11y.
- `app/src/main/res/values-zh/strings.xml` — copy ZH aggiornato e nuove chiavi UI/a11y.

**Azioni eseguite:**
1. Letti `docs/MASTER-PLAN.md`, `CLAUDE.md`, `AGENTS.md`, `docs/TASKS/TASK-059-rifinitura-ux-sync-cloud.md` e `docs/TASKS/TASK-055-audit-sync-supabase-ux-scroll-database-history.md` prima di ogni modifica.
2. Letti i file in scope indicati dal task: `CatalogSyncViewModel.kt`, `CloudSyncIndicator.kt`, `OptionsScreen.kt` e le stringhe `values`, `values-en`, `values-es`, `values-zh`.
3. Avviata execution su approvazione esplicita utente; nessuna modifica a repository, DAO, Room, data source, schema Supabase o navigation.
4. Inventariate le stringhe `catalog_cloud_*` esistenti: le chiavi preesistenti coprivano già sync completa/rapida, stati, pending, prezzi e sessioni; sono state riusate dove il significato era corretto.
5. Create nuove chiavi solo per badge, titoli dettaglio, CTA a11y, raccomandazioni e messaggi brevi dell’indicatore; hash delle chiavi `catalog_cloud*`/`cloud_sync_indicator*` identico nelle 4 lingue (`477f5d385d9a5ce67132e7c6c0f2d5a653a29cc2`).
6. Rimossa dai testi visibili la terminologia grezza tipo `sync_events`; i nomi chiave restano tecnici ma non sono user-facing.
7. UI/UX: sezione cloud più respirata, con badge testuali `Completa`/`Rapida`/`Invio`/`Ricezione`/`Sessioni`, separazione catalogo-prezzi vs sessioni, e CTA evidenziate quando la rapida o la completa sono raccomandate.
8. Accessibilità: badge e sezione hanno contenuto testuale, i pulsanti hanno `contentDescription`, l’indicatore cloud espone lo stato localizzato e non usa più ellipsis su una sola riga.
9. Verificato scope: nessuna modifica a `InventoryRepository.kt`, `CatalogAutoSyncCoordinator.kt`, `RealtimeRefreshCoordinator.kt`, `Supabase*RemoteDataSource.kt`, DAO, Room entities, `AppDatabase.kt`, schema Supabase/RLS o navigation.

**Check obbligatori:**
| Check                    | Stato | Note                        |
|--------------------------|-------|-----------------------------|
| Build Gradle             | ✅ | `./gradlew :app:assembleDebug` senza `JAVA_HOME` non trova Java Runtime; rieseguito con `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` -> BUILD SUCCESSFUL. |
| Lint                     | ✅ | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:lintDebug` -> BUILD SUCCESSFUL. |
| Warning nuovi            | ✅ | Nessun warning Kotlin/deprecation introdotto dal codice modificato; restano warning Gradle/toolchain preesistenti (`android.builtInKotlin`, `android.newDsl`, legacy variant API). |
| Coerenza con planning    | ✅ | Modifiche limitate a VM presentation state, Options cloud section, indicator e risorse L10n; nessuna logica sync/remota cambiata. |
| Criteri di accettazione  | ✅ | Verificati singolarmente nella tabella sotto; smoke manuali runtime restano consigliati per review/TASK-063 ma non bloccanti per passaggio a `REVIEW`. |

**Baseline regressione TASK-004 (se applicabile):**
- Test eseguiti: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` -> BUILD SUCCESSFUL.
- Test aggiunti/aggiornati: nessuno; gli assert esistenti restano validi dopo copy/stringhe e stato UI aggiuntivo.
- Limiti residui: nessuno sui test JVM mirati. Non sono stati eseguiti smoke manuali UI/device; vedi handoff.

**Verifica criteri di accettazione:**
| # | Stato | Evidenza |
|---|-------|----------|
| 1 | ESEGUITO | CTA e body distinguono `Sincronizzazione rapida` da `Sincronizzazione completa`; badge `Rapida`/`Completa` nel VM/UI. |
| 2 | ESEGUITO | Copy e badge separano `Invio` e `Ricezione` usando summary esistente (`pushed*`, `pulled*`, targeted fetch, remote updates). |
| 3 | ESEGUITO | Stringhe pending/success/error/auth/config/offline/parziali aggiornate nelle 4 lingue. |
| 4 | ESEGUITO | `manualFullSyncRequired` e quick non verificabile mostrano `Serve sincronizzazione completa` senza gergo interno. |
| 5 | ESEGUITO | Conteggi quick recenti mostrati solo se processati/applicati; niente riga eventi a zero. |
| 6 | ESEGUITO | Nessun file repository/DAO/Room/data source/schema/navigation modificato. |
| 7 | ESEGUITO | Entry point sync invariati; `CatalogSyncViewModelTest` verde. |
| 8 | ESEGUITO | `values`, `values-en`, `values-es`, `values-zh` aggiornati e chiavi allineate. |
| 9 | ESEGUITO | `assembleDebug`, `lintDebug`, `git diff --check` verdi con JBR Android Studio. |
| 10 | ESEGUITO | Nessun testo visibile con `sync_events`, outbox, bootstrap, payload, watermark, RLS o “delta non verificabile”. |
| 11 | ESEGUITO | Differenza rapida/completa spiegata da titolo, body e raccomandazioni CTA. |
| 12 | ESEGUITO | `fullSyncRecommended` evidenzia la completa in casi manual-full-required / non verificabile / parziali. |
| 13 | ESEGUITO | Quick con ricezione usa riga `Ricezione aggiornamenti` e badge `Ricezione`. |
| 14 | ESEGUITO | Dettaglio `Catalogo e prezzi` e `Sessioni e cronologia` sono separati in UI. |
| 15 | ESEGUITO | `CloudSyncIndicator` resta compatto: chip singolo, testi brevi, max due righe, nessuna dashboard. |
| 16 | ESEGUITO | UI resta nel pattern Material3 locale: `OptionsGroup`, badge Material-style, spacing `appSpacing`, nessun redesign schermo. |
| 17 | ESEGUITO | Accessibilità coperta staticamente: testo oltre colore, contentDescription su sezione/pulsanti/indicator; layout usa wrap e FlowRow per lingue lunghe. |

**Incertezze:**
- Nessuna bloccante.
- Smoke visuali su device/emulator per font scaling 200%, portrait stretto e dark theme non eseguiti in questa sessione; raccomandati in review/manual QA.

**Handoff notes:**
- Stato portato a `REVIEW`, non `DONE`.
- Review consigliata: controllare visivamente la sezione cloud in IT/EN/ES/ZH, inclusi stato pending, quick success con ricezione remota, full required e indicatore overlay.
- I warning Gradle/toolchain sono preesistenti e fuori scope TASK-059.

---

## Review

### Review — 2026-04-26

**Revisore:** Codex (review/fix repo-grounded)

**File e scope verificati:**
- `CatalogSyncViewModel.kt` — modifiche limitate a stato/copy/presentation (`statusBadges`, raccomandazioni CTA, ordine dettaglio catalogo); nessuna semantica sync cambiata.
- `OptionsScreen.kt` — sezione cloud più leggibile con badge, blocchi dettaglio separati e CTA rapide/complete; nessuna chiamata Supabase o logica business nel composable.
- `CloudSyncIndicator.kt` — indicatore resta compatto, con testi brevi, massimo due righe e `contentDescription` localizzata.
- `strings.xml` IT/EN/ES/ZH — chiavi `catalog_cloud*` / `cloud_sync_indicator*` allineate; nessuna stringa user-facing con `sync_events`, `outbox`, `bootstrap`, `payload`, `watermark`, `RLS` o “delta non verificabile”.
- Diff scope: nessuna modifica a repository, DAO, Room, data source, schema Supabase o navigation; TASK-060/TASK-061/TASK-062/TASK-063 non invasi.

**Processi Gradle:**
- Verificati processi attivi prima dei check: presenti solo Gradle/Kotlin daemon idle; nessun `gradlew` client appeso da riusare.
- Il comando combinato precedente di Claude non è stato assunto come evidenza; check rieseguiti da zero con JBR Android Studio.

**Check rieseguiti in review:**
| Check | Stato | Evidenza |
|---|---|---|
| Build Gradle | ✅ | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` + `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL`. |
| Lint | ✅ | `./gradlew :app:lintDebug` -> `BUILD SUCCESSFUL`. |
| Baseline TASK-004 mirata | ✅ | `./gradlew :app:testDebugUnitTest --tests "com.example.merchandisecontrolsplitview.viewmodel.CatalogSyncViewModelTest"` -> `BUILD SUCCESSFUL`. |
| Diff hygiene | ✅ | `git diff --check` -> nessun output. |
| Warning nuovi | ✅ | Nessun warning Kotlin/deprecation nuovo dal codice modificato; restano warning Gradle/toolchain preesistenti (`android.builtInKotlin`, `android.newDsl`, legacy variant API, built-in Kotlin migration). |

**Criteri di accettazione:**
| # | Stato | Note |
|---|---|---|
| 1 | ✅ | Completa e rapida sono distinguibili da label, body, badge e CTA. |
| 2 | ✅ | Invio e ricezione sono separati in badge/copy quando summary espone dati utili. |
| 3 | ✅ | Pending, successo, errori parziali, auth/sessione, config/permessi e offline hanno copy coerenti nelle 4 lingue. |
| 4 | ✅ | `manualFullSyncRequired` è mappato a “Serve sincronizzazione completa” / equivalenti localizzati. |
| 5 | ✅ | I conteggi restano selettivi; rimossi dettagli rumorosi quando non aggiungono segnale. |
| 6 | ✅ | Nessun repository/DAO/Room/data source/schema/navigation modificato. |
| 7 | ✅ | Entry point e flussi sync invariati; test mirato del ViewModel verde. |
| 8 | ✅ | IT/EN/ES/ZH aggiornate e chiavi allineate. |
| 9 | ✅ | Build/lint/test/diff check rieseguiti in review e verdi. |
| 10 | ✅ | Nessun gergo interno vietato nel testo visibile; nomi chiave tecnici restano solo interni. |
| 11 | ✅ | La differenza rapida/completa è comprensibile nella UI principale senza documentazione esterna. |
| 12 | ✅ | I casi in cui la rapida non basta evidenziano la sync completa. |
| 13 | ✅ | Quick con ricezione remota non sembra solo “invio completato”. |
| 14 | ✅ | Sessioni/cronologia sono visualmente separate da catalogo/prezzi. |
| 15 | ✅ | `CloudSyncIndicator` resta chip compatto, non dashboard. |
| 16 | ✅ | `OptionsScreen` resta coerente con Material3 e pattern card locale. |
| 17 | ✅ | Accessibilità/robustezza verificate staticamente; wrap/FlowRow e testi brevi riducono rischio overflow su ES/ZH. |

**Problemi trovati:**
- Nessuno bloccante.

**Verdetto:** APPROVED

**Note per fix:**
- Nessun fix codice necessario in review.
- Smoke visuali su device/emulator per font scaling 200%, portrait stretto, dark theme e scenari live multi-device restano consigliati ma non bloccanti per TASK-059; la matrice live resta nel perimetro TASK-063.

---

## Chiusura

| Campo | Valore |
|-------|--------|
| Stato finale | **DONE** |
| Data chiusura | 2026-04-26 |
| Esito | Review APPROVED senza fix codice; criteri di accettazione soddisfatti; check obbligatori verdi. |

---

## Handoff

- **A:** prossimo operatore / planner.
- **File task** questo documento; **MASTER-PLAN** allineato a stato `DONE` per TASK-059.
- **TASK-055** resta `PARTIAL` fino a chiusura follow-up più ampi (non chiusa da TASK-059).
- **TASK-057** e **TASK-058** restano `DONE`.
- Prossimo candidato consigliato dal backlog sync: **TASK-060** (pull remoto → refresh puntuale `DatabaseScreen`) oppure **TASK-063** se si vuole prima evidenza live multi-device.
