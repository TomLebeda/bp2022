package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleBoundTimeoutCountdown implements Rule {
    private CoreStateMap coreStateMap;
    private InnerRule r;
    private Counter counter;
    private long timeout = 0;
    private String stateKey;
    public final String description;

    public class Counter {
        private int remaining = 0;

        public Counter(int remaining) {
            this.remaining = remaining;
        }

        public void countdown() {
            remaining--;
        }

        public void countup() {
            remaining++;
        }

        public void nullify() {
            remaining = 0;
        }

        public void add(int i) {
            remaining += i;
        }

        public int getRemaining() {
            return remaining;
        }
    }

    @FunctionalInterface
    public interface InnerRule {
        void apply(Counter counter, CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleBoundTimeoutCountdown self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleBoundTimeoutCountdown(CoreStateMap coreStateMap, String description, String boundStateKey, int countdownInit, int timeout_ms, InnerRule r) {
        this.counter = new Counter(countdownInit);
        this.timeout = timeout_ms + System.currentTimeMillis();
        this.stateKey = boundStateKey;
        this.coreStateMap = coreStateMap;
        this.r = r;
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg){
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
        if (counter.getRemaining() <= 0 || System.currentTimeMillis() >= timeout) {
            Logger.debug("RULE KILLED ON FIRST IF");
            return true;
        } else {
            if (coreStateMap.has(stateKey)) {
                try {
                    return !coreStateMap.getBool(stateKey);
                } catch (InvalidTypeException e) {
                    Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey" + stateKey + " is not boolean, will return true.");
                    Logger.debug("RULE KILLED ON SECOND IF");
                    return true;
                }
            } else {
                Logger.warn(this.getClass().getName(), "Trying to execute toDelete() on rule, but stateKey " + stateKey + "does not exist in core state. Will return true.");
                Logger.debug("RULE KILLED ON THIRD IF");
                return true;
            }
        }
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
