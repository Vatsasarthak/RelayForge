# Runs the k6 load test inside the docker compose network (no local k6 install needed), then computes the exact
# end-to-end delivery latency (sent_at - created_at) of the run's notifications from PostgreSQL.
# Usage (from the project folder, with the stack running):
#   .\perf\run-load-test.ps1 -Rate 100 -Duration 60s [-MaxVus 500]
# Results:
#   perf\results\k6-rate<rate>-<timestamp>.json        k6 summary (API side)
#   perf\results\delivery-rate<rate>-<timestamp>.json  delivery latency percentiles (from the database)
# k6 exits with code 99 when a threshold (p95 < 500 ms, errors < 1 %) was crossed.
param(
    [int]$Rate = 100,
    [string]$Duration = "60s",
    [int]$MaxVus = 200
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw ".env not found in $root" }
function Get-EnvValue([string]$name, [string]$default = $null) {
    $match = Select-String -Path $envFile -Pattern "^$name=(.*)$"
    if (-not $match) {
        if ($null -ne $default) { return $default }
        throw "$name missing in .env"
    }
    return $match.Matches[0].Groups[1].Value
}
# read the API key without printing it
$apiKey = Get-EnvValue "API_KEY"
$dbUser = Get-EnvValue "POSTGRES_USER"
$dbName = Get-EnvValue "POSTGRES_DB"
$appUrl = "http://localhost:" + (Get-EnvValue "APP_PORT" "8080")
# compose network name = "<project>_default" (COMPOSE_PROJECT_NAME=notifyr for pre-rename installations)
$network = (Get-EnvValue "COMPOSE_PROJECT_NAME" "relayforge") + "_default"

$resultsDir = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $resultsDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryFile = Join-Path $resultsDir "k6-rate$Rate-$stamp.json"

# A fresh API_CLIENT user per run, so its notifications can be counted afterwards. It is created here and not in
# the k6 script's setup(): k6 writes setup()'s return value into the --summary-export file, so a token obtained in
# setup() would be stored in perf\results. The token reaches k6 only as an environment variable, which k6 never
# exports, and it is not printed.
$loadTestUser = "loadtest-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$passwordBytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$registration = @{ username = $loadTestUser; password = [Convert]::ToBase64String($passwordBytes); role = "API_CLIENT" } |
        ConvertTo-Json
try {
    $auth = Invoke-RestMethod -Uri "$appUrl/auth/register" -Method Post -ContentType "application/json" -Body $registration
} catch {
    throw "Could not create the load-test user at $appUrl/auth/register (is the stack running?): $($_.Exception.Message)"
}
if (-not $auth.token) { throw "The registration response contained no token" }

# passed to docker by name only (-e NAME), so the values are not part of the command line
$env:API_KEY = $apiKey
$env:AUTH_TOKEN = $auth.token
$env:LOADTEST_USER = $loadTestUser
# k6 writes its log lines to stderr; do not let PowerShell turn them into terminating errors
$ErrorActionPreference = "Continue"
try {
    docker run --rm --network $network `
        -v "${PSScriptRoot}\k6:/scripts:ro" -v "${resultsDir}:/results" `
        -e API_KEY -e AUTH_TOKEN -e LOADTEST_USER -e RATE=$Rate -e DURATION=$Duration -e MAX_VUS=$MaxVus `
        grafana/k6:0.54.0 run --quiet --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" `
        --summary-export "/results/k6-rate$Rate-$stamp.json" /scripts/send-notifications.js 2>&1 | ForEach-Object { "$_" }
    $k6Exit = $LASTEXITCODE
} finally {
    Remove-Item Env:API_KEY, Env:AUTH_TOKEN, Env:LOADTEST_USER -ErrorAction SilentlyContinue
    $auth = $null
}
$ErrorActionPreference = "Stop"

# Safety net: the exported summary must never contain a bearer token (it is meant to be committed).
if (Test-Path $summaryFile) {
    if (Select-String -Path $summaryFile -Pattern 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.' -Quiet) {
        throw ("$summaryFile contains a JWT. Do not commit it; delete or sanitize it and check the k6 script's " +
                "setup() return value.")
    }
} else {
    Write-Warning "k6 did not write $summaryFile (k6 exit code $k6Exit)"
}

# exact delivery latency of this run's notifications, computed in PostgreSQL ($loadTestUser is generated above)
$sql = @"
SELECT json_build_object(
  'user', owner, 'delivered', count(*),
  'p50_ms', round((percentile_cont(0.50) WITHIN GROUP (ORDER BY ms))::numeric, 1),
  'p95_ms', round((percentile_cont(0.95) WITHIN GROUP (ORDER BY ms))::numeric, 1),
  'p99_ms', round((percentile_cont(0.99) WITHIN GROUP (ORDER BY ms))::numeric, 1),
  'max_ms', round(max(ms)::numeric, 1))
FROM (SELECT owner, extract(epoch FROM (sent_at - created_at)) * 1000 AS ms
      FROM notifications
      WHERE owner = '$loadTestUser'
        AND sent_at IS NOT NULL) t
GROUP BY owner;
"@
$latency = ($sql | docker exec -i relayforge_postgres psql -U $dbUser -d $dbName -tA) -join ""
$latency | Set-Content -Encoding utf8 (Join-Path $resultsDir "delivery-rate$Rate-$stamp.json")
"DELIVERY_LATENCY $latency"
exit $k6Exit
