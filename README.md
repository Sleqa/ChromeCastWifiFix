# ChromeCastWifiFix

An Android TV app for Chromecast / Google TV devices whose **Wi-Fi toggle switches
itself off**. Not a dropout, not a roaming failure — the radio is switched off and
the device sits there offline until someone picks up the remote.

The app runs a small foreground service that watches the radio and turns it back
on, logging every outage so you can find out how often the bug actually fires.

> **Why this has to run on the device.** Once Wi-Fi is off, nothing off-device can
> reach the Chromecast — no cloud service, no push, no cron job on a PC running
> `adb shell svc wifi enable`. The repair has to be local.

---

## Before you install this, check two settings

Either of these could be the real cause, and would make the app unnecessary:

1. **Settings → System → Energy Saver.** If there is anything like "turn off Wi-Fi
   when idle / asleep", turn it off.
2. **Low Power Standby** (Android 13+). On TV hardware this subsystem can cut
   networking during standby:
   ```
   adb shell settings get global low_power_standby_enabled
   ```
   The app shows this value on its Diagnostics panel, so you can correlate it
   against the event log either way.

---

## How it works

`WifiManager.setWifiEnabled()` was deprecated in Android 10 and returns `false`
for ordinary apps. AOSP's `WifiServiceImpl.setWifiEnabled()` still permits it for
four kinds of caller, and exactly one of them is reachable by a sideloaded app:

| Caller | Reachable here? |
| --- | --- |
| Privileged/system app (`NETWORK_SETTINGS`) | No — signature-level, `pm grant` cannot grant it |
| Device owner / profile owner | Only after a factory reset, see below |
| **App whose `targetSdkVersion` is below 29** | **Yes — this is what the app uses** |
| Shell UID (`adb shell cmd wifi set-wifi-enabled enabled`) | Needs a shell bridge; not implemented |

So the app is built with `compileSdk 35` but **`targetSdk 28`**. There is no Play
Store `targetSdk` floor to worry about because this is never published there —
it is sideloaded.

Targeting 28 pays for itself four more times over, because every one of these
Android restrictions is keyed on `targetSdk`:

| Restriction | Applies from | Here |
| --- | --- | --- |
| `ForegroundServiceStartNotAllowedException` | targetSdk 31 | exempt — the boot receiver can start the service |
| `SCHEDULE_EXACT_ALARM` for `setExactAndAllowWhileIdle` | targetSdk 31 | exempt — the standby watchdog needs no permission |
| Mandatory `foregroundServiceType` | targetSdk 34 | exempt |
| `POST_NOTIFICATIONS` runtime prompt | targetSdk 33 | exempt |

**Do not raise `targetSdk`.** At 29 or above the Wi-Fi call silently returns
`false` and the app becomes a no-op. `app/build.gradle.kts` says so too.

### `setWifiEnabled` returning `true` does not mean Wi-Fi came on

Since Android 13 there is a branch aimed squarely at legacy-targetSdk apps like
this one: it posts a user confirmation dialog and returns `true` **without
touching the radio**. So the watchdog never trusts the return value. Success is
defined as *observing* `WIFI_STATE_ENABLED` within 15 seconds; anything else is a
failure and gets retried.

### What it does when it sees Wi-Fi go off

1. **Debounce** 3 s — a radio that recovers on its own is left alone, and
   `WIFI_STATE_DISABLING` is ignored entirely.
2. **Attempt** the first available repair tier.
3. **Confirm** by waiting for `WIFI_STATE_ENABLED`, up to 15 s.
4. **Back off** on failure: 0s → 5s → 15s → 45s → 2m → 5m → 10m → 15m.
5. **Escalate** to the next available tier after two failures on the current one.
6. **Give up** after 8 attempts and raise an alert rather than hammering forever.
7. **Reset** the counters once Wi-Fi has been continuously on for 60 s.

**Auto-snooze.** The app cannot tell "the bug turned Wi-Fi off" from "you turned
it off on purpose". If it repairs three times inside two minutes it stops fighting
and pauses for ten minutes. There is also a manual snooze (30 min / 2 h / until
reboot) on the status screen.

---

## Install

Grab the APK from the latest [Actions run](../../actions) (artifact
`chromecastwififix-apk`) or from a release, then:

```bash
adb connect <chromecast-ip>:<port>     # accept the prompt on the TV
adb install -r app-release.apk
```

Open **Wi-Fi Fix** from the Google TV home screen. The watchdog starts on launch
and again on every boot.

> On an Android 15+ host, `adb install` may refuse a low-targetSdk APK. Use
> `adb install --bypass-low-target-sdk-block -r app-release.apk`.

### First thing to do: press **Check capability**

This calls `setWifiEnabled(true)` while Wi-Fi is **already on**, so it changes
nothing and can never leave you offline. It tells you whether the framework lets
this app make the call at all:

