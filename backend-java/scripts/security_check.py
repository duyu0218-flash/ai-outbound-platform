#!/usr/bin/env python3
"""Fail closed on dependency vulnerabilities and every unreviewed SAST finding.

Reviews bind exact source locations, counts and the complete runtime source scope.
Route observations have a separate access-control inventory. Raw reports stay intact.
"""
import collections
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def finding_key(finding):
    # SpotBugs instanceHash includes taint-source ordering, which differs between
    # macOS and Linux JDKs. Bind the exact method/source location instead, plus
    # full source SHA-256 and occurrence count below; never just the detector type.
    source = finding.find('SourceLine')
    method = finding.find('Method')
    return ':'.join([finding.get('type'), source.attrib['sourcepath'],
                     method.attrib['classname'], method.attrib['name'],
                     method.attrib['signature'], source.attrib['start'],
                     source.attrib['end'], finding.attrib['priority']])


def evaluate(module):
    target = module / 'target'
    bom = json.loads((target / 'java-dependencies.cdx.json').read_text())
    osv = json.loads((target / 'osv.json').read_text())
    reviews = json.loads((module / 'security/reviewed-findings.json').read_text())
    entrypoints = json.loads((module / 'security/entrypoints.json').read_text())
    scope = json.loads((module / 'security/review-scope.json').read_text())
    root = ET.parse(target / 'spotbugsXml.xml').getroot()
    errors = []
    source_files = {str(p.relative_to(module)) for directory in ['src/main/java', 'src/main/resources']
                    for p in (module / directory).rglob('*') if p.is_file()}
    if source_files != set(scope):
        errors.append('Runtime source scope changed; security review required')
    for path, digest in scope.items():
        source = module / path
        if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            errors.append('Runtime source review invalidated: ' + path)
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
    compiled = {str(p.relative_to(target / 'classes'))[:-6].replace('/', '.')
                for p in (target / 'classes').rglob('*.class')}
    analyzed_classes = {node.attrib['class'] for node in root.findall('.//ClassStats')}
    analyzed = int(summary.get('total_classes', '0')) if summary is not None else 0
    scan_errors = root.find('Errors')
    if (not compiled or analyzed != len(compiled) or analyzed_classes != compiled or scan_errors is None
            or int(scan_errors.get('errors', '0')) or int(scan_errors.get('missingClasses', '0'))
            or scan_errors.findall('Error') or scan_errors.findall('MissingClass')):
        errors.append('SAST class coverage or analysis errors')
    findings = root.findall('BugInstance')
    counts = collections.Counter()
    route_counts = collections.Counter()
    for finding in findings:
        key = finding_key(finding)
        if finding.get('type') == 'SPRING_ENDPOINT':
            route_counts[key] += 1
            route = entrypoints.get(key)
            if (int(finding.get('priority')) != 3 or not route or not route.get('access')
                    or not route.get('evidence')):
                errors.append('Unreviewed or changed route: ' + key)
            continue
        counts[key] += 1
        review = reviews.get(key)
        if not review or not review.get('reason') or not review.get('evidence'):
            errors.append('Unreviewed finding: ' + key)
            continue
        source = finding.find('SourceLine').get('sourcepath')
        if source != review['source']:
            errors.append('Review source mismatch: ' + key)
        if 'src/main/java/' + source not in review.get('sha256', {}):
            errors.append('Review must bind its own source: ' + key)
        for path, digest in review['sha256'].items():
            if hashlib.sha256((module / path).read_bytes()).hexdigest() != digest:
                errors.append('Review invalidated by source change: ' + key + ': ' + path)
    for key, count in counts.items():
        if key in reviews and count != reviews[key]['count']:
            errors.append('Review occurrence count changed: ' + key)
    for key in reviews.keys() - counts.keys():
        errors.append('Stale review must be removed: ' + key)
    for key in entrypoints.keys() - route_counts.keys():
        errors.append('Stale route review must be removed: ' + key)
    for key, count in route_counts.items():
        if key in entrypoints and entrypoints[key].get('count') != count:
            errors.append('Route occurrence count changed: ' + key)
    report = dict(passed=not errors, dependency_packages=len(components),
                  scanned_packages=len(scanned), dependency_advisories=sorted(advisories),
                  sast_classes=analyzed, sast_raw_findings=len(findings),
                  sast_reviewed_high_medium=sum(int(f.get('priority')) <= 2 for f in findings),
                  sast_reviewed_low_priority=sum(int(f.get('priority')) == 3 and f.get('type') != 'SPRING_ENDPOINT' for f in findings),
                  sast_reviewed_actionable=sum(counts.values()),
                  sast_reviewed_entrypoints=sum(route_counts.values()),
                  sast_unreviewed_findings=len([e for e in errors if 'Unreviewed' in e]),
                  source_scope_files=len(scope),
                  sast_low_priority=sum(int(f.get('priority')) > 2 for f in findings),
                  sast_by_type=dict(collections.Counter(f.get('type') for f in findings)), errors=errors)
    (target / 'security-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    report = evaluate(Path(__file__).resolve().parents[1])
    print(json.dumps(report, indent=2))
    sys.exit(0 if report['passed'] else 1)
