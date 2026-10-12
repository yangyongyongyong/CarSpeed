package com.thomas.carspeed

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoStorage {
    private const val DIR_NAME = "CarSpeed"
    private const val SHARE_DIR_NAME = "Share"
    private const val TRIP_PREFIX = "carspeed-trip-"
    const val MIN_FREE_BYTES = 1_000L * 1024L * 1024L
    private val AUTO_VIDEO_NAME = Regex("carspeed-\\d{8}-\\d{6}")
    private val AUTO_TRIP_NAME = Regex("carspeed-trip-\\d{8}-\\d{6}")

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
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startTimeMs))
        return "$TRIP_PREFIX$stamp"
    }

    fun outputFile(
        context: Context,
        startTimeMs: Long = System.currentTimeMillis(),
        tripFolderName: String? = null
    ): File {
        val dir = tripFolderName?.let { tripDir(context, it) } ?: videoDir(context)
        return File(dir, fileNameFor(startTimeMs))
    }

    fun videoDir(context: Context): File {
        val baseDir = publicMediaDir(Environment.DIRECTORY_MOVIES) ?: context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(baseDir, DIR_NAME).apply { mkdirs() }
    }

    fun tripDir(context: Context, tripFolderName: String): File {
        return File(videoDir(context), sanitizeFolderName(tripFolderName)).apply { mkdirs() }
    }

    fun videoRelativePath(tripFolderName: String?): String {
        return listOfNotNull(Environment.DIRECTORY_MOVIES, DIR_NAME, tripFolderName?.let { sanitizeFolderName(it) })
            .joinToString("/") + "/"
    }

    fun photoOutputFile(context: Context, timeMs: Long = System.currentTimeMillis()): File {
        return File(photoDir(context), photoFileNameFor(timeMs))
    }

    fun tempPhotoFile(context: Context, timeMs: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        return File(dir, photoFileNameFor(timeMs))
    }

    fun publishPhotoToGallery(context: Context, sourceFile: File, fileName: String = sourceFile.name): File {
        val targetFile = File(photoDir(context), fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, photoRelativePath())
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

    fun photoRelativePath(): String {
        return "${Environment.DIRECTORY_PICTURES}/$DIR_NAME/"
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
    }

    fun photoFiles(context: Context): List<File> {
        return photoDirs(context).flatMap { dir ->
            dir.listFiles()
            ?.filter { isAppPhotoFile(it) }
            .orEmpty()
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
        return File(videoFile.parentFile, videoFile.nameWithoutExtension + ".srt")
    }

    fun trackFileFor(videoFile: File): File {
        return legacyCompanionFileFor(videoFile, ".track.json")
    }

    fun trackFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".track.json")
    }

    fun trackFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".track.json")
    }

    fun plateFileFor(videoFile: File): File {
        return legacyCompanionFileFor(videoFile, ".plate.json")
    }

    fun plateFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".plate.json")
    }

    fun plateFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".plate.json")
    }

    fun favoriteFileFor(videoFile: File): File {
        return legacyCompanionFileFor(videoFile, ".favorite")
    }

    fun favoriteFileFor(context: Context, videoFile: File): File {
        return privateCompanionFileFor(context, videoFile, ".favorite")
    }

    fun favoriteFilesFor(context: Context, videoFile: File): List<File> {
        return companionCandidates(context, videoFile, ".favorite")
    }

    fun videoFiles(context: Context): List<File> {
        return videoDirs(context).flatMap { videoFiles(it) }.distinctBy { it.absolutePath }
    }

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
                val isTripDir = parent.name.startsWith(TRIP_PREFIX)
                VideoTrip(
                    id = key,
                    title = if (isTripDir) parent.name else first.nameWithoutExtension,
                    directory = if (isTripDir) parent else first.parentFile ?: videoDir(context),
                    segments = sorted
                )
            }
    }

    fun companionFilesFor(videoFile: File): List<File> {
        if (!isAppVideoFile(videoFile)) return emptyList()
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
            .filter { it.exists() && it.isFile }
    }

    fun companionFilesFor(trip: VideoTrip): List<File> {
        return trip.segments.flatMap { companionFilesFor(it) }.distinctBy { it.absolutePath }
    }

    fun companionFilesFor(context: Context, trip: VideoTrip): List<File> {
        return trip.segments.flatMap { companionFilesFor(context, it) }.distinctBy { it.absolutePath }
    }

    fun isFavorite(videoFile: File): Boolean {
        return favoriteFileFor(videoFile).exists()
    }

    fun isFavorite(context: Context, videoFile: File): Boolean {
        return favoriteFilesFor(context, videoFile).any { it.exists() }
    }

    fun isFavorite(trip: VideoTrip): Boolean {
        return trip.segments.any { isFavorite(it) } || favoriteFileForTrip(trip).exists()
    }

    fun isFavorite(context: Context, trip: VideoTrip): Boolean {
        return trip.segments.any { isFavorite(context, it) } || favoriteFilesForTrip(context, trip).any { it.exists() }
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
            plateFileFor(videoFile) to plateFileFor(targetVideo),
            favoriteFileFor(videoFile) to favoriteFileFor(targetVideo)
        ).forEach { (from, to) ->
            if (from.exists() && !to.exists()) {
                val modified = from.lastModified()
                if (from.renameTo(to)) to.setLastModified(modified)
            }
        }
        return targetVideo
    }

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

    fun deleteVideoGroup(videoFile: File): Int {
        return companionFilesFor(videoFile).count { file -> file.delete() || !file.exists() }
    }

    fun deleteVideoGroup(context: Context, videoFile: File): Int {
        return companionFilesFor(context, videoFile).count { deleteIfExists(it) }
    }

    fun deleteVideoTrip(trip: VideoTrip): Int {
        val filesDeleted = companionFilesFor(trip).count { file -> file.delete() || !file.exists() }
        val favoriteDeleted = favoriteFileForTrip(trip).takeIf { it.exists() }?.let { if (it.delete() || !it.exists()) 1 else 0 } ?: 0
        if (trip.directory.name.startsWith(TRIP_PREFIX)) {
            trip.directory.delete()
        }
        return filesDeleted + favoriteDeleted
    }

    fun deleteVideoTrip(context: Context, trip: VideoTrip): Int {
        val filesDeleted = companionFilesFor(context, trip).count { deleteIfExists(it) }
        val favoriteDeleted = favoriteFilesForTrip(context, trip)
            .filter { it.exists() }
            .count { deleteIfExists(it) }
        if (trip.directory.name.startsWith(TRIP_PREFIX)) {
            trip.directory.delete()
            privateMirrorDirFor(context, trip.directory).delete()
        }
        return filesDeleted + favoriteDeleted
    }

    fun deleteVideoGroups(videoFiles: List<File>): Int {
        return videoFiles.distinctBy { it.absolutePath }.sumOf { deleteVideoGroup(it) }
    }

    fun deleteVideoGroups(context: Context, videoFiles: List<File>): Int {
        return videoFiles.distinctBy { it.absolutePath }.sumOf { deleteVideoGroup(context, it) }
    }

    fun deleteAllVideos(context: Context): Int {
        return videoTrips(context).sumOf { deleteVideoTrip(context, it) }
    }

    fun videoFiles(dir: File): List<File> {
        return dir.walkTopDown()
            .filter { isAppVideoFile(it) && !isShareExportFile(it) }
            .toList()
    }

    private fun videoDirs(context: Context): List<File> {
        return listOfNotNull(
            videoDir(context),
            legacyAppMediaDir(context, Environment.DIRECTORY_MOVIES)
        ).distinctBy { it.absolutePath }.onEach { it.mkdirs() }
    }

    private fun photoDirs(context: Context): List<File> {
        return listOfNotNull(
            photoDir(context),
            legacyAppMediaDir(context, Environment.DIRECTORY_PICTURES)
        ).distinctBy { it.absolutePath }.onEach { it.mkdirs() }
    }

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
        return if (parent != null && parent.name.startsWith(TRIP_PREFIX)) {
            parent.absolutePath
        } else {
            video.absolutePath
        }
    }

    private fun favoriteFileForTrip(trip: VideoTrip): File {
        return File(trip.directory, ".favorite")
    }

    private fun favoriteFileForTrip(context: Context, trip: VideoTrip): File {
        return File(privateMirrorDirFor(context, trip.directory), ".favorite")
    }

    private fun favoriteFilesForTrip(context: Context, trip: VideoTrip): List<File> {
        return listOf(favoriteFileForTrip(context, trip), favoriteFileForTrip(trip))
            .distinctBy { it.absolutePath }
    }

    private fun cleanupGroupKey(file: File): String {
        val parent = file.parentFile
        return if (parent != null && AUTO_TRIP_NAME.matches(parent.name)) {
            parent.absolutePath
        } else {
            File(file.parentFile, companionBaseName(file)).absolutePath
        }
    }

    private fun isTripProtected(file: File): Boolean {
        val parent = file.parentFile ?: return false
        return File(parent, ".favorite").exists()
    }

    fun isAutoGeneratedVideoFile(file: File): Boolean {
        return isAppVideoFile(file) && AUTO_VIDEO_NAME.matches(file.nameWithoutExtension)
    }

    fun cleanupLoopStorage(context: Context, limitGb: Int, requiredFreeBytes: Long = 0L): Boolean {
        val dir = videoDir(context)
        val limitBytes = limitGb.coerceIn(
            DashcamSettings.MIN_LOOP_STORAGE_GB,
            DashcamSettings.MAX_LOOP_STORAGE_GB
        ) * 1024L * 1024L * 1024L
        val plan = planCleanup(
            files = managedVideoFiles(context, dir),
            limitBytes = limitBytes,
            usableSpaceBytes = dir.usableSpace,
            requiredFreeBytes = requiredFreeBytes
        )
        plan.filesToDelete.filter { isAppVideoFile(it) }.forEach { deleteVideoGroup(context, it) }
        plan.filesToDelete.filterNot { isAppVideoFile(it) }.forEach { deleteIfExists(it) }

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
                        file.extension.equals("mp4", ignoreCase = true) ||
                        file.extension.equals("srt", ignoreCase = true) ||
                        file.name.endsWith(".track.json", ignoreCase = true) ||
                        file.name.endsWith(".plate.json", ignoreCase = true) ||
                        file.name.endsWith(".favorite", ignoreCase = true)
                        )
            }
            .toList()
    }

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
        return listOf(privateCompanionFileFor(context, videoFile, suffix), legacyCompanionFileFor(videoFile, suffix))
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
        return dir.apply { mkdirs() }
    }

    private fun relativePathForKnownVideoRoot(context: Context, sourceDir: File): String? {
        val roots = listOfNotNull(
            publicMediaDir(Environment.DIRECTORY_MOVIES)?.let { File(it, DIR_NAME) },
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

    private fun isAppVideoFile(file: File): Boolean {
        return file.isFile && file.extension.equals("mp4", ignoreCase = true)
    }

    private fun isShareExportFile(file: File): Boolean {
        return file.parentFile?.name == SHARE_DIR_NAME
    }

    private fun isAppPhotoFile(file: File): Boolean {
        return file.isFile && file.extension.equals("jpg", ignoreCase = true)
    }

    private fun isAutoGeneratedCompanionFile(file: File): Boolean {
        val baseName = companionBaseName(file)
        val favorite = File(file.parentFile, "$baseName.favorite")
        return AUTO_VIDEO_NAME.matches(baseName) && !favorite.exists() && !isTripProtected(file)
    }

    private fun isAutoGeneratedCompanionFile(context: Context, file: File): Boolean {
        val baseName = companionBaseName(file)
        val videoFile = File(file.parentFile, "$baseName.mp4")
        return AUTO_VIDEO_NAME.matches(baseName) &&
            !favoriteFilesFor(context, videoFile).any { it.exists() } &&
            !isTripProtected(context, file)
    }

    private fun isTripProtected(context: Context, file: File): Boolean {
        val parent = file.parentFile ?: return false
        return File(parent, ".favorite").exists() || File(privateMirrorDirFor(context, parent), ".favorite").exists()
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
