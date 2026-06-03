#!/bin/sh
# Run this once after cloning if gradle-wrapper.jar is missing.
# Requires Gradle installed globally (brew install gradle / sdk install gradle).
echo "Generating Gradle wrapper..."
gradle wrapper --gradle-version=8.7 --distribution-type=bin
echo "Done. You can now use ./gradlew"
