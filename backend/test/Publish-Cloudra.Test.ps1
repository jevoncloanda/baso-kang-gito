# Transport/public-HTTP fakes only; real signature verification is exercised by the dry run.
param([Parameter(Mandatory = $true)][string]$Candidate)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$global:publishTestRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$global:publishTestCandidate = (Resolve-Path -LiteralPath $Candidate).Path
$global:publishTestInfo = (& node (Join-Path $global:publishTestRoot 'backend/scripts/release.mjs') verify $global:publishTestCandidate | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Real candidate verification failed' }
$testFolder = Join-Path $global:publishTestRoot ('.releases/private/publisher-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testFolder
$spacedCandidate = Join-Path $testFolder 'candidate with spaces.apk'
$notesFile = Join-Path $testFolder 'notes with spaces.json'
Copy-Item -LiteralPath $global:publishTestCandidate -Destination $spacedCandidate
$global:publishTestNote = 'Perbaikan ' + [char]0x2013 + ' Buku Harian'
[IO.File]::WriteAllText($notesFile, (ConvertTo-Json -InputObject @($global:publishTestNote)))
$global:publishTestUploads = New-Object System.Collections.Generic.List[string]

function Assert-Test([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function global:npm.cmd { $global:LASTEXITCODE = 0; 'Previously built backend (test fake)' }
function global:node {
    Assert-Test ($args[0] -eq 'backend/scripts/release.mjs' -and $args[1] -eq 'verify') 'Unexpected node command'
    $global:LASTEXITCODE = 0
    $global:publishTestInfo | ConvertTo-Json -Compress
}
function global:scp {
    $global:publishTestScp++
    Assert-Test ($args -contains 'BatchMode=yes' -and $args -contains 'StrictHostKeyChecking=yes') 'SSH must fail closed'
    Assert-Test ($args[-1] -match '^test-cloudra:/srv/baso-kang-gito-release-state/staging/upload-[a-f0-9]{32}/$') 'Unsafe SCP destination'
    foreach ($file in @('artifact.apk', 'manifest.json', 'index.html', 'site.css', 'site.js', 'publish-cloudra.py')) {
        Assert-Test ($args -contains $file -and (Test-Path -LiteralPath $file)) "Missing upload file: $file"
    }
    $global:LASTEXITCODE = if ($global:publishTestMode -eq 'scp') { 1 } else { 0 }
    if ($global:LASTEXITCODE) { 'Simulated upload failure' }
}
function global:ssh {
    $global:publishTestSsh++
    Assert-Test ($args -contains 'BatchMode=yes' -and $args -contains 'StrictHostKeyChecking=yes') 'Unsafe SSH options'
    $command = $args[-1]
    $global:LASTEXITCODE = 0
    if ($command -like '* rollback *') {
        $global:publishTestRollback++
        '{"restored":true,"current":"generations/bootstrap"}'
    } elseif ($command -like '* publish *') {
        $global:publishTestActivation++
        if ($global:publishTestMode -eq 'remote') { $global:LASTEXITCODE = 1; 'Simulated remote failure'; return }
        $global:publishTestPolicy | ConvertTo-Json -Depth 10 -Compress | ForEach-Object { '{"changed":true,"previous":"generations/bootstrap","policy":' + $_ + '}' }
    } else {
        $token = [regex]::Match($command, 'upload-[a-f0-9]{32}').Value
        Assert-Test ($token.Length -gt 0) 'Missing unique staging token'
        $global:publishTestUploads.Add((Join-Path $global:publishTestRoot ".releases/private/$token"))
        if ($global:publishTestMode -eq 'ssh') { $global:LASTEXITCODE = 1; 'Simulated SSH failure' }
    }
}
function global:Invoke-WebRequest {
    param($Uri, [switch]$UseBasicParsing, $TimeoutSec, $MaximumRedirection, $Headers, $OutFile, [switch]$PassThru)
    $url = [uri]$Uri
    $responseHeaders = @{ 'Cache-Control' = 'no-store' }
    $content = ''
    if ($url.AbsolutePath -in @('/version.json', '/v1/app-version')) {
        $copy = $global:publishTestPolicy | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        if ($global:publishTestMode -eq 'api' -and $url.AbsolutePath -eq '/v1/app-version') { $copy.minimumSupportedVersionCode = 2 }
        $content = $copy | ConvertTo-Json -Depth 10
        if ($OutFile) { [IO.File]::WriteAllText($OutFile, $content) }
    } elseif ($url.AbsolutePath.EndsWith('.apk')) {
        Copy-Item -LiteralPath $global:publishTestCandidate -Destination $OutFile
        if ($global:publishTestMode -eq 'checksum') { [IO.File]::AppendAllText($OutFile, 'corrupted public download') }
        $responseHeaders = @{ 'Content-Type' = 'application/vnd.android.package-archive'; 'Cache-Control' = 'public, max-age=31536000, immutable' }
    } else {
        Copy-Item -LiteralPath (Join-Path $global:publishTestRoot ('distribution' + $url.AbsolutePath)) -Destination $OutFile
    }
    [pscustomobject]@{ StatusCode = 200; Headers = $responseHeaders; Content = $content }
}

try {
    $passed = 0
    foreach ($mode in @('success', 'ssh', 'scp', 'remote', 'checksum', 'api')) {
        $global:publishTestMode = $mode
        $global:publishTestSsh = 0; $global:publishTestScp = 0; $global:publishTestActivation = 0; $global:publishTestRollback = 0
        $global:publishTestPolicy = Get-Content -LiteralPath (Join-Path $global:publishTestRoot 'backend/release-policy.json') -Raw | ConvertFrom-Json
        $global:publishTestPolicy.latestVersionCode = $global:publishTestInfo.versionCode
        $global:publishTestPolicy.latestVersionName = $global:publishTestInfo.versionName
        $global:publishTestPolicy.sha256 = $global:publishTestInfo.sha256
        $global:publishTestPolicy.releaseNotes = @($global:publishTestNote)
        $global:publishTestPolicy.releaseDate = '2026-11-04'
        $global:publishTestPolicy.downloadUrl = "https://basokanggito.jevoncl.com/releases/baso-kang-gito-v$($global:publishTestInfo.versionName)-$($global:publishTestInfo.versionCode).apk"
        $failed = $false
        try {
            & (Join-Path $global:publishTestRoot 'backend/scripts/Publish-Cloudra.ps1') -SshHost test-cloudra -NotesFile $notesFile -Candidate $spacedCandidate -Publish *> $null
        } catch { $failed = $true }
        Assert-Test ($failed -eq ($mode -ne 'success')) "Unexpected result for $mode"
        Assert-Test ($global:publishTestSsh -gt 0) "SSH fake unused for $mode"
        Assert-Test ($global:publishTestRollback -eq $(if ($mode -in @('remote', 'checksum', 'api')) { 1 } else { 0 })) "Incorrect rollback behavior for $mode"
        if ($mode -eq 'ssh') { Assert-Test ($global:publishTestScp -eq 0) 'Upload followed failed SSH preflight' }
        if ($mode -eq 'scp') { Assert-Test ($global:publishTestActivation -eq 0) 'Activation followed failed upload' }
        $passed++
    }
    $before = $global:publishTestSsh
    $rejected = $false
    try {
        & (Join-Path $global:publishTestRoot 'backend/scripts/Publish-Cloudra.ps1') -SshHost test-cloudra -RemoteState '/srv/unsafe;touch-file' -NotesFile $notesFile -Candidate $spacedCandidate -Publish *> $null
    } catch { $rejected = $true }
    Assert-Test ($rejected -and $global:publishTestSsh -eq $before) 'Unsafe remote path reached SSH'
    $passed++
    Write-Output "PASS: $passed Windows publishing transport/recovery checks. All SSH/SCP/HTTP calls were local fakes."
} finally {
    foreach ($path in @($testFolder) + @($global:publishTestUploads)) {
        $resolved = [IO.Path]::GetFullPath($path)
        $boundary = [IO.Path]::GetFullPath((Join-Path $global:publishTestRoot '.releases/private')) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside private test storage' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
    foreach ($function in @('node', 'npm.cmd', 'ssh', 'scp', 'Invoke-WebRequest')) { Remove-Item -LiteralPath "Function:\$function" -ErrorAction SilentlyContinue }
}
