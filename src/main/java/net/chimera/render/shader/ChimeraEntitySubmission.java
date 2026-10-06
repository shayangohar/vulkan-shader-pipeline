package net.chimera.render.shader;

/** Marker and immutable identity stored on one delayed entity model submission. */
public interface ChimeraEntitySubmission {
    int FAMILY_NONE = 0;
    int FAMILY_ENTITY = 1;
    int FAMILY_BLOCK = 2;

    boolean chimera$isWorldEntity();

    int chimera$entityId();

    /** The enclosing block entity's block.properties id (Iris blockEntityId), 0 outside one. */
    int chimera$blockEntityId();

    default int chimera$family() {
        return chimera$isWorldEntity() ? FAMILY_ENTITY : FAMILY_NONE;
    }
}
