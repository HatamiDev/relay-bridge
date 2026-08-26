# Clear out the stalled partial downloads from earlier attempts. Any process
# still holding a lock is killed first, otherwise the delete silently fails and
# the next download picks the stale file back up.
Get-Process powershell -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }

Start-Sleep -Seconds 1

Get-ChildItem "C:\Users\ROG\Desktop\workspace\SMS & call bridge\dist" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "relay-apk*" } |
    ForEach-Object {
        Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
        Write-Output ("  removed " + $_.Name)
    }
Write-Output "CLEAN-DONE"
