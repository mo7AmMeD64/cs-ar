package com.stardima

import com.lagradost.cloudstream3.utils.JsUnpacker

/**
 * Shared P.A.C.K.E.R. (`eval(function(p,a,c,k,e,d){...}('..',r,c,'..'.split('|'),0,{}))`)
 * decoder used by the StarDima host extractors.
 *
 * Most of these hosts (Uqload, Mixdrop, StreamHG/vibuxer) hide their JWPlayer
 * setup inside this obfuscator. We first run a faithful port of the packer's own
 * substitution pass (it never throws and keeps the payload when indices are
 * out of range), then fall back to CloudStream's [JsUnpacker], and finally
 * return `null` so callers can try the raw markup.
 */
internal object JsPacker {
    private val EVAL_RE = Regex(
        """(?s)\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
    )
    private val WORD_RE = Regex("""\b[a-zA-Z0-9_]+\b""")

    fun unpack(html: String): String? {
        port(html)?.let { return it }
        return try {
            JsUnpacker(html).unpack()
        } catch (_: Throwable) {
            null
        }
    }

    private fun port(html: String): String? {
        val match = EVAL_RE.find(html) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null

        val decoded = StringBuilder(payload)
        var offset = 0
        for (word in WORD_RE.findAll(payload)) {
            val index = word.value.toIntOrNull(radix) ?: continue
            val value = symtab.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
            decoded.replace(word.range.first + offset, word.range.last + 1 + offset, value)
            offset += value.length - word.value.length
        }
        return decoded.toString()
    }
}
