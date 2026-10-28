from pathlib import Path
import hashlib, json, shutil, zipfile
from datetime import datetime
root=Path(__file__).resolve().parents[1]
source=root/'build/libs/pkmoba-0.52.0.jar'
mods=Path('E:/我的世界/.minecraft/versions/兴年宝可梦/mods').resolve()
assert source.is_file() and mods.is_dir()
with zipfile.ZipFile(source) as z:
    assert 'com/pkmoba/client/PokemonOverheadBar.class' in z.namelist()
    assert 'com/pkmoba/client/ExpansionEffects.class' in z.namelist()
    assert 'com/pkmoba/client/PikachuEffects.class' in z.namelist()
    assert 'com/pkmoba/combat/ExpansionCombat.class' in z.namelist()
    for species in ['lucario','abomasnow','blaziken','gengar','tyranitar','garchomp','scizor','gardevoir']:
        for key in 'qwer':
            assert f'assets/pkmoba/textures/gui/skills/{species}_{key}.png' in z.namelist()
            assert f'assets/pkmoba/textures/effect/expansion/{species}_{key}_surface.png' in z.namelist()
backup=root.parents[2]/'backup'/('before-cinematic-effects-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(parents=True)
for old in mods.glob('pkmoba*.jar'):
    assert old.resolve().parent==mods
    shutil.copy2(old,backup/old.name)
    old.unlink()
target=mods/source.name
shutil.copy2(source,target)
sha=lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(source)==sha(target) and len(list(mods.glob('pkmoba*.jar')))==1
result=dict(installed=str(target),sha256=sha(target),size=target.stat().st_size,backup=str(backup))
(root/'build/local-deployment.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
