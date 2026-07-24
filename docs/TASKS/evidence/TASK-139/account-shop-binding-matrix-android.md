# TASK-139 P0 — Matrice account/shop binding Android

Stato: matrice implementata e verificata su host il 2026-07-19. Nessuna riga di
questa matrice autorizza reset, logout globale o mutazioni sul database reale.

| Caso | Binding locale | Dati locali | Account/shop attivo | Decisione | Rete business | Outbox | UI/azioni |
|---|---|---:|---|---|---|---|---|
| A | presente | qualsiasi | stesso owner + stesso shop + stesso protocollo/schema | `READY` | pull/reconcile, poi push | solo stesso owner/shop | sync automatica reale, nessun dialog |
| B | assente | completamente vuoto | account/shop validi | associa atomicamente e verifica, poi `READY` | consentita dopo il commit del binding | nessuna entry da adottare | nessun dialog |
| C | assente | prodotti/fornitori/categorie/prezzi/history/outbox o pending | account/shop validi | `REVIEW_REQUIRED_UNBOUND` | zero business request | zero drain | conteggi reali; mantieni oppure scarto esplicito con doppia conferma |
| D | presente | qualsiasi | owner diverso | `BLOCKED_ACCOUNT_MISMATCH` | zero business request | zero drain | dialog Material 3 a due azioni; keep/back/dismiss non mutano nulla, il singolo tap distruttivo owner-safe conferma il replace |
| E | presente | qualsiasi | stesso owner, shop diverso | `BLOCKED_SHOP_MISMATCH` | zero business request | zero drain | stessa dialog a due azioni; keep/back/dismiss non mutano nulla, replace abilitato solo con shop corrente verificato |
| F | presente | qualsiasi | stesso scope, conflitto reale | policy conflitti TASK-126 esistente | solo elementi sicuri | solo stesso scope | conflitto classificato, non generico stato positivo |
| G | presente | dirty | stesso scope, offline | `OFFLINE` senza cambiare binding | nessuna finché offline | resta nello stesso scope | attesa pulita; reconnect riprogramma automaticamente |
| H | assente/presente | qualsiasi | auth/shop in verifica o errore recuperabile | `CHECKING` / `ERROR_RECOVERABLE` | zero business request | zero drain | nessuna dicitura «sync attiva» |

## Drift Android pre-patch

- `MerchandiseControlApplication.alignBusinessDataScope` cancellava
  automaticamente catalogo, prezzi e history su qualunque mismatch, incluso
  il primo shop selezionato con database unbound.
- Il binding era una stringa owner/shop salvata con `SharedPreferences.apply()`:
  nessun commit sincrono, nessuna verifica e owner raw persistito nel valore.
- `CatalogAutoSyncCoordinator`, `HistorySessionPushCoordinator`, bootstrap
  automatico di Options e Realtime potevano essere svegliati dall'evento auth
  prima della decisione binding.
- `Task126OwnerStoreGate` era coperto da soli test statici e non proteggeva i
  cicli runtime.
- Options non distingueva unbound dirty, account mismatch e shop mismatch e
  poteva quindi mostrare una dicitura positiva mentre il runtime non era sicuro.

## Contratto di implementazione Android

- Estendere il gate TASK-126, non introdurre un secondo orchestratore.
- Pubblicare la decisione binding nel `CatalogSyncStateTracker` già condiviso.
- Fare controllare la stessa decisione a catalog bootstrap/push/drain, history,
  sync manuale, Realtime e immagini prima di rete business.
- Persistenza strutturata owner-hash/store/local-store/protocol/schema in una
  riga singleton Room. La migrazione additiva `20→21` crea soltanto la tabella:
  non resetta e non adotta dati. Controllo di vuoto, import del binding legacy e
  insert/readback avvengono nella stessa transazione Room, senza loggare ID.
- Nessun reset automatico. Lo scarto unbound è disponibile solo dalla UI dopo
  conferma esplicita; delete fixture + nuovo binding sono una transazione Room e
  non vengono esercitati sul database reale di acceptance.
- Account/shop mismatch hanno un'azione diversa dal discard unbound: per default
  mantengono dati e sync bloccata; il singolo tap distruttivo nella dialog
  Material 3 a due azioni conferma la sostituzione atomica di dati e binding.
  Same-scope e schema mismatch non sono sostituibili.
- I test host devono provare `0` chiamate repository/remote/device per review,
  account mismatch e shop mismatch, oltre a outbox cross-scope non drenata.

## Risultato post-patch

- `Task126OwnerStoreGate` valida owner hash, shop, local shop, protocollo,
  schema ed epoch; il medesimo stato vive nel `CatalogSyncStateTracker`.
- La migration Room `20→21` è additiva e preserva i dati v20. Il binding legacy
  viene importato come autorevole senza reset; un mismatch resta bloccato.
- I gate runtime coprono catalog bootstrap/push/drain, history, manual sync,
  realtime e immagini. Le verifiche vengono ripetute dopo device check e attesa
  single-flight per intercettare cambi account/shop concorrenti.
- Options mostra conteggi reali per prodotti, fornitori, categorie, prezzi,
  history, pending e outbox; discard unbound conserva il proprio percorso,
  mentre account/shop mismatch usa una sola dialog a due azioni riapribile da
  `Rivedi` e mantenere i dati resta l'esito predefinito.
- Suite P0 combinata: `146/146 PASS`, 0 skipped/failure/error.
- Suite JVM completa finale: `674` test totali, `669 PASS`, `5` opt-in/live skipped,
  `0` failure/error; nessuno skip appartiene a TASK-139/P0.
- Runtime autenticato installato in-place: same-scope `READY`, cold offline
  preserva e mostra la thumbnail cache, reconnect ritenta automaticamente il
  contesto shop e ritorna `READY`; outbox finale `0`, nessun dato cancellato.
- Evidence runtime: `android-authenticated-runtime-p0.md`.

## Addendum UX account/shop mismatch — 2026-07-19

La sola UI Android dei casi D/E è stata autorizzata come patch execution
mirata, quindi la lane non è più `review-only` esclusivamente per questo punto.
Gate, coordinator, repository, Room e policy della matrice restano quelli già
validati. La dialog viene presentata automaticamente al massimo una volta per
fingerprint mismatch privacy-safe e resta riapribile manualmente; shop non
risolto, auth non valida o snapshot locale non verificabile mantengono il
replace disabilitato. Evidence completa:
`android-account-shop-mismatch-dialog-addendum.md`.

Il finding P1 emerso nell'audit è chiuso senza cambiare questa matrice:
upload/remove immagini registrano ora l'intero flight nella lease TASK-126 già
esistente; discovery/transition invalidano intent/PUT/finalize/remove prima di
qualunque fase successiva o apply locale stale.
