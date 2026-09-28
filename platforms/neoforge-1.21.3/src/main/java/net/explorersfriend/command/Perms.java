package net.explorersfriend.command;

import net.minecraft.commands.CommandSourceStack;

/** 1.21.x mojmap: classic numeric permission levels. */
public final class Perms {

    private Perms() {
    }

    public static boolean atLeast(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }
}
