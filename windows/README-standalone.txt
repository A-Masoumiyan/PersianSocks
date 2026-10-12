PersianSocks Windows 0.4.0 — self-contained EXE (Windows x64).

Run PersianSocks.exe. Approve the Windows Administrator prompt needed for TUN routing.
The embedded sing-box/Wintun runtime is verified and extracted on first connection.

Android 0.18 feature parity:
- Right sidebar with Connection, Scan, Sources/Proxies, Tools/Reports groups.
- 72px cards: three columns at 1366x768 (15 full cards visible), four on large screens.
- Full/service/custom-target filters; HTTP+HTTPS grouping; strict ping boundaries;
  ping/service/recent/UDP sorting, plus speed sorting.
- Separate scan types, Max, slow mode, resume, healthy rescan, 20-fastest refresh,
  cancellable collection, cancellable per-proxy connection/ping/speed tests.
- 23 sources with fallbacks, 20 custom sources, 8 named HTTPS test destinations,
  manual/text import, local country database and offline SVG country flags.
- Per-process split tunneling: all, only selected, all except selected. Includes
  installed/active app search, system-app toggle, retained hidden selections, file picker.
  Changes apply next connection. Uninstalled files are skipped; empty only mode fails.
- Live engine traffic rates and totals; tray quick controls and floating desktop widget.
  Closing the native main window hides it to the tray by default. The persistent
  Exit-on-close setting performs full shutdown. Tray open restores the same HWND.
- Failover checks the actual core proxy path twice, chooses up to eight filtered
  candidates with a five-minute cooldown, and waits for a physical adapter when offline.
- Scan remains bound to physical NIC/DNS while VPN is active.

Validation: local Go tests with race detection, real TLS fixtures, engine config check
for all three proxy types and all three split modes, runtime extraction/repair,
50,000-result persistence, browser UI interactions and 800/1366/1920px layout passed.
The three native engine-forwarding cases are explicitly skipped in this Linux sandbox
because creating its netlink socket is denied. A separate Windows Server 2025 CI run passed all 20 tests, including native quick-window
creation and real engine forwarding through SOCKS5/HTTP/HTTPS. System-wide Wintun and
actual per-application routing remain unverified.
https://github.com/A-Masoumiyan/PersianSocks/actions/runs/38317551801
UDP marks SOCKS5 UDP ASSOCIATE acceptance, not guaranteed end-to-end UDP forwarding.
Traffic counts flows recorded by the engine, including captured direct routes.

Build: Go 1.25+ and PowerShell; run build.ps1. It verifies pinned official downloads,
bundles the runtime/flags/licenses, then builds a Windows GUI EXE. Source package omits
binary runtime.zip; build.ps1 recreates it. Controller MIT; sing-box GPL-3.0-or-later;
Wintun and flag-icons licenses and notices are bundled.
https://github.com/SagerNet/sing-box/tree/v1.14.3
https://github.com/lipis/flag-icons/tree/v7.5.0

Windows 0.4.0:
Native Win32/WebView2 main window, dark title bar and tray popup. Requires Microsoft
WebView2 Runtime (normally present on Windows 11); install the Microsoft runtime if
missing. The connection engine and Wintun are still embedded in the standalone EXE.
Batch speed tests freeze ALL filtered results across pages, show bytes/failures and
completed counts, preserve healthy proxies on errors, and use one sequential worker.
Stop batch cancels its own task; independent proxy tests continue. Each downloads at
most 20 MB. Proxy context menus support keyboard access and connect/select together.
Additional native Windows tests exercise hide/restore/exit. The built EXE smoke test
checks the local controller, single-instance restoration and persisted close policy.
Auto Android builds are disabled; its workflow remains manually available.
