import json, pathlib, zipfile
root=pathlib.Path(__file__).resolve().parent
with zipfile.ZipFile(root/'build/libs/pkmoba-0.27.2.jar') as z:
    assert json.loads(z.read('fabric.mod.json'))['version']=='0.27.2'
    assert 'BlastoiseSpinPoseMixin' in json.loads(z.read('pkmoba.mixins.json'))['client']
    assert 'com/pkmoba/combat/BasicAttack$WaterShot.class' in z.namelist()
    assert 'assets/pkmoba/textures/effect/water_smooth.png' in z.namelist()
c=(root/'src/main/java/com/pkmoba/combat/CharizardCombat.java').read_text(encoding='utf-8')
b=(root/'src/main/java/com/pkmoba/combat/BlastoiseCombat.java').read_text(encoding='utf-8')
w=(root/'src/main/java/com/pkmoba/client/WaterEffects.java').read_text(encoding='utf-8')
assert 'if (skill == Skill.R) return;' in c
assert c.index('if (skill == Skill.R) return;') < c.index('points -= skill.cost;',c.index('private static void cast'))
assert 'if (channel == 3 && skill == Skill.R) return;' in b
assert 'double r = 0.48;' in w and 'Vec3 merged =' not in w
assert 'p.kind() == 6' in w and 'key.kind != 6' in w
a=(root/'src/main/java/com/pkmoba/combat/BasicAttack.java').read_text(encoding='utf-8')
t=(root/'src/main/java/com/pkmoba/client/Targeting.java').read_text(encoding='utf-8')
m=(root/'src/main/java/com/pkmoba/mixin/BlastoiseSpinPoseMixin.java').read_text(encoding='utf-8')
assert 'WATER_RANGE = 4.0' in a and 'BasicAttack.WATER_RANGE' in t
assert 'ClientState.getChannel() != 3' in m and 'part.z -= 2.0F' in m
assert '"tail", "shell_cannon_left", "shell_cannon_right"' in m
print('PASS: version, resources, mixin registration, E/R guards, bubble renderer, range, cannon branches')
print('Client runtime NOT_RUN; source/package checks are not gameplay tests')
