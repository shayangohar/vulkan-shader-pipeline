package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A family draw opens in FamilyRenderType.setupRenderState and closes in clearRenderState, but
 * RenderType.draw has no finally. The shadow pass catches a caster draw that throws and carries on,
 * so a draw left open kept handing its pipeline to every later ShaderInstance draw.
 */
final class EntityDrawWindowTest {
    @Test
    void anOpenDrawClosesAsTheCasterPassAndTheNextFrameBegin() throws ReflectiveOperationException {
        try {
            leaveDrawOpen();
            ChimeraEntityBridge.endShadowCasters();
            assertFalse(drawActive(), "the caster window closes a draw its failed batch left open");

            leaveDrawOpen();
            ChimeraEntityBridge.beginWorldWindow();
            assertFalse(drawActive(), "the next frame's world window closes it too");
        } finally {
            field("drawActive").setBoolean(null, false);
            ChimeraEntityBridge.endWorldWindow();
        }
    }

    private static void leaveDrawOpen() throws ReflectiveOperationException {
        field("drawActive").setBoolean(null, true);
        assertTrue(drawActive());
    }

    private static boolean drawActive() throws ReflectiveOperationException {
        return field("drawActive").getBoolean(null);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ChimeraEntityBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
