package net.chimera.render.shader;

/**
 * The camera position packs see. Like Iris, the origin moves by whole multiples of 30,000 blocks
 * once the camera walks past that range or teleports, so pack-facing coordinates stay small
 * enough for float precision. The raw values keep the world position for block and biome lookups.
 */
final class CameraOrigin {
    double rawX;
    double rawY;
    double rawZ;
    double previousRawX;
    double previousRawY;
    double previousRawZ;
    float x;
    float y;
    float z;
    float previousX;
    float previousY;
    float previousZ;
    double shiftX;
    double shiftZ;
    boolean seeded;

    void advance(double nextX, double nextY, double nextZ) {
        previousRawX = rawX;
        previousRawY = rawY;
        previousRawZ = rawZ;
        rawX = nextX;
        rawY = nextY;
        rawZ = nextZ;
        double currentShiftedX = rawX + shiftX;
        double currentShiftedZ = rawZ + shiftZ;
        double previousShiftedX = previousRawX + shiftX;
        double previousShiftedZ = previousRawZ + shiftZ;
        if (!seeded) {
            previousRawX = rawX;
            previousRawY = rawY;
            previousRawZ = rawZ;
            previousShiftedX = currentShiftedX;
            previousShiftedZ = currentShiftedZ;
            seeded = true;
        }
        double deltaX = PackFrameState.cameraShift(currentShiftedX, previousShiftedX);
        double deltaZ = PackFrameState.cameraShift(currentShiftedZ, previousShiftedZ);
        shiftX += deltaX;
        shiftZ += deltaZ;
        x = (float) (rawX + shiftX);
        y = (float) rawY;
        z = (float) (rawZ + shiftZ);
        previousX = (float) (previousRawX + shiftX);
        previousY = (float) previousRawY;
        previousZ = (float) (previousRawZ + shiftZ);
    }

    void reset() {
        rawX = rawY = rawZ = previousRawX = previousRawY = previousRawZ = 0.0;
        x = y = z = previousX = previousY = previousZ = 0.0f;
        shiftX = shiftZ = 0.0;
        seeded = false;
    }

    void seedPreviousFromCurrent() {
        previousRawX = rawX;
        previousRawY = rawY;
        previousRawZ = rawZ;
        previousX = x;
        previousY = y;
        previousZ = z;
    }
}
