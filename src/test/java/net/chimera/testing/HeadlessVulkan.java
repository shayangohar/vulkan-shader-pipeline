package net.chimera.testing;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.vulkan.VK10.*;

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

    /**
     * A headless device on the first GPU, with one queue that does what {@code queueFlags} asks, one
     * resettable command buffer and a fence. It never starts Minecraft or touches VulkanMod's device.
     */
    public static final class Device implements AutoCloseable {
        private VkInstance instance;
        private VkPhysicalDevice physical;
        private VkDevice device;
        private VkQueue queue;
        private VkCommandBuffer command;
        private long pool;
        private long fence;

        /** {@code queueFlags}: VK_QUEUE_COMPUTE_BIT, or VK_QUEUE_GRAPHICS_BIT where a blit needs it. */
        public Device(String name, int queueFlags) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default()
                        .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                                .pApplicationName(stack.UTF8(name)).apiVersion(VK_MAKE_VERSION(1, 2, 0)));
                ok(vkCreateInstance(create, null, pointer));
                instance = instance(pointer.get(0), create);
                int family = choosePhysical(stack, queueFlags);
                VkDeviceQueueCreateInfo.Buffer queueCreate = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                VkDeviceCreateInfo deviceCreate = VkDeviceCreateInfo.calloc(stack).sType$Default()
                        .pQueueCreateInfos(queueCreate);
                ok(vkCreateDevice(physical, deviceCreate, null, pointer));
                device = new VkDevice(pointer.get(0), physical, deviceCreate);
                vkGetDeviceQueue(device, family, 0, pointer);
                queue = new VkQueue(pointer.get(0), device);
                LongBuffer handle = stack.mallocLong(1);
                ok(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                        .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null,
                        handle));
                pool = handle.get(0);
                ok(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer));
                command = new VkCommandBuffer(pointer.get(0), device);
                ok(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle));
                fence = handle.get(0);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        /** Takes the first GPU, and returns its first queue family that does what {@code queueFlags} asks. */
        private int choosePhysical(MemoryStack stack, int queueFlags) {
            IntBuffer count = stack.mallocInt(1);
            ok(vkEnumeratePhysicalDevices(instance, count, null));
            assertTrue(count.get(0) > 0, "no Vulkan device");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            ok(vkEnumeratePhysicalDevices(instance, count, devices));
            physical = new VkPhysicalDevice(devices.get(0), instance);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, queues);
            for (int family = 0; family < queues.remaining(); family++) {
                if ((queues.get(family).queueFlags() & queueFlags) == queueFlags) return family;
            }
            throw new AssertionError("no queue with flags " + queueFlags);
        }

        public VkPhysicalDevice physical() {
            return physical;
        }

        public VkDevice device() {
            return device;
        }

        public VkCommandBuffer command() {
            return command;
        }

        /** Memory of the first type {@code requirements} allows that has every one of {@code properties}. */
        public long allocate(MemoryStack stack, VkMemoryRequirements requirements, int properties) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
            for (int type = 0; type < memory.memoryTypeCount(); type++) {
                if ((requirements.memoryTypeBits() & (1 << type)) != 0
                        && (memory.memoryTypes(type).propertyFlags() & properties) == properties) {
                    LongBuffer handle = stack.mallocLong(1);
                    ok(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                            .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle));
                    return handle.get(0);
                }
            }
            throw new AssertionError("no memory type with properties " + properties);
        }

        /** Ends the command buffer, submits it, and waits for the GPU to finish it. */
        public void submitAndWait(MemoryStack stack) {
            ok(vkEndCommandBuffer(command));
            ok(vkResetFences(device, fence));
            ok(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(command.address())), fence));
            ok(vkWaitForFences(device, fence, true, 10_000_000_000L));
        }

        /** Waits for the device to idle, then destroys the fence, pool, device and instance. */
        @Override
        public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (fence != 0) vkDestroyFence(device, fence, null);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        }
    }

    /** Fails the test on any Vulkan result but VK_SUCCESS. */
    public static void ok(int result) {
        assertTrue(result == VK_SUCCESS, "Vulkan result " + result);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<MemoryStack> memoryStacks() throws ReflectiveOperationException {
        Field stacks = MemoryStack.class.getDeclaredField("TLS");
        stacks.setAccessible(true);
        return (ThreadLocal<MemoryStack>) stacks.get(null);
    }
}
