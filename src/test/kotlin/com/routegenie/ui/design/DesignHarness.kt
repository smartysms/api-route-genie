package com.routegenie.ui.design

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.routegenie.testing.ProjectFiles
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.FontUIResource

/**
 * A look to test against. [overrides] are Swing color keys written on top of the installed look and feel, which is exactly what a
 * third-party theme (Dracula, One Dark, Material) is: a base plus a list of changed keys. The values are approximations of those
 * themes' main colors, enough to see how the window behaves on them; they are not copied theme files.
 */
internal class ThemeSample(val id: String, val dark: Boolean, val overrides: Map<String, Int>)

internal object ThemeSamples {
    private class Colors(
        val background: Int, val foreground: Int, val selection: Int, val selectionInactive: Int, val selectionText: Int,
        val hover: Int, val border: Int, val secondary: Int, val link: Int, val focus: Int,
    )

    private fun keys(c: Colors) = mapOf(
        "Tree.background" to c.background,
        "Tree.foreground" to c.foreground,
        "Tree.selectionBackground" to c.selection,
        "Tree.selectionInactiveBackground" to c.selectionInactive,
        "Tree.selectionForeground" to c.selectionText,
        "Tree.selectionInactiveForeground" to c.selectionText,
        "List.background" to c.background,
        "List.foreground" to c.foreground,
        "List.hoverBackground" to c.hover,
        "Tree.hoverBackground" to c.hover,
        "Panel.background" to c.background,
        "ScrollPane.background" to c.background,
        "Viewport.background" to c.background,
        "Label.foreground" to c.foreground,
        "Label.disabledForeground" to c.secondary,
        "Component.infoForeground" to c.secondary,
        "Borders.color" to c.border,
        "Component.borderColor" to c.border,
        "Separator.foreground" to c.border,
        "Link.activeForeground" to c.link,
        "Component.focusColor" to c.focus,
        "TextField.background" to c.background,
        "TextField.foreground" to c.foreground,
        "TabbedPane.background" to c.background,
        "TabbedPane.contentAreaColor" to c.border,
        "DefaultTabs.background" to c.background,
        "DefaultTabs.borderColor" to c.border,
        "DefaultTabs.hoverBackground" to c.hover,
        "DefaultTabs.underlinedTabBackground" to c.background,
        "ToolBar.background" to c.background,
    )

    /** New UI light and New UI dark, the defaults a current IDE shows; the test IDE has no installed theme to read them from. */
    val LIGHT = ThemeSample("light", false, keys(Colors(0xFFFFFF, 0x000000, 0xD4E2FF, 0xDFE1E5, 0x000000, 0xF2F3F7, 0xEBECF0, 0x6F737A, 0x315FBD, 0x3574F0)))
    val DARK = ThemeSample("dark", true, keys(Colors(0x2B2D30, 0xDFE1E5, 0x2E436E, 0x393B40, 0xDFE1E5, 0x3A3D42, 0x43454A, 0x868A91, 0x548AF7, 0x548AF7)))

    /** The classic Darcula theme exactly as the installed look and feel defines it. */
    val DARCULA = ThemeSample("darcula", true, emptyMap())
    val DRACULA = ThemeSample("dracula", true, keys(Colors(0x282A36, 0xF8F8F2, 0x44475A, 0x3A3C4E, 0xF8F8F2, 0x343746, 0x44475A, 0xA0A6C8, 0x8BE9FD, 0xBD93F9)))
    val ONE_DARK = ThemeSample("onedark", true, keys(Colors(0x282C34, 0xABB2BF, 0x3E4451, 0x31353F, 0xFFFFFF, 0x2C313A, 0x181A1F, 0x8E95A3, 0x61AFEF, 0x528BFF)))
    val MATERIAL = ThemeSample("material", true, keys(Colors(0x263238, 0xEEFFFF, 0x314549, 0x2A3A40, 0xFFFFFF, 0x2D3C43, 0x1C262B, 0x8FA6B2, 0x82AAFF, 0x80CBC4)))

