# Independent derivation of expected-partitions.csv and expected-counts.json for consent-vectors/v6.
# It mirrors the rules of ConsentRouter / ConsentRowCheck (the loader), NOT the splitter's:
#  - a row is a _consents row when its path, as the partition's loader reads it (normalized, and with a last segment equal to the text
#    value dropped), is \_consents\ ; only the exact spellings \_consents\ and µ_consentsµ may grant (the others: NON_CANONICAL_PATH);
#  - every _consents value (valid, invalid name or rejected row) makes its study known in two spellings: exact (the part before the first
#    dot of the trimmed value) and folded (U+FFFD, whitespace/control/format characters removed, part before the first dot, lower case);
#  - a data row whose node is an exact known study is a study row; a node that is NOT one but folds to a folded known study is unroutable
#    (INVALID_STUDY_NODE), never global; only a node that folds to no consents value at all is global (and, if phsNNNNNN, reported);
#  - valid consents whose exact studies fold alike ('PHS999999.c1', 'phs999999.c2') refuse the run (foldClashes);
#  - rows under \_consents\<anything>\ that are no consents rows are counted (consentsSubtreeRowsNotConsents) and stay global.
# At the end it checks the invariant: a routed row whose node folds to a consents study lands only in a partition of that exact study;
# every other routed row is global. v4 changes nothing for v1-v3.
# v5 adds the path-shape rules (proposed contract rules R1 and R2, ADR-0014 amendment), judged after the consents rows and the patient checks:
#  - R1: the text before the first delimiter (backslash or mu) must be empty. `phs999999\X\` and a consents-like `_consents\` (never a grant)
#    are unroutable with MALFORMED_CONCEPT_PATH, never global;
#  - R2: the study node (first node after the leading delimiter) must not be empty: `\\phs999999\X\` is unroutable with INVALID_STUDY_NODE
#    (as is a node that normalization changes or that folds to a consents study). Study ids at any other depth stay legitimate globals
#    (`\_studies_consents\phs123456\Y\`). The invariant also asserts that no routed row breaks R1 or R2. Format characters are removed by
#    code point (tag characters U+E0001-E007F are supplementary).
# v6 (review round 6): a path that mentions _consents (any case, also with U+FFFD inside) and breaks R1 or R2 might be a consents row. It is a
# rejected consents row, not a data row: it grants nothing, its text value's study is registered (exact and folded) and it counts as
# consentRowsRejected under MALFORMED_CONCEPT_PATH (R1) or INVALID_STUDY_NODE (R2), never as unroutable. It is decided on the fields alone,
# before the field count and the patient are looked at. So raising a limit cannot turn the well-formed rows of its study into global rows.
# usage: python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json
import csv, sys, json, re, unicodedata
inp, out_csv, out_json = sys.argv[1:4]
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
CONSENTS = '\\_consents\\'
CANONICAL = (CONSENTS, 'µ_consentsµ')
def parse_line(line):  # one line = one record; None when it cannot be parsed (unterminated quote, bad escaping)
    try: return next(csv.reader([line], strict=True))
    except (csv.Error, StopIteration): return None
lines = [l.rstrip('\r') for l in open(inp, encoding='utf-8-sig', newline='').read().split('\n')]
while lines and lines[-1] == '': lines.pop()
first = parse_line(lines[0])
header = first is not None and len(first) >= 4 and first[0].strip().lower() in ('patient_num', 'subject_id', 'patient_id') \
    and first[1].strip().lower() in ('concept_path', 'conceptpath', 'concept_cd', 'concept')
data = [(l, parse_line(l)) for l in (lines[1:] if header else lines) if l != '']
def study(c): return c.split('.')[0]
def fold(c):  # foldStudy: drop U+FFFD, whitespace, control, separators and format characters; cut at the first dot; lower case
    kept = ''.join(ch for ch in c if ch != '\ufffd' and unicodedata.category(ch) not in ('Cc', 'Cf', 'Zs', 'Zl', 'Zp'))
    return kept.split('.')[0].lower()
def node(p):
    parts = re.split(r'[\\µ]', p)
    return parts[1] if len(parts) > 1 else ''
def pint(s):
    try: return int(s.strip())
    except: return None
def pint_strict(s):
    if not s or len(s) > 11 or not re.fullmatch(r'[+-]?[0-9]+', s): return None
    v = int(s)
    return v if -2**31 <= v < 2**31 else None
