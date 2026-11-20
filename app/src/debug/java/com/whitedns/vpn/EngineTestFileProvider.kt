package com.whitedns.vpn

import androidx.core.content.FileProvider

/** Separate class so the debug-only test provider can coexist with the app's update FileProvider. */
class EngineTestFileProvider : FileProvider()
