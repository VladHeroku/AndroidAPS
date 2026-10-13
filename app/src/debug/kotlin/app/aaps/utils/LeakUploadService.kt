package app.aaps.utils

import app.aaps.core.interfaces.utils.fabric.FabricPrivacy

/**
 * Stub retained for debug-source compatibility after removing LeakCanary from the preview build.
 */
class LeakUploadService(
    @Suppress("unused") private val fabricPrivacy: FabricPrivacy
)

class MemoryLeakException(message: String) : Exception(message)
