# MOBILE_PARITY_CONTRACT_STAGING_RESULT

Date: 2026-09-28. Status: SQL_CONTRACT_REAL_STAGING_TRANSACTION_ROLLBACK PASS; app-auth/HTTP/cross-device E2E NOT_RUN.
No source/config or persistent backend changes, commits, migrations, policies, production deploys, public publication, fixture cleanup deletes, or app test launches were performed.
The SQL test uses existing Admin pgTAP practice (SET LOCAL ROLE authenticated + request.jwt.claims) with the verified TEST owner; it does not prove possession of a fresh app session.

## Sources and baseline

- Admin checkout: /Users/minxiang/Projects/merchandise-control-admin-web; HEAD fe4907adc51ff842720e1c7eb36aa05e0fa53cb8; clean at inspection.
- Supabase workspace: /Users/minxiang/Desktop/MerchandiseControlSupabase; not a Git repository. No files changed there.
- Canonical migration: supabase/migrations/20260821144753_mobile_storefront_authoring_v1.sql.
- Session/summary extension: supabase/migrations/20260821211500_mobile_storefront_authoring_session_summary.sql.
- Task: Admin docs/TASKS/TASK-152-mobile-storefront-authoring-boundary.md.
- Existing authenticated-role SQL test method: Admin supabase/tests/storefront_v1_admin_publications.sql.
- SQL artifact: contract-staging.sql.
- Sanitized measured result: contract-staging-result.json.

## Contract verified against source and live definition

1. Receipt identity is shop + principal + idempotency key (migration lines 29-40). Principal is profile or staff; product identity is inside payload and thus request hash. An intent consists of scope, operation, payload and expectedVersion.
2. Server request SHA256 is generated from jsonb {shopId, operation, payload, expectedVersion} (882-891). The client must retain the exact logical request; a modified expectedVersion also changes identity.
3. Receipt lookup precedes optimistic-version comparison (898-928 vs 943-962). Same key/request replays the original successful ACK with idempotent=true and original version/audit. It does not mean the current server record still equals that old ACK.
4. Same key with a changed valid payload or expectedVersion returns idempotency_conflict. It neither applies B nor silently reapplies A.
5. Receipt TTL is seven days (39); expired receipts are removed on a subsequent request in that principal/shop (893-896). Replay after TTL requires readback/reconciliation. Blindly replacing a key can mis-handle an unknown earlier result.
6. Authentication, product lookup and payload validation occur before receipt lookup (746-880). Revoked permission, missing product or validation failure may prevent recovering a prior ACK.
7. Terminal codes observed in source: validation_failed, permission_denied, not_found, stale_revision, invalid_state, idempotency_conflict, session_expired. Newly inserted receipts are removed for explicit stale/state/permission/constraint failures; success retains response.
8. Live staging function storefront_publication_authoring_mutate_v1 MD5 b04d614f4374b6228271b625b4159d38. Live metadata confirms lookup-before-version and TTL default seven days. Both migration ledger entries are present.
9. The session summary migration binds mutation source to authenticated session_id and expires with the token. This is audit/source binding, separate from mutation receipt TTL.

## Live TEST target and privacy

Supabase project merchandisecontrol-dev is ACTIVE_HEALTHY. Its ref and URL were matched to the repository's explicit staging allowlist/documented TEST target, not inferred from a production default.
Verified active designated TEST shop/owner:
- shop hash da11551e796819075558924ede8837ed
- owner profile hash bf727712f2b9c4c17918cba5ba8e1e7a
- membership shop_owner/active; profile active; shop active.
The shared TEST shop contains 19,755 products and was not treated as disposable.
A unique product with prefix TASK143_MOBILE_PARITY_20260928_SQL was inserted under authenticated role only inside one transaction.
Synthetic product hash: 97be03fd82ef002b46d38338b423579b.
No raw profile/shop/product UUID, token, key, email, or business row content is included.

## F03 real SQL execution

| Case | Expected / actual | Result |
|---|---|---|
| SQL-F03-01 | A save expected0 applies version1 | PASS |
| SQL-F03-02 | Discard response; retry A same key/expected0 returns identical original payload/audit, idempotent=true | PASS |
| SQL-F03-03 | B same key/expected0 returns idempotency_conflict | PASS |
| SQL-F03-04 | B new key/expected0 returns stale_revision | PASS |
| SQL-F03-05 | B new key/expected1 applies B version2 | PASS |
| SQL-F03-06 | Empty name returns validation_failed | PASS |
| SQL-F03-07 | A retry after B returns original A/version1 ACK | PASS |
| SQL-F03-08 | Independent publication read remains B/version2/draft and correlation matches B | PASS |
| SQL-F03-09 | Exactly two save success audits before timing loop | PASS |
| SQL-F03-10 | One new synthetic product only in transaction | PASS |
| SQL-F03-11 | One publication for that product | PASS |
| SQL-F03-12 | Ten additional saves and ten exact replays all succeed without duplicate version increments | PASS |

