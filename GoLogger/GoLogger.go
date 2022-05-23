package GoLogger

import "fmt"
import "strings"
import "time"

const (
	CLEAR           = "\u001b[0m"
	GRAY            = "\u001b[38;5;245m"
	WHITE           = "\u001b[37;5;97m"
	YELLOW          = "\u001B[37;5;93m"
	RED             = "\u001b[31m"
	RED_ON_BLACK    = "\u001b[40m\u001B[1m\u001b[31m" //background black + foreground bright red + bold
	BLACK_ON_YELLOW = "\u001b[43;1m\u001b[30m"
	BLUE            = "\u001b[37;5;94m"
	GREEN           = "\u001B[37;5;92m"
	PINK            = "\u001b[35;5;1m"
)

func Trace(text ...string) {
	s := GRAY + time.Now().Format("2006 01.02. 15:04:05.000") + " - TRACE: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Info(text ...string) {
	s := WHITE + time.Now().Format("2006 01.02. 15:04:05.000") + " - INFO: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Warn(text ...string) {
	s := YELLOW + time.Now().Format("2006 01.02. 15:04:05.000") + " - WARN: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Err(text ...string) {
	s := RED + time.Now().Format("2006 01.02. 15:04:05.000") + " - ERROR: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Fatal(text ...string) {
	s := RED_ON_BLACK + time.Now().Format("2006 01.02. 15:04:05.000") + " - FATAL: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Debug(text ...string) {
	s := BLUE + time.Now().Format("2006 01.02. 15:04:05.000") + " - DEBUG: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func Success(text ...string) {
	s := GREEN + time.Now().Format("2006 01.02. 15:04:05.000") + " - SUCCESS: " + strings.Join(text, " ") + CLEAR
	fmt.Println(s)
}

func ColorText(s string, c string) string {
	return c + s + CLEAR
}

func PrintColor(s string, c string) {
	fmt.Print(c + s + CLEAR)
}
