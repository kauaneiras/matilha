package com.matilha.app.domain

/*
 * Modelo de domínio do Matilha. Tudo aqui é Kotlin puro (sem Android),
 * para que as regras de segurança/compatibilidade sejam testáveis na JVM.
 */

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

enum class AgeGroup(val label: String, val hint: String) {
    PUPPY("Filhote", "até ~1 ano"),
    ADULT("Adulto", "1 a 7 anos"),
    SENIOR("Sênior", "acima de ~7 anos; porte grande envelhece antes"),
}

enum class Sex(val label: String) { MALE("Macho"), FEMALE("Fêmea") }

/** Porte derivado do peso — faixas exatas do requisito. */
enum class SizeClass(val label: String, val range: String) {
    MINI("Mini", "até 5 kg"),
    SMALL("Pequeno", "5 a 10 kg"),
    MEDIUM("Médio", "10 a 25 kg"),
    LARGE("Grande", "25 a 45 kg"),
    GIANT("Gigante", "acima de 45 kg");

    companion object {
        /** Limites: 5 kg é Mini, 10 kg Pequeno, 25 kg Médio, 45 kg Grande (limite superior inclusivo). */
        fun fromWeight(kg: Double): SizeClass = when {
            kg <= 5.0 -> MINI
            kg <= 10.0 -> SMALL
            kg <= 25.0 -> MEDIUM
            kg <= 45.0 -> LARGE
            else -> GIANT
        }
    }
}

enum class Vaccine(val label: String) {
    V8_V10("V8/V10"), RABIES("Raiva"), GIARDIA("Giárdia"), FLU("Gripe (tosse dos canis)")
}

enum class PlayStyle(val label: String, val description: String) {
    RUNNER("Corredor", "Gosta de ser perseguido e de correr em grupo."),
    WRESTLER("Lutador / Corpo a corpo", "Rola, dá trombadas e morde o pescoço de brincadeira. Pode assustar quem não conhece esse estilo."),
    BALL_FOCUSED("Focado em bolinha/brinquedo", "Ignora outros cães, só quer buscar coisas."),
    SHY("Observador / Tímido", "Prefere ficar perto do tutor e interage pouco."),
}

enum class Trigger(val label: String, val description: String) {
    LEASH_REACTIVE("Reativo na guia", "Late se estiver preso, mas fica bem solto."),
    RESOURCE_GUARDING("Possessivo com recursos", "Não pode ter brinquedos ou petiscos por perto quando está com outros cães."),
    SELECTIVE("Seletivo por porte ou sexo", "Só se dá bem com certos cães (veja detalhes)."),
}

enum class Energy(val level: Int, val label: String) {
    E1(1, "Só quer cheirar grama e deitar"),
    E2(2, "Tranquilo, passeio curto"),
    E3(3, "Moderado, brinca e descansa"),
    E4(4, "Agitado, brinca muito"),
    E5(5, "Velocista incansável (padrão Border Collie)");

    companion object { fun of(level: Int) = entries.first { it.level == level.coerceIn(1, 5) } }
}

data class VaccineRecord(val vaccine: Vaccine, val appliedAt: Long) : java.io.Serializable

enum class ProofStatus(val label: String) {
    NONE("Sem comprovante"), PENDING("Em análise"), APPROVED("Comprovante aprovado"), REJECTED("Comprovante recusado")
}

data class Pet(
    val id: String,
    val ownerId: String,
    val name: String,
    val breed: String,            // "SRD" para sem raça definida
    val ageGroup: AgeGroup,
    val weightKg: Double,
    val sex: Sex,
    val neutered: Boolean,
    val inHeat: Boolean = false,  // só faz sentido para fêmea inteira
    val vaccines: List<VaccineRecord> = emptyList(),
    val ectoparasiteControlAt: Long? = null,
    val proofStatus: ProofStatus = ProofStatus.NONE,
    val proofUri: String? = null,
    val energy: Int = 3,
    val playStyles: Set<PlayStyle> = emptySet(),
    val triggers: Set<Trigger> = emptySet(),
    /** Se SELECTIVE: só se dá bem com este sexo (null = indiferente). */
    val onlyWithSex: Sex? = null,
    /** Se SELECTIVE: portes que estressam o cão. */
    val fearsSizes: Set<SizeClass> = emptySet(),
    val notes: String = "",
    val underReview: Boolean = false,
) : java.io.Serializable {
    val size: SizeClass get() = SizeClass.fromWeight(weightKg)
    val isIntactMale: Boolean get() = sex == Sex.MALE && !neutered
    val isFemaleInHeat: Boolean get() = sex == Sex.FEMALE && !neutered && inHeat
}

data class User(
    val id: String,
    val name: String,
    val neighborhood: String = "",
) : java.io.Serializable

