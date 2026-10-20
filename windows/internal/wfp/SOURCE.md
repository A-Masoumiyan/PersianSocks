Runtime source from github.com/tailscale/wf at 6fbb0a674ee6 (BSD).
Local change: round arena allocation sizes to uint64 alignment before placing C/WFP structures after UTF-16 strings and byte blobs; tested with Go race/checkptr. Corrected FWPM_FILTER0 context union layout and added an AMD64 ABI assertion. Added Windows build constraints. Generator/tool-only files are omitted.
Upstream: https://github.com/tailscale/wf/tree/6fbb0a674ee6
