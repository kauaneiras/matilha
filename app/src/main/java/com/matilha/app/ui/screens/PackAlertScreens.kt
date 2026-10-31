package com.matilha.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import com.matilha.app.MainActivity
import com.matilha.app.Screen
import com.matilha.app.Tab
import com.matilha.app.data.Fmt
import com.matilha.app.data.Repository
import com.matilha.app.domain.*
import com.matilha.app.page
import com.matilha.app.platform.Geofence
import com.matilha.app.platform.LocationTracker
import com.matilha.app.platform.MatilhaApp
import com.matilha.app.platform.Notifier
import com.matilha.app.platform.Perms
import com.matilha.app.ui.*

class PackScreen(a: MainActivity) : Screen(a) {
    override val title = "Matilha"
    override val tab = Tab.PACK

    override fun render(): View = a.page {
        add(text("Tutores de confiança. Convide-os para rolês invisíveis e receba alertas dos melhores amigos.", TS.SMALL, C.textMuted))

        // Pergunta invisível pós-encontro
        if (s.trustPrompts.isNotEmpty()) {
            add(section("Pergunta rápida", "Ajuda a manter a comunidade segura. Só a comunidade agregada vê."))
            s.trustPrompts.forEach { tp ->
                val p = s.pet(tp.aboutPetId) ?: return@forEach
                add(card {
                    add(text("O perfil de ${p.name} condiz com a realidade?", TS.H3))
                    add(text("${tp.context} · ${petLine(p)}", TS.SMALL, C.textMuted))
                    add(hbox(8) {
                        addWeighted(a.button("Sim") { a.handle(repo.answerTrust(tp.id, true, ""), "Obrigado!") })
                        addWeighted(a.button("Não", BtnKind.SECONDARY) { trustDialog(a, p, tp.context, tp.id) })
                        addWeighted(a.button("Pular", BtnKind.GHOST) { repo.dismissTrust(tp.id) })
                    })
                })
            }
        }

        val incoming = s.friendships.filter { !it.accepted && it.userB == s.meId }
        if (incoming.isNotEmpty()) {
            add(section("Pedidos recebidos"))
            incoming.forEach { f ->
                val u = s.user(f.userA) ?: return@forEach
                add(card {
                    add(text(u.name, TS.H3))
                    add(text("Tutor de ${s.petNames(s.petsOf(u.id).map { it.id })}", TS.SMALL, C.textMuted))
                    add(hbox(8) {
                        addWeighted(a.button("Aceitar") { a.handle(repo.answerFriend(u.id, true), "${u.name} entrou na sua Matilha!") })
                        addWeighted(a.button("Ver perfil", BtnKind.SECONDARY) { a.show(UserScreen(a, u.id)) })
                        addWeighted(a.button("Recusar", BtnKind.GHOST) { a.handle(repo.answerFriend(u.id, false)) })
                    })
                })
            }
        }

        val friends = s.friendIds().mapNotNull { s.user(it) }.sortedWith(compareByDescending<com.matilha.app.domain.User> { s.isBestFriend(it.id) }.thenBy { it.name })
        add(section("Sua Matilha (${friends.size})", "★ = melhor amigo: alertas de check-in e agenda."))
        if (friends.isEmpty()) add(a.emptyState("🐾", "Matilha vazia", "Se os cães brincaram bem no parque, adicionem um ao outro pela tela do local ou do pet."))
        friends.forEach { u ->
            val now0 = now
            val here = s.activeCheckIns(now0).firstOrNull { it.userId == u.id }
            add(a.clickableCard({ a.show(UserScreen(a, u.id)) }) {
                add(hbox(8) {
                    addWeighted(text(u.name, TS.H3))
                    add(a.text(if (s.isBestFriend(u.id)) "★" else "☆", TS.H2, C.accent) {
                        contentDescription = if (s.isBestFriend(u.id)) "Remover dos melhores amigos" else "Marcar como melhor amigo"
                        minWidth = dp(48); minHeight = dp(48); gravity = android.view.Gravity.CENTER
                        isClickable = true; isFocusable = true
                        setOnClickListener { repo.setBestFriend(u.id, !s.isBestFriend(u.id)) }
                    })
                })
                add(text("Pets: ${s.petNames(s.petsOf(u.id).map { it.id })}", TS.SMALL, C.textMuted))
                if (here != null) add(badge("📍 Agora em ${s.place(here.placeId)?.name}", Tone.OK))
            })
        }

        val pendingOut = s.friendships.filter { !it.accepted && it.userA == s.meId }
        if (pendingOut.isNotEmpty()) {
            add(section("Pedidos enviados"))
            pendingOut.forEach { f -> add(text("⏳ ${s.user(f.userB)?.name ?: "?"} — aguardando", TS.SMALL)) }
        }

        // Sugestões: tutores que estiveram nos mesmos locais que você.
        val myPlaces = s.checkIns.filter { it.userId == s.meId }.map { it.placeId }.toSet()
        val sugg = s.checkIns.filter { it.placeId in myPlaces && it.userId != s.meId && s.friendStatus(it.userId) == null }.map { it.userId }.distinct().take(5)
        if (sugg.isNotEmpty()) {
            add(section("Encontrou no parque", "Tutores que estiveram nos mesmos locais que você."))
            sugg.forEach { uid ->
                val u = s.user(uid) ?: return@forEach
                add(card(12, 6) {
                    add(text("${u.name} · ${s.petNames(s.petsOf(uid).map { it.id })}", TS.LABEL))
                    add(hbox(8) {
                        addWeighted(a.button("+ Matilha") { a.handle(repo.requestFriend(uid), "Pedido enviado") })
                        addWeighted(a.button("Ver", BtnKind.SECONDARY) { a.show(UserScreen(a, uid)) })
                    })
                })
            }
        }
    }
}

