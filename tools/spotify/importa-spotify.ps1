# Playerless: porta le tue playlist di Spotify sul telefono.
# Si avvia con importa-spotify.bat. Serve il telefono collegato via USB con il debug USB attivo
# e Firefox installato.
#
#   importa-spotify.bat                   legge Spotify e importa tutto
#   importa-spotify.bat -Login            rifa' l'accesso a Spotify (per esempio per cambiare account)
#   importa-spotify.bat -File export.json importa un file gia' esportato (vedi spotify-export.js)
#   importa-spotify.bat -Serial XXXX      sceglie il telefono se ne sono collegati piu' di uno
#
# The library is read by a Firefox of the bot's own, with its own profile: Spotify's web player
# runs in it, and the bot reads the player's token from the first script on, then asks Spotify's
# web API as the player does. Signing in happens in a plain window of that Firefox, not driven by
# the bot, so Spotify's login sees an ordinary browser.

param(
    [string]$File,
    [string]$Serial,
    [switch]$Login
)

$package = 'com.playerless'
$remote = "/sdcard/Android/data/$package/files"
$exportScript = Join-Path $PSScriptRoot 'spotify-export.js'
$profileDir = Join-Path $env:LOCALAPPDATA 'Playerless\spotify-firefox'
$loginUrl = 'https://accounts.spotify.com/it/login?continue=https%3A%2F%2Fopen.spotify.com%2F'

function Fail([string]$message) {
    Write-Host ''
    Write-Host $message -ForegroundColor Red
    exit 1
}

# --- The phone ------------------------------------------------------------------------------

$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adbPath)) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $command) { Fail 'Non trovo adb: installa gli Android SDK Platform Tools.' }
    $adbPath = $command.Source
}

$devices = @(& $adbPath devices | Where-Object { $_ -match "^(\S+)\s+device$" } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($devices.Count -eq 0) { Fail 'Telefono non trovato: collegalo con il cavo, sbloccalo e accetta il debug USB.' }
    if ($devices.Count -gt 1) { Fail "Collegati piu' dispositivi ($($devices -join ', ')): scegli con -Serial." }
    $Serial = $devices[0]
}

# adb's own chatter goes to stderr; the exit code and our messages are enough.
function Adb { & $adbPath -s $Serial @args 2>$null }

if (-not ((Adb shell pm path $package) -match 'package:')) {
    Fail 'Playerless non e'' installata sul telefono.'
}

# --- WebDriver BiDi, Firefox's remote control protocol ---------------------------------------

$script:nextId = 0
$script:socket = $null

function Send-Bidi([string]$json) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $segment = New-Object 'ArraySegment[byte]' -ArgumentList @(, $bytes)
    $script:socket.SendAsync($segment, [Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait()
}

function Receive-Bidi {
    $buffer = New-Object byte[] 262144
    $stream = New-Object IO.MemoryStream
    do {
        $segment = New-Object 'ArraySegment[byte]' -ArgumentList @(, $buffer)
        $result = $script:socket.ReceiveAsync($segment, [Threading.CancellationToken]::None).Result
        $stream.Write($buffer, 0, $result.Count)
    } until ($result.EndOfMessage)
    [Text.Encoding]::UTF8.GetString($stream.ToArray())
}

function Invoke-Bidi([string]$method, $params) {
    $id = ++$script:nextId
    Send-Bidi (@{ id = $id; method = $method; params = $params } | ConvertTo-Json -Depth 30 -Compress)
    while ($true) {
        $message = Receive-Bidi | ConvertFrom-Json
        if ($message.id -eq $id) {
            if ($message.type -eq 'error') { throw "$method non riuscito: $($message.error) $($message.message)" }
            return $message.result
        }
    }
}

# Runs JavaScript in the page, waiting for it if it's a promise, and returns the string it gives.
function Invoke-Page([string]$context, [string]$expression) {
    $result = Invoke-Bidi 'script.evaluate' @{ expression = $expression; target = @{ context = $context }; awaitPromise = $true; resultOwnership = 'none' }
    if ($result.type -eq 'exception') { throw "Errore nella pagina di Spotify: $($result.exceptionDetails.text)" }
    return [string]$result.result.value
}

# --- Firefox --------------------------------------------------------------------------------

function Find-Firefox {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Mozilla Firefox\firefox.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Mozilla Firefox\firefox.exe'),
        (Join-Path $env:LOCALAPPDATA 'Mozilla Firefox\firefox.exe')
    )
    $registered = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe' -ErrorAction SilentlyContinue
    if ($registered) { $candidates = @($registered.'(default)') + $candidates }
    $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}

