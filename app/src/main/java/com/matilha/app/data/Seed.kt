package com.matilha.app.data

import com.matilha.app.domain.*

/**
 * Dados iniciais de demonstração (Brasília). Usuários com prefixo "sim-" representam a comunidade
 * enquanto não há servidor; tudo que o usuário real cria é dele de verdade.
 */
object Seed {
    const val ME = "me"

    private fun occ(vararg peaks: Pair<IntRange, Double>): List<Double> {
        val a = MutableList(24) { 0.0 }
        peaks.forEach { (r, v) -> r.forEach { a[it] = v } }
        return a
    }

    val places = listOf(
        Place("pl-parcao", "Parcão do Parque da Cidade", PlaceKind.DOG_PARK, -15.80020, -47.90680, 90.0, "sim-ana",
            shadeHours = setOf(6, 7, 8, 16, 17, 18),
            typicalDogsByHour = occ(6..7 to 3.0, 8..9 to 5.0, 10..15 to 1.5, 16..16 to 4.0, 17..19 to 9.0, 20..21 to 3.0)),
        Place("pl-308s", "Gramado da SQS 308", PlaceKind.LAWN, -15.81420, -47.90190, 70.0, "sim-bruno",
            shadeHours = setOf(6, 7, 8, 9, 10, 15, 16, 17),
            typicalDogsByHour = occ(6..8 to 2.0, 9..16 to 0.3, 17..19 to 4.0, 20..21 to 1.0)),
        Place("pl-205n", "Praça da SQN 205", PlaceKind.SQUARE, -15.77430, -47.88160, 60.0, "sim-carla",
            shadeHours = setOf(7, 8, 9, 16, 17),
            typicalDogsByHour = occ(6..7 to 1.0, 8..9 to 2.0, 10..16 to 0.2, 17..20 to 3.0)),
        Place("pl-sudoeste", "Cachorródromo do Sudoeste", PlaceKind.DOG_PARK, -15.79560, -47.92410, 60.0, "sim-diego",
            shadeHours = setOf(6, 7, 17),
            typicalDogsByHour = occ(6..8 to 4.0, 9..16 to 1.0, 17..20 to 7.0, 21..21 to 2.0)),
        Place("pl-olhos", "Trilha do Parque Olhos d'Água", PlaceKind.TRAIL, -15.74480, -47.88520, 150.0, "sim-elisa",
            shadeHours = (6..17).toSet(),
            typicalDogsByHour = occ(6..8 to 3.0, 9..15 to 1.0, 16..18 to 2.5)),
        Place("pl-pontao", "Orla do Pontão do Lago Sul", PlaceKind.PARK, -15.82790, -47.87210, 120.0, "sim-fabio",
            shadeHours = setOf(17, 18),
            typicalDogsByHour = occ(7..9 to 1.0, 10..15 to 0.2, 16..20 to 5.0, 21..22 to 2.0)),
        Place("pl-jbotanico", "Praça do Jardim Botânico", PlaceKind.SQUARE, -15.86390, -47.84050, 70.0, "sim-gabi",
            shadeHours = setOf(6, 7, 8, 9, 15, 16, 17),
            typicalDogsByHour = occ(6..8 to 1.0, 17..19 to 2.0)),
        Place("pl-114s", "Gramado da SQS 114", PlaceKind.LAWN, -15.82300, -47.89620, 60.0, "sim-ana",
            shadeHours = setOf(6, 7, 8, 16, 17, 18),
            typicalDogsByHour = occ(6..7 to 1.0, 18..20 to 2.0)),
    )

    private fun rev(place: String, user: String, f: Fencing, fl: Floor, vararg a: Amenity) =
        PlaceReview(place, user, f, fl, a.toSet(), 0L)

