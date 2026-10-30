package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.InvertColors
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.TrendingUp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bluetooth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteForever
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditCalendar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FirstPage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderZip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Healing
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassEmpty
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lan
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LinkOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationCity
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MyLocation
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NotificationsActive
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Security
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartToy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TimerOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Update
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Warning
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AllInclusive
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Badge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WifiTethering
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatteryStd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Devices
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ImageSearch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phonelink
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeVertical
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sync
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudQueue
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Article
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Chat
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FormatTextdirectionRToL
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardReturn
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.LibraryBooks
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.MenuBook
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.PlaylistAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Segment
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ShortText
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Sort
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.TextSnippet
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Abc
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Accessibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Adjust
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AdsClick
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AltRoute
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlternateEmail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Anchor
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Animation
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Assistant
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AppShortcut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Architecture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AspectRatio
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Autorenew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatteryAlert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatteryChargingFull
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatterySaver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bedtime
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BlurOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BorderStyle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BugReport
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Calculate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cameraswitch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Campaign
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Category
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Interests
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CenterFocusStrong
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Circle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Backup
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudSync
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Celebration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Collections
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Videocam
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ColorLens
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Colorize
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Contacts
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Contrast
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Crop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Crop169
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CropFree
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyBitcoin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dashboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataArray
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataObject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataSaverOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataUsage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Layers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dialpad
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Difference
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DoNotDisturbOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragIndicator
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Email
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewColumn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Explore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FastForward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FilterAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FilterList
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fingerprint
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FlashlightOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FontDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatBold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatColorFill
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatQuote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatSize
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatUnderlined
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ControlCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gamepad
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gavel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gradient
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridView
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Height
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HighlightAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.InstallMobile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Inventory2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardCapslock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardCommandKey
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardTab
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LibraryAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lightbulb
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LineWeight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Loop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Memory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MicNone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mosque
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MotionPhotosOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mouse
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HeadsetMic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NotificationsOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Opacity
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenWith
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Padding
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Password
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Phone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PinDrop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectActual
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectLarge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectSmall
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPicture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PieChart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlaylistAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PowerSettingsNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Preview
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PrivacyTip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QueryStats
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QuestionAnswer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Quickreply
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RecordVoiceOver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Reorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Repeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RestartAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Restore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RoundedCorner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Science
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ScreenRotation
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Screenshot
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlignHorizontalRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sensors
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SentimentSatisfied
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shortcut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shuffle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SignalCellularAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Straighten
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Style
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Subtitles
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Swipe
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeDownAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SystemUpdate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolunteerActivism
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tab
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TabletAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timeline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Today
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Toll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInFull
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInBrowser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TravelExplore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TripOrigin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Upload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalAlignBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalAlignTop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewAgenda
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewCarousel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewDay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewStream
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewWeek
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wallpaper
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Waves
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Weekend
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WorkOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ZoomIn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ZoomOutMap
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AccountTree
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Brush
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CallToAction
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChatBubbleOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Compress
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DevicesFold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Egg
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FiberManualRecord
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatColorText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Grain
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handshake
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Highlight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HorizontalRule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Hub
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Input
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LibraryMusic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NetworkCheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Park
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RadioButtonChecked
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Rotate90DegreesCw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sell
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.South
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StayCurrentLandscape
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwipeRightAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Texture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.UnfoldMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Verified
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Functions
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.CompareArrows
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FormatAlignLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Rule
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.settings.R as SettingsR
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension as ExtensionIcon
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The glyph beside each settings row, keyed by the string resource of the row's
 * own name.
 *
 * One table rather than an argument at every call site, for the same reason
 * [SettingsRouteIcons] is one: the icons only work if they agree with each
 * other, and a few hundred `icon =` arguments spread over four files is how two
 * rows called the same thing end up wearing different glyphs. The row helpers
 * that take a `@StringRes` title look their icon up here, so giving a row an
 * icon is an edit to this file alone.
 *
 * Every settings row with a fixed name has an entry. A row missing from the map
 * draws no icon, which is left for rows named by the user's own things — a
 * language, a layout, an installed pack, a word — where one glyph would repeat
 * down the whole list and say nothing the name does not.
 *
 * Keys are string resources, including the few rows whose drawn title is a
 * format ("Row height: 1.2"): those are keyed by the format's resource.
 */
internal object SettingsRowIcons {
    // Builders rather than built vectors. Materialising all ~450 glyphs used
    // to run at class init — path parsing for every icon in the app, paid in
    // one lump before the first settings row could draw. Each glyph is now
    // built (and cached by the icons library) when a row first draws it.
    private val map: Map<Int, () -> ImageVector> = buildMap {

        // ---- About ----
        put(R.string.about_version_title) { Icons.Outlined.Info }
        put(R.string.update_action_notes) { Icons.Outlined.Celebration }
        put(R.string.about_licence_title) { Icons.Outlined.Gavel }
        put(R.string.about_licences_title) { Icons.Outlined.Gavel }
        put(R.string.about_supporters_title) { Icons.Outlined.VolunteerActivism }
        put(R.string.about_source_title) { Icons.Outlined.Code }
        put(R.string.about_launcher_name_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.about_launcher_shown_title) { Icons.Outlined.VisibilityOff }
        put(R.string.about_app_language_title) { Icons.Outlined.Translate }
        put(R.string.about_all_languages_checking_title) { Icons.Outlined.Translate }
        put(R.string.about_all_languages_download_title) { Icons.Outlined.Download }
        put(R.string.about_all_languages_downloading_title) { Icons.Outlined.Download }
        put(R.string.about_all_languages_install_title) { Icons.Outlined.InstallMobile }
        put(R.string.about_all_languages_installing_title) { Icons.Outlined.InstallMobile }
        put(R.string.about_all_languages_failed_title) { Icons.Outlined.Translate }
        put(R.string.about_all_languages_keep_title) { Icons.Outlined.Close }
        put(R.string.about_user_guide_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.about_privacy_policy_title) { Icons.Outlined.PrivacyTip }
        put(R.string.about_setting_links_title) { Icons.Outlined.Link }
        put(R.string.about_storage_title) { Icons.Outlined.PieChart }
        put(R.string.statistics_title) { Icons.Outlined.QueryStats }
        put(R.string.about_diagnostics_title) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.about_persona_title) { Icons.Outlined.Person }
        put(R.string.about_replay_onboarding_title) { Icons.Outlined.Replay }
        put(R.string.about_dictionaries_title) { Icons.AutoMirrored.Outlined.LibraryBooks }
        put(R.string.about_report_bug_title) { Icons.Outlined.BugReport }
        put(R.string.about_email_developer_title) { Icons.Outlined.Email }
        put(R.string.about_share_play_link) { Icons.Outlined.Share }
        put(R.string.about_share_github_link) { Icons.Outlined.Share }
        put(R.string.about_share_fdroid_link) { Icons.Outlined.Share }

