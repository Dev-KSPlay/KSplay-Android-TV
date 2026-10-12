package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Stream languages from a Blu-ray clip's CLPI, BDMV/CLIPINF/<clip>.clpi beside BDMV/STREAM/<clip>.m2ts.
 * A clip's PMT carries no language descriptors, so without it a disc's tracks show unnamed.
 */
internal object BlurayClipInfo {
    private const val TAG = "BlurayClipInfo"
    const val MAX_BYTES = 1 shl 20

    private val CLIP = Regex("/(BDMV)/(STREAM)/([^/]+)\\.(m2ts)$", RegexOption.IGNORE_CASE)

    // Per clip, as a player rebuilds its sources on retries and track changes.
    private val cache = object : LinkedHashMap<String, Map<Int, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Int, String>>?) = size > 16
    }

    /** The CLPI path beside a clip path, in the clip path's case, or null for any other path. */
    fun clipInfoPath(path: String): String? {
        val match = CLIP.find(path) ?: return null
        val (bdmv, stream, clip, extension) = match.destructured
        val dir = if (stream == stream.lowercase()) "clipinf" else "CLIPINF"
        val ext = if (extension == extension.lowercase()) "clpi" else "CLPI"
        return path.substring(0, match.range.first) + "/$bdmv/$dir/$clip.$ext"
    }

    /**
     * PID to ISO 639-2 language for the clip at [uri]. Empty when the clip has no readable CLPI, and
     * the clip then plays as it would without this.
     */
    fun languages(uri: Uri, dataSourceFactory: DataSource.Factory): Map<Int, String> {
        val path = uri.path ?: return emptyMap()
        val infoPath = clipInfoPath(path) ?: return emptyMap()
        val key = uri.toString()
        synchronized(cache) { cache[key]?.let { return it } }
        // A failed read, such as one a seek cancelled, is not kept: the next load asks again.
        val bytes = read(uri.buildUpon().path(infoPath).build(), dataSourceFactory) ?: return emptyMap()
        val languages = parseLanguages(bytes)
        Log.i(TAG, "clip info: ${languages.size} stream languages for ${uri.lastPathSegment}")
        synchronized(cache) { cache[key] = languages }
        return languages
    }

    private fun read(uri: Uri, dataSourceFactory: DataSource.Factory): ByteArray? {
        val source = dataSourceFactory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).setLength(MAX_BYTES.toLong()).build())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 shl 10)
            while (out.size() < MAX_BYTES) {
                val read = source.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } catch (e: Exception) {
            // Missing (404), unreachable or refused: the clip plays without names.
            Log.d(TAG, "no clip info: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Reads ProgramInfo: per stream its PID and StreamCodingInfo, where audio (0x80-0x86, 0xA1,
     * 0xA2), graphics (0x90, 0x91) and text subtitles (0x92) carry a language code.
     */
    fun parseLanguages(b: ByteArray): Map<Int, String> {
        if (b.size < 40 || String(b, 0, 4, Charsets.US_ASCII) != "HDMV") return emptyMap()
        val out = LinkedHashMap<Int, String>()
        var p = u32(b, 12) + 5 // Length and a reserved byte.
        if (p < 40 || p >= b.size) return out
        val sequences = b[p].u8()
        p++
        repeat(sequences) {
            if (p + 8 > b.size) return out
            val streams = b[p + 6].u8()
            p += 8
            repeat(streams) {
                if (p + 3 > b.size) return out
                val pid = (b[p].u8() shl 8) or b[p + 1].u8()
                val length = b[p + 2].u8()
                val info = p + 3
                p = info + length
                if (length < 1 || p > b.size) return out
                val at = when (b[info].u8()) {
                    in 0x80..0x86, 0xA1, 0xA2, 0x92 -> 2
                    0x90, 0x91 -> 1
                    else -> return@repeat
                }
                if (at + 3 > length) return@repeat
                val language = String(b, info + at, 3, Charsets.US_ASCII).lowercase()
                if (language != "und" && language.all { it in 'a'..'z' }) out.putIfAbsent(pid, language)
            }
        }
        return out
    }

    private fun Byte.u8() = toInt() and 0xFF

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].u8() shl 24) or (b[at + 1].u8() shl 16) or (b[at + 2].u8() shl 8) or b[at + 3].u8()

    /** A TS track's format id is "program/PID"; a format the PMT left unnamed takes the CLPI's language. */
    fun withLanguage(format: Format, languages: Map<Int, String>): Format {
        if (format.language != null && format.language != C.LANGUAGE_UNDETERMINED) return format
        val pid = format.id?.substringAfterLast('/')?.toIntOrNull() ?: return format
        val language = languages[pid] ?: return format
        return format.buildUpon().setLanguage(language).build()
    }

    /** Wraps a clip's extractor so each track's format carries the CLPI's language. */
    class LanguageExtractor(
        private val delegate: Extractor,
        private val languages: Map<Int, String>
    ) : Extractor {
        override fun sniff(input: ExtractorInput) = delegate.sniff(input)

        override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = LanguageTrackOutput(output.track(id, type), languages)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })

        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

        override fun release() = delegate.release()

        override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    }

    private class LanguageTrackOutput(
        private val delegate: TrackOutput,
        private val languages: Map<Int, String>
    ) : TrackOutput {
        override fun format(format: Format) = delegate.format(withLanguage(format, languages))

        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
            delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
