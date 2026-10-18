import csv, sys, json, re
inp, out_csv, out_json = sys.argv[1:4]
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
rows = list(csv.reader(open(inp, encoding='utf-8', newline='')))
rows = [r for r in rows if r]
header = rows[0][0].lower() == 'patient_num'
data = rows[1:] if header else rows
norm = lambda p: p.replace('µ', '\\')
def study(c): return c.split('.')[0]
def node(p):
    parts = re.split(r'[\\µ]', p)
    return parts[1] if len(parts) > 1 else ''
def pint(s):
    try: return int(s.strip())
    except: return None
consents = {}
for r in data:
    if len(r) >= 4 and norm(r[1]) == '\\_consents\\' and pint(r[0]) is not None and r[3].strip():
        consents.setdefault(pint(r[0]), set()).add(r[3].strip())
allc = set().union(*consents.values())
bad = {c for c in allc if not NAME.match(c) or '..' in c}
studies = {study(c) for c in allc}
parts = {}
cnt = dict(read=0, study=0, glob=0, noStudy=0, noGlobal=0, invalid=0, unroutable=0, studyNodesWithout=set(), droppedCopies={})
for r in data:
    cnt['read'] += 1
    if len(r) < 4 or pint(r[0]) is None:
        cnt['unroutable'] += 1; continue
    p = pint(r[0]); n = node(r[1]); cs = sorted(consents.get(p, []))
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
    unroutableRows=cnt['unroutable'], linesWritten=sum(len(v) for v in parts.values()),
    rowsPerPartition={k: len(v) for k, v in sorted(parts.items())},
    invalidConsentNamesDropped=[{'value': c, 'rows': cnt['droppedCopies'].get(c, 0)} for c in sorted(bad)],
    studyNodesWithoutConsents=sorted(cnt['studyNodesWithout']), patientsWithConsents=len(consents))
json.dump(exp, open(out_json, 'w'), indent=2); open(out_json, 'a').write('\n')
print(json.dumps(exp, indent=1))
