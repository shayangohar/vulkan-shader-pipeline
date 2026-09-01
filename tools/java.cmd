@echo off
set "CHIMERA_PRISM_JAVA_HOME=%CHIMERA_PRISM_JAVA_HOME%"
if not defined CHIMERA_PRISM_JAVA_HOME set "CHIMERA_PRISM_JAVA_HOME=C:\Users\shaya\AppData\Roaming\PrismLauncher\java\java-runtime-delta"
if not exist "%CHIMERA_PRISM_JAVA_HOME%\bin\java.exe" (
    echo Prism Java 21 was not found at "%CHIMERA_PRISM_JAVA_HOME%" 1>&2
    exit /b 1
)
"%CHIMERA_PRISM_JAVA_HOME%\bin\java.exe" %*
exit /b %ERRORLEVEL%
