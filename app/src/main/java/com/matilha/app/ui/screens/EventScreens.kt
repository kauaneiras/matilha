package com.matilha.app.ui.screens

import android.text.InputType
import android.view.View
import com.matilha.app.MainActivity
import com.matilha.app.Screen
import com.matilha.app.Tab
import com.matilha.app.data.AppState
import com.matilha.app.data.Fmt
import com.matilha.app.data.Repository
import com.matilha.app.domain.*
import com.matilha.app.page
import com.matilha.app.ui.*

class EventsScreen(a: MainActivity) : Screen(a) {
    override val title = "Rolês"
    override val tab = Tab.EVENTS
    private var seg = 0

    override fun render(): View = a.page {
        add(a.singleChoice(listOf(0, 1, 2), seg, { listOf("Públicos", "Privados", "Meus rolês")[it] }) { seg = it; a.renderNow(true) })
        when (seg) {
            0 -> {
                add(text("Eventos temáticos em praças e parques, abertos a todos.", TS.SMALL, C.textMuted))
                val evs = s.publicEvents.filter { it.startsAt + it.durationMin * MINUTE > now }.sortedBy { it.startsAt }
                if (evs.isEmpty()) add(a.emptyState("📣", "Nenhum evento público", "Crie o primeiro!"))
                evs.forEach { add(publicEventCard(a, s, it, now)) }
                add(a.button("+ Criar evento público") { a.show(PublicEventFormScreen(a, null)) })
            }
            1 -> {
                add(text("Quintais e espaços 100% controlados. O endereço só aparece após o anfitrião aprovar.", TS.SMALL, C.textMuted))
                val evs = s.privateEvents.filter { !it.cancelled && it.endsAt > now && it.hostId != s.meId && repo.canSee(it) }.sortedBy { it.startsAt }
                if (evs.isEmpty()) add(a.emptyState("🏡", "Nenhum rolê privado disponível", "Rolês invisíveis aparecem aqui quando alguém da sua Matilha te convidar."))
                evs.forEach { add(privateEventCard(a, s, it, now)) }
                add(a.button("+ Abrir meu quintal") { a.show(PrivateEventFormScreen(a)) })
            }
            else -> {
                val hosting = s.privateEvents.filter { it.hostId == s.meId }.sortedByDescending { it.startsAt }
                val going = s.privateEvents.filter { it.hostId != s.meId && it.requestOf(s.meId)?.status.let { st -> st == RequestStatus.PENDING || st == RequestStatus.APPROVED } }
                val pub = s.publicEvents.filter { s.meId in it.attendees && it.startsAt + it.durationMin * MINUTE > now }
                val mySched = s.schedules.filter { it.userId == s.meId && it.dayStart + it.toMin * MINUTE > now }.sortedBy { it.dayStart * 10000 + it.fromMin }
                if (hosting.isEmpty() && going.isEmpty() && pub.isEmpty() && mySched.isEmpty()) add(a.emptyState("🗓", "Nada marcado ainda", "Agende presença em uma praça, confirme um evento ou abra seu quintal."))
                if (hosting.isNotEmpty()) { add(section("Sou anfitrião")); hosting.forEach { add(privateEventCard(a, s, it, now)) } }
                if (going.isNotEmpty()) { add(section("Pedidos e convites")); going.forEach { add(privateEventCard(a, s, it, now)) } }
                if (pub.isNotEmpty()) { add(section("Eventos públicos confirmados")); pub.forEach { add(publicEventCard(a, s, it, now)) } }
                if (mySched.isNotEmpty()) {
                    add(section("Presenças agendadas"))
                    mySched.forEach { sc ->
                        add(a.clickableCard({ a.show(PlaceScreen(a, sc.placeId)) }) {
                            add(text(s.place(sc.placeId)?.name ?: "Local", TS.H3))
                            add(text("${Fmt.relativeDay(Fmt.atTime(sc.dayStart, sc.fromMin), now)}–${com.matilha.app.domain.ScheduleRules.label(sc.toMin)} · ${s.petNames(sc.petIds)}", TS.SMALL, C.textMuted))
                        })
                    }
                }
            }
        }
    }
}

