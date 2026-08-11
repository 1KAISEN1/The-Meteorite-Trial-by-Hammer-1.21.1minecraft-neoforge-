package trial_by_hammer.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import trial_by_hammer.TrialByHammer;
import trial_by_hammer.client.ForgingCameraState;
import trial_by_hammer.content.block.entity.SmithingAnvilBlockEntity;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;
import trial_by_hammer.menu.ForgingMenu;
import trial_by_hammer.network.ForgingActionPayload;
import trial_by_hammer.network.ForgingActionPayload.Action;

/**
 * 第一版锻造视图：真实世界俯视相机、透明界面、三种工具和锤击力度预览。
 * First forging view: real-world top-down camera, transparent UI, three tools, and hammer-force preview.
 */
public final class ForgingScreen extends AbstractContainerScreen<ForgingMenu> {
    private static final int TOOL_SIZE = 22;
    private static final int TOOL_PANEL_X = 12;
    private static final int TOOL_PANEL_BOTTOM = 14;
    private static final int FORCE_MIN = 1;
    private static final int FORCE_MAX = 10;
    private static final int PLANE_UNITS_PER_BLOCK = 4096;
    private int selectedInventorySlot = -1;
    private int force = 5;
    private int hammerPulseTicks;
    private boolean draggingWithTongs;
    private long lastDragPacketMillis;
    private CameraType previousCameraType;
    private boolean previousHideGui;
    private boolean cameraTypeCaptured;
    private int snapshotRetryTicks;

    public ForgingScreen(ForgingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 0;
        this.imageHeight = 0;
    }

