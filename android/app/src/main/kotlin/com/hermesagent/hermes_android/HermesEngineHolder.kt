package com.hermesagent.hermes_android

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache

/**
 * Holds the FlutterEngine used for method-channel callbacks from Android Auto
 * receivers when the UI process may not be running.
 */
object HermesEngineHolder {

    private const val ENGINE_ID = "hermes_engine"

    @Volatile
    var engine: FlutterEngine? = null
        private set

    fun attach(engine: FlutterEngine) {
        this.engine = engine
        if (FlutterEngineCache.getInstance().get(ENGINE_ID) == null) {
            FlutterEngineCache.getInstance().put(ENGINE_ID, engine)
        }
    }

    fun detach() {
        this.engine = null
        FlutterEngineCache.getInstance().remove(ENGINE_ID)
    }
}
