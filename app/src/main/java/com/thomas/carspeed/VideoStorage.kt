package com.thomas.carspeed

<<<<<<< HEAD
import android.content.Context
import android.content.ContentValues
=======
import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
>>>>>>> zipcopy/main
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoStorage {
    private const val DIR_NAME = "CarSpeed"
<<<<<<< HEAD
    private const val TRIP_PREFIX = "carspeed-trip-"
    const val MIN_FREE_BYTES = 1_000L * 1024L * 1024L
    private val AUTO_VIDEO_NAME = Regex("carspeed-\\d{8}-\\d{6}")
    private val AUTO_TRIP_NAME = Regex("carspeed-trip-\\d{8}-\\d{6}")
=======
    private const val SHARE_DIR_NAME = "Share"
    private const val TRIP_PREFIX = "carspeed-trip-"
    private const val TRIP_FOLDER_PREFIX = "行车 "
    const val MIN_FREE_BYTES = 1_000L * 1024L * 1024L
    private val AUTO_VIDEO_NAME = Regex("carspeed-\\d{8}-\\d{6}")
    private val VIDEO_STAMP = Regex("carspeed-(\\d{8})-(\\d{6})")
    private val consolidateLock = Any()
>>>>>>> zipcopy/main

    data class VideoTrip(
        val id: String,
        val title: String,
        val directory: File,
        val segments: List<File>
    ) {
        val firstVideo: File
            get() = segments.first()

        val lastModified: Long
            get() = segments.maxOfOrNull { it.lastModified() } ?: 0L
    }

    data class CleanupPlan(
        val filesToDelete: List<File>,
        val hasEnoughSpaceAfterCleanup: Boolean,
        val projectedFreeBytes: Long,
        val projectedManagedBytes: Long
    )

    fun fileNameFor(startTimeMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startTimeMs))
        return "carspeed-$stamp.mp4"
    }

    fun photoFileNameFor(timeMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))
        return "carspeed-photo-$stamp.jpg"
    }

    fun tripFolderNameFor(startTimeMs: Long): String {
<<<<<<< HEAD
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startTimeMs))
        return "$TRIP_PREFIX$stamp"
=======
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startTimeMs))
    }

    fun tripFolderStartTimeMs(name: String): Long? {
        val compact = Regex("^(\\d{8})-(\\d{6})$").matchEntire(name)
        if (compact != null) {
            return parseCompactDateTime(compact.groupValues[1], compact.groupValues[2])
        }
        val legacy = Regex("^carspeed-trip-(\\d{8})-(\\d{6})$").matchEntire(name)
        if (legacy != null) {
            return parseCompactDateTime(legacy.groupValues[1], legacy.groupValues[2])
        }
        val readable = Regex("^行车 (\\d{4}-\\d{2}-\\d{2} \\d{2}点\\d{2}分\\d{2}秒)$").matchEntire(name) ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH'点'mm'分'ss'秒'", Locale.US).parse(readable.groupValues[1])?.time
        }.getOrNull()
    }

    fun canonicalTripFolderName(name: String?): String? {
        val startMs = name?.let { tripFolderStartTimeMs(it) } ?: return null
        return tripFolderNameFor(startMs)
    }

    fun tripListTitle(startTimeMs: Long, durationMs: Long): String? {
        if (startTimeMs <= 0L) return null
        val stamp = SimpleDateFormat("yyyyMMdd HH:mm:ss", Locale.US).format(Date(startTimeMs))
        if (durationMs <= 0L) return stamp
        return "$stamp  ${formatTripDuration(durationMs)}"
    }

    fun formatTripDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h${minutes}min"
            hours > 0L -> "${hours}h"
            minutes > 0L -> "${minutes}min"
            else -> "${seconds}s"
        }
    }

    fun isTripDirectoryName(name: String): Boolean {
        return name.startsWith(TRIP_PREFIX) || tripFolderStartTimeMs(name) != null
    }

    fun galleryTripToken(fileName: String): String? {
        val base = companionBaseName(File(fileName))
        val token = base.substringBefore("__", missingDelimiterValue = "")
        if (token.isEmpty() || token == base) return null
        return token.takeIf { isTripDirectoryName(it) }
    }

    fun galleryFileName(tripFolderName: String?, fileName: String): String {
        val token = galleryTripToken(fileName) ?: return fileName
        return fileName.removePrefix("${token}__").ifBlank { fileName }
>>>>>>> zipcopy/main
    }

    fun outputFile(
        context: Context,
        startTimeMs: Long = System.currentTimeMillis(),
        tripFolderName: String? = null
    ): File {
        val dir = tripFolderName?.let { tripDir(context, it) } ?: videoDir(context)
        return File(dir, fileNameFor(startTimeMs))
    }

