package com.example.bikeheartrateapp.wear.presentation
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Wearable

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val sendButton = Button(this).apply {
            text = "Send Test BPM 132"
            setOnClickListener {
                sendBpmToPhone(132)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            addView(statusText)
            addView(sendButton)
        }

        setContentView(layout)
    }

    private fun sendBpmToPhone(bpm: Int) {
        statusText.text = "Sending $bpm..."

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    statusText.text = "No phone connected"
                    return@addOnSuccessListener
                }

                nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(
                            node.id,
                            "/heart_rate",
                            bpm.toString().toByteArray()
                        )
                        .addOnSuccessListener {
                            statusText.text = "Sent BPM $bpm"
                        }
                        .addOnFailureListener { error ->
                            statusText.text = "Send failed: ${error.message}"
                        }
                }
            }
            .addOnFailureListener { error ->
                statusText.text = "Node error: ${error.message}"
            }
    }
}