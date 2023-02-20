package com.amazon.pvar.tspoc.merlin;

import org.apache.log4j.Logger;

public class DebugUtils {
    final private static Logger logger = org.apache.log4j.Logger.getRootLogger();

    public static void debug(final String str) {
        logger.debug(str);
    }

    public static void warn(final String str) {
        logger.warn(str);
    }
}
