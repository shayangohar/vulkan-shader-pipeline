package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chimera destroys a Vulkan handle it owns only once no frame can use it (KNOW-509, HandleRetirement). */
final class HandleRetirementTest {
    private final List<Runnable> queued = new ArrayList<>();
    private Consumer<Runnable> frameOps;

    @BeforeEach
    void captureFrameOps() {
        frameOps = HandleRetirement.frameOps;
        HandleRetirement.frameOps = queued::add;
        HandleRetirement.recording();
    }

    @AfterEach
    void restoreFrameOps() {
        HandleRetirement.frameOps = frameOps;
        HandleRetirement.recording();
    }

    @Test
    void aHandleWaitsForItsFramesUnlessTheDeviceIsIdle() {
        List<String> destroyed = new ArrayList<>();
        HandleRetirement.retire(() -> destroyed.add("mid-frame"));
        assertEquals(List.of(), destroyed, "mid-frame a handle waits for the frame slot's fence");
        assertEquals(1, queued.size());

        HandleRetirement.idleUntilRecording();
        HandleRetirement.retire(() -> destroyed.add("idle"));
        assertEquals(List.of("idle"), destroyed, "after an idle wait it goes at once, beside nothing new");

        HandleRetirement.recording();
        HandleRetirement.retire(() -> destroyed.add("next frame"));
        assertEquals(List.of("idle"), destroyed, "the next frame's recording closes the window");
        assertEquals(2, queued.size());
    }

    /** The window opens after the idle wait and closes before VulkanMod begins the command buffer, whatever throws. */
    @Test
    void theIdleWindowEndsBeforeTheFrameRecords() throws IOException {
        List<String> idle = calls(MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle"),
                "waitForImmediateDestruction");
        int waitIdle = idle.indexOf("Vulkan.waitIdle");
        assertTrue(waitIdle >= 0 && waitIdle < idle.indexOf("HandleRetirement.idleUntilRecording"), idle.toString());

        ClassNode renderer = MixinBytecode.nativeClass("net/chimera/render/ChimeraRenderer");
        MethodNode hook = method(renderer, "beforeMainCommandBuffer");
        assertTrue(calls(renderer, "beforeMainCommandBuffer").contains("HandleRetirement.recording"));
        boolean finallyCloses = false;
        for (TryCatchBlockNode block : hook.tryCatchBlocks) {
            if (block.type != null) continue;
            for (AbstractInsnNode next = block.handler; next != null; next = next.getNext()) {
                if (next instanceof MethodInsnNode call && call.name.equals("recording")) finallyCloses = true;
            }
        }
        assertTrue(finallyCloses, "a pack change that throws still closes the window");
    }

    /** One rule: no class but HandleRetirement queues a VulkanMod frame op, and every raw owner retires through it. */
    @Test
    void everyOwnedHandleRetiresThroughOneRule() throws IOException, URISyntaxException {
        Path classes = Path.of(HandleRetirement.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> queuers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes.resolve("net/chimera"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                ClassNode owner = read(file);
                for (MethodNode method : owner.methods) {
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call && call.name.equals("addFrameOp")
                                && call.owner.equals("net/vulkanmod/vulkan/memory/MemoryManager")) {
                            queuers.add(owner.name);
                        }
                    }
                }
            }
        }
        assertEquals(List.of("net/chimera/render/HandleRetirement"), queuers.stream().distinct().toList());
        for (String[] retires : List.of(new String[] {"PackStorageImage", "free"},
                new String[] {"PackRawTexture", "free"}, new String[] {"ComputeKernel", "close"},
                new String[] {"DepthSampleView", "destroy"}, new String[] {"TargetFormatViews", "free"})) {
            ClassNode owner = MixinBytecode.nativeClass("net/chimera/render/" + retires[0]);
            assertTrue(calls(owner, retires[1]).contains("HandleRetirement.retire"), retires[0] + "." + retires[1]);
        }
    }

    private static ClassNode read(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + name + " is missing"));
    }

    private static List<String> calls(ClassNode owner, String name) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method(owner, name).instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
