#!/bin/bash
# Starts a virtual display (headed Chromium gets far fewer bot checks than headless),
# a VNC view of it for solving bot checks / signing in, then the server.
set -euo pipefail
export DISPLAY=:99
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
Xvfb :99 -screen 0 1080x2200x24 -nolisten tcp &
for _ in $(seq 50); do [ -e /tmp/.X11-unix/X99 ] && break; sleep 0.1; done

if [ "${VNC:-1}" = "1" ]; then
  # Bound to localhost: reachable through `kubectl port-forward`, not from the rest of the cluster.
  x11vnc -display :99 -localhost -rfbport 5900 -forever -shared -nopw -quiet >/dev/null 2>&1 &
  websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900 >/dev/null 2>&1 &
fi

exec node src/index.js
