@echo off
setlocal enabledelayedexpansion

rem Locate grpcurl (winget default path fallback)
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

echo === CreateTemplate ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"idempotencyKey\":\"idem-%RANDOM%\",\"name\":\"welcome\",\"description\":\"welcome template\",\"engine\":\"TEMPLATE_ENGINE_HANDLEBARS\",\"contents\":[{\"channel\":\"CHANNEL_EMAIL\",\"subject\":\"Hello {{name}}\",\"body\":\"Hi {{name}}, welcome\"}]}" ^
  %TARGET% %SERVICE%/CreateTemplate

echo.
echo Replace TEMPLATE_ID below with value from CreateTemplate response.

echo === UpdateTemplate ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"templateId\":\"TEMPLATE_ID\",\"name\":\"welcome v2\",\"description\":\"welcome template updated\",\"engine\":\"TEMPLATE_ENGINE_HANDLEBARS\",\"contents\":[{\"channel\":\"CHANNEL_EMAIL\",\"subject\":\"Hello {{name}} v2\",\"body\":\"Hi {{name}}, welcome again\"}]}" ^
  %TARGET% %SERVICE%/UpdateTemplate

echo === GetTemplate (active) ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"templateId\":\"TEMPLATE_ID\"}" ^
  %TARGET% %SERVICE%/GetTemplate

echo === ListTemplates ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"page\":0,\"size\":10}" ^
  %TARGET% %SERVICE%/ListTemplates

echo === RenderPreview ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"templateId\":\"TEMPLATE_ID\",\"version\":1,\"channel\":\"CHANNEL_EMAIL\",\"payload\":{\"name\":\"Alice\"}}" ^
  %TARGET% %SERVICE%/RenderPreview

endlocal
