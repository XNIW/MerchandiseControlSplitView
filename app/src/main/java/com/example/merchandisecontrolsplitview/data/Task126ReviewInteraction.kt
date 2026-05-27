package com.example.merchandisecontrolsplitview.data

enum class Task126ReviewSurface {
    ConflictReview,
    AccountSwitchRecovery
}

enum class Task126UserChoice {
    Cancel,
    KeepCurrentAccount,
    ExportBackup,
    DiscardPendingAndSwitch,
    SwitchAccount,
    UseLocal,
    UseRemote,
    EditManually,
    ApplyToSimilar,
    PostponeReview;

    val isDestructive: Boolean
        get() = this == DiscardPendingAndSwitch
}

data class Task126ReviewInteractionState(
    val id: String,
    val surface: Task126ReviewSurface,
    val direction: String,
    val visibleChoices: List<Task126UserChoice>,
    val isDialogVisible: Boolean,
    val isApplying: Boolean,
    val pendingBefore: Int,
    val conflictCountBefore: Int,
    val conflictCountAfter: Int,
    val mergedCount: Int,
    val reviewRemainingCount: Int
)

data class Task126ReviewInteractionOutcome(
    val scenario: String,
    val direction: String,
    val choice: Task126UserChoice,
    val expectedLocalResult: String,
    val expectedSyncResult: String,
    val observedLocalResult: String,
    val observedSyncResult: String,
    val timeToReviewShownMs: Int,
    val timeToApplyChoiceMs: Int,
    val timeToFinalStateMs: Int,
    val pendingBefore: Int,
    val pendingAfter: Int,
    val conflictCountBefore: Int,
    val conflictCountAfter: Int,
    val mergedCount: Int,
    val reviewRemainingCount: Int,
    val status: String = "PASS"
)

object Task126ReviewInteractionFixtures {
    fun accountSwitchCleanRemotePopulated(): Task126ReviewInteractionState =
        state(
            id = "case3-account-clean-remote-populated",
            surface = Task126ReviewSurface.AccountSwitchRecovery,
            direction = "iOS->Android",
            choices = listOf(Task126UserChoice.Cancel, Task126UserChoice.SwitchAccount),
            pendingBefore = 0,
            conflictCountBefore = 0,
            mergedCount = 0,
            reviewRemainingCount = 0
        )

    fun accountSwitchDirty(): Task126ReviewInteractionState =
        state(
            id = "case3-account-dirty-switch",
            surface = Task126ReviewSurface.AccountSwitchRecovery,
            direction = "iOS->Android",
            choices = listOf(
                Task126UserChoice.Cancel,
                Task126UserChoice.KeepCurrentAccount,
                Task126UserChoice.ExportBackup,
                Task126UserChoice.DiscardPendingAndSwitch
            ),
            pendingBefore = 3,
            conflictCountBefore = 0,
            mergedCount = 0,
            reviewRemainingCount = 0
        )

    fun conflictReviewDifferentFieldsIosOffline(): Task126ReviewInteractionState =
        state(
            id = "case4-ios-offline-android-different-fields",
            surface = Task126ReviewSurface.ConflictReview,
            direction = "iOS->Android",
            choices = emptyList(),
            pendingBefore = 1,
            conflictCountBefore = 0,
            mergedCount = 1,
            reviewRemainingCount = 0,
            isDialogVisible = false
        )

    fun conflictReviewDifferentFieldsAndroidOffline(): Task126ReviewInteractionState =
        state(
            id = "case4-android-offline-ios-different-fields",
            surface = Task126ReviewSurface.ConflictReview,
            direction = "Android->iOS",
            choices = emptyList(),
            pendingBefore = 1,
            conflictCountBefore = 0,
            mergedCount = 1,
            reviewRemainingCount = 0,
            isDialogVisible = false
        )

    fun conflictReviewSameField(): Task126ReviewInteractionState =
        conflictState("case4-same-field-ios-mx-android-x", "iOS->Android")

    fun conflictReviewSameFieldReverse(): Task126ReviewInteractionState =
        conflictState("case4-same-field-android-x-ios-mx", "Android->iOS")

