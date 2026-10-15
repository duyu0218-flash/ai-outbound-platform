"""Record Java route presence, not behavioral equivalence or acceptance.

Uses the simple literal controller annotations in this migration. Runtime and
business tests remain necessary; a mapped route is not a completed migration.
"""
import json
import re
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]


def key(method, path):
    return method, re.sub(r"\{[^}]+\}", "{}", path)


def update_inventory():
    mappings = {}
    for path in (MODULE / "src/main/java/com/duozhi/outbound").glob("*.java"):
        text = path.read_text()
        prefix = re.search(r'@RequestMapping\("([^"]+)"\)', text)
        prefix = prefix.group(1) if prefix else ""
        for method, arguments in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?', text):
            for suffix in re.findall(r'"([^"]*)"', arguments) or [""]:
                mappings[key(method.upper(), prefix + suffix)] = path.name
    partial = {
        key("POST", "/api/v1/campaigns/{id}/start"): "仅支持 auto_dial=false；实际拨号返回 503，且不写入待拨数据",
        key("POST", "/api/v1/campaigns/{id}/resume"): "尚未接入执行器，合法恢复请求返回 503，不修改状态",
        key("GET", "/api/v1/admin/system-overview"): "容量与数据库统计已迁移；Redis、AI、电话服务探针标记 unverified",
    }
    output = MODULE / "src/main/resources/contracts/migration-inventory.json"
    inventory = json.loads(output.read_text())
    inventory["status_meaning"] = "mapped 表示 Java 存在路由；不表示行为等价或验收通过。具体测试见 ACCEPTANCE.md。"
    for route in inventory["routes"]:
        route_key = key(route["method"], route["path"])
        route["java_status"] = "partial" if route_key in partial else "mapped" if route_key in mappings else "pending"
        route.pop("java_source", None)
        route.pop("limitation", None)
        if route_key in mappings:
            route["java_source"] = mappings[route_key]
        if route_key in partial:
            route["limitation"] = partial[route_key]
    output.write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + "\n")
    counts = {state: sum(r["java_status"] == state for r in inventory["routes"]) for state in ("mapped", "partial", "pending")}
    print(json.dumps(counts))


if __name__ == "__main__":
    update_inventory()
