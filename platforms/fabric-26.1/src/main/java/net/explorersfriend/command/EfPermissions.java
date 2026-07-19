package net.explorersfriend.command;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Permission checks for every /efmap command. When a mod exposing the
 * fabric-permissions-api is installed (e.g. LuckPerms), nodes such as
 * {@code explorersfriend.command.render} decide; otherwise the classic OP-level
 * fallback applies unchanged. The API classes are referenced only from an isolated
 * holder class, so nothing is class-loaded unless the API mod is actually present.
 */
public final class EfPermissions {

    private static final boolean API_PRESENT =
            FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0");

    private EfPermissions() {
    }

    public static Predicate<CommandSourceStack> require(String node, int fallbackLevel) {
        return source -> check(source, node, fallbackLevel);
    }

    public static boolean check(CommandSourceStack source, String node, int fallbackLevel) {
        if (API_PRESENT) {
            try {
                return Api.check(source, node, fallbackLevel);
            } catch (Throwable t) {
                // incompatible API build at runtime - fall back to levels, never break commands
            }
        }
        return Perms.atLeast(source, fallbackLevel);
    }

    private static final class Api {
        static boolean check(CommandSourceStack source, String node, int fallbackLevel) {
            return me.lucko.fabric.api.permissions.v0.Permissions.check(source, node, fallbackLevel);
        }
    }
}
