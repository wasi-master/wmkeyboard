# Weather: today's forecast for a city, from Open-Meteo.
#
# An API 5 plugin that reaches the network. plugin.json declares the
# "network" permission and lists the two hosts it talks to; the keyboard
# refuses every other address. Network is a risky permission, so it is off
# after install: the first request raises PermissionError, the panel shows
# an Allow button, and nothing is sent until the user turns it on.
#
# It also shows a setting from the manifest (units), a timer that runs only
# while the panel is open, a header button, and plugin.state(persist=True),
# which saves the city between sessions.

from wm import plugin, ui, http

GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST = "https://api.open-meteo.com/v1/forecast"

# WMO weather codes, roughly, to words and a Material Symbols icon.
CODES = [
    (0, "Clear", "sunny"),
    (3, "Cloudy", "cloud"),
    (48, "Fog", "foggy"),
    (67, "Rain", "rainy"),
    (77, "Snow", "weather_snowy"),
    (82, "Showers", "rainy"),
    (99, "Thunderstorm", "thunderstorm"),
]

state = plugin.state(city="Dhaka", place="", days=[], now=None, error="", persist=True)


def describe(code):
    for top, words, icon in CODES:
        if code <= top:
            return words, icon
    return "Unknown", "help"


def look_up():
    state.error = ""
    try:
        found = http.get(GEOCODE, params={"name": state.city, "count": 1}).raise_for_status().json()
        if not found.get("results"):
            state.error = "No place called " + state.city
            return
        place = found["results"][0]
        unit = "fahrenheit" if plugin.settings.get("units") == "imperial" else "celsius"
        r = http.get(FORECAST, params={
            "latitude": place["latitude"],
            "longitude": place["longitude"],
            "current": "temperature_2m,weather_code",
            "daily": "temperature_2m_max,temperature_2m_min,weather_code",
            "temperature_unit": unit,
            "timezone": "auto",
        })
        r.raise_for_status()
        data = r.json()
    except PermissionError:
        state.error = "Allow internet access to look up the weather."
        return
    except (ConnectionError, TimeoutError, http.HTTPError) as e:
        state.error = "Could not reach Open-Meteo: " + str(e)
        return
    state.place = place["name"] + ", " + place.get("country", "")
    state.now = data["current"]
    daily = data["daily"]
    state.days = [
        {"date": daily["time"][i], "high": daily["temperature_2m_max"][i],
         "low": daily["temperature_2m_min"][i], "code": daily["weather_code"][i]}
        for i in range(len(daily["time"]))
    ]


def set_city(text):
    state.city = text.strip()


@plugin.on_open
def opened():
    if state.now is None and state.city:
        look_up()


@plugin.every(minutes=30)
def refresh():
    if state.city:
        look_up()


@plugin.command("Refresh", icon="refresh")
def refresh_now():
    look_up()


@plugin.setting_changed("units")
def units_changed(value):
    # Celsius or Fahrenheit: the numbers on screen are now in the old unit.
    if value and state.now is not None:
        look_up()


def render():
    with ui.column():
        with ui.row(weights=[3, 1]):
            ui.input(label="City", value=state.city, on_change=set_city, on_submit=lambda text: look_up())
            ui.button("Look up", on_click=look_up, primary=True)
        if state.error:
            ui.label(state.error, color="danger")
        if state.now is not None:
            words, icon = describe(state.now["weather_code"])
            with ui.card(title=state.place):
                with ui.row():
                    ui.icon(icon)
                    ui.stat("Now", str(round(state.now["temperature_2m"])) + "°", caption=words)
            ui.chart([d["high"] for d in state.days], kind="bar", labels=[d["date"][5:] for d in state.days])
            ui.list([
                ui.item(d["date"], subtitle=describe(d["code"])[0], icon=describe(d["code"])[1],
                        trailing=str(round(d["low"])) + "° / " + str(round(d["high"])) + "°")
                for d in state.days
            ])
