package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.shaderpack.PackRenderingPhase;

import java.util.Arrays;
import java.util.List;

/**
 * The Iris shader keys Chimera serves on the host's own vertex layout, in the main pass and the
 * shadow pass (Iris MixinGameRenderer's shader overrides). Each lane owns its draws at
 * BufferUploader.drawWithShader (ChimeraBufferUploaderMixin): the host mesh, draw call and raster
 * state stay, and only the program and its targets change. This table answers which lane a draw
 * belongs to by its host shader and format, which pack program serves it, the Iris rendering
 * phase it draws in and the alpha test of its Iris ShaderKey.
 *
 * <p>Depth-only main-pass shaders Iris also maps (the water mask, the dragon rays' depth) write no
 * colour, so their host draw is equivalent and they are not main-pass lanes. In the shadow pass
 * every draw writes the shadow map, so the water mask draws with SHADOW_BASIC.</p>
 */
public enum HostLayoutLane {
    /** Iris WEATHER: rain and snow, drawn with the particle shader inside renderSnowAndRain. */
    WEATHER(ChimeraEntityBridge.Family.WEATHER, "gbuffers_weather", DefaultVertexFormat.PARTICLE,
            PackRenderingPhase.RAIN_SNOW, Pass.WEATHER, AlphaTest.ONE_TENTH, "particle"),
    /** Iris LINES: block outlines, fishing lines and other line draws. */
    LINE(ChimeraEntityBridge.Family.LINE, "gbuffers_line", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            PackRenderingPhase.OUTLINE, Pass.WORLD, AlphaTest.OFF, "rendertype_lines"),
    /** Iris GLINT in the world; Iris draws world glint inside the entity phase. */
    GLINT(ChimeraEntityBridge.Family.GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.NON_ZERO, Shaders.GLINT),
    /** First-person glint: the same program under the hand projection. */
    HAND_GLINT(ChimeraEntityBridge.Family.HAND_GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.HAND_SOLID, Pass.HAND, AlphaTest.NON_ZERO, Shaders.GLINT),
    /** Iris LIGHTNING: lightning bolts and dragon death rays. */
    LIGHTNING(ChimeraEntityBridge.Family.LIGHTNING, "gbuffers_lightning", DefaultVertexFormat.POSITION_COLOR,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, "rendertype_lightning"),
    /** Iris BEACON: beacon and end gateway beams. */
    BEACON_BEAM(ChimeraEntityBridge.Family.BEACON_BEAM, "gbuffers_beaconbeam", DefaultVertexFormat.BLOCK,
            PackRenderingPhase.BLOCK_ENTITIES, Pass.WORLD, AlphaTest.OFF, "rendertype_beacon_beam"),
    /** Iris LEASH: leads, drawn with gbuffers_basic. */
    LEASH(ChimeraEntityBridge.Family.LEASH, "gbuffers_basic", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, "rendertype_leash"),
    /** Iris TEXT_BG: text and name-tag backgrounds, with gbuffers_entities_translucent's chain. */
    TEXT_BG(ChimeraEntityBridge.Family.TEXT_BG, "text_bg", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.ONE_TENTH, Shaders.TEXT_BACKGROUND),
    /*
     * The shadow pass. Iris ShadowRenderer holds the ENTITIES phase while it draws entities and
     * block entities, so every shadow lane reports it. Iris assigns no shadow key to text intensity
     * (its swizzle is a GL texture state) and cancels beacon beams; those draws stay out of the
     * shadow map.
     */
    /** Iris SHADOW_LINES: fishing lines and hitboxes, the shadow program widened like gbuffers_basic. */
    SHADOW_LINES(ChimeraEntityBridge.Family.SHADOW_LINES, "shadow_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_lines"),
    /** Iris SHADOW_LEASH: leads. */
    SHADOW_LEASH(ChimeraEntityBridge.Family.SHADOW_LEASH, "shadow_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_leash"),
    /** Iris SHADOW_LIGHTNING: lightning bolts, through shadow_lightning, shadow_entities and shadow. */
    SHADOW_LIGHTNING(ChimeraEntityBridge.Family.SHADOW_LIGHTNING, "shadow_lightning",
            DefaultVertexFormat.POSITION_COLOR, PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF,
            "rendertype_lightning"),
    /** Iris GLINT -> SHADOW_ENTITIES_CUTOUT: glint layers on items already in the map. */
    SHADOW_GLINT(ChimeraEntityBridge.Family.SHADOW_GLINT, "shadow_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.ONE_TENTH, Shaders.GLINT),
    /** Iris SHADOW_TEXT: sign text, text displays and name tags. */
    SHADOW_TEXT(ChimeraEntityBridge.Family.SHADOW_TEXT, "shadow_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.NON_ZERO, "rendertype_text",
            "rendertype_text_see_through"),
    /** Iris SHADOW_TEXT_BG: text and name tag backgrounds. */
    SHADOW_TEXT_BG(ChimeraEntityBridge.Family.SHADOW_TEXT_BG, "shadow_text_bg",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, PackRenderingPhase.ENTITIES, Pass.SHADOW,
            AlphaTest.NON_ZERO, Shaders.TEXT_BACKGROUND),
    /** Iris SHADOW_BASIC: the boat water mask, depth only like its host shader. */
    SHADOW_BASIC(ChimeraEntityBridge.Family.SHADOW_BASIC, "shadow_basic", DefaultVertexFormat.POSITION,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, "rendertype_water_mask");

    /** The 1.21.1 shaders several lanes share. */
    private static final class Shaders {
        static final String[] GLINT = {"rendertype_glint", "rendertype_glint_translucent", "rendertype_entity_glint",
                "rendertype_entity_glint_direct", "rendertype_armor_entity_glint"};
        static final String[] TEXT_BACKGROUND = {"rendertype_text_background",
                "rendertype_text_background_see_through"};
    }

    /** Where a lane's draws happen. */
    public enum Pass {
        WORLD,
        /** LevelRenderer.renderSnowAndRain. */
        WEATHER,
        /** First person: drawn under the hand projection, only while the hand renders. */
        HAND,
        /** The pack shadow pass's caster window (ChimeraShadowCasters). */
        SHADOW
    }

    /** Iris AlphaTests of the lane's ShaderKey. */
    private enum AlphaTest {
        OFF(0.0F),
        NON_ZERO(HostAlphaTest.NON_ZERO_ALPHA),
        ONE_TENTH(HostAlphaTest.ONE_TENTH_ALPHA);

        private final float reference;

        AlphaTest(float reference) {
            this.reference = reference;
        }
    }

    private final ChimeraEntityBridge.Family family;
    private final String program;
    private final VertexFormat format;
    private final PackRenderingPhase phase;
    private final Pass pass;
    private final AlphaTest alphaTest;
    private final List<String> shaders;

    HostLayoutLane(ChimeraEntityBridge.Family family, String program, VertexFormat format,
                   PackRenderingPhase phase, Pass pass, AlphaTest alphaTest, String... shaders) {
        this.family = family;
        this.program = program;
        this.format = format;
        this.phase = phase;
        this.pass = pass;
        this.alphaTest = alphaTest;
        this.shaders = List.of(shaders);
    }

    public ChimeraEntityBridge.Family family() {
        return family;
    }

    /** The pack program that serves this lane, through the Iris fallback chain. */
    public String program() {
        return program;
    }

    public VertexFormat format() {
        return format;
    }

    /** The host shaders, by name, whose draws this lane takes. */
    public List<String> shaders() {
        return shaders;
    }

    public Pass pass() {
        return pass;
    }

    /** The Iris rendering phase (renderStage) of this lane's draws. */
    public PackRenderingPhase phase() {
        if (pass == Pass.HAND) {
            return ChimeraHandRenderer.translucentPhase()
                    ? PackRenderingPhase.HAND_TRANSLUCENT : PackRenderingPhase.HAND_SOLID;
        }
        return phase;
    }

    /** The discard threshold of the lane's Iris ShaderKey, or 0 for AlphaTests.OFF. */
    public float alphaReference() {
        return alphaTest.reference;
    }

    /** Iris ExtendedShader binds a white texture to unit 0 for a format without texture coordinates. */
    public boolean whiteAlbedo() {
        return !format.contains(VertexFormatElement.UV0);
    }

    /** The lane that owns a host draw in a pass, or null when the draw keeps its host program. */
    public static HostLayoutLane forDraw(String shader, VertexFormat format, Pass pass) {
        for (HostLayoutLane lane : values()) {
            if (lane.pass == pass && lane.format == format && lane.shaders.contains(shader)) {
                return lane;
            }
        }
        return null;
    }

    /** The lane a bridge family names, or null for a family drawn another way. */
    public static HostLayoutLane of(ChimeraEntityBridge.Family family) {
        for (HostLayoutLane lane : values()) {
            if (lane.family == family) return lane;
        }
        return null;
    }

    /** The lanes one pack program serves, world lane first. */
    public static List<HostLayoutLane> forProgram(String program) {
        return Arrays.stream(values()).filter(lane -> lane.program.equals(program)).toList();
    }
}
