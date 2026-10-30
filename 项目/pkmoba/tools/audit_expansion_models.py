"""Read-only Cobblemon bone/locator audit for the eight approved expansion species."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2] / '宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon/models'
names = ['lucario', 'abomasnow', 'blaziken', 'gengar', 'tyranitar', 'garchomp', 'scizor', 'gardevoir']
for name in names:
    paths = sorted(root.glob(f'*/{name}*.geo.json'))
    if not paths:
        raise SystemExit(f'MISSING MODEL: {name}')
    print('\nSPECIES', name)
    for path in paths:
        data = json.loads(path.read_text(encoding='utf-8'))
        bones = [bone for geometry in data['minecraft:geometry'] for bone in geometry['bones']]
        print('MODEL', path.name)
        print('BONES', ', '.join(bone['name'] for bone in bones))
        print('LOCATORS', json.dumps({bone['name']: bone['locators'] for bone in bones if bone.get('locators')}, ensure_ascii=False))