fun privateEventCard(a: MainActivity, s: AppState, e: PrivateEvent, now: Long): View = a.clickableCard({ a.show(PrivateEventScreen(a, e.id)) }) {
    add(a.text("🏡 ${e.title}", TS.H3))
    add(a.text("${e.neighborhood} · ${Fmt.relativeDay(e.startsAt, now)} · ${e.approvedDogCount()}/${e.maxDogs} cães", TS.SMALL, C.textMuted))
    add(a.hbox(6) {
        if (e.cancelled) add(a.badge("Cancelado", Tone.DANGER))
        if (e.visibility == PrivateVisibility.PACK_ONLY) add(a.badge("Só Matilha", Tone.PRIMARY))
        if (e.hostId == s.meId) {
            val pend = e.requests.count { it.status == RequestStatus.PENDING }
            add(a.badge(if (pend > 0) Fmt.plural(pend, "pedido", "pedidos") else "Anfitrião", if (pend > 0) Tone.WARN else Tone.NEUTRAL))
        } else e.requestOf(s.meId)?.let { r -> add(a.badge(r.status.label, when (r.status) { RequestStatus.APPROVED -> Tone.OK; RequestStatus.REJECTED -> Tone.DANGER; else -> Tone.INFO })) }
        if (e.hostId != s.meId && e.requestOf(s.meId) == null) {
            val ok = s.myPets.any { DoorFilter.check(it, e.requirements, now).ok }
            add(a.badge(if (ok) "Seu pet atende" else "Seu pet não atende", if (ok) Tone.OK else Tone.NEUTRAL))
        }
    })
}

class PrivateEventScreen(a: MainActivity, private val eventId: String) : Screen(a) {
    override val title = "Rolê privado"

    override fun render(): View = a.page {
        val e = s.privateEvents.firstOrNull { it.id == eventId }
        if (e == null || !repo.canSee(e)) { add(a.emptyState("🏡", "Rolê indisponível", "Ele pode ter sido removido ou é exclusivo de outra Matilha.")); return@page }
        val host = e.hostId == s.meId
        val approved = e.isApproved(s.meId)
        add(text(e.title, TS.H1))
        add(a.hbox(6) {
            if (e.cancelled) add(badge("Cancelado pelo anfitrião", Tone.DANGER))
            add(badge(e.visibility.label, Tone.PRIMARY))
        })
        add(card {
            add(text("🗓 ${Fmt.relativeDay(e.startsAt, now)} · ${Fmt.duration(e.durationMin)}", TS.BODY))
            add(text("👤 Anfitrião: ${if (host) "você" else s.user(e.hostId)?.name ?: "?"}", TS.BODY))
            add(text("🐕 ${e.approvedDogCount()} de ${e.maxDogs} vagas preenchidas", TS.BODY))
            if (approved) {
                add(divider())
                add(text("📍 Endereço exato", TS.LABEL, C.ok))
                add(text(e.exactAddress, TS.BODY) { setTextIsSelectable(true) })
            } else {
                add(text("📍 ${e.neighborhood} (área aproximada)", TS.BODY))
                add(text("O endereço exato e o chat são liberados após o anfitrião aceitar seu pedido.", TS.SMALL, C.textMuted))
            }
        })
        if (e.description.isNotBlank()) add(card { add(text(e.description, TS.BODY)) })
        add(section("O espaço oferece"))
        if (e.amenities.isEmpty()) add(text("Nada marcado.", TS.SMALL, C.textMuted))
        else add(FlowLayout(a, a.dp(6), a.dp(6)).apply { e.amenities.sortedBy { it.ordinal }.forEach { addView(a.badge("✓ " + it.label, Tone.OK)) } })
        add(section("Filtros de porta", "Requisitos travados pelo anfitrião."))
        DoorFilter.describe(e.requirements).forEach { add(text("• $it", TS.SMALL)) }

        if (host) renderHost(e) else renderGuest(e)

        if (approved && !e.cancelled) {
            add(section("Chat do rolê"))
            if (e.chat.isEmpty()) add(text("Nenhuma mensagem ainda.", TS.SMALL, C.textMuted))
            e.chat.takeLast(50).forEach { m ->
                val mine = m.userId == s.meId
                add(card(10, 2, color = if (mine) C.primarySoft else C.surface) {
                    add(text(if (mine) "Você" else s.user(m.userId)?.name ?: "?", TS.LABEL))
                    add(text(m.text, TS.BODY))
                    add(text(Fmt.relativeDay(m.at, now), TS.SMALL, C.textMuted))
                })
            }
            var msg = ""
            val f = field("Mensagem", "", "Escreva para o grupo", lines = 2) { msg = it }
            add(f)
            add(a.button("Enviar") { a.handle(repo.sendChat(e.id, msg)) { f.editText().setText("") } })
        }
    }

