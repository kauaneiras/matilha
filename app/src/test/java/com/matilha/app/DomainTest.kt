package com.matilha.app

import com.matilha.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val now = 1_700_000_000_000L
    private fun pet(id: String, kg: Double = 15.0, sex: Sex = Sex.MALE, neutered: Boolean = true, owner: String = "o-$id", energy: Int = 3,
                    styles: Set<PlayStyle> = emptySet(), triggers: Set<Trigger> = emptySet(), heat: Boolean = false,
                    onlyWith: Sex? = null, fears: Set<SizeClass> = emptySet(), age: AgeGroup = AgeGroup.ADULT, review: Boolean = false,
                    vaccines: List<VaccineRecord> = Vaccine.entries.map { VaccineRecord(it, now - 30 * DAY) }, proof: ProofStatus = ProofStatus.APPROVED) =
        Pet(id, owner, id, "SRD", age, kg, sex, neutered, heat, vaccines, now - 10 * DAY, proof, "x", energy, styles, triggers, onlyWith, fears, underReview = review)

    @Test fun sizeBoundaries() {
        assertEquals(SizeClass.MINI, SizeClass.fromWeight(5.0))
        assertEquals(SizeClass.SMALL, SizeClass.fromWeight(5.01))
        assertEquals(SizeClass.SMALL, SizeClass.fromWeight(10.0))
        assertEquals(SizeClass.MEDIUM, SizeClass.fromWeight(25.0))
        assertEquals(SizeClass.LARGE, SizeClass.fromWeight(45.0))
        assertEquals(SizeClass.GIANT, SizeClass.fromWeight(45.1))
    }

    @Test fun fearOfGiantsRaisesCaution() {
        val mine = pet("mine", triggers = setOf(Trigger.SELECTIVE), fears = setOf(SizeClass.GIANT))
        val dane = pet("dane", kg = 62.0)
        val w = Compatibility.warnings(mine, dane)
        assertTrue(w.any { it.level == WarningLevel.CAUTION && "medo" in it.message })
        assertTrue(Compatibility.hasConflict(listOf(mine), listOf(dane)))
        assertFalse(Compatibility.hasConflict(listOf(mine), listOf(pet("small", kg = 8.0))))
    }

    @Test fun selectiveBySex() {
        val mine = pet("m", triggers = setOf(Trigger.SELECTIVE), onlyWith = Sex.FEMALE)
        assertTrue(Compatibility.hasConflict(listOf(mine), listOf(pet("x", sex = Sex.MALE))))
        assertFalse(Compatibility.hasConflict(listOf(mine), listOf(pet("y", sex = Sex.FEMALE))))
    }

    @Test fun selectiveWithoutTriggerIsIgnored() {
        // Campos de seletividade só valem com o gatilho marcado.
        val mine = pet("m", fears = setOf(SizeClass.GIANT))
        assertFalse(Compatibility.hasConflict(listOf(mine), listOf(pet("g", kg = 70.0))))
    }

    @Test fun intactMalesAndHeat() {
        val m1 = pet("a", neutered = false); val m2 = pet("b", neutered = false)
        assertTrue(Compatibility.warnings(m1, m2).any { it.level == WarningLevel.CAUTION })
        val heat = pet("c", sex = Sex.FEMALE, neutered = false, heat = true)
        assertTrue(Compatibility.warnings(m1, heat).any { it.level == WarningLevel.BLOCK })
        assertTrue(Compatibility.warnings(heat, m1).any { it.level == WarningLevel.BLOCK })
        // Fêmea castrada nunca está "no cio" mesmo que o campo venha true.
        assertFalse(pet("d", sex = Sex.FEMALE, neutered = true, heat = true).isFemaleInHeat)
        assertTrue(Compatibility.warnings(pet("n"), heat).isEmpty())
    }

    @Test fun ownPetsNeverConflictWithThemselves() {
        val a = pet("a", neutered = false, owner = "me"); val b = pet("b", neutered = false, owner = "me")
        assertFalse(Compatibility.hasConflict(listOf(a, b), listOf(a, b)))
    }

    @Test fun resourceGuardingIsInfoOnly() {
        val g = pet("g", triggers = setOf(Trigger.RESOURCE_GUARDING))
        val w = Compatibility.warnings(pet("m"), g)
        assertEquals(1, w.size); assertEquals(WarningLevel.INFO, w[0].level)
        assertFalse(Compatibility.hasConflict(listOf(pet("m")), listOf(g)))
    }

    @Test fun wrestlerVsShySmall() {
        val shy = pet("s", kg = 4.0, styles = setOf(PlayStyle.SHY))
        val big = pet("b", kg = 30.0, styles = setOf(PlayStyle.WRESTLER))
        assertTrue(Compatibility.hasConflict(listOf(shy), listOf(big)))
        assertFalse(Compatibility.hasConflict(listOf(big), listOf(shy)))
    }

    @Test fun harmonySummary() {
        val ps = listOf(pet("a", kg = 8.0, energy = 3), pet("b", kg = 9.0, energy = 3), pet("c", kg = 12.0, energy = 3), pet("d", kg = 20.0, energy = 4))
        assertEquals("4 cães de porte pequeno/médio, energia moderada", Harmony.summary(ps))
        assertEquals("1 cão de porte gigante, energia moderada", Harmony.summary(listOf(pet("g", kg = 60.0))))
        assertTrue(Harmony.summary(emptyList()).startsWith("Nenhum"))
        assertTrue(Harmony.summary(listOf(pet("x", kg = 3.0), pet("y", kg = 50.0))).contains("variado"))
    }

    @Test fun doorFilters() {
        val req = EventRequirements(maxWeightKg = 10.0, requireApprovedVaccineProof = true, forbidIntactMales = true)
        assertTrue(DoorFilter.check(pet("ok", kg = 9.0), req, now).ok)
        assertFalse(DoorFilter.check(pet("heavy", kg = 11.0), req, now).ok)
        assertFalse(DoorFilter.check(pet("intact", kg = 8.0, neutered = false), req, now).ok)
        assertFalse(DoorFilter.check(pet("noproof", kg = 8.0, proof = ProofStatus.NONE), req, now).ok)
        val expired = pet("exp", kg = 8.0, vaccines = Vaccine.entries.map { VaccineRecord(it, now - 400 * DAY) })
        assertFalse(DoorFilter.check(expired, req, now).ok)
        assertFalse(DoorFilter.check(pet("rev", kg = 8.0, review = true), req, now).ok)
        val sized = EventRequirements(allowedSizes = setOf(SizeClass.LARGE), maxEnergy = 3, allowedAgeGroups = setOf(AgeGroup.SENIOR), forbidUnderReview = false)
        val r = DoorFilter.check(pet("x", kg = 10.0, energy = 5), sized, now)
        assertEquals(3, r.reasons.size)
    }

    @Test fun vaccination() {
        val p = pet("p")
        assertTrue(Vaccination.upToDate(p, now))
        assertTrue(Vaccination.hasSeal(p, now))
        val missing = p.copy(vaccines = p.vaccines.filterNot { it.vaccine == Vaccine.GIARDIA })
        assertEquals(listOf(Vaccine.GIARDIA), Vaccination.missingOrExpired(missing, now))
        assertEquals(ProofStatus.REJECTED, Vaccination.evaluateProof(missing, now).first)
        assertEquals(ProofStatus.NONE, Vaccination.evaluateProof(p.copy(proofUri = null), now).first)
        assertEquals(ProofStatus.APPROVED, Vaccination.evaluateProof(p, now).first)
        val future = p.copy(vaccines = Vaccine.entries.map { VaccineRecord(it, now + 5 * DAY) })
        assertFalse(Vaccination.upToDate(future, now))
        assertTrue(Vaccination.ectoOk(p, now))
        assertFalse(Vaccination.ectoOk(p.copy(ectoparasiteControlAt = now - 100 * DAY), now))
    }

    @Test fun consensusPrefersSafetyOnTiesAndLatestVote() {
        val r = listOf(
            PlaceReview("p", "u1", Fencing.DOUBLE_GATE, Floor.GRASS, setOf(Amenity.WATER), 1),
            PlaceReview("p", "u2", Fencing.OPEN, Floor.GRASS, setOf(Amenity.WATER, Amenity.NIGHT_LIGHT), 2),
        )
        val c = PlaceRules.consensus(r)
        assertEquals(Fencing.OPEN, c.fencing) // empate -> menos seguro
        assertTrue(c.confirmed(Amenity.WATER))
        assertFalse(c.confirmed(Amenity.NIGHT_LIGHT)) // 1 de 2 não é maioria
        val updated = r + PlaceReview("p", "u2", Fencing.DOUBLE_GATE, Floor.GRASS, emptySet(), 3)
        val c2 = PlaceRules.consensus(updated)
        assertEquals(2, c2.reviewCount)
        assertEquals(Fencing.DOUBLE_GATE, c2.fencing)
        assertNull(PlaceRules.consensus(emptyList()).fencing)
    }

    @Test fun hotFloorWindow() {
        assertFalse(PlaceRules.hotFloorNow(Floor.CONCRETE, 9))
        assertTrue(PlaceRules.hotFloorNow(Floor.CONCRETE, 10))
        assertTrue(PlaceRules.hotFloorNow(Floor.CONCRETE, 15))
        assertFalse(PlaceRules.hotFloorNow(Floor.CONCRETE, 16))
        assertFalse(PlaceRules.hotFloorNow(Floor.GRASS, 12))
    }

    @Test fun quietWindows() {
        val t = MutableList(24) { 0.0 }; (6..8).forEach { t[it] = 3.0 }; (17..19).forEach { t[it] = 5.0 }
        val w = PlaceRules.quietWindows(t)
        assertEquals(listOf(9..16, 20..22), w)
        assertEquals("09h–17h", PlaceRules.formatWindow(w[0]))
        assertEquals("20h–23h", PlaceRules.formatWindow(w[1]))
        assertEquals(listOf(6..22), PlaceRules.quietWindows(List(24) { 0.0 }))
        assertTrue(PlaceRules.quietWindows(List(24) { 9.0 }).isEmpty())
    }

    @Test fun scheduleValidation() {
        assertNull(ScheduleRules.validate(17 * 60 + 30, 18 * 60 + 30))
        assertNotNull(ScheduleRules.validate(17 * 60 + 15, 18 * 60))
        assertNotNull(ScheduleRules.validate(5 * 60 + 30, 7 * 60))
        assertNotNull(ScheduleRules.validate(22 * 60, 23 * 60 + 30))
        assertNotNull(ScheduleRules.validate(18 * 60, 18 * 60))
        assertNull(ScheduleRules.validate(22 * 60 + 30, 23 * 60))
        assertEquals(34, ScheduleRules.slots.size)
        assertEquals(6 * 60, ScheduleRules.slots.first()); assertEquals(22 * 60 + 30, ScheduleRules.slots.last())
        assertEquals("17h30", ScheduleRules.label(17 * 60 + 30))
    }

    @Test fun radar() {
        val rule = RadarRule("r", "x", minDogs = 2, sizes = setOf(SizeClass.MEDIUM), neuteredOnly = true, playStyles = setOf(PlayStyle.RUNNER), radiusM = 2000.0)
        val good1 = pet("a", kg = 15.0, styles = setOf(PlayStyle.RUNNER))
        val good2 = pet("b", kg = 20.0, styles = setOf(PlayStyle.RUNNER, PlayStyle.WRESTLER))
        val intact = pet("c", kg = 15.0, neutered = false, styles = setOf(PlayStyle.RUNNER))
        val far = pet("d", kg = 15.0, styles = setOf(PlayStyle.RUNNER))
        val at18 = 18 * 60
        assertNull(Radar.evaluate(rule, at18, listOf(good1 to 100.0, intact to 100.0, far to 5000.0)))
        assertEquals(2, Radar.evaluate(rule, at18, listOf(good1 to 100.0, good2 to 1500.0))!!.size)
        assertNull(Radar.evaluate(rule, 16 * 60, listOf(good1 to 100.0, good2 to 100.0)))
        assertNull(Radar.evaluate(rule, 20 * 60, listOf(good1 to 100.0, good2 to 100.0)))
        assertNull(Radar.evaluate(rule.copy(enabled = false), at18, listOf(good1 to 100.0, good2 to 100.0)))
        // Mesmo pet listado duas vezes conta uma vez.
        assertNull(Radar.evaluate(rule, at18, listOf(good1 to 100.0, good1 to 200.0)))
        assertTrue(Radar.describe(rule).contains("castrados"))
    }

    @Test fun trust() {
        val fb = listOf(
            TrustFeedback("1", "u1", "p", false, "mordeu", "x", 1),
            TrustFeedback("2", "u1", "p", false, "de novo", "x", 2), // mesmo tutor conta uma vez
        )
        assertFalse(Trust.shouldFlag("p", fb))
        val fb2 = fb + TrustFeedback("3", "u2", "p", false, "agressivo", "y", 3)
        assertTrue(Trust.shouldFlag("p", fb2))
        val fb3 = fb2 + (4..8).map { TrustFeedback("$it", "u$it", "p", true, "", "z", it.toLong()) }
        assertFalse(Trust.shouldFlag("p", fb3)) // maioria confirma o perfil
        assertEquals(100, Trust.score("new", emptyList()).percent)
    }

    @Test fun geo() {
        val d = Geo.distanceM(-15.8, -47.9, -15.8, -47.89)
        assertTrue(d in 1000.0..1150.0)
        val (la, lo) = Geo.obfuscate(-15.8, -47.9, 42)
        val off = Geo.distanceM(-15.8, -47.9, la, lo)
        assertTrue("deslocamento $off", off in 250.0..550.0)
        // Determinístico
        assertEquals(Geo.obfuscate(-15.8, -47.9, 42), Geo.obfuscate(-15.8, -47.9, 42))
        assertEquals("400 m", Geo.formatDistance(404.0))
        assertEquals("1,5 km", Geo.formatDistance(1500.0))
    }

    @Test fun hazardLifecycle() {
        val h = Hazard("h", "p", HazardType.LOOSE_AGGRESSIVE_DOG, "u1", now)
        assertTrue(h.isActive(now + HOUR))
        assertFalse(h.isActive(now + 4 * HOUR)) // expira em 3h
        assertFalse(h.copy(resolvedBy = setOf("u2")).isActive(now))
        assertTrue(h.copy(confirmations = setOf("u3"), resolvedBy = setOf("u2")).isActive(now))
    }
}
