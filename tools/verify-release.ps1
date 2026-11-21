param(
    [string]$Apk = 'app/build/outputs/apk/release/app-release.apk',
    [string]$ArchiveDirectory = 'artifacts'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskApk = [IO.Path]::GetFullPath((Join-Path $taskRoot $Apk))
$taskArchive = [IO.Path]::GetFullPath((Join-Path $taskRoot $ArchiveDirectory))
if (!(Test-Path -LiteralPath $taskApk)) { throw 'Release APK does not exist' }
$taskSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$taskTools = Join-Path $taskSdk 'build-tools\36.0.0'
$taskAapt = Join-Path $taskTools 'aapt.exe'
$taskSigner = Join-Path $taskTools 'apksigner.bat'
$taskAlign = Join-Path $taskTools 'zipalign.exe'
New-Item -ItemType Directory -Path $taskArchive -Force | Out-Null

$taskBadging = & $taskAapt dump badging $taskApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK metadata verification failed' }
$taskBadgingText = $taskBadging -join "`n"
if ($taskBadgingText -notmatch "package: name='io.github.yudigaga.lightfreeze' versionCode='18' versionName='0.8.3'") { throw 'Unexpected application ID or version' }
if ($taskBadgingText -match 'application-debuggable|testOnly') { throw 'Debug or test APK cannot be archived as Release' }
if ($taskBadgingText -notmatch "sdkVersion:'33'" -or $taskBadgingText -notmatch "targetSdkVersion:'36'") { throw 'Unexpected SDK contract' }
$taskManifest = & $taskAapt dump xmltree $taskApk AndroidManifest.xml 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Manifest component verification failed' }
$taskManifestText = $taskManifest -join "`n"
if ($taskManifestText -notmatch 'io.github.yudigaga.lightfreeze.ui.UrlActionActivity' -or $taskManifestText -notmatch '"lightfreeze-yudigaga"') { throw 'Frozen URL component or scheme missing' }
if ($taskManifestText -match 'E: service|android.permission.INTERNET') { throw 'Unexpected persistent service or network permission' }

$taskSignature = & $taskSigner verify --verbose --print-certs $taskApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$taskSignatureText = $taskSignature -join "`n"
if ($taskSignatureText -notmatch 'Verified using v3 scheme.*true') { throw 'Release must have a verified v3 signature' }
# apksigner selects v3 for minSdk33; verify the v2 block separately in its platform range.
$taskSignatureV2 = & $taskSigner verify --verbose --min-sdk-version 24 --max-sdk-version 27 $taskApk 2>&1
if ($LASTEXITCODE -ne 0 -or ($taskSignatureV2 -join "`n") -notmatch 'Verified using v2 scheme.*true') { throw 'Release v2 signature block verification failed' }
$taskCertMatch = [regex]::Match($taskSignatureText, 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)')
if (!$taskCertMatch.Success) { throw 'Signing certificate digest missing' }
$taskCert = $taskCertMatch.Groups[1].Value.ToUpperInvariant()
$taskExpectedCert = Join-Path $taskRoot 'release-certificate.sha256'
if (!(Test-Path -LiteralPath $taskExpectedCert) -or (Get-Content -LiteralPath $taskExpectedCert -Raw).Trim() -ne $taskCert) { throw 'Signing certificate does not match the project Release key' }
$taskAlignment = & $taskAlign -c -P 16 -v 4 $taskApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [IO.Compression.ZipFile]::OpenRead($taskApk)
try {
    foreach ($entryName in @('META-INF/xposed/java_init.list', 'META-INF/xposed/module.prop', 'META-INF/xposed/scope.list')) {
        $taskEntry = $taskZip.GetEntry($entryName)
        if (!$taskEntry) { throw "Missing API102 metadata: $entryName" }
        $taskReader = [IO.StreamReader]::new($taskEntry.Open())
        try { $taskContent = $taskReader.ReadToEnd().Trim() } finally { $taskReader.Dispose() }
        if ($entryName.EndsWith('java_init.list') -and $taskContent -ne 'io.github.yudigaga.lightfreeze.system.FreezerModule') { throw 'Unexpected module entry' }
        if ($entryName.EndsWith('scope.list') -and $taskContent -ne 'system') { throw 'Unexpected system scope' }
        if ($entryName.EndsWith('module.prop') -and ($taskContent -notmatch 'minApiVersion=102' -or $taskContent -notmatch 'targetApiVersion=102')) { throw 'Unexpected module API version' }
    }
} finally { $taskZip.Dispose() }

$taskTests = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'app\build\test-results\testDebugUnitTest') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
if ($taskTests.Count -eq 0) { throw 'No unit-test results found' }
$taskCount = 0; $taskFailures = 0; $taskErrors = 0; $taskSkipped = 0
foreach ($taskTest in $taskTests) {
    $taskSuite = ([xml](Get-Content -LiteralPath $taskTest.FullName -Raw)).testsuite
    $taskCount += [int]$taskSuite.tests; $taskFailures += [int]$taskSuite.failures
    $taskErrors += [int]$taskSuite.errors; $taskSkipped += [int]$taskSuite.skipped
}
if ($taskFailures -gt 0 -or $taskErrors -gt 0) { throw 'Unit tests have failures or errors' }
$taskLintPath = Join-Path $taskRoot 'app\build\reports\lint-results-release.xml'
if (!(Test-Path -LiteralPath $taskLintPath)) { throw 'Release lint report missing' }
$taskLint = [xml](Get-Content -LiteralPath $taskLintPath -Raw)
$taskLintErrors = @($taskLint.issues.issue | Where-Object {$_.severity -in @('Error','Fatal')}).Count
$taskLintWarnings = @($taskLint.issues.issue | Where-Object {$_.severity -eq 'Warning'}).Count
if ($taskLintErrors -ne 0) { throw 'Release lint has errors' }

