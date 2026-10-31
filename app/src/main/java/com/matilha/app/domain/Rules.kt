package com.matilha.app.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun formatDistance(m: Double): String = when {
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        else -> String.format(java.util.Locale("pt", "BR"), "%.1f km", m / 1000)
    }

    /**
     * Desloca um ponto de forma determinística (seed) entre [minM] e [maxM] metros — usado para
     * o círculo aproximado de eventos privados: o endereço real fica dentro do círculo, mas
     * nunca no centro.
     */
    fun obfuscate(lat: Double, lon: Double, seed: Long, minM: Double = 250.0, maxM: Double = 550.0): Pair<Double, Double> {
        val rnd = java.util.Random(seed)
        val angle = rnd.nextDouble() * 2 * Math.PI
        val dist = minM + rnd.nextDouble() * (maxM - minM)
        val dLat = (dist * cos(angle)) / 111_320.0
        val dLon = (dist * sin(angle)) / (111_320.0 * cos(Math.toRadians(lat)))
        return (lat + dLat) to (lon + dLon)
    }
}

/** Avisos de compatibilidade de UM cão (o meu) diante de outros. */
enum class WarningLevel { INFO, CAUTION, BLOCK }

data class CompatWarning(val level: WarningLevel, val message: String)

object Compatibility {

    /**
     * Lista os motivos pelos quais [other] pode estressar ou conflitar com [mine].
     * Considera apenas o que está declarado nos perfis.
     */
    fun warnings(mine: Pet, other: Pet): List<CompatWarning> {
        if (mine.id == other.id) return emptyList()
        val out = mutableListOf<CompatWarning>()
        if (Trigger.SELECTIVE in mine.triggers) {
            if (other.size in mine.fearsSizes) {
                out += CompatWarning(WarningLevel.CAUTION, "${mine.name} tem medo de cães de porte ${other.size.label.lowercase()} e ${other.name} é ${other.size.label.lowercase()}.")
            }
            val only = mine.onlyWithSex
            if (only != null && other.sex != only) {
                out += CompatWarning(WarningLevel.CAUTION, "${mine.name} só se dá bem com ${if (only == Sex.FEMALE) "fêmeas" else "machos"}; ${other.name} é ${other.sex.label.lowercase()}.")
            }
        }
        if (mine.isIntactMale && other.isIntactMale) {
            out += CompatWarning(WarningLevel.CAUTION, "${mine.name} e ${other.name} são machos inteiros — risco de disputa.")
        }
        if (other.isFemaleInHeat && mine.isIntactMale) {
            out += CompatWarning(WarningLevel.BLOCK, "${other.name} está no cio e ${mine.name} é macho inteiro.")
        }
        if (mine.isFemaleInHeat && other.isIntactMale) {
            out += CompatWarning(WarningLevel.BLOCK, "${mine.name} está no cio e ${other.name} é macho inteiro.")
        }
        if (PlayStyle.WRESTLER in other.playStyles && (PlayStyle.SHY in mine.playStyles || mine.ageGroup == AgeGroup.SENIOR || mine.size == SizeClass.MINI) && other.size.ordinal > mine.size.ordinal) {
            out += CompatWarning(WarningLevel.CAUTION, "${other.name} brinca de corpo a corpo e é maior que ${mine.name}.")
        }
        if (Trigger.RESOURCE_GUARDING in other.triggers) {
            out += CompatWarning(WarningLevel.INFO, "${other.name} é possessivo com recursos — evite brinquedos e petiscos por perto.")
        }
        if (other.underReview) {
            out += CompatWarning(WarningLevel.CAUTION, "O perfil de ${other.name} está em revisão pela comunidade.")
        }
        return out
    }

    /** Avisos agregados para todos os meus cães contra todos os presentes (sem os meus). */
    fun warningsForGroup(myPets: List<Pet>, present: List<Pet>): List<CompatWarning> {
        val mineIds = myPets.map { it.id }.toSet()
        val others = present.filter { it.id !in mineIds }
        return myPets.flatMap { m -> others.flatMap { o -> warnings(m, o) } }.distinctBy { it.message }
    }

    fun hasConflict(myPets: List<Pet>, present: List<Pet>): Boolean =
        warningsForGroup(myPets, present).any { it.level != WarningLevel.INFO }
}

