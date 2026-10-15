package net.chimera.shaderpack.options;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The pack's own translations: {@code lang/*.lang} files beside shaders.properties, read as UTF-8
 * properties and keyed by the lower-cased file name ({@code en_US.lang} serves {@code en_us}). The
 * menu looks a key up in the player's language first, then in English.
 */
public final class PackLanguage {
    private static final String FALLBACK = "en_us";

    private final Map<String, Map<String, String>> languages;

    private PackLanguage(Map<String, Map<String, String>> languages) {
        this.languages = Collections.unmodifiableMap(languages);
    }

    public static PackLanguage empty() {
        return new PackLanguage(Map.of());
    }

    /** Reads the {@code lang} folder under {@code shadersRoot}; files that fail to parse are skipped. */
    public static PackLanguage read(Path shadersRoot) {
        Path folder = shadersRoot.resolve("lang");
        Map<String, Map<String, String>> languages = new HashMap<>();
        if (!Files.isDirectory(folder)) return new PackLanguage(languages);
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".lang")) return;
                Properties entries = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    entries.load(reader);
                } catch (IOException | IllegalArgumentException malformed) {
                    return;
                }
                Map<String, String> map = new HashMap<>();
                entries.forEach((key, value) -> map.put(key.toString(), value.toString()));
                languages.put(name.substring(0, name.lastIndexOf('.')), Collections.unmodifiableMap(map));
            });
        } catch (IOException unreadable) {
            return new PackLanguage(languages);
        }
        return new PackLanguage(languages);
    }

    /** {@code key} in {@code language} (a Minecraft code like {@code de_de}), else in English. */
    public Optional<String> translate(String language, String key) {
        String code = language == null ? FALLBACK : language.toLowerCase(Locale.ROOT);
        String value = entry(code, key);
        return Optional.ofNullable(value != null ? value : entry(FALLBACK, key));
    }

    public Set<String> languages() {
        return languages.keySet();
    }

    private String entry(String language, String key) {
        Map<String, String> entries = languages.get(language);
        return entries == null ? null : entries.get(key);
    }
}
