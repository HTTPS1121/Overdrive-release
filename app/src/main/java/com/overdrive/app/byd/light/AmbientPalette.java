package com.overdrive.app.byd.light;

/** Maps any RGB colour to the nearest entry of the car's ambient palette (1-based index). */
public final class AmbientPalette {

    private AmbientPalette() {}

    /**
     * DiLink 5's seven named IAL colours, in SDK order (IAL_ICE_BLUE=1 … IAL_DARK_BLUE=7).
     * The SDK names them without RGB values, so these are approximations.
     */
    public static final String[] DILINK5_SEVEN_COLOURS = {
            "#7FD4FF", // ice blue
            "#EAF6FF", // cold white
            "#FFE2B0", // warm white
            "#FF5A1F", // red-orange
            "#FF0000", // true red
            "#D0208F", // red-purple
            "#0030FF"  // dark blue
    };

    /** The RGB table for a car reporting {@code count} colours, or null when none is known. */
    public static String[] paletteFor(int count, boolean diLink5) {
        if (count == LightConstants.AMBIENT_COLOURS.length) return LightConstants.AMBIENT_COLOURS;
        if (diLink5 && count == DILINK5_SEVEN_COLOURS.length) return DILINK5_SEVEN_COLOURS;
        return null;
    }

    /** Strict {@code #RRGGBB} or {@code RRGGBB} to 0xRRGGBB, else -1. */
    public static int parseRgb(String value) {
        if (value == null) return -1;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return -1;
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return -1;
        }
        return Integer.parseInt(hex, 16);
    }

    public static String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /**
     * 1-based index of the palette colour nearest {@code rgb} in CIELAB. Both sides are scaled to
     * full value first: brightness is the car's separate control, so only hue and saturation
     * pick the colour. Returns -1 for black, which has no hue.
     */
    public static int nearestIndex(int rgb, String[] palette) {
        if (palette == null) return -1;
        double[] target = labAtFullValue(rgb);
        if (target == null) return -1;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int colour = parseRgb(palette[i]);
            double[] lab = colour < 0 ? null : labAtFullValue(colour);
            if (lab == null) continue;
            double dl = target[0] - lab[0];
            double da = target[1] - lab[1];
            double db = target[2] - lab[2];
            double distance = dl * dl + da * da + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i + 1;
            }
        }
        return best;
    }

    private static double[] labAtFullValue(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max == 0) return null;
        double scale = 255.0 / max;
        double lr = linear(r * scale / 255.0);
        double lg = linear(g * scale / 255.0);
        double lb = linear(b * scale / 255.0);
        // sRGB (D65) → XYZ, normalised by the D65 white point.
        double x = (0.4124 * lr + 0.3576 * lg + 0.1805 * lb) / 0.95047;
        double y = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
        double z = (0.0193 * lr + 0.1192 * lg + 0.9505 * lb) / 1.08883;
        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);
        return new double[]{116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16.0) / 116.0;
    }
}