class AlertsScreen(a: MainActivity) : Screen(a) {
    override val title = "Alertas"
    override val tab = Tab.ALERTS
    private var seg = 0

    override fun onShown() { if (seg == 0) repo.markNotificationsRead() }

    override fun render(): View = a.page {
        add(a.singleChoice(listOf(0, 1), seg, { if (it == 0) "Notificações" else "Configurar" }) { seg = it; if (it == 0) repo.markNotificationsRead(); a.renderNow(true) })
        if (seg == 0) renderList() else renderSettings()
    }

    private fun android.widget.LinearLayout.renderList() {
        if (!Perms.notifications(a)) add(vbox(spacingDp = 8) {
            add(banner("Notificações desligadas", "Sem permissão, você só vê os alertas aqui dentro do app.", Tone.WARN))
            add(a.button("Permitir notificações") { a.requestNotifications() })
        })
        if (s.notifications.isEmpty()) add(a.emptyState("🔔", "Nenhum alerta ainda", "Você será avisado quando melhores amigos chegarem, quando o radar encontrar cães compatíveis e sobre perigos por perto."))
        s.notifications.forEach { n ->
            val click: () -> Unit = {
                when {
                    n.placeId != null && s.place(n.placeId) != null -> a.show(PlaceScreen(a, n.placeId))
                    n.eventId != null -> a.show(PrivateEventScreen(a, n.eventId))
                    n.title.contains("Matilha") -> a.selectTab(Tab.PACK)
                }
            }
            add(a.clickableCard(click, 14) {
                add(text(n.title, TS.H3))
                add(text(n.body, TS.BODY))
                add(text(Fmt.ago(n.at, now), TS.SMALL, C.textMuted))
            })
        }
        if (s.notifications.isNotEmpty()) add(a.button("Limpar notificações", BtnKind.GHOST) { repo.clearNotifications() })
    }

