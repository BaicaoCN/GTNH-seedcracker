#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p out
javac --release 17 -encoding UTF-8 -cp jocl.jar -d out ui/*.java
jar cfe gtnh-seed-solver.jar SolverGui -C out .
