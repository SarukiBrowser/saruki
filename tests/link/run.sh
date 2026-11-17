#!/bin/bash
# Saruki Remote link tests on a computer (Java 17+). Run from the source folder: bash tests/link/run.sh
set -u
cd "$(dirname "$0")/../.."
B=build/linktest; rm -rf $B; mkdir -p $B
javac --release 17 -encoding UTF-8 -d $B shared/java/io/github/sarukibrowser/link/*.java \
  app/src/main/java/io/github/sarukibrowser/tv/WsServer.java remote/src/main/java/io/github/sarukibrowser/remote/WsClient.java \
  tests/link/TypeTest.java tests/link/CamTest.java tests/link/LinkTest.java || exit 1
fail=0
for t in TypeTest CamTest io.github.sarukibrowser.remote.LinkTest; do
  java -cp $B $t > $B/$t.log 2>&1 && echo "PASS $t" || { echo "FAIL $t"; grep FAIL $B/$t.log; fail=1; }
done
[ $fail = 0 ] && echo "ALL PASS" || echo "SOME TESTS FAILED"
exit $fail
