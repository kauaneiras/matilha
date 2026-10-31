package com.matilha.app

import com.matilha.app.data.*
import com.matilha.app.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Calendar

class RepositoryTest {
    private var t = 0L
    private lateinit var repo: Repository

    /** Sábado, 14h (horário local), para dar previsibilidade aos testes. */
    private fun baseTime(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 31, 14, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun myPet(name: String = "Lola", kg: Double = 8.0, sex: Sex = Sex.FEMALE, neutered: Boolean = true,
                      triggers: Set<Trigger> = emptySet(), fears: Set<SizeClass> = emptySet()): Pet {
        val p = Pet(repo.newPetId(), "me", name, "SRD", AgeGroup.ADULT, kg, sex, neutered,
            vaccines = Vaccine.entries.map { VaccineRecord(it, t - 30 * DAY) }, ectoparasiteControlAt = t - 5 * DAY,
            proofUri = "content://x", energy = 3, triggers = triggers, fearsSizes = fears)
        return (repo.savePet(p) as Result.Ok).value
    }

    @Before fun setUp() {
        t = baseTime()
        repo = Repository(null, clock = { t })
    }

    private fun onboard(): Pet {
        val p = myPet()
        assertTrue(repo.completeOnboarding("Mariana", "Asa Sul") is Result.Ok)
        return p
    }

    @Test fun onboardingRequiresNameAndPet() {
        assertTrue(repo.completeOnboarding("Mariana", "") is Result.Err)
        myPet()
        assertTrue(repo.completeOnboarding("", "") is Result.Err)
        assertTrue(repo.completeOnboarding("Mariana", "Asa Sul") is Result.Ok)
        assertTrue(repo.state.onboarded)
        assertEquals("Mariana", repo.state.me.name)
    }

    @Test fun petValidationAndProofSeal() {
        val bad = Pet("x", "me", " ", "", AgeGroup.ADULT, 10.0, Sex.MALE, true)
        assertTrue(repo.savePet(bad) is Result.Err)
        assertTrue(repo.savePet(bad.copy(name = "Rex", weightKg = Double.NaN)) is Result.Err)
        assertTrue(repo.savePet(bad.copy(name = "Rex", weightKg = 0.0)) is Result.Err)
        val sel = bad.copy(name = "Rex", weightKg = 10.0, triggers = setOf(Trigger.SELECTIVE))
        assertTrue("seletivo sem detalhes", repo.savePet(sel) is Result.Err)
        val ok = repo.savePet(sel.copy(fearsSizes = setOf(SizeClass.GIANT)))
        assertTrue(ok is Result.Ok)
        assertEquals("SRD", (ok as Result.Ok).value.breed)
        val p = myPet()
        assertEquals(ProofStatus.APPROVED, p.proofStatus)
        // Macho não pode ficar "no cio"; campos seletivos limpos se gatilho desmarcado.
        val m = (repo.savePet(p.copy(sex = Sex.MALE, inHeat = true, fearsSizes = setOf(SizeClass.GIANT))) as Result.Ok).value
        assertFalse(m.inHeat); assertTrue(m.fearsSizes.isEmpty())
    }

    @Test fun cannotDeleteLastPetOrPetInCheckIn() {
        val p = onboard()
        assertTrue(repo.deletePet(p.id) is Result.Err)
        val q = myPet("Bob")
        repo.checkIn("pl-parcao", listOf(q.id))
        assertTrue(repo.deletePet(q.id) is Result.Err)
        repo.checkOut(false)
        assertTrue(repo.deletePet(q.id) is Result.Ok)
        assertTrue(repo.deletePet("pet-paco") is Result.Err) // não é meu
    }

    @Test fun checkInSwitchesAndAutoCheckoutAfter5MinutesOutside() {
        val p = onboard()
        val place = repo.state.place("pl-parcao")!!
        assertTrue(repo.checkIn(place.id, listOf(p.id)) is Result.Ok)
        assertTrue(repo.checkIn(place.id, listOf(p.id)) is Result.Err) // já está aqui
        assertTrue(repo.checkIn(place.id, emptyList()) is Result.Err)
        // Dentro do raio + 150 m: nada acontece.
        repo.onLocation(place.lat + 0.0015, place.lon) // ~165 m < 90 + 150
        t += 10 * MINUTE
        assertNull(repo.onLocation(place.lat + 0.0015, place.lon))
        assertNotNull(repo.state.myActiveCheckIn(t))
        // Sai do raio: começa a contar.
        assertNull(repo.onLocation(place.lat + 0.01, place.lon)) // ~1,1 km
        t += 4 * MINUTE
        assertNull(repo.onLocation(place.lat + 0.01, place.lon))
        assertNotNull(repo.state.myActiveCheckIn(t))
        // Voltou antes dos 5 min: zera.
        repo.onLocation(place.lat, place.lon)
        assertNull(repo.state.outsideSince)
        repo.onLocation(place.lat + 0.01, place.lon)
        t += 5 * MINUTE + 1
        val closed = repo.onLocation(place.lat + 0.01, place.lon)
        assertNotNull(closed); assertTrue(closed!!.autoClosed)
        assertNull(repo.state.myActiveCheckIn(t))
    }

    @Test fun geofenceTransitionsAndConfirm() {
        val p = onboard()
        repo.checkIn("pl-parcao", listOf(p.id))
        repo.onGeofenceTransition(entering = false)
        t += 3 * MINUTE
        assertNull(repo.confirmGeofenceExit())
        repo.onGeofenceTransition(entering = true)
        t += 10 * MINUTE
        assertNull(repo.confirmGeofenceExit())
        repo.onGeofenceTransition(entering = false)
        t += 6 * MINUTE
        assertNotNull(repo.confirmGeofenceExit())
    }

    @Test fun autoCheckoutDisabledRespectsSetting() {
        val p = onboard()
        repo.saveAlertSettings(repo.state.alertSettings.copy(autoCheckout = false))
        repo.checkIn("pl-parcao", listOf(p.id))
        repo.onLocation(-15.0, -47.0)
        t += 30 * MINUTE
        assertNull(repo.onLocation(-15.0, -47.0))
        assertNotNull(repo.state.myActiveCheckIn(t))
    }

    @Test fun staleCheckInExpires() {
        val p = onboard()
        repo.checkIn("pl-parcao", listOf(p.id))
        t += 3 * HOUR
        assertNull(repo.expireStaleCheckIns())
        t += 2 * HOUR
        assertNotNull(repo.expireStaleCheckIns())
    }

    @Test fun checkoutCreatesTrustPromptsForDogsMetLongEnough() {
        val p = onboard()
        // Coloca um cão simulado no Parcão.
        repo.update { s -> s.copy(checkIns = s.checkIns.filter { it.userId == s.meId } + CheckIn("x", "sim-bruno", listOf("pet-thor"), "pl-parcao", t)) }
        repo.checkIn("pl-parcao", listOf(p.id))
        t += 5 * MINUTE
        repo.checkOut(false)
        assertTrue("menos de 10 min juntos", repo.state.trustPrompts.isEmpty())
        repo.checkIn("pl-parcao", listOf(p.id))
        t += 15 * MINUTE
        repo.checkOut(false)
        assertEquals(listOf("pet-thor"), repo.state.trustPrompts.map { it.aboutPetId })
        val prompt = repo.state.trustPrompts.first()
        assertTrue(repo.answerTrust(prompt.id, false, "") is Result.Err) // precisa dizer o que não condiz
        assertTrue(repo.answerTrust(prompt.id, true, "") is Result.Ok)
        assertTrue(repo.state.trustPrompts.isEmpty())
    }

    @Test fun trustFlagAfterTwoTutors() {
        onboard()
        assertFalse(repo.state.pet("pet-bidu")!!.underReview)
        repo.update { s -> s.copy(trustFeedback = s.trustFeedback + TrustFeedback("z", "sim-ana", "pet-bidu", false, "mordeu", "x", t)) }
        repo.giveTrustFeedback("pet-bidu", false, "Tentou morder", "Parcão")
        assertTrue(repo.state.pet("pet-bidu")!!.underReview)
        assertTrue(repo.giveTrustFeedback(repo.state.myPets.first().id, true, "", "x") is Result.Err)
    }

    @Test fun agendaRules() {
        val p = onboard()
        val today = Repository.startOfDay(t)
        assertTrue("já passou", repo.schedule("pl-parcao", today, 9 * 60, 10 * 60, listOf(p.id)) is Result.Err)
        val ok = repo.schedule("pl-parcao", today, 17 * 60 + 30, 18 * 60 + 30, listOf(p.id))
        assertTrue(ok is Result.Ok)
        assertTrue("sobreposição", repo.schedule("pl-308s", today, 18 * 60, 19 * 60, listOf(p.id)) is Result.Err)
        val second = repo.schedule("pl-308s", today, 18 * 60 + 30, 19 * 60, listOf(p.id))
        assertTrue(second is Result.Ok)
        assertTrue(repo.schedule("pl-parcao", today, 17 * 60 + 10, 18 * 60, listOf(p.id)) is Result.Err)
        assertTrue(repo.schedule("pl-parcao", today, 20 * 60, 21 * 60, listOf("pet-paco")) is Result.Err) // pet de outro tutor
        // "Eu também vou!"
        repo.update { CommunitySimulator.step(it, t) }
        val ana = repo.state.schedules.first { it.userId == "sim-ana" }
        repo.cancelSchedule((ok as Result.Ok).value.id)
        assertTrue("ainda conflita com 18h30 na 308", repo.joinSchedule(ana, listOf(p.id)) is Result.Err)
        repo.cancelSchedule((second as Result.Ok).value.id)
        val joined = repo.joinSchedule(ana, listOf(p.id))
        assertTrue(joined is Result.Ok)
        assertEquals(ana.id, (joined as Result.Ok).value.joinedScheduleId)
        assertTrue(repo.joinSchedule((joined).value, listOf(p.id)) is Result.Err)
    }

    @Test fun privateEventFlow() {
        val p = onboard() // 8 kg, castrada, vacinas com comprovante
        val quintal = repo.state.privateEvents.first { it.id == "pv-quintal" }
        assertTrue("convite exclusivo da Matilha", repo.canSee(quintal))
        assertTrue(repo.eligibility(quintal).all { it.ok })
        assertTrue(repo.requestToJoin(quintal.id, listOf(p.id), "Oi!") is Result.Ok)
        assertTrue(repo.requestToJoin(quintal.id, listOf(p.id), "") is Result.Err) // duplicado
        assertFalse(repo.state.privateEvents.first { it.id == quintal.id }.isApproved("me"))
        assertTrue(repo.sendChat(quintal.id, "oi") is Result.Err) // chat fechado
        t += 2 * MINUTE
        repo.pulse()
        val e = repo.state.privateEvents.first { it.id == quintal.id }
        assertTrue(e.isApproved("me"))
        assertTrue(repo.sendChat(e.id, "Obrigada!") is Result.Ok)
        val notifs = repo.state.notifications.map { it.title }
        assertTrue(notifs.toString(), notifs.any { it.contains("aprovado") })

        // Pet pesado não pode pedir.
        val heavy = myPet("Thor", kg = 30.0)
        val piscina = repo.state.privateEvents.first { it.id == "pv-piscina" }
        assertTrue(repo.requestToJoin(piscina.id, listOf(heavy.id, p.id), "") is Result.Err) // Lola (8kg, Pequeno) não é Médio/Grande
        assertTrue(repo.requestToJoin(piscina.id, listOf(heavy.id), "") is Result.Ok)
    }

    @Test fun invisibleEventHiddenFromOutsiders() {
        onboard()
        val e = repo.state.privateEvents.first { it.id == "pv-quintal" }.copy(invitedUserIds = setOf("sim-bruno"))
        repo.update { s -> s.copy(privateEvents = s.privateEvents.map { if (it.id == e.id) e else it }) }
        assertFalse(repo.canSee(e))
        assertTrue(repo.requestToJoin(e.id, listOf(repo.state.myPets[0].id), "") is Result.Err)
    }

    @Test fun hostCreatesAndApproves() {
        val p = onboard()
        val start = t + 2 * DAY
        val req = EventRequirements(maxWeightKg = 10.0)
        assertTrue(repo.createPrivateEvent("Oi", "", start, 120, "Asa Sul", "Rua 1, casa 2", -15.8, -47.9, 3, req, emptySet(), PrivateVisibility.MAP_APPROX, emptySet()) is Result.Err)
        assertTrue("invisível sem convidados", repo.createPrivateEvent("Quintal da Lola", "", start, 120, "Asa Sul", "Rua 1, casa 2", -15.8, -47.9, 3, req, emptySet(), PrivateVisibility.PACK_ONLY, emptySet()) is Result.Err)
        assertTrue("convidado precisa ser da Matilha", repo.createPrivateEvent("Quintal da Lola", "", start, 120, "Asa Sul", "Rua 1, casa 2", -15.8, -47.9, 3, req, emptySet(), PrivateVisibility.PACK_ONLY, setOf("sim-zzz")) is Result.Err)
        val r = repo.createPrivateEvent("Quintal da Lola", "", start, 120, "Asa Sul", "Rua 1, casa 2", -15.8, -47.9, 3, req, setOf(HostAmenity.SHADE), PrivateVisibility.PACK_ONLY, setOf("sim-ana"))
        val e = (r as Result.Ok).value
        val off = Geo.distanceM(e.exactLat, e.exactLon, e.approxLat, e.approxLon)
        assertTrue("centro aproximado deslocado: $off", off >= 250)
        assertTrue(off < e.approxRadiusM) // endereço real dentro do círculo
        // Pedido simulado de um tutor
        repo.update { s -> s.copy(privateEvents = s.privateEvents.map { if (it.id == e.id) it.copy(requests = listOf(JoinRequest("sim-ana", listOf("pet-amora", "pet-paco"), RequestStatus.PENDING, t))) else it }) }
        val n = repo.evaluateAlerts()
        assertTrue(n.any { it.title.contains("Pedido") })
        assertTrue(repo.evaluateAlerts().none { it.title.contains("Pedido") }) // não repete
        assertTrue(repo.decideRequest(e.id, "sim-ana", true) is Result.Ok)
        assertEquals(2, repo.state.privateEvents.first { it.id == e.id }.approvedDogCount())
        assertNotNull(p)
    }

    @Test fun bestFriendCheckInAlertRespectsDistanceAndFiresOnce() {
        onboard()
        val parcao = repo.state.place("pl-parcao")!!
        repo.onLocation(parcao.lat + 0.0036, parcao.lon) // ~400 m
        repo.update { s -> s.copy(checkIns = s.checkIns + CheckIn("ci-paco", "sim-ana", listOf("pet-paco"), parcao.id, t)) }
        val n = repo.evaluateAlerts()
        val hit = n.firstOrNull { it.body.contains("Paco") && it.body.contains("check-in") }
        assertNotNull(n.toString(), hit)
        assertTrue(hit!!.body, hit.body.contains("400 m"))
        assertTrue(hit.body.contains("da sua cachorra Lola"))
        assertTrue(repo.evaluateAlerts().none { it.body.contains("check-in") })
        // Longe demais: não avisa.
        repo.onLocation(parcao.lat + 0.1, parcao.lon)
        repo.update { s -> s.copy(checkIns = s.checkIns + CheckIn("ci-paco2", "sim-ana", listOf("pet-paco"), parcao.id, t)) }
        assertTrue(repo.evaluateAlerts().none { it.body.contains("check-in") })
    }

    @Test fun radarFiresWithCooldown() {
        onboard()
        val parcao = repo.state.place("pl-parcao")!!
        t = Fmt.atTime(Repository.startOfDay(t), 18 * 60)
        repo.onLocation(parcao.lat, parcao.lon)
        repo.saveRadarRule(RadarRule("rr", "Corrida", minDogs = 2, sizes = setOf(SizeClass.MEDIUM), neuteredOnly = true, playStyles = setOf(PlayStyle.RUNNER)))
        repo.update { s -> s.copy(checkIns = s.checkIns.filterNot { it.userId != s.meId } +
            CheckIn("a", "sim-ana", listOf("pet-paco"), parcao.id, t) + CheckIn("b", "sim-diego", listOf("pet-bidu", "pet-lola"), parcao.id, t)) }
        assertTrue(repo.evaluateAlerts().any { it.title == "Radar: Corrida" })
        assertTrue(repo.evaluateAlerts().none { it.title.startsWith("Radar") })
        t += 3 * HOUR + 1
        // Fora da janela (21h): não dispara.
        assertTrue(repo.evaluateAlerts().none { it.title.startsWith("Radar") })
    }

    @Test fun hazardsReportConfirmResolve() {
        onboard()
        val h = (repo.reportHazard("pl-parcao", HazardType.BROKEN_GLASS, "perto do banco") as Result.Ok).value
        assertEquals(1, repo.state.activeHazards("pl-parcao", t).size)
        // Reportar o mesmo tipo de novo não duplica.
        repo.reportHazard("pl-parcao", HazardType.BROKEN_GLASS, "")
        assertEquals(1, repo.state.activeHazards("pl-parcao", t).size)
        repo.resolveHazard(h.id)
        assertEquals(0, repo.state.activeHazards("pl-parcao", t).size)
    }

    @Test fun addPlaceRejectsDuplicatesAndReviewReplacesVote() {
        onboard()
        val parcao = repo.state.place("pl-parcao")!!
        assertTrue(repo.addPlace("Outro", PlaceKind.LAWN, parcao.lat, parcao.lon, Fencing.OPEN, Floor.GRASS, emptySet(), emptySet()) is Result.Err)
        val r = repo.addPlace("Gramado novo", PlaceKind.LAWN, -15.9, -47.95, Fencing.OPEN, Floor.CONCRETE, setOf(Amenity.WATER), setOf(7, 8))
        val p = (r as Result.Ok).value
        repo.reviewPlace(p.id, Fencing.SIMPLE_FENCE, Floor.GRASS, emptySet(), null)
        val c = PlaceRules.consensus(repo.state.reviews.filter { it.placeId == p.id })
        assertEquals(1, c.reviewCount); assertEquals(Fencing.SIMPLE_FENCE, c.fencing)
        assertEquals(setOf(7, 8), repo.state.place(p.id)!!.shadeHours)
    }

    @Test fun friendFlow() {
        onboard()
        assertEquals(FriendStatus.PENDING_IN, repo.state.friendStatus("sim-diego"))
        assertTrue(repo.answerFriend("sim-diego", true) is Result.Ok)
        assertEquals(FriendStatus.ACCEPTED, repo.state.friendStatus("sim-diego"))
        assertTrue(repo.requestFriend("sim-leo") is Result.Ok)
        assertTrue(repo.requestFriend("sim-leo") is Result.Err)
        t += 2 * MINUTE
        repo.pulse()
        assertEquals(FriendStatus.ACCEPTED, repo.state.friendStatus("sim-leo"))
        repo.setBestFriend("sim-leo", true)
        assertTrue(repo.state.isBestFriend("sim-leo"))
        repo.removeFriend("sim-leo")
        assertNull(repo.state.friendStatus("sim-leo"))
        assertTrue(repo.requestFriend("me") is Result.Err)
    }

    @Test fun simulatorIsDeterministicAndKeepsOneCheckInPerOwner() {
        val s1 = CommunitySimulator.step(repo.state, t)
        val s2 = CommunitySimulator.step(repo.state, t)
        assertEquals(s1.activeCheckIns(t).map { it.id }.sorted(), s2.activeCheckIns(t).map { it.id }.sorted())
        var s = repo.state
        for (i in 0 until 48) {
            val tt = t + i * 30 * MINUTE
            s = CommunitySimulator.step(s, tt)
            val active = s.activeCheckIns(tt)
            assertEquals(active.map { it.userId }.distinct().size, active.size)
            active.forEach { assertTrue(it.petIds.isNotEmpty()) }
        }
        // Eventos de demonstração nunca ficam todos no passado.
        val later = CommunitySimulator.step(s, t + 30 * DAY)
        assertTrue(later.publicEvents.all { it.startsAt + it.durationMin * MINUTE > t + 30 * DAY })
    }

    @Test fun persistenceRoundTripAndCorruptionFallback() {
        val dir = kotlin.io.path.createTempDirectory("matilha").toFile()
        val f = File(dir, "state.bin")
        val r1 = Repository(StateStore(f), clock = { t })
        val saved = (r1.savePet(Pet(r1.newPetId(), "me", "Nina", "SRD", AgeGroup.PUPPY, 3.0, Sex.FEMALE, false)) as Result.Ok).value
        val r2 = Repository(StateStore(f), clock = { t })
        assertEquals("Nina", r2.state.pet(saved.id)?.name)
        // Arquivo corrompido: usa o backup.
        r2.updateMe("Ana", "")
        f.writeText("lixo")
        val r3 = Repository(StateStore(f), clock = { t })
        assertEquals("Nina", r3.state.pet(saved.id)?.name)
        dir.deleteRecursively()
    }

    @Test fun publicEvents() {
        onboard()
        assertTrue(repo.createPublicEvent("pl-parcao", "Oi", EventTheme.OTHER, t + DAY, 60, "") is Result.Err)
        assertTrue(repo.createPublicEvent("pl-parcao", "Encontro de Salsichinhas", EventTheme.BREED_MEETUP, t - HOUR, 60, "") is Result.Err)
        val e = (repo.createPublicEvent("pl-parcao", "Encontro de Salsichinhas", EventTheme.BREED_MEETUP, t + DAY, 60, "") as Result.Ok).value
        assertTrue("me" in e.attendees)
        repo.togglePublicAttendance("pe-idosos")
        assertTrue("me" in repo.state.publicEvents.first { it.id == "pe-idosos" }.attendees)
        repo.togglePublicAttendance("pe-idosos")
        assertFalse("me" in repo.state.publicEvents.first { it.id == "pe-idosos" }.attendees)
    }
}
