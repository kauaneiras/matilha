package com.matilha.app.data

import com.matilha.app.domain.*
import java.util.Random

/**
 * Simula a comunidade (outros tutores) enquanto o MVP não tem servidor. É uma função pura de
 * (estado, horário): presença em praças segue a ocupação típica de cada local por meia hora,
 * anfitriões simulados respondem pedidos, e eventos de demonstração se repetem semanalmente.
 * Nada aqui altera dados criados pelo usuário real.
 */
object CommunitySimulator {
    private const val SLOT = 30 * MINUTE

    fun step(s0: AppState, now: Long): AppState {
        var s = s0
        s = presence(s, now)
        s = dailySchedules(s, now)
        s = rollEvents(s, now)
        s = hostsDecide(s, now)
        s = acceptFriendRequests(s, now)
        s = prune(s, now)
        return s
    }

    private fun presence(s: AppState, now: Long): AppState {
        val slot = now / SLOT
        val hour = Repository.hourOf(now)
        val sim = s.simulatedUserIds
        val pool = mutableMapOf<String, Int>()
        Seed.homes.values.forEach { hs -> hs.forEach { pool[it] = (pool[it] ?: 0) + 1 } }

        // Decide onde cada tutor simulado está nesta meia hora.
        val desired = mutableMapOf<String, Pair<String, List<String>>>() // owner -> (place, pets)
        sim.sorted().forEach { owner ->
            val pets = s.petsOf(owner).filter { Seed.homes.containsKey(it.id) }
            if (pets.isEmpty()) return@forEach
            val homes = pets.flatMap { Seed.homes[it.id].orEmpty() }.distinct()
            val rnd = Random(owner.hashCode() * 31L + slot)
            for ((i, placeId) in homes.withIndex()) {
                val place = s.place(placeId) ?: continue
                val typical = place.typicalDogsByHour.getOrElse(hour) { 0.0 }
                val prob = (typical / (pool[placeId] ?: 1)).coerceAtMost(0.85) * (if (i == 0) 1.0 else 0.5)
                if (rnd.nextDouble() < prob) {
                    val going = pets.filter { placeId in Seed.homes[it.id].orEmpty() }.map { it.id }
                    desired[owner] = placeId to going
                    break
                }
            }
        }

        val checkIns = s.checkIns.toMutableList()
        // Encerra check-ins simulados que não continuam.
        for (i in checkIns.indices) {
            val c = checkIns[i]
            if (c.userId !in sim || !c.isActive(now)) continue
            val d = desired[c.userId]
            if (d == null || d.first != c.placeId) checkIns[i] = c.copy(endedAt = now)
        }
        // Abre os novos.
        desired.forEach { (owner, d) ->
            val active = checkIns.any { it.userId == owner && it.isActive(now) }
            if (!active) {
                val offset = (Random(owner.hashCode() + slot).nextInt(10)) * MINUTE
                val start = minOf(slot * SLOT + offset, now)
                checkIns += CheckIn("sim-ci-$owner-$slot", owner, d.second, d.first, start, source = CheckInSource.GEO)
            }
        }
        return s.copy(checkIns = checkIns)
    }

    /** Ana (tutora do Paco) e Bruno agendam presença no Parcão todo dia. */
    private fun dailySchedules(s: AppState, now: Long): AppState {
        val today = Repository.startOfDay(now)
        val plans = listOf(
            Triple("sim-ana", listOf("pet-paco"), 18 * 60 to 19 * 60),
            Triple("sim-bruno", listOf("pet-duque"), 17 * 60 + 30 to 18 * 60 + 30),
            Triple("sim-joao", listOf("pet-kira"), 7 * 60 to 8 * 60),
        )
        var sch = s.schedules
        plans.forEach { (owner, pets, win) ->
            if (s.user(owner) == null || pets.any { s.pet(it) == null }) return@forEach
            val day = if (now < Fmt.atTime(today, win.second)) today else Repository.addDays(today, 1)
            val id = "sim-sc-$owner-$day"
            if (sch.none { it.id == id }) sch = sch + Schedule(id, owner, pets, "pl-parcao", day, win.first, win.second)
        }
        return if (sch === s.schedules) s else s.copy(schedules = sch)
    }

