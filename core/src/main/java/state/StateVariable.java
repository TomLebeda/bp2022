package state;

/**
 * This class represents a state variable that is stored in a {@link StateVault}
 *
 * @param <T> Data type that will be stored in this variable. It can be Number (Double), Boolean or String. Type is final, can not be changed later.
 */
public class StateVariable<T> {
    /**
     * name of the variable, used as a key for finding in map
     */
    private final String name;
    /**
     * ID of module that is responsible for updating and managing values of this variable
     */
    private final String origin;
    /**
     * last time this variable was refreshed, in milliseconds unix-time
     */
    protected long lastRefresh;
    /**
     * maximal duration (in milliseconds) that can pass without updating before the variable is considered outdated and valueExpired is used.
     * When variable is refreshed, it is no longer expired and normal value is used again (until it expires again)
     */
    protected long expirationPeriod;
    /**
     * If true, this values does expire.
     * If false, this value never expires and there is no need to refresh it periodically.
     */
    protected final boolean expires;

    /**
     * The value itself. Type can be Boolean, String or Number (Double)
     */
    protected T value;
    /**
     * Default value, that should be used when this {@link StateVariable} expires.
     */
    protected T valueExpired;

    /**
     * Returns a value of this variable.
     * If the variable expires, expiration is handled.
     * If the variable is expired, the default valueExpired is returned.
     *
     * @return value of this variable, valueExpired if the variable is expired
     */
    public T getValue() {
        if (expires) {
            if (System.currentTimeMillis() > lastRefresh + expirationPeriod) {
                return valueExpired;
            } else {
                return value;
            }
        } else {
            return value;
        }
    }

    /**
     * Updated this variable to the given newValue.
     * If the variable expires, the expiration countdown is refreshed (lastRefresh is updated).
     *
     * @param newValue new value that will be set
     */
    public void updateValue(T newValue) {
        this.value = newValue;
        this.lastRefresh = System.currentTimeMillis();
    }

    /**
     * Creates a new StateVariable that never expires.
     * Type of the variable is inferred from the Type of value parameter.
     *
     * @param name   name of the new variable
     * @param origin origin of the new variable
     * @param value  value of the new variable. Type can be Boolean, String or Number (Double)
     */
    public StateVariable(String name, String origin, T value) {
        this.name = name;
        this.origin = origin;
        this.value = value;
        this.expires = false;
    }

    /**
     * Creates a new StateVariable that expires. Type of the variable is inferred from the Type of value parameter.
     *
     * @param name             name of the variable
     * @param origin           ID of a module that is responsible for this variable
     * @param expirationPeriod max duration that can pass without refreshing before the variable expires
     * @param value            the value of this variable
     * @param valueExpired     the value that should be used when the variable expires
     */
    public StateVariable(String name, String origin, long expirationPeriod, T value, T valueExpired) {
        this.name = name;
        this.origin = origin;
        this.expirationPeriod = expirationPeriod;
        this.value = value;
        this.valueExpired = valueExpired;
        this.expires = true;
        this.lastRefresh = System.currentTimeMillis();
    }

    public String getName() {
        return this.name;
    }

    public String getOrigin() {
        return origin;
    }

}

