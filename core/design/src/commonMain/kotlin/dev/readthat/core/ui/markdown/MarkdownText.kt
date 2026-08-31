package dev.readthat.core.ui.markdown

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

internal sealed interface MarkdownBlock {
    val indentLevel: Int

    data class Paragraph(
        override val indentLevel: Int,
        val content: List<MarkdownInline>,
    ) : MarkdownBlock

    data class ListItem(
        override val indentLevel: Int,
        val marker: String,
        val content: List<MarkdownInline>,
    ) : MarkdownBlock

    data class Quote(
        override val indentLevel: Int,
        val content: List<MarkdownInline>,
    ) : MarkdownBlock

    data object Blank : MarkdownBlock {
        override val indentLevel: Int = 0
    }
}

internal sealed interface MarkdownInline {
    data class Plain(val value: String) : MarkdownInline
    data class Strong(val content: List<MarkdownInline>) : MarkdownInline
    data class Emphasis(val content: List<MarkdownInline>) : MarkdownInline
    data class Link(val content: List<MarkdownInline>, val url: String) : MarkdownInline
}

/**
 * A small, bounded subset of Reddit-flavoured Markdown for user-authored post and comment bodies.
 * The source remains the cached/network representation; parsing is presentation-only so Android,
 * iOS, and web can apply native typography without inflating the API payload.
 */
internal object MarkdownParser {
    private val unorderedItem = Regex("^([-+*])\\s+(.*)$")
    private val orderedItem = Regex("^(\\d{1,3}[.)])\\s+(.*)$")
    private val safeUrl = Regex("^https?://[^\\s\\p{Cntrl}]+$", RegexOption.IGNORE_CASE)

    fun parse(source: String): List<MarkdownBlock> {
        val blocks = ArrayList<MarkdownBlock>()
        source.normalizedLines().forEach { rawLine ->
            if (rawLine.isBlank()) {
                if (blocks.lastOrNull() !is MarkdownBlock.Blank) blocks += MarkdownBlock.Blank
                return@forEach
            }

            val leading = rawLine.takeWhile { it == ' ' || it == '\t' }
            val leadingSpaces = leading.sumOf { if (it == '\t') TAB_WIDTH else 1 }
            var content = rawLine.drop(leading.length)
            var indent = (leadingSpaces / SPACES_PER_INDENT).coerceAtMost(MAX_INDENT)

            var quoteDepth = 0
            while (content.startsWith('>')) {
                quoteDepth++
                content = content.drop(1).removePrefix(" ")
            }
            if (quoteDepth > 0) {
                indent = (indent + quoteDepth - 1).coerceAtMost(MAX_INDENT)
                blocks += MarkdownBlock.Quote(indent, parseInline(content))
                return@forEach
            }

            unorderedItem.matchEntire(content)?.let { match ->
                blocks += MarkdownBlock.ListItem(indent, BULLET, parseInline(match.groupValues[2]))
                return@forEach
            }
            orderedItem.matchEntire(content)?.let { match ->
                blocks += MarkdownBlock.ListItem(indent, match.groupValues[1], parseInline(match.groupValues[2]))
                return@forEach
            }
            blocks += MarkdownBlock.Paragraph(indent, parseInline(content))
        }
        while (blocks.lastOrNull() is MarkdownBlock.Blank) blocks.removeAt(blocks.lastIndex)
        return blocks
    }

    fun preview(source: String): String = parse(source)
        .asSequence()
        .mapNotNull { block ->
            when (block) {
                MarkdownBlock.Blank -> null
                is MarkdownBlock.Paragraph -> block.content.plainText()
                is MarkdownBlock.ListItem -> block.content.plainText()
                is MarkdownBlock.Quote -> block.content.plainText()
            }
        }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")

