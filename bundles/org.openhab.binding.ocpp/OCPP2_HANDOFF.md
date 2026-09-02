# OCPP 2.0.1 / 2.1 implementation handoff — `org.openhab.binding.ocpp`

You are a Claude session on a system that has a live **Alfen** charger able to speak both **OCPP
1.6-J** and **OCPP 2.0.1 / 2.1**. Your job: add a **2.0.1 (and 2.1-ready) stack to the existing
binding as an additive second protocol, without disturbing the working 1.6-J path**. This document is
self-contained — it assumes none of the original author's machine, notes, or memory.

Read it end to end before writing code. The plan here is deliberate and grounded; follow it rather
than re-deriving.

---

## 0. The one-line task

One binding, two protocols. A charger negotiates `ocpp1.6` **or** `ocpp2.0.1` (or `ocpp2.1`) at the
WebSocket handshake and the binding routes it to the right handler set. **The openHAB-facing model —
Things, channels, and the whole CPMS layer — stays identical regardless of which protocol the charger
speaks.** That is the entire point: a charger upgrading its firmware must not change a single Thing or
Item the user has.

Do **not** fork a second binding. Do **not** touch the 1.6 path's behaviour. The seam already exists
(see §3); you extend it.

---

## 1. GitHub, identity, access

- **Repo (your working copy):** `https://github.com/stamateviorel/openhab-addons` — a fork of
  `openhab/openhab-addons`.
- **Branch to build on:** **`ocpp-cpms`** — the full binding: OCPP 1.6-J central system + the CPMS
  layer (users/cards/caps/usage, a Charging page, external metering). This is the latest of
  everything.
- **Do not confuse with `ocpp-initial-contribution`** — that is the *lean* 1.6-only branch under
  upstream review as PR #21265. Your 2.0.1 work is **not** for that PR; it is a later, separate
  contribution. Keep CPMS and 2.0.1 off #21265.
- **The binding lives at** `bundles/org.openhab.binding.ocpp/`.
- **Clone & check out:**

  ```bash
  git clone https://github.com/stamateviorel/openhab-addons.git
  cd openhab-addons
  git checkout ocpp-cpms
  ```

- **Commit identity — mandatory on every commit** (openHAB CI enforces DCO):

  ```text
  Signed-off-by: Stamate Viorel <stamate.viorel@gmail.com>
  Co-Authored-By: Claude <noreply@anthropic.com>
  ```

  Set `git config user.name "Stamate Viorel"` / `user.email "stamate.viorel@gmail.com"` in the clone.
  `gh` should be authenticated as `stamateviorel`. **Never push or open/modify a PR without the
  owner's explicit go** — prepare the branch, show the exact command, wait.

- Suggested working branch for this task: `git checkout -b ocpp2-dualstack ocpp-cpms`.

---

## 2. Build, test, deploy

- **Toolchain:** Maven **3.9.9**, **Temurin 21** (`JAVA_HOME`). System Maven/JDK are usually too old.
- **Build one bundle (the full gate):**

  ```bash
  JAVA_HOME=/path/to/temurin-21 mvn -pl bundles/org.openhab.binding.ocpp spotless:apply install -Dgib.disable=true
  ```

  `-o` (offline) works once dependencies are cached. Add `-am` if the reactor needs the parent poms
  built first. The build runs, in order: **spotless** (formats/import-sorts — `spotless:apply` fixes,
  `spotless:check` in `verify` fails on drift), **SAT** (checkstyle: class Javadoc + `@author`
  required, no unused imports; SpotBugs), **JUnit5 + Mockito tests**, **Karaf feature verification**,
  **markdownlint** on `README.md`. All must pass.
- **`ocpp-cpms` currently builds green with 167 tests.** Keep it green.
- **Gotcha:** on a memory-tight or busy host, background Maven can be OOM-reaped mid-build. If a build
  dies early ("Scanning for projects…" then killed) with no error, run it in the **foreground** and/or
  cap the heap (`MAVEN_OPTS="-Xmx900m"`), and free memory first.
- **Deploy to a live openHAB for testing:** drop the built jar
  (`bundles/org.openhab.binding.ocpp/target/org.openhab.binding.ocpp-*.jar`) into the runtime's
  `addons/` directory; the felix file-install watcher reloads it (~30 s). Or, in the Karaf console,
  `bundle:update <id>` (find the id with `bundle:list | grep binding.ocpp`). Keep a copy of the
  previous jar for instant rollback.

