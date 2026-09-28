package net.explorersfriend.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * 26.x replaced numeric permission levels with PermissionSets. This shim reproduces
 * the classic "at least op level N" semantics; non-leveled custom sets deny.
 */
public final class Perms {

    private Perms() {
    }

    public static boolean atLeast(CommandSourceStack source, int level) {
        var perms = source.permissions();
        if (perms instanceof LevelBasedPermissionSet leveled) {
            return leveled.level().ordinal() >= level;
        }
        // Console, RCON and functions carry ALL_PERMISSIONS (not level-based).
        return perms == net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS;
    }
}