def java_trim(s): return s.strip(''.join(chr(i) for i in range(33)))
def java_split(path):
    parts = path.split('\\')
    while parts and parts[-1] == '': parts.pop()
    return parts if parts else ['']
def join_trimmed(path): return '\\'.join(java_trim(x) for x in java_split(path)) + '\\'
def normalize_path(p):
    p = join_trimmed(p.replace('µ', '\\')).replace('\ufffd', '')
    return join_trimmed(p).replace('\ufffd', '')
def timestamp_ok(ts):
    if re.fullmatch(r'-?[0-9]*', ts): return ts == '' or -2**63 <= int(ts) < 2**63
    D, F = r'[0-9]{4}-[0-9]{2}-[0-9]{2}', r'(\.[0-9]{1,9})?'
    return bool(re.fullmatch(D, ts)                                                              # local date
        or re.fullmatch(D + r'T[0-9]{2}:[0-9]{2}(:[0-9]{2}' + F + r')?', ts)                   # ISO local date-time
        or re.fullmatch(D + r' [0-9]{2}:[0-9]{2}:[0-9]{2}' + F, ts)                            # space separated
        or re.fullmatch(D + r'T[0-9]{2}:[0-9]{2}:[0-9]{2}' + F + r'(Z|[+-][0-9]{2}:[0-9]{2}(:[0-9]{2})?)', ts))  # instant: Z or offset
def gate(r):
    if len(r) != 5: return 'BAD_FIELD_COUNT'
    if pint_strict(r[0]) is None: return 'BAD_PATIENT_NUM'
    if r[2] != '':
        try: float(r[2].strip()); return 'NUMERIC_NVAL'
        except ValueError: return 'BAD_NVAL'
    if not timestamp_ok(r[4]): return 'BAD_TIMESTAMP'
    return None
def path_form(r):  # None (not a consents row), 'canonical' or 'non-canonical'
    if len(r) < 2: return None
    if r[1] in CANONICAL: return 'canonical'
    n = normalize_path(r[1])
    if n == CONSENTS: return 'non-canonical'
    text = java_trim(r[3] if len(r) > 3 else '').replace('\ufffd', '')
    if text and n.endswith('\\' + text + '\\'):
        dropped = n[:n.rindex('\\', 0, len(n) - 1) + 1]
        if dropped == CONSENTS: return 'non-canonical'
    return None
def shape_problem(r):  # a consents-like path that breaks R1 or R2: a rejected consents row (v6)
    if len(r) < 2 or '_consents' not in r[1].lower().replace('\ufffd', ''): return None
    if re.split(r'[\\µ]', r[1])[0] != '': return 'MALFORMED_CONCEPT_PATH'
    n = node(r[1])
    if n == '' or n != node(normalize_path(r[1])): return 'INVALID_STUDY_NODE'
    return None
def judge(r):
    return 'NON_CANONICAL_PATH' if path_form(r) == 'non-canonical' else gate(r)
consents = {}
studies = set()
folded = set()
for raw, r in data:
    if r is None: continue
    if path_form(r) is None:
        g = shape_problem(r)
        if g is None: continue
    else: g = judge(r)
    if g is not None:  # rejected: grants nothing, but its study is known (a row with fewer than 5 fields: any field from the third on)
        vs = [r[3]] if len(r) >= 5 else [x for x in r[2:] if not re.fullmatch(r'[-+0-9.:]*', x.strip())]
        for v in vs:
            for c in {java_trim(v), java_trim(v).replace('\ufffd', '')}:
                if c and study(c): studies.add(study(c)); folded.add(fold(study(c)))
    elif r[3].strip():
        consents.setdefault(pint_strict(r[0]), set()).add(r[3].strip())
allc = set().union(*consents.values())
bad = {c for c in allc if not NAME.match(c) or '..' in c}
studies |= {study(c) for c in allc}
folded |= {fold(study(c)) for c in allc}
by_fold = {}
for c in allc:
    if c not in bad: by_fold.setdefault(fold(study(c)), set()).add(study(c))
clashes = sorted(' / '.join(sorted(v)) for v in by_fold.values() if len(v) > 1)
parts = {}
cnt = dict(subtree=0, read=0, study=0, glob=0, noStudy=0, noGlobal=0, invalid=0, unroutable=0, invalidNode=0, malformedPath=0, studyNodesWithout=set(),
           droppedCopies={}, consentRejected={})
