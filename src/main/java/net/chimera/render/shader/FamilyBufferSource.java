package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A buffer source that batches the pack family lanes apart from the host's. A render type that a
 * family lane draws where it is written ({@link ChimeraEntityBridge#familyFor}) goes to its own
 * {@link FamilyRenderType} buffer here; every other type goes to the host source unchanged.
 *
 * <p>Family batches stay buffered until {@link #endFamilyBatches} (or a full {@link #endBatch})
 * draws them, in Iris's batch order (by {@link IrisTransparency}, opaque kinds first), each run of
 * lanes with one set of pack outputs inside one pack window. Vanilla's shared buffer would instead
 * draw every entity's batch as soon as the next render type starts, opening a pack window per
 * switch.</p>
 *
 * <p>The level's entity and crumbling sources stand in for Iris's batched entity source: they hold
 * every family batch, vanilla's own flushes included, until Iris's translucent boundary, before the
 * hand ({@link #endLevelBatchesAtBoundary}). They hold a batch that a world host-layout lane draws
 * too (a lead, a name tag's background, the block outline), which its lane still takes when it
 * draws, in the phase of the Iris wrapper it was written in ({@link HeldLaneRenderType}). With the
 * pack's separateEntityDraws only the opaque, opaque-decal and water-mask kinds
 * draw there, and the rest right after the deferred passes ({@link #endHeldLevelBatches}), as
 * Iris's batchedentityrendering MixinLevelRenderer does. They draw in Iris's order
 * ({@link IrisBatchOrder}): a lead writes only gbuffers_basic's targets, so drawn ahead of the chest
 * or sign in front of it, the chest's outputs would leave it showing through. Types no lane takes
 * flush where vanilla flushes them.</p>
 *
 * <p>Each family type keeps its own buffer. Vanilla memoizes most entity render types, but it
 * builds energy swirl and breeze wind anew each frame, as some mods do with theirs; past
 * {@link #MAX_FAMILY_TYPES}, the types that hold no batch and that no flush has drawn for
 * {@link #IDLE_FLUSHES} flushes are freed with their buffers.</p>
 */
public final class FamilyBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
    private static final List<FamilyBufferSource> LIVE = new ArrayList<>();
    private static final int MAX_FAMILY_TYPES = 256;
    /** About half a second: the world source flushes two or three times a frame. */
    private static final long IDLE_FLUSHES = 64;
    /** Whether this frame's level has reached Iris's translucent boundary, before which level sources hold. */
    private static boolean pastBoundary;
    /** Whether this frame's level has run its deferred passes, after which a level source holds nothing. */
    private static boolean pastDeferred;
    /** The order of the batches the level sources hold this frame. */
    private static final IrisBatchOrder<RenderType> LEVEL_ORDER = new IrisBatchOrder<>();

    private final MultiBufferSource.BufferSource host;
    /** One of the level's entity and crumbling sources, which hold batches for the deferred passes. */
    private final boolean level;
    private final SequencedMap<RenderType, ByteBufferBuilder> familyBuffers;
    private final Map<TypeKey, FamilyRenderType> familyTypes = new HashMap<>();
    /** The types a level source holds world host-lane batches under, one per host type and phase. */
    private final Map<LaneKey, HeldLaneRenderType> laneTypes = new HashMap<>();
    /** Each held type's earlier batches of connected primitives, closed and waiting to draw. */
    private final Map<RenderType, List<MeshData>> closedBatches = new HashMap<>();
    private long flushes;

    /** A family type's identity: its lane, the phase it draws in, and the host type it wraps. */
    private record TypeKey(Family family, PackRenderingPhase phase, RenderType host) {}

    /** A held host-lane type's identity: the host type and the phase its batch draws in. */
    private record LaneKey(RenderType host, PackRenderingPhase phase) {}

    /** A source for one renderer's own batches, such as the hand's or the shadow casters'. */
    public FamilyBufferSource(MultiBufferSource.BufferSource host) {
        this(host, false);
    }

    /** A source; {@code level} for the level's entity and crumbling sources. */
    public FamilyBufferSource(MultiBufferSource.BufferSource host, boolean level) {
        this(host, level, new LinkedHashMap<>());
    }

    private FamilyBufferSource(MultiBufferSource.BufferSource host, boolean level,
                               SequencedMap<RenderType, ByteBufferBuilder> familyBuffers) {
        // Every family type has a fixed buffer, so the shared one is never written.
        super(new ByteBufferBuilder(64), familyBuffers);
        this.host = host;
        this.level = level;
        this.familyBuffers = familyBuffers;
        LIVE.add(this);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        RenderType held = heldType(type);
        if (held == null) return host.getBuffer(type);
        if (level) LEVEL_ORDER.use(held, kind(held));
        // A chunk type asks for 4 MB up front; a held batch starts small and grows as it fills.
        familyBuffers.computeIfAbsent(held, started -> new ByteBufferBuilder(
                Math.min(started.bufferSize(), RenderType.TRANSIENT_BUFFER_SIZE)));
        closeConnectedBatch(held);
        return super.getBuffer(held);
    }

    /**
     * Keeps a started batch of connected primitives (a lead's strip) closed and held, where vanilla
     * would draw it before the next batch of its type starts: one strip cannot take another's
     * vertices. Iris's segmented buffers keep each strip the same way.
     */
    private void closeConnectedBatch(RenderType type) {
        if (type.canConsolidateConsecutiveGeometry()) return;
        BufferBuilder started = startedBuilders.remove(type);
        MeshData mesh = started == null ? null : started.build();
        if (mesh != null) closedBatches.computeIfAbsent(type, unused -> new ArrayList<>()).add(mesh);
    }

    /**
     * The type this source holds a batch of {@code type} under: its family lane's, or in a level
     * source, when a world host-layout lane draws it, the type in the phase it is written in; null
     * for the host to draw.
     */
    private RenderType heldType(RenderType type) {
        Family family = ChimeraEntityBridge.familyFor(type);
        if (family == null) {
            if (!level || !ChimeraEntityBridge.worldLaneDraws(type)) return null;
            PackRenderingPhase phase = ChimeraEntityBridge.hostLaneWritePhase();
            return laneTypes.computeIfAbsent(new LaneKey(type, phase), key -> new HeldLaneRenderType(type, phase));
        }
        PackRenderingPhase phase = ChimeraEntityBridge.writePhase(family);
        return familyTypes.computeIfAbsent(new TypeKey(family, phase, type),
                key -> new FamilyRenderType(family, phase, type));
    }

    /** A held batch's kind, as Iris batches it. */
    private static IrisTransparency kind(RenderType type) {
        if (type instanceof FamilyRenderType family) return family.transparency();
        return type instanceof HeldLaneRenderType held ? held.transparency() : IrisTransparency.of(type);
    }

    @Override
    public void endLastBatch() {
        host.endLastBatch();
    }

    @Override
    public void endBatch() {
        endFamilyBatches(due());
        host.endBatch();
    }

    @Override
    public void endBatch(RenderType type) {
        if (type instanceof FamilyRenderType) {
            drawStarted(type);
        } else {
            host.endBatch(type);
        }
    }

    /** Draws every started family batch. */
    public void endFamilyBatches() {
        endFamilyBatches(type -> true);
    }

    /** At the head of each level render: the level has reached neither its boundary nor deferred. */
    public static void beginLevel() {
        pastBoundary = false;
        pastDeferred = false;
        LEVEL_ORDER.reset();
    }

    /**
     * Opens one of Iris's batch groups, around one entity's render or one banner's patterns, unless
     * one is open; true when this call opened it, and must close it with {@link #endLevelGroup}.
     */
    public static boolean startLevelGroup() {
        return LEVEL_ORDER.startGroup();
    }

    public static void endLevelGroup() {
        LEVEL_ORDER.endGroup();
    }

    /**
     * Iris's translucent boundary, before the hand and the deferred passes: the level's batches due
     * there, everything or with separateEntityDraws the opaque kinds, as batchedentityrendering's
     * MixinLevelRenderer draws them ahead of Iris's beginTranslucents. The shadow pass has its own.
     */
    public static void endLevelBatchesAtBoundary(ChimeraMainPass pass) {
        if (pass == null || ChimeraRenderer.shadowPassActive()) return;
        pastBoundary = true;
        endLevelBatches(pass, levelDue());
    }

    /**
     * Every batch the level still holds: right after the deferred passes, before translucent terrain,
     * and at the end of the level for any started later.
     */
    public static void endHeldLevelBatches(ChimeraMainPass pass) {
        if (ChimeraRenderer.shadowPassActive()) return;
        pastBoundary = true;
        pastDeferred = true;
        endLevelBatches(pass, type -> true);
    }

    /** The batches this source draws now: a level source holds them for Iris's boundary. */
    private Predicate<RenderType> due() {
        return level ? levelDue() : type -> true;
    }

    private static Predicate<RenderType> levelDue() {
        boolean separates = ChimeraRenderer.packSeparatesEntityDraws();
        return type -> levelDraws(kind(type), pastBoundary, pastDeferred, separates);
    }

    /**
     * Whether a level source draws a batch of this kind now. Iris buffers the level's batches whole
     * (FullyBufferedMultiBufferSource ignores vanilla's endBatch(type)) until its translucent
     * boundary; with separateEntityDraws only the opaque kinds draw there, the rest after deferred.
     */
    static boolean levelDraws(IrisTransparency kind, boolean pastBoundary, boolean pastDeferred,
                              boolean separates) {
        if (!pastBoundary) return false;
        return pastDeferred || !separates || kind.drawsBeforeDeferred();
    }

    /**
     * The level's entity and crumbling sources. Found here, not through RenderBuffers, which hands
     * them out only while the bridge routes the world's buffers (audit LD1).
     */
    static List<FamilyBufferSource> levelSources() {
        return LIVE.stream().filter(source -> source.level).toList();
    }

    /**
     * Drops every batch the level sources hold, unseen: the family bridge was switched off, by a
     * pack falling back mid-frame or at a frame boundary, so no lane draws them any more, and
     * RenderBuffers no longer hands the sources out for anything to flush them (audit LD1).
     */
    public static void discardLevelBatches() {
        for (FamilyBufferSource source : levelSources()) {
            List.copyOf(source.familyBuffers.keySet()).forEach(source::discard);
        }
    }

    /**
     * Draws both level sources' started batches as one, in Iris's order, on the main target: Iris
     * buffers crumbling with the entities, so its decals land between their translucent kinds.
     */
    private static void endLevelBatches(ChimeraMainPass pass, Predicate<RenderType> drawn) {
        Map<RenderType, FamilyBufferSource> owners = new HashMap<>();
        List<RenderType> started = new ArrayList<>();
        for (FamilyBufferSource source : levelSources()) {
            List<RenderType> own = source.startedTypes(drawn);
            source.noteFlush(own);
            own.forEach(type -> owners.put(type, source));
            started.addAll(own);
        }
        if (started.isEmpty()) return;
        if (pass != null) pass.rebindMainTarget();
        draw(started, levelOrder(), type -> owners.get(type).drawStarted(type),
                type -> owners.get(type).discard(type));
        new LinkedHashSet<>(owners.values()).forEach(FamilyBufferSource::trim);
    }

    private void endFamilyBatches(Predicate<RenderType> drawn) {
        List<RenderType> started = startedTypes(drawn);
        if (started.isEmpty()) return;
        noteFlush(started);
        draw(started, level ? levelOrder() : Comparator.comparing(FamilyBufferSource::kind), this::drawStarted,
                this::discard);
        trim();
    }

    /**
     * Iris's order for the level's batches ({@link IrisBatchOrder}). A source of a renderer's own
     * batches keeps, within a kind, the order its types were first buffered.
     */
    private static Comparator<RenderType> levelOrder() {
        Map<RenderType, Integer> ranks = LEVEL_ORDER.ranks();
        return Comparator.comparing(FamilyBufferSource::kind)
                .thenComparingInt(type -> ranks.getOrDefault(type, Integer.MAX_VALUE));
    }

    /** This source's started batches that {@code drawn} takes, in first-use order. */
    private List<RenderType> startedTypes(Predicate<RenderType> drawn) {
        List<RenderType> started = new ArrayList<>();
        if (startedBuilders.isEmpty() && closedBatches.isEmpty()) return started;
        for (RenderType type : familyBuffers.keySet()) {
            if (holds(type) && drawn.test(type)) started.add(type);
        }
        return started;
    }

    /** Whether this source holds a batch of {@code type}, started or closed. */
    private boolean holds(RenderType type) {
        return startedBuilders.containsKey(type) || closedBatches.containsKey(type);
    }

    /** Counts a flush that draws {@code drawn}. */
    private void noteFlush(List<RenderType> drawn) {
        if (drawn.isEmpty()) return;
        flushes++;
        for (RenderType type : drawn) {
            if (type instanceof FamilyRenderType family) family.drawnAt(flushes);
            if (type instanceof HeldLaneRenderType held) held.drawnAt(flushes);
        }
    }

    /** Draws started batches in {@code order}, one pack window per run of family batches with equal outputs. */
    private static void draw(List<RenderType> started, Comparator<RenderType> order, Consumer<RenderType> draw,
                             Consumer<RenderType> discard) {
        started.sort(order);
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            started.forEach(draw);
        } else {
            pass.drawFamilyBatches(started, draw, discard);
        }
    }

    /** Draws the type's closed batches in the order they were written, then its started one. */
    private void drawStarted(RenderType type) {
        List<MeshData> closed = closedBatches.remove(type);
        if (closed != null) closed.forEach(type::draw);
        super.endBatch(type);
    }

    private void trim() {
        if (familyBuffers.size() > MAX_FAMILY_TYPES) freeIdleTypes();
    }

    /**
     * Frees every idle held type with its buffer, a family lane's or a host-layout lane's. Vanilla
     * memoizes its render types, but a mod may build a lines or leash type anew each frame, and
     * each such type held for a host-layout lane kept its buffer until the lanes changed.
     */
    private void freeIdleTypes() {
        familyBuffers.entrySet().removeIf(entry -> {
            RenderType type = entry.getKey();
            long lastDrawn;
            if (type instanceof FamilyRenderType family) {
                lastDrawn = family.lastDrawn();
            } else if (type instanceof HeldLaneRenderType held) {
                lastDrawn = held.lastDrawn();
            } else {
                return false;
            }
            if (!idle(holds(type), flushes, lastDrawn)) return false;
            familyTypes.values().remove(type);
            laneTypes.values().remove(type);
            entry.getValue().close();
            return true;
        });
    }

    /**
     * Whether a held type may be freed: it holds no batch, started or closed ({@code held}), which a
     * level source may keep for its deferred passes, and no flush has drawn it for IDLE_FLUSHES flushes.
     */
    static boolean idle(boolean held, long flushes, long lastDrawn) {
        return !held && flushes - lastDrawn > IDLE_FLUSHES;
    }

    /** Drops a type's held batches unseen: its lane's pack outputs could not open this frame. */
    private void discard(RenderType type) {
        List<MeshData> closed = closedBatches.remove(type);
        if (closed != null) closed.forEach(MeshData::close);
        BufferBuilder builder = startedBuilders.remove(type);
        MeshData mesh = builder == null ? null : builder.build();
        if (mesh != null) mesh.close();
    }

    /** Frees the family buffers and forgets their types, before the installed lanes change. */
    private void release() {
        familyBuffers.keySet().forEach(this::discard);
        familyBuffers.values().forEach(ByteBufferBuilder::close);
        familyBuffers.clear();
        familyTypes.clear();
        laneTypes.clear();
    }

    /** Frees every live source's family buffers, before the installed lanes change. */
    static void releaseAll() {
        LIVE.forEach(FamilyBufferSource::release);
    }

    @Override
    public void close() {
        release();
        sharedBuffer.close();
        LIVE.remove(this);
    }
}
