
package com.marketplace.deliveryrider

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

import com.google.firebase.auth.FirebaseAuth

class MainActivity : Activity() {

    private val auth by lazy {
        FirebaseAuth.getInstance()
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (auth.currentUser?.isAnonymous == true) {
            auth.signOut()
        }

        if (auth.currentUser != null) {
            showHomeScreen()
        } else {
            showLoginScreen()
        }
    }

    private fun setupContent() {
        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
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
            setTextColor(Color.rgb(35, 65, 55))
            setPadding(0, 12, 0, 12)
        })
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showLoginScreen() {
        setupContent()

        heading("Delivery Rider", 30f)
        heading("Sign in to your rider account", 20f)

        content.addView(TextView(this).apply {
            text = "Use the email and password provided for your rider account."
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
                    showHomeScreen()
                }
                .addOnFailureListener { error ->
                    loginButton.isEnabled = true
                    loginButton.text = "Sign In"

                    showMessage(
                        "Login failed: ${error.message}"
                    )
                }
        }
    }

    private fun showHomeScreen() {
        setupContent()

        heading("Delivery Rider", 30f)

        content.addView(TextView(this).apply {
            text = "Signed in as:\n${auth.currentUser?.email}"
            textSize = 16f
            setPadding(0, 8, 0, 20)
        })

        content.addView(TextView(this).apply {
            text = "Your rider account is signed in. " +
                "Delivery access verification and order claiming " +
                "will be added next."
            textSize = 16f
        })

        content.addView(Button(this).apply {
            text = "Log Out"

            setOnClickListener {
                auth.signOut()
                showLoginScreen()
            }
        })
    }
}
