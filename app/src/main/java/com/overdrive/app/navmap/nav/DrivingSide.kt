package com.overdrive.app.navmap.nav

/**
 * Left- or right-hand traffic at a position, from coarse country boxes. Only used to mirror turn and lane
 * glyphs, so a border-area miss costs a mirrored U-turn arrow, nothing more.
 */
object DrivingSide {

    // Each row: south, north, west, east. Right-hand exceptions are tested first.
    private val RIGHT = arrayOf(
        doubleArrayOf(4.5, 21.2, 116.8, 126.7),   // Philippines
        doubleArrayOf(9.5, 23.5, 100.0, 109.5),   // Cambodia, Laos, Vietnam
        doubleArrayOf(18.0, 28.6, 92.2, 101.2),   // Myanmar (north of Thailand's box)
    )
    private val LEFT = arrayOf(
        doubleArrayOf(5.5, 37.2, 60.8, 97.5),     // India, Pakistan, Nepal, Bhutan, Bangladesh, Sri Lanka
        doubleArrayOf(5.5, 20.5, 97.3, 105.7),    // Thailand
        doubleArrayOf(-11.0, 7.5, 95.0, 141.0),   // Malaysia, Singapore, Brunei, Indonesia, Timor-Leste
        doubleArrayOf(-11.7, -1.0, 140.8, 156.0), // Papua New Guinea
        doubleArrayOf(24.0, 45.7, 122.9, 154.0),  // Japan
        doubleArrayOf(22.1, 22.6, 113.8, 114.5),  // Hong Kong, Macau
        doubleArrayOf(-44.0, -10.0, 112.9, 153.7),// Australia
        doubleArrayOf(-47.4, -34.3, 166.3, 178.7),// New Zealand
        doubleArrayOf(49.8, 60.9, -10.7, 1.8),    // United Kingdom, Ireland
        doubleArrayOf(34.5, 35.7, 32.2, 34.6),    // Cyprus
        doubleArrayOf(35.8, 36.1, 14.1, 14.6),    // Malta
        doubleArrayOf(-35.0, -15.5, 11.5, 41.0),  // South Africa and neighbours
        doubleArrayOf(-11.8, 5.1, 29.3, 42.0),    // Kenya, Tanzania, Uganda
    )

    fun isLeftHand(lat: Double, lng: Double): Boolean {
        if (lat.isNaN() || lng.isNaN()) return false
        for (b in RIGHT) if (lat in b[0]..b[1] && lng in b[2]..b[3]) return false
        for (b in LEFT) if (lat in b[0]..b[1] && lng in b[2]..b[3]) return true
        return false
    }
}
