# Independent review — R-A06

## R-A06 red evidence and iOS counterpart source comparison

Read-only Android red verification: `/tmp/task143-auth-restore-red.xml` contains 16 tests, 14 PASS, 2 FAIL, 0 errors, 0 SKIP. Failed cases are late stored Authenticated after the restore timeout and transient SDK RefreshFailure followed by Authenticated. Receipt states production is unchanged from main `1bf758dd8d83a771dcfdb1844a223a036ba19eff`. No patch verdict, build or test rerun by reviewer. Parent requires the final generation fence after suspension to protect **clearSession as well as publication**; stale work must never delete a replacement account session.

Counterpart iOS scope was limited to SupabaseAuthViewModel, SupabaseAuthService, client configuration and exact pinned SDK implementation. Package.resolved and the cached source checkout both identify supabase-swift 2.46.0, revision `dd29b624b9ceea87612d0b00457e1400f7d22c2e`.

**The same Android late-bootstrap omission is not present in iOS source.** SupabaseClientProvider opts into `emitLocalSessionAsInitialSession: true`. SDK AuthClient emits the stored initial session immediately and starts an asynchronous refresh if expired; its persistent authStateChanges listener remains registered. The service bridges the full stream, and SupabaseAuthViewModel.startAuthListener/apply (175–199) consumes initialSession, tokenRefreshed and signedIn events, publishing signedIn only for nonexpired info. An initially absent/expired state becoming signedOut therefore does not prevent subsequent valid tokenRefreshed from restoring the app state. There is no analogous application bootstrap timeout or filtering out all late Authenticated events. This is source evidence, not a newly executed iOS startup test.

**Uncertainty, separate race candidate only:** iOS VM applies stream events without an app generation fence. In pinned SDK LiveSessionManager, `remove()` deletes storage but does not cancel `inFlightRefreshTask`; the task stores its response and emits tokenRefreshed after its network await. SDK signOut removes/emits signedOut before awaiting the logout RPC. These source paths warrant a controlled future test if an already-in-flight refresh completes after a successful logout or a replacement session, but ordering/server response outcomes have not been reproduced here. No validated new iOS finding, no live attribution, no fix request or scope expansion is made from this candidate. A safe test would suspend a synthetic refresh response, complete local logout, release the prior response, then inspect the final app/SDK identity without real tokens.

Existing located iOS auth tests are primarily source contracts, unconfigured-view-model and owner/store flow checks; no event-stream timing result was executed or claimed. No device, session, keychain, preference or signature inspection performed; resolved iOS signing is not reopened. Await R-A06 frozen patch for the authorized scoped review.


### iOS logout candidate — requested complete callpath check

Follow-up read-only check confirms `OptionsView` calls VM.signOut, gated by canSignOut; VM calls SupabaseAuthService.signOut, which directly awaits SDK AuthClient.signOut(scope: .local). SDK reads synchronous `currentSession` (AuthClient:106 → sessionStorage.get), removes/emits signedOut, then calls APIClient.execute(logout) with its own authorization header. It does not call the asynchronous `session` getter or APIClient.authorizedExecute (the path that would await refresh). `LiveSessionManager.remove` does not await/cancel the in-flight refresh task. No hidden refresh wait was found in the complete wrapper/SDK/logout request chain.

This narrows but does not reproduce the candidate: the server/network ordering would need a previously accepted refresh response to arrive after logout completion. It is **not listed as a validated residual defect**, is not attributed to the live event, and authorizes no source change. No new iOS tests/build/device action. The source conclusion for the actual Android timeout/ignored-late-event defect remains: no equivalent omission in iOS. Review priority returns to the forthcoming R-A06 freeze.


## R-A06 — first frozen patch review, changes required

Reviewed the two-file delta against main `1bf758dd8d83a771dcfdb1844a223a036ba19eff`. Production hash `96c39e61cffd1e89615f5cbf315630b232a1ea2714a9c4a58b4c7756d45acb55` independently matched the freeze. Freeze declares test hash `2659d5ba954b2f241fba6022825ad0b9b453cad3947ddf9e876966cfec8616c9`; after authorized new-red work started during review, current test bytes no longer match the old freeze, so any final verdict must use the next manifest. No production/source change by reviewer.

