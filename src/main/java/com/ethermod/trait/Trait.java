package com.ethermod.trait;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * One trait: a badge, a common node, or a choice. {@code id} is global ("tectonic.fortune") and
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
}
