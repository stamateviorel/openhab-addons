# Ambiance AmpliPi Binding

This binding controls an [Ambiance AmpliPi](https://github.com/stamateviorel/ambiance-amplipi) controller — a lightweight, single-purpose whole-house audio appliance (a stripped fork of [micro-nova/AmpliPi](https://github.com/micro-nova/AmpliPi)) whose only jobs are **internet radio**, **Spotify Connect**, **public-address announcements**, and a **burglar siren**, over up to six amplified zones.

The controller exposes a small REST API; this binding polls it, publishes the state on channels, forwards commands, fetches album art, and registers an **audio sink** so `Voice.say(...)` and the `Audio` actions play announcements through the system.

## Supported Things

| Thing        | Type   | Description                                                                 |
|--------------|--------|-----------------------------------------------------------------------------|
| `controller` | Bridge | An Ambiance AmpliPi controller: playback sources (radio/Spotify), announcements, siren, sleep timer, master volume/mute, host diagnostics, and the audio sink. |
| `zone`       | Thing  | One amplified output zone (power, volume, mute, name). A child of a `controller`. |
| `group`      | Thing  | A controller-defined zone group controlled as one (power, volume, mute). Keyed by the group's name. |

## Discovery

If the controller host advertises the `_ambianceamplipi._tcp` mDNS service (install `packaging/avahi/ambiance-amplipi.service` from the [ambiance-amplipi](https://github.com/stamateviorel/ambiance-amplipi) repo into `/etc/avahi/services/`), the `controller` bridge is discovered automatically and appears in the Inbox with its hostname and port. Otherwise add the `controller` bridge manually with its hostname or IP.

Once the bridge is online, its **zones and zone groups are discovered automatically** — they appear in the Inbox labeled with the names configured on the controller (`zones.conf` / `groups.conf` / the web UI), so nothing has to be written by hand. Renaming a zone on the controller re-publishes its discovery result with the new label; a renamed *group* appears as a new discovery result (groups are keyed by name) and the old group thing goes offline as GONE.

## Thing Configuration

### `controller`

| Parameter         | Type    | Required | Default | Description                                            |
|-------------------|---------|----------|---------|--------------------------------------------------------|
| `hostname`        | text    | yes      |         | Hostname or IP address of the controller.              |
| `port`            | integer | no       | 8080    | HTTP API port.                                         |
| `refreshInterval` | integer | no       | 3       | How often (seconds) the controller is polled.          |

### `zone`

| Parameter | Type    | Required | Description                        |
|-----------|---------|----------|------------------------------------|
| `id`      | integer | yes      | Zero-based zone id (`0`…`5`).       |

## Channels

### `controller`

| Channel        | Type                   | RW | Description                                                                 |
|----------------|------------------------|----|-----------------------------------------------------------------------------|
| `source`       | String                 | RW | The playback source that owns the audio path (`radio`, `spotify`, ...). Command options come from the controller; selecting one switches to and starts it. The now-playing, `control` and `power` channels follow the active source. |
| `station`      | String                 | RW | Current radio station. Command options are the controller's station list.   |
| `power`        | Switch                 | RW | Radio source on/off (`ON` starts playback, `OFF` stops it).                 |
| `control`      | Player                 | RW | Transport: play/pause and next/previous station.                            |
| `masterVolume` | Dimmer                 | RW | Master volume across all zones (0–100 %).                                    |
| `masterMute`   | Switch                 | RW | Master mute across all zones.                                               |
| `title`        | String                 | R  | Current stream title (e.g. `Artist - Track`).                               |
| `artist`       | String                 | R  | Artist parsed from the stream title.                                        |
| `track`        | String                 | R  | Track parsed from the stream title.                                         |
| `cover`        | Image                  | R  | Album art fetched by the controller from the now-playing metadata.          |
| `siren`        | Switch                 | RW | Burglar siren: `ON` drives all zones to full and loops the alarm; `OFF` restores. |
| `announce`     | String                 | RW | URL of audio to play as a public-address announcement.                      |
| `announceQueue`| Number                 | R  | Announcements waiting in the controller's queue (the controller serializes a burst itself). |
| `announceVolume`| Dimmer                | RW | Default announcement loudness (the boost channel), persisted on the controller; applies to any announcement that carries no volume of its own. |
| `clearAnnouncements`| Switch            | RW | `ON` drops every announcement still queued (the one on air finishes).       |
| `healthOk`     | Switch                 | R  | `ON` while the audio subsystem (radio + preamp) is healthy.                 |
| `health`       | String                 | R  | `ok`, or a human-readable summary of what the controller reported wrong.    |
| `sleepTimer`   | Number                 | RW | Minutes until the active source is silenced (`0` = no timer). Command minutes to arm; `0` cancels. |
| `cpuPercent`   | Number                 | R  | Controller host CPU usage (advanced; polled every 60 s).                    |
| `memoryPercent`| Number                 | R  | Controller host memory usage (advanced).                                    |
| `diskPercent`  | Number                 | R  | Controller host disk usage (advanced).                                      |
| `temperature`  | Number:Temperature     | R  | Controller host CPU temperature (advanced).                                 |

### `zone`

| Channel  | Type   | RW | Description                 |
|----------|--------|----|-----------------------------|
| `power`  | Switch | RW | Zone power (output on/off).  |
| `volume` | Dimmer | RW | Zone volume (0–100 %).       |
| `mute`   | Switch | RW | Zone mute.                   |
| `name`   | String | RW | The zone's display name; commanding a new one renames it on the controller (persisted). |

### `group`

| Channel  | Type   | RW | Description                                        |
|----------|--------|----|-----------------------------------------------------|
| `power`  | Switch | RW | All member zones on/off (`ON` when every member is on). |
| `volume` | Dimmer | RW | Sets every member; state is the members' average.   |
| `mute`   | Switch | RW | Mutes every member (`ON` when every member is muted). |

## Audio Sink

Each `controller` registers an audio sink under its thing UID, so it can be used as a notification/voice target:

```java
Voice.say("Dinner is ready", "voicerss:enGB", "ambianceamplipi:controller:main")
```

or as the default sink in the console: `openhab:audio sink ambianceamplipi:controller:main`. Announcements are ducked over the radio and restored automatically by the controller. A burst of announcements is **queued and played one at a time by the controller** (its own bounded FIFO), so overlapping `say(...)` calls never talk over each other — no queueing rule is needed on the openHAB side. The `announceQueue`, `announceVolume` and `clearAnnouncements` channels expose that queue and its default loudness.

## Full Example

`ambiance.things`:

```java
Bridge ambianceamplipi:controller:main "Ambiance AmpliPi" [ hostname="192.168.1.138", port=8080, refreshInterval=3 ] {
    Thing zone z0 "Office"    [ id=0 ]
    Thing zone z1 "Bathroom"  [ id=1 ]
    Thing zone z2 "Living"    [ id=2 ]
    Thing zone z3 "Kitchen"   [ id=3 ]
}
```

`ambiance.items`:

```java
String  Radio_Station   "Station [%s]"        { channel="ambianceamplipi:controller:main:station" }
Player  Radio_Control                          { channel="ambianceamplipi:controller:main:control" }
Dimmer  Radio_Volume    "Volume [%d %%]"       { channel="ambianceamplipi:controller:main:masterVolume" }
Switch  Radio_Mute       "Mute"                { channel="ambianceamplipi:controller:main:masterMute" }
String  Radio_Title      "Now playing [%s]"    { channel="ambianceamplipi:controller:main:title" }
Image   Radio_Cover                            { channel="ambianceamplipi:controller:main:cover" }
Switch  House_Siren      "Siren"               { channel="ambianceamplipi:controller:main:siren" }
Switch  Radio_Health_OK  "Audio healthy"       { channel="ambianceamplipi:controller:main:healthOk" }

Switch  Living_Power     "Living power"        { channel="ambianceamplipi:zone:main:z2:power" }
Dimmer  Living_Volume    "Living volume [%d %%]" { channel="ambianceamplipi:zone:main:z2:volume" }
Switch  Living_Mute      "Living mute"         { channel="ambianceamplipi:zone:main:z2:mute" }
```
