package com.thomas.carspeed

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class VideoHistoryCacheTest {
    @Test
    fun signatureChangesWhenFileChanges() {
        val dir = Files.createTempDirectory("carspeed-history-cache-test").toFile()
        try {
            val video = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("old") }
            val before = VideoHistoryCache.signatureForFiles(listOf(video))

            video.writeText("new content")
            video.setLastModified(video.lastModified() + 2_000L)
            val after = VideoHistoryCache.signatureForFiles(listOf(video))

            assertNotEquals(before, after)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun signatureIsStableForSameFilesInDifferentOrder() {
        val dir = Files.createTempDirectory("carspeed-history-cache-order-test").toFile()
        try {
            val video = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("video") }
            val track = dir.resolve("carspeed-20261010-100000.track.json").apply { writeText("track") }

            assertEquals(
                VideoHistoryCache.signatureForFiles(listOf(video, track)),
                VideoHistoryCache.signatureForFiles(listOf(track, video))
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
