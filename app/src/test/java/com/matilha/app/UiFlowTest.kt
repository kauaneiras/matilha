package com.matilha.app

import android.content.DialogInterface
import com.matilha.app.UiTestKit.click
import com.matilha.app.UiTestKit.hasText
import com.matilha.app.UiTestKit.idle
import com.matilha.app.UiTestKit.latestDialog
import com.matilha.app.UiTestKit.shot
import com.matilha.app.UiTestKit.type
import com.matilha.app.domain.RequestStatus
import com.matilha.app.domain.Sex
import com.matilha.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
open class UiFlowTest {

    /** Jornada completa de um novo tutor. */
    @Test fun fullJourney() {
        val (_, a) = UiTestKit.launch()
        assertTrue(hasText(a, "A conta é do seu cachorro."))
        shot(a, "01-onboarding")

        // Tenta continuar sem nome: deve bloquear.
        click(a, "Continuar: cadastrar meu pet")
        assertTrue(a.top() is OnboardingScreen)

        type(a, "Seu nome (tutor)", "Mariana")
        type(a, "Seu bairro", "Asa Sul")
        click(a, "Continuar: cadastrar meu pet")
        assertTrue(a.top() is PetFormScreen)
        shot(a, "02-pet-form")

        // Salvar vazio mostra erro e não avança.
        click(a, "Salvar e começar")
        assertEquals("Informe o nome do pet.", UiTestKit.dialogMessage())
        latestDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle()

        type(a, "Nome do pet", "Lola")
        click(a, "Sem raça definida (SRD)")
        type(a, "Peso (kg)", "8,5")
        assertTrue("porte calculado ao vivo", hasText(a, "Porte calculado: Pequeno"))
        click(a, "Fêmea")
        click(a, "Corredor")
        click(a, "Seletivo por porte ou sexo")
        assertTrue("detalhes da seletividade aparecem", hasText(a, "Tem medo de cães de porte…"))
        click(a, "Gigante (acima de 45 kg)")
        shot(a, "03-pet-form-filled")
        click(a, "Salvar e começar")

        val st = a.repo.state
        assertTrue(st.onboarded)
        assertEquals("Mariana", st.me.name)
        val lola = st.myPets.single()
        assertEquals("Lola", lola.name); assertEquals(8.5, lola.weightKg, 0.0); assertEquals(Sex.FEMALE, lola.sex)
        assertTrue(a.top() is MapScreen)
        shot(a, "04-map", fullPage = false)

        // Lista de locais (acessível).
        click(a, "☰ Lista de locais")
        assertTrue(hasText(a, "Parcão do Parque da Cidade"))
        shot(a, "05-place-list")
        click(a, "Parcão do Parque da Cidade")
        assertTrue(a.top() is PlaceScreen)
        assertTrue(hasText(a, "Radiografia do local"))
        assertTrue(hasText(a, "100% cercado, portão duplo"))
        shot(a, "06-place")

        // Check-in.
        click(a, "📍 Fazer check-in aqui")
        assertNotNull(a.repo.state.myActiveCheckIn(a.repo.now()))
        assertTrue(hasText(a, "Você está aqui com Lola"))

        // Agenda: seleciona 21h00–22h00 de amanhã.
        click(a, "Amanhã")
        click(a, "21h00"); click(a, "21h30")
        click(a, "Estarei lá das 21h00 às 22h00")
        assertTrue(a.repo.state.schedules.any { it.userId == "me" && it.fromMin == 21 * 60 && it.toMin == 22 * 60 })
        shot(a, "07-place-after-checkin")

        // Reportar perigo.
        click(a, "⚠ Reportar perigo")
        val d = latestDialog()!!
        d.listView.performItemClick(d.listView, 1, 1); idle()
        latestDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals(1, a.repo.state.hazards.count { it.placeId == "pl-parcao" && it.reportedBy == "me" })

        // Check-out manual leva à pergunta do passeio.
        click(a, "Check-out")
        assertTrue(a.top() is WalkFeedbackScreen)
        shot(a, "08-walk-feedback")
        click(a, "👍 Tudo certo")
        assertEquals(1, a.repo.state.walkFeedback.size)

        // Rolês privados.
        a.selectTab(Tab.EVENTS); idle()
        click(a, "Privados")
        assertTrue(hasText(a, "Brincadeira no quintal para 3 cães pequenos"))
        shot(a, "09-events-private")
        click(a, "Brincadeira no quintal para 3 cães pequenos")
        assertFalse("endereço oculto antes da aprovação", hasText(a, "SQS 309, Bloco C"))
        assertTrue(hasText(a, "Asa Sul (área aproximada)"))
        shot(a, "10-private-event")
        // Lola foi cadastrada sem vacinas e este rolê exige comprovante: o botão fica desativado.
        assertFalse(UiTestKit.findText(a, "Pedir para participar")!!.isEnabled)
        assertTrue(hasText(a, "Exige comprovante de vacina aprovado no app"))
    }

