package net.chimera.render.shader;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * ItemRenderer.render has no finally either: an item whose renderer throws stays current, and
     * every later entity draw carried its currentRenderedItemId.
     */
    @Test
    void anItemLeftCurrentUnwindsAsTheCasterPassAndTheNextFrameBegin() {
        try {
            RenderedItemIds.push(5, 0);
            RenderedItemIds.push(7, 0);
            ChimeraEntityBridge.endShadowCasters();
            assertEquals(0, RenderedItemIds.current(), "the caster window unwinds items its failed draws left");
            RenderedItemIds.push(3, 0);
            RenderedItemIds.pop();
            assertEquals(0, RenderedItemIds.current(), "a later item pops back to none, not to a stale item");

            RenderedItemIds.push(9, 0);
            ChimeraEntityBridge.beginWorldWindow();
            assertEquals(0, RenderedItemIds.current(), "the next frame's world window unwinds it too");
        } finally {
            RenderedItemIds.install(null);
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
