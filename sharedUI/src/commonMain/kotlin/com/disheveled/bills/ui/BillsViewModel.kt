package com.disheveled.bills.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.disheveled.bills.ai.AiProvider
import com.disheveled.bills.ai.FailureReason
import com.disheveled.bills.ai.ReceiptPhoto
import com.disheveled.bills.ai.ReceiptScanner
import com.disheveled.bills.ai.ScanOutcome
import com.disheveled.bills.db.BillsStore
import com.disheveled.bills.model.Category
import com.disheveled.bills.model.Expense
import com.disheveled.bills.model.ExpenseSource
import com.disheveled.bills.model.Person
import com.disheveled.bills.model.Receipt
import com.disheveled.bills.model.ReceiptItem
import com.disheveled.bills.model.SavedSplit
import com.disheveled.bills.spend.CategoryGuesser
import com.disheveled.bills.split.SplitCalculator
import com.disheveled.bills.split.SplitResult
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

internal sealed interface Screen {
    /** Pengeluaran tab: the spending log. Where the app opens. */
    data object Spend : Screen

    /** Split tab: start a split, see past ones. */
    data object Splits : Screen

    /** Our own viewfinder, live. Distinct from [Scanning], which is the model reading it. */
    data object Camera : Screen
    data object Scanning : Screen
    data object Review : Screen
    data object People : Screen
    data object Assign : Screen
    data object Summary : Screen

    /**
     * Logging or changing an entry; what's being edited lives in [SpendViewModel]. [back] is
     * where it was opened from: the tab, the review screen ("Aku bayar sendiri") or a summary.
     */
    data class ExpenseEditor(val back: Screen = Spend) : Screen
    data object Recap : Screen

    /** A split from the history, read-only, recomputed from what was saved. */
    data class SavedSummary(val splitId: String, val back: Screen) : Screen
    data class Failed(val reason: FailureReason, val detail: String?) : Screen
}

/** The two roots under the bottom nav. A flow started from one comes back to it. */
internal enum class Tab(val root: Screen) {
    Spend(Screen.Spend),
    Splits(Screen.Splits),
}

/**
 * Where the system back gesture goes from this screen, or null to let it leave the app.
 * [origin] is the tab the current flow was started from.
 *
 * The same destinations the on-screen back arrows use, on purpose: a gesture that lands
 * somewhere different from the button right above it is a bug of its own.
 */
internal fun Screen.backDestination(origin: Tab): Screen? = when (this) {
    Screen.Spend -> null
    Screen.Splits -> Screen.Spend
    Screen.Camera, Screen.Scanning, Screen.Review -> origin.root
    Screen.People -> Screen.Review
    Screen.Assign -> Screen.People
    Screen.Summary -> Screen.Assign
    is Screen.ExpenseEditor -> back
    is Screen.SavedSummary -> back
    Screen.Recap -> Screen.Spend
    is Screen.Failed -> origin.root
}

/** A saved split as the Split tab's history list shows it. */
internal data class HistoryEntry(
    val splitId: String,
    val merchant: String,
    val dateLabel: String,
    val total: Long,
    val currency: String,
    val people: List<Person>,
    /** The user's share as logged in Pengeluaran, or null when it isn't. */
    val loggedShare: Long?,
)

internal data class BillsState(
    val screen: Screen = Screen.Spend,
    /** The tab last shown, which is where a scan or split started from returns to. */
    val origin: Tab = Tab.Spend,
    val receipt: Receipt? = null,
    /** What [receipt] was for: the model's pick or a keyword guess until the user picks. */
    val category: Category = Category.Lainnya,
    val categoryPicked: Boolean = false,
    /**
     * Photos taken on the camera and not sent yet: the parts of a long receipt, top first.
     * Dropped when the camera closes.
     */
    val pages: List<CapturedReceipt> = emptyList(),
    /** The first photo being read, and how many were sent with it. */
    val capturedPreview: ImageBitmap? = null,
    val capturedCount: Int = 0,
    /** False for manual entry — there is no extraction to second-guess in that case. */
    val fromScan: Boolean = false,
    val people: List<Person> = emptyList(),
    /** item id -> people sharing it. Absent means "everyone". */
    val assignments: Map<String, Set<String>> = emptyMap(),
    val split: SplitResult? = null,
    /**
     * What [split] was last saved as. Computing again after going back to edit reuses its id,
     * so the history gets the corrected split instead of a second copy.
     */
    val savedSplit: SavedSplit? = null,
    val summary: String? = null,
    val summaryLoading: Boolean = false,
    /** The user said they weren't part of this split, so stop offering to log a share. */
    val notInSplit: Boolean = false,

    // From the store, kept across flows.
    val savedSplits: List<SavedSplit> = emptyList(),
    /** Logged shares by split id. */
    val shares: Map<String, Expense> = emptyMap(),
    val selfName: String? = null,
    val history: List<HistoryEntry> = emptyList(),
) {
    /** The share logged for the split on screen, if any. */
    val currentShare: Expense? get() = savedSplit?.let { shares[it.id] }

    /** Who the user is among [people], by the name they picked last time, ignoring case. */
    fun selfAmong(people: List<Person>): Person? {
        val name = selfName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return people.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
    }

    val canContinueFromReview: Boolean
        get() = receipt?.items?.isNotEmpty() == true

    val canContinueFromPeople: Boolean
        get() = canContinueFromReview && people.isNotEmpty()
}