---

## 3. Current architecture (1.6) — and the seam you extend

Thing model (unchanged for 2.0.1):

```text
server (bridge)  →  chargepoint (bridge)  →  connector (thing)
                                          →  cpms-user (thing, optional)
```

- **`server`** owns the OCPP WebSocket endpoint (default port 8887, path = chargePointId).
- **`chargepoint`** = one physical charger, matched by the URL path it dials.
- **`connector`** = one outlet; carries status + metering + the control channels
  (charge-limit, power-limit, pause, number-phases, availability, …).
- **`cpms-user`** = a person + their RFID cards (CPMS; protocol-agnostic).

Transport & inbound (the part that forks per protocol):

- **`internal/transport/ChargeTimeTransport.java`** wraps the ChargeTime `Server` and sets the
  WebSocket subprotocol. Today: `new Protocol("ocpp1.6")` on the `Draft_6455`. It registers the 1.6
  server feature profiles (Core, SmartCharging, RemoteTrigger, LocalAuthList).
- **`internal/transport/InboundCoreHandler.java`** implements the ChargeTime 1.6
  `ServerCoreEventHandler`, answers each request with a spec-valid confirmation, and forwards events to…
- **`internal/transport/OcppServerListener.java`** — **THE SEAM.** The bridge handler implements it.
  Right now its methods take **1.6 library types** (`BootNotificationRequest`, `MeterValuesRequest`,
  `StartTransactionRequest`, `StopTransactionRequest`, `StatusNotificationRequest`). **This is the one
  thing that must change to go dual-stack** (see §7 step 1).
- **`internal/handler/OcppServerBridgeHandler.java`** = the listener implementation + session routing +
  outbound dispatch + CPMS wiring. **`OcppChargePointHandler` / `OcppConnectorHandler`** = per-charger
  and per-connector logic (boot config, charging profiles, metering, liveness watchdog).

Everything **above** the seam is protocol-neutral already and must be reused as-is for 2.0.1:

- **CPMS** (`internal/cpms/CpmsService.java`, `OcppBindingConfig`, `internal/ui/OcppCpmsUiProvider`) —
  users, cards, person-based authorization, monthly caps, per-user month/year usage, transaction log,
  the Charging sidebar page. It operates on **abstract transaction start/stop events + energy**, not on
  any 1.6 wire type. It does not care about protocol.
- **External metering** — a connector can point at an openHAB item (cumulative kWh or instantaneous
  power) for energy accounting when the charger has no usable meter. Protocol-neutral.
- **Channels, Thing types, config-descriptions, i18n, discovery** — reused unchanged.

**Dependency reality (important):** the binding already embeds the **ChargeTime `2.0`-generation
jars** for its 1.6 support — `ocpp16` (models + profiles), `ocpp-json` (transport), `ocpp-common`
(base), plus `Java-WebSocket 1.6.0` and Gson (platform) — via bnd `Embed-Dependency`
(`<bnd.includeresource>` in `pom.xml`). The 2.0.1 module is the **same library family**
(`eu.chargetime.ocpp:ocpp2:2.0`), so adding it is a natural extension of the existing embed, not a new
dependency stack.

---

## 4. OCPP 2.0.1 vs 1.6 — what actually changes on the wire

The RPC framing is identical across versions (OCPP-J: `CALL [2,id,action,payload]` /
`CALLRESULT [3,…]` / `CALLERROR [4,…]` over WebSocket). Only **action names, payload schemas, and
handlers fork.** What differs:

- **Version negotiation is per-connection**, via the WebSocket `Sec-WebSocket-Protocol` subprotocol
  (`ocpp1.6` / `ocpp2.0.1` / `ocpp2.1`); the server echoes exactly one. A charger speaks **one version
  for the life of the socket.**
- **Transactions:** 2.0.1 replaces 1.6's separate `StartTransaction` / `StopTransaction` /
  `MeterValues` / `StatusNotification` churn with **one `TransactionEvent`** message
  (`eventType = Started | Updated | Ended`) carrying meter readings, the trigger reason, and the id
  token inline. This is the single biggest mapping change — and it makes session accounting cleaner
  than 1.6.
