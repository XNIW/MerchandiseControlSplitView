# Supabase backend runbook

> TASK-062 artifact. Repo-grounded audit only: no live migration was applied and no
> definitive SQL schema is invented here.

## Index

- [Overview](#overview)
- [Evidence levels](#evidence-levels)
- [Local Supabase project sources reviewed](#local-supabase-project-sources-reviewed)
- [Backend objects summary](#backend-objects-summary)
- [Android - Supabase mapping](#android---supabase-mapping)
- [Tables](#tables)
- [RPC](#rpc)
- [Realtime](#realtime)
- [RLS / Security model](#rls--security-model)
- [Secrets / BuildConfig](#secrets--buildconfig)
- [Migration/versioning policy](#migrationversioning-policy)
- [Quick start nuovo ambiente](#quick-start-nuovo-ambiente)
- [Verification checklist](#verification-checklist)
- [Troubleshooting](#troubleshooting)
- [Known gaps / Follow-up TASK-063](#known-gaps--follow-up-task-063)

## Overview

This document describes the Supabase backend expected by the Android client in
this repository. It is based on:

- Android Kotlin code under `app/src/main/java/com/example/merchandisecontrolsplitview/`.
- The versioned SQL artifact in `supabase/migrations/`.
- Existing task documentation for TASK-055, TASK-061 and TASK-062.

No Supabase project was introspected during TASK-062. Therefore this document
does not use `LIVE` evidence for any backend object.

Final review on 2026-04-26 additionally inspected the local Supabase workspace
`/Users/minxiang/Desktop/MerchandiseControlSupabase` as a read-only local schema
source. That folder is useful evidence, but it is not live introspection and it
does not prove the current remote state. Objects inferred only from Kotlin are
marked `CODE`; objects found in SQL files in this Android repository are marked
`MIGRATION`; objects found only in the local Supabase workspace are marked
`LOCAL_SUPABASE_PROJECT`; missing pieces are marked as gaps or `ASSUMPTION`.

## Evidence levels

| Level | Meaning | How to read it here |
|-------|---------|---------------------|
| `CODE` | Deduced from Android Kotlin DTOs, PostgREST calls, RPC calls or Realtime subscribers. | Confirms what the client expects, not the real DDL. |
| `MIGRATION` | Confirmed by a versioned SQL file in this repository. | Confirms a repo artifact, not live deployment. |
| `LOCAL_SUPABASE_PROJECT` | Confirmed by files under `/Users/minxiang/Desktop/MerchandiseControlSupabase`. | Local source/evidence only; not a live verification and not applied by TASK-062. |
| `LIVE` | Verified on a real Supabase project with attached evidence. | Not used in this TASK-062 document. |
| `ASSUMPTION` | Reasonable inference not backed by code or migration. | Must not be treated as backend truth. |

## Local Supabase project sources reviewed

The following files were read from
`/Users/minxiang/Desktop/MerchandiseControlSupabase` during final review. Treat
them as local source material for future import/reconciliation, not as proof of
remote production state.

| Local file | What it evidences locally | TASK-062 interpretation |
|------------|---------------------------|-------------------------|
| `supabase/migrations/20260416_task010_shared_sheet_sessions_realtime.sql` | Initial `shared_sheet_sessions` table and Realtime publication entry. | Historical source; later hardened by ownership/RLS migration. |
| `supabase/migrations/20260417_task012_ownership_rls.sql` | `owner_user_id`, owner-scoped RLS, grant/revoke model for `shared_sheet_sessions`. | Local schema source for session ownership. |
| `supabase/migrations/20260417120000_task013_inventory_catalog_rls.sql` | Base DDL, RLS and grants for `inventory_suppliers`, `inventory_categories`, `inventory_products`. | Local schema source for catalog tables. |
| `supabase/migrations/20260417200000_task016_inventory_product_prices.sql` | Base DDL, constraints, RLS and grants for `inventory_product_prices`. | Local schema source for price history table. |
| `supabase/migrations/20260418200000_task019_inventory_catalog_tombstone.sql` | `deleted_at`, active-row unique indexes, anti-resurrection trigger for `inventory_*`. | Local schema source for tombstone behavior. |
| `supabase/migrations/20260421120000_task038_restrict_authenticated_delete_inventory.sql` | Removal of authenticated `DELETE` posture for inventory catalog/prices. | Local source for final delete restriction intent. |
| `supabase/migrations/20260422120000_task040_shared_sheet_sessions_v2.sql` | `display_name` and `session_overlay` payload v2 extension. | Local schema source for current session payload shape. |
| `supabase/migrations/20260424021936_task045_sync_events.sql` | `sync_events` DDL, indexes, select RLS, RPC `record_sync_event`, grants and publication add. | Local schema source for event lane; not copied or applied here. |
| `docs/mapping_room_to_supabase.md`, `docs/decisions.md` | Decision history and Android/Room-to-Supabase mapping. | Supporting context, not DDL. |
| `TASKS/046_apply_verify_sync_events_staging_live.md` | Local task log claims live apply/verification for `sync_events`. | Useful operational history, but this TASK-062 review did not re-verify live state; not marked `LIVE`. |

## Backend objects summary

| Object | Kind | Evidence | Status | Notes |
|--------|------|----------|--------|-------|
| `inventory_suppliers` | Table | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Android consumer + Android-repo hardening artifact + local DDL source | Full `CREATE TABLE` is absent from this Android repo, but present in the local Supabase project. |
| `inventory_categories` | Table | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Android consumer + Android-repo hardening artifact + local DDL source | Same as suppliers. |
| `inventory_products` | Table | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Android consumer + Android-repo hardening artifact + local DDL/tombstone source | Trigger/function source exists locally; Android repo only has hardening. |
| `inventory_product_prices` | Table | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Android consumer + Android-repo hardening artifact + local DDL source | Full DDL/constraints absent from Android repo, present locally. |
| `shared_sheet_sessions` | Table | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Android consumer + Android-repo hardening artifact + local DDL/v2 source | Full DDL absent from Android repo, present locally. |
| `sync_events` | Table | `CODE`, `LOCAL_SUPABASE_PROJECT` | Android consumer + local SQL source found | DDL/RLS/indexes are absent from Android repo; local migration `20260424021936_task045_sync_events.sql` is the source to review/import. |
| `record_sync_event` | RPC | `CODE`, `LOCAL_SUPABASE_PROJECT` | Android consumer + local SQL source found | Function body, owner enforcement and grants are absent from Android repo; present locally. |
| `sync_events` Realtime | Realtime publication | `CODE`, `LOCAL_SUPABASE_PROJECT` | Client expects it; local migration adds table to publication | Live publication still requires verification. |
| `shared_sheet_sessions` Realtime | Realtime publication | `CODE`, `LOCAL_SUPABASE_PROJECT` | Client expects it; local migration adds table to publication | Live publication still requires verification. |
| `history_entries` | Legacy remote table name | `MIGRATION`, `CODE` | Android local Room table; remote legacy policies dropped | Do not treat Room `history_entries` as active Supabase sync table. |
| `product_price_summary` | View | `MIGRATION`, `CODE` | Remote view hardening artifact; Room view also exists locally | Remote view definition not versioned. |
| `sync_event_outbox`, `sync_event_watermarks`, `sync_event_device_state` | Room-only tables | `CODE` | Local only | Not Supabase backend objects. |

## Android - Supabase mapping

| Backend object | Kotlin consumer files | Operations used | Evidence | Status | Risk if schema diverges |
|----------------|-----------------------|-----------------|----------|--------|-------------------------|
| `inventory_suppliers` | `SupabaseCatalogRemoteDataSource.kt`, `InventoryCatalogRemoteRows.kt`, `InventoryRepository.kt` | `select` paginated, `select` by ids, `upsert onConflict=id`, tombstone `patch` (`deleted_at`, `updated_at`) | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Confirmed repo-side; local DDL source found; live unknown | Upsert/tombstone may fail; full/bootstrap pull may miss rows; RLS drift can leak or hide data. |
| `inventory_categories` | `SupabaseCatalogRemoteDataSource.kt`, `InventoryCatalogRemoteRows.kt`, `InventoryRepository.kt` | `select` paginated, `select` by ids, `upsert onConflict=id`, tombstone `patch` (`deleted_at`, `updated_at`) | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Same as suppliers | Same as suppliers. |
| `inventory_products` | `SupabaseCatalogRemoteDataSource.kt`, `InventoryCatalogRemoteRows.kt`, `InventoryRepository.kt` | `select` paginated, `select` by ids, `upsert onConflict=id`, tombstone `patch` (`deleted_at`, `updated_at`) | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Confirmed repo-side; local DDL/tombstone source found; live unknown | Product bridges break; targeted `sync_events` catch-up cannot fetch changed products; tombstone policy/trigger drift can block updates. |
| `inventory_product_prices` | `SupabaseProductPriceRemoteDataSource.kt`, `InventoryCatalogRemoteRows.kt`, `InventoryRepository.kt` | `select` paginated, `select` by ids, `upsert onConflict=id` | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Confirmed repo-side; local DDL source found; live unknown | Price history becomes stale; duplicate/constraint drift can cause 23505 or missing bridges. |
| `shared_sheet_sessions` | `SupabaseSessionBackupRemoteDataSource.kt`, `SharedSheetSessionRecord.kt`, `SupabaseRealtimeSessionSubscriber.kt`, `InventoryRemoteFetchSupport.kt`, `InventoryRepository.kt`, `MerchandiseControlApplication.kt` | `select` paginated by `remote_id`, `upsert onConflict=remote_id`, Realtime `postgres_changes` for all `PostgresAction` records | `CODE`, `MIGRATION`, `LOCAL_SUPABASE_PROJECT` | Confirmed repo-side; local DDL/publication source found; live publication unknown | History/session backup restore fails; cross-owner leak if RLS missing; Realtime session refresh stays silent if publication missing. |
| `sync_events` | `SupabaseSyncEventRemoteDataSource.kt`, `SyncEventModels.kt`, `SupabaseSyncEventRealtimeSubscriber.kt`, `CatalogAutoSyncCoordinator.kt`, `InventoryRepository.kt`, `MerchandiseControlApplication.kt` | Capability `select`, fetch after watermark, owner/store/id filters, Realtime `Insert` filtered by `owner_user_id` | `CODE`, `LOCAL_SUPABASE_PROJECT` | SQL absent from Android repo; local migration source found; live unknown | Quick/delta catch-up breaks; app falls back to full sync recommendation; RLS drift can leak cross-owner events. |
| RPC `record_sync_event` | `SupabaseSyncEventRemoteDataSource.kt`, `SyncEventModels.kt`, `InventoryRepository.kt` | `rpc("record_sync_event", params)` returning `SyncEventRemoteRow` | `CODE`, `LOCAL_SUPABASE_PROJECT` | SQL absent from Android repo; local function source found; live unknown | Outbox remains pending; remote devices do not wake from changes; owner spoofing risk if RPC drifts from local source. |
| Realtime `sync_events` | `SupabaseSyncEventRealtimeSubscriber.kt`, `MerchandiseControlApplication.kt`, `CatalogAutoSyncCoordinator.kt` | Channel `sync-events-v1-{owner}`, `PostgresAction.Insert`, filter `owner_user_id = signed-in user` | `CODE`, `LOCAL_SUPABASE_PROJECT` | Local migration adds publication entry; live must be verified | Incremental remote wake-up does not happen; foreground/network catch-up remains fallback path. |
| Realtime `shared_sheet_sessions` | `SupabaseRealtimeSessionSubscriber.kt`, `RealtimeRefreshCoordinator.kt`, `MerchandiseControlApplication.kt` | Channel `shared-sheet-sessions-v1-v2`, `postgresChangeFlow<PostgresAction>` on table | `CODE`, `LOCAL_SUPABASE_PROJECT` | Local migration adds publication entry; live must be verified | Remote session materialization is not pushed to Room until manual/bootstrap fetch. |
| `history_entries` legacy | Migration SQL, local Room `HistoryEntry.kt`/`HistoryEntryDao.kt` | Remote broad policies are dropped; Android active remote path is `shared_sheet_sessions` | `MIGRATION`, `CODE` | Legacy remote object only | Leaving remote legacy policies open can expose history; confusing it with Room can lead to wrong migrations. |

## Tables

### `inventory_suppliers`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `id`, `owner_user_id`, `name`, `deleted_at` | `CODE` | Confirmed client contract. |
| Tombstone patch | Updates `deleted_at` and `updated_at` with filters `id`, `owner_user_id`, `deleted_at is null` | `CODE` | Client expects `updated_at`; Android repo lacks base DDL, local source includes it. |
| Upsert key | `onConflict = "id"` | `CODE` | Constraint source is local, not versioned in this Android repo. |
| RLS/index | Owner-scoped select/insert/update; index `(owner_user_id, id)` | `MIGRATION` | Versioned artifact, live application unknown. |
| Local schema source | Base table/RLS in local migration `20260417120000_task013_inventory_catalog_rls.sql`; `deleted_at` and active unique index in `20260418200000_task019_inventory_catalog_tombstone.sql`; DELETE restriction in `20260421120000_task038_restrict_authenticated_delete_inventory.sql`. | `LOCAL_SUPABASE_PROJECT` | Local source only; not copied/applied by TASK-062. |

### `inventory_categories`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `id`, `owner_user_id`, `name`, `deleted_at` | `CODE` | Confirmed client contract. |
| Tombstone patch | Updates `deleted_at` and `updated_at` with owner filter | `CODE` | Client expects `updated_at`; Android repo lacks base DDL, local source includes it. |
| Upsert key | `onConflict = "id"` | `CODE` | Constraint source is local, not versioned in this Android repo. |
| RLS/index | Owner-scoped select/insert/update; index `(owner_user_id, id)` | `MIGRATION` | Versioned artifact, live application unknown. |
| Local schema source | Base table/RLS in local migration `20260417120000_task013_inventory_catalog_rls.sql`; `deleted_at` and active unique index in `20260418200000_task019_inventory_catalog_tombstone.sql`; DELETE restriction in `20260421120000_task038_restrict_authenticated_delete_inventory.sql`. | `LOCAL_SUPABASE_PROJECT` | Local source only; not copied/applied by TASK-062. |

### `inventory_products`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `id`, `owner_user_id`, `barcode`, `item_number`, `product_name`, `second_product_name`, `purchase_price`, `retail_price`, `supplier_id`, `category_id`, `stock_quantity`, `deleted_at` | `CODE` | Confirmed client contract. |
| Tombstone patch | Updates `deleted_at` and `updated_at` with owner filter | `CODE` | Client expects `updated_at`; Android repo lacks base DDL, local source includes it. |
| Upsert key | `onConflict = "id"` | `CODE` | Constraint source is local, not versioned in this Android repo. |
| RLS/index | Owner-scoped select/insert/update; index `(owner_user_id, id)` | `MIGRATION` | Versioned artifact, live application unknown. |
| Tombstone hardening | Function `inventory_catalog_block_update_when_tombstoned()` gets `search_path` hardening | `MIGRATION` | Function/trigger source is local; Android repo only has hardening. |
| Local schema source | Base table/RLS in local migration `20260417120000_task013_inventory_catalog_rls.sql`; `deleted_at`, active barcode unique index, function and triggers in `20260418200000_task019_inventory_catalog_tombstone.sql`; DELETE restriction in `20260421120000_task038_restrict_authenticated_delete_inventory.sql`. | `LOCAL_SUPABASE_PROJECT` | Local source only; live trigger state still requires verification. |

### `inventory_product_prices`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `id`, `owner_user_id`, `product_id`, `type`, `price`, `effective_at`, `source`, `note`, `created_at` | `CODE` | Confirmed client contract. |
| Upsert key | `onConflict = "id"` | `CODE` | Constraint source is local, not versioned in this Android repo. |
| Reads | Full paginated read ordered by `id`; targeted read by remote ids | `CODE` | Confirmed client contract. |
| RLS/index | Owner-scoped select/insert/update; index `(owner_user_id, id)` | `MIGRATION` | Versioned artifact, live application unknown. |
| Local schema source | Table, `type` check, unique `(owner_user_id, product_id, type, effective_at)`, FK to `inventory_products`, RLS/grants in local migration `20260417200000_task016_inventory_product_prices.sql`; DELETE restriction in `20260421120000_task038_restrict_authenticated_delete_inventory.sql`. | `LOCAL_SUPABASE_PROJECT` | Local source only; not copied/applied by TASK-062. |

### `shared_sheet_sessions`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `remote_id`, `payload_version`, `display_name`, `timestamp`, `supplier`, `category`, `is_manual_entry`, `data`, `session_overlay`, `owner_user_id`, `updated_at` | `CODE` | Confirmed client contract. |
| Upsert key | `onConflict = "remote_id"` | `CODE` | Constraint source is local, not versioned in this Android repo. |
| Reads | Full paginated read ordered by `remote_id` | `CODE` | Confirmed client contract. |
| RLS/index | Drops public select; owner-scoped select/insert/update/delete; index `(owner_user_id, remote_id)` | `MIGRATION` | Versioned artifact, live application unknown. |
| Realtime payload | Subscriber decodes full `SharedSheetSessionRecord` from `postgres_changes` | `CODE` | Publication and replica identity must be verified live. |
| Local schema source | Initial table/publication in local migration `20260416_task010_shared_sheet_sessions_realtime.sql`; ownership/RLS/grants in `20260417_task012_ownership_rls.sql`; v2 columns/check in `20260422120000_task040_shared_sheet_sessions_v2.sql`. | `LOCAL_SUPABASE_PROJECT` | Local source only; current live grants/publication still require verification. |

### `sync_events`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client columns | `id`, `owner_user_id`, `store_id`, `domain`, `event_type`, `source`, `source_device_id`, `batch_id`, `client_event_id`, `changed_count`, `entity_ids`, `created_at`, `metadata` | `CODE` | Client contract only. |
| Capability check | Select by `owner_user_id`, order `id`, range `0..0` | `CODE` | Used to decide whether `sync_events` path is available. |
| Fetch path | Select where `owner_user_id`, `store_id` null/value, `id > watermark`, order `id`, limit `1..500` | `CODE` | Client contract only. |
| RLS/index/DDL | Not found in `supabase/migrations/*.sql` | `CODE` gap | Must be dumped or versioned from a verified source. |
| Realtime | Insert-only subscription filtered by `owner_user_id` | `CODE` | Publication must be verified live. |
| Local schema source | Local migration `20260424021936_task045_sync_events.sql` creates table, constraints, indexes, owner SELECT policy, grant SELECT to `authenticated`, RPC and publication add. It also has `expires_at`, which Android does not currently decode. | `LOCAL_SUPABASE_PROJECT` | Good source for future import/review; not present in Android repo and not verified live in TASK-062. |

### `history_entries` legacy remote object

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Android local table | Room entity/table `history_entries` is local state | `CODE` | Not a Supabase active sync table. |
| Remote policy hardening | Broad authenticated policies are dropped because the remote legacy table lacks `owner_user_id` | `MIGRATION` | Versioned hardening artifact, live application unknown. |
| Active remote history/session sync | Uses `shared_sheet_sessions`, not remote `history_entries` | `CODE`, `MIGRATION` | Confirmed repo-side. |

### `product_price_summary`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Remote hardening | `alter view public.product_price_summary set (security_invoker = true)` | `MIGRATION` | Versioned artifact, live application unknown. |
| View definition | Not present for Supabase in repo | gap | Needs schema dump or authoritative migration if remote clients depend on it. |
| Room view | Android has a local Room view with the same name | `CODE` | Do not assume it equals the Supabase view. |

## RPC

### `record_sync_event`

| Aspect | Detail | Evidence | Status |
|--------|--------|----------|--------|
| Client call | `postgrest.rpc("record_sync_event", params).decodeSingle<SyncEventRemoteRow>()` | `CODE` | Confirmed client contract. |
| Params | `p_domain`, `p_event_type`, `p_changed_count`, `p_entity_ids`, `p_store_id`, `p_source`, `p_source_device_id`, `p_batch_id`, `p_client_event_id`, `p_metadata` | `CODE` | Confirmed client contract. |
| Return | One `SyncEventRemoteRow` | `CODE` | Confirmed client contract. |
| SQL body | Not found in Android repo; found locally in `20260424021936_task045_sync_events.sql` | `LOCAL_SUPABASE_PROJECT` | Local source uses `security definer`, validates domain/event type/entity ids/metadata and inserts `owner_user_id` from `auth.uid()`. |
| Grants | Not found in Android repo; found locally in `20260424021936_task045_sync_events.sql` | `LOCAL_SUPABASE_PROJECT` | Local source revokes broadly and grants execute to `authenticated`; live grants still require verification. |
| Owner model | Local source sets `v_owner := auth.uid()` and does not accept caller-supplied owner id | `LOCAL_SUPABASE_PROJECT` | Correct shape locally; still not `LIVE` evidence. |

Operational expectation: this RPC should not accept a caller-supplied owner id.
The Android client sends event metadata and entity ids, then expects the server
to create an owner-scoped event row and return it.

## Realtime

| Table | Client channel/use | Events expected | Evidence | Status |
|-------|--------------------|-----------------|----------|--------|
| `sync_events` | `sync-events-v1-{ownerUserId}` | `Insert` only, filtered by `owner_user_id` | `CODE`, `LOCAL_SUPABASE_PROJECT` | Local migration adds table to `supabase_realtime`; publication, privileges and latency not re-verified live here. |
| `shared_sheet_sessions` | `shared-sheet-sessions-v1-v2` | Generic `PostgresAction` with decodable record | `CODE`, `LOCAL_SUPABASE_PROJECT` | Local migration adds table to `supabase_realtime`; publication, privileges and payload shape not re-verified live here. |

Realtime is an optimization/wake-up path. The catalog source of truth remains
Room locally plus PostgREST fetch/apply paths. If `sync_events` Realtime is not
available, the app still has foreground/network drain paths and full sync
fallback UX from TASK-061.

## RLS / Security model

Repo-grounded model:

- Business tables are expected to be owner-scoped with `owner_user_id = auth.uid()`.
- The versioned migration uses `(select auth.uid()) = owner_user_id` for the
  `inventory_*`, `inventory_product_prices` and `shared_sheet_sessions` policies.
- `shared_sheet_sessions_select_public` is explicitly dropped in the migration.
- Broad authenticated policies on remote legacy `history_entries` are dropped.
- `sync_events` RLS is not versioned in this Android repo; a local Supabase
  migration source exists and must be reviewed/imported or compared with a dump.
- `record_sync_event` must prevent owner spoofing; local source does so via
  `auth.uid()`, but the Android repo lacks that SQL artifact.

Objects with migration-backed RLS/index artifacts:

| Object | Policies in repo | Evidence | Live state |
|--------|------------------|----------|------------|
| `inventory_suppliers` | select/insert/update owner-scoped | `MIGRATION` | Unknown |
| `inventory_categories` | select/insert/update owner-scoped | `MIGRATION` | Unknown |
| `inventory_products` | select/insert/update owner-scoped | `MIGRATION` | Unknown |
| `inventory_product_prices` | select/insert/update owner-scoped | `MIGRATION` | Unknown |
| `shared_sheet_sessions` | select/insert/update/delete owner-scoped; public select dropped | `MIGRATION` | Unknown |
| `history_entries` | broad authenticated policies dropped | `MIGRATION` | Unknown |

Objects without versioned RLS in this Android repo:

| Object | Gap | Evidence |
|--------|-----|----------|
| `sync_events` | Table DDL, owner policy, indexes, grants and publication are missing from Android repo; present in local Supabase migration source. | `CODE`, `LOCAL_SUPABASE_PROJECT` |
| `record_sync_event` | Function body, grants and owner enforcement are missing from Android repo; present in local Supabase migration source. | `CODE`, `LOCAL_SUPABASE_PROJECT` |

## Secrets / BuildConfig

| Variable | Used by | Source in repo | Notes |
|----------|---------|----------------|-------|
| `SUPABASE_URL` | `MerchandiseControlApplication.createSupabaseClient` | `app/build.gradle.kts` -> `BuildConfig.SUPABASE_URL` | Read from environment first, then root `local.properties`. |
| `SUPABASE_PUBLISHABLE_KEY` | Same Supabase client | `app/build.gradle.kts` -> `BuildConfig.SUPABASE_PUBLISHABLE_KEY` | Do not commit real keys. |
| `GOOGLE_WEB_CLIENT_ID` | Google sign-in -> Supabase Auth | `app/build.gradle.kts` -> `BuildConfig.GOOGLE_WEB_CLIENT_ID` | Required for Google ID token flow. |

`readLocalOrEnv(name)` resolves values in this order:

1. `System.getenv(name)` if non-blank.
2. Root `local.properties`.
3. Empty string.

The application creates a Supabase client only when URL and publishable key are
non-blank. Missing config disables remote sync/auth paths and keeps the app
offline-first.

Do not commit:

- real anon/publishable keys when the repository is public;
- service-role keys;
- database passwords;
- SQL output containing secrets.

## Migration/versioning policy

See also `supabase/migrations/README.md`.

Rules:

1. A committed `.sql` file is a versioned artifact, not proof of live deployment.
2. No production/staging migration may be applied without explicit approval and
   an execution log outside this document.
3. No `schema.sql`, `0001_init.sql` or RPC SQL may be called definitive unless it
   comes from a verified source such as `pg_dump --schema-only`, Supabase
   Dashboard export, or a previously reviewed migration.
4. Future SQL files must state source, target environment, status, live
   application state and approval metadata.
5. SQL inferred only from Kotlin DTOs must remain `ASSUMPTION` or a draft note,
   not backend truth.

Known migration order in this repo:

| Order | File | Type | Notes |
|-------|------|------|-------|
| 1 | `supabase/migrations/20260424190000_harden_rls_and_sync_indexes.sql` | Incremental hardening artifact | RLS owner policies, indexes, view/function hardening, legacy policy drops. Not full DDL. |

Local Supabase workspace source chain observed during final review:

| Order | Local file | Type | Notes |
|-------|------------|------|-------|
| 1 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260416_task010_shared_sheet_sessions_realtime.sql` | Initial session table/publication | Historical public-read baseline, superseded by ownership hardening. |
| 2 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260417_task012_ownership_rls.sql` | Session ownership/RLS | Adds `owner_user_id` and owner-scoped policies/grants. |
| 3 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260417120000_task013_inventory_catalog_rls.sql` | Catalog DDL/RLS | Base `inventory_*` tables and policies. |
| 4 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260417200000_task016_inventory_product_prices.sql` | Price history DDL/RLS | Base `inventory_product_prices` table and policies. |
| 5 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260418200000_task019_inventory_catalog_tombstone.sql` | Catalog tombstone | `deleted_at`, partial unique indexes and anti-resurrection trigger. |
| 6 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260421120000_task038_restrict_authenticated_delete_inventory.sql` | Delete restriction | Removes authenticated delete posture for inventory catalog/prices. |
| 7 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260422120000_task040_shared_sheet_sessions_v2.sql` | Session payload v2 | Adds `display_name` and `session_overlay` check. |
| 8 | `/Users/minxiang/Desktop/MerchandiseControlSupabase/supabase/migrations/20260424021936_task045_sync_events.sql` | Sync event lane | Adds `sync_events`, RPC, indexes, grants and publication entry. |

Before copying any of these local files into this Android repository, record
source, target environment, artifact status and approval in
`supabase/migrations/README.md`. Do not treat the local chain as a schema dump
or live proof without a fresh review.

## Quick start nuovo ambiente

Use this as a checklist for a new Supabase project. Do not apply to production
without approval.

- [ ] Create a dedicated Supabase project, preferably staging before production.
- [ ] Obtain an authoritative schema source: `pg_dump --schema-only`, reviewed
      migration chain, or Supabase Dashboard export.
- [ ] If using the local Supabase workspace as source, review the files listed in
      [Local Supabase project sources reviewed](#local-supabase-project-sources-reviewed)
      and label any copied SQL as a new reviewed artifact; do not apply it live
      from this task.
- [ ] Confirm DDL for all Android-consumed tables: `inventory_suppliers`,
      `inventory_categories`, `inventory_products`, `inventory_product_prices`,
      `shared_sheet_sessions`, `sync_events`.
- [ ] Confirm primary/unique keys used by Android upserts: `id` for inventory
      tables/product prices, `remote_id` for shared sessions.
- [ ] Confirm required columns from the [Tables](#tables) section, including
      JSON payload columns and timestamps.
- [ ] Apply or review owner-scoped RLS for inventory tables and
      `shared_sheet_sessions` using the versioned hardening artifact as a repo
      reference.
- [ ] Add or verify `sync_events` table RLS and indexes from a verified source.
- [ ] Add or verify RPC `record_sync_event` from a verified source. It should set
      owner from `auth.uid()` and grant execution only as intended.
- [ ] Enable Realtime publication for `sync_events` and `shared_sheet_sessions`.
- [ ] Configure Android build inputs: `SUPABASE_URL`,
      `SUPABASE_PUBLISHABLE_KEY`, `GOOGLE_WEB_CLIENT_ID`.
- [ ] Build Android against that environment.
- [ ] Run RLS/RPC/Realtime verification below with two users before declaring
      the backend ready.
- [ ] Record environment, approval and application status for every SQL artifact.

## Verification checklist

These checks are not marked as executed by TASK-062. They are the required
operational checklist for review, staging setup, or TASK-063.

### RLS

- [ ] User A does not see User B rows on `inventory_suppliers`.
- [ ] User A does not see User B rows on `inventory_categories`.
- [ ] User A does not see User B rows on `inventory_products`.
- [ ] User A does not see User B rows on `inventory_product_prices`.
- [ ] User A does not see User B rows on `shared_sheet_sessions`.
- [ ] User A does not see User B rows on `sync_events`.
- [ ] Anonymous client cannot read or write business tables.
- [ ] `shared_sheet_sessions` is owner-scoped for select/insert/update/delete.
- [ ] `sync_events` is owner-scoped for select and insert/RPC-created rows.
- [ ] Legacy remote `history_entries` is not exposed as a cross-user table.

### RPC

- [ ] `record_sync_event` succeeds for an authenticated user with valid params.
- [ ] `record_sync_event` returns a row matching `SyncEventRemoteRow`.
- [ ] `record_sync_event` does not allow owner spoofing.
- [ ] Duplicate `client_event_id` behavior is understood and documented.
- [ ] Grants are limited to the intended role(s), normally `authenticated`.

### Realtime

- [ ] `sync_events` is in the Realtime publication.
- [ ] `shared_sheet_sessions` is in the Realtime publication.
- [ ] Insert into `sync_events` for User A wakes User A only.
- [ ] Insert/update/delete on `shared_sheet_sessions` emits decodable records.
- [ ] Authenticated JWT is required; anonymous subscriptions do not expose
      business rows.

### PostgREST / pagination

- [ ] Supabase `max_rows` is compatible with Android page size
      `INVENTORY_REMOTE_PAGE_SIZE = 900`.
- [ ] Inventory full reads ordered by `id` return all pages.
- [ ] `shared_sheet_sessions` full reads ordered by `remote_id` return all pages.
- [ ] Targeted `isIn` reads work for chunks used by Android.

## Troubleshooting

| Symptom | Likely cause | Check |
|---------|--------------|-------|
| Remote sync/auth disabled in app | Missing `SUPABASE_URL` or `SUPABASE_PUBLISHABLE_KEY` | Verify env/local.properties and `BuildConfig` values. |
| Google login does not start or fails | Missing/wrong `GOOGLE_WEB_CLIENT_ID` | Verify OAuth client and `BuildConfig.GOOGLE_WEB_CLIENT_ID`. |
| `sync_events_schema_or_rls_unavailable` | Table missing, RLS denies select, or PostgREST cannot decode expected columns | Check `sync_events` DDL/RLS and authenticated select by owner. |
| Quick sync reports full sync recommended | `sync_events` gap, event too large, missing IDs, or drain limit reached | Inspect app logs for `manualFullSyncRequired`, `syncEventsGapDetected`, `syncEventsTooLarge`. |
| Session backup push fails with permission denied | `shared_sheet_sessions` RLS/owner mismatch | Verify authenticated JWT and `owner_user_id = auth.uid()`. |
| Realtime session updates are silent | Table not in publication, RLS blocks event, or payload shape differs | Verify Dashboard Realtime settings and decode columns. |
| Realtime `sync_events` silent | `sync_events` not in publication or owner filter mismatch | Verify publication and row `owner_user_id`. |
| Product/price pull misses rows | DDL/index/keys drift or PostgREST pagination limit mismatch | Verify keys, indexes and `max_rows`. |
| 23505 or duplicate conflicts on upsert | Unique/PK differs from Android `onConflict` fields | Verify `id`/`remote_id` unique constraints from real schema. |

## Known gaps / Follow-up TASK-063

| Gap | Evidence | Risk | Follow-up |
|-----|----------|------|-----------|
| Full DDL for `inventory_*`, `inventory_product_prices`, `shared_sheet_sessions` is not versioned in this Android repo. | `CODE`, `MIGRATION` delta, `LOCAL_SUPABASE_PROJECT` source chain | Android repo alone cannot recreate a new environment; local Supabase source may drift from actual live. | Import reviewed SQL artifacts or add a schema dump with provenance. |
| `sync_events` table DDL/RLS/indexes are not versioned in this Android repo. | `CODE`, `LOCAL_SUPABASE_PROJECT` local migration | Quick/delta sync can fail or leak across users if live config drifts from local source. | Decide whether to import `20260424021936_task045_sync_events.sql` as an artifact, then verify live. |
| RPC `record_sync_event` SQL/grants are not versioned in this Android repo. | `CODE`, `LOCAL_SUPABASE_PROJECT` local migration | Event recording/outbox may fail; owner spoofing risk if deployed function differs. | Import/review function artifact or compare with dump before relying on it. |
| Realtime publication for `sync_events` is not verified live by TASK-062. | `CODE`, `LOCAL_SUPABASE_PROJECT` local migration | Device B may not wake on Device A catalog changes. | TASK-063 live A/B smoke and backend publication check. |
| Realtime publication for `shared_sheet_sessions` is not verified live by TASK-062. | `CODE`, `LOCAL_SUPABASE_PROJECT` local migration | Session/history changes may not arrive via Realtime. | TASK-063 or dedicated live check. |
| No schema-only dump artifact exists in this Android repo. | Repo audit + local source chain | Review cannot prove live backend equals intended schema. | Add dump metadata/artifact when approved. |
| `product_price_summary` remote view definition is absent. | `MIGRATION` hardening only | Security setting exists as artifact, but view shape cannot be recreated. | Capture view definition from verified source if remote clients depend on it. |
| `history_entries` remote legacy status is only partially documented. | `MIGRATION` drop policies | A stale table could remain live with unexpected grants if migration not applied. | Verify live policies and document final legacy decision. |

TASK-063 should remain a separate live smoke task. It should validate two-user /
two-device behavior, Realtime publication, RLS isolation, and fallback UX. TASK-062
does not close TASK-055 and does not activate TASK-063.
