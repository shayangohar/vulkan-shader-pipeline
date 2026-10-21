package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The advanced-resource plan's snapshot, as JSON: images, computes, bindings and buffers. */
final class AdvancedResourceJson {
    private AdvancedResourceJson() {}

    static JsonArray images(Map<String, PackAdvancedResourcePlan.ImageSpec> images) {
        JsonArray imageArray = new JsonArray();
        images.values().stream().sorted(Comparator.comparing(PackAdvancedResourcePlan.ImageSpec::name))
                .forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("sampler", value.sampler());
            item.addProperty("pixelFormat", value.pixelFormat());
            item.addProperty("internalFormat", value.internalFormat());
            item.addProperty("pixelType", value.pixelType());
            item.addProperty("clear", value.clear());
            item.addProperty("relative", value.relative());
            item.addProperty("width", value.width());
            item.addProperty("height", value.height());
            item.addProperty("depth", value.depth());
            item.addProperty("widthToken", value.widthToken());
            item.addProperty("heightToken", value.heightToken());
            item.addProperty("depthToken", value.depthToken());
            item.addProperty("estimatedBytes", value.estimatedBytes());
            item.addProperty("status", value.status().name());
            item.add("deviations", PlanJson.strings(value.deviations()));
            imageArray.add(item);
        });
        return imageArray;
    }

    static JsonArray computeStages(Map<String, PackAdvancedResourcePlan.ComputeSpec> computeStages) {
        JsonArray computeArray = new JsonArray();
        computeStages.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("program", value.program());
            item.addProperty("source", value.relativeSource());
            item.addProperty("localSizeX", value.localSizeX());
            item.addProperty("localSizeY", value.localSizeY());
            item.addProperty("localSizeZ", value.localSizeZ());
            item.add("images", PlanJson.strings(value.imageNames()));
            item.add("samplers", PlanJson.strings(value.samplerNames()));
            item.addProperty("status", value.status().name());
            item.add("deviations", PlanJson.strings(value.deviations()));
            computeArray.add(item);
        });
        return computeArray;
    }

    /** One entry per program, in name order, with its bindings as the writer renders each. */
    static <T> JsonArray programBindings(Map<String, List<T>> bindings, Function<T, JsonObject> writer) {
        JsonArray programArray = new JsonArray();
        bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("program", entry.getKey());
            JsonArray entries = new JsonArray();
            entry.getValue().forEach(binding -> entries.add(writer.apply(binding)));
            item.add("bindings", entries);
            programArray.add(item);
        });
        return programArray;
    }

    static JsonObject graphicsBinding(PackAdvancedResourcePlan.GraphicsImageBinding binding) {
        JsonObject value = new JsonObject();
        value.addProperty("symbol", binding.symbol());
        value.addProperty("image", binding.imageName());
        value.addProperty("sampler", binding.sampler());
        value.addProperty("type", binding.glslType());
        if (!binding.viewFormat().isEmpty()) value.addProperty("viewFormat", binding.viewFormat());
        value.addProperty("selectorSlot", binding.selectorSlot());
        return value;
    }

    static JsonArray buffers(Map<Integer, PackAdvancedResourcePlan.BufferSpec> buffers) {
        JsonArray bufferArray = new JsonArray();
        buffers.values().stream()
                .sorted(Comparator.comparingInt(PackAdvancedResourcePlan.BufferSpec::logicalIndex))
                .forEach(value -> {
            JsonObject item = new JsonObject();
            item.addProperty("index", value.logicalIndex());
            item.addProperty("size", value.size());
            item.addProperty("name", value.requestedName());
            item.addProperty("status", value.status().name());
            item.add("deviations", PlanJson.strings(value.deviations()));
            bufferArray.add(item);
        });
        return bufferArray;
    }

    static JsonObject storageBinding(PackAdvancedResourcePlan.StorageBufferBinding binding) {
        JsonObject value = new JsonObject();
        value.addProperty("block", binding.blockName());
        value.addProperty("instance", binding.instanceName());
        value.addProperty("originalBinding", binding.originalBinding());
        value.addProperty("index", binding.logicalIndex());
        value.addProperty("binding", binding.rewrittenBinding());
        value.addProperty("stages", binding.stageMask());
        value.addProperty("size", binding.size());
        value.addProperty("status", binding.status().name());
        value.add("deviations", PlanJson.strings(binding.deviations()));
        return value;
    }
}
