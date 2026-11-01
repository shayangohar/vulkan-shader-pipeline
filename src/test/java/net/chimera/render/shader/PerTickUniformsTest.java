package net.chimera.render.shader;

import net.chimera.shaderpack.UniformRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's PER_TICK uniforms refresh only when the game tick changes (ProgramUniforms.update);
 * Chimera rewrote every binding every frame, so rain fades and cloud motion moved between ticks.
 */
final class PerTickUniformsTest {
    @Test
    void aPerTickBindingHoldsBetweenTicksAndAFrameValueDoesNot() {
        for (String name : List.of("rainStrength", "wetness", "worldTime", "cloudTime", "currentPlayerHealth",
                "constantMood")) {
            UniformRegistry.UniformDescriptor descriptor = UniformRegistry.descriptor(name);
            assertFalse(PackUniformProvider.refreshes(descriptor, false), name);
            assertTrue(PackUniformProvider.refreshes(descriptor, true), name);
        }
        for (String name : List.of("frameTimeCounter", "thunderStrength", "cameraPosition", "fogMode")) {
            assertTrue(PackUniformProvider.refreshes(UniformRegistry.descriptor(name), false), name);
        }
    }

    /** A pack's custom value of an Iris name is not Iris's PER_TICK uniform. */
    @Test
    void onlyTheCatalogValueIsPerTick() {
        UniformRegistry.UniformDescriptor custom = new UniformRegistry.UniformDescriptor("rainStrength",
                List.of("float"), UniformRegistry.Availability.LIVE, "custom:rainStrength",
                UniformRegistry.DefaultPolicy.ZERO);
        assertTrue(PackUniformProvider.refreshes(custom, false));
    }

    /**
     * Iris's constantMood (CommonUniforms, PER_TICK): the biome ambience moodiness kept in [0, 1].
     * Chimera refused every program reading it, so Glimmer's world programs fell back.
     */
    @Test
    void aProgramReadingConstantMoodPlans() {
        UniformRegistry.ProgramInterface plan = UniformRegistry.plan("""
                uniform float constantMood;
                void main() { gl_FragColor = vec4(constantMood); }
                """, UniformRegistry.Stage.POST);
        assertTrue(plan.executable(), plan.deviations().toString());
        assertFalse(plan.deviations().contains("UNIFORM_NAME_UNSUPPORTED:constantMood"));
    }
}
