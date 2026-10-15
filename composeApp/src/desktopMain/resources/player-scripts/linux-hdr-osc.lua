-- Nuvio experimental Linux HDR session: minimal on-screen display.
--
-- Display only. Every key and mouse binding of the HDR session is installed by the
-- application (keybind -> script-message nuvio-hdr-key) and handled by Nuvio's own
-- player logic, so this script never binds input or changes playback state.
-- Shows title, position/duration, play/pause state and a short key hint while the
-- user interacts, while paused, and briefly after a seek.

local mp = require "mp"
local msg = require "mp.msg"

local hint = "Esc  Back     Space  Play/Pause     \226\134\144 \226\134\146  Seek     F11  Leave HDR"
local overlay = mp.create_osd_overlay("ass-events")
local hide_at = 0
local visible = false

local function ass_text(value)
    value = tostring(value or "")
    value = value:gsub("\\", "\\\226\129\160"):gsub("{", "\\{"):gsub("}", "\\}")
    return (value:gsub("[\r\n]+", " "))
end

local function clock(seconds)
    if type(seconds) ~= "number" or seconds ~= seconds or seconds < 0 then return "--:--" end
    seconds = math.floor(seconds)
    local h, m, s = math.floor(seconds / 3600), math.floor(seconds % 3600 / 60), seconds % 60
    if h > 0 then return string.format("%d:%02d:%02d", h, m, s) end
    return string.format("%d:%02d", m, s)
end

local function render()
    local paused = mp.get_property_native("pause") == true
    if not paused and mp.get_time() >= hide_at then
        if visible then overlay:remove() end
        visible = false
        return
    end
    local w, h = 1920, 1080
    overlay.res_x, overlay.res_y = w, h
    local position = mp.get_property_native("time-pos")
    local duration = mp.get_property_native("duration")
    local fraction = 0
    if type(position) == "number" and type(duration) == "number" and duration > 0 then
        fraction = math.max(0, math.min(1, position / duration))
    end
    local margin, bar_y, bar_h = 96, h - 150, 8
    local bar_w = w - 2 * margin
    local ass = {}
    -- Bottom scrim.
    ass[#ass + 1] = string.format(
        "{\\an7\\pos(0,0)\\bord0\\shad0\\1c&H000000&\\1a&H50&\\p1}m 0 %d l %d %d l %d %d l 0 %d{\\p0}",
        h - 260, w, h - 260, w, h, h)
    -- Title and state.
    ass[#ass + 1] = string.format(
        "{\\an1\\pos(%d,%d)\\fs40\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&}%s",
        margin, bar_y - 60, ass_text(mp.get_property("media-title", "")))
    ass[#ass + 1] = string.format(
        "{\\an3\\pos(%d,%d)\\fs34\\bord2\\shad0\\1c&HFFFFFF&\\3c&H000000&}%s   %s / %s",
        w - margin, bar_y - 60, paused and "Paused" or "Playing", clock(position), clock(duration))
    -- Progress bar.
    ass[#ass + 1] = string.format(
        "{\\an7\\pos(0,0)\\bord0\\shad0\\1c&HFFFFFF&\\1a&HB0&\\p1}m %d %d l %d %d l %d %d l %d %d{\\p0}",
        margin, bar_y, margin + bar_w, bar_y, margin + bar_w, bar_y + bar_h, margin, bar_y + bar_h)
    if fraction > 0 then
        local filled = margin + math.floor(bar_w * fraction)
        ass[#ass + 1] = string.format(
            "{\\an7\\pos(0,0)\\bord0\\shad0\\1c&HFFFFFF&\\1a&H00&\\p1}m %d %d l %d %d l %d %d l %d %d{\\p0}",
            margin, bar_y, filled, bar_y, filled, bar_y + bar_h, margin, bar_y + bar_h)
    end
    -- Key hint and HDR session marker.
    ass[#ass + 1] = string.format(
        "{\\an1\\pos(%d,%d)\\fs28\\bord1.5\\shad0\\1c&HDDDDDD&\\3c&H000000&}%s",
        margin, bar_y + 70, ass_text(hint))
    ass[#ass + 1] = string.format(
        "{\\an3\\pos(%d,%d)\\fs26\\bord1.5\\shad0\\1c&HAAAAAA&\\3c&H000000&}HDR session (experimental)",
        w - margin, bar_y + 70)
    overlay.data = table.concat(ass, "\n")
    overlay:update()
    visible = true
end

local function show(seconds)
    hide_at = math.max(hide_at, mp.get_time() + (seconds or 3))
    render()
end

mp.register_script_message("nuvio-hdr-key", function() show(3) end)
mp.register_script_message("nuvio-hdr-osc-hint", function(text)
    if type(text) == "string" and #text > 0 and #text <= 200 then hint = text end
    if visible then render() end
end)
mp.observe_property("mouse-pos", "native", function() show(3) end)
mp.observe_property("pause", "bool", function() show(3) end)
mp.register_event("seek", function() show(2) end)
mp.register_event("file-loaded", function() show(4) end)
mp.add_periodic_timer(0.5, render)
msg.info("Nuvio HDR OSC ready")