def reject(why): cnt['consentRejected'][why] = cnt['consentRejected'].get(why, 0) + 1
routed = []  # (partition, node, global?)
for raw, r in data:
    cnt['read'] += 1
    if r is None:
        if '_consents' in raw.replace('\ufffd', '').lower(): reject('UNPARSABLE_LINE')
        else: cnt['unroutable'] += 1
        continue
    if path_form(r) is not None:
        g = judge(r)
        if g: reject(g); continue
    elif shape_problem(r):
        reject(shape_problem(r)); continue
    elif len(r) >= 2 and node(normalize_path(r[1])) == '_consents':
        cnt['subtree'] += 1
    if len(r) != 5 or pint(r[0]) is None:
        cnt['unroutable'] += 1; continue
    p = pint(r[0])
    if re.split(r'[\\\\µ]', r[1])[0] != '':  # R1
        cnt['unroutable'] += 1; cnt['malformedPath'] += 1; continue
    n = node(r[1]); cs = sorted(consents.get(p, []))
    if n == '' or n != node(normalize_path(r[1])):  # R2
        cnt['unroutable'] += 1; cnt['invalidNode'] += 1; continue
    if n not in studies and fold(n) in folded:
        cnt['unroutable'] += 1; cnt['invalidNode'] += 1; continue
    line = tuple(r)
    if n in studies:
        mine = [c for c in cs if study(c) == n]
        ok = [c for c in mine if c not in bad]
        for c in mine:
            if c in bad: cnt['droppedCopies'][c] = cnt['droppedCopies'].get(c, 0) + 1
        for c in ok: parts.setdefault(c, []).append(line); routed.append((c, n, False))
        if ok: cnt['study'] += 1
        elif mine: cnt['invalid'] += 1
        else: cnt['noStudy'] += 1
    else:
        if re.fullmatch(r'phs[0-9]{6}', n): cnt['studyNodesWithout'].add(n)
        if not cs: cnt['noGlobal'] += 1; continue
        ok = [c for c in cs if c not in bad]
        for c in cs:
            if c in bad: cnt['droppedCopies'][c] = cnt['droppedCopies'].get(c, 0) + 1
        for c in ok: parts.setdefault(c, []).append(line); routed.append((c, n, True))
        if ok: cnt['glob'] += 1
        else: cnt['invalid'] += 1
for part, n, is_global in routed:  # the invariant
    assert n != '', (part, n)
    if is_global: assert fold(n) not in folded, (part, n)
    else: assert study(part) == n, (part, n)
q = lambda f: '"' + f.replace('"', '""') + '"'
with open(out_csv, 'w', encoding='utf-8', newline='') as f:
    for part in sorted(parts):
        for line in sorted(parts[part], key=lambda l: ','.join(q(x) for x in l)):
            f.write(','.join(q(x) for x in (part,) + line) + '\n')
valid_studies = {study(c) for c in allc}
exp = dict(rowsRead=cnt['read'], rowsRouted=cnt['study'] + cnt['glob'], studyRows=cnt['study'], globalRows=cnt['glob'],
    noConsentStudyRows=cnt['noStudy'], noConsentGlobalRows=cnt['noGlobal'], invalidConsentRowsRejected=cnt['invalid'],
    unroutableRows=cnt['unroutable'], invalidStudyNodeRows=cnt['invalidNode'], malformedConceptPathRows=cnt['malformedPath'],
    consentRowsRejected=sum(cnt['consentRejected'].values()), consentRowsRejectedByReason=dict(sorted(cnt['consentRejected'].items())),
    linesWritten=sum(len(v) for v in parts.values()),
    rowsPerPartition={k: len(v) for k, v in sorted(parts.items())},
    invalidConsentNamesDropped=[{'value': c, 'rows': cnt['droppedCopies'].get(c, 0)} for c in sorted(bad)],
    studyNodesWithoutConsents=sorted(cnt['studyNodesWithout']),
    studiesOnlyInRejectedConsentRows=sorted(studies - valid_studies), patientsWithConsents=len(consents),
    consentsSubtreeRowsNotConsents=cnt['subtree'], studyFoldClashes=clashes)
json.dump(exp, open(out_json, 'w'), indent=2); open(out_json, 'a').write('\n')
print(json.dumps(exp, indent=1))
