package com.overdrive.app.overlay

/** Stored [iconOpacity] / [iconSizeDp] keys in the statusOverlay config section. */
object StatusOverlayIconAppearance {
    const val OPACITY_MIN = 20
    const val OPACITY_MAX = 100
    /** Matches fragment_settings_overlay icon transparency slider range. */
    const val TRANSPARENCY_SLIDER_MIN = 0
    const val TRANSPARENCY_SLIDER_MAX = 80
    const val TRANSPARENCY_SLIDER_STEP = 5
    const val SIZE_MIN_DP = 12
    const val SIZE_MAX_DP = 32

    @JvmStatic
    fun clampStoredOpacity(value: Int): Int = value.coerceIn(OPACITY_MIN, OPACITY_MAX)

    @JvmStatic
    fun clampStoredSizeDp(value: Int): Int = value.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)

    @JvmStatic
    fun clampSliderTransparency(transparency: Int): Int =
        transparency.coerceIn(TRANSPARENCY_SLIDER_MIN, TRANSPARENCY_SLIDER_MAX)

    /**
     * UI slider is transparency percent (higher = more transparent).
     * Persisted [iconOpacity] stays icon alpha percent (higher = less transparent).
     */
    @JvmStatic
    fun sliderTransparencyFromStoredOpacity(storedOpacity: Int): Int =
        clampSliderTransparency(100 - clampStoredOpacity(storedOpacity))

    @JvmStatic
    fun storedOpacityFromSliderTransparency(transparency: Int): Int =
        clampStoredOpacity(100 - clampSliderTransparency(transparency))

    @JvmStatic
    fun snapOpacitySliderValue(storedOpacity: Int): Float {
        val transparency = sliderTransparencyFromStoredOpacity(storedOpacity)
        return (TRANSPARENCY_SLIDER_MIN +
            (transparency - TRANSPARENCY_SLIDER_MIN) / TRANSPARENCY_SLIDER_STEP * TRANSPARENCY_SLIDER_STEP)
            .toFloat()
    }

    @JvmStatic
    fun snapSizeSliderValue(sizeDp: Int): Float {
        val clamped = clampStoredSizeDp(sizeDp)
        return (SIZE_MIN_DP + (clamped - SIZE_MIN_DP) / 2 * 2).toFloat()
    }
}
