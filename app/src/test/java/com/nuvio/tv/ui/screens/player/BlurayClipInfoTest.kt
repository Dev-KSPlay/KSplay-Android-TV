package com.nuvio.tv.ui.screens.player

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurayClipInfoTest {
    private fun stream(pid: Int, vararg info: Int) = byteArrayOf((pid shr 8).toByte(), pid.toByte(), info.size.toByte()) +
        ByteArray(info.size) { info[it].toByte() }

    private fun language(code: String) = code.map { it.code }.toIntArray()

    // HDMV header, then ProgramInfo at 40 with one program sequence, as a disc writes it.
    private fun clipInfo(vararg streams: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HDMV0300".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 40))
        out.write(ByteArray(24))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1)) // Length, reserved, one program sequence.
        out.write(byteArrayOf(0, 0, 0, 0, 0x01, 0x00, streams.size.toByte(), 0))
        streams.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val disc = clipInfo(
        stream(0x1011, 0x24, 0x61, 0, 0, 0),
        stream(0x1100, 0x83, 0x61, *language("eng"), 0),
        stream(0x1101, 0x81, 0x61, *language("SPA"), 0),
        stream(0x1102, 0x81, 0x61, *language("und"), 0),
        stream(0x12A0, 0x90, *language("fra"), 0),
        stream(0x1A00, 0x92, 0x01, *language("deu"))
    )

    @Test fun `stream languages by PID`() {
        assertEquals(
            mapOf(0x1100 to "eng", 0x1101 to "spa", 0x12A0 to "fra", 0x1A00 to "deu"),
            BlurayClipInfo.parseLanguages(disc)
        )
    }

    @Test fun `a cut or foreign file yields what it holds and never throws`() {
        for (size in 0 until disc.size) BlurayClipInfo.parseLanguages(disc.copyOf(size))
        // Cut into the spa stream: the streams before it still count.
        assertEquals(mapOf(0x1100 to "eng"), BlurayClipInfo.parseLanguages(disc.copyOf(disc.size - 26)))
        assertTrue(BlurayClipInfo.parseLanguages(ByteArray(4096) { 0x47 }).isEmpty())
    }

    @Test fun `clip info sits beside the clip in its case`() {
        assertEquals("/stream/abc/BDMV/CLIPINF/00065.clpi", BlurayClipInfo.clipInfoPath("/stream/abc/BDMV/STREAM/00065.m2ts"))
        assertEquals("/disc/bdmv/clipinf/00001.clpi", BlurayClipInfo.clipInfoPath("/disc/bdmv/stream/00001.m2ts"))
        assertEquals("/Disc/BDMV/CLIPINF/00800.CLPI", BlurayClipInfo.clipInfoPath("/Disc/BDMV/STREAM/00800.M2TS"))
        assertNull(BlurayClipInfo.clipInfoPath("/movie.m2ts"))
        assertNull(BlurayClipInfo.clipInfoPath("/Disc/BDMV/STREAM/SSIF/00065.ssif"))
    }

    @Test fun `only tracks the PMT left unnamed take the clip info language`() {
        val languages = mapOf(4352 to "eng")
        assertEquals("en", BlurayClipInfo.withLanguage(Format.Builder().setId("1/4352").build(), languages).language)
        assertEquals("de", BlurayClipInfo.withLanguage(Format.Builder().setId("1/4352").setLanguage("de").build(), languages).language)
        assertNull(BlurayClipInfo.withLanguage(Format.Builder().setId("1/4353").build(), languages).language)
        assertNull(BlurayClipInfo.withLanguage(Format.Builder().build(), languages).language)
    }

    @Test fun `the wrapped extractor names its tracks`() {
        val formats = mutableListOf<Format>()
        val sink = object : TrackOutput {
            override fun format(format: Format) { formats += format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        val clip = object : Extractor {
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) {
                output.track(0, 1).format(Format.Builder().setId("1/4352").build())
                output.track(1, 1).format(Format.Builder().setId("1/4353").build())
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
        }
        BlurayClipInfo.LanguageExtractor(clip, mapOf(4352 to "eng")).init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = sink
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        assertEquals(listOf("en", null), formats.map { it.language })
    }
}
