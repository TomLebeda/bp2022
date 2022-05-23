package rules;

import com.sun.jdi.InvalidTypeException;
import core.Core;
import core.Logger;
import messages.*;
import org.json.JSONArray;
import state.CoreStateMap;
import state.MessageStateMap;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static rules.RuleActions.*;
import static messages.MessageCode.*;

public class RuleManager {
    private MessageProcessor messageProcessor;
    private CoreStateMap coreStateMap;
    private ArrayList<Rule> rulebook = new ArrayList<>();
    private ArrayList<Rule> waitingRules = new ArrayList<>();

    private void loadAllRules() {
        rulebook.add(new RuleSimple(coreStateMap, "Rule for settings core states", (core, msg, output, self) -> {
            if (msg.getCode() == SET_STATE) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                switch (msg.getString("type")) {
                    case "string" -> {
                        core.setState(msg.getString("title"), msg.getOrigin(), msg.getString("content"));
                    }
                    case "boolean" -> {
                        core.setState(msg.getString("title"), msg.getOrigin(), msg.getBool("content"));
                    }
                    case "number" -> {
                        core.setState(msg.getString("title"), msg.getOrigin(), msg.getNumber("content"));
                    }
                }
                terminate(output, self.getDescription());
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for switching active dialog modules", (core, msg, output, self) -> {
            if (msg.getCode() == DIALOG_READY) {
                if (msg.getBool("content")) { // given dialog manager is ready
                    core.setState(msg.getOrigin() + "_ready", msg.getOrigin(), msg.getBool("content"));

                    // if dialog state is "dead", set it to "waiting_for_command"
                    if (core.getString("dialog_state").equals("dead")) {
                        core.setState("dialog_state", Core.ID, "waiting_for_command");
                    }

                    // switch currently active dialog module
                    if (core.has("active_dialog_module") && msg.getOrigin().equals("speech_module") && core.getString("active_dialog_module").equals("voice_kit")) {
                        // voice_kit has priority, therefore it can not be overwritten by speech_module
                        terminate(output, self.getDescription());
                    } else {
                        // stop listening on old dialog manager if there is any
                        if (core.has("active_dialog_module") && !core.getString("active_dialog_module").equals(msg.getOrigin())) {
                            MyMessage stopMsg = new MyMessage(UUID.randomUUID().toString(), "core", STOP_LISTENING);
                            output.add(new RuleOutput(true, 10_000, stopMsg, core.getString("active_dialog_module"), MessageDestinationType.QUEUE));
                        }

                        // switch the active dialog
                        Logger.debug("Active dialog manager switched to: " + msg.getOrigin());
                        core.setState("active_dialog_module", Core.ID, msg.getOrigin());

                        // start listening on given dialog
                        MyMessage startMsg = new MyMessage(UUID.randomUUID().toString(), "core", START_LISTENING);
                        output.add(new RuleOutput(true, 10_000, startMsg, msg.getOrigin(), MessageDestinationType.QUEUE));

                        terminate(output, self.getDescription());
                    }
                } else { // dialog manager is not ready
                    core.setState(msg.getOrigin() + "_ready", msg.getOrigin(), msg.getBool("content"));

                    // switch currently active dialog manager
                    if (core.getBool("voice_kit_ready", false)) {
                        core.setState("active_dialog_module", Core.ID, "voice_kit");
                    } else if (core.getBool("speech_module_ready", false)) {
                        core.setState("active_dialog_module", Core.ID, "speech_module");
                    } else {
                        // if no module is ready, set the dialog state as dead
                        core.setState("dialog_state", Core.ID, "dead");
                    }
                }
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for deleting core states", (core, msg, output, self) -> {
            if (msg.getCode() == DELETE_STATE) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                core.delState(msg.getString("title"));
                Logger.info(self.getDescription(), "Core state" + msg.getString("title") + " was deleted.");
                terminate(output, self.getDescription());
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for enabling listening when TTS is done", (core, msg, output, self) -> {
            if (msg.getCode() == TTS_DONE) {
                Logger.trace(self.getDescription(), "TTS done on module" + msg.getOrigin());
                MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), "core", MessageCode.START_LISTENING);
                output.add(new RuleOutput(true, 10_000, newMsg, msg.getOrigin(), MessageDestinationType.QUEUE));
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for telling user nothing was recognized", (core, msg, output, self) -> {
            if (msg.getCode() == SPEECH && !msg.has("meanings") && !core.getString("dialog_state").equals("user_dictates")) {
                logUnrecognizedSpeech(msg.getString("content"), null);
                synthAndSpeak(output, "Omlouvám se, nerozumím vašemu požadavku.", core.getString("active_dialog_module"));
                terminate(output, self.getDescription());
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for text dictation", (core, msg, output, self) -> {
            if (msg.getCode() == SPEECH && core.getString("dialog_state").equals("user_dictates")) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                // if the core state is missing, create it as empty string state
                if (!core.has("dictated_text_buffer")) {
                    core.setState("dictated_text_buffer", Core.ID, "");
                    Logger.trace(self.getDescription(), "State \"dictated_text_buffer\" was created.");
                }

                // append recognized text to buffer
                core.setState("dictated_text_buffer", Core.ID, core.getString("dictated_text_buffer") + msg.getString("content"));
                Logger.trace(self.getDescription(), "Appended " + msg.getString("content")
                        + " to \"dictated_text_buffer\". Current value: " + core.getString("dictated_text_buffer"));

                // ask user if they want to dictate some more
                synthAndSpeak(output, "Zaznamenáno. Chcete pokračovat v diktování?", core.getString("active_dialog_module"));
                Logger.trace(self.getDescription(), "Confirmation question (for continuing dictating) asked.");

                // set the states accordingly
                core.setState("last_confirmation_reason", Core.ID, "continue_dictation");
                Logger.trace(self.getDescription(), "State \"last_confirmation_reason\" set to \"continue_dictation\"");
                core.setState("dialog_state", Core.ID, "waiting_for_confirmation", 60, "waiting_for_command");
                Logger.trace(self.getDescription(), "State \"dialog_state\" set to \"waiting_for_confirmation\".");

                terminate(output, self.getDescription()); // otherwise this speech will trigger next rules as well
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for setting last email title and address", (core, msg, output, self) -> {
            if (msg.getCode() == EMAIL) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                core.setState("last_email_title", Core.ID, msg.getString("title"));
                core.setState("last_email_sender_address", Core.ID, msg.getString("authorAddress"));
                Logger.info(self.getDescription(), "Last email title and sender address was set.");
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for activating dialog manager based on user initiation", (core, msg, output, self) -> {
            // if user initiates new dialog, activate the dialog manager that was user used
            if (msg.getCode() == SPEECH && core.getString("dialog_state").equals("waiting_for_command")) {
                core.setState("active_dialog_module", Core.ID, msg.getOrigin());
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for confirmation handling", (core, msg, output, self) -> {
            if (msg.getCode() == SPEECH && core.getString("dialog_state").equals("waiting_for_confirmation")) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                List<String> meanings = extractMeanings(msg);
                boolean confirmed;
                if (meanings.stream().anyMatch(s -> s.contains("yes"))
                        || meanings.stream().anyMatch(s -> s.contains("good"))
                        || meanings.stream().anyMatch(s -> s.contains("ok"))
                        || meanings.stream().anyMatch(s -> s.contains("clear"))) {
                    confirmed = true;
                    Logger.trace(self.getDescription(), "Confirmation POSITIVE");
                } else if (meanings.stream().anyMatch(s -> s.contains("no"))
                        || meanings.stream().anyMatch(s -> s.contains("cancel"))
                        || meanings.stream().anyMatch(s -> s.contains("wait"))
                        || meanings.stream().anyMatch(s -> s.contains("back"))) {
                    confirmed = false;
                    Logger.trace(self.getDescription(), "Confirmation NEGATIVE");
                } else {
                    Logger.warn(self.getDescription(), "There is no YES/NO meaning in this message: " + msg);
                    return;
                }

                switch (core.getString("last_confirmation_reason")) {
                    case "continue_dictation" -> {
                        if (confirmed) {
                            core.setState("dialog_state", Core.ID, "user_dictates");
                            synthAndSpeak(output, "Můžete pokračovat v diktování.", core.getString("active_dialog_module"));
                            Logger.trace(self.getDescription(), "Notice for user to continue dictating was send to synthesize and say out loud.");
                        } else {
                            core.setState("dialog_state", Core.ID, "waiting_for_command");
                            switch (core.getString("last_dictation_reason")) {
                                case "respond_to_email" -> {
                                    String newID = UUID.randomUUID().toString();
                                    core.setState(newID, Core.ID, true);
                                    core.setState("last_cancellable_task", Core.ID, "respond_to_email");
                                    core.setState("last_cancellable_task_ID", Core.ID, newID);
                                    bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule for cancellable email response sending",
                                            1, 60_000, (counter1, core1, msg1, out1, self1) -> {
                                        Logger.debug("---------------------------- looking for:" + newID + ", Found: " + msg1.getID());
                                        if (msg1.getID().equals(newID)) { // received trigger message
                                            Logger.trace(self1.getDescription(), "TRIGGERED by:" + msg1.getID());
                                            if (core1.getBool(newID)) { // the state is still true => task was not cancelled
                                                MyMessage mailMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, EMAIL);
                                                mailMsg.content.put("author", "tom.lebeda@gmail.com");
                                                mailMsg.content.put("receiver", core1.getString("last_email_sender_address"));
                                                mailMsg.content.put("description", "RE: " + core1.getString("last_email_title"));
                                                mailMsg.content.put("content", core1.getString("dictated_text_buffer"));
                                                out1.add(new RuleOutput(false, 30_000, mailMsg, "email_sender", MessageDestinationType.QUEUE));
                                            } else {
                                                Logger.trace(self1.getDescription(), "Task was cancelled, nothing was sent.");
                                            }
                                            core1.delState(newID); // the generated state is no longer needed
                                        }
                                    }));
                                    sendBackAfter(output, new MyMessage(newID, Core.ID, DUMMY), 20);
                                    synthAndSpeak(output, "Odpověď bude odeslána.", core.getString("active_dialog_module"));
                                }
                                default -> {
                                    Logger.warn(self.getDescription(), "Unknown value of \"last_dictation_reason\": " + msg.getString("last_dictation_reason"));
                                }
                            }
                        }
                    }
                    case "select_last_unread_mail" -> {
                        if (confirmed) {
                            String newID = UUID.randomUUID().toString();
                            MyMessage fetchMsg = new MyMessage(newID, Core.ID, FETCH);
                            fetchMsg.content.put(MessageHeader.FILTER_TYPE.getValue(), "new_unread");
                            output.add(new RuleOutput(false, 5000, fetchMsg, "email_reader", MessageDestinationType.QUEUE));
                            bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule for newest email fetch to respond",
                                    1, 10000, (counter1, core1, msg1, out1, self1) -> {
                                if (msg1.getCode() == EMAIL && msg1.has("respID") && msg1.getString("respID").equals(newID)) {
                                    String a = msg1.getString("authorAddress");
                                    core1.setState("last_email_sender_address", Core.ID, a);
                                    synthAndSpeak(out1, "Chcete nadiktovat odpověď na mail od odesílatele  " + a + "?", core.getString("active_dialog_module"));
                                    core1.setState("last_dictation_reason", Core.ID, "respond_to_email");
                                    core1.setState("last_confirmation_reason", Core.ID, "start_dictation");
                                    core1.setState("dialog_state", Core.ID, "waiting_for_confirmation");
                                    counter1.countDown();
                                }
                            }));
                        } else {
                            synthAndSpeak(output, "Zrušeno.", core.getString("active_dialog_module"));
                            core.setState("dialog_state", Core.ID, "waiting_for_command");
                        }
                    }
                    case "start_dictation" -> {
                        if (confirmed) {
                            core.setState("dialog_state", Core.ID, "user_dictates");
                            core.setState("dictated_text_buffer", Core.ID, "");
                            synthAndSpeak(output, "Můžete diktovat.", core.getString("active_dialog_module"));
                        } else {
                            core.setState("dialog_state", Core.ID, "waiting_for_command");
                            synthAndSpeak(output, "Zrušeno.", core.getString("active_dialog_module"));
                        }
                    }
                    default -> {
                        Logger.warn(self.getDescription(), "Unknown value of \"last_confirmation_reason\": " + msg.getString("last_confirmation_reason"));
                    }
                }
                terminate(output, self.getDescription()); // otherwise, the same speech will fall into "waiting_for_command" Rule and trigger confirmation/cancellation
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for handling spoken responses", (core, msg, output, self) -> {
            if (msg.getCode() == SPEECH && core.getString("dialog_state").equals("waiting_for_response")) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                List<String> meanings = extractMeanings(msg);
                String meaning = guessResponseMeaning(meanings);
                List<String> selectors = extractSelectors(msg, true);

                int unreadEmailCount;
                try {
                    unreadEmailCount = core.getNumberList("unread_email_IDs").size();
                } catch (Exception e) {
                    Logger.warn(self.getDescription(), "Failed to get number of unread emails from ID list: " + e.getMessage());
                    unreadEmailCount = 10_000; // this should cover all emails in mailbox, the rule that it is used in will be deleted by time out
                }
                switch (meaning) {
                    case "get_text" -> {
                        Logger.trace(self.getDescription(), "User wants to know text of email.");
                        String newID = UUID.randomUUID().toString();
                        MyMessage fetchMsg = new MyMessage(newID, Core.ID, FETCH);
                        Logger.debug("SELECTORS: " + selectors);
                        if (selectors.isEmpty()) {
                            fetchMsg.content.put("filterType", "all_unread");
                        } else {
                            fetchMsg.content.put("filterType", "custom");
                            fetchMsg.content.put("filter", selectors);
                        }
                        output.add(new RuleOutput(false, 10_000, fetchMsg, "email_reader", MessageDestinationType.QUEUE));
                        bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule for reading EMAIL.text from search",
                                unreadEmailCount, 10 * 60 * 1000, (counter1, core1, msg1, out1, self1) -> {
                            if (msg1.has("respID") && msg1.getString("respID").equals(newID)) {
                                if (msg1.getCode() == EMAIL) {
                                    String tts = msg1.getString("content");
                                    synthAndSpeak(out1, tts, core.getString("active_dialog_module"));
                                    counter1.countDown();
                                } else if (msg1.getCode() == REPORT && msg1.getString("content").equals("empty_search")) {
                                    synthAndSpeak(out1, "Žádný takový email nebyl nalezen.", core.getString("active_dialog_module"));
                                    counter1.nullify(); // this rule will no longer be needed => nullify counter to delete it
                                } else {
                                    Logger.warn(self1.getDescription(), "Unrecognized message: " + msg1.fullString());
                                }
                            }
                        }));
                    }
                    case "get_sender" -> {
                        Logger.trace(self.getDescription(), "User wants to know sender.");
                        String newID = UUID.randomUUID().toString();
                        MyMessage fetchMsg = new MyMessage(newID, Core.ID, FETCH);
                        if (selectors.isEmpty()) {
                            fetchMsg.content.put("filterType", "all_unread");
                        } else {
                            fetchMsg.content.put("filterType", "custom");
                            fetchMsg.content.put("filter", selectors);
                        }
                        output.add(new RuleOutput(false, 10_000, fetchMsg, "email_reader", MessageDestinationType.QUEUE));
                        bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule for reading EMAIL.sender from search",
                                unreadEmailCount, 10 * 60 * 1000, (counter1, core1, msg1, out1, self1) -> {
                            if (msg1.has("respID") && msg1.getString("respID").equals(newID)) {
                                if (msg1.getCode() == EMAIL) {
                                    String email_sender = msg1.getString("author");
                                    String tts2 = "Odesílatel je:";
                                    if (email_sender.equals("")) {
                                        email_sender = msg1.getString("authorAddress");
                                        tts2 = "Adresa odesílatele je:";
                                    }
                                    synthAndSpeak(out1, tts2 + email_sender, core1.getString("active_dialog_module"));
                                    counter1.countDown();
                                } else if (msg1.getCode() == REPORT && msg1.getString("content").equals("empty_search")) {
                                    synthAndSpeak(out1, "Žádný takový email nebyl nalezen.", core1.getString("active_dialog_module"));
                                    counter1.nullify(); // this rule will no longer be needed => nullify counter to delete it
                                } else {
                                    Logger.warn(self1.getDescription(), "Unrecognized message: " + msg1.fullString());
                                }
                            }
                        }));
                    }
                    case "get_subject" -> {
                        Logger.trace(self.getDescription(), "User wants to know subject.");
                        String newID = UUID.randomUUID().toString();
                        MyMessage fetchMsg = new MyMessage(newID, Core.ID, FETCH);
                        if (selectors.isEmpty()) {
                            fetchMsg.content.put("filterType", "all_unread");
                        } else {
                            fetchMsg.content.put("filterType", "custom");
                            fetchMsg.content.put("filter", selectors);
                        }
                        output.add(new RuleOutput(false, 10_000, fetchMsg, "email_reader", MessageDestinationType.QUEUE));
                        bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule for reading EMAIL.subject from search",
                                unreadEmailCount, 10 * 60 * 1000, (counter1, core1, msg1, out1, self1) -> {
                            if (msg1.has("respID") && msg1.getString("respID").equals(newID)) {
                                if (msg1.getCode() == EMAIL) {
                                    String subj = msg1.getString("title");
                                    if (subj.startsWith("[") && subj.endsWith("]")) {
                                        subj = "Mail nemá žádný předmět.";
                                    }
                                    synthAndSpeak(out1, subj, core1.getString("active_dialog_module"));
                                    counter1.countDown(); // the rule was used => count down
                                } else if (msg1.getCode() == REPORT && msg1.getString("content").equals("empty_search")) {
                                    synthAndSpeak(out1, "Žádný takový email nebyl nalezen.", core1.getString("active_dialog_module"));
                                    counter1.nullify(); // this rule will no longer be needed => nullify counter to delete it
                                } else {
                                    Logger.warn(self1.getDescription(), "Unrecognized message: " + msg1.fullString());
                                }
                            }
                        }));
                    }
                    case "open" -> {
                        Logger.trace(self.getDescription(), "User wants to open email in browser.");
                        MyMessage openMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MessageCode.OPEN_BROWSER);
                        openMsg.content.put(MessageHeader.LINK.getValue(), "tom.lebeda@gmail.com");
                        output.add(new RuleOutput(false, 3_000, openMsg, "desktop_watcher", MessageDestinationType.QUEUE));
                        core.setState("dialog_state", Core.ID, "waiting_for_command");
                    }
                    case "delay" -> {
                        Logger.trace(self.getDescription(), "User wants to delay.");
                        String newID = UUID.randomUUID().toString();
                        sendBackAfter(output, new MyMessage(newID, Core.ID, DUMMY), 1000 * 60 * 30);
                        core.setState("dialog_state", Core.ID, "waiting_for_command");
                        bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule waiting for forced-email-check trigger message with ID" + newID,
                                1, 1000 * 60 * 31, (counter1, core1, msg1, out1, self1) -> {
                            if (msg1.getID().equals(newID)) {
                                MyMessage forceCheckMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, FORCE_CHECK);
                                out1.add(new RuleOutput(false, 10_000, forceCheckMsg, "email_reader", MessageDestinationType.QUEUE));
                            }
                        }));
                    }
                    case "respond" -> {
                        Logger.trace(self.getDescription(), "User wants to respond.");
                        String a;
                        if (core.has("last_email_sender_address")) {
                            a = core.getString("last_email_sender_address");
                            synthAndSpeak(output, "Chcete nadiktovat odpověď na mail od odesílatele " + a + "?", core.getString("active_dialog_module"));
                            core.setState("last_dictation_reason", Core.ID, "respond_to_email");
                            core.setState("last_confirmation_reason", Core.ID, "start_dictation");
                            core.setState("dialog_state", Core.ID, "waiting_for_confirmation");
                        } else {
                            synthAndSpeak(output, "Není vybrán žádný mail. Vybrat nejnovější nepřečtený?", core.getString("active_dialog_module"));
                            core.setState("dialog_state", Core.ID, "waiting_for_confirmation");
                            core.setState("last_confirmation_reason", Core.ID, "select_last_unread_mail");
                        }
                    }
                    case "mark_as_read" -> {
                        Logger.trace(self.getDescription(), "User wants to mark emails as read.");
                        MyMessage markerMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, MARK_AS_READ);
                        selectors = extractSelectors(msg, false);
                        if (selectors.isEmpty()) {
                            markerMsg.content.put("filterType", "all_unread");
                        } else {
                            markerMsg.content.put("filterType", "custom");
                            if (!selectors.contains("seen:false")) {
                                selectors.add("seen:false");
                            }
                            markerMsg.content.put("filter", selectors);
                        }
                        output.add(new RuleOutput(false, 10_000, markerMsg, "email_reader", MessageDestinationType.QUEUE));
                        if (unreadEmailCount > 1) {
                            synthAndSpeak(output, "Maily byly označeny jako přečtené.", core.getString("active_dialog_module"));
                        } else {
                            synthAndSpeak(output, "Mail byl označen jako přečtený.", core.getString("active_dialog_module"));
                        }
                        core.setState("dialog_state", Core.ID, "waiting_for_command");
                    }
                    case "acknowledge" -> {
                        Logger.trace(self.getDescription(), "User acknowledged notification.");
                        core.setState("dialog_state", Core.ID, "waiting_for_command");
                    }
                    default -> {
                        Logger.warn(self.getDescription(), "Unknown response meaning: " + meaning);
                    }
                }
                terminate(output, self.getDescription());
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule that handles spoken commands", (core, msg, output, self) -> {
            if (msg.getCode() == SPEECH && core.getString("dialog_state").equals("waiting_for_command")) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                List<String> meanings = extractMeanings(msg);
                String spokenText = msg.getString("content");
                if (meanings.stream().anyMatch(s -> s.contains("cancel"))
                        || meanings.stream().anyMatch(s -> s.contains("no"))
                        || meanings.stream().anyMatch(s -> s.contains("back"))
                        || meanings.stream().anyMatch(s -> s.contains("wait"))) {
                    if (core.getString("last_cancellable_task").equals("respond_to_email")) {
                        core.setState(core.getString("last_cancellable_task_ID"), Core.ID, false);
                        synthAndSpeak(output, "Odeslání bylo zrušeno.", core.getString("active_dialog_module"));
                    }
                } else if (meanings.stream().anyMatch(s -> s.contains("check"))
                        || meanings.stream().anyMatch(s -> s.contains("unread"))
                        || meanings.stream().anyMatch(s -> s.contains("unseen"))
                        || meanings.stream().anyMatch(s -> s.contains("unseen"))
                        || meanings.stream().anyMatch(s -> s.contains("unsettled"))) {
                    // command to force-check email
                    Logger.trace(self.getDescription(), "User wants to force-check email.");
                    MyMessage forceCheckMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, FORCE_CHECK);
                    output.add(new RuleOutput(false, 10_000, forceCheckMsg, "email_reader", MessageDestinationType.QUEUE));
                } else if (meanings.stream().anyMatch(s -> s.contains("open"))
                        || meanings.stream().anyMatch(s -> s.contains("show"))) {
                    if (spokenText.contains("prohlížeč") || spokenText.contains("internet")) {
                        Logger.trace(self.getDescription(), "User wants to open browser");
                        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, OPEN_BROWSER);
                        output.add(new RuleOutput(false, 3_000, newMsg, "desktop_watcher", MessageDestinationType.QUEUE));
                    } else if (spokenText.contains("e-mail") || spokenText.contains("mail") || spokenText.contains("poštu") || spokenText.contains("schránku")) {
                        MyMessage newMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, OPEN_BROWSER);
                        newMsg.content.put("link", "www.gmail.com");
                        Logger.trace(self.getDescription(), "User wants to open email in browser");
                        output.add(new RuleOutput(false, 3_000, newMsg, "desktop_watcher", MessageDestinationType.QUEUE));
                    } else if (spokenText.contains("stažené soubor") || spokenText.contains("stažený soubor")) {
                        Logger.trace(self.getDescription(), "User wants to open file browser in folder Downloads");
                        MyMessage cmd = new MyMessage(UUID.randomUUID().toString(), Core.ID, OPEN_FILE_EXPLORER);
                        cmd.content.put("link", "/home/tom/Downloads");
                        output.add(new RuleOutput(false, 3_000, cmd, "desktop_watcher", MessageDestinationType.QUEUE));
                    } else if (spokenText.contains("průzkumníka") || spokenText.contains("soubor")) {
                        Logger.trace(self.getDescription(), "User wants to open file browser");
                        MyMessage cmd = new MyMessage(UUID.randomUUID().toString(), Core.ID, OPEN_FILE_EXPLORER);
                        cmd.content.put("link", "/home/tom/");
                        output.add(new RuleOutput(false, 3_000, cmd, "desktop_watcher", MessageDestinationType.QUEUE));
                    }
                }
            }
        }));