    val reviews = listOf(
        rev("pl-parcao", "sim-ana", Fencing.DOUBLE_GATE, Floor.GRASS, Amenity.NIGHT_LIGHT, Amenity.WATER, Amenity.SHADED_BENCH, Amenity.DOG_BINS, Amenity.POOP_BAGS),
        rev("pl-parcao", "sim-bruno", Fencing.DOUBLE_GATE, Floor.GRASS, Amenity.NIGHT_LIGHT, Amenity.WATER, Amenity.DOG_BINS),
        rev("pl-parcao", "sim-carla", Fencing.SIMPLE_FENCE, Floor.GRASS, Amenity.NIGHT_LIGHT, Amenity.WATER, Amenity.SHADED_BENCH),
        rev("pl-308s", "sim-bruno", Fencing.OPEN, Floor.GRASS, Amenity.NIGHT_LIGHT, Amenity.SHADED_BENCH),
        rev("pl-308s", "sim-elisa", Fencing.OPEN, Floor.GRASS, Amenity.SHADED_BENCH),
        rev("pl-205n", "sim-carla", Fencing.SEMI_OPEN, Floor.DIRT, Amenity.NIGHT_LIGHT),
        rev("pl-sudoeste", "sim-diego", Fencing.SIMPLE_FENCE, Floor.SAND, Amenity.NIGHT_LIGHT, Amenity.WATER, Amenity.DOG_BINS, Amenity.POOP_BAGS),
        rev("pl-sudoeste", "sim-fabio", Fencing.SIMPLE_FENCE, Floor.SAND, Amenity.WATER, Amenity.DOG_BINS),
        rev("pl-olhos", "sim-elisa", Fencing.OPEN, Floor.DIRT, Amenity.SHADED_BENCH),
        rev("pl-pontao", "sim-fabio", Fencing.OPEN, Floor.CONCRETE, Amenity.NIGHT_LIGHT, Amenity.DOG_BINS),
        rev("pl-jbotanico", "sim-gabi", Fencing.SEMI_OPEN, Floor.GRASS, Amenity.SHADED_BENCH),
        rev("pl-114s", "sim-ana", Fencing.OPEN, Floor.GRASS, Amenity.NIGHT_LIGHT),
    )

    private val users = listOf(
        User("sim-ana", "Ana", "Asa Sul"), User("sim-bruno", "Bruno", "Asa Sul"), User("sim-carla", "Carla", "Lago Sul"),
        User("sim-diego", "Diego", "Sudoeste"), User("sim-elisa", "Elisa", "Asa Norte"), User("sim-fabio", "Fábio", "Lago Sul"),
        User("sim-gabi", "Gabi", "Jardim Botânico"), User("sim-hugo", "Hugo", "Asa Norte"), User("sim-iris", "Íris", "Sudoeste"),
        User("sim-joao", "João", "Asa Sul"), User("sim-kel", "Kelly", "Lago Sul"), User("sim-leo", "Léo", "Asa Norte"),
    )

    /** Vacinas aplicadas há [daysAgo] dias. */
    private fun vax(now: Long, daysAgo: Int) = Vaccine.entries.map { VaccineRecord(it, now - daysAgo * DAY) }

