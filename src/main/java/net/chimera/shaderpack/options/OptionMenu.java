package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pack's option menu: a main screen and named sub-screens, each a grid of elements.
 *
 * <p>An element is {@code <empty>} (a blank cell), {@code <profile>} (the profile button, dropped
 * when the pack has no profiles), {@code [ID]} (a link to sub-screen ID), or an option name. A name
 * the pack does not declare becomes a blank cell. {@code *} stands for every option no screen places,
 * filled in after all screens are read. Without a {@code screen} entry the main screen is just
 * {@code *}. A screen without a column count uses three columns above eighteen elements, else two.
 */
public final class OptionMenu {
    /** One cell of a screen. */
    public sealed interface Element permits Empty, ProfileButton, Link, OptionEntry {}

    /** A blank cell. */
    public record Empty() implements Element {}

    /** The profile button. */
    public record ProfileButton() implements Element {}

    /** A button that opens sub-screen {@code screen}. */
    public record Link(String screen) implements Element {}

    /** An option; {@code slider} when shaders.properties lists it under {@code sliders}. */
    public record OptionEntry(ShaderOption option, boolean slider) implements Element {}

    /** A screen's elements and its explicit column count, if the pack gave one. */
    public record Screen(String id, List<Element> elements, Optional<Integer> columns) {
        public Screen {
            elements = Collections.unmodifiableList(elements);
        }

        public int columnCount() {
            return columns.orElse(elements.size() > 18 ? 3 : 2);
        }
    }

    private static final Element EMPTY = new Empty();

    private final Screen main;
    private final Map<String, Screen> subScreens;
    private final List<String> problems;

    private OptionMenu(Screen main, Map<String, Screen> subScreens, List<String> problems) {
        this.main = main;
        this.subScreens = Collections.unmodifiableMap(subScreens);
        this.problems = List.copyOf(problems);
    }

    public static OptionMenu build(MenuProperties properties, OptionSet options, PackProfiles profiles) {
        return new Assembly(options, profiles, properties).assemble();
    }

    public Screen main() {
        return main;
    }

    /** The sub-screen a link names; empty for an unknown id, which the menu shows as the main screen. */
    public Optional<Screen> subScreen(String id) {
        return Optional.ofNullable(subScreens.get(id));
    }

    public Map<String, Screen> subScreens() {
        return subScreens;
    }

    /** Elements that named no declared option. */
    public List<String> problems() {
        return problems;
    }

    /** Builds every screen, then fills each {@code *} with the options left unplaced. */
    private static final class Assembly {
        private final OptionSet options;
        private final PackProfiles profiles;
        private final MenuProperties properties;
        /** Where a {@code *} stood: the screen's element list and the index it fills from. */
        private record FillPoint(List<Element> elements, int index) {}

        private final List<String> unplaced = new ArrayList<>();
        private final List<FillPoint> fillPoints = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        Assembly(OptionSet options, PackProfiles profiles, MenuProperties properties) {
            this.options = options;
            this.profiles = profiles;
            this.properties = properties;
            unplaced.addAll(options.toggles().keySet());
            unplaced.addAll(options.choices().keySet());
        }

        OptionMenu assemble() {
            Screen main = screen(null, properties.mainScreen().orElse(List.of("*")), properties.mainColumns());
            Map<String, Screen> subScreens = new HashMap<>();
            properties.subScreens().forEach((id, entries) -> subScreens.put(id,
                    screen(id, entries, Optional.ofNullable(properties.subColumns().get(id)))));
            for (FillPoint point : fillPoints) {
                List<Element> fill = new ArrayList<>();
                for (String name : List.copyOf(unplaced)) fill.add(element(name));
                point.elements().addAll(point.index(), fill);
            }
            return new OptionMenu(main, subScreens, problems);
        }

        private Screen screen(String id, List<String> entries, Optional<Integer> columns) {
            List<Element> elements = new ArrayList<>();
            for (String entry : entries) {
                if (entry.equals("*")) {
                    fillPoints.add(new FillPoint(elements, elements.size()));
                    continue;
                }
                Element element = entry.equals("<profile>") ? (profiles.isEmpty() ? null : new ProfileButton())
                        : element(entry);
                if (element != null) elements.add(element);
            }
            return new Screen(id, elements, columns);
        }

        private Element element(String entry) {
            if (entry.equals("<empty>")) return EMPTY;
            if (entry.startsWith("[") && entry.endsWith("]") && entry.length() >= 2) {
                return new Link(entry.substring(1, entry.length() - 1));
            }
            ShaderOption option = options.isToggle(entry) ? options.toggles().get(entry).option()
                    : options.isChoice(entry) ? options.choices().get(entry).option() : null;
            if (option == null) {
                problems.add("MENU_ELEMENT_UNKNOWN:" + entry);
                return EMPTY;
            }
            unplaced.remove(entry);
            return new OptionEntry(option, properties.sliders().contains(entry));
        }
    }
}
