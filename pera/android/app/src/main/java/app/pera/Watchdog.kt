package app.pera

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.pera.client.SyncService
import app.pera.host.HostService

/**
 * Mantiene vivos los servicios de Pera sin que haya que abrir la app.
 *
 * Android deja arrancar un servicio en primer plano desde segundo plano si el usuario ha quitado la
 * optimización de batería a la app (excepción documentada en developer.android.com). Con eso:
 *  - un trabajo periódico del sistema (cada 15 min, el mínimo) comprueba que sigan en marcha;
 *  - cualquier arranque del proceso (botón del panel, copia de Teclado, reinicio…) también lo comprueba.
 * Lo único que no se puede saltar es «Forzar detención»: Android cancela todo hasta que se vuelve a usar.
 */
object Watchdog {
    private const val JOB_ID = 42

    /** Arranca lo que esté activado y no esté funcionando. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        runCatching {
            when {
                Prefs.hostEnabled(app) -> if (!HostService.running) HostService.start(app)
                Prefs.syncEnabled(app) -> if (!SyncService.running) SyncService.start(app)
            }
        }.onFailure { Log.w("PeraWatchdog", "Android no dejó arrancar el servicio ahora", it) }
        schedule(app)
    }

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!Prefs.hostEnabled(context) && !Prefs.syncEnabled(context)) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, Job::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true) // sobrevive a reinicios
                .build(),
        )
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters?): Boolean {
            ensure(this)
            return false
        }

        override fun onStopJob(params: JobParameters?) = true
    }
}
