package dev.readthat.client

import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentRenderList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class SharedDetailUiState(
    val detail: DetailState = DetailState(),
    val render: CommentRenderList = CommentRenderList(emptyList(), 0, 0),
    val canMutate: Boolean = true,
)

/** Expensive tree flattening is shared too, and never re-runs for loading/error flag churn. */
internal fun Flow<DetailState>.commentRenderLists(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<CommentRenderList> = map { state ->
    DetailStructure(
        tree = state.comments,
        collapsed = state.collapsedCommentIds + state.autoCollapsedCommentIds,
    )
}.distinctUntilChanged().map { structure ->
    structure.tree?.let { CommentFlattener.flatten(it, structure.collapsed) }
        ?: CommentRenderList(emptyList(), 0, 0)
}.flowOn(dispatcher)

private data class DetailStructure(
    val tree: dev.readthat.comments.domain.CommentTree?,
    val collapsed: Set<String>,
)
