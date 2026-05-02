param(
    [string]$KafkaContainer = "kafka",
    [string]$PostgresContainer = "postgres-db",
    [string]$PostgresDb = "main_db",
    [string]$PostgresUser = "user",
    [string]$InputTopic = "notification.mail.dispatches.scheduled",
    [string]$OutputTopic = "notification.mail.dispatches",
    [int]$DelaySeconds = 10,
    [int]$TimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-InContainerPsql {
    param(
        [Parameter(Mandatory = $true)][string]$Sql
    )
    docker exec $PostgresContainer psql -U $PostgresUser -d $PostgresDb -t -A -c $Sql
}

$dispatchId = [guid]::NewGuid().ToString()
$eventId = [guid]::NewGuid().ToString()
$clientId = [guid]::NewGuid().ToString()
$plannedAt = [DateTimeOffset]::UtcNow.AddSeconds($DelaySeconds).ToString("yyyy-MM-ddTHH:mm:ss.fffK")
$createdAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffK")

$payload = @{
    dispatch_id       = $dispatchId
    event_id          = $eventId
    client_id         = $clientId
    preferred_channel = "CHANNEL_EMAIL"
    template_id       = "smoke-template"
    template_version  = 1
    payload           = @{
        subject = "scheduler smoke"
        body    = "scheduler smoke body"
    }
    recipient_ids     = @("smoke-recipient")
    planned_send_at   = $plannedAt
    created_at        = $createdAt
}

$message = @{
    outboxId       = 9999999
    aggregateType  = "mail_dispatch"
    aggregateId    = $dispatchId
    eventType      = "MailDispatchRequested"
    payload        = $payload
    headers        = @{
        message_id = $dispatchId
        event_type = "MailDispatchRequested"
    }
    createdAt      = $createdAt
} | ConvertTo-Json -Compress -Depth 10

Write-Host "Producing scheduled message for dispatchId=$dispatchId plannedAt=$plannedAt"
$message | docker exec -i $KafkaContainer bash -lc "kafka-console-producer --bootstrap-server localhost:9092 --topic $InputTopic"

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$published = $false

while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $status = Invoke-InContainerPsql -Sql "select status from nf_sched.scheduled_delivery_task where aggregate_id = '$dispatchId' order by task_id desc limit 1;"
    if ($status -match "PUBLISHED") {
        $published = $true
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $published) {
    Write-Error "Timeout: task was not published by scheduler-delivery. Check service logs and DB rows for aggregate_id=$dispatchId"
}

$row = Invoke-InContainerPsql -Sql "select task_id||'|'||status||'|'||coalesce(to_char(published_at, 'YYYY-MM-DD\"T\"HH24:MI:SSOF'), 'null') from nf_sched.scheduled_delivery_task where aggregate_id = '$dispatchId' order by task_id desc limit 1;"
Write-Host "DB row: $row"
Write-Host "Smoke test passed: scheduler-delivery moved message from $InputTopic to publish stage for $OutputTopic"
