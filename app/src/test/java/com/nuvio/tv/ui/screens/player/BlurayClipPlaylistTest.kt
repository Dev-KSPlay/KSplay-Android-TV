package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BlurayClipPlaylistTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    /** An MPLS with one play item per clip, each lasting its 45 kHz ticks. */
    private fun mpls(vararg items: Pair<String, Long>): ByteArray {
        var bytes = byteArrayOf()
        var at = 0L
        for ((clip, ticks) in items) {
            val body = ByteArray(20)
            (clip + "M2TS").toByteArray().copyInto(body, 0)
            for (i in 0..3) {
                body[12 + i] = (at shr (24 - 8 * i)).toByte()
                body[16 + i] = ((at + ticks) shr (24 - 8 * i)).toByte()
            }
            at += ticks
            bytes += byteArrayOf(0, 20) + body
        }
        val playlistStart = 16
        val data = ByteArray(playlistStart + 10 + bytes.size)
        "MPLS0200".toByteArray().copyInto(data, 0)
        data[11] = playlistStart.toByte()
        data[playlistStart + 4 + 2] = (items.size shr 8).toByte()
        data[playlistStart + 4 + 3] = items.size.toByte()
        bytes.copyInto(data, playlistStart + 10)
        return data
    }

    private val hour = 45_000L * 3600

    private val playlists = mapOf(
        // The feature, split over two clips.
        "00000.mpls" to mpls("00001" to hour, "00002" to hour / 2),
        // An anti-copy loop: one short clip, longer than the feature.
        "00001.mpls" to mpls(*Array(40) { "00009" to hour / 5 }),
        // A trailer, a clip of its own.
        "00002.mpls" to mpls("00003" to hour / 30),
        // A cut of the feature without its second clip.
        "00003.mpls" to mpls("00001" to hour)
    )

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path == "/disc/BDMV/PLAYLIST/") {
                    return MockResponse().setBody(playlists.keys.joinToString("") { "<a href=\"$it\">$it</a>\n" })
                }
                val name = path.removePrefix("/disc/BDMV/PLAYLIST/")
                return playlists[name]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun probe(clip: String) =
        BlurayPlaylistResolver.probeClipPlaylist(server.url("/disc/BDMV/STREAM/$clip").toString(), emptyMap(), client)

    @Test fun `a clip that is part of its feature plays the feature playlist`() {
        val playlist = probe("00001.m2ts")!!
        assertEquals("00000.mpls", playlist.name)
        assertEquals(
            listOf(server.url("/disc/BDMV/STREAM/00001.m2ts").toString(), server.url("/disc/BDMV/STREAM/00002.m2ts").toString()),
            playlist.segmentUrls
        )
        // Each clip's placeholder duration until it is prepared.
        assertEquals(listOf(3_600_000L, 1_800_000L), playlist.durationsMs)
    }

    @Test fun `a clip that is a whole title or outside every playlist plays alone`() {
        assertNull(probe("00003.m2ts"))
        assertNull(probe("00004.m2ts"))
        assertNull(probe("trailer.m2ts"))
    }

    @Test fun `a folder without playlists plays the clip alone`() {
        assertNull(
            BlurayPlaylistResolver.probeClipPlaylist(server.url("/other/BDMV/STREAM/00001.m2ts").toString(), emptyMap(), client)
        )
    }

    @Test fun `the feature is the longest playlist that is not a loop`() {
        val parsed = playlists.map { (name, data) -> BlurayPlaylistResolver.parseMpls(name, data)!! }
        assertEquals("00000.mpls", BlurayPlaylistResolver.selectMainPlaylist(parsed)!!.name)
    }
}
