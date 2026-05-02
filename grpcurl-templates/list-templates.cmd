@echo off
set PAGE=0
set SIZE=10
if not "%~1"=="" set PAGE=%~1
if not "%~2"=="" set SIZE=%~2

setlocal
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

echo === ListTemplates page=%PAGE% size=%SIZE% ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"page\":%PAGE%,\"size\":%SIZE%}" ^
  %TARGET% %SERVICE%/ListTemplates

endlocal
