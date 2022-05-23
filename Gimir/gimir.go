package main

import (
	Logger "bakalarka/GoLogger"
	"bufio"
	"fmt"
	"github.com/PuerkitoBio/goquery"
	"golang.org/x/net/html"
	"io/ioutil"
	"math"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
)

type Word struct {
	label    string
	forms    map[string]struct{}
	meanings map[string]*Meaning
}

func (w *Word) toString(verbose bool, abnf bool) string {
	//TODO: seřadit podle abecedy při všech situacích
	if !abnf {
		s := fmt.Sprint(w.forms)
		s = strings.ReplaceAll(s, "{}", "")
		s = s[4 : len(s)-2]
		ss := strings.Split(s, ":")
		S := ""
		i := 0
		for _, f := range ss {
			S += strings.TrimSpace(f)
			if i != len(ss)-1 {
				S += ", "
			}
			i++
		}
		if verbose {
			out := Logger.ColorText("WORD_"+w.label, Logger.PINK) + ":\n"
			out += Logger.ColorText("    -> FORMS: ", Logger.WHITE) + "[" + S + "]\n"
			out += Logger.ColorText("    -> MEANINGS: ", Logger.WHITE) + "["
			j := 0
			for key := range w.meanings {
				if j != len(w.meanings)-1 {
					out += key + ", "
				} else {
					out += key
				}
			}
			out += "]"
			return out
		} else {
			return Logger.ColorText("WORD_"+w.label, Logger.PINK) + " -> [" + S + "]"
		}
	} else {
		fs := ""
		i := 0
		for form := range w.forms {
			fs += " " + form + " "
			if i != len(w.forms)-1 {
				fs += "|"
			}
			i++
		}
		out := "$WORD_" + w.label + " = ( (" + fs + ") {WORD_" + w.label + "} );"
		return out
	}
}

func (w *Word) delete() {
	for key := range w.meanings { // for every meaning that this Word has
		delete(meanings[key].words, w.label) // delete this Word from that Meaning
	}
	delete(words, w.label) // delete this Word from wordList
}

type Meaning struct {
	label string
	words map[string]*Word
}

func (m *Meaning) toString(verbose bool, abnf bool) string {
	//TODO: seřadit podle abecedy při všech situacích
	if !abnf {
		if !verbose {
			w := ""
			i := 0
			for s := range m.words {
				w += s
				if i != len(m.words)-1 {
					w += ", "
				}
				i++
			}
			return Logger.ColorText("MEANING_"+m.label, Logger.BLUE) + " -> [" + w + "]"
		} else {
			out := Logger.ColorText("MEANING_"+m.label, Logger.BLUE) + ":\n"
			for _, word := range m.words {
				out += "    -> " + word.toString(false, false) + "\n"
			}
			return out
		}
	} else {
		fs := ""
		i := 0
		for _, word := range m.words {
			fs += " $WORD_" + word.label + " "
			if i != len(m.words)-1 {
				fs += "|"
			}
			i++
		}
		out := "$MEANING_" + m.label + " = ( (" + fs + ") {MEANING_" + m.label + "} );"
		if verbose {
			out += "\n"
			for _, word := range m.words {
				out += word.toString(false, true) + "\n"
			}
		}
		return out
	}
}

var words = make(map[string]*Word)
var meanings = make(map[string]*Meaning)
var reader = bufio.NewReader(os.Stdin)

func (m *Meaning) delete(flagRecursive bool) {
	if flagRecursive { // if recursive delete
		for key := range m.words { // for every word in this meaning
			m.words[key].delete() // find that word and call delete
		}
	}
	delete(meanings, m.label) // delete this meaning from the list
	for s := range words {    // clear reference to deleted meaning on all words
		delete(words[s].meanings, m.label)
	}
}

func main() {
	fmt.Println("Welcome to Gimir. Start by loading a file or generating new word/meaning. Or type \"help\" to display help.")
	for {
		command := readLine()
		if strings.HasPrefix(command, "help") {
			printHelp()
		} else if strings.HasPrefix(command, "quit") {
			fmt.Println("Do you really want to quit Gimir? [y/N]")
			command = readLine()
			if command == "y" {
				fmt.Println("Goodbye.")
				os.Exit(0)
			} else {
				reportEndOfCommand("Cancelled. ")
			}
		} else if strings.HasPrefix(command, "generate meaning") {
			generateMeaning(command)
		} else if strings.HasPrefix(command, "generate word") {
			generateWord(command)
		} else if strings.HasPrefix(command, "delete meaning") {
			deleteMeaning(command)
		} else if strings.HasPrefix(command, "delete word") {
			deleteWord(command)
		} else if strings.HasPrefix(command, "modify meaning") {
			modifyMeaning(command)
		} else if strings.HasPrefix(command, "modify word") {
			modifyWord(command)
		} else if strings.HasPrefix(command, "print meanings") {
			printMeanings(command)
		} else if strings.HasPrefix(command, "print words") {
			printWords(command)
		} else if strings.HasPrefix(command, "load") {
			load(command)
		} else if strings.HasPrefix(command, "dump") {
			dump(command)
		} else if command == "optimize" {
			optimize()
		} else if strings.HasPrefix(command, "search") {
			search(command)
		} else if command == "fetch names" {
			fetchNames()
		} else {
			Logger.PrintColor("Command is not recognized. Type \"help\" to display help or \"quit\" to quit Gimir.\n", Logger.RED)
		}
	}
}

func search(command string) {
	command = strings.TrimSpace(command[len("search "):])
	splits := strings.Split(command, " ")
	if len(splits) == 0 {
		reportEndOfCommand("Nothing found.")
		return
	}
}

