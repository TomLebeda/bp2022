package rules;

import com.sun.jdi.InvalidTypeException;
import core.Logger;
import state.CoreStateMap;
import state.MessageStateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

public class RuleSimple implements Rule {

    private InnerRule r;
    private CoreStateMap coreStateMap;
    private final String description;

    @FunctionalInterface
    public interface InnerRule {
        void apply(CoreStateMap core, MessageStateMap msg, List<RuleOutput> output, RuleSimple self) throws InvalidTypeException, NoSuchElementException, IllegalAccessException;
    }


    public RuleSimple(CoreStateMap coreStateMap, String description, InnerRule r) {
        this.coreStateMap = coreStateMap;
        this.r = r;
        this.description = description;
    }

    @Override
    public List<RuleOutput> apply(MessageStateMap msg){
        ArrayList<RuleOutput> output = new ArrayList<>();
        try {
            r.apply(coreStateMap, msg, output, this);
        } catch (Exception e) {
            Rule.handleException(e);
        }
        return output;
    }

    @Override
    public boolean toDelete() {
        return false;
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}
