package go_amq_client

import (
	Logger "bakalarka/GoLogger"
	"fmt"
	"github.com/go-stomp/stomp"
	"github.com/go-stomp/stomp/frame"
	"github.com/google/uuid"
	"time"
	"strconv"
)

const (
	MESSAGE_CODE   = "msgCode"
	MESSAGE_TYPE   = "msgType"
	MESSAGE_ID     = "msgID"
	MESSAGE_ORIGIN = "msgOrigin"
)

var (
	connection         *stomp.Conn
	reconnecting       = false
	connected          = false
	Login              = "MODULE_ID"
	Password           = "MODULE_PASSWORD"
	serverAddress      = "BROKER_URL"
	LastEnrollID       = ""
	EnrollAcknowledged = false
)

// Reconnect Attempts to recconnect to the broker.
// This method will continue to try to reconnect until it succeeds.
// Thread-safe
func Reconnect() {
	if reconnecting {
		return
	}
	reconnecting = true
	defer func() { reconnecting = false }()

	connected = false
	if connection != nil {
		err := connection.Disconnect()
		if err != nil {
			Logger.Err("ERROR: unable to disconnect.")
		}
		time.Sleep(1 * time.Second)
		Logger.Info("Disconnected.")
	}
	for !connected {
		conn, err := stomp.Dial("tcp", serverAddress,
			stomp.ConnOpt.Login(Login, Password),
			stomp.ConnOpt.HeartBeatError(70*time.Second),
			stomp.ConnOpt.HeartBeat(60*time.Second, 60*time.Second),
		)
		if err != nil {
			Logger.Warn("Unable to establish STOMP connection:", err.Error())
		} else {
			connected = true
			connection = conn
			Logger.Info("Connected to server.")
		}
		time.Sleep(1 * time.Second)
	}

}

// Listen Sets up a listener for given destination. Incoming messages will be put into given channel.
func Listen(destination string, msgChannel chan string) {
	listening := false
	var sub *stomp.Subscription = nil
	var err error = nil
	for {
		if connected {
			if listening {
				msg, err2 := sub.Read()
				if err2 != nil {
					Logger.Err("Error while reading new message from ", destination, "| ERR:", err2.Error())
					listening = false
				} else {
					msgChannel <- string(msg.Body)
				}
			} else {
				sub, err = connection.Subscribe(destination, stomp.AckAuto)
				if err != nil {
					Logger.Err("Unable to subscribe to", destination, "| Err:", err.Error())
					Reconnect()
				} else {
					Logger.Info("Subscribed to", destination)
					listening = true
				}
			}
		} else {
			Reconnect()
			time.Sleep(1 * time.Second)
		}
	}
}

// Sends a message to given destination, if expiration is zero or less, no expiration is added (message will never expire)
func Send(destination string, bytes *[]byte, expiration_ms int64, options ...func(*frame.Frame) error) {
	options = append(options, stomp.SendOpt.NoContentLength)
	if expiration_ms > 0 {
		dur := time.Duration(expiration_ms * 1e6)
		d := strconv.Itoa(int(time.Now().Add(dur).UnixMilli()))
		options = append(options, stomp.SendOpt.Header("expires", d))
	}
	err := connection.Send(
		destination,
		"text/plain",
		*bytes,
		options...,
	)
	if err != nil {
		Logger.Err("Failed to send message:", err.Error())
		Reconnect()
	} else {
		Logger.Trace("Message sent.")
	}
}

// Sends an enroll message to given "core" queue. Resets the EnrollAcknowledged attribute.
func Enroll(originId string, listeningOn string) {
	for !connected {
		Reconnect()
		time.Sleep(1 * time.Second)
	}
	LastEnrollID = uuid.New().String()
	buff := []byte(fmt.Sprintf("{\"msgID\":\"%s\",\"msgOrigin\":\"%s\",\"msgType\":\"service\",\"msgCode\":\"here\",\"listeningOn\":\"%s\"}", LastEnrollID, originId, listeningOn))
	Send("core", &buff, 5000)
	EnrollAcknowledged = false
}
