@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

if "%~1"=="" goto :usage

set "RECIPIENT_ID=%~1"
set "EMAIL=%~2"
set "PHONE=%~3"
set "PUSH_ID=%~4"
set "PREFERRED_CHANNEL=%~5"
if not defined PREFERRED_CHANNEL set "PREFERRED_CHANNEL=CHANNEL_EMAIL"
set "ACTIVE=%~6"
if not defined ACTIVE set "ACTIVE=true"

set "REDIS_CONTAINER=%PROFILE_CONSENT_REDIS_CONTAINER%"
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

set "REDIS_MODE=docker"
if /I "%REDIS_CONTAINER%"=="host" set "REDIS_MODE=host"
if /I "%REDIS_CONTAINER%"=="-" set "REDIS_MODE=host"

set "EMAIL_ENABLED=false"
if defined EMAIL set "EMAIL_ENABLED=true"

set "SMS_ENABLED=false"
if defined PHONE set "SMS_ENABLED=true"

set "PUSH_ENABLED=false"
if defined PUSH_ID set "PUSH_ENABLED=true"

set "UPDATED_AT=%date:~-4%-%date:~3,2%-%date:~0,2%T%time:~0,8%Z"
set "UPDATED_AT=%UPDATED_AT: =0%"

where docker >nul 2>nul
set "HAS_DOCKER=0"
if not errorlevel 1 set "HAS_DOCKER=1"

if /I "%REDIS_MODE%"=="docker" (
  if "%HAS_DOCKER%"=="0" (
    echo [ERROR] docker not found in PATH.
    goto :usage_host
  )
  docker inspect -f "{{.State.Running}}" "%REDIS_CONTAINER%" 2>nul | findstr /I /C:"true" >nul
  if errorlevel 1 (
    echo [ERROR] Redis container "%REDIS_CONTAINER%" is not running.
    goto :usage_host
  )
  set "REDIS_CMD=docker exec %REDIS_CONTAINER% redis-cli --user %REDIS_USER% -a %REDIS_PASSWORD%"
) else (
  where redis-cli >nul 2>nul
  if errorlevel 1 (
    echo [ERROR] redis-cli not found in PATH for host mode.
    exit /b 1
  )
  set "REDIS_CMD=redis-cli -h %REDIS_HOST% -p %REDIS_PORT% --user %REDIS_USER% -a %REDIS_PASSWORD%"
)

set "KEY=%REDIS_KEY_PREFIX%%RECIPIENT_ID%"

echo [1/2] Upserting recipient profile "%RECIPIENT_ID%" into Redis...
call %REDIS_CMD% HSET "%KEY%" ^
  active "%ACTIVE%" ^
  preferred_channel "%PREFERRED_CHANNEL%" ^
  updated_at "%UPDATED_AT%" ^
  email.enabled "%EMAIL_ENABLED%" ^
  email.blacklisted "false" ^
  email.destination "%EMAIL%" ^
  sms.enabled "%SMS_ENABLED%" ^
  sms.blacklisted "false" ^
  sms.destination "%PHONE%" ^
  push.enabled "%PUSH_ENABLED%" ^
  push.blacklisted "false" ^
  push.destination "%PUSH_ID%"
if errorlevel 1 exit /b 1

echo [2/2] Current Redis hash for "%RECIPIENT_ID%":
call %REDIS_CMD% HGETALL "%KEY%"
exit /b 0

:usage
echo Usage:
echo   %~nx0 ^<recipient_id^> [email] [phone] [push_id] [preferred_channel] [active]
echo.
echo Example:
echo   %~nx0 user-212 user-212@example.com
echo   %~nx0 user-212 user-212@example.com 79990000012 mobile-user-212 CHANNEL_EMAIL true
echo.
echo Defaults:
echo   preferred_channel = CHANNEL_EMAIL
echo   active            = true
echo   redis mode        = docker, container redis_container
exit /b 1

:usage_host
echo If Redis is available on host instead, run:
echo   set PROFILE_CONSENT_REDIS_CONTAINER=host
echo   set PROFILE_CONSENT_REDIS_HOST=localhost
echo   set PROFILE_CONSENT_REDIS_PORT=6379
echo   %~nx0 %RECIPIENT_ID% %EMAIL% %PHONE% %PUSH_ID% %PREFERRED_CHANNEL% %ACTIVE%
exit /b 1
