from __future__ import annotations

import json
from .dialogue_rules import keyword_match

from ..schemas import ScriptFlowGraph, ScriptFlowSimulateOut


class FlowValidationError(ValueError):
    pass


def validate_graph(graph: ScriptFlowGraph) -> None:
    node_ids = [node.id for node in graph.nodes]
    edge_ids = [edge.id for edge in graph.edges]
    if len(node_ids) != len(set(node_ids)):
        raise FlowValidationError("duplicate node id")
    if len(edge_ids) != len(set(edge_ids)):
        raise FlowValidationError("duplicate edge id")
    starts = [node for node in graph.nodes if node.type == "start"]
    if len(starts) != 1:
        raise FlowValidationError("flow must contain exactly one start node")
    known = set(node_ids)
    outgoing: dict[str, list] = {}
    for edge in graph.edges:
        if edge.source not in known or edge.target not in known:
            raise FlowValidationError(f"edge endpoint not found: {edge.id}")
        if edge.source == edge.target:
            raise FlowValidationError(f"self-loop is not allowed: {edge.id}")
        if edge.condition == "keyword" and not any(word.strip() for word in edge.keywords):
            raise FlowValidationError(f"keyword edge requires keywords: {edge.id}")
        if edge.condition in {'equals','not_equals'} and not edge.variable:
            raise FlowValidationError(f'variable condition requires a variable: {edge.id}')
        if edge.condition == 'intent' and not edge.keywords:
            raise FlowValidationError(f'intent condition requires an intent: {edge.id}')
        outgoing.setdefault(edge.source, []).append(edge)
    node_map = {node.id: node for node in graph.nodes}
    if any(edge.target == starts[0].id for edge in graph.edges):
        raise FlowValidationError("start node cannot have incoming edges")
    for source, edges in outgoing.items():
        if node_map[source].type in {"handoff", "hangup"}:
            raise FlowValidationError(f"terminal node cannot have outgoing edges: {source}")
        for condition in ("always", "silence"):
            if sum(edge.condition == condition for edge in edges) > 1:
                raise FlowValidationError(f"node can only have one {condition} edge: {source}")
    for node in graph.nodes:
        if node.type in {'collect','set'} and not node.variable:
            raise FlowValidationError(f'node requires a variable: {node.id}')
        if node.type not in {"handoff", "hangup"} and not outgoing.get(node.id):
            raise FlowValidationError(f"non-terminal node requires an outgoing edge: {node.id}")
    # Reject automatic cycles at publish time, before a live call can enter one.
    automatic={node.id for node in graph.nodes if node.type in {'set','branch'}}
    for start in automatic:
        visited=set();stack=[edge.target for edge in outgoing.get(start,[])]
        while stack:
            target=stack.pop()
            if target==start:raise FlowValidationError('automatic nodes contain a cycle')
            if target not in automatic or target in visited:continue
            visited.add(target);stack.extend(edge.target for edge in outgoing.get(target,[]))
    reachable = {starts[0].id}
    pending = [starts[0].id]
    while pending:
        source = pending.pop()
        for edge in outgoing.get(source, []):
            if edge.target not in reachable:
                reachable.add(edge.target)
                pending.append(edge.target)
    unreachable = sorted(known - reachable)
    if unreachable:
        raise FlowValidationError(f"unreachable nodes: {', '.join(unreachable)}")
    if not any(node.id in reachable and node.type in {"handoff", "hangup"} for node in graph.nodes):
        raise FlowValidationError("flow requires a reachable terminal node")


def dump_graph(graph: ScriptFlowGraph) -> str:
    validate_graph(graph)
    return json.dumps(graph.model_dump(), ensure_ascii=False, separators=(",", ":"))


def load_graph(value: str) -> ScriptFlowGraph:
    try:
        return ScriptFlowGraph.model_validate(json.loads(value or "{}"))
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise FlowValidationError("invalid flow graph") from exc


def default_graph(opening: str = "") -> ScriptFlowGraph:
    return ScriptFlowGraph.model_validate({
        "nodes": [
            {"id": "start", "type": "start", "label": "开始", "position": {"x": 80, "y": 180}},
            {"id": "opening", "type": "message", "label": "开场白", "prompt": opening, "position": {"x": 320, "y": 180}},
            {"id": "listen", "type": "listen", "label": "等待客户回答", "position": {"x": 580, "y": 180}},
            {"id": "hangup", "type": "hangup", "label": "结束通话", "position": {"x": 840, "y": 180}},
        ],
        "edges": [
            {"id": "e-start-opening", "source": "start", "target": "opening", "condition": "always"},
            {"id": "e-opening-listen", "source": "opening", "target": "listen", "condition": "always"},
            {"id": "e-listen-hangup", "source": "listen", "target": "hangup", "condition": "always"},
        ],
    })


def simulate(graph: ScriptFlowGraph, current_node_id: str | None, transcript: str, silence: bool, variables: dict | None = None) -> ScriptFlowSimulateOut:
    validate_graph(graph)
    node_map = {node.id: node for node in graph.nodes}
    current = node_map.get(current_node_id or "") or next(node for node in graph.nodes if node.type == "start")
    from .dialogue_rules import classify
    import re
    values = dict(variables or {})
    if current.type == 'collect':
        if not transcript.strip() or classify(transcript) in {'question','unclear'}:
            return ScriptFlowSimulateOut(current_node_id=current.id,action='speak',prompt=current.prompt,variables=values)
        values[current.variable] = transcript.strip()[:500]
    if current.type == 'set':
        values[current.variable] = current.value
    candidates = [edge for edge in graph.edges if edge.source == current.id]
    normalized = transcript.casefold()
    matched = next((e for e in candidates if e.condition == "silence" and silence), None)
    if matched is None:
        matched = next((e for e in candidates if e.condition == "keyword" and any(keyword_match(transcript, k) for k in e.keywords if k.strip())), None)
    if matched is None:
        matched = next((e for e in candidates if
            (e.condition=='intent' and classify(transcript) in e.keywords) or
            (e.condition=='equals' and e.variable in values and values[e.variable]==e.value) or
            (e.condition=='not_equals' and e.variable in values and values[e.variable]!=e.value)),None)
    if matched is None:
        matched = next((e for e in candidates if e.condition == "always"), None)
    target = node_map.get(matched.target) if matched else None
    action = "wait"
    if target:
        action = {"message": "speak", "listen": "listen", "handoff": "handoff", "hangup": "hangup", "start": "continue", 'collect':'speak','set':'continue','branch':'continue'}[target.type]
    return ScriptFlowSimulateOut(
        current_node_id=current.id,
        next_node_id=target.id if target else None,
        action=action,
        prompt=re.sub(r'\{([a-zA-Z0-9_]+)\}',lambda m:str(values.get(m[1],'（待确认）')),target.prompt) if target else "",
        matched_edge_id=matched.id if matched else None,
        variables=values,
    )