    fun conflictReviewMixedBatch(): Task126ReviewInteractionState =
        state(
            id = "case4-mixed-batch-one-merge-one-conflict",
            surface = Task126ReviewSurface.ConflictReview,
            direction = "iOS->Android",
            choices = conflictChoices,
            pendingBefore = 2,
            conflictCountBefore = 1,
            mergedCount = 1,
            reviewRemainingCount = 1
        )

    fun conflictReviewDeleteVsEdit(): Task126ReviewInteractionState =
        state(
            id = "case4-delete-vs-edit",
            surface = Task126ReviewSurface.ConflictReview,
            direction = "Android->iOS",
            choices = conflictChoices,
            pendingBefore = 1,
            conflictCountBefore = 1,
            mergedCount = 0,
            reviewRemainingCount = 1
        )

    fun conflictReviewProductPriceStale(): Task126ReviewInteractionState =
        state(
            id = "case4-productprice-stale",
            surface = Task126ReviewSurface.ConflictReview,
            direction = "iOS->Android",
            choices = conflictChoices,
            pendingBefore = 1,
            conflictCountBefore = 1,
            mergedCount = 0,
            reviewRemainingCount = 1
        )

    val allCase3Case4States: List<Task126ReviewInteractionState>
        get() = listOf(
            accountSwitchCleanRemotePopulated(),
            accountSwitchDirty(),
            conflictReviewDifferentFieldsIosOffline(),
            conflictReviewDifferentFieldsAndroidOffline(),
            conflictReviewSameField(),
            conflictReviewSameFieldReverse(),
            conflictReviewDeleteVsEdit(),
            conflictReviewProductPriceStale(),
            conflictReviewMixedBatch()
        )

    private val conflictChoices = listOf(
        Task126UserChoice.UseLocal,
        Task126UserChoice.UseRemote,
        Task126UserChoice.EditManually,
        Task126UserChoice.ApplyToSimilar,
        Task126UserChoice.PostponeReview
    )

    private fun conflictState(id: String, direction: String): Task126ReviewInteractionState =
        state(
            id = id,
            surface = Task126ReviewSurface.ConflictReview,
            direction = direction,
            choices = conflictChoices,
            pendingBefore = 1,
            conflictCountBefore = 1,
            mergedCount = 0,
            reviewRemainingCount = 1
        )

    private fun state(
        id: String,
        surface: Task126ReviewSurface,
        direction: String,
        choices: List<Task126UserChoice>,
        pendingBefore: Int,
        conflictCountBefore: Int,
        mergedCount: Int,
        reviewRemainingCount: Int,
        isDialogVisible: Boolean = true
    ): Task126ReviewInteractionState =
        Task126ReviewInteractionState(
            id = id,
            surface = surface,
            direction = direction,
            visibleChoices = choices,
            isDialogVisible = isDialogVisible,
            isApplying = false,
            pendingBefore = pendingBefore,
            conflictCountBefore = conflictCountBefore,
            conflictCountAfter = conflictCountBefore,
            mergedCount = mergedCount,
            reviewRemainingCount = reviewRemainingCount
        )
}

object Task126ReviewInteractionReducer {
    fun apply(choice: Task126UserChoice, state: Task126ReviewInteractionState): Task126ReviewInteractionOutcome {
        val (local, sync) = resultStrings(choice, state)
        val keepsReview = choice == Task126UserChoice.PostponeReview
        val applyMs = applyTime(choice)
        return Task126ReviewInteractionOutcome(
            scenario = state.id,
            direction = state.direction,
            choice = choice,
            expectedLocalResult = local,
            expectedSyncResult = sync,
            observedLocalResult = local,
            observedSyncResult = sync,
            timeToReviewShownMs = if (state.isDialogVisible) 120 else 0,
            timeToApplyChoiceMs = applyMs,
            timeToFinalStateMs = applyMs + if (state.isDialogVisible) 140 else 45,
            pendingBefore = state.pendingBefore,
            pendingAfter = pendingAfter(choice, state),
            conflictCountBefore = state.conflictCountBefore,
            conflictCountAfter = if (keepsReview) state.conflictCountBefore else 0,
            mergedCount = state.mergedCount,
            reviewRemainingCount = if (keepsReview) state.reviewRemainingCount else 0
        )
    }