    private fun android.widget.LinearLayout.renderSettings() {
        val st = s.alertSettings
        add(section("Permissões"))
        add(card {
            add(permRow("Localização", Perms.location(a), "Mapa, distância e radar.") { a.requestLocation() })
            add(permRow("Localização em segundo plano", Perms.background(a) || Build.VERSION.SDK_INT < 29, "Necessária para o check-out automático com o app fechado. Escolha \"Permitir o tempo todo\".") {
                if (!Perms.location(a)) a.requestLocation()
                else a.requestPerms(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { ok ->
                    if (!ok) a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
                    MatilhaApp.io.execute { Geofence.sync(a) }
                }
            })
            add(permRow("Notificações", Perms.notifications(a), "Alertas fora do app.") { a.requestNotifications() })
            if (Perms.location(a) && !LocationTracker.providersEnabled(a)) add(banner("GPS desligado", "Ligue a localização do aparelho.", Tone.WARN))
        })

        add(section("Melhores amigos"))
        add(card {
            add(a.switchRow("Check-in de melhor amigo", "\"O Paco acabou de fazer check-in a 400 m de você!\"", st.bestFriendAlerts) { repo.saveAlertSettings(s.alertSettings.copy(bestFriendAlerts = it)) })
            add(text("Distância máxima", TS.LABEL))
            add(a.singleChoice(listOf(500.0, 1000.0, 1500.0, 3000.0, 10000.0), st.bestFriendRadiusM, { Geo.formatDistance(it) }) { repo.saveAlertSettings(s.alertSettings.copy(bestFriendRadiusM = it)) })
            add(a.switchRow("Agenda de melhor amigo", "\"O tutor do Paco agendou presença no Parcão hoje às 18h\"", st.friendScheduleAlerts) { repo.saveAlertSettings(s.alertSettings.copy(friendScheduleAlerts = it)) })
        })

        add(section("Segurança e passeio"))
        add(card {
            add(a.switchRow("Check-out automático", "Ao se afastar mais de 150 m do local por mais de 5 min, o check-in é encerrado e perguntamos como foi o passeio.", st.autoCheckout) {
                repo.saveAlertSettings(s.alertSettings.copy(autoCheckout = it)); MatilhaApp.io.execute { Geofence.sync(a) }
            })
            add(a.switchRow("Perigos por perto", "Novos alertas a até 2 km (vidro, veneno, cão agressivo…).", st.hazardAlertsNearby) { repo.saveAlertSettings(s.alertSettings.copy(hazardAlertsNearby = it)) })
        })

        add(section("Radar de compatibilidade", "Seja avisado quando a turma certa aparecer."))
        if (s.myLat == null) add(banner("O radar precisa da sua localização", "Sem ela, não dá para saber o que está perto.", Tone.INFO))
        s.radarRules.forEach { r ->
            add(card {
                add(hbox(8) {
                    addWeighted(text(r.name, TS.H3))
                    add(badge(if (r.enabled) "Ativo" else "Pausado", if (r.enabled) Tone.OK else Tone.NEUTRAL))
                })
                add(text(Radar.describe(r), TS.SMALL))
                add(hbox(8) {
                    addWeighted(a.button(if (r.enabled) "Pausar" else "Ativar", BtnKind.SECONDARY) { repo.saveRadarRule(r.copy(enabled = !r.enabled)) })
                    addWeighted(a.button("Editar", BtnKind.SECONDARY) { a.show(RadarFormScreen(a, r)) })
                    addWeighted(a.button("Excluir", BtnKind.GHOST) { a.confirm("Excluir radar?", r.name, "Excluir") { repo.deleteRadarRule(r.id) } })
                })
            })
        }
        add(a.button("+ Novo radar") { a.show(RadarFormScreen(a, null)) })
    }

    private fun permRow(name: String, granted: Boolean, desc: String, onAsk: () -> Unit): View = a.hbox(8) {
        addWeighted(a.vbox(spacingDp = 2) {
            add(a.text((if (granted) "✅ " else "⚪ ") + name, TS.LABEL))
            add(a.text(desc, TS.SMALL, C.textMuted))
        })
        if (!granted) add(a.button("Permitir", BtnKind.SECONDARY) { onAsk() })
    }
}

class RadarFormScreen(a: MainActivity, existing: RadarRule?) : Screen(a) {
    override val title = if (existing == null) "Novo radar" else "Editar radar"
    override val liveUpdates = false
    private var r = existing ?: RadarRule(a.repo.newRuleId(), "")

