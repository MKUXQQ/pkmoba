from pathlib import Path
import hashlib, json, re, shutil, zipfile
from datetime import datetime
root=Path(__file__).resolve().parents[1]
# Always deploy the jar the current build.gradle version produces (not a frozen filename).
version=re.search(r"version\s*=\s*'([^']+)'", (root/'build.gradle').read_text(encoding='utf-8')).group(1)
source=root/f'build/libs/pkmoba-{version}.jar'
mods=Path('E:/我的世界/.minecraft/versions/兴年宝可梦/mods').resolve()
assert source.is_file() and mods.is_dir()
with zipfile.ZipFile(source) as z:
    assert 'com/pkmoba/client/PokemonOverheadBar.class' in z.namelist()
    assert 'com/pkmoba/client/ExpansionEffects.class' in z.namelist()
    assert 'com/pkmoba/client/PikachuEffects.class' in z.namelist()
    assert 'com/pkmoba/combat/ExpansionCombat.class' in z.namelist()
    for name in ['pikachu_thunder_shock','pikachu_thunderbolt','pikachu_quick_attack','pikachu_afterimage',
                 'pikachu_storm_cloud','pikachu_thunder_bolt','pikachu_ground_ring','pikachu_spark']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    for key in ['thundershock','thunderbolt','quick_attack','thunder']:
        assert f'assets/pkmoba/textures/gui/skills/pikachu_{key}.png' in z.namelist(), key
    for species in ['lucario','alolan_ninetales','blaziken','gengar','tyranitar','gardevoir',
                'greninja','meowscarada','ceruledge','hisuian_zoroark','krookodile','armarouge']:
        for key in 'qwer':
            assert f'assets/pkmoba/textures/gui/skills/{species}_{key}.png' in z.namelist()
    # Lucario uses its own pixel sheet kit (0.87: blades, fist, bolt, speed band).
    for name in ['tt5_cube','tt5_grid','tt5_dust','tt5_maw','tt5_octa','tt5_crack','tt5_pillar','tt5_spark','tt5_wall']:
        assert any(n.endswith('textures/effect/' + name + '.png') for n in z.namelist()), name
    for name in ['lu_sword','lu_comet','lu_bolt','lu_punch','lu_star','lu_streak','lu_glow']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    # Alolan Ninetales replaced Abomasnow in 0.87.
    for name in ['nt_fdry','nt_veilc','nt_blizzard','nt_tera','nt_flake','nt_glint','nt_puff']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    assert not any('abomasnow' in n for n in z.namelist()), 'abomasnow assets still packaged'
    # 0.124 ninja kit 2.0 (spiral shuriken / night slash / dark pulse / hydro pump) + trickster kit.
    for name in ['kd_bite','kd_bite_heavy','kd_dust','kd_burst','kd_quake','kd_shock','kd_rage','kd_claw',
                 'gj_star','gj_slash','gj_orb','gj_ring','gj_bubble','gj_splash','gj_drop',
                 'gj_plot_seal','gj_plot_burst','gj_plot_mote',
                 'mw2_leaf','mw2_cut','mw2_flower','mw2_bloom','mw2_petal','mw2_storm',
                 'mw2_ring','mw2_glow']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    # 0.135 armarouge fire/psychic kit: bolts, cannon shell, blast, rune and the pulse rings.
    for name in ['ar_bolt','ar_bolt_heavy','ar_psy','ar_flame','ar_cannon','ar_boom','ar_rune','ar_pulse',
                 'ar_muzzle','ar_scorch','ar_glyph','ar_spark','ar_smoke']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    for slot in 'qwer':
        assert f'assets/pkmoba/textures/gui/skills/armarouge_{slot}.png' in z.namelist(), slot
    # 0.139 incineroar was pulled, so none of its sheets may ship again.
    assert not any('/effect/infx_' in n for n in z.namelist()), 'stale infx_ sheets'
    assert not any('incineroar_' in n for n in z.namelist()), 'stale incineroar icons'
    assert 'assets/pkmoba/textures/gui/pokemon/incineroar.png' not in z.namelist()
    assert 'assets/pkmoba/bedrock/pokemon/animations/pkmoba_incineroar.animation.json' not in z.namelist()
    assert 'assets/pkmoba/textures/gui/pokemon/armarouge.png' in z.namelist()
    assert 'assets/pkmoba/bedrock/pokemon/animations/pkmoba_armarouge.animation.json' in z.namelist()
    assert not any('/effect/gn_' in n for n in z.namelist()), 'stale gn_ sheets'
    assert not any('/effect/mw_' in n for n in z.namelist()), 'stale mw_ sheets'
    assert not any('/effect/gj_jet' in n for n in z.namelist()), 'hydro pump sheet still packaged'
    # 0.132 ceruledge twin-blade kit: blade, shadow, twin slashes, dance and the drain ribbon.
    for name in ['ce_blade','ce_shadow','ce_flare_l','ce_flare_r','ce_cross','ce_dance','ce_drain','ce_ember']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    for slot in 'qwer':
        assert f'assets/pkmoba/textures/gui/skills/ceruledge_{slot}.png' in z.namelist(), slot
    assert 'assets/pkmoba/bedrock/pokemon/animations/pkmoba_ceruledge.animation.json' in z.namelist()
    assert not any('/effect/mw2_leaf2' in n for n in z.namelist())
    # 0.133 hisuian zoroark: ghost-claw kit, malice sigil and the illusion burst.
    for name in ['zo_claw_l','zo_claw_r','zo_claw_cross','zo_ring','zo_wisp','zo_orb',
                 'zo_burst','zo_sigil','zo_after']:
        assert f'assets/pkmoba/textures/effect/{name}.png' in z.namelist(), name
    for slot in 'qwer':
        assert f'assets/pkmoba/textures/gui/skills/hisuian_zoroark_{slot}.png' in z.namelist(), slot
    assert 'assets/pkmoba/textures/gui/pokemon/hisuian_zoroark.png' in z.namelist()
    assert 'assets/pkmoba/bedrock/pokemon/animations/pkmoba_hisuian_zoroark.animation.json' in z.namelist()
    for slot in 'qwer':
        assert f'assets/pkmoba/textures/gui/skills/greninja_{slot}.png' in z.namelist(), slot
    assert not any(n.startswith('assets/pkmoba/textures/effect/mv_') for n in z.namelist()), 'stale mv_ sheets'
    assert not any('steelix' in n for n in z.namelist()), 'steelix assets still packaged'
    # Garchomp and Scizor were removed from the roster entirely.
    assert not any(n.startswith('assets/pkmoba/textures/effect/expansion/garchomp') for n in z.namelist())
    assert not any(n.startswith('assets/pkmoba/textures/gui/skills/scizor') for n in z.namelist())
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