    val ALL = listOf(LIGHT, DARK, DARCULA, DRACULA, ONE_DARK, MATERIAL)
}

/**
 * Runs a block under a [ThemeSample] and puts everything back afterwards, so tests that follow see the original look. The test
 * IDE starts with plain Swing defaults (Metal widgets, bold fonts), which no user ever sees, so the IntelliJ look and feel is
 * installed for the block; if that is not possible, plain fonts and the platform's tree icons are set so the images stay usable
 * (the notes file says which path was taken).
 */
internal object ThemeSwitch {
    private val FONT_KEYS = listOf(
        "Label.font", "Tree.font", "List.font", "Table.font", "TextField.font", "TextArea.font", "Button.font", "ComboBox.font",
        "ToolBar.font", "Panel.font", "TabbedPane.font", "ToolTip.font", "EditorPane.font", "CheckBox.font", "Viewport.font",
    )

    fun <T> with(sample: ThemeSample, notes: MutableList<String>, block: () -> T): T {
        val savedLaf = UIManager.getLookAndFeel()
        val originalDark = !JBColor.isBright()
        val touched = ArrayList<String>()
        var installed = false
        try {
            installed = runCatching { UIManager.setLookAndFeel(if (sample.dark) DarculaLaf() else IntelliJLaf()) }
                .onFailure { notes += "${sample.id}: IntelliJ look and feel not installable (${it.javaClass.simpleName}: ${it.message})" }
                .isSuccess
            notes += "${sample.id}: swing laf ${UIManager.getLookAndFeel().name}, installed=$installed"
            JBColor.setDark(sample.dark)
            IconLoader.setUseDarkIcons(sample.dark)
            IconLoader.clearCacheInTests()
            if (!installed) {
                val plain = FontUIResource(Font(Font.SANS_SERIF, Font.PLAIN, BASE_FONT_SIZE))
                FONT_KEYS.forEach { put(it, plain, touched) }
                put("Tree.expandedIcon", AllIcons.General.ArrowDown, touched)
                put("Tree.collapsedIcon", AllIcons.General.ArrowRight, touched)
            }
            sample.overrides.forEach { (key, rgb) -> put(key, ColorUIResource(rgb), touched) }
            return block()
        } finally {
            touched.forEach { UIManager.put(it, null) }
            if (installed) runCatching { UIManager.setLookAndFeel(savedLaf) }
            JBColor.setDark(originalDark)
            IconLoader.setUseDarkIcons(originalDark)
            IconLoader.clearCacheInTests()
        }
    }

    private fun put(key: String, value: Any, touched: MutableList<String>) {
        UIManager.put(key, value)
        touched += key
    }

    private const val BASE_FONT_SIZE = 13
}

/** Painting of Swing components into images, without a window. */
internal object Snapshots {
    val dir: File = File(ProjectFiles.root, "build/reports/routegenie/design").also { it.mkdirs() }

    fun layoutAll(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach { layoutAll(it) }
        }
    }

    /** Lays [component] out at [width] x [height] logical pixels and paints it at [scale] times that size. */
    fun render(component: JComponent, width: Int, height: Int, scale: Double = 1.0, background: Color? = null): BufferedImage {
        component.setBounds(0, 0, width, height)
        layoutAll(component)
        layoutAll(component)
        val image = BufferedImage((width * scale).toInt(), (height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = background ?: Palette.surface
            g.fillRect(0, 0, image.width, image.height)
            g.scale(scale, scale)
            component.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    fun write(image: BufferedImage, name: String): File {
        val file = File(dir, "$name.png")
        ImageIO.write(image, "png", file)
        return file
    }

    /** Pixels that differ from the first pixel (taken as the background); a blank paint has none. */
    fun inkPixels(image: BufferedImage): Int {
        val background = image.getRGB(0, 0)
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) != background) count++
        return count
    }
}
