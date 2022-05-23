package main

import (
	Logger "bakalarka/GoLogger"
	mqClient "bakalarka/go_amq_client"
	"errors"
	"fmt"
	"github.com/emersion/go-imap"
	imapClient "github.com/emersion/go-imap/client"
	"github.com/google/uuid"
	"github.com/valyala/fastjson"
	"golang.org/x/net/html/charset"
	"golang.org/x/text/encoding"
	"io"
	"math"
	"mime/quotedprintable"
	"os"
	"strconv"
	"strings"
	"time"
)

type account struct {
	server string
	uname  string
	passwd string
}

type mail struct {
	sender        string
	senderAddress string
	read          bool
	mailID        uint32
	subject       string
	received      int64
	text          string
}

const myID = "MODULE_ID"
const myPass = "PASSWORD"

var mainAccount = account{server: "IMAP_SERVER", uname: "USERNAME", passwd: "PASSWORD"}

func main() {
	msgQueue := make(chan string, 100)
	mqClient.Login = myID
	mqClient.Password = myPass
	go mqClient.Listen("broadcast", msgQueue)
	go mqClient.Listen(myID, msgQueue)
	mqClient.Enroll(myID, myID)

	go handleEmail(mainAccount)
	for {
		processMessage(<-msgQueue)
	}
}

