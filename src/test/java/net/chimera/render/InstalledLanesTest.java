package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.InstalledLanes;
import net.chimera.shaderpack.MixinBytecode;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack load hands the family bridge its lanes as one InstalledLanes. The set's pipelines are
 * all GraphicsPipelines, so a swapped argument would compile and draw one family with another's
 * program; each PackPipelineSet field goes into the component of its own name.
 */
final class InstalledLanesTest {
    private static final String SET = "net/chimera/render/PackPipelineSet";

    @Test
    void eachPipelineGoesIntoItsNamesake() throws IOException {
        MethodNode lanes = MixinBytecode.nativeClass(SET).methods.stream()
                .filter(method -> method.name.equals("lanes")).findFirst().orElseThrow();
        List<String> reads = new ArrayList<>();
        lanes.instructions.forEach(instruction -> {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(SET)) {
                reads.add(field.name);
            }
        });
        assertEquals("hostLayout", reads.remove(0), "the host-layout lanes are gathered first");
        List<String> components = Arrays.stream(InstalledLanes.class.getRecordComponents())
                .map(component -> component.getName())
                .filter(name -> !name.equals("hostLayout") && !name.equals("entityIds"))
                .collect(Collectors.toList());
        assertEquals(components, reads);
    }

    @Test
    void noPackInstallsNoLane() {
        assertFalse(InstalledLanes.NONE.any());
        assertTrue(InstalledLanes.NONE.hostLayout().isEmpty());
        Map<ChimeraEntityBridge.Family, GraphicsPipeline> weatherWithoutProgram =
                new EnumMap<>(ChimeraEntityBridge.Family.class);
        weatherWithoutProgram.put(ChimeraEntityBridge.Family.WEATHER, null);
        InstalledLanes hand = new InstalledLanes(null, null, null, null, null, null, null, null, null, null,
                new PackPipelines.PackHand(Map.of()), null, null, null, weatherWithoutProgram, null, null, null, null);
        assertTrue(hand.any(), "a hand program is a lane");
        assertTrue(hand.hostLayout().isEmpty(), "a host-layout lane without a pipeline keeps its host draw");
    }
}
