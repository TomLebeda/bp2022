package core;

import org.apache.commons.lang3.StringUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;

public class Logger {

    public static final boolean TRACE_ENABLED = true;
    private static LinkedList<String> message_buffer = new LinkedList<>();
    private final static String timeStampPattern = "dd.MM. HH:mm:ss.SSS";
    private final static int BUFFER_SIZE = 500;


    /**
     * List of available logging levels.
     */
    private enum Level {
        TRACE("TRACE: "),
        INFO("INFO: "),
        DEBUG("DEBUG: "),
        WARNING("WARNING: "),
        ERROR("ERROR: "),
        FATAL("FATAL: "),
        SUCCESS("SUCCESS: ");

        private final String value;

        Level(String value) {
            this.value = value;
        }
    }

    /**
     * Constructs formatted log message from given input.
     * If TRACE level is not enabled, messages of that level will not be outputted, but stored in memory.
     * When ERROR or FATAL message arrives, it will trigger "Tracing" which will output more detailed log with TRACE messages enabled.
     *
     * @param message Text of the message.
     * @param origin  Origin of the message. It describes where does the message come from.
     * @param level   Logging leve of the message.
     */
    private static void out(String message, String origin, Level level) {
        String output = timeStamp() + " - " + origin + " " + StringUtils.rightPad(level.value, 6) + message;
        if (TRACE_ENABLED) {
            // if TRACE is enabled, log out everything
            out_raw(output);
        } else {
            // else store TRACE messages and log them out only when ERROR or FATAL arrives
            message_buffer.add(output);
            if (message_buffer.size() > BUFFER_SIZE) {
                message_buffer.removeFirst();
            }
            if (level == Level.INFO || level == Level.DEBUG || level == Level.WARNING) {
                out_raw(output);
            } else if (level == Level.ERROR || level == Level.FATAL) {
                out_raw("--- TRACE ON: ---");
                for (String s : message_buffer) {
                    out_raw(s);
                }
                out_raw("--- TRACE OFF ---");
            }
        }
    }

    /**
     * Outputs given text as is, without modification. Current implementation prints input to stdout, but more complex behaviour such as logging to file is planned.
     *
     * @param string string that should be outputted to log
     */
    private static void out_raw(String string) {
        System.out.println(string);
    }


    /**
     * Creates and logs new ERROR message. Tracing will be triggered, see out().
     *
     * @param origin  Where the message comes from.
     * @param message Text of the ERROR message.
     */
    public static void error(String origin, String message) {
        out(message, origin, Level.ERROR);
    }

    /**
     * Creates and logs new WARNING message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the WARNING message.
     */
    public static void warn(String origin, String message) {
        out(message, origin, Level.WARNING);
    }

    /**
     * Creates and logs new SUCCESS message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the SUCCESS message.
     */
    public static void success(String origin, String message) {
        out(message, origin, Level.SUCCESS);
    }

    /**
     * Creates and logs new INFO message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the SUCCESS message.
     */
    public static void info(String origin, String message) {
        out(message, origin, Level.INFO);
    }

    /**
     * Creates and logs new TRACE message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the TRACE message.
     */
    public static void trace(String origin, String message) {
        out(message, origin, Level.TRACE);
    }

    /**
     * Creates and logs new FATAL message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the FATAL message.
     */
    public static void fatal(String origin, String message) {
        out(message, origin, Level.FATAL);
    }

    /**
     * Creates and logs new DEBUG message.
     *
     * @param origin  Where the message comes from.
     * @param message Text of the DEBUG message.
     */
    public static void debug(String origin, String message) {
        out(message, origin, Level.DEBUG);
    }


    /**
     * Creates and logs new DEBUG message without origin.
     *
     * @param message Text of the DEBUG message.
     */
    public static void debug(String message) {
        out(message, "", Level.DEBUG);
    }

    /**
     * Returns new formatted timeStamp with current time.
     *
     * @return timestamp string.
     */
    public static String timeStamp() {
        return new SimpleDateFormat(timeStampPattern).format(new Date());
    }
}