object Harmony {
    /** Ex.: "4 cães de porte pequeno/médio, energia moderada". */
    fun summary(present: List<Pet>): String {
        if (present.isEmpty()) return "Nenhum cão no local agora — clima tranquilo."
        val n = present.size
        val sizes = present.map { it.size }.distinct().sortedBy { it.ordinal }
        val sizeTxt = when {
            sizes.size == 1 -> sizes.first().label.lowercase()
            sizes.size == 2 && sizes[1].ordinal - sizes[0].ordinal == 1 -> "${sizes[0].label.lowercase()}/${sizes[1].label.lowercase()}"
            else -> "variado (${sizes.first().label.lowercase()} a ${sizes.last().label.lowercase()})"
        }
        val avg = present.map { it.energy }.average()
        val energyTxt = energyLabel(avg)
        val dogs = if (n == 1) "1 cão" else "$n cães"
        return "$dogs de porte $sizeTxt, energia $energyTxt"
    }

    fun energyLabel(avg: Double): String = when {
        avg < 1.8 -> "muito baixa"
        avg < 2.6 -> "baixa"
        avg < 3.5 -> "moderada"
        avg < 4.3 -> "alta"
        else -> "muito alta"
    }
}

/** Resultado da verificação dos filtros de porta de um evento privado. */
data class Eligibility(val pet: Pet, val reasons: List<String>) {
    val ok: Boolean get() = reasons.isEmpty()
}

object Vaccination {
    const val VALIDITY_MS = 365 * DAY
    const val ECTO_VALIDITY_MS = 90 * DAY

    fun missingOrExpired(pet: Pet, now: Long): List<Vaccine> =
        Vaccine.entries.filter { v ->
            val last = pet.vaccines.filter { it.vaccine == v }.maxOfOrNull { it.appliedAt }
            last == null || now - last > VALIDITY_MS || last > now
        }

    fun upToDate(pet: Pet, now: Long) = missingOrExpired(pet, now).isEmpty()

    fun ectoOk(pet: Pet, now: Long): Boolean {
        val at = pet.ectoparasiteControlAt ?: return false
        return at <= now && now - at <= ECTO_VALIDITY_MS
    }

    /** O selo "Vacinação em dia" exige as 4 vacinas válidas E comprovante aprovado. */
    fun hasSeal(pet: Pet, now: Long) = upToDate(pet, now) && pet.proofStatus == ProofStatus.APPROVED

    /**
     * Verificação automática do comprovante (MVP): aprova se há imagem anexada e as 4 vacinas
     * estão dentro da validade; caso contrário, recusa com o motivo.
     */
    fun evaluateProof(pet: Pet, now: Long): Pair<ProofStatus, String> {
        if (pet.proofUri.isNullOrBlank()) return ProofStatus.NONE to "Anexe uma foto da carteirinha de vacinação."
        val missing = missingOrExpired(pet, now)
        return if (missing.isEmpty()) ProofStatus.APPROVED to "Datas conferidas: todas as vacinas válidas."
        else ProofStatus.REJECTED to "Vencidas ou sem data: ${missing.joinToString { it.label }}."
    }
}

object DoorFilter {
    fun check(pet: Pet, req: EventRequirements, now: Long): Eligibility {
        val r = mutableListOf<String>()
        req.maxWeightKg?.let { if (pet.weightKg > it) r += "Peso acima do limite (${fmtKg(pet.weightKg)} > ${fmtKg(it)})" }
        if (req.allowedSizes.isNotEmpty() && pet.size !in req.allowedSizes) r += "Porte ${pet.size.label} não aceito"
        if (req.requireApprovedVaccineProof && !Vaccination.hasSeal(pet, now)) r += "Exige comprovante de vacina aprovado no app"
        if (req.forbidIntactMales && pet.isIntactMale) r += "Proibido machos não castrados"
        if (req.forbidFemalesInHeat && pet.isFemaleInHeat) r += "Fêmeas no cio não podem participar"
        req.maxEnergy?.let { if (pet.energy > it) r += "Energia ${pet.energy} acima do máximo ($it)" }
        if (req.allowedAgeGroups.isNotEmpty() && pet.ageGroup !in req.allowedAgeGroups) r += "Faixa etária ${pet.ageGroup.label} não aceita"
        if (req.forbidUnderReview && pet.underReview) r += "Perfil em revisão pela comunidade"
        return Eligibility(pet, r)
    }

