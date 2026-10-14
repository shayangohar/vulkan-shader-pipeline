package net.chimera.render.shader;

/** An item node with the rendered item and block entity ids current when it was submitted. */
public interface ChimeraItemSubmission {
    int chimera$itemId();

    int chimera$blockEntityId();
}
