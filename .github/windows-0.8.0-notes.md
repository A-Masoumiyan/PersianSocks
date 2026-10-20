# PersianSocks Windows 0.8.0

- Kill switch defaults ON, including when upgrading from older settings. Controls are beside Connect and at the top of the sidebar; protection readiness and firewall errors are shown separately from the setting.
- Native WFP IPv4 and IPv6 outbound blocks exist independently of sing-box. Only the controller, verified core transport, loopback IPC, DHCP, and traffic entering the protected TUN are allowed. Replacements install new blocks before old policies are removed. Non-dynamic filters survive unexpected controller-session loss, but are removed on explicit app exit or explicit OFF. They do not survive reboot/BFE restart; this app must be running before relying on protection.
- While disconnected, a guard core rejects the selected app/domain routes and allows unrelated routes directly. During engine restart, the independent firewall fails closed for ordinary applications. For all-system routing, ordinary Internet remains blocked until VPN connects or the switch is disabled.
- ChatGPT-only protects the app, configured related domains, and the shared WebView2 runtime. Shared WebView2 requests outside the ChatGPT domain list are rejected instead of silently going direct. This can affect other applications that use the same WebView2 runtime. Requests to the same configured domains from a browser are also covered.
- OFF and full Exit explicitly remove protection; minimizing/hiding to the tray keeps it. This is a network-routing control, not a guarantee of anonymity, account privacy, WebRTC behavior, unknown OS components, alternate proxies, new dependencies, or newly introduced domains. App/browser caches may require restarting the target app.

Validation required before publication:
- Configuration validation with the pinned engine for normal and guard modes.
- Windows child-process TCP/UDP probes over both IPv4 and IPv6; blocked origins must receive zero requests, and explicit OFF must restore the same probes.
- A pre-existing TCP stream must stop after WFP policy activation.
- Protection must survive closing/reopening the firewall owner's non-dynamic session.
- Real isolated Wintun app/domain/ChatGPT and guard-mode routes: protected traffic proxied or rejected as appropriate, unrelated traffic direct, and direct fallback blocked after forced core termination.
- Actual EXE window smoke runs with kill-switch enforcement disabled in its isolated test profile so it cannot disrupt the CI runner's control connection. WFP tests restrict identical production block rules to isolated probe EXEs for the same reason; unrestricted whole-computer policy is not activated on the hosted runner.

Sources: https://learn.microsoft.com/en-us/windows/win32/fwp/object-management and https://github.com/tailscale/wf/tree/6fbb0a674ee6. Dependencies' BSD licenses are included in the embedded runtime.