# Every Firefox process that runs on the bot's profile.
function Get-BotFirefox {
    Get-CimInstance Win32_Process -Filter "Name = 'firefox.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profileDir) }
}

function Stop-BotFirefox {
    Get-BotFirefox | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function New-BotProfile {
    New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
    # No welcome pages or default browser questions in the login window.
    @(
        'user_pref("browser.shell.checkDefaultBrowser", false);',
        'user_pref("browser.aboutwelcome.enabled", false);',
        'user_pref("browser.startup.homepage_override.mstone", "ignore");',
        'user_pref("startup.homepage_welcome_url", "");',
        'user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);',
        'user_pref("datareporting.policy.dataSubmissionPolicyBypassNotification", true);'
    ) -join "`r`n" | Set-Content -Path (Join-Path $profileDir 'user.js') -Encoding ASCII
}

function Get-FreePort {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $port
}

# A plain window, not driven by anything: the user signs in and closes it.
function Open-SpotifyLogin {
    Write-Host ''
    Write-Host 'Si apre una finestra di Firefox sulla pagina di accesso di Spotify.' -ForegroundColor Green
    Write-Host '  Accedi al tuo account. Quando vedi Spotify, chiudi quella finestra:'
    Write-Host '  il bot continua da solo. (Serve solo la prima volta.)'
    Start-Process -FilePath $firefox -ArgumentList "-no-remote -new-instance -profile `"$profileDir`" $loginUrl" | Out-Null
    # firefox.exe may hand over to another process and exit at once: wait on the profile instead.
    $deadline = (Get-Date).AddSeconds(20)
    while (-not (Get-BotFirefox) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    while (Get-BotFirefox) { Start-Sleep -Seconds 1 }
}

# The page's own script, set up before Spotify's: it keeps the token the web player gets.
$tokenHook = @'
() => {
  window.__playerlessBot = true;
  const realFetch = window.fetch;
  window.fetch = function (input, init) {
    const response = realFetch.apply(this, arguments);
    try {
      const url = String(input && input.url ? input.url : input);
      if (url.includes('/api/token')) {
        response.then((r) => r.clone().json()).then((j) => { if (j && j.accessToken) window.__playerlessToken = 'Bearer ' + j.accessToken; }).catch(() => {});
      }
      const auth = new Headers((init && init.headers) || (input instanceof Request ? input.headers : undefined)).get('authorization');
      if (/^Bearer \S+$/.test(auth || '')) window.__playerlessSeen = auth;
    } catch (e) {}
    return response;
  };
}
'@

# Reads the whole library in an invisible Firefox. $null when nobody is signed in.
function Read-SpotifyLibrary {
    Stop-BotFirefox
    $port = Get-FreePort
    Start-Process -FilePath $firefox -ArgumentList "-no-remote -new-instance -headless -profile `"$profileDir`" --remote-debugging-port $port about:blank" | Out-Null
    try {
        $deadline = (Get-Date).AddSeconds(40)
        while ($true) {
            try {
                $script:socket = New-Object Net.WebSockets.ClientWebSocket
                $script:socket.ConnectAsync([Uri]"ws://127.0.0.1:$port/session", [Threading.CancellationToken]::None).Wait()
                break
            } catch {
                if ((Get-Date) -gt $deadline) { throw 'Firefox non risponde al bot.' }
                Start-Sleep -Milliseconds 500
            }
        }
        Invoke-Bidi 'session.new' @{ capabilities = @{} } | Out-Null
        $context = (Invoke-Bidi 'browsingContext.getTree' @{}).contexts[0].context
        Invoke-Bidi 'script.addPreloadScript' @{ functionDeclaration = $tokenHook } | Out-Null
        Invoke-Bidi 'browsingContext.navigate' @{ context = $context; url = 'https://open.spotify.com/'; wait = 'complete' } | Out-Null

        $token = ''
        for ($i = 0; $i -lt 40 -and -not $token; $i++) {
            Start-Sleep -Milliseconds 500
            $token = Invoke-Page $context 'window.__playerlessToken || window.__playerlessSeen || ""'
        }
        if (-not $token) { throw 'Spotify non si carica: controlla la connessione.' }

        Invoke-Page $context ([IO.File]::ReadAllText($exportScript) + "`n;''") | Out-Null
        $account = Invoke-Page $context "playerless.me('$token').then((p) => p.name || p.uri || 'collegato', () => '')"
        if (-not $account) { return $null }
        Write-Host "Account Spotify: $account"

        Invoke-Page $context @"
window.__playerlessLines = [];
window.__playerlessDone = null;
playerless.exportLibrary('$token', (line) => window.__playerlessLines.push(line)).then(
  (out) => { window.__playerlessDone = { json: JSON.stringify(out) }; },
  (e) => { window.__playerlessDone = { error: String((e && e.message) || e) }; });
''
"@ | Out-Null
        while ($true) {
            Start-Sleep -Milliseconds 700
            $state = Invoke-Page $context 'JSON.stringify({ lines: window.__playerlessLines.splice(0), done: !!window.__playerlessDone, error: window.__playerlessDone && window.__playerlessDone.error })' | ConvertFrom-Json
            foreach ($line in $state.lines) { Write-Host "  $line" }
            if ($state.error) { throw "Lettura da Spotify non riuscita: $($state.error)" }
            if ($state.done) { break }
        }

        # Back in pieces, so no single message gets too big.
        $length = [int](Invoke-Page $context 'String(window.__playerlessDone.json.length)')
        $json = New-Object Text.StringBuilder
        for ($offset = 0; $offset -lt $length; $offset += 300000) {
            [void]$json.Append((Invoke-Page $context "window.__playerlessDone.json.slice($offset, $($offset + 300000))"))
        }
        return $json.ToString()
    } finally {
        try { Invoke-Bidi 'browser.close' @{} | Out-Null } catch {}
        if ($script:socket) { $script:socket.Dispose(); $script:socket = $null }
        for ($i = 0; $i -lt 10 -and (Get-BotFirefox); $i++) { Start-Sleep -Milliseconds 500 }
        Stop-BotFirefox
    }
}

# --- 1. The export ----------------------------------------------------------------------------

if (-not $File) {
    $firefox = Find-Firefox
    if (-not $firefox) {
        Fail 'Non trovo Firefox. Installalo, oppure esporta a mano con spotify-export.js e usa -File.'
    }
    New-BotProfile
    try {
        if ($Login) { Open-SpotifyLogin }
        Write-Host ''
        Write-Host 'Leggo la tua libreria di Spotify...'
        $json = Read-SpotifyLibrary
        if ($null -eq $json) {
            Open-SpotifyLogin
            Write-Host 'Leggo la tua libreria di Spotify...'
            $json = Read-SpotifyLibrary
            if ($null -eq $json) { Fail 'Spotify non risulta ancora collegato: rilancia il bot e accedi nella finestra di Firefox.' }
        }
    } catch {
        Fail "$_"
    }
    $File = Join-Path $env:TEMP 'playerless-spotify.json'
    [IO.File]::WriteAllText($File, $json, (New-Object Text.UTF8Encoding $false))
}
if (-not (Test-Path $File)) { Fail "File non trovato: $File" }

# --- 2. Onto the phone. Opening the app first creates its folder; opening it again starts the import.

Write-Host ''
Write-Host 'Mando le playlist al telefono...'
Adb shell am start -n "$package/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
Adb shell mkdir -p $remote | Out-Null
Adb shell rm -f "$remote/spotify-report.txt" "$remote/spotify-progress.txt" | Out-Null
Adb push $File "$remote/spotify.json" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'Copia sul telefono non riuscita.' }
Adb shell am start -n "$package/.MainActivity" | Out-Null

