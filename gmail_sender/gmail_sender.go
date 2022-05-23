package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"fmt"
	"github.com/google/uuid"
	"github.com/valyala/fastjson"
	"net/smtp"
	"os"
	"strings"
)

const myID = "MODULE_ID"
const myPass = "PASSWORD"

const mailPass = "MAIL_PASSWORD"

func main() {
	msgQueue := make(chan string, 100)
	mqClient.Login = myID
	mqClient.Password = myPass
	go mqClient.Listen("broadcast", msgQueue)
	go mqClient.Listen(myID, msgQueue)
	mqClient.Enroll(myID, myID)
	Logger.Trace("Ready.")

	for {
		processMessage(<-msgQueue)
	}
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
		if code == "email" {
			sendEmailFromJSON(obj)
		} else {
			Logger.Err("Received data message but this module is not supposed to receive any. Message will be ignored.")
		}
		return
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

func sendEmailFromJSON(obj *fastjson.Value) {
	fb := obj.GetStringBytes("author")
	if fb == nil {
		Logger.Err("Can not send email, because author is nil.")
		return
	}
	from := string(fb)

	rb := obj.GetStringBytes("receiver")
	if rb == nil {
		Logger.Err("Can not send email, because receiver is nil.")
		return
	}
	receiver := string(rb)

	db := obj.GetStringBytes("description")
	subj := ""
	if db == nil || len(db) == 0 {
		Logger.Warn("Email will have empty Subject.")
	} else {
		subj = string(db)
	}

	cb := obj.GetStringBytes("content")
	content := ""
	if cb == nil || len(db) == 0 {
		Logger.Warn("Email will have empty Body.")
	} else {
		content = string(cb)
	}

	if content == "" && subj == "" {
		Logger.Warn("Sending empty mail is pointless, ignoring (nothing will be sent).")
		return
	}

	host := "smtp.gmail.com"
	if !strings.HasSuffix(from, "@gmail.com") {
		Logger.Err("Don't recognize smtp server, can not send email.")
		return
	} else {
		port := "587"

		msg := []byte(fmt.Sprintf("To: %s\r\n"+
			"Subject: %s\r\n"+
			"\r\n"+
			"%s.\r\n", receiver, subj, content))

		auth := smtp.PlainAuth("", from, mailPass, host)

		err := smtp.SendMail(host+":"+port, auth, from, []string{receiver}, msg)
		if err != nil {
			Logger.Err("Failed to send email:", err.Error())
		} else {
			Logger.Success("Email successfully sent.")
		}
	}
}
