package messages;

import java.util.HashMap;

/**
 * Simple enum that distinguishes two types of message - data and service.
 * This enum is mainly for convenience and to prevent magic strings.
 */
public enum MessageType {
    SERVICE_MESSAGE("service"),
    DATA_MESSAGE("data");

    private final String value;

    private static final HashMap<String, MessageType> BY_LABEL = new HashMap<>();

    static {
        for (MessageType type : values()){
            BY_LABEL.put(type.value, type);
        }
    }

    MessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MessageType getLabel(String label) {
        return BY_LABEL.get(label);
    }
}