    fun parseInline(source: String, depth: Int = 0): List<MarkdownInline> {
        if (source.isEmpty()) return emptyList()
        if (depth >= MAX_INLINE_NESTING) return listOf(MarkdownInline.Plain(source.unescaped()))

        val output = ArrayList<MarkdownInline>()
        val plain = StringBuilder()
        // A failed delimiter search proves there is no valid closer later at this parse depth.
        // Remember that result so adversarial repeated openers cannot turn parsing quadratic.
        val exhaustedDelimiters = HashSet<String>()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                output += MarkdownInline.Plain(plain.toString())
                plain.clear()
            }
        }

        var index = 0
        while (index < source.length) {
            if (source[index] == '\\' && index + 1 < source.length) {
                plain.append(source[index + 1])
                index += 2
                continue
            }

            val markdownLink = parseMarkdownLink(source, index, depth)
            if (markdownLink != null) {
                flushPlain()
                output += MarkdownInline.Link(markdownLink.label, markdownLink.url)
                index = markdownLink.endExclusive
                continue
            }

            val angleAutolink = parseAngleAutolink(source, index)
            if (angleAutolink != null) {
                flushPlain()
                output += MarkdownInline.Link(
                    listOf(MarkdownInline.Plain(angleAutolink.url)),
                    angleAutolink.url,
                )
                index = angleAutolink.endExclusive
                continue
            }

            val tripleMarker = when {
                source.startsWith("***", index) -> "***"
                source.startsWith("___", index) -> "___"
                else -> null
            }
            val tripleKey = "triple:$tripleMarker"
            if (
                tripleMarker != null &&
                tripleKey !in exhaustedDelimiters &&
                canOpenDelimiter(source, index, tripleMarker)
            ) {
                val end = findClosingDelimiter(source, tripleMarker, index + tripleMarker.length)
                if (end > index + tripleMarker.length) {
                    flushPlain()
                    val emphasized = MarkdownInline.Emphasis(
                        parseInline(source.substring(index + tripleMarker.length, end), depth + 1),
                    )
                    output += MarkdownInline.Strong(listOf(emphasized))
                    index = end + tripleMarker.length
                    continue
                }
                if (end < 0) exhaustedDelimiters += tripleKey
            }

            val strongMarker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("__", index) -> "__"
                else -> null
            }
            val strongKey = "strong:$strongMarker"
            if (
                strongMarker != null &&
                strongKey !in exhaustedDelimiters &&
                canOpenDelimiter(source, index, strongMarker)
            ) {
                val end = findClosingStrongDelimiter(source, strongMarker, index + strongMarker.length)
                if (end > index + strongMarker.length) {
                    flushPlain()
                    output += MarkdownInline.Strong(
                        parseInline(source.substring(index + strongMarker.length, end), depth + 1),
                    )
                    index = end + strongMarker.length
                    continue
                }
                if (end < 0) exhaustedDelimiters += strongKey
            }

            val emphasisMarker = source[index].toString().takeIf {
                (it == "*" || it == "_") && !source.startsWith(it + it, index)
            }
            val emphasisKey = "emphasis:$emphasisMarker"
            if (
                emphasisMarker != null &&
                emphasisKey !in exhaustedDelimiters &&
                canOpenDelimiter(source, index, emphasisMarker)
            ) {
                val end = findClosingEmphasisDelimiter(
                    source,
                    emphasisMarker,
                    index + emphasisMarker.length,
                )
                if (end > index + emphasisMarker.length) {
                    flushPlain()
                    output += MarkdownInline.Emphasis(
                        parseInline(source.substring(index + emphasisMarker.length, end), depth + 1),
                    )
                    index = end + emphasisMarker.length
                    continue
                }
                if (end < 0) exhaustedDelimiters += emphasisKey
            }

            val bareUrl = parseBareUrl(source, index)
            if (bareUrl != null) {
                flushPlain()
                output += MarkdownInline.Link(listOf(MarkdownInline.Plain(bareUrl.url)), bareUrl.url)
                plain.append(bareUrl.trailing)
                index = bareUrl.endExclusive
                continue
            }

            plain.append(source[index])
            index++
        }
        flushPlain()
        return output
    }

    private fun parseMarkdownLink(source: String, start: Int, depth: Int): InlineMatch? {
        if (source[start] != '[') return null
        val labelEnd = source.findUnescaped(
            target = ']',
            start = start + 1,
            endExclusive = minOf(source.length, start + 1 + MAX_LINK_LABEL_LENGTH),
        )
        if (labelEnd <= start + 1 || source.getOrNull(labelEnd + 1) != '(') return null
        val urlEnd = source.findBalancedClosingParenthesis(
            start = labelEnd + 2,
            endExclusive = minOf(source.length, labelEnd + 2 + MAX_LINK_TARGET_LENGTH),
        ) ?: return null
        val rawUrl = source.substring(labelEnd + 2, urlEnd).removeSurrounding("<", ">").unescaped()
        if (!safeUrl.matches(rawUrl)) return null
        return InlineMatch(
            label = parseInline(source.substring(start + 1, labelEnd), depth + 1),
            url = rawUrl,
            endExclusive = urlEnd + 1,
        )
    }

    private fun parseAngleAutolink(source: String, start: Int): UrlMatch? {
        if (source[start] != '<') return null
        val end = source.findUnescaped(
            target = '>',
            start = start + 1,
            endExclusive = minOf(source.length, start + 1 + MAX_LINK_TARGET_LENGTH),
        )
        if (end < 0) return null
        val url = source.substring(start + 1, end)
        return url.takeIf(safeUrl::matches)?.let { UrlMatch(it, end + 1) }
    }

    private fun parseBareUrl(source: String, start: Int): UrlMatch? {
        val isUrl = source.regionMatches(start, "https://", 0, 8, ignoreCase = true) ||
            source.regionMatches(start, "http://", 0, 7, ignoreCase = true)
        if (!isUrl) return null
        val rawEnd = source.indexOfFirstFrom(start) { it.isWhitespace() }
            .takeIf { it >= 0 } ?: source.length
        val candidate = source.substring(start, rawEnd)
        val url = candidate.withoutTrailingUrlPunctuation()
        if (!safeUrl.matches(url)) return null
        return UrlMatch(url, rawEnd, candidate.drop(url.length))
    }

    private fun findClosingDelimiter(source: String, delimiter: String, start: Int): Int {
        var index = start
        while (index <= source.length - delimiter.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source.startsWith(delimiter, index) && canCloseDelimiter(source, index, delimiter)) {
                return index
            }
            index++
        }
        return -1
    }

    private fun findClosingStrongDelimiter(source: String, delimiter: String, start: Int): Int {
        var index = start
        while (index <= source.length - delimiter.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source.startsWith(delimiter, index)) {
                val marker = delimiter[0]
                val runLength = source.markerRunLength(index, marker)
                val closingIndex = if (runLength >= 3) index + runLength - 2 else index
                if (canCloseDelimiter(source, closingIndex, delimiter)) return closingIndex
                index += runLength.coerceAtLeast(1)
                continue
            }
            index++
        }
        return -1
    }

    private fun findClosingEmphasisDelimiter(source: String, delimiter: String, start: Int): Int {
        var index = start
        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index].toString() == delimiter) {
                val inMarkerRun = source.getOrNull(index - 1) == delimiter[0] ||
                    source.getOrNull(index + 1) == delimiter[0]
                if (!inMarkerRun && canCloseDelimiter(source, index, delimiter)) return index
            }
            index++
        }
        return -1
    }

    private fun canOpenDelimiter(source: String, index: Int, delimiter: String): Boolean {
        val next = source.getOrNull(index + delimiter.length) ?: return false
        if (next.isWhitespace()) return false
        if (delimiter.first() == '_') {
            val previous = source.getOrNull(index - 1)
            if (previous?.isLetterOrDigit() == true && next.isLetterOrDigit()) return false
        }
        return true
    }

    private fun canCloseDelimiter(source: String, index: Int, delimiter: String): Boolean {
        val previous = source.getOrNull(index - 1) ?: return false
        if (previous.isWhitespace()) return false
        if (delimiter.first() == '_') {
            val next = source.getOrNull(index + delimiter.length)
            if (previous.isLetterOrDigit() && next?.isLetterOrDigit() == true) return false
        }
        return true
    }

    private fun String.normalizedLines() = replace("\r\n", "\n").replace('\r', '\n').split('\n')

    private fun String.findUnescaped(
        target: Char,
        start: Int,
        endExclusive: Int = length,
    ): Int {
        var index = start
        while (index < endExclusive) {
            if (this[index] == '\\') index += 2
            else if (this[index] == target) return index
            else index++
        }
        return -1
    }

    private fun String.findBalancedClosingParenthesis(
        start: Int,
        endExclusive: Int = length,
    ): Int? {
        var depth = 1
        var index = start
        while (index < endExclusive) {
            when (this[index]) {
                '\\' -> index++
                '(' -> depth++
                ')' -> if (--depth == 0) return index
            }
            index++
        }
        return null
    }

    private fun String.markerRunLength(start: Int, marker: Char): Int {
        var end = start
        while (end < length && this[end] == marker) end++
        return end - start
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }

    private fun String.withoutTrailingUrlPunctuation(): String {
        var end = length
        var roundBalance = 0
        var squareBalance = 0
        forEach { character ->
            when (character) {
                '(' -> roundBalance++
                ')' -> roundBalance--
                '[' -> squareBalance++
                ']' -> squareBalance--
            }
        }
        while (end > 0) {
            val shouldTrim = when (this[end - 1]) {
                in URL_TRAILING_PUNCTUATION -> true
                ')' -> roundBalance < 0
                ']' -> squareBalance < 0
                else -> false
            }
            if (!shouldTrim) break
            when (this[end - 1]) {
                ')' -> roundBalance++
                ']' -> squareBalance++
            }
            end--
        }
        return substring(0, end)
    }

    private fun String.unescaped(): String = buildString(length) {
        var index = 0
        while (index < this@unescaped.length) {
            if (this@unescaped[index] == '\\' && index + 1 < this@unescaped.length) index++
            append(this@unescaped[index])
            index++
        }
    }

    private data class InlineMatch(
        val label: List<MarkdownInline>,
        val url: String,
        val endExclusive: Int,
    )

    private data class UrlMatch(
        val url: String,
        val endExclusive: Int,
        val trailing: String = "",
    )

    private const val MAX_INDENT = 6
    private const val MAX_INLINE_NESTING = 12
    private const val MAX_LINK_LABEL_LENGTH = 512
    private const val MAX_LINK_TARGET_LENGTH = 2_048
    private const val SPACES_PER_INDENT = 2
    private const val TAB_WIDTH = 4
    private const val BULLET = "•"
    private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':')
}

