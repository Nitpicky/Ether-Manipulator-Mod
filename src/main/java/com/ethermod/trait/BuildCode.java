package com.ethermod.trait;

import com.ethermod.EtherMod;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Short shareable set codes, e.g. {@code S_1GABA2HAAA3BBBA}: the "S_" prefix, then for each filled slot its number
 * (1-3), the Module's letter, then the choice (A or B) of each tier in order. Choices stop at the
 * first tier left open, so {@code 1GA} is Gravitic in slot 1 with only its first choice.
 */
public final class BuildCode {
    private BuildCode() {}

    /** One letter per Module (the P Modules take a letter of their element). */
    public static final Map<String, Character> LETTERS =
            Map.of(
                    "plasmic", 'P',
                    "kinetic", 'K',
                    "tectonic", 'T',
                    "hydraulic", 'H',
                    "pyrotechnic", 'F',
                    "pneumatic", 'W',
                    "photonic", 'L',
                    "gravitic", 'G',
                    "botanic", 'B');

    /** One slot of a code: slot index (0-2), Module path and choices (0 = A, 1 = B), up to 3. */
    public record Slot(int slot, String module, int[] choices) {}

    public static String moduleOf(char letter) {
        char c = Character.toUpperCase(letter);
        for (var e : LETTERS.entrySet()) if (e.getValue() == c) return e.getKey();
        return null;
    }

    /** Codes are written with this prefix ("S_" for Set); it is optional when reading one. */
    public static final String PREFIX = "S_";

    /** A code with its prefix, or "" for an empty build. */
    public static String withPrefix(String body) {
        return body.isEmpty() ? "" : PREFIX + body;
    }

    /** Parses a code (prefix optional); throws IllegalArgumentException with the offending part. */
    public static List<Slot> parse(String code) {
        String s = code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length());
        List<Slot> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d < '1' || d > '3') throw new IllegalArgumentException(s.substring(i));
            if (i + 1 >= s.length()) throw new IllegalArgumentException(s.substring(i));
            String module = moduleOf(s.charAt(i + 1));
            if (module == null) throw new IllegalArgumentException(s.substring(i, i + 2));
            int j = i + 2;
            List<Integer> picks = new ArrayList<>();
            while (j < s.length() && (s.charAt(j) == 'A' || s.charAt(j) == 'B') && picks.size() < 3) {
                picks.add(s.charAt(j) == 'A' ? 0 : 1);
                j++;
            }
            int slot = d - '1';
            for (Slot o : out) if (o.slot == slot || o.module.equals(module)) throw new IllegalArgumentException(s.substring(i, j));
            out.add(new Slot(slot, module, picks.stream().mapToInt(Integer::intValue).toArray()));
            i = j;
        }
        return out;
    }

    /** "A"/"B" (any case) to 0/1, or -1. */
    public static int choice(String s) {
        if (s.equalsIgnoreCase("A")) return 0;
        if (s.equalsIgnoreCase("B")) return 1;
        return -1;
    }

    /** Longest possible code: the prefix, then three slots of digit, letter and three choices. */
    public static final int MAX_LENGTH = 17;

    /**
     * The code of a stored build (the slot list a Set or the Manipulator keeps), stored choices
     * included, with its prefix. Empty when no slot holds a known Module.
     */
    public static String encode(ListTag build, RegistryAccess access) {
        StringBuilder b = new StringBuilder();
        for (int s = 0; s < Math.min(TraitBuild.MAX_SLOTS, build.size()); s++) {
            CompoundTag slot = build.getCompound(s);
            ResourceLocation id = ResourceLocation.tryParse(slot.getString("line"));
            if (id == null || slot.getString("line").isEmpty()) continue;
            Character letter = LETTERS.get(id.getPath());
            TraitLine line = TraitLines.get(access, id).orElse(null);
            if (letter == null || line == null) continue;
            b.append(s + 1).append(letter);
            ListTag picks = slot.getList("picks", Tag.TAG_STRING);
            for (int t = 0; t < Math.min(TraitLine.TIERS, picks.size()); t++) {
                String pick = picks.getString(t);
                int choice = -1;
                for (int k = 0; k < 2; k++)
                    if (!pick.isEmpty() && line.tier(t).get(k).id().equals(pick)) choice = k;
                if (choice < 0) break;
                b.append(choice == 0 ? 'A' : 'B');
            }
        }
        return withPrefix(b.toString());
    }

    /**
     * A code turned into a stored build (three slots, like a saved Set). Throws
     * IllegalArgumentException when the code is malformed, empty or names a Module this world
     * doesn't have.
     */
    public static ListTag decode(String code, RegistryAccess access) {
        if (code == null || code.length() > MAX_LENGTH * 2) throw new IllegalArgumentException("length");
        List<Slot> slots = parse(code);
        if (slots.isEmpty()) throw new IllegalArgumentException("empty");
        ListTag build = new ListTag();
        for (int s = 0; s < TraitBuild.MAX_SLOTS; s++) {
            CompoundTag e = new CompoundTag();
            e.putString("line", "");
            e.put("picks", new ListTag());
            build.add(e);
        }
        for (Slot slot : slots) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, slot.module());
            TraitLine line =
                    TraitLines.get(access, id).orElseThrow(() -> new IllegalArgumentException(slot.module()));
            ListTag picks = new ListTag();
            for (int t = 0; t < TraitLine.TIERS; t++)
                picks.add(
                        StringTag.valueOf(
                                t < slot.choices().length ? line.tier(t).get(slot.choices()[t]).id() : ""));
            CompoundTag e = build.getCompound(slot.slot());
            e.putString("line", id.toString());
            e.put("picks", picks);
        }
        return build;
    }

    /** The Modules a stored build uses, for unlock checks. */
    public static List<ResourceLocation> modules(ListTag build) {
        List<ResourceLocation> out = new ArrayList<>();
        for (int s = 0; s < build.size(); s++) {
            String line = build.getCompound(s).getString("line");
            ResourceLocation id = line.isEmpty() ? null : ResourceLocation.tryParse(line);
            if (id != null) out.add(id);
        }
        return out;
    }
}
