package com.example.merchandisecontrolsplitview.data

import java.util.concurrent.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test

class LocalStartupTraceTest {
    private class Clock(var value: Long = 100L) {
        var calls = 0
        fun now(): Long { calls += 1; return value }
    }
    private fun trace(clock: Clock, lines: MutableList<String>, scope: String? = null) =
        LocalStartupTrace(scope, clock::now, { lines.add(it) })

    @Test fun monotonicSpanMeasuresOnlyItsOwnBoundaries() {
        val clock = Clock(10L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        clock.value = 20L; trace.begin("resolve")
        clock.value = 65L; trace.end("resolve")
        clock.value = 80L; trace.finish("ok")
        assertTrue(lines.single { "event=span_end" in it }.contains("duration_ns=45"))
        assertTrue(lines.last().contains("span_ns=resolve:45"))
        assertTrue(lines.last().contains("elapsed_ns=70"))
    }

    @Test fun regressiveInjectedClockCannotCreateNegativeDurations() {
        val clock = Clock(100L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.begin("quick_check"); clock.value = 50L; trace.end("quick_check"); trace.finish("ok")
        assertTrue(lines.single { "event=span_end" in it }.contains("duration_ns=0"))
        assertTrue(lines.last().contains("elapsed_ns=0"))
        assertFalse(lines.any { Regex("(?:elapsed|duration)_ns=-").containsMatchIn(it) })
    }

    @Test fun rowCheckpointsCountWithoutClockReadsOrLogFlood() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val initialReads = clock.calls
        repeat(10000) { trace.checkpoint("physical_compare_row"); trace.checkpoint("physical_sql_row") }
        assertEquals(initialReads, clock.calls)
        assertTrue(lines.isEmpty())
        trace.finish("ok")
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("physical_compare_row:10000"))
        assertTrue(lines.single().contains("physical_sql_row:10000"))
    }

