package com.nuvio.tv.launcher.atv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nuvio.tv.core.util.hasPendingJob
import com.nuvio.tv.core.util.scheduleSafely
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Makes the Scout wallpapers when the plugin asks for them while the app is not running (first
 * use, or a set over a day old). Runs in the app's own process, where Scout and TMDB are; the
 * plugin's process only schedules it ([ask]), at most every few hours.
 */
class ScoutWallpaperJob : JobService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MakerEntryPoint {
        fun maker(): ScoutWallpaperMaker
    }

    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val maker = EntryPointAccessors.fromApplication(applicationContext, MakerEntryPoint::class.java).maker()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            it.launch {
                try {
                    maker.makeNow()
                } catch (e: Exception) {
                    Log.w(TAG, "job: not made", e)
                } finally {
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        return true
    }

    companion object {
        private const val TAG = "AtvWall"
        private const val JOB_ID = 10_040
        private const val ASK_EVERY_MS = 3 * 60 * 60_000L

        /** From either process: asks the app for a set, once the network is up, at most every few hours. */
        fun ask(context: Context) {
            val app = context.applicationContext
            if (System.currentTimeMillis() - AtvWall.lastAskedAt(app) < ASK_EVERY_MS) return
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (scheduler.hasPendingJob(JOB_ID)) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(app, ScoutWallpaperJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            if (scheduler.scheduleSafely(job)) {
                runCatching { AtvWall.markAsked(app) }
                Log.i(TAG, "asked the app for a set")
            }
        }
    }
}
