# Shared helpers for emulator UI checks. Dot-source it:  . .\tools\ui_helpers.ps1
$script:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$script:dev = "emulator-5554"
$script:shots = "$env:TEMP\shots"; New-Item -ItemType Directory -Force $script:shots | Out-Null

function Shot($n) {
  & $script:adb -s $script:dev shell screencap -p /sdcard/Download/$n.png
  & $script:adb -s $script:dev pull /sdcard/Download/$n.png "$script:shots\$n.png" *> $null
}

# Taps the first on-screen element whose text contains $text (and sits at or below $minY). Returns $true if one was tapped.
function Tap-Contains($text, $minY = 0) {
  & $script:adb -s $script:dev shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
  & $script:adb -s $script:dev pull /sdcard/ui.xml "$env:TEMP\ui.xml" *> $null
  $xml = [xml](Get-Content "$env:TEMP\ui.xml" -Raw -Encoding UTF8)
  foreach ($n in $xml.SelectNodes("//node[contains(@text,'$text')]")) {
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
      $x = ([int]$matches[1] + [int]$matches[3]) / 2; $y = ([int]$matches[2] + [int]$matches[4]) / 2
      if ($y -ge $minY -and $x -lt 1060) { & $script:adb -s $script:dev shell input tap $x $y; return $true }
    }
  }
  return $false
}

# Opens the editor on the sample portrait and waits for the subject detection.
function Open-Editor([int]$wait = 45) {
  & $script:adb -s $script:dev shell am force-stop com.hinterlay.app.debug
  & $script:adb -s $script:dev shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM content://com.hinterlay.app.debug.fileprovider/exports/sample1.jpg -n com.hinterlay.app.debug/com.hinterlay.app.MainActivity | Out-Null
  Start-Sleep -Seconds $wait
}

# Opens Tools and selects the tool chip named $name, scrolling the chip row as needed.
function Open-Tool($name) {
  Tap-Contains 'Tools' | Out-Null; Start-Sleep -Seconds 1
  foreach ($swipe in @(@(0,0), @(700,150), @(700,300), @(150,700))) {
    if (Tap-Contains $name 1300) { return $true }
    if ($swipe[0] -gt 0) { & $script:adb -s $script:dev shell input swipe $swipe[0] 1675 $swipe[1] 1675 300; Start-Sleep -Milliseconds 700 }
  }
  return $false
}

# Builds a labelled grid from screenshots (names -> cells), cropping each to $rect (x,y,w,h).
function Make-Sheet($files, $labels, $rect, $cols, $out) {
  Add-Type -AssemblyName System.Drawing
  $r = New-Object System.Drawing.Rectangle $rect[0],$rect[1],$rect[2],$rect[3]
  $cw = [int]($rect[2] * 480 / $rect[3]); $ch = 480
  $rows = [math]::Ceiling($files.Count / $cols)
  $bmp = New-Object System.Drawing.Bitmap ($cw * $cols), ($ch * $rows)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.InterpolationMode = 'HighQualityBicubic'; $g.Clear([System.Drawing.Color]::Black)
  $font = New-Object System.Drawing.Font('Arial', 22, [System.Drawing.FontStyle]::Bold)
  for ($i = 0; $i -lt $files.Count; $i++) {
    $b = [System.Drawing.Bitmap]::FromFile("$script:shots\$($files[$i]).png")
    $x = ($i % $cols) * $cw; $y = [math]::Floor($i / $cols) * $ch
    $g.DrawImage($b, (New-Object System.Drawing.Rectangle $x,$y,$cw,$ch), $r, 'Pixel')
    $g.DrawString($labels[$i], $font, [System.Drawing.Brushes]::White, $x + 8, $y + 6); $b.Dispose()
  }
  $g.Dispose(); $bmp.Save($out); return $out
}