    @Test fun physicalReadTimingAggregatesPagesWithoutTimingRows() {
        val clock = Clock(0L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        clock.value = 10L; trace.checkpoint("physical_page"); trace.checkpoint("physical_read_begin")
        repeat(100) { trace.checkpoint("physical_compare_row") }
        clock.value = 60L; trace.checkpoint("physical_read_end")
        clock.value = 70L; trace.checkpoint("physical_read_begin")
        clock.value = 100L; trace.checkpoint("physical_read_end")
        trace.finish("ok")
        assertTrue(lines.last().contains("physical_read_ns=80"))
        assertTrue(lines.last().contains("physical_page:1"))
        assertTrue(lines.last().contains("physical_compare_row:100"))
    }

    @Test fun falsePredicateRunsOnceAndPreservesCallersShortCircuit() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        var firstCalls = 0; var secondCalls = 0
        val actual = trace.fact("auth_state_changed") { firstCalls += 1; false } &&
            trace.fact("device_changed") { secondCalls += 1; true }
        assertFalse(actual); assertEquals(1, firstCalls); assertEquals(0, secondCalls)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("reason=auth_state_changed"))
    }

    @Test fun truePredicateAndInvalidDiagnosticReasonDoNotChangeResult() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        var calls = 0
        assertTrue(trace.fact("validation_failed") { calls += 1; true })
        assertFalse(trace.fact("raw-account@example.invalid") { calls += 1; false })
        assertEquals(2, calls); assertTrue(lines.isEmpty())
    }

    @Test fun predicateCancellationEscapesAsTheSameObjectWithoutLoggingItsMessage() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = CancellationException("raw-account@example.invalid")
        val observed = assertThrows(CancellationException::class.java) {
            trace.fact("validation_failed") { throw expected }
        }
        assertSame(expected, observed); assertTrue(lines.isEmpty())
    }

    @Test fun sinkExceptionCannotChangePredicateResultOrBecomeItsRetry() {
        val clock = Clock(); var writes = 0; var predicateCalls = 0
        val trace = LocalStartupTrace(null, clock::now, { writes += 1; throw IllegalStateException("sink unavailable") })
        assertFalse(trace.fact("device_changed") { predicateCalls += 1; false })
        trace.finish("failed")
        assertEquals(1, predicateCalls); assertEquals(2, writes)
    }

    @Test fun unknownStagesReasonsAndOutcomesCannotLeakArbitraryText() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val calls = clock.calls
        trace.event("raw-account@example.invalid")
        trace.event("mutex_requested", "raw-account@example.invalid")
        trace.begin("raw-scope"); trace.end("raw-scope"); trace.checkpoint("raw-row-id")
        trace.finish("raw-exception-message")
        assertEquals(calls, clock.calls); assertTrue(lines.isEmpty())
        trace.event("mutex_requested"); trace.finish("ok")
        assertFalse(lines.any { "raw-" in it || "@example" in it })
    }

    @Test fun identitiesAndDbGenerationAreHashOnlyWithDistinctLabels() {
        val clock = Clock(); val lines = mutableListOf<String>()
        val identity = "owner-raw/shop-raw"; val generation = "db-generation-raw"
        val trace = trace(clock, lines, LocalStartupTrace.hashIdentity(identity))
        trace.generation(generation); trace.finish("ok")
        assertTrue(lines.all { "scope_sha256=${LocalStartupTrace.hashIdentity(identity)}" in it })
        assertTrue(lines.last().contains("db_generation_sha256=${LocalStartupTrace.hashIdentity(generation)}"))
        assertFalse(lines.any { identity in it || generation in it || "tracker_generation" in it })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LocalStartupTrace.hashIdentity("abc"))
    }

    @Test fun invalidCallerScopeHashIsNeverLoggedAsRawIdentity() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines, "private@example.invalid")
        trace.finish("ok")
        assertTrue(lines.single().contains("scope_sha256=unknown"))
        assertFalse(lines.single().contains("private@example.invalid"))
    }

    @Test fun cancellationTerminalKeepsInFlightProofVisibleAndIsEmittedOnce() {
        val clock = Clock(0L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.begin("physical"); trace.checkpoint("physical_read_begin")
        clock.value = 25L; trace.finish("cancelled"); trace.finish("ok")
        assertEquals(1, lines.count { "event=terminal" in it })
        assertTrue(lines.last().contains("outcome=cancelled"))
        assertTrue(lines.last().contains("physical_read_inflight_ns=25"))
        assertTrue(lines.last().contains("open_spans=physical"))
    }

    @Test fun thousandPageCallbacksCannotHideCriticalPublicationAndTerminal() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val before = clock.calls
        repeat(1000) { trace.checkpoint("physical_page") }
        assertTrue(lines.isEmpty()); assertEquals(before, clock.calls)
        trace.event("scope_published", "local_read_write"); trace.finish("ok")
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("stage=scope_published"))
        assertTrue(lines.last().contains("event=terminal"))
        assertTrue(lines.last().contains("physical_page:1000"))
        assertTrue(lines.last().contains("dropped_log_lines=0"))
    }

    @Test fun repeatedBeginCannotResetAnInFlightSpan() {
        val clock = Clock(0L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.begin("receipt"); clock.value = 100L; trace.begin("receipt")
        clock.value = 150L; trace.end("receipt"); trace.finish("ok")
        assertTrue(lines.single { "event=span_end" in it }.contains("duration_ns=150"))
    }

    @Test fun completedTraceDoesNotLogButStillEvaluatesTheCallerPredicateOnce() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.finish("ok"); val clockCalls = clock.calls; var predicateCalls = 0
        trace.event("mutex_requested"); trace.begin("resolve"); trace.checkpoint("physical_page")
        assertFalse(trace.fact("device_changed") { predicateCalls += 1; false })
        assertEquals(1, predicateCalls); assertEquals(1, lines.size); assertEquals(clockCalls, clock.calls)
    }

    @Test fun traceUsesItsOwnCoroutineContextKey() {
        val trace = trace(Clock(), mutableListOf())
        assertSame(trace, (EmptyCoroutineContext + trace)[LocalStartupTrace])
    }

    @Test fun spanReturnsTheOriginalObjectAndEvaluatesOnce() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = Any(); var calls = 0
        val actual = trace.span("binding") { calls += 1; expected }
        assertSame(expected, actual); assertEquals(1, calls)
        assertTrue(lines.last().contains("event=span_end")); assertTrue(lines.last().contains("outcome=ok"))
    }

    @Test fun spanRethrowsTheOriginalFailureWithoutLoggingItsMessage() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = IllegalStateException("private-row-value")
        val actual = assertThrows(IllegalStateException::class.java) {
            trace.span("receipt") { throw expected }
        }
        assertSame(expected, actual)
        assertTrue(lines.last().contains("outcome=failed"))
        assertFalse(lines.any { "private-row-value" in it })
    }

    @Test fun spanRethrowsTheOriginalCancellationAndClosesOnlyItsOwnSpan() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = CancellationException("private-cancel-details")
        val actual = assertThrows(CancellationException::class.java) {
            trace.span("physical") { throw expected }
        }
        assertSame(expected, actual)
        assertTrue(lines.last().contains("outcome=cancelled"))
        assertFalse(lines.any { "private-cancel-details" in it })
    }

    @Test fun nullTraceSpanIsOnlyTheOriginalCallAndReturn() {
        val trace: LocalStartupTrace? = null; val expected = Any(); var calls = 0
        assertSame(expected, trace.span("resolve") { calls += 1; expected })
        assertEquals(1, calls)
        val failure = IllegalStateException("original failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            trace.span("resolve") { throw failure }
        })
    }

    @Test fun coldLaunchWhitelistCoversDispatchAndRoomWithoutClaimingAsyncAuthCompletion() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.event("app_on_create")
        trace.span("auth_restore_dispatch") { Unit }
        trace.event("room_checking")
        trace.span("room_open") { Unit }
        trace.span("business_consumers_init") { Unit }
        trace.event("room_ready"); trace.finish("ok")
        assertTrue(lines.any { "stage=app_on_create" in it })
        assertTrue(lines.any { "stage=auth_restore_dispatch" in it })
        assertTrue(lines.any { "stage=room_open" in it })
        assertTrue(lines.any { "stage=business_consumers_init" in it })
        assertTrue(lines.any { "stage=room_ready" in it })
        assertFalse(lines.any { "async_auth_completed" in it })
    }

    @Test fun monotonicTimestampUsesTheAlreadyReadBoundaryClock() {
        val clock = Clock(10L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val before = clock.calls; clock.value = 25L; trace.begin("resolve")
        assertEquals(before + 1, clock.calls)
        assertTrue(lines.single().contains("at_ns=25"))
        assertTrue(lines.single().contains("elapsed_ns=15"))
    }

    @Test fun bothMutexOwnersHaveStaticAttemptRoleMarkers() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.event("offline_scope"); trace.event("cloud_align"); trace.finish("ok")
        assertTrue(lines.any { "stage=offline_scope" in it })
        assertTrue(lines.any { "stage=cloud_align" in it })
    }

    @Test fun throwingDiagnosticClockCannotPreventTheOriginalSpanResult() {
        var clockCalls = 0; var businessCalls = 0; var logCalls = 0; val expected = Any()
        val trace = LocalStartupTrace(null, {
            clockCalls += 1
            if (clockCalls == 1) 100L else throw AssertionError("private-clock-details")
        }, { logCalls += 1 })
        val actual = trace.span("resolve") { businessCalls += 1; expected }
        trace.event("scope_resolved"); trace.generation("db-id"); trace.checkpoint("physical_read_begin")
        trace.finish("ok")
        assertSame(expected, actual); assertEquals(1, businessCalls); assertEquals(0, logCalls)
    }

    @Test fun clockFailureInSpanFinallyCannotMaskTheOriginalCancellation() {
        var throwClock = false; var businessCalls = 0; val expected = CancellationException("private-cancel")
        val trace = LocalStartupTrace(null, { if (throwClock) throw AssertionError("clock") else 100L }, {})
        val actual = assertThrows(CancellationException::class.java) {
            trace.span("physical") { businessCalls += 1; throwClock = true; throw expected }
        }
        assertSame(expected, actual); assertEquals(1, businessCalls)
    }

    @Test fun sinkAssertionErrorCannotChangeResultOrCancellationIdentity() {
        var businessCalls = 0; val expected = Any()
        val trace = LocalStartupTrace(null, { 100L }, { throw AssertionError("diagnostic sink") })
        assertSame(expected, trace.span("binding") { businessCalls += 1; expected })
        val failure = CancellationException("original cancel")
        assertSame(failure, assertThrows(CancellationException::class.java) {
            trace.span("counts") { businessCalls += 1; throw failure }
        })
        assertEquals(2, businessCalls)
    }

    @Test fun factClockFailureCannotChangeItsSingleOriginalEvaluation() {
        var failClock = false; var calls = 0
        val trace = LocalStartupTrace(null, { if (failClock) throw AssertionError("clock") else 100L }, {})
        failClock = true
        assertFalse(trace.fact("device_changed") { calls += 1; false })
        val expected = IllegalStateException("original predicate exception")
        assertSame(expected, assertThrows(IllegalStateException::class.java) {
            trace.fact("device_changed") { calls += 1; throw expected }
        })
        assertEquals(2, calls)
    }

    @Test fun recoveredDiagnosticClockMarksMissingDurationsUnknown() {
        var failClock = false; val lines = mutableListOf<String>()
        val trace = LocalStartupTrace(null, { if (failClock) throw AssertionError("clock") else 100L }, { lines.add(it) })
        failClock = true; trace.begin("resolve")
        failClock = false; trace.finish("ok")
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("diagnostic_status=INCOMPLETE_UNKNOWN"))
        assertTrue(lines.single().contains("span_ns=UNKNOWN"))
        assertTrue(lines.single().contains("physical_read_ns=UNKNOWN"))
    }

    @Test fun allObservationBodiesArePassiveWhenDiagnosticClockFails() {
        var failClock = false; val trace = LocalStartupTrace(null, {
            if (failClock) throw AssertionError("clock") else 100L
        }, {})
        trace.begin("receipt"); failClock = true
        trace.event("mutex_requested"); trace.end("receipt"); trace.generation("generation")
        trace.checkpoint("physical_read_begin"); trace.checkpoint("physical_read_end"); trace.finish("cancelled")
    }

    @Test fun phaseAndEventLogLimitStillReservesOneTerminalSlot() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        repeat(1000) { trace.event("mutex_requested") }
        trace.finish("ok")
        assertEquals(64, lines.size)
        assertTrue(lines.last().contains("event=terminal"))
        assertTrue(lines.last().contains("dropped_log_lines=937"))
    }

    @Test fun nullCapturedIdentityReportsOnlyTheCurrentGeneration() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(null, LocalAuthIdentity("private-current-user", 17L)))
        val line = lines.single(); assertTrue(line.contains("event=local_identity_mismatch"))
        for (token in listOf("captured_is_null=true", "current_is_null=false", "same_user=not_comparable",
            "captured_auth_generation=absent", "current_auth_generation=17", "same_generation=not_comparable",
            "difference=CAPTURED_NULL_CURRENT_PRESENT")) {
            assertTrue("Missing static fact $token", token in line.split(' '))
        }
        assertFalse(line.contains("private-current-user"))
    }

    @Test fun nullCurrentIdentityReportsOnlyTheCapturedGeneration() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(LocalAuthIdentity("private-captured-user", 23L), null))
        val line = lines.single(); assertTrue(line.contains("event=local_identity_mismatch"))
        for (token in listOf("captured_is_null=false", "current_is_null=true", "same_user=not_comparable",
            "captured_auth_generation=23", "current_auth_generation=absent", "same_generation=not_comparable",
            "difference=CAPTURED_PRESENT_CURRENT_NULL")) {
            assertTrue("Missing static fact $token", token in line.split(' '))
        }
        assertFalse(line.contains("private-captured-user"))
    }

    @Test fun sameUserGenerationChangeRetainsTheExactLongValues() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(LocalAuthIdentity("private-same-user", Long.MAX_VALUE - 1L),
            LocalAuthIdentity("private-same-user", Long.MAX_VALUE)))
        val line = lines.single()
        for (token in listOf("captured_is_null=false", "current_is_null=false", "same_user=true",
            "captured_auth_generation=9223372036854775806", "current_auth_generation=9223372036854775807",
            "same_generation=false", "difference=GENERATION_CHANGED")) {
            assertTrue("Missing static fact $token", token in line.split(' '))
        }
        assertFalse(line.contains("private-same-user"))
    }

    @Test fun userChangeWithSameGenerationHasItsOwnStaticClassification() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(LocalAuthIdentity("private-before-user", 31L),
            LocalAuthIdentity("private-after-user", 31L)))
        val line = lines.single()
        for (token in listOf("captured_is_null=false", "current_is_null=false", "same_user=false",
            "captured_auth_generation=31", "current_auth_generation=31", "same_generation=true",
            "difference=USER_CHANGED")) {
            assertTrue("Missing static fact $token", token in line.split(' '))
        }
        assertFalse(line.contains("private-before-user")); assertFalse(line.contains("private-after-user"))
    }

    @Test fun simultaneousUserAndGenerationChangeIsNotCollapsedToOneDifference() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(LocalAuthIdentity("private-before-user", 7L),
            LocalAuthIdentity("private-after-user", 8L)))
        val line = lines.single()
        for (token in listOf("captured_is_null=false", "current_is_null=false", "same_user=false",
            "captured_auth_generation=7", "current_auth_generation=8", "same_generation=false",
            "difference=USER_AND_GENERATION_CHANGED")) {
            assertTrue("Missing static fact $token", token in line.split(' '))
        }
    }

    @Test fun equalNullSameObjectAndStructuralCloneHaveNoDiagnosticOrClockCost() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val captured = LocalAuthIdentity("private-equal-user", 5L); val initialReads = clock.calls
        assertTrue(trace.localIdentityEquals(null, null))
        assertTrue(trace.localIdentityEquals(captured, captured))
        assertTrue(trace.localIdentityEquals(captured, LocalAuthIdentity("private-equal-user", 5L)))
        assertEquals(initialReads, clock.calls); assertTrue(lines.isEmpty())
    }

    @Test fun comparisonDiagnosticAloneDoesNotBecomeTheTerminalFirstReason() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertFalse(trace.localIdentityEquals(null, LocalAuthIdentity("private-user", 1L)))
        trace.finish("discarded")
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("event=local_identity_mismatch"))
        assertFalse(lines.any { "event=predicate_false" in it })
        assertTrue(lines.last().contains("event=terminal"))
        assertTrue("first_reason=none" in lines.last().split(' '))
    }

    @Test fun originalFactKeepsTheSingleCurrentReadAndShortCircuitsLaterPredicates() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val captured = LocalAuthIdentity("private-user", 4L)
        var currentReads = 0; var laterCalls = 0
        fun readCurrent(): LocalAuthIdentity? {
            currentReads += 1
            check(currentReads == 1) { "A second current read is not the original comparison" }
            return LocalAuthIdentity("private-user", 5L)
        }
        val actual = trace.fact("local_identity_changed") {
            trace.localIdentityEquals(captured, readCurrent())
        } && trace.fact("device_changed") { laterCalls += 1; true }
        assertFalse(actual); assertEquals(1, currentReads); assertEquals(0, laterCalls)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("event=local_identity_mismatch"))
        assertTrue(lines[1].contains("event=predicate_false"))
        assertTrue("reason=local_identity_changed" in lines[1].split(' '))
        trace.finish("discarded")
        assertTrue("first_reason=local_identity_changed" in lines.last().split(' '))
    }

    @Test fun earlierFalseFactDoesNotReadOrCompareTheLocalIdentity() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        var currentReads = 0; var laterCalls = 0
        val actual = trace.fact("auth_state_changed") { false } &&
            trace.fact("local_identity_changed") {
                currentReads += 1
                trace.localIdentityEquals(null, LocalAuthIdentity("private-current", 2L))
            } && trace.fact("device_changed") { laterCalls += 1; true }
        assertFalse(actual); assertEquals(0, currentReads); assertEquals(0, laterCalls)
        assertFalse(lines.any { "event=local_identity_mismatch" in it })
        trace.finish("discarded")
        assertTrue("first_reason=auth_state_changed" in lines.last().split(' '))
    }

    @Test fun mismatchSinkAndClockFailuresCannotAlterResultOrLeakRawUserValues() {
        val captured = LocalAuthIdentity("private-raw-captured\nowner@example.invalid", 12L)
        val current = LocalAuthIdentity("private-raw-current=secret", 13L)
        val clock = Clock(); val lines = mutableListOf<String>(); var writes = 0
        val throwingSink = LocalStartupTrace(null, clock::now, {
            writes += 1; lines.add(it); throw AssertionError("private-sink-failure")
        })
        assertFalse(throwingSink.localIdentityEquals(captured, current))
        assertEquals(1, writes); assertEquals(1, lines.size)
        assertFalse(lines.any { "private-raw" in it || "owner@example.invalid" in it ||
            "private-sink-failure" in it || "LocalAuthIdentity(" in it })
        var failClock = false; var clockReads = 0; var clockFailureWrites = 0
        val throwingClock = LocalStartupTrace(null, {
            clockReads += 1
            if (failClock) throw AssertionError("private-clock-failure") else 100L
        }, { clockFailureWrites += 1 })
        failClock = true
        assertFalse(throwingClock.localIdentityEquals(captured, current))
        val readsAfterFailure = clockReads
        assertTrue(throwingClock.localIdentityEquals(captured,
            LocalAuthIdentity(captured.userId, captured.authGeneration)))
        assertEquals(readsAfterFailure, clockReads); assertEquals(0, clockFailureWrites)
    }

    @Test fun quietCostAggregatesActualDomainsWithoutLoggingRowsAndKeepsResultIdentity() {
        val clock = Clock(100L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = Any(); var calls = 0
        assertSame(expected, trace.cost(StartupCostStage.RECEIPT_HEADER) { calls += 1; clock.value = 110L; expected })
        trace.cost(StartupCostStage.PHYSICAL_PROJECTION, ShopSyncRowDomain.PRODUCTS) { calls += 1; clock.value = 120L }
        trace.cost(StartupCostStage.PHYSICAL_PROJECTION, ShopSyncRowDomain.PRICES) { calls += 1; clock.value = 135L }
        trace.cost(StartupCostStage.PHYSICAL_PROJECTION, ShopSyncRowDomain.PRODUCTS) { calls += 1; clock.value = 140L }
        assertEquals(4, calls); assertTrue(lines.isEmpty())
        val beforeFinish = clock.calls; trace.finish("ok")
        assertEquals(beforeFinish + 1, clock.calls)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("event=cost_time_summary"))
        assertTrue(lines[0].contains("cost_ns=0.0:10,12.3:15,12.4:15"))
        assertTrue(lines[1].contains("event=cost_count_summary"))
        assertTrue(lines[1].contains("cost_count=0.0:1,12.3:2,12.4:1"))
        assertTrue(lines[2].contains("event=terminal"))
        assertEquals(1, lines.map { Regex("at_ns=([0-9]+)").find(it)!!.groupValues[1] }.distinct().size)
    }

    @Test fun cursorCostCountsTheLastFalseMoveWithExactlyTwoClocksPerOriginalCall() {
        val clock = Clock(0L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val initial = clock.calls; var moves = 0
        repeat(10000) {
            assertTrue(trace.cost(StartupCostStage.PHYSICAL_CURSOR_MOVE, ShopSyncRowDomain.PRODUCTS) {
                moves += 1; clock.value += 1L; true
            })
            trace.checkpoint("physical_compare_row")
        }
        assertFalse(trace.cost(StartupCostStage.PHYSICAL_CURSOR_MOVE, ShopSyncRowDomain.PRODUCTS) {
            moves += 1; clock.value += 1L; false
        })
        assertEquals(10001, moves); assertEquals(initial + 20002, clock.calls); assertTrue(lines.isEmpty())
        trace.finish("ok")
        assertEquals(initial + 20003, clock.calls)
        assertTrue(lines[0].contains("cost_ns=11.3:10001"))
        assertTrue(lines[1].contains("cost_count=11.3:10001"))
        assertTrue(lines[2].contains("physical_compare_row:10000"))
    }

    @Test fun failedCostClocksAreTwoDistinctBoundariesAndOnlyMakeMetadataUnknown() {
        var clockReads = 0; var businessCalls = 0; val lines = mutableListOf<String>(); val expected = Any()
        val trace = LocalStartupTrace(null, {
            clockReads += 1
            if (clockReads == 2 || clockReads == 3) throw AssertionError("private-clock-failure")
            100L
        }, { lines.add(it) })
        assertSame(expected, trace.cost(StartupCostStage.MANIFEST_FETCH, ShopSyncRowDomain.PRODUCTS) {
            businessCalls += 1; expected
        })
        assertEquals(3, clockReads); assertEquals(1, businessCalls); assertTrue(lines.isEmpty())
        trace.finish("ok")
        assertEquals(4, clockReads); assertEquals(3, lines.size)
        assertTrue(lines[0].contains("cost_ns=UNKNOWN"))
        assertTrue(lines[1].contains("cost_count=4.3:1"))
        assertTrue(lines.all { "diagnostic_status=INCOMPLETE_UNKNOWN" in it })
        assertFalse(lines.any { "private-clock-failure" in it })
    }

    @Test fun costFinallyCannotReplaceTheOriginalFailureOrCancellationWhenItsClockThrows() {
        for (expected in listOf(IllegalStateException("private-business-failure"), CancellationException("private-cancel"))) {
            var failClock = false; var reads = 0; var calls = 0; val lines = mutableListOf<String>()
            val trace = LocalStartupTrace(null, {
                reads += 1
                if (failClock) throw AssertionError("private-clock")
                100L
            }, { lines.add(it) })
            val observed = runCatching {
                trace.cost(StartupCostStage.PHYSICAL_COMPARE, ShopSyncRowDomain.HISTORY) {
                    calls += 1; failClock = true; throw expected
                }
            }.exceptionOrNull()
            assertSame(expected, observed); assertEquals(1, calls); assertEquals(3, reads)
            failClock = false; trace.finish(if (expected is CancellationException) "cancelled" else "failed")
            assertTrue(lines[0].contains("cost_ns=UNKNOWN"))
            assertTrue(lines[1].contains("cost_count=13.5:1"))
            assertFalse(lines.any { "private-" in it })
        }
    }

    @Test fun nullTraceCostRunsOnlyTheOriginalBlockOnceAndPreservesThrows() {
        val trace: LocalStartupTrace? = null; val expected = Any(); var calls = 0
        assertSame(expected, trace.cost(StartupCostStage.RECEIPT_HEADER) { calls += 1; expected })
        for (failure in listOf(IllegalStateException("original failure"), CancellationException("original cancel"))) {
            assertSame(failure, runCatching {
                trace.cost(StartupCostStage.PHYSICAL_PROJECTION, ShopSyncRowDomain.HISTORY) { calls += 1; throw failure }
            }.exceptionOrNull())
        }
        assertEquals(3, calls)
    }

    @Test fun regressiveCostClockIsUnknownInsteadOfAnInventedZeroOrNegativeDuration() {
        val clock = Clock(200L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        assertEquals(42, trace.cost(StartupCostStage.RECEIPT_RELATIONAL) { clock.value = 100L; 42 })
        trace.finish("ok")
        assertTrue(lines[0].contains("cost_ns=UNKNOWN"))
        assertTrue(lines[1].contains("cost_count=1.0:1"))
        assertTrue(lines.last().contains("diagnostic_status=INCOMPLETE_UNKNOWN"))
        assertFalse(lines.any { Regex("cost_ns=.*:-").containsMatchIn(it) })
    }

    @Test fun admissionAcceptsOnlySevenStaticCallersAndCapturesOnlyTheFirstWithoutChangingFirstReason() {
        val allowed = listOf("direct", "auth_checking_restored", "auth_signed_in_restored", "auth_refresh_unavailable",
            "shop_context_offline", "auth_refresh_ready", "shop_context_ready")
        for (reason in allowed) {
            val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
            val before = clock.calls
            trace.admission("private-owner@example.invalid\nraw-device")
            assertEquals(before, clock.calls); assertTrue(lines.isEmpty())
            trace.admission(reason)
            trace.admission(allowed[(allowed.indexOf(reason) + 1) % allowed.size])
            assertEquals(before + 1, clock.calls); assertEquals(1, lines.size)
            trace.finish("ok")
            assertEquals(2, lines.size)
            assertTrue(lines.first().contains("event=admission"))
            assertTrue(lines.all { "caller=$reason" in it.split(' ') })
            assertTrue("first_reason=none" in lines.last().split(' '))
            assertFalse(lines.any { "private-owner" in it || "raw-device" in it })
        }
    }

    @Test fun costAndAdmissionSinkFailuresCannotRetryOrHideTheAttemptedOriginalTerminal() {
        var writes = 0; var calls = 0; val lines = mutableListOf<String>(); val expected = Any()
        val trace = LocalStartupTrace(null, { 100L }, {
            writes += 1; lines.add(it); throw AssertionError("private-sink")
        })
        trace.admission("direct"); trace.admission("shop_context_ready")
        assertEquals(1, writes)
        assertSame(expected, trace.cost(StartupCostStage.MANIFEST_DIGEST) { calls += 1; expected })
        val failure = CancellationException("private-business-cancel")
        assertSame(failure, runCatching {
            trace.cost(StartupCostStage.RECEIPT_MANIFEST) { calls += 1; throw failure }
        }.exceptionOrNull())
        assertEquals(1, writes); trace.finish("cancelled"); trace.finish("ok")
        assertEquals(2, calls); assertEquals(4, writes)
        assertEquals(listOf("admission", "cost_time_summary", "cost_count_summary", "terminal"),
            lines.map { Regex("event=([a-z_]+)").find(it)!!.groupValues[1] })
        assertTrue("caller=direct" in lines.last().split(' '))
        assertFalse(lines.any { "private-" in it })
    }

    @Test fun completedTraceCostStillRunsTheOriginalBlockWithoutClocksOrAdditionalRecords() {
        val clock = Clock(); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        trace.finish("ok"); val before = clock.calls; val expected = Any(); var calls = 0
        assertSame(expected, trace.cost(StartupCostStage.PRICE_PARENT_FETCH) { calls += 1; expected })
        trace.admission("direct")
        assertEquals(1, calls); assertEquals(before, clock.calls); assertEquals(1, lines.size)
    }

    @Test fun all105CostBucketsRemainQuietAndEachSummaryHasAnUnambiguousBoundedLongFormat() {
        val clock = Clock(0L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val domains = listOf<ShopSyncRowDomain?>(null) + ShopSyncRowDomain.entries
        val before = clock.calls
        for (stage in StartupCostStage.entries) for (domain in domains) {
            clock.value = 0L
            trace.cost(stage, domain) { clock.value = Long.MAX_VALUE }
        }
        assertEquals(before + 210, clock.calls); assertTrue(lines.isEmpty())
        trace.finish("ok")
        assertEquals(3, lines.size)
        val times = lines[0].substringAfter("cost_ns=").split(',')
        val counts = lines[1].substringAfter("cost_count=").split(',')
        assertEquals(105, times.size); assertEquals(105, counts.size)
        assertEquals(105, times.map { it.substringBefore(':') }.distinct().size)
        assertTrue(times.all { it.substringAfter(':') == Long.MAX_VALUE.toString() })
        assertTrue(counts.all { it.substringAfter(':') == "1" })
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size < 3800 })
        // Any nonnegative Long count has at most19 digits; prove that whole record bound too.
        val maximumCountRecord = lines[1].replace(Regex(":1(?=,|$)"), ":${Long.MAX_VALUE}")
        assertTrue(maximumCountRecord.toByteArray(Charsets.UTF_8).size < 3800)
    }

    @Test fun costInlineKeepsAnExistingSuspendingCallAtItsOriginalSite() = kotlinx.coroutines.runBlocking {
        val clock = Clock(100L); val lines = mutableListOf<String>(); val trace = trace(clock, lines)
        val expected = Any(); var calls = 0
        val actual = trace.cost(StartupCostStage.PHYSICAL_HISTORY_FETCH, ShopSyncRowDomain.HISTORY) {
            kotlinx.coroutines.yield(); calls += 1; clock.value = 135L; expected
        }
        assertSame(expected, actual); assertEquals(1, calls); assertTrue(lines.isEmpty())
        trace.finish("ok")
        assertTrue(lines[0].contains("cost_ns=14.5:35"))
        assertTrue(lines[1].contains("cost_count=14.5:1"))
    }

}
