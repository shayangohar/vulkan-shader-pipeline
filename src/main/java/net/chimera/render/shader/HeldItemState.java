package net.chimera.render.shader;

import net.chimera.shaderpack.PackItemIdResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Set;

/**
 * Iris's held-item uniforms (IdMapUniforms.HeldItemSupplier), captured once per frame: each
 * hand's item.properties id, the light its item emits and that light's colour.
 *
 * <p>The id comes from the stack's item_model component, falling back to the item's registry
 * name, and is -1 when the pack lists neither. The light is a block item's default block state
 * emission and 0 for any other item; the colour is white, as Iris's default
 * IrisItemLightProvider gives every vanilla item. With oldHandLight on, the main hand reports the
 * off hand's light when that is brighter. Without a player every value is Iris's invalid one.</p>
 */
final class HeldItemState {
    static final Set<String> NAMES = Set.of(
            "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2",
            "heldBlockLightColor", "heldBlockLightColor2");

    private PackItemIdResolver items = PackItemIdResolver.empty();
    private boolean oldHandLight = true;
    private int mainId = PackItemIdResolver.UNMAPPED;
    private int offId = PackItemIdResolver.UNMAPPED;
    private int mainLight;
    private int offLight;

    void install(PackItemIdResolver items, boolean oldHandLight) {
        this.items = items == null ? PackItemIdResolver.empty() : items;
        this.oldHandLight = oldHandLight;
    }

    void capture(Minecraft minecraft) {
        Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            clear();
            return;
        }
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        this.mainId = items.resolve(itemName(main));
        this.offId = items.resolve(itemName(off));
        this.offLight = lightEmission(off);
        this.mainLight = oldHandLight ? Math.max(lightEmission(main), this.offLight) : lightEmission(main);
    }

    void clear() {
        this.mainId = this.offId = PackItemIdResolver.UNMAPPED;
        this.mainLight = this.offLight = 0;
    }

    /** The scalar, or one component of the colour, Iris serves under a name. */
    double value(String name, int component) {
        return switch (name) {
            case "heldItemId" -> this.mainId;
            case "heldItemId2" -> this.offId;
            case "heldBlockLightValue" -> this.mainLight;
            case "heldBlockLightValue2" -> this.offLight;
            case "heldBlockLightColor", "heldBlockLightColor2" -> component < 3 ? 1.0 : Double.NaN;
            default -> Double.NaN;
        };
    }

    /** Writes a name in its declared type: integer types as ints, the colour as floats. */
    void write(String name, String type, MappedBuffer target) {
        boolean colour = name.startsWith("heldBlockLightColor");
        int count = colour ? 3 : 1;
        boolean integral = type.startsWith("i") || type.equals("bool");
        for (int component = 0; component < count; component++) {
            double value = value(name, colour ? component : -1);
            if (integral) {
                target.putInt(component * 4, (int) value);
            } else {
                target.putFloat(component * 4, (float) value);
            }
        }
    }

    /** IrisItemLightProvider's default: a block item emits its block's default light. */
    static int lightEmission(ItemStack stack) {
        return stack != null && stack.getItem() instanceof BlockItem item
                ? item.getBlock().defaultBlockState().getLightEmission() : 0;
    }

    private static String itemName(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        return (model != null ? model : BuiltInRegistries.ITEM.getKey(stack.getItem())).toString();
    }
}
