package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

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
 * {@link #MAX_FAMILY_TYPES}, the types a batch did not draw are freed with their buffers.</p>
 */
public final class FamilyBufferSource extends MultiBufferSource.BufferSource implements AutoCloseable {
    private static final List<FamilyBufferSource> LIVE = new ArrayList<>();
    private static final int MAX_FAMILY_TYPES = 256;

    private final MultiBufferSource.BufferSource host;
    private final SequencedMap<RenderType, ByteBufferBuilder> familyBuffers;
    private final Map<Family, Map<RenderType, FamilyRenderType>> familyTypes = new EnumMap<>(Family.class);

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
        FamilyRenderType familyType = familyTypes.computeIfAbsent(family, ignored -> new HashMap<>())
                .computeIfAbsent(type, ignored -> new FamilyRenderType(family, type));
        familyBuffers.computeIfAbsent(familyType, started -> new ByteBufferBuilder(started.bufferSize()));
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

    /** Draws every started family batch: opaque lanes first, one pack window per run of equal outputs. */
    public void endFamilyBatches() {
        if (startedBuilders.isEmpty()) return;
        List<FamilyRenderType> started = new ArrayList<>(startedBuilders.size());
        for (RenderType type : familyBuffers.keySet()) {
            if (startedBuilders.containsKey(type)) started.add((FamilyRenderType) type);
        }
        started.sort(Comparator.comparing(RenderType::sortOnUpload));
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) {
            started.forEach(super::endBatch);
        } else {
            pass.drawFamilyBatches(started, super::endBatch, this::discard);
        }
        if (familyBuffers.size() > MAX_FAMILY_TYPES) freeIdleTypes(started);
    }

    /** Frees every family type, and its buffer, that this batch did not draw. */
    private void freeIdleTypes(List<FamilyRenderType> drawn) {
        Set<RenderType> kept = new HashSet<>(drawn);
        familyBuffers.entrySet().removeIf(entry -> {
            if (kept.contains(entry.getKey())) return false;
            FamilyRenderType type = (FamilyRenderType) entry.getKey();
            familyTypes.get(type.family()).remove(type.host());
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
