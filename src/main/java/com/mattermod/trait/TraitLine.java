package com.mattermod.trait;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * A Guild Wars 2 style trait line, loaded from datapacks
 * (data/&lt;namespace&gt;/mattermod/trait_line/&lt;id&gt;.json) and synced to clients.
 *
 * <p>Layout: badge (active when equipped) → Adept choice (1 of 2) → common → Master choice → common
 * → Grandmaster choice.
 */
public record TraitLine(
        String name,
        String description,
        int color,
        Item icon,
        int order,
        Trait badge,
        List<Trait> commons,
        List<Trait> adept,
        List<Trait> master,
        List<Trait> grandmaster) {

    public static final int TIERS = 3;

    private static final Codec<Integer> HEX_COLOR =
            Codec.STRING.comapFlatMap(
                    s -> {
                        try {
                            return DataResult.success(
                                    Integer.parseUnsignedInt(
                                                    s.startsWith("#") ? s.substring(1) : s, 16)
                                            & 0xFFFFFF);
                        } catch (NumberFormatException e) {
                            return DataResult.error(() -> "Bad colour: " + s);
                        }
                    },
                    c -> String.format("#%06X", c));

    private static Codec<List<Trait>> exactly(int n) {
        return Trait.CODEC
                .listOf()
                .validate(
                        l ->
                                l.size() == n
                                        ? DataResult.success(l)
                                        : DataResult.error(
                                                () ->
                                                        "Expected "
                                                                + n
                                                                + " traits, got "
                                                                + l.size()));
    }

    public static final Codec<TraitLine> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.STRING.fieldOf("name").forGetter(TraitLine::name),
                                            Codec.STRING
                                                    .optionalFieldOf("description", "")
                                                    .forGetter(TraitLine::description),
                                            HEX_COLOR
                                                    .optionalFieldOf("color", 0xFFFFFF)
                                                    .forGetter(TraitLine::color),
                                            BuiltInRegistries.ITEM
                                                    .byNameCodec()
                                                    .fieldOf("icon")
                                                    .forGetter(TraitLine::icon),
                                            Codec.INT
                                                    .optionalFieldOf("order", 100)
                                                    .forGetter(TraitLine::order),
                                            Trait.CODEC
                                                    .fieldOf("badge")
                                                    .forGetter(TraitLine::badge),
                                            exactly(2)
                                                    .fieldOf("commons")
                                                    .forGetter(TraitLine::commons),
                                            exactly(2).fieldOf("adept").forGetter(TraitLine::adept),
                                            exactly(2)
                                                    .fieldOf("master")
                                                    .forGetter(TraitLine::master),
                                            exactly(2)
                                                    .fieldOf("grandmaster")
                                                    .forGetter(TraitLine::grandmaster))
                                    .apply(i, TraitLine::new));

    public List<Trait> tier(int tier) {
        return switch (tier) {
            case 0 -> adept;
            case 1 -> master;
            default -> grandmaster;
        };
    }
}
