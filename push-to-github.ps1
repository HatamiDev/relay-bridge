<#
.SYNOPSIS
    Pushes this project to GitHub and starts the APK build.

.DESCRIPTION
    Run this from the project folder in PowerShell. It initialises git if
    needed, commits everything, creates a PRIVATE GitHub repository, pushes,
    and opens the Actions tab where the APK will appear.

    Private is not optional: the bootstrap secret is compiled into the APK and
    the workflow reads it from repository secrets. A public repo would leak the
    ability to create rooms on your relay server.

.EXAMPLE
    .\push-to-github.ps1
    .\push-to-github.ps1 -RepoName relay-bridge -Remote git@github.com:me/relay-bridge.git
#>

[CmdletBinding()]
param(
    # Name for the new repository when this script creates one.
    [string]$RepoName = 'relay-bridge',

    # Push to an existing repository instead of creating one.
    [string]$Remote = '',

    # Skip creating the repo even if the GitHub CLI is available.
    [switch]$NoCreate
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Step($msg)  { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "    $msg" -ForegroundColor Yellow }
function Die($msg)   { Write-Host "`nX  $msg" -ForegroundColor Red; exit 1 }

# ── Prerequisites ────────────────────────────────────────────────────────────

Step 'Checking prerequisites'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Die 'git is not installed. Get it from https://git-scm.com/download/win and re-run.'
}
Ok "git $(git --version | Select-Object -First 1)"

$gh = Get-Command gh -ErrorAction SilentlyContinue
if ($gh) {
    Ok "GitHub CLI found — the repository can be created automatically."
} else {
    Warn 'GitHub CLI not found. Install it (winget install GitHub.cli) to have'
    Warn 'this script create the repository, or pass -Remote with an existing one.'
}

# ── Safety: refuse to commit secrets ─────────────────────────────────────────

Step 'Checking for secrets that must not be committed'

$danger = @()
foreach ($p in @('server\.env', 'android\app\google-services.json', 'release.jks')) {
    if (Test-Path $p) { $danger += $p }
}
if ($danger.Count -gt 0) {
    Warn "These exist locally and are excluded by .gitignore (correct):"
    $danger | ForEach-Object { Warn "  $_" }
    Warn 'Add them as repository SECRETS instead — see docs/05-BUILD-APK.md.'
}

# ── Commit ───────────────────────────────────────────────────────────────────

Step 'Preparing the commit'

if (-not (Test-Path .git)) {
    git init --initial-branch=main | Out-Null
    Ok 'Initialised a new repository.'
} else {
    Ok 'Repository already initialised.'
}

# A stale .git can be left behind by a tool that could not clean up after
# itself; a fresh index avoids inheriting that state.
if (Test-Path '.git\index.lock') {
    Remove-Item '.git\index.lock' -Force
    Warn 'Removed a stale index.lock.'
}

git add -A

$staged = (git diff --cached --name-only | Measure-Object -Line).Lines
if ($staged -eq 0) {
    Ok 'Nothing new to commit.'
} else {
    $msg = @'
Relay: dual-Android SMS & WebRTC call bridge

Single APK, role picker on first launch (Sender / Receiver).
One sender serves up to 8 receivers, each with its own end-to-end key.
'@
    git commit -q -m $msg
    Ok "Committed $staged file(s)."
}

# Leaked-secret check against what is actually tracked, not what is on disk.
$tracked = git ls-files
$leaks = $tracked | Where-Object { $_ -match '(\.env$|\.jks$|google-services\.json$|secrets/)' }
if ($leaks) {
    Write-Host ''
    Die "These secrets are TRACKED and would be pushed:`n  $($leaks -join "`n  ")`n
Run:  git rm --cached <file>   for each, then re-run this script."
}
Ok 'No secrets are tracked.'

# ── Remote ───────────────────────────────────────────────────────────────────

Step 'Configuring the remote'

$existing = git remote get-url origin 2>$null

if ($Remote) {
    if ($existing) { git remote set-url origin $Remote } else { git remote add origin $Remote }
    Ok "origin -> $Remote"
} elseif ($existing) {
    Ok "origin already set -> $existing"
} elseif ($gh -and -not $NoCreate) {
    Ok "Creating a private repository named '$RepoName'..."
    gh repo create $RepoName --private --source=. --remote=origin
    if ($LASTEXITCODE -ne 0) { Die 'gh repo create failed. Run `gh auth login` first.' }
    Ok 'Repository created and origin configured.'
} else {
    Write-Host ''
    Write-Host 'No remote configured. Create a PRIVATE repo on github.com, then run:' -ForegroundColor Yellow
    Write-Host "  git remote add origin git@github.com:<you>/$RepoName.git" -ForegroundColor White
    Write-Host '  git push -u origin main' -ForegroundColor White
    exit 0
}

# ── Push ─────────────────────────────────────────────────────────────────────

Step 'Pushing'

git push -u origin main
if ($LASTEXITCODE -ne 0) { Die 'Push failed. Check your credentials and try again.' }
Ok 'Pushed.'

# ── What happens next ────────────────────────────────────────────────────────

$url = git remote get-url origin
$web = $url -replace '^git@github\.com:', 'https://github.com/' -replace '\.git$', ''

Write-Host ''
Write-Host '────────────────────────────────────────────────────────────' -ForegroundColor DarkGray
Write-Host ' The APK build has started.' -ForegroundColor Green
Write-Host '────────────────────────────────────────────────────────────' -ForegroundColor DarkGray
Write-Host ''
Write-Host " Watch it:     $web/actions"
Write-Host " Download:     the run -> Artifacts -> relay-apk-N"
Write-Host ''
Write-Host ' Before it can talk to your server, add at:' -ForegroundColor Yellow
Write-Host "   $web/settings/secrets/actions"
Write-Host ''
Write-Host '   Secret    RELAY_BOOTSTRAP_SECRET   = BOOTSTRAP_SECRET from server/.env'
Write-Host '   Variable  RELAY_SERVER_URL         = https://hatamidev.com   (already the default)'
Write-Host '   Secret    GOOGLE_SERVICES_JSON     = base64 of google-services.json  (for call wake-ups)'
Write-Host ''
Write-Host ' The first build will probably fail — this code has never been' -ForegroundColor Yellow
Write-Host ' compiled. Send me the Kotlin errors from the log and I will fix them.' -ForegroundColor Yellow
Write-Host ''

if ($gh) {
    Start-Process "$web/actions"
}
