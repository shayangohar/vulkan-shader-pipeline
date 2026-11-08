package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Encoding through the rounding steps equals rounding the sRGB curve, and decoding inverts it. */
final class SrgbChannelsTest {
    @Test
    void encodingMatchesTheRoundedCurve() {
        for (int sample = 0; sample <= 400_000; sample++) {
            double linear = sample / 400_000.0;
            assertEquals(curve(linear), SrgbChannels.encode(linear), "linear " + linear);
        }
    }

    @Test
    void everyChannelValueSurvivesARoundTrip() {
        for (int value = 0; value < 256; value++) {
            assertEquals(value, SrgbChannels.encode(SrgbChannels.linear(value)));
        }
    }

    @Test
    void encodingClampsOutsideTheUnitRange() {
        assertEquals(0, SrgbChannels.encode(-0.5));
        assertEquals(255, SrgbChannels.encode(1.5));
    }

    private static int curve(double linear) {
        double encoded = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.min(1, Math.max(0, encoded)) * 255);
    }
}