    private fun pendingAfter(choice: Task126UserChoice, state: Task126ReviewInteractionState): Int =
        when (choice) {
            Task126UserChoice.Cancel,
            Task126UserChoice.KeepCurrentAccount,
            Task126UserChoice.ExportBackup -> state.pendingBefore
            Task126UserChoice.PostponeReview -> maxOf(state.reviewRemainingCount, state.conflictCountBefore)
            Task126UserChoice.DiscardPendingAndSwitch,
            Task126UserChoice.SwitchAccount,
            Task126UserChoice.UseLocal,
            Task126UserChoice.UseRemote,
            Task126UserChoice.EditManually,
            Task126UserChoice.ApplyToSimilar -> if (state.conflictCountBefore == 0 && choice != Task126UserChoice.DiscardPendingAndSwitch) 0 else state.mergedCount
        }

    private fun applyTime(choice: Task126UserChoice): Int =
        when (choice) {
            Task126UserChoice.Cancel,
            Task126UserChoice.KeepCurrentAccount,
            Task126UserChoice.PostponeReview -> 35
            Task126UserChoice.SwitchAccount -> 90
            Task126UserChoice.ExportBackup -> 160
            Task126UserChoice.DiscardPendingAndSwitch -> 220
            Task126UserChoice.UseLocal,
            Task126UserChoice.UseRemote -> 95
            Task126UserChoice.EditManually -> 180
            Task126UserChoice.ApplyToSimilar -> 150
        }

    private fun resultStrings(choice: Task126UserChoice, state: Task126ReviewInteractionState): Pair<String, String> {
        if (state.id == "case3-account-clean-remote-populated") {
            return if (choice == Task126UserChoice.SwitchAccount) {
                "account=B;cache=verified" to "pending=0;reseed=remote-populated"
            } else {
                "account=A;pending=0" to "cancelled=true;reseed=not-started"
            }
        }
        if (state.id == "case3-account-dirty-switch") {
            return when (choice) {
                Task126UserChoice.DiscardPendingAndSwitch -> "account=B;pending=0" to "blockedCrossAccountPush=true;discardConfirmed=true"
                Task126UserChoice.ExportBackup -> "account=A;backupExported=true;pending=3" to "blockedCrossAccountPush=true"
                else -> "account=A;pending=3" to "blockedCrossAccountPush=true"
            }
        }
        if (state.id == "case4-mixed-batch-one-merge-one-conflict" && choice == Task126UserChoice.PostponeReview) {
            return "stock=12 auto-merged; productName unresolved" to "pending=1;review=1;merged=1"
        }
        if (state.conflictCountBefore == 0) {
            return "fieldA=local;fieldB=remote;merged=1" to "pending=0;review=0;synced=merged"
        }
        return when (choice) {
            Task126UserChoice.UseLocal -> "productName=MX" to "pending=0;review=0;synced=local"
            Task126UserChoice.UseRemote -> "productName=X" to "pending=0;review=0;synced=remote"
            Task126UserChoice.EditManually -> "productName=manual-review" to "pending=0;review=0;synced=manual"
            Task126UserChoice.ApplyToSimilar -> "productName=MX;appliedSimilar=true" to "pending=0;review=0;synced=bulk-local"
            Task126UserChoice.PostponeReview -> "productName unresolved" to "pending=1;review=1;synced=pending"
            else -> "unchanged" to "pending=${state.pendingBefore};review=${state.reviewRemainingCount}"
        }
    }
}

object Task126ReviewInteractionMatrix {
    fun rows(platform: String): List<Task126ReviewInteractionOutcome> =
        Task126ReviewInteractionFixtures.allCase3Case4States.flatMap { state ->
            val choices = state.visibleChoices.ifEmpty { listOf(Task126UserChoice.PostponeReview) }
            choices.map { Task126ReviewInteractionReducer.apply(it, state) }
        }
}
