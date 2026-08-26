# Probe only: can we get a github.com credential without hanging?
# Prints a masked result, never the token itself.
$ErrorActionPreference = "Continue"

$req = Join-Path $env:TEMP "gcred.txt"
# The blank final line is what terminates `git credential fill`'s input; without
# it the command sits waiting on stdin forever.
"protocol=https`nhost=github.com`n" | Set-Content -Path $req -Encoding ascii -NoNewline
Add-Content -Path $req -Value "" -Encoding ascii

$out = cmd /c "git credential fill < `"$req`"" 2>&1
Remove-Item $req -Force -ErrorAction SilentlyContinue

$tok = $null
foreach ($line in $out) {
    if ("$line" -like "password=*") { $tok = "$line".Substring(9) }
}

if ($tok) {
    Write-Output ("GIT-CRED-OK len=" + $tok.Length + " prefix=" + $tok.Substring(0, [Math]::Min(4, $tok.Length)))
} else {
    Write-Output "GIT-CRED-NONE"
    Write-Output ($out -join " | ")
}

if (Get-Command gh -ErrorAction SilentlyContinue) {
    Write-Output "GH-PRESENT"
    $s = gh auth status 2>&1
    Write-Output ("GH-STATUS: " + (($s | Select-Object -First 4) -join " / "))
} else {
    Write-Output "GH-ABSENT"
}
Write-Output "PROBE-DONE"
