# TASK-139 Android — Matrice contract parity finale

Data verifica: `2026-07-18`. Fonte canonica:
`contracts/product-image-v1.json` (SHA-256
`612a403b1397546cad62b38cf70ad666c7290bfcdae1973778ff8b1ff85f1686`).

| Requisito | Admin | Android | iOS | Test esistente | Test mancante | Drift | Patch necessaria | Risultato |
|---|---|---|---|---|---|---|---|---|
| Main/thumb budget | 768000 B target, 1 MiB hard; thumb 92160 B | Uguale | Uguale | processor + shared contract TS/Kotlin/Swift | Server staging condiviso | Nessuno nel contratto | Sì, solo freeze Kotlin diretto dei budget JSON | `PASS_LOCAL` |
| Ladder compressione | Fattori/quality canonici | Uguale | Uguale | vector JSON consumati nei tre linguaggi | Nessuno locale | Drift iterativo già risolto | No | `PASS` |
| Marker JPEG | Solo APP0 JFIF; no COM/APP1…APP15/trailing | Canonicalizer + validator | Canonicalizer + validator | marker regressions + encoder reali | Supabase staging | Nessuno noto | No | `PASS_LOCAL` |
| API fields e MIME | Intent/finalize/read/remove; `image/jpeg` obbligatorio | Uguale | Uguale | fixture/API serializer/loopback | Staging | Nessuno noto | No | `PASS_LOCAL` |
| Batch/concurrency | 100 ref; max 4 download | Uguale | Uguale | Android 200 richieste in batch `[100,100]`, max 4 | Staging condiviso | Nessuno | No | `PASS_LOCAL` |
| Signed URL lease | TTL 300 s, safety 30 s, memory-only | LRU 256 | LRU 1000 | expiry/refresh/LRU | Expiry reale staging | Budget platform-specific intenzionale | No | `PASS_LOCAL` |
| Retry | Upload 1 transient/5xx; URL refresh 1; decode 0 | Uguale; status HTTP separato dal code canonico | Uguale | retry 503, permanent 403, double 403 | Failure controllato staging | Nessuno | Sì, rimossi suffissi HTTP dai code runtime | `PASS_LOCAL` |
| Cache | 32 MiB/256 | 8 MiB memory + 64 MiB disk, `noBackup`, atomica | 48 MiB/100 + 128 MiB | LRU, byte budget, temp cleanup, decode | Device fisico | Budget platform-specific intenzionale | No | `PASS_EMULATOR` |
| Account/shop scope | account/shop/product/version/variant | Uguale | Uguale | cache/session isolation + purge | Switch su staging | Nessuno | No | `PASS_LOCAL` |
| Progressive rendering | Lista thumb; dettaglio thumb → main | Uguale | Uguale | UI harness e assert semantici | UI autenticata staging | Naming nativo intenzionale | No | `PASS_SYNTHETIC_EMULATOR` |
| Cancellazione | Ultimo consumer abortisce batch | Coroutine upload/load cancellabili | Task on-disappear | ordered phase/cancel + UI lifecycle tests | Rete staging | Nessuno noto | No | `PASS_LOCAL` |
| Upload progress | preprocess/intent/main/thumb/finalize/completed | Uguale | Uguale | ordine completo + cancel | UI autenticata staging | Naming nativo intenzionale | No | `PASS_LOCAL` |
| Cleanup | Lock, dry-run/execute, ledger/audit | N/A client | N/A client | pgTAP/script coordinatore | Staging | N/A Android | No | `COORDINATOR` |
| Error code | 17 codici condivisi | Allowlist identica e guard runtime; nessun code interno fuori contratto | Uguale | parity JSON, scan literal runtime, rejection guard + runtime suites | Risposte staging | Drift `image_scope_changed`/`image_metadata_strip_failed` e suffissi HTTP corretto | Sì | `PASS_LOCAL` |
| No-image | Zero read-url/Storage | Zero rete e zero cache write | Uguale | service test dedicato | Rete staging | Nessuno | No | `PASS_LOCAL` |
| Sync/domain | Solo version ID/timestamp; no blob/path/URL | Uguale | Uguale | boundary JSON + sync tests | Parity staging | Nessuno | No | `PASS_LOCAL` |
| Contratto/fixture | Fonte canonica | Copia byte-identica | Copia byte-identica | `cmp`, SHA-256, test TS/Kotlin/Swift | Nessuno | Nessuno | No | `PASS_BYTE_IDENTICAL` |
| Visual/performance | Browser viewport | API 35: sei stati, 200 prodotti, 20 editor | Simulator harness | instrumentation/assert + screenshot ispezionati | Android staging/device fisico | Nessun difetto locale | No | `PASS_SYNTHETIC_EMULATOR` |
| Canonicalizer allocation | N/A server | Compattazione in-place; zero copia su JPEG pulito, una copia finale se ridotto | Implementazione nativa separata | identità buffer pulito 1 MiB + vector APP2 `19→10 B` | Profilazione device fisico | Gap Android corretto | Sì | `PASS_LOCAL` |

## Hash byte-identici nei tre worktree

- contract: `612a403b1397546cad62b38cf70ad666c7290bfcdae1973778ff8b1ff85f1686`;
- fixture valida: `5912807c913ff04af05e6d35339ae43eb875ab1e56c246cb916d894f212b0e49`;
- fixture invalida: `b089914123663e806a7304dda251d654ff8436447f10474bfb804d3fea318fd8`;
- vector sintetici: `34322705f2e036fdd68d21a46f2310fa14da57894a312786e93a97495dd987d9`.

`cmp` Admin↔Android e iOS↔Android e `shasum -a 256 -c` hanno restituito
exit `0`. Le differenze platform-specific restano solo input HEIC iOS, budget
cache e cap delle lease firmate; wire format, API, marker, ladder e semantica
restano condivisi.
