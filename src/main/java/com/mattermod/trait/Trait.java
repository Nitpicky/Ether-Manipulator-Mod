package com.mattermod.trait;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * One trait: a badge, a common node, or a choice. {@code id} is global ("excavation.fortune") and
 * is what effect code checks for.
 *
 * <p>Choices grow stronger at every stage after their tier opens (see {@link Traits#rank}): {@code
 * ranks} holds the text for rank II, then III.
 */
public record Trait(String id, String name, String description, List<String> ranks) {

    public static final Codec<Trait> CODEC =
            RecordCodecBuilder.create(
                    i ->
                            i.group(
                                            Codec.STRING.fieldOf("id").forGetter(Trait::id),
                                            Codec.STRING.fieldOf("name").forGetter(Trait::name),
                                            Codec.STRING
                                                    .optionalFieldOf("description", "")
                                                    .forGetter(Trait::description),
                                            Codec.STRING
                                                    .listOf()
                                                    .optionalFieldOf("ranks", List.of())
                                                    .forGetter(Trait::ranks))
                                    .apply(i, Trait::new));

    /** The description at a rank (1 = base), with the upgrades reached so far. */
    public String describe(int rank) {
        StringBuilder b = new StringBuilder(description);
        for (int r = 2; r <= rank && r - 2 < ranks.size(); r++)
            b.append("  ").append(r == 2 ? "Mk II: " : "Mk III: ").append(ranks.get(r - 2));
        if (rank < 3 && rank - 1 < ranks.size())
            b.append("  Next Mk: ").append(ranks.get(rank - 1));
        return b.toString();
    }
}
