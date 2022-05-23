package rules;

import core.Core;
import core.Logger;
import messages.MessageCode;
import messages.MessageDestinationType;
import messages.MessageHeader;
import messages.MyMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import state.MessageStateMap;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public abstract class RuleActions {

    static void sendDesktopNotification(MessageStateMap msg, List<RuleOutput> out) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.NOTIFY);
        MessageCode code = msg.getCode();
        JSONArray actions = new JSONArray();
        switch (code) {
            case NEW_RSS -> {
                newMsg.content.put(MessageHeader.TITLE.getValue(), "Nový RSS článek:");
                newMsg.content.put(MessageHeader.DESCRIPTION.getValue(), msg.getString("title"));
            }
            case UNREAD_EMAILS -> {
                int n = msg.getNumberList("content").size();
                String tts;
                if (n == 1) {
                    tts = "Máte nepřečtený email.";
//                    TODO : add actions DETAILS (download more info and show it as notification), MARK AS READ
                } else if (n > 1 && n < 5) {
                    tts = String.format("Máte %d nepřečtené emaily.", n);
                } else {
                    tts = String.format("Máte %d nepřečtených emailů.", n);
                }
                newMsg.content.put(MessageHeader.TITLE.getValue(), tts);
            }
            default -> {
                newMsg.content.put(MessageHeader.TITLE.getValue(), "Nová notifikace:"); // unknown notification
            }
        }
        if (msg.has(MessageHeader.LINK.getValue())) {
            actions.put(new JSONObject()
                    .put("key", "link:" + msg.getString(MessageHeader.LINK.getValue()))
                    .put("text", "Otevřít"));
        }
        actions.put(new JSONObject()
                .put("key", "delay:3600")
                .put("text", "Odložit"));
        newMsg.content.put(MessageHeader.ACTIONS.getValue(), actions);
        out.add(new RuleOutput(false, 30_000, newMsg, "desktop_notifier", MessageDestinationType.QUEUE));
    }

    static long sendBackTomorrow(MessageStateMap msg, List<RuleOutput> out, int at_hour) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), "core", MessageCode.STORE);
        newMsg.content.put(MessageHeader.CONTENT.getValue(), msg.getContent());
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1).withHour(at_hour).withMinute(0);
        long duration = Duration.between(LocalDateTime.now(), tomorrow).getSeconds() * 1000;
        newMsg.content.put(MessageHeader.EXPIRATION.getValue(), duration);
        newMsg.content.put(MessageHeader.ON_EXPIRE.getValue(), "delsend");
        out.add(new RuleOutput(false, -1, newMsg, "message_vault", MessageDestinationType.QUEUE));
        return duration;
    }

    static void startListening(MessageStateMap msg, List<RuleOutput> out) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), "core", MessageCode.START_LISTENING);
        out.add(new RuleOutput(false, 1000, newMsg, "speech_module", MessageDestinationType.QUEUE));
    }

    static void stopListening(MessageStateMap msg, List<RuleOutput> out) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), "core", MessageCode.STOP_LISTENING);
        out.add(new RuleOutput(false, 1_0000, newMsg, "speech_module", MessageDestinationType.QUEUE));
    }

    static void terminate(List<RuleOutput> out, String ruleDesc) {
        Logger.trace(ruleDesc, "TERMINATED");
        out.add(new RuleOutput(true,0, null, null, null));
    }

    static void synthAndSpeak(List<RuleOutput> out, String textToSpeak, String destination) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.SYNTHESIZE);
        newMsg.content.put("content", textToSpeak);
        out.add(new RuleOutput(false, 5000, newMsg, destination, MessageDestinationType.QUEUE));
    }

    static void fetchAllUnreadMails(List<RuleOutput> out) {
        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.FETCH);
        newMsg.content.put(MessageHeader.FILTER_TYPE.getValue(), "all_unread");
//        newMsg.content.put(MessageHeader.ACTIONS.getValue(), attachedCommand);
        out.add(new RuleOutput(false, 3000, newMsg, "email_reader", MessageDestinationType.QUEUE));
        Logger.debug("REQUEST FOR EMAIL FETCH WAS ADDED TO OUTPUT");
    }

    static void sendBackAfter(List<RuleOutput> out, MyMessage msgToResend, int resendAfterSeconds) {
        MyMessage storeMessage = new MyMessage(UUID.randomUUID().toString(), "core", MessageCode.STORE);
        storeMessage.content.put(MessageHeader.CONTENT.getValue(), msgToResend.content);
        storeMessage.content.put(MessageHeader.EXPIRATION.getValue(), resendAfterSeconds * 1000); // given time is seconds, but message vault counts in millis
        storeMessage.content.put(MessageHeader.ON_EXPIRE.getValue(), "delsend");
        out.add(new RuleOutput(false, -1, storeMessage, "message_vault", MessageDestinationType.QUEUE));
    }
}
