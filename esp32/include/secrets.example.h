#pragma once

// TEMPLATE -- copy this file to `secrets.h` (same directory) and fill in your
// real values.
//
// `secrets.h` is gitignored and never committed. It holds everything specific
// to one boat: the credentials, which are compiled straight into the firmware
// (a binary built from a real one carries the WiFi password and the password
// that authorises reflashing a board wired to machinery), and the addresses,
// which describe a network this repository has no business naming.
//
// Both this file and secrets.h are included by the three mains AND by
// config.h, each guarded by `#if __has_include("secrets.h")`, so a fresh clone
// falls back to these placeholders and still builds. It just will not join
// WiFi or find a server until you create secrets.h -- or configure both
// through the SensESP setup portal.

#define SECRET_OTA_PASSWORD "set-a-strong-ota-password"

// The address of each board, used ONLY by scripts/ota_auth.py at upload
// time -- never compiled in. An IP or an mDNS name both work. These used
// to be `upload_port` per env in platformio.ini; they live here so a public
// repository does not carry the boat's network layout.
#define SECRET_OTA_HOST_TX "tx-remote.local"
#define SECRET_OTA_HOST_RX "rx-remote.local"
#define SECRET_OTA_HOST_HH "hh-remote.local"

// The boat's signalk-server instance. Compiled into all three firmwares as
// the default, and still overridable per device through SensESP's web config
// UI. An IP or an mDNS name both work.
#define SECRET_SK_SERVER_ADDRESS "signalk.local"
#define SECRET_SK_SERVER_PORT 3000

// The boat's WiFi. Up to three networks; leave the unused pairs empty.
#define SECRET_WIFI_SSID "your-wifi-ssid"
#define SECRET_WIFI_PASSWORD "your-wifi-password"
#define SECRET_WIFI_SSID_2 ""
#define SECRET_WIFI_PASSWORD_2 ""
#define SECRET_WIFI_SSID_3 ""
#define SECRET_WIFI_PASSWORD_3 ""
