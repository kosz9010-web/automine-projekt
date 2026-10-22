package pl.automine;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BlockSelectScreen extends Screen {
    private static final int PER_PAGE = 10;

    private TextFieldWidget search;
    private String query = "";
    private int page = 0;
    private int maxPage = 0;
    private final List<ButtonWidget> rows = new ArrayList<>();

    public BlockSelectScreen() {
        super(Text.translatable("screen.automine.title"));
    }

    @Override
    protected void init() {
        rows.clear();
        int cx = width / 2;

        search = new TextFieldWidget(textRenderer, cx - 100, 24, 200, 20, Text.literal("search"));
        search.setPlaceholder(Text.literal("Szukaj bloku..."));
        search.setText(query);
        search.setChangedListener(s -> {
            query = s;
            page = 0;
            refreshList();
        });
        addDrawableChild(search);
        setInitialFocus(search);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            if (page > 0) page--;
            refreshList();
        }).dimensions(cx - 100, height - 28, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            if (page < maxPage) page++;
            refreshList();
        }).dimensions(cx + 60, height - 28, 40, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Gotowe"), b -> close())
                .dimensions(cx - 50, height - 28, 100, 20).build());

        refreshList();
    }

    private void refreshList() {
        for (ButtonWidget r : rows) remove(r);
        rows.clear();

        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Block> filtered = new ArrayList<>();
        for (Block b : Registries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
            String id = Registries.BLOCK.getId(b).getPath();
            if (q.isEmpty() || name.contains(q) || id.contains(q)) filtered.add(b);
        }
        filtered.sort(Comparator
                .comparing((Block b) -> !AutoMineMod.TARGETS.contains(b))
                .thenComparing(b -> b.getName().getString()));

        maxPage = Math.max(0, (filtered.size() - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));

        int cx = width / 2;
        int from = page * PER_PAGE;
        int to = Math.min(filtered.size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            Block block = filtered.get(i);
            ButtonWidget btn = ButtonWidget.builder(label(block), b -> {
                if (!AutoMineMod.TARGETS.remove(block)) AutoMineMod.TARGETS.add(block);
                AutoMineMod.save();
                b.setMessage(label(block));
            }).dimensions(cx - 100, 52 + (i - from) * 22, 200, 20).build();
            rows.add(btn);
            addDrawableChild(btn);
        }
    }

    private Text label(Block block) {
        return Text.literal(AutoMineMod.TARGETS.contains(block) ? "[x] " : "[  ] ").append(block.getName());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical < 0 && page < maxPage) page++;
        else if (vertical > 0 && page > 0) page--;
        else return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        refreshList();
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                "Strona " + (page + 1) + "/" + (maxPage + 1) + "  |  wybrane: " + AutoMineMod.TARGETS.size(),
                width / 2, height - 42, 0xAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
