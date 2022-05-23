package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"fmt"
	"github.com/esiqveland/notify"
	"github.com/godbus/dbus/v5"
	"github.com/google/uuid"
	"github.com/valyala/fastjson"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const myID = "MODULE_ID"
const myPass = "PASSWORD"

var dbusConn *dbus.Conn
var notifier notify.Notifier
var notificationStorage = make(map[uint32]notify.Notification)

func main() {
	var err error = nil

	// connect to dbus
	dbusConn, err = dbus.SessionBusPrivate()
	if err != nil {
		Logger.Fatal("Error while connecting to dbus:" + err.Error())
		os.Exit(1)
	}
	defer func(conn *dbus.Conn) {
		err := conn.Close()
		if err != nil {
			Logger.Err("Failed to close dbus connection:" + err.Error())
		}
	}(dbusConn)
	if err = dbusConn.Auth(nil); err != nil {
		Logger.Fatal("Failed to authenticate dbus connection: " + err.Error())
		os.Exit(1)
	}

	if err = dbusConn.Hello(); err != nil {
		Logger.Fatal("Failed to establish dbus connection:" + err.Error())
		os.Exit(1)
	}

	Logger.Info("Connection to dbus established.")

	// create notifier
	notifier, err = notify.New(dbusConn, notify.WithOnAction(onAction), notify.WithOnClosed(onClose))
	if err != nil {
		Logger.Fatal("Exception occurred while creating notifier:" + err.Error())
		os.Exit(1)
	}
	defer func(notifier notify.Notifier) {
		err := notifier.Close()
		if err != nil {
			Logger.Fatal("Failed to close notifier:" + err.Error())
			os.Exit(1)
		}
	}(notifier)
	Logger.Info("Notifier created.")

	sendNotification(notify.Notification{
		AppName:       myID,
		Summary:       "Desktop notifier started",
		ExpireTimeout: 5 * time.Second,
	})

	// connect to MQ and listen for messages
	msgQueue := make(chan string, 100)
	mqClient.Login = myID
	mqClient.Password = myPass
	go mqClient.Listen("broadcast", msgQueue)
	go mqClient.Listen(myID, msgQueue)
	mqClient.Enroll(myID, myID)
	Logger.Success("Setup finished.")
	for {
		processMessage(<-msgQueue)
	}
}

// Performs action pushed by user on the notification bubble.
func onAction(action *notify.ActionInvokedSignal) {
	if strings.HasPrefix(action.ActionKey, "link") {
		err := exec.Command("xdg-open", action.ActionKey[5:]).Start()
		if err != nil {
			Logger.Err("Exception during xdg-open:", err.Error())
		}
	} else if strings.HasPrefix(action.ActionKey, "delay") {
		// TODO: přesunout odkládání notifikací do podoby zpráv přes Core, neuchovávat zde
		go func() {
			notificationCopy := notificationStorage[action.ID]
			duration, err := time.ParseDuration(action.ActionKey[6:] + "s")
			if err != nil {
				Logger.Warn("Failed to parse delay duration, using default 30 minutes.")
				duration = 30 * time.Minute
			}
			time.Sleep(duration)
			sendNotification(notificationCopy)
		}()
	} else if strings.HasPrefix(action.ActionKey, "mark_as_read") {
		if len(action.ActionKey) < 12 {
			Logger.Err("Can not process clicked action, because email ID could not be extracted (key too short).")
			return
		}
		id := action.ActionKey[12:]
		msg := []byte(fmt.Sprintf("{"+
			"\"msgType\":\"data\","+
			"\"msgCode\":\"mark_as_read\","+
			"\"msgOrigin\":\"%s\","+
			"\"msgID\":\"%s\","+
			"\"filter\":\"mail\","+
			"\"itemID\":\"%s\","+
			"}", myID, uuid.New().String(), id))
		mqClient.Send("core", &msg, 30000)
		notification := notify.Notification{
			ReplacesID:    action.ID,
			Summary:       "Email byl označen jako přečtený.",
			ExpireTimeout: 1 * time.Second,
		}
		sendNotification(notification)
	}
}

// Performs cleanup of dead notifications.
func onClose(closed *notify.NotificationClosedSignal) {
	delete(notificationStorage, closed.ID)
}

