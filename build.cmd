@echo off
setlocal

rem Каталог установленного EDT, по которому собирается плагин
if "%EDT_HOME%"=="" set "EDT_HOME=C:\Program Files\1C\1CE\components\1c-edt-2025.1.5+34-x86_64"

set "MVN=mvn"
if not "%MAVEN_HOME%"=="" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"

call "%MVN%" -B -f "%~dp0build\pom.xml" clean verify %*
