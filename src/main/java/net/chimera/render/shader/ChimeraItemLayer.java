package net.chimera.render.shader;

/** An item layer tagged with the Iris item id of the stack it was resolved for. */
public interface ChimeraItemLayer {
    void chimera$tagItem(int itemId, boolean blockItem);
}
