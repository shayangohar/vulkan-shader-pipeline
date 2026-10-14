package net.chimera.render;

import java.nio.file.Path;

/**
 * The pack change an in-game command requested, applied at the next safe frame boundary. A newer
 * request replaces an older one, and asking for the pack already active or already pending does
 * nothing unless the request forces a reload.
 */
public final class PackChangeQueue {
    /** A complete pack replacement requested by an in-game command. */
    record PendingPackChange(Path path, boolean forceReload) {}

    private PendingPackChange pending;

    public boolean request(Path activePath, Path requestedPath, boolean forceReload) {
        if (!forceReload && pending == null && samePath(activePath, requestedPath)) {
            return false;
        }
        if (!forceReload && pending != null && samePath(pending.path(), requestedPath)) {
            return false;
        }
        pending = new PendingPackChange(requestedPath, forceReload);
        return true;
    }

    private static boolean samePath(Path first, Path second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    PendingPackChange pending() {
        return pending;
    }

    public boolean hasPending() {
        return pending != null;
    }

    public Path pendingPath() {
        return pending == null ? null : pending.path();
    }

    public boolean pendingForced() {
        return pending != null && pending.forceReload();
    }

    public void clear() {
        pending = null;
    }
}
