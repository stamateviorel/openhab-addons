# OCPP Binding

This binding lets openHAB act as an OCPP central system, so EV chargers (charge points) that speak OCPP connect directly to openHAB — no vendor cloud required.
It speaks both OCPP 1.6-J and OCPP 2.0.1, and each charger settles on one of them when it connects.
It is built on the [ChargeTime OCA-OCPP](https://github.com/ChargeTimeEU/Java-OCA-OCPP) library.

Chargers open a WebSocket connection to openHAB and are modelled as a three-tier hierarchy that mirrors OCPP itself: one server endpoint, the charge points that dial in to it, and the connectors of each charge point.

It reports connection state, connector status and metering, and controls charging: current limit, pause, remote start/stop, availability, unlock and reset.

## Protocol versions

A charger picks the version at the WebSocket handshake, by asking for the `ocpp1.6` or `ocpp2.0.1`
subprotocol, and keeps it for the life of the connection. Nothing has to be configured for this: the
same endpoint answers both, and one charger can be on 1.6 while the next is on 2.0.1. A charger that
asks for no subprotocol at all is treated as 1.6. The Things, channels and users are the same either
way, so a charger can be moved between versions without touching the openHAB side.

A few differences are worth knowing about, because they show up in what the channels report:

- 2.0.1 has five connector statuses where 1.6 had nine, and moved the rest into the transaction it
  belongs to. The `charge-point-status` channel still reports the 1.6 names; `Occupied` reads as
  `Preparing` until the charger says what the vehicle is doing.
- 2.0.1 lets the charger name a transaction with text rather than a number. The `transaction-id`
  channel keeps reporting the number the binding logs usage under, and the charger's own id is used
  when a stop has to be sent.
- Capabilities come from the 2.0.1 device model rather than a flat key list, so they arrive a moment
  after the charger connects rather than in a single answer.
- A 2.0.1 charger can open a transaction on plug-in and take the card afterwards. The session is
  logged under the first token it presents, and the `id-tag` channel follows. A later token is taken
  only while the session still has none, so a stop by a different card does not re-attribute it.
- A session survives an openHAB restart on either version: the transaction keeps its id and its
  connector, and a stop sent afterwards still names the transaction the way the charger knows it.

The `extraConfig` entries on the `server` are named with 1.6 keys. On a 2.0.1 charger the ones the
binding knows are mapped onto their device-model variables; anything else has to be written as
`Component.Variable`, and a bare key that cannot be mapped is skipped with a note in the log.

## Supported Things

- `server`: the OCPP JSON WebSocket endpoint chargers connect to, and the bridge for all charge points.
- `chargepoint`: one physical charger matched to a session by its OCPP charge point id (the URL path it dials, without the leading slash), and the bridge for its connectors.
- `connector`: one connector (outlet) of a charger, carrying the live status and metering channels.
- `cpms-user`: a person and their RFID cards, for authorization and per-person monthly/yearly usage. Optional — add these only if you want to track who charges. See [Users and usage](#users-and-usage).

## Discovery

Discovery is passive — chargers announce themselves.
When a charger connects with a charge point id that has no `chargepoint` thing, it appears in the inbox under its `server` bridge.
When a known charge point reports a connector for the first time, that `connector` appears in the inbox under its `chargepoint` bridge.
There is no active scan; point your charger at `ws://<openhab-host>:<port>/<chargePointId>` and it will show up.

You do not need to know the charge point id in advance: connect the charger and it appears in the inbox under its real id, ready to accept.
Only if it never shows up, set the binding's log level to `DEBUG` (see [Logging](https://www.openhab.org/docs/administration/logging.html)) and look for the `Charger connected: id=...` line — it prints the exact id the charger dialed, which helps track down a URL or connection problem.
The id is whatever path the charger appends to its backend URL — often its serial number — so it is easiest to read it back here rather than hunt for it in the charger's own settings.

## Thing Configuration

### `server`

| Name                         | Type    | Description                                                                                                                                       | Default | Required | Advanced |
| ---------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------- | -------- |
| port                         | integer | TCP port the OCPP server listens on                                                                                                               | 8887    | no       | no       |
| host                         | text    | Local bind address                                                                                                                                | 0.0.0.0 | no       | yes      |
| heartbeatInterval            | integer | Heartbeat interval (s) returned to chargers on boot                                                                                               | 300     | no       | yes      |
| meterValuesData              | text    | Measurands to configure on chargers (empty = leave unchanged)                                                                                     | (empty) | no       | yes      |
| meterValueSampleInterval     | integer | MeterValueSampleInterval to configure (-1 = leave unchanged)                                                                                      | -1      | no       | yes      |
| clockAlignedDataInterval     | integer | ClockAlignedDataInterval to configure (-1 = leave unchanged)                                                                                      | -1      | no       | yes      |
| disableRemoteTxAuthorization | boolean | Configure AuthorizeRemoteTxRequests=false                                                                                                         | false   | no       | yes      |
| extraConfig                  | text[]  | Extra ChangeConfiguration entries as key=value, applied on boot                                                                                   | (empty) | no       | yes      |
| pingInterval                 | integer | WebSocket ping interval (s). A charger that does not answer a ping is disconnected, and many never do — leave at 0 unless yours is known to reply | 0       | no       | yes      |
| requestTimeoutSeconds        | integer | Seconds before an unanswered request to a charger fails                                                                                           | 30      | no       | yes      |
| authPassword                 | text    | HTTP Basic password chargers must present (username = charge point id). Empty disables authentication                                              | (empty) | no       | yes      |
| tlsKeystorePath              | text    | Path to a PKCS12 keystore with the server's TLS certificate and key. When set, the endpoint runs `wss://` (TLS) instead of `ws://`                | (empty) | no       | yes      |
| tlsKeystorePassword          | text    | Password for the TLS keystore (store and key)                                                                                                     | (empty) | no       | yes      |
| chargerIds                   | text[]  | Charge point id allow-list. Empty accepts any charger; otherwise unlisted ones are rejected                                                       | (empty) | no       | yes      |

These settings are pushed to a charger as ChangeConfiguration requests after it boots, one at a time, and only until the charger has accepted them once for the configured values — a changed configuration is sent again on the charger's next boot, an unchanged one is not repeated on every reconnect.
A request a charger leaves unanswered fails after `requestTimeoutSeconds`; the OCPP library itself would wait on it forever.
Measurands a charger rejects are dropped one at a time until it accepts them, and the accepted set is remembered per configuration key.
The binding also runs a heartbeat-derived liveness watchdog and self-heals when a charger reconnects under a new session.
Card authorization is configured binding-wide, not per server — see [Add-on Settings](#add-on-settings) below. For the charger's own offline authorization cache, see the `chargepoint` `local-auth-list` channel below.

### Add-on Settings

Card authorization is edited under Settings → Add-on Settings → OCPP Binding, so it applies to the whole binding rather than to a single server Thing.

| Name            | Type    | Description                                                                                                    | Default |
| --------------- | ------- | ------------------------------------------------------------------------------------------------------------- | ------- |
| whitelistTagIds | text[]  | idTag whitelist. Empty accepts every tag; otherwise unknown tags are rejected                                 | (empty) |
| autoLearn       | boolean | While on, a tapped tag not yet in the whitelist is added to it. Turn on to enrol cards, then off              | false   |
| discoverCards   | boolean | While on, a tapped tag no user owns is offered in the inbox to create a user from                             | false   |

`autoLearn` writes the tapped tag back into `whitelistTagIds` here, so the card list stays visible and editable in one place and no charger session is dropped while enrolling.

### `chargepoint`

| Name                | Type    | Description                                                                                                                             | Default | Required | Advanced |
| ------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------- | -------- |
| chargePointId       | text    | The charger's OCPP identity (its WebSocket URL suffix)                                                                                  | N/A     | yes      | no       |
| configSettleSeconds | integer | Delay after BootNotification before the configuration above is sent. Some chargers are not ready to answer immediately                  | 0       | no       | yes      |
| meterless           | boolean | The charger has no internal meter: skip measurand configuration and disable clock-aligned sampling                                      | false   | no       | yes      |
| heartbeat           | integer | Per-charger heartbeat interval (s), overriding the server default. Also sizes this charger's liveness window. 0 uses the server default | 0       | no       | yes      |
| extraConfig         | text[]  | Extra ChangeConfiguration entries as key=value for this charger alone, applied after any set on the server                               | (empty) | no       | yes      |

### `connector`

| Name                  | Type    | Description                                                                                                                                                        | Default | Required | Advanced |
| --------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | -------- | -------- |
| connectorId           | integer | OCPP connector number (1..N)                                                                                                                                       | 1       | no       | no       |
| forceTxDefaultProfile | boolean | Always send the charge limit as a TxDefaultProfile, even during a transaction. Needed for chargers that reject a TxProfile outside one                             | false   | no       | yes      |
| profileMinIntervalMs  | integer | Minimum spacing (ms) between SetChargingProfile sends; rapid changes are coalesced. 0 disables                                                                     | 0       | no       | yes      |
| hardwareMaxCurrentKey | text    | Vendor ChangeConfiguration key backing the `hardware-max-current` channel. Empty disables that channel                                                             | (empty) | no       | yes      |
| remoteStartTag        | text    | idTag used when starting a transaction via the `charging` channel                                                                                                  | openhab | no       | yes      |
| refreshInterval       | integer | Poll this connector for MeterValues every N seconds via TriggerMessage. 0 disables polling                                                                         | 0       | no       | yes      |
| nominalVoltage        | decimal | Line voltage for converting an amps charge-limit to watts on a charger that only accepts a power limit (W = A×V×phases)                                            | 230     | no       | yes      |
| phases                | integer | Phases assumed in that amps→watts conversion — 1 single-phase, 3 three-phase                                                                                       | 1       | no       | yes      |
| stuckStateRecovery    | boolean | Send an UnlockConnector if the connector stays in a transient state (Preparing/Finishing) too long. Off by default; enable only for a charger known to wedge there | false   | no       | yes      |
| remoteStartRetries    | integer | Retry a RemoteStart the charger does not answer, this many times. 0 disables. For a charger that drops the first start request but accepts a retry                 | 0       | no       | yes      |
| externalEnergyItem    | text    | Number item metering this connector, for usage accounting when the charger has no OCPP meter                                                                       | (empty) | no       | no       |
| externalMeterType     | text    | How to read that item: `energy-kwh`, `energy-wh` (cumulative, differenced) or `power-kw`, `power-w` (instantaneous, integrated)                                    | energy-kwh | no    | no       |

Most connectors need no configuration beyond `connectorId`.
The rest cover specific charger behaviors.
Entries are sent when a charger boots, and again when one reconnects without booting — which is what happens after openHAB restarts — so a changed entry reaches a charger that stayed on without waiting for its next reboot.
The charge point's own `extraConfig` is for a setting that belongs to one charger rather than the site — a vendor key, or something you want to change on a single unit. Its entries are sent after the server's, so a key set in both is left at the charger's value. A charger reports which of its settings are writable, and the ones it calls read-only are logged as such when the binding reads its configuration.

`forceTxDefaultProfile` is for chargers that reject a `TxProfile` when no transaction is active — a Phoenix CHARX does: the charge limit is then sent as a `TxDefaultProfile`, which such chargers accept and apply through their own load management.
`profileMinIntervalMs` coalesces rapid limit changes into at most one `SetChargingProfile` per interval, which keeps a solar-tracking rule that adjusts the limit every few seconds from flooding the charger.
`refreshInterval` actively polls a connector for `MeterValues` for chargers that do not push them on their own; a poll is skipped while the previous one is still outstanding, so a charger that stops answering cannot build a backlog.
`hardwareMaxCurrentKey` binds the `hardware-max-current` channel to a vendor `ChangeConfiguration` key, since the hardware ceiling is not a standard OCPP setting.
`stuckStateRecovery` is left off because auto-unlocking a connector is a physical side effect, and `Preparing` and `Finishing` are normal states a charger can dwell in.
`remoteStartRetries` is for a charger that intermittently ignores the first `RemoteStartTransaction`: the binding re-sends it up to that many times, a few seconds apart, and stops as soon as a transaction starts, so it never double-starts. Off (0) by default, so a charger that answers first time is unaffected.
`externalEnergyItem` is for a charger with no internal meter (a Phoenix CHARX, say): point it at a Number item fed by a separate meter, such as a Modbus energy clamp, and the binding uses that item for the session's energy instead of the charger's own meter. Without it, a meter-less charger logs sessions with zero energy.
`externalMeterType` says what that item carries, so either kind of meter works. A **cumulative** counter (`energy-kwh` or `energy-wh`) is read at the start and stop of each session and differenced. An **instantaneous power** reading (`power-kw` or `power-w`) is sampled through the session and integrated into energy — for a clamp that reports live power rather than a running total. A `Number:Energy` or `Number:Power` item is converted from whatever unit it carries; a plain `Number` is taken in the unit named by the type. Integration samples every 30 s, which is well within tolerance for the roughly steady power of a charging car. A cumulative counter is the more robust choice where you have one: it also survives an openHAB restart mid-session, whereas an in-progress power integration is lost across a restart and that session logs no energy.

## Channels

### `chargepoint`

| Channel         | Type     | Read/Write | Description                                                                                                       |
| --------------- | -------- | ---------- | ----------------------------------------------------------------------------------------------------------------- |
| connected       | Switch   | R          | Whether the charger has an open session                                                                           |
| last-seen       | DateTime | R          | Timestamp of the last contact from the charger                                                                    |
| reset           | Switch   | W          | Momentary — soft reset the charge point                                                                           |
| local-auth-list | String   | RW         | The charger's local authorization list (comma-separated idTags), persisted — set it to push cards for offline use |
| custom-message  | String   | RW         | Sends a vendor-specific message and shows the answer. OCPP 2.0.1 only                                             |
| display-message | String   | RW         | Text to show on the charger's own screen; empty clears it. OCPP 2.0.1 only                                        |

Vendor, model, firmware version and serial number are published as thing properties from the charger's BootNotification.

`display-message` writes a line to the charger's own screen and clears it again when set to an empty string. The binding keeps one message of its own, so setting new text replaces the last rather than stacking another on top, and a charger that will not take the message says so instead of failing quietly.

`custom-message` carries a vendor-specific OCPP 2.0.1 `DataTransfer`, for a setting or command a charger only exposes its own way. Send it a JSON object naming the vendor, and optionally a message id and a payload:

```json
{"vendorId": "Alfen", "messageId": "SetSetting", "data": "{\"key\": 1}"}
```

The charger's answer is published back on the same channel as `{"status":"Accepted","data":…}`, so a rule can read what came of it. `UnknownVendorId` or `UnknownMessageId` means the charger did not recognise what was sent, which is its answer rather than a failure. The channel is offered for 2.0.1 only; on a 1.6 charger a command to it is logged and dropped. Vendor messages are not portable between charger makes, so anything sent here is specific to the hardware in front of you.

Inbound vendor messages are answered with `UnknownVendorId` and logged rather than acted on, and a charger's security events, log-upload progress, monitoring reports and customer-information answers are logged as they arrive.

The local authorization list lets a cached RFID card start a charge while openHAB or the network is offline, on a charger that supports `LocalAuthListManagement`.
The list lives on the `local-auth-list` channel as a comma-separated set of idTags: set it (from a rule or the UI) and the binding pushes it to the charger with `SendLocalList`, versioned by content so it is not rewritten on every boot. It is persisted on the charge point thing, so it survives an openHAB restart. A charger that does not advertise the profile is left untouched.

### `connector`

| Channel                 | Type                     | Read/Write | Description                                                                                     |
| ----------------------- | ------------------------ | ---------- | ----------------------------------------------------------------------------------------------- |
| charge-point-status     | String                   | R          | OCPP status (Available, Preparing, Charging, ...)                                               |
| cable-connected         | Switch                   | R          | Whether a vehicle cable is plugged in (derived)                                                 |
| current-import-l1/l2/l3 | Number:ElectricCurrent   | R          | Imported current per phase (MeterValues)                                                        |
| voltage-l1/l2/l3        | Number:ElectricPotential | R          | Voltage per phase (MeterValues)                                                                 |
| current-offered         | Number:ElectricCurrent   | R          | Current offered to the vehicle                                                                  |
| power-active-import     | Number:Power             | R          | Active power imported                                                                           |
| power-offered           | Number:Power             | R          | Power offered to the vehicle                                                                    |
| energy-active-import    | Number:Energy            | R          | Energy register (Energy.Active.Import.Register)                                                 |
| session-energy          | Number:Energy            | R          | Energy of the current session so far, from the meter register; final value at stop              |
| charging                | Switch                   | RW         | ON while a transaction runs; command to remote start/stop                                       |
| charge-limit            | Number:ElectricCurrent   | RW         | Charge current cap via SetChargingProfile                                                       |
| power-limit             | Number:Power             | RW         | Charge power cap (watts) for power-only chargers; takes over from charge-limit until a later charge-limit clears it |
| number-phases           | Number                   | RW         | Phases to charge on (1/2/3); 0 = charger default. Needs a charger that supports phase switching |
| pause                   | Switch                   | RW         | Pause charging (profile limit 0) without ending the transaction                                 |
| availability            | Switch                   | RW         | OCPP availability (Operative/Inoperative)                                                       |
| unlock                  | Switch                   | W          | Momentary — unlock the connector                                                                |
| hardware-max-current    | Number:ElectricCurrent   | RW         | Hardware current ceiling via a vendor config key                                                |

Beyond the channels above, the connector also exposes the full OCPP 1.6 SampledValue set — aggregate and per-phase current/voltage, active and reactive power, power factor, frequency, active/reactive energy (register and interval, import and export), plus vehicle telemetry (`soc`, `rpm`, `temperature`) — and per-transaction metadata (`id-tag`, `transaction-id`, `meter-start`, `meter-stop`) and the metering timestamps (`timestamp`, `timestamp-start`, `timestamp-stop`).

For chargers that reject a TxProfile outside a transaction (e.g. Phoenix CHARX), set `forceTxDefaultProfile` on the connector so the charge limit is sent as a TxDefaultProfile.

## Controlling a charge

The connector's writable channels map to OCPP commands, and each updates only once the charger confirms the command — a rejected request leaves the channel showing the real state rather than the requested one.

`charging` starts and stops a transaction: sending it `ON` issues a `RemoteStartTransaction`, `OFF` a `RemoteStopTransaction`.
The transaction is started with the idTag from the connector's `remoteStartTag` (default `openhab`), which has to be authorized: by this binding through the Authorized Tag IDs list in [Add-on Settings](#add-on-settings) (empty accepts any tag), and by the charger itself if it enforces its own whitelist.
So if `ON` does nothing, set `remoteStartTag` to a tag your charger accepts, or allow that tag on the charger.
To start as someone else — a particular card, or a vehicle's own AutoCharge identity — send that token as a command to the connector's `id-tag` channel first; the next `ON` presents it, typed as a vehicle where a user lists it under `vehicles`. It is spent by a start the charger accepts — a start the charger rejects keeps it, so it does not have to be set again — and any session beginning clears it. The following session goes back to the connector's configured `remoteStartTag` rather than silently running as the last token used, and the channel shows the token the next start would present.
Most chargers also only start once a vehicle is plugged in, so a `RemoteStart` on an idle connector is often ignored.
Because `charging` follows the charger's reported status, it also reads `ON` on its own whenever a transaction is running, however it was started.

Stopping has one limitation to be aware of: a `RemoteStop` needs the transaction id the charger assigned when the session began, and openHAB only holds that id for a session it saw start.
A session started outside openHAB — by the vehicle or the charger's own app, or before the `connector` thing existed, or while openHAB was down — therefore cannot be ended from `charging`: there is no id to stop with, so `OFF` is logged and does nothing.
To keep stop working, start the charge from openHAB (`charging` `ON`), which makes the transaction tracked; for a session you did not start, suspend the power with `pause` (a 0 A profile needs no transaction) or end it with the `chargepoint`-level `reset` (a reset needs no transaction either, but reboots the whole charger).

`charge-limit` caps the charging current: the value is sent as a `SetChargingProfile` and the channel reflects the applied limit once accepted.
Some chargers only accept a charge limit expressed in watts (their OCPP `ChargingScheduleAllowedChargingRateUnit` is `Power`, not `Current`); the binding learns this from the charger and converts `charge-limit` amps to watts with `nominalVoltage` and `phases`, so the same amps channel still works.
Alternatively set `power-limit` (watts) directly — it is sent as-is, with no conversion, on any charger that accepts a power limit, and takes over from `charge-limit` while it is set. Commanding `charge-limit` again clears the power-limit and returns to amps, so the most recent command always wins.
`number-phases` requests charging on a given number of phases (1, 2 or 3) by setting `numberPhases` in the charging profile — for switching a car to single-phase when solar surplus is low, for instance; 0 clears the request so the charger keeps its own default (OCPP assumes 3). It only takes effect on a charger that supports phase switching (its `ConnectorSwitch3to1PhaseSupported` is true), and when set it also drives the amps→watts conversion above.
`pause` suspends charging with a 0 A profile without ending the transaction; switching it off resumes — at your `charge-limit` if one is set, otherwise by removing the cap so the charger returns to its own maximum — distinct from `charging`, which ends the session.
A pause is a 0 A limit, so a resume must lift the cap rather than send another 0 A, which a charger reads as "stay suspended".
`availability` takes the connector Operative or Inoperative, `unlock` releases the cable lock, and the `chargepoint`-level `reset` performs a soft reset of the whole charger.

## Users and usage

A private site with several chargers often wants to know who charged and how much. Add a `cpms-user` thing per person and the binding tracks their energy and can gate authorization on their cards — all optional; without any users, authorization falls back to the [Add-on Settings](#add-on-settings) whitelist and no usage is tracked.

A `cpms-user` carries the person's `cards` (their RFID idTags), their `vehicles`, an `enabled` switch (off blocks that person from starting a charge), and an optional `monthlyCapKwh`.

`vehicles` holds the tokens a car or a charger presents on its own instead of a card: the MAC address a charger sends when AutoCharge recognises the vehicle, or the identifier it sends when it is set to start on plug-in. They are managed exactly like cards — either kind authorizes a charge, and both count towards the same person's usage — so a site can mix cards and AutoCharge without keeping two lists of people. Which kind a charger presented is visible on 2.0.1, where the protocol names it; a 1.6 charger sends only the value, so a vehicle there is indistinguishable from a card and can simply go in whichever list you find clearer.

Note that once any user exists, a plug-in or AutoCharge token is refused like any other unknown token until it belongs to someone. Turn on Discover New Cards, let the vehicle or charger present it once, and it appears in the inbox — labelled as a vehicle where the charger said so. The cap gates the start of a session: once that person's logged charging this month reaches it, their cards stop authorizing until the next month rolls over. A session already under way is not cut off, so one long session can carry a little past the cap. Its `month-energy` and `year-energy` channels report the kWh that person has drawn since the start of the month and year, summed across every charger from the transactions the binding logs.

The session log is append-only and never trimmed, so month and year totals stay computable for as far back as the binding has run; if the stored log is ever found unreadable, a new session is dropped rather than allowed to overwrite the history.

To add someone without typing card ids, turn on Discover New Cards in Add-on Settings, have them tap their card, and it appears in the inbox as a new user pre-filled with that card, labelled with the charger, the connector where one is known, and the time it was seen — accept it and give it their name. Turn Discover off again once everyone is enrolled. You can also add a `cpms-user` by hand and type the cards in.

Once at least one user exists, the binding serves an **OCPP Charging** dashboard in the sidebar (no setup, no items to wire): the month's and year's totals, a stacked chart of the last twelve months per person, the split per charger, the people with their month, year and cap, and the recent sessions. Tapping a person opens their own page with the same figures, chart and session history for them alone. The pages appear only while users exist — for a site with no users they stay hidden.

## Full Example

### `demo.things`

```java
Bridge ocpp:server:main [ port=8887 ] {
    Bridge chargepoint wallbox "Wallbox" [ chargePointId="wallbox" ] {
        Thing connector c1 "Connector 1" [ connectorId=1 ]
    }
}
```

### `demo.items`

```java
String  Wallbox_Status   "Status [%s]"              { channel="ocpp:connector:main:wallbox:c1:charge-point-status" }
Switch  Wallbox_Cable    "Cable connected"          { channel="ocpp:connector:main:wallbox:c1:cable-connected" }
Number:Power  Wallbox_Power  "Power [%.0f W]"       { channel="ocpp:connector:main:wallbox:c1:power-active-import" }
Number:Energy Wallbox_Energy "Energy [%.2f kWh]"    { channel="ocpp:connector:main:wallbox:c1:energy-active-import" }

Switch  Wallbox_Charging "Charging"                 { channel="ocpp:connector:main:wallbox:c1:charging" }
Switch  Wallbox_Pause    "Pause"                    { channel="ocpp:connector:main:wallbox:c1:pause" }
Number:ElectricCurrent Wallbox_Limit "Limit [%.0f A]" { channel="ocpp:connector:main:wallbox:c1:charge-limit" }
Switch  Wallbox_Reset    "Reset charger"            { channel="ocpp:chargepoint:main:wallbox:reset" }
Switch  Wallbox_Force_Stop "Force stop"
```

The `reset` channel is on the `chargepoint`, not the connector, and is momentary — the binding pops it back OFF after sending.
`Wallbox_Force_Stop` is an unbound helper for the rule below.

### `demo.rules`

```java
// Charge only overnight, using pause — which works even for a session openHAB did not start.
rule "Resume charging at 23:00"
when
    Time cron "0 0 23 ? * *"
then
    Wallbox_Pause.sendCommand(OFF)
end

rule "Pause charging at 07:00"
when
    Time cron "0 0 7 ? * *"
then
    Wallbox_Pause.sendCommand(ON)
end

// Follow solar surplus: cap the current to the spare power (single phase, ~230 V).
// A starting point only; real solar charging also wants hysteresis and a breaker-headroom check.
rule "Track solar surplus"
when
    Item Solar_Surplus_W changed
then
    var Number surplus = Solar_Surplus_W.state as Number
    var int amps = (surplus.doubleValue / 230.0).intValue
    if (amps < 6)  amps = 6
    if (amps > 16) amps = 16
    Wallbox_Limit.sendCommand(amps)
end

// Stop a session openHAB did not start (no transaction id, so `charging` OFF cannot end it).
// Cut the power with pause, or end the session with a reset (which reboots the charger).
rule "Force stop"
when
    Item Wallbox_Force_Stop received command ON
then
    Wallbox_Pause.sendCommand(ON)
    // Wallbox_Reset.sendCommand(ON)   // uncomment to end the session instead of only pausing
end
```

## Troubleshooting

### A charge point stays UNKNOWN, or nothing appears in the inbox

The charge point id is the path of the WebSocket URL the charger dials, so the charger must connect to `ws://<host>:<port>/<chargePointId>`.
A charger pointed at the bare root (`ws://<host>:<port>/`, nothing after the slash) sends no id and is ignored, logging `connected without a charge point id in its URL path`.
Put the id in the charger's backend URL — many chargers keep the URL and the id in separate fields, but it still has to end up as the URL path after the leading slash — and make the `chargepoint` thing's `chargePointId` match it exactly.
If you are unsure what the charger actually sends, enable `log:set DEBUG org.openhab.binding.ocpp` and read it off the `Charger connected: id=...` line.

### A connector sits at SuspendedEVSE and will not charge

`SuspendedEVSE` means the charge point itself is withholding energy — a charging-profile limit or an authorization result — unlike `SuspendedEV`, which is the vehicle not drawing (battery full, or charging scheduled in the car).
Check the connector is not left paused and that `charge-limit` is not 0: sending `pause` OFF resumes charging — at your `charge-limit` if one is set, otherwise by clearing the cap so the charger returns to its own maximum.
Some chargers also suspend when a `charge-limit` is set _before_ a transaction starts — with no transaction it goes out as a `TxDefaultProfile`, which such a charger accepts but then drops to `SuspendedEVSE` a few seconds in. On those, start the charge first and set the limit once it is `Charging`: send `charging` ON (or plug in), wait for `Charging`, then set `charge-limit`. Adjusting it mid-charge afterwards works normally.

### A charger never connects and the log shows nothing after start-up

If the server starts (`OCPP JSON server listening`) but no `Charger connected` line ever follows, the charger is not completing the WebSocket handshake, so nothing reaches the binding.
First rule out the network: from a device on the charger's own network segment (not just any machine), check the openHAB host and port are reachable — `nc -zv <openhab-host> 8887` — and use the host's IP rather than a `.lan` name to rule out DNS.
A charger that always sends an HTTP Basic-auth header — some send their id with a very short or empty password on every connection, a V2C Trydan being one — is accepted when no `authPassword` is configured, so that is no longer a cause of a silent no-connect (older builds did reject such a charger during the handshake, before the binding saw it).

### Power readings lag behind the charger

The `power-active-import` and per-phase metering channels only update when the charger sends a `MeterValues` sample, so between samples they look stale (the energy register keeps climbing because it is a running total). To sample more often, lower the `server`'s `meterValueSampleInterval` — the binding pushes it to the charger (10–15 s is plenty). For a charger that will not honour that, set the `connector`'s `refreshInterval` to poll it for a fresh `MeterValues` on a fixed cadence via TriggerMessage. Do not push either below a few seconds on an older charger.

## Charger-specific notes

Every charger dials `ws://<openhab-host>:<port>/<chargePointId>`; the only real differences are how each vendor's UI presents the URL and the id, and a few per-charger quirks.

| Charger                             | Configure on the charger                                                          | Notes                                                                                                                                                                |
| ----------------------------------- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Phoenix Contact CHARX SEC-3xxx      | `ws://<host>:8887/<id>`                                                           | No internal meter: set `meterless` on the `chargepoint` and `forceTxDefaultProfile` on the `connector`. Metered externally.                                          |
| Wallbox Copper SB / Pulsar Plus     | `ws://<host>:8887/<id>`                                                           | Works with defaults.                                                                                                                                                 |
| Alfen Eve Single/Double             | `ws://<host>:8887/<id>` (CSMS URL in the ACE Service Installer)                   | Speaks either version; the network profile that wins decides which. Its BootNotification model can exceed OCPP's 20-character limit; the binding accepts it rather than refusing the charger. |
| Mennekes Amtron (Bender controller) | Backend URL `ws://<host>:8887/` plus ChargeBoxIdentity `<id>` in a separate field | The controller joins them into `ws://<host>:8887/<id>`. Do not copy the `/OCPPJProxy/v16/` path from the Bender docs — that is only for their proxy backend.         |
| V2C Trydan                          | `ws://<host>:8887/<id>`                                                           | Sends a short-password HTTP Basic-auth header on every connection; accepted (the binding relaxes the library's password-length check when no `authPassword` is set). |

## Security

Without `authPassword` the endpoint runs OCPP security profile 0: a plain-text WebSocket that accepts every connection, appropriate only on a trusted LAN.
Anyone who can reach the port can connect under any charge point id, so restrict exposure by binding a specific interface (`host`) or with firewall rules.
Setting `authPassword` enables HTTP Basic authentication (security profile 1): a charger must present the password with its charge point id as the username, and other connections are rejected before a session opens.
The OCPP profile-1 rule is 16–20 visible ASCII characters for 1.6 and 16–40 for 2.0.1. A configured `authPassword` is bounded to 16–40, the union of the two, so a textual `.things` file cannot set one no charger could match; what a charger _presents_ is not length-checked at all when no `authPassword` is set, and is compared exactly when one is. The library would otherwise refuse a handshake on length alone, before the binding saw it, which silently locked out chargers that always send a header with a short or empty password.
Setting `tlsKeystorePath` (a PKCS12 keystore holding the server's certificate and key) serves the endpoint over `wss://` — OCPP security profile 2 together with `authPassword`, or an encrypted profile 0 without. Client-certificate authentication (profile 3) is not supported.
