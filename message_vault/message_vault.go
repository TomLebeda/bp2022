package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"container/list"
	"fmt"
	"github.com/google/uuid"
	"github.com/valyala/fastjson"
	"os"
	"strconv"
	"time"
)

const myID = "MODULE_ID"
const myPass = "PASSWORD"

var vault *list.List

type vaultItem struct {
	expiration int64
	onExpire   string
	msgCode    string
	msgID      string
	saveTime   int64
	payload    []byte
}

func main() {
	// TODO: load and save to/from file for long-lasting messages to survive beyond process lifetime
	vault = list.New()
	go checkExpirations()
	msgQueue := make(chan string, 100)
	Logger.Info("Starting.")
	mqClient.Login = myID
	mqClient.Password = myPass
	go mqClient.Listen("broadcast", msgQueue)
	go mqClient.Listen(myID, msgQueue)
	mqClient.Enroll(myID, myID)
	for {
		processMessage(<-msgQueue)
	}
}

// Periodically checks stored items if they are expired. Calls associated functions when expired item is found.
func checkExpirations() {
	for {
		if vault.Len() > 0 {
			for e := vault.Front(); e != nil; e = e.Next() {
				item := e.Value.(vaultItem)
				if item.saveTime+item.expiration < time.Now().UnixMilli() {
					// item has expired
					switch item.onExpire {
					case "send":
						Logger.Trace("Found expired item with ID ", item.msgID, ", sending it.")
						mqClient.Send("core", &item.payload, 0)
						item.saveTime = time.Now().UnixMilli() // reset the counter
						e.Value = item
					case "delsend":
						Logger.Trace("Found expired item with ID", item.msgID, ", sending it and then deleting.")
						mqClient.Send("core", &item.payload, 0)
						vault.Remove(e)
					case "del":
						Logger.Trace("Found expired item with ID", item.msgID, ", deleting it (without sending).")
						vault.Remove(e)
					}
				}
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func processMessage(msg string) {
	// check if the message can be parsed and has all needed headers
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
		Logger.Warn("Received data message to process, but this module is not supposed to receive any data messages. Message will be ignored.")
		return
	} else if msgType == "service" {
		switch code {
		case "fetch":
			requestTypeRaw := obj.GetStringBytes("requestType")
			filterRaw := obj.GetStringBytes("filter")
			if requestTypeRaw == nil || filterRaw == nil {
				Logger.Err("Received fetch request, but missing requestType or filter headers. Ignoring.")
				return
			}
			filter := string(filterRaw)
			switch reqType := string(requestTypeRaw); reqType {
			case "getID":
				Logger.Trace("Sending message with ID", filter)
				sendByID(filter, false)
			case "delID":
				Logger.Trace("Deleting message with ID", filter)
				delByID(filter)
			case "getdelID":
				Logger.Trace("Sending message with ID", filter, "with DELETE.")
				sendByID(filter, true)
			case "getAllWithCode":
				Logger.Trace("Sending ALL messages with CODE", filter)
				sendAllWithCode(filter, false)
			case "getdelAllWithCode":
				Logger.Trace("Sending ALL messages with CODE", filter, "with DELETE.")
				sendAllWithCode(filter, true)
			case "getNewWithCode":
				Logger.Trace("Sending NEW message with CODE", filter)
				sendNewWithCode(filter, false)
			case "getdelNewWithCode":
				Logger.Trace("Sending NEW message with CODE", filter, "with DELETE.")
				sendNewWithCode(filter, true)
			case "getOldWithCode":
				Logger.Trace("Sending OLD message with CODE", filter)
				sendOldWithCode(filter, false)
			case "getdelOldWithCode":
				Logger.Trace("Sending OLD message with CODE", filter, "with DELETE.")
				sendOldWithCode(filter, true)
			default:
				Logger.Err("Received fetch request with unknown type: ", reqType, ". Skipping.")
				return
			}
		case "store":
			exp := obj.GetInt("expiration")
			if exp < 1 {
				Logger.Err("Can not store message with expiration set to ", strconv.Itoa(exp), ". Header is missing or the value is wrong. Skipping.")
				return
			}
			onExp := obj.GetStringBytes("onExpire")
			if onExp == nil {
				Logger.Err("Can not store message, because header onExpire is missing. Skipping.")
				return
			}
			payloadRaw := obj.Get("content") // content is expected to be JSON object that is the message that should be stored.
			if payloadRaw == nil {
				Logger.Err("Can not store message, because header content is missing. Skipping.")
				return
			}
			var payload []byte
			payload = payloadRaw.MarshalTo(payload)
			c := obj.Get("content").GetStringBytes("msgCode")
			if c == nil {
				Logger.Err("Can not store message, because extraction of messageCode failed. Skipping.")
			}
			id := obj.Get("content").GetStringBytes("msgID")
			if id == nil {
				Logger.Err("Can not store message, because extraction of messageID failed. Skipping.")
			}

			vault.PushFront(vaultItem{
				expiration: int64(exp),
				onExpire:   string(onExp),
				msgCode:    string(c),
				msgID:      string(id),
				saveTime:   time.Now().UnixMilli(),
				payload:    payload,
			})
			Logger.Trace("Stored new message.")
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
		Logger.Err("Received message with unknown type: ", msgType, ". Message will be ignored.")
		return
	}
}

func delByID(id string) {
	for e := vault.Front(); e != nil; e = e.Next() {
		item := e.Value.(vaultItem)
		if item.msgID == id {
			vault.Remove(e)
			Logger.Trace("Message found and deleted.")
			return
		}
	}
	Logger.Warn("No message with given ID found, nothing happened.")
}

// Sends a stored message with given ID. If there is none, nothing happens.
func sendByID(id string, delete bool) {
	for e := vault.Front(); e != nil; e = e.Next() {
		item := e.Value.(vaultItem)
		if item.msgID == id {
			mqClient.Send("core", &item.payload, 0)
			if delete {
				vault.Remove(e)
			}
			return
		}
	}
}

// Sends all stored message with given code.
func sendAllWithCode(code string, delete bool) {
	// NOTE: can not use list.Delete on items directly during iteration, because it causes e=nil => it stops the iteration after first deletion
	toDel := make([]*list.Element, 0)
	for e := vault.Front(); e != nil; e = e.Next() {
		item := e.Value.(vaultItem)
		if item.msgCode == code {
			mqClient.Send("core", &item.payload, 0)
			if delete {
				toDel = append(toDel, e)
			}
		}
	}
	for i := 0; i < len(toDel); i++ {
		vault.Remove(toDel[i])
	}
}

// Sends newest stored message with given code.
func sendNewWithCode(code string, delete bool) {
	for e := vault.Front(); e != nil; e = e.Next() {
		item := e.Value.(vaultItem)
		if item.msgCode == code {
			mqClient.Send("core", &item.payload, 0)
			if delete {
				vault.Remove(e)
			}
			return
		}
	}
}

// Sends oldest stored message with given code.
func sendOldWithCode(code string, delete bool) {
	for e := vault.Back(); e != nil; e = e.Prev() {
		item := e.Value.(vaultItem)
		if item.msgCode == code {
			mqClient.Send("core", &item.payload, 0)
			if delete {
				vault.Remove(e)
			}
			return
		}
	}
}