//
//        rulebook.add(new RuleSimple(coreStateMap, "Rule that tells UNREAD_EMAILS notification if appropriate", (core, msg, output, self) -> {
//            if (msg.getCode() == UNREAD_EMAILS) {
//                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
//                boolean speak = (core.has("user_active") && !core.getBool("user_active")) && (core.has("dialog_ready") && core.getBool("dialog_ready"));
//                if (!speak) {
//                    sendDesktopNotification(msg, output);
//                    terminate(output, self.getDescription());
//                }
//            }
//        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule that suppresses UNREAD_EMAILS notification based on daytime", (core, msg, output, self) -> {
            if (msg.getCode() == UNREAD_EMAILS) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                if (LocalDateTime.now().getHour() < 8 || LocalDateTime.now().getHour() > 20) {

                    String newID = UUID.randomUUID().toString();
                    sendBackTomorrow(new MyMessage(newID, Core.ID, DUMMY), output, 10);
                    bufferRule(new RuleTimeoutCountdown(coreStateMap, "Generated rule waiting for forced-email-check trigger message with ID" + newID,
                            1, 1000 * 60 * 31, (counter1, core1, msg1, out1, self1) -> {
                        if (msg1.getID().equals(newID)) {
                            MyMessage forceCheckMsg = new MyMessage(UUID.randomUUID().toString(), Core.ID, FORCE_CHECK);
                            out1.add(new RuleOutput(false, 10_000, forceCheckMsg, "email_reader", MessageDestinationType.QUEUE));
                        }
                    }));

                    sendBackTomorrow(msg, output, 10);
                    Logger.trace(self.getDescription(), "Suppressed UNREAD_EMAIL notification.");
                    terminate(output, self.getDescription());
                }
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule that displays UNREAD_EMAILS notification if speaking is not desired", (core, msg, output, self) -> {
            if (msg.getCode() == UNREAD_EMAILS) {
                // speaking is NOT desired only if no speech module is available
                if (!core.has("active_dialog_module")) {
                    sendDesktopNotification(msg, output);
                    terminate(output, self.getDescription());
                }
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule that handles UNREAD_EMAILS notification", (core, msg, output, self) -> {
            if (msg.getCode() == UNREAD_EMAILS) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                List<Double> ids = msg.getNumberList("content"); // get mail IDs from message
                Logger.debug("IDS: " + ids);

                int mailCount = ids.size(); // count unread mails
                core.setState("unread_email_IDs", Core.ID, ids);
                core.setState("default_unread_mails", Core.ID, true, 5 * 60 * 1000, false);
                core.setState("default_all_mails", Core.ID, true, 5 * 60 * 1000, false);

                // generate appropriate notification string
                String tts;
                if (mailCount == 1) {
                    tts = "Máte nepřečtený mail.";
                } else if (mailCount > 1 && mailCount < 5) {
                    tts = String.format("Máte %d nepřečtené maily.", mailCount);
                } else {
                    tts = String.format("Máte %d nepřečtených mailů.", mailCount);
                }

                // select which dialog module should be used for new notification
                if (core.getStringList("living_modules").contains("voice_kit")) {
                    // voice kit has priority over browser module => if voice kit is active, override whatever was there before
                    core.setState("active_dialog_module", Core.ID, "voice_kit");
                }

                synthAndSpeak(output, tts, core.getString("active_dialog_module"));
                core.setState("dialog_state", Core.ID, "waiting_for_response", 1000 * 60 * 5, "waiting_for_command");
                core.setState("response_topic", Core.ID, "unread_emails");
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for delaying NEW_RSS if daytime is bad", (core, msg, output, self) -> {
            if (msg.getCode() == NEW_RSS) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                if (LocalDateTime.now().getHour() < 8 || LocalDateTime.now().getHour() > 22) {
                    sendBackTomorrow(msg, output, 10);
                    Logger.trace(self.getDescription(), "RSS delayed for tomorrow.");
                    terminate(output, self.getDescription());
                }
            }
        }));

        rulebook.add(new RuleSimple(coreStateMap, "Rule for NEW_RSS that sends a desktop notification", (core, msg, output, self) -> {
            if (msg.getCode() == NEW_RSS) {
                Logger.trace(self.getDescription(), "TRIGGERED by:" + msg.getID());
                sendDesktopNotification(msg, output);
                Logger.trace(self.getDescription(), "Notification sent.");
            }
        }));

    }

    private void prolongMailSessionTimeout(CoreStateMap core, List<RuleOutput> output, String sessionID) throws InvalidTypeException {
        if (core.has("last_email_session_timeout_msg_id")) {
            MyMessage removeOldTimeoutMsg = new MyMessage(UUID.randomUUID().toString(), "core", FETCH);
            removeOldTimeoutMsg.content.put(MessageHeader.FILTER.getValue(), core.getString("last_email_session_timeout_msg_id"));
            removeOldTimeoutMsg.content.put(MessageHeader.REQUEST_TYPE.getValue(), "delID");
            output.add(new RuleOutput(false, 5000, removeOldTimeoutMsg, "message_vault", MessageDestinationType.QUEUE));
        }
        MyMessage timeoutMsg = new MyMessage(UUID.randomUUID().toString(), "core", DELETE_STATE); // prepare message for deleting state
        timeoutMsg.content.put(MessageHeader.TITLE.getValue(), sessionID); // the state that should be deleted is the session ID
        sendBackAfter(output, timeoutMsg, 3 * 60); // send the message for deleting state to message vault to be sent back after 3 minutes
        core.setState("last_email_session_timeout_msg_id", "core", timeoutMsg.getID());
    }

    private void logUnrecognizedSpeech(String spokenText, JSONArray meanings) {
        String m;
        if (meanings == null) {
            m = "[]";
        } else {
            m = meanings.toString();
        }
        String log = Logger.timeStamp() + " SPOKEN TEXT: " + spokenText + " -> RECOGNIZED: " + m + "\n";
        try (FileOutputStream fo = new FileOutputStream("speech.log", true)) {
            fo.write(log.getBytes(StandardCharsets.UTF_8));
            Logger.trace(this.getClass().getName(), "Logged unrecognized speech: " + log);
        } catch (IOException e) {
            Logger.error(this.getClass().getName(), "Failed to log unrecognized speech: " + e.getMessage());
        }
    }

    private List<String> extractMeanings(MessageStateMap msg) {
        JSONArray meaningsRaw = msg.getContent().getJSONArray(MessageHeader.MEANINGS.getValue());
        List<String> meanings = new ArrayList<>();
        for (int i = 0; i < meaningsRaw.length(); i++) {
            meanings.add(meaningsRaw.getString(i));
        }
//        for (int i = 0; i < meaningsRaw.length(); i++) {
//            int start = meanings.get(i).indexOf("MEANING_")+"MEANING_".length();
//            String s = meaningsRaw.getString(i).substring(meaningsRaw.getString(i).indexOf("MEANING") + "MEANING_".length()).trim();
//            if (!meanings.contains(s)) {
//                meanings.add(s);
//            }
//        }
        return meanings;
    }

    private List<String> extractSelectors(MessageStateMap msg, boolean checkSeen) throws InvalidTypeException {
        JSONArray selectorsRaw = msg.getContent().getJSONArray(MessageHeader.MEANINGS.getValue());
        List<String> output = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        List<String> meanings = new ArrayList<>();

        // split meanings into categories
        for (int i = 0; i < selectorsRaw.length(); i++) {
            String s = selectorsRaw.getString(i);
            if (s.contains("NUMBER")) {
                // put every item that contains NUMBER into number list, numbers can be duplicit
                numbers.add(s);
            } else if (s.contains("MEANING_name") && !names.contains(s)) {
                // put every item that contains MEANING_name into name list, without duplicates
                names.add(s);
            } else if (s.contains("MEANING") && !meanings.contains(s)) {
                // put every other item into general meaning list, without duplicates
                meanings.add(s);
            }
        }

        // handle SEEN selector
        if (checkSeen) {
            int seenFlag = 0; // 0 means whatever, 1 means only seen, -1 means only unread
            if (coreStateMap.getBool("default_unread_mails", false)) {
                // if the default_unread_mails flag is active, set the seen flag to -1 (unread)
                seenFlag = -1;
            }
            for (String m : meanings) {
                // explicitly stating what user wants has priority over default values => override
                if (m.contains("MEANING_seen") || m.contains("WORD_přečtený") || m.contains("MEANING_settled")) {
                    // NOTE: MEANING_read collides with command to read given email, so it needs to be more specific -> WORD_přečtený
                    seenFlag = 1;
                    break;
                } else if (m.contains("MEANING_unseen") || m.contains("MEANING_unread") || m.contains("MEANING_unsettled")) {
                    output.add("seen:false");
                    seenFlag = -1;
                    break;
                }
            }
            // if the selector is not neutral, add that value to output
            if (seenFlag == 1) {
                output.add("seen:true");
            } else if (seenFlag == -1) {
                output.add("seen:false");
            }
        }

        // handle NAMES selector
        if (!names.isEmpty()) {
            String s = names.get(0); // use the first name that was recognized
            int begin = s.indexOf("WORD_");
            int end = s.indexOf(":");
            if (end == -1 || begin == -1) {
                Logger.warn(this.getClass().getName(), "Found name, but parsing failed (indexOf returned -1).");
            } else {
                // if the parsing is successful, add the name to the selector output
                output.add("from:" + s.substring(begin + "NAME_".length(), end).strip());
            }
        }

        // handles SORTING (old/new) selector
        boolean checkCountDefaults = true;
        for (String m : meanings) {
            if (m.contains("MEANING_new")) {
                output.add("sort:new");
                break;
            } else if (m.contains("MEANING_last")) {
                output.add("sort:new");
                checkCountDefaults = false; // explicitly stating "last email" has priority over default count flag
                break;
            } else if (m.contains("MEANING_old")) {
                output.add("sort:old");
                break;
            } else if (m.contains("MEANING_first")) {
                output.add("sort:old");
                checkCountDefaults = false; // explicitly stating "first email" has priority over default count flag
                break;
            }
        }

        // handle NUMBER selector
        int selectorCount = 1; // 1 is the default value in gmail_read module if no count selector is provided
        if (checkCountDefaults) {
            if (coreStateMap.getBool("default_all_mails", false)) {
                // if there is no explicitly specified numbers and default_all_mails flag is active, set the selector to 10k emails (that should cover all of them)
                selectorCount = 10_000;
            }
        }

        if (!numbers.isEmpty()) {
            // explicit specification has priority over default values -> default values are evaluated if number list is empty
            int num = 0; // some numbers are present, so the result should not remain zero
            for (String numberString : numbers) {
                // extract the end-bit of the string to get ready to parse
                String end = numberString.substring(numberString.indexOf("_") + 1);//, numberString.indexOf(":"));
                try {
                    // try parsing the number string into integer
                    int endN = Integer.parseInt(end);
                    Logger.debug("--------------------NUMBER: " + endN);
                    // if the parsing passed, add the number to given output
                    // NOTE: this algorithm assumes that user will say single number 1-9 or 11-19 or 10,20,30...90 or 10+1, 10+2, 10+3, ..., 20+1, 20+2, ... 90+9
                    // if other number-sequence is given, the result may not be what user expects
                    num += endN;
                } catch (Exception e) {
                    Logger.warn(this.getClass().getName(), "Parsing integer failed.");
                }
            }
            selectorCount = num; // apply the result

        }

        for (String meaning : meanings) {
            if (meaning.contains("WORD_all") || meaning.contains("WORD_everything")) {
                selectorCount = 10_000;
            }
        }

        output.add("count:" + selectorCount);

        return output;
    }

    private String guessResponseMeaning(List<String> meanings) {
        if (meanings.stream().anyMatch(s -> s.contains("who"))
                || meanings.stream().anyMatch(s -> s.contains("sender"))
                || meanings.stream().anyMatch(s -> s.contains("author"))) {
            return "get_sender";
        } else if (meanings.stream().anyMatch(s -> s.contains("subject"))
                || meanings.stream().anyMatch(s -> s.contains("title"))
                || meanings.stream().anyMatch(s -> s.contains("WORD_které"))
                || meanings.stream().anyMatch(s -> s.contains("about"))) {
            return "get_subject";
        } else if ((meanings.stream().noneMatch(s -> s.contains("mark")) && meanings.stream().noneMatch(s -> s.contains("response")))
                && (meanings.stream().anyMatch(s -> s.contains("content"))
                || meanings.stream().anyMatch(s -> s.contains("text"))
                || meanings.stream().anyMatch(s -> s.contains("_read")) // there is also MEANING_unread so the underscore has meaning
                || (meanings.stream().anyMatch(s -> s.contains("what")) && meanings.stream().anyMatch(s -> s.contains("write"))))) {
            return "get_text";
        } else if (meanings.stream().anyMatch(s -> s.contains("open"))
                || meanings.stream().anyMatch(s -> s.contains("show"))) {
            return "open";
        } else if (meanings.stream().anyMatch(s -> s.contains("remind"))
                || meanings.stream().anyMatch(s -> s.contains("postpone"))
                || meanings.stream().anyMatch(s -> s.contains("wait"))
                || meanings.stream().anyMatch(s -> s.contains("later"))) {
            return "delay";
        } else if (meanings.stream().anyMatch(s -> s.contains("mark"))) {
            return "mark_as_read";
        } else if (meanings.stream().anyMatch(s -> s.contains("ok"))
                || (meanings.stream().anyMatch(s -> s.contains("good")))
                || (meanings.stream().anyMatch(s -> s.contains("clear")))
                || (meanings.stream().anyMatch(s -> s.contains("understand")))) {
            return "acknowledge";
        } else if (meanings.stream().noneMatch(s -> s.contains("get"))
                && (meanings.stream().anyMatch(s -> s.contains("response"))
                || meanings.stream().anyMatch(s -> s.contains("write")))) {
            return "respond";
        }
        return "unrecognized";
    }

    public void setup() {
        loadAllRules();
    }

    public ArrayList<Rule> getRelevantRules(MyMessage msg) {
        return rulebook;
    }

    public void link(Core core, MessageProcessor messageProcessor) {
        this.coreStateMap = core.getStateMap();
        this.messageProcessor = messageProcessor;
    }

    private void bufferRule(int index, Rule rule) {
        waitingRules.add(index, rule);
    }

    private void bufferRule(Rule rule) {
        waitingRules.add(rule);
    }

    public void cleanUpRules() {
        rulebook.removeIf(Rule::toDelete);
        rulebook.addAll(waitingRules);
        waitingRules = new ArrayList<>();
    }
}
