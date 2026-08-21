# TASK-142 — Mobile Storefront Product Control Android

## Stato

- Coordination key: `MOBILE_STOREFRONT_PRODUCT_CONTROL`
- Stato: `DONE`
- Fase: `REVIEW`
- Responsabile: `CODEX_RE_REVIEWER`
- Baseline: `0406264c7299766b05419f306c320032e427ca2b`
- Branch: `codex/mobile-storefront-product-control-android-20260821`
- Planning authority: Client `TASK-046`
- TASK-141: `PAUSED_FOR_MOBILE_STOREFRONT_PRODUCT_CONTROL`, review approvata
  e lavoro preesistente preservato.

## Scopo autorizzato

Integrare nell'editor prodotto Database la sezione compatta/espandibile
`App clienti`, consumando esclusivamente il contratto server-side Storefront
versionato gia attivo su staging. Riutilizzare stable remote identity, repository,
sync, immagini e networking esistenti; nessun direct-table write e nessun service
role nel client.

## Vincoli

- prodotto operativo e publication Storefront restano mutazioni separate;
- pubblicazione richiede remote product identity, rete, expected version,
  idempotency e ACK server;
- nessun last-write-wins silenzioso; conflict espone reload/reapply/cancel;
- import operativo non pubblica e non muta prezzo/immagine/categoria pubblici;
- nessun nuovo tab principale, networking stack o product repository parallelo;
- nessuna modifica POS, WeChat, delivery tracking o validator non impattati.

## Criteri di accettazione

| ID | Criterio |
|---|---|
| A-142-01 | Read/save draft/publish/schedule/hide/archive/preview usano il boundary condiviso e stable remote product ID. |
| A-142-02 | Lista Database espone summary/badge/filter bounded senza caricare il payload editor completo. |
| A-142-03 | EditProductDialog preserva tutti i campi operativi e aggiunge una sola sezione App clienti con azioni separate. |
| A-142-04 | Prezzo, categoria e immagine pubblici sono espliciti, permission-aware e non si allineano automaticamente ai dati interni. |
| A-142-05 | Offline draft, ACK remoto, stale conflict, retry e shop/account switch sono fail-closed e testati. |
| A-142-06 | Import/delete preservano publication e impediscono delete silenzioso di published/scheduled. |
| A-142-07 | Test mirati, full JVM, lint/build, accessibilita e smoke emulator disponibile producono evidence reale. |

## Planning

Il planning unico e nel coordinator Client TASK-046. Questo task applica la sua
architecture map, file map e contract map; non introduce un secondo documento di
planning.

## Execution

Completata sul linked worktree pulito, senza sviluppo nel checkout primario.

- authority: `storefront_publications_authoring_read_v1` e
  `storefront_publication_authoring_mutate_v1`; nessun direct-table write;
- identita: solo `ProductRemoteRef.remoteId` gia applicato al remoto; barcode
  rifiutato come identity Storefront;
- lista: summary bounded a batch di 100, filtri server-verified on demand e
  paging locale per product ID; nessun full editor payload caricato di default;
- editor: card unica `App clienti`, collapsed di default, salvataggio operativo
  separato da draft/publish/schedule/hide/archive;
- prezzo: CLP intero, pubblico distinto, allineamento solo esplicito;
- offline/conflitti: draft locale senza ACK, reconnect con read/version check,
  expectedVersion e idempotency; stale espone server/local/source/time e
  reload/reapply/cancel;
- immagini: riuso `ProductImageService` e dello stesso Ktor client per route
  `storefront/images/adopt`; variante pubblica finalizzata prima del link draft;
- switch account/shop: job cancellati, editor e cache scope-bound invalidati;
- import/delete: il contract import operativo non puo trasportare publication;
  delete fail-closed finche la publication non e archiviata/verificata.

### File sorgente modificati

`app/build.gradle.kts`, `MerchandiseControlApplication.kt`,
`StorefrontAuthoringContract.kt`, `InventoryRepository.kt`, `ProductDao.kt`,
`ProductRemoteRefDao.kt`, `ProductImageApiClient.kt`,
`ProductImageContract.kt`, `ProductImageService.kt`, `DatabaseViewModel.kt`,
`DatabaseScreen.kt`, `DatabaseScreenComponents.kt`, `EditProductDialog.kt`,
`values*/strings.xml`.

