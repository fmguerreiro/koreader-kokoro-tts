local Device = require("device")
local Event = require("ui/event")
local InfoMessage = require("ui/widget/infomessage")
local Screen = Device.screen
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local _ = require("gettext")
local sha2 = require("ffi/sha2")
local socket = require("socket")

local BRIDGE_URI = "koreader-tts://"
local CALLBACK_POLL_SECONDS = 0.1
local PAGE_TURN_DELAY_SECONDS = 0.25

local TtsBridge = WidgetContainer:extend{
    name = "ttsbridge",
    is_doc_only = true,
    narrating = false,
    request_counter = 0,
}

local function bookLanguageTag(language)
    local tag = language or G_reader_settings:readSetting("text_lang_fallback", "en-US")
    if type(tag) == "string" and tag:match("^%a[%w]*([%-_][%w]+)*$") then
        return tag:gsub("_", "-")
    end
    return "en-US"
end

local function base64Url(text)
    return (sha2.bin_to_base64(text):gsub("%+", "-"):gsub("/", "_"):gsub("=", ""))
end

local function normalizeText(text)
    return (text:gsub("%s+", " "):gsub("^%s+", ""):gsub("%s+$", ""))
end

function TtsBridge:init()
    self.ui.menu:registerToMainMenu(self)
end

function TtsBridge:getCurrentPageText()
    local x0, y0, x1, y1, page, is_reflow
    if self.ui.rolling then
        x0 = 0
        y0 = 0
        x1 = Screen:getWidth()
        y1 = Screen:getHeight()
    else
        page = self.ui:getCurrentPage()
        is_reflow = self.ui.document.configurable.text_wrap
        self.ui.document.configurable.text_wrap = 0
        local page_boxes = self.ui.document:getTextBoxes(page)
        if page_boxes and page_boxes[1][1].word then
            x0 = page_boxes[1][1].x0
            y0 = page_boxes[1][1].y0
            x1 = page_boxes[#page_boxes][#page_boxes[#page_boxes]].x1
            y1 = page_boxes[#page_boxes][#page_boxes[#page_boxes]].y1
        end
    end
    local res = x0 and self.ui.document:getTextFromPositions(
        { x = x0, y = y0, page = page },
        { x = x1, y = y1 },
        true
    )
    if self.ui.paging then
        self.ui.document.configurable.text_wrap = is_reflow
    end
    return res and res.text
end

function TtsBridge:openBridge(path, text, language, request_id, callback_port)
    local uri = BRIDGE_URI .. path
    if text then
        uri = uri .. "?text_base64=" .. base64Url(text)
            .. "&language=" .. language
            .. "&request_id=" .. request_id
            .. "&callback_port=" .. callback_port
    end
    local ok, opened = pcall(Device.openLink, Device, uri)
    return ok and opened
end

function TtsBridge:startCallbackListener()
    local listener, err = socket.bind("127.0.0.1", 0)
    if not listener then
        return nil, err
    end
    listener:settimeout(0)
    local _, port = listener:getsockname()
    self.callback_listener = listener
    self.callback_port = port
    self.callback_task = function()
        self:pollCallback()
    end
    UIManager:scheduleIn(CALLBACK_POLL_SECONDS, self.callback_task)
    return true
end

function TtsBridge:pollCallback()
    local listener = self.callback_listener
    if not listener then
        return
    end

    local client = listener:accept()
    if client then
        client:settimeout(0.2)
        local request = client:receive("*l")
        if request then
            local request_id, event = request:match("^GET /%?request_id=([^& ]+)&event=([^& ]+) HTTP/")
            if request_id and event then
                self:handleCallback(request_id, event)
            end
        end
        client:send("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        client:close()
    end

    if self.callback_listener then
        UIManager:scheduleIn(CALLBACK_POLL_SECONDS, self.callback_task)
    end
end

function TtsBridge:handleCallback(request_id, event)
    if not self.narrating or request_id ~= self.request_id then
        return
    end
    if event == "done" then
        self:advanceNarration()
    elseif event == "error" then
        self:stopNarration(false)
        UIManager:show(InfoMessage:new{
            icon = "notice-warning",
            text = _("Kokoro narration failed."),
        })
    end
end

function TtsBridge:speakCurrentPage()
    local text = self:getCurrentPageText()
    text = text and normalizeText(text)
    if not text or text == "" then
        self:stopNarration(false)
        UIManager:show(InfoMessage:new{
            text = _("Narration finished."),
            timeout = 2,
        })
        return
    end
    if self.last_text and text == self.last_text then
        self:stopNarration(false)
        UIManager:show(InfoMessage:new{
            text = _("Narration finished."),
            timeout = 2,
        })
        return
    end

    self.request_counter = self.request_counter + 1
    self.request_id = tostring(os.time()) .. "-" .. tostring(self.request_counter)
    self.last_text = text
    if not self:openBridge(
        "speak",
        text,
        bookLanguageTag((self.ui.doc_props or {}).language),
        self.request_id,
        self.callback_port
    ) then
        self:stopNarration(false)
        UIManager:show(InfoMessage:new{
            icon = "notice-warning",
            text = _("Could not reach the Android text-to-speech bridge."),
        })
    end
end

function TtsBridge:advanceNarration()
    self.ui:handleEvent(Event:new("GotoViewRel", 1))
    self.advance_task = function()
        self.advance_task = nil
        if self.narrating then
            self:speakCurrentPage()
        end
    end
    UIManager:scheduleIn(PAGE_TURN_DELAY_SECONDS, self.advance_task)
end

function TtsBridge:startNarration()
    if self.narrating then
        return
    end
    local ok = self:startCallbackListener()
    if not ok then
        UIManager:show(InfoMessage:new{
            icon = "notice-warning",
            text = _("Could not start the KOReader narration listener."),
        })
        return
    end
    self.narrating = true
    self.last_text = nil
    self:speakCurrentPage()
    if self.narrating then
        UIManager:show(InfoMessage:new{
            text = _("Narration started. Android shows playback status."),
            timeout = 3,
        })
    end
end

function TtsBridge:closeCallbackListener()
    if self.callback_task then
        UIManager:unschedule(self.callback_task)
        self.callback_task = nil
    end
    if self.advance_task then
        UIManager:unschedule(self.advance_task)
        self.advance_task = nil
    end
    if self.callback_listener then
        self.callback_listener:close()
        self.callback_listener = nil
    end
    self.callback_port = nil
end

function TtsBridge:stopNarration(show_message)
    local was_narrating = self.narrating
    self.narrating = false
    self.request_id = nil
    self.last_text = nil
    self:closeCallbackListener()
    if was_narrating then
        self:openBridge("stop")
    end
    if show_message and was_narrating then
        UIManager:show(InfoMessage:new{
            text = _("Narration stopped."),
            timeout = 2,
        })
    end
end

function TtsBridge:onCloseDocument()
    self:stopNarration(false)
end

function TtsBridge:addToMainMenu(menu_items)
    menu_items.ttsbridge = {
        text = _("Android text-to-speech"),
        sorting_hint = "more_tools",
        sub_item_table = {
            {
                text = _("Start continuous narration"),
                callback = function() self:startNarration() end,
            },
            {
                text = _("Stop narration"),
                callback = function() self:stopNarration(true) end,
            },
        },
    }
end

return TtsBridge
