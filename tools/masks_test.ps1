# Applies every face mask on the sample portrait in the emulator and builds one labelled contact sheet.
# Usage (from the project root):  powershell -File tools\masks_test.ps1  [-NoInstall]
param([switch]$NoInstall)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $d = "emulator-5554"
$shots = "$env:TEMP\shots"; New-Item -ItemType Directory -Force $shots | Out-Null
function Shot($n) { & $adb -s $d shell screencap -p /sdcard/Download/$n.png; & $adb -s $d pull /sdcard/Download/$n.png "$shots\$n.png" *> $null }
function Tap-Contains($text, $minY = 0) {
  & $adb -s $d shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
  & $adb -s $d pull /sdcard/ui.xml "$env:TEMP\ui.xml" *> $null
  $xml = [xml](Get-Content "$env:TEMP\ui.xml" -Raw -Encoding UTF8)
  foreach ($n in $xml.SelectNodes("//node[contains(@text,'$text')]")) {
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
      $x = ([int]$matches[1] + [int]$matches[3]) / 2; $y = ([int]$matches[2] + [int]$matches[4]) / 2
      if ($y -ge $minY -and $x -lt 1060) { & $adb -s $d shell input tap $x $y; return $true }
    }
  }
  return $false
}
if (-not $NoInstall) { & $adb -s $d install -r app\build\outputs\apk\debug\app-debug.apk 2>&1 | Select-Object -Last 1 }
& $adb -s $d shell cmd uimode night yes | Out-Null
& $adb -s $d shell am force-stop com.hinterlay.app.debug
& $adb -s $d shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM content://com.hinterlay.app.debug.fileprovider/exports/sample1.jpg -n com.hinterlay.app.debug/com.hinterlay.app.MainActivity | Out-Null
Start-Sleep -Seconds 45
Tap-Contains 'Tools' | Out-Null; Start-Sleep -Seconds 1
& $adb -s $d shell input swipe 700 1675 150 1675 300; Start-Sleep -Seconds 1
$f = Tap-Contains 'Faces' 1300
if (-not $f) { & $adb -s $d shell input swipe 700 1675 300 1675 300; Start-Sleep -Seconds 1; $f = Tap-Contains 'Faces' 1300 }
"faces tab: $f"; Start-Sleep -Seconds 4
$names = 'Puppy','Kitty','Bunny','Fox','Shades','Hearts','Royal','Clown','Alien'
$i = 0
foreach ($n in $names) {
  $ok = Tap-Contains $n 1700
  if (-not $ok) { & $adb -s $d shell input swipe 760 1848 200 1848 300; Start-Sleep -Milliseconds 600; $ok = Tap-Contains $n 1700 }
  Start-Sleep -Milliseconds 2600
  Shot "m_$i"; "$n tapped=$ok"; $i++
}
Add-Type -AssemblyName System.Drawing
$rect = New-Object System.Drawing.Rectangle 270,420,470,480
$out = New-Object System.Drawing.Bitmap 1410,1440; $g = [System.Drawing.Graphics]::FromImage($out); $g.InterpolationMode = 'HighQualityBicubic'; $g.Clear([System.Drawing.Color]::Black)
$font = New-Object System.Drawing.Font('Arial', 22, [System.Drawing.FontStyle]::Bold)
for ($i = 0; $i -lt 9; $i++) {
  $b = [System.Drawing.Bitmap]::FromFile("$shots\m_$i.png")
  $x = ($i % 3) * 470; $y = [math]::Floor($i / 3) * 480
  $g.DrawImage($b, (New-Object System.Drawing.Rectangle $x, $y, 470, 480), $rect, 'Pixel')
  $g.DrawString($names[$i], $font, [System.Drawing.Brushes]::White, $x + 8, $y + 6); $b.Dispose()
}
$g.Dispose(); $out.Save("$shots\masks_sheet.png"); "sheet: $shots\masks_sheet.png"
