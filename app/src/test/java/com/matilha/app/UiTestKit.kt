package com.matilha.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Utilitários para dirigir a UI como um usuário e capturar telas. */
object UiTestKit {
    val outDir: File get() = File(System.getProperty("matilha.screens") ?: "build/screens").apply { mkdirs() }

    fun launch(): Pair<ActivityController<MainActivity>, MainActivity> {
        val c = Robolectric.buildActivity(MainActivity::class.java)
        c.get().testMode = true
        c.setup()
        idle()
        return c to c.get()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    fun root(a: MainActivity): View = a.window.decorView

    fun texts(a: MainActivity): List<String> = all(root(a)).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    fun hasText(a: MainActivity, s: String) = texts(a).any { s in it }

    fun findText(a: MainActivity, s: String): TextView? =
        all(root(a)).filterIsInstance<TextView>().filter { it.isShown }.firstOrNull { s in it.text.toString() }

    fun click(a: MainActivity, s: String) {
        val v = findText(a, s) ?: fail("Texto não encontrado na tela: \"$s\"\nNa tela: ${texts(a).take(60)}") as Nothing
        // Rola até a view (como o usuário faria) e clica.
        var p = v.parent
        while (p != null) { if (p is ScrollView) { p.requestChildFocus(v, v); break }; p = p.parent }
        // Como um toque real: o evento vai para o primeiro ancestral clicável.
        var target: View = v
        while (!target.isClickable && target.parent is View) target = target.parent as View
        if (!target.isEnabled) fail("Botão desabilitado: \"$s\"")
        target.performClick()
        idle()
    }

    fun type(a: MainActivity, label: String, value: String) {
        val lbl = all(root(a)).filterIsInstance<TextView>().firstOrNull { it !is EditText && it.text.toString() == label }
            ?: fail("Campo não encontrado: $label") as Nothing
        val et = a.findViewById<EditText>(lbl.labelFor) ?: fail("Campo sem EditText: $label") as Nothing
        et.setText(value)
        idle()
    }

    /** Auditoria de acessibilidade: nome acessível, alvo de toque ≥ 44dp, texto ≥ 12sp. */
    fun a11yAudit(a: MainActivity, screen: String): List<String> {
        idle()
        val d = a.resources.displayMetrics
        val problems = mutableListOf<String>()
        all(root(a)).filter { it.isShown }.forEach { v ->
            val name = (v.contentDescription ?: (v as? TextView)?.text)?.toString()?.trim().orEmpty()
            if (v.isClickable && v !is ScrollView && v.javaClass.simpleName != "OsmMapView" && v !is android.widget.EditText) {
                if (name.isEmpty() && !(v is ViewGroup && all(v).any { c -> c !== v && c is TextView && c.text.isNotBlank() }))
                    problems += "$screen: clicável sem nome acessível (${v.javaClass.simpleName})"
                val minPx = 44 * d.density
                if (v.height in 1 until minPx.toInt() || (v.width in 1 until minPx.toInt() && v !is TextView))
                    problems += "$screen: alvo pequeno ${(v.width / d.density).toInt()}x${(v.height / d.density).toInt()}dp \"$name\""
            }
            if (v is TextView && v.text.isNotBlank()) {
                val sp = v.textSize / d.scaledDensity
                if (sp < 12f) problems += "$screen: texto ${"%.1f".format(sp)}sp \"${v.text.take(30)}\""
            }
        }
        return problems.distinct()
    }

    fun latestDialog(): AlertDialog? = ShadowDialog.getLatestDialog() as? AlertDialog

    fun dialogMessage(): String? = latestDialog()?.let { shadowOf(it).message?.toString() }

    /** Captura a tela visível (viewport) e, se houver rolagem, a página inteira. */
    fun shot(a: MainActivity, name: String, fullPage: Boolean = true) {
        idle()
        val root = root(a)
        val w = root.width.takeIf { it > 0 } ?: 822
        val h = root.height.takeIf { it > 0 } ?: 1782
        save(render(root, w, h), "$name.png")
        if (fullPage) {
            val sv = all(root).filterIsInstance<ScrollView>().firstOrNull { it.tag == MainActivity.MAIN_SCROLL && it.isShown } ?: return
            val content = sv.getChildAt(0) ?: return
            if (content.height <= sv.height) return
            val bmp = Bitmap.createBitmap(content.width, minOf(content.height, 12000), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(com.matilha.app.ui.C.bg)
            content.draw(c)
            save(bmp, "$name-full.png")
        }
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun save(b: Bitmap, file: String) {
        File(outDir, file).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
