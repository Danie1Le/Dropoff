<#
  Regenerates docs/media/ (M8-4, ticket #131) from a running demo stack, with no GUI recorder.

    docker compose --profile demo up -d      # wait a minute or two so orders flow
    pwsh ops/media/capture.ps1               # or: powershell -File ops/media/capture.ps1

  Needs Docker and Microsoft Edge (headless). Writes:
    swagger-order.png, grafana-overview.png   headless Edge screenshots of the live stack
    k6-summary.png                            a short k6 `place-orders.js` run (LOAD_PROFILE=short), summary rendered to PNG
    demo.gif                                  a timed sequence of frames, stitched by ffmpeg in Docker (free image)

  The GIF is not a screen recording. Each frame is an HTML page: the left pane is the real output of
  `simulator watch <orderId>` (timestamped while it runs), the right pane is a headless screenshot of the live
  Grafana dashboard taken at that moment.
#>
param(
  [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
  [string]$Out = (Join-Path $PSScriptRoot '..\..\docs\media'),
  [string]$Work = (Join-Path ([IO.Path]::GetTempPath()) 'dropoff-media'),
  [int]$Frames = 12,
  [int]$FrameSeconds = 2
)
$ErrorActionPreference = 'Continue'
$Out = (New-Item -ItemType Directory -Force $Out).FullName
New-Item -ItemType Directory -Force $Work | Out-Null
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:PATH += ';C:\Program Files\Docker\Docker\resources\bin'
$Grafana = 'http://localhost:3000/d/dropoff-overview/dropoff-overview?orgId=1&kiosk'

function Shot([string]$file, [string]$url, [string]$size, [int]$budgetMs) {
  & $Edge --headless=new --disable-gpu "--user-data-dir=$Work\edge" --hide-scrollbars "--window-size=$size" `
    "--virtual-time-budget=$budgetMs" "--screenshot=$file" $url 2>$null | Out-Null
  if (-not (Test-Path $file)) { throw "screenshot failed: $file" }
}
function Esc([string]$s) { [Net.WebUtility]::HtmlEncode($s) }
function TerminalPage([string]$title, [string[]]$lines, [string]$size) {
  $body = (($lines | ForEach-Object { Esc $_ }) -join "`n")
  "<html><body style='margin:0;background:#0d1117'><div style='font:14px/1.45 Consolas,monospace;color:#c9d1d9;padding:16px;white-space:pre'>" +
  "<div style='color:#7ee787'>$(Esc $title)</div>$body</div></body></html>"
}

# 1. Swagger UI and the Grafana dashboard
Shot "$Out\swagger-order.png" 'http://localhost:8082/swagger-ui.html' '1400,1100' 10000
Shot "$Out\grafana-overview.png" "$Grafana&from=now-15m&to=now" '1600,1000' 20000

# 2. k6: short place-orders run; the text summary becomes a PNG
New-Item -ItemType Directory -Force "$Repo\ops\k6\results" | Out-Null
Push-Location $Repo
$k6 = docker compose --profile demo --profile load run --rm -T k6 run --no-color place-orders.js 2>&1 | Out-String
Pop-Location
$lines = ($k6 -replace "`r`n", "`n" -replace "`r", "`n") -split "`n"
$start = ($lines | Select-String 'THRESHOLDS' | Select-Object -First 1).LineNumber
$end = ($lines | Select-String 'NETWORK' | Select-Object -First 1).LineNumber + 3
$summary = $lines[($start - 1)..([Math]::Min($end, $lines.Count - 1))] | Where-Object { $_ -notmatch '^\s*(running|place_orders)' }
Set-Content "$Work\k6.html" (TerminalPage 'k6 run place-orders.js (LOAD_PROFILE=short)' $summary '') -Encoding utf8
Shot "$Out\k6-summary.png" "file:///$($Work -replace '\\','/')/k6.html" '1000,880' 3000

# 3. GIF frames: watch an order that has a courier, while taking timed Grafana shots
$logs = docker compose --profile demo logs simulator --no-color --tail 200 2>&1 | Out-String
$orderId = ([regex]::Matches($logs, 'accepted order (\d+)') | Select-Object -Last 1).Groups[1].Value
if (-not $orderId) { throw 'no accepted order in simulator logs yet; wait and retry' }
$watchOut = "$Work\watch.txt"
Remove-Item $watchOut -ErrorAction SilentlyContinue
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
$t0 = Get-Date
$stamps = @()
for ($i = 0; $i -lt $Frames; $i++) {
  Shot "$Work\g$i.png" "$Grafana&from=now-5m&to=now" '1100,760' 12000
  $stamps += (Get-Date)
}
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue
docker ps -aq --filter 'name=simulator-run' | ForEach-Object { docker rm -f $_ | Out-Null }
$watch = Get-Content $watchOut | Where-Object { $_ -match 'Watching|Order \d+ is|STATUS|LOCATION' }
# Watch lines carry their own UTC timestamps (LOCATION/STATUS); the header lines have none, so show them from frame 0.
for ($i = 0; $i -lt $Frames; $i++) {
  $cut = $stamps[$i].ToUniversalTime()
  $shown = $watch | Where-Object {
    if ($_ -match '^(\d{4}-\d\d-\d\dT[\d:.]+)Z') { [datetime]::Parse($Matches[1], $null, 'RoundtripKind') -le $cut.AddSeconds(0) } else { $true }
  } | Select-Object -Last 14
  $img = "file:///$($Work -replace '\\','/')/g$i.png"
  $html = "<html><body style='margin:0;background:#0d1117;display:flex'>" +
    "<div style='width:540px;font:13px/1.5 Consolas,monospace;color:#c9d1d9;padding:14px;white-space:pre-wrap;word-break:break-all'>" +
    "<div style='color:#7ee787'>`$ simulator watch $orderId</div>" + (($shown | ForEach-Object { Esc $_ }) -join "`n") + "</div>" +
    "<img src='$img' style='width:740px;height:auto;align-self:flex-start'></body></html>"
  Set-Content "$Work\f$i.html" $html -Encoding utf8
  Shot "$Work\frame$('{0:D3}' -f $i).png" "file:///$($Work -replace '\\','/')/f$i.html" '1280,540' 3000
}

# 4. Stitch with ffmpeg in Docker: 1 frame per $FrameSeconds seconds, palette-optimised, 960 px wide
docker run --rm -v "${Work}:/w" --entrypoint ffmpeg linuxserver/ffmpeg:latest -y -loglevel error `
  -framerate (1 / $FrameSeconds) -i /w/frame%03d.png `
  -vf 'scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=3' `
  -loop 0 /w/demo.gif
Copy-Item "$Work\demo.gif" "$Out\demo.gif" -Force
Get-ChildItem $Out | Select-Object Name, Length
