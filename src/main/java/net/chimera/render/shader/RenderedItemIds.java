package net.chimera.render.shader;

import net.chimera.shaderpack.PackItemIdResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;

/**
 * Iris CapturedRenderingState's current rendered item. Chimera writes it into EntityIds.z (Iris
 * iris_Entity.z), which entity-layout programs read as currentRenderedItemId. An item layer makes
 * its item current while it submits (Iris ItemStackStateLayerMixin), and an item node makes the
 * ids it captured current again while it draws (Iris MixinItemFeatureRenderer).
 */
public final class RenderedItemIds {
    /** The blockEntityId Iris sets while a block item submits. */
    public static final int BLOCK_ITEM_ENTITY_ID = 1;

    private static PackItemIdResolver items = PackItemIdResolver.empty();
    private static final int[] previousItems = new int[8];
    private static final int[] previousBlockEntities = new int[8];
    private static int depth;
    private static int current;
    /** The item {@code ItemModelResolver.appendItemLayers} is resolving, for the layers it creates. */
    private static int resolvingItem;
    private static boolean resolvingBlockItem;

    private RenderedItemIds() {}

    /** Installs the session's item.properties ids and clears the current item. */
    public static void install(PackItemIdResolver next) {
        items = next == null ? PackItemIdResolver.empty() : next;
        depth = 0;
        current = 0;
        resolvingItem = 0;
        resolvingBlockItem = false;
    }

    /**
     * The id Iris gives an item stack: a block item (not a solid bucket) takes its default
     * state's block.properties id, 0 when unmapped; any other item its item.properties id, by
     * its item_model component first.
     */
    public static int idOf(ItemStack stack) {
        Item item = stack == null ? null : stack.getItem();
        if (item == null) return 0;
        if (isBlockItem(item)) {
            int id = ChimeraTerrainPipelines.blockStateId(((BlockItem) item).getBlock().defaultBlockState());
            return id == -1 ? 0 : id;
        }
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        return items.renderedItemId((model != null ? model : BuiltInRegistries.ITEM.getKey(item)).toString());
    }

    public static boolean isBlockItem(ItemStack stack) {
        return stack != null && isBlockItem(stack.getItem());
    }

    private static boolean isBlockItem(Item item) {
        return item instanceof BlockItem && !(item instanceof SolidBucketItem);
    }

    /** Marks the item whose layers are being resolved; returns the encoding of the one it replaces. */
    public static long beginResolving(int itemId, boolean blockItem) {
        long previous = encode(resolvingItem, resolvingBlockItem);
        resolvingItem = itemId;
        resolvingBlockItem = blockItem;
        return previous;
    }

    public static void endResolving(long previous) {
        resolvingItem = (int) previous;
        resolvingBlockItem = (previous >>> 32) != 0;
    }

    public static int resolvingItem() {
        return resolvingItem;
    }

    public static boolean resolvingBlockItem() {
        return resolvingBlockItem;
    }

    /** Makes an item current while its layer submits; a block item also sets blockEntityId to 1. */
    public static void beginLayer(int itemId, boolean blockItem) {
        push(itemId, blockItem ? BLOCK_ITEM_ENTITY_ID : ChimeraEntityBridge.currentBlockEntityId());
    }

    /** Makes the item and block entity an item node captured current while it draws. */
    public static void push(int itemId, int blockEntityId) {
        if (depth < previousItems.length) {
            previousItems[depth] = current;
            previousBlockEntities[depth] = ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        } else {
            ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        }
        depth++;
        current = itemId;
    }

    /** Restores the item and block entity that {@link #push} or {@link #beginLayer} replaced. */
    public static void pop() {
        if (depth <= 0) return;
        depth--;
        boolean saved = depth < previousItems.length;
        current = saved ? previousItems[depth] : 0;
        ChimeraEntityBridge.replaceBlockEntityId(saved ? previousBlockEntities[depth] : 0);
    }

    /** The item EntityIds.z carries for the vertex being written. */
    public static int current() {
        return current;
    }

    private static long encode(int itemId, boolean blockItem) {
        return (itemId & 0xFFFFFFFFL) | (blockItem ? 1L << 32 : 0L);
    }
}
