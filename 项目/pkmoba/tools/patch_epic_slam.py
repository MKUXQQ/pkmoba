import pathlib

p = pathlib.Path('src/main/java/com/pkmoba/client/HitFeedback.java')
s = p.read_text(encoding='utf-8')

anchor = "    private static int lastPikachuStrikeEntity = Integer.MIN_VALUE;"
s = s.replace(anchor, "    private static long lastEpicSlamTick = Long.MIN_VALUE;\n" + anchor, 1)

old_method = '''    /** One camera shake per locally controlled Pikachu R cast, independent of target hits. */'''
new_method = '''    /**
     * Heavy Gardevoir impact: one shake per slam wherever the player is standing, so Moonblast
     * and Future Sight land with weight even when the user is only watching. The cue is sent once
     * per impact, so this cannot run every frame.
     */
    public static void onEpicSlam(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(at) > 40.0 * 40.0) return;
        long now = mc.level.getGameTime();
        if (now - lastEpicSlamTick < 6) return;
        lastEpicSlamTick = now;
        addShake(EffectPayload.Power.HEAVY, mc.player.position().subtract(at).normalize());
        beginHitstop(EffectPayload.Power.LIGHT);
    }

    /** One camera shake per locally controlled Pikachu R cast, independent of target hits. */'''
assert old_method in s
s = s.replace(old_method, new_method, 1)
p.write_text(s, encoding='utf-8')

e = pathlib.Path('src/main/java/com/pkmoba/client/ExpansionEffects.java')
x = e.read_text(encoding='utf-8')
old = '''            } else if (phase.equals("impact") || phase.equals("detonate")) sound(pos, true);'''
new = '''            } else if (phase.equals("impact") || phase.equals("detonate")) {
                sound(pos, true);
                if (species.id().equals("gardevoir") && (skill == 1 || skill == 3))
                    HitFeedback.onEpicSlam(pos);
            }'''
assert old in x
x = x.replace(old, new, 1)
e.write_text(x, encoding='utf-8')
print('epic slam hook added')
