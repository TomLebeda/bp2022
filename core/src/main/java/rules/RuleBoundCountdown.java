package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleBoundCountdown implements Rule {
    private InnerRule r;
    private CoreStateMap coreStateMap;
    private String boundStateKey;
    private Counter counter;
    public final String description;

    private class Counter {
        private int remaining = 0;

        public Counter(int remaining) {
            this.remaining = remaining;
        }

        void countdown() {
            remaining--;
        }

        void countup() {
            remaining++;
        }

        void nullify() {
            remaining = 0;
        }

        void add(int i) {
            remaining += i;
        }

        int getRemaining() {
            return remaining;
        }
    }

    @FunctionalInterface
    public interface InnerRule {
        void apply(Counter counter, CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleBoundCountdown self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleBoundCountdown(CoreStateMap coreStateMap, String description, int countdownInit, String boundStateKey, InnerRule r) {
        this.coreStateMap = coreStateMap;
        this.r = r;
        this.boundStateKey = boundStateKey;
        this.counter = new Counter(countdownInit);
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg) {
        ArrayList<RuleOutput> output = new ArrayList<>();
        try {
            if (!toDelete()) {
                r.apply(counter, coreStateMap, msg, output, this);
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
        if (counter.getRemaining() <= 0) {
            return true;
        }
        if (coreStateMap.has(boundStateKey)) {
            try {
                return !coreStateMap.getBool(boundStateKey);
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
