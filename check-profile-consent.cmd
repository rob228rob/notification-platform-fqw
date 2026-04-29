@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "HOST=%PROFILE_CONSENT_HOST%"
if not defined HOST set "HOST=localhost"

set "PORT=%PROFILE_CONSENT_PORT%"
if not defined PORT set "PORT=9096"

@REM set "REDIS_CONTAINER=%PROFILE_CONSENT_REDIS_CONTAINER%"
if not defined REDIS_CONTAINER set "REDIS_CONTAINER=redis_container"

set "REDIS_HOST=%PROFILE_CONSENT_REDIS_HOST%"
if not defined REDIS_HOST set "REDIS_HOST=localhost"

set "REDIS_PORT=%PROFILE_CONSENT_REDIS_PORT%"
if not defined REDIS_PORT set "REDIS_PORT=6379"

set "REDIS_USER=%PROFILE_CONSENT_REDIS_USERNAME%"
if not defined REDIS_USER set "REDIS_USER=redisuser"

set "REDIS_PASSWORD=%PROFILE_CONSENT_REDIS_PASSWORD%"
if not defined REDIS_PASSWORD set "REDIS_PASSWORD=redisuserpassword"

set "REDIS_KEY_PREFIX=%PROFILE_CONSENT_REDIS_KEY_PREFIX%"
if not defined REDIS_KEY_PREFIX set "REDIS_KEY_PREFIX=profile-consent:recipient:"

where grpcurl >nul 2>nul
if errorlevel 1 (
  echo [ERROR] grpcurl not found in PATH.
  echo Install grpcurl or add it to PATH, then rerun this script.
  echo Expected command: grpcurl -plaintext %HOST%:%PORT% list
  exit /b 1
)

where docker >nul 2>nul
set "HAS_DOCKER=0"
if not errorlevel 1 set "HAS_DOCKER=1"

set "REDIS_MODE=docker"
if /I "%REDIS_CONTAINER%"=="-" set "REDIS_MODE=host"
if /I "%REDIS_CONTAINER%"=="host" set "REDIS_MODE=host"

if /I "%REDIS_MODE%"=="docker" (
  if "%HAS_DOCKER%"=="0" (
    echo [ERROR] docker not found in PATH.
    echo Either install Docker or run in host mode:
    echo   set PROFILE_CONSENT_REDIS_CONTAINER=host
    echo   set PROFILE_CONSENT_REDIS_HOST=localhost
    echo   set PROFILE_CONSENT_REDIS_PORT=6379
    exit /b 1
  )

  echo [1/6] Checking Redis container "%REDIS_CONTAINER%"...
  docker inspect -f "{{.State.Running}}" "%REDIS_CONTAINER%" 2>nul | findstr /I /C:"true" >nul
  if errorlevel 1 (
    echo [ERROR] Redis container "%REDIS_CONTAINER%" is not running.
    echo If Redis is available on host instead, run:
    echo   set PROFILE_CONSENT_REDIS_CONTAINER=host
    echo   set PROFILE_CONSENT_REDIS_HOST=localhost
    echo   set PROFILE_CONSENT_REDIS_PORT=6379
    exit /b 1
  )
) else (
  where redis-cli >nul 2>nul
  if errorlevel 1 (
    echo [ERROR] redis-cli not found in PATH for host mode.
    echo Either install redis-cli or use Docker mode with:
    echo   set PROFILE_CONSENT_REDIS_CONTAINER=redis_container
    exit /b 1
  )
  echo [1/6] Checking Redis on %REDIS_HOST%:%REDIS_PORT%...
  redis-cli -h %REDIS_HOST% -p %REDIS_PORT% --user %REDIS_USER% -a %REDIS_PASSWORD% PING | findstr /I "PONG" >nul
  if errorlevel 1 (
    echo [ERROR] Redis is not reachable on %REDIS_HOST%:%REDIS_PORT%.
    exit /b 1
  )
)

