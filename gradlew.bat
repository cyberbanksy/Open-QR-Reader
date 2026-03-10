@ECHO OFF
SET DIRNAME=%~dp0
SET APP_HOME=%DIRNAME%

IF NOT DEFINED GRADLE_USER_HOME (
  SET GRADLE_USER_HOME=%APP_HOME%\.gradle-home
)

IF DEFINED JAVA_HOME (
  SET JAVACMD=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVACMD=java.exe
)

"%JAVACMD%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
