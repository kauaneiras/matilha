package com.matilha.app.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.matilha.app.MainActivity
import com.matilha.app.Screen
import com.matilha.app.Tab
import com.matilha.app.data.AppState
import com.matilha.app.data.Fmt
import com.matilha.app.data.Repository
import com.matilha.app.domain.*
import com.matilha.app.page
import com.matilha.app.platform.Geofence
import com.matilha.app.platform.MatilhaApp
import com.matilha.app.platform.Perms
import com.matilha.app.screenScroll
import com.matilha.app.ui.*

/** Status visual de um local para o MEU pet. Cor + símbolo + texto (acessível). */
enum class PlaceStatus(val symbol: String, val label: String) {
    HAZARD("!", "Alerta ativo"), CONFLICT("⚠", "Perfis que podem estressar seu pet"), ACTIVE("", "Cães presentes, sem conflitos"), EMPTY("", "Vazio agora")
}

fun placeStatus(s: AppState, p: Place, now: Long): PlaceStatus {
    if (s.activeHazards(p.id, now).isNotEmpty()) return PlaceStatus.HAZARD
    val present = s.presentPets(p.id, now)
    if (Compatibility.hasConflict(s.myPets, present)) return PlaceStatus.CONFLICT
    return if (present.any { it.ownerId != s.meId }) PlaceStatus.ACTIVE else PlaceStatus.EMPTY
}

fun statusColor(st: PlaceStatus) = when (st) {
    PlaceStatus.HAZARD -> 0xFFC62828.toInt(); PlaceStatus.CONFLICT -> 0xFFE0A100.toInt()
    PlaceStatus.ACTIVE -> 0xFF2E7D32.toInt(); PlaceStatus.EMPTY -> 0xFF5C6B73.toInt()
}

class MapScreen(a: MainActivity) : Screen(a) {
    override val title = "Mapa vivo"
    override val tab = Tab.MAP
    override val liveUpdates = false

    private var listMode = false
    private var fQuiet = false
    private var fShade = false
    private var fFenced = false
    private var fPrivate = true
    private var map: OsmMapView? = null
    private var bottom: LinearLayout? = null
    private val listener: (AppState) -> Unit = { a.runOnUiThread { refresh() } }

    override fun onShown() { repo.observe(listener) }
    override fun onHidden() { repo.unobserve(listener) }

    private fun visiblePlaces(): List<Place> {
        val hour = Repository.hourOf(now)
        return s.places.filter { p ->
            (!fQuiet || PlaceRules.isQuietNow(p, s.presentPets(p.id, now).size, hour)) &&
                (!fShade || PlaceRules.hasShadeAt(p, hour)) &&
                (!fFenced || PlaceRules.consensus(s.reviews.filter { it.placeId == p.id }).fencing.let { it == Fencing.DOUBLE_GATE || it == Fencing.SIMPLE_FENCE })
        }
    }

    private fun visiblePrivate(): List<PrivateEvent> = if (!fPrivate) emptyList() else
        s.privateEvents.filter { !it.cancelled && it.endsAt > now && it.startsAt < now + 14 * DAY && repo.canSee(it) && it.visibility == PrivateVisibility.MAP_APPROX }

    private fun markers(): List<MapMarker> {
        val ps = visiblePlaces().map { p ->
            val st = placeStatus(s, p, now)
            val n = s.presentPets(p.id, now).size
            MapMarker(p.id, p.lat, p.lon, p.name, statusColor(st), badge = if (st == PlaceStatus.HAZARD) "!" else if (n > 0) "$n" else if (st == PlaceStatus.CONFLICT) "⚠" else "·")
        }
        val pv = visiblePrivate().map { e ->
            MapMarker("pv:" + e.id, e.approxLat, e.approxLon, e.neighborhood + " · rolê privado", 0xFF6A4FB3.toInt(), areaRadiusM = e.approxRadiusM, symbol = "🏡")
        }
        return pv + ps
    }

