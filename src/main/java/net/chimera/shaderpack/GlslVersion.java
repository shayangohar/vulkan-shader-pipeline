package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A stage's {@code #version} directive, with the one rule every converter applies to it.
 *
 * <p>Chimera compiles every stage as GLSL 460 for Vulkan, so the authored number only tells a
 * converter which syntax to expect. Every desktop version from 110 to 460 is accepted, in the
 * compatibility or core profile. What a program may use is decided by its features
 * (geometry and tessellation stages, compute, image load/store, storage buffers), never by the
 * number. GLSL ES is not a desktop profile and is refused. {@code 120e}, which Chimera's early
 * fixtures use, reads as 120.</p>
 */
public record GlslVersion(int number, String profile) {
    /** The rule, as converters name it in a refusal. */
    public static final String RULE = "a desktop GLSL version from 110 to 460";

    private static final Set<Integer> DESKTOP = Set.of(110, 120, 130, 140, 150, 330, 400, 410, 420, 430, 440, 450, 460);

    public GlslVersion {
        profile = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
    }

    /**
     * Every directive in the source, in order: a prepared source can carry more than one. A
     * directive is {@code #version}, in any case, then a number and an optional profile word,
     * with nothing after them but a line comment.
     */
    public static List<GlslVersion> all(String source) {
        List<GlslVersion> result = new ArrayList<>();
        for (GlslDirectives.Line line : GlslDirectives.lines(source)) {
            List<String> words = line.words();
            if (!line.keyword().equalsIgnoreCase("version") || !line.plain() || words.isEmpty() || words.size() > 2) {
                continue;
            }
            String number = words.get(0).replaceFirst("[eE]$", "");
            String profile = words.size() == 2 ? words.get(1) : null;
            boolean digits = !number.isEmpty() && number.chars().allMatch(value -> value >= '0' && value <= '9');
            if (!digits || profile != null && !profile.chars().allMatch(GlslVersion::asciiLetter)) continue;
            try {
                result.add(new GlslVersion(Integer.parseInt(number), profile));
            } catch (NumberFormatException overflow) {
                result.add(new GlslVersion(-1, profile));
            }
        }
        return result;
    }

    private static boolean asciiLetter(int value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    /** The first directive, or null when the source has none. */
    public static GlslVersion first(String source) {
        List<GlslVersion> versions = all(source);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** The highest version number among the directives, or 0 when there is none. */
    public static int highest(String source) {
        int highest = 0;
        for (GlslVersion version : all(source)) highest = Math.max(highest, version.number());
        return highest;
    }

    /** True when the source declares at least one version and every one follows the rule. */
    public static boolean declaredAndSupported(String source) {
        List<GlslVersion> versions = all(source);
        return !versions.isEmpty() && versions.stream().allMatch(GlslVersion::supported);
    }

    /** True when every declared version follows the rule; a source without one passes. */
    public static boolean supportedIfDeclared(String source) {
        return all(source).stream().allMatch(GlslVersion::supported);
    }

    /** Throws the converter's refusal unless every declared version follows the rule. */
    public static void requireSupported(String source, String what, boolean required) {
        List<GlslVersion> versions = all(source);
        if (versions.isEmpty() && required) {
            throw new IllegalArgumentException(what + " is missing a #version directive");
        }
        for (GlslVersion version : versions) {
            if (!version.supported()) {
                throw new IllegalArgumentException(what + " requires " + RULE + ", not " + version);
            }
        }
    }

    public boolean supported() {
        return DESKTOP.contains(number)
                && (profile.isEmpty() || profile.equals("core") || profile.equals("compatibility"));
    }

    /**
     * Iris's core-profile mode (TransformPatcher: OptiFine's special core profile): the core
     * profile, or 150 and above with no profile. Such a program reads vaPosition and the other
     * core inputs and matrices instead of the compatibility built-ins.
     */
    public boolean irisCore() {
        return profile.equals("core") || (number >= 150 && profile.isEmpty());
    }

    @Override
    public String toString() {
        return profile.isEmpty() ? Integer.toString(number) : number + " " + profile;
    }
}