internal class BillsViewModel(
    private val aiProviders: List<AiProvider>,
    private val store: BillsStore,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {

    private val scanner by lazy { ReceiptScanner(aiProviders) }

    /** The extraction in flight, so abandoning a photo can call it off. */
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(BillsState())
    val state: StateFlow<BillsState> = _state.asStateFlow()

    private var nextItemId = 0
    private var lastCapture: List<CapturedReceipt> = emptyList()

    /** Most photos one scan takes; 1 when no provider with a key reads a receipt's parts together. */
    val maxPhotos: Int get() = scanner.maxPhotos

    init {
        combine(store.splits(), store.splitShares()) { splits, shares -> splits to shares }
            .onEach { (splits, shares) ->
                _state.update {
                    it.copy(
                        savedSplits = splits,
                        shares = shares,
                        history = splits.map { split -> historyEntry(split, shares[split.id]) },
                    )
                }
            }
            .launchIn(viewModelScope)
        store.selfName()
            .onEach { name -> _state.update { it.copy(selfName = name) } }
            .launchIn(viewModelScope)
    }

    // --- scanning -------------------------------------------------------------------

    /**
     * A photo from the camera. When a scan can take more than one, it waits on the camera
     * for the next part or for [readPages]; otherwise it is read straight away, as always.
     */
    fun onPhotoTaken(photo: CapturedReceipt) {
        if (maxPhotos == 1) return scan(listOf(photo))
        _state.update { if (it.pages.size < maxPhotos) it.copy(pages = it.pages + photo) else it }
    }

    fun removePage(index: Int) = _state.update { it.copy(pages = it.pages.filterIndexed { i, _ -> i != index }) }

    fun readPages() {
        val pages = _state.value.pages
        if (pages.isNotEmpty()) scan(pages)
    }

    /** Gallery picks are a whole receipt already, so they are read straight away. */
    fun onPhotosPicked(photos: List<CapturedReceipt>) = scan(photos.take(maxPhotos))

    private fun scan(photos: List<CapturedReceipt>) {
        lastCapture = photos
        _state.update {
            it.copy(
                screen = Screen.Scanning,
                pages = emptyList(),
                capturedPreview = photos.first().preview,
                capturedCount = photos.size,
            )
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            when (val outcome = scanner.scan(photos.map { ReceiptPhoto(it.base64, it.mediaType) })) {
                is ScanOutcome.Success -> {
                    nextItemId = outcome.receipt.items.size
                    _state.update {
                        it.copy(
                            screen = Screen.Review,
                            receipt = outcome.receipt,
                            category = outcome.receipt.suggestedCategory
                                ?: CategoryGuesser.guess(outcome.receipt.merchant)
                                ?: Category.Lainnya,
                            categoryPicked = false,
                            fromScan = true,
                            assignments = emptyMap(),
                            split = null,
                            savedSplit = null,
                            summary = null,
                            notInSplit = false,
                        )
                    }
                }
                is ScanOutcome.Failure -> _state.update {
                    it.copy(screen = Screen.Failed(outcome.reason, outcome.detail))
                }
            }
        }
    }

    /** Skips extraction entirely — the way out when a receipt simply won't read. */
    fun startManualEntry() {
        nextItemId = 0
        _state.update {
            it.copy(
                screen = Screen.Review,
                fromScan = false,
                capturedPreview = null,
                receipt = Receipt(
                    merchant = "",
                    currency = "IDR",
                    items = emptyList(),
                    subtotal = 0, tax = 0, serviceCharge = 0, discount = 0, total = 0,
                ),
                category = Category.Lainnya,
                categoryPicked = false,
                assignments = emptyMap(),
                split = null,
                savedSplit = null,
                summary = null,
                notInSplit = false,
            )
        }
        addItem()
    }

    // --- review ---------------------------------------------------------------------

    fun setMerchant(name: String) {
        updateReceipt { it.copy(merchant = name) }
        // A typed-in name can still say what the bill was, unless the model or the user already did.
        _state.update { state ->
            if (state.categoryPicked || state.receipt?.suggestedCategory != null) state
            else state.copy(category = CategoryGuesser.guess(name) ?: Category.Lainnya)
        }
    }

    fun pickReceiptCategory(category: Category) =
        _state.update { it.copy(category = category, categoryPicked = true) }

    fun updateItem(id: String, name: String, qty: Int, totalPrice: Long) = updateReceipt { receipt ->
        val safeQty = qty.coerceAtLeast(1)
        receipt.copy(
            items = receipt.items.map { item ->
                if (item.id != id) item
                else item.copy(
                    name = name,
                    qty = safeQty,
                    totalPrice = totalPrice,
                    unitPrice = totalPrice / safeQty,
                )
            },
        )
    }

    fun addItem() = updateReceipt { receipt ->
        receipt.copy(
            items = receipt.items + ReceiptItem(
                id = "manual-${nextItemId++}", name = "", qty = 1, unitPrice = 0, totalPrice = 0,
            ),
        )
    }

    fun removeItem(id: String) {
        updateReceipt { receipt -> receipt.copy(items = receipt.items.filterNot { it.id == id }) }
        _state.update { it.copy(assignments = it.assignments - id) }
    }

    fun setCharges(tax: Long, serviceCharge: Long, discount: Long) = updateReceipt {
        it.copy(tax = tax, serviceCharge = serviceCharge, discount = discount)
    }

    /**
     * Keeps the derived fields honest after any edit: subtotal and total always describe
     * the items actually on screen, so the Summary reconciliation compares real numbers.
     */
    private fun updateReceipt(transform: (Receipt) -> Receipt) {
        _state.update { state ->
            val current = state.receipt ?: return@update state
            val edited = transform(current)
            val itemsTotal = edited.itemsTotal
            state.copy(
                receipt = edited.copy(
                    subtotal = itemsTotal,
                    total = itemsTotal + edited.tax + edited.serviceCharge - edited.discount,
                ),
            )
        }
    }

    // --- people ---------------------------------------------------------------------

    fun addPerson(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.update { state ->
            state.copy(people = state.people + Person(id = "p-${state.people.size}-$trimmed", name = trimmed))
        }
    }

    fun removePerson(id: String) {
        _state.update { state ->
            state.copy(
                people = state.people.filterNot { it.id == id },
                assignments = state.assignments
                    .mapValues { (_, ids) -> ids - id }
                    .filterValues { it.isNotEmpty() },
            )
        }
    }

    // --- assignment -----------------------------------------------------------------

    /**
     * An item with no entry is shared by everyone. Selecting people narrows it; clearing the
     * last person hands it back to everyone rather than leaving it paid for by nobody.
     */
    fun toggleAssignment(itemId: String, personId: String) {
        _state.update { state ->
            val current = state.assignments[itemId] ?: state.people.map { it.id }.toSet()
            val next = if (personId in current) current - personId else current + personId
            state.copy(
                assignments = when {
                    next.isEmpty() -> state.assignments - itemId
                    next.size == state.people.size -> state.assignments - itemId
                    else -> state.assignments + (itemId to next)
                },
            )
        }
    }

    fun shareWithEveryone(itemId: String) {
        _state.update { it.copy(assignments = it.assignments - itemId) }
    }

    /**
     * Breaks a multi-portion line into 1× lines so each portion can go to different people.
     * Every portion starts with whoever shared the whole line, so nothing moves on screen
     * until someone is actually reassigned.
     */
    fun splitIntoPortions(itemId: String) {
        _state.update { state ->
            val receipt = state.receipt ?: return@update state
            val item = receipt.items.find { it.id == itemId }?.takeIf { it.qty > 1 }
                ?: return@update state
            val portions = SplitCalculator.portions(item)
            val sharers = state.assignments[itemId]
            state.copy(
                receipt = receipt.copy(
                    items = receipt.items.flatMap { if (it.id == itemId) portions else listOf(it) },
                ),
                assignments = (state.assignments - itemId) +
                    (sharers?.let { ids -> portions.associate { it.id to ids } } ?: emptyMap()),
            )
        }
    }

    fun sharersOf(itemId: String): Set<String> =
        _state.value.assignments[itemId] ?: _state.value.people.map { it.id }.toSet()

    // --- navigation -----------------------------------------------------------------

    fun goTo(screen: Screen) {
        // Leaving the scanning screen means this photo has been abandoned. Without cancelling,
        // the reply still lands a few seconds later and drags the user onto Review from
        // wherever they have got to since. Safe to do here because the Scanning -> Review hop
        // is made inside the scan itself, never through this function.
        if (_state.value.screen == Screen.Scanning) scanJob?.cancel()
        val tab = Tab.entries.firstOrNull { it.root == screen }
        _state.update {
            it.copy(
                screen = screen,
                origin = tab ?: it.origin,
                // Closing the camera throws away the parts taken so far, like closing it on one.
                pages = if (screen == Screen.Camera) it.pages else emptyList(),
            )
        }
    }

    /** Re-runs extraction on the photos already taken, so a transient failure costs no retake. */
    fun retryLastCapture() {
        if (lastCapture.isNotEmpty()) scan(lastCapture)
    }

    fun computeSplit() {
        val state = _state.value
        val receipt = state.receipt ?: return
        if (state.people.isEmpty()) return

        val split = SplitCalculator.calculate(receipt, state.people, state.assignments)
        val now = Clock.System.now().toEpochMilliseconds()
        val previous = state.savedSplit
        val saved = SavedSplit(
            id = previous?.id ?: Uuid.random().toString(),
            receipt = receipt,
            // The earlier save's date, not today's, if the user is back editing after midnight.
            date = receipt.date ?: previous?.date ?: today(),
            people = state.people,
            assignments = state.assignments,
            selfPersonId = previous?.selfPersonId,
            createdAtMillis = previous?.createdAtMillis ?: now,
            updatedAtMillis = now,
            category = state.category,
        )
        _state.update {
            it.copy(
                screen = Screen.Summary,
                split = split,
                savedSplit = saved,
                summary = null,
                summaryLoading = true,
            )
        }
        viewModelScope.launch { store.saveSplit(saved) }
        state.currentShare?.let { share -> refreshShare(share, saved, split, now) }

        viewModelScope.launch {
            val recap = scanner.summarize(split)
            _state.update { it.copy(summary = recap, summaryLoading = false) }
        }
    }

    /**
     * A share already logged follows the split when it's edited in the same flow: the snapshot
     * rule protects last month's log from new maths, not from the user fixing a typo now.
     * If the user's person was removed, so is the share.
     */
    private fun refreshShare(share: Expense, saved: SavedSplit, split: SplitResult, now: Long) {
        val mine = split.shares.firstOrNull { it.person.id == saved.selfPersonId }
        viewModelScope.launch {
            if (mine == null) store.deleteExpense(share.id, atMillis = now)
            else store.saveExpense(
                share.copy(
                    title = saved.receipt.merchant.ifBlank { "Struk" },
                    amount = mine.total,
                    category = saved.category ?: share.category,
                    date = saved.date,
                    updatedAtMillis = now,
                ),
            )
        }
    }

    // --- logging a share --------------------------------------------------------------

    /**
     * Logs [person]'s share of [saved] in Pengeluaran and remembers them as the user, so the
     * next split can save in one tap.
     */
    fun logShare(saved: SavedSplit, person: Person) {
        val result = SplitCalculator.calculate(saved.receipt, saved.people, saved.assignments)
        val share = result.shares.firstOrNull { it.person.id == person.id } ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        val receipt = saved.receipt
        val expense = Expense(
            id = Uuid.random().toString(),
            title = receipt.merchant.ifBlank { "Struk" },
            amount = share.total,
            currency = receipt.currency,
            category = saved.category ?: receipt.suggestedCategory
                ?: CategoryGuesser.guess(receipt.merchant) ?: Category.Lainnya,
            suggestedCategory = receipt.suggestedCategory,
            date = saved.date,
            note = null,
            source = ExpenseSource.Split,
            splitId = saved.id,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val updated = saved.copy(selfPersonId = person.id, updatedAtMillis = now)
        _state.update { if (it.savedSplit?.id == saved.id) it.copy(savedSplit = updated) else it }
        viewModelScope.launch {
            store.saveSplit(updated)
            store.saveExpense(expense)
            store.setSelfName(person.name)
        }
    }

    fun notInThisSplit() = _state.update { it.copy(notInSplit = true) }

    /** Ends the flow on [tab], keeping what came from the store. */
    fun startOver(tab: Tab = _state.value.origin) {
        _state.update {
            BillsState(
                screen = tab.root,
                origin = tab,
                savedSplits = it.savedSplits,
                shares = it.shares,
                selfName = it.selfName,
                history = it.history,
            )
        }
    }

    override fun onCleared() {
        scanner.close()
    }
}

private fun historyEntry(split: SavedSplit, share: Expense?) = HistoryEntry(
    splitId = split.id,
    merchant = split.receipt.merchant.ifBlank { "Struk" },
    dateLabel = formatDate(split.date),
    total = SplitCalculator.calculate(split.receipt, split.people, split.assignments).total,
    currency = split.receipt.currency,
    people = split.people,
    loggedShare = share?.amount,
)