    override fun render(): View {
        if (listMode) return renderList()
        val frame = FrameLayout(a)
        val m = map ?: OsmMapView(a).also { mv ->
            map = mv
            val (la, lo) = (s.myLat to s.myLon).let { (x, y) -> if (x != null && y != null) x to y else -15.7990 to -47.8950 }
            mv.setCenter(la, lo, 12.5)
            mv.tilesEnabled = !a.testMode
            mv.onMarkerTap = { mk ->
                if (mk.id.startsWith("pv:")) a.show(PrivateEventScreen(a, mk.id.removePrefix("pv:"))) else a.show(PlaceScreen(a, mk.id))
            }
            mv.onLongPress = { la2, lo2 -> a.show(PlaceFormScreen(a, null, la2, lo2)) }
        }
        (m.parent as? ViewGroup)?.removeView(m)
        frame.addView(m, FrameLayout.LayoutParams(-1, -1))

        // Filtros no topo.
        val chips = a.hbox(8) {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            add(a.chip("🤫 Fuga da Muvuca", fQuiet) { fQuiet = it; refresh() })
            add(a.chip("🌳 Sombra agora", fShade) { fShade = it; refresh() })
            add(a.chip("🔒 Cercados", fFenced) { fFenced = it; refresh() })
            add(a.chip("🏡 Rolês privados", fPrivate) { fPrivate = it; refresh() })
        }
        frame.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Controles à direita.
        val controls = a.vbox(spacingDp = 8) {
            fun round(label: String, desc: String, onClick: () -> Unit) = a.text(label, TS.H2) {
                gravity = Gravity.CENTER; contentDescription = desc
                background = ripple(rounded(C.surface, 26f, context, C.outline), context, 26f)
                elevation = dp(3).toFloat()
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
            add(round("+", "Aproximar") { m.zoomBy(1.0) }, dp(52), dp(52))
            add(round("−", "Afastar") { m.zoomBy(-1.0) }, dp(52), dp(52))
            add(round("◎", "Minha localização") { centerOnMe() }, dp(52), dp(52))
        }
        frame.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = a.dp(12) })

        val b = a.vbox(12, 8)
        bottom = b
        frame.addView(b, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = a.dp(14) })
        refresh()
        return frame
    }

    private fun centerOnMe() {
        if (!Perms.location(a)) { a.requestLocation { ok -> if (ok) a.toast("Buscando sua localização…") }; return }
        val la = s.myLat; val lo = s.myLon
        if (la != null && lo != null) map?.setCenter(la, lo, maxOf(map?.zoom ?: 14.0, 14.0)) else a.toast("Ainda sem sinal de localização. Verifique se o GPS está ligado.")
    }

    private fun refresh() {
        if (listMode) { a.renderNow(false); return }
        val m = map ?: return
        m.markers = markers()
        m.myLocation = if (s.myLat != null && s.myLon != null) s.myLat!! to s.myLon!! else null
        val b = bottom ?: return
        b.removeAllViews()
        val active = s.myActiveCheckIn(now)
        if (active != null) {
            val p = s.place(active.placeId)
            b.add(a.card(12, 8) {
                elevation = dp(6).toFloat()
                add(text("📍 Você está em ${p?.name ?: "um local"} com ${s.petNames(active.petIds)}", TS.LABEL))
                add(text("Desde ${Fmt.time(active.startedAt)}." + if (s.alertSettings.autoCheckout) " Check-out automático ao se afastar." else "", TS.SMALL, C.textMuted))
                add(hbox(8) {
                    addWeighted(a.button("Ver local", BtnKind.SECONDARY) { p?.let { a.show(PlaceScreen(a, it.id)) } })
                    addWeighted(a.button("Check-out") { doCheckout(a) })
                })
            })
        }
        b.add(a.hbox(8) {
            addWeighted(a.button("☰ Lista de locais", BtnKind.SECONDARY) { listMode = true; a.renderNow(true) }.apply { elevation = dp(4).toFloat() })
            addWeighted(a.button("+ Novo local") { val (la, lo) = m.centerLat to m.centerLon; a.show(PlaceFormScreen(a, null, la, lo)) }.apply { elevation = dp(4).toFloat() })
        })
        val shown = visiblePlaces().size
        if (shown == 0 && (fQuiet || fShade || fFenced)) b.add(a.banner("Nenhum local atende aos filtros agora", "Desmarque algum filtro no topo.", Tone.INFO))
        b.add(legend())
        m.contentDescription = "Mapa com $shown locais. Use o botão Lista de locais para navegar com leitor de tela."
    }

    private fun legend(): View = a.hbox(10) {
        background = rounded(C.surface, 12f, context, C.outline)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        listOf(PlaceStatus.EMPTY to "Vazio", PlaceStatus.ACTIVE to "Com cães", PlaceStatus.CONFLICT to "Atenção", PlaceStatus.HAZARD to "Perigo").forEach { (st, l) ->
            add(colorDot(statusColor(st)))
            add(text(l, TS.SMALL, C.text))
        }
    }

    private fun renderList(): View = a.page {
        add(a.button("🗺️ Voltar ao mapa", BtnKind.SECONDARY) { listMode = false; a.renderNow(true) })
        add(a.multiChoice(listOf("quiet", "shade", "fenced"), setOfNotNull("quiet".takeIf { fQuiet }, "shade".takeIf { fShade }, "fenced".takeIf { fFenced }),
            { when (it) { "quiet" -> "🤫 Fuga da Muvuca"; "shade" -> "🌳 Sombra agora"; else -> "🔒 Cercados" } }) { sel ->
            fQuiet = "quiet" in sel; fShade = "shade" in sel; fFenced = "fenced" in sel; a.renderNow(false)
        })
        val places = visiblePlaces().sortedBy { s.distanceToMe(it.lat, it.lon) ?: Double.MAX_VALUE }
        if (places.isEmpty()) add(a.emptyState("🔍", "Nenhum local com esses filtros", "Tente desmarcar algum filtro."))
        places.forEach { p -> add(placeCard(a, s, p, now)) }
        val pv = visiblePrivate()
        if (pv.isNotEmpty()) {
            add(section("Rolês privados por perto", "Só a área aproximada é mostrada."))
            pv.forEach { e -> add(a.clickableCard({ a.show(PrivateEventScreen(a, e.id)) }) {
                add(text("🏡 ${e.title}", TS.H3)); add(text("${e.neighborhood} · ${Fmt.relativeDay(e.startsAt, now)}", TS.SMALL, C.textMuted))
            }) }
        }
    }
}

