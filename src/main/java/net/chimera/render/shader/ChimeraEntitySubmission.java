package net.chimera.render.shader;

/** A delayed submit node that carries the identity of the world entity that submitted it. */
public interface ChimeraEntitySubmission {
    SubmissionIdentity chimera$identity();
}