- **Config / capabilities:** 2.0.1 drops 1.6's flat `GetConfiguration` key/value list for a
  hierarchical, self-describing **device model** — `ChargingStation → EVSE → Connector`, everything a
  **Component** holding typed **Variables** (`Actual/Target/MinSet/MaxSet`, RO/RW/WO). Discover with
  `GetBaseReport(FullInventory)` → paginated `NotifyReport`; then `GetReport` /
  `GetVariables` / `SetVariables`. Nearly every controller has `Available` (RO) + `Enabled` (RW).
  Standard components include `SmartChargingCtrlr`, `OCPPCommCtrlr`, `SampledDataCtrlr`,
  `SecurityCtrlr`. **This supersedes the binding's hand-rolled `ChargerCapabilities` /
  `GetConfiguration` discovery for 2.0.1 chargers** — adopt it rather than porting the 1.6 approach.
- **Smart charging:** `SetChargingProfile` takes `evseId` (0 = whole station) + a `chargingSchedule`
  **array** (max 3), and the response is **`Accepted` / `Rejected` only — `NotSupported` was removed**.
  New purpose `ChargingStationExternalConstraints`. `chargingRateUnit` still `A`/`W`; `numberPhases`
  still there; **`phaseToUse` (1/2/3) is new** for selecting *which* phase in single-phase charging
  (needs `ACPhaseSwitchingSupported` on `SmartChargingCtrlr`). Note: **1↔3 phase count switching is
  NOT 2.0.1-only** — 1.6 has it (`numberPhases`) and the binding already implements it.
- **Remote control:** `RequestStartTransaction` / `RequestStopTransaction` replace 1.6's
  `RemoteStartTransaction` / `RemoteStopTransaction`.
- **Authorization:** `Authorize` carries an `idToken` (typed) and can carry certificates for **ISO
  15118 Plug & Charge** — a certificate-based auth model alongside RFID. `IdTokenInfo` replaces
  `IdTagInfo`.
- **Security:** security profiles (TLS, per-charger client certs, secure firmware) are first-class in
  2.0.1. Signed meter values (tamper-evident) exist — relevant if usage is ever billed.
- **Upward limit reporting (PV/load-balancing):** `NotifyChargingLimit` / `ClearedChargingLimit` with
  `ChargingLimitSource ∈ {EMS, SO, CSO, Other}` — 1.6 has no equivalent.
- **2.1** (Jan 2025) is an additive superset of 2.0.1 (V2X/bidirectional, DER control, tariffs,
  dynamic setpoints via `chargingProfileKind=Dynamic` + `UpdateDynamicSchedule`). Build 2.0.1 first;
  the same `v201` structure extends to `v21`. Confirm whether the ChargeTime library ships a `v21`
  package before promising 2.1 handlers — as of this writing 2.1 is thin on the library side.

**Blunt truth to keep in mind:** a protocol version grants **zero** capability on its own. A charger
that rejects `SetChargingProfile` on 1.6 will reject it on 2.0.1; smart charging is optional in both.
2.0.1 makes capability better *describable* (device model), not more *obeyed*. Diagnose charger-side
walls as charger-side.

---

## 5. The library

- **`eu.chargetime.ocpp:ocpp2:2.0`** — on **Maven Central since 2025-12-13, MIT-licensed**. Library
  version is `2.0`; the **protocol it implements is 2.0.1** (Java package `eu.chargetime.ocpp.v201.*` —
  the `v201` package is the proof; do not conflate library `2.0` with protocol `2.0.1`).
- **CSMS/server support is real:** `MultiProtocolJSONServer`, `IMultiProtocolServerAPI`, and
  `Server*Function` handler classes ship in the jar; `ocpp-json` provides a WSS server factory.
- **Deps are lean and OSGi-friendly:** Gson + Java-WebSocket 1.6.0 (both already in the bundle). The
  jars carry **no OSGi manifest** → embed via bnd, exactly like the 1.6 jars: add the artifact to the
  `<bnd.includeresource>` `@…jar` list and as a `<dependency>` in `pom.xml`.
- **Same `validate()` / builder codec idiom** as the 1.6 library. **64 operations** in 2.0.1 vs 28 in
  1.6.
