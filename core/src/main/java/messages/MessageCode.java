package messages;

import java.util.HashMap;

/**
 * Simple representation of all known message codes that are implemented.
 * Main purpose is for convenience to avoid magical strings all over the code.
 * Also stores information about what code is assigned to which {@link MessageType} (Data or Service).
 */
public enum MessageCode {

    WHO("who", MessageType.SERVICE_MESSAGE),
    HERE("here", MessageType.SERVICE_MESSAGE),
    DIE("die", MessageType.SERVICE_MESSAGE),
    DYING("dying", MessageType.SERVICE_MESSAGE),
    DEAD("dead", MessageType.SERVICE_MESSAGE),
    CONFIRM("confirm", MessageType.SERVICE_MESSAGE),

    FETCH("fetch", MessageType.SERVICE_MESSAGE),
    STORE("store", MessageType.SERVICE_MESSAGE),
    START_LISTENING("start_listening", MessageType.SERVICE_MESSAGE),
    STOP_LISTENING("stop_listening", MessageType.SERVICE_MESSAGE),
    MARK_AS_READ("mark_as_read", MessageType.SERVICE_MESSAGE),
    FORCE_CHECK("force_check", MessageType.SERVICE_MESSAGE),
    DIALOG_READY("dialog_ready", MessageType.DATA_MESSAGE),

    TTS_DONE("tts_done", MessageType.DATA_MESSAGE),
    NEW_RSS("new_rss", MessageType.DATA_MESSAGE),
    EMAIL("email", MessageType.DATA_MESSAGE),
    REPORT("report", MessageType.DATA_MESSAGE),
    UNREAD_EMAILS("unread_emails", MessageType.DATA_MESSAGE),
    SPEECH("speech", MessageType.DATA_MESSAGE),
    SYNTHESIZE("synth", MessageType.DATA_MESSAGE),
    SET_STATE("set_state", MessageType.DATA_MESSAGE),
    DELETE_STATE("del_state", MessageType.DATA_MESSAGE),
    OPEN_BROWSER("open_browser", MessageType.DATA_MESSAGE),
    OPEN_FILE_EXPLORER("open_file_explorer", MessageType.DATA_MESSAGE),
    DUMMY("dummy", MessageType.DATA_MESSAGE),
    NOTIFY("notify", MessageType.DATA_MESSAGE);

    private final String value;
    private final MessageType type;
    private static final HashMap<String, MessageCode> BY_LABEL = new HashMap<>();

    static {
        for (MessageCode code : values()) {
            BY_LABEL.put(code.value, code);
        }
    }

    MessageCode(String value, MessageType type) {
        this.value = value;
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public static MessageCode getLabel(String label) {
        return BY_LABEL.get(label);
    }

    public MessageType getType() {
        return this.type;
    }

}
