package dev.readthat.creation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.readthat.client.CreateState
import dev.readthat.client.CreationStatusState
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.shared.CreateCommunityDraft
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostKind
import kotlin.math.absoluteValue

private val ComposerBlue = Color(0xFF0A66C2)
private val ComposerPaleBlue = Color(0xFFD7E8F7)
private val ComposerIconBackground = Color(0xFFE8EEF0)

typealias StagedMediaRenderer = @Composable (
    media: LocalPostMedia,
    kind: PostKind,
    modifier: Modifier,
) -> Unit

/** Mature composer presentation shared by Android and iOS; the host only launches native pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedCreatePostScreen(
    state: CreateState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onCommunityChanged: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onLinkChanged: (String) -> Unit,
    onKindChanged: (PostKind) -> Unit,
    onFlairChanged: (PostFlair?) -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onRefreshCommunities: () -> Unit,
    onPickImages: () -> Unit,
    onPickVideo: () -> Unit,
    stagedMediaRenderer: StagedMediaRenderer,
    onTakePhoto: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val draft = state.post
    var communityPickerVisible by remember { mutableStateOf(false) }
    var flairPickerVisible by remember { mutableStateOf(false) }
    var imageSourceVisible by remember { mutableStateOf(false) }

    if (communityPickerVisible) {
        CommunityPicker(
            snapshot = state.communityDrawer,
            loading = state.communitiesLoading,
            error = state.communitiesError,
            selected = draft.normalizedSubreddit,
            onRefresh = onRefreshCommunities,
            onDismiss = { communityPickerVisible = false },
            onSelect = {
                onCommunityChanged(it)
                communityPickerVisible = false
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            ComposerTopBar(
                subreddit = draft.normalizedSubreddit,
                canPost = draft.canSubmit && !state.submitting,
                submitting = state.submitting,
                onClose = onBack,
                onSelectCommunity = { communityPickerVisible = true },
                onPost = onSubmit,
            )
        },
        bottomBar = {
            PostTypeRail(
                selected = draft.kind,
                onText = { onKindChanged(PostKind.Text) },
                onLink = { onKindChanged(PostKind.Link) },
                onImage = {
                    if (draft.kind != PostKind.Image) onKindChanged(PostKind.Image)
                    imageSourceVisible = true
                },
                onVideo = {
                    if (draft.kind != PostKind.Video) onKindChanged(PostKind.Video)
                    onPickVideo()
                },
            )
        },
    ) { padding ->
        ComposerBody(
            draft = draft,
            flairsAvailable = state.postFlairs.isNotEmpty() || state.postFlairsLoading,
            error = state.error ?: draft.error,
            onTitleChange = onTitleChanged,
            onBodyChange = onBodyChanged,
            onLinkChange = onLinkChanged,
            onClearAttachment = { onKindChanged(PostKind.Text) },
            onRemoveMedia = onRemoveMedia,
            onAddImages = { imageSourceVisible = true },
            onChooseMedia = {
                if (draft.kind == PostKind.Image) imageSourceVisible = true else onPickVideo()
            },
            onChooseFlair = { flairPickerVisible = true },
            stagedMediaRenderer = stagedMediaRenderer,
            modifier = Modifier.padding(padding),
        )
    }

    if (flairPickerVisible) {
        FlairPicker(
            options = state.postFlairs,
            selected = draft.flair,
            loading = state.postFlairsLoading,
            onDismiss = { flairPickerVisible = false },
            onApply = {
                onFlairChanged(it)
                flairPickerVisible = false
            },
        )
    }

    if (imageSourceVisible) {
        ModalBottomSheet(
            onDismissRequest = { imageSourceVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                "Add image",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            onTakePhoto?.let { takePhoto ->
                SourceRow(Icons.Default.Add, "Take photo") {
                    imageSourceVisible = false
                    takePhoto()
                }
            }
            SourceRow(Icons.Default.Add, "Photo library") {
                imageSourceVisible = false
                onPickImages()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ComposerTopBar(
    subreddit: String,
    canPost: Boolean,
    submitting: Boolean,
    onClose: () -> Unit,
    onSelectCommunity: () -> Unit,
    onPost: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            enabled = !submitting,
            modifier = Modifier.size(44.dp).background(ComposerIconBackground, CircleShape),
        ) { Icon(Icons.Default.Close, "Close create post") }
        Row(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(22.dp))
                .clickable(enabled = !submitting, role = Role.Button, onClick = onSelectCommunity)
                .padding(horizontal = 12.dp, vertical = 10.dp).weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (subreddit.isBlank()) "Select a community" else "r/$subreddit",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp))
        }
        Surface(
            onClick = onPost,
            enabled = canPost,
            color = if (canPost) ComposerBlue else ComposerPaleBlue,
            contentColor = if (canPost) Color.White else Color(0xFF7E9DB8),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.height(40.dp),
        ) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Post", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ComposerBody(
    draft: CreatePostDraft,
    flairsAvailable: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onClearAttachment: () -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onAddImages: () -> Unit,
    onChooseMedia: () -> Unit,
    onChooseFlair: () -> Unit,
    stagedMediaRenderer: StagedMediaRenderer,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        BasicTextField(
            value = draft.title,
            onValueChange = onTitleChange,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            decorationBox = { input ->
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    if (draft.title.isEmpty()) Text(
                        "Title",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    input()
                }
            },
        )

        if (draft.normalizedSubreddit.isNotBlank()) {
            if (flairsAvailable) FlairPill(draft.flair, onChooseFlair)
            Text(
                "Review and follow the rules of this community",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
        }

        when (draft.kind) {
            PostKind.Link -> LinkComposer(draft.linkUrl, onLinkChange, onClearAttachment)
            PostKind.Image, PostKind.Video -> MediaComposer(
                kind = draft.kind,
                media = draft.localMediaItems,
                preparing = draft.preparingMedia,
                onRemove = { index ->
                    if (draft.localMediaItems.size == 1) onClearAttachment() else onRemoveMedia(index)
                },
                onAdd = if (draft.kind == PostKind.Image && draft.localMediaItems.size < 20) onAddImages else null,
                onChoose = onChooseMedia,
                onClear = onClearAttachment,
                stagedMediaRenderer = stagedMediaRenderer,
            )
            PostKind.Text -> Unit
        }

        BasicTextField(
            value = draft.body,
            onValueChange = onBodyChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
            decorationBox = { input ->
                Box(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                    if (draft.body.isEmpty()) Text(
                        if (draft.kind == PostKind.Text) "Body text (optional)" else "Caption (optional)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    input()
                }
            },
        )
        Text(
            "Draft media is staged privately; every post type commits to Room before upload.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FlairPill(flair: PostFlair?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = colorOr(flair?.backgroundColor, ComposerIconBackground),
        contentColor = colorOr(flair?.textColor, MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (flair == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(flair?.text ?: "Add flair", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LinkComposer(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
            decorationBox = { input ->
                Box {
                    if (value.isBlank()) Text("Enter link", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                }
            },
        )
        IconButton(onClick = onClear, modifier = Modifier.size(32.dp).background(ComposerIconBackground, CircleShape)) {
            Icon(Icons.Default.Close, "Remove link", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MediaComposer(
    kind: PostKind,
    media: List<LocalPostMedia>,
    preparing: Boolean,
    onRemove: (Int) -> Unit,
    onAdd: (() -> Unit)?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    stagedMediaRenderer: StagedMediaRenderer,
) {
    if (preparing) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (media.isEmpty()) {
        Surface(
            onClick = onChoose,
            color = ComposerIconBackground,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(170.dp).padding(vertical = 8.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (kind == PostKind.Image) Icons.Default.Add else Icons.Default.PlayArrow, null)
                Text(if (kind == PostKind.Image) "Add photos" else "Add a video", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClear) { Text("Cancel") }
            }
        }
        return
    }
    LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(media, key = { _, item -> item.localPath }) { index, item ->
            MediaTile(item, kind, { onRemove(index) }, stagedMediaRenderer)
        }
        if (onAdd != null) item {
            Surface(
                onClick = onAdd,
                color = ComposerIconBackground,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(160.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Add, null)
                    Text("Add", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MediaTile(
    media: LocalPostMedia,
    kind: PostKind,
    onRemove: () -> Unit,
    renderer: StagedMediaRenderer,
) {
    Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        renderer(media, kind, Modifier.fillMaxSize())
        IconButton(
            onClick = onRemove,
            modifier = Modifier.padding(8.dp).size(32.dp).align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = .72f), CircleShape),
        ) { Icon(Icons.Default.Close, "Remove ${media.name}", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun PostTypeRail(
    selected: PostKind,
    onText: () -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onVideo: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeButton(Icons.Default.Add, "TEXT", selected == PostKind.Text, onText)
            TypeButton(Icons.Default.Search, "LINK", selected == PostKind.Link, onLink)
            TypeButton(Icons.Default.Add, "IMAGE", selected == PostKind.Image, onImage)
            TypeButton(Icons.Default.PlayArrow, "VIDEO", selected == PostKind.Video, onVideo)
            Spacer(Modifier.weight(1f))
            Text("Markdown", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TypeButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { role = Role.Button; contentDescription = label },
    ) { Icon(icon, null, tint = if (selected) ComposerBlue else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

internal data class CommunityChoice(
    val name: String,
    val displayName: String,
    val recent: Boolean,
    val accessType: String,
    val role: String,
)

internal fun CommunityDrawerSnapshot.creationChoices(): List<CommunityChoice> {
    val membershipByName = communities.associateBy { it.name.lowercase() }
    val recent = recentlyVisited.map {
        val membership = membershipByName[it.name.lowercase()]
        CommunityChoice(
            it.name,
            it.displayName,
            true,
            membership?.accessType ?: "public",
            membership?.role ?: "visitor",
        )
    }
    val recentNames = recent.mapTo(mutableSetOf()) { it.name.lowercase() }
    return recent + communities.filterNot { it.name.lowercase() in recentNames }.map {
        CommunityChoice(it.name, it.displayName, false, it.accessType, it.role)
    }
}

@Composable
private fun CommunityPicker(
    snapshot: CommunityDrawerSnapshot,
    loading: Boolean,
    error: String?,
    selected: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    val choices = remember(snapshot, query) {
        snapshot.creationChoices().filter {
            query.isBlank() || it.name.contains(query, true) || it.displayName.contains(query, true)
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close community picker") }
                Text("Post to", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    .background(ComposerIconBackground, RoundedCornerShape(24.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp),
                    decorationBox = { input ->
                        Box {
                            if (query.isBlank()) Text("Search communities", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                    },
                )
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Clear search")
                } else Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
            when {
                loading && choices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && choices.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(choices, key = CommunityChoice::name) { choice ->
                        CommunityRow(choice, choice.name.equals(selected, true)) { onSelect(choice.name) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (choices.isEmpty()) item {
                        Text("No communities found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityRow(choice: CommunityChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = listOf(Color(0xFFFF4500), Color(0xFF0A66C2), Color(0xFF46A508), Color(0xFF8E44AD))
        Box(
            Modifier.size(46.dp).background(colors[choice.name.hashCode().absoluteValue % colors.size], CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(choice.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("r/${choice.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                choice.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(choice.accessType.replaceFirstChar(Char::uppercase)).append(" community")
                    if (choice.recent) append(" · Recently visited")
                    if (choice.role != "visitor") append(" · ${choice.role}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Default.Check, "Selected", tint = ComposerBlue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlairPicker(
    options: List<PostFlair>,
    selected: PostFlair?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onApply: (PostFlair?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pending by remember(selected) { mutableStateOf(selected) }
    val filtered = remember(options, query) { options.filter { query.isBlank() || it.text.contains(query, true) } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxHeight(.78f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add flair", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onApply(pending) }) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                    .background(ComposerIconBackground, RoundedCornerShape(24.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp),
                    decorationBox = { input ->
                        Box {
                            if (query.isBlank()) Text("Search flair", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                    },
                )
            }
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item { FlairRow("None", pending == null, null) { pending = null } }
                items(filtered, key = PostFlair::id) { flair ->
                    FlairRow(flair.text, pending?.id == flair.id, flair) { pending = flair }
                }
            }
        }
    }
}

@Composable
private fun FlairRow(label: String, selected: Boolean, flair: PostFlair?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).border(2.dp, if (selected) ComposerBlue else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                .padding(5.dp).background(if (selected) ComposerBlue else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        if (flair == null) Text(label, style = MaterialTheme.typography.bodyLarge)
        else Surface(
            color = colorOr(flair.backgroundColor, ComposerIconBackground),
            contentColor = colorOr(flair.textColor, MaterialTheme.colorScheme.onSurface),
            shape = RoundedCornerShape(14.dp),
        ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold) }
    }
}

/** Shared offline-first community composer. */
@Composable
fun SharedCreateCommunityScreen(
    state: CreateState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onNameChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onAccessChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.community
    Column(modifier.fillMaxSize()) {
        CreationHeader("Create a community", onBack) {
            Button(onClick = onSubmit, enabled = draft.canSubmit && !state.submitting) {
                if (state.submitting) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("Create", fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Your community is available immediately", fontWeight = FontWeight.Bold)
                        Text(
                            "We save it offline first, then sync the same idempotent command when the network is ready.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                draft.name,
                onNameChanged,
                label = { Text("Community name") },
                prefix = { Text("r/") },
                supportingText = { Text("3–21 letters, numbers, or underscores. Names cannot be changed.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                draft.displayName,
                onDisplayNameChanged,
                label = { Text("Display name") },
                supportingText = { Text("${100 - draft.displayName.length} characters left") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                draft.description,
                onDescriptionChanged,
                label = { Text("Description – optional") },
                supportingText = { Text("${1_000 - draft.description.length} characters left") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Text("Community type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessTypeChip("public", "Public", "Anyone can view, join, and post", draft, onAccessChanged)
                AccessTypeChip("restricted", "Restricted", "Anyone can view; approved members can post", draft, onAccessChanged)
                AccessTypeChip("private", "Private", "Only approved members can view and post", draft, onAccessChanged)
            }
            (state.error ?: draft.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccessTypeChip(
    value: String,
    title: String,
    description: String,
    draft: CreateCommunityDraft,
    onSelected: (String) -> Unit,
) {
    FilterChip(
        selected = draft.accessType == value,
        onClick = { onSelected(value) },
        label = {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SharedPendingPostScreen(
    state: CreationStatusState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCommitted: (String) -> Unit,
    autoOpenCommittedPost: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val pending = state.post
    LaunchedEffect(pending?.remotePostId, autoOpenCommittedPost) {
        if (autoOpenCommittedPost) pending?.remotePostId?.let(onCommitted)
    }
    Column(modifier.fillMaxSize()) {
        CreationHeader("Your post", onBack)
        if (pending == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else PendingPostContent(pending, state, onRetry, onCommitted)
    }
}

@Composable
private fun PendingPostContent(
    pending: PendingPostEntity,
    state: CreationStatusState,
    onRetry: () -> Unit,
    onCommitted: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MutationStatusCard(
            pending.state,
            when (pending.state) {
                "completed" -> "Published and confirmed by ReadThat"
                "uploading" -> "Uploading media with resumable progress"
                "creating" -> "Media is ready; publishing the post"
                else -> "Saved on this device and queued for sync"
            },
            state.error ?: pending.lastError,
        )
        Text("r/${pending.subreddit}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(pending.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        pending.flairText?.let { flair ->
            Surface(
                color = colorOr(pending.flairBackgroundColor, MaterialTheme.colorScheme.surfaceVariant),
                contentColor = colorOr(pending.flairTextColor, MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(14.dp),
            ) { Text(flair, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold) }
        }
        when (pending.kind.lowercase()) {
            "text" -> if (pending.body.isNotBlank()) MarkdownText(pending.body)
            "link" -> Text(pending.linkUrl, color = MaterialTheme.colorScheme.primary)
            "image" -> Text("Images are staged privately and upload from the durable queue.")
            "video" -> Text("Video is staged privately and uploads resumably before publishing.")
        }
        pending.remotePostId?.let { postId ->
            Button(onClick = { onCommitted(postId) }, modifier = Modifier.fillMaxWidth()) { Text("Open post") }
        } ?: run {
            OutlinedButton(onClick = onRetry, enabled = !state.retrying, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.retrying) "Retrying…" else "Retry now")
            }
            Text(
                "You can leave this screen. Room keeps the command and uploaded-media progress across restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SharedPendingCommunityScreen(
    state: CreationStatusState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.community
    Column(modifier.fillMaxSize()) {
        CreationHeader("Community", onBack)
        if (pending == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else PendingCommunityContent(pending, state, onRetry, onCreatePost, onOpenCommunity)
    }
}

@Composable
private fun PendingCommunityContent(
    pending: PendingSubredditEntity,
    state: CreationStatusState,
    onRetry: () -> Unit,
    onCreatePost: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MutationStatusCard(
            pending.state,
            if (pending.remoteSubredditId != null) "Created and confirmed by ReadThat"
            else "Available locally now and queued for sync",
            state.error ?: pending.lastError,
        )
        Text("r/${pending.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(pending.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (pending.description.isNotBlank()) Text(pending.description)
        Text(
            "${pending.accessType.replaceFirstChar(Char::uppercase)} community · You are the owner",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pending.remoteSubredditId == null) {
            OutlinedButton(onClick = onRetry, enabled = !state.retrying, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.retrying) "Retrying…" else "Retry creation")
            }
        }
        Button(onClick = { onCreatePost(pending.name) }, modifier = Modifier.fillMaxWidth()) {
            Text("Create the first post")
        }
        OutlinedButton(onClick = { onOpenCommunity(pending.name) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open community")
        }
        Text(
            "Posts created before the community finishes syncing remain ordered behind its idempotent command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreationHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        action()
    }
}

@Composable
private fun MutationStatusCard(state: String, detail: String, error: String?) {
    val completed = state == "completed"
    Surface(
        color = if (completed) Color(0xFF1F7A45).copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (completed) "✓" else "↻", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.padding(start = 14.dp)) {
                Text(state.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.takeIf(String::isNotBlank)?.let {
                    Text(it, Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

internal fun colorOr(value: String?, fallback: Color): Color {
    val text = value?.trim()?.removePrefix("#") ?: return fallback
    val parsed = text.toLongOrNull(16) ?: return fallback
    return when (text.length) {
        6 -> Color(0xFF000000L or parsed)
        8 -> Color(parsed)
        else -> fallback
    }
}