func processMessage(msg string) {
	Logger.Trace("Processing message: ", msg)
	var parser fastjson.Parser
	msgObj, err := parser.Parse(msg)
	if err != nil {
		Logger.Err("Failed to parse message", msg, ". Skipping. Error message:", err.Error())
		return
	}
	msgType := msgObj.GetStringBytes(mqClient.MESSAGE_TYPE)
	if msgType == nil {
		Logger.Err("Unable to process message, because msgType was not found. Skipping.")
		return
	}
	msgCode := msgObj.GetStringBytes(mqClient.MESSAGE_CODE)
	if msgCode == nil {
		Logger.Err("Unable to process message, because msgCode was not found. Skipping.")
		return
	}
	msgID := msgObj.GetStringBytes(mqClient.MESSAGE_ID)
	if msgCode == nil {
		Logger.Err("Unable to process message, because msgID was not found. Skipping.")
		return
	}
	code := string(msgCode)

	// module is acknowledged by Core
	if string(msgType) == "data" {
		if !mqClient.EnrollAcknowledged {
			Logger.Warn("Received data message to process, but enroll protocol hasn't finished yet. I will ignore that for now and proceed normally.")
		}
		switch code {
		case "force_check":
			ids, err := fetchUIDs(connectAndLogin(mainAccount), true)
			if err != nil {
				Logger.Err("Failed to get uids of unread emails: " + err.Error())
			} else {
				Logger.Info("Detected", strconv.Itoa(len(ids)), " unread email(s) after forced check, UIDs:"+fmt.Sprint(ids))
				jo := fastjson.Object{}
				jo.Set("msgID", fastjson.MustParse("\""+uuid.New().String()+"\""))
				jo.Set("msgType", fastjson.MustParse("\"data\""))
				jo.Set("msgCode", fastjson.MustParse("\"unread_emails\""))
				jo.Set("msgOrigin", fastjson.MustParse("\""+myID+"\""))
				jo.Set("content", fastjson.MustParse(strings.ReplaceAll(fmt.Sprint(ids), " ", ",")))
				jo.Set("respID", fastjson.MustParse("\""+string(msgObj.GetStringBytes("msgID"))+"\""))
				jo.Set("link", fastjson.MustParse("\""+mainAccount.uname+"\"")) // TODO: mainAccount will not be sufficient when more accounts are used

				msgContent := jo.MarshalTo([]byte{})
				mqClient.Send("core", &msgContent, 5000)
			}
		}
		Logger.Err("Received data message but this module is not supposed to receive any. Message will be ignored.")
		return
	} else if string(msgType) == "service" {
		switch code {
		case "confirm":
			respID := msgObj.GetStringBytes("respID")
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
		case "fetch":
			Logger.Trace("Received fetch request.")
			ft := msgObj.GetStringBytes("filterType")
			client := connectAndLogin(mainAccount)
			defer logout(client)
			if ft == nil {
				Logger.Warn("Received fetch request, but there is no filterType header that is required. Message will be ignored")
				return
			}
			switch string(ft) {
			case "all_unread":
				Logger.Trace("Received fetch request for all unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) == 0 {
					reportEmptySearch(string(msgID))
				} else {
					fetchAndSend(client, uids, string(msgID))
				}
				//sendAllUnread(mainAccount)
			case "old_unread":
				Logger.Trace("Received fetch request for oldest unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) == 0 {
					reportEmptySearch(string(msgID))
				} else {
					fetchAndSend(client, []uint32{uids[0]}, string(msgID)) // first id => oldest message
				}
				//sendOldUnread(mainAccount)
			case "new_unread":
				Logger.Trace("Received fetch request for newest unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) == 0 {
					reportEmptySearch(string(msgID))
				} else {
					fetchAndSend(client, []uint32{uids[len(uids)-1]}, string(msgID)) // last id => newest message
				}
				//sendNewUnread(mainAccount)
			case "last":
				Logger.Trace("Received fetch request for last incoming email.")
				uids, err := fetchUIDs(client, false)
				if err != nil {
					Logger.Err("Failed to fetch uids: " + err.Error())
				} else if len(uids) == 0 {
					reportEmptySearch(string(msgID))
				} else {
					fetchAndSend(client, []uint32{uids[len(uids)-1]}, string(msgID)) // last id => newest message
				}
				//sendLast(mainAccount, 1)
			case "custom":
				Logger.Trace("Received fetch request for custom criteria.")
				var filters []string
				vals := msgObj.GetArray("filter")
				for i := 0; i < len(vals); i++ {
					if b := vals[i].GetStringBytes(); b != nil {
						filters = append(filters, string(b))
					}
				}
				uids, err := fetchCustomUIDs(client, filters)
				if err != nil {
					Logger.Err("Failed to fetch (custom filtered) uids: " + err.Error())
				} else if len(uids) == 0 {
					reportEmptySearch(string(msgID))
				} else {
					fetchAndSend(client, uids, string(msgID)) // function fetchCustomUIDs handles sorting and counting of IDs itself
				}
			default:
				Logger.Warn("Received fetch request, but the filterType", string(ft), "is not recognized. Message will be ignored.")
				return
			}
		case "mark_as_read":
			Logger.Trace("Received mark_as_read request.")
			ft := msgObj.GetStringBytes("filterType")
			client := connectAndLogin(mainAccount)
			defer logout(client)
			if ft == nil {
				Logger.Warn("Received mark_as_read request, but there is no filterType header that is required. Message will be ignored")
				return
			}
			switch string(ft) {
			case "all_unread":
				Logger.Trace("Received mark_as_read request for all unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) != 0 {
					markAsRead(client, uids)
				}
			case "old_unread":
				Logger.Trace("Received mark_as_read request for oldest unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) != 0 {
					markAsRead(client, []uint32{uids[0]}) // first id => oldest message
				}
			case "new_unread":
				Logger.Trace("Received fetch request for newest unread email.")
				uids, err := fetchUIDs(client, true)
				if err != nil {
					Logger.Err("Failed to fetch all unread uids: " + err.Error())
				} else if len(uids) != 0 {
					markAsRead(client, []uint32{uids[len(uids)-1]}) // last id => newest message
				}
				//sendNewUnread(mainAccount)

			case "custom":
				Logger.Trace("Received fetch request for custom criteria.")
				var filters []string
				vals := msgObj.GetArray("filter")
				for i := 0; i < len(vals); i++ {
					if b := vals[i].GetStringBytes(); b != nil {
						filters = append(filters, string(b))
					}
				}
				uids, err := fetchCustomUIDs(client, filters)
				if err != nil {
					Logger.Err("Failed to fetch (custom filtered) uids: " + err.Error())
				} else if len(uids) != 0 {
					markAsRead(client, uids) // function fetchCustomUIDs handles sorting and counting of IDs itself
				}
			default:
				Logger.Warn("Received fetch request, but the filterType", string(ft), "is not recognized. Message will be ignored.")
				return
			}
		default:
			Logger.Warn("Received service message with code", code, "but there is nothing to do with it. Message will be ignored. ")
		}
	} else {
		Logger.Err("Received message with unknown type: ", string(msgType), ". Message will be ignored.")
		return
	}
}

