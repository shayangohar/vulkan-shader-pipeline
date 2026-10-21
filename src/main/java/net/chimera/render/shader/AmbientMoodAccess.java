package net.chimera.render.shader;

/**
 * Iris's constantMood (CommonUniforms): the cave-sound moodiness the biome ambience handler steps
 * each mood tick, kept in [0, 1] and never reset when the mood sound plays, unlike the vanilla value
 * playerMood reads.
 */
public interface AmbientMoodAccess {
    float chimera$constantMood();
}
