package com.example.merchandisecontrolsplitview.data

/** Pure bounded projection of actual AccessibilityNodeInfo values. It grants no authority,
 * performs no UI action, and cannot substitute for the separate typed draw/complete-dataset proof. */
internal object PilotB6AxTargetJoin {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val empty: Boolean get() = right <= left || bottom <= top
        val height: Long get() = bottom.toLong() - top
        fun contains(other: Bounds): Boolean = !empty && !other.empty &&
            left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }
    data class Node(val id: Int, val parentID: Int?, val text: String?, val visible: Boolean,
        val bounds: Bounds, val clickable: Boolean = false, val collectionItem: Boolean = false,
        val collectionContainer: Boolean = false)
    data class Target(val anchor: String, val fields: List<String>)
    data class Match(val context: Boolean, val fields: Boolean, val containerID: Int?, val bounds: Bounds?)
    data class Result(val before: Match, val after: Match, val absentAfter: Boolean) {
        val context: Boolean get() = before.context || after.context
        val beforeMatches: Boolean get() = before.context && before.fields
        val afterMatches: Boolean get() = after.context && after.fields && absentAfter
        val observedBounds: Bounds? get() = if (afterMatches) after.bounds else before.bounds ?: after.bounds
    }
    private val missing = Match(false, false, null, null)

    fun evaluate(nodes: List<Node>, rootID: Int, viewport: Bounds, before: Target, after: Target,
        absentAfterFields: List<String>): Result {
        require(nodes.size in 1..512 && nodes.map { it.id }.distinct().size == nodes.size)
        require(!viewport.empty && absentAfterFields.size <= 12)
        val byID = nodes.associateBy { it.id }
        val root = requireNotNull(byID[rootID])
        require(root.parentID == null && nodes.count { it.parentID == null } == 1)
        nodes.forEach { node ->
            require(node.parentID == null || node.parentID in byID)
            var current: Node? = node
            val seen = HashSet<Int>()
            while (current != null) {
                require(seen.add(current.id) && seen.size <= 64) { "AX_TREE_CYCLE_OR_DEPTH" }
                current = current.parentID?.let(byID::get)
            }
        }
        fun descendant(node: Node, ancestorID: Int): Boolean {
            var current: Node? = node
            while (current != null) {
                if (current.id == ancestorID) return true
                current = current.parentID?.let(byID::get)
            }
            return false
        }
        fun match(target: Target): Match {
            require(target.anchor.length in 1..255 && target.fields.size in 1..12 &&
                target.fields.all { it.length in 1..255 } && target.fields.distinct().size == target.fields.size)
            // Ambiguity is not resolved by picking the first visible occurrence.
            val anchor = nodes.singleOrNull { it.text == target.anchor } ?: return missing
            if (!anchor.visible || !viewport.contains(anchor.bounds)) return missing
            var container: Node? = anchor
            while (container != null && container.id != rootID &&
                !(container.clickable || container.collectionItem)) {
                // Never promote a list/root enclosing multiple rows to the target row.
                if (container.collectionContainer) return missing
                container = container.parentID?.let(byID::get)
            }
            val row = container ?: return missing
            if (row.id == rootID || row.collectionContainer || !row.visible ||
                !viewport.contains(row.bounds) || !row.bounds.contains(anchor.bounds) ||
                row.bounds.height * 4 > viewport.height * 3) return missing
            val subtree = nodes.filter { descendant(it, row.id) }
            require(subtree.size <= 128) { "AX_TARGET_SUBTREE_OVERFLOW" }
            val fields = target.fields.all { value ->
                val field = subtree.singleOrNull { it.text == value }
                field != null && field.visible && row.bounds.contains(field.bounds) && viewport.contains(field.bounds)
            }
            return Match(true, fields, row.id, row.bounds)
        }
        // Absence is only a secondary visible-window witness. Delete still requires the
        // independently recorded complete Paging dataset and exact target/control draw.
        val absent = absentAfterFields.all { value -> nodes.none { it.text == value && it.visible } }
        return Result(match(before), match(after), absent)
    }
}
