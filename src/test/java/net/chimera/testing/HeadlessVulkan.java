package net.chimera.testing;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests that create a headless Vulkan device run only when the build asks for them. */
public final class HeadlessVulkan {
    /** The MemoryStack VulkanMod Reforged's VkInstanceFactory gives its instance thread. */
    private static final int INSTANCE_STACK_BYTES = 4 * 1024 * 1024;

    private HeadlessVulkan() {}

    /** Skips the calling test unless {@code -Pchimera.gpu=true}. */
    public static void assumeEnabled() {
        assumeTrue(Boolean.getBoolean("chimera.gpu"), "headless Vulkan checks run with -Pchimera.gpu=true");
    }

    /**
     * Wraps the created instance {@code handle} as the game does: on a thread with a 4 MB
     * MemoryStack. VkInstance lists every device's extensions on the stack, and two GPUs outgrow
     * LWJGL's 64 KB default.
     */
    public static VkInstance instance(long handle, VkInstanceCreateInfo create) {
        CompletableFuture<VkInstance> instance = new CompletableFuture<>();
        new Thread(() -> {
            try {
                memoryStacks().set(MemoryStack.create(INSTANCE_STACK_BYTES));
                instance.complete(new VkInstance(handle, create));
            } catch (Throwable failure) {
                instance.completeExceptionally(failure);
            }
        }, "headless-vulkan-instance").start();
        return instance.join();
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<MemoryStack> memoryStacks() throws ReflectiveOperationException {
        Field stacks = MemoryStack.class.getDeclaredField("TLS");
        stacks.setAccessible(true);
        return (ThreadLocal<MemoryStack>) stacks.get(null);
    }
}
