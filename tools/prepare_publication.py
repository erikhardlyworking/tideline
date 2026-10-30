"""Export buildable widget source and policies, preserving local Play/research materials."""
from pathlib import Path
import argparse
import shutil

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ['README.md','PRIVACY.md','LICENSE','.gitignore','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','requirements.lock.txt','key.properties.example']
DIRECTORIES = ['app','tide-core','tide-cli','gradle']
SOURCE_FILES = [
    'docs/DATA_NOTICES.txt', 'docs/PYTMD_LICENSE.txt',
    'docs/site/index.html', 'docs/site/privacy.html',
    'tools/build_android_data.py', 'tools/coastal.py', 'tools/common.py',
    'tools/download_inputs.py', 'tools/prepare_publication.py',
    'tools/render_privacy.py', 'tools/task.py', 'tools/verify_model_pack.py',
]
EXCLUDE = {'build','.gradle','__pycache__','.idea','.git','local'}

def private_file(path):
    return (path.name in {'key.properties', 'play-service-account.json'} or
            path.name.startswith('.env') or path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx', '.key'})

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
            if path.is_file() and not private_file(path) and not any(part in EXCLUDE for part in path.relative_to(ROOT/directory).parts):
                copy(path.relative_to(ROOT))
    for name in SOURCE_FILES: copy(Path(name))
    copy(Path('data/packs/tideline-global.tpk'))
    files=[p for p in dest.rglob('*') if p.is_file()]
    print(f'Exported {len(files)} files, {sum(p.stat().st_size for p in files):,} bytes to {dest}')

if __name__=='__main__': main()
