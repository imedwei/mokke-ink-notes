#!/usr/bin/env bash
# Bind + attach a USB device through usbipd-win into this WSL distro.
# Usage: ./scripts/usbipd-attach.sh [BUSID]    (default: 8-3, the Palma 2 Pro)
#
# Bind requires admin (one-time per host reboot — UAC prompts the first time
# only). Attach runs as the current user. Both are invoked through the WSL
# interop layer (usbipd.exe / powershell.exe).
set -euo pipefail

BUSID="${1:-8-3}"

if ! command -v usbipd.exe >/dev/null 2>&1; then
  echo "ERROR: usbipd.exe not on PATH from WSL — install usbipd-win on the host?" >&2
  exit 1
fi

adb_sees_device() {
  # Wrap in `timeout` because adb can hang indefinitely when the kernel-side
  # usbip state is wedged. A hung adb means we want to fall through to the
  # detach/attach recovery path, not freeze the script.
  timeout 5 adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .
}

# Fast path: if adb already sees a device, the bind+attach work is done.
if adb_sees_device; then
  echo "✓ already attached"
  adb devices
  exit 0
fi

# usbipd.exe writes CRLF; strip \r before parsing.
state_line=$(usbipd.exe list 2>/dev/null | tr -d '\r' | awk -v id="$BUSID" '$1 == id')
if [[ -z "$state_line" ]]; then
  echo "ERROR: busid $BUSID not present in 'usbipd.exe list'. Plug device in?" >&2
  usbipd.exe list 2>/dev/null | tr -d '\r' >&2
  exit 1
fi
echo "current: $state_line"

# State strings: "Not shared" / "Shared" / "Attached" / "Persisted".
# Bind only needed when "Not shared". Bind persists across attaches but resets
# at host reboot.
if echo "$state_line" | grep -qi "Not shared"; then
  echo "→ Binding $BUSID (UAC prompt will pop on Windows — click Yes)"
  if ! powershell.exe -NoProfile -Command \
      "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-Command','usbipd bind --busid ${BUSID}'"; then
    echo "ERROR: elevated bind failed (UAC denied or usbipd error)" >&2
    exit 1
  fi
  echo "✓ bound"
fi

# Attach runs as the current user (NOT admin) — admin context attaches to the
# wrong WSL instance.
attach_once() {
  echo "→ Attaching $BUSID to WSL"
  usbipd.exe attach --wsl --busid "$BUSID" 2>&1
}

attach_out=$(attach_once || true)
echo "$attach_out"

# "Device busy (exported)" means usbipd thinks the device is already attached
# elsewhere — usbipd's state can lag. Detach (no admin) and retry once.
if echo "$attach_out" | grep -qi "Device busy"; then
  echo "→ Detaching stale attachment and retrying"
  usbipd.exe detach --busid "$BUSID" 2>&1 || true
  attach_out=$(attach_once || true)
  echo "$attach_out"
fi

if echo "$attach_out" | grep -qi "^usbipd: error"; then
  echo "ERROR: attach failed" >&2
  exit 1
fi

echo "→ Waiting up to 15s for adb to see the device"
for _ in $(seq 1 15); do
  if adb_sees_device; then
    adb devices
    exit 0
  fi
  sleep 1
done

echo "ERROR: device did not appear in 'adb devices' within 15s" >&2
adb devices >&2
exit 1
