package rules;

import messages.MessageDestinationType;
import messages.MyMessage;

/**
 * This class represents output Rule after it is applied to a data message.
 * {@link RuleOutput} contains message that should be sent, destination and a boolean that says if the output is terminal (terminal output stops any further rule application).
 */
public class RuleOutput {
    public boolean terminal;
    public MyMessage msg;
    public String destination;
    public MessageDestinationType destType;
    public int expiration;

    public RuleOutput(boolean terminal, int messageExpiration_ms, MyMessage msg, String destination, MessageDestinationType destType) {
        this.terminal = terminal;
        this.msg = msg;
        this.destination = destination;
        this.destType = destType;
        this.expiration = messageExpiration_ms;
    }
}
