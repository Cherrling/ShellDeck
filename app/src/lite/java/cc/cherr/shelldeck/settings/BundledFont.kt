package cc.cherr.shelldeck.settings

import android.content.Context
import android.graphics.Typeface

internal object BundledFont {
    const val defaultId = "system"
    val entries = emptyList<FontEntry>()
    fun load(@Suppress("UNUSED_PARAMETER") context: Context): Typeface = Typeface.MONOSPACE
}
