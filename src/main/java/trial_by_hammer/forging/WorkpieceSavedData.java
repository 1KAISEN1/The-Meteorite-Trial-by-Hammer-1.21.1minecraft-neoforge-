package trial_by_hammer.forging;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece.DeformationParameters;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece.HammerResult;
import trial_by_hammer.forging.material.ForgingMaterialProperties;

/**
 * 世界级 UUID 工件库；完整几何不随物品栏反复同步。
 * World-level UUID workpiece repository; full geometry is not repeatedly synchronized with inventories.
 */
public final class WorkpieceSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "trial_by_hammer_workpieces";
    private static final String ENTRIES_KEY = "entries";
    public static final int DEFAULT_TEST_TEMPERATURE_CELSIUS = 950;
    public static final int MIN_TEST_TEMPERATURE_CELSIUS = 20;
    public static final int MAX_TEST_TEMPERATURE_CELSIUS = 1400;
    private static final Factory<WorkpieceSavedData> FACTORY = new Factory<>(
            WorkpieceSavedData::new, WorkpieceSavedData::load);

    private final Map<UUID, Entry> entries = new HashMap<>();

    public static WorkpieceSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Entry createIronBillet(UUID id) {
        Entry entry = new Entry(LayeredSlabWorkpiece.createIronBillet(), 0, DEFAULT_TEST_TEMPERATURE_CELSIUS);
        this.entries.put(id, entry);
        this.setDirty();
        return entry;
    }

    public Entry getOrCreateIronBillet(UUID id) {
        return this.entries.computeIfAbsent(id, ignored -> {
            this.setDirty();
            return new Entry(LayeredSlabWorkpiece.createIronBillet(), 0, DEFAULT_TEST_TEMPERATURE_CELSIUS);
        });
    }

    public Optional<Entry> find(UUID id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    public HammerResult hammer(
            UUID id,
            boolean fromTop,
            int centerX,
            int centerZ,
            int force) {
        Entry entry = this.getOrCreateIronBillet(id);
        ForgingMaterialProperties material = ForgingMaterialProperties.TEST_IRON;
        HammerResult result = entry.geometry.hammerFace(
                fromTop,
                centerX,
                centerZ,
                force,
                new DeformationParameters(
                        material.plasticityAt(entry.testTemperatureCelsius),
                        material.ductility(),
                        material.interfaceFriction()));
        if (result.changed()) {
            entry.revision++;
            this.setDirty();
        }
        return result;
    }

    public int measure(UUID id, int x, int z) {
        return this.getOrCreateIronBillet(id).geometry.thicknessUnitsAt(x, z);
    }

    public void setTestTemperature(UUID id, int temperatureCelsius) {
        Entry entry = this.getOrCreateIronBillet(id);
        int clamped = Math.max(
                MIN_TEST_TEMPERATURE_CELSIUS,
                Math.min(MAX_TEST_TEMPERATURE_CELSIUS, temperatureCelsius));
        if (entry.testTemperatureCelsius != clamped) {
            entry.testTemperatureCelsius = clamped;
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entryTags = new ListTag();
        this.entries.forEach((id, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("id", id);
            entryTag.putInt("revision", entry.revision);
            entryTag.putInt("test_temperature_c", entry.testTemperatureCelsius);
            entryTag.putByteArray("geometry", entry.geometry.encode());
            entryTags.add(entryTag);
        });
        tag.put(ENTRIES_KEY, entryTags);
        return tag;
    }

    private static WorkpieceSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorkpieceSavedData data = new WorkpieceSavedData();
        ListTag entryTags = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
        for (Tag rawEntry : entryTags) {
            CompoundTag entryTag = (CompoundTag)rawEntry;
            try {
                UUID id = entryTag.getUUID("id");
                int revision = Math.max(0, entryTag.getInt("revision"));
                int testTemperature = entryTag.contains("test_temperature_c", Tag.TAG_INT)
                        ? entryTag.getInt("test_temperature_c")
                        : DEFAULT_TEST_TEMPERATURE_CELSIUS;
                LayeredSlabWorkpiece geometry = LayeredSlabWorkpiece.decode(entryTag.getByteArray("geometry"));
                data.entries.put(id, new Entry(
                        geometry,
                        revision,
                        Math.max(MIN_TEST_TEMPERATURE_CELSIUS, Math.min(MAX_TEST_TEMPERATURE_CELSIUS, testTemperature))));
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Skipped invalid Trial by Hammer workpiece entry", exception);
            }
        }
        return data;
    }

    public static final class Entry {
        private final LayeredSlabWorkpiece geometry;
        private int revision;
        private int testTemperatureCelsius;

        private Entry(LayeredSlabWorkpiece geometry, int revision, int testTemperatureCelsius) {
            this.geometry = geometry;
            this.revision = revision;
            this.testTemperatureCelsius = testTemperatureCelsius;
        }

        public LayeredSlabWorkpiece geometry() {
            return this.geometry;
        }

        public int revision() {
            return this.revision;
        }

        public int testTemperatureCelsius() {
            return this.testTemperatureCelsius;
        }
    }
}
