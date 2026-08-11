package trial_by_hammer.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.TrialByHammer;

/** 注册锻造界面菜单类型。 / Registers forging-screen menu types. */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, TrialByHammer.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ForgingMenu>> FORGING = MENUS.register(
            "forging",
            () -> IMenuTypeExtension.create(ForgingMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
