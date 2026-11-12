package net.chimera.render.shader;

import net.chimera.shaderpack.MixinBytecode;
import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GraphicsPipelineMrtMixin wraps createGraphicsPipeline: it opens the pipeline-creation scope
 * before the creation and closes it in a finally. A registered pipeline's creation swaps its own
 * contract in, so a creation that threw without closing its scope would hand that contract to the
 * pass's later variants.
 */
final class MrtPipelineContextTest {
    private static final String MIXIN = "net/chimera/mixin/GraphicsPipelineMrtMixin";
    private static final String CONTEXT = "net/chimera/render/shader/MrtPipelineContext";
    private static final String CREATE = "createGraphicsPipeline";

    /** The scope opens before the creation and closes on both of its exits, the throwing one included. */
    @Test
    void theMixinClosesTheCreationScopeInAFinally() throws IOException {
        ClassNode mixin = MixinBytecode.nativeClass(MIXIN);
        List<MethodNode> wrappers = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            String kind = injector(method);
            if (kind != null && kind.endsWith("/Inject;")) {
                assertTrue(!targets(method, CREATE), "no head or return inject on " + CREATE + " remains");
            }
            if (kind != null && kind.endsWith("/WrapMethod;") && targets(method, CREATE)) wrappers.add(method);
        }
        assertEquals(1, wrappers.size(), "one wrapper around " + CREATE);
        MethodNode wrapper = wrappers.get(0);
        List<AbstractInsnNode> code = List.of(wrapper.instructions.toArray());
        int begin = indexOfCall(code, CONTEXT, "beginPipeline");
        int creation = indexOfCall(code, "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call");
        assertTrue(begin >= 0 && begin < creation, "the scope opens before the creation");
        assertTrue(wrapper.tryCatchBlocks.stream().anyMatch(block -> closesBeforeRethrow(block, code, creation)),
                "a finally around the creation closes the scope before it rethrows");
        int normalClose = indexOfCall(code.subList(creation, code.size()), CONTEXT, "endPipeline");
        assertTrue(normalClose >= 0, "the creation's normal exit closes the scope");
    }

    /**
     * A registered pipeline's creation swaps its contract in and endPipeline puts the pass's back,
     * so the pass's later variants read the pass's blend modes.
     */
    @Test
    void endPipelineRestoresThePassContract() {
        try {
            MrtPipelineContext.begin(new int[] {37}, 8);
            MrtPipelineContext.beginPipeline(side(MrtPipelineContext.contract(new int[] {97}, null), 0b1,
                    new PackBlendPlan.Mode[] {PackBlendPlan.Mode.OFF}));
            assertArrayEquals(new int[] {97}, MrtPipelineContext.colorFormats(), "the pipeline's own contract");
            assertEquals(0b1, MrtPipelineContext.writtenOutputs(), "the pipeline's written outputs");
            assertArrayEquals(new PackBlendPlan.Mode[] {PackBlendPlan.Mode.OFF}, MrtPipelineContext.hostPassBlends());
            MrtPipelineContext.endPipeline();
            assertArrayEquals(new int[] {37}, MrtPipelineContext.colorFormats(), "the pass's contract again");
            assertEquals(SpirvOutputWrites.ALL, MrtPipelineContext.writtenOutputs(), "no creation is open");
            assertNull(MrtPipelineContext.hostPassBlends());
        } finally {
            MrtPipelineContext.end();
        }
    }

    /** A creation without a registered contract renders into the pass's, with every output written. */
    @Test
    void anUnregisteredPipelineTakesThePassContract() {
        try {
            MrtPipelineContext.begin(new int[] {37}, 8);
            MrtPipelineContext.beginPipeline(new PipelineSide());
            assertArrayEquals(new int[] {37}, MrtPipelineContext.colorFormats());
            assertEquals(SpirvOutputWrites.ALL, MrtPipelineContext.writtenOutputs());
            assertNull(MrtPipelineContext.hostPassBlends());
            MrtPipelineContext.endPipeline();
            assertArrayEquals(new int[] {37}, MrtPipelineContext.colorFormats());
        } finally {
            MrtPipelineContext.end();
        }
    }

    /** end() closes an open creation scope with the pass's contract, so nothing outlives the pass. */
    @Test
    void endClearsTheScopeWithTheContract() {
        try {
            MrtPipelineContext.begin(new int[] {97}, 8);
            MrtPipelineContext.beginPipeline(side(MrtPipelineContext.contract(new int[] {37}, null), 0b1,
                    new PackBlendPlan.Mode[1]));
            MrtPipelineContext.end();
            assertEquals(SpirvOutputWrites.ALL, MrtPipelineContext.writtenOutputs());
            assertNull(MrtPipelineContext.hostPassBlends());
            assertNull(MrtPipelineContext.colorFormats());
        } finally {
            MrtPipelineContext.end();
        }
    }

    /**
     * Whether {@code block} is a finally (or a catch of every Throwable) around the instruction at
     * {@code creation} whose handler closes the scope before it rethrows.
     */
    private static boolean closesBeforeRethrow(TryCatchBlockNode block, List<AbstractInsnNode> code, int creation) {
        if (block.type != null && !block.type.equals("java/lang/Throwable")) return false;
        if (code.indexOf(block.start) > creation || creation >= code.indexOf(block.end)) return false;
        int handler = code.indexOf(block.handler);
        int close = indexOfCall(code.subList(handler, code.size()), CONTEXT, "endPipeline");
        for (int index = handler; index < code.size(); index++) {
            if (code.get(index).getOpcode() == Opcodes.ATHROW) return close >= 0 && close < index - handler;
        }
        return false;
    }

    /** The injector annotation on {@code method}, or null. */
    private static String injector(MethodNode method) {
        for (List<AnnotationNode> list : List.of(
                method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
            for (AnnotationNode annotation : list) {
                if (annotation.desc.endsWith("/Inject;") || annotation.desc.endsWith("/WrapMethod;")) {
                    return annotation.desc;
                }
            }
        }
        return null;
    }

    /** Whether {@code method}'s injector names {@code target} in its method list. */
    private static boolean targets(MethodNode method, String target) {
        for (List<AnnotationNode> list : List.of(
                method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
            for (AnnotationNode annotation : list) {
                if (annotation.values == null) continue;
                for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                    if (annotation.values.get(index).equals("method")
                            && annotation.values.get(index + 1) instanceof List<?> names
                            && names.stream().anyMatch(name -> String.valueOf(name).startsWith(target))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int indexOfCall(List<AbstractInsnNode> code, String owner, String name) {
        for (int index = 0; index < code.size(); index++) {
            if (code.get(index) instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /** A pipeline side as a pack load leaves it: registered contract, written outputs, host-pass blends. */
    private static PipelineSide side(MrtPipelineContext.Contract contract, int writes, PackBlendPlan.Mode[] blends) {
        PipelineSide side = new PipelineSide();
        side.contract = contract;
        side.writtenOutputs = writes;
        side.hostPassBlends = blends;
        return side;
    }

    /**
     * The per-pipeline state is a field every GraphicsPipeline carries (ChimeraPipelineSideMixin),
     * not maps keyed by the pipeline, so it goes with the pipeline and a draw reads it without a lookup.
     */
    @Test
    void everyGraphicsPipelineCarriesItsSide() throws IOException {
        // MixinTargetAuditTest checks that it is registered and resolves against GraphicsPipeline.
        ClassNode mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraPipelineSideMixin");
        assertTrue(mixin.interfaces.contains("net/chimera/render/shader/PipelineSide$Holder"));
        for (String owner : List.of(CONTEXT, "net/chimera/render/shader/IrisDrawState",
                "net/chimera/render/shader/GeometryStageModules")) {
            ClassNode node = MixinBytecode.nativeClass(owner);
            assertTrue(node.fields.stream().noneMatch(field -> field.desc.equals("Ljava/util/Map;")),
                    owner + " keeps no map keyed by the pipeline");
        }
    }
}