**CHANGES REQUIRED — two concrete issues in the same batch:**

1. **P1: terminal SDK invalidation can leave initial bootstrap Checking forever.** In `validateStoredSession` (first-freeze lines 607–613), a refresh can set SDK NotAuthenticated and return before the observer is scheduled. The helper returns on NotAuthenticated, then its finally clears the bootstrap ticket. The observer later sees no pending ticket and app state Checking (not SignedIn), so it also does nothing. This is possible with the exact SDK clearSession path, which publishes NotAuthenticated before the refresh adapter returns. Executor independently noticed the same interleaving and parent authorized a controlled red test and minimal fix. Also test explicit intent invalidating a suspended initial Checking attempt, not only late attempts already SignedOut.

2. **P2: newly populated account after successful server refresh is incorrectly rejected as an account switch.** First-freeze line 612 compares any nonnull current user against `localUser?.id`; a null initial local user necessarily fails when a usable server user appears. Exact SDK 3.6.0 `UserSession.user` is nullable/default-null; `Auth.currentUserOrNull` reads that optional value, and `AuthImpl.refreshCurrentSession` (530–536) requires only the refresh token and imports the returned session. The prior app explicitly published `currentUser ?: localUser`, so a validated null→user transition is a supported success path. Preserve known-account A→B rejection and null→null fail-closed while restoring this positive server-validation case. Parent/executor informed for the same bounded batch.

The latch/consume-before-refresh and pre-lock explicit-intent invalidation address the original delayed bootstrap path; queued observer work checks current status under the auth mutex, and post-await fences cover both publication and explicit local cleanup. Their final closure depends on the above fixes; no premature approval.

Evidence inspection only: five XML suites in `/tmp/task143-auth-restore-targeted/` report 118 PASS / 0 FAIL / 0 errors / 0 SKIP (Auth21, scope3, binding20, recovery68, application6). These green cases do not cover the two new issues. Original red remains 14 PASS / 2 FAIL out of16. No reviewer test/build/device action. Await next frozen source plus new red/green evidence.


## R-A06 — re-review closure after the single correction batch

**Source verdict: APPROVED. Both first-review findings closed; no remaining concrete P0/P1/P2 in the scoped auth delta.** Bootstrap invalidation now settles a cancelled initial Checking state; SDK NotAuthenticated or a known A→B identity change terminates the bootstrap SignedOut before clearing its ticket and does not clear the superseding session. Successful refresh may populate an initially absent user, while final absent identity still fails closed and a previously known different account is still rejected.

Rechecked original invariants: only an armed timed-out/transient bootstrap may consume a later SDK authentication; awaiting eligibility is consumed before refresh to prevent recursive events. Explicit login/logout/shutdown invalidate before waiting for the auth mutex. Generation and current-identity checks follow refresh suspension and guard publication and local cleanup. Cancellation propagates and closes eligibility. Canonical invalid-session code classification, local-scope logout and deferred offline local-identity policy are preserved. No new auth engine, SDK configuration, business-store mutation or logging payload was added.

New freeze matches exactly (two source files only against main1bf):
- SupabaseAuthManager.kt `536f365d905bad74f0ffc38b54b26cd2d3f50300d905293432ba2a6bd7d65007`
- SupabaseAuthManagerTest.kt `023b96ab49a7825f43a2c6362d477b546334d3695176a93243ab0df81061887a`
- Recomputed git-diff SHA256 `7ca32ee3251716d3359d61905d218502d3da0dcfb717ee40a3431f2038677d47`

Independent XML reads verify review-red24 has 21 PASS/3 FAIL and review-red25 has21 PASS/4 FAIL; the additional fourth failure is null→server-user. The other failures cover initial Checking with queued SDK invalidation, explicit Google intent and SDK account change. New green XMLs total122 PASS/0 FAIL/0 errors/0 SKIP: Auth25, scope3, binding20, recovery68, application6. Log confirms targeted command success. Tests were executed by executor, not reviewer; no assertion was removed to obtain green.

Approval is for this exact frozen source. Final canonical build/lint/regression gates, signed TEST APK, exact-SHA CI and authenticated cold-restart acceptance remain separate. Reviewer changed only this `/tmp` report and ran no test/build/device action.