/** Returns readable single-line text for compact previews without exposing Markdown delimiters. */
fun markdownPlainText(source: String): String = MarkdownParser.preview(source)

/**
 * Renders the supported Markdown subset as one [Text] node. A single node keeps feed max-line
 * truncation global across paragraphs and lists, and gives accessibility services one coherent
 * reading order while retaining native, independently tappable link annotations.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
    val quoteBarColor = MaterialTheme.colorScheme.outline
    val annotated = remember(blocks, linkColor, quoteColor, quoteBarColor) {
        blocks.toAnnotatedString(linkColor, quoteColor, quoteBarColor)
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private fun List<MarkdownInline>.plainText(): String = buildString {
    fun appendInline(inline: MarkdownInline) {
        when (inline) {
            is MarkdownInline.Plain -> append(inline.value)
            is MarkdownInline.Strong -> inline.content.forEach(::appendInline)
            is MarkdownInline.Emphasis -> inline.content.forEach(::appendInline)
            is MarkdownInline.Link -> inline.content.forEach(::appendInline)
        }
    }
    forEach(::appendInline)
}

private fun List<MarkdownBlock>.toAnnotatedString(
    linkColor: Color,
    quoteColor: Color,
    quoteBarColor: Color,
): AnnotatedString = buildAnnotatedString {
    this@toAnnotatedString.forEachIndexed { index, block ->
        when (block) {
            MarkdownBlock.Blank -> Unit
            is MarkdownBlock.Paragraph -> withStyle(block.paragraphStyle()) {
                block.content.forEach { appendMarkdownInline(it, linkColor) }
            }
            is MarkdownBlock.ListItem -> withStyle(block.paragraphStyle(hangingIndentSp = 22)) {
                append(block.marker)
                append("  ")
                block.content.forEach { appendMarkdownInline(it, linkColor) }
            }
            is MarkdownBlock.Quote -> withStyle(block.paragraphStyle(hangingIndentSp = 18)) {
                withStyle(SpanStyle(color = quoteBarColor, fontWeight = FontWeight.Bold)) {
                    append("│  ")
                }
                withStyle(SpanStyle(color = quoteColor)) {
                    block.content.forEach { appendMarkdownInline(it, linkColor) }
                }
            }
        }
        if (index != this@toAnnotatedString.lastIndex) append('\n')
    }
}

private fun MarkdownBlock.paragraphStyle(hangingIndentSp: Int = 0): ParagraphStyle {
    val firstLine = indentLevel * INDENT_SP
    return ParagraphStyle(
        textIndent = TextIndent(
            firstLine = firstLine.sp,
            restLine = (firstLine + hangingIndentSp).sp,
        ),
    )
}

private fun AnnotatedString.Builder.appendMarkdownInline(inline: MarkdownInline, linkColor: Color) {
    when (inline) {
        is MarkdownInline.Plain -> append(inline.value)
        is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            inline.content.forEach { appendMarkdownInline(it, linkColor) }
        }
        is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            inline.content.forEach { appendMarkdownInline(it, linkColor) }
        }
        is MarkdownInline.Link -> withLink(
            LinkAnnotation.Url(
                inline.url,
                TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ),
            ),
        ) {
            inline.content.forEach { appendMarkdownInline(it, linkColor) }
        }
    }
}

private const val INDENT_SP = 16