func markAsRead(client *imapClient.Client, uids []uint32) {
	seqSet := new(imap.SeqSet)
	seqSet.AddNum(uids...)
	item := imap.FormatFlagsOp(imap.AddFlags, true)
	flags := []interface{}{imap.SeenFlag}
	err := client.UidStore(seqSet, item, flags, nil)
	if err != nil {
		Logger.Err("Failed to mark mails as read: " + err.Error())
	} else {
		Logger.Info("Marked emails " + fmt.Sprint(uids) + " as read.")
	}
}

func reportEmptySearch(requestID string) {
	Logger.Warn("UIDs fetch was successful, but result is empty.")
	jo := fastjson.Object{}
	jo.Set("msgID", fastjson.MustParse("\""+uuid.New().String()+"\""))
	jo.Set("msgType", fastjson.MustParse("\"data\""))
	jo.Set("msgCode", fastjson.MustParse("\"report\""))
	jo.Set("msgOrigin", fastjson.MustParse("\""+myID+"\""))
	jo.Set("content", fastjson.MustParse("\"empty_search\""))
	jo.Set("respID", fastjson.MustParse("\""+requestID+"\""))
	out := jo.MarshalTo([]byte{})
	//Logger.Debug(string(out))
	mqClient.Send("core", &out, 10_000) // message will wait for 10 seconds on broker for Core to pick it up
}

func findPlainText(bs *imap.BodyStructure, path []int) []int {
	if bs.MIMEType == "text" && bs.MIMESubType == "plain" {
		return append(path, 1)
	}

	if bs.MIMEType == "multipart" {
		for i, part := range bs.Parts {
			if part.MIMEType == "text" && part.MIMESubType == "plain" {
				return append(path, i+1)
			}
			if part.MIMEType == "multipart" {
				p := findPlainText(part, path)
				if p != nil {
					return append(path, append([]int{i + 1}, p...)...)
				}
			}
		}
	}
	return nil
}

