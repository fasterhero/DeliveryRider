
package com.marketplace.deliveryrider

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : Activity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var content: LinearLayout
    private lateinit var availableContainer: LinearLayout
    private lateinit var deliveriesContainer: LinearLayout
    private lateinit var statusText: TextView

    private var availableOrders: List<DocumentSnapshot> = emptyList()
    private var myOrders: List<DocumentSnapshot> = emptyList()

    private var availableListener: ListenerRegistration? = null
    private var myOrdersListener: ListenerRegistration? = null

    private fun riderUid(): String? = auth.currentUser?.uid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (auth.currentUser?.isAnonymous == true) {
            auth.signOut()
        }

        if (auth.currentUser != null) {
            verifyRider()
        } else {
            showLoginScreen()
        }
    }

    private fun setupContent() {
        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)
    }

    private fun heading(text: String, size: Float) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 65, 55))
            setPadding(0, 12, 0, 12)
        })
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun addButton(
        parent: LinearLayout,
        text: String,
        action: () -> Unit
    ) {
        parent.addView(Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        })
    }

    private fun removeOrderListeners() {
        availableListener?.remove()
        myOrdersListener?.remove()

        availableListener = null
        myOrdersListener = null
    }

    private fun showLoginScreen() {
        removeOrderListeners()
        setupContent()

        heading("Delivery Rider", 30f)
        heading("Sign in to your rider account", 20f)

        content.addView(TextView(this).apply {
            text = "Use your registered email and password."
            textSize = 15f
        })

        val emailInput = EditText(this).apply {
            hint = "Email address"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        content.addView(emailInput)

        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        content.addView(passwordInput)

        val loginButton = Button(this).apply {
            text = "Sign In"
        }
        content.addView(loginButton)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (email.isBlank() || password.isBlank()) {
                showMessage("Enter your email and password.")
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            loginButton.text = "Signing in..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    verifyRider()
                }
                .addOnFailureListener { error ->
                    loginButton.isEnabled = true
                    loginButton.text = "Sign In"
                    showMessage("Login failed: ${error.message}")
                }
        }
    }

    private fun verifyRider() {
        val uid = riderUid()

        if (uid == null) {
            showLoginScreen()
            return
        }

        removeOrderListeners()
        setupContent()
        heading("Delivery Rider", 28f)
        heading("Verifying rider account...", 18f)

        db.collection("riders")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener

                if (document.exists() &&
                    document.getBoolean("active") == true
                ) {
                    showHomeScreen()
                } else {
                    showAccessDenied(
                        "Your rider profile is missing or inactive. " +
                            "Check riders/$uid in Firestore."
                    )
                }
            }
            .addOnFailureListener { error ->
                if (isFinishing || isDestroyed) return@addOnFailureListener

                showAccessDenied(
                    "Could not verify rider profile: ${error.message}"
                )
            }
    }

    private fun showAccessDenied(message: String) {
        removeOrderListeners()
        setupContent()

        heading("Rider Verification", 26f)

        content.addView(TextView(this).apply {
            text = message
            textSize = 16f
        })

        addButton(content, "Try Again") {
            verifyRider()
        }

        addButton(content, "Log Out") {
            auth.signOut()
            showLoginScreen()
        }
    }

    private fun showHomeScreen() {
        setupContent()

        heading("Delivery Rider", 30f)

        content.addView(TextView(this).apply {
            text = "Signed in as:\n${auth.currentUser?.email}"
            textSize = 15f
            setPadding(0, 0, 0, 8)
        })

        statusText = TextView(this).apply {
            text = "Connected. Loading orders..."
            textSize = 14f
            setTextColor(Color.rgb(35, 100, 65))
            setPadding(0, 8, 0, 12)
        }
        content.addView(statusText)

        addButton(content, "Refresh Orders") {
            statusText.text = "Refreshing orders..."
            listenForOrders()
        }

        heading("Available Orders", 22f)

        availableContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(availableContainer)

        heading("My Deliveries", 22f)

        deliveriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(deliveriesContainer)

        addButton(content, "Log Out") {
            removeOrderListeners()
            auth.signOut()
            showLoginScreen()
        }

        listenForOrders()
    }

    private fun listenForOrders() {
        val uid = riderUid() ?: return

        removeOrderListeners()

        // Available orders: merchant marked ready, no rider assigned.
        availableListener = db.collection("orders")
            .whereEqualTo("status", "ready")
            .whereEqualTo("riderId", null)
            .addSnapshotListener { snapshot, error ->

                if (isFinishing || isDestroyed) return@addSnapshotListener

                if (error != null) {
                    statusText.text =
                        "Available-order error: ${error.message}"
                    return@addSnapshotListener
                }

                availableOrders = snapshot?.documents ?: emptyList()
                renderOrders()
            }

        // Orders assigned to this rider.
        myOrdersListener = db.collection("orders")
            .whereEqualTo("riderId", uid)
            .addSnapshotListener { snapshot, error ->

                if (isFinishing || isDestroyed) return@addSnapshotListener

                if (error != null) {
                    statusText.text =
                        "My-deliveries error: ${error.message}"
                    return@addSnapshotListener
                }

                myOrders = snapshot?.documents ?: emptyList()
                renderOrders()
            }
    }

    private fun renderOrders() {
        if (!::availableContainer.isInitialized ||
            !::deliveriesContainer.isInitialized
        ) return

        availableContainer.removeAllViews()
        deliveriesContainer.removeAllViews()

        if (availableOrders.isEmpty()) {
            availableContainer.addView(TextView(this).apply {
                text = "No available orders right now."
                textSize = 15f
                setPadding(0, 8, 0, 12)
            })
        } else {
            availableOrders.forEach { order ->
                addOrderCard(order, availableContainer, true)
            }
        }

        val activeDeliveries = myOrders.filter {
            val status = it.getString("status")
            status == "assigned" || status == "picked_up"
        }

        if (activeDeliveries.isEmpty()) {
            deliveriesContainer.addView(TextView(this).apply {
                text = "You have no active deliveries."
                textSize = 15f
                setPadding(0, 8, 0, 12)
            })
        } else {
            activeDeliveries.forEach { order ->
                addOrderCard(order, deliveriesContainer, false)
            }
        }

        statusText.text =
            "Available: ${availableOrders.size} | " +
                "My active deliveries: ${activeDeliveries.size}"
    }

    private fun addOrderCard(
        order: DocumentSnapshot,
        parent: LinearLayout,
        canClaim: Boolean
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 248, 245))
                cornerRadius = 18f
                setStroke(1, Color.rgb(220, 225, 218))
            }
        }

        val orderId = order.id
        val merchantName =
            order.getString("merchantName") ?: "Merchant"

        val total = order.get("total")?.toString() ?: "0"
        val address =
            order.getString("deliveryAddress") ?: "No address provided"

        val status = order.getString("status") ?: "unknown"

        card.addView(TextView(this).apply {
            text = "Order #$orderId"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 65, 55))
        })

        card.addView(TextView(this).apply {
            text = "Merchant: $merchantName"
            textSize = 15f
            setPadding(0, 6, 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "Items:\n${formatItems(order)}"
            textSize = 14f
            setPadding(0, 6, 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "Total: ₱$total"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 6, 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "Delivery address: $address"
            textSize = 14f
            setPadding(0, 6, 0, 0)
        })

        card.addView(TextView(this).apply {
            text = "Status: $status"
            textSize = 14f
            setPadding(0, 6, 0, 0)
        })

        if (canClaim) {
            addButton(card, "Claim Order") {
                claimOrder(orderId)
            }
        } else if (status == "assigned") {
            addButton(card, "Mark as Picked Up") {
                updateDeliveryStatus(
                    orderId,
                    "assigned",
                    "picked_up"
                )
            }
        } else if (status == "picked_up") {
            addButton(card, "Mark as Delivered") {
                updateDeliveryStatus(
                    orderId,
                    "picked_up",
                    "delivered"
                )
            }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = 16

        parent.addView(card, params)
    }

    private fun formatItems(order: DocumentSnapshot): String {
        val items = order.get("items") as? List<*> ?: return "Item details unavailable"

        if (items.isEmpty()) return "No items listed"

        return items.mapIndexed { index, item ->
            val fields = item as? Map<*, *>

            if (fields == null) {
                "${index + 1}. ${item ?: "Item"}"
            } else {
                val name = fields["name"]
                    ?: fields["productName"]
                    ?: fields["title"]
                    ?: "Item ${index + 1}"

                val quantity = fields["quantity"]
                    ?: fields["qty"]
                    ?: 1

                "$name × $quantity"
            }
        }.joinToString("\n")
    }

    private fun claimOrder(orderId: String) {
        val uid = riderUid() ?: return
        val orderRef = db.collection("orders").document(orderId)

        showMessage("Claiming order...")

        db.runTransaction { transaction ->
            val snapshot = transaction.get(orderRef)

            if (!snapshot.exists()) {
                throw IllegalStateException("This order no longer exists.")
            }

            if (snapshot.getString("status") != "ready" ||
                snapshot.getString("riderId") != null
            ) {
                throw IllegalStateException(
                    "This order has already been claimed or is no longer ready."
                )
            }

            transaction.update(orderRef, "riderId", uid)
            transaction.update(orderRef, "status", "assigned")
            null
        }.addOnSuccessListener {
            showMessage("Order claimed successfully!")
        }.addOnFailureListener { error ->
            showMessage("Could not claim order: ${error.message}")
        }
    }

    private fun updateDeliveryStatus(
        orderId: String,
        expectedStatus: String,
        newStatus: String
    ) {
        val uid = riderUid() ?: return
        val orderRef = db.collection("orders").document(orderId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(orderRef)

            if (!snapshot.exists() ||
                snapshot.getString("riderId") != uid ||
                snapshot.getString("status") != expectedStatus
            ) {
                throw IllegalStateException(
                    "Order status changed. Refresh and try again."
                )
            }

            transaction.update(orderRef, "status", newStatus)
            null
        }.addOnSuccessListener {
            showMessage("Delivery status updated: $newStatus")
        }.addOnFailureListener { error ->
            showMessage("Update failed: ${error.message}")
        }
    }

    override fun onDestroy() {
        removeOrderListeners()
        super.onDestroy()
    }
}
