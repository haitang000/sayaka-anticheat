$ErrorActionPreference = "Stop"

function Write-HookJson {
    param([hashtable]$Value)

    [Console]::Out.WriteLine(($Value | ConvertTo-Json -Depth 8 -Compress))
}

function Get-TextHash {
    param([string]$Text)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "")
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-WorktreeFingerprint {
    param([string]$Root)

    $entries = [Collections.Generic.List[string]]::new()
    $trackedPaths = @(& git -C $Root -c core.quotepath=false diff --name-only --no-renames HEAD --)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect tracked files."
    }

    foreach ($relativePath in @($trackedPaths | Where-Object { $_ } | Sort-Object -Unique)) {
        $fullPath = Join-Path $Root $relativePath
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            $contentHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
            $entries.Add("tracked`t$relativePath`t$contentHash")
        }
        else {
            $entries.Add("tracked`t$relativePath`t<deleted>")
        }
    }

    $untrackedPaths = @(& git -C $Root -c core.quotepath=false ls-files --others --exclude-standard --)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect untracked files."
    }

    foreach ($relativePath in @($untrackedPaths | Where-Object { $_ } | Sort-Object -Unique)) {
        $fullPath = Join-Path $Root $relativePath
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            $contentHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
            $entries.Add("untracked`t$relativePath`t$contentHash")
        }
    }

    return Get-TextHash ($entries -join "`n")
}

function Get-StatePath {
    param(
        [string]$Root,
        [string]$SessionId,
        [string]$TurnId
    )

    $stateDirectory = Join-Path ([IO.Path]::GetTempPath()) "codex-repository-workflow-hooks"
    [IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
    $key = Get-TextHash "$Root`n$SessionId`n$TurnId"
    return Join-Path $stateDirectory "$key.json"
}

try {
    $rawInput = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($rawInput)) {
        exit 0
    }

    $payload = $rawInput | ConvertFrom-Json
    $rootOutput = @(& git -C $payload.cwd rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or $rootOutput.Count -eq 0) {
        Write-HookJson @{ continue = $true }
        exit 0
    }

    $root = $rootOutput[0].Trim()
    $statePath = Get-StatePath -Root $root -SessionId $payload.session_id -TurnId $payload.turn_id

    if ($payload.hook_event_name -eq "UserPromptSubmit") {
        $head = (& git -C $root rev-parse HEAD).Trim()
        $state = @{
            root = $root
            head = $head
            fingerprint = Get-WorktreeFingerprint -Root $root
        }
        $state | ConvertTo-Json -Compress | Set-Content -LiteralPath $statePath -Encoding UTF8

        Write-HookJson @{
            hookSpecificOutput = @{
                hookEventName = "UserPromptSubmit"
                additionalContext = "Repository workflow requirement: after every requested file modification, inspect the resulting diff and run checks appropriate to the changed behavior. Fix failures and rerun checks. When verification succeeds, automatically create a local Git commit containing only files changed for the current request. Preserve pre-existing unrelated changes and untracked files. Never push unless the user explicitly requests it."
            }
        }
        exit 0
    }

    if ($payload.hook_event_name -ne "Stop" -or -not (Test-Path -LiteralPath $statePath)) {
        Write-HookJson @{ continue = $true }
        exit 0
    }

    $baseline = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $currentFingerprint = Get-WorktreeFingerprint -Root $root
    if ($currentFingerprint -eq $baseline.fingerprint) {
        Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
        Write-HookJson @{ continue = $true }
        exit 0
    }

    if ($payload.stop_hook_active) {
        Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
        Write-HookJson @{
            continue = $true
            systemMessage = "The repository still differs from the state recorded at the start of this turn. The workflow reminder has already run once; report any unresolved verification or commit blocker clearly."
        }
        exit 0
    }

    Write-HookJson @{
        decision = "block"
        reason = "This turn still has uncommitted repository changes. Before stopping, inspect the diff, run appropriate bug checks, fix and rerun any failures, then commit only the files changed for this request. Preserve changes that existed before the turn and do not push. If verification cannot pass, do not commit; report the blocker clearly."
    }
}
catch {
    Write-HookJson @{
        continue = $true
        systemMessage = "The repository workflow hook could not complete: $($_.Exception.Message)"
    }
}
