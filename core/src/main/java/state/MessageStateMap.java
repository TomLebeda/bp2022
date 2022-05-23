package state;

import messages.MessageCode;
import messages.MyMessage;
import org.json.JSONObject;

import java.util.List;

/**
 * Simple interface that is used inside Rule and provides method for getting all types of attributes from a message and one method for getting whole message content as a JSONObject.
 */
public interface MessageStateMap {
    String getString(String key);

    Double getNumber(String key);

    Boolean getBool(String key);

    MessageCode getCode();

    String fullString();

    JSONObject getContent();

    boolean has(String key);

    default String getOrigin() {
        return getString("msgOrigin");
    }

    default String getID() {
        return getString("msgID");
    }

    MyMessage getRawMsg();

    List<String> getStringList(String key);

    List<Double> getNumberList(String key);

    List<Boolean> getBooleanList(String key);
}
