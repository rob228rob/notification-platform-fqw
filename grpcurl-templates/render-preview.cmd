@echo off
if "%~1"=="" (
  echo Usage: %~nx0 TEMPLATE_ID [VERSION] [NAME]
  exit /b 1
)
set TEMPLATE_ID=%~1
set VERSION=1
if not "%~2"=="" set VERSION=%~2
set NAME=Alice
if not "%~3"=="" set NAME=%~3

setlocal
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

echo === RenderPreview templateId=%TEMPLATE_ID% version=%VERSION% name=%NAME% ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"templateId\":\"%TEMPLATE_ID%\",\"version\":%VERSION%,\"channel\":\"CHANNEL_EMAIL\",\"payload\":{\"name\":\"%NAME%\"}}" ^
  %TARGET% %SERVICE%/RenderPreview

endlocal
