#!/bin/sh
# Gradle start up script for POSIX systems
DIRNAME="$(dirname "$0")"
APP_HOME="$(cd "$DIRNAME" && pwd)"
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
