"""Pure-Java contract check, no Minecraft bootstrap or fabricated gameplay acceptance."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
jdk=root.parent/'tools'/'jdk21'/'bin'
source=root/'src/main/java/com/pkmoba/combat/ExpansionRoster.java'
code='''import com.pkmoba.combat.ExpansionRoster; import java.util.*;
public class ExpansionRosterCheck {
 public static void main(String[] args) {
  Set<String> ids=new HashSet<>(); Set<Integer> channels=new HashSet<>(); int count=0;
  for(var s:ExpansionRoster.ALL) {
   require(ids.add(s.id()),"duplicate species"); require(!s.id().equals("dragonite"),"excluded species");
   require(s.maxHp()>=250 && s.maxHp()<=400,"HP bound"); require(s.moves().size()==4,"four slots");
   require(ExpansionRoster.find("cobblemon:"+s.id())==s,"namespace lookup");
   for(int i=0;i<4;i++) {var m=s.move(i); require(channels.add(s.channel(i)),"channel collision");
    require(m.cost()>0 && m.cost()<=10,"cost"); require(m.cooldown()>0,"cooldown");
    require(m.windup()>0 && m.duration()>0,"timing"); require(m.range()>=0 && m.radius()>0,"geometry");
    require(!m.name().isBlank(),"name"); count++;
   }
  }
  require(ids.size()==8 && count==32,"roster size"); require(ExpansionRoster.find(null)==null,"null safe");
  require(ExpansionRoster.find("unknown")==null,"unknown safe");
  System.out.println("PASS: eight species / 32 move contracts / unique channels / HP, costs, timing, geometry, lookup");
 }
 static void require(boolean b,String m) {if(!b) throw new AssertionError(m);}
}'''
with tempfile.TemporaryDirectory(prefix='pkmoba-expansion-') as folder:
 p=Path(folder);(p/'ExpansionRosterCheck.java').write_text(code,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(p),str(source),str(p/'ExpansionRosterCheck.java')],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',str(p),'ExpansionRosterCheck'],check=True)
chart=(root/'src/main/java/com/pkmoba/combat/TypeEffectiveness.java').read_text(encoding='utf8')
for key in ('lucario','abomasnow','blaziken','gengar','tyranitar','garchomp','scizor','gardevoir'):
 if f'case "{key}" -> dual(' not in chart:
  raise SystemExit(f'missing expansion defensive type chart entry: {key}')
print('PASS: eight expansion type chart entries exist')
