package net.chimera.render;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.GraphicsQueue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Records a run of texture uploads into one graphics-queue command buffer, as VulkanMod's texture
 * tick records its sprite uploads (MTextureManager.tick). Outside such a recording,
 * VulkanImage.uploadSubTextureAsync submits a command buffer with a fence per region. At every
 * fiftieth fence, Synchronization.addFence makes the render thread wait for the GPU to drain the
 * frame in flight. Binding each written image then submits its layout transition alone
 * (VulkanImage.readOnlyLayout). An open batch begins recording at its first upload. Closing it
 * makes the written images shader-readable and submits once, with no wait. Render thread only.
 */
final class TextureUploadBatch<I> {
    /** What a batch drives: VulkanMod's graphics queue in game, a fake in tests. */
    interface Recorder<I> {
        void begin();

        /** Makes {@code written} shader-readable in the recording, then submits it. */
        void submit(List<I> written);
    }

    /** The graphics queue's shared recording (GraphicsQueue.startRecording / endRecordingAndSubmit). */
    static final Recorder<VulkanImage> GRAPHICS_QUEUE = new Recorder<>() {
        @Override
        public void begin() {
            DeviceManager.getGraphicsQueue().startRecording();
        }

        @Override
        public void submit(List<VulkanImage> written) {
            GraphicsQueue queue = DeviceManager.getGraphicsQueue();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer commands = queue.getCommandBuffer().getHandle();
                for (VulkanImage image : written) image.readOnlyLayout(stack, commands);
            } finally {
                queue.endRecordingAndSubmit();
            }
        }
    };

    private final Recorder<I> recorder;
    private final Set<I> written = new LinkedHashSet<>();
    private boolean open;
    private boolean recording;

    TextureUploadBatch(Recorder<I> recorder) {
        this.recorder = recorder;
    }

    void open() {
        open = true;
    }

    /** Called before each upload to {@code image}: an open batch's first one begins the recording. */
    void beforeUpload(I image) {
        if (!open) return;
        if (!recording) {
            recorder.begin();
            recording = true;
        }
        written.add(image);
    }

    /** Ends the batch, submitting its recording if an upload began one. */
    void close() {
        open = false;
        if (!recording) return;
        recording = false;
        try {
            recorder.submit(List.copyOf(written));
        } finally {
            written.clear();
        }
    }
}
