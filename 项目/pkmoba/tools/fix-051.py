from pathlib import Path
base=Path(__file__).resolve().parents[1]
root=base/'src/main/java/com/pkmoba'
def patch(rel,old,new):
 p=root/rel;s=p.read_text(encoding='utf-8');assert old in s,(rel,old);p.write_text(s.replace(old,new),encoding='utf-8')
patch('client/RoomDetailScreen.java','选择宝可梦 / 获取','选择宝可梦')
# Keep the selector in the room panel's position and prevent Screen.render from blurring our already drawn UI.
patch('client/RoomTeamScreen.java','Math.min(580, width - 16)','Math.min(520, width - 16)')
patch('client/RoomTeamScreen.java','Math.min(380, height - 12)','Math.min(292, height - 12)')
for name in ['RoomTeamScreen','RoomPasswordScreen','RoomDetailScreen','RoomSettingsScreen','RoomLobbyScreen']:
 p=root/f'client/{name}.java';s=p.read_text(encoding='utf-8');pos=s.index('    @Override')
 s=s[:pos]+'    /** Do not run the vanilla full-frame blur over custom menu artwork. */\n    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {}\n'+s[pos:];p.write_text(s,encoding='utf-8')
patch('duel/RoomManager.java','COUNTDOWN_TICKS = 20 * 10','COUNTDOWN_TICKS = 20 * 5')
patch('duel/RoomManager.java','        public UUID bot;','        public UUID bot;\n        public List<String> botRoster = List.of();')
patch('duel/RoomManager.java','        room.bot = UUID.randomUUID();','        room.bot = UUID.randomUUID();\n        room.botRoster = BotDuelService.randomTeam();')
patch('duel/RoomManager.java','return BotDuelService.teamForDisplay();','return room.botRoster;')
patch('duel/RoomManager.java','return BotDuelService.teamForDisplay().stream().limit(room.settings.capacity).toList();','return room.botRoster.stream().limit(room.settings.capacity).toList();')
patch('duel/RoomManager.java','        player.sendSystemMessage(Component.literal("已确认队伍并准备。双方准备后由房主开始，入场倒计时 10 秒；倒下后有 20 秒按 C 换宠。"));\n        ServerPlayer host = playerOf(player.server, room.host);\n        if (host != null && allReady(room)) host.sendSystemMessage(Component.literal("双方已准备！按 H 进入房间，点击开始对战。"));','        player.sendSystemMessage(Component.literal("已准备，双方就绪后自动倒计时 5 秒。"));\n        ServerPlayer host = playerOf(player.server, room.host);\n        if (host != null && allReady(room)) { start(host); return; }')
patch('duel/BotDuelService.java','ENTRY_COUNTDOWN_TICKS = 10 * 20','ENTRY_COUNTDOWN_TICKS = 5 * 20')
patch('duel/BotDuelService.java','        final int teamSize;','        final int teamSize;\n        final List<String> team;')
patch('duel/BotDuelService.java','            this.teamSize = room == null ? TEAM.size() : Math.min(TEAM.size(), room.settings.capacity);','            this.team = (room == null ? randomTeam() : room.botRoster).stream()\n                    .map(id -> id.substring(id.indexOf(\':\') + 1)).toList();\n            this.teamSize = room == null ? team.size() : Math.min(team.size(), room.settings.capacity);')
patch('duel/BotDuelService.java','    public static List<String> teamForDisplay() {','    public static List<String> randomTeam() {\n        List<String> team = new ArrayList<>(teamForDisplay());\n        java.util.Collections.shuffle(team);\n        return List.copyOf(team);\n    }\n\n    public static List<String> teamForDisplay() {')
patch('duel/BotDuelService.java','TEAM.get(match.botIndex)','match.team.get(match.botIndex)')
patch('duel/BotDuelService.java','TEAM.get(Math.min(match.botIndex, TEAM.size() - 1))','match.team.get(Math.min(match.botIndex, match.team.size() - 1))')
patch('combat/BlastoiseCombat.java','if (CastLock.active(entity)) return;','if (channel != CHANNEL_CANNON && CastLock.active(entity)) return;')
patch('combat/BlastoiseCombat.java','case Q -> CastLock.begin(entity, CastLock.Shape.LINE, dir, aimPoint, -1);','case Q -> CastLock.release(entity); // Q alone continuously steers toward the mouse.')
patch('combat/BlastoiseCombat.java','            Vec3 steer = CastLock.dirOr(entity, aimPoint == null\n                    ? CharizardCombat.modelForward(entity)\n                    : CharizardCombat.aimDirection(entity, aimPoint));\n            Vec3 lockedPoint = CastLock.pointOr(entity, aimPoint);','            Vec3 steer = aimPoint == null ? CharizardCombat.modelForward(entity)\n                    : CharizardCombat.aimDirection(entity, aimPoint);\n            FacingService.snap(entity, FacingService.Priority.CHANNEL, steer);\n            Vec3 lockedPoint = aimPoint;')
p=base/'build.gradle';s=p.read_text(encoding='utf-8');assert "version = '0.50.0'" in s;p.write_text(s.replace("version = '0.50.0'","version = '0.51.0'"),encoding='utf-8')
