#!/usr/bin/env python3
"""Bundled libmpv audio policy probe: generated media, null/file AOs, no hardware/display.

Usage: python3 audio_passthrough_test.py /path/to/private/runtime [recovery_probe.so]
This exercises libmpv itself, not Nuvio's JNI/window creation or a physical receiver.
Omit the probe to reproduce the original unadapted libmpv failures. The probe
calls the production recovery helper, not a Python reimplementation of recovery.
"""
import base64
import ctypes as C
import os
from pathlib import Path
import sys
import tempfile
import time
import wave
import zlib


class Event(C.Structure):
    _fields_ = [("event_id", C.c_int), ("error", C.c_int),
                ("reply_userdata", C.c_uint64), ("data", C.c_void_p)]


class Log(C.Structure):
    _fields_ = [("prefix", C.c_char_p), ("level", C.c_char_p), ("text", C.c_char_p),
                ("log_level", C.c_int)]


class End(C.Structure):
    _fields_ = [("reason", C.c_int), ("error", C.c_int)]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    require(not any(os.environ.get(k) for k in
                    ("DISPLAY", "WAYLAND_DISPLAY", "NUVIO_RUN_LIVE_DISPLAY_TESTS")),
            "Unset DISPLAY, WAYLAND_DISPLAY and NUVIO_RUN_LIVE_DISPLAY_TESTS")
    runtime = Path(sys.argv[1]).resolve(strict=True)
    lib = C.CDLL(str(runtime / "lib/libmpv.so.2"))
    lib.mpv_create.restype = C.c_void_p
    lib.mpv_terminate_destroy.argtypes = [C.c_void_p]
    lib.mpv_set_option_string.argtypes = [C.c_void_p, C.c_char_p, C.c_char_p]
    lib.mpv_initialize.argtypes = [C.c_void_p]
    lib.mpv_request_log_messages.argtypes = [C.c_void_p, C.c_char_p]
    lib.mpv_wait_event.argtypes = [C.c_void_p, C.c_double]
    lib.mpv_wait_event.restype = C.POINTER(Event)
    lib.mpv_command.argtypes = [C.c_void_p, C.POINTER(C.c_char_p)]
    lib.mpv_get_property_string.argtypes = [C.c_void_p, C.c_char_p]
    lib.mpv_get_property_string.restype = C.c_void_p
    lib.mpv_free.argtypes = [C.c_void_p]
    probe = C.CDLL(str(Path(sys.argv[2]).resolve(strict=True))) if len(sys.argv) > 2 else None
    if probe:
        probe.audio_recovery_create.restype = C.c_void_p
        probe.audio_recovery_destroy.argtypes = [C.c_void_p]
        probe.audio_recovery_accept.argtypes = [C.c_void_p, C.c_void_p, C.POINTER(Event)]
    codecs = "ac3,dts,eac3,truehd,dts-hd,dts-hd-ma"

    def property_string(handle, name):
        value = lib.mpv_get_property_string(handle, name.encode())
        require(bool(value), "Missing property " + name)
        try:
            return C.string_at(value).decode()
        finally:
            lib.mpv_free(value)

    def play(source, ao, passthrough, fallback, pcm_only=False, output_file=None, expect_recovery=False):
        # No default/empty AO entry: libmpv cannot probe ALSA/Pulse/PipeWire.
        require(ao in ("null", "pcm"), "Unsafe AO")
        handle = lib.mpv_create()
        require(bool(handle), "mpv_create")
        logs = []
        recovery = probe.audio_recovery_create() if probe else None
        recoveries = 0
        try:
            options = {"config": "no", "load-scripts": "no", "ytdl": "no", "terminal": "no",
                       "vo": "null", "hwdec": "no", "ao": ao, "idle": "yes",
                       "audio-fallback-to-null": "yes" if fallback else "no"}
            if passthrough:
                options["audio-spdif"] = codecs
            if pcm_only:
                options["ao-null-format"] = "s16"
            if output_file is not None:
                options["ao-pcm-file"] = str(output_file)
            for name, value in options.items():
                require(lib.mpv_set_option_string(handle, name.encode(), value.encode()) >= 0,
                        "Rejected libmpv option: " + name)
            require(lib.mpv_initialize(handle) >= 0, "mpv_initialize")
            require("0.41" in property_string(handle, "mpv-version"), "Expected bundled mpv 0.41")
            require(property_string(handle, "audio-spdif") == (codecs if passthrough else ""),
                    "Passthrough option changed during initialization")
            require(lib.mpv_request_log_messages(handle, b"v") >= 0, "request fixture logs")
            command = (C.c_char_p * 3)(b"loadfile", str(source).encode(), None)
            require(lib.mpv_command(handle, command) >= 0, "loadfile")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                pointer = lib.mpv_wait_event(handle, 0.1)
                if probe:
                    result = probe.audio_recovery_accept(recovery, handle, pointer)
                    require(result >= 0, f"Production recovery failed: {result}")
                    recoveries += result
                event = pointer.contents
                if event.event_id == 2:  # MPV_EVENT_LOG_MESSAGE
                    log = C.cast(event.data, C.POINTER(Log)).contents
                    logs.append(log.text.decode(errors="replace"))
                elif event.event_id == 7:  # MPV_EVENT_END_FILE
                    end = C.cast(event.data, C.POINTER(End)).contents
                    # The native override is deliberately local to this handle/attempt.
                    # Persisted user settings are independently checked by the JVM tests.
                    expected = codecs if passthrough and not recoveries else ""
                    require(property_string(handle, "audio-spdif") == expected,
                            "Unexpected per-attempt runtime override")
                    if probe:
                        require(recoveries == int(expect_recovery), f"Unexpected recovery count: {recoveries}")
                    return end.reason, end.error, "".join(logs)
            raise AssertionError("Timed out waiting for local media EOF/error:\n" + "".join(logs))
        finally:
            if recovery:
                probe.audio_recovery_destroy(recovery)
            lib.mpv_terminate_destroy(handle)

    with tempfile.TemporaryDirectory(prefix="nuvio-audio-policy-") as directory:
        root = Path(directory)
        wav = root / "silence.wav"
        ac3 = root / "silence.ac3"
        with wave.open(str(wav), "wb") as output:
            output.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
            output.writeframes(bytes(48000 * 2 * 2))
        # One generated silent stereo/48kHz AC3 frame, repeated for about a second.
        # Generated locally with ffmpeg anullsrc and -frames:a 1 -c:a ac3; no external
        # media or encoder dependency (the private playback runtime omits AC3 encoding).
        frame = zlib.decompress(base64.b64decode(
            "eJzjLn/PzuPg/JDtAyMHB4ciIyNLuLxdzc/Pz8/Pt6v5/a8Uxvzxf248TNzov9zZ2QoMICBnvRsEGhgQgDc7"
            "6hqYXw4GSDIMPBCK+ThYpgBJhnFtdtQFEIPvHQg8QJaCGl4BlnmHbJ7p2uwosJK7IHAHWcoAQvHLgqUOIMmw"
            "bV2b7QBiYDqdDWrjY7DMDmTzrm1dmwGi2TGczgA13AbTv1LXtq4FMzCdDgFB27hHwx9JCmojHcMfAPktxX0="
        ))
        require(len(frame) == 768 and frame[:2] == b"\x0b\x77", "Invalid AC3 fixture")
        ac3.write_bytes(frame * 32)

        failed_output = root / "missing" / "output.wav"
        cases = [
            ("disabled passthrough decodes AC3 to null output", ac3, "null", False, False, {},
             lambda reason, error, logs: reason == 0 and error == 0
             and "spdif_ac3" not in logs and "AO: [null]" in logs),
            ("enabled options accepted; SPDIF reaches null AO", ac3, "null", True, True, {},
             lambda reason, error, logs: reason == 0 and error == 0
             and "spdif_ac3" in logs and "AO: [null]" in logs),
            ("unlisted PCM codec decodes with passthrough enabled", wav, "null", True, True, {},
             lambda reason, error, logs: reason == 0 and error == 0
             and "spdif_ac3" not in logs and "AO: [null]" in logs),
            ("unrecovered output error propagates AO_INIT_FAILED", wav, "pcm", False, False,
             {"output_file": failed_output},
             lambda reason, error, logs: reason == 4 and error == -14),
            ("PCM output-open failure falls back to null", wav, "pcm", True, True,
             {"output_file": failed_output},
             lambda reason, error, logs: reason == 0 and error == 0
             and "spdif_ac3" not in logs and "AO: [null]" in logs),
            # No audio device is queried: the file AO cannot create a file in a missing directory.
            ("output-open failure recovers through PCM then null", ac3, "pcm", True, True,
             {"output_file": failed_output, "expect_recovery": True},
             lambda reason, error, logs: reason == 0 and error == 0
             and "Falling back to PCM output" in logs and "AO: [null]" in logs),
            ("rejected SPDIF format recovers as PCM", ac3, "null", True, True,
             {"pcm_only": True, "expect_recovery": True},
             lambda reason, error, logs: reason == 0 and error == 0
             and "Passthrough format unsupported" in logs
             and "Falling back to PCM output" in logs and "AO: [null]" in logs),
        ]
        if probe:
            cases.append(("failed PCM recovery preserves AO_INIT_FAILED without retrying", ac3, "pcm", True, False,
                          {"output_file": failed_output, "expect_recovery": True},
                          lambda reason, error, logs: reason == 4 and error == -14))
        failed = []
        for name, source, ao, passthrough, fallback, extra, verify in cases:
            try:
                reason, error, logs = play(source, ao, passthrough, fallback, **extra)
                require(verify(reason, error, logs), f"Unexpected {reason}/{error}:\n{logs}")
                print("PASS " + name, flush=True)
            except AssertionError as error:
                failed.append(name)
                print("FAIL " + name + ": " + str(error), flush=True)
        print(f"{len(cases) - len(failed)} passed, {len(failed)} failed headless libmpv audio groups", flush=True)
        require(not failed, "Audio recovery regressions: " + "; ".join(failed))

if __name__ == "__main__":
    main()
