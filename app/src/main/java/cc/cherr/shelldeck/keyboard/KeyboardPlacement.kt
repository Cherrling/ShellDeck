package cc.cherr.shelldeck.keyboard

enum class KeyboardPlacement(val label: String) {
    BOTTOM("底部"), RIGHT("右侧"), LEFT("左侧"), AUTO("自动（竖屏底部，横屏右侧）");

    fun resolve(landscape: Boolean): KeyboardPlacement =
        if (this == AUTO) { if (landscape) RIGHT else BOTTOM } else this
}
