package messages;

import com.sun.jdi.InvalidTypeException;
import core.*;
import core.Module;
import org.json.JSONArray;
import org.json.JSONObject;
import rules.*;
import state.StateVault;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This class handles incoming messages received from {@link MQClient} and processes them according to their type and content.
 */
public class MessageProcessor {
    private RuleManager ruleManager;
    private MQClient mqClient;
    private Core core;
    private StateVault coreState;

    /**
     * Sets references to other submodules that are needed in other methods.
     *
     * @param core        reference to Core instance
     * @param mqClient    reference to message client MQClient instance
     * @param ruleManager reference to RuleManage instance
     * @param coreState   reference to StateVault instance
     */
    public void link(Core core, MQClient mqClient, RuleManager ruleManager, StateVault coreState) {
        this.ruleManager = ruleManager;
        this.mqClient = mqClient;
        this.core = core;
        this.coreState = coreState;
    }


    /**
     * This method accepts service messages and processes them according to their code.
     *
     * @param msg service message to process
     */
    private void processServiceMessage(MyMessage msg) {
        Logger.trace(this.getClass().getName(), "Processing service message: " + msg.content.toString());
        switch (msg.getCode()) {
            // code HERE means that some module is telling that it is alive and wants to be acknowledged
            case HERE -> {
                Logger.trace(this.getClass().getName(), "Received service message with code \"here\", from module \"" + msg.getOrigin() + "\". Initiating enroll protocol.");
                // construct new message that will be the response
                MyMessage confMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.CONFIRM);
                // set the content of the message to be a valid acknowledgment response
                confMsg.content.put(MessageHeader.RESPONSE_ID.getValue(), msg.getID());
                // send the response to the same destination that the module is listening on
                mqClient.sendMessage(MessageDestinationType.QUEUE, msg.content.getString(MessageHeader.LISTENING_ON.getValue()), confMsg);
                if (msg.has(MessageHeader.STATE_VARS.getValue())) {
                    JSONArray ja = msg.getContent().getJSONArray(MessageHeader.STATE_VARS.getValue());
                    for (int i = 0; i < ja.length(); i++) {
                        JSONObject jo = ja.getJSONObject(i);
                        if (jo.has("name") && jo.has("type") && jo.has("value")) {
                            String varName = jo.getString("name");
                            boolean expires = jo.has("expirationPeriod") && jo.has("expiredValue");
                            long expirationPeriod = -1;
                            if (expires) {
                                expirationPeriod = jo.getNumber("expirationPeriod").longValue();
                            }
                            if (jo.getString("type").equals("boolean")) {
                                boolean varValue = jo.getBoolean("value");
                                if (expires) {
                                    boolean expiredValue = jo.getBoolean("expiredValue");
                                    coreState.setState(varName, msg.getOrigin(), varValue, expirationPeriod, expiredValue);
                                } else {
                                    coreState.setState(varName, msg.getOrigin(), varValue);
                                }
                            } else if (jo.getString("type").equals("string")) {
                                String varValue = jo.getString("value");
                                if (expires) {
                                    String expiredValue = jo.getString("expiredValue");
                                    coreState.setState(varName, msg.getOrigin(), varValue, expirationPeriod, expiredValue);
                                } else {
                                    coreState.setState(varName, msg.getOrigin(), varValue);
                                }
                            } else if (jo.getString("type").equals("number")) {
                                double varValue = jo.getNumber("value").doubleValue();
                                if (expires) {
                                    double expiredValue = jo.getNumber("expiredValue").doubleValue();
                                    coreState.setState(varName, msg.getOrigin(), varValue, expirationPeriod, expiredValue);
                                } else {
                                    coreState.setState(varName, msg.getOrigin(), varValue);
                                }
                            } else {
                                Logger.warn(this.getClass().getName(), "Unable to create state variable, type was not recognized. " +
                                        "Received type:" + jo.getString("type"));
                            }
                        } else {
                            Logger.warn(this.getClass().getName(), "Received HERE message with state variables, " +
                                    "but there are missing headers \"name\" or \"type\" or \"value\". Ignoring this variable.");
                        }
                    }
                }
                try {
                    List<String> living_modules = coreState.getStringList("living_modules");
                    // don't add modules that are already there
                    if (!living_modules.contains(msg.getOrigin())) {
                        living_modules.add(msg.getOrigin());
//                            coreState.setState("living_modules", Core.ID, living_modules);
                        Logger.trace(this.getClass().getName(), "Living modules state updated, added " + msg.getOrigin());
                        Logger.debug("LIVING MODULES: " + coreState.getStringList("living_modules"));
                    }
                } catch (InvalidTypeException e) {
                    Logger.error(this.getClass().getName(), "Failed get living_modules state: " + e.getMessage());
                }
                // set variable that keeps module status "alive" for broadcast period + 5 sec
                coreState.setState("module_verified_" + msg.getOrigin(), Core.ID, true, MQClient.CONNECTION_CHECK_PERIOD + 5000, false);
            }
            // code DYING means that some module is dying and wants to inform Core about that fact
            case DYING -> {
                Logger.trace(this.getClass().getName(), "Received service message with code \"dying\" from module with ID " + msg.content.getString(MessageHeader.MESSAGE_ORIGIN.getValue()));
                // remove all state variables that are maintained by the dying module
                coreState.removeAllFromModule(msg.content.getString(MessageHeader.MESSAGE_ORIGIN.getValue()));
                // construct a new message that will broadcast the death notice to all modules
                MyMessage deathNotice = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.DEAD);
                // set the content of the new message so that it is a death notice
                deathNotice.content.put(MessageHeader.CONTENT.getValue(), msg.content.getString(MessageHeader.MESSAGE_ORIGIN.getValue()));
                // broadcast the death-notice-message
                mqClient.sendMessage(MessageDestinationType.TOPIC, "broadcast", deathNotice);
                try {
                    List<String> livingModules = coreState.getStringList("living_modules");
                    if (livingModules.contains(msg.getOrigin())) {
                        livingModules.remove(msg.getOrigin());
                        Logger.trace(this.getClass().getName(), "Removed dying module from living_modules list: " + msg.getOrigin());
//                        TODO: more dialog managers will require better switching mechanism
                        if (msg.getOrigin().equals("speech_module")) {
                            // if the speech module dies
                        }
                        Logger.debug("LIVING MODULES: " + coreState.getStringList("living_modules"));
                    } else {
                        Logger.trace(this.getClass().getName(), "Dying module is not listed in living_modules state:" + msg.getOrigin());
                    }
                } catch (Exception e) {
                    Logger.error(this.getClass().getName(), "Failed to get living_modules state to delete dying module: " + e.getMessage());
                }
            }
            default ->
                    Logger.warn(this.getClass().getName(), "Received service message with code \"" + msg.getCode() + "\", but there is nothing to do with it. This message will be ignored.");
        }
    }

    /**
     * This method accepts data message and processes it.
     * Processing data message means applying rules to it one by one, until there is terminal rule output or all rules are applied.
     *
     * @param msg message to process
     */
    public void processDataMessage(MyMessage msg) {
        Logger.trace(this.getClass().getName(), "Processing data message: " + msg.content.toString());
        ArrayList<Rule> rules = ruleManager.getRelevantRules(msg); // load all relevant rules
        boolean catched = false;
        // for every rule:
        for (Rule rule : rules) {
            try {
                // apply the rule to the message
                for (RuleOutput ruleOutput : rule.apply(msg)) {
                    // if there is message in a rule output, send it to the given destination
                    catched = true;
                    if (ruleOutput.msg != null) {
                        if (ruleOutput.destination != null) {
                            if (ruleOutput.expiration > 0) {
                                mqClient.sendMessage(ruleOutput.destType, ruleOutput.destination, ruleOutput.expiration, ruleOutput.msg);
                            } else {
                                mqClient.sendMessage(ruleOutput.destType, ruleOutput.destination, ruleOutput.msg);
                            }
                        } else {
                            Logger.error(this.getClass().getName(), "Cannot use rule output because it has no message (ruleOutput.msg is null), ignoring.");
                        }
                    }
                    // if the output is terminal, stop the iteration, no other rules will be applied after that
                    if (ruleOutput.terminal) {
                        Logger.trace(this.getClass().getName(), "Received terminate signal from rule output, terminating further rule application.");
                        return;
                    }
                }
            } catch (Exception e) {
                Logger.error(this.getClass().getName(), "Rule failed: " + e.getMessage());
            }
        }
        if (!catched) {
            Logger.warn(this.getClass().getName(), "Message " + msg.getID() + " fallen through (no output was produced).");
        }
    }

    /**
     * This method will take raw Message object from JMS specification and digest it.
     * The message needs to be TextMessage, any other type will log error and be ignored.
     * <p>
     * If the message is TextMessage, its content is extracted and parsed into {@link MyMessage}.
     * If the content is not a valid JSON format, parsing will fail, error will be logged and message will be ignored.
     * <p>
     * The process of digestion also includes detecting whether it is data message or service message and appropriate method is called.
     *
     * @param rawMsg raw Message from JMS specification that was received by MessageListener
     */
    public void digest(Message rawMsg) {
        // Only text messages are supported
        if (!(rawMsg instanceof TextMessage)) {
            Logger.error(this.getClass().getName(), "Can not digest message, because it is not a text message. Ignoring this message. Message:" + rawMsg.toString());
            return;
        }

        // parse raw text message to custom format
        MyMessage msg = MyMessage.parse((TextMessage) rawMsg);
        if (msg == null) {
            try {
                Logger.error(this.getClass().getName(), "Failed to process message:" + ((TextMessage) rawMsg).getText() + ", because parsing failed (returned null).");
            } catch (JMSException e) {
                Logger.error(this.getClass().getName(), "Failed to process message, because parsing failed (returned null). Unable to extract message text.");
            }
            return;
        }

        // don't react to core's own messages
        if (msg.content.getString("msgOrigin").equals(Core.ID) && msg.getCode() == MessageCode.WHO) {
            Logger.trace(this.getClass().getName(), "Ignoring my own WHO message: " + msg.content.toString());
            return;
        }

        // process the message according to its type
        try {
            if (msg.getType().equals(MessageType.SERVICE_MESSAGE)) {
                processServiceMessage(msg);
            } else if (msg.getType().equals(MessageType.DATA_MESSAGE)) {
                processDataMessage(msg);
                ruleManager.cleanUpRules();
            } else {
                Logger.error(this.getClass().getName(), "Received and parsed message that has unrecognized type, it will be ignored.");
            }
        } catch (Exception e) {
            Logger.error(this.getClass().getName(), "Unable to process message: " + msg.content.toString() + ". Exception: " + e.getMessage());
        }
    }
}
