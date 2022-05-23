import time
import uuid
import multiprocessing
from dialog import SpeechCloudWS, Dialog, ABNF_INLINE
import random
import asyncio
import logging
from pprint import pprint, pformat
from datetime import datetime
import sys
import json
import threading

MY_ID = "MODULE_ID"

sys.path.insert(0, "PATH_TO_STOMP_PACKAGE")
import stomp


def debug(text):
    # custom colored debug function
    print("\u001b[37;5;94m DEBUG:" + text + "\u001b[0m")


class MyDialog(Dialog, stomp.ConnectionListener):
    acknowledged = False
    lastEnrollID = None
    listen = False
    listening = False
    task = None
    conn = None
    synths = list()
    tts = None
    ready = False
    initialized = False

    async def checkerLoop(self):
        debug("Checker loop started.")
        while True:
            if self.listen and not self.listening:
                self.task = asyncio.create_task(self.startListening())
            if self.listening and not self.listen:
                self.listening = False
                self.task.cancel()
                try:
                    await self.task
                except asyncio.CancelledError:
                    debug("task cancelled")
                await self.display("Not listening anymore.")
            if len(self.synths) > 0:
                # TODO: splitting into smaller pieces for faster synthesis
                self.listen = False
                if self.task is not None:
                    self.task.cancel()
                    try:
                        await self.task
                    except asyncio.CancelledError:
                        debug("task cancelled")
                tts = self.synths.pop(0)
                debug("TTS: " + tts)
                await self.synthesize_and_wait(text=tts)
                if len(self.synths) == 0:  # if there is no more speech to be synthesized...
                    await asyncio.sleep(0.2)  # wait 200 ms to avoid self-echo
                    msg = dict()  # send the notification that TTS is done
                    msg["msgID"] = str(uuid.uuid4())
                    msg["msgOrigin"] = MY_ID
                    msg["msgType"] = "data"
                    msg["msgCode"] = "tts_done"
                    self.sendMessage("core", json.dumps(msg))
            await asyncio.sleep(0.05)

    def send_dialog_state(self):
        debug("SENDING DIALOG STATE: " + str(self.ready))
        # send info about readiness
        msg = dict()
        msg["msgID"] = str(uuid.uuid4())
        msg["msgOrigin"] = MY_ID
        msg["msgType"] = "data"
        msg["msgCode"] = "dialog_ready"
        msg["content"] = self.ready
        self.sendMessage("core", json.dumps(msg))

    def on_asr_initialized(self):
        self.initialized = True

    async def startListening(self):
        debug("listening...")
        self.listening = True
        await self.display("Listening now.")
        result = await self.recognize_and_wait_for_slu_result()
        await self.processResult(result)

    def on_message(self, frame):
        debug("Received msg: " + frame.body)
        try:
            json_msg = json.loads(frame.body)
            if all(key in json_msg for key in ("msgID", "msgCode", "msgType", "msgOrigin")):
                if json_msg["msgType"] == "data":
                    if not self.acknowledged:
                        debug("WARNING: this module received data message and has not finished enroll protocol yet.")
                    if json_msg["msgCode"] == "synth":
                        self.synths.append(str(json_msg["content"]))
                elif json_msg["msgType"] == "service":
                    if json_msg["msgCode"] == "confirm":
                        respID = json_msg["respID"]
                        if respID == self.lastEnrollID:
                            self.acknowledged = True
                            debug("Enroll protocol finished.")
                            self.send_dialog_state()
                            return
                        else:
                            debug("Received confirmation message from Core, but the respID is not recognized. Ignoring. ")
                    elif json_msg["msgCode"] == "who":
                        msg = dict()
                        self.lastEnrollID = str(uuid.uuid4())
                        msg["msgID"] = self.lastEnrollID
                        msg["msgOrigin"] = MY_ID
                        msg["msgType"] = "service"
                        msg["msgCode"] = "here"
                        msg["listeningOn"] = MY_ID
                        self.sendMessage("core", json.dumps(msg))
                        self.send_dialog_state()
                    elif json_msg["msgCode"] == "die":
                        debug("Received command to die. Initiating death protocol.")
                        msg = dict()
                        msg["msgID"] = str(uuid.uuid4())
                        msg["msgOrigin"] = MY_ID
                        msg["msgType"] = "service"
                        msg["msgCode"] = "dying"
                        msg["description"] = "Received service message with code: die"
                        self.sendMessage("broadcast", json.dumps(msg))
                        debug("Quitting now, goodbye.")
                        exit(0)
                    elif json_msg["msgCode"] == "start_listening":
                        debug("Received request to START listening")
                        self.listen = True
                    elif json_msg["msgCode"] == "stop_listening":
                        debug("Received request to STOP listening")
                        self.listen = False
                    else:
                        debug("Received message with unrecognized code. Ignoring.")
                else:
                    debug("Message has unknown type, ignoring.")
            else:
                debug("Message does not have mandatory headers, ignoring.")
        except Exception as e:
            logging.error("Failed to process message:", e)

    def sendMessage(self, dest, text):
        debug("---SENDING MESSAGE---")
        self.conn.send(destination=dest, content_type="text/plain", body=text)

    def on_disconnected(self):
        debug("Disconnected from broker, will try to reconnect in 10 secs.")
        time.sleep(10)
        self.connect_and_subscribe()

    def mapping(self, tags):
        return tags

    async def stopListening(self):
        debug("stopping listener")
        await self.display("Not listening anymore.")
        await self.sc.asr_pause()
        self.listening = False
        debug("not listening anymore")

    async def loadGrammars(self):
        GRM_ff = [
            {
                'entity': 'responses',
                'data': open('grammars/grammarV2.abnf', 'rt').read(),
                'mapping': self.mapping,
                'type': ABNF_INLINE
            },
        ]
        await self.define_slu_grammars(GRM_ff)
        debug("Grammars loaded.")

    async def processResult(self, result):
        debug("processing results...")
        asr_res = result.asr_result["result"]  # the text that was recognized
        if len(asr_res) != 0:
            debug("RESULT -> " + str(asr_res))
            msg = dict()
            msg["msgID"] = str(uuid.uuid4())
            msg["msgOrigin"] = MY_ID
            msg["msgType"] = "data"
            msg["msgCode"] = "speech"
            if result.responses:
                msg["meanings"] = result.entity_1best.all
            msg["content"] = asr_res
            self.sendMessage("core", json.dumps(msg))
        else:
            logging.warning("Length of ASR_RES is 0.")

    async def on_err(self):
        await self.display("ERR OCCURRED")

    async def on_asr_start(self):
        self.ready = True
        await self.display("ASR READY")

    async def on_tts_started(self):
        await self.display("TTS Started.")

    def on_asr_stop(self):
        self.listening = False

    async def main(self):
        conn = stomp.Connection([('BROKER_SERVER_ADDRESS', 'PORT')], heartbeats=(60_000, 60_000), auto_content_length=False)
        conn.set_listener('', self)
        self.conn = conn
        self.connect_and_subscribe()

        self.sc.on("asr_paused", self.on_asr_stop)
        self.sc.on("asr_ready", self.on_asr_start)
        self.sc.on("sc_error", self.on_err)
        self.sc.on("tts_started", self.on_tts_started)
        self.sc.on("asr_initialized", self.on_asr_initialized)

        task = asyncio.create_task(self.checkerLoop())
        await self.loadGrammars()
        self.ready = True
        self.send_dialog_state()
        await task

    def connect_and_subscribe(self):
        self.conn.connect(MY_ID, "PASSWORD", wait=True)
        self.conn.subscribe(destination="broadcast", ack="auto", id=str(uuid.uuid4()))
        self.conn.subscribe(destination=MY_ID, ack="auto", id=str(uuid.uuid4()))


if __name__ == '__main__':
    logging.basicConfig(format='%(asctime)s %(levelname)-10s %(message)s', level=logging.DEBUG)
    SpeechCloudWS.run(MyDialog, address="0.0.0.0", port=8892, static_path="./static")