    private fun android.widget.LinearLayout.renderHost(e: PrivateEvent) {
        if (e.cancelled) return
        add(section("Pedidos"))
        if (e.requests.isEmpty()) add(text("Nenhum pedido ainda.", TS.SMALL, C.textMuted))
        e.requests.forEach { r ->
            add(a.card(12, 8) {
                add(text("${s.user(r.userId)?.name ?: "?"} · ${r.status.label}", TS.H3))
                if (r.message.isNotBlank()) add(text("\"${r.message}\"", TS.SMALL))
                r.petIds.mapNotNull { s.pet(it) }.forEach { p ->
                    add(a.petRow(p, s, now) { a.show(PetProfileScreen(a, p.id)) })
                    val el = DoorFilter.check(p, e.requirements, now)
                    if (!el.ok) add(banner("${p.name} não atende mais: ${el.reasons.joinToString()}", null, Tone.WARN))
                }
                if (r.status == RequestStatus.PENDING) add(hbox(8) {
                    addWeighted(a.button("Aceitar") { a.handle(repo.decideRequest(e.id, r.userId, true), "Aprovado: endereço e chat liberados para ${s.user(r.userId)?.name}") })
                    addWeighted(a.button("Recusar", BtnKind.SECONDARY) { a.handle(repo.decideRequest(e.id, r.userId, false)) })
                })
            })
        }
        if (e.visibility == PrivateVisibility.PACK_ONLY) add(text("Convidados: " + e.invitedUserIds.mapNotNull { s.user(it)?.name }.joinToString(), TS.SMALL, C.textMuted))
        add(a.button("Cancelar rolê", BtnKind.DANGER) { a.confirm("Cancelar \"${e.title}\"?", "Os participantes verão que foi cancelado.", "Cancelar rolê") { repo.cancelPrivateEvent(e.id) } })
    }

    private fun android.widget.LinearLayout.renderGuest(e: PrivateEvent) {
        val r = e.requestOf(s.meId)
        val elig = repo.eligibility(e)
        add(section("Seus pets"))
        elig.forEach { el ->
            if (el.ok) add(banner("${el.pet.name} atende aos requisitos", null, Tone.OK))
            else add(banner("${el.pet.name} não pode participar", el.reasons.joinToString("\n") { "• $it" }, Tone.NEUTRAL, "🚫"))
        }
        if (e.cancelled) return
        val okPets = elig.filter { it.ok }.map { it.pet }
        when {
            e.endsAt < now -> add(banner("Este rolê já terminou", null, Tone.NEUTRAL))
            r?.status == RequestStatus.PENDING -> {
                add(banner("Pedido enviado", "Aguardando o anfitrião.", Tone.INFO))
                add(a.button("Cancelar pedido", BtnKind.SECONDARY) { repo.cancelRequest(e.id) })
            }
            r?.status == RequestStatus.APPROVED -> {
                add(banner("Você foi aprovado! 🎉", "Endereço e chat liberados acima.", Tone.OK))
                add(a.button("Não poderei ir", BtnKind.SECONDARY) { a.confirm("Desistir do rolê?", "Sua vaga será liberada.", "Desistir") { repo.cancelRequest(e.id) } })
            }
            r?.status == RequestStatus.REJECTED -> add(banner("O anfitrião não aprovou desta vez", null, Tone.NEUTRAL))
            okPets.isEmpty() -> add(a.button("Pedir para participar", enabled = false) {}.apply { contentDescription = "Pedir para participar, indisponível: nenhum dos seus pets atende aos filtros" })
            e.approvedDogCount() >= e.maxDogs -> add(banner("Vagas esgotadas", null, Tone.NEUTRAL))
            else -> {
                var msg = ""
                add(field("Mensagem ao anfitrião (opcional)", "", "Ex.: o Paco adora piscina e é bem tranquilo", lines = 2) { msg = it })
                add(a.button("Pedir para participar") {
                    a.pickPets("Quem vai?", okPets) { ids -> a.handle(repo.requestToJoin(e.id, ids, msg), "Pedido enviado ao anfitrião!") }
                })
            }
        }
    }
}

class PrivateEventFormScreen(a: MainActivity) : Screen(a) {
    override val title = "Abrir meu quintal"
    override val liveUpdates = false
    private var t = ""
    private var desc = ""
    private var startsAt = Fmt.atTime(Repository.addDays(Repository.startOfDay(a.repo.now()), 2), 16 * 60)
    private var duration = 180
    private var hood = a.repo.state.me.neighborhood
    private var address = ""
    private var maxDogs = 4
    private var maxKg: Double? = null
    private var sizes: Set<SizeClass> = emptySet()
    private var vax = true
    private var noIntact = false
    private var noHeat = true
    private var maxEnergy: Int? = null
    private var ages: Set<AgeGroup> = emptySet()
    private var amen: Set<HostAmenity> = emptySet()
    private var vis = PrivateVisibility.MAP_APPROX
    private var invited: Set<String> = emptySet()
    /** Ponto do endereço (privado). Começa na localização atual, se houver. */
    private var pointLat: Double? = a.repo.state.myLat
    private var pointLon: Double? = a.repo.state.myLon
    private var pointFromGps = a.repo.state.myLat != null