    fun describe(req: EventRequirements): List<String> {
        val out = mutableListOf<String>()
        req.maxWeightKg?.let { out += "Apenas cães até ${fmtKg(it)}" }
        if (req.allowedSizes.isNotEmpty()) out += "Portes: ${req.allowedSizes.sortedBy { it.ordinal }.joinToString { it.label }}"
        if (req.requireApprovedVaccineProof) out += "Obrigatório comprovante de vacina aprovado no app"
        if (req.forbidIntactMales) out += "Proibido machos não castrados"
        if (req.forbidFemalesInHeat) out += "Proibido fêmeas no cio"
        req.maxEnergy?.let { out += "Energia máxima: $it/5" }
        if (req.allowedAgeGroups.isNotEmpty()) out += "Idade: ${req.allowedAgeGroups.sortedBy { it.ordinal }.joinToString { it.label }}"
        if (req.forbidUnderReview) out += "Perfis em revisão não participam"
        return out
    }

    fun fmtKg(kg: Double): String =
        if (kg % 1.0 == 0.0) "${kg.toInt()} kg" else String.format(java.util.Locale("pt", "BR"), "%.1f kg", kg)
}

data class PlaceConsensus(
    val fencing: Fencing?,
    val floor: Floor?,
    val amenities: Map<Amenity, Int>, // votos "sim"
    val reviewCount: Int,
) {
    /** Uma comodidade é confirmada se a maioria dos avaliadores marcou. */
    fun confirmed(a: Amenity) = reviewCount > 0 && (amenities[a] ?: 0) * 2 > reviewCount
}

object PlaceRules {
    fun consensus(reviews: List<PlaceReview>): PlaceConsensus {
        // Só o voto mais recente de cada usuário conta.
        val latest = reviews.groupBy { it.userId }.map { (_, v) -> v.maxBy { it.createdAt } }
        // Empate na moda: prevalece a opção MENOS segura (princípio da precaução).
        val fencing = latest.groupingBy { it.fencing }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Fencing, Int>> { it.value }.thenBy { it.key.safetyRank })
            .firstOrNull()?.key
        val floor = latest.groupingBy { it.floor }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Floor, Int>> { it.value }.thenByDescending { it.key == Floor.CONCRETE })
            .firstOrNull()?.key
        val amen = Amenity.entries.associateWith { a -> latest.count { a in it.amenities } }
        return PlaceConsensus(fencing, floor, amen, latest.size)
    }

    fun hotFloorNow(floor: Floor?, hour: Int) = floor == Floor.CONCRETE && hour in 10..15

    /** Classificação de movimento por hora. */
    enum class Crowd(val label: String) { EMPTY("Vazio"), CALM("Tranquilo"), MODERATE("Movimentado"), BUSY("Muvuca") }

    fun crowdOf(avgDogs: Double): Crowd = when {
        avgDogs < 0.5 -> Crowd.EMPTY
        avgDogs < 2.5 -> Crowd.CALM
        avgDogs < 6.0 -> Crowd.MODERATE
        else -> Crowd.BUSY
    }

    /** Faixas contíguas (06h–23h) em que o local costuma estar vazio. Ex.: [6..7, 13..15]. */
    fun quietWindows(typical: List<Double>, from: Int = 6, to: Int = 22): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (h in from..to) {
            val empty = crowdOf(typical.getOrElse(h) { 0.0 }) == Crowd.EMPTY
            if (empty && start < 0) start = h
            if ((!empty || h == to) && start >= 0) {
                out += start..(if (empty) h else h - 1)
                start = -1
            }
        }
        return out
    }

    fun formatWindow(r: IntRange) = "%02dh–%02dh".format(r.first, r.last + 1)

    /** "Fuga da Muvuca": vazio agora (ninguém com check-in) e historicamente vazio nesta hora. */
    fun isQuietNow(place: Place, activeDogs: Int, hour: Int): Boolean =
        activeDogs == 0 && crowdOf(place.typicalDogsByHour.getOrElse(hour) { 0.0 }) == Crowd.EMPTY

    fun hasShadeAt(place: Place, hour: Int) = hour in place.shadeHours
}

