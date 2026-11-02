package com.disheveled.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.disheveled.bills.ui.BottomNav
import com.disheveled.bills.ui.LocalReducedMotion
import com.disheveled.bills.ui.SpendViewModel
import com.disheveled.bills.ui.Tab
import com.disheveled.bills.ui.backDestination
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.disheveled.bills.ai.AiProvider
import com.disheveled.bills.db.BillsStore
import com.disheveled.bills.model.Expense
import com.disheveled.bills.model.SavedSplit
import com.disheveled.bills.spend.indonesianName
import com.disheveled.bills.split.SplitCalculator
import com.disheveled.bills.split.SplitResult
import com.disheveled.bills.ui.BillsViewModel
import com.disheveled.bills.ui.ReceiptCapture
import com.disheveled.bills.ui.Screen
import com.disheveled.bills.ui.formatDate
import com.disheveled.bills.ui.formatMoney
import com.disheveled.bills.ui.screen.AssignScreen
import com.disheveled.bills.ui.screen.CameraScreen
import com.disheveled.bills.ui.screen.ErrorScreen
import com.disheveled.bills.ui.screen.ExpenseEditorScreen
import com.disheveled.bills.ui.screen.PeopleScreen
import com.disheveled.bills.ui.screen.RecapScreen
import com.disheveled.bills.ui.screen.ReviewScreen
import com.disheveled.bills.ui.screen.ScanningScreen
import com.disheveled.bills.ui.screen.SpendScreen
import com.disheveled.bills.ui.screen.SplitsScreen
import com.disheveled.bills.ui.screen.ShareState
import com.disheveled.bills.ui.screen.SummaryScreen
import com.disheveled.bills.ui.theme.BillsTheme