enum class PlaceKind(val label: String) {
    SQUARE("Praça"), LAWN("Gramado de quadra"), TRAIL("Trilha"), DOG_PARK("Cachorródromo"), PARK("Parque")
}

enum class Fencing(val label: String, val detail: String, val safetyRank: Int) {
    DOUBLE_GATE("100% cercado, portão duplo", "Seguro para cães fujões", 4),
    SIMPLE_FENCE("Cercado simples", "Cerca sem antecâmara", 3),
    SEMI_OPEN("Semi-aberto", "Cerca viva ou falhas na cerca", 2),
    OPEN("Totalmente aberto", "Perto de vias — exige guia", 1),
}

enum class Floor(val label: String, val warning: String?) {
    GRASS("Grama natural", null),
    DIRT("Terra batida", "Vira lama em dias de chuva"),
    SAND("Areia", null),
    CONCRETE("Cimento/Asfalto", "Piso quente entre 10h e 16h — risco de queimar os coxins"),
}

enum class Amenity(val label: String) {
    NIGHT_LIGHT("Iluminação noturna"),
    WATER("Bebedouro para cães"),
    SHADED_BENCH("Bancos na sombra"),
    DOG_BINS("Lixeiras exclusivas"),
    POOP_BAGS("Saquinhos cata-caca"),
}

data class Place(
    val id: String,
    val name: String,
    val kind: PlaceKind,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 80.0,
    val createdBy: String,
    /** Horas do dia (0-23) com sombra contínua no trajeto/área principal. */
    val shadeHours: Set<Int> = emptySet(),
    /** Ocupação média histórica por hora (0-23) => nº médio de cães. Alimentado por check-ins. */
    val typicalDogsByHour: List<Double> = List(24) { 0.0 },
) : java.io.Serializable

/** Uma avaliação técnica de um usuário. O consenso do local é a moda dos votos. */
data class PlaceReview(
    val placeId: String,
    val userId: String,
    val fencing: Fencing,
    val floor: Floor,
    val amenities: Set<Amenity>,
    val createdAt: Long,
) : java.io.Serializable

enum class HazardType(val label: String, val ttlMs: Long) {
    TALL_GRASS_TICKS("Grama alta com carrapatos", 7 * DAY),
    BROKEN_GLASS("Vidro quebrado no local", 3 * DAY),
    POISON("Veneno/comida estranha no chão", 2 * DAY),
    LOOSE_AGGRESSIVE_DOG("Cachorro agressivo solto sem dono agora", 3 * HOUR),
}

data class Hazard(
    val id: String,
    val placeId: String,
    val type: HazardType,
    val reportedBy: String,
    val createdAt: Long,
    val note: String = "",
    val confirmations: Set<String> = emptySet(),
    val resolvedBy: Set<String> = emptySet(),
) : java.io.Serializable {
    /** Apoio = quem reportou + confirmações. Some quando expira ou quando "já resolvido" empata/supera o apoio. */
    fun isActive(now: Long): Boolean =
        now < createdAt + type.ttlMs && resolvedBy.size < 1 + confirmations.size
}

enum class CheckInSource { MANUAL, GEO }

data class CheckIn(
    val id: String,
    val userId: String,
    val petIds: List<String>,
    val placeId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val source: CheckInSource = CheckInSource.MANUAL,
    val autoClosed: Boolean = false,
) : java.io.Serializable {
    fun isActive(now: Long) = endedAt == null && now >= startedAt
}

/** "Estarei lá das 17h30 às 18h30". Horários em minutos desde a meia-noite do dia [dayStart]. */
data class Schedule(
    val id: String,
    val userId: String,
    val petIds: List<String>,
    val placeId: String,
    val dayStart: Long,
    val fromMin: Int,
    val toMin: Int,
    val joinedScheduleId: String? = null,
) : java.io.Serializable

enum class EventTheme(val label: String) {
    BREED_MEETUP("Encontro de raça"),
    SENIOR_WALK("Caminhada de idosos (ritmo lento)"),
    PUPPY_SOCIAL("Socialização de filhotes"),
    OTHER("Outro"),
}

data class PublicEvent(
    val id: String,
    val placeId: String,
    val creatorId: String,
    val title: String,
    val theme: EventTheme,
    val startsAt: Long,
    val durationMin: Int,
    val description: String = "",
    val attendees: Set<String> = emptySet(),
) : java.io.Serializable

enum class HostAmenity(val label: String) {
    POOL("Piscina liberada"),
    FENCED_LAWN("Gramado cercado"),
    SHADE("Sombra"),
    FRESH_WATER("Água fresca"),
    TOYS_OK("Pode trazer brinquedos"),
    HUMAN_SNACKS("Humanos podem levar cerveja/lanche (racha via Pix)"),
}

