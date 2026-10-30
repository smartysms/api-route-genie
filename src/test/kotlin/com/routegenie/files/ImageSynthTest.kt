package com.routegenie.files

import com.routegenie.Limits
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.random.Random

class ImageSynthTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun headless() {
            System.setProperty("java.awt.headless", "true")
        }

        /** Largest gap below the target each format can leave: chunk or segment framing that cannot be split further. */
        val MAX_UNDERSHOOT = mapOf(ImageFormat.PNG to 11L, ImageFormat.JPEG to 3L, ImageFormat.GIF to 2L)

        val GRID = listOf(1 to 1, 1 to 500, 500 to 1, 64 to 64, 333 to 77, 2048 to 2048)
    }

    class Decoded(val formatName: String, val width: Int, val height: Int, val distinctColors: Int)

    private fun decode(bytes: ByteArray): Decoded {
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            assertTrue("no ImageIO reader recognises the bytes", readers.hasNext())
            val reader = readers.next()
            try {
                reader.input = stream
                val image = reader.read(0)
                val seen = HashSet<Int>()
                val step = maxOf(1, image.width * image.height / 20_000)
                var i = 0
                for (y in 0 until image.height) for (x in 0 until image.width) if (i++ % step == 0) seen.add(image.getRGB(x, y))
                return Decoded(reader.formatName, image.width, image.height, seen.size)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun make(format: ImageFormat, w: Int, h: Int, seed: Int, target: Long? = null) =
        ImageSynth.generate(ImageSpec(format, w, h, target), Random(seed))

    @Test
    fun `every format and size decodes to the exact size, the right format and not one flat colour`() {
        for (format in ImageFormat.entries) for ((w, h) in GRID) {
            val file = make(format, w, h, seed = w * 31 + h)
            val d = decode(file.bytes)
            val label = "$format ${w}x$h"
            assertEquals(label, format.imageIoName, d.formatName.lowercase())
            assertEquals(label, w, d.width)
            assertEquals(label, h, d.height)
            assertEquals(label, w, file.width)
            assertEquals(label, h, file.height)
            assertEquals(label, format.mime, file.mime)
            assertEquals(label, format.extension, file.extension)
            if (w * h >= 2) assertTrue("$label is one flat colour", d.distinctColors > 1)
        }
    }

    @Test
    fun `images look like pictures not noise`() {
        for (format in ImageFormat.entries) {
            val d = decode(make(format, 256, 256, 5).bytes)
            assertTrue("${format} has ${d.distinctColors} colours", d.distinctColors >= 30)
        }
    }

    @Test
    fun `a seed gives identical bytes and other seeds differ`() {
        for (format in ImageFormat.entries) {
            val a = make(format, 64, 64, 7).bytes
            assertArrayEquals("$format same seed", a, make(format, 64, 64, 7).bytes)
            val others = (8..12).map { make(format, 64, 64, it).bytes.toList() }.toSet()
            assertEquals("$format different seeds", 5, others.size)
            assertFalse("$format seed 7 vs 8", a.toList() == others.first())
        }
    }

    @Test
    fun `padding is deterministic too`() {
        for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF)) {
            val plain = make(format, 64, 64, 3).bytes.size.toLong()
            assertArrayEquals(make(format, 64, 64, 3, plain + 5000).bytes, make(format, 64, 64, 3, plain + 5000).bytes)
        }
    }

    @Test
    fun `target size is reached within the framing and never exceeded`() {
        val report = StringBuilder()
        for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF)) {
            var worst = 0L
            for ((w, h) in listOf(64 to 64, 333 to 77, 256 to 256, 1 to 500)) {
                val plain = make(format, w, h, 11).bytes.size.toLong()
                for (extra in listOf(0L, 1L, 2L, 3L, 4L, 5L, 11L, 12L, 13L, 100L, 1_000L, 65_533L, 65_537L, 70_000L, 200_000L, 1_000_000L)) {
                    val target = plain + extra
                    val file = make(format, w, h, 11, target)
                    val error = target - file.bytes.size
                    worst = maxOf(worst, error)
                    assertTrue("$format ${w}x$h target $target got ${file.bytes.size}", error >= 0)
                    assertTrue("$format ${w}x$h target $target got ${file.bytes.size}: undershoot $error", error <= MAX_UNDERSHOOT.getValue(format))
                    if (extra >= 12 || format != ImageFormat.PNG) {
                        val d = decode(file.bytes)
                        assertEquals(w, d.width)
                        assertEquals(h, d.height)
                    }
                }
            }
            report.append("$format worst undershoot $worst bytes (bound ${MAX_UNDERSHOOT[format]}); ")
        }
        println("targetBytes accuracy: $report")
    }

    @Test
    fun `a padded file still decodes as the same picture`() {
        for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF)) {
            val plain = make(format, 100, 60, 2)
            val padded = make(format, 100, 60, 2, plain.bytes.size + 150_000L)
            val a = ImageIO.read(ByteArrayInputStream(plain.bytes))
            val b = ImageIO.read(ByteArrayInputStream(padded.bytes))
            for (y in 0 until 60) for (x in 0 until 100) assertEquals("$format $x,$y", a.getRGB(x, y), b.getRGB(x, y))
        }
    }

    @Test
    fun `a target below the smallest file is reported with the real minimum`() {
        for (format in ImageFormat.entries) {
            val plain = make(format, 64, 64, 1).bytes.size.toLong()
            val e = runCatching { make(format, 64, 64, 1, 1L) }.exceptionOrNull()
            assertTrue("$format: $e", e is ImageSizeUnreachable)
            e as ImageSizeUnreachable
            assertTrue("$format min ${e.min} vs plain $plain", e.min <= plain && e.min > 1)
            assertEquals(1L, e.requested)
            assertTrue(e.max >= e.min)
            assertEquals("files.error.size_unreachable", e.bundleKey)
            if (format != ImageFormat.JPEG) {
                val tooSmall = runCatching { make(format, 64, 64, 1, plain - 1) }.exceptionOrNull()
                assertTrue("$format: $tooSmall", tooSmall is ImageSizeUnreachable)
            }
        }
    }

    @Test
    fun `jpeg goes below the default size by lowering its quality`() {
        val plain = make(ImageFormat.JPEG, 333, 77, 4).bytes.size.toLong()
        val smaller = make(ImageFormat.JPEG, 333, 77, 4, plain - 200)
        assertTrue(smaller.bytes.size <= plain - 200)
        assertEquals(333, decode(smaller.bytes).width)
    }

    @Test
    fun `bmp has one legal size and says so`() {
        val exact = ImageEncodersSizes.bmp(64, 64)
        val file = make(ImageFormat.BMP, 64, 64, 1, exact)
        assertEquals(exact, file.bytes.size.toLong())
        assertEquals(exact, make(ImageFormat.BMP, 64, 64, 1).bytes.size.toLong())
        for (target in listOf(exact - 1, exact + 1, 1L)) {
            val e = runCatching { make(ImageFormat.BMP, 64, 64, 1, target) }.exceptionOrNull()
            assertTrue("bmp target $target: $e", e is ImageSizeUnreachable)
            e as ImageSizeUnreachable
            assertEquals(exact, e.min)
            assertEquals(exact, e.max)
        }
    }

    @Test
    fun `a target above the largest allowed file is a limit error`() {
        val e = runCatching { make(ImageFormat.PNG, 64, 64, 1, Limits.FILE_MAX_BYTES + 1) }.exceptionOrNull()
        assertTrue("$e", e is FileLimitExceeded)
        e as FileLimitExceeded
        assertEquals(FileLimit.BYTES, e.limit)
        assertEquals("files.error.bytes", e.limit.bundleKey)
        assertEquals(listOf<Any>(Limits.FILE_MAX_BYTES + 1, Limits.FILE_MAX_BYTES), e.args)
    }

    @Test
    fun `the largest target is reached`() {
        val file = make(ImageFormat.PNG, 64, 64, 1, Limits.FILE_MAX_BYTES)
        assertTrue(file.bytes.size <= Limits.FILE_MAX_BYTES)
        assertTrue(Limits.FILE_MAX_BYTES - file.bytes.size <= 11)
        assertEquals(64, decode(file.bytes).width)
    }

    @Test
    fun `sizes beyond the limits are rejected with a clear error`() {
        fun limit(spec: ImageSpec): FileLimit? = (runCatching { ImageSynth.generate(spec, Random(1)) }.exceptionOrNull() as? FileLimitExceeded)?.limit
        assertEquals(FileLimit.WIDTH, limit(ImageSpec(ImageFormat.PNG, Limits.IMAGE_MAX_DIMENSION + 1, 1)))
        assertEquals(FileLimit.HEIGHT, limit(ImageSpec(ImageFormat.PNG, 1, Limits.IMAGE_MAX_DIMENSION + 1)))
        assertEquals(FileLimit.PIXELS, limit(ImageSpec(ImageFormat.PNG, Limits.IMAGE_MAX_DIMENSION, Limits.IMAGE_MAX_DIMENSION)))
        assertEquals(FileLimit.BYTES, limit(ImageSpec(ImageFormat.BMP, 4096, 4096)))
        assertTrue(runCatching { ImageSynth.generate(ImageSpec(ImageFormat.PNG, 0, 5), Random(1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ImageSynth.generate(ImageSpec(ImageFormat.PNG, 5, -1), Random(1)) }.exceptionOrNull() is IllegalArgumentException)
        // a thin strip at the side limit is fine
        assertEquals(Limits.IMAGE_MAX_DIMENSION, decode(make(ImageFormat.PNG, Limits.IMAGE_MAX_DIMENSION, 1, 1).bytes).width)
    }

    @Test
    fun `defaults are used when the caller gives nothing`() {
        val spec = ImageSynth.defaultSpec(ImageFormat.JPEG)
        assertEquals(Limits.IMAGE_DEFAULT_WIDTH, spec.width)
        assertEquals(Limits.IMAGE_DEFAULT_HEIGHT, spec.height)
        assertNull(spec.targetBytes)
        assertEquals(256, ImageSpec(ImageFormat.PNG).width)
    }

    @Test
    fun `estimates cover the real size and stay in a useful range`() {
        val report = StringBuilder()
        for (format in ImageFormat.entries) for ((w, h) in listOf(64 to 64, 256 to 256, 333 to 77, 1920 to 1080)) {
            val spec = ImageSpec(format, w, h)
            val real = ImageSynth.generate(spec, Random(9)).bytes.size.toLong()
            val estimate = ImageSynth.estimateBytes(spec)
            report.append("$format ${w}x$h real=$real estimate=$estimate; ")
            assertTrue("$format ${w}x$h estimate $estimate is below the real $real", estimate >= real)
            assertTrue("$format ${w}x$h estimate $estimate is useless against $real", estimate <= real * 6 + 2_000)
            assertEquals(estimate, ImageSynth.minimumBytes(spec))
            assertTrue(ImageSynth.maximumBytes(spec) >= estimate)
        }
        println("estimates: $report")
    }

    @Test
    fun `bmp estimate is exact`() {
        for ((w, h) in listOf(1 to 1, 3 to 3, 64 to 64, 333 to 77)) {
            val spec = ImageSpec(ImageFormat.BMP, w, h)
            assertEquals(ImageSynth.estimateBytes(spec), make(ImageFormat.BMP, w, h, 1).bytes.size.toLong())
            assertEquals(ImageSynth.estimateBytes(spec), ImageSynth.maximumBytes(spec))
        }
    }

    // ---- fit -------------------------------------------------------------------------------------------------------

    private val png = ImageSpec(ImageFormat.PNG, 1000, 800)

    @Test
    fun `fit gives nothing when no image format is allowed`() {
        assertNull(ImageSynth.fit(png, emptyList()))
        assertNull(ImageSynth.fit(png, ImageFormat.parseAllowed("application/pdf")))
        assertNull(ImageSynth.fit(png, ImageFormat.parseAllowed("image/svg+xml, image/webp")))
    }

    @Test
    fun `fit keeps an allowed format and otherwise takes the first allowed one`() {
        assertEquals(ImageFormat.PNG, ImageSynth.fit(png, listOf(ImageFormat.GIF, ImageFormat.PNG))!!.format)
        assertEquals(ImageFormat.GIF, ImageSynth.fit(png, listOf(ImageFormat.GIF, ImageFormat.JPEG))!!.format)
        assertEquals(ImageFormat.JPEG, ImageSynth.fit(png, ImageFormat.parseAllowed("image/jpeg"))!!.format)
    }

    @Test
    fun `fit clamps the sides into the bounds`() {
        val all = ImageFormat.entries
        val big = ImageSynth.fit(png, all, maxWidth = 300, maxHeight = 200)!!
        assertEquals(300, big.width)
        assertEquals(200, big.height)
        val small = ImageSynth.fit(ImageSpec(ImageFormat.PNG, 10, 10), all, minWidth = 50, minHeight = 40)!!
        assertEquals(50, small.width)
        assertEquals(40, small.height)
        val capped = ImageSynth.fit(ImageSpec(ImageFormat.PNG, 100_000, 100_000), all)!!
        assertTrue(capped.width <= Limits.IMAGE_MAX_DIMENSION && capped.height <= Limits.IMAGE_MAX_DIMENSION)
        assertTrue(capped.width.toLong() * capped.height <= Limits.IMAGE_MAX_PIXELS)
        val same = ImageSynth.fit(ImageSpec(ImageFormat.JPEG, 640, 480), all)!!
        assertEquals(ImageSpec(ImageFormat.JPEG, 640, 480), same)
    }

    @Test
    fun `fit gives nothing when the bounds contradict each other`() {
        assertNull(ImageSynth.fit(png, ImageFormat.entries, minWidth = 500, maxWidth = 100))
        assertNull(ImageSynth.fit(png, ImageFormat.entries, minHeight = 500, maxHeight = 100))
        assertNull(ImageSynth.fit(png, ImageFormat.entries, minWidth = Limits.IMAGE_MAX_DIMENSION + 1))
        assertNull(ImageSynth.fit(png, ImageFormat.entries, maxBytes = 0))
    }

    @Test
    fun `a fitted spec really produces a file within maxBytes`() {
        for (format in ImageFormat.entries) for (maxBytes in listOf(1_500L, 5_000L, 50_000L, 300_000L)) {
            val fitted = ImageSynth.fit(ImageSpec(format, 1920, 1080), listOf(format), maxBytes = maxBytes)
            if (fitted == null) {
                assertTrue("$format cannot fit anything in $maxBytes yet the smallest image is smaller", ImageSynth.estimateBytes(ImageSpec(format, 1, 1)) > maxBytes)
                continue
            }
            val file = ImageSynth.generate(fitted, Random(maxBytes))
            assertTrue("$format maxBytes $maxBytes fitted $fitted real ${file.bytes.size}", file.bytes.size <= maxBytes)
            assertEquals(fitted.width, decode(file.bytes).width)
        }
    }

    @Test
    fun `fit applies the target as an upper bound and keeps a reachable target`() {
        val fitted = ImageSynth.fit(ImageSpec(ImageFormat.PNG, 1920, 1080, targetBytes = 40_000), ImageFormat.entries, maxBytes = 30_000)!!
        assertEquals(30_000L, fitted.targetBytes)
        val file = ImageSynth.generate(fitted, Random(1))
        assertTrue(file.bytes.size <= 30_000)
        assertTrue(30_000 - file.bytes.size <= 11)
    }

    @Test
    fun `bmp can be fitted by shrinking`() {
        val fitted = ImageSynth.fit(ImageSpec(ImageFormat.BMP, 1920, 1080), listOf(ImageFormat.BMP), maxBytes = 100_000)!!
        assertTrue(ImageSynth.generate(fitted, Random(1)).bytes.size <= 100_000)
    }

    // ---- speed, concurrency, memory, headless ------------------------------------------------------------------------

    @Test
    fun `speed budgets hold warm`() {
        var seed = 0
        val png = PerfBudget.assertWithin("image-png-256", Limits.BUDGET_IMAGE_PNG_256_MS, warmups = 20, runs = 15) {
            ImageSynth.generate(ImageSpec(ImageFormat.PNG, 256, 256), Random(seed++))
        }
        val jpeg = PerfBudget.assertWithin("image-jpeg-1920x1080", Limits.BUDGET_IMAGE_JPEG_FULL_HD_MS, warmups = 5, runs = 9) {
            ImageSynth.generate(ImageSpec(ImageFormat.JPEG, 1920, 1080), Random(seed++))
        }
        val others = listOf(
            "image-gif-256" to ImageSpec(ImageFormat.GIF, 256, 256),
            "image-bmp-256" to ImageSpec(ImageFormat.BMP, 256, 256),
            "image-jpeg-256" to ImageSpec(ImageFormat.JPEG, 256, 256),
            "image-png-1920x1080" to ImageSpec(ImageFormat.PNG, 1920, 1080),
            "image-gif-1920x1080" to ImageSpec(ImageFormat.GIF, 1920, 1080),
            "image-png-256-padded-1MB" to ImageSpec(ImageFormat.PNG, 256, 256, 1_000_000),
            "image-png-2048x2048" to ImageSpec(ImageFormat.PNG, 2048, 2048),
        ).map { (label, spec) -> PerfBudget.measure(label, Long.MAX_VALUE, warmups = 3, runs = 5) { ImageSynth.generate(spec, Random(seed++)) } }
        println("speed: png256 median ${"%.2f".format(png.medianMs)} ms (budget ${Limits.BUDGET_IMAGE_PNG_256_MS}), " +
            "jpeg1080p median ${"%.2f".format(jpeg.medianMs)} ms (budget ${Limits.BUDGET_IMAGE_JPEG_FULL_HD_MS}); " +
            others.joinToString("; ") { "${it.label} ${"%.2f".format(it.medianMs)} ms" })
    }

    @Test
    fun `eight threads at once give the same bytes as one`() {
        val jobs = (0 until 24).map { it to ImageFormat.entries[it % ImageFormat.entries.size] }
        val expected = jobs.map { (seed, format) -> make(format, 120, 90, seed, if (format == ImageFormat.BMP) null else 30_000L).bytes }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 6).map {
                jobs.map { (seed, format) ->
                    pool.submit(Callable { make(format, 120, 90, seed, if (format == ImageFormat.BMP) null else 30_000L).bytes })
                }
            }.flatten().map { it.get() }
            results.forEachIndexed { i, bytes -> assertArrayEquals("job ${i % jobs.size}", expected[i % jobs.size], bytes) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `nothing is retained between calls`() {
        var ref: WeakReference<ByteArray>? = null
        run { ref = WeakReference(make(ImageFormat.PNG, 512, 512, 1).bytes) }
        LeakProbe.assertCollected("generated png bytes", ref!!)
        var seed = 0
        for (format in ImageFormat.entries) {
            LeakProbe.assertNoHeapGrowth("image $format", maxGrowthBytes = 2L * 1024 * 1024, warmups = 20, iterations = 120) {
                ImageSynth.generate(ImageSpec(format, 96, 96, if (format == ImageFormat.BMP) null else 40_000L), Random(seed++))
            }
        }
    }

    @Test
    fun `no fonts, toolkit or Graphics2D are used so a runner without fonts works`() {
        val forbiddenUse = Regex("""\bGraphics2D?\b|java\.awt\.Font|\bFont\(|\bToolkit\b|java\.awt\.Color|createGraphics|FontMetrics|JFrame|java\.awt\.Frame""")
        val offenders = ProjectFiles.kotlinSources(java.io.File(ProjectFiles.mainKotlin, "com/routegenie/files")).filter {
            forbiddenUse.containsMatchIn(it.readText().lineSequence().filterNot { l -> l.trimStart().startsWith("*") || l.trimStart().startsWith("//") }.joinToString("\n"))
        }
        assertTrue("These files use fonts or Graphics: $offenders", offenders.isEmpty())
    }

    @Test
    fun `generation works with the headless flag forced on`() {
        assertEquals("true", System.getProperty("java.awt.headless"))
        assertNotNull(make(ImageFormat.JPEG, 40, 40, 1))
        if (!java.awt.GraphicsEnvironment.isHeadless()) println("note: the JVM was already started with a display; the code under test still touches no window")
    }
}

/** The exact BMP size, computed independently of the encoder so the test is not circular. */
private object ImageEncodersSizes {
    fun bmp(w: Int, h: Int): Long = 54L + ((w * 3 + 3) / 4 * 4).toLong() * h
}
