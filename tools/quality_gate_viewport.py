# SPDX-License-Identifier: GPL-3.0-or-later
"""Exact rule 5, native PEC integer units (0.1mm), no float tolerance."""
import json
from pathlib import Path


def load_hoop(path):
    spec = json.loads(Path(path).read_text())
    assert spec['source'] == 'io.github.linker2k.myembro.engine.edit.HoopSpec.H100'
    width, height = spec['width'], spec['height']
    if not isinstance(width, int) or not isinstance(height, int) or min(width, height) <= 0:
        raise ValueError('Invalid generated HoopSpec dimensions')
    return width, height


def check_viewport(data, events, hoop, margin_units=None):
    if margin_units is None:
        config=Path(__file__).with_name('hoop-spec.json')
        if not config.exists():config=Path(__file__).resolve().parents[1]/'engine/build/hoop-spec.json'
        margin_units=load_limits(config)['hoop_safety_margin_units_0p1mm']
    p = int.from_bytes(data[8:12], 'little')
    if p < 12 or p+528 > len(data):
        raise ValueError('Invalid PEC pointer/header')
    width = int.from_bytes(data[p+520:p+522], 'little')
    height = int.from_bytes(data[p+522:p+524], 'little')
    points = [(x, y) for x, y, command in events if command in (0, 1)]
    if points:
        minx, miny = min(x for x,y in points), min(y for x,y in points)
        maxx, maxy = max(x for x,y in points), max(y for x,y in points)
    else:
        minx = miny = maxx = maxy = 0
    if not all(isinstance(v, int) for v in (minx,miny,maxx,maxy)):
        raise ValueError('PEC coordinates must be native integer units')
    result=assess_bounds(minx,miny,maxx,maxy,width,height,hoop,margin_units)
    result['motion_count']=len(points)
    if not points:
        result['errors'].append('PEC_EMPTY: no recorded STITCH/JUMP destinations')
        result['valid']=False
    return result


def reassess_viewport(viewport,hoop,margin_units):
    result=assess_bounds(viewport['min_x'],viewport['min_y'],viewport['max_x'],viewport['max_y'],
                         viewport['header_width'],viewport['header_height'],hoop,margin_units)
    result['motion_count']=viewport.get('motion_count')
    if result['motion_count']==0:
        result['errors'].append('PEC_EMPTY: no recorded STITCH/JUMP destinations')
        result['valid']=False
    return result


def assess_bounds(minx,miny,maxx,maxy,width,height,hoop,margin_units):
    errors = []
    if minx != 0 or miny != 0:
        errors.append(f'PEC_ORIGIN: min=({minx},{miny}), required=(0,0)')
    if maxx >= hoop[0] or maxy >= hoop[1]:
        errors.append(f'PEC_HOOP: max=({maxx},{maxy}), hoop=({hoop[0]},{hoop[1]}) exclusive size bound')
    if (width,height) != (maxx-minx,maxy-miny):
        errors.append(f'PEC_DIMENSIONS: header=({width},{height}), actual=({maxx-minx},{maxy-miny})')
    return dict(min_x=minx,min_y=miny,max_x=maxx,max_y=maxy,header_width=width,
                header_height=height,errors=errors,valid=not errors)


def load_limits(path):
    return json.loads(Path(path).read_text())['limits']


def integer_safety_findings(density, consecutive, stitch_delta_squared_units, origin, limits, zero_motion_count=0):
    """P5/P7/P8 thresholds come exclusively from the exported Kotlin constants."""
    if min(density,consecutive,stitch_delta_squared_units,zero_motion_count)<0:
        raise ValueError("Safety counters must be nonnegative")
    reasons, warnings = [], []
    imported = origin == 'IMPORTED'
    if zero_motion_count>limits['max_zero_length_motion_commands']:
        (warnings if imported else reasons).append(f'ZERO_LENGTH_MOTION: {zero_motion_count}')
    if density > limits['max_generated_penetrations']:
        (warnings if imported and density <= limits['max_imported_penetrations'] else reasons).append(
            f'CELL_DENSITY: {density}')
    if consecutive > limits['max_consecutive_positions']:
        (warnings if imported else reasons).append(f'SAME_POINT_REPEAT: {consecutive}')
    if stitch_delta_squared_units > limits['max_stitch_units_0p1mm']**2:
        (warnings if imported else reasons).append(f'LONG_STITCH: dx²+dy²={stitch_delta_squared_units}')
    return reasons, warnings


def stationary_motion_count(events):
    x=y=count=0
    for nx,ny,command in events:
        if command in (0,1):
            count += (nx,ny)==(x,y)
            x,y=nx,ny
    return count
