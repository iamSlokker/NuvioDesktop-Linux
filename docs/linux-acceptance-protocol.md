# Linux and Windows acceptance boundary

This protocol is a future test plan, not a record that these GUI or hardware tests
have run. The October 2026 engineering mission uses headless fixtures only.
The known three-format artifacts from [run 38397205286](https://github.com/iamSlokker/NuvioDesktop-Linux/actions/runs/38397205286)
contain `fce98dc1633b51f813c20965e05941c2745beb8c`, **not** the later `linux`
baseline `a96ce242f084a41ecf433c206958f1cbffa53345` or these audit changes.
Record the exact artifact SHA256, source revision, package format and test result
for each future acceptance run. Source/licensing and publication gates remain
separate; this plan authorizes no new artifact distribution.

## Evidence already available

- Owner-reported Bazzite acceptance covers the final login SVG in AppImage and
  Flatpak, account switching and selected playback/navigation behavior. It is
  not complete application, driver or desktop-environment acceptance.
- Linux headless updater tests exercise actual full HTTP download, SHA256
  verification, session-bound receipt and pre-install reverification, including
  tampering, copied/unverified archives, failed retries and legacy delta markers.
- `PortableUpdateStagingTest` exercises the actual private ZIP extractor, portable
  root detection, stale-file manifest calculation and helper generation with
  disposable files. It never executes `cmd.exe`, `robocopy`, Explorer or Nuvio.
  Generated-script ordering is not evidence that Windows locking/restart works.
- Settings tests cover credential stripping/merge, backup ZIP parsing,
  organization persistence and isolated account storage. They do not establish
  the GUI Backup → Import → Restart interaction or atomic rollback of every store
  after a mid-import disk failure.

## Disposable Bazzite session

Only run this section when live acceptance is explicitly scheduled. Use an
already verified, owner-authorized AppImage. Do not substitute the installed
Flatpak or copy any real profile into the test directory. Verify the AppImage
against the trusted producing run's checksum before making it executable.

```bash
appimage=/absolute/path/to/the-verified.AppImage
expected_sha256=REPLACE_WITH_TRUSTED_RUN_SHA256
[[ "$expected_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 1
printf '%s  %s\n' "$expected_sha256" "$appimage" | sha256sum -c - || exit 1
chmod u+x "$appimage"
test_profile=$(mktemp -d /tmp/nuvio-acceptance.XXXXXXXX)
mkdir -p "$test_profile"/{home,config,data,cache,state,tmp}
printf 'Disposable evidence/profile directory: %s\n' "$test_profile"
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  -u LD_PRELOAD -u LD_LIBRARY_PATH \
  HOME="$test_profile/home" \
  XDG_CONFIG_HOME="$test_profile/config" XDG_DATA_HOME="$test_profile/data" \
  XDG_CACHE_HOME="$test_profile/cache" XDG_STATE_HOME="$test_profile/state" \
  TMPDIR="$test_profile/tmp" \
  JAVA_TOOL_OPTIONS="-Duser.home=$test_profile/home -Dnuvio.desktop.dataDir=$test_profile/config/nuviohtpc" \
  "$appimage" >"$test_profile/launch.log" 2>&1
```

The active display/session variables are intentionally inherited for this
future manual test. No sandbox-disabling flag is permitted. Keep the printed
directory until results are reviewed; never use a cleanup command on an inferred
HOME or personal path. Inspect/redact logs before sharing, especially after a
test account is used. `JAVA_TOOL_OPTIONS` is inherited by a restarted JVM, and
`nuvio.desktop.dataDir` overrides the application's storage root.

For Flatpak acceptance, use a disposable VM/user with a separate Flatpak
installation and data directory. Merely changing `HOME` around `flatpak run`
is not sufficient evidence of sandbox data isolation. Never upgrade, uninstall,
override or run this test against the owner's installed
`com.nuvio.media.desktop` or `~/.var/app/com.nuvio.media.desktop`.

Record Bazzite release, GNOME/Mutter, XWayland and NVIDIA driver versions,
GPU/display model, session type and scaling. AMD/Intel or other desktops remain
unaccepted unless tested separately. The current player needs X11/XWayland; HDR
decoding and SDR tone mapping do not prove native HDR display output.

| Check | Steps and pass evidence |
| --- | --- |
| Login and profile isolation | Use only dedicated test accounts when account acceptance is separately approved. Verify official backend selection and login; switch test accounts/profiles and confirm unrelated settings/history do not cross. No real credentials in evidence. |
| Local video and aspect | Open a known 16:9 local test clip. Compare windowed and fullscreen geometry; no unexplained bars, video disappearance or incorrect stretching. Record dimensions and a screenshot. |
| Profiles and HUD | Switch Neutral → Cinematic → Vivid → Neutral; verify video stays visible, HUD layers clear, old thumbnail/timestamp pixels do not remain. Test seeking, pause/play and subtitles. |
| Lifecycle and navigation | Close the topmost panel/toast with Escape before leaving playback; then return once to the prior screen. Repeat windowed/fullscreen and after PiP entry/exit. Verify progress persistence, audio stops and process exits normally. |
| PiP and window changes | Resize/move, enter/exit PiP and fullscreen; confirm video/HUD/input ownership and final geometry. Do not infer native Wayland or multi-monitor HDR support. |
| Audio/subtitle acceptance | Use a disposable local subtitle fixture and test automatic synchronization, retiming and track selection. Passthrough/receiver recovery needs separate real receiver evidence; synthetic fixtures are insufficient. |
| Integrated snapshot features | Check Discover/Library filters, TMDB catalog grouping, backdrops, hero framing, Ambient Adaptive, Calendar, profile keyboard/gamepad navigation, Settings highlight/defaults and artwork prewarming. Local fixtures cover repositories; real provider/UI behavior is separately recorded. No new upstream snapshot is needed. |

## Settings Backup → Import → Restart — pending manual acceptance

The owner previously declined this additional manual flow. It remains pending;
completion of this mission does not depend on asking the owner to repeat it.
When a tester becomes available, use the disposable profile above, no imported
personal stores and no live account/API credentials:

1. Set visible non-secret values, for example theme and a Settings category
   title/order, and note them. Export a backup with credentials excluded into
   the disposable directory. Inspect ZIP names and confirm only expected stores
   and backup metadata are present.
2. Change those values, then import that same backup through the UI. Confirm the
   restart prompt, orderly exit, one relaunched instance, restored values and
   persisted values after a second normal exit/start.
3. In a fresh disposable profile, attempt malformed, empty and traversal-named
   fixtures. Confirm no external file is changed and an error is shown. Startup
   application failures must not retry forever; preserve any
   `pending-settings-import.failed.zip`, `.bak` files and log as evidence.
4. Credential-bearing behavior, if required, uses dummy offline values only.
   Confirm credential-free merge preserves local dummy keys and credential-full
   import replaces them as documented. Do not authenticate imported fake values
   against a service.

The current import stages changes for startup; per-file `.bak` recovery is not
a proven whole-backup transaction. Do not promise automatic rollback of an
interrupted multi-store import. Restore the entire disposable profile snapshot
when testing disk/permission failures, after all app processes have stopped.

## Windows updater — pending real Windows acceptance

Use a disposable Windows VM and portable app copies, never an installed app or
personal profile. A real Windows system is needed for batch parsing, file locks,
`tasklist`, `robocopy`, Explorer, process shutdown and relaunch. Linux headless
passes do not satisfy those checks.

Prerequisites: two authorized portable builds with recorded source identities
and trusted full-archive SHA256s; the starting build must include PR #3's integrity
correction and the reviewed stale-manifest safety correction. Obtain the expected
digest from the authenticated GitHub release asset metadata, not from a checksum
calculated solely from the downloaded bytes. Do not create a public release just
to execute this test. If there is no suitable authorized candidate, leave this
step pending. A maintainer-only harness may invoke the existing
`AppUpdaterPlatform.downloadApk` / `installDownloadedApk` in the disposable copy
with a trusted fixture URL/digest; report that as API acceptance, not release
catalog/UI acceptance. Do not bypass `requireCompleteZipForInstall`.

In that VM, set child-process environment variables before launch, for example:

```powershell
$testRoot = Join-Path $env:TEMP ('nuvio-acceptance-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$env:LOCALAPPDATA = Join-Path $testRoot 'LocalAppData'
$env:APPDATA = Join-Path $testRoot 'RoamingAppData'
$env:JAVA_TOOL_OPTIONS = "-Dnuvio.desktop.dataDir=`"$testRoot\profile`" -Duser.home=`"$testRoot\home`""
New-Item -ItemType Directory -Path $env:LOCALAPPDATA,$env:APPDATA,"$testRoot\profile","$testRoot\home" | Out-Null
# Extract the verified starting portable image to $testRoot\install; launch only that copy.
```

Clear inherited `JDK_JAVA_OPTIONS`/`_JAVA_OPTIONS` first if present in the VM.
Take a VM snapshot and copy the disposable install/profile before destructive
failure tests. The installed portable tree and update files must remain under
the test root.

| Case | Required observation |
| --- | --- |
| Verified full ZIP | Entire ZIP is downloaded without HTTP Range delta requests. Missing/malformed digest, wrong bytes, renamed legacy marker, copied unverified ZIP and post-download tampering are rejected before helper launch. |
| Normal replacement | Hold a file open in the running app naturally. Trigger update; helper waits for app PID exit, normal exit flushes disposable state, new bytes replace old bytes, exactly one new app instance launches with the new build identity. |
| Stale-file boundary | Include an old manifest-owned file and an unrelated user sentinel. Old owned file disappears, sentinel remains. Crafted forward/backslash traversal, rooted paths and Windows alias entries must not delete any outside sentinel. |
| Replacement failure | In the snapshot, hold a target file exclusively open or deny replacement. Confirm bounded robocopy retry/failure, logged error and updates-folder fallback; no false successful restart. The retained verified ZIP is available for manual recovery. |
| Manual fallback | Turn in-place update off or use a non-writable/non-ASCII unsupported batch path. Confirm the verified ZIP is revealed, no install is overwritten and no forced updater exit occurs. Manually extract only into a new disposable install. |
| Shutdown/restart | Verify progress/settings flushed and auxiliary player/P2P children stopped before replacement; delayed exit fallback does not produce two active instances. Restart inherits disposable data root. |

**Failure recovery is not atomic rollback.** Robocopy may replace some files
before a later copy fails. The helper opens the updates folder and avoids restart;
it does not restore the previous complete application image. Recover by stopping
the disposable copy and restoring its VM/install snapshot, or extracting the
verified full ZIP into a clean new directory. Preserve the original archive and
`apply-update.log`; never force an unverified marker or mixed install to launch.

## Recording outcomes

Each case records PASS / FAIL / NOT RUN, exact candidate SHA256/source, OS/runtime
details, evidence location and the tested scope. A failure is reproducible steps
plus logs/screenshots, not a blanket driver claim. Keep real Windows, receiver,
controller, settings-import GUI and HDR-output checks pending until actual
evidence exists. No package becomes redistribution-ready from runtime acceptance
alone; use the compliance report and final readiness report for separate gates.
