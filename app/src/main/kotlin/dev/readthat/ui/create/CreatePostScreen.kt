package dev.readthat.ui.create

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostKind
import java.io.File

private val ComposerBlue = Color(0xFF0A66C2)
private val ComposerPaleBlue = Color(0xFFE7F3FF)
private val ComposerIconBackground = Color(0xFFF2F4F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostScreen(
    viewModel: CreatePostViewModel,
    onBack: () -> Unit,
    onQueued: (mutationId: String) -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val communities by viewModel.communities.collectAsStateWithLifecycle()
    val communityLoading by viewModel.communityLoading.collectAsStateWithLifecycle()
    val communityError by viewModel.communityError.collectAsStateWithLifecycle()
    val flairs by viewModel.flairs.collectAsStateWithLifecycle()
    val flairsLoading by viewModel.flairsLoading.collectAsStateWithLifecycle()
    var communityPickerVisible by remember { mutableStateOf(false) }
    var flairPickerVisible by remember { mutableStateOf(false) }
    var imageSourceVisible by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (draft.localMediaItems.isEmpty()) viewModel.selectImages(uris) else viewModel.addImages(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let(viewModel::selectCapturedImage)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::selectMedia)
    }

    if (communityPickerVisible) {
        CommunityPicker(
            snapshot = communities,
            loading = communityLoading,
            error = communityError,
            selected = draft.normalizedSubreddit,
            onRefresh = viewModel::refreshCommunities,
            onDismiss = { communityPickerVisible = false },
            onSelect = {
                viewModel.setSubreddit(it)
                communityPickerVisible = false
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            ComposerTopBar(
                subreddit = draft.normalizedSubreddit,
                canPost = draft.canSubmit,
                submitting = draft.submitting,
                onClose = onBack,
                onSelectCommunity = { communityPickerVisible = true },
                onPost = { viewModel.submit(onQueued) },
            )
        },
        bottomBar = {
            PostTypeRail(
                selected = draft.kind,
                onLink = { viewModel.setKind(PostKind.Link) },
                onImage = { imageSourceVisible = true },
                onVideo = {
                    viewModel.setKind(PostKind.Video)
                    videoPicker.launch("video/*")
                },
            )
        },
    ) { padding ->
        ComposerBody(
            draft = draft,
            onTitleChange = viewModel::setTitle,
            onBodyChange = viewModel::setBody,
            onLinkChange = viewModel::setLink,
            onClearAttachment = { viewModel.setKind(PostKind.Text) },
            onRemoveMedia = viewModel::removeMediaAt,
            onAddImages = { imageSourceVisible = true },
            onChooseMedia = {
                if (draft.kind == PostKind.Image) imageSourceVisible = true
                else videoPicker.launch("video/*")
            },
            onChooseFlair = { flairPickerVisible = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (flairPickerVisible) {
        FlairPicker(
            options = flairs,
            selected = draft.flair,
            loading = flairsLoading,
            onDismiss = { flairPickerVisible = false },
            onApply = {
                viewModel.setFlair(it)
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            SourceRow(Icons.Default.CameraAlt, "Take photo") {
                imageSourceVisible = false
                if (draft.kind != PostKind.Image) viewModel.setKind(PostKind.Image)
                camera.launch(null)
            }
            SourceRow(Icons.Default.Image, "Photo library") {
                imageSourceVisible = false
                if (draft.kind != PostKind.Image) viewModel.setKind(PostKind.Image)
                imagePicker.launch("image/*")
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
            modifier = Modifier.size(44.dp).background(ComposerIconBackground, CircleShape),
        ) { Icon(Icons.Default.Close, "Close create post") }
        Row(
            Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onSelectCommunity)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .weight(1f),
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
        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.MoreHoriz, "More post options")
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
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Post", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ComposerBody(
    draft: CreatePostDraft,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onClearAttachment: () -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onAddImages: () -> Unit,
    onChooseMedia: () -> Unit,
    onChooseFlair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
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
                    if (draft.title.isEmpty()) {
                        Text(
                            "Title",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    input()
                }
            },
        )

        if (draft.normalizedSubreddit.isNotBlank()) {
            FlairPill(draft.flair, onChooseFlair)
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
                    if (draft.body.isEmpty()) {
                        Text(
                            "Body text (optional)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    input()
                }
            },
        )
        draft.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FlairPill(flair: PostFlair?, onClick: () -> Unit) {
    val background = flair?.backgroundColor.toComposeColor(ComposerIconBackground)
    val foreground = flair?.textColor.toComposeColor(MaterialTheme.colorScheme.onSurface)
    Surface(
        onClick = onClick,
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (flair == null) Icons.Default.Add else Icons.Default.Check, null, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(flair?.text ?: "Add flair", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LinkComposer(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        IconButton(
            onClick = onClear,
            modifier = Modifier.size(32.dp).background(ComposerIconBackground, CircleShape),
        ) { Icon(Icons.Default.Close, "Remove link", Modifier.size(18.dp)) }
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
) {
    if (preparing) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
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
                Icon(if (kind == PostKind.Image) Icons.Default.Image else Icons.Default.Videocam, null)
                Text(if (kind == PostKind.Image) "Add photos" else "Add a video", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClear) { Text("Cancel") }
            }
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(media.size, key = { media[it].localPath }) { index ->
            MediaTile(media[index], kind == PostKind.Video, { onRemove(index) })
        }
        if (onAdd != null) {
            item {
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
}

@Composable
private fun MediaTile(media: LocalPostMedia, video: Boolean, onRemove: () -> Unit) {
    Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        if (!video) {
            AsyncImage(
                model = File(media.localPath),
                contentDescription = media.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                "Video selected",
                tint = Color.White,
                modifier = Modifier.size(64.dp).align(Alignment.Center),
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.padding(8.dp).size(32.dp).align(Alignment.TopEnd).background(Color.Black.copy(alpha = .72f), CircleShape),
        ) { Icon(Icons.Default.Close, "Remove ${media.name}", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun PostTypeRail(selected: PostKind, onLink: () -> Unit, onImage: () -> Unit, onVideo: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeButton(Icons.Default.Link, "LINK", selected == PostKind.Link, onLink)
            TypeButton(Icons.Default.Image, "IMAGE", selected == PostKind.Image, onImage)
            TypeButton(Icons.Default.Videocam, "VIDEO", selected == PostKind.Video, onVideo)
            Spacer(Modifier.weight(1f))
            Text("Markdown", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun TypeButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { role = Role.Button; contentDescription = label },
    ) {
        Icon(icon, null, tint = if (selected) ComposerBlue else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private data class CommunityChoice(
    val name: String,
    val displayName: String,
    val recent: Boolean,
    val accessType: String,
    val role: String,
)

private fun CommunityDrawerSnapshot.choices(): List<CommunityChoice> {
    val membershipByName = communities.associateBy { it.name.lowercase() }
    val recent = recentlyVisited.map {
        val membership = membershipByName[it.name.lowercase()]
        CommunityChoice(
            name = it.name,
            displayName = it.displayName,
            recent = true,
            accessType = membership?.accessType ?: "public",
            role = membership?.role ?: "visitor",
        )
    }
    val recentNames = recent.mapTo(mutableSetOf()) { it.name.lowercase() }
    return recent + communities
        .filterNot { it.name.lowercase() in recentNames }
        .map { CommunityChoice(it.name, it.displayName, false, it.accessType, it.role) }
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
) {
    var query by remember { mutableStateOf("") }
    val choices = remember(snapshot, query) {
        snapshot.choices().filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true)
        }
    }
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Clear search") }
                    } else {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                            CommunityRow(choice, choice.name.equals(selected, ignoreCase = true)) { onSelect(choice.name) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (choices.isEmpty()) {
                            item { Text("No communities found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
        }
    }
}

@Composable
private fun CommunityRow(choice: CommunityChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarColors = listOf(Color(0xFFFF4500), Color(0xFF0A66C2), Color(0xFF46A508), Color(0xFF8E44AD))
        Box(
            Modifier.size(46.dp).background(avatarColors[kotlin.math.abs(choice.name.hashCode()) % avatarColors.size], CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(choice.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
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
                    append(choice.accessType.replaceFirstChar(Char::uppercase))
                    append(" community")
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
    val filtered = remember(options, query) { options.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxHeight(.78f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        FlairRow(label = "None", selected = pending == null, flair = null) { pending = null }
                    }
                    items(filtered, key = PostFlair::id) { flair ->
                        FlairRow(label = flair.text, selected = pending?.id == flair.id, flair = flair) { pending = flair }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlairRow(label: String, selected: Boolean, flair: PostFlair?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) ComposerBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                )
                .padding(5.dp)
                .background(if (selected) ComposerBlue else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        if (flair == null) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        } else {
            Surface(
                color = flair.backgroundColor.toComposeColor(ComposerIconBackground),
                contentColor = flair.textColor.toComposeColor(MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun String?.toComposeColor(fallback: Color): Color = this?.let { value ->
    runCatching { Color(AndroidColor.parseColor(value)) }.getOrDefault(fallback)
} ?: fallback
