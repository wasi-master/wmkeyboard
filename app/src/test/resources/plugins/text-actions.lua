-- Selection actions: buttons on the bar over text you select.
--
-- plugin.json lists them under "actions", and asks for the "selection"
-- permission. Tapping one calls transform(text, action) with the selected
-- text and the action's id, and the string it returns takes the selection's
-- place. Returning nil leaves the text as it was. Undo on the bar takes the
-- change back.
--
-- The panel picks the quotes that Wrap in quotes uses. The choice is kept in
-- wm.storage, which an action can read as well as the panel.

local STYLES = {
  { id = "curly", open = "“", close = "”", label = "“Curly”" },
  { id = "straight", open = "\"", close = "\"", label = "\"Straight\"" },
  { id = "guillemets", open = "«", close = "»", label = "«Guillemets»" },
  { id = "corner", open = "「", close = "」", label = "「Corner」" },
}

local function current_style()
  local id = wm.storage.get("quotes") or "curly"
  for _, style in ipairs(STYLES) do
    if style.id == id then return style end
  end
  return STYLES[1]
end

-- Each Latin letter moves 13 places along the alphabet, so the same action
-- undoes itself.
local function rot13(text)
  return (text:gsub("%a", function(c)
    local base = c:match("%l") and 97 or 65
    return string.char((c:byte() - base + 13) % 26 + base)
  end))
end

function transform(text, action)
  if action == "rot13" then
    return rot13(text)
  elseif action == "quote" then
    local style = current_style()
    return style.open .. text .. style.close
  end
  return nil
end

function render()
  local chosen = current_style()
  local rows = {
    ui.label { text = "Quotes for Wrap in quotes", style = "title" },
  }
  for _, style in ipairs(STYLES) do
    rows[#rows + 1] = ui.button {
      id = style.id,
      text = style.label,
      style = style.id == chosen.id and "primary" or nil,
    }
  end
  rows[#rows + 1] = ui.label {
    text = "Select some text, then tap ROT13 or Wrap in quotes on the bar.",
    style = "caption",
  }
  return ui.column(rows)
end

function on_event(e)
  if e.type == "click" then
    wm.storage.set("quotes", e.id)
  end
end
