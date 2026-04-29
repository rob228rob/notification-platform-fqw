$outPath = "deploy/grafana/dashboard.json"

function New-Ds {
    return @{
        type = "prometheus"
        uid  = '${DS_PROMETHEUS}'
    }
}

function New-StatPanel {
    param(
        [int]$Id,
        [string]$Title,
        [string]$Expr,
        [string]$Legend,
        [int]$X,
        [int]$Y,
        [int]$W = 5,
        [int]$H = 4,
        [string]$Unit = "none",
        [bool]$BinaryUp = $false
    )

    $defaults = @{
        color      = @{ mode = $(if ($BinaryUp) { "thresholds" } else { "palette-classic" }) }
        mappings   = @()
        thresholds = @{
            mode  = "absolute"
            steps = $(if ($BinaryUp) {
                    @(
                        @{ color = "red"; value = 0 },
                        @{ color = "green"; value = 1 }
                    )
                }
                else {
                    @(
                        @{ color = "green"; value = $null }
                    )
                })
        }
        unit       = $Unit
    }
    if ($BinaryUp) {
        $defaults.max = 1
        $defaults.min = 0
    }

    return @{
        datasource  = New-Ds
        fieldConfig = @{
            defaults  = $defaults
            overrides = @()
        }
        gridPos     = @{
            h = $H
            w = $W
            x = $X
            y = $Y
        }
        id          = $Id
        options     = @{
            colorMode     = $(if ($BinaryUp) { "background" } else { "value" })
            graphMode     = $(if ($BinaryUp) { "none" } else { "area" })
            justifyMode   = "center"
            orientation   = "auto"
            reduceOptions = @{
                calcs  = @("lastNotNull")
                fields = ""
                values = $false
            }
            textMode      = $(if ($BinaryUp) { "auto" } else { "value" })
        }
        targets     = @(
            @{
                editorMode   = "code"
                expr         = $Expr
                legendFormat = $Legend
                range        = $true
                refId        = "A"
            }
        )
        title       = $Title
        type        = "stat"
    }
}

function New-TimeSeriesPanel {
    param(
        [int]$Id,
        [string]$Title,
        [array]$Targets,
        [int]$X,
        [int]$Y,
        [int]$W = 12,
        [int]$H = 8,
        [string]$Unit = "none"
    )

    $preparedTargets = @()
    foreach ($t in $Targets) {
        $preparedTargets += @{
            editorMode   = "code"
            expr         = $t.expr
            legendFormat = $t.legendFormat
            range        = $true
            refId        = $t.refId
        }
    }

    return @{
        datasource  = New-Ds
        fieldConfig = @{
            defaults  = @{
                color      = @{ mode = "palette-classic" }
                custom     = @{
                    axisBorderShow = $false
                    axisCenteredZero = $false
                    axisColorMode = "text"
                    axisLabel = ""
                    axisPlacement = "auto"
                    barAlignment = 0
                    drawStyle = "line"
                    fillOpacity = 10
                    gradientMode = "none"
                    hideFrom = @{
                        legend  = $false
                        tooltip = $false
                        viz     = $false
                    }
                    lineInterpolation = "linear"
                    lineWidth = 2
                    pointSize = 3
                    scaleDistribution = @{ type = "linear" }
                    showPoints = "never"
                    spanNulls = $false
                    stacking = @{
                        group = "A"
                        mode  = "none"
                    }
                    thresholdsStyle = @{ mode = "off" }
                }
                mappings   = @()
                thresholds = @{
                    mode  = "absolute"
                    steps = @(
                        @{ color = "green"; value = $null }
                    )
                }
                unit       = $Unit
            }
            overrides = @()
        }
        gridPos     = @{
            h = $H
            w = $W
            x = $X
            y = $Y
        }
        id          = $Id
        options     = @{
            legend  = @{
                calcs       = @()
                displayMode = "table"
                placement   = "bottom"
                showLegend  = $true
            }
            tooltip = @{
                mode = "multi"
                sort = "none"
            }
        }
        targets     = $preparedTargets
        title       = $Title
        type        = "timeseries"
    }
}

