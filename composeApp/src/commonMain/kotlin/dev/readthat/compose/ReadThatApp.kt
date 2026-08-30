package dev.readthat.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.readthat.client.AppDestination
import dev.readthat.client.CreateMode
import dev.readthat.client.FeedCard
import dev.readthat.client.ReadThatUiState
import dev.readthat.client.ReadThatViewModel
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentRow
import dev.readthat.domain.CellUi
import dev.readthat.search.domain.SearchComment
import dev.readthat.search.domain.SearchCommunity
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchProfile
import dev.readthat.shared.PostKind
import dev.readthat.shared.SessionState
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import readthat.composeapp.generated.resources.Res
import readthat.composeapp.generated.resources.readthat_logo

private val ReadThatLightColors = lightColorScheme(
    primary = Color(0xFFF04424),
    onPrimary = Color(0xFFFFF8F0),
    secondary = Color(0xFF5B6570),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF5EDE7),
    onSurface = Color(0xFF17212B),
    outline = Color(0xFF82756D),
)

private val ReadThatDarkColors = darkColorScheme(
    primary = Color(0xFFFF7658),
    onPrimary = Color(0xFF3B0900),
    secondary = Color(0xFFC4CCD3),
    background = Color(0xFF17212B),
    surface = Color(0xFF17212B),
    surfaceVariant = Color(0xFF263542),
    onSurface = Color(0xFFFFF8F0),
    outline = Color(0xFF8D9AA5),
)

@Composable
fun ReadThatApp(viewModel: ReadThatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = if (state.settings.darkTheme) ReadThatDarkColors else ReadThatLightColors) {
        when (state.session) {
            SessionState.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            SessionState.SignedOut -> AuthScreen(state, viewModel)
            is SessionState.SignedIn -> AppScaffold(state, viewModel)
        }
    }
}

@Composable
private fun BrandLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painter = painterResource(Res.drawable.readthat_logo),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun AppScaffold(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val root = state.destination in setOf(
        AppDestination.Feed, AppDestination.Search, AppDestination.Communities,
        AppDestination.Create, AppDestination.Profile,
    )
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (root) NavigationBar {
                listOf(
                    "Home" to AppDestination.Feed,
                    "Search" to AppDestination.Search,
                    "Communities" to AppDestination.Communities,
                    "Create" to AppDestination.Create,
                    "Profile" to AppDestination.Profile,
                ).forEach { (label, destination) ->
                    NavigationBarItem(
                        selected = state.destination == destination,
                        onClick = { viewModel.navigate(destination) },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val destination = state.destination) {
                AppDestination.Feed -> FeedScreen(state, viewModel)
                AppDestination.Search -> SearchScreen(state, viewModel)
                AppDestination.Communities -> CommunitiesScreen(state, viewModel)
                AppDestination.Create -> CreateScreen(state, viewModel)
                AppDestination.Profile -> ProfileScreen(state, viewModel)
                AppDestination.Settings -> SettingsScreen(state, viewModel)
                AppDestination.EditProfile -> EditProfileScreen(state, viewModel)
                is AppDestination.PostDetail -> DetailScreen(state, destination.postId, viewModel)
                is AppDestination.Community -> CommunityScreen(state, destination.name, viewModel)
                is AppDestination.Media -> MediaScreen(state, viewModel)
                is AppDestination.PublicProfile -> PublicProfileScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun AuthScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLogo(
            Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
            contentDescription = "ReadThat",
        )
        Spacer(Modifier.height(20.dp))
        Text("ReadThat", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(if (register) "Create an account" else "Sign in to keep reading")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") })
        if (register) OutlinedTextField(
            displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") },
        )
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (register) viewModel.register(username, password, displayName)
                else viewModel.login(username, password)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (register) "Create account" else "Sign in") }
        OutlinedButton(onClick = { register = !register }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "I already have an account" else "Create an account")
        }
    }
}

