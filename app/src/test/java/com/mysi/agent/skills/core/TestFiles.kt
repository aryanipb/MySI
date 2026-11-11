package com.mysi.agent.skills.core

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.util.zip.ZipFile

/** Fixture files and the PdfBox-Android data files for JVM tests. */
object TestFiles {
    fun fixture(name: String): File =
        File(TestFiles::class.java.classLoader!!.getResource("fixtures/$name")!!.toURI())

    private var pdfAssetsReady = false

    /**
     * On Android, PdfBox reads its glyph lists and font metrics through the AssetManager. On the
     * JVM it falls back to the classpath, so copy the library's own assets out of its AAR in the
     * Gradle cache into the test resources folder. The real library then runs on its real data.
     */
    @Synchronized
    fun installPdfBoxAssets() {
        if (pdfAssetsReady) return
        val jar = File(PDFBoxResourceLoader::class.java.protectionDomain!!.codeSource.location.toURI())
        val caches = generateSequence(jar) { it.parentFile }.firstOrNull { it.name == "caches" }
            ?: error("Gradle cache not found above $jar")
        val aar = File(caches, "modules-2/files-2.1/com.tom-roush/pdfbox-android").walkTopDown()
            .firstOrNull { it.isFile && it.name == "pdfbox-android-$PDFBOX_VERSION.aar" }
            ?: error("pdfbox-android-$PDFBOX_VERSION.aar not found in $caches")
        val root = fixture("plan.pdf").parentFile!!.parentFile!!
        ZipFile(aar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith("assets/")) continue
                val dst = File(root, entry.name.removePrefix("assets/"))
                if (dst.length() == entry.size) continue
                dst.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> dst.outputStream().use { input.copyTo(it) } }
            }
        }
        pdfAssetsReady = true
    }

    private const val PDFBOX_VERSION = "2.0.27.0"

    fun tempDir(): File = java.nio.file.Files.createTempDirectory("mysi-skills").toFile().also { it.deleteOnExit() }

    /** Writes a zip with the given parts; text parts are UTF-8. */
    fun zip(file: File, parts: List<Pair<String, Any>>): File {
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            for ((name, body) in parts) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                when (body) {
                    is ByteArray -> z.write(body)
                    else -> z.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                z.closeEntry()
            }
        }
        return file
    }

    fun write(dir: File, name: String, text: String): File = File(dir, name).apply { writeText(text) }
}
