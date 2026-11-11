@REM ----------------------------------------------------------------------------
@REM Maven Wrapper Batch Script for Windows (Located in .mvn/)
@REM ----------------------------------------------------------------------------

@IF "%DEBUG%" == "" @ECHO OFF
@SETLOCAL EnableDelayedExpansion EnableExtensions

SET "DIR=%~dp0"
SET "WRAPPER_JAR=%DIR%wrapper\maven-wrapper.jar"
SET "WRAPPER_PROP=%DIR%wrapper\maven-wrapper.properties"

IF NOT EXIST "%DIR%wrapper" (
    mkdir "%DIR%wrapper"
)

IF NOT EXIST "%WRAPPER_JAR%" (
    echo Downloading Maven Wrapper JAR...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar', '%WRAPPER_JAR%')"
)

IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
    SET "JAVA_EXE=java"
) ELSE (
    SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_EXE%" -jar "%WRAPPER_JAR%" %*
exit /b %ERRORLEVEL%

