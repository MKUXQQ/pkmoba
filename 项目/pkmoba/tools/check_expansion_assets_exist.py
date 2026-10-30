"""Existence-only gate for expansion skill assets, pose/animation files, and model skeletons."""
from pathlib import Path
import json

root=Path(__file__).resolve().parents[1]
assets=root/'src/main/resources/assets/pkmoba'
models=root.parent/'宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon/models'
species=('lucario','abomasnow','blaziken','gengar','tyranitar','garchomp','scizor','gardevoir')
slots='qwer'
missing=[]
for name in species:
    for slot in slots:
        path=assets/'textures/gui/skills'/f'{name}_{slot}.png'
        if not path.is_file(): missing.append(f'skill icon: {path}')
    animation=assets/'bedrock/pokemon/animations'/f'pkmoba_{name}.animation.json'
    if not animation.is_file(): missing.append(f'animation: {animation}')
    candidates=sorted(models.glob(f'*/{name}*.geo.json'))
    if not candidates:
        missing.append(f'model skeleton: {name}')
    else:
        found=False
        for path in candidates:
            try:
                data=json.loads(path.read_text(encoding='utf-8'))
                if any(geometry.get('bones') for geometry in data.get('minecraft:geometry',[])):
                    found=True
                    break
            except (OSError,ValueError):
                continue
        if not found: missing.append(f'model skeleton has no bones: {name}')
if missing:
    print('\n'.join('MISSING '+item for item in missing))
    raise SystemExit(1)
print('PASS: 32 skill-icon paths, 8 animation files, and 8 model skeletons exist')
