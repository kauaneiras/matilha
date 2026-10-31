package com.matilha.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.matilha.app.MainActivity
import com.matilha.app.R
import com.matilha.app.data.Repository
import com.matilha.app.data.StateStore
import com.matilha.app.domain.AppNotification
import com.matilha.app.domain.CheckIn
import java.io.File
import java.util.concurrent.Executors

class MatilhaApp : Application() {
    val repo: Repository by lazy { Repository(StateStore(File(filesDir, "matilha-state.bin"))) }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        PulseJob.schedule(this)
    }

    companion object {
        fun repo(ctx: Context) = (ctx.applicationContext as MatilhaApp).repo
        /** Executor único para trabalho fora da thread principal (ordem garantida). */
        val io = Executors.newSingleThreadExecutor()
    }
}

object Perms {
    fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun location(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun fineLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun background(ctx: Context) = has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || has(ctx, "android.permission.POST_NOTIFICATIONS")
}

object Notifier {
    const val CH_FRIENDS = "amigos"
    const val CH_RADAR = "radar"
    const val CH_SAFETY = "seguranca"
    const val CH_WALK = "passeio"
    const val CH_EVENTS = "roles"
    const val EXTRA_PLACE = "placeId"
    const val EXTRA_EVENT = "eventId"
    const val EXTRA_WALK_FEEDBACK = "walkFeedbackPlace"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CH_FRIENDS, "Melhores amigos", NotificationManager.IMPORTANCE_HIGH).apply { description = "Check-ins e agendas dos amigos da Matilha" },
            NotificationChannel(CH_RADAR, "Radar de compatibilidade", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Cães compatíveis por perto" },
            NotificationChannel(CH_SAFETY, "Alertas de segurança", NotificationManager.IMPORTANCE_HIGH).apply { description = "Perigos reportados perto de você" },
            NotificationChannel(CH_WALK, "Passeio", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Check-out automático e avaliação do passeio" },
            NotificationChannel(CH_EVENTS, "Rolês e Matilha", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Pedidos, aprovações e convites" },
        ).forEach { nm.createNotificationChannel(it) }
    }

    private fun channelFor(n: AppNotification) = when {
        n.title.startsWith("Radar") -> CH_RADAR
        n.title.startsWith("Alerta") -> CH_SAFETY
        n.eventId != null || n.title.contains("Matilha") && !n.title.startsWith("Agenda") -> CH_EVENTS
        else -> CH_FRIENDS
    }

    fun post(ctx: Context, n: AppNotification) {
        if (!Perms.notifications(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            n.placeId?.let { putExtra(EXTRA_PLACE, it) }
            n.eventId?.let { putExtra(EXTRA_EVENT, it) }
        }
        val pi = PendingIntent.getActivity(ctx, n.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(ctx, channelFor(n))
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(Notification.BigTextStyle().bigText(n.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(n.id.hashCode(), notif) } catch (_: SecurityException) {}
    }

    /** "Como foi o passeio? Tudo limpo e seguro na praça hoje?" */
    fun postWalkFeedback(ctx: Context, c: CheckIn, placeName: String) {
        if (!Perms.notifications(ctx)) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WALK_FEEDBACK, c.placeId)
        }
        val pi = PendingIntent.getActivity(ctx, c.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val okIntent = Intent(ctx, WalkFeedbackReceiver::class.java).putExtra(EXTRA_PLACE, c.placeId)
        val okPi = PendingIntent.getBroadcast(ctx, c.id.hashCode() + 1, okIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(ctx, CH_WALK)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle("Como foi o passeio?")
            .setContentText("Check-out automático em $placeName. Tudo limpo e seguro por lá hoje?")
            .setStyle(Notification.BigTextStyle().bigText("Você se afastou de $placeName, então encerramos seu check-in. Tudo limpo e seguro por lá hoje?"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "Tudo certo", okPi).build())
            .addAction(Notification.Action.Builder(null, "Reportar problema", pi).build())
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(WALK_ID, notif) } catch (_: SecurityException) {}
    }

    fun cancelWalk(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.cancel(WALK_ID)
    private const val WALK_ID = 4242
}

class WalkFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val place = intent.getStringExtra(Notifier.EXTRA_PLACE) ?: return
        val pr = goAsync()
        MatilhaApp.io.execute {
            try { MatilhaApp.repo(ctx).submitWalkFeedback(place, true, "") ; Notifier.cancelWalk(ctx) } finally { pr.finish() }
        }
    }
}

/** Processa o resultado de um pulso: notificações do sistema e pergunta pós-passeio. */
object PulseHandler {
    fun deliver(ctx: Context, r: Repository.PulseResult) {
        r.notifications.forEach { Notifier.post(ctx, it) }
        r.autoClosed?.let { onAutoCheckout(ctx, it) }
    }

    fun onAutoCheckout(ctx: Context, c: CheckIn) {
        Geofence.clear(ctx)
        val name = MatilhaApp.repo(ctx).state.place(c.placeId)?.name ?: "local"
        Notifier.postWalkFeedback(ctx, c, name)
    }
}

/**
 * Cerca virtual do check-in ativo, via LocationManager.addProximityAlert (API do sistema, baixo consumo:
 * usa a localização que o sistema já obtém). Raio = raio do local + 150 m. Na saída, agenda uma
 * confirmação em 5 min; se ainda estiver fora, o check-in é encerrado.
 */
object Geofence {
    private const val ACTION = "com.matilha.app.GEOFENCE"
    private const val REQ = 7001
    private const val REQ_CONFIRM = 7002

    private fun pi(ctx: Context, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(ctx, REQ, Intent(ctx, GeofenceReceiver::class.java).setAction(ACTION), flags or PendingIntent.FLAG_MUTABLE)

    @SuppressLint("MissingPermission")
    fun register(ctx: Context, lat: Double, lon: Double, radiusM: Double) {
        if (!Perms.fineLocation(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        clear(ctx)
        try { lm.addProximityAlert(lat, lon, (radiusM + Repository.AUTO_CHECKOUT_MARGIN_M).toFloat(), -1, pi(ctx)!!) } catch (_: Exception) {}
    }

    fun clear(ctx: Context) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        pi(ctx, PendingIntent.FLAG_NO_CREATE)?.let { p -> try { lm?.removeProximityAlert(p) } catch (_: Exception) {}; p.cancel() }
        cancelConfirm(ctx)
    }

    /** Re-registra a cerca para o check-in ativo (após boot ou ao abrir o app). */
    fun sync(ctx: Context) {
        val repo = MatilhaApp.repo(ctx)
        val c = repo.state.myActiveCheckIn(repo.now())
        val place = c?.let { repo.state.place(it.placeId) }
        if (place == null || !repo.state.alertSettings.autoCheckout) clear(ctx) else register(ctx, place.lat, place.lon, place.radiusM)
    }

    fun scheduleConfirm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = PendingIntent.getBroadcast(ctx, REQ_CONFIRM, Intent(ctx, GeofenceConfirmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Repository.AUTO_CHECKOUT_DELAY_MS + 5_000, p)
    }

    fun cancelConfirm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        PendingIntent.getBroadcast(ctx, REQ_CONFIRM, Intent(ctx, GeofenceConfirmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val pr = goAsync()
        MatilhaApp.io.execute {
            try {
                MatilhaApp.repo(ctx).onGeofenceTransition(entering)
                if (entering) Geofence.cancelConfirm(ctx) else Geofence.scheduleConfirm(ctx)
            } finally { pr.finish() }
        }
    }
}

class GeofenceConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pr = goAsync()
        MatilhaApp.io.execute {
            try { MatilhaApp.repo(ctx).confirmGeofenceExit()?.let { PulseHandler.onAutoCheckout(ctx, it) } } finally { pr.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        PulseJob.schedule(ctx)
        val pr = goAsync()
        MatilhaApp.io.execute { try { Geofence.sync(ctx) } finally { pr.finish() } }
    }
}

/** Pulso periódico em segundo plano (~15 min, mínimo do Android). */
class PulseJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MatilhaApp.io.execute {
            try {
                val repo = MatilhaApp.repo(this)
                if (repo.state.onboarded) {
                    LocationTracker.lastKnown(this)?.let { loc -> repo.onLocation(loc.latitude, loc.longitude)?.let { PulseHandler.onAutoCheckout(this, it) } }
                    PulseHandler.deliver(this, repo.pulse())
                }
            } catch (_: Exception) {
            } finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 1001
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            val info = JobInfo.Builder(ID, ComponentName(ctx, PulseJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            try { js.schedule(info) } catch (_: Exception) {}
        }
    }
}

/** Localização via LocationManager (sem Play Services). */
object LocationTracker {
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!Perms.location(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 30 * 60 * 1000L }
            .maxByOrNull { it.time }
    }

    /** Atualizações enquanto o app está em primeiro plano (a cada ~30 s / 20 m). */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, onLoc: (Location) -> Unit) {
        stop(ctx)
        if (!Perms.location(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLoc(location)
            @Deprecated("API antiga") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 30_000L, 20f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        lastKnown(ctx)?.let { loc -> Handler(Looper.getMainLooper()).post { onLoc(loc) } }
    }

    fun stop(ctx: Context) {
        val l = listener ?: return
        try { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (_: Exception) {}
        listener = null
    }

    fun providersEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }
}
