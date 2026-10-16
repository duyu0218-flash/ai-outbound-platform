#!/usr/bin/env python3
"""Fail closed on dependency vulnerabilities and unreviewed high/medium SAST findings.

Reviews bind exact detector fingerprints, counts and source digests. They are not
vulnerability suppressions: raw reports and low-priority observations are retained.
"""
import collections
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def evaluate(module):
    target = module / 'target'
    bom = json.loads((target / 'java-dependencies.cdx.json').read_text())
    osv = json.loads((target / 'osv.json').read_text())
    reviews = json.loads((module / 'security/reviewed-findings.json').read_text())
    root = ET.parse(target / 'spotbugsXml.xml').getroot()
    errors = []
    components = {c['purl'].split('?')[0] for c in bom['components'] if c.get('purl', '').startswith('pkg:maven/')}
    scanned = set()
    advisories = set()
    for result in osv.get('results', []):
        for package in result.get('packages', []):
            p = package['package']
            if p.get('ecosystem') == 'Maven':
                scanned.add('pkg:maven/' + p['name'].replace(':', '/') + '@' + p['version'])
            advisories.update(v['id'] for v in package.get('vulnerabilities', []))
    if not components or not components.issubset(scanned):
        errors.append('OSV package coverage is incomplete')
    if advisories:
        errors.append('Dependency advisories: ' + ', '.join(sorted(advisories)))
    summary = root.find('FindBugsSummary')
    compiled = list((target / 'classes/com/duozhi/outbound').rglob('*.class'))
    analyzed = int(summary.get('total_classes', '0')) if summary is not None else 0
    scan_errors = root.find('Errors')
    if (not compiled or analyzed != len(compiled) or scan_errors is None
            or int(scan_errors.get('errors', '0')) or int(scan_errors.get('missingClasses', '0'))
            or scan_errors.findall('Error') or scan_errors.findall('MissingClass')):
        errors.append('SAST class coverage or analysis errors')
    findings = root.findall('BugInstance')
    counts = collections.Counter()
    for finding in findings:
        if int(finding.get('priority')) > 2:
            continue
        key = finding.get('type') + ':' + finding.get('instanceHash')
        counts[key] += 1
        review = reviews.get(key)
        if not review or not review.get('reason') or not review.get('evidence'):
            errors.append('Unreviewed finding: ' + key)
            continue
        source = finding.find('SourceLine').get('sourcepath')
        if source != review['source']:
            errors.append('Review source mismatch: ' + key)
        for path, digest in review['sha256'].items():
            if hashlib.sha256((module / path).read_bytes()).hexdigest() != digest:
                errors.append('Review invalidated by source change: ' + key + ': ' + path)
    for key, count in counts.items():
        if key in reviews and count != reviews[key]['count']:
            errors.append('Review occurrence count changed: ' + key)
    for key in reviews.keys() - counts.keys():
        errors.append('Stale review must be removed: ' + key)
    report = dict(passed=not errors, dependency_packages=len(components),
                  scanned_packages=len(scanned), dependency_advisories=sorted(advisories),
                  sast_classes=analyzed, sast_raw_findings=len(findings),
                  sast_reviewed_high_medium=sum(counts.values()),
                  sast_low_priority=sum(int(f.get('priority')) > 2 for f in findings),
                  sast_by_type=dict(collections.Counter(f.get('type') for f in findings)), errors=errors)
    (target / 'security-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    report = evaluate(Path(__file__).resolve().parents[1])
    print(json.dumps(report, indent=2))
    sys.exit(0 if report['passed'] else 1)
