# 1) Settle whether the Android SDK is genuinely unreachable from this machine.
# 2) Clean the broken .git left by an earlier attempt and make a real commit.
#
# No here-strings anywhere: Windows PowerShell 5.1 parses them inconsistently
# when the file has LF line endings, which is how this file was written.

Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"

Write-Output "=== GOOGLE: ranged GET, not HEAD (some CDNs 404 on HEAD) ==="
$urls = @(
  'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
  'https://dl.google.com/android/repository/repository2-3.xml',
  'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.5.2/gradle-8.5.2.pom'
)
foreach ($u in $urls) {
  try {
    $req = [System.Net.HttpWebRequest]::Create($u)
    $req.Method = 'GET'
    $req.Timeout = 20000
    # First byte only: proves reachability without pulling 130 MB.
    $req.AddRange(0, 0)
    $resp = $req.GetResponse()
    Write-Output ("  OK {0}  totalLen={1}  {2}" -f [int]$resp.StatusCode, $resp.ContentLength, $u)
    $resp.Close()
  } catch [System.Net.WebException] {
    $r = $_.Exception.Response
    if ($r) { $code = [int]$r.StatusCode } else { $code = 'ERR' }
    Write-Output ("  FAIL {0}  {1}   ({2})" -f $code, $u, $_.Exception.Status)
  }
}

Write-Output ""
Write-Output "=== CLEAN THE BROKEN REPO ==="
if (Test-Path .git) {
  Remove-Item .git -Recurse -Force -ErrorAction SilentlyContinue
  Write-Output "  removed the half-written .git"
} else {
  Write-Output "  no .git present"
}
Remove-Item _probe.ps1  -Force -ErrorAction SilentlyContinue
Remove-Item _probe2.ps1 -Force -ErrorAction SilentlyContinue

Write-Output ""
Write-Output "=== COMMIT ==="

git init --initial-branch=main 2>&1 | Out-Null
git add -A 2>&1 | Out-Null

# Several -m flags: git joins them with blank lines, so this yields a proper
# subject plus body without needing a here-string.
git commit -q `
  -m "Relay: dual-Android SMS and WebRTC call bridge" `
  -m "Single APK, role picker on first launch (Sender / Receiver). One sender serves up to 8 receivers, each with its own end-to-end key." `
  -m "core: AES-256-GCM envelopes, P-256 ECDH pairing, 6-digit SAS verification. gateway: SMS interception, InCallService, WebRTC audio bridge. client: Aurora Glass Compose UI, full-screen call, encrypted message cache. server: Node + Socket.IO + Redis, zero-knowledge relay for hatamidev.com. deploy: nginx, systemd, coturn, redis. ci: GitHub Actions producing an installable APK." 2>&1 | Out-Null

Write-Output ("  commit : " + (git log --oneline -1 2>&1))
Write-Output ("  tracked: " + ((git ls-files | Measure-Object -Line).Lines) + " files")

Write-Output ""
Write-Output "=== SECRET LEAK CHECK ==="
$leaks = git ls-files | Where-Object { $_ -match '(\.env$|\.jks$|google-services\.json$|secrets/)' }
if ($leaks) {
  Write-Output "  !!! TRACKED SECRETS:"
  $leaks | ForEach-Object { Write-Output ("    " + $_) }
} else {
  Write-Output "  clean - no secrets tracked"
}

Write-Output ""
Write-Output "PREP-COMPLETE"
