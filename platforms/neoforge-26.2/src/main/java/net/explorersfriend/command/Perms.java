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
        return source.permissions() instanceof LevelBasedPermissionSet leveled
                && leveled.level().ordinal() >= level;
    }
}
