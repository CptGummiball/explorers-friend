package net.explorersfriend.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared logger for every module. Lives in {@code common} so core code never
 * needs the platform entry-point class; platform modules reuse the same instance.
 */
public final class Log {

    public static final Logger LOGGER = LoggerFactory.getLogger("ExplorersFriend");

    private Log() {
    }
}
