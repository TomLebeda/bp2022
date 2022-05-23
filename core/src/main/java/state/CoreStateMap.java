package state;

import com.sun.jdi.InvalidTypeException;
import messages.MessageCode;
import messages.MyMessage;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * This interface is used to access and modify {@link StateVariable} inside {@link StateVault} from Rules.
 * It handles type conversion with generic methods.
 */
public interface CoreStateMap {
    String getString(String key) throws InvalidTypeException, NoSuchElementException;

    Double getNumber(String key) throws InvalidTypeException, NoSuchElementException;

    Boolean getBool(String key) throws InvalidTypeException, NoSuchElementException;

    Boolean getBool(String key, boolean defaultValue) throws InvalidTypeException, NoSuchElementException;

    <T> void setState(String key, String origin, T value, long expirationPeriod, T expiredValue);

    <T> void setState(String key, String origin, T value);

    void delState(String key);

    boolean has(String key);

    List<String> getStringList(String key) throws InvalidTypeException, NoSuchElementException;

    List<Double> getNumberList(String key) throws InvalidTypeException, NoSuchElementException;

    List<Boolean> getBooleanList(String key) throws InvalidTypeException, NoSuchElementException;

    @Override
    String toString();
}
