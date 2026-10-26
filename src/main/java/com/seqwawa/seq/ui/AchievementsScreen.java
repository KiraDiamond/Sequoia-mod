package com.seqwawa.seq.ui;

import static com.seqwawa.seq.managers.ThemeManager.color;
import static com.seqwawa.seq.ui.theme.UiColor.*;
import static com.seqwawa.seq.utils.rendering.UiCanvas.HorizontalAlign.LEFT;
import static com.seqwawa.seq.utils.rendering.UiCanvas.HorizontalAlign.RIGHT;

import com.seqwawa.seq.client.SeqClient;
import com.seqwawa.seq.managers.GuildRaidProgressService;
import com.seqwawa.seq.managers.GuildRaidProgressService.State;
import com.seqwawa.seq.model.GuildRaidProgress;
import com.seqwawa.seq.model.GuildRaidProgress.Category;
import com.seqwawa.seq.model.GuildRaidProgress.Milestone;
import com.seqwawa.seq.model.SeqRaid;
import com.seqwawa.seq.model.SeqTier;
import com.seqwawa.seq.ui.theme.UiColor;
import com.seqwawa.seq.utils.rendering.UiCanvas;
import com.seqwawa.seq.utils.rendering.UiCanvas.HorizontalAlign;
import com.seqwawa.seq.utils.rendering.UiRenderer;
import com.seqwawa.seq.utils.rendering.MinecraftUiRenderer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public final class AchievementsScreen extends Screen {

    private static final List<SeqRaid> RAID_ORDER = List.of(SeqRaid.NOTG, SeqRaid.NOL, SeqRaid.TCC, SeqRaid.TNA, SeqRaid.TWP);

    private static final float MARGIN = SequoiaUiStyle.CONTENT_PADDING;
    private static final float HEADER_HEIGHT = SequoiaUiStyle.HEADER_HEIGHT;
    private static final float CARD_GAP = 6;
    private static final float SECTION_HEIGHT = 26;
    private static final float SUMMARY_HEIGHT = 28;
    private static final float SCROLL_SPEED = 30;

    private final Screen parent;
    private final GuildRaidProgressService service = GuildRaidProgressService.getInstance();

    private List<Row> rows = List.of();
    private State state = State.LOADING;
    private GuildRaidProgress shown = GuildRaidProgress.EMPTY;
    private float scrollOffset;
    private float maxScroll;
    private float mouseX;
    private float mouseY;

    public AchievementsScreen(Screen parent) {
        super(Component.literal("Sequoia Achievements"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        scrollOffset = 0;
        refresh();
    }

    private void refresh() {
        state = service.state();
        shown = service.progress();
        rows = buildRows(shown);
        if (state != State.READY) {
            scrollOffset = 0;
            maxScroll = 0;
        }
    }

    static List<Row> buildRows(GuildRaidProgress progress) {
        if (progress.categories() != null) {
            return progress.categories().stream().map(AchievementsScreen::row).toList();
        }
        List<Row> rows = new ArrayList<>(RAID_ORDER.size() + 1);
        for (SeqRaid raid : RAID_ORDER) {
            rows.add(legacyRow(raid.displayName(), raid.code(), raid.assetKey(),
                    progress.count(raid), progress.tier(raid), false));
        }
        rows.add(legacyRow("All Guild Raids", "All Raids", "icon",
                progress.totalCount(), progress.totalTier(), true));
        return List.copyOf(rows);
    }

    private static Row legacyRow(String name, String compactName, String icon, long count, SeqTier tier, boolean total) {
        return new Row("raid:" + compactName, name, compactName, icon, "raid", count, tier,
                tier == null ? null : tier.label(), 0, null, total, false, false, false, 0);
    }

    private static Row row(Category category) {
        String name = category.label() == null || category.label().isBlank() ? category.key() : category.label();
        String icon = "raid:all raids".equals(category.key()) ? "icon" : category.assetKey();
        for (SeqRaid raid : SeqRaid.values()) {
            if (("raid:" + raid.code()).equals(category.key())) icon = raid.assetKey();
        }
        Milestone next = category.next();
        return new Row(category.key(), name, name, icon, category.kind(),
                category.value() == null ? 0 : Math.max(0, category.value()),
                SeqTier.fromKey(category.tier()), category.tierLabel(),
                next == null ? 0 : next.at(), next == null ? null : next.label(),
                "raid:all raids".equals(category.key()),
                category.unknown() || category.value() == null, true, category.completed(),
                category.maxValue() == null ? 0 : Math.max(0, category.maxValue()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int pointerX, int pointerY, float partialTick) {
        super.render(guiGraphics, pointerX, pointerY, partialTick);
        if (state != service.state() || !shown.equals(service.progress())) {
            refresh();
            triggerImmediateNarration(true);
        }
        mouseX = MinecraftUiRenderer.mouseX(pointerX);
        mouseY = MinecraftUiRenderer.mouseY(pointerY);
        UiRenderer.renderScreen(this, this::renderScreen);
    }

    private void renderScreen(UiCanvas canvas) {
        float width = canvas.metrics().width();
        float height = canvas.metrics().height();
        PanelLayout panel = panelLayout(width, height, Float.MAX_VALUE);
        SequoiaUiStyle.drawPanelFrame(canvas, HEADER_HEIGHT);
        text(canvas, "Achievements", width - MARGIN, HEADER_HEIGHT / 2, 18, color(ACCENT_PRIMARY_HOVER), RIGHT);
        SequoiaSidebarNavigation.render(canvas, SequoiaSidebarNavigation.Destination.ACHIEVEMENTS, mouseX, mouseY);

        if (state != State.READY) {
            canvas.fillRect(panel.x(), panel.y(), panel.width(), panel.height(), color(BACKGROUND_CONTENT));
            fittedText(canvas, "Achievement progress", panel.x() + 14, panel.y() + 26,
                    Math.max(0, panel.width() - 28), 16, color(ACCENT_PRIMARY_HOVER));
            fittedText(canvas, statusLine(), panel.x() + 14, panel.y() + 54,
                    Math.max(0, panel.width() - 28), 12, color(TEXT_SECONDARY));
            return;
        }

        if (rows.isEmpty()) {
            scrollOffset = 0;
            maxScroll = 0;
            fittedText(canvas, "No achievements available", panel.x() + 14, panel.y() + 26,
                    Math.max(0, panel.width() - 28), 16, color(TEXT_SECONDARY));
            return;
        }
        maxScroll = Math.max(0, contentHeight(panel.width(), rows) - panel.height());
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        canvas.save();
        canvas.scissor(panel.x(), panel.y(), panel.width(), panel.height());
        renderProgress(canvas, rows, panel.x(), panel.y() - scrollOffset, Math.max(0, panel.width() - 6));
        canvas.restore();
        if (maxScroll > 0 && panel.height() > 0) {
            float thumb = Math.min(panel.height(), Math.max(20, panel.height() * panel.height() / (panel.height() + maxScroll)));
            float y = panel.y() + (panel.height() - thumb) * scrollOffset / maxScroll;
            canvas.fillRect(panel.right() - 3, panel.y(), 3, panel.height(), color(CONTROL_TRACK));
            canvas.fillRect(panel.right() - 3, y, 3, thumb, color(CONTROL_THUMB));
        }
        renderHoveredDetails(canvas, panel);
    }

    private void renderHoveredDetails(UiCanvas canvas, PanelLayout panel) {
        if (mouseX < panel.x() || mouseX >= panel.right() - 6 || mouseY < panel.y() || mouseY >= panel.bottom()) return;
        for (CardPlacement card : cardPlacements(Math.max(0, panel.width() - 6), rows)) {
            float x = panel.x() + card.x();
            float y = panel.y() + card.y() - scrollOffset;
            if (mouseX < x || mouseX >= x + card.width() || mouseY < y || mouseY >= y + cardHeight(card.width())) continue;
            Row row = card.row();
            List<String> lines = new ArrayList<>();
            lines.add(row.name());
            String rank = rankLabel(row);
            lines.add(rank.equalsIgnoreCase(valueLabel(row)) ? valueLabel(row) : rank + " / " + progressValueLabel(row));
            lines.add(remainingLabel(row));
            float width = 0;
            for (String line : lines) width = Math.max(width, measure(line, 12) + 20);
            width = Math.min(width, Math.max(0, canvas.metrics().width() - 16));
            // Wrap rather than ellipsize so every title and milestone remains readable.
            List<String> wrapped = new ArrayList<>();
            for (String line : lines) wrapped.addAll(wrapText(line, Math.max(1, width - 20), 12));
            float height = Math.min(wrapped.size() * 16 + 16, Math.max(0, canvas.metrics().height() - 16));
            x = Math.max(8, Math.min(mouseX + 10, canvas.metrics().width() - width - 8));
            y = Math.max(0, Math.min(mouseY + 14, canvas.metrics().height() - height));
            canvas.fillRect(x, y, width, height, color(BACKGROUND_POPUP));
            canvas.strokeRect(x, y, width, height, 1, color(CONTROL_BORDER));
            canvas.save();
            canvas.scissor(x + 1, y + 1, Math.max(0, width - 2), Math.max(0, height - 2));
            for (int i = 0; i < wrapped.size(); i++) text(canvas, wrapped.get(i), x + 10, y + 16 + i * 16,
                    12, color(i == 0 ? TEXT_PRIMARY : TEXT_SECONDARY), LEFT);
            canvas.restore();
            return;
        }
    }

    private static List<String> wrapText(String value, float width, float size) {
        List<String> lines = new ArrayList<>();
        String remaining = value;
        while (!remaining.isEmpty()) {
            int end = remaining.length();
            while (end > 1 && measure(remaining.substring(0, end), size) > width) end--;
            int space = remaining.lastIndexOf(' ', end);
            if (end < remaining.length() && space > 0) end = space;
            lines.add(remaining.substring(0, end));
            remaining = remaining.substring(end).stripLeading();
        }
        return lines;
    }

    /** Draws the same progress composition for the live screen and rendering checks. */
    static void renderProgress(UiCanvas canvas, List<Row> rows, float x, float y, float width) {
        if (rows.isEmpty() || width <= 0) return;
        long complete = rows.stream().filter(row -> !row.unknown()
                && ("event".equals(row.kind()) ? row.count() > 0 : row.completed())).count();
        fittedText(canvas, rows.size() + " achievements  /  " + complete + " complete",
                x, y + 10, width, 12, color(TEXT_MUTED));
        for (CardPlacement placement : cardPlacements(width, rows)) {
            if (placement.section() != null) {
                fittedText(canvas, placement.section(), x, y + placement.y() - 12,
                        width, 12, color(ACCENT_PRIMARY_HOVER));
            }
            renderCard(canvas, placement.row(), x + placement.x(), y + placement.y(), placement.width());
        }
    }

    static float cardHeight(float width) {
        return 108;
    }

    static int columnCount(float width) {
        return Math.max(1, Math.min(3, (int) ((width + CARD_GAP) / (232 + CARD_GAP))));
    }

    /** Contiguous sections preserve the source's row-major order, including future kinds. */
    static List<CardPlacement> cardPlacements(float width, List<Row> rows) {
        int columns = columnCount(width);
        float cardWidth = Math.max(0, (width - CARD_GAP * (columns - 1)) / columns);
        List<CardPlacement> placements = new ArrayList<>(rows.size());
        float y = SUMMARY_HEIGHT;
        int start = 0;
        while (start < rows.size()) {
            String section = sectionLabel(rows.get(start));
            int end = start + 1;
            while (end < rows.size() && section.equals(sectionLabel(rows.get(end)))) end++;
            y += SECTION_HEIGHT;
            for (int i = start; i < end; i++) {
                int index = i - start;
                placements.add(new CardPlacement(rows.get(i), index % columns * (cardWidth + CARD_GAP),
                        y + index / columns * (cardHeight(cardWidth) + CARD_GAP), cardWidth,
                        i == start ? section : null));
            }
            y += ((end - start + columns - 1) / columns) * (cardHeight(cardWidth) + CARD_GAP);
            start = end;
        }
        return List.copyOf(placements);
    }

    static float contentHeight(float width, List<Row> rows) {
        List<CardPlacement> cards = cardPlacements(Math.max(0, width - 6), rows);
        if (cards.isEmpty()) return 0;
        CardPlacement last = cards.getLast();
        return last.y() + cardHeight(last.width());
    }

    static String sectionLabel(Row row) {
        return switch (row.kind() == null ? "" : row.kind()) {
            case "raid" -> "Guild raids";
            case "war" -> "Guild wars";
            case "profession" -> "Professions";
            case "role", "badge" -> "Honours & badges";
            case "event" -> "Raiding events";
            default -> "Achievements";
        };
    }

    static PanelLayout panelLayout(float screenWidth, float screenHeight, float desiredHeight) {
        float x = SequoiaSidebarNavigation.WIDTH + MARGIN;
        float width = Math.max(0, screenWidth - x - MARGIN);
        float y = Math.min(HEADER_HEIGHT + MARGIN, Math.max(0, screenHeight - MARGIN));
        float height = Math.min(Math.max(0, screenHeight - y - MARGIN), Math.max(0, desiredHeight));
        return new PanelLayout(x, y, width, height);
    }

    @Override
    public boolean mouseClicked(@NotNull MouseButtonEvent click, boolean outsideScreen) {
        if (click.button() == 0 && SequoiaSidebarNavigation.click(
                MinecraftUiRenderer.mouseX(click.x()), MinecraftUiRenderer.mouseY(click.y()),
                MinecraftUiRenderer.screenHeight(), SequoiaSidebarNavigation.Destination.ACHIEVEMENTS, parent)) {
            return true;
        }
        return super.mouseClicked(click, outsideScreen);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        PanelLayout panel = panelLayout(MinecraftUiRenderer.screenWidth(), MinecraftUiRenderer.screenHeight(), Float.MAX_VALUE);
        float x = MinecraftUiRenderer.mouseX(mouseX);
        float y = MinecraftUiRenderer.mouseY(mouseY);
        if (x < panel.x() || x >= panel.right() || y < panel.y() || y >= panel.bottom()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scrollBy((float) -scrollY * SCROLL_SPEED);
        return true;
    }

    @Override
    public boolean keyPressed(@NotNull KeyEvent event) {
        switch (event.key()) {
            case GLFW.GLFW_KEY_UP -> scrollBy(-SCROLL_SPEED);
            case GLFW.GLFW_KEY_DOWN -> scrollBy(SCROLL_SPEED);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollBy(-pageScrollAmount());
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollBy(pageScrollAmount());
            case GLFW.GLFW_KEY_HOME -> scrollOffset = 0;
            case GLFW.GLFW_KEY_END -> scrollOffset = maxScroll;
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    private void scrollBy(float amount) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + amount));
    }

    private float pageScrollAmount() {
        return Math.max(SCROLL_SPEED, panelLayout(MinecraftUiRenderer.screenWidth(),
                MinecraftUiRenderer.screenHeight(), Float.MAX_VALUE).height() - CARD_GAP);
    }

    private String statusLine() {
        return state == State.LOADING ? "Loading your achievements..." : "Progress unavailable right now";
    }

    private static void renderCard(UiCanvas canvas, Row row, float x, float y, float width) {
        float height = cardHeight(width);
        canvas.fillRect(x, y, width, height, color(BACKGROUND_CONTENT));
        canvas.fillRect(x, y, width, 1, color(ACCENT_DIVIDER));
        float padding = 12;
        float iconSize = width < 190 ? 32 : 44;
        boolean hasArtwork = AchievementBadgeRenderer.draw(canvas, row, x + padding, y + 10, iconSize);
        float textX = x + padding + (hasArtwork ? iconSize + 10 : 0);
        float right = x + width - padding;
        float nameWidth = Math.max(0, right - textX);
        fittedText(canvas, row.name(), row.compactName(), textX, y + 22, nameWidth,
                "event".equals(row.kind()) ? 12 : 14, color(TEXT_PRIMARY));
        String rank = rankLabel(row);
        boolean duplicateRank = rank.equalsIgnoreCase(valueLabel(row));
        if (!duplicateRank) fittedText(canvas, rank, textX, y + 42, nameWidth, 11,
                row.unknown() ? color(TEXT_MUTED) : tierColor(row.tier()));

        float progressWidth = Math.max(0, width - padding * 2);
        if ("event".equals(row.kind())) {
            fittedText(canvas, "Raiding event award", x + padding, y + 65,
                    progressWidth, 11, color(TEXT_MUTED));
            if (!row.unknown() && row.count() > 0) {
                Color earned = color(CONTROL_SUCCESS);
                canvas.strokeLine(x + padding, y + 91, x + padding + 3, y + 94, 1.5f, earned);
                canvas.strokeLine(x + padding + 3, y + 94, x + padding + 9, y + 87, 1.5f, earned);
                fittedText(canvas, valueLabel(row), x + padding + 16, y + 91,
                        Math.max(0, progressWidth - 16), 11, earned);
            } else {
                fittedText(canvas, valueLabel(row), x + padding, y + 91,
                        progressWidth, 11, color(TEXT_MUTED));
            }
            return;
        }
        fittedText(canvas, progressValueLabel(row), x + padding, y + 65,
                progressWidth, 12, color(TEXT_SECONDARY));
        if (row.completed() && !row.unknown() && row.milestonesAvailable()) {
            Color complete = color(CONTROL_SUCCESS);
            canvas.strokeLine(x + padding, y + 91, x + padding + 3, y + 94, 1.5f, complete);
            canvas.strokeLine(x + padding + 3, y + 94, x + padding + 9, y + 87, 1.5f, complete);
            fittedText(canvas, "Complete", x + padding + 16, y + 91,
                    Math.max(0, progressWidth - 16), 11, complete);
            return;
        }
        // Unknown data has no quantitative track; an empty bar would imply zero progress.
        if (row.unknown() || !row.milestonesAvailable() || row.nextAt() <= 0) {
            fittedText(canvas, remainingLabel(row), x + padding, y + 91,
                    progressWidth, 10, color(TEXT_MUTED));
            return;
        }
        canvas.fillRect(x + padding, y + 77, progressWidth, 3, color(CONTROL_INPUT));
        float ratio = progressRatio(row);
        if (ratio > 0) canvas.fillRect(x + padding, y + 77, progressWidth * ratio, 3, color(ACCENT_PRIMARY));
        fittedText(canvas, remainingLabel(row), x + padding, y + 94,
                progressWidth, 11, color(TEXT_SECONDARY));
    }

    static String progressValueLabel(Row row) {
        if (row.unknown() || row.completed() || !row.milestonesAvailable() || row.nextAt() <= 0) return valueLabel(row);
        String counts = formatCount(row.count()) + " / " + formatCount(row.nextAt());
        return switch (row.kind() == null ? "" : row.kind()) {
            case "profession" -> "Level " + counts;
            case "war" -> counts + " wars";
            case "raid" -> counts + " completions";
            case "role" -> valueLabel(row) + " (" + counts + ")";
            default -> valueLabel(row);
        };
    }

    private static void fittedText(UiCanvas canvas, String value, float x, float y, float width, float size, Color textColor) {
        fittedText(canvas, value, value, x, y, width, size, textColor);
    }

    private static void fittedText(UiCanvas canvas, String value, String compact, float x, float y, float width, float size, Color textColor) {
        if (width <= 0) return;
        String shown = measure(value, size) <= width ? value : compact;
        if (measure(shown, size) > width) {
            while (!shown.isEmpty() && measure(shown + "...", size) > width) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown.isEmpty() ? "" : shown + "...";
        }
        text(canvas, shown, x, y, size, textColor, LEFT);
    }

    static float progressRatio(Row row) {
        if ("event".equals(row.kind())) return 0f;
        if (row.unknown() || !row.milestonesAvailable()) return 0f;
        if (row.completed()) return 1f;
        if (row.nextAt() <= 0) return 0f;
        return Math.min(1f, Math.max(0f, (float) row.count() / row.nextAt()));
    }

    static String rankLabel(Row row) {
        if (row.unknown()) return "Unknown";
        if (row.tierLabel() != null && !row.tierLabel().isBlank()) return row.tierLabel();
        return "Unranked";
    }

    static String valueLabel(Row row) {
        if ("event".equals(row.kind()))
            return row.unknown() ? "Status unknown" : row.count() > 0 ? "Earned" : "Not earned";
        if (row.unknown()) return "Progress unknown";
        return switch (row.kind() == null ? "" : row.kind()) {
            case "profession" -> "Level " + formatCount(row.count());
            case "war" -> formatCount(row.count()) + " wars";
            case "role" -> row.count() > 0 ? "Role held" : "No role held";
            case "badge" -> row.completed() ? "Badge earned" : "Badge not earned";
            default -> formatCount(row.count()) + " completions";
        };
    }

    static String targetLabel(Row row) {
        if ("event".equals(row.kind())) return valueLabel(row);
        if (row.unknown()) return "Progress unknown";
        if (!row.milestonesAvailable()) return "Milestones unavailable";
        if (row.completed()) return "Complete";
        return row.nextAt() > 0 ? formatCount(row.count()) + " / " + formatCount(row.nextAt())
                : "Milestones unavailable";
    }

    static String remainingLabel(Row row) {
        if ("event".equals(row.kind())) return "Raiding event award";
        if (row.unknown()) return "Awaiting source data";
        if (!row.milestonesAvailable()) return "Waiting for achievement details";
        if (row.completed()) return "All milestones complete";
        if (row.nextAt() <= 0) return "Milestones unavailable";
        String label = row.nextLabel() == null || row.nextLabel().isBlank() ? "next milestone" : row.nextLabel();
        return formatCount(Math.max(0, row.nextAt() - row.count())) + " to " + label;
    }

    static UiColor tierToken(SeqTier tier) {
        return switch (tier) {
            case BRONZE -> ACHIEVEMENT_BRONZE;
            case SILVER -> ACHIEVEMENT_SILVER;
            case GOLD -> ACHIEVEMENT_GOLD;
            case PLATINUM -> ACHIEVEMENT_PLATINUM;
            case DIAMOND -> ACHIEVEMENT_DIAMOND;
            case OBSIDIAN -> ACHIEVEMENT_OBSIDIAN;
            case MYTHRIL -> ACHIEVEMENT_MYTHRIL;
        };
    }

    static Color tierColor(SeqTier tier) {
        return tier == null ? color(TEXT_DISABLED) : color(tierToken(tier));
    }

    static String formatCount(long count) {
        return String.format(Locale.US, "%,d", count);
    }

    @Override
    public Component getNarrationMessage() {
        StringBuilder narration = new StringBuilder("Sequoia Achievements. ");
        if (state != State.READY) {
            return Component.literal(narration.append(statusLine()).toString());
        }
        if (rows.isEmpty()) narration.append("No achievements available. ");
        for (Row row : rows) {
            narration.append(row.name()).append(": ").append(valueLabel(row)).append(", ");
            if ("event".equals(row.kind())) {
                narration.append(rankLabel(row)).append(", Raiding event award. ");
                continue;
            }
            narration.append(rankLabel(row)).append(", ").append(targetLabel(row));
            narration.append(", ").append(remainingLabel(row)).append(". ");
        }
        return Component.literal(narration.toString());
    }

    @Override
    public void onClose() {
        SeqClient.mc.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void text(
            UiCanvas canvas, String value, float x, float y, float size, Color textColor, HorizontalAlign align) {
        canvas.drawText(value, x, y, new UiCanvas.TextStyle(
                SeqClient.getFontManager().getSelectedFont(), size, textColor, align, UiCanvas.VerticalAlign.MIDDLE));
    }

    private static float measure(String value, float size) {
        return UiRenderer.measureText(value, SeqClient.getFontManager().getSelectedFont(), size).width();
    }

    record Row(
            String key,
            String name,
            String compactName,
            String icon,
            String kind,
            long count,
            SeqTier tier,
            String tierLabel,
            long nextAt,
            String nextLabel,
            boolean total,
            boolean unknown,
            boolean milestonesAvailable,
            boolean completed,
            long maxValue) {}

    record CardPlacement(Row row, float x, float y, float width, String section) {}

    record PanelLayout(float x, float y, float width, float height) {
        float right() {
            return x + width;
        }

        float bottom() {
            return y + height;
        }
    }
}
