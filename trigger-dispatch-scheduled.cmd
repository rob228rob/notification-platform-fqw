@echo off
setlocal

set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

if "%~1"=="" (
  echo Usage: %~nx0 ^<event_id^> [send_at_utc]
  echo Example: %~nx0 ddd6a93c-2a4d-4908-8632-b01c8745ed4d 2026-03-22T19:10:00Z
  exit /b 1
)

set EVENT_ID=%~1

if "%~2"=="" (
  for /f %%i in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().AddMinutes(10).ToString(\"yyyy-MM-ddTHH:mm:ssZ\")"') do set SEND_AT=%%i
) else (
  set SEND_AT=%~2
)

set IMPORTS=-import-path libs\proto-facade\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/facade/v1/facade.proto
set TARGET=localhost:9090
set SERVICE=notification.facade.v1.NotificationFacade

echo === TriggerDispatch (scheduled) ===
echo event_id=%EVENT_ID%
echo override_send_at=%SEND_AT%

"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"eventId\":\"%EVENT_ID%\",\"idempotencyKey\":\"dispatch-%RANDOM%\",\"overrideSendAt\":\"%SEND_AT%\"}" ^
  %TARGET% %SERVICE%/TriggerDispatch

endlocal