func digest(msg *imap.Message, client *imapClient.Client) (mail, error) {
	env := msg.Envelope
	if env == nil {
		return mail{}, errors.New("envelope is nil")
	}
	from := env.From
	if from == nil || len(from) == 0 {
		return mail{}, errors.New("item From (inside Envelope) is nil")
	}
	fromAddr := from[0].Address()
	fromName := from[0].PersonalName
	emailID := msg.Uid
	timestamp := env.Date.UnixMilli()
	bs := msg.BodyStructure
	if bs == nil {
		Logger.Err("fetched item does not have body structure (item is nil)")
		return mail{}, errors.New("fetched message does not have body structure (item is nill)")
	}

	wantBody := true
	path := findPlainText(bs, []int{})
	if len(path) == 0 || path == nil {
		Logger.Warn("path to body of type text/plain is empty or nil, email will be without body.")
		wantBody = false
	}

	var decodedBody []byte
	if wantBody {
		pathS := fmt.Sprintf("%d", path)
		pathS = strings.Trim(pathS, "[")
		pathS = strings.Trim(pathS, "]")
		pathS = strings.Replace(pathS, " ", ".", -1)

		seqset2 := new(imap.SeqSet)
		seqset2.AddNum(msg.Uid)

		bodyChan := make(chan *imap.Message, 1)
		done2 := make(chan error, 1)
		item := imap.FetchItem("BODY.PEEK[" + pathS + "]") // peek will not set the Seen flag
		//item := imap.FetchItem("BODY[" + pathS + "]") // peek will not set the Seen flag
		go func() {
			done2 <- client.UidFetch(seqset2, []imap.FetchItem{item}, bodyChan)
		}()

		body := <-bodyChan // wait for messages

		section := imap.BodySectionName{}
		section.Path = path
		bodyStringRaw := body.GetBody(&section)

		//TODO: může být více druhů kódování, viz https://stackoverflow.com/questions/24883742/how-to-decode-mail-body-in-go
		var err error = nil
		decodedBody, err = io.ReadAll(quotedprintable.NewReader(bodyStringRaw))
		if err != nil {
			Logger.Err("Failed to decode email body: ", err.Error())
			decodedBody = []byte("[decoding of email body failed]")
		}
		bs2 := msg.BodyStructure
		if len(bs2.Parts) > 0 {
			for _, i := range path {
				bs2 = bs2.Parts[i-1]
			}
		}
		var chset string
		chset = bs2.Params["charset"]
		enc, encName := charset.Lookup(chset)
		if enc != nil {
			Logger.Trace("Text is encoded with " + encName)
			decoder := enc.NewDecoder()
			decodedBody, err = decoder.Bytes(decodedBody)
			if err != nil {
				Logger.Warn("Failed to decode bytes: " + err.Error())
				decodedBody = []byte("[decoding of email body failed]")
			}
		} else {
			Logger.Warn("Failed to find encoding. Will not decode.")
			//decodedBody = []byte("[decoding of email body failed]")
			decoder := encoding.Nop.NewDecoder()
			decodedBody, err = decoder.Bytes(decodedBody)
			if err != nil {
				Logger.Warn("Failed to decode bytes: " + err.Error())
				decodedBody = []byte("[decoding of email body failed]")
			}
		}
	} else {
		decodedBody = []byte("[email body was not fetched]")
	}
	purifiedBody := string(decodedBody)
	purifiedBody = strings.ReplaceAll(purifiedBody, "\"", "'") // purify string for JSON
	purifiedBody = strings.ReplaceAll(purifiedBody, "\n", "")  // purify string for JSON
	purifiedBody = strings.ReplaceAll(purifiedBody, "\r", "")  // purify string for JSON
	purifiedBody = strings.ReplaceAll(purifiedBody, "\t", "")  // purify string for JSON
	outMail := mail{
		sender:        fromName,
		senderAddress: fromAddr,
		read:          false,
		mailID:        emailID,
		subject:       msg.Envelope.Subject,
		received:      timestamp,
		text:          purifiedBody,
	}
	return outMail, nil
}

func fetchAndSend(client *imapClient.Client, uids []uint32, respID string) {
	Logger.Trace("Entered fetchAndSend(), uids: " + fmt.Sprint(uids))
	messages := make(chan *imap.Message, 10) // create a channel for fetched messages

	if len(uids) == 0 { // proper report to Core should be handled before calling this method
		Logger.Warn("No emails found, uids is empty.")
		return
	}

	go func() {
		// get the envelope and BodyStructure
		items := []imap.FetchItem{imap.FetchEnvelope, imap.FetchBodyStructure}

		seqset := new(imap.SeqSet) // create new seqset
		seqset.AddNum(uids...)     // add all fetched UIDs to the selection

		if err := client.UidFetch(seqset, items, messages); err != nil {
			Logger.Err("Failed to fetch msg with UIDs, err: " + err.Error())
			return
		}
	}()

	for msg := range messages {
		if msg == nil {
			Logger.Warn("Can not digest nil msg, skipping.")
		} else {
			Logger.Trace("Received fetched msg: " + strconv.Itoa(int(msg.Uid)))
			m, err := digest(msg, client)
			if err != nil {
				Logger.Warn("Digest failed:", err.Error())
			} else {
				Logger.Trace("Msg digested to mail struct.")
				sendMailAsMsg(m, respID)
			}
		}
	}
}

