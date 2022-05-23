package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"fmt"
	"github.com/google/uuid"
	"github.com/valyala/fastjson"
	"os"
	"os/exec"
	"strconv"
	"time"
)

const myID = "MODULE_ID"
const passwd = "PASSWORD"

var thresholdIdle = 30_000
var userActive = false

func main() {
	msgQueue := make(chan string, 100)
	mqClient.Login = myID
	mqClient.Password = passwd
	go mqClient.Listen("broadcast", msgQueue)
	go mqClient.Listen(myID, msgQueue)
	mqClient.Enroll(myID, myID)
	go func() {
		for {
			checkUserActivity(false)
			time.Sleep(500 * time.Millisecond)
		}
	}()
	for {
		processMessage(<-msgQueue)
	}
}

func checkUserActivity(forceUpdate bool) {
	out, err := exec.Command("xprintidle").Output()
	if err != nil {
		Logger.Err("Error occurred while executing \"xprintidle\":", err.Error())
		return
	}
	idleTime, err := strconv.Atoi(string(out[:len(out)-1]))
	if err != nil {
		Logger.Err("Unable to parse xprintidle output to int:", err.Error())
		return
	}
	currentlyActive := idleTime <= thresholdIdle
	change := currentlyActive != userActive
	if change || forceUpdate {
		userActive = currentlyActive
		msg := []byte(fmt.Sprintf("{"+
			"\"msgID\":\"%s\","+
			"\"msgOrigin\":\"desktop_watcher\","+
			"\"msgType\":\"data\","+
			"\"msgCode\":\"set_state\","+
			"\"title\":\"user_active\","+
			"\"type\":\"boolean\","+
			"\"content\":%t"+
			"}", uuid.New().String(), userActive))
		mqClient.Send("core", &msg, 500)
	}
}

func processMessage(msg string) {
	Logger.Trace("Parsing message: ", msg)
	var parser fastjson.Parser
	obj, err := parser.Parse(msg)
	if err != nil {
		Logger.Err("Failed to parse incoming message. Error message:", err.Error())
		return
	}
	msgTypeBytes := obj.GetStringBytes(mqClient.MESSAGE_TYPE)
	if msgTypeBytes == nil {
		Logger.Err("Unable to process message, because msgType was not found. Skipping.")
		return
	}
	msgCode := obj.GetStringBytes(mqClient.MESSAGE_CODE)
	if msgCode == nil {
		Logger.Err("Unable to process message, because msgCode was not found. Skipping.")
		return
	}

	// message is ready to be processed
	code := string(msgCode)
	msgType := string(msgTypeBytes)

	if msgType == "data" {
		if !mqClient.EnrollAcknowledged {
			Logger.Warn("Received data message to process, but enroll protocol hasn't finished yet. I will ignore that for now and proceed normally.")
		}
		switch code {
		case "open_browser":
			link := obj.GetStringBytes("link")
			err := exec.Command("vivaldi-stable", string(link)).Start()
			if err != nil {
				Logger.Err("Exception during xdg-open:", err.Error())
			}
		case "open_file_explorer":
			link := obj.GetStringBytes("link")
			Logger.Trace("Received request to open file explorer on location", string(link))
			err := exec.Command("thunar", string(link)).Start()
			if err != nil {
				Logger.Err("Exception during thunar:", err.Error())
			}
		default:
			Logger.Warn("Received data message to process, but this module is not supposed to receive any data messages. Message will be ignored.")
		}
		return
	} else if msgType == "service" {
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
				checkUserActivity(true)
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
		Logger.Err("Received message with unknown type: ", msgType, ". Message will be ignored.")
		return
	}
}
