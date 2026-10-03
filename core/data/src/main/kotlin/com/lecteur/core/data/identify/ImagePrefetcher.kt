package com.lecteur.core.data.identify

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Warms the image cache so the library still shows its artwork without a network. Fire and forget: a failed download only
 * means that image is fetched later, when it is first displayed.
 */
interface ImagePrefetcher {
    fun prefetch(urls: List<String>)
}

class CoilImagePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context
) : ImagePrefetcher {

    override fun prefetch(urls: List<String>) {
        if (urls.isEmpty()) return
        val loader = Coil.imageLoader(context)
        urls.distinct().forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
            )
        }
    }
}

/**
 * The single image loader of the app (display and prefetch share its disk cache). TMDB artwork never changes under
 * the same path, so cache headers are ignored: a cached image is served as is, offline or not. The cache sits in
 * no-backup storage, which the system does not clear under pressure the way it clears `cacheDir`.
 */
object LecteurImageLoader {
    const val DISK_CACHE_BYTES = 250L * 1024 * 1024

    fun create(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.noBackupFilesDir.resolve("image_cache"))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