func fetchNames() {
	resp, err := http.Get("https://sklonuj.cz/sklonovani-jmen/")
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		Logger.PrintColor("Page for names not found. ", Logger.RED)
		reportEndOfCommand("")
		return
	}

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		panic(err)
	}

	nameLabels := make(map[string]struct{})
	doc.Find("#mainContent > div:nth-child(5) > div:nth-child(2)").Find("a").Each(func(i int, selection *goquery.Selection) {
		t := selection.Text()
		nameLabels[t] = struct{}{}
	})

	names := make([]*Word, 0)

	var resp2 *http.Response
	var err2 error
	done := 0
	for s := range nameLabels {
		resp2, err2 = http.Get("https://sklonuj.cz/jmeno/" + s)
		if err2 != nil {
			panic(err)
		}

		if resp2.StatusCode != 200 {
			Logger.PrintColor("Page for names not found. ", Logger.RED)
			reportEndOfCommand("")
			return
		}

		doc2, err3 := goquery.NewDocumentFromReader(resp2.Body)
		if err3 != nil {
			panic(err)
		}
		name := Word{
			label:    s,
			forms:    make(map[string]struct{}),
			meanings: make(map[string]*Meaning),
		}
		doc2.Find("#mainContent > div:nth-child(6) > div > div:nth-child(1) > ul").Find("li").Each(func(i int, selection *goquery.Selection) {
			name.forms[strings.TrimSpace(selection.Text())] = struct{}{} // add the form to the name
		})
		if len(name.forms) != 0 { // some words are not found on the page
			names = append(names, &name)
		}
		if done != 0 {
			clearLine()
		}
		done++
		fmt.Print("Done: " + strconv.Itoa(done) + ", Waiting: " + strconv.Itoa(len(nameLabels)-done))
	}
	nameMeaning := Meaning{
		label: "name",
		words: make(map[string]*Word),
	}
	for i, name := range names {
		nameMeaning.words[name.label] = names[i] // add all names to the meaning
		words[name.label] = names[i]             // add the names to the global word list
		names[i].meanings[nameMeaning.label] = &nameMeaning
	}
	meanings[nameMeaning.label] = &nameMeaning // add the meaning to global meaning list
	defer resp2.Body.Close()
	reportEndOfCommand("\nDone. ")
}

func optimize() {
	badWords := []string{"budu", "byste", "jsi", "by", "se", "s", "z", "ze", "bychom", "bysme", "jsem", "jste", "bys", "bych", "jsme", "budeš", "budu", "bude", "budeme", "budete"}
	optimized := 0
	merged := 0
	if len(meanings) != 0 && len(words) != 0 {
		for s := range words {
			fmt.Print("Optimized: " + strconv.Itoa(optimized) + ", Remaining: " + strconv.Itoa(len(words)-optimized))
			word := words[s]
			for _, badWord := range badWords {
				for s2 := range word.forms {
					if len(strings.Split(s2, " ")) > 1 {
						// search bad words only if the string has more parts
						if strings.HasPrefix(s2, badWord+" ") {
							delete(word.forms, s2)                                            // delete old item
							word.forms[strings.TrimSpace(s2[len(badWord+" "):])] = struct{}{} // add new trimmed item
						} else if strings.HasSuffix(s2, " "+badWord) {
							delete(word.forms, s2)
							word.forms[strings.TrimSpace(s2[0:len(s2)-len(badWord)])] = struct{}{}
						} else if strings.Contains(s2, " "+badWord+" ") {
							delete(word.forms, s2)
							word.forms[strings.TrimSpace(strings.ReplaceAll(s2, " "+badWord+" ", ""))] = struct{}{}
						}
					}
				}
			}
			delete(word.forms, "—") // delete all forms that are just this symbol
			optimized++
			clearLine()
		}
		for s1 := range words {
			w1 := words[s1]
			for s2 := range words {
				if s1 == s2 {
					break
				}
				w2 := words[s2]
				b := true
				for form := range w1.forms {
					if _, ok := w2.forms[form]; !ok {
						b = false
						break
					}
				}
				if b {
					Logger.PrintColor("These words could be merged. Which one do you want to keep?\n", Logger.YELLOW)
					fmt.Println(w1.toString(true, false))
					fmt.Println(w2.toString(true, false))
					input := readLine()
					var keep *Word
					var mergeOut *Word
					if input == w1.label {
						keep = words[w1.label]
						mergeOut = words[w2.label]
					} else if input == w2.label {
						keep = words[w2.label]
						mergeOut = words[w1.label]
					} else {
						fmt.Println("Nothing will be merged.")
						continue
					}
					for s := range mergeOut.meanings {
						keep.meanings[s] = meanings[s]       // transfer meanings from mergeOut into Keep
						meanings[s].words[keep.label] = keep // transfer keep into meanings
					}
					for s := range mergeOut.forms {
						keep.forms[s] = struct{}{} // transfer all meanings from one to another
					}
					mergeOut.delete()
					merged++
				}
			}
		}
	}
	fmt.Print("Merged words: " + strconv.Itoa(merged))
	reportEndOfCommand("Done. ")
}

