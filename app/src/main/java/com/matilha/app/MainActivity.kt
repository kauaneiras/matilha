package com.matilha.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import com.matilha.app.data.AppState
import com.matilha.app.data.Repository
import com.matilha.app.domain.AppNotification
import com.matilha.app.platform.*
import com.matilha.app.ui.*
import com.matilha.app.ui.screens.*

enum class Tab(val label: String, val icon: String) {
    MAP("Mapa", "🗺️"), EVENTS("Rolês", "📅"), PACK("Matilha", "🐾"), PETS("Pets", "🐶"), ALERTS("Alertas", "🔔")
}

/** Uma tela do app. As telas raiz pertencem a uma aba. */
abstract class Screen(val a: MainActivity) {
    abstract val title: String
    open val tab: Tab? = null
    /** Telas com formulário não são redesenhadas a cada mudança de estado (preserva o que o usuário digita). */
    open val liveUpdates: Boolean = true
    abstract fun render(): View
    open fun onShown() {}
    open fun onHidden() {}
    /** Retorna true se tratou o "voltar" (ex.: confirmar descarte). */
    open fun onBack(): Boolean = false

    val repo: Repository get() = a.repo
    val s: AppState get() = repo.state
    val now: Long get() = repo.now()
    val ctx: android.content.Context get() = a
}

class MainActivity : Activity() {
    lateinit var repo: Repository
    private val stack = ArrayList<Screen>()
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var backBtn: TextView
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val navItems = HashMap<Tab, TextView>()
    private val main = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var resumed = false
    /** Em testes, desliga rede/localização reais. */
    var testMode = false

    private val stateListener: (AppState) -> Unit = { main.post { scheduleRender() } }

