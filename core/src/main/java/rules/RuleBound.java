package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleBound implements Rule {

    private InnerRule r;
    private CoreStateMap coreStateMap;
    private String boundStateKey;
    public final String description;

    @FunctionalInterface
    public interface InnerRule {
        void apply(CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleBound self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleBound(CoreStateMap coreStateMap, String description, String boundStateKey, InnerRule r) {
        this.coreStateMap = coreStateMap;
        this.r = r;
        this.boundStateKey = boundStateKey;
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
        if (coreStateMap.has(boundStateKey)) {
            try {
                return !coreStateMap.getBool(boundStateKey); //if state is false, the rule should be deleted
            } catch (InvalidTypeException e) {
                Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey" + boundStateKey + " is not boolean, will return true.");
                return true;
            }
        } else {
            Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey " + boundStateKey + "does not exist in core state. Will return true.");
            return true;
        }
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
