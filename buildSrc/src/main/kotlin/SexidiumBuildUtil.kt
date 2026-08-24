// Helpers puros de build (zip, unzip, hash). Ver buildSrc/build.gradle.kts para POR QUE eles moram
// aqui e não no build.gradle.kts: o configuration cache não serializa referências a classes
// declaradas em build scripts, e isso quebrava o build de produção inteiro.
//
// Nenhuma função aqui toca em logger/file()/Project. Quem chama loga no call-site com o `logger` da
// própria Task (Task.getLogger() é explicitamente seguro sob configuration cache).

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SexidiumBuildUtil {
    fun zipDirectoryInto(sourceDirectory: File, destinationZip: File) {
        destinationZip.parentFile.mkdirs()
        val sourceRoot = sourceDirectory.toPath()
        ZipOutputStream(FileOutputStream(destinationZip)).use { zipStream ->
            sourceDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
                val relativePath = sourceRoot.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                zipStream.putNextEntry(ZipEntry(relativePath))
                file.inputStream().use { it.copyTo(zipStream) }
                zipStream.closeEntry()
            }
        }
    }

    /**
     * The folder inside an extracted world archive whose contents ARE the world (level.dat's own folder).
     * Handles the two shapes a downloaded map ships in:
     *  - a wrapper folder ("Medieval-BreadBuilds/level.dat", "My Map/level.dat") — the wrapper is stripped;
     *  - a modern (MC 1.21.6+) dimension-storage save whose overworld lives at dimensions/minecraft/overworld
     *    and which has NO top-level region/ — the overworld is flattened up next to level.dat in [stagingDir].
     * Both consumers (lobby dimension seeding, minigame map cloning) read chunks from a TOP-LEVEL region/,
     * so the zip written from this folder always has region/entities/poi/data at its root.
     *
     * Returns the content root plus whether flattening happened, so the caller can log it (this object
     * has no `logger` of its own — see the class-level comment above).
     */
    fun worldContentRoot(extractedRoot: File, stagingDir: File): Pair<File, Boolean> {
        val worldRoot = extractedRoot.walkTopDown().firstOrNull { it.isFile && it.name == "level.dat" }?.parentFile
            ?: extractedRoot
        val overworld = worldRoot.resolve("dimensions/minecraft/overworld")
        if (worldRoot.resolve("region").isDirectory || !overworld.resolve("region").isDirectory) {
            return worldRoot to false
        }
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        worldRoot.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(stagingDir.resolve(it.name), true) }
        overworld.listFiles()?.forEach { it.copyRecursively(stagingDir.resolve(it.name), true) }
        return stagingDir to true
    }

    fun unzipInto(zipFile: File, destinationDir: File) {
        destinationDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = destinationDir.resolve(entry.name)
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { zipStream.copyTo(it) }
                }
                entry = zipStream.nextEntry
            }
        }
    }

    // Random-access unzip via ZipFile (reads the central directory) rather than the streaming
    // ZipInputStream used by unzipInto: downloaded world saves often carry STORED entries with data
    // descriptors, which make ZipInputStream throw "only DEFLATED entries can have EXT descriptor".
    // ZipFile handles them.
    fun unzipFileInto(zipFile: File, destinationDir: File) {
        destinationDir.mkdirs()
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = destinationDir.resolve(entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    archive.getInputStream(entry).use { input -> outFile.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }

    // Content fingerprint of a bundled map, written into the manifest so the runtime can tell "this map
    // changed" from "this map is already on disk" (see com.sexidium.core.world.MapBundle).
    //
    // Hashed from the SOURCE zip under assets/worlds/**, never from the staged one: zipDirectoryInto
    // stamps each entry with the current time, so the staged zip's bytes differ on every build even when
    // nothing changed — using those would make every single boot look like an update and replace the
    // operator's maps for nothing. The source zip only changes when someone re-exports the map, which is
    // exactly the event this is meant to detect.
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
