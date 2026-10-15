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
    fun favorite_markers_match_trip_tokens() {
        val dir = Files.createTempDirectory("carspeed-favorite-marker-test").toFile()
        try {
            val video = dir.resolve("carspeed-20261013-100000.mp4")
            val trip = VideoStorage.VideoTrip(
                id = "carspeed-trip-20261013-100000",
                title = "carspeed-trip-20261013-100000",
                directory = dir,
                segments = listOf(video)
            )

            assertEquals(
                setOf(trip.id),
                VideoStorage.favoriteIdsFromMarkers(
                    listOf(trip),
                    setOf(".carspeed-trip-20261013-100000.favorite")
                )
            )
            assertEquals(emptySet<String>(), VideoStorage.favoriteIdsFromMarkers(listOf(trip), emptySet()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recording_duration_follows_the_filename_clock() {
        val dir = Files.createTempDirectory("carspeed-duration-test").toFile()
        try {
            val video = dir.resolve("carspeed-trip-20261013-100000__carspeed-20261013-100000.mp4").apply {
                writeText("segment")
            }
            assertEquals(0L, VideoStorage.recordingDurationMs(dir.resolve("note.txt")))

            val before = VideoStorage.recordingDurationMs(video)
            video.setLastModified(video.lastModified() + 90_000L)

            assertEquals(before + 90_000L, VideoStorage.recordingDurationMs(video))
        } finally {
            dir.deleteRecursively()
        }
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

    @Test
    fun gallery_file_name_drops_the_trip_token() {
        val prefixed = "carspeed-trip-20261011-025338__carspeed-20261011-025338.mp4"

        assertEquals(
            "carspeed-20261011-025338.mp4",
            VideoStorage.galleryFileName("carspeed-trip-20261011-025338", prefixed)
        )
        assertEquals("carspeed-trip-20261011-025338", VideoStorage.galleryTripToken(prefixed))
        assertEquals("20261011-025338", VideoStorage.canonicalTripFolderName("carspeed-trip-20261011-025338"))
    }

    @Test
    fun trip_list_title_shows_start_time_and_duration() {
        val start = 1_700_000_000_000L
        val stamp = java.text.SimpleDateFormat("yyyyMMdd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(start))

        assertEquals("$stamp  1h3min", VideoStorage.tripListTitle(start, 3_780_000L))
        assertEquals("45min", VideoStorage.formatTripDuration(45L * 60L * 1000L))
        assertEquals("1h", VideoStorage.formatTripDuration(3_600_000L))
        assertEquals("40s", VideoStorage.formatTripDuration(40_000L))
    }

    @Test
    fun readable_trip_folder_round_trips_to_the_same_second() {
        val name = VideoStorage.tripFolderNameFor(1_700_000_000_000L)

        assertTrue(VideoStorage.isTripDirectoryName(name))
        assertEquals(1_700_000_000L, VideoStorage.tripFolderStartTimeMs(name)!! / 1000L)
        assertTrue(VideoStorage.isTripDirectoryName("carspeed-trip-20231114-221320"))
        assertTrue(VideoStorage.tripFolderStartTimeMs("carspeed-trip-20231114-221320") != null)
    }

    @Test
    fun readable_trip_folder_is_deleted_as_one_group() {
        val dir = Files.createTempDirectory("carspeed-readable-trip-cleanup-test").toFile()
        try {
            val trip = dir.resolve("行车 2026-10-13 16点00分05秒").apply { mkdirs() }
            val first = trip.resolve("carspeed-20261013-160005.mp4").apply { writeBytes(ByteArray(40)) }
            val second = trip.resolve("carspeed-20261013-160505.mp4").apply { writeBytes(ByteArray(40)) }
            val newer = dir.resolve("carspeed-20261013-170000.mp4").apply { writeBytes(ByteArray(10)) }
            first.setLastModified(1_000L)
            second.setLastModified(1_100L)
            newer.setLastModified(2_000L)

            val plan = VideoStorage.planCleanup(
                files = VideoStorage.managedVideoFiles(dir),
                limitBytes = 50L,
                usableSpaceBytes = 1_000L,
                requiredFreeBytes = 0L,
                minFreeBytes = 50L
            )

            assertEquals(setOf(first, second), plan.filesToDelete.toSet())
            assertEquals(10L, plan.projectedManagedBytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun moved_trip_video_still_finds_the_old_track_file() {
        val root = Files.createTempDirectory("carspeed-legacy-track").toFile()
        try {
            val video = root.resolve("DCIM/CarSpeed/20261013-094720/carspeed-20261013-094721.mp4").apply {
                parentFile?.mkdirs()
                writeText("video")
            }
            val legacyDir = root.resolve(
                "Android/media/com.thomas.carspeed/CarSpeed/carspeed-trip-20261013-094720"
            )
            val track = legacyDir.resolve("carspeed-20261013-094721.track.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }
            val subtitle = legacyDir.resolve("carspeed-20261013-094721.srt").apply { writeText("1") }

            val tracks = VideoStorage.legacyCompanionFiles(video, ".track.json", root)
            val subtitles = VideoStorage.legacyCompanionFiles(video, ".srt", root)

            assertTrue(tracks.any { it.canonicalFile == track.canonicalFile })
            assertTrue(subtitles.any { it.canonicalFile == subtitle.canonicalFile })
        } finally {
            root.deleteRecursively()
        }
    }
}
