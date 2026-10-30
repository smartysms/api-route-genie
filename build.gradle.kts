import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// The version comes from gradle.properties (or -Pversion=... in the release workflow).
group = "com.routegenie"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformPath = providers.gradleProperty("platformPath")
        if (platformPath.isPresent) {
            local(platformPath.get())
        } else {
            // Fallback when no local IDE is configured: download PyCharm from JetBrains.
            pycharm(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("PythonCore")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// The 262 platform jars are Java 25 class files (major version 69), so the plugin must target 25 as well.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

val branding = listOf("productName", "productShortName", "productDir", "repoSlug")
    .associateWith { providers.gradleProperty(it).get() }

tasks.processResources {
    inputs.properties(branding)
    filesMatching("messages/Branding.properties") {
        filter(mapOf("tokens" to branding), ReplaceTokens::class.java)
    }
}

// Rewrites the product name in the Markdown docs after it was changed in gradle.properties. The previously applied
// values are remembered in .branding-applied, so the old text does not have to be known.
tasks.register("syncDocs") {
    group = "documentation"
    description = "Updates README.md, CHANGELOG.md and docs/*.md to the branding in gradle.properties"
    val marker = layout.projectDirectory.file(".branding-applied").asFile
    val docs = fileTree(layout.projectDirectory) { include("README.md", "CHANGELOG.md", "docs/**/*.md") }
    doLast {
        val previous = if (marker.exists()) {
            marker.readLines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        } else {
            branding
        }
        // The full name contains the short name, so it has to be replaced first.
        val order = listOf("productName", "productShortName", "repoSlug", "productDir")
        docs.forEach { file ->
            var text = file.readText()
            order.forEach { key -> text = text.replace(previous.getValue(key), branding.getValue(key)) }
            file.writeText(text)
        }
        marker.writeText(branding.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}

intellijPlatform {
    // name and vendor are patched into plugin.xml from the branding values; everything else stays in plugin.xml.
    pluginConfiguration {
        name = branding.getValue("productName")
        vendor {
            name = branding.getValue("productName")
            url = "https://github.com/${branding.getValue("repoSlug")}"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            // Explicit null: without it the plugin would default to "262.*".
            untilBuild = provider { null }
        }
    }
    // Only needed for the Settings search index; slow and pointless for a plugin with a single settings page.
    buildSearchableOptions = false
}

// The newest section of CHANGELOG.md (the part under the first "## " heading) is the plugin's change notes: the plugin page
// and the update list in the IDE show it, and so does the GitHub release. One text, written by hand, in one file.
fun newestChangelogLines(): List<String> {
    val lines = layout.projectDirectory.file("CHANGELOG.md").asFile.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return emptyList()
    val next = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
    return lines.subList(start + 1, if (next < 0) lines.size else start + 1 + next)
}

fun changelogHtml(lines: List<String>): String {
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun inline(text: String) = escape(text).replace(Regex("`([^`]+)`"), "<code>$1</code>")
    val out = StringBuilder()
    var inList = false
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("- ")) {
            if (!inList) { out.append("<ul>"); inList = true }
            out.append("<li>").append(inline(line.removePrefix("- "))).append("</li>")
        } else if (line.isNotEmpty()) {
            if (inList) { out.append("</ul>"); inList = false }
            out.append("<p>").append(inline(line)).append("</p>")
        }
    }
    if (inList) out.append("</ul>")
    return out.toString()
}

intellijPlatform.pluginConfiguration.changeNotes = provider { changelogHtml(newestChangelogLines()) }

// Used by the release workflow: the same notes as HTML for updatePlugins.xml and as Markdown for the GitHub release.
tasks.register("exportChangeNotes") {
    group = "documentation"
    description = "Writes the newest CHANGELOG.md section to build/change-notes.html and build/release-notes.md"
    val html = layout.buildDirectory.file("change-notes.html")
    val markdown = layout.buildDirectory.file("release-notes.md")
    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    inputs.file(changelog)
    outputs.files(html, markdown)
    doLast {
        val lines = newestChangelogLines()
        html.get().asFile.writeText(changelogHtml(lines))
        markdown.get().asFile.writeText(lines.joinToString("\n").trim() + "\n")
    }
}
