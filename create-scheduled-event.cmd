@echo off
setlocal

set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

for /f %%i in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().AddMinutes(2).ToString(\"yyyy-MM-ddTHH:mm:ssZ\")"') do set SEND_AT=%%i

set IMPORTS=-import-path libs\proto-facade\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/facade/v1/facade.proto
set TARGET=localhost:9090
set SERVICE=notification.facade.v1.NotificationFacade

echo === CreateNotificationEvent (scheduled) ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"idempotencyKey\":\"sched-%RANDOM%\",\"templateId\":\"tmpl-order-reminder\",\"templateVersion\":1,\"priority\":\"DELIVERY_PRIORITY_HIGH\",\"preferredChannel\":\"CHANNEL_EMAIL\",\"strategy\":{\"kind\":\"STRATEGY_KIND_SCHEDULED\",\"sendAt\":\"%SEND_AT%\"},\"payload\":{\"subject\":\"Scheduled test\",\"body\":\"Hello from scheduled cmd request\"},\"audience\":{\"kind\":\"AUDIENCE_KIND_EXPLICIT\",\"snapshotOnDispatch\":true,\"recipientId\":[\"user-212\"]}}" ^
  %TARGET% %SERVICE%/CreateNotificationEvent

endlocal
