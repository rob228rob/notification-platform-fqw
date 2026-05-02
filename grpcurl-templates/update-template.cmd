@echo off
if "%~1"=="" (
  echo Usage: %~nx0 TEMPLATE_ID [NAME] [DESC]
  exit /b 1
)
set TEMPLATE_ID=%~1
set NAME=%~2
if "%NAME%"=="" set NAME=welcome-updated
set DESC=%~3
if "%DESC%"=="" set DESC=updated template

setlocal
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

echo === UpdateTemplate templateId=%TEMPLATE_ID% ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"templateId\":\"%TEMPLATE_ID%\",\"name\":\"%NAME%\",\"description\":\"%DESC%\",\"engine\":\"TEMPLATE_ENGINE_HANDLEBARS\",\"contents\":[{\"channel\":\"CHANNEL_EMAIL\",\"subject\":\"Hello {{name}} updated\",\"body\":\"Hi {{name}}, welcome again\"}]}" ^
  %TARGET% %SERVICE%/UpdateTemplate

endlocal