fun placeCard(a: MainActivity, s: AppState, p: Place, now: Long): View = a.clickableCard({ a.show(PlaceScreen(a, p.id)) }) {
    val st = placeStatus(s, p, now)
    val present = s.presentPets(p.id, now)
    add(a.hbox(8) {
        add(a.colorDot(statusColor(st), 14))
        addWeighted(a.text(p.name, TS.H3))
        s.distanceToMe(p.lat, p.lon)?.let { add(a.text(Geo.formatDistance(it), TS.SMALL, C.textMuted)) }
    })
    add(a.text("${p.kind.label} · ${st.label}" + if (present.isNotEmpty()) " · ${Fmt.plural(present.size, "cão", "cães")} agora" else "", TS.SMALL, C.textMuted))
    val hz = s.activeHazards(p.id, now)
    if (hz.isNotEmpty()) add(a.badge("⚠ " + hz.joinToString { it.type.label }, Tone.DANGER))
}

fun doCheckout(a: MainActivity) {
    val c = a.repo.checkOut(auto = false) ?: return
    Geofence.clear(a)
    a.show(WalkFeedbackScreen(a, c.placeId))
}

class PlaceScreen(a: MainActivity, private val placeId: String) : Screen(a) {
    override val title: String get() = s.place(placeId)?.name ?: "Local"
    private var agendaDay = 0 // 0 = hoje, 1 = amanhã
    private var selFrom: Int? = null
    private var selTo: Int? = null

