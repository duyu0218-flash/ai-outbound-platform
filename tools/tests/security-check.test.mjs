import { test } from "node:test";
import { fs, path, os, assert, sha, writeJSON } from "../lib/common.mjs";
import { evaluate } from "../security-check.mjs";
function fixture(fn) {
  const m = fs.mkdtempSync(path.join(os.tmpdir(), "security-gate-"));
  try {
    for (const p of [
      "target/classes/com/example",
      "security",
      "src/main/java/com/example",
    ])
      fs.mkdirSync(path.join(m, p), { recursive: true });
    fs.writeFileSync(
      path.join(m, "target/classes/com/example/Example.class"),
      "fixture",
    );
    const source = "src/main/java/com/example/Example.java";
    fs.writeFileSync(path.join(m, source), "reviewed");
    const write = (p, v) => writeJSON(path.join(m, p), v);
    const bug = (type = "TEST_SQL", priority = 2) =>
      `<BugInstance type="${type}" priority="${priority}" instanceHash="fixture"><Method classname="com.example.Example" name="query" signature="()V"/><SourceLine sourcepath="com/example/Example.java" start="12" end="12"/></BugInstance>`;
    const key = (type = "TEST_SQL", priority = 2) =>
      `${type}:com/example/Example.java:com.example.Example:query:()V:12:12:${priority}`;
    const xml = (bugs = "", classes = 1, missing = 0) =>
      fs.writeFileSync(
        path.join(m, "target/spotbugsXml.xml"),
        `<BugCollection><FindBugsSummary total_classes="${classes}"><PackageStats><ClassStats class="com.example.Example"/></PackageStats></FindBugsSummary><Errors errors="0" missingClasses="${missing}"/>${bugs}</BugCollection>`,
      );
    const review = (type = "TEST_SQL", priority = 2, count = 1) => {
      xml(bug(type, priority).repeat(count));
      write("security/reviewed-findings.json", {
        [key(type, priority)]: {
          source: "com/example/Example.java",
          count: 1,
          reason: "fixture",
          evidence: "fixture",
          sha256: { [source]: sha(path.join(m, source)) },
        },
      });
    };
    write("target/java-dependencies.cdx.json", {
      components: [{ purl: "pkg:maven/org.example/library@1.0?type=jar" }],
    });
    write("target/osv.json", {
      results: [
        {
          packages: [
            {
              package: {
                ecosystem: "Maven",
                name: "org.example:library",
                version: "1.0",
              },
            },
          ],
        },
      ],
    });
    write("security/reviewed-findings.json", {});
    write("security/entrypoints.json", {});
    write("security/review-scope.json", {
      [source]: sha(path.join(m, source)),
    });
    xml();
    fn({
      m,
      source,
      write,
      xml,
      review,
      key,
      read: (p) => JSON.parse(fs.readFileSync(path.join(m, p))),
    });
  } finally {
    fs.rmSync(m, { recursive: true, force: true });
  }
}
const check = (name, fn) => test(name, () => fixture(fn));
check("clean complete scan passes", ({ m }) => assert(evaluate(m).passed));
check("any advisory blocks", ({ m, read, write }) => {
  const r = read("target/osv.json");
  r.results[0].packages[0].vulnerabilities = [{ id: "GHSA-test" }];
  write("target/osv.json", r);
  assert(!evaluate(m).passed);
});
check("missing dependency coverage blocks", ({ m, write }) => {
  write("target/osv.json", { results: [] });
  assert(!evaluate(m).passed);
});
check("missing class and incomplete analysis block", ({ m, xml }) => {
  xml("", 0);
  assert(!evaluate(m).passed);
  xml("", 1, 1);
  assert(!evaluate(m).passed);
});
check("unreviewed finding blocks", ({ m, review, write }) => {
  review();
  write("security/reviewed-findings.json", {});
  assert(!evaluate(m).passed);
});
check("exact source review passes", ({ m, review }) => {
  review();
  assert(evaluate(m).passed);
});
check("source change invalidates review", ({ m, source, review }) => {
  review();
  fs.writeFileSync(path.join(m, source), "changed");
  assert(!evaluate(m).passed);
});
check("finding occurrence counts bound", ({ m, review }) => {
  review("TEST_SQL", 2, 2);
  assert(!evaluate(m).passed);
});
check("stale reviews block", ({ m, review, xml }) => {
  review();
  xml();
  assert(!evaluate(m).passed);
});
check("taint ordering may change but location cannot", ({ m, review }) => {
  review();
  const p = path.join(m, "target/spotbugsXml.xml");
  fs.writeFileSync(
    p,
    fs
      .readFileSync(p, "utf8")
      .replace('instanceHash="fixture"', 'instanceHash="linux"'),
  );
  assert(evaluate(m).passed);
  fs.writeFileSync(
    p,
    fs.readFileSync(p, "utf8").replace('start="12"', 'start="13"'),
  );
  assert(!evaluate(m).passed);
});
check("malformed and missing reports fail closed", ({ m }) => {
  const p = path.join(m, "target/osv.json");
  fs.writeFileSync(p, "incomplete");
  assert.throws(() => evaluate(m));
  fs.rmSync(p);
  assert.throws(() => evaluate(m));
});
check("low priority still requires review", ({ m, review, write }) => {
  review("TEST_SQL", 3);
  assert(evaluate(m).passed);
  write("security/reviewed-findings.json", {});
  assert(!evaluate(m).passed);
});
check(
  "routes require access inventory and cannot be stale",
  ({ m, review, write, key, xml }) => {
    review("SPRING_ENDPOINT", 3);
    write("security/reviewed-findings.json", {});
    assert(!evaluate(m).passed);
    write("security/entrypoints.json", {
      [key("SPRING_ENDPOINT", 3)]: {
        access: "authenticated",
        count: 1,
        evidence: "fixture",
      },
    });
    assert(evaluate(m).passed);
    xml();
    assert(!evaluate(m).passed);
  },
);
check(
  "unchanged sink does not permit edited caller",
  ({ m, source, review, read, write }) => {
    review();
    const caller = "src/main/java/com/example/Caller.java";
    fs.writeFileSync(path.join(m, caller), "trusted");
    write("security/review-scope.json", {
      ...read("security/review-scope.json"),
      [caller]: sha(path.join(m, caller)),
    });
    assert(evaluate(m).passed);
    fs.writeFileSync(path.join(m, caller), "unreviewed");
    assert(!evaluate(m).passed);
  },
);
check("new runtime source blocks", ({ m }) => {
  fs.writeFileSync(path.join(m, "src/main/java/com/example/New.java"), "new");
  assert(!evaluate(m).passed);
});
check("equal counts cannot hide wrong analyzed class", ({ m }) => {
  const p = path.join(m, "target/spotbugsXml.xml");
  fs.writeFileSync(
    p,
    fs
      .readFileSync(p, "utf8")
      .replace('class="com.example.Example"', 'class="com.example.Other"'),
  );
  assert(!evaluate(m).passed);
});
check("review must bind its own source", ({ m, review, read, write }) => {
  review();
  const r = read("security/reviewed-findings.json");
  Object.values(r)[0].sha256 = {};
  write("security/reviewed-findings.json", r);
  assert(!evaluate(m).passed);
});
check("DTD and external entities rejected", ({ m }) => {
  fs.writeFileSync(
    path.join(m, "target/spotbugsXml.xml"),
    '<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><BugCollection>&e;</BugCollection>',
  );
  assert.throws(() => evaluate(m));
});
