package com.animeday

import android.content.Context
import android.os.Build
import com.anime.day.Utils.NativeLib
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Loads the original libnative-lib.so and calls NativeLib.buildSecure with a
 * package spoof whose sourceDir points at a readable placeholder file.
 *
 * The native code only opens sourceDir to make sure the app package exists; it
 * does not parse the file. A placeholder therefore yields the full valid token
 * instead of the short "err_open" token that the API rejects.
 */
internal object NativeToken {
    private const val LIB_NAME = "libnative-lib.so"
    private const val TOKEN_PROP = "animeday.native.token"

    @Volatile
    private var loaded = false

    private val lock = Any()

    fun secureId(context: Context): String? {
        load(context)
        // Serialize every native call: the .so has process-wide mutable state and
        // concurrent calls corrupt its heap.
        synchronized(lock) {
            val fresh = runCatching {
                NativeLib.buildSecure(
                    FakeContext(
                        context.applicationContext ?: context,
                        sourceFile(context).absolutePath,
                    )
                )
            }.getOrNull()
            if (!fresh.isNullOrEmpty()) {
                System.setProperty(TOKEN_PROP, fresh)
                return fresh
            }
            // Native unavailable (e.g. after a plugin reload) -> fall back to cache.
            return System.getProperty(TOKEN_PROP)?.takeIf { it.isNotEmpty() }
        }
    }

    private fun sourceFile(context: Context): File {
        val dir = File(context.filesDir, "animeday_native")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "app.apk")
        if (!f.exists()) f.writeBytes(byteArrayOf(0))
        return f
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val so = findOrExtract(context)
            System.load(so.absolutePath)
            // The first call initialises process-wide OpenSSL state. Run it while
            // still holding the lock so concurrent callers cannot race it.
            runCatching {
                NativeLib.buildSecure(
                    FakeContext(
                        context.applicationContext ?: context,
                        sourceFile(context).absolutePath,
                    )
                )
            }
            loaded = true
        }
    }

    private fun findOrExtract(context: Context): File {
        val abis = LinkedHashSet<String>()
        abis.addAll(Build.SUPPORTED_ABIS)
        if (Build.CPU_ABI.isNotEmpty()) abis.add(Build.CPU_ABI)

        // Android's linker keys loaded libraries by real path, so every reload
        // needs a fresh extraction path.
        val tag = java.lang.Long.toString(System.nanoTime(), 36)
        val base = File(context.filesDir, "animeday_native")
        val dir = File(base, tag).apply { mkdirs() }

        for (abi in abis) {
            for (entry in listOf("lib/$abi/$LIB_NAME", "jni/$abi/$LIB_NAME")) {
                val input = openResource(entry) ?: openFromPluginFile(entry) ?: continue
                val out = File(dir, "libnative-lib-$abi.so")
                input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
                out.setReadable(true, false)
                out.setExecutable(true, false)
                out.setWritable(true)
                if (out.length() > 0L) {
                    base.listFiles()?.forEach { if (it != dir) runCatching { it.deleteRecursively() } }
                    return out
                }
            }
        }
        throw UnsatisfiedLinkError("$LIB_NAME not found inside plugin for ABIs=$abis")
    }

    private fun openResource(entry: String): InputStream? = try {
        NativeToken::class.java.classLoader?.getResourceAsStream(entry)
    } catch (_: Throwable) {
        null
    }

    private fun openFromPluginFile(entry: String): InputStream? {
        val path = pluginFilePath() ?: return null
        return try {
            ZipFile(path).use { zip ->
                val z = zip.getEntry(entry) ?: return null
                zip.getInputStream(z).use { it.readBytes() }.inputStream()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun pluginFilePath(): String? {
        try {
            val loc = NativeToken::class.java.protectionDomain?.codeSource?.location
            if (loc != null) {
                val f = File(loc.toURI())
                if (f.exists()) return f.absolutePath
            }
        } catch (_: Throwable) {
        }
        try {
            val cl = NativeToken::class.java.classLoader ?: return null
            val pathListField = cl.javaClass.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(cl) ?: return null
            val dexField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = dexField.get(pathList) as? Array<*> ?: return null
            for (el in elements) {
                if (el == null) continue
                val pathField = el.javaClass.getDeclaredField("path").apply { isAccessible = true }
                when (val p = pathField.get(el)) {
                    is File -> if (p.exists()) return p.absolutePath
                    is String -> if (File(p).exists()) return p
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }
}
