package com.dp.demo

import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple activity to display privacy policy for Health Connect.
 * Required by Health Connect to explain why the app needs health data access.
 */
class HealthConnectPrivacyPolicy : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }

        val titleView = TextView(this).apply {
            text = "Health Data Privacy Policy"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val contentView = TextView(this).apply {
            text = """
                This app collects health data from Health Connect to support digital phenotyping research.
                
                Data Collected:
                • Heart Rate
                • Steps
                • Sleep Sessions
                • Blood Pressure
                • Weight
                • Oxygen Saturation
                • Respiratory Rate
                
                How We Use Your Data:
                Your health data is collected solely for research purposes. The data is stored securely and used to understand health patterns and behaviors.
                
                Data Sharing:
                Your health data is shared only with authorized researchers and is never sold to third parties.
                
                Your Rights:
                You can revoke access to your health data at any time through the Health Connect app settings.
                
                Contact:
                For questions about your data, please contact the research team.
            """.trimIndent()
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.START
        }

        layout.addView(titleView)
        layout.addView(contentView)
        
        setContentView(layout)
    }
}
