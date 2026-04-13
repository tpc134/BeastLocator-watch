package com.ruich97.beastlocatorwatch

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.welcomeStartButton).setOnClickListener {
            DestinationStore(this).setWelcomeCompleted(true)
            finish()
        }
    }
}
