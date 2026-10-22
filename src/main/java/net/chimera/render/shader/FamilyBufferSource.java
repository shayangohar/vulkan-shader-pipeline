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
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Predicate;

/**
 * A buffer source that batches the pack family lanes apart from the host's. A render type that a
 * family lane draws where it is written ({@link ChimeraEntityBridge#familyFor}) goes to its own
 * {@link FamilyRenderType} buffer here; every other type goes to the host source unchanged.
 *
 * <p>Family batches stay buffered until {@link #endFamilyBatches} (or a full {@link #endBatch})
 * draws them: opaque lanes first, each run of lanes with one set of pack outputs inside one pack
 * window. Vanilla's shared buffer would instead draw every entity's batch as soon as the next
 * render type starts, opening a pack window per switch.</p>
 *
 * <p>Each family type keeps its own buffer. Vanilla memoizes most entity render types, but it
 * builds energy swirl and breeze wind anew each frame, as some mods do with theirs; past
 * {@link #MAX_FAMILY_TYPES}, the types no flush has drawn for {@link #IDLE_FLUSHES} flushes are
 * freed with their buffers.</p>
 */
public final class FamilyBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
    private static final List<FamilyBufferSource> LIVE = new ArrayList<>();
    private static final int MAX_FAMILY_TYPES = 256;
    /** About half a second: the world source flushes two or three times a frame. */
    private static final long IDLE_FLUSHES = 64;
    /** The active pack's separateEntityDraws (ParticleDirectives), false with no pack. */
    private static boolean separateEntityDraws;

    private final MultiBufferSource.BufferSource host;
    private final SequencedMap<RenderType, ByteBufferBuilder> familyBuffers;
    private final Map<TypeKey, FamilyRenderType> familyTypes = new HashMap<>();
    private long flushes;

    /** A family type's identity: its lane, the phase it draws in, and the host type it wraps. */
    private record TypeKey(Family family, PackRenderingPhase phase, RenderType host) {}

    public FamilyBufferSource(MultiBufferSource.BufferSource host) {
        this(host, new LinkedHashMap<>());
    }

    private FamilyBufferSource(MultiBufferSource.BufferSource host,
                               SequencedMap<RenderType, ByteBufferBuilder> familyBuffers) {
        // Every family type has a fixed buffer, so the shared one is never written.
        super(new ByteBufferBuilder(64), familyBuffers);
        this.host = host;
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
        endFamilyBatches();
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

    /** Whether the active pack draws its other entity batches after its deferred passes. */
    public static void setSeparateEntityDraws(boolean separate) {
        separateEntityDraws = separate;
    }

    /**
     * The flush after the world's entities or block entities: every started family batch, or with
     * the pack's separateEntityDraws only the opaque, opaque-decal and water-mask ones, as Iris draws
     * those before the deferred passes. The rest wait for {@link #endHeldWorldBatches}.
     */
    public void endWorldFamilyBatches() {
        endFamilyBatches(separateEntityDraws ? FamilyRenderType::drawsBeforeDeferred : type -> true);
    }

    /**
     * With separateEntityDraws, the world's held entity batches, after the deferred passes and before
     * translucent terrain, as Iris draws them right after the translucent boundary; on the main target.
     */
    public static void endHeldWorldBatches(ChimeraMainPass pass) {
        if (!separateEntityDraws || pass == null
                || !(Minecraft.getInstance().renderBuffers().bufferSource() instanceof FamilyBufferSource source)) {
            return;
        }
        pass.rebindMainTarget();
        source.endFamilyBatches();
    }

    /** Draws every started family batch: opaque lanes first, one pack window per run of equal outputs. */
    public void endFamilyBatches() {
        endFamilyBatches(type -> true);
    }

    private void endFamilyBatches(Predicate<FamilyRenderType> drawn) {
        if (startedBuilders.isEmpty()) return;
        List<FamilyRenderType> started = new ArrayList<>(startedBuilders.size());
        for (RenderType type : familyBuffers.keySet()) {
            if (startedBuilders.containsKey(type) && drawn.test((FamilyRenderType) type)) {
                started.add((FamilyRenderType) type);
            }
        }
        if (started.isEmpty()) return;
        started.sort(Comparator.comparing(RenderType::sortOnUpload));
        flushes++;
        started.forEach(type -> type.drawnAt(flushes));
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            started.forEach(super::endBatch);
        } else {
            pass.drawFamilyBatches(started, super::endBatch, this::discard);
        }
        if (familyBuffers.size() > MAX_FAMILY_TYPES) freeIdleTypes();
    }

    /** Frees every family type, and its buffer, that no flush has drawn for IDLE_FLUSHES flushes. */
    private void freeIdleTypes() {
        familyBuffers.entrySet().removeIf(entry -> {
            FamilyRenderType type = (FamilyRenderType) entry.getKey();
            if (flushes - type.lastDrawn() <= IDLE_FLUSHES) return false;
            familyTypes.remove(new TypeKey(type.family(), type.phase(), type.host()));
            entry.getValue().close();
            return true;
        });
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
