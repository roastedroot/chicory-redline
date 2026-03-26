#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
java --enable-native-access=ALL-UNNAMED -jar jmh/target/benchmarks.jar BenchmarkToml2Json "$@"