- *Accepted* — Tier 1 works. (Hedged deliberately: on Android 13+ an accepted call
  can still mean a dialog was posted, which is why the watchdog waits for the
  radio regardless.)
- *Rejected* — Tier 1 is closed on this device. Check the Diagnostics panel:
  `targetSdk` must read 28 and the `change_wifi_state` app-op must read `ALLOWED`.

**Test now** is the end-to-end proof: it switches Wi-Fi off on purpose and lets
the watchdog recover it. It arms an independent one-minute alarm first that forces
the radio back on regardless of what the state machine does, so a bug in the
watchdog cannot strand the device. It is still the one button that can cost you a
trip to the remote — the confirmation dialog says so.

---

## Diagnostics panel

| Line | What it tells you |
| --- | --- |
| `targetSdk` | Must be under 29 or the Wi-Fi call is rejected outright |
| `change_wifi_state app-op` | Can be set to `IGNORED` independently of the permission grant, which produces an otherwise inexplicable `false` |
| `Airplane mode` | The framework rejects `setWifiEnabled` in airplane mode; the watchdog pauses instead of burning its retry budget |
| `low_power_standby_enabled` | Prime suspect for a TV dongle whose radio switches itself off |
| `Device owner` | Whether Tier 2 is provisioned |

---

## Things that do not work, and why

**Writing `Settings.Global.WIFI_ON` via `WRITE_SECURE_SETTINGS`.** This is the top
answer in every forum thread about this problem and it does nothing.
`WifiSettingsStore` only ever *writes* that key and only ever *reads* it at boot —
there is no observer on it that drives the radio. Setting it to `1` while the
radio is off just leaves the framework's persisted state lying, which can make the
next boot come up confused. The app deliberately does not do this.

**`adb shell pm grant … NETWORK_SETTINGS`.** Impossible. It is a signature-level
permission, not a runtime one, so `pm grant` cannot grant it.

**Holding a `WifiLock`.** A Wi-Fi lock stops the framework putting the *chip* into
power-save mode. It has no bearing on whether the radio is *toggled off*, which
happens several layers above. Not implemented, on purpose.

---

## Tier 2: device owner (not recommended)

A device owner clears the permission check by a different branch, so it keeps
working even if the legacy `targetSdk` route is ever closed. The app ships the
receiver needed to provision it:

```bash
adb shell dpm set-device-owner com.sleqa.wififix/.admin.WifiFixAdminReceiver
```

Two reasons not to bother:

- It fails with *"there are already some accounts on the device"* on any signed-in
  Google TV. The only route is factory reset → sideload → provision → *then* sign
  in.
- **Device owner cannot be removed without another factory reset.**

The app reports Tier 2 as unavailable and skips it when not provisioned, which
costs nothing.

---

## Building

```bash
./gradlew test            # the state machine, as a plain JVM test suite
./gradlew assembleRelease
```

CI builds on every push and uploads the APK as an artifact. With no signing
secrets configured the release build falls back to the debug key, so the workflow
always produces something installable.

For a stable signature (so `adb install -r` upgrades in place instead of demanding
an uninstall), create a keystore and add four repository secrets:

```bash
keytool -genkeypair -v -keystore release.jks -alias wififix \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks     # -> secret KEYSTORE_BASE64
```

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

> `app/build.gradle.kts` disables the `ExpiredTargetSdkVersion` lint check. That
> check is *fatal* and runs as part of `lintVitalRelease`, so without disabling it
> `assembleRelease` fails outright on the deliberate `targetSdk 28`.

---

## Verifying on the device

```bash
adb logcat -s WifiFix           # every event is mirrored here
adb shell cmd wifi set-wifi-enabled disabled    # simulate the bug
```

Expected: detected within ~1 s of the broadcast (or ~10 s via the poll safety
net), attempted after the 3 s debounce, confirmed a few seconds later, one row in
the event log. Disabling Wi-Fi kills your own ADB session — it coming back by
itself *is* the proof.

Also worth checking:

```bash
adb reboot                      # service should return without opening the app
adb install -r app-release.apk  # MY_PACKAGE_REPLACED should restart it
```

`adb shell am force-stop` is **not** a useful test: a force-stopped app has its
alarms cancelled and nothing restarts it until the next boot. That is expected
Android behaviour, not a bug in the watchdog.

**The Android TV emulator is not a useful target.** Its Wi-Fi is synthetic and
never exercises the vendor HAL or the driver — the layers where a radio-toggle bug
actually lives. Use it for checking the app appears in the leanback launcher and
that D-pad focus works, nothing more.

---

## Known limits

- Android TV has no notification shade in the phone sense, so the notifications
  are close to invisible on a Chromecast. The status screen and the event log are
  the real user-facing channel.
- If the radio is being switched off by a system policy that immediately switches
  it off again, the app will detect that pattern and back off rather than fight.
  The event log will show it.
