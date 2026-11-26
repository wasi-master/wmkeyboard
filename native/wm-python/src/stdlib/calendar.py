"""calendar: month and year calendars as numbers, text and HTML, and the
date arithmetic behind them. Names are English."""

import datetime
import sys

__all__ = [
    "IllegalMonthError", "IllegalWeekdayError", "setfirstweekday", "firstweekday", "isleap", "leapdays", "weekday",
    "monthrange", "monthcalendar", "prmonth", "month", "prcal", "calendar", "timegm", "month_name", "month_abbr",
    "day_name", "day_abbr", "Calendar", "TextCalendar", "HTMLCalendar", "LocaleTextCalendar", "LocaleHTMLCalendar",
    "weekheader", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
]

error = ValueError

MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY = range(7)
(JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER) = range(1, 13)
EPOCH = 1970
_EPOCH_ORD = datetime.date(EPOCH, 1, 1).toordinal()

mdays = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

day_name = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
day_abbr = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
month_name = ["", "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
month_abbr = ["", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


class IllegalMonthError(ValueError):
    def __init__(self, month):
        self.month = month

    def __str__(self):
        return f"bad month number {self.month}; must be 1-12"


class IllegalWeekdayError(ValueError):
    def __init__(self, weekday):
        self.weekday = weekday

    def __str__(self):
        return f"bad weekday number {self.weekday}; must be 0 (Monday) to 6 (Sunday)"


def isleap(year):
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)


def leapdays(y1, y2):
    y1 -= 1
    y2 -= 1
    return (y2 // 4 - y1 // 4) - (y2 // 100 - y1 // 100) + (y2 // 400 - y1 // 400)


def weekday(year, month, day):
    if not datetime.MINYEAR <= year <= datetime.MAXYEAR:
        year = 2000 + year % 400
    return datetime.date(year, month, day).weekday()


def _validate_month(month):
    if not 1 <= month <= 12:
        raise IllegalMonthError(month)


def monthrange(year, month):
    _validate_month(month)
    day1 = weekday(year, month, 1)
    ndays = mdays[month] + (month == FEBRUARY and isleap(year))
    return day1, ndays


def _monthlen(year, month):
    return mdays[month] + (month == FEBRUARY and isleap(year))


def _prevmonth(year, month):
    return (year - 1, 12) if month == 1 else (year, month - 1)


def _nextmonth(year, month):
    return (year + 1, 1) if month == 12 else (year, month + 1)


class Calendar:
    def __init__(self, firstweekday=0):
        self.firstweekday = firstweekday

    def getfirstweekday(self):
        return self._firstweekday % 7

    def setfirstweekday(self, firstweekday):
        self._firstweekday = firstweekday

    firstweekday = property(getfirstweekday, setfirstweekday)

    def iterweekdays(self):
        for i in range(self.firstweekday, self.firstweekday + 7):
            yield i % 7

    def itermonthdates(self, year, month):
        for y, m, d in self.itermonthdays3(year, month):
            yield datetime.date(y, m, d)

    def itermonthdays(self, year, month):
        day1, ndays = monthrange(year, month)
        days_before = (day1 - self.firstweekday) % 7
        for _ in range(days_before):
            yield 0
        yield from range(1, ndays + 1)
        days_after = (self.firstweekday - day1 - ndays) % 7
        for _ in range(days_after):
            yield 0

    def itermonthdays2(self, year, month):
        for i, d in enumerate(self.itermonthdays(year, month), self.firstweekday):
            yield d, i % 7

    def itermonthdays3(self, year, month):
        day1, ndays = monthrange(year, month)
        days_before = (day1 - self.firstweekday) % 7
        days_after = (self.firstweekday - day1 - ndays) % 7
        y, m = _prevmonth(year, month)
        end = _monthlen(y, m) + 1
        for d in range(end - days_before, end):
            yield y, m, d
        for d in range(1, ndays + 1):
            yield year, month, d
        y, m = _nextmonth(year, month)
        for d in range(1, days_after + 1):
            yield y, m, d

    def itermonthdays4(self, year, month):
        for i, (y, m, d) in enumerate(self.itermonthdays3(year, month)):
            yield y, m, d, (self.firstweekday + i) % 7

    def monthdatescalendar(self, year, month):
        dates = list(self.itermonthdates(year, month))
        return [dates[i : i + 7] for i in range(0, len(dates), 7)]

    def monthdays2calendar(self, year, month):
        days = list(self.itermonthdays2(year, month))
        return [days[i : i + 7] for i in range(0, len(days), 7)]

    def monthdayscalendar(self, year, month):
        days = list(self.itermonthdays(year, month))
        return [days[i : i + 7] for i in range(0, len(days), 7)]

    def yeardatescalendar(self, year, width=3):
        months = [self.monthdatescalendar(year, m) for m in range(JANUARY, JANUARY + 12)]
        return [months[i : i + width] for i in range(0, len(months), width)]

    def yeardays2calendar(self, year, width=3):
        months = [self.monthdays2calendar(year, m) for m in range(JANUARY, JANUARY + 12)]
        return [months[i : i + width] for i in range(0, len(months), width)]

    def yeardayscalendar(self, year, width=3):
        months = [self.monthdayscalendar(year, m) for m in range(JANUARY, JANUARY + 12)]
        return [months[i : i + width] for i in range(0, len(months), width)]


class TextCalendar(Calendar):
    def prweek(self, theweek, width):
        print(self.formatweek(theweek, width), end="")

    def formatday(self, day, weekday, width):
        s = "" if day == 0 else f"{day:2}"
        return s.center(width)

    def formatweek(self, theweek, width):
        return " ".join(self.formatday(d, wd, width) for (d, wd) in theweek)

    def formatweekday(self, day, width):
        names = day_name if width >= 9 else day_abbr
        return names[day][:width].center(width)

    def formatweekheader(self, width):
        return " ".join(self.formatweekday(i, width) for i in self.iterweekdays())

    def formatmonthname(self, theyear, themonth, width, withyear=True):
        s = month_name[themonth]
        if withyear:
            s = f"{s} {theyear!r}"
        return s.center(width)

    def prmonth(self, theyear, themonth, w=0, l=0):
        print(self.formatmonth(theyear, themonth, w, l), end="")

    def formatmonth(self, theyear, themonth, w=0, l=0):
        w = max(2, w)
        l = max(1, l)
        s = self.formatmonthname(theyear, themonth, 7 * (w + 1) - 1)
        s = s.rstrip()
        s += "\n" * l
        s += self.formatweekheader(w).rstrip()
        s += "\n" * l
        for week in self.monthdays2calendar(theyear, themonth):
            s += self.formatweek(week, w).rstrip()
            s += "\n" * l
        return s

    def formatyear(self, theyear, w=2, l=1, c=6, m=3):
        w = max(2, w)
        l = max(1, l)
        c = max(2, c)
        colwidth = (w + 1) * 7 - 1
        v = []
        v.append(repr(theyear).center(colwidth * m + c * (m - 1)).rstrip())
        v.append("\n" * l)
        header = self.formatweekheader(w)
        for i, row in enumerate(self.yeardays2calendar(theyear, m)):
            months = range(m * i + 1, min(m * (i + 1) + 1, 13))
            v.append("\n" * l)
            names = (self.formatmonthname(theyear, k, colwidth, False) for k in months)
            v.append(formatstring(names, colwidth, c).rstrip())
            v.append("\n" * l)
            headers = (header for k in months)
            v.append(formatstring(headers, colwidth, c).rstrip())
            v.append("\n" * l)
            height = max(len(cal) for cal in row)
            for j in range(height):
                weeks = []
                for cal in row:
                    weeks.append("" if j >= len(cal) else self.formatweek(cal[j], w))
                v.append(formatstring(weeks, colwidth, c).rstrip())
                v.append("\n" * l)
        return "".join(v)

    def pryear(self, theyear, w=0, l=0, c=6, m=3):
        print(self.formatyear(theyear, w, l, c, m), end="")


class HTMLCalendar(Calendar):
    cssclasses = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
    cssclasses_weekday_head = cssclasses
    cssclass_noday = "noday"
    cssclass_month_head = "month"
    cssclass_month = "month"
    cssclass_year_head = "year"
    cssclass_year = "year"

    def formatday(self, day, weekday):
        if day == 0:
            return f'<td class="{self.cssclass_noday}">&nbsp;</td>'
        return f'<td class="{self.cssclasses[weekday]}">{day}</td>'

    def formatweek(self, theweek):
        s = "".join(self.formatday(d, wd) for (d, wd) in theweek)
        return f"<tr>{s}</tr>"

    def formatweekday(self, day):
        return f'<th class="{self.cssclasses_weekday_head[day]}">{day_abbr[day]}</th>'

    def formatweekheader(self):
        s = "".join(self.formatweekday(i) for i in self.iterweekdays())
        return f"<tr>{s}</tr>"

    def formatmonthname(self, theyear, themonth, withyear=True):
        s = f"{month_name[themonth]} {theyear}" if withyear else f"{month_name[themonth]}"
        return f'<tr><th colspan="7" class="{self.cssclass_month_head}">{s}</th></tr>'

    def formatmonth(self, theyear, themonth, withyear=True):
        v = []
        a = v.append
        a(f'<table border="0" cellpadding="0" cellspacing="0" class="{self.cssclass_month}">')
        a("\n")
        a(self.formatmonthname(theyear, themonth, withyear=withyear))
        a("\n")
        a(self.formatweekheader())
        a("\n")
        for week in self.monthdays2calendar(theyear, themonth):
            a(self.formatweek(week))
            a("\n")
        a("</table>")
        a("\n")
        return "".join(v)

    def formatyear(self, theyear, width=3):
        v = []
        a = v.append
        width = max(width, 1)
        a(f'<table border="0" cellpadding="0" cellspacing="0" class="{self.cssclass_year}">')
        a("\n")
        a(f'<tr><th colspan="{width}" class="{self.cssclass_year_head}">{theyear}</th></tr>')
        for i in range(JANUARY, JANUARY + 12, width):
            months = range(i, min(i + width, 13))
            a("<tr>")
            for m in months:
                a("<td>")
                a(self.formatmonth(theyear, m, withyear=False))
                a("</td>")
            a("</tr>")
        a("</table>")
        return "".join(v)

    def formatyearpage(self, theyear, width=3, css="calendar.css", encoding=None):
        if encoding is None:
            encoding = "utf-8"
        v = []
        a = v.append
        a(f'<?xml version="1.0" encoding="{encoding}"?>\n')
        a('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">\n')
        a("<html>\n")
        a("<head>\n")
        a(f'<meta http-equiv="Content-Type" content="text/html; charset={encoding}" />\n')
        if css is not None:
            a(f'<link rel="stylesheet" type="text/css" href="{css}" />\n')
        a(f"<title>Calendar for {theyear}</title>\n")
        a("</head>\n")
        a("<body>\n")
        a(self.formatyear(theyear, width))
        a("</body>\n")
        a("</html>\n")
        return "".join(v).encode(encoding, "xmlcharrefreplace")


class LocaleTextCalendar(TextCalendar):
    def __init__(self, firstweekday=0, locale=None):
        super().__init__(firstweekday)
        self.locale = locale


class LocaleHTMLCalendar(HTMLCalendar):
    def __init__(self, firstweekday=0, locale=None):
        super().__init__(firstweekday)
        self.locale = locale


c = TextCalendar()

firstweekday = c.getfirstweekday


def setfirstweekday(firstweekday):
    if not MONDAY <= firstweekday <= SUNDAY:
        raise IllegalWeekdayError(firstweekday)
    c.firstweekday = firstweekday


monthcalendar = c.monthdayscalendar
prweek = c.prweek
week = c.formatweek
weekheader = c.formatweekheader
prmonth = c.prmonth
month = c.formatmonth
calendar = c.formatyear
prcal = c.pryear

_colwidth = 7 * 3 - 1
_spacing = 6


def format(cols, colwidth=_colwidth, spacing=_spacing):
    print(formatstring(cols, colwidth, spacing))


def formatstring(cols, colwidth=_colwidth, spacing=_spacing):
    spacing *= " "
    return spacing.join(c.center(colwidth) for c in cols)


def timegm(tuple):
    year, month, day, hour, minute, second = tuple[:6]
    days = datetime.date(year, month, 1).toordinal() - _EPOCH_ORD + day - 1
    hours = days * 24 + hour
    minutes = hours * 60 + minute
    return minutes * 60 + second
