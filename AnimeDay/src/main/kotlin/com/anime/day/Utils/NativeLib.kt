package com.anime.day.Utils

import android.content.Context
import android.os.Build
import com.animeday.FakeContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Mirrors com.anime.day.Utils.NativeLib from the original app.
 * The actual native methods (buildSecure / decryptResponse) are registered
 * dynamically by JNI_OnLoad inside libnative-lib.so, so the class name and the
 * method names/signatures must match exactly.
 */
object NativeLib {
    private const val LIB_NAME = "libnative-lib.so"
    private const val TOKEN_PROP = "animeday.native.token"

    @Volatile
    private var loaded = false

    private val lock = Any()

    external fun buildSecure(context: Context): String?

    external fun decryptResponse(data: String, key: String): String?

    /**
     * Extracts libnative-lib.so from this plugin package and loads it.
     * buildSecure() reads the caller's package signature, so a [FakeContext]
     * impersonating com.anime.day is passed to it.
     */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val so = findOrExtract(context)
            System.load(so.absolutePath)
            // The native library shares global OpenSSL/BoringSSL state and is NOT
            // thread-safe. Perform the first (one-time initialisation) call while
            // still holding the lock so concurrent callers never race the init.
            runCatching { buildSecure(FakeContext(context.applicationContext ?: context)) }
            loaded = true
        }
    }

    fun secureId(context: Context): String? {
        load(context)
        // Serialize every native call: the .so has process-wide mutable state and
        // concurrent calls corrupt the heap (crashes surface in unrelated threads).
        synchronized(lock) {
            val fresh = runCatching {
                buildSecure(FakeContext(context.applicationContext ?: context))
            }.getOrNull()
            if (!fresh.isNullOrEmpty() && fresh.length > 8) {
                // Process-global cache survives CloudStream plugin (re)loads, which
                // create a new ClassLoader where the .so cannot be re-opened.
                System.setProperty(TOKEN_PROP, fresh)
                return fresh
            }
            // Native unavailable (e.g. after a plugin reload) -> fall back to cache.
            return System.getProperty(TOKEN_PROP)?.takeIf { it.isNotEmpty() }
        }
    }

    fun decrypt(context: Context, data: String, key: String): String? {
        load(context)
        synchronized(lock) {
            return runCatching { decryptResponse(data, key) }.getOrNull()
        }
    }

    private fun findOrExtract(context: Context): File {
        val abis = LinkedHashSet<String>()
        abis.addAll(Build.SUPPORTED_ABIS)
        if (Build.CPU_ABI.isNotEmpty()) abis.add(Build.CPU_ABI)

        // A unique directory per load attempt is required: Android's linker keys
        // loaded libraries by real path + ClassLoader namespace, so re-opening the
        // same path from a new (reloaded) plugin ClassLoader fails with
        // "already opened by ClassLoader ... can't open in ClassLoader ...".
        val tag = java.lang.Long.toString(System.nanoTime(), 36)
        val base = File(context.filesDir, "animeday_native")
        val dir = File(base, tag).apply { mkdirs() }

        for (abi in abis) {
            val entry = "lib/$abi/$LIB_NAME"
            val input = openResource(entry) ?: openFromPluginFile(entry) ?: continue
            val out = File(dir, "libnative-lib-$abi.so")
            input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
            out.setReadable(true, false)
            out.setExecutable(true, false)
            out.setWritable(true)
            if (out.length() > 0L) {
                // Drop copies left behind by previous (re)loads.
                base.listFiles()?.forEach { if (it != dir) runCatching { it.deleteRecursively() } }
                return out
            }
        }
        throw UnsatisfiedLinkError("$LIB_NAME not found inside plugin for ABIs=$abis")
    }

    private fun openResource(entry: String): InputStream? = try {
        NativeLib::class.java.classLoader?.getResourceAsStream(entry)
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
            val loc = NativeLib::class.java.protectionDomain?.codeSource?.location
            if (loc != null) {
                val f = File(loc.toURI())
                if (f.exists()) return f.absolutePath
            }
        } catch (_: Throwable) {
        }
        try {
            val cl = NativeLib::class.java.classLoader ?: return null
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