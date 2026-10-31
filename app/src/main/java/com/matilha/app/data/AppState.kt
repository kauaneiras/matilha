package com.matilha.app.data

import com.matilha.app.domain.*

/** Estado completo do app. Imutável: toda alteração gera uma nova cópia (fácil de testar e persistir). */
data class AppState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val meId: String,
    val onboarded: Boolean = false,
    val users: List<User> = emptyList(),
    val pets: List<Pet> = emptyList(),
    val places: List<Place> = emptyList(),
    val reviews: List<PlaceReview> = emptyList(),
    val hazards: List<Hazard> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val publicEvents: List<PublicEvent> = emptyList(),
    val privateEvents: List<PrivateEvent> = emptyList(),
    val friendships: List<Friendship> = emptyList(),
    val radarRules: List<RadarRule> = emptyList(),
    val trustFeedback: List<TrustFeedback> = emptyList(),
    val trustPrompts: List<TrustPrompt> = emptyList(),
    val walkFeedback: List<WalkFeedback> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val alertSettings: AlertSettings = AlertSettings(),
    val myLat: Double? = null,
    val myLon: Double? = null,
    val myLocationAt: Long? = null,
    /** Momento em que o celular saiu do raio (local + 150 m) do check-in ativo. */
    val outsideSince: Long? = null,
    val radarLastFired: Map<String, Long> = emptyMap(),
    val alertedKeys: Set<String> = emptySet(),
    /** IDs de usuários simulados (dados de demonstração da comunidade). */
    val simulatedUserIds: Set<String> = emptySet(),
) : java.io.Serializable {

    val me: User get() = users.first { it.id == meId }
    val myPets: List<Pet> get() = pets.filter { it.ownerId == meId }

    fun user(id: String) = users.firstOrNull { it.id == id }
    fun pet(id: String) = pets.firstOrNull { it.id == id }
    fun place(id: String) = places.firstOrNull { it.id == id }
    fun petsOf(userId: String) = pets.filter { it.ownerId == userId }

    fun activeCheckIns(now: Long) = checkIns.filter { it.isActive(now) }
    fun activeAt(placeId: String, now: Long) = activeCheckIns(now).filter { it.placeId == placeId }
    fun presentPets(placeId: String, now: Long): List<Pet> =
        activeAt(placeId, now).flatMap { c -> c.petIds.mapNotNull { pet(it) } }.distinctBy { it.id }

    fun myActiveCheckIn(now: Long) = activeCheckIns(now).firstOrNull { it.userId == meId }

    fun activeHazards(placeId: String, now: Long) = hazards.filter { it.placeId == placeId && it.isActive(now) }

    fun friendshipWith(userId: String) = friendships.firstOrNull {
        (it.userA == meId && it.userB == userId) || (it.userB == meId && it.userA == userId)
    }

    fun friendStatus(userId: String): FriendStatus? {
        val f = friendshipWith(userId) ?: return null
        return when {
            f.accepted -> FriendStatus.ACCEPTED
            f.userA == meId -> FriendStatus.PENDING_OUT
            else -> FriendStatus.PENDING_IN
        }
    }

    fun friendIds(): Set<String> = friendships.filter { it.accepted && (it.userA == meId || it.userB == meId) }
        .map { if (it.userA == meId) it.userB else it.userA }.toSet()

    fun isBestFriend(userId: String) = friendshipWith(userId)?.let { it.accepted && it.bestFriend } == true

    fun distanceToMe(lat: Double, lon: Double): Double? =
        if (myLat == null || myLon == null) null else Geo.distanceM(myLat, myLon, lat, lon)

    fun petNames(ids: List<String>) = ids.mapNotNull { pet(it)?.name }.joinToString(", ")

    fun unreadNotifications() = notifications.count { !it.read }

    companion object { const val SCHEMA_VERSION = 1 }
}
