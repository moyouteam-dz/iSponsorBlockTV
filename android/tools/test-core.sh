#!/usr/bin/env sh
# Dependency-free checks of core skip algorithm using local Kotlin compiler.
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp "$BASE/app/src/main/java/com/moyou/isponsorblocktv/domain/SponsorSegments.kt" "$TMP/SponsorSegments.kt"
cp "$BASE/app/src/main/java/com/moyou/isponsorblocktv/domain/LoungeFrameParser.kt" "$TMP/LoungeFrameParser.kt"
cat > "$TMP/Smoke.kt" <<'KOTLIN'
import com.moyou.isponsorblocktv.domain.*
fun main() {
    val segments=SponsorSegments.merge(listOf(RawSegment(2.0, 5.0, "x"), RawSegment(4.5, 9.0, "y")))
    check(segments.size==1)
    check(segments.single().uuids==listOf("x", "y"))
    check(SponsorSegments.next(segments, 0.0)!!.waitSeconds==2.0)
    check(SponsorSegments.next(segments, 9.0)==null)
    val body="[[0,[\"c\",\"sid\"]]]"
    val input=java.io.ByteArrayInputStream((body.toByteArray().size.toString()+"\n"+body).toByteArray())
    check(LoungeFrameParser.readFrame(input)==body)
    check(LoungeFrameParser.readFrame(input)==null)
    println("Core segment and Lounge framing tests: PASS")
}
KOTLIN
kotlinc "$TMP/SponsorSegments.kt" "$TMP/LoungeFrameParser.kt" "$TMP/Smoke.kt" -include-runtime -d "$TMP/test.jar"
java -jar "$TMP/test.jar"
