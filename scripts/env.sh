#!/usr/bin/env bash
RYADOM_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$RYADOM_ROOT/.tools/jdk"
export ANDROID_HOME="$RYADOM_ROOT/.tools/android-sdk"
export ANDROID_USER_HOME="$RYADOM_ROOT/.tools/android-user"
export GRADLE_USER_HOME="$RYADOM_ROOT/.tools/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
