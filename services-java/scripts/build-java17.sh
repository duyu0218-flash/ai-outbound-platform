#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo 'Set JAVA_HOME to an installed JDK 17 directory.' >&2
  exit 1
fi
java_version="$("$JAVA_HOME/bin/java" -version 2>&1)"
if [[ "$java_version" != *'version "17.'* ]]; then
  echo 'All backend services require JDK 17.' >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
exec "${MAVEN_BIN:-mvn}" -f "$module_dir/pom.xml" "$@"
