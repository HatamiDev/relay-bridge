# Push to the repository that was just created.
# Git Credential Manager holds a stored github.com credential on this machine,
# so git supplies it itself — no token is ever handled by this script.

Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"

$remote = "https://github.com/HatamiDev/relay-bridge.git"

Write-Output "=== REMOTE ==="
$existing = git remote 2>&1
if ($existing -contains 'origin') {
  git remote set-url origin $remote 2>&1 | Out-Null
  Write-Output "  updated origin"
} else {
  git remote add origin $remote 2>&1 | Out-Null
  Write-Output "  added origin"
}
Write-Output ("  " + (git remote -v | Select-Object -First 1))

Write-Output ""
Write-Output "=== PUSH ==="
# GIT_TERMINAL_PROMPT=0 makes git fail fast instead of hanging on a prompt that
# nobody is here to answer; GCM's own window can still appear if it needs one.
$env:GIT_TERMINAL_PROMPT = "0"
$out = git push -u origin main 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { Write-Output ("  " + $_) }

Write-Output ""
if ($code -eq 0) {
  Write-Output "PUSH-OK"
  Write-Output ("  commit : " + (git rev-parse --short HEAD))
  Write-Output ("  files  : " + ((git ls-files | Measure-Object -Line).Lines))
} else {
  Write-Output "PUSH-FAILED exit=$code"
}
Write-Output "PUSH-DONE"
