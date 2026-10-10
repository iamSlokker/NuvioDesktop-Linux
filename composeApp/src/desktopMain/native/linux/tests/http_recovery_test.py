#!/usr/bin/env python3
"""Real bundled-libmpv range failure; loopback only, null outputs, no GTK/JNI/display."""
import http.server
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import threading


def main():
    binary = Path(sys.argv[1]).resolve(strict=True)
    data_size = 16000 * 2 * 180
    media = (b"RIFF" + struct.pack("<I", 36 + data_size) + b"WAVEfmt "
             + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
             + b"data" + struct.pack("<I", data_size) + bytes(data_size))
    rejected = []
    with tempfile.TemporaryDirectory(prefix="nuvio-http-recovery-") as temporary:
        arm_file = Path(temporary) / "armed"

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                if self.path != "/fixture.wav":
                    self.send_error(404)
                    return
                range_header = self.headers.get("Range")
                offset = int(range_header.removeprefix("bytes=").split("-", 1)[0]) if range_header else 0
                if arm_file.exists() and offset > 0:
                    rejected.append(offset)
                    self.send_response(429, "Too Many Requests")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(206 if range_header else 200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(media) - offset))
                if range_header:
                    self.send_header("Content-Range", f"bytes {offset}-{len(media) - 1}/{len(media)}")
                self.end_headers()
                self.connection.settimeout(2)
                try:
                    self.wfile.write(media[offset:])
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass  # mpv closes the previous range connection when seeking.

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        environment = os.environ.copy()
        for key in ("DISPLAY", "WAYLAND_DISPLAY", "NUVIO_RUN_LIVE_DISPLAY_TESTS"):
            environment.pop(key, None)
        try:
            result = subprocess.run(
                [str(binary), f"http://127.0.0.1:{server.server_port}/fixture.wav", str(arm_file)],
                env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15,
            )
            print(result.stdout, end="")
            if result.returncode:
                raise RuntimeError(f"native probe exited {result.returncode}")
            if not rejected:
                raise RuntimeError("probe did not make an actual rejected range request")
            print("PASS loopback fixture observed rejected range request")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    main()
