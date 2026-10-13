package cc.cherr.shelldeck.settings

import android.content.Context
import android.graphics.Typeface

internal object BundledFont {
    const val defaultId = "maple"
    val entries = listOf(FontEntry("maple", "Maple Mono NF CN"))
    fun load(context: Context): Typeface = context.resources.getFont(cc.cherr.shelldeck.R.font.maple_mono_nf_cn_regular)
}
