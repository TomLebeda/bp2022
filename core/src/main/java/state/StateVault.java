package state;

import com.sun.jdi.InvalidTypeException;
import core.Core;
import core.Logger;
import messages.MessageCode;
import messages.MessageProcessor;
import messages.MyMessage;

import java.util.*;

/**
 * This class holds all state variables that are accessible at any time for any rule.
 */
public class StateVault implements CoreStateMap {
    private HashMap<String, StateVariable> vault = new HashMap<>();
    private boolean fromMessage = false;
    private Core core;
    private MessageProcessor messageProcessor;
//    private HashMap<String, MyMessage> messageCache = new HashMap<>();

//    @Override
//    public int cachedMessagesCount() {
//        return messageCache.size();
//    }
//
//    @Override
//    public void cacheMessage(MyMessage msg) {
//        this.messageCache.put(msg.getID(), msg);
//    }
//
//    @Override
//    public MyMessage getCachedMessage(String msgID) {
//        return messageCache.get(msgID);
//    }
//
//    @Override
//    public List<MyMessage> getAllCachedMessages() {
//        return new ArrayList<>(messageCache.values());
//    }
//
//    @Override
//    public List<MyMessage> getAllCachedMessages(MessageCode byCode) {
//        List<MyMessage> out = new ArrayList<>();
//        for (MyMessage myMessage : messageCache.values()) {
//            if (myMessage.getCode() == byCode) {
//                out.add(myMessage);
//            }
//        }
//        return out;
//    }
//
//    @Override
//    public void clearMessageCache() {
//        this.messageCache.clear();
//    }

    @Override
    public String toString() {
        return this.vault.toString();
    }

