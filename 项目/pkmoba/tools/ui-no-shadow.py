from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'src/main/java/com/pkmoba/client'
for name in ['BattleMenuScreen','ChallengePopupScreen','PlayerSelectScreen','MatchResultOverlay','RoomLobbyScreen','StarterScreen','RoomDetailScreen','RoomSettingsScreen']:
    p=root/(name+'.java')
    s=p.read_text(encoding='utf-8')
    import re
    s=re.sub(r'(graphics|g)\.drawCenteredString\(', r'PixelUi.centered(\1, ', s)
    p.write_text(s, encoding='utf-8')