<<<<<<< HEAD
    fun videoDir(context: Context): File {
        val baseDir = publicMediaDir(Environment.DIRECTORY_MOVIES) ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(baseDir, DIR_NAME).apply { mkdirs() }
=======
    fun privateOutputFile(
        context: Context,
        startTimeMs: Long = System.currentTimeMillis(),
        tripFolderName: String? = null
    ): File {
        // 分片直接写进系统会扫描的相册目录，避免再在应用私有目录里各存一份。
        return outputFile(context, startTimeMs, tripFolderName)
    }

    /**
     * 系统相册没有「新建相册」接口。相册名来自直接包含视频的文件夹，而且这列是只读的。
     * 相册里每次行驶一个子文件夹，文件夹名是行程开始时间 `yyyyMMdd-HHmmss`。
     */
    fun videoDir(context: Context): File {
        return sharedAlbumRoot(context)
>>>>>>> zipcopy/main
    }

    fun tripDir(context: Context, tripFolderName: String): File {
        return File(videoDir(context), sanitizeFolderName(tripFolderName)).apply { mkdirs() }
    }

<<<<<<< HEAD
    fun videoRelativePath(tripFolderName: String?): String {
        return listOfNotNull(Environment.DIRECTORY_MOVIES, DIR_NAME, tripFolderName?.let { sanitizeFolderName(it) })
            .joinToString("/") + "/"
    }

    fun photoOutputFile(context: Context, timeMs: Long = System.currentTimeMillis()): File {
        return File(photoDir(context), photoFileNameFor(timeMs))
=======
    fun photoOutputFile(
        context: Context,
        timeMs: Long = System.currentTimeMillis(),
        tripFolderName: String? = null
    ): File {
        val folder = canonicalTripFolderName(tripFolderName) ?: tripFolderNameFor(timeMs)
        return File(photoDir(context), "$folder/${photoFileNameFor(timeMs)}")
>>>>>>> zipcopy/main
    }

    fun tempPhotoFile(context: Context, timeMs: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        return File(dir, photoFileNameFor(timeMs))
    }

<<<<<<< HEAD
    fun publishPhotoToGallery(context: Context, sourceFile: File, fileName: String = sourceFile.name): File {
        val targetFile = File(photoDir(context), fileName)
=======
    fun publishPhotoToGallery(
        context: Context,
        sourceFile: File,
        fileName: String = sourceFile.name,
        tripFolderName: String? = null
    ): File {
        val folder = canonicalTripFolderName(tripFolderName)
            ?: canonicalTripFolderName(photoStampFolder(fileName))
            ?: tripFolderNameFor(sourceFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis())
        val targetFile = File(photoDir(context), "$folder/$fileName")
>>>>>>> zipcopy/main
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
<<<<<<< HEAD
                put(MediaStore.Images.Media.RELATIVE_PATH, photoRelativePath())
=======
                put(MediaStore.Images.Media.RELATIVE_PATH, photoRelativePath(folder))
>>>>>>> zipcopy/main
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create photo media item")
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open photo media item")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            sourceFile.copyTo(targetFile, overwrite = true)
        }
        targetFile.setLastModified(sourceFile.lastModified())
        return targetFile
    }

    fun photoDir(context: Context): File {
        val baseDir = publicMediaDir(Environment.DIRECTORY_PICTURES) ?: context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        return File(baseDir, DIR_NAME).apply { mkdirs() }
    }

<<<<<<< HEAD
    fun photoRelativePath(): String {
        return "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/"
=======
    fun photoRelativePath(tripFolderName: String? = null): String {
        val folder = canonicalTripFolderName(tripFolderName)
        return if (folder.isNullOrBlank()) {
            "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/"
        } else {
            "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/$folder/"
        }
    }

    fun shareExportFile(context: Context, sourceName: String, timeMs: Long = System.currentTimeMillis()): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, Environment.DIRECTORY_MOVIES)
        val dir = File(baseDir, "$DIR_NAME/$SHARE_DIR_NAME").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))
        val safeSource = sanitizeVideoBaseName(sourceName.removeSuffix(".mp4")).ifBlank { "carspeed" }
        return File(dir, "$safeSource-share-$stamp.mp4")
    }

    fun publishShareVideoToGallery(context: Context, sourceFile: File, fileName: String = sourceFile.name): File {
        val publicRoot = publicMediaDir(Environment.DIRECTORY_MOVIES)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val targetFile = File(publicRoot, "$DIR_NAME/$SHARE_DIR_NAME/$fileName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$DIR_NAME/$SHARE_DIR_NAME/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create share video media item")
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open share video media item")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = true)
        }
        targetFile.setLastModified(sourceFile.lastModified())
        return targetFile
>>>>>>> zipcopy/main
    }

    fun photoFiles(context: Context): List<File> {
        return photoDirs(context).flatMap { dir ->
<<<<<<< HEAD
            dir.listFiles()
            ?.filter { isAppPhotoFile(it) }
            .orEmpty()
=======
            dir.walkTopDown().filter { isAppPhotoFile(it) }.toList()
>>>>>>> zipcopy/main
        }.distinctBy { it.absolutePath }
    }

    fun latestPhotoFile(context: Context): File? {
        return photoFiles(context).maxByOrNull { it.lastModified() }
    }

    fun deletePhoto(photoFile: File): Boolean {
        return isAppPhotoFile(photoFile) && (photoFile.delete() || !photoFile.exists())
    }

    fun deleteAllPhotos(context: Context): Int {
        return photoFiles(context).count { deletePhoto(it) }
    }

    fun subtitleFileFor(videoFile: File): File {
<<<<<<< HEAD
        return File(videoFile.parentFile, videoFile.nameWithoutExtension + ".srt")
    }

    fun trackFileFor(videoFile: File): File {
        return File(videoFile.parentFile, videoFile.nameWithoutExtension + ".track.json")
    }

    fun favoriteFileFor(videoFile: File): File {
        return File(videoFile.parentFile, videoFile.nameWithoutExtension + ".favorite")
=======
        return resolveCompanion(videoFile, ".srt")
    }

    fun trackFileFor(videoFile: File): File {
        return resolveCompanion(videoFile, ".track.json")
    }

    fun trackFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".track.json")
    }

    fun trackFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".track.json")
    }

    fun plateFileFor(videoFile: File): File {
        return resolveCompanion(videoFile, ".plate.json")
    }

    fun plateFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".plate.json")
    }

    fun plateFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".plate.json")
    }

    fun favoriteFileFor(videoFile: File): File {
        return resolveCompanion(videoFile, ".favorite")
    }

    fun favoriteFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".favorite")
    }

    fun favoriteFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".favorite")
>>>>>>> zipcopy/main
    }

    fun videoFiles(context: Context): List<File> {
        return videoDirs(context).flatMap { videoFiles(it) }.distinctBy { it.absolutePath }
    }

<<<<<<< HEAD
=======
    fun recordingDurationMs(file: File): Long {
        val match = VIDEO_STAMP.find(file.name) ?: return 0L
        val startMs = parseCompactDateTime(match.groupValues[1], match.groupValues[2]) ?: return 0L
        val endMs = file.lastModified()
        if (endMs <= 0L) return 0L
        return (endMs - startMs).coerceAtLeast(0L)
    }

