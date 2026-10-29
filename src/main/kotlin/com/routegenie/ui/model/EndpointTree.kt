package com.routegenie.ui.model

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework

/** A node of the endpoint tree. Immutable once built; [parent] and [index] are set by the group that owns the node. */
sealed class Node {
    var parent: Group? = null
        internal set

    /** Position among the parent's children, so the Swing model answers getIndexOfChild in O(1). */
    var index: Int = 0
        internal set
}

enum class GroupKind { FRAMEWORK, PATH, FILE, PREFIX }

/** [key] identifies the group across rebuilds (to restore which groups were open); [count] is the number of endpoints below. */
class Group(val key: String, val label: String, val kind: GroupKind, val children: List<Node>) : Node() {
    val count: Int = children.sumOf { if (it is Group) it.count else 1 }

    init {
        children.forEachIndexed { i, child ->
            child.parent = this
            child.index = i
        }
    }
}

class Leaf(val endpoint: Endpoint) : Node()

/** The tree for one list of endpoints; the root is never shown. */
class EndpointTree(val root: Group) {
    val count: Int get() = root.count

    /** The leaf of the endpoint with this [Endpoint.key], or null; a walk over the tree, used only for rare lookups. */
    fun findLeaf(key: String): Leaf? {
        val stack = ArrayDeque<Group>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val group = stack.removeLast()
            for (child in group.children) when (child) {
                is Leaf -> if (child.endpoint.key == key) return child
                is Group -> stack.add(child)
            }
        }
        return null
    }

    /** All groups, parents before children. */
    fun groups(): Sequence<Group> = sequence {
        val stack = ArrayDeque<Group>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val group = stack.removeLast()
            yield(group)
            for (i in group.children.indices.reversed()) (group.children[i] as? Group)?.let { stack.add(it) }
        }
    }

    companion object {
        val EMPTY = EndpointTree(Group("", "", GroupKind.PATH, emptyList()))

        /** [endpoints] must already be in [EndpointIndex.ORDER]; the order inside groups follows from it. */
        fun build(endpoints: List<Endpoint>, mode: GroupMode): EndpointTree = EndpointTree(
            when (mode) {
                GroupMode.NONE -> Group("", "", GroupKind.PATH, endpoints.map { Leaf(it) })
                GroupMode.LOGICAL -> logical(endpoints)
                GroupMode.BY_FILE -> byFile(endpoints)
                GroupMode.BY_PREFIX -> byPrefix(endpoints)
            },
        )

        private fun logical(endpoints: List<Endpoint>): Group {
            val byFramework = LinkedHashMap<Framework, MutableList<Endpoint>>()
            for (e in endpoints) byFramework.getOrPut(e.framework) { ArrayList() } += e
            val groups = byFramework.entries.sortedBy { it.key.ordinal }.map { (framework, list) ->
                val key = "framework:${framework.name}"
                val trie = Trie("")
                for (e in list) trie.insert(segmentsOf(e.path).dropLast(1), e)
                Group(key, framework.displayName, GroupKind.FRAMEWORK, trie.toNodes(key))
            }
            return Group("", "", GroupKind.PATH, groups)
        }

        private fun byFile(endpoints: List<Endpoint>): Group {
            val byFile = LinkedHashMap<String, MutableList<Endpoint>>()
            for (e in endpoints) byFile.getOrPut(e.source.path) { ArrayList() } += e
            val groups = byFile.entries.sortedBy { it.key }.map { (path, list) ->
                val inFile = list.sortedWith(compareBy<Endpoint> { it.source.line }.thenBy { it.method.ordinal })
                Group("file:$path", path, GroupKind.FILE, inFile.map { Leaf(it) })
            }
            return Group("", "", GroupKind.PATH, groups)
        }

        private fun byPrefix(endpoints: List<Endpoint>): Group {
            val byPrefix = LinkedHashMap<String, MutableList<Endpoint>>()
            for (e in endpoints) byPrefix.getOrPut(segmentsOf(e.path).firstOrNull()?.let { "/$it" } ?: "/") { ArrayList() } += e
            val groups = byPrefix.entries.sortedBy { it.key }.map { (prefix, list) ->
                Group("prefix:$prefix", prefix, GroupKind.PREFIX, list.map { Leaf(it) })
            }
            return Group("", "", GroupKind.PATH, groups)
        }

        fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /** Path hierarchy under a framework: endpoints sit at the node of their path without the last segment. */
        private class Trie(val segment: String) {
            val leaves = ArrayList<Endpoint>()
            val children = LinkedHashMap<String, Trie>()

            fun insert(segments: List<String>, endpoint: Endpoint) {
                var node = this
                for (s in segments) node = node.children.getOrPut(s) { Trie(s) }
                node.leaves += endpoint
            }

            /** Leaves first (they are the shorter paths), then child groups. */
            fun toNodes(parentKey: String): List<Node> {
                val out = ArrayList<Node>(leaves.size + children.size)
                leaves.mapTo(out) { Leaf(it) }
                for (child in children.values) out += child.toNode(parentKey)
                return out
            }

            /**
             * A group whose only child is another group is merged into it (api + v1 shows as /api/v1), and a group
             * holding a single endpoint is replaced by that endpoint, so the tree has no chains of one-item folders.
             */
            private fun toNode(parentKey: String): Node {
                var label = "/$segment"
                var node = this
                while (node.leaves.isEmpty() && node.children.size == 1) {
                    node = node.children.values.first()
                    label += "/${node.segment}"
                }
                val key = "$parentKey$label"
                val nodes = node.toNodes(key)
                if (nodes.size == 1 && nodes[0] is Leaf) return nodes[0]
                return Group(key, label, GroupKind.PATH, nodes)
            }
        }
    }
}
