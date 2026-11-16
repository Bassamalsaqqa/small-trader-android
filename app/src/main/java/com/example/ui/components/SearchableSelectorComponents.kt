package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Result returned by candidate fetchers for bounded pagination.
 */
data class SelectorFetchResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

/**
 * Hoisted state for reusable inline typeahead selectors.
 *
 * Enforces:
 * 1. 250ms maximum debounce on text query changes.
 * 2. 1+ characters threshold to trigger search; below 1 char resets state.
 * 3. Immediate cancellation of older in-flight requests on new keystrokes.
 * 4. Stale discard: verifies query intent sequence, active company scope, and session generation before committing results.
 * 5. Bounded 30 page: requests capped to 30 items per page with strict cursor append deduplication.
 * 6. Keyboard navigation: Up/Down arrow highlighting, Enter selection, Escape dismissal.
 * 7. Minimum 48dp touch targets on interactive controls.
 * 8. Clear selection / clear query operations.
 * 9. Truthful loading, empty, error, and retry states.
 * 10. Selection validates current company/session before returning stable existing record.
 */
class SearchableSelectorState<T>(
    private val scope: CoroutineScope,
    val debounceMs: Long = 250L,
    private val idSelector: (T) -> String,
    private val companyScopeProvider: () -> String? = { null },
    private val sessionGenerationProvider: () -> Long = { 0L },
    private val onFetchCandidates: suspend (query: String, cursor: String?, limit: Int) -> SelectorFetchResult<T>
) {
    var query by mutableStateOf("")
        private set

    var selectedItem by mutableStateOf<T?>(null)
        private set

    var candidates by mutableStateOf<List<T>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isLoadingMore by mutableStateOf(false)
        private set

    var hasMore by mutableStateOf(false)
        private set

    var nextCursor by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isExpanded by mutableStateOf(false)
        private set

    var highlightedIndex by mutableIntStateOf(-1)
        private set

    // Internal request coordination
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    var currentIntentSeq: Long = 0L
        private set

    // Immutable provenance of the committed candidates
    var committedQuery: String? = null
        private set
    var committedCompany: String? = null
        private set
    var committedGeneration: Long = 0L
        private set

    /**
     * Handles text query modifications with max 250ms debounce, rapid keystroke cancellation,
     * immediate UI/cursor/candidate invalidation, and stale sequence/session discard.
     */
    fun onQueryChanged(newQuery: String) {
        // 1. Advance sequence FIRST before any edit or check
        val seq = ++currentIntentSeq

        // 2. Immediately cancel both in-flight jobs
        searchJob?.cancel()
        loadMoreJob?.cancel()

        // 3. Immediately clear displayed rows, page cursor, hasMore, highlightedIndex, and isLoadingMore
        query = newQuery
        highlightedIndex = -1
        errorMessage = null
        candidates = emptyList()
        nextCursor = null
        hasMore = false
        isLoadingMore = false
        committedQuery = null
        committedCompany = null
        committedGeneration = 0L

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            // Under 1 char threshold: clear loading & close dropdown
            isLoading = false
            isExpanded = false
            return
        }

        val startCompany = companyScopeProvider()
        val startGen = sessionGenerationProvider()

        searchJob = scope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            if (seq != currentIntentSeq) return@launch

            isLoading = true
            isExpanded = true

            try {
                // Request bounded strictly to 30 items
                val result = onFetchCandidates(trimmed, null, 30)

                // Stale discard validations:
                // 1. Intent sequence (discards out-of-order or superseded responses)
                if (seq != currentIntentSeq) return@launch
                // 2. Active company tenant scope
                if (companyScopeProvider() != startCompany) return@launch
                // 3. Active session generation
                if (sessionGenerationProvider() != startGen) return@launch

                // Record committed result provenance
                committedQuery = trimmed
                committedCompany = startCompany
                committedGeneration = startGen

                // Deduplicate items strictly by ID
                candidates = result.items.distinctBy(idSelector)
                nextCursor = result.nextCursor
                hasMore = result.hasMore
                errorMessage = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == currentIntentSeq) {
                    errorMessage = e.message ?: "Failed to load results"
                    candidates = emptyList()
                    hasMore = false
                    nextCursor = null
                    committedQuery = null
                    committedCompany = null
                    committedGeneration = 0L
                }
            } finally {
                // Cancelled or superseded job must NOT mutate loading state
                if (seq == currentIntentSeq) {
                    isLoading = false
                }
            }
        }
    }

    /**
     * Loads the next bounded page (30 items) using cursor pagination with strict ID deduplication.
     * Captures the exact committed query and provenance for which the cursor was issued.
     */
    fun loadMore() {
        val targetQuery = committedQuery ?: return
        val cursor = nextCursor ?: return
        if (isLoading || isLoadingMore || !hasMore) return

        val seq = currentIntentSeq
        val startCompany = committedCompany
        val startGen = committedGeneration

        loadMoreJob?.cancel()
        loadMoreJob = scope.launch {
            isLoadingMore = true
            try {
                val result = onFetchCandidates(targetQuery, cursor, 30)

                if (seq != currentIntentSeq) return@launch
                if (companyScopeProvider() != startCompany) return@launch
                if (sessionGenerationProvider() != startGen) return@launch

                // Strict deduplication when appending
                val combined = candidates + result.items
                candidates = combined.distinctBy(idSelector)
                nextCursor = result.nextCursor
                hasMore = result.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal pagination failure preserves already loaded candidates
            } finally {
                // Cancelled old finally does not flip new loading state
                if (seq == currentIntentSeq) {
                    isLoadingMore = false
                }
            }
        }
    }

    /**
     * Selects a candidate item after validating against current result provenance:
     * - Item must exist in current active candidates list
     * - Active company and session generation must match committed provenance
     *
     * @return true if selection was committed; false if rejected due to stale session or invalid provenance.
     */
    fun selectItem(item: T): Boolean {
        val currentCompany = companyScopeProvider()
        val currentGen = sessionGenerationProvider()
        if (committedCompany != null && currentCompany != committedCompany) {
            return false
        }
        if (committedGeneration != 0L && currentGen != committedGeneration) {
            return false
        }

        val itemId = idSelector(item)
        val isInCandidates = candidates.any { idSelector(it) == itemId }
        if (!isInCandidates) {
            return false
        }

        selectedItem = item
        isExpanded = false
        highlightedIndex = -1
        return true
    }

    /**
     * Invalidate entire scope and selection without typing (e.g. on company switch, logout, 401).
     */
    fun invalidateScope() {
        val seq = ++currentIntentSeq
        searchJob?.cancel()
        loadMoreJob?.cancel()
        selectedItem = null
        candidates = emptyList()
        query = ""
        nextCursor = null
        hasMore = false
        isLoading = false
        isLoadingMore = false
        errorMessage = null
        isExpanded = false
        highlightedIndex = -1
        committedQuery = null
        committedCompany = null
        committedGeneration = 0L
    }

    /**
     * Clears current selection while retaining query or resetting cleanly.
     */
    fun clearSelection() {
        selectedItem = null
    }

    /**
     * Clears query, cancels jobs, and resets candidates and dropdown.
     */
    fun clearQuery() {
        onQueryChanged("")
    }

    /**
     * Retries search with the current query.
     */
    fun retry() {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            onQueryChanged(query)
        }
    }

    fun dismiss() {
        isExpanded = false
        highlightedIndex = -1
    }

    fun expand() {
        if (candidates.isNotEmpty() || isLoading || errorMessage != null) {
            isExpanded = true
        }
    }

    fun navigateHighlight(delta: Int) {
        if (candidates.isEmpty()) return
        val count = candidates.size
        highlightedIndex = if (highlightedIndex == -1) {
            if (delta > 0) 0 else count - 1
        } else {
            (highlightedIndex + delta).coerceIn(0, count - 1)
        }
    }

    fun confirmHighlight(): Boolean {
        val targetIndex = if (highlightedIndex in candidates.indices) {
            highlightedIndex
        } else if (candidates.size == 1) {
            0
        } else {
            -1
        }
        if (targetIndex in candidates.indices) {
            return selectItem(candidates[targetIndex])
        }
        return false
    }

    fun setExplicitQuery(q: String) {
        query = q
    }

    fun setDirectCandidates(items: List<T>, hasMore: Boolean = false, nextCursor: String? = null, query: String = "") {
        val seq = ++currentIntentSeq
        searchJob?.cancel()
        loadMoreJob?.cancel()
        committedQuery = query
        committedCompany = companyScopeProvider()
        committedGeneration = sessionGenerationProvider()
        candidates = items.distinctBy(idSelector)
        this.hasMore = hasMore
        this.nextCursor = nextCursor
        this.isLoading = false
        this.isLoadingMore = false
        this.errorMessage = null
        if (items.isNotEmpty()) {
            this.isExpanded = true
        }
    }
}