/** Filtros de porta travados pelo anfitrião. */
data class EventRequirements(
    val maxWeightKg: Double? = null,
    val allowedSizes: Set<SizeClass> = emptySet(),  // vazio = todos
    val requireApprovedVaccineProof: Boolean = false,
    val forbidIntactMales: Boolean = false,
    val forbidFemalesInHeat: Boolean = true,
    val maxEnergy: Int? = null,
    val allowedAgeGroups: Set<AgeGroup> = emptySet(), // vazio = todas
    val forbidUnderReview: Boolean = true,
) : java.io.Serializable

enum class PrivateVisibility(val label: String) {
    MAP_APPROX("Visível no mapa (só área aproximada)"),
    PACK_ONLY("Invisível no mapa — só convidados da Matilha"),
}

enum class RequestStatus(val label: String) { PENDING("Aguardando anfitrião"), APPROVED("Aprovado"), REJECTED("Recusado"), CANCELLED("Cancelado") }

data class JoinRequest(
    val userId: String,
    val petIds: List<String>,
    val status: RequestStatus,
    val createdAt: Long,
    val message: String = "",
) : java.io.Serializable

data class ChatMessage(val userId: String, val text: String, val at: Long) : java.io.Serializable

data class PrivateEvent(
    val id: String,
    val hostId: String,
    val title: String,
    val description: String,
    val startsAt: Long,
    val durationMin: Int,
    val neighborhood: String,
    /** Endereço exato — só revelado a convidados aprovados. */
    val exactAddress: String,
    val exactLat: Double,
    val exactLon: Double,
    /** Centro do círculo aproximado (deslocado do endereço real). */
    val approxLat: Double,
    val approxLon: Double,
    val approxRadiusM: Double = 900.0,
    val maxDogs: Int,
    val requirements: EventRequirements,
    val amenities: Set<HostAmenity>,
    val visibility: PrivateVisibility,
    val invitedUserIds: Set<String> = emptySet(),
    val requests: List<JoinRequest> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val cancelled: Boolean = false,
) : java.io.Serializable {
    val endsAt: Long get() = startsAt + durationMin * MINUTE
    fun approvedDogCount(): Int = requests.filter { it.status == RequestStatus.APPROVED }.sumOf { it.petIds.size }
    fun requestOf(userId: String): JoinRequest? = requests.lastOrNull { it.userId == userId }
    fun isApproved(userId: String) = userId == hostId || requestOf(userId)?.status == RequestStatus.APPROVED
}

enum class FriendStatus { PENDING_OUT, PENDING_IN, ACCEPTED }

/** Amizade de Matilha entre dois tutores (vinculada aos cães que brincaram juntos). */
data class Friendship(
    val id: String,
    val userA: String,       // quem pediu
    val userB: String,       // quem recebe
    val accepted: Boolean,
    val createdAt: Long,
    val bestFriend: Boolean = false, // marcado por quem vê: alerta de "melhor amigo"
) : java.io.Serializable

/** Regra do Radar de Compatibilidade. */
data class RadarRule(
    val id: String,
    val name: String,
    val minDogs: Int = 2,
    val sizes: Set<SizeClass> = emptySet(),        // vazio = qualquer porte
    val neuteredOnly: Boolean = false,
    val playStyles: Set<PlayStyle> = emptySet(),   // vazio = qualquer estilo; senão precisa ter ao menos um
    val radiusM: Double = 2000.0,
    val fromMin: Int = 17 * 60,
    val toMin: Int = 20 * 60,
    val enabled: Boolean = true,
) : java.io.Serializable

/** Resposta à "pergunta invisível": o perfil do cão condiz com a realidade? */
data class TrustFeedback(
    val id: String,
    val fromUserId: String,
    val aboutPetId: String,
    val matches: Boolean,
    val issue: String = "",
    val context: String,   // ex.: "Evento: Tarde de piscina" / "Praça X"
    val at: Long,
) : java.io.Serializable

/** Pergunta pendente para o usuário responder após um encontro. */
data class TrustPrompt(
    val id: String,
    val aboutPetId: String,
    val context: String,
    val createdAt: Long,
) : java.io.Serializable

data class WalkFeedback(
    val id: String,
    val userId: String,
    val placeId: String,
    val allGood: Boolean,
    val note: String,
    val at: Long,
) : java.io.Serializable

data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val at: Long,
    val placeId: String? = null,
    val eventId: String? = null,
    val read: Boolean = false,
) : java.io.Serializable

data class AlertSettings(
    val bestFriendAlerts: Boolean = true,
    val bestFriendRadiusM: Double = 1500.0,
    val friendScheduleAlerts: Boolean = true,
    val autoCheckout: Boolean = true,
    val hazardAlertsNearby: Boolean = true,
) : java.io.Serializable
