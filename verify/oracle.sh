#!/bin/bash
# 重新生成期望值（用站点自己的 crypto-js）
cd "$(dirname "$0")" && node oracle.js