    /**
     * Returns a Double value of a StateVariable with given name.
     * Throws if there is no such variable or name does not match type.
     *
     * @param key name of the variable
     * @return value of the stored variable
     * @throws NoSuchElementException when there is no StateVariable stored with given name, this exception is thrown
     * @throws InvalidTypeException   when there is StateVariable with given name, but it has different type, this exception is thrown
     */
    @Override
    public Double getNumber(String key) throws NoSuchElementException, InvalidTypeException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof Number) {
                return ((Number) var).doubleValue();
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }

    /**
     * Returns a String value of a StateVariable with given name.
     * Throws if there is no such variable or name does not match type.
     *
     * @param key name of the variable
     * @return value of the stored variable
     * @throws NoSuchElementException when there is no StateVariable stored with given name, this exception is thrown
     * @throws InvalidTypeException   when there is StateVariable with given name, but it has different type, this exception is thrown
     */
    @Override
    public String getString(String key) throws NoSuchElementException, InvalidTypeException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof String) {
                return (String) var;
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }

    /**
     * Returns a Boolean value of a StateVariable with given name.
     * Throws if there is no such variable or name does not match type.
     *
     * @param key name of the variable
     * @return value of the stored variable
     * @throws NoSuchElementException when there is no StateVariable stored with given name, this exception is thrown
     * @throws InvalidTypeException   when there is StateVariable with given name, but it has different type, this exception is thrown
     */
    @Override
    public Boolean getBool(String key) throws NoSuchElementException, InvalidTypeException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof Boolean) {
                return (boolean) var;
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }

    @Override
    public Boolean getBool(String key, boolean defaultValue) throws NoSuchElementException, InvalidTypeException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof Boolean) {
                return (boolean) var;
            } else {
                throw new InvalidTypeException();
            }
        } else {
            return defaultValue;
        }
    }

    /**
     * Stores a StatesVariable that expires with given parameters.
     * If variable with given name already exists and has the same type, the value is updated.
     * If variable with given name already exists but has different type or origin, error is logged and update is not completed.
     * If no stored variable matches given name, new StateVariable is created and stored.
     *
     * @param key              name of the variable
     * @param origin           origin of the variable
     * @param value            new value of the variable
     * @param expirationPeriod expiration period of the new variable
     * @param expiredValue     expired value of the new variable
     * @param <T>              type is inferred from the value parameter
     */
    @Override
    public <T> void setState(String key, String origin, T value, long expirationPeriod, T expiredValue) {
        if (key.equals("") || origin.equals("")) {
            Logger.error(this.getClass().getName(), "Can not set state variable, because key or origin is not specified.");
            return;
        }
        StateVariable existing = vault.get(key);
        if (existing == null) {
            // variable does not exist, create a new one
            if (value instanceof Number || value instanceof String || value instanceof Boolean) {
                if (expirationPeriod <= 0) {
                    Logger.warn(this.getClass().getName(), "Given expiration period is zero or less, can not use that as expiration period. " +
                            "Will use default 10_000 (10 seconds).");
                    expirationPeriod = 10000;
                }
                Logger.info(this.getClass().getName(), "Setting new state variable " + key + " to value " + value + ".");
                vault.put(key, new StateVariable<>(key, origin, expirationPeriod, value, expiredValue));
            } else if (value instanceof List<?>) {
                Logger.warn(this.getClass().getName(), "Can not store state as List<> with expirations, state will be set without expiration.");
                if (((List<?>) value).size() == 0) {
                    Logger.warn(this.getClass().getName(), "Can not set empty List as core state.");
                    return;
                }
                Object first = ((List<?>) value).get(0);
                if (first instanceof Number || first instanceof String || first instanceof Boolean) {
                    vault.put(key, new StateVariable<>(key, origin, value));
                } else {
                    Logger.warn(this.getClass().getName(), "Can not set list as variable, because stored type is not Number, String or Boolean.");
                }
            } else {
                Logger.error(this.getClass().getName(), "Can not set state variable, because given value is not Number, String or Boolean.");
            }
        } else {
            // variable already exists, check and update it
            if (existing.getOrigin().equals(origin)) {
                // origin is the same, proceed to check if the type match
                if ((value instanceof Number && existing.value instanceof Number)
                        || (value instanceof Boolean && existing.value instanceof Boolean)
                        || (value instanceof String && existing.value instanceof String)
                        || (value instanceof List<?>)) {
                    if (value instanceof List<?>){
                        if (((List<?>) value).size() == 0) {
                            Logger.warn(this.getClass().getName(), "Can not update state variable \"" + key + "\" to empty list.");
                            return;
                        }
                    }
                    // type matches, update the value
                    // If list stores different types, it will be changed to new type (?)
                    Logger.info(this.getClass().getName(), "Updating state variable " + key + " to value " + value);
                    existing.updateValue(value);
                } else {
                    // type does not match
                    Logger.error(this.getClass().getName(), "Can not update state variable " + key + " because types does not match.");
                }
            } else {
                // origin does not match
                Logger.error(this.getClass().getName(), "Can not update state variable " + key + " because origin does not match.");
            }
        }
    }

    /**
     * Sets a StateVariable that does not expire.
     * If variable with given name already exists and has the same type, the value is updated.
     * If variable with given name already exists but has different type or origin, error is logged and update is not completed.
     * If no stored variable matches given name, new StateVariable is created and stored.
     *
     * @param key    name of the variable
     * @param origin origin of the variable
     * @param value  new value of the variable
     * @param <T>    type is inferred from the value parameter
     */
    @Override
    public <T> void setState(String key, String origin, T value) {
        if (key.equals("") || origin.equals("")) {
            Logger.error(this.getClass().getName(), "Can not set state variable, because key or origin is not specified.");
            return;
        }
        StateVariable var = vault.get(key);
        if (var == null) {
            // variable does not exist, create a new one
            if (value instanceof Number || value instanceof String || value instanceof Boolean) {
                Logger.info(this.getClass().getName(), "Setting new state variable " + key + " to value " + value + ".");
                vault.put(key, new StateVariable<>(key, origin, value));
            } else if (value instanceof List<?>) {
                if (((List<?>) value).isEmpty()) {
                    Logger.warn(this.getClass().getName(), "Can not set empty List as core state.");
                    return;
                }
                Object first = ((List<?>) value).get(0);
                if (first instanceof Number || first instanceof String || first instanceof Boolean) {
                    vault.put(key, new StateVariable<>(key, origin, value));
                } else {
                    Logger.warn(this.getClass().getName(), "Can not set list as variable, because stored type is not Number, String or Boolean.");
                }
            } else {
                Logger.error(this.getClass().getName(), "Can not set state variable, because given value is not Number, String or Boolean.");
            }
        } else {
            // variable already exists, check and update it
            if (var.getOrigin().equals(origin)) {
                // origin is the same, proceed to check if the type match
                if ((value instanceof Number && var.value instanceof Number)
                        || (value instanceof Boolean && var.value instanceof Boolean)
                        || (value instanceof String && var.value instanceof String)) {
                    // type matches, update the value
                    Logger.info(this.getClass().getName(), "Updating state variable " + key + " to value " + value);
                    var.updateValue(value);
                } else {
                    // type does not match
                    Logger.error(this.getClass().getName(), "Can not update state variable " + key + " because types does not match.");
                }
            } else {
                // origin does not match
                Logger.error(this.getClass().getName(), "Can not update state variable " + key + " because origin does not match.");
            }
        }
    }

    /**
     * Deletes a state variable with given name.
     * If there is now variable with given name, warning is logged and nothing is deleted.
     *
     * @param key name of a variable that should be removed
     */
    @Override
    public void delState(String key) {
        Logger.trace(this.getClass().getName(), "Removing value " + key + ".");
        if (vault.remove(key) == null) {
            Logger.warn(this.getClass().getName(), "Unable to delete state variable " + key + ", because there is no variable with given name.");
        }
    }

    @Override
    public boolean has(String key) {
        return this.vault.containsKey(key);
    }

    @Override
    public List<String> getStringList(String key) throws InvalidTypeException, NoSuchElementException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof List<?>) {
                if (((List<?>) var).size() > 0) {
                    Object first = ((List<?>) var).get(0);
                    if (first instanceof String) {
                        return (List<String>) var;
                    } else {
                        throw new InvalidTypeException();
                    }
                } else {
                    throw new InvalidTypeException();
                }
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }

    @Override
    public List<Double> getNumberList(String key) throws InvalidTypeException, NoSuchElementException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof List<?>) {
                if (((List<?>) var).size() > 0) {
                    Object first = ((List<?>) var).get(0);
                    if (first instanceof Double) {
                        return (List<Double>) var;
                    } else {
                        throw new InvalidTypeException();
                    }
                } else {
                    throw new InvalidTypeException();
                }
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }

    @Override
    public List<Boolean> getBooleanList(String key) throws InvalidTypeException, NoSuchElementException {
        if (vault.containsKey(key)) {
            Object var = vault.get(key).getValue();
            if (var instanceof List<?>) {
                if (((List<?>) var).size() > 0) {
                    Object first = ((List<?>) var).get(0);
                    if (first instanceof Boolean) {
                        return (List<Boolean>) var;
                    } else {
                        throw new InvalidTypeException();
                    }
                } else {
                    throw new InvalidTypeException();
                }
            } else {
                throw new InvalidTypeException();
            }
        } else {
            throw new NoSuchElementException();
        }
    }


    /**
     * Removes all variables that are associated with given module.
     *
     * @param moduleName ID of a module whose variables should be removed
     */
    public void removeAllFromModule(String moduleName) {
        Logger.trace(this.getClass().getName(), "Removing all state variables associated with module " + moduleName + ".");
        vault.values().removeIf(stateVariable -> stateVariable.getOrigin().equals(moduleName));
    }

    /**
     * Stores a references to other submodules that are needed in other methods.
     *
     * @param core             reference to Core instance
     * @param messageProcessor reference to Message Processor instance
     */
    public void link(Core core, MessageProcessor messageProcessor) {
        this.core = core;
        this.messageProcessor = messageProcessor;
    }

    public void setup() {
        // setup state variable for holding names of living modules
        List<String> living_modules = new ArrayList<>();
        living_modules.add("core"); // can not add empty list as state variable (because of type-safety)
        this.setState("living_modules", Core.ID, living_modules);
        this.setState("dialog_state", Core.ID, "dead"); // start the dialog state as "dead"
    }
}