    private fun pets(now: Long): List<Pet> {
        val v = vax(now, 120)
        fun p(id: String, owner: String, name: String, breed: String, age: AgeGroup, kg: Double, sex: Sex, neut: Boolean, energy: Int,
              styles: Set<PlayStyle>, trig: Set<Trigger> = emptySet(), heat: Boolean = false, proof: Boolean = true,
              onlyWith: Sex? = null, fears: Set<SizeClass> = emptySet(), review: Boolean = false) =
            Pet(id, owner, name, breed, age, kg, sex, neut, heat, if (proof) v else vax(now, 500), now - 30 * DAY,
                if (proof) ProofStatus.APPROVED else ProofStatus.NONE, if (proof) "demo://carteirinha" else null,
                energy, styles, trig, onlyWith, fears, underReview = review)
        return listOf(
            p("pet-paco", "sim-ana", "Paco", "SRD", AgeGroup.ADULT, 14.0, Sex.MALE, true, 4, setOf(PlayStyle.RUNNER)),
            p("pet-thor", "sim-bruno", "Thor", "Dogue Alemão", AgeGroup.ADULT, 62.0, Sex.MALE, true, 2, setOf(PlayStyle.WRESTLER)),
            p("pet-mel", "sim-carla", "Mel", "Shih-tzu", AgeGroup.SENIOR, 6.0, Sex.FEMALE, true, 1, setOf(PlayStyle.SHY)),
            p("pet-bidu", "sim-diego", "Bidu", "SRD", AgeGroup.ADULT, 18.0, Sex.MALE, true, 4, setOf(PlayStyle.RUNNER, PlayStyle.WRESTLER)),
            p("pet-luna", "sim-elisa", "Luna", "Border Collie", AgeGroup.ADULT, 19.0, Sex.FEMALE, true, 5, setOf(PlayStyle.RUNNER, PlayStyle.BALL_FOCUSED)),
            p("pet-fred", "sim-fabio", "Fred", "Labrador", AgeGroup.ADULT, 32.0, Sex.MALE, false, 4, setOf(PlayStyle.BALL_FOCUSED), setOf(Trigger.RESOURCE_GUARDING)),
            p("pet-pipoca", "sim-gabi", "Pipoca", "Spitz Alemão", AgeGroup.PUPPY, 3.5, Sex.FEMALE, false, 4, setOf(PlayStyle.RUNNER), proof = false),
            p("pet-zeus", "sim-hugo", "Zeus", "Pit Bull", AgeGroup.ADULT, 28.0, Sex.MALE, false, 5, setOf(PlayStyle.WRESTLER), setOf(Trigger.LEASH_REACTIVE), review = true),
            p("pet-bento", "sim-iris", "Bento", "Basset Hound", AgeGroup.SENIOR, 24.0, Sex.MALE, true, 1, setOf(PlayStyle.SHY)),
            p("pet-kira", "sim-joao", "Kira", "Golden Retriever", AgeGroup.ADULT, 30.0, Sex.FEMALE, true, 3, setOf(PlayStyle.BALL_FOCUSED, PlayStyle.RUNNER)),
            p("pet-nina", "sim-kel", "Nina", "Dachshund", AgeGroup.ADULT, 8.0, Sex.FEMALE, false, 3, setOf(PlayStyle.RUNNER), heat = true),
            p("pet-tobias", "sim-leo", "Tobias", "Bulldog Francês", AgeGroup.ADULT, 12.0, Sex.MALE, true, 2, setOf(PlayStyle.WRESTLER),
                setOf(Trigger.SELECTIVE), onlyWith = Sex.FEMALE),
            p("pet-amora", "sim-ana", "Amora", "SRD", AgeGroup.SENIOR, 11.0, Sex.FEMALE, true, 2, setOf(PlayStyle.SHY)),
            p("pet-duque", "sim-bruno", "Duque", "Pastor Alemão", AgeGroup.ADULT, 38.0, Sex.MALE, true, 4, setOf(PlayStyle.RUNNER)),
            p("pet-lola", "sim-diego", "Lola", "Beagle", AgeGroup.ADULT, 13.0, Sex.FEMALE, true, 4, setOf(PlayStyle.RUNNER)),
            p("pet-max", "sim-elisa", "Max", "Labrador", AgeGroup.PUPPY, 16.0, Sex.MALE, false, 5, setOf(PlayStyle.WRESTLER, PlayStyle.RUNNER), proof = false),
            p("pet-bela", "sim-joao", "Bela", "Golden Retriever", AgeGroup.SENIOR, 29.0, Sex.FEMALE, true, 2, setOf(PlayStyle.SHY)),
            p("pet-chico", "sim-leo", "Chico", "SRD", AgeGroup.ADULT, 9.0, Sex.MALE, true, 3, setOf(PlayStyle.RUNNER)),
            p("pet-sol", "sim-iris", "Sol", "Poodle", AgeGroup.ADULT, 4.0, Sex.FEMALE, true, 3, setOf(PlayStyle.BALL_FOCUSED)),
            p("pet-rex", "sim-hugo", "Rex", "Husky Siberiano", AgeGroup.ADULT, 24.0, Sex.MALE, true, 5, setOf(PlayStyle.RUNNER, PlayStyle.WRESTLER)),
        )
    }

