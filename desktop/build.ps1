# Compila Playerless.exe con il compilatore C# che Windows ha gia': non serve installare niente.
# La pagina (web\) e gli script di YouTube dell'app Android finiscono dentro l'exe.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "Non trovo il compilatore C# di .NET 4: $csc" }

# The YouTube solver and BotGuard page are the very files the phone uses.
$yt = Join-Path $here '..\app\src\main\assets\yt'
$files = @(Get-ChildItem (Join-Path $here 'web') -File) +
    @('yt.solver.lib.min.js', 'yt.solver.core.js', 'botguard.html' | ForEach-Object { Get-Item (Join-Path $yt $_) })
$resources = $files | ForEach-Object { "/resource:$($_.FullName),web/$($_.Name)" }

$out = Join-Path $here 'build\Playerless.exe'
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
& $csc /nologo /target:winexe /optimize+ "/out:$out" "/win32icon:$(Join-Path $here 'src\Playerless.ico')" `
    /r:System.Web.Extensions.dll /r:System.Management.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll `
    @resources (Join-Path $here 'src\Playerless.cs')
if ($LASTEXITCODE -ne 0) { throw 'Compilazione non riuscita.' }
Write-Host "Fatto: $out ($([math]::Round((Get-Item $out).Length / 1KB)) KB)"

# The copy on the desktop, if there is one, stays current.
$onDesktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Playerless.exe'
if (Test-Path $onDesktop) {
    try {
        Copy-Item $out $onDesktop -Force -ErrorAction Stop
        Write-Host "Aggiornato anche $onDesktop"
    } catch {
        Write-Host "Non aggiorno $onDesktop finche' Playerless e' aperta: chiudila e ricompila." -ForegroundColor Yellow
    }
}