    override fun render(): View = a.page {
        add(text("Ofereça um espaço 100% controlado. No mapa aparece só um círculo aproximado do bairro.", TS.SMALL, C.textMuted))
        add(field("Título", t, "Ex.: Tarde de piscina para Goldens e Labradores") { t = it })
        add(field("Descrição", desc, "Como é o espaço, regras da casa…", lines = 3) { desc = it })
        add(a.valueButton("Data e hora", Fmt.dateTime(startsAt)) { a.pickDateTime(startsAt) { startsAt = it; a.renderNow(false) } })
        add(text("Duração", TS.LABEL))
        add(a.singleChoice(listOf(60, 120, 180, 240, 360), duration, { Fmt.duration(it) }) { duration = it })
        add(field("Bairro (público)", hood, "Ex.: Lago Sul", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { hood = it })
        add(field("Endereço completo (privado)", address, "Só aprovados verão", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS) { address = it })
        add(a.valueButton("Ponto do endereço no mapa (privado)", when {
            pointLat == null -> "Marcar no mapa"
            pointFromGps -> "Minha localização atual"
            else -> "Marcado no mapa"
        }) {
            a.show(MapPickerScreen(a, "Onde é o rolê?", pointLat ?: s.myLat ?: -15.7942, pointLon ?: s.myLon ?: -47.8822) { la, lo ->
                pointLat = la; pointLon = lo; pointFromGps = false
            })
        })
        add(text("Usado só para posicionar o círculo aproximado (deslocado) no mapa.", TS.SMALL, C.textMuted))

        add(section("Filtros de porta", "Cães fora do filtro não conseguem pedir para participar."))
        add(text("Limite de cães", TS.LABEL))
        add(a.singleChoice(listOf(2, 3, 4, 6, 8, 10, 15), maxDogs, { "$it" }) { maxDogs = it })
        add(text("Peso máximo", TS.LABEL))
        add(a.singleChoice(listOf<Double?>(null, 5.0, 10.0, 25.0, 45.0), maxKg, { if (it == null) "Sem limite" else "Até ${Fmt.kg(it)}" }) { maxKg = it })
        add(text("Portes aceitos (vazio = todos)", TS.LABEL))
        add(a.multiChoice(SizeClass.entries, sizes, { it.label }) { sizes = it })
        add(text("Faixa etária (vazio = todas)", TS.LABEL))
        add(a.multiChoice(AgeGroup.entries, ages, { it.label }) { ages = it })
        add(text("Energia máxima", TS.LABEL))
        add(a.singleChoice(listOf<Int?>(null, 2, 3, 4), maxEnergy, { if (it == null) "Qualquer" else "Até $it/5" }) { maxEnergy = it })
        add(a.switchRow("Obrigatório comprovante de vacina aprovado", null, vax) { vax = it })
        add(a.switchRow("Proibido machos não castrados", null, noIntact) { noIntact = it })
        add(a.switchRow("Proibido fêmeas no cio", null, noHeat) { noHeat = it })

        add(section("Checklist do anfitrião"))
        add(a.multiChoice(HostAmenity.entries, amen, { it.label }) { amen = it })

        add(section("Visibilidade"))
        val friends = s.friendIds().toList().sortedBy { s.user(it)?.name }
        val inviteBox = vbox(spacingDp = 8)
        fun refreshInvites() {
            inviteBox.removeAllViews()
            if (vis != PrivateVisibility.PACK_ONLY) return
            if (friends.isEmpty()) inviteBox.add(banner("Sua Matilha está vazia", "Adicione tutores à Matilha para criar rolês invisíveis.", Tone.WARN))
            else {
                inviteBox.add(text("Convidar da Matilha", TS.LABEL))
                inviteBox.add(a.multiChoice(friends, invited, { u -> "${s.user(u)?.name} (${s.petNames(s.petsOf(u).map { it.id })})" }) { invited = it })
            }
        }
        add(a.singleChoice(PrivateVisibility.entries, vis, { it.label }) { vis = it; refreshInvites() })
        refreshInvites()
        add(inviteBox)

        add(a.button("Publicar rolê") {
            val req = EventRequirements(maxKg, sizes, vax, noIntact, noHeat, maxEnergy, ages, true)
            val lat = pointLat; val lon = pointLon
            if (lat == null || lon == null) { a.toast("Marque o ponto do endereço no mapa."); return@button }
            a.handle(repo.createPrivateEvent(t, desc, startsAt, duration, hood, address, lat, lon, maxDogs, req, amen, vis, invited), "Rolê criado!") {
                a.replaceTop(PrivateEventScreen(a, it.id))
            }
        })
    }
}
