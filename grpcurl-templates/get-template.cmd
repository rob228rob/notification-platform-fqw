@echo off
if "%~1"=="" (
  echo Usage: %~nx0 TEMPLATE_ID [VERSION]
  exit /b 1
)
set TEMPLATE_ID=%~1
set VERSION=%~2

setlocal
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

if "%VERSION%"=="" (
  set BODY={\"templateId\":\"%TEMPLATE_ID%\"}
) else (
  set BODY={\"templateId\":\"%TEMPLATE_ID%\",\"version\":%VERSION%}
)

echo === GetTemplate templateId=%TEMPLATE_ID% version=%VERSION% ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "%BODY%" ^
  %TARGET% %SERVICE%/GetTemplate

endlocal
