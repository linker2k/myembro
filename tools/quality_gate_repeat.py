# SPDX-License-Identifier: GPL-3.0-or-later
"""SE400 incident policy v6: count explicit STITCH/JUMP destinations, including zero moves.
Do not count an implicit origin or control commands. A different destination resets the run.
Needle-only PE-Design corpus p99=2; coordinator chose this stricter motion-inclusive limit.
"""
def maximum_consecutive_positions(events):
    last = None
    run = peak = 0
    for x, y, command in events:
        if command not in (0, 1):  # STITCH, JUMP
            continue
        position = (x, y)
        run = run + 1 if position == last else 1
        last = position
        peak = max(peak, run)
    return peak
