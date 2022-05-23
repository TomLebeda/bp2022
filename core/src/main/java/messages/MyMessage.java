package messages;

import core.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import state.MessageStateMap;
import state.StateVariable;

import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents parsed and validated Message that is processed inside Core.
 */
public class MyMessage implements MessageStateMap {
    public JSONObject content = new JSONObject();

    /**
     * Returns code of the message.
     *
     * @return code of the message
     */
    public MessageCode getCode() {
        return MessageCode.getLabel(this.content.getString(MessageHeader.MESSAGE_CODE.getValue()));
    }

    /**
     * Returns origin of the message.
     *
     * @return Origin of the message. That is name of the module where the message was created in.
     */
    public String getOrigin() {
        return this.content.getString(MessageHeader.MESSAGE_ORIGIN.getValue());
    }

    /**
     * Returns ID of the message.
     *
     * @return ID of the message
     */
    public String getID() {
        return this.content.getString(MessageHeader.MESSAGE_ID.getValue());
    }

    /**
     * Returns raw content of the message in the form of JSON-valid String.
     *
     * @return string representation of the raw message
     */
    @Override
    public MyMessage getRawMsg() {
        return this;
    }

    /**
     * Finds and returns list of strings with given key if exists.
     * Throws {@link org.json.JSONException} if key does not exist.
     *
     * @param key name of the string list
     * @return list of strings under given key
     */
    @Override
    public List<String> getStringList(String key) {
        JSONArray ja = this.content.getJSONArray(key);
        ArrayList<String> out = new ArrayList<>();
        for (Object o : ja) {
            if (o instanceof String) {
                out.add((String) o);
            } else {
                Logger.warn(this.getClass().getName(), "Can not put JSON item to string array, because it is not a string. Skipping.");
            }
        }
        return out;
    }

    /**
     * Finds and returns list of numbers with given key if exists.
     * Throws {@link org.json.JSONException} if key does not exist.
     *
     * @param key name of the number list
     * @return list of numbers under given key
     */
    @Override
    public List<Double> getNumberList(String key) {
        JSONArray ja = this.content.getJSONArray(key);
        ArrayList<Double> out = new ArrayList<>();
        for (Object o : ja) {
            if (o instanceof Number) {
                out.add(((Number) o).doubleValue());
            } else {
                Logger.warn(this.getClass().getName(), "Can not put JSON item to double array, because it is not a double. Skipping.");
            }
        }
        return out;
    }

    /**
     * Finds and returns list of booleans if with given key if exists.
     * Throws {@link org.json.JSONException} if key does not exist.
     *
     * @param key name of the boolean list
     * @return list of booleans under given key
     */
    @Override
    public List<Boolean> getBooleanList(String key) {
        JSONArray ja = this.content.getJSONArray(key);
        ArrayList<Boolean> out = new ArrayList<>();
        for (Object o : ja) {
            if (o instanceof Boolean) {
                out.add((Boolean) o);
            } else {
                Logger.warn(this.getClass().getName(), "Can not put JSON item to boolean array, because it is not a boolean. Skipping.");
            }
        }
        return out;
    }

    /**
     * Returns type of the message.
     *
     * @return type of the message
     */
    public MessageType getType() {
        return MessageType.getLabel(this.content.getString(MessageHeader.MESSAGE_TYPE.getValue()));
    }

    /**
     * Parses a TextMessage from JMS to MyMessage and returns new instance of MyMessage.
     * If parsing fails, it returns null.
     *
     * @param rawMsg raw TextMessage from JMS that should be parsed into MyMessage
     * @return new instance of MyMessage or null if parsing fails.
     */
    public static MyMessage parse(TextMessage rawMsg) {
        try {
            JSONObject parsedJSON = new JSONObject(rawMsg.getText());
            if (!parsedJSON.has(MessageHeader.MESSAGE_ID.getValue())
                    || !parsedJSON.has(MessageHeader.MESSAGE_ORIGIN.getValue())
                    || !parsedJSON.has(MessageHeader.MESSAGE_TYPE.getValue())
                    || !parsedJSON.has(MessageHeader.MESSAGE_CODE.getValue())) {
                Logger.error(MyMessage.class.getName(), "Unable to parse message, missing required key(s).");
                return null;
            }
            MyMessage newMessage = new MyMessage(
                    parsedJSON.getString(MessageHeader.MESSAGE_ID.getValue()),
                    parsedJSON.getString(MessageHeader.MESSAGE_ORIGIN.getValue()),
                    MessageCode.getLabel(parsedJSON.getString(MessageHeader.MESSAGE_CODE.getValue()))
            );
            newMessage.content = parsedJSON;
            return newMessage;
        } catch (Exception e) {
            Logger.error(MyMessage.class.getName(), "Unable to parse message, parsing failed, exception: " + e.getMessage());
            return null;
        }
    }

    public MyMessage(String ID, String origin, MessageCode code) {
        this.content.put(MessageHeader.MESSAGE_ID.getValue(), ID);
        this.content.put(MessageHeader.MESSAGE_ORIGIN.getValue(), origin);
        this.content.put(MessageHeader.MESSAGE_TYPE.getValue(), code.getType().getValue());
        this.content.put(MessageHeader.MESSAGE_CODE.getValue(), code.getValue());
    }

    /**
     * Finds and returns String under given key.
     * Throws {@link org.json.JSONException} if key does not exist.
     *
     * @param key key of the String
     * @return String with given kay
     */
    @Override
    public String getString(String key) {
        return this.content.getString(key);
    }

    /**
     * Finds and returns number under given key.
     *
     * @param key key of the number
     * @return value of the number with given key
     */
    @Override
    public Double getNumber(String key) {
        return this.content.getNumber(key).doubleValue();
    }

    /**
     * Returns TRUE if boolean with given key is present and has also value TRUE.
     * Returns FALSE if boolean with given key is present and has also value FALSE.
     * Throws {@link org.json.JSONException} if key does not exit.
     *
     * @param key key of the boolean
     * @return value of the boolean with given key
     */
    @Override
    public Boolean getBool(String key) {
        return this.content.getBoolean(key);
    }

    /**
     * Returns String that represents full content of the message.
     *
     * @return content of the message in String form.
     */
    @Override
    public String fullString() {
        return this.content.toString();
    }

    /**
     * Returns reference to raw JSONObject that represents content of the message.
     *
     * @return reference to raw content of the message
     */
    @Override
    public JSONObject getContent() {
        return this.content;
    }

    /**
     * Returns TRUE if message contains given key, otherwise returns FALSE.
     *
     * @param key key of uncertain item
     * @return
     */
    @Override
    public boolean has(String key) {
        return this.content.has(key);
    }


}
