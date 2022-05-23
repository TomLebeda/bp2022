package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleTimeoutCountdown implements Rule {

    private InnerRule r;
    private Counter counter;
    private long timeout = 0;
    private CoreStateMap coreStateMap;
    public final String description;

    public class Counter {
        private int remaining = 0;

        public Counter(int remaining) {
            this.remaining = remaining;
        }

        public void countDown() {
            remaining--;
        }

        public void countUp() {
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
        void apply(Counter counter, CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleTimeoutCountdown self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleTimeoutCountdown(CoreStateMap coreStateMap, String description, int countdownInit, int timeout_ms, InnerRule r) {
        this.r = r;
        this.counter = new Counter(countdownInit);
        this.timeout = timeout_ms + System.currentTimeMillis();
        this.coreStateMap = coreStateMap;
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
        return counter.getRemaining() <= 0 || System.currentTimeMillis() >= timeout;
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
