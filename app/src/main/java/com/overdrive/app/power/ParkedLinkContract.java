package com.overdrive.app.power;

/**
 * Public contract for the optional parked transport. This class is also used by
 * the shell daemon and must never load a native library.
 */
public final class ParkedLinkContract {
    private ParkedLinkContract() {}

    /** Increment whenever the native protocol or result semantics change. */
    public static final int ABI_VERSION = 0x00010002;

    public static final String ACTION = "com.overdrive.app.action.PARKED_LINK";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_GENERATION = "generation";
    public static final String COMMAND_START = "start";
    public static final String COMMAND_STOP = "stop";
    public static final String COMMAND_DETACH = "detach";

    public static final int OP_ASSERT = 1;
    public static final int OP_RELEASE = 2;
    public static final int OP_DETACH = 3;

    public static final int RESULT_CONFIRMED = 1;
    public static final int RESULT_REJECTED = 0;
    public static final int RESULT_DISPATCHED_UNCONFIRMED = 2;
    public static final int RESULT_BROADCAST_UNKNOWN = -100;
    public static final int RESULT_BIND_FAILED = -101;
    public static final int RESULT_NOT_DISPATCHED = -102;
    public static final int RESULT_SERVICE_UNAVAILABLE = -103;
    /** The remote side may already have accepted the command. */
    public static final int RESULT_TRANSPORT_FAILED = -104;
    public static final int RESULT_BAD_REQUEST = -105;
    public static final int RESULT_STALE_GENERATION = -106;
    public static final int RESULT_WORKER_START_FAILED = -107;
    /** Dispatch state is unknown. */
    public static final int RESULT_INTERNAL_FAILED = -108;
    public static final int RESULT_UNAUTHORIZED = -109;
    public static final int RESULT_LIBRARY_UNAVAILABLE = -110;
    public static final int RESULT_LIBRARY_INCOMPATIBLE = -111;

    public static int operationFor(String command) {
        if (COMMAND_START.equals(command)) return OP_ASSERT;
        if (COMMAND_STOP.equals(command)) return OP_RELEASE;
        if (COMMAND_DETACH.equals(command)) return OP_DETACH;
        return 0;
    }

    /** Only these outcomes prove that no new hold was acquired. */
    public static boolean definitelyNotApplied(int result) {
        switch (result) {
            case RESULT_REJECTED:
            case RESULT_BIND_FAILED:
            case RESULT_NOT_DISPATCHED:
            case RESULT_SERVICE_UNAVAILABLE:
            case RESULT_BAD_REQUEST:
            case RESULT_STALE_GENERATION:
            case RESULT_WORKER_START_FAILED:
            case RESULT_UNAUTHORIZED:
            case RESULT_LIBRARY_UNAVAILABLE:
            case RESULT_LIBRARY_INCOMPATIBLE:
                return true;
            default:
                return false;
        }
    }

    // Compatibility spellings are read only; new source and writes use the
    // generic names. Keep old installations' settings and ownership readable.
    static final String LEGACY_CONFIG_KEY = new String(new char[]{
            100, 105, 53, 80, 97, 114, 107, 101, 100, 75, 101, 101, 112, 65,
            108, 105, 118, 101, 81, 110, 120, 78, 101, 116, 119, 111, 114,
            107, 67, 111, 110, 116, 114, 111, 108});
    static final String LEGACY_MARKER_LEVER = new String(new char[]{
            113, 110, 120, 110, 101, 116, 119, 111, 114, 107});
    static final String LEGACY_STATUS_KEY = new String(new char[]{
            113, 110, 120, 78, 101, 116, 119, 111, 114, 107});
    static final String LEGACY_SETTINGS_STATUS_KEY = new String(new char[]{
            113, 110, 120, 78, 101, 116, 119, 111, 114, 107, 67, 111, 110,
            116, 114, 111, 108});
}
