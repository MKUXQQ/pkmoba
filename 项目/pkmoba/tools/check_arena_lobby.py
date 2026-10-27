"""Source/geometry regression checks only; does not build or launch Minecraft."""
from pathlib import Path
import math
root = Path(__file__).resolve().parents[1]
java = root / 'src/main/java/com/pkmoba'
files = {name: (java / name).read_text(encoding='utf-8') for name in (
    'duel/ArenaStore.java', 'duel/RoomManager.java', 'duel/DuelManager.java',
    'item/ModeItems.java', 'command/MoCommand.java', 'client/RoomLobbyScreen.java',
    'client/RoomDetailScreen.java')}
arena = files['duel/ArenaStore.java']
complete = arena.split('public boolean complete()', 1)[1].split('private static boolean finite', 1)[0]
assert 'insideSwitchZone' not in complete, 'room readiness must remain independent of switch zones'
assert 'points.size() != 4' in arena, 'switch zones require exactly four vertices'
assert 'polygon.size() >= 4' in files['item/ModeItems.java']
assert 'boolean red = player.isShiftKeyDown()' in files['item/ModeItems.java']
assert 'return finite(redPlayer) && finite(bluePlayer) && hasBoundary()' in arena
assert 'reconcile(running);' in files['duel/RoomManager.java']
assert 'reconcile(player.server);' in files['duel/RoomManager.java']
assert 'SERVER_STOPPED.register' in files['duel/RoomManager.java']
assert 'if (saved != null && !saved.name.equals("draft")) copyArena(saved, draft);' in files['item/ModeItems.java']
assert 'target.boundary.addAll(source.boundary)' in files['item/ModeItems.java']
assert 'if (pendingRoom != 0 && myRoomId == pendingRoom' in files['client/RoomLobbyScreen.java']
assert 'ClientRooms.send(starting ? "START" : "READY"' in files['client/RoomDetailScreen.java']
assert 'return mySideIsBlue() ? redName : blueName;' in files['client/RoomDetailScreen.java']
# Mirror the horizontal boundary algorithm, checking edges/corners/degenerate polygons.
def turn(a,b,c): return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def intersects(a,b,c,d):
    if max(a[0],b[0])+1e-6 < min(c[0],d[0]) or max(c[0],d[0])+1e-6 < min(a[0],b[0]) or max(a[1],b[1])+1e-6 < min(c[1],d[1]) or max(c[1],d[1])+1e-6 < min(a[1],b[1]): return False
    return turn(a,b,c)*turn(a,b,d)<=1e-6 and turn(c,d,a)*turn(c,d,b)<=1e-6
def valid(poly):
    if len(poly)<4 or not all(math.isfinite(v) for p in poly for v in p): return False
    for i,a in enumerate(poly):
        b=poly[(i+1)%len(poly)]
        if abs(a[0]-b[0])<1e-6 and abs(a[1]-b[1])<1e-6: return False
        for j in range(i+1,len(poly)):
            if j==i+1 or (i==0 and j==len(poly)-1): continue
            if intersects(a,b,poly[j],poly[(j+1)%len(poly)]): return False
    return abs(sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(poly,poly[1:]+poly[:1])))>1e-6
def contains(poly, point):
    if not valid(poly): return True
    inside = False
    for a, b in zip(poly, poly[-1:] + poly[:-1]):
        dx, dz = b[0] - a[0], b[1] - a[1]
        cross = (point[0] - a[0]) * dz - (point[1] - a[1]) * dx
        if abs(cross) <= 1e-6 and min(a[0], b[0])-1e-6 <= point[0] <= max(a[0], b[0])+1e-6 and min(a[1], b[1])-1e-6 <= point[1] <= max(a[1], b[1])+1e-6:
            return True
        if (a[1] > point[1]) != (b[1] > point[1]) and point[0] < dx * (point[1] - a[1]) / dz + a[0]: inside = not inside
    return inside
square = [(0,0), (10,0), (10,10), (0,10)]
assert valid(square)
for p in [(5,5), (0,0), (10,5), (5,0)]: assert contains(square, p), p
for p in [(-1,5), (11,5), (5,11)]: assert not contains(square,p), p
assert not valid([(0,0),(10,10),(0,10),(8,0)])
assert not valid([(0,0),(10,0),(10,0),(0,10)])
assert not valid(square[:3])
assert not valid([(0,0),(1,0),(2,0),(3,0)])
assert not valid([(0,0),(10,0),(float('nan'),10),(0,10)])
# Verify responsive lobby cards never collide with footer on normal Minecraft GUI sizes.
for width, height in [(320,240),(427,240),(640,360),(854,480),(1280,720)]:
    w, h = max(220,min(680,width-24)), max(180,min(430,height-20))
    columns = 2 if w >= 540 else 1
    card = 76 if h < 240 else 94
    rows = max(1,(h-104+10)//(card+10))
    last_bottom = 62+(rows-1)*(card+10)+card
    assert last_bottom <= h-30, (width,height,last_bottom,h)
    assert (w-24-10*(columns-1))//columns >= 196
print('PASS: independent four-point zones, legacy room readiness, UI actions, geometry and responsive layouts')
