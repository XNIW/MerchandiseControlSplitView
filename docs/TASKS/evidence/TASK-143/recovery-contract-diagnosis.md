# Native recovery contract diagnosis — 2026-09-28

## Outcome

The deployed TEST checkpoint contract intentionally returns a short denial envelope for `resource_exceeded` and `invalid_baseline`. Both original native adapters attempt to decode the full success DTO before checking `status`; missing success-only fields can therefore produce Android `MissingFieldException` or iOS `DecodingError.keyNotFound` and conceal the server denial.

A read-only invocation of the deployed resource preflight for the designated TEST shop, with its existing verified mapping and canonical union scope inferred from current rows, returned `resourceExceeded=true`, `storageScanStatus=compressed_legacy_history_requires_remediation`, and `activeCompressedHistoryCount=16`. The row-count limits were not exceeded.

This is a confirmed current server preflight blocker plus a confirmed client/contract incompatibility. It is not a captured response from the exact Android request: no app tokens, claims, session, or actual device identifier were extracted or impersonated. The sanitized Android log exposes only the exception type, so exact request attribution still needs the client's safe RPC/status tracing or the repaired client's authenticated retry.

## Verified source and deployed compatibility

Canonical source:
`/Users/minxiang/Projects/merchandise-control-admin-web/supabase/migrations/20260722013109_cross_platform_sync_event_completeness.sql`

| Function | Source line | Deployed prosrc MD5 | Deployed pg_get_functiondef MD5 |
| --- | ---: | --- | --- |
| app_private.sync_recovery_preflight_counts_v1(uuid,text,uuid,uuid) | 6279 | bdd119e370dc9e4a9b03290c8217e2ad | 51e5ab68addc2fab0f96cdc717024ab2 |
| public.shop_sync_recovery_checkpoint_v1(uuid,text,text,text) | 7651 | 8c9d0add91798f5b88509dde8ba5c7d8 | d35fd818c96870a7183b8da8cac19641 |
| public.shop_sync_recovery_page_v1 (8 arguments) | 9594 | 7ba9b916953abe77252f98ffa737c8a5 | 64af07acdba360133bf36be198fae1ea |

All three deployed function bodies matched the canonical source bodies exactly by MD5 during this investigation. Checksums are compatibility evidence, not a replacement for runtime behavior.

The later `20260722020000_task_139_recovery_runtime_lease.sql` changes the scope resolver/runtime lease and marks these public readers VOLATILE. It takes authorization-related row locks; public recovery RPCs were not invoked with fabricated identities.

The success branch and page required nested fields match the Android and iOS DTO expectations at source inspection. In particular the deployed page includes `currentScopeEventMaxId`, `baselineDomainEventMaxId`, `pageDomainEventMaxId`, and accepts `p_expected_domain_event_max_id`. A union of all function-definition keys does not establish that every branch returns all keys.

## Exact short envelope

Root keys for both early-denial branches:

```text
schemaVersion
digestContract
status
shopId
scope
syncEvents
payloadBudgets
resourcePreflight
checkpointDigest
```

Absent by contract: `catalog`, `prices`, `history`, `images`, `integrity`.

`scope` keys: `kind`, `historyKind`, `key`, `legacyOwnerKey`, `accountKey`, `deviceKey`.

The first preflight envelope's `syncEvents` contains:
- `maxId`, `verifiedBaselineId`: canonical decimal strings.
- `historicalBlockingCountStatus="not_scanned"`.
- `inspectionLimit=10000`, `inspectedCount=0`, `scanComplete=false`, `requiresFullRecovery=true`.
- `blockingCount`, `oldestBlockingId`, `newestBlockingId`: null.
- `domainMaxIds`: `catalog`, `prices`, `history`, each a canonical decimal string.

Branch ordering: invalid baseline (`maxId < verifiedBaselineId`) precedes `resourceExceeded`. Thus an invalid-baseline envelope can legitimately also include a resource-exceeded preflight. The server hashes the checkpoint JSONB text before appending `checkpointDigest`.