    @Override
    protected void init() {
        super.init();
        if (this.minecraft != null) {
            if (!this.cameraTypeCaptured) {
                this.previousCameraType = this.minecraft.options.getCameraType();
                this.previousHideGui = this.minecraft.options.hideGui;
                this.cameraTypeCaptured = true;
            }
            this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            this.minecraft.options.hideGui = true;
        }
        ForgingCameraState.activate(this.menu.getAnvilPos());
        this.selectFirstAvailableTool();
        this.snapshotRetryTicks = 0;
        this.requestSnapshot();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // 锻造视角始终采用第一人称渲染语义，从而隐藏本地玩家且不受 F5 模式影响。
        // The forging view always uses first-person rendering semantics, hiding the local player and ignoring F5 modes.
        if (this.minecraft != null) {
            this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            this.minecraft.options.hideGui = true;
        }
        if (this.hammerPulseTicks > 0) {
            this.hammerPulseTicks--;
        }

        SmithingAnvilBlockEntity anvil = this.clientAnvil();
        if (anvil != null && anvil.clientGeometry() != null) {
            this.snapshotRetryTicks = 0;
        } else if (++this.snapshotRetryTicks >= 20) {
            // 方块实体与菜单包可能在进服或刚放置时先后到达；缺少几何时每秒重试，而不是永久空白。
            // Block-entity and menu packets may arrive in either order during join or placement; retry once per
            // second while geometry is absent instead of leaving the workpiece permanently invisible.
            this.snapshotRetryTicks = 0;
            this.requestSnapshot();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.minecraft != null && this.minecraft.options.keyTogglePerspective.matches(keyCode, scanCode)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            SmithingAnvilBlockEntity anvil = this.clientAnvil();
            if (anvil != null) {
                int delta = keyCode == GLFW.GLFW_KEY_UP ? 50 : -50;
                this.sendValueAction(
                        Action.SET_TEST_TEMPERATURE,
                        anvil.clientTestTemperatureCelsius() + delta);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 故意不调用父类渲染，保留真实世界画面而不添加容器暗色遮罩。
        // Deliberately skip the parent renderer to preserve the real world without a container dim layer.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.trial_by_hammer.prototype_notice"),
                this.width / 2,
                this.height - 14,
                0xFFB8B8B8);
        SmithingAnvilBlockEntity anvil = this.clientAnvil();
        if (anvil != null) {
            graphics.drawString(
                    this.font,
                    Component.translatable(
                            "screen.trial_by_hammer.test_temperature",
                            anvil.clientTestTemperatureCelsius()),
                    this.width - 150,
                    10,
                    0xFFFFB56B,
                    true);
        }

        List<ToolEntry> tools = this.collectAvailableTools();
        this.renderToolPanel(graphics, tools, mouseX, mouseY);
        this.renderToolPreview(graphics, mouseX, mouseY, tools);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<ToolEntry> tools = this.collectAvailableTools();
        int clickedTool = button == 0 ? this.findToolAt(tools, mouseX, mouseY) : -1;
        if (clickedTool >= 0 && button == 0) {
            this.selectedInventorySlot = tools.get(clickedTool).inventorySlot();
            return true;
        }

        ToolKind kind = this.selectedKind(tools);
        if (kind == ToolKind.HAMMER && button == 0) {
            this.hammerPulseTicks = 4;
            this.sendAction(Action.HAMMER, mouseX, mouseY, this.force);
            return true;
        }
        if (kind == ToolKind.TONGS) {
            if (button == 0) {
                this.draggingWithTongs = true;
                this.lastDragPacketMillis = Util.getMillis();
                this.sendAction(Action.TONGS_MOVE, mouseX, mouseY, 0);
                return true;
            }
            if (button == 1) {
                this.sendAction(Action.TONGS_FLIP, mouseX, mouseY, 0);
                return true;
            }
        }
        if (kind == ToolKind.THICKNESS_GAUGE && button == 0) {
            this.sendAction(Action.MEASURE, mouseX, mouseY, 0);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0
                && this.draggingWithTongs
                && this.selectedKind(this.collectAvailableTools()) == ToolKind.TONGS) {
            long now = Util.getMillis();
            if (now - this.lastDragPacketMillis >= 33L) {
                this.lastDragPacketMillis = now;
                this.sendAction(Action.TONGS_MOVE, mouseX, mouseY, 0);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingWithTongs) {
            this.draggingWithTongs = false;
            if (this.selectedKind(this.collectAvailableTools()) == ToolKind.TONGS) {
                this.sendAction(Action.TONGS_MOVE, mouseX, mouseY, 0);
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        ToolKind kind = this.selectedKind(this.collectAvailableTools());
        if (kind == ToolKind.TONGS && scrollY != 0.0D) {
            this.sendAction(Action.TONGS_ROTATE, mouseX, mouseY, scrollY > 0.0D ? 1 : -1);
            return true;
        }
        if (kind != ToolKind.HAMMER || scrollY == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        this.force = Mth.clamp(this.force + (scrollY > 0.0D ? 1 : -1), FORCE_MIN, FORCE_MAX);
        return true;
    }

    @Override
    public void removed() {
        ForgingCameraState.deactivate();
        this.draggingWithTongs = false;
        if (this.minecraft != null && this.cameraTypeCaptured && this.previousCameraType != null) {
            this.minecraft.options.setCameraType(this.previousCameraType);
            this.minecraft.options.hideGui = this.previousHideGui;
        }
        super.removed();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 透明世界视图没有传统容器背景。 / The transparent world view has no traditional container background.
    }

    private List<ToolEntry> collectAvailableTools() {
        List<ToolEntry> tools = new ArrayList<>(3);
        if (this.minecraft == null || this.minecraft.player == null) {
            return tools;
        }

        Inventory inventory = this.minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            ToolKind kind = ToolKind.fromStack(stack);
            if (kind != null) {
                tools.add(new ToolEntry(slot, stack, kind));
            }
        }
        return tools;
    }

    private void selectFirstAvailableTool() {
        List<ToolEntry> tools = this.collectAvailableTools();
        if (tools.stream().noneMatch(entry -> entry.inventorySlot() == this.selectedInventorySlot)) {
            this.selectedInventorySlot = tools.isEmpty() ? -1 : tools.getFirst().inventorySlot();
        }
    }

    private void renderToolPanel(GuiGraphics graphics, List<ToolEntry> tools, int mouseX, int mouseY) {
        int panelY = this.toolPanelY();
        int panelWidth = tools.isEmpty() ? 154 : tools.size() * TOOL_SIZE + 8;
        graphics.fill(TOOL_PANEL_X - 4, panelY - 4, TOOL_PANEL_X - 4 + panelWidth, panelY + TOOL_SIZE, 0xAA141414);

        if (tools.isEmpty()) {
            graphics.drawString(
                    this.font,
                    Component.translatable("screen.trial_by_hammer.no_tools"),
                    TOOL_PANEL_X,
                    panelY,
                    0xFFFF7777,
                    true);
            return;
        }

        for (int index = 0; index < tools.size(); index++) {
            ToolEntry entry = tools.get(index);
            int x = TOOL_PANEL_X + index * TOOL_SIZE;
            boolean selected = entry.inventorySlot() == this.selectedInventorySlot;
            int outline = selected ? 0xFFFFC44D : 0xFF707070;
            graphics.fill(x - 2, panelY - 2, x + 20, panelY + 20, selected ? 0xCC3B3020 : 0xAA202020);
            graphics.renderOutline(x - 2, panelY - 2, TOOL_SIZE, TOOL_SIZE, outline);
            graphics.renderItem(entry.stack(), x, panelY);
            graphics.renderItemDecorations(this.font, entry.stack(), x, panelY);

            if (mouseX >= x - 2 && mouseX < x + 20 && mouseY >= panelY - 2 && mouseY < panelY + 20) {
                graphics.renderTooltip(this.font, entry.stack(), mouseX, mouseY);
            }
        }
    }

    private void renderToolPreview(GuiGraphics graphics, int mouseX, int mouseY, List<ToolEntry> tools) {
        ToolKind kind = this.selectedKind(tools);
        if (kind == null || this.isOverToolPanel(tools.size(), mouseX, mouseY)) {
            return;
        }

        switch (kind) {
            case HAMMER -> this.renderHammerPreview(graphics, mouseX, mouseY);
            case TONGS -> {
                this.renderCrosshair(graphics, mouseX, mouseY, 0xFFE2C16B);
                graphics.drawString(
                        this.font,
                        Component.translatable("screen.trial_by_hammer.tongs_help"),
                        mouseX + 10,
                        mouseY + 8,
                        0xFFE2C16B,
                        true);
            }
            case THICKNESS_GAUGE -> {
                this.renderCrosshair(graphics, mouseX, mouseY, 0xFF76D7FF);
                SmithingAnvilBlockEntity anvil = this.clientAnvil();
                int measuredVolumeUnits = anvil == null ? -1 : anvil.clientMeasuredVolumeUnits();
                Component measurement = measuredVolumeUnits < 0
                        ? Component.translatable("screen.trial_by_hammer.thickness_pending")
                        : Component.translatable(
                                "screen.trial_by_hammer.thickness",
                                String.format(
                                        Locale.ROOT,
                                        "%.3f",
                                        measuredVolumeUnits
                                                * LayeredSlabWorkpiece.VERTICAL_CELL_SIZE
                                                / LayeredSlabWorkpiece.THICKNESS_UNITS_PER_CELL),
                                String.format(
                                        Locale.ROOT,
                                        "%.2f",
                                        (double)measuredVolumeUnits
                                                / LayeredSlabWorkpiece.THICKNESS_UNITS_PER_CELL));
                graphics.drawString(
                        this.font,
                        measurement,
                        mouseX + 10,
                        mouseY + 8,
                        0xFF76D7FF,
                        true);
            }
        }
    }

    private void renderHammerPreview(GuiGraphics graphics, int mouseX, int mouseY) {
        int pulse = this.hammerPulseTicks > 0 ? 2 : 0;
        int faceHalfLength = Math.max(5, this.hammerFaceHalfExtentPixels(
                        LayeredSlabWorkpiece.HAMMER_FACE_HALF_LENGTH_CELLS, 12, 64)
                - pulse);
        int faceHalfWidth = Math.max(3, this.hammerFaceHalfExtentPixels(
                        LayeredSlabWorkpiece.HAMMER_FACE_HALF_WIDTH_CELLS, 7, 42)
                - pulse);
        // 预览恢复最初 5×3 的长方形锤面，并与服务端权威物理尺寸一致。
        // The preview restores the original 5×3 rectangular face and matches its server-authoritative physical size.
        graphics.fill(
                mouseX - faceHalfLength,
                mouseY - faceHalfWidth,
                mouseX + faceHalfLength + 1,
                mouseY + faceHalfWidth + 1,
                0x447BCAE1);
        graphics.renderOutline(
                mouseX - faceHalfLength,
                mouseY - faceHalfWidth,
                faceHalfLength * 2 + 1,
                faceHalfWidth * 2 + 1,
                0xFFE6F7FF);

        int ringRadius = (int)Math.ceil(Math.hypot(faceHalfLength, faceHalfWidth)) + 7;
        int ringSegments = 96;
        int activeSegments = Mth.ceil((float)ringSegments * this.force / FORCE_MAX);
        for (int segment = 0; segment < ringSegments; segment++) {
            double angle = Math.PI * 2.0D * segment / ringSegments - Math.PI / 2.0D;
            int x = mouseX + (int)Math.round(Math.cos(angle) * ringRadius);
            int y = mouseY + (int)Math.round(Math.sin(angle) * ringRadius);
            int color = segment < activeSegments ? 0xFFFFB23F : 0x88606060;
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        }

        Component forceLabel = Component.translatable("screen.trial_by_hammer.force", this.force);
        graphics.drawCenteredString(this.font, forceLabel, mouseX, mouseY + ringRadius + 6, 0xFFFFFFFF);
    }

    private int hammerFaceHalfExtentPixels(int physicalHalfExtentCells, int minimum, int maximum) {
        if (this.minecraft == null || this.height <= 0) {
            return minimum;
        }
        Camera camera = this.minecraft.gameRenderer.getMainCamera();
        double planeY = this.menu.getAnvilPos().getY() + 1.005D;
        double distance = Math.max(0.01D, Math.abs(camera.getPosition().y - planeY));
        double tangent = Math.tan(Math.toRadians(this.minecraft.options.fov().get()) / 2.0D);
        double pixelsPerCell = this.height * LayeredSlabWorkpiece.CELL_SIZE / (2.0D * tangent * distance);
        return Mth.clamp(
                (int)Math.round(pixelsPerCell * (physicalHalfExtentCells + 0.5D)),
                minimum,
                maximum);
    }

    private void renderCrosshair(GuiGraphics graphics, int mouseX, int mouseY, int color) {
        graphics.fill(mouseX - 6, mouseY, mouseX + 7, mouseY + 1, color);
        graphics.fill(mouseX, mouseY - 6, mouseX + 1, mouseY + 7, color);
    }

    private int findToolAt(List<ToolEntry> tools, double mouseX, double mouseY) {
        int panelY = this.toolPanelY();
        for (int index = 0; index < tools.size(); index++) {
            int x = TOOL_PANEL_X + index * TOOL_SIZE;
            if (mouseX >= x - 2 && mouseX < x + 20 && mouseY >= panelY - 2 && mouseY < panelY + 20) {
                return index;
            }
        }
        return -1;
    }

    private boolean isOverToolPanel(int toolCount, double mouseX, double mouseY) {
        int panelY = this.toolPanelY();
        int panelWidth = toolCount == 0 ? 154 : toolCount * TOOL_SIZE + 8;
        return mouseX >= TOOL_PANEL_X - 4
                && mouseX < TOOL_PANEL_X - 4 + panelWidth
                && mouseY >= panelY - 4
                && mouseY < panelY + TOOL_SIZE;
    }

    private int toolPanelY() {
        return this.height - TOOL_PANEL_BOTTOM - TOOL_SIZE;
    }

    private ToolKind selectedKind(List<ToolEntry> tools) {
        return tools.stream()
                .filter(entry -> entry.inventorySlot() == this.selectedInventorySlot)
                .map(ToolEntry::kind)
                .findFirst()
                .orElse(null);
    }

    private void sendAction(Action action, double mouseX, double mouseY, int value) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        Vec3 point = this.screenToAnvilPlane(mouseX, mouseY);
        if (point == null) {
            return;
        }
        double centerX = this.menu.getAnvilPos().getX() + 0.5D;
        double centerZ = this.menu.getAnvilPos().getZ() + 0.5D;
        int planeX = (int)Math.round((point.x - centerX) * PLANE_UNITS_PER_BLOCK);
        int planeZ = (int)Math.round((point.z - centerZ) * PLANE_UNITS_PER_BLOCK);
        SmithingAnvilBlockEntity anvil = this.clientAnvil();
        int revision = anvil == null ? -1 : anvil.clientRevision();
        PacketDistributor.sendToServer(new ForgingActionPayload(
                this.menu.getAnvilPos(), action, planeX, planeZ, value, revision));
    }

    private void requestSnapshot() {
        SmithingAnvilBlockEntity anvil = this.clientAnvil();
        int revision = anvil == null ? -1 : anvil.clientRevision();
        PacketDistributor.sendToServer(new ForgingActionPayload(
                this.menu.getAnvilPos(), Action.SYNC_REQUEST, 0, 0, 0, revision));
    }

    private void sendValueAction(Action action, int value) {
        SmithingAnvilBlockEntity anvil = this.clientAnvil();
        int revision = anvil == null ? -1 : anvil.clientRevision();
        PacketDistributor.sendToServer(new ForgingActionPayload(
                this.menu.getAnvilPos(), action, 0, 0, value, revision));
    }

    private Vec3 screenToAnvilPlane(double mouseX, double mouseY) {
        if (this.minecraft == null) {
            return null;
        }
        Camera camera = this.minecraft.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        double normalizedX = 2.0D * mouseX / this.width - 1.0D;
        double normalizedY = 1.0D - 2.0D * mouseY / this.height;
        double tangent = Math.tan(Math.toRadians(this.minecraft.options.fov().get()) / 2.0D);
        double aspect = (double)this.width / this.height;
        Vec3 direction = new Vec3(look.x, look.y, look.z)
                .add(new Vec3(-left.x, -left.y, -left.z).scale(normalizedX * tangent * aspect))
                .add(new Vec3(up.x, up.y, up.z).scale(normalizedY * tangent));
        if (Math.abs(direction.y) < 1.0E-6D) {
            return null;
        }
        double planeY = this.menu.getAnvilPos().getY() + 1.005D;
        double distance = (planeY - origin.y) / direction.y;
        return distance > 0.0D ? origin.add(direction.scale(distance)) : null;
    }

    private SmithingAnvilBlockEntity clientAnvil() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        return this.minecraft.level.getBlockEntity(this.menu.getAnvilPos()) instanceof SmithingAnvilBlockEntity anvil
                ? anvil
                : null;
    }

    private record ToolEntry(int inventorySlot, ItemStack stack, ToolKind kind) {
    }

    private enum ToolKind {
        HAMMER,
        TONGS,
        THICKNESS_GAUGE;

        private static ToolKind fromStack(ItemStack stack) {
            if (stack.is(TrialByHammer.ARTISAN_HAMMER.get())) {
                return HAMMER;
            }
            if (stack.is(TrialByHammer.TONGS.get())) {
                return TONGS;
            }
            if (stack.is(TrialByHammer.THICKNESS_GAUGE.get())) {
                return THICKNESS_GAUGE;
            }
            return null;
        }
    }
}
