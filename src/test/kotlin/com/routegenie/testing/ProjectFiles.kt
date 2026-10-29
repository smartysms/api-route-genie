package com.routegenie.testing

import java.io.File
import java.util.Properties

/** Access to the project's own files from tests; Gradle runs tests with the project directory as working directory. */
object ProjectFiles {
    val root: File = File(System.getProperty("user.dir"))
    val mainKotlin: File = File(root, "src/main/kotlin")
    val mainResources: File = File(root, "src/main/resources")

    fun kotlinSources(dir: File = mainKotlin): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    fun properties(file: File): Properties =
        file.reader(Charsets.UTF_8).use { reader -> Properties().apply { load(reader) } }

    fun gradleProperties(): Properties = properties(File(root, "gradle.properties"))

    fun relative(file: File): String = file.relativeTo(root).path.replace('\\', '/')
}
