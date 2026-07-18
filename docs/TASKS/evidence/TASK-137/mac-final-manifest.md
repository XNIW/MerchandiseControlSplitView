# Manifest consolidamento finale Mac — Android

Data: 2026-07-17
Repository canonico: `/Users/minxiang/Projects/MerchandiseControlSplitView`
Stato iniziale: `main` @ `8e7c88918d520b78073b8d0d9a1460f0ff4b215b`; `origin/main` uguale; ahead/behind `0/0`; worktree sporco limitato a TASK-137.
Branch di separazione: `integrate/mac-final-android-20260717T150455Z`.
Repository secondario preservato, non integrato: `/Users/minxiang/AndroidStudioProjects/MerchandiseControlSplitView`.

Commit TASK-137 creati sul branch di separazione:

- `d3b1d93` — runtime/UI;
- `57befb2` — test/cache/isolation.

Metadati comuni per ogni path sotto: repository Android canonico; `include=yes`;
dipendenza `TASK-137 contract Admin`; evidence `25/25` baseline, test origin
binding mirato, instrumentation finale invalidata `1/1`, assemble/lint baseline.
I path aggiunti (`A` nei receipt Git dei due commit) erano untracked al
recovery; tutti gli altri erano tracked. Le sezioni assegnano categoria,
motivo e dipendenze a ciascun path senza eccezioni implicite.

La whitelist seguente è esaustiva. Tutti i path non elencati restano esclusi.

## TASK-137 — runtime e risorse (`C. TASK137_ANDROID_SOURCE`)

- `app/build.gradle.kts`
- `app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/AppDatabase.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryCatalogRemoteRows.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/InventoryRepository.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/Product.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/data/ProductDao.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreen.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/DatabaseScreenComponents.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/EditProductDialog.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/viewmodel/DatabaseViewModel.kt`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/xml/file_paths.xml`
- `app/schemas/com.example.merchandisecontrolsplitview.data.AppDatabase/20.json`
- `app/src/androidTest/AndroidManifest.xml`
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/res/xml/task137_network_security_config.xml`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageApiClient.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageCache.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageContract.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageProcessor.kt`
- `app/src/main/java/com/example/merchandisecontrolsplitview/productimage/ProductImageService.kt`

## TASK-137 — test (`E. TASK137_TEST`)

- `app/src/test/java/com/example/merchandisecontrolsplitview/data/AppDatabaseMigrationTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/DefaultInventoryRepositoryTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/data/ProductImageCatalogContractTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/productimage/ProductImageCacheTest.kt`
- `app/src/test/java/com/example/merchandisecontrolsplitview/productimage/ProductImageProcessorTest.kt`
- `app/src/androidTest/java/com/example/merchandisecontrolsplitview/productimage/ProductImageDeviceTest.kt`

## TASK-137 — governance ed evidence (`G. TASK137_DOCUMENTATION`)

- `docs/MASTER-PLAN.md`
- `docs/TASKS/TASK-137-product-catalog-images-cross-platform-android.md`
- `docs/TASKS/evidence/TASK-137/README.md`
- `docs/TASKS/evidence/TASK-137/android-instrumentation.xml`
- `docs/TASKS/evidence/TASK-137/android-summary.json`
- `docs/TASKS/evidence/TASK-137/mac-final-manifest.md`

## Esclusioni e blocker preservati

- `docs/TASKS/evidence/TASK-137/android-instrumentation.log` — untracked,
  `J. GENERATED_EXCLUDE`, log runtime raw, `include=no`.
- Il file TASK-103 sigillato presente nel repository secondario dipende da modifiche cumulative fuori scope e non compila isolatamente nel repository canonico; non è stato integrato.
- Il repository secondario resta intatto. Nessun deploy, pubblicazione Play Store o migrazione remota.
- Ogni path non elencato è deterministically `L. UNRELATED_PRESERVE`, salvo
  output build/cache (`J`) o file sensibili/config locali (`K`); nessun path
  `M. UNKNOWN_BLOCK` è incluso. Non sono presenti modifiche `.gitattributes`.
