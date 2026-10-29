package com.routegenie

import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** plugin.xml and Ids.kt must describe the same set of identifiers; neither can drift without a test failing. */
class IdsConsistencyTest {
    private val pluginXml: Document = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(ProjectFiles.mainResources, "META-INF/plugin.xml"))

    private fun attributes(tag: String, attribute: String): Set<String> {
        val nodes = pluginXml.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { (nodes.item(it) as Element).getAttribute(attribute).ifEmpty { null } }.toSet()
    }

    @Test
    fun `tool windows match`() = assertEquals(Ids.TOOL_WINDOWS, attributes("toolWindow", "id"))

    @Test
    fun `notification groups match`() = assertEquals(Ids.NOTIFICATION_GROUPS, attributes("notificationGroup", "id"))

    @Test
    fun `actions match`() = assertEquals(Ids.ACTIONS, attributes("action", "id"))

    @Test
    fun `extension points match`() = assertEquals(Ids.EXTENSION_POINTS, attributes("extensionPoint", "qualifiedName"))

    @Test
    fun `every class named in plugin xml exists`() {
        val names = attributes("toolWindow", "factoryClass") + attributes("action", "class") + attributes("extensionPoint", "interface")
        assertTrue("plugin.xml names no classes?", names.isNotEmpty())
        for (name in names) Class.forName(name, false, javaClass.classLoader)
    }

    @Test
    fun `the plugin id is declared exactly once`() {
        // It lives only in plugin.xml; Kotlin code never needs it.
        assertEquals(1, pluginXml.getElementsByTagName("id").length)
    }
}
