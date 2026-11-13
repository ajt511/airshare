# AirShare fork — build notes

## What this fork is

AirShare（隔空互传）is a fork of HotKids/EasyShare (MIT, which itself
derives from kmod-midori/CatShare) that adds:

- Air-gesture transfer (MediaPipe Hand Landmarker): open palm → fist grab →
  tilt to pick a target → open palm to release. See `gesture/`, `vision/`,
  `GestureTransferActivity`.
- A LAN transfer channel (UDP discovery on 48888 + HTTP on 48889), reusing
  the existing incoming-transfer UI coordinator. See `lan/`.
- Vendor adaptation entries (MIUI/ColorOS autostart, battery exemption).
  See `compat/VendorCompat.kt`.

The Share-Alliance protocol engine (BLE GATT + WiFi Direct + Ktor/Netty) is
upstream code and should stay untouched unless fixing a bug.

## MediaPipe model asset

`app/src/main/assets/hand_landmarker.task` is not committed (≈7.5 MB). It is
downloaded by CI before building, and `ensureHandModel` fails the build with
the download command when missing. For local builds:

```sh
curl -fL -o app/src/main/assets/hand_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
```

## APK signing

- CI signs APKs with the official `SIGNING_*` secrets when present.
- When those secrets are absent (typical for user forks), the workflow
  generates an **ephemeral keystore for that build**; the APK is still
  R8-processed and `apksigner verify`-checked. Updates installed over a
  previous ephemeral-signed build require uninstalling first.
- Never package unsigned APKs and never add a second packaging path; keep
  Gradle ABI splits as upstream. Unit tests, lint and R8 run without
  signing inputs.