        // ---- Updates (absent in a build with no update source behind it) ----
        // The same glyph on every state of the one row, so it does not appear to
        // jump between rows as the download runs.
        put(R.string.update_row_check_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_available_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_available_title_external) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_downloading_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_install_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_installing_title) { Icons.Outlined.SystemUpdate }
        put(R.string.update_row_auto_check_title) { Icons.Outlined.Autorenew }
        put(R.string.update_row_prompts_title) { Icons.Outlined.Notifications }
        put(R.string.update_row_prereleases_title) { Icons.Outlined.Science }
        put(R.string.update_row_release_page_title) { Icons.AutoMirrored.Outlined.OpenInNew }

        // ---- Notifications ----
        put(R.string.notify_permission_title) { Icons.Outlined.Notifications }
        put(R.string.notify_downloads_title) { Icons.Outlined.CloudDownload }
        put(R.string.notify_updates_title) { Icons.Outlined.SystemUpdate }
        put(R.string.notify_backup_title) { Icons.Outlined.Backup }
        put(R.string.notify_controls_title) { Icons.Outlined.Keyboard }
        put(R.string.notify_system_title) { Icons.Outlined.Tune }

        // ---- Accessibility ----
        put(R.string.accessibility_color_vision_title) { Icons.Outlined.Palette }
        put(R.string.accessibility_high_contrast_title) { Icons.Outlined.Contrast }
        put(R.string.accessibility_key_outlines_title) { Icons.Outlined.BorderStyle }
        put(R.string.accessibility_bold_labels_title) { Icons.Outlined.FormatBold }
        put(R.string.accessibility_readable_font_title) { Icons.Outlined.FontDownload }
        put(R.string.accessibility_text_size_title) { Icons.Outlined.FormatSize }
        put(R.string.accessibility_keyboard_font_title) { Icons.Outlined.TextFields }
        put(R.string.accessibility_reduce_motion_title) { Icons.Outlined.MotionPhotosOff }
        put(R.string.accessibility_classic_popup_title) { Icons.Outlined.Keyboard }
        put(R.string.accessibility_panel_motion_title) { Icons.Outlined.Animation }
        put(R.string.accessibility_selection_motion_title) { Icons.Outlined.SelectAll }
        put(R.string.accessibility_language_switch_motion_title) { Icons.Outlined.Flip }
        put(R.string.accessibility_tool_result_motion_title) { Icons.Outlined.Spellcheck }
        put(R.string.settings_depth_title) { Icons.Outlined.Tune }
        put(R.string.settings_hidden_rows_title) { Icons.Outlined.Visibility }
        put(R.string.accessibility_row_icons_title) { Icons.Outlined.Interests }
        put(R.string.accessibility_screen_transitions_title) { Icons.Outlined.Animation }
        put(R.string.accessibility_keyboard_preview_title) { Icons.Outlined.Keyboard }
        put(R.string.favourites_button_title) { Icons.Outlined.Star }
        put(R.string.toolai_hub_chat_button_everywhere_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_hub_settings_assistant_title) { Icons.Outlined.Assistant }
        put(R.string.semantic_mode_title) { Icons.Outlined.ImageSearch }
        put(R.string.semantic_tag_stickers_title) { Icons.Outlined.Sell }
        put(R.string.semantic_delete_title) { Icons.Outlined.Delete }
        put(R.string.accessibility_talkback_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.accessibility_passthrough_service_title) { Icons.Outlined.Accessibility }
        put(R.string.accessibility_debounce_title) { Icons.Outlined.FilterAlt }
        put(R.string.accessibility_long_press_title) { Icons.Outlined.TouchApp }
        put(R.string.accessibility_key_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.accessibility_haptics_title) { Icons.Outlined.Vibration }

        // ---- Add-ons ----
        put(R.string.addon_auto_refresh_title) { Icons.Outlined.Autorenew }
        put(R.string.addon_refresh_unmetered_title) { Icons.Outlined.Wifi }
        put(R.string.import_link_row_title) { Icons.Outlined.Link }
        put(R.string.import_link_token_title) { Icons.Outlined.Key }

        // ---- Appearance ----
        put(R.string.appearance_themes_title) { Icons.Outlined.Palette }
        put(R.string.theme_shuffle_interval_title) { Icons.Outlined.Schedule }
        put(R.string.theme_shuffle_now_title) { Icons.Outlined.Shuffle }
        put(R.string.theme_material_you_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_auto_title) { Icons.Outlined.AutoMode }
        put(R.string.theme_auto_light_title) { Icons.Outlined.LightMode }
        put(R.string.theme_auto_dark_title) { Icons.Outlined.DarkMode }
        put(R.string.theme_auto_light_from_title) { Icons.Outlined.WbSunny }
        put(R.string.theme_auto_dark_from_title) { Icons.Outlined.Bedtime }
        put(R.string.theme_app_colors_title) { Icons.Outlined.Colorize }
        put(R.string.theme_app_colors_scope_title) { Icons.Outlined.Layers }
        put(R.string.theme_app_colors_source_title) { Icons.Outlined.Apps }
        put(R.string.theme_app_colors_style_title) { Icons.Outlined.Style }
        put(R.string.theme_app_colors_reset_title) { Icons.Outlined.RestartAlt }

        // ---- The theme editor ----
        put(R.string.theme_editor_dark_title) { Icons.Outlined.Contrast }
        put(R.string.theme_follow_wallpaper_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_crop_image_title) { Icons.Outlined.Crop }
        put(R.string.theme_crop_landscape_title) { Icons.Outlined.Crop169 }
        put(R.string.theme_background_animated_title) { Icons.Outlined.Animation }
        put(R.string.theme_popup_placement_title) { Icons.Outlined.Layers }
        put(R.string.theme_custom_radii_title) { Icons.Outlined.RoundedCorner }
        put(R.string.theme_custom_layout_title) { Icons.Outlined.Straighten }
        put(R.string.theme_bold_labels_title) { Icons.Outlined.FormatBold }
        put(R.string.theme_font_title) { Icons.Outlined.TextFormat }
        put(R.string.theme_script_font_title) { Icons.Outlined.Translate }
        put(R.string.theme_script_font_add_title) { Icons.Outlined.Add }
        put(R.string.theme_sound_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.appearance_font_title) { Icons.Outlined.TextFields }
        put(R.string.appearance_icons_title) { Icons.Outlined.Image }
        put(R.string.appearance_key_corner_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.appearance_key_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_key_hint_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_key_hint_offset_title) { Icons.Outlined.SwapVert }
        put(R.string.appearance_key_hint_offset_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.appearance_key_label_offset_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.appearance_key_label_offset_y_title) { Icons.Outlined.SwapVert }
        put(R.string.appearance_toolbar_show_title) { Icons.Outlined.Visibility }
        put(R.string.appearance_toolbar_swipe_down_title) { Icons.Outlined.SwipeDown }
        put(R.string.appearance_toolbar_drag_title) { Icons.Outlined.DragIndicator }
        put(R.string.appearance_toolbar_hardware_only_title) { SymbolIcons.KeyboardExternalInput }
        put(R.string.appearance_toolbar_hardware_auto_hide_title) { Icons.Outlined.KeyboardHide }
        put(R.string.appearance_toolbar_lock_title) { Icons.Outlined.Lock }
        put(R.string.appearance_toolbar_rtl_title) { Icons.AutoMirrored.Outlined.FormatTextdirectionRToL }
        put(R.string.appearance_toolbar_fit_title) { Icons.Outlined.SpaceBar }
        put(R.string.appearance_toolbar_padding_top_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.appearance_toolbar_padding_bottom_title) { Icons.Outlined.VerticalAlignBottom }
        put(R.string.appearance_toolbar_labels_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.appearance_toolbar_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_toolbar_labels_fixed_title) { Icons.Outlined.Straighten }
        put(R.string.appearance_toolbar_pinned_colors_title) { Icons.Outlined.Palette }
        put(R.string.appearance_tool_circle_title) { Icons.Outlined.Circle }
        put(R.string.appearance_tool_shape_title) { Icons.Outlined.Category }
        put(R.string.appearance_toolbox_layout_title) { Icons.Outlined.GridView }
        put(R.string.appearance_toolbox_columns_title) { Icons.Outlined.GridOn }
        put(R.string.appearance_toolbox_pill_columns_title) { Icons.Outlined.ViewAgenda }
        put(R.string.appearance_toolbox_pill_filled_title) { Icons.Outlined.FormatColorFill }
        put(R.string.appearance_toolbox_paginate_title) { Icons.Outlined.Swipe }
        put(R.string.appearance_toolbox_page_size_title) { Icons.Outlined.Numbers }
        put(R.string.appearance_toolbox_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_toolbox_cell_spacing_title) { Icons.Outlined.Straighten }
        put(R.string.appearance_toolbox_search_button_title) { Icons.Outlined.Search }
        put(R.string.appearance_toolbox_search_at_end_title) { Icons.Outlined.AlignHorizontalRight }
        put(R.string.appearance_toolbox_sort_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.appearance_toolbox_search_sort_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.appearance_suggestion_text_size_title) { Icons.Outlined.FormatSize }
        put(R.string.appearance_suggestion_spacing_title) { Icons.Outlined.SpaceBar }
        put(R.string.appearance_suggestion_primary_color_title) { Icons.Outlined.Colorize }
        put(R.string.appearance_tool_width_title) { Icons.Outlined.Straighten }
        put(R.string.appearance_tool_icon_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.appearance_reset_toolbox_order_title) { Icons.Outlined.Restore }
        put(R.string.appearance_reset_title) { Icons.Outlined.Restore }

        // ---- Backup ----
        put(R.string.backup_section_settings_label) { Icons.Outlined.Settings }
        put(R.string.backup_section_dictionary_label) { SymbolIcons.Dictionary }
        put(R.string.backup_section_wordlists_label) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.backup_section_clipboard_label) { Icons.Outlined.ContentPaste }
        put(R.string.backup_section_snippets_label) { Icons.Outlined.Description }
        put(R.string.backup_section_themes_label) { Icons.Outlined.Palette }
        put(R.string.backup_section_icons_label) { Icons.Outlined.Category }
        put(R.string.backup_section_stickers_label) { SymbolIcons.Sticker }
        put(R.string.backup_section_addons_label) { Icons.Outlined.Extension }
        put(R.string.backup_section_emoji_label) { Icons.Outlined.History }
        put(R.string.backup_section_statistics_label) { Icons.Outlined.QueryStats }
        put(R.string.backup_section_vocab_label) { Icons.Outlined.AutoStories }
        put(R.string.backup_blacklist_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.backup_include_secrets_title) { Icons.Outlined.Key }
        put(R.string.backup_sync_title) { Icons.Outlined.Sync }
        put(R.string.backup_sync_enabled_title) { Icons.Outlined.Sync }
        put(R.string.backup_sync_mode_title) { Icons.Outlined.Schedule }
        put(R.string.backup_sync_interval_title) { Icons.Outlined.Schedule }
        put(R.string.backup_sync_targets_title) { Icons.Outlined.CloudQueue }
        put(R.string.backup_auto_targets_title) { Icons.Outlined.CloudQueue }
        put(R.string.backup_sync_secrets_title) { Icons.Outlined.Key }
        put(R.string.backup_sync_keep_toolbar_title) { Icons.Outlined.Handyman }
        put(R.string.backup_sync_keep_layouts_title) { Icons.Outlined.Language }
        put(R.string.backup_files_contents_title) { Icons.Outlined.Checklist }
        put(R.string.backup_auto_folder_title) { Icons.Outlined.Folder }
        put(R.string.backup_auto_webdav_url_label) { Icons.Outlined.Link }
        put(R.string.backup_auto_drive_title) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_s3_bucket_label) { Icons.Outlined.Inventory2 }
        put(R.string.backup_auto_s3_path_style_title) { Icons.Outlined.Link }
        put(R.string.backup_auto_ftp_host_label) { Icons.Outlined.Link }
        put(R.string.backup_auto_ftp_secure_title) { Icons.Outlined.Lock }
        put(R.string.backup_sftp_host_key_title) { Icons.Outlined.Key }
        put(R.string.backup_sftp_legacy_title) { Icons.Outlined.History }
        put(R.string.backup_smb_encrypt_title) { Icons.Outlined.Lock }
        put(R.string.backup_git_skip_ci_title) { Icons.Outlined.Block }
        put(R.string.backup_git_allow_public_title) { Icons.Outlined.Public }
        put(R.string.backup_auto_dest_dropbox) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_dest_onedrive) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_enabled_title) { Icons.Outlined.CloudUpload }
        put(R.string.backup_auto_interval_title) { Icons.Outlined.Schedule }
        put(R.string.backup_auto_keep_title) { Icons.Outlined.Numbers }
        put(R.string.backup_auto_staggered_title) { Icons.Outlined.History }
        put(R.string.backup_frequent_enabled_title) { Icons.Outlined.Schedule }
        put(R.string.backup_frequent_interval_title) { Icons.Outlined.Schedule }
        put(R.string.backup_frequent_keep_title) { Icons.Outlined.Numbers }
        put(R.string.home_shortcut_add_title) { Icons.Outlined.Shortcut }
        put(R.string.backup_auto_encrypt_title) { Icons.Outlined.Lock }
        put(R.string.backup_auto_charging_title) { Icons.Outlined.BatteryChargingFull }
        put(R.string.backup_auto_unmetered_title) { Icons.Outlined.Wifi }

        // ---- Key press ----
        put(R.string.keypress_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_haptic_strength_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_haptic_intensity_title) { Icons.Outlined.GraphicEq }
        put(R.string.keypress_long_press_haptics_title) { Icons.Outlined.TouchApp }
        put(R.string.keypress_long_press_release_title) { Icons.Outlined.TouchApp }
        put(R.string.keypress_cursor_haptics_title) { Icons.Outlined.SpaceBar }
        put(R.string.keypress_vibrate_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.keypress_vibrate_delete_swipe_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.keypress_vibrate_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_dnd_mute_title) { Icons.Outlined.DoNotDisturbOn }
        put(R.string.keypress_haptics_hardware_title) { Icons.Outlined.Vibration }
        put(R.string.hardware_sound_key_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.hardware_sound_volume_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.hardware_sound_pack_release_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.hardware_sound_output_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.keypress_sound_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_system_touch_title) { Icons.Outlined.PhoneAndroid }
        put(R.string.keypress_popup_title) { Icons.Outlined.Notifications }
        put(R.string.keypress_popup_numeric_title) { Icons.Outlined.Dialpad }
        put(R.string.keypress_popup_window_band_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.keypress_popup_on_key_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_popup_min_duration_title) { Icons.Outlined.Timer }
        put(R.string.keypress_popup_font_size_title) { Icons.Outlined.FormatSize }
        put(R.string.keypress_popup_height_title) { Icons.Outlined.Height }
        put(R.string.keypress_popup_offset_y_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.keypress_popup_offset_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.keypress_popup_background_title) { Icons.Outlined.Palette }
        put(R.string.keypress_popup_text_color_title) { Icons.Outlined.Colorize }
        put(R.string.keypress_alternates_size_title) { Icons.Outlined.FormatSize }
        put(R.string.keypress_alternates_padding_title) { Icons.Outlined.Padding }
        put(R.string.keypress_alternates_columns_title) { Icons.Outlined.ViewWeek }
        put(R.string.keypress_alternates_nearest_title) { Icons.Outlined.SwapVert }
        put(R.string.keypress_alternates_centred_title) { Icons.Outlined.SwapHoriz }
        put(R.string.keypress_alternates_order_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.keypress_alternates_hold_title) { Icons.Outlined.Gesture }
        put(R.string.keypress_popup_shape_title) { Icons.Outlined.Category }
        put(R.string.keypress_popup_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.keypress_long_press_delay_title) { Icons.Outlined.Timer }
        put(R.string.keypress_repeat_start_title) { Icons.Outlined.Timer }
        put(R.string.keypress_delete_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_hold_words_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.keypress_word_delete_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_space_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_custom_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_flicks_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.keypress_caps_lock_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_long_press_hints_title) { Icons.Outlined.Lightbulb }
        put(R.string.keypress_all_accents_title) { Icons.Outlined.Translate }
        put(R.string.keypress_native_letters_title) { Icons.Outlined.Abc }
        put(R.string.keypress_top_row_holds_title) { Icons.Outlined.Numbers }
        put(R.string.keypress_hold_symbols_title) { Icons.Outlined.Tag }
        put(R.string.keypress_shifted_popup_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_symbols_numpad_title) { Icons.Outlined.Dialpad }
        put(R.string.keypress_enter_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.keypress_ctrl_raw_title) { Icons.Outlined.Terminal }
        put(R.string.keypress_hold_actions_title) { Icons.Outlined.SelectAll }
        put(R.string.keypress_hold_action_position_title) { Icons.Outlined.Bolt }
        put(R.string.keypress_globe_drag_title) { Icons.Outlined.Gesture }

        // ---- Emoji ----
        put(R.string.langemoji_emoji_toolbar_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.langemoji_strip_shortcut_title) { Icons.Outlined.SwapHoriz }
        put(R.string.langemoji_strip_toolbox_title) { Icons.Outlined.Apps }
        put(R.string.langemoji_strip_at_end_title) { Icons.Outlined.SwapHoriz }
        put(R.string.langemoji_strip_own_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.langemoji_strip_tool_width_title) { Icons.Outlined.Straighten }
        put(R.string.langemoji_strip_icon_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.langemoji_emoji_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.langemoji_emoji_prediction_title) { Icons.Outlined.AutoAwesome }
        put(R.string.langemoji_emoji_insert_mode_title) { Icons.Outlined.TouchApp }
        put(R.string.langemoji_emoji_grid_size_title) { Icons.Outlined.GridOn }
        put(R.string.langemoji_emoji_tab_width_title) { Icons.Outlined.Straighten }
        put(R.string.langemoji_emoji_size_title) { Icons.Outlined.FormatSize }
        put(R.string.langemoji_emoji_grid_columns_title) { Icons.Outlined.ViewColumn }
        put(R.string.langemoji_emoji_grid_row_height_title) { Icons.Outlined.Height }
        put(R.string.langemoji_emoji_tab_mode_title) { Icons.Outlined.History }
        put(R.string.langemoji_emoji_recents_title) { Icons.Outlined.Numbers }
        put(R.string.langemoji_emoji_clear_recents_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_clear_history_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_kaomoji_title) { Icons.Outlined.SentimentSatisfied }
        put(R.string.langemoji_kaomoji_title) { Icons.Outlined.SentimentSatisfied }
        put(R.string.langemoji_kaomoji_split_title) { Icons.Outlined.ViewColumn }
        put(R.string.langemoji_kaomoji_tag_tabs_title) { Icons.Outlined.Tab }
        put(R.string.langemoji_kaomoji_start_title) { Icons.Outlined.History }
        put(R.string.langemoji_kaomoji_rail_title) { Icons.Outlined.ViewWeek }
        put(R.string.langemoji_kaomoji_suggest_title) { Icons.Outlined.Lightbulb }
        put(R.string.langemoji_kaomoji_in_search_title) { Icons.Outlined.Search }
        put(R.string.langemoji_kaomoji_faces_title) { Icons.Outlined.SentimentSatisfied }
        put(R.string.langemoji_kaomoji_tags_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.langemoji_kaomoji_import_title) { Icons.Outlined.Download }
        put(R.string.langemoji_kaomoji_export_title) { Icons.Outlined.Upload }
        put(R.string.langemoji_kaomoji_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.langemoji_emoji_long_press_name_title) { Icons.Outlined.Abc }
        put(R.string.langemoji_emoji_animated_title) { Icons.Outlined.Animation }
        put(R.string.langemoji_emoji_sticker_title) { Icons.Outlined.PhotoSizeSelectActual }
        put(R.string.langemoji_emoji_row_title) { Icons.Outlined.ViewWeek }
        put(R.string.langemoji_emoji_bar_content_title) { Icons.Outlined.ViewWeek }
        put(R.string.langemoji_emoji_bar_count_title) { Icons.Outlined.Numbers }
        put(R.string.langemoji_emoji_bar_scroll_title) { Icons.Outlined.SwapHoriz }
        put(R.string.langemoji_emoji_font_title) { Icons.Outlined.FontDownload }
        put(R.string.langemoji_emoji_skin_tone_title) { Icons.Outlined.Colorize }
        put(R.string.langemoji_emoji_tone_override_title) { Icons.Outlined.Colorize }
        put(R.string.langemoji_emoji_close_after_insert_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.langemoji_media_switcher_title) { Icons.Outlined.SwapHoriz }
        put(R.string.langemoji_media_remember_title) { Icons.Outlined.History }
        put(R.string.langemoji_emoji_continuous_title) { Icons.Outlined.SwapVert }
        put(R.string.langemoji_panel_height_title) { Icons.Outlined.Height }
        put(R.string.langemoji_emoji_hide_unrenderable_title) { Icons.Outlined.VisibilityOff }
        put(R.string.langemoji_emoji_unicode_search_title) { Icons.Outlined.Translate }
        put(R.string.langemoji_emoji_categories_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.langemoji_emoji_added_title) { Icons.Outlined.Add }
        put(R.string.langemoji_emoji_keywords_title) { Icons.Outlined.EmojiEmotions }

        // ---- Languages ----
        put(R.string.langemoji_lang_add_title) { Icons.Outlined.Add }
        put(R.string.langemoji_lang_keymaps_title) { Icons.Outlined.GridOn }
        put(R.string.langemoji_lang_per_app_toggle_title) { Icons.Outlined.Apps }
        put(R.string.langemoji_lang_os_switcher_title) { Icons.Outlined.Language }
        put(R.string.langemoji_lang_subtype_enabler_title) { Icons.Outlined.Settings }
        put(R.string.langemoji_lang_app_name_first_title) { Icons.Outlined.Apps }
        put(R.string.langemoji_lang_auto_download_title) { Icons.Outlined.CloudDownload }
        put(R.string.langemoji_lang_metered_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.langemoji_lang_autopair_title) { Icons.Outlined.Link }
        put(R.string.langemoji_lang_carry_word_title) { Icons.Outlined.Translate }
        put(R.string.langemoji_lang_switcher_extra_title) { Icons.Outlined.Settings }
        put(R.string.langemoji_lang_forget_apps_title) { Icons.Outlined.DeleteSweep }
        put(R.string.languages_conjunct_backspace_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.languages_numeral_system_title) { Icons.Outlined.Numbers }
        put(R.string.languages_custom_dictionaries_title) { SymbolIcons.Dictionary }
        put(R.string.languages_emoji_keywords_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.languages_cjk_traditional_title) { Icons.Outlined.Translate }
        put(R.string.languages_rime_engine_title) { Icons.Outlined.Settings }
        put(R.string.languages_rime_status_title) { Icons.Outlined.Info }
        put(R.string.languages_rime_import_title) { Icons.Outlined.FileOpen }
        put(R.string.languages_rime_delete_all_title) { Icons.Outlined.DeleteSweep }
        put(R.string.languages_mozc_engine_title) { Icons.Outlined.Settings }
        put(R.string.languages_mozc_status_title) { Icons.Outlined.Info }
        put(R.string.languages_mozc_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.languages_cjk_jianpin_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.languages_cjk_prediction_title) { Icons.Outlined.AutoAwesome }
        put(R.string.languages_cjk_shortcuts_title) { Icons.Outlined.Today }
        put(R.string.languages_cjk_fuzzy_title) { Icons.Outlined.BlurOn }
        put(R.string.languages_cjk_lazy_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.languages_cjk_loose_marks_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_cjk_full_width_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.languages_cjk_space_steps_title) { Icons.Outlined.SwapHoriz }
        put(R.string.languages_cjk_fuzzy_pairs_reset_title) { Icons.Outlined.Restore }
        put(R.string.languages_fancy_style_row_title) { Icons.Outlined.TextFormat }
        put(R.string.languages_fancy_fonts_add_title) { Icons.Outlined.TextFormat }
        put(R.string.languages_spelling_map_row_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_space_after_suggestion_title) { Icons.Outlined.SpaceBar }
        put(R.string.languages_phonetic_siblings_row_title) { Icons.Outlined.FindReplace }
        put(R.string.languages_phonetic_context_row_title) { Icons.Outlined.Link }
        put(R.string.languages_more_layouts_title) { Icons.Outlined.GridOn }

        // ---- Layout & size ----
        put(R.string.layout_number_row_title) { Icons.Outlined.Numbers }
        put(R.string.layout_number_row_height_title) { Icons.Outlined.Height }
        put(R.string.layout_number_row_in_symbols_title) { Icons.Outlined.Numbers }
        put(R.string.layout_number_row_on_keypad_title) { Icons.Outlined.Dialpad }
        put(R.string.layout_number_row_shift_symbols_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.layout_arrow_row_title) { Icons.Outlined.OpenWith }
        put(R.string.layout_arrow_row_order_title) { Icons.Outlined.Reorder }
        put(R.string.layout_symbols_return_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.layout_symbols_return_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_numeral_scope_title) { Icons.Outlined.Numbers }
        put(R.string.layout_resize_drag_title) { Icons.Outlined.AspectRatio }
        put(R.string.layout_key_height_title) { Icons.Outlined.Height }
        put(R.string.layout_bottom_row_height_title) { Icons.Outlined.Height }
        put(R.string.layout_side_padding_left_title) { Icons.Outlined.Padding }
        put(R.string.layout_side_padding_right_title) { Icons.Outlined.Padding }
        put(R.string.layout_key_spacing_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_extend_edge_keys_title) { Icons.Outlined.TouchApp }
        put(R.string.layout_keyboard_scale_title) { Icons.Outlined.ZoomOutMap }
        put(R.string.layout_bottom_padding_title) { Icons.Outlined.Padding }
        put(R.string.layout_hide_system_keyboard_bar_title) { Icons.Outlined.VisibilityOff }
        put(R.string.layout_board_corner_top_title) { Icons.Outlined.RoundedCorner }
        put(R.string.layout_board_corner_bottom_title) { Icons.Outlined.RoundedCorner }
        put(R.string.layout_board_corners_title) { Icons.Outlined.CropFree }
        put(R.string.layout_keyboard_width_title) { Icons.Outlined.Straighten }
        put(R.string.layout_keyboard_position_title) { Icons.Outlined.OpenWith }
        put(R.string.layout_font_size_title) { Icons.Outlined.FormatSize }
        put(R.string.layout_follow_portrait_title) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_variant_allow_one_handed_title) { SymbolIcons.MobileHandLeft }
        put(R.string.layout_variant_allow_split_title) { Icons.Outlined.VerticalSplit }
        put(R.string.layout_variant_follows_portrait_label) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_one_handed_title) { SymbolIcons.MobileHandLeft }
        put(R.string.layout_one_handed_portrait_only_title) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_split_title) { Icons.Outlined.VerticalSplit }
        put(R.string.layout_split_gap_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_split_spacebar_title) { Icons.Outlined.SpaceBar }
        put(R.string.layout_split_large_only_title) { Icons.Outlined.TabletAndroid }
        put(R.string.layout_floating_title) { Icons.Outlined.PictureInPicture }
        put(R.string.layout_floating_landscape_title) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_landscape_fullscreen_title) { Icons.Outlined.ScreenRotation }
        put(R.string.layout_floating_handle_title) { Icons.Outlined.DragHandle }
        put(R.string.layout_floating_width_title) { Icons.Outlined.Straighten }
        put(R.string.layout_floating_height_title) { Icons.Outlined.Height }
        put(R.string.layout_floating_reset_title) { Icons.Outlined.Restore }
        put(R.string.layout_persistent_title) { Icons.Outlined.PushPin }
        put(R.string.layout_summon_button_permission_title) { Icons.Outlined.Layers }
        // Advanced › Floating button (#755).
        put(R.string.floating_button_title) { Icons.Outlined.Adjust }

        // ---- Extensions ----
        put(R.string.extensions_title) { Icons.Outlined.ExtensionIcon }
        put(R.string.extension_enabled_title) { Icons.Outlined.ToggleOn }
        for (extension in Extension.entries) put(extensionTitle(extension)) { extensionIcon(extension) }
        put(R.string.floating_button_enable_title) { Icons.Outlined.Adjust }
        put(R.string.share_sheet_title) { Icons.Outlined.Share }
        put(R.string.share_sheet_enabled_title) { Icons.Outlined.Share }
        put(R.string.share_sheet_recents_title) { Icons.Outlined.History }
        put(R.string.share_sheet_pins_title) { Icons.Outlined.PushPin }
        put(R.string.share_sheet_recents_clear_title) { Icons.Outlined.History }
        put(R.string.floating_button_screen_title) { Icons.Outlined.Preview }
        put(R.string.floating_button_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.floating_button_double_tap_title) { Icons.Outlined.AdsClick }
        put(R.string.floating_button_hold_title) { Icons.Outlined.Gesture }
        put(R.string.floating_button_tap_typing_title) { Icons.Outlined.TouchApp }
        put(R.string.floating_button_double_tap_typing_title) { Icons.Outlined.AdsClick }
        put(R.string.floating_button_menu_unreached_title) { Icons.Outlined.Apps }
        put(R.string.floating_button_menu_add_title) { Icons.Outlined.Add }
        put(R.string.floating_button_menu_labels_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.floating_button_menu_reset_title) { Icons.Outlined.Restore }
        put(R.string.floating_button_menu_gap_title) { Icons.Outlined.SpaceBar }
        put(R.string.floating_button_menu_animation_title) { Icons.Outlined.Animation }
        put(R.string.floating_button_style_title) { Icons.Outlined.Palette }
        put(R.string.floating_button_shape_title) { Icons.Outlined.Interests }
        put(R.string.floating_button_size_title) { Icons.Outlined.Straighten }
        put(R.string.floating_button_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.floating_button_idle_title) { Icons.Outlined.Timer }
        put(R.string.floating_button_idle_opacity_title) { Icons.Outlined.Contrast }
        put(R.string.floating_button_tuck_title) { Icons.Outlined.VisibilityOff }
        put(R.string.floating_button_while_typing_title) { Icons.Outlined.Keyboard }
        put(R.string.floating_button_over_keyboard_title) { Icons.Outlined.Layers }
        put(R.string.floating_button_landscape_title) { Icons.Outlined.ScreenRotation }
        put(R.string.floating_button_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.floating_button_reset_position_title) { Icons.Outlined.Restore }
        put(R.string.layout_reset_sizing_title) { Icons.Outlined.Restore }
        put(R.string.layout_comma_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.layout_symbols_numpad_key_title) { Icons.Outlined.Dialpad }
        put(R.string.layout_show_globe_title) { Icons.Outlined.Language }
        put(R.string.layout_globe_recent_title) { Icons.Outlined.History }
        put(R.string.layout_globe_guard_title) { Icons.Outlined.Timer }
        put(R.string.layout_globe_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.layout_swap_comma_globe_title) { Icons.Outlined.SwapHoriz }
        put(R.string.layout_globe_in_one_place_title) { Icons.Outlined.PinDrop }
        put(R.string.layout_editor_action_row_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.layout_editor_hint_title) { Icons.Outlined.Subtitles }
        put(R.string.layout_editor_alternate_columns_title) { Icons.Outlined.ViewWeek }
        put(R.string.layout_editor_role_title) { Icons.Outlined.Tune }
        put(R.string.layout_editor_show_shift_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.layout_editor_tablet_expand_title) { Icons.Outlined.TabletAndroid }
        put(R.string.layout_editor_persist_title) { Icons.Outlined.PushPin }
        put(R.string.layout_editor_flick_hints_title) { Icons.Outlined.OpenWith }
        put(R.string.layout_editor_theme_title) { Icons.Outlined.Palette }
        put(R.string.layout_editor_json_title) { Icons.Outlined.DataObject }
        put(R.string.layout_editor_composer_title) { Icons.Outlined.Keyboard }
        put(R.string.layout_editor_actual_size_title) { Icons.Outlined.Height }
        put(R.string.layout_editor_tool_row_title) { Icons.Outlined.Widgets }
        put(R.string.layout_editor_layout_row_title) { Icons.Outlined.GridOn }
        put(R.string.layout_editor_edit_row_title) { Icons.Outlined.EditNote }
        put(R.string.layout_editor_field_row_title) { Icons.Outlined.Dashboard }

        // ---- Keyboard modes / rows ----
        put(R.string.modes_enabled_title) { Icons.Outlined.Tune }
        put(R.string.modes_drag_edits_title) { Icons.Outlined.DragIndicator }
        put(R.string.modes_emoji_row_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.modes_symbol_row_title) { Icons.Outlined.Tag }
        put(R.string.modes_pinned_tools_title) { Icons.Outlined.PushPin }
        put(R.string.modes_pinned_behaviour_title) { Icons.Outlined.PushPin }
        put(R.string.modes_toolbox_order_title) { Icons.Outlined.Reorder }
        put(R.string.modes_symbol_sets_title) { Icons.Outlined.Tag }
        put(R.string.modes_manual_duration_title) { Icons.Outlined.Timer }
        put(R.string.modes_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.modes_autocapitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.modes_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.modes_autospace_title) { Icons.Outlined.SpaceBar }
        put(R.string.modes_glide_title) { Icons.Outlined.Gesture }
        put(R.string.modes_two_thumb_title) { Icons.Outlined.TouchApp }
        put(R.string.modes_commit_as_typed_title) { Icons.Outlined.Terminal }
        put(R.string.modes_layout_title) { Icons.Outlined.Keyboard }
        put(R.string.rows_symbol_row_title) { Icons.Outlined.Tag }
        put(R.string.rows_fancy_title) { Icons.Outlined.TextFormat }
        put(R.string.rows_dictionary_bar_title) { SymbolIcons.Dictionary }
        put(R.string.rows_recent_words_placement_title) { Icons.Outlined.ViewAgenda }
        put(R.string.rows_recent_words_steady_title) { Icons.Outlined.Height }
        put(R.string.rows_symbol_row_height_title) { Icons.Outlined.Height }
        put(R.string.rows_symbol_row_lines_title) { Icons.Outlined.ViewAgenda }
        put(R.string.rows_symbol_row_scroll_title) { Icons.Outlined.SwapHoriz }
        put(R.string.rows_reset_order_title) { Icons.Outlined.Restore }

        // ---- Photos ----
        put(R.string.photo_services_title) { Icons.Outlined.Wallpaper }
        put(R.string.photo_rotation_title) { Icons.Outlined.Autorenew }
        put(R.string.photo_library_title) { Icons.Outlined.Collections }
        put(R.string.photo_rotation_on_title) { Icons.Outlined.Autorenew }
        put(R.string.photo_rotation_interval_title) { Icons.Outlined.Schedule }
        put(R.string.photo_rotation_topics_title) { Icons.Outlined.Category }
        put(R.string.photo_rotation_safe_title) { Icons.Outlined.Shield }
        put(R.string.photo_rotation_wide_title) { Icons.Outlined.AspectRatio }
        put(R.string.photo_rotation_metered_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.photo_rotation_pool_title) { Icons.Outlined.Inventory2 }
        put(R.string.photo_rotation_scope_title) { Icons.Outlined.Palette }
        put(R.string.photo_rotation_scope_pick_title) { Icons.Outlined.Checklist }
        put(R.string.photo_rotation_seed_palette_title) { Icons.Outlined.Colorize }
        put(R.string.photo_rotation_readability_title) { Icons.Outlined.Contrast }
        put(R.string.photo_rotation_key_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.photo_rotation_budget_title) { Icons.Outlined.Storage }

        // ---- Plugins ----
        put(R.string.plugins_auto_disable_title) { Icons.Outlined.Block }
        put(R.string.plugins_detail_enabled_title) { Icons.Outlined.Extension }

        // ---- Privacy ----
        put(R.string.privacy_learn_typing_title) { Icons.Outlined.School }
        put(R.string.privacy_system_dictionary_title) { SymbolIcons.Dictionary }
        put(R.string.privacy_use_system_dictionary_title) { Icons.Outlined.PhoneAndroid }
        put(R.string.privacy_dict_shortcuts_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.privacy_incognito_title) { Icons.Outlined.VisibilityOff }
        put(R.string.privacy_auto_incognito_title) { Icons.Outlined.Public }
        put(R.string.privacy_backup_title) { Icons.Outlined.CloudUpload }
        put(R.string.privacy_verify_checksums_title) { Icons.Outlined.Verified }
        put(R.string.privacy_delete_learned_words_title) { Icons.Outlined.DeleteSweep }
        put(R.string.privacy_weather_location_title) { Icons.Outlined.LocationOn }

        // ---- Privacy: other apps (automation) ----
        put(R.string.automation_enabled_title) { Icons.Outlined.Apps }
        put(R.string.automation_actions_title) { Icons.Outlined.Checklist }
        put(R.string.automation_layout_title) { Icons.Outlined.Language }
        put(R.string.automation_mode_title) { Icons.Outlined.Tune }
        put(R.string.automation_theme_title) { Icons.Outlined.Palette }
        put(R.string.automation_show_pin_title) { Icons.Outlined.PushPin }
        put(R.string.automation_feedback_title) { Icons.Outlined.Vibration }
        put(R.string.automation_savers_title) { Icons.Outlined.BatterySaver }
        put(R.string.automation_position_title) { Icons.Outlined.OpenWith }
        put(R.string.automation_open_tool_title) { Icons.Outlined.Widgets }
        put(R.string.automation_incognito_on_title) { Icons.Outlined.VisibilityOff }
        put(R.string.automation_incognito_off_title) { Icons.Outlined.Visibility }
        put(R.string.automation_words_title) { Icons.Outlined.Spellcheck }
        put(R.string.automation_backup_title) { Icons.Outlined.Backup }
        put(R.string.automation_layout_events_title) { Icons.Outlined.Campaign }
        put(R.string.automation_type_text_title) { Icons.Outlined.Keyboard }

        // ---- Privacy: the fingerprint lock ----
        // The per-target rows on the configurator are absent on purpose: each
        // borrows the glyph of the screen or row it guards, so the list reads
        // as the places it names rather than as a column of padlocks.
        put(R.string.privacy_lock_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_lock_enabled_title) { Icons.Outlined.Lock }
        put(R.string.privacy_lock_relock_title) { Icons.Outlined.Timer }
        put(R.string.privacy_lock_credential_title) { Icons.Outlined.Password }
        put(R.string.privacy_lock_enroll_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_lock_storage_delete_title) { Icons.Outlined.DeleteSweep }
        put(R.string.privacy_lock_factory_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.privacy_lock_export_title) { Icons.Outlined.Save }
        put(R.string.privacy_lock_method_title) { Icons.Outlined.Pin }
        put(R.string.privacy_lock_shortcut_title) { Icons.Outlined.Fingerprint }

        // ---- Privacy: permissions ----
        put(R.string.privacy_permissions_title) { Icons.Outlined.Key }
        put(R.string.privacy_permissions_biometric_title) { Icons.Outlined.Fingerprint }
        put(R.string.privacy_permissions_mic_title) { Icons.Outlined.MicNone }
        put(R.string.privacy_permissions_camera_title) { Icons.Outlined.PhotoCamera }
        put(R.string.privacy_permissions_contacts_title) { Icons.Outlined.Contacts }
        put(R.string.privacy_permissions_calendar_title) { Icons.Outlined.CalendarMonth }
        put(R.string.privacy_permissions_images_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.privacy_permissions_storage_title) { Icons.Outlined.Folder }
        put(R.string.privacy_permissions_notifications_title) { Icons.Outlined.Notifications }
        put(R.string.privacy_permissions_usage_title) { Icons.Outlined.DataUsage }
        put(R.string.privacy_permissions_accessibility_title) { Icons.Outlined.Accessibility }
        put(R.string.privacy_permissions_screen_content_title) { Icons.Outlined.Preview }
        put(R.string.privacy_permissions_install_updates_title) { Icons.Outlined.InstallMobile }
        put(R.string.privacy_permissions_overlay_title) { Icons.Outlined.Layers }
        put(R.string.privacy_permissions_internet_title) { Icons.Outlined.Public }
        put(R.string.privacy_permissions_network_state_title) { Icons.Outlined.SignalCellularAlt }
        put(R.string.privacy_permissions_vibrate_title) { Icons.Outlined.Vibration }

        // ---- Diagnostics ----
        put(R.string.shell_debug_log_system_title) { Icons.Outlined.Terminal }
        put(R.string.shell_debug_log_copy_title) { Icons.Outlined.ContentCopy }
        put(R.string.shell_debug_log_share_title) { Icons.Outlined.Share }
        put(R.string.shell_debug_log_crash_test_title) { Icons.Outlined.BugReport }

        // ---- Text expander ----
        put(R.string.expander_multi_expand_title) { Icons.Outlined.AltRoute }
        put(R.string.expander_grid_columns_title) { Icons.Outlined.GridView }
        put(R.string.expander_titles_only_title) { Icons.Outlined.ViewAgenda }
        put(R.string.expander_panel_stays_title) { Icons.Outlined.PushPin }
        put(R.string.expander_secure_fields_title) { Icons.Outlined.Key }
        put(R.string.expander_escape_title) { Icons.Outlined.Block }
        put(R.string.rows_snippet_multi_expand_label) { Icons.Outlined.AltRoute }

        // ---- Typing ----
        put(R.string.typing_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_autocorrect_confidence_title) { Icons.Outlined.Tune }
        put(R.string.typing_autocorrect_adaptive_title) { Icons.Outlined.Tune }
        put(R.string.typing_timing_signal_title) { Icons.Outlined.Timer }
        put(R.string.typing_number_row_corrections_title) { Icons.Outlined.Pin }
        put(R.string.typing_autocorrect_splits_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_language_detection_title) { Icons.Outlined.Translate }
        put(R.string.typing_language_detection_strength_title) { Icons.Outlined.Tune }
        put(R.string.typing_language_detection_by_app_title) { Icons.Outlined.Apps }
        put(R.string.languages_phonetic_english_title) { Icons.Outlined.Translate }
        put(R.string.languages_khipro_english_title) { Icons.Outlined.Translate }
        put(R.string.languages_phonetic_english_switch_title) { Icons.Outlined.TouchApp }
        put(R.string.languages_ansi_allowed_title) { Icons.Outlined.FontDownload }
        put(R.string.languages_ansi_version_title) { Icons.Outlined.Numbers }
        put(R.string.languages_zawgyi_allowed_title) { Icons.Outlined.SwapHoriz }
        put(R.string.languages_zawgyi_key_font_title) { Icons.Outlined.FontDownload }
        put(R.string.typing_register_priors_title) { Icons.Outlined.QuestionAnswer }
        put(R.string.typing_context_rerank_title) { Icons.Outlined.Psychology }
        put(R.string.typing_neural_context_title) { Icons.Outlined.AutoAwesome }
        put(R.string.typing_context_revision_title) { Icons.Outlined.History }
        put(R.string.typing_phrase_completion_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.typing_learned_gate_title) { Icons.Outlined.Tune }
        put(R.string.typing_learn_threshold_title) { Icons.Outlined.School }
        put(R.string.typing_new_word_sightings_title) { Icons.Outlined.LibraryAdd }
        put(R.string.typing_ask_before_learning_title) { Icons.Outlined.HelpOutline }
        put(R.string.typing_offer_near_miss_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_undo_autocorrect_title) { Icons.AutoMirrored.Outlined.Undo }
        put(R.string.typing_undo_chip_title) { Icons.AutoMirrored.Outlined.Undo }
        put(R.string.typing_undo_chip_obviousness_title) { Icons.Outlined.Tune }
        put(R.string.typing_undo_memory_title) { Icons.Outlined.History }
        put(R.string.typing_stop_after_declines_title) { Icons.Outlined.Loop }
        put(R.string.typing_learn_corrections_title) { Icons.Outlined.School }
        put(R.string.typing_learned_corrections_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_learned_corrections_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.typing_adapt_taps_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_mistype_tolerance_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_fix_several_slips_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_suggestion_pages_title) { Icons.Outlined.UnfoldMore }
        put(R.string.typing_suggestion_swipe_up_title) { Icons.Outlined.UnfoldMore }
        put(R.string.typing_suggestion_expanded_grid_title) { Icons.Outlined.GridOn }
        put(R.string.typing_skip_all_caps_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_autocorrect_on_enter_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.typing_autocorrect_on_punctuation_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_dictionary_capitals_title) { Icons.Outlined.TextFields }
        put(R.string.typing_skip_typed_word_title) { Icons.Outlined.FilterList }
        put(R.string.typing_typed_word_first_title) { Icons.Outlined.TextFields }
        put(R.string.typing_number_prediction_title) { Icons.Outlined.Numbers }
        put(R.string.typing_block_offensive_title) { Icons.Outlined.Block }
        put(R.string.typing_auto_apostrophe_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_auto_capitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_double_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_double_space_window_title) { Icons.Outlined.Timer }
        put(R.string.typing_auto_space_punctuation_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_space_after_suggestion_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_wrap_selection_title) { Icons.Outlined.DataArray }
        put(R.string.typing_auto_close_brackets_title) { Icons.Outlined.DataArray }
        put(R.string.typing_continue_lists_title) { Icons.Outlined.FormatListNumbered }
        put(R.string.typing_shift_recase_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.typing_suggestions_all_fields_title) { Icons.Outlined.Lightbulb }
        put(R.string.typing_punctuation_suggestions_title) { Icons.Outlined.MoreHoriz }
        put(R.string.typing_suggestions_first_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.typing_suggestion_slots_title) { Icons.Outlined.Numbers }
        put(R.string.typing_fit_more_words_title) { Icons.Outlined.ViewWeek }
        put(R.string.typing_suggestion_emoji_slot_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.typing_suggestion_emoji_count_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.typing_suggestion_fixed_slots_title) { Icons.Outlined.ViewColumn }
        put(R.string.typing_suggestion_tinted_title) { Icons.Outlined.Palette }
        put(R.string.typing_suggestion_scroll_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_suggestion_more_arrow_title) { Icons.Outlined.ArrowDropDown }
        put(R.string.typing_primary_center_title) { Icons.Outlined.CenterFocusStrong }
        put(R.string.typing_contact_names_title) { Icons.Outlined.Contacts }
        put(R.string.typing_contact_emails_title) { Icons.Outlined.AlternateEmail }
        put(R.string.typing_contact_emails_in_email_fields_title) { Icons.Outlined.AlternateEmail }
        put(R.string.typing_typed_emails_title) { Icons.Outlined.History }
        put(R.string.typing_typed_numbers_title) { Icons.Outlined.History }
        put(R.string.typing_app_names_title) { Icons.Outlined.Apps }
        put(R.string.typing_inline_emoji_search_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.typing_inline_autofill_title) { Icons.Outlined.Password }
        put(R.string.typing_smart_replies_title) { Icons.Outlined.Quickreply }
        put(R.string.typing_personal_dictionary_title) { SymbolIcons.Dictionary }
        put(R.string.typing_custom_dictionaries_title) { SymbolIcons.Dictionary }
        put(R.string.typing_blacklist_title) { Icons.Outlined.VisibilityOff }
        put(R.string.backup_blacklist_scope_title) { Icons.Outlined.Translate }
        put(R.string.typing_word_menu_title) { Icons.Outlined.Tune }
        put(R.string.typing_synonym_sources_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_rank_control_title) { Icons.Outlined.SwapVert }
        put(R.string.typing_delete_edits_lists_title) { Icons.Outlined.Description }
        put(R.string.typing_smart_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.typing_smart_calc_title) { Icons.Outlined.Calculate }
        put(R.string.typing_smart_currency_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.typing_smart_units_title) { Icons.Outlined.Straighten }
        put(R.string.typing_smart_tool_keywords_title) { Icons.Outlined.Bolt }
        put(R.string.typing_smart_hit_detection_title) { Icons.Outlined.AdsClick }
        put(R.string.typing_group_autopilot_title) { Icons.Outlined.AdsClick }
        put(R.string.typing_autopilot_strength_title) { Icons.Outlined.Tune }
        put(R.string.typing_autopilot_show_title) { Icons.Outlined.Visibility }
        put(R.string.typing_autopilot_size_title) { Icons.Outlined.ZoomOutMap }
        put(R.string.typing_autopilot_outline_title) { Icons.Outlined.CropFree }
        put(R.string.typing_autopilot_animate_title) { Icons.Outlined.Animation }
        put(R.string.typing_group_octopus_title) { Icons.Outlined.Keyboard }
        put(R.string.typing_octopus_enabled_title) { Icons.Outlined.Keyboard }
        put(R.string.typing_octopus_placement_title) { Icons.Outlined.PictureInPicture }
        put(R.string.typing_octopus_density_title) { Icons.Outlined.Numbers }
        put(R.string.typing_octopus_kinds_title) { Icons.Outlined.Category }
        put(R.string.typing_octopus_glide_title) { Icons.Outlined.Gesture }
        put(R.string.typing_octopus_flick_title) { Icons.Outlined.SwipeUp }
        put(R.string.typing_octopus_sensitivity_title) { Icons.Outlined.Tune }
        put(R.string.typing_octopus_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_octopus_size_title) { Icons.Outlined.FormatSize }
        put(R.string.typing_octopus_hints_title) { Icons.Outlined.VisibilityOff }
        put(R.string.typing_octopus_long_press_title) { Icons.Outlined.Abc }
        put(R.string.typing_octopus_stack_title) { Icons.Outlined.Layers }
        put(R.string.typing_smart_dates_title) { Icons.Outlined.CalendarMonth }
        put(R.string.typing_smart_weather_title) { SymbolIcons.PartlyCloudyDay }
        put(R.string.typing_smart_lookups_title) { Icons.Outlined.Search }
        put(R.string.typing_smart_intents_title) { Icons.Outlined.Translate }
        put(R.string.typing_smart_gifs_title) { SymbolIcons.GifBox }
        put(R.string.typing_smart_numbers_title) { Icons.Outlined.Numbers }
        put(R.string.typing_smart_contacts_title) { Icons.Outlined.Contacts }
        put(R.string.typing_smart_repeats_title) { Icons.Outlined.Repeat }
        put(R.string.typing_smart_number_grouping_title) { Icons.AutoMirrored.Outlined.Segment }
        put(R.string.typing_otp_chip_title) { Icons.Outlined.Password }
        put(R.string.typing_otp_access_title) { Icons.Outlined.Notifications }
        put(R.string.typing_otp_code_fields_title) { Icons.Outlined.Dialpad }
        put(R.string.typing_otp_expiry_title) { Icons.Outlined.Timer }
        put(R.string.typing_otp_dismiss_title) { Icons.Outlined.NotificationsOff }
        put(R.string.typing_otp_per_digit_title) { Icons.Outlined.Pin }
        put(R.string.typing_glide_typing_title) { Icons.Outlined.Gesture }
        put(R.string.typing_glide_picker_title) { Icons.Outlined.Gesture }
        put(R.string.typing_glide_picker_sensitivity_title) { Icons.Outlined.Tune }
        put(R.string.typing_glide_picker_dwell_title) { Icons.Outlined.Timer }
        put(R.string.typing_glide_picker_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_glide_picker_choices_title) { Icons.Outlined.Numbers }
        put(R.string.typing_glide_swipe_style_title) { Icons.Outlined.School }
        put(R.string.typing_glide_vocabulary_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.typing_glide_sandbox_title) { Icons.Outlined.Person }
        put(R.string.typing_glide_search_all_chip_title) { Icons.Outlined.Search }
        put(R.string.typing_glide_kept_strokes_title) { Icons.Outlined.History }
        put(R.string.typing_glide_checkup_title) { Icons.Outlined.QueryStats }
        put(R.string.typing_glide_checkup_measure_title) { Icons.Outlined.Science }
        put(R.string.typing_glide_steadiness_title) { Icons.Outlined.Anchor }
        put(R.string.typing_glide_lookahead_title) { Icons.Outlined.FastForward }
        put(R.string.typing_glide_engine_title) { Icons.Outlined.Psychology }
        put(R.string.typing_glide_library_title) { Icons.Outlined.Extension }
        put(R.string.typing_glide_library_remove_title) { Icons.Outlined.Extension }
        put(R.string.typing_glide_commit_color_title) { Icons.Outlined.Palette }
        put(R.string.typing_glide_commit_scope_title) { Icons.Outlined.Tune }
        put(R.string.typing_letter_swipe_action_title) { Icons.Outlined.Draw }
        put(R.string.typing_handwrite_dot_title) { Icons.Outlined.Timer }
        put(R.string.typing_gesture_cooldown_title) { Icons.Outlined.Timer }
        put(R.string.typing_space_glide_multiword_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_two_thumb_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_finger_swipes_title) { Icons.Outlined.Swipe }
        put(R.string.typing_finger_swipes_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.typing_symbol_chords_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_symbol_chord_add_title) { Icons.Outlined.Add }
        put(R.string.typing_symbol_chords_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.typing_key_swipes_title) { Icons.Outlined.Swipe }
        put(R.string.typing_key_swipe_add_title) { Icons.Outlined.Add }
        put(R.string.typing_key_swipe_preset_title) { Icons.Outlined.Dashboard }
        put(R.string.typing_key_swipes_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.typing_two_thumb_word_end_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.typing_two_thumb_pause_title) { Icons.Outlined.Timer }
        put(R.string.typing_two_thumb_tap_pause_title) { Icons.Outlined.Timer }
        put(R.string.typing_two_thumb_one_letter_pause_title) { Icons.Outlined.Timer }
        put(R.string.typing_two_thumb_retract_title) { Icons.AutoMirrored.Outlined.Undo }
        put(R.string.typing_two_thumb_pause_band_title) { Icons.Outlined.HorizontalRule }
        put(R.string.typing_swipe_continues_typed_title) { Icons.Outlined.Swipe }
        put(R.string.typing_shift_glide_capitals_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_shift_glide_mode_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_space_after_glide_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_glide_backspace_undo_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.appearance_toolbar_placement_title) { Icons.Outlined.ViewAgenda }
        put(R.string.appearance_toolbar_show_strip_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.appearance_toolbar_landscape_column_title) { Icons.Outlined.ViewColumn }
        put(R.string.tooldetail_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_show_in_toolbox_title) { Icons.Outlined.Apps }
        put(R.string.tooldetail_icon_colour_title) { Icons.Outlined.Colorize }
        put(R.string.tooldetail_icon_colour_start_title) { Icons.Outlined.Colorize }
        put(R.string.tooldetail_icon_colour_end_title) { Icons.Outlined.Gradient }
        put(R.string.typing_glide_apostrophe_title) { Icons.Outlined.FormatQuote }
        put(R.string.typing_possessive_swipe_title) { Icons.Outlined.FormatQuote }
        put(R.string.typing_glide_start_radius_title) { Icons.Outlined.TripOrigin }
        put(R.string.typing_glide_end_radius_title) { Icons.Outlined.Adjust }
        put(R.string.typing_glide_near_radius_title) { Icons.Outlined.AltRoute }
        put(R.string.typing_glide_dwell_title) { Icons.Outlined.Timer }
        put(R.string.typing_glide_loop_title) { Icons.Outlined.Loop }
        put(R.string.typing_glide_loop_arc_title) { Icons.Outlined.Tune }
        put(R.string.typing_glide_loop_extent_title) { Icons.Outlined.ZoomOutMap }
        put(R.string.typing_glide_loop_radius_title) { Icons.Outlined.CenterFocusStrong }
        put(R.string.typing_glide_wiggle_title) { Icons.Outlined.Waves }
        put(R.string.typing_glide_wiggle_strength_title) { Icons.Outlined.LineWeight }
        put(R.string.typing_glide_wiggle_extent_title) { Icons.Outlined.Straighten }
        put(R.string.typing_glide_shapes_per_word_title) { Icons.Outlined.Layers }
        put(R.string.typing_swipe_start_distance_title) { Icons.Outlined.Straighten }
        put(R.string.typing_trail_style_title) { Icons.Outlined.LineWeight }
        put(R.string.typing_trail_width_title) { Icons.Outlined.LineWeight }
        put(R.string.typing_trail_length_title) { Icons.Outlined.Timeline }
        put(R.string.typing_trail_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.typing_glide_preview_title) { Icons.Outlined.Notifications }
        put(R.string.typing_glide_preview_height_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.typing_glide_preview_shift_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_glide_preview_size_title) { Icons.Outlined.FormatSize }
        put(R.string.typing_glide_preview_color_title) { Icons.Outlined.Palette }
        put(R.string.typing_glide_preview_text_color_title) { Icons.Outlined.Colorize }
        put(R.string.typing_glide_strip_preview_title) { Icons.Outlined.Lightbulb }
        put(R.string.typing_spacebar_language_arrows_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_spacebar_live_wpm_title) { Icons.Outlined.Speed }
        put(R.string.typing_language_echo_title) { Icons.Outlined.Timer }
        put(R.string.typing_spacebar_display_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_language_picker_style_title) { Icons.Outlined.ViewCarousel }
        put(R.string.typing_space_hold_picker_long_ring_title) { Icons.Outlined.ViewAgenda }
        put(R.string.typing_space_cursor_2d_title) { Icons.Outlined.Mouse }
        put(R.string.typing_space_cursor_step_title) { Icons.Outlined.Speed }
        put(R.string.typing_space_cursor_accelerate_title) { Icons.Outlined.FastForward }
        put(R.string.typing_space_cursor_direct_title) { Icons.Outlined.SwapHoriz }
        put(R.string.typing_space_cursor_edge_repeat_title) { Icons.Outlined.FastForward }
        put(R.string.typing_space_cursor_whole_keyboard_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_space_swipe_up_touchpad_title) { Icons.Outlined.SwipeUp }
        put(R.string.typing_space_swipe_up_numpad_title) { Icons.Outlined.SwipeUp }
        put(R.string.typing_space_cursor_top_speed_title) { Icons.Outlined.Speed }
        put(R.string.typing_space_cursor_magnifier_title) { Icons.Outlined.ZoomIn }
        put(R.string.typing_space_swipe_down_hide_title) { Icons.Outlined.SwipeDown }
        put(R.string.typing_edge_swipe_back_title) { Icons.AutoMirrored.Outlined.ArrowBack }
        put(R.string.typing_hint_flick_title) { Icons.Outlined.SwipeDownAlt }
        put(R.string.typing_capital_flick_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.typing_flick_hints_title) { Icons.Outlined.OpenWith }
        put(R.string.typing_flick_popup_title) { Icons.Outlined.Preview }
        put(R.string.typing_swipe_only_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.typing_flick_distance_title) { Icons.Outlined.Straighten }
        put(R.string.typing_flick_reach_title) { Icons.Outlined.Gesture }
        put(R.string.typing_flick_onto_letter_title) { Icons.Outlined.Swipe }
        put(R.string.typing_space_hold_keys_label) { Icons.Outlined.TouchApp }
        put(R.string.typing_backspace_swipe_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_unit_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_preview_title) { Icons.Outlined.Visibility }
        put(R.string.typing_backspace_up_clears_title) { Icons.Outlined.DeleteSweep }
        put(R.string.typing_forward_delete_swipe_title) { Icons.Outlined.KeyboardTab }
        put(R.string.typing_web_backspace_key_title) { Icons.Outlined.Public }
        put(R.string.typing_backspace_step_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.typing_backspace_char_step_title) { Icons.Outlined.Speed }
        put(R.string.typing_shift_enter_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.typing_volume_cursor_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.typing_volume_cursor_media_title) { Icons.Outlined.MusicNote }
        put(R.string.typing_hardware_input_title) { SymbolIcons.KeyboardExternalInput }
        put(R.string.typing_hw_shortcuts_title) { Icons.Outlined.Bolt }
        put(R.string.typing_hw_shortcuts_list_title) { Icons.Outlined.Keyboard }
        put(R.string.typing_hw_panel_nav_title) { Icons.Outlined.Gamepad }
        put(R.string.typing_hw_dpad_keys_title) { Icons.Outlined.ControlCamera }
        put(R.string.typing_hw_esc_title) { Icons.Outlined.Close }
        put(R.string.typing_hw_digit_chord_title) { Icons.Outlined.Pin }
        put(R.string.typing_hw_modifier_words_title) { Icons.Outlined.Abc }
        put(R.string.typing_hw_picker_timeout_title) { Icons.Outlined.Timer }
        put(R.string.typing_hw_suggestion_hotkeys_title) { Icons.Outlined.Numbers }
        put(R.string.typing_hw_suggestion_hints_title) { Icons.Outlined.Tag }
        put(R.string.typing_hw_pick_mode_key_title) { Icons.Outlined.ToggleOn }
        put(R.string.typing_hw_function_keys_title) { Icons.Outlined.Functions }
        put(R.string.typing_hw_lang_chord_title) { Icons.Outlined.Language }
        put(R.string.typing_hw_mac_title) { Icons.Outlined.KeyboardCommandKey }
        put(R.string.typing_hw_auto_show_title) { Icons.Outlined.Visibility }

        // ---- Tools screen ----
        put(R.string.tools_colored_icons_title) { Icons.Outlined.Palette }
        put(R.string.tools_split_panels_title) { Icons.Outlined.ViewAgenda }
        put(R.string.tools_gradient_icons_title) { Icons.Outlined.Gradient }

        // ---- Tool pages: shared ----
        put(CommonR.string.common_enable) { Icons.Outlined.PowerSettingsNew }
        put(R.string.toolai_keyword_case_title) { Icons.Outlined.TextFormat }

        // ---- Tool pages ----
        put(R.string.tooldetail_emoji_all_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.tooldetail_snippets_all_title) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.tooldetail_clipboard_all_title) { Icons.Outlined.ContentPaste }
        put(R.string.tooldetail_voice_all_title) { Icons.Outlined.Mic }
        put(R.string.clipboard_history_title) { Icons.Outlined.History }
        put(R.string.clipboard_suggest_recent_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_suggest_codes_title) { Icons.Outlined.Password }
        put(R.string.clipboard_toast_title) { Icons.Outlined.Notifications }
        put(R.string.clipboard_expiry_title) { Icons.Outlined.Timer }
        put(R.string.clipboard_max_title) { Icons.Outlined.Numbers }
        put(R.string.clipboard_sensitive_title) { Icons.Outlined.Shield }
        put(R.string.clipboard_detect_sensitive_title) { Icons.Outlined.Search }
        put(R.string.clipboard_sensitive_expiry_title) { Icons.Outlined.Timer }
        put(R.string.panel_layout_row_title) { Icons.Outlined.ViewAgenda }
        put(R.string.clipboard_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.clipboard_view_title) { Icons.Outlined.GridView }
        put(R.string.clipboard_columns_title) { Icons.Outlined.ViewWeek }
        put(R.string.clipboard_lines_title) { Icons.Outlined.TextFields }
        put(R.string.clipboard_time_title) { Icons.Outlined.Schedule }
        put(R.string.clipboard_max_chars_title) { Icons.Outlined.Straighten }
        put(R.string.clipboard_numbers_title) { Icons.Outlined.FormatListNumbered }
        put(R.string.clipboard_swipe_delete_title) { Icons.Outlined.Swipe }
        put(R.string.clipboard_undo_delete_title) { Icons.AutoMirrored.Outlined.Undo }
        put(R.string.clipboard_confirm_pinned_delete_title) { Icons.Outlined.PushPin }
        put(R.string.clipboard_pinned_last_title) { Icons.Outlined.PushPin }
        put(R.string.clipboard_pinned_in_place_title) { Icons.Outlined.PushPin }
        put(R.string.clipboard_pinned_folders_title) { Icons.Outlined.Folder }
        put(R.string.clipboard_search_title) { Icons.Outlined.Search }
        put(R.string.clipboard_search_regex_title) { Icons.Outlined.DataObject }
        put(R.string.clipboard_clear_button_title) { Icons.Outlined.DeleteSweep }
        put(R.string.clipboard_pinned_tabs_title) { Icons.Outlined.Tab }
        put(R.string.clipboard_outline_pinned_title) { Icons.Outlined.BorderStyle }
        put(R.string.clipboard_card_buttons_title) { Icons.Outlined.PushPin }
        put(R.string.clipboard_card_shortcut_title) { Icons.Outlined.TouchApp }
        put(R.string.clipboard_type_out_title) { Icons.Outlined.Keyboard }
        put(R.string.clipboard_bump_on_paste_title) { Icons.Outlined.VerticalAlignTop }
        put(R.string.clipboard_type_tags_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.clipboard_keep_rich_text_title) { Icons.Outlined.FormatBold }
        put(R.string.clipboard_swipe_pin_title) { Icons.Outlined.SwipeRight }
        put(R.string.clipboard_line_spacing_title) { Icons.Outlined.UnfoldMore }
        put(R.string.clipboard_private_copies_title) { Icons.Outlined.Lock }
        put(R.string.clipboard_copy_rules_title) { Icons.AutoMirrored.Outlined.Rule }
        put(R.string.clipboard_copy_rules_system_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_plain_letters_title) { Icons.Outlined.TextFormat }
        put(R.string.clipboard_panel_height_title) { Icons.Outlined.Height }
        put(R.string.clipboard_over_keys_height_title) { Icons.Outlined.UnfoldMore }
        put(R.string.clipboard_key_row_title) { Icons.Outlined.SpaceBar }
        put(R.string.clipboard_password_paste_title) { Icons.Outlined.Password }
        put(R.string.clipboard_link_previews_title) { Icons.Outlined.Link }
        put(R.string.clipboard_entities_title) { Icons.Outlined.Tag }
        put(R.string.clipboard_entity_icons_title) { Icons.Outlined.Category }
        put(R.string.clipboard_entity_to_clipboard_title) { Icons.Outlined.ContentCopy }
        put(R.string.clipboard_fragments_on_strip_title) { Icons.Outlined.ContentCut }
        put(R.string.clipboard_chip_life_title) { Icons.Outlined.Timer }
        put(R.string.clipboard_recent_chips_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_compact_paste_chip_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_phone_formats_title) { Icons.Outlined.Phone }
        put(R.string.clipboard_screenshots_title) { Icons.Outlined.Screenshot }
        put(R.string.clipboard_track_source_title) { Icons.Outlined.Apps }
        put(R.string.clipboard_storage_permission_title) { Icons.Outlined.Folder }
        put(R.string.clipboard_usage_permission_title) { Icons.Outlined.Lock }
        put(R.string.tooldetail_layout_nav_title) { Icons.Outlined.AspectRatio }
        put(R.string.tooldetail_flashlight_auto_off_title) { Icons.Outlined.FlashlightOff }
        put(R.string.tooldetail_compass_degrees_title) { Icons.Outlined.Explore }
        put(R.string.tooldetail_compass_qibla_title) { Icons.Outlined.Mosque }
        put(R.string.tooldetail_level_angles_title) { Icons.Outlined.Architecture }
        put(R.string.tooldetail_redo_ctrl_y_title) { Icons.AutoMirrored.Outlined.Redo }
        put(R.string.tooldetail_moon_southern_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_weather_fahrenheit_title) { Icons.Outlined.Thermostat }
        put(R.string.tooldetail_weather_auto_fetch_title) { Icons.Outlined.CloudDownload }
        put(R.string.tooldetail_weather_refresh_title) { Icons.Outlined.Schedule }
        put(R.string.tooldetail_calendar_hijri_title) { Icons.Outlined.CalendarMonth }
        put(R.string.toolai_weekend_title) { Icons.Outlined.Weekend }
        // Gallery (#708).
        put(R.string.gallery_access_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.gallery_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.gallery_columns_title) { Icons.Outlined.GridView }
        put(R.string.gallery_camera_tile_title) { Icons.Outlined.PhotoCamera }
        put(R.string.gallery_show_videos_title) { Icons.Outlined.Videocam }
        put(R.string.gallery_scrubber_title) { Icons.Outlined.Timeline }
        put(R.string.gallery_start_expanded_title) { Icons.Outlined.OpenInFull }
        put(R.string.gallery_remember_album_title) { Icons.Outlined.Collections }
        put(R.string.gallery_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.gallery_quality_title) { Icons.Outlined.Compress }
        put(R.string.gallery_max_selection_title) { Icons.Outlined.Checklist }
        put(R.string.gallery_caption_title) { Icons.Outlined.Subtitles }
        put(R.string.gallery_fallback_title) { Icons.Outlined.Share }
        put(R.string.gallery_close_after_send_title) { Icons.Outlined.KeyboardHide }
        put(R.string.gallery_save_edited_title) { Icons.Outlined.Save }
        // To-do list (#920).
        put(R.string.tooldetail_todo_open_title) { Icons.Outlined.Checklist }
        put(R.string.tooldetail_todo_hide_done_title) { Icons.Outlined.VisibilityOff }
        put(R.string.tooldetail_todo_due_chip_title) { Icons.Outlined.Notifications }
        put(R.string.tooldetail_todo_sync_title) { Icons.Outlined.Sync }
        put(R.string.tooldetail_todo_sync_now_title) { Icons.Outlined.Sync }
        put(R.string.tooldetail_todo_import_title) { Icons.Outlined.Download }
        put(R.string.tooldetail_todo_clear_title) { Icons.Outlined.Delete }
        put(R.string.todo_clear_done_title) { Icons.Outlined.Delete }
        // Send files (#729).
        put(R.string.send_instance_title) { Icons.Outlined.Dns }
        put(R.string.send_expire_title) { Icons.Outlined.Timer }
        put(R.string.send_downloads_title) { Icons.Outlined.Download }
        put(R.string.send_password_title) { Icons.Outlined.Lock }
        put(R.string.send_after_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.send_detect_title) { Icons.Outlined.Link }
        put(R.string.send_open_after_title) { Icons.Outlined.OpenInNew }
        put(R.string.send_nearby_title) { Icons.Outlined.Devices }
        put(R.string.send_keep_direct_title) { Icons.Outlined.WifiTethering }
        put(R.string.send_nearby_name_title) { Icons.Outlined.Badge }
        put(R.string.send_share_sheet_title) { Icons.Outlined.Share }
        put(R.string.send_history_title) { Icons.Outlined.History }
        put(R.string.send_history_open_title) { Icons.Outlined.History }
        put(R.string.gallery_strip_location_title) { Icons.Outlined.PrivacyTip }
        put(R.string.gallery_strip_all_title) { Icons.Outlined.Fingerprint }
        put(R.string.gallery_random_names_title) { Icons.Outlined.Shuffle }
        put(R.string.gallery_history_title) { Icons.Outlined.History }
        put(R.string.gallery_history_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.tooldetail_camera_front_title) { Icons.Outlined.Cameraswitch }
        put(R.string.tooldetail_camera_mirror_title) { Icons.Outlined.Flip }
        put(R.string.tooldetail_camera_fullframe_title) { Icons.Outlined.AspectRatio }
        put(R.string.tooldetail_camera_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.tooldetail_camera_shutter_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_camera_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_camera_timer_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_camera_resolution_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.tooldetail_camera_search_button_title) { Icons.Outlined.ImageSearch }
        put(R.string.tooldetail_camera_search_with_title) { Icons.Outlined.Share }
        put(R.string.tooldetail_camera_search_engine_title) { Icons.Outlined.TravelExplore }
        put(R.string.tooldetail_launcher_open_mode_title) { Icons.AutoMirrored.Outlined.OpenInNew }
        put(R.string.tooldetail_launcher_combos_title) { Icons.Outlined.VerticalSplit }
        put(R.string.tooldetail_launcher_sort_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.tooldetail_launcher_labels_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.tooldetail_launcher_columns_title) { Icons.Outlined.GridView }
        put(R.string.tooldetail_launcher_icon_size_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.tooldetail_launcher_icon_shape_title) { Icons.Outlined.RoundedCorner }
        put(R.string.tooldetail_launcher_hidden_title) { Icons.Outlined.VisibilityOff }
        put(R.string.tooldetail_launcher_work_title) { Icons.Outlined.WorkOutline }
        put(R.string.tooldetail_launcher_folders_title) { Icons.Outlined.Folder }
        put(R.string.tooldetail_launcher_recents_title) { Icons.Outlined.History }
        put(R.string.tooldetail_launcher_recents_count_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_launcher_drilldown_title) { Icons.Outlined.AppShortcut }
        put(R.string.tooldetail_launcher_non_exported_title) { Icons.Outlined.Lock }
        put(R.string.tooldetail_dictionary_auto_title) { Icons.Outlined.Search }
        put(R.string.tooldetail_dictionary_sources_title) { Icons.Outlined.SwapHoriz }
        put(R.string.tooldetail_dictionary_translations_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_dictionary_translation_langs_title) { Icons.Outlined.Language }
        // ---- Vocabulary ----
        put(R.string.tooldetail_vocab_packs_title) { Icons.Outlined.Inventory2 }
        put(R.string.tooldetail_vocab_lists_title) { Icons.Outlined.PlaylistAdd }
        put(R.string.tooldetail_vocab_review_title) { Icons.Outlined.Style }
        put(R.string.tooldetail_vocab_browse_title) { Icons.Outlined.AutoStories }
        put(R.string.tooldetail_vocab_nudges_title) { Icons.Outlined.Lightbulb }
        put(R.string.tooldetail_vocab_nudge_self_title) { Icons.Outlined.Abc }
        put(R.string.tooldetail_vocab_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_vocab_scope_title) { Icons.Outlined.FilterList }
        put(R.string.tooldetail_vocab_level_title) { Icons.Outlined.Tune }
        put(R.string.tooldetail_vocab_cooldown_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_vocab_related_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_vocab_translations_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_vocab_audio_source_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_vocab_accent_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.tooldetail_vocab_tts_rate_title) { Icons.Outlined.Speed }
        put(R.string.tooldetail_vocab_tts_pitch_title) { Icons.Outlined.GraphicEq }
        put(R.string.tooldetail_vocab_audio_test_title) { Icons.Outlined.PlayArrow }
        put(R.string.tooldetail_vocab_scheduler_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_vocab_goal_title) { Icons.Outlined.Flag }
        put(R.string.tooldetail_vocab_wotd_card_title) { Icons.Outlined.Today }
        put(R.string.tooldetail_vocab_wotd_chip_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_vocab_wotd_interval_title) { Icons.Outlined.Schedule }
        put(R.string.tooldetail_vocab_wotd_chip_times_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_text_edit_repeat_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_cursor_repeat_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_cursor_repeat_toolbox_title) { Icons.Outlined.GridView }
        put(R.string.tooldetail_select_mode_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_select_mode_taps_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_trackpad_step_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.tooldetail_trackpad_step_y_title) { Icons.Outlined.SwapVert }
        put(R.string.tooldetail_trackpad_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_trackpad_taps_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_trackpad_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_trackpad_trail_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_trackpad_magnifier_title) { Icons.Outlined.ZoomIn }
        put(R.string.tooldetail_numpad_calc_title) { Icons.Outlined.Calculate }
        put(R.string.tooldetail_incognito_learning_title) { Icons.Outlined.School }
        put(R.string.tooldetail_incognito_clipboard_title) { Icons.Outlined.ContentPaste }
        put(R.string.tooldetail_incognito_private_clipboard_title) { Icons.Outlined.ContentCopy }
        put(R.string.tooldetail_incognito_auto_nav_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_power_now_title) { Icons.Outlined.BatterySaver }
        put(R.string.tooldetail_power_preset_title) { Icons.Outlined.Bolt }
        put(R.string.tooldetail_power_trigger_title) { Icons.Outlined.AutoMode }
        put(R.string.tooldetail_power_battery_title) { Icons.Outlined.BatteryAlert }
        put(R.string.tooldetail_power_charging_title) { Icons.Outlined.BatteryChargingFull }
        put(R.string.tooldetail_power_drop_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_power_drop_sound_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.tooldetail_power_drop_anim_title) { Icons.Outlined.Animation }
        put(R.string.tooldetail_power_drop_trail_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_power_drop_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.tooldetail_power_drop_network_title) { Icons.Outlined.CloudOff }
        put(R.string.tooldetail_power_drop_screenshot_title) { Icons.Outlined.Screenshot }
        put(R.string.tooldetail_power_drop_models_title) { Icons.Outlined.Memory }
        put(R.string.tooldetail_power_drop_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.tooldetail_power_drop_glide_title) { Icons.Outlined.Gesture }
        put(R.string.tooldetail_power_drop_stats_title) { Icons.Outlined.QueryStats }
        put(R.string.tooldetail_power_drop_media_pin_title) { Icons.Outlined.PushPin }

        // ---- Media control tool ----
        put(R.string.tooldetail_mediactl_pin_title) { Icons.Outlined.PushPin }
        put(R.string.tooldetail_mediactl_apps_title) { Icons.Outlined.MusicNote }
        put(R.string.tooldetail_mediactl_access_title) { Icons.Outlined.Notifications }
        // KDE Connect (#285)
        put(R.string.kdeconnect_enabled_title) { Icons.Outlined.Phonelink }
        put(R.string.kdeconnect_devices_title) { Icons.Outlined.Devices }
        put(R.string.kdeconnect_lifetime_title) { Icons.Outlined.Link }
        put(R.string.kdeconnect_auto_connect_title) { Icons.Outlined.Sync }
        put(R.string.kdeconnect_clipboard_receive_title) { Icons.Outlined.ContentPaste }
        put(R.string.kdeconnect_clear_secrets_title) { Icons.Outlined.Timer }
        put(R.string.kdeconnect_clipboard_send_title) { Icons.Outlined.ContentCopy }
        put(R.string.kdeconnect_remote_typing_title) { Icons.Outlined.Keyboard }
        put(R.string.kdeconnect_pipeline_title) { Icons.Outlined.Translate }
        put(R.string.kdeconnect_pad_speed_title) { Icons.Outlined.Speed }
        put(R.string.kdeconnect_pad_accel_title) { Icons.Outlined.Mouse }
        put(R.string.kdeconnect_scroll_speed_title) { Icons.Outlined.SwapVert }
        put(R.string.kdeconnect_natural_scroll_title) { Icons.Outlined.SwipeVertical }
        put(R.string.kdeconnect_tap_click_title) { Icons.Outlined.TouchApp }
        put(R.string.kdeconnect_pad_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.kdeconnect_receive_files_title) { Icons.Outlined.Download }
        put(R.string.kdeconnect_share_sheet_title) { Icons.Outlined.Share }
        put(R.string.kdeconnect_battery_title) { Icons.Outlined.BatteryStd }
        put(R.string.kdeconnect_media_title) { Icons.Outlined.MusicNote }
        put(R.string.kdeconnect_media_access_title) { Icons.Outlined.Notifications }
        put(R.string.kdeconnect_notify_title) { Icons.Outlined.Phonelink }
        put(R.string.musicapps_reset_title) { Icons.Outlined.Restore }

        // ---- Selection actions ----
        // The row that opens the screen carries the screen's own glyph, so the
        // row, its search result and the heading are one icon.
        put(R.string.selection_macros_title) { Icons.Outlined.HighlightAlt }
        put(R.string.selection_macros_placement_title) { Icons.Outlined.ViewStream }
        put(R.string.selection_macros_every_title) { Icons.Outlined.AllInclusive }
        put(R.string.selection_macros_caret_title) { Icons.Outlined.PushPin }
        put(R.string.selection_macros_detect_title) { Icons.Outlined.Sensors }
        put(R.string.text_actions_settings_actions_title) { Icons.Outlined.Keyboard }
        put(R.string.text_actions_settings_translate_title) { Icons.Outlined.Translate }
        put(R.string.selection_macros_actions_title) { Icons.Outlined.Checklist }
        put(R.string.selection_macros_ai_title) { Icons.Outlined.AutoAwesome }
        put(R.string.selection_macros_zones_title) { Icons.Outlined.Public }

        // ---- Data saver ----
        put(R.string.datasaver_manual_title) { Icons.Outlined.DataSaverOn }
        put(R.string.datasaver_trigger_title) { Icons.Outlined.AutoMode }
        put(R.string.datasaver_link_previews_title) { Icons.Outlined.Link }
        put(R.string.datasaver_dictionary_title) { SymbolIcons.Dictionary }
        put(R.string.datasaver_photos_title) { Icons.Outlined.Wallpaper }
        put(R.string.datasaver_weather_title) { SymbolIcons.PartlyCloudyDay }
        put(R.string.datasaver_rates_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.datasaver_addons_title) { Icons.Outlined.Extension }
        put(R.string.datasaver_media_title) { SymbolIcons.AnimatedImages }
        put(R.string.datasaver_search_title) { Icons.Outlined.Search }
        put(R.string.datasaver_animated_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.datasaver_downloads_title) { Icons.Outlined.CloudDownload }
        put(R.string.datasaver_ai_title) { Icons.Outlined.AutoAwesome }
        put(R.string.datasaver_voice_title) { Icons.Outlined.Dns }
        put(R.string.datasaver_grammar_title) { Icons.Outlined.Spellcheck }
        put(R.string.datasaver_offline_fallback_title) { Icons.Outlined.CloudOff }
        // The signpost left behind on the languages screen, where the metered
        // download confirmation used to live.
        put(R.string.langemoji_lang_metered_title) { Icons.Outlined.DataSaverOn }
        put(R.string.statistics_toggle_title) { Icons.Outlined.QueryStats }
        put(R.string.statistics_learned_corrections_action) { Icons.Outlined.Spellcheck }
        put(R.string.tooldetail_power_drop_popup_title) { Icons.Outlined.Notifications }
        put(R.string.tooldetail_power_drop_glass_blur_title) { Icons.Outlined.BlurOn }
        put(R.string.tooldetail_autocorrect_title) { Icons.Outlined.Spellcheck }
        put(R.string.tooldetail_selection_actions_title) { Icons.Outlined.HighlightAlt }
        put(R.string.tooldetail_selection_actions_nav_title) { Icons.Outlined.Checklist }
        put(R.string.tooldetail_fancy_style_title) { Icons.Outlined.TextFormat }
        put(R.string.tooldetail_fancy_keep_title) { Icons.Outlined.PushPin }
        put(R.string.tooldetail_fancy_auto_off_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_fancy_language_nav_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_custom_layout_layout_title) { Icons.Outlined.GridView }
        put(R.string.tooldetail_custom_layout_keymaps_nav_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_typing_nav_title) { Icons.Outlined.Keyboard }
        put(R.string.tooldetail_keypress_nav_title) { Icons.Outlined.TouchApp }
        put(R.string.tooldetail_themes_nav_title) { Icons.Outlined.Palette }
        put(R.string.tooldetail_appearance_icons_nav_title) { Icons.Outlined.Category }
        put(R.string.tooldetail_appearance_emoji_nav_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.tooldetail_appearance_fonts_nav_title) { Icons.Outlined.TextFields }
        put(R.string.tooldetail_handwriting_stylus_title) { Icons.Outlined.Draw }
        put(R.string.tooldetail_handwriting_auto_space_title) { Icons.Outlined.SpaceBar }
        put(R.string.tooldetail_handwriting_zinnia_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_handwriting_pause_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_handwriting_full_screen_title) { Icons.Outlined.OpenInFull }
        put(R.string.tooldetail_handwriting_languages_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_sticker_packs_title) { SymbolIcons.Sticker }
        put(R.string.tooldetail_sticker_suggest_title) { Icons.Outlined.AutoAwesome }
        put(R.string.tooldetail_sticker_suggest_style_title) { Icons.Outlined.ViewAgenda }
        put(R.string.tooldetail_sticker_suggest_trigger_title) { Icons.AutoMirrored.Outlined.Backspace }
        put(R.string.tooldetail_media_full_bleed_title) { Icons.Outlined.Fullscreen }
        put(R.string.tooldetail_media_gif_per_row_title) { Icons.Outlined.ViewColumn }
        put(R.string.tooldetail_media_sticker_per_row_title) { Icons.Outlined.ViewColumn }
        put(R.string.tooldetail_media_spacing_title) { Icons.Outlined.Padding }
        put(R.string.tooldetail_media_hide_header_title) { Icons.Outlined.SwipeUp }
        put(R.string.tooldetail_media_remember_search_title) { Icons.Outlined.History }
        put(R.string.tooldetail_gif_recent_title) { Icons.Outlined.History }
        put(R.string.tooldetail_media_gifcities_title) { Icons.Outlined.History }
        put(R.string.tooldetail_gif_favourites_offline_title) { Icons.Outlined.Download }
        put(R.string.tooldetail_media_sticker_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_media_gif_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_media_limit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_image_columns_title) { Icons.Outlined.GridOn }
        put(R.string.tooldetail_search_safe_title) { Icons.Outlined.Shield }
        put(R.string.tooldetail_search_count_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_search_answer_title) { Icons.Outlined.AutoAwesome }
        put(R.string.tooldetail_search_open_browser_title) { Icons.Outlined.OpenInBrowser }
        put(R.string.tooldetail_tavily_advanced_title) { Icons.Outlined.TravelExplore }
        put(R.string.tooldetail_searx_categories_title) { Icons.Outlined.Category }
        put(R.string.tooldetail_ocr_select_all_title) { Icons.Outlined.SelectAll }
        put(R.string.tooldetail_ocr_engine_title) { SymbolIcons.ConvertToText }
        put(R.string.tooldetail_qr_scan_auto_title) { Icons.Outlined.Bolt }
        put(R.string.tooldetail_qr_scan_haptics_title) { Icons.Outlined.Vibration }
        put(R.string.tooldetail_qr_scan_preview_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_doc_scan_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.voice_enabled_title) { Icons.Outlined.Mic }
        put(R.string.voice_ui_title) { Icons.Outlined.ViewAgenda }
        put(R.string.voice_strip_language_title) { Icons.Outlined.Language }
        put(R.string.voice_typing_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.voice_hold_picks_title) { Icons.Outlined.TouchApp }
        put(R.string.voice_resume_title) { Icons.Outlined.Replay }
        put(R.string.voice_pause_media_title) { Icons.Outlined.MusicNote }
        put(R.string.voice_mic_title) { Icons.Outlined.HeadsetMic }
        put(R.string.voice_mic_switch_title) { Icons.Outlined.HeadsetMic }
        put(R.string.voice_engine_picker_title) { Icons.Outlined.TouchApp }
        put(R.string.voice_system_on_device_title) { Icons.Outlined.Memory }
        put(R.string.voice_engine_languages_title) { Icons.Outlined.Translate }
        put(R.string.voice_ai_tidy_title) { Icons.Outlined.AutoAwesome }
        put(R.string.voice_continuous_title) { Icons.Outlined.MicNone }
        put(R.string.voice_silence_stop_title) { Icons.Outlined.Timer }
        put(R.string.voice_vad_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.voice_vad_threshold_title) { Icons.Outlined.Tune }
        put(R.string.voice_smart_turn_title) { Icons.Outlined.Psychology }
        put(R.string.voice_denoise_title) { Icons.Outlined.Waves }
        put(R.string.voice_punctuation_title) { Icons.Outlined.MoreHoriz }
        put(R.string.voice_auto_punctuation_title) { Icons.Outlined.Spellcheck }
        put(R.string.voice_engine_title) { Icons.Outlined.GraphicEq }
        put(R.string.voice_languages_title) { Icons.Outlined.Language }
        put(R.string.voice_translate_title) { Icons.Outlined.Translate }
        put(R.string.voice_server_language_title) { Icons.Outlined.Language }
        put(R.string.voice_bias_personal_title) { Icons.Outlined.Spellcheck }
        put(R.string.voice_server_test_title) { Icons.Outlined.Dns }
        put(R.string.voice_hold_title) { Icons.Outlined.TouchApp }
        put(R.string.models_whisper_fallback_title) { Icons.Outlined.Memory }
        put(R.string.models_whisper_compute_title) { Icons.Outlined.Speed }
        put(R.string.tooldetail_grammar_dialect_title) { Icons.Outlined.Language }
        put(R.string.tooldetail_grammar_debounce_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_grammar_system_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_grammar_no_suggestions_title) { Icons.Outlined.FormatUnderlined }
        put(R.string.tooldetail_grammar_correctness_title) { Icons.Outlined.Spellcheck }
        put(R.string.tooldetail_grammar_clarity_title) { Icons.Outlined.Visibility }
        put(R.string.tooldetail_grammar_engagement_title) { Icons.Outlined.AutoAwesome }
        put(R.string.tooldetail_grammar_delivery_title) { Icons.Outlined.Campaign }
        put(R.string.tooldetail_wiki_markdown_title) { Icons.Outlined.Link }
        put(R.string.tooldetail_wiki_link_limit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_chips_nav_title) { Icons.Outlined.Calculate }
        put(R.string.tooldetail_calc_degrees_title) { Icons.Outlined.Architecture }
        put(R.string.tooldetail_calc_phone_layout_title) { Icons.Outlined.Dialpad }
        put(R.string.tooldetail_calc_precision_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_units_compound_title) { Icons.Outlined.Height }
        put(R.string.tooldetail_currency_auto_fetch_title) { Icons.Outlined.CloudDownload }
        put(R.string.servers_title) { Icons.Outlined.Dns }
        put(R.string.tooldetail_currency_decimals_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_currency_label_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.tooldetail_currency_refresh_title) { Icons.Outlined.Refresh }
        put(R.string.tooldetail_currency_source_title) { Icons.Outlined.Cloud }
        put(R.string.tooldetail_crypto_enable_title) { Icons.Outlined.CurrencyBitcoin }
        put(R.string.tooldetail_crypto_decimals_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_crypto_refresh_title) { Icons.Outlined.Refresh }
        put(R.string.tooldetail_crypto_source_title) { Icons.Outlined.Cloud }
        put(R.string.tooldetail_crypto_coins_title) { Icons.Outlined.Toll }
        put(R.string.tooldetail_rate_fallback_title) { Icons.Outlined.CloudSync }
        put(R.string.tooldetail_qr_gen_size_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.tooldetail_qr_gen_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_qr_gen_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.tooldetail_qr_max_chars_title) { Icons.Outlined.Straighten }
        put(R.string.tooldetail_password_length_title) { Icons.Outlined.Straighten }
        put(R.string.tooldetail_password_uppercase_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.tooldetail_password_digits_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_password_symbols_title) { Icons.Outlined.Tag }
        put(R.string.tooldetail_password_ambiguous_title) { Icons.Outlined.Visibility }
        put(R.string.tooldetail_passphrase_words_title) { Icons.Outlined.Abc }
        put(R.string.tooldetail_passphrase_capitalize_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.tooldetail_passphrase_digit_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_modes_edit_title) { Icons.Outlined.Tune }
        put(R.string.toolai_typing_seconds_label) { Icons.Outlined.Timer }
        put(R.string.toolai_typing_words_label) { Icons.Outlined.Abc }
        put(R.string.toolai_typing_punctuation_title) { Icons.Outlined.MoreHoriz }
        put(R.string.toolai_typing_numbers_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_typing_glide_title) { Icons.Outlined.Gesture }
        put(R.string.toolai_typing_suggestions_title) { Icons.Outlined.Lightbulb }
        put(R.string.toolai_typing_clear_records_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_ai_max_tokens_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_ai_show_thinking_title) { Icons.Outlined.Psychology }
        // AI reply (#234).
        put(R.string.toolai_reply_count_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_reply_length_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.toolai_reply_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.toolai_reply_context_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_reply_image_mode_title) { Icons.Outlined.Image }
        put(R.string.toolai_reply_tidy_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_reply_history_title) { Icons.Outlined.History }
        put(R.string.toolai_reply_provider_nav_title) { Icons.Outlined.AutoAwesome }
        put(R.string.toolai_ai_model_picker_title) { Icons.Outlined.Tune }
        put(R.string.toolai_ai_actions_title) { Icons.Outlined.AutoAwesome }
        put(R.string.toolai_ai_diff_title) { Icons.Outlined.Difference }
        put(R.string.toolai_ai_diff_first_title) { Icons.Outlined.Difference }
        put(R.string.toolai_strip_suggest_title) { Icons.Outlined.AutoAwesome }
        put(R.string.toolai_strip_suggest_word_only_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.toolai_strip_suggest_chars_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_ai_tool_search_title) { Icons.Outlined.TravelExplore }
        put(R.string.toolai_ai_tool_search_needs_setup_title) { Icons.Outlined.TravelExplore }
        put(R.string.toolai_ai_tool_fetch_title) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.toolai_ai_tool_rounds_title) { Icons.Outlined.Repeat }
        put(R.string.toolai_ai_mcp_title) { Icons.Outlined.Hub }
        put(R.string.toolai_ai_chat_nav_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_ai_history_title) { Icons.Outlined.History }
        put(R.string.toolai_ai_history_nav_title) { Icons.Outlined.History }
        put(R.string.toolai_ai_history_max_title) { Icons.Outlined.Numbers }
        put(R.string.toolai_continue_context_title) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.toolai_keep_chats_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.toolai_chat_enter_sends_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.toolai_chat_auto_rename_title) { Icons.Outlined.DriveFileRenameOutline }
        put(R.string.toolai_chat_follow_ups_title) { Icons.Outlined.Lightbulb }
        put(R.string.toolai_research_depth_title) { Icons.Outlined.TravelExplore }
        put(R.string.toolai_research_review_plan_title) { Icons.Outlined.Checklist }
        put(R.string.toolai_ai_selection_menu_title) { Icons.Outlined.HighlightAlt }
        put(R.string.toolai_delete_chats_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_memory_screen_title) { Icons.Outlined.Psychology }
        put(R.string.toolai_memory_enabled_title) { Icons.Outlined.Psychology }
        put(R.string.toolai_memory_cloud_title) { Icons.Outlined.Cloud }
        put(R.string.toolai_memory_delete_all_title) { Icons.Outlined.DeleteSweep }
        put(R.string.toolai_ai_action_raw_title) { Icons.Outlined.Code }
        put(R.string.toolai_ai_action_ask_title) { Icons.Outlined.QuestionAnswer }
        put(R.string.toolai_ai_action_prefill_title) { Icons.Outlined.EditNote }
        put(R.string.toolai_ai_action_before_cursor_title) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.toolai_ai_action_empty_field_title) { Icons.Outlined.CheckBoxOutlineBlank }
        put(R.string.toolai_ai_action_append_title) { Icons.AutoMirrored.Outlined.PlaylistAdd }
        put(R.string.toolai_ai_action_output_only_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.toolai_translate_into_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_translate_engine_title) { Icons.Outlined.CloudSync }
        put(R.string.tooldetail_translate_downloaded_first_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.tooldetail_translate_only_downloaded_title) { Icons.Outlined.FilterAlt }
        put(R.string.tooldetail_deepl_translate_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_deepl_write_title) { Icons.Outlined.EditNote }
        put(R.string.tooldetail_deepl_style_title) { Icons.Outlined.Tune }
        put(R.string.customdict_emoji_auto_download_title) { Icons.Outlined.CloudDownload }
        put(R.string.customdict_only_my_lists_title) { Icons.Outlined.FilterAlt }

        // Rows the #43 reorganisation added: hub rows for the split pages, the
        // per-page resets, and the choice rows that replaced radio lists.
        put(R.string.appearance_toolbar_reset_title) { Icons.Outlined.Restore }
        put(R.string.appearance_toolbar_section_title) { Icons.Outlined.ViewDay }
        put(R.string.appearance_toolbox_reset_title) { Icons.Outlined.Restore }
        put(R.string.appearance_toolbox_section_title) { Icons.Outlined.Apps }
        put(R.string.backup_auto_group_title) { Icons.Outlined.Schedule }
        put(R.string.customdict_add_language_title) { Icons.Outlined.Add }
        put(R.string.fonts_english_header) { Icons.Outlined.TextFields }
        put(R.string.home_addons_title) { Icons.Outlined.Extension }
        put(R.string.home_datasaver_title) { Icons.Outlined.DataSaverOn }
        put(R.string.home_notifications_title) { Icons.Outlined.Notifications }
        put(R.string.home_modes_title) { Icons.Outlined.ViewCarousel }
        put(R.string.home_rows_title) { Icons.Outlined.ViewAgenda }
        put(R.string.keypress_haptics_page_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_popup_group_title) { Icons.Outlined.Preview }
        put(R.string.keypress_shortcuts_group_title) { Icons.Outlined.Shortcut }
        put(R.string.languages_translit_hints_row_title) { Icons.Outlined.Translate }
        put(R.string.languages_phonetic_strip_fixed_title) { Icons.Outlined.PushPin }
        put(R.string.languages_raw_keys_chip_title) { Icons.Outlined.Keyboard }
        put(R.string.languages_phonetic_strip_source_title) { Icons.Outlined.Translate }
        put(R.string.languages_phonetic_candidates_title) { Icons.Outlined.ViewAgenda }
        put(R.string.languages_neural_phonetic_title) { Icons.Outlined.AutoAwesome }
        put(R.string.languages_neural_phonetic_model_title) { Icons.Outlined.CloudDownload }
        put(R.string.languages_romanized_dictation_model_title) { Icons.Outlined.CloudDownload }
        put(R.string.languages_neural_leads_title) { Icons.Outlined.SpaceBar }
        put(R.string.languages_phonetic_guide_title) { Icons.AutoMirrored.Outlined.MenuBook }
        put(R.string.langemoji_emoji_panel_title) { Icons.Outlined.GridView }
        put(R.string.languages_cjk_double_pinyin_title) { Icons.Outlined.Keyboard }
        put(R.string.languages_cjk_double_pinyin_custom_title) { Icons.Outlined.EditNote }
        put(R.string.languages_cjk_region_title) { Icons.Outlined.Public }
        put(R.string.layout_one_handed_group_title) { SymbolIcons.MobileHandLeft }
        put(R.string.layout_size_position_title) { Icons.Outlined.FormatSize }
        put(R.string.typing_group_corrections_title) { Icons.Outlined.Spellcheck }
        put(R.string.typing_group_gestures_title) { Icons.Outlined.Gesture }
        put(R.string.typing_group_glide_title) { Icons.Outlined.Gesture }
        put(R.string.typing_page_spacebar_title) { Icons.Outlined.SpaceBar }
        put(R.string.typing_page_flicks_title) { Icons.Outlined.Swipe }
        put(R.string.typing_group_writing_title) { Icons.Outlined.Abc }
        put(R.string.clipboard_page_chips_title) { Icons.Outlined.ContentPaste }
        put(R.string.clipboard_page_rules_title) { Icons.Outlined.Shield }
        put(R.string.typing_group_hardware_title) { SymbolIcons.KeyboardExternalInput }
        put(R.string.typing_group_otp_title) { Icons.Outlined.Password }
        put(R.string.typing_group_smart_chips_title) { Icons.Outlined.AutoAwesome }
        put(R.string.typing_group_suggestions_title) { Icons.Outlined.Lightbulb }
        // ---- The theme editor: colours, sliders and shapes ----
        put(R.string.theme_board_background_title) { Icons.Outlined.Wallpaper }
        put(R.string.theme_board_gradient_title) { Icons.Outlined.Gradient }
        put(R.string.theme_suggestion_bar_title) { Icons.Outlined.Lightbulb }
        put(R.string.theme_navigation_bar_title) { Icons.Outlined.CallToAction }
        put(R.string.theme_one_handed_title) { SymbolIcons.MobileHandLeft }
        put(R.string.theme_one_handed_icon_title) { SymbolIcons.MobileHandLeft }
        put(R.string.theme_image_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.theme_image_blur_title) { Icons.Outlined.BlurOn }
        put(R.string.theme_key_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_letter_keys_title) { Icons.Outlined.Keyboard }
        put(R.string.theme_key_gradient_title) { Icons.Outlined.Gradient }
        put(R.string.theme_key_background_title) { Icons.Outlined.FormatColorFill }
        put(R.string.theme_key_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_modifier_keys_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.theme_modifier_key_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_hint_text_title) { Icons.Outlined.Subtitles }
        put(R.string.theme_enter_key_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.theme_enter_key_icon_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.theme_pressed_key_title) { Icons.Outlined.TouchApp }
        put(R.string.theme_key_border_title) { Icons.Outlined.BorderStyle }
        put(R.string.theme_border_width_title) { Icons.Outlined.LineWeight }
        put(R.string.theme_key_elevation_title) { Icons.Outlined.Layers }
        put(R.string.theme_key_shadow_color_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_texture_opacity_title) { Icons.Outlined.Texture }
        put(R.string.theme_key_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.theme_key_height_title) { Icons.Outlined.Height }
        put(R.string.theme_key_gap_title) { Icons.Outlined.SpaceBar }
        put(R.string.theme_side_padding_left_title) { Icons.Outlined.Padding }
        put(R.string.theme_side_padding_right_title) { Icons.Outlined.Padding }
        put(R.string.theme_font_scale_title) { Icons.Outlined.FormatSize }
        put(R.string.theme_hint_scale_title) { Icons.Outlined.FormatSize }
        put(R.string.theme_accent_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_gesture_trail_title) { Icons.Outlined.Gesture }
        put(R.string.theme_trail_width_title) { Icons.Outlined.LineWeight }
        put(R.string.theme_trail_opacity_title) { Icons.Outlined.Opacity }
        put(R.string.theme_popup_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_popup_background_title) { Icons.Outlined.ChatBubbleOutline }
        put(R.string.theme_popup_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_popup_border_title) { Icons.Outlined.BorderStyle }
        put(R.string.theme_popup_elevation_title) { Icons.Outlined.Layers }
        put(R.string.theme_popup_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.theme_popup_height_title) { Icons.Outlined.Height }
        put(R.string.theme_popup_selected_title) { Icons.Outlined.Highlight }
        put(R.string.theme_popup_selected_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_menu_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_toolbar_section_title) { Icons.Outlined.Widgets }
        put(R.string.theme_strip_sheet_title) { Icons.Outlined.Subtitles }
        put(R.string.theme_pick_start_title) { Icons.Outlined.TouchApp }
        put(R.string.theme_toolbar_background_title) { Icons.Outlined.FormatColorFill }
        put(R.string.theme_tool_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_tool_icons_title) { Icons.Outlined.Widgets }
        put(R.string.theme_tool_circles_title) { Icons.Outlined.Circle }
        put(R.string.theme_tool_circle_active_title) { Icons.Outlined.RadioButtonChecked }
        put(R.string.theme_tool_circle_active_icon_title) { Icons.Outlined.Widgets }
        put(R.string.theme_tool_circle_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.theme_tool_border_title) { Icons.Outlined.BorderStyle }
        put(R.string.theme_tool_border_width_title) { Icons.Outlined.LineWeight }
        put(R.string.theme_tool_elevation_title) { Icons.Outlined.Layers }
        put(R.string.theme_tool_width_title) { Icons.Outlined.Straighten }
        put(R.string.theme_toolbar_height_title) { Icons.Outlined.Height }
        put(R.string.theme_cards_title) { Icons.Outlined.Dashboard }
        put(R.string.theme_card_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_card_elevation_title) { Icons.Outlined.Layers }
        put(R.string.theme_suggestion_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_other_suggestions_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_other_suggestions_size_title) { Icons.Outlined.FormatSize }
        put(R.string.theme_secondary_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_divider_title) { Icons.Outlined.HorizontalRule }
        put(R.string.theme_chip_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_chip_radius_title) { Icons.Outlined.RoundedCorner }
        put(R.string.theme_chip_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_chip_active_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.theme_chip_active_text_title) { Icons.Outlined.FormatColorText }
        put(R.string.theme_chip_border_title) { Icons.Outlined.BorderStyle }
        put(R.string.theme_animation_speed_title) { Icons.Outlined.Speed }
        put(R.string.theme_effect_color_title) { Icons.Outlined.Palette }
        put(R.string.theme_effect_color_custom_label) { Icons.Outlined.Colorize }
        put(R.string.theme_effect_intensity_title) { Icons.Outlined.Bolt }
        put(R.string.theme_effect_size_title) { Icons.Outlined.PhotoSizeSelectSmall }
        put(R.string.theme_effect_speed_title) { Icons.Outlined.Speed }
        put(R.string.theme_effect_spread_title) { Icons.Outlined.Grain }
        put(R.string.theme_effect_gravity_title) { Icons.Outlined.South }
        put(R.string.theme_effect_duration_title) { Icons.Outlined.Timer }
        put(R.string.theme_key_override_shape_title) { Icons.Outlined.Category }
        put(R.string.theme_key_override_label_size_title) { Icons.Outlined.FormatSize }
        put(R.string.theme_decal_x_title) { Icons.Outlined.SwapHoriz }
        put(R.string.theme_decal_y_title) { Icons.Outlined.SwapVert }
        put(R.string.theme_decal_size_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.theme_decal_rotation_title) { Icons.Outlined.Rotate90DegreesCw }
        put(R.string.theme_gradient_angle_title) { Icons.Outlined.Architecture }
        put(R.string.theme_gradient_color_title) { Icons.Outlined.Palette }
        put(R.string.import_sticker_editor_brush_size_label) { Icons.Outlined.Brush }
        put(R.string.import_sticker_editor_border_width_label) { Icons.Outlined.LineWeight }
        put(R.string.import_sticker_editor_cutout_action) { Icons.Outlined.ContentCut }
        put(R.string.import_sticker_editor_upscale_action) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.import_sticker_pack_rename_subtitle) { Icons.Outlined.DriveFileRenameOutline }
        put(R.string.import_signal_added_title) { Icons.Outlined.CheckCircle }
        put(R.string.import_telegram_added_title) { Icons.Outlined.CheckCircle }

        // ---- Layout editor ----
        put(R.string.layout_editor_name_title) { Icons.Outlined.DriveFileRenameOutline }
        put(R.string.layout_editor_language_title) { Icons.Outlined.Language }
        put(R.string.layout_editor_font_title) { Icons.Outlined.TextFields }
        put(R.string.layout_editor_font_scale_label) { Icons.Outlined.FormatSize }
        put(R.string.layout_editor_layer_theme_title) { Icons.Outlined.Palette }
        put(R.string.layout_editor_panel_theme_title) { Icons.Outlined.Palette }
        put(R.string.layout_editor_reorder_rows_title) { Icons.Outlined.Reorder }
        put(R.string.layout_editor_reorder_keys_title) { Icons.Outlined.SwapHoriz }
        put(R.string.layout_editor_row_height_label) { Icons.Outlined.Height }
        put(R.string.layout_editor_key_width_label) { Icons.Outlined.Straighten }
        put(R.string.layout_editor_key_label_scale_label) { Icons.Outlined.FormatSize }
        put(R.string.layout_editor_key_row_span_hint) { Icons.Outlined.UnfoldMore }
        put(R.string.layout_editor_action_alternates_label) { Icons.Outlined.MoreHoriz }
        put(R.string.layout_editor_icon_beside_label_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.layout_editor_kana_variant_title) { Icons.Outlined.Spellcheck }
        put(R.string.layout_editor_repeat_hold_title) { Icons.Outlined.Repeat }
        put(R.string.layout_editor_hold_action_label) { Icons.Outlined.TouchApp }
        put(R.string.layout_editor_action_position_title) { Icons.Outlined.SwapVert }
        put(R.string.panel_name_emoji) { Icons.Outlined.EmojiEmotions }
        put(R.string.panel_name_gif) { SymbolIcons.GifBox }
        put(R.string.panel_name_sticker) { SymbolIcons.Sticker }
        put(R.string.panel_name_clipboard) { Icons.Outlined.ContentPaste }
        put(R.string.panel_name_text_edit) { Icons.Outlined.EditNote }
        put(R.string.panel_name_trackpad) { SymbolIcons.TrackpadInput }
        put(R.string.panel_name_numpad) { Icons.Outlined.Dialpad }
        put(R.string.panel_name_handwriting) { Icons.Outlined.Draw }

        // ---- Layout & size: per screen and per orientation ----
        put(SettingsR.string.core_settings_screen_variant_landscape_label) { Icons.Outlined.StayCurrentLandscape }
        put(SettingsR.string.core_settings_screen_variant_portrait_unfolded_label) { Icons.Outlined.DevicesFold }
        put(SettingsR.string.core_settings_screen_variant_landscape_unfolded_label) { Icons.Outlined.DevicesFold }
        put(R.string.layout_one_handed_width_title) { Icons.Outlined.Straighten }
        put(R.string.layout_one_handed_height_title) { Icons.Outlined.Height }
        put(R.string.layout_one_handed_side_title) { Icons.Outlined.SwapHoriz }
        put(R.string.layout_symbols_return_chars_title) { Icons.AutoMirrored.Outlined.KeyboardReturn }
        put(R.string.home_reset_pinned_tools_title) { Icons.Outlined.Restore }
        put(R.string.fonts_script_header) { Icons.Outlined.TextFields }
        put(R.string.languages_keyman_rules_title_for) { Icons.AutoMirrored.Outlined.Rule }
        put(R.string.languages_cjk_flex_import_title) { Icons.Outlined.FileOpen }

        // ---- Typing, key press, modes ----
        put(R.string.typing_hug_punctuation_title) { Icons.Outlined.Compress }
        put(R.string.typing_language_punctuation_spacing_title) { Icons.Outlined.Translate }
        put(R.string.typing_suggestion_overflow_title) { Icons.Outlined.ContentCut }
        put(R.string.typing_space_short_swipe_title) { Icons.Outlined.Swipe }
        put(R.string.typing_space_long_swipe_title) { Icons.Outlined.SwipeRightAlt }
        put(R.string.hardware_shortcuts_leader_title) { Icons.Outlined.KeyboardCommandKey }
        put(R.string.hardware_sound_style_title) { Icons.Outlined.LibraryMusic }
        put(R.string.keypress_haptic_style_title) { Icons.Outlined.Vibration }
        put(R.string.keypress_currency_keys_title) { Icons.Outlined.CurrencyExchange }
        put(R.string.keypress_hold_letters_title) { Icons.Outlined.Abc }
        put(R.string.modes_use_title) { Icons.Outlined.PowerSettingsNew }
        put(R.string.modes_theme_title) { Icons.Outlined.Palette }
        put(R.string.modes_field_types_title) { Icons.AutoMirrored.Outlined.Input }
        put(R.string.modes_field_hints_title) { Icons.Outlined.Search }
        put(R.string.modes_priority_title) { Icons.Outlined.TouchApp }
        put(R.string.rows_snippet_propagate_case_label) { Icons.Outlined.KeyboardCapslock }
        put(R.string.rows_snippet_confirm_label) { Icons.Outlined.HelpOutline }
        put(R.string.rows_snippet_immediate_label) { Icons.Outlined.Bolt }
        put(R.string.rows_snippet_inside_words_label) { Icons.Outlined.TextFields }
        put(R.string.expander_folder_enabled_title) { Icons.Outlined.PowerSettingsNew }

        // ---- Tools, plugins, backup, photos, privacy ----
        put(R.string.selection_macros_ai_manage_title) { Icons.Outlined.AutoAwesome }
        put(R.string.toolai_ai_local_context_title) { Icons.Outlined.Memory }
        put(R.string.tooldetail_plugins_manage_title) { Icons.Outlined.Extension }
        put(R.string.tooldetail_symbols_clear_title) { Icons.Outlined.DeleteSweep }
        put(CommonR.string.common_reset_defaults) { Icons.Outlined.Restore }
        put(R.string.plugins_allow_title) { Icons.Outlined.Extension }
        put(R.string.plugins_install_file_title) { Icons.Outlined.FileOpen }
        put(R.string.plugin_ide_entry_title) { Icons.Outlined.Code }
        put(R.string.plugin_ide_edit_title) { Icons.Outlined.EditNote }
        put(R.string.plugin_ide_new_title) { Icons.Outlined.Add }
        put(R.string.plugin_ide_import_title) { Icons.Outlined.FileOpen }
        put(R.string.vocab_review_ahead_action) { Icons.Outlined.FastForward }
        put(R.string.backup_section_swipe_label) { Icons.Outlined.Gesture }
        put(R.string.backup_webdav_preset_title) { Icons.Outlined.Dns }
        put(R.string.backup_s3_preset_title) { Icons.Outlined.Inventory2 }
        put(R.string.backup_git_provider_title) { Icons.Outlined.Code }
        put(R.string.backup_imap_security_title) { Icons.Outlined.Lock }
        put(R.string.backup_drive_space_title) { Icons.Outlined.Folder }
        put(R.string.servers_repo_data) { Icons.Outlined.Storage }
        put(R.string.servers_repo_addons) { Icons.Outlined.Extension }
        put(R.string.servers_repo_sounds) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.servers_repo_espanso_hub) { Icons.AutoMirrored.Outlined.TextSnippet }
        put(R.string.photo_rotation_source_saved_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.photo_rotation_source_online_title) { Icons.Outlined.CloudDownload }
        put(R.string.photo_rotation_shuffle_title) { Icons.Outlined.Shuffle }
        put(R.string.photo_rotation_delete_downloads_title) { Icons.Outlined.DeleteSweep }
        // The network activity row carries the screen's own glyph.
        put(R.string.netlog_title) { Icons.Outlined.NetworkCheck }
        put(R.string.netlog_keep_title) { Icons.Outlined.History }
        put(R.string.netlog_keyboard_dot_title) { Icons.Outlined.FiberManualRecord }

        // ---- Vocabulary card fields ----
        put(R.string.tooldetail_vocab_field_ipa_title) { Icons.Outlined.RecordVoiceOver }
        put(R.string.tooldetail_vocab_field_respelling_title) { Icons.Outlined.Spellcheck }
        put(R.string.tooldetail_vocab_field_examples_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.tooldetail_vocab_field_quotations_title) { Icons.Outlined.FormatQuote }
        put(R.string.tooldetail_vocab_field_synonyms_title) { Icons.Outlined.SwapHoriz }
        put(R.string.tooldetail_vocab_field_antonyms_title) { Icons.AutoMirrored.Outlined.CompareArrows }
        put(R.string.tooldetail_vocab_field_hypernyms_title) { Icons.Outlined.AccountTree }
        put(R.string.tooldetail_vocab_field_family_title) { Icons.Outlined.Hub }
        put(R.string.tooldetail_vocab_field_etymology_title) { Icons.Outlined.History }
        put(R.string.tooldetail_vocab_field_origin_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_vocab_field_root_title) { Icons.Outlined.Park }
        put(R.string.tooldetail_vocab_field_attested_title) { Icons.Outlined.Verified }
        put(R.string.tooldetail_vocab_field_topics_title) { Icons.Outlined.Category }
        put(R.string.tooldetail_vocab_field_tags_title) { Icons.Outlined.Sell }

        // ---- Grammar: the kinds inside each category ----
        put(SettingsR.string.core_settings_grammar_kind_spelling_label) { Icons.Outlined.Spellcheck }
        put(SettingsR.string.core_settings_grammar_kind_typo_label) { Icons.Outlined.Keyboard }
        put(SettingsR.string.core_settings_grammar_kind_grammar_label) { Icons.AutoMirrored.Outlined.Rule }
        put(SettingsR.string.core_settings_grammar_kind_agreement_label) { Icons.Outlined.Handshake }
        put(SettingsR.string.core_settings_grammar_kind_capitalization_label) { Icons.Outlined.KeyboardCapslock }
        put(SettingsR.string.core_settings_grammar_kind_punctuation_label) { Icons.Outlined.FormatQuote }
        put(SettingsR.string.core_settings_grammar_kind_boundary_label) { Icons.Outlined.SpaceBar }
        put(SettingsR.string.core_settings_grammar_kind_malapropism_label) { Icons.Outlined.SwapHoriz }
        put(SettingsR.string.core_settings_grammar_kind_eggcorn_label) { Icons.Outlined.Egg }
        put(SettingsR.string.core_settings_grammar_kind_usage_label) { Icons.AutoMirrored.Outlined.MenuBook }
        put(SettingsR.string.core_settings_grammar_kind_readability_label) { Icons.Outlined.Visibility }
        put(SettingsR.string.core_settings_grammar_kind_redundancy_label) { Icons.Outlined.ContentCopy }
        put(SettingsR.string.core_settings_grammar_kind_repetition_label) { Icons.Outlined.Repeat }
        put(SettingsR.string.core_settings_grammar_kind_word_choice_label) { Icons.Outlined.FindReplace }
        put(SettingsR.string.core_settings_grammar_kind_enhancement_label) { Icons.Outlined.AutoAwesome }
        put(SettingsR.string.core_settings_grammar_kind_style_label) { Icons.Outlined.Brush }
        put(SettingsR.string.core_settings_grammar_kind_miscellaneous_label) { Icons.Outlined.Category }
        put(SettingsR.string.core_settings_grammar_kind_formatting_label) { Icons.AutoMirrored.Outlined.FormatAlignLeft }
        put(SettingsR.string.core_settings_grammar_kind_regionalism_label) { Icons.Outlined.Public }
        put(SettingsR.string.core_settings_grammar_kind_nonstandard_label) { Icons.Outlined.ErrorOutline }

        // ---- Clock tool ----
        put(R.string.clock_open_title) { Icons.Outlined.OpenInNew }
        put(R.string.clock_settings_title) { Icons.Outlined.Settings }
        put(R.string.clock_hour_format_title) { Icons.Outlined.Schedule }
        put(R.string.clock_show_seconds_title) { Icons.Outlined.Timer }
        put(R.string.clock_sort_zones_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.clock_zones_grid_title) { Icons.Outlined.GridView }
        put(R.string.clock_insert_format_title) { Icons.Outlined.TextFields }
        put(R.string.clock_alarm_sound_title) { Icons.Outlined.Alarm }
        put(R.string.clock_timer_sound_title) { Icons.Outlined.Timer }
        put(R.string.clock_channel_sound_title) { Icons.AutoMirrored.Outlined.VolumeUp }
        put(R.string.clock_vibrate_title) { Icons.Outlined.Vibration }
        put(R.string.clock_snooze_title) { Icons.Outlined.Bedtime }
        put(R.string.clock_fade_title) { Icons.AutoMirrored.Outlined.TrendingUp }
        put(R.string.clock_auto_silence_title) { Icons.Outlined.TimerOff }
        put(R.string.clock_auto_snooze_title) { Icons.Outlined.Update }
        put(R.string.clock_upcoming_title) { Icons.Outlined.Event }
        put(R.string.clock_snoozed_notice_title) { Icons.Outlined.Notifications }
        put(R.string.clock_missed_notice_title) { Icons.Outlined.NotificationsActive }
        put(R.string.clock_volume_keys_title) { Icons.AutoMirrored.Outlined.VolumeOff }
        put(R.string.clock_focus_title) { Icons.Outlined.CenterFocusStrong }
        put(R.string.clock_short_break_title) { Icons.Outlined.HourglassEmpty }
        put(R.string.clock_long_break_title) { Icons.Outlined.Weekend }
        put(R.string.clock_long_every_title) { Icons.Outlined.Repeat }
        put(R.string.clock_work_start_title) { Icons.Outlined.WbSunny }
        put(R.string.clock_work_end_title) { Icons.Outlined.WbTwilight }
        put(R.string.clock_history_keep_title) { Icons.Outlined.History }
        put(R.string.clock_grant_exact_title) { Icons.Outlined.Alarm }
        put(R.string.clock_grant_full_title) { Icons.Outlined.Fullscreen }
        put(R.string.clock_grant_notify_title) { Icons.Outlined.Notifications }

        // ---- Notes and to-do ----
        put(R.string.notes_title) { Icons.Outlined.StickyNote2 }
        put(R.string.tooldetail_notes_open_title) { Icons.Outlined.StickyNote2 }
        put(R.string.notes_tags_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.notes_trash_title) { Icons.Outlined.DeleteSweep }
        put(R.string.notes_export_all_action) { Icons.Outlined.FolderZip }
        put(R.string.notes_import_action) { Icons.Outlined.FileOpen }
        put(R.string.notes_empty_trash_action) { Icons.Outlined.DeleteForever }
        put(R.string.tooldetail_notes_sort_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.tooldetail_notes_markdown_title) { Icons.Outlined.FormatBold }
        put(R.string.tooldetail_notes_panel_start_title) { Icons.Outlined.FirstPage }
        put(R.string.tooldetail_notes_trash_days_title) { Icons.Outlined.HourglassBottom }
        put(R.string.tooldetail_notes_save_selection_title) { Icons.Outlined.HighlightAlt }
        put(R.string.tooldetail_notes_clear_title) { Icons.Outlined.DeleteForever }
        put(R.string.tooldetail_todo_list_title) { Icons.Outlined.Checklist }

        // ---- Music, speed test, location ----
        put(R.string.music_text_format_title) { Icons.Outlined.TextFields }
        put(R.string.music_card_style_title) { Icons.Outlined.Image }
        put(R.string.music_open_link_title) { Icons.Outlined.OpenInNew }
        put(R.string.music_clip_seconds_title) { Icons.Outlined.Timer }
        put(R.string.music_now_playing_first_title) { Icons.Outlined.GraphicEq }
        put(R.string.music_keep_history_title) { Icons.Outlined.History }
        put(R.string.music_history_open_title) { Icons.Outlined.LibraryMusic }
        put(R.string.music_history_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.tooldetail_speed_duration_title) { Icons.Outlined.Timer }
        put(R.string.tooldetail_speed_connections_title) { Icons.Outlined.Lan }
        put(R.string.tooldetail_speed_upload_title) { Icons.Outlined.Upload }
        put(R.string.tooldetail_speed_unit_title) { Icons.Outlined.Speed }
        put(R.string.tooldetail_speed_card_style_title) { Icons.Outlined.Palette }
        put(R.string.tooldetail_speed_card_size_title) { Icons.Outlined.AspectRatio }
        put(R.string.tooldetail_speed_card_layout_title) { Icons.Outlined.Dashboard }
        put(R.string.tooldetail_speed_send_mode_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.tooldetail_speed_save_gallery_title) { Icons.Outlined.PhotoLibrary }
        put(R.string.tooldetail_speed_show_ip_title) { Icons.Outlined.Public }
        put(R.string.tooldetail_speed_librespeed_pick_title) { Icons.Outlined.Dns }
        put(R.string.location_format_title) { Icons.Outlined.TextFields }
        put(R.string.location_map_link_title) { Icons.Outlined.Explore }
        put(R.string.location_precision_title) { Icons.Outlined.Adjust }
        put(R.string.location_lookup_address_title) { Icons.Outlined.LocationCity }
        put(R.string.location_locate_on_open_title) { Icons.Outlined.MyLocation }
        put(R.string.location_add_place_title) { Icons.Outlined.PinDrop }
        put(R.string.location_permission_title) { Icons.Outlined.LocationOn }
        put(R.string.location_camera_stamp_title) { Icons.Outlined.PhotoCamera }
        put(R.string.location_camera_caption_title) { Icons.Outlined.Subtitles }

        // ---- Smart home ----
        put(R.string.ha_enabled_title) { Icons.Outlined.PowerSettingsNew }
        put(R.string.ha_favourites_title) { Icons.Outlined.Home }
        put(R.string.ha_open_on_title) { Icons.Outlined.FirstPage }
        put(R.string.ha_areas_tabs_title) { Icons.Outlined.Tab }
        put(R.string.ha_tile_size_title) { Icons.Outlined.AspectRatio }
        put(R.string.ha_confirm_risky_title) { Icons.Outlined.Warning }
        put(R.string.ha_insert_format_title) { Icons.Outlined.TextFields }
        put(R.string.ha_assist_enabled_title) { Icons.Outlined.QuestionAnswer }
        put(R.string.ha_assist_agent_title) { Icons.Outlined.SmartToy }
        put(R.string.ha_assist_language_title) { Icons.Outlined.Language }
        put(R.string.ha_lifetime_title) { Icons.Outlined.Link }
        put(R.string.ha_cache_title) { Icons.Outlined.Storage }
        put(R.string.ha_ai_tools_title) { Icons.Outlined.AutoAwesome }
        put(R.string.ha_smart_chip_title) { Icons.Outlined.Lightbulb }

        // ---- Emoji mixes, added emoji, kaomoji ----
        put(R.string.emojimix_row_title) { Icons.Outlined.Interests }
        put(R.string.emojimix_add_title) { Icons.Outlined.Add }
        put(R.string.emojimix_panel_strip_title) { Icons.Outlined.ViewDay }
        put(R.string.emojimix_typing_title) { Icons.Outlined.Lightbulb }
        put(R.string.emojimix_style_title) { Icons.Outlined.Style }
        put(R.string.emojimix_replace_title) { Icons.Outlined.SwapHoriz }
        put(R.string.emojimix_panel_title) { Icons.Outlined.GridView }
        put(R.string.emojimix_order_title) { Icons.AutoMirrored.Outlined.Sort }
        put(R.string.emojimix_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.emojimix_cell_size_title) { Icons.Outlined.AspectRatio }
        put(R.string.emojimix_auto_refresh_title) { Icons.Outlined.Autorenew }
        put(R.string.emojimix_clear_cache_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_added_add_title) { Icons.Outlined.Add }
        put(R.string.langemoji_emoji_added_import_title) { Icons.Outlined.FileOpen }
        put(R.string.langemoji_emoji_added_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.langemoji_emoji_order_preset_title) { Icons.Outlined.Dashboard }
        put(R.string.langemoji_emoji_categories_reset_title) { Icons.Outlined.RestartAlt }
        put(R.string.langemoji_kaomoji_add_title) { Icons.Outlined.Add }
        put(R.string.langemoji_kaomoji_new_tag_title) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.langemoji_kaomoji_rail_add) { Icons.Outlined.Add }
        put(R.string.langemoji_kaomoji_rail_edit) { Icons.Outlined.Edit }
        put(R.string.langemoji_kaomoji_rail_search) { Icons.Outlined.Search }
        put(R.string.langemoji_kaomoji_rail_tags) { Icons.AutoMirrored.Outlined.Label }
        put(R.string.typing_suggestion_pages_emoji_title) { Icons.Outlined.EmojiEmotions }
        put(R.string.typing_suggestion_pages_fancy_title) { Icons.Outlined.FontDownload }

        // ---- Text tools: repeat, word steps, find, PIN, QR, scanners, media ----
        put(R.string.tooldetail_repeat_count_title) { Icons.Outlined.Repeat }
        put(R.string.tooldetail_repeat_mode_title) { Icons.Outlined.Tune }
        put(R.string.tooldetail_repeat_separator_title) { Icons.Outlined.HorizontalRule }
        put(R.string.tooldetail_repeat_numbered_title) { Icons.Outlined.FormatListNumbered }
        put(R.string.tooldetail_repeat_alternate_title) { Icons.Outlined.TextFormat }
        put(R.string.tooldetail_repeat_max_chars_title) { Icons.Outlined.Straighten }
        put(R.string.tooldetail_repeat_chip_insert_title) { Icons.Outlined.SmartButton }
        put(R.string.tooldetail_repeat_recents_title) { Icons.Outlined.History }
        put(R.string.tooldetail_repeat_recents_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.tooldetail_word_steps_title) { Icons.Outlined.KeyboardDoubleArrowRight }
        put(R.string.tooldetail_word_steps_capitals_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.tooldetail_word_steps_digits_title) { Icons.Outlined.Numbers }
        put(R.string.tooldetail_find_history_title) { Icons.Outlined.History }
        put(R.string.tooldetail_find_history_clear_title) { Icons.Outlined.DeleteSweep }
        put(R.string.tooldetail_pin_length_title) { Icons.Outlined.Pin }
        put(R.string.tooldetail_pin_avoid_weak_title) { Icons.Outlined.Shield }
        put(R.string.tooldetail_qr_gen_ecc_header) { Icons.Outlined.Healing }
        put(R.string.tooldetail_qr_scan_zxing_title) { Icons.Outlined.QrCodeScanner }
        put(R.string.tooldetail_doc_scan_builtin_title) { Icons.Outlined.DocumentScanner }
        put(R.string.tooldetail_media_filter_header) { Icons.Outlined.FilterAlt }
        put(R.string.tooldetail_image_search_moved_title) { Icons.Outlined.TravelExplore }
        put(R.string.tooldetail_sticker_suggest_magnify_title) { Icons.Outlined.ZoomIn }
        put(R.string.tooldetail_moon_arabic_title) { Icons.Outlined.Translate }
        put(R.string.tooldetail_dictionary_packs_title) { Icons.Outlined.AutoStories }
        put(R.string.toolai_typing_default_test_title) { Icons.Outlined.Speed }

        // ---- Calendar ----
        put(R.string.tooldetail_calendar_save_mode_title) { Icons.Outlined.EditCalendar }
        put(R.string.tooldetail_calendar_default_calendar_title) { Icons.Outlined.CalendarMonth }
        put(R.string.tooldetail_calendar_default_length_title) { Icons.Outlined.HourglassEmpty }
        put(R.string.tooldetail_calendar_default_reminder_title) { Icons.Outlined.NotificationsActive }
        put(R.string.toolai_ai_builtin_calendar_mode_title) { Icons.Outlined.EditCalendar }

        // ---- AI ----
        put(R.string.settings_assistant_title) { Icons.Outlined.Assistant }
        put(R.string.toolai_ai_in_use_title) { Icons.Outlined.CheckCircle }
        put(R.string.toolai_ai_builtin_title) { Icons.Outlined.Handyman }
        put(R.string.toolai_ai_mcp_import_title) { Icons.Outlined.DataObject }
        put(R.string.toolai_ai_mcp_transport_title) { Icons.Outlined.Lan }
        put(R.string.toolai_reply_catch_shots_title) { Icons.Outlined.Screenshot }
        put(R.string.toolai_reply_tones_nav_title) { Icons.Outlined.Mood }

        // ---- Sticker generator ----
        put(R.string.stickergen_seed_lock_title) { Icons.Outlined.Lock }
        put(R.string.stickergen_cutout_title) { Icons.Outlined.ContentCut }
        put(R.string.stickergen_safety_title) { Icons.Outlined.Shield }

        // ---- Look up, selection menu ----
        put(R.string.lookup_settings_selection_title) { Icons.Outlined.HighlightAlt }
        put(R.string.lookup_settings_tap_title) { Icons.Outlined.TouchApp }
        put(R.string.lookup_settings_style_title) { Icons.Outlined.ViewAgenda }
        put(R.string.lookup_settings_anki_title) { Icons.Outlined.School }
        put(R.string.selection_macros_icons_title) { Icons.Outlined.Image }
        put(R.string.selection_macros_zones_sort_title) { Icons.AutoMirrored.Outlined.Sort }

        // ---- Themes, panels, gallery ----
        put(R.string.theme_assets_title) { Icons.Outlined.PhotoSizeSelectLarge }
        put(R.string.theme_glass_title) { Icons.Outlined.Opacity }
        put(R.string.theme_glass_window_blur_title) { Icons.Outlined.BlurOn }
        put(R.string.theme_glass_wallpaper_permission_title) { Icons.Outlined.Wallpaper }
        put(R.string.theme_glass_accent_board_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_glass_blur_board_title) { Icons.Outlined.Layers }
        put(R.string.theme_match_letter_keys_title) { Icons.Outlined.FormatColorFill }
        put(R.string.panel_layout_media_tabs_idle_title) { SymbolIcons.GifBox }
        put(R.string.gallery_folders_title) { Icons.Outlined.Folder }
        put(R.string.floating_button_category_add_title) { Icons.Outlined.Add }

        // ---- Typing, key press, layouts, languages ----
        put(R.string.typing_recent_word_bar_title) { Icons.Outlined.History }
        put(R.string.typing_shift_recase_caret_title) { SymbolIcons.Shift }
        put(R.string.typing_shift_recase_inside_title) { SymbolIcons.Shift }
        put(R.string.typing_smart_keep_text_title) { Icons.AutoMirrored.Outlined.ShortText }
        put(R.string.typing_glide_shape_seeding_title) { Icons.Outlined.Gesture }
        put(R.string.typing_symbol_chord_row_title) { Icons.Outlined.Tag }
        put(R.string.typing_key_swipe_row_title) { Icons.Outlined.Swipe }
        put(R.string.typing_finger_swipe_row_title) { Icons.Outlined.SwipeVertical }
        put(R.string.keypress_word_stops_punctuation_title) { Icons.Outlined.FormatQuote }
        put(R.string.keypress_word_stops_capitals_title) { Icons.Outlined.KeyboardCapslock }
        put(R.string.keypress_word_stops_digits_title) { Icons.Outlined.Numbers }
        put(R.string.keypress_no_accents_title) { Icons.Outlined.Block }
        put(R.string.appearance_toolbar_hold_delay_title) { Icons.Outlined.Timer }
        put(R.string.layout_editor_bottom_row_laid_out_title) { Icons.Outlined.PushPin }
        put(R.string.layout_editor_mode_row_title) { Icons.Outlined.Tune }
        put(R.string.layout_editor_ime_row_title) { Icons.Outlined.Keyboard }
        put(R.string.layout_editor_popup_preview_title) { Icons.Outlined.Preview }
        put(R.string.layout_bottom_row_keys_title) { Icons.Outlined.SpaceBar }
        put(R.string.languages_picker_hidden_title) { Icons.Outlined.VisibilityOff }
        put(R.string.languages_vietnamese_strict_tones_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_vietnamese_restore_marks_title) { Icons.Outlined.AutoFixHigh }
        put(R.string.languages_bengali_vowel_twice_title) { Icons.Outlined.TouchApp }
        put(R.string.languages_cjk_traditional_button_title) { Icons.Outlined.Translate }
        put(R.string.languages_cjk_reading_in_field_title) { Icons.Outlined.TextFields }
        put(R.string.languages_cjk_reading_on_switch_title) { Icons.Outlined.Language }
        put(R.string.languages_avro_dictionary_title) { SymbolIcons.Dictionary }
        put(R.string.languages_khipro_specs_title) { Icons.AutoMirrored.Outlined.Rule }
        put(R.string.languages_khipro_specs_bundled_title) { Icons.Outlined.Inventory2 }
        put(R.string.languages_rime_method_pinyin) { Icons.Outlined.Abc }
        put(R.string.languages_rime_method_t9) { Icons.Outlined.Dialpad }
        put(R.string.languages_rime_method_zhuyin) { Icons.Outlined.FontDownload }
        put(R.string.languages_rime_method_cangjie) { Icons.Outlined.GridOn }
        put(R.string.languages_rime_method_stroke) { Icons.Outlined.Draw }
        put(R.string.languages_rime_method_jyutping) { Icons.Outlined.RecordVoiceOver }
        put(R.string.languages_voikko_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_hunspell_title) { Icons.Outlined.Spellcheck }
        put(R.string.languages_speller_title) { Icons.Outlined.Spellcheck }

        // ---- Backup, privacy, data, sending ----
        put(R.string.backup_frequent_encrypt_title) { Icons.Outlined.Lock }
        put(R.string.backup_sync_paired_title) { Icons.Outlined.Devices }
        put(R.string.backup_sync_wifi_only_title) { Icons.Outlined.Wifi }
        put(R.string.backup_sync_keep_theme_title) { Icons.Outlined.Palette }
        put(R.string.clipboard_clean_links_title) { Icons.Outlined.LinkOff }
        put(R.string.kdeconnect_codes_title) { Icons.Outlined.Password }
        put(R.string.privacy_native_downloads_title) { Icons.Outlined.Download }
        put(R.string.privacy_qualcomm_licence_title) { Icons.Outlined.Memory }
        put(R.string.privacy_permissions_location_title) { Icons.Outlined.LocationOn }
        put(R.string.privacy_permissions_nearby_title) { Icons.Outlined.Bluetooth }
        put(R.string.privacy_permissions_calendar_write_title) { Icons.Outlined.EditCalendar }
        put(R.string.datasaver_music_title) { Icons.Outlined.MusicNote }
        put(R.string.datasaver_speed_test_title) { Icons.Outlined.Speed }
        put(R.string.datasaver_send_title) { Icons.AutoMirrored.Outlined.Send }
        put(R.string.send_instance_check_all) { Icons.Outlined.NetworkCheck }
        put(R.string.send_history_clear_received) { Icons.Outlined.DeleteSweep }

        // ---- App lock targets named after their screens ----
        put(R.string.home_backup_title) { Icons.Outlined.Save }
        put(R.string.home_privacy_title) { Icons.Outlined.Security }
        put(R.string.home_screen_ai_chat_title) { Icons.AutoMirrored.Outlined.Chat }
        put(R.string.home_screen_ai_history_title) { Icons.Outlined.History }
        put(R.string.home_screen_blacklist_title) { Icons.Outlined.VisibilityOff }
        put(R.string.home_screen_debug_log_title) { Icons.AutoMirrored.Outlined.Article }
        put(R.string.home_screen_dictionary_title) { SymbolIcons.Dictionary }

        // ---- Stepper, permission and icon-picker rows ----
        put(R.string.tooldetail_repeat_chip_after_title) { Icons.Outlined.SmartButton }
        put(R.string.typing_smart_repeat_after_title) { Icons.Outlined.SmartButton }
        put(R.string.privacy_permissions_exact_alarms_title) { Icons.Outlined.Alarm }
        put(R.string.privacy_permissions_full_screen_title) { Icons.Outlined.Fullscreen }
        put(R.string.privacy_permissions_clock_ring_title) { Icons.Outlined.NotificationsActive }
        put(R.string.layout_editor_icon_field_label) { Icons.Outlined.Image }
        put(R.string.layout_editor_icon_hint_field_label) { Icons.Outlined.Lightbulb }

        // ---- Frosted glass sliders ----
        put(R.string.theme_glass_refraction_title) { Icons.Outlined.Waves }
        put(R.string.theme_glass_dispersion_title) { Icons.Outlined.Gradient }
        put(R.string.theme_glass_blur_strength_title) { Icons.Outlined.BlurOn }
        put(R.string.theme_glass_fallback_title) { Icons.Outlined.Opacity }
        put(R.string.theme_glass_backdrop_blur_title) { Icons.Outlined.BlurOn }
        put(R.string.theme_glass_saturation_title) { Icons.Outlined.InvertColors }
        put(R.string.theme_glass_brightness_title) { Icons.Outlined.LightMode }
        put(R.string.theme_glass_rim_title) { Icons.Outlined.BorderStyle }
        put(R.string.theme_glass_rim_color_title) { Icons.Outlined.ColorLens }
        put(R.string.theme_glass_grain_title) { Icons.Outlined.Grain }
    }

    operator fun get(@StringRes id: Int): ImageVector? = map[id]?.invoke()
}