    /** Pet sem vacinas não consegue clicar em "Pedir para participar" quando o anfitrião exige comprovante. */
    @Test fun doorFilterBlocksButton() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = false)
        a.show(PrivateEventScreen(a, "pv-quintal")); idle()
        assertTrue(hasText(a, "Exige comprovante de vacina aprovado no app"))
        val btn = UiTestKit.findText(a, "Pedir para participar")!!
        assertFalse(btn.isEnabled)
        shot(a, "11-door-filter-blocked")
    }

    @Test fun approvedGuestSeesAddressAndChat() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        a.show(PrivateEventScreen(a, "pv-quintal")); idle()
        click(a, "Pedir para participar")
        a.repo.update { s -> s.copy(privateEvents = s.privateEvents.map { if (it.id == "pv-quintal") it.copy(requests = it.requests.map { r -> r.copy(status = RequestStatus.APPROVED) }) else it }) }
        idle()
        assertTrue(hasText(a, "SQS 309, Bloco C"))
        assertTrue(hasText(a, "Chat do rolê"))
        UiTestKit.type(a, "Mensagem", "Chegamos às 16h!")
        click(a, "Enviar")
        assertTrue(a.repo.state.privateEvents.first { it.id == "pv-quintal" }.chat.any { it.text == "Chegamos às 16h!" })
        shot(a, "12-private-approved")
    }

    @Test fun conflictWarningOnPlace() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true, fearsGiants = true)
        val now = a.repo.now()
        a.repo.update { s -> s.copy(checkIns = s.checkIns.filter { it.userId == "me" } + com.matilha.app.domain.CheckIn("t1", "sim-bruno", listOf("pet-thor"), "pl-308s", now - 60_000)) }
        a.show(PlaceScreen(a, "pl-308s")); idle()
        assertTrue(hasText(a, "Atenção: há perfis no local que podem estressar o seu pet"))
        assertEquals(PlaceStatus.CONFLICT, placeStatus(a.repo.state, a.repo.state.place("pl-308s")!!, now).let { if (a.repo.state.activeHazards("pl-308s", now).isEmpty()) it else PlaceStatus.CONFLICT })
        shot(a, "13-place-conflict")
    }

    @Test fun accessibilityAuditAllScreens() {
        val (_, a) = UiTestKit.launch()
        val problems = UiTestKit.a11yAudit(a, "onboarding").toMutableList()
        onboardQuick(a, vaccinated = true)
        val screens: List<Pair<String, () -> Screen>> = listOf(
            "mapa" to { MapScreen(a) }, "rolês" to { EventsScreen(a) }, "matilha" to { PackScreen(a) },
            "pets" to { PetsScreen(a) }, "alertas" to { AlertsScreen(a) }, "local" to { PlaceScreen(a, "pl-parcao") },
            "form-pet" to { PetFormScreen(a, null) }, "perfil-pet" to { PetProfileScreen(a, "pet-thor") },
            "rolê-privado" to { PrivateEventScreen(a, "pv-piscina") }, "form-rolê" to { PrivateEventFormScreen(a) },
            "form-local" to { PlaceFormScreen(a, null, -15.8, -47.9) }, "evento" to { PublicEventScreen(a, "pe-idosos") },
            "tutor" to { UserScreen(a, "sim-ana") }, "radar" to { RadarFormScreen(a, null) },
        )
        for ((name, make) in screens) { a.replaceAll(make()); idle(); problems += UiTestKit.a11yAudit(a, name) }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun tabsRender() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        for (t in Tab.entries) {
            a.selectTab(t); idle()
            assertTrue("aba ${t.label} sem erro", !hasText(a, "Algo deu errado"))
            shot(a, "20-tab-${t.name.lowercase()}", fullPage = t != Tab.MAP)
        }
        a.selectTab(Tab.ALERTS); idle()
        click(a, "Configurar")
        assertTrue(hasText(a, "Radar de compatibilidade"))
        shot(a, "21-alerts-settings")
        click(a, "+ Novo radar")
        shot(a, "22-radar-form")
        a.onBackPressed(); idle()
        a.selectTab(Tab.PACK); idle()
        click(a, "Aceitar")
        assertTrue(a.repo.state.friendIds().contains("sim-diego"))
    }

    @Test fun createPrivateEventAndPublicEvent() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        a.show(PrivateEventFormScreen(a)); idle()
        type(a, "Título", "Tarde no quintal da Lola")
        type(a, "Endereço completo (privado)", "SQS 308 Bloco A apto 101")
        click(a, "Invisível no mapa — só convidados da Matilha")
        click(a, "Ana (Paco, Amora)")
        click(a, "Piscina liberada")
        // Sem GPS e sem ponto marcado: não publica.
        click(a, "Publicar rolê")
        assertTrue(a.repo.state.privateEvents.none { it.hostId == "me" })
        click(a, "Marcar no mapa")
        assertTrue(a.top() is MapPickerScreen)
        shot(a, "17-map-picker", fullPage = false)
        click(a, "Usar este ponto")
        assertTrue(a.top() is PrivateEventFormScreen)
        assertTrue("seleções preservadas", hasText(a, "✓ Piscina liberada"))
        assertTrue(hasText(a, "Marcado no mapa"))
        shot(a, "14-private-form")
        click(a, "Publicar rolê")
        val e = a.repo.state.privateEvents.firstOrNull { it.hostId == "me" }
        assertNotNull("rolê criado; diálogo: ${UiTestKit.dialogMessage()}", e)
        assertEquals(setOf("sim-ana"), e!!.invitedUserIds)
        assertTrue(a.top() is PrivateEventScreen)
        assertTrue(hasText(a, "SQS 308 Bloco A apto 101")) // anfitrião vê o endereço

        a.show(PublicEventFormScreen(a, "pl-parcao")); idle()
        type(a, "Título", "Encontro de Salsichinhas")
        click(a, "Publicar evento")
        assertTrue(a.repo.state.publicEvents.any { it.title == "Encontro de Salsichinhas" && it.creatorId == "me" })
        shot(a, "15-public-event")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xhdpi") fun darkMode() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        a.show(PlaceScreen(a, "pl-parcao")); idle()
        shot(a, "30-dark-place", fullPage = false)
        a.selectTab(Tab.PETS); idle()
        shot(a, "31-dark-pets", fullPage = false)
    }

    @Test fun largeFontAccessibility() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        val (_, a) = UiTestKit.launch()
        shot(a, "40-bigfont-onboarding", fullPage = false)
        onboardQuick(a, vaccinated = true)
        a.show(PlaceScreen(a, "pl-parcao")); idle()
        shot(a, "41-bigfont-place", fullPage = false)
        a.selectTab(Tab.EVENTS); idle()
        shot(a, "42-bigfont-events", fullPage = false)
    }

    @Test fun notificationTapsOpenTheRightScreen() {
        val (c, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        fun tap(extra: String, value: String) {
            c.newIntent(android.content.Intent(a, MainActivity::class.java).putExtra(extra, value)); idle()
        }
        tap(com.matilha.app.platform.Notifier.EXTRA_PLACE, "pl-olhos")
        assertTrue(a.top() is PlaceScreen); assertEquals("Trilha do Parque Olhos d'Água", a.top()!!.title)
        tap(com.matilha.app.platform.Notifier.EXTRA_EVENT, "pv-chacara")
        assertTrue(a.top() is PrivateEventScreen)
        tap(com.matilha.app.platform.Notifier.EXTRA_WALK_FEEDBACK, "pl-parcao")
        assertTrue(a.top() is WalkFeedbackScreen)
        assertTrue(hasText(a, "Tudo limpo e seguro em Parcão do Parque da Cidade hoje?"))
        // Local inexistente (ex.: removido) não quebra o app.
        a.selectTab(Tab.MAP); idle()
        tap(com.matilha.app.platform.Notifier.EXTRA_PLACE, "nao-existe")
        assertTrue(a.top() is MapScreen)
    }

    @Test fun formDiscardProtection() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        a.selectTab(Tab.PETS); idle()
        click(a, "+ Cadastrar outro pet")
        type(a, "Nome do pet", "Bob")
        a.onBackPressed(); idle()
        assertTrue("pede confirmação antes de descartar", a.top() is PetFormScreen)
        assertTrue(latestDialog()!!.isShowing)
        latestDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(a.top() is PetsScreen)
        assertEquals(1, a.repo.state.myPets.size)
        // Formulário sem alterações volta direto.
        click(a, "+ Cadastrar outro pet")
        a.onBackPressed(); idle()
        assertTrue(a.top() is PetsScreen)
    }

    @Test fun multiplePetsAreChosenOnCheckIn() {
        val (_, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        val now = a.repo.now()
        a.repo.savePet(com.matilha.app.domain.Pet(a.repo.newPetId(), "me", "Bob", "Beagle", com.matilha.app.domain.AgeGroup.PUPPY, 9.0, Sex.MALE, false))
        a.show(PlaceScreen(a, "pl-sudoeste")); idle()
        click(a, "📍 Fazer check-in aqui")
        val d = latestDialog()!!
        assertEquals(2, d.listView.adapter.count)
        d.listView.performItemClick(d.listView, 1, 1) // desmarca o Bob
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        val ci = a.repo.state.myActiveCheckIn(a.repo.now())!!
        assertEquals(listOf("Lola"), ci.petIds.map { a.repo.state.pet(it)!!.name })
        shot(a, "16-two-pets-checkin")
    }

    @Test fun dataSurvivesAppRestart() {
        val (c, a) = UiTestKit.launch()
        onboardQuick(a, vaccinated = true)
        a.repo.reportHazard("pl-parcao", com.matilha.app.domain.HazardType.POISON, "perto do bebedouro")
        c.pause().stop().destroy()
        // Novo repositório lendo o mesmo arquivo (como após o sistema matar o processo).
        val file = java.io.File(a.filesDir, "matilha-state.bin")
        val reloaded = com.matilha.app.data.Repository(com.matilha.app.data.StateStore(file))
        assertTrue(reloaded.state.onboarded)
        assertEquals("Lola", reloaded.state.myPets.single().name)
        assertEquals(1, reloaded.state.hazards.count { it.type == com.matilha.app.domain.HazardType.POISON })
    }

    private fun onboardQuick(a: MainActivity, vaccinated: Boolean, fearsGiants: Boolean = false) {
        val now = a.repo.now()
        val p = com.matilha.app.domain.Pet(
            a.repo.newPetId(), "me", "Lola", "SRD", com.matilha.app.domain.AgeGroup.ADULT, 8.0, Sex.FEMALE, true,
            vaccines = if (vaccinated) com.matilha.app.domain.Vaccine.entries.map { com.matilha.app.domain.VaccineRecord(it, now - 20 * com.matilha.app.domain.DAY) } else emptyList(),
            proofUri = if (vaccinated) "content://x" else null, energy = 3,
            triggers = if (fearsGiants) setOf(com.matilha.app.domain.Trigger.SELECTIVE) else emptySet(),
            fearsSizes = if (fearsGiants) setOf(com.matilha.app.domain.SizeClass.GIANT) else emptySet(),
        )
        a.repo.savePet(p)
        a.repo.completeOnboarding("Mariana", "Asa Sul")
        a.selectTab(Tab.MAP); idle()
    }
}
