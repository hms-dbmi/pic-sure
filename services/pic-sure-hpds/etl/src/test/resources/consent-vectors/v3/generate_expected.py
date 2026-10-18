# Independent derivation of expected-partitions.csv and expected-counts.json for consent-vectors/v3.
# It mirrors the rules of ConsentRouter / ConsentRowCheck (the loader), NOT the splitter's:
#  - a row is a _consents row when its path, as the partition's loader reads it (normalized, and with a last segment equal to the text
#    value dropped), is \_consents\ ; only the exact spellings \_consents\ and µ_consentsµ may grant (the others: NON_CANONICAL_PATH);
#  - every rejected _consents row still makes the study of its trimmed text value known, so a data row of that study of a patient without
#    a valid consent of it is dropped (NO_CONSENT), never global;
#  - a node that no _consents row names at all is global and only reported (as in the splitter).
# At the end it checks the invariant: a routed row with a study node lands only in a partition of that study; a global row's node is not a
# known study.
# usage: python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json
import csv, sys, json, re
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
    return bool(re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?)?Z?', ts))
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
def judge(r):
    return 'NON_CANONICAL_PATH' if path_form(r) == 'non-canonical' else gate(r)
consents = {}
studies = set()
for raw, r in data:
    if r is None or path_form(r) is None: continue
    g = judge(r)
    if g is not None:  # rejected: grants nothing, but its study is known
        v = r[3] if len(r) > 3 else (r[2] if len(r) == 3 else '')
        for c in {java_trim(v), java_trim(v).replace('\ufffd', '')}:
            if c: studies.add(study(c))
    elif r[3].strip():
        consents.setdefault(pint_strict(r[0]), set()).add(r[3].strip())
allc = set().union(*consents.values())
bad = {c for c in allc if not NAME.match(c) or '..' in c}
studies |= {study(c) for c in allc}
parts = {}
cnt = dict(read=0, study=0, glob=0, noStudy=0, noGlobal=0, invalid=0, unroutable=0, invalidNode=0, studyNodesWithout=set(),
           droppedCopies={}, consentRejected={})
def reject(why): cnt['consentRejected'][why] = cnt['consentRejected'].get(why, 0) + 1
routed = []  # (partition, node, global?)
for raw, r in data:
    cnt['read'] += 1
    if r is None:
        if '_consents' in raw: reject('UNPARSABLE_LINE')
        else: cnt['unroutable'] += 1
        continue
    if path_form(r) is not None:
        g = judge(r)
        if g: reject(g); continue
    if len(r) != 5 or pint(r[0]) is None:
        cnt['unroutable'] += 1; continue
    p = pint(r[0]); n = node(r[1]); cs = sorted(consents.get(p, []))
    if n != node(normalize_path(r[1])):
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
    if is_global: assert n not in studies, (part, n)
    else: assert study(part) == n, (part, n)
q = lambda f: '"' + f.replace('"', '""') + '"'
with open(out_csv, 'w', encoding='utf-8', newline='') as f:
    for part in sorted(parts):
        for line in sorted(parts[part], key=lambda l: ','.join(q(x) for x in l)):
            f.write(','.join(q(x) for x in (part,) + line) + '\n')
valid_studies = {study(c) for c in allc}
exp = dict(rowsRead=cnt['read'], rowsRouted=cnt['study'] + cnt['glob'], studyRows=cnt['study'], globalRows=cnt['glob'],
    noConsentStudyRows=cnt['noStudy'], noConsentGlobalRows=cnt['noGlobal'], invalidConsentRowsRejected=cnt['invalid'],
    unroutableRows=cnt['unroutable'], invalidStudyNodeRows=cnt['invalidNode'],
    consentRowsRejected=sum(cnt['consentRejected'].values()), consentRowsRejectedByReason=dict(sorted(cnt['consentRejected'].items())),
    linesWritten=sum(len(v) for v in parts.values()),
    rowsPerPartition={k: len(v) for k, v in sorted(parts.items())},
    invalidConsentNamesDropped=[{'value': c, 'rows': cnt['droppedCopies'].get(c, 0)} for c in sorted(bad)],
    studyNodesWithoutConsents=sorted(cnt['studyNodesWithout']),
    studiesOnlyInRejectedConsentRows=sorted(studies - valid_studies), patientsWithConsents=len(consents))
json.dump(exp, open(out_json, 'w'), indent=2); open(out_json, 'a').write('\n')
print(json.dumps(exp, indent=1))
