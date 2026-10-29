package com.routegenie.spike.schema

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.json.JsonFileType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LeakHunter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.EditorTextField
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import com.routegenie.Limits
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import java.lang.ref.WeakReference

/** Spike S1: can the IDE's own JSON Schema support validate a body editor that is not a project file? */
class JsonSchemaEmbeddedSpikeTest : BasePlatformTestCase() {
    private val ep = ExtensionPointName.create<JsonSchemaProviderFactory>("JavaScript.JsonSchema.ProviderFactory")

    override fun setUp() {
        super.setUp()
        // The squiggles come from this inspection, so it must be enabled in the test profile (it is on by default in the IDE).
        myFixture.enableInspections(JsonSchemaComplianceInspection::class.java)
    }

    private fun register(parent: Disposable, appliesTo: (VirtualFile) -> Boolean): SpikeSchemaFactory {
        val factory = SpikeSchemaFactory(SpikeSchemas.schemaFile(), appliesTo)
        ep.point.registerExtension(factory, parent)
        project.getService(JsonSchemaService::class.java).reset()
        return factory
    }

    private fun problems(infos: List<HighlightInfo>): List<String> =
        infos.filter { it.severity >= HighlightSeverity.WARNING }.mapNotNull { it.description }

    private fun highlight(editor: Editor): List<HighlightInfo> {
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)!!
        return CodeInsightTestFixtureImpl.instantiateAndRun(psi, editor, intArrayOf(), false)
    }

    private fun body(name: String, text: String) = LightVirtualFile(name, JsonFileType.INSTANCE, text)

    fun `test real file gets schema errors`() {
        register(testRootDisposable) { it.name == "body.json" }
        myFixture.configureByText("body.json", """{"age": -1, "extra": true}""")
        val found = problems(myFixture.doHighlighting())
        println("S1 REAL FILE: $found")
        assertTrue(found.toString(), found.any { it.contains("name") })
    }

    fun `test in-memory light virtual file gets schema errors and completion`() {
        register(testRootDisposable) { it is LightVirtualFile && it.name == "body.json" }
        myFixture.configureFromExistingVirtualFile(body("body.json", """{"age": "x"}"""))
        val found = problems(myFixture.doHighlighting())
        println("S1 LVF: $found")
        assertTrue(found.toString(), found.any { it.contains("Incompatible types") })

        myFixture.configureFromExistingVirtualFile(body("body.json", """{""}"""))
        myFixture.editor.caretModel.moveToOffset(2)
        myFixture.completeBasic()
        val lookup = myFixture.lookupElementStrings.orEmpty()
        println("S1 LVF completion: $lookup")
        assertTrue(lookup.toString(), lookup.containsAll(listOf("name", "age")))
    }

    fun `test editor made with EditorFactory over a document gets schema errors`() {
        register(testRootDisposable) { it is LightVirtualFile && it.name == "embedded.json" }
        val found = runInEdtAndGet {
            val lvf = body("embedded.json", """{"age": -5}""")
            val doc = FileDocumentManager.getInstance().getDocument(lvf)!!
            val ed = EditorFactory.getInstance().createEditor(doc, project, lvf, false)
            try {
                problems(highlight(ed))
            } finally {
                EditorFactory.getInstance().releaseEditor(ed)
            }
        }
        println("S1 EditorFactory editor: $found")
        assertTrue(found.toString(), found.any { it.contains("name") })
        assertTrue(found.toString(), found.isNotEmpty())
    }

    fun `test EditorTextField created editor gets schema errors`() {
        register(testRootDisposable) { it is LightVirtualFile && it.name == "field.json" }
        val found = runInEdtAndGet {
            val doc = FileDocumentManager.getInstance().getDocument(body("field.json", """{"age": "x"}"""))!!
            val field = EditorTextField(doc, project, JsonFileType.INSTANCE, false, false)
            field.addNotify()
            try {
                val ed = field.editor
                println("S1 EditorTextField editor=$ed file=${(ed as? com.intellij.openapi.editor.ex.EditorEx)?.virtualFile}")
                problems(highlight(ed!!))
            } finally {
                field.removeNotify()
            }
        }
        println("S1 EditorTextField: $found")
        assertTrue(found.toString(), found.any { it.contains("Incompatible types") })
    }

    fun `test value constraint violations are reported`() {
        register(testRootDisposable) { it is LightVirtualFile && it.name == "value.json" }
        myFixture.configureFromExistingVirtualFile(body("value.json", """{"name": "a", "age": -5}"""))
        val found = problems(myFixture.doHighlighting())
        println("S1 VALUE: $found")
        assertTrue(found.toString(), found.isNotEmpty())
    }

    fun `test without a registered provider no schema errors appear`() {
        myFixture.configureByText("body.json", """{"age": -1, "extra": true}""")
        assertTrue(problems(myFixture.doHighlighting()).isEmpty())
    }

    fun `test time to create an editor and attach a schema`() {
        val parent = Disposer.newDisposable("spike-timing")
        try {
            val result = PerfBudget.measure("spike-s1-editor-create-attach-highlight", Limits.BUDGET_WINDOW_OPEN_MS, warmups = 3, runs = 9) {
                runInEdtAndGet {
                    val run = Disposer.newDisposable(parent, "run")
                    register(run) { it is LightVirtualFile && it.name == "timing.json" }
                    val lvf = body("timing.json", """{"age": "x"}""")
                    val doc = FileDocumentManager.getInstance().getDocument(lvf)!!
                    val ed = EditorFactory.getInstance().createEditor(doc, project, lvf, false)
                    highlight(ed)
                    EditorFactory.getInstance().releaseEditor(ed)
                    Disposer.dispose(run)
                }
            }
            println("S1 TIMING median=${result.medianMs} ms best=${result.bestMs} ms")
        } finally {
            Disposer.dispose(parent)
        }
    }

    fun `test editor and provider are released after disposal`() {
        val run = Disposer.newDisposable("spike-leak")
        var factory: SpikeSchemaFactory? = register(run) { it is LightVirtualFile && it.name == "leak.json" }
        val refs = runInEdtAndGet {
            val lvf = body("leak.json", """{"age": "x"}""")
            val doc = FileDocumentManager.getInstance().getDocument(lvf)!!
            val ed = EditorFactory.getInstance().createEditor(doc, project, lvf, false)
            assertTrue(problems(highlight(ed)).isNotEmpty())
            val r = listOf("editor" to WeakReference<Any>(ed), "document" to WeakReference<Any>(doc))
            EditorFactory.getInstance().releaseEditor(ed)
            r
        }
        Disposer.dispose(run)
        project.getService(JsonSchemaService::class.java).reset()
        assertFalse("provider must be gone from the extension point", ep.extensionList.any { it === factory })
        val factoryRef = WeakReference<Any>(factory)
        factory = null
        // Fails with the reference path when anything static, a listener or a cache still reaches the released editor.
        LeakHunter.checkLeak(LeakHunter.allRoots(), com.intellij.openapi.editor.impl.EditorImpl::class.java) { it.virtualFile?.name == "leak.json" }
        refs.forEach { (label, ref) -> LeakProbe.assertCollected(label, ref) }
        LeakProbe.assertCollected("provider factory", factoryRef)
    }
}
