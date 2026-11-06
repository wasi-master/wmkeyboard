package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Article
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Assignment
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FactCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.InsertDriveFile
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardTab
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.LastPage
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.MenuBook
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.TextSnippet
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Accessibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Air
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlternateEmail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AspectRatio
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AudioFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BarChart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatterySaver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Calculate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Call
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cameraswitch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChatBubbleOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Compress
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Crop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dialpad
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DirectionsCar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Done
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiFlags
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiNature
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiObjects
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiPeople
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiSymbols
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Explore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fastfood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FavoriteBorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FirstPage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FitnessCenter
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashAuto
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatShapes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Forum
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Functions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Highlight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HighlightAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HomeIotDevice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ImageSearch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Interests
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MailOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Menu
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NetworkCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenWith
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PersonOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pets
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phonelink
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureAsPdf
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPictureAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QueryStats
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Quickreply
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Repeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Science
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Screenshot
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Security
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ShoppingCart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipNext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipPrevious
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SportsEsports
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SportsSoccer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarBorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Straighten
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TimerOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TravelExplore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Umbrella
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VideoFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Videocam
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewAgenda
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewHeadline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VpnKey
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WaterDrop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WorkOutline

/**
 * The icons the icon picker offers, keyed by a stable name.
 *
 * These are the Material Symbols the app already bundles — the picker
 * deliberately does not expose the whole `material-icons-extended` set, which
 * is thousands of glyphs the R8 shrinker would then have to keep. Everything
 * here is referenced somewhere in the app already, so offering it costs no
 * extra bytes.
 *
 * The key is the Material symbol name and is what a per-slot override stores
 * (`b:<name>`), so renaming one drops that override back to the default —
 * treat the keys as the wire format they are.
 */
object BuiltinIcons {

