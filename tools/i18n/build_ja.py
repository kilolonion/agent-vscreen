#!/usr/bin/env python3
"""Build app/src/main/assets/i18n/ja.json from tools/i18n/ja.tsv (2.0.1-beta2).

ja.tsv: one line per text, `English<TAB>Japanese`, newlines written as ⏎. The English side is the text as the app
renders it, with {0}, {1} where the Kotlin string inserts a value (in order); the Japanese side uses the same holes in
any order. Run `python3 tools/i18n/build_ja.py --missing` to list user-facing tr() texts (ui/, host/, api/Policy.kt)
that have no Japanese yet; those stay English in the app.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TSV = os.path.join(ROOT, 'tools', 'i18n', 'ja.tsv')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'i18n', 'ja.json')
HOLE = re.compile(r'\{(\d+)\}')


def load():
    pairs = {}
    with open(TSV, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            en, ja = line.split('\t', 1)
            en, ja = en.replace('⏎', '\n'), ja.replace('⏎', '\n')
            if sorted(HOLE.findall(en)) != sorted(HOLE.findall(ja)):
                sys.exit(f'ja.tsv:{n}: the holes differ: {en!r} / {ja!r}')
            pairs[en] = ja
    return pairs


def to_template(src):
    """A Kotlin string literal's source text -> the rendered text with {0}, {1} for its templates."""
    out, i, n = [], 0, 0
    while i < len(src):
        c = src[i]
        if c == '\\' and i + 1 < len(src):
            e = src[i + 1]
            if e == 'u':
                out.append(chr(int(src[i + 2:i + 6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b'}.get(e, e)); i += 2; continue
        if c == '$' and src[i + 1:i + 2] == '{':
            d, k = 0, i + 1
            while True:
                if src[k] == '{': d += 1
                elif src[k] == '}':
                    d -= 1
                    if d == 0: break
                k += 1
            out.append('{%d}' % n); n += 1; i = k + 1; continue
        if c == '$':
            m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[i + 1:])
            if m:
                out.append('{%d}' % n); n += 1; i += 1 + m.end(); continue
        out.append(c); i += 1
    return ''.join(out)


def missing(pairs):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import subprocess
    base = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'kilon', 'agentvscreen')
    raw = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'extract.py'),
                          os.path.join(base, 'ui'), os.path.join(base, 'host')], capture_output=True, text=True, check=True).stdout
    texts = sorted({to_template(k) for k in json.loads(raw)})
    left = [t for t in texts if t not in pairs and re.search('[A-Za-z]{2}', t)]
    for t in left:
        print(t.replace('\n', '⏎'))
    print(f'{len(left)} of {len(texts)} texts have no Japanese', file=sys.stderr)


def texts(*dirs):
    base = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'kilon', 'agentvscreen')
    import subprocess
    raw = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'extract.py')] +
                         [os.path.join(base, d) for d in dirs], capture_output=True, text=True, check=True).stdout
    return {to_template(k) for k in json.loads(raw)}


def check(pairs):
    """CI gate: every user-facing text has Japanese (or is listed in ja-skip.txt as agent-only), and no entry is
    left keyed on an English text the code no longer has (it would silently stop matching after a copy edit)."""
    skip_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ja-skip.txt')
    skip = {l.rstrip('\n').replace('⏎', '\n') for l in open(skip_file, encoding='utf-8') if l.strip() and not l.startswith('#')}
    shown = texts('ui', 'host')
    left = sorted(t for t in shown if t not in pairs and t not in skip and re.search('[A-Za-z]{2}', t))
    live = texts('.')
    stale = sorted(k for k in pairs if k not in live)
    dead_skip = sorted(t for t in skip if t not in shown)
    for t in left: print('no Japanese: ' + t.replace('\n', '⏎'))
    for t in stale: print('stale ja.tsv entry (its English text is gone): ' + t.replace('\n', '⏎'))
    for t in dead_skip: print('stale ja-skip.txt line: ' + t.replace('\n', '⏎'))
    if left or stale or dead_skip:
        sys.exit('ja.tsv is out of step with the code: after changing an English text, move its Japanese to the new text '
                 '(or add an agent-only text to ja-skip.txt), then run python3 tools/i18n/build_ja.py')
    # the generated table must match the TSV
    want = build(pairs)
    have = open(OUT, encoding='utf-8').read()
    if want != have:
        sys.exit('app/src/main/assets/i18n/ja.json is not rebuilt: run python3 tools/i18n/build_ja.py')
    print(f'OK: {len(shown)} user-facing texts, {len(skip)} agent-only, Japanese complete')


def build(pairs):
    exact = {en: ja for en, ja in pairs.items() if not HOLE.search(en)}
    tpl = [[en, ja] for en, ja in sorted(pairs.items()) if HOLE.search(en)]
    return json.dumps({'exact': dict(sorted(exact.items())), 'tpl': tpl}, ensure_ascii=False, indent=0) + '\n'


def main():
    pairs = load()
    if '--missing' in sys.argv:
        missing(pairs); return
    if '--check' in sys.argv:
        check(pairs); return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(build(pairs))
    n = sum(1 for en in pairs if HOLE.search(en))
    print(f'{len(pairs) - n} exact, {n} templates -> {os.path.relpath(OUT, ROOT)}')


if __name__ == '__main__':
    main()