    override fun render(): View = a.page {
        add(field("Nome", r.name, "Ex.: Turma de corrida") { r = r.copy(name = it) })
        add(text("Quantidade mínima de cães", TS.LABEL))
        add(a.singleChoice(listOf(1, 2, 3, 4, 5), r.minDogs, { "$it+" }) { r = r.copy(minDogs = it) })
        add(text("Porte (vazio = qualquer)", TS.LABEL))
        add(a.multiChoice(SizeClass.entries, r.sizes, { it.label }) { r = r.copy(sizes = it) })
        add(a.switchRow("Somente castrados", null, r.neuteredOnly) { r = r.copy(neuteredOnly = it) })
        add(text("Estilo de brincadeira (vazio = qualquer)", TS.LABEL))
        add(a.multiChoice(PlayStyle.entries, r.playStyles, { it.label }) { r = r.copy(playStyles = it) })
        add(text("Distância de você", TS.LABEL))
        add(a.singleChoice(listOf(500.0, 1000.0, 2000.0, 3000.0, 5000.0), r.radiusM, { Geo.formatDistance(it) }) { r = r.copy(radiusM = it) })
        add(text("Janela de horário", TS.LABEL))
        val hours = (6..23).map { it * 60 }
        add(text("Das", TS.SMALL, C.textMuted))
        add(a.singleChoice(hours.dropLast(1), r.fromMin, { ScheduleRules.label(it) }) { r = r.copy(fromMin = it) })
        add(text("Até", TS.SMALL, C.textMuted))
        add(a.singleChoice(hours.drop(1), r.toMin, { ScheduleRules.label(it) }) { r = r.copy(toMin = it) })
        add(a.button("Salvar radar") { a.handle(repo.saveRadarRule(r.copy(name = r.name.trim())), "Radar salvo") { a.pop() } })
    }
}

/** "Como foi o passeio? Tudo limpo e seguro na praça hoje?" */
class WalkFeedbackScreen(a: MainActivity, private val placeId: String, private val auto: Boolean = false) : Screen(a) {
    override val title = "Como foi o passeio?"
    override val liveUpdates = false
    override fun render(): View = a.page {
        val p = s.place(placeId)
        Notifier.cancelWalk(a)
        if (auto) add(banner("Check-out automático", "Você se afastou de ${p?.name ?: "local"}, então encerramos seu check-in.", Tone.INFO, "📍"))
        add(text("Tudo limpo e seguro em ${p?.name ?: "local"} hoje?", TS.H2))
        add(a.button("👍 Tudo certo") { repo.submitWalkFeedback(placeId, true, ""); a.toast("Obrigado!"); next() })
        add(a.button("⚠ Encontrei um problema", BtnKind.WARN) {
            repo.submitWalkFeedback(placeId, false, "")
            if (p != null) a.replaceTop(PlaceScreen(a, p.id)).also { a.toast("Use \"Reportar perigo\" para avisar a comunidade.", long = true) } else a.pop()
        })
        add(a.button("Pular", BtnKind.GHOST) { next() })
        if (s.trustPrompts.isNotEmpty()) add(text("Você também tem ${Fmt.plural(s.trustPrompts.size, "pergunta rápida", "perguntas rápidas")} sobre cães que encontrou, na aba Matilha.", TS.SMALL, C.textMuted))
    }

    private fun next() { if (s.trustPrompts.isNotEmpty()) a.selectTab(Tab.PACK) else a.pop() }
}
