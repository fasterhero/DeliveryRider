package com.marketplace.deliveryrider

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────── Design tokens ─────────────────────────
private val GREEN = Color.parseColor("#1E7B3A")
private val GREEN_DARK = Color.parseColor("#145A2A")
private val GREEN_SOFT = Color.parseColor("#E8F5EC")
private val ORANGE = Color.parseColor("#FF5A1F")
private val ORANGE_SOFT = Color.parseColor("#FFF1EA")
private val YELLOW_SOFT = Color.parseColor("#FFF8DB")
private val INK = Color.parseColor("#1F2937")
private val GREY = Color.parseColor("#6B7280")
private val LINE = Color.parseColor("#E5E7EB")
private val BG = Color.parseColor("#F6F7F8")
private val RED = Color.parseColor("#D93025")
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MAX_ACTIVE = 3

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private data class MerchantInfo(
        val name: String,
        val lat: Double?,
        val lng: Double?,
        val phone: String
    )

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val prefs by lazy { getSharedPreferences("rider_prefs", Context.MODE_PRIVATE) }

    private lateinit var headerHost: LinearLayout
    private lateinit var bodyHost: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var topInset: View
    private lateinit var bottomInset: View

    // Rider state
    private var riderName = ""
    private var riderPhone = ""
    private var isOnline = false

    // Live data
    private var availableOrders = listOf<DocumentSnapshot>()
    private var myOrders = listOf<DocumentSnapshot>()
    private var availableListener: ListenerRegistration? = null
    private var myOrdersListener: ListenerRegistration? = null
    private var firstAvailableSnapshot = true
    private val merchantCache = hashMapOf<String, MerchantInfo>()
    private val merchantRequested = hashSetOf<String>()

    private var currentTab = "jobs"
    private var tabRender: (() -> Unit)? = null

    private fun riderUid(): String? = auth.currentUser?.uid

    // ───────────────────────── Lifecycle ─────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GREEN
        title = "Delivery Rider"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        topInset = View(this).apply { setBackgroundColor(GREEN) }
        root.addView(topInset, lp(MATCH, 0))
        headerHost = vbox()
        root.addView(headerHost, lp(MATCH, WRAP))
        bodyHost = FrameLayout(this)
        root.addView(bodyHost, lp(MATCH, 0, 1f))
        navBar = hbox().apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        root.addView(navBar, lp(MATCH, dp(60)))
        bottomInset = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(bottomInset, lp(MATCH, 0))

        root.setOnApplyWindowInsetsListener { _, insets ->
            topInset.layoutParams = topInset.layoutParams.apply { height = insets.systemWindowInsetTop }
            bottomInset.layoutParams = bottomInset.layoutParams.apply { height = insets.systemWindowInsetBottom }
            insets
        }
        setContentView(root)

        val user = auth.currentUser
        if (user != null && !user.isAnonymous) {
            verifyRider()
        } else {
            if (user?.isAnonymous == true) auth.signOut()
            showLogin()
        }
    }

    override fun onDestroy() {
        stopListeners()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) tabRender?.invoke()
    }

    // ───────────────────────── Helpers ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun shape(fill: Int, radius: Int = 12, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ripple(bg: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), bg, null)

    private fun tv(label: String, size: Float = 14f, color: Int = INK, bold: Boolean = false) =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(pad: Int = 14) = vbox().apply {
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = shape(Color.WHITE, 16, LINE)
    }

    private fun cta(label: String, color: Int = ORANGE, onClick: () -> Unit) =
        tv(label, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = ripple(shape(color, 14))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun ghostButton(label: String, color: Int = GREEN, onClick: () -> Unit) =
        tv(label, 14f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ripple(shape(Color.WHITE, 14, color))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun iconButton(icon: String, desc: String, onClick: () -> Unit) =
        tv(icon, 18f, GREEN, true).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ripple(shape(Color.WHITE, 24, GREEN))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun callButton(
        icon: String,
        desc: String,
        known: String,
        emptyMsg: String = "Number not available.",
        lookup: (((String) -> Unit) -> Unit)? = null
    ) = iconButton(icon, desc) {
        if (known.isNotBlank()) dial(known)
        else if (lookup != null) lookup { p -> if (p.isBlank()) toast(emptyMsg) else dial(p) }
        else toast(emptyMsg)
    }

    private fun inputField(
        hint: String,
        type: Int = InputType.TYPE_CLASS_TEXT
    ) = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = shape(Color.WHITE, 12, LINE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
    }

    private fun sectionTitle(page: LinearLayout, label: String) {
        page.addView(tv(label, 18f, INK, true), lp(MATCH, WRAP, 0f, 0, 18, 0, 8))
    }

    private fun kv(label: String, value: String, bold: Boolean = false, color: Int = INK): View {
        val row = hbox().apply { setPadding(0, dp(3), 0, dp(3)) }
        row.addView(tv(label, if (bold) 15f else 14f, if (bold) INK else GREY, bold), lp(0, WRAP, 1f))
        row.addView(tv(value, if (bold) 15f else 14f, color, bold))
        return row
    }

    private fun emptyState(icon: String, title: String, sub: String): LinearLayout {
        val box = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(36), dp(16), dp(24))
        }
        box.addView(tv(icon, 48f))
        box.addView(tv(title, 17f, INK, true), lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        box.addView(tv(sub, 13f, GREY).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, 0f, 0, 4, 0, 0))
        return box
    }

    private fun money(v: Double): String =
        "₱" + if (v % 1.0 == 0.0) String.format(Locale.US, "%,.0f", v)
        else String.format(Locale.US, "%,.2f", v)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun num(d: DocumentSnapshot, field: String, def: Double = 0.0): Double =
        (d.get(field) as? Number)?.toDouble() ?: def

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim()))))
        } catch (_: Exception) {
            toast("No phone app available.")
        }
    }

    private fun navigate(lat: Double?, lng: Double?, label: String) {
        val primary = if (lat != null && lng != null) "google.navigation:q=$lat,$lng&mode=d"
        else "geo:0,0?q=" + Uri.encode(label)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(primary)).apply {
                setPackage("com.google.android.apps.maps")
            })
        } catch (_: Exception) {
            try {
                val fallback = if (lat != null && lng != null) "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"
                else "geo:0,0?q=" + Uri.encode(label)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
            } catch (_: Exception) {
                toast("No maps app found.")
            }
        }
    }

    private fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }

    private fun myLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best.accuracy) best = l
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    private fun askLocationOnce() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("askedLoc", false)) return
        prefs.edit().putBoolean("askedLoc", true).apply()
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            201
        )
    }

    private fun newPage(): LinearLayout {
        val sv = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = vbox().apply { setPadding(dp(16), dp(14), dp(16), dp(24)) }
        sv.addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
        bodyHost.removeAllViews()
        bodyHost.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    // ───────────────────────── Order helpers ─────────────────────────
    private fun createdMillis(d: DocumentSnapshot): Long =
        ((d.get("createdAt") as? Timestamp)?.seconds ?: (System.currentTimeMillis() / 1000)) * 1000

    private fun deliveredMillis(d: DocumentSnapshot): Long =
        ((d.get("deliveredAt") as? Timestamp)?.seconds ?: (d.get("createdAt") as? Timestamp)?.seconds
            ?: (System.currentTimeMillis() / 1000)) * 1000

    private fun pay(d: DocumentSnapshot): Double =
        (d.get("riderPay") as? Number)?.toDouble() ?: num(d, "deliveryFee")

    private fun activeOrders() = myOrders.filter {
        val s = it.getString("status")
        s == "assigned" || s == "picked_up"
    }.sortedBy { createdMillis(it) }

    private fun deliveredOrders() = myOrders.filter { it.getString("status") == "delivered" }
        .sortedByDescending { deliveredMillis(it) }

    private fun shortId(d: DocumentSnapshot) = "#" + d.id.take(6).uppercase(Locale.US)

    private fun deliveryLat(d: DocumentSnapshot) = (d.get("deliveryLatitude") as? Number)?.toDouble()
    private fun deliveryLng(d: DocumentSnapshot) = (d.get("deliveryLongitude") as? Number)?.toDouble()

    private fun fetchMerchant(id: String) {
        if (id.isBlank() || merchantRequested.contains(id)) return
        merchantRequested.add(id)
        db.collection("merchants").document(id).get()
            .addOnSuccessListener { m ->
                if (m.exists()) {
                    merchantCache[id] = MerchantInfo(
                        m.getString("name") ?: "Merchant",
                        (m.get("latitude") as? Number)?.toDouble(),
                        (m.get("longitude") as? Number)?.toDouble(),
                        m.getString("phone") ?: m.getString("phoneNumber") ?: ""
                    )
                    tabRender?.invoke()
                }
            }
    }

    private fun itemsText(d: DocumentSnapshot): List<String> {
        val items = d.get("items") as? List<*> ?: return emptyList()
        return items.mapNotNull {
            val mp = it as? Map<*, *> ?: return@mapNotNull null
            "${(mp["quantity"] as? Number)?.toInt() ?: 1} × ${mp["name"] ?: "Item"}"
        }
    }

    // ───────────────────────── Auth ─────────────────────────
    private fun showLoading(msg: String) {
        headerHost.visibility = View.GONE
        navBar.visibility = View.GONE
        val box = vbox().apply { gravity = Gravity.CENTER }
        box.addView(tv("🛵", 48f))
        box.addView(tv(msg, 15f, GREY), lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        bodyHost.removeAllViews()
        bodyHost.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showLogin() {
        stopListeners()
        headerHost.visibility = View.GONE
        navBar.visibility = View.GONE
        val page = newPage()
        page.setPadding(0, 0, 0, dp(24))

        val hero = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(GREEN_DARK, GREEN)
            ).apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        hero.addView(tv("🛵", 46f))
        hero.addView(tv("Rider Portal", 26f, Color.WHITE, true))
        hero.addView(
            tv("Accept deliveries and earn on your schedule", 14f, Color.argb(215, 255, 255, 255)),
            lp(WRAP, WRAP, 0f, 0, 6, 0, 0)
        )
        page.addView(hero, lp(MATCH, WRAP))

        val form = card(18)
        page.addView(form, lp(MATCH, WRAP, 0f, 16, -18, 16, 0))
        form.addView(tv("Ready to ride? 👋", 20f, INK, true))
        form.addView(tv("Sign in with your rider account.", 13f, GREY), lp(WRAP, WRAP, 0f, 0, 2, 0, 8))
        val email = inputField("Email address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = inputField("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(email, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        form.addView(pass, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))

        var busy = false
        val btn = cta("Sign in") {}
        form.addView(btn, lp(MATCH, WRAP, 0f, 0, 16, 0, 0))
        btn.setOnClickListener {
            if (busy) return@setOnClickListener
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            if (e.isBlank() || p.isBlank()) {
                toast("Enter your email and password.")
                return@setOnClickListener
            }
            busy = true
            btn.text = "Signing in…"
            btn.alpha = 0.6f
            auth.signInWithEmailAndPassword(e, p)
                .addOnSuccessListener { verifyRider() }
                .addOnFailureListener {
                    busy = false
                    btn.text = "Sign in"
                    btn.alpha = 1f
                    toast("Login failed: ${it.message}")
                }
        }
        form.addView(
            tv("Forgot password?", 13f, GREEN, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(4))
                setOnClickListener {
                    val e = email.text.toString().trim()
                    if (e.isBlank()) toast("Type your email above first.")
                    else auth.sendPasswordResetEmail(e)
                        .addOnSuccessListener { toast("Reset link sent to $e") }
                        .addOnFailureListener { toast("Could not send: ${it.message}") }
                }
            },
            lp(MATCH, WRAP)
        )
    }

    private fun verifyRider() {
        val uid = riderUid()
        if (uid == null) { showLogin(); return }
        stopListeners()
        showLoading("Verifying rider account…")

        db.collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (doc.exists() && doc.getBoolean("active") == true) {
                    riderName = doc.getString("name") ?: ""
                    riderPhone = doc.getString("phone") ?: ""
                    isOnline = doc.getBoolean("isOnline") == true
                    startMyOrdersListener()
                    if (isOnline) startAvailableListener()
                    showTab("jobs")
                    askLocationOnce()
                } else {
                    showAccessDenied("Your rider profile is missing or inactive.\nAsk the admin to activate riders/$uid.")
                }
            }
            .addOnFailureListener {
                if (isFinishing || isDestroyed) return@addOnFailureListener
                showAccessDenied("Could not verify rider profile: ${it.message}")
            }
    }

    private fun showAccessDenied(message: String) {
        stopListeners()
        headerHost.visibility = View.GONE
        navBar.visibility = View.GONE
        val page = newPage()
        page.addView(emptyState("🔒", "Rider verification", message))
        page.addView(cta("Try again", GREEN) { verifyRider() }, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
        page.addView(ghostButton("Log out", RED) { logout() }, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
    }

    private fun logout() {
        stopListeners()
        auth.signOut()
        availableOrders = emptyList()
        myOrders = emptyList()
        isOnline = false
        showLogin()
    }

    // ───────────────────────── Listeners ─────────────────────────
    private fun startMyOrdersListener() {
        val uid = riderUid() ?: return
        myOrdersListener?.remove()
        myOrdersListener = db.collection("orders").whereEqualTo("riderId", uid)
            .addSnapshotListener { snap, err ->
                if (isFinishing || isDestroyed) return@addSnapshotListener
                if (err != null) {
                    toast("My deliveries error: ${err.message}")
                    return@addSnapshotListener
                }
                myOrders = snap?.documents.orEmpty()
                myOrders.forEach { fetchMerchant(it.getString("merchantId").orEmpty()) }
                refreshNav()
                tabRender?.invoke()
            }
    }

    private fun startAvailableListener() {
        availableListener?.remove()
        firstAvailableSnapshot = true
        availableListener = db.collection("orders")
            .whereEqualTo("status", "ready")
            .whereEqualTo("riderId", null)
            .addSnapshotListener { snap, err ->
                if (isFinishing || isDestroyed) return@addSnapshotListener
                if (err != null) {
                    toast("Available orders error: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                if (!firstAvailableSnapshot) {
                    val fresh = snap.documentChanges.count { it.type == DocumentChange.Type.ADDED }
                    if (fresh > 0) alertNewJob(fresh)
                }
                firstAvailableSnapshot = false
                availableOrders = snap.documents.sortedBy { createdMillis(it) }
                availableOrders.forEach { fetchMerchant(it.getString("merchantId").orEmpty()) }
                refreshNav()
                tabRender?.invoke()
            }
    }

    private fun stopListeners() {
        availableListener?.remove()
        availableListener = null
        myOrdersListener?.remove()
        myOrdersListener = null
        tabRender = null
    }

    private fun alertNewJob(count: Int) {
        toast(if (count == 1) "🔔 New delivery available!" else "🔔 $count new deliveries!")
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(longArrayOf(0, 300, 150, 300), -1)
        } catch (_: Exception) {
        }
    }

    private fun setOnline(on: Boolean) {
        val uid = riderUid() ?: return
        isOnline = on
        if (on) {
            startAvailableListener()
            askLocationOnce()
        } else {
            availableListener?.remove()
            availableListener = null
            availableOrders = emptyList()
        }
        refreshHeader()
        refreshNav()
        tabRender?.invoke()
        toast(if (on) "You're online — waiting for orders" else "You're offline")
        db.collection("riders").document(uid).update("isOnline", on)
            .addOnFailureListener { toast("Status not saved to server: ${it.message}") }
    }

    // ───────────────────────── Shell ─────────────────────────
    private fun showTab(tab: String) {
        currentTab = tab
        tabRender = null
        refreshHeader()
        refreshNav()
        when (tab) {
            "jobs" -> showJobsTab()
            "active" -> showActiveTab()
            "earnings" -> showEarningsTab()
            else -> showProfileTab()
        }
    }

    private fun refreshHeader() {
        headerHost.removeAllViews()
        headerHost.visibility = View.VISIBLE
        val bar = hbox().apply {
            setBackgroundColor(GREEN)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val first = riderName.trim().split(" ").firstOrNull().orEmpty()
        val col = vbox()
        col.addView(tv(if (first.isBlank()) "Hi, Rider 👋" else "Hi, $first 👋", 19f, Color.WHITE, true))
        col.addView(
            tv(if (isOnline) "● Online — receiving orders" else "● Offline", 12f, Color.argb(215, 255, 255, 255))
        )
        bar.addView(col, lp(0, WRAP, 1f))
        bar.addView(
            tv(if (isOnline) "ONLINE" else "OFFLINE", 13f, if (isOnline) GREEN else GREY, true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = shape(Color.WHITE, 20)
                isClickable = true
                setOnClickListener { setOnline(!isOnline) }
            }
        )
        headerHost.addView(bar, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun refreshNav() {
        navBar.visibility = View.VISIBLE
        navBar.removeAllViews()
        val items = listOf(
            Triple("jobs", "📦", "Orders"),
            Triple("active", "🛵", "Active"),
            Triple("earnings", "💰", "Earnings"),
            Triple("profile", "👤", "Profile")
        )
        for ((key, icon, label) in items) {
            val selected = key == currentTab
            val badge = when (key) {
                "jobs" -> if (isOnline) availableOrders.size else 0
                "active" -> activeOrders().size
                else -> 0
            }
            val col = vbox().apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showTab(key) }
            }
            val wrap = FrameLayout(this)
            wrap.addView(tv(icon, 20f).apply { alpha = if (selected) 1f else 0.55f })
            if (badge > 0) {
                wrap.addView(
                    tv("$badge", 10f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        minimumWidth = dp(16)
                        setPadding(dp(3), 0, dp(3), 0)
                        background = shape(ORANGE, 8)
                    },
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
                )
            }
            col.addView(wrap)
            col.addView(tv(label, 11f, if (selected) GREEN else GREY, selected))
            navBar.addView(col, lp(0, MATCH, 1f))
        }
    }

    // ───────────────────────── JOBS TAB (available orders) ─────────────────────────
    private fun showJobsTab() {
        val page = newPage()
        tabRender = { renderJobs(page) }
        renderJobs(page)
    }

    private fun renderJobs(page: LinearLayout) {
        page.removeAllViews()

        if (!isOnline) {
            page.addView(emptyState("😴", "You're offline", "Go online to start receiving delivery requests."))
            page.addView(cta("Go online", GREEN) { setOnline(true) }, lp(MATCH, WRAP, 0f, 24, 4, 24, 0))
            return
        }

        val active = activeOrders().size
        page.addView(
            tv("Active deliveries: $active / $MAX_ACTIVE", 12f, GREY),
            lp(WRAP, WRAP, 0f, 0, 0, 0, 10)
        )

        if (availableOrders.isEmpty()) {
            page.addView(emptyState("🛵", "Looking for orders…", "You'll hear a sound when a new delivery is ready for pickup."))
            return
        }
        availableOrders.forEach { page.addView(jobCard(it), lp(MATCH, WRAP, 0f, 0, 0, 0, 12)) }
    }

    private fun jobCard(d: DocumentSnapshot): View {
        val c = card(16)
        val mid = d.getString("merchantId").orEmpty()
        val m = merchantCache[mid]

        val top = hbox()
        top.addView(tv("🏪 ${d.getString("merchantName") ?: "Merchant"}", 16f, INK, true), lp(0, WRAP, 1f))
        top.addView(tv("Earn ${money(pay(d))}", 13f, GREEN, true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = shape(GREEN_SOFT, 10)
        })
        c.addView(top)
        c.addView(tv("${shortId(d)}  ·  ${itemsText(d).size} item(s)", 12f, GREY), lp(WRAP, WRAP, 0f, 0, 2, 0, 8))

        // Distances
        val dLat = deliveryLat(d)
        val dLng = deliveryLng(d)
        val lines = mutableListOf<String>()
        val me = myLocation()
        val mLat = m?.lat
        val mLng = m?.lng
        if (me != null && mLat != null && mLng != null) {
            lines.add("📍 Pickup ≈ ${String.format(Locale.US, "%.1f", km(me.latitude, me.longitude, mLat, mLng))} km from you")
        }
        if (mLat != null && mLng != null && dLat != null && dLng != null) {
            lines.add("🛣️ Trip ≈ ${String.format(Locale.US, "%.1f", km(mLat, mLng, dLat, dLng))} km (straight line)")
        }
        lines.forEach { c.addView(tv(it, 13f, INK)) }

        c.addView(
            tv("🏠 ${d.getString("deliveryAddress") ?: "No address"}", 13f, GREY).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            lp(MATCH, WRAP, 0f, 0, 6, 0, 0)
        )

        c.addView(View(this).apply { setBackgroundColor(LINE) }, lp(MATCH, 1, 0f, 0, 10, 0, 8))
        c.addView(kv("💵 Collect from customer (COD)", money(num(d, "total")), true))

        c.addView(cta("Accept delivery") { claimOrder(d.id) }, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))
        return c
    }

    private fun claimOrder(orderId: String) {
        val uid = riderUid() ?: return
        if (activeOrders().size >= MAX_ACTIVE) {
            toast("You already have $MAX_ACTIVE active deliveries. Finish one first.")
            return
        }
        val ref = db.collection("orders").document(orderId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) throw IllegalStateException("This order no longer exists.")
            if (snap.getString("status") != "ready" || snap.getString("riderId") != null) {
                throw IllegalStateException("Another rider already took this order.")
            }
            val upd = hashMapOf<String, Any?>("riderId" to uid, "status" to "assigned")
            if (riderName.isNotBlank()) upd["riderName"] = riderName
            if (riderPhone.isNotBlank()) upd["riderPhone"] = riderPhone
            tx.update(ref, upd)
            null
        }.addOnSuccessListener {
            toast("Order accepted! Head to the restaurant 🛵")
            showTab("active")
        }.addOnFailureListener {
            toast("Could not accept: ${it.message}")
        }
    }

    // ───────────────────────── ACTIVE TAB ─────────────────────────
    private fun showActiveTab() {
        val page = newPage()
        tabRender = { renderActive(page) }
        renderActive(page)
    }

    private fun renderActive(page: LinearLayout) {
        page.removeAllViews()
        val list = activeOrders()
        if (list.isEmpty()) {
            page.addView(emptyState("🛵", "No active deliveries", "Accepted orders will appear here."))
            page.addView(cta("Find orders", GREEN) { showTab("jobs") }, lp(MATCH, WRAP, 0f, 24, 4, 24, 0))
            return
        }
        list.forEach { page.addView(activeCard(it), lp(MATCH, WRAP, 0f, 0, 0, 0, 14)) }
    }

    private fun stepPill(label: String, on: Boolean): View =
        tv(label, 12f, if (on) Color.WHITE else GREY, on).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(if (on) GREEN else Color.parseColor("#F1F3F4"), 14)
        }

    private fun activeCard(d: DocumentSnapshot): View {
        val status = d.getString("status") ?: "assigned"
        val picked = status == "picked_up"
        val orderId = d.id
        val mid = d.getString("merchantId").orEmpty()
        val mName = d.getString("merchantName") ?: "Merchant"
        val m = merchantCache[mid]
        val total = num(d, "total")

        val c = card(16)
        val top = hbox()
        top.addView(tv(shortId(d), 16f, INK, true), lp(0, WRAP, 1f))
        top.addView(tv("Earn ${money(pay(d))}", 13f, GREEN, true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = shape(GREEN_SOFT, 10)
        })
        c.addView(top)

        val steps = hbox()
        steps.addView(stepPill("1 · Pickup", !picked))
        steps.addView(tv("  ›  ", 14f, GREY))
        steps.addView(stepPill("2 · Drop-off", picked))
        c.addView(steps, lp(WRAP, WRAP, 0f, 0, 10, 0, 10))

        c.addView(
            tv("💵 Collect ${money(total)} cash from the customer", 14f, ORANGE, true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = shape(ORANGE_SOFT, 10)
            },
            lp(MATCH, WRAP, 0f, 0, 0, 0, 10)
        )

        if (!picked) {
            // ── Pickup stage
            c.addView(tv("Pick up from", 12f, GREY))
            val row = hbox()
            row.addView(tv("🏪 $mName", 17f, INK, true), lp(0, WRAP, 1f))
            row.addView(iconButton("🧭", "Navigate to restaurant") { navigate(m?.lat, m?.lng, mName) }, lp(WRAP, WRAP, 0f, 0, 0, 8, 0))
            row.addView(
                callButton("🏪📞", "Call merchant", d.getString("merchantPhone").orEmpty().ifBlank { m?.phone.orEmpty() },
                    "Merchant's number isn't available.")
            )
            c.addView(row, lp(MATCH, WRAP, 0f, 0, 2, 0, 8))

            c.addView(tv("Check these items:", 12f, GREY))
            itemsText(d).forEach { c.addView(tv("•  $it", 14f, INK)) }

            c.addView(
                cta("Picked up from restaurant") { advance(orderId, "assigned", "picked_up") },
                lp(MATCH, WRAP, 0f, 0, 14, 0, 0)
            )
            c.addView(
                ghostButton("Release order", RED) {
                    AlertDialog.Builder(this)
                        .setTitle("Release this order?")
                        .setMessage("It goes back to the available list for other riders.")
                        .setPositiveButton("Release") { _, _ -> releaseOrder(orderId) }
                        .setNegativeButton("Keep", null)
                        .show()
                },
                lp(MATCH, WRAP, 0f, 0, 8, 0, 0)
            )
        } else {
            // ── Drop-off stage
            c.addView(tv("Deliver to", 12f, GREY))
            val row = hbox()
            val col = vbox()
            col.addView(tv("👤 ${d.getString("customerName") ?: "Customer"}", 17f, INK, true))
            col.addView(tv("🏠 ${d.getString("deliveryAddress") ?: "No address"}", 13f, GREY))
            row.addView(col, lp(0, WRAP, 1f, 0, 0, 8, 0))
            val dLat = deliveryLat(d)
            val dLng = deliveryLng(d)
            row.addView(
                iconButton("🧭", "Navigate to customer") {
                    navigate(dLat, dLng, d.getString("deliveryAddress") ?: "Customer")
                },
                lp(WRAP, WRAP, 0f, 0, 0, 8, 0)
            )
            row.addView(callButton("📞", "Call customer", d.getString("customerPhone").orEmpty(), "Customer's number is missing."))
            c.addView(row, lp(MATCH, WRAP, 0f, 0, 2, 0, 8))

            val rn = d.getString("riderNote").orEmpty()
            if (rn.isNotBlank()) {
                c.addView(
                    tv("📝 $rn", 13f, INK, true).apply {
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = shape(YELLOW_SOFT, 10)
                    },
                    lp(MATCH, WRAP, 0f, 0, 0, 0, 4)
                )
            }
            c.addView(
                cta("Delivered • cash collected", GREEN) {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm delivery")
                        .setMessage("Did you hand over the order and collect ${money(total)} in cash?")
                        .setPositiveButton("Yes, delivered") { _, _ -> advance(orderId, "picked_up", "delivered") }
                        .setNegativeButton("Not yet", null)
                        .show()
                },
                lp(MATCH, WRAP, 0f, 0, 14, 0, 0)
            )
        }
        return c
    }

    private fun advance(orderId: String, expected: String, newStatus: String) {
        val uid = riderUid() ?: return
        val ref = db.collection("orders").document(orderId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists() || snap.getString("riderId") != uid || snap.getString("status") != expected) {
                throw IllegalStateException("Order status changed. Please try again.")
            }
            val upd = hashMapOf<String, Any>("status" to newStatus)
            if (newStatus == "picked_up") upd["pickedUpAt"] = FieldValue.serverTimestamp()
            if (newStatus == "delivered") upd["deliveredAt"] = FieldValue.serverTimestamp()
            tx.update(ref, upd)
            null
        }.addOnSuccessListener {
            if (newStatus == "delivered") {
                toast("Delivered! Great job 🎉")
                if (activeOrders().isEmpty()) showTab("earnings")
            } else {
                toast("Picked up. Head to the customer.")
            }
        }.addOnFailureListener {
            toast("Update failed: ${it.message}")
        }
    }

    private fun releaseOrder(orderId: String) {
        val uid = riderUid() ?: return
        val ref = db.collection("orders").document(orderId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists() || snap.getString("riderId") != uid || snap.getString("status") != "assigned") {
                throw IllegalStateException("This order can't be released anymore.")
            }
            val upd = hashMapOf<String, Any?>(
                "riderId" to null, "status" to "ready", "riderName" to null, "riderPhone" to null
            )
            tx.update(ref, upd)
            null
        }.addOnSuccessListener {
            toast("Order released")
        }.addOnFailureListener {
            toast("Could not release: ${it.message}")
        }
    }

    // ───────────────────────── EARNINGS TAB ─────────────────────────
    private fun showEarningsTab() {
        val page = newPage()
        tabRender = { renderEarnings(page) }
        renderEarnings(page)
    }

    private fun renderEarnings(page: LinearLayout) {
        page.removeAllViews()
        val delivered = deliveredOrders()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val weekStart = todayStart - 6L * 24 * 60 * 60 * 1000
        val today = delivered.filter { deliveredMillis(it) >= todayStart }
        val week = delivered.filter { deliveredMillis(it) >= weekStart }

        val hero = vbox().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(GREEN, GREEN_DARK)
            ).apply { cornerRadius = dp(18).toFloat() }
        }
        hero.addView(tv("Today's earnings", 13f, Color.argb(215, 255, 255, 255)))
        hero.addView(tv(money(today.sumOf { pay(it) }), 32f, Color.WHITE, true))
        hero.addView(
            tv(
                "${today.size} deliveries  ·  COD collected ${money(today.sumOf { num(it, "total") })}",
                13f, Color.argb(225, 255, 255, 255)
            ),
            lp(WRAP, WRAP, 0f, 0, 4, 0, 0)
        )
        page.addView(hero, lp(MATCH, WRAP))

        fun stat(title: String, value: String, sub: String): View {
            val s = vbox().apply {
                background = shape(Color.WHITE, 14, LINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            s.addView(tv(title, 12f, GREY))
            s.addView(tv(value, 18f, INK, true))
            s.addView(tv(sub, 11f, GREY))
            return s
        }
        val row = hbox()
        row.addView(stat("Last 7 days", money(week.sumOf { pay(it) }), "${week.size} deliveries"), lp(0, WRAP, 1f, 0, 0, 6, 0))
        row.addView(stat("All time", money(delivered.sumOf { pay(it) }), "${delivered.size} deliveries"), lp(0, WRAP, 1f, 6, 0, 0, 0))
        page.addView(row, lp(MATCH, WRAP, 0f, 0, 12, 0, 0))

        page.addView(
            tv("Cash to remit = COD collected. Your earning is the delivery pay shown per order.", 11f, GREY),
            lp(WRAP, WRAP, 0f, 0, 8, 0, 0)
        )

        sectionTitle(page, "Recent deliveries")
        if (delivered.isEmpty()) {
            page.addView(emptyState("🧾", "No deliveries yet", "Completed deliveries will show here."))
            return
        }
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        delivered.take(30).forEach { d ->
            val r = hbox().apply {
                background = shape(Color.WHITE, 14, LINE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val col = vbox()
            col.addView(tv(d.getString("merchantName") ?: "Order", 14f, INK, true))
            col.addView(tv("${shortId(d)}  ·  ${fmt.format(Date(deliveredMillis(d)))}", 11f, GREY))
            r.addView(col, lp(0, WRAP, 1f))
            val right = vbox().apply { gravity = Gravity.END }
            right.addView(tv("+${money(pay(d))}", 15f, GREEN, true))
            right.addView(tv("COD ${money(num(d, "total"))}", 11f, GREY))
            r.addView(right)
            page.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        }
    }

    // ───────────────────────── PROFILE TAB ─────────────────────────
    private fun showProfileTab() {
        val page = newPage()
        tabRender = null

        val hero = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val initial = (riderName.ifBlank { auth.currentUser?.email ?: "R" }).trim().take(1).uppercase(Locale.US)
        hero.addView(
            tv(initial, 30f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
            },
            lp(dp(76), dp(76))
        )
        hero.addView(tv(riderName.ifBlank { "Rider" }, 20f, INK, true), lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        hero.addView(tv(auth.currentUser?.email ?: "", 13f, GREY))
        page.addView(hero, lp(MATCH, WRAP))

        sectionTitle(page, "Your details")
        val info = card(14)
        info.addView(kv("Name", riderName.ifBlank { "—" }))
        info.addView(kv("Phone", riderPhone.ifBlank { "—" }))
        info.addView(kv("Status", if (isOnline) "Online" else "Offline", false, if (isOnline) GREEN else GREY))
        info.addView(kv("Rider ID", (riderUid() ?: "").take(8)))
        page.addView(info)
        page.addView(
            tv("Name and phone are shown to customers and merchants when you accept an order. Ask the admin to change them.", 11f, GREY),
            lp(WRAP, WRAP, 0f, 0, 6, 0, 0)
        )

        sectionTitle(page, "Account")
        page.addView(
            ghostButton("Change password") {
                val email = auth.currentUser?.email
                if (email.isNullOrBlank()) toast("No email on this account.")
                else auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener { toast("Reset link sent to $email") }
                    .addOnFailureListener { toast("Could not send: ${it.message}") }
            }
        )
        page.addView(ghostButton("Log out", RED) { logout() }, lp(MATCH, WRAP, 0f, 0, 10, 0, 0))
        page.addView(
            tv("Delivery Marketplace · Rider app", 11f, GREY).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP, 0f, 0, 20, 0, 0)
        )
    }
}