    /**
     * The icons, as **builders** rather than vectors.
     *
     * Every `Icons.Outlined.X` parses path data and builds a node tree the
     * first time it is read, and this table has a hundred and ninety of them.
     * Held as vectors, one `b:` icon override on one key — or anything that
     * merely consulted the table — built the lot, in the keyboard's process,
     * on whichever thread got there first. Builders mean [byName] pays for the
     * one icon it is asked for. Same shape the settings app's row icons use,
     * and the reason `KeyIcons.byName` returns early before touching its own
     * catalogue.
     *
     * The picker is the one caller that does want all of them, and it invokes
     * them itself when it opens.
     */
    val catalog: Map<String, () -> ImageVector> = linkedMapOf(
        "Accessibility" to { Icons.Outlined.Accessibility },
        "Add" to { Icons.Outlined.Add },
        "Air" to { Icons.Outlined.Air },
        "AlternateEmail" to { Icons.Outlined.AlternateEmail },
        "Apps" to { Icons.Outlined.Apps },
        "ArrowBack" to { Icons.AutoMirrored.Outlined.ArrowBack },
        "ArrowDownward" to { Icons.Outlined.ArrowDownward },
        "ArrowDropDown" to { Icons.Outlined.ArrowDropDown },
        "ArrowForward" to { Icons.AutoMirrored.Outlined.ArrowForward },
        "ArrowUpward" to { Icons.Outlined.ArrowUpward },
        "Article" to { Icons.AutoMirrored.Outlined.Article },
        "AspectRatio" to { Icons.Outlined.AspectRatio },
        "Assignment" to { Icons.AutoMirrored.Outlined.Assignment },
        "AudioFile" to { Icons.Outlined.AudioFile },
        "AutoAwesome" to { Icons.Outlined.AutoAwesome },
        "AutoStories" to { Icons.Outlined.AutoStories },
        "Backspace" to { Icons.AutoMirrored.Outlined.Backspace },
        "BarChart" to { Icons.Outlined.BarChart },
        "BatterySaver" to { Icons.Outlined.BatterySaver },
        "Bolt" to { Icons.Outlined.Bolt },
        "Calculate" to { Icons.Outlined.Calculate },
        "CalendarMonth" to { Icons.Outlined.CalendarMonth },
        "Call" to { Icons.Outlined.Call },
        "Cameraswitch" to { Icons.Outlined.Cameraswitch },
        "ChatBubbleOutline" to { Icons.Outlined.ChatBubbleOutline },
        "Check" to { Icons.Outlined.Check },
        "CheckCircle" to { Icons.Outlined.CheckCircle },
        "Checklist" to { Icons.Outlined.Checklist },
        "ChevronLeft" to { Icons.Outlined.ChevronLeft },
        "ChevronRight" to { Icons.Outlined.ChevronRight },
        "Close" to { Icons.Outlined.Close },
        "Cloud" to { Icons.Outlined.Cloud },
        "Code" to { Icons.Outlined.Code },
        "Compress" to { Icons.Outlined.Compress },
        "ContentCopy" to { Icons.Outlined.ContentCopy },
        "ContentCut" to { Icons.Outlined.ContentCut },
        "ContentPaste" to { Icons.Outlined.ContentPaste },
        "ConvertToText" to { SymbolIcons.ConvertToText },
        "Crop" to { Icons.Outlined.Crop },
        "CurrencyExchange" to { Icons.Outlined.CurrencyExchange },
        "DarkMode" to { Icons.Outlined.DarkMode },
        "Delete" to { Icons.Outlined.Delete },
        "DeleteSweep" to { Icons.Outlined.DeleteSweep },
        "Description" to { Icons.Outlined.Description },
        "Dialpad" to { Icons.Outlined.Dialpad },
        "Dictionary" to { SymbolIcons.Dictionary },
        "DirectionsCar" to { Icons.Outlined.DirectionsCar },
        "DocumentScanner" to { Icons.Outlined.DocumentScanner },
        "Done" to { Icons.Outlined.Done },
        "DragHandle" to { Icons.Outlined.DragHandle },
        "Draw" to { Icons.Outlined.Draw },
        "Edit" to { Icons.Outlined.Edit },
        "EditNote" to { Icons.Outlined.EditNote },
        "EmojiEmotions" to { Icons.Outlined.EmojiEmotions },
        "EmojiFlags" to { Icons.Outlined.EmojiFlags },
        "EmojiNature" to { Icons.Outlined.EmojiNature },
        "EmojiObjects" to { Icons.Outlined.EmojiObjects },
        "EmojiPeople" to { Icons.Outlined.EmojiPeople },
        "EmojiSymbols" to { Icons.Outlined.EmojiSymbols },
        "Explore" to { Icons.Outlined.Explore },
        "Extension" to { Icons.Outlined.Extension },
        "FactCheck" to { Icons.AutoMirrored.Outlined.FactCheck },
        "Fastfood" to { Icons.Outlined.Fastfood },
        "FavoriteBorder" to { Icons.Outlined.FavoriteBorder },
        "FileDownload" to { Icons.Outlined.FileDownload },
        "FileOpen" to { Icons.Outlined.FileOpen },
        "FileUpload" to { Icons.Outlined.FileUpload },
        "FindReplace" to { Icons.Outlined.FindReplace },
        "FirstPage" to { Icons.Outlined.FirstPage },
        "FitnessCenter" to { Icons.Outlined.FitnessCenter },
        "FlashAuto" to { Icons.Outlined.FlashAuto },
        "FlashlightOn" to { Icons.Outlined.FlashlightOn },
        "FlashOff" to { Icons.Outlined.FlashOff },
        "FlashOn" to { Icons.Outlined.FlashOn },
        "Flight" to { Icons.Outlined.Flight },
        "Folder" to { Icons.Outlined.Folder },
        "FormatShapes" to { Icons.Outlined.FormatShapes },
        "Forum" to { Icons.Outlined.Forum },
        "Fullscreen" to { Icons.Outlined.Fullscreen },
        "Functions" to { Icons.Outlined.Functions },
        "GifBox" to { SymbolIcons.GifBox },
        "GraphicEq" to { Icons.Outlined.GraphicEq },
        "GridOn" to { Icons.Outlined.GridOn },
        "GridView" to { Icons.Outlined.GridView },
        "Group" to { Icons.Outlined.Group },
        "HelpOutline" to { Icons.AutoMirrored.Outlined.HelpOutline },
        "Highlight" to { Icons.Outlined.Highlight },
        "HighlightAlt" to { Icons.Outlined.HighlightAlt },
        "History" to { Icons.Outlined.History },
        "Home" to { Icons.Outlined.Home },
        "HomeIotDevice" to { Icons.Outlined.HomeIotDevice },
        "Image" to { Icons.Outlined.Image },
        "ImageSearch" to { Icons.Outlined.ImageSearch },
        "Info" to { Icons.Outlined.Info },
        "InsertDriveFile" to { Icons.AutoMirrored.Outlined.InsertDriveFile },
        "Interests" to { Icons.Outlined.Interests },
        "Key" to { Icons.Outlined.Key },
        "Keyboard" to { Icons.Outlined.Keyboard },
        "KeyboardArrowDown" to { Icons.Outlined.KeyboardArrowDown },
        "KeyboardArrowLeft" to { Icons.AutoMirrored.Outlined.KeyboardArrowLeft },
        "KeyboardArrowRight" to { Icons.AutoMirrored.Outlined.KeyboardArrowRight },
        "KeyboardArrowUp" to { Icons.Outlined.KeyboardArrowUp },
        "KeyboardDoubleArrowDown" to { Icons.Outlined.KeyboardDoubleArrowDown },
        "KeyboardDoubleArrowLeft" to { Icons.Outlined.KeyboardDoubleArrowLeft },
        "KeyboardDoubleArrowRight" to { Icons.Outlined.KeyboardDoubleArrowRight },
        "KeyboardDoubleArrowUp" to { Icons.Outlined.KeyboardDoubleArrowUp },
        "KeyboardHide" to { Icons.Outlined.KeyboardHide },
        "KeyboardReturn" to { Icons.AutoMirrored.Outlined.KeyboardReturn },
        "KeyboardTab" to { Icons.AutoMirrored.Outlined.KeyboardTab },
        "Language" to { Icons.Outlined.Language },
        "LastPage" to { Icons.AutoMirrored.Outlined.LastPage },
        "LightMode" to { Icons.Outlined.LightMode },
        "Link" to { Icons.Outlined.Link },
        "LocationOn" to { Icons.Outlined.LocationOn },
        "Lock" to { Icons.Outlined.Lock },
        "MailOutline" to { Icons.Outlined.MailOutline },
        "Menu" to { Icons.Outlined.Menu },
        "MenuBook" to { Icons.AutoMirrored.Outlined.MenuBook },
        "Mic" to { Icons.Outlined.Mic },
        "MobileHandLeft" to { SymbolIcons.MobileHandLeft },
        "MoreHoriz" to { Icons.Outlined.MoreHoriz },
        "MoreVert" to { Icons.Outlined.MoreVert },
        "MusicNote" to { Icons.Outlined.MusicNote },
        "NetworkCheck" to { Icons.Outlined.NetworkCheck },
        "Notifications" to { Icons.Outlined.Notifications },
        "Numbers" to { Icons.Outlined.Numbers },
        "OpenInNew" to { Icons.AutoMirrored.Outlined.OpenInNew },
        "OpenWith" to { Icons.Outlined.OpenWith },
        "Palette" to { Icons.Outlined.Palette },
        "PartlyCloudyDay" to { SymbolIcons.PartlyCloudyDay },
        "Password" to { Icons.Outlined.Password },
        "Pause" to { Icons.Outlined.Pause },
        "Payments" to { Icons.Outlined.Payments },
        "PersonOutline" to { Icons.Outlined.PersonOutline },
        "Pets" to { Icons.Outlined.Pets },
        "Phone" to { Icons.Outlined.Phone },
        "Phonelink" to { Icons.Outlined.Phonelink },
        "PhotoCamera" to { Icons.Outlined.PhotoCamera },
        "PhotoLibrary" to { Icons.Outlined.PhotoLibrary },
        "PictureAsPdf" to { Icons.Outlined.PictureAsPdf },
        "PictureInPictureAlt" to { Icons.Outlined.PictureInPictureAlt },
        "PlayArrow" to { Icons.Outlined.PlayArrow },
        "Public" to { Icons.Outlined.Public },
        "PushPin" to { Icons.Outlined.PushPin },
        "QrCode2" to { Icons.Outlined.QrCode2 },
        "QrCodeScanner" to { Icons.Outlined.QrCodeScanner },
        "QueryStats" to { Icons.Outlined.QueryStats },
        "Quickreply" to { Icons.Outlined.Quickreply },
        "Redo" to { Icons.AutoMirrored.Outlined.Redo },
        "Refresh" to { Icons.Outlined.Refresh },
        "Remove" to { Icons.Outlined.Remove },
        "Repeat" to { Icons.Outlined.Repeat },
        "Save" to { Icons.Outlined.Save },
        "Schedule" to { Icons.Outlined.Schedule },
        "School" to { Icons.Outlined.School },
        "Science" to { Icons.Outlined.Science },
        "Screenshot" to { Icons.Outlined.Screenshot },
        "Search" to { Icons.Outlined.Search },
        "Security" to { Icons.Outlined.Security },
        "SelectAll" to { Icons.Outlined.SelectAll },
        "Send" to { Icons.AutoMirrored.Outlined.Send },
        "Settings" to { Icons.Outlined.Settings },
        "Share" to { Icons.Outlined.Share },
        "Shield" to { Icons.Outlined.Shield },
        "ShoppingCart" to { Icons.Outlined.ShoppingCart },
        "SkipNext" to { Icons.Outlined.SkipNext },
        "SkipPrevious" to { Icons.Outlined.SkipPrevious },
        "SmartButton" to { Icons.Outlined.SmartButton },
        "Smartphone" to { Icons.Outlined.Smartphone },
        "SpaceBar" to { Icons.Outlined.SpaceBar },
        "Speed" to { Icons.Outlined.Speed },
        "Spellcheck" to { Icons.Outlined.Spellcheck },
        "SportsEsports" to { Icons.Outlined.SportsEsports },
        "SportsSoccer" to { Icons.Outlined.SportsSoccer },
        "Star" to { Icons.Outlined.Star },
        "StarBorder" to { Icons.Outlined.StarBorder },
        "StarOutline" to { Icons.Outlined.StarOutline },
        "Sticker" to { SymbolIcons.Sticker },
        "StickerAdd" to { SymbolIcons.StickerAdd },
        "StickyNote2" to { Icons.AutoMirrored.Outlined.StickyNote2 },
        "Straighten" to { Icons.Outlined.Straighten },
        "SwapHoriz" to { Icons.Outlined.SwapHoriz },
        "SwapVert" to { Icons.Outlined.SwapVert },
        "Terminal" to { Icons.Outlined.Terminal },
        "TextFields" to { Icons.Outlined.TextFields },
        "TextFormat" to { Icons.Outlined.TextFormat },
        "TextSnippet" to { Icons.AutoMirrored.Outlined.TextSnippet },
        "Thermostat" to { Icons.Outlined.Thermostat },
        "Timer" to { Icons.Outlined.Timer },
        "TimerOff" to { Icons.Outlined.TimerOff },
        "ToolsLevel" to { SymbolIcons.ToolsLevel },
        "TouchApp" to { Icons.Outlined.TouchApp },
        "TrackpadInput" to { SymbolIcons.TrackpadInput },
        "Translate" to { Icons.Outlined.Translate },
        "TravelExplore" to { Icons.Outlined.TravelExplore },
        "Tune" to { Icons.Outlined.Tune },
        "Umbrella" to { Icons.Outlined.Umbrella },
        "Undo" to { Icons.AutoMirrored.Outlined.Undo },
        "VerticalSplit" to { Icons.Outlined.VerticalSplit },
        "Vibration" to { Icons.Outlined.Vibration },
        "Videocam" to { Icons.Outlined.Videocam },
        "VideoFile" to { Icons.Outlined.VideoFile },
        "ViewAgenda" to { Icons.Outlined.ViewAgenda },
        "ViewHeadline" to { Icons.Outlined.ViewHeadline },
        "VisibilityOff" to { Icons.Outlined.VisibilityOff },
        "VolumeOff" to { Icons.AutoMirrored.Outlined.VolumeOff },
        "VolumeUp" to { Icons.AutoMirrored.Outlined.VolumeUp },
        "VpnKey" to { Icons.Outlined.VpnKey },
        "WaterDrop" to { Icons.Outlined.WaterDrop },
        "WbSunny" to { Icons.Outlined.WbSunny },
        "WbTwilight" to { Icons.Outlined.WbTwilight },
        "Widgets" to { Icons.Outlined.Widgets },
        "WorkOutline" to { Icons.Outlined.WorkOutline },
    )

    /** Names in picker order. */
    val names: List<String> = catalog.keys.toList()

    /** The vector for [name], or null when the name is blank or unknown. */
    fun byName(name: String?): ImageVector? =
        name?.takeIf { it.isNotBlank() }?.let { catalog[it]?.invoke() }

    /**
     * "EmojiEmotions" → "Emoji emotions": the picker's search text and tooltip.
     * Derived rather than stored so the catalog stays one line per icon.
     */
    fun label(name: String): String {
        if (name.isEmpty()) return name
        val spaced = buildString {
            name.forEachIndexed { index, c ->
                if (index > 0 && c.isUpperCase() && !name[index - 1].isUpperCase()) append(' ')
                append(c)
            }
        }
        return spaced.first().uppercase() + spaced.drop(1).lowercase()
    }
}
