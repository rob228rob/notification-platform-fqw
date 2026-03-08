@echo off
setlocal

set "GRPCURL=grpcurl"
set "JSON_FILE=%~dp0grpcurl-create-event.full.json"
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe" set "GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe"

%GRPCURL% -v -plaintext -d @ localhost:9090 notification.facade.v1.NotificationFacade/CreateNotificationEvent < "%JSON_FILE%"

endlocal