func modifyWord(command string) {
	command = command[len("modify meaning "):]
	command = strings.ReplaceAll(command, ", ", ",")
	splits := strings.Split(command, " ")
	if len(splits) < 2 {
		Logger.PrintColor("Command parsing failed.", Logger.RED)
		reportEndOfCommand("")
		return
	}
	var adds []string
	var dels []string
	newLabel := ""
	for _, split := range splits {
		if strings.HasPrefix(split, "--add=") {
			adds = strings.Split(split[len("--add="):], ",")
			continue
		}
		if strings.HasPrefix(split, "--delete=") || strings.HasPrefix(split, "-d=") {
			dels = strings.Split(split[strings.IndexAny(split, "="):], ",")
			continue
		}
		if strings.HasPrefix(split, "--label") || strings.HasPrefix(split, "-l=") {
			newLabel = split[strings.IndexAny(split, "="):]
			continue
		}
	}
	if adds == nil && dels == nil && newLabel == "" {
		reportEndOfCommand("No modification specified. ")
		return
	}
	if _, ok := words[splits[1]]; ok {
		w := words[splits[1]]
		for _, add := range adds {
			w.forms[add] = struct{}{}
		}
		for _, del := range dels {
			delete(w.forms, del)
		}
		if newLabel != "" {
			if _, ok2 := words[newLabel]; !ok2 {
				w.label = newLabel
			} else {
				Logger.PrintColor("Can not change label, because there is already existing word with that label.", Logger.YELLOW)
			}
		}
	} else {
		Logger.PrintColor("Word not found.", Logger.YELLOW)
		reportEndOfCommand("")
		return
	}
	reportEndOfCommand("Done. ")
}

func modifyMeaning(command string) {
	command = command[len("modify meaning "):]
	command = strings.ReplaceAll(command, ", ", ",")
	splits := strings.Split(command, " ")
	if len(splits) < 2 {
		Logger.PrintColor("Command parsing failed.", Logger.RED)
		reportEndOfCommand("")
		return
	}
	var adds []string
	var dels []string
	newLabel := ""
	flagRecursive := false
	for _, split := range splits {
		if strings.HasPrefix(split, "--add=") {
			adds = strings.Split(split[len("--add="):], ",")
			continue
		}
		if strings.HasPrefix(split, "--delete=") || strings.HasPrefix(split, "-d=") {
			dels = strings.Split(split[strings.IndexAny(split, "="):], ",")
			continue
		}
		if strings.HasPrefix(split, "--label") || strings.HasPrefix(split, "-l=") {
			newLabel = split[strings.IndexAny(split, "="):]
			continue
		}
		if split == "--recursive" || split == "-r" {
			flagRecursive = true
		}
	}
	if adds == nil && dels == nil && newLabel == "" {
		reportEndOfCommand("No modification specified. ")
		return
	}
	if _, ok := meanings[splits[1]]; ok {
		m := meanings[splits[1]]
		for _, add := range adds {
			// for every word:
			if _, ok2 := m.words[add]; !ok2 { // word is not in meaning
				if _, ok3 := words[add]; !ok3 { // word does not exist in word list
					generateWord("generate word " + add) // this should generate the word
				}
				// now the word must exist
				w := words[add]         // pick the existing word
				w.meanings[m.label] = m // add new meaning to the existing word
				m.words[w.label] = w    // add picked word to the meaning
			}
		}
		for _, del := range dels {
			// for every word:
			if _, ok2 := m.words[del]; ok2 { // word is in selected meaning
				if flagRecursive {
					words[del].delete() // this will delete every trace of that word
				} else {
					delete(words[del].meanings, m.label) // remove this meaning from the word
					delete(m.words, del)                 // remove the word from this meaning
				}
			}
		}
		if newLabel != "" {
			if _, ok2 := meanings[newLabel]; !ok2 {
				m.label = newLabel
			} else {
				Logger.PrintColor("Can not change label, because there is already existing meaning with that label.", Logger.YELLOW)
			}
		}
	} else {
		Logger.PrintColor("Meaning not found.", Logger.YELLOW)
		reportEndOfCommand("")
		return
	}
	reportEndOfCommand("Done. ")
}

