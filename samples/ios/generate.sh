#!/bin/sh
# Generates XrayKmpSample.xcodeproj from project.yml after checking that the SwiftPM pin
# matches release/mobile.env (the single source of the xray-rust-mobile version).
set -eu
cd "$(dirname "$0")"
pinned=$(sed -n 's/^XRAY_RUST_MOBILE_VERSION=//p' ../../release/mobile.env)
sample=$(sed -n 's/^ *exactVersion: *//p' project.yml)
if [ "$pinned" != "$sample" ]; then
  echo "project.yml pins xray-rust-mobile $sample, release/mobile.env pins $pinned" >&2
  exit 1
fi
xcodegen generate --spec project.yml