    override fun render(): View = a.page {
        val p = s.place(placeId) ?: run { add(a.emptyState("📍", "Local não encontrado", "")); return@page }
        val hour = Repository.hourOf(now)
        val cons = PlaceRules.consensus(s.reviews.filter { it.placeId == p.id })
        val present = s.presentPets(p.id, now)
        val activeHere = s.activeAt(p.id, now)
        val myCheckIn = s.myActiveCheckIn(now)

        // Cabeçalho
        add(card {
            add(text(p.name, TS.H2))
            val dist = s.distanceToMe(p.lat, p.lon)
            add(text(listOfNotNull(p.kind.label, dist?.let { "a ${Geo.formatDistance(it)}" }, Fmt.plural(cons.reviewCount, "avaliação", "avaliações")).joinToString(" · "), TS.SMALL, C.textMuted))
        })

        // Alertas
        val hazards = s.activeHazards(p.id, now)
        hazards.forEach { h ->
            add(vbox(spacingDp = 6) {
                add(banner(h.type.label, listOfNotNull(h.note.ifBlank { null }, "Reportado ${Fmt.ago(h.createdAt, now)} · ${Fmt.plural(1 + h.confirmations.size, "confirmação", "confirmações")}").joinToString("\n"), Tone.DANGER))
                if (h.reportedBy != s.meId) add(hbox(8) {
                    addWeighted(a.button(if (s.meId in h.confirmations) "✓ Confirmado" else "Ainda está lá", BtnKind.DANGER, enabled = s.meId !in h.confirmations) { repo.confirmHazard(h.id); a.toast("Obrigado por confirmar") })
                    addWeighted(a.button("Já foi resolvido", BtnKind.SECONDARY) { repo.resolveHazard(h.id); a.toast("Obrigado! O alerta some quando a maioria confirmar.") })
                }) else add(a.button("Marcar como resolvido", BtnKind.SECONDARY) { repo.resolveHazard(h.id) })
            })
        }
        if (PlaceRules.hotFloorNow(cons.floor, hour)) add(banner("Piso quente agora", "Cimento/asfalto entre 10h e 16h pode queimar os coxins. Teste com o dorso da mão por 7 segundos.", Tone.WARN, "🔥"))
        add(a.button("⚠ Reportar perigo", BtnKind.WARN) { hazardDialog(p) })

        // Ao vivo
        add(section("Ao vivo", "Quem está lá agora (check-in manual ou automático)."))
        add(card {
            val others = present.filter { it.ownerId != s.meId }
            add(text(if (others.isEmpty() && present.isEmpty()) "Ninguém com check-in agora" else "🐕 ${Fmt.plural(present.size, "cão", "cães")} no local agora", TS.H3))
            add(badge("Clima atual: " + Harmony.summary(present), Tone.PRIMARY))
            val warns = Compatibility.warningsForGroup(s.myPets, present)
            if (warns.any { it.level != WarningLevel.INFO }) add(banner("Atenção: há perfis no local que podem estressar o seu pet", null, Tone.WARN))
            warns.forEach { w -> add(text("• " + w.message, TS.SMALL, if (w.level == WarningLevel.INFO) C.textMuted else C.text)) }
        })
        activeHere.filter { it.userId != s.meId }.forEach { c ->
            c.petIds.mapNotNull { s.pet(it) }.forEach { pet ->
                val best = s.isBestFriend(c.userId)
                add(a.petRow(pet, s, now, extra = (if (best) "★ Melhor amigo · " else "") + "chegou ${Fmt.ago(c.startedAt, now)}") { a.show(PetProfileScreen(a, pet.id)) })
            }
        }
        when {
            myCheckIn?.placeId == p.id -> add(hbox(8) {
                addWeighted(text("✅ Você está aqui com ${s.petNames(myCheckIn.petIds)}", TS.LABEL, C.ok))
                add(a.button("Check-out") { doCheckout(a) })
            })
            else -> add(a.button(if (myCheckIn != null) "Mudar meu check-in para cá" else "📍 Fazer check-in aqui") { startCheckIn(p) })
        }

        // Agenda
        add(section("Agenda: quem vai mais tarde?", "Toque no início e no fim do horário (de 30 em 30 min, 06h–23h)."))
        add(a.singleChoice(listOf(0, 1), agendaDay, { if (it == 0) "Hoje" else "Amanhã" }) { agendaDay = it; selFrom = null; selTo = null; a.renderNow(false) })
        val day = Repository.addDays(Repository.startOfDay(now), agendaDay)
        val schedules = repo.schedulesFor(p.id, day)
        add(agendaGrid(day, schedules))
        val f = selFrom
        if (f != null) {
            val t = selTo ?: (f + ScheduleRules.SLOT_MIN)
            add(a.button("Estarei lá das ${ScheduleRules.label(f)} às ${ScheduleRules.label(t)}") {
                a.pickPets("Quem vai?", s.myPets) { ids ->
                    a.handle(repo.schedule(p.id, day, f, t, ids), "Presença marcada!") { selFrom = null; selTo = null }
                }
            })
            add(a.button("Limpar seleção", BtnKind.GHOST) { selFrom = null; selTo = null; a.renderNow(false) })
        }
        if (schedules.isEmpty()) add(text("Ninguém agendou ainda para ${if (agendaDay == 0) "hoje" else "amanhã"}.", TS.SMALL, C.textMuted))
        schedules.forEach { sc ->
            val mine = sc.userId == s.meId
            val best = s.isBestFriend(sc.userId)
            val going = s.schedules.any { it.userId == s.meId && it.joinedScheduleId == sc.id }
            add(card(12, 6) {
                add(text("${ScheduleRules.label(sc.fromMin)}–${ScheduleRules.label(sc.toMin)} · ${s.petNames(sc.petIds)}" + if (best) " ★" else "", TS.LABEL))
                add(text(if (mine) "Você" else "Tutor: ${s.user(sc.userId)?.name ?: "?"}" + if (best) " (melhor amigo)" else "", TS.SMALL, C.textMuted))
                val joiners = s.schedules.filter { it.joinedScheduleId == sc.id }
                if (joiners.isNotEmpty()) add(text("Também vão: " + joiners.joinToString { s.petNames(it.petIds) }, TS.SMALL))
                if (mine) add(a.button("Cancelar minha presença", BtnKind.SECONDARY) { repo.cancelSchedule(sc.id) })
                else if (!going) add(a.button("Eu também vou!") {
                    a.pickPets("Quem vai?", s.myPets) { ids -> a.handle(repo.joinSchedule(sc, ids), "Presença confirmada!") }
                })
                else add(badge("✓ Você confirmou presença", Tone.OK))
            })
        }

        // Passeio solo
        add(section("Modo passeio solo", "Gráfico de silêncio/movimento (média da comunidade)."))
        add(card {
            val crowdNow = PlaceRules.crowdOf(p.typicalDogsByHour.getOrElse(hour) { 0.0 })
            add(text("Agora: ${if (present.isEmpty()) "ninguém com check-in" else Fmt.plural(present.size, "cão", "cães")} · normalmente ${crowdNow.label.lowercase()} neste horário", TS.SMALL))
            add(CrowdChart(a, p.typicalDogsByHour, hour), -1, a.dp(150))
            val quiet = PlaceRules.quietWindows(p.typicalDogsByHour)
            add(text(if (quiet.isEmpty()) "Sem horário tipicamente vazio entre 06h e 23h." else "🤫 Costuma estar vazio: " + quiet.joinToString(", ") { PlaceRules.formatWindow(it) }, TS.LABEL, C.primary))
            add(text("Ideal para cães reativos, medrosos, em adestramento ou tutores que querem paz.", TS.SMALL, C.textMuted))
        })
        add(card {
            add(text("🌳 Clima e sombra", TS.H3))
            val shade = p.shadeHours.sorted()
            add(text(if (shade.isEmpty()) "Sem informação de sombra ainda." else "Sombra contínua: " + compressHours(shade), TS.SMALL))
            add(badge(if (PlaceRules.hasShadeAt(p, hour)) "Agora: com sombra" else "Agora: sol pleno ou sem informação", if (PlaceRules.hasShadeAt(p, hour)) Tone.OK else Tone.WARN))
        })

        // Radiografia
        add(section("Radiografia do local", "Consenso das avaliações dos tutores."))
        add(card {
            add(text("Segurança e cercamento", TS.LABEL))
            add(text(cons.fencing?.let { "${it.label} — ${it.detail}" } ?: "Sem avaliação", TS.BODY))
            add(divider())
            add(text("Tipo de piso", TS.LABEL))
            add(text(cons.floor?.label ?: "Sem avaliação", TS.BODY))
            cons.floor?.warning?.let { add(badge("⚠ $it", Tone.WARN)) }
            add(divider())
            add(text("Infraestrutura", TS.LABEL))
            Amenity.entries.forEach { am ->
                val votes = cons.amenities[am] ?: 0
                val ok = cons.confirmed(am)
                add(text((if (ok) "✓ " else "✗ ") + am.label + if (cons.reviewCount > 0) " ($votes/${cons.reviewCount})" else "", TS.SMALL, if (ok) C.ok else C.textMuted) {
                    contentDescription = am.label + if (ok) ": confirmado" else ": não confirmado"
                })
            }
            if (Amenity.NIGHT_LIGHT !in Amenity.entries.filter { cons.confirmed(it) }) add(text("Sem iluminação confirmada: evite ir às 21h.", TS.SMALL, C.warn))
        })
        add(a.button(if (repo.myReview(p.id) == null) "Avaliar este local" else "Atualizar minha avaliação", BtnKind.SECONDARY) { a.show(PlaceFormScreen(a, p.id, p.lat, p.lon)) })

        // Eventos públicos
        add(section("Eventos públicos aqui"))
        val evs = s.publicEvents.filter { it.placeId == p.id && it.startsAt + it.durationMin * MINUTE > now }.sortedBy { it.startsAt }
        if (evs.isEmpty()) add(text("Nenhum evento marcado.", TS.SMALL, C.textMuted))
        evs.forEach { e -> add(publicEventCard(a, s, e, now)) }
        add(a.button("+ Criar evento público aqui", BtnKind.SECONDARY) { a.show(PublicEventFormScreen(a, p.id)) })
    }