func dump(command string) {
	cmd := command[len("dump "):]
	splits := strings.Split(cmd, " ")
	for i, split := range splits {
		splits[i] = strings.ReplaceAll(strings.TrimSpace(split), "\"", "")
	}
	if len(splits) < 1 {
		Logger.PrintColor("Parsing failed. ", Logger.RED)
		reportEndOfCommand("")
		return
	}
	flagAppend := false
	if len(splits) == 2 {
		if strings.HasPrefix(splits[2], "--append") || strings.HasPrefix(splits[2], "-a") {
			flagAppend = true
		}
	}
	fname := splits[0]

	if fname == "" {
		reportEndOfCommand("No filename specified, unable to write data. ")
		return
	}
	var f *os.File
	var err error
	if flagAppend {
		f, err = os.OpenFile(fname, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	} else {
		f, err = os.OpenFile(fname, os.O_CREATE|os.O_WRONLY, 0644)
	}
	if err != nil {
		Logger.PrintColor("Failed to create new file: "+err.Error(), Logger.RED)
		reportEndOfCommand("")
		return
	}
	defer f.Close()
	data := ""
	for _, meaning := range meanings {
		data += meaning.toString(false, true) + "\n"
	}
	data += "\n"
	for _, word := range words {
		data += word.toString(false, true) + "\n"
	}
	f.WriteString(data)
	reportEndOfCommand("Done. ")
}

func load(command string) {
	splits := strings.Split(command, " ")
	if len(splits) != 2 {
		Logger.PrintColor("Parsing failed. ", Logger.RED)
		reportEndOfCommand("")
	}
	fname := splits[1]
	s, err := ioutil.ReadFile(fname)
	if err != nil {
		Logger.ColorText("Failed to load file: "+err.Error()+". ", Logger.RED)
		reportEndOfCommand("")
		return
	}
	content := string(s)
	rx1 := regexp.MustCompile("\\{.*?\\}")
	rx2 := regexp.MustCompile("\\(.*?\\)")
	content = strings.ReplaceAll(content, "\n", "")
	lines := strings.Split(content, ";")
	for _, line := range lines {
		if strings.HasPrefix(line, "$WORD_") {
			c := rx2.FindString(line)
			c = strings.ReplaceAll(c, "(", "")
			c = strings.ReplaceAll(c, ")", "")
			c = strings.TrimSpace(c)
			items := strings.Split(c, "|")
			for i, item := range items {
				items[i] = strings.TrimSpace(item)
			}
			w := Word{
				label:    strings.ReplaceAll(strings.ReplaceAll(rx1.FindString(line), "}", ""), "{", "")[len("WORD_"):],
				forms:    make(map[string]struct{}),
				meanings: make(map[string]*Meaning),
			}
			for _, item := range items {
				w.forms[item] = struct{}{}
			}
			words[w.label] = &w
		}
	}
	for _, line := range lines {
		if strings.HasPrefix(line, "$MEANING_") {
			label := rx1.FindString(line)
			label = strings.ReplaceAll(label, "{", "")
			label = strings.ReplaceAll(label, "}", "")
			label = strings.TrimSpace(label)
			c := rx2.FindString(line)
			c = strings.ReplaceAll(c, "(", "")
			c = strings.ReplaceAll(c, ")", "")
			c = strings.ReplaceAll(c, "$", "")
			c = strings.TrimSpace(c)
			items := strings.Split(c, "|")
			for i, item := range items {
				items[i] = strings.TrimSpace(item)
			}
			m := Meaning{
				label: label[len("MEANING_"):],
				words: make(map[string]*Word),
			}
			for _, item := range items {
				trim := item[len("WORD_"):]
				w := words[trim]
				if w != nil {
					m.words[trim] = w
					w.meanings[m.label] = &m
				}
			}
			meanings[m.label] = &m
		}
	}
	reportEndOfCommand("Done. ")
}

func printWords(command string) {
	command = strings.TrimSpace(command[len("print words"):])
	splits := strings.Split(command, " ")
	wordLabels := make([]string, 0)
	for i := 0; i < len(splits); i++ {
		splits[i] = strings.TrimSpace(splits[i])
	}
	for _, split := range splits {
		if !strings.HasPrefix(split, "-") {
			wordLabels = append(wordLabels, split)
		}
	}
	for i := range wordLabels {
		wordLabels[i] = strings.TrimSpace(wordLabels[i])
		wordLabels[i] = strings.ReplaceAll(wordLabels[i], ",", "")
		wordLabels[i] = strings.ReplaceAll(wordLabels[i], " ", "_")
		wordLabels[i] = strings.ReplaceAll(wordLabels[i], "\"", "")
	}
	flagVerbose := false
	flagAbnf := false
	for _, split := range splits {
		if split == "--verbose" || split == "-v" {
			flagVerbose = true
			continue
		}
		if split == "--abnf" {
			flagAbnf = true
			continue
		}
	}
	if (len(wordLabels) == 1 && wordLabels[0] == "") || len(wordLabels) == 0 {
		for _, word := range words {
			fmt.Println(word.toString(flagVerbose, flagAbnf))
		}
	} else {
		for _, label := range wordLabels {
			if _, ok := words[label]; ok {
				fmt.Println(words[label].toString(flagVerbose, flagAbnf))
			} else {
				Logger.PrintColor("Word \""+label+"\" was not found.\n", Logger.YELLOW)
			}
		}
	}
	reportEndOfCommand("Done. ")
}

func printMeanings(command string) {
	command = strings.TrimSpace(command[len("print meanings"):])
	splits := strings.Split(command, " ")
	meaningLabels := make([]string, 0)
	for i := 0; i < len(splits); i++ {
		splits[i] = strings.TrimSpace(splits[i])
	}
	for _, split := range splits {
		if !strings.HasPrefix(split, "-") {
			meaningLabels = append(meaningLabels, split)
		}
	}
	for i := range meaningLabels {
		meaningLabels[i] = strings.TrimSpace(meaningLabels[i])
		meaningLabels[i] = strings.ReplaceAll(meaningLabels[i], ",", "")
		meaningLabels[i] = strings.ReplaceAll(meaningLabels[i], " ", "_")
		meaningLabels[i] = strings.ReplaceAll(meaningLabels[i], "\"", "")
	}
	flagVerbose := false
	flagAbnf := false
	for _, split := range splits {
		if split == "--recursive" || split == "-r" {
			flagVerbose = true
			continue
		}
		if split == "--abnf" {
			flagAbnf = true
			continue
		}
	}
	if (len(meaningLabels) == 1 && meaningLabels[0] == "") || len(meaningLabels) == 0 {
		for _, meaning := range meanings {
			fmt.Println(meaning.toString(flagVerbose, flagAbnf))
		}
	} else {
		for _, label := range meaningLabels {
			if _, ok := meanings[label]; ok {
				fmt.Println(meanings[label].toString(flagVerbose, flagAbnf))
			} else {
				Logger.PrintColor("Meaning \""+label+"\" was not found.\n", Logger.YELLOW)
			}
		}
	}
	reportEndOfCommand("Done. ")
}

func deleteWord(command string) {
	command = strings.TrimSpace(command[len("delete word"):])
	splits := strings.Split(command, " ")
	wordToDelete := splits[0]
	wordToDelete = strings.ReplaceAll(wordToDelete, " ", "_")
	if m, ok := words[wordToDelete]; ok {
		fmt.Println("Do you really want to delete this word? (y/N)")
		fmt.Println(m.toString(true, false))
		cmd := readLine()
		if cmd == "y" {
			m.delete()
			reportEndOfCommand("Deleted. ")
			return
		} else {
			reportEndOfCommand("Cancelled. ")
			return
		}
	} else {
		fmt.Println("There is no word \"" + wordToDelete + "\".")
	}
	reportEndOfCommand("Done. ")
}

func deleteMeaning(command string) {
	command = strings.TrimSpace(command[len("delete meaning"):])
	splits := strings.Split(command, " ")
	meaningToDelete := splits[0]
	meaningToDelete = strings.ReplaceAll(meaningToDelete, " ", "_")
	flagRecursive := false
	for _, split := range splits {
		if split == "--recursive" || split == "-r" {
			flagRecursive = true
			continue
		}
	}
	if m, ok := meanings[meaningToDelete]; ok {
		if flagRecursive {
			fmt.Println("Do you really want to delete this meaning and all associated words? (y/N)")
			fmt.Println(m.toString(true, false))
		} else {
			fmt.Println("Do you really want to delete this meaning (words won't be deleted)? (y/N)")
			fmt.Println(m.toString(false, false))
		}
		cmd := readLine()
		if cmd == "y" {
			m.delete(flagRecursive)
			reportEndOfCommand("Deleted. ")
			return
		} else {
			reportEndOfCommand("Cancelled. ")
			return
		}
	} else {
		fmt.Println("There is no meaning \"" + meaningToDelete + "\".")
	}
	reportEndOfCommand("Done. ")
}

func generateWord(command string) {
	command = strings.TrimSpace(command[len("generate word"):])
	splits := strings.Split(command, " ")
	wordLabel := splits[0]
	wordLabel = strings.ReplaceAll(wordLabel, " ", "_")
	forms := fetchForms(strings.TrimSpace(splits[0]), false)
	if _, ok := words[wordLabel]; ok {
		existingWord := words[wordLabel]
		for form := range forms {
			existingWord.forms[form] = struct{}{}
		}
	} else {
		newWord := Word{ // create a new Word
			label:    wordLabel,
			forms:    make(map[string]struct{}),
			meanings: make(map[string]*Meaning),
		}
		for form := range forms { // add all found form to new Word
			newWord.forms[form] = struct{}{}
		}
		words[wordLabel] = &newWord
	}
	fmt.Println(words[wordLabel].toString(true, false))
	reportEndOfCommand("Done. ")
}

func reportEndOfCommand(s string) {
	fmt.Println(Logger.ColorText(s+"What next?", Logger.GREEN) + Logger.ColorText(" (type \"help\" for help or \"quit\" to quit Gimir)", Logger.GRAY))
}

const MAX_DEPTH = 2

func generateMeaning(command string) {
	command = strings.TrimSpace(command[len("generate meaning"):])
	command = strings.ReplaceAll(command, ", ", ",")
	splits := strings.Split(command, " ")
	meaningLabel := splits[0]
	meaningLabel = strings.ReplaceAll(meaningLabel, " ", "_")
	rawSeedWords := splits[2]
	rawSeedWords = strings.ReplaceAll(rawSeedWords, "\"", "")
	seedWords := strings.Split(rawSeedWords, ",")
	for i := 0; i < len(seedWords); i++ {
		seedWords[i] = strings.TrimSpace(seedWords[i])
	}
	flagAutofill := false
	flagVerbose := false
	flagTrust := false
	for _, split := range splits {
		if split == "--auto" || split == "-a" {
			flagAutofill = true
			continue
		}
		if split == "--verbose" || split == "-v" {
			flagVerbose = true
			continue
		}
		if split == "--trust" || split == "-t" {
			flagTrust = true
			continue
		}
	}

	if _, ok := meanings[meaningLabel]; ok {
		Logger.PrintColor("WARNING: This meaning already exists. Do you want to overwrite it? (y/N) ", Logger.YELLOW)
		conf := readLine()
		if conf == "y" {
			fmt.Println("Deleted meaning: " + meanings[meaningLabel].toString(flagVerbose, false))
		} else {
			fmt.Println("Action cancelled. What next? (type \"help\" for help or \"quit\" to quit)")
			return
		}
	}

	type ref struct {
		text  string
		depth int
	}

	// put the seed words into waitingList and into foundWords
	foundWords := make(map[string]bool)
	wordsWaiting := make([]ref, len(seedWords))
	for i := 0; i < len(seedWords); i++ {
		wordsWaiting[i] = ref{
			text:  seedWords[i],
			depth: 0,
		}
	}
	for len(wordsWaiting) != 0 {
		w := wordsWaiting[0]
		if !flagAutofill {
			fmt.Print("Waiting: " + strconv.Itoa(len(wordsWaiting)) + ", Done: " + strconv.Itoa(len(foundWords)) + ", Keep? (Y/n): " + meaningLabel + " -> " + w.text + " ")
			conf := readLine()
			//print("\u001b[1F") // move cursor line up
			print("\u001b[1F") // move cursor line up
			clearLine()
			if conf == "n" {
				wordsWaiting = wordsWaiting[1:]
				foundWords[w.text] = false
				continue
			}
		}
		if w.depth > MAX_DEPTH {
			wordsWaiting = wordsWaiting[1:]
			continue
		}
		wordsWaiting = wordsWaiting[1:]
		foundWords[w.text] = true
		for fetched := range fetchSynonyms(w.text, flagVerbose) {
			_, ok := foundWords[fetched]
			inWaiting := false
			for _, word := range wordsWaiting {
				if fetched == word.text {
					inWaiting = true
					break
				}
			}
			if !inWaiting && !ok && w.depth <= MAX_DEPTH {
				wordsWaiting = append(wordsWaiting, ref{fetched, w.depth + 1})
			}
		}
	}
	final := make([]string, 0)
	if !flagTrust {
		keepEditing := true
		fmt.Println(Logger.ColorText("\nDone. Here are found synonyms for meaning ", Logger.WHITE) +
			Logger.ColorText(meaningLabel, Logger.BLUE) + ":")
		Logger.PrintColor("If you do/don't want to keep some of them, write them separated by commas to toggle it. (green = keep, red = don't keep).\n", Logger.GRAY)
		Logger.PrintColor("Press enter (write empty line) when you are done. Alternative forms of each word will be searched.\n", Logger.GRAY)
		s := fmt.Sprint(foundWords)
		s = strings.ReplaceAll(s, "true", "")
		s = strings.ReplaceAll(s, "false", "")
		s = s[4 : len(s)-2]
		ss := strings.Split(s, ":")
		for i := 0; i < len(ss); i++ {
			ss[i] = strings.TrimSpace(ss[i])
		}
		for keepEditing {
			cmd := exec.Command("stty", "size")
			cmd.Stdin = os.Stdin
			out, err := cmd.Output()
			if err != nil {
				panic(err)
			}
			terminalWidth, _ := strconv.Atoi(strings.Split(strings.TrimSpace(string(out)), " ")[1])
			maxColumn := terminalWidth / 20
			columns := int(math.Min(float64(maxColumn), math.Ceil(float64(len(ss))/5.0)))
			i := 0
			l := 0
			for i < len(ss) {
				for j := 0; j < columns && i < len(ss); j++ {
					if foundWords[ss[i]] {
						Logger.PrintColor(" - "+ss[i]+"\u001b[0G"+fmt.Sprintf("\u001b[%dC", 20*(j+1)), Logger.GREEN)
					} else {
						Logger.PrintColor(" - "+ss[i]+"\u001b[0G"+fmt.Sprintf("\u001b[%dC", 20*(j+1)), Logger.RED)
					}
					i++
				}
				fmt.Print("\n") // break line after all columns
				l++
			}
			input := readLine()
			if input == "" {
				keepEditing = false
				for s2, b := range foundWords {
					if b {
						final = append(final, s2)
					}
				}
			} else {
				toggles := strings.Split(input, ",")
				for j := 0; j < len(toggles); j++ {
					toggles[j] = strings.TrimSpace(toggles[j])
				}
				for _, toggle := range toggles {
					if _, ok := foundWords[toggle]; ok {
						foundWords[toggle] = !foundWords[toggle]
					}
				}
				for j := 0; j < l+1; j++ {
					print("\u001b[1A")
					clearLine()
				}
			}
		}
	} else {
		for s, b := range foundWords {
			if b {
				final = append(final, s)
			}
		}
	}

	newMeaning := Meaning{
		label: meaningLabel,
		words: make(map[string]*Word),
	}
	meanings[meaningLabel] = &newMeaning // add new Meaning to meanings list

	for _, currentLabel := range final {
		foundForms := fetchForms(currentLabel, false)
		currentLabel = strings.ReplaceAll(currentLabel, " ", "_")
		if _, ok := words[currentLabel]; ok {
			existingWord := words[currentLabel]
			for form := range foundForms {
				existingWord.forms[form] = struct{}{}
			}
			existingWord.meanings[meaningLabel] = &newMeaning   // add the new Meaning to the existing Word
			newMeaning.words[existingWord.label] = existingWord // add the existing Word to the newly created Meaning
		} else {
			newWord := Word{ // create a new Word
				label:    currentLabel,
				forms:    make(map[string]struct{}),
				meanings: make(map[string]*Meaning),
			}
			for form := range foundForms { // add all found form to new Word
				newWord.forms[form] = struct{}{}
			}
			newWord.meanings[meaningLabel] = &newMeaning // add new Meaning to new Word
			newMeaning.words[currentLabel] = &newWord    // add new Word to new Meaning
			words[currentLabel] = &newWord               // add new Words to words list
		}
	}
	Logger.PrintColor("RESULT: \n", Logger.GREEN)
	fmt.Println(newMeaning.toString(false, false))
	reportEndOfCommand("Done. ")
}

func fetchForms(s string, verbose bool) map[string]struct{} {
	out := make(map[string]struct{})
	s = strings.TrimSpace(s)

	wikiBuf := fetchFormsFromWiki(s, verbose)
	dobrySlovnikBuf := fetchFormsFromDobrySlovnik(s, verbose)

	for w := range wikiBuf {
		out[w] = struct{}{}
	}
	for w := range dobrySlovnikBuf {
		out[w] = struct{}{}
	}

	return out
}

func fetchFormsFromDobrySlovnik(s string, verbose bool) map[string]struct{} {
	buf := make(map[string]struct{})

	resp, err := http.Get("http://www.dobryslovnik.cz/cestina?" + s)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 && verbose {
		Logger.PrintColor("Wiktionary page not found for word \""+s+"\".\n", Logger.GRAY)
		return nil
	}

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	doc.Find(".form").Each(func(i int, selection *goquery.Selection) {
		ch := selection.Contents().Nodes
		for _, node := range ch {
			if node.Type == html.TextNode {
				content := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(strings.ReplaceAll(node.Data, "!", ""), "\n", " "), "—", ""))
				if content != "" {
					buf[content] = struct{}{}
				}
			}
		}
	})

	return buf
}

