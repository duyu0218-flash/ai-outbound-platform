import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from security_check import evaluate, finding_key
import xml.etree.ElementTree as ET


class SecurityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.module = Path(self.temporary.name)
        for path in ['target/classes/com/duozhi/outbound', 'security', 'src/main/java/com/duozhi/outbound']:
            (self.module / path).mkdir(parents=True)
        (self.module / 'target/classes/com/duozhi/outbound/Example.class').write_bytes(b'fixture')
        self.source = 'src/main/java/com/duozhi/outbound/Example.java'
        (self.module / self.source).write_text('reviewed source')
        self.write_json('target/java-dependencies.cdx.json', {'components': [{'purl': 'pkg:maven/org.example/library@1.0?type=jar'}]})
        self.package = {'package': {'ecosystem': 'Maven', 'name': 'org.example:library', 'version': '1.0'}}
        self.osv()
        self.xml()
        self.write_json('security/reviewed-findings.json', {})

    def write_json(self, path, data):
        (self.module / path).write_text(json.dumps(data))

    def osv(self):
        self.write_json('target/osv.json', {'results': [{'packages': [self.package]}]})

    def xml(self, bug='', classes=1, missing=0):
        (self.module / 'target/spotbugsXml.xml').write_text(
            f'<BugCollection><FindBugsSummary total_classes="{classes}"/>'
            f'<Errors errors="0" missingClasses="{missing}"/>{bug}</BugCollection>')

    def finding(self, count=1):
        bug = '<BugInstance type="TEST_SQL" priority="2" instanceHash="fixture"><Method classname="com.duozhi.outbound.Example" name="query" signature="()V"/><SourceLine sourcepath="com/duozhi/outbound/Example.java" start="12" end="12"/></BugInstance>'
        self.xml(bug * count)
        self.write_json('security/reviewed-findings.json', {finding_key(ET.fromstring(bug)): {
            'source': 'com/duozhi/outbound/Example.java', 'count': 1,
            'reason': 'Test fixture review', 'evidence': 'Test fixture evidence',
            'sha256': {self.source: hashlib.sha256((self.module / self.source).read_bytes()).hexdigest()}}})

    def test_clean_complete_scan_passes(self):
        self.assertTrue(evaluate(self.module)['passed'])

    def test_any_dependency_advisory_blocks(self):
        self.package['vulnerabilities'] = [{'id': 'GHSA-test-fixture'}]
        self.osv()
        self.assertFalse(evaluate(self.module)['passed'])

    def test_incomplete_dependency_scan_blocks(self):
        self.write_json('target/osv.json', {'results': []})
        self.assertFalse(evaluate(self.module)['passed'])

    def test_missing_class_or_incomplete_analysis_blocks(self):
        for classes, missing in [(0, 0), (1, 1)]:
            self.xml(classes=classes, missing=missing)
            self.assertFalse(evaluate(self.module)['passed'])

    def test_unreviewed_finding_blocks(self):
        self.finding()
        self.write_json('security/reviewed-findings.json', {})
        self.assertFalse(evaluate(self.module)['passed'])

    def test_review_passes_only_exact_source_and_count(self):
        self.finding()
        self.assertTrue(evaluate(self.module)['passed'])
        (self.module / self.source).write_text('unreviewed edit')
        self.assertFalse(evaluate(self.module)['passed'])
        self.finding(count=2)
        self.assertFalse(evaluate(self.module)['passed'])

    def test_stale_review_blocks(self):
        self.finding()
        self.xml()
        self.assertFalse(evaluate(self.module)['passed'])

    def test_cross_platform_taint_order_is_stable_but_changed_location_blocks(self):
        self.finding()
        report = self.module / 'target/spotbugsXml.xml'
        report.write_text(report.read_text().replace('instanceHash="fixture"', 'instanceHash="linux-taint-order"'))
        self.assertTrue(evaluate(self.module)['passed'])
        report.write_text(report.read_text().replace('start="12"', 'start="13"'))
        self.assertFalse(evaluate(self.module)['passed'])

    def test_missing_or_malformed_report_never_passes(self):
        (self.module / 'target/osv.json').write_text('incomplete')
        with self.assertRaises(ValueError):
            evaluate(self.module)