# --- 3. The app matches every song; its progress and its report come back through the same folder.

Write-Host 'Playerless cerca ogni brano su JioSaavn e YouTube. Tieni il telefono sbloccato con l''app aperta.'
$report = Join-Path $env:TEMP 'playerless-spotify-report.txt'
$deadline = (Get-Date).AddMinutes(60)
$started = Get-Date
$hinted = $false
while ($true) {
    Start-Sleep -Seconds 2
    if ((Adb shell "[ -f $remote/spotify-report.txt ] && echo yes") -match 'yes') { break }
    $progress = ((Adb shell "cat $remote/spotify-progress.txt 2>/dev/null") -join '').Trim()
    if ($progress) {
        Write-Host -NoNewline "`r  Brani controllati: $progress    "
    } elseif (-not $hinted -and ((Get-Date) - $started).TotalSeconds -gt 20 -and
              ((Adb shell "[ -f $remote/spotify.json ] && echo yes") -match 'yes')) {
        Write-Host 'Il telefono non ha ancora iniziato: sbloccalo e apri Playerless.' -ForegroundColor Yellow
        $hinted = $true
    }
    if ((Get-Date) -gt $deadline) { Fail 'Il telefono non ha finito in un''ora.' }
}

Adb pull "$remote/spotify-report.txt" $report | Out-Null
Write-Host ''
Write-Host ''
Write-Host 'Fatto.' -ForegroundColor Green
Get-Content -Path $report -Encoding UTF8 | Write-Host
