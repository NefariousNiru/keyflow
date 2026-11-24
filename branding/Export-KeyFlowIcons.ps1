$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root 'android/app/src/main/res/drawable-nodpi'
$desktop = Join-Path $root 'desktop/RemoteKeyboard.Windows'
[IO.Directory]::CreateDirectory($android) | Out-Null

function Color([string]$Hex) { [Drawing.ColorTranslator]::FromHtml($Hex) }

function Fill-Tile($Graphics, $Bounds, [float]$Radius, $Top, $Bottom, $Border) {
    $path = [Drawing.Drawing2D.GraphicsPath]::new()
    $brush = [Drawing.Drawing2D.LinearGradientBrush]::new($Bounds, $Top, $Bottom, 55)
    try {
        $diameter = 2 * $Radius
        $path.AddArc($Bounds.Left, $Bounds.Top, $diameter, $diameter, 180, 90)
        $path.AddArc($Bounds.Right - $diameter, $Bounds.Top, $diameter, $diameter, 270, 90)
        $path.AddArc($Bounds.Right - $diameter, $Bounds.Bottom - $diameter, $diameter, $diameter, 0, 90)
        $path.AddArc($Bounds.Left, $Bounds.Bottom - $diameter, $diameter, $diameter, 90, 90)
        $path.CloseFigure()
        $Graphics.FillPath($brush, $path)
        if ($null -ne $Border) {
            $pen = [Drawing.Pen]::new($Border, 3)
            try { $Graphics.DrawPath($pen, $path) } finally { $pen.Dispose() }
        }
    } finally {
        $brush.Dispose()
        $path.Dispose()
    }
}

function Render-Mark([int]$Size, [string]$Layer) {
    $bitmap = [Drawing.Bitmap]::new($Size, $Size)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = 'AntiAlias'
        $graphics.PixelOffsetMode = 'HighQuality'
        $graphics.ScaleTransform($Size / 432, $Size / 432)
        if ($Layer -eq 'background') {
            $gradient = [Drawing.Drawing2D.LinearGradientBrush]::new(
                [Drawing.RectangleF]::new(0, 0, 432, 432), (Color '#344c79'), (Color '#15192f'), 55)
            try { $graphics.FillRectangle($gradient, 0, 0, 432, 432) } finally { $gradient.Dispose() }
        } elseif ($Layer -eq 'complete') {
            Fill-Tile $graphics ([Drawing.RectangleF]::new(13, 13, 406, 406)) 112 (Color '#364e7b') (Color '#161a32') (Color '#849ac2')
        }

        if ($Layer -ne 'background') {
            if ($Layer -eq 'complete') {
                $graphics.TranslateTransform(216, 216)
                $graphics.ScaleTransform(1.3, 1.3)
                $graphics.TranslateTransform(-216, -216)
            }
            # The staggered upper caps and spacebar form the same mark at every size.
            Fill-Tile $graphics ([Drawing.RectangleF]::new(108, 169, 62, 62)) 18 (Color '#d6ffff') (Color '#79d9ed') $null
            Fill-Tile $graphics ([Drawing.RectangleF]::new(185, 150, 62, 81)) 18 (Color '#e3eeff') (Color '#91adea') $null
            Fill-Tile $graphics ([Drawing.RectangleF]::new(262, 130, 62, 101)) 18 (Color '#f2e8ff') (Color '#b3a0f0') $null
            Fill-Tile $graphics ([Drawing.RectangleF]::new(108, 250, 216, 48)) 18 (Color '#c5e5ff') (Color '#9faef0') $null
        }
        $bitmap
    } finally { $graphics.Dispose() }
}

function Save-Png([string]$Name, [int]$Size, [string]$Layer) {
    $bitmap = Render-Mark $Size $Layer
    try { $bitmap.Save((Join-Path $android $Name), [Drawing.Imaging.ImageFormat]::Png) }
    finally { $bitmap.Dispose() }
}

Save-Png 'keyflow_icon.png' 256 'complete'
Save-Png 'keyflow_icon_background.png' 432 'background'
Save-Png 'keyflow_icon_foreground.png' 432 'foreground'

$sizes = 16, 24, 32, 48, 64, 128, 256
$frames = foreach ($size in $sizes) {
    $bitmap = Render-Mark $size 'complete'
    $png = [IO.MemoryStream]::new()
    try {
        $bitmap.Save($png, [Drawing.Imaging.ImageFormat]::Png)
        ,$png.ToArray()
    } finally {
        $bitmap.Dispose()
        $png.Dispose()
    }
}
$icon = [IO.BinaryWriter]::new([IO.File]::Create((Join-Path $desktop 'KeyFlow.ico')))
try {
    $icon.Write([uint16]0)
    $icon.Write([uint16]1)
    $icon.Write([uint16]$sizes.Length)
    $offset = 6 + 16 * $sizes.Length
    for ($index = 0; $index -lt $sizes.Length; $index++) {
        $extent = [byte]($sizes[$index] % 256)
        $icon.Write($extent)
        $icon.Write($extent)
        $icon.Write([uint16]0)
        $icon.Write([uint16]1)
        $icon.Write([uint16]32)
        $icon.Write([uint32]$frames[$index].Length)
        $icon.Write([uint32]$offset)
        $offset += $frames[$index].Length
    }
    foreach ($frame in $frames) { $icon.Write([byte[]]$frame) }
} finally { $icon.Dispose() }
