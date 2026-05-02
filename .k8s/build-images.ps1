$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
Push-Location $repoRoot

try {
    .\gradlew `
      :services:notification-facade:bootJar `
      :services:notification-mail-sender:bootJar `
      :services:notification-sms-sender:bootJar `
      :services:profile-consent:bootJar `
      :services:history-writer:bootJar `
      :services:scheduler-delivery:bootJar `
      :services:template-registry:bootJar `
      :services:custom-loader:installDist

    $services = @(
      @{ module = "notification-facade"; image = "notification-facade" },
      @{ module = "notification-mail-sender"; image = "notification-mail-sender" },
      @{ module = "notification-sms-sender"; image = "notification-sms-sender" },
      @{ module = "profile-consent"; image = "profile-consent" },
      @{ module = "history-writer"; image = "history-writer" },
      @{ module = "scheduler-delivery"; image = "scheduler-delivery" },
      @{ module = "template-registry"; image = "template-registry" }
    )

    foreach ($service in $services) {
        $jar = Get-ChildItem "services/$($service.module)/build/libs/*.jar" |
          Where-Object { $_.Name -notlike "*-plain.jar" } |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1

        if (-not $jar) {
            throw "Не найден JAR для модуля $($service.module)"
        }

        $relativeJar = $jar.FullName.Substring($repoRoot.Length + 1).Replace("\", "/")
        docker build `
          -f .k8s/docker/Dockerfile.spring `
          --build-arg "JAR_FILE=$relativeJar" `
          -t "$($service.image):latest" `
          .
    }

    docker build -f services/custom-loader/Dockerfile -t custom-loader:latest .
}
finally {
    Pop-Location
}
