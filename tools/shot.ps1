# 抓取《滚木派对》窗口的画面到 PNG。只抓游戏窗口，不抓整个桌面。
param(
    [string]$TitlePart = "Gunmu Party",
    [string]$Out = "D:\Downloads\gunmu party\build\shot.png",
    [int]$WaitSec = 0
)

Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W {
    [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string name);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr h);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)]
    public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    public delegate bool EnumProc(IntPtr h, IntPtr p);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

if ($WaitSec -gt 0) { Start-Sleep -Seconds $WaitSec }

$found = [IntPtr]::Zero
$foundTitle = ""
$cb = [W+EnumProc]{
    param($h, $p)
    if (-not [W]::IsWindowVisible($h)) { return $true }
    $len = [W]::GetWindowTextLength($h)
    if ($len -eq 0) { return $true }
    $sb = New-Object System.Text.StringBuilder ($len + 2)
    [void][W]::GetWindowText($h, $sb, $sb.Capacity)
    $t = $sb.ToString()
    if ($t -like "*$TitlePart*") {
        $script:found = $h
        $script:foundTitle = $t
        return $false
    }
    return $true
}
[void][W]::EnumWindows($cb, [IntPtr]::Zero)

if ($found -eq [IntPtr]::Zero) {
    Write-Output "NOTFOUND"
    exit 2
}

$r = New-Object W+RECT
[void][W]::GetWindowRect($found, [ref]$r)
$w = $r.Right - $r.Left
$h2 = $r.Bottom - $r.Top
if ($w -le 0 -or $h2 -le 0) { Write-Output "BADRECT"; exit 3 }

$bmp = New-Object System.Drawing.Bitmap $w, $h2
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
$g.Dispose()

$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Output "OK $foundTitle ${w}x${h2} -> $Out"