## Current read-only preflight

Scope inferred from current designated TEST shop/mapping rows: `authorized_shop_plus_legacy`; verified mapping present. The preflight is private-helper validation, not an app-auth test.

| Domain | Row count |
| --- | ---: |
| suppliers | 135 |
| categories | 104 |
| products | 19,832 |
| prices | 41,345 |
| history | 178 |
| images | 1 |
| total | 61,595 |

`violationCount=1`; `storageViolationCount=1`; only `storageViolations.history=true`; 16 active compressed history records.

A further read-only aggregate showed all 16 belong directly to the designated shop, although the status label contains “legacy”: 15 have pglz-compressed data and overlay, and one has pglz-compressed data with no compressed overlay. Reported physical data storage sizes were 1,620–5,665 bytes. These physical sizes do not establish logical JSON size or payload safety. The deployed policy blocks active compressed history before detoasting; it does not prove that the records are corrupt.

Evidence:
- `/tmp/mobile-parity-recovery-preflight-readonly.sql`
- `/tmp/mobile-parity-recovery-preflight-readonly.json`

No row contents, actual identity IDs, tokens, or sessions are included.

## Synthetic fixtures for both clients

- `/tmp/mobile-parity-recovery-short-resource-exceeded.json`
- `/tmp/mobile-parity-recovery-short-invalid-baseline.json`
- `/tmp/mobile-parity-recovery-fixture-context.json`

These were constructed through read-only SQL SELECT using the deployed digest-contract, payload-budget and SHA-256 helpers, exactly following the early-branch JSON builder. All identity UUIDs, device identifiers, scope hashes, and event fences are synthetic. The preflight counts are the sanitized aggregate above. They are fixtures, not captured app responses.

| Fixture | Verified baseline | Event max | Canonical checkpoint digest |
| --- | --- | --- | --- |
| resource_exceeded | 0 | 42 | bfe3808361ccab15a768e9702ab0539b1a80d84717a7ac3757db623d8e62e2d4 |
| invalid_baseline | 43 | 42 | 68125a75a9d065be4a22c5f47316a231ccc124a90048c1cd56c63dbc76243c36 |

The fixture context supplies synthetic owner/shop/device identities that allow existing identity checks to pass. Changing those values without adjusting the envelope should exercise rejection tests. The synthetic scope key was generated with the canonical resolver formula; clients should continue treating it as an opaque server key.

## Client implication and boundaries

Android strict decode was in `SupabaseShopSyncReadRemoteDataSource.checkpoint` before `validateCheckpoint.status`; iOS had the equivalent ordering in `ShopSyncRecoveryContract.swift` around line 785. Their dedicated executors own code changes and verification; this investigation made no repository changes.

A strict status envelope/discriminator should:
1. Preserve response budget/raw lexical checks and request/session/local-scope freshness checks.
2. Validate schema, shop, scope kind/history kind, hash formats, account binding, device binding, required legacy-owner key, and any expected scope binding before classifying denial.
3. Recognize supported non-ready statuses as typed terminal contract outcomes. Unknown or malformed status stays a contract failure.
4. Decode the complete strict success DTO only for `ready`; never add empty domain defaults to turn denial into an empty successful catalog.
5. Preserve the recovery journal and existing local data on denial; prevent an automatic retry loop from hiding a terminal resource-policy failure.

The data-policy blocker remains after a client classification fix. Recovery cannot be called successful merely because the exception becomes typed. Any history data/policy remediation is a separate authorized backend/data decision; no decompression, migration, deletion, policy weakening, or payload-limit increase was performed or recommended as an automatic workaround.

No first-supplier page was invoked: the observed preflight is already denied, and fabricating a device/auth context would not represent the real native request. Page source compatibility is verified; page runtime behavior is not.

## Operations performed

Read-only metadata/definition queries, canonical-scope aggregate preflight, physical compression aggregate, and synthetic JSON construction through SELECT. No backend mutation, deploy, permission change, claims/session extraction, real fixture creation, or repository edit. Temporary artifacts only.
