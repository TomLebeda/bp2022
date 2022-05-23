package messages;

import java.util.HashMap;

/**
 * Represents all known and implemented heads that are used in JSON format in text messages that are used for communication between modules.
 * Stores information for both types of messages.
 * Main purpose is to avoid using magical strings all over the code.
 */
public enum MessageHeader {
    ACTIONS("actions"),
    AUTHOR("author"),
    AUTHOR_ADDRESS("authorAddress"),
    CHECKSUM("checksum"),
    CONTENT("content"),
    DESCRIPTION("description"),
    EXPIRATION("expiration"),
    FILTER("filter"),
    FILTER_TYPE("filterType"),
    LINK("link"),
    LISTENING_ON("listeningOn"),
    ON_EXPIRE("onExpire"),
    MESSAGE_CODE("msgCode"), // code of this message, see MessageCode
    MESSAGE_ID("msgID"), // ID of the message, UUID format
    MESSAGE_ORIGIN("msgOrigin"), // ID of the module that produced this message
    MESSAGE_TYPE("msgType"), // type of this message, see messages.MyMessage.Type
    PRIORITY("priority"), // priority of this message, higher number means higher priority
    PUBLISH_TIME("publishTime"),
    REQUIRE_CONFIRMATION("reqconf"),
    REQUEST_TYPE("requestType"),
    RESPONSE_ID("respID"),
    STATE_VARS("stateVars"),
    TIMESTAMP("timestamp"),
    MEANINGS("meanings"),
    TITLE("title");

    private final String value;
    private static final HashMap<String, MessageHeader> BY_LABEL = new HashMap<>();

    static {
        for (MessageHeader key : values()){
            BY_LABEL.put(key.value, key);
        }
    }

    public static MessageHeader getLabel(String label) {
        return BY_LABEL.get(label);
    }

    MessageHeader(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
