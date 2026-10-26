#!/usr/bin/env python3
"""Phase 4 exit report from an AirTouch log (adb logcat -v time -s 'AirTouch:*' > airtouch.log).

Usage: python3 tools/p4_exit_report.py airtouch.log [--since "10-25 20:30:00"]

Checks the blueprint's stage 10 exit test:
  * IG-01: one swipe up advances exactly one Reel in >= 96 % of tries (48 of 50), and never two.
  * IG-02: swipes down go back exactly one Reel.
  * Tap grid: cursor taps land within 8 dp of where AirTouch aimed them, in every rotation tested.
Accidental Back or Home is a visual check during the tests; it is not in the log.
"""
import re
import sys
from collections import Counter

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) .*?AirTouch\(\s*\d+\): (.*)$")
PAGE = re.compile(r"P4 page (\d+)\u2192(\?|\d+)(?: of (\d+))? for (\w+) in (\S+)(.*)")
TAPGRID = re.compile(r"P4 tapgrid rotation=(\d+) measured=(\d+)/(\d+) pipeline_max_dp=([\d.]+|NaN) aim_median_dp=([\d.]+) aim_max_dp=([\d.]+) within8=(\d+)/(\d+)")
FORWARD = {"SWIPE_UP", "FLICK_UP"}
BACK = {"SWIPE_DOWN", "FLICK_DOWN"}


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    since = argv[argv.index("--since") + 1] if "--since" in argv else ""
    pages, grids, actions = [], [], Counter()
    seen = set()
    with open(argv[1], encoding="utf-8", errors="replace") as log:
        for raw in log:
            m = LINE.match(raw.rstrip("\n"))
            if not m or m.group(1) < since:
                continue
            # The capture loop replays the phone's buffer after a reconnect: count each line once.
            if raw in seen:
                continue
            seen.add(raw)
            text = m.group(2)
            if text.startswith("P4 action Scroll"):
                actions[text.split(" for ")[1].split(" in ")[0]] += 1
            if p := PAGE.match(text):
                pages.append(p)
            if g := TAPGRID.match(text):
                grids.append(g)

    print("Reels and Shorts (one swipe = one page)")
    ok = True
    for name, types, step in (("IG-01 forward", FORWARD, 1), ("IG-02 back", BACK, -1)):
        results = Counter()
        for p in pages:
            if p.group(4) not in types or "instagram" not in p.group(5) and "youtube" not in p.group(5):
                continue
            if p.group(2) == "?":
                results["not measured (next swipe too soon, or no page)"] += 1
                continue
            delta = int(p.group(2)) - int(p.group(1))
            results["single" if delta == step else "none" if delta == 0 else "double or more" if delta * step > 1 else "wrong way"] += 1
        measured = results["single"] + results["none"] + results["double or more"] + results["wrong way"]
        if measured:
            share = results["single"] / measured
            passed = share >= 0.96 and results["double or more"] == 0 and results["wrong way"] == 0
            ok &= passed
            print(f"  {name}: {results['single']}/{measured} single ({share:.0%}), "
                  f"{results['double or more']} double, {results['none']} none, {results['wrong way']} wrong way "
                  f"-> {'PASS' if passed else 'FAIL'}")
        else:
            print(f"  {name}: no measured swipes")
            ok = False
        for reason, n in results.items():
            if reason.startswith("not measured"):
                print(f"    {n} {reason}")

    print("Cursor tap grid (taps within 8 dp of where AirTouch aimed)")
    if not grids:
        print("  no tap-test runs in this log")
        ok = False
    for g in grids:
        pipeline = float(g.group(4))
        passed = pipeline == pipeline and pipeline <= 8.0
        ok &= passed
        print(f"  rotation {g.group(1)}°: pipeline max {g.group(4)} dp ({g.group(2)}/{g.group(3)} taps measured) -> "
              f"{'PASS' if passed else 'FAIL'}; your aim: median {g.group(5)} dp, max {g.group(6)} dp, {g.group(7)}/{g.group(8)} within 8 dp")
    print(f"Scroll actions by gesture: {dict(actions)}")
    print("Phase 4 exit test:", "PASS" if ok else "NOT YET")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
