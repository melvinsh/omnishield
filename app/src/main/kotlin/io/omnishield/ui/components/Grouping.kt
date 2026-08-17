package io.omnishield.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Expressive grouped-list corner treatment: a run of related rows rendered as one visual
 * unit, with the group's silhouette carrying the large radius and a hairline gap replacing the
 * divider. First row gets the large corners on top, last on the bottom, middles stay nearly
 * square; a single-row group is a plain large-radius container.
 *
 * This replaces the Card-plus-`HorizontalDivider` sections, which is exactly the pattern that
 * reads as "Material 2 with rounded corners": one shape token on every container flattens the
 * hierarchy the shape system is supposed to carry. Segmenting makes each row a surface of its
 * own — pressed states and ripples clip to the row, not the whole section — while the group
 * still scans as one thing.
 */
fun groupedShape(index: Int, count: Int, outer: Dp = 26.dp, inner: Dp = 5.dp): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** Collects the rows of one group. Plain list building, so callers may loop while adding. */
class GroupedScope internal constructor() {
    internal val entries = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        entries += content
    }
}

/**
 * Renders each [GroupedScope.item] on its own [Surface] with [groupedShape] corners and a [gap]
 * between rows — no dividers. The Surface clips, so ripples and `pressScale` on rows inside
 * stay contained to their segment.
 */
@Composable
fun GroupedColumn(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    gap: Dp = 2.dp,
    content: GroupedScope.() -> Unit,
) {
    val scope = GroupedScope().apply(content)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        val count = scope.entries.size
        scope.entries.forEachIndexed { index, entry ->
            // Full width on the segment itself: a Surface hugs its content, and a row whose
            // content happens to be narrow (a version label, an empty-state line) would
            // otherwise render as a pill instead of a band of the group.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupedShape(index, count),
                color = color,
                content = entry,
            )
        }
    }
}
