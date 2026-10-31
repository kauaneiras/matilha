package com.matilha.app.data

import com.matilha.app.domain.DAY
import com.matilha.app.domain.HOUR
import com.matilha.app.domain.MINUTE
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Fmt {
    val BR: Locale = Locale("pt", "BR")

    fun time(t: Long): String = SimpleDateFormat("HH'h'mm", BR).format(t)
    fun dayShort(t: Long): String = SimpleDateFormat("EEE, dd/MM", BR).format(t).replaceFirstChar { it.uppercase() }
    fun dateTime(t: Long): String = "${dayShort(t)} às ${time(t)}"
    fun date(t: Long): String = SimpleDateFormat("dd/MM/yyyy", BR).format(t)

    /** "hoje às 18h00", "amanhã às 10h00", "Sáb, 02/11 às 10h00". */
    fun relativeDay(t: Long, now: Long): String {
        val d0 = Repository.startOfDay(now)
        val d = Repository.startOfDay(t)
        val label = when (d) {
            d0 -> "hoje"
            Repository.addDays(d0, 1) -> "amanhã"
            Repository.addDays(d0, -1) -> "ontem"
            else -> dayShort(t)
        }
        return "$label às ${time(t)}"
    }

    fun ago(t: Long, now: Long): String {
        val d = now - t
        return when {
            d < MINUTE -> "agora mesmo"
            d < HOUR -> "há ${d / MINUTE} min"
            d < DAY -> "há ${d / HOUR} h"
            else -> "há ${plural((d / DAY).toInt(), "dia", "dias")}"
        }
    }

    /** "1 cão", "3 cães". */
    fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    fun kg(v: Double): String = if (v % 1.0 == 0.0) "${v.toInt()} kg" else String.format(BR, "%.1f kg", v)

    /** Converte "12,5" ou "12.5" em Double. */
    fun parseDecimal(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    fun atTime(dayStart: Long, minuteOfDay: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart; set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60)
    }.timeInMillis
}
