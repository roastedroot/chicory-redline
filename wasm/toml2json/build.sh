#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/toml2json.wasm ../toml2json.wasm
echo "Built wasm/toml2json.wasm"