func fetchFormsFromWiki(s string, verbose bool) map[string]struct{} {
	resp, err := http.Get("https://cs.wiktionary.org/wiki/" + s)
	buf := make(map[string]struct{})
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && verbose {
		Logger.PrintColor("Wiktionary page not found for word \""+s+"\".\n", Logger.GRAY)
		return nil
	}
	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		panic(err)
	}
	f := doc.Find("#čeština").Parent().NextUntil("h2")
	rx := regexp.MustCompile("\\(.*?\\)")
	rx2 := regexp.MustCompile("\\[.*?]")
	f.Each(func(i int, selection *goquery.Selection) {
		selection.Find("td").Each(func(i int, selection2 *goquery.Selection) {
			text := selection2.Text()
			text = rx.ReplaceAllString(text, "")
			text = rx2.ReplaceAllString(text, "")
			vals := strings.FieldsFunc(text, func(r rune) bool {
				return r == ',' || r == ';' || r == '/' || r == '\n'
			})
			for _, val := range vals {
				val = strings.TrimSpace(val)
				//outMap[val] = empty
				buf[val] = struct{}{}
			}
		})
	})
	buf[s] = struct{}{}
	return buf
}

func fetchSynonyms(w string, verbose bool) map[string]struct{} {
	out := make(map[string]struct{})
	w = strings.TrimSpace(w)

	wikiBuf := fetchSynonymsFromWiki(w, verbose)
	dobrySlovnikBuf := fetchSynonymsFromDobrySlovnik(w, verbose)

	for s := range wikiBuf {
		s = strings.TrimSpace(s)
		if s != "" {
			out[s] = struct{}{}
		}
	}
	for s := range dobrySlovnikBuf {
		s = strings.TrimSpace(s)
		if s != "" {
			out[s] = struct{}{}
		}
	}

	return out
}