$taskFile = Join-Path $taskArchive 'LightFreeze-0.8.3-release.apk'
Copy-Item -LiteralPath $taskApk -Destination $taskFile -Force
$taskHash = (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash
$taskMapping = Join-Path $taskRoot 'app\build\outputs\mapping\release\mapping.txt'
if (!(Test-Path -LiteralPath $taskMapping)) { throw 'R8 Release mapping missing' }
$taskMappingText = Get-Content -LiteralPath $taskMapping -Raw
if ($taskMappingText -notmatch '# compiler: R8') { throw 'Unexpected Release compiler mapping' }
$taskMappingArchive = Join-Path $taskArchive 'LightFreeze-0.8.3-mapping.txt'
Copy-Item -LiteralPath $taskMapping -Destination $taskMappingArchive -Force
$taskMappingHash = (Get-FileHash -LiteralPath $taskMappingArchive -Algorithm SHA256).Hash
$taskEvidence = [ordered]@{
    packageName = 'io.github.yudigaga.lightfreeze'; versionName = '0.8.3'; versionCode = 18
    apk = $taskFile; bytes = (Get-Item -LiteralPath $taskFile).Length; sha256 = $taskHash
    certificateSha256 = $taskCert; signatureV2 = $true; signatureV3 = $true; alignment16KiB = $true
    r8 = $true; mapping = $taskMappingArchive; mappingSha256 = $taskMappingHash
    minSdk = 33; targetSdk = 36; api102 = $true
    tests = @{tests=$taskCount; failures=$taskFailures; errors=$taskErrors; skipped=$taskSkipped}
    lint = @{errors=$taskLintErrors; warnings=$taskLintWarnings}
    limitations = @('Owner-rule upgrade, transactional management removal and new member-selection UI not physical-device verified', 'High-risk theme/system-UID PM writes and ROM restrictions not device-verified', 'No physical-device acceptance for frozen-body URL resolver/launch hooks or card-removal hooks', 'Root/Shizuku cold-start service authorization and PM writes not device-verified', 'No post-change device latency measurements; Toast, native/launcher/multi-trigger/ROM behavior not device-verified', 'Disabled-module cold-boot reinjection and external recovery not device-verified; frozen URL supports only compatible no-result ACTION_VIEW launchers')
}
$taskEvidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskArchive 'LightFreeze-0.8.3-release.json') -Encoding utf8NoBOM
"$taskHash  LightFreeze-0.8.3-release.apk" | Set-Content -LiteralPath (Join-Path $taskArchive 'SHA256SUMS.txt') -Encoding utf8NoBOM
$taskBadgingText | Set-Content -LiteralPath (Join-Path $taskArchive 'release-badging.txt') -Encoding utf8NoBOM
$taskSignatureText | Set-Content -LiteralPath (Join-Path $taskArchive 'release-signature.txt') -Encoding utf8NoBOM
$taskSignatureV2 | Set-Content -LiteralPath (Join-Path $taskArchive 'release-signature-v2.txt') -Encoding utf8NoBOM
$taskAlignment | Set-Content -LiteralPath (Join-Path $taskArchive 'release-alignment.txt') -Encoding utf8NoBOM
$taskEvidence | ConvertTo-Json -Depth 5
