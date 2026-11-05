[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._@-]*$')][string]$SshHost,
    [Parameter(Mandatory = $true)][string]$NotesFile,
    [string]$Candidate,
    [switch]$Build,
    [ValidatePattern('^/srv/[A-Za-z0-9_-]+$')][string]$RemoteState = '/srv/baso-kang-gito-release-state',
    [switch]$Publish
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$page = 'https://basokanggito.jevoncl.com'
$api = 'https://basokanggito-api.jevoncl.com/v1/app-version'
$assets = @('index.html', 'site.css', 'site.js', 'favicon.ico', 'favicon-32x32.png', 'apple-touch-icon.png')
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15')
$utf8 = New-Object System.Text.UTF8Encoding($false)
$upload = $null
$remoteAttempted = $false
$remoteStage = $null

function Invoke-Checked([string]$Command, [string[]]$ArgumentList) {
    $stderrFile = [IO.Path]::GetTempFileName()
    try {
        $output = & $Command @ArgumentList 2> $stderrFile
        $exitCode = $LASTEXITCODE
        $diagnostic = [IO.File]::ReadAllText($stderrFile, [Text.Encoding]::UTF8).Trim()
        if ($exitCode -ne 0) {
            throw "$Command failed (exit ${exitCode}): $(@($output | Select-Object -Last 3) -join ' ') $diagnostic"
        }
        if ($diagnostic) { Write-Warning "$Command stderr: $diagnostic" }
        return ($output | Out-String).Trim()
    } finally {
        Remove-Item -LiteralPath $stderrFile -Force
    }
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 10) + "`n", $utf8)
}

function Get-Public([string]$Url, [string]$OutFile = '') {
    $arguments = @{ Uri = $Url; UseBasicParsing = $true; TimeoutSec = 120; MaximumRedirection = 0; Headers = @{ 'Cache-Control' = 'no-cache' } }
    if ($OutFile) { $arguments.OutFile = $OutFile; $arguments.PassThru = $true }
    $response = Invoke-WebRequest @arguments
    Require ($response.StatusCode -eq 200) "Public URL did not return HTTP 200: $Url"
    return $response
}

