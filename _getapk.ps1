param(
    [string]$ArtifactId = "",
    [int]$Attempts = 40
)

# Pull the built APK artifact out of the private repo onto this machine.
#
# Two things this has to survive:
#
#  * **Auth.** The repo is private, so the download needs a credential. It
#    reuses whatever Git already stores for github.com — the same credential
#    that pushed the commits. `git credential fill` blocks on stdin unless its
#    input ends in a blank line, so the request goes through a temp file rather
#    than a pipe. The token is never printed.
#
#  * **A flaky link.** Plain Invoke-WebRequest restarts from zero on every
#    drop, which never finishes over a connection that dies mid-transfer. This
#    streams to disk and resumes with an HTTP Range request from whatever byte
#    it reached, so progress is cumulative across retries.
#
# Pass -ArtifactId to target a specific artifact; omitted, it resolves the
# newest one in the repo.

$ErrorActionPreference = "Stop"
$repoRoot = "C:\Users\ROG\Desktop\workspace\SMS & call bridge"
$outDir   = Join-Path $repoRoot "dist"
$partPath = Join-Path $outDir "relay-apk.part"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# ── Credential ───────────────────────────────────────────────────────────────

$req = Join-Path $env:TEMP ("gcred-" + [guid]::NewGuid().ToString("N").Substring(0, 6) + ".txt")
"protocol=https`nhost=github.com`n" | Set-Content -Path $req -Encoding ascii -NoNewline
Add-Content -Path $req -Value "" -Encoding ascii
$cred = cmd /c "git credential fill < `"$req`"" 2>&1
Remove-Item $req -Force -ErrorAction SilentlyContinue

$tok = $null
foreach ($line in $cred) { if ("$line" -like "password=*") { $tok = "$line".Substring(9) } }
if (-not $tok) { Write-Output "NO-CREDENTIAL"; exit 1 }

$hdr = @{
    Authorization = "Bearer $tok"
    Accept        = "application/vnd.github+json"
    "User-Agent"  = "relay-bridge-fetch"
}

# ── Resolve the artifact ─────────────────────────────────────────────────────

if (-not $ArtifactId) {
    $list = Invoke-RestMethod -Uri "https://api.github.com/repos/HatamiDev/relay-bridge/actions/artifacts?per_page=1" -Headers $hdr
    if ($list.artifacts.Count -eq 0) { Write-Output "NO-ARTIFACTS"; exit 1 }
    $ArtifactId = $list.artifacts[0].id
    Write-Output ("  artifact " + $list.artifacts[0].name + " (" +
        [math]::Round($list.artifacts[0].size_in_bytes / 1MB, 1) + " MB)")
}

$url = "https://api.github.com/repos/HatamiDev/relay-bridge/actions/artifacts/$ArtifactId/zip"

# A fresh artifact id means the old partial file is for a different build.
$stamp = Join-Path $outDir "relay-apk.part.id"
if ((Test-Path $stamp) -and (Get-Content $stamp -Raw).Trim() -ne "$ArtifactId") {
    Remove-Item $partPath -Force -ErrorAction SilentlyContinue
}
Set-Content -Path $stamp -Value "$ArtifactId"

# ── Resumable download ───────────────────────────────────────────────────────

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$total = 0

for ($i = 1; $i -le $Attempts; $i++) {
    $have = 0
    if (Test-Path $partPath) { $have = (Get-Item $partPath).Length }
    if ($total -gt 0 -and $have -ge $total) { break }

    try {
        $rq = [Net.HttpWebRequest]::Create($url)
        $rq.Method = "GET"
        $rq.Timeout = 60000
        $rq.ReadWriteTimeout = 60000
        $rq.UserAgent = "relay-bridge-fetch"
        $rq.Accept = "application/vnd.github+json"
        $rq.Headers.Add("Authorization", "Bearer $tok")
        # Byte 0 is a valid resume point, so the header is always safe to send.
        $rq.AddRange([long]$have)

        $rs = $rq.GetResponse()
        if ($total -eq 0) { $total = $have + $rs.ContentLength }

        $src = $rs.GetResponseStream()
        $dst = [IO.File]::Open($partPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        $buf = New-Object byte[] 65536
        try {
            while (($n = $src.Read($buf, 0, $buf.Length)) -gt 0) { $dst.Write($buf, 0, $n) }
        } finally {
            $dst.Close(); $src.Close(); $rs.Close()
        }
    } catch {
        # Fall through to the progress report and retry.
    }

    $now = 0
    if (Test-Path $partPath) { $now = (Get-Item $partPath).Length }
    Write-Output ("  attempt " + $i + ": " + [math]::Round($now / 1MB, 1) + " / " +
        [math]::Round($total / 1MB, 1) + " MB")

    if ($total -gt 0 -and $now -ge $total) { break }
    if ($now -le $have) { Start-Sleep -Seconds 3 }   # no progress, back off
}

$final = (Get-Item $partPath).Length
if ($total -eq 0 -or $final -lt $total) {
    Write-Output ("INCOMPLETE " + $final + " / " + $total)
    exit 1
}

# ── Unpack ───────────────────────────────────────────────────────────────────

$zip = Join-Path $outDir "relay-apk.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Move-Item $partPath $zip -Force
Remove-Item $stamp -Force -ErrorAction SilentlyContinue

$extract = Join-Path $outDir "apk"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $extract -Force
Remove-Item $zip -Force

Get-ChildItem $extract -Recurse -File | ForEach-Object {
    Write-Output ("  " + $_.FullName.Substring($repoRoot.Length + 1) + "  " +
        [math]::Round($_.Length / 1MB, 2) + " MB")
}
Write-Output "APK-DONE"
