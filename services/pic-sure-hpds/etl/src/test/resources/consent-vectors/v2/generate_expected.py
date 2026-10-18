# Independent derivation of expected-partitions.csv and expected-counts.json for consent-vectors/v2.
# It mirrors the rules of ConsentRouter / ConsentRowCheck (the loader), NOT the splitter's: a _consents row must pass the gate below
# before it grants a consent, and a row whose study node changes when the loader normalizes the path is not routed.
# One line is one record, as in the loader: a line that cannot be parsed costs that line only.
# usage: python3 -I generate_expected.py combined-allConcepts-input.csv expected-partitions.csv expected-counts.json
import csv, sys, json, re
inp, out_csv, out_json = sys.argv[1:4]
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
CONSENTS = '\\_consents\\'
def parse_line(line):  # one line = one record; None when it cannot be parsed (unterminated quote, bad escaping)
    try: return next(csv.reader([line], strict=True))
    except (csv.Error, StopIteration): return None
lines = [l for l in open(inp, encoding='utf-8-sig', newline='').read().split('\n')]
lines = [l.rstrip('\r') for l in lines]
while lines and lines[-1] == '': lines.pop()
while lines and lines[0].strip() == '': lines.pop(0)
first = parse_line(lines[0])
header = first is not None and len(first) >= 4 and first[0].strip().lower() in ('patient_num', 'subject_id', 'patient_id') \
    and first[1].strip().lower() in ('concept_path', 'conceptpath', 'concept_cd', 'concept')
data = [(l, parse_line(l)) for l in (lines[1:] if header else lines) if l != '']
norm = lambda p: p.replace('µ', '\\')
def study(c): return c.split('.')[0]
def node(p):
    parts = re.split(r'[\\µ]', p)
    return parts[1] if len(parts) > 1 else ''
def pint(s):  # the loader trims the patient id of a data row
    try: return int(s.strip())
    except: return None
def pint_strict(s):  # Integer.parseInt, untrimmed, at most 11 characters, as the splitter reads a consent row
    if not s or len(s) > 11 or not re.fullmatch(r'[+-]?[0-9]+', s): return None
    v = int(s)
    return v if -2**31 <= v < 2**31 else None
def java_trim(s): return s.strip(''.join(chr(i) for i in range(33)))
def java_split(path):  # String.split("\\\\"): trailing empty strings are dropped
    parts = path.split('\\')
    while parts and parts[-1] == '': parts.pop()
    return parts if parts else ['']
def join_trimmed(path): return '\\'.join(java_trim(x) for x in java_split(path)) + '\\'
def normalize_path(p):
    p = join_trimmed(norm(p)).replace('\ufffd', '')
    return join_trimmed(p).replace('\ufffd', '')
def timestamp_ok(ts):  # blank, an integer that fits a long, or the ISO forms of the splitter (forms used by the vector only)
    if re.fullmatch(r'-?[0-9]*', ts): return ts == '' or -2**63 <= int(ts) < 2**63
    return bool(re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}([T ][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?)?Z?', ts))
def gate(r):  # None when the _consents row may grant, else why not
    if len(r) != 5: return 'BAD_FIELD_COUNT'
    if pint_strict(r[0]) is None: return 'BAD_PATIENT_NUM'
    if r[2] != '':
        try: float(r[2].strip()); return 'NUMERIC_NVAL'
        except ValueError: return 'BAD_NVAL'
    if not timestamp_ok(r[4]): return 'BAD_TIMESTAMP'
    return None
is_consent_row = lambda r: len(r) >= 2 and norm(r[1]) == CONSENTS
consents = {}
for raw, r in data:
    if r is not None and is_consent_row(r) and gate(r) is None and r[3].strip():
        consents.setdefault(pint_strict(r[0]), set()).add(r[3].strip())
allc = set().union(*consents.values())
bad = {c for c in allc if not NAME.match(c) or '..' in c}
studies = {study(c) for c in allc}
parts = {}
cnt = dict(read=0, study=0, glob=0, noStudy=0, noGlobal=0, invalid=0, unroutable=0, invalidNode=0, studyNodesWithout=set(), droppedCopies={},
           consentRejected={})
def reject(why): cnt['consentRejected'][why] = cnt['consentRejected'].get(why, 0) + 1
for raw, r in data:
    cnt['read'] += 1
    if r is None:  # an unparsable line that mentions the consents variable grants nothing; any other one is a lost row
        if '_consents' in raw: reject('UNPARSABLE_LINE')
        else: cnt['unroutable'] += 1
        continue
    if is_consent_row(r):
        g = gate(r)
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
        for c in ok: parts.setdefault(c, []).append(line)
        if ok: cnt['study'] += 1
        elif mine: cnt['invalid'] += 1
        else: cnt['noStudy'] += 1
    else:
        if re.fullmatch(r'phs[0-9]{6}', n): cnt['studyNodesWithout'].add(n)
        if not cs: cnt['noGlobal'] += 1; continue
        ok = [c for c in cs if c not in bad]
        for c in cs:
            if c in bad: cnt['droppedCopies'][c] = cnt['droppedCopies'].get(c, 0) + 1
        for c in ok: parts.setdefault(c, []).append(line)
        if ok: cnt['glob'] += 1
        else: cnt['invalid'] += 1
q = lambda f: '"' + f.replace('"', '""') + '"'
with open(out_csv, 'w', encoding='utf-8', newline='') as f:
    for part in sorted(parts):
        for line in sorted(parts[part], key=lambda l: ','.join(q(x) for x in l)):
            f.write(','.join(q(x) for x in (part,) + line) + '\n')
exp = dict(rowsRead=cnt['read'], rowsRouted=cnt['study'] + cnt['glob'], studyRows=cnt['study'], globalRows=cnt['glob'],
    noConsentStudyRows=cnt['noStudy'], noConsentGlobalRows=cnt['noGlobal'], invalidConsentRowsRejected=cnt['invalid'],
    unroutableRows=cnt['unroutable'], invalidStudyNodeRows=cnt['invalidNode'],
    consentRowsRejected=sum(cnt['consentRejected'].values()), consentRowsRejectedByReason=dict(sorted(cnt['consentRejected'].items())),
    linesWritten=sum(len(v) for v in parts.values()),
    rowsPerPartition={k: len(v) for k, v in sorted(parts.items())},
    invalidConsentNamesDropped=[{'value': c, 'rows': cnt['droppedCopies'].get(c, 0)} for c in sorted(bad)],
    studyNodesWithoutConsents=sorted(cnt['studyNodesWithout']), patientsWithConsents=len(consents))
json.dump(exp, open(out_json, 'w'), indent=2); open(out_json, 'a').write('\n')
print(json.dumps(exp, indent=1))