func fetchSynonymsFromDobrySlovnik(w string, verbose bool) map[string]struct{} {
	buf := make(map[string]struct{})
	resp, err := http.Get("http://www.dobryslovnik.cz/cestina?" + w)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 && verbose {
		Logger.PrintColor("DobrySlovnik page not found for word \""+w+"\".\n", Logger.GRAY)
		return nil
	}

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	doc.Find(".translation").Each(func(i int, selection *goquery.Selection) {
		t := selection.Find("a").Text()
		t = strings.TrimSpace(strings.ReplaceAll(t, "\n", " "))
		buf[t] = struct{}{}
		//Logger.Debug(t)
	})
	return buf
}

func fetchSynonymsFromWiki(w string, verbose bool) map[string]struct{} {
	buf := make(map[string]struct{})
	resp, err := http.Get("https://cs.wiktionary.org/wiki/" + w)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 && verbose {
		Logger.PrintColor("Wiktionary page not found for word \""+w+"\".\n", Logger.GRAY)
		return nil
	}

	doc, err := goquery.NewDocumentFromReader(resp.Body)
	if err != nil {
		panic(err)
	}

	synonyms := doc.Find("#čeština").Parent().NextUntil("h2")
	lis := synonyms.Find("#synonyma").Parent().Next().Find("li")
	lis.Each(func(i int, selection *goquery.Selection) {
		text := selection.Text()
		rx := regexp.MustCompile("\\(.*?\\)")
		text = rx.ReplaceAllString(text, "")
		rx2 := regexp.MustCompile("\\[.*?]")
		text = rx2.ReplaceAllString(text, "")
		vals := strings.FieldsFunc(text, func(r rune) bool {
			return r == ',' || r == ';' || r == '/' || r == '\n'
		})
		for _, val := range vals {
			val = strings.TrimSpace(val)
			if val != "—" && val != "–" && val != "-" && val != "---" {
				if verbose {
					Logger.PrintColor(w+" -> "+val+"\n", Logger.GRAY)
				}
				buf[val] = struct{}{}
				//out[val] = empty
			}
		}
	})

	similar := doc.Find("#čeština").NextUntil("h2") //.Find("#synonyma")
	lis = similar.Find("#související").Parent().Next().Find("li")
	lis.Each(func(i int, selection *goquery.Selection) {
		text := selection.Text()
		rx := regexp.MustCompile("\\(.*?\\)")
		text = rx.ReplaceAllString(text, "")
		rx2 := regexp.MustCompile("\\[.*?]")
		text = rx2.ReplaceAllString(text, "")

		vals := strings.FieldsFunc(text, func(r rune) bool {
			return r == ',' || r == ';' || r == '/'
		})
		for _, val := range vals {
			val = strings.TrimSpace(val)
			if val != "—" && val != "–" && val != "-" && val != "---" {
				if verbose {
					Logger.PrintColor(w+" -> "+val+"\n", Logger.GRAY)
				}
				//out[val] = empty
				buf[val] = struct{}{}
			}
		}
	})
	return buf
}