>>>>>>> zipcopy/main
    fun latestVideoFile(context: Context): File? {
        return videoFiles(context).maxByOrNull { it.lastModified() }
    }

    fun videoTrips(context: Context): List<VideoTrip> {
        return videoFiles(context)
            .groupBy { tripGroupKey(it) }
            .map { (key, files) ->
                val sorted = files.sortedWith(compareBy<File> { it.parentFile?.absolutePath.orEmpty() }.thenBy { it.name })
                val first = sorted.first()
                val parent = first.parentFile ?: videoDir(context)
<<<<<<< HEAD
                val isTripDir = parent.name.startsWith(TRIP_PREFIX)
                VideoTrip(
                    id = key,
                    title = if (isTripDir) parent.name else first.nameWithoutExtension,
=======
                val isTripDir = isTripDirectoryName(parent.name)
                val flatToken = galleryTripToken(first.name)?.takeIf { parent.name == DIR_NAME }
                VideoTrip(
                    id = flatToken ?: key,
                    title = when {
                        isTripDir -> parent.name
                        flatToken != null -> flatToken
                        else -> first.nameWithoutExtension
                    },
>>>>>>> zipcopy/main
                    directory = if (isTripDir) parent else first.parentFile ?: videoDir(context),
                    segments = sorted
                )
            }
    }

    fun companionFilesFor(videoFile: File): List<File> {
        if (!isAppVideoFile(videoFile)) return emptyList()
<<<<<<< HEAD
        return listOf(videoFile, subtitleFileFor(videoFile), trackFileFor(videoFile), favoriteFileFor(videoFile))
=======
        return listOf(videoFile, subtitleFileFor(videoFile), trackFileFor(videoFile), plateFileFor(videoFile), favoriteFileFor(videoFile))
            .filter { it.exists() && it.isFile }
    }

    fun companionFilesFor(context: Context, videoFile: File): List<File> {
        if (!isAppVideoFile(videoFile)) return emptyList()
        return (
            listOf(videoFile, subtitleFileFor(videoFile)) +
                trackFilesFor(context, videoFile) +
                plateFilesFor(context, videoFile) +
                favoriteFilesFor(context, videoFile)
            )
            .distinctBy { it.absolutePath }
>>>>>>> zipcopy/main
            .filter { it.exists() && it.isFile }
    }

    fun companionFilesFor(trip: VideoTrip): List<File> {
        return trip.segments.flatMap { companionFilesFor(it) }.distinctBy { it.absolutePath }
    }

<<<<<<< HEAD
=======
    fun companionFilesFor(context: Context, trip: VideoTrip): List<File> {
        return trip.segments.flatMap { companionFilesFor(context, it) }.distinctBy { it.absolutePath }
    }

    fun favoriteTripIds(context: Context, trips: List<VideoTrip>): Set<String> {
        if (trips.isEmpty()) return emptySet()
        val names = HashSet<String>()
        privateVideoDataRoot(context).list()?.let { names.addAll(it) }
        galleryAlbumDir().list()?.let { names.addAll(it) }
        val marked = favoriteIdsFromMarkers(trips, names).toMutableSet()
        trips.forEach { trip ->
            if (trip.directory.name != DIR_NAME && File(trip.directory, ".favorite").isFile) {
                marked.add(trip.id)
            }
        }
        return marked
    }

    internal fun favoriteIdsFromMarkers(trips: List<VideoTrip>, markerNames: Set<String>): Set<String> {
        if (trips.isEmpty() || markerNames.isEmpty()) return emptySet()
        return trips.mapNotNull { trip ->
            val tokenMarker = ".${sanitizeFolderName(trip.id)}.favorite"
            val segmentMarked = trip.segments.any { segment ->
                "${segment.nameWithoutExtension}.favorite" in markerNames
            }
            trip.id.takeIf { tokenMarker in markerNames || segmentMarked }
        }.toSet()
    }

>>>>>>> zipcopy/main
    fun isFavorite(videoFile: File): Boolean {
        return favoriteFileFor(videoFile).exists()
    }

<<<<<<< HEAD
    fun isFavorite(trip: VideoTrip): Boolean {
        return trip.segments.any { isFavorite(it) } || favoriteFileForTrip(trip).exists()
=======
    fun isFavorite(context: Context, videoFile: File): Boolean {
        return favoriteFilesFor(context, videoFile).any { it.exists() }
    }

    fun isFavorite(trip: VideoTrip): Boolean {
        return trip.segments.any { isFavorite(it) } ||
            favoriteFileForTrip(trip).exists() ||
            legacyFavoriteMarkers(trip.id).any { it.exists() }
    }

    fun isFavorite(context: Context, trip: VideoTrip): Boolean {
        return trip.segments.any { isFavorite(context, it) } || favoriteFilesForTrip(context, trip).any { it.exists() }
>>>>>>> zipcopy/main
    }

    fun setFavorite(videoFile: File, favorite: Boolean): Boolean {
        if (!isAppVideoFile(videoFile)) return false
        val marker = favoriteFileFor(videoFile)
        return if (favorite) {
            marker.writeText("favorite", Charsets.UTF_8)
            true
        } else {
            marker.delete() || !marker.exists()
        }
    }

<<<<<<< HEAD
=======
    fun setFavorite(context: Context, videoFile: File, favorite: Boolean): Boolean {
        if (!isAppVideoFile(videoFile)) return false
        val marker = favoriteFileFor(context, videoFile)
        return if (favorite) {
            marker.parentFile?.mkdirs()
            marker.writeText("favorite", Charsets.UTF_8)
            true
        } else {
            favoriteFilesFor(context, videoFile).all { deleteIfExists(it) }
        }
    }

>>>>>>> zipcopy/main
    fun setFavorite(trip: VideoTrip, favorite: Boolean): Boolean {
        val marker = favoriteFileForTrip(trip)
        return if (favorite) {
            marker.writeText("favorite", Charsets.UTF_8)
            true
        } else {
            val tripDeleted = marker.delete() || !marker.exists()
            val segmentDeleted = trip.segments.all { setFavorite(it, false) }
            tripDeleted && segmentDeleted
        }
    }

<<<<<<< HEAD
=======
    fun setFavorite(context: Context, trip: VideoTrip, favorite: Boolean): Boolean {
        val marker = favoriteFileForTrip(context, trip)
        return if (favorite) {
            marker.parentFile?.mkdirs()
            marker.writeText("favorite", Charsets.UTF_8)
            true
        } else {
            val tripDeleted = favoriteFilesForTrip(context, trip).all { deleteIfExists(it) }
            val segmentDeleted = trip.segments.all { setFavorite(context, it, false) }
            tripDeleted && segmentDeleted
        }
    }

>>>>>>> zipcopy/main
    fun renameVideoGroup(videoFile: File, requestedName: String): File? {
        if (!isAppVideoFile(videoFile)) return null
        val baseName = sanitizeVideoBaseName(requestedName).ifBlank { return null }
        val targetVideo = File(videoFile.parentFile, "$baseName.mp4")
        if (targetVideo.exists() || targetVideo == videoFile) return null

        val originalModified = videoFile.lastModified()
        val oldSubtitle = subtitleFileFor(videoFile)
        val oldTrack = trackFileFor(videoFile)
        if (!videoFile.renameTo(targetVideo)) return null
        targetVideo.setLastModified(originalModified)

        listOf(
            oldSubtitle to subtitleFileFor(targetVideo),
            oldTrack to trackFileFor(targetVideo),
<<<<<<< HEAD
=======
            plateFileFor(videoFile) to plateFileFor(targetVideo),
>>>>>>> zipcopy/main
            favoriteFileFor(videoFile) to favoriteFileFor(targetVideo)
        ).forEach { (from, to) ->
            if (from.exists() && !to.exists()) {
                val modified = from.lastModified()
                if (from.renameTo(to)) to.setLastModified(modified)
            }
        }
        return targetVideo
    }

<<<<<<< HEAD
=======
    fun renameVideoGroup(context: Context, videoFile: File, requestedName: String): File? {
        if (!isAppVideoFile(videoFile)) return null
        val baseName = sanitizeVideoBaseName(requestedName).ifBlank { return null }
        val targetVideo = File(videoFile.parentFile, "$baseName.mp4")
        if (targetVideo.exists() || targetVideo == videoFile) return null

        val originalModified = videoFile.lastModified()
        val oldSubtitle = subtitleFileFor(videoFile)
        if (!videoFile.renameTo(targetVideo)) return null
        targetVideo.setLastModified(originalModified)

        renameIfExists(oldSubtitle, subtitleFileFor(targetVideo))
        moveCompanionCandidates(trackFilesFor(context, videoFile), trackFileFor(context, targetVideo))
        moveCompanionCandidates(plateFilesFor(context, videoFile), plateFileFor(context, targetVideo))
        moveCompanionCandidates(favoriteFilesFor(context, videoFile), favoriteFileFor(context, targetVideo))
        return targetVideo
    }

>>>>>>> zipcopy/main
    fun deleteVideoGroup(videoFile: File): Int {
        return companionFilesFor(videoFile).count { file -> file.delete() || !file.exists() }
    }

<<<<<<< HEAD
    fun deleteVideoTrip(trip: VideoTrip): Int {
        val filesDeleted = companionFilesFor(trip).count { file -> file.delete() || !file.exists() }
        val favoriteDeleted = favoriteFileForTrip(trip).takeIf { it.exists() }?.let { if (it.delete() || !it.exists()) 1 else 0 } ?: 0
        if (trip.directory.name.startsWith(TRIP_PREFIX)) {
            trip.directory.delete()
        }
=======
    fun deleteVideoGroup(context: Context, videoFile: File): Int {
        return companionFilesFor(context, videoFile).count { deleteIfExists(it) }
    }

    fun deleteVideoTrip(trip: VideoTrip): Int {
        val filesDeleted = companionFilesFor(trip).count { file -> file.delete() || !file.exists() }
        val favoriteDeleted = favoriteFileForTrip(trip).takeIf { it.exists() }?.let { if (it.delete() || !it.exists()) 1 else 0 } ?: 0
        if (isTripDirectoryName(trip.directory.name)) {
            trip.directory.delete()
        }
        deleteLegacyTripDir(trip.id)
        return filesDeleted + favoriteDeleted
    }

    fun deleteVideoTrip(context: Context, trip: VideoTrip): Int {
        val filesDeleted = companionFilesFor(context, trip).count { deleteIfExists(it) }
        val favoriteDeleted = favoriteFilesForTrip(context, trip)
            .filter { it.exists() }
            .count { deleteIfExists(it) }
        if (isTripDirectoryName(trip.directory.name)) {
            trip.directory.delete()
            privateMirrorDirFor(context, trip.directory).delete()
        }
        deleteLegacyTripDir(trip.id)
>>>>>>> zipcopy/main
        return filesDeleted + favoriteDeleted
    }

    fun deleteVideoGroups(videoFiles: List<File>): Int {
        return videoFiles.distinctBy { it.absolutePath }.sumOf { deleteVideoGroup(it) }
    }

<<<<<<< HEAD
    fun deleteAllVideos(context: Context): Int {
        return videoTrips(context).sumOf { deleteVideoTrip(it) }
=======
    fun deleteVideoGroups(context: Context, videoFiles: List<File>): Int {
        return videoFiles.distinctBy { it.absolutePath }.sumOf { deleteVideoGroup(context, it) }
    }

    fun deleteAllVideos(context: Context): Int {
        return videoTrips(context).sumOf { deleteVideoTrip(context, it) }
>>>>>>> zipcopy/main
    }

    fun videoFiles(dir: File): List<File> {
        return dir.walkTopDown()
<<<<<<< HEAD
            .filter { isAppVideoFile(it) }
            .toList()
    }

    private fun videoDirs(context: Context): List<File> {
        return listOfNotNull(
            videoDir(context),
            legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES)
        ).distinctBy { it.absolutePath }.onEach { it.mkdirs() }
=======
            .filter { isAppVideoFile(it) && !isShareExportFile(it) }
            .toList()
    }

    fun consolidateIntoAlbum(context: Context) {
        synchronized(consolidateLock) {
            val roots = listOf(
                galleryAlbumDir(),
                sharedAlbumRoot(context),
                legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES),
                existingPublicVideoDir()
            )
            roots.filterNotNull()
                .distinctBy { it.absolutePath }
                .flatMap { videoFiles(it) }
                .filter { file -> !isInCanonicalTripFolder(file) }
                .filter { System.currentTimeMillis() - it.lastModified() > 15_000L }
                .forEach { file ->
                    runCatching { publishIntoGalleryAlbum(context, file) }
                }
        }
    }

    @Volatile
    private var cachedVideoDirs: List<File>? = null

    private fun videoDirs(context: Context): List<File> {
        cachedVideoDirs?.let { return it }
        val dirs = listOfNotNull(
            galleryAlbumDir().takeIf { it.isDirectory || it.mkdirs() },
            videoDir(context),
            legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES),
            existingPublicVideoDir()
        ).distinctBy { it.absolutePath }.onEach { dir ->
            if (dir.absolutePath != galleryAlbumDir().absolutePath) dir.mkdirs()
        }
        cachedVideoDirs = dirs
        return dirs
>>>>>>> zipcopy/main
    }

    private fun photoDirs(context: Context): List<File> {
        return listOfNotNull(
            photoDir(context),
            legacyAppMediaDir(context, Environment.DIRECTORY_PICTURES)
        ).distinctBy { it.absolutePath }.onEach { it.mkdirs() }
    }

<<<<<<< HEAD
=======
    /**
     * 应用自己的共享媒体目录。系统媒体库会扫描这里，应用也可以直接用文件路径读写。
     */
    @Suppress("DEPRECATION")
    private fun sharedAlbumRoot(context: Context): File {
        val mediaRoot = context.externalMediaDirs.firstOrNull { dir -> dir != null }
            ?: context.getExternalFilesDir(null)?.parentFile?.parentFile?.let { androidDir ->
                File(androidDir, "media/${context.packageName}")
            }
            ?: File(context.filesDir, "media")
        return File(mediaRoot, DIR_NAME).apply { mkdirs() }
    }

    private fun existingPublicVideoDir(): File? {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) ?: return null
        val dir = File(movies, DIR_NAME)
        return dir.takeIf { it.isDirectory }
    }

    private fun legacyVideoRoots(context: Context): List<File> {
        return listOfNotNull(
            legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES),
            existingPublicVideoDir()
        ).distinctBy { it.absolutePath }
    }

    private fun moveTripDirectory(context: Context, source: File, dest: File) {
        val oldPaths = source.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
        dest.parentFile?.mkdirs()
        val moved = source.renameTo(dest) || runCatching {
            source.copyRecursively(dest, overwrite = false)
            if (!source.deleteRecursively()) {
                dest.deleteRecursively()
                false
            } else {
                dest.isDirectory
            }
        }.getOrDefault(false)
        if (!moved) return
        dest.walkTopDown().filter { isAppVideoFile(it) }.forEach { video ->
            MediaScannerConnection.scanFile(context, arrayOf(video.absolutePath), arrayOf("video/mp4"), null)
        }
        oldPaths.forEach { path ->
            forgetMediaPath(context, path)
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }

    private fun forgetMediaPath(context: Context, path: String) {
        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        )
        collections.forEach { collection ->
            runCatching {
                context.contentResolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DATA}=?",
                    arrayOf(path)
                )
            }
        }
    }

    private fun parseCompactDateTime(date: String, time: String): Long? {
        return runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("$date-$time")?.time
        }.getOrNull()
    }

