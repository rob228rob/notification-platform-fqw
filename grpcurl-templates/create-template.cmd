@echo off
setlocal
set GRPCURL=grpcurl
where grpcurl >nul 2>nul || set GRPCURL=%LOCALAPPDATA%\Microsoft\WinGet\Packages\FullStorydev.grpcurl_Microsoft.Winget.Source_8wekyb3d8bbwe\grpcurl.exe

set IMPORTS=-import-path libs\proto-templates\src\main\proto -import-path libs\proto-common\src\main\proto
set PROTO=-proto ../notification/templates/v1/templates.proto
set TARGET=localhost:9090
set SERVICE=notification.templates.v1.TemplateRegistry

echo === CreateTemplate ===
"%GRPCURL%" -plaintext %IMPORTS% %PROTO% ^
  -d "{\"idempotencyKey\":\"idem-%RANDOM%\",\"name\":\"welcome\",\"description\":\"welcome template\",\"engine\":\"TEMPLATE_ENGINE_HANDLEBARS\",\"contents\":[{\"channel\":\"CHANNEL_EMAIL\",\"subject\":\"Hello {{name}}\",\"body\":\"Hi {{name}}, welcome\"}]}" ^
  %TARGET% %SERVICE%/CreateTemplate

endlocal
