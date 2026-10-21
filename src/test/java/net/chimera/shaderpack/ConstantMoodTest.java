package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's constantMood (CommonUniforms, PER_TICK): the biome ambience moodiness kept in [0, 1].
 * Chimera refused every program reading it, so Glimmer's world programs fell back.
 */
final class ConstantMoodTest {
    @Test
    void aProgramReadingConstantMoodPlans() {
        UniformRegistry.ProgramInterface plan = UniformRegistry.plan("""
                uniform float constantMood;
                void main() { gl_FragColor = vec4(constantMood); }
                """, UniformRegistry.Stage.POST);
        assertTrue(plan.executable(), plan.deviations().toString());
        assertFalse(plan.deviations().contains("UNIFORM_NAME_UNSUPPORTED:constantMood"));
        UniformRegistry.UniformDescriptor descriptor = UniformRegistry.descriptor("constantMood");
        assertEquals(UniformRegistry.Availability.LIVE, descriptor.availability());
        assertTrue(UniformRegistry.perTick(descriptor), "Iris refreshes constantMood once per tick");
    }

    /** The mixin steps the mood where vanilla reads the mood tick's sky light. */
    @Test
    void theMoodTickReadsTheSkyLight() throws IOException {
        var handler = MixinBytecode.nativeClass("net/minecraft/client/resources/sounds/BiomeAmbientSoundsHandler");
        var tick = handler.methods.stream().filter(method -> method.name.equals("lambda$tick$3")).findFirst()
                .orElseThrow(() -> new AssertionError("re-audit the biome ambience mood lambda"));
        assertEquals("(Lnet/minecraft/world/level/biome/AmbientMoodSettings;)V", tick.desc);
        boolean readsBrightness = false;
        for (var instruction : tick.instructions) {
            readsBrightness |= instruction instanceof MethodInsnNode call && call.name.equals("getBrightness");
        }
        assertTrue(readsBrightness, "the mood lambda reads the sky light at its sampled position");
        var player = MixinBytecode.nativeClass("net/minecraft/client/player/LocalPlayer");
        assertTrue(player.fields.stream().anyMatch(field -> field.name.equals("ambientSoundHandlers")));
    }
}