function Verify-Public($Info, $Policy, [string]$Folder) {
    $nonce = [guid]::NewGuid().ToString('N')
    $websiteFile = Join-Path $Folder 'public-version.json'
    $apiFile = Join-Path $Folder 'public-api-policy.json'
    $website = Get-Public "$page/version.json?verify=$nonce" $websiteFile
    Require (($website.Headers['Cache-Control'] -join ',') -match 'no-store') 'version.json must be served with Cache-Control: no-store (including Cloudflare).'
    $public = Get-Content -LiteralPath $websiteFile -Encoding UTF8 -Raw | ConvertFrom-Json
    $null = Get-Public "$api`?verify=$nonce" $apiFile
    $server = Get-Content -LiteralPath $apiFile -Encoding UTF8 -Raw | ConvertFrom-Json
    foreach ($key in @('latestVersionCode', 'latestVersionName', 'minimumSupportedVersionCode', 'recommendedVersionCode', 'allowMissingVersion', 'downloadPageUrl', 'releaseDate', 'downloadUrl', 'sha256', 'mandatoryUpdateMessage')) {
        Require (($public.$key -ceq $Policy.$key) -and ($server.$key -ceq $Policy.$key)) "Website/API policy mismatch: $key"
    }
    Require ((ConvertTo-Json -InputObject @($public.releaseNotes) -Compress) -ceq (ConvertTo-Json -InputObject @($Policy.releaseNotes) -Compress)) 'Website release notes mismatch'
    Require ((ConvertTo-Json -InputObject @($server.releaseNotes) -Compress) -ceq (ConvertTo-Json -InputObject @($Policy.releaseNotes) -Compress)) 'API release notes mismatch'
    $download = Join-Path $Folder 'public-download.apk'
    $response = Get-Public $Policy.downloadUrl $download
    Require (($response.Headers['Content-Type'] -join ',') -match '^application/vnd.android.package-archive(?:;|$)') 'Incorrect public APK MIME type'
    Require (($response.Headers['Cache-Control'] -join ',') -match 'immutable') 'Versioned APK must use immutable caching'
    Require ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $Info.sha256) 'Public APK checksum mismatch'
    foreach ($asset in $assets) {
        $downloadedAsset = Join-Path $Folder ('public-' + $asset)
        $expectedBytes = [IO.File]::ReadAllBytes((Join-Path $Folder $asset))
        if ($asset -eq 'index.html') {
            $html = $utf8.GetString($expectedBytes)
            foreach ($linked in $assets | Where-Object { $_ -ne 'index.html' }) {
                $hash = (Get-FileHash -LiteralPath (Join-Path $Folder $linked) -Algorithm SHA256).Hash.ToLowerInvariant()
                $html = $html.Replace(('="/' + $linked + '"'), ('="/' + $linked + '?v=' + $hash + '"'))
            }
            $expectedBytes = $utf8.GetBytes($html)
        }
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $expectedHash = ([BitConverter]::ToString($hasher.ComputeHash($expectedBytes))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        $url = if ($asset -eq 'index.html') { "$page/" } else { "$page/$asset`?v=$expectedHash" }
        $null = Get-Public $url $downloadedAsset
        $actualBytes = [IO.File]::ReadAllBytes($downloadedAsset)
        if ($asset -eq 'index.html') {
            $html = $utf8.GetString($actualBytes)
            $beacon = [regex]::Match($html, '<script type="module" src="https://static\.cloudflareinsights\.com/beacon\.min\.js/v[a-f0-9]+" integrity="sha512-[A-Za-z0-9+/]{86}==" data-cf-beacon=''({[^<>\r\n]*})'' crossorigin="anonymous"></script>\n(?=</body>\n</html>\n\z)')
            if ($beacon.Success) {
                try {
                    $config = $beacon.Groups[1].Value | ConvertFrom-Json
                    $keys = ($config.PSObject.Properties.Name | Sort-Object) -join ','
                    if ($keys -ceq 'r,spa,token,version' -and $config.version -is [string] -and $config.version -cmatch '^\d{4}\.\d{2}\.\d+$' -and
                        $config.token -is [string] -and $config.token -cmatch '^[a-f0-9]{32}$' -and
                        ($config.r -is [int] -or $config.r -is [long]) -and $config.r -eq 1 -and
                        ($config.spa -is [int] -or $config.spa -is [long]) -and $config.spa -eq 2) {
                        $actualBytes = $utf8.GetBytes($html.Remove($beacon.Index, $beacon.Length))
                    }
                } catch { <# Unrecognized edge content must fail the checksum check. #> }
            }
        }
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $actualHash = ([BitConverter]::ToString($hasher.ComputeHash($actualBytes))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        Require ($actualHash -ceq $expectedHash) "Public asset checksum mismatch: $asset"
    }
}

Push-Location $root
try {
    Require (([bool]$Candidate) -xor [bool]$Build) 'Supply exactly one of -Candidate <apk> or -Build.'
    foreach ($command in @('git', 'node', 'npm.cmd', 'java', 'ssh', 'scp')) { $null = Get-Command $command -ErrorAction Stop }
    foreach ($asset in $assets) { Require (Test-Path -LiteralPath (Join-Path $root "distribution/$asset") -PathType Leaf) "Missing website source: $asset" }
    Require (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'publish-cloudra.py') -PathType Leaf) 'Missing Linux activation script'
    $checkout = Invoke-Checked 'git' @('rev-parse', '--show-toplevel')
    Require ([IO.Path]::GetFullPath($checkout) -eq $root) 'Run from this source repository, not a copied script.'
    $gradle = Get-Content -LiteralPath (Join-Path $root 'android/app/build.gradle.kts') -Encoding UTF8 -Raw
    $code = [regex]::Matches($gradle, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
    $name = [regex]::Matches($gradle, '(?m)^\s*versionName\s*=\s*"([0-9]+(?:\.[0-9]+){1,3})"\s*$')
    $package = [regex]::Matches($gradle, '(?m)^\s*applicationId\s*=\s*"([^"]+)"\s*$')
    Require ($code.Count -eq 1 -and $name.Count -eq 1 -and $package.Count -eq 1 -and $package[0].Groups[1].Value -ceq 'id.basokanggito.app') 'Unexpected Android release configuration'
    $notesPath = (Resolve-Path -LiteralPath $NotesFile).Path
    $notes = @(ConvertFrom-Json -InputObject (Get-Content -LiteralPath $notesPath -Encoding UTF8 -Raw))
    Require ($notes.Count -ge 1 -and $notes.Count -le 20) 'Supply 1-20 Indonesian release notes in a JSON array.'
    foreach ($note in $notes) { Require ($note -is [string] -and $note.Length -gt 0 -and $note.Length -le 500) 'Invalid release note' }
    Require ((Get-Content -LiteralPath $notesPath -Encoding UTF8 -Raw).TrimStart().StartsWith('[')) 'Release notes must be a JSON array.'
    $null = Invoke-Checked 'npm.cmd' @('--prefix', 'backend', 'run', 'build')
    if ($Build) {
        $null = Invoke-Checked 'node' @('backend/scripts/release.mjs', 'init')
        $prepared = (Invoke-Checked 'node' @('backend/scripts/release.mjs', 'prepare')) | ConvertFrom-Json
        $Candidate = $prepared.candidate
    }
    $candidatePath = (Resolve-Path -LiteralPath $Candidate).Path
    $info = (Invoke-Checked 'node' @('backend/scripts/release.mjs', 'verify', $candidatePath)) | ConvertFrom-Json
    $pin = Get-Content -LiteralPath (Join-Path $root 'android/production-signing.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    Require ($info.packageId -ceq 'id.basokanggito.app' -and $info.certificateSha256 -ceq $pin.certificateSha256) 'Production identity mismatch'
    Require ($info.versionCode -eq [int]$code[0].Groups[1].Value -and $info.versionName -ceq $name[0].Groups[1].Value -and $info.versionCode -gt $pin.referenceVersionCode) 'Candidate must match current Gradle version and exceed the installed production reference.'
    Require ((Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $info.sha256) 'Local APK checksum mismatch'
    Write-Output "Verified $($info.packageId) $($info.versionName) ($($info.versionCode)); SHA-256 $($info.sha256)"
    if (-not $Publish) {
        Write-Output "PASS: dry run. No SSH connection or remote changes. Target: $SshHost $RemoteState; public root /srv/baso-kang-gito-downloads/. Use -Publish only after approval."
        return
    }
    $token = [guid]::NewGuid().ToString('N')
    $upload = Join-Path $root ".releases/private/upload-$token"
    $null = New-Item -ItemType Directory -Path $upload
    Copy-Item -LiteralPath $candidatePath -Destination (Join-Path $upload 'artifact.apk')
    $hashes = @{}
    foreach ($asset in $assets) {
        Copy-Item -LiteralPath (Join-Path $root "distribution/$asset") -Destination (Join-Path $upload $asset)
        $hashes[$asset] = (Get-FileHash -LiteralPath (Join-Path $upload $asset) -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'publish-cloudra.py') -Destination (Join-Path $upload 'publish-cloudra.py')
    Write-Json (Join-Path $upload 'manifest.json') @{ packageId = $info.packageId; versionCode = $info.versionCode; versionName = $info.versionName; certificateSha256 = $info.certificateSha256; sha256 = $info.sha256; assets = $hashes; releaseNotes = $notes }
    $remoteStage = "$RemoteState/staging/upload-$token"
    $preflight = "test -d '$RemoteState/staging' && test -d '$RemoteState/artifacts' && test -L '$RemoteState/current' && test -r '$RemoteState/production-signing.json' && command -v python3 >/dev/null && umask 022 && mkdir -- '$remoteStage'"
    $null = Invoke-Checked 'ssh' ($sshOptions + @($SshHost, $preflight))
    Push-Location $upload
    try {
        $null = Invoke-Checked 'scp' ($sshOptions + @('--', 'artifact.apk', 'manifest.json', 'publish-cloudra.py') + $assets + @("${SshHost}:$remoteStage/"))
    } finally { Pop-Location }
    $remoteAttempted = $true
    $result = (Invoke-Checked 'ssh' ($sshOptions + @($SshHost, "python3 '$remoteStage/publish-cloudra.py' publish --state-root '$RemoteState' --stage '$remoteStage'"))) | ConvertFrom-Json
    Verify-Public $info $result.policy $upload
    Write-Output "PASS: published and publicly verified $($info.versionName) ($($info.versionCode)). Minimum/missing-header policy preserved. Previous generation: $($result.previous)."
    Write-Output "Recovery receipt: $remoteStage/receipt.json. Previous APKs and generations retained."
} catch {
    $failure = $_
    if ($remoteAttempted) {
        try {
            $restored = Invoke-Checked 'ssh' ($sshOptions + @($SshHost, "python3 '$remoteStage/publish-cloudra.py' rollback --state-root '$RemoteState' --stage '$remoteStage'"))
            Write-Warning "Public verification/activation failed; rollback result: $restored"
        } catch {
            Write-Warning "Rollback could not be confirmed. Keep all files. Retry the rollback command in $remoteStage using the same host; it refuses to replace a newer release."
        }
    }
    Write-Error -Message "FAIL: $($failure.Exception.Message). Uploads are retained for inspection; prior APKs are never deleted." -ErrorAction Continue
    throw $failure
} finally {
    Pop-Location
}
