local Device = require("device")
local InfoMessage = require("ui/widget/infomessage")
local Screen = Device.screen
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")
local _ = require("gettext")
local sha2 = require("ffi/sha2")

local BRIDGE_URI = "koreader-tts://"
local TtsBridge = WidgetContainer:extend{
    name = "ttsbridge",
    is_doc_only = true,
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

function TtsBridge:openBridge(path, text, language)
    local uri = BRIDGE_URI .. path
    if text then
        uri = uri .. "?text_base64=" .. base64Url(text) .. "&language=" .. language
    end
    local ok, opened = pcall(Device.openLink, Device, uri)
    return ok and opened
end

function TtsBridge:speakCurrentPage()
    local text = self:getCurrentPageText()
    if not text or text:match("^%s*$") then
        UIManager:show(InfoMessage:new{
            text = _("Current page has no readable text."),
            timeout = 3,
        })
        return
    end
    if self:openBridge("speak", text, bookLanguageTag((self.ui.doc_props or {}).language)) then
        UIManager:show(InfoMessage:new{
            text = _("Speaking current page."),
            timeout = 2,
        })
    else
        UIManager:show(InfoMessage:new{
            icon = "notice-warning",
            text = _("Could not reach the Piper text-to-speech companion app."),
        })
    end
end

function TtsBridge:addToMainMenu(menu_items)
    menu_items.ttsbridge = {
        text = _("Piper text-to-speech"),
        sorting_hint = "more_tools",
        sub_item_table = {
            {
                text = _("Speak current page"),
                callback = function() self:speakCurrentPage() end,
            },
            {
                text = _("Stop speaking"),
                callback = function() self:openBridge("stop") end,
            },
        },
    }
end

return TtsBridge