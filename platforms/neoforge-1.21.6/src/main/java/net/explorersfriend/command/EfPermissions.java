package net.explorersfriend.command;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Permission checks on NeoForge: no cross-mod permission API is bundled, so the
 * documented nodes fall back to the classic OP levels on this platform
 * (documented in MULTIPLATFORM.md). Node names stay identical for parity.
 */
public final class EfPermissions {

    private EfPermissions() {
    }

    public static Predicate<CommandSourceStack> require(String node, int fallbackLevel) {
        return source -> check(source, node, fallbackLevel);
    }

    public static boolean check(CommandSourceStack source, String node, int fallbackLevel) {
        return Perms.atLeast(source, fallbackLevel);
    }
}
