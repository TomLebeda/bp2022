package core;

import state.StateVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This class represents a Module, planned for future updates.
 */
public class Module {
    /**
     * address/name/key of the queue that this module is subscribed to
     */
    private final String listeningOn;
    /**
     * Is the module online and active?
     * Allows to differentiate between offline modules and non-existing modules.
     */
    private boolean online;
    /**
     * ID of this module, must be unique.
     * In future update this concept will be expanded with "name" attribute that is descriptive, but not unique.
     */
    private String ID;
    /**
     * List of the state variables that this module handles. Not used yet, prepared for future update.
     */
    private List<StateVariable> stateVariables = new ArrayList<>();

    /**
     * Creates new instance of Module with given parameters.
     * @param listeningOn key of the message queue that this module is listening on
     * @param online true if module is online and responsive, false if module is offline
     */
    public Module(String listeningOn, boolean online) {
        this.listeningOn = listeningOn;
        this.online = online;
        this.ID = UUID.randomUUID().toString();
    }

    /**
     * @return address of message queue that this module is subscribed to
     */
    public String getListeningOn() {
        return listeningOn;
    }

    /**
     * @return TRUE if module is active and listening, otherwise FALSE.
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * @return ID string of this module.
     */
    public String getID() {
        return ID;
    }
}