/**
 * Remember helper for creating [SearchableSelectorState].
 */
@Composable
fun <T> rememberSearchableSelectorState(
    debounceMs: Long = 250L,
    idSelector: (T) -> String,
    companyScopeProvider: () -> String? = { null },
    sessionGenerationProvider: () -> Long = { 0L },
    onFetchCandidates: suspend (query: String, cursor: String?, limit: Int) -> SelectorFetchResult<T>
): SearchableSelectorState<T> {
    val scope = rememberCoroutineScope()
    return remember(scope, debounceMs) {
        SearchableSelectorState(
            scope = scope,
            debounceMs = debounceMs,
            idSelector = idSelector,
            companyScopeProvider = companyScopeProvider,
            sessionGenerationProvider = sessionGenerationProvider,
            onFetchCandidates = onFetchCandidates
        )
    }
}

/**
 * Generic inline direct typeahead field with attached expandable candidates container.
 *
 * Implements:
 * - 48dp minimum touch targets on all interactive elements.
 * - Hardware keyboard navigation: Up/Down arrow keys highlight items, Enter selects, Escape dismisses.
 * - RTL/LTR bidi text alignment.
 * - Loading indicator, truthful empty view, and error card with retry button.
 * - Direct inline presentation (no modal hop or extra click required).
 */