- The **`MultiProtocolJSONServer`** is the intended path for dual-stack: it accepts multiple
  subprotocols on one endpoint and dispatches to the right handler set. Prefer it over standing up two
  separate servers.

Other JVM options are non-starters (CitrineOS = Node; ShellRecharge = Scala/GPL-3.0 blocks EPL; SteVe
= 1.6-only GPL webapp). `ocpp2:2.0` is the only permissive, Central-published, CSMS-capable 2.0.1 JVM
library.

---

## 6. Step-by-step implementation

Work additively. After each step, build green and — where possible — verify against the Alfen.

1. **Neutralise the seam (`OcppServerListener`).** Replace the 1.6 library types in its method
   signatures with small **protocol-neutral value objects** (Java records) the binding owns, e.g.
   `BootInfo(vendor, model, firmware, serial)`, `TransactionEvent(kind, connectorId, idToken,
   meterWh, timestamp, reason)`, `MeterSample(...)`, `StatusInfo(connectorId, status, errorCode)`,
   `AuthorizeRequest(idToken, certificate?)`. Then make the **existing 1.6 `InboundCoreHandler`
   translate 1.6 wire types → these neutral objects** before calling the listener. This is a pure
   refactor: behaviour identical, 1.6 tests stay green. Do this first and in isolation.
2. **Add the dependency.** Add `eu.chargetime.ocpp:ocpp2:2.0` to `pom.xml` and to the bnd
   `<bnd.includeresource>` embed list (mirror how `ocpp16`/`ocpp-json`/`ocpp-common` are embedded).
   Confirm the bundle still resolves in the Karaf feature verification.
3. **Negotiate both subprotocols.** In `ChargeTimeTransport` (or a sibling), move to the library's
   **`MultiProtocolJSONServer`** and advertise `ocpp1.6` **and** `ocpp2.0.1`. Route each session to the
   handler set for its negotiated subprotocol. Keep the 1.6 handler set exactly as is.
4. **Write the `v201` inbound handler.** Implement the 2.0.1 server event handlers and translate to the
   **same neutral events** the 1.6 path now emits:
   - `BootNotification` (2.0.1 shape) → `onBoot`.
   - **`TransactionEvent`** → map `Started` → start, `Ended` → stop, `Updated` → meter sample. This one
     message covers what 1.6 split across Start/Stop/MeterValues/StatusNotification.
   - `StatusNotification` (2.0.1) → `onStatus`.
   - `Authorize` (with `idToken`, optional cert) → `onAuthorize` / `isTagAuthorized`.
   - `Heartbeat`.
   Answer each with a spec-valid 2.0.1 confirmation. Wrap every listener call in a try/catch so a
   downstream throw never starves the confirmation (the 1.6 handler already does this — copy the
   pattern).
5. **Device model for config + capabilities.** For 2.0.1 sessions, use
   `GetBaseReport`/`GetReport`/`GetVariables`/`SetVariables` instead of `GetConfiguration`/
   `ChangeConfiguration`. Build a small device-model reader that fills the same neutral
   `ChargerCapabilities` the rest of the binding consumes (e.g. read `SmartChargingCtrlr.Available`,
   `Phases3to1`, `ACPhaseSwitchingSupported`). This replaces the 1.6 `SupportedFeatureProfiles` guesswork.
6. **Outbound commands.** For a 2.0.1 session, emit `RequestStartTransaction` /
   `RequestStopTransaction`, and `SetChargingProfile` in 2.0.1 shape (`evseId`, schedule array,
   `phaseToUse` when single-phase). Gate on the negotiated version at the point of send.
7. **Everything above the seam is untouched.** The bridge/CPMS/channels/Thing model consume the neutral
   events — they work for 2.0.1 for free once steps 1 and 4 are done. The 2.0.1 EVSE/connector
   hierarchy maps onto the existing `chargepoint`/`connector` Things (EVSE id → connector).
8. **Tests.** Unit-test the `v201` translation (2.0.1 payloads → neutral events) the way the 1.6
   transport is tested (`ChargeTimeTransportTest`, `InboundCoreHandlerTest`). Keep the 1.6 tests green.

Milestone order that de-risks: (1) seam neutralised + 1.6 green → (2) 2.0.1 boot + a charge session
end-to-end on the Alfen → (3) metering + charging profile → (4) device-model config → (5) security /
Plug & Charge (only if the Alfen and the demand call for it).

