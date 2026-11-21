package com.ethermod.client;

import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import com.ethermod.item.ManipulatorStage;
import com.ethermod.trait.*;
import com.google.gson.JsonElement;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Builds the coloured tooltip text for K-screen hit areas and draws the hovered one. */
final class KTooltips {
    private KTooltips() {}

    static void render(GuiGraphicsExtractor g, Font font, int screenWidth, KHit hit, int mouseX, int mouseY) {
        var lines = new ArrayList<FormattedCharSequence>();
        int wrap = Math.max(40, Math.min(228, screenWidth - 16));
        for (Component line : hit.tip()) lines.addAll(font.split(line, wrap));
        int[] bounds = {0, 0, 0};
        net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner =
                (sw, sh, x, y, w, h) -> {
                    var pos =
                            net.minecraft.client.gui.screens.inventory.tooltip
                                    .DefaultTooltipPositioner.INSTANCE
                                    .positionTooltip(sw, sh, x, y, w, h);
                    bounds[0] = pos.x();
                    bounds[1] = pos.y();
                    bounds[2] = w;
                    return pos;
                };
        g.nextStratum();
        g.tooltip(
                font,
                lines.stream()
                        .map(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent::create)
                        .toList(),
                mouseX,
                mouseY,
                positioner,
                null,
                false);
        var titleColour = hit.tip().getFirst().getStyle().getColor();
        int colour = titleColour != null ? 0xff000000 | titleColour.getValue() : hit.colour();
        // Keep the one-pixel accent inside the tooltip border, at its actual clamped position.
        if (bounds[2] > 0) {
            g.nextStratum();
            g.fill(bounds[0] - 2, bounds[1] - 3, bounds[0] + bounds[2] + 2, bounds[1] - 2, colour);
        }
    }

    static List<Component> traitTip(Identifier id, Trait trait, int tier) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.traitName(trait), c));
        out.add(rich(Lang.traitDesc(trait), c));
        return out;
    }

    static List<Component> commonTip(Identifier id, Trait trait, int common, int stage) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.traitName(trait), c));
        if (stage < common + 2)
            out.add(
                    label(
                            Lang.tr(
                                    "gui.ethermod.requires_tier",
                                    ManipulatorStage.opening(common + 1).title().getString()),
                            0xffdbad72));
        out.add(rich(Lang.traitDesc(trait), c));
        return out;
    }

    static List<Component> moduleTip(Identifier id, TraitLine line) {
        int c = module(id, p()).accent();
        var out = new ArrayList<Component>();
        out.add(label(Lang.tr("gui.ethermod.module_named", Lang.moduleName(id, line)), c));
        var all = rules.getAsJsonObject("modules");
        if (all.has(id.getPath()))
            appendLines(out, all.getAsJsonObject(id.getPath()).get("body"), c, false);
        else out.add(rich(Lang.traitDesc(line.badge()), c));
        return out;
    }

    static void appendLines(List<Component> out, JsonElement element, int c, boolean condition) {
        if (element == null) return;
        if (element.isJsonArray()) {
            for (var e : element.getAsJsonArray()) appendLines(out, e, c, condition);
        } else
            out.add(
                    condition
                            ? label(element.getAsString(), 0xffdbad72)
                            : rich(element.getAsString(), c));
    }

    static MutableComponent rich(String source, int colour) {
        if (Lang.english())
            source =
                    source.replaceAll("(?i)orb\\s+mode", "Mode")
                        .replaceAll("(?i)\\bmodule\\b", "Module")
                        .replaceAll("(?i)\\bbeam\\b", "Beam")
                        .replaceAll("(?i)\\bmode\\b", "Mode")
                        .replaceAll("(?i)\\btier\\b", "Tier")
                        .replaceAll("(?i)\\bcommon\\b", "Common");
        var root = Component.empty();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(
                                "\\[(/?)(b|u|i|when|color(?:=#[0-9A-Fa-f]{6})?)\\]|[+-]?\\d+(?:\\.\\d+)?(?:x\\d+|%|x)?|[A-Z][A-Za-z-]*"
                                    + " Modes?")
                        .matcher(source);
        int end = 0;
        Style style = Style.EMPTY.withColor(0xffcbd0d0);
        var styles = new ArrayDeque<Style>();
        while (m.find()) {
            root.append(Component.literal(source.substring(end, m.start())).withStyle(style));
            String token = m.group();
            if (token.startsWith("[")) {
                if (m.group(1).equals("/")) {
                    if (!styles.isEmpty()) style = styles.pop();
                } else {
                    styles.push(style);
                    String tag = m.group(2);
                    if (tag.equals("b")) style = style.withBold(true);
                    if (tag.equals("u")) style = style.withUnderlined(true);
                    if (tag.equals("when")) style = style.withColor(0xdbad72);
                    if (tag.startsWith("color="))
                        style = style.withColor(hex(tag.substring(6)) & 0xffffff);
                }
            } else
                root.append(
                        Component.literal(token)
                                .withStyle(
                                        style.withColor(
                                                Character.isDigit(token.charAt(0))
                                                                || token.startsWith("+")
                                                                || token.startsWith("-")
                                                        ? 0xe9bf67
                                                        : colour & 0xffffff)));
            end = m.end();
        }
        root.append(Component.literal(source.substring(end)).withStyle(style));
        return root;
    }

    static MutableComponent label(String s, int c) {
        return Component.literal(s)
                .withStyle(style -> style.withColor(c & 0xffffff).withItalic(false));
    }
}
