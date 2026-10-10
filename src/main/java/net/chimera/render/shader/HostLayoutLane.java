package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.List;

/**
 * The Iris IrisPipelines main-pass mappings Chimera serves on the host's own vertex layout.
 * Each lane owns its draws at RenderType.draw (ChimeraRenderTypeMixin): the host mesh, draw
 * call and raster state stay, and only the program and its authored targets change. This
 * table answers which lane a draw belongs to, which host pipelines and format the lane
 * accepts, which pack program serves it, and the Iris rendering phase it draws in.
 *
 * <p>Depth-only pipelines Iris also maps (DRAGON_RAYS_DEPTH to LIGHTNING, WATER_MASK to
 * BASIC) write no colour, so their host draw is equivalent and they are not lanes.</p>
 */
public enum HostLayoutLane {
    /** Iris WEATHER: rain and snow. */
    WEATHER(ChimeraEntityBridge.Family.WEATHER, "gbuffers_weather", DefaultVertexFormat.PARTICLE,
            PackRenderingPhase.RAIN_SNOW, false,
            RenderPipelines.WEATHER_DEPTH_WRITE, RenderPipelines.WEATHER_NO_DEPTH_WRITE),
    /** Iris LINES: block outlines, fishing lines and other line draws. */
    LINE(ChimeraEntityBridge.Family.LINE, "gbuffers_line", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH,
            PackRenderingPhase.OUTLINE, false,
            RenderPipelines.LINES, RenderPipelines.LINES_TRANSLUCENT, RenderPipelines.SECONDARY_BLOCK_OUTLINE),
    /** Iris GLINT in the world; Iris draws world glint inside the entity phase. */
    GLINT(ChimeraEntityBridge.Family.GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.ENTITIES, false, RenderPipelines.GLINT),
    /** First-person glint: the same program under the hand projection. */
    HAND_GLINT(ChimeraEntityBridge.Family.HAND_GLINT, "gbuffers_armor_glint", DefaultVertexFormat.POSITION_TEX,
            PackRenderingPhase.HAND_SOLID, true, RenderPipelines.GLINT),
    /** Iris LIGHTNING: lightning bolts and dragon death rays. */
    LIGHTNING(ChimeraEntityBridge.Family.LIGHTNING, "gbuffers_lightning", DefaultVertexFormat.POSITION_COLOR,
            PackRenderingPhase.ENTITIES, false, RenderPipelines.LIGHTNING, RenderPipelines.DRAGON_RAYS),
    /** Iris BEACON: beacon and end gateway beams. */
    BEACON_BEAM(ChimeraEntityBridge.Family.BEACON_BEAM, "gbuffers_beaconbeam", DefaultVertexFormat.BLOCK,
            PackRenderingPhase.BLOCK_ENTITIES, false,
            RenderPipelines.BEACON_BEAM_OPAQUE, RenderPipelines.BEACON_BEAM_TRANSLUCENT),
    /** Iris LEASH: leads, drawn with gbuffers_basic. */
    LEASH(ChimeraEntityBridge.Family.LEASH, "gbuffers_basic", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            PackRenderingPhase.ENTITIES, false, RenderPipelines.LEASH);

    private final ChimeraEntityBridge.Family family;
    private final String program;
    private final VertexFormat format;
    private final PackRenderingPhase phase;
    private final boolean hand;
    private final List<RenderPipeline> pipelines;

    HostLayoutLane(ChimeraEntityBridge.Family family, String program, VertexFormat format,
                   PackRenderingPhase phase, boolean hand, RenderPipeline... pipelines) {
        this.family = family;
        this.program = program;
        this.format = format;
        this.phase = phase;
        this.hand = hand;
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

    /** A first-person variant: drawn under the hand projection, only while the hand renders. */
    public boolean hand() {
        return hand;
    }

    /** The Iris rendering phase (renderStage) of this lane's draws. */
    public PackRenderingPhase phase() {
        if (hand) {
            return ChimeraHandRenderer.translucentPhase()
                    ? PackRenderingPhase.HAND_TRANSLUCENT : PackRenderingPhase.HAND_SOLID;
        }
        return phase;
    }

    /** Iris ExtendedShader binds a white texture to unit 0 for a format without texture coordinates. */
    public boolean whiteAlbedo() {
        return !format.contains(VertexFormatElement.UV0);
    }

    /** True for a host pipeline of this lane drawing on the lane's host format. */
    public boolean serves(RenderPipeline pipeline) {
        return pipeline != null && pipelines.contains(pipeline) && pipeline.getVertexFormat() == format;
    }

    /** The lane that owns a host draw, or null when the draw keeps its host program. */
    public static HostLayoutLane forDraw(RenderPipeline pipeline, VertexFormat format, boolean handActive) {
        for (HostLayoutLane lane : values()) {
            if (lane.hand == handActive && lane.format == format && lane.pipelines.contains(pipeline)) {
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
        return java.util.Arrays.stream(values()).filter(lane -> lane.program.equals(program)).toList();
    }
}
