# Commit whatever changed and push. Used repeatedly while fixing build errors.
param([string]$Message = "fix build")

Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"

git add -A 2>&1 | Out-Null

$staged = (git diff --cached --name-only | Measure-Object -Line).Lines
if ($staged -eq 0) {
  Write-Output "  nothing to commit"
} else {
  git commit -q -m $Message 2>&1 | Out-Null
  Write-Output ("  committed " + $staged + " file(s): " + (git log --oneline -1))
}

$env:GIT_TERMINAL_PROMPT = "0"
$out = git push origin main 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { Write-Output ("  " + $_) }

if ($code -eq 0) { Write-Output "SHIP-OK" } else { Write-Output "SHIP-FAILED exit=$code" }
Write-Output "SHIP-DONE"
