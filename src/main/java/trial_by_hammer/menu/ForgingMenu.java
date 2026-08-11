package trial_by_hammer.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import trial_by_hammer.content.ModWorkshopBlocks;

/**
 * 服务器持有的锻造界面会话；第一版只验证访问距离和铁砧位置。
 * Server-owned forging session; the first version validates access distance and anvil position only.
 */
public final class ForgingMenu extends AbstractContainerMenu {
    private final BlockPos anvilPos;
    private final ContainerLevelAccess access;

    public ForgingMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public ForgingMenu(int containerId, Inventory inventory, BlockPos anvilPos) {
        super(ModMenus.FORGING.get(), containerId);
        this.anvilPos = anvilPos.immutable();
        this.access = ContainerLevelAccess.create(inventory.player.level(), anvilPos);
    }

    public BlockPos getAnvilPos() {
        return this.anvilPos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ModWorkshopBlocks.SMITHING_ANVIL.get());
    }
}