    /** Locais "de casa" de cada pet simulado (usados pelo simulador de presença). */
    val homes: Map<String, List<String>> = mapOf(
        "pet-paco" to listOf("pl-parcao", "pl-114s"), "pet-amora" to listOf("pl-114s", "pl-parcao"),
        "pet-thor" to listOf("pl-308s", "pl-parcao"), "pet-duque" to listOf("pl-parcao", "pl-308s"),
        "pet-mel" to listOf("pl-pontao", "pl-205n"), "pet-bidu" to listOf("pl-sudoeste", "pl-parcao"),
        "pet-lola" to listOf("pl-sudoeste", "pl-parcao"), "pet-luna" to listOf("pl-olhos", "pl-205n"),
        "pet-max" to listOf("pl-olhos", "pl-205n"), "pet-fred" to listOf("pl-pontao", "pl-parcao"),
        "pet-pipoca" to listOf("pl-jbotanico", "pl-pontao"), "pet-zeus" to listOf("pl-205n", "pl-sudoeste"),
        "pet-rex" to listOf("pl-sudoeste", "pl-olhos"), "pet-bento" to listOf("pl-sudoeste", "pl-308s"),
        "pet-sol" to listOf("pl-sudoeste", "pl-parcao"), "pet-kira" to listOf("pl-parcao", "pl-308s"),
        "pet-bela" to listOf("pl-308s", "pl-114s"), "pet-nina" to listOf("pl-pontao", "pl-jbotanico"),
        "pet-tobias" to listOf("pl-205n", "pl-olhos"), "pet-chico" to listOf("pl-205n", "pl-parcao"),
    )

    /** Dia/horário da próxima ocorrência de [dow] (Calendar.SUNDAY..) às [minute], a partir de agora. */
    fun nextWeekday(now: Long, dow: Int, minute: Int): Long {
        val today = Repository.startOfDay(now)
        for (i in 0..7) {
            val d = Repository.addDays(today, i)
            val c = java.util.Calendar.getInstance().apply { timeInMillis = d }
            val t = Fmt.atTime(d, minute)
            if (c.get(java.util.Calendar.DAY_OF_WEEK) == dow && t > now) return t
        }
        return Fmt.atTime(Repository.addDays(today, 7), minute)
    }

