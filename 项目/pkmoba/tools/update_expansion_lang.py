from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[1]/'src/main'
text=(root/'java/com/pkmoba/combat/ExpansionRoster.java').read_text(encoding='utf8')
english={
'lucario':['Aura Sphere','Force Palm','Extreme Speed','Close Combat'],
'abomasnow':['Ice Shard','Icy Wind','Mist','Blizzard'],
'blaziken':['Blaze Kick','Double Kick','Flame Charge','Flare Blitz'],
'gengar':['Shadow Ball','Sludge Bomb','Hypnosis','Dream Eater'],
' tyranitar':['Rock Slide','Crunch','Sandstorm','Stone Edge'],
'garchomp':['Dragon Claw','Bulldoze','Dig','Dragon Rush'],
'scizor':['Bullet Punch','Fury Cutter','U-turn','Swords Dance'],
'gardevoir':['Psychic','Moonblast','Teleport','Future Sight']}
english['tyranitar']=english.pop(' tyranitar')
for lang in ['zh_cn','en_us']:
 path=root/f'resources/assets/pkmoba/lang/{lang}.json';data=json.loads(path.read_text(encoding='utf8'))
 for block in re.split(r'new Species\(',text)[1:]:
  species=re.match(r'"([a-z]+)"',block).group(1)
  names=re.findall(r'm\("([^"]+)"',block)[:4]
  for slot,name in zip('qwer',names if lang=='zh_cn' else english[species]):data[f'skill.pkmoba.{species}.{slot}']=name
 path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 print(lang,'32 move labels added')
