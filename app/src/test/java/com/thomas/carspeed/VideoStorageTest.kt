package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VideoStorageTest {
    @Test
    fun file_name_is_stable_and_mp4() {
        val name = VideoStorage.fileNameFor(1_700_000_000_000L)

        assertTrue(Regex("carspeed-\\d{8}-\\d{6}\\.mp4").matches(name))
    }

    @Test
    fun photo_file_name_is_stable_and_jpg() {
        val name = VideoStorage.photoFileNameFor(1_700_000_000_000L)

        assertTrue(Regex("carspeed-photo-\\d{8}-\\d{6}\\.jpg").matches(name))
    }

    @Test
    fun plate_file_uses_video_base_name() {
        val dir = Files.createTempDirectory("carspeed-plate-file-test").toFile()
        try {
            val mp4 = dir.resolve("carspeed-20261010-100000.mp4")

            assertEquals(
                dir.resolve("carspeed-20261010-100000.plate.json"),
                VideoStorage.plateFileFor(mp4)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun managed_video_files_only_include_app_video_and_subtitle_files() {
        val dir = Files.createTempDirectory("carspeed-video-test").toFile()
        try {
            val mp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("video") }
            val srt = dir.resolve("carspeed-20261010-100000.srt").apply { writeText("subtitle") }
            val track = dir.resolve("carspeed-20261010-100000.track.json").apply { writeText("track") }
            val plate = dir.resolve("carspeed-20261010-100000.plate.json").apply { writeText("plate") }
            dir.resolve("holiday-drive.mp4").writeText("protected")
            dir.resolve("holiday-drive.srt").writeText("protected subtitle")
            dir.resolve("other.mp4").writeText("external")
            dir.resolve("carspeed-photo-20261010-100000.jpg").writeText("photo")

            val files = VideoStorage.managedVideoFiles(dir).toSet()

            assertEquals(setOf(mp4, srt, track, plate), files)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun favorited_auto_video_is_visible_but_not_managed_for_cleanup() {
        val dir = Files.createTempDirectory("carspeed-favorite-test").toFile()
        try {
            val mp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("video") }
            val srt = dir.resolve("carspeed-20261010-100000.srt").apply { writeText("subtitle") }
            val track = dir.resolve("carspeed-20261010-100000.track.json").apply { writeText("track") }
            val plate = dir.resolve("carspeed-20261010-100000.plate.json").apply { writeText("plate") }

            assertTrue(VideoStorage.setFavorite(mp4, true))

            val favorite = dir.resolve("carspeed-20261010-100000.favorite")
            assertTrue(VideoStorage.isFavorite(mp4))
            assertEquals(setOf(mp4), VideoStorage.videoFiles(dir).toSet())
            assertEquals(setOf(mp4, srt, track, plate, favorite), VideoStorage.companionFilesFor(mp4).toSet())
            assertTrue(VideoStorage.managedVideoFiles(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renamed_video_remains_visible_but_is_not_managed_for_cleanup() {
        val dir = Files.createTempDirectory("carspeed-rename-visible-test").toFile()
        try {
            val mp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("video") }
            val srt = dir.resolve("carspeed-20261010-100000.srt").apply { writeText("subtitle") }
            val track = dir.resolve("carspeed-20261010-100000.track.json").apply { writeText("track") }
            val plate = dir.resolve("carspeed-20261010-100000.plate.json").apply { writeText("plate") }
            VideoStorage.setFavorite(mp4, true)

            val renamed = VideoStorage.renameVideoGroup(mp4, "周末 山路")

            requireNotNull(renamed)
            assertEquals("周末 山路.mp4", renamed.name)
            assertTrue(dir.resolve("周末 山路.srt").exists())
            assertTrue(dir.resolve("周末 山路.track.json").exists())
            assertTrue(dir.resolve("周末 山路.plate.json").exists())
            assertTrue(dir.resolve("周末 山路.favorite").exists())
            assertFalse(mp4.exists())
            assertFalse(srt.exists())
            assertFalse(track.exists())
            assertFalse(plate.exists())
            assertEquals(setOf(renamed), VideoStorage.videoFiles(dir).toSet())
            assertTrue(VideoStorage.managedVideoFiles(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun delete_video_group_removes_video_subtitle_and_track_only() {
        val dir = Files.createTempDirectory("carspeed-delete-group-test").toFile()
        try {
            val mp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeText("video") }
            val srt = dir.resolve("carspeed-20261010-100000.srt").apply { writeText("subtitle") }
            val track = dir.resolve("carspeed-20261010-100000.track.json").apply { writeText("track") }
            val plate = dir.resolve("carspeed-20261010-100000.plate.json").apply { writeText("plate") }
            val favorite = dir.resolve("carspeed-20261010-100000.favorite").apply { writeText("favorite") }
            val other = dir.resolve("carspeed-20261010-101000.mp4").apply { writeText("other") }

            val deleted = VideoStorage.deleteVideoGroup(mp4)

            assertEquals(5, deleted)
            assertFalse(mp4.exists())
            assertFalse(srt.exists())
            assertFalse(track.exists())
            assertFalse(plate.exists())
            assertFalse(favorite.exists())
            assertTrue(other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanup_plan_deletes_oldest_group_until_new_segment_has_safe_space() {
        val dir = Files.createTempDirectory("carspeed-cleanup-space-test").toFile()
        try {
            val oldMp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeBytes(ByteArray(80)) }
            val oldSrt = dir.resolve("carspeed-20261010-100000.srt").apply { writeBytes(ByteArray(20)) }
            val oldTrack = dir.resolve("carspeed-20261010-100000.track.json").apply { writeBytes(ByteArray(5)) }
            val oldPlate = dir.resolve("carspeed-20261010-100000.plate.json").apply { writeBytes(ByteArray(7)) }
            val newerMp4 = dir.resolve("carspeed-20261010-101000.mp4").apply { writeBytes(ByteArray(60)) }
            oldMp4.setLastModified(1_000L)
            oldSrt.setLastModified(1_001L)
            oldTrack.setLastModified(1_002L)
            oldPlate.setLastModified(1_003L)
            newerMp4.setLastModified(2_000L)

            val plan = VideoStorage.planCleanup(
                files = VideoStorage.managedVideoFiles(dir),
                limitBytes = 1_000L,
                usableSpaceBytes = 50L,
                requiredFreeBytes = 100L,
                minFreeBytes = 50L
            )

            assertEquals(setOf(oldMp4, oldSrt, oldTrack, oldPlate), plan.filesToDelete.toSet())
            assertTrue(plan.hasEnoughSpaceAfterCleanup)
            assertEquals(162L, plan.projectedFreeBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanup_plan_deletes_oldest_group_when_managed_files_exceed_quota() {
        val dir = Files.createTempDirectory("carspeed-cleanup-quota-test").toFile()
        try {
            val oldMp4 = dir.resolve("carspeed-20261010-100000.mp4").apply { writeBytes(ByteArray(80)) }
            val newerMp4 = dir.resolve("carspeed-20261010-101000.mp4").apply { writeBytes(ByteArray(60)) }
            oldMp4.setLastModified(1_000L)
            newerMp4.setLastModified(2_000L)

            val plan = VideoStorage.planCleanup(
                files = VideoStorage.managedVideoFiles(dir),
                limitBytes = 100L,
                usableSpaceBytes = 1_000L,
                requiredFreeBytes = 0L,
                minFreeBytes = 50L
            )

            assertEquals(setOf(oldMp4), plan.filesToDelete.toSet())
            assertEquals(60L, plan.projectedManagedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanup_plan_deletes_whole_trip_folder_as_one_group() {
        val dir = Files.createTempDirectory("carspeed-trip-cleanup-test").toFile()
        try {
            val trip = dir.resolve("carspeed-trip-20261010-100000").apply { mkdirs() }
            val first = trip.resolve("carspeed-20261010-100000.mp4").apply { writeBytes(ByteArray(80)) }
            val second = trip.resolve("carspeed-20261010-100500.mp4").apply { writeBytes(ByteArray(80)) }
            val secondTrack = trip.resolve("carspeed-20261010-100500.track.json").apply { writeBytes(ByteArray(5)) }
            val newer = dir.resolve("carspeed-20261010-110000.mp4").apply { writeBytes(ByteArray(20)) }
            first.setLastModified(1_000L)
            second.setLastModified(1_100L)
            secondTrack.setLastModified(1_101L)
            newer.setLastModified(2_000L)

            val plan = VideoStorage.planCleanup(
                files = VideoStorage.managedVideoFiles(dir),
                limitBytes = 100L,
                usableSpaceBytes = 1_000L,
                requiredFreeBytes = 0L,
                minFreeBytes = 50L
            )

            assertEquals(setOf(first, second, secondTrack), plan.filesToDelete.toSet())
            assertEquals(20L, plan.projectedManagedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }
}
