package radium.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import radium.client.DynamicSpoofer;
import radium.client.LineSpoofer;
import radium.client.SpoofConfig;

import java.util.ArrayList;
import java.util.List;

public final class RadiumScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int TAB_GAP = 4;

    private static final int VISIBLE_ROWS = 4;
    private static final int ROW_HEIGHT = 46;
    private static final int TARGET_WIDTH = 145;
    private static final int VALUE_WIDTH = 145;
    private static final int REMOVE_WIDTH = 28;
    private static final int SCROLLBAR_X_OFFSET = 342;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_TRACK_HEIGHT = VISIBLE_ROWS * ROW_HEIGHT - 8;

    private static Tab rememberedTab = Tab.VARIABLES;
    private static int rememberedLineScroll;
    private static int rememberedDynamicScroll;

    private Tab activeTab = rememberedTab;
    private int lineScroll = rememberedLineScroll;
    private int dynamicScroll = rememberedDynamicScroll;
    private boolean refreshingLineRows;
    private boolean refreshingDynamicRows;

    private Button variablesTabButton;
    private Button linesTabButton;
    private Button dynamicTabButton;

    private Button variableToggleButton;
    private Button lineToggleButton;
    private Button dynamicToggleButton;

    private Button addLineButton;
    private Button lineScrollUpButton;
    private Button lineScrollDownButton;

    private Button addDynamicButton;
    private Button dynamicScrollUpButton;
    private Button dynamicScrollDownButton;

    private Button doneButton;

    private EditBox objectiveField;
    private EditBox valueField;

    private final List<LineRowWidgets> lineRows = new ArrayList<>();
    private final List<DynamicRowWidgets> dynamicRows = new ArrayList<>();

    private final Screen parent;
    private ScrollDrag scrollDrag = ScrollDrag.NONE;
    private double scrollbarGrabOffset;

    /** Opens from the in-game keybind. */
    public RadiumScreen() {
        this(null);
    }

    /** Opens from another screen, such as Mod Menu, and returns to it on close. */
    public RadiumScreen(Screen parent) {
        super(Component.literal("Radium - Scoreboard Spoofer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = panelTop();
        int tabWidth = (PANEL_WIDTH - TAB_GAP * 2) / 3;

        this.variablesTabButton = Button.builder(
                Component.literal("Variables"),
                button -> switchTab(Tab.VARIABLES)
        ).bounds(left, top + 24, tabWidth, 20).build();
        this.addRenderableWidget(this.variablesTabButton);

        this.linesTabButton = Button.builder(
                Component.literal("Lines"),
                button -> switchTab(Tab.LINES)
        ).bounds(left + tabWidth + TAB_GAP, top + 24, tabWidth, 20).build();
        this.addRenderableWidget(this.linesTabButton);

        this.dynamicTabButton = Button.builder(
                Component.literal("Dynamic"),
                button -> switchTab(Tab.DYNAMIC)
        ).bounds(left + (tabWidth + TAB_GAP) * 2, top + 24, tabWidth, 20).build();
        this.addRenderableWidget(this.dynamicTabButton);

        createVariablesWidgets(left, top);
        createLineWidgets(left, top);
        createDynamicWidgets(left, top);

        this.doneButton = Button.builder(
                Component.literal("Done"),
                button -> this.onClose()
        ).bounds(left, top + 310, PANEL_WIDTH, 20).build();
        this.addRenderableWidget(this.doneButton);

        clampLineScroll();
        clampDynamicScroll();
        refreshLineRows();
        refreshDynamicRows();
        updateTabVisibility();
    }

    private void createVariablesWidgets(int left, int top) {
        this.variableToggleButton = Button.builder(
                getVariableToggleText(),
                button -> {
                    SpoofConfig.enabled = !SpoofConfig.enabled;
                    button.setMessage(getVariableToggleText());
                }
        ).bounds(left, top + 54, PANEL_WIDTH, 20).build();
        this.addRenderableWidget(this.variableToggleButton);

        this.objectiveField = new EditBox(
                this.font,
                left,
                top + 94,
                PANEL_WIDTH,
                20,
                Component.literal("Objective / variable")
        );
        this.objectiveField.setValue(SpoofConfig.targetObjective);
        this.objectiveField.setMaxLength(64);
        this.objectiveField.setResponder(value -> SpoofConfig.targetObjective = value.trim());
        this.addRenderableWidget(this.objectiveField);

        this.valueField = new EditBox(
                this.font,
                left,
                top + 146,
                PANEL_WIDTH,
                20,
                Component.literal("Fake display value")
        );
        this.valueField.setValue(SpoofConfig.fakeValue);
        this.valueField.setMaxLength(128);
        this.valueField.setResponder(value -> SpoofConfig.fakeValue = value);
        this.addRenderableWidget(this.valueField);
    }

    private void createLineWidgets(int left, int top) {
        this.lineToggleButton = Button.builder(
                getLineToggleText(),
                button -> toggleLineSpoofing()
        ).bounds(left, top + 54, PANEL_WIDTH, 20).build();
        this.addRenderableWidget(this.lineToggleButton);

        createLineRows(left, top);

        this.addLineButton = Button.builder(
                Component.literal("+ Add Line"),
                button -> addLineEntry()
        ).bounds(left, top + 284, 210, 20).build();
        this.addRenderableWidget(this.addLineButton);

        this.lineScrollUpButton = Button.builder(
                Component.literal("▲"),
                button -> setLineScroll(this.lineScroll - 1)
        ).bounds(left + 214, top + 284, 69, 20).build();
        this.addRenderableWidget(this.lineScrollUpButton);

        this.lineScrollDownButton = Button.builder(
                Component.literal("▼"),
                button -> setLineScroll(this.lineScroll + 1)
        ).bounds(left + 287, top + 284, 73, 20).build();
        this.addRenderableWidget(this.lineScrollDownButton);
    }

    private void createDynamicWidgets(int left, int top) {
        this.dynamicToggleButton = Button.builder(
                getDynamicToggleText(),
                button -> toggleDynamicTracking()
        ).bounds(left, top + 54, PANEL_WIDTH, 20).build();
        this.addRenderableWidget(this.dynamicToggleButton);

        createDynamicRows(left, top);

        this.addDynamicButton = Button.builder(
                Component.literal("+ Add Dynamic"),
                button -> addDynamicEntry()
        ).bounds(left, top + 284, 210, 20).build();
        this.addRenderableWidget(this.addDynamicButton);

        this.dynamicScrollUpButton = Button.builder(
                Component.literal("▲"),
                button -> setDynamicScroll(this.dynamicScroll - 1)
        ).bounds(left + 214, top + 284, 69, 20).build();
        this.addRenderableWidget(this.dynamicScrollUpButton);

        this.dynamicScrollDownButton = Button.builder(
                Component.literal("▼"),
                button -> setDynamicScroll(this.dynamicScroll + 1)
        ).bounds(left + 287, top + 284, 73, 20).build();
        this.addRenderableWidget(this.dynamicScrollDownButton);
    }

    private void toggleLineSpoofing() {
        boolean enable = !SpoofConfig.lineEnabled;

        if (enable) {
            // Fixed Lines and Dynamic both own scoreboard-team prefixes. Running
            // both at once creates ambiguous ownership, so enabling one mode
            // immediately disables and restores the other.
            if (SpoofConfig.dynamicEnabled) {
                SpoofConfig.dynamicEnabled = false;
                DynamicSpoofer.restoreAll();
            }
            SpoofConfig.lineEnabled = true;
        } else {
            SpoofConfig.lineEnabled = false;
            LineSpoofer.restoreAll();
        }

        refreshModeToggleLabels();
    }

    private void toggleDynamicTracking() {
        boolean enable = !SpoofConfig.dynamicEnabled;

        if (enable) {
            if (SpoofConfig.lineEnabled) {
                SpoofConfig.lineEnabled = false;
                LineSpoofer.restoreAll();
            }
            SpoofConfig.dynamicEnabled = true;
        } else {
            SpoofConfig.dynamicEnabled = false;
            DynamicSpoofer.restoreAll();
        }

        refreshModeToggleLabels();
    }

    private void refreshModeToggleLabels() {
        if (this.lineToggleButton != null) {
            this.lineToggleButton.setMessage(getLineToggleText());
        }
        if (this.dynamicToggleButton != null) {
            this.dynamicToggleButton.setMessage(getDynamicToggleText());
        }
    }

    private void createLineRows(int left, int top) {
        this.lineRows.clear();
        int rowStart = top + 98;

        for (int slot = 0; slot < VISIBLE_ROWS; slot++) {
            int rowY = rowStart + slot * ROW_HEIGHT;
            final int rowSlot = slot;

            EditBox target = new EditBox(
                    this.font,
                    left,
                    rowY,
                    TARGET_WIDTH,
                    20,
                    Component.literal("Line contains")
            );
            target.setMaxLength(96);
            target.setResponder(value -> updateLineTarget(rowSlot, value));
            this.addRenderableWidget(target);

            EditBox fakeValue = new EditBox(
                    this.font,
                    left + 150,
                    rowY,
                    VALUE_WIDTH,
                    20,
                    Component.literal("Fake value")
            );
            fakeValue.setMaxLength(128);
            fakeValue.setResponder(value -> updateLineValue(rowSlot, value));
            this.addRenderableWidget(fakeValue);

            Button remove = Button.builder(
                    Component.literal("X"),
                    button -> removeVisibleLine(rowSlot)
            ).bounds(left + 300, rowY, REMOVE_WIDTH, 20).build();
            this.addRenderableWidget(remove);

            this.lineRows.add(new LineRowWidgets(target, fakeValue, remove));
        }
    }

    private void createDynamicRows(int left, int top) {
        this.dynamicRows.clear();
        int rowStart = top + 98;

        for (int slot = 0; slot < VISIBLE_ROWS; slot++) {
            int rowY = rowStart + slot * ROW_HEIGHT;
            final int rowSlot = slot;

            EditBox target = new EditBox(
                    this.font,
                    left,
                    rowY,
                    TARGET_WIDTH,
                    20,
                    Component.literal("Line contains")
            );
            target.setMaxLength(96);
            target.setResponder(value -> updateDynamicTarget(rowSlot, value));
            this.addRenderableWidget(target);

            EditBox startingFake = new EditBox(
                    this.font,
                    left + 150,
                    rowY,
                    VALUE_WIDTH,
                    20,
                    Component.literal("Starting fake value")
            );
            startingFake.setMaxLength(128);
            startingFake.setResponder(value -> updateDynamicStart(rowSlot, value));
            this.addRenderableWidget(startingFake);

            Button remove = Button.builder(
                    Component.literal("X"),
                    button -> removeVisibleDynamic(rowSlot)
            ).bounds(left + 300, rowY, REMOVE_WIDTH, 20).build();
            this.addRenderableWidget(remove);

            this.dynamicRows.add(new DynamicRowWidgets(target, startingFake, remove));
        }
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
        rememberedTab = tab;
        refreshLineRows();
        refreshDynamicRows();
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        boolean variables = this.activeTab == Tab.VARIABLES;
        boolean lines = this.activeTab == Tab.LINES;
        boolean dynamic = this.activeTab == Tab.DYNAMIC;

        this.variablesTabButton.active = !variables;
        this.linesTabButton.active = !lines;
        this.dynamicTabButton.active = !dynamic;

        setWidgetVisible(this.variableToggleButton, variables);
        setWidgetVisible(this.objectiveField, variables);
        setWidgetVisible(this.valueField, variables);

        setWidgetVisible(this.lineToggleButton, lines);
        setWidgetVisible(this.addLineButton, lines);
        setWidgetVisible(this.lineScrollUpButton, lines);
        setWidgetVisible(this.lineScrollDownButton, lines);

        setWidgetVisible(this.dynamicToggleButton, dynamic);
        setWidgetVisible(this.addDynamicButton, dynamic);
        setWidgetVisible(this.dynamicScrollUpButton, dynamic);
        setWidgetVisible(this.dynamicScrollDownButton, dynamic);

        for (int slot = 0; slot < this.lineRows.size(); slot++) {
            int entryIndex = this.lineScroll + slot;
            boolean visible = lines && entryIndex < SpoofConfig.lineEntries.size();
            LineRowWidgets row = this.lineRows.get(slot);
            setWidgetVisible(row.target, visible);
            setWidgetVisible(row.fakeValue, visible);
            setWidgetVisible(row.remove, visible);
        }

        for (int slot = 0; slot < this.dynamicRows.size(); slot++) {
            int entryIndex = this.dynamicScroll + slot;
            boolean visible = dynamic && entryIndex < SpoofConfig.dynamicEntries.size();
            DynamicRowWidgets row = this.dynamicRows.get(slot);
            setWidgetVisible(row.target, visible);
            setWidgetVisible(row.startingFake, visible);
            setWidgetVisible(row.remove, visible);
        }

        updateScrollControls();
    }

    private void addLineEntry() {
        SpoofConfig.LineEntry added = SpoofConfig.addLineEntry();
        if (added == null) {
            return;
        }

        setLineScroll(maxLineScroll());
        refreshLineRows();
        updateTabVisibility();

        int slot = SpoofConfig.lineEntries.indexOf(added) - this.lineScroll;
        if (slot >= 0 && slot < this.lineRows.size()) {
            this.setFocused(this.lineRows.get(slot).target);
        }
    }

    private void addDynamicEntry() {
        SpoofConfig.DynamicEntry added = SpoofConfig.addDynamicEntry();
        if (added == null) {
            return;
        }

        setDynamicScroll(maxDynamicScroll());
        refreshDynamicRows();
        updateTabVisibility();

        int slot = SpoofConfig.dynamicEntries.indexOf(added) - this.dynamicScroll;
        if (slot >= 0 && slot < this.dynamicRows.size()) {
            this.setFocused(this.dynamicRows.get(slot).target);
        }
    }

    private void removeVisibleLine(int slot) {
        int entryIndex = this.lineScroll + slot;
        if (entryIndex < 0 || entryIndex >= SpoofConfig.lineEntries.size()) {
            return;
        }

        SpoofConfig.LineEntry entry = SpoofConfig.lineEntries.get(entryIndex);
        LineSpoofer.removeEntry(entry);
        SpoofConfig.removeLineEntry(entry);

        clampLineScroll();
        refreshLineRows();
        updateTabVisibility();
    }

    private void removeVisibleDynamic(int slot) {
        int entryIndex = this.dynamicScroll + slot;
        if (entryIndex < 0 || entryIndex >= SpoofConfig.dynamicEntries.size()) {
            return;
        }

        SpoofConfig.DynamicEntry entry = SpoofConfig.dynamicEntries.get(entryIndex);
        DynamicSpoofer.removeEntry(entry);
        SpoofConfig.removeDynamicEntry(entry);

        clampDynamicScroll();
        refreshDynamicRows();
        updateTabVisibility();
    }

    private void updateLineTarget(int slot, String value) {
        if (this.refreshingLineRows) {
            return;
        }
        SpoofConfig.LineEntry entry = lineEntryForSlot(slot);
        if (entry != null) {
            entry.targetLine = value;
        }
    }

    private void updateLineValue(int slot, String value) {
        if (this.refreshingLineRows) {
            return;
        }
        SpoofConfig.LineEntry entry = lineEntryForSlot(slot);
        if (entry != null) {
            entry.fakeValue = value;
        }
    }

    private void updateDynamicTarget(int slot, String value) {
        if (this.refreshingDynamicRows) {
            return;
        }
        SpoofConfig.DynamicEntry entry = dynamicEntryForSlot(slot);
        if (entry != null) {
            entry.targetLine = value;
        }
    }

    private void updateDynamicStart(int slot, String value) {
        if (this.refreshingDynamicRows) {
            return;
        }
        SpoofConfig.DynamicEntry entry = dynamicEntryForSlot(slot);
        if (entry != null) {
            entry.startingFakeValue = value;
        }
    }

    private SpoofConfig.LineEntry lineEntryForSlot(int slot) {
        int index = this.lineScroll + slot;
        if (index < 0 || index >= SpoofConfig.lineEntries.size()) {
            return null;
        }
        return SpoofConfig.lineEntries.get(index);
    }

    private SpoofConfig.DynamicEntry dynamicEntryForSlot(int slot) {
        int index = this.dynamicScroll + slot;
        if (index < 0 || index >= SpoofConfig.dynamicEntries.size()) {
            return null;
        }
        return SpoofConfig.dynamicEntries.get(index);
    }

    private void refreshLineRows() {
        if (this.lineRows.isEmpty()) {
            return;
        }

        clampLineScroll();
        this.refreshingLineRows = true;
        try {
            for (int slot = 0; slot < this.lineRows.size(); slot++) {
                LineRowWidgets row = this.lineRows.get(slot);
                SpoofConfig.LineEntry entry = lineEntryForSlot(slot);
                if (entry == null) {
                    row.target.setValue("");
                    row.fakeValue.setValue("");
                } else {
                    if (!row.target.getValue().equals(entry.targetLine)) {
                        row.target.setValue(entry.targetLine);
                    }
                    if (!row.fakeValue.getValue().equals(entry.fakeValue)) {
                        row.fakeValue.setValue(entry.fakeValue);
                    }
                }
            }
        } finally {
            this.refreshingLineRows = false;
        }
        updateScrollControls();
    }

    private void refreshDynamicRows() {
        if (this.dynamicRows.isEmpty()) {
            return;
        }

        clampDynamicScroll();
        this.refreshingDynamicRows = true;
        try {
            for (int slot = 0; slot < this.dynamicRows.size(); slot++) {
                DynamicRowWidgets row = this.dynamicRows.get(slot);
                SpoofConfig.DynamicEntry entry = dynamicEntryForSlot(slot);
                if (entry == null) {
                    row.target.setValue("");
                    row.startingFake.setValue("");
                } else {
                    if (!row.target.getValue().equals(entry.targetLine)) {
                        row.target.setValue(entry.targetLine);
                    }
                    if (!row.startingFake.getValue().equals(entry.startingFakeValue)) {
                        row.startingFake.setValue(entry.startingFakeValue);
                    }
                }
            }
        } finally {
            this.refreshingDynamicRows = false;
        }
        updateScrollControls();
    }

    private void setLineScroll(int scroll) {
        int clamped = Math.max(0, Math.min(scroll, maxLineScroll()));
        if (clamped == this.lineScroll) {
            updateScrollControls();
            return;
        }
        this.lineScroll = clamped;
        rememberedLineScroll = clamped;
        refreshLineRows();
        updateTabVisibility();
    }

    private void setDynamicScroll(int scroll) {
        int clamped = Math.max(0, Math.min(scroll, maxDynamicScroll()));
        if (clamped == this.dynamicScroll) {
            updateScrollControls();
            return;
        }
        this.dynamicScroll = clamped;
        rememberedDynamicScroll = clamped;
        refreshDynamicRows();
        updateTabVisibility();
    }

    private void clampLineScroll() {
        this.lineScroll = Math.max(0, Math.min(this.lineScroll, maxLineScroll()));
        rememberedLineScroll = this.lineScroll;
    }

    private void clampDynamicScroll() {
        this.dynamicScroll = Math.max(0, Math.min(this.dynamicScroll, maxDynamicScroll()));
        rememberedDynamicScroll = this.dynamicScroll;
    }

    private int maxLineScroll() {
        return Math.max(0, SpoofConfig.lineEntries.size() - VISIBLE_ROWS);
    }

    private int maxDynamicScroll() {
        return Math.max(0, SpoofConfig.dynamicEntries.size() - VISIBLE_ROWS);
    }

    private void updateScrollControls() {
        if (this.lineScrollUpButton == null || this.dynamicScrollUpButton == null) {
            return;
        }

        boolean lines = this.activeTab == Tab.LINES;
        this.lineScrollUpButton.active = lines && this.lineScroll > 0;
        this.lineScrollDownButton.active = lines && this.lineScroll < maxLineScroll();
        this.addLineButton.active = lines && SpoofConfig.lineEntries.size() < SpoofConfig.MAX_LINE_ENTRIES;

        boolean dynamic = this.activeTab == Tab.DYNAMIC;
        this.dynamicScrollUpButton.active = dynamic && this.dynamicScroll > 0;
        this.dynamicScrollDownButton.active = dynamic && this.dynamicScroll < maxDynamicScroll();
        this.addDynamicButton.active = dynamic && SpoofConfig.dynamicEntries.size() < SpoofConfig.MAX_DYNAMIC_ENTRIES;
    }

    private static void setWidgetVisible(AbstractWidget widget, boolean visible) {
        widget.visible = visible;
        widget.active = visible;
    }

    private static Component getVariableToggleText() {
        return Component.literal("Variable spoof: ")
                .append(Component.literal(SpoofConfig.enabled ? "ON" : "OFF")
                        .withStyle(SpoofConfig.enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static Component getLineToggleText() {
        return Component.literal("Line spoofing: ")
                .append(Component.literal(SpoofConfig.lineEnabled ? "ON" : "OFF")
                        .withStyle(SpoofConfig.lineEnabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static Component getDynamicToggleText() {
        return Component.literal("Dynamic tracking: ")
                .append(Component.literal(SpoofConfig.dynamicEnabled ? "ON" : "OFF")
                        .withStyle(SpoofConfig.dynamicEnabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int top = panelTop();

        graphics.centeredText(
                this.font,
                this.title,
                centerX,
                top + 5,
                0xFFFFFFFF
        );

        switch (this.activeTab) {
            case VARIABLES -> renderVariablesTab(graphics, centerX, top);
            case LINES -> renderLinesTab(graphics, centerX, top);
            case DYNAMIC -> renderDynamicTab(graphics, centerX, top);
        }
    }

    private void renderVariablesTab(GuiGraphicsExtractor graphics, int centerX, int top) {
        graphics.centeredText(
                this.font,
                Component.literal("Objective / variable (blank = current sidebar)"),
                centerX,
                top + 81,
                0xFFB8B8B8
        );

        String currentObjective = SpoofConfig.lastSidebarObjective.isBlank()
                ? "none detected"
                : SpoofConfig.lastSidebarObjective;
        graphics.centeredText(
                this.font,
                Component.literal("Current sidebar: " + shorten(currentObjective, 45)),
                centerX,
                top + 118,
                0xFF888888
        );

        graphics.centeredText(
                this.font,
                Component.literal("Fake display value (4M, 2.5B, 1T, etc.)"),
                centerX,
                top + 133,
                0xFFB8B8B8
        );

        graphics.centeredText(
                this.font,
                Component.literal(shorten(SpoofConfig.statusMessage, 55)),
                centerX,
                top + 174,
                SpoofConfig.enabled ? 0xFF9FD49F : 0xFF999999
        );

        graphics.centeredText(
                this.font,
                Component.literal("Best for normal player/objective scoreboards"),
                centerX,
                top + 190,
                0xFF777777
        );
    }

    private void renderLinesTab(GuiGraphicsExtractor graphics, int centerX, int top) {
        int left = centerX - PANEL_WIDTH / 2;
        int rowStart = top + 98;

        renderListHeaders(graphics, left, top, "Line contains", "Fake value");

        for (int slot = 0; slot < VISIBLE_ROWS; slot++) {
            SpoofConfig.LineEntry entry = lineEntryForSlot(slot);
            if (entry == null) {
                continue;
            }
            int rowY = rowStart + slot * ROW_HEIGHT;
            String status = entry.statusMessage == null || entry.statusMessage.isBlank()
                    ? "Ready"
                    : entry.statusMessage;
            graphics.centeredText(
                    this.font,
                    Component.literal(shorten(status, 50)),
                    left + 164,
                    rowY + 24,
                    lineStatusColor(entry)
            );
        }

        renderScrollbar(graphics, left, rowStart, SpoofConfig.lineEntries.size(), this.lineScroll, maxLineScroll());

        graphics.centeredText(
                this.font,
                Component.literal("Entries " + SpoofConfig.lineEntries.size() + "/" + SpoofConfig.MAX_LINE_ENTRIES
                        + "  •  wheel, drag bar, or ▲/▼ to scroll"),
                centerX,
                top + 273,
                0xFF777777
        );
    }

    private void renderDynamicTab(GuiGraphicsExtractor graphics, int centerX, int top) {
        int left = centerX - PANEL_WIDTH / 2;
        int rowStart = top + 98;

        renderListHeaders(graphics, left, top, "Line contains", "Starting fake");

        for (int slot = 0; slot < VISIBLE_ROWS; slot++) {
            SpoofConfig.DynamicEntry entry = dynamicEntryForSlot(slot);
            if (entry == null) {
                continue;
            }
            int rowY = rowStart + slot * ROW_HEIGHT;
            String status = entry.statusMessage == null || entry.statusMessage.isBlank()
                    ? "Ready"
                    : entry.statusMessage;
            graphics.centeredText(
                    this.font,
                    Component.literal(shorten(status, 50)),
                    left + 164,
                    rowY + 24,
                    dynamicStatusColor(entry)
            );
        }

        renderScrollbar(
                graphics,
                left,
                rowStart,
                SpoofConfig.dynamicEntries.size(),
                this.dynamicScroll,
                maxDynamicScroll()
        );

        graphics.centeredText(
                this.font,
                Component.literal(SpoofConfig.dynamicEntries.size() + "/" + SpoofConfig.MAX_DYNAMIC_ENTRIES
                        + "  •  whole-unit tracking  •  Lines and Dynamic are exclusive"),
                centerX,
                top + 273,
                0xFF777777
        );
    }

    private void renderListHeaders(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            String leftTitle,
            String rightTitle
    ) {
        graphics.centeredText(
                this.font,
                Component.literal(leftTitle),
                left + TARGET_WIDTH / 2,
                top + 83,
                0xFFB8B8B8
        );
        graphics.centeredText(
                this.font,
                Component.literal(rightTitle),
                left + 150 + VALUE_WIDTH / 2,
                top + 83,
                0xFFB8B8B8
        );
    }

    private void renderScrollbar(
            GuiGraphicsExtractor graphics,
            int left,
            int rowStart,
            int entryCount,
            int scroll,
            int maxScroll
    ) {
        ScrollbarMetrics bar = scrollbarMetrics(left, rowStart, entryCount, scroll, maxScroll);

        graphics.fillGradient(
                bar.trackX,
                bar.trackY,
                bar.trackX + SCROLLBAR_WIDTH,
                bar.trackY + bar.trackHeight,
                0xFF2A2A2A,
                0xFF2A2A2A
        );

        int thumbColor = maxScroll > 0 ? 0xFFB8B8B8 : 0xFF666666;
        graphics.fillGradient(
                bar.trackX + 1,
                bar.thumbY,
                bar.trackX + SCROLLBAR_WIDTH - 1,
                bar.thumbY + bar.thumbHeight,
                thumbColor,
                thumbColor
        );
    }

    private ScrollbarMetrics scrollbarMetrics(
            int left,
            int rowStart,
            int entryCount,
            int scroll,
            int maxScroll
    ) {
        int trackX = left + SCROLLBAR_X_OFFSET;
        int total = Math.max(1, entryCount);
        int visible = Math.min(VISIBLE_ROWS, total);
        int thumbHeight = Math.max(18, SCROLLBAR_TRACK_HEIGHT * visible / total);
        thumbHeight = Math.min(SCROLLBAR_TRACK_HEIGHT, thumbHeight);

        int travel = Math.max(0, SCROLLBAR_TRACK_HEIGHT - thumbHeight);
        int thumbY = maxScroll == 0
                ? rowStart
                : rowStart + (int) Math.round((double) travel * scroll / maxScroll);

        return new ScrollbarMetrics(
                trackX,
                rowStart,
                SCROLLBAR_TRACK_HEIGHT,
                thumbY,
                thumbHeight,
                maxScroll
        );
    }

    private int lineStatusColor(SpoofConfig.LineEntry entry) {
        if (!SpoofConfig.lineEnabled) {
            return 0xFF777777;
        }
        String status = entry.statusMessage == null ? "" : entry.statusMessage.toLowerCase();
        if (status.startsWith("locked")) {
            return 0xFF9FD49F;
        }
        if (status.contains("already used") || status.startsWith("could not")) {
            return 0xFFD49F9F;
        }
        return 0xFF999999;
    }

    private int dynamicStatusColor(SpoofConfig.DynamicEntry entry) {
        if (!SpoofConfig.dynamicEnabled) {
            return 0xFF777777;
        }
        String status = entry.statusMessage == null ? "" : entry.statusMessage.toLowerCase();
        if (status.startsWith("tracking")) {
            return 0xFF9FD49F;
        }
        if (status.contains("numeric") || status.contains("owned") || status.startsWith("could not")) {
            return 0xFFD49F9F;
        }
        return 0xFF999999;
    }

    /**
     * Mouse-wheel scrolling is row-based because the list virtualizes four edit
     * rows at a time. Large wheel deltas can advance more than one row.
     */
    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount
    ) {
        if ((this.activeTab != Tab.LINES && this.activeTab != Tab.DYNAMIC) || verticalAmount == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int top = panelTop();
        int listTop = top + 94;
        int listBottom = top + 278;

        if (mouseX < left || mouseX > left + PANEL_WIDTH || mouseY < listTop || mouseY > listBottom) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int rows = Math.max(1, (int) Math.round(Math.abs(verticalAmount)));
        int delta = verticalAmount > 0.0D ? -rows : rows;
        if (this.activeTab == Tab.LINES) {
            setLineScroll(this.lineScroll + delta);
        } else {
            setDynamicScroll(this.dynamicScroll + delta);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && beginScrollbarDrag(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (this.scrollDrag != ScrollDrag.NONE) {
            updateScrollbarDrag(event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.scrollDrag != ScrollDrag.NONE && event.button() == 0) {
            this.scrollDrag = ScrollDrag.NONE;
            this.scrollbarGrabOffset = 0.0D;
            this.setDragging(false);
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean beginScrollbarDrag(double mouseX, double mouseY) {
        if (this.activeTab != Tab.LINES && this.activeTab != Tab.DYNAMIC) {
            return false;
        }

        int maxScroll = this.activeTab == Tab.LINES ? maxLineScroll() : maxDynamicScroll();
        if (maxScroll <= 0) {
            return false;
        }

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int rowStart = panelTop() + 98;
        int entryCount = this.activeTab == Tab.LINES
                ? SpoofConfig.lineEntries.size()
                : SpoofConfig.dynamicEntries.size();
        int currentScroll = this.activeTab == Tab.LINES ? this.lineScroll : this.dynamicScroll;
        ScrollbarMetrics bar = scrollbarMetrics(left, rowStart, entryCount, currentScroll, maxScroll);

        if (mouseX < bar.trackX || mouseX > bar.trackX + SCROLLBAR_WIDTH
                || mouseY < bar.trackY || mouseY > bar.trackY + bar.trackHeight) {
            return false;
        }

        if (mouseY >= bar.thumbY && mouseY <= bar.thumbY + bar.thumbHeight) {
            this.scrollbarGrabOffset = mouseY - bar.thumbY;
        } else {
            // Clicking an empty part of the track jumps the thumb toward the
            // cursor and immediately starts dragging from its center.
            this.scrollbarGrabOffset = bar.thumbHeight / 2.0D;
        }

        this.scrollDrag = this.activeTab == Tab.LINES ? ScrollDrag.LINES : ScrollDrag.DYNAMIC;
        this.setDragging(true);
        updateScrollbarDrag(mouseY);
        return true;
    }

    private void updateScrollbarDrag(double mouseY) {
        if (this.scrollDrag == ScrollDrag.NONE) {
            return;
        }

        int centerX = this.width / 2;
        int left = centerX - PANEL_WIDTH / 2;
        int rowStart = panelTop() + 98;

        boolean lines = this.scrollDrag == ScrollDrag.LINES;
        int entryCount = lines ? SpoofConfig.lineEntries.size() : SpoofConfig.dynamicEntries.size();
        int maxScroll = lines ? maxLineScroll() : maxDynamicScroll();
        int currentScroll = lines ? this.lineScroll : this.dynamicScroll;
        ScrollbarMetrics bar = scrollbarMetrics(left, rowStart, entryCount, currentScroll, maxScroll);

        int travel = Math.max(0, bar.trackHeight - bar.thumbHeight);
        if (travel == 0 || maxScroll == 0) {
            return;
        }

        double desiredTop = mouseY - this.scrollbarGrabOffset;
        double clampedTop = Math.max(bar.trackY, Math.min(desiredTop, bar.trackY + travel));
        double ratio = (clampedTop - bar.trackY) / travel;
        int newScroll = (int) Math.round(ratio * maxScroll);

        if (lines) {
            setLineScroll(newScroll);
        } else {
            setDynamicScroll(newScroll);
        }
    }

    private int panelTop() {
        return Math.max(18, this.height / 2 - 170);
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    @Override
    public void onClose() {
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Tab {
        VARIABLES,
        LINES,
        DYNAMIC
    }

    private enum ScrollDrag {
        NONE,
        LINES,
        DYNAMIC
    }

    private record ScrollbarMetrics(
            int trackX,
            int trackY,
            int trackHeight,
            int thumbY,
            int thumbHeight,
            int maxScroll
    ) {
    }

    private record LineRowWidgets(EditBox target, EditBox fakeValue, Button remove) {
    }

    private record DynamicRowWidgets(EditBox target, EditBox startingFake, Button remove) {
    }
}
