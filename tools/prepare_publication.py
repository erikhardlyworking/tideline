"""Export reviewed source/data/docs only, excluding the private research workspace and keys."""
from pathlib import Path
import argparse
import shutil

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ['README.md','PRIVACY.md','LICENSE','.gitignore','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','requirements.lock.txt']
DIRECTORIES = ['app','tide-core','tide-cli','gradle','tools','docs','.github']
REPORTS = ['REPORT.md','REPORT.html','INTERPOLATION.md','android_data_manifest.json','kotlin_validation.json','kotlin_location_validation.json']
EXCLUDE = {'build','.gradle','__pycache__','.idea','.git'}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('destination',type=Path)
    dest = parser.parse_args().destination.resolve()
    if dest == ROOT or ROOT in dest.parents and dest.parts[len(ROOT.parts)] not in ('.publish',):
        raise SystemExit('Use a separate destination, or a directory under .publish/.')
    if dest.exists() and any(dest.iterdir()):
        raise SystemExit('Destination must be empty; existing files will not be overwritten.')
    dest.mkdir(parents=True,exist_ok=True)
    def copy(relative):
        target=dest/relative;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(ROOT/relative,target)
    for name in ROOT_FILES: copy(Path(name))
    for directory in DIRECTORIES:
        for path in (ROOT/directory).rglob('*'):
            if path.is_file() and not any(part in EXCLUDE for part in path.relative_to(ROOT/directory).parts):
                copy(path.relative_to(ROOT))
    for report in REPORTS: copy(Path('results')/report)
    copy(Path('data/packs/tideline-global.tpk'))
    files=[p for p in dest.rglob('*') if p.is_file()]
    print(f'Exported {len(files)} files, {sum(p.stat().st_size for p in files):,} bytes to {dest}')

if __name__=='__main__': main()
