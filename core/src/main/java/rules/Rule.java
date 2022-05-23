package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.MessageStateMap;
import state.CoreStateMap;

import java.util.List;
import java.util.NoSuchElementException;

public interface Rule {
    /**
     * Rule that should be applied.
     *
     * @param msg  message attributes that can be used inside the rule
     * @return returns collection of {@link RuleOutput} that should be processed
     */
    List<RuleOutput> apply(MessageStateMap msg);

    boolean toDelete();

    static void handleException(Exception e) {
        if (e instanceof InvalidTypeException) {
            Logger.error(RuleSimple.class.getName(), "Rule failed, because requested key was different type. This should not happen, please check your rules.");
        } else if (e instanceof NoSuchElementException) {
            Logger.warn(RuleSimple.class.getName(), "Rule failed, because requested key was not found: " + e);
        } else if (e instanceof IllegalAccessException) {
            Logger.error(RuleSimple.class.getName(), "Rule failed, because there was attempt to get message from stateVault that was not created from message.");
        } else {
            Logger.error(RuleSimple.class.getName(), "Unexpected exception occurred during rule application: " + e);
        }
        e.printStackTrace();
    }

    String getDescription();
}
