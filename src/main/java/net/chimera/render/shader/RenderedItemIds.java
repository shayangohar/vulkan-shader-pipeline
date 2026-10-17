package net.chimera.render.shader;

import net.chimera.shaderpack.PackItemIdResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;

/**
 * Iris CapturedRenderingState's current rendered item. Chimera writes it into EntityIds.z (Iris
 * iris_Entity.z), which entity-layout programs read as currentRenderedItemId. An item is current
 * while ItemRenderer.render draws it (Iris MixinItemRenderer).
 */
public final class RenderedItemIds {
    /** The blockEntityId Iris sets while a block item renders. */
    public static final int BLOCK_ITEM_ENTITY_ID = 1;

    private static PackItemIdResolver items = PackItemIdResolver.empty();
    private static final int[] previousItems = new int[8];
    private static final int[] previousBlockEntities = new int[8];
    private static int depth;
    private static int current;

    private RenderedItemIds() {}

    /** Installs the session's item.properties ids and clears the current item. */
    public static void install(PackItemIdResolver next) {
        items = next == null ? PackItemIdResolver.empty() : next;
        depth = 0;
        current = 0;
    }

    /**
     * The id Iris gives an item stack: a block item (not a solid bucket) takes its default
     * state's block.properties id, 0 when unmapped; any other item its item.properties id.
     */
    public static int idOf(ItemStack stack) {
        Item item = stack == null ? null : stack.getItem();
        if (item == null) return 0;
        if (isBlockItem(item)) {
            int id = ChimeraTerrainPipelines.blockStateId(((BlockItem) item).getBlock().defaultBlockState());
            return id == -1 ? 0 : id;
        }
        return items.renderedItemId(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    private static boolean isBlockItem(Item item) {
        return item instanceof BlockItem && !(item instanceof SolidBucketItem);
    }

    /** Makes an item current while it renders; a block item also sets blockEntityId to 1. */
    public static void beginItem(ItemStack stack) {
        push(idOf(stack), stack != null && isBlockItem(stack.getItem())
                ? BLOCK_ITEM_ENTITY_ID : ChimeraEntityBridge.currentBlockEntityId());
    }

    private static void push(int itemId, int blockEntityId) {
        if (depth < previousItems.length) {
            previousItems[depth] = current;
            previousBlockEntities[depth] = ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        } else {
            ChimeraEntityBridge.replaceBlockEntityId(blockEntityId);
        }
        depth++;
        current = itemId;
    }

    /** Restores the item and block entity that {@link #beginItem} replaced. */
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
}
