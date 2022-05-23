package core;

import messages.MessageProcessor;
import rules.RuleManager;
import state.CoreStateMap;
import state.StateVault;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic class that represents the Core module itself.
 * Contains submodules that are linked together and provide all Core's functionality.
 */
public class Core {

    public static final String ID = "MODULE_ID";
    protected final String passwd = "PASSWORD";

    private final MQClient mqClient;
    private final MessageProcessor messageProcessor;
    private final RuleManager ruleManager;
    private final StateVault stateVault;
    List<Module> moduleList = new ArrayList<>();

    public Core() {
        // create all components
        Logger.info(this.getClass().getName(), "Starting...");
        Logger.trace(this.getClass().getName(), "Creating all modules...");
        mqClient = new MQClient();
        messageProcessor = new MessageProcessor();
        ruleManager = new RuleManager();
        stateVault = new StateVault();

        // link components together
        Logger.trace(this.getClass().getName(), "Linking components...");
        mqClient.link(this, messageProcessor);
        messageProcessor.link(this, mqClient, ruleManager, stateVault);
        ruleManager.link(this, messageProcessor);
        stateVault.link(this, messageProcessor);

        // setup all the components
        Logger.trace(this.getClass().getName(), "Starting all setups...");
        stateVault.setup();
        ruleManager.setup();
        mqClient.setup("BROKER_URL_HERE", Core.ID, this.passwd);

        Logger.success(this.getClass().getName(), "Setup completed, ready to go.");
    }

    /**
     * Helper function that returns state map of the core
     * @return reference to Core's state vault
     */
    public CoreStateMap getStateMap() {
        return this.stateVault;
    }
}
