package core;

import com.sun.jdi.InvalidTypeException;
import messages.MessageCode;
import messages.MessageDestinationType;
import messages.MessageProcessor;
import messages.MyMessage;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import javax.jms.*;
import java.util.*;

/**
 * This class represents the core submodule that is responsible for connection to message broker, sending and receiving messages.
 * It handles messages listeners and transmits incoming messages to {@link MessageProcessor} to handle.
 */
public class MQClient {
    /**
     * Number of milliseconds between broadcasting connection check message.
     */
    public static final int CONNECTION_CHECK_PERIOD = 60_000;
    /**
     * reference to the MessageProcessor instance
     */
    private MessageProcessor messageProcessor;
    /**
     * reference to the Core object
     */
    private Core core;
    /**
     * ConnectionFactory object used to establish connection to Artemis
     */
    private ActiveMQConnectionFactory connectionFactory;
    private String login;
    private String passwd;

    /**
     * Sets login and password for a client to be able to connect to broker with given URL.
     *
     * @param brokerUrl URL of broker
     * @param login     login/username for connecting to broker
     * @param passwd    password for connecting to broker
     */
    public void setup(String brokerUrl, String login, String passwd) {
        // setup login and password for broker
        this.login = login;
        this.passwd = passwd;

        try {
            // create new connection factory that will be used to create connections, sessions and other objects
            connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
            Logger.trace(this.getClass().getName(), "Connection factory created on url:" + brokerUrl);
            Logger.trace(this.getClass().getName(), "Connection started.");

            // setup basic message listeners on broadcast topic and core queue for this very module
            setupListener(MessageDestinationType.QUEUE, "core");
            setupListener(MessageDestinationType.TOPIC, "broadcast");

            // start the connection-checking beacon
            new Thread(() -> {
                while (true) {
                    try {
                        this.broadcastConnectionCheck();
                        Thread.sleep(CONNECTION_CHECK_PERIOD); // broadcast connection-check every minute
                    } catch (InterruptedException e) {
                        Logger.error(this.getClass().getName(), "Thread was interrupted.");
                    }
                }
            }).start();
        } catch (Exception e) {
            Logger.fatal(this.getClass().getName(), "Setup of MQClient failed:" + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Creates, connects and handles new message listener on given topic or queue.
     * Every listener runs in separate thread.
     *
     * @param destinationType Type of destination that will be listened to. Can be QUEUE or TOPIC (see {@link MessageDestinationType}).
     * @param topicName       Name of the destination that will be listened to.
     */
    private void setupListener(MessageDestinationType destinationType, String topicName) {
        // every listener has its own thread
        new Thread(() -> {
            while (true) {
                Connection connection = null;
                Session session = null;
                try {
                    // create connection and session for the new listener
                    connection = connectionFactory.createConnection(login, passwd);
                    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    connection.start();
                    Destination dest;

                    // choose destination type and set its name
                    if (destinationType == MessageDestinationType.TOPIC) {
                        dest = session.createTopic(topicName);
                    } else if (destinationType == MessageDestinationType.QUEUE) {
                        dest = session.createQueue(topicName);
                    } else {
                        Logger.warn(this.getClass().getName(), "Unable to set new listener, because destination type was not given. This should never happen, check your code.");
                        return;
                    }

                    // create consumer
                    MessageConsumer consumer = session.createConsumer(dest);
                    Logger.info(this.getClass().getName(), "Added listener to " + destinationType.name().toLowerCase(Locale.ROOT) + ": " + topicName);

                    // start the listening loop with blocking waiting and infinite timeout
                    while (true) {
                        messageProcessor.digest(consumer.receive(0));
                    }
                } catch (Exception e) {
                    Logger.error(this.getClass().getName(), "Failed to add listener to topic: " + topicName + ". Reason: " + e.getMessage());
                } finally {
                    cleanup(connection, session);
                }
            }
        }).start();
    }

    /**
     * Cleans up resources after connection is closed.
     * Broker should be able to do that as well, but it is bad habit to let broker clean up dead connections.
     *
     * @param connection connection to close and destroy
     * @param session    session to close and destroy
     */
    private void cleanup(Connection connection, Session session) {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                Logger.warn(this.getClass().getName(), "Unable to close connection: " + e.getMessage() + ". Broker should be able to clean up dead connections.");
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (JMSException e) {
                Logger.warn(this.getClass().getName(), "Unable to close session: " + e.getMessage() + ". Broker should be able to clean up dead sessions.");
            }
        }
    }

    /**
     * Sends a connection-check message to broadcast topic.
     * This method is called periodically to detect dead modules and confirm connection with living ones.
     */
    public void broadcastConnectionCheck() {
        Logger.info(this.getClass().getName(), "Cleaning non-responsive modules");
        try {
            List<String> living_modules = core.getStateMap().getStringList("living_modules");
            List<String> to_delete = new ArrayList<>();
            for (String module : living_modules) {
                if (module.equals(Core.ID)) {
                    // skip the core item
                    continue;
                }
                if (!core.getStateMap().getBool("module_verified_" + module, true)) {
                    // the module is not verified any more => kill it
                    MyMessage killMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.DIE);
                    sendMessage(MessageDestinationType.QUEUE, module, 10_000, killMsg);
                    core.getStateMap().delState("module_verified_" + module);
                    // can not remove items from list directly during its own iteration
                    to_delete.add(module);
                }
            }
            living_modules.removeAll(to_delete);
            if (!to_delete.isEmpty()) {
                Logger.debug("LIVING MODULES AFTER CLEANUP: " + core.getStateMap().getStringList("living_modules"));
            }
        } catch (Exception e) {
            Logger.error(this.getClass().getName(), "Failed to get living_modules state: " + e.getCause());
        }

        Logger.info(this.getClass().getName(), "Broadcasting connection check.");
        MyMessage msg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.WHO);
        sendMessage(MessageDestinationType.TOPIC, "broadcast", 10_000, msg);
    }

