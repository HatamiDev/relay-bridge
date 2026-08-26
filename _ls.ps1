Get-ChildItem "C:\Users\ROG\Desktop\workspace\SMS & call bridge\dist" -Recurse -File |
    ForEach-Object { Write-Output ($_.FullName + "  " + [math]::Round($_.Length / 1MB, 2) + " MB") }
Write-Output "LS-DONE"
