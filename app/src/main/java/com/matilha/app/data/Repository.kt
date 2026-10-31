package com.matilha.app.data

import com.matilha.app.domain.*
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Calendar
import java.util.UUID

/** Resultado de uma ação do usuário: sucesso ou mensagem de erro legível. */
sealed class Result<out T> {
    data class Ok<T>(val value: T) : Result<T>()
    data class Err(val message: String) : Result<Nothing>()
}

/** Persistência atômica (arquivo temporário + rename) com cópia de segurança. */
class StateStore(private val file: File) {
    fun load(): AppState? {
        for (f in listOf(file, File(file.path + ".bak"))) {
            if (!f.exists()) continue
            try {
                ObjectInputStream(f.inputStream().buffered()).use { ins ->
                    val s = ins.readObject() as AppState
                    if (s.schemaVersion == AppState.SCHEMA_VERSION) return s
                }
            } catch (_: Exception) { /* tenta o backup */ }
        }
        return null
    }

    fun save(state: AppState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        ObjectOutputStream(tmp.outputStream().buffered()).use { it.writeObject(state) }
        if (file.exists()) file.copyTo(File(file.path + ".bak"), overwrite = true)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}

/**
 * Fonte única de verdade. Toda mutação passa por [update], que persiste e notifica observadores.
 * No MVP os dados da comunidade são locais/simulados ([CommunitySimulator]); a troca por um backend
 * real acontece aqui, sem afetar telas e regras.
 */
class Repository(
    private val store: StateStore?,
    val clock: () -> Long = System::currentTimeMillis,
    initial: AppState? = null,
) {
    @Volatile var state: AppState = initial ?: store?.load() ?: Seed.create(clock())
        private set

    private val listeners = mutableListOf<(AppState) -> Unit>()
    private val lock = Any()

    fun observe(l: (AppState) -> Unit) { synchronized(listeners) { if (l !in listeners) listeners += l } }
    fun unobserve(l: (AppState) -> Unit) { synchronized(listeners) { listeners -= l } }

    fun update(block: (AppState) -> AppState): AppState {
        val s = synchronized(lock) {
            val n = block(state)
            if (n !== state) { state = n; try { store?.save(n) } catch (_: Exception) {} }
            n
        }
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { it(s) }
        return s
    }

    fun now() = clock()
    private fun id() = UUID.randomUUID().toString()

    // ---------------------------------------------------------------- Perfil / Pets

    fun completeOnboarding(tutorName: String, neighborhood: String): Result<Unit> {
        if (tutorName.isBlank()) return Result.Err("Informe seu nome.")
        if (state.myPets.isEmpty()) return Result.Err("Cadastre pelo menos um pet.")
        update { s ->
            s.copy(onboarded = true, users = s.users.map { if (it.id == s.meId) it.copy(name = tutorName.trim(), neighborhood = neighborhood.trim()) else it })
        }
        return Result.Ok(Unit)
    }

    fun updateMe(name: String, neighborhood: String): Result<Unit> {
        if (name.isBlank()) return Result.Err("Informe seu nome.")
        update { s -> s.copy(users = s.users.map { if (it.id == s.meId) it.copy(name = name.trim(), neighborhood = neighborhood.trim()) else it }) }
        return Result.Ok(Unit)
    }

    fun validatePet(p: Pet): String? = when {
        p.name.isBlank() -> "Informe o nome do pet."
        p.name.length > 30 -> "Nome muito longo (máx. 30 caracteres)."
        p.breed.isBlank() -> "Informe a raça (ou SRD)."
        p.weightKg.isNaN() || p.weightKg <= 0.3 || p.weightKg > 120 -> "Informe um peso entre 0,3 e 120 kg."
        p.energy !in 1..5 -> "Energia deve ser de 1 a 5."
        Trigger.SELECTIVE in p.triggers && p.onlyWithSex == null && p.fearsSizes.isEmpty() ->
            "Em \"Seletivo\", diga com quais cães ele não se dá bem (sexo ou porte)."
        p.vaccines.any { it.appliedAt > clock() + MINUTE } -> "Data de vacina no futuro."
        else -> null
    }

    /** Cria ou edita um pet do usuário. Pets em revisão não podem limpar a revisão sozinhos. */
    fun savePet(input: Pet): Result<Pet> {
        val cleaned = input.copy(
            name = input.name.trim(), breed = input.breed.trim().ifBlank { "SRD" },
            inHeat = input.sex == Sex.FEMALE && !input.neutered && input.inHeat,
            onlyWithSex = if (Trigger.SELECTIVE in input.triggers) input.onlyWithSex else null,
            fearsSizes = if (Trigger.SELECTIVE in input.triggers) input.fearsSizes else emptySet(),
            ownerId = state.meId,
        )
        validatePet(cleaned)?.let { return Result.Err(it) }
        val existing = state.pet(cleaned.id)
        if (existing != null && existing.ownerId != state.meId) return Result.Err("Este pet não é seu.")
        // Qualquer alteração de vacinas/comprovante reavalia o selo.
        val proofed = if (cleaned.proofUri.isNullOrBlank()) cleaned.copy(proofStatus = ProofStatus.NONE)
        else cleaned.copy(proofStatus = Vaccination.evaluateProof(cleaned, clock()).first)
        val final = proofed.copy(underReview = existing?.underReview ?: false)
        update { s ->
            if (existing == null) s.copy(pets = s.pets + final) else s.copy(pets = s.pets.map { if (it.id == final.id) final else it })
        }
        return Result.Ok(final)
    }

    fun newPetId() = id()

    fun deletePet(petId: String): Result<Unit> {
        val s = state
        val p = s.pet(petId) ?: return Result.Err("Pet não encontrado.")
        if (p.ownerId != s.meId) return Result.Err("Este pet não é seu.")
        if (s.myPets.size <= 1 && s.onboarded) return Result.Err("Você precisa ter ao menos um pet cadastrado.")
        val now = clock()
        if (s.myActiveCheckIn(now)?.petIds?.contains(petId) == true) return Result.Err("Faça check-out antes de remover este pet.")
        update { st ->
            st.copy(
                pets = st.pets.filterNot { it.id == petId },
                schedules = st.schedules.map { it.copy(petIds = it.petIds - petId) }.filter { it.petIds.isNotEmpty() },
                privateEvents = st.privateEvents.map { e -> e.copy(requests = e.requests.map { r -> r.copy(petIds = r.petIds - petId) }.filter { it.petIds.isNotEmpty() }) },
            )
        }
        return Result.Ok(Unit)
    }

    // ---------------------------------------------------------------- Locais

    fun addPlace(name: String, kind: PlaceKind, lat: Double, lon: Double, fencing: Fencing, floor: Floor, amenities: Set<Amenity>, shadeHours: Set<Int>): Result<Place> {
        if (name.isBlank()) return Result.Err("Dê um nome ao local.")
        if (name.trim().length < 3) return Result.Err("Nome muito curto.")
        val near = state.places.firstOrNull { Geo.distanceM(it.lat, it.lon, lat, lon) < 40 }
        if (near != null) return Result.Err("Já existe \"${near.name}\" neste ponto. Avalie-o em vez de criar outro.")
        val now = clock()
        val p = Place(id(), name.trim(), kind, lat, lon, createdBy = state.meId, shadeHours = shadeHours)
        update { s -> s.copy(places = s.places + p, reviews = s.reviews + PlaceReview(p.id, s.meId, fencing, floor, amenities, now)) }
        return Result.Ok(p)
    }

    fun reviewPlace(placeId: String, fencing: Fencing, floor: Floor, amenities: Set<Amenity>, shadeHours: Set<Int>?): Result<Unit> {
        if (state.place(placeId) == null) return Result.Err("Local não encontrado.")
        val now = clock()
        update { s ->
            s.copy(
                reviews = s.reviews.filterNot { it.placeId == placeId && it.userId == s.meId } + PlaceReview(placeId, s.meId, fencing, floor, amenities, now),
                places = if (shadeHours == null) s.places else s.places.map { if (it.id == placeId) it.copy(shadeHours = shadeHours) else it },
            )
        }
        return Result.Ok(Unit)
    }

    fun myReview(placeId: String) = state.reviews.firstOrNull { it.placeId == placeId && it.userId == state.meId }

    fun reportHazard(placeId: String, type: HazardType, note: String): Result<Hazard> {
        val now = clock()
        val dup = state.activeHazards(placeId, now).firstOrNull { it.type == type }
        if (dup != null) {
            confirmHazard(dup.id)
            return Result.Ok(state.hazards.first { it.id == dup.id })
        }
        val h = Hazard(id(), placeId, type, state.meId, now, note.trim().take(200))
        update { s -> s.copy(hazards = s.hazards + h) }
        return Result.Ok(h)
    }

    fun confirmHazard(hazardId: String) = update { s ->
        s.copy(hazards = s.hazards.map {
            if (it.id == hazardId && it.reportedBy != s.meId) it.copy(confirmations = it.confirmations + s.meId, resolvedBy = it.resolvedBy - s.meId) else it
        })
    }

    fun resolveHazard(hazardId: String) = update { s ->
        s.copy(hazards = s.hazards.map {
            if (it.id == hazardId) it.copy(resolvedBy = it.resolvedBy + s.meId, confirmations = it.confirmations - s.meId) else it
        })
    }

    // ---------------------------------------------------------------- Check-in / check-out

    fun checkIn(placeId: String, petIds: List<String>, source: CheckInSource = CheckInSource.MANUAL): Result<CheckIn> {
        val s = state
        val now = clock()
        val place = s.place(placeId) ?: return Result.Err("Local não encontrado.")
        val mine = petIds.filter { id -> s.pet(id)?.ownerId == s.meId }.distinct()
        if (mine.isEmpty()) return Result.Err("Escolha ao menos um pet para o check-in.")
        s.myActiveCheckIn(now)?.let { if (it.placeId == placeId) return Result.Err("Você já está com check-in aqui.") }
        // Um check-in por vez: encerra o anterior (sem pergunta de passeio, foi o próprio usuário que trocou).
        s.myActiveCheckIn(now)?.let { checkOut(auto = false) }
        val c = CheckIn(id(), s.meId, mine, place.id, now, source = source)
        update { st -> st.copy(checkIns = st.checkIns + c, outsideSince = null) }
        return Result.Ok(c)
    }

    /** Encerra o check-in ativo. Gera perguntas de confiança sobre cães que estiveram juntos ≥ 10 min. */
    fun checkOut(auto: Boolean): CheckIn? {
        val now = clock()
        val c = state.myActiveCheckIn(now) ?: return null
        val closed = c.copy(endedAt = now, autoClosed = auto)
        update { s ->
            val overlapping = s.checkIns.filter {
                it.userId != s.meId && it.placeId == c.placeId &&
                    minOf(it.endedAt ?: now, now) - maxOf(it.startedAt, c.startedAt) >= 10 * MINUTE
            }.flatMap { it.petIds }.distinct()
            val placeName = s.place(c.placeId)?.name ?: "local"
            val alreadyAsked = s.trustPrompts.map { it.aboutPetId }.toSet() +
                s.trustFeedback.filter { it.fromUserId == s.meId && now - it.at < 7 * DAY }.map { it.aboutPetId }
            val prompts = overlapping.filter { it !in alreadyAsked }.take(3).map { TrustPrompt(id(), it, "Passeio em $placeName", now) }
            s.copy(checkIns = s.checkIns.map { if (it.id == c.id) closed else it }, outsideSince = null, trustPrompts = s.trustPrompts + prompts)
        }
        return closed
    }

    fun submitWalkFeedback(placeId: String, allGood: Boolean, note: String) {
        val now = clock()
        update { s -> s.copy(walkFeedback = s.walkFeedback + WalkFeedback(id(), s.meId, placeId, allGood, note.trim().take(300), now)) }
    }

    /**
     * Atualiza a localização. Implementa a regra do check-out automático: fora do raio do local + 150 m
     * por mais de 5 minutos encerra o check-in. Retorna o check-in encerrado, se houver.
     */
    fun onLocation(lat: Double, lon: Double): CheckIn? {
        val now = clock()
        var toClose = false
        update { s ->
            var n = s.copy(myLat = lat, myLon = lon, myLocationAt = now)
            val c = s.myActiveCheckIn(now)
            if (c == null || !s.alertSettings.autoCheckout) return@update n.copy(outsideSince = null)
            val place = s.place(c.placeId) ?: return@update n
            val d = Geo.distanceM(lat, lon, place.lat, place.lon)
            if (d <= place.radiusM + AUTO_CHECKOUT_MARGIN_M) {
                n = n.copy(outsideSince = null)
            } else {
                val since = s.outsideSince ?: now
                n = n.copy(outsideSince = since)
                if (now - since >= AUTO_CHECKOUT_DELAY_MS) toClose = true
            }
            n
        }
        return if (toClose) checkOut(auto = true) else null
    }

    /** Chamado pela cerca virtual (geofence) do sistema: saída/entrada no raio do local. */
    fun onGeofenceTransition(entering: Boolean) {
        val now = clock()
        update { s ->
            if (s.myActiveCheckIn(now) == null) s.copy(outsideSince = null)
            else if (entering) s.copy(outsideSince = null)
            else s.copy(outsideSince = s.outsideSince ?: now)
        }
    }

    /** Confirmação 5 min após a saída: se ainda estiver fora, encerra. */
    fun confirmGeofenceExit(): CheckIn? {
        val now = clock()
        val since = state.outsideSince ?: return null
        if (state.myActiveCheckIn(now) == null || !state.alertSettings.autoCheckout) return null
        return if (now - since >= AUTO_CHECKOUT_DELAY_MS) checkOut(auto = true) else null
    }

    /** Rede de segurança: check-in esquecido por mais de [MAX_CHECKIN_MS] é encerrado. */
    fun expireStaleCheckIns(): CheckIn? {
        val now = clock()
        val c = state.myActiveCheckIn(now) ?: return null
        return if (now - c.startedAt > MAX_CHECKIN_MS) checkOut(auto = true) else null
    }

    // ---------------------------------------------------------------- Agenda

    fun schedule(placeId: String, dayStart: Long, fromMin: Int, toMin: Int, petIds: List<String>, joinedScheduleId: String? = null): Result<Schedule> {
        ScheduleRules.validate(fromMin, toMin)?.let { return Result.Err(it) }
        val s = state
        val now = clock()
        if (s.place(placeId) == null) return Result.Err("Local não encontrado.")
        val mine = petIds.filter { s.pet(it)?.ownerId == s.meId }
        if (mine.isEmpty()) return Result.Err("Escolha ao menos um pet.")
        if (dayStart + toMin * MINUTE <= now) return Result.Err("Esse horário já passou.")
        val conflict = s.schedules.firstOrNull { it.userId == s.meId && it.dayStart == dayStart && ScheduleRules.overlaps(it.fromMin, it.toMin, fromMin, toMin) }
        if (conflict != null) {
            val where = s.place(conflict.placeId)?.name ?: "outro local"
            return Result.Err("Você já marcou ${ScheduleRules.label(conflict.fromMin)}–${ScheduleRules.label(conflict.toMin)} em $where. Cancele antes.")
        }
        val sc = Schedule(id(), s.meId, mine, placeId, dayStart, fromMin, toMin, joinedScheduleId)
        update { st -> st.copy(schedules = st.schedules + sc) }
        return Result.Ok(sc)
    }

    fun joinSchedule(other: Schedule, petIds: List<String>): Result<Schedule> {
        if (other.userId == state.meId) return Result.Err("Esse agendamento é seu.")
        return schedule(other.placeId, other.dayStart, other.fromMin, other.toMin, petIds, other.id)
    }

    fun cancelSchedule(id: String) = update { s -> s.copy(schedules = s.schedules.filterNot { it.id == id && it.userId == s.meId }) }

    fun schedulesFor(placeId: String, dayStart: Long) =
        state.schedules.filter { it.placeId == placeId && it.dayStart == dayStart }.sortedBy { it.fromMin }

    // ---------------------------------------------------------------- Eventos públicos

    fun createPublicEvent(placeId: String, title: String, theme: EventTheme, startsAt: Long, durationMin: Int, description: String): Result<PublicEvent> {
        val now = clock()
        if (state.place(placeId) == null) return Result.Err("Escolha um local.")
        if (title.isBlank() || title.trim().length < 4) return Result.Err("Dê um título ao evento (mín. 4 letras).")
        if (startsAt < now - 5 * MINUTE) return Result.Err("A data/hora do evento já passou.")
        if (startsAt > now + 90 * DAY) return Result.Err("Crie eventos com até 90 dias de antecedência.")
        if (durationMin !in 15..480) return Result.Err("Duração entre 15 min e 8 h.")
        val e = PublicEvent(id(), placeId, state.meId, title.trim().take(80), theme, startsAt, durationMin, description.trim().take(500), setOf(state.meId))
        update { s -> s.copy(publicEvents = s.publicEvents + e) }
        return Result.Ok(e)
    }

    fun togglePublicAttendance(eventId: String) = update { s ->
        s.copy(publicEvents = s.publicEvents.map { e ->
            if (e.id != eventId || e.creatorId == s.meId) e
            else if (s.meId in e.attendees) e.copy(attendees = e.attendees - s.meId) else e.copy(attendees = e.attendees + s.meId)
        })
    }

    fun cancelPublicEvent(eventId: String) = update { s -> s.copy(publicEvents = s.publicEvents.filterNot { it.id == eventId && it.creatorId == s.meId }) }

    // ---------------------------------------------------------------- Eventos privados

    fun createPrivateEvent(
        title: String, description: String, startsAt: Long, durationMin: Int,
        neighborhood: String, address: String, lat: Double, lon: Double,
        maxDogs: Int, req: EventRequirements, amenities: Set<HostAmenity>,
        visibility: PrivateVisibility, invited: Set<String>,
    ): Result<PrivateEvent> {
        val now = clock()
        if (title.isBlank() || title.trim().length < 4) return Result.Err("Dê um título ao rolê (mín. 4 letras).")
        if (neighborhood.isBlank()) return Result.Err("Informe o bairro (é o que aparece no mapa).")
        if (address.isBlank() || address.trim().length < 6) return Result.Err("Informe o endereço completo (só aprovados verão).")
        if (startsAt < now) return Result.Err("A data/hora do rolê já passou.")
        if (startsAt > now + 90 * DAY) return Result.Err("Crie rolês com até 90 dias de antecedência.")
        if (durationMin !in 30..720) return Result.Err("Duração entre 30 min e 12 h.")
        if (maxDogs !in 1..30) return Result.Err("Limite de cães entre 1 e 30.")
        val friends = state.friendIds()
        val inv = invited.filter { it in friends }.toSet()
        if (visibility == PrivateVisibility.PACK_ONLY && inv.isEmpty()) return Result.Err("Rolê invisível precisa de pelo menos um convidado da sua Matilha.")
        val eid = id()
        val (aLat, aLon) = Geo.obfuscate(lat, lon, eid.hashCode().toLong())
        val e = PrivateEvent(
            eid, state.meId, title.trim().take(80), description.trim().take(800), startsAt, durationMin,
            neighborhood.trim(), address.trim(), lat, lon, aLat, aLon, maxDogs = maxDogs, requirements = req,
            amenities = amenities, visibility = visibility, invitedUserIds = inv,
        )
        update { s -> s.copy(privateEvents = s.privateEvents + e) }
        return Result.Ok(e)
    }

    /** Pode ver o evento? Público no mapa: todos. Invisível: anfitrião e convidados. */
    fun canSee(e: PrivateEvent): Boolean = e.hostId == state.meId || e.visibility == PrivateVisibility.MAP_APPROX || state.meId in e.invitedUserIds

    fun eligibility(e: PrivateEvent, now: Long = clock()) = state.myPets.map { DoorFilter.check(it, e.requirements, now) }

    fun requestToJoin(eventId: String, petIds: List<String>, message: String): Result<Unit> {
        val s = state
        val now = clock()
        val e = s.privateEvents.firstOrNull { it.id == eventId } ?: return Result.Err("Rolê não encontrado.")
        if (!canSee(e)) return Result.Err("Rolê não disponível.")
        if (e.cancelled) return Result.Err("Este rolê foi cancelado.")
        if (e.hostId == s.meId) return Result.Err("Você é o anfitrião.")
        if (e.endsAt < now) return Result.Err("Este rolê já terminou.")
        val prev = e.requestOf(s.meId)
        if (prev != null && prev.status in setOf(RequestStatus.PENDING, RequestStatus.APPROVED)) return Result.Err("Você já pediu para participar.")
        if (prev?.status == RequestStatus.REJECTED) return Result.Err("O anfitrião recusou seu pedido.")
        if (petIds.isEmpty()) return Result.Err("Escolha quais pets vão.")
        val pets = petIds.mapNotNull { s.pet(it) }.filter { it.ownerId == s.meId }
        val blocked = pets.map { DoorFilter.check(it, e.requirements, now) }.filterNot { it.ok }
        if (blocked.isNotEmpty()) return Result.Err("${blocked.first().pet.name} não atende: ${blocked.first().reasons.first()}.")
        if (e.approvedDogCount() + pets.size > e.maxDogs) return Result.Err("Não há vagas suficientes (${e.maxDogs - e.approvedDogCount()} restantes).")
        update { st -> st.copy(privateEvents = st.privateEvents.map {
            if (it.id == eventId) it.copy(requests = it.requests.filterNot { r -> r.userId == st.meId } + JoinRequest(st.meId, pets.map { p -> p.id }, RequestStatus.PENDING, now, message.trim().take(300))) else it
        }) }
        return Result.Ok(Unit)
    }

    fun cancelRequest(eventId: String) = update { s ->
        s.copy(privateEvents = s.privateEvents.map { e ->
            if (e.id != eventId) e else e.copy(requests = e.requests.map { if (it.userId == s.meId) it.copy(status = RequestStatus.CANCELLED) else it })
        })
    }

    fun decideRequest(eventId: String, userId: String, approve: Boolean): Result<Unit> {
        val s = state
        val e = s.privateEvents.firstOrNull { it.id == eventId } ?: return Result.Err("Rolê não encontrado.")
        if (e.hostId != s.meId) return Result.Err("Só o anfitrião decide.")
        val r = e.requestOf(userId) ?: return Result.Err("Pedido não encontrado.")
        if (approve && e.approvedDogCount() + r.petIds.size > e.maxDogs) return Result.Err("Sem vagas: aumente o limite ou recuse.")
        update { st -> st.copy(privateEvents = st.privateEvents.map { ev ->
            if (ev.id != eventId) ev else ev.copy(requests = ev.requests.map {
                if (it.userId == userId) it.copy(status = if (approve) RequestStatus.APPROVED else RequestStatus.REJECTED) else it
            })
        }) }
        return Result.Ok(Unit)
    }

    fun sendChat(eventId: String, text: String): Result<Unit> {
        val t = text.trim()
        if (t.isEmpty()) return Result.Err("Mensagem vazia.")
        val e = state.privateEvents.firstOrNull { it.id == eventId } ?: return Result.Err("Rolê não encontrado.")
        if (!e.isApproved(state.meId)) return Result.Err("O chat é liberado após o anfitrião aprovar.")
        val now = clock()
        update { s -> s.copy(privateEvents = s.privateEvents.map { if (it.id == eventId) it.copy(chat = it.chat + ChatMessage(s.meId, t.take(500), now)) else it }) }
        return Result.Ok(Unit)
    }

    fun cancelPrivateEvent(eventId: String) = update { s ->
        s.copy(privateEvents = s.privateEvents.map { if (it.id == eventId && it.hostId == s.meId) it.copy(cancelled = true) else it })
    }

    /** Após o fim de rolês em que participei, gera perguntas de confiança sobre os outros cães. */
    fun promptsAfterPrivateEvents() {
        val now = clock()
        update { s ->
            val asked = s.trustPrompts.map { it.aboutPetId + "|" + it.context }.toSet() +
                s.trustFeedback.filter { it.fromUserId == s.meId }.map { it.aboutPetId + "|" + it.context }
            val prompts = s.privateEvents.filter { !it.cancelled && it.endsAt <= now && now - it.endsAt < 3 * DAY && it.isApproved(s.meId) }
                .flatMap { e ->
                    val ctx = "Rolê: ${e.title}"
                    val petIds = e.requests.filter { it.status == RequestStatus.APPROVED && it.userId != s.meId }.flatMap { it.petIds } +
                        (if (e.hostId != s.meId) s.petsOf(e.hostId).map { it.id } else emptyList())
                    petIds.distinct().filter { "$it|$ctx" !in asked && s.pet(it) != null }.map { TrustPrompt(id(), it, ctx, now) }
                }
            if (prompts.isEmpty()) s else s.copy(trustPrompts = s.trustPrompts + prompts)
        }
    }

    // ---------------------------------------------------------------- Matilha

    fun requestFriend(userId: String): Result<Unit> {
        val s = state
        if (userId == s.meId) return Result.Err("Esse é você.")
        if (s.user(userId) == null) return Result.Err("Tutor não encontrado.")
        when (s.friendStatus(userId)) {
            FriendStatus.ACCEPTED -> return Result.Err("Já está na sua Matilha.")
            FriendStatus.PENDING_OUT -> return Result.Err("Pedido já enviado.")
            FriendStatus.PENDING_IN -> return answerFriend(userId, true)
            null -> {}
        }
        update { st -> st.copy(friendships = st.friendships + Friendship(id(), st.meId, userId, false, clock())) }
        return Result.Ok(Unit)
    }

    fun answerFriend(userId: String, accept: Boolean): Result<Unit> {
        val f = state.friendshipWith(userId) ?: return Result.Err("Pedido não encontrado.")
        update { s ->
            if (accept) s.copy(friendships = s.friendships.map { if (it.id == f.id) it.copy(accepted = true) else it })
            else s.copy(friendships = s.friendships.filterNot { it.id == f.id })
        }
        return Result.Ok(Unit)
    }

    fun removeFriend(userId: String) = update { s ->
        val f = s.friendshipWith(userId)
        if (f == null) s else s.copy(friendships = s.friendships.filterNot { it.id == f.id })
    }

    fun setBestFriend(userId: String, best: Boolean) = update { s ->
        s.copy(friendships = s.friendships.map {
            if (it.accepted && ((it.userA == s.meId && it.userB == userId) || (it.userB == s.meId && it.userA == userId))) it.copy(bestFriend = best) else it
        })
    }

    // ---------------------------------------------------------------- Confiança

    fun answerTrust(promptId: String, matches: Boolean, issue: String): Result<Unit> {
        val p = state.trustPrompts.firstOrNull { it.id == promptId } ?: return Result.Err("Pergunta não encontrada.")
        val r = giveTrustFeedback(p.aboutPetId, matches, issue, p.context)
        // Só remove a pergunta se a resposta foi aceita.
        if (r is Result.Ok) update { s -> s.copy(trustPrompts = s.trustPrompts.filterNot { it.id == promptId }) }
        return r
    }

    fun dismissTrust(promptId: String) = update { s -> s.copy(trustPrompts = s.trustPrompts.filterNot { it.id == promptId }) }

    fun giveTrustFeedback(petId: String, matches: Boolean, issue: String, context: String): Result<Unit> {
        val s = state
        val pet = s.pet(petId) ?: return Result.Err("Pet não encontrado.")
        if (pet.ownerId == s.meId) return Result.Err("Você não pode avaliar o próprio pet.")
        if (!matches && issue.isBlank()) return Result.Err("Conte rapidamente o que não condiz (ajuda a revisão).")
        val now = clock()
        update { st ->
            val fb = st.trustFeedback.filterNot { it.fromUserId == st.meId && it.aboutPetId == petId } +
                TrustFeedback(id(), st.meId, petId, matches, issue.trim().take(300), context, now)
            val flag = Trust.shouldFlag(petId, fb)
            st.copy(trustFeedback = fb, pets = st.pets.map { if (it.id == petId && flag) it.copy(underReview = true) else it })
        }
        return Result.Ok(Unit)
    }

    // ---------------------------------------------------------------- Alertas

    fun saveRadarRule(rule: RadarRule): Result<RadarRule> {
        if (rule.name.isBlank()) return Result.Err("Dê um nome ao radar.")
        if (rule.minDogs !in 1..20) return Result.Err("Quantidade mínima entre 1 e 20 cães.")
        if (rule.toMin <= rule.fromMin) return Result.Err("O horário final precisa ser depois do inicial.")
        if (rule.radiusM !in 200.0..20_000.0) return Result.Err("Raio entre 200 m e 20 km.")
        update { s -> s.copy(radarRules = if (s.radarRules.any { it.id == rule.id }) s.radarRules.map { if (it.id == rule.id) rule else it } else s.radarRules + rule) }
        return Result.Ok(rule)
    }

    fun newRuleId() = id()
    fun deleteRadarRule(id: String) = update { s -> s.copy(radarRules = s.radarRules.filterNot { it.id == id }) }
    fun saveAlertSettings(a: AlertSettings) = update { s -> s.copy(alertSettings = a, outsideSince = if (a.autoCheckout) s.outsideSince else null) }

    fun markNotificationsRead() = update { s -> if (s.notifications.none { !it.read }) s else s.copy(notifications = s.notifications.map { it.copy(read = true) }) }
    fun clearNotifications() = update { s -> s.copy(notifications = emptyList()) }

    /**
     * Avalia todos os gatilhos de notificação e devolve as novas. Cada gatilho dispara uma única vez
     * (chaves em alertedKeys) e o radar respeita intervalo mínimo de [RADAR_COOLDOWN_MS].
     */
    fun evaluateAlerts(): List<AppNotification> {
        val now = clock()
        val out = mutableListOf<AppNotification>()
        update { s ->
            val keys = s.alertedKeys.toMutableSet()
            val radarFired = s.radarLastFired.toMutableMap()
            val myPetNames = s.myPets.map { it.name }
            val myPetLabel = when {
                myPetNames.isEmpty() -> "seu pet"
                s.myPets.size == 1 -> (if (s.myPets[0].sex == Sex.FEMALE) "da sua cachorra " else "do seu cachorro ") + myPetNames[0]
                else -> "dos seus pets"
            }
            val friends = s.friendIds()
            val settings = s.alertSettings

            // 1) Melhor amigo fez check-in perto.
            if (settings.bestFriendAlerts) {
                s.activeCheckIns(now).filter { it.userId in friends && s.isBestFriend(it.userId) && now - it.startedAt < 60 * MINUTE }.forEach { c ->
                    val key = "ci:${c.id}"
                    if (key in keys) return@forEach
                    val place = s.place(c.placeId) ?: return@forEach
                    val d = s.distanceToMe(place.lat, place.lon)
                    if (d != null && d > settings.bestFriendRadiusM) return@forEach
                    keys += key
                    val names = s.petNames(c.petIds)
                    val where = if (d != null) "${place.name}, a ${Geo.formatDistance(d)} de você" else place.name
                    out += AppNotification(id(), "$names chegou!", "O $names (amigo $myPetLabel) acabou de fazer check-in em $where!", now, placeId = place.id)
                }
            }

            // 2) Melhor amigo agendou presença.
            if (settings.friendScheduleAlerts) {
                s.schedules.filter { it.userId in friends && s.isBestFriend(it.userId) && it.dayStart + it.toMin * MINUTE > now }.forEach { sc ->
                    val key = "sc:${sc.id}"
                    if (key in keys) return@forEach
                    keys += key
                    val place = s.place(sc.placeId) ?: return@forEach
                    val names = s.petNames(sc.petIds)
                    val day = if (sc.dayStart == startOfDay(now)) "hoje" else Fmt.dayShort(sc.dayStart)
                    out += AppNotification(id(), "Agenda da Matilha", "O tutor do $names agendou presença em ${place.name} $day às ${ScheduleRules.label(sc.fromMin)}.", now, placeId = place.id)
                }
            }

            // 3) Radar de compatibilidade.
            if (s.myLat != null) {
                val candidates = s.activeCheckIns(now).filter { it.userId != s.meId }.flatMap { c ->
                    val pl = s.place(c.placeId) ?: return@flatMap emptyList()
                    val d = Geo.distanceM(s.myLat, s.myLon!!, pl.lat, pl.lon)
                    c.petIds.mapNotNull { s.pet(it) }.map { it to d }
                }
                s.radarRules.forEach { rule ->
                    val last = radarFired[rule.id] ?: 0L
                    if (now - last < RADAR_COOLDOWN_MS) return@forEach
                    val hit = Radar.evaluate(rule, minuteOfDay(now), candidates) ?: return@forEach
                    radarFired[rule.id] = now
                    out += AppNotification(id(), "Radar: ${rule.name}", "${hit.size} cães compatíveis por perto agora: ${hit.take(4).joinToString { it.name }}${if (hit.size > 4) "…" else ""}.", now)
                }
            }

            // 4) Perigos novos perto de mim.
            if (settings.hazardAlertsNearby && s.myLat != null) {
                s.hazards.filter { it.isActive(now) && it.reportedBy != s.meId && now - it.createdAt < 2 * HOUR }.forEach { h ->
                    val key = "hz:${h.id}"
                    if (key in keys) return@forEach
                    val pl = s.place(h.placeId) ?: return@forEach
                    val d = s.distanceToMe(pl.lat, pl.lon) ?: return@forEach
                    if (d > 2000) return@forEach
                    keys += key
                    out += AppNotification(id(), "Alerta em ${pl.name}", "${h.type.label} (a ${Geo.formatDistance(d)} de você).", now, placeId = pl.id)
                }
            }

            // 5) Pedidos para meus rolês privados.
            s.privateEvents.filter { it.hostId == s.meId && !it.cancelled }.forEach { e ->
                e.requests.filter { it.status == RequestStatus.PENDING }.forEach { r ->
                    val key = "rq:${e.id}:${r.userId}:${r.createdAt}"
                    if (key in keys) return@forEach
                    keys += key
                    out += AppNotification(id(), "Pedido para \"${e.title}\"", "${s.user(r.userId)?.name ?: "Alguém"} quer levar ${s.petNames(r.petIds)}.", now, eventId = e.id)
                }
            }

            // 6) Decisões sobre meus pedidos.
            s.privateEvents.forEach { e ->
                val r = e.requestOf(s.meId) ?: return@forEach
                if (r.status != RequestStatus.APPROVED && r.status != RequestStatus.REJECTED) return@forEach
                val key = "dc:${e.id}:${r.status}"
                if (key in keys) return@forEach
                keys += key
                out += if (r.status == RequestStatus.APPROVED)
                    AppNotification(id(), "Você foi aprovado!", "\"${e.title}\": endereço e chat liberados.", now, eventId = e.id)
                else AppNotification(id(), "Pedido não aprovado", "O anfitrião de \"${e.title}\" não aprovou desta vez.", now, eventId = e.id)
            }

            // 7) Pedidos de Matilha recebidos.
            s.friendships.filter { !it.accepted && it.userB == s.meId }.forEach { f ->
                val key = "fr:${f.id}"
                if (key in keys) return@forEach
                keys += key
                val names = s.petNames(s.petsOf(f.userA).map { it.id })
                out += AppNotification(id(), "Pedido de Matilha", "${s.user(f.userA)?.name ?: "Alguém"} (tutor de $names) quer entrar na sua Matilha.", now)
            }

            if (out.isEmpty() && keys == s.alertedKeys && radarFired == s.radarLastFired) s
            else s.copy(alertedKeys = keys, radarLastFired = radarFired, notifications = (out + s.notifications).take(100))
        }
        return out
    }

    fun addNotification(title: String, body: String, placeId: String? = null): AppNotification {
        val n = AppNotification(id(), title, body, clock(), placeId = placeId)
        update { s -> s.copy(notifications = (listOf(n) + s.notifications).take(100)) }
        return n
    }

    data class PulseResult(val notifications: List<AppNotification>, val autoClosed: CheckIn?)

    /** Ciclo periódico (app aberto: a cada minuto; em segundo plano: JobScheduler ~15 min). */
    fun pulse(): PulseResult {
        val now = clock()
        promptsAfterPrivateEvents()
        update { CommunitySimulator.step(it, now) }
        val closed = confirmGeofenceExit() ?: expireStaleCheckIns()
        return PulseResult(evaluateAlerts(), closed)
    }

    fun minuteOfDay(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    companion object {
        const val AUTO_CHECKOUT_MARGIN_M = 150.0
        const val AUTO_CHECKOUT_DELAY_MS = 5 * MINUTE
        const val MAX_CHECKIN_MS = 4 * HOUR
        const val RADAR_COOLDOWN_MS = 3 * HOUR

        fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun hourOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

        /** Soma dias de calendário (respeita horário de verão, diferente de + n * DAY). */
        fun addDays(dayStart: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, n) }.timeInMillis
    }
}
