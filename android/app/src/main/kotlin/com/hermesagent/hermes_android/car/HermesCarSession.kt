package com.hermesagent.hermes_android.car

import androidx.car.app.Session
import android.content.Intent

/**
 * Creates the initial screen for the Hermes Android Auto app.
 */
class HermesCarSession : Session() {
    override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
        return HermesStatusScreen(carContext)
    }
}
