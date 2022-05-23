package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"fmt"
	"github.com/google/uuid"
	"github.com/mmcdole/gofeed"
	"github.com/valyala/fastjson"
	"os"
	"strconv"
	"strings"
	"time"
)

const myID = "MODULE_ID"    // ID of this module
const myPass = "PASSWORD"   // ID of this module
const fileName = "rss_subs" // file where rss subscriptions are stored

type feedMemoryItem struct {
	url  string
	last int64
}

// Entry method for processing new incoming messages from MQ broker
func processMessage(msg string) {
	Logger.Trace("Processing message: ", msg)
	var parser fastjson.Parser
	obj, err := parser.Parse(msg)
	if err != nil {
		Logger.Err("Failed to parse message", msg, ". Skipping. Error message:", err.Error())
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
		Logger.Err("Received message with unknown type: " + msgType + ". Message will be ignored.")
	}
}

// Loads rss subscriptions from file, parses it and returns array of feedMemoryItem,
// which is struct that contains URL and last time this feed was updated
func loadFeeds() []feedMemoryItem {
	fileContent, err := os.ReadFile("rss_subs") // read the file
	if err != nil {                             // if the file cannot be loaded, this program is useless, so it can be terminated
		Logger.Fatal("Error loading file with rss feeds. Terminating. ")
		os.Exit(1)
	}
	lines := strings.Split(strings.Trim(string(fileContent), "\n"), "\n") // split file into lines
	feeds := make([]feedMemoryItem, len(lines))                           // prepare the array

	// split every line by semicolon and store it as an item in the array
	for i, line := range lines {
		split := strings.Split(line, ";")
		if len(split) != 2 {
			Logger.Err("File with rss subs has faulty format, on every line needs to be url and integer separated by semicolon.")
			continue
		}
		feeds[i].url = split[0]
		num, err := strconv.Atoi(split[1])
		if err != nil {
			Logger.Warn("Unable to parse last seen time for URL: ", split[0], "Skipping this url.")
			continue
		}
		feeds[i].last = int64(num)
	}
	return feeds
}

// Extracts headers from the rss item, constructs message for Core and sends it.
// If there are missing or invalid headers, the item will be skipped.
func processRssItem(item *gofeed.Item, lastSeen int64) {
	title := item.Title
	link := item.Link
	updateTime := item.UpdatedParsed
	if updateTime == nil {
		updateTime = item.PublishedParsed
	}
	description := item.Description
	description = strings.ReplaceAll(description, "\"", "'") // replace double quotes with single quotes to not confuse JSON parser
	if title == "" || link == "" || updateTime == nil {      // check the headers
		Logger.Err("Received RSS item with invalid format, missing some headers. Skipping.")
		return
	}
	if updateTime.Unix() > lastSeen { // check if the item was already seen
		s := []byte(fmt.Sprintf("{\"msgID\":\"%s\","+
			"\"msgOrigin\":\"%s\","+
			"\"msgType\":\"data\","+
			"\"msgCode\":\"new_rss\","+
			"\"link\":\"%s\","+
			"\"title\":\"%s\","+
			"\"desc\":\"%s\"}", uuid.New().String(), myID, link, title, description))
		mqClient.Send("core", &s, 0)
	}
}

// check all rss subscriptions, if any new items are found, they will be processed into message and send to Core
func checkRss() {
	feeds := loadFeeds() // load subscriptions from file
	parser := gofeed.NewParser()
	for i, _ := range feeds {
		raw, err := parser.ParseURL(feeds[i].url)
		if err != nil {
			Logger.Err("Parsing URL", feeds[i].url, "failed, error:", err.Error(), "Skipping this feed.")
			continue
		}
		items := raw.Items
		lastTime := raw.UpdatedParsed
		if lastTime == nil {
			lastTime = raw.PublishedParsed
		}
		if lastTime == nil {
			Logger.Warn("Feed does not have last updated time.")
			lastTime = items[0].UpdatedParsed
			if lastTime == nil {
				lastTime = items[0].PublishedParsed
				if lastTime == nil {
					Logger.Err("Neither feed nor items have last updated time. Skipping this feed.")
					continue
				}
			}
		}
		for _, item := range items {
			go processRssItem(item, feeds[i].last)
		}
		feeds[i].last = lastTime.Unix()
	}
	updateFile(feeds)
}

// Updates the timestamps of each feed inside the storage file.
func updateFile(items []feedMemoryItem) {
	s := ""
	for _, item := range items {
		s += item.url + ";" + strconv.FormatInt(item.last, 10) + "\n"
	}
	err := os.WriteFile(fileName, []byte(s), 'w')
	if err != nil {
		Logger.Err("Unable to save subs to file:", err.Error())
	}
}

func main() {
	msgQueue := make(chan string, 100)
	mqClient.Login = myID
	mqClient.Password = myPass
	go mqClient.Listen("rss_reader", msgQueue)
	go mqClient.Listen("broadcast", msgQueue)
	mqClient.Enroll(myID, myID)
	go func() {
		for {
			checkRss()
			Logger.Trace("RSS checked.")
			time.Sleep(1 * time.Hour)
		}
	}()
	for {
		processMessage(<-msgQueue)
	}
}