    /**
     * Send a text message or more of them at once to given destination on broker with infinite expiration time.
     *
     * @param destinationType Type of destination that message should be sent to.
     * @param destinationName Name of destination that message should be sent to.
     * @param message         Content of the message.
     */
    public void sendMessage(MessageDestinationType destinationType, String destinationName, MyMessage... message) {
        sendMessage(destinationType, destinationName, -1, message);
    }

    /**
     * Sends a text message (or more of them at once) to given destination on broker.
     *
     * @param destinationType Type of destination that message should be sent to.
     * @param destinationName Name of destination that message should be sent to.
     * @param message         Content of the message.
     * @param expiration      Expiration of the message in milliseconds.
     */
    public void sendMessage(MessageDestinationType destinationType, String destinationName, int expiration, MyMessage... message) {
        Connection connection = null;
        Session session = null;

        try {
            // create and start new connection and session
            connection = connectionFactory.createConnection(login, passwd);
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            connection.start();

            // for every message that should be sent:
            for (MyMessage myMessage : message) {
                // select correct destination type and set its name
                TextMessage msg = session.createTextMessage(myMessage.content.toString());
                if (destinationType == MessageDestinationType.QUEUE) {

                    // create a producer with given destination
                    MessageProducer producer = session.createProducer(session.createQueue(destinationName));

                    // check if the messages should expire or not
                    if (expiration > 0) {
                        producer.setTimeToLive(expiration);
                    }
                    // send the message
                    producer.send(msg);
                    Logger.trace(this.getClass().getName(), "Message " + msg.getText() + " sent to destination " + destinationName);
                } else if (destinationType == MessageDestinationType.TOPIC) {

                    // create a producer and send the message
                    session.createProducer(session.createTopic(destinationName)).send(msg);
                } else {
                    Logger.error(this.getClass().getName(), "Can not send message because destination type has not been specified. This should never happen, check your code.");
                }
            }
        } catch (JMSException e) {
            Logger.error(this.getClass().getName(), "Failed to send message: " + e.getMessage());
        } finally {
            // clean up the connections and sessions after all messages are sent
            cleanup(connection, session);
        }
    }

    /**
     * Links other core submodules that are required in other methods.
     *
     * @param core             reference to the Core instance
     * @param messageProcessor reference to the messageProcessor instance
     */
    public void link(Core core, MessageProcessor messageProcessor) {
        this.core = core;
        this.messageProcessor = messageProcessor;
    }
}
