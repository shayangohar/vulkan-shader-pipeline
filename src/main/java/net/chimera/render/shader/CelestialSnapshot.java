package net.chimera.render.shader;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * One derivation of where the sun, the moon and the light are in a frame, shared by the pack
 * uniforms and by the shadow matrix so a caster and a receiver cannot disagree about the light.
 *
 * <p>Ported from Iris through the Vitrail reference, whose {@code celestial} transform is the sky
 * renderer's own, moved forward so a pack can read the result before the host applies it. The
 * angles are host attributes sampled at the frame's partial tick, which is why an option, a
 * datapack or a dimension that moves the sky moves the shadows with it.
 *
 * <p>The pack's {@code sunPathRotation} turns the whole frame about the view's Z axis.
 * Chimera's own {@code sunPathOffset} shifts the angle itself, before that turn.
 */
final class CelestialSnapshot {
    /** The quarter turn the sky places a body with, carried in the format's reference. */
    private static final float QUARTER_TURN_DEGREES = -90.0F;
    /** Positions stay unnormalised: a body sits a hundred units out, as the format expects. */
    private static final float CELESTIAL_LENGTH = 100.0F;

    private static final Matrix4f SCRATCH = new Matrix4f();
    private static final Vector4f VECTOR = new Vector4f();

    private CelestialSnapshot() {}

    /**
     * The pack-facing angle in turns: the raw attribute plus the quarter turn, wrapped once.
     * One step of wrapping, not a modulus, so a value just outside the range lands where the
     * reference lands rather than a turn away.
     */
    static float angle01(float angleDegrees) {
        return wrapped(angleDegrees) / 360.0F;
    }

    /** Whether the sun, not the moon, is the body that lights the world at this raw angle. */
    static boolean isDay(float sunAngleDegrees) {
        return wrapped(sunAngleDegrees) < 180.0F;
    }

    private static float wrapped(float angleDegrees) {
        float angle = angleDegrees + 90.0F;
        if (angle < 0.0F) {
            angle += 360.0F;
        } else if (angle > 360.0F) {
            angle -= 360.0F;
        }
        return angle;
    }

    /**
     * Eye-space sun or moon position, unnormalised, at the frame's offset angle.
     * The angle is where the body is in the sky; the view is what makes the result readable by a
     * pack that samples the same sky the player sees.
     */
    static void position(Matrix4f gbufferModelView, float sunPathRotation, float offsetDegrees,
                         float angleDegrees, Vector3f dest) {
        SCRATCH.set(gbufferModelView)
                .rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(angleDegrees + offsetDegrees));
        VECTOR.set(0.0F, CELESTIAL_LENGTH, 0.0F, 1.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z);
    }

    /**
     * Eye-space up: the same quarter turn, and deliberately not the sky angle, because this one is
     * where up is, not where the sun is.
     */
    static void upPosition(Matrix4f gbufferModelView, Vector3f dest) {
        SCRATCH.set(gbufferModelView).rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES));
        VECTOR.set(0.0F, CELESTIAL_LENGTH, 0.0F, 0.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z);
    }

    /**
     * World-space unit light direction for the shadow matrix, without the model view: the shadow
     * caster is built from this and the pack sees the same body through {@link #position}.
     */
    static void lightVector(float sunPathRotation, float offsetDegrees, float angleDegrees,
                            Vector3f dest) {
        SCRATCH.identity()
                .rotateY((float) Math.toRadians(QUARTER_TURN_DEGREES))
                .rotateZ((float) Math.toRadians(sunPathRotation))
                .rotateX((float) Math.toRadians(angleDegrees + offsetDegrees));
        VECTOR.set(0.0F, 1.0F, 0.0F, 0.0F);
        SCRATCH.transform(VECTOR);
        dest.set(VECTOR.x, VECTOR.y, VECTOR.z).normalize();
    }

    /** The host's packed sky colour, as the format hands it to a pack: components over 255. */
    static void skyColor(int packed, Vector3f dest) {
        dest.set(((packed >> 16) & 0xFF) / 255.0F,
                ((packed >> 8) & 0xFF) / 255.0F,
                (packed & 0xFF) / 255.0F);
    }
}
