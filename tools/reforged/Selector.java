package reforged;

/**
 * A Mixin target selector: {@code Lowner;name(desc)}, {@code name(desc)}, {@code name:desc} or a bare name. A null
 * part matches anything, and {@code *} in the name matches any run of characters.
 */
record Selector(String owner, String name, String descriptor) {
    static Selector parse(String text) {
        String rest = text.trim();
        String owner = null;
        int semicolon = rest.indexOf(';');
        int paren = rest.indexOf('(');
        int colon = rest.indexOf(':');
        if (rest.startsWith("L") && semicolon > 0 && (paren < 0 || semicolon < paren)
                && (colon < 0 || semicolon < colon)) {
            owner = rest.substring(1, semicolon);
            rest = rest.substring(semicolon + 1);
            paren = rest.indexOf('(');
            colon = rest.indexOf(':');
        }
        String name = rest;
        String descriptor = null;
        if (paren >= 0) {
            name = rest.substring(0, paren);
            descriptor = rest.substring(paren);
        } else if (colon >= 0) {
            name = rest.substring(0, colon);
            descriptor = rest.substring(colon + 1);
        }
        return new Selector(owner, name.isEmpty() ? null : name, descriptor);
    }

    /** Whether the selector is meant to match several methods: a wildcard or a pattern name. */
    boolean wildcard() {
        return name != null && (name.contains("*") || name.contains("{"));
    }

    boolean nameMatches(String candidate) {
        if (name == null) return true;
        if (name.contains("*")) return candidate.matches(name.replace("$", "\\$").replace("*", ".*"));
        return name.equals(candidate);
    }

    boolean matches(String memberOwner, String memberName, String memberDescriptor) {
        if (owner != null && !owner.equals(memberOwner)) return false;
        return nameMatches(memberName) && (descriptor == null || descriptor.equals(memberDescriptor));
    }
}
