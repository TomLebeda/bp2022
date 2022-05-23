package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleBoundTimeout implements Rule {
    private InnerRule r;
    private String stateKey;
    private long timeout = 0;
    private CoreStateMap coreStateMap;
    public final String description;

    @FunctionalInterface
    public interface InnerRule {
        void apply(CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleBoundTimeout self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleBoundTimeout (CoreStateMap coreStateMap, String description, String boundStateKey, int timeout_ms, InnerRule r) {
        this.coreStateMap = coreStateMap;
        this.r = r;
        this.stateKey = boundStateKey;
        this.timeout = timeout_ms + System.currentTimeMillis();
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg){
        ArrayList<RuleOutput> output = new ArrayList<>();
        try {
            if (!toDelete()) {
                r.apply(coreStateMap, msg, output, this);
            } else {
                Logger.warn(this.getClass().getName(), "Attempted to apply rule that should be already deleted. Rule was not applied.");
            }
        } catch (Exception e) {
            Rule.handleException(e);
        }
        return output;
    }

    @Override
    public boolean toDelete() {
        if (this.timeout <= System.currentTimeMillis()) {
            return true;
        }
        if (coreStateMap.has(stateKey)) {
            try {
                return coreStateMap.getBool(stateKey);
            } catch (InvalidTypeException e) {
                Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey" + stateKey + " is not boolean, will return true.");
                return true;
            }
        } else {
            Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey " + stateKey + "does not exist in core state. Will return true.");
            return true;
        }
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