@Composable
fun <T> SearchableTypeaheadField(
    state: SearchableSelectorState<T>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    testTagPrefix: String = "typeahead",
    lang: AppLanguage = AppLanguage.AR,
    leadingIcon: @Composable (() -> Unit)? = null,
    onItemSelected: (T) -> Unit = {},
    itemContent: @Composable (item: T, isHighlighted: Boolean, onClick: () -> Unit) -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_container")
    ) {
        // Label if supplied
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Primary inline text field
        OutlinedTextField(
            value = state.query,
            onValueChange = { newText ->
                val filtered = newText.replace("\n", "").replace("\r", "")
                if (filtered != state.query) {
                    state.onQueryChanged(filtered)
                }
            },
            enabled = enabled,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = leadingIcon ?: {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(20.dp)
                                .testTag("${testTagPrefix}_loading")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (state.query.isNotEmpty()) {
                        IconButton(
                            onClick = { state.clearQuery() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("${testTagPrefix}_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = if (lang == AppLanguage.AR) "مسح" else "Clear",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("${testTagPrefix}_input")
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && state.candidates.isNotEmpty() && state.selectedItem == null) {
                        state.expand()
                    }
                }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                if (state.candidates.isNotEmpty()) {
                                    if (!state.isExpanded) {
                                        state.expand()
                                    }
                                    state.navigateHighlight(1)
                                }
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (event.type == KeyEventType.KeyDown) {
                                if (state.candidates.isNotEmpty()) {
                                    if (!state.isExpanded) {
                                        state.expand()
                                    }
                                    state.navigateHighlight(-1)
                                }
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val targetIndex = if (state.highlightedIndex in state.candidates.indices) {
                                    state.highlightedIndex
                                } else if (state.candidates.size == 1) {
                                    0
                                } else {
                                    -1
                                }
                                if (state.isExpanded && targetIndex in state.candidates.indices) {
                                    val item = state.candidates[targetIndex]
                                    if (state.selectItem(item)) {
                                        onItemSelected(item)
                                    }
                                }
                            }
                            true
                        }
                        Key.Escape -> {
                            if (event.type == KeyEventType.KeyDown) {
                                if (state.isExpanded) {
                                    state.dismiss()
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
        )

        // Inline attached candidates dropdown surface
        AnimatedVisibility(
            visible = state.isExpanded && (state.candidates.isNotEmpty() || state.isLoading || state.errorMessage != null || state.query.isNotBlank()),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(max = 280.dp)
                    .testTag("${testTagPrefix}_dropdown"),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Error message state with retry button
                    if (state.errorMessage != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .testTag("${testTagPrefix}_error"),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { state.retry() },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("${testTagPrefix}_retry_button")
                                ) {
                                    Text(
                                        text = if (lang == AppLanguage.AR) "إعادة المحاولة" else "Retry",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // Empty results state
                    if (!state.isLoading && state.errorMessage == null && state.candidates.isEmpty() && state.query.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("${testTagPrefix}_empty"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (lang == AppLanguage.AR) {
                                    "لا توجد نتائج مطابقة لـ «${state.query}»"
                                } else {
                                    "No matching items for «${state.query}»"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    // Candidates list
                    if (state.candidates.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            itemsIndexed(state.candidates) { index, item ->
                                val isHighlighted = (index == state.highlightedIndex)
                                itemContent(item, isHighlighted) {
                                    if (state.selectItem(item)) {
                                        onItemSelected(item)
                                        focusManager.clearFocus()
                                    }
                                }
                                if (index < state.candidates.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }

                            // Load more pagination footer
                            if (state.hasMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (state.isLoadingMore) {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
                                        } else {
                                            OutlinedButton(
                                                onClick = { state.loadMore() },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .testTag("${testTagPrefix}_load_more")
                                            ) {
                                                Text(
                                                    text = if (lang == AppLanguage.AR) "تحميل المزيد (30 صنف)" else "Load More (30 items)",
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Specialized inline product selector for [ProductCatalogItem].
 *
 * Implements:
 * - Duplicate product names disambiguation via SKU, stable ServerUlid, and unit price.
 * - Exact typed DTO decimal preservation without float conversions.
 * - Preserves base packaging unit contracts without guessing from representative barcode.
 * - RTL/LTR bidi isolation on codes, barcodes, and prices.
 */
@Composable
fun SearchableProductSelector(
    state: SearchableSelectorState<ProductCatalogItem>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "بحث بالاسم أو الباركود أو الرمز...",
    currencyCode: String = "ILS",
    lang: AppLanguage = AppLanguage.AR,
    testTagPrefix: String = "product_selector",
    onProductSelected: (ProductCatalogItem) -> Unit = {}
) {
    SearchableTypeaheadField(
        state = state,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        testTagPrefix = testTagPrefix,
        lang = lang,
        onItemSelected = onProductSelected
    ) { product, isHighlighted, onClick ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(
                    if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surface
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("${testTagPrefix}_item_${product.id.value}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Row 1: Product name + SKU pill (distinguishes duplicate names)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = "\u2066${product.sku}\u2069",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                textDirection = TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Row 2: Monospace ID prefix + Barcode + Price & available stock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\u2066ID: ${product.id.value.take(8)}\u2069" +
                                (product.barcode?.let { " • \u2066$it\u2069" } ?: ""),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFontFamily,
                            textDirection = TextDirection.Ltr
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "\u2066${product.unitPrice.toPlainString()} $currencyCode\u2069 • " +
                                "${product.availableQuantity.value.toPlainString()} ${product.baseUnit.name}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Specialized inline customer selector for [CustomerDirectoryItem].
 *
 * Implements:
 * - Duplicate customer names disambiguation via customer code, stable ServerUlid, and phone.
 * - Walk-in customer status pill.
 * - RTL/LTR bidi isolation on codes, IDs, and phone numbers.
 */
@Composable
fun SearchableCustomerSelector(
    state: SearchableSelectorState<CustomerDirectoryItem>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "بحث باسم الزبون أو الهاتف أو الرمز...",
    lang: AppLanguage = AppLanguage.AR,
    testTagPrefix: String = "customer_selector",
    onCustomerSelected: (CustomerDirectoryItem) -> Unit = {}
) {
    SearchableTypeaheadField(
        state = state,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        testTagPrefix = testTagPrefix,
        lang = lang,
        onItemSelected = onCustomerSelected
    ) { customer, isHighlighted, onClick ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(
                    if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surface
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("${testTagPrefix}_item_${customer.id.value}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Row 1: Customer name + Code pill (distinguishes duplicate names)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = "\u2066${customer.code}\u2069",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                textDirection = TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Row 2: Monospace ID prefix + Phone number + Walk-in badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\u2066ID: ${customer.id.value.take(8)}\u2069" +
                                (customer.phone?.let { " • \u2066$it\u2069" } ?: ""),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFontFamily,
                            textDirection = TextDirection.Ltr
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (customer.isWalkIn) {
                        Text(
                            text = if (lang == AppLanguage.AR) "زبون نقدي" else "Walk-in",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Adaptor for local demo product model [Product] in non-cash pickers (such as purchase bill dialogs).
 */
@Composable
fun SearchableDemoProductSelector(
    state: SearchableSelectorState<Product>,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "ابحث بالاسم أو الباركود...",
    lang: AppLanguage = AppLanguage.AR,
    testTagPrefix: String = "demo_product_selector",
    onProductSelected: (Product) -> Unit = {}
) {
    SearchableTypeaheadField(
        state = state,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        testTagPrefix = testTagPrefix,
        lang = lang,
        onItemSelected = onProductSelected
    ) { product, isHighlighted, onClick ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(
                    if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surface
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("${testTagPrefix}_item_${product.id}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = product.name(lang),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = "\u2066${product.barcode}\u2069",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = MonoFontFamily,
                                textDirection = TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\u2066ID: ${product.id}\u2069",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFontFamily,
                            textDirection = TextDirection.Ltr
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${StitchBidiUtils.formatIls(product.priceIls)} • ${product.stockCount} ${product.unit(lang)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
