# TASK-139 Android — acceptance autenticata P0

Stato: `PASS` per same-account/same-shop, cold offline cache e reconnect;
TASK-139 resta `REVIEW`, non `DONE`.

Data run: 2026-07-19. Target: emulatore Android autenticato già fornito
dall'utente. Nessuna identità account/shop, credenziale o token è riportata.

## Installazione e preservazione dati

- APK finale installata esclusivamente in-place (`install -r -t`), senza
  uninstall, clear-data, reset DB, logout, discard/replace o reinstallazione del
  simulatore.
- SHA-256 APK finale:
  `a4b2a12887ba30a8f13b66f67bedc5f196c01d8b766bad65b19acd70274a1ed6`.
- Inode DB prima/dopo: `33697`; schema Room finale: `21`.
- Snapshot finale: prodotti `19734`, fornitori `76`, categorie `48`, prezzi
  `41146`, history entries `83`, outbox `0`, device-state `1`.
- Il prodotto QA mascherato (`9913…2329`) esiste una sola volta e conserva una
  versione immagine; nessuna mutation immagine è stata avviata nel ciclo
  offline/reconnect.

## Binding e sessione

- Login eseguito usando l'account Google già presente; nessuna credenziale
  digitata o acquisita nell'evidence.
- Il successivo relaunch ha ripristinato la sessione senza chooser.
- Binding Room singleton: `1` riga, owner hash lungo `64`, protocollo `126`,
  schema `2`, epoch `1`; il binding legacy è stato rimosso dopo l'import.
- Same-account/same-shop converge a `READY`; log finale dopo reconnect:
  `business_scope status=READY`.

## Bug runtime trovati e corretti

1. La perdita della rete validata non aggiornava subito Options. Il tracker ora
   pubblica la connettività reale e lo stato offline non mostra claim positivi.
2. `CatalogCloudContent` mostrava il dettaglio «Automatic sync active» anche in
   offline/error. Il dettaglio è ora derivato dallo stato UI online valido.
3. Durante il cold restore, lo stato shop temporaneamente irrisolto veniva
   interpretato come cambio shop e purgava la cache. Gli stati transitori non
   cambiano più scope; logout/cambio account/cambio shop effettivi continuano a
   purgare lo scope precedente.
4. Nel cold offline la cache esisteva ma il contesto shop remoto non era
   risolvibile. La lettura cache-only può ora derivare lo shop esclusivamente da
   un binding Room owner-hash/protocol/schema/epoch valido; account mismatch
   resta fail-closed e produce zero rete.
5. Al reconnect il contesto shop fallito offline non veniva ritentato. Il retry
   è ora automatico, coalesced e limitato a account signed-in + rete validata +
   errore recuperabile; foreground e reconnect non richiedono pulsanti manuali.

## Ciclo reale online → cold offline → reconnect

| Passo | Evidence reale |
|---|---|
| Warm online | thumbnail reale scaricata; cache disco `1` file |
| Cold launch offline | ping fallito (`exit 2`); processo riavviato; prodotto e thumbnail reale renderizzati dalla cache; cache ancora `1` file |
| Options offline | claim «Automatic sync active» `0`; sync business in pausa, dati locali disponibili |
| Reconnect | ping riuscito (`exit 0`); un solo retry shop-context; binding torna `READY` automaticamente |
| Options finale | account connesso, `Up to date`; riconciliazione completa richiesta come stato dati separato, non mismatch account/shop |
| Integrità | outbox `0`, cache `1`, prodotto/immagine preservati, nessun upload duplicato avviato |

Screenshot privacy-safe aperti e verificati:

- `/tmp/task139-android-cold-offline-cache-reconnect-final.png`;
- `/tmp/task139-android-options-auto-reconnect-final.png`.

Logcat del ciclo finale, ripulito prima del cold launch:

- retry shop-context su reconnect: `1`;
- `FATAL EXCEPTION`: `0`;
- ANR app: `0`;
- errori Room/SQLite: `0`;
- errori auth: `0`;
- blocchi binding dopo il ritorno `READY`: `0`.

## Gate finali

| Gate | Risultato |
|---|---|
| Suite mirate Application/ProductImageService/Database/Catalog/Options | `101/101 PASS`, 0 skip/failure/error |
| JVM completo forced | `674` totali: `669 PASS`, `5` opt-in/live/fixture-local skipped, `0` failure/error; nessuno skip P0/TASK-139 |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |
| `lintDebug` | PASS |

Il prodotto/immagine QA non è stato rimosso: serve ancora alla verifica iOS e
alla convergenza cross-platform. Nessuna azione distruttiva è stata eseguita.
