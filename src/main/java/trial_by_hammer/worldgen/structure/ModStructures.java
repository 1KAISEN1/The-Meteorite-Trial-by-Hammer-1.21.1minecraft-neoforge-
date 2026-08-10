package trial_by_hammer.worldgen.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.TrialByHammer;

public final class ModStructures {
    private static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, TrialByHammer.MODID);
    private static final DeferredRegister<StructurePieceType> STRUCTURE_PIECE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, TrialByHammer.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<MeteoriteCraterStructure>> METEORITE_CRATER =
            STRUCTURE_TYPES.register("meteorite_crater", () -> () -> MeteoriteCraterStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType.ContextlessType> METEORITE_CRATER_PIECE =
            STRUCTURE_PIECE_TYPES.register("meteorite_crater", () -> MeteoriteCraterPiece::new);

    private ModStructures() {
    }

    public static void register(IEventBus modEventBus) {
        STRUCTURE_TYPES.register(modEventBus);
        STRUCTURE_PIECE_TYPES.register(modEventBus);
    }
}
