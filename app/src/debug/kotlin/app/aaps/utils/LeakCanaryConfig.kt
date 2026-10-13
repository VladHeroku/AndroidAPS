package app.aaps.utils

import app.aaps.core.interfaces.utils.fabric.FabricPrivacy

/**
 * Adaptive Meal preview builds deliberately disable LeakCanary so no separate "Leaks"
 * launcher/application is installed next to AAPS.
 */
fun configureLeakCanary(isEnabled: Boolean = false, fabricPrivacy: FabricPrivacy? = null) {
    // Intentionally no-op in this preview build.
}
