package net.chimera.render.shader;

/** Marker and immutable identity stored on one delayed entity model submission. */
public interface ChimeraEntitySubmission {
    boolean chimera$isWorldEntity();

    int chimera$entityId();
}