func sendMailAsMsg(m mail, respID string) {
	jo := fastjson.Object{}
	jo.Set("msgID", fastjson.MustParse("\""+uuid.New().String()+"\""))
	jo.Set("msgType", fastjson.MustParse("\"data\""))
	jo.Set("msgCode", fastjson.MustParse("\"email\""))
	jo.Set("msgOrigin", fastjson.MustParse("\""+myID+"\""))
	jo.Set("author", fastjson.MustParse("\""+m.sender+"\""))
	jo.Set("authorAddress", fastjson.MustParse("\""+m.senderAddress+"\""))
	jo.Set("title", fastjson.MustParse("\""+m.subject+"\""))
	valText, err := fastjson.Parse("\"" + m.text + "\"")
	if err != nil {
		Logger.Warn("Failed to parse mail text into JSON: " + err.Error())
		valText = fastjson.MustParse("\"[Failed to parse text to JSON]\"")
	}
	jo.Set("content", valText)
	jo.Set("receiveTime", fastjson.MustParse(strconv.Itoa(int(m.received))))
	jo.Set("itemID", fastjson.MustParse("\""+strconv.Itoa(int(m.mailID))+"\""))
	jo.Set("respID", fastjson.MustParse("\""+respID+"\""))
	out := jo.MarshalTo([]byte{})
	//Logger.Debug(string(out))
	mqClient.Send("core", &out, 30_000) // message will wait for 30 seconds on broker for Core to pick it up
}

func handleEmail(acc account) {
	// login
	client := connectAndLogin(acc)

	// don't forget to log out
	defer logout(client)

	for {
		client = connectAndLogin(acc)

		// check the email first
		ids, err := fetchUIDs(client, true)
		if err != nil {
			Logger.Warn("Failed to fetch UIDs of unread emails. Will try again in 10 seconds.")
			time.Sleep(10 * time.Second)
			continue
		}

		// if there is some unread mail, send that information to Core
		if len(ids) != 0 {
			Logger.Info("Detected", strconv.Itoa(len(ids)), " unread email(s), UIDs:"+fmt.Sprint(ids))

			jo := fastjson.Object{}
			jo.Set("msgID", fastjson.MustParse("\""+uuid.New().String()+"\""))
			jo.Set("msgType", fastjson.MustParse("\"data\""))
			jo.Set("msgCode", fastjson.MustParse("\"unread_emails\""))
			jo.Set("msgOrigin", fastjson.MustParse("\""+myID+"\""))
			jo.Set("content", fastjson.MustParse(strings.ReplaceAll(fmt.Sprint(ids), " ", ",")))
			jo.Set("link", fastjson.MustParse("\""+acc.uname+"\""))

			msgContent := jo.MarshalTo([]byte{})
			mqClient.Send("core", &msgContent, 5000)
		} else {
			Logger.Trace("No unread emails detected.")
		}

		// idle and wait for any updates
		idleWait(client)
	}
}

func fetchCustomUIDs(client *imapClient.Client, filterStrings []string) ([]uint32, error) {
	Logger.Trace("Fetching UIDs with custom specifications.")
	mbox, err := client.Select("INBOX", false)
	if err != nil {
		return nil, err
	}
	if mbox.Messages == 0 {
		return nil, errors.New("there is 0 messages in selected inbox")
	}
	criterias := make(map[string]string)
	criterias["count"] = "1"
	criterias["sort"] = "new"
	for _, s := range filterStrings {
		split := strings.Split(s, ":")
		if len(split) != 2 {
			Logger.Warn("Split filterString does not have 2 parts. It will be ignored.")
			continue
		}
		if split[0] == "count" || split[0] == "sort" || split[0] == "from" || split[0] == "seen" {
			criterias[split[0]] = split[1]
		} else {
			Logger.Warn("Split string has unknown first part: " + split[0] + ". It will be ignored.")
			continue
		}
	}

	crits := imap.NewSearchCriteria()
	crits.Header = map[string][]string{}
	if s, ok := criterias["from"]; ok {
		crits.Header["From"] = []string{s}
	}
	if s, ok := criterias["seen"]; ok {
		if s == "true" {
			crits.WithFlags = []string{imap.SeenFlag}
		} else if s == "false" {
			crits.WithoutFlags = []string{imap.SeenFlag}
		} else {
			Logger.Warn("Criteria \"seen\" has invalid value: " + s + ", valid values are \"true\" or \"false\" (strings)")
		}
	}
	count := 1
	if s, ok := criterias["count"]; ok {
		n, err := strconv.Atoi(s)
		if err != nil {
			Logger.Warn("Failed to convert \"count\" criteria to numeric value: " + s)
		} else {
			count = n
		}
	}
	sort := "new"
	if s, ok := criterias["sort"]; ok {
		if s == "old" {
			sort = "old"
		}
	}

	uids, err := client.UidSearch(crits)

	if sort == "old" {
		// return up to count ids from the front of the list, with size protection
		return uids[:int(math.Min(float64(len(uids)), float64(count)))], nil
	} else {
		// return up to count ids from the back of the list, with size protection
		return uids[len(uids)-int(math.Min(float64(len(uids)), float64(count))):], nil
	}
}

