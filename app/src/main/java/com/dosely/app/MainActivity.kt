package com.dosely.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dosely.app.reminder.Notifications
import com.dosely.app.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.ensureChannels(this)
        val startRoute = intent?.getStringExtra(Notifications.EXTRA_ROUTE)
        setContent {
            AppRoot(initialRoute = startRoute)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.dosely.app.billing.SubscriptionManager.get(this).refresh()
        com.dosely.app.wear.PhoneWatchSync.enqueue(this)
    }
}
