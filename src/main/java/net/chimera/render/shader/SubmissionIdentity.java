package net.chimera.render.shader;

/**
 * Who submitted a delayed world node: whether a world entity did, its Iris entityId, the
 * enclosing block entity's blockEntityId (0 outside one), and the family it draws with.
 */
public record SubmissionIdentity(boolean worldEntity, int entityId, int blockEntityId, int family) {
    public static final int FAMILY_NONE = 0;
    public static final int FAMILY_ENTITY = 1;
    public static final int FAMILY_BLOCK = 2;

    /** A node no world entity submitted; its ids are never read. */
    public static final SubmissionIdentity NONE = new SubmissionIdentity(false, 0, 0, FAMILY_NONE);

    /** The identity of whatever ChimeraEntityBridge is submitting now. */
    public static SubmissionIdentity current() {
        if (!ChimeraEntityBridge.isSubmittingEntity()) return NONE;
        return new SubmissionIdentity(true, ChimeraEntityBridge.currentEntityId(),
                ChimeraEntityBridge.currentBlockEntityId(), ChimeraEntityBridge.currentSubmissionFamily());
    }

    /** The identity a world entity left on a submit node, or null for any other node. */
    public static SubmissionIdentity ofWorldNode(Object node) {
        if (!(node instanceof ChimeraEntitySubmission submission)) return null;
        SubmissionIdentity identity = submission.chimera$identity();
        return identity != null && identity.worldEntity() ? identity : null;
    }

    /** The family the node draws with: its block entity's block program, else gbuffers_entities. */
    public ChimeraEntityBridge.Family baseFamily() {
        return family == FAMILY_BLOCK ? ChimeraEntityBridge.Family.BLOCK : ChimeraEntityBridge.Family.ENTITY;
    }
}