---

## 7. The Alfen — configuring and testing

Alfen's **Eve / NG** platform speaks OCPP **1.6-J and 2.0.1** (newer firmware advertises 2.1). This is
your live proving ground.

- **Point the charger at the binding.** In the Alfen configuration (ACE Service Installer, or the
  charger's web/管理 interface): set the OCPP backend URL to
  `ws://<openhab-host>:8887/<chargePointId>` (or `wss://…` for TLS), and select the OCPP version. The
  **`<chargePointId>` is the URL path** — the binding matches the `chargepoint` Thing by it. Alfen
  typically uses its serial or a configured identity.
- **Start on 1.6 to confirm the baseline.** Bring the Alfen up on 1.6-J first and verify the *existing*
  stack (boot → StatusNotification → a transaction → MeterValues → charge-limit). This proves the wire
  path before you add 2.0.1.
- **Then switch the Alfen to 2.0.1** and verify, in order: subprotocol negotiated as `ocpp2.0.1`,
  BootNotification accepted, a `TransactionEvent(Started)`/`Ended` round-trip logged as a session with
  energy, StatusNotification reflected on the connector, and `SetChargingProfile` applied.
- **Security:** if the Alfen wants a security profile, the binding already has `authPassword` (HTTP
  Basic, profile 1) and `tlsKeystorePath`/`tlsKeystorePassword` (TLS, profile 2) on the `server`
  Thing — reuse them for 2.0.1; do not invent a parallel config.
- **Watch for charger-side rejections**, and diagnose them as charger-side. Alfen honours smart
  charging on hardware with the contactor, but a rejection on 2.0.1 is the charger's choice, not a
  binding bug.
- **Enable debug logging:** `log:set DEBUG org.openhab.binding.ocpp` in the Karaf console; watch the
  negotiated subprotocol and the raw CALLs.

---

## 8. Conventions you must follow

- **Comments:** minimal — only where the code cannot speak for itself: a library quirk, an OCPP
  protocol constraint, an openHAB framework quirk, vendor/charger behaviour, or a genuine edge case.
  No narration of the next line. Target ~5% density; a reviewer flagged "AI slop" on this binding
  before, so keep it terse. Reviewer rationale goes in the **commit message**, not the code.
- **PR style (openHAB / maintainer `@splatch`, `@lsiepel`):** terse, user-voice PR bodies; one tight
  *why* paragraph; no "What changes" section; no AI-attribution footer in the body (that lives in the
  commit trailer only); target `main`.
- **Explicit go for every public action** — pushing a branch, opening/updating a PR, posting a comment.
  Prepare it, show the exact command, wait for the owner's "go".
- **2.0.1 is a separate future contribution** — keep it (and the CPMS) **off** the lean 1.6 PR #21265.
- Build locally (`mvn … install`) and keep it green **before** every commit; the build is the gate.

---

## 9. Reference anchors

- OCPP 2.0.1 schemas (OCA-tagged, easy to read): `mobilityhouse/ocpp` repo, `v201/schemas/` —
  `TransactionEventRequest.json`, `SetChargingProfileRequest.json`, `GetBaseReportRequest.json`,
  `GetVariablesRequest.json`, `enums.py`.
- OCA Part 2 appendices (device model, primary source).
- Library: `central.sonatype.com/artifact/eu.chargetime.ocpp/ocpp2`,
  `repo1.maven.org/maven2/eu/chargetime/ocpp/`, `ChargeTimeEU/Java-OCA-OCPP` (PR #239, `v2.0` release,
  `MultiProtocol*` server classes).
- Structural precedent for a dual-stack CSMS: `mobilityhouse/ocpp` (Python), `lorenzodonini/ocpp-go`
  (Go) — both keep 1.6 and 2.0.1 as parallel handler sets dispatched on the negotiated subprotocol.

---

*This binding's current state on `ocpp-cpms`: OCPP 1.6-J central system, 167 tests green, deployed and
running against Phoenix CHARX + Wallbox chargers, plus a CPMS layer (users/cards/caps/usage). The
1.6-J core is under upstream review as PR #21265 (`ocpp-initial-contribution`). Your 2.0.1 work builds
on `ocpp-cpms` and is a separate, later contribution.*
