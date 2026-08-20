package com.whitedns.vpn

import android.content.res.Configuration

internal fun isTelevisionUiMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

internal fun televisionSafeInsets(uiMode: Int, widthPixels: Int, heightPixels: Int): Pair<Int, Int> =
    if (isTelevisionUiMode(uiMode)) widthPixels / 20 to heightPixels / 20 else 0 to 0
