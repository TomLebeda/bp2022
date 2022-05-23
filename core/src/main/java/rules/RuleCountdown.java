package rules;

import com.sun.jdi.InvalidTypeException;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleCountdown implements Rule {

    private Counter counter;
    private InnerRule r;
    private CoreStateMap coreStateMap;
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
        void apply(Counter counter, CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleCountdown self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleCountdown(int countdownInit, String description, CoreStateMap coreStateMap, InnerRule r) {
        this.r = r;
        this.counter = new Counter(countdownInit);
        this.coreStateMap = coreStateMap;
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg) {
        ArrayList<RuleOutput> output = new ArrayList<>();
        try {
            if (!toDelete()) {
                r.apply(counter, coreStateMap, msg, output, this);
            }
        } catch (Exception e) {
            Rule.handleException(e);
        }
        return output;
    }

    @Override
    public boolean toDelete() {
        return counter.getRemaining() <= 0;
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