    private fun startCheckIn(p: Place) {
        a.pickPets("Quem está com você?", s.myPets) { ids ->
            val d = s.distanceToMe(p.lat, p.lon)
            val doIt = {
                a.handle(repo.checkIn(p.id, ids), "Check-in feito em ${p.name}!") {
                    MatilhaApp.io.execute { Geofence.register(a, p.lat, p.lon, p.radiusM) }
                    if (s.alertSettings.autoCheckout && !Perms.fineLocation(a)) a.toast("Ative a localização para o check-out automático.", long = true)
                }
            }
            if (d != null && d > p.radiusM + 500) a.confirm("Você parece estar longe", "Sua localização está a ${Geo.formatDistance(d)} de ${p.name}. Check-ins só devem ser feitos no local, para o mapa não mentir.", "Estou lá") { doIt() }
            else doIt()
        }
    }

    private fun hazardDialog(p: Place) {
        val types = HazardType.entries
        AlertDialog.Builder(a).setTitle("Qual o perigo em ${p.name}?")
            .setItems(types.map { it.label }.toTypedArray()) { _, i ->
                val f = a.field("Detalhe (opcional)", "", "Ex.: perto do banco da entrada", lines = 2)
                f.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
                AlertDialog.Builder(a).setTitle(types[i].label).setView(f)
                    .setPositiveButton("Reportar") { _, _ -> a.handle(repo.reportHazard(p.id, types[i], f.editText().text.toString()), "Alerta publicado. Obrigado por cuidar da matilha!") }
                    .setNegativeButton("Cancelar", null).show()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun agendaGrid(day: Long, schedules: List<Schedule>): View {
        val counts = ScheduleRules.dogsPerSlot(schedules)
        val flow = FlowLayout(a, a.dp(6), a.dp(6))
        ScheduleRules.slots.forEach { slot ->
            val past = Fmt.atTime(day, slot + ScheduleRules.SLOT_MIN) <= now
            val n = counts[slot] ?: 0
            val f = selFrom; val t = selTo
            val selected = f != null && (slot == f || (t != null && slot >= f && slot < t))
            val v = a.text(ScheduleRules.label(slot) + if (n > 0) "\n🐕$n" else "\n ", TS.SMALL) {
                gravity = Gravity.CENTER
                minWidth = dp(64); minHeight = dp(52)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                val bg = when { selected -> C.primary; past -> C.surfaceAlt; n >= 4 -> C.warnSoft; n > 0 -> C.okSoft; else -> C.surface }
                background = ripple(rounded(bg, 10f, context, if (selected) C.primary else C.outline), context, 10f)
                setTextColor(when { selected -> C.onPrimary; past -> C.textMuted; else -> C.text })
                contentDescription = "${ScheduleRules.label(slot)}, ${if (n == 0) "ninguém agendado" else "$n cães agendados"}" + if (past) ", horário passado" else if (selected) ", selecionado" else ""
                isEnabled = !past
                isClickable = !past; isFocusable = !past
                setOnClickListener { onSlot(slot) }
            }
            flow.addView(v)
        }
        return flow
    }

    private fun onSlot(slot: Int) {
        val f = selFrom
        when {
            f == null || selTo != null -> { selFrom = slot; selTo = null }
            slot < f -> { selFrom = slot }
            else -> { selTo = slot + ScheduleRules.SLOT_MIN }
        }
        a.renderNow(false)
    }
}

/** "06h–09h, 16h–19h" a partir de horas avulsas. */
fun compressHours(hours: List<Int>): String {
    if (hours.isEmpty()) return ""
    val out = mutableListOf<IntRange>()
    var start = hours[0]; var prev = hours[0]
    for (h in hours.drop(1)) { if (h == prev + 1) prev = h else { out += start..prev; start = h; prev = h } }
    out += start..prev
    return out.joinToString(", ") { PlaceRules.formatWindow(it) }
}

/** Gráfico de barras de movimento por hora (06h–22h). */
class CrowdChart(ctx: Context, private val values: List<Double>, private val nowHour: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * resources.displayMetrics.density; color = C.textMuted; textAlign = Paint.Align.CENTER }
    private val hours = 6..22

    init {
        val quiet = PlaceRules.quietWindows(values)
        contentDescription = "Gráfico de movimento por hora. " + hours.joinToString("; ") { "${it}h ${PlaceRules.crowdOf(values.getOrElse(it) { 0.0 }).label}" } +
            (if (quiet.isNotEmpty()) ". Costuma estar vazio: " + quiet.joinToString { PlaceRules.formatWindow(it) } else "")
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val n = hours.count()
        val labelH = 16 * d
        val chartH = height - labelH - 4 * d
        val bw = width.toFloat() / n
        val max = maxOf(values.maxOrNull() ?: 1.0, 6.0)
        hours.forEachIndexed { i, h ->
            val v = values.getOrElse(h) { 0.0 }
            val bh = maxOf((v / max * chartH).toFloat(), 3 * d)
            p.color = when (PlaceRules.crowdOf(v)) {
                PlaceRules.Crowd.EMPTY -> 0xFF8BC4A8.toInt(); PlaceRules.Crowd.CALM -> 0xFF4E9F7A.toInt()
                PlaceRules.Crowd.MODERATE -> 0xFFE0A100.toInt(); PlaceRules.Crowd.BUSY -> 0xFFC62828.toInt()
            }
            val x = i * bw
            c.drawRoundRect(x + bw * 0.15f, chartH - bh, x + bw * 0.85f, chartH, 3 * d, 3 * d, p)
            if (h == nowHour) {
                p.color = C.text; p.strokeWidth = 2 * d
                c.drawLine(x + bw / 2, 0f, x + bw / 2, chartH, p)
            }
            if ((h - 6) % 3 == 0) {
                tp.typeface = if (h == nowHour) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                c.drawText("${h}h", x + bw / 2, height - 4 * d, tp)
            }
        }
    }
}

/** Escolha de um ponto: o usuário arrasta o mapa sob o alfinete fixo no centro. */
class MapPickerScreen(a: MainActivity, title0: String, private val startLat: Double, private val startLon: Double, private val onPicked: (Double, Double) -> Unit) : Screen(a) {
    override val title = title0
    override val liveUpdates = false
    override fun render(): View {
        val frame = FrameLayout(a)
        val m = OsmMapView(a).apply { setCenter(startLat, startLon, 15.0); tilesEnabled = !a.testMode; contentDescription = "Mapa para escolher o ponto. Arraste para posicionar o alfinete." }
        frame.addView(m, FrameLayout.LayoutParams(-1, -1))
        frame.addView(a.text("📍", TS.H1) { textSize = 40f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { bottomMargin = a.dp(6) })
        frame.addView(a.vbox(12, 8) {
            add(banner("Arraste o mapa até o alfinete ficar sobre o local", "Os outros tutores verão só um círculo aproximado, nunca este ponto.", Tone.INFO, "🔒"))
            add(a.button("Usar este ponto") { onPicked(m.centerLat, m.centerLon); a.pop() })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = a.dp(14) })
        return frame
    }
}

/** Cadastro de novo local ou avaliação de um existente. */
class PlaceFormScreen(a: MainActivity, private val placeId: String?, private val lat: Double, private val lon: Double) : Screen(a) {
    override val title = if (placeId == null) "Novo local" else "Avaliar local"
    override val liveUpdates = false
    private val prev = placeId?.let { a.repo.myReview(it) }
    private val cons = placeId?.let { id -> PlaceRules.consensus(a.repo.state.reviews.filter { it.placeId == id }) }
    private var name = ""
    private var kind = PlaceKind.SQUARE
    private var fencing: Fencing? = prev?.fencing ?: cons?.fencing
    private var floor: Floor? = prev?.floor ?: cons?.floor
    private var amenities: Set<Amenity> = prev?.amenities ?: cons?.let { c -> Amenity.entries.filter { c.confirmed(it) }.toSet() } ?: emptySet()
    private var shade: Set<Int> = placeId?.let { a.repo.state.place(it)?.shadeHours } ?: emptySet()

