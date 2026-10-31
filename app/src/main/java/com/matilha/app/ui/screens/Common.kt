package com.matilha.app.ui.screens

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.matilha.app.MainActivity
import com.matilha.app.data.AppState
import com.matilha.app.data.Fmt
import com.matilha.app.domain.*
import com.matilha.app.ui.*
import java.util.Calendar

fun sizeEmoji(s: SizeClass) = when (s) { SizeClass.MINI -> "🐁"; SizeClass.SMALL -> "🐕"; SizeClass.MEDIUM -> "🐕"; SizeClass.LARGE -> "🐕‍🦺"; SizeClass.GIANT -> "🐘" }

/** Linha-resumo do pet ("Paco · SRD · Adulto · 14 kg (Médio) · Macho castrado · Energia 4/5"). */
fun petLine(p: Pet): String = listOf(
    p.breed, p.ageGroup.label, "${Fmt.kg(p.weightKg)} (${p.size.label})",
    p.sex.label + if (p.neutered) (if (p.sex == Sex.FEMALE) " castrada" else " castrado") else (if (p.sex == Sex.FEMALE) " inteira" else " inteiro"),
    "Energia ${p.energy}/5",
).joinToString(" · ")

/** Selos visíveis do pet (sempre com texto, nunca só cor). */
fun MainActivity.petBadges(p: Pet, now: Long): FlowLayout {
    val f = FlowLayout(this, dp(6), dp(6))
    if (Vaccination.hasSeal(p, now)) f.addView(badge("💉 Vacinação em dia", Tone.OK))
    else if (Vaccination.upToDate(p, now)) f.addView(badge("💉 Vacinas em dia (sem comprovante)", Tone.INFO))
    else f.addView(badge("💉 Vacinas pendentes", Tone.WARN))
    if (Vaccination.ectoOk(p, now)) f.addView(badge("Antipulgas em dia", Tone.OK))
    if (p.isFemaleInHeat) f.addView(badge("No cio", Tone.DANGER))
    if (p.isIntactMale) f.addView(badge("Macho inteiro", Tone.NEUTRAL))
    if (p.underReview) f.addView(badge("⚠ Perfil em revisão", Tone.DANGER))
    p.playStyles.forEach { f.addView(badge(it.label, Tone.PRIMARY)) }
    p.triggers.forEach { f.addView(badge("⚠ " + it.label, Tone.WARN)) }
    return f
}

fun MainActivity.petRow(p: Pet, s: AppState, now: Long, extra: String? = null, onClick: (() -> Unit)? = null): View {
    val body: LinearLayout.() -> Unit = {
        add(hbox(8) {
            addWeighted(text("${sizeEmoji(p.size)} ${p.name}", TS.H3))
            if (p.ownerId != s.meId) add(text("tutor: ${s.user(p.ownerId)?.name ?: "?"}", TS.SMALL, C.textMuted))
        })
        add(text(petLine(p), TS.SMALL, C.textMuted))
        if (extra != null) add(text(extra, TS.SMALL, C.text))
        add(petBadges(p, now))
    }
    return if (onClick != null) clickableCard(onClick, 14, body).apply { contentDescription = null } else card(14, 6, block = body)
}

/** Escolha de quais pets participam. Com um único pet, escolhe direto. */
fun MainActivity.pickPets(title: String, candidates: List<Pet>, preselectAll: Boolean = true, onPicked: (List<String>) -> Unit) {
    if (candidates.isEmpty()) { toast("Nenhum pet disponível."); return }
    if (candidates.size == 1) { onPicked(listOf(candidates[0].id)); return }
    val checked = BooleanArray(candidates.size) { preselectAll }
    AlertDialog.Builder(this).setTitle(title)
        .setMultiChoiceItems(candidates.map { "${it.name} (${it.size.label})" }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
        .setPositiveButton("Confirmar") { _, _ ->
            val ids = candidates.filterIndexed { i, _ -> checked[i] }.map { it.id }
            if (ids.isEmpty()) toast("Escolha ao menos um pet.") else onPicked(ids)
        }
        .setNegativeButton("Cancelar", null).show()
}

fun MainActivity.pickDateTime(initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(this, { _, y, m, d ->
        TimePickerDialog(this, { _, h, min ->
            val r = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(r.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 60_000
    }.show()
}

fun MainActivity.pickDate(initial: Long?, maxNow: Boolean, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial ?: System.currentTimeMillis() }
    DatePickerDialog(this, { _, y, m, d ->
        onPicked(Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
        if (maxNow) datePicker.maxDate = System.currentTimeMillis()
    }.show()
}

/** Botão-valor (ex.: "Data: Sáb, 02/11 às 10h00 ✎"). */
fun MainActivity.valueButton(label: String, value: String, onClick: () -> Unit): View = vbox(spacingDp = 4) {
    add(text(label, TS.LABEL))
    add(text("$value  ✎", TS.BODY) {
        background = ripple(rounded(C.surface, 10f, context, C.outline), context, 10f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        contentDescription = "$label: $value. Toque para alterar."
        setOnClickListener { onClick() }
    })
}

fun MainActivity.emptyState(icon: String, title: String, body: String): View = vbox(24, 8) {
    gravity = Gravity.CENTER_HORIZONTAL
    add(text(icon, TS.H1) { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    add(text(title, TS.H3) { gravity = Gravity.CENTER })
    add(text(body, TS.SMALL, C.textMuted) { gravity = Gravity.CENTER })
}

fun compatTone(level: WarningLevel) = when (level) { WarningLevel.BLOCK -> Tone.DANGER; WarningLevel.CAUTION -> Tone.WARN; WarningLevel.INFO -> Tone.INFO }
