# Undo the last commit, which swept a filled-in .env (JWT + bootstrap secret)
# into the repository, and force the remote back to a clean history.
#
# The secrets themselves have already been rotated, so what is left in the
# GitHub history is dead material — but leaving a live-looking secret in a
# repo is the kind of thing that gets copy-pasted back into use a year later.
# Removing it is cheap; explaining it later is not.
#
# Force-push is safe here: this machine is the only pusher and the branch has
# no other contributors.

$ErrorActionPreference = "Stop"
Set-Location "C:\Users\ROG\Desktop\workspace\SMS & call bridge"

Write-Output "== before"
git log --oneline -2

# Keep the good changes (the guide edits) staged, drop the commit itself.
git reset --soft HEAD~1

# Unstage the three files that should never have been added. `git rm --cached`
# rather than plain unstage, because they were newly created by that commit —
# they must leave the index entirely, not revert to a previous version.
# -f because the working-tree copies were regenerated with rotated secrets
# after the commit; without it git refuses, on the assumption you might be
# discarding staged work you meant to keep. Here the staged content is exactly
# what must not survive.
foreach ($f in @("BOOTSTRAP_SECRET.txt", "relay-app-upload.zip", "ziMEdfTb")) {
    git rm --cached -f --ignore-unmatch -q -- $f 2>&1 | Out-Null
}

# ziMEdfTb was a stray temp file from the artifact download, not something to keep.
Remove-Item "ziMEdfTb" -Force -ErrorAction SilentlyContinue

git add .gitignore
git commit -q -m "Correct the cPanel root test and document private-repo clone paths" `
               -m "Also stop deployment bundles and the bootstrap-secret note from being tracked; the previous commit swept a filled-in .env into the history. Those secrets have been rotated."

Write-Output "== after"
git log --oneline -2
git show --stat --name-only HEAD | Select-Object -Skip 4

Write-Output "== pushing (force, rewriting the bad commit off the remote)"
$env:GIT_TERMINAL_PROMPT = "0"
$out = git push --force-with-lease origin main 2>&1
Write-Output ($out -join "`n")
Write-Output "SCRUB-DONE"