    override fun render(): View = a.page {
        if (placeId == null) {
            add(banner("Local no ponto marcado", "Lat ${"%.5f".format(lat)}, Lon ${"%.5f".format(lon)}. Para ajustar, volte ao mapa e mantenha o dedo pressionado no ponto exato.", Tone.INFO, "📍"))
            add(field("Nome do local", name, "Ex.: Gramado da SQS 308", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name = it })
            add(text("Tipo", TS.LABEL))
            add(a.singleChoice(PlaceKind.entries, kind, { it.label }) { kind = it })
        } else add(text(s.place(placeId)?.name ?: "", TS.H2))
        add(section("Segurança e cercamento"))
        add(a.singleChoice(Fencing.entries, fencing, { "${it.label} — ${it.detail}" }) { fencing = it })
        add(section("Tipo de piso"))
        add(a.singleChoice(Floor.entries, floor, { it.label }) { floor = it })
        add(section("Infraestrutura", "Marque o que existe e funciona."))
        add(a.multiChoice(Amenity.entries, amenities, { it.label }) { amenities = it })
        add(section("Sombra contínua", "Em quais horários a área principal fica na sombra?"))
        add(a.multiChoice((6..18).toList(), shade, { "${it}h" }) { shade = it })
        add(a.button(if (placeId == null) "Cadastrar local" else "Salvar avaliação") { save() })
    }

