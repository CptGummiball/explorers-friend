package net.explorersfriend.command;

import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 1.21.11 replaced the numeric permission-level check with the PermissionPredicate
 * system. This shim reproduces the classic "at least op level N" semantics on top of
 * it; non-leveled custom predicates conservatively deny.
 */
public final class Perms {

    private Perms() {
    }

    public static boolean atLeast(ServerCommandSource source, int level) {
        return source.getPermissions() instanceof LeveledPermissionPredicate leveled
                && leveled.getLevel().getLevel() >= level;
    }
}