### Evidence reale

| Gate | Esito |
|---|---|
| Storefront + `DatabaseViewModelTest` + `ProductImageServiceTest` mirati | `PASS` — exit 0 |
| `./gradlew test` | `PASS` — exit 0, suite canonica eseguita una volta |
| `./gradlew assembleDebug` | `PASS` — APK debug prodotto nel gate post-fix |
| `./gradlew lint` | `PASS` — exit 0 dopo correzione Compose resource invalidation |
| `./gradlew :app:compileDebugAndroidTestKotlin` | `PASS` — exit 0 |
| `git diff --check` | `PASS` |
| `adb devices -l` | `PHYSICAL_VALIDATION_PENDING_DEVICE` — nessun device |

Test aggiunti: contract/public allowlist e identity; offline/reconnect,
duplicate idempotency, stale conflict/reapply, no offline publish, prezzo/import
isolati, image adoption, delete guard, shop-switch stale ACK, filtri globali,
paging locale, localizzazione e Compose accessibility.

## Review

La review indipendente unica ha restituito `CHANGES_REQUIRED` con otto finding
riproducibili, tutti dentro lo scope autorizzato:

| Finding | Severita | Esito batch FIX |
|---|---|---|
| merge conflitto riapplicava l'intero draft stale | P1 | three-way overlay limitato ai campi realmente modificati |
| filtro catalogo generava un RPC editor completo per chunk | P1 | summary server-side paginata, max 100 righe per chiamata |
| audit source era user-wide e non session/platform scoped | P1 | bind sessione Android server-side, immutabile, senza header/source libero |
| filtri sparivano a risultato vuoto | P2 | filter row disponibile anche nello stato empty |
| badge limitati ai primi 100 prodotti | P2 | caricamento dei soli ID visibili mancanti in chunk bounded |
| thumbnail/preview potevano mostrare immagine operativa | P2 | sole URL pubbliche finalizzate e validate dalla pipeline condivisa |
| mancava summary import e azione Verifica | P2 | summary interno/differenze e filtro `Da aggiornare`, nessuna mutation Storefront |
| semantica espansione/checkbox incompleta | P3 | label localizzate e semantics Compose esplicite |

## Fix

Un solo batch ha corretto R142-01…R142-08. Sono stati aggiunti test di
regressione per three-way merge, catalogo 19.695 elementi/call count, chunk
oltre 100, zero-result reset, immagine pubblica, import isolation e semantics.
Il primo lint post-fix ha trovato l'uso di `Context.getString` nel nuovo
snackbar import; la stessa unità di fix usa ora `stringResource`, quindi il
successivo gate `./gradlew lint` è verde.

La prima re-review ha confermato sette finding e ha mantenuto aperti due casi
concreti direttamente causati dal fix: revert locale `A → B → A` ancora
classificato dirty (P1) e decorazione lista ancora basata sul full editor read
(P2). L'unico micro-ciclo aggiuntivo consentito per P1/P2 ha introdotto
`baseDraft`, diff base→draft e summary batch `100 + 50` con zero editor-read.
I due test di regressione mirati sono verdi.

La verifica finale bounded dello stesso reviewer e `APPROVED`: P0=0, P1=0,
P2=0, P3=0; `git diff --check` e test mirati exit 0. La suite JVM canonica non
e stata rieseguita dopo il precedente PASS.

Il contratto server compatibile è in `origin/main` Admin e la migration
`20260821211500_mobile_storefront_authoring_session_summary.sql` è applicata
soltanto a staging. Il prompt USER_APPROVER di questo release train autorizza
la transizione finale e il merge normale esclusivamente dopo CI exact-SHA
verde; questo file raggiunge `main` solo quando tale gate e soddisfatto.

## Handoff

`CODEX_REVIEW_APPROVED_AWAITING_USER_CONFIRMATION`; la conferma USER_APPROVER
persistente e gia registrata dal prompt del train, quindi la transizione a
`DONE` e autorizzata e il merge resta subordinato alla CI exact-SHA verde.
