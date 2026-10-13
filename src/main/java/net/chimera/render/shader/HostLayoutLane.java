package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Arrays;
import java.util.List;

/**
 * The Iris IrisPipelines mappings Chimera serves on the host's own vertex layout, in the main
 * pass (assignToMain) and the shadow pass (assignToShadow). Each lane owns its draws at
 * RenderType.draw (ChimeraRenderTypeMixin): the host mesh, draw call and raster state stay, and
 * only the program and its targets change. This table answers which lane a draw belongs to,
 * which host pipelines and format the lane accepts, which pack program serves it, the Iris
 * rendering phase it draws in and the alpha test of its Iris ShaderKey.
 *
 * <p>Depth-only main-pass pipelines Iris also maps (DRAGON_RAYS_DEPTH to LIGHTNING, WATER_MASK to
 * BASIC) write no colour, so their host draw is equivalent and they are not main-pass lanes.
 * In the shadow pass every draw writes the shadow map, so WATER_MASK draws with SHADOW_BASIC.</p>
 */
public enum HostLayoutLane {
    /** Iris WEATHER: rain and snow. */
    WEATHER(ChimeraEntityBridge.Family.WEATHER, "gbuffers_weather", DefaultVertexFormat.PARTICLE,
            PackRenderingPhase.RAIN_SNOW, Pass.WORLD, AlphaTest.ONE_TENTH,
            RenderPipelines.WEATHER_DEPTH_WRITE, RenderPipelines.WEATHER_NO_DEPTH_WRITE),
    /** Iris LINES: block outlines, fishing lines and other line draws. */
    LINE(ChimeraEntityBridge.Family.LINE, "gbuffers_line", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH,
            PackRenderingPhase.OUTLINE, Pass.WORLD, AlphaTest.OFF,
            RenderPipelines.LINES, RenderPipelines.LINES_TRANSLUCENT, RenderPipelines.SECONDARY_BLOCK_OUTLINE),
    /** Iris GLINT in the world; Iris draws world glint inside the entity phase. */
    GLINT(ChimeraEntityBridge.Family.GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.NON_ZERO, RenderPipelines.GLINT),
    /** First-person glint: the same program under the hand projection. */
    HAND_GLINT(ChimeraEntityBridge.Family.HAND_GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.HAND_SOLID, Pass.HAND, AlphaTest.NON_ZERO, RenderPipelines.GLINT),
    /** Iris LIGHTNING: lightning bolts and dragon death rays. */
    LIGHTNING(ChimeraEntityBridge.Family.LIGHTNING, "gbuffers_lightning", DefaultVertexFormat.POSITION_COLOR,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, RenderPipelines.LIGHTNING, RenderPipelines.DRAGON_RAYS),
    /** Iris BEACON: beacon and end gateway beams. */
    BEACON_BEAM(ChimeraEntityBridge.Family.BEACON_BEAM, "gbuffers_beaconbeam", DefaultVertexFormat.BLOCK,
            PackRenderingPhase.BLOCK_ENTITIES, Pass.WORLD, AlphaTest.OFF,
            RenderPipelines.BEACON_BEAM_OPAQUE, RenderPipelines.BEACON_BEAM_TRANSLUCENT),
    /** Iris LEASH: leads, drawn with gbuffers_basic. */
    LEASH(ChimeraEntityBridge.Family.LEASH, "gbuffers_basic", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.WORLD, AlphaTest.OFF, RenderPipelines.LEASH),
    /*
     * The shadow pass. Iris ShadowRenderer holds the ENTITIES phase while it draws entity and
     * block-entity features, so every shadow lane reports it. Iris assigns no shadow key to
     * LINES_TRANSLUCENT, DRAGON_RAYS or TEXT_INTENSITY (its intensity swizzle is a GL texture
     * state) and cancels beacon beams; those draws stay out of the shadow map.
     */
    /** Iris SHADOW_LINES: fishing lines and hitboxes, the shadow program widened like gbuffers_basic. */
    SHADOW_LINES(ChimeraEntityBridge.Family.SHADOW_LINES, "shadow_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PackRenderingPhase.ENTITIES, Pass.SHADOW,
            AlphaTest.OFF, RenderPipelines.LINES, RenderPipelines.SECONDARY_BLOCK_OUTLINE),
    /** Iris SHADOW_LEASH: leads. */
    SHADOW_LEASH(ChimeraEntityBridge.Family.SHADOW_LEASH, "shadow_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, RenderPipelines.LEASH),
    /** Iris SHADOW_LIGHTNING: lightning bolts, through shadow_lightning, shadow_entities and shadow. */
    SHADOW_LIGHTNING(ChimeraEntityBridge.Family.SHADOW_LIGHTNING, "shadow_lightning", DefaultVertexFormat.POSITION_COLOR,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, RenderPipelines.LIGHTNING),
    /** Iris GLINT -> SHADOW_ENTITIES_CUTOUT: glint layers on items already in the map. */
    SHADOW_GLINT(ChimeraEntityBridge.Family.SHADOW_GLINT, "shadow_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.ONE_TENTH, RenderPipelines.GLINT),
    /** Iris SHADOW_TEXT: sign text, text displays and name tags. */
    SHADOW_TEXT(ChimeraEntityBridge.Family.SHADOW_TEXT, "shadow_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.NON_ZERO,
            RenderPipelines.TEXT, RenderPipelines.TEXT_POLYGON_OFFSET, RenderPipelines.TEXT_SEE_THROUGH),
    /** Iris SHADOW_TEXT_BG: text and name tag backgrounds. */
    SHADOW_TEXT_BG(ChimeraEntityBridge.Family.SHADOW_TEXT_BG, "shadow_text_bg", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.NON_ZERO,
            RenderPipelines.TEXT_BACKGROUND, RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH),
    /** Iris SHADOW_BASIC: the boat water mask, depth only like its host pipeline. */
    SHADOW_BASIC(ChimeraEntityBridge.Family.SHADOW_BASIC, "shadow_basic", DefaultVertexFormat.POSITION,
            PackRenderingPhase.ENTITIES, Pass.SHADOW, AlphaTest.OFF, RenderPipelines.WATER_MASK);

    /** Where a lane's draws happen. */
    public enum Pass {
        WORLD,
        /** First person: drawn under the hand projection, only while the hand renders. */
        HAND,
        /** The pack shadow pass's caster window (ChimeraShadowCasters). */
        SHADOW;

        /** The pass a host draw belongs to now. */
        public static Pass current() {
            if (ChimeraEntityBridge.isShadowCasterPass()) return SHADOW;
            return ChimeraHandRenderer.active() ? HAND : WORLD;
        }
    }

    /** Iris AlphaTests of the lane's ShaderKey. */
    private enum AlphaTest {
        OFF(0.0F),
        /** NON_ZERO_ALPHA: GREATER 0.0001. */
        NON_ZERO(0.0001F),
        /** ONE_TENTH_ALPHA: GREATER 0.1. */
        ONE_TENTH(0.1F);

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
    private final List<RenderPipeline> pipelines;

    HostLayoutLane(ChimeraEntityBridge.Family family, String program, VertexFormat format,
                   PackRenderingPhase phase, Pass pass, AlphaTest alphaTest, RenderPipeline... pipelines) {
        this.family = family;
        this.program = program;
        this.format = format;
        this.phase = phase;
        this.pass = pass;
        this.alphaTest = alphaTest;
        this.pipelines = List.of(pipelines);
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

    public List<RenderPipeline> pipelines() {
        return pipelines;
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

    /** True for a host pipeline of this lane drawing on the lane's host format. */
    public boolean serves(RenderPipeline pipeline) {
        return pipeline != null && pipelines.contains(pipeline) && pipeline.getVertexFormat() == format;
    }

    /** The lane that owns a host draw in a pass, or null when the draw keeps its host program. */
    public static HostLayoutLane forDraw(RenderPipeline pipeline, VertexFormat format, Pass pass) {
        for (HostLayoutLane lane : values()) {
            if (lane.pass == pass && lane.format == format && lane.pipelines.contains(pipeline)) {
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