func printHelp() {
	Logger.PrintColor("Commands: ", Logger.WHITE)
	Logger.PrintColor("(all flags are optional)\n", Logger.GRAY)
	fmt.Println("    - generate meaning " + Logger.ColorText("[meaning]", Logger.BLUE) +
		" from " + Logger.ColorText("[list of words]", Logger.BLUE) + Logger.ColorText(" --auto --trust", Logger.PINK))
	Logger.PrintColor("        -> Will generate new meaning with given label [meaning] from given [list of words]. List items must be separated by commas.\n", Logger.GRAY)

	fmt.Println("    - generate word " + Logger.ColorText("[word]", Logger.BLUE))
	Logger.PrintColor("        -> Will generate new word with given label [word].\n", Logger.GRAY)

	fmt.Println("    - delete meaning " + Logger.ColorText("[meaning]", Logger.BLUE) + Logger.ColorText("--recursive", Logger.PINK))
	Logger.PrintColor("        -> Will delete meaning with given label [meaning].\n", Logger.GRAY)

	fmt.Println("    - delete word " + Logger.ColorText("[word]", Logger.BLUE))
	Logger.PrintColor("        -> Deletes word with given label [word].\n", Logger.GRAY)

	fmt.Println("    - modify meaning " + Logger.ColorText("[meaning]", Logger.BLUE) +
		Logger.ColorText(" --add=", Logger.PINK) +
		Logger.ColorText("[list of words] ", Logger.BLUE) +
		Logger.ColorText("--remove=", Logger.PINK) +
		Logger.ColorText("[list of words] ", Logger.BLUE) +
		Logger.ColorText("--label=", Logger.PINK) +
		Logger.ColorText("[new label] ", Logger.BLUE) +
		Logger.ColorText("--recursive", Logger.PINK))
	Logger.PrintColor("        -> Modifies meaning with given label [word]. Adds/removes all words in [list of words]. "+
		"New words are automatically generated if needed. \n", Logger.GRAY)

	fmt.Println("    - modify word " + Logger.ColorText("[meaning]", Logger.BLUE) +
		Logger.ColorText(" --add=", Logger.PINK) +
		Logger.ColorText("[list of forms] ", Logger.BLUE) +
		Logger.ColorText("--remove=", Logger.PINK) +
		Logger.ColorText("[list of forms] ", Logger.BLUE) +
		Logger.ColorText("--label=", Logger.PINK) +
		Logger.ColorText("[new label] ", Logger.BLUE))
	Logger.PrintColor("        -> Modifies given word. Adds/removes all forms in [list of forms].\n", Logger.GRAY)

	fmt.Println("    - print meanings " + Logger.ColorText("[list of meanings]", Logger.BLUE) +
		Logger.ColorText("--recursive --abnf", Logger.PINK))
	Logger.PrintColor("        -> Prints out all given meaning. List items must be separated by spaces. Leave empty to print all meanings. \n", Logger.GRAY)

	fmt.Println("    - print words" + Logger.ColorText("[list of words]", Logger.BLUE) + Logger.ColorText("--abnf", Logger.PINK))
	Logger.PrintColor("        -> Prints out all given words. List items must be separated by spaces. Leave empty to print all words. \n", Logger.GRAY)

	fmt.Println("    - load " + Logger.ColorText("[filename]", Logger.BLUE))
	Logger.PrintColor("        -> Loads data from given ABNF grammar file.\n", Logger.GRAY)

	fmt.Println("    - dump " + Logger.ColorText("[filename]", Logger.BLUE) + Logger.ColorText("--append", Logger.PINK))
	Logger.PrintColor("        -> Writes ALL data to given file as ABNF grammar.\n\n", Logger.GRAY)

	fmt.Println("    - optimize")
	Logger.PrintColor("        -> Optimizes all data. Deletes and trims garbage words in all forms of every word and searches for words that could be merged.\n\n", Logger.GRAY)

	Logger.PrintColor("Flags:\n", Logger.WHITE)

	Logger.PrintColor("    --auto (-a)\n", Logger.PINK)
	Logger.PrintColor("        -> Run fully automatically without requiring manual confirmation for each word.\n", Logger.GRAY)

	Logger.PrintColor("    --trust (-t)\n", Logger.PINK)
	Logger.PrintColor("        -> Don't ask for confirmation at the end of synonym search.\n", Logger.GRAY)

	Logger.PrintColor("    --recursive (-r)\n", Logger.PINK)
	Logger.PrintColor("        -> Perform action recursively to all associated child items. \n", Logger.GRAY)
	Logger.PrintColor("        -> When combined with deletion of words, it will completely remove the word from data, not just the reference from meaning. \n", Logger.GRAY)
	Logger.PrintColor("        -> When combined with print, it will print the meanings with expanded associated words. \n", Logger.GRAY)

	Logger.PrintColor("    --add=", Logger.PINK)
	Logger.PrintColor("[list of words]\n", Logger.BLUE)
	Logger.PrintColor("        -> List of words to be added. Must be enclosed in double quotes. Words are separated by commas.\n", Logger.GRAY)

	Logger.PrintColor("    --delete=", Logger.PINK)
	Logger.PrintColor("[list of words]", Logger.BLUE)
	Logger.PrintColor(" (-d)\n", Logger.PINK)
	Logger.PrintColor("        -> List of words to be removed. Must be enclosed in double quotes. Words are separated by commas.\n", Logger.GRAY)

	Logger.PrintColor("    --label=", Logger.PINK)
	Logger.PrintColor("[label]", Logger.BLUE)
	Logger.PrintColor(" (-l)\n", Logger.PINK)
	Logger.PrintColor("        -> Changes label to new given label. \n", Logger.GRAY)

	Logger.PrintColor("    --abnf\n", Logger.PINK)
	Logger.PrintColor("        -> Print the selected items with ABNF format. Flag \"--verbose\" has no effect if flag \"--abnf\" is enabled. \n", Logger.GRAY)

	Logger.PrintColor("    --append (-a)\n", Logger.PINK)
	Logger.PrintColor("        -> Append data to given file instead of overwriting its content. \n", Logger.GRAY)

	reportEndOfCommand("End of help. ")
}

func clearLine() {
	print("\u001b[0G")
	print("\u001b[2K")
}

func readLine() string {
	s, err := reader.ReadString('\n')
	if err != nil {
		panic(err)
	}
	return strings.TrimSpace(s)
}