@Composable
fun App(
    aiProviders: List<AiProvider>,
    store: BillsStore,
    capture: ReceiptCapture,
    onShareText: (String) -> Unit,
    /** The system's "remove animations": presses dim instead of shrinking. */
    reducedMotion: Boolean = false,
) = CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
    BillsTheme {
        val vm: BillsViewModel = viewModel { BillsViewModel(aiProviders, store) }
        val spendVm: SpendViewModel = viewModel { SpendViewModel(aiProviders, store) }
        val state by vm.state.collectAsStateWithLifecycle()
        val spend by spendVm.state.collectAsStateWithLifecycle()
        val launcher = capture.rememberLauncher(vm.maxPhotos, vm::onPhotosPicked)

        // One handler for the whole app rather than one per screen: the destinations live in
        // a single `when`, so back and the on-screen arrow can't drift apart. Disabled on
        // Pengeluaran, which lets the system do its usual thing and leave the app. A single
        // handler is also required — binding one state to two of these throws at runtime.
        val backDestination = state.screen.backDestination(state.origin)
        val origin = state.origin.root
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            isBackEnabled = backDestination != null,
            onBackCompleted = { backDestination?.let(vm::goTo) },
        )

        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Sides only: in landscape the navigation bar and any cutout sit on one edge
                // and nothing should run under them. Top and bottom are each screen's call,
                // so Scanning can go full-bleed while the rest pad their header and footer.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            when (val screen = state.screen) {
                Screen.Spend -> TabRoot(Tab.Spend, onSelect = { vm.goTo(it.root) }) {
                    SpendScreen(
                        state = spend,
                        onPreviousMonth = spendVm::previousMonth,
                        onNextMonth = spendVm::nextMonth,
                        onOpenRecap = { vm.goTo(Screen.Recap) },
                        onOpenExpense = { expense ->
                            spendVm.edit(expense, state.savedSplits.firstOrNull { it.id == expense.splitId })
                            vm.goTo(Screen.ExpenseEditor(back = Screen.Spend))
                        },
                        onScan = { vm.goTo(Screen.Camera) },
                        onPickFromGallery = launcher::pickFromGallery,
                        onAddManual = { spendVm.startNew(); vm.goTo(Screen.ExpenseEditor(back = Screen.Spend)) },
                    )
                }

                is Screen.ExpenseEditor -> spend.draft?.let { draft ->
                    ExpenseEditorScreen(
                        draft = draft,
                        today = spend.today,
                        onClose = { vm.goTo(screen.back) },
                        onAmountChange = spendVm::setAmount,
                        onTitleChange = spendVm::setTitle,
                        onPickCategory = spendVm::pickCategory,
                        onDateChange = spendVm::setDate,
                        onNoteChange = spendVm::setNote,
                        onSave = {
                            spendVm.saveDraft()
                            // "Aku bayar sendiri" ends the scan flow on the log it just landed in.
                            if (screen.back == Screen.Review) vm.startOver(Tab.Spend) else vm.goTo(screen.back)
                        },
                        onDelete = { spendVm.deleteDraft(); vm.goTo(screen.back) },
                        onOpenSplit = { id -> vm.goTo(Screen.SavedSummary(id, back = screen)) },
                    )
                }

                Screen.Recap -> {
                    // Asks again whenever the month or its numbers change underneath.
                    LaunchedEffect(spend.report) { spendVm.loadRecapNote() }
                    RecapScreen(
                        state = spend,
                        onBack = { vm.goTo(Screen.Spend) },
                        onPreviousMonth = spendVm::previousMonth,
                        onNextMonth = spendVm::nextMonth,
                    )
                }

                Screen.Splits -> TabRoot(Tab.Splits, onSelect = { vm.goTo(it.root) }) {
                    SplitsScreen(
                        history = state.history,
                        onScan = { vm.goTo(Screen.Camera) },
                        onPickFromGallery = launcher::pickFromGallery,
                        onOpenSplit = { vm.goTo(Screen.SavedSummary(it, back = Screen.Splits)) },
                    )
                }

                // The session is remembered inside this branch, not beside the state, so the
                // camera opens on arrival and is released the moment the screen goes away.
                Screen.Camera -> CameraScreen(
                    session = capture.rememberCameraSession(vm::onPhotoTaken),
                    pages = state.pages,
                    maxPhotos = vm.maxPhotos,
                    onClose = { vm.goTo(origin) },
                    onPickFromGallery = launcher::pickFromGallery,
                    onRemovePage = vm::removePage,
                    onRead = vm::readPages,
                )

                Screen.Scanning -> ScanningScreen(
                    preview = state.capturedPreview,
                    photoCount = state.capturedCount,
                    onCancel = { vm.goTo(origin) },
                )

                Screen.Review -> state.receipt?.let { receipt ->
                    ReviewScreen(
                        receipt = receipt,
                        date = receipt.date ?: state.savedSplit?.date ?: spend.today,
                        category = state.category,
                        origin = state.origin,
                        canContinue = state.canContinueFromReview,
                        showExtractionWarning = state.fromScan,
                        onBack = { vm.goTo(origin) },
                        onPickCategory = vm::pickReceiptCategory,
                        onPaidAlone = {
                            spendVm.startFromReceipt(receipt, state.category)
                            vm.goTo(Screen.ExpenseEditor(back = Screen.Review))
                        },
                        onMerchantChange = vm::setMerchant,
                        onItemChange = vm::updateItem,
                        onAddItem = vm::addItem,
                        onRemoveItem = vm::removeItem,
                        onChargesChange = vm::setCharges,
                        onContinue = { vm.goTo(Screen.People) },
                    )
                }

                Screen.People -> state.receipt?.let { receipt ->
                    PeopleScreen(
                        receipt = receipt,
                        people = state.people,
                        suggestions = state.history
                            .flatMap { it.people }
                            .distinctBy { it.name }
                            .filter { candidate -> state.people.none { it.name == candidate.name } }
                            .take(6),
                        canContinue = state.canContinueFromPeople,
                        onBack = { vm.goTo(Screen.Review) },
                        onAddPerson = vm::addPerson,
                        onRemovePerson = vm::removePerson,
                        onContinue = { vm.goTo(Screen.Assign) },
                    )
                }

                Screen.Assign -> state.receipt?.let { receipt ->
                    AssignScreen(
                        receipt = receipt,
                        people = state.people,
                        assignments = state.assignments,
                        onBack = { vm.goTo(Screen.People) },
                        onToggle = vm::toggleAssignment,
                        onShareWithEveryone = vm::shareWithEveryone,
                        onSplitPortions = vm::splitIntoPortions,
                        onContinue = vm::computeSplit,
                    )
                }

                Screen.Summary -> state.split?.let { split ->
                    val saved = state.savedSplit
                    SummaryScreen(
                        split = split,
                        dateLabel = saved?.let { formatDate(it.date) }.orEmpty(),
                        summary = state.summary,
                        summaryLoading = state.summaryLoading,
                        shareState = shareStateOf(saved, state.currentShare, hidden = state.notInSplit),
                        knownSelf = state.selfAmong(state.people),
                        onBack = { vm.goTo(Screen.Assign) },
                        onShare = { onShareText(shareText(split, state.summary)) },
                        onDone = { vm.startOver() },
                        onLogShare = { person -> saved?.let { vm.logShare(it, person) } },
                        onNotInSplit = vm::notInThisSplit,
                        onOpenShare = {
                            state.currentShare?.let {
                                spendVm.edit(it, saved)
                                vm.goTo(Screen.ExpenseEditor(back = Screen.Summary))
                            }
                        },
                    )
                }

                is Screen.SavedSummary -> state.savedSplits.firstOrNull { it.id == screen.splitId }?.let { saved ->
                    val result = remember(saved) {
                        SplitCalculator.calculate(saved.receipt, saved.people, saved.assignments)
                    }
                    val logged = state.shares[saved.id]
                    SummaryScreen(
                        split = result,
                        dateLabel = formatDate(saved.date),
                        // The recap isn't stored; a saved split is the numbers only.
                        summary = null,
                        summaryLoading = false,
                        shareState = shareStateOf(saved, logged, hidden = false),
                        knownSelf = state.selfAmong(saved.people),
                        onBack = { vm.goTo(screen.back) },
                        onShare = { onShareText(shareText(result, null)) },
                        onDone = { vm.goTo(screen.back) },
                        onLogShare = { vm.logShare(saved, it) },
                        onNotInSplit = {},
                        onOpenShare = {
                            logged?.let {
                                spendVm.edit(it, saved)
                                vm.goTo(Screen.ExpenseEditor(back = screen))
                            }
                        },
                    )
                }

                is Screen.Failed -> ErrorScreen(
                    reason = screen.reason,
                    detail = screen.detail,
                    onRetry = vm::retryLastCapture,
                    onManualEntry = vm::startManualEntry,
                    onBack = { vm.goTo(origin) },
                )
            }
        }
    }
}

private fun shareStateOf(split: SavedSplit?, logged: Expense?, hidden: Boolean): ShareState = when {
    logged != null -> ShareState.Logged(split?.selfPersonId, logged.amount)
    hidden || split == null -> ShareState.Hidden
    else -> ShareState.Offer(split.date.month.indonesianName())
}

/** A tab root: its screen, with the bottom nav underneath. */
@Composable
private fun TabRoot(tab: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        BottomNav(current = tab, onSelect = onSelect)
    }
}

/** Plain-text recap for sharing — no markdown, since chat apps render none of it. */
internal fun shareText(split: SplitResult, summary: String?): String = buildString {
    val currency = split.receipt.currency
    appendLine("Split ${split.receipt.merchant.ifBlank { "struk" }} — ${formatMoney(split.total, currency)}")
    appendLine()
    split.shares.forEach { share ->
        appendLine("${share.person.name}: ${formatMoney(share.total, currency)}")
    }
    if (summary != null) {
        appendLine()
        appendLine(summary)
    }
}
