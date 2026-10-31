package com.matilha.app.ui.screens

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.matilha.app.MainActivity
import com.matilha.app.Screen
import com.matilha.app.Tab
import com.matilha.app.data.Fmt
import com.matilha.app.data.Result
import com.matilha.app.domain.*
import com.matilha.app.page
import com.matilha.app.ui.*

class OnboardingScreen(a: MainActivity) : Screen(a) {
    override val title = "Bem-vindo ao Matilha"
    override val liveUpdates = false
    private var name = ""
    private var hood = ""

    override fun render(): View = a.page {
        name = name.ifEmpty { s.me.name }; hood = hood.ifEmpty { s.me.neighborhood }
        add(text("🐾", TS.H1) { textSize = 48f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        add(text("A conta é do seu cachorro.", TS.H1))
        add(text("Você é o motorista e responsável legal. O perfil do pet (porte, energia, estilo de brincadeira e alertas) é o que faz o mapa e os filtros funcionarem com segurança.", TS.BODY, C.textMuted))
        add(card {
            add(text("Como funciona", TS.H3))
            add(text("🗺️  Mapa vivo: avaliações técnicas dos locais, alertas em tempo real e quem está lá agora.", TS.SMALL))
            add(text("🤫  Modo passeio solo: horários vazios e sombra para quem quer paz.", TS.SMALL))
            add(text("🏡  Rolês privados: quintais com filtros de porta e endereço oculto.", TS.SMALL))
            add(text("🔔  Alertas inteligentes e check-out automático.", TS.SMALL))
        })
        add(field("Seu nome (tutor)", name, "Ex.: Mariana", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name = it })
        add(field("Seu bairro", hood, "Ex.: Asa Sul", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { hood = it })
        add(text("O bairro ajuda nos alertas da região. Seu endereço nunca é mostrado.", TS.SMALL, C.textMuted))
        add(a.button("Continuar: cadastrar meu pet") {
            if (name.isBlank()) { a.toast("Informe seu nome."); return@button }
            repo.updateMe(name, hood)
            val existing = s.myPets.firstOrNull()
            a.show(PetFormScreen(a, existing?.id, onboarding = true))
        })
        add(text("Os tutores e cães de demonstração (Brasília) simulam a comunidade nesta versão.", TS.SMALL, C.textMuted))
    }
}

class PetsScreen(a: MainActivity) : Screen(a) {
    override val title = "Meus pets"
    override val tab = Tab.PETS

    override fun render(): View = a.page {
        add(card {
            add(hbox(8) {
                addWeighted(vbox(spacingDp = 2) {
                    add(text(s.me.name.ifBlank { "Tutor" }, TS.H3))
                    add(text(if (s.me.neighborhood.isBlank()) "Bairro não informado" else s.me.neighborhood, TS.SMALL, C.textMuted))
                })
                add(a.button("Editar", BtnKind.GHOST) { a.show(ProfileEditScreen(a)) })
            })
        })
        add(section("Pets (${s.myPets.size})", "Você pode cadastrar mais de um pet no mesmo perfil."))
        s.myPets.forEach { p -> add(a.petRow(p, s, now) { a.show(PetProfileScreen(a, p.id)) }) }
        add(a.button("+ Cadastrar outro pet") { a.show(PetFormScreen(a, null)) })
    }
}

class ProfileEditScreen(a: MainActivity) : Screen(a) {
    override val title = "Seu perfil"
    override val liveUpdates = false
    override fun render(): View = a.page {
        var name = s.me.name; var hood = s.me.neighborhood
        add(field("Seu nome", name, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { name = it })
        add(field("Bairro", hood, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { hood = it })
        add(a.button("Salvar") { a.handle(repo.updateMe(name, hood), "Perfil salvo") { a.pop() } })
    }
}

class PetFormScreen(a: MainActivity, private val petId: String?, private val onboarding: Boolean = false) : Screen(a) {
    override val title = if (petId == null) "Cadastrar pet" else "Editar pet"
    override val liveUpdates = false
    private var draft: Pet = (petId?.let { a.repo.state.pet(it) }) ?: Pet(
        id = a.repo.newPetId(), ownerId = a.repo.state.meId, name = "", breed = "", ageGroup = AgeGroup.ADULT,
        weightKg = Double.NaN, sex = Sex.MALE, neutered = true,
    )
    private var dirty = false
    private var weightText = if (draft.weightKg.isNaN()) "" else Fmt.kg(draft.weightKg).removeSuffix(" kg")

    override fun onBack(): Boolean {
        if (!dirty) return false
        a.confirm("Descartar alterações?", "As informações não salvas serão perdidas.", "Descartar") { dirty = false; a.pop() }
        return true
    }

    private fun change(block: (Pet) -> Pet) { draft = block(draft); dirty = true }

    override fun render(): View = a.page {
        if (onboarding) add(banner("Passo 2 de 2", "Quanto mais preciso o perfil, mais seguros ficam os encontros.", Tone.INFO))
        add(section("Identificação"))
        add(field("Nome do pet", draft.name, "Ex.: Paco", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { v -> change { it.copy(name = v) } })
        val breedField = field("Raça", draft.breed, "Ex.: Labrador ou SRD", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { v -> change { it.copy(breed = v) } }
        add(breedField)
        add(a.button("Sem raça definida (SRD)", BtnKind.GHOST) { breedField.editText().setText("SRD") })

        add(section("Biometria e porte", "A energia muda muito com a idade."))
        add(text("Idade", TS.LABEL))
        add(a.singleChoice(AgeGroup.entries, draft.ageGroup, { "${it.label} (${it.hint})" }) { v -> change { it.copy(ageGroup = v) } })
        val sizeLabel = text(sizeText(), TS.SMALL, C.primary)
        add(field("Peso (kg)", weightText, "Ex.: 12,5", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { v ->
            weightText = v
            change { it.copy(weightKg = Fmt.parseDecimal(v) ?: Double.NaN) }
            sizeLabel.text = sizeText()
        })
        add(sizeLabel)

        add(section("Status sanitário e reprodutivo"))
        add(text("Sexo", TS.LABEL))
        val heatBox = vbox(spacingDp = 6)
        fun refreshHeat() {
            heatBox.removeAllViews()
            if (draft.sex == Sex.FEMALE && !draft.neutered) {
                heatBox.add(a.switchRow("Está no cio agora", "Fêmeas no cio bloqueiam encontros com machos inteiros e eventos que proíbem cio.", draft.inHeat) { v -> change { it.copy(inHeat = v) } })
            }
        }
        add(a.singleChoice(Sex.entries, draft.sex, { it.label }) { v -> change { it.copy(sex = v) }; refreshHeat() })
        add(text("Castração", TS.LABEL))
        add(a.singleChoice(listOf(true, false), draft.neutered, { if (it) "Castrado(a)" else "Inteiro(a)" }) { v -> change { it.copy(neutered = v) }; refreshHeat() })
        refreshHeat()
        add(heatBox)

        add(section("Vacinas e antiparasitário", "Validade considerada: vacinas 12 meses, antipulgas/carrapatos 90 dias."))
        Vaccine.entries.forEach { v -> add(vaccineRow(v)) }
        add(ectoRow())
        add(proofCard())

        add(section("Bússola de energia"))
        val energyLabel = text(energyText(draft.energy), TS.BODY, C.primary)
        add(energyLabel)
        add(SeekBar(a).apply {
            max = 4; progress = draft.energy - 1
            contentDescription = "Energia de 1 a 5"
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    change { it.copy(energy = p + 1) }; energyLabel.text = energyText(p + 1)
                    if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = energyText(p + 1)
                    else contentDescription = "Energia: " + energyText(p + 1)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        add(section("Estilo de brincadeira", "Marque todos que se aplicam."))
        PlayStyle.entries.forEach { ps ->
            add(vbox(spacingDp = 2) {
                add(a.chip(ps.label, ps in draft.playStyles) { on -> change { it.copy(playStyles = if (on) it.playStyles + ps else it.playStyles - ps) } })
                add(text(ps.description, TS.SMALL, C.textMuted))
            })
        }

        add(section("Gatilhos e restrições", "Alertas de segurança mostrados a outros tutores."))
        val selectiveBox = vbox(spacingDp = 8)
        fun refreshSelective() {
            selectiveBox.removeAllViews()
            if (Trigger.SELECTIVE !in draft.triggers) return
            selectiveBox.add(text("Dá-se bem apenas com…", TS.LABEL))
            selectiveBox.add(a.singleChoice(listOf<Sex?>(null, Sex.FEMALE, Sex.MALE), draft.onlyWithSex, { when (it) { null -> "Indiferente"; Sex.FEMALE -> "Só fêmeas"; Sex.MALE -> "Só machos" } }) { v -> change { it.copy(onlyWithSex = v) } })
            selectiveBox.add(text("Tem medo de cães de porte…", TS.LABEL))
            selectiveBox.add(a.multiChoice(SizeClass.entries, draft.fearsSizes, { "${it.label} (${it.range})" }) { v -> change { it.copy(fearsSizes = v) } })
        }
        Trigger.entries.forEach { t ->
            add(vbox(spacingDp = 2) {
                add(a.chip(t.label, t in draft.triggers) { on -> change { it.copy(triggers = if (on) it.triggers + t else it.triggers - t) }; refreshSelective() })
                add(text(t.description, TS.SMALL, C.textMuted))
            })
        }
        refreshSelective()
        add(selectiveBox)

        add(field("Observações (opcional)", draft.notes, "Ex.: adora água, tem medo de bicicleta", lines = 3) { v -> change { it.copy(notes = v.take(300)) } })

        add(a.button(if (onboarding) "Salvar e começar" else "Salvar pet") { save() })
        if (petId != null && !onboarding) add(a.button("Remover pet", BtnKind.DANGER) {
            a.confirm("Remover ${draft.name}?", "Agendamentos e pedidos com este pet serão cancelados.", "Remover") {
                a.handle(repo.deletePet(draft.id), "Pet removido") { dirty = false; a.pop() }
            }
        })
    }

    private fun sizeText() = if (draft.weightKg.isNaN()) "Porte: informe o peso" else "Porte calculado: ${draft.size.label} (${draft.size.range})"
    private fun energyText(e: Int) = "$e/5 — ${Energy.of(e).label}"

    private fun vaccineRow(v: Vaccine): View {
        val holder = LinearLayout(a)
        fun build() {
            holder.removeAllViews()
            val l = draft.vaccines.filter { it.vaccine == v }.maxOfOrNull { it.appliedAt }
            val ok = l != null && now - l <= Vaccination.VALIDITY_MS
            val valTxt = if (l == null) "Informar data da última dose" else "Última dose: ${Fmt.date(l)}" + if (ok) " ✓" else " (vencida)"
            holder.addView(a.valueButton(v.label, valTxt) {
                a.pickDate(l, maxNow = true) { d -> change { p -> p.copy(vaccines = p.vaccines.filterNot { it.vaccine == v } + VaccineRecord(v, d)) }; build() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        build()
        return holder
    }

    private fun ectoRow(): View {
        val holder = LinearLayout(a)
        fun build() {
            holder.removeAllViews()
            val l = draft.ectoparasiteControlAt
            val txt = if (l == null) "Informar data da última aplicação" else "Última aplicação: ${Fmt.date(l)}" + if (now - l <= Vaccination.ECTO_VALIDITY_MS) " ✓" else " (vencida)"
            holder.addView(a.valueButton("Controle de pulgas/carrapatos", txt) {
                a.pickDate(l, maxNow = true) { d -> change { it.copy(ectoparasiteControlAt = d) }; build() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        build()
        return holder
    }

    private fun proofCard(): View {
        val box = a.card(spacingDp = 8) {}
        fun build() {
            box.removeAllViews()
            box.add(a.text("Comprovante de vacinação", TS.H3))
            val (st, msg) = if (draft.proofUri.isNullOrBlank()) ProofStatus.NONE to "Anexe uma foto da carteirinha. O selo \"Vacinação em dia\" exige as 4 vacinas válidas e o comprovante."
            else Vaccination.evaluateProof(draft, now)
            box.add(a.badge(st.label, when (st) { ProofStatus.APPROVED -> Tone.OK; ProofStatus.REJECTED -> Tone.DANGER; ProofStatus.PENDING -> Tone.INFO; else -> Tone.NEUTRAL }))
            box.add(a.text(msg, TS.SMALL, C.textMuted))
            box.add(a.button(if (draft.proofUri.isNullOrBlank()) "Anexar foto da carteirinha" else "Trocar foto", BtnKind.SECONDARY) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                a.startForResult(i) { data ->
                    val uri = data?.data ?: return@startForResult
                    try { a.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    change { it.copy(proofUri = uri.toString()) }
                    build()
                }
            })
            box.add(a.text("A verificação desta versão confere as datas informadas acima com a foto anexada.", TS.SMALL, C.textMuted))
        }
        build()
        return box
    }

    private fun save() {
        when (val r = repo.savePet(draft)) {
            is Result.Err -> a.handle(r)
            is Result.Ok -> {
                dirty = false
                if (onboarding) {
                    a.handle(repo.completeOnboarding(s.me.name, s.me.neighborhood)) {
                        a.toast("Tudo pronto, ${r.value.name}! 🐾")
                        a.requestLocation { a.requestNotifications() }
                        a.selectTab(Tab.MAP)
                    }
                } else {
                    a.toast("${r.value.name} salvo")
                    if (petId == null) a.replaceTop(PetProfileScreen(a, r.value.id)) else a.pop()
                }
            }
        }
    }
}

class PetProfileScreen(a: MainActivity, private val petId: String) : Screen(a) {
    override val title: String get() = s.pet(petId)?.name ?: "Pet"

    override fun render(): View = a.page {
        val p = s.pet(petId) ?: run { add(a.emptyState("🐾", "Pet não encontrado", "Ele pode ter sido removido.")); return@page }
        val mine = p.ownerId == s.meId
        add(a.petRow(p, s, now))
        if (p.underReview) add(banner("Perfil em revisão", "Tutores relataram que o comportamento não condiz com o perfil. Encontre com cautela.", Tone.DANGER))
        val score = Trust.score(p.id, s.trustFeedback)
        add(card {
            add(text("Confiança da comunidade", TS.H3))
            add(text(if (score.total == 0) "Ainda sem avaliações de encontros." else "${score.percent}% — ${score.positive} confirmaram o perfil, ${score.negative} relataram divergência.", TS.SMALL))
        })
        if (p.triggers.isNotEmpty() || p.playStyles.contains(PlayStyle.WRESTLER)) add(card {
            add(text("Antes de brincar, saiba:", TS.H3))
            if (PlayStyle.WRESTLER in p.playStyles) add(text("• Brinca de corpo a corpo (rola, dá trombadas, morde o pescoço de brincadeira).", TS.SMALL))
            p.triggers.forEach { t -> add(text("• ${t.label}: ${t.description}", TS.SMALL)) }
            p.onlyWithSex?.let { add(text("• Dá-se bem apenas com ${if (it == Sex.FEMALE) "fêmeas" else "machos"}.", TS.SMALL)) }
            if (p.fearsSizes.isNotEmpty()) add(text("• Tem medo de cães de porte ${p.fearsSizes.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase() }}.", TS.SMALL))
        })
        if (p.notes.isNotBlank()) add(card { add(text("Observações", TS.H3)); add(text(p.notes, TS.SMALL)) })
        if (mine) {
            val missing = Vaccination.missingOrExpired(p, now)
            if (missing.isNotEmpty()) add(banner("Vacinas pendentes", missing.joinToString { it.label } + ". Alguns rolês exigem o selo de vacinação.", Tone.WARN))
            add(a.button("Editar perfil de ${p.name}") { a.show(PetFormScreen(a, p.id)) })
        } else {
            val warns = Compatibility.warningsForGroup(s.myPets, listOf(p))
            if (warns.isNotEmpty()) {
                add(section("Compatibilidade com seus pets"))
                warns.forEach { w -> add(banner(w.message, null, compatTone(w.level))) }
            } else if (s.myPets.isNotEmpty()) add(banner("Nenhum conflito declarado com seus pets", null, Tone.OK))
            add(a.button("Ver tutor: ${s.user(p.ownerId)?.name ?: "?"}", BtnKind.SECONDARY) { a.show(UserScreen(a, p.ownerId)) })
            add(a.button("O perfil condiz com a realidade?", BtnKind.GHOST) { trustDialog(a, p, "Perfil de ${p.name}") })
        }
    }
}

/** A "pergunta invisível e rápida". */
fun trustDialog(a: MainActivity, p: Pet, context: String, promptId: String? = null) {
    val opts = arrayOf("Sim, condiz", "Não condiz")
    android.app.AlertDialog.Builder(a).setTitle("O perfil de ${p.name} condiz com a realidade?")
        .setItems(opts) { _, i ->
            if (i == 0) {
                val r = if (promptId != null) a.repo.answerTrust(promptId, true, "") else a.repo.giveTrustFeedback(p.id, true, "", context)
                a.handle(r, "Obrigado! Isso mantém a comunidade segura.")
            } else {
                val f = a.field("O que não condiz?", "", "Ex.: tentou morder outros cães", lines = 2)
                f.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
                android.app.AlertDialog.Builder(a).setTitle("Conte rapidamente").setView(f)
                    .setPositiveButton("Enviar") { _, _ ->
                        val txt = f.editText().text.toString()
                        val r = if (promptId != null) a.repo.answerTrust(promptId, false, txt) else a.repo.giveTrustFeedback(p.id, false, txt, context)
                        a.handle(r, "Relato enviado. Perfis com relatos de vários tutores entram em revisão.")
                    }.setNegativeButton("Cancelar", null).show()
            }
        }.setNegativeButton("Agora não", null).show()
}

class UserScreen(a: MainActivity, private val userId: String) : Screen(a) {
    override val title: String get() = s.user(userId)?.name ?: "Tutor"

    override fun render(): View = a.page {
        val u = s.user(userId) ?: run { add(a.emptyState("👤", "Tutor não encontrado", "")); return@page }
        add(card {
            add(text(u.name, TS.H2))
            if (u.neighborhood.isNotBlank()) add(text(u.neighborhood, TS.SMALL, C.textMuted))
            if (userId in s.simulatedUserIds) add(badge("Perfil de demonstração", Tone.NEUTRAL))
        })
        if (userId != s.meId) {
            when (s.friendStatus(userId)) {
                FriendStatus.ACCEPTED -> {
                    add(banner("Na sua Matilha", if (s.isBestFriend(userId)) "Melhor amigo: você recebe alertas de check-in e agenda." else null, Tone.OK))
                    add(a.button(if (s.isBestFriend(userId)) "★ Remover dos melhores amigos" else "☆ Marcar como melhor amigo", BtnKind.SECONDARY) {
                        repo.setBestFriend(userId, !s.isBestFriend(userId))
                    })
                    add(a.button("Remover da Matilha", BtnKind.DANGER) { a.confirm("Remover ${u.name} da Matilha?", "Vocês deixam de ver convites exclusivos um do outro.", "Remover") { repo.removeFriend(userId) } })
                }
                FriendStatus.PENDING_OUT -> add(banner("Pedido de Matilha enviado", "Aguardando ${u.name} aceitar.", Tone.INFO))
                FriendStatus.PENDING_IN -> add(hbox(8) {
                    addWeighted(a.button("Aceitar na Matilha") { a.handle(repo.answerFriend(userId, true), "Agora vocês são da mesma Matilha!") })
                    addWeighted(a.button("Recusar", BtnKind.SECONDARY) { a.handle(repo.answerFriend(userId, false)) })
                })
                null -> add(a.button("+ Adicionar à Matilha") { a.handle(repo.requestFriend(userId), "Pedido enviado") })
            }
        }
        add(section("Pets"))
        s.petsOf(userId).forEach { p -> add(a.petRow(p, s, now) { a.show(PetProfileScreen(a, p.id)) }) }
    }
}
