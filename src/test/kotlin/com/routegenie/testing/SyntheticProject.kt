package com.routegenie.testing

import java.io.File

/**
 * Generates a synthetic Python project for benchmarks. Everything is ASCII. Each file has a few constants and
 * f-strings (so a value evaluator has something to do) and `routesPerFile` lines of the form `# route /mN/rK`, which
 * [FakeRouteProvider] turns into endpoints, next to FastAPI-looking decorators for code that wants realistic text.
 */
object SyntheticProject {
    data class SyntheticFile(val path: String, val text: String)

    fun files(count: Int, routesPerFile: Int = 3, filesPerPackage: Int = 20): List<SyntheticFile> {
        require(count >= 0 && routesPerFile >= 0 && filesPerPackage > 0)
        return (0 until count).map { n ->
            val pkg = "pkg${n / filesPerPackage}"
            SyntheticFile("$pkg/module$n.py", fileText(n, routesPerFile))
        }
    }

    fun fileText(n: Int, routesPerFile: Int): String = buildString {
        append("from fastapi import APIRouter\n\n")
        append("router = APIRouter()\n")
        append("PREFIX = \"/m$n\"\n")
        append("VERSION = \"v$n\"\n\n")
        for (k in 0 until routesPerFile) {
            append("# route /m$n/r$k\n")
            append("@router.get(f\"{PREFIX}/r$k/{VERSION}\")\n")
            append("def handler_${n}_$k(item_id: int):\n")
            append("    return {\"id\": item_id, \"module\": $n}\n\n")
        }
    }

    /** Writes the files under [dir] (for tests that want a real directory instead of the in-memory fixture). */
    fun writeTo(dir: File, files: List<SyntheticFile>) {
        for (file in files) {
            val target = File(dir, file.path)
            target.parentFile.mkdirs()
            target.writeText(file.text, Charsets.US_ASCII)
        }
    }
}