>>>>>>> zipcopy/main
    @Suppress("DEPRECATION")
    private fun publicMediaDir(type: String): File? {
        return Environment.getExternalStoragePublicDirectory(type)?.takeIf { parent ->
            parent.exists() || parent.mkdirs()
        }
    }

    private fun legacyAppMediaDir(context: Context, type: String): File? {
        val baseDir = context.getExternalFilesDir(type) ?: return null
        return File(baseDir, DIR_NAME)
    }

    private fun tripGroupKey(video: File): String {
        val parent = video.parentFile
<<<<<<< HEAD
        return if (parent != null && parent.name.startsWith(TRIP_PREFIX)) {
=======
        val flatToken = parent?.takeIf { it.name == DIR_NAME }?.let { galleryTripToken(video.name) }
        if (flatToken != null) return flatToken
        return if (parent != null && isTripDirectoryName(parent.name)) {
>>>>>>> zipcopy/main
            parent.absolutePath
        } else {
            video.absolutePath
        }
    }

    private fun favoriteFileForTrip(trip: VideoTrip): File {
<<<<<<< HEAD
        return File(trip.directory, ".favorite")
    }

    private fun cleanupGroupKey(file: File): String {
        val parent = file.parentFile
        return if (parent != null && AUTO_TRIP_NAME.matches(parent.name)) {
=======
        return tripFavoriteMarker(trip.directory, trip.id)
    }

    private fun favoriteFileForTrip(context: Context, trip: VideoTrip): File {
        val mirror = privateMirrorDirFor(context, trip.directory)
        return if (trip.directory.name == DIR_NAME) {
            File(mirror, ".${sanitizeFolderName(trip.id)}.favorite")
        } else {
            File(mirror, ".favorite")
        }
    }

    private fun favoriteFilesForTrip(context: Context, trip: VideoTrip): List<File> {
        return (listOf(favoriteFileForTrip(context, trip), favoriteFileForTrip(trip)) + legacyFavoriteMarkers(trip.id))
            .distinctBy { it.absolutePath }
    }

    private fun cleanupGroupKey(file: File): String {
        galleryTripToken(file.name)?.takeIf { file.parentFile?.name == DIR_NAME }?.let { return it }
        val parent = file.parentFile
        return if (parent != null && isTripDirectoryName(parent.name)) {
>>>>>>> zipcopy/main
            parent.absolutePath
        } else {
            File(file.parentFile, companionBaseName(file)).absolutePath
        }
    }

    private fun isTripProtected(file: File): Boolean {
        val parent = file.parentFile ?: return false
<<<<<<< HEAD
        return File(parent, ".favorite").exists()
    }

    fun isAutoGeneratedVideoFile(file: File): Boolean {
        return isAppVideoFile(file) && AUTO_VIDEO_NAME.matches(file.nameWithoutExtension)
=======
        if (File(parent, ".favorite").exists()) return true
        val token = galleryTripToken(file.name) ?: return false
        if (File(parent, ".${sanitizeFolderName(token)}.favorite").exists()) return true
        return legacyFavoriteMarkers(token).any { it.exists() }
    }

    fun isAutoGeneratedVideoFile(file: File): Boolean {
        return isAppVideoFile(file) && AUTO_VIDEO_NAME.matches(gallerySegmentBase(file.nameWithoutExtension))
>>>>>>> zipcopy/main
    }

    fun cleanupLoopStorage(context: Context, limitGb: Int, requiredFreeBytes: Long = 0L): Boolean {
        val dir = videoDir(context)
<<<<<<< HEAD
=======
        val dirs = videoDirs(context)
>>>>>>> zipcopy/main
        val limitBytes = limitGb.coerceIn(
            DashcamSettings.MIN_LOOP_STORAGE_GB,
            DashcamSettings.MAX_LOOP_STORAGE_GB
        ) * 1024L * 1024L * 1024L
        val plan = planCleanup(
<<<<<<< HEAD
            files = managedVideoFiles(dir),
=======
            files = dirs.flatMap { managedVideoFiles(context, it) }.distinctBy { it.absolutePath },
>>>>>>> zipcopy/main
            limitBytes = limitBytes,
            usableSpaceBytes = dir.usableSpace,
            requiredFreeBytes = requiredFreeBytes
        )
<<<<<<< HEAD
        plan.filesToDelete.forEach { it.delete() }
=======
        plan.filesToDelete.filter { isAppVideoFile(it) }.forEach { deleteVideoGroup(context, it) }
        plan.filesToDelete.filterNot { isAppVideoFile(it) }.forEach { deleteIfExists(it) }
>>>>>>> zipcopy/main

        return dir.usableSpace >= requiredFreeTarget(requiredFreeBytes)
    }

    fun planCleanup(
        files: List<File>,
        limitBytes: Long,
        usableSpaceBytes: Long,
        requiredFreeBytes: Long,
        minFreeBytes: Long = MIN_FREE_BYTES
    ): CleanupPlan {
        val remaining = files.toMutableList()
        val deleted = mutableListOf<File>()
        var projectedFreeBytes = usableSpaceBytes
        val targetFreeBytes = minFreeBytes + requiredFreeBytes.coerceAtLeast(0L)

        fun totalBytes(): Long = remaining.sumOf { it.length() }
        fun deleteOldest(): Boolean {
            val oldest = remaining.minByOrNull { it.lastModified() } ?: return false
            val groupKey = cleanupGroupKey(oldest)
            val companions = remaining.filter { cleanupGroupKey(it) == groupKey }
            companions.forEach { file ->
                projectedFreeBytes += file.length()
                deleted += file
                remaining.remove(file)
            }
            return true
        }

        while (remaining.isNotEmpty() && (totalBytes() > limitBytes || projectedFreeBytes < targetFreeBytes)) {
            if (!deleteOldest()) break
        }

        return CleanupPlan(
            filesToDelete = deleted,
            hasEnoughSpaceAfterCleanup = projectedFreeBytes >= targetFreeBytes,
            projectedFreeBytes = projectedFreeBytes,
            projectedManagedBytes = totalBytes()
        )
    }

    fun estimateSegmentBytes(mode: VideoMode, durationMs: Long): Long {
        val seconds = (durationMs / 1000L).coerceAtLeast(1L)
        val bitsPerPixelFrame = 0.12
        val estimatedBitsPerSecond = mode.width.toDouble() * mode.height.toDouble() * mode.fps * bitsPerPixelFrame
        val estimatedBytes = (estimatedBitsPerSecond / 8.0 * seconds).toLong()
        return estimatedBytes.coerceAtLeast(150L * 1024L * 1024L)
    }

    private fun requiredFreeTarget(requiredFreeBytes: Long): Long {
        return MIN_FREE_BYTES + requiredFreeBytes.coerceAtLeast(0L)
    }

    fun managedVideoFiles(dir: File): List<File> {
        return dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    isAutoGeneratedCompanionFile(file) &&
                    (
<<<<<<< HEAD
                    file.extension.equals("mp4", ignoreCase = true) ||
                        file.extension.equals("srt", ignoreCase = true) ||
                        file.name.endsWith(".track.json", ignoreCase = true) ||
=======
                        file.extension.equals("mp4", ignoreCase = true) ||
                        file.extension.equals("srt", ignoreCase = true) ||
                        file.name.endsWith(".track.json", ignoreCase = true) ||
                        file.name.endsWith(".plate.json", ignoreCase = true) ||
>>>>>>> zipcopy/main
                        file.name.endsWith(".favorite", ignoreCase = true)
                        )
            }
            .toList()
    }

<<<<<<< HEAD
    private fun companionBaseName(file: File): String {
        return if (file.name.endsWith(".track.json", ignoreCase = true)) {
            file.name.dropLast(".track.json".length)
        } else {
            file.nameWithoutExtension
        }
    }

=======
    fun managedVideoFiles(context: Context, dir: File): List<File> {
        return dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    isAutoGeneratedCompanionFile(context, file) &&
                    (
                        file.extension.equals("mp4", ignoreCase = true) ||
                            file.extension.equals("srt", ignoreCase = true) ||
                            file.name.endsWith(".track.json", ignoreCase = true) ||
                            file.name.endsWith(".plate.json", ignoreCase = true) ||
                            file.name.endsWith(".favorite", ignoreCase = true)
                        )
            }
            .toList()
    }

    private fun companionBaseName(file: File): String {
        return when {
            file.name.endsWith(".track.json", ignoreCase = true) -> file.name.dropLast(".track.json".length)
            file.name.endsWith(".plate.json", ignoreCase = true) -> file.name.dropLast(".plate.json".length)
            else -> file.nameWithoutExtension
        }
    }

    private fun legacyCompanionFileFor(videoFile: File, suffix: String): File {
        return File(videoFile.parentFile, videoFile.nameWithoutExtension + suffix)
    }

    private fun privateCompanionFileFor(context: Context, videoFile: File, suffix: String): File {
        val dir = privateMirrorDirFor(context, videoFile.parentFile ?: videoDir(context))
        return File(dir, videoFile.nameWithoutExtension + suffix)
    }

    private fun companionCandidates(context: Context, videoFile: File, suffix: String): List<File> {
        val storageRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val legacy = if (storageRoot == null) {
            emptyList()
        } else {
            legacyCompanionFiles(videoFile, suffix, storageRoot)
        }
        return (
            listOf(privateCompanionFileFor(context, videoFile, suffix), legacyCompanionFileFor(videoFile, suffix)) +
                legacy
            )
            .distinctBy { it.absolutePath }
    }

    private fun privateVideoDataRoot(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, Environment.DIRECTORY_MOVIES)
        return File(baseDir, DIR_NAME).apply { mkdirs() }
    }

    private fun privateMirrorDirFor(context: Context, sourceDir: File): File {
        val root = privateVideoDataRoot(context)
        val relative = relativePathForKnownVideoRoot(context, sourceDir)
        val dir = if (relative.isNullOrBlank()) {
            root
        } else {
            File(root, relative)
        }
        return dir
    }

    private fun relativePathForKnownVideoRoot(context: Context, sourceDir: File): String? {
        val roots = listOfNotNull(
            sharedAlbumRoot(context),
            existingPublicVideoDir(),
            legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES)
        )
        return roots.asSequence()
            .mapNotNull { root -> childRelativePath(root, sourceDir) }
            .firstOrNull()
    }

    private fun childRelativePath(root: File, child: File): String? {
        val rootPath = root.absoluteFile.path.trimEnd(File.separatorChar)
        val childPath = child.absoluteFile.path
        if (childPath == rootPath) return ""
        val prefix = rootPath + File.separator
        return if (childPath.startsWith(prefix)) childPath.removePrefix(prefix) else null
    }

    private fun renameIfExists(from: File, to: File) {
        if (!from.exists() || to.exists()) return
        to.parentFile?.mkdirs()
        val modified = from.lastModified()
        if (from.renameTo(to)) to.setLastModified(modified)
    }

    private fun moveCompanionCandidates(fromFiles: List<File>, to: File) {
        fromFiles.distinctBy { it.absolutePath }.forEach { from ->
            if (!from.exists()) return@forEach
            if (from.absolutePath == to.absolutePath) return@forEach
            to.parentFile?.mkdirs()
            val modified = from.lastModified()
            val moved = if (!to.exists()) {
                from.renameTo(to) || runCatching {
                    from.copyTo(to, overwrite = false)
                    from.delete()
                    true
                }.getOrDefault(false)
            } else {
                deleteIfExists(from)
            }
            if (moved && to.exists()) to.setLastModified(modified)
        }
    }

    private fun deleteIfExists(file: File): Boolean {
        return file.delete() || !file.exists()
    }