func processMessage(msg string) {
	Logger.Trace("Processing message: ", msg)
	var parser fastjson.Parser
	obj, err := parser.Parse(msg)
	if err != nil {
		Logger.Err("Failed to parse message", msg, ". Skipping. Error message:", err.Error())
		return
	}
	msgType := obj.GetStringBytes(mqClient.MESSAGE_TYPE)
	if msgType == nil {
		Logger.Err("Unable to process message, because msgType was not found. Skipping.")
		return
	}
	msgCode := obj.GetStringBytes(mqClient.MESSAGE_CODE)
	if msgCode == nil {
		Logger.Err("Unable to process message, because msgCode was not found. Skipping.")
		return
	}
	code := string(msgCode)

	// module is acknowledged by Core
	if string(msgType) == "data" {
		if !mqClient.EnrollAcknowledged {
			Logger.Warn("Received data message to process, but enroll protocol hasn't finished yet. I will ignore that for now and proceed normally.")
		}
		if code == "notify" {
			Logger.Info("Received request for new notification.")
			title := obj.GetStringBytes("title")
			if title == nil {
				Logger.Err("Notification title missing. Cannot show notification without title. This error will be ignored and message skipped.")
				return
			}
			desc := obj.GetStringBytes("description")
			if desc == nil {
				desc = []byte("")
			}
			notification := notify.Notification{
				AppName:       myID,
				ReplacesID:    0,
				AppIcon:       "",
				Summary:       string(title),
				Body:          string(desc),
				Actions:       getActions(obj.GetArray("actions")),
				Hints:         nil,
				ExpireTimeout: getDuration(obj.GetInt("duration")),
			}
			sendNotification(notification)
		} else {
			Logger.Err("Received data message with code ", code, "but don't know what to do with it. Message will be ignored.")
			return
		}
	} else if string(msgType) == "service" {
		switch code {
		case "confirm":
			respID := obj.GetStringBytes("respID")
			if respID == nil {
				Logger.Err("Received confirmation message without response ID. Message will be ignored.")
				return
			}
			if string(respID) == mqClient.LastEnrollID {
				mqClient.EnrollAcknowledged = true
				Logger.Info("Enroll protocol finished.")
				return
			} else {
				Logger.Warn("Received confirmation from Core, but the respID is not recognized. Message will be ignored.")
				return
			}
		case "who":
			Logger.Trace("Received service message with code \"who\", initiating enroll protocol.")
			mqClient.Enroll(myID, myID)
		case "die":
			Logger.Trace("Received service message with code \"die\", initiating death protocol.")
			s := []byte(fmt.Sprintf("{\"msgID\":\"%s\","+
				"\"msgOrigin\":\"%s\","+
				"\"msgType\":\"service\","+
				"\"msgCode\":\"dying\","+
				"\"description\":\"Received service message with code: die\"}", uuid.New().String(), myID))
			mqClient.Send("core", &s, 0)
			Logger.Success("Received command to die and successfully finished death protocol. Quitting now, goodbye.")
			os.Exit(0) // kill the application
		default:
			Logger.Warn("Received service message with code", code, "but there is nothing to do with it. Message will be ignored. ")
		}
	} else {
		Logger.Err("Received message with unknown type: ", string(msgType), ". Message will be ignored.")
		return
	}
}

// Sends a new notification for desktop environment to show.
func sendNotification(notification notify.Notification) {
	if id, err := notifier.SendNotification(notification); err != nil {
		Logger.Err("Error during sending notification:", err.Error())
	} else {
		if notification.Actions != nil { // there is no need to save the notification if there are no actions linked to it
			notificationStorage[id] = notification
		}
	}
}

// Extracts a duration for notification
func getDuration(timeInt int) time.Duration {
	duration, err := time.ParseDuration(strconv.Itoa(timeInt) + "ms")
	if err != nil {
		Logger.Err("Failed to parse notification duration:", err.Error(), ". Default value of 10 seconds will be used.")
		return 10_000
	}
	return duration
}

// Extracts actions for given notification
func getActions(s []*fastjson.Value) []notify.Action {
	var out = make([]notify.Action, len(s))
	for i, item := range s {
		out[i] = notify.Action{
			Key:   string(item.GetStringBytes("key")),
			Label: string(item.GetStringBytes("text")),
		}
	}
	return out
}