    private val pulseTick = object : Runnable {
        override fun run() {
            runPulse()
            main.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        repo = MatilhaApp.repo(this)
        window.statusBarColor = C.primary
        window.navigationBarColor = C.surface
        buildChrome()
        setContentView(root)
        repo.observe(stateListener)
        if (!repo.state.onboarded) replaceAll(OnboardingScreen(this)) else selectTab(Tab.MAP)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i == null || !repo.state.onboarded) return
        i.getStringExtra(Notifier.EXTRA_WALK_FEEDBACK)?.let { show(WalkFeedbackScreen(this, it)); i.removeExtra(Notifier.EXTRA_WALK_FEEDBACK); return }
        i.getStringExtra(Notifier.EXTRA_PLACE)?.let { if (repo.state.place(it) != null) show(PlaceScreen(this, it)); i.removeExtra(Notifier.EXTRA_PLACE); return }
        i.getStringExtra(Notifier.EXTRA_EVENT)?.let { id ->
            if (repo.state.privateEvents.any { it.id == id }) show(PrivateEventScreen(this, id)); i.removeExtra(Notifier.EXTRA_EVENT)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (repo.state.onboarded && !testMode) {
            startLocation()
            main.removeCallbacks(pulseTick); main.post(pulseTick)
            MatilhaApp.io.execute { Geofence.sync(this) }
        }
        top()?.onShown()
    }

    override fun onPause() {
        resumed = false
        top()?.onHidden()
        main.removeCallbacks(pulseTick)
        LocationTracker.stop(this)
        super.onPause()
    }

    override fun onDestroy() { repo.unobserve(stateListener); super.onDestroy() }

    fun startLocation() {
        if (testMode) return
        LocationTracker.start(this) { loc ->
            MatilhaApp.io.execute {
                repo.onLocation(loc.latitude, loc.longitude)?.let { closed -> main.post { onAutoCheckout(closed) } }
            }
        }
    }

    private fun onAutoCheckout(c: com.matilha.app.domain.CheckIn) {
        Geofence.clear(this)
        if (resumed) show(WalkFeedbackScreen(this, c.placeId, auto = true)) else PulseHandler.onAutoCheckout(this, c)
    }

    fun runPulse() {
        if (testMode) return
        MatilhaApp.io.execute {
            val r = repo.pulse()
            main.post {
                r.notifications.forEach { inAppNotify(it) }
                r.autoClosed?.let { onAutoCheckout(it) }
            }
        }
    }

    private fun inAppNotify(n: AppNotification) {
        if (resumed) toast("${n.title}: ${n.body}", long = true) else Notifier.post(this, n)
    }

    // ------------------------------------------------------------ Chrome

    private fun buildChrome() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        header = hbox(4) {
            setBackgroundColor(C.primary)
            setPadding(dp(4), dp(6), dp(16), dp(6))
            minimumHeight = dp(56)
        }
        backBtn = text("←", TS.H1, C.onPrimary) {
            gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48)
            contentDescription = "Voltar"
            isClickable = true; isFocusable = true
            background = ripple(transparent(), context, 24f)
            setOnClickListener { onBackPressed() }
        }
        titleView = text("", TS.H2, C.onPrimary) { setPadding(dp(8), 0, 0, 0); maxLines = 2; markHeading() }
        header.add(backBtn)
        header.addWeighted(titleView)
        content = FrameLayout(this)
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.surface)
            elevation = dp(8).toFloat()
        }
        Tab.entries.forEach { t ->
            val item = text("${t.icon}\n${t.label}", TS.SMALL, C.textMuted) {
                gravity = Gravity.CENTER
                minHeight = dp(60)
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true; isFocusable = true
                background = ripple(transparent(), context, 0f)
                setOnClickListener { selectTab(t) }
            }
            navItems[t] = item
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Edge-to-edge (Android 15): respeita barras do sistema e teclado.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
    }

    private fun updateNav() {
        nav.visibility = if (repo.state.onboarded) View.VISIBLE else View.GONE
        backBtn.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        titleView.setPadding(if (stack.size > 1) dp(4) else dp(12), 0, 0, 0)
        val current = stack.firstOrNull()?.tab
        val unread = repo.state.unreadNotifications()
        val packPending = repo.state.trustPrompts.size + repo.state.friendships.count { !it.accepted && it.userB == repo.state.meId }
        navItems.forEach { (tab, v) ->
            val sel = tab == current
            v.setTextColor(if (sel) C.primary else C.textMuted)
            v.typeface = if (sel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            val count = when (tab) { Tab.ALERTS -> unread; Tab.PACK -> packPending; else -> 0 }
            v.text = "${tab.icon}\n${tab.label}" + if (count > 0) " ($count)" else ""
            v.contentDescription = tab.label + (if (count > 0) ", $count pendentes" else "") + if (sel) ", aba selecionada" else ""
        }
    }

    fun top(): Screen? = stack.lastOrNull()

    // ------------------------------------------------------------ Navegação

    fun selectTab(t: Tab) {
        if (!repo.state.onboarded) return
        val screen = when (t) {
            Tab.MAP -> MapScreen(this)
            Tab.EVENTS -> EventsScreen(this)
            Tab.PACK -> PackScreen(this)
            Tab.PETS -> PetsScreen(this)
            Tab.ALERTS -> AlertsScreen(this)
        }
        replaceAll(screen)
    }

    fun replaceAll(s: Screen) {
        stack.forEach { it.onHidden() }
        stack.clear(); stack += s
        renderNow(resetScroll = true)
        s.onShown()
    }

    fun show(s: Screen) {
        top()?.onHidden()
        stack += s
        renderNow(resetScroll = true)
        s.onShown()
    }

    /** Fecha a tela atual. */
    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onHidden()
        renderNow(resetScroll = false)
        top()?.onShown()
    }

    /** Substitui a tela atual (ex.: formulário salvo → detalhe). */
    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).onHidden()
        stack += s
        renderNow(resetScroll = true)
        s.onShown()
    }

    @Deprecated("API do framework")
    override fun onBackPressed() {
        val t = top()
        if (t != null && t.onBack()) return
        if (stack.size > 1) pop()
        else if (t?.tab != null && t.tab != Tab.MAP) selectTab(Tab.MAP)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        main.post { renderPending = false; val t = top(); if (t != null && t.liveUpdates) renderNow(false) else updateNav() }
    }

    private val scrollPositions = HashMap<Screen, Int>()
    private var rendered: Screen? = null

    fun renderNow(resetScroll: Boolean) {
        val t = top() ?: return
        val old = content.getChildAt(0)
        val prev = rendered
        (old?.findViewWithTag<View>(MAIN_SCROLL) as? ScrollView)?.let { sv -> if (prev != null && prev in stack) scrollPositions[prev] = sv.scrollY }
        scrollPositions.keys.retainAll(stack.toSet())
        rendered = t
        titleView.text = t.title
        title = t.title
        content.removeAllViews()
        val v = try { t.render() } catch (e: Exception) {
            vbox(16, 12) { add(banner("Algo deu errado nesta tela", e.message ?: e.javaClass.simpleName, Tone.DANGER)); add(button("Voltar") { onBackPressed() }) }
        }
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val y = if (resetScroll) 0 else scrollPositions[t] ?: 0
        if (resetScroll) scrollPositions.remove(t)
        (v.findViewWithTag<View>(MAIN_SCROLL) as? ScrollView)?.let { sv -> if (y > 0) sv.post { sv.scrollTo(0, y) } }
        updateNav()
    }

    // ------------------------------------------------------------ Utilidades para telas

    fun toast(msg: String, long: Boolean = false) = Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    fun confirm(title: String, msg: String, yes: String, danger: Boolean = false, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancelar", null).show()
    }

    fun <T> handle(r: com.matilha.app.data.Result<T>, okMsg: String? = null, onOk: (T) -> Unit = {}) {
        when (r) {
            is com.matilha.app.data.Result.Ok -> { okMsg?.let { toast(it) }; onOk(r.value) }
            is com.matilha.app.data.Result.Err -> AlertDialog.Builder(this).setTitle("Não foi possível").setMessage(r.message).setPositiveButton("Entendi", null).show()
        }
    }

    private val permCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private var nextReq = 100

    fun requestPerms(perms: Array<String>, cb: (Boolean) -> Unit) {
        val missing = perms.filter { !Perms.has(this, it) }
        if (missing.isEmpty()) { cb(true); return }
        val code = nextReq++
        permCallbacks[code] = cb
        requestPermissions(missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        permCallbacks.remove(requestCode)?.invoke(ok)
        renderNow(false)
    }

    fun requestLocation(cb: (Boolean) -> Unit = {}) = requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { ok ->
        if (ok) startLocation()
        cb(ok)
    }

    fun requestNotifications(cb: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf("android.permission.POST_NOTIFICATIONS"), cb) else cb(true)
    }

    private val resultCallbacks = HashMap<Int, (Intent?) -> Unit>()

    fun startForResult(i: Intent, cb: (Intent?) -> Unit) {
        val code = nextReq++
        resultCallbacks[code] = cb
        try { startActivityForResult(i, code) } catch (_: Exception) { resultCallbacks.remove(code); toast("Nenhum app disponível para isso.") }
    }

    @Deprecated("API do framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        resultCallbacks.remove(requestCode)?.invoke(if (resultCode == RESULT_OK) data else null)
    }

    companion object { const val MAIN_SCROLL = "main-scroll" }
}

/** ScrollView principal da tela (posição preservada entre redesenhos). */
fun android.content.Context.screenScroll(content: View): ScrollView = scroll(content).apply { tag = MainActivity.MAIN_SCROLL }

fun android.content.Context.page(block: LinearLayout.() -> Unit): ScrollView = screenScroll(vbox(16, 14) { block(); add(spacer(24)) })
