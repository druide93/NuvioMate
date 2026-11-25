package com.nuvio.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import com.nuvio.tv.core.image.CollectionCoverNetworkClient
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import com.nuvio.tv.core.image.PlaybackImageCache
import com.nuvio.tv.core.image.HardwareBitmapSettingInterceptor
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.network.imageConnectivityChecker
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerUserStateProjection
import com.nuvio.tv.data.mediaserver.plex.PlexArtworkInterceptor
import com.nuvio.tv.data.mediaserver.plex.PlexArtworkSigner
import com.nuvio.tv.data.mediaserver.silo.SiloArtworkInterceptor
import com.nuvio.tv.data.mediaserver.silo.SiloArtworkRefresher
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var imagePerformancePreferences: ImagePerformancePreferences
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var serverUserStateProjection: ServerUserStateProjection
    @Inject lateinit var homeAssistantBridge: com.nuvio.tv.core.homeassistant.HaMqttBridge
    @Inject lateinit var homeAssistantAmbiance: com.nuvio.tv.core.homeassistant.AmbianceEngine
    @Inject lateinit var scoutWallpaperMaker: com.nuvio.tv.launcher.atv.ScoutWallpaperMaker
    @Inject lateinit var onDemandAdultGuard: com.nuvio.tv.livetv.ondemand.OnDemandAdultGuard
    @Inject lateinit var plexArtworkSigner: PlexArtworkSigner
    @Inject lateinit var siloArtworkRefresher: SiloArtworkRefresher

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        // The ATV Launcher wallpaper plugin's own small process: it only hands out pictures that
        // the app made, so none of the app starts there (no Hilt, no sync, no Home Assistant).
        if (com.nuvio.tv.launcher.atv.AtvWall.isWallProcess(this)) return
        // Before super.onCreate(): Hilt creates injected services there, and the budget is read once.
        MemoryBudget.init(this)
        com.nuvio.tv.core.player.PlaybackDeviceSupport.init(this)
        super.onCreate()
        // Live TV's own words in the device's language already for what runs before the app is opened
        // (recordings, reminders, launcher rows); MainActivity reads them again in the app's language.
        com.nuvio.tv.livetv.ui.LiveTvTexts.init(this)
        registerActivityLifecycleCallbacks(com.nuvio.tv.core.usenet.UsenetAppLifecycle())
        SentryInitializer.start(this, sentrySettingsDataStore)
        PluginRuntimeHooks.onApplicationCreate(this)
        // Live TV fork: VPN progress lines go into the start-up report.
        com.nuvio.vpn.android.VpnPlaybackProtection.diagnostics =
            java.util.function.Consumer { line -> com.nuvio.tv.livetv.model.LiveTvLoadReport.add(line) }
        // ...and whether it's on and which profile (at start and whenever it changes).
        com.nuvio.tv.livetv.data.VpnReportLines.start()
        // ...and whether video is playing (the VPN only checks for a stall while something plays).
        com.nuvio.tv.livetv.data.VpnPlaybackSignal.start()
        // The DNS setting (Live TV settings), before any Live TV or player request.
        com.nuvio.tv.livetv.data.ForkDns.init(this)
        androidTvChannelSyncService.start()
        serverUserStateProjection.start()
        homeAssistantBridge.start()
        homeAssistantAmbiance.start()
        // Live TV fork: adult On Demand titles never stay in Continue watching.
        onDemandAdultGuard.start()
        // Scout suggestions as ATV Launcher wallpapers, made only once its plugin has been used.
        scoutWallpaperMaker.start()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        // Same name the app's other requests use (see NetworkModule).
        val imageUserAgent = "Nuvio/" + BuildConfig.NUVIO_BASE_VERSION.ifBlank { BuildConfig.VERSION_NAME }.ifBlank { "dev" }
        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(PlexArtworkInterceptor { if (::plexArtworkSigner.isInitialized) plexArtworkSigner else null })
                .addNetworkInterceptor(PlexArtworkInterceptor.Token { if (::plexArtworkSigner.isInitialized) plexArtworkSigner else null })
                .addInterceptor(SiloArtworkInterceptor({ if (::siloArtworkRefresher.isInitialized) siloArtworkRefresher else null }))
                // Pictures are asked for in the app's name, like its other requests. OkHttp's own
                // "okhttp/4.12.0" is turned away by some sites: Wikipedia's (where many playlists
                // and guides take channel logos) answers 403 to it and asks for an app's name.
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("User-Agent") != null) chain.proceed(request)
                    else chain.proceed(request.newBuilder().header("User-Agent", imageUserAgent).build())
                }
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                // Scout Mix tiles: their picture is drawn from the mix's shows.
                add(com.nuvio.tv.core.image.ScoutMixArt.Factory())
                add(HardwareBitmapSettingInterceptor { imagePerformancePreferences.hardwareBitmapsEnabled })
                add(CustomPosterFallbackInterceptor())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    NetworkFetcher.Factory(
                        networkClient = { CollectionCoverNetworkClient(imageOkHttpClient.asNetworkClient()) },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                            )
                        },
                        connectivityChecker = ::imageConnectivityChecker,
                    )
                )
            }
            .memoryCache {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.15 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.20 for decent image caching.
                // Normal devices (>3GB): use 0.25 for snappy image loading.
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                // maxSizePercent takes the share of largeMemoryClass under largeHeap (512 MB on
                // 1 GB TVs, ~77 MB of bitmaps), so when MemoryBudget sizes from a smaller heap on
                // such a TV the cache takes its share from that heap too.
                val budgetHeapBytes = MemoryBudget.heapMb * 1024L * 1024L
                val builder = MemoryCache.Builder()
                if (budgetHeapBytes < Runtime.getRuntime().maxMemory()) {
                    builder.maxSizeBytes((budgetHeapBytes * cachePercent).toLong())
                } else {
                    builder.maxSizePercent(context, cachePercent)
                }
                builder.build().also(PlaybackImageCache::attach)
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(false)
            .allowRgb565(imagePerformancePreferences.rgb565Enabled)
            .bitmapFactoryMaxParallelism(4)
            .build()
    }
}
