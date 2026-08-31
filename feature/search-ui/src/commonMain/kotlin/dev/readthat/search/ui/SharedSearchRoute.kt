package dev.readthat.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.readthat.client.SharedSearchUiState
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.search.domain.DiscoverCommunity
import dev.readthat.search.domain.SearchComment
import dev.readthat.search.domain.SearchCommunity
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchProfile
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType

typealias SearchImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
) -> Unit

/**
 * Canonical search presentation shared by Android and iOS.
 *
 * The host owns only lifecycle collection, navigation and image decoding. Insets are deliberately
 * not consumed here: the application shell owns safe drawing once, which avoids doubled top and
 * bottom padding on iOS while preserving Android edge-to-edge rendering.
 */
@Composable
fun SharedSearchRoute(
    state: SharedSearchUiState,
    pagedResults: LazyPagingItems<SearchItem>,
    onQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClearQuery: () -> Unit,
    onBack: () -> Unit,
    onSelectType: (SearchType) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onSelectTime: (SearchTime) -> Unit,
    onToggleSafe: () -> Unit,
    onDeleteRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onRetryAll: () -> Unit,
    onPost: (String) -> Unit,
    onComment: (postId: String, commentId: String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
    imageRenderer: SearchImageRenderer,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    fun submit(query: String) {
        focus.clearFocus()
        onSubmit(query)
    }

    Column(modifier.fillMaxSize()) {
        SearchBar(
            query = state.draftQuery,
            onQuery = onQueryChanged,
            onSubmit = { submit(state.draftQuery) },
            onClear = onClearQuery,
            onBack = onBack,
        )
        when {
            state.isSuggesting -> Suggestions(
                state = state,
                onSubmit = ::submit,
                onCommunity = onCommunity,
                onProfile = onProfile,
            )
            !state.hasResults -> Discover(
                state = state,
                onSubmit = ::submit,
                onCommunity = onCommunity,
                onDeleteRecent = onDeleteRecent,
                onClearRecent = onClearRecent,
            )
            else -> {
                SearchTabs(state.type, onSelectType)
                SearchFilters(state, onSelectSort, onSelectTime, onToggleSafe)
                if (state.type == SearchType.All) {
                    AllResults(
                        sections = state.allSections,
                        loading = state.loadingAll,
                        error = state.error,
                        retry = onRetryAll,
                        onPost = onPost,
                        onComment = onComment,
                        onCommunity = onCommunity,
                        onProfile = onProfile,
                        onTab = onSelectType,
                        imageRenderer = imageRenderer,
                    )
                } else {
                    PagedResults(
                        paged = pagedResults,
                        onPost = onPost,
                        onComment = onComment,
                        onCommunity = onCommunity,
                        onProfile = onProfile,
                        imageRenderer = imageRenderer,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search ReadThat") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                shape = RoundedCornerShape(24.dp),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun Suggestions(
    state: SharedSearchUiState,
    onSubmit: (String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
) {
    val suggestions = state.typeahead
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "search:${state.draftQuery}") {
            SuggestionRow("⌕", "Search for ${state.draftQuery.trim()}") {
                onSubmit(state.draftQuery)
            }
        }
        suggestions?.completions
            ?.filterNot { it.equals(state.draftQuery.trim(), ignoreCase = true) }
            ?.forEach { completion ->
                item(key = "completion:$completion") {
                    SuggestionRow("⌕", completion) { onSubmit(completion) }
                }
            }
        val communities = suggestions?.communities.orEmpty()
        if (communities.isNotEmpty()) {
            item { SectionTitle("Communities") }
            items(communities, key = ::searchItemKey) { item ->
                val community = item as? SearchCommunity ?: return@items
                CommunityRow(community) { onCommunity(community.name) }
            }
        }
        val profiles = suggestions?.profiles.orEmpty()
        if (profiles.isNotEmpty()) {
            item { SectionTitle("Profiles") }
            items(profiles, key = ::searchItemKey) { item ->
                val profile = item as? SearchProfile ?: return@items
                ProfileRow(profile, imageRenderer = null) { onProfile(profile.username) }
            }
        }
    }
}

@Composable
private fun Discover(
    state: SharedSearchUiState,
    onSubmit: (String) -> Unit,
    onCommunity: (String) -> Unit,
    onDeleteRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.recent.isNotEmpty()) {
            item { SectionTitle("Recent", action = "Clear", onAction = onClearRecent) }
            items(state.recent, key = { "recent:$it" }) { recent ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSubmit(recent) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◷", style = MaterialTheme.typography.titleMedium)
                    Text(recent, Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1)
                    IconButton(onClick = { onDeleteRecent(recent) }) {
                        Icon(Icons.Default.Close, "Remove $recent from recent searches")
                    }
                }
            }
        }
        if (state.discover.trending.isNotEmpty()) {
            item { SectionTitle("Trending") }
            items(state.discover.trending, key = { "trend:${it.id}" }) { trend ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSubmit(trend.query) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↗", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(trend.query, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "r/${trend.subreddit} · ${trend.score} votes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.discover.communities.isNotEmpty()) {
            item { SectionTitle("Trending Communities") }
            items(state.discover.communities, key = { "discover-community:${it.id}" }) { community ->
                DiscoverCommunityRow(community) { onCommunity(community.name) }
            }
        }
    }
}

@Composable
private fun SearchTabs(selected: SearchType, onSelect: (SearchType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchType.entries.forEach { type ->
            TextButton(onClick = { onSelect(type) }) {
                Text(
                    type.label,
                    fontWeight = if (type == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (type == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SearchFilters(
    state: SharedSearchUiState,
    onSort: (SearchSort) -> Unit,
    onTime: (SearchTime) -> Unit,
    onSafe: () -> Unit,
) {
    val sortOptions = compatibleSorts(state.type)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterMenu(state.sort.label, sortOptions, { it.label }, onSort)
        if (state.type !in setOf(SearchType.Communities, SearchType.Profiles)) {
            FilterMenu(state.time.label, SearchTime.entries, { it.label }, onTime)
            FilterChip(selected = state.safe, onClick = onSafe, label = { Text("Safe Search") })
        }
    }
}

@Composable
private fun <T> FilterMenu(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text("$label ▾") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun PagedResults(
    paged: LazyPagingItems<SearchItem>,
    onPost: (String) -> Unit,
    onComment: (String, String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
    imageRenderer: SearchImageRenderer,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(
            count = paged.itemCount,
            key = { index -> paged.peek(index)?.let(::searchItemKey) ?: "placeholder:$index" },
        ) { index ->
            paged[index]?.let { item ->
                SearchResultRow(item, onPost, onComment, onCommunity, onProfile, imageRenderer)
                HorizontalDivider()
            }
        }
        when (val append = paged.loadState.append) {
            is LoadState.Loading -> item { LoadingRow() }
            is LoadState.Error -> item {
                ErrorRow(append.error.message ?: "Could not load more", paged::retry)
            }
            else -> Unit
        }
        if (paged.loadState.refresh is LoadState.Loading && paged.itemCount == 0) {
            item { SearchSkeleton() }
        }
        val refresh = paged.loadState.refresh
        if (refresh is LoadState.Error && paged.itemCount == 0) {
            item { ErrorRow(refresh.error.message ?: "Search is unavailable", paged::retry) }
        }
        if (refresh is LoadState.NotLoading && paged.itemCount == 0) {
            item { EmptyResults() }
        }
    }
}

@Composable
private fun AllResults(
    sections: SearchSections?,
    loading: Boolean,
    error: String?,
    retry: () -> Unit,
    onPost: (String) -> Unit,
    onComment: (String, String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
    onTab: (SearchType) -> Unit,
    imageRenderer: SearchImageRenderer,
) {
    when {
        sections == null && loading -> SearchSkeleton()
        sections == null && error != null -> ErrorRow(error, retry)
        sections == null || sections.isEmpty -> EmptyResults()
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            fun section(title: String, type: SearchType, values: List<SearchItem>) {
                if (values.isEmpty()) return
                item(key = "header:${type.wire}") { SectionTitle(title, "See all") { onTab(type) } }
                items(values, key = ::searchItemKey) { item ->
                    SearchResultRow(item, onPost, onComment, onCommunity, onProfile, imageRenderer)
                    HorizontalDivider()
                }
            }
            section("Communities", SearchType.Communities, sections.communities)
            section("Posts", SearchType.Posts, sections.posts)
            section("Comments", SearchType.Comments, sections.comments)
            section("Media", SearchType.Media, sections.media)
            section("Profiles", SearchType.Profiles, sections.profiles)
            if (loading) item { LoadingRow() }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchItem,
    onPost: (String) -> Unit,
    onComment: (String, String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
    imageRenderer: SearchImageRenderer,
) {
    when (item) {
        is SearchPost -> PostRow(item, imageRenderer) { onPost(item.id) }
        is SearchComment -> CommentRow(item) { onComment(item.postId, item.id) }
        is SearchCommunity -> CommunityRow(item) { onCommunity(item.name) }
        is SearchProfile -> ProfileRow(item, imageRenderer) { onProfile(item.username) }
    }
}

@Composable
private fun PostRow(post: SearchPost, imageRenderer: SearchImageRenderer, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("r/${post.subreddit} · u/${post.author}", style = MaterialTheme.typography.labelMedium)
            Text(
                post.title,
                Modifier.padding(top = 6.dp),
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            post.body?.takeIf(String::isNotBlank)?.let { body ->
                MarkdownText(
                    markdown = body,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "${post.score} votes · ${post.commentCount} comments",
                Modifier.padding(top = 9.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        val media = post.media
        media?.thumbnailUrl?.let { url ->
            imageRenderer(
                url,
                media.cacheKey ?: "search:${post.id}",
                post.kind == "video",
                post.title,
                ContentScale.Crop,
                Modifier.padding(start = 12.dp).size(92.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
private fun CommentRow(comment: SearchComment, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text("r/${comment.post.subreddit}", style = MaterialTheme.typography.labelMedium)
        Text(comment.post.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Surface(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("u/${comment.author}", style = MaterialTheme.typography.labelMedium)
                MarkdownText(
                    markdown = comment.body,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${comment.score} votes", Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "Go to Comments",
            Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CommunityRow(community: SearchCommunity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleBadge(community.name.firstOrNull()?.uppercase() ?: "R")
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("r/${community.name}", fontWeight = FontWeight.Bold)
            Text(community.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${community.subscriberCount} members", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiscoverCommunityRow(community: DiscoverCommunity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleBadge(community.name.firstOrNull()?.uppercase() ?: "R")
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("r/${community.name}", fontWeight = FontWeight.Bold)
            Text(community.displayName)
            Text("${community.subscriberCount} members", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProfileRow(
    profile: SearchProfile,
    imageRenderer: SearchImageRenderer?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = profile.avatarUrl
        if (avatar != null && imageRenderer != null) {
            imageRenderer(
                avatar,
                "search-profile:${profile.id}",
                false,
                "${profile.displayName} avatar",
                ContentScale.Crop,
                Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            CircleBadge(profile.displayName.firstOrNull()?.uppercase() ?: "U")
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("u/${profile.username}", fontWeight = FontWeight.Bold)
            Text(profile.displayName)
            Text("${profile.karma} karma", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CircleBadge(text: String) {
    Box(
        Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SuggestionRow(symbol: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(14.dp))
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 18.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun SearchSkeleton() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(5) { index ->
            Surface(
                Modifier.fillMaxWidth().height(if (index % 2 == 0) 92.dp else 128.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) { }
        }
    }
}

@Composable
private fun EmptyResults() {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(48.dp))
        Text("No results found", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            "Try another phrase or broader filters.",
            Modifier.padding(top = 5.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorRow(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        TextButton(onClick = retry) { Text("Retry") }
    }
}

internal fun compatibleSorts(type: SearchType): List<SearchSort> = when (type) {
    SearchType.Communities, SearchType.Profiles -> listOf(SearchSort.Relevance)
    SearchType.Comments -> listOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New)
    else -> SearchSort.entries
}

internal fun searchItemKey(item: SearchItem): String = when (item) {
    is SearchPost -> "post:${item.id}"
    is SearchComment -> "comment:${item.id}"
    is SearchCommunity -> "community:${item.id}"
    is SearchProfile -> "profile:${item.id}"
}
