package main

import "context"

// Platform helpers (network detection, app discovery and file picker) share the
// process lifetime so full exit also closes their PowerShell child processes.
var platformCommandContext, stopPlatformCommands = context.WithCancel(context.Background())
