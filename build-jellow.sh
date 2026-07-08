#!/bin/bash
# Build Jellow APKs
cd "$(dirname "$0")"
export JAVA_HOME="$PWD/jdk-21.0.10+7"

# Phone debug
./gradlew assembleJellowDebug

# TV debug (uncomment if needed)
# ./gradlew :app:tv:assembleJellowDebug

# Phone release (requires signing)
# ./gradlew assembleJellowRelease

echo ""
echo "=== Jellow APKs ==="
find . -path "*/jellow/*" -name "*.apk" -not -path "*/build/*" | while read f; do
  ls -lh "$f"
done
