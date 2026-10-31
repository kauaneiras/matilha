package com.matilha.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Intent
import android.location.LocationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.matilha.app.domain.*
import com.matilha.app.platform.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformTest {
    private lateinit var app: Application
    private val repo get() = MatilhaApp.repo(app)

    /** Espera o executor de segundo plano terminar o que foi enfileirado. */
    private fun flushIo() { MatilhaApp.io.submit {}.get(); UiTestKit.idle() }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("android.permission.POST_NOTIFICATIONS", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val now = repo.now()
        repo.savePet(Pet(repo.newPetId(), "me", "Lola", "SRD", AgeGroup.ADULT, 8.0, Sex.FEMALE, true,
            vaccines = Vaccine.entries.map { VaccineRecord(it, now - 10 * DAY) }))
        repo.completeOnboarding("Mariana", "Asa Sul")
    }

    @Test fun channelsAndNotificationPosting() {
        val nm = app.getSystemService(NotificationManager::class.java)
        assertEquals(setOf("amigos", "radar", "seguranca", "passeio", "roles"), nm.notificationChannels.map { it.id }.toSet())
        Notifier.post(app, AppNotification("n1", "Radar: Corrida", "2 cães compatíveis", repo.now(), placeId = "pl-parcao"))
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertEquals("radar", posted[0].channelId)
        // Toque abre o local certo.
        val intent = shadowOf(posted[0].contentIntent).savedIntent
        assertEquals("pl-parcao", intent.getStringExtra(Notifier.EXTRA_PLACE))
    }

    @Test fun noNotificationWithoutPermission() {
        shadowOf(app).denyPermissions("android.permission.POST_NOTIFICATIONS")
        Notifier.post(app, AppNotification("n1", "x", "y", repo.now()))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun geofenceExitConfirmationAutoCheckoutAndWalkQuestion() {
        val pet = repo.state.myPets.single()
        repo.checkIn("pl-parcao", listOf(pet.id))
        val place = repo.state.place("pl-parcao")!!
        Geofence.register(app, place.lat, place.lon, place.radiusM)

        // Saída da cerca: marca o horário e agenda a confirmação em ~5 min.
        GeofenceReceiver().onReceive(app, Intent().putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false))
        flushIo()
        assertNotNull(repo.state.outsideSince)
        val alarm = shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarm.size)
        assertTrue(alarm[0].triggerAtTime - System.currentTimeMillis() in (5 * MINUTE)..(6 * MINUTE))

        // Voltou: cancela a confirmação.
        GeofenceReceiver().onReceive(app, Intent().putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true))
        flushIo()
        assertNull(repo.state.outsideSince)

        // Saiu de novo e ficou fora mais de 5 min: a confirmação encerra o check-in.
        repo.update { it.copy(outsideSince = repo.now() - 6 * MINUTE) }
        GeofenceConfirmReceiver().onReceive(app, Intent())
        flushIo()
        val closed = repo.state.checkIns.first { it.userId == "me" }
        assertTrue(closed.autoClosed)
        assertNull(repo.state.myActiveCheckIn(repo.now()))
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(n.any { it.extras.getString("android.title") == "Como foi o passeio?" && it.actions?.size == 2 })

        // Ação "Tudo certo" registra o feedback sem abrir o app.
        WalkFeedbackReceiver().onReceive(app, Intent().putExtra(Notifier.EXTRA_PLACE, "pl-parcao"))
        flushIo()
        assertTrue(repo.state.walkFeedback.single().allGood)
    }

    @Test fun confirmationIgnoredWhenNoActiveCheckIn() {
        repo.update { it.copy(outsideSince = repo.now() - 10 * MINUTE) }
        GeofenceConfirmReceiver().onReceive(app, Intent())
        flushIo()
        assertTrue(repo.state.checkIns.none { it.userId == "me" })
    }

    @Test fun backgroundJobAndBoot() {
        val js = app.getSystemService(JobScheduler::class.java)
        js.cancelAll()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        flushIo()
        val job = js.allPendingJobs.single()
        assertTrue(job.isPeriodic); assertTrue(job.isPersisted)
        // Ação desconhecida não faz nada.
        js.cancelAll()
        BootReceiver().onReceive(app, Intent("x"))
        assertTrue(js.allPendingJobs.isEmpty())
    }

    @Test fun pulseDeliversSystemNotifications() {
        val parcao = repo.state.place("pl-parcao")!!
        repo.onLocation(parcao.lat, parcao.lon)
        repo.update { s -> s.copy(checkIns = s.checkIns + CheckIn("bf", "sim-ana", listOf("pet-paco"), parcao.id, repo.now())) }
        PulseHandler.deliver(app, repo.pulse())
        val titles = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.map { it.extras.getString("android.title") }
        assertTrue(titles.toString(), titles.any { it == "Paco chegou!" })
        assertNotNull(InstrumentationRegistry.getInstrumentation())
    }
}
