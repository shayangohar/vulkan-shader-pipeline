package net.chimera.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;

import java.util.ArrayList;
import java.util.List;

/**
 * A material map's own animation table, read from its {@code .mcmeta} with
 * Minecraft's {@link AnimationMetadataSection} codec and frame-size rule.
 *
 * <p>A map keeps its own frame order, durations and interpolation flag,
 * independent of the albedo beside it. The whole table is validated up
 * front: a frame size that does not divide the image, an out-of-range
 * frame index, or a non-positive duration rejects the map, so that one
 * sprite and kind stays flat instead of animating a wrong region.</p>
 */
record MaterialAnimationSchedule(int frameWidth, int frameHeight, int columns,
        List<MaterialAnimation.Frame> frames, boolean interpolate) {

    /**
     * Parses {@code mcmeta} for an image of the given size. Returns null when
     * the file has no {@code animation} section; throws
     * {@link IllegalArgumentException} for an invalid table.
     */
    static MaterialAnimationSchedule parse(String mcmeta, int imageWidth, int imageHeight) {
        JsonElement root = JsonParser.parseString(mcmeta);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:mcmeta is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        if (!object.has("animation")) return null;
        AnimationMetadataSection section = AnimationMetadataSection.CODEC
                .parse(JsonOps.INSTANCE, object.get("animation"))
                .getOrThrow(message -> new IllegalArgumentException("MATERIAL_MAP_ANIMATION:" + message));
        FrameSize size = section.calculateFrameSize(imageWidth, imageHeight);
        if (size.width() <= 0 || size.height() <= 0
                || imageWidth % size.width() != 0 || imageHeight % size.height() != 0) {
            throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame " + size.width() + "x"
                    + size.height() + " does not divide " + imageWidth + "x" + imageHeight);
        }
        int columns = imageWidth / size.width();
        int count = columns * (imageHeight / size.height());
        List<MaterialAnimation.Frame> frames = new ArrayList<>();
        if (section.frames().isPresent()) {
            for (AnimationFrame frame : section.frames().get()) {
                int time = frame.timeOr(section.defaultFrameTime());
                if (frame.index() < 0 || frame.index() >= count) {
                    throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame index "
                            + frame.index() + " outside " + count + " frames");
                }
                if (time <= 0) {
                    throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame duration " + time);
                }
                frames.add(new MaterialAnimation.Frame(frame.index(), time));
            }
        } else {
            if (section.defaultFrameTime() <= 0) {
                throw new IllegalArgumentException("MATERIAL_MAP_ANIMATION:frame duration "
                        + section.defaultFrameTime());
            }
            for (int index = 0; index < count; index++) {
                frames.add(new MaterialAnimation.Frame(index, section.defaultFrameTime()));
            }
        }
        return new MaterialAnimationSchedule(size.width(), size.height(), columns,
                List.copyOf(frames), section.interpolatedFrames());
    }

    /** True when the table cycles; a single entry is a static frame, as for the game's sprites. */
    boolean animated() {
        return frames.size() > 1;
    }

    /** Copies one frame of the strip out of the decoded image. */
    int[] crop(int[] image, int imageWidth, int frameIndex) {
        int originX = (frameIndex % columns) * frameWidth;
        int originY = (frameIndex / columns) * frameHeight;
        int[] frame = new int[frameWidth * frameHeight];
        for (int y = 0; y < frameHeight; y++) {
            System.arraycopy(image, (originY + y) * imageWidth + originX, frame, y * frameWidth, frameWidth);
        }
        return frame;
    }

    /** Frames in the strip, whether or not the schedule shows them all. */
    int frameCount(int imageHeight) {
        return columns * (imageHeight / frameHeight);
    }
}
