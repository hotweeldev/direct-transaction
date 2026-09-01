package id.co.bni.direct.transaction.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The response timestamp format shared with the Spine services:
 * {@code yyyy-MM-dd HH:mm:ss}. Every date field on the wire is a string in that shape, not
 * an ISO-8601 instant, and the MFEs parse it as such.
 *
 * <p><b>Local, not UTC.</b> A string with no offset beside it is read as a wall clock by
 * everything downstream. Mixing a UTC stamp into a list of local ones shows the event
 * hours in the past. Moving to offsets on the wire is a contract change, not a formatting
 * one - do it across all services at once or not at all.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Timestamps() {
    }

    /** Null-safe format; returns {@code null} for a null input. */
    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMAT);
    }

    /** Now, as the wire format. */
    public static String now() {
        return LocalDateTime.now().format(FORMAT);
    }
}