    fun create(now: Long): AppState {
        val me = User(ME, "", "")
        val sat = java.util.Calendar.SATURDAY
        val sun = java.util.Calendar.SUNDAY
        val publicEvents = listOf(
            PublicEvent("pe-salsichas", "pl-parcao", "sim-kel", "Encontro de Salsichinhas", EventTheme.BREED_MEETUP, nextWeekday(now, sat, 10 * 60), 120,
                "Dachshunds e amigos de patas curtas. Traga água!", setOf("sim-kel", "sim-gabi")),
            PublicEvent("pe-idosos", "pl-olhos", "sim-iris", "Cãominhada de Cães Idosos (ritmo lento)", EventTheme.SENIOR_WALK, nextWeekday(now, sun, 8 * 60), 60,
                "Percurso curto na sombra, várias paradas. Sem pressa.", setOf("sim-iris", "sim-carla")),
            PublicEvent("pe-filhotes", "pl-sudoeste", "sim-diego", "Sessão de Socialização de Filhotes", EventTheme.PUPPY_SOCIAL, nextWeekday(now, sat, 16 * 60), 90,
                "Para filhotes com a 2ª dose em dia. Adestradora voluntária presente.", setOf("sim-diego")),
        )
        val (lat1, lon1) = -15.84120 to -47.86950
        val (lat2, lon2) = -15.83010 to -47.89810
        val (lat3, lon3) = -15.86990 to -47.82990
        fun approx(id: String, lat: Double, lon: Double) = Geo.obfuscate(lat, lon, id.hashCode().toLong())
        val piscina = approx("pv-piscina", lat1, lon1)
        val quintal = approx("pv-quintal", lat2, lon2)
        val chacara = approx("pv-chacara", lat3, lon3)
        val privateEvents = listOf(
            PrivateEvent("pv-piscina", "sim-carla", "Tarde de Piscina para Goldens e Labradores",
                "Piscina rasa liberada para cães de porte grande que amam água. Toalhas por sua conta!",
                nextWeekday(now, sat, 15 * 60), 180, "Lago Sul", "SHIS QI 15, Conjunto 8, Casa 12 — Lago Sul", lat1, lon1, piscina.first, piscina.second,
                maxDogs = 6, requirements = EventRequirements(allowedSizes = setOf(SizeClass.MEDIUM, SizeClass.LARGE), requireApprovedVaccineProof = true, forbidIntactMales = true),
                amenities = setOf(HostAmenity.POOL, HostAmenity.FENCED_LAWN, HostAmenity.SHADE, HostAmenity.FRESH_WATER, HostAmenity.HUMAN_SNACKS),
                visibility = PrivateVisibility.MAP_APPROX,
                requests = listOf(JoinRequest("sim-joao", listOf("pet-kira"), RequestStatus.APPROVED, now - DAY))),
            PrivateEvent("pv-quintal", "sim-ana", "Brincadeira no quintal para 3 cães pequenos",
                "Quintal gramado e cercado, ideal para cães pequenos e tranquilos.",
                nextWeekday(now, sat, 16 * 60), 120, "Asa Sul", "SQS 309, Bloco C — casa dos fundos (interfone 101)", lat2, lon2, quintal.first, quintal.second,
                maxDogs = 3, requirements = EventRequirements(maxWeightKg = 10.0, requireApprovedVaccineProof = true, forbidIntactMales = true),
                amenities = setOf(HostAmenity.FENCED_LAWN, HostAmenity.SHADE, HostAmenity.FRESH_WATER, HostAmenity.TOYS_OK),
                visibility = PrivateVisibility.PACK_ONLY, invitedUserIds = setOf(ME)),
            PrivateEvent("pv-chacara", "sim-gabi", "Domingo na chácara — cães calmos",
                "Muito espaço e sombra. Para cães de energia baixa a moderada.",
                nextWeekday(now, sun, 9 * 60), 240, "Jardim Botânico", "Chácara Recanto, Rua 6, Lote 40 — Jardim Botânico", lat3, lon3, chacara.first, chacara.second,
                maxDogs = 8, requirements = EventRequirements(maxEnergy = 3, requireApprovedVaccineProof = false),
                amenities = setOf(HostAmenity.FENCED_LAWN, HostAmenity.SHADE, HostAmenity.FRESH_WATER, HostAmenity.HUMAN_SNACKS),
                visibility = PrivateVisibility.MAP_APPROX),
        )
        val feedback = listOf(
            TrustFeedback("tf-1", "sim-ana", "pet-zeus", false, "Tentou morder outros cães, perfil diz que é calmo.", "Praça da SQN 205", now - 3 * DAY),
            TrustFeedback("tf-2", "sim-elisa", "pet-zeus", false, "Muito mais agressivo do que o perfil indica.", "Praça da SQN 205", now - 2 * DAY),
            TrustFeedback("tf-3", "sim-carla", "pet-paco", true, "", "Parcão do Parque da Cidade", now - 5 * DAY),
            TrustFeedback("tf-4", "sim-bruno", "pet-paco", true, "", "Parcão do Parque da Cidade", now - 4 * DAY),
        )
        val friendships = listOf(
            Friendship("fr-ana", ME, "sim-ana", true, now - 20 * DAY, bestFriend = true),
            Friendship("fr-carla", "sim-carla", ME, true, now - 10 * DAY),
            Friendship("fr-diego", "sim-diego", ME, false, now - HOUR),
        )
        val rules = listOf(
            RadarRule("rr-default", "Turma de corrida", minDogs = 2, sizes = setOf(SizeClass.MEDIUM), neuteredOnly = true,
                playStyles = setOf(PlayStyle.RUNNER), radiusM = 3000.0, fromMin = 17 * 60, toMin = 20 * 60, enabled = false),
        )
        val hazards = listOf(
            Hazard("hz-1", "pl-205n", HazardType.BROKEN_GLASS, "sim-carla", now - 5 * HOUR, "Perto do banco da entrada norte.", setOf("sim-elisa")),
            Hazard("hz-2", "pl-308s", HazardType.TALL_GRASS_TICKS, "sim-bruno", now - DAY, "Fundo do gramado, perto do bloco H."),
        )
        val base = AppState(
            meId = ME, users = listOf(me) + users, pets = pets(now), places = places, reviews = reviews.map { it.copy(createdAt = now - 10 * DAY) },
            hazards = hazards, publicEvents = publicEvents, privateEvents = privateEvents, friendships = friendships,
            radarRules = rules, trustFeedback = feedback, simulatedUserIds = users.map { it.id }.toSet(),
        )
        return CommunitySimulator.step(base, now)
    }
}