@Composable
private fun FeedScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandLogo(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                        contentDescription = "ReadThat",
                    )
                    Column {
                        Text("ReadThat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (state.feed.isOffline) Text("Offline · showing saved posts", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Button(onClick = viewModel::refresh) { Text("Refresh") }
            }
        }
        if (state.feed.items.isEmpty() && state.feed.isRefreshing) item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        items(state.feed.items, key = FeedCard::id) { card -> FeedCardView(card, state, viewModel) }
        item {
            if (!state.feed.endReached) Button(
                onClick = viewModel::loadMore,
                enabled = !state.feed.isLoadingMore,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (state.feed.isLoadingMore) "Loading…" else "Load more") }
        }
    }
}

@Composable
private fun FeedCardView(card: FeedCard, state: ReadThatUiState, viewModel: ReadThatViewModel) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { viewModel.navigate(AppDestination.PostDetail(card.id)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            card.cells.forEach { cell ->
                when (cell) {
                    is CellUi.Metadata -> Text(cell.line, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                    is CellUi.Title -> Text(cell.text, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleLarge)
                    is CellUi.Text -> Text(cell.body, Modifier.padding(horizontal = 16.dp), maxLines = cell.maxLines)
                    is CellUi.Media -> {
                        val poster = cell.video?.posterUrl ?: cell.sourceUrl
                        if (poster != null) SharedImage(
                            viewModel, poster, cell.cacheKey ?: card.id,
                            Modifier.fillMaxWidth().aspectRatio(cell.aspectRatio.coerceIn(.5f, 2.5f))
                                .clickable { viewModel.navigate(AppDestination.Media(card.id)) },
                            videoPreview = cell.video != null,
                        )
                    }
                    is CellUi.ImageCarousel -> cell.items.firstOrNull()?.let { image ->
                        image.sourceUrl?.let { SharedImage(
                            viewModel, it, image.cacheKey ?: card.id,
                            Modifier.fillMaxWidth().aspectRatio(image.aspectRatio.coerceIn(.5f, 2.5f)),
                        ) }
                    }
                    is CellUi.ActionBar -> Row(
                        Modifier.fillMaxWidth().padding(16.dp, 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row {
                            Text("▲ ${cell.scoreLabel}", Modifier.clickable { viewModel.vote(card.id, if (cell.viewerVote == 1) 0 else 1) })
                            Text("   ▼", Modifier.clickable { viewModel.vote(card.id, if (cell.viewerVote == -1) 0 else -1) })
                        }
                        Text("${cell.commentLabel} comments")
                    }
                    is CellUi.Announcement -> Text(cell.text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                    is CellUi.Link -> Text(cell.domain, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
                    is CellUi.AdHeader -> Text("${cell.label} · ${cell.author}", Modifier.padding(horizontal = 16.dp))
                    is CellUi.AdTitle -> Text(cell.text, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                    is CellUi.AdSummary -> Text("${cell.text}\n${cell.disclosureLabel}", Modifier.padding(16.dp))
                    is CellUi.AdActionBar -> Text("${cell.commentCount} comments", Modifier.padding(16.dp))
                    is CellUi.AdMedia -> cell.items.firstOrNull()?.let { media ->
                        val preview = media.posterUrl ?: media.imageUrl
                        if (preview != null) SharedImage(
                            viewModel, preview, media.cacheKey,
                            Modifier.fillMaxWidth().aspectRatio(media.aspectRatio.coerceIn(.5f, 2.5f)),
                        )
                        Text("${cell.ctaLabel} · ${cell.displayDomain}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    is CellUi.AdRelatedPosts -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(cell.disclosureLabel, style = MaterialTheme.typography.labelSmall)
                        cell.posts.forEach { related ->
                            Text(
                                "${related.title} · r/${related.subreddit}",
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.navigate(AppDestination.PostDetail(related.postId))
                                }.padding(vertical = 6.dp),
                            )
                        }
                    }
                    is CellUi.GroupDivider -> Unit
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(state: ReadThatUiState, postId: String, viewModel: ReadThatViewModel) {
    val post = state.detail.post
    val rows = state.detail.comments?.let(CommentFlattener::flatten)?.rows.orEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Button(onClick = viewModel::back, modifier = Modifier.padding(12.dp)) { Text("Back") } }
        if (post != null) item {
            Column(Modifier.padding(16.dp)) {
                Text(post.subreddit, style = MaterialTheme.typography.labelLarge)
                Text(post.title, style = MaterialTheme.typography.headlineSmall)
                post.body?.let { Text(it, Modifier.padding(top = 8.dp)) }
                post.media?.let { media ->
                    val image = media.posterUrl ?: media.url
                    if (image != null) SharedImage(
                        viewModel, image, media.cacheKey ?: postId,
                        Modifier.fillMaxWidth().padding(top = 12.dp).aspectRatio(media.aspectRatio.coerceIn(.5f, 2.5f)),
                        videoPreview = media.isVideo,
                    )
                    if (media.isVideo) Button(onClick = { viewModel.navigate(AppDestination.Media(postId)) }) {
                        Text("Play video")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlatformShareButton(viewModel.shareText(postId, post.title))
                }
                OutlinedTextField(
                    state.detail.reshareTarget,
                    viewModel::setReshareTarget,
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Reshare to community") },
                )
                OutlinedButton(
                    onClick = { viewModel.reshare(postId) },
                    enabled = state.detail.reshareTarget.isNotBlank() && !state.detail.resharing,
                ) { Text(if (state.detail.resharing) "Resharing…" else "Reshare") }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                state.detail.replyingToId?.let { parent ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Replying to $parent", style = MaterialTheme.typography.labelMedium)
                        Text("Cancel", Modifier.clickable { viewModel.replyTo(null) }, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(
                    state.detail.commentDraft,
                    viewModel::setCommentDraft,
                    Modifier.fillMaxWidth(),
                    label = { Text(if (state.detail.replyingToId == null) "Add a comment" else "Write a reply") },
                    minLines = 2,
                )
                Button(
                    onClick = { viewModel.submitComment(postId) },
                    enabled = state.detail.commentDraft.isNotBlank() && !state.detail.submittingComment,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (state.detail.submittingComment) "Posting…" else "Post comment") }
            }
        }
        items(rows, key = CommentRow::key) { row ->
            when (row) {
                is CommentRow.Comment -> Column(
                    Modifier.fillMaxWidth().padding(start = (12 + row.renderDepth * 12).dp, end = 12.dp, top = 8.dp),
                ) {
                    Text(row.authorDisplayName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Text(row.body)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "▲ ${row.scoreLabel}",
                            Modifier.clickable {
                                viewModel.voteComment(postId, row.key, if (row.viewerVote == 1) 0 else 1)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "▼",
                            Modifier.clickable {
                                viewModel.voteComment(postId, row.key, if (row.viewerVote == -1) 0 else -1)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(row.ageLabel, style = MaterialTheme.typography.labelSmall)
                        Text(
                            "Reply", Modifier.clickable { viewModel.replyTo(row.key) },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
                is CommentRow.LoadMore -> Text(
                    row.label,
                    Modifier.padding(start = (12 + row.renderDepth * 12).dp, top = 16.dp, bottom = 16.dp)
                        .clickable { viewModel.loadMoreComments(postId, row.key) },
                    color = MaterialTheme.colorScheme.primary,
                )
                is CommentRow.ContinueThread -> Text(
                    "Continue thread",
                    Modifier.padding(16.dp).clickable { viewModel.loadMoreComments(postId, row.key) },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MediaScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val items = state.mediaFeed.items
    val anchor = state.mediaFeed.anchorPostId
    val initial = remember(items, anchor) { items.indexOfFirst { it.postId == anchor }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initial) { items.size }
    LaunchedEffect(pager.currentPage, items.size) {
        if (items.isNotEmpty() && pager.currentPage >= items.lastIndex - 2) viewModel.appendMediaFeed()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.mediaFeed.loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            items.isEmpty() -> Text(
                state.mediaFeed.error ?: "No media available",
                Modifier.align(Alignment.Center), color = Color.White,
            )
            else -> VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                val media = item.media.toPostMedia()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (media.isVideo) {
                        media.posterUrl?.let { poster -> SharedImage(
                            viewModel, poster, media.cacheKey ?: item.postId,
                            Modifier.fillMaxWidth().aspectRatio(media.aspectRatio.coerceIn(.5f, 2.5f)),
                            videoPreview = true,
                        ) }
                        PlatformVideoPlayer(
                            media,
                            state.settings.copy(autoplayVideo = state.settings.autoplayVideo && pager.currentPage == page),
                            Modifier.fillMaxWidth().aspectRatio(media.aspectRatio.coerceIn(.5f, 2.5f)),
                        )
                    } else {
                        val url = media.zoomUrl ?: media.url ?: media.posterUrl
                        if (url != null) SharedImage(
                            viewModel, url, media.cacheKey ?: item.postId,
                            Modifier.fillMaxWidth().aspectRatio(media.aspectRatio.coerceIn(.5f, 2.5f)),
                        )
                    }
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
                    ) {
                        Text("r/${item.subreddit} · u/${item.author}", color = Color.White)
                        Text(item.title, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("▲ ${item.score}   ${item.commentCount} comments", color = Color.White)
                    }
                }
            }
        }
        Button(onClick = viewModel::back, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("Back") }
    }
}

@Composable
private fun SearchScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            state.search.query, viewModel::search, Modifier.fillMaxWidth(),
            label = { Text("Posts, communities, comments, profiles") },
        )
        if (state.search.searching) CircularProgressIndicator(Modifier.padding(12.dp))
        LazyColumn { items(state.search.results, key = SearchItem::id) { SearchResult(it, viewModel) } }
    }
}

@Composable
private fun SearchResult(item: SearchItem, viewModel: ReadThatViewModel) {
    Column(Modifier.fillMaxWidth().clickable { viewModel.openSearchResult(item) }.padding(vertical = 10.dp)) {
        Text(when (item) {
            is SearchPost -> item.title
            is SearchCommunity -> "r/${item.name} · ${item.displayName}"
            is SearchComment -> item.body
            is SearchProfile -> "u/${item.username} · ${item.displayName}"
        }, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CommunitiesScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item { Text("Communities", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Discover communities; details, rules, membership, and feeds remain available offline.") }
        items(state.communities.discover.communities, key = { it.id }) { community ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    viewModel.navigate(AppDestination.Community(community.name))
                }.padding(vertical = 12.dp),
            ) {
                Text("r/${community.name}", fontWeight = FontWeight.Bold)
                Text("${community.displayName} · ${community.subscriberCount} members")
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
        }
        item {
            OutlinedButton(onClick = { viewModel.setCreateMode(CreateMode.Community); viewModel.navigate(AppDestination.Create) }) {
                Text("Start a community")
            }
        }
    }
}

@Composable
private fun CreateScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val creation = state.create
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item {
            Text("Create", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creation.mode == CreateMode.Post) Button(onClick = {}) { Text("Post") }
                else OutlinedButton(onClick = { viewModel.setCreateMode(CreateMode.Post) }) { Text("Post") }
                if (creation.mode == CreateMode.Community) Button(onClick = {}) { Text("Community") }
                else OutlinedButton(onClick = { viewModel.setCreateMode(CreateMode.Community) }) { Text("Community") }
            }
        }
        if (creation.mode == CreateMode.Post) {
            item {
                val draft = creation.post
                OutlinedTextField(draft.subreddit, viewModel::setPostCommunity, Modifier.fillMaxWidth(), label = { Text("Community") })
                OutlinedTextField(draft.title, viewModel::setPostTitle, Modifier.fillMaxWidth(), label = { Text("Title") })
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PostKind.Text, PostKind.Link, PostKind.Image, PostKind.Video)
                        .chunked(2)
                        .forEach { kinds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                kinds.forEach { kind ->
                                    if (draft.kind == kind) Button(onClick = {}) { Text(kind.name) }
                                    else OutlinedButton(onClick = { viewModel.setPostKind(kind) }) { Text(kind.name) }
                                }
                            }
                        }
                }
                when (draft.kind) {
                    PostKind.Text -> OutlinedTextField(
                        draft.body, viewModel::setPostBody, Modifier.fillMaxWidth(), label = { Text("Body") }, minLines = 5,
                    )
                    PostKind.Link -> OutlinedTextField(
                        draft.linkUrl, viewModel::setPostLink, Modifier.fillMaxWidth(), label = { Text("https://…") },
                    )
                    PostKind.Image, PostKind.Video -> {
                        OutlinedTextField(
                            draft.body, viewModel::setPostBody, Modifier.fillMaxWidth(),
                            label = { Text("Caption (optional)") }, minLines = 2,
                        )
                        PlatformMediaPickerButton(
                            draft.kind,
                            enabled = !creation.submitting,
                            onPicked = viewModel::addPickedMedia,
                            onError = viewModel::reportCreateError,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        draft.localMediaItems.forEachIndexed { index, media ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${media.name} · ${media.byteSize / 1_048_576.0} MB", maxLines = 1)
                                Text(
                                    "Remove", Modifier.clickable { viewModel.removePickedMedia(index) },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                Text("Draft media is staged privately; all post types queue durably in Room when offline.", style = MaterialTheme.typography.bodySmall)
                creation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = viewModel::submitCreate,
                    enabled = draft.canSubmit && !creation.submitting,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text(if (creation.submitting) "Publishing…" else "Publish") }
            }
        } else {
            item {
                val draft = creation.community
                OutlinedTextField(draft.name, viewModel::setCommunityName, Modifier.fillMaxWidth(), label = { Text("Name") })
                OutlinedTextField(
                    draft.displayName, viewModel::setCommunityDisplayName, Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                )
                OutlinedTextField(
                    draft.description, viewModel::setCommunityDescription, Modifier.fillMaxWidth(),
                    label = { Text("Description") }, minLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("public", "restricted", "private").forEach { access ->
                        if (draft.accessType == access) Button(onClick = {}) { Text(access) }
                        else OutlinedButton(onClick = { viewModel.setCommunityAccess(access) }) { Text(access) }
                    }
                }
                creation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = viewModel::submitCreate,
                    enabled = draft.canSubmit && !creation.submitting,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text(if (creation.submitting) "Creating…" else "Create community") }
            }
        }
    }
}

@Composable
private fun CommunityScreen(state: ReadThatUiState, name: String, viewModel: ReadThatViewModel) {
    val community = state.communities.detail
    LazyColumn(Modifier.fillMaxSize()) {
        item { Button(onClick = viewModel::back, modifier = Modifier.padding(12.dp)) { Text("Back") } }
        if (community != null && community.name.equals(name.removePrefix("r/"), true)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("r/${community.name}", style = MaterialTheme.typography.headlineMedium)
                    Text(community.displayName, fontWeight = FontWeight.Bold)
                    Text("${community.subscriberCount} members · ${community.accessType}")
                    Text(community.description, Modifier.padding(vertical = 8.dp))
                    Button(
                        onClick = { viewModel.setCommunityJoined(!community.isJoined) },
                        enabled = community.canChangeMembership && !state.communities.membershipChanging,
                    ) { Text(if (community.isJoined) "Joined" else "Join") }
                    community.rules.forEachIndexed { index, rule ->
                        Text("${index + 1}. ${rule.title}", fontWeight = FontWeight.SemiBold)
                        if (rule.description.isNotBlank()) Text(rule.description)
                    }
                    if (state.communities.isOffline) Text("Offline · showing saved community", color = MaterialTheme.colorScheme.tertiary)
                }
            }
            items(state.communities.posts, key = FeedCard::id) { card -> FeedCardView(card, state, viewModel) }
        } else if (state.communities.loading) {
            item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            item { Text(state.communities.error ?: "Community unavailable", Modifier.padding(20.dp)) }
        }
        item {
            OutlinedButton(onClick = viewModel::refreshCommunity, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val user = (state.session as? SessionState.SignedIn)?.user ?: return
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
        Text(user.displayName, style = MaterialTheme.typography.headlineMedium)
        Text("u/${user.username} · ${user.karma} karma")
        Text(user.bio, Modifier.padding(vertical = 12.dp))
        Button(onClick = { viewModel.navigate(AppDestination.EditProfile) }) { Text("Edit profile") }
        Button(onClick = { viewModel.navigate(AppDestination.Settings) }) { Text("Settings") }
        OutlinedButton(onClick = viewModel::logout) { Text("Sign out") }
    }
}

@Composable
private fun EditProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val editor = state.profile
    LazyColumn(Modifier.fillMaxSize().padding(20.dp)) {
        item { Button(onClick = viewModel::back) { Text("Back") } }
        item { Text("Edit profile", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedTextField(
                editor.displayName, viewModel::setProfileDisplayName, Modifier.fillMaxWidth(),
                label = { Text("Display name") },
            )
            OutlinedTextField(
                editor.bio, viewModel::setProfileBio, Modifier.fillMaxWidth(),
                label = { Text("Bio") }, minLines = 4,
            )
            PlatformMediaPickerButton(
                PostKind.Image,
                enabled = !editor.saving,
                onPicked = viewModel::setProfileAvatar,
                onError = viewModel::reportProfileError,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            editor.avatar?.let { media ->
                Text("New photo: ${media.name}")
                Text(
                    "Remove selection", Modifier.clickable { viewModel.removeProfileAvatar() },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = viewModel::removeProfileAvatar) { Text("Remove current photo") }
            editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = viewModel::saveProfile,
                enabled = !editor.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text(if (editor.saving) "Saving…" else "Save") }
        }
    }
}

@Composable
private fun PublicProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val user = state.profile.publicProfile
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Button(onClick = viewModel::back) { Text("Back") }
        when {
            state.profile.loading -> CircularProgressIndicator()
            user != null -> {
                user.avatarUrl?.let { SharedImage(
                    viewModel, it, "avatar:${user.id}:${user.updatedAt}",
                    Modifier.size(88.dp).background(Color.Gray, CircleShape),
                ) }
                Text(user.displayName, style = MaterialTheme.typography.headlineMedium)
                Text("u/${user.username} · ${user.karma} karma")
                Text(user.bio, Modifier.padding(vertical = 12.dp))
            }
            else -> Text(state.profile.error ?: "Profile unavailable")
        }
    }
}

@Composable
private fun SettingsScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Button(onClick = viewModel::back) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SettingSwitch("Dark theme", state.settings.darkTheme, viewModel::setDarkTheme)
        SettingSwitch("Autoplay video", state.settings.autoplayVideo, viewModel::setAutoplay)
        Text("Media remains readable from cache offline; large video downloads use each platform's native HLS asset downloader.")
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label); Switch(checked, onChecked) }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SharedImage(
    viewModel: ReadThatViewModel,
    url: String,
    cacheKey: String,
    modifier: Modifier,
    videoPreview: Boolean = false,
) {
    var bitmap by remember(url, cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, cacheKey) {
        bitmap = runCatching {
            viewModel.loadMediaBytes(url, cacheKey, videoPreview).decodeToImageBitmap()
        }.getOrNull()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: CircularProgressIndicator(Modifier.size(28.dp))
    }
}
