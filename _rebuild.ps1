# Trigger a fresh CI build.
#
# An empty commit rather than a workflow_dispatch call: the workflow already
# runs on push, and this needs no API token, no dispatch permission, and
# leaves a visible marker in the history saying why the build was re-run.

Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"
git commit -q --allow-empty -m "Rebuild against the live relay at relay.hatamidev.com"
$env:GIT_TERMINAL_PROMPT = "0"
$out = git push origin main 2>&1
Write-Output ($out -join "`n")
git log --oneline -1
Write-Output "REBUILD-TRIGGERED"
