package com.nextnodes.permissions;

import com.nextnodes.permissions.PermissionModels.UserEntry;

/** Pure decision: what name to show for a target player to a given viewer (packet-level over-head nick). */
public final class DisguiseName {
    private DisguiseName() {}

    /**
     * @param target        the player being rendered
     * @param viewerIsTarget true if the viewer is the target themselves (they keep seeing their real name)
     * @return the disguise name to show, or null to keep the real name.
     */
    public static String shownName(UserEntry target, boolean viewerIsTarget) {
        if (target == null || viewerIsTarget) {
            return null;
        }
        return (target.disguiseName != null && !target.disguiseName.isBlank()) ? target.disguiseName : null;
    }
}
