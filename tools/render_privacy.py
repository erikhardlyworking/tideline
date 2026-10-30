"""Render the bundled canonical policy as a standalone public HTML page (stdlib only)."""
from pathlib import Path
from html import escape

ROOT = Path(__file__).resolve().parents[1]

def main():
    blocks = (ROOT / 'PRIVACY.md').read_text().strip().split('\n\n')
    body = []
    for block in blocks:
        if block.startswith('# '):
            body.append('<h1>' + escape(block[2:]) + '</h1>')
        elif block.startswith('## '):
            body.append('<h2>' + escape(block[3:]) + '</h2>')
        else:
            body.append('<p>' + escape(block.replace('\n', ' ')) + '</p>')
    html = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TideLine Privacy Policy</title><style>body{font:18px/1.6 system-ui,sans-serif;max-width:46rem;margin:3rem auto;padding:0 1.25rem;color:#20333d;background:#f6f8fa}h1,h2{line-height:1.2}h2{margin-top:2rem}p{overflow-wrap:anywhere}</style><main>' + '\n'.join(body) + '</main></html>\n'
    output = ROOT / 'docs/site'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'privacy.html').write_text(html)
    (output / 'index.html').write_text(html)
    print('Generated docs/site/privacy.html and index.html from PRIVACY.md')

if __name__ == '__main__':
    main()