Totals: 12 PASS / 0 FAIL / 0 SKIP.
This proves deployed SQL semantics and response-discard recovery within a transaction.
It does NOT prove a physical COMMIT with lost network response, HTTP transport, mobile persisted outbox, process restart, or Android/iOS synchronization. Those gates remain separate.

## Measured timing

Server function wall-clock timings via clock_timestamp(), ten sequential calls of each kind on the synthetic fixture in one transaction. Percentiles use linear interpolation (percentile_cont equivalent).
- New draft mutation: n=10; p50 10.499 ms; p95 14.457 ms; max 15.456 ms.
- Exact replay: n=10; p50 1.032 ms; p95 1.185 ms; max 1.249 ms.
These are current server timings, not a before/after comparison and not app or network latency. Ten warm sequential samples are not a load test.
No catalog-wide benchmark was run.

## Rollback verification

Independent query after ROLLBACK:
- Products in designated shop: 19,755 (same as before).
- Synthetic products matching exact prefix: 0.
- Synthetic publications matching exact prefix: 0.
- Synthetic audit after.publicName matching exact prefix: 0.
Receipt/audit/business changes from the SQL transaction rolled back atomically; no delete cleanup ran.
No live mobile pending queue was read or changed. Transaction receipt replay was checked per record/ACK/audit, not only counts.

## Mobile configuration and remaining authentication gate

Verified without printing values:
- Android local.properties SUPABASE_URL matches designated staging; SUPABASE_PUBLISHABLE_KEY present.
- iOS iOSMerchandiseControl/SupabaseConfig.plist SUPABASE_PROJECT_URL matches staging; SUPABASE_PUBLISHABLE_KEY present.
- Supabase workspace supabase/.temp/project-ref matches staging.
- tools/agent/config.env absent.
- Runtime env MC_AGENT_CONFIG, MC_ANDROID_TASK072_SESSION_FILE, MC_IOS_TASK072D_SESSION_EXPORT_PATH, TASK072D_SESSION_EXPORT_PATH, MC_IOS_SIMULATOR_ID, MC_ANDROID_DEVICE_SERIAL unset.
- Filename-only checks of /tmp and /Users/minxiang/Projects/_codex-private found no existing TASK072/103/114 auth-session candidate in those root directories.

App auth state on dedicated devices is NOT_RUN, not inferred from setup or prior historical observations.
Minimum user action: open the installed app on each dedicated simulator; Options / Opzioni -> Accedi con Google; use the same authorized tester and complete its TEST shop selection or local-data review shown by the app. Then run the existing auth preflights and compare project/owner hashes plus active shop scope before enabling app writes.
No password/token should be pasted into chat.

## Existing harness usability

Read only: /Users/minxiang/Desktop/iOSMerchandiseControl/tools/agent/README.md, lib/common.sh, lib/supabase.sh, lib/sync.sh, lib/android.sh, lib/ios.sh.
The harness is a general mobile sync harness, separate from the suspended Excel project; no harness run was started.
- Android auth preflight: Task103AuthPreflightTest.authSessionOwnerHashWhenEnabled gated by task112AuthPreflight. Requires SignedIn; optional explicit session-file import would consume/delete that file and must not be used implicitly.
- iOS auth preflight: SupabaseConfigSecurityTests.testTask103IOSAuthPreflightWhenEnabled, requires an existing unexpired session. Prints only project/owner hashes and provider.
- mc_live_mutation_near_realtime calls iOS fixture write then Android receipt, followed by Android write then iOS receipt. It runs tests, launches apps and compares expected deltas; broad counters alone are insufficient for the requested record-by-record matrix.
- Missing config falls back to config.example.env, including device/simulator defaults. Never invoke without explicit verified dedicated destination, project, task, evidence directory, and scope.
- Existing live wrappers may target pre-existing default stores and may require app local-data binding/recovery. They were not launched against the user's original apps/devices.
- No separate runtime credentials or ready tester profile config was found; login remains the app-auth prerequisite.

## Documentation check

Supabase skill read and official changelog checked. The currently relevant source/API reference is https://supabase.com/docs/reference/javascript/rpc.
No Supabase feature implementation or dependency/schema update was performed.
