<#
  Regenerates docs/media/ (M8-4, ticket #131) from a running demo stack, with no GUI recorder.

    docker compose --profile demo up -d      # wait a few minutes so orders flow
    pwsh ops/media/capture.ps1               # or: powershell -File ops/media/capture.ps1

  Run it on an idle host, from a fresh stack (`docker compose --profile demo --profile load down -v`, then `up`).
  The script stops on the first failed native command, and first checks that the stack is healthy.

  Needs Docker and Microsoft Edge (headless). Writes:
    swagger-order.png, grafana-overview.png   headless Edge screenshots of the live stack
    k6-summary.png                            a k6 `place-orders.js` run (-LoadProfile, default short), summary rendered to PNG
    demo.gif                                  a timed sequence of frames, stitched by ffmpeg in Docker (free image)

  The GIF is not a screen recording. Each frame is an HTML page: the left pane is the real output of
  `simulator watch <orderId>` (timestamped while it runs), the right pane is a headless screenshot of the live
  Grafana dashboard taken at that moment.

  Order matters: the Grafana screenshot and the GIF are taken first, while the simulator runs and there is no backlog.
  Then the simulator is stopped, the script waits for the order backlog to clear, and only then runs k6, so the k6 load
  (and the backlog it leaves) never shows in the other media. k6 exit 99 (a threshold failed) is not an error here: the
  summary is shown as run, never tuned.
#>
param(
  [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
  [string]$Out = (Join-Path $PSScriptRoot '..\..\docs\media'),
  [string]$Work = (Join-Path ([IO.Path]::GetTempPath()) 'dropoff-media'),
  [int]$Frames = 12,
  [int]$FrameSeconds = 2,
  [ValidateSet('short', 'full')][string]$LoadProfile = 'short',
  [int]$SettleTimeoutSeconds = 300,
  [switch]$K6Only   # skip the screenshots and the GIF (simulator already stopped); only run k6
)
$ErrorActionPreference = 'Stop'
$Out = (New-Item -ItemType Directory -Force $Out).FullName
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:PATH += ';C:\Program Files\Docker\Docker\resources\bin'
$Grafana = 'http://localhost:3000/d/dropoff-overview/dropoff-overview?orgId=1&kiosk'
$WorkUrl = "file:///$($Work -replace '\\','/')"
Set-Location $Repo

# Runs a native command and returns its output as text. Throws on a non-zero exit code unless it is in $OkCodes.
function Native([scriptblock]$cmd, [int[]]$OkCodes = @(0)) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'   # PS 5.1 turns native stderr into errors under 'Stop'
  try { $text = (& $cmd 2>&1 | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $prev }
  if ($OkCodes -notcontains $LASTEXITCODE) { throw "exit code ${LASTEXITCODE}: $cmd`n$text" }
  $text
}
function Shot([string]$file, [string]$url, [string]$size, [int]$budgetMs) {
  Remove-Item $file -ErrorAction SilentlyContinue
  # Edge is a GUI-subsystem exe: it sets no usable exit code, so wait for the process and check the output file (deleted above).
  $argList = @('--headless=new', '--disable-gpu', "`"--user-data-dir=$Work\edge`"", '--hide-scrollbars', "--window-size=$size",
    "--virtual-time-budget=$budgetMs", "`"--screenshot=$file`"", "`"$url`"")
  Start-Process -FilePath $Edge -ArgumentList $argList -Wait -NoNewWindow
  if (-not (Test-Path $file)) { throw "screenshot failed: $file" }
}
function Esc([string]$s) { [Net.WebUtility]::HtmlEncode($s) }
function TerminalPage([string]$title, [string[]]$lines) {
  $body = (($lines | ForEach-Object { Esc $_ }) -join "`n")
  "<html><body style='margin:0;background:#0d1117'><div style='font:14px/1.45 Consolas,monospace;color:#c9d1d9;padding:16px;white-space:pre'>" +
  "<div style='color:#7ee787'>$(Esc $title)</div>$body</div></body></html>"
}
function PromValue([string]$query) {
  $r = Invoke-RestMethod -UseBasicParsing "http://localhost:9090/api/v1/query?query=$([uri]::EscapeDataString($query))"
  if ($r.data.result.Count -eq 0) { return 0 }
  [double]$r.data.result[0].value[1]
}

# 0. Preflight and a clean work dir
if (-not (Test-Path $Edge)) { throw "Edge not found: $Edge" }
if (Test-Path $Work) { Remove-Item "$Work\*" -Recurse -Force }
New-Item -ItemType Directory -Force $Work | Out-Null
Native { docker info } | Out-Null
$running = (Native { docker compose --profile demo ps --status running --services }) -split "`n" | Where-Object { $_ }
$needed = @('postgres', 'kafka', 'merchant', 'order', 'dispatch', 'prometheus', 'grafana')
if (-not $K6Only) { $needed += 'simulator' }
foreach ($svc in $needed) {
  if ($running -notcontains $svc) { throw "service not running: $svc (start it with: docker compose --profile demo up -d)" }
}
foreach ($url in 'http://localhost:8082/swagger-ui.html', 'http://localhost:3000/api/health', 'http://localhost:9090/-/ready') {
  try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 $url | Out-Null } catch { throw "not healthy: $url ($($_.Exception.Message))" }
}
if (-not $K6Only -and (Native { docker compose --profile demo logs simulator --no-color --tail 200 }) -notmatch 'accepted order \d+') {
  throw 'no accepted order in simulator logs yet; wait for orders to flow and retry'
}
if (-not $K6Only) {
# The simulator stamps lines with the Docker VM clock. Measure its offset from this host once and correct for it.
$hostNow = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
$vmNow = [double](Native { docker compose --profile demo exec -T postgres date -u +%s }).Trim()
$skew = $vmNow - $hostNow   # seconds; add to host stamps to compare with container stamps

# 1. Swagger UI and the Grafana dashboard (no k6 load has run yet, so no backlog)
Shot "$Out\swagger-order.png" 'http://localhost:8082/swagger-ui.html' '1400,1100' 10000
Shot "$Out\grafana-overview.png" "$Grafana&from=now-15m&to=now" '1600,1000' 20000

# 2. GIF frames: watch an order that has a courier, while taking timed Grafana shots
$logs = Native { docker compose --profile demo logs simulator --no-color --tail 200 }
$orderId = ([regex]::Matches($logs, 'accepted order (\d+)') | Select-Object -Last 1).Groups[1].Value
if (-not $orderId) { throw 'no accepted order in simulator logs' }
$watchOut = "$Work\watch.txt"
$span = $Frames * 5 + 20
$job = Start-Job -ArgumentList $Repo, $orderId, $watchOut, $span -ScriptBlock {
  param($repo, $id, $file, $span)
  $env:PATH += ';C:\Program Files\Docker\Docker\resources\bin'
  Set-Location $repo
  $p = Start-Process docker -ArgumentList "compose --profile demo run --rm -T simulator watch $id --order-url http://order:8082" `
    -RedirectStandardOutput $file -NoNewWindow -PassThru
  Start-Sleep $span
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep 12  # compose run setup
$stamps = @()
for ($i = 0; $i -lt $Frames; $i++) {
  Shot "$Work\g$i.png" "$Grafana&from=now-5m&to=now" '1100,760' 12000
  $stamps += (Get-Date)
}
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue
docker ps -aq --filter 'name=simulator-run' | ForEach-Object { docker rm -f $_ | Out-Null }
if (-not (Test-Path $watchOut)) { throw "simulator watch wrote no output: $watchOut" }
$watch = Get-Content $watchOut | Where-Object { $_ -match 'Watching|Order \d+ is|STATUS|LOCATION' }
if (-not $watch) { throw "simulator watch printed no usable lines for order $orderId" }
# Watch lines carry their own container-clock UTC timestamps (LOCATION/STATUS); the header lines have none, so show them from frame 0.
for ($i = 0; $i -lt $Frames; $i++) {
  $cut = $stamps[$i].ToUniversalTime().AddSeconds($skew)
  $shown = $watch | Where-Object {
    if ($_ -match '^(\d{4}-\d\d-\d\dT[\d:.]+)Z') { [datetime]::Parse($Matches[1], $null, 'RoundtripKind') -le $cut } else { $true }
  } | Select-Object -Last 14
  $html = "<html><body style='margin:0;background:#0d1117;display:flex'>" +
    "<div style='width:540px;font:13px/1.5 Consolas,monospace;color:#c9d1d9;padding:14px;white-space:pre-wrap;word-break:break-all'>" +
    "<div style='color:#7ee787'>`$ simulator watch $orderId</div>" + (($shown | ForEach-Object { Esc $_ }) -join "`n") + "</div>" +
    "<img src='$WorkUrl/g$i.png' style='width:740px;height:auto;align-self:flex-start'></body></html>"
  Set-Content "$Work\f$i.html" $html -Encoding utf8
  Shot "$Work\frame$('{0:D3}' -f $i).png" "$WorkUrl/f$i.html" '1280,540' 3000
}

# 3. Stitch with ffmpeg in Docker: 1 frame per $FrameSeconds seconds, palette-optimised, 960 px wide
Native { docker run --rm -v "${Work}:/w" --entrypoint ffmpeg linuxserver/ffmpeg:latest -y -loglevel error `
  -framerate (1 / $FrameSeconds) -i /w/frame%03d.png `
  -vf 'scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3' `
  -loop 0 /w/demo.gif } | Out-Null
if (-not (Test-Path "$Work\demo.gif")) { throw 'ffmpeg wrote no demo.gif' }
Copy-Item "$Work\demo.gif" "$Out\demo.gif" -Force
}

# 4. Stop the simulator (services stay up) and wait for the order backlog to stop moving, so k6 starts from a settled stack
Native { docker compose --profile demo stop simulator } | Out-Null
# With the simulator stopped, couriers stop moving, so a few orders can stay unassigned for good. "Settled" therefore
# means the waiting count has stopped changing (3 equal reads, 10 s apart), not that it is zero.
$deadline = (Get-Date).AddSeconds($SettleTimeoutSeconds)
$prev = -1; $same = 0
while ($same -lt 2) {
  $waiting = PromValue 'dropoff_orders_waiting_for_courier'
  if ($waiting -eq $prev) { $same++ } else { $same = 0; $prev = $waiting }
  if ($same -ge 2) { break }
  if ((Get-Date) -gt $deadline) { throw "orders did not settle within ${SettleTimeoutSeconds}s (waiting count still changing: $waiting)" }
  Start-Sleep 10
}
Write-Host "settled with $waiting order(s) waiting for a courier"
Start-Sleep 30  # let the post-settle rate windows empty

# 5. k6: place-orders run with an explicit profile; the text summary becomes a PNG. Exit 99 = thresholds crossed.
New-Item -ItemType Directory -Force "$Repo\ops\k6\results" | Out-Null
$k6 = Native { $env:LOAD_PROFILE = $LoadProfile; docker compose --profile demo --profile load run --rm -T k6 run --no-color place-orders.js } -OkCodes 0, 99
$lines = ($k6 -replace "`r`n", "`n" -replace "`r", "`n") -split "`n"
$start = ($lines | Select-String 'THRESHOLDS' | Select-Object -First 1).LineNumber
$net = ($lines | Select-String 'NETWORK' | Select-Object -First 1).LineNumber
if (-not $start -or -not $net) { throw "k6 summary has no THRESHOLDS/NETWORK section; raw output:`n$k6" }
$end = [Math]::Min($net + 3, $lines.Count - 1)
$summary = $lines[($start - 1)..$end] | Where-Object { $_ -notmatch '^\s*(running|place_orders)' }
Set-Content "$Work\k6.html" (TerminalPage "k6 run place-orders.js (LOAD_PROFILE=$LoadProfile)" $summary) -Encoding utf8
Shot "$Out\k6-summary.png" "$WorkUrl/k6.html" '1000,880' 3000
Write-Host ($summary -join "`n")
Get-ChildItem $Out | Select-Object Name, Length
