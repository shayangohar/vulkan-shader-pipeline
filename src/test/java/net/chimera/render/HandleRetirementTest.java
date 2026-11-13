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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chimera destroys a Vulkan handle it owns only once no work can use it (KNOW-509, HandleRetirement). */
final class HandleRetirementTest {
    private final List<Runnable> queued = new ArrayList<>();
    private Consumer<Runnable> frameOps;

    @BeforeEach
    void captureFrameOps() {
        frameOps = HandleRetirement.frameOps;
        HandleRetirement.frameOps = queued::add;
    }

    @AfterEach
    void restoreFrameOps() {
        HandleRetirement.frameOps = frameOps;
    }

    @Test
    void aHandleWaitsForItsFramesUnlessItsTeardownFollowsAnIdleWait() {
        List<String> destroyed = new ArrayList<>();
        HandleRetirement.retire(() -> destroyed.add("mid-frame"));
        assertEquals(List.of(), destroyed, "mid-frame a handle waits for the frame slot's fence");
        assertEquals(1, queued.size());

        HandleRetirement.whileIdle(() -> HandleRetirement.retire(() -> destroyed.add("idle")));
        assertEquals(List.of("idle"), destroyed, "in an idle teardown it goes at once, beside nothing new");

        HandleRetirement.retire(() -> destroyed.add("after"));
        assertEquals(List.of("idle"), destroyed, "the window is the teardown alone");
        assertEquals(2, queued.size());

        assertThrows(IllegalStateException.class, () -> HandleRetirement.whileIdle(() -> {
            throw new IllegalStateException("teardown failed");
        }));
        HandleRetirement.retire(() -> destroyed.add("after a failed teardown"));
        assertEquals(3, queued.size(), "a teardown that throws still closes the window");
    }

    /**
     * Only the idle wait opens the window, right after Vulkan.waitIdle: a rollback, a dimension restore
     * or renderVanilla, which follow a load that may have submitted uploads, never run inside it.
     */
    @Test
    void onlyTheIdleWaitOpensTheWindow() throws IOException, URISyntaxException {
        List<String> idle = calls(MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle"),
                "waitForImmediateDestruction");
        int waitIdle = idle.indexOf("Vulkan.waitIdle");
        assertTrue(waitIdle >= 0 && waitIdle < idle.indexOf("HandleRetirement.whileIdle"), idle.toString());
        assertEquals(List.of("net/chimera/render/PackSessionLifecycle.waitForImmediateDestruction"),
                callers("whileIdle"));
    }

    /** One rule: no class but HandleRetirement queues a VulkanMod frame op, and every raw owner retires through it. */
    @Test
    void everyOwnedHandleRetiresThroughOneRule() throws IOException, URISyntaxException {
        Path classes = Path.of(HandleRetirement.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> queuers = new ArrayList<>();
        for (ClassNode owner : classes(classes)) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.name.equals("addFrameOp")
                            && call.owner.equals("net/vulkanmod/vulkan/memory/MemoryManager")) {
                        queuers.add(owner.name);
                    }
                }
            }
        }
        assertEquals(List.of("net/chimera/render/HandleRetirement"), queuers.stream().distinct().toList());
        for (String[] retires : List.of(new String[] {"PackStorageImage", "free"},
                new String[] {"PackRawTexture", "free"}, new String[] {"ComputeKernel", "close"},
                new String[] {"DepthSampleView", "destroy"}, new String[] {"TargetFormatViews", "free"})) {
            List<String> calls = calls(MixinBytecode.nativeClass("net/chimera/render/" + retires[0]), retires[1]);
            String name = retires[0] + "." + retires[1];
            assertTrue(calls.contains("HandleRetirement.retire"), name);
            assertFalse(calls.stream().anyMatch(call -> call.contains(".vkDestroy") || call.contains(".vmaDestroy")),
                    name + " destroys nothing itself: " + calls);
        }
    }

    private static List<String> callers(String retirementMethod) throws IOException, URISyntaxException {
        Path classes = Path.of(HandleRetirement.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> callers = new ArrayList<>();
        for (ClassNode owner : classes(classes)) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.name.equals(retirementMethod)
                            && call.owner.equals("net/chimera/render/HandleRetirement")) {
                        callers.add(owner.name + "." + method.name);
                    }
                }
            }
        }
        return callers;
    }

    private static List<ClassNode> classes(Path root) throws IOException {
        List<ClassNode> nodes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("net/chimera"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(input).accept(node, 0);
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    private static List<String> calls(ClassNode owner, String name) {
        MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + name + " is missing"));
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