object ScheduleRules {
    const val DAY_START_MIN = 6 * 60
    const val DAY_END_MIN = 23 * 60
    const val SLOT_MIN = 30

    /** Slots de 30 em 30 min de 06h00 a 22h30 (último termina às 23h). */
    val slots: List<Int> = (DAY_START_MIN until DAY_END_MIN step SLOT_MIN).toList()

    fun label(min: Int) = "%02dh%02d".format(min / 60, min % 60)

    fun validate(fromMin: Int, toMin: Int): String? = when {
        fromMin % SLOT_MIN != 0 || toMin % SLOT_MIN != 0 -> "Use horários de 30 em 30 minutos."
        fromMin < DAY_START_MIN || toMin > DAY_END_MIN -> "A agenda vai das 06h às 23h."
        toMin <= fromMin -> "O horário final precisa ser depois do inicial."
        toMin - fromMin > 4 * 60 -> "Máximo de 4 horas por agendamento."
        else -> null
    }

    fun overlaps(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int) = aFrom < bTo && bFrom < aTo

    /** Quantidade de cães agendados por slot. */
    fun dogsPerSlot(schedules: List<Schedule>): Map<Int, Int> =
        slots.associateWith { s -> schedules.filter { s >= it.fromMin && s < it.toMin }.sumOf { it.petIds.size } }
}

object Radar {
    /** Retorna os pets (de outros tutores) que atendem à regra, ou null se a regra não dispara. */
    fun evaluate(
        rule: RadarRule,
        nowMinOfDay: Int,
        candidates: List<Pair<Pet, Double>>, // pet presente + distância do local até o usuário
    ): List<Pet>? {
        if (!rule.enabled) return null
        if (nowMinOfDay < rule.fromMin || nowMinOfDay >= rule.toMin) return null
        val match = candidates.filter { (p, d) ->
            d <= rule.radiusM &&
                (rule.sizes.isEmpty() || p.size in rule.sizes) &&
                (!rule.neuteredOnly || p.neutered) &&
                (rule.playStyles.isEmpty() || p.playStyles.any { it in rule.playStyles }) &&
                !p.underReview
        }.map { it.first }.distinctBy { it.id }
        return if (match.size >= rule.minDogs) match else null
    }

    fun describe(rule: RadarRule): String {
        val parts = mutableListOf("pelo menos ${rule.minDogs} ${if (rule.minDogs == 1) "cão" else "cães"}")
        if (rule.sizes.isNotEmpty()) parts += "porte ${rule.sizes.sortedBy { it.ordinal }.joinToString("/") { it.label.lowercase() }}"
        if (rule.neuteredOnly) parts += "castrados"
        if (rule.playStyles.isNotEmpty()) parts += "estilo ${rule.playStyles.joinToString(" ou ") { "'${it.label}'" }}"
        return "Me avise se ${parts.joinToString(", ")} derem check-in a até ${Geo.formatDistance(rule.radiusM)} entre ${ScheduleRules.label(rule.fromMin)} e ${ScheduleRules.label(rule.toMin)}"
    }
}

object Trust {
    /** Nº de tutores distintos dizendo "não condiz" para abrir revisão automática. */
    const val REVIEW_THRESHOLD = 2

    data class Score(val positive: Int, val negative: Int) {
        val total get() = positive + negative
        /** 0..100, com prior bayesiano (3 positivos virtuais) para não punir perfis novos. */
        val percent: Int get() = (((positive + 3.0) / (total + 3.0)) * 100).roundToInt()
    }

    fun score(petId: String, feedback: List<TrustFeedback>): Score {
        val latest = feedback.filter { it.aboutPetId == petId }.groupBy { it.fromUserId }.map { (_, v) -> v.maxBy { it.at } }
        return Score(latest.count { it.matches }, latest.count { !it.matches })
    }

    fun shouldFlag(petId: String, feedback: List<TrustFeedback>): Boolean {
        val s = score(petId, feedback)
        return s.negative >= REVIEW_THRESHOLD && s.negative * 2 >= s.total
    }
}