    /** Eventos de demonstração que já terminaram voltam na semana seguinte. */
    private fun rollEvents(s: AppState, now: Long): AppState {
        val pub = s.publicEvents.map { e ->
            if (e.creatorId !in s.simulatedUserIds) return@map e
            var start = e.startsAt
            while (start + e.durationMin * MINUTE < now) start = Repository.addDays(start, 7)
            if (start == e.startsAt) e else e.copy(startsAt = start, attendees = e.attendees.filter { it in s.simulatedUserIds }.toSet())
        }
        val priv = s.privateEvents.map { e ->
            if (e.hostId !in s.simulatedUserIds) return@map e
            var start = e.startsAt
            while (start + e.durationMin * MINUTE < now) start = Repository.addDays(start, 7)
            if (start == e.startsAt) e else e.copy(startsAt = start, requests = e.requests.filter { it.userId in s.simulatedUserIds }, chat = emptyList())
        }
        return s.copy(publicEvents = pub, privateEvents = priv)
    }

    /** Anfitriões simulados avaliam pedidos do usuário após ~1 minuto (aprovam se houver vaga e o cão atender os filtros). */
    private fun hostsDecide(s: AppState, now: Long): AppState {
        var changed = false
        val events = s.privateEvents.map { e ->
            if (e.hostId !in s.simulatedUserIds || e.cancelled) return@map e
            var ev = e
            e.requests.filter { it.userId == s.meId && it.status == RequestStatus.PENDING && now - it.createdAt >= MINUTE }.forEach { r ->
                val pets = r.petIds.mapNotNull { s.pet(it) }
                val ok = pets.isNotEmpty() && pets.all { DoorFilter.check(it, ev.requirements, now).ok } &&
                    ev.approvedDogCount() + pets.size <= ev.maxDogs
                ev = ev.copy(
                    requests = ev.requests.map { if (it.userId == s.meId) it.copy(status = if (ok) RequestStatus.APPROVED else RequestStatus.REJECTED) else it },
                    chat = if (ok) ev.chat + ChatMessage(e.hostId, "Oi! Aprovei vocês 🐾 O endereço está liberado aqui. Qualquer dúvida, é só chamar.", now) else ev.chat,
                )
                changed = true
            }
            ev
        }
        return if (changed) s.copy(privateEvents = events) else s
    }

    private fun acceptFriendRequests(s: AppState, now: Long): AppState {
        if (s.friendships.none { !it.accepted && it.userA == s.meId && it.userB in s.simulatedUserIds && now - it.createdAt >= MINUTE }) return s
        return s.copy(friendships = s.friendships.map {
            if (!it.accepted && it.userA == s.meId && it.userB in s.simulatedUserIds && now - it.createdAt >= MINUTE) it.copy(accepted = true) else it
        })
    }

    private fun prune(s: AppState, now: Long): AppState {
        val keepCheckIns = s.checkIns.filter { it.endedAt == null || now - it.endedAt < 2 * DAY || it.userId == s.meId && now - it.endedAt < 30 * DAY }
        val today = Repository.startOfDay(now)
        val keepSchedules = s.schedules.filter { it.dayStart >= Repository.addDays(today, -1) }
        val keepHazards = s.hazards.filter { now - it.createdAt < 30 * DAY }
        return if (keepCheckIns.size == s.checkIns.size && keepSchedules.size == s.schedules.size && keepHazards.size == s.hazards.size) s
        else s.copy(checkIns = keepCheckIns, schedules = keepSchedules, hazards = keepHazards)
    }
}
