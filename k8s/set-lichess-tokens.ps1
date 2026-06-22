# Updates the `almachess-secrets` Secret on the AlMaChess server with
# fresh Lichess tokens and restarts the API so it picks them up.
#
# Tokens are read silently (no echo, no PowerShell history entry).
# The Secret manifest is written to a short-lived local temp file,
# uploaded via SFTP, applied, and the remote copy is deleted.
#
# Requires:
#   - python with paramiko (pip install paramiko)
#   - C:\Users\leo11\.almachess_deploy\ssh.py (the SSH helper from the
#     server-deploy flow). Override with -SshHelper if you moved it.

[CmdletBinding()]
param(
    [string]$ServerHost      = '141.37.74.146',
    [string]$ServerUser      = 'chess',
    [string]$Namespace       = 'almachess',
    [string]$PostgresPassword = 'almachess',
    [string]$SshHelper       = 'C:\Users\leo11\.almachess_deploy\ssh.py'
)

$ErrorActionPreference = 'Stop'

function Read-Plain($prompt) {
    $sec = Read-Host -AsSecureString -Prompt $prompt
    [System.Net.NetworkCredential]::new('', $sec).Password
}

Write-Host "Updating Lichess tokens on $ServerUser@$ServerHost ($Namespace)..."

$boardPlain = Read-Plain 'Lichess Board-Token'
$botPlain   = Read-Plain 'Lichess Bot-Token'

if (-not $env:ALMACHESS_PASS) {
    $env:ALMACHESS_PASS = Read-Plain "SSH password for $ServerUser@$ServerHost"
}
$env:ALMACHESS_HOST = $ServerHost
$env:ALMACHESS_USER = $ServerUser

$yaml = @"
apiVersion: v1
kind: Secret
metadata:
  name: almachess-secrets
  namespace: $Namespace
type: Opaque
stringData:
  POSTGRES_PASSWORD: "$PostgresPassword"
  LICHESS_BOARD_TOKEN: "$boardPlain"
  LICHESS_BOT_TOKEN: "$botPlain"
"@

$local  = Join-Path $env:TEMP "almachess-secret-$([guid]::NewGuid().Guid).yaml"
$remote = '/tmp/almachess-secret.yaml'

try {
    [IO.File]::WriteAllText($local, $yaml, [System.Text.UTF8Encoding]::new($false))

    Write-Host '[1/3] uploading manifest...'
    python $SshHelper put $local $remote

    Write-Host '[2/3] applying on cluster + rolling out API...'
    $cmd = @"
export PATH=`$HOME/bin:`$PATH
kubectl apply -f $remote
rm -f $remote
kubectl -n $Namespace rollout restart deploy/almachess-api
kubectl -n $Namespace rollout status   deploy/almachess-api --timeout=120s
"@
    python $SshHelper run $cmd

    Write-Host '[3/3] done.'
}
finally {
    if (Test-Path $local) { Remove-Item $local -Force -ErrorAction SilentlyContinue }
    $boardPlain = $null
    $botPlain   = $null
    [GC]::Collect()
}