    private fun save() {
        val f = fencing ?: return a.toast("Escolha o tipo de cercamento.")
        val fl = floor ?: return a.toast("Escolha o tipo de piso.")
        if (placeId == null) a.handle(repo.addPlace(name, kind, lat, lon, f, fl, amenities, shade), "Local cadastrado!") { a.replaceTop(PlaceScreen(a, it.id)) }
        else a.handle(repo.reviewPlace(placeId, f, fl, amenities, shade), "Avaliação salva. Obrigado!") { a.pop() }
    }
}

fun publicEventCard(a: MainActivity, s: AppState, e: PublicEvent, now: Long): View = a.clickableCard({ a.show(PublicEventScreen(a, e.id)) }) {
    add(a.text("📣 ${e.title}", TS.H3))
    add(a.text("${Fmt.relativeDay(e.startsAt, now)} · ${Fmt.duration(e.durationMin)} · ${s.place(e.placeId)?.name ?: ""}", TS.SMALL, C.textMuted))
    add(a.hbox(6) {
        add(a.badge(e.theme.label, Tone.PRIMARY))
        add(a.badge(Fmt.plural(e.attendees.size, "confirmado", "confirmados"), Tone.NEUTRAL))
        if (s.meId in e.attendees) add(a.badge("✓ Você vai", Tone.OK))
    })
}

class PublicEventScreen(a: MainActivity, private val eventId: String) : Screen(a) {
    override val title = "Evento público"
    override fun render(): View = a.page {
        val e = s.publicEvents.firstOrNull { it.id == eventId } ?: run { add(a.emptyState("📣", "Evento não encontrado", "Ele pode ter sido cancelado.")); return@page }
        val p = s.place(e.placeId)
        add(text(e.title, TS.H1))
        add(a.hbox(6) { add(badge(e.theme.label, Tone.PRIMARY)); if (e.startsAt <= now) add(badge("Acontecendo agora", Tone.OK)) })
        add(card {
            add(text("🗓 ${Fmt.relativeDay(e.startsAt, now)} (${Fmt.duration(e.durationMin)})", TS.BODY))
            add(text("📍 ${p?.name ?: "Local removido"}", TS.BODY))
            add(text("👤 Criado por ${if (e.creatorId == s.meId) "você" else s.user(e.creatorId)?.name ?: "?"}", TS.SMALL, C.textMuted))
            add(text(Fmt.plural(e.attendees.size, "tutor confirmado", "tutores confirmados"), TS.SMALL, C.textMuted))
        })
        if (e.description.isNotBlank()) add(card { add(text(e.description, TS.BODY)) })
        if (e.creatorId == s.meId) add(a.button("Cancelar evento", BtnKind.DANGER) { a.confirm("Cancelar evento?", "Ele some do mapa para todos.", "Cancelar evento") { repo.cancelPublicEvent(e.id); a.pop() } })
        else add(a.button(if (s.meId in e.attendees) "Não vou mais" else "Eu vou!", if (s.meId in e.attendees) BtnKind.SECONDARY else BtnKind.PRIMARY) { repo.togglePublicAttendance(e.id) })
        if (p != null) add(a.button("Ver local", BtnKind.GHOST) { a.show(PlaceScreen(a, p.id)) })
    }
}

class PublicEventFormScreen(a: MainActivity, initialPlace: String?) : Screen(a) {
    override val title = "Criar evento público"
    override val liveUpdates = false
    private var placeId = initialPlace
    private var titleTxt = ""
    private var theme = EventTheme.BREED_MEETUP
    private var startsAt = Fmt.atTime(Repository.addDays(Repository.startOfDay(a.repo.now()), 1), 10 * 60)
    private var duration = 90
    private var desc = ""