$panels = @(
    (New-StatPanel -Id 1 -Title "Service Up" -Expr 'up{job=~"$job"}' -Legend '{{job}}' -X 0 -Y 0 -W 4 -Unit "none" -BinaryUp $true),
    (New-StatPanel -Id 2 -Title "Process Uptime" -Expr 'process_uptime_seconds{application=~"$application"}' -Legend '{{application}}' -X 4 -Y 0 -Unit "s"),
    (New-StatPanel -Id 3 -Title "Process CPU Usage" -Expr 'process_cpu_usage{application=~"$application"}' -Legend '{{application}}' -X 9 -Y 0 -Unit "percentunit"),
    (New-StatPanel -Id 4 -Title "System CPU Usage" -Expr 'system_cpu_usage{application=~"$application"}' -Legend '{{application}}' -X 14 -Y 0 -Unit "percentunit"),
    (New-StatPanel -Id 5 -Title "Heap Usage Ratio" -Expr 'sum by (application) (jvm_memory_used_bytes{application=~"$application", area="heap"}) / max by (application) (jvm_gc_max_data_size_bytes{application=~"$application"})' -Legend '{{application}}' -X 19 -Y 0 -Unit "percentunit"),
    (New-TimeSeriesPanel -Id 6 -Title "gRPC Server RPS" -X 0 -Y 4 -Unit "reqps" -Targets @(
            @{ expr = 'sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_started_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 7 -Title "gRPC Server p95 Latency" -X 12 -Y 4 -Unit "s" -Targets @(
            @{ expr = 'histogram_quantile(0.95, sum by (le, application, grpc_method) (rate(grpc_server_call_duration_seconds_bucket{application=~"$application"}[5m])))'; legendFormat = '{{application}} {{grpc_method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 8 -Title "Kafka Publish RPS" -X 0 -Y 12 -Unit "reqps" -Targets @(
            @{ expr = 'sum by (application, messaging_destination_name, error) (rate(spring_kafka_template_seconds_count{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{messaging_destination_name}} error={{error}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 9 -Title "Kafka Publish Avg Latency" -X 12 -Y 12 -Unit "s" -Targets @(
            @{ expr = 'sum by (application, messaging_destination_name) (rate(spring_kafka_template_seconds_sum{application=~"$application"}[5m])) / sum by (application, messaging_destination_name) (rate(spring_kafka_template_seconds_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{messaging_destination_name}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 10 -Title "Hikari Connections" -X 0 -Y 20 -W 8 -Targets @(
            @{ expr = 'hikaricp_connections_active{application=~"$application"}'; legendFormat = '{{application}} active'; refId = 'A' },
            @{ expr = 'hikaricp_connections_idle{application=~"$application"}'; legendFormat = '{{application}} idle'; refId = 'B' },
            @{ expr = 'hikaricp_connections_pending{application=~"$application"}'; legendFormat = '{{application}} pending'; refId = 'C' }
        )),
    (New-TimeSeriesPanel -Id 11 -Title "Hikari Acquire / Usage Avg" -X 8 -Y 20 -W 8 -Unit "s" -Targets @(
            @{ expr = 'rate(hikaricp_connections_acquire_seconds_sum{application=~"$application"}[5m]) / rate(hikaricp_connections_acquire_seconds_count{application=~"$application"}[5m])'; legendFormat = '{{application}} acquire'; refId = 'A' },
            @{ expr = 'rate(hikaricp_connections_usage_seconds_sum{application=~"$application"}[5m]) / rate(hikaricp_connections_usage_seconds_count{application=~"$application"}[5m])'; legendFormat = '{{application}} usage'; refId = 'B' }
        )),
    (New-TimeSeriesPanel -Id 12 -Title "Executor Threads / Queue" -X 16 -Y 20 -W 8 -Targets @(
            @{ expr = 'executor_active_threads{application=~"$application"}'; legendFormat = '{{application}} {{name}} active'; refId = 'A' },
            @{ expr = 'executor_queued_tasks{application=~"$application"}'; legendFormat = '{{application}} {{name}} queued'; refId = 'B' }
        )),
    (New-TimeSeriesPanel -Id 13 -Title "Scheduled Task Execution Rate" -X 0 -Y 28 -Unit "ops" -Targets @(
            @{ expr = 'sum by (application, code_namespace, code_function, outcome) (rate(tasks_scheduled_execution_seconds_count{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{code_function}} {{outcome}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 14 -Title "Scheduled Task Avg Duration" -X 12 -Y 28 -Unit "s" -Targets @(
            @{ expr = 'sum by (application, code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_sum{application=~"$application"}[5m])) / sum by (application, code_namespace, code_function) (rate(tasks_scheduled_execution_seconds_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{code_function}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 15 -Title "JVM Memory Used" -X 0 -Y 36 -Unit "bytes" -Targets @(
            @{ expr = 'sum by (application, area) (jvm_memory_used_bytes{application=~"$application"})'; legendFormat = '{{application}} {{area}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 16 -Title "JVM Threads" -X 12 -Y 36 -W 6 -Targets @(
            @{ expr = 'jvm_threads_live_threads{application=~"$application"}'; legendFormat = '{{application}} live'; refId = 'A' },
            @{ expr = 'jvm_threads_daemon_threads{application=~"$application"}'; legendFormat = '{{application}} daemon'; refId = 'B' },
            @{ expr = 'jvm_threads_peak_threads{application=~"$application"}'; legendFormat = '{{application}} peak'; refId = 'C' }
        )),
    (New-TimeSeriesPanel -Id 17 -Title "GC Pause / Overhead" -X 18 -Y 36 -W 6 -Unit "s" -Targets @(
            @{ expr = 'rate(jvm_gc_pause_seconds_sum{application=~"$application"}[5m])'; legendFormat = '{{application}} {{gc}}'; refId = 'A' },
            @{ expr = 'jvm_gc_overhead{application=~"$application"}'; legendFormat = '{{application}} overhead'; refId = 'B' }
        )),
    (New-TimeSeriesPanel -Id 18 -Title "gRPC Status RPS" -X 0 -Y 44 -W 8 -Unit "reqps" -Targets @(
            @{ expr = 'sum by (application, grpc_status) (rate(grpc_server_call_started_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 19 -Title "gRPC Method RPS" -X 8 -Y 44 -W 16 -Unit "reqps" -Targets @(
            @{ expr = 'sum by (application, grpc_method) (rate(grpc_server_call_started_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{grpc_method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 20 -Title "gRPC p50 Latency" -X 0 -Y 52 -W 8 -Unit "s" -Targets @(
            @{ expr = 'histogram_quantile(0.50, sum by (le, application, grpc_method) (rate(grpc_server_call_duration_seconds_bucket{application=~"$application"}[5m])))'; legendFormat = '{{application}} {{grpc_method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 21 -Title "gRPC p99 Latency" -X 8 -Y 52 -W 8 -Unit "s" -Targets @(
            @{ expr = 'histogram_quantile(0.99, sum by (le, application, grpc_method) (rate(grpc_server_call_duration_seconds_bucket{application=~"$application"}[5m])))'; legendFormat = '{{application}} {{grpc_method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 22 -Title "gRPC Max Call Duration" -X 16 -Y 52 -W 8 -Unit "s" -Targets @(
            @{ expr = 'max by (application, grpc_method, grpc_status) (grpc_server_call_duration_seconds_max{application=~"$application"})'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 23 -Title "gRPC Processing Avg Duration" -X 0 -Y 60 -W 12 -Unit "s" -Targets @(
            @{ expr = 'sum by (application, service, method, statusCode) (rate(grpc_server_processing_duration_seconds_sum{application=~"$application"}[5m])) / sum by (application, service, method, statusCode) (rate(grpc_server_processing_duration_seconds_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{service}}/{{method}} {{statusCode}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 24 -Title "gRPC Processing Max Duration" -X 12 -Y 60 -W 12 -Unit "s" -Targets @(
            @{ expr = 'max by (application, service, method, statusCode) (grpc_server_processing_duration_seconds_max{application=~"$application"})'; legendFormat = '{{application}} {{service}}/{{method}} {{statusCode}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 25 -Title "gRPC Received Messages Rate" -X 0 -Y 68 -W 12 -Unit "ops" -Targets @(
            @{ expr = 'sum by (application, service, method) (rate(grpc_server_requests_received_messages_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{service}}/{{method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 26 -Title "gRPC Sent Messages Rate" -X 12 -Y 68 -W 12 -Unit "ops" -Targets @(
            @{ expr = 'sum by (application, service, method) (rate(grpc_server_responses_sent_messages_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{service}}/{{method}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 27 -Title "gRPC Received Bytes Throughput" -X 0 -Y 76 -W 12 -Unit "Bps" -Targets @(
            @{ expr = 'sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_rcvd_total_compressed_message_size_bytes_sum{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 28 -Title "gRPC Sent Bytes Throughput" -X 12 -Y 76 -W 12 -Unit "Bps" -Targets @(
            @{ expr = 'sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_sent_total_compressed_message_size_bytes_sum{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 29 -Title "gRPC Avg Request Size" -X 0 -Y 84 -W 12 -Unit "bytes" -Targets @(
            @{ expr = 'sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_rcvd_total_compressed_message_size_bytes_sum{application=~"$application"}[5m])) / sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_rcvd_total_compressed_message_size_bytes_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 30 -Title "gRPC Avg Response Size" -X 12 -Y 84 -W 12 -Unit "bytes" -Targets @(
            @{ expr = 'sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_sent_total_compressed_message_size_bytes_sum{application=~"$application"}[5m])) / sum by (application, grpc_method, grpc_status) (rate(grpc_server_call_sent_total_compressed_message_size_bytes_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 31 -Title "gRPC Max Request Size" -X 0 -Y 92 -W 12 -Unit "bytes" -Targets @(
            @{ expr = 'max by (application, grpc_method, grpc_status) (grpc_server_call_rcvd_total_compressed_message_size_bytes_max{application=~"$application"})'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 32 -Title "gRPC Max Response Size" -X 12 -Y 92 -W 12 -Unit "bytes" -Targets @(
            @{ expr = 'max by (application, grpc_method, grpc_status) (grpc_server_call_sent_total_compressed_message_size_bytes_max{application=~"$application"})'; legendFormat = '{{application}} {{grpc_method}} {{grpc_status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 33 -Title "HTTP Request Rate" -X 0 -Y 100 -W 12 -Unit "reqps" -Targets @(
            @{ expr = 'sum by (application, method, uri, status) (rate(http_server_requests_seconds_count{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{method}} {{uri}} {{status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 34 -Title "HTTP Avg Duration" -X 12 -Y 100 -W 12 -Unit "s" -Targets @(
            @{ expr = 'sum by (application, method, uri, status) (rate(http_server_requests_seconds_sum{application=~"$application"}[5m])) / sum by (application, method, uri, status) (rate(http_server_requests_seconds_count{application=~"$application"}[5m]))'; legendFormat = '{{application}} {{method}} {{uri}} {{status}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 35 -Title "Log Events Rate" -X 0 -Y 108 -W 8 -Unit "ops" -Targets @(
            @{ expr = 'sum by (application, level) (rate(logback_events_total{application=~"$application"}[1m]))'; legendFormat = '{{application}} {{level}}'; refId = 'A' }
        )),
    (New-TimeSeriesPanel -Id 36 -Title "Disk Free / Total" -X 8 -Y 108 -W 8 -Unit "bytes" -Targets @(
            @{ expr = 'disk_free_bytes{application=~"$application"}'; legendFormat = '{{application}} free'; refId = 'A' },
            @{ expr = 'disk_total_bytes{application=~"$application"}'; legendFormat = '{{application}} total'; refId = 'B' }
        )),
    (New-TimeSeriesPanel -Id 37 -Title "JDBC Connections" -X 16 -Y 108 -W 8 -Targets @(
            @{ expr = 'jdbc_connections_active{application=~"$application"}'; legendFormat = '{{application}} active'; refId = 'A' },
            @{ expr = 'jdbc_connections_idle{application=~"$application"}'; legendFormat = '{{application}} idle'; refId = 'B' },
            @{ expr = 'jdbc_connections_max{application=~"$application"}'; legendFormat = '{{application}} max'; refId = 'C' }
        ))
)

$dashboard = @{
    __inputs             = @(
        @{
            name       = "DS_PROMETHEUS"
            label      = "Prometheus"
            description = ""
            type       = "datasource"
            pluginId   = "prometheus"
            pluginName = "Prometheus"
        }
    )
    __requires           = @(
        @{ type = "grafana"; id = "grafana"; name = "Grafana"; version = "10.0.0" },
        @{ type = "datasource"; id = "prometheus"; name = "Prometheus"; version = "1.0.0" },
        @{ type = "panel"; id = "stat"; name = "Stat"; version = "" },
        @{ type = "panel"; id = "timeseries"; name = "Time series"; version = "" }
    )
    annotations          = @{
        list = @(
            @{
                builtIn   = 1
                datasource = @{ type = "grafana"; uid = "-- Grafana --" }
                enable    = $true
                hide      = $true
                iconColor = "rgba(0, 211, 255, 1)"
                name      = "Annotations & Alerts"
                type      = "dashboard"
            }
        )
    }
    description          = "Expanded dashboard for notification services load testing: JVM, gRPC server internals, Kafka, HikariCP, scheduler, HTTP, logs."
    editable             = $true
    fiscalYearStartMonth = 0
    graphTooltip         = 0
    id                   = $null
    links                = @()
    liveNow              = $false
    panels               = $panels
    refresh              = "10s"
    schemaVersion        = 39
    style                = "dark"
    tags                 = @("notifications", "load-test", "grpc", "kafka", "jvm")
    templating           = @{
        list = @(
            @{
                current      = @{ selected = $true; text = "Prometheus"; value = '${DS_PROMETHEUS}' }
                hide         = 0
                includeAll   = $false
                label        = "Datasource"
                name         = "datasource"
                options      = @()
                query        = "prometheus"
                refresh      = 1
                regex        = ""
                skipUrlSync  = $false
                type         = "datasource"
            },
            @{
                allValue     = ".*"
                current      = @{ selected = $true; text = "All"; value = ".*" }
                datasource   = New-Ds
                definition   = "label_values(jvm_info, application)"
                hide         = 0
                includeAll   = $true
                label        = "Application"
                multi        = $true
                name         = "application"
                options      = @()
                query        = @{ qryType = 1; query = "label_values(jvm_info, application)"; refId = "PrometheusVariableQueryEditor-Application" }
                refresh      = 1
                regex        = ""
                skipUrlSync  = $false
                sort         = 1
                type         = "query"
            },
            @{
                allValue     = ".*"
                current      = @{ selected = $true; text = "All"; value = ".*" }
                datasource   = New-Ds
                definition   = "label_values(up, job)"
                hide         = 0
                includeAll   = $true
                label        = "Job"
                multi        = $true
                name         = "job"
                options      = @()
                query        = @{ qryType = 1; query = "label_values(up, job)"; refId = "PrometheusVariableQueryEditor-Job" }
                refresh      = 1
                regex        = ""
                skipUrlSync  = $false
                sort         = 1
                type         = "query"
            }
        )
    }
    time                 = @{ from = "now-1h"; to = "now" }
    timepicker           = @{}
    timezone             = ""
    title                = "Notification Platform Load Test"
    uid                  = "notification-load-test"
    version              = 1
    weekStart            = ""
}

$json = $dashboard | ConvertTo-Json -Depth 100
Set-Content -LiteralPath $outPath -Value $json -Encoding UTF8
Write-Output $outPath
