# Kick off the download detached and return immediately, so progress can be
# polled instead of holding a tool call open for the whole transfer.
$root = "C:\Users\ROG\Desktop\workspace\SMS & call bridge"
$log  = Join-Path $root "dist\download.log"
New-Item -ItemType Directory -Force -Path (Join-Path $root "dist") | Out-Null
Remove-Item $log -Force -ErrorAction SilentlyContinue

$p = Start-Process powershell `
    -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", "`"$root\_getapk.ps1`""
    ) `
    -RedirectStandardOutput $log `
    -RedirectStandardError (Join-Path $root "dist\download.err") `
    -WindowStyle Hidden -PassThru

Write-Output ("STARTED pid=" + $p.Id)
