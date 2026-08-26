# Continue the scrub. The previous attempt already ran `git reset --soft HEAD~1`
# before failing, so HEAD is back at 213088e with the bad commit's contents
# sitting in the index. This picks up from there — no second reset.

$ErrorActionPreference = "Continue"
Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"

Write-Output "== HEAD"
git log --oneline -1

foreach ($f in @("BOOTSTRAP_SECRET.txt", "relay-app-upload.zip", "ziMEdfTb")) {
    git rm --cached -f --ignore-unmatch -q -- $f 2>&1 | Out-Null
}
Remove-Item "ziMEdfTb" -Force -ErrorAction SilentlyContinue

git add .gitignore deploy/README-cpanel-fa.md

Write-Output "== staged"
git diff --cached --name-only

git commit -q -m "Correct the cPanel root test and document private-repo clone paths" -m "Also stop deployment bundles and the bootstrap-secret note from being tracked. The previous commit swept a filled-in .env into the history; those secrets have been rotated."

Write-Output "== after"
git log --oneline -2

$env:GIT_TERMINAL_PROMPT = "0"
$out = git push --force-with-lease origin main 2>&1
Write-Output ($out -join "`n")

Write-Output "== remote tree contains a secret file?"
git ls-tree -r --name-only HEAD | Select-String -Pattern "BOOTSTRAP_SECRET|relay-app-upload|ziMEdfTb"
Write-Output "SCRUB2-DONE"