>>>>>>> zipcopy/main
    private fun isAppVideoFile(file: File): Boolean {
        return file.isFile && file.extension.equals("mp4", ignoreCase = true)
    }

<<<<<<< HEAD
=======
    private fun isShareExportFile(file: File): Boolean {
        return file.parentFile?.name == SHARE_DIR_NAME
    }

>>>>>>> zipcopy/main
    private fun isAppPhotoFile(file: File): Boolean {
        return file.isFile && file.extension.equals("jpg", ignoreCase = true)
    }

    private fun isAutoGeneratedCompanionFile(file: File): Boolean {
        val baseName = companionBaseName(file)
        val favorite = File(file.parentFile, "$baseName.favorite")
<<<<<<< HEAD
        return AUTO_VIDEO_NAME.matches(baseName) && !favorite.exists() && !isTripProtected(file)
=======
        return AUTO_VIDEO_NAME.matches(gallerySegmentBase(baseName)) && !favorite.exists() && !isTripProtected(file)
    }

    private fun isAutoGeneratedCompanionFile(context: Context, file: File): Boolean {
        val baseName = companionBaseName(file)
        val videoFile = File(file.parentFile, "$baseName.mp4")
        return AUTO_VIDEO_NAME.matches(gallerySegmentBase(baseName)) &&
            !favoriteFilesFor(context, videoFile).any { it.exists() } &&
            !isTripProtected(context, file)
    }

    private fun isTripProtected(context: Context, file: File): Boolean {
        if (isTripProtected(file)) return true
        val parent = file.parentFile ?: return false
        val mirror = privateMirrorDirFor(context, parent)
        if (File(mirror, ".favorite").exists()) return true
        val token = galleryTripToken(file.name) ?: return false
        if (File(mirror, ".${sanitizeFolderName(token)}.favorite").exists()) return true
        return legacyFavoriteMarkers(token).any { it.exists() }
    }

    private fun tripFavoriteMarker(directory: File, tripId: String): File {
        return if (directory.name == DIR_NAME) {
            File(directory, ".${sanitizeFolderName(tripId)}.favorite")
        } else {
            File(directory, ".favorite")
        }
    }

    private fun gallerySegmentBase(baseName: String): String {
        val token = baseName.substringBefore("__", missingDelimiterValue = "")
        if (token.isEmpty() || token == baseName || !isTripDirectoryName(token)) return baseName
        return baseName.removePrefix("${token}__")
    }

    fun galleryAlbumDir(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), DIR_NAME)
    }

    private fun isInCanonicalTripFolder(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (parent.parentFile?.absolutePath != galleryAlbumDir().absolutePath) return false
        val folder = canonicalTripFolderName(parent.name) ?: return false
        return parent.name == folder && galleryTripToken(file.name) == null
    }

    fun publishIntoGalleryAlbum(context: Context, sourceFile: File, scannedUri: Uri? = null): File {
        if (!isAppVideoFile(sourceFile) || isShareExportFile(sourceFile)) return sourceFile
        val album = galleryAlbumDir()
        val folder = canonicalTripFolderName(tripTokenFor(sourceFile)) ?: return sourceFile
        val displayName = galleryFileName(folder, sourceFile.name)
        val destDir = File(album, folder)
        if (sourceFile.parentFile?.absolutePath == destDir.absolutePath && sourceFile.name == displayName) {
            return sourceFile
        }
        val uri = scannedUri ?: videoUriForPath(context, sourceFile.absolutePath) ?: return sourceFile
        val sidecars = listOf(
            subtitleFileFor(sourceFile),
            trackFileFor(sourceFile),
            plateFileFor(sourceFile),
            favoriteFileFor(sourceFile)
        ).filter { it.exists() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DIR_NAME/$folder/")
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        }
        if (context.contentResolver.update(uri, values, null, null) == 0) return sourceFile
        val dest = File(destDir, displayName)
        sidecars.forEach { sidecar ->
            val targetName = sidecar.name.replaceFirst(sourceFile.nameWithoutExtension, dest.nameWithoutExtension)
            moveBeside(sidecar, File(dest.parentFile, targetName))
        }
        return dest
    }

    private fun videoUriForPath(context: Context, path: String): Uri? {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DATA}=?",
            arrayOf(path),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
    }

    private fun tripTokenFor(file: File): String? {
        file.parentFile?.name?.takeIf { isTripDirectoryName(it) }?.let { return it }
        galleryTripToken(file.name)?.let { return it }
        val match = AUTO_VIDEO_NAME.matchEntire(file.nameWithoutExtension) ?: return null
        val token = "$TRIP_PREFIX${match.value.removePrefix("carspeed-")}"
        return token.takeIf { legacyTripDirs(token).any { dir -> dir.isDirectory } }
    }

    private fun photoStampFolder(fileName: String): String? {
        val match = Regex("carspeed-photo-(\\d{8})-(\\d{6})").find(fileName) ?: return null
        val startMs = parseCompactDateTime(match.groupValues[1], match.groupValues[2]) ?: return null
        return tripFolderNameFor(startMs)
    }

    private fun resolveCompanion(videoFile: File, suffix: String): File {
        val beside = File(videoFile.parentFile, videoFile.nameWithoutExtension + suffix)
        if (beside.exists()) return beside
        val storageRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return beside
        return legacyCompanionFiles(videoFile, suffix, storageRoot).firstOrNull { it.exists() } ?: beside
    }

    /**
     * 视频搬进 DCIM/CarSpeed/起始时间/ 之后，轨迹和字幕仍可能留在
     * Android/media 或 Movies 下的 carspeed-trip- 旧目录里。
     */
    internal fun legacyCompanionFiles(videoFile: File, suffix: String, storageRoot: File): List<File> {
        val tokens = listOfNotNull(
            galleryTripToken(videoFile.name),
            videoFile.parentFile?.name?.takeIf { isTripDirectoryName(it) }
        )
        if (tokens.isEmpty()) return emptyList()
        val legacyName = gallerySegmentBase(videoFile.nameWithoutExtension) + suffix
        return tokens.flatMap { token -> legacyTripDirs(token, storageRoot) }
            .map { File(it, legacyName) }
    }

    private fun tripFolderAliases(name: String): List<String> {
        val names = LinkedHashSet<String>()
        names.add(name)
        val startMs = tripFolderStartTimeMs(name) ?: return names.toList()
        names.add(tripFolderNameFor(startMs))
        val compact = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        names.add("$TRIP_PREFIX$compact")
        names.add("$TRIP_FOLDER_PREFIX${SimpleDateFormat("yyyy-MM-dd HH'点'mm'分'ss'秒'", Locale.US).format(Date(startMs))}")
        return names.toList()
    }

    private fun legacyTripDirs(token: String): List<File> {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return emptyList()
        return legacyTripDirs(token, root)
    }

    private fun legacyTripDirs(token: String, root: File): List<File> {
        if (!isTripDirectoryName(token)) return emptyList()
        val parents = listOf(
            "${Environment.DIRECTORY_DCIM}/$DIR_NAME",
            "${Environment.DIRECTORY_PICTURES}/$DIR_NAME",
            "${Environment.DIRECTORY_MOVIES}/$DIR_NAME",
            "Android/media/com.thomas.carspeed/$DIR_NAME",
            "Android/data/com.thomas.carspeed/files/${Environment.DIRECTORY_MOVIES}/$DIR_NAME"
        )
        return tripFolderAliases(token).flatMap { name ->
            parents.map { parent -> File(root, "$parent/$name") }
        }
    }

    private fun legacyFavoriteMarkers(tripId: String): List<File> {
        if (!isTripDirectoryName(tripId)) return emptyList()
        return legacyTripDirs(tripId).map { File(it, ".favorite") }
    }

    private fun deleteLegacyTripDir(tripId: String) {
        if (!isTripDirectoryName(tripId)) return
        legacyTripDirs(tripId).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { child -> if (child.isFile) child.delete() }
            dir.delete()
        }
    }

    private fun moveBeside(source: File, target: File) {
        if (!source.exists() || target.exists()) return
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        runCatching {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
>>>>>>> zipcopy/main
    }

    private fun sanitizeVideoBaseName(name: String): String {
        return name.trim()
            .removeSuffix(".mp4")
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(80)
    }

    private fun sanitizeFolderName(name: String): String {
        return sanitizeVideoBaseName(name).ifBlank { tripFolderNameFor(System.currentTimeMillis()) }
    }

    fun displayPath(file: File): String {
        return file.absolutePath
    }
}
