package dev.readthat.domain

/**
 * THE FLATTENER — the piece the interview prompt calls "flattening".
 *
 * The server sends a two-level structure:
 *
 *     [ Group(g1, [c1, c2, c3]), Group(g2, [c4, c5]), ... ]
 *
 * A LazyColumn / RecyclerView wants ONE dimension:
 *
 *     [ c1, c2, c3, divider, c4, c5, divider, ... ]
 *
 * Flattening collapses the nesting into that single render list while preserving
 * order, and — critically — assigns each item a STABLE, UNIQUE key.
 *
 * Why stable keys matter, and why the key is composite:
 *   - LazyColumn uses the key for scroll position restoration and to decide what to
 *     recompose when the list changes. Index-based keys break both.
 *   - The same cellId can legitimately appear in more than one group (a shared
 *     "promoted" cell, or the same post surfaced twice in different carousels), so
 *     cellId alone is not unique. "groupId/cellId" is.
 *
 * This is a pure function: List<WireGroup> -> RenderList. No Android, no IO.
 */
object FeedFlattener {

    const val KEY_SEPARATOR = "/"

    fun flatten(
        groups: List<WireGroup>,
        registry: CellConverterRegistry = CellConverterRegistry(),
        appendDividers: Boolean = true,
    ): RenderList {
        val items = ArrayList<CellUi>(groups.sumOf { it.cells.size } + groups.size)
        val dropped = LinkedHashMap<String, Int>()

        for (group in groups) {
            var renderedInGroup = 0

            for (cell in group.cells) {
                val key = keyFor(group.groupId, cell.cellId)
                val ui = registry.convert(cell, key)
                if (ui != null) {
                    items += ui
                    renderedInGroup++
                } else {
                    val typeName = when (cell) {
                        is WireCell.Unknown -> cell.typeName
                        else -> cell::class.simpleName ?: "unnamed"
                    }
                    dropped[typeName] = (dropped[typeName] ?: 0) + 1
                }
            }

            // A group that rendered nothing must not leave a stray divider behind —
            // otherwise a client that can't parse a new post type shows a feed of
            // empty separators instead of degrading invisibly.
            if (appendDividers && renderedInGroup > 0) {
                items += CellUi.GroupDivider(key = keyFor(group.groupId, "divider"))
            }
        }

        return RenderList(items = items, droppedCellTypes = dropped)
    }

    fun keyFor(groupId: String, cellId: String): String = groupId + KEY_SEPARATOR + cellId
}