func fetchUIDs(client *imapClient.Client, onlyUnseen bool) ([]uint32, error) {
	Logger.Trace("Fetching UIDs of (unread?) emails.")

	// select INBOX
	mbox, err := client.Select("INBOX", false)
	if err != nil {
		//Logger.Err("Failed to select inbox:", err.Error())
		return nil, err
	}

	if mbox.Messages == 0 {
		//Logger.Warn("Can not fetch messages, because there is 0 messages in selected inbox", mbox.Name)
		return nil, errors.New("there is 0 messages in selected inbox")
	}

	criteria := imap.NewSearchCriteria()
	if onlyUnseen {
		// select search criteria to UNSEEN
		criteria.WithoutFlags = []string{imap.SeenFlag}
	}

	// search for unread messages
	ids, err := client.UidSearch(criteria)
	if err != nil {
		Logger.Err("Error during searching: ", err.Error())
		return nil, err
	}
	Logger.Trace("Fetched IDs of (unread?) emails: " + fmt.Sprint(ids))
	return ids, nil
}

func idleWait(client *imapClient.Client) *imapClient.MailboxUpdate {
	// prepare for idling
	updates := make(chan imapClient.Update)
	client.Updates = updates
	done := make(chan error, 1)        // create channel for idle error/done reports
	stopChannel := make(chan struct{}) // reset the stop channel

	// start idling
	go func() {
		done <- client.Idle(stopChannel, nil)
	}()

	Logger.Trace("Entering idle loop.")
	for {
		select {
		case update := <-updates:
			Logger.Trace("New update")
			switch u := update.(type) {
			case *imapClient.ExpungeUpdate:
				Logger.Info("Received update that email ", strconv.Itoa(int(u.SeqNum)), " was deleted.")
			case *imapClient.MailboxUpdate:
				// this is triggered when new email arrives
				Logger.Info("Received update on mailbox", u.Mailbox.Name)
				close(stopChannel) // stop the idling, it will break out of this select and jump to last case item
			case *imapClient.MessageUpdate:
				Logger.Info("Received update that attribute changed on email", strconv.Itoa(int(u.Message.Uid)))
			case *imapClient.StatusUpdate:
				Logger.Info("Received status update:", u.Status.Info)
			default:
				Logger.Warn("Received unknown update, ignoring. This should not happen, check your implementation.")
			}
		case err := <-done:
			if err != nil {
				Logger.Err("Received error: ", err.Error())
			}
			Logger.Info("Idling stopped.")
			return nil
		}
	}
}

func logout(c *imapClient.Client) {
	if c == nil {
		Logger.Warn("Can not perform logout, because given client is null. Returning.")
		return
	}
	Logger.Trace("Logging out.")
	err := c.Logout()
	if err != nil {
		Logger.Err("Error during logout: ", err.Error())
	}
}

// Login into given email, returns connected client session and error.
func connectAndLogin(acc account) *imapClient.Client {
	var client *imapClient.Client
	var err error

	logout(client) // try to log out before logging in

	// keep trying until success every 5 minutes
	for {
		// Connect to server
		Logger.Trace("Connecting to server...")
		client, err = imapClient.DialTLS(acc.server, nil)
		if err != nil {
			Logger.Warn("Failed to connect to ", acc.server+".", "Error message:", err.Error())
		} else {
			Logger.Trace("Connected to ", acc.server) // not success yet, login is remaining
		}

		// Login
		if err = client.Login(acc.uname, acc.passwd); err != nil {
			Logger.Warn("Login failed:", err.Error())
		} else {
			Logger.Success("Logged in as", acc.uname)
			return client
		}
		Logger.Trace("Connection and/or login failed, will try again in 5 minutes.")
		time.Sleep(5 * time.Minute)
	}
}
