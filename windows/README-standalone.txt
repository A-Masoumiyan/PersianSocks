PersianSocks Windows 0.1.2 — self-contained EXE.
Run build.ps1 with Go 1.25 or later installed. It downloads and verifies official sing-box/Wintun, bundles the runtime, and builds PersianSocks.exe.
Bypass rules are restricted to the TUN inbound, so the local verification proxy cannot route through the direct bypass.
Windows TUN and actual Windows runtime execution have not been tested in this environment. Native core run is blocked here by netlink permissions; its three proxy-forwarding tests are SKIPPED, not runtime passes.
Controller is MIT; sing-box is GPL-3.0-or-later. https://github.com/SagerNet/sing-box/tree/v1.14.3
