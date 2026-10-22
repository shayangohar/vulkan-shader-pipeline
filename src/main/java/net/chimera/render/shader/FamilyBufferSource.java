package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
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
 * <p>The level's entity and crumbling sources stand in for Iris's batched entity source, which
 * draws everything at the translucent boundary ({@link #endLevelBatchesBeforeDeferred}), the block
 * outline and crumbling included. With the pack's separateEntityDraws they draw only the opaque,
 * opaque-decal and water-mask kinds until then, and the rest right after the deferred passes
 * ({@link #endLevelBatchesAfterDeferred}), as Iris's batchedentityrendering MixinLevelRenderer does.</p>
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
    /** Whether this frame's level has run its deferred passes, after which a level source holds nothing. */
    private static boolean pastDeferred;

    private final MultiBufferSource.BufferSource host;
    /** One of the level's entity and crumbling sources, which hold batches for the deferred passes. */
    private final boolean level;
    private final SequencedMap<RenderType, ByteBufferBuilder> familyBuffers;
    private final Map<TypeKey, FamilyRenderType> familyTypes = new HashMap<>();
    private long flushes;

    /** A family type's identity: its lane, the phase it draws in, and the host type it wraps. */
    private record TypeKey(Family family, PackRenderingPhase phase, RenderType host) {}

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
        Family family = ChimeraEntityBridge.familyFor(type);
        if (family == null) return host.getBuffer(type);
        PackRenderingPhase phase = ChimeraEntityBridge.writePhase(family);
        FamilyRenderType familyType = familyTypes.computeIfAbsent(new TypeKey(family, phase, type),
                key -> new FamilyRenderType(family, phase, type));
        // A chunk type asks for 4 MB up front; a family batch starts small and grows as it fills.
        familyBuffers.computeIfAbsent(familyType, started -> new ByteBufferBuilder(
                Math.min(started.bufferSize(), RenderType.TRANSIENT_BUFFER_SIZE)));
        return super.getBuffer(familyType);
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
            super.endBatch(type);
        } else {
            host.endBatch(type);
        }
    }

    /** The flush after the world's entities or block entities: the batches due by now. */
    public void endWorldFamilyBatches() {
        endFamilyBatches(due());
    }

    /** Draws every started family batch. */
    public void endFamilyBatches() {
        endFamilyBatches(type -> true);
    }

    /** At the head of each level render: the level has not reached its deferred passes. */
    public static void beginLevel() {
        pastDeferred = false;
    }

    /**
     * At the translucent terrain layer, before its depth capture and deferred passes: the level's
     * batches due there, where Iris draws its batched entities. The shadow pass draws its own layers.
     */
    public static void endLevelBatchesBeforeDeferred(ChimeraMainPass pass) {
        if (pass == null || ChimeraRenderer.shadowPassActive()) return;
        endLevelBatches(pass, levelDue());
    }

    /** Right after the deferred passes, before translucent terrain: every batch the level held for them. */
    public static void endLevelBatchesAfterDeferred(ChimeraMainPass pass) {
        if (pass == null || ChimeraRenderer.shadowPassActive()) return;
        pastDeferred = true;
        endLevelBatches(pass, type -> true);
    }

    /** The batches this source draws now: a level source keeps the late kinds for after deferred. */
    private Predicate<FamilyRenderType> due() {
        return level ? levelDue() : type -> true;
    }

    private static Predicate<FamilyRenderType> levelDue() {
        return !pastDeferred && ChimeraRenderer.packSeparatesEntityDraws()
                ? FamilyRenderType::drawsBeforeDeferred : type -> true;
    }

    /**
     * Draws both level sources' started batches as one, in Iris's order, on the main target: Iris
     * buffers crumbling with the entities, so its decals land between their translucent kinds and
     * the outline.
     */
    private static void endLevelBatches(ChimeraMainPass pass, Predicate<FamilyRenderType> drawn) {
        RenderBuffers buffers = Minecraft.getInstance().renderBuffers();
        Map<RenderType, FamilyBufferSource> owners = new HashMap<>();
        List<FamilyRenderType> started = new ArrayList<>();
        for (MultiBufferSource.BufferSource candidate : List.of(buffers.bufferSource(),
                buffers.crumblingBufferSource())) {
            if (!(candidate instanceof FamilyBufferSource source)) continue;
            List<FamilyRenderType> own = source.startedTypes(drawn);
            source.noteFlush(own);
            own.forEach(type -> owners.put(type, source));
            started.addAll(own);
        }
        if (started.isEmpty()) return;
        pass.rebindMainTarget();
        draw(started, type -> owners.get(type).drawStarted(type), type -> owners.get(type).discard(type));
        new LinkedHashSet<>(owners.values()).forEach(FamilyBufferSource::trim);
    }

    private void endFamilyBatches(Predicate<FamilyRenderType> drawn) {
        List<FamilyRenderType> started = startedTypes(drawn);
        if (started.isEmpty()) return;
        noteFlush(started);
        draw(started, this::drawStarted, this::discard);
        trim();
    }

    /** This source's started family batches that {@code drawn} takes, in first-use order. */
    private List<FamilyRenderType> startedTypes(Predicate<FamilyRenderType> drawn) {
        List<FamilyRenderType> started = new ArrayList<>();
        if (startedBuilders.isEmpty()) return started;
        for (RenderType type : familyBuffers.keySet()) {
            if (startedBuilders.containsKey(type) && drawn.test((FamilyRenderType) type)) {
                started.add((FamilyRenderType) type);
            }
        }
        return started;
    }

    /** Counts a flush that draws {@code drawn}. */
    private void noteFlush(List<FamilyRenderType> drawn) {
        if (drawn.isEmpty()) return;
        flushes++;
        drawn.forEach(type -> type.drawnAt(flushes));
    }

    /**
     * Draws started batches in Iris's order (TransparencyType, each kind in first-use order), one
     * pack window per run of equal outputs.
     */
    private static void draw(List<FamilyRenderType> started, Consumer<RenderType> draw,
                             Consumer<RenderType> discard) {
        started.sort(Comparator.comparing(FamilyRenderType::transparency));
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            started.forEach(draw);
        } else {
            pass.drawFamilyBatches(started, draw, discard);
        }
    }

    private void drawStarted(RenderType type) {
        super.endBatch(type);
    }

    private void trim() {
        if (familyBuffers.size() > MAX_FAMILY_TYPES) freeIdleTypes();
    }

    /** Frees every idle family type with its buffer. */
    private void freeIdleTypes() {
        familyBuffers.entrySet().removeIf(entry -> {
            FamilyRenderType type = (FamilyRenderType) entry.getKey();
            if (!idle(startedBuilders.containsKey(type), flushes, type.lastDrawn())) return false;
            familyTypes.remove(new TypeKey(type.family(), type.phase(), type.host()));
            entry.getValue().close();
            return true;
        });
    }

    /**
     * Whether a family type may be freed: it holds no started batch, which a level source may keep
     * for its deferred passes, and no flush has drawn it for IDLE_FLUSHES flushes.
     */
    static boolean idle(boolean started, long flushes, long lastDrawn) {
        return !started && flushes - lastDrawn > IDLE_FLUSHES;
    }

    /** Drops a started batch unseen: its lane's pack outputs could not open this frame. */
    private void discard(RenderType type) {
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
