package com.routegenie.model

/**
 * A position in a project file. [path] is relative to the project base directory and uses '/' as separator, so a
 * snapshot never holds a VirtualFile or PSI element. [line] is 1-based, [offset] 0-based; both are re-read on every
 * refresh and clamped when the file changed meanwhile.
 */
data class SourceRef(val path: String, val offset: Int, val line: Int) {
    init {
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(line >= 1) { "line is 1-based: $line" }
    }

    override fun toString(): String = "$path:$line"
}