    override fun render(): View = a.page {
        add(text("Qualquer tutor pode criar um marcador de evento público em um local.", TS.SMALL, C.textMuted))
        add(a.valueButton("Local", placeId?.let { s.place(it)?.name } ?: "Escolher local") {
            val ps = s.places.sortedBy { it.name }
            AlertDialog.Builder(a).setTitle("Escolha o local").setItems(ps.map { it.name }.toTypedArray()) { _, i -> placeId = ps[i].id; a.renderNow(false) }.show()
        })
        add(text("Tema", TS.LABEL))
        add(a.singleChoice(EventTheme.entries, theme, { it.label }) { theme = it })
        add(field("Título", titleTxt, "Ex.: Encontro de Salsichinhas") { titleTxt = it })
        add(text("Sugestões: \"Encontro de Salsichinhas\", \"Cãominhada de Cães Idosos (ritmo lento)\", \"Sessão de Socialização de Filhotes\".", TS.SMALL, C.textMuted))
        add(a.valueButton("Data e hora", Fmt.dateTime(startsAt)) { a.pickDateTime(startsAt) { startsAt = it; a.renderNow(false) } })
        add(text("Duração", TS.LABEL))
        add(a.singleChoice(listOf(30, 60, 90, 120, 180), duration, { Fmt.duration(it) }) { duration = it })
        add(field("Descrição (opcional)", desc, "Ritmo, regras, o que levar…", lines = 3) { desc = it })
        add(a.button("Publicar evento") {
            val pid = placeId ?: return@button a.toast("Escolha o local.")
            a.handle(repo.createPublicEvent(pid, titleTxt, theme, startsAt, duration, desc), "Evento publicado!") { a.replaceTop(PublicEventScreen(a, it.id)) }
        })
    }
}
