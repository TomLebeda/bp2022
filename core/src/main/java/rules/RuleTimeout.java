package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleTimeout implements Rule {
    private long timeout = 0;
    private final InnerRule r;
    private final CoreStateMap coreStateMap;
    public final String description;

    @FunctionalInterface
    public interface InnerRule {
        void apply(CoreStateMap core, MessageStateMap msg, List<RuleOutput> out, RuleTimeout self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }

    public RuleTimeout(int timeout_ms, String description, CoreStateMap coreStateMap, InnerRule r) {
        this.r = r;
        this.timeout = timeout_ms + System.currentTimeMillis();
        this.coreStateMap = coreStateMap;
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg) {
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
        return System.currentTimeMillis() >= timeout;
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
