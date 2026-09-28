# Android mobile parity — benchmark grande dataset

Data esecuzione: 2026-09-28T17:44:51.940Z. **PASS**: 1 test, 0 failure/error/skip, 7.772s nel test; Gradle BUILD SUCCESSFUL in 17s, zero nuovi warning Kotlin. Questo benchmark misura **core Room/repository su host**, non UI Android, sync, network o performance device.

## Dataset e ambiente

- Fixture esclusivamente sintetica in Room in-memory: **20.000 prodotti, 300.000 righe prezzo, 100 fornitori, 50 categorie**. Ogni prodotto ha 8 prezzi acquisto + 7 vendita, relazioni reali supplier/category e nomi Unicode `Café 商品`. Seed via DAO production in chunk **200 prodotti / 3.000 prezzi**, dentro transazioni Room; nessuna lista completa di 300.000 oggetti. Nessun dato live o harness Excel.
- Seed: **2698.088ms**. Conteggi finali e risultati delle query asseriti; pagine in ordine, prezzo corrente/precedente, 8 righe history acquisto. No-op asserito senza dirty/history/notifica; 33 mutazioni nome (3 warmup+30 misure) con esattamente 33 notifiche, revisione32 e history invariata.
- Mac15,9 / Apple M3 Max / **48GiB RAM host**, macOS aarch64; JDK **17.0.17**, Robolectric API33, heap massimo test **512MiB**. Host condiviso con altre attività; non un laboratorio con CPU isolata. Nessun cache flush o forced GC.
- **30 campioni per scenario**, dopo **3 warmup** per scenario; durata con `System.nanoTime`, percentile nearest-rank. I campioni includono i controlli di correttezza e il dispatch/repository applicabile. Ricerca usa veri PagingSource Room con placeholder/count; ogni campione costruisce e invalida la propria source.
- Misurata soltanto l'implementazione finale. **Nessun confronto prima/dopo**, nessuna ottimizzazione introdotta, nessuna soglia temporale usata per pass/fail.

## Risultati warm (millisecondi)

| Scenario | p50 | p95 | max |
|---|---:|---:|---:|
| Pagina dettagli 50, offset 0 | 2.109 | 3.666 | 5.073 |
| Pagina dettagli 50, offset 10000 | 2.502 | 4.974 | 5.184 |
| Pagina dettagli 50, offset 19950 | 3.390 | 5.095 | 5.142 |
| PagingSource search barcode specifico con count | 25.155 | 26.385 | 26.590 |
| PagingSource search nome ampio con count | 56.681 | 58.733 | 59.000 |
| Lookup barcode/prezzi correnti | 0.125 | 0.262 | 0.291 |
| Dettaglio prodotto/prezzi precedenti | 0.074 | 0.174 | 0.206 |
| Prima emissione flow history acquisto | 0.327 | 0.787 | 0.803 |
| Editor invariato no-op | 0.281 | 0.393 | 0.405 |
| Editor modifica nome + dirty transaction | 0.616 | 1.502 | 1.535 |

Il dato maggiore osservato è la ricerca ampia con count: **59.000ms max**. Non permette di inferire il tempo render/UI, storage reale su telefono, avvio a freddo, import/export, sincronizzazione cloud, background/sospensione/force-stop o una garanzia assoluta di 3 secondi.

## Memoria misurata

- Pagine SQLite dopo seed: **54,317,056 byte / 51.80MiB**, da `PRAGMA page_count * page_size`; è dimensione pagine del DB, non memoria totale del processo.
- Heap JVM usato: inizio **109.96MiB**, dopo seed **131.57MiB**, fine **151.88MiB**. Snapshot `totalMemory-freeMemory`, influenzati da GC, non picco assoluto e non RSS Android.
- **Picco RSS host osservato 455.91MiB** del processo test JVM PID 36607; `ps -o rss` circa ogni100ms, **54 campioni** dalla fase seed fino a complete. Include Robolectric/framework/SQLite/heap e non è RSS dell'app Android. Il campionamento può perdere picchi intermedi: **non** etichettarlo come kernel maxRSS.
- La stima preliminare incrementale100–200MB era una stima; i numeri sopra sono misure con metriche distinte, non una validazione di un delta incrementale preciso.

## Riproduzione ed evidenze

File nuovo e unico della slice: `app/src/test/java/com/example/merchandisecontrolsplitview/data/MobileParityLargeDatasetBenchmarkTest.kt`. Opt-in; nei gate/CI normali il test salta prima di allocare il DB. Nessuna modifica sourceapp/buildconfig/dependency/schema. Test congelato SHA256 `da0528f442a986cb28bd71e10cba1d2cbd981ad0444bce6ddf695e86cf5851f7`; InventoryRepository SHA256 `0a17c12d191dcca6e1c21c0e47e2328bd94388ef222609e8f84ba9f7a1e06916`. Baseline HEAD `d7c4953c4ed6bc2a33cc5dbfd009eb862f70feac` più patch del task nel worktree condiviso.

```sh
MOBILE_PARITY_LARGE_BENCHMARK=1 \
MOBILE_PARITY_BENCHMARK_OUTPUT=large-dataset-samples.json \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/minxiang/Library/Android/sdk' \
./gradlew testDebugUnitTest --tests '*MobileParityLargeDatasetBenchmarkTest' --console=plain
```

- Tutti i **300 campioni**: `large-dataset-samples.json`.
- Campioni RSS e metodo: `large-dataset-rss.json`; sampler `/tmp/task143-monitor-benchmark-rss.py`.
- Output Gradle: `/tmp/task143-android-large-benchmark.log`.
- XML test: `large-dataset-test.xml`.
- Il run è stato serializzato con android_executor; slot Gradle restituito al termine per gate canonici e Compose finali. Nessun rerun benchmark necessario senza nuove modifiche/finding.
