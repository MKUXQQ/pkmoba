from pathlib import Path
p=Path(__file__).resolve().parent
(p/'install-release-0.51.0.ps1').write_text((p/'install-release-0.50.0.ps1').read_text(encoding='utf-8').replace('0.50.0','0.51.0'),encoding='utf-8')