set "REDIS_WRITE_CMD=docker exec %REDIS_CONTAINER% redis-cli --user %REDIS_USER% -a %REDIS_PASSWORD%"
if /I "%REDIS_MODE%"=="host" set "REDIS_WRITE_CMD=redis-cli -h %REDIS_HOST% -p %REDIS_PORT% --user %REDIS_USER% -a %REDIS_PASSWORD%"

echo [2/6] Seeding test recipient profiles into Redis...
call %REDIS_WRITE_CMD% HSET "%REDIS_KEY_PREFIX%user-allow" active true preferred_channel CHANNEL_EMAIL updated_at 2026-04-04T14:00:00Z email.enabled true email.blacklisted false email.destination user-allow@example.com sms.enabled true sms.blacklisted false sms.destination 79990000001 push.enabled true push.blacklisted false push.destination mobile-user-allow >nul
if errorlevel 1 exit /b 1

call %REDIS_WRITE_CMD% HSET "%REDIS_KEY_PREFIX%user-blocked" active true preferred_channel CHANNEL_SMS updated_at 2026-04-04T14:05:00Z email.enabled true email.blacklisted true email.destination user-blocked@example.com sms.enabled true sms.blacklisted false sms.destination 79990000002 push.enabled false push.blacklisted false push.destination mobile-user-blocked >nul
if errorlevel 1 exit /b 1

call %REDIS_WRITE_CMD% HSET "%REDIS_KEY_PREFIX%user-inactive" active false preferred_channel CHANNEL_PUSH updated_at 2026-04-04T14:10:00Z email.enabled true email.blacklisted false email.destination inactive@example.com sms.enabled true sms.blacklisted false sms.destination 79990000003 push.enabled true push.blacklisted false push.destination mobile-user-inactive >nul
if errorlevel 1 exit /b 1

echo [3/6] Checking gRPC reflection...
grpcurl -plaintext %HOST%:%PORT% list notification.facade.v1.ProfileConsentService
if errorlevel 1 exit /b 1

echo.
echo [4/6] GetRecipientProfile for user-allow
grpcurl -plaintext -d "{\"recipientId\":\"user-allow\"}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/GetRecipientProfile
if errorlevel 1 exit /b 1

echo.
echo [5/6] BatchGetRecipientProfiles for two recipients
grpcurl -plaintext -d "{\"recipientId\":[\"user-allow\",\"user-blocked\"]}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/BatchGetRecipientProfiles
if errorlevel 1 exit /b 1

echo.
echo [6/6] CheckRecipientChannel scenarios
echo --- allowed EMAIL for user-allow ---
grpcurl -plaintext -d "{\"recipientId\":\"user-allow\",\"channel\":\"CHANNEL_EMAIL\"}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/CheckRecipientChannel
if errorlevel 1 exit /b 1

echo --- blacklisted EMAIL for user-blocked ---
grpcurl -plaintext -d "{\"recipientId\":\"user-blocked\",\"channel\":\"CHANNEL_EMAIL\"}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/CheckRecipientChannel
if errorlevel 1 exit /b 1

echo --- inactive PUSH for user-inactive ---
grpcurl -plaintext -d "{\"recipientId\":\"user-inactive\",\"channel\":\"CHANNEL_PUSH\"}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/CheckRecipientChannel
if errorlevel 1 exit /b 1

echo --- profile not found ---
grpcurl -plaintext -d "{\"recipientId\":\"user-missing\",\"channel\":\"CHANNEL_SMS\"}" %HOST%:%PORT% notification.facade.v1.ProfileConsentService/CheckRecipientChannel
if errorlevel 1 exit /b 1

echo.
echo [OK] ProfileConsentService contract checks completed successfully.
echo Service endpoint: %HOST%:%PORT%
if /I "%REDIS_MODE%"=="docker" echo Redis mode: docker, container: %REDIS_CONTAINER%
if /I "%REDIS_MODE%"=="host" echo Redis mode: host, endpoint: %REDIS_HOST%:%REDIS_PORT%
echo Redis key prefix: %REDIS_KEY_PREFIX%
exit /b 0
